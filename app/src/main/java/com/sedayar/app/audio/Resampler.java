package com.sedayar.app.audio;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Streaming downmix (stereo → mono) + linear-interpolated resample to 16 kHz
 * 16-bit little-endian PCM. Holds only a few hundred ms of audio, so even
 * hour-long lectures never stress memory.
 *
 * Pure Java — covered by unit tests in src/test and reused by the desktop
 * end-to-end harness, so the exact code that ships is the code that gets
 * tested.
 */
public final class Resampler {

    public static final int OUT_RATE = 16000;

    private final double step;
    private final int channels;
    private double pos = 0.0;      // position within the current chunk, in samples
    private short prev = 0;        // last mono sample of the previous chunk
    private boolean started = false;

    public Resampler(int inRate, int channels) {
        this.step = (double) Math.max(1, inRate) / OUT_RATE;
        this.channels = Math.max(1, channels);
    }

    /** Convenience path used by MediaCodec output (little-endian interleaved). */
    public long resample(ByteBuffer pcmLittleEndian, OutputStream out) throws IOException {
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

    /** Already-mono path (WAV reader, live stream, tests). */
    public long resampleMono(short[] mono, int frames, OutputStream out) throws IOException {
        return emit(out, mono, frames);
    }

    private long emit(OutputStream out, short[] data, int frames) throws IOException {
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
