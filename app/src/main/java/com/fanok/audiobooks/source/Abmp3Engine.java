package com.fanok.audiobooks.source;

import static com.fanok.audiobooks.pojo.BookPOJO.absUrl;
import static com.fanok.audiobooks.source.Html.cards;
import static com.fanok.audiobooks.source.Html.each;
import static com.fanok.audiobooks.source.Html.lastPage;
import static com.fanok.audiobooks.source.Html.ownPath;
import static com.fanok.audiobooks.source.Html.rows;
import static com.fanok.audiobooks.source.Html.text;

import android.util.Log;

import androidx.annotation.Nullable;

import com.fanok.audiobooks.AutorsSearchABMP3;
import com.fanok.audiobooks.Url;
import com.fanok.audiobooks.model.BookPages;
import com.fanok.audiobooks.model.ComentsModel;
import com.fanok.audiobooks.model.OtherArtistModel;
import com.fanok.audiobooks.model.OtherSourceModel;
import com.fanok.audiobooks.net.SiteHttp;
import com.fanok.audiobooks.pojo.AudioPOJO;
import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.pojo.ComentsPOJO;
import com.fanok.audiobooks.pojo.DescriptionPOJO;
import com.fanok.audiobooks.pojo.GenrePOJO;
import com.fanok.audiobooks.pojo.OtherArtistPOJO;
import com.fanok.audiobooks.pojo.SeriesPOJO;
import com.fanok.audiobooks.search.ProviderPage;
import com.fanok.audiobooks.search.SearchUrls;
import com.fanok.audiobooks.util.DownloadUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.reactivex.functions.BooleanSupplier;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;

/**
 * The Yii engine of audiobook-mp3.com and the sites that run it (audio-kniga.biz, baza-knig.info): plain GETs, the
 * same URLs ({@code /audio-<id>-<slug>}, {@code /genre-}, {@code /avtor-}, {@code /ispolnitel-}, {@code /series-}),
 * the same book cards, rows, search and comments. What a site differs in is data, its {@link Site}: its server and
 * sorts, a paging cap, how a plain-text title names its author, and the markup of its book page and rows; the code
 * never asks which site it serves.
 * <ul>
 * <li>Listings (the index, the Top-100, a genre, author, reader or series page) page with the {@code page} query
 * parameter alone ({@code Url.abmp3ListingPage}); a site may refuse every page past its {@link Site#maxPage} (HTTP
 * 403), so its listings end there. Author, reader, series and genre listings are not re-sorted.</li>
 * <li>Genres {@code /genres}, one page. Readers {@code /performers?page=N}. The authors' index is an alphabet:
 * {@link #loadPeople} walks its letters one after another, each letter's own pages too.</li>
 * <li>The search {@code /search?text=Q} is one static page of every hit (books, then authors), sliced into pages of
 * {@link #SEARCH_PAGE_SIZE} here; no reader search.</li>
 * <li>Book pages carry a Playerjs whose {@code file} is a playlist URL on a CDN that wants the site as Referer;
 * {@code [{title, file, start, end}]} items that share one file are its time ranges. Other narrations are found by
 * searching the title.</li>
 * </ul>
 */
public class Abmp3Engine implements SourceParser {

    /** The CDN of the engine's sites (covers, playlists, audio), shared by all of them. */
    static final String CDN = "redirectto.cc";

    /** The search returns every hit on one page; it is sliced client-side. */
    static final int SEARCH_PAGE_SIZE = 40;
    /** Pages one letter of the authors' index may take in the walk of {@link #loadPeople}; the walk skips the rest. */
    static final int LETTER_SLOTS = 1000;
    /** Bound of the series pages read for a book's series tab. */
    static final int MAX_SERIES_PAGES = 10;

    static final String AUTHOR = "a[href*=/avtor-]";
    static final String READER = "a[href*=/ispolnitel-]";
    static final String GENRE = "a[href*=/genre-]";
    static final String SERIES = "a[href*=/series-]";

    private static final String TAG = "ComentsModel";
    private static final Pattern NUMBER = Pattern.compile("\\((\\d+)\\)");

    /** How a plain-text title (a search hit, a similar book, a card without an author link) names its author. */
    public enum Byline {
        /** The text is the title alone. */
        NONE,
        /** "Author - Title". */
        AUTHOR_FIRST,
        /** "Title - Author". */
        AUTHOR_LAST
    }

