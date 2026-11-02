package com.fanok.audiobooks.search;

import com.fanok.audiobooks.Url;
import com.fanok.audiobooks.pojo.SearcheblPOJO;
import com.fanok.audiobooks.pojo.SearchebleArrayPOJO;
import com.fanok.audiobooks.search.SearchGrouper.Offer;
import com.fanok.audiobooks.search.SearchRequest.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The author and series plates above the results: the sites' own lists (Книга в ухе's authors and series, ABMP3's
 * authors) and the authors and series of the found cards that hold the whole query by themselves, so that every
 * site's results bring plates, whichever sites are on. One plate per person however spelled ("Кинг Стивен" is
 * "Стивен Кинг") and per series; the sites' own plate wins, its link lists the most. Under a site's tab only that
 * site's plates. The most found cards first, the sites' own order next.
 */
final class SearchPlates {

    private static final class Plate {
        /** Null until a card brings a usable name and link; the cards count all the same. */
        SearchebleArrayPOJO item;
        /** The site's own series name carries its author after it ("Кингсбридж Кена Фоллетта"). */
        final boolean server;
        int cards;

        Plate(SearchebleArrayPOJO item, boolean server) {
            this.item = item;
            this.server = server;
        }
    }

    private SearchPlates() {
    }

    static SearcheblPOJO of(List<Offer> offers, SearchRequest request, SearcheblPOJO server, Provider tab) {
        Map<String, Plate> authors = new LinkedHashMap<>();
        Map<String, Plate> series = new LinkedHashMap<>();
        if (server != null) {
            for (SearchebleArrayPOJO p : server.getAutorsList()) {
                if (tab == null || Url.isHost(p.getUrl(), tab.server)) {
                    String key = key(TextNorm.person(p.getName()));
                    if (!key.isEmpty() && !authors.containsKey(key)) {
                        authors.put(key, new Plate(p, true));
                    }
                }
            }
            for (SearchebleArrayPOJO p : server.getSeriesList()) {
                if (tab == null || Url.isHost(p.getUrl(), tab.server)) {
                    String key = TextNorm.key(p.getName());
                    if (!key.isEmpty() && !series.containsKey(key)) {
                        series.put(key, new Plate(p, true));
                    }
                }
            }
        }
        for (Offer o : offers) {
            if (tab != null && o.source != tab) {
                continue;
            }
            // a card links one author: a field of several people names none of them alone
            if (o.card.authors.size() == 1 && o.holds(Field.AUTHOR, request)) {
                count(authors, key(o.card.authors.get(0)), o.book.getAutor(), o.book.getUrlAutor(), null);
            }
            if (o.holds(Field.SERIES, request)) {
                count(series, TextNorm.key(o.book.getSeries()), o.book.getSeries(), o.book.getUrlSeries(),
                        o.card.authors);
            }
        }
        SearcheblPOJO out = new SearcheblPOJO();
        out.setAutorsList(sorted(authors));
        out.setSeriesList(sorted(series));
        return out;
    }

    /** A person's identity: the words and the initials ("Ли Бо" is not "Ли Юй", "А. Толстой" not "Л. Толстой"). */
    private static String key(TextNorm.Person p) {
        return p.isEmpty() ? "" : p.words + "|" + p.initials;
    }

    /**
     * One more card of the plate {@code key}; a new plate when none is it. A series ({@code seriesAuthors} not null)
     * is also a site's own series named with its author after it: "Кингсбридж" by Кен Фоллетт is "Кингсбридж Кена
     * Фоллетта", but "Тень" is not "Тень империи Иванова".
     */
    private static void count(Map<String, Plate> plates, String key, String name, String url,
            List<TextNorm.Person> seriesAuthors) {
        if (key.isEmpty()) {
            return;
        }
        Plate plate = plates.get(key);
        if (plate == null && seriesAuthors != null) {
            for (Map.Entry<String, Plate> e : plates.entrySet()) {
                if (e.getValue().server && e.getKey().startsWith(key + " ")
                        && namesAuthor(e.getKey().substring(key.length() + 1), seriesAuthors)) {
                    plate = e.getValue();
                    break;
                }
            }
        }
        if (plate == null) {
            plate = new Plate(null, false);
            plates.put(key, plate);
        }
        if (plate.item == null) {
            try {
                plate.item = new SearchebleArrayPOJO(name.trim(), url == null ? "" : url.trim());
            } catch (RuntimeException e) {
                // no name or no link yet: counted, shown once a card brings one
            }
        }
        plate.cards++;
    }

    /**
     * Every word of {@code rest} (of three letters or more) is a form of a word of {@code authors}: they share their
     * first four letters, or the whole shorter word ("Кена Фоллетта" names "Кен Фоллетт").
     */
    private static boolean namesAuthor(String rest, List<TextNorm.Person> authors) {
        boolean any = false;
        for (String r : rest.split(" ")) {
            if (r.length() < 3) {
                continue;
            }
            boolean found = false;
            for (TextNorm.Person p : authors) {
                for (String w : p.words) {
                    int common = 0;
                    while (common < Math.min(r.length(), w.length()) && r.charAt(common) == w.charAt(common)) {
                        common++;
                    }
                    found |= common >= Math.min(4, Math.min(r.length(), w.length()));
                }
            }
            if (!found) {
                return false;
            }
            any = true;
        }
        return any;
    }

    private static ArrayList<SearchebleArrayPOJO> sorted(Map<String, Plate> plates) {
        List<Plate> list = new ArrayList<>(plates.values());
        Collections.sort(list, (a, b) -> Integer.compare(b.cards, a.cards)); // stable: ties keep the sites' order
        ArrayList<SearchebleArrayPOJO> out = new ArrayList<>(list.size());
        for (Plate p : list) {
            if (p.item != null) {
                out.add(p.item);
            }
        }
        return out;
    }
}
