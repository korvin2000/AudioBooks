package com.fanok.audiobooks.service;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.AudioProcessor.AudioFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.junit.After;
import org.junit.Test;

public class BalanceAudioProcessorTest {

    private static final short[] SAMPLES = {0, 1, -1, 12345, -12345, Short.MAX_VALUE, Short.MIN_VALUE, 7};

    @After
    public void centre() {
        BalanceAudioProcessor.setBalance(0);
        BalanceAudioProcessor.setNightSound(false);
    }

    @Test
    public void theCentrePlaysTheSamplesAsTheyAre() throws Exception {
        assertArrayEquals(SAMPLES, play(processor(2), SAMPLES));
        short[] mono = play(processor(1), SAMPLES);
        for (int i = 0; i < SAMPLES.length; i++) {
            assertEquals(SAMPLES[i], mono[2 * i]);
            assertEquals(SAMPLES[i], mono[2 * i + 1]);
        }
    }

    @Test
    public void monoPlaysAsStereoAndLeansToASide() throws Exception {
        BalanceAudioProcessor.setBalance(-100);
        short[] played = play(processor(1), SAMPLES);
        assertEquals(2 * SAMPLES.length, played.length);
        for (int i = 0; i < SAMPLES.length; i++) {
            assertEquals(SAMPLES[i], played[2 * i]);
            assertEquals(0, played[2 * i + 1]);
        }
    }

    @Test
    public void aChangeWhilePlayingIsSpreadOverOneBuffer() throws Exception {
        BalanceAudioProcessor processor = processor(2);
        short[] loud = new short[200];
        Arrays.fill(loud, (short) 10_000);
        BalanceAudioProcessor.setBalance(50);
        short[] ramp = play(processor, loud);
        assertTrue(ramp[0] < 10_000 && ramp[0] > 9_900);
        assertEquals(5_000, ramp[ramp.length - 2]);
        short[] after = play(processor, loud);
        for (int i = 0; i < after.length; i += 2) {
            //the left is turned down linearly, the right untouched
            assertEquals(5_000, after[i]);
            assertEquals(10_000, ramp[i + 1]);
            assertEquals(10_000, after[i + 1]);
        }
    }

    @Test
    public void nightLiftsAWhisperOnBothSidesAndHoldsItSteady() throws Exception {
        BalanceAudioProcessor processor = processor(1);
        BalanceAudioProcessor.setNightSound(true);
        short[] whisper = tone(1, 1036); //−30 dBFS
        short[] played = whisper;
        for (int b = 0; b < 240; b++) { //6 s
            played = play(processor, whisper);
        }
        for (int i = 0; i < played.length; i += 2) {
            assertEquals(played[i], played[i + 1]);
            assertEquals(4f * whisper[i / 2], played[i], 1f); //the most it lifts: +12 dB
        }
    }

    @Test
    public void nightKeepsALoudOnsetUnderTheCeilingWithoutAJumpBeforeIt() throws Exception {
        BalanceAudioProcessor processor = processor(2);
        BalanceAudioProcessor.setNightSound(true);
        for (int b = 0; b < 240; b++) {
            play(processor, tone(2, 1036)); //lifted 4×
        }
        short[] onset = tone(2, 1036);
        short[] loud = tone(2, Short.MAX_VALUE);
        System.arraycopy(loud, 1400, onset, 1400, onset.length - 1400); //from frame 700, inside a 5 ms block
        onset[1601] = Short.MIN_VALUE;
        short[] played = play(processor, onset);
        for (int i = 2; i < 1400; i++) { //the whisper is turned down smoothly before the onset, not cut
            assertTrue(Math.abs(played[i] - played[i - 2]) < 600);
        }
        assertTrue(peak(played) <= BalanceAudioProcessor.CEILING + 1);
        for (int b = 0; b < 40; b++) {
            assertTrue(peak(play(processor, loud)) <= BalanceAudioProcessor.CEILING + 1);
        }
    }

    @Test
    public void nightLeavesPausesAndHissAsTheyAre() throws Exception {
        BalanceAudioProcessor processor = processor(2);
        BalanceAudioProcessor.setNightSound(true);
        short[] hiss = new short[2 * FRAMES];
        for (int i = 0; i < hiss.length; i++) {
            hiss[i] = (short) (i * 7919 % 61 - 30); //under −60 dBFS
        }
        for (int b = 0; b < 120; b++) {
            assertArrayEquals(hiss, play(processor, hiss));
        }
    }

    @Test
    public void nightOffGlidesBackToTheSamplesAsTheyAre() throws Exception {
        BalanceAudioProcessor processor = processor(2);
        BalanceAudioProcessor.setNightSound(true);
        short[] whisper = tone(2, 1036);
        for (int b = 0; b < 240; b++) {
            play(processor, whisper);
        }
        BalanceAudioProcessor.setNightSound(false);
        int last = peak(play(processor, whisper));
        assertTrue(last > 3_900); //no jump: it starts at the 4× it had
        for (int b = 0; b < 40; b++) { //1 s
            int peak = peak(play(processor, whisper));
            assertTrue(peak <= last);
            assertTrue(b > 0 || peak > 2 * 1036); //and goes down over a few buffers, not at once
            last = peak;
        }
        assertArrayEquals(whisper, play(processor, whisper));
    }

    @Test
    public void nightOffComesBackExactlyWithFullScaleSamplesToo() throws Exception {
        BalanceAudioProcessor processor = processor(2);
        BalanceAudioProcessor.setNightSound(true);
        short[] whisper = tone(2, 1036);
        for (int b = 0; b < 240; b++) {
            play(processor, whisper);
        }
        BalanceAudioProcessor.setNightSound(false);
        whisper[1001] = Short.MIN_VALUE; //32768 over full scale 32767 must not hold the gain a hair under 1
        for (int b = 0; b < 40; b++) {
            play(processor, whisper);
        }
        assertArrayEquals(whisper, play(processor, whisper));
    }

    /** Frames per buffer, an MP3 frame's. */
    private static final int FRAMES = 1152;

    /** A buffer of a 441 Hz sine (100 frames a period at 44.1 kHz), the same on every channel. */
    private static short[] tone(int channels, int amplitude) {
        short[] samples = new short[channels * FRAMES];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (short) Math.round(amplitude * Math.sin(2 * Math.PI * (i / channels) / 100));
        }
        return samples;
    }

    private static int peak(short[] samples) {
        int peak = 0;
        for (short sample : samples) {
            peak = Math.max(peak, Math.abs(sample));
        }
        return peak;
    }

    private static BalanceAudioProcessor processor(int channels) throws Exception {
        BalanceAudioProcessor processor = new BalanceAudioProcessor();
        assertEquals(2, processor.configure(new AudioFormat(44_100, channels, C.ENCODING_PCM_16BIT)).channelCount);
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT);
        return processor;
    }

    private static short[] play(BalanceAudioProcessor processor, short[] samples) {
        ByteBuffer input = ByteBuffer.allocateDirect(2 * samples.length).order(ByteOrder.nativeOrder());
        for (short sample : samples) {
            input.putShort(sample);
        }
        input.flip();
        processor.queueInput(input);
        assertFalse(input.hasRemaining());
        ByteBuffer output = processor.getOutput();
        short[] played = new short[output.remaining() / 2];
        for (int i = 0; i < played.length; i++) {
            played[i] = output.getShort();
        }
        return played;
    }
}
