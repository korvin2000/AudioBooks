package com.fanok.audiobooks.search;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * The last availability check of every site ({@link SourceProbe}), kept in the preferences: run by hand from the
 * settings, or by itself at most once a day, some time after the app starts and only on an unmetered network, so it
 * never competes with the user's first pages or costs mobile data.
 */
public final class SourceHealth {

    static final String PREF_RESULTS = "source_health";
    static final String PREF_CHECKED_AT = "source_health_at";
    static final long DAY_MS = TimeUnit.DAYS.toMillis(1);
    /** The automatic check waits this long after the start: the user's own pages come first. */
    static final long START_DELAY_SECONDS = 30;
    /** Sites checked at once by the automatic check. */
    static final int BACKGROUND_PARALLEL = 2;

    private SourceHealth() {
    }

    /** Checks every site through its current route and keeps the results. */
    @NonNull
    public static Single<List<SourceProbe.Result>> check(@NonNull SharedPreferences prefs, int parallel) {
        return SourceProbe.probeAll(Arrays.asList(Provider.values()), parallel, Schedulers.io())
                .doOnSuccess(results -> prefs.edit()
                        .putStringSet(PREF_RESULTS, encode(results))
                        .putLong(PREF_CHECKED_AT, System.currentTimeMillis())
                        .apply());
    }

    /** Starts the automatic check when the last one is a day old and the network is unmetered; never fails. */
    @SuppressLint("CheckResult") // process-wide and short: it ends by itself, nothing to dispose
    public static void checkIfDue(@NonNull Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (!due(prefs.getLong(PREF_CHECKED_AT, 0), System.currentTimeMillis()) || !unmetered(context)) {
            return;
        }
        Single.timer(START_DELAY_SECONDS, TimeUnit.SECONDS)
                .flatMap(tick -> unmetered(context) ? check(prefs, BACKGROUND_PARALLEL)
                        : Single.just(Collections.<SourceProbe.Result>emptyList()))
                .subscribe(results -> { }, e -> Log.w("SourceHealth", "Automatic source check failed", e));
    }

    /** The status of each site at the last check; a site never checked is absent. */
    @NonNull
    public static Map<Provider, ProviderPage.Status> last(@NonNull SharedPreferences prefs) {
        return decode(prefs.getStringSet(PREF_RESULTS, Collections.<String>emptySet()));
    }

    /** When the last check ended (epoch millis); 0 when there was none. */
    public static long lastCheckedAt(@NonNull SharedPreferences prefs) {
        return prefs.getLong(PREF_CHECKED_AT, 0);
    }

    /** Pure: a day has passed since {@code lastAt}, or the clock went back. */
    static boolean due(long lastAt, long now) {
        return now - lastAt >= DAY_MS || now < lastAt;
    }

    /** Pure: one {@code prefValue=STATUS} entry per site. */
    @NonNull
    static Set<String> encode(@NonNull List<SourceProbe.Result> results) {
        Set<String> entries = new HashSet<>();
        for (SourceProbe.Result result : results) {
            entries.add(result.provider.prefValue + "=" + result.status.name());
        }
        return entries;
    }

    /** Pure: {@link #encode} read back; an entry of an unknown site or status is skipped. */
    @NonNull
    static Map<Provider, ProviderPage.Status> decode(@NonNull Set<String> entries) {
        Map<Provider, ProviderPage.Status> statuses = new EnumMap<>(Provider.class);
        for (String entry : entries) {
            int eq = entry.indexOf('=');
            if (eq < 0) {
                continue;
            }
            for (Provider provider : Provider.values()) {
                if (provider.prefValue.equals(entry.substring(0, eq))) {
                    try {
                        statuses.put(provider, ProviderPage.Status.valueOf(entry.substring(eq + 1)));
                    } catch (IllegalArgumentException ignored) {
                        // a status of an older version: unknown now
                    }
                }
            }
        }
        return statuses;
    }

    private static boolean unmetered(@NonNull Context context) {
        ConnectivityManager connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkCapabilities network = connectivity == null ? null
                : connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
        return network != null && network.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && network.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
    }
}
