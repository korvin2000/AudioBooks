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
