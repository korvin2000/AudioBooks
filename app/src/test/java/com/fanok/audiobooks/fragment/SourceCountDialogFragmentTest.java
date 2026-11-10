package com.fanok.audiobooks.fragment;

import static org.junit.Assert.assertEquals;

import com.fanok.audiobooks.search.Provider;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** The status line of «Активные источники»: the count as it is, the sites a new count switches on or off. */
public class SourceCountDialogFragmentTest {

    /** R.array.sources_count_status and R.string.sources_names_more. */
    private static final String[] WORDS = {"Работают сайты № 1–%1$d вашего списка", "Работает только № 1 — %1$s",
            "Активны все %1$d: поиск идёт по каждому сайту", "Включится: %1$s", "Включатся: %1$s",
            "Выключится: %1$s", "Выключатся: %1$s", "каталог перейдёт на %1$s"};
    private static final String MORE = "%1$s, %2$s и ещё %3$d";

    private static final Provider A = Provider.values()[0];
    private static final Provider B = Provider.values()[1];
    private static final Provider C = Provider.values()[2];
    private static final Provider D = Provider.values()[3];
    private static final List<Provider> FOUR = Arrays.asList(A, B, C, D);

    private static String line(List<Provider> order, int active, int shown, Provider catalog) {
        return SourceCountDialogFragment.statusLine(WORDS, MORE, order, active, shown, catalog);
    }

    @Test
    public void unchangedSaysWhichSitesWork() {
        assertEquals("Работают сайты № 1–2 вашего списка", line(FOUR, 2, 2, A));
        assertEquals("Работает только № 1 — " + A.title, line(FOUR, 1, 1, A));
        assertEquals("Активны все 4: поиск идёт по каждому сайту", line(FOUR, 4, 4, A));
    }

    @Test
    public void aHigherCountNamesTheSitesSwitchedOn() {
        assertEquals("Включится: " + C.title, line(FOUR, 2, 3, A));
        assertEquals("Включатся: " + B.title + ", " + C.title + ", " + D.title, line(FOUR, 1, 4, A));
        List<Provider> all = Arrays.asList(Provider.values());
        assertEquals("Включатся: " + all.get(13).title + ", " + all.get(14).title + " и ещё 5",
                line(all, 13, 20, A));
    }

    @Test
    public void aLowerCountNamesTheSitesSwitchedOffAndTheCatalogThatMoves() {
        assertEquals("Выключится: " + C.title, line(FOUR, 3, 2, A));
        assertEquals("Выключатся: " + B.title + ", " + C.title + ", " + D.title + " · каталог перейдёт на "
                + A.title, line(FOUR, 4, 1, C));
        assertEquals("no catalog known", "Выключится: " + D.title, line(FOUR, 4, 3, null));
    }
}
