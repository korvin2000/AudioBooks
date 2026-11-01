package com.fanok.audiobooks.search;

import com.fanok.audiobooks.pojo.BookPOJO;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The last search, as it was left (search-refactoring.md §8): the request, every card with its source, each source's
 * last answer and next page, and the screen's tab, order and grouping. Kept in memory for the life of the process (a
 * tap on an author's chip closes the search; coming back shows it at once) and in one JSON file in the app's files,
 * written whole to a temporary file and renamed, by one writer off the main thread. Groups and scores are not saved:
 * they are recomputed from the cards.
 */
public final class SearchSnapshot {

    static final int SCHEMA = 1;
    /** Cards kept at most; beyond them a source loses its last cards and its next page (no holes on "more"). */
    static final int MAX_OFFERS = 1500;
    /** Older snapshots keep only the request. */
    public static final long MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000;

    public static final class Source {
        public String name;
        public String status;
        public int count;
        /** The page to load next; null when the source has no more. */
        public Integer nextPage;
        /** The query it goes on with, when not the request's (the other letter case); null: the request's. */
        public String query;
        /** It already had its retry in the other letter case. */
        public boolean caseRetried;
    }

    public static final class Card {
        public String source;
        public int rank;
        public BookPOJO book;
    }

    public int schema = SCHEMA;
    public long savedAt;
    public String text = "";
    public List<String> scope = new ArrayList<>();
    /** The tab's source name; null for "All". */
    public String tab;
    public String sort;
    public boolean grouped = true;
    /** Some cards did not fit and were left out. */
    public boolean truncated;
    public List<Source> sources = new ArrayList<>();
    public List<Card> cards = new ArrayList<>();
    /** The author and series chips of the search, so that a restored one shows them without a request. */
    public com.fanok.audiobooks.pojo.SearcheblPOJO people;

    /** Leaves out the cards beyond {@link #MAX_OFFERS}, from the end of the sources with most, and their next pages. */
    void truncate() {
        while (cards.size() > MAX_OFFERS) {
            truncated = true;
            Source biggest = null;
            int most = 0;
            for (Source s : sources) {
                int n = 0;
                for (Card c : cards) {
                    n += c.source.equals(s.name) ? 1 : 0;
                }
                if (n > most) {
                    most = n;
                    biggest = s;
                }
            }
            if (biggest == null) {
                cards = new ArrayList<>(cards.subList(0, MAX_OFFERS));
                return;
            }
            int over = cards.size() - MAX_OFFERS;
            for (int i = cards.size() - 1; i >= 0 && over > 0; i--) {
                if (cards.get(i).source.equals(biggest.name)) {
                    cards.remove(i);
                    over--;
                    biggest.count = Math.max(0, biggest.count - 1);
                }
            }
            biggest.nextPage = null; // its continuation would skip the cards left out
        }
    }

    /** The memory and file copy of the last search. */
    public static final class Store {

        private static volatile SearchSnapshot memory;
        private static final ExecutorService WRITER = Executors.newSingleThreadExecutor();
        private static final Gson GSON = new Gson();
        private final File file;

        public Store(File dir) {
            this.file = new File(dir, "last.json");
        }

        /** The last search: from memory, else from the file; null when there is none or it cannot be read. */
        public SearchSnapshot load(long now) {
            SearchSnapshot s = memory;
            if (s == null) {
                s = read(file);
                memory = s;
            }
            if (s != null && now - s.savedAt > MAX_AGE_MS) {
                SearchSnapshot old = new SearchSnapshot();
                old.text = s.text;
                old.scope = s.scope;
                old.savedAt = s.savedAt;
                return old;
            }
            return s;
        }

        /** Keeps {@code s} in memory at once and writes it in the background. */
        public void save(final SearchSnapshot s) {
            s.truncate();
            memory = s;
            WRITER.execute(() -> write(s, file));
        }

        public void clear() {
            memory = null;
            WRITER.execute(() -> {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            });
        }

        static SearchSnapshot read(File file) {
            if (!file.isFile()) {
                return null;
            }
            try (Reader r = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                SearchSnapshot s = GSON.fromJson(r, SearchSnapshot.class);
                if (s == null || s.schema != SCHEMA || s.text == null || s.cards == null || s.sources == null) {
                    return null;
                }
                for (Card c : s.cards) {
                    if (c == null || c.book == null || c.book.getUrl() == null || c.book.getName() == null
                            || c.source == null) {
                        return null;
                    }
                }
                return s;
            } catch (IOException | JsonParseException | IllegalStateException e) {
                return null;
            }
        }

        static void write(SearchSnapshot s, File file) {
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
                return;
            }
            File tmp = new File(file.getPath() + ".tmp");
            try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
                GSON.toJson(s, w);
            } catch (IOException e) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return;
            }
            if (!tmp.renameTo(file)) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(file);
            }
        }

        /** For tests: forget the copy in memory. */
        static void forgetMemory() {
            memory = null;
        }
    }
}
