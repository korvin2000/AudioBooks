package com.fanok.audiobooks.source;

import static com.fanok.audiobooks.source.Json.string;

import com.fanok.audiobooks.BookRemovedException;
import com.fanok.audiobooks.model.BookPages;
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

import org.jsoup.Connection;
import org.jsoup.nodes.Document;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import okhttp3.HttpUrl;

/**
 * yakniga.org (probed live, 2026-11-04) and the engine of its backend's other fronts ({@link TwoKnigi}): a Nuxt front
 * over a public GraphQL API, {@code POST /graphql}, no login, no challenge. The app keeps the site's own URLs and
 * {@link #fetch} reads each of them as one GraphQL query, answered as JSON; the parse methods read {@code data.x}, the
 * alias every query gives its answer. What a front differs in is its {@link Site}: its server, the routes of its pages
 * and the field that names a record in them; the code never asks which site it serves.
 * <ul>
 * <li>Every request carries the site's {@code Origin}: without it the server ignores the sort (the order is random
 * from one request to the next) and lists the books of another front-end.</li>
 * <li>Of 312,630 entries about 79% are LitRes trial stubs ({@code source} "litres": one short chapter), "author_today"
 * fragments (one 20-minute chapter whatever the book's duration says) or paid "biblio" entries without chapters. The
 * books that play in full come from {@link #FULL_SOURCES} (63,180): every listing asks for them alone
 * ({@code by_source}), and the cards are checked again ({@link #playable}), since the filter lets some stubs through
 * (the answer to this origin shows the full sources as {@code null}). A copyright-blocked book and one without
 * chapters are left out as well.</li>
 * <li>Listings {@code books(query, page, perPage)}, also of a genre, an author, a reader or a series; the query JSON
 * sorts: {@code by_date} (newest, the default of the site's genre and people pages), {@code by_pop} (the site's
 * "По рейтингу"), {@code by_alphabet} and {@code by_fixed_top} (the curated "Популярные" list of {@code /top}).
 * The app's URLs: the catalog {@code /?by_date=desc}, a genre {@code /genres/<alias>}, an author {@code /<alias>}, a
 * reader {@code /reader/<alias>} (each with its sort parameter, as on the site), a series {@code /series/<alias>}
 * (by its numbers), all paged with {@code page=N}.</li>
 * <li>Genres {@code genres}: 55 in one page, sub-genres under their parent. Authors and readers {@code authors},
 * {@code readers} (215,049 and 25,821, by id, most of them of stubs only): each with the count of their full books, a
 * person without any is left out. The site has no pages for these lists, so {@link #AUTHORS} and {@link #READERS}
 * are the app's own.</li>
 * <li>Search {@code search(term, source)}: one answer of at most 20 books, 10 authors and 10 readers; no paging.</li>
 * <li>A book {@code /<authorAlias>/<bookAlias>}: {@code book(aliasName, authorAliasName)} with its chapters (an MP3
 * each, {@code /yafile/<hex>.mp3} on the site itself, ranges, no Referer needed) and its series' books. The book's
 * duration is its chapters' sum (the page's other figures disagree).</li>
 * </ul>
 */
public class Yakniga implements SourceParser {

    public static final String SERVER = "https://yakniga.org";
    /** Every full book, newest first: the catalog. */
    public static final String INDEX = SERVER + "/?by_date=desc";
    public static final String GENRES = SERVER + "/genres";
    public static final String AUTHORS = SERVER + "/authors";
    public static final String READERS = SERVER + "/readers";

    /** The sources of the books that play in full; "litres", "author_today" and "biblio" are stubs. */
    static final List<String> FULL_SOURCES = Collections.unmodifiableList(
            Arrays.asList("manual", "knigavuhe", "akniga", "aknig"));
    static final int PER_PAGE = 30;
    /** Authors or readers asked for at once: about half of the first pages have full books, few of the last. */
    static final int PEOPLE_PER_PAGE = 100;
    /** Bound of the API pages one page of the authors or readers list reads while they hold nobody. */
    static final int MAX_PEOPLE_PAGES = 10;
    /** The books of a series the book page asks for. */
    static final int SERIES_MAX = 100;

    /** The default sort of every listing, as the site's genre and people pages have it. */
    static final String NEWEST = "by_date=desc";
    private static final String[] SORT_KEYS = {"by_date", "by_fixed_top", "by_pop", "by_alphabet"};
    private static final List<Sort.Entry> MENU = Sort.menu(Sort.NEW, Sort.POPULAR, Sort.RATING, Sort.TITLE);
    /** An author's or a reader's page sorts as the site's own menu there: no curated list. */
    private static final List<Sort.Entry> PERSON_MENU = Sort.menu(Sort.NEW, Sort.RATING, Sort.TITLE);
    /** A search page past the first: the search answers in one page. */
    static final String NOTHING_FOUND = "{\"data\":{\"x\":[]}}";

