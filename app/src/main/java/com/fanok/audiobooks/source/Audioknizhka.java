package com.fanok.audiobooks.source;

import static com.fanok.audiobooks.source.Html.afterLabel;
import static com.fanok.audiobooks.source.Html.href;
import static com.fanok.audiobooks.source.Html.labelled;
import static com.fanok.audiobooks.source.Html.text;

import androidx.annotation.Nullable;

import com.fanok.audiobooks.BookRemovedException;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;

/**
 * audioknizhka.org: DataLife Engine, template "coolserial-new", Bookish's family (probed live, 2026-11-21). Plain
 * HTML, no wall, no Referer or cookies needed; 10 {@code div.short-item} cards a page, the same on every listing and
 * the search.
 * <ul>
 * <li>index {@code /}, then {@code /page/N/} (last ~3799); a genre {@code /<slug>/}, an author or series
 * {@code /xfsearch/author|cycle/<name>/}, all with the index's page scheme. Cards: "Title - Author", author linked,
 * reader plain text (not linked, so no readers anywhere), genres as plain text ("Роман и проза / Новый год"), time,
 * series, rating. The sidebar menu lists ~780 categories with their counts, many of one book; the empty ones, the
 * repeated ones and book titles cut into categories are left out (~750 stay). No index of authors (the app's built-in
 * list).</li>
 * <li>sorts: none on the server: the DLE sort POST is ignored and the LazyDev filter ({@code /filters.html},
 * {@code /f/sort=...}) is broken (404). The one ranking is {@link #TOP} ("Топ 50", one page of title-and-cover
 * cards), offered as POPULAR next to the index. No periods.</li>
 * <li>search: {@code /index.php?do=search&subaction=search&story=Q&search_start=N}, GET, newest first, with a pager.</li>
 * <li>book {@code /<genre>/<id>-<slug>.html}: h1 "Title - Author - <a>Author</a>" (old posts "Title - <a>Author</a>",
 * co-authors "Title - <a>A1</a>, <a>A2</a>");
 * "Похожие книги" recommendations. Chapters: an inline Playerjs {@code file:[{title, file}]} of archive.org MP3s
 * ({@code /download/...}, 302 to {@code ia*.us.archive.org}, no headers needed), no durations; a few posts point at
 * a dead host instead ({@code m4.audioknigi.xyz}, no DNS record on 2026-11-21: the site's own player fails too). New books have one
 * whole-book MP3; old ones a single-quoted list with HTML-entity titles ({@code &#1044;...}). A taken-down book keeps
 * its page with an empty {@code .fplayer .video-box} (several of the Top-50).</li>
 * </ul>
 */
public final class Audioknizhka implements SourceParser {

    public static final String SERVER = "https://audioknizhka.org";
    public static final String INDEX = SERVER + "/";
    /** Any page carries the genre menu; the index is the one the catalog already loads. */
    public static final String GENRES = SERVER + "/";
    public static final String AUTHORS = SERVER + PeopleIndex.AUTHORS;
    public static final String READERS = null;
    /** "Топ 50": the site's only ranking, one page. */
    public static final String TOP = SERVER + "/top.html";

    /** Bound of the pages read for a book's series tab. */
    static final int MAX_SERIES_PAGES = 10;

    private static final List<Sort.Entry> MENU = Sort.menu(Sort.NEW, Sort.POPULAR);
    private static final Pattern INDEX_PATH = Pattern.compile("^/(page/\\d+/?)?$");
    private static final String CARDS = "#dle-content div.short-item, #dle-content div.popular-item";
    /** The "Автор:", "Читает:", "Жанр:", "Время:", "Серия:" rows of a card or book page. */
    private static final String ROWS = "ul.short-list li";

    // ---------------------------------------------------------------- SourceParser

    @Override
    public ProviderPage<BookPOJO> listing(Document page, String url, int pageNo) {
        return parseListing(page, pageNo);
    }

