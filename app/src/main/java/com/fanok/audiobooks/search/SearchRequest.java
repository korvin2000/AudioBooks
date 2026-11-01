package com.fanok.audiobooks.search;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * What the listener asked for: the text as typed and the card fields it must be found in. An empty scope is
 * "anywhere": each word may lie in any field ("булгаков собачье сердце" → author and title). Otherwise every
 * significant word must lie in one of the chosen fields.
 */
public final class SearchRequest {

    /** In the order that settles ties between equally good matches. */
    public enum Field { TITLE, AUTHOR, SERIES, READER }

    public final String text;
    public final Set<Field> scope;

    public SearchRequest(String text, Set<Field> scope) {
        this.text = text == null ? "" : text.trim();
        this.scope = scope == null || scope.isEmpty() ? Collections.<Field>emptySet()
                : Collections.unmodifiableSet(EnumSet.copyOf(scope));
    }

    public static SearchRequest anywhere(String text) {
        return new SearchRequest(text, null);
    }

    public boolean isAnywhere() {
        return scope.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SearchRequest && text.equals(((SearchRequest) o).text)
                && scope.equals(((SearchRequest) o).scope);
    }

    @Override
    public int hashCode() {
        return 31 * text.hashCode() + scope.hashCode();
    }
}
