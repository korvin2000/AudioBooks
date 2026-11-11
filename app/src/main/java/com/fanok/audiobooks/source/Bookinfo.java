package com.fanok.audiobooks.source;

import static com.fanok.audiobooks.source.Json.string;

import com.fanok.audiobooks.BookRemovedException;
import com.fanok.audiobooks.model.OtherSourceModel;
import com.fanok.audiobooks.net.JsonPage;
import com.fanok.audiobooks.net.SiteHttp;
import com.fanok.audiobooks.pojo.AudioPOJO;
import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.pojo.DescriptionPOJO;
import com.fanok.audiobooks.pojo.GenrePOJO;
import com.fanok.audiobooks.pojo.SeriesPOJO;
import com.fanok.audiobooks.search.ListingFilters;
import com.fanok.audiobooks.search.ProviderPage;
import com.fanok.audiobooks.search.SearchUrls;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.nodes.Document;

/**
 * bookinfo.net (probed live, 2026-11-04): a Nuxt front over an open Rails JSON API {@code /api/v1/} (no key, cookies or
 * Referer), which the app reads alone; the API URLs are the app's listing URLs, a book is its public page
 * {@code /<genre>/<author>/<slug>}, which {@link #fetch} reads as {@code /api/v1/books/<slug>/<author>}.
 * <ul>
 * <li>books {@code /api/v1/books}: {@code {books, meta: {all, page, total_pages}}}, paged with {@code page=N} and
 * {@code per=N}; a card has no readers, no duration and no audio. Of the 86 thousand books about a third have no audio
 * and a seventh are LitRes trial fragments (one 600 s file): {@code search[feed]=home}, the site's own home feed,
 * keeps 36 thousand that all play in full (57 sampled, none a fragment or empty). The home page and the search carry
 * it; a genre, an author, a reader and a series are listed as the site's own pages list them, without it
 * ({@link #CATALOG}): the feed leaves whole genres empty (S.T.A.L.K.E.R.: 0 of the site's 16 books, Фанфик: 1 of
 * 470). A fragment or a book without audio opened from there is reported as such ({@link #tracks}).</li>
 * <li>filters {@code search[genre_alias|author_alias|reader_alias|series_alias|title]} (the title a substring, the
 * only search), combined with each other and with {@code order}: {@code -id} (the default, newest), {@code -rating},
 * {@code title}, {@code -updated_at} ({@code duration}, views and comments are refused). No periods.</li>
 * <li>genres {@code /api/v1/genres} (50); authors {@code /api/v1/authors} and readers {@code /api/v1/readers},
 * {@code order=-books_count}, {@code per=10|100}, their search {@code search[name]=}.</li>
 * <li>book {@code /api/v1/books/<slug>[/<author>]}: people, genres, series with its number, rating, chapters
 * {@code audio_files[]} each a whole MP3 {@code /audio_files/<id>/file.mp3} on the same host with its seconds (ranges,
 * no Referer needed). A LitRes entry ({@code source} set) plays a 10-minute fragment.</li>
 * </ul>
 */
public final class Bookinfo implements SourceParser {

    public static final String SERVER = "https://bookinfo.net";
    static final String API = SERVER + "/api/v1/";
    /** Every book listing: the full books of the home feed, 20 a page. */
    public static final String INDEX = API + "books?search%5Bfeed%5D=home&per=20";
    /** The books of a genre, author, reader or series: all the site's published ones, as its own pages show them. */
    static final String CATALOG = API + "books?per=20";
    public static final String GENRES = API + "genres?per=100&order=title";
    public static final String AUTHORS = API + "authors?order=-books_count&per=100";
    public static final String READERS = API + "readers?order=-books_count&per=100";

    /** Bound of the listing pages read for a book's series tab. */
    static final int MAX_SERIES_PAGES = 5;
    /** The length of a LitRes trial fragment, in seconds. */
    static final int FRAGMENT_SECONDS = 600;

