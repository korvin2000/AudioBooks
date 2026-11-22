package com.fanok.audiobooks.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fanok.audiobooks.BookRemovedException;
import com.fanok.audiobooks.Consts;
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
import java.util.Arrays;
import java.util.List;

/** Pages saved from audioknizhka.org on 2026-11-21 (see src/test/resources/fixtures/audioknizhka). */
public class AudioknizhkaTest {

    private static final String S = Audioknizhka.SERVER;
    private static final String BOOK = S + "/detektiv/41978-poslednij-den-goda-obuhova-lena.html";
    private static final String OLD_BOOK = S + "/skazki/18059-dikij-robot.html";
    private static final String AUTHOR = S + "/xfsearch/author/%D0%BE%D0%B1%D1%83%D1%85%D0%BE%D0%B2%D0%B0"
            + "%20%D0%BB%D0%B5%D0%BD%D0%B0/";
    private static final String SERIES = S + "/xfsearch/cycle/%D0%B4%D0%B8%D0%BA%D0%B8%D0%B9"
            + "%20%D1%80%D0%BE%D0%B1%D0%BE%D1%82/";
    private static final String CYCLE = S + "/xfsearch/cycle/%D0%BD%D0%B5%D0%BC%D0%B8%D1%81%D1%82%D0%B8%D1%87"
            + "%D0%B5%D1%81%D0%BA%D0%B8%D0%B9%20%D0%B4%D0%B5%D1%82%D0%B5%D0%BA%D1%82%D0%B8%D0%B2/";

    private final Audioknizhka parser = new Audioknizhka();

    private static Document fixture(String name, String baseUri) throws IOException {
        try (InputStream in = AudioknizhkaTest.class.getResourceAsStream("/fixtures/audioknizhka/" + name)) {
            assertNotNull("missing fixture " + name, in);
            return Jsoup.parse(in, "UTF-8", baseUri);
        }
    }

    /** The site's duration as the app shows it. */
    private static String time(String printed) {
        return Consts.formatTimeDisplay(printed);
    }

    private static String search(String query, int page) {
        return new Audioknizhka().searchUrl(SearchUrls.Category.BOOKS, query, page);
    }

    // ---------------------------------------------------------------- URLs and sorts

    @Test
    public void drawerUrls() {
        assertEquals(S + "/", Audioknizhka.INDEX);
        assertEquals(S + "/", Audioknizhka.GENRES);
        assertEquals(S + PeopleIndex.AUTHORS, Audioknizhka.AUTHORS);
        assertNull(Audioknizhka.READERS);
    }

    @Test
    public void listingsPageAsDle() {
        assertEquals(S + "/", parser.pageUrl(S + "/", 1));
        assertEquals(S + "/page/2/", parser.pageUrl(S + "/", 2));
        assertEquals(S + "/roman/page/3/", parser.pageUrl(S + "/roman/", 3));
        assertEquals(AUTHOR + "page/2/", parser.pageUrl(AUTHOR, 2));
        assertEquals(Audioknizhka.TOP, parser.pageUrl(Audioknizhka.TOP, 1));
    }

    @Test
    public void onlyTheIndexAndTheTop50Sort() {
        List<Sort.Entry> menu = parser.sorts(S + "/");
        assertEquals(2, menu.size());
        assertEquals(menu, parser.sorts(S + "/page/5/"));
        assertEquals(menu, parser.sorts(Audioknizhka.TOP));
        assertTrue(parser.sorts(S + "/roman/").isEmpty());
        assertTrue(parser.sorts(AUTHOR).isEmpty());
        assertTrue(parser.sorts(search("Обухова", 1)).isEmpty());
        assertEquals(S + "/", parser.sortUrl(Sort.NEW, S + "/"));
        assertEquals(S + "/top.html", parser.sortUrl(Sort.POPULAR, S + "/"));
        for (Sort sort : Sort.values()) {
            if (sort != Sort.NEW && sort != Sort.POPULAR) {
                assertNull(sort.name(), parser.sortUrl(sort, S + "/"));
            }
        }
        assertTrue(parser.periods(S + "/").isEmpty());
    }

