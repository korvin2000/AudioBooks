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
import com.fanok.audiobooks.search.ProviderPage;
import com.fanok.audiobooks.search.SearchUrls;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;

/** audioknigi.top pages saved live on 2026-11-21 (src/test/resources/fixtures/audioknigi-top). */
public class AudioknigiTopTest {

    private static final AudioknigiTop P = new AudioknigiTop();
    private static final String S = AudioknigiTop.SERVER;
    private static final String BOOK = S + "/master-run-kniga-12";
    private static final String COMMENTED = S + "/mehanika-nevozmoghnogo-2-ubiyca-na-krayu-imperii-yan-badevskiy";
    private static final String GENRE = S + "/zhanr/fantastika/";
    private static final String SEARCH = S + "/index.php";
    private static final String MAIN = "&set_new_sort=dle_sort_main&set_direction_sort=dle_direction_main";
    private static final String XFSEARCH = "&set_new_sort=dle_sort_xfsearch&set_direction_sort=dle_direction_xfsearch";

    private static Document fixture(String name, String baseUri) throws IOException {
        try (InputStream in = AudioknigiTopTest.class.getResourceAsStream("/fixtures/audioknigi-top/" + name)) {
            assertNotNull("missing fixture " + name, in);
            return Jsoup.parse(in, "UTF-8", baseUri);
        }
    }

    private static String text(String name) throws Exception {
        URL url = AudioknigiTopTest.class.getResource("/fixtures/audioknigi-top/" + name);
        assertNotNull("missing fixture " + name, url);
        return new String(Files.readAllBytes(Paths.get(url.toURI())), StandardCharsets.UTF_8);
    }

    private static ProviderPage<BookPOJO> listing(String name, String url) throws IOException {
        ProviderPage<BookPOJO> page = P.listing(fixture(name, url), url, 1);
        assertEquals(name, ProviderPage.Status.OK, page.status);
        for (BookPOJO book : page.items) {
            assertFalse(name, book.getName().isEmpty());
            assertTrue(book.getUrl(), book.getUrl().startsWith(S + "/") && !book.getUrl().endsWith("/"));
            assertTrue(book.getUrl() + " cover", book.getPhoto().startsWith(S + "/uploads/posts/"));
        }
        return page;
    }

    private static String first(ProviderPage<BookPOJO> page) {
        return page.items.get(0).getUrl().substring(S.length() + 1);
    }

    private static String last(ProviderPage<BookPOJO> page) {
        return page.items.get(page.items.size() - 1).getUrl().substring(S.length() + 1);
    }

    // ---------------------------------------------------------------- listings

    @Test
    public void indexPagesHaveFullCards() throws IOException {
        ProviderPage<BookPOJO> page = listing("listing-index-p1.html", AudioknigiTop.INDEX);
        assertEquals("the carousels' cards are left out", 18, page.items.size());
        assertTrue(page.hasMore);
        BookPOJO book = page.items.get(0);
        assertEquals("Я тебя отпускаю", book.getName());
        assertEquals(S + "/ya-tebya-otpuskayu", book.getUrl());
        assertEquals(S + "/uploads/posts/2026-10/0a679550_42989188.webp", book.getPhoto());
        assertEquals("Мария Метлицкая", book.getAutor());
        assertEquals(S + "/author/marija-metlickaja/", book.getUrlAutor());
        assertEquals("Марина Багинская", book.getArtist());
        assertEquals(S + "/read/marina-baginskaja/", book.getUrlArtist());
        assertEquals("02:04:42 on the card", "2 ч. 4 мин.", book.getTime());
        assertEquals("no votes", "0", book.getReting());
        assertNull(book.getSeries());
        assertNull("newer cards name no genre", book.getGenre());

        BookPOJO inSeries = page.items.get(1);
        assertEquals("Мёртвоприношение", inSeries.getName());
        assertEquals("Гривы", inSeries.getSeries());
        assertEquals(S + "/cikl/grivy/", inSeries.getUrlSeries());

        BookPOJO twoAuthors = page.items.get(14);
        assertEquals("Мастер Рун. Книга 12", twoAuthors.getName());
        assertEquals("one link, a name a line", "Артём Сластин, ПолуЁж", twoAuthors.getAutor());
        assertEquals(S + "/author/artem-slastin-poluezh/", twoAuthors.getUrlAutor());
        assertEquals("Саня БтрЪ", twoAuthors.getArtist());
        assertEquals("8 ч. 16 мин.", twoAuthors.getTime());
        assertEquals("Мастер Рун. Книга 1", twoAuthors.getSeries());
        assertEquals("u-kromki-morya-uzkiy-lepestok", last(page));

        ProviderPage<BookPOJO> second = listing("listing-index-p2.html", S + "/page/2/");
        assertEquals(18, second.items.size());
        assertTrue(second.hasMore);
        assertEquals("temnyy-ohotnik-14", first(second));
        assertEquals("Ольга Морган, Татьяна Полуянова", second.items.get(findName(second, "Знахарка. Волчья Луна"))
                .getAutor());

        ProviderPage<BookPOJO> end = listing("listing-index-last.html", S + "/page/1846/");
        assertEquals(5, end.items.size());
        assertFalse(end.hasMore);
        assertEquals("Три королевства 4. Светлая для наследника - Нина Новолодская", end.items.get(0).getName());
        assertEquals("iskopaemoe-yuriy-tretyyakov", last(end));
    }

