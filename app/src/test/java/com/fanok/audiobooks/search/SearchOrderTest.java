package com.fanok.audiobooks.search;

import static org.junit.Assert.assertEquals;

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
}
