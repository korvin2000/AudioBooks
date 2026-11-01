package com.fanok.audiobooks.search;

import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.search.SearchRequest.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * How well a card answers a query, field by field (search-refactoring.md §6.2, A.2). Every query word is matched to at
 * most one word of the card and every card word serves one query word; the matching takes the most pairs, then the
 * best quality (Kuhn–Munkres on a matrix of at most 12 query words). A pair is exact (1.0), a prefix of four letters
 * or more (0.9: unfinished typing, case endings), one edit for five letters or more (0.8) or two for nine or more
 * (0.65); numbers only match exactly, so "том 2" never meets "том 3". The result is a {@link Tier} and a score 0–100,
 * both depending only on the query and the card, never on the other results.
 */
public final class SearchMatcher {

    /** A: a field equals the query; B: every word, exact, as a phrase in one field; C: every word exact; D: with typos or prefixes; E: partly. */
    public enum Tier { A, B, C, D, E }

    static final int MAX_QUERY_WORDS = 12;
    static final int MAX_FIELD_WORDS = 24;
    private static final Field[] FIELDS = Field.values();
    private static final double[] ANYWHERE_WEIGHT = {1.0, 0.95, 0.9, 0.9}; // TITLE, AUTHOR, SERIES, READER

    /** A card's text in the search form, computed once when the card arrives. */
    public static final class Card {

        final List<List<String>> words = new ArrayList<>(FIELDS.length);
        final String[] keys = new String[FIELDS.length];
        /** The title's key without its recording edition, and the edition (§6.1). */
        public final String title;
        public final String edition;
        public final List<TextNorm.Person> authors;
        public final List<TextNorm.Person> readers;

        public Card(String title, String author, String series, String reader) {
            String[] raw = new String[FIELDS.length];
            raw[Field.TITLE.ordinal()] = title;
            raw[Field.AUTHOR.ordinal()] = author;
            raw[Field.SERIES.ordinal()] = series;
            raw[Field.READER.ordinal()] = reader;
            for (Field f : FIELDS) {
                List<String> w = TextNorm.tokens(raw[f.ordinal()]);
                keys[f.ordinal()] = TextNorm.join(w);
                words.add(w.size() > MAX_FIELD_WORDS ? new ArrayList<>(w.subList(0, MAX_FIELD_WORDS)) : w);
            }
            String[] split = TextNorm.splitEdition(title);
            this.title = split[0];
            this.edition = split[1];
            this.authors = TextNorm.persons(author);
            this.readers = TextNorm.persons(reader);
        }

        public static Card of(BookPOJO book) {
            return new Card(book.getName(), book.getAutor(), book.getSeries(), book.getArtist());
        }

        public boolean has(Field f) {
            return !words.get(f.ordinal()).isEmpty();
        }

        /** The whole field equals {@code key}; the title also without its edition. */
        boolean equalsWhole(Field f, String key) {
            return !key.isEmpty() && (key.equals(keys[f.ordinal()]) || f == Field.TITLE && key.equals(title));
        }
    }

    /** The verdict for one card. */
    public static final class Match {

        public static final Match NONE = new Match(Tier.E, 0, false, Collections.<Field>emptySet(), false);

        public final Tier tier;
        public final int score;
        /** Every significant query word was found in an allowed field. */
        public final boolean covered;
        /** Fields that matched at least one query word. */
        public final Set<Field> fields;
        /** A prefix or a typo was needed ("похоже"). */
        public final boolean fuzzy;

        Match(Tier tier, int score, boolean covered, Set<Field> fields, boolean fuzzy) {
            this.tier = tier;
            this.score = score;
            this.covered = covered;
            this.fields = fields;
            this.fuzzy = fuzzy;
        }
    }

    private SearchMatcher() {
    }

