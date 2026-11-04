package com.fanok.audiobooks.util;

import androidx.annotation.NonNull;
import java.util.Locale;

/**
 * The description page's time line: how much of a book is left, from its chapter lengths and where it stands.
 * The chapter starts are summed once per chapter list, so each progress update is a few operations. Pure.
 */
public final class RemainingTime {

    /** Not started, or no chapter list: the book's whole length is shown. */
    public static final int TOTAL = 0;

    /** Started: {@link Line#left} real seconds at the speed. */
    public static final int LEFT = 1;

    /** Less than {@link #DONE_SEC} left: listened. */
    public static final int DONE = 2;

    /** Started, but a chapter's length is unknown: the chapter number instead of an invented remainder. */
    public static final int CHAPTER = 3;

    /** The last chapter's real end often misses its listed length by seconds: this much left counts as listened. */
    static final long DONE_SEC = 60;

    /** Less than this into the first chapter is not started yet. */
    static final long START_MS = 1_000;

    /** starts[i]: seconds before chapter i; starts[n]: the book's length. */
    private final long[] starts;

    /** Every chapter's length is known. */
    private final boolean known;

    /** @param seconds the lengths of the book's chapters, its whole list in order; 0 = unknown */
    public RemainingTime(@NonNull int[] seconds) {
        starts = new long[seconds.length + 1];
        boolean all = seconds.length > 0;
        for (int i = 0; i < seconds.length; i++) {
            all &= seconds[i] > 0;
            starts[i + 1] = starts[i] + Math.max(0, seconds[i]);
        }
        known = all;
    }

    /** The book's length by its chapters in seconds; 0 when a chapter's length is unknown. */
    public long total() {
        return known ? starts[starts.length - 1] : 0;
    }

    /** Where the book stands at {@code chapter} (its index; -1 = never played) and {@code positionMs} in it. */
    @NonNull
    public Line at(int chapter, long positionMs, float speed) {
        int n = starts.length - 1;
        if (n == 0 || chapter < 0 || (chapter == 0 && positionMs < START_MS)) {
            return new Line(TOTAL, 0, 0, 0, n);
        }
        if (chapter >= n) {
            //as Checkpoint.clampTo: past the end of a shorter list is the last chapter from its beginning
            chapter = n - 1;
            positionMs = 0;
        }
        if (!known) {
            return new Line(CHAPTER, 0, (int) (1000L * chapter / n), chapter + 1, n);
        }
        //in ms down to the whole second, as a launcher widget counts the time left: both show the same minute
        long behindMs = starts[chapter] * 1000 + Math.min(Math.max(0, positionMs),
                (starts[chapter + 1] - starts[chapter]) * 1000);
        long restMs = starts[n] * 1000 - behindMs;
        if (restMs < DONE_SEC * 1000) {
            return new Line(DONE, 0, 1000, chapter + 1, n);
        }
        double real = speed > 0 ? restMs / (double) speed : restMs;
        return new Line(LEFT, (long) (real / 1000), (int) (behindMs / starts[n]), chapter + 1, n);
    }

    /**
     * "5 ч. 12 мин.", "48 мин.", "2 ч.": whole minutes, as the sites write a book's length and launcher widgets the
     * time left in it, so the book screen and a widget show the same.
     */
    @NonNull
    public static String format(long seconds) {
        long minutes = Math.max(0, seconds) / 60;
        long hours = minutes / 60;
        minutes %= 60;
        if (hours > 0 && minutes > 0) {
            return hours + " ч. " + minutes + " мин.";
        }
        return hours > 0 ? hours + " ч." : minutes + " мин.";
    }

    /** "1,5×", "2×", "0,75×". */
    @NonNull
    public static String speed(float speed) {
        int hundredths = Math.round(speed * 100);
        int fraction = hundredths % 100;
        String text = String.valueOf(hundredths / 100);
        if (fraction != 0) {
            text += "," + (fraction % 10 == 0 ? String.valueOf(fraction / 10)
                    : String.format(Locale.ROOT, "%02d", fraction));
        }
        return text + "×";
    }

    /** One state of the line. */
    public static final class Line {

        /** {@link #TOTAL}, {@link #LEFT}, {@link #DONE} or {@link #CHAPTER}. */
        public final int state;

        /** LEFT: real seconds left at the speed. */
        public final long left;

        /** How much is behind, 0..1000: by time, or by chapters for CHAPTER. */
        public final int permille;

        /** The chapter's number from 1, and the chapter count. */
        public final int chapter;

        public final int chapters;

        Line(int state, long left, int permille, int chapter, int chapters) {
            this.state = state;
            this.left = left;
            this.permille = permille;
            this.chapter = chapter;
            this.chapters = chapters;
        }
    }
}
