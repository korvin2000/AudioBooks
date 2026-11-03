package com.fanok.audiobooks.service;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Map;

/**
 * Where a book was left: the chapter by its position in the book's chapter list, and the offset in it.
 * Immutable, so a snapshot taken on the player thread can be written later without reading service fields.
 * Resume reads only this record; the legacy chapter-title and seconds fields are written for compatibility
 * and never read back for resume.
 */
public final class Checkpoint {

    /** Key of the most recently written checkpoint, whatever its book. */
    public static final String KEY_LAST = "checkpoint_last";

    private static final String KEY_PREFIX = "checkpoint:";

    private static final String VERSION = "2";

    /** The format before {@link #at}: {@code 1|chapter|ms|url}, read with the time unknown. */
    private static final String VERSION_1 = "1";

    @NonNull
    public final String bookUrl;

    public final int chapterIndex;

    public final long positionMs;

    /** When the place was reached (epoch ms, 0 unknown): of two places of a book, a cloud sync keeps the later. */
    public final long at;

    /** The place, reached now. */
    public Checkpoint(@NonNull String bookUrl, int chapterIndex, long positionMs) {
        this(bookUrl, chapterIndex, positionMs, System.currentTimeMillis());
    }

    public Checkpoint(@NonNull String bookUrl, int chapterIndex, long positionMs, long at) {
        this.bookUrl = bookUrl;
        this.chapterIndex = Math.max(0, chapterIndex);
        this.positionMs = Math.max(0, positionMs);
        this.at = Math.max(0, at);
    }

    /** Preference key of the checkpoint of {@code bookUrl}. */
    @NonNull
    public static String key(@NonNull String bookUrl) {
        return KEY_PREFIX + bookUrl;
    }

    @NonNull
    public String serialize() {
        return VERSION + "|" + chapterIndex + "|" + positionMs + "|" + at + "|" + bookUrl;
    }

    /** @return the checkpoint, or null when {@code raw} is absent or not a valid record. */
    @Nullable
    public static Checkpoint parse(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        boolean current = raw.startsWith(VERSION + "|");
        int fields = current ? 5 : 4; // the URL is the rest, whatever it holds
        String[] parts = raw.split("\\|", fields);
        if (parts.length != fields || !current && !VERSION_1.equals(parts[0]) || parts[fields - 1].isEmpty()) {
            return null;
        }
        try {
            int index = Integer.parseInt(parts[1]);
            long position = Long.parseLong(parts[2]);
            long at = current ? Long.parseLong(parts[3]) : 0;
            if (index < 0 || position < 0 || at < 0) {
                return null;
            }
            return new Checkpoint(parts[fields - 1], index, position, at);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The checkpoint of {@code bookUrl} among stored preference values; any other key is ignored. */
    @Nullable
    public static Checkpoint read(@NonNull Map<String, ?> values, @NonNull String bookUrl) {
        Object raw = values.get(key(bookUrl));
        Checkpoint checkpoint = raw instanceof String ? parse((String) raw) : null;
        return checkpoint != null && checkpoint.bookUrl.equals(bookUrl) ? checkpoint : null;
    }

    /**
     * This checkpoint for a book of {@code chapterCount} chapters: an index past the end selects the last
     * chapter and, since that is not the chapter the offset was measured in, starts it from the beginning.
     */
    @NonNull
    public Checkpoint clampTo(int chapterCount) {
        if (chapterCount <= 0) {
            return new Checkpoint(bookUrl, 0, 0, at);
        }
        if (chapterIndex < chapterCount) {
            return this;
        }
        return new Checkpoint(bookUrl, chapterCount - 1, 0, at);
    }

    /** The same place of the same book; {@link #at} is no part of it. */
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Checkpoint)) {
            return false;
        }
        Checkpoint other = (Checkpoint) o;
        return chapterIndex == other.chapterIndex && positionMs == other.positionMs
                && bookUrl.equals(other.bookUrl);
    }

    @Override
    public int hashCode() {
        return (bookUrl.hashCode() * 31 + chapterIndex) * 31 + (int) (positionMs ^ (positionMs >>> 32));
    }

    @NonNull
    @Override
    public String toString() {
        return serialize();
    }
}
