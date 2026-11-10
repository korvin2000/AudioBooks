package com.fanok.audiobooks.source;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

import com.fanok.audiobooks.BookRemovedException;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;

/**
 * A book the site only sells through LitRes plays a one-item {@code litres.ru/audiotrial} playlist: it is reported as
 * taken down, as on golosom, not played as the book (pages saved with «Ночной Дозор»).
 */
public class LitresTrialTest {

    private static Document fixture(String name, String baseUri) throws IOException {
        try (InputStream in = LitresTrialTest.class.getResourceAsStream("/fixtures/" + name)) {
            assertNotNull("missing fixture " + name, in);
            return Jsoup.parse(in, "UTF-8", baseUri);
        }
    }

    @Test
    public void knigobludTrialIsARemovedBook() throws IOException {
        String url = "https://www.knigoblud.club/nochnoy-dozor";
        Document page = fixture("knigoblud/series.html", url);
        assertThrows(BookRemovedException.class, () -> Knigoblud.parseTracks(page, url));
    }

    @Test
    public void otrubTrialIsARemovedBook() throws IOException {
        String url = "https://otrub.in/nochnojj-dozor-2.html";
        Document page = fixture("otrub/series-book.html", url);
        assertThrows(BookRemovedException.class, () -> Otrub.parseTracks(page, url));
    }

    @Test
    public void izibukTrialIsARemovedBook() throws IOException {
        String url = "https://izib.uk/art1439";
        Document page = fixture("izibuk/series.html", url);
        assertThrows(BookRemovedException.class, () -> Izibuk.parseTracks(page, url));
        String several = "https://izib.uk/art20481";
        Document readers = fixture("izibuk/book-several-readers.html", several);
        assertThrows(BookRemovedException.class, () -> Izibuk.parseTracks(readers, several));
    }
}
