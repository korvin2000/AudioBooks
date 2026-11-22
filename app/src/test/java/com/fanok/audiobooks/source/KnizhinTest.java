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
import com.fanok.audiobooks.pojo.ComentsPOJO;
import com.fanok.audiobooks.pojo.DescriptionPOJO;
import com.fanok.audiobooks.pojo.GenrePOJO;
import com.fanok.audiobooks.pojo.SeriesPOJO;
import com.fanok.audiobooks.search.ListingFilters;
import com.fanok.audiobooks.search.ProviderPage;
import com.fanok.audiobooks.search.SearchUrls;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** knizhin.net API answers saved on 2026-11-21 (see src/test/resources/fixtures/knizhin). */
public class KnizhinTest {

    private static final Knizhin SITE = new Knizhin();
    private static final String S = Knizhin.SERVER;
    private static final String API = S + "/api/";
    private static final String GENRE = Knizhin.INDEX + "&genre=audiospektakl";
    private static final String AUTHOR = API + "authors/stiven-king?audio=full";
    private static final String READER = API + "narrators/scaners?audio=full";
    private static final String BOOK = S + "/book/ne-vyklyuchaj-svet/";
    /** Where lis10book.com keeps the covers of its recent books, which knizhin.net's records point at. */
    private static final String COVERS = Lis10book.SERVER + "/wp-content/uploads/covers/";

    private static JsonPage json(String name, String url) throws IOException {
        return BookPageFixtures.json("knizhin/" + name, url);
    }

    private static ProviderPage<BookPOJO> listing(String name, String url) throws IOException {
        return SITE.parseListing(json(name, url));
    }

    private static JsonPage book(String name, String slug) throws IOException {
        return json(name, API + "books/" + slug);
    }

    // ---------------------------------------------------------------- listings

    @Test
    public void indexCardsAndPages() throws IOException {
        ProviderPage<BookPOJO> page = listing("listing-index-p1.json", Knizhin.INDEX);
        assertEquals(ProviderPage.Status.OK, page.status);
        assertEquals(24, page.items.size());
        assertTrue("2825 pages", page.hasMore);
        BookPOJO first = page.items.get(0);
        assertEquals(S + "/book/fenomen-cheloveka/", first.getUrl());
        assertEquals("Феномен человека", first.getName());
        assertNull("the cards name no cover", first.getPhoto());
        assertEquals("Пьер Тейяр де Шарден", first.getAutor());
        assertEquals(API + "authors/per-tejyar-de-sharden?audio=full", first.getUrlAutor());
        assertEquals("Марина Сычева", first.getArtist());
        assertEquals(API + "narrators/marina-sycheva?audio=full", first.getUrlArtist());
        assertEquals("43680 s", "12 ч. 8 мин.", first.getTime());
        assertEquals("uknigRating 0 is no rating", "0", first.getReting());
        assertEquals(S + "/book/strannaya-orhideya/", page.items.get(23).getUrl());

        ProviderPage<BookPOJO> second = listing("listing-index-p2.json", SITE.pageUrl(Knizhin.INDEX, 2));
        assertEquals(Knizhin.INDEX + "&page=2", SITE.pageUrl(Knizhin.INDEX, 2));
        assertEquals(24, second.items.size());
        assertTrue(second.hasMore);
        assertEquals("Оторопь", second.items.get(0).getName());
        assertEquals("Игорь Минаков, Пётр Алмазный", second.items.get(23).getAutor());

        ProviderPage<BookPOJO> last = listing("listing-index-last.json", SITE.pageUrl(Knizhin.INDEX, 2825));
        assertEquals(16, last.items.size());
        assertFalse(last.hasMore);
        BookPOJO fandorin = last.items.get(15);
        assertEquals(S + "/book/fandorin-nikolas-3-f-m/", fandorin.getUrl());
        assertEquals("Борис Акунин", fandorin.getAutor());
        assertEquals("Александр Клюквин", fandorin.getArtist());
        assertEquals("16 ч. 20 мин.", fandorin.getTime());
        assertEquals("4.75", fandorin.getReting());
        assertEquals(S + "/book/na-beregu/", last.items.get(0).getUrl());
    }

