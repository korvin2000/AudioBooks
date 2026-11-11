package com.fanok.audiobooks.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ChapterTextTest {

    private static final int D = ChapterText.DIGIT;
    private static final int S = ChapterText.SYMBOL;

    /** Three chapters: 10 min, 20 min, 30 min = 1 h. */
    private static final int[] BOOK = {600, 1200, 1800};

    @Test
    public void anUnderscoreReadsAsASpace() {
        assertEquals("01 Na beregu", ChapterText.shown("01_Na_beregu"));
        assertEquals("Глава 1", ChapterText.shown("Глава 1"));
        assertEquals("", ChapterText.shown(null));
    }

    @Test
    public void digitsAndSymbolsAreRunsLettersAndSpacesAreNot() {
        assertArrayEquals(new int[]{0, 2, D}, ChapterText.runs("01 Na beregu"));
        assertArrayEquals(new int[0], ChapterText.runs("На берегу"));
        assertArrayEquals(new int[0], ChapterText.runs(""));
        //"05-22": digits, a symbol, digits
        assertArrayEquals(new int[]{0, 2, D, 2, 3, S, 3, 5, D}, ChapterText.runs("05-22"));
        //neighbouring symbols are one run; a space ends a run
        assertArrayEquals(new int[]{6, 7, D, 7, 8, S, 9, 10, S, 15, 17, S}, ChapterText.runs("Часть 1. «Берег»."));
    }

    @Test
    public void manyRunsAndACombiningMark() {
        assertEquals(3 * 9, ChapterText.runs("1.2.3.4.5").length);
        //"й" as "и" + U+0306 stays a letter
        assertArrayEquals(new int[0], ChapterText.runs("й"));
    }

    @Test
    public void aLengthHasHoursOnlyWhenTheChapterDoes() {
        assertEquals("10:09", ChapterText.duration(609));
        assertEquals("00:05", ChapterText.duration(5));
        assertEquals("59:59", ChapterText.duration(3599));
        assertEquals("1:02:45", ChapterText.duration(3765));
        assertEquals("12:00:00", ChapterText.duration(43200));
        assertEquals("00:00", ChapterText.duration(-3));
    }

    @Test
    public void theSubtitleSaysTheChapterAndWhatIsLeft() {
        RemainingTime time = new RemainingTime(BOOK);
        long total = time.total();
        assertEquals("Глава 2 из 3 · осталось 45 мин.", ChapterText.subtitle(time.at(1, 300_000, 1f), total));
        assertEquals("Глава 2 из 3 · осталось 30 мин.", ChapterText.subtitle(time.at(1, 300_000, 1.5f), total));
        assertEquals("Глава 3 из 3 · прослушано", ChapterText.subtitle(time.at(2, 1_790_000, 1f), total));
        assertEquals("3 главы · 1 ч.", ChapterText.subtitle(time.at(-1, 0, 1f), total));
    }

    @Test
    public void theSubtitleWithoutAChaptersLengthOrWithoutChapters() {
        RemainingTime unknown = new RemainingTime(new int[]{600, 0, 1800});
        assertEquals("Глава 2 из 3", ChapterText.subtitle(unknown.at(1, 5_000, 1f), unknown.total()));
        assertEquals("3 главы", ChapterText.subtitle(unknown.at(-1, 0, 1f), unknown.total()));
        RemainingTime empty = new RemainingTime(new int[0]);
        assertEquals("", ChapterText.subtitle(empty.at(2, 5_000, 1f), empty.total()));
    }

    @Test
    public void theBooksProgressIsSaidOnceItIsStarted() {
        RemainingTime time = new RemainingTime(BOOK);
        assertEquals("Прослушано 25 % из 1 ч.", ChapterText.progress(time.at(1, 300_000, 2f), time.total()));
        assertEquals("Прослушано", ChapterText.progress(time.at(2, 1_790_000, 1f), time.total()));
        assertEquals("", ChapterText.progress(time.at(-1, 0, 1f), time.total()));
        RemainingTime unknown = new RemainingTime(new int[]{600, 0, 1800});
        assertEquals("Прослушано 33 %", ChapterText.progress(unknown.at(1, 5_000, 1f), unknown.total()));
    }

    @Test
    public void chaptersAreCountedInRussian() {
        assertEquals("1 глава", ChapterText.chapters(1));
        assertEquals("3 главы", ChapterText.chapters(3));
        assertEquals("16 глав", ChapterText.chapters(16));
        assertEquals("11 глав", ChapterText.chapters(11));
        assertEquals("21 глава", ChapterText.chapters(21));
        assertEquals("112 глав", ChapterText.chapters(112));
        assertEquals("300 глав", ChapterText.chapters(300));
    }
}
