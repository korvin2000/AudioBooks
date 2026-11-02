package com.fanok.audiobooks.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.search.ProviderPage.Status;
import com.fanok.audiobooks.search.SearchGrouper.Offer;
import com.fanok.audiobooks.search.SearchOrder.Sort;
import com.fanok.audiobooks.search.SearchRequest.Field;
import com.fanok.audiobooks.search.SearchUi.ChipKind;
import com.fanok.audiobooks.search.SearchUi.Empty;
import com.fanok.audiobooks.search.SearchUi.Pass;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

public class SearchUiTest {

    private static int next;
    private SearchUi.Input in;
    private final Map<String, SearchSession.State> states = new HashMap<>();

    @Before
    public void setUp() {
        in = new SearchUi.Input();
        in.searched = true;
        in.request = SearchRequest.anywhere("мастер и маргарита");
        in.sources = Arrays.asList(Provider.AKNIGA, Provider.IZIBUK, Provider.KNIGOBLUD, Provider.BIBLUS);
        in.states = states;
    }

    private static Offer offer(Provider source, String title, String author, String reader) {
        BookPOJO b = new BookPOJO();
        b.setName(title);
        b.setAutor(author);
        b.setArtist(reader);
        b.setUrl(source.server + "/u" + (next++));
        return new Offer(b, source, source.ordinal(), next);
    }

    private void state(Provider p, boolean running, Status status, int count) {
        states.put(p.name(), new SearchSession.State(p.name(), running, status, count, false));
    }

    @Test
    public void chipsShowEachSourcesStateAndThePassSumsThemUp() {
        state(Provider.AKNIGA, false, Status.OK, 2);
        state(Provider.IZIBUK, false, Status.EMPTY, 0);
        state(Provider.KNIGOBLUD, true, null, 0);
        state(Provider.BIBLUS, false, Status.TIMEOUT, 0);
        SearchUi.State s = SearchUi.reduce(in);
        assertEquals(Arrays.asList(ChipKind.FOUND, ChipKind.EMPTY, ChipKind.RUNNING, ChipKind.TIMEOUT),
                Arrays.asList(s.chips.get(0).kind, s.chips.get(1).kind, s.chips.get(2).kind, s.chips.get(3).kind));
        assertEquals(Pass.RUNNING, s.pass);
        assertEquals(3, s.answered);
        assertEquals(4, s.planned);
        assertEquals(Arrays.asList(Provider.BIBLUS), s.failed);
        assertEquals(Empty.SEARCHING, s.empty);

        state(Provider.KNIGOBLUD, false, Status.OK, 0);
        assertEquals(Pass.PARTIAL, SearchUi.reduce(in).pass);
        assertEquals(Empty.NOTHING_SOME_FAILED, SearchUi.reduce(in).empty);
        state(Provider.BIBLUS, false, Status.OK, 1);
        assertEquals(Pass.DONE, SearchUi.reduce(in).pass);
        assertEquals(Empty.NOTHING, SearchUi.reduce(in).empty);
    }

    /** The status is the last page's: an empty or failed next page leaves the source "found". */
    @Test
    public void aLaterEmptyOrFailedPageKeepsWhatASourceFound() {
        state(Provider.AKNIGA, false, Status.EMPTY, 24);
        state(Provider.IZIBUK, false, Status.TIMEOUT, 5);
        state(Provider.KNIGOBLUD, false, Status.OK, 3);
        state(Provider.BIBLUS, false, Status.OK, 1);
        SearchUi.State s = SearchUi.reduce(in);
        assertEquals(ChipKind.FOUND, s.chips.get(0).kind);
        assertEquals(ChipKind.FOUND, s.chips.get(1).kind);
        assertTrue(s.failed.isEmpty());
        assertEquals(Pass.DONE, s.pass);
    }

    @Test
    public void allGroupsOneWorkAndASiteTabShowsItsOwnCards() {
        in.offers = Arrays.asList(
                offer(Provider.AKNIGA, "Мастер и Маргарита", "Булгаков Михаил", "Клюквин Александр"),
                offer(Provider.KNIGOBLUD, "Мастер и Маргарита", "Михаил Булгаков", "Клюквин Александр"),
                offer(Provider.BIBLUS, "Мастер и Маргарита", "Булгаков Михаил", "Клюквин Александр"),
                offer(Provider.IZIBUK, "Белая гвардия", "Булгаков Михаил", null));
        SearchUi.State all = SearchUi.reduce(in);
        assertEquals(2, all.rows.size());
        assertEquals("the match first", "Мастер и Маргарита", all.rows.get(0).offer.book.getName());
        assertEquals(3, all.rows.get(0).group.offers.size());
        assertEquals("Книгоблуд and Библус are one catalog", 3, all.sites);
        assertEquals(2, all.allCount);
        assertNotNull(all.row(all.rows.get(0).key));

        in.tab = Provider.BIBLUS;
        SearchUi.State tab = SearchUi.reduce(in);
        assertEquals(1, tab.rows.size());
        assertNull(tab.rows.get(0).group);
        assertEquals("All keeps its own count on a tab", 2, tab.allCount);
        assertTrue(tab.chips.get(3).selected);
    }

