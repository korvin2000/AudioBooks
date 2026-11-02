package com.fanok.audiobooks.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class BookFolderNameTest {

    @Test
    public void titleAuthorAndReader() {
        assertEquals("Мастер и Маргарита - Булгаков Михаил (Козий Владимир)",
                StorageNames.bookFolderName("Мастер и Маргарита", "Булгаков Михаил", "Козий Владимир"));
        assertEquals("Война и мир (Ливанов)", StorageNames.bookFolderName(" Война и мир ", null, "Ливанов"));
        assertEquals("Война и мир", StorageNames.bookFolderName("Война и мир", "", " "));
    }

    @Test
    public void aReaderEqualToTheAuthorIsNotRepeated() {
        assertEquals("Война и мир - Толстой", StorageNames.bookFolderName("Война и мир", "Толстой", "Толстой"));
    }

    @Test
    public void reservedCharactersAreRemoved() {
        assertEquals("Гарри Поттер Тайная комната - Роулинг",
                StorageNames.bookFolderName("Гарри Поттер: Тайная комната?", "Роулинг", null));
        String name = StorageNames.bookFolderName("Война/мир", "Тол\\стой", "Ко<зий>");
        assertEquals("Войнамир - Толстой (Козий)", name);
    }

    @Test
    public void unusableTitlesBecomeUnknown() {
        assertEquals("Unknown - Булгаков", StorageNames.bookFolderName(null, "Булгаков", null));
        assertEquals("Unknown", StorageNames.bookFolderName("", null, null));
        assertEquals("Unknown", StorageNames.bookFolderName("   ", null, null));
        assertEquals("Unknown", StorageNames.bookFolderName("...", null, null));
        assertEquals("Unknown", StorageNames.bookFolderName("CON", null, null));
        assertEquals("Unknown", StorageNames.bookFolderName(". CON", null, null));
    }

    @Test
    public void theFolderIsNeverHiddenNorEndsWithADot() {
        assertEquals("и вечный бой", StorageNames.bookFolderName("...и вечный бой.", null, null));
        assertEquals("и вечный бой - Блок", StorageNames.bookFolderName(" . и вечный бой. ", "Блок.", null));
    }

    @Test
    public void aLongTitleIsCutWithoutSplittingACharacterAndDropsTheRest() {
        StringBuilder title = new StringBuilder("Ж/");
        while (title.length() < 300) title.append("Ж");
        String name = StorageNames.bookFolderName(title.toString(), "Булгаков", "Козий");

        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        assertTrue(bytes.length <= StorageNames.MAX_FOLDER_BYTES);
        assertEquals(name, new String(bytes, StandardCharsets.UTF_8));
        assertFalse(name.contains("/"));
        assertFalse(name.contains("Булгаков"));
        assertTrue(name.matches("Ж+"));
    }

    @Test
    public void theCutLeavesNoTrailingDotsOrSpaces() {
        StringBuilder title = new StringBuilder();
        while (title.length() < 74) title.append("Ж");
        // 148 bytes, then " ." reaches exactly the limit
        String name = StorageNames.bookFolderName(title + " .ЖЖЖ", null, null);

        assertEquals(title.toString(), name);
    }

    @Test
    public void theReaderIsDroppedFirstThenTheAuthor() {
        StringBuilder reader = new StringBuilder();
        while (reader.length() < 200) reader.append("Ж");
        assertEquals("Мастер - Булгаков", StorageNames.bookFolderName("Мастер", "Булгаков", reader.toString()));

        StringBuilder title = new StringBuilder();
        while (title.length() < 70) title.append("Ж");
        // 140 bytes: the author does not fit, so the short reader goes too
        assertEquals(title.toString(), StorageNames.bookFolderName(title.toString(), "Булгаков Михаил", "Ян"));
        assertTrue(StorageNames.utf8Length(title + " (Ян)") <= StorageNames.MAX_FOLDER_BYTES);
    }
}
