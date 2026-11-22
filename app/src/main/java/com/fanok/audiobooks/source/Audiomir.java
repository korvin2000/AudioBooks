package com.fanok.audiobooks.source;

import static com.fanok.audiobooks.source.Html.afterLabel;
import static com.fanok.audiobooks.source.Html.href;
import static com.fanok.audiobooks.source.Html.labelled;
import static com.fanok.audiobooks.source.Html.text;

import androidx.annotation.Nullable;

import com.fanok.audiobooks.BookRemovedException;
import com.fanok.audiobooks.model.BookPages;
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

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * audiomir.xyz: DataLife Engine, template "abook" (probed live, 2026-11-21). The site lives on {@link #SERVER};
 * {@code audiomir.xyz} answers 302 to it with the path kept (a host alias). No anti-bot wall.
 * <ul>
 * <li>index: {@code /}, then {@code /page/N/}; 12 cards a page ({@code div.main-news}), pager
 * {@code .navigation-center}. A card's title is "Title - Author(s)"; the author suffix is cut off.</li>
 * <li>sorts: none on the server (a POSTed {@code dlenewssortby} is ignored). Fixed one-page lists of 50:
 * {@link #COMMENTED} (most commented) and {@link #RANDOM}. {@code /topbooks.html} ("Популярные книги") is the 50
 * newest books, the index's own order, so it is not offered.</li>
 * <li>genres: {@link #GENRES}, {@code /<slug>/} with the index's page scheme; the list also links the people indexes
 * and the {@code /blocked/} category of taken-down books, which are left out.</li>
 * <li>search: {@code /index.php?do=search&subaction=search&story=Q&search_start=N} (GET, at least 3 letters), 10
 * cards; the "найдено" count stops at 50 while the pages go on.</li>
 * <li>people: {@link #AUTHORS} and {@link #READERS} are paged DLE lists of person pages ({@code /<id>-<slug>.html});
 * a person page lists all that person's books as cards. Card links go to {@code /xfsearch/avtor|chitaet|cikl/<name>/}
 * listings instead; the book page links its people's pages when they have one.</li>
 * <li>book {@code /<id>-<slug>.html}: "Другие книги из серии", "Похожие книги", DLE comments inline (oldest first).
 * Playerjs {@code file} is either an inline list (older books, archive.org) or {@code #2} base64 with the player's
 * key pieces ({@link #KEYS}) that decodes to {@code https://k20.aubks.org/books/<genre>/<slug>/<slug>.txt}, a JSON
 * {@code [{title,file}]} (UTF-8 BOM) of MP3s on the same host, which answers 403 without a Referer of the site. A
 * book taken down shows "удалена по требованию правообладателя" instead of the player.</li>
 * </ul>
 */
public final class Audiomir implements SourceParser {

    public static final String SERVER = "https://m1.audiomir.xyz";
    public static final String INDEX = SERVER + "/";
    public static final String GENRES = SERVER + "/audioknigi-po-zhanram.html";
    public static final String AUTHORS = SERVER + "/authors/";
    public static final String READERS = SERVER + "/reads/";
    public static final String COMMENTED = SERVER + "/commentsbooks.html";
    public static final String RANDOM = SERVER + "/randombooks.html";

    /** The audio host the site shares with its sister sites (fanmir.org, audiofant.org). */
    static final String AUDIO_HOST = "aubks.org";
    /** The Playerjs key pieces ({@code bk0..bk4}) of the site's player (2026-11-21). */
    static final String[] KEYS = {"//TTNrRzhyVDZ5Sjl4QnY=", "//QjdkSDRqUDFxVDlzVnc=", "//TDduWDJ3WjV0UTFZcA==",
            "//UjV2UThtTmM3WnAyTHhB", "//RjJnNlQ5Ylk0blgzSGo="};

    static final int SEARCH_PAGE_SIZE = 10;
    /** The search reports at most this many hits, however many pages follow. */
    static final int SEARCH_COUNT_CAP = 50;

    private static final List<Sort.Entry> MENU = Sort.menu(Sort.NEW, Sort.COMMENTS, Sort.RANDOM);
    private static final Pattern INDEX_URL = Pattern.compile(Pattern.quote(SERVER) + "/(page/\\d+/?)?");
    private static final Pattern SEARCH_FOUND =
            Pattern.compile("найдено (\\d+) ответов \\(Результаты запроса \\d+ - (\\d+)\\)");
    private static final Pattern DURATION = Pattern.compile("\\((\\d+:\\d{2}(?::\\d{2})?)\\)");
    private static final Pattern BACKGROUND = Pattern.compile("url\\(['\"]?([^'\")]+)");
    /** The "Год:", "Автор:", "Читает:", "Серия:" rows of a card or a book page. */
    private static final String ROWS = ".fnsc-left > div";
    private static final String CARDS = "div.main-news.ajax-news";

    // ---------------------------------------------------------------- SourceParser

    @Override
    public String referer() {
        return INDEX;
    }

    /** The MP3s and playlists on k20.aubks.org answer 403 without a Referer of the site; the CDN is shared. */
    @Override
    public Set<String> refererHosts() {
        return Collections.singleton(AUDIO_HOST);
    }

    @Override
    public ProviderPage<BookPOJO> listing(Document page, String url, int pageNo) {
        return parseListing(page, pageNo);
    }

    @Override
    public String pageUrl(String url, int page) {
        return Dle.pageUrl(url, page);
    }

    /** The index and the fixed lists; genre, people, series and search pages cannot be re-sorted. */
    @Override
    public List<Sort.Entry> sorts(String url) {
        return INDEX_URL.matcher(url).matches() || url.equals(COMMENTED) || url.equals(RANDOM) ? MENU
                : Collections.<Sort.Entry>emptyList();
    }

    @Override
    public String sortUrl(Sort sort, String listingUrl) {
        switch (sort) {
            case NEW: return INDEX;
            case COMMENTS: return COMMENTED;
            case RANDOM: return RANDOM;
            default: return null;
        }
    }

    @Override
    public ProviderPage<GenrePOJO> genres(Document page, String url, int pageNo) {
        return parseGenres(page);
    }

    @Override
    public ProviderPage<GenrePOJO> people(Document page, String url, int pageNo, boolean performers) {
        return parsePeople(page, pageNo);
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
    public ArrayList<BookPOJO> recommendations(Document page, String bookUrl) {
        return parseRecommendations(page);
    }

    /** An inline list as it is; else the decoded playlist URL, fetched with the book page as Referer. */
    @Override
    public ArrayList<AudioPOJO> tracks(Document page, String base) throws IOException {
        String title = parseDescription(page).getTitle();
        String playlist = playlistUrl(page, base);
        if (playlist == null) {
            return Players.inlinePlayerjs(page, base, title);
        }
        return parsePlaylist(SiteHttp.body(SiteHttp.connect(playlist).referrer(base)), playlist, title);
    }

    @Override
    public ArrayList<SeriesPOJO> series(Document page, String bookUrl) {
        return parseSeries(page);
    }

    @Override
    public ArrayList<ComentsPOJO> comments(String bookUrl, BooleanSupplier cancelled) throws Exception {
        return parseComments(BookPages.get(bookUrl, this::fetch));
    }

    // ---------------------------------------------------------------- listings

    /** Index, genre, fixed-list, xfsearch and person pages. */
    public static ProviderPage<BookPOJO> parseListing(Document doc, int page) {
        Elements raw = doc.select(CARDS);
        return ProviderPage.of(Html.cards(raw, Audiomir::card), lastPage(doc) > page, raw.size());
    }

    /** A search page; also the other-source search. */
    public static ProviderPage<BookPOJO> parseSearch(Document doc, int page) {
        Elements raw = doc.select(CARDS);
        boolean hasMore = raw.size() >= SEARCH_PAGE_SIZE;
        Matcher m = SEARCH_FOUND.matcher(doc.text());
        if (hasMore && m.find()) {
            int found = Integer.parseInt(m.group(1));
            hasMore = found >= SEARCH_COUNT_CAP || Integer.parseInt(m.group(2)) < found;
        }
        return ProviderPage.of(Html.cards(raw, Audiomir::card), hasMore, raw.size());
    }

    @Nullable
    private static BookPOJO card(Element card) {
        Element title = card.selectFirst(".main-news-title a");
        if (title == null) {
            return null;
        }
        BookPOJO book = new BookPOJO();
        book.setUrl(title.absUrl("href"));
        Element author = labelled(card, ROWS, "Автор");
        String authors = names(author);
        book.setAutor(authors);
        book.setUrlAutor(href(author, "a"));
        book.setName(withoutAuthor(title.text().trim(), authors));
        book.setPhoto(Html.imgSrc(card.selectFirst(".main-news-image img")));
        Element reader = labelled(card, ROWS, "Читает");
        book.setArtist(names(reader));
        book.setUrlArtist(href(reader, "a"));
        Element genres = card.selectFirst(".main-news-c");
        book.setGenre(names(genres));
        book.setUrlGenre(href(genres, "a"));
        Element series = labelled(card, ROWS, "Серия");
        Element seriesLink = series == null ? null : series.selectFirst("a");
        if (seriesLink != null) {
            book.setSeries(seriesLink.text().trim());
            book.setUrlSeries(seriesLink.absUrl("href"));
        }
        Matcher time = DURATION.matcher(text(card, ".main-news-play"));
        book.setTime(time.find() ? time.group(1) : "");
        book.setComents(Html.iconText(card, ".main-news-st img[ktoto-src$=comment.svg]"));
        String rating = text(card, ".main-news-rating .ratingtypeplusminus");
        if (!rating.isEmpty()) {
            book.setReting(rating);
        }
        book.setDesc(text(card, ".main-news-text"));
        return book;
    }

    private static int lastPage(Document doc) {
        return Html.lastPage(doc.select(".navigation-center a, .navigation-center span"));
    }

    // ---------------------------------------------------------------- genres, people

    /** {@link #GENRES} without the people indexes and the taken-down books. */
    public static ProviderPage<GenrePOJO> parseGenres(Document doc) {
        return ProviderPage.of(Html.rows(doc.select(".almn > a[href]"), a -> {
            String url = a.absUrl("href");
            if (url.equals(AUTHORS) || url.equals(READERS) || url.endsWith("/blocked/")) {
                return null;
            }
            GenrePOJO genre = new GenrePOJO(a.ownText().trim(), url);
            genre.setDescription(text(a, "span"));
            return genre;
        }), false);
    }

    /** A page of {@link #AUTHORS} or {@link #READERS}: person pages with their book counts. */
    public static ProviderPage<GenrePOJO> parsePeople(Document doc, int page) {
        return ProviderPage.of(Html.rows(doc.select("a.readers-item"), a -> {
            GenrePOJO person = new GenrePOJO(text(a, ".readers-item-title"), a.absUrl("href"));
            person.setDescription(text(a, ".readers-item-num").replaceAll("\\D+", ""));
            return person;
        }), lastPage(doc) > page);
    }

    // ---------------------------------------------------------------- book page

    public static DescriptionPOJO parseDescription(Document doc) {
        DescriptionPOJO description = new DescriptionPOJO();
        Element author = labelled(doc, ROWS, "Автор");
        String authors = names(author);
        description.setAutor(authors);
        description.setAutorUrl(href(author, "a"));
        description.setTitle(withoutAuthor(text(doc, ".full-news-title h1"), authors));
        description.setPoster(Html.imgSrc(doc.selectFirst(".full-news-image img")));
        Element reader = labelled(doc, ROWS, "Читает");
        description.setArtist(names(reader));
        description.setArtistUrl(href(reader, "a"));
        Element genres = doc.selectFirst(".full-news-tb .main-news-c");
        description.setGenre(names(genres));
        description.setGenreUrl(href(genres, "a"));
        Element series = labelled(doc, ROWS, "Серия");
        Element seriesLink = series == null ? null : series.selectFirst("a");
        if (seriesLink != null) {
            description.setSeries(seriesLink.text().trim());
            description.setSeriesUrl(seriesLink.absUrl("href"));
        }
        description.setTime(afterLabel(labelled(doc, ROWS, "Время")));
        String like = text(doc, ".full-news-rating [data-likes-id]");
        String dislike = text(doc, ".full-news-rating [data-dislikes-id]");
        if (!like.isEmpty() && !dislike.isEmpty()) {
            description.setLike(like);
            description.setDisLike(dislike);
        }
        description.setDescription(Html.paragraphs(doc.selectFirst(".full-news-text")));
        return description;
    }

    /** "Похожие книги". */
    public static ArrayList<BookPOJO> parseRecommendations(Document doc) {
        return Html.cards(doc.select(".rel-news a.weeknews"), a -> {
            BookPOJO book = new BookPOJO();
            book.setUrl(a.absUrl("href"));
            book.setName(text(a, ".weeknews-title"));
            Element image = a.selectFirst(".weeknews-image");
            Matcher m = BACKGROUND.matcher(image == null ? "" : image.attr("style"));
            if (m.find()) {
                book.setPhoto(m.group(1));
            }
            return book;
        });
    }

    /** "Другие книги из серии", the open book included; empty when the book has no series. */
    public static ArrayList<SeriesPOJO> parseSeries(Document doc) {
        ArrayList<SeriesPOJO> out = new ArrayList<>();
        for (Element block : doc.select(".rbls:has(.stlblock-title:contains(из серии))")) {
            for (Element a : block.select("a.weeknews")) {
                String url = a.absUrl("href");
                if (url.isEmpty()) {
                    continue;
                }
                SeriesPOJO item = new SeriesPOJO();
                item.setUrl(url);
                item.setName(text(a, ".weeknews-title"));
                Element image = a.selectFirst(".weeknews-image");
                item.setPhoto(image == null ? "" : image.absUrl("ktoto-src"));
                out.add(item);
            }
        }
        return out;
    }

    /** All comments are on the book page, oldest first. Flat: a reply quotes. */
    public static ArrayList<ComentsPOJO> parseComments(Document doc) {
        ArrayList<ComentsPOJO> out = new ArrayList<>();
        for (Element comment : doc.select("#dle-comments-list div[id^=comment-id-]")) {
            Element body = comment.selectFirst(".com-item-text div[id^=comm-id-]");
            if (body == null) {
                continue;
            }
            ComentsPOJO pojo = new ComentsPOJO();
            Element name = comment.selectFirst(".com-item-author");
            pojo.setName(name == null ? "" : name.ownText().trim());
            pojo.setDate(text(comment, ".com-item-date"));
            String rating = text(comment, ".com-item-rating .ratingtypeplusminus");
            pojo.setReting(rating.equals("0") ? "" : rating);
            Element avatar = comment.selectFirst(".com-item-image img");
            pojo.setImage(avatar == null ? "" : avatar.absUrl("src"));
            Element quote = body.selectFirst(".quote");
            if (quote != null) {
                pojo.setQuoteText(quote.text());
                pojo.setQuoteName(text(body, ".title_quote").replace("Цитата:", "").trim());
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
        return out;
    }

    // ---------------------------------------------------------------- chapters

    /**
     * The playlist URL the Playerjs {@code file} names (decoded with {@link #KEYS}); null when the list is inline. A
     * page without a player is a {@link BookRemovedException} when it shows the takedown notice, else an
     * {@link IllegalStateException}, as is a {@code file} that decodes to no URL (the player's keys changed).
     */
    @Nullable
    public static String playlistUrl(Document doc, String bookUrl) {
        String file;
        try {
            file = Players.playerjsFile(doc).trim();
        } catch (IllegalStateException noPlayer) {
            if (BookRemovedException.isNotice(doc.select(".stt-block-title").text())) {
                throw new BookRemovedException(bookUrl);
            }
            throw noPlayer;
        }
        if (file.startsWith("[")) {
            return null;
        }
        // the decoded text must be an absolute URL: garbage from changed keys is not a path on the site
        String url = file.startsWith("#2") ? Players.resolve(null, Players.playerjsKeyed(file, KEYS))
                : Players.resolve(bookUrl, file);
        if (url.isEmpty()) {
            throw new IllegalStateException("Playerjs file decodes to no URL on " + bookUrl);
        }
        return url;
    }

    /** The JSON playlist {@code [{title, file}]}; the site writes it with a UTF-8 BOM. */
    public static ArrayList<AudioPOJO> parsePlaylist(String json, String playlistUrl, String bookName) {
        return Players.parsePlayerjsPlaylist(json.replace(String.valueOf((char) 0xFEFF), ""), playlistUrl, bookName);
    }

    // ---------------------------------------------------------------- helpers

    /** The links of a row joined by ", " ("Автор: A B" → "A, B"); its text after the label without links. */
    private static String names(@Nullable Element row) {
        if (row == null) {
            return "";
        }
        Elements links = row.select("a");
        if (links.isEmpty()) {
            return afterLabel(row);
        }
        StringBuilder names = new StringBuilder();
        for (Element a : links) {
            names.append(names.length() == 0 ? "" : ", ").append(a.text().trim());
        }
        return names.toString();
    }

    /** "Title - Author" → "Title"; the title as it is when it does not end with that author. */
    static String withoutAuthor(String title, String author) {
        String suffix = " - " + author;
        return !author.isEmpty() && title.endsWith(suffix) ? title.substring(0, title.length() - suffix.length())
                .trim() : title;
    }
}
