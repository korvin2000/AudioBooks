package com.fanok.audiobooks.source;

import static com.fanok.audiobooks.source.Html.text;

import com.fanok.audiobooks.model.BookPages;
import com.fanok.audiobooks.net.SiteHttp;
import com.fanok.audiobooks.pojo.AudioPOJO;
import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.pojo.DescriptionPOJO;
import com.fanok.audiobooks.pojo.GenrePOJO;
import com.fanok.audiobooks.search.ListingFilters;
import com.fanok.audiobooks.search.PeopleIndex;
import com.fanok.audiobooks.search.ProviderPage;
import com.fanok.audiobooks.search.SearchUrls;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * staroeradio.ru (probed live, 2026-11-21): Gosteleradiofond radio plays and readings, plain nginx, no wall, no cookies.
 * <ul>
 * <li>Lists are unpaged: a letter of the collection {@code /collection/<letter>} (URL-encoded Cyrillic or {@code 0-9},
 * the letters are the genre list {@code /collection}) holds up to a few thousand items in one page; the index
 * {@code /program} is the 7-day broadcast schedule; the search is an XHR {@code GET /search?q=Q} (3 letters at least)
 * answering an HTML fragment of all its hits (an empty body for none). They are paged on the device: page N is
 * {@code ?page=N} ({@code &page=N}) on the list's URL, which {@link #fetch} drops; a later page reads the list held from
 * page 1 ({@link #held}, one list), so every way in (catalog, search, a person) pages the same way.</li>
 * <li>An item is a link {@code /audio/<id>} whose text is "Автор - Название - (ЛР), (исп.: А, Б, и др.), (Зап.:
 * 1976г.)" or "Автор - Название (ЛР Фамилия И, Фамилия И 1991г)" ({@link #title}). Links to the sister archives
 * (svidetel.su, theatrologia.su, reportage.su, lektorium.su) are not the site's and are dropped. No covers,
 * durations, genres or sorts on the lists; no series, comments or people pages anywhere.</li>
 * <li>People have no pages: an author or reader is the site search for the name as the titles write it, as the
 * app's built-in lists link them ({@link PeopleIndex}, {@link #person}).</li>
 * <li>Book {@code /audio/<id>}: the same title in {@code h1}, the description with the cast in {@code .audio .grid_6}
 * beside the pictures {@code /ap/staroeradio/images/pics/*.jpg} (the first is the cover), {@code .grid_9} without
 * them. One MP3 {@code /ap/get_mp3_radio_128.php?id=<id>} (no signing, no Referer, ranges), 128 kbps CBR; no page
 * tells its length, so {@link #tracks} reads it from one ranged request. An unknown id answers 200 with an empty
 * title and {@code ?id=}.</li>
 * </ul>
 */
public final class Staroeradio implements SourceParser {

    public static final String SERVER = "https://staroeradio.ru";
    public static final String INDEX = SERVER + "/program";
    public static final String GENRES = SERVER + "/collection";
    public static final String AUTHORS = SERVER + PeopleIndex.AUTHORS;
    public static final String READERS = SERVER + PeopleIndex.READERS;

    /** Items of one page of a list paged on the device. */
    static final int PAGE_SIZE = 50;
    /** Bytes a second of the site's 128 kbps MP3s. */
    static final int BYTES_PER_SECOND = 16000;
    /** The shortest query the site's search box sends. */
    static final int MIN_QUERY = 3;

    private static final String CARD = "a:has(.mp3name), a:has(.mp3name1)";
    /** A list paged on the device: a letter, the schedule, a search. */
    private static final Pattern LIST = Pattern.compile("/(collection/[^/?#]+|program|search\\?q=[^#]*)$");
    private static final Pattern PAGE = Pattern.compile("[?&]page=(\\d*)$");
    private static final Pattern BOOK = Pattern.compile(Pattern.quote(SERVER) + "/audio/\\d+");
    private static final Pattern MP3 = Pattern.compile("[?&]id=\\d+");
    /** The trailing "(…)" groups of a title, with the " - " or ", " before each. */
    private static final Pattern GROUPS =
            Pattern.compile("(?:\\s*[-,]?\\s*\\((?:[^()]|\\([^()]*\\))*\\))+\\s*$");
    /** One "(…)" group, a name in brackets inside it included ("Л.Кудрявцева (Л.Скудатина)"). */
    private static final Pattern GROUP = Pattern.compile("\\(((?:[^()]|\\([^()]*\\))*)\\)");
    /**
     * "исп.: …", "Исп.: …", "уч.: …", "вед: …", "чит. …", "чит …", "читает …", "читают …", also after other words
     * ("стих.чит. …"); not "ведущий", "читатели".
     */
    private static final Pattern CAST = Pattern.compile(
            "(?:^|[\\s.])(?:читае?т|читают|(?:исп|уч|вед|чит)(?![а-яё])\\.?:?)\\s*(.+)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** A recording note closing a cast: "… зап. 1987", "… ч. 2 из 2х. зап. 82г.", "…, 1978". */
    private static final Pattern CAST_NOTE = Pattern.compile(
            "(?:[,\\s]+(?:[Зз]ап(?![а-яё])|ч\\.\\s*\\d).*|,?\\s+(?:18|19|20)\\d\\d\\s*г?\\.?)$");
    private static final Pattern RECORDED = Pattern.compile("^[Зз]ап(?:ись)?(?![а-яё])");
    /** A year: "1971", "1971г", "85г" (of 1985). */
    private static final Pattern YEAR = Pattern.compile("(?<!\\d)((?:18|19|20)\\d\\d|\\d\\d(?=\\s*г))(?!\\d)");
    /**
     * "(ЛР Трофимов Н 1991г)", "(ЛР Агафонов Е, Богачёв Г 92г)": the radio's mark, the readers, the year; "(ЛР зап.
     * 1986г)", "(ЛР запись по трансляции 1975г.)" name no reader.
     */
    private static final Pattern LR = Pattern.compile("^ЛР\\s+(\\D+?)\\s*(\\d{4}|\\d{2})\\s*г?\\.?$");
    /** Between two names of an LR list written without commas: "Буров Н. Мельникова Л.". */
    private static final Pattern LR_NEXT = Pattern.compile("(?<=\\s\\p{Lu}\\.?)\\s+(?=\\p{Lu}\\p{Ll})");
    /** One word of a person's name: a capital first (initials, brackets too) or a particle (Антуан де Сент-Экзюпери). */
    private static final Pattern NAME_WORD =
            Pattern.compile("[(«\"]?\\p{Lu}.*|де|ди|да|дю|фон|ван|дер|ле|ля|ла|эль|аль|бин|ибн");
    /** "и др.", "??", the scriptwriter ("авт. В.Серых"), a month ("ЛР Июль 1988г"), nothing. */
    private static final Pattern NOT_A_PERSON = Pattern.compile("(?:и\\s+)?др\\.?|\\?+|и другие|авт\\.\\s.+|"
            + "(?:[Яя]нвар|[Фф]еврал|[Мм]арт|[Аа]прел|[Мм]а[йя]|[Ии]юн|[Ии]юл|[Аа]вгуст|[Сс]ентябр|[Оо]ктябр|[Нн]оябр"
            + "|[Дд]екабр)[ья]?");
    /** The author reading: "автор", "авторы", "авт.". */
    private static final Pattern THE_AUTHOR = Pattern.compile("[Аа]вт(?:оры?)?\\.?");
    private static final Pattern AND_OTHERS = Pattern.compile("\\s+и\\s+др\\.?$");
    private static final Pattern SPACES = Pattern.compile("[\\s\\u00A0]+");
    /** A tag the search's highlighter wrote into its own markup ("Ю.З<b>а<</b>b>островский"): text, not a tag. */
    private static final Pattern HIGHLIGHT = Pattern.compile("</?b>");
    private static final Pattern RANGE_TOTAL = Pattern.compile("/(\\d+)\\s*$");

    /** The whole list behind the pages in view; replaced by the next list read. */
    private volatile Held held;

    // ---------------------------------------------------------------- SourceParser

    /** A list ({@link #list}) or a page; the search is an XHR of the collection page. */
    @Override
    public Document fetch(String url) throws IOException {
        if (!LIST.matcher(PAGE.matcher(url).replaceFirst("")).find()) {
            return SiteHttp.get(url);
        }
        return list(url, whole -> !whole.contains("/search?") ? SiteHttp.get(whole)
                : !searchable(whole) ? Jsoup.parse("", whole)
                : SiteHttp.parse(SiteHttp.execute(SiteHttp.xhr(whole, GENRES))));
    }

    /**
     * Whether the site would search for this: 3 letters at least, as its search box asks. A shorter query answers
     * megabytes ("на" 4.4 MB, "а" 12.5 MB), past what the app reads; it finds nothing here instead.
     */
    static boolean searchable(String searchUrl) throws IOException {
        String q = URLDecoder.decode(searchUrl.substring(searchUrl.indexOf("?q=") + 3), "UTF-8").trim();
        return q.codePointCount(0, q.length()) >= MIN_QUERY;
    }

    /**
     * The whole list a page of the device's paging belongs to ({@code ?page=N} dropped): read afresh for page 1, the
     * held one for a later page of the same list.
     */
    Document list(String url, BookPages.Fetch load) throws IOException {
        String whole = PAGE.matcher(url).replaceFirst("");
        Held list = held;
        if (pageOf(url) > 1 && list != null && list.url.equals(whole)) {
            return list.page;
        }
        Document page = load.get(whole);
        held = new Held(whole, page);
        return page;
    }

    @Override
    public ProviderPage<BookPOJO> listing(Document page, String url, int pageNo) {
        return parseListing(page, pageNo);
    }

    @Override
    public String pageUrl(String url, int page) {
        return page <= 1 ? url : ListingFilters.withParam(url, "page", String.valueOf(page));
    }

    @Override
    public boolean listsDuration(String url) {
        return false;
    }

    @Override
    public ProviderPage<GenrePOJO> genres(Document page, String url, int pageNo) {
        return ProviderPage.of(parseGenres(page), false);
    }

    /** The whole search answers at once; page N is cut from it on the device. */
    @Override
    public String searchUrl(SearchUrls.Category category, String query, int page) {
        return category == SearchUrls.Category.BOOKS
                ? SERVER + "/search?q=" + SearchUrls.enc(query) + "&page=" + page : null;
    }

    @Override
    public DescriptionPOJO description(Document page, String bookUrl) {
        return parseDescription(page);
    }

    /** The MP3, its length from one ranged request (0 when it does not tell). */
    @Override
    public ArrayList<AudioPOJO> tracks(Document page, String base) {
        ArrayList<AudioPOJO> tracks = parseTracks(page);
        tracks.get(0).setTime(probeSeconds(tracks.get(0).getUrl(), base));
        return tracks;
    }

    // ---------------------------------------------------------------- pure parsers

    /** Page {@code pageNo} of a letter, the schedule or a search: {@link #PAGE_SIZE} of the site's own items. */
    public static ProviderPage<BookPOJO> parseListing(Document page, int pageNo) {
        Elements own = new Elements();
        for (Element a : page.select(CARD)) {
            if (BOOK.matcher(a.absUrl("href")).matches()) {
                own.add(a);
            }
        }
        int from = Math.max(0, (pageNo - 1) * PAGE_SIZE);
        if (from >= own.size()) {
            return ProviderPage.empty();
        }
        int to = Math.min(own.size(), from + PAGE_SIZE);
        ArrayList<BookPOJO> books = Html.cards(own.subList(from, to), a -> {
            BookPOJO book = new BookPOJO();
            book.setUrl(a.absUrl("href"));
            Title title = title(text(a, ".mp3name, .mp3name1"));
            book.setName(title.name);
            book.setAutor(title.author);
            book.setUrlAutor(person(title.author));
            book.setArtist(title.readers);
            book.setUrlArtist(person(title.readers));
            return book;
        });
        return ProviderPage.of(books, to < own.size(), to - from);
    }

    /** The letters of {@code /collection}. */
    public static ArrayList<GenrePOJO> parseGenres(Document page) {
        return Html.rows(page.select(".letters a[href*=/collection/]"),
                a -> new GenrePOJO(a.text().trim(), a.absUrl("href")));
    }

    public static DescriptionPOJO parseDescription(Document page) {
        DescriptionPOJO d = new DescriptionPOJO();
        Title title = title(text(page, ".audio-head h1"));
        d.setTitle(title.name);
        d.setAutor(title.author);
        d.setAutorUrl(person(title.author));
        d.setArtist(title.readers);
        d.setArtistUrl(person(title.readers));
        d.setPoster(Html.href(page, ".audio .images a[href$=.jpg]"));
        d.setDescription(Html.paragraphs(page.selectFirst(".audio > .grid_6, .audio > .grid_9")));
        return d;
    }

    /** The one MP3 of the page; a page without one (an unknown id: {@code ?id=}) is an {@link IllegalStateException}. */
    public static ArrayList<AudioPOJO> parseTracks(Document page) {
        Element audio = page.selectFirst("audio#radio-player[src]");
        String url = audio == null ? "" : audio.absUrl("src");
        if (!MP3.matcher(url).find()) {
            throw new IllegalStateException("staroeradio: no recording on " + page.location());
        }
        String name = title(text(page, ".audio-head h1")).name;
        AudioPOJO track = new AudioPOJO();
        track.setBookName(name);
        track.setName(name);
        track.setUrl(url);
        ArrayList<AudioPOJO> tracks = new ArrayList<>();
        tracks.add(track);
        return tracks;
    }

    /**
     * Seconds of a 128 kbps file whose ranged answer says {@code Content-Range: bytes 0-1023/<total>}, or whose whole
     * answer (a 200 ignoring the range) says {@code Content-Length}; 0 without.
     */
    static int seconds(String contentRange, String contentLength) {
        Matcher total = RANGE_TOTAL.matcher(contentRange == null ? "" : contentRange);
        String bytes = total.find() ? total.group(1)
                : contentRange == null && contentLength != null && contentLength.matches("\\d+") ? contentLength : "0";
        return (int) (Long.parseLong(bytes) / BYTES_PER_SECOND);
    }

    /** The parts of an item's title; a title that does not split is all {@link #name}. */
    static final class Title {
        String author = "";
        String name = "";
        /** The cast or the reader, "и др." left out. */
        String readers = "";
        String year = "";
    }

    /**
     * "Автор - Название - (ЛР), (исп.: А, Б, и др.), (Зап.: 1976г.), (2)": the author (when the part before the first
     * " - " is a name), the cast, the recording year, and the name with the other groups kept ("Название (ЛР) (2),
     * 1976"). "(читает автор)" / "(вед.: автор)" make the author the reader.
     */
    static Title title(String text) {
        Title t = new Title();
        String full = SPACES.matcher(HIGHLIGHT.matcher(text).replaceAll("")).replaceAll(" ").trim()
                .replaceFirst("\\s+-$", ""); // "Название - (сказка) -"
        Matcher tail = GROUPS.matcher(full);
        int cut = tail.find() ? tail.start() : full.length();
        String head = full.substring(0, cut).replaceFirst("[\\s,-]+$", "");
        if (head.isEmpty()) {
            t.name = full;
            return t;
        }
        int dash = head.indexOf(" - ");
        String who = dash > 0 ? head.substring(0, dash).replaceFirst("[\\s,]+$", "") : "";
        if (!who.isEmpty() && isPerson(who)) {
            t.author = who;
            head = head.substring(dash + 3);
        }
        StringBuilder name = new StringBuilder(head);
        Matcher group = GROUP.matcher(full.substring(cut));
        while (group.find()) {
            String g = group.group(1).trim();
            Matcher cast = CAST.matcher(g);
            Matcher lr = LR.matcher(g);
            if (cast.find()) {
                t.readers = join(t.readers, cast(cast.group(1), t));
            } else if (RECORDED.matcher(g).find() && !year(g).isEmpty()) {
                t.year = t.year.isEmpty() ? year(g) : t.year;
            } else if (lr.matches()) {
                name.append(" (ЛР)");
                if (!RECORDED.matcher(lr.group(1)).find()) {
                    t.readers = join(t.readers, cast(LR_NEXT.matcher(lr.group(1)).replaceAll(", "), t));
                }
                t.year = year(lr.group(2) + "г");
            } else if (!g.isEmpty()) {
                name.append(" (").append(g).append(')');
            }
        }
        t.name = name + (t.year.isEmpty() ? "" : ", " + t.year);
        return t;
    }

    /** True for "Чехов А.", "Андерсен Х.К.", "Чехов А., Ройзман З.", "Антуан де Сент-Экзюпери"; not "По дорогам сказки". */
    static boolean isPerson(String text) {
        for (String one : text.split(",")) {
            String[] words = one.trim().split(" ");
            if (one.trim().isEmpty() || words.length > 4) {
                return false;
            }
            for (String word : words) {
                if (!NAME_WORD.matcher(word).matches()) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * "А.Иванов, Б.Петров, и др." without the "и др."; "автор" is the title's author; a recording note after the names
     * ("зап. 1965") gives the year when the title has none yet.
     */
    private static String cast(String list, Title t) {
        Matcher note = CAST_NOTE.matcher(list);
        if (note.find()) {
            if (t.year.isEmpty()) {
                t.year = year(note.group());
            }
            list = list.substring(0, note.start());
        }
        String out = "";
        for (String one : list.split("[,;]")) {
            String name = AND_OTHERS.matcher(one.trim()).replaceFirst("");
            if (THE_AUTHOR.matcher(name).matches()) {
                name = t.author;
            }
            if (!NOT_A_PERSON.matcher(name).matches()) {
                out = join(out, name);
            }
        }
        return out;
    }

    /** The first year of {@code text} with its century ("85г" is 1985); "" for none. */
    static String year(String text) {
        Matcher year = YEAR.matcher(text);
        return !year.find() ? "" : year.group(1).length() == 2 ? "19" + year.group(1) : year.group(1);
    }

    private static String join(String list, String more) {
        return list.isEmpty() || more.isEmpty() ? list + more : list + ", " + more;
    }

    /** The site search for the first of {@code names}: a person's books ("" for none). */
    static String person(String names) {
        String first = names.split(",")[0].trim();
        return first.isEmpty() ? "" : SERVER + "/search?q=" + SearchUrls.segment(first);
    }

    private static int pageOf(String url) {
        Matcher page = PAGE.matcher(url);
        return page.find() && !page.group(1).isEmpty() ? Integer.parseInt(page.group(1)) : 1;
    }

    /** Asks as the book page's audio element would: a media subresource of the page, not a navigation. */
    private static int probeSeconds(String mp3, String bookUrl) {
        try {
            Connection probe = SiteHttp.connect(mp3).referrer(bookUrl).header("Range", "bytes=0-1023")
                    .header("Accept", "*/*").header("Sec-Fetch-Dest", "audio").header("Sec-Fetch-Mode", "no-cors")
                    .header("Sec-Fetch-Site", "same-origin").ignoreContentType(true).maxBodySize(1024);
            probe.request().removeHeader("Sec-Fetch-User").removeHeader("Upgrade-Insecure-Requests");
            Connection.Response answer = SiteHttp.execute(probe).bufferUp(); // reads the 1 KB and frees the connection
            return seconds(answer.header("Content-Range"), answer.header("Content-Length"));
        } catch (IOException e) {
            return 0; // the length is a nicety: the player still has the file
        }
    }

    private static final class Held {
        final String url;
        final Document page;

        Held(String url, Document page) {
            this.url = url;
            this.page = page;
        }
    }
}
