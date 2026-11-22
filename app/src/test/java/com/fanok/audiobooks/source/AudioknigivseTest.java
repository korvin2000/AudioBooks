package com.fanok.audiobooks.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fanok.audiobooks.BookRemovedException;
import com.fanok.audiobooks.pojo.AudioPOJO;
import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.pojo.DescriptionPOJO;
import com.fanok.audiobooks.pojo.GenrePOJO;
import com.fanok.audiobooks.pojo.SeriesPOJO;
import com.fanok.audiobooks.search.PeopleIndex;
import com.fanok.audiobooks.search.ProviderPage;
import com.fanok.audiobooks.search.SearchUrls;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Pages saved from audioknigivse.ru on 2026-11-21 (see src/test/resources/fixtures/audioknigivse). */
public class AudioknigivseTest {

    private static final String S = Audioknigivse.SERVER;
    private static final String BOOK = S + "/audioknigi_-vsem_popadanci/22602-chernaja-luna-aleksandr-izotov-5.html";
    private static final String IZOTOV = S + "/xfsearch/avtor/%D0%B0%D0%BB%D0%B5%D0%BA%D1%81%D0%B0%D0%BD%D0%B4%D1%80"
            + "%20%D0%B8%D0%B7%D0%BE%D1%82%D0%BE%D0%B2/";
    private static final String FANTASTIKA = S + "/audioknigi_vsem_fantastika/";

    private final Audioknigivse parser = new Audioknigivse();

    private static Document fixture(String name, String baseUri) throws IOException {
        try (InputStream in = AudioknigivseTest.class.getResourceAsStream("/fixtures/audioknigivse/" + name)) {
            assertNotNull("missing fixture " + name, in);
            return Jsoup.parse(in, "UTF-8", baseUri);
        }
    }

    private static String firstUrl(String name) throws IOException {
        return Audioknigivse.parseListing(fixture(name, S + "/"), 1).items.get(0).getUrl();
    }

    // ---------------------------------------------------------------- URLs

    @Test
    public void drawerUrls() {
        assertEquals(S + "/", Audioknigivse.INDEX);
        assertEquals(S + PeopleIndex.AUTHORS, Audioknigivse.AUTHORS);
        assertEquals(S + PeopleIndex.READERS, Audioknigivse.READERS);
    }

    @Test
    public void pagesAppendPageN() {
        assertEquals(S + "/", parser.pageUrl(S + "/", 1));
        assertEquals(S + "/page/1373/", parser.pageUrl(S + "/", 1373));
        assertEquals(FANTASTIKA + "page/2/", parser.pageUrl(FANTASTIKA, 2));
        assertEquals(IZOTOV + "page/3/", parser.pageUrl(IZOTOV, 3));
    }

