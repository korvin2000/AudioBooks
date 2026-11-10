package com.fanok.audiobooks.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fanok.audiobooks.Consts;
import com.fanok.audiobooks.net.FakePrefs;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** The user's sources: the stored order and active count, read back safely; the selected source stays active. */
public class SourcesTest {

    @After
    public void restoreDefaults() {
        Sources.load(new FakePrefs());
        Consts.setSOURCE(Provider.AKNIGA.prefValue);
    }

    @Test
    public void nothingStoredIsTheDeclarationOrderWithTheFirstThirteenActive() {
        Sources.State state = Sources.parse(null);
        assertEquals(Arrays.asList(Provider.values()), state.order);
        assertEquals(Math.min(Sources.DEFAULT_MAX_ACTIVE, Provider.values().length), state.active);
    }

    @Test
    public void theStoredTextIsReadBack() {
        List<Provider> order = new ArrayList<>(Arrays.asList(Provider.values()));
        java.util.Collections.reverse(order);
        Sources.State state = Sources.parse(Sources.format(order, 3));
        assertEquals(order, state.order);
        assertEquals(3, state.active);
        assertEquals("all can be active", order.size(), Sources.parse(Sources.format(order, order.size())).active);
    }

    @Test
    public void unknownAndRepeatedIdsAreDroppedAndAMissingSiteStartsInactive() {
        Sources.State state = Sources.parse("uknig,gone_site,uknig,abook|izibuc");
        assertEquals(Arrays.asList(Provider.UKNIG, Provider.AKNIGA, Provider.IZIBUK),
                state.order.subList(0, 3));
        assertEquals(2, state.active);
        assertEquals(Provider.values().length, state.order.size());
    }

    @Test
    public void theActiveCountStaysBetweenOneAndAll() {
        assertEquals("none active: the first one is", 1, Sources.parse("|abook,uknig").active);
        assertEquals(1, Sources.parse(Sources.format(Arrays.asList(Provider.values()), 0)).active);
        assertEquals(1, Sources.parse("garbage").active);
        StringBuilder all = new StringBuilder();
        for (Provider provider : Provider.values()) {
            all.append(provider.prefValue).append(',');
        }
        assertEquals("no bar: every listed one", Provider.values().length, Sources.parse(all.toString()).active);
    }

    @Test
    public void switchingOffTheSelectedSourceSelectsTheFirstActiveOne() {
        FakePrefs prefs = new FakePrefs();
        prefs.edit().putString("sorce_books", Provider.UKNIG.prefValue).apply();
        List<Provider> order = Arrays.asList(Provider.IZIBUK, Provider.AKNIGA, Provider.UKNIG);
        assertFalse("the selected one stays active", Sources.save(prefs, order, 3));
        assertTrue(Sources.save(prefs, order, 2));
        assertEquals(Provider.IZIBUK.prefValue, prefs.getString("sorce_books", null));
        assertEquals(Provider.IZIBUK.sourceId, Consts.getSOURCE());
        assertEquals(Arrays.asList(Provider.IZIBUK, Provider.AKNIGA), Sources.active());
        assertFalse(Sources.isActive(Provider.UKNIG));
        assertTrue("the rest follow, inactive", Sources.all().containsAll(Arrays.asList(Provider.values())));
    }

    /** Nothing stored: the default catalog (Akniga) counts as selected, so switching it off selects another one. */
    @Test
    public void switchingOffTheDefaultCatalogWithNothingStoredSelectsTheFirstActiveOne() {
        FakePrefs prefs = new FakePrefs();
        Sources.load(prefs);
        assertNull("the default is active: nothing is written", prefs.getString(Sources.SELECTED, null));
        assertTrue(Sources.setActive(prefs, Provider.AKNIGA, false));
        Provider first = Sources.active().get(0);
        assertEquals(first.prefValue, prefs.getString(Sources.SELECTED, null));
        assertEquals(first.sourceId, Consts.getSOURCE());
    }

    @Test
    public void aSourceMovedToAPositionShiftsTheOthersAndTheActiveCountStays() {
        Sources.State s = new Sources.State(Arrays.asList(Provider.AKNIGA, Provider.KNIGAVUHE, Provider.IZIBUK,
                Provider.ABMP3, Provider.OTRUB), 3);
        Sources.State up = Sources.moved(s, Provider.IZIBUK, 0);
        assertEquals(Arrays.asList(Provider.IZIBUK, Provider.AKNIGA, Provider.KNIGAVUHE, Provider.ABMP3,
                Provider.OTRUB), up.order);
        assertEquals(3, up.active);
        Sources.State in = Sources.moved(s, Provider.OTRUB, 1);
        assertEquals("an inactive one moved in pushes the last active out",
                Arrays.asList(Provider.AKNIGA, Provider.OTRUB, Provider.KNIGAVUHE), in.order.subList(0, in.active));
        Sources.State out = Sources.moved(s, Provider.AKNIGA, 3);
        assertEquals("an active one moved out lets the first inactive in",
                Arrays.asList(Provider.KNIGAVUHE, Provider.IZIBUK, Provider.ABMP3), out.order.subList(0, out.active));
        assertEquals("clamped", Provider.AKNIGA, Sources.moved(s, Provider.AKNIGA, 99).order.get(4));
    }

