package com.fanok.audiobooks.source;

import static com.fanok.audiobooks.search.ListingFilters.param;
import static com.fanok.audiobooks.search.ListingFilters.withParam;
import static com.fanok.audiobooks.source.Html.cards;
import static com.fanok.audiobooks.source.Html.each;
import static com.fanok.audiobooks.source.Html.lastPage;
import static com.fanok.audiobooks.source.Html.text;

import com.fanok.audiobooks.R;
import com.fanok.audiobooks.Url;
import com.fanok.audiobooks.model.OtherSourceModel;
import com.fanok.audiobooks.pojo.AudioPOJO;
import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.pojo.DescriptionPOJO;
import com.fanok.audiobooks.pojo.GenrePOJO;
import com.fanok.audiobooks.pojo.SeriesPOJO;
import com.fanok.audiobooks.search.ListingFilters;
import com.fanok.audiobooks.search.ProviderPage;
import com.fanok.audiobooks.search.SearchUrls;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;

/**
 * otrub.in: plain GETs. Listings ({@link Url#INDEX_OTRUB}, {@code /popular/?period=week|month}, a genre
 * {@code /genres/<slug>.html}) page with {@code ?p=N}; the site has no genre rankings, so a ranking opened from a genre
 * carries {@code genre=<slug>} (ignored by the server) and its cards are narrowed on the device. Cards carry no
 * duration. Search {@code /search?q=Q&p=N} (books only). Book pages carry an XSPlayer playlist or an inline Playerjs;
 * the series is a {@code /series/<slug>.html} page. No comments, no other narrations.
 */
public final class Otrub implements SourceParser {

    private static final List<Sort.Entry> MENU = Collections.unmodifiableList(Arrays.asList(
            Sort.item(Sort.NEW), Sort.submenu(R.string.popular, Sort.POPULAR_WEEK, Sort.POPULAR_MONTH)));
    private static final List<ListingFilters.Period> WEEK_MONTH =
            Arrays.asList(ListingFilters.Period.WEEK, ListingFilters.Period.MONTH);
    private static final Pattern GENRE =
            Pattern.compile("^" + Pattern.quote(Url.SERVER_OTRUB) + "/genres/([\\w-]+)\\.html");

    // ---------------------------------------------------------------- SourceParser

    @Override
    public ProviderPage<BookPOJO> listing(Document page, String url, int pageNo) {
        return parseListing(page, pageNo);
    }

    @Override
    public String pageUrl(String url, int page) {
        return url.contains("?") ? url + page : url + "?p=" + page;
    }

    /** Author, reader and series listings are not re-sorted. */
    @Override
    public List<Sort.Entry> sorts(String url) {
        boolean people = url.contains("reader") || url.contains("author") || url.contains("serie")
                || url.contains("cikl");
        return people ? Collections.<Sort.Entry>emptyList() : MENU;
    }

    @Override
    public String sortUrl(Sort sort, String listingUrl) {
        switch (sort) {
            case NEW: return Url.NEW_BOOK_OTRUB;
            case POPULAR_WEEK: return Url.BEST_WEEK_OTRUB;
            case POPULAR_MONTH: return Url.BEST_MONTH_OTRUB;
            default: return null;
        }
    }

    /** Cards carry no duration. */
    @Override
    public boolean listsDuration(String url) {
        return false;
    }

    /** {@code popular} takes {@code period=week|month} only. */
    @Override
    public List<ListingFilters.Period> periods(String url) {
        return url.contains("/popular/") ? WEEK_MONTH : Collections.<ListingFilters.Period>emptyList();
    }

    @Override
    public ListingFilters.Period periodOf(String url, Calendar today) {
        return "month".equals(param(url, "period")) ? ListingFilters.Period.MONTH : ListingFilters.Period.WEEK;
    }

    @Override
    public String withPeriod(String url, ListingFilters.Period period, Calendar today) {
        return withParam(url, "period", period == ListingFilters.Period.MONTH ? "month" : "week");
    }

    @Override
    public String cardGenre(String url) {
        return param(url, "genre");
    }

