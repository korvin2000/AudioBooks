package com.fanok.audiobooks.search;

import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fanok.audiobooks.Consts;
import com.fanok.audiobooks.net.AppProxy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The user's source setup at one moment: the order and the active count ({@link Sources}), the catalog source, the
 * sites that go direct and the sites whose route the user set by hand ({@code AppProxy}). The source screens take one
 * before each change, so "Отменить" puts all of it back in one step, whatever the change moved along (a site pushed out of the active ones, the catalog source).
 */
public final class SourceSnapshot {

    @NonNull
    final List<Provider> order;
    final int active;
    /** The catalog source's {@link Provider#prefValue}; null when none is known. */
    @Nullable
    final String catalog;
    @NonNull
    final Set<String> direct;
    /** The sites whose route the user set by hand ({@link AppProxy#PREF_MANUAL}). */
    @NonNull
    final Set<String> manual;

    /** A setup whose direct sites are the hand-set ones, as before the region's defaults. */
    public SourceSnapshot(@NonNull List<Provider> order, int active, @Nullable String catalog,
            @NonNull Set<String> direct) {
        this(order, active, catalog, direct, direct);
    }

    public SourceSnapshot(@NonNull List<Provider> order, int active, @Nullable String catalog,
            @NonNull Set<String> direct, @NonNull Set<String> manual) {
        this.order = Collections.unmodifiableList(new ArrayList<>(order));
        this.active = active;
        this.catalog = catalog;
        this.direct = Collections.unmodifiableSet(new TreeSet<>(direct));
        this.manual = Collections.unmodifiableSet(new TreeSet<>(manual));
    }

    /** The live setup. */
    @NonNull
    public static SourceSnapshot take(@NonNull SharedPreferences prefs) {
        Provider current = Provider.current();
        return new SourceSnapshot(Sources.all(), Sources.activeCount(), current == null ? null : current.prefValue,
                AppProxy.readDirect(prefs), AppProxy.readManual(prefs));
    }

    /** Makes this the live setup again: the routes and the catalog source first, so the order finds it active. */
    public void restore(@NonNull SharedPreferences prefs) {
        synchronized (AppProxy.class) { // the region's defaults write the routes under the same lock
            SharedPreferences.Editor editor = prefs.edit().putStringSet(AppProxy.PREF_DIRECT, new TreeSet<>(direct))
                    .putStringSet(AppProxy.PREF_MANUAL, new TreeSet<>(manual));
            if (catalog != null) {
                editor.putString(Sources.SELECTED, catalog);
                Consts.setSOURCE(catalog);
            }
            editor.apply();
            Sources.save(prefs, order, active);
        }
    }

    /**
     * The undo of one change: makes this the live setup again only while the live setup still is {@code after} (the
     * setup the change left); returns false, restoring nothing, when something changed it since.
     */
    public boolean restoreIf(@NonNull SharedPreferences prefs, @NonNull SourceSnapshot after) {
        synchronized (AppProxy.class) {
            if (!after.equals(take(prefs))) {
                return false;
            }
            restore(prefs);
            return true;
        }
    }

    /** Every source in this setup's order, the active ones first. */
    @NonNull
    public List<Provider> order() {
        return order;
    }

    public boolean isActive(@NonNull Provider provider) {
        int index = order.indexOf(provider);
        return index >= 0 && index < active;
    }

    /** 1-based; 0 for a site missing from the order. */
    public int position(@NonNull Provider provider) {
        return order.indexOf(provider) + 1;
    }

    /** The catalog source; null when none is known. */
    @Nullable
    public Provider catalog() {
        for (Provider provider : Provider.values()) {
            if (provider.prefValue.equals(catalog)) {
                return provider;
            }
        }
        return null;
    }

    /**
     * Pure: the snapshot as text (for an intent): the stored order, the catalog source, the direct sites, the hand-set
     * sites, by lines.
     */
    @NonNull
    public String encode() {
        StringBuilder text = new StringBuilder(Sources.format(order, active)).append('\n');
        if (catalog != null) {
            text.append(catalog);
        }
        return text.append('\n').append(join(direct)).append('\n').append(join(manual)).toString();
    }

    @NonNull
    private static String join(@NonNull Set<String> sites) {
        StringBuilder text = new StringBuilder();
        for (String site : sites) {
            text.append(text.length() == 0 ? "" : ",").append(site);
        }
        return text.toString();
    }

    @NonNull
    private static Set<String> split(@NonNull String line) {
        Set<String> sites = new TreeSet<>();
        for (String site : line.split(",")) {
            if (!site.isEmpty()) {
                sites.add(site);
            }
        }
        return sites;
    }

    /**
     * Pure: {@link #encode} read back, normalized as the stored order is; null for text of another kind. Text of an
     * older version, without the hand-set line, takes the direct sites as hand-set.
     */
    @Nullable
    public static SourceSnapshot decode(@Nullable String text) {
        String[] lines = text == null ? new String[0] : text.split("\n", -1);
        if (lines.length != 3 && lines.length != 4) {
            return null;
        }
        Sources.State state = Sources.parse(lines[0]);
        Set<String> direct = split(lines[2]);
        return new SourceSnapshot(state.order, state.active, lines[1].isEmpty() ? null : lines[1], direct,
                lines.length == 4 ? split(lines[3]) : direct);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof SourceSnapshot)) {
            return false;
        }
        SourceSnapshot other = (SourceSnapshot) o;
        return active == other.active && order.equals(other.order) && direct.equals(other.direct)
                && manual.equals(other.manual)
                && (catalog == null ? other.catalog == null : catalog.equals(other.catalog));
    }

    @Override
    public int hashCode() {
        return ((order.hashCode() * 31 + active) * 31 + direct.hashCode()) * 31 + manual.hashCode();
    }
}
