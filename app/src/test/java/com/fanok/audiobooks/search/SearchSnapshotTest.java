package com.fanok.audiobooks.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fanok.audiobooks.pojo.BookPOJO;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SearchSnapshotTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @After
    public void tearDown() {
        SearchSnapshot.Store.forgetMemory();
    }

    private static SearchSnapshot snapshot(long savedAt, int cardsA, int cardsB) {
        SearchSnapshot s = new SearchSnapshot();
        s.savedAt = savedAt;
        s.text = "мастер и маргарита";
        s.scope = Arrays.asList("AUTHOR");
        s.sources.add(source("AKNIGA", 3));
        s.sources.add(source("IZIBUK", 2));
        for (int i = 0; i < cardsA; i++) {
            s.cards.add(card("AKNIGA", i));
        }
        for (int i = 0; i < cardsB; i++) {
            s.cards.add(card("IZIBUK", i));
        }
        return s;
    }

    private static SearchSnapshot.Source source(String name, int next) {
        SearchSnapshot.Source s = new SearchSnapshot.Source();
        s.name = name;
        s.status = "OK";
        s.nextPage = next;
        return s;
    }

    private static SearchSnapshot.Card card(String source, int i) {
        SearchSnapshot.Card c = new SearchSnapshot.Card();
        c.source = source;
        c.rank = i;
        c.book = new BookPOJO();
        c.book.setName("Книга " + i);
        c.book.setUrl("https://" + source.toLowerCase() + ".example/" + i);
        return c;
    }

    @Test
    public void roundTripThroughTheFile() throws Exception {
        File dir = folder.newFolder("search");
        SearchSnapshot s = snapshot(1000, 2, 1);
        SearchSnapshot.Store.write(s, new File(dir, "last.json"));
        SearchSnapshot back = SearchSnapshot.Store.read(new File(dir, "last.json"));
        assertEquals("мастер и маргарита", back.text);
        assertEquals(Arrays.asList("AUTHOR"), back.scope);
        assertEquals(3, back.cards.size());
        assertEquals("Книга 1", back.cards.get(1).book.getName());
        assertEquals(Integer.valueOf(3), back.sources.get(0).nextPage);
    }

    @Test
    public void aBrokenOrForeignFileReadsAsNoSnapshot() throws Exception {
        File broken = folder.newFile("broken.json");
        try (FileOutputStream out = new FileOutputStream(broken)) {
            out.write("{\"schema\":1,\"cards\":[{\"source\":".getBytes(StandardCharsets.UTF_8));
        }
        assertNull(SearchSnapshot.Store.read(broken));
        File foreign = folder.newFile("foreign.json");
        try (FileOutputStream out = new FileOutputStream(foreign)) {
            out.write("{\"schema\":99,\"text\":\"x\",\"cards\":[],\"sources\":[]}".getBytes(StandardCharsets.UTF_8));
        }
        assertNull(SearchSnapshot.Store.read(foreign));
        assertNull(SearchSnapshot.Store.read(new File(folder.getRoot(), "missing.json")));
    }

    @Test
    public void anOldSnapshotKeepsOnlyItsRequest() throws IOException {
        SearchSnapshot.Store store = new SearchSnapshot.Store(folder.newFolder("s"));
        store.save(snapshot(0, 2, 1));
        SearchSnapshot fresh = store.load(SearchSnapshot.MAX_AGE_MS - 1);
        assertEquals(3, fresh.cards.size());
        SearchSnapshot old = store.load(SearchSnapshot.MAX_AGE_MS + 1);
        assertEquals("мастер и маргарита", old.text);
        assertTrue(old.cards.isEmpty());
    }

    @Test
    public void tooManyCardsLeaveTheTailOfTheBiggestSourceAndItsNextPage() {
        SearchSnapshot s = snapshot(0, SearchSnapshot.MAX_OFFERS, 10);
        s.truncate();
        assertEquals(SearchSnapshot.MAX_OFFERS, s.cards.size());
        assertTrue(s.truncated);
        assertNull("no continuation over a hole", s.sources.get(0).nextPage);
        assertEquals("its count follows the cards left", 0, s.sources.get(0).count);
        assertEquals(Integer.valueOf(2), s.sources.get(1).nextPage);
        assertEquals("IZIBUK", s.cards.get(s.cards.size() - 1).source);
    }
}
