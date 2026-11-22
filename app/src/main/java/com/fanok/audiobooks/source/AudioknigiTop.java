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
import com.fanok.audiobooks.search.ProviderPage;
import com.fanok.audiobooks.search.SearchUrls;

import io.reactivex.functions.BooleanSupplier;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import okhttp3.HttpUrl;

import org.jsoup.HttpStatusException;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;

/**
 * audioknigi.top: DataLife Engine, template "top", a front-end of the slovushko.com / vozknig backend whose catalog
 * overlaps knigavuhe.org's: 6 of 10 older books are there with the same reader, 1 of 7 fresh ones (probed live,
 * 2026-11-21). No wall, no cookies, no Referer for pages.
 * <ul>
 * <li>Listings of 18 {@code div.short-nm} cards (the {@code .card--big} carousels are left out): the index {@code /},
 * genres {@code /zhanr/<slug>/}, authors {@code /author/<slug>/}, readers {@code /read/<slug>/}, series
 * {@code /cikl/<slug>/}; page N is {@code <listing>page/N/}, the next page the pager's forward link. Newer cards carry
 * a duration and a series, older ones a year and genres instead. Person and series pages miss some newer books
 * (the series of "Темный охотник 14" lists book 1 alone), and one with none indexed answers 404 with an empty list
 * (Мария Метлицкая, 21 books by the authors' index): the site's data, an empty listing here. Some card titles are
 * escaped twice ({@code &amp;quot;}). The URL scheme and the sort keys are audioknigi.pro's
 * ({@link AudioknigiPro#fetchUrl}).</li>
 * <li>Sorts: no sort UI, but the DLE form POSTed to a page works (date, rating, views, comments, title), key
 * {@code main} on the index and {@code xfsearch} in a genre, each narrowed to the genre; no periods, no top list. The
 * PHPSESSID session keeps the last sort of a key, and a plain GET of an author's page came back in the title order a
 * genre had been sorted in, so {@link #fetch} POSTs every listing with its sort, "new" (date) too.</li>
 * <li>Indexes of one page each in the site's order, with book counts: genres {@link #GENRES} (107 rows, a "---" one
 * left out), authors {@link #AUTHORS} (2895), readers {@link #READERS} (1831), series {@code /cikly} (not used); each
 * is a 404 with a slash after it.</li>
 * <li>Search: POST only ({@code do=search&subaction=search&story=Q&search_start=N&result_from=M}; a GET finds
 * nothing), 10 cards a page.</li>
 * <li>Book {@code /<slug>} (no id in the URL): "Label: value" rows, inline comments (oldest first), "Другие аудиокниги
 * автора" on some (the "Послушайте эти аудиокниги" block is the six newest books on every page). One link may name several authors, a line each. The player's {@code data-playlist-endpoint}
 * ({@code /engine/ajax/controller.php?mod=audio_signed_playlist&id=<id>}) answers an XHR with
 * {@code {"ok","newsid","playlist"}}, the playlist a JSON list without its brackets (and newer ones without their
 * braces either). Newer books play signed MP3s on {@code s5.vozknig.com} (about 24 h, no headers needed), older ones
 * archive.org files; both are shared with other sites.</li>
 * </ul>
 */
public final class AudioknigiTop implements SourceParser {

    public static final String SERVER = "https://audioknigi.top";
    public static final String INDEX = SERVER + "/";
    public static final String GENRES = SERVER + "/genres";
    public static final String AUTHORS = SERVER + "/authors";
    public static final String READERS = SERVER + "/ispolniteli";

    // ---------------------------------------------------------------- SourceParser

    /**
     * The search is POSTed, every listing POSTed with its sort spelled out; the rest is a GET. A person or series page
     * answering 404 is the site's empty list ({@link #emptyOn404}).
     */
    @Override
    public Document fetch(String url) throws IOException {
        if (isSearch(url)) {
            return SiteHttp.postForm(url);
        }
        try {
            return Dle.fetch(AudioknigiPro.fetchUrl(url));
        } catch (HttpStatusException e) {
            if (e.getStatusCode() == 404 && emptyOn404(url)) {
                return Document.createShell(url);
            }
            throw e;
        }
    }

