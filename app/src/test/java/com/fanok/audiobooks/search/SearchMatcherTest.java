package com.fanok.audiobooks.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fanok.audiobooks.search.SearchMatcher.Card;
import com.fanok.audiobooks.search.SearchMatcher.Match;
import com.fanok.audiobooks.search.SearchMatcher.Tier;
import com.fanok.audiobooks.search.SearchRequest.Field;
import java.util.EnumSet;
import org.junit.Test;

/** The query corpus of search-refactoring.md, appendix B, for the matcher alone. */
public class SearchMatcherTest {

    private static final Card HEART = new Card("Собачье сердце", "Булгаков Михаил", null, "Клюквин Александр");

    private static Match anywhere(String query, Card card) {
        return SearchMatcher.score(card, SearchRequest.anywhere(query));
    }

    @Test
    public void wholeFieldIsTierA() {
        assertEquals(Tier.A, anywhere("собачье сердце", HEART).tier);
        assertEquals(Tier.A, anywhere("СОБАЧЬЕ СЕРДЦЕ", HEART).tier);
        assertEquals("the edition is not part of the title",
                Tier.A, anywhere("мастер и маргарита", new Card("Мастер и Маргарита (полная версия)", "Булгаков",
                        null, null)).tier);
    }

    @Test
    public void authorAndTitleWordsAreSpreadOverFields() {
        Match m = anywhere("булгаков собачье сердце", HEART); // B02
        assertEquals(Tier.C, m.tier);
        assertEquals(EnumSet.of(Field.TITLE, Field.AUTHOR), m.fields);
        assertEquals(Tier.C, anywhere("сердце собачье", HEART).tier); // B09: words out of order
    }

    @Test
    public void aTypoIsTierD() {
        Match m = anywhere("собачье серцде", HEART); // B08
        assertEquals(Tier.D, m.tier);
        assertTrue(m.fuzzy);
        assertTrue(m.score < anywhere("собачье сердце", HEART).score);
    }

    @Test
    public void aSingleExactWordInOneFieldIsTierB() {
        Card king = new Card("Оно", "Кинг Стивен", null, "Бордуков Игорь");
        assertEquals(Tier.B, anywhere("Кинг", king).tier); // B10
        Card pillar = new Card("Столп огненный", "Фоллетт Кен", "Кингсбридж", null);
        assertEquals("a prefix", Tier.D, anywhere("Кинг", pillar).tier);
        assertEquals(Tier.E, anywhere("Кинг", new Card("Игра по расписанию", "Бальдаччи Дэвид", null, null)).tier);
    }

    @Test
    public void stopWordsDoNotDecideTheTier() {
        String q = "гарри поттер и философский камень"; // B18
        assertEquals(Tier.A, anywhere(q, new Card("Гарри Поттер и философский камень", "Роулинг Джоан", null, null)).tier);
        assertEquals(Tier.B, anywhere(q, new Card("Гарри Поттер. Философский камень", "Роулинг Джоан", null, null)).tier);
        assertEquals(Tier.E, anywhere(q, new Card("Гарри Поттер и Тайная комната", "Роулинг Джоан", null, null)).tier);
    }

    @Test
    public void numbersMatchOnlyExactly() {
        Match m = anywhere("война и мир том 2", new Card("Война и мир (том 3)", "Толстой Лев", null, null)); // B29
        assertEquals(Tier.E, m.tier);
        assertFalse(m.covered);
        assertEquals(Tier.A, anywhere("война и мир том 2", new Card("Война и мир. Том 2", "Толстой Лев", null, null)).tier);
        assertEquals(Tier.B, anywhere("война и мир том 2", new Card("Война и мир. Том 2. Эпилог", "Толстой Лев", null, null)).tier);
    }

    @Test
    public void theMatchingTakesTheMostPairsThenTheBest() {
        // B26: a greedy match would give "алекс" the first name and lose "алексей"
        Match m = anywhere("алекс алексей", new Card("Сказки", "Алексей Александров", null, null));
        assertTrue(m.covered);
        Match d = anywhere("дозоры дозор", new Card("Дозоры дозора", null, null, null));
        assertTrue(d.covered);
        assertEquals("exact + prefix (95 + 8), not typo + prefix (85 + 8)", 100, d.score);
    }

    @Test
    public void scopeKeepsWordsInTheChosenFields() {
        SearchRequest byReader = new SearchRequest("клюквин", EnumSet.of(Field.READER));
        assertEquals(Tier.B, SearchMatcher.score(HEART, byReader).tier);
        SearchRequest byAuthor = new SearchRequest("клюквин", EnumSet.of(Field.AUTHOR));
        assertEquals(Tier.E, SearchMatcher.score(HEART, byAuthor).tier);
        SearchRequest either = new SearchRequest("булгаков клюквин", EnumSet.of(Field.AUTHOR, Field.READER));
        assertEquals(Tier.C, SearchMatcher.score(HEART, either).tier);
    }

    @Test
    public void authorDashTitleEarnsABonus() {
        Card it = new Card("Оно", "Стивен Кинг", null, null);
        Match dash = anywhere("стивен кинг — оно", it); // B11
        Match plain = anywhere("стивен кинг оно", it);
        assertEquals(Tier.C, dash.tier);
        assertEquals(Math.min(100, plain.score + 3), dash.score);
        Card misery = new Card("Мизери", "Стивен Кинг", null, null);
        assertEquals("the author's other book earns no bonus", anywhere("стивен кинг оно", misery).score,
                anywhere("стивен кинг — оно", misery).score);
    }

    @Test
    public void emptyQueryMatchesNothing() {
        assertEquals(Tier.E, anywhere("  ", HEART).tier);
        assertEquals(0, anywhere("—", HEART).score);
    }

    @Test
    public void matchingPrefersMoreEdgesOverHeavierOnes() {
        long[][] w = {{1100, 1090}, {1080, 0}};
        int[] a = SearchMatcher.maxWeightMatching(w, 2);
        assertEquals(1, a[0]);
        assertEquals(0, a[1]);
        int[] none = SearchMatcher.maxWeightMatching(new long[][]{{0}, {0}}, 1);
        assertEquals(-1, none[0]);
        assertEquals(-1, none[1]);
    }
}
