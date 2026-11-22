package com.fanok.audiobooks.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fanok.audiobooks.pojo.AudioPOJO;
import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.pojo.DescriptionPOJO;
import com.fanok.audiobooks.pojo.GenrePOJO;
import com.fanok.audiobooks.search.PeopleIndex;
import com.fanok.audiobooks.search.ProviderPage;
import com.fanok.audiobooks.search.SearchUrls;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/** Pages saved from staroeradio.ru on 2026-11-21 (see src/test/resources/fixtures/staroeradio). */
public class StaroeradioTest {

    private static final String S = Staroeradio.SERVER;
    private static final String LETTER_A = S + "/collection/%D0%90";
    private static final String CHEKHOV = S + "/search?q=%D0%A7%D0%B5%D1%85%D0%BE%D0%B2";
    /** The person link of "Островский А." and the search saved for it. */
    private static final String OSTROVSKY = S + "/search?q=%D0%9E%D1%81%D1%82%D1%80%D0%BE%D0%B2%D1%81%D0%BA%D0%B8%D0%B9%20%D0%90.";
    private static final String IVCHENKO = S + "/search?q=%D0%92.%D0%98%D0%B2%D1%87%D0%B5%D0%BD%D0%BA%D0%BE";

    private final Staroeradio parser = new Staroeradio();

    private static Document fixture(String name, String baseUri) throws IOException {
        try (InputStream in = StaroeradioTest.class.getResourceAsStream("/fixtures/staroeradio/" + name)) {
            assertNotNull("missing fixture " + name, in);
            return Jsoup.parse(in, "UTF-8", baseUri);
        }
    }

    private static ProviderPage<BookPOJO> listing(String name, String url, int page) throws IOException {
        return Staroeradio.parseListing(fixture(name, url), page);
    }

    private static void card(BookPOJO book, String id, String name, String author, String readers) {
        assertEquals(S + "/audio/" + id, book.getUrl());
        assertEquals(name, book.getName());
        assertEquals(author, book.getAutor());
        assertEquals(author == null ? null : Staroeradio.person(author), book.getUrlAutor());
        assertEquals(readers, book.getArtist());
        assertEquals(readers == null ? null : Staroeradio.person(readers), book.getUrlArtist());
        assertNull("no covers on the lists", book.getPhoto());
        assertNull("no durations on the lists", book.getTime());
    }

    // ---------------------------------------------------------------- URLs

    @Test
    public void drawerUrlsPagesAndNoSorts() {
        assertEquals(S + "/program", Staroeradio.INDEX);
        assertEquals(S + "/collection", Staroeradio.GENRES);
        assertEquals(S + PeopleIndex.AUTHORS, Staroeradio.AUTHORS);
        assertEquals(S + PeopleIndex.READERS, Staroeradio.READERS);
        assertEquals(Staroeradio.INDEX, parser.pageUrl(Staroeradio.INDEX, 1));
        assertEquals(S + "/program?page=6", parser.pageUrl(Staroeradio.INDEX, 6));
        assertEquals(LETTER_A + "?page=39", parser.pageUrl(LETTER_A, 39));
        assertEquals(OSTROVSKY + "&page=2", parser.pageUrl(OSTROVSKY, 2));
        assertEquals(CHEKHOV + "&page=2", parser.pageUrl(CHEKHOV + "&page=1", 2));
        for (String url : new String[]{Staroeradio.INDEX, LETTER_A, CHEKHOV, OSTROVSKY}) {
            assertTrue(url, parser.sorts(url).isEmpty());
            assertFalse(url, parser.hasSort(url));
            assertFalse("the lists carry no time: " + url, parser.listsDuration(url));
            assertTrue(url, parser.periods(url).isEmpty());
        }
        assertNull(parser.sortUrl(Sort.POPULAR, Staroeradio.INDEX));
        assertTrue(parser.hosts().isEmpty());
        assertTrue(parser.refererHosts().isEmpty());
        assertNull(parser.referer());
        assertNull(parser.mediaHeaders(S + "/audio/37323"));
    }

