package com.fanok.audiobooks.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fanok.audiobooks.model.OtherSourceModel;
import com.fanok.audiobooks.pojo.AudioPOJO;
import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.pojo.DescriptionPOJO;
import com.fanok.audiobooks.pojo.GenrePOJO;
import com.fanok.audiobooks.pojo.SeriesPOJO;
import com.fanok.audiobooks.search.ProviderPage;
import com.fanok.audiobooks.search.SearchUrls;
import com.fanok.audiobooks.util.DownloadUtil;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Pages saved from baza-knig.info on 2026-11-04 (see src/test/resources/fixtures/baza-knig-info). */
public class BazaKnigInfoTest {

    private static final String S = BazaKnigInfo.SERVER;
    private static final String BOOK = S
            + "/audio-125174-princessa-v-akademii-kniga-2-kak-razljubit-v-akademii-lda-i-ognja-julija-hanevskaja";
    private static final String GENRE = S + "/genre-5-detektivy-trillery?page=";
    private static final String AUTHOR = S + "/avtor-179-saimak-klifford?page=";
    private static final String READER = S + "/ispolnitel-1368-tverskaya-alisa?page=";
    private static final String SERIES = S + "/series-12789-princessa-v-akademii?page=";

    private final BazaKnigInfo site = new BazaKnigInfo();

    private static InputStream open(String name) {
        InputStream in = BazaKnigInfoTest.class.getResourceAsStream("/fixtures/baza-knig-info/" + name);
        assertNotNull("missing fixture " + name, in);
        return in;
    }

    static Document page(String name, String url) throws IOException {
        try (InputStream in = open(name)) {
            return Jsoup.parse(in, "UTF-8", url);
        }
    }

