package com.sedayar.app.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The pure-Java WAV path: converts any PCM WAV to 16 kHz mono — exactly the
 * files the app's own recorder (and plenty of recorders) produce.
 */
public class WavFileReaderTest {

    private static final class WavBuilder {
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
        int sampleRate = 44100;
        int channels = 2;
        int bits = 16;
        int format = 1; // PCM

        byte[] build() {
            byte[] raw = data.toByteArray();
            ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            h.put("RIFF".getBytes());
            h.putInt(36 + raw.length);
            h.put("WAVE".getBytes());
            h.put("fmt ".getBytes());
            h.putInt(16);
            h.putShort((short) format);
            h.putShort((short) channels);
            h.putInt(sampleRate);
            h.putInt(sampleRate * channels * bits / 8);
            h.putShort((short) (channels * bits / 8));
            h.putShort((short) bits);
            h.put("data".getBytes());
            h.putInt(raw.length);
            ByteBuffer all = ByteBuffer.allocate(44 + raw.length);
            all.put(h.array()).put(raw);
            return all.array();
        }
    }

    private void addFrame(WavBuilder b, int ch, int sampleIdx) {
        ByteBuffer le = ByteBuffer.allocate(b.channels * b.bits / 8)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (int c = 0; c < b.channels; c++) {
            int v = (int) Math.round(Math.sin(2 * Math.PI * 440 * sampleIdx / b.sampleRate) * 9000);
            if (b.bits == 16) {
                le.putShort((short) v);
            } else if (b.bits == 8) {
                le.put((byte) ((v >> 8) + 128));
            } else if (b.bits == 24) {
                int x = v << 8;
                le.put((byte) (x & 0xFF));
                le.put((byte) ((x >> 8) & 0xFF));
                le.put((byte) ((x >> 16) & 0xFF));
            } else if (b.bits == 32) {
                if (b.format == 3) {
                    le.putFloat(v / 32767f);
                } else {
                    le.putInt(v << 16);
                }
            }
        }
        b.data.write(le.array(), 0, le.array().length);
    }

    private File writeWav(byte[] bytes) throws Exception {
        File f = File.createTempFile("test", ".wav");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(bytes);
        }
        f.deleteOnExit();
        return f;
    }

    private int sampleRateOf(File wav) throws Exception {
        byte[] h = new byte[44];
        try (java.io.FileInputStream in = new java.io.FileInputStream(wav)) {
            in.read(h);
        }
        return ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN).getInt(24);
    }

    private long dataBytes(File wav) {
        return wav.length() - 44;
    }

    @Test
    public void stereo44kSineBecomesMono16k() throws Exception {
        WavBuilder b = new WavBuilder();
        b.sampleRate = 44100;
        b.channels = 2;
        for (int i = 0; i < 4410; i++) { // exactly 100 ms
            addFrame(b, 2, i);
        }
        File in = writeWav(b.build());
        assertTrue(WavFileReader.isWav(in));

        File out = File.createTempFile("out", ".wav");
        out.deleteOnExit();
        boolean ok = WavFileReader.toWav16kMono(in, out, null);
        assertTrue(ok);
        assertEquals(16000, sampleRateOf(out));
        // 100 ms of 16 kHz mono = 1600 samples = 3200 bytes (±4)
        assertTrue("data bytes: " + dataBytes(out),
                Math.abs(dataBytes(out) - 3200) <= 4);
    }

    @Test
    public void mono16kPassthroughIsSampleExact() throws Exception {
        WavBuilder b = new WavBuilder();
        b.sampleRate = 16000;
        b.channels = 1;
        for (int i = 0; i < 1600; i++) {
            addFrame(b, 1, i);
        }
        File in = writeWav(b.build());
        File out = File.createTempFile("out", ".wav");
        out.deleteOnExit();
        assertTrue(WavFileReader.toWav16kMono(in, out, null));
        assertEquals(1600 * 2, dataBytes(out));
    }

    @Test
    public void twentyFourBitMonoConverts() throws Exception {
        WavBuilder b = new WavBuilder();
        b.sampleRate = 44100;
        b.channels = 1;
        b.bits = 24;
        for (int i = 0; i < 4410; i++) {
            addFrame(b, 1, i);
        }
        File in = writeWav(b.build());
        File out = File.createTempFile("out", ".wav");
        out.deleteOnExit();
        assertTrue(WavFileReader.toWav16kMono(in, out, null));
        assertTrue(Math.abs(dataBytes(out) - 3200) <= 4);
    }

    @Test
    public void float32StereoConverts() throws Exception {
        WavBuilder b = new WavBuilder();
        b.sampleRate = 48000;
        b.channels = 2;
        b.bits = 32;
        b.format = 3; // IEEE float
        for (int i = 0; i < 4800; i++) { // 100 ms
            addFrame(b, 2, i);
        }
        File in = writeWav(b.build());
        File out = File.createTempFile("out", ".wav");
        out.deleteOnExit();
        assertTrue(WavFileReader.toWav16kMono(in, out, null));
        assertTrue(Math.abs(dataBytes(out) - 3200) <= 4);
    }

    @Test
    public void adpcmFallsBackToMediaCodecPath() throws Exception {
        WavBuilder b = new WavBuilder();
        b.format = 2; // ADPCM — not supported on purpose
        b.sampleRate = 44100;
        b.channels = 2;
        b.bits = 4;
        b.data.write(new byte[1000]);
        File in = writeWav(b.build());
        File out = File.createTempFile("out", ".wav");
        out.deleteOnExit();
        assertFalse(WavFileReader.toWav16kMono(in, out, null));
    }

    @Test
    public void nonWavFileIsRejectedBySniff() throws Exception {
        File in = File.createTempFile("not", ".wav");
        try (FileOutputStream o = new FileOutputStream(in)) {
            o.write(new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11});
        }
        in.deleteOnExit();
        assertFalse(WavFileReader.isWav(in));
    }
}