    private static int findName(ProviderPage<BookPOJO> page, String name) {
        for (int i = 0; i < page.items.size(); i++) {
            if (page.items.get(i).getName().equals(name)) {
                return i;
            }
        }
        fail(name);
        return -1;
    }

    @Test
    public void sortedIndex() throws IOException {
        ProviderPage<BookPOJO> rating = listing("listing-rating-p1.html", S + "/");
        assertEquals(18, rating.items.size());
        assertTrue(rating.hasMore);
        assertEquals("drugaya-storona-2-provodnik-chasty-1-rodion-korablev", first(rating));
        assertEquals("8.73", rating.items.get(0).getReting());
        ProviderPage<BookPOJO> rating2 = listing("listing-rating-p2.html", S + "/page/2/");
        assertEquals("amur-batyushka-nikolay-zadornov", first(rating2));
        assertTrue(rating2.hasMore);
        assertEquals("ne-proschayusy", first(listing("listing-views-p1.html", S + "/")));
        assertEquals("mehanika-nevozmoghnogo-2-ubiyca-na-krayu-imperii-yan-badevskiy",
                first(listing("listing-comments-p1.html", S + "/")));
        assertEquals("#Бояръ-Аниме. Романов. Том 1", listing("listing-title-p1.html", S + "/").items.get(0).getName());
    }

    @Test
    public void genrePagesAndTheirSorts() throws IOException {
        ProviderPage<BookPOJO> page = listing("genre-p1.html", GENRE);
        assertEquals(18, page.items.size());
        assertTrue(page.hasMore);
        BookPOJO book = page.items.get(0);
        assertEquals("Бард - Сергей Карелин, Евгений Лисицин", book.getName());
        assertEquals("two links", "Сергей Карелин, Евгений Лисицин", book.getAutor());
        assertEquals(S + "/author/sergej-karelin/", book.getUrlAutor());
        assertEquals("Олег Шубин", book.getArtist());
        assertEquals("Бард (Евгений Лисицин)", book.getSeries());
        assertEquals("sozdayuschiy-buduschee-boris-romanovskiy", last(page));
        assertEquals("the date sort is the plain listing", first(page), first(listing("genre-date-p1.html", GENRE)));

        assertEquals("drugaya-storona-2-provodnik-chasty-1-rodion-korablev",
                first(listing("genre-rating-p1.html", GENRE)));
        assertEquals("dvoynik", first(listing("genre-views-p1.html", GENRE)));
        assertEquals("mehanoid-vlad-tehnomak", first(listing("genre-comments-p1.html", GENRE)));
        ProviderPage<BookPOJO> title = listing("genre-title-p1.html", GENRE);
        assertEquals("ampquot18_ampquot-doktor-kniga-1", first(title));
        assertEquals("the site escapes it twice", "\"18+\" Доктор. Книга 1", title.items.get(0).getName());
    }

