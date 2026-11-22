package com.fanok.audiobooks.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fanok.audiobooks.BookRemovedException;
import com.fanok.audiobooks.model.OtherSourceModel;
import com.fanok.audiobooks.pojo.AudioPOJO;
import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.pojo.DescriptionPOJO;
import com.fanok.audiobooks.pojo.GenrePOJO;
import com.fanok.audiobooks.search.ProviderPage;
import com.fanok.audiobooks.search.SearchUrls;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** mds-old.ru pages saved on 2026-11-21 (see src/test/resources/fixtures/mds). */
public class MdsTest {

    private static final Mds M = new Mds();
    private static final String S = Mds.SERVER;
    private static final String VARLEY = S + "/author/%D0%94%D0%B6%D0%BE%D0%BD_%D0%92%D0%B0%D1%80%D0%BB%D0%B8";
    private static final String BOOK = VARLEY + "/%D0%A0%D0%BE%D0%B1%D0%B8%D0%BD%D0%B7%D0%BE%D0%BD_%D0%9A%D1%80%D1%83%D0%B7%D0%BE";
    private static final String NO_PARTS = S + "/author/%D0%90%D0%BB%D0%B5%D0%BD_%D0%94%D0%BE%D1%80%D0%B5%D0%BC%D1%8C%D0%B5"
            + "/%D0%9F%D0%BE%D1%80%D0%B0_%D0%BC%D1%89%D0%B5%D0%BD%D0%B8%D1%8F";
    private static final String SHECKLEY = S + "/author/%D0%A0%D0%BE%D0%B1%D0%B5%D1%80%D1%82_%D0%A8%D0%B5%D0%BA%D0%BB%D0%B8";
    private static final String GENRE_SF = S + "/?cat=%D0%96%D0%B0%D0%BD%D1%80%D1%8B%2F%D0%BF%D0%BE%D0%B4%D0%B6%D0%B0"
            + "%D0%BD%D1%80%D1%8B%3A%D0%A4%D0%B0%D0%BD%D1%82%D0%B0%D1%81%D1%82%D0%B8%D0%BA%D0%B0";
    private static final String GENRE_FANTASY = S + "/?cat=%D0%96%D0%B0%D0%BD%D1%80%D1%8B%2F%D0%BF%D0%BE%D0%B4%D0%B6"
            + "%D0%B0%D0%BD%D1%80%D1%8B%3A%D0%A4%D1%8D%D0%BD%D1%82%D0%B5%D0%B7%D0%B8";
    private static final String SEARCH = S + "/?q=%D0%A8%D0%B5%D0%BA%D0%BB%D0%B8";

    private static Document page(String name, String url) throws IOException {
        try (InputStream in = MdsTest.class.getResourceAsStream("/fixtures/mds/" + name)) {
            assertNotNull("missing fixture " + name, in);
            return Jsoup.parse(in, "UTF-8", url);
        }
    }

    private static ProviderPage<BookPOJO> listing(String name, String url) throws IOException {
        return M.listing(page(name, url), url, 1);
    }

    private static void assertPage(ProviderPage<BookPOJO> page, int cards, boolean more, String firstName) {
        assertEquals(ProviderPage.Status.OK, page.status);
        assertEquals(cards, page.items.size());
        assertEquals(more, page.hasMore);
        assertEquals(firstName, page.items.get(0).getName());
    }

    // ---------------------------------------------------------------- listings

    @Test
    public void indexCardsCarryEverything() throws IOException {
        ProviderPage<BookPOJO> page = listing("list_new_p1.html", Mds.INDEX);
        assertPage(page, 20, true, "Муза");
        BookPOJO muse = page.items.get(0);
        String burgess = S + "/author/%D0%AD%D0%BD%D1%82%D0%BE%D0%BD%D0%B8_%D0%91%D0%B5%D1%80%D0%B4%D0%B6%D0%B5%D1%81%D1%81";
        assertEquals(burgess + "/%D0%9C%D1%83%D0%B7%D0%B0", muse.getUrl());
        assertEquals("Энтони Берджесс", muse.getAutor());
        assertEquals(burgess, muse.getUrlAutor());
        assertNull(muse.getArtist()); // no reader anywhere on the site
        assertEquals(S + "/covers/cover_zvnachpdY6mnrMipK_mvxiakfx85fc32.jpg", muse.getPhoto());
        assertEquals("3085 s", "51 мин.", muse.getTime());
        assertEquals("Фантастика, «Мягкая» (гуманитарная) научная фантастика", muse.getGenre());
        assertEquals(GENRE_SF, muse.getUrlGenre());
        assertEquals("3.3", muse.getReting());
        assertTrue(muse.getDesc(), muse.getDesc().startsWith("Специалист по истории литературы Пейли"));
        assertEquals("Часы времени", page.items.get(19).getName());
    }

