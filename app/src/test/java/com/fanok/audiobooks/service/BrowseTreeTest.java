package com.fanok.audiobooks.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class BrowseTreeTest {

    @Test
    public void rootHoldsTheListsAndTheCurrentBookFirstWhileThereIsOne() {
        assertEquals(Arrays.asList(BrowseTree.NODE_CURRENT, BrowseTree.NODE_HISTORY, BrowseTree.NODE_FAVORITE,
                BrowseTree.NODE_SAVED), BrowseTree.rootNodes(true));
        assertEquals(Arrays.asList(BrowseTree.NODE_HISTORY, BrowseTree.NODE_FAVORITE, BrowseTree.NODE_SAVED),
                BrowseTree.rootNodes(false));
    }

    @Test
    public void rootFitsTheTabsOfAndroidAuto() {
        assertTrue(BrowseTree.rootNodes(true).size() <= 4);
    }

    @Test
    public void aNodeIsNeverPlayedAsABookNorAsAChapter() {
        //playFromMediaId takes a number as a chapter and a web URL as a book: a node's id must be neither
        List<String> nodes = BrowseTree.rootNodes(true);
        for (String node : nodes) {
            assertFalse(node, BrowseTree.isBook(node));
            assertFalse(node, node.matches("\\d+"));
            assertFalse(node, MediaPlayerService.MY_MEDIA_ROOT_ID.equals(node));
        }
    }

    @Test
    public void aBookIsItsWebUrl() {
        assertTrue(BrowseTree.isBook("https://akniga.org/book?x=1&y=2"));
        assertTrue(BrowseTree.isBook("http://example.com/b"));
        assertFalse(BrowseTree.isBook(null));
        assertFalse(BrowseTree.isBook(""));
        assertFalse(BrowseTree.isBook("7")); // a chapter's number
        assertFalse(BrowseTree.isBook("file:///sdcard/x"));
        assertFalse(BrowseTree.isBook(MediaPlayerService.MY_MEDIA_ROOT_ID));
    }

    @Test
    public void aShortBookIsListedWhole() {
        assertEquals(0, BrowseTree.firstListedChapter(10, 0));
        assertEquals(0, BrowseTree.firstListedChapter(10, 9));
        assertEquals(0, BrowseTree.firstListedChapter(BrowseTree.MAX_ITEMS, BrowseTree.MAX_ITEMS - 1));
    }

    @Test
    public void aLongBookIsListedFromTheChapterBeforeThePlayingOne() {
        int count = BrowseTree.MAX_ITEMS * 4;
        assertEquals(0, BrowseTree.firstListedChapter(count, 0));
        assertEquals(0, BrowseTree.firstListedChapter(count, 1));
        assertEquals(69, BrowseTree.firstListedChapter(count, 70));
        //near the end the window keeps its full length and still holds the playing chapter
        assertEquals(count - BrowseTree.MAX_ITEMS, BrowseTree.firstListedChapter(count, count - 1));
        int first = BrowseTree.firstListedChapter(count, count - 10);
        assertTrue(first <= count - 10 && count - 10 < first + BrowseTree.MAX_ITEMS);
    }

    @Test
    public void aSubtitleIsTheAuthorAndTheReader() {
        assertEquals("Булгаков — Клюквин", BrowseTree.subtitle("Булгаков", "Клюквин"));
        assertEquals("Булгаков", BrowseTree.subtitle(" Булгаков ", ""));
        assertEquals("Клюквин", BrowseTree.subtitle(null, "Клюквин"));
        assertNull(BrowseTree.subtitle(" ", null));
    }
}
