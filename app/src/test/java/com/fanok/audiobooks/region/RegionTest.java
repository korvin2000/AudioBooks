package com.fanok.audiobooks.region;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class RegionTest {

    @Test
    public void russiaAndUkraine() {
        assertEquals(Region.RUSSIA, Region.ofCountry("RU"));
        assertEquals(Region.RUSSIA, Region.ofCountry("ru"));
        assertEquals(Region.UKRAINE, Region.ofCountry(" UA\n"));
    }

    @Test
    public void west() {
        String eu = "AT BE BG HR CY CZ DK EE FI FR DE GR IE IT LV LT LU MT NL PL PT RO SK SI ES SE HU";
        for (String cc : (eu + " GB US JP CH NO IS LI CA AU IL").split(" ")) {
            assertEquals(cc, Region.WEST, Region.ofCountry(cc));
        }
        assertEquals("27 EU + 10 named + territories", 27 + 10 + 7 + 6 + 4, Region.WEST_COUNTRIES.size());
        assertEquals("UK is GB", Region.WEST, Region.ofCountry("uk"));
        assertEquals("GB", Region.country("uk"));
        assertEquals("Réunion is the EU", Region.WEST, Region.ofCountry("RE"));
        assertEquals("Puerto Rico is the US", Region.WEST, Region.ofCountry("PR"));
    }

    @Test
    public void other() {
        for (String cc : "KR RS BA ME MK AL XK MD BY KZ CN TR GE AM NZ BR IN".split(" ")) {
            assertEquals(cc, Region.OTHER, Region.ofCountry(cc));
        }
    }

    @Test
    public void noCountry() {
        for (String code : new String[]{null, "", " ", "EU", "ZZ", "XX", "AP", "A1", "T1", "DEU", "Германия", "D"}) {
            assertNull(code, Region.ofCountry(code));
        }
    }
}
