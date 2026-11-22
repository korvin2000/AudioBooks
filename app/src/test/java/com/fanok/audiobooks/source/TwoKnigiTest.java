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

import okhttp3.HttpUrl;

/**
 * 2knigi.net GraphQL answers saved on 2026-11-21 (see src/test/resources/fixtures/two-knigi), each the answer to the
 * very request {@link TwoKnigi#request} builds for its URL: yakniga.org's engine with this site's routes.
 */
public class TwoKnigiTest {

    private static final TwoKnigi SITE = new TwoKnigi();
    private static final String S = TwoKnigi.SERVER;
    private static final String API = S + "/graphql";
    private static final String GENRE = S + "/ganri/7539-fantastika?by_date=desc";
    private static final String AUTHOR = S + "/avtor/1415512-panchenko-andrey?by_date=desc";
    private static final String READER = S + "/golos/93877-krivosheev-vadim?by_date=desc";
    private static final String SERIES = S + "/serii/syn-pomeschika";
    private static final String BOOK = S + "/kniga/2409897-syn-pomeschika-kniga-14_semin-nikita";
    private static final String FULL = "{\"by_source\":[\"manual\",\"knigavuhe\",\"akniga\",\"aknig\"]";

    private static JsonPage json(String name) throws IOException {
        return BookPageFixtures.json("two-knigi/" + name, API);
    }

    private static ProviderPage<BookPOJO> listing(String name) throws IOException {
        return SITE.parseListing(json(name));
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
        assertEquals(30, page.items.size());
        assertTrue("2121 pages", page.hasMore);
        BookPOJO first = page.items.get(0);
        assertEquals(S + "/kniga/2437176-nepravilnaya-kniga-1-eliksir-rektora_shneyder-natalya_sheyd-eris",
                first.getUrl());
        assertEquals("Неправильная. Книга 1. Эликсир ректора", first.getName());
        assertEquals(S + "/2lib/1e015a4e0713154d5b5c575b104a03050c16550d570f000a1f565e4303134c0b52511c025755.webp",
                first.getPhoto());
        assertEquals("Шнейдер Наталья, Шейд Эрис", first.getAutor());
        assertEquals(S + "/avtor/121506-shneyder-natalya?by_date=desc", first.getUrlAutor());
        assertEquals("Хохлов Денис, Звонкая Ирина", first.getArtist());
        assertEquals(S + "/golos/106393-hohlov-denis?by_date=desc", first.getUrlArtist());
        assertEquals("28909 s", "8 ч. 1 мин.", first.getTime());
        assertEquals("the first of its genres", "Фэнтези", first.getGenre());
        assertEquals(S + "/ganri/6020-fentezi?by_date=desc", first.getUrlGenre());
        assertEquals("Неправильная", first.getSeries());
        assertEquals(S + "/serii/nepravilnaya", first.getUrlSeries());
        assertEquals(S + "/kniga/2416981-kafedra-kniga-4_lahov-igor", page.items.get(29).getUrl());

        ProviderPage<BookPOJO> two = listing("index-p2.json");
        assertEquals("a book without a duration left out", 29, two.items.size());
        assertTrue(two.hasMore);
        assertEquals(S + "/kniga/2411108-mo-syan_olga-ruban", two.items.get(0).getUrl());
        assertNull(two.items.get(0).getSeries());
        assertEquals("3232 s", "53 мин.", two.items.get(1).getTime());
        assertFalse(urls(two.items).contains(S + "/kniga/2403961-don-pedro_andrey-shoppert"));

        ProviderPage<BookPOJO> last = listing("index-last.json");
        assertEquals("of 5, two without a duration", 3, last.items.size());
        assertFalse("page 2121 of 2121", last.hasMore);
        assertEquals(S + "/kniga/4487-oblako_lopato-semen", last.items.get(2).getUrl());
        assertNull("a book without a genre", last.items.get(0).getGenre());

        ProviderPage<BookPOJO> beyond = listing("index-beyond.json");
        assertEquals(ProviderPage.Status.EMPTY, beyond.status);
        assertFalse(beyond.hasMore);
    }

