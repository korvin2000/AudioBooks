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

    private ListeningProgress() {
    }

    /**
     * The percent listened: the chapters before {@code lastChapter} plus {@code seconds} into it, out of all of them.
     * -1 (no ring) when there are no chapters or {@code lastChapter} is not among them (renamed or reloaded
     * chapters); 0 when nothing was listened to yet or the chapters have no length.
     */
    public static int percent(@NonNull List<AudioListPOJO> chapters, @Nullable String lastChapter, int seconds) {
        if (chapters.isEmpty()) {
            return -1;
        }
        if (lastChapter == null || lastChapter.isEmpty()) {
            return 0;
        }
        int current = 0;
        int total = 0;
        boolean found = false;
        for (AudioListPOJO chapter : chapters) {
            if (!found) {
                if (lastChapter.equals(chapter.getAudioName())) {
                    found = true;
                    current += seconds;
                } else {
                    current += chapter.getTime();
                }
            }
            total += chapter.getTime();
        }
        if (!found) {
            return -1;
        }
        return total != 0 ? current * 100 / total : 0;
    }

    /** The percent of the book {@code url} from its stored chapters and position; reads the database. */
    public static int of(@NonNull AudioListDBModel chapters, @NonNull AudioDBModel position, @NonNull String url) {
        List<AudioListPOJO> list = chapters.get(url);
        return list.isEmpty() ? -1 : percent(list, position.getName(url), position.getTime(url));
    }
}
