package com.fanok.audiobooks.service;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;
import java.nio.ByteBuffer;

/**
 * The left/right balance and the night sound of playback, played by the audio sink ({@link EndAwareRenderersFactory}):
 * audiofx has no balance, and its dynamics effects are either crude (LoudnessEnhancer) or costly and API 28+
 * (DynamicsProcessing). Mono is played as stereo so that it can lean to a side too. Both settings are the app's ones
 * ({@link #setBalance}, {@link #setNightSound}), read for every buffer: a change is heard at once, ramped so it does not
 * click. The sink hands it 16-bit PCM (it converts other PCM first while float output is off, as it is here); anything
 * else and more than two channels pass untouched.
 * <p>
 * Night sound evens the level for listening quietly: every 5 ms block, a slow leveler steers the speech peaks to
 * {@link #TARGET} (lifting whispers by up to 12 dB, taking shouts down by up to 12 dB) and a limiter keeps every sample
 * under {@link #CEILING}. Blocks under {@link #GATE} (pauses, hiss) leave the level as it is instead of raising it.
 * The block's peak is read before it is played, so the gain goes down before the first sample that would be too loud,
 * not after it; within a block it moves linearly. Per sample that is a peak pass and a few multiplications, no log/exp,
 * nothing allocated. The level is kept over seeks and chapters, which then start at the gain they left off with.
 */
@OptIn(markerClass = UnstableApi.class)
public final class BalanceAudioProcessor extends BaseAudioProcessor {

    /** The balance runs from all left ({@code -MAX}) through the centre (0) to all right ({@code MAX}). */
    public static final int MAX = 100;

    /** Night sound levels in 16-bit sample units: −12, −6, −40 dBFS and full scale; the leveler's gain is within ±12 dB. */
    static final float TARGET = 8192f, CEILING = 16384f, GATE = 328f, FULL = 32767f, GAIN_MIN = .25f, GAIN_MAX = 4f;
    /**
     * Steps per 5 ms block, 1 − e^(−5 ms / τ): the level follows louder peaks with τ = 100 ms and quieter ones with
     * τ = 1.5 s; the limiter, and the gain going back to 1 once night sound is off, recover with τ = 100 ms.
     */
    private static final float ATTACK = .0488f, RELEASE = .00333f, RECOVER = .0488f;

    /** Gains of the left and right channel, replaced as a pair so a buffer never gets halves of two balances. */
    private static volatile float[] channelGains = {1f, 1f};
    private static volatile boolean nightSound;

    /** The gains the last buffer ended with, and their steps over this buffer; playback thread. */
    private float left = 1f;
    private float right = 1f;
    private float leftStep;
    private float rightStep;
    /** Night sound state, playback thread: frames per 5 ms, smoothed peak, limiter, the gain the last block ended with. */
    private int block = 1;
    private float env = TARGET;
    private float limiter = 1f;
    private float gain = 1f;
    private boolean wasNight;

    /** Sets the balance, clamped to {@code ±MAX}; any thread. */
    public static void setBalance(int balance) {
        channelGains = gains(balance);
    }

    /** Turns the night sound on or off; any thread. */
    public static void setNightSound(boolean on) {
        nightSound = on;
    }

    /**
     * The left and right gain for {@code balance}: the side it leans away from is turned down linearly, as Android's
     * own audio balance does, the other stays full. The centre is exactly 1 and 1: the samples play as they are.
     */
    static float[] gains(int balance) {
        int b = Math.max(-MAX, Math.min(MAX, balance));
        return new float[]{b > 0 ? 1f - (float) b / MAX : 1f, b < 0 ? 1f + (float) b / MAX : 1f};
    }

