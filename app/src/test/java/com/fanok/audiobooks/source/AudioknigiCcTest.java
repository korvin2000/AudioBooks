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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/** Pages saved from audioknigi.cc on 2026-11-21 (see src/test/resources/fixtures/audioknigi-cc). */
public class AudioknigiCcTest {

    private static final String S = AudioknigiCc.SERVER;
    private static final String BOOK = S + "/popadancy/9092444-beglyj.html";
    private static final String GENRE = S + "/popadancy/";
    private static final String AUTHOR = S + "/xfsearch/avtor/%D0%94%D0%BC%D0%B8%D1%82%D1%80%D0%B8%D0%B9%20"
            + "%D0%A8%D0%B8%D0%BC%D0%BE%D1%85%D0%B8%D0%BD/";
    private static final String SERIES = S + "/xfsearch/serie/%D0%9F%D0%BE%D0%B4%D0%BA%D0%B8%D0%B4%D1%8B%D1%88/";

    private final AudioknigiCc parser = new AudioknigiCc();

    private static InputStream open(String name) {
        InputStream in = AudioknigiCcTest.class.getResourceAsStream("/fixtures/audioknigi-cc/" + name);
        assertNotNull("missing fixture " + name, in);
        return in;
    }

    private static Document fixture(String name, String baseUri) throws IOException {
        try (InputStream in = open(name)) {
            return Jsoup.parse(in, "UTF-8", baseUri);
        }
    }

    private static String text(String name) throws IOException {
        try (InputStream in = open(name); Scanner s = new Scanner(in, StandardCharsets.UTF_8.name())) {
            return s.useDelimiter("\\A").next();
        }
    }

    private static List<String> urls(ProviderPage<BookPOJO> page) {
        List<String> out = new ArrayList<>();
        for (BookPOJO book : page.items) {
            out.add(book.getUrl());
        }
        return out;
    }

    // ---------------------------------------------------------------- URLs

    @Test
    public void pagesAppendPageN() {
        assertEquals(AudioknigiCc.INDEX, parser.pageUrl(AudioknigiCc.INDEX, 1));
        assertEquals(S + "/page/2/", parser.pageUrl(AudioknigiCc.INDEX, 2));
        assertEquals(S + "/fjentezi/litrpg/page/3/", parser.pageUrl(S + "/fjentezi/litrpg/", 3));
        assertEquals(AUTHOR + "page/2/", parser.pageUrl(AUTHOR, 2));
    }

    @Test
    public void sortsArePostedFormsOnTheSiteHost() {
        assertEquals(AudioknigiCc.MENU, parser.sorts(AudioknigiCc.INDEX));
        assertEquals("the plain index is already newest first", AudioknigiCc.INDEX,
                parser.sortUrl(Sort.NEW, AudioknigiCc.INDEX));
        String views = parser.sortUrl(Sort.POPULAR, AudioknigiCc.INDEX);
        assertEquals(S + "/?dlenewssortby=news_read&dledirection=desc"
                + "&set_new_sort=dle_sort_main&set_direction_sort=dle_direction_main", views);
        assertEquals(S + "/page/2/?dlenewssortby=news_read&dledirection=desc"
                + "&set_new_sort=dle_sort_main&set_direction_sort=dle_direction_main", parser.pageUrl(views, 2));
        assertTrue(parser.sortUrl(Sort.RATING, AudioknigiCc.INDEX).contains("dlenewssortby=rating&dledirection=desc"));
        assertTrue(parser.sortUrl(Sort.TITLE, AudioknigiCc.INDEX).contains("dlenewssortby=title&dledirection=asc"));
        assertEquals(views, parser.sortUrl(Sort.POPULAR, views));
        assertNull("comm_num is ignored by the server", parser.sortUrl(Sort.COMMENTS, AudioknigiCc.INDEX));
        assertNull(parser.sortUrl(Sort.TOP_100, AudioknigiCc.INDEX));
        for (Sort.Entry entry : AudioknigiCc.MENU) {
            String url = parser.sortUrl(entry.sort, AudioknigiCc.INDEX);
            assertTrue(url, url.startsWith(S + "/"));
            assertTrue(parser.pageUrl(url, 2).startsWith(S + "/page/2/"));
            assertTrue(url, parser.hasSort(url));
        }
    }

