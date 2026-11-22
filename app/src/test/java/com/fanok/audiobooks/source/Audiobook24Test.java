package com.fanok.audiobooks.source;

import static org.junit.Assert.assertArrayEquals;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Pages saved from www.audiobook24.ru and archive.org on 2026-11-21 (src/test/resources/fixtures/audiobook24). */
public class Audiobook24Test {

    private static final String S = Audiobook24.SERVER;
    private static final String BOOK = S + "/aleksandr-shuvalov-dzhoker/";
    private static final String OLD_BOOK = S + "/lev-tolstoj-anna-karenina/";
    private static final String REMOVED = S + "/audiobooks/zarubezhnaja_literatura_slushat_onlajn/"
            + "zarubezhnaja_fantastika_slushat_onlajn_skachat_besplatno_bez_registracii/"
            + "dzhoan_rouling_garri_potter_i_prokljatoe_ditja/13-1-0-6420";
    private static final String BOOK_2021 = S + "/velikaya-otechestvennaya-vojna-1941-1945/";
    private static final String DOWNLOAD_ONLY = S + "/anton-chehov-vishnevyj-sad/";
    private static final String GENRE = S + "/radiospektakli_i_audiopostanovki/";
    private static final String CAT = S + "/?cat=2029";
    private static final String TAG = S + "/tag/aleksandr-shuvalov/";
    private static final String BY_VIEWS = "v_sortby=views&v_orderby=desc";

    private final Audiobook24 site = new Audiobook24();

    private static InputStream open(String name) {
        InputStream in = Audiobook24Test.class.getResourceAsStream("/fixtures/audiobook24/" + name);
        assertNotNull("missing fixture " + name, in);
        return in;
    }

    private static Document page(String name, String url) throws IOException {
        try (InputStream in = open(name)) {
            return Jsoup.parse(in, "UTF-8", url);
        }
    }

