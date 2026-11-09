package com.fanok.audiobooks.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.fanok.audiobooks.pojo.GenrePOJO;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class GenrePicksTest {

    private static List<String> names(List<GenrePOJO> genres) {
        List<String> names = new ArrayList<>();
        for (GenrePOJO g : genres) {
            names.add(g.getName());
        }
        return names;
    }

    @Test
    public void favoritesRoundTripInTheirOrder() {
        List<Genre> genres = Arrays.asList(Genre.HORROR, Genre.DETECTIVE, Genre.KIDS);
        assertEquals("HORROR,DETECTIVE,KIDS", GenrePicks.join(genres));
        assertEquals(genres, GenrePicks.parse(GenrePicks.join(genres)));
        assertEquals("", GenrePicks.join(Collections.<Genre>emptyList()));
    }

    @Test
    public void parseDropsUnknownBlankAndRepeatedNames() {
        assertEquals(Arrays.asList(Genre.HORROR, Genre.DETECTIVE), GenrePicks.parse(" HORROR,FOO,,DETECTIVE,HORROR"));
        assertEquals(Collections.emptyList(), GenrePicks.parse(""));
        assertEquals(Collections.emptyList(), GenrePicks.parse(null));
        assertEquals(Genre.KIDS, GenrePicks.genre("KIDS"));
        assertNull(GenrePicks.genre("kids"));
    }

    @Test
    public void mergeRaisesTheFavouritesAndLeavesTheCountsAlone() {
        Map<String, Object> counts = new HashMap<>();
        counts.put(Genre.HORROR.name(), 3);
        counts.put(Genre.KIDS.name(), 5);
        counts.put("?искусственный голос", 2);
        Map<String, Object> merged = GenrePicks.merge(counts, Arrays.asList(Genre.HORROR, Genre.DETECTIVE));
        assertEquals(3 + GenrePicks.FAVORITE_BOOST, merged.get(Genre.HORROR.name()));
        assertEquals(GenrePicks.FAVORITE_BOOST, merged.get(Genre.DETECTIVE.name()));
        assertEquals(5, merged.get(Genre.KIDS.name()));
        assertEquals(2, merged.get("?искусственный голос"));
        assertEquals(3, counts.get(Genre.HORROR.name())); // a copy
        assertEquals(3, GenrePicks.opens(counts, Genre.HORROR));
        assertEquals(0, GenrePicks.opens(counts, Genre.DETECTIVE));
    }

    @Test
    public void anOpenCountsUnderEveryGenreTheNameNamesOnce() {
        assertEquals(Arrays.asList(Genre.DETECTIVE.name(), Genre.ACTION.name()),
                new ArrayList<>(GenrePicks.keys("Детективы, триллеры, боевики")));
        assertEquals(Collections.singletonList(Genre.HORROR.name()),
                new ArrayList<>(GenrePicks.keys("Мистика и ужасы")));
        assertEquals(Collections.singletonList("?искусственный голос"),
                new ArrayList<>(GenrePicks.keys("Искусственный голос")));
        assertTrue(GenrePicks.keys(null).isEmpty());
        assertTrue(GenrePicks.keys(" , ").isEmpty());
    }

    @Test
    public void narrowingKeepsTheSiteGenresThatNameTheGenre() {
        List<GenrePOJO> site = Arrays.asList(new GenrePOJO("Ужасы, мистика", "https://x/1"),
                new GenrePOJO("Детективы, триллеры, боевики", "https://x/2"),
                new GenrePOJO("Мистика и ужасы", "https://x/3"),
                new GenrePOJO("Искусственный голос", "https://x/4"));
        assertEquals(Arrays.asList("Ужасы, мистика", "Мистика и ужасы"),
                names(GenrePicks.narrowed(site, Genre.HORROR)));
        assertEquals(Collections.singletonList("Детективы, триллеры, боевики"),
                names(GenrePicks.narrowed(site, Genre.DETECTIVE)));
        assertEquals(Collections.singletonList("Детективы, триллеры, боевики"),
                names(GenrePicks.narrowed(site, Genre.ACTION)));
        assertEquals(Collections.emptyList(), names(GenrePicks.narrowed(site, Genre.PODCASTS)));
        assertSame(site, GenrePicks.narrowed(site, null));
    }

    @Test
    public void aCombinedGenreIsStarredByAnyGenreItNames() {
        String combined = "Детективы, триллеры, боевики";
        assertTrue(GenrePicks.starred(Collections.singletonList(Genre.ACTION), combined));
        assertTrue(GenrePicks.starred(Arrays.asList(Genre.KIDS, Genre.DETECTIVE), combined));
        assertFalse(GenrePicks.starred(Collections.singletonList(Genre.KIDS), combined));
        assertFalse(GenrePicks.starred(Collections.singletonList(Genre.KIDS), "Искусственный голос"));
    }

    @Test
    public void theStarOfACombinedGenreTakesOutEveryGenreItNamesOrStarsTheNarrowedOne() {
        String combined = "Детективы, триллеры, боевики";
        List<Genre> none = Collections.emptyList();
        // on: every genre it names goes, the others stay in their order
        assertEquals(Collections.singletonList(Genre.KIDS),
                GenrePicks.toggled(Arrays.asList(Genre.ACTION, Genre.KIDS, Genre.DETECTIVE), combined, Genre.ACTION));
        // off: the genre the list is narrowed to, else (not narrowed, or to a genre it does not name) its first one
        assertEquals(Arrays.asList(Genre.KIDS, Genre.ACTION),
                GenrePicks.toggled(Collections.singletonList(Genre.KIDS), combined, Genre.ACTION));
        assertEquals(Collections.singletonList(Genre.DETECTIVE), GenrePicks.toggled(none, combined, null));
        assertEquals(Collections.singletonList(Genre.DETECTIVE), GenrePicks.toggled(none, combined, Genre.KIDS));
        assertEquals(none, GenrePicks.toggled(none, "Искусственный голос", null));
    }
}
