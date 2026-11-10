package com.fanok.audiobooks.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.pojo.FavoritePersonPOJO;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class SnapshotTest {

    private static final String A = "https://akniga.org/a", B = "https://akniga.org/b", C = "https://akniga.org/c";

    private static Snapshot fav(String... urls) {
        Snapshot s = new Snapshot();
        for (String url : urls) {
            s.fav.put(url, 0L);
            BookPOJO book = new BookPOJO();
            book.setUrl(url);
            book.setName("Книга " + url);
            s.books.put(url, book);
        }
        return s;
    }

    @Test
    public void oneSidedChanges_spreadIncludingDeletions() {
        Snapshot base = fav(A, B);
        Snapshot merged = Snapshot.merge(base, fav(A, B, C), fav(B));
        // C added here, A removed in the cloud
        assertEquals(Arrays.asList(B, C), new java.util.ArrayList<>(merged.fav.keySet()));
        assertEquals("Книга " + C, merged.books.get(C).getName());
        assertNull(merged.books.get(A));
    }

    @Test
    public void unknownBase_isAUnion() {
        Snapshot merged = Snapshot.merge(new Snapshot(), fav(A), fav(B));
        assertEquals(2, merged.fav.size());
    }

    @Test
    public void conflicts_takeTheLaterOpeningAndPlaceTheCloudSettingAndTheEdit() {
        Snapshot base = new Snapshot(), mine = new Snapshot(), theirs = new Snapshot();
        base.hist.put(A, 1L);
        mine.hist.put(A, 5L);
        theirs.hist.put(A, 3L);
        base.pos.put(A, new Snapshot.Pos(1, 0, 1, null));
        mine.pos.put(A, new Snapshot.Pos(2, 10, 20, "Глава 2"));
        theirs.pos.put(A, new Snapshot.Pos(4, 50, 30, "Глава 4"));
        base.prefs.put("pref_theme", "dark");
        mine.prefs.put("pref_theme", "light");
        theirs.prefs.put("pref_theme", "black");
        base.hist.put(B, 1L);
        theirs.hist.put(B, 7L); // reopened there, removed here: the opening wins

        Snapshot merged = Snapshot.merge(base, mine, theirs);
        assertEquals(Long.valueOf(5), merged.hist.get(A));
        assertEquals(4, merged.pos.get(A).c);
        assertEquals("black", merged.prefs.get("pref_theme"));
        assertEquals(Long.valueOf(7), merged.hist.get(B));
    }

    @Test
    public void conflicts_aBookOnlyOpenedLosesToThePlaceReachedElsewhere() {
        Snapshot mine = new Snapshot(), theirs = new Snapshot();
        mine.pos.put(A, new Snapshot.Pos(0, 0, 99, null)); // a new device opened it a minute ago
        theirs.pos.put(A, new Snapshot.Pos(4, 50, 30, "Глава 4"));
        assertEquals(4, Snapshot.merge(new Snapshot(), mine, theirs).pos.get(A).c);
        assertEquals(4, Snapshot.merge(new Snapshot(), theirs, mine).pos.get(A).c);

        Snapshot base = new Snapshot();
        base.pos.put(A, new Snapshot.Pos(4, 50, 30, null));
        Snapshot restarted = new Snapshot();
        restarted.pos.put(A, new Snapshot.Pos(0, 0, 99, null));
        assertTrue("a start changed on one side only still spreads",
                Snapshot.merge(base, restarted, base).pos.get(A).start());
    }

    @Test
    public void people_areTheSameWhateverTheirPages() {
        Snapshot base = new Snapshot(), mine = new Snapshot(), theirs = new Snapshot();
        String key = Snapshot.person(0, "кинг|с"), gone = Snapshot.person(1, "чтец|ч");
        base.people.put(key, new FavoritePersonPOJO(0, "Стивен Кинг", Collections.emptyList(), 9));
        mine.people.put(key, new FavoritePersonPOJO(0, "Стивен Кинг", Collections.singletonList(A), 9));
        theirs.people.put(key, new FavoritePersonPOJO(0, "Кинг Стивен", Collections.singletonList(B), 4));
        base.people.put(gone, new FavoritePersonPOJO(1, "Чтец", null, 1));
        mine.people.put(gone, base.people.get(gone));
        Snapshot merged = Snapshot.merge(base, mine, theirs);
        assertEquals(Collections.singletonList(A), merged.people.get(key).getUrls());
        assertFalse(merged.people.containsKey(gone));
        assertTrue(merged.sameData(theirs)); // pages alone are nothing to upload
    }

    @Test
    public void sameData_ignoresOrderAndWhenAPlaceWasReached() {
        Snapshot one = fav(A, B), two = fav(B, A);
        one.pos.put(A, new Snapshot.Pos(1, 100, 5, null));
        two.pos.put(A, new Snapshot.Pos(1, 100, 9, "Глава"));
        assertTrue(one.sameData(two));
        two.pos.put(A, new Snapshot.Pos(1, 101, 9, null));
        assertFalse(one.sameData(two));
    }

    @Test
    public void descends_onlyFromACopyThatSawEveryWriteOfTheBase() {
        Snapshot base = new Snapshot();
        base.count("phone");
        base.count("phone"); // phone:2
        Snapshot later = new Snapshot();
        later.count("tablet", base); // phone:2, tablet:1
        assertTrue(later.descends(base));

        Snapshot stale = new Snapshot();
        stale.count("tablet"); // overwrote phone's second write without seeing it
        stale.seq.put("phone", 1);
        assertFalse(stale.descends(base));
        assertFalse(new Snapshot().descends(base)); // deleted, recreated, another account
    }

    @Test
    public void tornClock_isNoAncestry() throws Exception {
        String json = "{\"v\":1,\"seq\":{\"phone\":\"x\",\"tablet\":3},\"books\":{},\"fav\":{},\"hist\":{},"
                + "\"people\":{},\"pos\":{},\"prefs\":{}}";
        Snapshot torn = Snapshot.parse(json.getBytes(StandardCharsets.UTF_8));
        assertTrue(torn.torn);
        assertFalse(torn.descends(new Snapshot()));
    }

    @Test
    public void bytes_roundTripAndKeepWhatANewerVersionAdded() throws Exception {
        Snapshot s = fav(A);
        s.hist.put(B, 42L);
        s.pos.put(A, new Snapshot.Pos(3, 1234, 99, "Глава 3"));
        s.prefs.put("speed", "1.25");
        s.count("phone");
        Snapshot back = Snapshot.parse(s.toBytes());
        assertTrue(s.sameData(back));
        assertTrue(s.copy().sameData(back));
        assertEquals(Integer.valueOf(1), back.seq.get("phone"));
        assertEquals("Глава 3", back.pos.get(A).n);
        assertEquals(99, back.pos.get(A).t);

        String json = "{\"v\":1,\"seq\":{},\"books\":{},\"fav\":{\"" + A + "\":0,\"" + B + "\":null},"
                + "\"hist\":{\"" + A + "\":\"bad\",\"" + B + "\":7},\"people\":{},"
                + "\"pos\":{\"" + A + "\":{\"c\":\"x\"},\"" + B + "\":{\"c\":2,\"ms\":5}},\"prefs\":{},\"marks\":{\"x\":1}}";
        Snapshot plain = Snapshot.parse(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(Collections.singleton(A), plain.fav.keySet());
        assertEquals(Collections.singleton(B), plain.hist.keySet()); // a bad entry goes alone
        assertEquals(Collections.singleton(B), plain.pos.keySet());
        Snapshot again = Snapshot.parse(plain.toBytes());
        assertEquals(1, again.rest.getAsJsonObject("marks").get("x").getAsInt());
    }

    @Test
    public void parse_refusesWhatItCannotTakeForAWholeDocument() throws Exception {
        assertNull(Snapshot.parse("not json".getBytes(StandardCharsets.UTF_8)));
        assertNull(Snapshot.parse("{\"v\":1,\"seq\":{},\"books\":{},\"fav\":{}}".getBytes(StandardCharsets.UTF_8)));
        assertNull(Snapshot.parse(new byte[]{0x1f, (byte) 0x8b, 1, 2}));
        try {
            Snapshot.parse("{\"v\":2}".getBytes(StandardCharsets.UTF_8));
            fail("a newer format must not be overwritten");
        } catch (Snapshot.NewerException expected) {
        }
    }
}