    @Test
    public void onlyTheTopListsNoDuration() {
        assertTrue(parser.listsDuration(S + "/"));
        assertTrue(parser.listsDuration(S + "/roman/"));
        assertFalse(parser.listsDuration(Audioknizhka.TOP));
    }

    @Test
    public void searchIsTheDleGet() {
        assertEquals(S + "/index.php?do=search&subaction=search&story=%D0%9E%D0%B1%D1%83%D1%85%D0%BE%D0%B2%D0%B0"
                + "&search_start=2&result_from=11&full_search=0", search("Обухова", 2));
        assertNull(parser.searchUrl(SearchUrls.Category.AUTHORS, "Обухова", 1));
        assertNull(parser.searchUrl(SearchUrls.Category.PERFORMERS, "Обухова", 1));
    }

    // ---------------------------------------------------------------- listings

    @Test
    public void indexCards() throws IOException {
        ProviderPage<BookPOJO> page = parser.listing(fixture("index.html", S + "/"), S + "/", 1);
        assertEquals(ProviderPage.Status.OK, page.status);
        assertTrue(page.hasMore);
        assertEquals(10, page.items.size());
        BookPOJO book = page.items.get(0);
        assertEquals(BOOK, book.getUrl());
        assertEquals("Последний день года", book.getName());
        assertEquals(S + "/uploads/posts/2026-11/6b0168280833f.jpg", book.getPhoto());
        assertEquals("Обухова Лена", book.getAutor());
        assertEquals(AUTHOR, book.getUrlAutor());
        assertEquals("Синица Игорь", book.getArtist());
        assertTrue(book.getUrlArtist() == null || book.getUrlArtist().isEmpty());
        assertEquals("Детектив", book.getGenre());
        assertEquals("НеМистический детектив", book.getSeries());
        assertEquals(CYCLE, book.getUrlSeries());
        assertEquals(time("7:25:34"), book.getTime());
        // a card without series
        BookPOJO second = page.items.get(1);
        assertEquals("Мой лучший друг Тофик", second.getName());
        assertEquals("Ишаев Максим", second.getAutor());
        assertEquals(time("0:33:35"), second.getTime());
        assertTrue(second.getSeries() == null || second.getSeries().isEmpty());
    }

    @Test
    public void laterAndLastPages() throws IOException {
        ProviderPage<BookPOJO> two = parser.listing(fixture("page2.html", S + "/page/2/"), S + "/page/2/", 2);
        assertTrue(two.hasMore);
        assertEquals(10, two.items.size());
        assertEquals("Женщина в чёрном", two.items.get(0).getName());
        assertEquals("Хилл Сьюзен", two.items.get(0).getAutor());

        ProviderPage<BookPOJO> last = parser.listing(fixture("last.html", S + "/page/3799/"), S + "/page/3799/", 3799);
        assertFalse(last.hasMore);
        assertEquals(3, last.items.size());
        BookPOJO oldest = last.items.get(0);
        assertEquals(S + "/roman-i-proza/3619-rozhdestvo-pod-kiparisami.html", oldest.getUrl());
        assertEquals("Рождество под кипарисами", oldest.getName());
        assertEquals("Слимани Лейла", oldest.getAutor());
        assertEquals("Бочанова Юлия", oldest.getArtist());
        assertEquals("Роман и проза / Новый год", oldest.getGenre());
        assertEquals(time("9:12:44"), oldest.getTime());
    }

