package com.fanok.audiobooks.source;

import static com.fanok.audiobooks.source.Html.afterLabel;
import static com.fanok.audiobooks.source.Html.href;
import static com.fanok.audiobooks.source.Html.labelled;
import static com.fanok.audiobooks.source.Html.text;

import androidx.annotation.Nullable;

import com.fanok.audiobooks.BookRemovedException;
import com.fanok.audiobooks.Url;
import com.fanok.audiobooks.net.SiteHttp;
import com.fanok.audiobooks.pojo.AudioPOJO;
import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.pojo.DescriptionPOJO;
import com.fanok.audiobooks.pojo.GenrePOJO;
import com.fanok.audiobooks.pojo.SeriesPOJO;
import com.fanok.audiobooks.search.PeopleIndex;
import com.fanok.audiobooks.search.ProviderPage;
import com.fanok.audiobooks.search.SearchUrls;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * audioknigi.cc: DataLife Engine, template "tema-knig" (probed live, 2026-11-21); no challenge, no Referer or cookie
 * needed. A mirror of audiokniga-online.ru: the same titles under its own news ids, audio from the same
 * {@code s1.audiokniga-online.net}, plus about a thousand older entries of its own.
 * <ul>
 * <li>index {@code /}, then {@code /page/N/}; a genre {@code /<slug>/} or {@code /<parent>/<slug>/} with the same
 * scheme. 10 cards a page ({@code .item--announce}: author, reader, genres, H:MM:SS or, on old short stories, MM:SS),
 * pager {@code #pagination}.</li>
 * <li>sorts: no sort form on the pages, yet the engine takes the DLE sort POSTed to the listing page itself:
 * {@code dlenewssortby} date, rating, news_read (views) and title work, comm_num is ignored (the plain date order
 * comes back; the site has no comments either). Keys {@code dle_sort_main}, in a genre {@code dle_sort_cat_<id>}, the
 * id being the genre link's {@code id} in the side menu of every page ({@link #learnCategories}). The choice sticks to
 * the PHPSESSID session (a later plain GET of {@code /} comes back sorted; xfsearch pages do not), so {@link #fetch}
 * sends index and genre pages with their sort. No periods, no fixed lists.</li>
 * <li>genres: the side menu of {@link #GENRES}; its "Чтецы" and "Авторы" entries are categories of a few featured
 * people (28 books in all), not people indexes, and are left out.</li>
 * <li>search: {@code /index.php?do=search&subaction=search&story=Q&search_start=N}, GET, 10 cards; the "найдено" count
 * stops at 50 while the pages go on.</li>
 * <li>author, reader, series: {@code /xfsearch/avtor|ispolnitel|serie/<name>/}, the index's page scheme, newest
 * first; a card's "Книга #N" label is its number in the series.</li>
 * <li>book {@code /<genre>/<id>-<slug>.html}: rows "Автор:", "Чтец:", "Цикл:", the time next to the genres. Playerjs
 * {@code file} is {@code https://s1.audiokniga-online.net/audiobooks/<slug>/playlist.txt.php}, a 302 to
 * {@code /f/audiobooksNNN/...}, a JSON {@code [{title, file}]} of MP3s (206 with Range, no Referer needed). Old
 * entries carry an inline list of {@code /uploads/files/*.m4a} on this host, some of which answer 404. Books sold on
 * LitRes keep the Playerjs, whose playlist answers 404, beside a LitRes trial widget ({@code iframe[src*=litres.ru]})
 * and, on some, the right-holder notice "доступны только ознакомительные фрагменты": removed.</li>
 * </ul>
 */
public final class AudioknigiCc implements SourceParser {

    public static final String SERVER = "https://audioknigi.cc";
    public static final String INDEX = SERVER + "/";
    public static final String GENRES = INDEX;
    /** /avtory/ and /ispolniteli/ are book rubrics, not indexes: the built-in lists (tools/people, AUDIOKNIGI_CC). */
    public static final String AUTHORS = SERVER + PeopleIndex.AUTHORS;
    public static final String READERS = SERVER + PeopleIndex.READERS;

    static final int SEARCH_PAGE_SIZE = 10;
    /** The search reports at most this many hits, however many pages follow. */
    static final int SEARCH_COUNT_CAP = 50;
    private static final int MAX_SERIES_PAGES = 5;

    /** The sorts the server performs (comm_num is ignored). */
    static final List<Sort.Entry> MENU = Sort.menu(Sort.NEW, Sort.POPULAR, Sort.RATING, Sort.TITLE);

    /** Genre root → DLE category id, read off the side menu of any page. */
    private static final Map<String, String> CATEGORY_IDS = new ConcurrentHashMap<>();

    private static final Pattern GENRE_PATH =
            Pattern.compile("^/(?!xfsearch/|page/|index\\.php)((?:[a-z0-9-]+/)+?)(page/\\d+/?)?$");
    private static final Pattern INDEX_PATH = Pattern.compile("^/(page/(\\d+)/?)?$");
    private static final Pattern PAGE = Pattern.compile("/page/(\\d+)/?$");
    private static final Pattern SEARCH_FOUND =
            Pattern.compile("найдено (\\d+) ответов \\(Результаты запроса \\d+ - (\\d+)\\)");
    private static final Pattern SERIES_NO = Pattern.compile("#\\s*(\\d+)");

    // ---------------------------------------------------------------- SourceParser

    /**
     * Sorted URLs are POSTed. An index page always goes out with its sort ("new" too); so does a genre page once its
     * id is known (any page fetched before tells it), until then the session cannot have sorted it.
     */
    @Override
    public Document fetch(String url) throws IOException {
        if (url.contains(Dle.SORT_PARAM + "=")) {
            return learnCategories(Dle.fetch(url));
        }
        String genre = genreOf(url);
        String id = genre == null ? null : CATEGORY_IDS.get(genre);
        if (id != null) {
            return Dle.fetch(Dle.pageUrl(sortedUrl(genre, Dle.Order.DATE, id), pageOf(url)));
        }
        if (genre == null && isIndex(url)) {
            return learnCategories(Dle.fetch(Dle.pageUrl(sortedUrl(INDEX, Dle.Order.DATE, null), pageOf(url))));
        }
        return learnCategories(SiteHttp.get(url));
    }

    /** Remembers the category ids of the side menu of {@code page}; returns the page. */
    static Document learnCategories(Document page) {
        for (Element a : page.select(".rail--nav a[id][href]")) {
            String url = a.absUrl("href");
            if (!url.isEmpty() && a.id().matches("\\d+")) {
                CATEGORY_IDS.put(url, a.id());
            }
        }
        return page;
    }

    @Override
    public ProviderPage<BookPOJO> listing(Document page, String url, int pageNo) {
        return parseListing(page, pageNo);
    }

    @Override
    public String pageUrl(String url, int page) {
        return Dle.pageUrl(url, page);
    }

    /** The index and genres sort; author, reader, series and search pages do not. */
    @Override
    public List<Sort.Entry> sorts(String url) {
        return isIndex(url) || url.contains("set_new_sort=dle_sort_main") || genreOf(url) != null
                ? MENU : Collections.<Sort.Entry>emptyList();
    }

    @Override
    public String sortUrl(Sort sort, String listingUrl) {
        Dle.Order order = Dle.Order.of(sort);
        if (order == null || order == Dle.Order.COMMENTS) {
            return null;
        }
        if (order == Dle.Order.DATE && isIndex(listingUrl)) {
            return listingUrl;
        }
        String url = sortedUrl(INDEX, order, null);
        return url.equals(listingUrl) ? listingUrl : url;
    }

    /** The genre root ({@code https://audioknigi.cc/<slug>/}) of a genre listing, plain or sorted; null elsewhere. */
    @Override
    public String genreOf(String url) {
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null || !Url.isHost(url, SERVER)) {
            return null;
        }
        Matcher m = GENRE_PATH.matcher(parsed.encodedPath());
        return m.matches() ? INDEX + m.group(1) : null;
    }

    /** The site-wide sort {@code siteWide} within {@code genre}; site-wide while the genre's id is unknown. */
    @Override
    public String inGenre(String siteWide, String genre) {
        String id = CATEGORY_IDS.get(genre);
        HttpUrl parsed = HttpUrl.parse(siteWide);
        Dle.Order order = Dle.Order.ofField(parsed == null ? null : parsed.queryParameter(Dle.SORT_PARAM));
        return id != null && order != null ? sortedUrl(genre, order, id) : siteWide;
    }

    @Override
    public ProviderPage<GenrePOJO> genres(Document page, String url, int pageNo) {
        return parseGenres(page);
    }

    @Override
    public String searchUrl(SearchUrls.Category category, String query, int page) {
        return category == SearchUrls.Category.BOOKS ? Dle.searchUrl(SERVER, query, page) : null;
    }

    @Override
    public ProviderPage<BookPOJO> searchResults(Document page, int pageNo) {
        return parseSearch(page);
    }

    @Override
    public DescriptionPOJO description(Document page, String bookUrl) {
        return parseDescription(page);
    }

    @Override
    public ArrayList<BookPOJO> recommendations(Document page, String bookUrl) {
        return parseRecommendations(page);
    }

    /**
     * The Playerjs playlist (inline on old entries), fetched with the book page as Referer; no player is an error, a
     * LitRes trial widget means the book was taken down (its playlist answers 404).
     */
    @Override
    public ArrayList<AudioPOJO> tracks(Document page, String base) throws IOException {
        if (page.selectFirst("iframe[src*=litres.ru]") != null) {
            throw new BookRemovedException(base);
        }
        return AudioknigaOnline.encoded(Players.loadPlayerjs(page, base, text(page, ".entry__header h1")));
    }

    /** The books of the "Цикл" link, all its pages, in series order. */
    @Override
    public ArrayList<SeriesPOJO> series(Document page, String bookUrl) throws IOException {
        ArrayList<SeriesPOJO> result = new ArrayList<>();
        String series = parseDescription(page).getSeriesUrl();
        for (int no = 1; !series.isEmpty() && no <= MAX_SERIES_PAGES; no++) {
            Document doc = fetch(pageUrl(series, no));
            result.addAll(parseSeries(doc));
            if (lastPage(doc) <= no) {
                break;
            }
        }
        return inSeriesOrder(result);
    }

    // ---------------------------------------------------------------- URLs

    /** Page 1 of {@code listingUrl} sorted by {@code sort}; {@code categoryId} is the genre's id, null for the index. */
    static String sortedUrl(String listingUrl, Dle.Order sort, @Nullable String categoryId) {
        int query = listingUrl.indexOf('?');
        return Dle.sorted(Dle.pageUrl(query == -1 ? listingUrl : listingUrl.substring(0, query), 1), sort,
                categoryId == null ? "main" : "cat_" + categoryId);
    }

    /** The plain index: {@code /} or {@code /page/N/}. */
    private static boolean isIndex(String url) {
        HttpUrl parsed = HttpUrl.parse(url);
        return parsed != null && parsed.query() == null && Url.isHost(url, SERVER)
                && INDEX_PATH.matcher(parsed.encodedPath()).matches();
    }

    private static int pageOf(String url) {
        HttpUrl parsed = HttpUrl.parse(url);
        Matcher m = PAGE.matcher(parsed == null ? "" : parsed.encodedPath());
        return m.find() ? Integer.parseInt(m.group(1)) : 1;
    }

    // ---------------------------------------------------------------- listings

    /** Index, sorted, genre and xfsearch pages. */
    public static ProviderPage<BookPOJO> parseListing(Document doc, int page) {
        Elements raw = doc.select("#dle-content > .item--announce");
        return ProviderPage.of(Html.cards(raw, AudioknigiCc::card), lastPage(doc) > page, raw.size());
    }

    /** A search page: its pager stops at the capped count, so the count and a full page tell. */
    public static ProviderPage<BookPOJO> parseSearch(Document doc) {
        Elements raw = doc.select("#dle-content > .item--announce");
        boolean hasMore = raw.size() >= SEARCH_PAGE_SIZE;
        Matcher m = SEARCH_FOUND.matcher(doc.text());
        if (hasMore && m.find()) {
            int found = Integer.parseInt(m.group(1));
            hasMore = found >= SEARCH_COUNT_CAP || Integer.parseInt(m.group(2)) < found;
        }
        return ProviderPage.of(Html.cards(raw, AudioknigiCc::card), hasMore, raw.size());
    }

    @Nullable
    private static BookPOJO card(Element card) {
        Element title = card.selectFirst(".item__title a");
        if (title == null) {
            return null;
        }
        BookPOJO book = new BookPOJO();
        book.setUrl(title.absUrl("href"));
        book.setName(title.text().trim());
        book.setPhoto(Html.imgSrc(card.selectFirst(".item__img img")));
        Element author = labelled(card, ".item__meta-item", "Автор");
        book.setAutor(afterLabel(author));
        book.setUrlAutor(href(author, "a"));
        Element reader = labelled(card, ".item__meta-item", "Исполнитель");
        book.setArtist(afterLabel(reader));
        book.setUrlArtist(href(reader, "a"));
        Element genre = genreLink(card);
        if (genre != null) {
            book.setGenre(genre.text().trim());
            book.setUrlGenre(genre.absUrl("href"));
        }
        book.setTime(text(card, ".item__size"));
        book.setDesc(text(card, ".item__text"));
        return book;
    }

    /** The first genre link of a card or book header; the featured-people categories are no genre. */
    @Nullable
    private static Element genreLink(Element scope) {
        for (Element a : scope.select(".item__category a")) {
            if (!isPeopleCategory(a.absUrl("href"))) {
                return a;
            }
        }
        return null;
    }

    private static boolean isPeopleCategory(String url) {
        return url.startsWith(SERVER + "/ispolniteli/") || url.startsWith(SERVER + "/avtory/");
    }

    /** The highest page number in the pager; 0 without one. */
    private static int lastPage(Document doc) {
        return Html.lastPage(doc.select("#pagination .pagination__pages a, #pagination .pagination__pages span"));
    }

    // ---------------------------------------------------------------- genres

    /** The side menu, sub-genres included; the "Чтецы" and "Авторы" categories of featured people are left out. */
    public static ProviderPage<GenrePOJO> parseGenres(Document doc) {
        return ProviderPage.of(Html.rows(doc.select(".rail--nav a[id][href]"), a -> {
            String url = a.absUrl("href");
            return isPeopleCategory(url) ? null : new GenrePOJO(a.text().trim(), url);
        }), false);
    }

    // ---------------------------------------------------------------- book page

    public static DescriptionPOJO parseDescription(Document doc) {
        DescriptionPOJO description = new DescriptionPOJO();
        Element header = doc.selectFirst(".entry__header");
        description.setTitle(text(header, "h1"));
        description.setPoster(Html.imgSrc(header == null ? null : header.selectFirst(".entry__poster img")));
        Element author = labelled(doc, ".entry__list li", "Автор");
        description.setAutor(afterLabel(author));
        description.setAutorUrl(href(author, "a"));
        Element reader = labelled(doc, ".entry__list li", "Чтец");
        description.setArtist(afterLabel(reader));
        description.setArtistUrl(href(reader, "a"));
        Element series = labelled(doc, ".entry__list li", "Цикл");
        description.setSeries(text(series, "a"));
        description.setSeriesUrl(href(series, "a"));
        Element genre = header == null ? null : genreLink(header);
        if (genre != null) {
            description.setGenre(genre.text().trim());
            description.setGenreUrl(genre.absUrl("href"));
        }
        description.setTime(text(header, ".item__size"));
        description.setLike(text(header, "[id^=likes-id-]"));
        description.setDisLike(text(header, "[id^=dislikes-id-]"));
        description.setDescription(Html.paragraphs(doc.selectFirst(".entry__text")));
        return description;
    }

    /** "Похожие аудиокниги". */
    public static ArrayList<BookPOJO> parseRecommendations(Document doc) {
        return Html.cards(doc.select("#owl-related a.showcase-card"), a -> {
            BookPOJO book = new BookPOJO();
            book.setUrl(a.absUrl("href"));
            book.setName(text(a, ".showcase-card__title"));
            book.setPhoto(Html.imgSrc(a.selectFirst("img")));
            return book;
        });
    }

    /** One page of a series listing, as the site orders it (newest first); "Книга #N" is the number. */
    public static ArrayList<SeriesPOJO> parseSeries(Document doc) {
        return Html.each(doc.select("#dle-content > .item--announce"), card -> {
            SeriesPOJO item = Html.seriesItem(card.selectFirst(".item__title a"), card.selectFirst(".item__img img"));
            Matcher m = SERIES_NO.matcher(text(card, ".item__label"));
            if (item != null && m.find()) {
                item.setNumber(m.group(1));
            }
            return item;
        });
    }

    /** First book first; books without a number keep their place after the numbered ones. */
    static ArrayList<SeriesPOJO> inSeriesOrder(ArrayList<SeriesPOJO> books) {
        Collections.sort(books, (a, b) -> Integer.compare(number(a), number(b)));
        return books;
    }

    private static int number(SeriesPOJO item) {
        String n = item.getNumber();
        return n == null || n.isEmpty() ? Integer.MAX_VALUE : Integer.parseInt(n);
    }
}
