package com.fanok.audiobooks.model;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
import com.fanok.audiobooks.R;

/**
 * The history's two filters, both remembered: the listening status (the switcher on the left of the toolbar) and
 * the period of the last opening of the book's page (the calendar on the right).
 */
public final class HistoryFilter {
    public static final int STATUS_ALL = 0;
    public static final int STATUS_NOT_STARTED = 1;
    public static final int STATUS_LISTENING = 2;
    public static final int STATUS_FINISHED = 3;

    public static final int PERIOD_ALL = 0;
    public static final int PERIOD_YEAR = 1;
    public static final int PERIOD_MONTH = 2;
    public static final int PERIOD_WEEK = 3;
    public static final int PERIOD_DAY = 4;

    /** The names of the statuses and of the periods, by value. */
    public static final int[] STATUS_TITLES = {R.string.history_all, R.string.history_not_started,
            R.string.history_listening, R.string.history_finished};
    public static final int[] PERIOD_TITLES = {R.string.history_period_all, R.string.history_period_year,
            R.string.history_period_month, R.string.history_period_week, R.string.history_period_day};
    /** The period items of the toolbar's menu, by value. */
    public static final int[] PERIOD_ITEMS = {R.id.history_period_all, R.id.history_period_year,
            R.id.history_period_month, R.id.history_period_week, R.id.history_period_day};

    private static final String PREF_STATUS = "history_status";
    private static final String PREF_PERIOD = "history_period";
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    /** The sliding window of each period, in days, by value. */
    private static final int[] PERIOD_DAYS = {0, 365, 30, 7, 1};

    private HistoryFilter() {
    }

    public static int status(@NonNull Context context) {
        return valid(prefs(context).getInt(PREF_STATUS, STATUS_ALL), STATUS_TITLES.length);
    }

    public static void setStatus(@NonNull Context context, int status) {
        prefs(context).edit().putInt(PREF_STATUS, status).apply();
    }

    public static int period(@NonNull Context context) {
        return valid(prefs(context).getInt(PREF_PERIOD, PERIOD_ALL), PERIOD_TITLES.length);
    }

    public static void setPeriod(@NonNull Context context, int period) {
        prefs(context).edit().putInt(PREF_PERIOD, period).apply();
    }

    /** A status or a period narrows the history. */
    public static boolean active(@NonNull Context context) {
        return status(context) != STATUS_ALL || period(context) != PERIOD_ALL;
    }

    /** The status of a book listened to {@code percent} (ListeningProgress; -1 is no progress at all). */
    public static int statusOf(int percent) {
        if (percent <= 0) {
            return STATUS_NOT_STARTED;
        }
        return percent >= 100 ? STATUS_FINISHED : STATUS_LISTENING;
    }

    /**
     * The earliest opening {@code period} keeps at {@code now} (epoch ms): the last 1, 7, 30 or 365 days. All the
     * time keeps everything, the undated rows (0) too.
     */
    public static long since(int period, long now) {
        return period > PERIOD_ALL && period < PERIOD_DAYS.length ? now - PERIOD_DAYS[period] * DAY_MS
                : Long.MIN_VALUE;
    }

    /** The period of the menu item {@code itemId}, or -1 if it is none of them. */
    public static int periodOf(int itemId) {
        for (int i = 0; i < PERIOD_ITEMS.length; i++) {
            if (PERIOD_ITEMS[i] == itemId) {
                return i;
            }
        }
        return -1;
    }

    private static int valid(int value, int count) {
        return value >= 0 && value < count ? value : 0;
    }

    private static SharedPreferences prefs(@NonNull Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }
}
