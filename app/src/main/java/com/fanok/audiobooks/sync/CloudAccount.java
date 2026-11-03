package com.fanok.audiobooks.sync;

import androidx.annotation.Nullable;

/** A signed-in account of a cloud: the name the status shows, and the secret its API needs (null when none). */
final class CloudAccount {

    @Nullable
    final String name;
    @Nullable
    final String token;

    CloudAccount(@Nullable String name, @Nullable String token) {
        this.name = name;
        this.token = token;
    }
}