    @Test
    public void searchIsOneXhrPagedOnTheDevice() {
        assertEquals(CHEKHOV + "&page=1", parser.searchUrl(SearchUrls.Category.BOOKS, "Чехов", 1));
        assertEquals(CHEKHOV + "&page=2", parser.searchUrl(SearchUrls.Category.BOOKS, "Чехов", 2));
        assertEquals(S + "/search?q=%D0%BC%D0%B0%D1%81%D1%82%D0%B5%D1%80+%D0%B8+%D0%BC%D0%B0%D1%80%D0%B3%D0%B0%D1%80%D0%B8%D1%82%D0%B0&page=3",
                parser.searchUrl(SearchUrls.Category.BOOKS, "мастер и маргарита", 3));
        assertNull(parser.searchUrl(SearchUrls.Category.AUTHORS, "Чехов", 1));
        assertNull(parser.searchUrl(SearchUrls.Category.PERFORMERS, "Чехов", 1));
    }

    /** Page 1 reads the whole list; its later pages reuse it until another list is read. */
    @Test
    public void laterPagesReuseTheListOfPageOne() throws IOException {
        final List<String> loaded = new ArrayList<>();
        Document letter = Jsoup.parse("", LETTER_A);
        Document chekhov = Jsoup.parse("", CHEKHOV);
        com.fanok.audiobooks.model.BookPages.Fetch load = url -> {
            loaded.add(url);
            return url.equals(LETTER_A) ? letter : chekhov;
        };
        assertSame(letter, parser.list(LETTER_A, load));
        assertSame(letter, parser.list(parser.pageUrl(LETTER_A, 2), load));
        assertSame(letter, parser.list(parser.pageUrl(LETTER_A, 39), load));
        assertSame(chekhov, parser.list(CHEKHOV + "&page=1", load));
        assertSame(chekhov, parser.list(CHEKHOV + "&page=2", load));
        assertSame(letter, parser.list(parser.pageUrl(LETTER_A, 3), load)); // the held list is the search now
        assertSame(letter, parser.list(LETTER_A, load)); // page 1 is read afresh
        assertEquals(java.util.Arrays.asList(LETTER_A, CHEKHOV, LETTER_A, LETTER_A), loaded);
    }

    // ---------------------------------------------------------------- listings

    @Test
    public void letterIsPagedByFiftyWithoutTheSisterArchives() throws IOException {
        Document page = fixture("letter-a.html", LETTER_A);
        ProviderPage<BookPOJO> first = Staroeradio.parseListing(page, 1);
        assertEquals(ProviderPage.Status.OK, first.status);
        assertEquals(50, first.items.size());
        assertTrue(first.hasMore);
        card(first.items.get(0), "32019", "А что же дальше ? (ЛР) (вып. 1-й из 8-ми), 1971", null, null);
        card(first.items.get(49), "41876", "Мишка Пряслин, 1991", "Абрамов Ф.", "И.Охлупин, С.Балабанов, "
                + "Р.Суховерко, О.Остроумова, Т.Курьянова, Т.Аксюта, И.Бякова, Л.Стриженова");

        ProviderPage<BookPOJO> second = Staroeradio.parseListing(page, 2);
        assertEquals(50, second.items.size());
        assertTrue(second.hasMore);
        card(second.items.get(0), "28308", "О чём плачут лошади (№3, 1984г.)", "Абрамов Ф.", "Вячеслав Тихонов");

        ProviderPage<BookPOJO> last = Staroeradio.parseListing(page, 39);
        assertEquals(39, last.items.size());
        assertFalse(last.hasMore);
        card(last.items.get(38), "38688", "Мария Ермолова. Первая российская народная артистка, 1969", "Ашкинезер М.",
                "А.Консовский, Г.Куликов, Е.Козырева, В.Хохряков, С.Фадеева");
        assertEquals(ProviderPage.Status.EMPTY, Staroeradio.parseListing(page, 40).status);

        int own = 0;
        for (int n = 1; n <= 39; n++) {
            for (BookPOJO book : Staroeradio.parseListing(page, n).items) {
                assertTrue(book.getUrl(), book.getUrl().matches("https://staroeradio\\.ru/audio/\\d+"));
                own++;
            }
        }
        assertEquals("2391 links, 452 of them to svidetel.su, reportage.su, ...", 1939, own);
    }

