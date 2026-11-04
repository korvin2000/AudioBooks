package com.fanok.audiobooks.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fanok.audiobooks.model.OtherSourceModel;
import com.fanok.audiobooks.pojo.AudioPOJO;
import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.pojo.DescriptionPOJO;
import com.fanok.audiobooks.pojo.GenrePOJO;
import com.fanok.audiobooks.search.ProviderPage;
import com.fanok.audiobooks.search.SearchUrls;
import com.fanok.audiobooks.util.DownloadUtil;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Pages saved from audio-kniga.biz on 2026-11-04 (see src/test/resources/fixtures/audio-kniga-biz). */
public class AudioKnigaBizTest {

    private static final String S = AudioKnigaBiz.SERVER;
    private static final String BOOK = S + "/audio-97246-silantev-vadim-zasada";
    private static final String GENRE = S + "/genre-23-fantasy?page=";
    private static final String AUTHOR = S + "/avtor-9016-svallou-dzheims?page=";
    private static final String READER = S + "/ispolnitel-13067-inkvizitor-borshch?page=";

    private final AudioKnigaBiz site = new AudioKnigaBiz();

    private static InputStream open(String name) {
        InputStream in = AudioKnigaBizTest.class.getResourceAsStream("/fixtures/audio-kniga-biz/" + name);
        assertNotNull("missing fixture " + name, in);
        return in;
    }

    static Document page(String name, String url) throws IOException {
        try (InputStream in = open(name)) {
            return Jsoup.parse(in, "UTF-8", url);
        }
    }