    @Test
    public void top50IsOnePageOfTitlesAndCovers() throws IOException {
        ProviderPage<BookPOJO> top = parser.listing(fixture("top.html", Audioknizhka.TOP), Audioknizhka.TOP, 1);
        assertFalse(top.hasMore);
        assertEquals(50, top.items.size());
        BookPOJO first = top.items.get(0);
        assertEquals(OLD_BOOK, first.getUrl());
        assertEquals("Дикий робот", first.getName());
        assertEquals(S + "/uploads/posts/2025-11/690a1b824a542.jpg", first.getPhoto());
        assertEquals(S + "/bojar-anime/6251-ne-tak-vospitan.html", top.items.get(49).getUrl());
        assertEquals("Не так воспитан", top.items.get(49).getName());
    }

    @Test
    public void genreListing() throws IOException {
        ProviderPage<BookPOJO> page = parser.listing(fixture("genre.html", S + "/roman/"), S + "/roman/", 1);
        assertTrue(page.hasMore);
        assertEquals(10, page.items.size());
        BookPOJO book = page.items.get(0);
        assertEquals(S + "/roman/41976-sahar-i-zoloto-skott-jemma.html", book.getUrl());
        assertEquals("Сахар и золото", book.getName());
        assertEquals("Скотт Эмма", book.getAutor());
        assertEquals("Моторная Людмила", book.getArtist());
        assertEquals("Роман", book.getGenre());
        assertEquals(time("14:10:02"), book.getTime());
    }

    @Test
    public void authorListing() throws IOException {
        ProviderPage<BookPOJO> page = parser.listing(fixture("author.html", AUTHOR), AUTHOR, 1);
        assertTrue(page.hasMore);
        assertEquals(10, page.items.size());
        for (BookPOJO book : page.items) {
            assertTrue(book.getAutor(), book.getAutor().contains("Обухова Лена"));
        }
    }

    @Test
    public void genresAreTheSidebarMenuWithoutEmptyOnes() throws IOException {
        ProviderPage<GenrePOJO> page = parser.genres(fixture("index.html", S + "/"), S + "/", 1);
        assertFalse(page.hasMore);
        ArrayList<GenrePOJO> genres = new ArrayList<>(page.items);
        // 782 menu rows: 8 empty, 20 repeats of a URL, 8 book titles cut at their quote
        assertEquals(746, genres.size());
        GenrePOJO first = genres.get(0);
        assertEquals("Альтернативная история", first.getName());
        assertEquals(S + "/alternativnaja-istorija/", first.getUrl());
        assertEquals(252, first.getReting());
        List<String> names = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        for (GenrePOJO genre : genres) {
            assertTrue(genre.getUrl(), genre.getUrl().startsWith(S + "/") && genre.getUrl().endsWith("/"));
            assertTrue(genre.getName(), genre.getReting() > 0);
            names.add(genre.getName());
            urls.add(genre.getUrl());
        }
        assertTrue(names.containsAll(Arrays.asList("Аудиоспектакли", "Детектив", "Фэнтези", "Попаданцы", "LitRPG")));
        assertFalse(names.contains("Топ 50"));
        assertFalse(names.contains("Подборка"));
        // "Полицейские детективы" twice: the empty twin is left out
        assertTrue(urls.contains(S + "/policejskie-detektivy/"));
        assertFalse(urls.contains(S + "/politseyskiye-detektivy/"));
        assertFalse(urls.contains(S + "/filosofiya/"));
        assertEquals(urls.size(), new java.util.HashSet<>(urls).size());
        assertTrue(urls.contains(S + "/detskaja-poznavatelnaja-i-razvivajuschaja-literatu/"));
        assertTrue(urls.contains(S + "/zarubezhnaja-religioznaja-i-jezotericheskaja-liter/"));
        assertFalse(urls.contains(S + "/upravlenie-krest/"));
        assertFalse(urls.contains(S + "/i-posle/"));
        assertFalse(names.contains("Сын мужа. Скажи мне «да"));
        assertTrue(names.contains("S.T.A.L.K.E.R."));
    }

    // ---------------------------------------------------------------- search

