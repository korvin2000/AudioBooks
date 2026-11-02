package com.fanok.audiobooks.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LoadBookTest {

    @Test
    public void deepLinkDataIsAcceptedAndMovedToTheCurrentHost() {
        assertEquals("https://akniga.org/book-1", LoadBook.bookUrl(null, "https://akniga.org/book-1"));
        assertEquals("https://baza-knig.top/x.html", LoadBook.bookUrl(null, "https://baza-knig.ink/x.html"));
        assertEquals("https://izib.uk/b/1", LoadBook.bookUrl(null, "http://izibuk.ru/b/1"));
    }

    @Test
    public void explicitExtraWinsAndUnknownHostsAreRejected() {
        assertEquals("https://knigavuhe.org/book/x/",
                LoadBook.bookUrl("https://knigavuhe.org/book/x/", "https://akniga.org/y"));
        assertNull(LoadBook.bookUrl(null, "https://example.com/book"));
        assertNull(LoadBook.bookUrl(null, null));
        assertNull(LoadBook.bookUrl("not a url", null));
    }

    @Test
    public void externalLinkLosesItsFragmentAndBecomesHttpsButTheExtraStaysAsItIs() {
        assertEquals("https://akniga.org/book-1", LoadBook.bookUrl(null, "http://akniga.org/book-1#comments"));
        assertEquals("https://akniga.org/b#x", LoadBook.bookUrl("https://akniga.org/b#x", null));
    }

    @Test
    public void theFirstSiteLinkOfASharedTextIsFound() {
        assertEquals("https://akniga.org/book-1",
                LoadBook.linkIn("Listen: https://akniga.org/book-1, you will like it"));
        assertEquals("https://knigavuhe.org/book/x/",
                LoadBook.linkIn("https://t.me/c/1 and https://knigavuhe.org/book/x/"));
        assertEquals("https://akniga.org/book-1", LoadBook.linkIn("akniga.org/book-1"));
        assertEquals("https://izib.uk/b/1", LoadBook.bookUrlIn("«http://izibuk.ru/b/1»"));
    }

    @Test
    public void textWithoutASiteLinkHasNone() {
        assertNull(LoadBook.linkIn("просто текст"));
        assertNull(LoadBook.linkIn("https://example.com/b"));
        assertNull(LoadBook.linkIn(null));
        assertNull(LoadBook.bookUrlIn("  "));
    }

    @Test
    public void collectionOpensAsListing() {
        assertTrue(LoadBook.opensAsListing("https://akniga.org/collection/letnie-priklyucheniya/"));
        assertFalse(LoadBook.opensAsListing("https://akniga.org/chernyy-roman-moy-plot"));
        assertFalse(LoadBook.opensAsListing("https://example.com/collection/x/"));
    }
}