    /** What an app URL of the site is. */
    enum Kind { ALL, GENRE, AUTHOR, READER, SERIES, BOOK, GENRES, AUTHORS, READERS, SEARCH }

    /**
     * What one front of the engine differs in, the codec of its URLs: its server (the API is its {@code /graphql}),
     * the route of each {@link Kind} of page and the field that names a record in it. A route is a path with
     * {@code *} for each name of the record ({@code genres/*}; {@code *} alone for yakniga's author pages); the route
     * with the most fixed segments that matches a path tells what it is ({@code /genres} before {@code /<author>}).
     */
    static final class Site {
        final String server;
        final String api;
        /** The field that names a genre, an author or a reader in its URL and the argument that looks it up. */
        final String key;
        /** The selection that names a record: its alias, and its {@link #key} when that is another field. */
        final String names;
        final Map<Kind, String[]> routes = new EnumMap<>(Kind.class);
        /** The book's fields its URL names, in route order ({@code $a}, {@code $b}), and the lookup by them. */
        String[] bookKeys = {"aliasName"};
        String bookArgs = "aliasName:$a";
        /** The book's own field naming its author when it lists none; null when it has none. */
        String bookAuthor;

        Site(String server, String key) {
            this.server = server;
            this.api = server + "/graphql";
            this.key = key;
            this.names = key.equals("aliasName") ? key : "aliasName " + key;
        }

        Site route(Kind kind, String route) {
            routes.put(kind, route.split("/"));
            return this;
        }

        Site book(String args, String... keys) {
            bookArgs = args;
            bookKeys = keys;
            return this;
        }

        Site bookAuthor(String field) {
            bookAuthor = field;
            return this;
        }
    }

    /** yakniga.org's own routes: an author's page is {@code /<alias>}, a book's {@code /<authorAlias>/<alias>}. */
    static final Site YAKNIGA = new Site(SERVER, "aliasName")
            .route(Kind.GENRES, "genres").route(Kind.AUTHORS, "authors").route(Kind.READERS, "readers")
            .route(Kind.SEARCH, "search").route(Kind.GENRE, "genres/*").route(Kind.AUTHOR, "*")
            .route(Kind.READER, "reader/*").route(Kind.SERIES, "series/*").route(Kind.BOOK, "*/*")
            .book("aliasName:$b,authorAliasName:$a", "authorAlias", "aliasName")
            .bookAuthor("authorAlias");

    final Site site;

    // ---------------------------------------------------------------- GraphQL documents

    private static final String PAGING = "metadata{currentPage nextPage totalPages totalEntries}";
    private static final String COUNT = "books(query:$s,page:1,perPage:1){metadata{totalEntries}}";
    private final String bookFields;
    private final String books;
    final String listAllQuery;
    final String genresQuery;
    final String searchBooksQuery;
    final String searchPeopleQuery;
    final String bookQuery;

    public Yakniga() {
        this(YAKNIGA);
    }

    Yakniga(Site site) {
        this.site = site;
        String n = site.names;
        bookFields = "id title " + n + " authorAlias authorName duration chaptersCount source isBiblio copyrightBlock"
                + " cover cover360";
        String people = "seriesNum authors{name " + n + "} readers{name " + n + "} genres{collection{name " + n + "}}";
        String card = bookFields + " " + people + " series{name aliasName}";
        books = "books(query:$q,page:$p,perPage:$n){collection{" + card + "} " + PAGING + "}";
        listAllQuery = "query($q:JSON,$p:Int,$n:Int){x:" + books + "}";
        genresQuery = "query{x:genres(page:1,perPage:200){collection{id name " + n + " position parent{aliasName}} "
                + PAGING + "}}";
        searchBooksQuery = "query($t:String!,$src:[String!]){x:search(term:$t,source:$src,ebookCheck:[false])"
                + "{__typename ... on Book{" + card + "}}}";
        searchPeopleQuery = "query($t:String!,$src:[String!],$s:JSON){x:search(term:$t,source:$src,"
                + "ebookCheck:[false]){__typename ... on Author{name " + n + " " + COUNT + "} ... on Reader{name " + n
                + " " + COUNT + "}}}";
        StringBuilder vars = new StringBuilder();
        for (int i = 0; i < site.bookKeys.length; i++) {
            vars.append("$").append((char) ('a' + i)).append(":String,");
        }
        bookQuery = "query(" + vars + "$s:JSON,$n:Int){x:book(" + site.bookArgs + "){" + bookFields + " " + people
                + " description likesCount dislikesCount chapters{collection{name duration fileUrl}} series{name"
                + " aliasName books(query:$s,page:1,perPage:$n){collection{" + bookFields + " seriesNum}}}}}";
    }