    /** The top is one page; the rest page as DLE does. */
    @Override
    public String pageUrl(String url, int page) {
        return TOP.equals(url) ? url : Dle.pageUrl(url, page);
    }

    /** The index and the top offer each other; nothing else sorts. */
    @Override
    public List<Sort.Entry> sorts(String url) {
        return isIndex(url) || TOP.equals(url) ? MENU : Collections.<Sort.Entry>emptyList();
    }

    @Override
    public String sortUrl(Sort sort, String listingUrl) {
        switch (sort) {
            case NEW:
                return INDEX;
            case POPULAR:
                return TOP;
            default:
                return null;
        }
    }

    /** The top's cards are a title and a cover. */
    @Override
    public boolean listsDuration(String url) {
        return !TOP.equals(url);
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
    public DescriptionPOJO description(Document page, String bookUrl) {
        return parseDescription(page);
    }

    @Override
    public ArrayList<BookPOJO> recommendations(Document page, String bookUrl) {
        return Bookish.parseRecommendations(page);
    }

    @Override
    public ArrayList<AudioPOJO> tracks(Document page, String base) {
        return parseTracks(page, base, parseDescription(page).getTitle());
    }

    /** The series listing linked from the book page, all its pages, in its order. */
    @Override
    public ArrayList<SeriesPOJO> series(Document page, String bookUrl) throws IOException {
        ArrayList<SeriesPOJO> result = new ArrayList<>();
        String series = href(labelled(page, ROWS, "Серия"), "a");
        for (int no = 1; !series.isEmpty() && no <= MAX_SERIES_PAGES; no++) {
            Document doc = fetch(pageUrl(series, no));
            result.addAll(parseSeries(doc));
            if (lastPage(doc) <= no) {
                break;
            }
        }
        return result;
    }

    private static boolean isIndex(String url) {
        HttpUrl parsed = HttpUrl.parse(url);
        return parsed != null && parsed.host().equals(HttpUrl.get(SERVER).host())
                && INDEX_PATH.matcher(parsed.encodedPath()).matches();
    }

    // ---------------------------------------------------------------- listings

    /** Index, top, genre, author or series pages and the search. */
    public static ProviderPage<BookPOJO> parseListing(Document doc, int page) {
        Elements raw = doc.select(CARDS);
        return ProviderPage.of(Html.cards(raw, Audioknizhka::card), lastPage(doc) > page, raw.size());
    }

    private static int lastPage(Document doc) {
        return Html.lastPage(doc.select(".bottom-nav .navigation a, .bottom-nav .navigation span"));
    }

    @Nullable
    private static BookPOJO card(Element card) {
        Element title = card.selectFirst("a.short-title, a.popular-item-img");
        if (title == null) {
            return null;
        }
        BookPOJO book = new BookPOJO();
        book.setUrl(title.absUrl("href"));
        book.setPhoto(Html.imgSrc(card.selectFirst("img")));
        Element author = labelled(card, ROWS, "Автор");
        String authors = afterLabel(author);
        book.setAutor(authors);
        book.setUrlAutor(href(author, "a"));
        book.setArtist(afterLabel(labelled(card, ROWS, "Читает")));
        book.setGenre(afterLabel(labelled(card, ROWS, "Жанр")));
        Element series = labelled(card, ROWS, "Серия");
        book.setSeries(text(series, "a"));
        book.setUrlSeries(href(series, "a"));
        book.setTime(Bookish.duration(card));
        String name = title.hasClass("short-title") ? title.text() : text(title, ".popular-item-title");
        book.setName(Bookish.title(name, authors));
        String percent = text(card, "li.current-rating");
        if (percent.matches("\\d+") && !percent.equals("0")) {
            book.setReting(String.format(Locale.ROOT, "%.1f", Integer.parseInt(percent) / 20f));
        }
        return book;
    }

    /** The cards of a series listing, in its order (no numbers on this site). */
    public static ArrayList<SeriesPOJO> parseSeries(Document doc) {
        ArrayList<SeriesPOJO> result = new ArrayList<>();
        for (BookPOJO book : Html.cards(doc.select(CARDS), Audioknizhka::card)) {
            SeriesPOJO item = new SeriesPOJO();
            item.setUrl(book.getUrl());
            item.setName(book.getName());
            item.setPhoto(book.getPhoto());
            result.add(item);
        }
        return result;
    }

    // ---------------------------------------------------------------- genres

    /**
     * The sidebar's genre menu with each count. Left out: empty categories, repeats of a URL (the menu lists one
     * category up to 20 times) and book titles cut at their opening quote into categories ("Управление «Крест").
     */
    public static ProviderPage<GenrePOJO> parseGenres(Document doc) {
        Set<String> seen = new HashSet<>();
        ArrayList<GenrePOJO> genres = Html.rows(doc.select(".side-nav ul.nav-menu[data-showitems] li a"), a -> {
            String count = text(a, "span").replaceAll("\\D", "");
            String name = a.ownText().trim();
            if (count.equals("0") || name.contains("«") && !name.contains("»") || !seen.add(a.absUrl("href"))) {
                return null;
            }
            GenrePOJO genre = new GenrePOJO(name, a.absUrl("href"));
            if (!count.isEmpty()) {
                genre.setReting(Integer.parseInt(count));
            }
            return genre;
        });
        return ProviderPage.of(genres, false);
    }

    // ---------------------------------------------------------------- book page

    public static DescriptionPOJO parseDescription(Document doc) {
        DescriptionPOJO description = new DescriptionPOJO();
        Element article = doc.selectFirst("article.article");
        if (article == null) {
            return description;
        }
        Element author = labelled(article, ROWS, "Автор");
        description.setAutor(afterLabel(author));
        description.setAutorUrl(href(author, "a"));
        // "Title - Author - <a>Author</a>" or "Title - <a>A1</a>, <a>A2</a>": the h1's own text without the separators
        Element h1 = article.selectFirst("h1");
        String title = h1 == null ? "" : h1.ownText().replaceFirst("[\\s\\-–,]+$", "");
        description.setTitle(Bookish.title(title.isEmpty() ? text(h1) : title, description.getAutor()));
        description.setPoster(Html.imgSrc(article.selectFirst(".fimg img")));
        description.setArtist(afterLabel(labelled(article, ROWS, "Читает")));
        Element genre = labelled(article, ROWS, "Жанр");
        description.setGenre(afterLabel(genre));
        description.setGenreUrl(href(genre, "a"));
        Element series = labelled(article, ROWS, "Серия");
        if (series != null && series.selectFirst("a") != null) {
            description.setSeries(text(series, "a"));
            description.setSeriesUrl(href(series, "a"));
        }
        description.setTime(Bookish.duration(article));
        String percent = text(article, "li.current-rating");
        String votes = text(article, "[data-vote-num-id]");
        if (percent.matches("\\d+") && !percent.equals("0") && votes.matches("\\d+") && !votes.equals("0")) {
            description.setReiting(BookPOJO.formatRating(Integer.parseInt(percent) / 20f, votes));
        }
        description.setDescription(Html.paragraphs(article.selectFirst("div.ftext.full-text")));
        return description;
    }

    // ---------------------------------------------------------------- chapters

    /**
     * The inline Playerjs list, double- or single-quoted (Gson reads both); old lists spell their titles in HTML
     * entities. An empty player (the book taken down) is a {@link BookRemovedException}, any other page without the
     * list an {@link IllegalStateException}.
     */
    public static ArrayList<AudioPOJO> parseTracks(Document doc, String base, String bookName) {
        Element player = doc.selectFirst(".fplayer .video-box");
        if (player != null && player.children().isEmpty() && player.text().isEmpty()) {
            throw new BookRemovedException(base);
        }
        ArrayList<AudioPOJO> tracks = Players.inlinePlayerjs(doc, base, bookName);
        for (AudioPOJO track : tracks) {
            track.setName(Parser.unescapeEntities(track.getName(), false));
        }
        return tracks;
    }
}
