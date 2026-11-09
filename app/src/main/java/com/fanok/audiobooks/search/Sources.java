package com.fanok.audiobooks.search;

import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fanok.audiobooks.Consts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The user's sources ("Настройки → Источники"): every {@link Provider} in the user's order, the first
 * {@link #activeCount} of them active. Only active sources exist for the source menu, the settings, the searches and
 * the availability check; an inactive one is still known to links, the library and the network routes
 * ({@code Provider.ofUrl} and friends stay on every provider). Stored as one preference, {@link #PREF}:
 * {@code "abook,izibuc|otrub"}, the active ones before {@code |}. A site added later is not in it and starts inactive.
 */
public final class Sources {

    public static final String PREF = "pref_sources";
    /** The most sources that can be active at once ({@link #maxActive()}), chosen in «Общие», stored as text. */
    public static final String PREF_MAX = "pref_sources_max";
    /** The limit with nothing chosen, and how many are active with nothing stored. */
    public static final int DEFAULT_MAX_ACTIVE = 13;
    /** The range of the limit to choose from. */
    public static final int MIN_LIMIT = 3;
    public static final int MAX_LIMIT = 20;
    /** The catalog source: the {@link Provider#prefValue} of the source the drawer sections open. */
    public static final String SELECTED = "sorce_books";
    /**
     * When the last auto-ranking ({@link #ranked}) was stored (epoch millis); 0 once its one-time offer was answered
     * without one; absent before the offer.
     */
    public static final String PREF_RANKED_AT = "pref_sources_ranked_at";
    /** A site through the proxy is less stable: it must be this many times faster to rank above a direct one. */
    static final int PROXY_PENALTY = 2;
    /**
     * 4Read, a small catalog in Ukrainian (4 850 books), answers fastest (light pages behind Cloudflare) and so
     * topped the ranking alone: it must be this many times faster to rank above another site.
     */
    static final int SMALL_CATALOG_PENALTY = 3;
    /** Times within this percentage of a group's fastest count as equal: the site with more books ranks higher. */
    static final int SAME_SPEED_PERCENT = 135;

    /** One consistent snapshot: the order and the active count change together. */
    static final class State {
        final List<Provider> order;
        final int active;

        State(List<Provider> order, int active) {
            this.order = Collections.unmodifiableList(order);
            this.active = active;
        }
    }

    private static volatile int maxActive = DEFAULT_MAX_ACTIVE;
    private static volatile State state = parse(null);

    private Sources() {
    }

    /** The most sources that can be active at once: {@link #PREF_MAX}, read by {@link #load}. */
    public static int maxActive() {
        return maxActive;
    }

    /** The stored limit clamped to {@link #MIN_LIMIT}..{@link #MAX_LIMIT}; missing or unreadable: the default. */
    static int readMax(@NonNull SharedPreferences prefs) {
        try {
            return Math.max(MIN_LIMIT, Math.min(MAX_LIMIT, Integer.parseInt(prefs.getString(PREF_MAX, null))));
        } catch (ClassCastException | NumberFormatException e) {
            return DEFAULT_MAX_ACTIVE;
        }
    }

    /** The active sources, in the user's order. */
    @NonNull
    public static List<Provider> active() {
        State s = state;
        return s.order.subList(0, s.active);
    }

    /** Every source in the user's order, the active ones first. */
    @NonNull
    public static List<Provider> all() {
        return state.order;
    }

    public static int activeCount() {
        return state.active;
    }

    public static boolean isActive(@Nullable Provider provider) {
        return provider != null && active().contains(provider);
    }

    /**
     * Reads the limit and the stored list; call at the start, before anything asks for the sources, and again when the
     * limit changes. A lower limit cuts the active part without rewriting the stored list.
     */
    public static synchronized void load(@NonNull SharedPreferences prefs) {
        String stored;
        try {
            stored = prefs.getString(PREF, null);
        } catch (ClassCastException e) {
            stored = null;
        }
        maxActive = readMax(prefs);
        state = parse(stored);
        keepSelectedActive(prefs);
    }

    /**
     * Stores {@code order} with its first {@code active} sources active (normalized as {@link #parse} does) and makes
     * the selected source an active one. Returns true when that moved the selection.
     */
    public static synchronized boolean save(@NonNull SharedPreferences prefs, @NonNull List<Provider> order,
            int active) {
        State s = parse(format(order, active));
        state = s;
        prefs.edit().putString(PREF, format(s.order, s.active)).apply();
        return keepSelectedActive(prefs);
    }

    /** Moves {@code provider} to {@code position} (0-based) of the user's order ({@link #moved}) and stores it. */
    public static synchronized boolean moveTo(@NonNull SharedPreferences prefs, @NonNull Provider provider,
            int position) {
        State s = moved(state, provider, position);
        return save(prefs, s.order, s.active);
    }

    /** Switches {@code provider} on or off ({@link #switched}) and stores it. */
    public static synchronized boolean setActive(@NonNull SharedPreferences prefs, @NonNull Provider provider,
            boolean on) {
        State s = switched(state, provider, on);
        return save(prefs, s.order, s.active);
    }

    /**
     * Pure: {@code provider} at {@code position} (0-based, clamped), the sources between shifted by one. The active
     * count stays: a source moved into the active part pushes the last active one out, one moved out lets the first
     * inactive one in.
     */
    @NonNull
    static State moved(@NonNull State s, @NonNull Provider provider, int position) {
        List<Provider> order = new ArrayList<>(s.order);
        order.remove(provider);
        order.add(Math.max(0, Math.min(order.size(), position)), provider);
        return new State(order, s.active);
    }

    /** Pure, for a preview: {@code order} after {@link #moveTo} of {@code provider} to {@code position} (0-based). */
    @NonNull
    public static List<Provider> moved(@NonNull List<Provider> order, @NonNull Provider provider, int position) {
        return moved(new State(new ArrayList<>(order), 1), provider, position).order;
    }

    /**
     * Pure (given the limit): {@code provider} switched on, as the last active source (at {@link #maxActive()} in place
     * of the last one, which becomes the first inactive), or off, as the first inactive one. The only active source
     * stays on.
     */
    @NonNull
    static State switched(@NonNull State s, @NonNull Provider provider, boolean on) {
        int index = s.order.indexOf(provider);
        if (index < 0 || (index < s.active) == on || (!on && s.active == 1)) {
            return s;
        }
        if (!on) {
            return new State(moved(s, provider, s.active - 1).order, s.active - 1);
        }
        return s.active < Math.min(maxActive, s.order.size())
                ? new State(moved(s, provider, s.active).order, s.active + 1)
                : moved(s, provider, s.active - 1);
    }

    /** The selected source must be active: otherwise the first active one is selected. True when it moved. */
    private static boolean keepSelectedActive(@NonNull SharedPreferences prefs) {
        String selected;
        try {
            selected = prefs.getString(SELECTED, null);
        } catch (ClassCastException e) {
            selected = null;
        }
        for (Provider provider : active()) {
            if (provider.prefValue.equals(selected)) {
                return false;
            }
        }
        if (selected == null) {
            return false; // nothing chosen yet: the settings' default applies, and it is checked when chosen
        }
        Provider first = active().get(0);
        prefs.edit().putString(SELECTED, first.prefValue).apply();
        Consts.setSOURCE(first.prefValue);
        return true;
    }

    /**
     * Pure (given the limit): the stored text read back. Unknown and repeated ids are dropped; a provider missing from
     * it is appended inactive; at least one and at most {@link #maxActive()} are active (the overflow becomes the first
     * inactive). No text: the declaration order, the first {@link #DEFAULT_MAX_ACTIVE} active, so a higher limit
     * switches nothing on by itself.
     */
    @NonNull
    static State parse(@Nullable String text) {
        Set<Provider> order = new LinkedHashSet<>();
        int active;
        if (text == null) {
            order.addAll(Arrays.asList(Provider.values()));
            active = DEFAULT_MAX_ACTIVE;
        } else {
            int bar = text.indexOf('|');
            addAll(order, bar < 0 ? text : text.substring(0, bar));
            active = order.size();
            if (bar >= 0) {
                addAll(order, text.substring(bar + 1));
            }
            order.addAll(Arrays.asList(Provider.values()));
        }
        active = Math.max(1, Math.min(Math.min(maxActive, order.size()), active));
        return new State(new ArrayList<>(order), active);
    }

    private static void addAll(@NonNull Set<Provider> order, @NonNull String ids) {
        for (String id : ids.split(",")) {
            for (Provider provider : Provider.values()) {
                if (provider.prefValue.equals(id.trim())) {
                    order.add(provider);
                }
            }
        }
    }

    /** Pure: the stored text of {@code order} with its first {@code active} sources active. */
    @NonNull
    static String format(@NonNull List<Provider> order, int active) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < order.size(); i++) {
            if (i == active) {
                text.append('|');
            } else if (i > 0) {
                text.append(',');
            }
            text.append(order.get(i).prefValue);
        }
        if (active >= order.size()) {
            text.append('|');
        }
        return text.toString();
    }

    /** Pure: the site of {@code result} works: its catalog listed books ({@link ProviderPage.Status#OK}). */
    public static boolean works(@Nullable SourceProbe.Result result) {
        return result != null && result.status == ProviderPage.Status.OK;
    }

    /** Pure: how many sites of {@code order} work by {@code results} ({@link #works}). */
    public static int working(@NonNull List<Provider> order, @NonNull Map<Provider, SourceProbe.Result> results) {
        int working = 0;
        for (Provider provider : order) {
            if (works(results.get(provider))) {
                working++;
            }
        }
        return working;
    }

    /**
     * Pure: the active count after {@link #ranked}: {@code max(1, min(active, working))}. The sites that failed become
     * inactive; the user's chosen count is never raised.
     */
    public static int rankedActive(int active, int working) {
        return Math.max(1, Math.min(active, working));
    }

    /**
     * Pure: {@code order} (the user's) ranked by a check of every site ({@code results}) and the sites' audiobook counts
     * ({@code books}, {@link SourceInfo#books}; unknown is 0):
     * <ol>
     * <li>a site works when its result is {@link ProviderPage.Status#OK}; empty, failed, timed out, blocked, rate
     * limited and unchecked sites do not;</li>
     * <li>its time is {@code max(millis, 1)}, times {@link #PROXY_PENALTY} through the proxy, times
     * {@link #SMALL_CATALOG_PENALTY} for 4Read;</li>
     * <li>the working sites by time, ties in the user's order, in groups: a group starts at its fastest site and takes
     * every next one whose time is at most {@link #SAME_SPEED_PERCENT}% of that; within a group more books first, ties
     * by time;</li>
     * <li>a site whose catalog ({@link Provider#original}) a site above it already has, a mirror, moves after every
     * unique one, the mirrors in their ranked order;</li>
     * <li>the sites that do not work last, in the user's order.</li>
     * </ol>
     */
    @NonNull
    public static List<Provider> ranked(@NonNull List<Provider> order,
            @NonNull Map<Provider, SourceProbe.Result> results, @NonNull Map<Provider, Integer> books) {
        Map<Provider, Long> time = new EnumMap<>(Provider.class);
        List<Provider> working = new ArrayList<>();
        List<Provider> failed = new ArrayList<>();
        for (Provider provider : order) {
            SourceProbe.Result result = results.get(provider);
            if (works(result)) {
                time.put(provider, Math.max(result.millis, 1)
                        * (Boolean.TRUE.equals(result.proxied) ? PROXY_PENALTY : 1)
                        * (provider == Provider.FOUR_READ ? SMALL_CATALOG_PENALTY : 1));
                working.add(provider);
            } else {
                failed.add(provider);
            }
        }
        Collections.sort(working, (a, b) -> Long.compare(time.get(a), time.get(b))); // stable: the user's order
        List<Provider> grouped = new ArrayList<>();
        for (int start = 0, end; start < working.size(); start = end) {
            long fastest = time.get(working.get(start));
            end = start + 1;
            while (end < working.size() && time.get(working.get(end)) * 100 <= fastest * SAME_SPEED_PERCENT) {
                end++;
            }
            List<Provider> group = new ArrayList<>(working.subList(start, end));
            Collections.sort(group, (a, b) -> {
                int byBooks = Integer.compare(books(books, b), books(books, a));
                return byBooks != 0 ? byBooks : Long.compare(time.get(a), time.get(b));
            });
            grouped.addAll(group);
        }
        List<Provider> ranked = new ArrayList<>();
        List<Provider> mirrors = new ArrayList<>();
        Set<Provider> catalogs = EnumSet.noneOf(Provider.class);
        for (Provider provider : grouped) {
            (catalogs.add(provider.original()) ? ranked : mirrors).add(provider);
        }
        ranked.addAll(mirrors);
        ranked.addAll(failed);
        return ranked;
    }

    private static int books(@NonNull Map<Provider, Integer> books, @NonNull Provider provider) {
        Integer count = books.get(provider);
        return count == null ? 0 : count;
    }
}
