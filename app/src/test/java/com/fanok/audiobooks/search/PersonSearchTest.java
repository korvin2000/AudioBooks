package com.fanok.audiobooks.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.pojo.GenrePOJO;
import com.fanok.audiobooks.search.PersonSearch.Mode;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** The books of one person on every site: the plan, the identity rules, the sources (no network). */
public class PersonSearchTest {

    private static final String KING_AKNIGA = "https://akniga.org/author/king/page<page>/";
    private static final String KING_IZIBUK = "https://izib.uk/author123";

    private static TextNorm.Person p(String name) {
        return TextNorm.person(name);
    }

    private static BookPOJO book(String url, String author, String urlAuthor, String reader, String urlReader) {
        BookPOJO b = new BookPOJO();
        b.setName("Книга " + url);
        b.setUrl(url);
        b.setAutor(author);
        b.setUrlAutor(urlAuthor);
        b.setArtist(reader);
        b.setUrlArtist(urlReader);
        return b;
    }

    /** The sites from memory: every call is written down; a listing page is two cards of "url#page". */
    private static final class Net implements PersonSearch.Net {
        final List<String> asked = new ArrayList<>();
        ProviderPage<GenrePOJO> people = ProviderPage.empty();
        IOException peopleFail;
        ProviderPage<BookPOJO> books = ProviderPage.empty();
        /** When set, the book search's pages 1, 2, ...; empty after them. */
        List<ProviderPage<BookPOJO>> bookPages;
        /** A listing that lists nothing (a moved page). */
        String emptyListing;

        @Override
        public ProviderPage<BookPOJO> listing(Provider p, String url, int page) {
            asked.add("listing " + url + " " + page);
            if (url.equals(emptyListing)) {
                return ProviderPage.empty();
            }
            return ProviderPage.of(Arrays.asList(book(p.server + "/l" + page + "a", null, null, null, null),
                    book(p.server + "/l" + page + "b", null, null, null, null)), page < 3);
        }

        @Override
        public ProviderPage<GenrePOJO> people(Provider p, boolean reader, String name) throws IOException {
            asked.add((reader ? "readers " : "authors ") + name);
            if (peopleFail != null) {
                throw peopleFail;
            }
            return people;
        }

        @Override
        public ProviderPage<BookPOJO> books(Provider p, String query, int page) {
            asked.add("books " + query + " " + page);
            if (bookPages != null) {
                return page <= bookPages.size() ? bookPages.get(page - 1) : ProviderPage.<BookPOJO>empty();
            }
            return books;
        }
    }

    private static Map<Provider, Mode> modes(List<PersonSearch.Source> sources) {
        Map<Provider, Mode> out = new EnumMap<>(Provider.class);
        for (PersonSearch.Source s : sources) {
            out.put(s.provider, s.mode);
        }
        return out;
    }

    @Test
    public void theWayOfEverySiteIsPlannedUpFront() {
        Map<Provider, String> urls = new EnumMap<>(Provider.class);
        urls.put(Provider.IZIBUK, KING_IZIBUK);
        List<Provider> sites = Arrays.asList(Provider.AKNIGA, Provider.IZIBUK, Provider.ABMP3, Provider.GOLOSOM,
                Provider.KNIGOBLUD);

        Map<Provider, Mode> author = modes(PersonSearch.plan("Стивен Кинг", false, urls, sites, new Net(), null));
        assertEquals(Mode.RESOLVE, author.get(Provider.AKNIGA)); // a people search
        assertEquals(Mode.LISTING, author.get(Provider.IZIBUK)); // a known page
        assertEquals(Mode.RESOLVE, author.get(Provider.ABMP3)); // authors only
        assertEquals(Mode.SEARCH, author.get(Provider.GOLOSOM));
        assertEquals(Mode.SEARCH, author.get(Provider.KNIGOBLUD));
        assertEquals(5, author.size());

        // a reader: the book search only where it finds readers; ABMP3 has no reader search, Knigoblud no reader
        Map<Provider, Mode> reader = modes(PersonSearch.plan("Игорь Князев", true, urls, sites, new Net(), null));
        assertEquals(Mode.RESOLVE, reader.get(Provider.AKNIGA));
        assertEquals(Mode.LISTING, reader.get(Provider.IZIBUK));
        assertEquals(Mode.SEARCH, reader.get(Provider.GOLOSOM));
        assertFalse(reader.containsKey(Provider.ABMP3));
        assertFalse(reader.containsKey(Provider.KNIGOBLUD));
        assertEquals(Collections.singleton(Provider.GOLOSOM), PersonSearch.READER_SEARCH);
    }

