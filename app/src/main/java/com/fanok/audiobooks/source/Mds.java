package com.fanok.audiobooks.source;

import static com.fanok.audiobooks.source.Json.arrayOf;
import static com.fanok.audiobooks.source.Json.string;

import androidx.annotation.Nullable;

import com.fanok.audiobooks.BookRemovedException;
import com.fanok.audiobooks.model.OtherSourceModel;
import com.fanok.audiobooks.net.JsonSlice;
import com.fanok.audiobooks.pojo.AudioPOJO;
import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.pojo.DescriptionPOJO;
import com.fanok.audiobooks.pojo.GenrePOJO;
import com.fanok.audiobooks.search.PeopleIndex;
import com.fanok.audiobooks.search.ProviderPage;
import com.fanok.audiobooks.search.SearchUrls;
import com.fanok.audiobooks.source.Players.Chapters;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * mds-old.ru (probed live, 2026-11-21): an unofficial archive of the radio programme «Модель для сборки» (~1460
 * works), Next.js (app router) behind nginx, plain GETs, no wall, cookies or Referer. Every page is rendered on the
 * server with its data in the React Server Components payload, the {@code self.__next_f.push([1,"…"])} strings of
 * its scripts ({@link #payload}); a string of 1024 chars or more (a long annotation) stands outside its JSON as a text
 * row {@code <id>:T<hex UTF-8 length>,<text>} referenced as {@code "$<id>"} ({@link #text}).
 * <ul>
 * <li>listings: {@link #INDEX} (new), {@link #RATING}, a genre {@code /?cat=Жанры/поджанры:<name>} and the search
 * {@code /?q=Q}, each paged with {@code &page=N}: 20 works in the props {@code {"initial":[…],"hasMore":…}}. The
 * plain {@code /} ("Для вас") is no sort of its own (a guest gets the rating order). A genre and a search are ranked
 * by rating alone: the server drops {@code sort} there. "Случайная" is one work (a server action), not a list.</li>
 * <li>genres: the "Жанры/поджанры" facet (48 values with counts, a sub-genre names its parent) of the props
 * {@code {"categories":[…]}} on every listing; the other facets (place, time, plot ...) are not genres.</li>
 * <li>author {@code /author/<Name_Slug>}: all their works at once in {@code {"works":[…]}}. No index of authors (the
 * built-in list of tools/people links these pages) and no readers anywhere (most works are read by Влад Копп).</li>
 * <li>search {@code /?q=}: the words as one phrase (case and punctuation aside) in the titles, authors and texts, so
 * a title with its author finds nothing ({@link #otherSourceTerms}).</li>
 * <li>book {@code /author/<first author's slug>/<name, spaces as _>}, each segment encoded ({@link #workUrl}: the
 * site's own raw {@code /} inside a name is a 404, {@code %2F} works): the first {@code "work":{…}} of the page, then
 * "Ещё у автора" and the similar works. Its {@code parts} (intro, story, music breaks, afterword) are time ranges of
 * one MP3, none for a work in one piece. {@code /api/play/<id>} (the page's download link) answers 302 to the signed
 * {@code /mp3/<file>.mp3?md5=&expires=} (~24 h, the same that the player's {@code /api/audio-url/<id>} gives), which
 * serves ranges without a Referer: a chapter URL that never expires. A work removed for its rights holder
 * ({@code rightsBlocked}, "Убрано по просьбе правообладателя") or a Soundstream episode ({@code isSoundstream})
 * has no recording here.</li>
 * </ul>
 */
public final class Mds implements SourceParser {

    public static final String SERVER = "https://mds-old.ru";
    public static final String INDEX = SERVER + "/?sort=new";
    public static final String RATING = SERVER + "/?sort=rating";
    /** The home page: its filter facets hold the genres. */
    public static final String GENRES = SERVER + "/";
    /** No index on the site: the built-in list of its author pages (tools/people, row MDS); no readers named. */
    public static final String AUTHORS = SERVER + PeopleIndex.AUTHORS;
    public static final String READERS = null;

    private static final String GENRE_FACET = "Жанры/поджанры";
    private static final String PUSH = "self.__next_f.push(";
    private static final String WORK = "\"work\":{";
    private static final List<Sort.Entry> MENU = Sort.menu(Sort.NEW, Sort.RATING);
    /** A genre, a search, an author: one order, the server's. */
    private static final Pattern UNSORTED = Pattern.compile("[?&](cat|q)=|/author/");
    private static final Pattern PAGE = Pattern.compile("[?&]page=\\d+$");
    private static final Pattern SPACE = Pattern.compile("[\\s\\u00A0]");
    private static final Pattern LINE_BREAK = Pattern.compile("\\s*\\n\\s*");
    private static final Pattern REF = Pattern.compile("\\$([0-9a-f]+)");

    // ---------------------------------------------------------------- SourceParser

    @Override
    public ProviderPage<BookPOJO> listing(Document page, String url, int pageNo) {
        return parseListing(page);
    }

    /** {@code page=N} last in the query, in place of the one there; page 1 is the listing itself. */
    @Override
    public String pageUrl(String url, int page) {
        String base = PAGE.matcher(url).replaceFirst("");
        if (page <= 1 && base.equals(url)) {
            return url;
        }
        return base + (base.indexOf('?') < 0 ? "?" : "&") + "page=" + page;
    }

    @Override
    public List<Sort.Entry> sorts(String url) {
        return UNSORTED.matcher(url).find() ? Collections.<Sort.Entry>emptyList() : MENU;
    }

    @Override
    public String sortUrl(Sort sort, String listingUrl) {
        switch (sort) {
            case NEW: return INDEX;
            case RATING: return RATING;
            default: return null;
        }
    }

    @Override
    public ProviderPage<GenrePOJO> genres(Document page, String url, int pageNo) {
        return ProviderPage.of(parseGenres(page), false);
    }

    /** {@code /?q=Q&page=N}, page 1 included (the server takes it as the bare query). */
    @Override
    public String searchUrl(SearchUrls.Category category, String query, int page) {
        return category == SearchUrls.Category.BOOKS
                ? SERVER + "/?q=" + SearchUrls.enc(query) + "&page=" + page : null;
    }

    @Override
    public DescriptionPOJO description(Document page, String bookUrl) {
        return parseDescription(page);
    }

    @Override
    public ArrayList<BookPOJO> recommendations(Document page, String bookUrl) {
        return parseRecommendations(page);
    }

    @Override
    public ArrayList<AudioPOJO> tracks(Document page, String base) {
        return parseTracks(page, base);
    }

    /** The title alone: the search takes the words as one phrase, and "Муза Энтони Берджесс" is in no work. */
    @Override
    public String otherSourceTerms(BookPOJO book) {
        return OtherSourceModel.query(book.getName());
    }

    /** The chapters are parts of one MP3 of the work. */
    @Override
    public boolean downloadsSharedFiles() {
        return true;
    }

    // ---------------------------------------------------------------- pure parsers

    /** The index, the rating, a genre, a search (20 works and {@code hasMore}) or an author (all works). */
    public static ProviderPage<BookPOJO> parseListing(Document page) {
        String payload = payload(page);
        String key = payload.contains("{\"initial\":") ? "initial" : "works";
        JsonObject props = props(payload, key);
        if (props == null) {
            throw new IllegalStateException("no works on " + page.location());
        }
        JsonArray works = arrayOf(props, key);
        return ProviderPage.of(cards(payload, works), flag(props, "hasMore"), works.size());
    }

    /** The "Жанры/поджанры" facet: name, listing and number of works; a sub-genre is described by its parent. */
    public static ArrayList<GenrePOJO> parseGenres(Document page) {
        JsonObject props = props(payload(page), "categories");
        if (props == null) {
            throw new IllegalStateException("no genre facet on " + page.location());
        }
        ArrayList<GenrePOJO> genres = new ArrayList<>();
        for (JsonElement group : arrayOf(props, "categories")) {
            if (!GENRE_FACET.equals(string(group.getAsJsonObject(), "title"))) {
                continue;
            }
            for (JsonElement item : arrayOf(group.getAsJsonObject(), "values")) {
                JsonObject value = item.getAsJsonObject();
                String name = string(value, "value");
                if (name.isEmpty()) {
                    continue;
                }
                GenrePOJO genre = new GenrePOJO(name, genreUrl(name));
                genre.setReting(Json.seconds(value.get("count")));
                if (!string(value, "parent").isEmpty()) {
                    genre.setDescription(string(value, "parent"));
                }
                genres.add(genre);
            }
        }
        return genres;
    }

    /** The book page; an empty one (no title) for a page without a work. */
    public static DescriptionPOJO parseDescription(Document page) {
        String payload = payload(page);
        JsonObject w = work(payload);
        DescriptionPOJO d = new DescriptionPOJO();
        if (w == null) {
            return d;
        }
        d.setTitle(string(w, "name"));
        d.setPoster(cover(w));
        d.setAutor(string(w, "author"));
        d.setAutorUrl(authorUrl(w));
        d.setTime(time(w));
        d.setGenre(genres(w));
        d.setGenreUrl(genreUrl(w));
        int votes = Json.seconds(w.get("listenerVotes"));
        d.setReiting(votes > 0
                ? BookPOJO.formatRating((float) number(w, "listenerRating"), String.valueOf(votes)) : "");
        d.setDescription(LINE_BREAK.matcher(text(payload, w, "annotation").trim()).replaceAll("\n"));
        return d;
    }

    /** "Ещё у автора" and the similar works: every work of the page after its own. */
    public static ArrayList<BookPOJO> parseRecommendations(Document page) {
        String payload = payload(page);
        JsonArray others = new JsonArray();
        Set<String> ids = new HashSet<>();
        for (int at = payload.indexOf(WORK); at >= 0; at = payload.indexOf(WORK, at + 1)) {
            JsonObject w = Json.object(JsonSlice.from(payload, at + WORK.length() - 1, '{'));
            if (ids.add(string(w, "id")) && ids.size() > 1) {
                others.add(w);
            }
        }
        return cards(payload, others);
    }

    /** The parts of the work's MP3 ({@link #chapters}); a page without a work has no player. */
    public static ArrayList<AudioPOJO> parseTracks(Document page, String bookUrl) {
        JsonObject w = work(payload(page));
        if (w == null) {
            throw new IllegalStateException("no work on " + bookUrl);
        }
        return chapters(w, bookUrl);
    }

    /**
     * One chapter per part of the MP3 (its time range), or the whole file for a work without parts. A work removed
     * for its rights holder or one of Soundstream's is a {@link BookRemovedException}; one without a recording an
     * {@link IllegalStateException}.
     */
    static ArrayList<AudioPOJO> chapters(JsonObject w, String bookUrl) {
        if (flag(w, "rightsBlocked") || flag(w, "isSoundstream")) {
            throw new BookRemovedException(bookUrl);
        }
        String id = string(w, "id");
        if (id.isEmpty() || string(w, "audioUrl").isEmpty()) {
            throw new IllegalStateException("no recording on " + bookUrl);
        }
        String mp3 = SERVER + "/api/play/" + id;
        Chapters chapters = new Chapters(string(w, "name"), bookUrl);
        JsonElement parts = w.get("parts");
        if (parts != null && parts.isJsonArray()) {
            chapters.each(parts.getAsJsonArray(), item -> {
                JsonObject part = item.getAsJsonObject();
                int start = (int) Math.floor(number(part, "start"));
                int end = (int) Math.ceil(number(part, "end"));
                if (end <= start) {
                    return null;
                }
                AudioPOJO audio = chapters.chapter(string(part, "title"), mp3, end - start);
                audio.setTimeStart(start);
                audio.setTimeFinish(end);
                return audio;
            });
        }
        if (chapters.isEmpty()) {
            chapters.add(chapters.chapter(string(w, "name"), mp3, Json.seconds(w.get("duration"))));
        }
        return chapters.done("mds work " + id);
    }

    // ---------------------------------------------------------------- cards and links

    private static ArrayList<BookPOJO> cards(String payload, JsonArray works) {
        ArrayList<BookPOJO> books = new ArrayList<>();
        for (JsonElement work : works) {
            try {
                BookPOJO book = card(payload, work.getAsJsonObject());
                if (!book.isNull()) {
                    books.add(book);
                }
            } catch (RuntimeException odd) {
                // one odd work is skipped, never the page
            }
        }
        return books;
    }

    private static BookPOJO card(String payload, JsonObject w) {
        BookPOJO book = new BookPOJO();
        book.setUrl(workUrl(w));
        book.setName(string(w, "name"));
        book.setAutor(string(w, "author"));
        book.setUrlAutor(authorUrl(w));
        book.setPhoto(cover(w));
        book.setTime(time(w));
        book.setGenre(genres(w));
        book.setUrlGenre(genreUrl(w));
        if (Json.seconds(w.get("listenerVotes")) > 0) {
            book.setReting(String.format(Locale.ROOT, "%.1f", number(w, "listenerRating")));
        }
        book.setDesc(text(payload, w, "annotation"));
        return book;
    }

    /** {@code /author/<first author's slug>/<name with its spaces as _>}, as the site links a work. */
    static String workUrl(JsonObject w) {
        return authorUrl(w) + "/" + segment(SPACE.matcher(string(w, "name")).replaceAll("_"));
    }

    private static String authorUrl(JsonObject w) {
        return SERVER + "/author/" + segment(string(arrayOf(w, "authors").get(0).getAsJsonObject(), "slug"));
    }

    /** A path segment encoded as the site's links are (encodeURIComponent: {@code ( ) ! ' ~} kept). */
    private static String segment(String text) {
        return SearchUrls.segment(text).replace("%28", "(").replace("%29", ")").replace("%21", "!")
                .replace("%27", "'").replace("%7E", "~");
    }

    private static String genreUrl(String name) {
        return SERVER + "/?cat=" + SearchUrls.enc(GENRE_FACET + ":" + name);
    }

    /** The listing of the work's first genre; "" without one. */
    private static String genreUrl(JsonObject w) {
        JsonArray genres = genreList(w);
        return genres.size() == 0 ? "" : genreUrl(string(genres.get(0)));
    }

    /** All of the work's genres ("Фантастика, «Твёрдая» научная фантастика"). */
    private static String genres(JsonObject w) {
        StringBuilder out = new StringBuilder();
        for (JsonElement genre : genreList(w)) {
            out.append(out.length() == 0 ? "" : ", ").append(string(genre));
        }
        return out.toString();
    }

    private static JsonArray genreList(JsonObject w) {
        JsonElement params = w.get("params");
        JsonElement genres = params != null && params.isJsonObject() ? params.getAsJsonObject().get(GENRE_FACET) : null;
        return genres != null && genres.isJsonArray() ? genres.getAsJsonArray() : new JsonArray();
    }

    private static String cover(JsonObject w) {
        String path = string(w, "coverUrl");
        return path.startsWith("/") ? SERVER + path : path;
    }

    /** H:MM:SS of the work's seconds; "" for none. */
    private static String time(JsonObject w) {
        int s = Json.seconds(w.get("duration"));
        return s == 0 ? "" : String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
    }

    // ---------------------------------------------------------------- the RSC payload

    /** The page's React Server Components payload: its {@code self.__next_f.push([1,"…"])} strings in order. */
    static String payload(Document page) {
        StringBuilder out = new StringBuilder();
        for (Element script : page.select("script")) {
            String js = script.data();
            int at = js.indexOf(PUSH);
            int end = js.lastIndexOf(')');
            if (at < 0 || end < at) {
                continue;
            }
            JsonArray chunk = Json.array(js.substring(at + PUSH.length(), end));
            if (chunk.size() > 1 && Json.seconds(chunk.get(0)) == 1) {
                out.append(string(chunk.get(1)));
            }
        }
        return out.toString();
    }

    /** The props object whose first key is {@code key} ({@code {"initial":[…],"hasMore":…}}); null without one. */
    @Nullable
    static JsonObject props(String payload, String key) {
        int at = payload.indexOf("{\"" + key + "\":");
        return at < 0 ? null : Json.object(JsonSlice.from(payload, at, '{'));
    }

    /** The book page's own work: its first {@code "work":{…}}; null without one. */
    @Nullable
    static JsonObject work(String payload) {
        int at = payload.indexOf(WORK);
        return at < 0 ? null : Json.object(JsonSlice.from(payload, at + WORK.length() - 1, '{'));
    }

    /**
     * A string field, read through React's escapes: {@code "$$…"} is a text starting with {@code $}, {@code "$<id>"}
     * a long text in the row {@code <id>} ({@link #textRow}), any other {@code "$…"} a value that is no text.
     */
    static String text(String payload, JsonObject o, String key) {
        String s = string(o, key);
        if (!s.startsWith("$")) {
            return s;
        }
        if (s.startsWith("$$")) {
            return s.substring(1);
        }
        Matcher ref = REF.matcher(s);
        return ref.matches() ? textRow(payload, ref.group(1)) : "";
    }

    /**
     * The text of row {@code id}: the payload is rows {@code <id>:<JSON or tagged data>\n}, but a text row is
     * {@code <id>:T<hex length>,<text>} with no line end, its length counted in UTF-8 bytes. "" without that row.
     */
    private static String textRow(String payload, String id) {
        int i = 0;
        while (i < payload.length()) {
            int colon = payload.indexOf(':', i);
            int comma = payload.indexOf(',', colon + 1);
            if (colon < 0 || comma < 0 || payload.charAt(colon + 1) != 'T') {
                int line = colon < 0 ? -1 : payload.indexOf('\n', colon);
                if (line < 0) {
                    return "";
                }
                i = line + 1;
                continue;
            }
            int end = comma + 1;
            try {
                for (int bytes = Integer.parseInt(payload.substring(colon + 2, comma), 16);
                        bytes > 0 && end < payload.length(); end++) {
                    char c = payload.charAt(end);
                    bytes -= c < 0x80 ? 1 : c < 0x800 || Character.isSurrogate(c) ? 2 : 3;
                }
            } catch (NumberFormatException notText) {
                return "";
            }
            if (payload.substring(i, colon).equals(id)) {
                return payload.substring(comma + 1, end);
            }
            i = end;
        }
        return "";
    }

    private static boolean flag(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    private static double number(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : 0;
    }
}
