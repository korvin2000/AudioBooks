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
import java.util.Random;
import org.junit.After;
import org.junit.Test;

/** Level 3 (the default): pauses up to 320 ms stay, a longer one keeps 320 ms + 12 % of the rest, at most 650 ms. */
public class SkipSilenceProcessorTest {

    private static final int RATE = 44_100;
    private static final int SPEECH = 8_000;

    @After
    public void defaultLevel() {
        SkipSilenceProcessor.setLevel(SkipSilenceProcessor.DEFAULT_LEVEL);
    }

    @Test
    public void shortPausesPlayAsTheyAre() throws Exception {
        short[] in = join(tone(1000, SPEECH), silence(300), tone(500, SPEECH), silence(100), tone(500, SPEECH));
        SkipSilenceProcessor processor = processor();
        assertArrayEquals(in, play(processor, in, 1152));
        assertEquals(0, processor.skippedFrames());
    }

    @Test
    public void aLongPauseKeepsItsStartAndEndAndIsCounted() throws Exception {
        short[] speech = tone(1000, SPEECH);
        SkipSilenceProcessor processor = processor();
        short[] out = play(processor, join(speech, silence(2000), speech), 1152);
        int kept = kept(2000);
        assertArrayEquals(join(speech, new short[2 * kept], speech), out);
        assertEquals(frames(2000) - kept, processor.skippedFrames());
    }

    @Test
    public void howTheBuffersAreCutChangesNothing() throws Exception {
        Random random = new Random(1);
        short[] in = join(tone(1013, SPEECH), noise(777, 200, random), tone(333, SPEECH), silence(1501),
                tone(250, SPEECH), noise(2333, 200, random), tone(10, SPEECH));
        short[] expected = play(processor(), in, 1152);
        assertTrue(expected.length < in.length);
        for (int frames : new int[]{1, 441, 1000, 4096}) {
            SkipSilenceProcessor processor = processor();
            short[] out = play(processor, in, frames);
            assertArrayEquals("buffers of " + frames, expected, out);
            assertEquals(in.length, out.length + 2 * processor.skippedFrames());
        }
    }

    @Test
    public void aQuietSoundInAPauseIsKept() throws Exception {
        short[] speech = tone(1000, SPEECH);
        short[] whisper = tone(160, SPEECH / 16); //24 dB under the speech
        short[] pause = new short[2 * kept(1000)];
        short[] out = play(processor(), join(speech, silence(1000), whisper, silence(1000), speech), 1152);
        assertArrayEquals(join(speech, pause, whisper, pause, speech), out);
    }

    @Test
    public void hissIsCutButABedNearTheSpeechIsNot() throws Exception {
        Random random = new Random(2);
        short[] speech = tone(1000, SPEECH);
        //the floor is learnt over the first 2.5 s: the first pause is left, the second shortened
        short[] hiss = join(speech, noise(1000, 320, random), speech, noise(2000, 320, random), speech); //−30 dB
        SkipSilenceProcessor processor = processor();
        assertEquals(hiss.length - 2 * (frames(2000) - kept(2000)), play(processor, hiss, 1152).length);
        short[] bed = join(speech, noise(1000, 1000, random), speech, noise(2000, 1000, random), speech); //−20 dB
        assertArrayEquals(bed, play(processor(), bed, 1152));
    }

    @Test
    public void quietSpeechAfterALoudPassageIsSoonHeardAgain() throws Exception {
        //speech 40 dB under a loud start, words of 200 ms between gaps of 100 ms: judged silent until the speech
        //level has come down to it, about 2.5 s, then played as it is
        short[] words = new short[0];
        for (int i = 0; i < 30; i++) {
            words = join(words, tone(200, 100), silence(100));
        }
        short[] in = join(tone(2000, 10_000), words);
        SkipSilenceProcessor processor = processor();
        short[] out = play(processor, in, 1152);
        assertTrue(processor.skippedFrames() < frames(2500));
        int tail = 2 * frames(6000);
        assertArrayEquals(Arrays.copyOfRange(in, in.length - tail, in.length),
                Arrays.copyOfRange(out, out.length - tail, out.length));
    }

