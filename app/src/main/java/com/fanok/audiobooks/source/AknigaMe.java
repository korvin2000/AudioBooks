package com.fanok.audiobooks.source;

import static com.fanok.audiobooks.source.Json.string;

import com.fanok.audiobooks.model.OtherSourceModel;
import com.fanok.audiobooks.net.JsonPage;
import com.fanok.audiobooks.net.SiteHttp;
import com.fanok.audiobooks.pojo.AudioPOJO;
import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.pojo.DescriptionPOJO;
import com.fanok.audiobooks.pojo.GenrePOJO;
import com.fanok.audiobooks.pojo.OtherArtistPOJO;
import com.fanok.audiobooks.pojo.SeriesPOJO;
import com.fanok.audiobooks.search.ListingFilters;
import com.fanok.audiobooks.search.ProviderPage;
import com.fanok.audiobooks.search.SearchUrls;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * akniga.me (probed live, 2026-11-04): a Laravel + Inertia (React) site, plain GETs, no cookies, Referer or challenge.
 * Every page is HTML whose {@code <div id="app" data-page="...">} holds the page's whole JSON; {@link #fetch} returns
 * that JSON as a {@link JsonPage} ({@link #inertia}). The same JSON comes bare for {@code X-Inertia: true} with the
 * build's {@code X-Inertia-Version}, but a stale version answers 409 after every deploy of the site; the HTML needs no
 * version and is the very URL a deep link opens, so the app reads the HTML.
 * <ul>
 * <li>catalog {@code /audiobooks}, a genre {@code /audiobooks/<slug>}: 36 cards a page in {@code props.items.data}
 * (title, narrators, seconds, cover; no authors), {@code ?page=N} with {@code items.has_more}; a page past the end is
 * a 404. {@code sort=popular|rating|duration}, newest without one: popular and rating rank the few books with plays or
 * votes, then the newest; duration is the shortest first. {@code duration=short|medium|long}: under 3 h, 3 to 10 h,
 * 10 h and more. The genres are {@code props.audio_categories} of every catalog page and of the home page (25,
 * sub-genres by {@code parent_id}; their {@code book_count} is stale).</li>
 * <li>search {@code /search?q=Q&type=books|authors|narrators&page=N}: {@code result_albums}, {@code result_authors} or
 * {@code result_narrators} with {@code search_pagination}.</li>
 * <li>authors {@code /authors} and readers {@code /readers}, by name, 66 a page; an author {@code /author/<slug>} and a
 * reader {@code /reader/<slug>} list 36 cards a page without narrators and seconds, and take no sort; a series
 * {@code /series/<slug>} is one page.</li>
 * <li>book {@code /book/<slug>}: {@code album}, {@code book_people}, {@code book_duration}, {@code book_rating},
 * {@code book_relations} (other narrations {@code editions}, {@code series}), {@code book_recommendations}, and at most
 * 50 chapters in {@code items.data}; all of them are in {@code /akniga/books/<id>/chapters} (plain JSON). A chapter is
 * an MP3 {@code /playing/<id>.mp3} on the site itself: ranges, no Referer needed.</li>
 * </ul>
 * The older half of the catalog titles a book "Author. «Title»": a card shows the title and takes the author from it
 * ({@link #AUTHOR_TITLE}), the book page strips it when it is the book's author.
 */
public final class AknigaMe implements SourceParser {

    public static final String SERVER = "https://akniga.me";
    public static final String INDEX = SERVER + "/audiobooks";
    /** The home page: its {@code audio_categories} are the genre list (the site has no page of genres). */
    public static final String GENRES = SERVER + "/";
    public static final String AUTHORS = SERVER + "/authors";
    public static final String READERS = SERVER + "/readers";

    /** Durations of the server's {@code duration} buckets, in minutes. */
    private static final int SHORT_BELOW = 180;
    private static final int LONG_FROM = 600;

    private static final List<Sort.Entry> MENU = Sort.menu(Sort.NEW, Sort.POPULAR, Sort.RATING, Sort.DURATION);
    /** The index and a genre: the listings the server sorts and filters. */
    private static final Pattern CATALOG = Pattern.compile("^/audiobooks(/[^/]+)?/?$");
    /** Listings whose cards carry no seconds. */
    private static final Pattern NO_SECONDS = Pattern.compile("^/(author|reader|series)/");
    /** "Author. «Title» rest" (also "Author — «", "Author: «"): the old catalog's titles. */
    static final Pattern AUTHOR_TITLE = Pattern.compile("^([^«»]+?)(?:\\.|:|\\s[—–-])\\s+«(.+)»(.*)$");

    // ---------------------------------------------------------------- SourceParser

    /** Every page is read as its Inertia JSON ({@link #inertia}). */
    @Override
    public Document fetch(String url) throws IOException {
        return inertia(SiteHttp.get(url));
    }

    @Override
    public ProviderPage<BookPOJO> listing(Document page, String url, int pageNo) {
        return parseListing(page);
    }

    @Override
    public String pageUrl(String url, int page) {
        return page <= 1 ? url : ListingFilters.withParam(url, "page", String.valueOf(page));
    }

    /** The index and a genre re-sort on the server; an author, reader, series or search does not. */
    @Override
    public List<Sort.Entry> sorts(String url) {
        return catalog(url) ? MENU : Collections.<Sort.Entry>emptyList();
    }

    @Override
    public String sortUrl(Sort sort, String listingUrl) {
        String value = sortValue(sort);
        return value == null ? null : withQuery(INDEX, "sort", value.isEmpty() ? null : value, true);
    }

    @Override
    public boolean listsDuration(String url) {
        HttpUrl parsed = HttpUrl.parse(url);
        return parsed == null || !NO_SECONDS.matcher(parsed.encodedPath()).find();
    }

    /**
     * The server's duration bucket that holds the whole range ({@code duration}), so its cards are only those that may
     * match; none for a range across buckets.
     */
    @Override
    public String withDuration(String url, ListingFilters.Duration duration) {
        return catalog(url) ? withQuery(url, "duration", bucket(duration), false) : url;
    }

    /** A genre's books without sort and page: the server ranks a genre itself. */
    @Override
    public String genreOf(String url) {
        return catalog(url) && !isIndex(url) ? withQuery(url, "sort", null, true) : null;
    }

    @Override
    public String inGenre(String siteWide, String genre) {
        return withQuery(genre, "sort", HttpUrl.get(siteWide).queryParameter("sort"), true);
    }

    @Override
    public ProviderPage<GenrePOJO> genres(Document page, String url, int pageNo) {
        return ProviderPage.of(parseGenres(page), false);
    }

    @Override
    public ProviderPage<GenrePOJO> people(Document page, String url, int pageNo, boolean performers) {
        return parsePeople(page, performers);
    }

    @Override
    public String searchUrl(SearchUrls.Category category, String query, int page) {
        String type;
        switch (category) {
            case BOOKS: type = "books"; break;
            case AUTHORS: type = "authors"; break;
            case PERFORMERS: type = "narrators"; break;
            default: return null;
        }
        return SERVER + "/search?q=" + SearchUrls.enc(query) + "&type=" + type + "&page=" + page;
    }

    @Override
    public ProviderPage<GenrePOJO> searchPeople(Document page, int pageNo, boolean performers) {
        return parsePeople(page, performers);
    }

    @Override
    public DescriptionPOJO description(Document page, String bookUrl) {
        return parseDescription(page);
    }

    @Override
    public ArrayList<BookPOJO> recommendations(Document page, String bookUrl) {
        return parseRecommendations(page);
    }

    /** The chapters on the page, or all of them from the chapters API when the page lists only the first ones. */
    @Override
    public ArrayList<AudioPOJO> tracks(Document page, String base) throws IOException {
        String all = chaptersUrl(page);
        return parseTracks(all == null ? page : SiteHttp.getJson(all), parseDescription(page).getTitle(), base);
    }

    @Override
    public ArrayList<SeriesPOJO> series(Document page, String bookUrl) {
        return parseSeries(page);
    }

    @Override
    public boolean findsOtherReaders() {
        return true;
    }

    /** The book page lists its other narrations itself ({@link #parseDescription}): no search of its own. */
    @Override
    public boolean probesOtherReaders() {
        return false;
    }

    @Override
    public ArrayList<OtherArtistPOJO> otherReaders(String bookUrl, String title, String author, String reader)
            throws IOException {
        return parseOtherReaders(fetch(bookUrl));
    }

    // ---------------------------------------------------------------- requests and URLs

    /**
     * The Inertia JSON of an HTML page (its {@code #app} element's {@code data-page}), without the
     * {@code author_aliases} map every page carries for its script. A page without one (an error or anti-bot page
     * answered with 200) is an {@link IOException}, never "no books".
     */
    public static JsonPage inertia(Document html) throws IOException {
        Element app = html.getElementById("app");
        String data = app == null ? "" : app.attr("data-page");
        if (data.isEmpty()) {
            throw new IOException("no Inertia page: " + html.location());
        }
        JsonPage page;
        try {
            page = JsonPage.parse(html.location(), data);
        } catch (JsonParseException e) {
            throw new IOException("malformed Inertia page: " + html.location(), e);
        }
        props(page).remove("author_aliases");
        return page;
    }

    /** The chapters API of the book when its page lists fewer chapters than the book has; null when it lists all. */
    static String chaptersUrl(Document page) {
        JsonObject props = props(page);
        int listed = objects(object(props, "items"), "data").size();
        String id = string(object(props, "album"), "id");
        return listed < number(object(props, "book_source"), "available") && !id.isEmpty()
                ? SERVER + "/akniga/books/" + id + "/chapters" : null;
    }

    /** {@code /audiobooks} or {@code /audiobooks/<genre>}, sorted or not. */
    private static boolean catalog(String url) {
        HttpUrl parsed = HttpUrl.parse(url);
        return parsed != null && CATALOG.matcher(parsed.encodedPath()).matches();
    }

    private static boolean isIndex(String url) {
        return HttpUrl.get(url).encodedPath().replaceFirst("/$", "").equals("/audiobooks");
    }

    /**
     * {@code url} with the query parameter {@code name} set to {@code value}, or without it for null; from its first
     * page when {@code firstPage}.
     */
    private static String withQuery(String url, String name, String value, boolean firstPage) {
        HttpUrl.Builder builder = HttpUrl.get(url).newBuilder().removeAllQueryParameters(name);
        if (firstPage) {
            builder.removeAllQueryParameters("page");
        }
        if (value != null) {
            builder.addQueryParameter(name, value);
        }
        String out = builder.build().toString();
        return out.endsWith("?") ? out.substring(0, out.length() - 1) : out;
    }

    /** The {@code sort} value of a menu sort, "" for the newest (the default); null for a sort the site has not. */
    private static String sortValue(Sort sort) {
        switch (sort) {
            case NEW: return "";
            case POPULAR: return "popular";
            case RATING: return "rating";
            case DURATION: return "duration";
            default: return null;
        }
    }

    /** The {@code duration} bucket holding all of {@code duration}; null when none does. */
    static String bucket(ListingFilters.Duration duration) {
        if (duration.isAny()) {
            return null;
        }
        boolean bounded = !duration.isUnbounded();
        if (bounded && duration.toMinutes <= SHORT_BELOW) {
            return "short";
        }
        if (bounded && duration.fromMinutes >= SHORT_BELOW && duration.toMinutes <= LONG_FROM) {
            return "medium";
        }
        return duration.fromMinutes >= LONG_FROM ? "long" : null;
    }

    // ---------------------------------------------------------------- listings

    /**
     * Index, sorts, genre, search, an author's, a reader's or a series' books. An author's or reader's cards get that
     * person as their author or reader.
     */
    public static ProviderPage<BookPOJO> parseListing(Document page) {
        JsonObject props = props(page);
        if (props.has("result_albums")) {
            List<JsonObject> found = objects(props, "result_albums");
            return ProviderPage.of(cards(found, null, false), flag(object(props, "search_pagination"), "has_more"),
                    found.size());
        }
        JsonObject list = object(props, "items");
        List<JsonObject> items = objects(list, "data");
        JsonObject person = props.has("person") && props.get("person").isJsonObject()
                ? props.getAsJsonObject("person") : null;
        boolean reader = "narrators".equals(string(props, "people_kind"));
        return ProviderPage.of(cards(items, person, reader), hasMore(list), items.size());
    }

    /** {@code has_more}, else page below the last; a list without either (a series) is one page. */
    private static boolean hasMore(JsonObject list) {
        return list.has("has_more") ? flag(list, "has_more")
                : number(list, "current_page") < number(list, "last_page");
    }

    private static ArrayList<BookPOJO> cards(List<JsonObject> items, JsonObject person, boolean reader) {
        ArrayList<BookPOJO> books = new ArrayList<>();
        for (JsonObject item : items) {
            BookPOJO book = card(item);
            if (person != null && reader) {
                book.setArtist(string(person, "name"));
                book.setUrlArtist(absolute(string(person, "url")));
            } else if (person != null) {
                book.setAutor(string(person, "name"));
                book.setUrlAutor(absolute(string(person, "url")));
            }
            if (!book.isNull()) {
                books.add(book);
            }
        }
        return books;
    }

    /** A catalog, search, person, series or recommendation card: names only, no links to people. */
    private static BookPOJO card(JsonObject o) {
        BookPOJO book = new BookPOJO();
        String url = absolute(string(o, "url"));
        if (!url.isEmpty()) {
            book.setUrl(url);
        }
        String name = string(o, "name");
        String authors = strings(o, "authors");
        Matcher m = AUTHOR_TITLE.matcher(name);
        if (m.matches()) {
            name = m.group(2) + m.group(3);
            if (authors.isEmpty()) {
                authors = m.group(1).trim();
            }
        }
        book.setName(name);
        book.setAutor(authors);
        book.setPhoto(cover(o, "preview", "cover"));
        String narrators = strings(o, "narrator_names");
        book.setArtist(narrators.isEmpty() ? strings(o, "narrators") : narrators);
        book.setTime(time(o.get("duration")));
        if (decimal(o, "rating_average") > 0) {
            book.setReting(string(o, "rating_average"));
        }
        return book;
    }

    /** The {@code audio_categories} of a catalog or the home page; a sub-genre is described by its parent's name. */
    public static ArrayList<GenrePOJO> parseGenres(Document page) {
        List<JsonObject> all = objects(props(page), "audio_categories");
        Map<String, String> names = new HashMap<>();
        for (JsonObject o : all) {
            names.put(string(o, "id"), string(o, "name"));
        }
        ArrayList<GenrePOJO> genres = new ArrayList<>();
        for (JsonObject o : all) {
            String name = string(o, "name");
            String url = absolute(string(o, "url"));
            if (name.isEmpty() || url.isEmpty()) {
                continue;
            }
            GenrePOJO genre = new GenrePOJO(name, url);
            String parent = names.get(string(o, "parent_id"));
            if (parent != null && !parent.isEmpty()) {
                genre.setDescription(parent);
            }
            genres.add(genre);
        }
        return genres;
    }

    /** A page of the authors or readers index, or of the author or reader search: name, books, book count. */
    public static ProviderPage<GenrePOJO> parsePeople(Document page, boolean performers) {
        JsonObject props = props(page);
        if (props.has("search_pagination")) {
            List<JsonObject> found = objects(props, performers ? "result_narrators" : "result_authors");
            return ProviderPage.of(people(found), flag(object(props, "search_pagination"), "has_more"),
                    found.size());
        }
        JsonObject list = object(props, "items");
        List<JsonObject> items = objects(list, "data");
        return ProviderPage.of(people(items), hasMore(list), items.size());
    }

    private static ArrayList<GenrePOJO> people(List<JsonObject> items) {
        ArrayList<GenrePOJO> people = new ArrayList<>();
        for (JsonObject o : items) {
            String name = string(o, "name");
            String url = string(o, "url");
            if (!name.isEmpty() && (url.startsWith("/author/") || url.startsWith("/reader/"))) {
                GenrePOJO person = new GenrePOJO(name, absolute(url));
                person.setReting(number(o, "book_count"));
                people.add(person);
            }
        }
        return people;
    }

    // ---------------------------------------------------------------- book

    /** The book page: all authors and readers, the first genre and series, the other narrations. */
    public static DescriptionPOJO parseDescription(Document page) {
        JsonObject props = props(page);
        JsonObject album = object(props, "album");
        JsonObject people = object(props, "book_people");
        DescriptionPOJO d = new DescriptionPOJO();
        String authors = names(people, "authors");
        d.setTitle(title(string(album, "name"), authors));
        d.setPoster(cover(album, "cover", "preview"));
        d.setAutor(authors);
        d.setAutorUrl(personUrl(first(people, "authors")));
        d.setArtist(names(people, "narrators"));
        d.setArtistUrl(personUrl(first(people, "narrators")));
        JsonObject genre = first(album, "genres");
        if (genre != null) {
            d.setGenre(string(genre, "name"));
            d.setGenreUrl(absolute(string(genre, "url")));
        }
        JsonObject series = first(object(props, "book_relations"), "series");
        if (series != null) {
            d.setSeries(string(series, "name"));
            d.setSeriesUrl(absolute(string(series, "url")));
        }
        d.setTime(time(props.get("book_duration")));
        JsonObject rating = object(props, "book_rating");
        double stars = decimal(rating, "average");
        d.setReiting(stars > 0 ? BookPOJO.formatRating((float) stars, string(rating, "votes")) : "");
        d.setDescription(Jsoup.parseBodyFragment(string(album, "description")).text());
        ArrayList<OtherArtistPOJO> others = parseOtherReaders(page);
        d.setOtherReader(!others.isEmpty());
        if (!others.isEmpty()) {
            d.setOtherReaders(others);
        }
        return d;
    }

    /**
     * {@code name} without its "Author. «...»" frame when that author is {@code authors} (any order of the names);
     * otherwise unchanged.
     */
    static String title(String name, String authors) {
        Matcher m = AUTHOR_TITLE.matcher(name);
        return m.matches() && !authors.isEmpty() && OtherSourceModel.samePerson(m.group(1).trim(), authors)
                ? m.group(2) + m.group(3) : name;
    }

    /** The author's other books, the similar books and the reader's other books, each once. */
    public static ArrayList<BookPOJO> parseRecommendations(Document page) {
        JsonObject props = props(page);
        JsonObject groups = object(props, "book_recommendations");
        Set<String> seen = new HashSet<>();
        seen.add(absolute(string(object(props, "album"), "url")));
        ArrayList<BookPOJO> out = new ArrayList<>();
        for (String group : new String[]{"author", "similar", "narrator"}) {
            for (BookPOJO book : cards(objects(groups, group), null, false)) {
                if (seen.add(book.getUrl())) {
                    out.add(book);
                }
            }
        }
        return out;
    }

    /** The books of the book's first series, numbered as the site numbers them. */
    public static ArrayList<SeriesPOJO> parseSeries(Document page) {
        ArrayList<SeriesPOJO> out = new ArrayList<>();
        JsonObject series = first(object(props(page), "book_relations"), "series");
        if (series == null) {
            return out;
        }
        for (JsonObject item : objects(series, "items")) {
            for (JsonObject b : objects(item, "books")) {
                String url = absolute(string(b, "url"));
                String name = string(b, "name");
                if (url.isEmpty() || name.isEmpty()) {
                    continue;
                }
                SeriesPOJO book = new SeriesPOJO();
                book.setUrl(url);
                book.setName(title(name, strings(b, "authors")));
                book.setNumber(string(item, "position"));
                book.setPhoto(cover(b, "preview", "cover"));
                out.add(book);
            }
        }
        return out;
    }

    /** The other narrations the book page lists ({@code book_relations.editions}). */
    public static ArrayList<OtherArtistPOJO> parseOtherReaders(Document page) {
        JsonObject props = props(page);
        String self = absolute(string(object(props, "album"), "url"));
        ArrayList<OtherArtistPOJO> out = new ArrayList<>();
        for (JsonObject edition : objects(object(props, "book_relations"), "editions")) {
            String url = absolute(string(edition, "url"));
            if (url.isEmpty() || url.equals(self)) {
                continue;
            }
            String readers = strings(edition, "narrators");
            OtherArtistPOJO pojo = new OtherArtistPOJO();
            pojo.setName(readers.isEmpty() ? string(edition, "name") : "Исполнитель " + readers);
            pojo.setUrl(url);
            out.add(pojo);
        }
        return out;
    }

    /**
     * The chapters of a book page ({@code items.data}) or of its chapters API ({@code chapters}, a list or
     * {@code {data}}): an MP3 each, with its seconds.
     */
    public static ArrayList<AudioPOJO> parseTracks(Document page, String bookName, String bookUrl) {
        JsonElement json = JsonPage.of(page);
        JsonObject root = json.isJsonObject() ? json.getAsJsonObject() : new JsonObject();
        JsonArray items;
        JsonElement chapters = root.get("chapters");
        if (chapters != null && chapters.isJsonArray()) {
            items = chapters.getAsJsonArray();
        } else {
            JsonObject list = chapters != null && chapters.isJsonObject() ? chapters.getAsJsonObject()
                    : object(props(page), "items");
            JsonElement data = list.get("data");
            items = data != null && data.isJsonArray() ? data.getAsJsonArray() : new JsonArray();
        }
        Players.Chapters out = new Players.Chapters(bookName, bookUrl);
        out.each(items, item -> {
            JsonObject c = item.getAsJsonObject();
            return out.chapter(string(c, "name"), string(c, "play"), Json.seconds(c.get("duration")));
        });
        return out.done(bookUrl);
    }

    // ---------------------------------------------------------------- JSON fields

    /** The {@code props} of an Inertia page; none for a JSON of another shape (an empty page). */
    private static JsonObject props(Document page) {
        JsonElement json = JsonPage.of(page);
        if (!json.isJsonObject()) {
            throw new IllegalStateException("JSON is not an object: " + page.location());
        }
        return object(json.getAsJsonObject(), "props");
    }

    /** The object under {@code key}; an empty one when it is absent or null. */
    private static JsonObject object(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
    }

    /** The objects of the list under {@code key}; none when it is absent. */
    private static List<JsonObject> objects(JsonObject o, String key) {
        List<JsonObject> out = new ArrayList<>();
        JsonElement list = o.get(key);
        if (list != null && list.isJsonArray()) {
            for (JsonElement item : list.getAsJsonArray()) {
                if (item.isJsonObject()) {
                    out.add(item.getAsJsonObject());
                }
            }
        }
        return out;
    }

    private static JsonObject first(JsonObject o, String key) {
        List<JsonObject> all = objects(o, key);
        return all.isEmpty() ? null : all.get(0);
    }

    /** The {@code name}s of the people under {@code key}, comma-separated. */
    private static String names(JsonObject o, String key) {
        StringBuilder out = new StringBuilder();
        for (JsonObject person : objects(o, key)) {
            append(out, string(person, "name"));
        }
        return out.toString();
    }

    /** The strings of the list under {@code key}, comma-separated. */
    private static String strings(JsonObject o, String key) {
        StringBuilder out = new StringBuilder();
        JsonElement list = o.get(key);
        if (list != null && list.isJsonArray()) {
            for (JsonElement item : list.getAsJsonArray()) {
                append(out, string(item));
            }
        }
        return out.toString();
    }

    private static void append(StringBuilder out, String name) {
        if (!name.trim().isEmpty()) {
            out.append(out.length() == 0 ? "" : ", ").append(name.trim());
        }
    }

    private static String personUrl(JsonObject person) {
        return person == null ? "" : absolute(string(person, "url"));
    }

    /** The site's root-relative {@code url} made absolute; "" for none. */
    private static String absolute(String url) {
        return url.isEmpty() || url.startsWith("http") ? url : SERVER + (url.startsWith("/") ? "" : "/") + url;
    }

    /** The first of the image fields {@code keys} that is set. */
    private static String cover(JsonObject o, String... keys) {
        for (String key : keys) {
            String url = string(o, key);
            if (!url.isEmpty()) {
                return absolute(url);
            }
        }
        return "";
    }

    /** H:MM:SS of a number of seconds; "" for none. */
    private static String time(JsonElement seconds) {
        int s = Json.seconds(seconds);
        return s == 0 ? "" : String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
    }

    private static int number(JsonObject o, String key) {
        return (int) decimal(o, key);
    }

    private static double decimal(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : 0;
    }

    private static boolean flag(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }
}
