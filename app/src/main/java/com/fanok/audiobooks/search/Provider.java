package com.fanok.audiobooks.search;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fanok.audiobooks.Consts;
import com.fanok.audiobooks.Url;
import com.fanok.audiobooks.source.Abmp3;
import com.fanok.audiobooks.source.AknigXyz;
import com.fanok.audiobooks.source.Akniga;
import com.fanok.audiobooks.source.Aknigi24;
import com.fanok.audiobooks.source.Audioboo;
import com.fanok.audiobooks.source.AudioknigaLife;
import com.fanok.audiobooks.source.AudioknigaOne;
import com.fanok.audiobooks.source.AudioknigaOnline;
import com.fanok.audiobooks.source.AudioknigiFun;
import com.fanok.audiobooks.source.AudioknigiPro;
import com.fanok.audiobooks.source.BazaKnig;
import com.fanok.audiobooks.source.Biblus;
import com.fanok.audiobooks.source.Bookish;
import com.fanok.audiobooks.source.Golosknig;
import com.fanok.audiobooks.source.Golosom;
import com.fanok.audiobooks.source.Izibuk;
import com.fanok.audiobooks.source.Knigavuhe;
import com.fanok.audiobooks.source.Knigoblud;
import com.fanok.audiobooks.source.Lis10book;
import com.fanok.audiobooks.source.Otrub;
import com.fanok.audiobooks.source.Poleknig;
import com.fanok.audiobooks.source.SlushatKnigi;
import com.fanok.audiobooks.source.SourceParser;
import com.fanok.audiobooks.source.Uknig;

import java.util.ArrayList;
import java.util.List;

/**
 * The sites the app scrapes: one row of data per site, in the order the source menu, the settings and the search
 * filter show them. Everything per-site that is not parsing lives here; the site's code is its {@link #parser}, so a
 * new site is a new adapter and one row here, with no other wiring.
 */
