package com.fanok.audiobooks.source;

import static com.fanok.audiobooks.source.Html.afterLabel;
import static com.fanok.audiobooks.source.Html.labelled;
import static com.fanok.audiobooks.source.Html.text;

import androidx.annotation.Nullable;

import com.fanok.audiobooks.BookRemovedException;
import com.fanok.audiobooks.pojo.AudioPOJO;
import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.pojo.DescriptionPOJO;
import com.fanok.audiobooks.pojo.GenrePOJO;
import com.fanok.audiobooks.search.ProviderPage;
import com.fanok.audiobooks.search.SearchUrls;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * aukniga.com (probed live, 2026-11-21) and its mirror aubook.org ({@link Aubook}): one Symfony engine, the same
 * slugs, other route names. This class is the engine; a site differs in its catalog, search and popular URLs only, the
 * pages parse alike whatever their routes. Plain GETs, no wall, no cookies, no Referer.
 * <ul>
 * <li>listings, newest first, 16 {@code .book-card} cards (cover, title, authors, annotation; no time, reader or
 * genre), paged with {@code /p/N} up to the pager's {@code rel=next}: the catalog {@code /bs}, a genre
 * {@code /ct/<slug>}, an author {@code /a/<slug>}, a reader {@code /va/<slug>}, a series {@code /s/<slug>} (aubook
 * {@code /serie/<slug>}; linked from the book's «Цикл» row, no index of them). No server sorts ({@code ?sort=} is
 * ignored, {@code /popular} is 404); the home page {@code /} is one list of 32 «Самое популярное», the popular
 * sort.</li>
 * <li>genres {@code /cts}, 57 on one page; authors {@code /as}, 50 a page ({@code /as/p/N}). No readers' index
 * ({@code /va}, {@code /vas} are 404; the sitemap lists reader pages, the book pages link them), no comments.</li>
 * <li>search {@code /find?q=Q}: one page of at most 24 books and the matching authors; {@code page} is ignored,
 * {@code /find/p/2} is 404.</li>
 * <li>book {@code /b/<slug>}: authors, readers, genre, series («Цикл») and time in {@code table.book-meta}, the
 * annotation, then other books of the author, of the genre and «Вам также понравится». Chapters: {@code var
 * auPlayerSettings = {'data': '#2…'}}, Playerjs {@code #2} whose base64 carries the player's keys ({@link #KEYS}) as
 * {@code "//" + base64(key)} pieces; it decodes to the player options, whose {@code file} lists one MP3 a chapter,
 * mostly on archive.org, some on other hosts (audiokniga.space: its front page reads «Сайт временно отключен» and every
 * MP3 path answers 403 here with or without a Referer, so those books do not play from here). A book the site
 * sells through LitRes plays the {@code litres.ru/get_mp3_trial} fragment instead: reported as taken down. That is
 * most of the catalog (2026-11-21: 16 of the 20 newest books, 6 of 10 from {@code /bs/p/1000} and the last page), and
 * nothing short of the book page tells one (its «Купить» {@code /godep/} button and
 * {@code .single-book__fragment-disclaimer}): the cards are alike, the site has no filter or sort, so the listings
 * keep them.</li>
 * </ul>
 */
public class Aukniga implements SourceParser {

    public static final String SERVER = "https://aukniga.com";
    public static final String INDEX = SERVER + "/bs";
    public static final String GENRES = SERVER + "/cts";
    public static final String AUTHORS = SERVER + "/as";
    public static final String READERS = null;
    /** The home page: one list of 32 «Самое популярное». */
    public static final String POPULAR = SERVER + "/";

    /**
     * The separator keys {@code bk0..bk2} of the sites' Playerjs build ({@code playerjs.3f0b9c32.js}, the same on both
     * sites; it sets no {@code bk3}, {@code bk4}) as {@code "//" + base64(key)}.
     */
    static final String[] KEYS = {"//QEAtKio/PyMqJg==", "//KiZAI0AjXygqKCYk", "//QCMqJiRAOjojKCom"};
    private static final Pattern PLAYER = Pattern.compile("auPlayerSettings\\s*=\\s*\\{\\s*'data'\\s*:\\s*'([^']+)'");

    private final String index;
    private final String search;
    @Nullable
    private final String popular;
    private final List<Sort.Entry> menu;

    public Aukniga() {
        this(INDEX, SERVER + "/find", POPULAR);
    }

    /** A site of the engine: its catalog, its search page and its popular list (null when it has none). */
    Aukniga(String index, String search, @Nullable String popular) {
        this.index = index;
        this.search = search;
        this.popular = popular;
        menu = popular == null ? Collections.<Sort.Entry>emptyList() : Sort.menu(Sort.NEW, Sort.POPULAR);
    }

    // ---------------------------------------------------------------- SourceParser

    @Override
    public ProviderPage<BookPOJO> listing(Document page, String url, int pageNo) {
        return parseListing(page);
    }

    /** Page N of every listing and of the authors' index is {@code <listing>/p/N}. */
    @Override
    public String pageUrl(String url, int page) {
        return page <= 1 ? url : url.replaceFirst("/$", "") + "/p/" + page;
    }

    /** The catalog and the popular list; genre, author and reader listings are newest first only. */
    @Override
    public List<Sort.Entry> sorts(String url) {
        return url.equals(index) || url.equals(popular) ? menu : Collections.<Sort.Entry>emptyList();
    }

    @Override
    public String sortUrl(Sort sort, String listingUrl) {
        switch (sort) {
            case NEW: return index;
            case POPULAR: return popular;
            default: return null;
        }
    }

    @Override
    public boolean listsDuration(String url) {
        return false;
    }

    @Override
    public ProviderPage<GenrePOJO> genres(Document page, String url, int pageNo) {
        return ProviderPage.of(parseGenres(page), false);
    }

    @Override
    public ProviderPage<GenrePOJO> people(Document page, String url, int pageNo, boolean performers) {
        return parsePeople(page);
    }

    /** Books and authors share one search page; readers are not searched. */
    @Override
    public String searchUrl(SearchUrls.Category category, String query, int page) {
        return category == SearchUrls.Category.PERFORMERS
                ? null : search + "?q=" + SearchUrls.enc(query) + "&page=" + page;
    }

    @Override
    public ProviderPage<GenrePOJO> searchPeople(Document page, int pageNo, boolean performers) {
        return performers ? ProviderPage.<GenrePOJO>empty() : parsePeople(page);
    }

    @Override
    public DescriptionPOJO description(Document page, String bookUrl) {
        return parseDescription(page);
    }

    @Override
    public ArrayList<BookPOJO> recommendations(Document page, String bookUrl) {
        return parseRecommendations(page);
    }

    @Override
    public ArrayList<AudioPOJO> tracks(Document page, String base) {
        return parseTracks(page, base);
    }

    // ---------------------------------------------------------------- pure parsers

    /** Any listing: the catalog, the popular list, a genre, author or reader page, the search. */
    public static ProviderPage<BookPOJO> parseListing(Document doc) {
        Elements cards = doc.select("div.book-card");
        return ProviderPage.of(Html.cards(cards, Aukniga::card), !doc.select(".pagination a[rel^=next]").isEmpty(),
                cards.size());
    }

    private static BookPOJO card(Element card) {
        BookPOJO book = new BookPOJO();
        Element title = card.selectFirst(".book-card__meta > div > a");
        book.setUrl(title == null ? "" : title.absUrl("href"));
        book.setName(text(title));
        book.setPhoto(Html.imgSrc(card.selectFirst("img.card__img")));
        book.setAutor(names(card, ".book-card__meta > a"));
        book.setUrlAutor(Html.href(card, ".book-card__meta > a"));
        book.setDesc(text(card, ".book-card__description"));
        return book;
    }

    /** The {@code /cts} page: name and listing of each genre. */
    public static ArrayList<GenrePOJO> parseGenres(Document doc) {
        return Html.rows(doc.select("main .h5 > a"), Aukniga::row);
    }

    /** A page of the authors' index, or the authors of a search: name and the person's listing. */
    public static ProviderPage<GenrePOJO> parsePeople(Document doc) {
        Elements rows = doc.select(".list-group > a.list-group-item");
        return ProviderPage.of(Html.rows(rows, Aukniga::row), !doc.select(".pagination a[rel^=next]").isEmpty(),
                rows.size());
    }

    private static GenrePOJO row(Element a) {
        String url = a.absUrl("href");
        return url.isEmpty() ? null : new GenrePOJO(a.text().trim(), url);
    }

    public static DescriptionPOJO parseDescription(Document doc) {
        DescriptionPOJO description = new DescriptionPOJO();
        description.setTitle(text(doc, ".single-book h1"));
        description.setPoster(Html.imgSrc(doc.selectFirst(".single-book__img-wrap img")));
        Element authors = labelled(doc, "table.book-meta tr", "Автор");
        description.setAutor(names(authors, "a"));
        description.setAutorUrl(Html.href(authors, "a"));
        Element readers = labelled(doc, "table.book-meta tr", "Чита");
        description.setArtist(names(readers, "a"));
        description.setArtistUrl(Html.href(readers, "a"));
        Element genres = labelled(doc, "table.book-meta tr", "Жанр");
        description.setGenre(names(genres, "a"));
        description.setGenreUrl(Html.href(genres, "a"));
        Element series = labelled(doc, "table.book-meta tr", "Цикл");
        description.setSeries(names(series, "a"));
        description.setSeriesUrl(Html.href(series, "a"));
        description.setTime(afterLabel(labelled(doc, "table.book-meta tr", "Время")));
        description.setDescription(Html.paragraphs(doc.selectFirst(".single-book__annotation .well")));
        return description;
    }

    /** The cards under the book: other books of its author, popular ones of its genre, «Вам также понравится». */
    public static ArrayList<BookPOJO> parseRecommendations(Document doc) {
        return Html.cards(doc.select("section:not(.single-book) div.book-card"), Aukniga::card);
    }

    /**
     * The chapters of the page's player. A page without one is an {@link IllegalStateException}; a LitRes trial
     * fragment in place of the book is a {@link BookRemovedException}.
     */
    public static ArrayList<AudioPOJO> parseTracks(Document doc, String bookUrl) {
        for (Element script : doc.select("script")) {
            Matcher m = PLAYER.matcher(script.data());
            if (m.find()) {
                String options = Players.playerjsKeyed(m.group(1), KEYS);
                ArrayList<AudioPOJO> tracks = Players.parsePlayerjsPlaylist(
                        Json.arrayOf(Json.object(options), "file").toString(), bookUrl, text(doc, ".single-book h1"));
                if (tracks.get(0).getUrl().contains("litres.ru/")) {
                    throw new BookRemovedException(bookUrl);
                }
                return tracks;
            }
        }
        throw new IllegalStateException("no player on " + bookUrl);
    }

    /** The people or genres a card or a meta row links, joined with ", "; "" for no row. */
    private static String names(@Nullable Element scope, String links) {
        StringBuilder names = new StringBuilder();
        for (Element a : scope == null ? new Elements() : scope.select(links)) {
            names.append(names.length() > 0 ? ", " : "").append(a.text().trim());
        }
        return names.toString();
    }
}