    @Test
    public void theIndexIsTheBroadcastSchedule() throws IOException {
        ProviderPage<BookPOJO> first = parser.listing(fixture("program.html", Staroeradio.INDEX), Staroeradio.INDEX, 1);
        assertEquals(50, first.items.size());
        assertTrue(first.hasMore);
        card(first.items.get(0), "37323", "На всякого мудреца довольно простоты, 1985", "Островский А.",
                "В.Ивченко, О.Волкова, О.Басилашвили, Л.Макарова, Е.Лебедев, В.Стржельчик, Э.Попова, Н.Данилова");
        assertEquals(IVCHENKO, first.items.get(0).getUrlArtist());
        card(first.items.get(1), "28501", "И вот приходит ветер (пер. 1-я), 1987", "Проханов А.",
                "В.Зозулин, С.Жирнов, А.Вокач, Г.Фролов, В.Сулимов, А.Борзунов");

        ProviderPage<BookPOJO> last = listing("program.html", Staroeradio.INDEX + "?page=6", 6);
        assertEquals(33, last.items.size());
        assertFalse(last.hasMore);
        card(last.items.get(32), "20135", "Певица (юмор), 1958", "Ласкин Б.", "М.Миронова, А.Менакер");
        assertEquals(ProviderPage.Status.EMPTY, listing("program.html", Staroeradio.INDEX, 7).status);
    }

    @Test
    public void searchHitsAndNone() throws IOException {
        Document hits = fixture("search-chekhov.html", CHEKHOV + "&page=1");
        ProviderPage<BookPOJO> first = parser.searchResults(hits, 1);
        assertEquals(50, first.items.size());
        assertTrue(first.hasMore);
        card(first.items.get(0), "15732", "Хамелеон, 1952", "Чехов А.", "Игорь Ильинский");
        card(first.items.get(1), "54154", "Чехов в письмах к Лике, к сестре Маше, к О.Книппер-Чеховой (спектакль)",
                null, null);
        for (BookPOJO book : first.items) {
            assertTrue("theatrologia.su's 8th hit is dropped: " + book.getUrl(),
                    book.getUrl().matches("https://staroeradio\\.ru/audio/\\d+"));
        }
        assertEquals("the 8th hit is the 9th link", S + "/audio/54150", first.items.get(7).getUrl());
        ProviderPage<BookPOJO> last = parser.searchResults(hits, 16);
        assertEquals("751 of the 832 hits are the site's own", 1, last.items.size());
        assertFalse(last.hasMore);
        assertEquals(S + "/audio/49349", last.items.get(0).getUrl());
        assertEquals(ProviderPage.Status.EMPTY, parser.searchResults(hits, 17).status);

        Document none = fixture("search-none.html", S + "/search?q=zzqxwv&page=1"); // an empty 200 answer
        ProviderPage<BookPOJO> empty = parser.searchResults(none, 1);
        assertEquals(ProviderPage.Status.EMPTY, empty.status);
        assertFalse(empty.hasMore);
    }