    @Test
    public void personAndSeriesListings() throws IOException {
        ProviderPage<BookPOJO> author = listing("author.html", S + "/author/stiven-king/");
        assertEquals(14, author.items.size());
        assertFalse(author.hasMore);
        assertEquals("pyatyy-shag", first(author));
        assertEquals("dolgiy-dghont-stiven-king", last(author));
        BookPOJO older = author.items.get(3);
        assertEquals("11-22-63, или Двадцать второе ноября шестьдесят третьего года - Стивен Кинг", older.getName());
        assertEquals("an older card has genres instead of a duration", "", older.getTime());
        assertEquals("Фантастика, Зарубежная литература", older.getGenre());
        assertEquals(GENRE, older.getUrlGenre());
        assertEquals("Игорь Князев", older.getArtist());
        assertEquals(S + "/read/igor-knjazev/", older.getUrlArtist());

        ProviderPage<BookPOJO> reader = listing("reader.html", S + "/read/aleksandr-bashkov/");
        assertEquals(18, reader.items.size());
        assertTrue(reader.hasMore);
        assertEquals("orden-krakena-4-oleg-sapfir_-dmitriy-angor", first(reader));
        assertEquals("Александр Башков", reader.items.get(0).getArtist());

        ProviderPage<BookPOJO> series = listing("series.html", S + "/cikl/anderdog/");
        assertEquals(7, series.items.size());
        assertFalse(series.hasMore);
        assertEquals("Андердог 4. Иномирье - Алексей Осадчук", series.items.get(0).getName());
        assertEquals("anderdog-8-renegat-tom-1-aleksey-osadchuk", last(series));
    }

    @Test
    public void sortUrlsStayOnTheListing() {
        assertEquals(Dle.MENU, P.sorts(AudioknigiTop.INDEX));
        assertEquals(Dle.MENU, P.sorts(S + "/page/3/"));
        assertEquals(Dle.MENU, P.sorts(GENRE));
        assertEquals(Dle.MENU, P.sorts(GENRE + "page/2/"));
        assertTrue(P.sorts(S + "/author/stiven-king/").isEmpty());
        assertTrue(P.sorts(S + "/read/aleksandr-bashkov/").isEmpty());
        assertTrue(P.sorts(S + "/cikl/anderdog/").isEmpty());
        assertTrue(P.sorts(P.searchUrl(SearchUrls.Category.BOOKS, "архимага", 1)).isEmpty());
        assertTrue(P.sorts(BOOK).isEmpty());

        assertEquals(AudioknigiTop.INDEX, P.sortUrl(Sort.NEW, AudioknigiTop.INDEX));
        assertEquals(S + "/?dlenewssortby=rating&dledirection=desc" + MAIN, P.sortUrl(Sort.RATING, GENRE));
        assertEquals(S + "/?dlenewssortby=news_read&dledirection=desc" + MAIN, P.sortUrl(Sort.POPULAR, GENRE));
        assertEquals(S + "/?dlenewssortby=comm_num&dledirection=desc" + MAIN, P.sortUrl(Sort.COMMENTS, GENRE));
        assertEquals(S + "/?dlenewssortby=title&dledirection=asc" + MAIN, P.sortUrl(Sort.TITLE, GENRE));
        assertNull("no top list", P.sortUrl(Sort.TOP_100, AudioknigiTop.INDEX));
        assertEquals(S + "/page/2/?dlenewssortby=rating&dledirection=desc" + MAIN,
                P.pageUrl(P.sortUrl(Sort.RATING, AudioknigiTop.INDEX), 2));

        String rating = P.sortUrl(Sort.RATING, GENRE);
        assertEquals(GENRE, P.genreOf(GENRE));
        assertEquals(GENRE, P.genreOf(GENRE + "page/4/"));
        assertNull(P.genreOf(AudioknigiTop.INDEX));
        assertNull(P.genreOf(S + "/author/stiven-king/"));
        assertEquals(GENRE + "?dlenewssortby=rating&dledirection=desc" + XFSEARCH, P.inGenre(rating, GENRE));
        assertEquals(GENRE, P.genreOf(P.inGenre(rating, GENRE)));
        assertEquals(GENRE, P.inGenre(P.sortUrl(Sort.NEW, GENRE), GENRE));
        assertEquals(GENRE + "page/2/?dlenewssortby=rating&dledirection=desc" + XFSEARCH,
                P.pageUrl(P.inGenre(rating, GENRE), 2));
        assertEquals(S + "/page/2/", P.pageUrl(AudioknigiTop.INDEX, 2));
    }