    @Test
    public void aGenreSortUsesTheIdOfTheSideMenu() throws IOException {
        assertEquals(GENRE, parser.genreOf(GENRE + "page/3/"));
        assertEquals(S + "/fjentezi/litrpg/", parser.genreOf(S + "/fjentezi/litrpg/page/2/"));
        assertNull(parser.genreOf(AudioknigiCc.INDEX));
        assertNull(parser.genreOf(S + "/page/2/"));
        assertNull(parser.genreOf(AUTHOR));
        assertNull(parser.genreOf(BOOK));
        assertNull(parser.genreOf(parser.searchUrl(SearchUrls.Category.BOOKS, "x", 1)));
        AudioknigiCc.learnCategories(fixture("genre-p1.html", GENRE));
        String inGenre = parser.inGenre(parser.sortUrl(Sort.TITLE, GENRE), GENRE);
        assertEquals(GENRE + "?dlenewssortby=title&dledirection=asc"
                + "&set_new_sort=dle_sort_cat_54&set_direction_sort=dle_direction_cat_54", inGenre);
        assertEquals(GENRE, parser.genreOf(inGenre));
        assertTrue(parser.hasSort(inGenre));
        assertEquals(S + "/fjentezi/litrpg/?dlenewssortby=rating&dledirection=desc"
                        + "&set_new_sort=dle_sort_cat_44&set_direction_sort=dle_direction_cat_44",
                parser.inGenre(parser.sortUrl(Sort.RATING, GENRE), S + "/fjentezi/litrpg/"));
    }

    @Test
    public void peopleSeriesAndSearchPagesDoNotSort() {
        assertFalse(parser.hasSort(AUTHOR));
        assertFalse(parser.hasSort(SERIES));
        assertFalse(parser.hasSort(parser.searchUrl(SearchUrls.Category.BOOKS, "x", 1)));
        assertFalse(parser.hasSort(BOOK));
    }

    @Test
    public void searchIsBooksOnly() {
        assertEquals(S + "/index.php?do=search&subaction=search&story=%D0%B4%D1%80%D0%B0%D0%BA%D0%BE%D0%BD"
                + "&search_start=6&result_from=51&full_search=0",
                parser.searchUrl(SearchUrls.Category.BOOKS, "дракон", 6));
        assertNull(parser.searchUrl(SearchUrls.Category.AUTHORS, "x", 1));
        assertNull(parser.searchUrl(SearchUrls.Category.PERFORMERS, "x", 1));
    }

    // ---------------------------------------------------------------- listings

    @Test
    public void indexCardsCarryEveryField() throws IOException {
        ProviderPage<BookPOJO> page = AudioknigiCc.parseListing(fixture("index-p1.html", AudioknigiCc.INDEX), 1);
        assertEquals(ProviderPage.Status.OK, page.status);
        assertEquals(10, page.items.size());
        assertTrue("335 pages", page.hasMore);
        BookPOJO first = page.items.get(0);
        assertEquals("Беглый", first.getName());
        assertEquals(BOOK, first.getUrl());
        assertEquals(S + "/uploads/posts/2026-11/1795180853_12.jpg", first.getPhoto());
        assertEquals("Дмитрий Шимохин, Виктор Коллингвуд", first.getAutor());
        assertEquals(AUTHOR, first.getUrlAutor());
        assertEquals("Вадим Кривошеев", first.getArtist());
        assertEquals(S + "/xfsearch/ispolnitel/%D0%92%D0%B0%D0%B4%D0%B8%D0%BC%20"
                + "%D0%9A%D1%80%D0%B8%D0%B2%D0%BE%D1%88%D0%B5%D0%B5%D0%B2/", first.getUrlArtist());
        assertEquals("Попаданцы", first.getGenre());
        assertEquals(GENRE, first.getUrlGenre());
        assertEquals("7 ч. 43 мин.", first.getTime());
        assertTrue(first.getDesc().startsWith("Вторая книга цикла «Подкидыш»"));
        assertTrue(parser.listsDuration(AudioknigiCc.INDEX));
        for (BookPOJO book : page.items) {
            assertTrue(book.getUrl(), book.getUrl().startsWith(S + "/") && book.getUrl().endsWith(".html"));
            assertFalse(book.getUrl(), book.getTime().isEmpty());
        }
    }

