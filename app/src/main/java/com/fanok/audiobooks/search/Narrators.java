package com.fanok.audiobooks.search;

import android.util.Log;

import com.fanok.audiobooks.App;
import com.fanok.audiobooks.net.SiteHttp;
import com.fanok.audiobooks.pojo.GenrePOJO;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import okhttp3.Request;
import okhttp3.Response;

/**
 * fantlab.ru's narrators («дикторы»): which readers have a card there (the FANTLAB list of {@link PeopleIndex}, a
 * name matched in any word order) and what the card says ({@link #load}). Readers lists mark those people
 * ({@link GenrePOJO#getCard}); activity.NarratorActivity shows the card.
 */
public final class Narrators {

    private static final String TAG = "Narrators";

    /** The size of the «ищем фото» picture fantlab serves as the photo of a narrator who has none. */
    private static final String NO_PHOTO = "14336";

    /** fantlab's own markup left in a title: "[work=414771]Циркезонье[/work]". */
    private static final Pattern BB_CODE = Pattern.compile("\\[/?[a-z]+(=[^\\]]*)?]");

    /** A name's {@link #key} → its card's url; read from the asset on first use. */
    private static Map<String, String> cards;

    private Narrators() {
    }

    /** Marks each reader of {@code page} who has a card; reads the asset on first use, so off the main thread. */
    public static ProviderPage<GenrePOJO> mark(ProviderPage<GenrePOJO> page) {
        for (GenrePOJO person : page.items) {
            person.setCard(card(person.getName()));
        }
        return page;
    }

    /** The card of the narrator {@code name}, or null. */
    static synchronized String card(String name) {
        if (cards == null) {
            try (InputStream in = App.getInstance().getAssets().open(PeopleIndex.ASSET)) {
                cards = cards(PeopleIndex.of(in));
            } catch (IOException e) {
                Log.w(TAG, "no narrators", e);
                cards = new HashMap<>();
            }
        }
        return name == null ? null : cards.get(key(name));
    }

    static Map<String, String> cards(PeopleIndex index) {
        Map<String, String> cards = new HashMap<>();
        for (GenrePOJO narrator : index.list("FANTLAB", true)) {
            String key = key(narrator.getName());
            if (!cards.containsKey(key)) { // two narrators of one name: the first, as fantlab lists them
                cards.put(key, narrator.getUrl());
            }
        }
        return cards;
    }

    /** A name's words in lower case, ё as е, sorted: "Клюквин Александр" and "Александр Клюквин" meet. */
    static String key(String name) {
        String[] words = name.toLowerCase(Locale.ROOT).replace('ё', 'е').split("[^\\p{L}\\p{N}]+");
        Arrays.sort(words);
        StringBuilder key = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty()) {
                key.append(key.length() == 0 ? "" : " ").append(word);
            }
        }
        return key.toString();
    }

    /** The card at {@code url}, read now; without fantlab's stand-in photo. */
    public static Card load(String url) throws IOException {
        Card card = parse(SiteHttp.get(url));
        if (!card.photo.isEmpty()) {
            Request head = new Request.Builder().url(card.photo).head().build();
            try (Response photo = App.getOkHttpClient().newCall(head).execute()) {
                if (!photo.isSuccessful() || NO_PHOTO.equals(photo.header("Content-Length"))) {
                    card.photo = "";
                }
            } catch (IOException e) { // the card stands without the check; the photo may still load
                Log.w(TAG, "photo " + card.photo, e);
            }
        }
        return card;
    }

    /** What a card says; a part the card lacks is empty. */
    public static final class Card {
        public final String name, bio;
        public String photo;
        /** "Страна: Россия", "Дата рождения: 29 декабря 1942 г.", ... */
        public final List<String> facts = new ArrayList<>();
        /** "Title — Author, year", as the card lists its audiobooks. */
        public final List<String> books = new ArrayList<>();

        Card(String name, String photo, String bio) {
            this.name = name;
            this.photo = photo;
            this.bio = bio;
        }
    }

    /** A card page ({@code https://fantlab.ru/dictorN}). */
    public static Card parse(Document page) {
        Element info = page.getElementById("autor_info");
        Element h1 = page.selectFirst("h1[itemprop=name]");
        Element img = info == null ? null : info.selectFirst("img.adaptimg");
        StringBuilder bio = new StringBuilder();
        Element about = info == null ? null : info.selectFirst(".person-info-bio");
        for (Element p : about == null ? new ArrayList<Element>() : about.select("p")) {
            if (!p.text().isEmpty()) {
                bio.append(bio.length() == 0 ? "" : "\n\n").append(p.text());
            }
        }
        Card card = new Card(h1 == null ? "" : h1.text().replaceFirst("^Диктор\\s*—\\s*", ""),
                img == null ? "" : img.absUrl("src"),
                bio.length() == 0 && about != null ? about.text() : bio.toString());
        if (info != null) {
            for (Element row : info.select("table.person-info-top-info tr")) {
                if (row.children().size() == 2) {
                    card.facts.add(row.child(0).text() + " " + row.child(1).text());
                }
            }
        }
        for (Element book : page.select(".editions_block div[id^=edition-]")) {
            String title = BB_CODE.matcher(book.attr("etitle")).replaceAll(""), author = book.attr("author");
            String year = book.attr("year");
            card.books.add(title + (author.isEmpty() ? "" : " — " + author) + (year.isEmpty() ? "" : ", " + year));
        }
        return card;
    }
}