    @Test
    public void sortedListings() throws IOException {
        BookPOJO top = listing("listing-popular-p1.json", Knizhin.INDEX + "&sort=popular").items.get(0);
        assertEquals(S + "/book/mir-valdiry-rosgard-1-8-gospodstvo-klana-nespyashhih/", top.getUrl());
        assertEquals("4.63", top.getReting());
        ProviderPage<BookPOJO> rated = listing("listing-rating-p1.json", Knizhin.INDEX + "&sort=rating");
        assertEquals(S + "/book/naslednicza-poloza/", rated.items.get(0).getUrl());
        assertTrue(rated.hasMore);
        ProviderPage<BookPOJO> longest = listing("listing-duration-p1.json", Knizhin.INDEX + "&sort=duration");
        assertEquals(S + "/book/drevnie-tajny-2/", longest.items.get(0).getUrl());
        assertEquals("538860 s", "149 ч. 41 мин.", longest.items.get(0).getTime());
        assertEquals("496800 s", "138 ч.", longest.items.get(1).getTime());

        ProviderPage<BookPOJO> over10h = listing("listing-long.json", Knizhin.INDEX + "&dur=long");
        assertEquals(24, over10h.items.size());
        assertTrue("588 pages", over10h.hasMore);
        assertEquals(S + "/book/provincziya-u-morya/", over10h.items.get(23).getUrl());
    }

    @Test
    public void genreAuthorAndSeriesListings() throws IOException {
        ProviderPage<BookPOJO> genre = listing("genre-p1.json", GENRE);
        assertEquals(24, genre.items.size());
        assertTrue("70 pages", genre.hasMore);
        assertEquals("Альбом-трибьют. Посвящение поэту Владимиру Маяковскому", genre.items.get(0).getName());
        assertEquals(S + "/book/gerda-4/", genre.items.get(23).getUrl());

        ProviderPage<BookPOJO> author = listing("author.json", AUTHOR);
        assertEquals(24, author.items.size());
        assertTrue("348 books with full audio", author.hasMore);
        assertEquals(S + "/book/myortvaya-zona-3/", author.items.get(0).getUrl());
        assertEquals("Юрий Мир", author.items.get(0).getArtist());
        assertEquals("48840 s", "13 ч. 34 мин.", author.items.get(0).getTime());
        assertEquals(S + "/book/karniz-6/", author.items.get(1).getUrl());
        assertEquals("Евгений Гайчук", author.items.get(1).getArtist());
        assertEquals(API + "narrators/evgenij-gajchuk?audio=full", author.items.get(1).getUrlArtist());

        ProviderPage<BookPOJO> series = listing("series.json", API + "series/empirej");
        assertEquals("all in one page", 3, series.items.size());
        assertFalse(series.hasMore);
        assertEquals(S + "/book/chetvertoe-krylo/", series.items.get(0).getUrl());
        assertEquals("Ребекка Яррос", series.items.get(0).getAutor());
    }

