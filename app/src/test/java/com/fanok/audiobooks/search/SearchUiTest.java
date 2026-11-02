package com.fanok.audiobooks.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.pojo.SearcheblPOJO;
import com.fanok.audiobooks.pojo.SearchebleArrayPOJO;
import com.fanok.audiobooks.search.ProviderPage.Status;
import com.fanok.audiobooks.search.SearchGrouper.Offer;
import com.fanok.audiobooks.search.SearchOrder.Sort;
import com.fanok.audiobooks.search.SearchRequest.Field;
import com.fanok.audiobooks.search.SearchUi.ChipKind;
import com.fanok.audiobooks.search.SearchUi.Empty;
import com.fanok.audiobooks.search.SearchUi.Pass;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
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
        assertEquals(Arrays.asList(ChipKind.FOUND, ChipKind.RUNNING, ChipKind.EMPTY, ChipKind.TIMEOUT),
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
        assertEquals("ИзиБук, Книгоблуд and Библус are one catalog", 2, all.sites);
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
    /** The tabs: the most found first, then the searching, the empty and last the silent; equals keep their order. */
    @Test
    public void chipsSortByWhatTheSourcesFound() {
        in.sources = Arrays.asList(Provider.AKNIGA, Provider.IZIBUK, Provider.KNIGOBLUD, Provider.BIBLUS,
                Provider.GOLOSOM, Provider.KNIGAVUHE);
        state(Provider.AKNIGA, false, Status.OK, 2);
        state(Provider.IZIBUK, false, Status.OK, 12);
        state(Provider.KNIGOBLUD, false, Status.TIMEOUT, 0);
        state(Provider.BIBLUS, false, Status.EMPTY, 0);
        state(Provider.GOLOSOM, true, null, 0);
        state(Provider.KNIGAVUHE, false, Status.EMPTY, 0);
        List<Provider> order = new ArrayList<>();
        for (SearchUi.Chip c : SearchUi.reduce(in).chips) {
            order.add(c.source);
        }
        assertEquals(Arrays.asList(Provider.IZIBUK, Provider.AKNIGA, Provider.GOLOSOM, Provider.BIBLUS,
                Provider.KNIGAVUHE, Provider.KNIGOBLUD), order);
    }

    private static Offer person(Provider source, String title, String author, String authorUrl, String series,
            String seriesUrl) {
        BookPOJO b = new BookPOJO();
        b.setName(title);
        b.setAutor(author);
        b.setUrlAutor(authorUrl);
        b.setSeries(series);
        b.setUrlSeries(seriesUrl);
        b.setUrl(source.server + "/u" + (next++));
        return new Offer(b, source, source.ordinal(), next); // the card is read once, when the offer is made
    }

    private static List<String> names(List<SearchebleArrayPOJO> plates) {
        List<String> out = new ArrayList<>();
        for (SearchebleArrayPOJO p : plates) {
            out.add(p.getName());
        }
        return out;
    }

    /**
     * Plates come from every site's cards whose author or series alone holds the query, one per person however
     * spelled, merged with the sites' own lists; the most found first; a tab shows its own site's.
     */
    @Test
    public void authorAndSeriesPlatesComeFromEverySitesResults() {
        in.request = SearchRequest.anywhere("кинг");
        in.offers = Arrays.asList(
                person(Provider.AKNIGA, "Оно", "Кинг Стивен", "https://akniga.org/author/king/", null, null),
                person(Provider.IZIBUK, "Сияние", "Стивен Кинг", "https://izib.uk/author/king", null, null),
                person(Provider.AKNIGA, "Король", "Эмма Кингсли", "https://akniga.org/author/kingsley/", null, null),
                person(Provider.BIBLUS, "Талисман", "Стивен Кинг, Питер Страуб", "https://biblus.ru/a/1", null,
                        null),
                person(Provider.KNIGOBLUD, "Столпы земли", "Кен Фоллетт", "https://www.knigoblud.club/a/2",
                        "Кингсбридж", "https://www.knigoblud.club/series/kingsbridge"),
                person(Provider.AKNIGA, "Мизери", "Стивен Кинг", null, null, null));
        SearcheblPOJO server = new SearcheblPOJO();
        server.setAutorsList(new ArrayList<>(Arrays.asList(
                new SearchebleArrayPOJO("Ларри Кинг", "https://knigavuhe.org/author/larri-king/?page="),
                new SearchebleArrayPOJO("Стивен Кинг", "https://knigavuhe.org/author/stiven-king/?page="))));
        server.setSeriesList(new ArrayList<>(Arrays.asList(new SearchebleArrayPOJO("Кингсбридж Кена Фоллетта",
                "https://knigavuhe.org/series/kingsbridzh/?page="))));
        in.people = server;

        SearcheblPOJO all = SearchUi.reduce(in).plates;
        assertEquals("a field of two people names neither; a card without a link adds nothing new",
                Arrays.asList("Стивен Кинг", "Эмма Кингсли", "Ларри Кинг"), names(all.getAutorsList()));
        assertEquals("the site's own plate wins", "https://knigavuhe.org/author/stiven-king/?page=",
                all.getAutorsList().get(0).getUrl());
        assertEquals("the site's series carries its author", Arrays.asList("Кингсбридж Кена Фоллетта"),
                names(all.getSeriesList()));

        in.tab = Provider.IZIBUK;
        SearcheblPOJO tab = SearchUi.reduce(in).plates;
        assertEquals(Arrays.asList("Стивен Кинг"), names(tab.getAutorsList()));
        assertEquals("https://izib.uk/author/king", tab.getAutorsList().get(0).getUrl());
        assertTrue(tab.getSeriesList().isEmpty());

        in.tab = Provider.KNIGOBLUD;
        assertEquals(Arrays.asList("Кингсбридж"), names(SearchUi.reduce(in).plates.getSeriesList()));
    }

    /** A site's collection ("Подборка") of the same name as a book is a row of its own. */
    @Test
    public void aCollectionNeverGroupsWithABook() {
        in.request = SearchRequest.anywhere("летние приключения");
        Offer book = offer(Provider.IZIBUK, "Летние приключения", "Иванов Иван", null);
        BookPOJO c = new BookPOJO();
        c.setName("Летние приключения");
        c.setUrl("https://akniga.org/collection/letnie-priklyucheniya/");
        Offer collection = new Offer(c, Provider.AKNIGA, Provider.AKNIGA.ordinal(), 0);
        assertTrue(collection.listing);
        in.offers = Arrays.asList(book, collection);
        SearchUi.State s = SearchUi.reduce(in);
        assertEquals(2, s.rows.size());
        assertEquals(1, s.rows.get(0).group.offers.size());
        assertEquals(1, s.rows.get(1).group.offers.size());
    }
    private static Offer genre(Provider source, String title, String author, String genre) {
        BookPOJO b = new BookPOJO();
        b.setName(title);
        b.setAutor(author);
        b.setGenre(genre);
        b.setUrl(source.server + "/u" + (next++));
        return new Offer(b, source, source.ordinal(), next);
    }

    /** "Чужой" in science fiction only: another genre hides, an unknown one stays last as "no data". */
    @Test
    public void genresKeepTheirCardsAndAnUnknownGenreGoesLast() {
        in.request = SearchRequest.anywhere("чужой");
        in.genres = EnumSet.of(Genre.SF_FANTASY);
        in.offers = Arrays.asList(
                genre(Provider.AKNIGA, "Чужой", "Алан Дин Фостер", "Фантастика"),
                genre(Provider.IZIBUK, "Чужой", "Ирина Иванова", "Детективы, триллеры"),
                genre(Provider.KNIGOBLUD, "Чужой среди своих", "Пётр Петров", null));
        SearchUi.State s = SearchUi.reduce(in);
        assertEquals(2, s.rows.size());
        assertEquals("Алан Дин Фостер", s.rows.get(0).offer.book.getAutor());
        assertTrue(s.rows.get(1).unknown);
        assertEquals(1, s.unknown);
        assertEquals(1, s.hiddenByScope);

        in.genres = EnumSet.of(Genre.ROMANCE);
        in.offers = Arrays.asList(genre(Provider.AKNIGA, "Чужой", "Алан Дин Фостер", "Фантастика"));
        assertEquals(Empty.SCOPE_HID_ALL, SearchUi.reduce(in).empty);
    }
    /** Initials tell people apart; cards without a link count all the same; a series of the same start is another. */
    @Test
    public void platesKeepPeopleApartAndCountEveryCard() {
        in.request = SearchRequest.anywhere("толстой");
        in.offers = Arrays.asList(
                person(Provider.AKNIGA, "Аэлита", "А. Толстой", "https://akniga.org/author/a-tolstoy/", null, null),
                person(Provider.AKNIGA, "Война и мир", "Л. Толстой", null, null, null),
                person(Provider.IZIBUK, "Анна Каренина", "Л. Толстой", null, null, null),
                person(Provider.KNIGOBLUD, "Воскресение", "Л. Толстой", "https://www.knigoblud.club/a/l-tolstoy",
                        null, null));
        assertEquals(Arrays.asList("Л. Толстой", "А. Толстой"), names(SearchUi.reduce(in).plates.getAutorsList()));

        in.request = SearchRequest.anywhere("тень");
        in.offers = Arrays.asList(person(Provider.AKNIGA, "Тень", "Пётр Петров", null, "Тень",
                "https://akniga.org/series/ten/"));
        SearcheblPOJO server = new SearcheblPOJO();
        server.setSeriesList(new ArrayList<>(Arrays.asList(new SearchebleArrayPOJO("Тень империи Иванова",
                "https://knigavuhe.org/series/ten-imperii/?page="))));
        in.people = server;
        assertEquals(Arrays.asList("Тень", "Тень империи Иванова"),
                names(SearchUi.reduce(in).plates.getSeriesList()));
    }

    /** A work passes the genres by any of its cards: a site without a genre neither hides it nor doubles it. */
    @Test
    public void aWorkPassesTheGenresByAnyOfItsCards() {
        in.request = SearchRequest.anywhere("чужой");
        in.genres = EnumSet.of(Genre.SF_FANTASY);
        in.offers = Arrays.asList(
                genre(Provider.AKNIGA, "Чужой", "Алан Дин Фостер", "Фантастика"),
                genre(Provider.ABMP3, "Чужой", "Алан Дин Фостер", null));
        SearchUi.State s = SearchUi.reduce(in);
        assertEquals(1, s.rows.size());
        assertEquals(2, s.rows.get(0).group.offers.size());
        assertEquals(0, s.unknown);
    }
    private void running(Provider p, long startedAt) {
        states.put(p.name(), new SearchSession.State(p.name(), true, null, 0, false, startedAt));
    }

    private static boolean slow(SearchUi.State s, Provider p) {
        for (SearchUi.Chip c : s.chips) {
            if (c.source == p) {
                return c.slow;
            }
        }
        throw new AssertionError(p);
    }

    /**
     * A source still searching after 8 s is marked slow; after 20 s the pass no longer waits for it, nor ever for one
     * silent the last time; the state says when the clock alone will change it.
     */
    @Test
    public void thePassWaitsTwentySecondsAndMarksTheSlowOnes() {
        in.nowMs = 100_000;
        running(Provider.AKNIGA, 97_000);
        running(Provider.IZIBUK, 91_000);
        running(Provider.KNIGOBLUD, 79_000);
        running(Provider.BIBLUS, 99_000);
        in.silent = EnumSet.of(Provider.BIBLUS);
        SearchUi.State s = SearchUi.reduce(in);
        assertEquals(Pass.RUNNING, s.pass);
        assertEquals(Arrays.asList(Provider.KNIGOBLUD, Provider.BIBLUS), s.waiting);
        assertEquals(Arrays.asList(false, true, true, true), Arrays.asList(slow(s, Provider.AKNIGA),
                slow(s, Provider.IZIBUK), slow(s, Provider.KNIGOBLUD), slow(s, Provider.BIBLUS)));
        assertEquals("Akniga is marked at 105 s, before ИзиБук's wait ends at 111 s", 105_000, s.nextChangeAt);

        in.slow = EnumSet.of(Provider.AKNIGA);
        assertTrue("slow by nature: marked at once, still waited for", slow(SearchUi.reduce(in), Provider.AKNIGA));
        assertEquals(Pass.RUNNING, SearchUi.reduce(in).pass);

        in.nowMs = 125_000;
        SearchUi.State late = SearchUi.reduce(in);
        assertEquals(Pass.DONE, late.pass);
        assertEquals(4, late.waiting.size());
        assertEquals(Empty.WAITING, late.empty);
        assertEquals(0, late.nextChangeAt);
        assertEquals(ChipKind.RUNNING, late.chips.get(0).kind);
    }
    /** "Искать дальше" needs an idle source with a next page: one still on its page (in the background) is not it. */
    @Test
    public void onlyAnIdleSourceThatFoundSomethingHasMore() {
        states.put(Provider.AKNIGA.name(), new SearchSession.State(Provider.AKNIGA.name(), true, null, 0, true, 1));
        in.nowMs = 60_000;
        assertEquals(false, SearchUi.reduce(in).hasMore);
        states.put(Provider.AKNIGA.name(), new SearchSession.State(Provider.AKNIGA.name(), false, Status.OK, 3, true));
        assertTrue(SearchUi.reduce(in).hasMore);
    }
}