    static String text(String name) throws IOException {
        try (InputStream in = open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0; ) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), "UTF-8");
        }
    }

    static List<Sort> flat(List<Sort.Entry> menu) {
        List<Sort> sorts = new ArrayList<>();
        for (Sort.Entry entry : menu) {
            sorts.add(entry.sort);
        }
        return sorts;
    }

    // ---------------------------------------------------------------- URLs and sorts

    @Test
    public void listingsPageWithThePageParameterAlone() {
        assertEquals(S + "/?page=2", site.pageUrl(AudioKnigaBiz.INDEX, 2));
        assertEquals(S + "/genre-23-fantasy", site.pageUrl(GENRE, 1));
        assertEquals(S + "/genre-23-fantasy?page=3", site.pageUrl(GENRE, 3));
        assertEquals(S + "/top?page=2", site.pageUrl(AudioKnigaBiz.TOP, 2));
        assertEquals(S + "/authors?page=4", site.pageUrl(AudioKnigaBiz.AUTHORS, 4));
    }

    @Test
    public void theOrderMenuIsNewAndTheTopHundred() {
        List<Sort> menu = Arrays.asList(Sort.NEW, Sort.TOP_100);
        assertEquals(menu, flat(site.sorts(AudioKnigaBiz.INDEX)));
        assertEquals(menu, flat(site.sorts(AudioKnigaBiz.TOP)));
        for (String url : new String[]{GENRE, AUTHOR, READER, S + "/series-3944-+S.T.A.L.K.E.R."}) {
            assertTrue(url, site.sorts(url).isEmpty());
        }
        assertEquals(AudioKnigaBiz.INDEX, site.sortUrl(Sort.NEW, AudioKnigaBiz.TOP));
        assertEquals(AudioKnigaBiz.TOP, site.sortUrl(Sort.TOP_100, AudioKnigaBiz.INDEX));
        assertNull("no random book: /random-book is 404", site.sortUrl(Sort.RANDOM, AudioKnigaBiz.INDEX));
        assertNull(site.sortUrl(Sort.POPULAR, AudioKnigaBiz.INDEX));
    }

    @Test
    public void searchIsOneStaticPageOfBooksAndAuthors() {
        assertEquals(S + "/search?text=%D0%93%D0%B0%D1%80%D1%80%D0%BE&page=2",
                site.searchUrl(SearchUrls.Category.BOOKS, "Гарро", 2));
        assertEquals(S + "/search?text=%D0%93%D0%B0%D1%80%D1%80%D0%BE&page=1",
                site.searchUrl(SearchUrls.Category.AUTHORS, "Гарро", 1));
        assertNull(site.searchUrl(SearchUrls.Category.PERFORMERS, "Гарро", 1));
        assertEquals(S + "/search?text=%D0%93%D0%B0%D1%80%D1%80%D0%BE%20%D0%BB%D0%B5%D0%B3%D0%B8%D0%BE%D0%BD",
                site.otherSourceSearchUrl("Гарро легион", 1));
    }

    // ---------------------------------------------------------------- listings

    @Test
    public void indexPagesHaveTwelveCards() throws IOException {
        ProviderPage<BookPOJO> first = site.listing(page("listing-index-p1.html", S + "/"), AudioKnigaBiz.INDEX, 1);
        assertEquals(12, first.items.size());
        assertTrue(first.hasMore);
        BookPOJO book = first.items.get(0);
        assertEquals("Гарро - Легион одного", book.getName());
        assertEquals(S + "/audio-102074-svallou-dzheyms-garro-legion-odnogo", book.getUrl());
        assertEquals("Сваллоу Джеймс", book.getAutor());
        assertEquals(S + "/avtor-9016-svallou-dzheims?page=", book.getUrlAutor());
        assertEquals("Инквизитор Борщ", book.getArtist());
        assertEquals(S + "/ispolnitel-13067-inkvizitor-borshch?page=", book.getUrlArtist());
        assertEquals("10 мин.", book.getTime());
        assertEquals("Фантастика", book.getGenre());
        assertEquals(GENRE, book.getUrlGenre());
        assertEquals("https://9giiu0g54k8c.redirectto.cc/s04/113623/img.jpg", book.getPhoto());
        assertEquals("Окопный ангел", first.items.get(11).getName());

        ProviderPage<BookPOJO> second = site.listing(page("listing-index-p2.html", S + "/?page=2"), S + "/?page=2", 2);
        assertEquals(12, second.items.size());
        assertTrue(second.hasMore);
        assertEquals("the author link differs in case from the title", "Город гибели", second.items.get(0).getName());
        assertEquals("Маккаммон Роберт", second.items.get(0).getAutor());
    }

    @Test
    public void listingsEndAtPage99WhereTheSiteStopsServing() throws IOException {
        Document doc = page("listing-index-p99.html", S + "/?page=99");
        assertTrue("the pager links page 100, which answers 403", Html.lastPage(doc.select(".pagination li a")) > 99);
        ProviderPage<BookPOJO> last = site.listing(doc, S + "/?page=99", 99);
        assertEquals(12, last.items.size());
        assertFalse(last.hasMore);
        // a card without a reader keeps its author and time
        BookPOJO noReader = last.items.get(3);
        assertEquals("Хип-хоп Америка", noReader.getName());
        assertEquals("Нельсон Джордж", noReader.getAutor());
        assertNull(noReader.getArtist());
        assertEquals("12 ч. 37 мин.", noReader.getTime());
        assertEquals("a reordered author", "Психология глупости", last.items.get(2).getName());
        assertEquals("co-authors", "Комедия с ошибкой", last.items.get(0).getName());
    }

    @Test
    public void theLastPageOfAGenreHasNoMore() throws IOException {
        ProviderPage<BookPOJO> last = site.listing(page("genre-last.html", S + "/genre-39-inostrannie?page=5"),
                S + "/genre-39-inostrannie?page=5", 5);
        assertEquals(7, last.items.size());
        assertFalse(last.hasMore);
        ProviderPage<BookPOJO> genre = site.listing(page("genre.html", S + "/genre-23-fantasy"), GENRE, 1);
        assertEquals(12, genre.items.size());
        assertTrue(genre.hasMore);
    }

    @Test
    public void topAuthorAndReaderListings() throws IOException {
        ProviderPage<BookPOJO> top = site.listing(page("top.html", AudioKnigaBiz.TOP), AudioKnigaBiz.TOP, 1);
        assertEquals(12, top.items.size());
        assertTrue(top.hasMore);
        assertEquals("Взгляд тигра", top.items.get(0).getName());

        ProviderPage<BookPOJO> author = site.listing(page("author.html", AUTHOR), AUTHOR, 1);
        assertEquals(12, author.items.size());
        assertTrue(author.hasMore);
        assertEquals("an en dash after the author", "Deus Ex. Падший ангел", author.items.get(1).getName());
        assertEquals("Гарро: рыцарь в сером - Warhammer - Ересь Хоруса", author.items.get(6).getName());

        ProviderPage<BookPOJO> reader = site.listing(page("reader.html", READER), READER, 1);
        assertEquals(2, reader.items.size());
        assertFalse(reader.hasMore);
        assertEquals("Инквизитор Борщ", reader.items.get(1).getArtist());
    }

    @Test
    public void genres() throws IOException {
        ProviderPage<GenrePOJO> genres = site.genres(page("genres.html", AudioKnigaBiz.GENRES), AudioKnigaBiz.GENRES,
                1);
        assertEquals(24, genres.items.size());
        assertFalse(genres.hasMore);
        assertEquals("Фантастика", genres.items.get(0).getName());
        assertEquals(GENRE, genres.items.get(0).getUrl());
        assertEquals("20827 книг", genres.items.get(0).getDescription());
        assertEquals("На иностранных языках", genres.items.get(23).getName());
        assertEquals("59 книг", genres.items.get(23).getDescription());
    }

    // ---------------------------------------------------------------- search

    @Test
    public void searchHitsNameTheirAuthorFirst() throws IOException {
        Document doc = page("search.html", S + "/search?text=x");
        ProviderPage<BookPOJO> hits = site.searchResults(doc, 1);
        assertEquals(13, hits.items.size());
        assertFalse(hits.hasMore);
        BookPOJO first = hits.items.get(0);
        assertEquals("Немезида", first.getName());
        assertEquals("Сваллоу Джеймс", first.getAutor());
        assertEquals(S + "/audio-28843-svallou-dzheyms-nemezida", first.getUrl());
        assertNull("the cover's CDN folder is not the book's id", first.getPhoto());
        assertEquals("Гарро: рыцарь в сером - Warhammer - Ересь Хоруса", hits.items.get(6).getName());
        assertEquals("Джеймс Сваллоу", hits.items.get(6).getAutor());
        assertEquals(ProviderPage.Status.EMPTY, site.searchResults(doc, 2).status);
        assertEquals(ProviderPage.Status.EMPTY,
                site.searchResults(page("search-none.html", S + "/search?text=x"), 1).status);

        ProviderPage<GenrePOJO> authors = site.searchPeople(doc, 1, false);
        assertEquals(1, authors.items.size());
        assertEquals("Сваллоу Джеймс", authors.items.get(0).getName());
        assertEquals(S + "/avtor-9016-svallou-dzheims?page=", authors.items.get(0).getUrl());

        List<OtherSourceModel.Hit> other = site.otherSourceHits(doc);
        assertEquals(13, other.size());
        assertEquals("Гарро - Легион одного", other.get(12).title);
        assertEquals("Сваллоу Джеймс", other.get(12).author);
        assertEquals(S + "/audio-102074-svallou-dzheyms-garro-legion-odnogo", other.get(12).url);
    }

    // ---------------------------------------------------------------- people

    @Test
    public void readersArePagedFiftyAPage() throws IOException {
        ProviderPage<GenrePOJO> readers = site.people(page("performers.html", AudioKnigaBiz.READERS), 1,
                new SourceParser.PeopleCursor(), url -> {
                    throw new AssertionError("a paged list fetches nothing more: " + url);
                });
        assertEquals(49, readers.items.size());
        assertTrue(readers.hasMore);
        assertEquals("Булдаков Олег", readers.items.get(0).getName());
        assertEquals(S + "/ispolnitel-3801-buldakov-oleg?page=", readers.items.get(0).getUrl());
        assertEquals("1413 книг", readers.items.get(0).getDescription());
    }

    @Test
    public void authorsAreTheAlphabetOneLetterAPage() throws IOException {
        Document index = page("authors.html", S + "/authors");
        List<String> letters = site.letters(index);
        assertEquals(46, letters.size());
        assertEquals("Russian letters first", S + "/authors/let-А", letters.get(0));
        assertEquals(S + "/authors/let-A", letters.get(30));
        List<String> fetched = new ArrayList<>();
        SourceParser.PeopleCursor cursor = new SourceParser.PeopleCursor();
        for (int pageNo = 1; pageNo <= 3; pageNo++) {
            ProviderPage<GenrePOJO> rows = site.people(index, pageNo, cursor, url -> {
                fetched.add(url);
                return page("authors-let-yu.html", url);
            });
            assertEquals(148, rows.items.size());
            assertTrue(rows.hasMore);
        }
        assertEquals(Arrays.asList(S + "/authors/let-%D0%90", S + "/authors/let-%D0%91", S + "/authors/let-%D0%92"),
                fetched);
        ProviderPage<GenrePOJO> rows = null;
        for (int pageNo = 4; pageNo <= 46; pageNo++) {
            rows = site.people(index, pageNo, cursor, url -> {
                fetched.add(url);
                return page("authors-let-yu.html", url);
            });
        }
        assertEquals(46, fetched.size());
        assertEquals(S + "/authors/let-Z", fetched.get(45));
        assertFalse("Z ends the list", rows.hasMore);
        GenrePOJO first = site.people(index, 1, cursor, url -> page("authors-let-yu.html", url)).items.get(0);
        assertEquals("Ю Рё Хан", first.getName());
        assertEquals(S + "/avtor-28906-yu-re-han?page=", first.getUrl());
        assertEquals("1 книг", first.getDescription());
    }

    // ---------------------------------------------------------------- book page

    @Test
    public void bookPage() throws IOException {
        Document doc = page("book.html", BOOK);
        DescriptionPOJO d = site.description(doc, BOOK);
        assertEquals("the en dash after the author goes too", "Засада", d.getTitle());
        assertEquals("Силантьев Вадим", d.getAutor());
        assertEquals(S + "/avtor-19873-silantev-vadim?page=", d.getAutorUrl());
        assertEquals("Кравченко Ольга", d.getArtist());
        assertEquals(S + "/ispolnitel-11916-kravchenko-olga?page=", d.getArtistUrl());
        assertEquals("Приключения", d.getGenre());
        assertEquals(S + "/genre-34-priklucheniya?page=", d.getGenreUrl());
        assertEquals("the page says \"Без серии\" for every book", "", d.getSeries());
        assertEquals("58 мин.", d.getTime());
        assertEquals("964", d.getReiting());
        assertEquals("https://9giiu0g54k8c.redirectto.cc/s04/104446/img.jpg", d.getPoster());
        assertTrue(d.getDescription(), d.getDescription().startsWith("Приключенческая новелла, основанная"));
        assertTrue(d.getDescription(), d.getDescription().endsWith("от Вадима Силантьева!"));

        BookPOJO card = site.book(doc, BOOK);
        assertEquals("Засада", card.getName());
        assertEquals(BOOK, card.getUrl());
        assertEquals("964", card.getReting());
        assertEquals("Засада", site.bookName(doc));

        ArrayList<BookPOJO> similar = site.recommendations(doc, BOOK);
        assertEquals(6, similar.size());
        assertEquals("Стервятники Уэйптона", similar.get(0).getName());
        assertEquals("Говард Роберт", similar.get(0).getAutor());
        assertEquals(S + "/audio-102004-govard-robert-stervyatniki-ueyptona", similar.get(0).getUrl());

        assertEquals(1, Abmp3Engine.parseComments(doc).comments.size());
        assertEquals("Д.Р.У.", Abmp3Engine.parseComments(doc).comments.get(0).getName().trim());
        assertTrue("no series to read, nothing fetched", site.loadSeries(BOOK).isEmpty());
    }

    // ---------------------------------------------------------------- chapters

    @Test
    public void aSingleFileBookIsOneChapterOfItsLength() throws IOException {
        ArrayList<AudioPOJO> tracks = Abmp3Engine.parsePlaylist(text("playlist.pl.txt"),
                "https://9giiu0g54k8c.redirectto.cc/s04/104446/104446.pl.txt", "Засада");
        assertEquals(1, tracks.size());
        assertEquals("Засада", tracks.get(0).getName());
        assertEquals("https://j3wccg4mgjcw.redirectto.cc/s04/104446/track-01.mp3", tracks.get(0).getUrl());
        assertEquals(3494, tracks.get(0).getTime());
        assertEquals("the whole file plays", -1, tracks.get(0).getTimeStart());
        assertEquals(-1, tracks.get(0).getTimeFinish());
    }

    @Test
    public void chaptersOfOneSharedFileAreItsTimeRanges() throws IOException {
        ArrayList<AudioPOJO> tracks = Abmp3Engine.parsePlaylist(text("playlist-shared.pl.txt"),
                "https://9giiu0g54k8c.redirectto.cc/s04/109856/109856.pl.txt", "Хип-хоп Америка");
        assertEquals(29, tracks.size());
        for (AudioPOJO track : tracks) {
            assertEquals("https://j3wccg4mgjcw.redirectto.cc/s04/109856/track-01.mp3", track.getUrl());
        }
        assertEquals("01. Введение", tracks.get(0).getName());
        assertEquals(0, tracks.get(0).getTimeStart());
        assertEquals(1695, tracks.get(0).getTimeFinish());
        assertEquals(1695, tracks.get(0).getTime());
        assertEquals(1695, tracks.get(1).getTimeStart());
        assertEquals(2743, tracks.get(1).getTimeFinish());
        assertEquals(42968, tracks.get(28).getTimeStart());
        assertEquals(45477, tracks.get(28).getTimeFinish());
        assertTrue("the shared file downloads once", site.downloadsSharedFiles());
    }

    @Test
    public void thePlaylistIsAskedWithTheBookAsRefererAndTheMediaWithTheSite() throws Exception {
        String playlist = text("playlist-shared.pl.txt");
        try (PlaylistServer server = new PlaylistServer(playlist)) {
            Document doc = Jsoup.parse(text("book.html").replace(
                    "https://9giiu0g54k8c.redirectto.cc/s04/104446/104446.pl.txt", server.url()), BOOK);
            ArrayList<AudioPOJO> tracks = site.tracks(doc, BOOK);
            assertEquals(BOOK, server.referer());
            assertEquals(29, tracks.size());
            assertEquals("Засада", tracks.get(0).getBookName());
            assertEquals(1695, tracks.get(1).getTimeStart());
        }
        Map<String, String> media = site.mediaHeaders(BOOK);
        assertEquals("the CDN answers 403 without the site's Referer", S + "/", media.get("Referer"));
        assertEquals(DownloadUtil.DESKTOP_UA, media.get("User-Agent"));
        assertEquals(S + "/", site.referer());
    }
}