    private static String text(String name) throws IOException {
        try (InputStream in = open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0; ) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static ProviderPage<BookPOJO> listing(String name, String url) throws IOException {
        return Audiobook24.parseListing(page(name, url));
    }

    private List<Sort> sorts(String url) {
        List<Sort> result = new ArrayList<>();
        for (Sort.Entry entry : site.sorts(url)) {
            result.add(entry.sort);
        }
        return result;
    }

    // ---------------------------------------------------------------- URLs and sorts

    @Test
    public void everyListingPagesOnBeforeItsQuery() {
        assertEquals(S + "/audiobooks/", site.pageUrl(Audiobook24.INDEX, 1));
        assertEquals(S + "/audiobooks/page/2/", site.pageUrl(Audiobook24.INDEX, 2));
        assertEquals(S + "/audiobooks/", site.pageUrl(S + "/audiobooks/page/7/", 1));
        assertEquals(S + "/audiobooks/page/2/?" + BY_VIEWS, site.pageUrl(Audiobook24.POPULAR, 2));
        assertEquals(S + "/page/3/?cat=2029", site.pageUrl(CAT, 3));
        assertEquals(GENRE + "page/45/?" + BY_VIEWS, site.pageUrl(GENRE + "?" + BY_VIEWS, 45));
        assertEquals(TAG + "page/2/", site.pageUrl(TAG, 2));
    }

    @Test
    public void searchIsWordPressS() {
        assertEquals(S + "/?s=%D0%B4%D0%B6%D0%BE%D0%BA%D0%B5%D1%80",
                site.searchUrl(SearchUrls.Category.BOOKS, "джокер", 1));
        assertEquals(S + "/page/2/?s=%D0%B2%D0%BE%D0%B9%D0%BD%D0%B0",
                site.searchUrl(SearchUrls.Category.BOOKS, "война", 2));
        assertNull(site.searchUrl(SearchUrls.Category.AUTHORS, "a", 1));
        assertNull(site.searchUrl(SearchUrls.Category.PERFORMERS, "a", 1));
    }

    @Test
    public void catalogAndCategoriesSortByDateAndViews() {
        List<Sort> menu = Arrays.asList(Sort.NEW, Sort.POPULAR);
        assertEquals(menu, sorts(Audiobook24.INDEX));
        assertEquals(menu, sorts(Audiobook24.POPULAR));
        assertEquals(menu, sorts(S + "/audiobooks/page/3/"));
        assertEquals(menu, sorts(GENRE));
        assertEquals(menu, sorts(CAT));
        assertEquals(Collections.<Sort>emptyList(), sorts(TAG));
        assertEquals(Collections.<Sort>emptyList(), sorts(S + "/?s=x"));
        assertEquals(Audiobook24.INDEX, site.sortUrl(Sort.NEW, Audiobook24.INDEX));
        assertEquals(S + "/audiobooks/?" + BY_VIEWS, site.sortUrl(Sort.POPULAR, Audiobook24.INDEX));
        assertEquals(Audiobook24.INDEX, site.sortUrl(Sort.NEW, Audiobook24.POPULAR));
        assertNull(site.sortUrl(Sort.RATING, Audiobook24.INDEX));
        assertFalse(site.listsDuration(Audiobook24.INDEX));
    }

    @Test
    public void aCategorySortsWithinItself() {
        assertNull(site.genreOf(Audiobook24.INDEX));
        assertNull(site.genreOf(Audiobook24.POPULAR));
        assertNull(site.genreOf(TAG));
        assertNull(site.genreOf(S + "/?s=x"));
        assertEquals(GENRE, site.genreOf(GENRE));
        assertEquals(GENRE, site.genreOf(GENRE + "page/2/?" + BY_VIEWS));
        assertEquals(CAT, site.genreOf(S + "/page/2/?cat=2029&" + BY_VIEWS));
        assertEquals(GENRE, site.sortUrl(Sort.NEW, GENRE));
        assertEquals(GENRE + "?" + BY_VIEWS, site.inGenre(site.sortUrl(Sort.POPULAR, GENRE), GENRE));
        assertEquals(CAT + "&" + BY_VIEWS, site.inGenre(site.sortUrl(Sort.POPULAR, CAT), CAT));
        assertEquals(GENRE, site.inGenre(site.sortUrl(Sort.NEW, GENRE + "?" + BY_VIEWS), GENRE));
        assertEquals(GENRE + "?" + BY_VIEWS, site.sortUrl(Sort.POPULAR, GENRE + "?" + BY_VIEWS));
    }

    // ---------------------------------------------------------------- listings

    @Test
    public void catalogCards() throws IOException {
        ProviderPage<BookPOJO> page = listing("listing.html", Audiobook24.INDEX);
        assertEquals(18, page.items.size());
        assertTrue(page.hasMore);
        BookPOJO first = page.items.get(0);
        assertEquals(S + "/dmitrij-gluhovskij-registratsiya/", first.getUrl());
        assertEquals("Регистрация", first.getName());
        assertEquals("Дмитрий Глуховский", first.getAutor());
        assertEquals(S + "/wp-content/uploads/2026/11/00000-1-1-1-17.jpg", first.getPhoto());
        assertEquals("Русская проза, повести, романы", first.getGenre());
        assertEquals(S + "/russkaya_proza_romany/", first.getUrlGenre());
        assertTrue(first.getDesc(), first.getDesc().startsWith("В аудиокниге Дмитрия Глуховского"));
        assertNull(first.getTime());
        assertNull(first.getArtist());
        BookPOJO last = page.items.get(17);
        assertEquals(S + "/aleksandr-afanasev-bolshaya-igra/", last.getUrl());
        assertEquals("Большая игра", last.getName());
        assertEquals("Александр Афанасьев", last.getAutor());
        assertEquals("Русские детективы и боевики", last.getGenre());
        BookPOJO series = page.items.get(7);
        assertEquals("Клим Ветров", series.getAutor());
        assertEquals("Девятиэтажники. Девятиэтажка 5", series.getName());
    }

    @Test
    public void laterAndLastCatalogPages() throws IOException {
        ProviderPage<BookPOJO> second = listing("listing_p2.html", S + "/audiobooks/page/2/");
        assertEquals(18, second.items.size());
        assertTrue(second.hasMore);
        assertEquals(S + "/leonid-yuzefovich-kostyum-arlekina/", second.items.get(0).getUrl());
        assertEquals("Радиоспектакли и аудиопостановки", second.items.get(17).getGenre());
        ProviderPage<BookPOJO> last = listing("listing_last.html", S + "/audiobooks/page/574/");
        assertEquals(7, last.items.size());
        assertFalse(last.hasMore);
        assertEquals(S + "/svetlana-aleksievich-tsinkovye-malchiki/", last.items.get(6).getUrl());
        assertEquals("Светлана Алексиевич", last.items.get(6).getAutor());
        assertEquals("Военные аудиокниги о войне в Афгане", last.items.get(6).getGenre());
    }

    @Test
    public void sortedByViews() throws IOException {
        ProviderPage<BookPOJO> popular = listing("popular.html", Audiobook24.POPULAR);
        assertEquals(18, popular.items.size());
        assertTrue(popular.hasMore);
        assertEquals(S + "/margaret-mitchell-unesennye-vetrom/", popular.items.get(0).getUrl());
        assertEquals("Унесенные ветром", popular.items.get(0).getName());
        assertEquals("Зарубежная классика", popular.items.get(0).getGenre());
        ProviderPage<BookPOJO> second = listing("popular_p2.html", site.pageUrl(Audiobook24.POPULAR, 2));
        assertTrue(second.hasMore);
        assertEquals(S + "/aleksandr-bushkov-svarog-1-rytsar-iz-niotkuda/", second.items.get(0).getUrl());
        ProviderPage<BookPOJO> genre = listing("genre_popular.html", GENRE + "?" + BY_VIEWS);
        assertEquals(18, genre.items.size());
        assertEquals(S + "/boris-akunin-smert-ahillesa/", genre.items.get(0).getUrl());
    }

    /**
     * A {@code ?cat=} genre pages on as {@code /page/N/?cat=<id>}: the site's own pager links that form. A category of
     * one page (2035, four books) has no pager, so the 404 of its {@code /page/2/?cat=2035} is never asked for.
     */
    @Test
    public void catGenrePagesOnAndASinglePageOneStops() throws IOException {
        String cat = S + "/?cat=2016";
        ProviderPage<BookPOJO> second = listing("cat_p2.html", site.pageUrl(cat, 2));
        assertEquals(18, second.items.size());
        assertTrue(second.hasMore);
        assertEquals(S + "/dmitrij-veresov-blizhnij-bereg-nila-ili-vospitanie-chuvstv/", second.items.get(0).getUrl());
        assertEquals(S + "/page/3/?cat=2016", page("cat_p2.html", S + "/page/2/?cat=2016")
                .selectFirst(".pager a.next_page").absUrl("href"));
        assertEquals(S + "/page/3/?cat=2016", site.pageUrl(cat, 3));
        ProviderPage<BookPOJO> single = listing("cat_single.html", S + "/?cat=2035");
        assertEquals(4, single.items.size());
        assertFalse(single.hasMore);
        assertEquals("Поводырь 4. Без поводыря", single.items.get(0).getName());
        assertEquals("Андрей Дай", single.items.get(0).getAutor());
    }

    @Test
    public void categoryAndTagListings() throws IOException {
        ProviderPage<BookPOJO> genre = listing("genre.html", GENRE);
        assertEquals(18, genre.items.size());
        assertTrue(genre.hasMore);
        assertEquals(S + "/valerij-bryusov-poslednie-stranitsy-iz-dnevnika-zhenshhiny/", genre.items.get(0).getUrl());
        assertEquals("Радиоспектакли и аудиопостановки", genre.items.get(0).getGenre());
        assertEquals(GENRE, genre.items.get(0).getUrlGenre());
        ProviderPage<BookPOJO> tag = listing("tag.html", TAG);
        assertEquals(5, tag.items.size());
        assertFalse(tag.hasMore);
        assertEquals(BOOK, tag.items.get(0).getUrl());
        for (BookPOJO book : tag.items) {
            assertEquals("Александр Шувалов", book.getAutor());
        }
    }

    @Test
    public void searchCardsHaveNoCover() throws IOException {
        ProviderPage<BookPOJO> hits = site.searchResults(page("search.html",
                site.searchUrl(SearchUrls.Category.BOOKS, "джокер", 1)), 1);
        assertEquals(10, hits.items.size());
        assertFalse(hits.hasMore);
        BookPOJO first = hits.items.get(0);
        assertEquals(S + "/oleg-shelonin-viktor-bazhenov-dzhoker/", first.getUrl());
        assertEquals("Олег Шелонин, Виктор Баженов", first.getAutor());
        assertEquals("Джокер", first.getName());
        assertNull(first.getPhoto());
        assertEquals(BOOK, hits.items.get(5).getUrl());
        ProviderPage<BookPOJO> many = site.searchResults(page("search_many.html",
                site.searchUrl(SearchUrls.Category.BOOKS, "война", 1)), 1);
        assertEquals(18, many.items.size());
        assertTrue(many.hasMore);
        assertEquals("Война за \"Асгард\" 1. Путь Шута", many.items.get(17).getName());
        ProviderPage<BookPOJO> none = site.searchResults(page("search_empty.html", S + "/?s=qzxwvjkq"), 1);
        assertTrue(none.items.isEmpty());
        assertEquals(ProviderPage.Status.EMPTY, none.status);
    }

    @Test
    public void authorIsTheTitleUpToItsFirstWordDot() {
        assertArrayEquals(new String[]{"Дж. Р. Р. Толкин", "Хоббит"}, Audiobook24.authorTitle("Дж. Р. Р. Толкин. Хоббит"));
        assertArrayEquals(new String[]{"", "Сборник «Именем закона»"}, Audiobook24.authorTitle("Сборник «Именем закона»"));
        assertArrayEquals(new String[]{"Аркадий и Борис Стругацкие", "Какими вы будете"},
                Audiobook24.authorTitle("Аркадий и Борис Стругацкие. Какими вы будете"));
    }

    // ---------------------------------------------------------------- genres

    @Test
    public void genresAreTheCategorySelect() throws IOException {
        List<GenrePOJO> genres = site.genres(page("listing.html", Audiobook24.GENRES), Audiobook24.GENRES, 1).items;
        assertEquals(23, genres.size());
        assertEquals("Альтернативная история", genres.get(0).getName());
        assertEquals(S + "/?cat=2035", genres.get(0).getUrl());
        assertEquals("Военные аудиокниги о ВОВ", genres.get(1).getName());
        assertEquals("Радиоспектакли и аудиопостановки", genres.get(15).getName());
        assertEquals(CAT, genres.get(15).getUrl());
        assertEquals("Юмор и сатира аудиокниги", genres.get(22).getName());
        for (GenrePOJO genre : genres) {
            assertFalse(genre.getName(), genre.getName().equals("Аудиокниги") || genre.getName().contains("слушать"));
        }
    }

    // ---------------------------------------------------------------- book page

    @Test
    public void bookPage() throws IOException {
        DescriptionPOJO d = site.description(page("book.html", BOOK), BOOK);
        assertEquals("Джокер", d.getTitle());
        assertEquals("Александр Шувалов", d.getAutor());
        assertEquals(TAG, d.getAutorUrl());
        assertEquals("Сергей Ларионов", d.getArtist());
        assertEquals("", d.getArtistUrl());
        assertEquals("7 ч. 19 мин.", d.getTime());
        assertEquals("Русские детективы и боевики", d.getGenre());
        assertEquals(S + "/russkie_detektivy_i_boeviki/", d.getGenreUrl());
        assertEquals(S + "/wp-content/uploads/2026/11/00000-1-1-1-14.jpg", d.getPoster());
        String text = d.getDescription();
        assertTrue(text, text.startsWith("Главный герой аудиокниги Александра Шувалова «Джокер» Владислав Дорохов"));
        assertTrue(text, text.endsWith("между верностью команде и холодным расчётом."));
        assertEquals(text, 4, text.split("\n").length);
        assertFalse(text, text.contains("Время звучания") || text.contains("скачать"));
        BookPOJO card = site.book(page("book.html", BOOK), BOOK);
        assertEquals("Джокер", card.getName());
        assertEquals("Сергей Ларионов", card.getArtist());
    }

    @Test
    public void oldBookPage() throws IOException {
        DescriptionPOJO d = site.description(page("book_old.html", OLD_BOOK), OLD_BOOK);
        assertEquals("Анна Каренина", d.getTitle());
        assertEquals("Лев Толстой", d.getAutor());
        assertEquals(S + "/tag/lev_tolstoj/", d.getAutorUrl());
        assertEquals("Евгений Терновский", d.getArtist());
        assertEquals("42 ч. 2 мин.", d.getTime());
        assertEquals("Русская классика", d.getGenre());
        assertTrue(d.getDescription().startsWith("В аудиокниге Льва Толстого «Анна Каренина»"));
    }

    /** A 2021 post: the time, publisher and reader ("читает: X") are the text's own last paragraphs. */
    @Test
    public void bookPageOf2021() throws IOException {
        DescriptionPOJO d = site.description(page("book_2021.html", BOOK_2021), BOOK_2021);
        assertEquals("Великая Отечественная война 1941-1945", d.getTitle());
        assertEquals("", d.getAutor());
        assertEquals("Евгений Смирнов", d.getArtist());
        assertEquals("3 ч. 50 мин.", d.getTime());
        String text = d.getDescription();
        assertTrue(text, text.startsWith("В аудиокниге «Великая Отечественная война 1941-1945»"));
        assertEquals(text, 3, text.split("\n").length);
        assertFalse(text, text.contains("Время звучания") || text.contains("Издательство") || text.contains("читает"));
        assertEquals("drako_emailna_1945", Audiobook24.archiveItem(page("book_2021.html", BOOK_2021), BOOK_2021));
    }

    // ---------------------------------------------------------------- chapters

    @Test
    public void chaptersAreTheArchiveOrgItemsOriginals() throws IOException {
        assertEquals("043_20261114_202611", Audiobook24.archiveItem(page("book.html", BOOK), BOOK));
        ArrayList<AudioPOJO> tracks = Bookish.parseArchiveItem(text("playlist.json"), "Джокер", BOOK);
        assertEquals(49, tracks.size());
        assertEquals("https://archive.org/download/043_20261114_202611/001.mp3", tracks.get(0).getUrl());
        assertEquals("001.mp3", tracks.get(0).getName());
        assertEquals(727, tracks.get(0).getTime());
        assertEquals("https://archive.org/download/043_20261114_202611/049.mp3", tracks.get(48).getUrl());
        int total = 0;
        for (AudioPOJO track : tracks) {
            total += track.getTime();
        }
        assertEquals(34756, total); // 9.65 h of originals, while the page states 07:19:59
    }

    @Test
    public void oldBookChapters() throws IOException {
        assertEquals("07-14-00-aina_202610", Audiobook24.archiveItem(page("book_old.html", OLD_BOOK), OLD_BOOK));
        ArrayList<AudioPOJO> tracks = Bookish.parseArchiveItem(text("playlist_old.json"), "Анна Каренина", OLD_BOOK);
        assertEquals(301, tracks.size());
        assertEquals("https://archive.org/download/07-14-00-aina_202610/00_00_00_Anna_Karenina.mp3",
                tracks.get(0).getUrl());
        assertEquals("00_00_00_Anna_Karenina", tracks.get(0).getName());
        assertEquals(30, tracks.get(0).getTime());
        assertEquals("https://archive.org/download/07-14-00-aina_202610/08_19_00_Anna_Karenina.mp3",
                tracks.get(300).getUrl());
        int total = 0;
        for (AudioPOJO track : tracks) {
            total += track.getTime();
        }
        assertEquals(151211, total); // 42:00:11, the page states 42:02:38
    }

    @Test
    public void aBookTakenDownIsRemoved() throws IOException {
        Document removed = page("removed.html", REMOVED);
        assertEquals("Гарри Поттер и Проклятое дитя", site.description(removed, REMOVED).getTitle());
        try {
            site.tracks(removed, REMOVED);
            fail("taken down");
        } catch (BookRemovedException expected) {
            // the right holder's notice, no player
        }
    }

    /** "Это произведение доступно только для скачивания": no frame, only /out/ links to disk.yandex.ru. */
    @Test(expected = IllegalStateException.class)
    public void aDownloadOnlyBookIsAnError() throws IOException {
        Document book = page("download_only.html", DOWNLOAD_ONLY);
        assertEquals("Вишневый сад", site.description(book, DOWNLOAD_ONLY).getTitle());
        site.tracks(book, DOWNLOAD_ONLY);
    }
}
