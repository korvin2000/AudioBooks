package com.fanok.audiobooks.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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

/** Pages saved from slushkin.online on 2026-11-21 (see src/test/resources/fixtures/slushkin). */
public class SlushkinTest {

    private static final String S = Slushkin.SERVER;
    private static final String VIJ = S + "/70930-vij-20.html";

    private final Slushkin parser = new Slushkin();

    private static Document fixture(String name, String baseUri) throws IOException {
        try (InputStream in = SlushkinTest.class.getResourceAsStream("/fixtures/slushkin/" + name)) {
            assertNotNull("missing fixture " + name, in);
            return Jsoup.parse(in, "UTF-8", baseUri);
        }
    }

    private static ProviderPage<BookPOJO> listing(String name, String url, int page) throws IOException {
        return Slushkin.parseListing(fixture(name, url), page);
    }

    // ---------------------------------------------------------------- URLs

    @Test
    public void drawerUrlsPagesAndNoSorts() {
        assertEquals(S + "/", Slushkin.INDEX);
        assertEquals(S + "/ganr.html", Slushkin.GENRES);
        assertEquals(S + PeopleIndex.AUTHORS, Slushkin.AUTHORS);
        assertEquals(S + PeopleIndex.READERS, Slushkin.READERS);
        assertEquals(Slushkin.INDEX, parser.pageUrl(Slushkin.INDEX, 1));
        assertEquals(S + "/page/6772/", parser.pageUrl(Slushkin.INDEX, 6772));
        assertEquals(S + "/zhanry/uzhasy/page/2/", parser.pageUrl(S + "/zhanry/uzhasy/", 2));
        assertEquals(S + "/xfsearch/avtor/viktor-pelevin/page/3/",
                parser.pageUrl(S + "/xfsearch/avtor/viktor-pelevin/", 3));
        for (String url : new String[]{Slushkin.INDEX, S + "/zhanry/uzhasy/", S + "/xfsearch/ozv/roman-efimov/"}) {
            assertTrue(url, parser.sorts(url).isEmpty());
            assertFalse(url, parser.hasSort(url));
            assertTrue(url, parser.listsDuration(url));
        }
        assertNull(parser.sortUrl(Sort.POPULAR, Slushkin.INDEX));
        assertTrue(parser.hosts().contains("bookis.shop"));
        assertTrue(parser.hosts().contains("bookis.life"));
        assertNull(parser.referer());
        assertNull(parser.mediaHeaders(VIJ));
    }

    @Test
    public void searchIsThePathFormPagedFromOne() {
        assertEquals(S + "/search/%D0%9F%D1%83%D1%88%D0%BA%D0%B8%D0%BD/page/1/",
                parser.searchUrl(SearchUrls.Category.BOOKS, "Пушкин", 1));
        assertEquals(S + "/search/%D0%B2%D0%B8%D0%B9%202.0/page/2/",
                parser.searchUrl(SearchUrls.Category.BOOKS, "вий 2.0", 2));
        assertEquals("a slash in the path is a 404", S + "/search/abc%20def/page/1/",
                parser.searchUrl(SearchUrls.Category.BOOKS, "abc/def", 1));
        assertEquals("under 5 characters the site refuses, the LIKE wildcard pads it",
                S + "/search/%D0%9A%D0%B8%D0%BD%D0%B3%25/page/1/",
                parser.searchUrl(SearchUrls.Category.BOOKS, "Кинг", 1));
        assertEquals(S + "/search/%D0%9E%D0%BD%D0%BE%25%25/page/2/",
                parser.searchUrl(SearchUrls.Category.BOOKS, " Оно ", 2));
        assertNull(parser.searchUrl(SearchUrls.Category.AUTHORS, "x", 1));
        assertNull(parser.searchUrl(SearchUrls.Category.PERFORMERS, "x", 1));
    }

    // ---------------------------------------------------------------- listings

    @Test
    public void indexCardsCarryEveryField() throws IOException {
        ProviderPage<BookPOJO> page = listing("index.html", Slushkin.INDEX, 1);
        assertEquals(ProviderPage.Status.OK, page.status);
        assertEquals(8, page.items.size());
        assertTrue(page.hasMore);
        BookPOJO first = page.items.get(0);
        assertEquals("Трасса 0.0.0", first.getName());
        assertEquals(S + "/70933-trassa-000.html", first.getUrl());
        assertEquals(S + "/uploads/posts/2026-05/trassa-000.jpg", first.getPhoto());
        assertEquals("Стас Линь", first.getAutor());
        assertEquals(S + "/xfsearch/avtor/stas-lin/", first.getUrlAutor());
        assertEquals("Роман Ефимов", first.getArtist());
        assertEquals(S + "/xfsearch/ozv/roman-efimov/", first.getUrlArtist());
        assertEquals("10 ч. 22 мин.", first.getTime());
        assertEquals("Фантастика, Фэнтези", first.getGenre());
        assertEquals(S + "/zhanry/fantastika/", first.getUrlGenre());
        assertTrue(first.getDesc().startsWith("Будет ли спокойно ехать по знакомой трассе"));
        BookPOJO series = page.items.get(2);
        assertEquals("Сказания о Пастухе Богов 18", series.getName());
        assertEquals("Сказания о Пастухе Богов", series.getSeries());
        assertEquals(S + "/xfsearch/cikl/skazanija-o-pastuhe-bogov/", series.getUrlSeries());
        assertEquals("7 ч. 57 мин.", series.getTime());
        BookPOJO vij = page.items.get(3);
        assertEquals(VIJ, vij.getUrl());
        assertEquals("Юрий Гуржий, Дамир Мударисов, Паль Городенский", vij.getArtist());
        assertEquals(S + "/xfsearch/ozv/jurij-gurzhij/", vij.getUrlArtist());
        assertEquals("Ужасы, Мистика", vij.getGenre());
    }

