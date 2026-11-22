package com.fanok.audiobooks.source;

import static com.fanok.audiobooks.source.Html.afterLabel;
import static com.fanok.audiobooks.source.Html.href;
import static com.fanok.audiobooks.source.Html.labelled;
import static com.fanok.audiobooks.source.Html.text;

import androidx.annotation.Nullable;

import com.fanok.audiobooks.pojo.AudioPOJO;
import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.pojo.DescriptionPOJO;
import com.fanok.audiobooks.pojo.GenrePOJO;
import com.fanok.audiobooks.pojo.SeriesPOJO;
import com.fanok.audiobooks.search.PeopleIndex;
import com.fanok.audiobooks.search.ProviderPage;
import com.fanok.audiobooks.search.SearchUrls;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * slushkin.online: DataLife Engine, template "slushkin-online" (probed live, 2026-11-21). A facade of knigavuhe.org's
 * catalog with its own audio hosts: the path id on {@code bookis.shop} (older books {@code bookis.life}) is the
 * knigavuhe book id. Plain HTML, no wall.
 * <ul>
 * <li>index: {@code /}, then {@code /page/N/}; 8 cards a page (cover, title, genres, author, reader, series, duration,
 * annotation), pager {@code .pagination__pages}. No sorts: the page has no sort form and a POSTed
 * {@code dlenewssortby} is ignored.</li>
 * <li>genres: the "Жанры" block of {@link #GENRES}, its 44 top {@code /zhanry/<slug>/} rows (subgenres and the plot,
 * setting and age facets are left out); a genre has the index's page scheme.</li>
 * <li>search: the DLE form answers 307 to {@code /search/<Q>/}, paged {@code /search/<Q>/page/N/} (page 1 too), 10
 * cards; a slash in the query is a 404 there. The search is a SQL {@code LIKE} that refuses a query under 5 characters
 * ("Минимальное количество символов для поиска - 5 символов"), so a shorter one is padded with {@code %}, the
 * wildcard: "Кинг%" finds what "Кинг" would.</li>
 * <li>author, reader, series: {@code /xfsearch/avtor|ozv|cikl/<slug>/}, the index's page scheme; there is no index of
 * all authors or readers, the drawer opens the app's built-in lists.</li>
 * <li>book {@code /<id>-<slug>.html}: the "Автор/Чтец/Цикл/Время" list, "Жанры", the series' books, "Похожие",
 * likes and dislikes. Playerjs with an inline {@code file} list of unsigned MP3s that need no Referer. No comments
 * seen.</li>
 * </ul>
 */
public final class Slushkin implements SourceParser {

    public static final String SERVER = "https://slushkin.online";
    public static final String INDEX = SERVER + "/";
    public static final String GENRES = SERVER + "/ganr.html";
    public static final String AUTHORS = SERVER + PeopleIndex.AUTHORS;
    public static final String READERS = SERVER + PeopleIndex.READERS;

