package com.fanok.audiobooks.model;

import static org.junit.Assert.assertEquals;

import com.fanok.audiobooks.pojo.AudioListPOJO;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** The cases of the card's ring as the adapter computed it, plus the two fixed ones (unknown or null chapter). */
public class ListeningProgressTest {

    private static AudioListPOJO chapter(String name, int seconds) {
        AudioListPOJO chapter = new AudioListPOJO();
        chapter.setAudioName(name);
        chapter.setTime(seconds);
        return chapter;
    }

    private static List<AudioListPOJO> book() {
        return Arrays.asList(chapter("Глава 1", 100), chapter("Глава 2", 200), chapter("Глава 3", 300));
    }

    @Test
    public void noChaptersHidesTheRing() {
        assertEquals(-1, ListeningProgress.percent(new ArrayList<>(), "Глава 1", 10));
    }

    @Test
    public void noLastChapterIsNothingListened() {
        assertEquals(0, ListeningProgress.percent(book(), null, 0));
        assertEquals(0, ListeningProgress.percent(book(), "", 50));
    }

    @Test
    public void countsTheChaptersBeforeAndTheSecondsInTheLast() {
        assertEquals(0, ListeningProgress.percent(book(), "Глава 1", 0));
        assertEquals(25, ListeningProgress.percent(book(), "Глава 2", 50));
        assertEquals(50, ListeningProgress.percent(book(), "Глава 3", 0));
        assertEquals(100, ListeningProgress.percent(book(), "Глава 3", 300));
    }

    @Test
    public void percentIsRoundedDown() {
        assertEquals(66, ListeningProgress.percent(Collections.singletonList(chapter("Глава 1", 3)), "Глава 1", 2));
    }

    @Test
    public void chaptersWithoutLengthAreZero() {
        assertEquals(0, ListeningProgress.percent(Arrays.asList(chapter("Глава 1", 0), chapter("Глава 2", 0)),
                "Глава 2", 40));
    }

    @Test
    public void bookWithoutSiteLengthsIsFinishedOnceThePlayerRecordedThem() {
        // the bug: a site without chapter lengths left every row at 0, so a book played to its end stayed at 0%;
        // the player now records each chapter's length as it plays it
        List<AudioListPOJO> recorded = Arrays.asList(chapter("01", 1800), chapter("02", 2100), chapter("03", 1500));
        int percent = ListeningProgress.percent(recorded, "03", 1500);
        assertEquals(100, percent);
        assertEquals(HistoryFilter.STATUS_FINISHED, HistoryFilter.statusOf(percent));
    }

    @Test
    public void unknownLengthsCountAsTheKnownAverage() {
        // only the first chapter played so far: the old code counted the second's start as the whole book (100%)
        List<AudioListPOJO> partly = Arrays.asList(chapter("Глава 1", 100), chapter("Глава 2", 0),
                chapter("Глава 3", 0), chapter("Глава 4", 0));
        assertEquals(25, ListeningProgress.percent(partly, "Глава 2", 0));
        assertEquals(HistoryFilter.STATUS_LISTENING,
                HistoryFilter.statusOf(ListeningProgress.percent(partly, "Глава 2", 0)));
    }

    @Test
    public void chapterBeingListenedIsAtLeastItsSeconds() {
        List<AudioListPOJO> chapters = Arrays.asList(chapter("Глава 1", 100), chapter("Глава 2", 0),
                chapter("Глава 3", 100));
        assertEquals(71, ListeningProgress.percent(chapters, "Глава 2", 150));
    }

    @Test
    public void lastMinuteOfTheBookCountsAsFinished() {
        // the sites' lengths are a second longer than where the player ends: 99% was never "listened"
        List<AudioListPOJO> site = Arrays.asList(chapter("Глава 1", 1801), chapter("Глава 2", 1801));
        assertEquals(100, ListeningProgress.percent(site, "Глава 2", 1800));
        List<AudioListPOJO> tenHours = Arrays.asList(chapter("Глава 1", 18000), chapter("Глава 2", 18000));
        assertEquals(100, ListeningProgress.percent(tenHours, "Глава 2", 18000 - ListeningProgress.END_SLACK_SECONDS));
        assertEquals(99, ListeningProgress.percent(tenHours, "Глава 2",
                18000 - ListeningProgress.END_SLACK_SECONDS - 1));
    }

    @Test
    public void startOfAShortLastChapterIsNotFinished() {
        assertEquals(50, ListeningProgress.percent(Arrays.asList(chapter("Глава 1", 10), chapter("Глава 2", 10)),
                "Глава 2", 0));
    }

    @Test
    public void unknownLastChapterIsNotFinished() {
        // the site renamed the chapters: the old code counted every chapter and showed 100%
        assertEquals(-1, ListeningProgress.percent(book(), "Часть 7", 30));
    }

    @Test
    public void chapterWithoutNameIsSkipped() {
        AudioListPOJO unnamed = new AudioListPOJO() {
            @Override
            public String getAudioName() {
                return null;
            }
        };
        unnamed.setTime(100);
        assertEquals(50, ListeningProgress.percent(Arrays.asList(unnamed, chapter("Глава 2", 100)), "Глава 2", 0));
    }
}
