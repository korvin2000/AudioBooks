package com.fanok.audiobooks.search;

import static org.junit.Assert.assertEquals;

import com.fanok.audiobooks.pojo.GenrePOJO;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class GenreSortTest {

    private static GenrePOJO genre(String name, int count) {
        GenrePOJO g = new GenrePOJO(name, "https://x/" + name);
        g.setReting(count);
        return g;
    }

    private static List<String> names(List<GenrePOJO> genres) {
        List<String> names = new ArrayList<>();
        for (GenrePOJO g : genres) {
            names.add(g.getName());
        }
        return names;
    }

    private final List<GenrePOJO> site = Arrays.asList(
            genre("фэнтези", 0), genre("Детектив", 120), genre("аудиоспектакль", 7), genre("Ёлки", 120),
            genre("Боевик", 0));

    @Test
    public void noneKeepsTheSiteOrderInACopy() {
        List<GenrePOJO> sorted = GenreSort.NONE.sorted(site);
        assertEquals(names(site), names(sorted));
        sorted.clear();
        assertEquals(5, site.size());
    }

    @Test
    public void nameSortsAlphabeticallyIgnoringCase() {
        assertEquals(Arrays.asList("аудиоспектакль", "Боевик", "Детектив", "Ёлки", "фэнтези"),
                names(GenreSort.NAME.sorted(site)));
    }

    @Test
    public void countPutsMostBooksFirstAndGenresWithoutACountLastInTheSiteOrder() {
        assertEquals(Arrays.asList("Детектив", "Ёлки", "аудиоспектакль", "фэнтези", "Боевик"),
                names(GenreSort.COUNT.sorted(site)));
    }

    @Test
    public void anUnknownKeptKeyIsNone() {
        assertEquals(GenreSort.NONE, GenreSort.of(null));
        assertEquals(GenreSort.NONE, GenreSort.of("bogus"));
        assertEquals(GenreSort.COUNT, GenreSort.of(GenreSort.COUNT.name()));
    }
}