    /** W04: an empty tab says why, by the state of its own source. */
    @Test
    public void anEmptyTabExplainsItself() {
        in.offers = Arrays.asList(offer(Provider.AKNIGA, "Мастер и Маргарита", "Булгаков", null));
        state(Provider.AKNIGA, false, Status.OK, 1);
        state(Provider.IZIBUK, false, Status.EMPTY, 0);
        state(Provider.KNIGOBLUD, true, null, 0);
        state(Provider.BIBLUS, false, Status.BLOCKED, 0);
        in.tab = Provider.IZIBUK;
        assertEquals(Empty.TAB_NOTHING, SearchUi.reduce(in).empty);
        in.tab = Provider.KNIGOBLUD;
        assertEquals(Empty.TAB_SEARCHING, SearchUi.reduce(in).empty);
        in.tab = Provider.BIBLUS;
        assertEquals(Empty.TAB_FAILED, SearchUi.reduce(in).empty);
        assertEquals(ChipKind.BLOCKED, SearchUi.reduce(in).tab.kind);
        in.tab = null;
        assertEquals(Empty.NONE, SearchUi.reduce(in).empty);
    }

    @Test
    public void aScopeKeepsMatchesAndCardsWithoutTheFieldAndHidesTheRest() {
        in.request = new SearchRequest("князев", EnumSet.of(Field.READER));
        in.offers = Arrays.asList(
                offer(Provider.AKNIGA, "Пикник на обочине", "Стругацкие", "Князев Игорь"),
                offer(Provider.IZIBUK, "Князев и другие", "Иванов", "Петров Пётр"),
                offer(Provider.KNIGOBLUD, "Обитаемый остров", "Стругацкие", null));
        SearchUi.State s = SearchUi.reduce(in);
        assertEquals(2, s.rows.size());
        assertEquals("Пикник на обочине", s.rows.get(0).offer.book.getName());
        assertTrue("no reader on the card: kept, last", s.rows.get(1).unknown);
        assertEquals(1, s.unknown);
        assertEquals("the title matched, not the reader", 1, s.hiddenByScope);

        in.offers = Arrays.asList(offer(Provider.IZIBUK, "Князев и другие", "Иванов", "Петров Пётр"));
        state(Provider.AKNIGA, false, Status.OK, 0);
        state(Provider.IZIBUK, false, Status.OK, 1);
        state(Provider.KNIGOBLUD, false, Status.EMPTY, 0);
        state(Provider.BIBLUS, false, Status.EMPTY, 0);
        assertEquals(Empty.SCOPE_HID_ALL, SearchUi.reduce(in).empty);
    }

    @Test
    public void ungroupedAllListsCardsAndSortsThem() {
        in.grouped = false;
        in.sort = Sort.TITLE;
        in.offers = Arrays.asList(
                offer(Provider.AKNIGA, "Мастер и Маргарита", "Булгаков", null),
                offer(Provider.KNIGOBLUD, "Мастер и Маргарита", "Булгаков", null),
                offer(Provider.IZIBUK, "Белая гвардия", "Булгаков", null));
        SearchUi.State s = SearchUi.reduce(in);
        assertEquals(3, s.rows.size());
        assertEquals("Белая гвардия", s.rows.get(0).offer.book.getName());
    }

    /** A row keeps its key while shown, even when a card with a smaller URL joins its group later. */
    @Test
    public void aShownRowKeepsItsKeyWhenItsGroupGrows() {
        Offer late = offer(Provider.IZIBUK, "Мастер и Маргарита", "Булгаков Михаил", null);
        Offer early = offer(Provider.KNIGOBLUD, "Мастер и Маргарита", "Булгаков Михаил", null);
        Offer smaller = offer(Provider.AKNIGA, "Мастер и Маргарита", "Михаил Булгаков", null);
        assertTrue(smaller.url().compareTo(early.url()) < 0 && smaller.url().compareTo(late.url()) < 0);
        in.offers = Arrays.asList(late, early);
        SearchUi.State first = SearchUi.reduce(in);
        String key = first.rows.get(0).key;
        in.shownIds = new java.util.HashSet<>(Arrays.asList(key));
        in.offers = Arrays.asList(late, early, smaller);
        SearchUi.State second = SearchUi.reduce(in);
        assertEquals(1, second.rows.size());
        assertEquals(key, second.rows.get(0).key);
        assertNotNull(second.row(key));
        assertEquals(3, second.rows.get(0).group.offers.size());
    }

    @Test
    public void beforeASearchAndWithoutSources() {
        in.searched = false;
        assertEquals(Empty.START, SearchUi.reduce(in).empty);
        assertEquals(Pass.IDLE, SearchUi.reduce(in).pass);
        in.searched = true;
        in.sources = Arrays.asList();
        assertEquals(Empty.NO_SOURCES, SearchUi.reduce(in).empty);
        assertEquals(Pass.NO_SOURCES, SearchUi.reduce(in).pass);
    }
}
