package com.fanok.audiobooks.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The description page's header "Автор — Название". Pure. */
public final class BookHeader {

    /** What may stand between an author and the title in a site's title: "Роулинг Джоан - Гарри Поттер". */
    private static final Pattern SEPARATOR = Pattern.compile("[\\s\\-\\u2013\\u2014:.,]+");

    private BookHeader() {
    }

    /** The title without a leading {@code author} and separator, so the header does not name the author twice. */
    @NonNull
    public static String title(@Nullable String author, @Nullable String title) {
        String text = title == null ? "" : title.trim();
        String name = author == null ? "" : author.trim();
        if (name.isEmpty() || text.length() <= name.length() || !text.regionMatches(true, 0, name, 0, name.length())) {
            return text;
        }
        Matcher separator = SEPARATOR.matcher(text).region(name.length(), text.length());
        if (!separator.lookingAt() || separator.end() == text.length()) {
            return text; //a longer word ("Кинг" in "Кингсли ...") or nothing after it
        }
        return text.substring(separator.end());
    }
}
