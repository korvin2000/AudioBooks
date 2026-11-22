package com.fanok.audiobooks.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.fanok.audiobooks.room.BookmarkEntity;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class BookmarkRulesTest {

    private static BookmarkEntity mark(int ordinal, String name, long ms) {
        return BookmarkRules.create("http://book", ordinal, name, ms, 1L);
    }

    private static int resolve(List<String> names, int[] ordinals, BookmarkEntity mark) {
        return BookmarkRules.resolve(names, ordinals, mark.chapterIndex, mark.chapterName);
    }

    @Test
    public void resolveKeepsThePlaceWhenTheNameIsStillThere() {
        List<String> names = Arrays.asList("Глава 1", "Глава 2", "Глава 3");
        assertEquals(1, resolve(names, null, mark(1, "Глава 2", 5000)));
    }

    @Test
    public void resolveFollowsTheNameAfterTheSiteChangedItsList() {
        List<String> names = Arrays.asList("Пролог", "Глава 1", "Глава 2");
        assertEquals(2, resolve(names, null, mark(1, "Глава 2", 5000)));
    }

    @Test
    public void resolveNeverGuessesByTheIndexAlone() {
        List<String> names = Arrays.asList("Другая 1", "Другая 2");
        assertEquals(-1, resolve(names, null, mark(1, "Глава 2", 5000)));
    }

    @Test
    public void resolvePrefersTheMarkedPlaceAmongEqualNames() {
        List<String> names = Arrays.asList("01", "01", "01");
        assertEquals(2, resolve(names, null, mark(2, "01", 0)));
    }

    @Test
    public void resolveDoesNotPickAmongEqualNamesElsewhere() {
        // parts that each number their chapters from 01: the marked one moved, which "01" it is cannot be told
        List<String> names = Arrays.asList("01", "02", "01", "02");
        assertEquals(-1, resolve(names, null, mark(5, "01", 0)));
    }

    @Test
    public void resolveOutOfRangeFindsTheOneChapterOfThatName() {
        List<String> names = Arrays.asList("A", "B");
        assertEquals(1, resolve(names, null, mark(9, "B", 0)));
    }

    @Test
    public void resolveWithoutANameOnlyAtTheMarkedPlace() {
        assertEquals(1, resolve(Arrays.asList("", ""), null, mark(1, "", 0)));
        assertEquals(-1, resolve(Arrays.asList("", "x"), null, mark(1, "", 0)));
    }

    @Test
    public void markMadeOnlineIsFoundInTheDownloadedChapters() {
        // the full list 0..4; chapters 1, 3 and 4 are downloaded
        List<String> offline = Arrays.asList("Глава 2", "Глава 4", "Глава 5");
        int[] ordinals = {1, 3, 4};
        assertEquals(1, resolve(offline, ordinals, mark(3, "Глава 4", 7000)));
    }

    @Test
    public void aChapterNotDownloadedIsNotTheDownloadedOneOfItsName() {
        // the full list has two "Вступление" (0 and 3); only the second is downloaded, the mark is in the first
        List<String> offline = Arrays.asList("Вступление", "Глава 5");
        int[] ordinals = {3, 4};
        assertEquals(-1, resolve(offline, ordinals, mark(0, "Вступление", 7000)));
    }

    @Test
    public void markMadeOfflineIsFoundInTheFullList() {
        // made in the downloaded list above, at its second chapter: stored with that chapter's full-list index
        BookmarkEntity mark = mark(3, "Глава 4", 7000);
        List<String> online = Arrays.asList("Глава 1", "Глава 2", "Глава 3", "Глава 4", "Глава 5");
        assertEquals(3, resolve(online, null, mark));
    }

    @Test
    public void chapterNotDownloadedIsNotFound() {
        List<String> offline = Arrays.asList("Глава 2", "Глава 4");
        assertEquals(-1, resolve(offline, new int[]{1, 3}, mark(2, "Глава 3", 0)));
    }

    @Test
    public void downloadedChapterOfTheSameIndexButAnotherNameIsNotIt() {
        List<String> offline = Arrays.asList("Глава 2", "Вставка");
        assertEquals(-1, resolve(offline, new int[]{1, 3}, mark(3, "Глава 4", 0)));
    }

    @Test
    public void noteIsTrimmedAndEmptyIsNone() {
        assertNull(BookmarkRules.cleanNote(null));
        assertNull(BookmarkRules.cleanNote("   "));
        assertEquals("слово", BookmarkRules.cleanNote("  слово \n"));
    }

    @Test
    public void noteIsCut() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < BookmarkRules.MAX_NOTE + 50; i++) {
            text.append('x');
        }
        assertEquals(BookmarkRules.MAX_NOTE, BookmarkRules.cleanNote(text.toString()).length());
    }

    @Test
    public void createClampsNegativeValues() {
        BookmarkEntity m = BookmarkRules.create("u", -3, null, -5, 7L);
        assertEquals(0, m.chapterIndex);
        assertEquals(0, m.positionMs);
        assertEquals("", m.chapterName);
        assertNull(m.note);
        assertEquals(7L, m.createdAt);
    }

    @Test
    public void findSameIsTheSameChapterAFewSecondsApart() {
        BookmarkEntity here = mark(1, "Глава", 10_000);
        List<BookmarkEntity> all = Arrays.asList(mark(1, "Глава", 60_000), here);
        assertSame(here, BookmarkRules.findSame(all, mark(1, "Глава", 10_000)));
        assertSame(here, BookmarkRules.findSame(all, mark(1, "Глава", 10_000 + BookmarkRules.SAME_PLACE_MS - 1)));
        assertNull(BookmarkRules.findSame(all, mark(1, "Глава", 10_000 + BookmarkRules.SAME_PLACE_MS)));
        assertNull(BookmarkRules.findSame(all, mark(2, "Глава", 10_000)));
        assertNull(BookmarkRules.findSame(all, mark(1, "Другая", 10_000)));
        assertNull(BookmarkRules.findSame(all, BookmarkRules.create("other", 1, "Глава", 10_000, 1L)));
        assertNull(BookmarkRules.findSame(Collections.<BookmarkEntity>emptyList(), here));
    }
}