    @Test
    public void laterAndLastPages() throws IOException {
        ProviderPage<BookPOJO> second = listing("list_new_p2.html", M.pageUrl(Mds.INDEX, 2));
        assertPage(second, 20, true, "Похороны великой Мамы");
        assertEquals(BOOK, second.items.get(8).getUrl());
        BookPOJO plugin = second.items.get(10); // two authors: the work lives under the first
        assertEquals("Екатерина Насута, Евгений Данилов", plugin.getAutor());
        String nasuta = S + "/author/%D0%95%D0%BA%D0%B0%D1%82%D0%B5%D1%80%D0%B8%D0%BD%D0%B0_%D0%9D%D0%B0%D1%81%D1%83%D1%82%D0%B0";
        assertEquals(nasuta, plugin.getUrlAutor());
        assertEquals(nasuta + "/%D0%9F%D0%BB%D0%B0%D0%B3%D0%B8%D0%BD", plugin.getUrl());

        ProviderPage<BookPOJO> last = listing("list_new_last.html", M.pageUrl(Mds.INDEX, 74));
        assertPage(last, 4, false, "Мудрость веков");
        assertEquals("Честные враги", last.items.get(3).getName());
    }

    @Test
    public void ratingIsItsOwnList() throws IOException {
        ProviderPage<BookPOJO> page = listing("list_rating.html", Mds.RATING);
        assertPage(page, 20, true, "Одинокая планета");
        assertEquals("4.6", page.items.get(0).getReting());
        assertEquals("Долина Проклятий", page.items.get(19).getName());
        // another order than the new works: none of the first five is the same
        ProviderPage<BookPOJO> fresh = listing("list_new_p1.html", Mds.INDEX);
        for (int i = 0; i < 5; i++) {
            assertFalse(page.items.get(i).getName().equals(fresh.items.get(i).getName()));
        }
    }

    @Test
    public void genreListingResolvesALongAnnotation() throws IOException {
        ProviderPage<BookPOJO> page = listing("genre.html", GENRE_FANTASY);
        assertPage(page, 20, true, "Ведьмин яр");
        assertEquals("Фэнтези, Героическое фэнтези", page.items.get(0).getGenre());
        assertEquals(GENRE_FANTASY, page.items.get(0).getUrlGenre());
        // its 1037 chars are the text row "$4d" of the payload, outside the JSON
        BookPOJO goblins = page.items.get(4);
        assertEquals("Заповедник Гоблинов", goblins.getName());
        assertEquals(1037, goblins.getDesc().length());
        assertTrue(goblins.getDesc().startsWith("Действие романа происходит в отдалённом будущем"));
        assertTrue(goblins.getDesc().endsWith("которая и носит название «Заповедник гоблинов»."));
    }

    @Test
    public void authorPageListsAllTheirWorks() throws IOException {
        ProviderPage<BookPOJO> page = listing("author.html", SHECKLEY);
        assertPage(page, 53, false, "Форма");
        assertEquals(SHECKLEY, page.items.get(0).getUrlAutor());
        assertEquals("После этой войны другой уже не будет", page.items.get(52).getName());
        assertTrue(M.sorts(SHECKLEY).isEmpty());
    }

    @Test
    public void theBuiltInAuthorLinkListsTheirWorks() throws IOException {
        // the people asset keeps the site's own link, /author/<First_Last>, unencoded
        String link = S + "/author/Роберт_Шекли";
        ProviderPage<BookPOJO> page = listing("author.html", link);
        assertPage(page, 53, false, "Форма");
        for (BookPOJO book : page.items) {
            assertEquals("Роберт Шекли", book.getAutor());
            assertEquals(SHECKLEY, book.getUrlAutor());
            assertTrue(book.getUrl(), book.getUrl().startsWith(SHECKLEY + "/"));
        }
        assertTrue(M.sorts(link).isEmpty());
    }

    // ---------------------------------------------------------------- search