    @Override
    public ProviderPage<BookPOJO> listing(Document page, String url, int pageNo) {
        return parseListing(page);
    }

    /** The indexes are single pages without a slash after them; every listing else pages DLE's way. */
    @Override
    public String pageUrl(String url, int page) {
        return HttpUrl.get(url).encodedPath().matches("/(genres|authors|ispolniteli|cikly)") ? url
                : Dle.pageUrl(url, page);
    }

    /** The index and the genres; person and series pages are not offered sorted. */
    @Override
    public List<Sort.Entry> sorts(String url) {
        return "main".equals(AudioknigiPro.sortKey(url)) || genreOf(url) != null ? Dle.MENU
                : Collections.<Sort.Entry>emptyList();
    }

    /** The index in the item's sort; "new" is the bare index, which {@link #fetch} POSTs in the date sort. */
    @Override
    public String sortUrl(Sort sort, String listingUrl) {
        Dle.Order order = Dle.Order.of(sort);
        return order == null ? null : order == Dle.Order.DATE ? INDEX : AudioknigiPro.sorted(INDEX, order);
    }

    /** The root of a genre listing, plain or sorted; null elsewhere. */
    @Override
    public String genreOf(String url) {
        String root = Dle.pageUrl(HttpUrl.get(url).newBuilder().query(null).build().toString(), 1);
        return HttpUrl.get(root).encodedPath().matches("/zhanr/[^/]+/") ? root : null;
    }

    @Override
    public String inGenre(String siteWide, String genre) {
        Dle.Order order = Dle.Order.ofField(HttpUrl.get(siteWide).queryParameter(Dle.SORT_PARAM));
        return order == null || order == Dle.Order.DATE ? genre : AudioknigiPro.sorted(genre, order);
    }

    @Override
    public ProviderPage<GenrePOJO> genres(Document page, String url, int pageNo) {
        return parseIndex(page);
    }

    @Override
    public ProviderPage<GenrePOJO> people(Document page, String url, int pageNo, boolean performers) {
        return parseIndex(page);
    }

    @Override
    public String searchUrl(SearchUrls.Category category, String query, int page) {
        return category == SearchUrls.Category.BOOKS ? Dle.searchUrl(SERVER, query, page) : null;
    }

    @Override
    public DescriptionPOJO description(Document page, String bookUrl) {
        return parseDescription(page);
    }

    /** A book opened by its link: its page's description plus the card's rating. */
    @Override
    public BookPOJO book(Document page, String bookUrl) {
        BookPOJO book = BookPOJO.of(parseDescription(page), bookUrl);
        setRating(book, rating(page));
        return book;
    }

    @Override
    public ArrayList<BookPOJO> recommendations(Document page, String bookUrl) {
        return parseRecommendations(page, bookUrl);
    }

    /** The signed playlist, an XHR with the book page as Referer. */
    @Override
    public ArrayList<AudioPOJO> tracks(Document page, String base) throws IOException {
        return parseTracks(SiteHttp.body(SiteHttp.xhr(playlistUrl(page, base), base)), title(page));
    }

    @Override
    public ArrayList<ComentsPOJO> comments(String bookUrl, BooleanSupplier cancelled) throws Exception {
        return parseComments(BookPages.get(bookUrl, this::fetch));
    }

    private static boolean isSearch(String url) {
        return "search".equals(HttpUrl.get(url).queryParameter("do"));
    }

    /** An author, reader or series page, which answers 404 with its empty list ("Кол-во аудиокниг: 0") when it has none. */
    static boolean emptyOn404(String url) {
        return HttpUrl.get(url).encodedPath().matches("/(author|read|cikl)/[^/]+/(page/\\d+/)?");
    }

    // ---------------------------------------------------------------- listings

    /** Index, sorted, genre, person, series and search pages; also the other-source search hits. */
    public static ProviderPage<BookPOJO> parseListing(Document doc) {
        Elements cards = doc.select("div.short-nm");
        return ProviderPage.of(Html.cards(cards, AudioknigiTop::card),
                doc.selectFirst(".bottom-navi a:has(.ion-ios-arrow-forward)") != null, cards.size());
    }

