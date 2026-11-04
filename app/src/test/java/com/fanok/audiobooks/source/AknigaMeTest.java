package com.fanok.audiobooks.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fanok.audiobooks.BookPageFixtures;
import com.fanok.audiobooks.net.JsonPage;
import com.fanok.audiobooks.pojo.AudioPOJO;
import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.pojo.DescriptionPOJO;
import com.fanok.audiobooks.pojo.GenrePOJO;
import com.fanok.audiobooks.pojo.OtherArtistPOJO;
import com.fanok.audiobooks.pojo.SeriesPOJO;
import com.fanok.audiobooks.search.ListingFilters;
import com.fanok.audiobooks.search.ProviderPage;
import com.fanok.audiobooks.search.SearchUrls;

import org.jsoup.Jsoup;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * akniga.me pages saved on 2026-11-04 (see src/test/resources/fixtures/akniga-me): each {@code .json} is the Inertia
 * JSON of the page's HTML as {@link AknigaMe#fetch} returns it ({@link AknigaMe#inertia}); {@code search-none.html}
 * is a whole page as the site serves it.
 */
public class AknigaMeTest {

    private static final AknigaMe SITE = new AknigaMe();
    private static final String S = AknigaMe.SERVER;
    private static final String INDEX = AknigaMe.INDEX;
    private static final String GENRE = S + "/audiobooks/fentezi";
    private static final String AUTHOR = S + "/author/fyodor-dostoevskiy";
    private static final String READER = S + "/reader/aleksandr-bordukov";
    private static final String SERIES = S + "/series/ballada-o-nefritovoi-kosti-6f1a2ba2";
    private static final String BOOK = S + "/book/ryadom-s-toboy";
    private static final String SERIES_BOOK = S + "/book/tsan-yue-ballada-o-nefritovoy-kosti-kniga-4";
    private static final String EDITIONS_BOOK = S + "/book/napoleon-khill-dumay-i-bogatey-2";
    private static final String LONG_BOOK = S + "/book/hp-lovecraft-the-complete-fiction-of-hp-lovecraft-the-necronomicon";

    private static JsonPage json(String name, String url) throws IOException {
        return BookPageFixtures.json("akniga-me/" + name, url);
    }

    private static ProviderPage<BookPOJO> listing(String name, String url) throws IOException {
        return SITE.listing(json(name, url), url, 1);
    }

    // ---------------------------------------------------------------- the page JSON