    @Test
    public void searchPages() throws IOException {
        ProviderPage<BookPOJO> one = parser.searchResults(fixture("search.html", search("Обухова", 1)), 1);
        assertTrue(one.hasMore);
        assertEquals(10, one.items.size());
        assertEquals(BOOK, one.items.get(0).getUrl());
        assertEquals("Последний день года", one.items.get(0).getName());
        assertEquals("Стихотворения", one.items.get(1).getName());

        ProviderPage<BookPOJO> two = parser.searchResults(fixture("search-2.html", search("Обухова", 2)), 2);
        assertFalse(two.hasMore);
        assertEquals(7, two.items.size());
        BookPOJO coauthored = two.items.get(0);
        assertEquals("Призраки белых ночей", coauthored.getName());
        assertEquals("Обухова Лена, Тимошенко Наталья", coauthored.getAutor());
        assertEquals("Современные детективы", coauthored.getGenre());
    }

    @Test
    public void searchWithoutHitsIsEmpty() throws IOException {
        ProviderPage<BookPOJO> none = parser.searchResults(fixture("search-empty.html", search("zzqxwvjk", 1)), 1);
        assertEquals(ProviderPage.Status.EMPTY, none.status);
        assertFalse(none.hasMore);
    }

    // ---------------------------------------------------------------- book page

    @Test
    public void newBookDescription() throws IOException {
        DescriptionPOJO d = parser.description(fixture("book.html", BOOK), BOOK);
        assertEquals("Последний день года", d.getTitle());
        assertEquals("Обухова Лена", d.getAutor());
        assertEquals(AUTHOR, d.getAutorUrl());
        assertEquals("Синица Игорь", d.getArtist());
        assertEquals("Детектив", d.getGenre());
        assertEquals(S + "/detektiv/", d.getGenreUrl());
        assertEquals("НеМистический детектив", d.getSeries());
        assertEquals(CYCLE, d.getSeriesUrl());
        assertEquals(S + "/uploads/posts/2026-11/6b0168280833f.jpg", d.getPoster());
        assertEquals(time("7:25:34"), d.getTime());
        assertTrue(d.getDescription(), d.getDescription().startsWith("В канун Нового года группа давних друзей"));
        assertTrue(d.getDescription().endsWith("недоверия между близкими людьми."));
        assertTrue(d.getReiting() == null || d.getReiting().isEmpty());
    }

    @Test
    public void oldBookDescription() throws IOException {
        DescriptionPOJO d = parser.description(fixture("book-old.html", OLD_BOOK), OLD_BOOK);
        assertEquals("Дикий робот", d.getTitle());
        assertEquals("Браун Питер", d.getAutor());
        assertEquals("Коротаева Екатерина", d.getArtist());
        assertEquals("Сказки", d.getGenre());
        assertEquals(S + "/skazki/", d.getGenreUrl());
        assertEquals("Дикий робот", d.getSeries());
        assertEquals(SERIES, d.getSeriesUrl());
        assertEquals(time("3:56:30"), d.getTime());
        assertEquals(BookPOJO.formatRating(4f, "8"), d.getReiting());
        assertTrue(d.getDescription().startsWith("Удивительная история про робота по имени Роз"));
    }

    @Test
    public void recommendations() throws IOException {
        ArrayList<BookPOJO> similar = parser.recommendations(fixture("book.html", BOOK), BOOK);
        assertEquals(5, similar.size());
        assertEquals(S + "/lyubovnyye-romany/39984-granicy-bezopasnosti.html", similar.get(0).getUrl());
        assertEquals("Границы (без)опасности", similar.get(0).getName());
        assertEquals(S + "/uploads/posts/2026-09/rejn-uajt.webp", similar.get(0).getPhoto());
    }

