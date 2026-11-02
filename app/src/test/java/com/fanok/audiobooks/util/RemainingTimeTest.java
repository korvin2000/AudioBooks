package com.fanok.audiobooks.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RemainingTimeTest {

    /** Three chapters: 10 min, 20 min, 30 min = 1 h. */
    private static final int[] BOOK = {600, 1200, 1800};

    @Test
    public void notStartedShowsTheTotal() {
        RemainingTime time = new RemainingTime(BOOK);
        assertEquals(3600, time.total());
        assertEquals(RemainingTime.TOTAL, time.at(-1, 0, 1f).state);
        assertEquals(RemainingTime.TOTAL, time.at(0, 0, 1f).state);
        assertEquals(RemainingTime.TOTAL, time.at(0, RemainingTime.START_MS - 1, 1f).state);
        assertEquals(RemainingTime.TOTAL, new RemainingTime(new int[0]).at(2, 5_000, 1f).state);
    }

    @Test
    public void leftCountsTheChaptersBehindAndThePositionAtTheSpeed() {
        RemainingTime time = new RemainingTime(BOOK);
        RemainingTime.Line line = time.at(1, 300_500, 1f); //600 + 300 behind
        assertEquals(RemainingTime.LEFT, line.state);
        assertEquals(2700, line.left);
        assertEquals(250, line.permille);
        assertEquals(2, line.chapter);
        assertEquals(3, line.chapters);
        assertEquals(1800, time.at(1, 300_000, 1.5f).left);
        assertEquals(1350, time.at(1, 300_000, 2f).left);
    }

    @Test
    public void aPositionPastItsChapterIsTheChapterEnd() {
        assertEquals(1800, new RemainingTime(BOOK).at(1, 5_000_000, 1f).left);
    }

    @Test
    public void aChapterPastTheEndIsTheLastFromItsBeginning() {
        assertEquals(1800, new RemainingTime(BOOK).at(7, 100_000, 1f).left);
    }

    @Test
    public void lessThanAMinuteLeftIsListened() {
        RemainingTime time = new RemainingTime(BOOK);
        RemainingTime.Line done = time.at(2, (1800 - RemainingTime.DONE_SEC + 1) * 1000, 1f);
        assertEquals(RemainingTime.DONE, done.state);
        assertEquals(1000, done.permille);
        assertEquals(RemainingTime.LEFT, time.at(2, (1800 - RemainingTime.DONE_SEC) * 1000, 1f).state);
    }

    @Test
    public void anUnknownChapterLengthGivesTheChapterNotARemainder() {
        RemainingTime time = new RemainingTime(new int[]{600, 0, 1800, 600});
        assertEquals(0, time.total());
        RemainingTime.Line line = time.at(2, 60_000, 1.5f);
        assertEquals(RemainingTime.CHAPTER, line.state);
        assertEquals(3, line.chapter);
        assertEquals(4, line.chapters);
        assertEquals(500, line.permille);
    }

    @Test
    public void formatWritesHoursAndMinutesRoundedUp() {
        assertEquals("0 мин.", RemainingTime.format(0));
        assertEquals("1 мин.", RemainingTime.format(1));
        assertEquals("1 мин.", RemainingTime.format(60));
        assertEquals("2 мин.", RemainingTime.format(61));
        assertEquals("1 ч.", RemainingTime.format(3600));
        assertEquals("1 ч. 1 мин.", RemainingTime.format(3601));
        assertEquals("5 ч. 12 мин.", RemainingTime.format(5 * 3600 + 12 * 60));
    }

    @Test
    public void speedIsWrittenWithACommaAndNoTrailingZeros() {
        assertEquals("1×", RemainingTime.speed(1f));
        assertEquals("2×", RemainingTime.speed(2f));
        assertEquals("1,5×", RemainingTime.speed(1.5f));
        assertEquals("1,1×", RemainingTime.speed(1.1f));
        assertEquals("0,75×", RemainingTime.speed(0.75f));
        assertEquals("1,25×", RemainingTime.speed(1.25f));
    }
}
