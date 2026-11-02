package com.fanok.audiobooks.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fanok.audiobooks.room.FavoritePersonEntity;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class FavoritePeopleTest {

    @Test
    public void keyIgnoresWordOrderCaseAndYo() {
        assertNotNull(FavoritePeople.key("Стивен Кинг"));
        assertEquals(FavoritePeople.key("Стивен Кинг"), FavoritePeople.key("Кинг Стивен"));
        assertEquals(FavoritePeople.key("Стивен Кинг"), FavoritePeople.key("  КИНГ,  стивен "));
        assertEquals(FavoritePeople.key("Пётр Алёшин"), FavoritePeople.key("Петр Алешин"));
    }

    @Test
    public void keyKeepsInitialsAndWholeNames() {
        assertNotEquals(FavoritePeople.key("А. Толстой"), FavoritePeople.key("Л. Толстой"));
        assertNotEquals(FavoritePeople.key("Толстой"), FavoritePeople.key("А. Толстой"));
        assertNotEquals(FavoritePeople.key("Булгаков"), FavoritePeople.key("Михаил Булгаков"));
        assertNotEquals(FavoritePeople.key("Котов"), FavoritePeople.key("Котова"));
        assertEquals(FavoritePeople.key("Л.Н. Толстой"), FavoritePeople.key("Толстой Л. Н."));
    }

    @Test
    public void keyDropsPatronymicAndKeepsHyphenatedSurname() {
        assertEquals(FavoritePeople.key("Иван Иванов"), FavoritePeople.key("Иванов Иван Иванович"));
        assertEquals(FavoritePeople.key("Василий Немирович-Данченко"),
                FavoritePeople.key("Немирович-Данченко Василий Иванович"));
        assertNotEquals(FavoritePeople.key("Салтыков-Щедрин"), FavoritePeople.key("Салтыков"));
    }

    @Test
    public void keyIsNullWithoutLetters() {
        assertNull(FavoritePeople.key(null));
        assertNull(FavoritePeople.key(""));
        assertNull(FavoritePeople.key("   "));
        assertNull(FavoritePeople.key(" , — "));
    }

    @Test
    public void splitKeepsSpellingAndOrder() {
        assertEquals(Arrays.asList("Иванов", "Петров"), FavoritePeople.split("Иванов, Петров и др."));
        assertEquals(Arrays.asList("Иванов И.И.", "Петров", "Сидоров", "Кузнецов", "Смирнов"),
                FavoritePeople.split(" Иванов И.И. / Петров & Сидоров; Кузнецов и Смирнов "));
        assertEquals(Arrays.asList("Ёлкин Пётр", "Немирович-Данченко"),
                FavoritePeople.split("Ёлкин Пётр,Немирович-Данченко"));
        assertEquals(Collections.singletonList("Стивен Кинг"), FavoritePeople.split("Стивен Кинг"));
    }

    @Test
    public void splitDropsEmptiesAndRepeats() {
        assertTrue(FavoritePeople.split(null).isEmpty());
        assertTrue(FavoritePeople.split("").isEmpty());
        assertTrue(FavoritePeople.split("  ").isEmpty());
        assertEquals(Arrays.asList("Иванов", "Петров"), FavoritePeople.split("Иванов,, ; Петров, —"));
        assertEquals(Collections.singletonList("Стивен Кинг"), FavoritePeople.split("Стивен Кинг, Кинг Стивен"));
    }

    private static FavoritePersonEntity row(int id, String name, String key, String urls, long addedAt) {
        FavoritePersonEntity e = new FavoritePersonEntity();
        e.id = id;
        e.kind = FavoritePeople.AUTHOR;
        e.name = name;
        e.personKey = key;
        e.urls = urls;
        e.addedAt = addedAt;
        return e;
    }

    @Test
    public void rekeyHealsStaleKeysAndMergesOnePersonsRowsIntoTheOldest() {
        String king = FavoritePeople.key("Стивен Кинг");
        List<FavoritePersonEntity> rows = Arrays.asList( // newest first, as getAll
                row(4, "Кинг Стивен", "[кинг, стивен, эдвин]|[]", "https://izib.uk/author123", 400),
                row(3, "Петров", FavoritePeople.key("Петров"), "", 300),
                row(2, "Иван Сидоров", "[иван, иванович, сидоров]|[]", "", 200),
                row(1, "Стивен Кинг", king, "https://akniga.org/author/king/\nhttps://izib.uk/other", 100));
        List<Integer> replaced = new ArrayList<>();
        List<FavoritePersonEntity> healed = FavoritePeople.rekey(rows, replaced);
        assertEquals(Arrays.asList(1, 4, 2), replaced);
        assertEquals(2, healed.size());

        FavoritePersonEntity merged = healed.get(0);
        assertEquals(1, merged.id);
        assertEquals("Стивен Кинг", merged.name);
        assertEquals(king, merged.personKey);
        assertEquals(100, merged.addedAt);
        assertEquals("the oldest's page wins per site", "https://akniga.org/author/king/\nhttps://izib.uk/other",
                merged.urls);

        FavoritePersonEntity rekeyed = healed.get(1);
        assertEquals(2, rekeyed.id);
        assertEquals(FavoritePeople.key("Иван Сидоров"), rekeyed.personKey);
        assertEquals(200, rekeyed.addedAt);

        List<Integer> none = new ArrayList<>();
        assertTrue("rows of today's keys stay", FavoritePeople.rekey(Collections.singletonList(
                row(3, "Петров", FavoritePeople.key("Петров"), "", 300)), none).isEmpty());
        assertTrue(none.isEmpty());
    }
}
