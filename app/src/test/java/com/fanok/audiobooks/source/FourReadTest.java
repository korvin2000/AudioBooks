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
import com.fanok.audiobooks.search.ProviderPage;
import com.fanok.audiobooks.search.SearchUrls;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pages saved from 4read.org on 2026-11-04 (see src/test/resources/fixtures/4read). */
public class FourReadTest {

    private static final String S = FourRead.SERVER;
    private static final String HLS_BOOK = S + "/8628-klub-otello-dzhej-di-pennington.html";
    private static final String MP3_BOOK = S + "/4111-krauch-bleik-temna-materiia.html";
    private static final String REMOVED_BOOK = S + "/2016-gerbert-frenk-dyuna.html";
    private static final String DUNE = S + "/xfsearch/cikl/%D0%B4%D1%8E%D0%BD%D0%B0/";

    private final FourRead parser = new FourRead();

    private static InputStream open(String name) {
        InputStream in = FourReadTest.class.getResourceAsStream("/fixtures/4read/" + name);
        assertNotNull("missing fixture " + name, in);
        return in;
    }

    private static Document fixture(String name, String baseUri) throws IOException {
        try (InputStream in = open(name)) {
            return Jsoup.parse(in, "UTF-8", baseUri);
        }
    }

    private static String text(String name) throws IOException {
        try (InputStream in = open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0; ) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    // ---------------------------------------------------------------- URLs, sorts, network

    @Test
    public void pagesAreDleSlashPageAndSinglePagesStay() {
        assertEquals(FourRead.INDEX, parser.pageUrl(FourRead.INDEX, 1));
        assertEquals(S + "/page/3/", parser.pageUrl(FourRead.INDEX, 3));
        assertEquals(S + "/fantast/page/2/", parser.pageUrl(S + "/fantast/", 2));
        assertEquals(S + "/fantast/", parser.pageUrl(S + "/fantast/page/4/", 1));
        assertEquals(DUNE + "page/2/", parser.pageUrl(DUNE, 2));
        assertEquals(FourRead.TOP_100, parser.pageUrl(FourRead.TOP_100, 2));
        assertEquals(FourRead.AUTHORS, parser.pageUrl(FourRead.AUTHORS, 2));
    }

    @Test
    public void theOrderMenuIsTheIndexAndTheTop100Only() {
        for (String url : new String[]{FourRead.INDEX, S + "/page/7/", FourRead.TOP_100}) {
            List<Sort.Entry> menu = parser.sorts(url);
            assertEquals(url, 2, menu.size());
            assertEquals(Sort.NEW, menu.get(0).sort);
            assertEquals(Sort.TOP_100, menu.get(1).sort);
        }
        assertEquals(FourRead.INDEX, parser.sortUrl(Sort.NEW, FourRead.TOP_100));
        assertEquals(FourRead.TOP_100, parser.sortUrl(Sort.TOP_100, FourRead.INDEX));
        // the server ignores the DLE sort form: no rating, views, comments or title order
        assertNull(parser.sortUrl(Sort.RATING, FourRead.INDEX));
        assertNull(parser.sortUrl(Sort.POPULAR, FourRead.INDEX));
        assertFalse(parser.hasSort(S + "/fantast/"));
        assertFalse(parser.hasSort(DUNE));
        assertFalse(parser.hasSort(parser.searchUrl(SearchUrls.Category.BOOKS, "кохання", 1)));
        assertNull(parser.genreOf(S + "/fantast/"));
    }

    @Test
    public void searchIsBooksOnlyAndPaged() {
        assertTrue(parser.searchUrl(SearchUrls.Category.BOOKS, "кохання", 2).startsWith(S
                + "/index.php?do=search&subaction=search&story=%D0%BA%D0%BE%D1%85%D0%B0%D0%BD%D0%BD%D1%8F"
                + "&search_start=2&"));
        assertNull(parser.searchUrl(SearchUrls.Category.AUTHORS, "x", 1));
        assertNull(parser.searchUrl(SearchUrls.Category.PERFORMERS, "x", 1));
    }

    @Test
    public void theMediaHostIsTheSitesAndWantsItsReferer() {
        assertEquals(new HashSet<>(Arrays.asList("reasd.org")), parser.hosts());
        assertEquals(FourRead.INDEX, parser.mediaHeaders(HLS_BOOK).get("Referer"));
    }

    // ---------------------------------------------------------------- listings

    @Test
    public void indexCardsCarryTitleAuthorCoverAndDuration() throws IOException {
        ProviderPage<BookPOJO> page = FourRead.parseListing(fixture("listing-index-p1.html", FourRead.INDEX), 1);
        assertEquals(ProviderPage.Status.OK, page.status);
        assertEquals("the carousels above the grid are not listed", 24, page.items.size());
        assertTrue("203 pages", page.hasMore);
        BookPOJO first = page.items.get(0);
        assertEquals("Коротке щасливе життя одного коричневого черевика", first.getName());
        assertEquals(S + "/8651-filip-dik-korotke-schaslive-zhittja-odnogo-korichnevogo-cherevika.html",
                first.getUrl());
        assertEquals("Філіп Дік", first.getAutor());
        assertEquals(S + "/uploads/posts/2026-11/medium/korotke-schaslive-zhittja-odnogo-korichnevogo-cherevika.webp",
                first.getPhoto());
        assertEquals("20 мин.", first.getTime());
        for (BookPOJO book : page.items) {
            assertTrue(book.getUrl(), book.getUrl().matches("https://4read\\.org/\\d+-[^/]+\\.html"));
        }
    }

    @Test
    public void laterPagesCarryTheRatingAndTheLastPageEnds() throws IOException {
        ProviderPage<BookPOJO> page = FourRead.parseListing(fixture("listing-index-p2.html", S + "/page/2/"), 2);
        assertEquals(24, page.items.size());
        assertTrue(page.hasMore);
        assertEquals("Мар’яна", page.items.get(0).getName());
        assertEquals("4", page.items.get(0).getReting());
        assertEquals("3.6", page.items.get(1).getReting());
        ProviderPage<BookPOJO> last = FourRead.parseListing(fixture("listing-index-last.html", S + "/page/203/"), 203);
        assertEquals(2, last.items.size());
        assertFalse(last.hasMore);
    }

    @Test
    public void aGenreListsItsGridOnly() throws IOException {
        ProviderPage<BookPOJO> page = FourRead.parseListing(fixture("genre-fantast-p1.html", S + "/fantast/"), 1);
        assertEquals("the genre's top carousel and the sidebar are not listed", 24, page.items.size());
        assertTrue("34 pages", page.hasMore);
        for (BookPOJO book : page.items) {
            assertFalse("Dune is in the carousel only", book.getUrl().equals(REMOVED_BOOK));
        }
    }

    @Test
    public void top100HasItsOwnCards() throws IOException {
        ProviderPage<BookPOJO> page = FourRead.parseListing(fixture("top-100.html", FourRead.TOP_100), 1);
        assertEquals(100, page.items.size());
        assertFalse(page.hasMore);
        BookPOJO first = page.items.get(0);
        assertEquals("Геловін - найкращий час для смерті", first.getName());
        assertEquals(S + "/6430-martin-otem-gelovin-najkraschij-chas-dlja-smerti.html", first.getUrl());
        assertEquals("Марті Отем", first.getAutor());
        assertTrue(first.getGenre(), first.getGenre().startsWith("Українська література / "));
        assertEquals(S + "/ukrayinska/", first.getUrlGenre());
        assertEquals("1 ч. 24 мин.", first.getTime());
        assertEquals("4.8", first.getReting());
        assertEquals(45, first.getComents());
        assertTrue(first.getPhoto().startsWith(S + "/uploads/posts/"));
    }

    @Test
    public void authorReaderAndCycleListings() throws IOException {
        String author = S + "/xfsearch/avtor/%D0%B0%D0%B9%D0%B7%D0%B5%D0%BA%20%D0%B0%D0%B7%D1%96%D0%BC%D0%BE%D0%B2/";
        ProviderPage<BookPOJO> azimov = FourRead.parseListing(fixture("author-azimov-p1.html", author), 1);
        assertEquals(24, azimov.items.size());
        assertTrue("2 pages", azimov.hasMore);
        String reader = S + "/xfsearch/chitaet/%D0%BA%D0%B0%D1%80%D1%96%D0%BD%D0%B0%20%D1%87%D0%B8%D1%82%D0%B0%D1%94/";
        ProviderPage<BookPOJO> karina = FourRead.parseListing(fixture("reader-karina.html", reader), 1);
        assertEquals(6, karina.items.size());
        assertFalse(karina.hasMore);
        ProviderPage<BookPOJO> dune = FourRead.parseListing(fixture("series-duna.html", DUNE), 1);
        assertEquals(4, dune.items.size());
        assertFalse(dune.hasMore);
    }

    // ---------------------------------------------------------------- search

    @Test
    public void searchPagesGoOnPastTheCappedCount() throws IOException {
        String url = parser.searchUrl(SearchUrls.Category.BOOKS, "кохання", 1);
        ProviderPage<BookPOJO> first = parser.searchResults(fixture("search-kohannia-p1.html", url), 1);
        assertEquals(24, first.items.size());
        assertTrue(first.hasMore);
        assertEquals("Маска", first.items.get(0).getName());
        assertEquals("Станіслав Лем", first.items.get(0).getAutor());
        // "знайдено 120" on every page and a pager of 5, but page 6 and later still answer
        ProviderPage<BookPOJO> fifth = parser.searchResults(fixture("search-kohannia-p5.html", url), 5);
        assertEquals(24, fifth.items.size());
        assertTrue(fifth.hasMore);
    }

    @Test
    public void aSearchWithoutHitsIsEmpty() throws IOException {
        ProviderPage<BookPOJO> none = parser.searchResults(fixture("search-none.html",
                parser.searchUrl(SearchUrls.Category.BOOKS, "qzxwvjkl", 1)), 1);
        assertEquals(ProviderPage.Status.EMPTY, none.status);
        assertFalse(none.hasMore);
    }

    // ---------------------------------------------------------------- sections

    @Test
    public void genresAreTheMenusAndTheHiddenCategories() throws IOException {
        List<GenrePOJO> genres = parser.genres(fixture("listing-index-p1.html", FourRead.GENRES), FourRead.GENRES, 1)
                .items;
        List<String> names = new ArrayList<>();
        Set<String> urls = new HashSet<>();
        for (GenrePOJO genre : genres) {
            names.add(genre.getName());
            assertTrue(genre.getUrl(), genre.getUrl().matches("https://4read\\.org/[a-z0-9-]+/"));
            assertTrue("duplicate " + genre.getUrl(), urls.add(genre.getUrl()));
        }
        assertEquals(Arrays.asList("Казки", "Дитячі", "Колискові", "Детективи", "Романи", "Фантастика", "Фентезі",
                "Саморозвиток", "Історична проза", "Сучасна проза", "Пригоди", "Про кохання", "Оповідання", "Вірші",
                "Драма", "Містика", "Жахи", "Біографії", "Аудіовистави", "Про війну", "Про бізнес", "П'єси", "Інше",
                "Українська література", "Зарубіжна література", "Антиутопія", "Гумор"), names);
    }

    @Test
    public void authorsAndReadersAreOnePageEach() throws IOException {
        ProviderPage<GenrePOJO> authors = parser.people(fixture("avtors.html", FourRead.AUTHORS), FourRead.AUTHORS, 1,
                false);
        assertEquals(1737, authors.items.size());
        assertFalse(authors.hasMore);
        GenrePOJO first = authors.items.get(0);
        assertEquals("Аарон Дембскі-Боуден", first.getName());
        assertEquals("1 книга", first.getDescription());
        assertTrue(first.getUrl().startsWith(S + "/xfsearch/avtor/"));
        ProviderPage<GenrePOJO> readers = parser.people(fixture("readers.html", FourRead.READERS), FourRead.READERS,
                1, true);
        assertEquals(994, readers.items.size());
        GenrePOJO karina = null;
        for (GenrePOJO reader : readers.items) {
            if (reader.getName().equals("Каріна читає")) {
                karina = reader;
            }
        }
        assertNotNull(karina);
        assertEquals("6 книг", karina.getDescription());
        assertEquals(S + "/xfsearch/chitaet/%D0%BA%D0%B0%D1%80%D1%96%D0%BD%D0%B0%20%D1%87%D0%B8%D1%82%D0%B0%D1%94/",
                karina.getUrl());
    }

    // ---------------------------------------------------------------- book page

    @Test
    public void bookPageCarriesEveryField() throws IOException {
        Document page = fixture("book-hls.html", HLS_BOOK);
        DescriptionPOJO d = parser.description(page, HLS_BOOK);
        assertEquals("Клуб Отелло", d.getTitle());
        assertEquals("Джей Ді Пеннінгтон", d.getAutor());
        assertTrue(d.getAutorUrl(), d.getAutorUrl().startsWith(S + "/xfsearch/avtor/"));
        assertEquals("Каріна читає", d.getArtist());
        assertTrue(d.getArtistUrl(), d.getArtistUrl().startsWith(S + "/xfsearch/chitaet/"));
        assertTrue(d.getGenre(), d.getGenre().startsWith("Світова література / Сучасна проза"));
        assertEquals(S + "/zarubizhna/", d.getGenreUrl());
        assertEquals("10 ч. 33 мин.", d.getTime());
        assertEquals(38_000, FourRead.bookSeconds(page));
        assertEquals("5", d.getReiting());
        assertEquals(S + "/uploads/posts/2026-10/medium/bd0ec0798d_photo_2026-10-23_18-44-23.jpg", d.getPoster());
        assertTrue(d.getDescription(), d.getDescription().startsWith("У психологічному трилері"));
        assertEquals("", d.getSeries());
        BookPOJO card = parser.book(page, HLS_BOOK);
        assertEquals("Клуб Отелло", card.getName());
        assertEquals("5", card.getReting());
        assertEquals(6, parser.recommendations(page, HLS_BOOK).size());
    }

    @Test
    public void anEmptyPageHasNoTitle() {
        assertEquals("", FourRead.parseDescription(Jsoup.parse("<html><body></body></html>", HLS_BOOK)).getTitle());
    }

    @Test
    public void aCycleBookNamesItsCycleAndATakenDownOneHasNoPlayer() throws IOException {
        Document page = fixture("book-removed-series.html", REMOVED_BOOK);
        DescriptionPOJO d = parser.description(page, REMOVED_BOOK);
        assertEquals("Дюна", d.getTitle());
        assertEquals("Дюна", d.getSeries());
        assertEquals(DUNE, d.getSeriesUrl());
        assertEquals("Характерник", d.getArtist());
        try {
            parser.tracks(page, REMOVED_BOOK);
            fail("a book without a player has no chapters");
        } catch (BookRemovedException expected) {
            // the page stays, its player is empty
        }
    }

    @Test
    public void theCycleTabIsOrderedByNumber() throws IOException {
        ArrayList<SeriesPOJO> items = FourRead.parseSeries(fixture("series-duna.html", DUNE));
        FourRead.sortByNumber(items);
        List<String> order = new ArrayList<>();
        for (SeriesPOJO item : items) {
            order.add(item.getNumber() + " " + item.getName());
            assertTrue(item.getPhoto().startsWith(S + "/uploads/"));
        }
        assertEquals(Arrays.asList("1.1 Дюна", "1.3 Пророк", "2 Месія Дюни", "4 Бог-Імператор Дюни"), order);
    }

    // ---------------------------------------------------------------- chapters

    @Test
    public void thePlayerNamesTheM3uUnderTheSiteRoot() throws IOException {
        assertEquals(S + "/m33u2/8628-klub-otello-dzhej-di-pennington.m3u",
                FourRead.playlistUrl(fixture("book-hls.html", HLS_BOOK), HLS_BOOK));
        assertEquals(S + "/m33u2/4111-krauch-bleik-temna-materiia.m3u",
                FourRead.playlistUrl(fixture("book-mp3.html", MP3_BOOK), MP3_BOOK));
    }

    @Test
    public void aNewBookIsOneHlsStreamOfTheWholeBook() throws IOException {
        String playlist = S + "/m33u2/8628-klub-otello-dzhej-di-pennington.m3u";
        ArrayList<AudioPOJO> tracks = FourRead.parseTracks(text("playlist-hls.m3u"), playlist, "Клуб Отелло", 38_000);
        assertEquals(1, tracks.size());
        AudioPOJO whole = tracks.get(0);
        assertEquals("Клуб Отелло", whole.getName());
        assertEquals("Клуб Отелло", whole.getBookName());
        assertTrue(whole.getUrl(), whole.getUrl().startsWith("https://s1.reasd.org/8628/hls/pl.m3u8?expires="));
        assertEquals(38_000, whole.getTime());
    }

    @Test
    public void anOldBookIsAnMp3PerPart() throws IOException {
        String playlist = S + "/m33u2/4111-krauch-bleik-temna-materiia.m3u";
        ArrayList<AudioPOJO> tracks = FourRead.parseTracks(text("playlist-mp3.m3u"), playlist, "Темна матерія", 34_080);
        assertEquals(15, tracks.size());
        assertEquals("Темна матерія-01", tracks.get(0).getName());
        assertEquals("Темна матерія-15", tracks.get(14).getName());
        assertTrue(tracks.get(0).getUrl(), tracks.get(0).getUrl().startsWith("https://reasd.org/4111/"));
        assertTrue(tracks.get(0).getUrl().contains(".mp3?expires="));
        assertEquals(0, tracks.get(0).getTime());
    }

    @Test
    public void extinfNamesAndLengthsAreKeptAndJunkIsNoPlaylist() {
        ArrayList<AudioPOJO> tracks = FourRead.parseTracks("#EXTM3U\n#EXTINF:125,Розділ 1\n"
                + "https://reasd.org/1/a.mp3\n#EXTINF:-1,\nhttps://reasd.org/1/b.mp3\n", S + "/m33u2/1-x.m3u", "X", 0);
        assertEquals(2, tracks.size());
        assertEquals("Розділ 1", tracks.get(0).getName());
        assertEquals(125, tracks.get(0).getTime());
        assertEquals("b", tracks.get(1).getName());
        assertEquals(0, tracks.get(1).getTime());
        try {
            FourRead.parseTracks("<!DOCTYPE html><html><body>404</body></html>", S + "/m33u2/1-x.m3u", "X", 0);
            fail("an HTML answer is no playlist");
        } catch (IllegalStateException expected) {
            // no playable chapters
        }
    }
}