    @Test
    public void htmlGuardIsAnErrorNotAnEmptyPage() throws IOException {
        try {
            SITE.parseListing(BookPageFixtures.load("knizhin/guard.html", S + "/"));
            fail("the Turnstile guard is not a listing");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("not a JSON page"));
        }
    }

    @Test
    public void genres() throws IOException {
        ArrayList<GenrePOJO> genres = SITE.parseGenres(json("genres.json", Knizhin.GENRES));
        assertEquals("141 less the one counted with no book", 140, genres.size());
        assertEquals("Фантастика", genres.get(0).getName());
        assertEquals(Knizhin.INDEX + "&genre=fantastika", genres.get(0).getUrl());
        assertEquals(20213, genres.get(0).getReting());
        assertEquals("Детективы, триллеры, боевики", genres.get(139).getName());
        assertEquals(1, genres.get(139).getReting());
        for (GenrePOJO genre : genres) {
            assertFalse("its books answer an empty listing", genre.getName().equals("Приключения, военные приключения"));
        }
        assertFalse(SITE.genres(json("genres.json", Knizhin.GENRES), Knizhin.GENRES, 1).hasMore);
    }

    // ---------------------------------------------------------------- search

    /** The books API ignores {@code q}: the search is the header's suggestions, one page without durations. */
    @Test
    public void searchIsTheSuggestions() throws IOException {
        String mag = SITE.searchUrl(SearchUrls.Category.BOOKS, "маг", 1);
        assertEquals(Knizhin.SUGGESTIONS + "%D0%BC%D0%B0%D0%B3&limit=24&page=1", mag);
        ProviderPage<BookPOJO> page = SITE.searchResults(Lis10book.suggested(json("search-mag.json", mag)), 1);
        assertEquals(ProviderPage.Status.OK, page.status);
        assertEquals(24, page.items.size());
        assertFalse("one page, whatever the total says", page.hasMore);
        BookPOJO first = page.items.get(0);
        assertEquals(S + "/book/mag-5/", first.getUrl());
        assertEquals("Маг", first.getName());
        assertEquals("Ника Ерш", first.getAutor());
        assertNull("no author link", first.getUrlAutor());
        assertNull(first.getPhoto());
        assertEquals("", first.getTime());
        assertEquals(S + "/book/mag-i-prist-protiv-wtf/", page.items.get(23).getUrl());
        assertFalse("no times on its cards", SITE.listsDuration(mag));
        assertTrue(SITE.listsDuration(Knizhin.INDEX));
        assertTrue("a search is not re-sorted", SITE.sorts(mag).isEmpty());
        assertEquals("nor bounded", mag, SITE.withDuration(mag, ListingFilters.Duration.UNDER_30M));

        String none = SITE.searchUrl(SearchUrls.Category.BOOKS, "zzzxqw", 1);
        ProviderPage<BookPOJO> empty = SITE.searchResults(Lis10book.suggested(json("search-none.json", none)), 1);
        assertEquals(ProviderPage.Status.EMPTY, empty.status);
        assertFalse(empty.hasMore);

        assertEquals(Knizhin.SUGGESTIONS + "zzzxqw&limit=24&page=2", SITE.searchUrl(SearchUrls.Category.BOOKS,
                "zzzxqw", 2));
        assertEquals("the lookup takes the suggestions as they are", none,
                SITE.otherSourceSearchUrl("zzzxqw", 1));
    }

    // ---------------------------------------------------------------- people

    @Test
    public void people() throws IOException {
        assertEquals(Knizhin.AUTHORS + "?page=2", SITE.pageUrl(Knizhin.AUTHORS, 2));
        ProviderPage<GenrePOJO> authors = SITE.people(json("people-authors-p1.json", Knizhin.AUTHORS),
                Knizhin.AUTHORS, 1, false);
        assertEquals(48, authors.items.size());
        assertTrue("628 pages", authors.hasMore);
        assertEquals("Антон Павлович Чехов", authors.items.get(0).getName());
        assertEquals(API + "authors/anton-chehov?audio=full", authors.items.get(0).getUrl());
        assertEquals(758, authors.items.get(0).getReting());

        ProviderPage<GenrePOJO> readers = SITE.people(json("people-readers-p1.json", Knizhin.READERS),
                Knizhin.READERS, 1, true);
        assertEquals(48, readers.items.size());
        assertTrue(readers.hasMore);
        assertEquals("Вячеслав Герасимов", readers.items.get(0).getName());
        assertEquals(API + "narrators/vyacheslav-gerasimov?audio=full", readers.items.get(0).getUrl());
        assertEquals(1713, readers.items.get(0).getReting());

        String king = SITE.searchUrl(SearchUrls.Category.AUTHORS, "Кинг", 1);
        assertEquals(Knizhin.AUTHORS + "?q=%D0%9A%D0%B8%D0%BD%D0%B3&page=1", king);
        ProviderPage<GenrePOJO> found = SITE.searchPeople(json("people-authors-search.json", king), 1, false);
        assertEquals(40, found.items.size());
        assertFalse(found.hasMore);
        assertEquals(AUTHOR, found.items.get(0).getUrl());
        String kozij = SITE.searchUrl(SearchUrls.Category.PERFORMERS, "Козий", 1);
        assertEquals(Knizhin.READERS + "?q=%D0%9A%D0%BE%D0%B7%D0%B8%D0%B9&page=1", kozij);
        ProviderPage<GenrePOJO> reader = SITE.searchPeople(json("people-readers-search.json", kozij), 1, true);
        assertEquals(1, reader.items.size());
        assertEquals(API + "narrators/nikolaj-kozij?audio=full", reader.items.get(0).getUrl());
    }

    // ---------------------------------------------------------------- sorts

    /** As BooksPresenter does it ({@code ListingFilters.sorted}): the site-wide sort, then narrowed to the listing. */
    private static String sorted(Sort sort, String listing) {
        String url = SITE.sortUrl(sort, listing);
        String genre = SITE.genreOf(listing);
        return url.equals(listing) || genre == null ? url : SITE.inGenre(url, genre);
    }

    @Test
    public void sorts() {
        List<Sort> menu = Arrays.asList(Sort.NEW, Sort.POPULAR, Sort.RATING, Sort.DURATION);
        for (String listing : new String[]{Knizhin.INDEX, GENRE, AUTHOR, READER}) {
            List<Sort.Entry> entries = SITE.sorts(listing);
            assertEquals(listing, menu.size(), entries.size());
            for (int i = 0; i < menu.size(); i++) {
                assertEquals(listing, menu.get(i), entries.get(i).sort);
            }
            for (Sort sort : menu) {
                String url = sorted(sort, listing);
                assertTrue(url, url.startsWith(S + "/api/"));
                assertEquals(url + " keeps its menu", 4, SITE.sorts(url).size());
            }
        }
        assertEquals(Knizhin.INDEX, SITE.sortUrl(Sort.NEW, Knizhin.INDEX + "&sort=rating"));
        assertEquals(Knizhin.INDEX + "&sort=popular", SITE.sortUrl(Sort.POPULAR, Knizhin.INDEX));
        assertEquals(Knizhin.INDEX + "&sort=rating", SITE.sortUrl(Sort.RATING, Knizhin.INDEX));
        assertEquals(Knizhin.INDEX + "&sort=duration", SITE.sortUrl(Sort.DURATION, Knizhin.INDEX));
        assertEquals(Knizhin.INDEX + "&sort=popular&page=2", SITE.pageUrl(Knizhin.INDEX + "&sort=popular", 2));
        assertEquals(GENRE + "&sort=duration", sorted(Sort.DURATION, GENRE));
        assertEquals(AUTHOR + "&sort=rating", sorted(Sort.RATING, AUTHOR));
        assertEquals(Knizhin.INDEX + "&dur=long", SITE.withDuration(Knizhin.INDEX,
                ListingFilters.Duration.of(600, ListingFilters.Duration.UNBOUNDED)));
        assertTrue("a series keeps its order", SITE.sorts(API + "series/empirej").isEmpty());
        assertTrue(SITE.sorts(Knizhin.AUTHORS).isEmpty());
        assertNull(SITE.sortUrl(Sort.COMMENTS, Knizhin.INDEX));
        assertNull(SITE.sortUrl(Sort.POPULAR_WEEK, Knizhin.INDEX));
    }

    // ---------------------------------------------------------------- book

    @Test
    public void description() throws IOException {
        DescriptionPOJO d = SITE.description(book("book.json", "ne-vyklyuchaj-svet"), BOOK);
        assertEquals("Не выключай свет", d.getTitle());
        assertEquals("a coverHash: lis10book.com's copy", COVERS + "851022.jpg", d.getPoster());
        assertEquals("Эндрю Райан", d.getAutor());
        assertEquals(API + "authors/endryu-rajan?audio=full", d.getAutorUrl());
        assertEquals("Scaners", d.getArtist());
        assertEquals(READER, d.getArtistUrl());
        assertEquals("Хоррор/Ужасы", d.getGenre());
        assertEquals(Knizhin.INDEX + "&genre=horror-uzhasy", d.getGenreUrl());
        assertEquals("", d.getSeries());
        assertEquals("11880 s", "3 ч. 18 мин.", d.getTime());
        assertEquals("uknigRating 0", "", d.getReiting());
        assertTrue(d.getDescription(), d.getDescription().startsWith("Уже двадцать девять лет инженер Томас Рид"));
        assertTrue(d.getDescription().contains("Пытаясь разобраться, Томас поднимает старые логи"));
        assertFalse(d.isOtherReader());

        BookPOJO card = SITE.book(book("book.json", "ne-vyklyuchaj-svet"), BOOK);
        assertEquals(BOOK, card.getUrl());
        assertEquals("Не выключай свет", card.getName());
        assertEquals("Хоррор/Ужасы", card.getGenre());

        DescriptionPOJO old = SITE.parseDescription(book("book-old.json", "golodnaya-smert"));
        assertEquals("Голодная смерть", old.getTitle());
        assertEquals("no coverHash: no cover found", "", old.getPoster());
        assertEquals("Клиффорд Саймак", old.getAutor());
        assertEquals("ЧеИзС", old.getArtist());
        assertEquals("Фэнтези", old.getGenre());
        assertEquals("1 ч. 14 мин.", old.getTime());
        assertEquals("", old.getReiting());

        DescriptionPOJO trial = SITE.parseDescription(book("book-litres.json", "chetvertoe-krylo"));
        assertEquals("Четвертое крыло", trial.getTitle());
        assertEquals("Любовное фэнтези", trial.getGenre());
        assertEquals("Эмпирей", trial.getSeries());
        assertEquals(API + "series/empirej", trial.getSeriesUrl());
        assertEquals(BookPOJO.formatRating(4.93f, "16"), trial.getReiting());
        assertEquals("22 ч. 20 мин.", trial.getTime());
    }

    @Test
    public void series() throws IOException {
        ArrayList<SeriesPOJO> empire = SITE.series(book("book-litres.json", "chetvertoe-krylo"),
                S + "/book/chetvertoe-krylo/");
        assertEquals(3, empire.size());
        assertEquals(S + "/book/chetvertoe-krylo/", empire.get(0).getUrl());
        assertEquals("Четвертое крыло", empire.get(0).getName());
        assertEquals("1", empire.get(0).getNumber());
        assertEquals("", empire.get(0).getPhoto());
        assertEquals(S + "/book/oniksovyj-shtorm/", empire.get(2).getUrl());
        assertEquals("3", empire.get(2).getNumber());

        ArrayList<SeriesPOJO> strelok = SITE.parseSeries(book("book-takedown.json", "vozdushnyj-strelok-1-boyarich"));
        assertEquals("the first of two series", 11, strelok.size());
        assertEquals(S + "/book/vozdushnyj-strelok-turist/", strelok.get(10).getUrl());
        assertEquals("8", strelok.get(10).getNumber());
        assertTrue(SITE.parseSeries(book("book.json", "ne-vyklyuchaj-svet")).isEmpty());
    }

    @Test
    public void recommendations() throws IOException {
        JsonPage page = Lis10book.withSimilar(book("book-litres.json", "chetvertoe-krylo"),
                json("similar.json", API + "books/chetvertoe-krylo/similar"));
        ArrayList<BookPOJO> similar = SITE.recommendations(page, S + "/book/chetvertoe-krylo/");
        assertEquals(12, similar.size());
        assertEquals(S + "/book/dolya-veroyatnosti/", similar.get(0).getUrl());
        assertEquals("Доля вероятности", similar.get(0).getName());
        assertEquals("Ребекка Яррос", similar.get(0).getAutor());
        assertNull(similar.get(0).getPhoto());
        assertEquals("Четвертое крыло", SITE.parseDescription(page).getTitle());
    }

    @Test
    public void comments() throws IOException {
        String slug = "mir-valdiry-rosgard-1-8-gospodstvo-klana-nespyashhih";
        ArrayList<ComentsPOJO> comments = Lis10book.parseComments(
                json("comments.json", API + "books/" + slug + "/comments?limit=50&offset=0"));
        assertEquals(12, comments.size());
        ComentsPOJO thanks = comments.get(1);
        assertEquals("Гость", thanks.getName());
        assertEquals("28.08.2026", thanks.getDate());
        assertEquals("Всё вернулось. Спасибо", thanks.getText());
        assertEquals("+1", thanks.getReting());
        assertEquals("4 up, 1 down", "+3", comments.get(2).getReting());
        assertEquals("Александр Л", comments.get(3).getName());
        assertTrue(Lis10book.parseComments(json("comments-none.json",
                API + "books/ne-vyklyuchaj-svet/comments?limit=50&offset=0")).isEmpty());
    }

    // ---------------------------------------------------------------- chapters

    @Test
    public void tracks() throws IOException {
        ArrayList<AudioPOJO> tracks = Lis10book.parseTracks(json("playlist.json", API + "p/ne-vyklyuchaj-svet"), BOOK);
        assertEquals(2, tracks.size());
        AudioPOJO first = tracks.get(0);
        assertEquals("ne-vykljuchajj-svet-01", first.getName());
        assertEquals("https://s2.knizhin.net/books/851022/5019720.mp3?h=T2EeY2iskMjWiWkit7YEIA&e=1795304291",
                first.getUrl());
        assertEquals("Не выключай свет", first.getBookName());
        assertEquals(5857, first.getTime());
        AudioPOJO last = tracks.get(1);
        assertEquals("https://s2.knizhin.net/books/851022/5019721.mp3?h=gDyNKrW7AlDD85WHSxAKHA&e=1795304291",
                last.getUrl());
        assertEquals(6027, last.getTime());

        assertTrue("the media host serves lis10book.com too", SITE.hosts().isEmpty());
        assertEquals(Collections.singleton("s2.knizhin.net"), SITE.refererHosts());
        assertEquals(S + "/", SITE.referer());
        assertNull("the media take the default Referer, the book page", SITE.mediaHeaders(BOOK));
    }

    @Test
    public void litresTrialAndTakedownHaveNoTracks() throws IOException {
        String trial = S + "/book/chetvertoe-krylo/";
        try {
            Lis10book.parseTracks(json("playlist-litres.json", API + "p/chetvertoe-krylo"), trial);
            fail("a LitRes trial is not the book");
        } catch (BookRemovedException expected) {
            assertEquals(trial, expected.getMessage());
        }
        String takedown = S + "/book/vozdushnyj-strelok-1-boyarich/";
        try {
            Lis10book.parseTracks(json("playlist-takedown.json", API + "p/vozdushnyj-strelok-1-boyarich"), takedown);
            fail("a taken-down book has no chapters");
        } catch (BookRemovedException expected) {
            assertEquals(takedown, expected.getMessage());
        }
        assertEquals("Воздушный стрелок: 1. Боярич",
                SITE.parseDescription(book("book-takedown.json", "vozdushnyj-strelok-1-boyarich")).getTitle());
    }
}