    /**
     * What one site of the engine differs in. The defaults are audiobook-mp3.com's markup; a site sets what its pages
     * do otherwise, once, in its adapter's constructor.
     */
    public static final class Site {
        final String server;
        /** The Top-100 listing; null when the site has none. */
        String top;
        /** The page that redirects to a random book; null when the site has none. */
        String random;
        /** The last listing page the site serves (it answers HTTP 403 past it); listings end there. */
        int maxPage = Integer.MAX_VALUE;
        Byline byline = Byline.NONE;
        /** The covers' CDN path that a search hit's {@code /audio-<id>-<slug>} completes; null when underivable. */
        String hitCovers;
        String hitCoverSlug = "";
        /** The Referer of the site's own hosts; null for none. */
        String referer;
        /** True when chapters may be time ranges of one shared file (and so download once). */
        boolean sharedFiles;
        /** True when a book page links its series, so the series tab reads it. */
        boolean seriesOnPage;
        // the book page
        String cover = ".abook_image";
        String title = "h1";
        String about = ".abook-desc";
        /** Where the book page links its author, reader, genre and series ({@link #AUTHOR} ...). */
        String info = ".panel-item";
        String time = ".panel-item:has(.fa-clock-o)";
        String views = ".panel-item:has(.fa-eye)";
        // genre and people rows
        String rowLink = ".title a";
        String rowCount = ".rating";
        /** The authors' index letters, Russian first: each query's links in page order. */
        String[] letters = {".alphabet > :last-child > a", ".alphabet > :first-child > a"};

        public Site(String server) {
            this.server = server;
        }

        public Site top(String url) {
            top = url;
            return this;
        }

        public Site random(String url) {
            random = url;
            return this;
        }

        public Site maxPage(int page) {
            maxPage = page;
            return this;
        }

        public Site byline(Byline order) {
            byline = order;
            return this;
        }

        public Site hitCovers(String path, String slugPrefix) {
            hitCovers = path;
            hitCoverSlug = slugPrefix;
            return this;
        }

        public Site referer(String url) {
            referer = url;
            return this;
        }

        public Site sharedFiles() {
            sharedFiles = true;
            return this;
        }

        public Site seriesOnPage() {
            seriesOnPage = true;
            return this;
        }

        /** The book page's cover, title, annotation (own text), people links, duration and views. */
        public Site bookPage(String cover, String title, String about, String info, String time, String views) {
            this.cover = cover;
            this.title = title;
            this.about = about;
            this.info = info;
            this.time = time;
            this.views = views;
            return this;
        }

        /** A genre or people row's link and its book count. */
        public Site rows(String link, String count) {
            rowLink = link;
            rowCount = count;
            return this;
        }

        public Site letters(String... queries) {
            letters = queries;
            return this;
        }
    }

    protected final Site site;
    private final List<Sort.Entry> menu;

    protected Abmp3Engine(Site site) {
        this.site = site;
        List<Sort> sorts = new ArrayList<>();
        sorts.add(Sort.NEW);
        if (site.top != null) {
            sorts.add(Sort.TOP_100);
        }
        if (site.random != null) {
            sorts.add(Sort.RANDOM);
        }
        menu = Sort.menu(sorts.toArray(new Sort[0]));
    }

    // ---------------------------------------------------------------- SourceParser

    @Override
    public String referer() {
        return site.referer;
    }

    /**
     * The engine's covers and playlists are on the redirectto.cc CDN that its sites share (audiobook-mp3.com's own
     * covers are on its cdn subdomain): a site with a Referer gives it to the CDN's requests that carry none, since the
     * CDN answers 403 without one of the family. The CDN keeps the global proxy route.
     */
    @Override
    public Set<String> refererHosts() {
        return site.referer == null ? Collections.<String>emptySet() : Collections.singleton(CDN);
    }

    /** The engine's search hits carry a title, an author and a link only. */
    @Override
    public boolean searchHitsNeedBook() {
        return true;
    }

    @Override
    public ProviderPage<BookPOJO> listing(Document page, String url, int pageNo) {
        boolean hasMore = more(page, pageNo);
        Element bookList = page.selectFirst(".b-posts");
        if (bookList == null) {
            return ProviderPage.empty();
        }
        return ProviderPage.of(cards(bookList.getElementsByClass("abook-item"), this::card), hasMore);
    }

    @Override
    public String pageUrl(String url, int page) {
        return Url.abmp3ListingPage(url, page);
    }

    /** The random-book page redirects to one random book's page: that book is the listing's one card. */
    @Override
    public ProviderPage<BookPOJO> loadListing(String url, int pageNo) throws IOException {
        Document page = fetch(url);
        if (site.random == null || !url.startsWith(site.random)) {
            return listing(page, url, pageNo);
        }
        BookPOJO book = book(page, page.location());
        return book.isNull() ? ProviderPage.<BookPOJO>empty()
                : ProviderPage.of(new ArrayList<>(Collections.singletonList(book)), false, 1);
    }