    @Test
    public void seriesListingInPageOrder() throws IOException {
        ArrayList<SeriesPOJO> series = Audioknizhka.parseSeries(fixture("series.html", SERIES));
        assertEquals(3, series.size());
        assertEquals(OLD_BOOK, series.get(0).getUrl());
        assertEquals("Дикий робот", series.get(0).getName());
        assertEquals(S + "/skazki/18046-spasenie-dikogo-robota.html", series.get(1).getUrl());
        assertEquals(S + "/zarubezhnye-detskie-knigi/16347-missija-dikogo-robota.html", series.get(2).getUrl());
        assertFalse(Audioknizhka.parseListing(fixture("series.html", SERIES), 1).hasMore);
    }

    // ---------------------------------------------------------------- chapters

    @Test
    public void newBookIsOneWholeBookMp3() throws IOException {
        ArrayList<AudioPOJO> tracks = parser.tracks(fixture("book.html", BOOK), BOOK);
        assertEquals(1, tracks.size());
        AudioPOJO only = tracks.get(0);
        assertEquals("0001", only.getName());
        assertEquals("https://archive.org/download/0001_20261121_20261121_0013/0001.mp3", only.getUrl());
        assertEquals("Последний день года", only.getBookName());
        assertEquals(0, only.getTime());
    }

    @Test
    public void oldBookSingleQuotedListWithEntities() throws IOException {
        ArrayList<AudioPOJO> tracks = parser.tracks(fixture("book-old.html", OLD_BOOK), OLD_BOOK);
        assertEquals(3, tracks.size());
        assertEquals("Дикий робот 01-31", tracks.get(0).getName());
        assertEquals("https://archive.org/download/55-80/%D0%94%D0%B8%D0%BA%D0%B8%D0%B9%20%D1%80%D0%BE%D0%B1%D0%BE"
                + "%D1%82%2001-31.mp3", tracks.get(0).getUrl());
        assertEquals("Дикий робот 55-80", tracks.get(2).getName());
        assertEquals("https://archive.org/download/55-80/%D0%94%D0%B8%D0%BA%D0%B8%D0%B9%20%D1%80%D0%BE%D0%B1%D0%BE"
                + "%D1%82%2055-80.mp3", tracks.get(2).getUrl());
    }

    @Test
    public void pageWithoutPlayerIsAnError() throws IOException {
        try {
            parser.tracks(fixture("index.html", S + "/"), S + "/");
            fail("a page without a player has no chapters");
        } catch (BookRemovedException e) {
            fail("an unknown page is not a removed book");
        } catch (IllegalStateException expected) {
            // not a book removed: an unknown page
        }
    }

    /** A taken-down book keeps its page and an empty {@code .video-box} (3836, one of the Top-50). */
    @Test(expected = BookRemovedException.class)
    public void emptyPlayerIsARemovedBook() throws IOException {
        String url = S + "/boevaja-fantastika/3836-kazhdomu-svoe-2.html";
        parser.tracks(fixture("book-removed.html", url), url);
    }

    /** The h1 "Title - <a>A1</a>, <a>A2</a>" of a co-authored book. */
    @Test
    public void coAuthoredBook() throws IOException {
        String url = S + "/bojar-anime/35699-chehov-kniga-3-mertvye-nevesty.html";
        Document page = fixture("book-coauthors.html", url);
        DescriptionPOJO d = parser.description(page, url);
        assertEquals("Чехов. Книга 3. Мертвые невесты", d.getTitle());
        assertEquals("Каин, Гоблин", d.getAutor());
        ArrayList<AudioPOJO> tracks = parser.tracks(page, url);
        assertEquals(29, tracks.size());
        assertEquals("Чехов. Книга 3. Мертвые невесты", tracks.get(0).getBookName());
        assertEquals("https://archive.org/download/26_20240726_202407/01.mp3", tracks.get(0).getUrl());
        assertEquals("https://archive.org/download/26_20240726_202407/29.mp3", tracks.get(28).getUrl());
    }

    @Test
    public void noOwnHostsOrHeaders() {
        assertTrue(parser.hosts().isEmpty());
        assertTrue(parser.refererHosts().isEmpty());
        assertNull(parser.referer());
        assertNull(parser.mediaHeaders(BOOK));
    }
}
