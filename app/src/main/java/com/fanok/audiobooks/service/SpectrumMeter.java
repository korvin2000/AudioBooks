package com.fanok.audiobooks.service;

import androidx.annotation.NonNull;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * A rough spectrum of what plays, for the cover's meter ({@code SpectrumView}): {@link #BANDS}
 * log-spaced bands from 80 Hz to 8 kHz, each as 0 to {@link #SEGMENTS} lit segments. {@link BalanceAudioProcessor}
 * feeds it the 16-bit PCM it plays, before its gains and before the speed, only while a meter is shown ({@link #on}):
 * no permission is needed, unlike audiofx's Visualizer. Every {@link #FRAME_MS} a Hann-windowed {@link #N}-point FFT
 * of the last frames, the bands' power compared with precomputed thresholds, no log. Each result is one long (four
 * bits a band and the time), so a reader on another thread never gets half of two. The sink plays its buffer later
 * than it is processed: the meter shows the levels of about {@link #DELAY_MS} ago, none when they are older.
 */
public final class SpectrumMeter {

    public static final int BANDS = 12;
    public static final int SEGMENTS = 8;
    /** About 23 ms at 44.1 kHz: bins 43 Hz apart, so even the lowest band has its own. */
    static final int N = 1024;
    private static final int SLOTS = 16;
    private static final long FRAME_MS = 40;
    /** The sink's buffer, 250 ms by default, and a little of the output's own latency; then how long levels hold. */
    private static final long DELAY_MS = 300;
    private static final long STALE_MS = 150;
    private static final double LOW_HZ = 80;
    private static final double HIGH_HZ = 8000;
    /**
     * The first segment lights at {@code FLOOR_DB} dBFS (a full-scale sine is 0), each next one {@code STEP_DB}
     * higher; a band is {@code TILT_DB} more sensitive than the one below it, as speech has less in the treble.
     */
    private static final double FLOOR_DB = -43;
    private static final double STEP_DB = 5;
    private static final double TILT_DB = 1;

    /** A meter is shown and playing: the processor feeds it. Any thread. */
    public static volatile boolean on;

    private static final float[] WINDOW = new float[N];
    private static final float[] COS = new float[N / 2];
    private static final float[] SIN = new float[N / 2];
    private static final float[] THRESHOLDS = new float[BANDS * SEGMENTS];

    static {
        for (int i = 0; i < N; i++) {
            WINDOW[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (N - 1)));
        }
        for (int k = 0; k < N / 2; k++) {
            COS[k] = (float) Math.cos(2 * Math.PI * k / N);
            SIN[k] = (float) Math.sin(2 * Math.PI * k / N);
        }
        // a full-scale sine's bin in 16-bit units through the window: 32768 * N / 4
        double full = 32768.0 * N / 4;
        for (int b = 0; b < BANDS; b++) {
            for (int s = 0; s < SEGMENTS; s++) {
                double db = FLOOR_DB + STEP_DB * s - TILT_DB * b;
                THRESHOLDS[b * SEGMENTS + s] = (float) (full * full * Math.pow(10, db / 10));
            }
        }
    }

    /** The results, each levels | time << 48; written by the playback thread only. */
    private static final AtomicLongArray RESULTS = new AtomicLongArray(SLOTS);
    private static int slot;

    /** Playback thread: the last N mono samples, the FFT's scratch, the bands' first bins at this rate. */
    private static final float[] RING = new float[N];
    private static final float[] RE = new float[N];
    private static final float[] IM = new float[N];
    private static final int[] EDGES = new int[BANDS + 1];
    private static int at;
    private static int rate;
    private static long nextMs;

    private SpectrumMeter() {
    }

    /**
     * Takes {@code frames} frames of 16-bit PCM, mono or stereo ({@code frameSize} 2 or 4), from {@code from} of
     * {@code in} without moving its position; analyses the last ones when a frame of the meter is due. Playback thread.
     */
    static void feed(@NonNull ByteBuffer in, int from, int frames, int frameSize, int sampleRate) {
        if (sampleRate != rate) {
            bands(sampleRate);
        }
        int skip = Math.max(0, frames - N);
        for (int f = skip, j = from + skip * frameSize; f < frames; f++, j += frameSize) {
            float l = in.getShort(j);
            RING[at] = frameSize == 2 ? l : (l + in.getShort(j + 2)) * .5f;
            at = (at + 1) & (N - 1);
        }
        long now = nowMs();
        if (now >= nextMs) {
            nextMs = now + FRAME_MS;
            slot = (slot + 1) & (SLOTS - 1);
            RESULTS.set(slot, analyze() | (now >> 2 & 0xFFFF) << 48);
        }
    }

    /** Forgets the levels: a meter starting does not show old ones (their time wraps after 262 s). Any thread. */
    public static void clear() {
        for (int i = 0; i < SLOTS; i++) {
            RESULTS.set(i, 0);
        }
    }

    /** A seek or a new stream: nothing buffered before it is played, nor analysed with what follows. Playback thread. */
    static void flush() {
        clear();
        java.util.Arrays.fill(RING, 0);
        nextMs = 0;
    }

    /** The lit segments of band {@code band} in {@code levels} ({@link #levels}). */
    public static int band(long levels, int band) {
        return (int) (levels >>> (4 * band) & 0xF);
    }

    /** The levels to show now: those processed about {@link #DELAY_MS} ago, 0 (all dark) when none are. Any thread. */
    public static long levels() {
        long now = nowMs() >> 2; // the results' time is in 4 ms units, 16 bits: it wraps after 262 s
        long best = 0;
        long bestAge = Long.MAX_VALUE;
        for (int i = 0; i < SLOTS; i++) {
            long result = RESULTS.get(i);
            long age = (now - (result >>> 48)) & 0xFFFF;
            if (age >= DELAY_MS / 4 && age < bestAge) {
                bestAge = age;
                best = result;
            }
        }
        return bestAge <= (DELAY_MS + STALE_MS) / 4 ? best & 0xFFFFFFFFFFFFL : 0;
    }

    private static long nowMs() {
        return System.nanoTime() / 1_000_000;
    }

    /** The first FFT bin of every band at {@code sampleRate}, each band at least one bin wide. */
    private static void bands(int sampleRate) {
        rate = sampleRate;
        for (int b = 0; b <= BANDS; b++) {
            double hz = LOW_HZ * Math.pow(HIGH_HZ / LOW_HZ, (double) b / BANDS);
            int bin = (int) Math.round(hz * N / Math.max(1, sampleRate));
            EDGES[b] = Math.min(N / 2, b == 0 ? Math.max(1, bin) : Math.max(EDGES[b - 1] + 1, bin));
        }
    }

    /** The ring's spectrum as {@link #BANDS} counts of lit segments, four bits each, the lowest band first. */
    static long analyze() {
        for (int i = 0, k = at; i < N; i++, k = (k + 1) & (N - 1)) {
            RE[i] = RING[k] * WINDOW[i];
            IM[i] = 0;
        }
        fft();
        long levels = 0;
        for (int b = 0; b < BANDS; b++) {
            float power = 0;
            for (int k = EDGES[b]; k < EDGES[b + 1]; k++) {
                power += RE[k] * RE[k] + IM[k] * IM[k];
            }
            int lit = 0;
            while (lit < SEGMENTS && power >= THRESHOLDS[b * SEGMENTS + lit]) {
                lit++;
            }
            levels |= (long) lit << (4 * b);
        }
        return levels;
    }

    /** In-place iterative radix-2 FFT of RE + i IM. */
    private static void fft() {
        for (int i = 1, j = 0; i < N; i++) {
            int bit = N >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                float t = RE[i];
                RE[i] = RE[j];
                RE[j] = t;
                t = IM[i];
                IM[i] = IM[j];
                IM[j] = t;
            }
        }
        for (int len = 2; len <= N; len <<= 1) {
            int half = len >> 1;
            int step = N / len;
            for (int i = 0; i < N; i += len) {
                for (int k = 0; k < half; k++) {
                    float c = COS[k * step];
                    float s = SIN[k * step];
                    int a = i + k;
                    int b = a + half;
                    float tr = RE[b] * c + IM[b] * s;
                    float ti = IM[b] * c - RE[b] * s;
                    RE[b] = RE[a] - tr;
                    IM[b] = IM[a] - ti;
                    RE[a] += tr;
                    IM[a] += ti;
                }
            }
        }
    }
}