    /** The indexes answer 404 with a slash after them (live, 2026-11-21): they are fetched as they are. */
    @Test
    public void indexesKeepTheirUrl() {
        for (String index : new String[] {AudioknigiTop.GENRES, AudioknigiTop.AUTHORS, AudioknigiTop.READERS}) {
            assertEquals(index, P.pageUrl(index, 1));
            assertEquals(index, P.pageUrl(index, 2));
        }
        assertEquals(S + "/author/stiven-king/", P.pageUrl(S + "/author/stiven-king", 1));
        assertEquals(S + "/author/stiven-king/page/2/", P.pageUrl(S + "/author/stiven-king/", 2));
    }

    /** A person with no book indexed (Мария Метлицкая, 21 by the index) is a 404: an empty listing, not an error. */
    @Test
    public void emptyPersonPagesAre404() {
        assertTrue(AudioknigiTop.emptyOn404(S + "/author/marija-metlickaja/"));
        assertTrue(AudioknigiTop.emptyOn404(S + "/read/marina-baginskaja/"));
        assertTrue(AudioknigiTop.emptyOn404(S + "/cikl/grivy/page/2/"));
        assertTrue(AudioknigiTop.emptyOn404(AudioknigiPro.fetchUrl(S + "/author/marija-metlickaja/")));
        assertFalse(AudioknigiTop.emptyOn404(GENRE));
        assertFalse(AudioknigiTop.emptyOn404(AudioknigiTop.AUTHORS));
        assertFalse(AudioknigiTop.emptyOn404(BOOK));
        assertFalse(AudioknigiTop.emptyOn404(AudioknigiTop.INDEX));
        ProviderPage<BookPOJO> empty = P.listing(Document.createShell(S + "/author/marija-metlickaja/"),
                S + "/author/marija-metlickaja/", 1);
        assertTrue(empty.items.isEmpty());
        assertFalse(empty.hasMore);
    }

    /** The session keeps the last sort of a key, so {@link AudioknigiTop#fetch} POSTs every listing with its own. */
    @Test
    public void everyListingGoesOutWithItsSort() {
        assertEquals(S + "/?dlenewssortby=date&dledirection=desc" + MAIN, AudioknigiPro.fetchUrl(AudioknigiTop.INDEX));
        assertEquals(S + "/author/stiven-king/?dlenewssortby=date&dledirection=desc" + XFSEARCH,
                AudioknigiPro.fetchUrl(S + "/author/stiven-king/"));
        assertEquals(S + "/cikl/anderdog/?dlenewssortby=date&dledirection=desc" + XFSEARCH,
                AudioknigiPro.fetchUrl(S + "/cikl/anderdog/"));
        assertEquals(GENRE + "page/2/?dlenewssortby=date&dledirection=desc" + XFSEARCH,
                AudioknigiPro.fetchUrl(GENRE + "page/2/"));
        String sorted = P.inGenre(P.sortUrl(Sort.TITLE, GENRE), GENRE);
        assertEquals(sorted, AudioknigiPro.fetchUrl(sorted));
        assertEquals("a book page is a plain GET", BOOK, AudioknigiPro.fetchUrl(BOOK));
        assertEquals(AudioknigiTop.GENRES, AudioknigiPro.fetchUrl(AudioknigiTop.GENRES));
        assertEquals(AudioknigiTop.AUTHORS, AudioknigiPro.fetchUrl(AudioknigiTop.AUTHORS));
    }

    // ---------------------------------------------------------------- genres and people