    @NonNull
    @Override
    protected AudioFormat onConfigure(@NonNull AudioFormat inputAudioFormat) {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT
                || inputAudioFormat.channelCount < 1 || inputAudioFormat.channelCount > 2) {
            return AudioFormat.NOT_SET;
        }
        return new AudioFormat(inputAudioFormat.sampleRate, 2, C.ENCODING_PCM_16BIT);
    }

    @Override
    public void queueInput(@NonNull ByteBuffer inputBuffer) {
        int position = inputBuffer.position();
        int limit = inputBuffer.limit();
        int frameSize = inputAudioFormat.bytesPerFrame;
        int frames = (limit - position) / frameSize;
        if (frames == 0) {
            return;
        }
        ByteBuffer buffer = replaceOutputBuffer(frames * outputAudioFormat.bytesPerFrame);
        float[] target = channelGains;
        boolean night = nightSound;
        if (night && !wasNight) { //starts at the gain it has and adapts from there
            env = TARGET;
            limiter = 1f;
        }
        wasNight = night;
        boolean level = night || gain != 1f;
        if (!level && frameSize == 4 && left == 1f && right == 1f && target[0] == 1f && target[1] == 1f) {
            buffer.put(inputBuffer);
        } else {
            leftStep = (target[0] - left) / frames;
            rightStep = (target[1] - right) / frames;
            if (level) {
                for (int i = position, end = position + frames * frameSize; i < end; ) {
                    i = level(inputBuffer, buffer, i, Math.min(block, (end - i) / frameSize), frameSize, night);
                }
            } else {
                play(inputBuffer, buffer, position, frames, frameSize, 1f, 1f);
            }
            inputBuffer.position(limit);
        }
        left = target[0];
        right = target[1];
        buffer.flip();
    }

    /**
     * Plays one block of {@code frames} frames from {@code i} at the night gain, or, with night sound off, at a gain
     * going back to 1 under full scale; returns the index after it. Both ends of every ramp are at most the block's
     * cap, so no sample leaves above the ceiling (but for a rounding step of the float ramps).
     */
    private int level(ByteBuffer in, ByteBuffer out, int i, int frames, int frameSize, boolean night) {
        int end = i + frames * frameSize;
        float ceiling = night ? CEILING : FULL;
        float loud = ceiling / gain; //a sample above it would leave too loud at the gain the last block ended with
        int peak = 0;
        int over = end;
        for (int j = i; j < end; j += 2) {
            int a = Math.abs(in.getShort(j));
            if (a > peak) {
                peak = a;
                if (a > loud && over == end) {
                    over = j;
                }
            }
        }
        float cap = ceiling / Math.max(1, peak);
        float k = (float) frames / block; //a buffer's short last block moves the level less
        float next;
        if (night) {
            if (peak > GATE) {
                env += (peak - env) * (peak > env ? ATTACK : RELEASE) * k;
            }
            float slow = Math.max(GAIN_MIN, Math.min(GAIN_MAX, TARGET / env));
            limiter = Math.min(limiter + (1f - limiter) * RECOVER * k, cap / slow);
            next = slow * limiter;
        } else { //back to 1, exactly: only a gain still above it is held under full scale
            cap = Math.max(1f, cap);
            next = Math.min(cap, Math.abs(gain - 1f) < .001f ? 1f : gain + (1f - gain) * RECOVER * k);
        }
        if (over < end) { //down to the cap by the first sample that would be too loud
            int before = (over - i) / frameSize;
            i = play(in, out, i, before, frameSize, gain, cap);
            frames -= before;
            gain = cap;
        }
        i = play(in, out, i, frames, frameSize, gain, next);
        gain = next;
        return i;
    }

    /** Plays {@code frames} frames from {@code i} at a gain going linearly from {@code from} to {@code to}, balanced. */
    private int play(ByteBuffer in, ByteBuffer out, int i, int frames, int frameSize, float from, float to) {
        float step = (to - from) / frames;
        float g = from;
        for (int end = i + frames * frameSize; i < end; i += frameSize) {
            g += step;
            left += leftStep;
            right += rightStep;
            short l = in.getShort(i);
            short r = frameSize == 2 ? l : in.getShort(i + 2);
            out.putShort(scale(l, g * left)).putShort(scale(r, g * right));
        }
        return i;
    }

    @Override
    protected void onFlush(@NonNull StreamMetadata streamMetadata) {
        //a new stream starts at the balance, no ramp; the night level carries over
        float[] target = channelGains;
        left = target[0];
        right = target[1];
        block = Math.max(1, inputAudioFormat.sampleRate / 200);
    }

    @Override
    protected void onReset() {
        gain = 1f;
        wasNight = false;
    }

    private static short scale(short sample, float gain) {
        //saturated: a ramp's float steps may end a hair above 1, and 32768 would wrap to -32768 (a click)
        return gain == 1f ? sample
                : (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(sample * gain)));
    }
}
