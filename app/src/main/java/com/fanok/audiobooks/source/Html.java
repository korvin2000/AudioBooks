package com.fanok.audiobooks.source;

import androidx.annotation.Nullable;

import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.pojo.GenrePOJO;
import com.fanok.audiobooks.pojo.SeriesPOJO;

import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.logging.Logger;

/**
 * What every HTML source does with a page: lists of cards or rows where one broken item is skipped (never the page),
 * texts and links that may be absent, pager numbers and lazily loaded images. Pure and static.
 */
public final class Html {

    private static final Logger LOG = Logger.getLogger("parsers");

    private Html() {
    }

    public interface ItemParser<T> {
        @Nullable
        T parse(Element element);
    }

    public interface Card {
        BookPOJO parse(Element card) throws Exception;
    }

    public interface Row {
        GenrePOJO parse(Element row) throws Exception;
    }

    /**
     * Applies {@code parser} to every element: an element that throws is skipped and counted (logged once
     * per list), so one odd card never loses the whole list.
     */
    public static <T> ArrayList<T> each(Iterable<Element> elements, ItemParser<T> parser) {
        ArrayList<T> result = new ArrayList<>();
        int skipped = 0;
        RuntimeException first = null;
        for (Element element : elements) {
            try {
                T item = parser.parse(element);
                if (item != null) {
                    result.add(item);
                }
            } catch (RuntimeException e) {
                skipped++;
                if (first == null) {
                    first = e;
                }
            }
        }
        if (skipped > 0) {
            LOG.warning("skipped " + skipped + " unparsable item(s): " + first);
        }
        return result;
    }

    /** Parses every card on its own: one broken card is skipped, the page survives (SRCH-08). */
    public static ArrayList<BookPOJO> cards(Elements cards, Card parser) {
        ArrayList<BookPOJO> out = new ArrayList<>();
        for (Element card : cards) {
            try {
                BookPOJO book = parser.parse(card);
                if (book != null && !book.isNull()) {
                    out.add(book);
                }
            } catch (Exception ignored) {
                // a malformed card (empty title, bad URL, missing block) is not a page failure
            }
        }
        return out;
    }

    /** Parses every row on its own: one malformed row (empty name, bad URL) is skipped, not the page. */
    public static ArrayList<GenrePOJO> rows(Elements rows, Row parser) {
        ArrayList<GenrePOJO> out = new ArrayList<>();
        for (Element row : rows) {
            try {
                GenrePOJO item = parser.parse(row);
                if (item != null && !item.isNull()) {
                    out.add(item);
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** The largest page number among pager links (text, else {@code data-page}); 0 when there is none. */
    public static int lastPage(Elements links) {
        int last = 0;
        for (Element a : links) {
            last = Math.max(last, number(a.text()));
            if (a.hasAttr("data-page")) {
                last = Math.max(last, number(a.attr("data-page")));
            }
        }
        return last;
    }

    private static int number(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Trimmed text of {@code e}; "" when it is null. */
    public static String text(@Nullable Element e) {
        return e == null ? "" : e.text().trim();
    }

    /** Trimmed text of the first match of {@code css} under {@code scope}; "" when absent. */
    public static String text(Element scope, String css) {
        return text(scope.selectFirst(css));
    }

    /** True for a link to a path of the page's own site ({@code /x}); false for another host, {@code //host/x} included. */
    public static boolean ownPath(String href) {
        return href.startsWith("/") && !href.startsWith("//");
    }

    /** Absolute href of the first match of {@code css} under {@code scope}; "" when absent or unresolvable. */
    public static String href(Element scope, String css) {
        Element element = scope.selectFirst(css);
        return element == null ? "" : element.absUrl("href");
    }

    /** Absolute cover of a lazy-loaded image (data-original, data-src, then src), skipping placeholders. */
    public static String imgSrc(@Nullable Element img) {
        if (img == null) {
            return "";
        }
        for (String attr : new String[]{"data-original", "data-src", "src"}) {
            String src = img.absUrl(attr);
            if (!src.isEmpty() && !src.contains("placeholder")) {
                return src;
            }
        }
        return "";
    }

    /** A series entry from a book link and its cover; null when the link is missing. */
    @Nullable
    public static SeriesPOJO seriesItem(@Nullable Element a, @Nullable Element img) {
        String link = a == null ? "" : a.absUrl("href");
        if (link.isEmpty()) {
            return null;
        }
        SeriesPOJO seriesPOJO = new SeriesPOJO();
        seriesPOJO.setUrl(link);
        seriesPOJO.setName(a.text().trim());
        seriesPOJO.setPhoto(imgSrc(img));
        return seriesPOJO;
    }
}
