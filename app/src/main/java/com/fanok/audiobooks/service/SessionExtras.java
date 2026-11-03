package com.fanok.audiobooks.service;

import androidx.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Keys the media session carries beyond the standard ones, for controllers that read them (a launcher's audiobook
 * widget): the chapter and a stable book id in the metadata, the real speed in the playback state, whose own speed is
 * 0 on pause, the chapter lengths in the queue. Other controllers ignore keys they do not know.
 */
public final class SessionExtras {

    /** Long, metadata: the current chapter from 0, the same as the state's active queue item id. */
    public static final String CHAPTER_INDEX = "chapterIndex";
    /** String, metadata: {@link #bookKey(String)}. */
    public static final String BOOK_KEY = "bookKey";
    /** Float, playback state: the speed, also while paused; Media3 sessions put it under the same key. */
    public static final String SPEED = "EXO_SPEED";
    /** Long, queue item: {@link #chapterDurationMs(int)}, only when known. */
    public static final String CHAPTER_DURATION_MS = "chapterDurationMs";

    private SessionExtras() {
    }

    /** A chapter's length in seconds, as the chapter list keeps it, in ms; 0 when unknown. */
    public static long chapterDurationMs(int seconds) {
        return seconds > 0 ? seconds * 1000L : 0;
    }

    /** The book's URL as a name-based UUID: the same for the book, different between books, without the URL itself. */
    @Nullable
    public static String bookKey(@Nullable String bookUrl) {
        return bookUrl == null || bookUrl.isEmpty() ? null
                : UUID.nameUUIDFromBytes(bookUrl.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