public enum Provider {
    AKNIGA(Url.SERVER_AKNIGA, "Akniga", "abook", "search_abook", Consts.SOURCE_ABOOK, "Akniga",
            Url.INDEX_AKNIGA, Url.SECTIONS_AKNIGA, Url.AUTHORS_AKNIGA, Url.PERFORMERS_AKNIGA, new Akniga()),
    KNIGAVUHE(Url.SERVER, "Книга в ухе", "kniga_v_uhe", "search_kniga_v_uhe", Consts.SOURCE_KNIGA_V_UHE,
            "KnigaVUhe", Url.INDEX, Url.SECTIONS, Url.AUTHORS, Url.PERFORMERS, new Knigavuhe()),
    IZIBUK(Url.SERVER_IZIBUK, "ИзиБук", "izibuc", "search_izibuc", Consts.SOURCE_IZI_BUK, "Izibuk",
            Url.INDEX_IZIBUK, Url.SECTIONS_IZIBUK, Url.AUTHORS_IZIBUK, Url.PERFORMERS_IZIBUK, new Izibuk()),
    ABMP3(Url.SERVER_ABMP3, "AudioBook-mp3", "audiobook_mp3", "search_abmp3", Consts.SOURCE_AUDIO_BOOK_MP3,
            "AudioBookMP3", Url.INDEX_ABMP3, Url.SECTIONS_ABMP3, Url.AUTHORS_ABMP3, Url.PERFORMERS_ABMP3, new Abmp3()),
    BAZA_KNIG(Url.SERVER_BAZA_KNIG, "База Книг", "baza_knig", "search_baza_knig", Consts.SOURCE_BAZA_KNIG,
            "BazaKnig", Url.INDEX_BAZA_KNIG, Url.SECTIONS_BAZA_KNIG, null, null, new BazaKnig()),
    KNIGOBLUD(Url.SERVER_KNIGOBLUD, "Книгоблуд", "knigoblud", "search_knigoblud", Consts.SOURCE_KNIGOBLUD,
            "Knigoblud", Url.INDEX_KNIGOBLUD, Url.SECTIONS_KNIGOBLUD, null, null, new Knigoblud(), IZIBUK),
    AUDIOBOO(Url.SERVER_AUDIOBOO, "Audioboo", "audioboo", "search_audioboo", Consts.SOURCE_AUDIOBOO, "Audioboo",
            Url.INDEX_AUDIOBOO, Url.INDEX_AUDIOBOO, Url.INDEX_AUDIOBOO, Url.INDEX_AUDIOBOO, new Audioboo()),
    OTRUB(Url.SERVER_OTRUB, "Отрубин", "otrub", "search_otrub", Consts.SOURCE_OTRUB, "Otrub",
            Url.INDEX_OTRUB, Url.SECTIONS_OTRUB, null, null, new Otrub(), IZIBUK),
    POLEKNIG(Url.SERVER_POLEKNIG, "Полекниг", "poleknig", "search_poleknig", Consts.SOURCE_POLEKNIG, "Poleknig",
            Url.INDEX_POLEKNIG, Url.SECTIONS_POLEKNIG, null, null, new Poleknig()),
    UKNIG(Uknig.SERVER, "уКниг", "uknig", "search_uknig", 9, "Uknig",
            Uknig.INDEX, Uknig.GENRES, Uknig.AUTHORS, Uknig.READERS, new Uknig(), POLEKNIG),
    AUDIOKNIGI_FUN(AudioknigiFun.SERVER, "Аудиокниги.fun", "audioknigi_fun", "search_audioknigi_fun", 10,
            "AudioknigiFun", AudioknigiFun.INDEX, AudioknigiFun.GENRES, AudioknigiFun.AUTHORS,
            AudioknigiFun.READERS, new AudioknigiFun()),
    AKNIG_XYZ(AknigXyz.SERVER, "Aknig.xyz", "aknig_xyz", "search_aknig_xyz", 11, "AknigXyz",
            AknigXyz.INDEX, AknigXyz.GENRES, null, null, new AknigXyz()),
    AUDIOKNIGA_LIFE(AudioknigaLife.SERVER, "Аудиокнига Life", "audiokniga_life", "search_audiokniga_life", 12,
            "AudioknigaLife", AudioknigaLife.INDEX, AudioknigaLife.GENRES, null, null, new AudioknigaLife()),
    AKNIGI24(Aknigi24.SERVER, "Aknigi24", "aknigi24", "search_aknigi24", 13, "Aknigi24",
            Aknigi24.INDEX, Aknigi24.GENRES, Aknigi24.AUTHORS, Aknigi24.READERS, new Aknigi24()),
    LIS10BOOK(Lis10book.SERVER, "Lis10book", "lis10book", "search_lis10book", 14, "Lis10book",
            Lis10book.INDEX, Lis10book.GENRES, Lis10book.AUTHORS, Lis10book.READERS, new Lis10book()),
    AUDIOKNIGA_ONE(AudioknigaOne.SERVER, "Audiokniga.one", "audiokniga_one", "search_audiokniga_one", 15,
            "AudioknigaOne", AudioknigaOne.INDEX, AudioknigaOne.GENRES, AudioknigaOne.AUTHORS, AudioknigaOne.READERS,
            new AudioknigaOne()),
    BIBLUS(Biblus.SERVER, "Библус", "biblus", "search_biblus", 16, "Biblus",
            Biblus.INDEX, Biblus.GENRES, null, null, new Biblus(), KNIGOBLUD),
    AUDIOKNIGA_ONLINE(AudioknigaOnline.SERVER, "Audiokniga-online", "audiokniga_online", "search_audiokniga_online",
            17, "AudioknigaOnline", AudioknigaOnline.INDEX, AudioknigaOnline.GENRES, null, null,
            new AudioknigaOnline()),
    SLUSHAT_KNIGI(SlushatKnigi.SERVER, "Slushat-knigi", "slushat_knigi", "search_slushat_knigi", 18, "SlushatKnigi",
            SlushatKnigi.INDEX, SlushatKnigi.GENRES, null, null, new SlushatKnigi()),
    AUDIOKNIGI_PRO(AudioknigiPro.SERVER, "Audioknigi.pro", "audioknigi_pro", "search_audioknigi_pro", 19,
            "AudioknigiPro", AudioknigiPro.INDEX, AudioknigiPro.GENRES, AudioknigiPro.AUTHORS, AudioknigiPro.READERS,
            new AudioknigiPro()),
    BOOKISH(Bookish.SERVER, "Bookish", "bookish", "search_bookish", 20, "Bookish",
            Bookish.INDEX, Bookish.GENRES, null, null, new Bookish()),
    GOLOSKNIG(Golosknig.SERVER, "Golosknig", "golosknig", "search_golosknig", 21, "Golosknig",
            Golosknig.INDEX, Golosknig.GENRES, Golosknig.AUTHORS, Golosknig.READERS, new Golosknig(), AKNIGA),
    GOLOSOM(Golosom.SERVER, "Голосом", "golosom", "search_golosom", 22, "Golosom",
            Golosom.INDEX, Golosom.GENRES, null, null, new Golosom(), KNIGOBLUD);

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
    /** The folder of the site's downloads below the download root; never renamed: saved books are found by it. */
    public final String folder;
    /** The drawer sections: the listing URL each opens, or null when the site has no such section. */
    public final String index;
    public final String sections;
    public final String authors;
    public final String performers;
    /** The site's code; never null. Must not touch {@code Provider} while it initialises. */
    public final SourceParser parser;
    /** The row this site mirrors, declared above it (the compiler rejects a later one, so no cycle); null for none. */
    private final Provider mirrorOf;

