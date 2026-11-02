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
 * The user's source setup at one moment: the order and the active count ({@link Sources}), the catalog source and the
 * sites that go direct ({@code AppProxy}). The source screens take one before each change, so "Отменить" puts all of
 * it back in one step, whatever the change moved along (a site pushed out of the active ones, the catalog source).
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

    public SourceSnapshot(@NonNull List<Provider> order, int active, @Nullable String catalog,
            @NonNull Set<String> direct) {
        this.order = Collections.unmodifiableList(new ArrayList<>(order));
        this.active = active;
        this.catalog = catalog;
        this.direct = Collections.unmodifiableSet(new TreeSet<>(direct));
    }

    /** The live setup. */
    @NonNull
    public static SourceSnapshot take(@NonNull SharedPreferences prefs) {
        Provider current = Provider.current();
        return new SourceSnapshot(Sources.all(), Sources.activeCount(), current == null ? null : current.prefValue,
                AppProxy.readDirect(prefs));
    }

    /** Makes this the live setup again: the routes and the catalog source first, so the order finds it active. */
    public void restore(@NonNull SharedPreferences prefs) {
        SharedPreferences.Editor editor = prefs.edit().putStringSet(AppProxy.PREF_DIRECT, new TreeSet<>(direct));
        if (catalog != null) {
            editor.putString(Sources.SELECTED, catalog);
            Consts.setSOURCE(catalog);
        }
        editor.apply();
        Sources.save(prefs, order, active);
    }

    /**
     * The undo of one change: makes this the live setup again only while the live setup still is {@code after} (the
     * setup the change left); returns false, restoring nothing, when something changed it since.
     */
    public boolean restoreIf(@NonNull SharedPreferences prefs, @NonNull SourceSnapshot after) {
        if (!after.equals(take(prefs))) {
            return false;
        }
        restore(prefs);
        return true;
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

    /** Pure: the snapshot as text (for an intent): the stored order, the catalog source, the direct sites, by lines. */
    @NonNull
    public String encode() {
        StringBuilder text = new StringBuilder(Sources.format(order, active)).append('\n');
        if (catalog != null) {
            text.append(catalog);
        }
        text.append('\n');
        String separator = "";
        for (String site : direct) {
            text.append(separator).append(site);
            separator = ",";
        }
        return text.toString();
    }

    /** Pure: {@link #encode} read back, normalized as the stored order is; null for text of another kind. */
    @Nullable
    public static SourceSnapshot decode(@Nullable String text) {
        String[] lines = text == null ? new String[0] : text.split("\n", -1);
        if (lines.length != 3) {
            return null;
        }
        Sources.State state = Sources.parse(lines[0]);
        Set<String> direct = new TreeSet<>();
        for (String site : lines[2].split(",")) {
            if (!site.isEmpty()) {
                direct.add(site);
            }
        }
        return new SourceSnapshot(state.order, state.active, lines[1].isEmpty() ? null : lines[1], direct);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof SourceSnapshot)) {
            return false;
        }
        SourceSnapshot other = (SourceSnapshot) o;
        return active == other.active && order.equals(other.order) && direct.equals(other.direct)
                && (catalog == null ? other.catalog == null : catalog.equals(other.catalog));
    }

    @Override
    public int hashCode() {
        return (order.hashCode() * 31 + active) * 31 + direct.hashCode();
    }
}