    @Test
    public void aSourceSwitchedOnIsTheLastActiveAndOffTheFirstInactive() {
        List<Provider> five = Arrays.asList(Provider.AKNIGA, Provider.KNIGAVUHE, Provider.IZIBUK, Provider.ABMP3,
                Provider.OTRUB);
        Sources.State on = Sources.switched(new Sources.State(five, 2), Provider.OTRUB, true);
        assertEquals(Arrays.asList(Provider.AKNIGA, Provider.KNIGAVUHE, Provider.OTRUB), on.order.subList(0, 3));
        assertEquals(3, on.active);
        Sources.State off = Sources.switched(new Sources.State(five, 3), Provider.AKNIGA, false);
        assertEquals(Arrays.asList(Provider.KNIGAVUHE, Provider.IZIBUK, Provider.AKNIGA), off.order.subList(0, 3));
        assertEquals(2, off.active);
        Sources.State only = new Sources.State(five, 1);
        assertEquals("the only active one stays on", only, Sources.switched(only, Provider.AKNIGA, false));
        Sources.State already = new Sources.State(five, 2);
        assertEquals(already, Sources.switched(already, Provider.AKNIGA, true));
    }

    @Test
    public void aSourceSwitchedOnPushesNoSiteOut() {
        Sources.State before = Sources.parse(null);
        Provider extra = before.order.get(Sources.DEFAULT_MAX_ACTIVE + 2);
        Sources.State on = Sources.switched(before, extra, true);
        assertEquals(Sources.DEFAULT_MAX_ACTIVE + 1, on.active);
        assertEquals(extra, on.order.get(Sources.DEFAULT_MAX_ACTIVE));
        assertEquals("the active ones stay", before.order.subList(0, Sources.DEFAULT_MAX_ACTIVE),
                on.order.subList(0, Sources.DEFAULT_MAX_ACTIVE));
        Sources.State all = new Sources.State(before.order, before.order.size() - 1);
        assertEquals("the last one too", all.order.size(),
                Sources.switched(all, all.order.get(all.order.size() - 1), true).active);
    }

    @Test
    public void aCountActivatesTheFirstSitesAndARoundTripRestoresTheSameSet() {
        FakePrefs prefs = new FakePrefs();
        Sources.load(prefs);
        List<Provider> order = Sources.all();
        List<Provider> thirteen = new ArrayList<>(Sources.active());
        Sources.save(prefs, Sources.all(), 5);
        assertEquals(order.subList(0, 5), Sources.active());
        Sources.save(prefs, Sources.all(), 13);
        assertEquals(thirteen, Sources.active());
        assertEquals("the order never changes", order, Sources.all());
        Sources.save(prefs, Sources.all(), order.size());
        assertEquals("all can be active", order, Sources.active());
    }

    @Test
    public void aCountBelowTheCatalogSourceSelectsTheFirstSite() {
        FakePrefs prefs = new FakePrefs();
        Sources.load(prefs);
        Provider eighth = Sources.active().get(7);
        prefs.edit().putString(Sources.SELECTED, eighth.prefValue).apply();
        Consts.setSOURCE(eighth.prefValue);
        assertFalse("still active", Sources.save(prefs, Sources.all(), 8));
        assertTrue(Sources.save(prefs, Sources.all(), 7));
        Provider first = Sources.all().get(0);
        assertEquals(first.prefValue, prefs.getString(Sources.SELECTED, null));
        assertEquals(first.sourceId, Consts.getSOURCE());
    }

    @Test
    public void theOldLimitCutsTheStoredCountOnceAndGoes() {
        FakePrefs prefs = new FakePrefs();
        List<Provider> order = Arrays.asList(Provider.values());
        prefs.edit().putString(Sources.PREF, Sources.format(order, 20)).putString(Sources.OLD_LIMIT, "13").apply();
        Sources.load(prefs);
        assertEquals(13, Sources.activeCount());
        assertEquals("the order stays", order, Sources.all());
        assertEquals("stored", Sources.format(order, 13), prefs.getString(Sources.PREF, null));
        assertFalse("removed", prefs.contains(Sources.OLD_LIMIT));
        Sources.save(prefs, order, 20);
        Sources.load(prefs);
        assertEquals("once: no limit after it", 20, Sources.activeCount());
    }