    /** The books of one {@code kind} ("genre", "author", "reader", "series") named in its URL. */
    String listIn(String kind) {
        String lookup = kind.equals("series") ? "aliasName" : site.key; // a series is named by its alias everywhere
        return "query($a:String,$q:JSON,$p:Int,$n:Int){x:" + kind + "(" + lookup + ":$a){name " + books + "}}";
    }

    /** A page of the authors or readers ({@code kind}), each with the count of their full books. */
    String peopleList(String kind) {
        return "query($p:Int,$n:Int,$s:JSON){x:" + kind + "(page:$p,perPage:$n){collection{name " + site.names + " "
                + COUNT + "} " + PAGING + "}}";
    }

    // ---------------------------------------------------------------- SourceParser

    /** The GraphQL answer for the app URL {@code url}; a search page past the first is empty without asking. */
    @Override
    public Document fetch(String url) throws IOException {
        if (kind(url) == Kind.SEARCH && page(url) > 1) {
            return JsonPage.parse(url, NOTHING_FOUND);
        }
        JsonObject request = request(url);
        if (request == null) {
            throw new IOException("not a page of " + site.server + ": " + url);
        }
        return post(request);
    }

    @Override
    public ProviderPage<BookPOJO> listing(Document page, String url, int pageNo) {
        return kind(url) == Kind.SEARCH ? parseSearch(page) : parseListing(page);
    }

    @Override
    public String pageUrl(String url, int page) {
        return page <= 1 ? withoutPage(url) : ListingFilters.withParam(url, "page", String.valueOf(page));
    }

    /** The catalog and a genre: every sort; an author or a reader: the site's own menu; a series, a search: none. */
    @Override
    public List<Sort.Entry> sorts(String url) {
        Kind kind = kind(url);
        if (kind == Kind.ALL || kind == Kind.GENRE) {
            return MENU;
        }
        return kind == Kind.AUTHOR || kind == Kind.READER ? PERSON_MENU : Collections.<Sort.Entry>emptyList();
    }

    @Override
    public String sortUrl(Sort sort, String listingUrl) {
        String param = sortParam(sort);
        if (param == null) {
            return null;
        }
        String siteWide = site.server + "/?" + param;
        String scope = genreOf(listingUrl);
        String same = scope == null ? siteWide : inGenre(siteWide, scope);
        return same.equals(withoutPage(listingUrl)) ? listingUrl : siteWide;
    }

    /** A genre's, an author's or a reader's books without sort and page: the server sorts each of them itself. */
    @Override
    public String genreOf(String url) {
        Kind kind = kind(url);
        if (kind != Kind.GENRE && kind != Kind.AUTHOR && kind != Kind.READER) {
            return null;
        }
        return site.server + "/" + join(path(url), "/");
    }

