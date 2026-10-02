package com.sedayar.app.audio;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Pure-Java WAV (RIFF/WAVE) reader: converts a PCM WAV of any sample rate,
 * bit depth (8/16/24/32-bit int or 32-bit float) and channel count into the
 * 16 kHz mono 16-bit WAV that Vosk expects — without touching MediaCodec.
 *
 * Why: MediaCodec/extractor fail on some devices even for plain WAV files
 * (some builds also choke on float PCM), which produced "decode failed"
 * errors in the audio-file tab. WAV never needs a codec, so we handle it
 * ourselves and keep MediaCodec for compressed formats only.
 *
 * Pure Java — covered by unit tests in src/test and reused by the desktop
 * end-to-end harness, so the exact code that ships is the code that gets
 * tested.
 */
public final class WavFileReader {

    private WavFileReader() {
    }

    /** Quick sniff: does the file start with a RIFF/WAVE header? */
    public static boolean isWav(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] h = new byte[12];
            return in.read(h) == 12
                    && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                    && h[8] == 'W' && h[9] == 'A' && h[10] == 'V' && h[11] == 'E';
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Converts {@code in} (a PCM WAV) into a 16 kHz mono WAV at {@code out}.
     *
     * @return true when the file was a supported PCM WAV and the conversion
     *         finished; false when the layout is unsupported (e.g. ADPCM) —
     *         the caller should fall back to the MediaCodec path.
     */
    public static boolean toWav16kMono(File in, File out,
                                       AudioDecoder.Progress progress) throws IOException {
        try (FileInputStream fin = new FileInputStream(in)) {
            byte[] riff = readFully(fin, 12);
            if (!(riff[0] == 'R' && riff[1] == 'I' && riff[2] == 'F' && riff[3] == 'F'
                    && riff[8] == 'W' && riff[9] == 'A' && riff[10] == 'V' && riff[11] == 'E')) {
                return false;
            }

            // ---- scan chunks for fmt / data --------------------------------
            int audioFormat = -1, channels = 0, sampleRate = 0, bits = 0;
            boolean fmtFound = false;
            long dataLen = -1;
            ByteBuffer le = ByteBuffer.wrap(riff).order(ByteOrder.LITTLE_ENDIAN);

            while (true) {
                byte[] hdr = readFully(fin, 8);
                String id = new String(hdr, 0, 4, "US-ASCII");
                long size = (hdr[4] & 0xFFL) | (hdr[5] & 0xFFL) << 8
                        | (hdr[6] & 0xFFL) << 16 | (hdr[7] & 0xFFL) << 24;

                if ("fmt ".equals(id)) {
                    byte[] fmt = readFully(fin, (int) Math.min(size, 40L));
                    le = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN);
                    audioFormat = le.getShort(0) & 0xFFFF;
                    channels = le.getShort(2) & 0xFFFF;
                    sampleRate = le.getInt(4);
                    bits = le.getShort(14) & 0xFFFF;
                    // WAVE_FORMAT_EXTENSIBLE (0xFFFE): real format in the subformat GUID
                    if (audioFormat == 0xFFFE && fmt.length >= 26) {
                        audioFormat = le.getShort(24) & 0xFFFF;
                    }
                    fmtFound = true;
                    if (size % 2 == 1) {
                        fin.skip(1);
                    }
                } else if ("data".equals(id)) {
                    if (!fmtFound) {
                        return false; // data before fmt — malformed
                    }
                    if (!supported(audioFormat, channels, sampleRate, bits)) {
                        return false; // let MediaCodec try (ADPCM etc.)
                    }
                    dataLen = size;
                    convert(fin, out, channels, sampleRate, bits, audioFormat,
                            dataLen, progress);
                    return true;
                } else {
                    long skipped = 0;
                    while (skipped < size) {
                        long s = fin.skip(size - skipped);
                        if (s <= 0) {
                            break;
                        }
                        skipped += s;
                    }
                    if (skipped < size) {
                        // truncated chunk — if we never saw fmt, give up
                        return false;
                    }
                    if (size % 2 == 1) {
                        fin.skip(1);
                    }
                }
            }
        }
    }

    private static boolean supported(int format, int channels, int rate, int bits) {
        boolean pcm = format == 1 || format == 3;
        boolean depth = (bits == 8 || bits == 16 || bits == 24 || bits == 32);
        return pcm && depth && channels >= 1 && channels <= 8 && rate >= 4000 && rate <= 192000;
    }

    // ------------------------------------------------------------- conversion

    private static void convert(FileInputStream fin, File out,
                                int channels, int sampleRate, int bits, int format,
                                long dataLen, AudioDecoder.Progress progress)
            throws IOException {
        int frameBytes = channels * (bits / 8);
        int framesTotal = dataLen > 0 ? (int) Math.min(Integer.MAX_VALUE - 1,
                dataLen / frameBytes) : 0;

        try (FileOutputStream fos = new FileOutputStream(out)) {
            AudioDecoder.writeWavHeader(fos);
            Resampler resampler = new Resampler(sampleRate, 1);
            long written = 0;
            long framesDone = 0;
            // 20 ms chunks at the input rate — small, steady memory
            int chunkFrames = Math.max(64, (sampleRate / 50));
            byte[] raw = new byte[chunkFrames * frameBytes];

            while (true) {
                int n = readSome(fin, raw, dataLen, framesDone * frameBytes);
                if (n <= 0) {
                    break;
                }
                int whole = n / frameBytes;
                if (whole <= 0) {
                    break;
                }
                short[] mono = downmix(raw, whole, channels, bits, format);
                written += resampler.resampleMono(mono, whole, fos);
                framesDone += whole;
                if (progress != null && framesTotal > 0) {
                    progress.onProgress(Math.min(0.98f, framesDone / (float) framesTotal));
                }
                if (progress != null && progress.isCancelled()) {
                    throw new IOException("cancelled");
                }
            }

            AudioDecoder.patchWavHeader(out, written);
            if (progress != null) {
                progress.onProgress(1f);
            }
        }
    }

    /** Reads up to raw.length bytes but never crosses the data chunk. */
    private static int readSome(FileInputStream fin, byte[] raw, long dataLen,
                                long consumed) throws IOException {
        int want = raw.length;
        if (dataLen > 0) {
            long left = dataLen - consumed;
            if (left <= 0) {
                return -1;
            }
            want = (int) Math.min(want, left);
        }
        int off = 0;
        while (off < want) {
            int n = fin.read(raw, off, want - off);
            if (n < 0) {
                break;
            }
            off += n;
        }
        return off == 0 ? -1 : off;
    }

    /** Interleaved → mono 16-bit; handles 8/16/24/32-bit int and 32-bit float. */
    private static short[] downmix(byte[] raw, int frames, int channels,
                                   int bits, int format) {
        short[] mono = new short[frames];
        ByteBuffer le = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        boolean isFloat = format == 3;
        for (int i = 0; i < frames; i++) {
            long sum = 0;
            for (int ch = 0; ch < channels; ch++) {
                int base = (i * channels + ch) * (bits / 8);
                long v;
                switch (bits) {
                    case 8:
                        v = ((raw[base] & 0xFF) - 128) << 8; // unsigned → signed
                        break;
                    case 16:
                        v = le.getShort(base);
                        break;
                    case 24:
                        int x = (raw[base] & 0xFF) | (raw[base + 1] & 0xFF) << 8
                                | (raw[base + 2] & 0xFF) << 16;
                        v = (x << 8) >> 8; // sign-extend
                        v = v >> 8;        // 24 → 16-bit range
                        break;
                    default: // 32
                        if (isFloat) {
                            v = Math.round(le.getFloat(base) * 32767f);
                        } else {
                            v = le.getInt(base) >> 16;
                        }
                        break;
                }
                sum += v;
            }
            long m = sum / channels;
            mono[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, m));
        }
        return mono;
    }

    private static byte[] readFully(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) {
                throw new EOFException("wav truncated");
            }
            off += r;
        }
        return b;
    }
}