    @Test
    public void indexSortsArePostedFormsThatChangeTheOrder() throws IOException {
        String form = "&set_new_sort=dle_sort_main&set_direction_sort=dle_direction_main";
        assertEquals(S + "/", parser.sortUrl(Sort.NEW, S + "/"));
        assertEquals(S + "/?dlenewssortby=news_read&dledirection=desc" + form, parser.sortUrl(Sort.POPULAR, S + "/"));
        assertEquals(S + "/?dlenewssortby=rating&dledirection=desc" + form, parser.sortUrl(Sort.RATING, S + "/"));
        String title = parser.sortUrl(Sort.TITLE, S + "/");
        assertEquals(S + "/?dlenewssortby=title&dledirection=asc" + form, title);
        assertEquals(S + "/page/2/?dlenewssortby=title&dledirection=asc" + form, parser.pageUrl(title, 2));
        assertEquals("the date form of a sorted index", S + "/?dlenewssortby=date&dledirection=desc" + form,
                parser.sortUrl(Sort.NEW, title));
        assertNull("no comments: comm_num keeps the date order", parser.sortUrl(Sort.COMMENTS, S + "/"));
        assertNull(parser.sortUrl(Sort.TOP_100, S + "/"));
        assertEquals(Audioknigivse.MENU, parser.sorts(S + "/"));
        assertEquals(Audioknigivse.MENU, parser.sorts(title));
        assertEquals(4, Audioknigivse.MENU.size());

        String date = firstUrl("sort-date.html");
        assertEquals(firstUrl("index.html"), date);
        assertEquals(S + "/audioknigi_litrpg/165-sumerechnyj-pet-dmitrij-bilik-2-kniga.html",
                firstUrl("sort-news_read.html"));
        assertEquals(S + "/audiokniga_litres/11541-bestoloch-ilja-romanov-1.html", firstUrl("sort-rating.html"));
        assertEquals(S + "/audiokniga_litres/8261-moja-neidealnaja-zhizn-sofi-kinsella.html",
                firstUrl("sort-title.html"));
        ProviderPage<BookPOJO> title2 = Audioknigivse.parseListing(fixture("sort-title-page2.html", S + "/"), 2);
        assertEquals("page 2 POSTed with the form stays in title order", "(Не) родной сын для майора Абрамова",
                title2.items.get(0).getName());
        assertEquals(10, title2.items.size());
        assertTrue(title2.hasMore);
    }

    @Test
    public void genreSortsUseTheCategoryId() throws IOException {
        assertEquals(FANTASTIKA, parser.genreOf(FANTASTIKA + "page/3/"));
        assertNull(parser.genreOf(S + "/"));
        assertNull(parser.genreOf(S + "/page/2/"));
        assertNull(parser.genreOf(IZOTOV));
        assertNull(parser.genreOf(S + "/tags/"));
        String popular = parser.inGenre(parser.sortUrl(Sort.POPULAR, S + "/"), FANTASTIKA);
        assertEquals(FANTASTIKA + "?dlenewssortby=news_read&dledirection=desc"
                + "&set_new_sort=dle_sort_cat_47&set_direction_sort=dle_direction_cat_47", popular);
        assertEquals(FANTASTIKA, parser.genreOf(popular));
        assertEquals(Audioknigivse.MENU, parser.sorts(popular));
        assertEquals(Audioknigivse.MENU, parser.sorts(FANTASTIKA));
        assertEquals(FANTASTIKA + "page/2/?dlenewssortby=news_read&dledirection=desc"
                + "&set_new_sort=dle_sort_cat_47&set_direction_sort=dle_direction_cat_47", parser.pageUrl(popular, 2));
        assertTrue(parser.sorts(IZOTOV).isEmpty());
        assertTrue(parser.sorts(parser.searchUrl(SearchUrls.Category.BOOKS, "x", 1)).isEmpty());

        String plain = firstUrl("genre.html");
        assertEquals(S + "/audioknigi_-vsem_popadanci/22599-sila-krovi-5-aleks-kamenev-5.html", plain);
        assertEquals(S + "/audioknigi_vsem_fantastika/2741-krepost-zhivyh-nikolaj-berg-1.html",
                firstUrl("genre-sort-news_read.html"));
        assertEquals(S + "/audioknigi_vsem_fantastika/1121-ohranitel-konstantin-nazimov-1.html",
                firstUrl("genre-sort-rating.html"));
        assertEquals(S + "/audioknigi_vsem_fantastika/2525-220-dnej-na-zvezdolete-georgij-martynov-1.html",
                firstUrl("genre-sort-title.html"));
    }

    // ---------------------------------------------------------------- listings