    /** The genre slug of a genre listing, or of a ranking narrowed to one. */
    @Override
    public String genreOf(String url) {
        String narrowed = cardGenre(url);
        if (narrowed != null) {
            return narrowed;
        }
        Matcher m = GENRE.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    /** "New" is the genre listing itself; a ranking carries the genre for {@link #cardGenre}. */
    @Override
    public String inGenre(String siteWide, String genre) {
        return siteWide.equals(Url.NEW_BOOK_OTRUB) ? Url.SERVER_OTRUB + "/genres/" + genre + ".html?p="
                : siteWide.replace("?", "?genre=" + genre + "&");
    }

    @Override
    public ProviderPage<GenrePOJO> genres(Document page, String url, int pageNo) {
        return ProviderPage.of(Html.rows(page.select("a._b8c4a1"), item -> {
            GenrePOJO genrePOJO = new GenrePOJO();
            if (!item.attr("href").isEmpty()) {
                genrePOJO.setUrl(Url.SERVER_OTRUB + item.attr("href") + "?p=");
            }
            Element nameEl = item.selectFirst("._28f7ab");
            if (nameEl != null) {
                genrePOJO.setName(nameEl.text());
            }
            Element countEl = item.selectFirst("._fb36fb");
            if (countEl != null) {
                genrePOJO.setDescription(countEl.text());
            }
            return genrePOJO;
        }), false);
    }

    @Override
    public String searchUrl(SearchUrls.Category category, String query, int page) {
        return category == SearchUrls.Category.BOOKS
                ? Url.SERVER_OTRUB + "/search?q=" + SearchUrls.enc(query) + "&p=" + page : null;
    }

    @Override
    public ProviderPage<BookPOJO> searchResults(Document page, int pageNo) {
        return parseListing(page, pageNo);
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
    public ArrayList<AudioPOJO> tracks(Document page, String base) {
        return parseTracks(page, base);
    }

    @Override
    public ArrayList<SeriesPOJO> series(Document page, String bookUrl) throws IOException {
        String series = Html.href(page, "a[href*=/series/]");
        return series.isEmpty() ? new ArrayList<>() : parseSeriesPage(fetch(series));
    }

    /** The search page without a page number, as the other-source lookup has always asked it. */
    @Override
    public String otherSourceSearchUrl(String terms, int page) {
        return HttpUrl.get(Url.SERVER_OTRUB).newBuilder().addPathSegment("search").addQueryParameter("q", terms)
                .build().toString();
    }

    @Override
    public ArrayList<OtherSourceModel.Hit> otherSourceHits(Document searchPage) {
        return parseSearch(searchPage);
    }

    // ---------------------------------------------------------------- pure parsers

    /** Index, rankings, genres and search results. */
    public static ProviderPage<BookPOJO> parseListing(Document doc, int page) {
        boolean hasMore = lastPage(doc.select(
                "#books_updates_list__pn a[data-page], #books_list__pn a[data-page]")) > page;
        Element listParent = doc.getElementById("books_updates_list");
        if (listParent == null) {
            listParent = doc.getElementById("books_list");
        }
        if (listParent == null) {
            return ProviderPage.empty();
        }
        ArrayList<BookPOJO> result = cards(listParent.select("._cb0a41 > ._dad4fa"), book -> {
            BookPOJO bookPOJO = new BookPOJO();
            Element img = book.selectFirst("._bce453 img._76d12c");
            if (img != null) {
                bookPOJO.setPhoto(img.attr("src"));
            }
            Element genreLink = book.selectFirst("._eeab32 b a");
            if (genreLink != null) {
                if (!genreLink.attr("href").isEmpty()) {
                    bookPOJO.setUrlGenre(Url.SERVER_OTRUB + genreLink.attr("href") + "?p=");
                }
                if (!genreLink.text().isEmpty()) {
                    bookPOJO.setGenre(genreLink.text());
                }
            }
            Element titleLink = book.selectFirst("._3dc935 a");
            if (titleLink != null) {
                if (!titleLink.attr("href").isEmpty()) {
                    bookPOJO.setUrl(Url.SERVER_OTRUB + titleLink.attr("href"));
                }
                if (!titleLink.text().isEmpty()) {
                    bookPOJO.setName(titleLink.text());
                }
            }
            for (Element block : book.select("._5d10b1 ._eeab32")) {
                Element label = block.selectFirst("._a6370b");
                Element a = block.selectFirst("a");
                if (label == null || a == null) {
                    continue;
                }
                String labelText = label.text();
                String href = a.attr("href");
                String name = a.text();
                if (labelText.contains("Автор")) {
                    bookPOJO.setAutor(name);
                    if (!href.isEmpty()) {
                        bookPOJO.setUrlAutor(Url.SERVER_OTRUB + href);
                    }
                } else if (labelText.contains("Читает")) {
                    bookPOJO.setArtist(name);
                    if (!href.isEmpty()) {
                        bookPOJO.setUrlArtist(Url.SERVER_OTRUB + href);
                    }
                } else if (labelText.contains("Цикл") || labelText.contains("Серия")) {
                    bookPOJO.setSeries(name);
                    if (!href.isEmpty()) {
                        bookPOJO.setUrlSeries(href.startsWith("/") ? Url.SERVER_OTRUB + href : href);
                    }
                }
            }
            Element desc = book.selectFirst("._b1f6b9");
            if (desc != null) {
                bookPOJO.setDesc(desc.text());
            }
            return bookPOJO;
        });
        return ProviderPage.of(result, hasMore);
    }

    public static DescriptionPOJO parseDescription(Document document) {
        DescriptionPOJO descriptionPOJO = new DescriptionPOJO();
        Elements titleElement = document.select("h1 span[itemprop=name]");
        if (titleElement.size() > 0) {
            descriptionPOJO.setTitle(titleElement.first().text().trim());
        }

        Elements img = document.select("._5e0b77 img");
        if (img.size() > 0) {
            descriptionPOJO.setPoster(img.first().absUrl("src"));
        }

        Elements infoBlocks = document.select("._72ce76 ._5bef20");
        for (Element block : infoBlocks) {
            String text = block.text();
            Element a = block.select("a").first();
            if (text.contains("Жанр") && a != null) {
                descriptionPOJO.setGenre(a.text());
                descriptionPOJO.setGenreUrl(a.absUrl("href"));
            } else if (text.contains("Автор") && a != null) {
                descriptionPOJO.setAutor(a.text());
                descriptionPOJO.setAutorUrl(a.absUrl("href"));
            } else if (text.contains("Читает") && a != null) {
                descriptionPOJO.setArtist(a.text());
                descriptionPOJO.setArtistUrl(a.absUrl("href"));
            } else if ((text.contains("Цикл") || text.contains("Серия")) && a != null) {
                descriptionPOJO.setSeries(a.text());
                descriptionPOJO.setSeriesUrl(a.absUrl("href"));
            } else if (text.contains("Длительность")) {
                String time = text.replace("Длительность:", "").trim();
                if (!time.isEmpty()) {
                    descriptionPOJO.setTime(time);
                }
            }
        }

        if (descriptionPOJO.getSeries().isEmpty()) {
            Element a = document.select("a[href*=/series/]").first();
            if (a != null) {
                descriptionPOJO.setSeries(a.text());
                descriptionPOJO.setSeriesUrl(a.absUrl("href"));
            }
        }

        Elements desc = document.select("._034fb7[itemprop=description]");
        if (desc.size() > 0) {
            descriptionPOJO.setDescription(desc.first().text());
        }
        return descriptionPOJO;
    }

    /** A book opened by its link. */
    public static BookPOJO parseBook(Document document, String url) {
        BookPOJO bookPOJO = new BookPOJO();

        bookPOJO.setUrl(url);

        Elements titleElement = document.select("h1 span[itemprop=name]");
        if (titleElement.size() > 0) {
            bookPOJO.setName(titleElement.first().text().trim());
        }

        Elements img = document.select("._5e0b77 img");
        if (img.size() > 0) {
            bookPOJO.setPhoto(img.first().absUrl("src"));
        }

        Elements infoBlocks = document.select("._72ce76 ._5bef20");
        for (Element block : infoBlocks) {
            String text = block.text();
            Element a = block.select("a").first();
            if (text.contains("Жанр") && a != null) {
                bookPOJO.setGenre(a.text());
                String href = a.absUrl("href");
                if (!href.isEmpty()) {
                    bookPOJO.setUrlGenre(href + "?p=");
                }
            } else if (text.contains("Автор") && a != null) {
                bookPOJO.setAutor(a.text());
                String href = a.absUrl("href");
                if (!href.isEmpty()) {
                    bookPOJO.setUrlAutor(href);
                }
            } else if (text.contains("Читает") && a != null) {
                bookPOJO.setArtist(a.text());
                String href = a.absUrl("href");
                if (!href.isEmpty()) {
                    bookPOJO.setUrlArtist(href);
                }
            } else if (text.contains("Длительность")) {
                String time = text.replace("Длительность:", "").trim();
                if (!time.isEmpty()) {
                    bookPOJO.setTime(time);
                }
            }
        }

        Elements desc = document.select("._034fb7[itemprop=description]");
        if (desc.size() > 0) {
            bookPOJO.setDesc(desc.first().text());
        }

        return bookPOJO;
    }

    /** An XSPlayer playlist, else an inline Playerjs. */
    public static ArrayList<AudioPOJO> parseTracks(Document doc, String bookUrl) {
        String bookName = text(doc.selectFirst("h1"));
        String player = Players.findPlayerScript(doc, "XSPlayer(", '{');
        if (player == null) {
            return Players.inlinePlayerjs(doc, bookUrl, bookName);
        }
        return Players.xsPlaylist(Players.object(player), bookUrl, bookName, "otrub player");
    }

    /** The books of a {@code /series/<slug>.html} page, in the site's order. */
    public static ArrayList<SeriesPOJO> parseSeriesPage(Document doc) {
        return each(doc.select("._3dc935 a"), a -> {
            Element card = a.closest("._dad4fa");
            return Html.seriesItem(a, card == null ? null : card.selectFirst("img"));
        });
    }

    /** The other-source search: its book cards as hits. */
    public static ArrayList<OtherSourceModel.Hit> parseSearch(Document doc) {
        Element list = doc.getElementById("books_updates_list");
        if (list == null) {
            list = doc.getElementById("books_list");
        }
        if (list == null) {
            return new ArrayList<>();
        }
        return each(list.getElementsByClass("_dad4fa"), book -> {
            Element a = book.selectFirst("._3dc935 a");
            if (a == null) {
                return null;
            }
            String author = "";
            String reader = "";
            for (Element block : book.select("._5d10b1 ._eeab32")) {
                String label = text(block, "._a6370b");
                if (label.contains("Автор")) {
                    author = text(block, "a");
                } else if (label.contains("Читает")) {
                    reader = text(block, "a");
                }
            }
            return new OtherSourceModel.Hit(a.absUrl("href"), a.text().trim(), author, reader);
        });
    }
}