    @Test
    public void searchPagesOnTheServer() throws IOException {
        assertEquals(SEARCH + "&page=1", M.searchUrl(SearchUrls.Category.BOOKS, "Шекли", 1));
        assertEquals(SEARCH + "&page=3", M.searchUrl(SearchUrls.Category.BOOKS, "Шекли", 3));
        assertEquals(SEARCH + "&page=3", M.pageUrl(M.searchUrl(SearchUrls.Category.BOOKS, "Шекли", 1), 3));
        assertNull(M.searchUrl(SearchUrls.Category.AUTHORS, "Шекли", 1));
        assertNull(M.searchUrl(SearchUrls.Category.PERFORMERS, "Шекли", 1));

        Document first = page("search.html", SEARCH);
        ProviderPage<BookPOJO> hits = M.searchResults(first, 1);
        assertPage(hits, 20, true, "Форма");
        for (BookPOJO book : hits.items) {
            assertEquals("Роберт Шекли", book.getAutor());
        }
        // ( ) kept, as the site writes them
        assertEquals(SHECKLEY + "/%D0%AD%D1%80%D0%B8%D0%BA%D1%81_(106.8)", hits.items.get(5).getUrl());

        ProviderPage<BookPOJO> last = M.searchResults(page("search_p3.html", SEARCH + "&page=3"), 3);
        assertPage(last, 15, false, "В стране чистых красок");
        // a "/" in a name is %2F: the site's own raw "/" link is a 404
        assertEquals(S + "/author/%D0%94%D0%B6%D0%B5%D1%80%D0%BE%D0%BC_%D0%9A._%D0%94%D0%B6%D0%B5%D1%80%D0%BE%D0%BC"
                + "/%D0%9A%D0%BE%D1%82_%D0%94%D0%B8%D0%BA%D0%B0_%D0%94%D0%B0%D0%BD%D0%BA%D0%B5%D1%80%D0%BC%D0%B0%D0%BD"
                + "%D0%B0_%2F_%D0%94%D0%BE%D0%BC_%D1%81%D1%82%D0%B0%D1%80%D0%BE%D1%81%D1%82%D0%B8",
                last.items.get(12).getUrl());

        String none = S + "/?q=zxqwvj";
        ProviderPage<BookPOJO> nothing = M.searchResults(page("search_none.html", none), 1);
        assertEquals(ProviderPage.Status.EMPTY, nothing.status);
        assertFalse(nothing.hasMore);
        assertTrue(M.sorts(SEARCH).isEmpty());
    }

    @Test
    public void otherSourcesSearchTheTitleAlone() throws IOException {
        BookPOJO book = new BookPOJO();
        book.setName("Муза");
        book.setAutor("Энтони Берджесс");
        book.setArtist("Влад Копп");
        assertEquals("Муза", M.otherSourceTerms(book));
        // the site takes the words as one phrase: the title with its author is in no work
        String both = S + "/?q=%D0%9C%D1%83%D0%B7%D0%B0+%D0%AD%D0%BD%D1%82%D0%BE%D0%BD%D0%B8+"
                + "%D0%91%D0%B5%D1%80%D0%B4%D0%B6%D0%B5%D1%81%D1%81&page=1";
        assertEquals(both, M.otherSourceSearchUrl(OtherSourceModel.query("Муза", "Энтони Берджесс"), 1));
        assertEquals(ProviderPage.Status.EMPTY,
                M.searchResults(page("search_title_author.html", both), 1).status);
        String title = M.otherSourceSearchUrl(M.otherSourceTerms(book), 1);
        assertEquals(S + "/?q=%D0%9C%D1%83%D0%B7%D0%B0&page=1", title);
        List<OtherSourceModel.Hit> hits = M.otherSourceHits(page("search_title.html", title));
        assertEquals(20, hits.size());
        String burgess = S + "/author/%D0%AD%D0%BD%D1%82%D0%BE%D0%BD%D0%B8_%D0%91%D0%B5%D1%80%D0%B4%D0%B6%D0%B5%D1%81%D1%81";
        assertEquals(burgess + "/%D0%9C%D1%83%D0%B7%D0%B0", hits.get(0).url);
        assertEquals("Муза", hits.get(0).title);
        assertEquals("Энтони Берджесс", hits.get(0).author);
    }

    @Test(expected = IllegalStateException.class)
    public void aPageWithoutWorksIsNoListing() throws IOException {
        Mds.parseListing(Jsoup.parse("<html><body><p>502 Bad Gateway</p></body></html>", S + "/"));
    }

    // ---------------------------------------------------------------- sorts, paging, genres