    @Test
    public void indexCards() throws IOException {
        ProviderPage<BookPOJO> page = Audioknigivse.parseListing(fixture("index.html", S + "/"), 1);
        assertEquals(10, page.items.size());
        assertTrue(page.hasMore);
        BookPOJO book = page.items.get(0);
        assertEquals(BOOK, book.getUrl());
        assertEquals("Чёрная луна", book.getName());
        assertEquals(S + "/uploads/posts/2026-11/2026-11-15_204740.jpg", book.getPhoto());
        assertEquals("Александр Изотов", book.getAutor());
        assertEquals(IZOTOV, book.getUrlAutor());
        assertEquals("Максим Ушаков", book.getArtist());
        assertEquals(S + "/xfsearch/zvuk/%D0%BC%D0%B0%D0%BA%D1%81%D0%B8%D0%BC%20%D1%83%D1%88%D0%B0%D0%BA%D0%BE%D0%B2/",
                book.getUrlArtist());
        assertEquals("Попаданцы / Фэнтези", book.getGenre());
        assertEquals(S + "/audioknigi_-vsem_popadanci/", book.getUrlGenre());
        assertEquals("Пробоина", book.getSeries());
        assertEquals(S + "/xfsearch/seria/%D0%BF%D1%80%D0%BE%D0%B1%D0%BE%D0%B8%D0%BD%D0%B0/", book.getUrlSeries());
        assertEquals("8 ч. 27 мин.", book.getTime());
        assertEquals("4.0", book.getReting());
        assertEquals("Путь марсиан", page.items.get(9).getName());
        assertTrue(parser.listsDuration(S + "/"));
    }

    @Test
    public void laterAndLastPages() throws IOException {
        ProviderPage<BookPOJO> two = Audioknigivse.parseListing(fixture("page2.html", S + "/page/2/"), 2);
        assertEquals(10, two.items.size());
        assertTrue(two.hasMore);
        BookPOJO first = two.items.get(0);
        assertEquals("Сила крови-2", first.getName());
        assertEquals("Алекс Каменев", first.getAutor());
        assertEquals("Юрий Мироненко", first.getArtist());
        assertEquals("9 ч. 6 мин.", first.getTime());
        assertEquals("nobody voted", "0", first.getReting());

        ProviderPage<BookPOJO> last = Audioknigivse.parseListing(fixture("page1373.html", S + "/page/1373/"), 1373);
        assertEquals(3, last.items.size());
        assertFalse(last.hasMore);
        BookPOJO old = last.items.get(0);
        assertEquals("a title without \" / \" stays whole", "Ярость Богов (книга 3) Серия: мир Вальдиры",
                old.getName());
        assertNull("no author row", old.getAutor());
        assertEquals("ЛитРПГ", old.getGenre());
        assertTrue("no duration row", old.getTime() == null || old.getTime().isEmpty());
    }

    @Test
    public void personAndSeriesListings() throws IOException {
        ProviderPage<BookPOJO> author = Audioknigivse.parseListing(fixture("author.html", IZOTOV), 1);
        assertEquals(10, author.items.size());
        assertTrue(author.hasMore);
        assertEquals("Александр Изотов", author.items.get(0).getAutor());
        assertEquals("a title with no space before the slash", "Убить топа. Запределье",
                author.items.get(7).getName());
        assertEquals("24/7", Audioknigivse.name("24/7"));
        ProviderPage<BookPOJO> series = Audioknigivse.parseListing(fixture("series.html", S + "/"), 1);
        assertEquals(5, series.items.size());
        assertFalse(series.hasMore);
    }

    @Test
    public void aPageWithoutCardsIsEmpty() throws IOException {
        assertTrue(Audioknigivse.parseListing(fixture("book.html", BOOK), 1).items.isEmpty());
    }

    // ---------------------------------------------------------------- genres

    @Test
    public void genresAreTheSidebarMenu() throws IOException {
        List<GenrePOJO> genres = Audioknigivse.parseGenres(fixture("index.html", S + "/")).items;
        assertEquals(18, genres.size());
        assertEquals("ЛитРПГ", genres.get(0).getName());
        assertEquals(S + "/audioknigi_litrpg/", genres.get(0).getUrl());
        assertEquals("Проза", genres.get(17).getName());
        assertEquals(S + "/proza_audio/", genres.get(17).getUrl());
        for (GenrePOJO genre : genres) {
            assertNotNull(genre.getName(), parser.genreOf(genre.getUrl()));
        }
    }

