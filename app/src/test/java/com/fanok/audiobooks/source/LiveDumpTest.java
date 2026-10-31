package com.fanok.audiobooks.source;

import static org.junit.Assume.assumeTrue;

import com.fanok.audiobooks.search.ListingFilters;
import com.fanok.audiobooks.search.Provider;
import com.fanok.audiobooks.search.ProviderPage;
import com.fanok.audiobooks.search.SearchUrls;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Comparator;
import java.util.List;

/**
 * Opt-in live check of every site through its adapter, never in CI: {@code LIVE_DUMP_DIR=<dir>} (and
 * {@code LIVE_DUMP_OUT=<name>}, {@code LIVE_DUMP_SITES=AKNIGA,UKNIG} optionally) writes what each feature yields —
 * listings and paging, sorts, periods, duration and genre narrowing, genres, authors, readers, search, a book's
 * metadata, card, recommendations, series, chapters, comments — to {@code <dir>/<name>/<SITE>.txt}. Pages are fetched
 * through the adapter's {@code fetch} and parsed from a copy saved under {@code <dir>/pages}, so a second run (another
 * version of the code) parses the very same pages: a diff of two outputs shows what a code change did on real pages.
 * Requests that adapters make themselves (playlists, comments, extra pages) are live in both runs.
 */
public class LiveDumpTest {

    private static final int CARDS = 40;
    private static final String QUERY = "Кинг";

    private File pages;
    /** URLs fetched live in this run: each once. */
    private final java.util.Set<String> fetched = new java.util.HashSet<>();
    private StringBuilder out;

