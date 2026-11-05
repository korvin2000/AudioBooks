package com.fanok.audiobooks.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fanok.audiobooks.BookPageFixtures;
import com.fanok.audiobooks.BookRemovedException;
import com.fanok.audiobooks.net.JsonPage;
import com.fanok.audiobooks.pojo.AudioPOJO;
import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.pojo.DescriptionPOJO;
import com.fanok.audiobooks.pojo.GenrePOJO;
import com.fanok.audiobooks.pojo.SeriesPOJO;
import com.fanok.audiobooks.search.ProviderPage;
import com.fanok.audiobooks.search.SearchUrls;
import com.google.gson.JsonObject;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** bookinfo.net API answers saved on 2026-11-04 (see src/test/resources/fixtures/bookinfo). */
public class BookinfoTest {

    private static final Bookinfo SITE = new Bookinfo();
    private static final String S = Bookinfo.SERVER;
    private static final String API = S + "/api/v1/";
    private static final String INDEX = Bookinfo.INDEX;
    private static final String GENRE = INDEX + "&search%5Bgenre_alias%5D=fentezi";
    private static final String AUTHOR = INDEX + "&search%5Bauthor_alias%5D=stiven-king";
    private static final String READER = INDEX + "&search%5Breader_alias%5D=gidra";
    private static final String SERIES = INDEX + "&search%5Bseries_alias%5D=master-trav&order=id";
    private static final String BOOK = S + "/fentezi/robbi-makniven/patent-na-smert";
    private static final String CHAPTERS = S + "/fentezi/vanya-mordorskiy/master-trav-7";

    private static JsonPage json(String name, String url) throws IOException {
        return BookPageFixtures.json("bookinfo/" + name, url);
    }

    private static ProviderPage<BookPOJO> listing(String name, String url) throws IOException {
        return SITE.listing(json(name, url), url, 1);
    }

    // ---------------------------------------------------------------- listings

    @Test
    public void indexCardsAndPages() throws IOException {
        ProviderPage<BookPOJO> page = listing("listing-index-p1.json", INDEX);
        assertEquals(ProviderPage.Status.OK, page.status);
        assertEquals(20, page.items.size());
        assertTrue("1805 pages", page.hasMore);
        BookPOJO first = page.items.get(0);
        assertEquals(S + "/fentezi/vanya-mordorskiy/master-trav-7", first.getUrl());
        assertEquals("Мастер Трав 7", first.getName());
        assertEquals(S + "/books/180933/cover.webp", first.getPhoto());
        assertEquals("Ваня Мордорский", first.getAutor());
        assertEquals(INDEX + "&search%5Bauthor_alias%5D=vanya-mordorskiy", first.getUrlAutor());
        assertEquals("Фэнтези", first.getGenre());
        assertEquals(GENRE, first.getUrlGenre());
        assertEquals("5.0", first.getReting());
        BookPOJO unrated = page.items.get(19);
        assertEquals(S + "/fentezi/paolo-bachigalupi/chelovek-s-zhyoltoy-kartochkoy", unrated.getUrl());
        assertEquals("no votes, no rating", "0", unrated.getReting());

        String two = SITE.pageUrl(INDEX, 2);
        assertEquals(INDEX + "&page=2", two);
        ProviderPage<BookPOJO> second = listing("listing-index-p2.json", two);
        assertEquals(20, second.items.size());
        assertTrue(second.hasMore);
        assertEquals(S + "/roman-proza/irvin-shou/zhivuschie-v-drugih-gorodah", second.items.get(0).getUrl());

        ProviderPage<BookPOJO> last = listing("listing-index-last.json", SITE.pageUrl(INDEX, 1805));
        assertEquals(17, last.items.size());
        assertFalse(last.hasMore);
        assertEquals(S + "/boeviki-ostrosyuzhetnaya-literatura/andrey-ilin/kogda-shataetsya-tron",
                last.items.get(16).getUrl());

        ProviderPage<BookPOJO> beyond = listing("listing-index-beyond.json", SITE.pageUrl(INDEX, 1806));
        assertEquals(ProviderPage.Status.EMPTY, beyond.status);
        assertFalse(beyond.hasMore);
    }