    @Test
    public void laterAndLastPages() throws IOException {
        ProviderPage<BookPOJO> two = listing("page2.html", S + "/page/2/", 2);
        assertEquals(8, two.items.size());
        assertTrue(two.hasMore);
        BookPOJO first = two.items.get(0);
        assertEquals("Последняя книжная лавка в Лондоне", first.getName());
        assertEquals("Маделин Мартин", first.getAutor());
        assertEquals("Виктория Фёдорова", first.getArtist());
        assertEquals("9 ч. 54 мин.", first.getTime());
        assertNull(first.getSeries());

        ProviderPage<BookPOJO> last = listing("last.html", S + "/page/6772/", 6772);
        assertEquals(6, last.items.size());
        assertFalse(last.hasMore);
        BookPOJO end = last.items.get(5);
        assertEquals(S + "/16647-armageddon-podzemelja-smerti.html", end.getUrl());
        assertEquals("Дмитрий Хазанович (Дим Димыч)", end.getArtist());
        assertEquals("Этногенез. Армагеддон", end.getSeries());
        assertEquals("7 ч. 59 мин.", end.getTime());
    }

    @Test
    public void genreAuthorAndSeriesListings() throws IOException {
        ProviderPage<BookPOJO> genre = listing("genre.html", S + "/zhanry/uzhasy/", 1);
        assertEquals(8, genre.items.size());
        assertTrue(genre.hasMore);
        assertEquals(VIJ, genre.items.get(0).getUrl());

        ProviderPage<BookPOJO> author = listing("author.html", S + "/xfsearch/avtor/viktor-pelevin/", 1);
        assertEquals(8, author.items.size());
        assertTrue(author.hasMore);
        for (BookPOJO book : author.items) {
            assertEquals(book.getUrl(), "Виктор Пелевин", book.getAutor());
        }

        ProviderPage<BookPOJO> series = listing("series.html", S + "/xfsearch/cikl/skazanija-o-pastuhe-bogov/", 1);
        assertEquals(8, series.items.size());
        assertTrue(series.hasMore);
        assertEquals("Сказания о Пастухе Богов 18", series.items.get(0).getName());
        assertEquals("Сказания о Пастухе Богов", series.items.get(7).getSeries());
    }

    @Test
    public void searchHitsOneHitAndNothing() throws IOException {
        String url = parser.searchUrl(SearchUrls.Category.BOOKS, "Пушкин", 1);
        ProviderPage<BookPOJO> hits = parser.searchResults(fixture("search.html", url), 1);
        assertEquals(10, hits.items.size());
        assertTrue(hits.hasMore);
        assertEquals(S + "/70418-moskva-ja-dumal-o-tebe-pushkin-v-moskve.html", hits.items.get(0).getUrl());
        assertEquals(S + "/67211-skupoj-rycar.html", hits.items.get(9).getUrl());

        ProviderPage<BookPOJO> one = parser.searchResults(
                fixture("search-one.html", S + "/search/%D0%B2%D0%B8%D0%B9%202.0/"), 1);
        assertEquals(1, one.items.size());
        assertFalse(one.hasMore);
        assertEquals("Вий 2.0", one.items.get(0).getName());

        ProviderPage<BookPOJO> none = parser.searchResults(fixture("search-empty.html", S + "/search/x/"), 1);
        assertEquals(ProviderPage.Status.EMPTY, none.status);
        assertTrue(none.items.isEmpty());
        assertFalse(none.hasMore);
    }

    @Test
    public void genresAreTheTopRowsOfTheGenreBlock() throws IOException {
        List<GenrePOJO> genres = parser.genres(fixture("genres.html", Slushkin.GENRES), Slushkin.GENRES, 1).items;
        assertEquals(44, genres.size());
        assertEquals("Фантастика", genres.get(0).getName());
        assertEquals(S + "/zhanry/fantastika/", genres.get(0).getUrl());
        assertEquals("Тру-крайм", genres.get(43).getName());
        assertEquals(S + "/zhanry/true-crime/", genres.get(43).getUrl());
        for (GenrePOJO genre : genres) {
            assertTrue(genre.getUrl(), genre.getUrl().matches("https://slushkin\\.online/zhanry/[a-z0-9-]+/"));
        }
    }

