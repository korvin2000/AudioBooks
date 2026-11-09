package com.fanok.audiobooks.search;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;
import com.fanok.audiobooks.pojo.GenrePOJO;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The user's genres: how often each was opened, counted by the app's genre ({@link Genre#of}), so a pick of "Ужасы,
 * мистика" on one site counts for "Мистика и ужасы" on every other (a name no genre matches counts by its own words),
 * and the favourite genres starred by the user (Избранное → Жанры). The "favorite genres" order
 * ({@link GenreSort#FAVORITE}) reads both through {@link #all}: the starred ones first, then the most opened.
 */
public final class GenrePicks {

    static final String PREFERENCES = "genre_picks";
    /** Default preference (synced): the favourite genres' {@link Genre#name()}s, comma-joined, oldest first. */
    public static final String PREF_FAVORITES = "favorite_genres";
    /** Added to a favourite's count in {@link #all}: a starred genre goes before any one merely opened. */
    static final int FAVORITE_BOOST = 1 << 20;

    private GenrePicks() {
    }

    /**
     * Counts one pick of the genre named {@code name} on a site, for {@link #key} and for every genre it names (see
     * {@link #keys}); a missing name is not counted.
     */
    public static void record(@NonNull Context context, @Nullable String name) {
        Set<String> keys = keys(name);
        if (!keys.isEmpty()) {
            SharedPreferences prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            for (String key : keys) {
                editor.putInt(key, prefs.getInt(key, 0) + 1);
            }
            editor.apply();
        }
    }

    /**
     * The keys one pick of {@code name} counts under: {@link #key}, then each genre of {@link Genre#allOf} once, so a
     * favourite Боевики counts the opens of "Детективы, триллеры, боевики"; empty for a name without words.
     */
    @NonNull
    static Set<String> keys(@Nullable String name) {
        Set<String> keys = new LinkedHashSet<>();
        String key = key(name);
        if (key != null) {
            keys.add(key);
            for (Genre genre : Genre.allOf(name)) {
                keys.add(genre.name());
            }
        }
        return keys;
    }

    /** Every count with the favourites raised above the rest, for {@link GenreSort#sorted}; a new map. */
    @NonNull
    public static Map<String, ?> all(@NonNull Context context) {
        return merge(counts(context), favorites(context));
    }

    /** The opens counted, as they are. */
    @NonNull
    public static Map<String, ?> counts(@NonNull Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getAll();
    }

    /** How often {@code genre} was opened, by {@link #counts}. */
    public static int opens(@NonNull Map<String, ?> counts, @NonNull Genre genre) {
        Object count = counts.get(genre.name());
        return count instanceof Integer ? (Integer) count : 0;
    }

    /** {@code counts} with each favourite's count raised by {@link #FAVORITE_BOOST}, as a new map. */
    @NonNull
    static Map<String, Object> merge(@NonNull Map<String, ?> counts, @NonNull List<Genre> favorites) {
        Map<String, Object> merged = new HashMap<>(counts);
        for (Genre genre : favorites) {
            merged.put(genre.name(), opens(counts, genre) + FAVORITE_BOOST);
        }
        return merged;
    }

    /** The {@code picks} count of a site's genre: the highest of the genres it names ({@link Genre#allOf}). */
    static int count(@NonNull Map<String, ?> picks, @Nullable String name) {
        Object own = picks.get(key(name));
        int count = own instanceof Integer ? (Integer) own : 0;
        for (Genre genre : Genre.allOf(name)) { // "Детективы, триллеры, боевики" ranks as a favourite Боевики too
            count = Math.max(count, opens(picks, genre));
        }
        return count;
    }

    /** The genre's {@link Genre#name()}, else "?" and the name's folded words; null for a name without words. */
    @Nullable
    static String key(@Nullable String name) {
        Genre genre = Genre.of(name);
        if (genre != null) {
            return genre.name();
        }
        String words = name == null ? "" : GenreMatcher.fold(name).replaceAll("[ ,]+", " ").trim();
        return words.isEmpty() ? null : "?" + words;
    }

    // ---------------------------------------------------------------- favourites

    /** The favourite genres, oldest first. */
    @NonNull
    public static List<Genre> favorites(@NonNull Context context) {
        return parse(PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_FAVORITES, ""));
    }

    /** Whether the star of a site's genre {@code name} is on: a favourite is among the genres it names. */
    public static boolean starred(@NonNull List<Genre> favorites, @Nullable String name) {
        return !Collections.disjoint(favorites, Genre.allOf(name));
    }

    /**
     * The star of a site's genre {@code name}: when on, it takes out every genre the name names; when off, it stars
     * {@code narrowedTo} (the genre the list is narrowed to) if the name names it, else {@link Genre#of}.
     *
     * @return whether a genre was starred
     */
    public static boolean toggleStar(@NonNull Context context, @Nullable String name, @Nullable Genre narrowedTo) {
        List<Genre> before = favorites(context);
        List<Genre> after = toggled(before, name, narrowedTo);
        if (!after.equals(before)) {
            save(context, after);
        }
        return after.size() > before.size();
    }

    /** {@code favorites} after {@link #toggleStar}, as a new list. */
    @NonNull
    static List<Genre> toggled(@NonNull List<Genre> favorites, @Nullable String name, @Nullable Genre narrowedTo) {
        List<Genre> toggled = new ArrayList<>(favorites);
        Set<Genre> named = Genre.allOf(name);
        if (!toggled.removeAll(named)) {
            Genre genre = named.contains(narrowedTo) ? narrowedTo : Genre.of(name);
            if (genre != null) {
                toggled.add(genre);
            }
        }
        return toggled;
    }

    /** Adds {@code genre} at the end of the favourites or takes it out. */
    public static void setFavorite(@NonNull Context context, @NonNull Genre genre, boolean on) {
        List<Genre> favorites = favorites(context);
        if (favorites.contains(genre) != on) {
            if (on) {
                favorites.add(genre);
            } else {
                favorites.remove(genre);
            }
            save(context, favorites);
        }
    }

    /** Puts {@code genre} back at {@code index} of the favourites (an undone removal). */
    public static void addFavorite(@NonNull Context context, @NonNull Genre genre, int index) {
        List<Genre> favorites = favorites(context);
        favorites.remove(genre);
        favorites.add(Math.max(0, Math.min(index, favorites.size())), genre);
        save(context, favorites);
    }

    public static void clearFavorites(@NonNull Context context) {
        save(context, new ArrayList<>());
    }

    private static void save(@NonNull Context context, @NonNull List<Genre> favorites) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(PREF_FAVORITES, join(favorites)).apply();
    }

    /** The genres named in a comma-joined {@code value}, each once; an unknown or blank name is dropped. */
    @NonNull
    public static List<Genre> parse(@Nullable String value) {
        List<Genre> genres = new ArrayList<>();
        for (String name : value == null ? new String[0] : value.split(",")) {
            Genre genre = genre(name.trim());
            if (genre != null && !genres.contains(genre)) {
                genres.add(genre);
            }
        }
        return genres;
    }

    @NonNull
    static String join(@NonNull List<Genre> genres) {
        StringBuilder out = new StringBuilder();
        for (Genre genre : genres) {
            out.append(out.length() > 0 ? "," : "").append(genre.name());
        }
        return out.toString();
    }

    /** The genre of a {@link Genre#name()}, null for one this version does not know. */
    @Nullable
    public static Genre genre(@Nullable String name) {
        for (Genre genre : Genre.values()) {
            if (genre.name().equals(name)) {
                return genre;
            }
        }
        return null;
    }

    /** The site's genres that name {@code genre} ({@link Genre#allOf}), in their order; all of them for null. */
    @NonNull
    public static List<GenrePOJO> narrowed(@NonNull List<GenrePOJO> genres, @Nullable Genre genre) {
        if (genre == null) {
            return genres;
        }
        List<GenrePOJO> matching = new ArrayList<>();
        for (GenrePOJO item : genres) {
            if (Genre.allOf(item.getName()).contains(genre)) {
                matching.add(item);
            }
        }
        return matching;
    }
}
