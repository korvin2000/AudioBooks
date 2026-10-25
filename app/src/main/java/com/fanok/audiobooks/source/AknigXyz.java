package com.fanok.audiobooks.source;

import androidx.annotation.Nullable;

import com.fanok.audiobooks.R;
import com.fanok.audiobooks.model.AudioModel;
import com.fanok.audiobooks.net.SiteHttp;
import com.fanok.audiobooks.pojo.AudioPOJO;
import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.pojo.ComentsPOJO;
import com.fanok.audiobooks.pojo.DescriptionPOJO;
import com.fanok.audiobooks.pojo.GenrePOJO;
import com.fanok.audiobooks.pojo.SeriesPOJO;
import com.fanok.audiobooks.search.ProviderPage;
import com.fanok.audiobooks.search.SearchUrls;

import io.reactivex.functions.BooleanSupplier;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * aknig.xyz: DataLife Engine, template "aknig" (probed live, 2026-10-25). The site lives on a numbered mirror,
 * {@link #SERVER}; {@code aknig.xyz} answers 302 to it with the path kept (a host alias).
 * <ul>
 * <li>index: {@code /}, then {@code /page/N/}; 12 cards a page, pager {@code .navigation-center}. {@code /page/1/}
 * is a 301 to {@code http://} and back (2-4 hops) and drops a POST body, so page 1 is the root.</li>
 * <li>sorts: the DLE sort form POSTed to the listing page itself: {@code dlenewssortby} date, rating, news_read
 * (views), comm_num or title, keys {@code dle_sort_main}/{@code dle_direction_main}, in a genre
 * {@code dle_sort_cat_<id>}/{@code dle_direction_cat_<id>} ({@link #categoryId}). A GET with that query is a 404.
 * The choice sticks to the PHPSESSID session, so a plain GET of a listing the session sorted comes back sorted
 * (the form on the page still says "date"): {@link #fetch} sends index and genre pages with their sort. No
 * periods; the fixed {@code /top100.html} is not offered.</li>
 * <li>genres: {@link #GENRES}; a genre is {@code /<slug>/} with the index's page scheme.</li>
 * <li>search: {@code /index.php?do=search&subaction=search&story=Q&search_start=N}, GET, 10 cards; the "найдено"
 * count stops at 50 while the pages go on.</li>
 * <li>author, reader, series, year: {@code /xfsearch/author|reader|serie|god/<value>/} with the index's page
 * scheme; there is no index of all authors or readers.</li>
 * <li>book {@code /<id>-<slug>.html}: comments inline (newest first), the series in the "Книги серии" tab, similar
 * books. Playerjs {@code file} is {@code /uploads/pl/YYYY-MM/<name>.txt}, a JSON playlist that answers 403 unless
 * the Referer is on this host; tracks are archive.org URLs (302 to a mirror, no headers needed). A book taken down
 * shows {@code div.berrors} ("удалена по требованию правообладателя") instead of the player.</li>
 * </ul>
 */
public final class AknigXyz implements SourceParser {

    public static final String SERVER = "https://s2.aknig.xyz";
    public static final String INDEX = SERVER + "/";
    public static final String GENRES = SERVER + "/genres.html";

    static final int SEARCH_PAGE_SIZE = 10;
    /** The search reports at most this many hits, however many pages follow. */
    static final int SEARCH_COUNT_CAP = 50;

    /** The DLE sort form: field and direction. */
    public enum Sort {
        NEW("date", "desc"), RATING("rating", "desc"), POPULAR("news_read", "desc"),
        COMMENTS("comm_num", "desc"), TITLE("title", "asc");

        final String field;
        final String direction;

        Sort(String field, String direction) {
            this.field = field;
            this.direction = direction;
        }
    }

    /** Genre root → DLE category id, learnt from the genre's own pages (their sort form). */
    private static final Map<String, String> CATEGORY_IDS = new ConcurrentHashMap<>();

    private static final Pattern CATEGORY = Pattern.compile("dle_sort_cat_(\\d+)");
    private static final Pattern GENRE_PATH = Pattern.compile("^/([a-z0-9-]+)/(page/\\d+/?)?$");
    private static final Pattern INDEX_PATH = Pattern.compile("^/(page/(\\d+)/?)?$");
    private static final Pattern PAGE = Pattern.compile("/page/(\\d+)/?$");
    private static final Pattern SEARCH_FOUND =
            Pattern.compile("найдено (\\d+) ответов \\(Результаты запроса \\d+ - (\\d+)\\)");
    private static final Pattern SERIES_ITEM = Pattern.compile("^#\\s*(\\d+)\\.?\\s*(.*)$");

    // ---------------------------------------------------------------- SourceParser

    /**
     * Sorted URLs are POSTed. An index page always goes out with its sort ("new" too); so does a genre page once its
     * id is known. Until then the session cannot have sorted the genre (a sort needs the id), so the plain GET is in
     * order, and it tells the id.
     */
    @Override
    public Document fetch(String url) throws IOException {
        if (url.contains(Dle.SORT_PARAM + "=")) {
            return Dle.fetch(url);
        }
        String genre = genreOf(url);
        if (genre == null) {
            return isIndex(url) ? Dle.fetch(Dle.pageUrl(sortedUrl(INDEX, Sort.NEW, null), pageOf(url)))
                    : SiteHttp.get(url);
        }
        String id = CATEGORY_IDS.get(genre);
        if (id != null) {
            return Dle.fetch(Dle.pageUrl(sortedUrl(genre, Sort.NEW, id), pageOf(url)));
        }
        return learnCategory(genre, SiteHttp.get(url));
    }

    /** Remembers the category id {@code page} of {@code genre} shows; returns the page. */
    static Document learnCategory(String genre, Document page) {
        String id = categoryId(page);
        if (id != null) {
            CATEGORY_IDS.put(genre, id);
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

    @Override
    public int optionsMenu() {
        return R.menu.books_dle_options_menu;
    }

    @Override
    public String sortUrl(int itemId, String listingUrl) {
        Sort sort = sortOf(itemId);
        if (sort == null) {
            return null;
        }
        if (sort == Sort.NEW && isIndex(listingUrl)) {
            return listingUrl;
        }
        String url = sortedUrl(INDEX, sort, null);
        return url.equals(listingUrl) ? listingUrl : url;
    }

    /** The index and genres sort; author, reader, series and search pages do not. */
    @Override
    public boolean hasSort(String url) {
        return isIndex(url) || genreOf(url) != null || url.contains("set_new_sort=dle_sort_main");
    }

    /** The genre root ({@code https://s2.aknig.xyz/<slug>/}) of a genre listing, plain or sorted; null elsewhere. */
    @Override
    public String genreOf(String url) {
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null || !parsed.host().equals(HttpUrl.get(SERVER).host())) {
            return null;
        }
        Matcher m = GENRE_PATH.matcher(parsed.encodedPath());
        return m.matches() && !m.group(1).equals("page") ? INDEX + m.group(1) + "/" : null;
    }

    /** The site-wide sort {@code siteWide} within {@code genre}; site-wide while the genre's id is unknown. */
    @Override
    public String inGenre(String siteWide, String genre) {
        String id = CATEGORY_IDS.get(genre);
        HttpUrl parsed = HttpUrl.parse(siteWide);
        String field = parsed == null ? null : parsed.queryParameter(Dle.SORT_PARAM);
        for (Sort sort : Sort.values()) {
            if (id != null && sort.field.equals(field)) {
                return sortedUrl(genre, sort, id);
            }
        }
        return siteWide;
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
        return parseBook(page, bookUrl);
    }

    @Override
    public ArrayList<BookPOJO> recommendations(Document page, String bookUrl) {
        return parseRecommendations(page);
    }

    /** The Playerjs playlist, fetched with the book page as Referer; a removed book has no player. */
    @Override
    public ArrayList<AudioPOJO> tracks(Document page, String base) throws IOException {
        return AudioModel.loadPlayerjs(page, base, text(page, ".full-news-title h1"));
    }

    @Override
    public ArrayList<SeriesPOJO> series(Document page, String bookUrl) {
        return parseSeries(page);
    }

    @Override
    public ArrayList<ComentsPOJO> comments(String bookUrl, BooleanSupplier cancelled) throws Exception {
        return parseComments(fetch(bookUrl));
    }

    // ---------------------------------------------------------------- URLs

    /**
     * Page 1 of {@code listingUrl} sorted by {@code sort}: the sort form as the query, which {@link Dle#fetch} POSTs
     * to the page itself. {@code categoryId} is the genre's DLE id, null for the index.
     */
    public static String sortedUrl(String listingUrl, Sort sort, @Nullable String categoryId) {
        int query = listingUrl.indexOf('?');
        String key = categoryId == null ? "main" : "cat_" + categoryId;
        return Dle.pageUrl(query == -1 ? listingUrl : listingUrl.substring(0, query), 1)
                + "?" + Dle.SORT_PARAM + "=" + sort.field + "&dledirection=" + sort.direction
                + "&set_new_sort=dle_sort_" + key + "&set_direction_sort=dle_direction_" + key;
    }

    /** The DLE id of the genre a listing page shows (its sort form's key); null on the index and xfsearch pages. */
    @Nullable
    public static String categoryId(Document doc) {
        Element key = doc.getElementById("set_new_sort");
        Matcher m = CATEGORY.matcher(key == null ? "" : key.val());
        return m.matches() ? m.group(1) : null;
    }

    @Nullable
    private static Sort sortOf(int itemId) {
        if (itemId == R.id.new_data) {
            return Sort.NEW;
        } else if (itemId == R.id.popular) {
            return Sort.POPULAR;
        } else if (itemId == R.id.reting) {
            return Sort.RATING;
        } else if (itemId == R.id.coments) {
            return Sort.COMMENTS;
        } else if (itemId == R.id.sort_title) {
            return Sort.TITLE;
        }
        return null;
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

    /** Index, sorted, genre, xfsearch and top-100 pages. */
    public static ProviderPage<BookPOJO> parseListing(Document doc, int page) {
        Elements raw = doc.select("div.main-news.ajax-news");
        return ProviderPage.of(cards(raw), lastPage(doc) > page, raw.size());
    }

    /** A search page; also the other-source search (a card's url, name, autor and artist are the hit). */
    public static ProviderPage<BookPOJO> parseSearch(Document doc, int page) {
        Elements raw = doc.select("div.main-news.ajax-news");
        boolean hasMore = raw.size() >= SEARCH_PAGE_SIZE;
        Matcher m = SEARCH_FOUND.matcher(doc.text());
        if (hasMore && m.find()) {
            int found = Integer.parseInt(m.group(1));
            hasMore = found >= SEARCH_COUNT_CAP || Integer.parseInt(m.group(2)) < found;
        }
        return ProviderPage.of(cards(raw), hasMore, raw.size());
    }

    private static ArrayList<BookPOJO> cards(Elements cards) {
        ArrayList<BookPOJO> out = new ArrayList<>();
        for (Element card : cards) {
            try {
                BookPOJO book = card(card);
                if (book != null && !book.isNull()) {
                    out.add(book);
                }
            } catch (RuntimeException ignored) {
                // one malformed card is not a page failure
            }
        }
        return out;
    }

    @Nullable
    private static BookPOJO card(Element card) {
        Element title = card.selectFirst(".main-news-title a");
        if (title == null) {
            return null;
        }
        BookPOJO book = new BookPOJO();
        book.setUrl(title.absUrl("href"));
        book.setName(title.text().trim());
        Element img = card.selectFirst(".main-news-image img");
        if (img != null) {
            book.setPhoto(img.absUrl("src"));
        }
        for (Element row : card.select(".main-news-info > div")) {
            Element a = row.selectFirst("a");
            String link = a == null ? "" : a.absUrl("href");
            String label = row.text();
            if (label.startsWith("Автор")) {
                book.setAutor(value(row));
                book.setUrlAutor(link);
            } else if (label.startsWith("Читает")) {
                book.setArtist(value(row));
                book.setUrlArtist(link);
            } else if (label.startsWith("Жанр")) {
                book.setGenre(value(row));
                book.setUrlGenre(link);
            }
        }
        Element series = card.selectFirst(".main-news-ck a");
        if (series != null) {
            book.setSeries(series.text().trim());
            book.setUrlSeries(series.absUrl("href"));
        }
        book.setTime(duration(card.select(".main-news-stat-r > div")));
        book.setComents(iconText(card, ".main-news-stat-r .fi-rr-comment"));
        setRating(book, text(card, ".main-news-rating-l .ratingtypeplusminus"));
        book.setDesc(text(card, ".main-news-text"));
        return book;
    }

    /** The highest page number in the pager; 0 without one. */
    private static int lastPage(Document doc) {
        int last = 0;
        for (Element item : doc.select(".navigation-center a, .navigation-center span")) {
            String number = item.text().trim();
            if (number.matches("\\d+")) {
                last = Math.max(last, Integer.parseInt(number));
            }
        }
        return last;
    }

    // ---------------------------------------------------------------- genres

    /** {@link #GENRES}; the hidden "ban" category of taken-down books is left out. */
    public static ProviderPage<GenrePOJO> parseGenres(Document doc) {
        ArrayList<GenrePOJO> out = new ArrayList<>();
        for (Element a : doc.select(".ctwrap a[href]")) {
            String url = a.absUrl("href");
            if (!url.isEmpty() && !url.contains("/zablokirovannye-knigi/")) {
                out.add(new GenrePOJO(a.text().trim(), url));
            }
        }
        return ProviderPage.of(out, false);
    }

    // ---------------------------------------------------------------- book page

    public static DescriptionPOJO parseDescription(Document doc) {
        DescriptionPOJO description = new DescriptionPOJO();
        description.setTitle(text(doc, ".full-news-title h1"));
        Element img = doc.selectFirst(".full-news-image img");
        if (img != null) {
            description.setPoster(img.absUrl("src"));
        }
        // the app's (desktop) UA gets this layout, a phone the .mflf one; comments carry an empty full-news-author
        Element author = doc.selectFirst(".full-news-author:has(a)");
        if (author != null) {
            description.setAutor(value(author));
            description.setAutorUrl(href(author));
        }
        Element reader = doc.selectFirst(".full-news-wread");
        if (reader != null) {
            description.setArtist(value(reader));
            description.setArtistUrl(href(reader));
        }
        Element genre = doc.selectFirst(".full-news-category");
        if (genre != null) {
            description.setGenre(value(genre));
            description.setGenreUrl(href(genre));
        }
        Element series = doc.selectFirst(".full-news-ckl a");
        if (series != null) {
            description.setSeries(series.text().trim());
            description.setSeriesUrl(series.absUrl("href"));
        }
        description.setTime(duration(doc.select(".main-news-stat-r > div, .fmlstat > div")));
        description.setReiting(text(doc, ".full-news-rating .ratingtypeplusminus, .mflf-rating-n"));
        description.setDescription(text(doc, "div.full-news-text.obr"));
        return description;
    }

    /** A book opened by its link: the card fields, read off the book page. */
    public static BookPOJO parseBook(Document doc, String url) {
        DescriptionPOJO d = parseDescription(doc);
        BookPOJO book = new BookPOJO();
        book.setUrl(url);
        book.setName(d.getTitle());
        book.setPhoto(d.getPoster());
        book.setAutor(d.getAutor());
        book.setUrlAutor(d.getAutorUrl());
        book.setArtist(d.getArtist());
        book.setUrlArtist(d.getArtistUrl());
        book.setGenre(d.getGenre());
        book.setUrlGenre(d.getGenreUrl());
        book.setSeries(d.getSeries());
        book.setUrlSeries(d.getSeriesUrl());
        book.setTime(d.getTime());
        setRating(book, d.getReiting());
        book.setDesc(d.getDescription());
        return book;
    }

    /** "Похожие книги". */
    public static ArrayList<BookPOJO> parseRecommendations(Document doc) {
        ArrayList<BookPOJO> out = new ArrayList<>();
        for (Element a : doc.select(".relfr a.slider-item")) {
            BookPOJO book = new BookPOJO();
            book.setUrl(a.absUrl("href"));
            book.setName(text(a, ".slider-item-title"));
            Element img = a.selectFirst(".slider-item-image img");
            if (img != null) {
                book.setPhoto(img.absUrl("src"));
            }
            setRating(book, text(a, ".slider-item-rating .ratingtypeplusminus"));
            book.setComents(iconText(a, ".fi-rr-comment"));
            if (!book.isNull()) {
                out.add(book);
            }
        }
        return out;
    }

    /** The "Книги серии" tab, in the site's order (the open book included); empty when the book has no series. */
    public static ArrayList<SeriesPOJO> parseSeries(Document doc) {
        ArrayList<SeriesPOJO> out = new ArrayList<>();
        for (Element a : doc.select(".fserline-wrap a.fserline")) {
            String url = a.absUrl("href");
            if (url.isEmpty()) {
                continue;
            }
            SeriesPOJO item = new SeriesPOJO();
            item.setUrl(url);
            String caption = a.text().trim();
            Matcher m = SERIES_ITEM.matcher(caption);
            if (m.matches()) {
                item.setNumber(m.group(1));
                caption = m.group(2);
            }
            item.setName(caption);
            out.add(item);
        }
        return out;
    }

    /** All comments are on the book page; oldest first, like the other DLE sites. Flat: a reply quotes. */
    public static ArrayList<ComentsPOJO> parseComments(Document doc) {
        ArrayList<ComentsPOJO> out = new ArrayList<>();
        for (Element comment : doc.select("#dle-comments-list div[id^=comment-id-]")) {
            Element body = comment.selectFirst(".com-item-text div[id^=comm-id-]");
            if (body == null) {
                continue;
            }
            ComentsPOJO pojo = new ComentsPOJO();
            pojo.setName(text(comment, ".com-item-author"));
            pojo.setDate(text(comment, ".com-item-date"));
            String rating = text(comment, ".com-item-rating .ratingtypeplusminus");
            pojo.setReting(rating.equals("0") ? "" : rating);
            Element avatar = comment.selectFirst(".com-item-image img");
            pojo.setImage(avatar == null ? "" : avatar.absUrl("src"));
            Element quote = body.selectFirst(".quote");
            Element quoteTitle = body.selectFirst(".title_quote");
            if (quote != null) {
                pojo.setQuoteText(quote.text());
                pojo.setQuoteName(quoteTitle == null ? "" : quoteTitle.text().replace("Цитата:", "").trim());
                Element own = body.clone();
                own.select(".quote, .title_quote").remove();
                pojo.setText(own.text().isEmpty() ? quote.text() : own.text());
            } else {
                pojo.setText(body.text());
            }
            if (!pojo.isEmty()) {
                out.add(pojo);
            }
        }
        Collections.reverse(out);
        return out;
    }

    // ---------------------------------------------------------------- helpers

    /** The site's net votes ("+7", "-2"); a card without them keeps the "0" the list hides. */
    private static void setRating(BookPOJO book, String rating) {
        if (!rating.isEmpty()) {
            book.setReting(rating);
        }
    }

    /** The text after a row's "Label:", e.g. both authors of "Автор: A, B". */
    private static String value(Element row) {
        String text = row.text();
        int colon = text.indexOf(':');
        return (colon == -1 ? text : text.substring(colon + 1)).trim();
    }

    private static String href(Element row) {
        Element a = row.selectFirst("a");
        return a == null ? "" : a.absUrl("href");
    }

    /** The H:MM:SS of the "Длительность:" stat; "" when absent. */
    private static String duration(Elements stats) {
        for (Element stat : stats) {
            if (stat.text().contains("Длительность")) {
                return stat.ownText().trim();
            }
        }
        return "";
    }

    /** The number next to an icon ({@code <div><i class="fi-rr-comment"></i>3</div>}). */
    private static String iconText(Element scope, String icon) {
        Element i = scope.selectFirst(icon);
        return i == null || i.parent() == null ? "" : i.parent().text().trim();
    }

    private static String text(Element scope, String query) {
        Element element = scope.selectFirst(query);
        return element == null ? "" : element.text().trim();
    }
}
