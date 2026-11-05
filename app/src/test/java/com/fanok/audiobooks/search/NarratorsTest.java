package com.fanok.audiobooks.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

/** fantlab.ru's narrators: the built-in list matched in any word order, and their cards. */
public class NarratorsTest {

    private static Document page(String name) throws IOException {
        try (InputStream in = NarratorsTest.class.getResourceAsStream("/fixtures/fantlab/" + name)) {
            return Jsoup.parse(in, "UTF-8", "https://fantlab.ru/" + name.replace(".html", ""));
        }
    }

    @Test
    public void aReaderIsFoundInAnyWordOrder() throws IOException {
        Map<String, String> cards = Narrators.cards(
                PeopleIndex.of(Files.readAllBytes(Paths.get("src/main/assets/people/people.bin"))));
        assertEquals("all of fantlab's narrators", 3965, cards.size(), 20);
        assertEquals("https://fantlab.ru/dictor152", cards.get(Narrators.key("Абдулов Всеволод")));
        assertEquals("https://fantlab.ru/dictor152", cards.get(Narrators.key("Всеволод  Абдулов")));
        assertNull(cards.get(Narrators.key("Всеволод")));
        assertEquals(Narrators.key("Клюквин Александр"), Narrators.key("александр клюквин"));
        assertEquals("а бальянс", Narrators.key("Бальянс А."));
    }

    @Test
    public void aFullCard() throws IOException {
        Narrators.Card card = Narrators.parse(page("dictor152.html"));
        assertEquals("Всеволод Абдулов", card.name);
        assertEquals("https://fantlab.ru/images/dictors/152", card.photo);
        assertEquals("Страна: Россия", card.facts.get(0));
        assertEquals("Дата смерти: 27 июля 2002 г. (59 лет)", card.facts.get(2));
        assertTrue(card.bio, card.bio.startsWith("Всеволод О́сипович Абдулов — советский и российский актёр"));
        assertTrue(card.bio, card.bio.contains("\n\nВсеволод Абдулов родился 29 декабря 1942 года"));
        assertEquals("Опередивший время — Зоя Чернышёва, 1969", card.books.get(0));
        assertTrue(card.books.size() > 30);
    }

    @Test
    public void aTitleLosesFantlabsMarkup() {
        Document page = Jsoup.parse("<div class=editions_block><div id=edition-1 author='Игорь Вардунас' year=2022"
                + " etitle='[work=414771]Циркезонье[/work]'></div></div>", "https://fantlab.ru/dictor1");
        assertEquals("Циркезонье — Игорь Вардунас, 2022", Narrators.parse(page).books.get(0));
    }

    @Test
    public void aCardOfANameAlone() throws IOException {
        Narrators.Card card = Narrators.parse(page("dictor3411.html"));
        assertEquals("А. Бальянс", card.name);
        assertTrue(card.facts.isEmpty());
        assertEquals("", card.bio);
        assertEquals(2, card.books.size());
    }
}
