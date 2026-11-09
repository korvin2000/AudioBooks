package com.fanok.audiobooks.fragment;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fanok.audiobooks.Consts;
import com.fanok.audiobooks.R;
import com.fanok.audiobooks.net.AppProxy;
import com.fanok.audiobooks.search.Provider;
import com.fanok.audiobooks.search.SourceSnapshot;
import com.fanok.audiobooks.search.Sources;
import com.google.android.material.snackbar.Snackbar;

/**
 * The changes the source list and the source card make to the user's sources, by the rules of {@link Sources} and
 * {@link AppProxy} (the sources dialog's storage), each one undoable: a {@link SourceSnapshot} before, the change, and a
 * snackbar that says what happened, with everything the change moved along, and "Отменить".
 */
final class SourceActions {

    /** How long the snackbar stays: long enough to read two lines and reach "Отменить". */
    private static final int SNACKBAR_MS = 6000;

    /** The words of {@link #describe}. */
    static final class Words {
        final String becameActive;
        final String becameInactive;
        final String catalog;

        Words(@NonNull String becameActive, @NonNull String becameInactive, @NonNull String catalog) {
            this.becameActive = becameActive;
            this.becameInactive = becameInactive;
            this.catalog = catalog;
        }

        @NonNull
        static Words of(@NonNull Context context) {
            return new Words(context.getString(R.string.sources_became_active),
                    context.getString(R.string.sources_became_inactive), context.getString(R.string.sources_done_catalog));
        }
    }

    private SourceActions() {
    }

    /** Switches {@code provider} on (the last active one) or off (the first inactive one). */
    static void setActive(@NonNull View anchor, @NonNull SharedPreferences prefs, @NonNull Provider provider,
            boolean on) {
        Context context = anchor.getContext();
        SourceSnapshot before = SourceSnapshot.take(prefs);
        Sources.setActive(prefs, provider, on);
        String head = context.getString(on ? R.string.sources_done_on : R.string.sources_done_off, provider.title);
        SourceSnapshot after = SourceSnapshot.take(prefs);
        show(anchor, prefs, before, after, describe(head, before, after, provider, Words.of(context)));
    }

    /** Moves {@code provider} to {@code position} (0-based); the active count stays. */
    static void move(@NonNull View anchor, @NonNull SharedPreferences prefs, @NonNull Provider provider,
            int position) {
        if (Sources.all().indexOf(provider) == position || position < 0 || position >= Sources.all().size()) {
            return;
        }
        Context context = anchor.getContext();
        SourceSnapshot before = SourceSnapshot.take(prefs);
        Sources.moveTo(prefs, provider, position);
        SourceSnapshot after = SourceSnapshot.take(prefs);
        String head = context.getString(R.string.sources_done_moved, provider.title, after.position(provider));
        if (before.isActive(provider) != after.isActive(provider)) {
            head += context.getString(after.isActive(provider) ? R.string.sources_done_now_active
                    : R.string.sources_done_now_inactive);
        }
        show(anchor, prefs, before, after, describe(head, before, after, provider, Words.of(context)));
    }

    /**
     * Sends {@code provider} direct or through the proxy. Through a proxy that cannot carry it now, the snackbar says
     * why; with the proxy off in the settings, its action is "Включить" ({@code enableProxy}) in place of the undo.
     */
    static void setDirect(@NonNull View anchor, @NonNull SharedPreferences prefs, @NonNull Provider provider,
            boolean direct, @NonNull Runnable enableProxy) {
        Context context = anchor.getContext();
        SourceSnapshot before = SourceSnapshot.take(prefs);
        AppProxy.setDirect(prefs, provider, direct);
        String text = context.getString(direct ? R.string.sources_done_direct : R.string.sources_done_proxy,
                provider.title);
        SourceTexts.ProxyState state = direct ? SourceTexts.ProxyState.WORKS : SourceTexts.proxyState(context);
        String reason = SourceTexts.proxyReason(context, state);
        if (state == SourceTexts.ProxyState.OFF) {
            Snackbar.make(anchor, text + SourceTexts.SEP + reason, SNACKBAR_MS)
                    .setAction(R.string.sources_proxy_needed_action, v -> enableProxy.run())
                    .show();
            return;
        }
        show(anchor, prefs, before, SourceSnapshot.take(prefs),
                reason == null ? text : text + SourceTexts.SEP + reason);
    }

    /**
     * Makes {@code provider} the catalog source, switching it on first when it is inactive, as the catalog's source
     * menu stores it ({@code sorce_books} and {@link Consts#setSOURCE}). Returns what that changed, for the snackbar;
     * null when it already was the catalog source.
     */
    @Nullable
    static String makeCatalog(@NonNull Context context, @NonNull SharedPreferences prefs,
            @NonNull Provider provider, @NonNull SourceSnapshot before) {
        if (Sources.isActive(provider) && provider == Provider.current()) {
            return null;
        }
        if (!Sources.isActive(provider)) {
            Sources.setActive(prefs, provider, true);
        }
        prefs.edit().putString(Sources.SELECTED, provider.prefValue).apply();
        Consts.setSOURCE(provider.prefValue);
        String head = context.getString(R.string.sources_done_catalog, provider.title);
        if (!before.isActive(provider)) {
            head += SourceTexts.SEP + context.getString(R.string.sources_done_on, provider.title);
        }
        return describe(head, before, SourceSnapshot.take(prefs), provider, Words.of(context));
    }

    /**
     * Pure: {@code head}, then what else changed from {@code before} to {@code after}: each site but {@code subject}
     * that crossed the line of the active ones, in the new order, and a new catalog source other than the subject.
     */
    @NonNull
    static String describe(@NonNull String head, @NonNull SourceSnapshot before, @NonNull SourceSnapshot after,
            @NonNull Provider subject, @NonNull Words words) {
        StringBuilder text = new StringBuilder(head);
        for (Provider provider : after.order()) {
            if (provider != subject && before.isActive(provider) != after.isActive(provider)) {
                text.append(SourceTexts.SEP).append(String.format(after.isActive(provider) ? words.becameActive
                        : words.becameInactive, provider.title));
            }
        }
        Provider catalog = after.catalog();
        if (catalog != null && catalog != before.catalog() && catalog != subject) {
            text.append(SourceTexts.SEP).append(String.format(words.catalog, catalog.title));
        }
        return text.toString();
    }

    /**
     * The snackbar: {@code text} and "Отменить", which makes {@code before} the live setup again while the live setup
     * still is {@code after}; a later change (another screen, another action) is kept, and said so.
     */
    static void show(@NonNull View anchor, @NonNull SharedPreferences prefs, @NonNull SourceSnapshot before,
            @NonNull SourceSnapshot after, @NonNull CharSequence text) {
        Snackbar.make(anchor, text, SNACKBAR_MS)
                .setAction(R.string.sources_undo, v -> {
                    if (!before.restoreIf(prefs, after)) {
                        Toast.makeText(v.getContext(), R.string.sources_undo_stale, Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }
}
