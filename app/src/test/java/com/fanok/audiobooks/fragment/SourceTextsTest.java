package com.fanok.audiobooks.fragment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fanok.audiobooks.region.Region;
import com.fanok.audiobooks.region.RegionCheck;
import com.fanok.audiobooks.search.Provider;
import com.fanok.audiobooks.search.ProviderPage;
import com.fanok.audiobooks.search.SourceProbe;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/** The words of the source screens: book counts, years, months, servers, "how long ago", lines and names. */
public class SourceTextsTest {

    private static final String THOUSANDS = "≈ %1$s тыс.";
    private static final String UNITS = "≈ %1$s";
    private static final String[] AGO = {"только что", "%1$d мин назад", "%1$d ч назад", "%1$d дн. назад"};
    private static final String[] MONTHS = {"январь", "февраль", "март", "апрель", "май", "июнь", "июль", "август",
            "сентябрь", "октябрь", "ноябрь", "декабрь"};

    @Test
    public void booksAreWholeThousandsTenthsOrInFull() {
        assertEquals("≈ 72 тыс.", SourceTexts.books(72116, THOUSANDS, UNITS));
        assertEquals("≈ 126 тыс.", SourceTexts.books(125642, THOUSANDS, UNITS));
        assertEquals("≈ 5,8 тыс.", SourceTexts.books(5823, THOUSANDS, UNITS));
        assertEquals("tenths up to where they would round to ten", "≈ 9,9 тыс.",
                SourceTexts.books(9949, THOUSANDS, UNITS));
        assertEquals("≈ 10 тыс.", SourceTexts.books(9950, THOUSANDS, UNITS));
        assertEquals("≈ 2 271", SourceTexts.books(2271, THOUSANDS, UNITS));
        assertEquals("≈ 980", SourceTexts.books(980, THOUSANDS, UNITS));
    }

    @Test
    public void digitsAreGroupedByThree() {
        assertEquals("0", SourceTexts.grouped(0));
        assertEquals("999", SourceTexts.grouped(999));
        assertEquals("1 000", SourceTexts.grouped(1000));
        assertEquals("1 234 567", SourceTexts.grouped(1234567));
    }

    @Test
    public void theYearOfSince() {
        assertEquals("2014", SourceTexts.year("2014-07"));
        assertEquals("2014", SourceTexts.year("2014"));
        assertNull(SourceTexts.year(null));
        assertNull(SourceTexts.year("июль 2014"));
    }

    @Test
    public void aMonthOfTheBookCount() {
        assertEquals("ноябрь 2026", SourceTexts.month("2026-11", MONTHS));
        assertEquals("январь 2025", SourceTexts.month("2025-01", MONTHS));
        assertNull(SourceTexts.month("2026-13", MONTHS));
        assertNull(SourceTexts.month("2026", MONTHS));
        assertNull(SourceTexts.month(null, MONTHS));
    }

    @Test
    public void theServerIsTheCountryOrTheNetworkInFront() {
        assertEquals("Нидерланды", SourceTexts.server("NL", null, "за %1$s"));
        assertEquals("за DDoS-Guard", SourceTexts.server(null, "DDoS-Guard", "за %1$s"));
        assertEquals("Нидерланды · за Cloudflare", SourceTexts.server("NL", "Cloudflare", "за %1$s"));
        assertNull(SourceTexts.server(null, null, "за %1$s"));
    }

    @Test
    public void agoTakesTheLargestWholeUnit() {
        long now = TimeUnit.DAYS.toMillis(100);
        assertNull("never", SourceTexts.ago(0, now, AGO));
        assertEquals("только что", SourceTexts.ago(now - TimeUnit.SECONDS.toMillis(30), now, AGO));
        assertEquals("a clock that went back", "только что", SourceTexts.ago(now + 5000, now, AGO));
        assertEquals("5 мин назад", SourceTexts.ago(now - TimeUnit.MINUTES.toMillis(5), now, AGO));
        assertEquals("2 ч назад", SourceTexts.ago(now - TimeUnit.MINUTES.toMillis(150), now, AGO));
        assertEquals("3 дн. назад", SourceTexts.ago(now - TimeUnit.HOURS.toMillis(80), now, AGO));
    }

    @Test
    public void joinSkipsEmptyParts() {
        assertEquals("0,8 с · 2 ч назад · через прокси", SourceTexts.join("0,8 с", "2 ч назад", "через прокси"));
        assertEquals("блок · напрямую", SourceTexts.join("блок", null, "", "напрямую"));
        assertEquals("", SourceTexts.join((String) null));
        assertEquals("№ 1, Akniga, каталог", SourceTexts.spoken("№ 1", "Akniga", null, "каталог"));
    }

    @Test
    public void sortsAreOneLineInLowerCaseEachOnce() {
        assertEquals("новое, популярное, рейтинг",
                SourceTexts.sorts(Arrays.asList("Новое", "Популярное", "Рейтинг", "Новое")));
        assertNull(SourceTexts.sorts(Collections.<String>emptyList()));
    }

    @Test
    public void namesBeyondThreeAreCounted() {
        String more = "%1$s, %2$s и ещё %3$d";
        assertEquals("A, B, C", SourceTexts.names(Arrays.asList("A", "B", "C"), more));
        assertEquals("A, B и ещё 3", SourceTexts.names(Arrays.asList("A", "B", "C", "D", "E"), more));
        assertEquals("A", SourceTexts.names(Collections.singletonList("A"), more));
    }

    @Test
    public void aSiteIsNewInTheVersionThatAddedIt() {
        assertTrue(SourceTexts.isNew("8.0", "8.0"));
        assertTrue(SourceTexts.isNew("8.0", "8.0.1"));
        assertFalse(SourceTexts.isNew("8.0", "8.1"));
        assertFalse(SourceTexts.isNew(null, "8.0"));
    }

    @Test
    public void statusColorsAreFixed() {
        assertEquals(0, SourceTexts.statusColor(null));
        assertEquals(SourceTexts.COLOR_UP, SourceTexts.statusColor(
                new SourceProbe.Result(Provider.AKNIGA, ProviderPage.Status.OK, 800, null)));
        assertEquals(SourceTexts.COLOR_DOWN, SourceTexts.statusColor(
                new SourceProbe.Result(Provider.AKNIGA, ProviderPage.Status.BLOCKED, 800, null)));
    }

    @Test
    public void theScreensWatchTheSourcesPreferences() {
        assertTrue(SourceTexts.watched("pref_sources"));
        assertTrue(SourceTexts.watched("sorce_books"));
        assertTrue(SourceTexts.watched("pref_proxy_direct"));
        assertTrue(SourceTexts.watched("source_health"));
        assertTrue(SourceTexts.watched("region"));
        assertTrue("cleared", SourceTexts.watched(null));
        assertFalse(SourceTexts.watched("pref_theme"));
    }

    @Test
    public void aCheckThatCouldNotTellGivesNoRegion() {
        assertNull("before the first check", SourceTexts.region((RegionCheck.Result) null));
        assertNull("undetermined, stored as OTHER", SourceTexts.region(
                new RegionCheck.Result(Region.OTHER, "", RegionCheck.Source.NONE, "", false)));
        assertEquals(Region.OTHER, SourceTexts.region(
                new RegionCheck.Result(Region.OTHER, "UZ", RegionCheck.Source.SERVICE, "ipinfo", false)));
        assertEquals(Region.RUSSIA, SourceTexts.region(
                new RegionCheck.Result(Region.RUSSIA, "", RegionCheck.Source.TIME_ZONE, "", false)));
    }
}
