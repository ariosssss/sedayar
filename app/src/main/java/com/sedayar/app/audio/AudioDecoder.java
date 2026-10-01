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
import java.nio.ByteOrder;

/**
 * Decodes any Android-supported audio file (mp3, m4a, aac, ogg, wav, ...)
 * into a 16 kHz mono PCM WAV, the format Vosk expects.
 *
 * The resampler is a small streaming linear-interpolator, so even long
 * lectures never hold more than a few hundred ms of audio in memory.
 */
public final class AudioDecoder {

    public interface Progress {
        void onProgress(float ratio);

        /** Return true to cancel. */
        boolean isCancelled();
    }

    private static final int OUT_RATE = 16000;
    private static final int TIMEOUT_US = 10_000;

    private AudioDecoder() {
    }

    public static File decodeToWav(Context context, Uri input, File outWav,
                                   Progress progress) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        FileOutputStream out = new FileOutputStream(outWav);
        try {
            extractor.setDataSource(context, input, null);
            int trackIndex = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    trackIndex = i;
                    format = f;
                    break;
                }
            }
            if (trackIndex < 0) {
                throw new IOException("no audio track");
            }
            extractor.selectTrack(trackIndex);

            int inRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            long durationUs = format.containsKey(MediaFormat.KEY_DURATION)
                    ? format.getLong(MediaFormat.KEY_DURATION) : 0L;

            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
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
            return outWav;
        } finally {
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
    }

    // ------------------------------------------------------------- resampler

    /** Streaming downmix (stereo -> mono) + linear resample to 16 kHz. */
    private static final class Resampler {
        private final double step;
        private final int channels;
        private double pos = 0.0;      // position within the current chunk, in samples
        private short prev = 0;        // last mono sample of the previous chunk
        private boolean started = false;

        Resampler(int inRate, int channels) {
            this.step = (double) Math.max(1, inRate) / OUT_RATE;
            this.channels = Math.max(1, channels);
        }

        long resample(ByteBuffer pcmLittleEndian, FileOutputStream out) throws IOException {
            ByteBuffer le = pcmLittleEndian.order(ByteOrder.LITTLE_ENDIAN);
            int frames = le.remaining() / (2 * channels);
            if (frames <= 0) {
                return 0L;
            }
            short[] mono = new short[frames];
            for (int i = 0; i < frames; i++) {
                int sum = 0;
                for (int ch = 0; ch < channels; ch++) {
                    sum += le.getShort((i * channels + ch) * 2);
                }
                mono[i] = (short) (sum / channels);
            }
            return emit(out, mono, frames);
        }

        private long emit(FileOutputStream out, short[] data, int frames)
                throws IOException {
            long written = 0;
            byte[] buf = new byte[1024];
            int n = 0;
            if (!started && frames > 0) {
                prev = data[0];
                started = true;
            }
            while (pos < frames) {
                int idx = (int) pos;
                double frac = pos - idx;
                double a = idx == 0 ? prev : data[idx - 1];
                double b = data[idx];
                int v = Math.round((float) (a + (b - a) * frac));
                buf[n++] = (byte) (v & 0xFF);
                buf[n++] = (byte) ((v >> 8) & 0xFF);
                pos += step;
                if (n == buf.length) {
                    out.write(buf);
                    written += n;
                    n = 0;
                }
            }
            if (n > 0) {
                out.write(buf, 0, n);
                written += n;
            }
            if (frames > 0) {
                prev = data[frames - 1];
                pos -= frames;
            }
            return written;
        }
    }

    // ------------------------------------------------------------- WAV file

    private static void writeWavHeader(FileOutputStream out) throws IOException {
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
        putInt(header, 24, OUT_RATE);
        putInt(header, 28, OUT_RATE * 2);       // byte rate
        header[32] = 2;                         // block align
        header[34] = 16;                        // bits per sample
        header[36] = 'd';
        header[37] = 'a';
        header[38] = 't';
        header[39] = 'a';
        out.write(header);
    }

    private static void patchWavHeader(File file, long dataBytes) throws IOException {
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