    @Test
    public void laterAndLastIndexPages() throws IOException {
        ProviderPage<BookPOJO> two = AudioknigiCc.parseListing(fixture("index-p2.html", S + "/page/2/"), 2);
        assertEquals(10, two.items.size());
        assertTrue(two.hasMore);
        assertEquals("Снегурочка", two.items.get(0).getName());
        ProviderPage<BookPOJO> last = AudioknigiCc.parseListing(fixture("index-last.html", S + "/page/335/"), 335);
        assertEquals(9, last.items.size());
        assertFalse(last.hasMore);
        BookPOJO old = last.items.get(0);
        assertEquals("Мы с тобою звери", old.getName());
        assertEquals("Моран Джурич", old.getAutor());
        assertEquals("АБАДДОН", old.getArtist());
        assertEquals("Ужасы и мистика", old.getGenre());
        assertEquals("27 мин.", old.getTime());
        assertEquals("Сталкер Лунь", last.items.get(8).getName());
    }

    @Test
    public void featuredPeopleCategoriesAreNoGenre() throws IOException {
        // the "Авторы" category: "Авторы / Стивен Кинг" and "Фэнтези / Стивен Кинг" under its cards
        ProviderPage<BookPOJO> page = AudioknigiCc.parseListing(
                fixture("category-avtory.html", S + "/avtory/"), 1);
        assertEquals(10, page.items.size());
        assertTrue("28 books", page.hasMore);
        assertEquals("Побег из Шоушенка", page.items.get(0).getName());
        assertNull(page.items.get(0).getUrlGenre());
        assertEquals("Фэнтези", page.items.get(1).getGenre());
        assertEquals(S + "/fjentezi/", page.items.get(1).getUrlGenre());
    }

    @Test
    public void genreListing() throws IOException {
        ProviderPage<BookPOJO> page = AudioknigiCc.parseListing(fixture("genre-p1.html", GENRE), 1);
        assertEquals(10, page.items.size());
        assertTrue("137 pages", page.hasMore);
        assertEquals("Беглый", page.items.get(0).getName());
        assertEquals("Псы Господни 4", page.items.get(9).getName());
    }

    @Test
    public void sortedListingsAreWhatTheServerSent() throws IOException {
        String views = parser.sortUrl(Sort.POPULAR, AudioknigiCc.INDEX);
        ProviderPage<BookPOJO> popular = AudioknigiCc.parseListing(fixture("sort-views.html", views), 1);
        assertEquals(S + "/klasika/1616530-vojna-i-mir.html", popular.items.get(0).getUrl());
        assertEquals("Левиафан", popular.items.get(9).getName());
        assertTrue(popular.hasMore);
        ProviderPage<BookPOJO> rating = AudioknigiCc.parseListing(
                fixture("sort-rating.html", parser.sortUrl(Sort.RATING, AudioknigiCc.INDEX)), 1);
        assertEquals("Гарри Поттер и Дары Смерти", rating.items.get(9).getName());
        String title = parser.sortUrl(Sort.TITLE, AudioknigiCc.INDEX);
        ProviderPage<BookPOJO> byTitle = AudioknigiCc.parseListing(fixture("sort-title.html", title), 1);
        assertEquals("(Не)желанный брак, или Сделка с оборотнем", byTitle.items.get(0).getName());
        ProviderPage<BookPOJO> byTitle2 = AudioknigiCc.parseListing(
                fixture("sort-title-p2.html", parser.pageUrl(title, 2)), 2);
        assertEquals("1970", byTitle2.items.get(0).getName());
        assertTrue(byTitle2.hasMore);
        ProviderPage<BookPOJO> genreTitle = AudioknigiCc.parseListing(fixture("sort-genre-title.html", GENRE), 1);
        assertEquals("1917: Вперед, Империя!", genreTitle.items.get(0).getName());
        for (BookPOJO book : genreTitle.items) {
            assertEquals(GENRE, book.getUrlGenre());
        }
    }

    @Test
    public void personListing() throws IOException {
        ProviderPage<BookPOJO> page = AudioknigiCc.parseListing(fixture("author.html", AUTHOR), 1);
        assertEquals(2, page.items.size());
        assertFalse(page.hasMore);
        assertEquals(BOOK, page.items.get(0).getUrl());
        assertEquals(S + "/9092431-katorzhnik.html", page.items.get(1).getUrl());
    }

    // ---------------------------------------------------------------- search