    @Nullable
    private static BookPOJO card(Element card) {
        Element title = card.selectFirst("a.name-kniga");
        if (title == null) {
            return null;
        }
        BookPOJO book = new BookPOJO();
        book.setUrl(title.absUrl("href"));
        book.setName(Parser.unescapeEntities(title.text().trim(), false));
        book.setPhoto(Html.imgSrc(card.selectFirst(".short-img img")));
        Element author = card.selectFirst("span.author");
        book.setAutor(names(author));
        book.setUrlAutor(href(author, "a"));
        Element reader = card.selectFirst("span.reader");
        book.setArtist(names(reader));
        book.setUrlArtist(href(reader, "a"));
        book.setGenre(text(card, "span.zhanr"));
        book.setUrlGenre(href(card, "span.zhanr a"));
        book.setSeries(text(card, "span.cikl a"));
        book.setUrlSeries(href(card, "span.cikl a"));
        book.setTime(text(card, "span.time"));
        setRating(book, text(card, ".short-img .card__rate"));
        return book;
    }

    /** The rows of {@link #GENRES}, {@link #AUTHORS} and {@link #READERS} with their book counts; a row without a letter is left out. */
    public static ProviderPage<GenrePOJO> parseIndex(Document doc) {
        return ProviderPage.of(Html.rows(doc.select("ul.fields-list > li"), li -> {
            Element a = li.selectFirst("a");
            String name = text(a);
            if (a == null || !name.matches(".*\\p{L}.*")) {
                return null;
            }
            GenrePOJO row = new GenrePOJO(name, a.absUrl("href"));
            row.setDescription(text(li, ".field_counter"));
            return row;
        }), false);
    }

    // ---------------------------------------------------------------- book page

    public static DescriptionPOJO parseDescription(Document doc) {
        DescriptionPOJO description = new DescriptionPOJO();
        description.setTitle(title(doc));
        description.setPoster(Html.imgSrc(doc.selectFirst(".card--details .card__cover img")));
        Element row = info(doc, "Автор");
        description.setAutor(names(row));
        description.setAutorUrl(href(row, "a"));
        row = info(doc, "Исполнител");
        description.setArtist(names(row));
        description.setArtistUrl(href(row, "a"));
        row = info(doc, "Жанр");
        description.setGenre(afterLabel(row));
        description.setGenreUrl(href(row, "a"));
        row = info(doc, "Цикл");
        description.setSeries(text(row, "a"));
        description.setSeriesUrl(href(row, "a"));
        description.setTime(afterLabel(info(doc, "Длительность")));
        description.setReiting(rating(doc));
        description.setLike(text(doc, "[id^=likes-id-]"));
        description.setDisLike(text(doc, "[id^=dislikes-id-]"));
        description.setDescription(Html.paragraphs(doc.selectFirst(".card__description")).replace(String.valueOf((char) 0xFEFF), ""));
        return description;
    }

    /**
     * "Другие аудиокниги автора &lt;name&gt;", once each and without this book; none when the page lacks the block. The
     * "Послушайте эти аудиокниги" block is the site's six newest books, the same on every page: not related.
     */
    public static ArrayList<BookPOJO> parseRecommendations(Document doc, String bookUrl) {
        Element heading = doc.selectFirst("h2:containsOwn(Другие аудиокниги автора)");
        Element block = heading == null ? null : heading.closest(".row--grid");
        ArrayList<BookPOJO> books = new ArrayList<>();
        if (block == null) {
            return books;
        }
        Set<String> seen = new HashSet<>(Collections.singleton(bookUrl));
        for (BookPOJO book : Html.cards(block.select("div.card"), card -> {
            Element a = card.selectFirst(".card__title a");
            if (a == null) {
                return null;
            }
            BookPOJO item = new BookPOJO();
            item.setUrl(a.absUrl("href"));
            item.setName(a.text().trim());
            item.setPhoto(Html.imgSrc(card.selectFirst(".card__cover img")));
            setRating(item, text(card, ".card__rate"));
            return item;
        })) {
            if (seen.add(book.getUrl())) {
                books.add(book);
            }
        }
        return books;
    }

