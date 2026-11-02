package com.fanok.audiobooks.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class BookHeaderTest {

    @Test
    public void aLeadingAuthorAndItsSeparatorAreDropped() {
        assertEquals("Гарри Поттер", BookHeader.title("Роулинг Джоан", "Роулинг Джоан - Гарри Поттер"));
        assertEquals("Оно", BookHeader.title("Кинг Стивен", "кинг стивен — Оно"));
        assertEquals("Оно", BookHeader.title(" Кинг Стивен ", "Кинг Стивен. Оно"));
    }

    @Test
    public void anyOtherTitleIsKept() {
        assertEquals("Кингсли - Оно", BookHeader.title("Кинг", "Кингсли - Оно"));
        assertEquals("Гарри Поттер", BookHeader.title("Роулинг Джоан", " Гарри Поттер "));
        assertEquals("Роулинг Джоан", BookHeader.title("Роулинг Джоан", "Роулинг Джоан"));
        assertEquals("Роулинг Джоан -", BookHeader.title("Роулинг Джоан", "Роулинг Джоан - "));
        assertEquals("Гарри Поттер", BookHeader.title("", "Гарри Поттер"));
        assertEquals("Гарри Поттер", BookHeader.title(null, "Гарри Поттер"));
        assertEquals("", BookHeader.title("Роулинг Джоан", null));
    }
}