    @Test
    public void sortedListings() throws IOException {
        ProviderPage<BookPOJO> popular = listing("popular-p1.json");
        assertEquals(30, popular.items.size());
        assertTrue("the curated 50, two pages", popular.hasMore);
        assertEquals(S + "/kniga/1917020-revizor-vozvraschenie-v-sssr-62_vinterkey-serzh", popular.items.get(0).getUrl());

        BookPOJO top = listing("rating-p1.json").items.get(0);
        assertEquals(S + "/kniga/470631-karamazovy-chast-shestaya_mihail-zhukovin", top.getUrl());
        assertEquals("41 мин.", top.getTime());

        ProviderPage<BookPOJO> title = listing("title-p1.json");
        assertEquals("of 30, one without a duration, two without chapters", 27, title.items.size());
        assertEquals("03:33", title.items.get(0).getName());
        assertEquals("0,5", title.items.get(1).getName());
        assertFalse("chaptersCount 0: no chapter to play",
                urls(title.items).contains(S + "/kniga/16177-100-dney-schastya_britstsi-fausto"));
        assertFalse(urls(title.items).contains(
                S + "/kniga/146412-100-zhenschin-100-stiley-ikony-stilya-izmenivshie-modu_blanchard-temsin"));
        assertEquals("chaptersCount 1", S + "/kniga/1769026-03-33_korben-sayks", title.items.get(0).getUrl());
    }

    @Test
    public void genreAuthorReaderAndSeriesListings() throws IOException {
        ProviderPage<BookPOJO> genre = listing("genre-p1.json");
        assertEquals(29, genre.items.size());
        assertTrue("547 pages", genre.hasMore);
        assertEquals(S + "/kniga/2416708-zapadnya_deymon-nayt", genre.items.get(0).getUrl());
        assertEquals("Сергей Карелин, Вадим Фарг", genre.items.get(28).getAutor());
        assertEquals(S + "/kniga/2379580-lekar_mihail-tyrin", listing("genre-rating-p1.json").items.get(0).getUrl());
        ProviderPage<BookPOJO> genreTop = listing("genre-popular-p1.json");
        assertEquals(S + "/kniga/2413467-teplo-nashih-tel-kniga-1_marion-ayzek", genreTop.items.get(0).getUrl());
        assertTrue("14 pages", genreTop.hasMore);

        String kniga = S + "/kniga/";
        ProviderPage<BookPOJO> author = listing("author.json");
        assertEquals(Arrays.asList(kniga + "2428755-polyarnaya-zvezda-kniga-1-kayur_panchenko-andrey",
                kniga + "2397745-rabota-nad-oshibkami-kniga-8-dikiy_panchenko-andrey",
                kniga + "2389821-rabota-nad-oshibkami-kniga-7-iskuplenie_panchenko-andrey",
                kniga + "2369668-rabota-nad-oshibkami-kniga-6-kamaradas_panchenko-andrey",
                kniga + "2347891-rabota-nad-oshibkami-kniga-5-chuzhaya-territriya_panchenko-andrey",
                kniga + "1921150-rabota-nad-oshibkami-kniga-4-za-chertoy_panchenko-andrey"), urls(author.items));
        assertFalse(author.hasMore);
        assertEquals("(Fan12) Альберт", author.items.get(5).getArtist());
        assertEquals(S + "/golos/103565-fan12-albert?by_date=desc", author.items.get(5).getUrlArtist());
        assertEquals("Полярная звезда (Панченко)", author.items.get(0).getSeries());
        assertEquals(S + "/serii/polyarnaya-zvezda-panchenko", author.items.get(0).getUrlSeries());
        List<BookPOJO> byTitle = listing("author-title.json").items;
        assertEquals("by title", kniga + "1921150-rabota-nad-oshibkami-kniga-4-za-chertoy_panchenko-andrey",
                byTitle.get(1).getUrl());
        assertEquals(kniga + "2397745-rabota-nad-oshibkami-kniga-8-dikiy_panchenko-andrey", byTitle.get(5).getUrl());

        ProviderPage<BookPOJO> reader = listing("reader.json");
        assertEquals(14, reader.items.size());
        assertFalse(reader.hasMore);
        assertEquals(BOOK, reader.items.get(0).getUrl());
        assertEquals("Шимохин Дмитрий, Коллингвуд Виктор", reader.items.get(2).getAutor());
        assertEquals(S + "/avtor/1424458-shimohin-dmitriy?by_date=desc", reader.items.get(2).getUrlAutor());

        ProviderPage<BookPOJO> series = listing("series.json");
        assertEquals("the series' full books, by their numbers",
                Arrays.asList(kniga + "2265473-syn-pomeschika-kniga-10_semin-nikita",
                        kniga + "2372237-syn-pomeschika-kniga-12_semin-nikita",
                        kniga + "2381337-syn-pomeschika-kniga-13_semin-nikita", BOOK), urls(series.items));
        assertFalse(series.hasMore);
    }