    /** All comments are on the book page, oldest first and flat: a reply quotes. */
    public static ArrayList<ComentsPOJO> parseComments(Document doc) {
        ArrayList<ComentsPOJO> comments = new ArrayList<>();
        for (Element item : doc.select("#dle-comments-list .comments__item")) {
            ComentsPOJO comment = new ComentsPOJO();
            comment.setName(text(item, ".comments__name"));
            comment.setDate(text(item, ".comments__time"));
            Element body = item.selectFirst(".comments__text");
            Element quote = body == null ? null : body.selectFirst(".quote");
            if (quote != null) {
                comment.setQuoteText(quote.text().trim());
                comment.setQuoteName(text(body, ".title_quote").replace("Цитата:", "").trim());
                Element own = body.clone();
                own.select(".quote_block").remove();
                comment.setText(own.text().trim());
            } else {
                comment.setText(text(body));
            }
            Element avatar = item.selectFirst("img.comments__avatar");
            comment.setImage(avatar == null ? "" : avatar.absUrl("src"));
            String likes = text(item, "[id^=comments-likes-id-]");
            comment.setReting(likes.equals("0") ? "" : likes);
            if (!comment.isEmty()) {
                comments.add(comment);
            }
        }
        return comments;
    }

    // ---------------------------------------------------------------- chapters

    /**
     * The playlist URL of a book page ({@code data-playlist-endpoint}). A page without one that carries a takedown
     * notice is a {@link BookRemovedException}, any other an {@link IllegalStateException}.
     */
    public static String playlistUrl(Document doc, String bookUrl) {
        Element player = doc.selectFirst("[data-playlist-endpoint]");
        if (player != null) {
            return player.absUrl("data-playlist-endpoint");
        }
        if (BookRemovedException.isNotice(doc.text())) {
            throw new BookRemovedException(bookUrl);
        }
        throw new IllegalStateException("audioknigi.top page has no playlist: " + bookUrl);
    }

    /**
     * The chapters of a playlist answer, one MP3 each without durations. A signed file's title is its number and the
     * file's slug ("01 master-run-kniga-12-01"), which says no more than the number: it becomes "Глава 1".
     */
    public static ArrayList<AudioPOJO> parseTracks(String json, String bookName) {
        String playlist = Json.string(Json.object(json), "playlist")
                .replaceAll("\"title\"\\s*:\\s*\"(\\d+)\\s+[a-z0-9_-]+\"", "\"title\":\"$1\"");
        return AudioknigiPro.parsePlaylist(playlist, bookName);
    }

    // ---------------------------------------------------------------- helpers

    /** The "Название:" row, which the heading of older books follows with " - <author>". */
    private static String title(Document doc) {
        String title = afterLabel(info(doc, "Название"));
        return title.isEmpty() ? text(doc, "h1") : title;
    }

    /** The "Label: value" row of the book page; null when absent. */
    @Nullable
    private static Element info(Document doc, String label) {
        return labelled(doc, ".card__meta li", label);
    }

    /** The book's average vote, 0 to 10, on its cover. */
    private static String rating(Document doc) {
        return text(doc, ".card--details .card__rate");
    }

    /** A card without votes ("0") keeps the "0" the list hides. */
    private static void setRating(BookPOJO book, String rating) {
        if (!rating.isEmpty()) {
            book.setReting(rating);
        }
    }

    /**
     * The names of the links under {@code scope}: one link may hold several, a line each ("A\nB" is "A, B"); the text
     * after the label when there is no link.
     */
    private static String names(@Nullable Element scope) {
        StringBuilder out = new StringBuilder();
        if (scope != null) {
            for (Element a : scope.select("a")) {
                for (String name : a.wholeText().split("\n")) {
                    if (!name.trim().isEmpty()) {
                        out.append(out.length() == 0 ? "" : ", ").append(name.trim());
                    }
                }
            }
        }
        return out.length() == 0 ? afterLabel(scope) : out.toString();
    }
}
