package com.fanok.audiobooks.search;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** The user's books by site: the counts of a source card and the source filter of the favorites. */
public class SourceLibraryTest {

    private static final String IZIBUK = "https://izibuk.ru/art1";
    private static final String IZIBUK_2 = "https://izibuk.ru/art2";
    private static final String BAZA = "https://baza-knig.ink/x/1-y.html";
    private static final String KNIGOBLUD = "https://www.knigoblud.club/e87cc048";
    private static final String UNKNOWN = "https://example.com/book";

    @Test
    public void countsEachListOfEachSite() {
        Map<Provider, int[]> counts = SourceLibrary.count(Arrays.asList(IZIBUK, IZIBUK_2, BAZA),
                Arrays.asList(IZIBUK, UNKNOWN, null), Collections.singletonList(KNIGOBLUD));
        assertArrayEquals(new int[]{2, 1, 0}, counts.get(Provider.IZIBUK));
        assertArrayEquals(new int[]{1, 0, 0}, counts.get(Provider.BAZA_KNIG));
        assertArrayEquals(new int[]{0, 0, 1}, counts.get(Provider.KNIGOBLUD));
        assertEquals("a site without books and an unknown host are absent", 3, counts.size());
    }

    @Test
    public void nothingCountedInAnEmptyLibrary() {
        List<String> none = Collections.emptyList();
        assertTrue(SourceLibrary.count(none, none, none).isEmpty());
    }

    @Test
    public void sitesAreListedMostBooksFirstThenInTheAppsOrder() {
        Map<Provider, Integer> sites = SourceLibrary.sites(Arrays.asList(BAZA, IZIBUK, KNIGOBLUD, UNKNOWN, IZIBUK_2));
        List<Provider> order = new ArrayList<>(sites.keySet());
        assertEquals(Provider.IZIBUK, order.get(0));
        assertEquals(Integer.valueOf(2), sites.get(Provider.IZIBUK));
        assertEquals(3, order.size());
        boolean bazaFirst = Provider.BAZA_KNIG.ordinal() < Provider.KNIGOBLUD.ordinal();
        assertEquals(bazaFirst ? Provider.BAZA_KNIG : Provider.KNIGOBLUD, order.get(1));
        assertEquals(Integer.valueOf(1), sites.get(order.get(2)));
    }

    @Test
    public void onlyKeepsTheSitesItemsInTheirOrder() {
        List<String> books = Arrays.asList(IZIBUK, BAZA, UNKNOWN, IZIBUK_2);
        assertEquals(Arrays.asList(IZIBUK, IZIBUK_2), SourceLibrary.only(books, Function.identity(), Provider.IZIBUK));
        assertTrue(SourceLibrary.only(books, Function.identity(), Provider.KNIGOBLUD).isEmpty());
    }

    @Test
    public void aFilterIsMadeAgainInTheOrderOfTheWholeList() {
        String a = new String("a");
        String b = new String("b");
        String c = new String("c");
        String d = new String("d");
        // c came back at its place in the whole list; the filter held d and b, c belonged to it
        List<String> all = Arrays.asList(a, b, c, d);
        assertEquals(Arrays.asList(b, c, d), SourceLibrary.among(all, Arrays.asList(d, b, c)));
        // the same objects only: an equal copy is another book
        List<String> among = SourceLibrary.among(all, Collections.singletonList(new String("a")));
        assertTrue(among.isEmpty());
    }
}
