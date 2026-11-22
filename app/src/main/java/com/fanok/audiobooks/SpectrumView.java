package com.fanok.audiobooks;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.PowerManager;
import android.util.AttributeSet;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.fanok.audiobooks.service.SpectrumMeter;
import com.google.android.material.color.MaterialColors;

/**
 * The cover player's spectrum meter, an old amplifier's LED display: {@link SpectrumMeter#BANDS} columns of
 * {@link SpectrumMeter#SEGMENTS} segments in the chapter name's colours (the top two in its digits' one), the dark
 * segments faintly shown, and over each column a peak that holds a moment. A column rises at once and falls a segment
 * a frame. It runs at 25 frames a second only while the book plays and the meter is in sight, not in power saving; on
 * a pause the columns fall and it stops, so a paused or hidden meter costs nothing. It redraws only what changed.
 */
public final class SpectrumView extends View implements Runnable {

    private static final long FRAME_MS = 40;
    /** A peak holds this many frames (600 ms), then falls a segment every {@code PEAK_FALL} frames (80 ms). */
    private static final int PEAK_HOLD = 15;
    private static final int PEAK_FALL = 2;
    /** The dark segments' alpha, of 255. */
    private static final int GHOST_ALPHA = 31;

    private final int[] mBars = new int[SpectrumMeter.BANDS];
    private final int[] mPeaks = new int[SpectrumMeter.BANDS];
    private final int[] mHolds = new int[SpectrumMeter.BANDS];
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int mLow;
    private final int mHigh;
    private final float mGap;
    private final float mRadius;
    private boolean mPlaying;
    private boolean mRunning;

    public SpectrumView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mLow = MaterialColors.getColor(this, R.attr.chapterSymbolColor);
        mHigh = MaterialColors.getColor(this, R.attr.chapterDigitColor);
        float dp = getResources().getDisplayMetrics().density;
        mGap = 2 * dp;
        mRadius = dp;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** The book plays (the player shows its pause icon): the meter runs; else its columns fall and it stops. */
    public void setPlaying(boolean playing) {
        mPlaying = playing;
        start();
    }

    private boolean live() {
        return mPlaying && getWindowVisibility() == VISIBLE;
    }

    private void start() {
        if (mRunning || !live()) {
            return;
        }
        PowerManager power = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
        if (power != null && power.isPowerSaveMode()) {
            return; // the dark segments only
        }
        mRunning = true;
        SpectrumMeter.clear();
        post(this);
    }

    /** Out of sight: it stops at once and starts dark when shown again. */
    private void stop() {
        mRunning = false;
        SpectrumMeter.on = false;
        removeCallbacks(this);
        java.util.Arrays.fill(mBars, 0);
        java.util.Arrays.fill(mPeaks, 0);
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            start();
        } else {
            stop();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    /** One frame. */
    @Override
    public void run() {
        boolean live = live();
        SpectrumMeter.on = live;
        long levels = live ? SpectrumMeter.levels() : 0;
        boolean changed = false;
        boolean lit = false;
        for (int b = 0; b < SpectrumMeter.BANDS; b++) {
            int level = SpectrumMeter.band(levels, b);
            int bar = level >= mBars[b] ? level : mBars[b] - 1;
            int peak = mPeaks[b];
            if (bar >= peak) {
                peak = bar;
                mHolds[b] = PEAK_HOLD;
            } else if (--mHolds[b] <= 0) {
                peak--;
                mHolds[b] = PEAK_FALL;
            }
            changed |= bar != mBars[b] || peak != mPeaks[b];
            mBars[b] = bar;
            mPeaks[b] = peak;
            lit |= peak > 0;
        }
        if (changed) {
            invalidate();
        }
        if (live || lit) {
            postDelayed(this, FRAME_MS);
        } else {
            mRunning = false;
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        int bands = SpectrumMeter.BANDS;
        int segments = SpectrumMeter.SEGMENTS;
        float width = (getWidth() - getPaddingLeft() - getPaddingRight() - mGap * (bands - 1)) / bands;
        float height = (getHeight() - getPaddingTop() - getPaddingBottom() - mGap * (segments - 1)) / segments;
        float floor = getHeight() - getPaddingBottom();
        for (int b = 0; b < bands; b++) {
            float left = getPaddingLeft() + b * (width + mGap);
            for (int s = 0; s < segments; s++) {
                float bottom = floor - s * (height + mGap);
                mPaint.setColor(s >= segments - 2 ? mHigh : mLow);
                if (s >= mBars[b] && s != mPeaks[b] - 1) {
                    mPaint.setAlpha(GHOST_ALPHA);
                }
                canvas.drawRoundRect(left, bottom - height, left + width, bottom, mRadius, mRadius, mPaint);
            }
        }
    }
}
