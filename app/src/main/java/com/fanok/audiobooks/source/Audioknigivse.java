package com.fanok.audiobooks.source;

import static com.fanok.audiobooks.source.Html.afterLabel;
import static com.fanok.audiobooks.source.Html.href;
import static com.fanok.audiobooks.source.Html.imgSrc;
import static com.fanok.audiobooks.source.Html.labelled;
import static com.fanok.audiobooks.source.Html.text;

import androidx.annotation.Nullable;

import com.fanok.audiobooks.BookRemovedException;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * audioknigivse.ru ("Аудиокниги всем"): DataLife Engine, template "audioknigi-vsem", beget hosting (probed live,
 * 2026-11-21). Plain GETs, no wall, no Referer or cookies needed.
 * <ul>
 * <li>index: {@code /}, then {@code /page/N/} (last ~1373); 10 cards a page, pager {@code .navigation}. A card's
 * title is "Name / Author (N)"; its rows "Писатель", "Озвучивает", "Категория" (one or two genres), "Цикл",
 * "Книга №" and "Длительность"; a 5-star rating with votes.</li>
 * <li>sorts: the DLE sort form POSTed to the listing page itself (no page shows the form, the engine takes it
 * anyway): {@code dlenewssortby} date, rating, news_read (views) and title change the order on the index
 * ({@code dle_sort_main}) and in a genre ({@code dle_sort_cat_<id>}); comm_num does not (the site has no comments).
 * The choice sticks to the PHPSESSID session, so {@link #fetch} sends index and genre pages with their sort. No
 * periods.</li>
 * <li>genres: the sidebar menu of any page ({@link #GENRES} is the index); a genre is {@code /<slug>/}. No page
 * shows a category's id: {@link #CATEGORY_IDS} holds them, read off the advanced search form's category list.
 * Taken-down books are moved to the hidden category {@code /audiokniga_litres/} ("Заблокировано").</li>
 * <li>search: {@code /index.php?do=search&subaction=search&story=Q&search_start=N}, GET, 10 hits a page; a hit
 * ({@code a.sres-wrap}) has the title, cover and genre names only.</li>
 * <li>author, reader, series: {@code /xfsearch/avtor|zvuk|seria/<name>/} with the index's page scheme; no index of
 * all authors or readers (the app's built-in lists).</li>
 * <li>book {@code /<genre>/<id>-<slug>.html}: chapters as the DLE audio BBCode ({@code <!--dle_audio_begin:url|title,
 * ...-->}, no durations), drawn by one of two templates: recent books (2026) a {@code .dleplyrplayer} with one
 * {@code <audio title><source src>} per chapter, older ones (2020-2023 at least) a {@code .dleaudioplayer} list of
 * {@code <li data-title data-url>} and no {@code <audio>}. MP3s on archive.org or chitalkin2.ru (shared with
 * slushkinvsem.ru), no headers needed; some old books point at unsigned {@code rN.audioknigi.xyz/<hash>/audio/NNN.mp3}
 * links that answer 403 with or without a Referer, so those parse but do not play. A taken-down book shows "Аудиокнига удалена по требованию правообладателя"
 * instead of the player. "Похожие аудиокниги" below. No comments.</li>
 * </ul>
 */
public final class Audioknigivse implements SourceParser {

    public static final String SERVER = "https://audioknigivse.ru";
    public static final String INDEX = SERVER + "/";
    /** Any page carries the genre menu; the index is the one the catalog opens anyway. */
    public static final String GENRES = SERVER + "/";
    public static final String AUTHORS = SERVER + PeopleIndex.AUTHORS;
    public static final String READERS = SERVER + PeopleIndex.READERS;

    /** The sorts that change the order, on the index and in a genre (comm_num does not: no comments). */
    static final List<Sort.Entry> MENU = Sort.menu(Sort.NEW, Sort.POPULAR, Sort.RATING, Sort.TITLE);

    /** Bound of the pages read for a book's series tab (10 books each). */
    static final int MAX_SERIES_PAGES = 5;

    /** DLE category id by genre slug: the advanced search form's category list, matched by name. */
    static final Map<String, String> CATEGORY_IDS = new HashMap<>();

    static {
        String[] slugs = {"audioknigi_litrpg", "audioknigi_vsem_fantastika", "audioknigi_-vsem_popadanci",
                "audioknigi_vsem_boeviki", "audioknigi_vsem_alternativa", "audioknigi_vsem_horror",
                "audioknigi_vsem_detektiv", "audioknigi_vsem_etnogenez", "audioknigi_vsem_stalker",
                "audioknigi_vsem_metro", "audioknigi_vsem_stix", "audioknigi_vsem_lubov_fentezi",
                "audioknigi_vsem_fentezi", "audioknigi_vsem_prikluchenia", "audioknigi_vsem_postapokalipsis",
                "audioknigi_vsem_roman_proza", "audioknigi_vsem_rus_klassik", "proza_audio", "audiokniga_litres"};
        String[] ids = {"46", "47", "48", "49", "50", "51", "52", "54", "55", "56", "57", "58", "59", "65", "68", "70",
                "71", "73", "75"};
        for (int i = 0; i < slugs.length; i++) {
            CATEGORY_IDS.put(slugs[i], ids[i]);
        }
    }

    private static final Pattern GENRE_PATH = Pattern.compile("^/([a-z0-9_-]+)/(page/\\d+/?)?$");
    private static final Pattern INDEX_PATH = Pattern.compile("^/(page/(\\d+)/?)?$");
    private static final Pattern PAGE = Pattern.compile("/page/(\\d+)/?$");
    /** "Name / Author (N)" (a few "Name/ Author"): the name, then the author with the number in the series. */
    private static final Pattern TITLE = Pattern.compile("^(.+?)\\s*/\\s+(.+?)(?:\\s*\\([^()]*\\))?$");
    private static final String ROWS = ".short-info-item";

    // ---------------------------------------------------------------- SourceParser

    /** Index and genre pages always go out with their sort ("new" too): the session keeps the last one. */
    @Override
    public Document fetch(String url) throws IOException {
        if (url.contains(Dle.SORT_PARAM + "=")) {
            return Dle.fetch(url);
        }
        if (isIndex(url)) {
            return Dle.fetch(Dle.pageUrl(sortedUrl(INDEX, Dle.Order.DATE), pageOf(url)));
        }
        String genre = genreOf(url);
        return genre != null ? Dle.fetch(Dle.pageUrl(sortedUrl(genre, Dle.Order.DATE), pageOf(url)))
                : SiteHttp.get(url);
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
        return isIndex(url) || url.contains("set_new_sort=dle_sort_main") || genreOf(url) != null ? MENU
                : Collections.<Sort.Entry>emptyList();
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
        String url = sortedUrl(INDEX, order);
        return url.equals(listingUrl) ? listingUrl : url;
    }

    /** The genre root ({@code https://audioknigivse.ru/<slug>/}) of a known genre, plain or sorted; null elsewhere. */
    @Override
    public String genreOf(String url) {
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null || !parsed.host().equals(HttpUrl.get(SERVER).host())) {
            return null;
        }
        Matcher m = GENRE_PATH.matcher(parsed.encodedPath());
        return m.matches() && CATEGORY_IDS.containsKey(m.group(1)) ? INDEX + m.group(1) + "/" : null;
    }

    /** The site-wide sort {@code siteWide} within {@code genre}. */
    @Override
    public String inGenre(String siteWide, String genre) {
        HttpUrl parsed = HttpUrl.parse(siteWide);
        Dle.Order order = Dle.Order.ofField(parsed == null ? null : parsed.queryParameter(Dle.SORT_PARAM));
        return order != null && genreOf(genre) != null ? sortedUrl(genre, order) : siteWide;
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
        return parseSearch(page, pageNo);
    }

    @Override
    public DescriptionPOJO description(Document page, String bookUrl) {
        return parseDescription(page);
    }

    @Override
    public BookPOJO book(Document page, String bookUrl) {
        DescriptionPOJO d = parseDescription(page);
        BookPOJO book = BookPOJO.of(d, bookUrl);
        setStars(book, page.selectFirst("article"));
        return book;
    }

    @Override
    public ArrayList<BookPOJO> recommendations(Document page, String bookUrl) {
        return parseRecommendations(page);
    }

    @Override
    public ArrayList<AudioPOJO> tracks(Document page, String base) {
        return parseTracks(page, base);
    }

    /** The series listing read page by page, by the number in the series. */
    @Override
    public ArrayList<SeriesPOJO> series(Document page, String bookUrl) throws IOException {
        ArrayList<SeriesPOJO> result = new ArrayList<>();
        String series = parseDescription(page).getSeriesUrl();
        for (int no = 1; series != null && !series.isEmpty() && no <= MAX_SERIES_PAGES; no++) {
            Document doc = fetch(pageUrl(series, no));
            result.addAll(parseSeries(doc));
            if (!parseListing(doc, no).hasMore) {
                break;
            }
        }
        Collections.sort(result, (x, y) -> Integer.compare(number(x.getNumber()), number(y.getNumber())));
        return result;
    }

    // ---------------------------------------------------------------- URLs

    /** Page 1 of {@code listing} sorted by {@code order}: the DLE sort form, POSTed by {@link Dle#fetch}. */
    static String sortedUrl(String listing, Dle.Order order) {
        String root = Dle.pageUrl(listing.contains("?") ? listing.substring(0, listing.indexOf('?')) : listing, 1);
        HttpUrl parsed = HttpUrl.parse(root);
        Matcher m = GENRE_PATH.matcher(parsed == null ? "" : parsed.encodedPath());
        String id = m.matches() ? CATEGORY_IDS.get(m.group(1)) : null;
        return Dle.sorted(root, order, id == null ? "main" : "cat_" + id);
    }

    /** The plain index: {@code /} or {@code /page/N/}. */
    private static boolean isIndex(String url) {
        HttpUrl parsed = HttpUrl.parse(url);
        return parsed != null && parsed.query() == null && parsed.host().equals(HttpUrl.get(SERVER).host())
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
        Elements raw = doc.select("div.short-item:has(a.short-link)");
        return ProviderPage.of(Html.cards(raw, Audioknigivse::card), lastPage(doc) > page, raw.size());
    }

    @Nullable
    private static BookPOJO card(Element card) {
        Element title = card.selectFirst("a.short-link");
        if (title == null) {
            return null;
        }
        BookPOJO book = new BookPOJO();
        book.setUrl(title.absUrl("href"));
        book.setName(name(title.text()));
        book.setPhoto(imgSrc(card.selectFirst(".short-img img")));
        Element author = labelled(card, ROWS, "Писатель");
        book.setAutor(afterLabel(author));
        book.setUrlAutor(href(author, "a"));
        Element reader = labelled(card, ROWS, "Озвучивает");
        book.setArtist(afterLabel(reader));
        book.setUrlArtist(href(reader, "a"));
        Element genre = labelled(card, ROWS, "Категория");
        book.setGenre(afterLabel(genre));
        book.setUrlGenre(href(genre, "a"));
        Element series = labelled(card, ROWS, "Цикл");
        book.setSeries(text(series, "a"));
        book.setUrlSeries(href(series, "a"));
        book.setTime(afterLabel(labelled(card, ROWS, "Длительность")));
        setStars(book, card);
        return book;
    }

    /** A search page: title, cover and genre names; the author is the title's " / Author" part. */
    public static ProviderPage<BookPOJO> parseSearch(Document doc, int page) {
        Elements raw = doc.select("a.sres-wrap");
        return ProviderPage.of(Html.cards(raw, hit -> {
            BookPOJO book = new BookPOJO();
            book.setUrl(hit.absUrl("href"));
            String title = text(hit, "h2");
            book.setName(name(title));
            Matcher m = TITLE.matcher(title);
            if (m.matches()) {
                book.setAutor(m.group(2));
            }
            book.setPhoto(imgSrc(hit.selectFirst(".sres-img img")));
            book.setGenre(text(hit, ".sres-date"));
            return book;
        }), lastPage(doc) > page, raw.size());
    }

    /** The highest page number in the pager; 0 without one. */
    private static int lastPage(Document doc) {
        return Html.lastPage(doc.select(".navigation a, .navigation span"));
    }

    // ---------------------------------------------------------------- genres

    /** The sidebar menu; its contact page is left out. */
    public static ProviderPage<GenrePOJO> parseGenres(Document doc) {
        return ProviderPage.of(Html.rows(doc.select(".nav-box-content a[href]"), a -> {
            HttpUrl url = HttpUrl.parse(a.absUrl("href"));
            Matcher m = GENRE_PATH.matcher(url == null ? "" : url.encodedPath().replaceFirst("/?$", "/"));
            return m.matches() ? new GenrePOJO(a.text().trim(), INDEX + m.group(1) + "/") : null;
        }), false);
    }

    // ---------------------------------------------------------------- book page

    public static DescriptionPOJO parseDescription(Document doc) {
        DescriptionPOJO description = new DescriptionPOJO();
        Element article = doc.selectFirst("article");
        if (article == null) {
            return description;
        }
        description.setTitle(name(text(article, ".short-head h1").replaceFirst("^Аудиокнига\\s+", "")));
        description.setPoster(imgSrc(article.selectFirst(".f-mov-img img")));
        Element author = labelled(article, ROWS, "Писатель");
        description.setAutor(afterLabel(author));
        description.setAutorUrl(href(author, "a"));
        Element reader = labelled(article, ROWS, "Озвучивает");
        description.setArtist(afterLabel(reader));
        description.setArtistUrl(href(reader, "a"));
        Element genre = labelled(article, ROWS, "Категория");
        description.setGenre(afterLabel(genre));
        description.setGenreUrl(href(genre, "a"));
        Element series = labelled(article, ROWS, "Цикл");
        description.setSeries(text(series, "a"));
        description.setSeriesUrl(href(series, "a"));
        description.setTime(afterLabel(labelled(article, ROWS, "Длительность")));
        String percent = text(article, "li.current-rating");
        String votes = text(article, "[data-vote-num-id]");
        if (percent.matches("\\d+") && !percent.equals("0") && votes.matches("\\d+") && !votes.equals("0")) {
            description.setReiting(BookPOJO.formatRating(Integer.parseInt(percent) / 20f, votes));
        }
        Element text = article.selectFirst(".mov-desc");
        if (text != null) {
            Element own = text.clone();
            own.select("center, script, [id^=yandex_rtb], div:has(audio), .dleaudioplayer, .quote").remove();
            description.setDescription(Html.paragraphs(own));
        }
        return description;
    }

    /**
     * One chapter per item of either DLE audio player: {@code <audio title><source src>} of the newer one,
     * {@code <li data-title data-url>} of the older; a taken-down book is a {@link BookRemovedException}.
     */
    public static ArrayList<AudioPOJO> parseTracks(Document doc, String bookUrl) {
        String title = parseDescription(doc).getTitle();
        Players.Chapters chapters = new Players.Chapters(title, bookUrl);
        for (Element item : doc.select(".dleplyrplayer audio, .dleaudioplayer li[data-url]")) {
            Element source = item.selectFirst("source[src]");
            String url = item.hasAttr("data-url") ? item.attr("data-url") : source == null ? "" : source.attr("src");
            String name = item.hasAttr("data-title") ? item.attr("data-title") : item.attr("title");
            chapters.add(chapters.chapter(name.trim(), url, 0));
        }
        if (chapters.isEmpty() && text(doc, ".mov-desc .quote").contains("удалена по требованию")) {
            throw new BookRemovedException(bookUrl);
        }
        return chapters.done(bookUrl);
    }

    /** "Похожие аудиокниги". */
    public static ArrayList<BookPOJO> parseRecommendations(Document doc) {
        return Html.cards(doc.select(".rel-cont .slide"), slide -> {
            BookPOJO book = new BookPOJO();
            book.setUrl(href(slide, "a.carou-inner"));
            book.setName(name(text(slide, ".rel-title")));
            book.setPhoto(imgSrc(slide.selectFirst("img")));
            return book;
        });
    }

    /** The books of a series listing with their number ("Книга №"). */
    public static ArrayList<SeriesPOJO> parseSeries(Document doc) {
        return Html.each(doc.select("div.short-item:has(a.short-link)"), card -> {
            SeriesPOJO item = Html.seriesItem(card.selectFirst("a.short-link"), card.selectFirst(".short-img img"));
            if (item != null) {
                item.setName(name(item.getName()));
                String number = text(labelled(card, ROWS, "Книга №")).replace("Книга №", "").trim();
                if (!number.isEmpty()) {
                    item.setNumber(number);
                }
            }
            return item;
        });
    }

    // ---------------------------------------------------------------- helpers

    /** The name of a "Name / Author (N)" title; the whole title without the " / ". */
    static String name(String title) {
        Matcher m = TITLE.matcher(title.trim());
        return m.matches() ? m.group(1) : title.trim();
    }

    /** The 5-star rating ("4.0") of a card; one nobody voted for keeps the "0" the list hides. */
    private static void setStars(BookPOJO book, @Nullable Element scope) {
        String percent = text(scope, "li.current-rating");
        if (percent.matches("\\d+") && !percent.equals("0")) {
            book.setReting(String.format(Locale.ROOT, "%.1f", Integer.parseInt(percent) / 20f));
        }
    }

    private static int number(@Nullable String s) {
        try {
            return s == null ? Integer.MAX_VALUE : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }
}