    @Test
    public void searchWithHits() throws IOException {
        ProviderPage<BookPOJO> page = parser.searchResults(fixture("search-beglyj.html",
                parser.searchUrl(SearchUrls.Category.BOOKS, "Беглый", 1)), 1);
        assertEquals(3, page.items.size());
        assertFalse(page.hasMore);
        assertEquals(BOOK, page.items.get(0).getUrl());
        assertEquals("Беглый огонь", page.items.get(2).getName());
        assertEquals("12 ч. 33 мин.", page.items.get(2).getTime());
    }

    @Test
    public void searchCountStopsAtFiftyWhileThePagesGoOn() throws IOException {
        ProviderPage<BookPOJO> one = parser.searchResults(fixture("search-drakon-p1.html",
                parser.searchUrl(SearchUrls.Category.BOOKS, "дракон", 1)), 1);
        assertEquals(10, one.items.size());
        assertTrue(one.hasMore);
        ProviderPage<BookPOJO> six = parser.searchResults(fixture("search-drakon-p6.html",
                parser.searchUrl(SearchUrls.Category.BOOKS, "дракон", 6)), 6);
        assertEquals(10, six.items.size());
        assertTrue("the count says 50 while page 6 is full", six.hasMore);
        assertEquals("Фея в Академии Чёрного дракона", six.items.get(0).getName());
    }

    @Test
    public void searchWithoutHitsIsEmpty() throws IOException {
        ProviderPage<BookPOJO> page = parser.searchResults(fixture("search-none.html",
                parser.searchUrl(SearchUrls.Category.BOOKS, "qzxwvjkq", 1)), 1);
        assertEquals(ProviderPage.Status.EMPTY, page.status);
        assertFalse(page.hasMore);
    }

    // ---------------------------------------------------------------- genres

    @Test
    public void genresAreTheSideMenuWithoutFeaturedPeople() throws IOException {
        ProviderPage<GenrePOJO> page = parser.genres(fixture("index-p1.html", AudioknigiCc.GENRES),
                AudioknigiCc.GENRES, 1);
        assertEquals(22, page.items.size());
        assertFalse(page.hasMore);
        assertEquals("Фэнтези", page.items.get(0).getName());
        assertEquals(S + "/fjentezi/", page.items.get(0).getUrl());
        assertEquals("ЛитРПГ", page.items.get(1).getName());
        assertEquals(S + "/fjentezi/litrpg/", page.items.get(1).getUrl());
        assertEquals("S.T.A.L.K.E.R", page.items.get(21).getName());
        for (GenrePOJO genre : page.items) {
            assertFalse(genre.getUrl(),
                    genre.getUrl().contains("/avtory/") || genre.getUrl().contains("/ispolniteli/"));
            assertEquals(genre.getUrl(), parser.genreOf(genre.getUrl()));
        }
    }

    // ---------------------------------------------------------------- book page

    @Test
    public void bookPage() throws IOException {
        Document doc = fixture("book.html", BOOK);
        DescriptionPOJO d = parser.description(doc, BOOK);
        assertEquals("Беглый", d.getTitle());
        assertEquals(S + "/uploads/posts/2026-11/1795180853_12.jpg", d.getPoster());
        assertEquals("Дмитрий Шимохин, Виктор Коллингвуд", d.getAutor());
        assertEquals(AUTHOR, d.getAutorUrl());
        assertEquals("Вадим Кривошеев", d.getArtist());
        assertTrue(d.getArtistUrl().startsWith(S + "/xfsearch/ispolnitel/"));
        assertEquals("Подкидыш", d.getSeries());
        assertEquals(SERIES, d.getSeriesUrl());
        assertEquals("Попаданцы", d.getGenre());
        assertEquals(GENRE, d.getGenreUrl());
        assertEquals("7 ч. 43 мин.", d.getTime());
        assertTrue(d.getDescription().startsWith("Вторая книга цикла «Подкидыш»"));
        assertTrue("paragraphs kept", d.getDescription().contains("\nПогоня по Забайкалью"));
        BookPOJO card = parser.book(doc, BOOK);
        assertEquals("Беглый", card.getName());
        assertEquals(BOOK, card.getUrl());
        ArrayList<BookPOJO> similar = parser.recommendations(doc, BOOK);
        assertEquals(5, similar.size());
        assertEquals("Точка отсчета", similar.get(0).getName());
        assertEquals(S + "/fantastika/1637972-tochka-otscheta.html", similar.get(0).getUrl());
        assertTrue(similar.get(0).getPhoto().startsWith(S + "/uploads/posts/2021-04/"));
    }

