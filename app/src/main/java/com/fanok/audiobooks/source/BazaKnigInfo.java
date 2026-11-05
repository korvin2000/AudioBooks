package com.fanok.audiobooks.source;

/**
 * baza-knig.info, a mirror of baza-knig.top's catalog (same titles and slugs, older ids) on the {@link Abmp3Engine}:
 * behind Cloudflare, blocked from Russia. Listings, the readers' list and each letter of the authors stop at page
 * {@link #MAX_PAGE} (the site answers 403 past it): the index lists about 1,200 of its 81,000 books, the genres the
 * rest. The Order menu is new and a random book ({@code /random-book}, a redirect the site does not link); there is no
 * top. Cards and hits read "Title - Author", a card's author link sits by its title, its reader and series
 * ("Серия ... (N)") in its info block. The book page has its own layout: title, author and reader in the heading,
 * genres above it, cover, "Время звучания" and the series beside the annotation; the series page lists the books with
 * their numbers. The authors' index pages 50 a page and by letter (each letter paged too). Chapters: a playlist on the
 * redirectto.cc CDN (shard s01), Referer this site, one MP3 per chapter; a hit's cover is derived from its link.
 */
public final class BazaKnigInfo extends Abmp3Engine {

    public static final String SERVER = "https://baza-knig.info";
    public static final String INDEX = SERVER + "/?page=";
    public static final String GENRES = SERVER + "/genres";
    public static final String AUTHORS = SERVER + "/authors?page=";
    public static final String READERS = SERVER + "/performers?page=";
    public static final String RANDOM = SERVER + "/random-book";
    /** The covers' CDN path of a book: its id's digits, then its slug. */
    static final String COVERS = "https://9giiu0g54k8c.redirectto.cc/s01/";
    /** The last page of a listing the site serves: {@code ?page=100} and later answer HTTP 403. */
    static final int MAX_PAGE = 99;

    public BazaKnigInfo() {
        super(new Site(SERVER)
                .random(RANDOM)
                .maxPage(MAX_PAGE)
                .byline(Byline.AUTHOR_LAST)
                .hitCovers(COVERS, "")
                .referer(SERVER + "/")
                .seriesOnPage()
                .bookPage(".book_cover img", ".book_title_name", ".book_description",
                        ".book_title_elem, .book_genre_pretitle, .book_series", ".book_blue_block",
                        ".book_actions_plays")
                .rows("h2 a", ".genre_item_books_count, .author_item_books_count")
                .letters(".authors_letters_block a"));
    }
}