    /** The audio hosts, serving this site alone. */
    private static final Set<String> MEDIA_HOSTS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("bookis.shop", "bookis.life")));
    private static final Pattern TOP_GENRE = Pattern.compile("^" + Pattern.quote(SERVER) + "/zhanry/[^/]+/$");
    private static final Pattern NUMBER = Pattern.compile("\\((\\d+)\\)");
    /** The server's minimum search length. */
    private static final int MIN_QUERY = 5;

    @Override
    public Set<String> hosts() {
        return MEDIA_HOSTS;
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
    public ProviderPage<GenrePOJO> genres(Document page, String url, int pageNo) {
        return parseGenres(page);
    }

    @Override
    public String searchUrl(SearchUrls.Category category, String query, int page) {
        if (category != SearchUrls.Category.BOOKS) {
            return null;
        }
        StringBuilder q = new StringBuilder(query.replace('/', ' ').trim());
        for (int n = q.codePointCount(0, q.length()); n < MIN_QUERY; n++) {
            q.append('%');
        }
        return SERVER + "/search/" + SearchUrls.segment(q.toString()) + "/page/" + page + "/";
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
        return Players.inlinePlayerjs(page, base, title(page));
    }

    @Override
    public ArrayList<SeriesPOJO> series(Document page, String bookUrl) {
        return parseSeries(page);
    }

    // ---------------------------------------------------------------- listings

    /** Index, genre, xfsearch and search pages. */
    public static ProviderPage<BookPOJO> parseListing(Document doc, int page) {
        Elements raw = doc.select("article.short");
        int last = Html.lastPage(doc.select(".pagination__pages a, .pagination__pages span"));
        return ProviderPage.of(Html.cards(raw, Slushkin::card), last > page, raw.size());
    }

    @Nullable
    private static BookPOJO card(Element card) {
        Element title = card.selectFirst(".short__title a");
        if (title == null) {
            return null;
        }
        BookPOJO book = new BookPOJO();
        book.setUrl(title.absUrl("href"));
        book.setName(title.text().trim());
        book.setPhoto(Html.imgSrc(card.selectFirst(".short__img img")));
        Element genres = card.selectFirst(".short__category");
        book.setGenre(names(genres));
        book.setUrlGenre(href(genres, "a"));
        Element author = meta(card, "fa-user");
        book.setAutor(text(author));
        book.setUrlAutor(href(author, "a"));
        Element reader = meta(card, "fa-microphone");
        book.setArtist(text(reader));
        book.setUrlArtist(href(reader, "a"));
        Element series = meta(card, "fa-list-alt");
        if (series != null) {
            book.setSeries(text(series, "a"));
            book.setUrlSeries(href(series, "a"));
        }
        book.setTime(text(meta(card, "fa-clock-o")));
        book.setDesc(text(card, ".short__text"));
        return book;
    }

    /** The card's meta row with the icon {@code icon}; null without one. */
    @Nullable
    private static Element meta(Element card, String icon) {
        Element i = card.selectFirst(".short__meta-item > span." + icon);
        return i == null ? null : i.parent();
    }

    // ---------------------------------------------------------------- genres

    /** The top rows of the "Жанры" block of {@link #GENRES}. */
    public static ProviderPage<GenrePOJO> parseGenres(Document doc) {
        Elements links = doc.select("h2:containsOwn(Жанры) + div.catmenu a[href]");
        return ProviderPage.of(Html.rows(links, a -> {
            String url = a.absUrl("href");
            return TOP_GENRE.matcher(url).matches() ? new GenrePOJO(a.text().trim(), url) : null;
        }), false);
    }

    // ---------------------------------------------------------------- book page

    public static DescriptionPOJO parseDescription(Document doc) {
        DescriptionPOJO d = new DescriptionPOJO();
        d.setTitle(title(doc));
        d.setPoster(Html.imgSrc(doc.selectFirst(".image-books img")));
        Element author = labelled(doc, ".list-books li", "Автор");
        d.setAutor(afterLabel(author));
        d.setAutorUrl(href(author, "a"));
        Element reader = labelled(doc, ".list-books li", "Чтец");
        d.setArtist(afterLabel(reader));
        d.setArtistUrl(href(reader, "a"));
        Element series = labelled(doc, ".list-books li", "Цикл");
        if (series != null) {
            d.setSeries(text(series, "a"));
            d.setSeriesUrl(href(series, "a"));
        }
        d.setTime(afterLabel(labelled(doc, ".list-books li", "Время")));
        Element genres = labelled(doc, ".list-books li", "Жанры");
        d.setGenre(names(genres));
        d.setGenreUrl(href(genres, "a"));
        d.setLike(text(doc, "[data-likes-id]"));
        d.setDisLike(text(doc, "[data-dislikes-id]"));
        d.setDescription(Html.paragraphs(doc.selectFirst("div[itemprop=description]")));
        return d;
    }

    /** "Похожие:". */
    public static ArrayList<BookPOJO> parseRecommendations(Document doc) {
        return Html.cards(doc.select(".sect a.rel"), a -> {
            BookPOJO book = new BookPOJO();
            book.setUrl(a.absUrl("href"));
            book.setName(text(a, ".rel__title"));
            book.setPhoto(Html.imgSrc(a.selectFirst("img")));
            return book;
        });
    }

    /** "Аудиокниги из цикла": the series' books, newest first as the site lists them, the open book included. */
    public static ArrayList<SeriesPOJO> parseSeries(Document doc) {
        ArrayList<SeriesPOJO> out = new ArrayList<>();
        for (Element li : doc.select("h2:contains(Аудиокниги из цикла) + .container_books li")) {
            SeriesPOJO item = Html.seriesItem(li.selectFirst("a[href]"), null);
            if (item == null) {
                continue;
            }
            Matcher m = NUMBER.matcher(li.ownText());
            if (m.find()) {
                item.setNumber(m.group(1));
            }
            out.add(item);
        }
        return out;
    }

    // ---------------------------------------------------------------- helpers

    /** The heading's title without the " - Author" that follows it. */
    private static String title(Document doc) {
        return text(doc, "h1.page__title [itemprop=name]");
    }

    /** The genre links' names, "Ужасы, Мистика"; "" without the row. */
    private static String names(@Nullable Element scope) {
        if (scope == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (Element a : scope.select("a")) {
            out.append(out.length() == 0 ? "" : ", ").append(a.text().trim());
        }
        return out.toString();
    }
}
