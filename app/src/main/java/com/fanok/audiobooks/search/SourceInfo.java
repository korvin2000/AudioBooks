package com.fanok.audiobooks.search;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fanok.audiobooks.region.Region;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the app tells the user about a site, written by hand: {@code assets/sources/<id>/info.json} with the site's
 * pictures beside it, {@code <id>} being the {@link Provider}'s name in lower case ({@link #dir}). Only editorial,
 * dated facts live here; the title, host, adapter and the sites with the same catalog stay in {@link Provider}, and
 * the live state (active, order, route, availability) in {@link Sources}, {@code AppProxy} and {@link SourceHealth}.
 * An unknown fact is null, never a guess. {@code SourceInfoTest} keeps every row and folder in step.
 */
public final class SourceInfo {

    public static final String ROOT = "sources";
    public static final String INFO = "info.json";
    /** A screenshot of the site's mobile page, 2:3 like a book cover. */
    public static final String COVER = "cover.webp";
    /** The site's icon, square. */
    public static final String ICON = "icon.webp";

    /** Preview fragments instead of the full book: on most books, on some, or never. */
    public enum Fragments { YES, SOME, NO }

    /** A few words that set the site apart: «Новинки раньше всех». */
    @Nullable
    public final String tagline;
    /** Two or three sentences on what sets the site apart from the others. */
    @NonNull
    public final String about;
    /** How often new books appear: «≈30 книг в день». */
    @Nullable
    public final String updates;
    /** About how many audiobooks the site has, as counted in {@link #booksAt} ("2026-11"). */
    @Nullable
    public final Integer books;
    @Nullable
    public final String booksAt;
    /** The ISO code of the country of the site's visible address; behind a {@link #cdn} the servers may be elsewhere. */
    @Nullable
    public final String country;
    /** The network that answers for the site (Cloudflare, DDoS-Guard, ...); null when the site answers itself. */
    @Nullable
    public final String cdn;
    /** Since when the site exists: "2014" or "2014-03". */
    @Nullable
    public final String since;
    /**
     * The regions where the site is blocked or gives fragments only, so it needs the proxy there: "RU", "UA", "WEST",
     * "OTHER" ({@link Region}), as measured from home connections there; null when unknown.
     */
    @Nullable
    public final List<String> blocked;
    /** Other addresses of the same site. */
    @NonNull
    public final List<String> mirrors;
    @Nullable
    public final Fragments fragments;
    /** True when the site removes books at right holders' request. */
    @Nullable
    public final Boolean takedowns;
    /**
     * Sites with the same catalog or the same new releases, by the editors' finding; wider than
     * {@link Provider#mirrors()}, which only names the catalogs the app treats as one.
     */
    @NonNull
    public final List<Provider> related;
    /** The app version that added the site ("8.0"); null for the first ones. */
    @Nullable
    public final String added;
    /** The site's own colour (ARGB), from its icon: a tint that makes its card recognizable. */
    @Nullable
    public final Integer accent;
    @NonNull
    public final List<String> pluses;
    @NonNull
    public final List<String> minuses;

    private SourceInfo(@NonNull JsonObject o) {
        tagline = text(o, "tagline");
        about = require(text(o, "about"), "about");
        updates = text(o, "updates");
        books = o.has("books") && !o.get("books").isJsonNull() ? o.get("books").getAsInt() : null;
        booksAt = text(o, "booksAt");
        country = text(o, "country");
        cdn = text(o, "cdn");
        since = text(o, "since");
        blocked = o.has("blocked") && !o.get("blocked").isJsonNull() ? texts(o, "blocked") : null;
        mirrors = texts(o, "mirrors");
        String f = text(o, "fragments");
        fragments = f == null ? null : Fragments.valueOf(f.toUpperCase(Locale.ROOT));
        takedowns = o.has("takedowns") && !o.get("takedowns").isJsonNull() ? o.get("takedowns").getAsBoolean() : null;
        List<Provider> sites = new ArrayList<>();
        for (String id : texts(o, "related")) {
            sites.add(Provider.valueOf(id.toUpperCase(Locale.ROOT)));
        }
        related = Collections.unmodifiableList(sites);
        added = text(o, "added");
        String color = text(o, "accent");
        accent = color == null ? null : 0xFF000000 | Integer.parseInt(color.substring(1), 16);
        pluses = texts(o, "pluses");
        minuses = texts(o, "minuses");
    }

    /**
     * True when the site is blocked or gives fragments only in {@code region} ({@link #blocked} names it: RUSSIA "RU",
     * UKRAINE "UA", WEST "WEST", OTHER "OTHER"), so it wants the proxy there; false when it is known to work; null when
     * nobody knows (no facts, or no region yet).
     */
    @Nullable
    public Boolean blockedIn(@Nullable Region region) {
        return blocked == null || region == null ? null : blocked.contains(key(region));
    }

    /** The key of {@code region} in {@link #blocked}. */
    @NonNull
    static String key(@NonNull Region region) {
        switch (region) {
            case RUSSIA:
                return "RU";
            case UKRAINE:
                return "UA";
            case WEST:
                return "WEST";
            default:
                return "OTHER";
        }
    }

    /** The folder of {@code provider}'s files in the assets. */
    @NonNull
    public static String dir(@NonNull Provider provider) {
        return ROOT + "/" + provider.name().toLowerCase(Locale.ROOT);
    }

    /** A file of {@code provider} for an image loader: {@code file:///android_asset/sources/<id>/<name>}. */
    @NonNull
    public static String uri(@NonNull Provider provider, @NonNull String name) {
        return "file:///android_asset/" + dir(provider) + "/" + name;
    }

    private static final Map<Provider, SourceInfo> CACHE = new EnumMap<>(Provider.class);

    /** The facts of {@code provider}, read once; null when its file is missing or broken (logged). */
    @Nullable
    public static synchronized SourceInfo of(@NonNull Context context, @NonNull Provider provider) {
        SourceInfo info = CACHE.get(provider);
        if (info == null) {
            try (InputStream in = context.getApplicationContext().getAssets().open(dir(provider) + "/" + INFO)) {
                info = parse(new InputStreamReader(in, StandardCharsets.UTF_8));
                CACHE.put(provider, info);
            } catch (IOException | RuntimeException e) {
                Log.w("SourceInfo", "No facts of " + provider, e);
            }
        }
        return info;
    }

    /** Pure: an {@link #INFO} file read; a malformed one is an exception. */
    @NonNull
    static SourceInfo parse(@NonNull Reader json) {
        return new SourceInfo(JsonParser.parseReader(json).getAsJsonObject());
    }

    @Nullable
    private static String text(@NonNull JsonObject o, @NonNull String key) {
        JsonElement e = o.get(key);
        String s = e == null || e.isJsonNull() ? null : e.getAsString().trim();
        return s == null || s.isEmpty() ? null : s;
    }

    @NonNull
    private static List<String> texts(@NonNull JsonObject o, @NonNull String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            return Collections.emptyList();
        }
        JsonArray array = e.getAsJsonArray();
        List<String> list = new ArrayList<>(array.size());
        for (JsonElement item : array) {
            list.add(item.getAsString().trim());
        }
        return Collections.unmodifiableList(list);
    }

    @NonNull
    private static String require(@Nullable String value, @NonNull String key) {
        if (value == null) {
            throw new IllegalArgumentException("no " + key);
        }
        return value;
    }
}
