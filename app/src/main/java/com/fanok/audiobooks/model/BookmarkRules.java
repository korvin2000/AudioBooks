package com.fanok.audiobooks.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.fanok.audiobooks.room.BookmarkEntity;
import java.util.List;

/**
 * The rules of a book's places marked by the listener (room.BookmarkEntity), apart from the screen and the database:
 * the mark of a place, one mark per place, and finding the marked chapter again in a list that is the downloaded
 * chapters only, or that the site changed. Not the catalog's marks on books (adapter.BookMarks).
 */
public final class BookmarkRules {

    /** Longer notes are cut: a mark is a pointer, not a document. */
    public static final int MAX_NOTE = 500;

    /** Marks of one chapter this close are one place: a second tap while it plays makes no second mark. */
    static final long SAME_PLACE_MS = 3000;

    private BookmarkRules() {
    }

    /**
     * The mark of {@code positionMs} into the chapter of index {@code ordinal} in the book's full list, named
     * {@code chapterName}.
     */
    @NonNull
    public static BookmarkEntity create(@NonNull String bookUrl, int ordinal, @Nullable String chapterName,
            long positionMs, long now) {
        BookmarkEntity mark = new BookmarkEntity();
        mark.urlBook = bookUrl;
        mark.chapterIndex = Math.max(0, ordinal);
        mark.chapterName = chapterName == null ? "" : chapterName;
        mark.positionMs = Math.max(0, positionMs);
        mark.createdAt = now;
        return mark;
    }

    /** The note trimmed and cut to {@link #MAX_NOTE}; null when nothing is left. */
    @Nullable
    public static String cleanNote(@Nullable String note) {
        if (note == null) {
            return null;
        }
        String trimmed = note.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > MAX_NOTE ? trimmed.substring(0, MAX_NOTE) : trimmed;
    }

    /** The mark among {@code existing} at the place of {@code mark} (same chapter, a few seconds apart), or null. */
    @Nullable
    public static BookmarkEntity findSame(@NonNull List<BookmarkEntity> existing, @NonNull BookmarkEntity mark) {
        for (BookmarkEntity other : existing) {
            if (other.urlBook.equals(mark.urlBook) && other.chapterIndex == mark.chapterIndex
                    && other.chapterName.equals(mark.chapterName)
                    && Math.abs(other.positionMs - mark.positionMs) < SAME_PLACE_MS) {
                return other;
            }
        }
        return null;
    }

    /**
     * Where the marked chapter is in {@code names}, a list whose chapter {@code i} has index {@code ordinals[i]} in the
     * book's full list ({@code ordinals} null: it is the full list): the chapter at the marked index when it still
     * has the marked name; else, in the full list only, the one chapter of that name; else -1. Never by the index
     * alone (after the site changed its list it is another chapter), nor by a name several chapters share: a wrong
     * jump is worse than none. A chapter that is not downloaded is not in an offline list, so it is -1 there: the one
     * downloaded chapter of its name may be another of the same name.
     */
    public static int resolve(@NonNull List<String> names, @Nullable int[] ordinals, int ordinal,
            @NonNull String chapterName) {
        int found = -1;
        for (int i = 0; i < names.size(); i++) {
            if (!chapterName.equals(names.get(i))) {
                continue;
            }
            if (ordinalOf(ordinals, i) == ordinal) {
                return i;
            }
            if (found >= 0) {
                found = -2; // a name of several chapters: only the marked index tells them apart
            } else if (found == -1) {
                found = i;
            }
        }
        return chapterName.isEmpty() || ordinals != null || found < 0 ? -1 : found;
    }

    private static int ordinalOf(@Nullable int[] ordinals, int index) {
        return ordinals != null && index < ordinals.length ? ordinals[index] : index;
    }
}
