package com.fanok.audiobooks.search;

import com.fanok.audiobooks.pojo.SearcheblPOJO;
import com.fanok.audiobooks.search.SearchGrouper.Group;
import com.fanok.audiobooks.search.SearchGrouper.Offer;
import com.fanok.audiobooks.search.SearchOrder.Sort;
import com.fanok.audiobooks.search.SearchRequest.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the search screen shows, as a pure function of what the session knows (search-refactoring.md §9): one chip
 * per source with its state and count, the state of the pass, the rows of the list and why it is empty when it is.
 * No Android here: texts are chosen by the screen from the kinds and numbers below.
 * <p>
 * Under "All" a row is a work (cards of the same book from several sites grouped) or, ungrouped, a card; under a
 * site's tab, that site's cards. A scope keeps the cards whose words lie in the chosen fields; a card that lacks
 * those fields altogether is kept too, after the others ({@link Row#unknown}), and only the rest is hidden.
 */
public final class SearchUi {

    /**
     * A source chip: searching, found (with a count), found nothing, did not answer in time, failed, blocked, or
     * stopped before it answered (by the listener, or a search restored after its process died).
     */
    public enum ChipKind { RUNNING, FOUND, EMPTY, TIMEOUT, ERROR, BLOCKED, STOPPED }

    /** The pass as a whole: before any search, under way, every source answered, some failed, all failed, no source. */
    public enum Pass { IDLE, RUNNING, DONE, PARTIAL, FAILED, NO_SOURCES }

    /** Why the list is empty; NONE when it is not. */
    public enum Empty { NONE, START, NO_SOURCES, SEARCHING, NOTHING, NOTHING_SOME_FAILED, ALL_FAILED, SCOPE_HID_ALL,
        TAB_SEARCHING, TAB_NOTHING, TAB_FAILED }

    public static final class Chip {
        public final Provider source;
        public final ChipKind kind;
        /** The cards it brought. */
        public final int count;
        public final boolean hasMore;
        public final boolean selected;

        Chip(Provider source, ChipKind kind, int count, boolean hasMore, boolean selected) {
            this.source = source;
            this.kind = kind;
            this.count = count;
            this.hasMore = hasMore;
            this.selected = selected;
        }
    }

    /**
     * One row: a work (with its {@link #group}) or a single card. {@link #key} identifies it while it is shown: the
     * group's stable id, or the card's URL.
     */
    public static final class Row {
        public final String key;
        /** The card shown: the group's representative, or the card itself. */
        public final Offer offer;
        /** Null for a row of a single card shown ungrouped (a site's tab, the "cards" view). */
        public final Group group;
        /** The card lacks every field of the scope: kept, after the others, as "no data". */
        public final boolean unknown;
        /** The shown card's verdict as computed for this state (the offer's own may already be another request's). */
        public final SearchMatcher.Match match;

        Row(Offer offer, Group group, boolean unknown) {
            this.key = group != null ? group.id : offer.url();
            this.offer = offer;
            this.group = group;
            this.unknown = unknown;
            this.match = offer.match();
        }
    }

    /** The facts the screen is computed from. */
    public static final class Input {
        public SearchRequest request = SearchRequest.anywhere("");
        /** The genres to keep; empty: every genre. A card whose genre is unknown is kept as "no data". */
        public Set<Genre> genres = Collections.emptySet();
        /** Every card of the pass, in order of arrival. */
        public List<Offer> offers = Collections.emptyList();
        /** The planned sources, in the listener's order: the order of the chips among equals (see {@link #CHIPS}). */
        public List<Provider> sources = Collections.emptyList();
        /** By source name; a source without one has not started. */
        public Map<String, SearchSession.State> states = Collections.emptyMap();
        /** The selected tab; null for "All". */
        public Provider tab;
        public Sort sort = Sort.RELEVANCE;
        public boolean grouped = true;
        public boolean fuzzy = true;
        /** A search has been asked for (or restored). */
        public boolean searched;
        /** Ids of the groups on screen now, so that they keep them. */
        public Set<String> shownIds = Collections.emptySet();
        /** When the results were saved, for a search shown from its snapshot; 0 for live results. */
        public long restoredAt;
        /** Tells a late result of a superseded search from the current one. */
        public int generation;
        /** The sites' own author and series lists for the query (Книга в ухе, ABMP3), of every site. */
        public SearcheblPOJO people = new SearcheblPOJO();
    }

    public static final class State {
        public final List<Chip> chips;
        /** The "All" chip's count: the rows of "All". */
        public final int allCount;
        public final Pass pass;
        public final int answered;
        public final int planned;
        /** Sources whose last answer was a failure, in the listener's order of sources. */
        public final List<Provider> failed;
        /** Sites (catalogs) with at least one shown card. */
        public final int sites;
        public final List<Row> rows;
        /** Cards the scope or the genres hid (the ones whose fields they check and that do not match). */
        public final int hiddenByScope;
        /** Rows kept although they lack the scope's fields or a genre. */
        public final int unknown;
        public final Empty empty;
        /** The selected tab's chip; null for "All". */
        public final Chip tab;
        /** Some source has a next page. */
        public final boolean hasMore;
        /** What this state was computed from. */
        public final Input input;
        /** The author and series plates of the selected tab ({@link SearchPlates}). */
        public final SearcheblPOJO plates;

        State(List<Chip> chips, int allCount, Pass pass, int answered, int planned, List<Provider> failed, int sites,
                List<Row> rows, int hiddenByScope, int unknown, Empty empty, Chip tab, boolean hasMore, Input input,
                SearcheblPOJO plates) {
            this.chips = chips;
            this.allCount = allCount;
            this.pass = pass;
            this.answered = answered;
            this.planned = planned;
            this.failed = failed;
            this.sites = sites;
            this.rows = rows;
            this.hiddenByScope = hiddenByScope;
            this.unknown = unknown;
            this.empty = empty;
            this.tab = tab;
            this.hasMore = hasMore;
            this.input = input;
            this.plates = plates;
        }

        /** The row opened with {@code key}, or null. */
        public Row row(String key) {
            for (Row r : rows) {
                if (r.key.equals(key)) {
                    return r;
                }
            }
            return null;
        }
    }

    /**
     * The chips after "All": the sources that found the most first, then those still searching, those that found
     * nothing, and last those that did not answer (timeout, error, blocked, stopped). Stable: equals keep the
     * listener's order of sources.
     */
    static final Comparator<Chip> CHIPS = (a, b) -> rank(a.kind) != rank(b.kind)
            ? Integer.compare(rank(a.kind), rank(b.kind))
            : a.kind == ChipKind.FOUND ? Integer.compare(b.count, a.count) : 0;

    private static int rank(ChipKind kind) {
        switch (kind) {
            case FOUND:
                return 0;
            case RUNNING:
                return 1;
            case EMPTY:
                return 2;
            default:
                return 3;
        }
    }

    private SearchUi() {
    }

    public static State reduce(Input in) {
        // the scope sorts every card into shown, kept as "no data", or hidden; the genres then judge each row
        List<Offer> main = new ArrayList<>();
        List<Offer> unknown = new ArrayList<>();
        int hidden = 0;
        for (Offer o : in.offers) {
            o.score(in.request);
            if (in.request.isAnywhere() || o.match().covered) {
                main.add(o);
            } else if (lacksScope(o, in.request.scope)) {
                unknown.add(o);
            } else {
                hidden++;
            }
        }
        int[] hiddenByGenre = new int[1];
        List<Row> allRows = rows(main, unknown, in, null, hiddenByGenre);
        List<Row> rows = in.tab == null ? allRows : rows(main, unknown, in, in.tab, new int[1]);
        hidden += hiddenByGenre[0];

        List<Chip> chips = new ArrayList<>();
        List<Provider> failed = new ArrayList<>();
        int answered = 0;
        boolean running = false;
        boolean more = false;
        Chip tab = null;
        for (Provider p : in.sources) {
            SearchSession.State s = in.states.get(p.name());
            ChipKind kind = kind(s);
            running |= kind == ChipKind.RUNNING;
            if (s != null && s.status != null && !s.running) {
                answered++;
            }
            if (kind == ChipKind.TIMEOUT || kind == ChipKind.ERROR || kind == ChipKind.BLOCKED
                    || kind == ChipKind.STOPPED) {
                failed.add(p);
            }
            Chip chip = new Chip(p, kind, s == null ? 0 : s.count, s != null && s.hasMore, p == in.tab);
            more |= chip.hasMore;
            if (p == in.tab) {
                tab = chip;
            }
            chips.add(chip);
        }
        Collections.sort(chips, CHIPS);
        Pass pass = !in.searched ? Pass.IDLE
                : in.sources.isEmpty() ? Pass.NO_SOURCES
                : running ? Pass.RUNNING
                : failed.size() == in.sources.size() ? Pass.FAILED
                : failed.isEmpty() ? Pass.DONE
                : Pass.PARTIAL;
        int unknownRows = 0;
        Set<Object> catalogs = new HashSet<>();
        for (Row r : rows) {
            unknownRows += r.unknown ? 1 : 0;
            for (Offer o : r.group != null ? r.group.offers : Collections.singletonList(r.offer)) {
                catalogs.add(o.source == null ? o.url() : o.source.original());
            }
        }
        return new State(Collections.unmodifiableList(chips), allRows.size(), pass, answered, in.sources.size(),
                Collections.unmodifiableList(failed), catalogs.size(), Collections.unmodifiableList(rows), hidden,
                unknownRows, empty(in, rows, pass, tab, hidden), tab, more, in,
                SearchPlates.of(in.offers, in.request, in.people, in.tab));
    }

    private static boolean lacksScope(Offer o, Set<Field> scope) {
        for (Field f : scope) {
            if (o.card.has(f)) {
                return false;
            }
        }
        return true;
    }

    /** The rows in order: the shown ones, those without a genre for the genre filter, those without the scope's data. */
    private static List<Row> rows(List<Offer> main, List<Offer> unknown, Input in, Provider tab, int[] hidden) {
        List<Row> out = new ArrayList<>();
        List<Row> noGenre = new ArrayList<>();
        List<Row> noScope = new ArrayList<>();
        addRows(out, noGenre, only(main, tab), in, tab, false, hidden);
        addRows(noScope, noScope, only(unknown, tab), in, tab, true, hidden);
        out.addAll(noGenre);
        out.addAll(noScope);
        return out;
    }

    private static void addRows(List<Row> out, List<Row> noGenre, List<Offer> offers, Input in, Provider tab,
            boolean unknown, int[] hidden) {
        if (tab == null && in.grouped) {
            List<Group> groups = SearchGrouper.group(offers, in.shownIds, in.fuzzy);
            Collections.sort(groups, SearchOrder.groups(in.sort));
            for (Group g : groups) {
                addRow(out, noGenre, g.representative, g, g.offers, in.genres, unknown, hidden);
            }
        } else {
            List<Offer> sorted = new ArrayList<>(offers);
            Collections.sort(sorted, SearchOrder.offers(in.sort));
            for (Offer o : sorted) {
                addRow(out, noGenre, o, null, Collections.singletonList(o), in.genres, unknown, hidden);
            }
        }
    }

    /**
     * A work passes the genres when one of its cards has a chosen genre, whatever the sites without a genre say;
     * a work none of whose cards has a genre is kept as "no data"; a work whose genres are all others is hidden.
     */
    private static void addRow(List<Row> out, List<Row> noGenre, Offer shown, Group g, List<Offer> cards,
            Set<Genre> genres, boolean unknown, int[] hidden) {
        boolean known = false;
        boolean pass = genres.isEmpty();
        for (int i = 0; !pass && i < cards.size(); i++) {
            Set<Genre> own = cards.get(i).genres;
            known |= !own.isEmpty();
            pass = !Collections.disjoint(own, genres);
        }
        if (pass) {
            out.add(new Row(shown, g, unknown));
        } else if (known) {
            hidden[0] += cards.size();
        } else {
            noGenre.add(new Row(shown, g, true));
        }
    }

    private static List<Offer> only(List<Offer> offers, Provider tab) {
        if (tab == null) {
            return offers;
        }
        List<Offer> out = new ArrayList<>();
        for (Offer o : offers) {
            if (o.source == tab) {
                out.add(o);
            }
        }
        return out;
    }

    static ChipKind kind(SearchSession.State s) {
        if (s != null && s.running) {
            return ChipKind.RUNNING;
        }
        if (s == null || s.status == null) {
            return ChipKind.STOPPED;
        }
        if (s.count > 0) {
            // the status is its last page's: an empty or failed next page leaves what it found (a failed one can
            // still be retried on its own)
            return ChipKind.FOUND;
        }
        switch (s.status) {
            case OK:
                return s.count > 0 ? ChipKind.FOUND : ChipKind.EMPTY;
            case TIMEOUT:
                return ChipKind.TIMEOUT;
            case BLOCKED:
                return ChipKind.BLOCKED;
            case ERROR:
                return ChipKind.ERROR;
            default:
                return ChipKind.EMPTY;
        }
    }

    private static Empty empty(Input in, List<Row> rows, Pass pass, Chip tab, int hidden) {
        if (!rows.isEmpty()) {
            return Empty.NONE;
        }
        if (!in.searched) {
            return Empty.START;
        }
        if (in.sources.isEmpty()) {
            return Empty.NO_SOURCES;
        }
        if (tab != null) {
            switch (tab.kind) {
                case RUNNING:
                    return Empty.TAB_SEARCHING;
                case TIMEOUT:
                case ERROR:
                case BLOCKED:
                case STOPPED:
                    return Empty.TAB_FAILED;
                default:
                    return Empty.TAB_NOTHING;
            }
        }
        if (hidden > 0 && pass != Pass.RUNNING) {
            return Empty.SCOPE_HID_ALL;
        }
        switch (pass) {
            case RUNNING:
                return Empty.SEARCHING;
            case FAILED:
                return Empty.ALL_FAILED;
            case PARTIAL:
                return Empty.NOTHING_SOME_FAILED;
            default:
                return Empty.NOTHING;
        }
    }
}
