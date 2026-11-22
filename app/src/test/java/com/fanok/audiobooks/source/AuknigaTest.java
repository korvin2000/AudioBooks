package com.fanok.audiobooks.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.fanok.audiobooks.BookPageFixtures;
import com.fanok.audiobooks.BookRemovedException;
import com.fanok.audiobooks.pojo.AudioPOJO;
import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.pojo.DescriptionPOJO;
import com.fanok.audiobooks.pojo.GenrePOJO;
import com.fanok.audiobooks.search.ProviderPage;
import com.fanok.audiobooks.search.SearchUrls;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** aukniga.com pages saved on 2026-11-21 (src/test/resources/fixtures/aukniga), the engine of {@link Aubook} too. */
public class AuknigaTest {

    private static final Aukniga SITE = new Aukniga();
    private static final String S = Aukniga.SERVER;
    private static final String GENRE = S + "/ct/fantastika";
    private static final String AUTHOR = S + "/a/arkadiy-i-boris-strugackie";
    private static final String READER = S + "/va/levashev-vladimir";
    private static final String BOOK = S + "/b/poisk-prednaznacheniya-ili-dvadcat-sedmaya-teorema-etiki-arkadiy-i-boris"
            + "-strugackie";
    private static final String TRIAL = S + "/b/leon-marina-dyachenko";
    private static final String ARCHIVE = "https://archive.org/download/16._20230213/";
    private static final String CHAPTER = ".%20%D0%9F%D0%BE%D0%B8%D1%81%D0%BA%20%D0%BF%D1%80%D0%B5%D0%B4%D0%BD%D0%B0"
            + "%D0%B7%D0%BD%D0%B0%D1%87%D0%B5%D0%BD%D0%B8%D1%8F.mp3";
    private static final String METRO = "%D0%BC%D0%B5%D1%82%D1%80%D0%BE";

    private static Document page(String name, String url) throws IOException {
        return BookPageFixtures.load("aukniga/" + name, url);
    }

    private static ProviderPage<BookPOJO> listing(String name, String url) throws IOException {
        return SITE.listing(page(name, url), url, 1);
    }

    private static BookPOJO named(List<BookPOJO> books, String name) {
        for (BookPOJO book : books) {
            if (name.equals(book.getName())) {
                return book;
            }
        }
        throw new AssertionError("no card " + name);
    }

    // ---------------------------------------------------------------- listings

    @Test
    public void catalogCardsAndPages() throws IOException {
        ProviderPage<BookPOJO> page = listing("listing-p1.html", Aukniga.INDEX);
        assertEquals(ProviderPage.Status.OK, page.status);
        assertEquals(16, page.items.size());
        assertTrue(page.hasMore);
        BookPOJO first = page.items.get(0);
        assertEquals("Мир Трибунала. Тата и медведь", first.getName());
        assertEquals(S + "/b/mir-tribunala-tata-i-medved-mash-diana", first.getUrl());
        assertEquals(S + "/uploads/books/images/f/4/mir-tribunala-tata-i-medved-mash-diana.jpg", first.getPhoto());
        assertEquals("Маш Диана", first.getAutor());
        assertEquals(S + "/a/mash-diana", first.getUrlAutor());
        assertTrue(first.getDesc(), first.getDesc().startsWith("Что делает среднестатистическая ведьмочка"));
        assertEquals("Леон", page.items.get(15).getName());

        ProviderPage<BookPOJO> second = listing("listing-p2.html", SITE.pageUrl(Aukniga.INDEX, 2));
        assertEquals(16, second.items.size());
        assertTrue(second.hasMore);
        assertEquals(S + "/b/sidus-vida-svoego-pervyy-daniyar-sugralinov", second.items.get(0).getUrl());

        ProviderPage<BookPOJO> last = listing("listing-last.html", SITE.pageUrl(Aukniga.INDEX, 1922));
        assertEquals(16, last.items.size());
        assertFalse(last.hasMore);
        assertEquals("Обрученные холодом", last.items.get(15).getName());
        BookPOJO two = named(last.items, "Отбор с осложнениями");
        assertEquals("Ника Ёрш, Ольга Ярошинская", two.getAutor());
        assertEquals(S + "/a/nika-ersh", two.getUrlAutor());
    }