    public static Match score(Card card, SearchRequest request) {
        List<String> q = TextNorm.tokens(request.text);
        if (q.size() > MAX_QUERY_WORDS) {
            q = q.subList(0, MAX_QUERY_WORDS);
        }
        if (q.isEmpty()) {
            return Match.NONE;
        }
        double[] weight = new double[FIELDS.length];
        for (Field f : FIELDS) {
            weight[f.ordinal()] = request.isAnywhere() ? ANYWHERE_WEIGHT[f.ordinal()]
                    : request.scope.contains(f) ? 1.0 : 0;
        }
        // columns: the words of the allowed fields in field order; only words some query word can take
        List<int[]> columns = new ArrayList<>();
        List<double[]> quality = new ArrayList<>();
        for (Field f : FIELDS) {
            if (weight[f.ordinal()] == 0) {
                continue;
            }
            List<String> words = card.words.get(f.ordinal());
            for (int j = 0; j < words.size(); j++) {
                double[] col = new double[q.size()];
                boolean any = false;
                for (int i = 0; i < q.size(); i++) {
                    col[i] = quality(q.get(i), words.get(j));
                    any |= col[i] > 0;
                }
                if (any) {
                    columns.add(new int[]{f.ordinal(), j});
                    quality.add(col);
                }
            }
        }
        long[][] w = new long[q.size()][columns.size()];
        for (int c = 0; c < columns.size(); c++) {
            for (int i = 0; i < q.size(); i++) {
                double qq = quality.get(c)[i];
                w[i][c] = qq > 0 ? 1000 + Math.round(100 * qq * weight[columns.get(c)[0]]) : 0;
            }
        }
        int[] assigned = maxWeightMatching(w, columns.size());

        boolean[] significant = new boolean[q.size()];
        int sig = 0;
        for (int i = 0; i < q.size(); i++) {
            significant[i] = TextNorm.isSignificant(q.get(i));
            sig += significant[i] ? 1 : 0;
        }
        if (sig == 0) {
            Arrays.fill(significant, true);
        }
        boolean covered = true;
        boolean exact = true;
        double base = 0;
        EnumSet<Field> fields = EnumSet.noneOf(Field.class);
        int phraseField = -1;
        int prevWord = -1;
        boolean phrase = true;
        int matched = 0;
        for (int i = 0; i < q.size(); i++) {
            if (assigned[i] < 0) {
                covered &= !significant[i];
                continue;
            }
            int[] col = columns.get(assigned[i]);
            double qq = quality.get(assigned[i])[i];
            base += qq * weight[col[0]];
            exact &= qq == 1.0;
            fields.add(FIELDS[col[0]]);
            if (matched == 0) {
                phraseField = col[0];
            } else if (col[0] != phraseField || col[1] != prevWord + 1) {
                phrase = false;
            }
            prevWord = col[1];
            matched++;
        }
        String key = TextNorm.join(q);
        boolean whole = false;
        for (Field f : FIELDS) {
            whole |= weight[f.ordinal()] > 0 && card.equalsWhole(f, key);
        }
        // one matched word is a phrase too (tier B for "Кинг" as the author), but earns the phrase bonus only from two
        double bonus = (phrase && matched >= 2 ? 8 : 0) + (whole ? 10 : 0)
                + (authorDashTitle(request.text, q, assigned, columns) ? 3 : 0);
        int score = (int) Math.min(100, Math.round(base / q.size() * 100 + bonus));
        Tier tier = whole ? Tier.A
                : !covered || matched == 0 ? Tier.E
                : exact && phrase ? Tier.B
                : exact ? Tier.C
                : Tier.D;
        return new Match(tier, score, covered && matched > 0, Collections.unmodifiableSet(fields), !exact);
    }

    /** Quality of query word {@code t} matching card word {@code w}: 1, 0.9, 0.8, 0.65 or 0 (see the class comment). */
    static double quality(String t, String w) {
        if (w.equals(t)) {
            return 1.0;
        }
        if (TextNorm.isNumber(t) || TextNorm.isNumber(w)) {
            return 0;
        }
        if (t.length() >= 4 && w.startsWith(t)) {
            return 0.9;
        }
        if (t.length() >= 5 && StringSim.osa(t, w, 1) == 1) {
            return 0.8;
        }
        if (t.length() >= 9 && StringSim.osa(t, w, 2) == 2) {
            return 0.65;
        }
        return 0;
    }

    /** "Стивен Кинг — Оно": the words left of a spaced dash went to the author, the ones right of it to the title. */
    private static boolean authorDashTitle(String text, List<String> q, int[] assigned, List<int[]> columns) {
        int dash = -1;
        for (String d : new String[]{" — ", " – ", " - "}) {
            dash = dash < 0 ? text.indexOf(d) : dash;
        }
        if (dash < 0) {
            return false;
        }
        int left = TextNorm.tokens(text.substring(0, dash)).size();
        if (left == 0 || left >= q.size()) {
            return false;
        }
        boolean author = false;
        boolean title = false;
        for (int i = 0; i < q.size(); i++) {
            if (assigned[i] < 0) {
                continue;
            }
            Field got = FIELDS[columns.get(assigned[i])[0]];
            if (got != (i < left ? Field.AUTHOR : Field.TITLE)) {
                return false;
            }
            author |= got == Field.AUTHOR;
            title |= got == Field.TITLE;
        }
        return author && title; // both sides found: an author's other book earns nothing
    }

    /**
     * The column given to each row ({@code -1}: none) in a matching of greatest total weight; a zero weight is no edge.
     * Kuhn–Munkres on costs {@code -w}, padded with empty columns so that every row can be placed.
     */
    static int[] maxWeightMatching(long[][] w, int columns) {
        int n = w.length;
        int m = Math.max(columns, n);
        long[] u = new long[n + 1];
        long[] v = new long[m + 1];
        int[] p = new int[m + 1];
        int[] way = new int[m + 1];
        for (int i = 1; i <= n; i++) {
            p[0] = i;
            int j0 = 0;
            long[] minv = new long[m + 1];
            Arrays.fill(minv, Long.MAX_VALUE);
            boolean[] used = new boolean[m + 1];
            do {
                used[j0] = true;
                int i0 = p[j0];
                long delta = Long.MAX_VALUE;
                int j1 = 0;
                for (int j = 1; j <= m; j++) {
                    if (!used[j]) {
                        long cost = j <= columns ? -w[i0 - 1][j - 1] : 0;
                        long cur = cost - u[i0] - v[j];
                        if (cur < minv[j]) {
                            minv[j] = cur;
                            way[j] = j0;
                        }
                        if (minv[j] < delta) {
                            delta = minv[j];
                            j1 = j;
                        }
                    }
                }
                for (int j = 0; j <= m; j++) {
                    if (used[j]) {
                        u[p[j]] += delta;
                        v[j] -= delta;
                    } else {
                        minv[j] -= delta;
                    }
                }
                j0 = j1;
            } while (p[j0] != 0);
            do {
                int j1 = way[j0];
                p[j0] = p[j1];
                j0 = j1;
            } while (j0 != 0);
        }
        int[] out = new int[n];
        Arrays.fill(out, -1);
        for (int j = 1; j <= columns; j++) {
            if (p[j] != 0 && w[p[j] - 1][j - 1] > 0) {
                out[p[j] - 1] = j - 1;
            }
        }
        return out;
    }
}
