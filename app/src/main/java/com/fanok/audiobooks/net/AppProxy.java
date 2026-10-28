package com.fanok.audiobooks.net;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fanok.audiobooks.App;
import com.fanok.audiobooks.Consts;

import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * The "Прокси" setting ({@link #PREF_MODE}): off (the device's network settings), the built-in SOCKS proxy from
 * {@link Consts}, or the automatic mode, where {@link ProxyFinder} supplies a verified public proxy. Installed once as
 * the process-wide {@link ProxySelector}, so every HTTP path (site pages through SiteHttp, audio and downloads through
 * OkHttp, covers) follows the setting without per-request code. The built-in credentials are handed only to the
 * built-in proxy's host:port, never to a public proxy or a site that asks for authentication.
 */
public final class AppProxy {

    /** The settings entry. Older versions stored the on/off switch here as a boolean; {@link #readMode} migrates it. */
    public static final String PREF = "pref_proxy";
    public static final String PREF_MODE = "pref_proxy_mode";
    public static final String MODE_OFF = "off";
    public static final String MODE_BUILTIN = "builtin";
    public static final String MODE_AUTO = "auto";

    private static volatile String mode = MODE_OFF;
    private static volatile ProxyFinder finder;
    /** Strong reference: SharedPreferences keeps its listeners weakly. */
    private static SharedPreferences.OnSharedPreferenceChangeListener listener;
    /** The catalog site setting: the automatic proxy must reach the selected site. */
    private static final String PREF_SOURCE = "sorce_books";

    private AppProxy() {
    }

    /** True when this build carries the built-in proxy's values; without them the built-in mode is inert. */
    public static boolean configured() {
        return !Consts.PROXY_HOST.isEmpty() && Consts.PROXY_PORT > 0;
    }

    /** {@link #MODE_OFF}, {@link #MODE_BUILTIN} or {@link #MODE_AUTO}. */
    @NonNull
    public static String mode() {
        return mode;
    }

    /** Call once, before the first HTTP client is built (OkHttp captures the default selector at construction). */
    public static void install(@NonNull Context context) {
        SharedPreferences prefs = prefs(context);
        mode = readMode(prefs);
        finder = ProxyFinder.get(context);
        listener = (p, key) -> {
            if (PREF_MODE.equals(key)) {
                applyMode(readMode(p));
            } else if (PREF_SOURCE.equals(key) && MODE_AUTO.equals(mode)) {
                // the proxy in use is checked against the new site; one it rejects is replaced
                ProxyFinder f = finder;
                if (f != null) {
                    f.recheckCurrent();
                }
            }
        };
        prefs.registerOnSharedPreferenceChangeListener(listener);
        List<Proxy> builtin = Collections.singletonList(proxy());
        ProxySelector.setDefault(new Selector(ProxySelector.getDefault(),
                () -> route(mode, configured(), builtin, AppProxy::autoProxies), AppProxy::onConnectFailed));
        Authenticator.setDefault(new ProxyOnlyAuthenticator(Consts.PROXY_HOST, Consts.PROXY_PORT,
                Consts.PROXY_USER, Consts.PROXY_PASSWORD));
        if (MODE_AUTO.equals(mode)) {
            finder.recheckCurrent();
        }
    }

    /** Stores and applies a mode; the automatic mode starts a search when no proxy is verified yet. */
    public static void setMode(@NonNull Context context, @NonNull String newMode) {
        String value = normalize(newMode);
        if (finder == null) {
            finder = ProxyFinder.get(context);
        }
        prefs(context).edit().putString(PREF_MODE, value).apply();
        applyMode(value);
    }

    private static void applyMode(@NonNull String value) {
        String before = mode;
        mode = value;
        if (!value.equals(before)) {
            // a pooled connection of the old route (direct or another proxy) must not carry the next request
            App.evictConnections();
        }
        ProxyFinder f = finder;
        if (MODE_AUTO.equals(value) && f != null) {
            f.ensureProxy();
        }
    }

    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getSharedPreferences(context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
    }

    /**
     * The stored mode. Migration: the old boolean switch ({@link #PREF}) on becomes {@link #MODE_BUILTIN} once and the
     * old key is removed. DATA-12: an older version stored that key as a string; such a value means "off". A wrongly
     * typed or unknown mode is "off" as well.
     */
    @NonNull
    static String readMode(@NonNull SharedPreferences prefs) {
        if (prefs.contains(PREF)) {
            boolean on;
            try {
                on = prefs.getBoolean(PREF, false);
            } catch (ClassCastException e) {
                on = false;
            }
            SharedPreferences.Editor editor = prefs.edit().remove(PREF);
            if (on && !prefs.contains(PREF_MODE)) {
                editor.putString(PREF_MODE, MODE_BUILTIN);
            }
            editor.apply();
        }
        try {
            return normalize(prefs.getString(PREF_MODE, MODE_OFF));
        } catch (ClassCastException e) {
            prefs.edit().remove(PREF_MODE).apply();
            return MODE_OFF;
        }
    }

    @NonNull
    static String normalize(@Nullable String value) {
        if (MODE_BUILTIN.equals(value) || MODE_AUTO.equals(value)) {
            return value;
        }
        return MODE_OFF;
    }

    /**
     * The proxies for web traffic in {@code mode}, tried in order; empty for the system's choice. The automatic mode
     * offers the proxy in use and its spares, so a client fails over to a spare within the same call.
     */
    @NonNull
    static List<Proxy> route(@NonNull String mode, boolean configured, @NonNull List<Proxy> builtin,
            @NonNull Supplier<List<Proxy>> auto) {
        switch (mode) {
            case MODE_BUILTIN:
                return configured ? builtin : Collections.emptyList();
            case MODE_AUTO:
                List<Proxy> proxies = auto.get();
                return proxies != null ? proxies : Collections.emptyList();
            default:
                return Collections.emptyList();
        }
    }

    @NonNull
    private static List<Proxy> autoProxies() {
        ProxyFinder f = finder;
        return f != null ? f.proxiesForRequest() : Collections.emptyList();
    }

    private static void onConnectFailed(@Nullable SocketAddress address, @Nullable IOException failure) {
        ProxyFinder f = finder;
        if (MODE_AUTO.equals(mode) && f != null) {
            f.reportFailure(address);
        }
    }

    /**
     * From the shared client's event listener: a call that was not cancelled could not connect through {@code address}
     * (a refused tunnel or handshake reaches only this). Like a selector report, a hint the finder confirms with one
     * probe before it moves on; the same failure reported by both queues one probe.
     */
    public static void reportConnectFailure(@Nullable SocketAddress address, @Nullable IOException failure) {
        ProxyFinder f = finder;
        if (MODE_AUTO.equals(mode) && f != null) {
            f.reportFailure(address, true);
        }
    }

    /**
     * From {@link SiteHttp}: the site refused a request (403, 429). In the automatic mode the proxy in use is checked by
     * one probe and replaced when the site refuses it. Never throws.
     */
    public static void reportSuspect() {
        try {
            ProxyFinder f = finder;
            if (MODE_AUTO.equals(mode) && f != null) {
                f.reportSuspect();
            }
        } catch (RuntimeException ignored) {
            // a hint must never break the request that gave it
        }
    }

    static Proxy proxy() {
        return new Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(Consts.PROXY_HOST, Math.max(Consts.PROXY_PORT, 1)));
    }

    /**
     * Routes web traffic through the proxies {@code route} names, in order; without any, defers to the system selector.
     * A failed connection is reported to {@code failed} (never thrown back into the client) and passed on to the
     * system selector.
     */
    static final class Selector extends ProxySelector {
        private final ProxySelector fallback;
        private final Supplier<List<Proxy>> route;
        private final BiConsumer<SocketAddress, IOException> failed;

        Selector(@Nullable ProxySelector fallback, @NonNull Supplier<List<Proxy>> route,
                @NonNull BiConsumer<SocketAddress, IOException> failed) {
            this.fallback = fallback;
            this.route = route;
            this.failed = failed;
        }

        @Override
        public List<Proxy> select(URI uri) {
            String scheme = uri == null ? null : uri.getScheme();
            boolean web = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme) || "socket".equalsIgnoreCase(scheme);
            if (web) {
                List<Proxy> proxies = route.get();
                if (proxies != null && !proxies.isEmpty()) {
                    return proxies;
                }
            }
            return fallback != null ? fallback.select(uri) : Collections.singletonList(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            try {
                failed.accept(sa, ioe);
            } catch (RuntimeException ignored) {
                // a report must never break the client's own error handling
            }
            if (fallback != null) {
                fallback.connectFailed(uri, sa, ioe);
            }
        }
    }

    /** Answers only the proxy's own challenge (SEC-03: the old global authenticator answered every challenger). */
    static final class ProxyOnlyAuthenticator extends Authenticator {
        private final String host;
        private final int port;
        private final String user;
        private final String password;

        ProxyOnlyAuthenticator(String host, int port, String user, String password) {
            this.host = host;
            this.port = port;
            this.user = user;
            this.password = password;
        }

        @Override
        protected PasswordAuthentication getPasswordAuthentication() {
            if (user.isEmpty() || !host.equalsIgnoreCase(getRequestingHost()) || getRequestingPort() != port) {
                return null;
            }
            return new PasswordAuthentication(user, password.toCharArray());
        }
    }
}