    @Test
    public void theOldLimitSwitchesNothingOnAndAnUnreadableOneIsTheOldDefault() {
        FakePrefs prefs = new FakePrefs();
        prefs.edit().putString(Sources.PREF, "otrub,abook|uknig").putString(Sources.OLD_LIMIT, "20").apply();
        Sources.load(prefs);
        assertEquals(Arrays.asList(Provider.OTRUB, Provider.AKNIGA), Sources.active());
        prefs = new FakePrefs();
        prefs.edit().putString(Sources.PREF, Sources.format(Arrays.asList(Provider.values()), 20))
                .putInt(Sources.OLD_LIMIT, 5).apply();
        Sources.load(prefs);
        assertEquals("a wrongly typed value", Sources.DEFAULT_MAX_ACTIVE, Sources.activeCount());
        assertFalse(prefs.contains(Sources.OLD_LIMIT));
    }

    @Test
    public void theOldLimitThatCutsTheSelectedSourceSelectsTheFirstActiveOne() {
        FakePrefs prefs = new FakePrefs();
        Sources.load(prefs);
        Provider eighth = Sources.active().get(7);
        prefs.edit().putString(Sources.SELECTED, eighth.prefValue).putString(Sources.OLD_LIMIT, "5").apply();
        Consts.setSOURCE(eighth.prefValue);
        Sources.load(prefs);
        Provider first = Sources.active().get(0);
        assertEquals(5, Sources.activeCount());
        assertEquals(first.prefValue, prefs.getString(Sources.SELECTED, null));
        assertEquals(first.sourceId, Consts.getSOURCE());
    }

    @Test
    public void theCardsChangesAreStored() {
        FakePrefs prefs = new FakePrefs();
        prefs.edit().putString(Sources.PREF, "otrub,abook,uknig|izibuc").apply();
        Sources.load(prefs);
        Sources.moveTo(prefs, Provider.UKNIG, 0);
        assertEquals(Arrays.asList(Provider.UKNIG, Provider.OTRUB, Provider.AKNIGA), Sources.active());
        Sources.setActive(prefs, Provider.IZIBUK, true);
        assertEquals(4, Sources.activeCount());
        Sources.load(prefs);
        assertEquals(Arrays.asList(Provider.UKNIG, Provider.OTRUB, Provider.AKNIGA, Provider.IZIBUK),
                Sources.active());
    }

    @Test
    public void theListIsLoadedFromThePreferences() {
        FakePrefs prefs = new FakePrefs();
        prefs.edit().putString(Sources.PREF, "otrub,abook|uknig").apply();
        Sources.load(prefs);
        assertEquals(Arrays.asList(Provider.OTRUB, Provider.AKNIGA), Sources.active());
        Consts.setSOURCE(Provider.AKNIGA.prefValue);
        assertEquals("the selected one first, then the user's order", Arrays.asList(Provider.AKNIGA, Provider.OTRUB),
                Provider.selectedFirst());
        prefs.edit().putInt(Sources.PREF, 5).apply();
        Sources.load(prefs); // a wrongly typed value: the defaults
        assertEquals(Sources.parse(null).active, Sources.activeCount());
    }

    // auto-ranking: sites of separate catalogs, unless a test is about mirrors
    private static final Provider A = Provider.KNIGAVUHE;
    private static final Provider B = Provider.BAZA_KNIG;
    private static final Provider C = Provider.AUDIOBOO;
    private static final Provider D = Provider.BOOKISH;
    private static final Provider E = Provider.AKNIG_XYZ;
    private static final Provider F = Provider.SLUSHAT_KNIGI;

    private final Map<Provider, SourceProbe.Result> results = new EnumMap<>(Provider.class);
    private final Map<Provider, Integer> books = new EnumMap<>(Provider.class);

    private void up(Provider provider, long millis, boolean proxied, Integer count) {
        results.put(provider, new SourceProbe.Result(provider, ProviderPage.Status.OK, millis, null, 1, proxied));
        if (count != null) {
            books.put(provider, count);
        }
    }

    private void down(Provider provider, ProviderPage.Status status) {
        results.put(provider, new SourceProbe.Result(provider, status, 50, null, 1, false));
    }

    private List<Provider> ranked(Provider... order) {
        return Sources.ranked(Arrays.asList(order), results, books);
    }

    @Test
    public void withinThirtyFivePercentTheSiteWithMoreBooksRanksHigher() {
        up(A, 100, false, 10);
        up(B, 135, false, 1000);
        assertEquals("135 ms is 35% above 100 ms: the same speed", Arrays.asList(B, A), ranked(A, B));
    }

    @Test
    public void justOverThirtyFivePercentTheFasterSiteRanksHigher() {
        up(A, 100, false, 10);
        up(B, 136, false, 1000);
        assertEquals(Arrays.asList(A, B), ranked(A, B));
        assertEquals("whatever the user's order", Arrays.asList(A, B), ranked(B, A));
    }

