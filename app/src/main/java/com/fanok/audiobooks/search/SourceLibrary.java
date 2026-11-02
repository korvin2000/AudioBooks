package com.fanok.audiobooks.search;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The user's own books by the site they come from ({@link Provider#ofUrl}): the counts of a source card's
 * "В вашей библиотеке", and the sites the favorites, the history and the downloads can be filtered by.
 */
public final class SourceLibrary {

    /** The places of a site's counts in {@link #count}. */
    public static final int FAVORITES = 0;
    public static final int HISTORY = 1;
    public static final int SAVED = 2;

    private SourceLibrary() {
    }

    /**
     * Each site's books in the favorites, the history and the downloads, at {@link #FAVORITES}, {@link #HISTORY}
     * and {@link #SAVED}; a site with none is absent, and so are the books of an unknown host.
     */
    @NonNull
    public static Map<Provider, int[]> count(@NonNull Iterable<String> favorites, @NonNull Iterable<String> history,
            @NonNull Iterable<String> saved) {
        Map<Provider, int[]> counts = new EnumMap<>(Provider.class);
        add(counts, favorites, FAVORITES);
        add(counts, history, HISTORY);
        add(counts, saved, SAVED);
        return counts;
    }

    private static void add(@NonNull Map<Provider, int[]> counts, @NonNull Iterable<String> urls, int list) {
        for (String url : urls) {
            Provider provider = Provider.ofUrl(url);
            if (provider == null) {
                continue;
            }
            int[] count = counts.get(provider);
            if (count == null) {
                count = new int[3];
                counts.put(provider, count);
            }
            count[list]++;
        }
    }

    /** The sites of {@code urls} with their number of books: most books first, a tie in the app's order. */
    @NonNull
    public static Map<Provider, Integer> sites(@NonNull Iterable<String> urls) {
        Map<Provider, Integer> counts = new EnumMap<>(Provider.class);
        for (String url : urls) {
            Provider provider = Provider.ofUrl(url);
            if (provider != null) {
                Integer count = counts.get(provider);
                counts.put(provider, count == null ? 1 : count + 1);
            }
        }
        List<Provider> order = new ArrayList<>(counts.keySet());
        // a stable sort: the enum map gave the app's order
        order.sort((a, b) -> Integer.compare(counts.get(b), counts.get(a)));
        Map<Provider, Integer> sites = new LinkedHashMap<>();
        for (Provider provider : order) {
            sites.put(provider, counts.get(provider));
        }
        return sites;
    }

    /** The items whose {@code url} is on {@code provider}'s site, in their order. */
    @NonNull
    public static <T> ArrayList<T> only(@NonNull List<T> items, @NonNull Function<T, String> url,
            @NonNull Provider provider) {
        ArrayList<T> only = new ArrayList<>();
        for (T item : items) {
            if (Provider.ofUrl(url.apply(item)) == provider) {
                only.add(item);
            }
        }
        return only;
    }

    /**
     * The items of {@code all} that are among {@code members} (the same objects), in the order of {@code all}: a
     * filtered list made again from the whole one after a book came back or the whole list was sorted.
     */
    @NonNull
    public static <T> ArrayList<T> among(@NonNull List<T> all, @NonNull Collection<T> members) {
        Set<T> wanted = Collections.newSetFromMap(new IdentityHashMap<>());
        wanted.addAll(members);
        ArrayList<T> among = new ArrayList<>();
        for (T item : all) {
            if (wanted.contains(item)) {
                among.add(item);
            }
        }
        return among;
    }
}