    @Test
    public void onlyTheBookSearchIsAskedAgainInTheOtherLetterCase() {
        List<PersonSearch.Source> plan = PersonSearch.plan("Кинг", false,
                Collections.singletonMap(Provider.IZIBUK, KING_IZIBUK),
                Arrays.asList(Provider.IZIBUK, Provider.AKNIGA, Provider.GOLOSOM), new Net(), null);
        assertFalse(plan.get(0).caseRetry());
        assertFalse(plan.get(1).caseRetry());
        assertTrue(plan.get(2).caseRetry());
        assertTrue(plan.get(0).listing());
        assertFalse(plan.get(1).listing());
    }

    @Test
    public void theLookupTakesTheOneHitOfTheVeryName() {
        TextNorm.Person king = p("Стивен Кинг");
        String u1 = "https://akniga.org/author/king/";
        String u2 = "https://akniga.org/author/owen/";
        assertEquals(u1, PersonSearch.resolve(king, Arrays.asList(
                new GenrePOJO("Оуэн Кинг", u2), new GenrePOJO("Кинг Стивен", u1))));
        assertEquals("the same page twice is one hit", u1, PersonSearch.resolve(king, Arrays.asList(
                new GenrePOJO("Стивен Кинг", u1), new GenrePOJO("Кинг Стивен", u1))));
        assertNull("namesakes are never guessed", PersonSearch.resolve(king, Arrays.asList(
                new GenrePOJO("Стивен Кинг", u1), new GenrePOJO("Кинг Стивен", u2))));
        assertNull("a near name is no identity", PersonSearch.resolve(king, Arrays.asList(
                new GenrePOJO("С. Кинг", u1), new GenrePOJO("Стивен Эдвин Кинг", u2))));
        assertNull(PersonSearch.resolve(king, Collections.<GenrePOJO>emptyList()));
    }

    @Test
    public void aOneWordNameIsThatOneWordAlone() {
        TextNorm.Person king = p("Кинг");
        assertTrue(PersonSearch.matches(king, p("Кинг")));
        assertTrue(PersonSearch.matches(king, p("С. Кинг")));
        assertFalse(PersonSearch.matches(king, p("Оуэн Кинг")));
        assertFalse(PersonSearch.matches(king, p("Стивен Кинг")));
        assertFalse(PersonSearch.matches(king, p("Кингсли")));
    }

    @Test
    public void aLongerNameNeedsItsWordsAndAgreeingInitials() {
        TextNorm.Person stephen = p("Стивен Кинг");
        assertTrue(PersonSearch.matches(stephen, p("Кинг Стивен")));
        assertTrue(PersonSearch.matches(stephen, p("С. Кинг")));
        assertTrue(PersonSearch.matches(stephen, p("Кинг")));
        assertTrue(PersonSearch.matches(stephen, p("Стивен Эдвин Кинг")));
        assertFalse(PersonSearch.matches(stephen, p("Оуэн Кинг")));
        assertFalse(PersonSearch.matches(stephen, p("О. Кинг")));
        assertTrue(PersonSearch.matches(p("С. Кинг"), p("Стивен Кинг")));
        assertFalse(PersonSearch.matches(p("С. Кинг"), p("Оуэн Кинг")));
        assertFalse(PersonSearch.matches(p("А. Толстой"), p("Л. Толстой")));
        assertTrue(PersonSearch.matches(p("Л. Н. Толстой"), p("Л. Толстой")));
        assertFalse("endings are never forgiven", PersonSearch.matches(p("Котов Иван"), p("Котова")));
    }

