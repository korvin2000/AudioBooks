package com.fanok.audiobooks.util;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Layout;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * Shows a cut one-line name in full now and then: it scrolls once to its end at about 40 dp/s, holds a second,
 * fades and comes back with its ellipsis. Automatic runs are due 3 s, 1 min and 2 min after the page opens, and
 * happen only while the name is on screen in a focused window; at most three per key (the book) per app process,
 * none with system animations off. Nothing is posted between the owner's {@link #cancel} (pause) and
 * {@link #schedule} (resume); the view's click and long-press listeners stay the owner's.
 */
public final class SeriesPeek implements Runnable {

    /** When the automatic runs are due, from the page's opening. */
    private static final long[] DUE_MS = {3_000, 60_000, 120_000};

    private static final float SPEED_DP_PER_S = 40f;

    private static final long HOLD_MS = 1_000;

    private static final long FADE_MS = 250;

    /** Automatic runs made per key in this process; main thread only. */
    private static final Map<String, Integer> RUNS = new HashMap<>();

    private final TextView mView;

    private final String mKey;

    private final long mOpenedAt = SystemClock.uptimeMillis();

    /** The next due run of this page; past the last one nothing is scheduled. */
    private int mSlot;

    @Nullable
    private Animator mRun;

    public SeriesPeek(@NonNull TextView view, @NonNull String key) {
        mView = view;
        mKey = key;
    }

    /** Posts the next due run, if any is left. */
    public void schedule() {
        mView.removeCallbacks(this);
        long now = SystemClock.uptimeMillis();
        //runs missed while paused are not replayed back to back: one catch-up, the next one still a minute on
        while (mSlot < DUE_MS.length - 1 && mOpenedAt + DUE_MS[mSlot + 1] <= now) {
            mSlot++;
        }
        if (mSlot < DUE_MS.length && runs() < DUE_MS.length) {
            long wait = mOpenedAt + DUE_MS[mSlot] - now;
            mView.postDelayed(this, Math.max(DUE_MS[0], wait));
        }
    }

    /** Drops the pending run and ends a running one, back to the ellipsis. */
    public void cancel() {
        mView.removeCallbacks(this);
        if (mRun != null) {
            mRun.cancel();
        }
    }

    @Override
    public void run() {
        mSlot++;
        if (mRun == null) { //else a long-press replay is showing it right now
            if (!animatorsOn() || !cut()) {
                mSlot = DUE_MS.length; //nothing to show, or no motion wanted
                return;
            }
            if (onScreen() && play()) {
                RUNS.put(mKey, runs() + 1);
            }
        }
        schedule();
    }

    /** One run now, if the name is cut (the long-press replay, not counted); true while a run shows it. */
    public boolean play() {
        if (mRun != null) {
            return true;
        }
        if (!cut()) {
            return false;
        }
        CharSequence text = mView.getText();
        int room = mView.getWidth() - mView.getTotalPaddingLeft() - mView.getTotalPaddingRight();
        int dx = (int) Math.ceil(mView.getPaint().measureText(text, 0, text.length())) - room;
        if (dx <= 0) {
            return false;
        }
        float density = mView.getResources().getDisplayMetrics().density;
        ValueAnimator scroll = ValueAnimator.ofInt(0, dx);
        scroll.setDuration((long) (dx * 1000f / (SPEED_DP_PER_S * density)));
        scroll.addUpdateListener(animation -> mView.setScrollX((Integer) animation.getAnimatedValue()));
        Animator out = ObjectAnimator.ofFloat(mView, View.ALPHA, 0f).setDuration(FADE_MS);
        out.setStartDelay(HOLD_MS);
        Animator in = ObjectAnimator.ofFloat(mView, View.ALPHA, 1f).setDuration(FADE_MS);
        in.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                rest(); //unseen at alpha 0
            }
        });
        AnimatorSet run = new AnimatorSet();
        run.playSequentially(scroll, out, in);
        run.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) { //also after cancel()
                rest();
                mView.setAlpha(1f);
                mRun = null;
            }
        });
        //the whole line is laid out and scrolled under soft edges; the view keeps its width
        mView.setEllipsize(null);
        mView.setHorizontalFadingEdgeEnabled(true);
        mRun = run;
        run.start();
        return true;
    }

    private void rest() {
        mView.setScrollX(0);
        mView.setHorizontalFadingEdgeEnabled(false);
        mView.setEllipsize(TextUtils.TruncateAt.END);
    }

    private int runs() {
        Integer runs = RUNS.get(mKey);
        return runs == null ? 0 : runs;
    }

    private boolean cut() {
        Layout layout = mView.getLayout();
        return layout != null && layout.getLineCount() > 0 && layout.getEllipsisCount(0) > 0;
    }

    private boolean onScreen() {
        return mView.isShown() && mView.hasWindowFocus() && mView.getGlobalVisibleRect(new Rect());
    }

    private boolean animatorsOn() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return ValueAnimator.areAnimatorsEnabled();
        }
        return Settings.Global.getFloat(mView.getContext().getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f;
    }
}
