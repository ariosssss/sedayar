package com.sedayar.app.audio;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;

/**
 * Decodes any Android-supported audio file (mp3, m4a, aac, ogg, wav, ...)
 * into a 16 kHz mono PCM WAV, the format Vosk expects.
 *
 * Two paths:
 *  1. plain PCM WAV files go through {@link WavFileReader} — pure Java, no
 *     codec, immune to the MediaCodec quirks that caused "decode failed";
 *  2. everything else (mp3/m4a/aac/ogg…) goes through MediaCodec, streamed
 *     through the shared {@link Resampler} so memory stays flat for hours of
 *     audio.
 */
public final class AudioDecoder {

    public interface Progress {
        void onProgress(float ratio);

        /** Return true to cancel. */
        boolean isCancelled();
    }

    private static final int TIMEOUT_US = 10_000;

    private AudioDecoder() {
    }

    /** Content-URI entry point (MediaCodec path). */
    public static File decodeToWav(Context context, Uri input, File outWav,
                                   Progress progress) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        FileOutputStream out = new FileOutputStream(outWav);
        try {
            extractor.setDataSource(context, input, null);
            MediaFormat format = pickAudioTrack(extractor);
            codec = runCodec(extractor, format, outWav, out, progress);
            return outWav;
        } finally {
            release(codec, extractor, out);
        }
    }

    /** Local-file entry point: pure-Java WAV fast path first, MediaCodec after. */
    public static File decodeToWav(File inputFile, File outWav,
                                   Progress progress) throws Exception {
        if (WavFileReader.isWav(inputFile)) {
            try {
                if (WavFileReader.toWav16kMono(inputFile, outWav, progress)) {
                    return outWav;
                }
            } catch (IOException e) {
                if (e.getMessage() != null && e.getMessage().contains("cancelled")) {
                    throw e;
                }
                // malformed WAV — fall through to MediaCodec and see what it says
            }
        }

        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        FileOutputStream out = new FileOutputStream(outWav);
        try {
            extractor.setDataSource(inputFile.getAbsolutePath());
            MediaFormat format = pickAudioTrack(extractor);
            codec = runCodec(extractor, format, outWav, out, progress);
            return outWav;
        } finally {
            release(codec, extractor, out);
        }
    }

    // ------------------------------------------------------------ shared impl

    private static MediaFormat pickAudioTrack(MediaExtractor extractor) throws IOException {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat f = extractor.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                extractor.selectTrack(i);
                return f;
            }
        }
        throw new IOException("no audio track");
    }

    private static MediaCodec runCodec(MediaExtractor extractor, MediaFormat format,
                                       File outWav, FileOutputStream out, Progress progress)
            throws Exception {
        int inRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        long durationUs = format.containsKey(MediaFormat.KEY_DURATION)
                ? format.getLong(MediaFormat.KEY_DURATION) : 0L;

        MediaCodec codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
        codec.configure(format, null, null, 0);
        codec.start();

        writeWavHeader(out);

        Resampler resampler = new Resampler(inRate, channels);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long bytesWritten = 0;
        boolean inputEos = false;

        while (true) {
            if (progress != null && progress.isCancelled()) {
                throw new IOException("cancelled");
            }

            if (!inputEos) {
                int inIdx = codec.dequeueInputBuffer(TIMEOUT_US);
                if (inIdx >= 0) {
                    ByteBuffer buf = codec.getInputBuffer(inIdx);
                    int size = buf == null ? 0 : extractor.readSampleData(buf, 0);
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputEos = true;
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size,
                                extractor.getSampleTime(), 0);
                        extractor.advance();
                    }
                }
            }

            int outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US);
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat of = codec.getOutputFormat();
                resampler = new Resampler(of.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                        of.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
            } else if (outIdx >= 0) {
                ByteBuffer pcm = codec.getOutputBuffer(outIdx);
                if (pcm != null && info.size > 0) {
                    pcm.position(info.offset);
                    pcm.limit(info.offset + info.size);
                    bytesWritten += resampler.resample(pcm, out);
                }
                codec.releaseOutputBuffer(outIdx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
                if (durationUs > 0 && progress != null) {
                    progress.onProgress(
                            Math.min(0.98f, info.presentationTimeUs / (float) durationUs));
                }
            }
        }

        patchWavHeader(outWav, bytesWritten);
        if (progress != null) {
            progress.onProgress(1f);
        }
        return codec;
    }

    private static void release(MediaCodec codec, MediaExtractor extractor,
                                FileOutputStream out) {
        try {
            if (codec != null) {
                codec.stop();
                codec.release();
            }
        } catch (Exception ignored) {
        }
        try {
            extractor.release();
        } catch (Exception ignored) {
        }
        try {
            out.close();
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------- WAV file

    static void writeWavHeader(FileOutputStream out) throws IOException {
        byte[] header = new byte[44];
        header[0] = 'R';
        header[1] = 'I';
        header[2] = 'F';
        header[3] = 'F';
        header[8] = 'W';
        header[9] = 'A';
        header[10] = 'V';
        header[11] = 'E';
        header[12] = 'f';
        header[13] = 'm';
        header[14] = 't';
        header[15] = ' ';
        header[16] = 16;                        // fmt chunk size
        header[20] = 1;                         // PCM
        header[22] = 1;                         // mono
        putInt(header, 24, Resampler.OUT_RATE);
        putInt(header, 28, Resampler.OUT_RATE * 2); // byte rate
        header[32] = 2;                         // block align
        header[34] = 16;                        // bits per sample
        header[36] = 'd';
        header[37] = 'a';
        header[38] = 't';
        header[39] = 'a';
        out.write(header);
    }

    static void patchWavHeader(File file, long dataBytes) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(file, "rw");
        try {
            raf.seek(4);
            putIntAt(raf, 36 + dataBytes);      // riff chunk size
            raf.seek(40);
            putIntAt(raf, dataBytes);           // data chunk size
        } finally {
            raf.close();
        }
    }

    private static void putIntAt(RandomAccessFile raf, long v) throws IOException {
        raf.write((int) (v & 0xFF));
        raf.write((int) ((v >> 8) & 0xFF));
        raf.write((int) ((v >> 16) & 0xFF));
        raf.write((int) ((v >> 24) & 0xFF));
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
        b[off + 2] = (byte) ((v >> 16) & 0xFF);
        b[off + 3] = (byte) ((v >> 24) & 0xFF);
    }
}
