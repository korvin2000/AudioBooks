package com.fanok.audiobooks.source;

import static org.junit.Assert.assertEquals;

import com.fanok.audiobooks.Url;
import com.fanok.audiobooks.pojo.BookPOJO;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.Test;

/** knigavuhe.org author listing and book page, markup as served live (2026-11-10). */
public class KnigavuheParsersTest {

    /** The cards of an author's page link the author's listing, not the page asked ({@code …/1/1/} is a 404). */
    @Test
    public void authorPageCardsLinkTheAuthorWithoutThePageNumber() {
        Document doc = Jsoup.parse("<div class='page_title'><a href='/authors/'>Все авторы</a> <h1>Стивен Кинг</h1></div>"
                + "<div id='books_list'><div class='bookkitem'><div class='bookkitem_name'>"
                + "<a href='/book/1/'>One</a></div></div></div>", Url.SERVER);
        String author = Url.SERVER + "/author/stiven-king/";
        BookPOJO book = Knigavuhe.parseListing(doc, author + "3/", 3).items.get(0);
        assertEquals("Стивен Кинг", book.getAutor());
        assertEquals(author, book.getUrlAutor());
        String digits = Url.SERVER + "/author/agent-007/";
        assertEquals(digits, Knigavuhe.parseListing(doc, digits + "1/", 1).items.get(0).getUrlAutor());
    }

    /** As the cards and the description: a genre that pages, and the readers of a book read by several. */
    @Test
    public void bookOpenedByLinkKeepsAPagedGenreAndItsReaders() {
        Document doc = Jsoup.parse("<div class='book_genre_pretitle'><a href='/genre/detektivy-trillery/'>Детективы</a>"
                + "</div><div class='book_title_elem book_title_name'>Книга</div>"
                + "<div class='book_title_elem'>читают <a href='/reader/odin/'>Один</a>, <a href='/reader/dva/'>Два</a>"
                + "</div>", Url.SERVER);
        BookPOJO book = Knigavuhe.parseBook(doc, Url.SERVER + "/book/kniga/");
        assertEquals(Url.SERVER + "/genre/detektivy-trillery/<page>/", book.getUrlGenre());
        assertEquals(Url.SERVER + "/genre/detektivy-trillery/1/",
                new Knigavuhe().pageUrl(book.getUrlGenre(), 1));
        assertEquals("Один", book.getArtist());
        assertEquals(Url.SERVER + "/reader/odin/", book.getUrlArtist());
    }
}
