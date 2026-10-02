package com.sedayar.app.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Rate conversion + downmix maths — the base of every decode path. */
public class ResamplerTest {

    private short[] sine(int frames, double freq, int rate) {
        short[] out = new short[frames];
        for (int i = 0; i < frames; i++) {
            out[i] = (short) Math.round(Math.sin(2 * Math.PI * freq * i / rate) * 10000);
        }
        return out;
    }

    @Test
    public void sixteenKiloHertzPassthroughKeepsEverySample() throws Exception {
        short[] in = sine(1600, 440, 16000); // 100 ms
        Resampler r = new Resampler(16000, 1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long n = r.resampleMono(in, in.length, out);
        assertEquals(in.length * 2, n);
        byte[] bytes = out.toByteArray();
        ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        // step==1 → sample-exact copy, shifted by one sample (the seed sample):
        // output[i] must equal input[i-1]
        assertEquals(in[0], bb.getShort(0));
        for (int i = 1; i < in.length; i += 160) { // every 10 ms
            int got = bb.getShort(i * 2);
            assertEquals("sample " + i, in[i - 1], got);
        }
    }

    @Test
    public void eightKiloHertzIsDoubledToSixteen() throws Exception {
        short[] in = sine(800, 440, 8000); // 100 ms at 8 kHz
        Resampler r = new Resampler(8000, 1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long n = r.resampleMono(in, in.length, out);
        assertEquals("100 ms at 16 kHz mono = 3200 bytes", 3200L, n);
    }

    @Test
    public void fortyFourPointOneDownsamplesToSixteen() throws Exception {
        short[] in = sine(4410, 440, 44100); // 100 ms
        Resampler r = new Resampler(44100, 1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long n = r.resampleMono(in, in.length, out);
        // 100 ms at 16 kHz = 1600 samples = 3200 bytes (±2 samples tolerance)
        assertTrue("got " + n, Math.abs(n - 3200) <= 4);
    }

    @Test
    public void stereoByteBufferPathDownmixesToMono() throws Exception {
        // L = constant 1000, R = constant -1000 → mono must be ~0
        ByteBuffer stereo = ByteBuffer.allocate(16 * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 16; i++) {
            stereo.putShort((short) 1000);
            stereo.putShort((short) -1000);
        }
        stereo.position(0);
        Resampler r = new Resampler(16000, 2);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long n = r.resample(stereo, out);
        assertEquals(16 * 2, n);
        byte[] bytes = out.toByteArray();
        ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 16; i++) {
            assertEquals(0, bb.getShort(i * 2));
        }
    }

    @Test
    public void chunksContinueWithoutClicks() throws Exception {
        // feed a 100 ms sine in two chunks (50 ms + 100 ms); step=1 → sample-exact
        short[] in = sine(1600, 220, 16000);
        Resampler r = new Resampler(16000, 1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long first = r.resampleMono(in, 800, out);
        long second = r.resampleMono(in, in.length, out);
        assertEquals(800 * 2, first);
        assertEquals(1600 * 2, second);
    }
}
