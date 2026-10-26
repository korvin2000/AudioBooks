package com.fanok.audiobooks.search;

import androidx.annotation.NonNull;
import com.fanok.audiobooks.R;
import com.fanok.audiobooks.pojo.GenrePOJO;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** The local order of a loaded genre list, chosen in its "order" menu. */
public enum GenreSort {
    /** The site's order. */
    NONE(R.string.genre_sort_none),
    NAME(R.string.genre_sort_name),
    /** Most books first; a genre without a count (0) goes last. */
    COUNT(R.string.genre_sort_count);

    public final int title;

    GenreSort(int title) {
        this.title = title;
    }

    /** The choice kept under {@link #name()}, or {@link #NONE}. */
    @NonNull
    public static GenreSort of(String key) {
        for (GenreSort sort : values()) {
            if (sort.name().equals(key)) {
                return sort;
            }
        }
        return NONE;
    }

    /** A sorted copy: {@code genres} itself is left as it is, and equal ones keep the site's order. */
    @NonNull
    public ArrayList<GenrePOJO> sorted(@NonNull List<GenrePOJO> genres) {
        ArrayList<GenrePOJO> list = new ArrayList<>(genres);
        if (this == NAME) {
            Collator collator = Collator.getInstance(new Locale("ru"));
            Collections.sort(list, (a, b) -> collator.compare(a.getName(), b.getName()));
        } else if (this == COUNT) {
            Collections.sort(list, (a, b) -> Integer.compare(b.getReting(), a.getReting()));
        }
        return list;
    }
}
