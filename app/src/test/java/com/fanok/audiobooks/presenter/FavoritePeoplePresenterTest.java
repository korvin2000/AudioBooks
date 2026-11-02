package com.fanok.audiobooks.presenter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fanok.audiobooks.model.FavoritePeople;
import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.presenter.FavoritePeoplePresenter.Row;
import com.fanok.audiobooks.search.Provider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** Избранное → Авторы / Чтецы: what a row knows of a person, and the list's search, filter and sort. */
public class FavoritePeoplePresenterTest {

    private static final String IZIBUK = "https://izibuk.ru/";
    private static final String BAZA = "https://baza-knig.ink/";

    private static FavoritePeople.Person person(String name, long addedAt, String... urls) {
        return new FavoritePeople.Person(FavoritePeople.AUTHOR, name, Arrays.asList(urls), addedAt);
    }

    private static BookPOJO book(String url, String author, String reader, String genre) {
        BookPOJO book = new BookPOJO();
        book.setUrl(url);
        book.setAutor(author);
        book.setArtist(reader);
        book.setGenre(genre);
        return book;
    }

    /** King (newest), Bulgakov, Tolstoy (oldest), as FavoritePeople.all gives them. */
    private static List<Row> rows() {
        List<FavoritePeople.Person> people = Arrays.asList(person("Стивен Кинг", 3),
                person("Михаил Булгаков", 2, BAZA + "author/bulgakov"), person("Лев Толстой", 1));
        List<BookPOJO> favorites = Arrays.asList(
                book(IZIBUK + "b1", "Кинг Стивен", "Иванов", "Ужасы"),
                book(BAZA + "b2", "Стивен Кинг, Питер Страуб", null, "Ужасы"),
                book(IZIBUK + "b3", "Михаил Булгаков", "Кинг Стивен", "Классика"));
        List<BookPOJO> history = Collections.singletonList(book(IZIBUK + "b4", "Толстой Лев", null, "Классика"));
        return FavoritePeoplePresenter.rows(FavoritePeople.AUTHOR, people, favorites, history);
    }

    private static List<String> names(List<Row> rows) {
        List<String> out = new ArrayList<>();
        for (Row row : rows) {
            out.add(row.person.name);
        }
        return out;
    }

    @Test
    public void rowsCountFavouriteBooksByPersonInAnyWordOrder() {
        List<Row> rows = rows();
        assertEquals(Arrays.asList("Стивен Кинг", "Михаил Булгаков", "Лев Толстой"), names(rows));
        assertEquals(2, rows.get(0).books); // the reader field does not count for authors
        assertEquals(1, rows.get(1).books);
        assertEquals(0, rows.get(2).books); // the history gives sites and genres, not books in favourites
    }

    @Test
    public void rowsListOwnPagesFirstThenSitesOfBooks() {
        List<Row> rows = rows();
        assertEquals(Arrays.asList(Provider.IZIBUK, Provider.BAZA_KNIG), rows.get(0).sites);
        assertEquals(Arrays.asList(Provider.BAZA_KNIG, Provider.IZIBUK), rows.get(1).sites);
        assertEquals(Collections.singletonList(Provider.IZIBUK), rows.get(2).sites);
        assertTrue(rows.get(2).genres.contains("Классика"));
    }

    @Test
    public void sortsByDateNameAndBooks() {
        FavoritePeoplePresenter list = new FavoritePeoplePresenter();
        list.setRows(rows());
        assertEquals(Arrays.asList("Стивен Кинг", "Михаил Булгаков", "Лев Толстой"), names(list.shown()));
        list.setSort(FavoritePeoplePresenter.SORT_NAME);
        assertEquals(Arrays.asList("Лев Толстой", "Михаил Булгаков", "Стивен Кинг"), names(list.shown()));
        list.setSort(FavoritePeoplePresenter.SORT_BOOKS);
        assertEquals(Arrays.asList("Стивен Кинг", "Михаил Булгаков", "Лев Толстой"), names(list.shown()));
    }

    @Test
    public void filtersAndSearches() {
        FavoritePeoplePresenter list = new FavoritePeoplePresenter();
        list.setRows(rows());
        list.setFilter(FavoritePeoplePresenter.FILTER_BOOKS, null);
        assertEquals(Arrays.asList("Стивен Кинг", "Михаил Булгаков"), names(list.shown()));
        list.setFilter(FavoritePeoplePresenter.FILTER_SITE, Provider.BAZA_KNIG.name());
        assertEquals(Arrays.asList("Стивен Кинг", "Михаил Булгаков"), names(list.shown()));
        list.setFilter(FavoritePeoplePresenter.FILTER_GENRE, "Классика");
        assertEquals(Arrays.asList("Михаил Булгаков", "Лев Толстой"), names(list.shown()));
        list.setFilter(FavoritePeoplePresenter.FILTER_NONE, null);
        list.setQuery("толст");
        assertEquals(Collections.singletonList("Лев Толстой"), names(list.shown()));
    }

    @Test
    public void sitesCountPeopleMostFirst() {
        FavoritePeoplePresenter list = new FavoritePeoplePresenter();
        list.setRows(rows());
        assertEquals(Arrays.asList(Provider.IZIBUK, Provider.BAZA_KNIG), new ArrayList<>(list.sites().keySet()));
        assertEquals(Integer.valueOf(3), list.sites().get(Provider.IZIBUK));
        assertEquals(Arrays.asList("Классика", "Ужасы"), list.genres());
    }
}