    @Test
    public void aCardIsThePersonsWhenOneOfItsPeopleIs() {
        BookPOJO owen = book("https://izib.uk/art1", "Оуэн Кинг", null, null, null);
        BookPOJO both = book("https://izib.uk/art2", "Стивен Кинг, Питер Страуб", null, null, null);
        BookPOJO alone = book("https://izib.uk/art3", "Кинг", null, "Игорь Князев", null);
        assertFalse(PersonSearch.holds(owen, p("Кинг"), false));
        assertFalse(PersonSearch.holds(both, p("Кинг"), false));
        assertTrue(PersonSearch.holds(alone, p("Кинг"), false));
        assertFalse(PersonSearch.holds(owen, p("Стивен Кинг"), false));
        assertTrue(PersonSearch.holds(both, p("Стивен Кинг"), false));
        assertTrue(PersonSearch.holds(both, p("Питер Страуб"), false));
        assertTrue(PersonSearch.holds(alone, p("Князев Игорь"), true));
        assertFalse("a reader is looked for among the readers", PersonSearch.holds(alone, p("Кинг"), true));
    }

    @Test
    public void onlyACardOfThatOnePersonTeachesItsPage() {
        TextNorm.Person king = p("Кинг Стивен");
        String url = "https://izib.uk/author123";
        assertEquals(url, PersonSearch.teaches(book("https://izib.uk/a1", "Стивен Кинг", url, null, null), king, false));
        assertNull("the link is the first person's", PersonSearch.teaches(
                book("https://izib.uk/a2", "Стивен Кинг, Питер Страуб", url, null, null), king, false));
        assertNull("a near name is no identity", PersonSearch.teaches(
                book("https://izib.uk/a3", "С. Кинг", url, null, null), king, false));
        assertNull("no link", PersonSearch.teaches(book("https://izib.uk/a4", "Стивен Кинг", null, null, null),
                king, false));
        String reader = "https://izib.uk/reader7";
        BookPOJO read = book("https://izib.uk/a5", "Стивен Кинг", url, "Игорь Князев", reader);
        assertEquals(reader, PersonSearch.teaches(read, p("Князев Игорь"), true));
        assertNull(PersonSearch.teaches(read, p("Князев Игорь"), false));
    }

    @Test
    public void aListedCardRanksByItsPlaceASearchedOneAfterAll() {
        assertEquals(0, PersonSearch.rank(true, 0));
        assertEquals(7, PersonSearch.rank(true, 7));
        assertEquals(Integer.MAX_VALUE, PersonSearch.rank(false, 0));
    }

    @Test
    public void aFoundPageIsListedAndKeptForTheNextTime() throws IOException {
        Net net = new Net();
        String hit = "https://akniga.org/author/king/page<page>/";
        net.people = ProviderPage.of(Arrays.asList(new GenrePOJO("Оуэн Кинг", "https://akniga.org/author/owen/"),
                new GenrePOJO("Стивен Кинг", hit)), false);
        List<String> found = new ArrayList<>();
        PersonSearch.Source s = PersonSearch.plan("Кинг Стивен", false, Collections.<Provider, String>emptyMap(),
                Collections.singletonList(Provider.AKNIGA), net, (p, url) -> found.add(p.name() + " " + url)).get(0);
        assertEquals(Mode.RESOLVE, s.mode);
        // a lookup, then a listing or the book search with its skipped pages
        assertEquals((2 + PersonSearch.Source.MORE) * Provider.AKNIGA.requestSeconds(), s.timeoutSeconds());
        ProviderPage<BookPOJO> first = s.page("Кинг Стивен", 1);
        assertEquals(2, first.items.size());
        assertEquals(Arrays.asList("authors Кинг Стивен", "listing " + hit + " 1"), net.asked);
        assertEquals(Collections.singletonList("AKNIGA " + hit), found);
        assertTrue(s.listing());
        assertEquals(Provider.AKNIGA.requestSeconds(), s.timeoutSeconds());
        s.page("Кинг Стивен", 2);
        assertEquals("listing " + hit + " 2", net.asked.get(2));
        assertEquals(3, net.asked.size());
    }

