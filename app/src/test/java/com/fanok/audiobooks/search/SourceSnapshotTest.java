package com.fanok.audiobooks.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fanok.audiobooks.Consts;
import com.fanok.audiobooks.net.AppProxy;
import com.fanok.audiobooks.net.FakePrefs;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/** The undo snapshot of the source screens: taken, changed, restored; and as text for an intent. */
public class SourceSnapshotTest {

    @After
    public void restoreDefaults() {
        Sources.load(new FakePrefs());
        Consts.setSOURCE(Provider.AKNIGA.prefValue);
    }

    @Test
    public void theTextIsReadBack() {
        List<Provider> order = new ArrayList<>(Arrays.asList(Provider.values()));
        Collections.reverse(order);
        SourceSnapshot snapshot = new SourceSnapshot(order, 5, Provider.BIBLUS.prefValue,
                new HashSet<>(Arrays.asList(Provider.AKNIGA.prefValue, Provider.OTRUB.prefValue)));
        assertEquals(snapshot, SourceSnapshot.decode(snapshot.encode()));
        SourceSnapshot bare = new SourceSnapshot(order, 1, null, Collections.<String>emptySet());
        assertEquals(bare, SourceSnapshot.decode(bare.encode()));
        SourceSnapshot handSet = new SourceSnapshot(order, 2, null, Collections.singleton(Provider.AKNIGA.prefValue),
                new HashSet<>(Arrays.asList(Provider.UKNIG.prefValue, Provider.GOLOSOM.prefValue)));
        assertEquals(handSet, SourceSnapshot.decode(handSet.encode()));
        assertFalse("the hand-set sites count", handSet.equals(new SourceSnapshot(order, 2, null,
                Collections.singleton(Provider.AKNIGA.prefValue))));
        String older = Sources.format(order, 2) + "\n\n" + Provider.AKNIGA.prefValue;
        assertEquals("an older text: the direct sites were the hand-set ones",
                new SourceSnapshot(order, 2, null, Collections.singleton(Provider.AKNIGA.prefValue)),
                SourceSnapshot.decode(older));
        assertNull(SourceSnapshot.decode(null));
        assertNull(SourceSnapshot.decode("abook,izibuc|otrub"));
    }

    @Test
    public void restoringPutsBackTheOrderTheCountTheCatalogAndTheRoutes() {
        FakePrefs prefs = new FakePrefs();
        Sources.load(prefs);
        Consts.setSOURCE(Provider.KNIGAVUHE.prefValue);
        prefs.edit().putString(Sources.SELECTED, Provider.KNIGAVUHE.prefValue).apply();
        SourceSnapshot before = SourceSnapshot.take(prefs);

        // the card: KNIGAVUHE off (the catalog moves to the first active), a site moved, another set direct
        Sources.setActive(prefs, Provider.KNIGAVUHE, false);
        Sources.moveTo(prefs, Provider.GOLOSOM, 0);
        AppProxy.setDirect(prefs, Provider.IZIBUK, true);
        assertFalse(Sources.isActive(Provider.KNIGAVUHE));
        assertEquals(Provider.GOLOSOM, Sources.all().get(0));

        before.restore(prefs);
        assertEquals(before.order(), Sources.all());
        assertEquals(before.active, Sources.activeCount());
        assertTrue(Sources.isActive(Provider.KNIGAVUHE));
        assertEquals(Provider.KNIGAVUHE, Provider.current());
        assertEquals(Provider.KNIGAVUHE.prefValue, prefs.getString(Sources.SELECTED, null));
        assertEquals(Collections.<String>emptySet(), AppProxy.readDirect(prefs));
        assertEquals("the route is no longer hand-set", Collections.<String>emptySet(), AppProxy.readManual(prefs));
        assertEquals(before, SourceSnapshot.take(prefs));
    }

    @Test
    public void undoingARouteBringsBackTheRegionsDefault() {
        FakePrefs prefs = new FakePrefs();
        Sources.load(prefs);
        Consts.setSOURCE(Provider.AKNIGA.prefValue);
        // the region's default: AKNIGA direct, not by hand
        prefs.edit().putStringSet(AppProxy.PREF_DIRECT, Collections.singleton(Provider.AKNIGA.prefValue))
                .putStringSet(AppProxy.PREF_MANUAL, Collections.singleton(Provider.UKNIG.prefValue)).apply();
        SourceSnapshot before = SourceSnapshot.take(prefs);

        AppProxy.setDirect(prefs, Provider.AKNIGA, false);
        SourceSnapshot after = SourceSnapshot.take(prefs);
        assertTrue(AppProxy.readManual(prefs).contains(Provider.AKNIGA.prefValue));

        assertTrue(before.restoreIf(prefs, after));
        assertEquals(Collections.singleton(Provider.AKNIGA.prefValue), AppProxy.readDirect(prefs));
        assertEquals("AKNIGA follows the region again", Collections.singleton(Provider.UKNIG.prefValue),
                AppProxy.readManual(prefs));
    }

    @Test
    public void anUndoRestoresOnlyWhileNothingChangedSinceItsChange() {
        FakePrefs prefs = new FakePrefs();
        Sources.load(prefs);
        Consts.setSOURCE(Provider.AKNIGA.prefValue);
        prefs.edit().putString(Sources.SELECTED, Provider.AKNIGA.prefValue).apply();
        SourceSnapshot before = SourceSnapshot.take(prefs);
        Sources.moveTo(prefs, Provider.GOLOSOM, 0);
        SourceSnapshot after = SourceSnapshot.take(prefs);

        // a later edit (another screen set a route): the undo keeps it
        AppProxy.setDirect(prefs, Provider.IZIBUK, true);
        assertFalse(before.restoreIf(prefs, after));
        assertEquals(Provider.GOLOSOM, Sources.all().get(0));
        assertTrue(AppProxy.readDirect(prefs).contains(Provider.IZIBUK.prefValue));

        // switched back by hand: the route is the same, but now set by hand, which the undo would lose
        AppProxy.setDirect(prefs, Provider.IZIBUK, false);
        assertFalse(before.restoreIf(prefs, after));

        // nothing since the change: the undo puts it back
        prefs.edit().putStringSet(AppProxy.PREF_MANUAL, Collections.<String>emptySet()).apply();
        assertTrue(before.restoreIf(prefs, after));
        assertEquals(before.encode(), SourceSnapshot.take(prefs).encode());
    }

    @Test
    public void placesAndActivityOfASnapshot() {
        SourceSnapshot snapshot = new SourceSnapshot(Arrays.asList(Provider.OTRUB, Provider.AKNIGA, Provider.IZIBUK),
                2, Provider.AKNIGA.prefValue, Collections.<String>emptySet());
        assertEquals(1, snapshot.position(Provider.OTRUB));
        assertTrue(snapshot.isActive(Provider.AKNIGA));
        assertFalse(snapshot.isActive(Provider.IZIBUK));
        assertEquals(0, snapshot.position(Provider.BIBLUS));
        assertFalse(snapshot.isActive(Provider.BIBLUS));
        assertEquals(Provider.AKNIGA, snapshot.catalog());
    }
}
