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
import com.fanok.audiobooks.pojo.ComentsPOJO;
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
import java.util.Collections;
import java.util.List;

/** Pages saved from m1.audiomir.xyz on 2026-11-21 (see src/test/resources/fixtures/audiomir). */
public class AudiomirTest {

    private static final String S = Audiomir.SERVER;
    private static final String BOOK = S + "/55980-nikolay-skiba-eger-serdce-stai-kniga-5.html";

    private final Audiomir parser = new Audiomir();

    private static InputStream open(String name) {
        InputStream in = AudiomirTest.class.getResourceAsStream("/fixtures/audiomir/" + name);
        assertNotNull("missing fixture " + name, in);
        return in;
    }

    private static Document fixture(String name, String baseUri) throws IOException {
        try (InputStream in = open(name)) {
            return Jsoup.parse(in, "UTF-8", baseUri);
        }
    }

    private static String textFixture(String name) throws IOException {
        try (InputStream in = open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) != -1; ) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    // ---------------------------------------------------------------- URLs and sorts

    @Test
    public void pagesAreDlePages() {
        assertEquals(Audiomir.INDEX, parser.pageUrl(Audiomir.INDEX, 1));
        assertEquals(S + "/page/2278/", parser.pageUrl(Audiomir.INDEX, 2278));
        assertEquals(S + "/audiospektakl/page/2/", parser.pageUrl(S + "/audiospektakl/", 2));
        assertEquals(S + "/authors/page/3/", parser.pageUrl(Audiomir.AUTHORS, 3));
        assertEquals(Audiomir.COMMENTED, parser.pageUrl(Audiomir.COMMENTED, 1));
        String series = S + "/xfsearch/cikl/%D0%B5%D0%B3%D0%B5%D1%80%D1%8C/";
        assertEquals(series + "page/2/", parser.pageUrl(series, 2));
    }

    @Test
    public void onlyTheRealListsAreSorts() {
        List<Sort.Entry> menu = parser.sorts(Audiomir.INDEX);
        assertEquals(3, menu.size());
        assertEquals(Sort.NEW, menu.get(0).sort);
        assertEquals(Sort.COMMENTS, menu.get(1).sort);
        assertEquals(Sort.RANDOM, menu.get(2).sort);
        assertEquals(menu, parser.sorts(S + "/page/5/"));
        assertEquals(menu, parser.sorts(Audiomir.COMMENTED));
        assertEquals(menu, parser.sorts(Audiomir.RANDOM));
        assertEquals(Audiomir.INDEX, parser.sortUrl(Sort.NEW, Audiomir.RANDOM));
        assertEquals(S + "/commentsbooks.html", parser.sortUrl(Sort.COMMENTS, Audiomir.INDEX));
        assertEquals(S + "/randombooks.html", parser.sortUrl(Sort.RANDOM, Audiomir.INDEX));
        // the server sorts nothing: no POSTed DLE sort, and topbooks.html is the newest 50
        assertNull(parser.sortUrl(Sort.POPULAR, Audiomir.INDEX));
        assertNull(parser.sortUrl(Sort.RATING, Audiomir.INDEX));
        assertNull(parser.sortUrl(Sort.TOP_100, Audiomir.INDEX));
        assertEquals(Collections.<Sort.Entry>emptyList(), parser.sorts(S + "/audiospektakl/"));
        assertEquals(Collections.<Sort.Entry>emptyList(), parser.sorts(S + "/59763-ninel-nuar.html"));
        assertEquals(Collections.<Sort.Entry>emptyList(),
                parser.sorts(parser.searchUrl(SearchUrls.Category.BOOKS, "война", 1)));
    }

    @Test
    public void searchIsTheDleGetSearchOfBooksOnly() {
        assertEquals(S + "/index.php?do=search&subaction=search&story=%D0%B2%D0%BE%D0%B9%D0%BD%D0%B0"
                        + "&search_start=2&result_from=11&full_search=0",
                parser.searchUrl(SearchUrls.Category.BOOKS, "война", 2));
        assertNull(parser.searchUrl(SearchUrls.Category.AUTHORS, "война", 1));
        assertNull(parser.searchUrl(SearchUrls.Category.PERFORMERS, "война", 1));
    }

    @Test
    public void theSharedAudioHostGetsTheSitesReferer() {
        assertEquals(S + "/", parser.referer());
        assertEquals(Collections.singleton("aubks.org"), parser.refererHosts());
        assertTrue(parser.hosts().isEmpty());
        assertNull(parser.mediaHeaders(BOOK)); // the book page is the Referer: k20.aubks.org answers 206
    }

    // ---------------------------------------------------------------- listings