    @Test
    public void genresAndPeopleIndexes() throws IOException {
        ProviderPage<GenrePOJO> genres = P.genres(fixture("genres.html", AudioknigiTop.GENRES), AudioknigiTop.GENRES, 1);
        assertEquals("the \"---\" row is left out", 106, genres.items.size());
        assertFalse(genres.hasMore);
        assertEquals("Детская литература", genres.items.get(0).getName());
        assertEquals(S + "/zhanr/detskaja-literatura/", genres.items.get(0).getUrl());
        assertEquals("Книг: 302", genres.items.get(0).getDescription());
        GenrePOJO fantasy = genres.items.get(5);
        assertEquals("Фантастика", fantasy.getName());
        assertEquals(GENRE, fantasy.getUrl());
        GenrePOJO lastGenre = genres.items.get(105);
        assertEquals("Хоррор", lastGenre.getName());
        assertEquals(S + "/zhanr/horror/", lastGenre.getUrl());
        assertEquals("Книг: 4", lastGenre.getDescription());
        for (GenrePOJO genre : genres.items) {
            assertTrue(genre.getUrl(), genre.getUrl().startsWith(S + "/zhanr/"));
        }

        ProviderPage<GenrePOJO> authors = P.people(fixture("people-authors.html", AudioknigiTop.AUTHORS),
                AudioknigiTop.AUTHORS, 1, false);
        assertEquals("the fixture keeps the first 150 of 2895", 150, authors.items.size());
        assertFalse(authors.hasMore);
        assertEquals("Юрий Третьяков", authors.items.get(0).getName());
        assertEquals(S + "/author/jurij-tretjakov/", authors.items.get(0).getUrl());
        assertEquals("Книг: 12", authors.items.get(0).getDescription());
        assertEquals("Дмитрий Мамин-Сибиряк", authors.items.get(149).getName());

        ProviderPage<GenrePOJO> readers = P.people(fixture("people-readers.html", AudioknigiTop.READERS),
                AudioknigiTop.READERS, 1, true);
        assertEquals(150, readers.items.size());
        assertEquals("Дмитрий Гугуев", readers.items.get(0).getName());
        assertEquals(S + "/read/dmitrij-guguev/", readers.items.get(0).getUrl());
        assertEquals("Книг: 15", readers.items.get(0).getDescription());
        assertEquals("Андрей Леонов", readers.items.get(149).getName());
        assertEquals("Книг: 112", readers.items.get(149).getDescription());
    }

    // ---------------------------------------------------------------- search

    @Test
    public void searchPages() throws IOException {
        String p1 = P.searchUrl(SearchUrls.Category.BOOKS, "архимага", 1);
        assertEquals(S + "/index.php?do=search&subaction=search&story=%D0%B0%D1%80%D1%85%D0%B8%D0%BC%D0%B0%D0%B3%D0%B0"
                + "&search_start=1&result_from=1&full_search=0", p1);
        String p3 = P.searchUrl(SearchUrls.Category.BOOKS, "архимага", 3);
        assertTrue(p3, p3.endsWith("&search_start=3&result_from=21&full_search=0"));
        assertNull(P.searchUrl(SearchUrls.Category.AUTHORS, "архимага", 1));
        assertNull(P.searchUrl(SearchUrls.Category.PERFORMERS, "архимага", 1));

        ProviderPage<BookPOJO> first = P.searchResults(fixture("search-p1.html", SEARCH), 1);
        assertEquals(ProviderPage.Status.OK, first.status);
        assertEquals(10, first.items.size());
        assertTrue(first.hasMore);
        BookPOJO hit = first.items.get(0);
        assertEquals("Дан. Навстречу судьбе", hit.getName());
        assertEquals(S + "/dan-navstrechu-sudybe", hit.getUrl());
        assertEquals("Игорь Хорт", hit.getAutor());
        assertEquals("Вениамин Пилюгин", hit.getArtist());
        assertEquals("12 ч. 5 мин.", hit.getTime());
        assertEquals("Дан", hit.getSeries());
        assertEquals(S + "/cikl/dan/", hit.getUrlSeries());
        assertEquals("Ловушка архимага", first.items.get(9).getName());

        ProviderPage<BookPOJO> end = P.searchResults(fixture("search-last.html", SEARCH), 3);
        assertEquals(4, end.items.size());
        assertFalse(end.hasMore);
        assertEquals("Если ведьме нужен муж - Валентина Гордова", end.items.get(3).getName());

        ProviderPage<BookPOJO> none = P.searchResults(fixture("search-none.html", SEARCH), 1);
        assertEquals(ProviderPage.Status.EMPTY, none.status);
        assertFalse(none.hasMore);
    }