    // ---------------------------------------------------------------- book page

    @Test
    public void bookDescriptionAndChapters() throws IOException {
        Document page = fixture("book.html", VIJ);
        DescriptionPOJO d = parser.description(page, VIJ);
        assertEquals("Вий 2.0", d.getTitle());
        assertEquals(S + "/uploads/posts/2026-05/vijj-20.jpg", d.getPoster());
        assertEquals("Юрий Молчан", d.getAutor());
        assertEquals(S + "/xfsearch/avtor/jurij-molchan/", d.getAutorUrl());
        assertEquals("Юрий Гуржий, Дамир Мударисов, Паль Городенский", d.getArtist());
        assertEquals(S + "/xfsearch/ozv/jurij-gurzhij/", d.getArtistUrl());
        assertEquals("7 ч. 27 мин.", d.getTime());
        assertEquals("Мистика, Ужасы", d.getGenre());
        assertEquals(S + "/zhanry/mistika/", d.getGenreUrl());
        assertEquals("1", d.getLike());
        assertTrue(d.getDescription().startsWith("Когда последний раз удивляла мысль"));
        assertTrue("paragraphs kept", d.getDescription().contains("\n"));

        BookPOJO card = parser.book(page, VIJ);
        assertEquals("Вий 2.0", card.getName());
        assertEquals("Юрий Молчан", card.getAutor());

        ArrayList<AudioPOJO> tracks = parser.tracks(page, VIJ);
        assertEquals(4, tracks.size());
        assertEquals("https://bookis.shop/1/audio/64604/vijj-20-0.mp3", tracks.get(0).getUrl());
        assertEquals("https://bookis.shop/1/audio/64604/vijj-20-3.mp3", tracks.get(3).getUrl());

        ArrayList<BookPOJO> similar = parser.recommendations(page, VIJ);
        assertEquals(8, similar.size());
        assertEquals(S + "/21785-vzryv-omut-klad-igra-protiv-vseh.html", similar.get(0).getUrl());
        assertEquals("Взрыв. Омут. Клад. Игра против всех", similar.get(0).getName());
        assertEquals(S + "/uploads/posts/2025-02/vzryv-omut-klad-igra-protiv-vsekh.jpg", similar.get(0).getPhoto());

        assertTrue("no series", parser.series(page, VIJ).isEmpty());
    }

    @Test
    public void aBookOfASeriesListsItsBooks() throws IOException {
        String url = S + "/70931-skazanija-o-pastuhe-bogov-18.html";
        Document page = fixture("book-series.html", url);
        DescriptionPOJO d = parser.description(page, url);
        assertEquals("Сказания о Пастухе Богов 18", d.getTitle());
        assertEquals("Сказания о Пастухе Богов", d.getSeries());
        assertEquals(S + "/xfsearch/cikl/skazanija-o-pastuhe-bogov/", d.getSeriesUrl());
        assertEquals("Adrenalin28", d.getArtist());
        assertEquals("7 ч. 57 мин.", d.getTime());

        ArrayList<SeriesPOJO> series = parser.series(page, url);
        assertEquals(18, series.size());
        assertEquals(url, series.get(0).getUrl());
        assertEquals("Сказания о Пастухе Богов 18", series.get(0).getName());
        assertEquals("18", series.get(0).getNumber());
        assertEquals(S + "/61443-skazanija-o-pastuhe-bogov-1.html", series.get(17).getUrl());
        assertEquals("1", series.get(17).getNumber());

        ArrayList<AudioPOJO> tracks = parser.tracks(page, url);
        assertEquals(4, tracks.size());
        assertEquals("https://bookis.shop/1/audio/64589/skazanija-o-pastukhe-bogov-18-3.mp3", tracks.get(3).getUrl());
    }

    @Test
    public void anOldBookPlaysFromTheOtherAudioHost() throws IOException {
        String url = S + "/16649-moroz-krasnyi-nos.html";
        Document page = fixture("book-old.html", url);
        DescriptionPOJO d = parser.description(page, url);
        assertEquals("Николай Некрасов", d.getAutor());
        assertEquals("Борис Горбатов", d.getArtist());
        assertEquals("1 ч. 2 мин.", d.getTime());
        assertEquals("Поэзия", d.getGenre());
        ArrayList<AudioPOJO> tracks = parser.tracks(page, url);
        assertEquals(1, tracks.size());
        assertEquals("https://bookis.life/1/audio/19889/moroz-krasnyi-nos-0.mp3", tracks.get(0).getUrl());
    }

    @Test
    public void aPageWithoutAPlayerIsAnError() throws IOException {
        try {
            parser.tracks(fixture("index.html", Slushkin.INDEX), Slushkin.INDEX);
            fail("no player");
        } catch (IllegalStateException expected) {
            // the model shows "no audio", never an empty chapter list
        }
    }
}