    @Test
    public void anAnswerWithoutDataIsAnError() throws IOException {
        JsonPage gone = json("not-found.json");
        assertEquals("Cannot return null for non-nullable field Query.book", Yakniga.error(gone));
        assertNull(Yakniga.error(json("book.json")));
        assertEquals("no book on the page", "", SITE.parseDescription(gone).getTitle());
        try {
            SITE.parseListing(gone);
            fail("no answer is no listing");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Query.book"));
        }
    }

    // ---------------------------------------------------------------- genres and people

    @Test
    public void genres() throws IOException {
        ArrayList<GenrePOJO> genres = SITE.parseGenres(json("genres.json"));
        assertEquals(57, genres.size());
        assertEquals("Фантастика", genres.get(0).getName());
        assertEquals(GENRE, genres.get(0).getUrl());
        assertEquals("", genres.get(0).getDescription());
        assertEquals("Фэнтези", genres.get(1).getName());
        assertEquals("Фантастика", genres.get(1).getDescription());
        assertEquals("Любовное фэнтези", genres.get(2).getName());
        assertEquals("Фантастика / Фэнтези", genres.get(2).getDescription());
        assertEquals(S + "/ganri/13650-lyubovnoe-fentezi?by_date=desc", genres.get(2).getUrl());
        assertEquals("Детективы, триллеры", genres.get(13).getName());
        assertEquals("Попаданцы", genres.get(48).getName());
        assertEquals(S + "/ganri/8652-popadantsy?by_date=desc", genres.get(48).getUrl());
        assertEquals("Тру-крайм", genres.get(56).getName());
        assertFalse(SITE.genres(json("genres.json"), TwoKnigi.GENRES, 1).hasMore);
    }

    @Test
    public void people() throws IOException {
        ProviderPage<GenrePOJO> authors = SITE.people(json("authors-p1.json"), TwoKnigi.AUTHORS, 1, false);
        assertEquals("of 100, those with full books", 51, authors.items.size());
        assertTrue("2179 pages", authors.hasMore);
        assertEquals("Владимова Марина", authors.items.get(0).getName());
        assertEquals(S + "/avtor/11508-vladimova-marina?by_date=desc", authors.items.get(0).getUrl());
        assertEquals(1, authors.items.get(0).getReting());

        ProviderPage<GenrePOJO> readers = SITE.parsePeople(json("readers-p1.json"), true);
        assertEquals(83, readers.items.size());
        assertEquals("Ларионов Сергей", readers.items.get(1).getName());
        assertEquals(S + "/golos/11060-larionov-sergey?by_date=desc", readers.items.get(1).getUrl());
        assertEquals(60, readers.items.get(1).getReting());

        List<String> asked = new ArrayList<>();
        SITE.walkPeople(url -> {
            asked.add(url);
            return json("readers-p1.json");
        }, 2, true, new SourceParser.PeopleCursor());
        assertEquals(Arrays.asList(TwoKnigi.READERS + "?page=2"), asked);
        JsonObject request = SITE.request(TwoKnigi.READERS + "?page=2");
        assertEquals(SITE.peopleList("readers"), Json.string(request, "query"));
        assertTrue(Json.string(request, "query").contains("collection{name aliasName uniqUrl books("));
        assertEquals("{\"p\":2,\"n\":100,\"s\":" + FULL + "}}", request.get("variables").toString());
    }