    @Test
    public void playlistIsAJsonListOnTheSharedHost() throws IOException {
        Document doc = fixture("book.html", BOOK);
        String file = Players.playerjsFile(doc);
        assertEquals("https://s1.audiokniga-online.net/audiobooks/"
                + "shimohin-dmitrij-kollingvud-viktor-podkidysh-02-beglyj/playlist.txt.php", file);
        String folder = "https://s1.audiokniga-online.net/f/audiobooks222547/"
                + "shimohin-dmitrij-kollingvud-viktor-podkidysh-02-beglyj/";
        ArrayList<AudioPOJO> chapters = AudioknigaOnline.encoded(
                Players.parsePlayerjsPlaylist(text("playlist.json"), folder + "playlist.txt.php", "Беглый"));
        assertEquals(26, chapters.size());
        assertEquals("001", chapters.get(0).getName());
        assertEquals(folder + "001.mp3", chapters.get(0).getUrl());
        assertEquals(folder + "026.mp3", chapters.get(25).getUrl());
        assertTrue(parser.hosts().isEmpty());
        assertTrue(parser.refererHosts().isEmpty());
        assertNull(parser.mediaHeaders(BOOK));
    }

    @Test
    public void anOldEntryHasAnInlineListOfItsOwnFiles() throws IOException {
        String url = S + "/uzhasy-mistika/1903336-mogila-v-lesu.html";
        Document doc = fixture("book-old-dead.html", url);
        DescriptionPOJO d = parser.description(doc, url);
        assertEquals("Могила в лесу", d.getTitle());
        assertEquals("", d.getAutor());
        assertEquals("Андрей Смскин", d.getArtist());
        assertEquals("Ужасы и мистика", d.getGenre());
        assertEquals("25 мин.", d.getTime());
        assertEquals("6", d.getLike());
        assertEquals("8", d.getDisLike());
        assertEquals("", d.getSeries());
        // the other 50 items of the list are empty; this one answers 404 (dead audio the page cannot tell)
        ArrayList<AudioPOJO> chapters = parser.tracks(doc, url);
        assertEquals(1, chapters.size());
        assertEquals("Страшная история на ночь", chapters.get(0).getName());
        assertEquals(S + "/uploads/files/2021-05/1621357435_strashnye-istorii-na-noch_-mogila-v-lesu_-strashnye-"
                + "istorii_-creepypasta_-uzhasy.m4a", chapters.get(0).getUrl());
    }

    @Test
    public void aLitresTrialBookIsRemoved() throws IOException {
        String url = S + "/fantastika/1388486-nepogrebennye.html";
        Document doc = fixture("book-litres.html", url);
        DescriptionPOJO d = parser.description(doc, url);
        assertEquals("Непогребенные", d.getTitle());
        assertEquals("Сергей Антонов", d.getAutor());
        assertEquals("Андрей Кравец", d.getArtist());
        assertEquals("Темные туннели", d.getSeries());
        // the page still has Playerjs; its playlist answers 404
        assertNotNull(doc.selectFirst("#playerjs1"));
        try {
            parser.tracks(doc, url);
            fail("LitRes trial only");
        } catch (BookRemovedException expected) {
            assertEquals(url, expected.getMessage());
        }
    }

    @Test
    public void aPageWithoutPlayerIsAnError() throws IOException {
        Document doc = fixture("index-p1.html", AudioknigiCc.INDEX);
        try {
            parser.tracks(doc, BOOK);
            fail("no player");
        } catch (IllegalStateException expected) {
            // no Playerjs on the page
        }
    }

    @Test
    public void seriesListingInSeriesOrder() throws IOException {
        ArrayList<SeriesPOJO> books = AudioknigiCc.inSeriesOrder(
                AudioknigiCc.parseSeries(fixture("series.html", SERIES)));
        assertEquals(2, books.size());
        assertEquals("Каторжник", books.get(0).getName());
        assertEquals("1", books.get(0).getNumber());
        assertEquals(S + "/9092431-katorzhnik.html", books.get(0).getUrl());
        assertEquals("Беглый", books.get(1).getName());
        assertEquals("2", books.get(1).getNumber());
        assertEquals(S + "/uploads/posts/2026-11/1795180853_12.jpg", books.get(1).getPhoto());
    }
}