    @Test
    public void dump() throws Exception {
        String dir = System.getenv("LIVE_DUMP_DIR");
        assumeTrue("set LIVE_DUMP_DIR to run the live check", dir != null);
        String name = System.getenv("LIVE_DUMP_OUT");
        String only = System.getenv("LIVE_DUMP_SITES");
        pages = new File(dir, "pages");
        File target = new File(dir, name == null ? "out" : name);
        pages.mkdirs();
        target.mkdirs();
        for (Provider p : Provider.values()) {
            if (only != null && !Arrays.asList(only.split(",")).contains(p.name())) {
                continue;
            }
            out = new StringBuilder();
            try {
                site(p);
            } catch (Throwable t) {
                line("ABORTED " + t);
            }
            Files.write(new File(target, p.name() + ".txt").toPath(), out.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private void site(Provider p) {
        SourceParser site = p.parser;
        Calendar today = Calendar.getInstance();
        line("# " + p + " " + p.server);
        BookPOJOHolder first = new BookPOJOHolder();
        listing(p, "index", p.index, 1, first);
        listing(p, "index p2", site.pageUrl(p.index, 2), 2, null);
        line("listsDuration(index)=" + site.listsDuration(p.index) + " cardGenre=" + site.cardGenre(p.index));
        for (ListingFilters.Duration d : new ListingFilters.Duration[]{ListingFilters.Duration.UNDER_30M,
                ListingFilters.Duration.FROM_1H_TO_2H, ListingFilters.Duration.OVER_2H}) {
            line("withDuration " + describe(d) + " -> " + site.withDuration(p.index, d));
        }
        menu(site, p.index);
        for (Sort sort : sorts(site, p.index)) {
            String url = site.sortUrl(sort, p.index);
            listing(p, "sort " + sort, url, 1, null);
            if (url != null) {
                line("sorts(" + sort + ")=" + sorts(site, url) + " genreOf=" + site.genreOf(url));
                for (ListingFilters.Period period : site.periods(url)) {
                    String with = site.withPeriod(url, period, today);
                    line("period " + period + " -> " + with + " -> " + site.periodOf(with, today));
                }
            }
        }
        String genre = null;
        if (p.sections != null) {
            ProviderPage<com.fanok.audiobooks.pojo.GenrePOJO> genres = call("genres", () -> site.genres(
                    page(site, p.sections), p.sections, 1));
            if (genres != null) {
                line("== genres " + genres.status + " more=" + genres.hasMore + " n=" + genres.items.size());
                for (Object g : genres.items) {
                    line("  " + describe(g));
                }
                genre = genres.items.isEmpty() ? null : genres.items.get(0).getUrl();
            }
        }
        if (genre != null) {
            listing(p, "genre", genre, 1, null);
            listing(p, "genre p2", site.pageUrl(genre, 2), 2, null);
            String root = site.genreOf(genre);
            line("genreOf(genre)=" + root + " sorts=" + sorts(site, genre) + " listsDuration="
                    + site.listsDuration(genre) + " cardGenre=" + site.cardGenre(genre));
            for (Sort sort : sorts(site, genre)) {
                String wide = site.sortUrl(sort, genre);
                String narrowed = wide == null || root == null ? wide : site.inGenre(wide, root);
                line("genre sort " + sort + " -> " + wide + " -> " + narrowed);
                if (narrowed != null) {
                    listing(p, "genre sort " + sort, narrowed, 1, null);
                    for (ListingFilters.Period period : site.periods(narrowed)) {
                        line("  period " + period + " -> " + site.withPeriod(narrowed, period, today));
                    }
                }
            }
        }
        people(p, "authors", p.authors, false);
        people(p, "readers", p.performers, true);
        for (SearchUrls.Category category : SearchUrls.Category.values()) {
            String url = site.searchUrl(category, QUERY, 1);
            line("== search " + category + " " + url + " p2=" + site.searchUrl(category, QUERY, 2));
            if (url == null) {
                continue;
            }
            if (category == SearchUrls.Category.BOOKS) {
                ProviderPage<com.fanok.audiobooks.pojo.BookPOJO> found = call("search", () ->
                        site.searchResults(page(site, url), 1));
                cards(found);
                List<?> hits = call("otherSourceHits", () -> site.otherSourceHits(page(site, url)));
                line("otherSourceHits n=" + (hits == null ? null : hits.size()));
            } else {
                boolean performers = category == SearchUrls.Category.PERFORMERS;
                ProviderPage<com.fanok.audiobooks.pojo.GenrePOJO> found = call("searchPeople", () ->
                        site.searchPeople(page(site, url), 1, performers));
                rows(found);
            }
        }
        if (first.book != null) {
            book(p, first.book);
        }
    }

    private void book(Provider p, com.fanok.audiobooks.pojo.BookPOJO card) {
        SourceParser site = p.parser;
        String url = card.getUrl();
        line("== book " + url);
        line("otherSourceTerms=" + site.otherSourceTerms(card) + " -> " + site.otherSourceSearchUrl(
                site.otherSourceTerms(card), 1) + " pages=" + site.otherSourcePages());
        Document doc = call("book page", () -> page(site, url));
        if (doc == null) {
            return;
        }
        line("description " + call("description", () -> describe(site.description(doc, url))));
        line("book " + call("book", () -> describe(site.book(doc, url))));
        List<?> recommendations = call("recommendations", () -> site.recommendations(doc, url));
        dumpList("recommendations", recommendations);
        dumpList("series", call("series", () -> site.series(doc, url)));
        dumpList("tracks", call("tracks", () -> site.tracks(doc, doc.location(), url)));
        line("mediaHeaders=" + site.mediaHeaders(url) + " findsOtherReaders=" + site.findsOtherReaders()
                + " probes=" + site.probesOtherReaders());
        dumpList("comments", call("comments", () -> site.comments(url, () -> false)));
        for (String link : new String[]{card.getUrlAutor(), card.getUrlArtist(), card.getUrlSeries()}) {
            if (link != null && !link.isEmpty()) {
                listing(p, "link of book", link, 1, null);
            }
        }
    }

    private void listing(Provider p, String what, String url, int pageNo, BookPOJOHolder first) {
        line("== " + what + " " + url);
        if (url == null) {
            return;
        }
        SourceParser site = p.parser;
        ProviderPage<com.fanok.audiobooks.pojo.BookPOJO> page = call(what, () -> site.listing(page(site, url), url,
                pageNo));
        cards(page);
        line("sorts=" + sorts(site, url) + " hasSort=" + site.hasSort(url) + " periods=" + site.periods(url)
                + " genreOf=" + site.genreOf(url) + " p2=" + site.pageUrl(url, pageNo + 1));
        if (first != null && page != null && !page.items.isEmpty()) {
            first.book = page.items.get(0);
        }
    }

    private void people(Provider p, String what, String url, boolean performers) {
        line("== " + what + " " + url);
        if (url == null) {
            return;
        }
        SourceParser site = p.parser;
        rows(call(what, () -> site.people(page(site, url), url, 1, performers)));
    }

    private void cards(ProviderPage<com.fanok.audiobooks.pojo.BookPOJO> page) {
        if (page == null) {
            return;
        }
        line("status=" + page.status + " more=" + page.hasMore + " n=" + page.items.size());
        for (int i = 0; i < Math.min(CARDS, page.items.size()); i++) {
            line("  " + describe(page.items.get(i)));
        }
    }

    private void rows(ProviderPage<com.fanok.audiobooks.pojo.GenrePOJO> page) {
        if (page == null) {
            return;
        }
        line("status=" + page.status + " more=" + page.hasMore + " n=" + page.items.size());
        for (int i = 0; i < Math.min(CARDS, page.items.size()); i++) {
            line("  " + describe(page.items.get(i)));
        }
    }

    private void dumpList(String what, List<?> items) {
        line(what + " n=" + (items == null ? null : items.size()));
        if (items != null) {
            for (int i = 0; i < Math.min(CARDS, items.size()); i++) {
                line("  " + describe(items.get(i)));
            }
        }
    }

    private void menu(SourceParser site, String url) {
        StringBuilder menu = new StringBuilder("menu(index)=");
        for (Sort.Entry entry : site.sorts(url)) {
            menu.append(entry.sort != null ? entry.sort.name() : "[" + entry.items + "]").append(' ');
        }
        line(menu.toString());
    }

    private static List<Sort> sorts(SourceParser site, String url) {
        List<Sort> out = new ArrayList<>();
        for (Sort.Entry entry : site.sorts(url)) {
            if (entry.sort != null) {
                out.add(entry.sort);
            }
            out.addAll(entry.items);
        }
        return out;
    }

    /** The page at {@code url} as the site fetches it, parsed from its saved copy (saved on the first run). */
    private Document page(SourceParser site, String url) throws IOException {
        File file = new File(pages, sha1(url) + ".html");
        Document live = null;
        if (fetched.add(url) || !file.exists()) {
            try {
                live = site.fetch(url); // also keeps what an adapter learns from its pages (DLE category ids)
            } catch (IOException | RuntimeException e) {
                if (!file.exists()) {
                    throw e;
                }
            }
        }
        if (!file.exists()) {
            String text = live.location() + "\n" + live.outerHtml();
            Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        }
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        int eol = text.indexOf('\n');
        return Jsoup.parse(text.substring(eol + 1), text.substring(0, eol));
    }

    private interface Call<T> {
        T get() throws Exception;
    }

    private <T> T call(String what, Call<T> call) {
        try {
            return call.get();
        } catch (Throwable t) {
            line(what + " FAILED " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return null;
        }
    }

    private void line(String s) {
        out.append(s).append('\n');
    }

    /** Every getter of a POJO, by name, long texts cut. */
    private static String describe(Object pojo) {
        if (pojo == null) {
            return "null";
        }
        StringBuilder s = new StringBuilder(pojo.getClass().getSimpleName()).append('{');
        Method[] methods = pojo.getClass().getMethods();
        Arrays.sort(methods, Comparator.comparing(Method::getName));
        for (Method m : methods) {
            String n = m.getName();
            if (m.getParameterCount() == 0 && !n.equals("getClass") && !n.equals("hashCode")
                    && (n.startsWith("get") || n.startsWith("is"))) {
                try {
                    Object v = m.invoke(pojo);
                    String text = String.valueOf(v);
                    s.append(n).append('=').append(text.length() > 120 ? text.substring(0, 120) + "…" : text)
                            .append("; ");
                } catch (Exception e) {
                    s.append(n).append("=!").append(e.getClass().getSimpleName()).append("; ");
                }
            }
        }
        return s.append('}').toString();
    }

    private static String sha1(String s) throws IOException {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : d) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static final class BookPOJOHolder {
        com.fanok.audiobooks.pojo.BookPOJO book;
    }
}