    private static final List<Sort.Entry> MENU = Sort.menu(Sort.NEW, Sort.RATING, Sort.TITLE);
    private static final String BOOKS_API = API + "books?";
    private static final String TITLE = "search%5Btitle%5D";
    private static final String GENRE = "search%5Bgenre_alias%5D";
    private static final String AUTHOR = "search%5Bauthor_alias%5D";
    private static final String READER = "search%5Breader_alias%5D";
    private static final String SERIES = "search%5Bseries_alias%5D";
    /** A book page: three path segments, the first a genre (not one of the site's own folders). */
    private static final Pattern BOOK = Pattern.compile("^https?://(?:www\\.)?bookinfo\\.net/"
            + "(?!(?:api|books|audio_files|authors|news|videos|_nuxt|author|reader|series)/)"
            + "([^/?#]+)/([^/?#]+)/([^/?#]+)");
    private static final Pattern NUMBER = Pattern.compile("\\d+");

    // ---------------------------------------------------------------- SourceParser

    /** A book page is its record {@code /api/v1/books/<slug>/<author>}; any other URL is an API call. */
    @Override
    public Document fetch(String url) throws IOException {
        Matcher book = BOOK.matcher(url);
        return SiteHttp.getJson(book.find() ? API + "books/" + book.group(3) + "/" + book.group(2) : url);
    }

    @Override
    public ProviderPage<BookPOJO> listing(Document page, String url, int pageNo) {
        return parseListing(page);
    }

    @Override
    public String pageUrl(String url, int page) {
        return page <= 1 ? without(url, "page") : ListingFilters.withParam(url, "page", String.valueOf(page));
    }

    /** The index, a genre, an author and a reader re-sort on the server; a series and a search get no menu. */
    @Override
    public List<Sort.Entry> sorts(String url) {
        return sortable(url) ? MENU : Collections.<Sort.Entry>emptyList();
    }

    @Override
    public String sortUrl(Sort sort, String listingUrl) {
        String value = sortValue(sort);
        return value == null ? null : withOrder(INDEX, value);
    }

    /** The books of a genre, an author or a reader without sort and page: the server ranks each of them itself. */
    @Override
    public String genreOf(String url) {
        return sortable(url) && !isIndex(url) ? withOrder(url, "") : null;
    }

    @Override
    public String inGenre(String siteWide, String genre) {
        String order = ListingFilters.param(siteWide, "order");
        return withOrder(genre, order == null ? "" : order);
    }