    /**
     * The site's search box sends 3 letters at least; a shorter query is not sent (live, "на" answers 4.4 MB, past
     * the app's limit, "а" 12.5 MB) and finds nothing.
     */
    @Test
    public void aShortQueryIsNotSent() throws IOException {
        Document none = parser.fetch(parser.searchUrl(SearchUrls.Category.BOOKS, "на", 1));
        assertEquals(ProviderPage.Status.EMPTY, parser.searchResults(none, 1).status);
        none = parser.fetch(parser.searchUrl(SearchUrls.Category.BOOKS, " а ", 2));
        assertEquals(ProviderPage.Status.EMPTY, parser.searchResults(none, 2).status);
        assertFalse(Staroeradio.searchable(S + "/search?q=%D0%BD%D0%B0"));
        assertTrue(Staroeradio.searchable(CHEKHOV));
        assertTrue(Staroeradio.searchable(S + "/search?q=%D0%9B.%D0%9C")); // "Л.М"
        assertTrue(Staroeradio.searchable(OSTROVSKY));
    }

    /** A person of the built-in lists opens the search for the name as the titles write it. */
    @Test
    public void aPersonIsTheSearchForTheName() throws IOException {
        assertEquals(OSTROVSKY, Staroeradio.person("Островский А."));
        assertEquals(IVCHENKO, Staroeradio.person("В.Ивченко, О.Волкова"));
        assertEquals("", Staroeradio.person(""));
        ProviderPage<BookPOJO> first = listing("search-person.html", OSTROVSKY, 1);
        assertEquals(50, first.items.size());
        assertTrue(first.hasMore);
        card(first.items.get(49), "8807", "Правда - хорошо, а счастье лучше (фрагмент) (1), 1951", "Островский А.",
                "В.Рыжова, В.Баринова, А.Сашин-Никольский");
        int his = 0;
        for (BookPOJO book : first.items) {
            his += "Островский А.".equals(book.getAutor()) ? 1 : 0;
        }
        assertTrue(String.valueOf(his), his > 40);
        ProviderPage<BookPOJO> last = listing("search-person.html", OSTROVSKY + "&page=4", 4);
        assertEquals(19, last.items.size());
        assertFalse(last.hasMore);
        BookPOJO glitch = last.items.get(18);
        card(glitch, "40998", "Весёлого веселье не покидает (сказка), 1960", "Туманян О.", "Л.Маратова, Б.Левинсон, "
                + "А.Граве, Л.Пирогов, К.Мукасян, Ю.Заостровский, Ф.Тобиас, М.Абрамов, В.Пригородов");
    }

    // ---------------------------------------------------------------- genres

    @Test
    public void theLettersAreTheGenres() throws IOException {
        ProviderPage<GenrePOJO> page = parser.genres(fixture("collection.html", Staroeradio.GENRES),
                Staroeradio.GENRES, 1);
        assertFalse(page.hasMore);
        assertEquals(29, page.items.size());
        assertEquals("0-9", page.items.get(0).getName());
        assertEquals(S + "/collection/0-9", page.items.get(0).getUrl());
        assertEquals("А", page.items.get(1).getName());
        assertEquals(LETTER_A, page.items.get(1).getUrl());
        assertEquals("Я", page.items.get(28).getName());
        assertEquals(S + "/collection/%D0%AF", page.items.get(28).getUrl());
    }

    // ---------------------------------------------------------------- book page

    @Test
    public void bookPage() throws IOException {
        String url = S + "/audio/37323";
        Document page = fixture("book.html", url);
        DescriptionPOJO d = parser.description(page, url);
        assertEquals("На всякого мудреца довольно простоты, 1985", d.getTitle());
        assertEquals("Островский А.", d.getAutor());
        assertEquals(OSTROVSKY, d.getAutorUrl());
        assertEquals("В.Ивченко, О.Волкова, О.Басилашвили, Л.Макарова, Е.Лебедев, В.Стржельчик, Э.Попова, Н.Данилова",
                d.getArtist());
        assertEquals(IVCHENKO, d.getArtistUrl());
        assertEquals(S + "/ap/staroeradio/images/pics/078167.jpg", d.getPoster());
        String text = d.getDescription();
        assertTrue(text, text.startsWith("Спектакль Ленинградского академического Большого драматического театра"));
        assertTrue(text, text.contains("\nДействующие лица и исполнители:\nЕгор Дмитрич Глумов, молодой человек - "
                + "Валерий М. Ивченко;\n"));
        assertTrue(text, text.endsWith("Л.Макарова (в роли Мамаевой) и О.Басилашвили (в роли Мамаева)"));
        assertTrue(d.getSeries() == null || d.getSeries().isEmpty());

        BookPOJO book = parser.book(page, url);
        assertEquals("Островский А.", book.getAutor());
        assertEquals(S + "/ap/staroeradio/images/pics/078167.jpg", book.getPhoto());

        ArrayList<AudioPOJO> tracks = Staroeradio.parseTracks(page);
        assertEquals(1, tracks.size());
        assertEquals(S + "/ap/get_mp3_radio_128.php?id=37323", tracks.get(0).getUrl());
        assertEquals("На всякого мудреца довольно простоты, 1985", tracks.get(0).getName());
        assertEquals("На всякого мудреца довольно простоты, 1985", tracks.get(0).getBookName());
    }

