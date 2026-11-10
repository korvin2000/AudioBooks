package com.fanok.audiobooks.sync;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.pojo.FavoritePersonPOJO;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiPredicate;
import java.util.function.BinaryOperator;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The synced state of one device: the cloud file, and the device's copy of what it last agreed on with the cloud.
 * Every collection is a map by a natural key, merged three-way against that copy, so a deletion spreads without
 * tombstones or schema changes: a key the base had and one side dropped is dropped. Pure Java, tested on the JVM.
 */
final class Snapshot {

    static final int VERSION = 1;

    /** Present in every document: one missing makes the file unreadable rather than a deletion of its content. */
    private static final List<String> SECTIONS = Arrays.asList("seq", "books", "fav", "hist", "people", "pos", "prefs");

    private static final Gson GSON = new Gson();

    /** A favourite, or a favourite person, is the same as long as it is there: its metadata never spreads alone. */
    private static final BiPredicate<Object, Object> PRESENT = (a, b) -> true;

    int v = VERSION;
    /** Writes of the file per device (a vector clock): whether the cloud copy has seen every write a base saw. */
    Map<String, Integer> seq = new TreeMap<>();
    /** When the file was written (epoch ms). */
    long at;
    /** Metadata of the books of {@link #fav} and {@link #hist}, without the annotation the book page reloads. */
    Map<String, BookPOJO> books = new TreeMap<>();
    /** Favourite books, newest first; the value is unused. */
    Map<String, Long> fav = new LinkedHashMap<>();
    /** When each book page of the history was last opened (epoch ms, 0 unknown). */
    Map<String, Long> hist = new LinkedHashMap<>();
    /** Favourite authors and readers by {@link #person}. */
    Map<String, FavoritePersonPOJO> people = new LinkedHashMap<>();
    /** Where each book was left. */
    Map<String, Pos> pos = new TreeMap<>();
    /** Synced settings as text, typed and filtered by CloudSync. */
    Map<String, String> prefs = new TreeMap<>();
    /** Members a newer version of the format added: written back as they came, so an older app does not drop them. */
    transient JsonObject rest;
    /** {@link #seq} lost an entry of the wrong form: it no longer tells what this copy has seen. */
    transient boolean torn;

    /** The place of a book: chapter by its position in the book's list, offset, when it was reached, chapter title. */
    static final class Pos {
        int c;
        long ms;
        long t;
        @Nullable
        String n;

        Pos() {
        }

        Pos(int c, long ms, long t, @Nullable String n) {
            this.c = c;
            this.ms = ms;
            this.t = t;
            this.n = n;
        }

        /** The very start of the book: where a book only opened, never played, stands. */
        boolean start() {
            return c == 0 && ms == 0;
        }

        /** The same place, whenever and however titled: a place reached anew is no change. */
        @Override
        public boolean equals(Object o) {
            return o instanceof Pos && ((Pos) o).c == c && ((Pos) o).ms == ms;
        }

        @Override
        public int hashCode() {
            return 31 * c + Long.hashCode(ms);
        }
    }

    /** The cloud file wrote a newer format than this app reads: it must not be overwritten. */
    static final class NewerException extends Exception {
        NewerException(int version) {
            super("Sync file version " + version);
        }
    }

    /** The key of a person in {@link #people}. */
    @NonNull
    static String person(int kind, @Nullable String key) {
        return kind + "|" + key;
    }

    /**
     * {@code mine} and {@code theirs} merged against {@code base}, the state both last agreed on (empty when unknown:
     * the merge is then a union). A key changed on one side only takes that change, a deletion included; changed on
     * both, an edit beats a deletion, a later opening or place beats an earlier one (a place past the book's start
     * beats its start), and the cloud's setting wins (a new device's defaults never override the user's settings).
     * Order and book metadata come from {@code mine} first; {@link #seq} is left to the caller.
     */
    @NonNull
    static Snapshot merge(@NonNull Snapshot base, @NonNull Snapshot mine, @NonNull Snapshot theirs) {
        Snapshot out = new Snapshot();
        out.fav = merge(base.fav, mine.fav, theirs.fav, PRESENT, (a, b) -> b);
        out.hist = merge(base.hist, mine.hist, theirs.hist, Long::equals, Math::max);
        out.people = merge(base.people, mine.people, theirs.people, PRESENT, (a, b) -> b);
        // a book only opened on one side stands at its start, stamped when opened: the place reached on the other wins
        out.pos = merge(base.pos, mine.pos, theirs.pos, Pos::equals,
                (a, b) -> a.start() != b.start() ? (a.start() ? b : a) : a.t > b.t ? a : b);
        out.prefs = merge(base.prefs, mine.prefs, theirs.prefs, String::equals, (a, b) -> b);
        Set<String> urls = new HashSet<>(out.fav.keySet());
        urls.addAll(out.hist.keySet());
        for (String url : urls) {
            BookPOJO book = mine.books.get(url);
            book = book != null ? book : theirs.books.get(url);
            book = book != null ? book : base.books.get(url);
            if (book != null) {
                out.books.put(url, book);
            }
        }
        out.rest = theirs.rest;
        return out;
    }

    static <V> Map<String, V> merge(Map<String, V> base, Map<String, V> mine, Map<String, V> theirs,
            BiPredicate<? super V, ? super V> same, BinaryOperator<V> both) {
        Set<String> keys = new LinkedHashSet<>(mine.keySet());
        keys.addAll(theirs.keySet());
        keys.addAll(base.keySet());
        Map<String, V> out = new LinkedHashMap<>();
        for (String key : keys) {
            V m = mine.get(key), t = theirs.get(key), b = base.get(key);
            V v = same(m, t, same) || same(t, b, same) ? m
                    : same(m, b, same) ? t
                    : m == null ? t : t == null ? m : both.apply(m, t);
            if (v != null) {
                out.put(key, v);
            }
        }
        return out;
    }