    /** Author ({@code /avtor-}), reader ({@code /ispolnitel-}), series and genre listings are not re-sorted. */
    @Override
    public List<Sort.Entry> sorts(String url) {
        boolean noSort = url.contains("reader") || url.contains("author") || url.contains("serie")
                || url.contains("cikl") || url.contains("genre") || url.contains("/avtor-")
                || url.contains("/ispolnitel-");
        return noSort ? Collections.<Sort.Entry>emptyList() : menu;
    }

    @Override
    public String sortUrl(Sort sort, String listingUrl) {
        switch (sort) {
            case NEW: return site.server + "/?page=";
            case TOP_100: return site.top;
            case RANDOM: return site.random;
            default: return null;
        }
    }

    @Override
    public ProviderPage<GenrePOJO> genres(Document page, String url, int pageNo) {
        Element items = page.selectFirst(".b-posts");
        if (items == null) {
            return ProviderPage.empty();
        }
        return ProviderPage.of(rows(items.children(), item -> row(item, true)), false);
    }

    /** A paged reader (or author) listing, or the authors' alphabet read from its first letter. */
    @Override
    public ProviderPage<GenrePOJO> people(Document page, String url, int pageNo, boolean performers)
            throws IOException {
        return people(page, pageNo, new PeopleCursor(), this::fetch);
    }

    /** The authors' index is the same alphabet on every page: its first page is read (later ones may be 403). */
    @Override
    public ProviderPage<GenrePOJO> loadPeople(String url, int pageNo, boolean performers, PeopleCursor cursor)
            throws IOException {
        return people(fetch(performers ? url : pageUrl(url, 1)), pageNo, cursor, this::fetch);
    }

    /** One static results page; {@link #searchResults} slices it into pages of {@link #SEARCH_PAGE_SIZE}. */
    @Override
    public String searchUrl(SearchUrls.Category category, String query, int page) {
        if (category == SearchUrls.Category.PERFORMERS) {
            return null;
        }
        return site.server + "/search?text=" + SearchUrls.enc(query) + "&page=" + page;
    }

    /** The search returns every hit at once: one request per page, sliced locally, no book-page fetches (SRCH-13). */
    @Override
    public ProviderPage<BookPOJO> searchResults(Document doc, int page) {
        Elements hits = booksBlock(doc);
        int from = Math.min((page - 1) * SEARCH_PAGE_SIZE, hits.size());
        int to = Math.min(page * SEARCH_PAGE_SIZE, hits.size());
        ArrayList<BookPOJO> result = cards(new Elements(hits.subList(from, to)), book -> {
            Element aTag = book.selectFirst("a");
            if (aTag == null) {
                return null;
            }
            BookPOJO bookPOJO = new BookPOJO();
            String href = aTag.attr("href");
            if (!href.isEmpty()) {
                bookPOJO.setUrl(site.server + href);
                String cover = coverUrl(href);
                if (cover != null) {
                    bookPOJO.setPhoto(cover);
                }
            }
            String[] hit = hit(aTag);
            if (!hit[0].trim().isEmpty()) {
                bookPOJO.setName(hit[0].trim());
            }
            if (!hit[1].isEmpty()) {
                bookPOJO.setAutor(hit[1]);
            }
            if (!hit[2].isEmpty()) {
                bookPOJO.setArtist(hit[2]);
            }
            return bookPOJO;
        });
        return ProviderPage.of(result, hits.size() > to, to - from);
    }

    /** The author block of the same one-page search. */
    @Override
    public ProviderPage<GenrePOJO> searchPeople(Document page, int pageNo, boolean performers) {
        return AutorsSearchABMP3.parse(page, pageNo, site.server);
    }

    @Override
    public DescriptionPOJO description(Document page, String bookUrl) {
        BookPage p = new BookPage(page);
        DescriptionPOJO d = new DescriptionPOJO();
        if (p.cover != null) {
            d.setPoster(p.cover.absUrl("src"));
        }
        if (p.about != null) {
            d.setDescription(Html.ownParagraphs(p.about));
        }
        if (p.author != null) {
            d.setAutor(p.author.text());
            d.setAutorUrl(absUrl(p.author, "href", "?page="));
        }
        if (p.reader != null) {
            d.setArtist(p.reader.text());
            d.setArtistUrl(absUrl(p.reader, "href", "?page="));
        }
        if (p.genre != null) {
            d.setGenre(p.genre.text());
            d.setGenreUrl(absUrl(p.genre, "href", "?page="));
        }
        if (p.series != null) {
            d.setSeries(p.series.text());
            d.setSeriesUrl(absUrl(p.series, "href", "?page="));
        }
        if (p.time != null) {
            d.setTime(unlabelled(p.time.text()));
        }
        if (p.views != null) {
            d.setReiting(unlabelled(p.views.text()));
        }
        if (p.title != null) {
            d.setTitle(p.name());
        }
        Element like = page.selectFirst(".vote-count");
        if (like != null) {
            d.setLike(like.text());
        }
        return d;
    }

