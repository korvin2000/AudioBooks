package com.fanok.audiobooks.search;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class TextNormTest {

    @Test
    public void keyFoldsCaseYoPunctuationAndSpaces() {
        assertEquals("мастер и маргарита", TextNorm.key("  «Мастер и — Маргарита»!  "));
        assertEquals(TextNorm.key("ВОЙНА И МИР"), TextNorm.key("война и мир"));
        assertEquals("семен", TextNorm.key("Семён"));
        assertEquals("й stays", "йога", TextNorm.key("Йога"));
        assertEquals("том 2", TextNorm.key("Том №2"));
        assertEquals("", TextNorm.key(null));
        assertEquals(Arrays.asList("салтыков", "щедрин"), TextNorm.tokens("Салтыков-Щедрин"));
    }

    @Test
    public void latinLookalikesInACyrillicWordBecomeCyrillic() {
        assertEquals("мастер", TextNorm.key("Мастeр")); // Latin e
        assertEquals("pascal stays Latin", "pascal", TextNorm.key("Pascal"));
    }

    @Test
    public void significantWords() {
        assertTrue(TextNorm.isSignificant("2"));
        assertTrue(TextNorm.isSignificant("мы"));
        assertFalse(TextNorm.isSignificant("и"));
        assertFalse(TextNorm.isSignificant("я"));
    }

    @Test
    public void editionLeavesTheTitleButVolumesStay() {
        assertArrayEquals(new String[]{"мастер и маргарита", "полная версия"},
                TextNorm.splitEdition("Мастер и Маргарита (полная версия)"));
        assertArrayEquals(new String[]{"пикник на обочине", "радиоспектакль"},
                TextNorm.splitEdition("Пикник на обочине (радиоспектакль)"));
        assertArrayEquals(new String[]{"собачье сердце", "спектакль"},
                TextNorm.splitEdition("Собачье сердце. Спектакль"));
        assertArrayEquals(new String[]{"война и мир", "сокращенная"}, TextNorm.splitEdition("Война и мир (сокращённая)"));
        assertArrayEquals(new String[]{"война и мир том 1", ""}, TextNorm.splitEdition("Война и мир (том 1)"));
        assertArrayEquals("a title that is only a marker stays whole", new String[]{"спектакль", ""},
                TextNorm.splitEdition("Спектакль"));
    }

    @Test
    public void aBracketKeepsItsPartNumberInTheTitle() {
        assertArrayEquals(new String[]{"мастер и маргарита часть 1", "радиоспектакль"},
                TextNorm.splitEdition("Мастер и Маргарита (радиоспектакль, часть 1)"));
        assertArrayEquals(new String[]{"тихий дон книга 2", "полная версия"},
                TextNorm.splitEdition("Тихий Дон (Книга 2, полная версия)"));
    }

    @Test
    public void stressMarksAndCapitalLookalikes() {
        assertEquals("замок", TextNorm.key("За́мок"));
        assertEquals("ночной дозор", TextNorm.key("Hочной Bозор".replace("Bозор", "дозор")));
        assertEquals("мастер", TextNorm.key("Mастер"));
        assertEquals("a Latin word with one Cyrillic letter stays Latin", "stеphen", TextNorm.key("Stеphen"));
    }

    @Test
    public void aHyphenatedSurnameIsNeverAPatronymic() {
        TextNorm.Person a = TextNorm.person("Василий Немирович-Данченко");
        TextNorm.Person b = TextNorm.person("Немирович-Данченко Василий Иванович");
        assertTrue(a.words.contains("немирович"));
        assertEquals(a, b);
    }

    @Test
    public void namesAreSetsOfWords() {
        TextNorm.Person kingA = TextNorm.person("Стивен Кинг");
        TextNorm.Person kingB = TextNorm.person("Кинг Стивен");
        TextNorm.Person kingC = TextNorm.person("Кинг С.");
        assertEquals(kingA, kingB);
        assertTrue(TextNorm.samePerson(kingA, kingC));
        assertTrue(TextNorm.samePerson(TextNorm.person("Макс Фрай"), TextNorm.person("Фрай Макс")));

        TextNorm.Person b1 = TextNorm.person("Булгаков Михаил Афанасьевич");
        TextNorm.Person b2 = TextNorm.person("М. А. Булгаков");
        TextNorm.Person b3 = TextNorm.person("Михаил Афанасьевич Булгаков");
        assertEquals(Arrays.asList("булгаков", "михаил"), Arrays.asList(b1.words.toArray()));
        assertEquals(b1, b3);
        assertTrue(TextNorm.samePerson(b1, b2));
        assertTrue(TextNorm.samePerson(b2, TextNorm.person("Булгаков")));
    }

    @Test
    public void differentPeopleStayApart() {
        assertFalse(TextNorm.samePerson(TextNorm.person("Александр Клюквин"), TextNorm.person("Александр Котов")));
        assertFalse(TextNorm.samePerson(TextNorm.person("Котов"), TextNorm.person("Котова")));
        assertFalse(TextNorm.samePerson(TextNorm.person("Александр Грин"), TextNorm.person("Александр Куприн")));
    }

    @Test
    public void aSurnameThatLooksLikeAPatronymicStays() {
        assertTrue(TextNorm.person("Генрик Сенкевич").words.contains("сенкевич"));
        assertTrue(TextNorm.person("Салтыков-Щедрин").words.contains("салтыковщедрин"));
    }

    @Test
    public void fieldsSplitIntoPeople() {
        List<TextNorm.Person> people = TextNorm.persons("Стругацкий Аркадий и Стругацкий Борис, Ильф И. / Петров Е. и др.");
        assertEquals(4, people.size());
        assertEquals(TextNorm.person("Петров Е."), people.get(3));
        assertTrue(TextNorm.persons(null).isEmpty());
        assertEquals(1, TextNorm.persons("Кинг Стивен, Стивен Кинг").size());
    }
}