    @Override
    public String inGenre(String siteWide, String genre) {
        return genre + "?" + sortOf(siteWide);
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
    public ProviderPage<GenrePOJO> loadPeople(String url, int pageNo, boolean performers, PeopleCursor cursor)
            throws IOException {
        return walkPeople(this::fetch, pageNo, performers, cursor);
    }

    @Override
    public String searchUrl(SearchUrls.Category category, String query, int page) {
        String type;
        switch (category) {
            case AUTHORS: type = "type=authors&"; break;
            case PERFORMERS: type = "type=readers&"; break;
            default: type = ""; break;
        }
        return url(Kind.SEARCH) + "?" + type + "q=" + SearchUrls.enc(query) + "&page=" + page;
    }

    @Override
    public ProviderPage<BookPOJO> searchResults(Document page, int pageNo) {
        return parseSearch(page);
    }

    @Override
    public ProviderPage<GenrePOJO> searchPeople(Document page, int pageNo, boolean performers) {
        return parsePeopleSearch(page, performers);
    }

    @Override
    public DescriptionPOJO description(Document page, String bookUrl) {
        return parseDescription(page);
    }

    @Override
    public ArrayList<AudioPOJO> tracks(Document page, String base) {
        return parseTracks(page);
    }

    @Override
    public ArrayList<SeriesPOJO> series(Document page, String bookUrl) {
        return parseSeries(page);
    }

    // ---------------------------------------------------------------- requests and URLs

    /** POSTs a GraphQL request; an answer without its data (a book or a person that is gone) is an error. */
    JsonPage post(JsonObject request) throws IOException {
        JsonPage page = SiteHttp.json(SiteHttp.connect(site.api)
                .method(Connection.Method.POST)
                .header("Content-Type", "application/json")
                .header("Origin", site.server)
                .referrer(site.server + "/")
                .requestBody(request.toString()));
        String error = error(page);
        if (error != null) {
            throw new IOException(site.api + ": " + error);
        }
        return page;
    }

    /** The request ({@code query} and {@code variables}) that reads the app URL {@code url}; null for another page. */
    JsonObject request(String url) {
        Kind kind = kind(url);
        if (kind == null) {
            return null;
        }
        List<String> names = named(kind, path(url));
        JsonObject v = new JsonObject();
        for (int i = 0; i < names.size(); i++) {
            v.addProperty(String.valueOf((char) ('a' + i)), names.get(i));
        }
        String query;
        switch (kind) {
            case ALL:
                query = listAllQuery;
                v.add("q", filter(url));
                break;
            case GENRE:
            case READER:
            case AUTHOR:
                query = listIn(kind.name().toLowerCase(Locale.ROOT));
                v.add("q", filter(url));
                break;
            case SERIES:
                query = listIn("series");
                v.add("q", sources()); // by the numbers in the series
                break;
            case BOOK:
                v.add("s", sources());
                v.addProperty("n", SERIES_MAX);
                return body(bookQuery, v);
            case GENRES:
                return body(genresQuery, v);
            case AUTHORS:
            case READERS:
                return peopleRequest(kind == Kind.READERS, page(url));
            default: // SEARCH
                boolean people = ListingFilters.param(url, "type") != null;
                v.addProperty("t", words(url));
                v.add("src", fullSources());
                if (people) {
                    v.add("s", sources());
                }
                return body(people ? searchPeopleQuery : searchBooksQuery, v);
        }
        v.addProperty("p", page(url));
        v.addProperty("n", PER_PAGE);
        return body(query, v);
    }

    /**
     * Page {@code pageNo} of the authors or readers with full books: page {@code pageNo + skipped} of the site's list;
     * a page without any is skipped (up to {@link #MAX_PEOPLE_PAGES} in a row), so the list never shows an empty page.
     */
    ProviderPage<GenrePOJO> walkPeople(BookPages.Fetch pages, int pageNo, boolean performers, PeopleCursor cursor)
            throws IOException {
        if (pageNo <= 1) {
            cursor.skipped = 0;
        }
        String list = url(performers ? Kind.READERS : Kind.AUTHORS);
        ProviderPage<GenrePOJO> rows = ProviderPage.empty();
        for (int read = 0; read < MAX_PEOPLE_PAGES; read++) {
            String page = ListingFilters.withParam(list, "page", String.valueOf(pageNo + cursor.skipped));
            rows = parsePeople(pages.get(page), performers);
            if (!rows.items.isEmpty() || !rows.hasMore) {
                return rows;
            }
            cursor.skipped++;
        }
        return rows;
    }

    /** API page {@code page} of the authors or readers with their full-book counts. */
    JsonObject peopleRequest(boolean performers, int page) {
        JsonObject v = new JsonObject();
        v.addProperty("p", page);
        v.addProperty("n", PEOPLE_PER_PAGE);
        v.add("s", sources());
        return body(peopleList(performers ? "readers" : "authors"), v);
    }

    private static JsonObject body(String query, JsonObject variables) {
        JsonObject body = new JsonObject();
        body.addProperty("query", query);
        body.add("variables", variables);
        return body;
    }

    /** The books filter of a listing: full books only, in the sort {@code url} names. */
    static JsonObject filter(String url) {
        JsonObject q = sources();
        String[] sort = sortOf(url).split("=");
        q.addProperty(sort[0], sort[1]);
        if (sort[0].equals("by_fixed_top")) {
            q.addProperty("by_date", "desc"); // as the site's /top asks for it
        }
        return q;
    }

    /** {@code {"by_source": [...]}}: the books that play in full. */
    static JsonObject sources() {
        JsonObject q = new JsonObject();
        q.add("by_source", fullSources());
        return q;
    }

    private static JsonArray fullSources() {
        JsonArray sources = new JsonArray();
        for (String source : FULL_SOURCES) {
            sources.add(source);
        }
        return sources;
    }

    /** The sort parameter {@code url} carries ("by_pop=desc"); {@link #NEWEST} when it carries none. */
    static String sortOf(String url) {
        for (String key : SORT_KEYS) {
            String value = ListingFilters.param(url, key);
            if (value != null) {
                return key + "=" + ("asc".equals(value) ? "asc" : "desc");
            }
        }
        return NEWEST;
    }

    /** The sort parameter of a menu sort; null for a sort the site has not. */
    static String sortParam(Sort sort) {
        switch (sort) {
            case NEW: return NEWEST;
            case POPULAR: return "by_fixed_top=desc";
            case RATING: return "by_pop=desc";
            case TITLE: return "by_alphabet=asc";
            default: return null;
        }
    }

    /** What the app URL {@code url} is: the route with the most fixed segments that matches; null for another page. */
    Kind kind(String url) {
        List<String> path = path(url);
        if (path == null) {
            return null;
        }
        if (path.isEmpty()) {
            return Kind.ALL;
        }
        Kind found = null;
        int best = -1;
        for (Map.Entry<Kind, String[]> route : site.routes.entrySet()) {
            int fixed = matches(route.getValue(), path);
            if (fixed > best) {
                best = fixed;
                found = route.getKey();
            }
        }
        return found;
    }

    /** The fixed segments of {@code route} when it matches {@code path}; -1 when it does not. */
    private static int matches(String[] route, List<String> path) {
        if (route.length != path.size()) {
            return -1;
        }
        int fixed = 0;
        for (int i = 0; i < route.length; i++) {
            if (!route[i].equals("*")) {
                if (!route[i].equals(path.get(i))) {
                    return -1;
                }
                fixed++;
            }
        }
        return fixed;
    }

    /** The names a path of {@code kind} gives its record, in route order ({@code $a}, {@code $b}). */
    private List<String> named(Kind kind, List<String> path) {
        List<String> names = new ArrayList<>();
        String[] route = site.routes.get(kind);
        for (int i = 0; route != null && i < route.length; i++) {
            if (route[i].equals("*")) {
                names.add(path.get(i));
            }
        }
        return names;
    }

    /** The URL of the page of {@code kind} that {@code names} name; "" without one of them. */
    String url(Kind kind, String... names) {
        StringBuilder url = new StringBuilder(site.server);
        int next = 0;
        for (String segment : site.routes.get(kind)) {
            String name = segment.equals("*") ? names[next++] : segment;
            if (name.isEmpty()) {
                return "";
            }
            url.append('/').append(name);
        }
        return url.toString();
    }

    /** The path segments of {@code url}, empty ones left out; null when it is no URL. */
    private static List<String> path(String url) {
        HttpUrl parsed = url == null ? null : HttpUrl.parse(url);
        if (parsed == null) {
            return null;
        }
        List<String> path = new ArrayList<>();
        for (String segment : parsed.pathSegments()) {
            if (!segment.isEmpty()) {
                path.add(segment);
            }
        }
        return path;
    }

    /** The {@code page} of {@code url}; 1 when it carries none. */
    static int page(String url) {
        String page = ListingFilters.param(url, "page");
        try {
            return page == null ? 1 : Math.max(1, Integer.parseInt(page));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** {@code url} without its {@code page}: the listing's first page. */
    static String withoutPage(String url) {
        return url.replaceFirst("([?&])page=[^&#]*&?", "$1").replaceFirst("[?&]$", "");
    }

    /** The words of a search URL ({@code q}), decoded. */
    private static String words(String url) {
        String q = ListingFilters.param(url, "q");
        try {
            return q == null ? "" : URLDecoder.decode(q, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        } catch (IllegalArgumentException malformed) {
            return q;
        }
    }

    /** The page of the book {@code o}; "" when it lacks a name its URL needs. */
    String bookUrl(JsonObject o) {
        String[] names = new String[site.bookKeys.length];
        for (int i = 0; i < names.length; i++) {
            names[i] = string(o, site.bookKeys[i]);
        }
        return url(Kind.BOOK, names);
    }

    /** A listing of {@code kind} for the record named {@code name}, newest first; "" without the name. */
    private String newest(Kind kind, String name) {
        String url = url(kind, name);
        return url.isEmpty() ? "" : url + "?" + NEWEST;
    }

    // ---------------------------------------------------------------- listings

    /** The catalog, its sorts, a genre, an author, a reader or a series: the full books of the page. */
    public ProviderPage<BookPOJO> parseListing(Document page) {
        JsonObject x = record(page);
        JsonObject list = x.has("collection") ? x : object(x, "books");
        if (list == null) {
            throw new IllegalStateException("not a list of books: " + page.location());
        }
        List<JsonObject> items = objects(list, "collection");
        return ProviderPage.of(cards(items), hasMore(list), items.size());
    }

    /** The books of a search, at most 20, in one page. */
    public ProviderPage<BookPOJO> parseSearch(Document page) {
        List<JsonObject> books = found(page, "Book");
        return ProviderPage.of(cards(books), false, books.size());
    }

    /**
     * True for a book that plays in full: of one of the {@link #FULL_SOURCES} (shown as none to this origin), not paid,
     * not blocked, with a duration and chapters. Some full books (5 of the first 60 by title, 2026-11-21) list a duration
     * but no chapter at all ({@code chaptersCount} 0, the book page has none either); the count is otherwise one more than
     * the chapters at times, so only its zero is trusted.
     */
    static boolean playable(JsonObject o) {
        JsonElement chapters = o.get("chaptersCount");
        boolean none = chapters != null && chapters.isJsonPrimitive() && chapters.getAsJsonPrimitive().isNumber()
                && chapters.getAsInt() == 0;
        return !stub(o) && !flag(o, "copyrightBlock") && Json.seconds(o.get("duration")) > 0 && !none;
    }

    /** A LitRes trial, an author.today fragment or a paid book. */
    private static boolean stub(JsonObject o) {
        String source = string(o, "source");
        return !(source.isEmpty() || FULL_SOURCES.contains(source)) || flag(o, "isBiblio");
    }

    private static boolean hasMore(JsonObject list) {
        JsonObject meta = object(list, "metadata");
        return meta != null && number(meta, "currentPage") < number(meta, "totalPages");
    }

    private ArrayList<BookPOJO> cards(List<JsonObject> items) {
        ArrayList<BookPOJO> books = new ArrayList<>();
        for (JsonObject item : items) {
            if (playable(item)) {
                BookPOJO book = card(item);
                if (!book.isNull()) {
                    books.add(book);
                }
            }
        }
        return books;
    }

    private BookPOJO card(JsonObject o) {
        BookPOJO book = new BookPOJO();
        book.setUrl(bookUrl(o));
        book.setName(clean(string(o, "title")));
        book.setPhoto(cover(o));
        book.setAutor(authors(o));
        book.setUrlAutor(newest(Kind.AUTHOR, authorKey(o)));
        book.setArtist(names(objects(o, "readers")));
        book.setUrlArtist(newest(Kind.READER, firstKey(objects(o, "readers"))));
        book.setTime(time(Json.seconds(o.get("duration"))));
        JsonObject genre = firstGenre(o);
        if (genre != null) {
            book.setGenre(clean(string(genre, "name")));
            book.setUrlGenre(newest(Kind.GENRE, string(genre, site.key)));
        }
        JsonObject series = object(o, "series");
        if (series != null) {
            book.setSeries(clean(string(series, "name")));
            book.setUrlSeries(url(Kind.SERIES, string(series, "aliasName")));
        }
        return book;
    }

    /**
     * The genres, in the site's order ({@code position}): each top genre followed by its sub-genres, a sub-genre
     * described by its parents ("Фантастика / Фэнтези").
     */
    public ArrayList<GenrePOJO> parseGenres(Document page) {
        List<JsonObject> all = objects(record(page), "collection");
        Set<String> aliases = new HashSet<>();
        for (JsonObject genre : all) {
            aliases.add(string(genre, "aliasName"));
        }
        List<JsonObject> top = new ArrayList<>();
        Map<String, List<JsonObject>> children = new HashMap<>();
        for (JsonObject genre : all) {
            JsonObject parent = object(genre, "parent");
            String parentAlias = parent == null ? "" : string(parent, "aliasName");
            if (parentAlias.isEmpty() || !aliases.contains(parentAlias)) {
                top.add(genre);
            } else {
                List<JsonObject> siblings = children.get(parentAlias);
                if (siblings == null) {
                    siblings = new ArrayList<>();
                    children.put(parentAlias, siblings);
                }
                siblings.add(genre);
            }
        }
        ArrayList<GenrePOJO> out = new ArrayList<>();
        addGenres(out, top, children, "", 0);
        return out;
    }

    private void addGenres(ArrayList<GenrePOJO> out, List<JsonObject> genres,
            Map<String, List<JsonObject>> children, String parents, int depth) {
        Collections.sort(genres, (a, b) -> {
            int byPosition = Integer.compare(number(a, "position"), number(b, "position"));
            return byPosition != 0 ? byPosition : Integer.compare(number(a, "id"), number(b, "id"));
        });
        for (JsonObject genre : genres) {
            String name = clean(string(genre, "name"));
            String url = newest(Kind.GENRE, string(genre, site.key));
            if (name.isEmpty() || url.isEmpty()) {
                continue;
            }
            GenrePOJO pojo = new GenrePOJO(name, url);
            if (!parents.isEmpty()) {
                pojo.setDescription(parents);
            }
            out.add(pojo);
            List<JsonObject> sub = children.get(string(genre, "aliasName"));
            if (sub != null && depth < 3) {
                addGenres(out, sub, children, parents.isEmpty() ? name : parents + " / " + name, depth + 1);
            }
        }
    }

    /** A page of the authors or readers: those with full books, their count as the rating. */
    public ProviderPage<GenrePOJO> parsePeople(Document page, boolean performers) {
        JsonObject list = record(page);
        List<JsonObject> items = objects(list, "collection");
        return ProviderPage.of(people(items, performers), hasMore(list), items.size());
    }

    /** The authors or readers a search found, at most 10, in one page. */
    public ProviderPage<GenrePOJO> parsePeopleSearch(Document page, boolean performers) {
        List<JsonObject> found = found(page, performers ? "Reader" : "Author");
        return ProviderPage.of(people(found, performers), false, found.size());
    }

    private ArrayList<GenrePOJO> people(List<JsonObject> items, boolean performers) {
        ArrayList<GenrePOJO> people = new ArrayList<>();
        for (JsonObject o : items) {
            String name = clean(string(o, "name"));
            String url = newest(performers ? Kind.READER : Kind.AUTHOR, string(o, site.key));
            int books = fullBooks(o);
            if (!name.isEmpty() && !url.isEmpty() && books > 0) {
                GenrePOJO person = new GenrePOJO(name, url);
                person.setReting(books);
                people.add(person);
            }
        }
        return people;
    }

    private static int fullBooks(JsonObject person) {
        JsonObject books = object(person, "books");
        JsonObject meta = books == null ? null : object(books, "metadata");
        return meta == null ? 0 : number(meta, "totalEntries");
    }

    /** The results of a search of the type {@code typename} ("Book", "Author", "Reader"). */
    private static List<JsonObject> found(Document page, String typename) {
        JsonElement x = x(page);
        if (!x.isJsonArray()) {
            throw new IllegalStateException("not a search: " + page.location());
        }
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement item : x.getAsJsonArray()) {
            if (item.isJsonObject() && typename.equals(string(item.getAsJsonObject(), "__typename"))) {
                out.add(item.getAsJsonObject());
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- book

    /** The book: all authors and readers, the first genre, the series with the book's number, likes. */
    public DescriptionPOJO parseDescription(Document page) {
        JsonElement x = data(page);
        JsonObject o = x != null && x.isJsonObject() ? x.getAsJsonObject() : new JsonObject();
        DescriptionPOJO d = new DescriptionPOJO();
        d.setTitle(clean(string(o, "title")));
        d.setPoster(cover(o));
        d.setAutor(authors(o));
        d.setAutorUrl(newest(Kind.AUTHOR, authorKey(o)));
        d.setArtist(names(objects(o, "readers")));
        d.setArtistUrl(newest(Kind.READER, firstKey(objects(o, "readers"))));
        JsonObject genre = firstGenre(o);
        if (genre != null) {
            d.setGenre(clean(string(genre, "name")));
            d.setGenreUrl(newest(Kind.GENRE, string(genre, site.key)));
        }
        JsonObject series = object(o, "series");
        if (series != null) {
            String number = seriesNumber(o);
            d.setSeries(clean(string(series, "name")) + (number.isEmpty() ? "" : " (" + number + ")"));
            d.setSeriesUrl(url(Kind.SERIES, string(series, "aliasName")));
        }
        int heard = chapterSeconds(o);
        d.setTime(time(heard > 0 ? heard : Json.seconds(o.get("duration"))));
        d.setLike(String.valueOf(number(o, "likesCount")));
        d.setDisLike(String.valueOf(number(o, "dislikesCount")));
        d.setDescription(Html.paragraphs(string(o, "description")));
        return d;
    }

    /**
     * The chapters, an MP3 each; a stub (a trial, a fragment, a paid book) or a book its rights holder blocked is a
     * {@link BookRemovedException}, as the site refuses to play them.
     */
    public ArrayList<AudioPOJO> parseTracks(Document page) {
        JsonObject o = record(page);
        String bookUrl = bookUrl(o);
        if (stub(o) || flag(o, "copyrightBlock")) {
            throw new BookRemovedException(bookUrl);
        }
        Players.Chapters chapters = new Players.Chapters(clean(string(o, "title")), site.server + "/");
        JsonObject list = object(o, "chapters");
        chapters.each(list == null ? new JsonArray() : Json.arrayOf(list, "collection"), item -> {
            JsonObject c = item.getAsJsonObject();
            return chapters.chapter(clean(string(c, "name")), string(c, "fileUrl"), Json.seconds(c.get("duration")));
        });
        return chapters.done(bookUrl);
    }

    /** The full books of the book's series, in the series' order, with their numbers. */
    public ArrayList<SeriesPOJO> parseSeries(Document page) {
        JsonObject series = object(record(page), "series");
        JsonObject books = series == null ? null : object(series, "books");
        ArrayList<SeriesPOJO> out = new ArrayList<>();
        for (JsonObject b : books == null ? Collections.<JsonObject>emptyList() : objects(books, "collection")) {
            String url = bookUrl(b);
            String name = clean(string(b, "title"));
            if (!playable(b) || url.isEmpty() || name.isEmpty()) {
                continue;
            }
            SeriesPOJO item = new SeriesPOJO();
            item.setUrl(url);
            item.setName(name);
            item.setNumber(seriesNumber(b));
            item.setPhoto(cover(b));
            out.add(item);
        }
        return out;
    }

    private static int chapterSeconds(JsonObject o) {
        JsonObject list = object(o, "chapters");
        int seconds = 0;
        for (JsonObject chapter : list == null ? Collections.<JsonObject>emptyList() : objects(list, "collection")) {
            seconds += Json.seconds(chapter.get("duration"));
        }
        return seconds;
    }

    /** "7" for 7.0, "2.5" for 2.5; "" for none. */
    static String seriesNumber(JsonObject o) {
        JsonElement e = o.get("seriesNum");
        double n = e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : 0;
        if (n <= 0) {
            return "";
        }
        return n == Math.rint(n) ? String.valueOf((long) n) : String.valueOf(n);
    }

    // ---------------------------------------------------------------- JSON fields

    /**
     * The error of an answer without its data ("Cannot return null for non-nullable field Query.book" for a book or a
     * person that is gone); null when the answer has its data.
     */
    static String error(Document page) {
        if (data(page) != null) {
            return null;
        }
        JsonElement root = JsonPage.of(page);
        JsonElement errors = root.isJsonObject() ? root.getAsJsonObject().get("errors") : null;
        if (errors != null && errors.isJsonArray()) {
            for (JsonElement e : errors.getAsJsonArray()) {
                String message = e.isJsonObject() ? string(e.getAsJsonObject(), "message") : "";
                if (!message.isEmpty()) {
                    return message;
                }
            }
        }
        return "no data";
    }

    /** {@code data.x}, the answer; null when there is none. */
    private static JsonElement data(Document page) {
        JsonElement root = JsonPage.of(page);
        JsonObject data = root.isJsonObject() ? object(root.getAsJsonObject(), "data") : null;
        JsonElement x = data == null ? null : data.get("x");
        return x == null || x.isJsonNull() ? null : x;
    }

    private static JsonElement x(Document page) {
        JsonElement x = data(page);
        if (x == null) {
            throw new IllegalStateException("no answer in " + page.location() + ": " + error(page));
        }
        return x;
    }

    private static JsonObject record(Document page) {
        JsonElement x = x(page);
        if (!x.isJsonObject()) {
            throw new IllegalStateException("not an object: " + page.location());
        }
        return x.getAsJsonObject();
    }

    private static JsonObject object(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
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

    private static JsonObject firstGenre(JsonObject o) {
        JsonObject genres = object(o, "genres");
        List<JsonObject> all = genres == null ? Collections.<JsonObject>emptyList() : objects(genres, "collection");
        return all.isEmpty() ? null : all.get(0);
    }

    private String firstKey(List<JsonObject> people) {
        return people.isEmpty() ? "" : string(people.get(0), site.key);
    }

    /** The authors, or the one the book names when it lists none. */
    private static String authors(JsonObject o) {
        String names = names(objects(o, "authors"));
        return names.isEmpty() ? clean(string(o, "authorName")) : names;
    }

    /** The name of the first author's page, or of the one the book names when it lists none. */
    private String authorKey(JsonObject o) {
        String key = firstKey(objects(o, "authors"));
        return key.isEmpty() && site.bookAuthor != null ? string(o, site.bookAuthor) : key;
    }

    /**
     * The names, comma-separated; a name listed again with its words in another order ("Сергеев Максим", "Максим
     * Сергеев": the site lists some people twice) once.
     */
    static String names(List<JsonObject> people) {
        StringBuilder out = new StringBuilder();
        Set<String> seen = new HashSet<>();
        for (JsonObject person : people) {
            String name = clean(string(person, "name"));
            if (!name.isEmpty() && seen.add(sameName(name))) {
                out.append(out.length() == 0 ? "" : ", ").append(name);
            }
        }
        return out.toString();
    }

    private static String sameName(String name) {
        String[] words = name.toLowerCase(Locale.ROOT).replace('ё', 'е').split("\\s+");
        Arrays.sort(words);
        return join(Arrays.asList(words), " ");
    }

    /** The 360-pixel cover, else the original one, absolute. */
    private String cover(JsonObject o) {
        String path = string(o, "cover360");
        if (path.isEmpty()) {
            path = string(o, "cover");
        }
        return path.startsWith("/") ? site.server + path : path.startsWith("https://") ? path : "";
    }

    /** H:MM:SS of a number of seconds; "" for none. */
    private static String time(int s) {
        return s <= 0 ? "" : String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
    }

    /** Without the no-break and other spaces the site leaves around names and titles. */
    private static String clean(String s) {
        return s.replace(' ', ' ').trim();
    }

    private static int number(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            return 0;
        }
        try {
            return e.getAsInt(); // ids are strings of digits
        } catch (NumberFormatException notNumber) {
            return 0;
        }
    }

    private static boolean flag(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsBoolean();
    }

    private static String join(List<String> parts, String separator) {
        StringBuilder s = new StringBuilder();
        for (String part : parts) {
            if (s.length() > 0) {
                s.append(separator);
            }
            s.append(part);
        }
        return s.toString();
    }
}
