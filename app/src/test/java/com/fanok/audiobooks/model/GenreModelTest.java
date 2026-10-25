package com.fanok.audiobooks.model;

import static com.fanok.audiobooks.model.SeriesModelTest.fixture;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fanok.audiobooks.Url;
import com.fanok.audiobooks.pojo.GenrePOJO;
import com.fanok.audiobooks.search.ProviderPage;
import java.io.IOException;
import org.junit.Test;

public class GenreModelTest {

    @Test
    public void audiobooGenresComeFromTheNavigationMenuWithTheSiteUrls() throws IOException {
        ProviderPage<GenrePOJO> page = GenreModel.parseAudiobooGenres(
                fixture("audioboo/listing-index-p1.html", Url.SERVER_AUDIOBOO + "/"));
        assertEquals(50, page.items.size());
        assertFalse(page.hasMore);
        GenrePOJO first = page.items.get(0);
        assertEquals("Альтернативная история", first.getName());
        assertEquals("https://audioboo.org/altist/", first.getUrl());
        assertEquals("953", first.getDescription());
        boolean mystic = false;
        for (GenrePOJO genre : page.items) {
            assertTrue(genre.getUrl(), Url.isHost(genre.getUrl(), Url.SERVER_AUDIOBOO));
            if (genre.getName().equals("Мистика")) {
                // the slug the site uses, not a guessed one ("mictika" is a 404)
                assertEquals("https://audioboo.org/mistic/", genre.getUrl());
                mystic = true;
            }
        }
        assertTrue(mystic);
    }

    @Test
    public void audiobooAuthorsAndReadersAreThoseOfTheListingCards() throws IOException {
        org.jsoup.nodes.Document index = fixture("audioboo/listing-index-p1.html", Url.SERVER_AUDIOBOO + "/");
        ProviderPage<GenrePOJO> authors = AutorsModel.parseAudiobooPeople(index, false, 1);
        ProviderPage<GenrePOJO> readers = AutorsModel.parseAudiobooPeople(index, true, 1);
        assertTrue(authors.items.size() > 10);
        assertTrue(readers.items.size() > 10);
        assertTrue(authors.hasMore);
        java.util.Set<String> names = new java.util.HashSet<>();
        for (GenrePOJO author : authors.items) {
            assertTrue(author.getName(), names.add(author.getName()));
            assertTrue(author.getUrl(), author.getUrl().startsWith(Url.SERVER_AUDIOBOO + "/xfsearch/avtora/"));
        }
        for (GenrePOJO reader : readers.items) {
            assertTrue(reader.getUrl(), reader.getUrl().startsWith(Url.SERVER_AUDIOBOO + "/tags/"));
        }
    }

    @Test
    public void knigavuhePeopleComeFromListingRowsAndSearchItems() {
        // the shapes of /authors/2/ (legacy_people_row) and /search/authors/ (author_item), cut down
        org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse("<div id=\"authors_list\">"
                + "<div class=\"legacy_people_row\"><div class=\"legacy_people_identity\">"
                + "<a href=\"/author/donna-tartt/\" class=\"legacy_people_avatar -pseudo\">ДТ</a>"
                + "<div class=\"legacy_people_identity_body\"><div class=\"legacy_people_name\">"
                + "<a href=\"/author/donna-tartt/\">Донна <span>Тартт</span></a></div>"
                + "<div class=\"legacy_people_books\">6 книг</div></div></div>"
                + "<div class=\"legacy_people_metric -plays\">43K</div></div>"
                + "<div class=\"common_list_item author_item\"><span class=\"author_item_books_count\">586 книг</span>"
                + "<a href=\"/author/stiven-king/\" class=\"author_item_name\"><span>Стивен Кинг</span></a></div>"
                + "</div><div class=\"pn_page_buttons\"><a href=\"/authors/\" data-page=\"1\">1</a>"
                + "<a href=\"/authors/2/\" data-page=\"2\">2</a><a href=\"/authors/330/\" data-page=\"330\">330</a></div>");
        ProviderPage<GenrePOJO> page = AutorsModel.parseKnigavuhePeople(doc, 2);
        assertEquals(2, page.items.size());
        assertTrue(page.hasMore);
        assertEquals("Донна Тартт", page.items.get(0).getName());
        assertEquals(Url.SERVER + "/author/donna-tartt/", page.items.get(0).getUrl());
        assertEquals("6 книг", page.items.get(0).getDescription());
        assertEquals("Стивен Кинг", page.items.get(1).getName());
        assertEquals("586 книг", page.items.get(1).getDescription());
        assertFalse(AutorsModel.parseKnigavuhePeople(doc, 330).hasMore);
    }
}