    @Test
    public void indexCardsCarryEveryField() throws IOException {
        ProviderPage<BookPOJO> page = parser.listing(fixture("index.html", S + "/"), Audiomir.INDEX, 1);
        assertEquals(12, page.items.size());
        assertTrue(page.hasMore);
        BookPOJO first = page.items.get(0);
        assertEquals(S + "/60034-annabel-li-akademiya-morimer-moy-opasnyy-vampir.html", first.getUrl());
        assertEquals("Академия Моримэр 2. Мой опасный вампир", first.getName());
        assertEquals(S + "/uploads/posts/2026-11/annabel-li-akademiya-morimer-moy-opasnyy-vampir.webp",
                first.getPhoto());
        assertEquals("Аннабель Ли", first.getAutor());
        assertEquals(S + "/xfsearch/avtor/%D0%B0%D0%BD%D0%BD%D0%B0%D0%B1%D0%B5%D0%BB%D1%8C%20%D0%BB%D0%B8/",
                first.getUrlAutor());
        assertEquals("Елена Лепихина", first.getArtist());
        assertEquals(S + "/xfsearch/chitaet/%D0%B5%D0%BB%D0%B5%D0%BD%D0%B0%20%D0%BB%D0%B5%D0%BF%D0%B8%D1%85"
                + "%D0%B8%D0%BD%D0%B0/", first.getUrlArtist());
        assertEquals("Любовное фэнтези, Юмористическое фэнтези", first.getGenre());
        assertEquals(S + "/lyubovnoye-fentezi/", first.getUrlGenre());
        assertEquals("Академия Моримэр", first.getSeries());
        assertEquals(S + "/xfsearch/cikl/%D0%B0%D0%BA%D0%B0%D0%B4%D0%B5%D0%BC%D0%B8%D1%8F%20%D0%BC%D0%BE%D1%80"
                + "%D0%B8%D0%BC%D1%8D%D1%80/", first.getUrlSeries());
        assertEquals("9 ч. 44 мин.", first.getTime()); // "Слушать онлайн (09:44:25)"
        assertEquals(0, first.getComents());
        assertEquals("0", first.getReting());
        assertTrue(first.getDesc().startsWith("Мариса давно смирилась"));
        assertEquals("Волшебный город. Тайны бродячей таверны", page.items.get(11).getName());
        assertTrue(parser.listsDuration(Audiomir.INDEX));
    }

    @Test
    public void laterAndLastPages() throws IOException {
        ProviderPage<BookPOJO> two = parser.listing(fixture("page2.html", S + "/page/2/"), S + "/page/2/", 2);
        assertEquals(12, two.items.size());
        assertTrue(two.hasMore);
        assertEquals("Марья Бессмертная 1", two.items.get(0).getName());
        ProviderPage<BookPOJO> last = parser.listing(fixture("last.html", S + "/page/2278/"), S + "/page/2278/", 2278);
        assertEquals(6, last.items.size());
        assertFalse(last.hasMore);
        assertEquals(S + "/2474-inoj-mir-chast-chetvertaja-nikita-sharipov.html", last.items.get(5).getUrl());
    }

    @Test
    public void aGenreListingNamesSeveralAuthors() throws IOException {
        String url = S + "/audiospektakl/";
        ProviderPage<BookPOJO> page = parser.listing(fixture("genre.html", url), url, 1);
        assertEquals(12, page.items.size());
        assertTrue(page.hasMore);
        BookPOJO first = page.items.get(0);
        assertEquals("Сыщик Липов 1. Разрубленная императрица", first.getName());
        assertEquals("Валерий Введенский, Елена Утехина", first.getAutor());
        assertTrue(first.getArtist().startsWith("Геннадий Смирнов, "));
        // an empty "Автор:" row: the title stays whole
        assertEquals("Аз, Буки, Веди... - Владимир Шаинский", page.items.get(10).getName());
        assertNull(page.items.get(10).getAutor());
    }

    @Test
    public void theFixedListsAreOnePageOfFifty() throws IOException {
        ProviderPage<BookPOJO> commented = parser.listing(fixture("commentsbooks.html", Audiomir.COMMENTED),
                Audiomir.COMMENTED, 1);
        assertEquals(50, commented.items.size());
        assertFalse(commented.hasMore);
        assertEquals(7, commented.items.get(0).getComents());
        assertEquals("Расследования графа Аверина 3. Демон из Пустоши. Колдун Российской империи",
                commented.items.get(0).getName());
        ProviderPage<BookPOJO> random = parser.listing(fixture("random.html", Audiomir.RANDOM), Audiomir.RANDOM, 1);
        assertEquals(50, random.items.size());
        assertFalse(random.hasMore);
        assertEquals("Мой учитель Лис", random.items.get(0).getName());
    }