    @Test
    public void inertiaJsonOfTheHtmlPage() throws IOException {
        String url = SITE.searchUrl(SearchUrls.Category.BOOKS, "zzzxqwj", 1);
        JsonPage page = AknigaMe.inertia(BookPageFixtures.load("akniga-me/search-none.html", url));
        assertEquals(url, page.location());
        assertEquals("search", JsonPage.of(page).getAsJsonObject().getAsJsonObject("props").get("view").getAsString());
        assertFalse("the script's alias map is dropped",
                JsonPage.of(page).getAsJsonObject().getAsJsonObject("props").has("author_aliases"));
        assertEquals(ProviderPage.Status.EMPTY, SITE.searchResults(page, 1).status);
        try {
            AknigaMe.inertia(Jsoup.parse("<html><body><h1>Доступ ограничен</h1></body></html>", INDEX));
            fail("a page without the Inertia JSON is not a listing");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("no Inertia page"));
        }
    }

    // ---------------------------------------------------------------- listings

    @Test
    public void indexCardsAndPages() throws IOException {
        ProviderPage<BookPOJO> page = listing("listing-p1.json", INDEX);
        assertEquals(ProviderPage.Status.OK, page.status);
        assertEquals(36, page.items.size());
        assertTrue("703 pages", page.hasMore);
        BookPOJO first = page.items.get(0);
        assertEquals(BOOK, first.getUrl());
        assertEquals("Рядом с тобой", first.getName());
        assertEquals(S + "/artwork/album/3108451?size=320&v=063c37885ba36f5d", first.getPhoto());
        assertEquals("Prometey", first.getArtist());
        assertNull("the cards name no authors", first.getAutor());
        assertEquals("5681.633 s", "1 ч. 34 мин.", first.getTime());
        assertEquals("no votes", "0", first.getReting());
        assertEquals(S + "/book/tim-taler-ili-prodannyy-smekh", page.items.get(35).getUrl());
        assertEquals("Леонид Кулагин, Дмитрий Назаров", page.items.get(4).getArtist());

        assertEquals(INDEX + "?page=2", SITE.pageUrl(INDEX, 2));
        assertEquals(INDEX, SITE.pageUrl(INDEX, 1));
        ProviderPage<BookPOJO> second = listing("listing-p2.json", SITE.pageUrl(INDEX, 2));
        assertEquals(36, second.items.size());
        assertTrue(second.hasMore);
        assertEquals(S + "/book/elektrostantsiya", second.items.get(0).getUrl());

        ProviderPage<BookPOJO> last = listing("listing-last.json", SITE.pageUrl(INDEX, 703));
        assertEquals(34, last.items.size());
        assertFalse(last.hasMore);
        BookPOJO island = last.items.get(0);
        assertEquals(S + "/book/van-tszyatszyun-ostrov-pokoya", island.getUrl());
        assertEquals("the old catalog's \"Author. «Title»\"", "Остров Покоя", island.getName());
        assertEquals("Ван Цзяцзюнь", island.getAutor());
        assertEquals("11 ч. 5 мин.", island.getTime());
        assertEquals(S + "/artwork/album/142169?size=320&v=5a7baa7a0e716651", island.getPhoto());
        assertEquals("Постоянство хищника", last.items.get(33).getName());
    }

    /** popular and rating rank the few books with plays or votes, then the newest; duration is the shortest first. */
    @Test
    public void sortedListings() throws IOException {
        ProviderPage<BookPOJO> popular = listing("listing-popular.json", INDEX + "?sort=popular");
        assertEquals(S + "/book/elizarov-mikhail-my-vyshli-pokurit-na-17-let", popular.items.get(0).getUrl());
        assertEquals("Мы вышли покурить на 17 лет", popular.items.get(0).getName());
        assertEquals("Елизаров Михаил", popular.items.get(0).getAutor());
        assertEquals("then the newest", BOOK, popular.items.get(4).getUrl());
        assertTrue(popular.hasMore);

        BookPOJO rated = listing("listing-rating.json", INDEX + "?sort=rating").items.get(0);
        assertEquals("Робин Шарма", rated.getAutor());
        assertEquals("Клуб „5 часов утра“. Секрет личной эффективности от монаха, который продал свой „Феррари“",
                rated.getName());
        assertEquals("5", rated.getReting());

        ProviderPage<BookPOJO> shortest = listing("listing-duration.json", INDEX + "?sort=duration");
        assertEquals(S + "/book/pencho-slaveykov-spi-ezeroto", shortest.items.get(0).getUrl());
        assertEquals("no «»: the name as it is", "Пенчо Славейков: Спи езерото", shortest.items.get(0).getName());
        assertEquals(S + "/book/govard-lavkraft-sovetskim-detyam", shortest.items.get(35).getUrl());
    }

    @Test
    public void genreListings() throws IOException {
        ProviderPage<BookPOJO> genre = listing("genre.json", GENRE);
        assertEquals(36, genre.items.size());
        assertTrue("81 pages", genre.hasMore);
        assertEquals(S + "/book/gurman-iz-drugogo-mira-4", genre.items.get(0).getUrl());
        assertEquals("Алекс Фрост", genre.items.get(0).getArtist());
        assertEquals(S + "/book/nyusha-porokhnya-buket-vasilkov-na-chyornom-poroge",
                listing("genre-p2.json", SITE.pageUrl(GENRE, 2)).items.get(0).getUrl());
        assertEquals(S + "/book/sonya-lavrova-kotenok-fish",
                listing("genre-duration.json", GENRE + "?sort=duration").items.get(0).getUrl());
        BookPOJO tide = listing("genre-long.json", GENRE + "?duration=long").items.get(0);
        assertEquals("Путь прилива", tide.getName());
        assertEquals("10 ч. 37 мин.", tide.getTime());
        ProviderPage<BookPOJO> rated = listing("genre-selfdev-rating.json", S + "/audiobooks/o-samorazvitii?sort=rating");
        assertEquals("a genre takes the sort too", S + "/book/robin-sharma-klub-5-chasov-utra-sekret-lichnoy-effektivnosti"
                + "-ot-monakha-kotoryy-prodal-svoy-ferrari", rated.items.get(0).getUrl());
        assertTrue(rated.hasMore);
    }

    /** An author's and a reader's cards have no readers and no seconds: the page's person fills its own role. */
    @Test
    public void authorReaderAndSeriesListings() throws IOException {
        ProviderPage<BookPOJO> author = listing("author.json", AUTHOR);
        assertEquals(36, author.items.size());
        assertTrue("3 pages", author.hasMore);
        BookPOJO meek = author.items.get(0);
        assertEquals(S + "/book/krotkaya", meek.getUrl());
        assertEquals("Кроткая", meek.getName());
        assertEquals("Фёдор Достоевский", meek.getAutor());
        assertEquals(AUTHOR, meek.getUrlAutor());
        assertNull(meek.getArtist());
        assertEquals("", meek.getTime());
        assertEquals(AUTHOR + "?page=2", SITE.pageUrl(AUTHOR, 2));

        ProviderPage<BookPOJO> reader = listing("reader.json", READER);
        assertTrue("11 pages", reader.hasMore);
        assertEquals(S + "/book/rasskazy-o-sherloke-kholmse", reader.items.get(0).getUrl());
        assertEquals("Александр Бордуков", reader.items.get(0).getArtist());
        assertEquals(READER, reader.items.get(0).getUrlArtist());
        assertEquals("Ивовая аллея", reader.items.get(35).getName());
        assertEquals("Синклер Льюис", reader.items.get(35).getAutor());

        ProviderPage<BookPOJO> series = listing("series.json", SERIES);
        assertEquals(3, series.items.size());
        assertFalse("one page", series.hasMore);
        assertEquals("Баллада о нефритовой кости. Книга 1", series.items.get(0).getName());
        assertEquals("Цан Юэ", series.items.get(0).getAutor());
        assertEquals("Дарья Павлова", series.items.get(0).getArtist());

        for (String url : new String[]{AUTHOR, READER, SERIES}) {
            assertFalse(url, SITE.listsDuration(url));
            assertTrue(url, SITE.sorts(url).isEmpty());
        }
        assertTrue(SITE.listsDuration(INDEX));
        assertTrue(SITE.listsDuration(GENRE + "?sort=popular"));
    }

    @Test
    public void genres() throws IOException {
        ArrayList<GenrePOJO> genres = AknigaMe.parseGenres(json("genres.json", AknigaMe.GENRES));
        assertEquals(25, genres.size());
        assertEquals("Аудиосказки", genres.get(0).getName());
        assertEquals(S + "/audiobooks/audioskazki", genres.get(0).getUrl());
        assertEquals("", genres.get(0).getDescription());
        assertEquals("Про любовь", genres.get(3).getName());
        assertEquals("a sub-genre", "Художественная литература", genres.get(3).getDescription());
        assertEquals("Фэнтези", genres.get(6).getName());
        assertEquals(GENRE, genres.get(6).getUrl());
        assertEquals("На английском", genres.get(24).getName());
        assertEquals("Аудиокниги на иностранном языке", genres.get(24).getDescription());
        assertFalse(SITE.genres(json("genres.json", AknigaMe.GENRES), AknigaMe.GENRES, 1).hasMore);
        assertEquals("every catalog page lists them", 25, AknigaMe.parseGenres(json("listing-p1.json", INDEX)).size());
    }

    // ---------------------------------------------------------------- search

    @Test
    public void search() throws IOException {
        String dostoevsky = SITE.searchUrl(SearchUrls.Category.BOOKS, "Достоевский", 1);
        assertEquals(S + "/search?q=%D0%94%D0%BE%D1%81%D1%82%D0%BE%D0%B5%D0%B2%D1%81%D0%BA%D0%B8%D0%B9&type=books&page=1",
                dostoevsky);
        ProviderPage<BookPOJO> first = SITE.searchResults(json("search.json", dostoevsky), 1);
        assertEquals(36, first.items.size());
        assertTrue("4 pages", first.hasMore);
        BookPOJO karamazov = first.items.get(0);
        assertEquals(S + "/book/fyodor-dostoevskiy-bratya-karamazovy-chast-4", karamazov.getUrl());
        assertEquals("Братья Карамазовы. Часть 4", karamazov.getName());
        assertEquals("Фёдор Достоевский", karamazov.getAutor());
        assertEquals("Юрий Григорьев, Аудиокнига в кармане", karamazov.getArtist());
        assertEquals("16 ч. 28 мин.", karamazov.getTime());
        assertEquals("Федор Достоевский - Записки из подполья", first.items.get(35).getName());

        String fourth = SITE.searchUrl(SearchUrls.Category.BOOKS, "Достоевский", 4);
        ProviderPage<BookPOJO> last = SITE.searchResults(json("search-last.json", fourth), 4);
        assertEquals(13, last.items.size());
        assertFalse(last.hasMore);
        assertEquals("Воспоминания жены писателя", last.items.get(12).getName());

        ProviderPage<BookPOJO> none = SITE.searchResults(
                json("search-none.json", SITE.searchUrl(SearchUrls.Category.BOOKS, "zzzxqwj", 1)), 1);
        assertEquals(ProviderPage.Status.EMPTY, none.status);
        assertFalse(none.hasMore);
        assertTrue("a search is not re-sorted", SITE.sorts(dostoevsky).isEmpty());
    }

    // ---------------------------------------------------------------- people

    @Test
    public void people() throws IOException {
        assertEquals(AknigaMe.AUTHORS + "?page=2", SITE.pageUrl(AknigaMe.AUTHORS, 2));
        ProviderPage<GenrePOJO> authors = SITE.people(json("authors-p1.json", AknigaMe.AUTHORS), AknigaMe.AUTHORS, 1,
                false);
        assertEquals(66, authors.items.size());
        assertTrue("130 pages", authors.hasMore);
        assertEquals("А. Азарина", authors.items.get(0).getName());
        assertEquals(S + "/author/a-azarina", authors.items.get(0).getUrl());
        assertEquals(1, authors.items.get(0).getReting());
        ProviderPage<GenrePOJO> second = AknigaMe.parsePeople(json("authors-p2.json", AknigaMe.AUTHORS + "?page=2"),
                false);
        assertEquals("Адэль Мифтахова", second.items.get(0).getName());
        assertTrue(second.hasMore);

        ProviderPage<GenrePOJO> readers = SITE.people(json("readers-p1.json", AknigaMe.READERS), AknigaMe.READERS, 1,
                true);
        assertEquals(66, readers.items.size());
        assertTrue("89 pages", readers.hasMore);
        assertEquals("А. Багдасаров", readers.items.get(0).getName());
        assertEquals(S + "/reader/a-bagdasarov", readers.items.get(0).getUrl());
    }

    @Test
    public void peopleSearch() throws IOException {
        String alexander = SITE.searchUrl(SearchUrls.Category.AUTHORS, "Александр", 1);
        assertEquals(S + "/search?q=%D0%90%D0%BB%D0%B5%D0%BA%D1%81%D0%B0%D0%BD%D0%B4%D1%80&type=authors&page=1",
                alexander);
        ProviderPage<GenrePOJO> found = SITE.searchPeople(json("search-authors.json", alexander), 1, false);
        assertEquals(36, found.items.size());
        assertTrue("9 pages", found.hasMore);
        assertEquals("Александр Куприн", found.items.get(0).getName());
        assertEquals(S + "/author/aleksandr-kuprin", found.items.get(0).getUrl());
        assertEquals(56, found.items.get(0).getReting());
        assertEquals(S + "/author/aleksandr-belyaev", found.items.get(35).getUrl());

        String king = SITE.searchUrl(SearchUrls.Category.PERFORMERS, "Кинг", 1);
        assertEquals(S + "/search?q=%D0%9A%D0%B8%D0%BD%D0%B3&type=narrators&page=1", king);
        ProviderPage<GenrePOJO> readers = SITE.searchPeople(json("search-narrators.json", king), 1, true);
        assertEquals(2, readers.items.size());
        assertFalse(readers.hasMore);
        assertEquals("Кинге Дмитрий", readers.items.get(0).getName());
        assertEquals(S + "/reader/kinge-dmitriy", readers.items.get(0).getUrl());
        assertEquals(S + "/reader/artisty-teatra-i-kino", readers.items.get(1).getUrl());
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
        List<Sort> menu = Arrays.asList(Sort.NEW, Sort.POPULAR, Sort.RATING, Sort.DURATION);
        for (String listing : new String[]{INDEX, GENRE}) {
            List<Sort.Entry> entries = SITE.sorts(listing);
            assertEquals(listing, menu.size(), entries.size());
            for (int i = 0; i < menu.size(); i++) {
                assertEquals(listing, menu.get(i), entries.get(i).sort);
            }
            for (Sort sort : menu) {
                String url = sorted(sort, listing);
                assertEquals(url + " keeps its menu", 4, SITE.sorts(url).size());
                assertEquals(url + " " + sort, url, sorted(sort, url));
            }
        }
        assertTrue(SITE.sorts(AknigaMe.AUTHORS).isEmpty());
        assertTrue(SITE.sorts(AknigaMe.GENRES).isEmpty());
        assertTrue(SITE.sorts(BOOK).isEmpty());
        assertNull(SITE.sortUrl(Sort.COMMENTS, INDEX));
        assertNull(SITE.sortUrl(Sort.POPULAR_WEEK, INDEX));
        assertNull(SITE.sortUrl(Sort.TITLE, INDEX));
    }

    @Test
    public void sortsSiteWideAndWithinAGenre() {
        String popular = INDEX + "?sort=popular";
        assertEquals(popular, sorted(Sort.POPULAR, INDEX));
        assertEquals(INDEX + "?sort=rating", sorted(Sort.RATING, popular));
        assertEquals(INDEX, sorted(Sort.NEW, SITE.pageUrl(INDEX + "?sort=duration", 3)));
        assertNull(SITE.genreOf(INDEX));
        assertNull(SITE.genreOf(popular));
        assertNull(SITE.genreOf(AUTHOR));

        assertEquals(GENRE, SITE.genreOf(GENRE + "?sort=rating&page=3"));
        assertEquals(GENRE + "?sort=duration", sorted(Sort.DURATION, GENRE + "?sort=popular"));
        assertEquals(GENRE, sorted(Sort.NEW, GENRE + "?sort=rating"));
        assertEquals(GENRE + "?sort=duration", SITE.inGenre(INDEX + "?sort=duration", GENRE));

        assertEquals(popular + "&page=2", SITE.pageUrl(popular, 2));
        assertEquals(popular + "&page=3", SITE.pageUrl(popular + "&page=2", 3));
        assertEquals(GENRE + "?sort=rating&page=2", SITE.pageUrl(sorted(Sort.RATING, GENRE), 2));
    }

    @Test
    public void durationBuckets() {
        assertEquals(INDEX + "?duration=short", SITE.withDuration(INDEX, ListingFilters.Duration.UNDER_30M));
        assertEquals("short", AknigaMe.bucket(ListingFilters.Duration.FROM_1H_TO_2H));
        assertEquals("short", AknigaMe.bucket(ListingFilters.Duration.of(0, 180)));
        assertEquals("medium", AknigaMe.bucket(ListingFilters.Duration.of(180, 600)));
        assertEquals("medium", AknigaMe.bucket(ListingFilters.Duration.of(240, 300)));
        assertEquals("long", AknigaMe.bucket(ListingFilters.Duration.of(600, ListingFilters.Duration.UNBOUNDED)));
        assertEquals("long", AknigaMe.bucket(ListingFilters.Duration.of(900, 1200)));
        assertNull("across short and medium", AknigaMe.bucket(ListingFilters.Duration.OVER_2H));
        assertNull(AknigaMe.bucket(ListingFilters.Duration.of(120, 240)));
        assertNull(AknigaMe.bucket(ListingFilters.Duration.ANY));

        String genreLong = SITE.withDuration(GENRE + "?sort=rating", ListingFilters.Duration.of(600, 900));
        assertEquals(GENRE + "?sort=rating&duration=long", genreLong);
        assertEquals("idempotent", genreLong, SITE.withDuration(genreLong, ListingFilters.Duration.of(600, 900)));
        assertEquals(GENRE + "?sort=rating", SITE.withDuration(genreLong, ListingFilters.Duration.ANY));
        assertEquals(INDEX, SITE.withDuration(INDEX + "?duration=medium", ListingFilters.Duration.ANY));
        assertEquals("people pages take no filter", AUTHOR,
                SITE.withDuration(AUTHOR, ListingFilters.Duration.UNDER_30M));
        assertEquals("the bound survives a sort", GENRE + "?duration=long&sort=popular",
                sorted(Sort.POPULAR, genreLong));
    }

    // ---------------------------------------------------------------- book

    @Test
    public void description() throws IOException {
        DescriptionPOJO d = SITE.description(json("book.json", BOOK), BOOK);
        assertEquals("Рядом с тобой", d.getTitle());
        assertEquals(S + "/img/books/3108451.jpg", d.getPoster());
        assertEquals("Иван Белов", d.getAutor());
        assertEquals(S + "/author/ivan-belov", d.getAutorUrl());
        assertEquals("Prometey", d.getArtist());
        assertEquals(S + "/reader/prometey", d.getArtistUrl());
        assertEquals("Ужасы и мистика", d.getGenre());
        assertEquals(S + "/audiobooks/uzhasy-i-mistika", d.getGenreUrl());
        assertEquals("", d.getSeries());
        assertEquals("5681.633 s", "1 ч. 34 мин.", d.getTime());
        assertEquals("no votes", "", d.getReiting());
        assertTrue(d.getDescription(), d.getDescription().startsWith("Самарская губерния 1921 года"));
        assertFalse(d.isOtherReader());

        BookPOJO card = SITE.book(json("book.json", BOOK), BOOK);
        assertEquals(BOOK, card.getUrl());
        assertEquals("Рядом с тобой", card.getName());
        assertEquals("Иван Белов", card.getAutor());
        assertEquals("Ужасы и мистика", card.getGenre());

        DescriptionPOJO lovecraft = AknigaMe.parseDescription(json("book-long.json", LONG_BOOK));
        assertEquals("the author's frame stripped", "The Complete fiction of HP Lovecraft (The Necronomicon)",
                lovecraft.getTitle());
        assertEquals("Peter Coates, Kenneth Elliot", lovecraft.getArtist());
        assertEquals(S + "/reader/peter-coates", lovecraft.getArtistUrl());
        assertEquals(S + "/artwork/album/141960", lovecraft.getPoster());
        assertEquals("", lovecraft.getDescription());
    }

    @Test
    public void titleLosesOnlyItsOwnAuthor() {
        assertEquals("Думай и богатей", AknigaMe.title("Наполеон Хилл. «Думай и богатей»", "Наполеон Хилл"));
        assertEquals("names in another order", "Мы вышли покурить на 17 лет",
                AknigaMe.title("Елизаров Михаил. «Мы вышли покурить на 17 лет»", "Михаил Елизаров"));
        assertEquals("Братья Карамазовы. Часть 4",
                AknigaMe.title("Фёдор Достоевский — «Братья Карамазовы». Часть 4", "Фёдор Достоевский"));
        assertEquals("Аудиоспектакль. «Мастер и Маргарита»",
                AknigaMe.title("Аудиоспектакль. «Мастер и Маргарита»", "Михаил Булгаков"));
        assertEquals("Рядом с тобой", AknigaMe.title("Рядом с тобой", "Иван Белов"));
    }

    @Test
    public void series() throws IOException {
        DescriptionPOJO d = AknigaMe.parseDescription(json("book-series.json", SERIES_BOOK));
        assertEquals("Баллада о нефритовой кости. Книга 4", d.getTitle());
        assertEquals("Цан Юэ", d.getAutor());
        assertEquals("Александра Дёмина", d.getArtist());
        assertEquals("Фэнтези", d.getGenre());
        assertEquals(GENRE, d.getGenreUrl());
        assertEquals("Баллада о нефритовой кости", d.getSeries());
        assertEquals(SERIES, d.getSeriesUrl());
        assertEquals("8 ч. 50 мин.", d.getTime());

        ArrayList<SeriesPOJO> ballad = SITE.series(json("book-series.json", SERIES_BOOK), SERIES_BOOK);
        assertEquals(3, ballad.size());
        assertEquals(S + "/book/tsan-yue-ballada-o-nefritovoy-kosti-kniga-1", ballad.get(0).getUrl());
        assertEquals("Баллада о нефритовой кости. Книга 1", ballad.get(0).getName());
        assertEquals("1", ballad.get(0).getNumber());
        assertEquals(S + "/artwork/album/242141?size=320&v=24a1c4bc3b635e6f", ballad.get(0).getPhoto());
        assertEquals("the site has no book 2", "3", ballad.get(1).getNumber());
        assertEquals(SERIES_BOOK, ballad.get(2).getUrl());
        assertEquals("4", ballad.get(2).getNumber());
        assertTrue(AknigaMe.parseSeries(json("book.json", BOOK)).isEmpty());
    }

    @Test
    public void otherReaders() throws IOException {
        JsonPage page = json("book-editions.json", EDITIONS_BOOK);
        DescriptionPOJO d = AknigaMe.parseDescription(page);
        assertEquals("Думай и богатей", d.getTitle());
        assertEquals("Наполеон Хилл", d.getAutor());
        assertEquals("Максим Киреев", d.getArtist());
        assertEquals("9 ч. 41 мин.", d.getTime());
        assertEquals(BookPOJO.formatRating(5f, "1"), d.getReiting());
        assertTrue(d.isOtherReader());
        assertEquals(2, d.getOtherReaders().size());
        ArrayList<OtherArtistPOJO> others = AknigaMe.parseOtherReaders(page);
        assertEquals("Исполнитель Всеволод Кузнецов", others.get(0).getName());
        assertEquals(S + "/book/napoleon-khill-dumay-i-bogatey", others.get(0).getUrl());
        assertEquals("Исполнитель Руслан Драпалюк", others.get(1).getName());
        assertEquals(S + "/book/napoleon-khill-dumay-i-bogatey-1", others.get(1).getUrl());
        assertTrue(SITE.findsOtherReaders());
        assertFalse("the page tells", SITE.probesOtherReaders());
    }

    @Test
    public void recommendations() throws IOException {
        ArrayList<BookPOJO> more = SITE.recommendations(json("book.json", BOOK), BOOK);
        assertEquals("1 of the author, 6 similar, 6 of the reader", 13, more.size());
        BookPOJO first = more.get(0);
        assertEquals(S + "/book/ivan-belov-samaya-strashnaya-kniga-zastupa-chernee-chernogo", first.getUrl());
        assertEquals("Самая страшная книга. Заступа: Чернее черного", first.getName());
        assertEquals("Иван Белов", first.getAutor());
        assertEquals(S + "/artwork/album/1556784?size=320&v=f6e450e11b78b9c7", first.getPhoto());
        assertEquals(S + "/book/taynaya-voyna-sssr-protiv-upyrey", more.get(1).getUrl());
        assertEquals(S + "/book/laboratoriya-1", more.get(12).getUrl());
        assertEquals("each once", 18, AknigaMe.parseRecommendations(json("book-editions.json", EDITIONS_BOOK)).size());
    }

    // ---------------------------------------------------------------- chapters

    @Test
    public void tracks() throws IOException {
        JsonPage page = json("book.json", BOOK);
        assertNull("the page lists all", AknigaMe.chaptersUrl(page));
        ArrayList<AudioPOJO> one = SITE.tracks(page, BOOK);
        assertEquals(1, one.size());
        assertEquals("Глава 1", one.get(0).getName());
        assertEquals(S + "/playing/3817305.mp3", one.get(0).getUrl());
        assertEquals(5681, one.get(0).getTime());
        assertEquals("Рядом с тобой", one.get(0).getBookName());

        JsonPage ballad = json("book-series.json", SERIES_BOOK);
        assertNull(AknigaMe.chaptersUrl(ballad));
        ArrayList<AudioPOJO> parts = SITE.tracks(ballad, SERIES_BOOK);
        assertEquals(16, parts.size());
        assertEquals("Цан Юэ. «Баллада о нефритовой кости. Книга 4». Часть 16", parts.get(15).getName());
        assertEquals(S + "/playing/8000137749834.mp3", parts.get(15).getUrl());
        assertEquals("Баллада о нефритовой кости. Книга 4", parts.get(0).getBookName());

        assertTrue("the media are on the site itself", SITE.hosts().isEmpty());
        assertNull("no Referer needed: the default, the book page", SITE.mediaHeaders(BOOK));
        assertNull(SITE.referer());
    }

    /** A page lists 50 chapters at most; the rest come from the chapters API. */
    @Test
    public void longBookReadsTheChaptersApi() throws IOException {
        JsonPage page = json("book-long.json", LONG_BOOK);
        String api = AknigaMe.chaptersUrl(page);
        assertEquals("50 of 240 on the page", S + "/akniga/books/141960/chapters", api);
        assertEquals(50, AknigaMe.parseTracks(page, "x", LONG_BOOK).size());
        ArrayList<AudioPOJO> all = AknigaMe.parseTracks(json("chapters-long.json", api), "The Complete fiction",
                LONG_BOOK);
        assertEquals(240, all.size());
        assertEquals("Howard Lovecraft. «The Complete fiction of HP Lovecraft (The Necronomicon)». #1",
                all.get(0).getName());
        assertEquals(11, all.get(0).getTime());
        assertEquals(S + "/playing/8000092092379.mp3", all.get(239).getUrl());
        assertEquals("The Complete fiction", all.get(239).getBookName());
    }

    @Test
    public void pageWithoutChaptersIsAnError() {
        try {
            AknigaMe.parseTracks(JsonPage.parse(BOOK, "{\"props\":{\"items\":{\"data\":[]}}}"), "x", BOOK);
            fail("no chapters is no book");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("no playable chapters"));
        }
    }
}
