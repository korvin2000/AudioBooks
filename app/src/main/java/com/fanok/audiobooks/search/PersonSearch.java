package com.fanok.audiobooks.search;

import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.pojo.GenrePOJO;
import io.reactivex.Single;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The books of one person, a favourite author or reader, on every site: one {@link Source} per site, its way decided
 * up front ({@link #mode}).
 * <ul>
 * <li>{@link Mode#LISTING}: the person's page on the site is known; its listing, page by page, as the catalog loads
 * it. The site's own order of the person's books ("newest first" as far as the site tells). A saved page whose first
 * page lists nothing (moved, or wrong) gives way, once, to the way the site would have without it.</li>
 * <li>{@link Mode#RESOLVE}: the site has a people search; page 1 looks the person up and takes the one hit of the very
 * same name ({@link #resolve}: the same words and initials, the identity {@code FavoritePeople.key} stands for), then
 * lists it. No such hit, or several (namesakes are never guessed): the book search for an author, nothing for a
 * reader. A failed lookup (timeout, block, rate limit) fails the page: a retry looks again.</li>
 * <li>{@link Mode#SEARCH}: the site's book search for the name, keeping the cards one of whose people of the kind is
 * the person ({@link #matches}); a page filtered to nothing goes on to the next ones, a few. A reader only on the sites whose book search finds readers ({@link #READER_SEARCH});
 * the other sites without a page or a people search are not asked.</li>
 * </ul>
 * Pure but for {@link Net}, so the planner, the matching and the sources are JVM-tested.
 */
public final class PersonSearch {

    public enum Mode { LISTING, RESOLVE, SEARCH }

    /** The sites whose book search finds a reader by name (Golosom.java: title, author, reader, series). */
    public static final Set<Provider> READER_SEARCH = Collections.unmodifiableSet(EnumSet.of(Provider.GOLOSOM));

    /** The sites as the catalog and the people lists reach them; a test answers from memory. Called off the main thread. */
    public interface Net {
        /** Page {@code page} of the listing {@code url} (a person's page) on {@code p}, as the catalog loads it. */
        ProviderPage<BookPOJO> listing(Provider p, String url, int page) throws IOException;

        /** Page 1 of the site's search of authors ({@code reader} false) or readers. */
        ProviderPage<GenrePOJO> people(Provider p, boolean reader, String name) throws IOException;

        /** Page {@code page} of the site's book search. */
        ProviderPage<BookPOJO> books(Provider p, String query, int page) throws IOException;
    }

    /** A site's page of the person was found by its people search (called off the main thread). */
    public interface Found {
        void found(Provider p, String url);
    }

    private PersonSearch() {
    }

    /**
     * How {@code p} is asked for the person {@code name}: by its known page {@code url}, by its people search, by its
     * book search; null when it cannot tell the person's books (a reader on a site without a reader search).
     */
    public static Mode mode(Provider p, boolean reader, String url, String name) {
        if (url != null) {
            return Mode.LISTING;
        }
        SearchUrls.Category people = reader ? SearchUrls.Category.PERFORMERS : SearchUrls.Category.AUTHORS;
        if (SearchUrls.url(p, people, name, 1) != null) {
            return Mode.RESOLVE;
        }
        return searches(p, reader) ? Mode.SEARCH : null;
    }

    /** The site's book search can find the person's books: any author, a reader only on {@link #READER_SEARCH}. */
    static boolean searches(Provider p, boolean reader) {
        return !reader || READER_SEARCH.contains(p);
    }

    /** One source per site of {@code sites} that can be asked, in their order; {@code urls}: the known pages by site. */
    public static List<Source> plan(String name, boolean reader, Map<Provider, String> urls, List<Provider> sites,
            Net net, Found found) {
        List<Source> out = new ArrayList<>();
        for (Provider p : sites) {
            String url = urls.get(p);
            Mode mode = mode(p, reader, url, name);
            if (mode != null) {
                Mode fallback = url == null ? mode : mode(p, reader, null, name);
                out.add(new Source(p, mode, fallback, reader, name, url, net, found));
            }
        }
        return out;
    }

    /**
     * The URL of the one hit whose name is the person's own: the same words and initials in any order ("Кинг Стивен"
     * is "Стивен Кинг"); null when no hit is, or several pages are (namesakes).
     */
    static String resolve(TextNorm.Person want, List<GenrePOJO> hits) {
        String url = null;
        for (GenrePOJO hit : hits) {
            if (want.isEmpty() || hit.getUrl() == null || !want.equals(TextNorm.person(hit.getName()))) {
                continue;
            }
            if (url != null && !url.equals(hit.getUrl())) {
                return null;
            }
            url = hit.getUrl();
        }
        return url;
    }

    /**
     * The person {@code want} is the card's person {@code card}. A one-word name without initials ("Кинг") is only the
     * same one word ("Кинг", "С. Кинг"), never a longer name ("Оуэн Кинг", "Стивен Кинг"); otherwise the words of one
     * name lie in the other's and the initials agree: "С. Кинг" is "Стивен Кинг", not "Оуэн Кинг"; "А. Толстой" is not
     * "Л. Толстой".
     */
    public static boolean matches(TextNorm.Person want, TextNorm.Person card) {
        if (want.words.isEmpty() || card.words.isEmpty()) {
            return want.words.isEmpty() && card.words.isEmpty() && !want.initials.isEmpty()
                    && want.initials.equals(card.initials);
        }
        if (want.words.size() == 1 && want.initials.isEmpty()) {
            return card.words.equals(want.words);
        }
        if (card.words.containsAll(want.words)) {
            return fits(want, card) || card.words.size() == want.words.size() && fits(card, want);
        }
        return want.words.containsAll(card.words) && fits(card, want);
    }

    /** Every initial of {@code less} is one of {@code more}'s, or the first letter of a word {@code more} adds. */
    private static boolean fits(TextNorm.Person less, TextNorm.Person more) {
        for (String initial : less.initials) {
            boolean found = more.initials.contains(initial);
            for (String w : more.words) {
                found |= !less.words.contains(w) && w.startsWith(initial);
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    /** One of the people of the card's field of the kind (authors, or readers) is the person. */
    public static boolean holds(BookPOJO book, TextNorm.Person want, boolean reader) {
        for (TextNorm.Person p : TextNorm.persons(reader ? book.getArtist() : book.getAutor())) {
            if (matches(want, p)) {
                return true;
            }
        }
        return false;
    }

    /** The page's cards of the person; a page filtered to nothing keeps the site's next-page evidence. */
    static ProviderPage<BookPOJO> keep(ProviderPage<BookPOJO> page, TextNorm.Person want, boolean reader) {
        if (page.status != ProviderPage.Status.OK) {
            return page;
        }
        List<BookPOJO> kept = new ArrayList<>();
        for (BookPOJO b : page.items) {
            if (holds(b, want, reader)) {
                kept.add(b);
            }
        }
        return ProviderPage.of(kept, page.hasMore, page.items.size());
    }

    /**
     * The site's page of the person a card teaches: the card's link of the kind, when its field names one person alone
     * (a site links only the first one) and that person has the person's very name; null otherwise.
     */
    public static String teaches(BookPOJO book, TextNorm.Person want, boolean reader) {
        List<TextNorm.Person> people = TextNorm.persons(reader ? book.getArtist() : book.getAutor());
        String url = reader ? book.getUrlArtist() : book.getUrlAutor();
        return people.size() == 1 && !want.isEmpty() && want.equals(people.get(0)) && url != null && !url.isEmpty()
                ? url : null;
    }

    /**
     * The rank of a card for "newest first" ({@link SearchOrder.Sort#NEWEST}): its place in its site's own sequence
     * when it came from the person's listing; after every listed card when it came from a book search (its order is
     * relevance, not time).
     */
    public static int rank(boolean listing, int position) {
        return listing ? position : Integer.MAX_VALUE;
    }

    /** One site's source of the person's books. A source never has two pages in flight (SearchSession). */
    public static final class Source implements SearchSession.Source<BookPOJO> {

        /** Book-search pages filtered to nothing that one page skips on to the next ones, at most. */
        static final int MORE = 2;

        public final Provider provider;
        public final Mode mode;
        /** The way without a known page: the mode, or a saved page's way when it lists nothing (null: none). */
        private final Mode fallback;
        private final boolean reader;
        private final String person;
        private final TextNorm.Person want;
        private final Net net;
        private final Found found;
        /** The person's page on the site: known, or found by the people search; null until then. */
        private volatile String url;
        /** The page was saved and has not listed anything yet: an empty page 1 drops it for the fallback, once. */
        private volatile boolean check;
        /** The people search found no single hit: the book search instead (an author), or nothing (a reader). */
        private volatile boolean unresolved;
        /** Book-search pages skipped so far, filtered to nothing: page n is the site's page n + skip. */
        private volatile int skip;

        Source(Provider provider, Mode mode, Mode fallback, boolean reader, String person, String url, Net net,
                Found found) {
            this.provider = provider;
            this.mode = mode;
            this.fallback = fallback;
            this.reader = reader;
            this.person = person;
            this.want = TextNorm.person(person);
            this.url = url;
            this.check = url != null;
            this.net = net;
            this.found = found;
        }

        @Override
        public String name() {
            return provider.name();
        }

        @Override
        public Single<ProviderPage<BookPOJO>> load(String query, int page) {
            return Single.fromCallable(() -> page(query, page));
        }

        ProviderPage<BookPOJO> page(String query, int page) throws IOException {
            String known = url;
            if (known != null) {
                ProviderPage<BookPOJO> listed = net.listing(provider, known, page);
                if (!check || page > 1 || fallback == null || listed.status != ProviderPage.Status.EMPTY) {
                    if (listed.status == ProviderPage.Status.OK) {
                        check = false;
                    }
                    return listed; // a failure may pass: only a page that lists nothing is stale
                }
                check = false;
                url = null; // a saved page that lists nothing (moved, or wrong): the way without it
            }
            if (fallback == Mode.RESOLVE && !unresolved) {
                if (page > 1) {
                    return ProviderPage.empty(); // page 1 resolves or settles the way first
                }
                ProviderPage<GenrePOJO> hits = net.people(provider, reader, person);
                if (hits.isFailure()) {
                    return hits.asFailure(); // a timeout, a block, a rate limit: no other way, a retry looks again
                }
                String hit = resolve(want, hits.items);
                if (hit != null) {
                    url = hit;
                    if (found != null) {
                        found.found(provider, hit);
                    }
                    return net.listing(provider, hit, page);
                }
                unresolved = true;
            }
            if (!searches(provider, reader)) {
                return ProviderPage.empty();
            }
            // the query is the person's name, or its other letter case on the case retry of a search
            if (page == 1) {
                skip = 0;
            }
            ProviderPage<BookPOJO> kept = keep(net.books(provider, query, page + skip), want, reader);
            // a page with none of the person's cards is no answer: the next ones, a few (the UI pages only a find)
            for (int more = 0; more < MORE && kept.status == ProviderPage.Status.OK && kept.items.isEmpty()
                    && kept.hasMore; more++) {
                skip++;
                kept = keep(net.books(provider, query, page + skip), want, reader);
            }
            return kept;
        }

        /** Its cards are the site's own sequence of the person's books (a listing), not a search's. */
        public boolean listing() {
            return url != null;
        }

        /** A listing is the same in any letter case: only the book search asks again in the other one. */
        @Override
        public boolean caseRetry() {
            return mode == Mode.SEARCH;
        }

        /**
         * The requests one page may take: a saved page not yet seen to list anything, a lookup, then a listing or the
         * book search with its skipped pages.
         */
        @Override
        public long timeoutSeconds() {
            long one = provider.requestSeconds();
            boolean known = url != null;
            if (known && (!check || fallback == null)) {
                return one;
            }
            int n = known ? 1 : 0;
            if (fallback == Mode.RESOLVE && !unresolved) {
                n++;
            }
            return (n + (searches(provider, reader) ? 1 + MORE : 1)) * one;
        }
    }
}
