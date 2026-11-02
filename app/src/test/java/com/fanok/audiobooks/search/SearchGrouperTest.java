package com.fanok.audiobooks.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.search.SearchGrouper.Group;
import com.fanok.audiobooks.search.SearchGrouper.Narration;
import com.fanok.audiobooks.search.SearchGrouper.Offer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import org.junit.Test;

/** Grouping cases of search-refactoring.md, appendix B. */
public class SearchGrouperTest {

    private static int next;

    private static Offer offer(Provider source, String title, String author, String reader, String time) {
        BookPOJO b = new BookPOJO();
        b.setName(title);
        b.setAutor(author);
        b.setArtist(reader);
        b.setTime(time);
        b.setUrl(source.server + "/book-" + (next++));
        return new Offer(b, source, source.ordinal(), 0).score(SearchRequest.anywhere(title));
    }

    private static List<Group> group(List<Offer> offers) {
        return SearchGrouper.group(offers, Collections.<String>emptySet(), true);
    }

    private static Group only(List<Group> groups) {
        assertEquals(1, groups.size());
        return groups.get(0);
    }

    @Test
    public void oneWorkFromManySitesIsOneGroupAndMirrorsCountOnce() { // B01
        List<Offer> offers = Arrays.asList(
                offer(Provider.AKNIGA, "Мастер и Маргарита", "Булгаков Михаил", "Клюквин Александр", "15:20:00"),
                offer(Provider.KNIGOBLUD, "Мастер и Маргарита", "Михаил Булгаков", "Клюквин Александр", "15:22:10"),
                offer(Provider.BIBLUS, "Мастер и Маргарита", "Булгаков Михаил", "Клюквин Александр", "15 ч. 21 мин."),
                offer(Provider.GOLOSOM, "Мастер и Маргарита", "Булгаков Михаил", "Клюквин Александр", null),
                offer(Provider.IZIBUK, "Мастер и Маргарита (полная версия)", "Булгаков М.", "Клюквин Александр",
                        "15:20:00"),
                offer(Provider.KNIGAVUHE, "Мастер и Маргарита", "Булгаков Михаил", "Козий Николай", "17:05:00"));
        Group g = only(group(offers));
        assertEquals(6, g.offers.size());
        assertEquals("ИзиБук, Книгоблуд, Библус и Голосом are one catalog", 3, g.catalogs());
        assertEquals("Клюквин, Клюквин (полная версия), Козий", 3, g.narrations.size());
        Narration kluk = g.narrations.get(0);
        assertEquals(new TreeSet<>(Arrays.asList("александр", "клюквин")), kluk.reader);
        assertEquals("a card without a length joins the only length there is", 4, kluk.offers.size());
        assertEquals(2, kluk.catalogs());
    }

    @Test
    public void anAuthorlessCardJoinsTheOnlyGroupOfItsTitle() { // B06
        Group g = only(group(Arrays.asList(offer(Provider.AKNIGA, "1984", "Оруэлл Джордж", "Терновский Евгений", null),
                offer(Provider.OTRUB, "1984", null, null, null))));
        assertEquals(2, g.offers.size());
        List<Group> two = group(Arrays.asList(offer(Provider.AKNIGA, "1984", "Оруэлл Джордж", null, null),
                offer(Provider.IZIBUK, "1984", "Иванов Иван", null, null),
                offer(Provider.OTRUB, "1984", null, null, null)));
        assertEquals("with two authors the authorless card is no bridge", 3, two.size());
    }

    @Test
    public void differentWorksStayApart() {
        assertEquals("B12", 2, group(Arrays.asList(offer(Provider.AKNIGA, "Дюна", "Герберт Фрэнк", null, null),
                offer(Provider.AKNIGA, "Мессия Дюны", "Герберт Фрэнк", null, null))).size());
        assertEquals("B13", 2, group(Arrays.asList(offer(Provider.AKNIGA, "Война и мир. Том 2", "Толстой Лев", null, null),
                offer(Provider.IZIBUK, "Война и мир. Том 3", "Толстой Лев", null, null))).size());
        assertEquals("B23", 2, group(Arrays.asList(offer(Provider.AKNIGA, "Рассказы", "Александр Грин", null, null),
                offer(Provider.IZIBUK, "Рассказы", "Александр Куприн", null, null))).size());
        assertEquals("B17", 2, group(Arrays.asList(
                offer(Provider.AKNIGA, "Гарри Поттер и философский камень", "Роулинг Джоан", null, null),
                offer(Provider.AKNIGA, "Гарри Поттер и Тайная комната", "Роулинг Джоан", null, null))).size());
    }

    @Test
    public void namesWrittenDifferentlyAreOneAuthor() { // B21, B24
        Group g = only(group(Arrays.asList(offer(Provider.AKNIGA, "Оно", "Стивен Кинг", null, null),
                offer(Provider.IZIBUK, "Оно", "Кинг Стивен", null, null),
                offer(Provider.KNIGAVUHE, "Оно", "Кинг С.", null, null))));
        assertEquals(3, g.offers.size());
        assertEquals(3, only(group(Arrays.asList(offer(Provider.AKNIGA, "Белая гвардия", "Булгаков Михаил Афанасьевич", null, null),
                offer(Provider.IZIBUK, "Белая гвардия", "М. А. Булгаков", null, null),
                offer(Provider.OTRUB, "Белая гвардия.", "Булгаков", null, null)))).offers.size());
        assertEquals("fuzzy off: different keys stay apart", 3, SearchGrouper.group(Arrays.asList(
                offer(Provider.AKNIGA, "Оно", "Стивен Кинг", null, null),
                offer(Provider.KNIGAVUHE, "Оно", "Кинг С.", null, null),
                offer(Provider.IZIBUK, "Оно", "Стивен Кинг Хилл", null, null)), Collections.<String>emptySet(), false).size());
    }