    // ---------------------------------------------------------------- search

    @Test
    public void searchUrl() {
        assertEquals(S + "/index.php?do=search&subaction=search&story=%D0%BE%D1%81%D0%B0%D0%B4%D1%87%D1%83%D0%BA"
                        + "&search_start=3&result_from=21&full_search=0",
                parser.searchUrl(SearchUrls.Category.BOOKS, "осадчук", 3));
        assertNull(parser.searchUrl(SearchUrls.Category.AUTHORS, "x", 1));
    }

    @Test
    public void searchHits() throws IOException {
        ProviderPage<BookPOJO> page = parser.searchResults(fixture("search.html", S + "/index.php"), 1);
        assertEquals(10, page.items.size());
        assertTrue(page.hasMore);
        BookPOJO hit = page.items.get(0);
        assertEquals(S + "/audioknigi_-vsem_popadanci/19454-predopredelenie-aleksej-osadchuk-9.html", hit.getUrl());
        assertEquals("Предопределение", hit.getName());
        assertEquals("Алексей Осадчук", hit.getAutor());
        assertEquals(S + "/uploads/posts/2025-11/2025-11-14_161249.jpg", hit.getPhoto());
        assertEquals("Попаданцы / Фэнтези", hit.getGenre());

        ProviderPage<BookPOJO> last = parser.searchResults(fixture("search-last.html", S + "/index.php"), 3);
        assertEquals(3, last.items.size());
        assertFalse(last.hasMore);
        assertEquals("Путь Изгоя (3)", last.items.get(0).getName());

        ProviderPage<BookPOJO> none = parser.searchResults(fixture("search-empty.html", S + "/index.php"), 1);
        assertTrue(none.items.isEmpty());
        assertFalse(none.hasMore);
    }

    // ---------------------------------------------------------------- book page

    @Test
    public void description() throws IOException {
        Document doc = fixture("book.html", BOOK);
        DescriptionPOJO d = parser.description(doc, BOOK);
        assertEquals("Чёрная луна", d.getTitle());
        assertEquals(S + "/uploads/posts/2026-11/2026-11-15_204740.jpg", d.getPoster());
        assertEquals("Александр Изотов", d.getAutor());
        assertEquals(IZOTOV, d.getAutorUrl());
        assertEquals("Максим Ушаков", d.getArtist());
        assertEquals("Попаданцы / Фэнтези", d.getGenre());
        assertEquals(S + "/audioknigi_-vsem_popadanci/", d.getGenreUrl());
        assertEquals("Пробоина", d.getSeries());
        assertEquals(S + "/xfsearch/seria/%D0%BF%D1%80%D0%BE%D0%B1%D0%BE%D0%B8%D0%BD%D0%B0/", d.getSeriesUrl());
        assertEquals("8 ч. 27 мин.", d.getTime());
        assertEquals("☆ 4,0 (2)".replace(',', '.'), d.getReiting().replace(',', '.'));
        String text = d.getDescription();
        assertTrue(text, text.startsWith("В тело студента-доходяги Василия Ветрова"));
        assertTrue(text, text.endsWith("у царя на это свои планы."));
        assertEquals("two paragraphs (a <br>)", 2, text.split("\n").length);
        assertFalse(text, text.contains("Ya.Context"));

        BookPOJO book = parser.book(doc, BOOK);
        assertEquals("Чёрная луна", book.getName());
        assertEquals("4.0", book.getReting());
        assertEquals("Максим Ушаков", book.getArtist());
    }