    @Test
    public void aPartAndAPageWithoutPictures() throws IOException {
        Document part = fixture("book-parts.html", S + "/audio/14684");
        DescriptionPOJO d = Staroeradio.parseDescription(part);
        assertEquals("Моя дочь Нюша (1), 1976", d.getTitle());
        assertEquals("Яковлев Ю. (Ховкин)", d.getAutor());
        assertEquals("С.Пономарёв, Н.Радолицкая, А.Дерябина, Ж.Романенко, С.Надеждин", d.getArtist());
        assertEquals(S + "/ap/staroeradio/images/pics/035197.jpg", d.getPoster());
        assertTrue(d.getDescription(), d.getDescription().startsWith("Радиокомпозиция спектакля Куйбышевского"));
        assertEquals(S + "/ap/get_mp3_radio_128.php?id=14684", Staroeradio.parseTracks(part).get(0).getUrl());

        Document plain = fixture("book-nopics.html", S + "/audio/32019");
        d = Staroeradio.parseDescription(plain);
        assertEquals("А что же дальше ? (ЛР) (вып. 1-й из 8-ми), 1971", d.getTitle());
        assertEquals("", d.getAutor());
        assertEquals("", d.getPoster());
        assertEquals("Передача подготовлена Ленинградским радио - Литературная игра для школьников.",
                d.getDescription());
        assertEquals(S + "/ap/get_mp3_radio_128.php?id=32019", Staroeradio.parseTracks(plain).get(0).getUrl());
    }

    /** An unknown id answers 200 with an empty title and {@code ?id=}: no book, no recording. */
    @Test
    public void anUnknownIdHasNoBook() throws IOException {
        Document page = fixture("book-unknown.html", S + "/audio/99999999");
        assertTrue(parser.book(page, S + "/audio/99999999").isNull());
        try {
            Staroeradio.parseTracks(page);
            fail("no recording");
        } catch (IllegalStateException expected) {
            // the contract's "no player"
        }
    }

    /** One ranged request tells the file's length: 196,938,631 bytes at 128 kbps are 3 h 25 min. */
    @Test
    public void theLengthComesFromTheRangedAnswer() throws IOException {
        String contentRange = null;
        try (InputStream in = StaroeradioTest.class.getResourceAsStream("/fixtures/staroeradio/mp3-range.headers")) {
            for (String line : new Scanner(in, "UTF-8").useDelimiter("\\A").next().split("\r?\n")) {
                if (line.startsWith("Content-Range:")) {
                    contentRange = line.substring("Content-Range:".length()).trim();
                }
            }
        }
        assertEquals("bytes 0-1023/196938631", contentRange);
        assertEquals(12308, Staroeradio.seconds(contentRange, "1024"));
        // a 200 that ignored the range is the whole file
        assertEquals(12308, Staroeradio.seconds(null, "196938631"));
        assertEquals(0, Staroeradio.seconds(null, null));
        assertEquals(0, Staroeradio.seconds("bytes */0", null));
    }

    // ---------------------------------------------------------------- titles