    @Test
    public void searchPeople() throws IOException {
        String king = SITE.searchUrl(SearchUrls.Category.AUTHORS, "Кинг", 1);
        assertEquals(S + "/poisk?type=authors&q=%D0%9A%D0%B8%D0%BD%D0%B3&page=1", king);
        JsonObject request = SITE.request(king);
        assertEquals(SITE.searchPeopleQuery, Json.string(request, "query"));
        assertEquals("Кинг", Json.string(request.getAsJsonObject("variables"), "t"));

        ProviderPage<GenrePOJO> authors = SITE.searchPeople(json("search-people.json"), 1, false);
        assertEquals("of 10, those with full books", 4, authors.items.size());
        assertEquals("Кинг-Смит Дик", authors.items.get(0).getName());
        assertEquals(S + "/avtor/103040-king-smit-dik?by_date=desc", authors.items.get(0).getUrl());
        assertEquals("a leading space trimmed", "Кинг Джули", authors.items.get(1).getName());
        ProviderPage<GenrePOJO> readers = SITE.searchPeople(json("search-people.json"), 1, true);
        assertEquals(2, readers.items.size());
        assertEquals(S + "/golos/18452-kinge-dmitriy?by_date=desc", readers.items.get(0).getUrl());
        assertEquals(8, readers.items.get(0).getReting());
    }

    // ---------------------------------------------------------------- search

    @Test
    public void search() throws IOException {
        String url = SITE.searchUrl(SearchUrls.Category.BOOKS, "Сын помещика", 1);
        assertEquals(S + "/poisk?q=%D0%A1%D1%8B%D0%BD+%D0%BF%D0%BE%D0%BC%D0%B5%D1%89%D0%B8%D0%BA%D0%B0&page=1", url);
        JsonObject request = SITE.request(url);
        assertEquals(SITE.searchBooksQuery, Json.string(request, "query"));
        assertEquals("{\"t\":\"Сын помещика\",\"src\":[\"manual\",\"knigavuhe\",\"akniga\",\"aknig\"]}",
                request.get("variables").toString());

        ProviderPage<BookPOJO> found = SITE.searchResults(json("search.json"), 1);
        assertEquals("four books, the ebooks and a series left out", 4, found.items.size());
        assertFalse("one page", found.hasMore);
        assertEquals(S + "/kniga/2265473-syn-pomeschika-kniga-10_semin-nikita", found.items.get(0).getUrl());
        assertEquals("Кривошеев Вадим", found.items.get(0).getArtist());
        assertEquals(BOOK, found.items.get(3).getUrl());
        assertEquals("a search URL read as a listing", 4, SITE.listing(json("search.json"), url, 1).items.size());

        ProviderPage<BookPOJO> none = SITE.searchResults(json("search-none.json"), 1);
        assertEquals(ProviderPage.Status.EMPTY, none.status);
        assertFalse(none.hasMore);
        String two = SITE.searchUrl(SearchUrls.Category.BOOKS, "Сын помещика", 2);
        assertEquals(ProviderPage.Status.EMPTY, SITE.searchResults(SITE.fetch(two), 2).status); // no request
        assertTrue("a search is not re-sorted", SITE.sorts(url).isEmpty());
    }

    // ---------------------------------------------------------------- book

