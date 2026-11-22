package com.fanok.audiobooks.source;

/**
 * 2knigi.net (probed live, 2026-11-21): another Nuxt front of yakniga.org's GraphQL backend ({@code POST /graphql},
 * {@code Origin} this site), the {@link Yakniga} engine with this site's routes. Same records and ids (63,605 full
 * books, 57 genres), chapters and sorts; the answers to this origin give each book, author, reader, genre and series
 * a {@code uniqUrl} ("2409897-syn-pomeschika-kniga-14_semin-nikita": a number of this front, not the record's id)
 * that names its page and looks it up ({@code book(uniqUrl)}, {@code genre(uniqUrl)}, ...).
 * <ul>
 * <li>Pages: a book {@code /kniga/<uniqUrl>}, a genre {@code /ganri/<uniqUrl>}, an author {@code /avtor/<uniqUrl>}, a
 * reader {@code /golos/<uniqUrl>}, a series {@code /serii/<alias>} ({@code series(aliasName)}), the genres
 * {@code /ganri} (the routes of the site's Nuxt router); listings page with {@code ?page=N}. The app's catalog is
 * {@code /?by_date=desc} (the home page shows curated shelves), its authors and readers lists {@code /avtor} and
 * {@code /golos} (the API's: the site has no such pages, as yakniga.org) and its search {@code /poisk?q=} (the site's
 * {@code /poisk} keeps the words in the script, not in the URL).</li>
 * <li>The site's own menus: "Новинки" and "Популярные" are curated lists of this front ({@code by_fixed_fresh},
 * {@code by_fixed_top}: 50 books), a genre's, author's or reader's "Популярные" is {@code by_pop}; the API sorts by
 * date and title as well. The HTML search {@code /search?q=} answers 301 then 404.</li>
 * <li>Chapters and covers are MP3 and WebP files under {@code /2lib/<hex>} on the site itself (yakniga's
 * {@code /yafile/} files, same bytes), ranges, no Referer needed.</li>
 * </ul>
 */
public final class TwoKnigi extends Yakniga {

    public static final String SERVER = "https://2knigi.net";
    /** Every full book, newest first: the catalog. */
    public static final String INDEX = SERVER + "/?by_date=desc";
    public static final String GENRES = SERVER + "/ganri";
    public static final String AUTHORS = SERVER + "/avtor";
    public static final String READERS = SERVER + "/golos";

    static final Site TWO_KNIGI = new Site(SERVER, "uniqUrl")
            .route(Kind.GENRES, "ganri").route(Kind.AUTHORS, "avtor").route(Kind.READERS, "golos")
            .route(Kind.SEARCH, "poisk").route(Kind.GENRE, "ganri/*").route(Kind.AUTHOR, "avtor/*")
            .route(Kind.READER, "golos/*").route(Kind.SERIES, "serii/*").route(Kind.BOOK, "kniga/*")
            .book("uniqUrl:$a", "uniqUrl");

    public TwoKnigi() {
        super(TWO_KNIGI);
    }
}
