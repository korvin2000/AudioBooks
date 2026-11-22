package com.fanok.audiobooks.source;

import static com.fanok.audiobooks.source.Html.text;

import androidx.annotation.Nullable;

import com.fanok.audiobooks.BookRemovedException;
import com.fanok.audiobooks.net.SiteHttp;
import com.fanok.audiobooks.pojo.AudioPOJO;
import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.pojo.DescriptionPOJO;
import com.fanok.audiobooks.pojo.GenrePOJO;
import com.fanok.audiobooks.search.ProviderPage;
import com.fanok.audiobooks.search.SearchUrls;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * audiobook24.ru: WordPress (BeTheme, Yoast, wp-postviews), nginx, no wall; the apex redirects to {@code www}
 * (probed live, 2026-11-21). 18 {@code div.post-item} cards a page: cover, "Author. Title", category, excerpt; no
 * reader or time on a card.
 * <ul>
 * <li>catalog {@code /audiobooks/}, then {@code /audiobooks/page/N/} (574 pages); a category {@code /<slug>/} or
 * {@code /?cat=<id>} (the genre list is the "Рубрики" select of every page, ids only; the server 301s a {@code ?cat=}
 * to its slug keeping the query, and {@code /page/N/?cat=<id>} answers itself); an author or series is a WP tag
 * {@code /tag/<slug>/}. Every listing pages on as {@code .../page/N/} before the query; the pager's
 * {@code a.next_page} tells there is more.</li>
 * <li>sorts: date (the default) and {@code ?v_sortby=views&v_orderby=desc} (wp-postviews), on the catalog and in a
 * category, page 2 included. No rating, no periods.</li>
 * <li>search {@code /?s=Q}, {@code /page/N/?s=Q}: the same cards without a cover or category.</li>
 * <li>book {@code /<slug>/} (old posts {@code /audiobooks/<...>/<slug>/13-1-0-N}): the title "Author. Title" in the
 * subheader, tags (the author's among series and topics), one category; "Время звучания: H:MM:SS", "Издательство: X"
 * and "Аудиокнигу ... читает Reader" in the block under the text, or (2021 posts) as the text's last paragraphs with
 * "читает: Reader" (the reader is not linked). Audio: an {@code archive.org/embed/<item>} frame; the
 * chapters are the item's MP3s from {@code archive.org/metadata/<item>} ({@link Bookish#parseArchiveItem}), served by
 * archive.org without a Referer. A book taken down shows "Удалена по требованию правообладателя" and no frame; a
 * download-only book (radio plays among them: "доступно только для скачивания") has no frame either, only
 * {@code /out/} links that 301 to disk.yandex.ru, so no chapters. The
 * item's lengths can disagree with the stated time (043_20261114_202611: 49 original MP3s of 9.66 h against
 * "07:19:59", no derivatives yet): the page's time is shown as printed.</li>
 * </ul>
 */
public final class Audiobook24 implements SourceParser {

    public static final String SERVER = "https://www.audiobook24.ru";
    /** The home page is a magazine without cards: the catalog is the "Аудиокниги" category. */
    public static final String INDEX = SERVER + "/audiobooks/";
    /** Any page carries the "Рубрики" select; the catalog is the one already loaded. */
    public static final String GENRES = INDEX;
    /** No author list: the tags mix authors with series and topics, and cards carry no tag. */
    public static final String AUTHORS = null;
    public static final String READERS = null;

    static final String BY_VIEWS = "v_sortby=views&v_orderby=desc";
    static final String POPULAR = INDEX + "?" + BY_VIEWS;

    private static final List<Sort.Entry> MENU =
            Collections.unmodifiableList(Arrays.asList(Sort.item(Sort.NEW), Sort.item(Sort.POPULAR)));
    private static final Pattern PAGED = Pattern.compile("page/\\d+/?$");
    private static final Pattern CATEGORY = Pattern.compile("^/([a-z0-9_-]+)/$");
    /** The end of the author part of "Author. Title": a dot after a word, not after an initial ("Дж. Р. Р. Толкин"). */
    private static final Pattern AUTHOR_END = Pattern.compile("[\\p{L}\\p{N}]{3}\\.\\s+");
    private static final Pattern ARCHIVE_EMBED = Pattern.compile("archive\\.org/embed/([^?&#\"'/]+)");
    private static final Pattern TIME = Pattern.compile("^Время звучания:\\s*(.+)$");
    private static final Pattern READER = Pattern.compile("^Аудиокнигу .+? чита(?:ет|ют):?\\s+(.+)$");
    private static final Pattern PUBLISHER = Pattern.compile("^Издательство:");

    // ---------------------------------------------------------------- SourceParser