    @Test
    public void noSingleHitFallsToTheBookSearchForAnAuthorAndToNothingForAReader() throws IOException {
        Net net = new Net();
        net.people = ProviderPage.of(Arrays.asList(new GenrePOJO("Стивен Кинг", "https://akniga.org/author/a/"),
                new GenrePOJO("Кинг Стивен", "https://akniga.org/author/b/")), false);
        net.books = ProviderPage.of(Arrays.asList(
                book("https://akniga.org/b1", "Оуэн Кинг", null, null, null),
                book("https://akniga.org/b2", "Стивен Кинг, Питер Страуб", null, null, null)), true);
        PersonSearch.Source author = PersonSearch.plan("Стивен Кинг", false, Collections.<Provider, String>emptyMap(),
                Collections.singletonList(Provider.AKNIGA), net, null).get(0);
        ProviderPage<BookPOJO> first = author.page("Стивен Кинг", 1);
        assertEquals(1, first.items.size());
        assertEquals("https://akniga.org/b2", first.items.get(0).getUrl());
        assertTrue(first.hasMore);
        assertFalse(author.listing());
        author.page("Стивен Кинг", 2);
        assertEquals(Arrays.asList("authors Стивен Кинг", "books Стивен Кинг 1", "books Стивен Кинг 2"), net.asked);

        Net readers = new Net();
        readers.people = ProviderPage.empty();
        PersonSearch.Source reader = PersonSearch.plan("Игорь Князев", true, Collections.<Provider, String>emptyMap(),
                Collections.singletonList(Provider.AKNIGA), readers, null).get(0);
        assertEquals(ProviderPage.Status.EMPTY, reader.page("Игорь Князев", 1).status);
        assertEquals(Collections.singletonList("readers Игорь Князев"), readers.asked);
    }

    @Test
    public void aFailedLookupFailsThePageAndARetryLooksAgain() throws IOException {
        Net net = new Net();
        net.peopleFail = new SocketTimeoutException("slow");
        PersonSearch.Source s = PersonSearch.plan("Стивен Кинг", false, Collections.<Provider, String>emptyMap(),
                Collections.singletonList(Provider.AKNIGA), net, null).get(0);
        assertThrows(SocketTimeoutException.class, () -> s.page("Стивен Кинг", 1));
        assertEquals("no fall to the book search", Collections.singletonList("authors Стивен Кинг"), net.asked);

        net.peopleFail = null;
        net.people = ProviderPage.<GenrePOJO>failed(new org.jsoup.HttpStatusException("limit", 429, "u"));
        ProviderPage<BookPOJO> limited = s.page("Стивен Кинг", 1);
        assertEquals(ProviderPage.Status.RATE_LIMITED, limited.status);
        assertEquals(2, net.asked.size());

        net.people = ProviderPage.of(Collections.singletonList(new GenrePOJO("Стивен Кинг", KING_AKNIGA)), false);
        assertEquals(2, s.page("Стивен Кинг", 1).items.size());
        assertEquals(Arrays.asList("authors Стивен Кинг", "authors Стивен Кинг", "authors Стивен Кинг",
                "listing " + KING_AKNIGA + " 1"), net.asked);
    }

    @Test
    public void aBookSearchKeepsThePersonsCardsAndItsPagerGoesOn() {
        ProviderPage<BookPOJO> page = ProviderPage.of(Arrays.asList(
                book("https://golosom.org/book/a", "Оуэн Кинг", null, null, null),
                book("https://golosom.org/book/b", "Мартин", null, null, null)), true);
        ProviderPage<BookPOJO> kept = PersonSearch.keep(page, p("Стивен Кинг"), false);
        assertTrue(kept.items.isEmpty());
        assertEquals("a page filtered to nothing is no end", ProviderPage.Status.OK, kept.status);
        assertTrue(kept.hasMore);
    }

