package com.fanok.audiobooks.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.search.SearchGrouper.Group;
import com.fanok.audiobooks.search.SearchGrouper.Offer;
import com.fanok.audiobooks.search.SearchOrder.Sort;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class SearchOrderTest {

    private static int next;

    private static Offer offer(Provider source, int rank, String title, String author, String reader, String time,
            String query) {
        BookPOJO b = new BookPOJO();
        b.setName(title);
        b.setAutor(author);
        b.setArtist(reader);
        b.setTime(time);
        b.setUrl(source.server + "/b" + (next++));
        return new Offer(b, source, source.ordinal(), rank).score(SearchRequest.anywhere(query));
    }

    private static List<String> titles(List<Group> groups) {
        List<String> out = new ArrayList<>();
        for (Group g : groups) {
            out.add(g.representative.book.getName());
        }
        return out;
    }

    private static List<Group> sorted(Sort sort, Offer... offers) {
        List<Group> groups = SearchGrouper.group(Arrays.asList(offers), Collections.<String>emptySet(), true);
        Collections.sort(groups, SearchOrder.groups(sort));
        return groups;
    }

    @Test
    public void relevanceFirstThenTheSitesOwnRank() {
        String q = "собачье сердце";
        List<Group> g = sorted(Sort.RELEVANCE,
                offer(Provider.AKNIGA, 1, "Сердце пармы", "Иванов Алексей", null, null, q),
                offer(Provider.AKNIGA, 2, "Собачье сердце", "Булгаков Михаил", null, null, q),
                offer(Provider.IZIBUK, 1, "Собачье серце", "Неизвестный", null, null, q),
                offer(Provider.IZIBUK, 2, "Собачье сердце и другие повести", "Булгаков Михаил", null, null, q));
        assertEquals(Arrays.asList("Собачье сердце", "Собачье сердце и другие повести", "Собачье серце", "Сердце пармы"),
                titles(g));
    }

    @Test
    public void namesByTheRussianAlphabetEmptyLast() {
        List<Group> byAuthor = sorted(Sort.AUTHOR,
                offer(Provider.AKNIGA, 1, "Б", null, null, null, "x"),
                offer(Provider.AKNIGA, 2, "В", "Ёлкин", null, null, "x"),
                offer(Provider.AKNIGA, 3, "А", "Абрамов", null, null, "x"));
        assertEquals(Arrays.asList("А", "В", "Б"), titles(byAuthor));
        List<Group> byTitle = sorted(Sort.TITLE,
                offer(Provider.AKNIGA, 1, "Ёж", "x", null, null, "x"),
                offer(Provider.AKNIGA, 2, "Дом", "x", null, null, "x"),
                offer(Provider.AKNIGA, 3, "Жук", "x", null, null, "x"));
        assertEquals(Arrays.asList("Дом", "Ёж", "Жук"), titles(byTitle));
        List<Group> byReader = sorted(Sort.READER,
                offer(Provider.AKNIGA, 1, "Один", "x", "Князев Игорь", null, "x"),
                offer(Provider.AKNIGA, 2, "Два", "y", null, null, "x"),
                offer(Provider.AKNIGA, 3, "Три", "z", "Абдулов Всеволод", null, "x"));
        assertEquals(Arrays.asList("Три", "Один", "Два"), titles(byReader));
    }

    @Test
    public void unknownLengthsGoLastBothWays() {
        Offer shortOne = offer(Provider.AKNIGA, 1, "Короткая", "a", null, "01:00:00", "x");
        Offer longOne = offer(Provider.AKNIGA, 2, "Длинная", "b", null, "10:00:00", "x");
        Offer unknown = offer(Provider.AKNIGA, 3, "Неизвестная", "c", null, null, "x");
        assertEquals(Arrays.asList("Короткая", "Длинная", "Неизвестная"),
                titles(sorted(Sort.SHORTEST, unknown, longOne, shortOne)));
        assertEquals(Arrays.asList("Длинная", "Короткая", "Неизвестная"),
                titles(sorted(Sort.LONGEST, shortOne, unknown, longOne)));
    }

    @Test
    public void asTheSitesAnsweredKeepsTheOrderOfArrival() {
        List<Offer> offers = new ArrayList<>();
        String[] titles = {"Позже", "Раньше", "Посередине"};
        int[] arrival = {2, 0, 1};
        for (int i = 0; i < titles.length; i++) {
            BookPOJO b = new BookPOJO();
            b.setName(titles[i]);
            b.setUrl(Provider.AKNIGA.server + "/a" + (next++));
            offers.add(new Offer(b, Provider.AKNIGA, 0, 0, arrival[i]).score(SearchRequest.anywhere("позже")));
        }
        List<Group> groups = SearchGrouper.group(offers, Collections.<String>emptySet(), true);
        Collections.sort(groups, SearchOrder.groups(Sort.ARRIVAL));
        assertEquals(Arrays.asList("Раньше", "Посередине", "Позже"), titles(groups));
    }

    @Test
    public void aSiteTabKeepsTheSitesOrder() {
        List<Offer> offers = new ArrayList<>(Arrays.asList(
                offer(Provider.AKNIGA, 1002, "Третья", "a", null, null, "x"),
                offer(Provider.AKNIGA, 1, "Первая", "a", null, null, "x"),
                offer(Provider.AKNIGA, 2, "Вторая", "a", null, null, "x")));
        Collections.sort(offers, SearchOrder.offers(Sort.SITE));
        assertEquals("Первая", offers.get(0).book.getName());
        assertEquals("Третья", offers.get(2).book.getName());
    }

    private static List<String> names(List<Offer> offers) {
        List<String> out = new ArrayList<>();
        for (Offer o : offers) {
            out.add(o.book.getName());
        }
        return out;
    }

    /** A person's books: every site's newest, then every site's second newest; a searched card after every listed one. */
    @Test
    public void newestInterleavesTheSitesByTheirOwnPlacesSearchedCardsLast() {
        String q = "стивен кинг";
        List<Offer> offers = new ArrayList<>(Arrays.asList(
                offer(Provider.GOLOSOM, Integer.MAX_VALUE, "Найдена", "Стивен Кинг", null, null, q),
                offer(Provider.IZIBUK, 1, "Изибук 2", "Стивен Кинг", null, null, q),
                offer(Provider.AKNIGA, 1, "Акнига 2", "Стивен Кинг", null, null, q),
                offer(Provider.IZIBUK, 0, "Изибук 1", "Стивен Кинг", null, null, q),
                offer(Provider.AKNIGA, 0, "Акнига 1", "Стивен Кинг", null, null, q)));
        Collections.sort(offers, SearchOrder.offers(Sort.NEWEST));
        assertEquals(Arrays.asList("Акнига 1", "Изибук 1", "Акнига 2", "Изибук 2", "Найдена"), names(offers));
    }

    /** A work by its best place on any site; among equal places the work more sites carry first. */
    @Test
    public void newestGroupsByTheirBestPlaceThenByTheSitesThatCarryThem() {
        String q = "стивен кинг";
        List<Group> g = sorted(Sort.NEWEST,
                offer(Provider.GOLOSOM, Integer.MAX_VALUE, "Найдена", "Стивен Кинг", null, null, q),
                offer(Provider.AKNIGA, 1, "Вторая", "Стивен Кинг", null, null, q),
                offer(Provider.AKNIGA, 0, "Одна", "Стивен Кинг", null, null, q),
                offer(Provider.AKNIGA, 2, "Оно", "Стивен Кинг", null, null, q),
                offer(Provider.IZIBUK, 0, "Оно", "Стивен Кинг", null, null, q));
        assertEquals(Arrays.asList("Оно", "Одна", "Вторая", "Найдена"), titles(g));
    }

    /** Fixed keys: any arrival order of the same cards sorts to the same list. */
    @Test
    public void newestIsTheSameWhateverTheOrderOfArrival() {
        Provider[] sites = {Provider.AKNIGA, Provider.IZIBUK, Provider.GOLOSOM, Provider.KNIGAVUHE};
        java.util.Random random = new java.util.Random(7);
        List<Offer> offers = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            Provider site = sites[i % sites.length];
            int rank = random.nextInt(4) == 0 ? Integer.MAX_VALUE : random.nextInt(6);
            offers.add(offer(site, rank, "Книга " + random.nextInt(12), "Стивен Кинг", null, null, "кинг"));
        }
        List<Offer> a = new ArrayList<>(offers);
        List<Offer> b = new ArrayList<>(offers);
        Collections.shuffle(a, new java.util.Random(1));
        Collections.shuffle(b, new java.util.Random(2));
        Collections.sort(a, SearchOrder.offers(Sort.NEWEST));
        Collections.sort(b, SearchOrder.offers(Sort.NEWEST));
        assertEquals(a, b);
        List<Group> ga = SearchGrouper.group(a, Collections.<String>emptySet(), true);
        List<Group> gb = SearchGrouper.group(b, Collections.<String>emptySet(), true);
        Collections.sort(ga, SearchOrder.groups(Sort.NEWEST));
        Collections.sort(gb, SearchOrder.groups(Sort.NEWEST));
        List<String> ida = new ArrayList<>();
        List<String> idb = new ArrayList<>();
        for (int i = 0; i < ga.size(); i++) {
            ida.add(ga.get(i).id);
            idb.add(gb.get(i).id);
        }
        assertEquals(ida, idb);
        for (int i = 1; i < a.size(); i++) {
            assertTrue(a.get(i - 1).nativeRank <= a.get(i).nativeRank);
        }
    }
}
