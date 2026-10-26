package com.fanok.audiobooks.policy;

import androidx.annotation.Nullable;
import com.fanok.audiobooks.search.Provider;

/**
 * Covers that never load and show {@code R.drawable.default_cover} instead: audioboo's Cloudflare answers every GET,
 * its cover images included, with a challenge.
 */
public final class DefaultCover {

    private DefaultCover() {
    }

    /** Whether the default cover shows instead of {@code photo}, without trying to load it. */
    public static boolean replaces(@Nullable String photo) {
        return Provider.ofUrl(photo) == Provider.AUDIOBOO;
    }
}