    // ---------------------------------------------------------------- book page

    @Test
    public void bookPage() throws IOException {
        Document page = fixture("book.html", BOOK);
        DescriptionPOJO d = P.description(page, BOOK);
        assertEquals("Мастер Рун. Книга 12", d.getTitle());
        assertEquals(S + "/uploads/posts/2026-10/cb343863_c330ef840577b51c.jpg", d.getPoster());
        assertEquals("Артём Сластин, ПолуЁж", d.getAutor());
        assertEquals(S + "/author/artem-slastin-poluezh/", d.getAutorUrl());
        assertEquals("Саня БтрЪ", d.getArtist());
        assertEquals(S + "/read/sanja-btr/", d.getArtistUrl());
        assertEquals("Фантастика, фэнтези", d.getGenre());
        assertEquals(GENRE, d.getGenreUrl());
        assertEquals("Мастер Рун. Книга 1", d.getSeries());
        assertEquals(S + "/cikl/master-run-kniga-1/", d.getSeriesUrl());
        assertEquals("08:16:29 on the page", "8 ч. 16 мин.", d.getTime());
        assertEquals("0", d.getReiting());
        assertTrue(d.getDescription(), d.getDescription().startsWith("Когда возникает безвыходная ситуация")
                && d.getDescription().endsWith("в загадочной и странной Яме продолжаются."));

        assertTrue("no author block; the six newest books shown on every page are not related",
                P.recommendations(page, BOOK).isEmpty());

        Document older = fixture("book-comments.html", COMMENTED);
        DescriptionPOJO o = P.description(older, COMMENTED);
        assertEquals("the \"Название\" row, not the heading with its author",
                "Механика невозможного 2. Убийца на краю империи", o.getTitle());
        assertEquals("Ян Бадевский", o.getAutor());
        assertEquals("Роман Волков", o.getArtist());
        assertEquals("Фэнтези, Попаданцы", o.getGenre());
        assertEquals(S + "/zhanr/fjentezi/", o.getGenreUrl());
        assertEquals("Механика невозможного", o.getSeries());
        assertEquals("11 ч. 21 мин.", o.getTime());
        assertEquals("9.5", o.getReiting());
        assertEquals("4", o.getLike());
        assertEquals("3", o.getDisLike());
        assertTrue("byte order marks are dropped: " + o.getDescription(),
                o.getDescription().startsWith("Ян Бадевски - Убийца на краю Империи."));
        ArrayList<BookPOJO> more = P.recommendations(older, COMMENTED);
        assertEquals("\"Другие аудиокниги автора\" only", 6, more.size());
        assertEquals(S + "/mehanika-nevozmoghnogo-1-shkola-na-krayu-imperii-yan-badevskiy", more.get(0).getUrl());
        assertEquals("Механика невозможного 1. Школа на краю империи - Ян Бадевский", more.get(0).getName());
        assertEquals(S + "/uploads/posts/2024-07/shkola-na-kraju-imperii-jan-badevskij.jpg", more.get(0).getPhoto());
        assertEquals("9.63", more.get(0).getReting());
        assertEquals(S + "/korrektirovschik-yan-badevskiy", more.get(5).getUrl());
        for (BookPOJO b : more) {
            assertTrue(b.getUrl(), b.getUrl().endsWith("-yan-badevskiy") && !b.getUrl().equals(COMMENTED));
        }

        BookPOJO card = P.book(older, COMMENTED);
        assertEquals(COMMENTED, card.getUrl());
        assertEquals("Механика невозможного 2. Убийца на краю империи", card.getName());
        assertEquals("9.5", card.getReting());
        assertEquals(S + "/uploads/posts/2024-07/ubijca-na-kraju-imperii-jan-badevskij.jpg", card.getPhoto());
    }

