package com.fanok.audiobooks.source;

import static com.fanok.audiobooks.source.Html.afterLabel;
import static com.fanok.audiobooks.source.Html.href;
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
import com.fanok.audiobooks.search.ProviderPage;
import com.fanok.audiobooks.search.SearchUrls;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * 4read.org: Ukrainian audiobooks, DataLife Engine, template "4read" behind Cloudflare without a challenge (probed live
 * 2026-11-04).
 * <ul>
 * <li>index {@code /}, then {@code /page/N/}; 24 cards a page, pager {@code #pagination}. Genres
 * {@code /<slug>/}, authors {@code /xfsearch/avtor/<name>/}, readers {@code /xfsearch/chitaet/<name>/}, cycles
 * {@code /xfsearch/cikl/<name>/}, all with the index's page scheme. Cards carry title, author (no link), duration,
 * the site's 5-star rating and the cycle with its number.</li>
 * <li>sorts: the page has the DLE sort form (date, rating, news_read, comm_num), but the server ignores it, POSTed
 * or as a query, on the index and in genres alike: every answer is newest first. So the Order menu is the index and
 * the fixed {@link #TOP_100} (one page of 100 cards in their own markup).</li>
 * <li>genres: no index page; the sidebar and "Усі жанри" menus of any page ({@link #GENRES}) and
 * {@link #HIDDEN_GENRES}, categories books link to that no menu names.</li>
 * <li>authors {@link #AUTHORS}, readers {@link #READERS}: one page each with every name and its count; their search is
 * a filter in the page's script, so there is no people search.</li>
 * <li>search: {@code /index.php?do=search&subaction=search&story=Q&search_start=N}, GET, 24 cards; the "знайдено"
 * count and the pager stop at 120 hits while the pages go on.</li>
 * <li>book {@code /<id>-<slug>.html}: Playerjs {@code file:"{v1}m33u2/<id>-<slug>.m3u"} ({@code {v1}} is the site
 * root), a text playlist of signed URLs ({@code ?expires=..&md5=..}, about 10 h) on {@link #MEDIA_HOST}: one HLS
 * {@code pl.m3u8} (unsigned TS segments) for newer books, one MP3 per part for older ones. The media host answers 403
 * without a Referer on the site. A book taken down keeps its page with an empty player.</li>
 * </ul>
 */
public final class FourRead implements SourceParser {

    public static final String SERVER = "https://4read.org";
    public static final String INDEX = SERVER + "/";
    /** The genre menus are on every page; the index is the one fetched. */
    public static final String GENRES = SERVER + "/";
    public static final String AUTHORS = SERVER + "/avtors.html";
    public static final String READERS = SERVER + "/readers.html";
    public static final String TOP_100 = SERVER + "/top-100.html";
    /** The audio host, {@code s1.}, {@code media.} and the bare domain; it serves this site alone. */
    public static final String MEDIA_HOST = "reasd.org";

    /** Categories books link to that no menu names: {name, slug}. */
    static final String[][] HIDDEN_GENRES = {{"Антиутопія", "antiutopia"}, {"Гумор", "humor"}};

    static final int SEARCH_PAGE_SIZE = 24;
    /** The search reports at most this many hits, however many pages follow. */
    static final int SEARCH_COUNT_CAP = 120;
    /** Cycle pages read at most for the series tab (24 books each). */
    static final int MAX_SERIES_PAGES = 5;

    static final List<Sort.Entry> MENU = Sort.menu(Sort.NEW, Sort.TOP_100);

    private static final Pattern INDEX_PATH = Pattern.compile("^/(page/\\d+/?)?$");
    private static final Pattern GENRE_ROOT = Pattern.compile("^/[a-z0-9-]+/$");
    private static final Pattern SEARCH_FOUND =
            Pattern.compile("знайдено (\\d+) відповід\\S* \\(Результати запиту \\d+ - (\\d+)\\)");
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)?");
    private static final Pattern HMS = Pattern.compile("(\\d+):(\\d{1,2}):(\\d{1,2})");
    private static final Pattern ABSOLUTE = Pattern.compile("(?i)^https?://\\S+$");

    private static final String CARDS = "main .grid-items > .poster, #top100-list > li.top100-card";
    /** The "Жанр:", "Автор:", "Читає:", "Триває:", "Цикл:" rows of a book page. */
    private static final String ROWS = "ul.pmovie__list > li";

    // ---------------------------------------------------------------- SourceParser

    @Override
    public Set<String> hosts() {
        return Collections.singleton(MEDIA_HOST);
    }

    @Override
    public ProviderPage<BookPOJO> listing(Document page, String url, int pageNo) {
        return parseListing(page, pageNo);
    }

    /** The top-100 and the people indexes are single pages. */
    @Override
    public String pageUrl(String url, int page) {
        HttpUrl parsed = HttpUrl.parse(url);
        return parsed != null && parsed.encodedPath().endsWith(".html") ? url : Dle.pageUrl(url, page);
    }

    /** The index and the top-100 offer each other; nothing else sorts. */
    @Override
    public List<Sort.Entry> sorts(String url) {
        return isIndex(url) || TOP_100.equals(url) ? MENU : Collections.<Sort.Entry>emptyList();
    }

    @Override
    public String sortUrl(Sort sort, String listingUrl) {
        switch (sort) {
            case NEW:
                return INDEX;
            case TOP_100:
                return TOP_100;
            default:
                return null;
        }
    }

    @Override
    public ProviderPage<GenrePOJO> genres(Document page, String url, int pageNo) {
        return parseGenres(page);
    }

    @Override
    public ProviderPage<GenrePOJO> people(Document page, String url, int pageNo, boolean performers) {
        return parsePeople(page);
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
        return parseBook(page, bookUrl);
    }

    @Override
    public ArrayList<BookPOJO> recommendations(Document page, String bookUrl) {
        return parseRecommendations(page);
    }

    /** The m3u the player names, fetched with the book page as Referer; a taken-down book has an empty player. */
    @Override
    public ArrayList<AudioPOJO> tracks(Document page, String base) throws IOException {
        String playlist = playlistUrl(page, base);
        return parseTracks(SiteHttp.body(SiteHttp.connect(playlist).referrer(base)), playlist,
                parseDescription(page).getTitle(), bookSeconds(page));
    }

    /** The media host refuses a request without a Referer on the site (the playlist, the m3u8 and its segments). */
    @Override
    public Map<String, String> mediaHeaders(String bookUrl) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", INDEX);
        return headers;
    }

    /** The book's cycle, every page of it, by the number each card shows. */
    @Override
    public ArrayList<SeriesPOJO> series(Document page, String bookUrl) throws IOException {
        ArrayList<SeriesPOJO> result = new ArrayList<>();
        String cycle = parseDescription(page).getSeriesUrl();
        for (int no = 1; cycle != null && !cycle.isEmpty() && no <= MAX_SERIES_PAGES; no++) {
            Document doc = fetch(pageUrl(cycle, no));
            result.addAll(parseSeries(doc));
            if (lastPage(doc) <= no) {
                break;
            }
        }
        sortByNumber(result);
        return result;
    }

    // ---------------------------------------------------------------- URLs

    /** The plain index: {@code /} or {@code /page/N/}. */
    static boolean isIndex(String url) {
        HttpUrl parsed = HttpUrl.parse(url);
        return parsed != null && parsed.query() == null && parsed.host().equals(HttpUrl.get(SERVER).host())
                && INDEX_PATH.matcher(parsed.encodedPath()).matches();
    }

    /** The m3u of the book's Playerjs; a {@link BookRemovedException} when its player is empty. */
    static String playlistUrl(Document page, String base) {
        Element player = page.getElementById("audioplayer");
        if (player != null && !player.html().contains("Playerjs")) {
            throw new BookRemovedException(base);
        }
        String url = Players.resolve(INDEX, Players.playerjsFile(page).replace("{v1}", ""));
        if (url.isEmpty()) {
            throw new IllegalStateException("4read player names no playlist: " + base);
        }
        return url;
    }

    // ---------------------------------------------------------------- listings

    /** Index, genre, author, reader, cycle, search and top-100 pages. */
    public static ProviderPage<BookPOJO> parseListing(Document doc, int page) {
        Elements raw = doc.select(CARDS);
        return ProviderPage.of(Html.cards(raw, FourRead::card), lastPage(doc) > page, raw.size());
    }

    /** A search page: the pager stops at {@link #SEARCH_COUNT_CAP} hits, so a full page at the cap has more. */
    public static ProviderPage<BookPOJO> parseSearch(Document doc, int page) {
        Elements raw = doc.select(CARDS);
        boolean hasMore = raw.size() >= SEARCH_PAGE_SIZE;
        Matcher m = SEARCH_FOUND.matcher(doc.text());
        if (hasMore && m.find()) {
            int found = Integer.parseInt(m.group(1));
            hasMore = found >= SEARCH_COUNT_CAP || Integer.parseInt(m.group(2)) < found;
        }
        return ProviderPage.of(Html.cards(raw, FourRead::card), hasMore, raw.size());
    }

    @Nullable
    private static BookPOJO card(Element card) {
        if (card.hasClass("top100-card")) {
            return topCard(card);
        }
        Element link = card.selectFirst("a.poster__link");
        if (link == null) {
            return null;
        }
        BookPOJO book = new BookPOJO();
        book.setUrl(link.absUrl("href"));
        book.setName(text(link, ".poster__title"));
        book.setPhoto(Html.imgSrc(card.selectFirst(".poster__img img")));
        book.setAutor(text(card, ".poster__subtitle.ws-nowrap"));
        Element duration = card.selectFirst(".js-duration[data-time]");
        if (duration != null) {
            book.setTime(duration.attr("data-time").trim());
        }
        Element series = card.selectFirst(".poster__series a");
        if (series != null) {
            book.setSeries(series.text().trim());
            book.setUrlSeries(series.absUrl("href"));
        }
        setRating(book, firstNumber(text(card, ".poster__ratings")));
        return book;
    }

    /** A card of {@link #TOP_100}: genres, duration, rating and comments in rows of its own. */
    @Nullable
    private static BookPOJO topCard(Element card) {
        Element link = card.selectFirst("a.top100-card__headline");
        if (link == null) {
            return null;
        }
        BookPOJO book = new BookPOJO();
        book.setUrl(link.absUrl("href"));
        book.setName(link.text().trim());
        book.setPhoto(Html.imgSrc(card.selectFirst(".top100-card__cover img")));
        book.setAutor(text(card, ".top100-card__author"));
        Element genres = card.selectFirst(".top100-card__categories");
        book.setGenre(text(genres));
        book.setUrlGenre(href(genres, "a"));
        book.setTime(afterLabel(card.selectFirst(".top100-card__duration")));
        Element comments = card.selectFirst(".top100-card__comments a");
        if (comments != null) {
            book.setComents(comments.ownText().trim());
        }
        setRating(book, firstNumber(text(card, ".card__rating-ext")));
        return book;
    }

    /** The highest page number in the pager (links of listings, {@code list_submit(N)} of searches); 0 without one. */
    static int lastPage(Document doc) {
        return Html.lastPage(doc.select("#pagination .pagination__pages > a, #pagination .pagination__pages > span"));
    }

    // ---------------------------------------------------------------- sections

    /** The sidebar and "Усі жанри" menus (genre roots only, first name wins), then {@link #HIDDEN_GENRES}. */
    public static ProviderPage<GenrePOJO> parseGenres(Document doc) {
        Map<String, GenrePOJO> byUrl = new LinkedHashMap<>();
        for (Element a : doc.select(".sb__nav a, .sb__more-list a, .home-discovery__links a")) {
            String url = a.absUrl("href");
            HttpUrl parsed = HttpUrl.parse(url);
            String name = a.text().trim();
            if (parsed != null && GENRE_ROOT.matcher(parsed.encodedPath()).matches() && !name.isEmpty()
                    && !byUrl.containsKey(url)) {
                byUrl.put(url, new GenrePOJO(name, url));
            }
        }
        for (String[] hidden : HIDDEN_GENRES) {
            String url = INDEX + hidden[1] + "/";
            if (!byUrl.containsKey(url)) {
                byUrl.put(url, new GenrePOJO(hidden[0], url));
            }
        }
        return ProviderPage.of(new ArrayList<>(byUrl.values()), false);
    }

    /** {@link #AUTHORS} or {@link #READERS}: every name with its book count, on one page. */
    public static ProviderPage<GenrePOJO> parsePeople(Document doc) {
        return ProviderPage.of(Html.rows(doc.select(".persons-catalog__item > a"), a -> {
            GenrePOJO row = new GenrePOJO(text(a, ".persons-catalog__name"), a.absUrl("href"));
            String count = text(a, ".persons-catalog__count");
            if (!count.isEmpty()) {
                row.setDescription(count);
            }
            return row;
        }), false);
    }

    // ---------------------------------------------------------------- book page

    public static DescriptionPOJO parseDescription(Document doc) {
        DescriptionPOJO description = new DescriptionPOJO();
        Element article = doc.selectFirst("article.pmovie");
        if (article == null) {
            return description;
        }
        String title = text(article, ".pmovie__header h1 [itemprop=name]");
        description.setTitle(title.isEmpty() ? text(article, ".pmovie__header h1") : title);
        description.setPoster(Html.imgSrc(article.selectFirst(".pmovie__img img")));
        Element genre = labelled(article, ROWS, "Жанр");
        description.setGenre(afterLabel(genre));
        description.setGenreUrl(href(genre, "a"));
        Element author = labelled(article, ROWS, "Автор");
        description.setAutor(afterLabel(author));
        description.setAutorUrl(href(author, "a"));
        Element reader = labelled(article, ROWS, "Читає");
        description.setArtist(afterLabel(reader));
        description.setArtistUrl(href(reader, "a"));
        description.setTime(afterLabel(labelled(article, ROWS, "Триває")));
        Element series = labelled(article, ROWS, "Цикл");
        if (series != null) {
            description.setSeries(text(series, "a"));
            description.setSeriesUrl(href(series, "a"));
        }
        description.setReiting(text(article, ".pmovie__rating-score"));
        description.setDescription(Html.paragraphs(article.selectFirst(".pmovie__book-description")));
        return description;
    }

    /** A book opened by its link: the card fields, read off the book page, with its rating. */
    public static BookPOJO parseBook(Document doc, String url) {
        DescriptionPOJO d = parseDescription(doc);
        BookPOJO book = BookPOJO.of(d, url);
        setRating(book, d.getReiting());
        return book;
    }

    /** "Схожі аудіокниги". */
    public static ArrayList<BookPOJO> parseRecommendations(Document doc) {
        return Html.cards(doc.select(".pmovie__related .poster"), FourRead::card);
    }

    /** One page of a cycle listing as series entries: name, link, cover and the number on the card. */
    public static ArrayList<SeriesPOJO> parseSeries(Document doc) {
        return Html.each(doc.select("main .grid-items > .poster"), card -> {
            Element link = card.selectFirst("a.poster__link");
            String url = link == null ? "" : link.absUrl("href");
            if (url.isEmpty()) {
                return null;
            }
            SeriesPOJO item = new SeriesPOJO();
            item.setUrl(url);
            item.setName(text(link, ".poster__title"));
            item.setNumber(text(card, ".poster__label"));
            item.setPhoto(Html.imgSrc(card.selectFirst(".poster__img img")));
            return item;
        });
    }

    /** By the cycle number ("1.1" before "2"); entries without one keep their order after the numbered. */
    static void sortByNumber(List<SeriesPOJO> items) {
        Collections.sort(items, new Comparator<SeriesPOJO>() {
            @Override
            public int compare(SeriesPOJO a, SeriesPOJO b) {
                return Double.compare(number(a.getNumber()), number(b.getNumber()));
            }
        });
    }

    private static double number(String text) {
        String n = firstNumber(text);
        return n.isEmpty() ? Double.MAX_VALUE : Double.parseDouble(n.replace(',', '.'));
    }

    // ---------------------------------------------------------------- chapters

    /**
     * The m3u answer: absolute URLs, one per line, {@code #EXTINF} names and lengths when it has them. One HLS
     * stream is the whole book, named after it with its duration; an MP3 part is named after its file.
     */
    public static ArrayList<AudioPOJO> parseTracks(String m3u, String playlistUrl, String bookName,
            int bookSeconds) {
        Players.Chapters chapters = new Players.Chapters(bookName, playlistUrl);
        String title = "";
        int length = 0;
        for (String raw : m3u.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.startsWith("#EXTINF:")) {
                int comma = line.indexOf(',');
                String extinf = (comma == -1 ? line.substring(8) : line.substring(8, comma)).trim();
                String n = extinf.startsWith("-") ? "" : firstNumber(extinf); // -1: unknown
                length = n.isEmpty() ? 0 : (int) Double.parseDouble(n.replace(',', '.'));
                title = comma == -1 ? "" : line.substring(comma + 1).trim();
            } else if (!line.isEmpty() && !line.startsWith("#")) {
                if (!ABSOLUTE.matcher(line).matches()) {
                    chapters.skipped++;
                } else {
                    boolean hls = isHls(line);
                    String name = !title.isEmpty() ? title : hls ? bookName : fileName(line);
                    chapters.add(chapters.chapter(name, line, length > 0 ? length : hls ? bookSeconds : 0));
                }
                title = "";
                length = 0;
            }
        }
        return chapters.done("4read playlist");
    }

    private static boolean isHls(String url) {
        HttpUrl parsed = HttpUrl.parse(url);
        return parsed != null && parsed.encodedPath().toLowerCase(java.util.Locale.ROOT).endsWith(".m3u8");
    }

    /** The decoded last path segment without its extension ("Темна матерія-01"). */
    private static String fileName(String url) {
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null || parsed.pathSegments().isEmpty()) {
            return "";
        }
        String name = parsed.pathSegments().get(parsed.pathSegments().size() - 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    // ---------------------------------------------------------------- helpers

    /** The book's "Триває: H:MM:SS" in seconds (the description keeps it only as display text); 0 without it. */
    static int bookSeconds(Document doc) {
        Element article = doc.selectFirst("article.pmovie");
        return article == null ? 0 : seconds(afterLabel(labelled(article, ROWS, "Триває")));
    }

    /** Seconds of "H:MM:SS"; 0 when absent. */
    static int seconds(@Nullable String time) {
        Matcher m = HMS.matcher(time == null ? "" : time);
        return m.find() ? Integer.parseInt(m.group(1)) * 3600 + Integer.parseInt(m.group(2)) * 60
                + Integer.parseInt(m.group(3)) : 0;
    }

    /** The first number of {@code text} ("Рейтинг 4.5 (4 голоси)" → "4.5"); "" without one. */
    private static String firstNumber(String text) {
        Matcher m = NUMBER.matcher(text == null ? "" : text);
        return m.find() ? m.group() : "";
    }

    /** The site's 5-star average; a card without votes keeps the "0" the list hides. */
    private static void setRating(BookPOJO book, String rating) {
        if (rating != null && !rating.isEmpty()) {
            book.setReting(rating);
        }
    }
}