    @Test
    public void pagesAndSorts() {
        assertEquals(Aukniga.INDEX, SITE.pageUrl(Aukniga.INDEX, 1));
        assertEquals(S + "/bs/p/2", SITE.pageUrl(Aukniga.INDEX, 2));
        assertEquals(GENRE + "/p/2", SITE.pageUrl(GENRE, 2));
        assertEquals(AUTHOR + "/p/2", SITE.pageUrl(AUTHOR, 2));
        assertEquals(READER + "/p/8", SITE.pageUrl(READER, 8));
        assertEquals(S + "/as/p/2", SITE.pageUrl(Aukniga.AUTHORS, 2));
        assertEquals(S + "/p/2", SITE.pageUrl(Aukniga.POPULAR, 2));

        assertEquals(Arrays.asList(Sort.NEW, Sort.POPULAR), sorts(Aukniga.INDEX));
        assertEquals(Arrays.asList(Sort.NEW, Sort.POPULAR), sorts(Aukniga.POPULAR));
        assertEquals(Aukniga.INDEX, SITE.sortUrl(Sort.NEW, Aukniga.POPULAR));
        assertEquals(S + "/", SITE.sortUrl(Sort.POPULAR, Aukniga.INDEX));
        assertNull(SITE.sortUrl(Sort.RATING, Aukniga.INDEX));
        assertTrue(sorts(GENRE).isEmpty());
        assertTrue(sorts(AUTHOR).isEmpty());
        assertTrue(sorts(READER).isEmpty());
        assertFalse(SITE.listsDuration(Aukniga.INDEX));
    }

    private static List<Sort> sorts(String url) {
        List<Sort> out = new ArrayList<>();
        for (Sort.Entry entry : SITE.sorts(url)) {
            out.add(entry.sort);
        }
        return out;
    }

    @Test
    public void popularIsOnePage() throws IOException {
        ProviderPage<BookPOJO> page = listing("popular.html", Aukniga.POPULAR);
        assertEquals(32, page.items.size());
        assertFalse(page.hasMore);
        assertEquals("Еретики Дюны", page.items.get(0).getName());
        assertEquals(S + "/a/frenk-herbert", page.items.get(0).getUrlAutor());
        assertEquals("Черный кофе", page.items.get(31).getName());
    }

    @Test
    public void genreAuthorAndReaderListings() throws IOException {
        ProviderPage<BookPOJO> genre = listing("genre.html", GENRE);
        assertEquals(16, genre.items.size());
        assertTrue(genre.hasMore);
        assertEquals("Проснувшийся", genre.items.get(0).getName());
        assertEquals(S + "/b/prosnuvshiysya-anton-demchenko", genre.items.get(0).getUrl());

        ProviderPage<BookPOJO> author = listing("author.html", AUTHOR);
        assertEquals(16, author.items.size());
        assertTrue(author.hasMore);
        assertEquals(BOOK, author.items.get(15).getUrl());
        assertEquals("Аркадий и Борис Стругацкие", author.items.get(15).getAutor());

        ProviderPage<BookPOJO> reader = listing("reader.html", READER);
        assertEquals(16, reader.items.size());
        assertTrue(reader.hasMore);
        assertEquals("Sapiens. Краткая история человечества", reader.items.get(0).getName());
    }

    // ---------------------------------------------------------------- sections

