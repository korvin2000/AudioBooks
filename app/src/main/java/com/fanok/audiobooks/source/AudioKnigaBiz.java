package com.fanok.audiobooks.source;

/**
 * audio-kniga.biz (also audioknigi.com.ua, the same site and ids): the {@link Abmp3Engine} with audiobook-mp3.com's
 * cards, rows and alphabet, its own catalog. Listings and the readers' list stop at page {@link #MAX_PAGE} (the site
 * answers 403 past it); the Order menu is new and the Top-100 ({@code /top}, paged on). A card's and a search hit's
 * title is "Author - Title"; a hit has no cover the page could derive (its CDN folder is not the book's id). The book
 * page lists "Исполнитель" and its time and views above the player, the genre above the cover; it links no series
 * ("Без серии" even for a book of one). Chapters: a playlist on the redirectto.cc CDN (shard s04), Referer this site;
 * a long book is often one MP3 whose chapters are time ranges.
 */
public final class AudioKnigaBiz extends Abmp3Engine {

    public static final String SERVER = "https://audio-kniga.biz";
    public static final String INDEX = SERVER + "/?page=";
    public static final String GENRES = SERVER + "/genres";
    public static final String AUTHORS = SERVER + "/authors?page=";
    public static final String READERS = SERVER + "/performers?page=";
    public static final String TOP = SERVER + "/top";
    /** The last page of a listing the site serves: {@code ?page=100} and later answer HTTP 403. */
    static final int MAX_PAGE = 99;

    public AudioKnigaBiz() {
        super(new Site(SERVER)
                .top(TOP)
                .maxPage(MAX_PAGE)
                .byline(Byline.AUTHOR_FIRST)
                .referer(SERVER + "/")
                .sharedFiles()
                .bookPage(".abook_image", "h1", ".abook-desc", ".panel-item, .abook_genre",
                        ".book-player span:has(.fa-clock-o)", ".book-player span:has(.fa-eye)"));
    }
}