    /** A book opened by its link: the description's fields as a card, its views as the rating. */
    @Override
    public BookPOJO book(Document page, String bookUrl) {
        BookPage p = new BookPage(page);
        BookPOJO book = new BookPOJO();
        book.setUrl(bookUrl);
        if (p.cover != null) {
            book.setPhoto(p.cover.absUrl("src"));
        }
        if (p.about != null) {
            book.setDesc(Html.ownParagraphs(p.about));
        }
        if (p.author != null) {
            book.setAutor(p.author.text());
            book.setUrlAutor(absUrl(p.author, "href", "?page="));
        }
        if (p.reader != null) {
            book.setArtist(p.reader.text());
            book.setUrlArtist(absUrl(p.reader, "href", "?page="));
        }
        if (p.genre != null) {
            book.setGenre(p.genre.text());
            book.setUrlGenre(absUrl(p.genre, "href", "?page="));
        }
        if (p.series != null) {
            book.setSeries(p.series.text());
            book.setUrlSeries(absUrl(p.series, "href", "?page="));
        }
        if (p.time != null) {
            book.setTime(unlabelled(p.time.text()));
        }
        if (p.views != null) {
            book.setReting(unlabelled(p.views.text()));
        }
        if (p.title != null) {
            book.setName(p.name());
        }
        return book;
    }

    /** The "similar books" block of a book page. */
    @Override
    public ArrayList<BookPOJO> recommendations(Document document, String bookUrl) {
        ArrayList<BookPOJO> books = new ArrayList<>();
        Element root = document.selectFirst(".similar-abooks-block");
        if (root == null) {
            return books;
        }
        for (Element item : root.getElementsByClass("abook-similar-item")) {
            BookPOJO bookPOJO = new BookPOJO();
            Element a = item.selectFirst("a");
            if (a != null) {
                bookPOJO.setUrl(a.absUrl("href"));
                Element img = a.selectFirst("img");
                if (img != null) {
                    bookPOJO.setPhoto(img.absUrl("src"));
                }
                Element name = a.selectFirst("span");
                Element author = item.selectFirst(AUTHOR);
                Element reader = item.selectFirst(READER);
                if (name != null) {
                    String[] named = author == null ? split(name.text())
                            : new String[]{strip(name.text(), author.text()), author.text()};
                    bookPOJO.setName(named[0]);
                    bookPOJO.setAutor(named[1]);
                }
                if (author != null) {
                    bookPOJO.setUrlAutor(absUrl(author, "href", "?page="));
                }
                if (reader != null) {
                    bookPOJO.setArtist(reader.text());
                    bookPOJO.setUrlArtist(absUrl(reader, "href", "?page="));
                }
            }
            if (!bookPOJO.isNull()) {
                books.add(bookPOJO);
            }
        }
        return books;
    }

    /** The Playerjs playlist, fetched with the book page as Referer (its CDN refuses requests without the site's). */
    @Override
    public ArrayList<AudioPOJO> tracks(Document page, String base) throws IOException {
        String name = bookName(page);
        if (!site.sharedFiles) {
            return Players.loadPlayerjs(page, base, name);
        }
        String file = Players.playerjsFile(page);
        if (file.trim().startsWith("[")) {
            return parsePlaylist(file, base, name);
        }
        String playlist = Players.resolve(base, file);
        if (playlist.isEmpty()) {
            throw new IllegalStateException("Playerjs file is neither a list nor a URL");
        }
        return parsePlaylist(SiteHttp.body(SiteHttp.connect(playlist).referrer(base)), playlist, name);
    }