    @Test
    public void thePopularListIsOnlyTheNewestBooks() throws IOException {
        String url = S + "/topbooks.html";
        ProviderPage<BookPOJO> top = parser.listing(fixture("topbooks.html", url), url, 1);
        ProviderPage<BookPOJO> index = parser.listing(fixture("index.html", S + "/"), Audiomir.INDEX, 1);
        assertEquals(50, top.items.size());
        for (int i = 0; i < index.items.size(); i++) {
            assertEquals(index.items.get(i).getUrl(), top.items.get(i).getUrl());
        }
    }

    @Test
    public void personPagesListAllTheirBooks() throws IOException {
        String author = S + "/59763-ninel-nuar.html";
        ProviderPage<BookPOJO> books = parser.listing(fixture("author.html", author), author, 1);
        assertEquals(12, books.items.size()); // "Книги: 12"
        assertFalse(books.hasMore);
        assertEquals("Нинель Нуар", books.items.get(0).getAutor());
        String reader = S + "/22534-valerij-tolkov.html";
        ProviderPage<BookPOJO> read = parser.listing(fixture("reader.html", reader), reader, 1);
        assertEquals(52, read.items.size()); // "Книги: 52"
        assertFalse(read.hasMore);
        assertTrue(read.items.get(0).getArtist().contains("Валерий Толков"));
    }

    // ---------------------------------------------------------------- search

    @Test
    public void searchHitsAndPagesPastTheCappedCount() throws IOException {
        ProviderPage<BookPOJO> few = parser.searchResults(fixture("search.html", S + "/index.php"), 1);
        assertEquals(3, few.items.size());
        assertFalse(few.hasMore);
        assertEquals("Академия Моримэр 2. Мой опасный вампир", few.items.get(0).getName());
        ProviderPage<BookPOJO> many = parser.searchResults(fixture("search_many.html", S + "/index.php"), 1);
        assertEquals(10, many.items.size());
        assertTrue(many.hasMore);
        assertEquals("Зверинец 5. Исповедь смертного греха", many.items.get(0).getName());
        ProviderPage<BookPOJO> two = parser.searchResults(fixture("search_many_p2.html", S + "/index.php"), 2);
        assertEquals(10, two.items.size());
        assertTrue("found 50 is a cap: page 6 still had 10 hits", two.hasMore);
        assertEquals(S + "/59646-andrey-gotlibovich-shoppert-livoniya-prodolzhenie.html", two.items.get(0).getUrl());
        ProviderPage<BookPOJO> none = parser.searchResults(fixture("search_empty.html", S + "/index.php"), 1);
        assertTrue(none.items.isEmpty());
        assertFalse(none.hasMore);
        assertEquals(ProviderPage.Status.EMPTY, none.status);
    }

    // ---------------------------------------------------------------- genres and people

    @Test
    public void genresLeaveOutPeopleAndTakenDownBooks() throws IOException {
        ProviderPage<GenrePOJO> genres = parser.genres(fixture("genres.html", Audiomir.GENRES), Audiomir.GENRES, 1);
        assertFalse(genres.hasMore);
        assertEquals(66, genres.items.size()); // 69 links less people and /blocked/
        GenrePOJO first = genres.items.get(0);
        assertEquals("Альтернативная история", first.getName());
        assertEquals(S + "/alternativnaja-istorija/", first.getUrl());
        assertEquals("1085 книг", first.getDescription());
        assertEquals("Аудиоспектакли", genres.items.get(1).getName());
        assertEquals("S.T.A.L.K.E.R.", genres.items.get(65).getName());
        for (GenrePOJO genre : genres.items) {
            assertFalse(genre.getUrl(), genre.getUrl().endsWith("/blocked/") || genre.getUrl().endsWith("/authors/")
                    || genre.getUrl().endsWith("/reads/"));
        }
    }

