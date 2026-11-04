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

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * yakniga.org GraphQL answers saved on 2026-11-04 (see src/test/resources/fixtures/yakniga), each the answer to the
 * very request {@link Yakniga#request} builds for its URL.
 */
public class YaknigaTest {

    private static final Yakniga SITE = new Yakniga();
    private static final String S = Yakniga.SERVER;
    private static final String API = S + "/graphql";
    private static final String GENRE = S + "/genres/fantastika?by_date=desc";
    private static final String AUTHOR = S + "/panchenko-andrey?by_date=desc";
    private static final String READER = S + "/reader/shirokova-lyudmila?by_date=desc";
    private static final String SERIES = S + "/series/avtostopom-po-galaktike";
    private static final String BOOK = S + "/duglas-adams/avtostopom-po-galaktike";
    private static final String FULL = "{\"by_source\":[\"manual\",\"knigavuhe\",\"akniga\",\"aknig\"]";

    private static JsonPage json(String name) throws IOException {
        return BookPageFixtures.json("yakniga/" + name, API);
    }

    private static ProviderPage<BookPOJO> listing(String name) throws IOException {
        return Yakniga.parseListing(json(name));
    }

    private static List<String> urls(List<BookPOJO> books) {
        List<String> urls = new ArrayList<>();
        for (BookPOJO book : books) {
            urls.add(book.getUrl());
        }
        return urls;
    }

    // ---------------------------------------------------------------- listings

    @Test
    public void indexCardsAndPages() throws IOException {
        ProviderPage<BookPOJO> page = listing("index-p1.json");
        assertEquals(ProviderPage.Status.OK, page.status);
        assertEquals(30, page.items.size());
        assertTrue("2106 pages", page.hasMore);
        BookPOJO first = page.items.get(0);
        assertEquals(S + "/vorontsova-aleksandra/hozyayka-zabytoy-usadby", first.getUrl());
        assertEquals("Хозяйка забытой усадьбы", first.getName());
        assertEquals(S + "/yafile/4e015f47564b424a5a560a5d121d05510b1c06005a5701561e510942014a190752531a560f53.webp",
                first.getPhoto());
        assertEquals("Воронцова Александра", first.getAutor());
        assertEquals(S + "/vorontsova-aleksandra?by_date=desc", first.getUrlAutor());
        assertEquals("Широкова Людмила", first.getArtist());
        assertEquals(READER, first.getUrlArtist());
        assertEquals("67673 s", "18 ч. 47 мин.", first.getTime());
        assertEquals("the first of its genres", "Фэнтези", first.getGenre());
        assertEquals(S + "/genres/fentezi?by_date=desc", first.getUrlGenre());
        assertNull(first.getSeries());
        BookPOJO second = page.items.get(1);
        assertEquals("Древние механизмы", second.getSeries());
        assertEquals(S + "/series/drevnie-mehanizmy", second.getUrlSeries());
        assertEquals(S + "/barbara-bredford/zhenschiny-v-ego-zhizni", page.items.get(29).getUrl());

        ProviderPage<BookPOJO> two = listing("index-p2.json");
        assertEquals("two stubs without a duration left out", 28, two.items.size());
        assertTrue(two.hasMore);
        assertEquals(S + "/semin-nikita/syn-pomeschika-kniga-13", two.items.get(0).getUrl());
        assertEquals("2754 s", "45 мин.", two.items.get(1).getTime());
        assertFalse(urls(two.items).contains(S + "/dzhozef-fanyu/rebyonok-kotorogo-uveli-fei"));
        assertFalse(urls(two.items).contains(S + "/pavel-belyaev/son-razuma"));

        ProviderPage<BookPOJO> last = listing("index-last.json");
        assertEquals(27, last.items.size());
        assertFalse("page 2106 of 2106", last.hasMore);
        BookPOJO wizards = last.items.get(0);
        assertEquals(S + "/harris-patrik-nil/volshebnye-neudachniki", wizards.getUrl());
        assertEquals("one reader listed twice, its words swapped", "Сергеев Максим", wizards.getArtist());
        BookPOJO evolution = last.items.get(1);
        assertEquals("Составитель: Элисон Джордж, Джордж Эдвин Эллисон", evolution.getAutor());
        assertEquals(S + "/sostavitel-elison-dzhordzh?by_date=desc", evolution.getUrlAutor());
        assertEquals("Ломакин Игорь", evolution.getArtist());
        assertEquals(S + "/lopato-semen/oblako", last.items.get(26).getUrl());

        ProviderPage<BookPOJO> beyond = listing("index-beyond.json");
        assertEquals(ProviderPage.Status.EMPTY, beyond.status);
        assertFalse(beyond.hasMore);
    }

    /** LitRes trials, paid and blocked books are left out; a page of nothing else keeps its next page. */
    @Test
    public void stubsAreLeftOut() throws IOException {
        ProviderPage<BookPOJO> rating = listing("rating-p1.json");
        assertEquals(28, rating.items.size());
        assertFalse("blocked by its rights holder",
                urls(rating.items).contains(S + "/vladimir-korchagin/tayna-reki-zlyh-duhov"));
        assertFalse("a LitRes trial", urls(rating.items).contains(S + "/viktor-smirnov/bagrovye-kovyli"));

        ProviderPage<BookPOJO> search = Yakniga.parseSearch(json("search.json"));
        assertFalse(urls(search.items).contains(S + "/andrey-panchenko/shans"));

        JsonPage stubs = JsonPage.parse(API, "{\"data\":{\"x\":{\"collection\":["
                + "{\"title\":\"Бег\",\"aliasName\":\"beg\",\"authorAlias\":\"a\",\"duration\":0,\"source\":\"litres\"},"
                + "{\"title\":\"Шаг\",\"aliasName\":\"shag\",\"authorAlias\":\"a\",\"duration\":1200,"
                + "\"source\":\"author_today\"},"
                + "{\"title\":\"Путь\",\"aliasName\":\"put\",\"authorAlias\":\"a\",\"duration\":900,\"source\":null,"
                + "\"isBiblio\":true}],"
                + "\"metadata\":{\"currentPage\":3,\"totalPages\":9}}}}");
        ProviderPage<BookPOJO> none = Yakniga.parseListing(stubs);
        assertEquals(ProviderPage.Status.OK, none.status);
        assertTrue(none.items.isEmpty());
        assertTrue("its next page stays", none.hasMore);
    }

    @Test
    public void sortedListings() throws IOException {
        ProviderPage<BookPOJO> popular = listing("popular-p1.json");
        assertEquals(30, popular.items.size());
        assertTrue("28 pages of the curated list", popular.hasMore);
        BookPOJO wolf = popular.items.get(0);
        assertEquals(S + "/uilyam-king/chest-volka", wolf.getUrl());
        assertEquals("Уильям Кинг, Ли Лайтнер", wolf.getAutor());
        assertEquals(S + "/uilyam-king?by_date=desc", wolf.getUrlAutor());
        assertEquals("Warhammer 40000: Космический Волк", wolf.getSeries());

        BookPOJO top = listing("rating-p1.json").items.get(0);
        assertEquals(S + "/mihail-zhukovin/karamazovy-chast-shestaya", top.getUrl());
        assertEquals("41 мин.", top.getTime());

        ProviderPage<BookPOJO> title = listing("title-p1.json");
        assertEquals(27, title.items.size());
        assertEquals("03:33", title.items.get(0).getName());
        assertEquals("0,5", title.items.get(1).getName());
    }

    @Test
    public void genreAuthorReaderAndSeriesListings() throws IOException {
        ProviderPage<BookPOJO> genre = listing("genre-p1.json");
        assertEquals(28, genre.items.size());
        assertTrue("542 pages", genre.hasMore);
        assertEquals(S + "/averyanov-evgeniy/drevnie-mehanizmy-kniga-2-boevoy-kuznets", genre.items.get(0).getUrl());
        assertEquals("Рафаэль Дамиров, Феликс Кресс", genre.items.get(27).getAutor());
        ProviderPage<BookPOJO> second = listing("genre-p2.json");
        assertEquals(30, second.items.size());
        assertEquals(S + "/gavrilov-dmitriy/nas-rassudyat-bogi", second.items.get(0).getUrl());
        assertEquals("a no-break space trimmed", "Золотарев Егор, Карелин Сергей", second.items.get(29).getAutor());
        assertEquals(S + "/kim-stenli-robinson/nyu-york-2140", listing("genre-rating-p1.json").items.get(0).getUrl());
        ProviderPage<BookPOJO> genreTop = listing("genre-popular-p1.json");
        assertEquals(S + "/uilyam-king/chest-volka", genreTop.items.get(0).getUrl());
        assertTrue("14 pages", genreTop.hasMore);

        ProviderPage<BookPOJO> author = listing("author.json");
        assertEquals(Arrays.asList(S + "/panchenko-andrey/rabota-nad-oshibkami-kniga-7-iskuplenie",
                S + "/panchenko-andrey/rabota-nad-oshibkami-kniga-6-kamaradas",
                S + "/panchenko-andrey/rabota-nad-oshibkami-kniga-5-chuzhaya-territriya",
                S + "/panchenko-andrey/rabota-nad-oshibkami-kniga-4-za-chertoy"), urls(author.items));
        assertFalse(author.hasMore);
        assertEquals("(Fan12) Альберт", author.items.get(3).getArtist());
        assertEquals(S + "/reader/fan12-albert?by_date=desc", author.items.get(3).getUrlArtist());
        assertEquals("by title", S + "/panchenko-andrey/rabota-nad-oshibkami-kniga-4-za-chertoy",
                listing("author-title.json").items.get(0).getUrl());

        ProviderPage<BookPOJO> reader = listing("reader.json");
        assertEquals(28, reader.items.size());
        assertFalse(reader.hasMore);
        assertEquals("Хозяйка забытой усадьбы", reader.items.get(0).getName());
        assertEquals("Сёмина Дия", reader.items.get(1).getAutor());

        ProviderPage<BookPOJO> series = listing("series.json");
        assertEquals("the series' full books, by their numbers",
                Arrays.asList(BOOK, S + "/duglas-adams/zafod-igraet-chisto"), urls(series.items));
        assertFalse(series.hasMore);
        assertEquals("beckham48", series.items.get(1).getArtist());
        assertEquals("25 мин.", series.items.get(1).getTime());
    }

    @Test
    public void anAnswerWithoutDataIsAnError() throws IOException {
        JsonPage gone = json("not-found.json");
        assertEquals("Cannot return null for non-nullable field Query.book", Yakniga.error(gone));
        assertNull(Yakniga.error(json("book.json")));
        assertNull(Yakniga.error(json("search-none.json")));
        assertEquals("no book on the page", "", Yakniga.parseDescription(gone).getTitle());
        try {
            Yakniga.parseListing(gone);
            fail("no answer is no listing");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Query.book"));
        }
        try {
            Yakniga.parseListing(Jsoup.parse("<html><body>Проверка браузера</body></html>", S + "/"));
            fail("an HTML page is not an answer");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("not a JSON page"));
        }
    }

    // ---------------------------------------------------------------- genres and people

    @Test
    public void genres() throws IOException {
        ArrayList<GenrePOJO> genres = Yakniga.parseGenres(json("genres.json"));
        assertEquals(55, genres.size());
        assertEquals("Фантастика", genres.get(0).getName());
        assertEquals(GENRE, genres.get(0).getUrl());
        assertEquals("", genres.get(0).getDescription());
        assertEquals("Фэнтези", genres.get(1).getName());
        assertEquals("Фантастика", genres.get(1).getDescription());
        assertEquals("Любовное фэнтези", genres.get(2).getName());
        assertEquals("Фантастика / Фэнтези", genres.get(2).getDescription());
        assertEquals(S + "/genres/lyubovnoe-fentezi?by_date=desc", genres.get(2).getUrl());
        assertEquals("LitRPG", genres.get(3).getName());
        assertEquals("Детективы, триллеры", genres.get(13).getName());
        assertEquals("Детектив", genres.get(14).getName());
        assertEquals("same position, by id", "Эзотерика", genres.get(32).getName());
        assertEquals("Юмор, сатира", genres.get(33).getName());
        assertEquals("Тру-крайм", genres.get(54).getName());
        assertFalse(SITE.genres(json("genres.json"), Yakniga.GENRES, 1).hasMore);
    }

    @Test
    public void people() throws IOException {
        ProviderPage<GenrePOJO> authors = SITE.people(json("authors-p1.json"), Yakniga.AUTHORS, 1, false);
        assertEquals("of 100, those with full books", 51, authors.items.size());
        assertTrue("2151 pages", authors.hasMore);
        assertEquals("Владимова Марина", authors.items.get(0).getName());
        assertEquals(S + "/vladimova-marina?by_date=desc", authors.items.get(0).getUrl());
        assertEquals(1, authors.items.get(0).getReting());
        assertEquals("a leading space trimmed", "Кривушенкова Фарида", authors.items.get(1).getName());

        ProviderPage<GenrePOJO> nobody = Yakniga.parsePeople(json("authors-tail.json"), false);
        assertEquals(ProviderPage.Status.OK, nobody.status);
        assertTrue(nobody.items.isEmpty());
        assertTrue(nobody.hasMore);

        ProviderPage<GenrePOJO> readers = Yakniga.parsePeople(json("readers-p1.json"), true);
        assertEquals(83, readers.items.size());
        assertEquals("Потапов Павел", readers.items.get(0).getName());
        assertEquals(S + "/reader/potapov-pavel?by_date=desc", readers.items.get(0).getUrl());
        assertEquals("Ларионов Сергей", readers.items.get(1).getName());
        assertEquals(60, readers.items.get(1).getReting());
    }

    /** A page of the site's list holding nobody with full books is skipped, and the list remembers it. */
    @Test
    public void peopleWalkSkipsEmptyPages() throws IOException {
        List<String> asked = new ArrayList<>();
        String[] answers = {"authors-p1.json", "authors-tail.json", "authors-p2.json", "authors-p2.json"};
        SourceParser.PeopleCursor cursor = new SourceParser.PeopleCursor();
        com.fanok.audiobooks.model.BookPages.Fetch pages = url -> {
            asked.add(url);
            return json(answers[asked.size() - 1]);
        };
        assertEquals(51, Yakniga.walkPeople(pages, 1, false, cursor).items.size());
        ProviderPage<GenrePOJO> two = Yakniga.walkPeople(pages, 2, false, cursor);
        assertEquals(47, two.items.size());
        assertEquals("Котова Ирина", two.items.get(0).getName());
        assertEquals(1, cursor.skipped);
        Yakniga.walkPeople(pages, 3, false, cursor);
        assertEquals(Arrays.asList(Yakniga.AUTHORS + "?page=1", Yakniga.AUTHORS + "?page=2",
                Yakniga.AUTHORS + "?page=3", Yakniga.AUTHORS + "?page=4"), asked);

        JsonObject request = Yakniga.request(Yakniga.READERS + "?page=7");
        assertEquals(Yakniga.peopleList("readers"), Json.string(request, "query"));
        assertEquals("{\"p\":7,\"n\":100,\"s\":" + FULL + "}}", request.get("variables").toString());
    }

    @Test
    public void searchPeople() throws IOException {
        String king = SITE.searchUrl(SearchUrls.Category.AUTHORS, "Кинг", 1);
        assertEquals(S + "/search?type=authors&q=%D0%9A%D0%B8%D0%BD%D0%B3&page=1", king);
        assertEquals(S + "/search?type=readers&q=%D0%9A%D0%B8%D0%BD%D0%B3&page=1",
                SITE.searchUrl(SearchUrls.Category.PERFORMERS, "Кинг", 1));
        JsonObject request = Yakniga.request(king);
        assertEquals(Yakniga.SEARCH_PEOPLE, Json.string(request, "query"));
        assertEquals("Кинг", Json.string(request.getAsJsonObject("variables"), "t"));

        ProviderPage<GenrePOJO> authors = SITE.searchPeople(json("search-people.json"), 1, false);
        assertEquals("of 10, those with full books", 4, authors.items.size());
        assertFalse(authors.hasMore);
        assertEquals("Кинг-Смит Дик", authors.items.get(0).getName());
        assertEquals(S + "/king-smit-dik?by_date=desc", authors.items.get(0).getUrl());
        assertEquals(2, authors.items.get(0).getReting());
        assertEquals("Кинг Джули", authors.items.get(1).getName());
        ProviderPage<GenrePOJO> readers = SITE.searchPeople(json("search-people.json"), 1, true);
        assertEquals(2, readers.items.size());
        assertEquals(S + "/reader/kinge-dmitriy?by_date=desc", readers.items.get(0).getUrl());
        assertEquals(8, readers.items.get(0).getReting());
    }

    // ---------------------------------------------------------------- search

    @Test
    public void search() throws IOException {
        String url = SITE.searchUrl(SearchUrls.Category.BOOKS, "Панченко", 1);
        assertEquals(S + "/search?q=%D0%9F%D0%B0%D0%BD%D1%87%D0%B5%D0%BD%D0%BA%D0%BE&page=1", url);
        JsonObject request = Yakniga.request(url);
        assertEquals(Yakniga.SEARCH_BOOKS, Json.string(request, "query"));
        assertEquals("{\"t\":\"Панченко\",\"src\":[\"manual\",\"knigavuhe\",\"akniga\",\"aknig\"]}",
                request.get("variables").toString());

        ProviderPage<BookPOJO> found = SITE.searchResults(json("search.json"), 1);
        assertEquals("of 20 books, three LitRes trials left out", 17, found.items.size());
        assertFalse("one page", found.hasMore);
        BookPOJO first = found.items.get(0);
        assertEquals(S + "/harris-tomas/voshozhdenie-gannibala", first.getUrl());
        assertEquals("Восхождение Ганнибала", first.getName());
        assertEquals("Панченко Константин", first.getArtist());
        assertEquals(S + "/sergey-panchenko/vtoroe-prishestvie-s-t-i-k-s", found.items.get(16).getUrl());
        assertEquals("a search URL read as a listing", 17,
                SITE.listing(json("search.json"), url, 1).items.size());

        ProviderPage<BookPOJO> none = SITE.searchResults(json("search-none.json"), 1);
        assertEquals(ProviderPage.Status.EMPTY, none.status);
        assertFalse(none.hasMore);

        String two = SITE.searchUrl(SearchUrls.Category.BOOKS, "Панченко", 2);
        assertEquals(url.replace("&page=1", "&page=2"), two);
        Document past = SITE.fetch(two); // no request: the search answers in one page
        assertEquals(ProviderPage.Status.EMPTY, SITE.searchResults(past, 2).status);

        assertEquals("Мастер и Маргарита", Json.string(Yakniga.request(SITE.searchUrl(SearchUrls.Category.BOOKS,
                "Мастер и Маргарита", 1)).getAsJsonObject("variables"), "t"));
        assertEquals("c++", Json.string(Yakniga.request(SITE.searchUrl(SearchUrls.Category.BOOKS, "c++", 1))
                .getAsJsonObject("variables"), "t"));
        assertTrue("a search is not re-sorted", SITE.sorts(url).isEmpty());
    }

    // ---------------------------------------------------------------- book

    @Test
    public void description() throws IOException {
        DescriptionPOJO d = SITE.description(json("book.json"), BOOK);
        assertEquals("Автостопом по галактике", d.getTitle());
        assertEquals(S + "/yafile/4e015f47564b424a5a560a5d121d03571c06000655531c055e440346490b02041c505455.webp",
                d.getPoster());
        assertEquals("Дуглас Адамс", d.getAutor());
        assertEquals(S + "/duglas-adams?by_date=desc", d.getAutorUrl());
        assertEquals("Оргин Дмитрий", d.getArtist());
        assertEquals(S + "/reader/orgin-dmitriy?by_date=desc", d.getArtistUrl());
        assertEquals("Фантастика", d.getGenre());
        assertEquals(GENRE, d.getGenreUrl());
        assertEquals("Автостопом по Галактике (1)", d.getSeries());
        assertEquals(SERIES, d.getSeriesUrl());
        assertEquals("the chapters' 16445 s", "4 ч. 34 мин.", d.getTime());
        assertEquals("3", d.getLike());
        assertEquals("0", d.getDisLike());
        assertEquals("", d.getReiting());
        String[] paragraphs = d.getDescription().split("\n");
        assertEquals(4, paragraphs.length);
        assertTrue(paragraphs[0], paragraphs[0].startsWith("Аудиокнига Автостопом по галактике - Дуглас Адамс - это"));
        assertEquals("Слушать Дуглас Адамс - Автостопом по галактике онлайн, вперед к звездам!", paragraphs[3]);

        DescriptionPOJO seventh = SITE.description(json("book-series.json"),
                S + "/panchenko-andrey/rabota-nad-oshibkami-kniga-7-iskuplenie");
        assertEquals("Работа над ошибками. Книга 7. Искупление", seventh.getTitle());
        assertEquals("Работа над ошибками (7)", seventh.getSeries());
        assertEquals(S + "/series/rabota-nad-oshibkami", seventh.getSeriesUrl());
        assertEquals("of two genres the first", "Фантастика", seventh.getGenre());
        assertEquals("9 ч. 13 мин.", seventh.getTime());
        assertEquals("2", seventh.getLike());
        assertEquals("1", seventh.getDisLike());
        assertEquals(2, seventh.getDescription().split("\n").length);

        DescriptionPOJO blocked = SITE.description(json("book-blocked.json"),
                S + "/vladimir-korchagin/tayna-reki-zlyh-duhov");
        assertTrue("plain text", blocked.getDescription().startsWith("История о трех подростках"));
        assertEquals("", blocked.getSeries());

        BookPOJO card = SITE.book(json("book.json"), BOOK);
        assertEquals(BOOK, card.getUrl());
        assertEquals("Автостопом по галактике", card.getName());
    }

    @Test
    public void tracks() throws IOException {
        ArrayList<AudioPOJO> chapters = SITE.tracks(json("book.json"), API);
        assertEquals(36, chapters.size());
        AudioPOJO first = chapters.get(0);
        assertEquals("Глава_000", first.getName());
        assertEquals(S + "/yafile/4e04595d564a1e16594d04074e50590d58401a06574e06530405541b075055441006103a08.mp3",
                first.getUrl());
        assertEquals(316, first.getTime());
        assertEquals("Автостопом по галактике", first.getBookName());
        assertEquals("Глава_035", chapters.get(35).getName());
        int seconds = 0;
        for (AudioPOJO chapter : chapters) {
            seconds += chapter.getTime();
        }
        assertEquals(16445, seconds);

        ArrayList<AudioPOJO> seventh = SITE.tracks(json("book-series.json"), API);
        assertEquals(20, seventh.size());
        assertEquals("01. Глава_01", seventh.get(0).getName());
        assertEquals(1675, seventh.get(19).getTime());

        try {
            SITE.tracks(json("book-stub.json"), API);
            fail("a LitRes trial plays one fragment");
        } catch (BookRemovedException expected) {
            assertEquals(S + "/duglas-adams/restoran-u-kontsa-vselennoy", expected.getMessage());
        }
        try {
            SITE.tracks(json("book-blocked.json"), API);
            fail("the site refuses to play a blocked book");
        } catch (BookRemovedException expected) {
            assertEquals(S + "/vladimir-korchagin/tayna-reki-zlyh-duhov", expected.getMessage());
        }
    }

    @Test
    public void seriesTab() throws IOException {
        ArrayList<SeriesPOJO> series = SITE.series(json("book-series.json"),
                S + "/panchenko-andrey/rabota-nad-oshibkami-kniga-7-iskuplenie");
        assertEquals("of 8, three LitRes trials left out", 5, series.size());
        assertEquals(S + "/andrey-butorin/vykroyka", series.get(0).getUrl());
        assertEquals("Выкройка", series.get(0).getName());
        assertEquals("1", series.get(0).getNumber());
        assertEquals(S + "/panchenko-andrey/rabota-nad-oshibkami-kniga-4-za-chertoy", series.get(1).getUrl());
        assertEquals("4", series.get(1).getNumber());
        assertEquals("Работа над ошибками. Книга 7. Искупление", series.get(4).getName());
        assertEquals("7", series.get(4).getNumber());
        assertEquals(S + "/yafile/4e015f47564b424a5a560a5d121d0551041c06005557015f1e510942014a190752531a560f53.webp",
                series.get(4).getPhoto());
        assertEquals(2, SITE.series(json("book.json"), BOOK).size());
        assertTrue(SITE.series(json("book-blocked.json"), S + "/vladimir-korchagin/tayna-reki-zlyh-duhov")
                .isEmpty());
    }

    // ---------------------------------------------------------------- requests, pages and sorts

    @Test
    public void requests() {
        JsonObject index = Yakniga.request(Yakniga.INDEX);
        assertEquals(Yakniga.LIST_ALL, Json.string(index, "query"));
        assertEquals("{\"q\":" + FULL + ",\"by_date\":\"desc\"},\"p\":1,\"n\":30}", index.get("variables").toString());
        assertEquals("{\"q\":" + FULL + ",\"by_date\":\"desc\"},\"p\":2,\"n\":30}",
                Yakniga.request(SITE.pageUrl(Yakniga.INDEX, 2)).get("variables").toString());
        assertEquals("the curated list, newest first, as the site asks for it",
                "{\"q\":" + FULL + ",\"by_fixed_top\":\"desc\",\"by_date\":\"desc\"},\"p\":1,\"n\":30}",
                Yakniga.request(S + "/?by_fixed_top=desc").get("variables").toString());

        JsonObject genre = Yakniga.request(S + "/genres/fantastika?by_pop=desc&page=3");
        assertEquals(Yakniga.listIn("genre"), Json.string(genre, "query"));
        assertEquals("{\"a\":\"fantastika\",\"q\":" + FULL + ",\"by_pop\":\"desc\"},\"p\":3,\"n\":30}",
                genre.get("variables").toString());
        JsonObject author = Yakniga.request(S + "/panchenko-andrey?by_alphabet=asc");
        assertEquals(Yakniga.listIn("author"), Json.string(author, "query"));
        assertEquals("{\"a\":\"panchenko-andrey\",\"q\":" + FULL + ",\"by_alphabet\":\"asc\"},\"p\":1,\"n\":30}",
                author.get("variables").toString());
        assertEquals(Yakniga.listIn("reader"), Json.string(Yakniga.request(READER), "query"));
        JsonObject series = Yakniga.request(SERIES);
        assertEquals(Yakniga.listIn("series"), Json.string(series, "query"));
        assertEquals("by the numbers", "{\"a\":\"avtostopom-po-galaktike\",\"q\":" + FULL + "},\"p\":1,\"n\":30}",
                series.get("variables").toString());

        JsonObject book = Yakniga.request(BOOK);
        assertEquals(Yakniga.BOOK, Json.string(book, "query"));
        assertEquals("{\"a\":\"duglas-adams\",\"b\":\"avtostopom-po-galaktike\",\"s\":" + FULL + "},\"n\":100}",
                book.get("variables").toString());
        assertEquals(Yakniga.GENRE_LIST, Json.string(Yakniga.request(Yakniga.GENRES), "query"));
        assertEquals(Yakniga.peopleList("authors"), Json.string(Yakniga.request(Yakniga.AUTHORS), "query"));
        assertNull("an ebook", Yakniga.request(S + "/raisa-ryabuha/ebooks/podarok"));
    }

    @Test
    public void pages() {
        assertEquals(Yakniga.INDEX + "&page=2", SITE.pageUrl(Yakniga.INDEX, 2));
        assertEquals(Yakniga.INDEX, SITE.pageUrl(Yakniga.INDEX + "&page=2", 1));
        assertEquals(GENRE + "&page=3", SITE.pageUrl(GENRE + "&page=2", 3));
        assertEquals(SERIES + "?page=2", SITE.pageUrl(SERIES, 2));
        assertEquals(SERIES, SITE.pageUrl(SERIES + "?page=2", 1));
        assertEquals(3, Yakniga.page(GENRE + "&page=3"));
        assertEquals(1, Yakniga.page(GENRE));
    }

    /** As BooksPresenter does it ({@code ListingFilters.sorted}): the site-wide sort, then narrowed to the listing. */
    private static String sorted(Sort sort, String listing) {
        String url = SITE.sortUrl(sort, listing);
        String genre = SITE.genreOf(listing);
        return url.equals(listing) || genre == null ? url : SITE.inGenre(url, genre);
    }

    private static List<Sort> menu(String url) {
        List<Sort> sorts = new ArrayList<>();
        for (Sort.Entry entry : SITE.sorts(url)) {
            sorts.add(entry.sort);
        }
        return sorts;
    }

    @Test
    public void sorts() {
        List<Sort> all = Arrays.asList(Sort.NEW, Sort.POPULAR, Sort.RATING, Sort.TITLE);
        List<Sort> person = Arrays.asList(Sort.NEW, Sort.RATING, Sort.TITLE);
        assertEquals(all, menu(Yakniga.INDEX));
        assertEquals(all, menu(GENRE));
        assertEquals(person, menu(AUTHOR));
        assertEquals(person, menu(READER));
        assertTrue("a series keeps its order", SITE.sorts(SERIES).isEmpty());
        assertTrue(SITE.sorts(Yakniga.GENRES).isEmpty());
        assertTrue(SITE.sorts(Yakniga.AUTHORS).isEmpty());
        assertTrue(SITE.sorts(BOOK).isEmpty());

        assertEquals(Yakniga.INDEX, SITE.sortUrl(Sort.NEW, Yakniga.INDEX));
        assertEquals(S + "/?by_fixed_top=desc", sorted(Sort.POPULAR, Yakniga.INDEX));
        assertEquals(S + "/?by_pop=desc", sorted(Sort.RATING, Yakniga.INDEX));
        assertEquals(S + "/?by_alphabet=asc", sorted(Sort.TITLE, Yakniga.INDEX));
        assertEquals(S + "/genres/fantastika?by_fixed_top=desc", sorted(Sort.POPULAR, GENRE));
        assertEquals(S + "/genres/fantastika?by_pop=desc", sorted(Sort.RATING, GENRE));
        assertEquals(GENRE, sorted(Sort.NEW, S + "/genres/fantastika?by_pop=desc"));
        assertEquals(S + "/panchenko-andrey?by_alphabet=asc", sorted(Sort.TITLE, AUTHOR));
        assertEquals(S + "/reader/shirokova-lyudmila?by_pop=desc", sorted(Sort.RATING, READER));
        for (String listing : new String[]{Yakniga.INDEX, GENRE, AUTHOR, READER}) {
            for (Sort sort : menu(listing)) {
                String url = sorted(sort, listing);
                assertTrue(url + " keeps its menu", menu(url).contains(sort));
                assertEquals(url + " " + sort, url, sorted(sort, url));
            }
        }
        assertEquals(S + "/genres/fantastika", SITE.genreOf(GENRE + "&page=2"));
        assertEquals(S + "/panchenko-andrey", SITE.genreOf(AUTHOR));
        assertNull(SITE.genreOf(Yakniga.INDEX));
        assertNull(SITE.genreOf(SERIES));
        assertNull(SITE.sortUrl(Sort.COMMENTS, Yakniga.INDEX));
        assertNull(SITE.sortUrl(Sort.DURATION, Yakniga.INDEX));
        assertNull(SITE.sortUrl(Sort.POPULAR_WEEK, Yakniga.INDEX));
    }

    @Test
    public void kinds() {
        assertEquals(Yakniga.Kind.ALL, Yakniga.kind(Yakniga.INDEX));
        assertEquals(Yakniga.Kind.GENRES, Yakniga.kind(Yakniga.GENRES));
        assertEquals(Yakniga.Kind.AUTHORS, Yakniga.kind(Yakniga.AUTHORS));
        assertEquals(Yakniga.Kind.READERS, Yakniga.kind(Yakniga.READERS));
        assertEquals(Yakniga.Kind.GENRE, Yakniga.kind(GENRE));
        assertEquals(Yakniga.Kind.AUTHOR, Yakniga.kind(AUTHOR));
        assertEquals(Yakniga.Kind.READER, Yakniga.kind(READER));
        assertEquals(Yakniga.Kind.SERIES, Yakniga.kind(SERIES));
        assertEquals(Yakniga.Kind.BOOK, Yakniga.kind(BOOK));
        assertEquals(Yakniga.Kind.SEARCH, Yakniga.kind(SITE.searchUrl(SearchUrls.Category.BOOKS, "x", 1)));
        assertNull(Yakniga.kind("not a url"));
    }
}
