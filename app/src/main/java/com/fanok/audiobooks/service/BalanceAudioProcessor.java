package com.fanok.audiobooks.service;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * The left/right balance, the bass and the night sound of playback, played by the audio sink
 * ({@link EndAwareRenderersFactory}): audiofx has no balance; its BassBoost (AOSP's LVM bundle) turns the whole sound
 * down to make room for the bass — 3 dB at its lowest step, 9 dB at its highest, while the bass gains at most 15 — so
 * the bass mostly wins by the rest losing, and on the phone speaker it plays no bass at all but keeps the cut; its
 * dynamics effects are either crude (LoudnessEnhancer) or costly and API 28+ (DynamicsProcessing). Mono is played as
 * stereo so that it can lean to a side too. The settings are the app's ones ({@link #setBalance}, {@link #setBass},
 * {@link #setNightSound}), read for every buffer: a change is heard at once, ramped so it does not click. The sink
 * hands it 16-bit PCM (it converts other PCM first while float output is off, as it is here); anything else and more
 * than two channels pass untouched.
 * <p>
 * Bass lifts the body of the voice by up to {@link #BASS_MAX_DB} at {@link #BASS_HZ}: the sound plus a band of it
 * around there, so hum and rumble below gain little and the treble nothing (at full boost +1.3 dB at 50 Hz, +0.3 dB
 * at 1 kHz). The band's filter never changes, only its share does, ramped over a buffer: turning the knob or the
 * equalizer never clicks. Nothing else is turned down: only a block whose lifted peak would clip plays quieter, as
 * below, and comes back with τ = 250 ms, so a bassy voice is not pumped. The peak is read as it will leave, after the
 * balance.
 * <p>
 * Night sound evens the level for listening quietly: every 5 ms block, a slow leveler steers the speech peaks to
 * {@link #TARGET} (lifting whispers by up to 12 dB, taking shouts down by up to 12 dB) and a limiter keeps every sample
 * under {@link #CEILING}. Blocks under {@link #GATE} (pauses, hiss) leave the level as it is instead of raising it.
 * The block's peak is read before it is played, so the gain goes down before the first sample that would be too loud,
 * not after it; within a block it moves linearly. Without night sound the same holds every block under full scale.
 * Per sample that is a peak pass, the filter and a few multiplications, no log/exp, nothing allocated. The level is
 * kept over seeks and chapters, which then start at the gain they left off with.
 */
@OptIn(markerClass = UnstableApi.class)
public final class BalanceAudioProcessor extends BaseAudioProcessor {

    /** The balance runs from all left ({@code -MAX}) through the centre (0) to all right ({@code MAX}). */
    public static final int MAX = 100;
    /** The bass knob's strength runs from 0 (none) to this, {@link #BASS_MAX_DB} of boost. */
    public static final int BASS_MAX = 1000;
    static final float BASS_MAX_DB = 9f;
    /** The band the bass adds: a band-pass at this frequency (Hz) of this Q, peak gain 1. */
    static final double BASS_HZ = 150, BASS_Q = 1.6;

    /** Night sound levels in 16-bit sample units: −12, −6, −40 dBFS and full scale; the leveler's gain is within ±12 dB. */
    static final float TARGET = 8192f, CEILING = 16384f, GATE = 328f, FULL = 32767f, GAIN_MIN = .25f, GAIN_MAX = 4f;
    /**
     * Steps per 5 ms block, 1 − e^(−5 ms / τ): the level follows louder peaks with τ = 100 ms and quieter ones with
     * τ = 1.5 s; the limiter, and the gain going back to 1 once night sound is off, recover with τ = 100 ms.
     */
    private static final float ATTACK = .0488f, RELEASE = .00333f, RECOVER = .0488f, BASS_RECOVER = .0198f;

    /** Gains of the left and right channel, replaced as a pair so a buffer never gets halves of two balances. */
    private static volatile float[] channelGains = {1f, 1f};
    private static volatile boolean nightSound;
    /** The share of the bass band added to the sound, 0 for none: 10^(dB/20) − 1. */
    private static volatile float bassShare;

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
    /** The block as it is played before its gain: left and right of every frame, the bass lifted. */
    private float[] mix = new float[2];
    /** The bass band's share the last buffer ended with, and its step over this one; playback thread. */
    private float bass;
    private float bassStep;
    /** The band-pass: its coefficients (b1 = 0, b2 = −b0), x1 x2 y1 y2 of each channel. */
    private float b0, a1, a2;
    private final float[] state = new float[8];

    /** Sets the balance, clamped to {@code ±MAX}; any thread. */
    public static void setBalance(int balance) {
        channelGains = gains(balance);
    }

    /** Sets the bass from the knob's strength, 0 (none) to {@link #BASS_MAX}, clamped; any thread. */
    public static void setBass(int strength) {
        float db = Math.max(0, Math.min(BASS_MAX, strength)) * BASS_MAX_DB / BASS_MAX;
        bassShare = (float) Math.pow(10, db / 20) - 1;
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
        float share = bassShare;
        boolean lift = share > 0 || bass > 0; //on, or going off
        bassStep = (share - bass) / frames;
        if (night && !wasNight) { //starts at the gain it has and adapts from there
            env = TARGET;
            limiter = 1f;
        }
        wasNight = night;
        if (!night && !lift && gain == 1f && frameSize == 4 && left == 1f && right == 1f
                && target[0] == 1f && target[1] == 1f) {
            buffer.put(inputBuffer);
        } else {
            leftStep = (target[0] - left) / frames;
            rightStep = (target[1] - right) / frames;
            for (int i = position, end = position + frames * frameSize; i < end; ) {
                i = level(inputBuffer, buffer, i, Math.min(block, (end - i) / frameSize), frameSize, night, lift);
            }
            inputBuffer.position(limit);
        }
        left = target[0];
        right = target[1];
        if (lift && share == 0) { //off: the band is forgotten, unheard (the ramp may have ended at a hair from 0)
            Arrays.fill(state, 0);
        }
        bass = share;
        buffer.flip();
    }

    /**
     * Plays one block of {@code frames} frames from {@code i}: the bass lifted, at the night gain, or, with night sound
     * off, at a gain going back to 1 under full scale; returns the index after it. Both ends of every ramp are at most
     * the block's cap, so no sample leaves above the ceiling (but for a rounding step of the float ramps).
     */
    private int level(ByteBuffer in, ByteBuffer out, int i, int frames, int frameSize, boolean night, boolean lift) {
        float ceiling = night ? CEILING : FULL;
        float loud = ceiling / gain; //a sample above it would leave too loud at the gain the last block ended with
        float peak = 0;
        int over = frames;
        float gl = left;
        float gr = right;
        for (int f = 0, j = i; f < frames; f++, j += frameSize) {
            float l = in.getShort(j);
            float r = frameSize == 2 ? l : in.getShort(j + 2);
            if (lift) {
                bass += bassStep;
                l += bass * band(0, l);
                r = frameSize == 2 ? l : r + bass * band(4, r);
            }
            mix[2 * f] = l;
            mix[2 * f + 1] = r;
            gl += leftStep; //as it will leave: a side turned down cannot hold the other one down
            gr += rightStep;
            float a = Math.max(Math.abs(l * gl), Math.abs(r * gr));
            if (a > peak) {
                peak = a;
                if (a > loud && over == frames) {
                    over = f;
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
        } else { //back to 1, exactly: only a gain still above it, or the bass, is held under full scale
            if (!lift) {
                cap = Math.max(1f, cap);
            }
            float recover = lift ? BASS_RECOVER : RECOVER;
            next = Math.min(cap, Math.abs(gain - 1f) < .001f ? 1f : gain + (1f - gain) * recover * k);
        }
        int f = 0;
        if (over < frames) { //down to the cap by the first sample that would be too loud
            play(out, 0, over, gain, cap);
            f = over;
            gain = cap;
        }
        play(out, f, frames - f, gain, next);
        gain = next;
        return i + frames * frameSize;
    }

    /** Plays {@code frames} frames of the block from frame {@code f}, the gain going from {@code from} to {@code to}. */
    private void play(ByteBuffer out, int f, int frames, float from, float to) {
        float step = (to - from) / frames;
        float g = from;
        for (int end = f + frames; f < end; f++) {
            g += step;
            left += leftStep;
            right += rightStep;
            out.putShort(scale(mix[2 * f], g * left)).putShort(scale(mix[2 * f + 1], g * right));
        }
    }

    /** One sample of channel {@code c} (its state from {@code c}) through the bass band-pass. */
    private float band(int c, float x) {
        float y = b0 * (x - state[c + 1]) - a1 * state[c + 2] - a2 * state[c + 3];
        state[c + 1] = state[c];
        state[c] = x;
        state[c + 3] = state[c + 2];
        state[c + 2] = y;
        return y;
    }

    @Override
    protected void onFlush(@NonNull StreamMetadata streamMetadata) {
        //a new stream starts at the balance and the bass, no ramp; the night level carries over
        float[] target = channelGains;
        left = target[0];
        right = target[1];
        bass = bassShare;
        block = Math.max(1, inputAudioFormat.sampleRate / 200);
        if (mix.length < 2 * block) {
            mix = new float[2 * block];
        }
        Arrays.fill(state, 0);
        //RBJ's band-pass of peak gain 1 at this stream's rate, computed once in double
        double w = 2 * Math.PI * BASS_HZ / Math.max(1, inputAudioFormat.sampleRate);
        double alpha = Math.sin(w) / (2 * BASS_Q);
        b0 = (float) (alpha / (1 + alpha));
        a1 = (float) (-2 * Math.cos(w) / (1 + alpha));
        a2 = (float) ((1 - alpha) / (1 + alpha));
    }

    @Override
    protected void onReset() {
        gain = 1f;
        wasNight = false;
    }

    private static short scale(float sample, float gain) {
        //saturated: a ramp's float steps may end a hair above 1, and 32768 would wrap to -32768 (a click)
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(sample * gain)));
    }
}
