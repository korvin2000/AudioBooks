package com.fanok.audiobooks.source;

/**
 * aubook.org (probed live, 2026-11-21): the {@link Aukniga} engine with the same slugs and audio and a larger catalog
 * (≈45.9k books against ≈30.7k), behind Cloudflare. Its managed challenge covers only the home page {@code /} from
 * abroad, so nothing reads it: the catalog is {@code /books}, and the home page's popular list is not offered. Routes,
 * all paged with {@code /p/N}: {@code /books}, a genre {@code /cat/<slug>} (all of them {@code /cats}), an author
 * {@code /author/<slug>} (index {@code /authors}, 50 a page), a reader {@code /voice-actor/<slug>} (no index,
 * {@code /voice-actors} is 404), a book {@code /book/<slug>}, the search {@code /search?q=Q} (one page, {@code page}
 * ignored). Everything else as on aukniga.
 */
public final class Aubook extends Aukniga {

    public static final String SERVER = "https://aubook.org";
    public static final String INDEX = SERVER + "/books";
    public static final String GENRES = SERVER + "/cats";
    public static final String AUTHORS = SERVER + "/authors";
    public static final String READERS = null;

    public Aubook() {
        super(INDEX, SERVER + "/search", null);
    }
}
