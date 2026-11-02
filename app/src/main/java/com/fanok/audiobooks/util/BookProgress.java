package com.fanok.audiobooks.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the open books stand, for the description page's "time left": a book screen's chapter lengths and, while
 * that book plays, its chapter and offset. Fed by BookActivity from data and broadcasts it already receives;
 * nothing is stored or polled. Main thread only.
 */
public final class BookProgress {

    public interface Listener {

        /** The chapters or the position of {@code bookUrl} changed. */
        void onBookProgress(@NonNull String bookUrl);
    }

    /** Book screens open at once: one per book in the back stack, a few at most. */
    private static final int KEEP = 4;

    private static final Map<String, Book> BOOKS = new LinkedHashMap<String, Book>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Book> eldest) {
            return size() > KEEP;
        }
    };

    private static final List<Listener> LISTENERS = new ArrayList<>();

    private static final class Book {

        @Nullable
        int[] seconds;

        int chapter = -1;

        long positionMs;
    }

    private BookProgress() {
    }

    /** The lengths of the book's chapters in seconds, its whole chapter list in order; 0 = unknown. */
    public static void chapters(@NonNull String bookUrl, @NonNull int[] seconds) {
        Book book = book(bookUrl);
        if (!Arrays.equals(book.seconds, seconds)) {
            book.seconds = seconds.clone();
            changed(bookUrl);
        }
    }

    /** Where the book plays: the chapter by its index in the whole chapter list, and the offset in it. */
    public static void position(@NonNull String bookUrl, int chapterIndex, long positionMs) {
        Book book = book(bookUrl);
        if (book.chapter != chapterIndex || book.positionMs != positionMs) {
            book.chapter = chapterIndex;
            book.positionMs = positionMs;
            changed(bookUrl);
        }
    }

    /** The chapter lengths given by {@link #chapters}, or null; not to be modified. */
    @Nullable
    public static int[] seconds(@NonNull String bookUrl) {
        Book book = BOOKS.get(bookUrl);
        return book == null ? null : book.seconds;
    }

    /** The playing chapter's index in the whole list; -1 until the book played with its screen open. */
    public static int chapter(@NonNull String bookUrl) {
        Book book = BOOKS.get(bookUrl);
        return book == null ? -1 : book.chapter;
    }

    public static long positionMs(@NonNull String bookUrl) {
        Book book = BOOKS.get(bookUrl);
        return book == null ? 0 : book.positionMs;
    }

    /** Something else the shown time depends on changed (the speed): the listeners of {@code bookUrl} recompute. */
    public static void refresh(@NonNull String bookUrl) {
        changed(bookUrl);
    }

    public static void addListener(@NonNull Listener listener) {
        if (!LISTENERS.contains(listener)) {
            LISTENERS.add(listener);
        }
    }

    public static void removeListener(@NonNull Listener listener) {
        LISTENERS.remove(listener);
    }

    private static Book book(String bookUrl) {
        Book book = BOOKS.get(bookUrl);
        if (book == null) {
            book = new Book();
            BOOKS.put(bookUrl, book);
        }
        return book;
    }

    private static void changed(String bookUrl) {
        //backwards: a listener may remove itself
        for (int i = LISTENERS.size() - 1; i >= 0; i--) {
            if (i < LISTENERS.size()) {
                LISTENERS.get(i).onBookProgress(bookUrl);
            }
        }
    }
}
