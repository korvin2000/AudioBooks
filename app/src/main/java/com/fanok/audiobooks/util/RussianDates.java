package com.fanok.audiobooks.util;

import androidx.annotation.NonNull;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Times and dates in the app's texts written the Russian way on any device language: «Проверено 31 окт., 21:35», not
 * «Проверено Oct 31, 9:35 PM». The formats are made per call: they are not thread-safe and cost little.
 */
public final class RussianDates {

    private static final Locale RUSSIAN = new Locale("ru");

    private RussianDates() {
    }

    /** «21:35». */
    @NonNull
    public static String time(long at) {
        return DateFormat.getTimeInstance(DateFormat.SHORT, RUSSIAN).format(new Date(at));
    }

    /** «02.11.2026». */
    @NonNull
    public static String date(long at) {
        return DateFormat.getDateInstance(DateFormat.SHORT, RUSSIAN).format(new Date(at));
    }

    /** «31 окт., 21:35». */
    @NonNull
    public static String dayAndTime(long at) {
        return new SimpleDateFormat("d MMM, HH:mm", RUSSIAN).format(new Date(at));
    }
}