    @Test
    public void chapters() throws IOException {
        ArrayList<AudioPOJO> tracks = parser.tracks(fixture("book.html", BOOK), BOOK);
        assertEquals(24, tracks.size());
        assertEquals("https://archive.org/download/01_20261028_20261028_2210/01.mp3", tracks.get(0).getUrl());
        assertEquals("01", tracks.get(0).getName());
        assertEquals("Чёрная луна", tracks.get(0).getBookName());
        assertEquals("https://archive.org/download/01_20261028_20261028_2210/24.mp3", tracks.get(23).getUrl());
        assertEquals("24", tracks.get(23).getName());
        assertEquals(0, tracks.get(0).getTime());
    }

    /** Older books (here 2023) draw the BBCode as {@code .dleaudioplayer li[data-url]}, without {@code <audio>}. */
    @Test
    public void chaptersOfTheOlderPlayer() throws IOException {
        String url = S + "/audioknigi_vsem_fantastika/10627-doktor-4-semen-afanasev-4.html";
        Document doc = fixture("book-old.html", url);
        ArrayList<AudioPOJO> tracks = parser.tracks(doc, url);
        assertEquals(32, tracks.size());
        assertEquals("https://archive.org/download/4-02_20230525/4_01.mp3", tracks.get(0).getUrl());
        assertEquals("01", tracks.get(0).getName());
        assertEquals("Доктор-4", tracks.get(0).getBookName());
        assertEquals("https://archive.org/download/4-02_20230525/4_32.mp3", tracks.get(31).getUrl());
        assertEquals("32", tracks.get(31).getName());

        DescriptionPOJO d = parser.description(doc, url);
        assertEquals("Семен Афанасьев", d.getAutor());
        assertEquals("Фантастика / Попаданцы", d.getGenre());
        assertTrue(d.getDescription(), d.getDescription().startsWith("Семён Афанасьев – популярный"));
        assertFalse("the player is not text", d.getDescription().contains("4_01.mp3"));
    }

    @Test
    public void aTakenDownBookIsRemoved() throws IOException {
        String url = S + "/audiokniga_litres/11541-bestoloch-ilja-romanov-1.html";
        Document doc = fixture("book-blocked.html", url);
        DescriptionPOJO d = parser.description(doc, url);
        assertEquals("Бестолочь", d.getTitle());
        assertEquals("Заблокировано", d.getGenre());
        assertTrue(d.getDescription(), d.getDescription().startsWith("Жить в Средневековье легко"));
        assertFalse(d.getDescription(), d.getDescription().contains("удалена"));
        try {
            parser.tracks(doc, url);
            fail();
        } catch (BookRemovedException expected) {
            // the notice instead of the player
        }
    }

    @Test(expected = IllegalStateException.class)
    public void aPageWithoutPlayerHasNoChapters() throws IOException {
        parser.tracks(fixture("index.html", S + "/"), S + "/");
    }

    @Test
    public void recommendations() throws IOException {
        ArrayList<BookPOJO> similar = parser.recommendations(fixture("book.html", BOOK), BOOK);
        assertEquals(5, similar.size());
        assertEquals("Тень пса", similar.get(0).getName());
        assertEquals(S + "/audioknigi_-vsem_popadanci/19489-ten-psa-aleksandr-izotov-3.html", similar.get(0).getUrl());
        assertEquals(S + "/uploads/posts/2025-11/2025-11-16_084705.jpg", similar.get(0).getPhoto());
    }

    @Test
    public void seriesListingByNumber() throws IOException {
        ArrayList<SeriesPOJO> series = Audioknigivse.parseSeries(fixture("series.html", S + "/"));
        assertEquals(5, series.size());
        assertEquals("Чёрная луна", series.get(0).getName());
        assertEquals("5", series.get(0).getNumber());
        assertEquals(BOOK, series.get(0).getUrl());
        assertEquals("Магия вето", series.get(4).getName());
        assertEquals("1", series.get(4).getNumber());
    }

    @Test
    public void defaultsStay() {
        assertTrue(parser.hosts().isEmpty());
        assertTrue(parser.refererHosts().isEmpty());
        assertNull(parser.referer());
        assertTrue(parser.periods(S + "/").isEmpty());
    }
}