    /** A card without its page's parts is dropped, and the page keeps its next-page evidence. */
    @Test
    public void unusableCardsAreDroppedButThePageGoesOn() {
        String text = "{\"books\":[{\"id\":1,\"title\":\"Без жанра\",\"title_alias\":\"bez-zhanra\",\"genres\":[],"
                + "\"main_genre\":{\"name_alias\":null},\"main_author\":{\"name_alias\":\"x\"}}],"
                + "\"meta\":{\"all\":40,\"page\":1,\"total_pages\":2}}";
        ProviderPage<BookPOJO> page = Bookinfo.parseListing(JsonPage.parse(INDEX, text));
        assertEquals(ProviderPage.Status.OK, page.status);
        assertTrue(page.items.isEmpty());
        assertTrue(page.hasMore);
    }

    @Test
    public void errorsAreNotEmptyPages() throws IOException {
        String refused = "{\"errors\":{\"order\":[{\"error\":\"invalid_order_column\"}]}}";
        try {
            Bookinfo.parseListing(JsonPage.parse(INDEX + "&order=-duration", refused));
            fail("a refused query is not a listing");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("books"));
        }
        try {
            Bookinfo.parseListing(BookPageFixtures.empty("bookinfo/x.html", S + "/"));
            fail("HTML is not a listing");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("not a JSON page"));
        }
    }

    @Test
    public void sortedListings() throws IOException {
        ProviderPage<BookPOJO> rating = listing("listing-rating-p1.json", INDEX + "&order=-rating");
        assertEquals(S + "/uzhasy-mistika/tatyana-gulevich/sosed", rating.items.get(0).getUrl());
        assertEquals("5.1", rating.items.get(0).getReting());
        assertTrue(rating.hasMore);
        ProviderPage<BookPOJO> title = listing("listing-title-p1.json", INDEX + "&order=title");
        assertEquals("0,05. Доказательная медицина от магии до поисков бессмертия", title.items.get(0).getName());
        assertEquals("108 бусин. Книга 2", title.items.get(19).getName());
        ProviderPage<BookPOJO> genreRating = listing("genre-rating.json", GENRE + "&order=-rating");
        assertEquals(S + "/fentezi/e-e-dok-smit/mest-robotov", genreRating.items.get(0).getUrl());
        assertEquals("the empty co-author is left out", "Александр Зубенко", genreRating.items.get(19).getAutor());
    }

    @Test
    public void genreAuthorReaderAndSeriesListings() throws IOException {
        ProviderPage<BookPOJO> genre = listing("genre-p1.json", GENRE);
        assertEquals(20, genre.items.size());
        assertTrue("431 pages", genre.hasMore);
        assertEquals(S + "/fentezi/mariya-kozlova/rasskazy", genre.items.get(19).getUrl());
        assertEquals(S + "/fentezi/aleksandr-zubenko/inkubatsiya",
                listing("genre-p2.json", SITE.pageUrl(GENRE, 2)).items.get(0).getUrl());

        ProviderPage<BookPOJO> author = listing("author.json", AUTHOR);
        assertEquals(20, author.items.size());
        assertTrue("197 books", author.hasMore);
        assertEquals(S + "/uzhasy-mistika/stiven-king/sezon-dozhdey", author.items.get(0).getUrl());

        ProviderPage<BookPOJO> reader = listing("reader.json", READER);
        assertEquals(14, reader.items.size());
        assertFalse(reader.hasMore);
        assertEquals(BOOK, reader.items.get(0).getUrl());

        ProviderPage<BookPOJO> series = listing("series.json", SERIES);
        assertEquals(5, series.items.size());
        assertFalse(series.hasMore);
        assertEquals("oldest first", "Мастер Трав I", series.items.get(0).getName());
        assertEquals(CHAPTERS, series.items.get(4).getUrl());
    }

    @Test
    public void genres() throws IOException {
        ArrayList<GenrePOJO> genres = Bookinfo.parseGenres(json("genres.json", Bookinfo.GENRES));
        assertEquals("50 less Зомби-апокалипсис, which has no books", 49, genres.size());
        assertEquals("EVE online", genres.get(0).getName());
        assertEquals(INDEX + "&search%5Bgenre_alias%5D=eve-online", genres.get(0).getUrl());
        assertEquals(4, genres.get(0).getReting());
        GenrePOJO fantasy = genres.get(45);
        assertEquals("Фэнтези", fantasy.getName());
        assertEquals(GENRE, fantasy.getUrl());
        assertEquals(11732, fantasy.getReting());
        assertEquals("Юмористическая литература", genres.get(48).getName());
        for (GenrePOJO genre : genres) {
            assertFalse(genre.getName(), genre.getName().equals("Зомби-апокалипсис"));
        }
        assertFalse(SITE.genres(json("genres.json", Bookinfo.GENRES), Bookinfo.GENRES, 1).hasMore);
    }

    // ---------------------------------------------------------------- search

    @Test
    public void search() throws IOException {
        String master = SITE.searchUrl(SearchUrls.Category.BOOKS, "мастер", 1);
        assertEquals(INDEX + "&search%5Btitle%5D=%D0%BC%D0%B0%D1%81%D1%82%D0%B5%D1%80&page=1", master);
        ProviderPage<BookPOJO> first = SITE.searchResults(json("search-p1.json", master), 1);
        assertEquals(20, first.items.size());
        assertTrue("4 pages", first.hasMore);
        assertEquals(CHAPTERS, first.items.get(0).getUrl());
        assertEquals("Борис Кригер", first.items.get(19).getAutor());

        String two = SITE.searchUrl(SearchUrls.Category.BOOKS, "мастер", 2);
        assertEquals(two, SITE.pageUrl(master, 2));
        ProviderPage<BookPOJO> second = SITE.searchResults(json("search-p2.json", two), 2);
        assertEquals(S + "/fentezi/garri-garrison/master-na-vse-ruki", second.items.get(0).getUrl());
        assertTrue(second.hasMore);

        ProviderPage<BookPOJO> none = SITE.searchResults(
                json("search-none.json", SITE.searchUrl(SearchUrls.Category.BOOKS, "zzzxqw", 1)), 1);
        assertEquals(ProviderPage.Status.EMPTY, none.status);
        assertFalse(none.hasMore);

        assertTrue("a search is not re-sorted", SITE.sorts(master).isEmpty());
        assertEquals(INDEX + "&search%5Btitle%5D=%D0%9C%D0%B0%D1%81%D1%82%D0%B5%D1%80+%D0%B8+%D0%9C%D0%B0%D1%80"
                        + "%D0%B3%D0%B0%D1%80%D0%B8%D1%82%D0%B0&page=1",
                SITE.searchUrl(SearchUrls.Category.BOOKS, "Мастер и Маргарита", 1));
    }

    @Test
    public void otherSourceSearchByTitle() {
        BookPOJO book = new BookPOJO();
        book.setName("Мастер и Маргарита");
        book.setAutor("Михаил Булгаков");
        book.setArtist("Олег Ефремов");
        assertEquals("the site searches titles only", "Мастер и Маргарита", SITE.otherSourceTerms(book));
    }

    // ---------------------------------------------------------------- people

    @Test
    public void people() throws IOException {
        assertEquals(Bookinfo.AUTHORS + "&page=2", SITE.pageUrl(Bookinfo.AUTHORS, 2));
        ProviderPage<GenrePOJO> authors = SITE.people(json("people-authors-p1.json", Bookinfo.AUTHORS),
                Bookinfo.AUTHORS, 1, false);
        assertEquals("the nameless author is left out", 99, authors.items.size());
        assertTrue("621 pages", authors.hasMore);
        assertEquals("Неизвестный автор", authors.items.get(0).getName());
        assertEquals(INDEX + "&search%5Bauthor_alias%5D=neizvestnyy-avtor", authors.items.get(0).getUrl());
        assertEquals("published books", 1709, authors.items.get(0).getReting());
        assertEquals("Стивен Кинг", authors.items.get(2).getName());
        assertEquals(AUTHOR, authors.items.get(2).getUrl());
        ProviderPage<GenrePOJO> second = Bookinfo.parsePeople(
                json("people-authors-p2.json", SITE.pageUrl(Bookinfo.AUTHORS, 2)), false);
        assertEquals("authors without published books are left out", 97, second.items.size());
        assertTrue(second.hasMore);

        ProviderPage<GenrePOJO> readers = SITE.people(json("people-readers-p1.json", Bookinfo.READERS),
                Bookinfo.READERS, 1, true);
        assertEquals(100, readers.items.size());
        assertTrue("88 pages", readers.hasMore);
        assertEquals("Олег Булдаков", readers.items.get(0).getName());
        assertEquals(INDEX + "&search%5Breader_alias%5D=oleg-buldakov", readers.items.get(0).getUrl());
        assertEquals(1079, readers.items.get(0).getReting());

        String king = SITE.searchUrl(SearchUrls.Category.AUTHORS, "Кинг", 1);
        assertEquals(API + "authors?search%5Bname%5D=%D0%9A%D0%B8%D0%BD%D0%B3&order=-books_count&per=100&page=1",
                king);
        ProviderPage<GenrePOJO> found = SITE.searchPeople(json("people-authors-search.json", king), 1, false);
        assertEquals("61 found, 42 with published books", 42, found.items.size());
        assertFalse(found.hasMore);
        assertEquals(AUTHOR, found.items.get(0).getUrl());

        String buldakov = SITE.searchUrl(SearchUrls.Category.PERFORMERS, "Булдаков", 1);
        assertTrue(buldakov, buldakov.startsWith(API + "readers?search%5Bname%5D="));
        ProviderPage<GenrePOJO> readersFound = SITE.searchPeople(json("people-readers-search.json", buldakov), 1,
                true);
        assertEquals(2, readersFound.items.size());
        assertEquals(INDEX + "&search%5Breader_alias%5D=buldakov-oleg", readersFound.items.get(1).getUrl());

        ProviderPage<GenrePOJO> none = SITE.searchPeople(json("people-none.json",
                SITE.searchUrl(SearchUrls.Category.AUTHORS, "zzzxqw", 1)), 1, false);
        assertEquals(ProviderPage.Status.EMPTY, none.status);
    }

    // ---------------------------------------------------------------- sorts

    /** As BooksPresenter does it ({@code ListingFilters.sorted}): the site-wide sort, then narrowed to the listing. */
    private static String sorted(Sort sort, String listing) {
        String url = SITE.sortUrl(sort, listing);
        String genre = SITE.genreOf(listing);
        return url.equals(listing) || genre == null ? url : SITE.inGenre(url, genre);
    }

    @Test
    public void sortMenus() {
        List<Sort> menu = Arrays.asList(Sort.NEW, Sort.RATING, Sort.TITLE);
        for (String listing : new String[]{INDEX, GENRE, AUTHOR, READER}) {
            List<Sort.Entry> entries = SITE.sorts(listing);
            assertEquals(listing, menu.size(), entries.size());
            for (int i = 0; i < menu.size(); i++) {
                assertEquals(listing, menu.get(i), entries.get(i).sort);
            }
            for (Sort sort : menu) {
                String url = sorted(sort, listing);
                assertEquals(url + " keeps its menu", 3, SITE.sorts(url).size());
                assertEquals(url + " " + sort, url, sorted(sort, url));
            }
        }
        assertTrue("a series keeps its order", SITE.sorts(SERIES).isEmpty());
        assertTrue(SITE.sorts(Bookinfo.AUTHORS).isEmpty());
        assertTrue(SITE.sorts(Bookinfo.GENRES).isEmpty());
        assertNull(SITE.sortUrl(Sort.POPULAR, INDEX));
        assertNull(SITE.sortUrl(Sort.DURATION, INDEX));
        assertFalse("the cards carry no duration", SITE.listsDuration(INDEX));
    }

    @Test
    public void sortsSiteWideAndWithinTheListing() {
        String rating = INDEX + "&order=-rating";
        assertEquals(rating, sorted(Sort.RATING, INDEX));
        assertEquals(INDEX + "&order=title", sorted(Sort.TITLE, rating));
        assertEquals(INDEX, sorted(Sort.NEW, SITE.pageUrl(rating, 3)));
        assertNull(SITE.genreOf(rating));

        assertEquals(GENRE, SITE.genreOf(GENRE + "&order=title&page=3"));
        assertEquals(GENRE + "&order=-rating", sorted(Sort.RATING, GENRE + "&order=title"));
        assertEquals(GENRE, sorted(Sort.NEW, GENRE + "&order=-rating"));
        assertEquals(AUTHOR + "&order=title", sorted(Sort.TITLE, AUTHOR));
        assertEquals(READER + "&order=-rating", sorted(Sort.RATING, READER + "&order=title"));

        assertEquals(rating + "&page=2", SITE.pageUrl(rating, 2));
        assertEquals(rating + "&page=3", SITE.pageUrl(rating + "&page=2", 3));
        assertEquals(rating, SITE.pageUrl(rating + "&page=2", 1));
        assertEquals(GENRE + "&order=-rating&page=2", SITE.pageUrl(sorted(Sort.RATING, GENRE), 2));
    }

    // ---------------------------------------------------------------- book

    @Test
    public void description() throws IOException {
        DescriptionPOJO d = SITE.description(json("book.json", API + "books/patent-na-smert/robbi-makniven"), BOOK);
        assertEquals("Патент на смерть", d.getTitle());
        assertEquals(S + "/books/180928/cover.webp", d.getPoster());
        assertEquals("Робби Макнивен", d.getAutor());
        assertEquals(INDEX + "&search%5Bauthor_alias%5D=robbi-makniven", d.getAutorUrl());
        assertEquals("Gidra", d.getArtist());
        assertEquals(READER, d.getArtistUrl());
        assertEquals("Фэнтези", d.getGenre());
        assertEquals(GENRE, d.getGenreUrl());
        assertEquals("Warhammer 40000. Кархародоны (1)", d.getSeries());
        assertEquals(INDEX + "&search%5Bseries_alias%5D=warhammer-40000-karharodony-2&order=id", d.getSeriesUrl());
        assertEquals("4932 s", "1 ч. 22 мин.", d.getTime());
        assertEquals(BookPOJO.formatRating(5.0f, "13"), d.getReiting());
        assertTrue(d.getDescription(), d.getDescription().startsWith("На заброшенной луне, утопающей в джунглях"));

        BookPOJO card = SITE.book(json("book.json", BOOK), BOOK);
        assertEquals(BOOK, card.getUrl());
        assertEquals("Патент на смерть", card.getName());
        assertEquals("Gidra", card.getArtist());
        assertEquals("Фэнтези", card.getGenre());

        DescriptionPOJO many = SITE.description(json("book-fragment.json", API + "books/kak-chitat-knigi/monika-vud"),
                S + "/roman-proza/monika-vud/kak-chitat-knigi");
        assertEquals("Алексей Багдасаров, Наталия Казначеева, Марина Лисовец", many.getArtist());
        assertEquals(INDEX + "&search%5Breader_alias%5D=aleksey-bagdasarov", many.getArtistUrl());
        assertEquals("unrated", "", many.getReiting());
        assertEquals("no series", "", many.getSeries());
        assertEquals("", many.getSeriesUrl());

        assertEquals("an empty record has no title", "",
                SITE.description(JsonPage.parse(BOOK, "{}"), BOOK).getTitle());
    }

    @Test
    public void tracks() throws IOException {
        String api = API + "books/master-trav-7/vanya-mordorskiy";
        ArrayList<AudioPOJO> tracks = SITE.tracks(json("book-chapters.json", api), api);
        assertEquals(21, tracks.size());
        AudioPOJO first = tracks.get(0);
        assertEquals("Глава 1", first.getName());
        assertEquals(S + "/audio_files/1443389/file.mp3", first.getUrl());
        assertEquals(1628, first.getTime());
        assertEquals("Мастер Трав 7", first.getBookName());
        assertEquals("Глава 21", tracks.get(20).getName());

        ArrayList<AudioPOJO> one = SITE.tracks(json("book.json", API + "books/patent-na-smert/robbi-makniven"),
                API + "books/patent-na-smert/robbi-makniven");
        assertEquals(1, one.size());
        assertEquals(S + "/audio_files/1443373/file.mp3", one.get(0).getUrl());
        assertEquals(4932, one.get(0).getTime());
    }

    @Test
    public void litresFragmentIsRemovedAndABookWithoutAudioHasNoChapters() throws IOException {
        String fragment = API + "books/kak-chitat-knigi/monika-vud";
        try {
            SITE.tracks(json("book-fragment.json", fragment), fragment);
            fail("a 10-minute LitRes trial is not the book");
        } catch (BookRemovedException expected) {
            // the book's own page
        }
        String empty = API + "books/breksten/k-linn";
        try {
            SITE.tracks(json("book-noaudio.json", empty), empty);
            fail("no audio files");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("no playable chapters"));
        }
        assertEquals("Брэкстен", SITE.description(json("book-noaudio.json", empty),
                S + "/lyubovnye-romany/k-linn/breksten").getTitle());

        JsonObject unsourced = new JsonObject();
        unsourced.addProperty("duration", 45981);
        JsonObject trial = new JsonObject();
        trial.addProperty("duration", 600);
        assertTrue("one 600 s file of a long book", Bookinfo.isFragment(unsourced, Arrays.asList(trial)));
        unsourced.addProperty("duration", 600);
        assertFalse("a 10-minute book", Bookinfo.isFragment(unsourced, Arrays.asList(trial)));
    }

    @Test
    public void seriesOfTheBook() throws IOException {
        JsonObject book = JsonPage.of(json("book-chapters.json", CHAPTERS)).getAsJsonObject()
                .getAsJsonObject("book");
        ArrayList<SeriesPOJO> series = Bookinfo.parseSeries(json("series.json", SERIES), book);
        assertEquals(5, series.size());
        assertEquals("Мастер Трав I", series.get(0).getName());
        assertEquals(S + "/popadantsy/vanya-mordorskiy/master-trav-i", series.get(0).getUrl());
        assertEquals(S + "/books/148858/cover.webp", series.get(0).getPhoto());
        assertEquals("the cards carry no number", "", series.get(0).getNumber());
        assertEquals(CHAPTERS, series.get(4).getUrl());
        assertEquals("the book's own number", "7", series.get(4).getNumber());
        assertEquals(SERIES, Bookinfo.seriesUrl("master-trav"));
    }

    @Test
    public void deepLinksReadTheRecord() {
        assertEquals(BOOK, Bookinfo.bookUrl("fentezi", "robbi-makniven", "patent-na-smert"));
        assertEquals("", Bookinfo.bookUrl("fentezi", "", "patent-na-smert"));
    }
}
