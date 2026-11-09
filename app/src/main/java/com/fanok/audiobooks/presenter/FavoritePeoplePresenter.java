package com.fanok.audiobooks.presenter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.fanok.audiobooks.model.FavoritePeople;
import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.search.Provider;
import com.fanok.audiobooks.search.TextNorm;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The list of Избранное → Авторы / Чтецы: each favourite person with what the library knows of them (their books in
 * the favourites, their sites and genres), and the list's search, filter and sort. Plain logic: the fragment loads the
 * rows off the main thread and shows {@link #shown()}.
 */
public final class FavoritePeoplePresenter {
    public static final int SORT_DATE = 0;
    public static final int SORT_NAME = 1;
    public static final int SORT_BOOKS = 2;

    public static final int FILTER_NONE = 0;
    /** People with a page or a book on one site ({@link Provider#name()}). */
    public static final int FILTER_SITE = 1;
    /** People with books in the favourites. */
    public static final int FILTER_BOOKS = 2;
    /** People with a book of one genre in the favourites or the history. */
    public static final int FILTER_GENRE = 3;

    /** One favourite person as listed. */
    public static final class Row {
        public final FavoritePeople.Person person;
        /** Their books in the favourites. */
        public final int books;
        /** The sites of their pages first, then of their books in the favourites and the history; each once. */
        public final List<Provider> sites;
        /** The genres of their books in the favourites and the history. */
        public final Set<String> genres;
        /** The place in the newest-first list (the date order). */
        final int order;
        /** A reader's narrator card on fantlab.ru (search.Narrators), set by the screen as it loads; null for none. */
        @Nullable
        public String card;

        Row(FavoritePeople.Person person, int books, List<Provider> sites, Set<String> genres, int order) {
            this.person = person;
            this.books = books;
            this.sites = sites;
            this.genres = genres;
            this.order = order;
        }
    }

    private List<Row> mRows = new ArrayList<>();
    private String mQuery = "";
    private int mSort = SORT_DATE;
    private int mFilter = FILTER_NONE;
    @Nullable
    private String mFilterValue;

    /**
     * The rows of {@code people} (newest first) of {@code kind}: their books are found by
     * {@link FavoritePeople#key} of each name of the author (or reader) field. Blocking-free, but call off the main
     * thread for a long history.
     */
    @NonNull
    public static ArrayList<Row> rows(int kind, @NonNull List<FavoritePeople.Person> people,
            @NonNull List<BookPOJO> favorites, @NonNull List<BookPOJO> history) {
        Map<String, int[]> books = new HashMap<>();
        Map<String, Set<Provider>> sites = new HashMap<>();
        Map<String, Set<String>> genres = new HashMap<>();
        for (FavoritePeople.Person p : people) {
            if (p.key != null) {
                books.put(p.key, new int[1]);
                Set<Provider> own = new LinkedHashSet<>();
                for (String url : p.urls) {
                    Provider provider = Provider.ofUrl(url);
                    if (provider != null) {
                        own.add(provider);
                    }
                }
                sites.put(p.key, own);
                genres.put(p.key, new TreeSet<>());
            }
        }
        collect(kind, favorites, books, sites, genres);
        collect(kind, history, null, sites, genres);
        ArrayList<Row> out = new ArrayList<>();
        for (FavoritePeople.Person p : people) {
            int[] count = p.key == null ? null : books.get(p.key);
            out.add(new Row(p, count == null ? 0 : count[0],
                    count == null ? Collections.<Provider>emptyList() : new ArrayList<Provider>(sites.get(p.key)),
                    count == null ? Collections.<String>emptySet() : genres.get(p.key), out.size()));
        }
        return out;
    }

    /** Adds what {@code list} says of the listed people; {@code books} (the favourites only) counts their books. */
    private static void collect(int kind, @NonNull List<BookPOJO> list, @Nullable Map<String, int[]> books,
            Map<String, Set<Provider>> sites, Map<String, Set<String>> genres) {
        for (BookPOJO book : list) {
            String field = kind == FavoritePeople.READER ? book.getArtist() : book.getAutor();
            for (String name : FavoritePeople.split(field)) {
                String key = FavoritePeople.key(name);
                Set<Provider> own = sites.get(key);
                if (own == null) {
                    continue; // not a favourite
                }
                if (books != null) {
                    books.get(key)[0]++;
                }
                Provider provider = Provider.ofUrl(book.getUrl());
                if (provider != null) {
                    own.add(provider);
                }
                String genre = book.getGenre() == null ? "" : book.getGenre().trim();
                if (!genre.isEmpty()) {
                    genres.get(key).add(genre);
                }
            }
        }
    }

    public void setRows(@NonNull List<Row> rows) {
        mRows = rows;
    }

    @NonNull
    public List<Row> rows() {
        return mRows;
    }

    /** Drops {@code row} at once (a swipe); the next load agrees. */
    public void remove(@NonNull Row row) {
        List<Row> rows = new ArrayList<>(mRows);
        rows.remove(row);
        mRows = rows;
    }

    public void setQuery(@Nullable String query) {
        mQuery = query == null ? "" : query;
    }

    public void setSort(int sort) {
        mSort = sort == SORT_NAME || sort == SORT_BOOKS ? sort : SORT_DATE;
    }

    public int sort() {
        return mSort;
    }

    /** {@code value}: the site's {@link Provider#name()} or the genre; FILTER_NONE clears it. */
    public void setFilter(int filter, @Nullable String value) {
        mFilter = filter;
        mFilterValue = filter == FILTER_SITE || filter == FILTER_GENRE ? value : null;
    }

    public int filter() {
        return mFilter;
    }

    @Nullable
    public String filterValue() {
        return mFilterValue;
    }

    /** The rows that pass the search and the filter, in the chosen order. */
    @NonNull
    public ArrayList<Row> shown() {
        String query = TextNorm.key(mQuery);
        Provider site = mFilter == FILTER_SITE ? provider(mFilterValue) : null;
        ArrayList<Row> out = new ArrayList<>();
        for (Row row : mRows) {
            if (!query.isEmpty() && !TextNorm.key(row.person.name).contains(query)) {
                continue;
            }
            if (mFilter == FILTER_SITE && !row.sites.contains(site)
                    || mFilter == FILTER_BOOKS && row.books == 0
                    || mFilter == FILTER_GENRE && !row.genres.contains(mFilterValue)) {
                continue;
            }
            out.add(row);
        }
        if (mSort == SORT_NAME) {
            Collator collator = Collator.getInstance(new Locale("ru"));
            out.sort((a, b) -> collator.compare(a.person.name, b.person.name));
        } else if (mSort == SORT_BOOKS) {
            out.sort((a, b) -> a.books != b.books ? Integer.compare(b.books, a.books)
                    : Integer.compare(a.order, b.order));
        } else {
            out.sort((a, b) -> Integer.compare(a.order, b.order));
        }
        return out;
    }

    /** The sites of all rows, each with the number of people there, most first. */
    @NonNull
    public LinkedHashMap<Provider, Integer> sites() {
        Map<Provider, Integer> counts = new HashMap<>();
        for (Row row : mRows) {
            for (Provider p : row.sites) {
                Integer n = counts.get(p);
                counts.put(p, n == null ? 1 : n + 1);
            }
        }
        List<Map.Entry<Provider, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((a, b) -> !a.getValue().equals(b.getValue()) ? Integer.compare(b.getValue(), a.getValue())
                : a.getKey().title.compareTo(b.getKey().title));
        LinkedHashMap<Provider, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<Provider, Integer> e : entries) {
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /** The genres of all rows, in order. */
    @NonNull
    public ArrayList<String> genres() {
        TreeSet<String> out = new TreeSet<>(Collator.getInstance(new Locale("ru")));
        for (Row row : mRows) {
            out.addAll(row.genres);
        }
        return new ArrayList<>(out);
    }

    @Nullable
    public static Provider provider(@Nullable String name) {
        for (Provider p : Provider.values()) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        return null;
    }
}
