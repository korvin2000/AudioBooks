package com.fanok.audiobooks.search;

import com.fanok.audiobooks.Consts;
import com.fanok.audiobooks.Url;
import com.fanok.audiobooks.source.AudioknigaLife;
import com.fanok.audiobooks.source.AudioknigiFun;
import com.fanok.audiobooks.source.SourceParser;
import com.fanok.audiobooks.source.Uknig;

/**
 * The sites the app scrapes: one row of data per site, in the order the source menu, the settings and the search
 * filter show them. Everything per-site that is not parsing lives here; a site with a {@link #parser} needs no other
 * wiring (the nine first sites keep their code in the models and have none).
 */
public enum Provider {
    AKNIGA(Url.SERVER_AKNIGA, "Akniga", "abook", "search_abook", Consts.SOURCE_ABOOK,
            Url.INDEX_AKNIGA, Url.SECTIONS_AKNIGA, Url.AUTHORS_AKNIGA, Url.PERFORMERS_AKNIGA, null),
    KNIGAVUHE(Url.SERVER, "Книга в ухе", "kniga_v_uhe", "search_kniga_v_uhe", Consts.SOURCE_KNIGA_V_UHE,
            Url.INDEX, Url.SECTIONS, Url.AUTHORS, Url.PERFORMERS, null),
    IZIBUK(Url.SERVER_IZIBUK, "ИзиБук", "izibuc", "search_izibuc", Consts.SOURCE_IZI_BUK,
            Url.INDEX_IZIBUK, Url.SECTIONS_IZIBUK, Url.AUTHORS_IZIBUK, Url.PERFORMERS_IZIBUK, null),
    ABMP3(Url.SERVER_ABMP3, "AudioBook-mp3", "audiobook_mp3", "search_abmp3", Consts.SOURCE_AUDIO_BOOK_MP3,
            Url.INDEX_ABMP3, Url.SECTIONS_ABMP3, Url.AUTHORS_ABMP3, Url.PERFORMERS_ABMP3, null),
    BAZA_KNIG(Url.SERVER_BAZA_KNIG, "База Книг", "baza_knig", "search_baza_knig", Consts.SOURCE_BAZA_KNIG,
            Url.INDEX_BAZA_KNIG, Url.SECTIONS_BAZA_KNIG, null, null, null),
    KNIGOBLUD(Url.SERVER_KNIGOBLUD, "Книгоблуд", "knigoblud", "search_knigoblud", Consts.SOURCE_KNIGOBLUD,
            Url.INDEX_KNIGOBLUD, Url.SECTIONS_KNIGOBLUD, null, null, null),
    AUDIOBOO(Url.SERVER_AUDIOBOO, "Audioboo", "audioboo", "search_audioboo", Consts.SOURCE_AUDIOBOO,
            Url.INDEX_AUDIOBOO, Url.INDEX_AUDIOBOO, Url.INDEX_AUDIOBOO, Url.INDEX_AUDIOBOO, null),
    OTRUB(Url.SERVER_OTRUB, "Отрубин", "otrub", "search_otrub", Consts.SOURCE_OTRUB,
            Url.INDEX_OTRUB, Url.SECTIONS_OTRUB, null, null, null),
    POLEKNIG(Url.SERVER_POLEKNIG, "Полекниг", "poleknig", "search_poleknig", Consts.SOURCE_POLEKNIG,
            Url.INDEX_POLEKNIG, Url.SECTIONS_POLEKNIG, null, null, null),
    UKNIG(Uknig.SERVER, "уКниг", "uknig", "search_uknig", 9,
            Uknig.INDEX, Uknig.GENRES, Uknig.AUTHORS, Uknig.READERS, new Uknig()),
    AUDIOKNIGI_FUN(AudioknigiFun.SERVER, "Аудиокниги.fun", "audioknigi_fun", "search_audioknigi_fun", 10,
            AudioknigiFun.INDEX, AudioknigiFun.GENRES, AudioknigiFun.AUTHORS, AudioknigiFun.READERS,
            new AudioknigiFun()),
    AUDIOKNIGA_LIFE(AudioknigaLife.SERVER, "Аудиокнига Life", "audiokniga_life", "search_audiokniga_life", 12,
            AudioknigaLife.INDEX, AudioknigaLife.GENRES, null, null, new AudioknigaLife());

    /** {@code https://host} without a trailing slash. */
    public final String server;
    /** The name the user sees (menu, settings, search filter, book cards). */
    public final String title;
    /** The value stored in the {@code sorce_books} preference when this is the selected source. */
    public final String prefValue;
    /** Preference key that enables the site in the multi-source search. */
    public final String prefKey;
    /** The {@code Consts.SOURCE_*} id of the site. */
    public final int sourceId;
    /** The drawer sections: the listing URL each opens, or null when the site has no such section. */
    public final String index;
    public final String sections;
    public final String authors;
    public final String performers;
    /** The site's code; null for a site whose code is in the models. Must not touch {@code Provider} while it initialises. */
    public final SourceParser parser;

    Provider(String server, String title, String prefValue, String prefKey, int sourceId,
            String index, String sections, String authors, String performers, SourceParser parser) {
        this.server = server;
        this.title = title;
        this.prefValue = prefValue;
        this.prefKey = prefKey;
        this.sourceId = sourceId;
        this.index = index;
        this.sections = sections;
        this.authors = authors;
        this.performers = performers;
        this.parser = parser;
    }

    /** Host without {@code www.}, used to filter a mixed result list by site. */
    public String host() {
        return Url.hostOf(server);
    }

    /** The provider serving {@code url} (after {@link Url#canonical}), or null for an unknown host. */
    public static Provider ofUrl(String url) {
        String canonical = url == null ? null : Url.canonical(url);
        for (Provider p : values()) {
            if (Url.isHost(canonical, p.server)) {
                return p;
            }
        }
        return null;
    }

    /** {@link #ofUrl}, but an unknown host is an error, not "no data". */
    public static Provider require(String url) {
        Provider provider = ofUrl(url);
        if (provider == null) {
            throw new IllegalArgumentException("unsupported host: " + Url.hostOf(url));
        }
        return provider;
    }

    /** The site's code when it has a parser, else null: the models' own branches handle the site. */
    public static SourceParser parserOf(String url) {
        Provider provider = ofUrl(url);
        return provider == null ? null : provider.parser;
    }

    /** The provider for a {@code Consts.SOURCE_*} id, or null. */
    public static Provider ofSource(int sourceId) {
        for (Provider p : values()) {
            if (p.sourceId == sourceId) {
                return p;
            }
        }
        return null;
    }

    /** The source selected in the settings. */
    public static Provider current() {
        return ofSource(Consts.getSOURCE());
    }
}
