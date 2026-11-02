package com.fanok.audiobooks.search;

import com.fanok.audiobooks.search.SearchGrouper.Group;
import com.fanok.audiobooks.search.SearchGrouper.Narration;
import com.fanok.audiobooks.search.SearchGrouper.Offer;
import java.text.Collator;
import java.util.Comparator;
import java.util.Locale;

/**
 * The orders of the result list (search-refactoring.md §6.5). Every comparator is a chain of plain comparisons of fixed
 * keys ending in the URL, so it is transitive and the order is the same whatever the arrival; no fuzzy comparison here.
 * Names compare by the Russian collation, empty ones last; unknown lengths go last in both directions.
 */
public final class SearchOrder {

    /**
     * {@code ARRIVAL}: as the sites answered, the order of the search before ranking. {@code NEWEST}: a person's books
     * (PersonSearch), every site's newest first, then every site's second newest...: each card by its place in its
     * site's own sequence ({@link Offer#nativeRank}), a work by its best place; no clock across sites is claimed.
     */
    public enum Sort { RELEVANCE, ARRIVAL, TITLE, AUTHOR, READER, SHORTEST, LONGEST, SITE, NEWEST }

    private static final Collator RU = Collator.getInstance(new Locale("ru", "RU"));

    private SearchOrder() {
    }

    public static Comparator<Group> groups(final Sort sort) {
        return groups(sort, null);
    }

    /**
     * {@code author}: the word of a request that names an author ({@link SearchUi#authorWord}), else null. By
     * relevance such a request puts first the works titled just so ("Кинг"), then the works whose author has the word
     * itself, by tier and the number of sites that carry them (the surname says nothing more between them), then the
     * rest; every other order is as without it.
     */
    public static Comparator<Group> groups(final Sort sort, final String author) {
        return new Comparator<Group>() {
            @Override
            public int compare(Group a, Group b) {
                int c;
                switch (sort) {
                    case TITLE:
                        c = byTitle(a.representative, b.representative);
                        break;
                    case AUTHOR:
                        c = byName(a.representative.book.getAutor(), b.representative.book.getAutor());
                        c = c != 0 ? c : byTitle(a.representative, b.representative);
                        break;
                    case READER:
                        c = byName(firstReader(a), firstReader(b));
                        c = c != 0 ? c : byTitle(a.representative, b.representative);
                        break;
                    case SHORTEST:
                        c = byLength(minutes(a, false), minutes(b, false), false);
                        break;
                    case LONGEST:
                        c = byLength(minutes(a, true), minutes(b, true), true);
                        break;
                    case ARRIVAL:
                        c = Integer.compare(firstArrival(a), firstArrival(b));
                        break;
                    case NEWEST:
                        c = Integer.compare(firstRank(a), firstRank(b));
                        c = c != 0 ? c : Integer.compare(b.catalogs(), a.catalogs());
                        break;
                    default:
                        c = byRelevance(a, b, author);
                        break;
                }
                return c != 0 ? c : a.id.compareTo(b.id);
            }
        };
    }

    /** Cards on their own: a site's tab, or the "cards" view of "All". */
    public static Comparator<Offer> offers(final Sort sort) {
        return offers(sort, null, false);
    }

    /**
     * As {@link #groups(Sort, String)} for cards: on a site's tab ({@code siteTab}) the author's cards keep the site's
     * own order inside their tier.
     */
    public static Comparator<Offer> offers(final Sort sort, final String author, final boolean siteTab) {
        return new Comparator<Offer>() {
            @Override
            public int compare(Offer a, Offer b) {
                int c;
                switch (sort) {
                    case TITLE:
                        c = byTitle(a, b);
                        break;
                    case AUTHOR:
                        c = byName(a.book.getAutor(), b.book.getAutor());
                        c = c != 0 ? c : byTitle(a, b);
                        break;
                    case READER:
                        c = byName(a.book.getArtist(), b.book.getArtist());
                        c = c != 0 ? c : byTitle(a, b);
                        break;
                    case SHORTEST:
                        c = byLength(a.minutes, b.minutes, false);
                        break;
                    case LONGEST:
                        c = byLength(a.minutes, b.minutes, true);
                        break;
                    case SITE:
                        c = Integer.compare(a.order, b.order);
                        c = c != 0 ? c : Integer.compare(a.nativeRank, b.nativeRank);
                        break;
                    case ARRIVAL:
                        c = Integer.compare(a.arrival, b.arrival);
                        break;
                    case NEWEST:
                        c = Integer.compare(a.nativeRank, b.nativeRank);
                        c = c != 0 ? c : Integer.compare(a.order, b.order);
                        break;
                    default:
                        c = author == null ? 0 : Integer.compare(bucket(a, author), bucket(b, author));
                        if (c == 0 && author != null && siteTab && bucket(a, author) == 1) {
                            c = a.match.tier.compareTo(b.match.tier);
                            c = c != 0 ? c : Integer.compare(a.nativeRank, b.nativeRank);
                        }
                        c = c != 0 ? c : byRelevance(a, b);
                        break;
                }
                return c != 0 ? c : a.url().compareTo(b.url());
            }
        };
    }