    @Test
    public void commentsAreOnTheBookPage() throws IOException {
        ArrayList<ComentsPOJO> comments = AudioknigiTop.parseComments(fixture("book-comments.html", COMMENTED));
        assertEquals(5, comments.size());
        ComentsPOJO first = comments.get(0);
        assertEquals("Семен", first.getName());
        assertEquals("24 марта 2025 07:54", first.getDate());
        assertTrue(first.getText(), first.getText().startsWith("Вместе с красочным волшебным миром"));
        assertEquals(S + "/templates/top/dleimages/noavatar.png", first.getImage());
        assertEquals("", first.getReting());
        assertEquals("1", comments.get(2).getReting());
        ComentsPOJO reply = comments.get(3);
        assertEquals("Александр", reply.getName());
        assertEquals("ФСБ тебе в гости", reply.getText());
        assertEquals("Денис", reply.getQuoteName());
        assertTrue(reply.getQuoteText(), reply.getQuoteText().startsWith("Покажите хоть одного киллера"));
        assertEquals("юрий", comments.get(4).getName());
        assertTrue(AudioknigiTop.parseComments(fixture("book.html", BOOK)).isEmpty());
    }

    // ---------------------------------------------------------------- chapters

    @Test
    public void signedPlaylistOfNewerBooks() throws Exception {
        assertEquals(S + "/engine/ajax/controller.php?mod=audio_signed_playlist&id=40250",
                AudioknigiTop.playlistUrl(fixture("book.html", BOOK), BOOK));
        ArrayList<AudioPOJO> tracks = AudioknigiTop.parseTracks(text("playlist.json"), "Мастер Рун. Книга 12");
        assertEquals(19, tracks.size());
        AudioPOJO first = tracks.get(0);
        assertEquals("the number and the file's slug say no more than the position", "Глава 1", first.getName());
        assertEquals("Мастер Рун. Книга 12", first.getBookName());
        assertTrue(first.getUrl(), first.getUrl().startsWith(
                "https://s5.vozknig.com/uploads/files/2026-10/master-run-kniga-12/01_96699.mp3?site=slovushko&md5="));
        assertTrue(first.getUrl(), first.getUrl().contains("&expires="));
        assertEquals(0, first.getTime());
        AudioPOJO last = tracks.get(18);
        assertEquals("Глава 19", last.getName());
        assertTrue(last.getUrl(), last.getUrl().startsWith(
                "https://s5.vozknig.com/uploads/files/2026-10/master-run-kniga-12/19_96699.mp3?"));
    }

    @Test
    public void archivePlaylistOfOlderBooks() throws Exception {
        assertEquals(S + "/engine/ajax/controller.php?mod=audio_signed_playlist&id=15995",
                AudioknigiTop.playlistUrl(fixture("book-comments.html", COMMENTED), COMMENTED));
        ArrayList<AudioPOJO> tracks = AudioknigiTop.parseTracks(text("playlist-old.json"), "Механика");
        assertEquals(39, tracks.size());
        assertEquals("Глава 1", tracks.get(0).getName());
        assertEquals("https://archive.org/download/13_20240720_202407/01.mp3", tracks.get(0).getUrl());
        assertEquals("Глава 39", tracks.get(38).getName());
        assertEquals("https://archive.org/download/13_20240720_202407/39.mp3", tracks.get(38).getUrl());
    }

    @Test
    public void aPageWithoutPlayerIsNoBook() {
        try {
            AudioknigiTop.playlistUrl(Jsoup.parse("<h1>Книга</h1><p>Плеер скоро появится</p>", S), BOOK);
            fail("no player");
        } catch (BookRemovedException e) {
            fail("no notice, not taken down");
        } catch (IllegalStateException expected) {
            // a page without a player
        }
        try {
            AudioknigiTop.playlistUrl(Jsoup.parse("<h1>Книга</h1><div>Аудиокнига удалена по требованию "
                    + "правообладателя</div>", S), BOOK);
            fail("taken down");
        } catch (BookRemovedException expected) {
            // the takedown notice
        }
        try {
            AudioknigiTop.parseTracks("{\"ok\":false,\"newsid\":1}", "Книга");
            fail("an answer without a playlist has no chapters");
        } catch (IllegalStateException expected) {
            // no playable chapters
        }
    }

    @Test
    public void noUnknownFeatures() {
        assertTrue(P.hosts().isEmpty());
        assertTrue(P.refererHosts().isEmpty());
        assertNull(P.referer());
        assertNull(P.mediaHeaders(BOOK));
        assertEquals(Collections.emptyList(), P.periods(AudioknigiTop.INDEX));
        assertFalse(P.postsPages());
    }
}
