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

import org.jsoup.nodes.Document;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * aubook.org pages saved on 2026-11-21 (src/test/resources/fixtures/aubook): the {@link Aukniga} engine on the
 * mirror's routes, without the home page's popular list.
 */
public class AubookTest {

    private static final Aubook SITE = new Aubook();
    private static final String S = Aubook.SERVER;
    private static final String GENRE = S + "/cat/fantastika";
    private static final String AUTHOR = S + "/author/arkadiy-i-boris-strugackie";
    private static final String READER = S + "/voice-actor/levashev-vladimir";
    private static final String BOOK = S + "/book/poisk-prednaznacheniya-ili-dvadcat-sedmaya-teorema-etiki-arkadiy-i"
            + "-boris-strugackie";
    private static final String TRIAL = S + "/book/leon-marina-dyachenko";

    private static Document page(String name, String url) throws IOException {
        return BookPageFixtures.load("aubook/" + name, url);
    }

    private static ProviderPage<BookPOJO> listing(String name, String url) throws IOException {
        return SITE.listing(page(name, url), url, 1);
    }

    @Test
    public void catalogCardsAndPages() throws IOException {
        assertEquals(S + "/books", Aubook.INDEX);
        ProviderPage<BookPOJO> page = listing("listing-p1.html", Aubook.INDEX);
        assertEquals(16, page.items.size());
        assertTrue(page.hasMore);
        BookPOJO first = page.items.get(0);
        assertEquals("Мир Трибунала. Тата и медведь", first.getName());
        assertEquals(S + "/book/mir-tribunala-tata-i-medved-mash-diana", first.getUrl());
        assertEquals(S + "/uploads/books/images/f/4/mir-tribunala-tata-i-medved-mash-diana.jpg", first.getPhoto());
        assertEquals("Маш Диана", first.getAutor());
        assertEquals(S + "/author/mash-diana", first.getUrlAutor());

        assertEquals(S + "/books/p/2", SITE.pageUrl(Aubook.INDEX, 2));
        ProviderPage<BookPOJO> second = listing("listing-p2.html", SITE.pageUrl(Aubook.INDEX, 2));
        assertEquals(16, second.items.size());
        assertTrue(second.hasMore);

        ProviderPage<BookPOJO> last = listing("listing-last.html", SITE.pageUrl(Aubook.INDEX, 2867));
        assertEquals(13, last.items.size());
        assertFalse(last.hasMore);
        assertEquals("Трудности белых ворон", last.items.get(12).getName());
    }

    @Test
    public void noSortsWithoutTheHomePage() {
        assertTrue(SITE.sorts(Aubook.INDEX).isEmpty());
        assertNull(SITE.sortUrl(Sort.POPULAR, Aubook.INDEX));
        assertFalse(SITE.listsDuration(Aubook.INDEX));
    }

    @Test
    public void genresAndPeople() throws IOException {
        List<GenrePOJO> genres = SITE.genres(page("genres.html", Aubook.GENRES), Aubook.GENRES, 1).items;
        assertEquals(57, genres.size());
        assertEquals(GENRE, genres.get(50).getUrl());

        ProviderPage<BookPOJO> genre = listing("genre.html", GENRE);
        assertEquals(16, genre.items.size());
        assertTrue(genre.hasMore);
        assertEquals(GENRE + "/p/2", SITE.pageUrl(GENRE, 2));

        ProviderPage<GenrePOJO> authors = SITE.people(page("authors.html", Aubook.AUTHORS), Aubook.AUTHORS, 1, false);
        assertEquals(50, authors.items.size());
        assertTrue(authors.hasMore);
        assertEquals(S + "/author/ya-ne-znayu", authors.items.get(0).getUrl());
        assertEquals(S + "/authors/p/2", SITE.pageUrl(Aubook.AUTHORS, 2));
        assertNull(Aubook.READERS);

        ProviderPage<BookPOJO> author = listing("author.html", AUTHOR);
        assertEquals(16, author.items.size());
        assertEquals(BOOK, author.items.get(15).getUrl());
        ProviderPage<BookPOJO> reader = listing("reader.html", READER);
        assertEquals(16, reader.items.size());
        assertTrue(reader.hasMore);
        assertEquals(READER + "/p/2", SITE.pageUrl(READER, 2));
    }

    @Test
    public void search() throws IOException {
        String url = SITE.searchUrl(SearchUrls.Category.BOOKS, "метро", 1);
        assertEquals(S + "/search?q=%D0%BC%D0%B5%D1%82%D1%80%D0%BE&page=1", url);
        Document doc = page("search.html", url);
        ProviderPage<BookPOJO> books = SITE.searchResults(doc, 1);
        assertEquals(24, books.items.size());
        assertFalse(books.hasMore);
        assertEquals(S + "/book/metro-1-metro-2033-dmitriy-gluhovskiy", books.items.get(0).getUrl());
        ProviderPage<GenrePOJO> authors = SITE.searchPeople(doc, 1, false);
        assertEquals(3, authors.items.size());
        assertEquals(S + "/author/metro-2034", authors.items.get(0).getUrl());
        assertEquals(ProviderPage.Status.EMPTY,
                SITE.searchResults(page("search-none.html", S + "/search?q=zzqxjvw"), 1).status);
    }

    @Test
    public void bookPage() throws IOException {
        Document doc = page("book.html", BOOK);
        DescriptionPOJO d = SITE.description(doc, BOOK);
        assertEquals("Поиск предназначения, или двадцать седьмая теорема этики", d.getTitle());
        assertEquals(AUTHOR, d.getAutorUrl());
        assertEquals(READER, d.getArtistUrl());
        assertEquals(GENRE, d.getGenreUrl());
        assertEquals("17 ч. 4 мин.", d.getTime());
        assertEquals(22, SITE.recommendations(doc, BOOK).size());

        ArrayList<AudioPOJO> tracks = SITE.tracks(doc, BOOK);
        assertEquals(36, tracks.size());
        assertTrue(tracks.get(0).getUrl().startsWith("https://archive.org/download/16._20230213/01.%20"));
        assertTrue(tracks.get(35).getUrl().startsWith("https://archive.org/download/16._20230213/36.%20"));
    }

    @Test
    public void seriesRowAndSeriesListing() throws IOException {
        String book = S + "/book/ermak-7-voyna-igor-valeriev";
        DescriptionPOJO d = SITE.description(page("book-series.html", book), book);
        assertEquals("Ермак", d.getSeries());
        assertEquals(S + "/serie/ermak", d.getSeriesUrl());
        assertEquals(S + "/author/igor-valeriev", d.getAutorUrl());

        ProviderPage<BookPOJO> series = listing("series.html", S + "/serie/ermak");
        assertEquals(10, series.items.size());
        assertFalse(series.hasMore);
        assertEquals(S + "/book/ermak-1-nachalo-igor-valeriev", series.items.get(0).getUrl());

        String big = S + "/serie/warhammer-40000";
        ProviderPage<BookPOJO> paged = listing("series-paged.html", big);
        assertEquals(16, paged.items.size());
        assertTrue(paged.hasMore);
        assertEquals(big + "/p/2", SITE.pageUrl(big, 2));
    }

    @Test
    public void litresTrialIsARemovedBook() throws IOException {
        Document doc = page("book-trial.html", TRIAL);
        assertEquals("Марина Дяченко", SITE.description(doc, TRIAL).getAutor());
        assertThrows(BookRemovedException.class, () -> SITE.tracks(doc, TRIAL));
    }
}