    private static void title(String text, String author, String name, String readers, String year) {
        Staroeradio.Title t = Staroeradio.title(text);
        assertEquals(text, author, t.author);
        assertEquals(text, name, t.name);
        assertEquals(text, readers, t.readers);
        assertEquals(text, year, t.year);
    }

    @Test
    public void titlesSplitIntoAuthorNameReadersAndYear() {
        title("Абрамов Ф. - Две зимы и три лета - (отрывок), (ЛР), (исп.: Владимир Ярмагаев), (Зап.: 1971г.)",
                "Абрамов Ф.", "Две зимы и три лета (отрывок) (ЛР), 1971", "Владимир Ярмагаев", "1971");
        title("Абкина М., Фиников В. - Принцесса-мышка. - Берник, Бернак ! - (сказки), (исп.: Вс.Абдулов, "
                        + "И.Потоцкая, и др.), (Зап.: 1990г.)", "Абкина М., Фиников В.",
                "Принцесса-мышка. - Берник, Бернак ! (сказки), 1990", "Вс.Абдулов, И.Потоцкая", "1990");
        title("Островский А. - На всякого мудреца довольно простоты - (исп.: В.Ивченко, и др.), (Зап. по трансляции"
                + " - 21.05.1985г.)", "Островский А.", "На всякого мудреца довольно простоты, 1985", "В.Ивченко", "1985");
        title("Чехов А., Резников П., - Дачная жизнь - (исп.: Е.Стеблов, В.Изотов, и др.), (Зап.: 1979г.)",
                "Чехов А., Резников П.", "Дачная жизнь, 1979", "Е.Стеблов, В.Изотов", "1979");
        // the newer style: the radio's mark, the readers and the year in one group
        title("Аверченко А - Женщины и мужчины (ЛР Агафонов Е, Богачёв Г, Захаров В. 92г)", "Аверченко А",
                "Женщины и мужчины (ЛР), 1992", "Агафонов Е, Богачёв Г, Захаров В.", "1992");
        title("Аверченко А - Ниночка (ЛР Буров Н. Мельникова Л. Трофимов Н. 1993)", "Аверченко А",
                "Ниночка (ЛР), 1993", "Буров Н., Мельникова Л., Трофимов Н.", "1993");
        // a recording note inside the cast; the author reading
        title("Аверченко А - О детях (чит. Р.Плятт зап. 1965)", "Аверченко А", "О детях, 1965", "Р.Плятт", "1965");
        title("Ахматова А - Цветов и неживых вещей (чит. автор, зап. 1946)", "Ахматова А",
                "Цветов и неживых вещей, 1946", "Ахматова А", "1946");
        title("Асеев Н - Вспомни свои молодые года (чит.авт. зап.1980г)", "Асеев Н", "Вспомни свои молодые года, 1980",
                "Асеев Н", "1980");
        title("Рождественский Р - Наши песни-наша память (чит. Толмазов Б ч. 2 из 2х. зап. 82г.)",
                "Рождественский Р", "Наши песни-наша память, 1982", "Толмазов Б", "1982");
        title("Анна Герман. Последняя встреча - (вед. В.Татарский, авт. В.Серых, зап. 1987)", "",
                "Анна Герман. Последняя встреча, 1987", "В.Татарский", "1987");
        title("Чехов А. - Дипломат - (исп.: О.Абдулов, Р.Плятт), (Запись по трансляции - 1950г.)", "Чехов А.",
                "Дипломат, 1950", "О.Абдулов, Р.Плятт", "1950");
        // the cast's other spellings (letter-a.html; live, the search "ова"): a capital, no dot, "читают авторы"
        title("Артур Конан Дойл - Медные буки - (детектив), (Исп.: А.Ливанов, А.Быков, Б.Иванов, Л.Наумкина, "
                        + "Т.Шатилова), (Зап.: 1994г.)", "Артур Конан Дойл", "Медные буки (детектив), 1994",
                "А.Ливанов, А.Быков, Б.Иванов, Л.Наумкина, Т.Шатилова", "1994");
        title("Андроников И. - Земляк Лермонтова. - Четыре часа из жизни Блока. - (вед: автор), (Зап.: 1974г.)",
                "Андроников И.", "Земляк Лермонтова. - Четыре часа из жизни Блока., 1974", "Андроников И.", "1974");
        title("Андроников И - Вспоминая Обухову (чит автор зап. 76г.)", "Андроников И", "Вспоминая Обухову, 1976",
                "Андроников И", "1976");
        title("Ахматова А. - Мужество - (чит К.Ампилова)", "Ахматова А.", "Мужество", "К.Ампилова", "");
        title("Ахматова А., Зенкевич М. - Стихи - (читают авторы), (сборник - часть 2-я из 2-х)",
                "Ахматова А., Зенкевич М.", "Стихи (сборник - часть 2-я из 2-х)", "Ахматова А., Зенкевич М.", "");
        // a word that only starts like a cast is no cast
        title("Передача «Писатели у микрофона» — «Памяти Виктора Некрасова». Ведущий Александр Воронин. 17.09.88", "",
                "Передача «Писатели у микрофона» — «Памяти Виктора Некрасова». Ведущий Александр Воронин. 17.09.88",
                "", "");
        // "(ЛР зап. …)", "(ЛР запись по трансляции …)": a year, no reader
        title("Писатели у микрофона - А.Шалимов (ЛР зап. 1986г)", "", "Писатели у микрофона - А.Шалимов (ЛР), 1986",
                "", "1986");
        title("Давыдова Тамара. Творческий вечер (ЛР зап. 1976г)", "", "Давыдова Тамара. Творческий вечер (ЛР), 1976",
                "", "1976");
        title("Актер Федор Михайлович Никитин говорит о предстоящем 50-летии (ЛР зап.1976г)", "",
                "Актер Федор Михайлович Никитин говорит о предстоящем 50-летии (ЛР), 1976", "", "1976");
        title("Актеры ЛенТЮЗа исполняют романс (Юбилей А. Ф. Борисова) (ЛР запись по трансляции 1975г.)", "",
                "Актеры ЛенТЮЗа исполняют романс (Юбилей А. Ф. Борисова) (ЛР), 1975", "", "1975");
        // no author: the part before " - " is no name, or there is no " - "
        title("А что же дальше ? - (ЛР), (вып. 1-й из 8-ми), (Зап.: 1971г.)", "",
                "А что же дальше ? (ЛР) (вып. 1-й из 8-ми), 1971", "", "1971");
        title("Абрамов Георгий. Страницы жизни - (вед.: ??)", "", "Абрамов Георгий. Страницы жизни", "", "");
        // a dash after the groups (letter-a.html); a month in the LR group is no reader (program.html)
        title("Афанасьев А. - Как Илья из Мурома богатырём стал - (сказка) -", "Афанасьев А.",
                "Как Илья из Мурома богатырём стал (сказка)", "", "");
        title("В воскресенье, когда вы дома (ЛР Июль 1988г)", "", "В воскресенье, когда вы дома (ЛР), 1988", "", "1988");
        // what does not split stays whole
        title("Александр Блок - Двенадцать. Поэма. (ЛР Читают Георгий Сорокин, Алексей Покровский (1967)",
                "Александр Блок", "Двенадцать. Поэма. (ЛР Читают Георгий Сорокин, Алексей Покровский (1967)", "", "");
        title("А.Гладилин и В.Некраов - о смерти Г.Шпаликова и его последнем пути. 14.10.1979.", "",
                "А.Гладилин и В.Некраов - о смерти Г.Шпаликова и его последнем пути. 14.10.1979.", "", "");
    }

    @Test
    public void years() {
        assertEquals("1971", Staroeradio.year("Зап.: 24.02.1971г."));
        assertEquals("1985", Staroeradio.year("зап. 11.11.85г"));
        assertEquals("", Staroeradio.year("№3"));
    }
}
