package com.fanok.audiobooks;

import android.content.Context;
import android.content.res.Configuration;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

/**
 * «Оформление → Размер шрифта»: the app's own text size, a percentage of the system's font scale. Each activity wraps
 * its base context ({@link #wrap}), so every sp size of its screens, dialogs and menus follows and the sizes keep their
 * proportions; what the system draws (the notification, toasts) keeps the system's size. At 100% nothing is wrapped.
 */
public final class FontScale {

    /** The percentage, an int: {@link #MIN}..{@link #MAX} in steps of {@link #STEP}. Not synced: a matter of the device. */
    public static final String PREF = "pref_font_scale";
    public static final int MIN = 50;
    public static final int MAX = 200;
    public static final int STEP = 5;
    public static final int DEFAULT = 100;
    /** The setting never takes the scale beyond these, whatever the system's; the system's own scale may be beyond. */
    static final float FLOOR = 0.5f;
    static final float CEILING = 2f;

    private FontScale() {
    }

    public static int percent(@NonNull Context context) {
        try {
            return Math.max(MIN, Math.min(MAX, PreferenceManager.getDefaultSharedPreferences(context)
                    .getInt(PREF, DEFAULT)));
        } catch (ClassCastException e) {
            return DEFAULT;
        }
    }

    /** {@code base} for an activity's {@code attachBaseContext}: the same resources at the chosen font scale. */
    @NonNull
    public static Context wrap(@NonNull Context base) {
        int percent = percent(base);
        if (percent == DEFAULT) {
            return base;
        }
        // only the font scale: a full copy would pin the night mode, the locale and the screen size of now
        Configuration delta = new Configuration();
        delta.fontScale = effective(base.getResources().getConfiguration().fontScale, percent);
        return base.createConfigurationContext(delta);
    }

    /** Pure: the font scale of {@code percent} of the {@code system} one, kept within the bounds. */
    static float effective(float system, int percent) {
        float scale = system * percent / 100f;
        return percent < DEFAULT ? Math.max(scale, Math.min(system, FLOOR))
                : Math.min(scale, Math.max(system, CEILING));
    }

    /**
     * Rows and labels of a fixed size hold larger text: each of {@code boxes} grows with the font scale of its screen
     * (its fixed width and height; never shrinks). Call once, on a newly inflated view.
     */
    public static void grow(@NonNull View... boxes) {
        for (View box : boxes) {
            float scale = box.getResources().getConfiguration().fontScale;
            ViewGroup.LayoutParams params = box.getLayoutParams();
            if (scale <= 1f || params == null) {
                continue;
            }
            if (params.width > 0) {
                params.width = Math.round(params.width * scale);
            }
            if (params.height > 0) {
                params.height = Math.round(params.height * scale);
            }
        }
    }
}
