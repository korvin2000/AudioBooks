package com.fanok.audiobooks.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import com.fanok.audiobooks.Url;
import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.search.ProviderPage;

import org.jsoup.Jsoup;
import org.junit.Test;

/** knigoblud.club listing cards, markup as served live (2026-11-10). */
public class KnigobludListingTest {

    private static String card(String meta) {
        return "<div class=\"bookListItem\"><a class=\"bookListItemCover\" href=\"/b1\">"
                + "<span class=\"bookListItemCoverNameText\">Книга</span></a>"
                + "<div class=\"bookListItemMetaBlock\">" + meta + "</div></div>";
    }

    /** A series page lists its books in {@code .BookList}, without the {@code #BL} of the other listings. */
    @Test
    public void seriesPageWithoutBlListsItsBooks() {
        String html = "<div class=\"BookList\">" + card("") + card("") + "</div>";
        ProviderPage<BookPOJO> page = Knigoblud.parseListing(Jsoup.parse(html, Url.SERVER_KNIGOBLUD), 1);
        assertEquals(2, page.items.size());
        assertEquals(Url.SERVER_KNIGOBLUD + "/b1", page.items.get(0).getUrl());
        assertFalse("no pager: the last page", page.hasMore);
    }

    /** Two authors: the second ✍ link is not the reader (fixture search-1984-p1.html, «Евгения Шамис»). */
    @Test
    public void secondAuthorIsNotTheReader() {
        String meta = "<span>✍️</span> <a href=\"/a1\">Евгений Никонов</a>"
                + " <span>✍️</span> <a href=\"/a2\">Евгения Шамис</a>"
                + " <span>🎙️</span> <a href=\"/r1\">Максим Киреев</a>"
                + " <span>🎙️</span> <a href=\"/r2\">Другой чтец</a>";
        BookPOJO book = Knigoblud.parseListing(Jsoup.parse("<div id=\"BL\">" + card(meta) + "</div>",
                Url.SERVER_KNIGOBLUD), 1).items.get(0);
        assertEquals("Евгений Никонов", book.getAutor());
        assertEquals(Url.SERVER_KNIGOBLUD + "/a1", book.getUrlAutor());
        assertEquals("Максим Киреев", book.getArtist());
        assertEquals(Url.SERVER_KNIGOBLUD + "/r1", book.getUrlArtist());
    }

    @Test
    public void readerWithoutAuthorIsRead() {
        BookPOJO book = Knigoblud.parseListing(Jsoup.parse("<div id=\"BL\">"
                + card("<span>🎙️</span> <a href=\"/r1\">Максим Киреев</a>") + "</div>", Url.SERVER_KNIGOBLUD), 1)
                .items.get(0);
        assertEquals("Максим Киреев", book.getArtist());
        assertNull(book.getAutor());
    }
}
