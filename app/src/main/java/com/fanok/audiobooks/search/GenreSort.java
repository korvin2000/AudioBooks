package com.fanok.audiobooks.search;

import androidx.annotation.NonNull;
import com.fanok.audiobooks.R;
import com.fanok.audiobooks.pojo.GenrePOJO;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The local order of a loaded genre list, chosen in its "order" menu. */
public enum GenreSort {
    /** The site's order. */
    NONE(R.string.genre_sort_none),
    NAME(R.string.genre_sort_name),
    /** Most books first ({@link #count}); a genre without a count (0) goes last. */
    COUNT(R.string.genre_sort_count);

    public final int title;

    /** A description that is only a book count, as some sites give it: "123 книги", "[953]", "4 738". */
    private static final Pattern COUNT_ONLY = Pattern.compile("^\\D{0,3}(\\d[\\d \u00a0]{0,10})\\D{0,16}$");

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
            Collections.sort(list, (a, b) -> Integer.compare(count(b), count(a)));
        }
        return list;
    }

    /** The genre's book count: its rating, else a description that is only a count; 0 when there is none. */
    static int count(@NonNull GenrePOJO genre) {
        if (genre.getReting() > 0) {
            return genre.getReting();
        }
        Matcher m = genre.getDescription() == null ? null : COUNT_ONLY.matcher(genre.getDescription().trim());
        if (m == null || !m.matches()) {
            return 0;
        }
        String digits = m.group(1).replaceAll("\\D", "");
        return digits.length() > 9 ? 0 : Integer.parseInt(digits);
    }
}