    @Test
    public void aBookSearchPageFilteredToNothingGoesOnToTheNextOnes() throws IOException {
        BookPOJO owen = book("https://golosom.org/book/o", "Оуэн Кинг", null, null, null);
        BookPOJO his = book("https://golosom.org/book/s", "Стивен Кинг", null, null, null);
        Net net = new Net();
        net.bookPages = Arrays.asList(ProviderPage.of(Collections.singletonList(owen), true),
                ProviderPage.of(Collections.singletonList(owen), true),
                ProviderPage.of(Collections.singletonList(his), true),
                ProviderPage.of(Collections.singletonList(his), false));
        PersonSearch.Source s = PersonSearch.plan("Стивен Кинг", false, Collections.<Provider, String>emptyMap(),
                Collections.singletonList(Provider.GOLOSOM), net, null).get(0);
        assertEquals(Mode.SEARCH, s.mode);
        assertEquals((1 + PersonSearch.Source.MORE) * Provider.GOLOSOM.requestSeconds(), s.timeoutSeconds());
        ProviderPage<BookPOJO> first = s.page("Стивен Кинг", 1);
        assertEquals(Collections.singletonList(his), first.items);
        assertTrue(first.hasMore);
        s.page("Стивен Кинг", 2); // the site's page 4: the pager goes on from the skipped ones
        assertEquals(Arrays.asList("books Стивен Кинг 1", "books Стивен Кинг 2", "books Стивен Кинг 3",
                "books Стивен Кинг 4"), net.asked);
        s.page("стивен кинг", 1); // a case retry starts at the site's page 1 again
        assertEquals("books стивен кинг 1", net.asked.get(4));

        Net none = new Net();
        none.bookPages = Collections.nCopies(10, ProviderPage.of(Collections.singletonList(owen), true));
        PersonSearch.Source few = PersonSearch.plan("Стивен Кинг", false, Collections.<Provider, String>emptyMap(),
                Collections.singletonList(Provider.GOLOSOM), none, null).get(0);
        ProviderPage<BookPOJO> nothing = few.page("Стивен Кинг", 1);
        assertEquals("a few pages, not the whole site", 1 + PersonSearch.Source.MORE, none.asked.size());
        assertEquals(ProviderPage.Status.OK, nothing.status);
        assertTrue(nothing.items.isEmpty());
        assertTrue(nothing.hasMore);
    }

    @Test
    public void aSavedPageThatListsNothingGivesWayToTheLookupOnce() throws IOException {
        String moved = "https://akniga.org/author/moved/";
        Net net = new Net();
        net.emptyListing = moved;
        net.people = ProviderPage.of(Collections.singletonList(new GenrePOJO("Стивен Кинг", KING_AKNIGA)), false);
        List<String> found = new ArrayList<>();
        PersonSearch.Source s = PersonSearch.plan("Стивен Кинг", false, Collections.singletonMap(Provider.AKNIGA, moved),
                Collections.singletonList(Provider.AKNIGA), net, (p, url) -> found.add(url)).get(0);
        assertEquals(Mode.LISTING, s.mode);
        assertEquals(2, s.page("Стивен Кинг", 1).items.size());
        assertEquals(Arrays.asList("listing " + moved + " 1", "authors Стивен Кинг", "listing " + KING_AKNIGA + " 1"),
                net.asked);
        assertEquals(Collections.singletonList(KING_AKNIGA), found);
        assertTrue(s.listing());
        assertEquals(Provider.AKNIGA.requestSeconds(), s.timeoutSeconds());

        Net listed = new Net();
        PersonSearch.Source kept = PersonSearch.plan("Стивен Кинг", false,
                Collections.singletonMap(Provider.AKNIGA, KING_AKNIGA), Collections.singletonList(Provider.AKNIGA),
                listed, null).get(0);
        kept.page("Стивен Кинг", 1);
        kept.page("Стивен Кинг", 2);
        assertEquals("a saved page that lists is trusted",
                Arrays.asList("listing " + KING_AKNIGA + " 1", "listing " + KING_AKNIGA + " 2"), listed.asked);
        assertEquals(Provider.AKNIGA.requestSeconds(), kept.timeoutSeconds());
    }
}