    /** The choice of recordings: a known reader before "reader unknown", the one on most sites first. */
    @Test
    public void recordingsOnMostSitesComeFirstAndUnknownReadersLast() {
        Group g = only(group(Arrays.asList(
                offer(Provider.SLUSHAT_KNIGI, "Мастер и Маргарита", "Булгаков Михаил", null, "16:53:00"),
                offer(Provider.KNIGAVUHE, "Мастер и Маргарита", "Булгаков Михаил", "Козий Николай", "17:05:00"),
                offer(Provider.AKNIGA, "Мастер и Маргарита", "Булгаков Михаил", "Абдуллаев Джахангир", "18:19:00"),
                offer(Provider.IZIBUK, "Мастер и Маргарита", "Булгаков Михаил", "Абдуллаев Джахангир", "18:19:00"))));
        assertEquals(3, g.narrations.size());
        assertEquals(new TreeSet<>(Arrays.asList("абдуллаев", "джахангир")), g.narrations.get(0).reader);
        assertEquals(new TreeSet<>(Arrays.asList("козий", "николай")), g.narrations.get(1).reader);
        assertTrue(g.narrations.get(2).reader.isEmpty());
    }

    @Test
    public void romanVolumesStayApart() {
        assertEquals(2, SearchGrouper.roman("ii"));
        assertEquals(14, SearchGrouper.roman("xiv"));
        assertEquals(0, SearchGrouper.roman("mix"));
        assertEquals(2, group(Arrays.asList(offer(Provider.AKNIGA, "Ведьмак. Книга II", "Сапковский Анджей", "Иванов", "10:00:00"),
                offer(Provider.IZIBUK, "Ведьмак. Книга III", "Сапковский А.", "Иванов", "10:10:00"))).size());
        assertEquals(2, group(Arrays.asList(offer(Provider.AKNIGA, "Хроники Амбера", "Желязны Роджер", null, null),
                offer(Provider.IZIBUK, "Хроники Амбера II", "Желязны Р.", null, null))).size());
    }

    @Test
    public void anAuthorlessCardJoinsOnceTwoSpellingsOfTheAuthorMerged() {
        Group g = only(group(Arrays.asList(offer(Provider.AKNIGA, "Собачье сердце", "Булгаков Михаил", null, null),
                offer(Provider.IZIBUK, "Собачье сердце", "М. Булгаков", null, null),
                offer(Provider.OTRUB, "Собачье сердце", null, null, null))));
        assertEquals(3, g.offers.size());
    }

    @Test
    public void recordingsSplitByReaderEditionAndLength() { // B19
        Group g = only(group(Arrays.asList(
                offer(Provider.AKNIGA, "Пикник на обочине", "Стругацкие", "Кузнецов Сергей", "2 ч 05 мин"),
                offer(Provider.IZIBUK, "Пикник на обочине", "Стругацкие", "Кузнецов Сергей", "09:02:00"),
                offer(Provider.KNIGAVUHE, "Пикник на обочине", "Стругацкие", "Кузнецов Сергей", "09:10:00"),
                offer(Provider.OTRUB, "Пикник на обочине (радиоспектакль)", "Стругацкие", null, "01:10:00"))));
        assertEquals(3, g.narrations.size());
    }

    @Test
    public void theResultDoesNotDependOnArrival() { // B15
        List<Offer> offers = new ArrayList<>(Arrays.asList(
                offer(Provider.AKNIGA, "Мастер и Маргарита", "Булгаков Михаил", "Клюквин Александр", "15:20:00"),
                offer(Provider.KNIGOBLUD, "Мастер и Маргарита", "Михаил Булгаков", "Клюквин Александр", "15:22:10"),
                offer(Provider.IZIBUK, "Мастер и Маргарита", "Булгаков М.", "Козий Николай", null),
                offer(Provider.OTRUB, "Мастер и Маргарита", null, null, null),
                offer(Provider.AKNIGA, "Белая гвардия", "Булгаков Михаил", null, null),
                offer(Provider.IZIBUK, "Белая гвардия.", "Булгаков", null, null),
                offer(Provider.AKNIGA, "1984", "Оруэлл Джордж", null, null)));
        Set<Set<String>> expected = urlSets(group(offers));
        Random random = new Random(7);
        for (int i = 0; i < 20; i++) {
            Collections.shuffle(offers, random);
            assertEquals(expected, urlSets(group(offers)));
        }
    }

    @Test
    public void aShownIdSurvivesAMerge() {
        Offer first = offer(Provider.IZIBUK, "Оно", "Кинг Стивен", null, null);
        Group shown = only(group(Collections.singletonList(first)));
        Offer smaller = offer(Provider.AKNIGA, "Оно", "Стивен Кинг", null, null);
        assertTrue(smaller.url().compareTo(first.url()) < 0);
        Group later = only(SearchGrouper.group(Arrays.asList(first, smaller),
                new HashSet<>(Collections.singletonList(shown.id)), true));
        assertEquals(shown.id, later.id);
        assertEquals(smaller.url(), only(group(Arrays.asList(first, smaller))).id);
    }

    private static Set<Set<String>> urlSets(List<Group> groups) {
        Set<Set<String>> out = new HashSet<>();
        for (Group g : groups) {
            Set<String> urls = new HashSet<>();
            for (Offer o : g.offers) {
                urls.add(o.url());
            }
            out.add(urls);
        }
        return out;
    }
}