    Provider(String server, String title, String prefValue, String prefKey, int sourceId, String folder,
            String index, String sections, String authors, String performers, SourceParser parser) {
        this(server, title, prefValue, prefKey, sourceId, folder, index, sections, authors, performers, parser, null);
    }

    /** A mirror of {@code mirrorOf}: another site, with its own host and adapter, that carries the same catalog. */
    Provider(String server, String title, String prefValue, String prefKey, int sourceId, String folder,
            String index, String sections, String authors, String performers, SourceParser parser,
            Provider mirrorOf) {
        this.server = server;
        this.title = title;
        this.prefValue = prefValue;
        this.prefKey = prefKey;
        this.sourceId = sourceId;
        this.folder = folder;
        this.index = index;
        this.sections = sections;
        this.authors = authors;
        this.performers = performers;
        this.parser = java.util.Objects.requireNonNull(parser);
        this.mirrorOf = mirrorOf;
    }

    /**
     * The original of the site's catalog: the row it mirrors (followed to the row that mirrors none), or the site
     * itself. Sites with the same original are mirrors of each other.
     */
    public Provider original() {
        return mirrorOf == null ? this : mirrorOf.original();
    }

    /** True when {@code other} is another site with this site's catalog: the original or a mirror of it. */
    public boolean isMirrorOf(Provider other) {
        return other != null && other != this && other.original() == original();
    }

    /** The other sites with this site's catalog, in registry order; empty for a unique site. */
    public List<Provider> mirrors() {
        List<Provider> mirrors = new ArrayList<>();
        for (Provider p : values()) {
            if (isMirrorOf(p)) {
                mirrors.add(p);
            }
        }
        return mirrors;
    }

    /** False for a unique site: no other site in the app carries its catalog. */
    public boolean hasMirrors() {
        return !mirrors().isEmpty();
    }

    /** What the app tells the user about the site ({@link SourceInfo}); null when its facts are missing. */
    @Nullable
    public SourceInfo info(@NonNull Context context) {
        return SourceInfo.of(context, this);
    }

    /**
     * How long one request of the site may take in a search or lookup over many sites: its page timeout
     * ({@link SourceParser#timeoutMs}) and a margin, so the request's own timeout, not the operation's, ends it.
     */
    public long requestSeconds() {
        return java.util.concurrent.TimeUnit.MILLISECONDS.toSeconds(parser.timeoutMs()) + 5;
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

    /**
     * The provider whose requests go to {@code host} (lower case, no {@code www.}): its server or a subdomain of it, or
     * one of its {@link SourceParser#hosts}; null for any other host (a shared CDN). For network policy only (Referer,
     * proxy route): a book link is accepted by {@link #ofUrl} alone.
     */
    public static Provider ofNetworkHost(String host) {
        if (host == null || host.isEmpty()) {
            return null;
        }
        String current = Url.hostOf(Url.canonical("https://" + host + "/")); // a former host is the site's
        for (Provider p : values()) {
            if (isUnder(current, p.host())) {
                return p;
            }
            for (String own : p.parser.hosts()) {
                if (isUnder(current, own)) {
                    return p;
                }
            }
        }
        return null;
    }

    /** {@code host} is {@code domain} or one of its subdomains. */
    private static boolean isUnder(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    /** {@link #ofUrl}, but an unknown host is an error, not "no data". */
    public static Provider require(String url) {
        Provider provider = ofUrl(url);
        if (provider == null) {
            throw new IllegalArgumentException("unsupported host: " + Url.hostOf(url));
        }
        return provider;
    }

    /** The code of the site serving {@code url}; null for an unknown host. */
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

    /** The active providers ({@link Sources}), the one selected in the settings first: a multi-site search's order. */
    public static List<Provider> selectedFirst() {
        List<Provider> all = new ArrayList<>(Sources.active());
        Provider selected = current();
        if (selected != null && all.remove(selected)) {
            all.add(0, selected);
        }
        return all;
    }

    /** The source selected in the settings. */
    public static Provider current() {
        return ofSource(Consts.getSOURCE());
    }
}
