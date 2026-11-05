package com.fanok.audiobooks.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.fanok.audiobooks.pojo.AudioListPOJO;
import java.util.List;

/**
 * How much of a book was listened to: the ring on its card (BooksListAddapter) and the history's status
 * (HistoryFilter) come from this one calculation.
 */
public final class ListeningProgress {

    /**
     * Under this many seconds of the book left, its last percent counts as listened: the sites' chapter lengths and
     * the player's positions differ by seconds, so a book played to its end would otherwise stay at 99%.
     */
    static final int END_SLACK_SECONDS = 60;

    private ListeningProgress() {
    }

    /**
     * The percent listened: the chapters before {@code lastChapter} plus {@code seconds} into it, out of all of them.
     * A chapter of unknown length (0: the site gives none and the player has not played it yet) counts as long as
     * the known ones on average, and the chapter being listened to as at least {@code seconds}. -1 (no ring) when
     * there are no chapters or {@code lastChapter} is not among them (renamed or reloaded chapters); 0 when nothing
     * was listened to yet or no chapter's length is known; 100 when under {@link #END_SLACK_SECONDS} are left.
     */
    public static int percent(@NonNull List<AudioListPOJO> chapters, @Nullable String lastChapter, int seconds) {
        if (chapters.isEmpty()) {
            return -1;
        }
        if (lastChapter == null || lastChapter.isEmpty()) {
            return 0;
        }
        int known = 0;
        long knownTotal = 0;
        for (AudioListPOJO chapter : chapters) {
            if (chapter.getTime() > 0) {
                known++;
                knownTotal += chapter.getTime();
            }
        }
        long unknownLength = known > 0 ? knownTotal / known : 0;
        long current = 0;
        long total = 0;
        boolean found = false;
        for (AudioListPOJO chapter : chapters) {
            long length = chapter.getTime() > 0 ? chapter.getTime() : unknownLength;
            if (!found) {
                if (lastChapter.equals(chapter.getAudioName())) {
                    found = true;
                    length = Math.max(length, seconds);
                    current += seconds;
                } else {
                    current += length;
                }
            }
            total += length;
        }
        if (!found) {
            return -1;
        }
        if (known == 0 || total == 0) {
            return 0;
        }
        long left = total - current;
        if (seconds > 0 && left <= END_SLACK_SECONDS && left * 100 < total) {
            return 100;
        }
        return (int) (current * 100 / total);
    }

    /** The percent of the book {@code url} from its stored chapters and position; reads the database. */
    public static int of(@NonNull AudioListDBModel chapters, @NonNull AudioDBModel position, @NonNull String url) {
        List<AudioListPOJO> list = chapters.get(url);
        return list.isEmpty() ? -1 : percent(list, position.getName(url), position.getTime(url));
    }
}