    @Override
    public Map<String, String> mediaHeaders(String bookUrl) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", DownloadUtil.DESKTOP_UA);
        headers.put("Referer", site.server + "/");
        return headers;
    }

    @Override
    public boolean downloadsSharedFiles() {
        return site.sharedFiles;
    }

    @Override
    public ArrayList<SeriesPOJO> series(Document page, String bookUrl) throws IOException {
        return series(page, this::fetch);
    }

    /** A site whose book pages link no series: nothing is fetched. */
    @Override
    public ArrayList<SeriesPOJO> loadSeries(String bookUrl) throws IOException {
        return site.seriesOnPage ? SourceParser.super.loadSeries(bookUrl) : new ArrayList<SeriesPOJO>();
    }

    @Override
    public ArrayList<ComentsPOJO> comments(String bookUrl, BooleanSupplier cancelled) throws Exception {
        // the page the description and the chapters were read from, not a second request to the site
        ComentsModel.Parsed parsed = parseComments(BookPages.get(bookUrl, this::fetch));
        if (parsed.skipped > 0) {
            Log.w(TAG, "skipped " + parsed.skipped + " malformed comments on " + bookUrl);
        }
        return parsed.comments;
    }

    @Override
    public boolean findsOtherReaders() {
        return true;
    }

    /** The title alone: the site's search finds nothing with the author added. */
    @Override
    public ArrayList<OtherArtistPOJO> otherReaders(String bookUrl, String title, String author, String reader)
            throws IOException {
        if (title == null || title.trim().isEmpty()) {
            return new ArrayList<>();
        }
        String terms = OtherSourceModel.query(title);
        return OtherArtistModel.otherReaders(otherSourceHits(fetch(otherSourceSearchUrl(terms, 1))), title, author,
                bookUrl, reader);
    }

    @Override
    public String otherSourceTerms(BookPOJO book) {
        return OtherSourceModel.query(book.getName());
    }

    /** The search page without a page number, as the other-source lookup has always asked it. */
    @Override
    public String otherSourceSearchUrl(String terms, int page) {
        return HttpUrl.get(site.server).newBuilder().addPathSegment("search").addQueryParameter("text", terms)
                .build().toString();
    }

    /** The hits of the search's books block, the author and reader read off their parentheses or the byline. */
    @Override
    public ArrayList<OtherSourceModel.Hit> otherSourceHits(Document doc) {
        return each(booksBlock(doc), item -> {
            Element a = item.selectFirst("a");
            if (a == null) {
                return null;
            }
            String[] hit = hit(a);
            return new OtherSourceModel.Hit(a.absUrl("href"), hit[0].trim(), hit[1], hit[2]);
        });
    }

    // ---------------------------------------------------------------- pure parsers

    /** The chapters' book name: the title without its author. */
    public String bookName(Document doc) {
        return new BookPage(doc).name();
    }

    /** The comments of a book page; a reply sits in the {@code li.comment} it answers. */
    public static ComentsModel.Parsed parseComments(Document doc) {
        Element list = doc.getElementById("w0");
        if (list == null) {
            return new ComentsModel.Parsed();
        }
        return ComentsModel.nest(list.select("li.comment"), "li.comment", (comment, pojo) -> {
            Element content = comment.selectFirst("> .comment-content");
            pojo.setImage(commentUrl(content, ".comment-author-avatar img", "src"));
            pojo.setName(commentText(content, ".comment-author-name > span:not(.comment-date)"));
            pojo.setDate(commentText(content, ".comment-date"));
            pojo.setText(commentText(content, ".comment-body"));
        });
    }

    /**
     * A playlist {@code [{title, file, start?, end?}]}: {@link Players#parsePlayerjsPlaylist}, with each chapter's
     * seconds from its bounds, and the bounds as its time range when other chapters play the same file.
     */
    public static ArrayList<AudioPOJO> parsePlaylist(String json, String baseUrl, String bookName) {
        ArrayList<AudioPOJO> chapters = Players.parsePlayerjsPlaylist(json, baseUrl, bookName);
        JsonArray items = Json.array(json);
        if (items.size() != chapters.size()) {
            return chapters; // an item was skipped or a folder flattened: the bounds no longer line up
        }
        Map<String, Integer> plays = new HashMap<>();
        for (AudioPOJO chapter : chapters) {
            Integer n = plays.get(chapter.getUrl());
            plays.put(chapter.getUrl(), n == null ? 1 : n + 1);
        }
        for (int i = 0; i < items.size(); i++) {
            JsonElement item = items.get(i);
            if (!item.isJsonObject() || item.getAsJsonObject().has("folder")) {
                return chapters;
            }
            JsonObject o = item.getAsJsonObject();
            int start = Json.seconds(o.get("start"));
            int end = Json.seconds(o.get("end"));
            AudioPOJO chapter = chapters.get(i);
            if (end > start) {
                chapter.setTime(end - start);
                if (plays.get(chapter.getUrl()) > 1) {
                    chapter.setTimeStart(start);
                    chapter.setTimeFinish(end);
                }
            }
        }
        return chapters;
    }

    /**
     * Page {@code pageNo} of a people list read from {@code page}: a paged listing, or (a page with an authors' list
     * and letters) the letters' pages one after another, which {@code cursor} keeps its place in.
     */
    ProviderPage<GenrePOJO> people(Document page, int pageNo, PeopleCursor cursor, BookPages.Fetch pages)
            throws IOException {
        if (page.selectFirst(".authors_list") == null) {
            Element parent = page.selectFirst(".b-posts");
            if (parent == null) {
                return ProviderPage.empty();
            }
            return ProviderPage.of(rows(parent.children(), item -> row(item, false)), more(page, pageNo));
        }
        List<String> letters = letters(page);
        if (letters.isEmpty()) {
            return ProviderPage.empty();
        }
        return walkLetters(letters, pageNo, cursor, pages);
    }

    /**
     * Page N of all authors: the letters' pages one after another. Page N is slot {@code N - 1 + skipped} of a walk
     * that gives each letter {@link #LETTER_SLOTS} slots; a letter's last page skips the rest of its slots, an empty
     * letter is passed over. A site whose letters are one page each makes page N its N-th letter.
     */
    ProviderPage<GenrePOJO> walkLetters(List<String> letters, int pageNo, PeopleCursor cursor, BookPages.Fetch pages)
            throws IOException {
        // the cursor changes only with a page returned: a failed refresh keeps the list's place
        int skipped = pageNo <= 1 ? 0 : cursor.skipped;
        while (true) {
            int slot = pageNo - 1 + skipped;
            int letter = slot / LETTER_SLOTS;
            int page = slot % LETTER_SLOTS + 1;
            if (letter >= letters.size()) {
                cursor.skipped = skipped;
                return ProviderPage.empty();
            }
            Document doc = pages.get(pageUrl(letters.get(letter), page));
            Element list = doc.selectFirst(".authors_list");
            ArrayList<GenrePOJO> found = list == null ? new ArrayList<GenrePOJO>()
                    : rows(list.children(), item -> row(item, false));
            boolean more = more(doc, page);
            boolean lastLetter = letter == letters.size() - 1;
            boolean empty = found.isEmpty() && !lastLetter;
            if (!more) {
                // the rest of the letter's slots; this very one too for an empty letter, read again as the next
                skipped += LETTER_SLOTS - page + (empty ? 1 : 0);
            }
            if (!empty) {
                cursor.skipped = skipped;
                return ProviderPage.of(found, more || !lastLetter);
            }
        }
    }

    /** The series of the book page, its listing read page by page; none when the book is in none. */
    ArrayList<SeriesPOJO> series(Document page, BookPages.Fetch pages) throws IOException {
        ArrayList<SeriesPOJO> result = new ArrayList<>();
        Element link = page.select(site.info).select(SERIES).first();
        String url = link == null ? "" : link.absUrl("href");
        for (int no = 1; !url.isEmpty() && no <= MAX_SERIES_PAGES; no++) {
            Document doc = pages.get(pageUrl(url, no));
            result.addAll(seriesBooks(doc));
            if (!more(doc, no)) {
                break;
            }
        }
        return result;
    }

    /** The authors' index letters, absolute, Russian ones first. */
    List<String> letters(Document page) {
        List<String> urls = new ArrayList<>();
        for (String query : site.letters) {
            for (Element a : page.select(query)) {
                String href = a.attr("href");
                if (ownPath(href)) {
                    urls.add(site.server + href.replaceFirst("/$", ""));
                }
            }
        }
        return urls;
    }

    /** The CDN cover of a search hit, derived from its {@code /audio-<id>-<slug>} link; null for any other link. */
    String coverUrl(String href) {
        if (site.hitCovers == null || href == null || href.isEmpty()) {
            return null;
        }
        int idStart = href.indexOf("/audio-");
        if (idStart < 0) {
            return null;
        }
        String rest = href.substring(idStart + 7);
        int dash = rest.indexOf('-');
        if (dash <= 0) {
            return null;
        }
        String id = rest.substring(0, dash);
        String slug = rest.substring(dash + 1);
        if (id.isEmpty() || slug.isEmpty() || !id.matches("\\d+")) {
            return null;
        }
        StringBuilder digitsPath = new StringBuilder();
        for (char c : id.toCharArray()) {
            digitsPath.append(c).append('/');
        }
        return site.hitCovers + digitsPath + site.hitCoverSlug + slug + ".jpg";
    }

    // ---------------------------------------------------------------- helpers

    /** True when the pager of page {@code pageNo} goes on and the site serves the next page. */
    boolean more(Document doc, int pageNo) {
        return lastPage(doc.select(".pagination li a")) > pageNo && pageNo < site.maxPage;
    }

    /** A book card of any listing. */
    private BookPOJO card(Element book) {
        BookPOJO bookPOJO = new BookPOJO();
        Element img = book.selectFirst("img");
        if (img != null) {
            bookPOJO.setPhoto(img.attr("src"));
        }
        Element author = book.selectFirst(AUTHOR);
        if (author != null) {
            bookPOJO.setAutor(author.text());
            bookPOJO.setUrlAutor(site.server + author.attr("href") + "?page=");
        }
        Element reader = book.selectFirst("a[rel=performer]");
        if (reader != null) {
            bookPOJO.setArtist(reader.text());
            bookPOJO.setUrlArtist(site.server + reader.attr("href") + "?page=");
        }
        Element time = book.selectFirst(".content-abook-info .a-info-item:has(.fa-clock-o)");
        if (time != null) {
            bookPOJO.setTime(time.text().trim());
        }
        Element series = book.selectFirst("a[rel=series]");
        if (series != null) {
            bookPOJO.setSeries(series.text());
            bookPOJO.setUrlSeries(site.server + series.attr("href") + "?page=");
        }
        Element a = book.selectFirst(".abook-title > a");
        if (a != null) {
            bookPOJO.setUrl(site.server + a.attr("href"));
            String[] named = author == null ? split(a.text()) : new String[]{strip(a.text(), author.text()), ""};
            bookPOJO.setName(named[0]);
            bookPOJO.setAutor(named[1]);
        }
        Element aGenre = book.selectFirst(".abook-genre a");
        if (aGenre != null) {
            bookPOJO.setUrlGenre(site.server + aGenre.attr("href") + "?page=");
            bookPOJO.setGenre(aGenre.text());
        }
        Element about = book.selectFirst(".abook-content");
        if (about != null) {
            bookPOJO.setDesc(about.text());
        }
        return bookPOJO;
    }

    /** The books of a series page, each with its number in the series. */
    private ArrayList<SeriesPOJO> seriesBooks(Document doc) {
        Element list = doc.selectFirst(".b-posts");
        if (list == null) {
            return new ArrayList<>();
        }
        return each(list.getElementsByClass("abook-item"), item -> {
            BookPOJO book = card(item);
            if (book.isNull()) {
                return null;
            }
            SeriesPOJO entry = new SeriesPOJO();
            entry.setUrl(book.getUrl());
            entry.setName(book.getName());
            if (book.getPhoto() != null) {
                entry.setPhoto(book.getPhoto());
            }
            Element series = item.selectFirst("a[rel=series]");
            Matcher number = NUMBER.matcher(series == null || series.parent() == null ? "" : series.parent().ownText());
            if (number.find()) {
                entry.setNumber(number.group(1));
            }
            return entry;
        });
    }

    /** A genre or people row; null for a genre row linking to another site. */
    private GenrePOJO row(Element item, boolean ownOnly) {
        GenrePOJO genrePOJO = new GenrePOJO();
        Element link = item.selectFirst(site.rowLink);
        if (link != null) {
            if (ownOnly && !ownPath(link.attr("href"))) {
                return null; // a row linking to another site
            }
            genrePOJO.setUrl(site.server + link.attr("href") + "?page=");
            if (!link.text().isEmpty()) {
                genrePOJO.setName(link.text());
            }
        }
        Element count = item.selectFirst(site.rowCount);
        if (count != null) {
            genrePOJO.setDescription(count(count.text()));
        }
        return genrePOJO;
    }

    /** The items of the search's books block ("Поиск в книгах", "Поиск в аудиокнигах"). */
    private static Elements booksBlock(Document doc) {
        for (Element block : doc.getElementsByClass("b-statictop-search")) {
            Element items = block.selectFirst(".b-statictop__items");
            if (text(block, ".b-statictop__title").contains("книгах") && items != null) {
                return items.children();
            }
        }
        return new Elements();
    }

    /**
     * {title, author, reader} of a search hit: before its {@code .add_info} "(author) (reader)", or (a hit without it)
     * the title split by the site's byline.
     */
    private String[] hit(Element a) {
        String full = a.text();
        Element info = a.selectFirst(".add_info");
        if (info == null && site.byline != Byline.NONE) {
            String[] named = split(full.trim());
            return new String[]{named[0], named[1], ""};
        }
        int cut = full.indexOf(" (");
        String title = cut >= 0 ? full.substring(0, cut) : full;
        String infoText = info == null ? "" : info.text();
        String author = firstParenthesis(infoText);
        String reader = lastParenthesis(infoText);
        return new String[]{title, author, author.equals(reader) ? "" : reader};
    }

    /** {title, author} of a plain-text title by the site's byline; the author is "" when the title names none. */
    String[] split(String text) {
        int at;
        switch (site.byline) {
            case AUTHOR_FIRST:
                at = dash(text, false);
                return at < 0 ? new String[]{text, ""}
                        : new String[]{text.substring(at + 3).trim(), text.substring(0, at).trim()};
            case AUTHOR_LAST:
                at = dash(text, true);
                return at < 0 ? new String[]{text, ""}
                        : new String[]{text.substring(0, at).trim(), text.substring(at + 3).trim()};
            default:
                return new String[]{text, ""};
        }
    }

    /**
     * {@code name} without its author: "Author - " anywhere; else, by the byline, the first (or last) dash-separated
     * part when it names the author in other words ("Звездная Елена - ...", "... - Елена Тодорова").
     */
    String strip(String name, String author) {
        if (author.isEmpty()) {
            return name;
        }
        String stripped = name.replace(author + " - ", "");
        if (!stripped.equals(name) || site.byline == Byline.NONE) {
            return stripped;
        }
        boolean last = site.byline == Byline.AUTHOR_LAST;
        int at = dash(name, last);
        if (at < 0) {
            return name;
        }
        String byline = last ? name.substring(at + 3) : name.substring(0, at);
        String title = (last ? name.substring(0, at) : name.substring(at + 3)).trim();
        return !title.isEmpty() && sharesWord(byline, author) ? title : name;
    }

    /** True when {@code a} and {@code b} have a word of three letters or more in common (case and ё aside). */
    static boolean sharesWord(String a, String b) {
        List<String> words = words(b);
        for (String word : words(a)) {
            if (words.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        for (String word : text.toLowerCase(Locale.ROOT).replace('ё', 'е').split("[^\\p{L}]+")) {
            if (word.length() >= 3) {
                words.add(word);
            }
        }
        return words;
    }

    /** The first (or last) " - " or " – " of {@code text}; -1 when there is none. */
    private static int dash(String text, boolean last) {
        int hyphen = last ? text.lastIndexOf(" - ") : text.indexOf(" - ");
        int en = last ? text.lastIndexOf(" – ") : text.indexOf(" – ");
        if (hyphen < 0 || en < 0) {
            return Math.max(hyphen, en);
        }
        return last ? Math.max(hyphen, en) : Math.min(hyphen, en);
    }

    /** "120книг", "– 30678 аудиокниг" → "120 книг", "30678 аудиокниг". */
    static String count(String text) {
        String t = text.trim();
        Matcher digit = Pattern.compile("\\d").matcher(t);
        if (digit.find()) {
            t = t.substring(digit.start());
        }
        return t.replaceAll("(\\d)(?=[^\\d\\s])", "$1 ").replaceAll("\\s+", " ");
    }

    /** A value without its "Label:" ("Время звучания: 07:39:05" → "07:39:05"); a time's own colons stay. */
    static String unlabelled(String text) {
        return text.trim().replaceFirst("^\\D*:\\s*", "");
    }

    private static String firstParenthesis(String s) {
        int open = s.indexOf('(');
        int close = open < 0 ? -1 : s.indexOf(')', open);
        return close > open ? s.substring(open + 1, close).trim() : "";
    }

    private static String lastParenthesis(String s) {
        int open = s.lastIndexOf('(');
        int close = s.lastIndexOf(')');
        return open >= 0 && close > open ? s.substring(open + 1, close).trim() : "";
    }

    /** Untrimmed text of the first match of {@code query} under {@code scope}; "" when absent. */
    private static String commentText(@Nullable Element scope, String query) {
        Element element = scope == null ? null : scope.selectFirst(query);
        return element == null ? "" : element.text();
    }

    private static String commentUrl(@Nullable Element scope, String query, String attribute) {
        Element element = scope == null ? null : scope.selectFirst(query);
        return element == null ? "" : element.absUrl(attribute);
    }

    /** The elements of a book page the description, the card and the chapters' name are read from. */
    private final class BookPage {
        final Element cover;
        final Element about;
        final Element author;
        final Element reader;
        final Element genre;
        final Element series;
        final Element time;
        final Element views;
        final Element title;

        BookPage(Document doc) {
            Elements info = doc.select(site.info);
            cover = doc.selectFirst(site.cover);
            about = doc.selectFirst(site.about);
            author = info.select(AUTHOR).first();
            reader = info.select(READER).first();
            genre = info.select(GENRE).first();
            series = info.select(SERIES).first();
            time = doc.selectFirst(site.time);
            views = doc.selectFirst(site.views);
            title = doc.selectFirst(site.title);
        }

        /** The title without its author. */
        String name() {
            return strip(text(title), author == null ? "" : author.text());
        }
    }
}