    /** The cards of the books API carry no duration. */
    @Override
    public boolean listsDuration(String url) {
        return !url.startsWith(BOOKS_API);
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
        String q = SearchUrls.enc(query);
        switch (category) {
            case BOOKS: return INDEX + "&" + TITLE + "=" + q + "&page=" + page;
            case AUTHORS: return API + "authors?search%5Bname%5D=" + q + "&order=-books_count&per=100&page=" + page;
            case PERFORMERS: return API + "readers?search%5Bname%5D=" + q + "&order=-books_count&per=100&page=" + page;
            default: return null;
        }
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
    public ArrayList<AudioPOJO> tracks(Document page, String base) {
        return parseTracks(page, base);
    }

    /** The full books of the book's series, read from its listing, oldest first. */
    @Override
    public ArrayList<SeriesPOJO> series(Document page, String bookUrl) throws IOException {
        JsonObject book = book(page);
        ArrayList<SeriesPOJO> out = new ArrayList<>();
        String alias = seriesAlias(book);
        for (int no = 1; !alias.isEmpty() && no <= MAX_SERIES_PAGES; no++) {
            Document listing = fetch(pageUrl(seriesUrl(alias), no));
            out.addAll(parseSeries(listing, book));
            if (!hasMore(object(listing))) {
                break;
            }
        }
        return out;
    }

    /** The title alone: the site searches titles only, and a name added to them finds nothing. */
    @Override
    public String otherSourceTerms(BookPOJO book) {
        return OtherSourceModel.query(book.getName());
    }

    // ---------------------------------------------------------------- URLs

    /** The public page of a book: what the app stores, shows and deep-links; "" when a part is missing. */
    public static String bookUrl(String genre, String author, String slug) {
        return genre.isEmpty() || author.isEmpty() || slug.isEmpty()
                ? "" : SERVER + "/" + genre + "/" + author + "/" + slug;
    }

    static String genreUrl(String alias) {
        return CATALOG + "&" + GENRE + "=" + alias;
    }

    static String authorUrl(String alias) {
        return alias.isEmpty() ? "" : CATALOG + "&" + AUTHOR + "=" + alias;
    }

    static String readerUrl(String alias) {
        return alias.isEmpty() ? "" : CATALOG + "&" + READER + "=" + alias;
    }

    /** A series, by the order the books were added: its parts in turn. */
    static String seriesUrl(String alias) {
        return alias.isEmpty() ? "" : CATALOG + "&" + SERIES + "=" + alias + "&order=id";
    }

    /** The books API without a title search or a series. */
    private static boolean sortable(String url) {
        return url.startsWith(BOOKS_API) && !url.contains(TITLE + "=") && !url.contains(SERIES + "=");
    }

    private static boolean isIndex(String url) {
        return !url.contains(GENRE + "=") && !url.contains(AUTHOR + "=") && !url.contains(READER + "=");
    }

    /** {@code url} from its first page in the order {@code value} ("" for the newest first, the default). */
    private static String withOrder(String url, String value) {
        String bare = without(without(url, "page"), "order");
        return value.isEmpty() ? bare : bare + "&order=" + value;
    }

    /** {@code url} without its query parameter {@code name} (never the first one: the feed or {@code per}). */
    private static String without(String url, String name) {
        return url.replaceAll("&" + Pattern.quote(name) + "=[^&#]*", "");
    }

    private static String sortValue(Sort sort) {
        switch (sort) {
            case NEW: return "";
            case RATING: return "-rating";
            case TITLE: return "title";
            default: return null;
        }
    }

    // ---------------------------------------------------------------- listings

    /** Index, sorts, genre, search, an author's, a reader's or a series' books. */
    public static ProviderPage<BookPOJO> parseListing(Document page) {
        JsonObject root = object(page);
        JsonArray books = Json.arrayOf(root, "books");
        ArrayList<BookPOJO> cards = new ArrayList<>();
        for (JsonObject o : objects(books)) {
            BookPOJO book = card(o);
            if (!book.isNull()) {
                cards.add(book);
            }
        }
        return ProviderPage.of(cards, hasMore(root), books.size());
    }

    private static BookPOJO card(JsonObject o) {
        BookPOJO book = new BookPOJO();
        String url = bookUrl(o);
        if (!url.isEmpty()) {
            book.setUrl(url);
        }
        book.setName(string(o, "title"));
        book.setPhoto(cover(o));
        book.setAutor(names(o, "authors"));
        book.setUrlAutor(authorUrl(alias(first(o, "authors"))));
        JsonObject genre = mainGenre(o);
        if (genre != null) {
            book.setGenre(string(genre, "title"));
            book.setUrlGenre(genreUrl(string(genre, "title_alias")));
        }
        if (number(o, "rating_count") > 0) {
            book.setReting(string(o, "rating"));
        }
        return book;
    }

    /** The genres of {@code /api/v1/genres} that have books, each with its count of published books. */
    public static ArrayList<GenrePOJO> parseGenres(Document page) {
        ArrayList<GenrePOJO> genres = new ArrayList<>();
        for (JsonObject o : objects(Json.arrayOf(object(page), "genres"))) {
            String name = string(o, "title");
            String alias = string(o, "title_alias");
            int published = number(o, "published_books_count");
            if (!name.isEmpty() && !alias.isEmpty() && published > 0) {
                GenrePOJO genre = new GenrePOJO(name, genreUrl(alias));
                genre.setReting(published);
                genres.add(genre);
            }
        }
        return genres;
    }

    /**
     * A page of the authors or readers index or of their search: name, books and the book count. An author without a
     * name or without published books (none of them in full) is left out.
     */
    public static ProviderPage<GenrePOJO> parsePeople(Document page, boolean performers) {
        JsonObject root = object(page);
        JsonArray items = Json.arrayOf(root, performers ? "readers" : "authors");
        ArrayList<GenrePOJO> people = new ArrayList<>();
        for (JsonObject o : objects(items)) {
            String name = string(o, "name").trim();
            String alias = alias(o);
            JsonElement published = o.get("published_books_count");
            boolean counted = published != null && published.isJsonPrimitive();
            int books = counted ? number(o, "published_books_count") : number(o, "books_count");
            if (name.isEmpty() || alias.isEmpty() || books == 0) {
                continue;
            }
            GenrePOJO person = new GenrePOJO(name, performers ? readerUrl(alias) : authorUrl(alias));
            person.setReting(books);
            people.add(person);
        }
        return ProviderPage.of(people, hasMore(root), items.size());
    }

    // ---------------------------------------------------------------- book

    /** The book record: all authors and readers, the main genre, the series with its number. */
    public static DescriptionPOJO parseDescription(Document page) {
        JsonObject o = book(page);
        DescriptionPOJO d = new DescriptionPOJO();
        d.setTitle(string(o, "title"));
        d.setPoster(cover(o));
        d.setAutor(names(o, "authors"));
        d.setAutorUrl(authorUrl(alias(first(o, "authors"))));
        d.setArtist(names(o, "readers"));
        d.setArtistUrl(readerUrl(alias(first(o, "readers"))));
        JsonObject genre = mainGenre(o);
        if (genre != null) {
            d.setGenre(string(genre, "title"));
            d.setGenreUrl(genreUrl(string(genre, "title_alias")));
        }
        String series = string(o, "series_name");
        String alias = seriesAlias(o);
        if (!series.isEmpty() && !alias.isEmpty()) {
            String no = string(o, "series_num");
            d.setSeries(no.isEmpty() ? series : series + " (" + no + ")");
            d.setSeriesUrl(seriesUrl(alias));
        }
        d.setTime(time(Json.seconds(o.get("duration"))));
        int votes = number(o, "rating_count");
        d.setReiting(votes > 0 ? BookPOJO.formatRating((float) decimal(o, "rating"), String.valueOf(votes)) : "");
        String text = string(o, "description");
        d.setDescription(text.contains("<") ? Html.paragraphs(text) : text.trim());
        return d;
    }

    /** The full books of a series listing, the book {@code current} with its number (the cards carry none). */
    public static ArrayList<SeriesPOJO> parseSeries(Document listing, JsonObject current) {
        ArrayList<SeriesPOJO> out = new ArrayList<>();
        String slug = string(current, "title_alias");
        for (JsonObject o : objects(Json.arrayOf(object(listing), "books"))) {
            String url = bookUrl(o);
            String name = string(o, "title");
            if (url.isEmpty() || name.isEmpty()) {
                continue;
            }
            SeriesPOJO item = new SeriesPOJO();
            item.setUrl(url);
            item.setName(name);
            item.setNumber(slug.equals(string(o, "title_alias")) ? string(current, "series_num") : "");
            item.setPhoto(cover(o));
            out.add(item);
        }
        return out;
    }

    /**
     * The chapters of the record, by position: a whole MP3 each with its seconds, resolved against {@code base}. A
     * LitRes trial fragment is a {@link BookRemovedException}; a book without audio an {@link IllegalStateException}.
     */
    public static ArrayList<AudioPOJO> parseTracks(Document page, String base) {
        JsonObject o = book(page);
        String url = bookUrl(o).isEmpty() ? base : bookUrl(o);
        List<JsonObject> files = objects(o.get("audio_files"));
        if (isFragment(o, files)) {
            throw new BookRemovedException(url);
        }
        Collections.sort(files, (a, b) -> Integer.compare(number(a, "position"), number(b, "position")));
        Players.Chapters chapters = new Players.Chapters(string(o, "title"), base);
        for (JsonObject file : files) {
            String name = string(file, "name").trim();
            if (name.isEmpty() || NUMBER.matcher(name).matches()) {
                name = "Глава " + (name.isEmpty() ? string(file, "position") : name);
            }
            chapters.add(chapters.chapter(name, string(file, "file"), Json.seconds(file.get("duration"))));
        }
        return chapters.done(url);
    }

    /**
     * A LitRes affiliate entry: the record names its source, or its only file is the 10-minute trial of a longer book.
     */
    static boolean isFragment(JsonObject book, List<JsonObject> files) {
        if (!string(book, "source").isEmpty()) {
            return true;
        }
        return files.size() == 1 && Json.seconds(files.get(0).get("duration")) == FRAGMENT_SECONDS
                && Json.seconds(book.get("duration")) > 2 * FRAGMENT_SECONDS;
    }

    // ---------------------------------------------------------------- JSON fields

    private static JsonObject object(Document page) {
        JsonElement json = JsonPage.of(page);
        if (!json.isJsonObject()) {
            throw new IllegalStateException("JSON is not an object: " + page.location());
        }
        return json.getAsJsonObject();
    }

    /** The {@code book} of a record; an empty one when there is none (the model rejects its empty title). */
    private static JsonObject book(Document page) {
        JsonElement book = object(page).get("book");
        return book != null && book.isJsonObject() ? book.getAsJsonObject() : new JsonObject();
    }

    /** The objects of a list; none when it is absent. */
    private static List<JsonObject> objects(JsonElement list) {
        List<JsonObject> out = new ArrayList<>();
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
        List<JsonObject> all = objects(o.get(key));
        return all.isEmpty() ? null : all.get(0);
    }

    private static JsonObject child(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static String alias(JsonObject o) {
        return o == null ? "" : string(o, "name_alias");
    }

    /** The page of a card or record: its main genre and main author, else its first ones. */
    private static String bookUrl(JsonObject o) {
        JsonObject genre = mainGenre(o);
        String author = alias(child(o, "main_author"));
        return bookUrl(genre == null ? "" : string(genre, "title_alias"),
                author.isEmpty() ? alias(first(o, "authors")) : author, string(o, "title_alias"));
    }

    /** The genre named by {@code main_genre} among the book's genres, else its first. */
    private static JsonObject mainGenre(JsonObject o) {
        String main = alias(child(o, "main_genre"));
        List<JsonObject> genres = objects(o.get("genres"));
        for (JsonObject genre : genres) {
            if (string(genre, "title_alias").equals(main)) {
                return genre;
            }
        }
        return genres.isEmpty() ? null : genres.get(0);
    }

    private static String seriesAlias(JsonObject o) {
        String alias = string(o, "series_alias");
        if (alias.isEmpty()) {
            alias = alias(child(o, "main_series"));
        }
        JsonObject series = first(o, "series");
        return alias.isEmpty() && series != null ? string(series, "title_alias") : alias;
    }

    /** The names of the people under {@code key}, comma-separated. */
    private static String names(JsonObject o, String key) {
        StringBuilder out = new StringBuilder();
        for (JsonObject person : objects(o.get(key))) {
            String name = string(person, "name").trim();
            if (!name.isEmpty()) {
                out.append(out.length() == 0 ? "" : ", ").append(name);
            }
        }
        return out.toString();
    }

    private static String cover(JsonObject o) {
        String path = string(o, "cover");
        return path.startsWith("/") ? SERVER + path : path.startsWith("https://") ? path : "";
    }

    private static boolean hasMore(JsonObject root) {
        JsonObject meta = child(root, "meta");
        return meta != null && number(meta, "page") < number(meta, "total_pages");
    }

    /** H:MM:SS of a number of seconds; "" for none. */
    private static String time(int s) {
        return s == 0 ? "" : String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
    }

    private static int number(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsInt() : 0;
    }

    private static double decimal(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : 0;
    }
}