    @Test
    public void sortsAndPages() {
        assertEquals(Mds.INDEX, M.pageUrl(Mds.INDEX, 1));
        assertEquals(S + "/?sort=new&page=2", M.pageUrl(Mds.INDEX, 2));
        assertEquals(S + "/?sort=rating&page=2", M.pageUrl(Mds.RATING, 2));
        assertEquals(S + "/?page=2", M.pageUrl(Mds.GENRES, 2));
        assertEquals(S + "/?page=3", M.pageUrl(S + "/?page=2", 3));
        assertEquals(S + "/?sort=new&page=1", M.pageUrl(S + "/?sort=new&page=2", 1));
        assertEquals(GENRE_FANTASY + "&page=2", M.pageUrl(GENRE_FANTASY, 2));

        List<Sort> menu = new ArrayList<>();
        for (Sort.Entry entry : M.sorts(Mds.INDEX)) {
            menu.add(entry.sort);
        }
        assertEquals(Arrays.asList(Sort.NEW, Sort.RATING), menu);
        assertEquals(2, M.sorts(Mds.RATING).size());
        assertEquals(2, M.sorts(Mds.GENRES).size());
        assertEquals(Mds.INDEX, M.sortUrl(Sort.NEW, Mds.RATING));
        assertEquals(Mds.RATING, M.sortUrl(Sort.RATING, Mds.INDEX));
        assertNull(M.sortUrl(Sort.POPULAR, Mds.INDEX));
        // a genre is ranked by rating alone: the server drops sort=new there
        assertTrue(M.sorts(GENRE_FANTASY).isEmpty());
        assertTrue(M.listsDuration(Mds.INDEX));
        assertTrue(M.periods(Mds.INDEX).isEmpty());
    }

    @Test
    public void genresAreTheGenreFacet() throws IOException {
        ProviderPage<GenrePOJO> page = M.genres(page("genres.html", Mds.GENRES), Mds.GENRES, 1);
        assertFalse(page.hasMore);
        assertEquals(48, page.items.size());
        GenrePOJO sf = page.items.get(0);
        assertEquals("Фантастика", sf.getName());
        assertEquals(GENRE_SF, sf.getUrl());
        assertEquals(944, sf.getReting());
        assertEquals("", sf.getDescription());
        GenrePOJO soft = page.items.get(1);
        assertEquals("«Мягкая» (гуманитарная) научная фантастика", soft.getName());
        assertEquals("Фантастика", soft.getDescription());
        assertEquals(815, soft.getReting());
        GenrePOJO steampunk = page.items.get(47);
        assertEquals("Стимпанк/паропанк", steampunk.getName());
        assertEquals("Таймпанк", steampunk.getDescription());
        // every listing carries the same facet
        assertEquals(48, Mds.parseGenres(page("list_new_p1.html", Mds.INDEX)).size());
    }

    @Test
    public void authorsAreTheBuiltInListAndNoReaders() {
        assertEquals(Mds.SERVER + com.fanok.audiobooks.search.PeopleIndex.AUTHORS, Mds.AUTHORS);
        assertNull(Mds.READERS);
    }

    // ---------------------------------------------------------------- book

    @Test
    public void bookPage() throws IOException {
        DescriptionPOJO d = M.description(page("book.html", BOOK), BOOK);
        assertEquals("Робинзон Крузо", d.getTitle());
        assertEquals("Джон Варли", d.getAutor());
        assertEquals(VARLEY, d.getAutorUrl());
        assertEquals("", d.getArtist());
        assertEquals(S + "/covers/cover_yrxGkCEcn4NftAc2A_mvxhzb0le78786.jpg", d.getPoster());
        assertEquals("7151 s", "1 ч. 59 мин.", d.getTime());
        assertEquals(BookPOJO.formatRating(3.6875f, "24"), d.getReiting());
        assertEquals("Фантастика, «Мягкая» (гуманитарная) научная фантастика", d.getGenre());
        assertEquals(GENRE_SF, d.getGenreUrl());
        String[] lines = d.getDescription().split("\n");
        assertEquals(2, lines.length);
        assertTrue(lines[0].startsWith("Его зовут Пирри, и он работает \"Робинзоном Крузо\""));
        assertTrue(lines[1].startsWith("Однажды в море, он встретил странную женщину"));
        assertEquals("", d.getSeries());

        BookPOJO card = M.book(page("book.html", BOOK), BOOK);
        assertEquals("Робинзон Крузо", card.getName());
        assertEquals(BOOK, card.getUrl());
    }

    @Test
    public void recommendationsAreTheAuthorsAndTheSimilarWorks() throws IOException {
        ArrayList<BookPOJO> others = M.recommendations(page("book.html", BOOK), BOOK);
        assertEquals(21, others.size());
        assertEquals("Убить Барби", others.get(0).getName()); // "Ещё у автора" first
        assertEquals("Джон Варли", others.get(0).getAutor());
        for (BookPOJO other : others) {
            assertFalse(BOOK.equals(other.getUrl()));
        }
    }

