package com.fanok.audiobooks.search;

import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.search.SearchMatcher.Card;
import com.fanok.audiobooks.search.SearchMatcher.Match;
import com.fanok.audiobooks.search.SearchMatcher.Tier;
import com.fanok.audiobooks.search.SearchRequest.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Cards of the same work from different sites as one group, its recordings as narrations (search-refactoring.md §6.4,
 * A.3). The result depends only on the cards, never on their order of arrival: equal keys (title without edition +
 * the words of the authors) group; a card without an author joins the only group of its title that has one; with
 * {@code fuzzy}, groups of compatible authors whose titles are nearly equal (Dice ≥ 0.92 over bigrams, equal numbers)
 * merge. Different authors, volumes, readers or editions never merge; inside a group, recordings split by reader,
 * edition and duration. Nothing is dropped: every card stays reachable from its group.
 */
public final class SearchGrouper {

    static final double TITLE_DICE = 0.92;
    /** Durations of one recording differ by at most this many minutes, or by {@link #DURATION_SHARE}. */
    static final int DURATION_SLACK = 5;
    static final double DURATION_SHARE = 0.10;

    /** One card of one site as it arrived: the book, its source and position, its search form and verdict. */
    public static final class Offer {

        public final BookPOJO book;
        public final Provider source;
        /** The source's place in the listener's order of sources. */
        public final int order;
        /**
         * The site's own rank, page·1000 + position; {@link Integer#MAX_VALUE} for a site whose order is not by
         * relevance (by date, by views), so that its order never lifts a card.
         */
        public final int nativeRank;
        public final Card card;
        /** The recording's length, -1 when unknown. */
        public final int minutes;
        /** Its place in the order of arrival of the whole search. */
        public final int arrival;
        /** A list of books (a site's collection), not a book: it opens as a listing and never groups. */
        public final boolean listing;
        /** The app's genres its site's genre name names; empty when none is known. */
        public final Set<Genre> genres;
        /** The verdict for the current request; {@link Match#NONE} until {@link #score}. */
        Match match = Match.NONE;
        private SearchRequest scoredFor;
        /** Whether the author alone, and the series alone, hold the whole query ({@link #holds}). */
        private boolean authorHolds;
        private boolean seriesHolds;
        private SearchRequest heldFor;

        public Offer(BookPOJO book, Provider source, int order, int nativeRank) {
            this(book, source, order, nativeRank, 0);
        }

        public Offer(BookPOJO book, Provider source, int order, int nativeRank, int arrival) {
            this.book = book;
            this.source = source;
            this.order = order;
            this.nativeRank = nativeRank;
            this.arrival = arrival;
            this.card = Card.of(book);
            this.minutes = ListingFilters.durationMinutes(book.getTime());
            this.listing = source != null && book.getUrl() != null && source.parser.opensAsListing(book.getUrl());
            this.genres = Genre.allOf(book.getGenre());
        }

        public String url() {
            return book.getUrl();
        }

        public Match match() {
            return match;
        }

        /** Scores the card for {@code request}; a second call for the same request costs nothing. */
        public Offer score(SearchRequest request) {
            if (!request.equals(scoredFor)) {
                match = SearchMatcher.score(card, request);
                scoredFor = request;
            }
            return this;
        }

        /**
         * Every significant word of {@code request} lies in this card's {@code field} ({@link Field#AUTHOR} or
         * {@link Field#SERIES}) alone: the card's author or series is one the listener looks for. Cached per request.
         */
        boolean holds(Field field, SearchRequest request) {
            if (!request.equals(heldFor)) {
                authorHolds = card.has(Field.AUTHOR) && SearchMatcher.score(card,
                        new SearchRequest(request.text, EnumSet.of(Field.AUTHOR))).covered;
                seriesHolds = card.has(Field.SERIES) && SearchMatcher.score(card,
                        new SearchRequest(request.text, EnumSet.of(Field.SERIES))).covered;
                heldFor = request;
            }
            return field == Field.AUTHOR ? authorHolds : field == Field.SERIES && seriesHolds;
        }

        /** The card's author has the word {@code word} itself (normalized), not a prefix or a near one. */
        boolean authorHas(String word) {
            return card.words.get(Field.AUTHOR.ordinal()).contains(word);
        }

        /** The card's whole title (also without its edition) is {@code key} (normalized). */
        boolean titleIs(String key) {
            return card.equalsWhole(Field.TITLE, key);
        }

        /** The work the card is of, as far as its own fields tell: title without edition and the authors' words. */
        String work() {
            return card.title + '\u0000' + TextNorm.join(new ArrayList<>(TextNorm.words(card.authors)));
        }

        /** The catalog the card belongs to: mirrors of one catalog count once. */
        Provider catalog() {
            return source == null ? null : source.original();
        }