    @Override
    public ProviderPage<BookPOJO> listing(Document page, String url, int pageNo) {
        return parseListing(page);
    }

    /** {@code .../page/N/} before the query ({@code /page/2/?cat=5}, {@code /audiobooks/page/2/?v_sortby=...}). */
    @Override
    public String pageUrl(String url, int page) {
        int q = url.indexOf('?');
        String path = PAGED.matcher(q < 0 ? url : url.substring(0, q)).replaceFirst("");
        String query = q < 0 ? "" : url.substring(q);
        if (!path.endsWith("/")) {
            path += "/";
        }
        return page <= 1 ? path + query : path + "page/" + page + "/" + query;
    }

    /** The catalog and the categories sort; tags (authors, series) and the search do not. */
    @Override
    public List<Sort.Entry> sorts(String url) {
        return isIndex(url) || genreOf(url) != null ? MENU : Collections.<Sort.Entry>emptyList();
    }

    @Override
    public String sortUrl(Sort sort, String listingUrl) {
        String siteWide = sort == Sort.NEW ? INDEX : sort == Sort.POPULAR ? POPULAR : null;
        if (siteWide == null) {
            return null;
        }
        String genre = genreOf(listingUrl);
        boolean same = genre == null ? siteWide.equals(listingUrl) : inGenre(siteWide, genre).equals(listingUrl);
        return same ? listingUrl : siteWide;
    }

    /** Cards carry no time. */
    @Override
    public boolean listsDuration(String url) {
        return false;
    }

