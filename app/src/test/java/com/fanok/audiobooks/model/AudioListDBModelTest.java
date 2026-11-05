package com.fanok.audiobooks.model;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.fanok.audiobooks.room.BooksAudioEntity;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** Which row gets the chapter length the player found. */
public class AudioListDBModelTest {

    private static BooksAudioEntity row(int ordinal, String name) {
        BooksAudioEntity row = new BooksAudioEntity();
        row.ordinal = ordinal;
        row.nameAudio = name;
        return row;
    }

    @Test
    public void rowIsFoundByOrdinalAndName() {
        // a row kept only for its exported copy sorts first (negative ordinal) and shifts the positions
        BooksAudioEntity retained = row(-8, "Старая глава");
        BooksAudioEntity first = row(0, "Глава 1");
        BooksAudioEntity second = row(1, "Глава 2");
        List<BooksAudioEntity> rows = Arrays.asList(retained, first, second);
        assertSame(second, AudioListDBModel.chapterRow(rows, 1, "Глава 2"));
        assertSame(first, AudioListDBModel.chapterRow(rows, 0, "Глава 1"));
    }

    @Test
    public void rowWithoutOrdinalIsFoundByPosition() {
        // imported books record no ordinal (-1)
        BooksAudioEntity first = row(-1, "01.mp3");
        BooksAudioEntity second = row(-1, "02.mp3");
        assertSame(second, AudioListDBModel.chapterRow(Arrays.asList(first, second), 1, "02.mp3"));
    }

    @Test
    public void anotherChapterGetsNothing() {
        List<BooksAudioEntity> rows = Arrays.asList(row(0, "Глава 1"), row(1, "Глава 2"));
        assertNull(AudioListDBModel.chapterRow(rows, 1, "Глава 5"));
        assertNull(AudioListDBModel.chapterRow(rows, 7, "Глава 2"));
    }
}