    @Test
    public void aGroupIsMeasuredFromItsFastestSiteNotFromItsLast() {
        up(A, 100, false, 1);
        up(B, 130, false, 5);
        up(C, 170, false, 100); // within 35% of B, not of A: the next group
        assertEquals(Arrays.asList(B, A, C), ranked(A, B, C));
    }

    @Test
    public void withinAGroupEqualBooksGoByTimeAndUnknownBooksCountAsNone() {
        up(A, 120, false, null);
        up(B, 100, false, 0);
        up(C, 130, false, 5);
        assertEquals(Arrays.asList(C, B, A), ranked(A, B, C));
    }

    @Test
    public void equalTimesKeepTheUsersOrder() {
        up(A, 100, false, 7);
        up(B, 100, false, 7);
        assertEquals(Arrays.asList(A, B), ranked(A, B));
        assertEquals(Arrays.asList(B, A), ranked(B, A));
    }

    @Test
    public void aSiteThroughTheProxyMustBeTwiceAsFast() {
        up(A, 190, false, 0);
        up(B, 100, true, 0); // counts as 200 ms
        assertEquals(Arrays.asList(A, B), ranked(B, A));
        results.clear();
        up(A, 210, false, 0);
        up(B, 100, true, 0);
        assertEquals(Arrays.asList(B, A), ranked(A, B));
        results.clear();
        books.clear();
        up(A, 100, false, 1);
        up(B, 100, true, 1000); // 200 ms: out of A's group, its books do not count
        assertEquals(Arrays.asList(A, B), ranked(B, A));
    }

    @Test
    public void fourReadMustBeThreeTimesAsFast() {
        up(A, 290, false, 0);
        up(Provider.FOUR_READ, 100, false, 0); // counts as 300 ms
        assertEquals(Arrays.asList(A, Provider.FOUR_READ), ranked(Provider.FOUR_READ, A));
        results.clear();
        up(A, 310, false, 0);
        up(Provider.FOUR_READ, 100, false, 0);
        assertEquals(Arrays.asList(Provider.FOUR_READ, A), ranked(A, Provider.FOUR_READ));
    }

    @Test
    public void aZeroTimeCountsAsOneMillisecond() {
        up(A, 0, false, 0);
        up(B, 1, false, 100);
        assertEquals("the same time: more books first", Arrays.asList(B, A), ranked(A, B));
    }

    @Test
    public void mirrorsOfAHigherRankedSiteFollowAllTheUniqueOnes() {
        up(Provider.GOLOSKNIG, 50, false, 1000); // a mirror of Akniga
        up(Provider.AKNIGA, 100, false, 1000);
        up(Provider.IZIBUK, 400, false, 10);
        up(Provider.OTRUB, 500, false, 10); // a mirror of Izibuk
        up(A, 1000, false, 10);
        down(B, ProviderPage.Status.ERROR);
        assertEquals(Arrays.asList(Provider.GOLOSKNIG, Provider.IZIBUK, A, Provider.AKNIGA, Provider.OTRUB, B),
                ranked(B, Provider.AKNIGA, Provider.OTRUB, Provider.GOLOSKNIG, A, Provider.IZIBUK));
    }

    @Test
    public void theSitesThatDoNotWorkFollowInTheUsersOrder() {
        down(A, ProviderPage.Status.ERROR);
        down(C, ProviderPage.Status.EMPTY);
        down(D, ProviderPage.Status.TIMEOUT);
        down(E, ProviderPage.Status.BLOCKED);
        down(F, ProviderPage.Status.RATE_LIMITED);
        up(Provider.AKNIGA, 300, true, 5);
        // B has no result
        assertEquals(Arrays.asList(Provider.AKNIGA, F, E, A, B, D, C),
                ranked(F, E, A, Provider.AKNIGA, B, D, C));
        assertEquals(1, Sources.working(Arrays.asList(A, B, C, D, E, F, Provider.AKNIGA), results));
        assertFalse(Sources.works(results.get(C)));
        assertFalse(Sources.works(null));
        assertTrue(Sources.works(results.get(Provider.AKNIGA)));
    }

    @Test
    public void theFailedSitesBecomeInactiveButTheChosenCountIsNotRaised() {
        assertEquals(3, Sources.rankedActive(5, 3));
        assertEquals(2, Sources.rankedActive(2, 10));
        assertEquals("at least one stays active", 1, Sources.rankedActive(5, 0));
    }

    @Test
    public void noResultsKeepTheUsersOrder() {
        assertEquals(Arrays.asList(C, A, B), ranked(C, A, B));
        assertEquals(0, Sources.working(Arrays.asList(C, A, B), results));
        assertEquals(Collections.<Provider>emptyList(), ranked());
    }
}
