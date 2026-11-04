package com.fanok.audiobooks.widgets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ExternalWidgetsTest {

    private static NowPlaying chapter(int index, int count, long positionMs, long durationMs) {
        NowPlaying shown = new NowPlaying();
        shown.chapterIndex = index;
        shown.chapterCount = count;
        shown.positionMs = positionMs;
        shown.durationMs = durationMs;
        shown.sampledAtMs = 1_000;
        return shown;
    }

    @Test
    public void bookPercentWeighsChaptersByLength() {
        //integration-level2-notes.txt §6: 600 s and 1200 s, 300 s into the second = 50% of the book
        NowPlaying shown = chapter(1, 2, 300_000, 1_200_000);
        shown.lengths(new int[]{600, 1200});
        assertEquals(50, shown.bookPercent(1_000));
        assertFalse(shown.approximate());
        //the speed runs the position on, not the share: 1.5× for 200 s plays 300 s, 1200 s of 1800
        shown.playing = true;
        shown.speed = 1.5f;
        assertEquals(66, shown.bookPercent(201_000));
    }

    @Test
    public void bookPercentWithoutTheWholeList() {
        NowPlaying shown = chapter(1, 4, 50_000, 100_000);
        shown.lengths(new int[]{60, 0, 60, 60});
        assertEquals(37, shown.bookPercent(1_000)); //by chapters: 1.5 of 4
        assertTrue(shown.approximate());
        shown.lengths(null); //an offline subset
        assertEquals(-1, shown.bookPercent(1_000));
        shown.lengths(new int[]{60, 60}); //not the session's list
        assertEquals(-1, shown.bookPercent(1_000));
    }

    @Test
    public void positionRunsOnWhilePlayingAndStopsWhenPaused() {
        NowPlaying shown = chapter(0, 1, 10_000, 30_000);
        shown.playing = true;
        shown.speed = 2f;
        assertEquals(20_000, shown.positionAt(6_000));
        assertEquals(30_000, shown.positionAt(60_000)); //not past the chapter's end
        NowPlaying stopped = shown.paused(6_000);
        assertFalse(stopped.playing);
        assertEquals(20_000, stopped.positionMs);
        assertEquals(6_000, stopped.sampledAtMs);
        assertTrue(shown.playing); //the published one is untouched
        //a kept one is stopped: a cold start only stamps it, it never runs on across the absence
        NowPlaying restored = stopped.paused(99_000);
        assertEquals(20_000, restored.positionMs);
        assertEquals(99_000, restored.sampledAtMs);
    }

    @Test
    public void titleIsTheAlbumAsTheLauncherTrimsIt() {
        assertEquals("Книга", NowPlaying.title("  Книга \n"));
        assertEquals("", NowPlaying.title(null));
    }

    @Test
    public void credits() {
        assertEquals("Автор · Чтец", NowPlaying.credits("Автор", "Чтец"));
        assertEquals("Автор", NowPlaying.credits("Автор", null));
        assertEquals("Чтец", NowPlaying.credits("", "Чтец"));
        assertEquals("Один", NowPlaying.credits("Один", "Один"));
        assertEquals(NowPlaying.TEXT_MAX, NowPlaying.credits(new String(new char[400]).replace('\0', 'a'), "b").length());
    }

    @Test
    public void sleepTimerCommandsAreMinutesTheChaptersEndOrOff() {
        assertTrue(ExternalWidgetsProvider.timerCommand("minutes", 15));
        assertTrue(ExternalWidgetsProvider.timerCommand("minutes", 1440));
        assertFalse(ExternalWidgetsProvider.timerCommand("minutes", 0));
        assertFalse(ExternalWidgetsProvider.timerCommand("minutes", 1441));
        assertTrue(ExternalWidgetsProvider.timerCommand("end_of_chapter", 0));
        assertTrue(ExternalWidgetsProvider.timerCommand("off", 0));
        assertFalse(ExternalWidgetsProvider.timerCommand("off", 5));
        assertFalse(ExternalWidgetsProvider.timerCommand("duration", 15));
        assertFalse(ExternalWidgetsProvider.timerCommand(null, 0));
    }

    @Test
    public void admitsFourRequestsInAnySecond() {
        long[] times = new long[ExternalWidgetsProvider.RATE];
        for (int i = 0; i < 4; i++) {
            assertTrue(ExternalWidgetsProvider.admit(times, 10_000 + i * 100));
        }
        assertFalse(ExternalWidgetsProvider.admit(times, 10_900));
        assertTrue(ExternalWidgetsProvider.admit(times, 11_000)); //the first one is a second old
        assertFalse(ExternalWidgetsProvider.admit(times, 11_050));
    }
}
