package com.fanok.audiobooks.widgets;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * What a launcher widget shows of the book: filled, then never changed while published, so binder threads read it
 * without locks. Positions are milliseconds in the chapter; {@link #sampledAtMs} is the elapsedRealtime at which
 * {@link #positionMs} was true.
 */
final class NowPlaying implements Cloneable {

    /** The launchers cut longer texts. */
    static final int TEXT_MAX = 300;

    String title = "", chapter = "", credits = "";

    int chapterIndex = -1, chapterCount;

    long positionMs, durationMs, sampledAtMs;

    boolean playing;

    float speed = 1f;

    /** The chapters before this one and the whole book, ms; bookMs 0: a chapter's length is unknown, -1: no book list. */
    long chapterStartMs, bookMs = -1;

    @Nullable
    byte[] cover;

    /** The address of the book's picture: pairs a kept cover with its book. */
    @Nullable
    String coverUri;

    /** The book's own address, kept to find the app's checkpoint of it; never sent. */
    @Nullable
    String bookUrl;

    /** Where the chapter is at {@code now}: it runs on at the speed while playing, up to a known end. */
    long positionAt(long now) {
        if (!playing) {
            return positionMs;
        }
        long at = positionMs + (long) ((now - sampledAtMs) * speed);
        return durationMs > 0 ? Math.min(at, durationMs) : at;
    }

    /** This one stopped at {@code now}: what a lost session or a cold start shows (a stopped one is not run on). */
    @NonNull
    NowPlaying paused(long now) {
        try {
            NowPlaying stopped = (NowPlaying) clone();
            stopped.positionMs = positionAt(now);
            stopped.sampledAtMs = now;
            stopped.playing = false;
            return stopped;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }

    /** The share of the whole book behind at {@code now}, 0..100: by time, by chapters while a length is unknown; -1. */
    int bookPercent(long now) {
        long at = positionAt(now);
        if (bookMs > 0) {
            return (int) Math.max(0, Math.min(100, (chapterStartMs + at) * 100 / bookMs));
        }
        if (bookMs < 0 || chapterIndex < 0 || chapterCount <= 0) {
            return -1;
        }
        long inChapter = durationMs > 0 ? Math.min(at, durationMs) * 100 / durationMs : 0;
        return (int) Math.min(100, (chapterIndex * 100L + inChapter) / chapterCount);
    }

    boolean approximate() {
        return bookMs == 0;
    }

    /** Places the chapter in the book of {@code seconds} (0 = unknown); a list other than the session's is no book. */
    void lengths(@Nullable int[] seconds) {
        bookMs = -1;
        if (seconds == null || seconds.length != chapterCount || chapterIndex < 0 || chapterIndex >= chapterCount) {
            return;
        }
        long total = 0;
        boolean known = true;
        for (int i = 0; i < seconds.length; i++) {
            if (i == chapterIndex) {
                chapterStartMs = total * 1000;
            }
            known &= seconds[i] > 0;
            total += Math.max(0, seconds[i]);
        }
        bookMs = known ? total * 1000 : 0;
    }

    /** "Author · Reader", either alone when the other is missing or the same. */
    @NonNull
    static String credits(@Nullable String author, @Nullable String reader) {
        String a = text(author), r = text(reader);
        return a.isEmpty() || a.equals(r) ? r : r.isEmpty() ? a : text(a + " · " + r);
    }

    /** As the session has it, cut as the launchers cut it. */
    @NonNull
    static String text(@Nullable String s) {
        return s == null ? "" : s.length() > TEXT_MAX ? s.substring(0, TEXT_MAX) : s;
    }

    /**
     * The book as GENESIS reads the session's album, Kotlin's trim (no-break spaces too): the snapshot's title must
     * equal it for the launcher to take both for one book.
     */
    @NonNull
    static String title(@Nullable String album) {
        if (album == null) {
            return "";
        }
        int from = 0, to = album.length();
        while (from < to && blank(album.charAt(from))) {
            from++;
        }
        while (to > from && blank(album.charAt(to - 1))) {
            to--;
        }
        return text(album.substring(from, to));
    }

    private static boolean blank(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }
}