    private static <V> boolean same(@Nullable V a, @Nullable V b, BiPredicate<? super V, ? super V> same) {
        return a == null ? b == null : b != null && same.test(a, b);
    }

    /** Whether {@code o} holds the same data, as the merge compares it: nothing to upload. */
    boolean sameData(@NonNull Snapshot o) {
        return sameMap(fav, o.fav, PRESENT) && sameMap(hist, o.hist, Long::equals)
                && sameMap(people, o.people, PRESENT) && sameMap(pos, o.pos, Pos::equals)
                && sameMap(prefs, o.prefs, String::equals);
    }

    /** A copy whose maps change on their own. */
    @NonNull
    Snapshot copy() {
        Snapshot s = new Snapshot();
        s.seq.putAll(seq);
        s.at = at;
        s.books.putAll(books);
        s.fav.putAll(fav);
        s.hist.putAll(hist);
        s.people.putAll(people);
        s.pos.putAll(pos);
        s.prefs.putAll(prefs);
        s.rest = rest;
        return s;
    }

    private static <V> boolean sameMap(Map<String, V> a, Map<String, V> b, BiPredicate<? super V, ? super V> same) {
        if (!a.keySet().equals(b.keySet())) {
            return false;
        }
        for (Map.Entry<String, V> e : a.entrySet()) {
            if (!same.test(e.getValue(), b.get(e.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether this copy has seen every write {@code base} had seen, so base is a common ancestor of both sides. Not
     * so when the file was deleted or recreated, belongs to another account, or a parallel write replaced ours.
     */
    boolean descends(@NonNull Snapshot base) {
        if (torn || base.torn) {
            return false;
        }
        for (Map.Entry<String, Integer> e : base.seq.entrySet()) {
            Integer n = seq.get(e.getKey());
            if (n == null || n < e.getValue()) {
                return false;
            }
        }
        return true;
    }

    /** Stamps a write by {@code device} that follows every write the {@code seen} copies (null: none) had seen. */
    void count(@NonNull String device, @Nullable Snapshot... seen) {
        for (Snapshot s : seen) {
            if (s != null) {
                for (Map.Entry<String, Integer> e : s.seq.entrySet()) {
                    seq.merge(e.getKey(), e.getValue(), Math::max);
                }
            }
        }
        seq.merge(device, 1, Integer::sum);
    }

    private static <V> void section(JsonObject json, String name, Class<V> type, Map<String, V> into) {
        for (Map.Entry<String, JsonElement> e : json.getAsJsonObject(name).entrySet()) {
            try {
                V value = GSON.fromJson(e.getValue(), type);
                if (value != null) {
                    into.put(e.getKey(), value);
                }
            } catch (RuntimeException ignored) {
                // an entry of another form: dropped, the others kept
            }
        }
    }

    /** gzip-compressed JSON. */
    @NonNull
    byte[] toBytes() throws IOException {
        JsonObject json = GSON.toJsonTree(this).getAsJsonObject();
        if (rest != null) {
            for (Map.Entry<String, JsonElement> e : rest.entrySet()) {
                json.add(e.getKey(), e.getValue());
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Writer writer = new OutputStreamWriter(new GZIPOutputStream(out), StandardCharsets.UTF_8)) {
            writer.write(json.toString());
        }
        return out.toByteArray();
    }

    /**
     * The document in {@code data} (gzip or plain JSON), or null when it is none: unreadable, or missing a section.
     * An entry of the wrong form is dropped alone, so one bad entry never costs the rest of the file.
     *
     * @throws NewerException when a newer app wrote it
     */
    @Nullable
    static Snapshot parse(@NonNull byte[] data) throws NewerException {
        JsonObject json;
        try {
            InputStream in = new ByteArrayInputStream(data);
            // a server may already have inflated it (Content-Encoding): gunzip only what is gzip
            if (data.length > 1 && (data[0] & 0xff) == 0x1f && (data[1] & 0xff) == 0x8b) {
                in = new GZIPInputStream(in);
            }
            json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            int version = json.get("v").getAsInt();
            if (version > VERSION) {
                throw new NewerException(version);
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        for (String section : SECTIONS) {
            if (!(json.get(section) instanceof JsonObject)) {
                return null;
            }
        }
        Snapshot s = new Snapshot();
        s.at = json.get("at") instanceof JsonPrimitive && json.getAsJsonPrimitive("at").isNumber()
                ? json.get("at").getAsLong() : 0;
        section(json, "seq", Integer.class, s.seq);
        s.torn = s.seq.size() != json.getAsJsonObject("seq").size();
        section(json, "books", BookPOJO.class, s.books);
        section(json, "fav", Long.class, s.fav);
        section(json, "hist", Long.class, s.hist);
        section(json, "people", FavoritePersonPOJO.class, s.people);
        section(json, "pos", Pos.class, s.pos);
        section(json, "prefs", String.class, s.prefs);
        s.rest = new JsonObject();
        for (Map.Entry<String, JsonElement> e : json.entrySet()) {
            if (!e.getKey().equals("v") && !e.getKey().equals("at") && !SECTIONS.contains(e.getKey())) {
                s.rest.add(e.getKey(), e.getValue());
            }
        }
        return s;
    }
}