    @Test
    public void description() throws IOException {
        DescriptionPOJO d = SITE.description(json("book.json"), BOOK);
        assertEquals("Сын помещика. Книга 14", d.getTitle());
        assertEquals(S + "/2lib/1e015a4e0713154d5b5c575b104a03050a16550d5108010f1f565e4303134c0b52511c025755.webp",
                d.getPoster());
        assertEquals("Семин Никита", d.getAutor());
        assertEquals(S + "/avtor/1412341-semin-nikita?by_date=desc", d.getAutorUrl());
        assertEquals("Кривошеев Вадим", d.getArtist());
        assertEquals(READER, d.getArtistUrl());
        assertEquals("Попаданцы", d.getGenre());
        assertEquals(S + "/ganri/8652-popadantsy?by_date=desc", d.getGenreUrl());
        assertEquals("Сын помещика (14)", d.getSeries());
        assertEquals(SERIES, d.getSeriesUrl());
        assertEquals("the chapters' 28697 s", "7 ч. 58 мин.", d.getTime());
        assertEquals("1", d.getLike());
        assertEquals("0", d.getDisLike());
        String[] paragraphs = d.getDescription().split("\n");
        assertEquals(2, paragraphs.length);
        assertTrue(paragraphs[0], paragraphs[0].startsWith("Четырнадцатый том цикла «Сын помещика» Никиты Семина"));

        BookPOJO card = SITE.book(json("book.json"), BOOK);
        assertEquals(BOOK, card.getUrl());
        assertEquals("Сын помещика. Книга 14", card.getName());
    }

    @Test
    public void tracks() throws IOException {
        ArrayList<AudioPOJO> chapters = SITE.tracks(json("book.json"), API);
        assertEquals(20, chapters.size());
        AudioPOJO first = chapters.get(0);
        assertEquals("01. Глава_01", first.getName());
        assertEquals(S + "/2lib/1e045c5407124911584759064c075f5e534a490a570b1e0b04070005514e025005111054133a05.mp3",
                first.getUrl());
        assertEquals(1677, first.getTime());
        assertEquals("Сын помещика. Книга 14", first.getBookName());
        AudioPOJO last = chapters.get(19);
        assertEquals("20. Глава_20", last.getName());
        assertEquals(S + "/2lib/1e045c5407124911584759064c075f5e534a490a570b1e0b04070005514e025005111054133a0604.mp3",
                last.getUrl());
        assertEquals(1223, last.getTime());
        int seconds = 0;
        for (AudioPOJO chapter : chapters) {
            seconds += chapter.getTime();
        }
        assertEquals(28697, seconds);

        try {
            SITE.tracks(json("book-stub.json"), API);
            fail("a LitRes trial plays one fragment");
        } catch (BookRemovedException expected) {
            assertEquals(S + "/kniga/1925469-syn-pomeschika-10_nikita-semin", expected.getMessage());
        }
    }

    @Test
    public void seriesTab() throws IOException {
        ArrayList<SeriesPOJO> series = SITE.series(json("book.json"), BOOK);
        assertEquals(4, series.size());
        assertEquals(S + "/kniga/2265473-syn-pomeschika-kniga-10_semin-nikita", series.get(0).getUrl());
        assertEquals("Сын помещика. Книга 10", series.get(0).getName());
        assertEquals("10", series.get(0).getNumber());
        assertEquals(BOOK, series.get(3).getUrl());
        assertEquals("14", series.get(3).getNumber());
        assertEquals(S + "/2lib/1e015a4e0713154d5b5c575b104a03030916550b520d06011f565e4303134c0b52511c025755.webp",
                series.get(0).getPhoto());
    }

    // ---------------------------------------------------------------- requests, routes, pages and sorts

