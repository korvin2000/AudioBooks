package com.fanok.audiobooks.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;

public class SpectrumMeterTest {

    private static final int RATE = 44100;

    /** {@code frames} stereo frames of a sine of {@code hz} at {@code db} dBFS, as the sink hands them. */
    private static ByteBuffer sine(double hz, double db, int frames) {
        ByteBuffer pcm = ByteBuffer.allocate(frames * 4).order(ByteOrder.nativeOrder());
        double amplitude = 32767 * Math.pow(10, db / 20);
        for (int i = 0; i < frames; i++) {
            short s = (short) Math.round(amplitude * Math.sin(2 * Math.PI * hz * i / RATE));
            pcm.putShort(s).putShort(s);
        }
        pcm.flip();
        return pcm;
    }

    private static long levelsOf(double hz, double db) {
        ByteBuffer pcm = sine(hz, db, 2 * SpectrumMeter.N);
        SpectrumMeter.feed(pcm, 0, 2 * SpectrumMeter.N, 4, RATE);
        assertEquals("the buffer is read in place", 0, pcm.position());
        return SpectrumMeter.analyze();
    }

    @Test
    public void aToneLightsItsBandOnly() {
        long levels = levelsOf(1000, -20); // 1 kHz is in the 7th band, 800-1175 Hz
        assertTrue(SpectrumMeter.band(levels, 6) >= 6);
        assertEquals(0, SpectrumMeter.band(levels, 0));
        assertEquals(0, SpectrumMeter.band(levels, SpectrumMeter.BANDS - 1));
    }

    @Test
    public void quieterLightsLess() {
        int loud = SpectrumMeter.band(levelsOf(1000, -20), 6);
        int quiet = SpectrumMeter.band(levelsOf(1000, -40), 6);
        assertTrue(quiet > 0 && quiet < loud);
    }

    @Test
    public void silenceIsDark() {
        assertEquals(0, levelsOf(1000, -200));
    }
}