    @Test
    public void peopleIndexesArePagedPersonPages() throws IOException {
        ProviderPage<GenrePOJO> authors = parser.people(fixture("authors.html", Audiomir.AUTHORS),
                Audiomir.AUTHORS, 1, false);
        assertEquals(12, authors.items.size());
        assertTrue(authors.hasMore);
        assertEquals("Харитон Мамбурин", authors.items.get(0).getName());
        assertEquals(S + "/59769-hariton-mamburin.html", authors.items.get(0).getUrl());
        assertEquals("4", authors.items.get(0).getDescription());
        ProviderPage<GenrePOJO> last = parser.people(fixture("authors_last.html", S + "/authors/page/621/"),
                S + "/authors/page/621/", 621, false);
        assertEquals(7, last.items.size());
        assertFalse(last.hasMore);
        assertEquals("Роберт Шекли", last.items.get(6).getName());
        assertEquals("52", last.items.get(6).getDescription());
        ProviderPage<GenrePOJO> readers = parser.people(fixture("readers.html", Audiomir.READERS),
                Audiomir.READERS, 1, true);
        assertEquals(12, readers.items.size());
        assertTrue(readers.hasMore);
        assertEquals("Валерий Толков", readers.items.get(0).getName());
        assertEquals(S + "/22534-valerij-tolkov.html", readers.items.get(0).getUrl());
    }

    // ---------------------------------------------------------------- book page

    @Test
    public void bookPage() throws IOException {
        Document doc = fixture("book.html", BOOK);
        DescriptionPOJO d = parser.description(doc, BOOK);
        assertEquals("Егерь. Системный Зверолов. Книга 5. Егерь. Опасная зона", d.getTitle());
        assertEquals(S + "/uploads/posts/2026-02/nikolay-skiba-eger-serdce-stai-kniga-5.webp", d.getPoster());
        assertEquals("Николай Скиба", d.getAutor());
        assertEquals("", d.getAutorUrl()); // no person page yet
        assertEquals("Станислав Черсков", d.getArtist());
        assertEquals("", d.getArtistUrl());
        assertEquals("Фэнтези, Боевое фэнтези, Героическое фэнтези, Попаданцы", d.getGenre());
        assertEquals(S + "/fjentezi/", d.getGenreUrl());
        assertEquals("Егерь. Системный Зверолов", d.getSeries());
        assertTrue(d.getSeriesUrl().startsWith(S + "/xfsearch/cikl/%D0%B5%D0%B3%D0%B5%D1%80%D1%8C."));
        assertEquals("9 ч. 45 мин.", d.getTime()); // "Время: 09:45:02"
        assertEquals("1", d.getLike());
        assertEquals("0", d.getDisLike());
        String[] paragraphs = d.getDescription().split("\n");
        assertEquals(4, paragraphs.length);
        assertTrue(paragraphs[0].startsWith("Воздух наполнен ароматом хвои"));
        BookPOJO card = parser.book(doc, BOOK);
        assertEquals(BOOK, card.getUrl());
        assertEquals("Егерь. Системный Зверолов. Книга 5. Егерь. Опасная зона", card.getName());
    }

    @Test
    public void anOlderBookLinksItsPeoplesPages() throws IOException {
        String url = S + "/7454-prodelki-bogini-ili-nevestu-zakazyvali-franciska-vudvort.html";
        DescriptionPOJO d = parser.description(fixture("book_comments.html", url), url);
        assertEquals("Проделки богини, или невесту заказывали?", d.getTitle());
        assertEquals("Франциска Вудворт", d.getAutor());
        assertEquals(S + "/10975-franciska-vudvort.html", d.getAutorUrl());
        assertEquals("Анастасия Лазарева", d.getArtist());
        assertEquals(S + "/14407-anastasija-lazareva.html", d.getArtistUrl());
        assertEquals("18 ч. 4 мин.", d.getTime());
        assertEquals("", d.getSeries());
    }

    @Test
    public void seriesAndSimilarBooks() throws IOException {
        Document doc = fixture("book.html", BOOK);
        ArrayList<SeriesPOJO> series = parser.series(doc, BOOK);
        assertEquals(7, series.size());
        assertEquals(S + "/55976-nikolay-skiba-eger-sistemnyy-zverolov.html", series.get(0).getUrl());
        assertEquals("Егерь. Системный Зверолов. Книга 1 - Николай Скиба", series.get(0).getName());
        assertEquals(S + "/uploads/posts/2026-02/73211658.webp", series.get(0).getPhoto());
        assertEquals(BOOK, series.get(4).getUrl());
        assertEquals(S + "/55982-nikolay-skiba-eger-turnir-kniga-7.html", series.get(6).getUrl());
        ArrayList<BookPOJO> similar = parser.recommendations(doc, BOOK);
        assertEquals(4, similar.size());
        assertEquals(S + "/55979-nikolay-skiba-eger-stolichnyy-gambit-kniga-4.html", similar.get(0).getUrl());
        assertEquals(S + "/uploads/posts/2026-02/3e86150119_73211658.webp", similar.get(0).getPhoto());
        assertTrue(parser.series(fixture("book_comments.html", S + "/7454-x.html"), S + "/7454-x.html").isEmpty());
    }

