package com.fanok.audiobooks.source;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fanok.audiobooks.pojo.DescriptionPOJO;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/** Akniga book overview formatting. */
public class AknigaDescriptionTest {

    private static final String BOOK_URL = "https://akniga.org/teorin-yuhan-sankta-psiho";

    private static Document load(String name) throws IOException {
        InputStream in = AknigaDescriptionTest.class.getResourceAsStream("/fixtures/akniga/" + name);
        org.junit.Assert.assertNotNull("missing fixture " + name, in);
        String html = new Scanner(in, StandardCharsets.UTF_8.name()).useDelimiter("\\A").next();
        return Jsoup.parse(html, BOOK_URL);
    }

    @Test
    public void bookOverviewHasHeadingsAndParagraphBreaks() throws IOException {
        DescriptionPOJO d = Akniga.parseDescription(load("book.html"), BOOK_URL);
        String text = d.getDescription();
        assertFalse(text.startsWith("Описание\n"));
        assertTrue(text.startsWith("Психиатрическую"));
        assertTrue(text.contains("\n\nДругое название\n"));
        assertTrue(text.contains("\n\nДобавлено 19 октября 2026"));
        // annotation paragraph break preserved
        assertTrue(text.contains("предков…\n\nОзвучено") || text.contains("предков...\n\nОзвучено")
                || text.toLowerCase().contains("психо"));
        // social section is present but separated
        assertTrue(text.contains("\n\nИсполнитель в социальных сетях"));
    }

    @Test
    public void otherReadingsAreNotDuplicated() throws IOException {
        DescriptionPOJO d = Akniga.parseDescription(load("book.html"), BOOK_URL);
        assertFalse(d.getDescription().toLowerCase().contains("другие озвучки"));
    }

    @Test
    public void siteAlternativeVoicesBlockIsExcludedButDescriptionRemains() {
        String html = "<div class='full-width description__article-main caption__article--about-block-samebook'>"
                + "<div class='content__article--about-content'><div class='alt-voices'>"
                + "<div class='alt-voices__caption'>Другие озвучки</div>"
                + "<div class='alt-voices__list'><a class='alt-voices__item'>Козий Николай</a></div>"
                + "</div></div></div>"
                + "<div class='description__article-main'><div class='content__main__book--item--caption'>Описание</div>"
                + "Французский мультимиллионер обратился за помощью.</div>";
        String text = Akniga.parseDescription(Jsoup.parse(html, BOOK_URL), BOOK_URL).getDescription();
        assertFalse(text.contains("Другие озвучки"));
        assertFalse(text.contains("Козий Николай"));
        assertTrue(text.contains("Французский мультимиллионер"));
    }

    @Test
    public void collectionsAndAlternateTitlesAreSeparateLines() {
        String html = "<div class='description__article-main caption__article--about-block-samebook'>"
                + "<div class='alt-voices alt-voices--collections'><div class='alt-voices__caption'>Входит в подборки</div>"
                + "<div class='alt-voices__list'><a class='alt-voices__item'><span class='alt-voices__name'>Первая подборка</span>"
                + "<span class='alt-voices__meta'>7 аудиокниг</span></a>"
                + "<a class='alt-voices__item'><span class='alt-voices__name'>Вторая подборка</span>"
                + "<span class='alt-voices__meta'>6 аудиокниг</span></a></div></div></div>"
                + "<div class='description__article-main'><div class='content__main__book--item--caption'>Другие названия</div>"
                + "Первое название; Второе название; Третье название</div>";
        String text = Akniga.parseDescription(Jsoup.parse(html, BOOK_URL), BOOK_URL).getDescription();
        assertTrue(text.contains("Входит в подборки\nПервая подборка 7 аудиокниг\nВторая подборка 6 аудиокниг"));
        assertTrue(text.contains("Другие названия\nПервое название\nВторое название\nТретье название"));
    }

    @Test
    public void fallbackUsesLdJsonWhenNoSections() throws IOException {
        String html = "<html><head><script type=\"application/ld+json\">"
                + "{\"description\":\"Fallback annotation text.\"}</script></head><body></body></html>";
        Document doc = Jsoup.parse(html, BOOK_URL);
        DescriptionPOJO d = Akniga.parseDescription(doc, BOOK_URL);
        assertTrue(d.getDescription().contains("Fallback annotation text."));
    }

    @Test
    public void emptySectionsProduceNoEmptyHeadings() throws IOException {
        String html = "<html><body>"
                + "<div class=\"description__article-main\"><div class=\"content__main__book--item--caption\">Описание</div>"
                + "   </div>"
                + "<div class=\"description__article-main\"><div class=\"content__main__book--item--caption\">Другое название</div>"
                + "Alt title</div>"
                + "</body></html>";
        Document doc = Jsoup.parse(html, BOOK_URL);
        DescriptionPOJO d = Akniga.parseDescription(doc, BOOK_URL);
        String text = d.getDescription();
        assertFalse(text.contains("Описание\n\n"));
        assertTrue(text.contains("Другое название\nAlt title"));
    }

    @Test
    public void nestedMarkupDoesNotDuplicateText() throws IOException {
        String html = "<html><body>"
                + "<div class=\"description__article-main\"><div class=\"content__main__book--item--caption\">Описание</div>"
                + "<p>First paragraph.</p><br><br><b>Second paragraph.</b></div>"
                + "</body></html>";
        Document doc = Jsoup.parse(html, BOOK_URL);
        DescriptionPOJO d = Akniga.parseDescription(doc, BOOK_URL);
        String text = d.getDescription();
        assertTrue(text.contains("First paragraph."));
        assertTrue(text.contains("Second paragraph."));
        assertFalse(text.contains("Описание"));
    }
}
