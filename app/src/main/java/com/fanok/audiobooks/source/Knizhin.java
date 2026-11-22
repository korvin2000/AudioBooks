package com.fanok.audiobooks.source;

import java.util.Collections;
import java.util.Set;

/**
 * knizhin.net (probed live, 2026-11-21): lis10book.com's engine ({@link Lis10book}) with a catalog of its own (other
 * record ids and totals) and the same audio. Every HTML page is the site's own Turnstile guard, so the app reads only
 * its open JSON API, where an HTML answer is an error; a book is its public page {@code /book/<slug>/}, read as
 * {@code /api/books/<slug>}.
 * <ul>
 * <li>books {@code /api/books?audio=full} (about half of the catalog: the rest is premium, a LitRes trial or taken
 * down), {@code &genre=<slug>}, sorts {@code sort=popular|rating|duration} (else newest), no periods,
 * {@code dur=short|medium|long}; 24 cards a page, which name no cover.</li>
 * <li>the books API ignores {@code q}: the book search is the header's suggestions {@code /api/search?q=<words>},
 * at most 24 cards of a slug, title, author name and nothing else, one page.</li>
 * <li>authors {@code /api/authors} and readers {@code /api/narrators}, 48 a page, their search {@code ?q=}; the books
 * of one {@code /api/authors/<slug>}, a series {@code /api/series/<slug>}, genres {@code /api/genres}.</li>
 * <li>book {@code /api/books/<slug>} (no {@code coverPath}: its own covers answer 410, a record with a
 * {@code coverHash} has lis10book.com's {@code covers/<playlistId>.jpg}), {@code /similar}, {@code /comments} (all
 * in one answer, {@code limit} and {@code offset} ignored, no {@code hasMore}). A genre may count no book at all.
 * Chapters {@code /api/p/<slug>}: signed MP3s ({@code h=}, {@code e=}) on s2.knizhin.net, which lis10book.com uses
 * too and which answers 403 without a Referer of the site; a LitRes trial is {@code fragment}, a taken-down book
 * {@code blocked}.</li>
 * </ul>
 */
public final class Knizhin extends Lis10book {

    public static final String SERVER = "https://knizhin.net";
    public static final String INDEX = SERVER + "/api/books?audio=full";
    public static final String GENRES = SERVER + "/api/genres";
    public static final String AUTHORS = SERVER + "/api/authors";
    public static final String READERS = SERVER + "/api/narrators";
    /** The header's suggestions: the book search. */
    public static final String SUGGESTIONS = SERVER + "/api/search?q=";
    /** The audio host, shared with lis10book.com. */
    static final String MEDIA = "s2.knizhin.net";

    public Knizhin() {
        super(new Site(SERVER, "/book/", Lis10book.SERVER + "/wp-content/uploads/").suggestionSearch());
    }

    @Override
    public String referer() {
        return SERVER + "/";
    }

    @Override
    public Set<String> refererHosts() {
        return Collections.singleton(MEDIA);
    }
}