        int filledFields() {
            int n = 0;
            for (SearchRequest.Field f : SearchRequest.Field.values()) {
                n += card.has(f) ? 1 : 0;
            }
            return n + (minutes > 0 ? 1 : 0) + (book.getPhoto() != null && !book.getPhoto().isEmpty() ? 1 : 0);
        }
    }

    /** One recording of a work: the cards that carry it, best first, on one or more sites. */
    public static final class Narration {

        public final SortedSet<String> reader;
        public final String edition;
        public final List<Offer> offers;
        public final Offer best;

        Narration(SortedSet<String> reader, String edition, List<Offer> offers) {
            this.reader = reader;
            this.edition = edition;
            List<Offer> sorted = new ArrayList<>(offers);
            Collections.sort(sorted, TO_OPEN);
            this.offers = Collections.unmodifiableList(sorted);
            this.best = sorted.get(0);
        }

        /** Sites that carry it, mirrors of one catalog counted once. */
        public int catalogs() {
            return SearchGrouper.catalogs(offers);
        }

        /** The shortest known length of its cards, -1 when none is known. */
        public int minutes() {
            int min = -1;
            for (Offer o : offers) {
                if (o.minutes > 0 && (min < 0 || o.minutes < min)) {
                    min = o.minutes;
                }
            }
            return min;
        }
    }

    /** One work: every card of it, its recordings, the card that represents it and its best verdict. */
    public static final class Group {

        /** Stable while shown: a URL of one of its cards (see {@link #group}). */
        public final String id;
        public final List<Offer> offers;
        public final List<Narration> narrations;
        /** The card whose title, cover and verdict the group shows. */
        public final Offer representative;
        public final Tier tier;
        public final int score;

        Group(String id, List<Offer> offers, List<Narration> narrations) {
            this.id = id;
            List<Offer> sorted = new ArrayList<>(offers);
            Collections.sort(sorted, REPRESENTATIVE);
            this.offers = Collections.unmodifiableList(sorted);
            this.narrations = Collections.unmodifiableList(narrations);
            this.representative = sorted.get(0);
            this.tier = representative.match.tier;
            this.score = representative.match.score;
        }

        public int catalogs() {
            return SearchGrouper.catalogs(offers);
        }