    /** The root of a category listing, plain, paged or sorted: {@code /<slug>/} or {@code /?cat=<id>}. */
    @Override
    public String genreOf(String url) {
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null || !ownHost(parsed)) {
            return null;
        }
        String path = PAGED.matcher(parsed.encodedPath()).replaceFirst("");
        if (path.equals("/")) {
            String cat = parsed.queryParameter("cat");
            return cat != null && cat.matches("\\d+") ? SERVER + "/?cat=" + cat : null;
        }
        Matcher m = CATEGORY.matcher(path);
        return m.matches() && !m.group(1).equals("audiobooks") ? SERVER + path : null;
    }

    /** The category in the sort's order: the category itself for "new", its views order for "popular". */
    @Override
    public String inGenre(String siteWide, String genre) {
        if (siteWide.equals(INDEX)) {
            return genre;
        }
        return siteWide.equals(POPULAR) ? genre + (genre.contains("?") ? "&" : "?") + BY_VIEWS : siteWide;
    }

    @Override
    public ProviderPage<GenrePOJO> genres(Document page, String url, int pageNo) {
        return parseGenres(page);
    }

    @Override
    public String searchUrl(SearchUrls.Category category, String query, int page) {
        return category == SearchUrls.Category.BOOKS ? pageUrl(SERVER + "/?s=" + SearchUrls.enc(query), page) : null;
    }

    @Override
    public DescriptionPOJO description(Document page, String bookUrl) {
        return parseDescription(page);
    }

    @Override
    public ArrayList<AudioPOJO> tracks(Document page, String base) throws IOException {
        String item = archiveItem(page, base);
        return Bookish.parseArchiveItem(SiteHttp.body(SiteHttp.connect("https://archive.org/metadata/" + item)),
                parseDescription(page).getTitle(), base);
    }

    // ---------------------------------------------------------------- URLs

    private static boolean ownHost(HttpUrl url) {
        return url.host().replaceFirst("^www\\.", "").equals("audiobook24.ru");
    }

    /** The catalog, any page, plain or sorted. */
    private static boolean isIndex(String url) {
        HttpUrl parsed = HttpUrl.parse(url);
        return parsed != null && ownHost(parsed)
                && PAGED.matcher(parsed.encodedPath()).replaceFirst("").equals("/audiobooks/");
    }

    // ---------------------------------------------------------------- listings

    /** The catalog, a category, a tag, a sort or the search. */
    public static ProviderPage<BookPOJO> parseListing(Document doc) {
        Elements raw = doc.select("div.post-item");
        return ProviderPage.of(Html.cards(raw, Audiobook24::card), doc.selectFirst(".pager a.next_page") != null,
                raw.size());
    }

    @Nullable
    private static BookPOJO card(Element card) {
        Element link = card.selectFirst(".post-title h2 a");
        if (link == null) {
            return null;
        }
        BookPOJO book = new BookPOJO();
        book.setUrl(link.absUrl("href"));
        String[] authorTitle = authorTitle(link.text());
        book.setAutor(authorTitle[0]);
        book.setName(authorTitle[1]);
        book.setPhoto(Html.imgSrc(card.selectFirst(".image_wrapper img.wp-post-image")));
        Element genre = card.selectFirst("ul.post-categories a");
        if (genre != null) {
            book.setGenre(genreName(genre.text()));
            book.setUrlGenre(genre.absUrl("href"));
        }
        // the excerpt opens with the title again
        String excerpt = text(card, ".post-excerpt");
        book.setDesc(excerpt.startsWith(link.text()) ? excerpt.substring(link.text().length()).trim() : excerpt);
        return book;
    }

    /** "Author. Title" as {author, title}; {"", text} when no word ends in ". " ("Сборник «Именем закона»"). */
    static String[] authorTitle(String text) {
        String name = text.trim();
        Matcher m = AUTHOR_END.matcher(name);
        if (!m.find()) {
            return new String[]{"", name};
        }
        return new String[]{name.substring(0, m.start() + 3).trim(), name.substring(m.end()).trim()};
    }

    /** A category without the SEO tail: "Русская классика слушать онлайн, скачать бесплатно" → "Русская классика". */
    static String genreName(String text) {
        return text.replaceFirst("\\s+слушать.*$", "").trim();
    }

    // ---------------------------------------------------------------- genres

    /** The "Рубрики" select: every category by its id, but "Аудиокниги", the catalog itself. */
    public static ProviderPage<GenrePOJO> parseGenres(Document doc) {
        return ProviderPage.of(Html.rows(doc.select("select#cat option"), option -> {
            String id = option.attr("value");
            String name = genreName(option.text());
            return !id.matches("\\d+") || name.equals("Аудиокниги") ? null
                    : new GenrePOJO(name, SERVER + "/?cat=" + id);
        }), false);
    }

    // ---------------------------------------------------------------- book page

    public static DescriptionPOJO parseDescription(Document doc) {
        DescriptionPOJO description = new DescriptionPOJO();
        String heading = text(doc, "#Subheader h1.title");
        if (heading.isEmpty()) {
            return description;
        }
        String[] authorTitle = authorTitle(heading);
        description.setTitle(authorTitle[1]);
        description.setAutor(authorTitle[0]);
        description.setAutorUrl(authorTag(doc, authorTitle[0]));
        description.setPoster(Html.imgSrc(doc.selectFirst(".single-photo-wrapper img.wp-post-image")));
        Element genre = doc.selectFirst(".meta-categories ul.post-categories a");
        if (genre != null) {
            description.setGenre(genreName(genre.text()));
            description.setGenreUrl(genre.absUrl("href"));
        }
        Element content = doc.selectFirst(".the_content_wrapper");
        if (content == null) {
            return description;
        }
        for (String line : Html.paragraphs(content).split("\n")) {
            Matcher time = TIME.matcher(line);
            if (time.matches()) {
                description.setTime(time.group(1).trim());
            }
            Matcher reader = READER.matcher(line);
            if (reader.matches()) {
                description.setArtist(reader.group(1).trim());
            }
        }
        Element own = content.clone();
        // the share and ad blocks, the title again, the player, then the time, reader and download links
        own.select("script, h2, h3, iframe").remove();
        own.children().select("div").remove();
        // 2021 posts print the time, publisher and reader as the text's own last paragraphs
        for (Element p : own.select("p")) {
            String line = p.text().trim();
            if (TIME.matcher(line).matches() || READER.matcher(line).matches() || PUBLISHER.matcher(line).find()) {
                p.remove();
            }
        }
        description.setDescription(Html.paragraphs(own));
        return description;
    }

    /** The tag page of the first tag named in the author part ("Николай Леонов, Алексей Макеев"); "" for none. */
    static String authorTag(Document doc, String authors) {
        String lower = authors.toLowerCase(Locale.ROOT);
        for (Element tag : doc.select(".mata-tags a[href*=/tag/]")) {
            String name = tag.text().trim().toLowerCase(Locale.ROOT);
            if (!name.isEmpty() && lower.contains(name)) {
                return tag.absUrl("href");
            }
        }
        return "";
    }

    // ---------------------------------------------------------------- chapters

    /**
     * The archive.org item of the page's player. A page that says the book was removed at the right holder's request
     * is a {@link BookRemovedException}; any other page without the frame an {@link IllegalStateException}.
     */
    static String archiveItem(Document doc, String bookUrl) {
        Element content = doc.selectFirst(".the_content_wrapper");
        Element frame = content == null ? null : content.selectFirst("iframe[src*=archive.org/embed/]");
        Matcher embed = ARCHIVE_EMBED.matcher(frame == null ? "" : frame.attr("src"));
        if (embed.find()) {
            return embed.group(1);
        }
        if (content != null && content.text().contains("по требованию правообладателя")) {
            throw new BookRemovedException(bookUrl);
        }
        throw new IllegalStateException("no archive.org player on " + bookUrl);
    }
}