    @Test
    public void genres() throws IOException {
        List<GenrePOJO> genres = SITE.genres(page("genres.html", Aukniga.GENRES), Aukniga.GENRES, 1).items;
        assertEquals(57, genres.size());
        assertEquals("LitRPG", genres.get(0).getName());
        assertEquals(S + "/ct/litrpg", genres.get(0).getUrl());
        assertEquals("Фантастика", genres.get(50).getName());
        assertEquals(GENRE, genres.get(50).getUrl());
        assertEquals("Юмор", genres.get(56).getName());
    }

    @Test
    public void authorsIndex() throws IOException {
        ProviderPage<GenrePOJO> page = SITE.people(page("authors.html", Aukniga.AUTHORS), Aukniga.AUTHORS, 1, false);
        assertEquals(50, page.items.size());
        assertTrue(page.hasMore);
        assertEquals("\"Я не знаю...\"", page.items.get(0).getName());
        assertEquals(S + "/a/ya-ne-znayu", page.items.get(0).getUrl());
        assertEquals("Bad_apple_me", page.items.get(49).getName());
        assertNull(Aukniga.READERS);
    }

    // ---------------------------------------------------------------- search

    @Test
    public void searchBooksAndAuthors() throws IOException {
        String url = SITE.searchUrl(SearchUrls.Category.BOOKS, "метро", 1);
        assertEquals(S + "/find?q=" + METRO + "&page=1", url);
        assertEquals(url, SITE.searchUrl(SearchUrls.Category.AUTHORS, "метро", 1));
        assertNull(SITE.searchUrl(SearchUrls.Category.PERFORMERS, "метро", 1));

        Document doc = page("search.html", S + "/find?q=" + METRO);
        ProviderPage<BookPOJO> books = SITE.searchResults(doc, 1);
        assertEquals(24, books.items.size());
        assertFalse(books.hasMore);
        assertEquals("Метро 1. Метро 2033", books.items.get(0).getName());
        assertEquals("Дмитрий Глуховский", books.items.get(0).getAutor());
        BookPOJO two = named(books.items, "Метро 2033: Слепящая пустота");
        assertEquals("Андрей Чернецов, Валентин Леженда", two.getAutor());
        assertEquals(S + "/a/andrey-chernecov", two.getUrlAutor());

        ProviderPage<GenrePOJO> authors = SITE.searchPeople(doc, 1, false);
        assertEquals(3, authors.items.size());
        assertFalse(authors.hasMore);
        assertEquals("Метро 2034", authors.items.get(0).getName());
        assertEquals(S + "/a/metro-2034", authors.items.get(0).getUrl());
        assertEquals(ProviderPage.Status.EMPTY, SITE.searchPeople(doc, 1, true).status);

        Document none = page("search-none.html", S + "/find?q=zzqxjvw");
        assertEquals(ProviderPage.Status.EMPTY, SITE.searchResults(none, 1).status);
        assertEquals(ProviderPage.Status.EMPTY, SITE.searchPeople(none, 1, false).status);
    }

    // ---------------------------------------------------------------- book page

    @Test
    public void bookPage() throws IOException {
        Document doc = page("book.html", BOOK);
        DescriptionPOJO d = SITE.description(doc, BOOK);
        assertEquals("Поиск предназначения, или двадцать седьмая теорема этики", d.getTitle());
        assertEquals(S + "/uploads/books/images/c/e/poisk-prednaznacheniya-ili-dvadcat-sedmaya-teorema-etiki-arkadiy-i"
                + "-boris-strugackie.jpg", d.getPoster());
        assertEquals("Аркадий и Борис Стругацкие", d.getAutor());
        assertEquals(AUTHOR, d.getAutorUrl());
        assertEquals("Левашев Владимир", d.getArtist());
        assertEquals(READER, d.getArtistUrl());
        assertEquals("Фантастика", d.getGenre());
        assertEquals(GENRE, d.getGenreUrl());
        assertEquals("17 ч. 4 мин.", d.getTime());
        String[] about = d.getDescription().split("\n");
        assertEquals(2, about.length);
        assertTrue(about[0], about[0].startsWith("В аудиокнигу включён роман Бориса Стругацкого"));
        assertTrue(about[1], about[1].startsWith("Главный герой, ленинградский инженер-программист"));

        BookPOJO card = SITE.book(doc, BOOK);
        assertEquals(BOOK, card.getUrl());
        assertEquals("Левашев Владимир", card.getArtist());

        ArrayList<BookPOJO> more = SITE.recommendations(doc, BOOK);
        assertEquals(22, more.size());
        assertEquals("Повесть о дружбе и недружбе", more.get(0).getName());
        assertEquals(S + "/uploads/books/images/2/d/povest-o-druzhbe-i-nedruzhbe-arkadiy-i-boris-strugackie.jpg",
                more.get(0).getPhoto());
        assertEquals("Фринштад 2. Меня любят в Магической академии", more.get(21).getName());
    }

