package com.fanok.audiobooks.service;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Skip silence: long pauses of speech are shortened, never removed, played by the audio sink ahead of the balance and
 * the speed ({@link EndAwareRenderersFactory}), so a pause is judged by the recording, not by the night sound's gain,
 * and its length in media time. On and off through {@code ExoPlayer#setSkipSilenceEnabled} (nothing runs while off);
 * the level ({@link #setLevel}) is read for every buffer. The frames left out are counted for the player's position.
 * <p>
 * The PCM is judged in 20 ms blocks, gathered across buffers so the result does not depend on how the decoder cuts
 * them: a block is silent when its mean distance from the DC level is under a threshold that follows the recording —
 * {@code LO} dB under the speech level for a clean pause, {@code M} times the noise floor for one that hisses, never
 * above {@code HI} dB under the speech (a bed of music or noise closer to the speech is never cut). Digital silence is
 * always silent. A run of silent blocks — a pause — up to {@code K} ms plays as it is; a longer one keeps
 * {@code K + R·(length − K)}, at most {@code CAP} ms: its start as it was, and its last {@code T} ms, the sound just
 * before the speech comes back, joined by one crossfade. Real room tone, no gaps, short pauses untouched, the longer
 * pause still the longer one. Per sample that is a few integer operations, plus a copy of the pauses; nothing is
 * allocated while it plays, nothing is held while it is off.
 */
@OptIn(markerClass = UnstableApi.class)
public final class SkipSilenceProcessor extends BaseAudioProcessor {

    /** The settings: skip silence on (default off) and its level, {@link #MIN_LEVEL} cutting least. */
    public static final String PREF = "skip_silence", PREF_LEVEL = "skip_silence_level";
    public static final int MIN_LEVEL = 1, MAX_LEVEL = 5, DEFAULT_LEVEL = 3;

    /** Per level, 1 to 5: the pause kept as it is, the sound before speech, the share kept of the rest, the cap (ms). */
    private static final int[] K = {450, 380, 320, 270, 220}, T = {180, 150, 130, 110, 90},
            CAP = {1200, 900, 650, 450, 320};
    private static final float[] R = {.30f, .20f, .12f, .06f, .02f};
    /** Per level: threshold ratios to the speech level (40…28 dB and 31…19 dB under it) and to the noise floor. */
    private static final float[] LO = {100, 71, 50, 35, 25}, HI = {35, 25, 18, 12.6f, 8.9f}, M = {1.6f, 2, 2.5f, 3.2f, 4};
    /** At any speed a pause keeps at least this much as heard, so fast playback never joins two words. */
    private static final float HEARD_MS = 150;
    private static final int BLOCK_MS = 20, XFADE_MS = 20, HEAD_MS = 1200, RING_MS = 180;
    /** The noise floor is the least level of the last 2.5–5 s: two windows of this many blocks. */
    private static final int FLOOR_BLOCKS = 128, IDLE_BLOCKS = 250;

    private static volatile int level = DEFAULT_LEVEL;

    /** Playback thread from here on. */
    private boolean enabled;
    private float speed = 1f;
    /** Frames left out of the pauses ended since the last flush; the sink adds them to the position. */
    private long skipped;
    /** Set by the end of stream: the next {@link #queueInput} plays what is held, once the last output was taken. */
    private boolean ending;

    private int rate, channels, frameSize, xfade;
    /** The block being gathered ({@link #fill} bytes), the start of the pause, and its last frames, round. */
    private ByteBuffer block, head, ring;
    private int fill, headFrames, ringFrames;
    /** The pause so far, the frames of its start already played, and its settings in frames, fixed when it starts. */
    private long quiet, sent;
    private int k, t, cap;
    private float r;

    /** The speech level, the noise floor's two windows, the DC level of each channel. */
    private float speech, floorPrev, floorNow;
    /** Blocks in the window of the floor, and since the last one above the floor. */
    private int floorBlocks, idle;
    private float[] dc = new float[0];

    /** Sets the level, clamped to {@link #MIN_LEVEL}…{@link #MAX_LEVEL}; any thread. */
    public static void setLevel(int value) {
        level = Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, value));
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    void setSpeed(float speed) {
        this.speed = speed;
    }

    /** Counted as the pause is cut, not when it ends, so the position moves on through a long silence. */
    long skippedFrames() {
        return skipped + quiet - keep(quiet);
    }

    @NonNull
    @Override
    protected AudioFormat onConfigure(@NonNull AudioFormat inputAudioFormat) {
        //the format is taken even while off, so that turning it on needs no new configuration
        return inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ? inputAudioFormat : AudioFormat.NOT_SET;
    }

    @Override
    public boolean isActive() {
        return enabled && super.isActive();
    }

    @Override
    public boolean isEnded() {
        return super.isEnded() && !ending;
    }

    @Override
    public void queueInput(@NonNull ByteBuffer input) {
        if (!input.hasRemaining() && !ending) {
            return;
        }
        //all of the input, the block held and what of the pause is still to be played
        ByteBuffer out = replaceOutputBuffer(input.remaining() + fill + (int) (keep(quiet) - sent) * frameSize);
        int p = level - MIN_LEVEL;
        while (input.hasRemaining()) {
            int n = Math.min(input.remaining(), block.capacity() - fill);
            input.get(block.array(), fill, n);
            fill += n;
            if (fill == block.capacity()) {
                play(out, block.capacity() / frameSize, p);
            }
        }
        if (ending) {
            if (fill > 0) {
                play(out, fill / frameSize, p);
            }
            finish(out);
            ending = false;
        }
        out.flip();
    }

    @Override
    protected void onQueueEndOfStream() {
        //not played here: the output handed over last may still be read. Called again on every pass of the pipeline
        //once the processor before has ended: only what is still held keeps it from ending.
        ending = fill > 0 || quiet > 0;
    }

    /** Plays the gathered block of {@code frames} frames at level index {@code p}. */
    private void play(ByteBuffer out, int frames, int p) {
        fill = 0;
        float a = measure(frames);
        float floor = Math.min(floorPrev, floorNow);
        boolean silent = a < Math.min(Math.max(floor * M[p], speech / LO[p]), speech / HI[p]);
        if (!silent || a > floor * M[p]) {
            //~80th percentile of the sound, +13 dB/s, −3.5 dB/s: a slam or a jingle is soon forgotten. A block clearly
            //above the noise floor counts even when a loud passage just before has made it "silent".
            speech = a > speech ? Math.max(speech * 1.03f, a / 8) : speech * .992f;
            idle = 0;
        } else if (++idle == IDLE_BLOCKS) { //nothing but the floor for 5 s: the speech level is learnt again
            speech = 0;
        }
        floorNow = Math.min(floorNow, Math.max(a, 0));
        if (++floorBlocks == FLOOR_BLOCKS) {
            floorPrev = floorNow;
            floorNow = Float.MAX_VALUE;
            floorBlocks = 0;
        }
        if (!silent) {
            finish(out);
            out.put(block.array(), 0, frames * frameSize);
            return;
        }
        if (quiet == 0) { //the pause's settings, at least HEARD_MS at this speed
            k = Math.min(headFrames, Math.max(ms(K[p]), (int) (HEARD_MS * speed * rate / 1000)));
            t = ms(T[p]);
            cap = Math.max(ms(CAP[p]), k);
            r = R[p];
        }
        if (quiet < headFrames) {
            int n = (int) Math.min(frames, headFrames - quiet);
            System.arraycopy(block.array(), 0, head.array(), (int) quiet * frameSize, n * frameSize);
        }
        int at = (int) (quiet % ringFrames);
        int first = Math.min(frames, ringFrames - at);
        System.arraycopy(block.array(), 0, ring.array(), at * frameSize, first * frameSize);
        System.arraycopy(block.array(), first * frameSize, ring.array(), 0, (frames - first) * frameSize);
        quiet += frames;
        long until = Math.max(0, keep(quiet) - t); //the start of the pause that is kept whatever comes
        out.put(head.array(), (int) sent * frameSize, (int) (until - sent) * frameSize);
        sent = until;
    }

    /**
     * Ends the pause: one up to {@code k} plays as it is; a longer one plays its kept start, the crossfade into its
     * last {@code t} frames and the rest of them — {@link #keep} frames in all.
     */
    private void finish(ByteBuffer out) {
        if (quiet == 0) {
            return;
        }
        long keep = keep(quiet);
        int x = (int) Math.min(xfade, quiet - keep);
        if (x == 0) {
            out.put(head.array(), (int) sent * frameSize, (int) (quiet - sent) * frameSize);
        } else {
            long h = keep - t;
            long tail = quiet - t;
            out.put(head.array(), (int) sent * frameSize, (int) (h - sent) * frameSize);
            for (int i = 0; i < x; i++) {
                float g = (i + 1f) / (x + 1);
                int from = (int) (h + i) * frameSize;
                int to = (int) ((tail + i) % ringFrames) * frameSize;
                for (int c = 0; c < frameSize; c += 2) {
                    out.putShort((short) Math.round(head.getShort(from + c) * (1 - g) + ring.getShort(to + c) * g));
                }
            }
            int at = (int) ((tail + x) % ringFrames);
            int first = Math.min(t - x, ringFrames - at);
            out.put(ring.array(), at * frameSize, first * frameSize);
            out.put(ring.array(), 0, (t - x - first) * frameSize);
            skipped += quiet - keep;
        }
        quiet = 0;
        sent = 0;
    }

    /** The frames a pause of {@code frames} keeps. */
    private long keep(long frames) {
        return frames <= k ? frames : Math.min(cap, k + (long) (r * (frames - k)));
    }

    /**
     * The block's mean distance from the DC level per sample, -1 for digital silence; the DC level of each channel
     * follows the blocks' mean (τ ≈ 160 ms).
     */
    private float measure(int frames) {
        int bits = 0;
        long abs = 0;
        for (int c = 0; c < channels; c++) {
            int d = Math.round(dc[c]);
            long sum = 0;
            for (int i = 2 * c, end = frames * frameSize; i < end; i += frameSize) {
                int s = block.getShort(i);
                bits |= s;
                sum += s;
                abs += Math.abs(s - d);
            }
            dc[c] += ((float) sum / frames - dc[c]) / 8;
        }
        return bits == 0 ? -1 : (float) abs / (frames * channels);
    }

    private int ms(int ms) {
        return (int) ((long) rate * ms / 1000);
    }

    @Override
    protected void onFlush(@NonNull StreamMetadata streamMetadata) {
        //a seek, a new stream, a speed change: what was held is not played, the recording is learnt anew
        fill = 0;
        quiet = 0;
        sent = 0;
        skipped = 0;
        ending = false;
        speech = 0;
        floorPrev = 0; //clean until measured
        floorNow = Float.MAX_VALUE;
        floorBlocks = 0;
        idle = 0;
        if (!enabled || inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            block = head = ring = null; //nothing kept while off
            return;
        }
        rate = inputAudioFormat.sampleRate;
        channels = inputAudioFormat.channelCount;
        frameSize = inputAudioFormat.bytesPerFrame;
        xfade = ms(XFADE_MS);
        headFrames = ms(HEAD_MS);
        ringFrames = ms(RING_MS);
        block = buffer(block, Math.max(1, ms(BLOCK_MS)) * frameSize);
        head = buffer(head, headFrames * frameSize);
        ring = buffer(ring, ringFrames * frameSize);
        if (dc.length != channels) {
            dc = new float[channels];
        }
        Arrays.fill(dc, 0);
    }

    @Override
    protected void onReset() {
        block = head = ring = null;
    }

    /** {@code buffer} if it has {@code bytes}, else a new one; native order, as the samples come. */
    private static ByteBuffer buffer(ByteBuffer buffer, int bytes) {
        return buffer != null && buffer.capacity() == bytes ? buffer
                : ByteBuffer.allocate(bytes).order(ByteOrder.nativeOrder());
    }
}