    static String text(String name) throws IOException {
        try (InputStream in = open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0; ) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), "UTF-8");
        }
    }

    // ---------------------------------------------------------------- URLs and sorts

    @Test
    public void listingsPageWithThePageParameterAlone() {
        assertEquals(S + "/?page=2", site.pageUrl(BazaKnigInfo.INDEX, 2));
        assertEquals(S + "/genre-5-detektivy-trillery", site.pageUrl(GENRE, 1));
        assertEquals(S + "/genre-5-detektivy-trillery?page=99", site.pageUrl(GENRE, 99));
        assertEquals(S + "/random-book?page=2", site.pageUrl(BazaKnigInfo.RANDOM, 2));
    }

    @Test
    public void theOrderMenuIsNewAndARandomBookWithoutATop() {
        List<Sort> menu = Arrays.asList(Sort.NEW, Sort.RANDOM);
        assertEquals(menu, AudioKnigaBizTest.flat(site.sorts(BazaKnigInfo.INDEX)));
        assertEquals(menu, AudioKnigaBizTest.flat(site.sorts(BazaKnigInfo.RANDOM)));
        for (String url : new String[]{GENRE, AUTHOR, READER, SERIES}) {
            assertTrue(url, site.sorts(url).isEmpty());
        }
        assertEquals(BazaKnigInfo.INDEX, site.sortUrl(Sort.NEW, BazaKnigInfo.RANDOM));
        assertEquals(BazaKnigInfo.RANDOM, site.sortUrl(Sort.RANDOM, BazaKnigInfo.INDEX));
        assertNull("/top is 404", site.sortUrl(Sort.TOP_100, BazaKnigInfo.INDEX));
    }

    @Test
    public void searchIsUtf8AndOneStaticPage() {
        assertEquals(S + "/search?text=%D0%9C%D0%B0%D1%82%D0%B0%D0%B1%D0%B0%D1%80&page=1",
                site.searchUrl(SearchUrls.Category.BOOKS, "Матабар", 1));
        assertEquals(S + "/search?text=%D0%9C%D0%B0%D1%82%D0%B0%D0%B1%D0%B0%D1%80&page=3",
                site.searchUrl(SearchUrls.Category.AUTHORS, "Матабар", 3));
        assertNull(site.searchUrl(SearchUrls.Category.PERFORMERS, "Матабар", 1));
    }

    // ---------------------------------------------------------------- listings

    @Test
    public void indexCardsReadTitleThenAuthor() throws IOException {
        ProviderPage<BookPOJO> first = site.listing(page("listing-index-p1.html", S + "/"), BazaKnigInfo.INDEX, 1);
        assertEquals(12, first.items.size());
        assertTrue(first.hasMore);
        BookPOJO book = first.items.get(0);
        assertEquals("Изумруд раджи", book.getName());
        assertEquals(S + "/audio-125180-izumrud-radzhi-agata-kristi", book.getUrl());
        assertEquals("Кристи Агата", book.getAutor());
        assertEquals(S + "/avtor-44-kristi-agata?page=", book.getUrlAutor());
        assertEquals("Иликаев Тимур", book.getArtist());
        assertEquals(S + "/ispolnitel-11432-ilikaev-timur?page=", book.getUrlArtist());
        assertEquals("41 мин.", book.getTime());
        assertEquals("Детективы, триллеры", book.getGenre());
        assertEquals(GENRE, book.getUrlGenre());
        assertEquals(BazaKnigInfo.COVERS + "1/2/5/1/8/0/izumrud-radzhi-agata-kristi.jpg", book.getPhoto());

        BookPOJO inSeries = first.items.get(3);
        assertEquals("Мой наглый гад", inSeries.getName());
        assertEquals("Светлячок", inSeries.getArtist());
        assertEquals("Мои Медведевы", inSeries.getSeries());
        assertEquals(S + "/series-12782-moi-medvedevy?page=", inSeries.getUrlSeries());

        BookPOJO noReader = first.items.get(11);
        assertEquals("Рассказы", noReader.getName());
        assertEquals("Саймак Клиффорд", noReader.getAutor());
        assertNull(noReader.getArtist());
        assertEquals("16 ч. 27 мин.", noReader.getTime());

        ProviderPage<BookPOJO> second = site.listing(page("listing-index-p2.html", S + "/?page=2"), S + "/?page=2", 2);
        assertEquals(12, second.items.size());
        assertTrue(second.hasMore);
        BookPOJO noAuthorLink = second.items.get(0);
        assertEquals("the author named by the title alone", "Чужой", noAuthorLink.getName());
        assertEquals("Mort Post", noAuthorLink.getAutor());
        assertNull(noAuthorLink.getUrlAutor());
    }

    @Test
    public void listingsEndAtPage99WhereTheSiteStopsServing() throws IOException {
        Document doc = page("listing-index-p99.html", S + "/?page=99");
        assertTrue("the pager links page 100, which answers 403", Html.lastPage(doc.select(".pagination li a")) > 99);
        ProviderPage<BookPOJO> last = site.listing(doc, S + "/?page=99", 99);
        assertEquals(12, last.items.size());
        assertFalse(last.hasMore);

        ProviderPage<BookPOJO> genreEnd = site.listing(page("genre-last.html", S + "/genre-61-erotika?page=2"),
                S + "/genre-61-erotika?page=2", 2);
        assertEquals(5, genreEnd.items.size());
        assertFalse(genreEnd.hasMore);
        assertEquals("the author in other words", "Ты - все", genreEnd.items.get(0).getName());
        assertEquals("Тодорова Елена", genreEnd.items.get(0).getAutor());

        ProviderPage<BookPOJO> genre = site.listing(page("genre.html", S + "/genre-5-detektivy-trillery"), GENRE, 1);
        assertEquals(12, genre.items.size());
        assertTrue(genre.hasMore);
    }

    @Test
    public void authorReaderAndSeriesListings() throws IOException {
        ProviderPage<BookPOJO> author = site.listing(page("author.html", AUTHOR), AUTHOR, 1);
        assertEquals(12, author.items.size());
        assertTrue(author.hasMore);
        assertEquals("Чудесное избавление", author.items.get(7).getName());

        ProviderPage<BookPOJO> reader = site.listing(page("reader.html", READER), READER, 1);
        assertEquals(12, reader.items.size());
        assertTrue(reader.hasMore);
        assertEquals("Тверская Алиса", reader.items.get(0).getArtist());
        assertEquals("a title after an icon", "Кошка для дракошки", reader.items.get(10).getName());

        ProviderPage<BookPOJO> series = site.listing(page("series.html", SERIES), SERIES, 1);
        assertEquals(2, series.items.size());
        assertFalse(series.hasMore);
    }

    @Test
    public void genresCountTheirBooks() throws IOException {
        ProviderPage<GenrePOJO> genres = site.genres(page("genres.html", BazaKnigInfo.GENRES), BazaKnigInfo.GENRES, 1);
        assertEquals(50, genres.items.size());
        assertFalse(genres.hasMore);
        assertEquals("Фантастика, фэнтези", genres.items.get(0).getName());
        assertEquals(S + "/genre-3-fantastika-fentezi?page=", genres.items.get(0).getUrl());
        assertEquals("30678 аудиокниг", genres.items.get(0).getDescription());
        GenrePOJO warhammer = null;
        for (GenrePOJO genre : genres.items) {
            if (genre.getName().equals("Warhammer 40000")) {
                warhammer = genre;
            }
        }
        assertNotNull(warhammer);
        assertEquals("a number in the name is not the count", "274 аудиоккниги", warhammer.getDescription());
        assertEquals("Технотьма", genres.items.get(49).getName());
    }

    // ---------------------------------------------------------------- search

    @Test
    public void searchHitsNameTheirAuthorLastAndCarryTheirCover() throws IOException {
        Document doc = page("search.html", S + "/search?text=x");
        ProviderPage<BookPOJO> first = site.searchResults(doc, 1);
        assertEquals(40, first.items.size());
        assertTrue(first.hasMore);
        BookPOJO hit = first.items.get(0);
        assertEquals("Матабар IV. Серия 2", hit.getName());
        assertEquals("Кирилл Клеванский", hit.getAutor());
        assertEquals(S + "/audio-102390-matabar-iv-serija-2-kirill-klevanskij", hit.getUrl());
        assertEquals(BazaKnigInfo.COVERS + "1/0/2/3/9/0/matabar-iv-serija-2-kirill-klevanskij.jpg", hit.getPhoto());
        ProviderPage<BookPOJO> second = site.searchResults(doc, 2);
        assertEquals(2, second.items.size());
        assertFalse(second.hasMore);
        assertEquals(ProviderPage.Status.EMPTY,
                site.searchResults(page("search-none.html", S + "/search?text=x"), 1).status);

        ProviderPage<GenrePOJO> authors = site.searchPeople(doc, 1, false);
        assertEquals(2, authors.items.size());
        assertEquals("Клеванский Кирилл", authors.items.get(0).getName());
        assertEquals(S + "/avtor-1914-klevanskii-kirill?page=", authors.items.get(0).getUrl());
        assertEquals("Аудиокниг: 63", authors.items.get(0).getDescription());

        List<OtherSourceModel.Hit> other = site.otherSourceHits(doc);
        assertEquals(42, other.size());
        assertEquals("Матабар IV. Серия 2", other.get(0).title);
        assertEquals("Кирилл Клеванский", other.get(0).author);
    }

    // ---------------------------------------------------------------- people

    @Test
    public void readersStopAtPage99() throws IOException {
        SourceParser.PeopleCursor cursor = new SourceParser.PeopleCursor();
        ProviderPage<GenrePOJO> first = site.people(page("performers.html", BazaKnigInfo.READERS), 1, cursor,
                url -> {
                    throw new AssertionError("a paged list fetches nothing more: " + url);
                });
        assertEquals(50, first.items.size());
        assertTrue(first.hasMore);
        assertEquals("Герасимов Вячеслав", first.items.get(0).getName());
        assertEquals(S + "/ispolnitel-7-gerasimov-vyacheslav?page=", first.items.get(0).getUrl());
        assertEquals("745 книг", first.items.get(0).getDescription());

        Document last = page("performers-p99.html", S + "/performers?page=99");
        assertTrue("the pager links page 100, which answers 403", Html.lastPage(last.select(".pagination li a")) > 99);
        ProviderPage<GenrePOJO> end = site.people(last, 99, cursor, url -> {
            throw new AssertionError(url);
        });
        assertEquals(50, end.items.size());
        assertFalse("the list ends cleanly at the last page served", end.hasMore);
    }

    @Test
    public void authorsWalkEveryLetterPageByPage() throws IOException {
        Document index = page("authors.html", S + "/authors");
        List<String> letters = site.letters(index);
        assertEquals(S + "/authors/let-А", letters.get(0));
        assertEquals("Я", letters.get(28).substring(letters.get(28).length() - 1));
        assertEquals(S + "/authors/let-A", letters.get(29));
        List<String> fetched = new ArrayList<>();
        SourceParser.PeopleCursor cursor = new SourceParser.PeopleCursor();
        // А: its first page (more follow), then its last, page 40, as if pages 2 to 39 had been read
        ProviderPage<GenrePOJO> page1 = site.people(index, 1, cursor, url -> {
            fetched.add(url);
            return page("authors-let-a.html", url);
        });
        assertEquals(50, page1.items.size());
        assertTrue(page1.hasMore);
        assertEquals("А Толин", page1.items.get(0).getName());
        assertEquals(S + "/avtor-14790-a-tolin?page=", page1.items.get(0).getUrl());
        assertEquals("1 книга", page1.items.get(0).getDescription());
        cursor.skipped += 38;
        ProviderPage<GenrePOJO> page2 = site.people(index, 2, cursor, url -> {
            fetched.add(url);
            return page("authors-let-a-last.html", url);
        });
        assertEquals(49, page2.items.size());
        assertTrue("Б follows", page2.hasMore);
        site.people(index, 3, cursor, url -> {
            fetched.add(url);
            return page("authors-let-a.html", url);
        });
        assertEquals(Arrays.asList(S + "/authors/let-%D0%90", S + "/authors/let-%D0%90?page=40",
                S + "/authors/let-%D0%91"), fetched);
    }

    // ---------------------------------------------------------------- book page

    @Test
    public void bookPage() throws IOException {
        Document doc = page("book.html", BOOK);
        DescriptionPOJO d = site.description(doc, BOOK);
        assertEquals("Как (раз)любить в академии льда и огня", d.getTitle());
        assertEquals("Ханевская Юлия", d.getAutor());
        assertEquals(S + "/avtor-2017-hanevskaya-yuliya?page=", d.getAutorUrl());
        assertEquals("Тверская Алиса", d.getArtist());
        assertEquals(S + "/ispolnitel-1368-tverskaya-alisa?page=", d.getArtistUrl());
        assertEquals("Любовное фэнтези", d.getGenre());
        assertEquals(S + "/genre-38-lyubovnoe-fentezi?page=", d.getGenreUrl());
        assertEquals("Принцесса в академии", d.getSeries());
        assertEquals(SERIES, d.getSeriesUrl());
        assertEquals("7 ч. 39 мин.", d.getTime());
        assertEquals("11", d.getReiting());
        assertEquals(BazaKnigInfo.COVERS + "1/2/5/1/7/4/"
                + "princessa-v-akademii-kniga-2-kak-razljubit-v-akademii-lda-i-ognja-julija-hanevskaja.jpg",
                d.getPoster());
        assertTrue(d.getDescription(),
                d.getDescription().trim().startsWith("«Как (раз)любить в академии льда и огня»"));

        BookPOJO card = site.book(doc, BOOK);
        assertEquals("Как (раз)любить в академии льда и огня", card.getName());
        assertEquals("Принцесса в академии", card.getSeries());
        assertEquals("11", card.getReting());
        assertEquals("Как (раз)любить в академии льда и огня", site.bookName(doc));

        ArrayList<BookPOJO> similar = site.recommendations(doc, BOOK);
        assertEquals(5, similar.size());
        assertEquals("Магия забытых душ. Книга 2", similar.get(0).getName());
        assertEquals("Закалюжная Людмила", similar.get(0).getAutor());
        assertEquals("Летура Анна", similar.get(0).getArtist());

        assertTrue(Abmp3Engine.parseComments(doc).comments.isEmpty());
        assertEquals("Oxana", Abmp3Engine.parseComments(page("book-comments.html", S + "/audio-100115-x")).comments
                .get(0).getName().trim());
    }

    @Test
    public void theSeriesTabListsTheSeriesPageWithNumbers() throws IOException {
        List<String> fetched = new ArrayList<>();
        ArrayList<SeriesPOJO> series = site.series(page("book.html", BOOK), url -> {
            fetched.add(url);
            return page("series.html", url);
        });
        assertEquals(Arrays.asList(S + "/series-12789-princessa-v-akademii"), fetched);
        assertEquals(2, series.size());
        assertEquals("Как не влюбиться в иномирной академии", series.get(0).getName());
        assertEquals("1", series.get(0).getNumber());
        assertEquals("Как (раз)любить в академии льда и огня", series.get(1).getName());
        assertEquals("2", series.get(1).getNumber());
        assertEquals(BOOK, series.get(1).getUrl());
        assertTrue(series.get(1).getPhoto().startsWith(BazaKnigInfo.COVERS));
    }

    // ---------------------------------------------------------------- chapters

    @Test
    public void chaptersAreOneMp3Each() throws IOException {
        ArrayList<AudioPOJO> tracks = Abmp3Engine.parsePlaylist(text("playlist.pl.txt"),
                "https://9giiu0g54k8c.redirectto.cc/s01/1/2/5/1/7/4/125174.pl.txt", "Книга");
        assertEquals(35, tracks.size());
        assertEquals("1", tracks.get(0).getName());
        assertEquals("https://9giiu0g54k8c.redirectto.cc/s01/1/2/5/1/7/4/0.mp3", tracks.get(0).getUrl());
        assertEquals("35", tracks.get(34).getName());
        assertEquals("https://9giiu0g54k8c.redirectto.cc/s01/1/2/5/1/7/4/34.mp3", tracks.get(34).getUrl());
        assertEquals(-1, tracks.get(0).getTimeStart());
        assertFalse(site.downloadsSharedFiles());
    }

    @Test
    public void thePlaylistIsAskedWithTheBookAsRefererAndTheMediaWithTheSite() throws Exception {
        try (PlaylistServer server = new PlaylistServer(text("playlist.pl.txt"))) {
            Document doc = Jsoup.parse(text("book.html").replace(
                    "https://9giiu0g54k8c.redirectto.cc/s01/1/2/5/1/7/4/125174.pl.txt", server.url()), BOOK);
            ArrayList<AudioPOJO> tracks = site.tracks(doc, BOOK);
            assertEquals(BOOK, server.referer());
            assertEquals(35, tracks.size());
            assertEquals("Как (раз)любить в академии льда и огня", tracks.get(0).getBookName());
        }
        Map<String, String> media = site.mediaHeaders(BOOK);
        assertEquals("the CDN answers 403 without the site's Referer", S + "/", media.get("Referer"));
        assertEquals(DownloadUtil.DESKTOP_UA, media.get("User-Agent"));
    }
}
