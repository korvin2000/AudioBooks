package com.fanok.audiobooks.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fanok.audiobooks.Url;
import com.fanok.audiobooks.pojo.GenrePOJO;
import com.fanok.audiobooks.search.ProviderPage;
import com.fanok.audiobooks.search.SearchUrls;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;

/** The people indexes of otrub.in (izib.uk's engine) and poleknig.com's readers (uknig's engine). */
public class PeopleIndexesTest {

    private static Document page(String path, String url) throws IOException {
        try (InputStream in = PeopleIndexesTest.class.getResourceAsStream("/fixtures/" + path)) {
            return Jsoup.parse(in, "UTF-8", url);
        }
    }

    @Test
    public void otrubListsThePeopleWithBooks() throws IOException {
        Otrub site = new Otrub();
        String url = site.pageUrl(Url.AUTHORS_OTRUB, 2);
        assertEquals("https://otrub.in/authors.html?p=2", url);
        ProviderPage<GenrePOJO> people = site.people(page("otrub/authors-p2.html", url), url, 2, false);
        assertTrue(people.hasMore);
        assertEquals("90 rows, 40 of them «нет книг»", 50, people.items.size());
        assertEquals("Ван Цянь Джули", people.items.get(0).getName());
        assertEquals("https://otrub.in/authors/van-cjan-dzhuli.html", people.items.get(0).getUrl());
        assertEquals("1 книга", people.items.get(0).getDescription());
    }

    @Test
    public void otrubSearchesItsPeople() throws IOException {
        Otrub site = new Otrub();
        String url = site.searchUrl(SearchUrls.Category.PERFORMERS, "клюквин", 1);
        assertEquals("https://otrub.in/readers.html?q=%D0%BA%D0%BB%D1%8E%D0%BA%D0%B2%D0%B8%D0%BD&p=1", url);
        assertEquals("https://otrub.in/authors.html?q=a&p=2", site.searchUrl(SearchUrls.Category.AUTHORS, "a", 2));
        assertEquals("https://otrub.in/search?q=a&p=1", site.searchUrl(SearchUrls.Category.BOOKS, "a", 1));
        ProviderPage<GenrePOJO> found = site.searchPeople(page("otrub/readers-search.html", url), 1, true);
        assertFalse(found.hasMore);
        assertEquals(2, found.items.size());
        assertEquals("https://otrub.in/readers/kljukvin-aleksandr.html", found.items.get(0).getUrl());
        assertEquals("454 книги", found.items.get(0).getDescription());
    }

    @Test
    public void poleknigReadersAreUknigsIndex() throws IOException {
        Poleknig site = new Poleknig();
        assertEquals(Url.PERFORMERS_POLEKNIG, site.pageUrl(Url.PERFORMERS_POLEKNIG, 1));
        assertEquals("five site pages a list page", Url.PERFORMERS_POLEKNIG + "?p=6",
                site.pageUrl(Url.PERFORMERS_POLEKNIG, 2));
        assertEquals("other listings keep their scheme", Url.SERVER_POLEKNIG + "/genres/5?p=2",
                site.pageUrl(Url.SERVER_POLEKNIG + "/genres/5", 2));
        ProviderPage<GenrePOJO> readers = Uknig.parsePeople(page("poleknig/readers-p1.html", Url.PERFORMERS_POLEKNIG));
        assertTrue(readers.hasMore);
        assertEquals(10, readers.items.size());
        assertEquals("BooksAudio", readers.items.get(0).getName());
        String url = readers.items.get(0).getUrl();
        assertTrue(url, url.matches("https://poleknig\\.com/readers/\\d+"));
        assertEquals(1, readers.items.get(0).getReting());
    }
}