    /**
     * Groups by relevance: with an author's word ({@code author}) its buckets first ({@link #bucket(Group, String)}),
     * the author's works by tier and the sites that carry them; then tier, score, the sites that carry the work (among
     * equal matches the one more sites have), and the representatives' own order.
     */
    private static int byRelevance(Group a, Group b, String author) {
        int c = 0;
        if (author != null) {
            int bucket = bucket(a, author);
            c = Integer.compare(bucket, bucket(b, author));
            if (c == 0 && bucket == 1) {
                c = a.tier.compareTo(b.tier);
                c = c != 0 ? c : Integer.compare(b.catalogs(), a.catalogs());
            }
        }
        c = c != 0 ? c : a.tier.compareTo(b.tier);
        c = c != 0 ? c : Integer.compare(b.score, a.score);
        c = c != 0 ? c : Integer.compare(b.catalogs(), a.catalogs());
        return c != 0 ? c : byRelevance(a.representative, b.representative);
    }

    /** 0: a work titled just as asked; 1: a work whose author has the word itself; 2: any other. */
    private static int bucket(Group g, String author) {
        int best = 2;
        for (Offer o : g.offers) {
            best = Math.min(best, bucket(o, author));
        }
        return best;
    }

    private static int bucket(Offer o, String author) {
        return o.titleIs(author) ? 0 : o.authorHas(author) ? 1 : 2;
    }

    /** Tier, score, the site's own rank, the listener's order of sources, the title. */
    private static int byRelevance(Offer a, Offer b) {
        int c = a.match.tier.compareTo(b.match.tier);
        if (c == 0) {
            c = Integer.compare(b.match.score, a.match.score);
        }
        if (c == 0) {
            c = Integer.compare(a.nativeRank, b.nativeRank);
        }
        if (c == 0) {
            c = Integer.compare(a.order, b.order);
        }
        return c != 0 ? c : byTitle(a, b);
    }

    private static int byTitle(Offer a, Offer b) {
        int c = byName(a.book.getName(), b.book.getName());
        return c != 0 ? c : byName(a.book.getAutor(), b.book.getAutor());
    }

    /** Russian collation of the search form; an empty name goes after every other. */
    static int byName(String a, String b) {
        String x = TextNorm.key(a);
        String y = TextNorm.key(b);
        if (x.isEmpty() || y.isEmpty()) {
            return Boolean.compare(x.isEmpty(), y.isEmpty());
        }
        synchronized (RU) {
            return RU.compare(x, y);
        }
    }

    private static int byLength(int a, int b, boolean longestFirst) {
        if (a <= 0 || b <= 0) {
            return Boolean.compare(a <= 0, b <= 0);
        }
        return longestFirst ? Integer.compare(b, a) : Integer.compare(a, b);
    }

    /** The first reader of the group by the alphabet: the one the "by reader" order files it under. */
    public static String firstReader(Group g) {
        String first = null;
        for (Offer o : g.offers) {
            String r = o.book.getArtist();
            if (!TextNorm.key(r).isEmpty() && (first == null || byName(r, first) < 0)) {
                first = r;
            }
        }
        return first;
    }

    /** When the group's first card arrived. */
    static int firstArrival(Group g) {
        int first = Integer.MAX_VALUE;
        for (Offer o : g.offers) {
            first = Math.min(first, o.arrival);
        }
        return first;
    }

    /** The best place of the group's cards in their sites' own sequences. */
    static int firstRank(Group g) {
        int first = Integer.MAX_VALUE;
        for (Offer o : g.offers) {
            first = Math.min(first, o.nativeRank);
        }
        return first;
    }

    /** The shortest (or longest) known recording of the group; -1 when no length is known. */
    static int minutes(Group g, boolean longest) {
        int best = -1;
        for (Narration n : g.narrations) {
            for (Offer o : n.offers) {
                if (o.minutes > 0 && (best < 0 || (longest ? o.minutes > best : o.minutes < best))) {
                    best = o.minutes;
                }
            }
        }
        return best;
    }
}