    @Test
    public void requests() {
        JsonObject index = SITE.request(TwoKnigi.INDEX);
        assertEquals(SITE.listAllQuery, Json.string(index, "query"));
        assertTrue("cards name their pages and count their chapters",
                SITE.listAllQuery.contains(" uniqUrl authorAlias authorName duration chaptersCount ")
                && SITE.listAllQuery.contains("authors{name aliasName uniqUrl}"));
        assertEquals("{\"q\":" + FULL + ",\"by_date\":\"desc\"},\"p\":2,\"n\":30}",
                SITE.request(SITE.pageUrl(TwoKnigi.INDEX, 2)).get("variables").toString());

        JsonObject genre = SITE.request(S + "/ganri/7539-fantastika?by_pop=desc&page=3");
        assertEquals(SITE.listIn("genre"), Json.string(genre, "query"));
        assertTrue(SITE.listIn("genre").contains("x:genre(uniqUrl:$a){"));
        assertEquals("{\"a\":\"7539-fantastika\",\"q\":" + FULL + ",\"by_pop\":\"desc\"},\"p\":3,\"n\":30}",
                genre.get("variables").toString());
        JsonObject author = SITE.request(S + "/avtor/1415512-panchenko-andrey?by_alphabet=asc");
        assertEquals(SITE.listIn("author"), Json.string(author, "query"));
        assertEquals("{\"a\":\"1415512-panchenko-andrey\",\"q\":" + FULL + ",\"by_alphabet\":\"asc\"},\"p\":1,\"n\":30}",
                author.get("variables").toString());
        assertEquals(SITE.listIn("reader"), Json.string(SITE.request(READER), "query"));
        JsonObject series = SITE.request(SERIES);
        assertTrue("a series by its alias", Json.string(series, "query").contains("x:series(aliasName:$a){"));
        assertEquals("{\"a\":\"syn-pomeschika\",\"q\":" + FULL + "},\"p\":1,\"n\":30}",
                series.get("variables").toString());

        JsonObject book = SITE.request(BOOK);
        assertEquals(SITE.bookQuery, Json.string(book, "query"));
        assertTrue(SITE.bookQuery.startsWith("query($a:String,$s:JSON,$n:Int){x:book(uniqUrl:$a){"));
        assertEquals("{\"a\":\"2409897-syn-pomeschika-kniga-14_semin-nikita\",\"s\":" + FULL + "},\"n\":100}",
                book.get("variables").toString());
        assertEquals(SITE.genresQuery, Json.string(SITE.request(TwoKnigi.GENRES), "query"));
        assertEquals(SITE.peopleList("authors"), Json.string(SITE.request(TwoKnigi.AUTHORS), "query"));
        assertNull("the curated new books: not an app URL", SITE.request(S + "/novinki"));
        assertNull("a yakniga.org route", SITE.request(S + "/semin-nikita/syn-pomeschika-kniga-14"));
    }

    /** Every URL the cards and pages build is the site's own page and opens again as what it is. */
    @Test
    public void routesRoundTrip() throws IOException {
        BookPOJO card = listing("index-p1.json").items.get(0);
        assertEquals(Yakniga.Kind.BOOK, SITE.kind(card.getUrl()));
        assertEquals(Yakniga.Kind.AUTHOR, SITE.kind(card.getUrlAutor()));
        assertEquals(Yakniga.Kind.READER, SITE.kind(card.getUrlArtist()));
        assertEquals(Yakniga.Kind.GENRE, SITE.kind(card.getUrlGenre()));
        assertEquals(Yakniga.Kind.SERIES, SITE.kind(card.getUrlSeries()));
        assertEquals(Yakniga.Kind.ALL, SITE.kind(TwoKnigi.INDEX));
        assertEquals(Yakniga.Kind.GENRES, SITE.kind(TwoKnigi.GENRES));
        assertEquals(Yakniga.Kind.AUTHORS, SITE.kind(TwoKnigi.AUTHORS));
        assertEquals(Yakniga.Kind.READERS, SITE.kind(TwoKnigi.READERS));
        assertEquals(Yakniga.Kind.SEARCH, SITE.kind(SITE.searchUrl(SearchUrls.Category.PERFORMERS, "x", 1)));
        assertEquals("a deep link to a book, with a tracking query", "2409897-syn-pomeschika-kniga-14_semin-nikita",
                Json.string(SITE.request(BOOK + "?utm_source=tg").getAsJsonObject("variables"), "a"));
        assertEquals("1412341-semin-nikita", Json.string(SITE.request(
                SITE.description(json("book.json"), BOOK).getAutorUrl()).getAsJsonObject("variables"), "a"));

        List<String> built = new ArrayList<>(Arrays.asList(card.getUrl(), card.getUrlAutor(), card.getUrlArtist(),
                card.getUrlGenre(), card.getUrlSeries(), SITE.pageUrl(GENRE, 2), SITE.pageUrl(SERIES, 2)));
        for (String listing : new String[]{TwoKnigi.INDEX, GENRE, AUTHOR, READER}) {
            for (Sort sort : menu(listing)) {
                built.add(sorted(sort, listing));
                built.add(SITE.pageUrl(sorted(sort, listing), 2));
            }
        }
        for (String url : built) {
            assertEquals(url, "2knigi.net", HttpUrl.get(url).host());
            assertTrue(url, SITE.request(url) != null);
        }
    }

