package com.fanok.audiobooks.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fanok.audiobooks.BookPageFixtures;
import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.pojo.DescriptionPOJO;
import com.fanok.audiobooks.pojo.GenrePOJO;

import org.jsoup.Jsoup;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;

/**
 * The overview of an akniga.org book page: the annotation and the site's notes, without the blocks other screens show
 * (the other narrations, the collections) and the ones that say nothing as text (the narrator's social networks).
 */
public class AknigaDescriptionTest {

    private static final String BOOK = "https://akniga.org/teorin-yuhan-sankta-psiho";
    /** A page with both «Другие озвучки» and «Входит в подборки». */
    private static final String IN_COLLECTIONS = "https://akniga.org/nevil-shyut-na-beregu";

    private static DescriptionPOJO description(String fixture, String url) throws IOException {
        return Akniga.parseDescription(BookPageFixtures.load(fixture, url), url);
    }

    @Test
    public void theOverviewIsTheAnnotationThenTheNotes() throws IOException {
        DescriptionPOJO d = description("akniga/book.html", BOOK);
        String[] lines = d.getDescription().split("\n");
        assertEquals(8, lines.length);
        assertTrue(lines[0], lines[0].startsWith("Психиатрическую больницу Санкта Психо"));
        assertTrue(lines[0], lines[0].endsWith("терзающие его душу…"));
        assertEquals("the site's line break starts a line", "Озвучено специально для проекта Аудиокниги Клуб", lines[1]);
        // the notes: the other titles, one a line under their caption, then the date, an empty line before each
        String notes = "Другое название\nSankta Psyko [ориг.]\nПриют Святой Патриции\n\nДобавлено 19 октября 2026";
        assertEquals(notes, d.getNotes());
        assertEquals(lines[0] + "\n" + lines[1] + "\n\n" + notes, d.getDescription());
    }

    @Test
    public void theParagraphsOfAnAnnotationAreLines() throws IOException {
        String[] lines = description("akniga/book-description.html", "https://akniga.org/grehem-yan-monument-chast-1")
                .getDescription().split("\n");
        assertEquals("Белтирран. Таинственная страна счастья, благоденствующая под властью силы Света.", lines[0]);
        assertEquals("Страна, которую веками считают лишь древней легендой!", lines[1]);
        assertEquals("Другое название", lines[lines.length - 4]);
        assertEquals("Monument [ориг.]", lines[lines.length - 3]);
        assertEquals("", lines[lines.length - 2]);
        assertEquals("Добавлено 9 часов назад", lines[lines.length - 1]);
    }

    @Test
    public void otherNarrationsCollectionsAndSocialNetworksAreNotText() throws IOException {
        String text = description("akniga/book-collections.html", IN_COLLECTIONS).getDescription();
        assertTrue(text, text.startsWith("Постъядерный мир. Атомная война длилась 37 дней."));
        assertTrue(text, text.endsWith("лучевой болезни…\n\nДругое название\nOn the Beach [ориг.]\nНа берегу\n\n"
                + "Добавлено 20 октября 2010"));
        for (String left : new String[]{"Описание", "Другие озвучки", "Булдаков Олег", "Входит в подборки",
                "Последние люди на Земле", "аудиокниг"}) {
            assertFalse(left, text.contains(left));
        }
        String social = description("akniga/book.html", BOOK).getDescription();
        assertFalse(social, social.contains("социальных сетях") || social.contains("ВКонтакте"));
    }

    @Test
    public void theCollectionsOfABook() throws IOException {
        ArrayList<GenrePOJO> collections = description("akniga/book-collections.html", IN_COLLECTIONS).getCollections();
        assertEquals(2, collections.size());
        assertEquals("Последние люди на Земле", collections.get(0).getName());
        assertEquals("https://akniga.org/collection/poslednie-lyudi-na-zemle/", collections.get(0).getUrl());
        assertEquals("9 аудиокниг", collections.get(0).getDescription());
        assertEquals("Хорошо, что это происходит не с нами", collections.get(1).getName());
        assertEquals("https://akniga.org/collection/horosho-chto-eto-proishodit-ne-s-nami/",
                collections.get(1).getUrl());
        // the app opens such a link as the list of the collection's books
        assertTrue(new Akniga().opensAsListing(collections.get(0).getUrl()));
        assertTrue(description("akniga/book.html", BOOK).getCollections().isEmpty());
    }

    @Test
    public void aLinkThatIsNoCollectionOrHasNoNameIsLeftOut() {
        String html = "<div class='description__article-main'>Текст</div><div class='alt-voices alt-voices--collections'>"
                + "<a class='alt-voices__item' href='/collection/a/'><span class='alt-voices__name'>А</span></a>"
                + "<a class='alt-voices__item' href='/some-book'><span class='alt-voices__name'>Книга</span></a>"
                + "<a class='alt-voices__item' href='/collection/b/'></a></div>";
        ArrayList<GenrePOJO> collections = Akniga.parseDescription(Jsoup.parse(html, BOOK), BOOK).getCollections();
        assertEquals(1, collections.size());
        assertEquals("https://akniga.org/collection/a/", collections.get(0).getUrl());
        assertEquals("", collections.get(0).getDescription());
    }

    @Test
    public void anEmptyAnnotationLeavesTheNotesAlone() {
        String html = "<div class='description__article-main'>"
                + "<div class='content__main__book--item--caption'>Описание</div>  </div>"
                + "<div class='description__article-main'>"
                + "<div class='content__main__book--item--caption'>Другое название</div>Alt title</div>";
        DescriptionPOJO d = Akniga.parseDescription(Jsoup.parse(html, BOOK), BOOK);
        assertEquals("Другое название\nAlt title", d.getDescription());
        assertEquals(d.getDescription(), d.getNotes());
    }

    @Test
    public void aSavedBookKeepsTheSameOverview() throws IOException {
        BookPOJO book = Akniga.parseBook(BookPageFixtures.load("akniga/book-collections.html", IN_COLLECTIONS),
                IN_COLLECTIONS);
        assertEquals(description("akniga/book-collections.html", IN_COLLECTIONS).getDescription(), book.getDesc());
    }

    @Test
    public void aPageWithoutSectionsTakesItsLdJson() {
        String html = "<html><head><script type=\"application/ld+json\">"
                + "{\"description\":\"Fallback annotation text.\"}</script></head><body></body></html>";
        assertEquals("Fallback annotation text.",
                Akniga.parseDescription(Jsoup.parse(html, BOOK), BOOK).getDescription());
    }
}