        public boolean contains(String url) {
            for (Offer o : offers) {
                if (o.url().equals(url)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Best verdict, then the fullest card, then the listener's order of sources, then the URL. */
    static final Comparator<Offer> REPRESENTATIVE = new Comparator<Offer>() {
        @Override
        public int compare(Offer a, Offer b) {
            int c = a.match.tier.compareTo(b.match.tier);
            if (c == 0) {
                c = Integer.compare(b.match.score, a.match.score);
            }
            if (c == 0) {
                c = Integer.compare(b.filledFields(), a.filledFields());
            }
            if (c == 0) {
                c = Integer.compare(a.order, b.order);
            }
            return c != 0 ? c : a.url().compareTo(b.url());
        }
    };

    /** The card to open for a recording: the listener's order of sources, then the fullest card, then the URL. */
    static final Comparator<Offer> TO_OPEN = new Comparator<Offer>() {
        @Override
        public int compare(Offer a, Offer b) {
            int c = Integer.compare(a.order, b.order);
            if (c == 0) {
                c = Integer.compare(b.filledFields(), a.filledFields());
            }
            return c != 0 ? c : a.url().compareTo(b.url());
        }
    };

    private SearchGrouper() {
    }

    /** A group while it is being built. */
    private static final class Draft {
        final String title;
        /** Of the title, once: the fuzzy step compares drafts pairwise. */
        final Set<String> bigrams;
        final Set<Integer> numbers;
        final List<Offer> offers = new ArrayList<>();
        final List<TextNorm.Person> authors = new ArrayList<>();
        boolean merged;

        Draft(String title) {
            this.title = title;
            this.bigrams = StringSim.bigrams(title);
            this.numbers = numbers(title);
        }

        boolean sameTitle(Draft other) {
            return numbers.equals(other.numbers) && StringSim.dice(bigrams, other.bigrams) >= TITLE_DICE;
        }

        SortedSet<String> authorWords() {
            return TextNorm.words(authors);
        }

        void absorb(Draft other) {
            offers.addAll(other.offers);
            for (TextNorm.Person p : other.authors) {
                if (!authors.contains(p)) {
                    authors.add(p);
                }
            }
            other.merged = true;
        }
    }

    /**
     * The groups of {@code offers} (scored), in no particular order. {@code shownIds} are the ids of groups already
     * on screen: a group keeps such an id (the smallest when it absorbed several), otherwise it gets the smallest URL
     * of its cards, so an id only changes when its group disappears into another.
     */
    public static List<Group> group(Collection<Offer> offers, Set<String> shownIds, boolean fuzzy) {
        // 1. equal keys; a TreeMap orders the drafts by key, so every later step is independent of arrival
        TreeMap<String, Draft> byKey = new TreeMap<>();
        List<Offer> listings = new ArrayList<>();
        for (Offer o : offers) {
            if (o.listing) {
                listings.add(o); // a collection of the same name is not a recording of the book
                continue;
            }
            String key = o.work();
            Draft d = byKey.get(key);
            if (d == null) {
                d = new Draft(o.card.title);
                byKey.put(key, d);
            }
            d.offers.add(o);
            for (TextNorm.Person p : o.card.authors) {
                if (!d.authors.contains(p)) {
                    d.authors.add(p);
                }
            }
        }
        List<Draft> drafts = new ArrayList<>(byKey.values());
        // 2. a card without an author joins the one group of its title that has authors; with two it stays apart
        joinAuthorless(drafts);
        // 3. nearly equal titles of compatible authors, compared within buckets of one author word each
        if (fuzzy) {
            TreeMap<String, List<Draft>> buckets = new TreeMap<>();
            for (Draft d : drafts) {
                if (d.merged) {
                    continue;
                }
                for (String w : d.authorWords()) {
                    if (w.length() >= 4) {
                        List<Draft> list = buckets.get(w);
                        if (list == null) {
                            list = new ArrayList<>();
                            buckets.put(w, list);
                        }
                        list.add(d);
                    }
                }
            }
            for (List<Draft> bucket : buckets.values()) {
                for (int i = 0; i < bucket.size(); i++) {
                    Draft a = bucket.get(i);
                    for (int j = i + 1; j < bucket.size() && !a.merged; j++) {
                        Draft b = bucket.get(j);
                        if (!b.merged && authorsCompatible(a.authors, b.authors) && a.sameTitle(b)) {
                            a.absorb(b);
                        }
                    }
                }
            }
            // the authors of two spellings ("Булгаков Михаил", "М. Булгаков") are one group now: step 2 once more
            joinAuthorless(drafts);
        }
        // 4. recordings, and the ids
        List<Group> out = new ArrayList<>();
        for (Draft d : drafts) {
            if (!d.merged) {
                out.add(new Group(idOf(d.offers, shownIds), d.offers, narrations(d.offers)));
            }
        }
        for (Offer o : listings) {
            List<Offer> one = Collections.singletonList(o);
            out.add(new Group(idOf(one, shownIds), one, narrations(one)));
        }
        return out;
    }

    /** Every person of the smaller side is a person of the other: a site may leave out a co-author, but not swap one. */
    static boolean authorsCompatible(List<TextNorm.Person> a, List<TextNorm.Person> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        List<TextNorm.Person> small = a.size() <= b.size() ? a : b;
        List<TextNorm.Person> big = small == a ? b : a;
        for (TextNorm.Person p : small) {
            boolean found = false;
            for (TextNorm.Person q : big) {
                found |= TextNorm.samePerson(p, q);
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    /** Each unmerged draft without authors joins the one unmerged draft of its title that has authors, if one. */
    private static void joinAuthorless(List<Draft> drafts) {
        Map<String, List<Draft>> withAuthors = new LinkedHashMap<>();
        for (Draft d : drafts) {
            if (!d.merged && !d.authors.isEmpty()) {
                List<Draft> list = withAuthors.get(d.title);
                if (list == null) {
                    list = new ArrayList<>();
                    withAuthors.put(d.title, list);
                }
                list.add(d);
            }
        }
        for (Draft d : drafts) {
            List<Draft> candidates = withAuthors.get(d.title);
            if (!d.merged && d.authors.isEmpty() && candidates != null && candidates.size() == 1) {
                candidates.get(0).absorb(d);
            }
        }
    }

    /** Nearly the same title: Dice over bigrams ≥ {@link #TITLE_DICE} and the same numbers ("том 2" ≠ "том 3"). */
    static boolean sameTitle(String a, String b) {
        return new Draft(a).sameTitle(new Draft(b));
    }

    /** The numbers of a title's key, Roman ones too ("книга ii" = "книга 2"): Dice ignores how often a letter repeats. */
    static Set<Integer> numbers(String key) {
        Set<Integer> out = new HashSet<>();
        for (String w : TextNorm.tokens(key)) {
            if (TextNorm.isNumber(w)) {
                try {
                    out.add(Integer.parseInt(w.length() > 9 ? w.substring(w.length() - 9) : w));
                } catch (NumberFormatException ignored) {
                    // digits of another script: compared by Character.isDigit only
                }
            } else {
                int roman = roman(w);
                if (roman > 0) {
                    out.add(roman);
                }
            }
        }
        return out;
    }

    /** Volume numbers up to 89: i, v, x, l only, so that a Latin word ("mix", "did") is never taken for one. */
    private static final java.util.regex.Pattern ROMAN =
            java.util.regex.Pattern.compile("(xl|l?x{0,3})(ix|iv|v?i{0,3})");

    /** The value of a Roman numeral in lower case (i…lxxxix), 0 for anything else. */
    static int roman(String w) {
        if (w.isEmpty() || !ROMAN.matcher(w).matches()) {
            return 0;
        }
        String digits = "ivxl";
        int[] values = {1, 5, 10, 50};
        int total = 0;
        for (int i = 0; i < w.length(); i++) {
            int v = values[digits.indexOf(w.charAt(i))];
            int next = i + 1 < w.length() ? values[digits.indexOf(w.charAt(i + 1))] : 0;
            total += v < next ? -v : v;
        }
        return total;
    }

    private static String idOf(List<Offer> offers, Set<String> shownIds) {
        String shown = null;
        String smallest = null;
        for (Offer o : offers) {
            String url = o.url();
            if (shownIds.contains(url) && (shown == null || url.compareTo(shown) < 0)) {
                shown = url;
            }
            if (smallest == null || url.compareTo(smallest) < 0) {
                smallest = url;
            }
        }
        return shown != null ? shown : smallest;
    }

    /**
     * Recordings: the cards of one reader and edition, cut where their lengths differ more than a recording's own
     * cards do. A card of unknown length joins the only length cluster there is, else a cluster of its own.
     */
    static List<Narration> narrations(List<Offer> offers) {
        TreeMap<String, List<Offer>> byVoice = new TreeMap<>();
        Map<String, SortedSet<String>> readerOf = new LinkedHashMap<>();
        Map<String, String> editionOf = new LinkedHashMap<>();
        for (Offer o : offers) {
            SortedSet<String> reader = TextNorm.words(o.card.readers);
            String key = TextNorm.join(new ArrayList<>(reader)) + '\u0000' + o.card.edition;
            List<Offer> list = byVoice.get(key);
            if (list == null) {
                list = new ArrayList<>();
                byVoice.put(key, list);
                readerOf.put(key, reader);
                editionOf.put(key, o.card.edition);
            }
            list.add(o);
        }
        List<Narration> out = new ArrayList<>();
        for (Map.Entry<String, List<Offer>> e : byVoice.entrySet()) {
            for (List<Offer> cluster : byLength(e.getValue())) {
                out.add(new Narration(new TreeSet<>(readerOf.get(e.getKey())), editionOf.get(e.getKey()), cluster));
            }
        }
        Collections.sort(out, NARRATIONS);
        return out;
    }

    /**
     * Recordings as the listener chooses among them: a known reader before "reader unknown", then the one on most
     * sites, then the one with most cards, then by reader and edition; ties keep the order of the keys (stable sort).
     */
    static final Comparator<Narration> NARRATIONS = new Comparator<Narration>() {
        @Override
        public int compare(Narration a, Narration b) {
            int c = Boolean.compare(a.reader.isEmpty(), b.reader.isEmpty());
            if (c == 0) {
                c = Integer.compare(b.catalogs(), a.catalogs());
            }
            if (c == 0) {
                c = Integer.compare(b.offers.size(), a.offers.size());
            }
            return c;
        }
    };

    private static List<List<Offer>> byLength(List<Offer> offers) {
        List<Offer> known = new ArrayList<>();
        List<Offer> unknown = new ArrayList<>();
        for (Offer o : offers) {
            (o.minutes > 0 ? known : unknown).add(o);
        }
        Collections.sort(known, new Comparator<Offer>() {
            @Override
            public int compare(Offer a, Offer b) {
                int c = Integer.compare(a.minutes, b.minutes);
                return c != 0 ? c : a.url().compareTo(b.url());
            }
        });
        List<List<Offer>> clusters = new ArrayList<>();
        List<Offer> current = null;
        for (Offer o : known) {
            int first = current == null ? 0 : current.get(0).minutes;
            if (current == null || o.minutes - first > Math.max(DURATION_SLACK, first * DURATION_SHARE)) {
                current = new ArrayList<>();
                clusters.add(current);
            }
            current.add(o);
        }
        if (!unknown.isEmpty()) {
            if (clusters.size() == 1) {
                clusters.get(0).addAll(unknown);
            } else {
                clusters.add(unknown);
            }
        }
        return clusters;
    }

    static int catalogs(List<Offer> offers) {
        Set<Object> seen = new HashSet<>();
        for (Offer o : offers) {
            Provider c = o.catalog();
            seen.add(c == null ? o.url() : c);
        }
        return seen.size();
    }
}
