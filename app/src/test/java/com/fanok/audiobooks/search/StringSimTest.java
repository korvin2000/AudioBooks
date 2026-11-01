package com.fanok.audiobooks.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StringSimTest {

    @Test
    public void osaCountsASwapOfNeighboursAsOneEdit() {
        assertEquals(0, StringSim.osa("сердце", "сердце", 2));
        assertEquals(1, StringSim.osa("серцде", "сердце", 2));
        assertEquals(1, StringSim.osa("сердце", "серце", 2));
        assertEquals(1, StringSim.osa("сердце", "сердца", 2));
        assertEquals(3, StringSim.osa("ca", "abc", 3)); // OSA, not full Damerau: no edit of a swapped pair
    }

    @Test
    public void osaStopsAtItsLimit() {
        assertEquals(2, StringSim.osa("кот", "котострофа", 1));
        assertEquals(3, StringSim.osa("абвгд", "еёжзи", 2));
    }

    @Test
    public void diceOfBigrams() {
        assertEquals(1.0, StringSim.dice(StringSim.bigrams(TextNorm.key("Собачье сердце")),
                StringSim.bigrams(TextNorm.key("Собачье сердце."))), 1e-9);
        double dune = StringSim.dice(StringSim.bigrams(TextNorm.key("Дюна")),
                StringSim.bigrams(TextNorm.key("Мессия Дюны")));
        assertTrue("Dune and Dune Messiah are not one book: " + dune, dune < 0.5);
        assertEquals(0, StringSim.dice(StringSim.bigrams(""), StringSim.bigrams("")), 0);
        assertEquals(1.0, StringSim.dice(StringSim.bigrams("я"), StringSim.bigrams("я")), 0);
    }
}