    @Test
    public void commentsAreOldestFirst() throws IOException {
        ArrayList<ComentsPOJO> one = Audiomir.parseComments(fixture("book.html", BOOK));
        assertEquals(1, one.size());
        assertEquals("Гость игорь", one.get(0).getName());
        assertEquals("25 октября 2026 23:46", one.get(0).getDate());
        assertTrue(one.get(0).getText().startsWith("герой конченый лох."));
        ArrayList<ComentsPOJO> five = Audiomir.parseComments(fixture("book_comments.html", S + "/7454-x.html"));
        assertEquals(5, five.size());
        assertEquals("Гость Александр", five.get(0).getName());
        assertEquals("25 октября 2022 13:42", five.get(0).getDate());
        assertEquals("Гость Ирина", five.get(4).getName());
    }

    // ---------------------------------------------------------------- chapters

    @Test
    public void theKeyedPlayerjsFileIsThePlaylistOnTheAudioHost() throws IOException {
        String playlist = Audiomir.playlistUrl(fixture("book.html", BOOK), BOOK);
        assertEquals("https://k20.aubks.org/books/fantastika/nikolay-skiba-eger-serdce-stai-kniga-5/"
                + "nikolay-skiba-eger-serdce-stai-kniga-5.txt", playlist);
        String json = textFixture("playlist.txt");
        assertTrue("the site writes a BOM", json.charAt(0) == 0xFEFF);
        ArrayList<AudioPOJO> tracks = Audiomir.parsePlaylist(json, playlist, "Егерь 5");
        assertEquals(10, tracks.size());
        assertEquals("https://k20.aubks.org/books/fantastika/nikolay-skiba-eger-serdce-stai-kniga-5/0001.mp3",
                tracks.get(0).getUrl());
        assertEquals("01", tracks.get(0).getName());
        assertEquals("https://k20.aubks.org/books/fantastika/nikolay-skiba-eger-serdce-stai-kniga-5/0010.mp3",
                tracks.get(9).getUrl());
    }

    @Test
    public void aFileKeyedWithOtherKeysIsNoPlaylist() throws IOException {
        // the player swaps one of its five pieces for another: our keys leave that piece in and decode garbage
        String html = textFixture("book.html").replace(Audiomir.KEYS[0], "//WDlwUTRtTjd2QjJjSzE=");
        try {
            Audiomir.playlistUrl(Jsoup.parse(html, BOOK), BOOK);
            fail("garbage resolved as a URL");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().startsWith("Playerjs file decodes to no URL"));
        }
    }

    @Test
    public void anOlderBookHasAnInlineArchiveOrgList() throws IOException {
        String url = S + "/7454-prodelki-bogini-ili-nevestu-zakazyvali-franciska-vudvort.html";
        Document doc = fixture("book_comments.html", url);
        assertNull(Audiomir.playlistUrl(doc, url));
        ArrayList<AudioPOJO> tracks = parser.tracks(doc, url);
        assertEquals(31, tracks.size());
        assertEquals("https://archive.org/download/20260906_20260906_0844/01_Prodelki%20bogini%2C%20ili%20Nevestu"
                + "%20zakazyvali.mp3", tracks.get(0).getUrl());
        assertTrue(tracks.get(30).getUrl().endsWith("/31_Prodelki%20bogini%2C%20ili%20Nevestu%20zakazyvali.mp3"));
    }

    @Test
    public void aTakenDownBookIsRemovedNotEmpty() throws IOException {
        String url = S + "/45055-romany-romanovyh-1-my-vse-esche-vragi-nataliza-koff.html";
        Document doc = fixture("book_blocked.html", url);
        assertEquals("Романы Романовых 1. Мы все еще враги?", parser.description(doc, url).getTitle());
        try {
            parser.tracks(doc, url);
            fail("a taken-down book has no chapters");
        } catch (BookRemovedException expected) {
            assertEquals(url, expected.getMessage());
        }
    }

    @Test
    public void aPageWithoutPlayerOrNoticeIsAnError() throws IOException {
        try {
            parser.tracks(fixture("index.html", S + "/"), S + "/");
            fail("no player");
        } catch (BookRemovedException wrong) {
            fail("no takedown notice on the page");
        } catch (IllegalStateException expected) {
            // no Playerjs
        }
    }

    @Test
    public void withoutAuthorCutsOnlyTheExactSuffix() {
        assertEquals("A - B", Audiomir.withoutAuthor("A - B - C", "C"));
        assertEquals("A - B", Audiomir.withoutAuthor("A - B", "C"));
        assertEquals("A - B", Audiomir.withoutAuthor("A - B", ""));
    }
}