    @Test
    public void seriesRowAndSeriesListing() throws IOException {
        String book = S + "/b/arhimag-5-voyna-koldunov-kniga-1-vtorzhenie-aleksandr-rudazov";
        DescriptionPOJO d = SITE.description(page("book-series.html", book), book);
        assertEquals("Архимаг", d.getSeries());
        assertEquals(S + "/s/arhimag", d.getSeriesUrl());
        assertEquals("Александр Рудазов", d.getAutor());
        assertEquals(S + "/va/aleksandr-sluckiy", d.getArtistUrl());
        assertEquals("", SITE.description(page("book.html", BOOK), BOOK).getSeries());

        ProviderPage<BookPOJO> series = listing("series.html", S + "/s/arhimag");
        assertEquals(14, series.items.size());
        assertFalse(series.hasMore);
        assertEquals("Архимаг 6. Дети Судного Часа", series.items.get(0).getName());
        assertEquals("Александр Рудазов", series.items.get(0).getAutor());

        String big = S + "/s/warhammer-40000";
        ProviderPage<BookPOJO> paged = listing("series-paged.html", big);
        assertEquals(16, paged.items.size());
        assertTrue(paged.hasMore);
        assertEquals(S + "/b/warhammer-40000-infernalnyy-rekviem-peter-fehervari", paged.items.get(0).getUrl());
        assertEquals(big + "/p/2", SITE.pageUrl(big, 2));
        assertTrue(sorts(big).isEmpty());
    }

    @Test
    public void chaptersAreArchiveOrgFiles() throws IOException {
        ArrayList<AudioPOJO> tracks = SITE.tracks(page("book.html", BOOK), BOOK);
        assertEquals(36, tracks.size());
        assertEquals("01", tracks.get(0).getName());
        assertEquals(ARCHIVE + "01" + CHAPTER, tracks.get(0).getUrl());
        assertEquals("Поиск предназначения, или двадцать седьмая теорема этики", tracks.get(0).getBookName());
        assertEquals("36", tracks.get(35).getName());
        assertEquals(ARCHIVE + "36" + CHAPTER, tracks.get(35).getUrl());
        assertTrue(SITE.hosts().isEmpty());
    }

    @Test
    public void litresTrialIsARemovedBook() throws IOException {
        Document doc = page("book-trial.html", TRIAL);
        assertEquals("Леон", SITE.description(doc, TRIAL).getTitle());
        assertEquals("Станислав Воронецкий", SITE.description(doc, TRIAL).getArtist());
        assertThrows(BookRemovedException.class, () -> SITE.tracks(doc, TRIAL));
    }

    @Test
    public void pageWithoutPlayerIsNoBook() throws IOException {
        assertThrows(IllegalStateException.class, () -> SITE.tracks(page("listing-p1.html", Aukniga.INDEX), BOOK));
        Document unknownKey = Jsoup.parse("<script>var auPlayerSettings = {'data': '#2e//WFh4eA==yJpZCI6InBsYXllciJ9'};"
                + "</script>", BOOK);
        assertThrows(IllegalStateException.class, () -> SITE.tracks(unknownKey, BOOK));
    }
}