    @Test
    public void aSeekDropsTheHeldPauseAndItsCount() throws Exception {
        SkipSilenceProcessor processor = processor();
        feed(processor, join(tone(1000, SPEECH), silence(3000)));
        assertEquals(frames(3000) - kept(3000), processor.skippedFrames()); //counted as it is cut
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT);
        short[] speech = tone(500, SPEECH);
        assertArrayEquals(speech, play(processor, speech, 1152));
        assertEquals(0, processor.skippedFrames());
    }

    @Test
    public void offTakesNoPartAndOnNeedsNoNewFormat() throws Exception {
        SkipSilenceProcessor processor = new SkipSilenceProcessor();
        processor.configure(new AudioFormat(RATE, 2, C.ENCODING_PCM_16BIT));
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT);
        assertFalse(processor.isActive());
        processor.setEnabled(true);
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT);
        assertTrue(processor.isActive());
        assertEquals(AudioFormat.NOT_SET, processor.configure(new AudioFormat(RATE, 2, C.ENCODING_PCM_FLOAT)));
    }

    @Test
    public void aHigherLevelKeepsLess() throws Exception {
        short[] in = join(tone(1000, SPEECH), silence(2000), tone(1000, SPEECH));
        int last = Integer.MAX_VALUE;
        for (int level = SkipSilenceProcessor.MIN_LEVEL; level <= SkipSilenceProcessor.MAX_LEVEL; level++) {
            SkipSilenceProcessor.setLevel(level);
            int length = play(processor(), in, 1152).length;
            assertTrue("level " + level, length < last);
            last = length;
        }
        //even the highest level keeps more than its 220 ms of a pause
        assertTrue(last > 2 * (frames(2000) + frames(220)));
    }

    private static SkipSilenceProcessor processor() throws Exception {
        SkipSilenceProcessor processor = new SkipSilenceProcessor();
        processor.setEnabled(true);
        processor.configure(new AudioFormat(RATE, 2, C.ENCODING_PCM_16BIT));
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT);
        return processor;
    }

    /** Plays stereo {@code samples} in buffers of {@code frames} frames, then the end of stream, as the sink does. */
    private static short[] play(SkipSilenceProcessor processor, short[] samples, int frames) {
        short[] out = feed(processor, samples, frames);
        int n = out.length;
        out = Arrays.copyOf(out, samples.length + RATE);
        //the pipeline repeats the end of stream on every pass once the processor before has ended
        for (int pass = 0; ; pass++) {
            processor.queueEndOfStream();
            if (processor.isEnded()) {
                return Arrays.copyOf(out, n);
            }
            assertTrue("ends", pass < 3);
            processor.queueInput(AudioProcessor.EMPTY_BUFFER);
            n = take(processor.getOutput(), out, n);
        }
    }

    private static short[] feed(SkipSilenceProcessor processor, short[] samples) {
        return feed(processor, samples, 1152);
    }

    private static short[] feed(SkipSilenceProcessor processor, short[] samples, int frames) {
        short[] out = new short[samples.length + 2 * RATE];
        int n = 0;
        for (int i = 0; i < samples.length; i += 2 * frames) {
            int count = Math.min(2 * frames, samples.length - i);
            ByteBuffer input = ByteBuffer.allocateDirect(2 * count).order(ByteOrder.nativeOrder());
            input.asShortBuffer().put(samples, i, count);
            processor.queueInput(input);
            assertFalse(input.hasRemaining());
            n = take(processor.getOutput(), out, n);
        }
        return Arrays.copyOf(out, n);
    }

    private static int take(ByteBuffer output, short[] out, int n) {
        while (output.hasRemaining()) {
            out[n++] = output.getShort();
        }
        return n;
    }

    /** The frames a pause of {@code ms} keeps at level 3. */
    private static int kept(int ms) {
        int k = frames(320);
        return Math.min(frames(650), k + (int) (.12f * (frames(ms) - k)));
    }

    private static int frames(int ms) {
        return RATE * ms / 1000;
    }

    /** {@code ms} of a 200 Hz stereo tone. */
    private static short[] tone(int ms, int amplitude) {
        short[] samples = new short[2 * frames(ms)];
        for (int i = 0; i < samples.length; i += 2) {
            samples[i] = samples[i + 1] = (short) Math.round(amplitude * Math.sin(2 * Math.PI * 200 * (i / 2) / RATE));
        }
        return samples;
    }

    /** {@code ms} of stereo white noise within ±{@code amplitude}. */
    private static short[] noise(int ms, int amplitude, Random random) {
        short[] samples = new short[2 * frames(ms)];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (short) (random.nextInt(2 * amplitude + 1) - amplitude);
        }
        return samples;
    }

    private static short[] silence(int ms) {
        return new short[2 * frames(ms)];
    }

    private static short[] join(short[]... parts) {
        int length = 0;
        for (short[] part : parts) {
            length += part.length;
        }
        short[] all = new short[length];
        int at = 0;
        for (short[] part : parts) {
            System.arraycopy(part, 0, all, at, part.length);
            at += part.length;
        }
        return all;
    }
}