    @Test
    public void partsAreTimeRangesOfOneMp3() throws IOException {
        ArrayList<AudioPOJO> parts = M.tracks(page("book.html", BOOK), BOOK);
        assertEquals(7, parts.size());
        String mp3 = S + "/api/play/yrxGkCEcn4NftAc2A";
        int[][] ranges = {{0, 861}, {861, 979}, {979, 2748}, {2748, 2808}, {2808, 6184}, {6184, 6299}, {6299, 7163}};
        for (int i = 0; i < parts.size(); i++) {
            AudioPOJO part = parts.get(i);
            assertEquals(mp3, part.getUrl());
            assertEquals("Робинзон Крузо", part.getBookName());
            assertEquals(i % 2 == 0 ? "Робинзон Крузо" : "Перерыв", part.getName());
            assertEquals(ranges[i][0], part.getTimeStart());
            assertEquals(ranges[i][1], part.getTimeFinish());
            assertEquals(ranges[i][1] - ranges[i][0], part.getTime());
        }
        assertTrue(M.downloadsSharedFiles());
    }

    @Test
    public void aWorkInOnePieceIsOneChapter() throws IOException {
        Document page = page("book_noparts.html", NO_PARTS);
        assertEquals("Пора мщения", M.description(page, NO_PARTS).getTitle());
        ArrayList<AudioPOJO> whole = M.tracks(page, NO_PARTS);
        assertEquals(1, whole.size());
        assertEquals(S + "/api/play/ztWgBYdjyWmCjjB8y", whole.get(0).getUrl());
        assertEquals("Пора мщения", whole.get(0).getName());
        assertEquals(816, whole.get(0).getTime());
        assertEquals(-1, whole.get(0).getTimeStart());
        assertEquals(-1, whole.get(0).getTimeFinish());
    }

    /**
     * Шекли's saved Soundstream episode (no recording here); a rights takedown has the saved work's flag set, as none
     * was found live to save.
     */
    @Test
    public void aWorkWithoutItsRecordingHasNoPlayer() throws IOException {
        JsonObject soundstream = null;
        JsonObject authorProps = Mds.props(Mds.payload(page("author.html", SHECKLEY)), "works");
        assertNotNull(authorProps);
        for (JsonElement w : authorProps.getAsJsonArray("works")) {
            if ("6ab968d2e62674fb2222aa5b".equals(w.getAsJsonObject().get("id").getAsString())) {
                soundstream = w.getAsJsonObject();
            }
        }
        assertNotNull(soundstream);
        assertEquals("После этой войны другой уже не будет", soundstream.get("name").getAsString());
        assertTrue(soundstream.get("isSoundstream").getAsBoolean());
        assertTrue(soundstream.get("audioUrl").isJsonNull());
        String soundstreamUrl = Mds.workUrl(soundstream);
        try {
            Mds.chapters(soundstream, soundstreamUrl);
            fail("a Soundstream episode plays");
        } catch (BookRemovedException expected) {
            assertEquals(soundstreamUrl, expected.getMessage());
        }

        JsonObject work = Mds.work(Mds.payload(page("book_noparts.html", NO_PARTS)));
        assertNotNull(work);
        JsonObject blocked = work.deepCopy();
        blocked.add("rightsBlocked", new JsonPrimitive(true));
        try {
            Mds.chapters(blocked, NO_PARTS);
            fail("rightsBlocked plays");
        } catch (BookRemovedException expected) {
            assertEquals(NO_PARTS, expected.getMessage());
        }
        JsonObject silent = work.deepCopy();
        silent.add("audioUrl", new JsonPrimitive(""));
        try {
            Mds.chapters(silent, NO_PARTS);
            fail("a work without audioUrl plays");
        } catch (BookRemovedException wrong) {
            fail("not a takedown");
        } catch (IllegalStateException expected) {
            // no player
        }
    }

    @Test
    public void aPageWithoutAWorkIsNoBook() throws IOException {
        Document authorPage = page("author.html", SHECKLEY);
        assertEquals("", M.description(authorPage, SHECKLEY).getTitle());
        try {
            M.tracks(authorPage, SHECKLEY);
            fail("an author page plays");
        } catch (BookRemovedException wrong) {
            fail("not a takedown");
        } catch (IllegalStateException expected) {
            // no player
        }
    }
}