    @Test
    public void pages() {
        assertEquals(TwoKnigi.INDEX + "&page=2", SITE.pageUrl(TwoKnigi.INDEX, 2));
        assertEquals(TwoKnigi.INDEX, SITE.pageUrl(TwoKnigi.INDEX + "&page=2", 1));
        assertEquals(GENRE + "&page=3", SITE.pageUrl(GENRE + "&page=2", 3));
        assertEquals(SERIES + "?page=2", SITE.pageUrl(SERIES, 2));
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
        assertEquals(all, menu(TwoKnigi.INDEX));
        assertEquals(all, menu(GENRE));
        assertEquals(person, menu(AUTHOR));
        assertEquals(person, menu(READER));
        assertTrue("a series keeps its order", SITE.sorts(SERIES).isEmpty());
        assertTrue(SITE.sorts(TwoKnigi.GENRES).isEmpty());
        assertTrue(SITE.sorts(BOOK).isEmpty());

        assertEquals(TwoKnigi.INDEX, SITE.sortUrl(Sort.NEW, TwoKnigi.INDEX));
        assertEquals(S + "/?by_fixed_top=desc", sorted(Sort.POPULAR, TwoKnigi.INDEX));
        assertEquals(S + "/?by_pop=desc", sorted(Sort.RATING, TwoKnigi.INDEX));
        assertEquals(S + "/?by_alphabet=asc", sorted(Sort.TITLE, TwoKnigi.INDEX));
        assertEquals(S + "/ganri/7539-fantastika?by_fixed_top=desc", sorted(Sort.POPULAR, GENRE));
        assertEquals(S + "/ganri/7539-fantastika?by_pop=desc", sorted(Sort.RATING, GENRE));
        assertEquals(GENRE, sorted(Sort.NEW, S + "/ganri/7539-fantastika?by_pop=desc"));
        assertEquals(S + "/avtor/1415512-panchenko-andrey?by_alphabet=asc", sorted(Sort.TITLE, AUTHOR));
        assertEquals(S + "/golos/93877-krivosheev-vadim?by_pop=desc", sorted(Sort.RATING, READER));
        for (String listing : new String[]{TwoKnigi.INDEX, GENRE, AUTHOR, READER}) {
            for (Sort sort : menu(listing)) {
                String url = sorted(sort, listing);
                assertTrue(url + " keeps its menu", menu(url).contains(sort));
                assertEquals(url + " " + sort, url, sorted(sort, url));
            }
        }
        assertEquals(S + "/ganri/7539-fantastika", SITE.genreOf(GENRE + "&page=2"));
        assertNull(SITE.genreOf(TwoKnigi.INDEX));
        assertNull(SITE.genreOf(SERIES));
        assertNull(SITE.sortUrl(Sort.COMMENTS, TwoKnigi.INDEX));
        assertNull(SITE.sortUrl(Sort.TOP_100, TwoKnigi.INDEX));
    }
}
