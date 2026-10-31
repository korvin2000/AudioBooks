package com.fanok.audiobooks.net;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fanok.audiobooks.App;
import com.fanok.audiobooks.Consts;
import com.fanok.audiobooks.search.Provider;
import com.fanok.audiobooks.search.Sources;

import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The "Прокси" setting ({@link #PREF_MODE}): off (the device's network settings), the built-in SOCKS proxy from
 * {@link Consts}, or the automatic mode, where {@link ProxyFinder} supplies a verified public proxy. Installed once as
 * the process-wide {@link ProxySelector}, so every HTTP path (site pages through SiteHttp, audio and downloads through
 * OkHttp, covers) follows the setting without per-request code. The route is chosen per host: a source the user set to
 * go direct ({@link #PREF_DIRECT}) bypasses the proxy, with its subdomains and {@code SourceParser.hosts()}; any
 * other host (a shared CDN) follows the mode. The built-in credentials are handed only to the
 * built-in proxy's host:port, never to a public proxy or a site that asks for authentication.
 */
public final class AppProxy {

    /** The settings entry. Older versions stored the on/off switch here as a boolean; {@link #readMode} migrates it. */
    public static final String PREF = "pref_proxy";
    public static final String PREF_MODE = "pref_proxy_mode";
    public static final String MODE_OFF = "off";
    public static final String MODE_BUILTIN = "builtin";
    public static final String MODE_AUTO = "auto";
    /**
     * The sources that bypass the proxy: a set of {@code Provider.prefValue}. Absent or empty, every source takes the
     * proxy (as before the setting existed), and so does a source added later.
     */
    public static final String PREF_DIRECT = "pref_proxy_direct";

    private static volatile String mode = MODE_OFF;
    private static volatile ProxyFinder finder;
    private static volatile Set<String> direct = Collections.emptySet();
    /** Strong reference: SharedPreferences keeps its listeners weakly. */
    private static SharedPreferences.OnSharedPreferenceChangeListener listener;
    /** The platform's selector, which ours defers to for anything it does not route. */
    private static ProxySelector system;
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
        if (listener != null) {
            // once per process. The read also keeps the field: R8 removed it while it was only written, and the
            // listener, held by SharedPreferences weakly, went with the first GC (route changes stopped applying)
            return;
        }
        SharedPreferences prefs = prefs(context);
        mode = readMode(prefs);
        direct = readDirect(prefs);
        finder = ProxyFinder.get(context);
        listener = (p, key) -> {
            if (PREF_MODE.equals(key)) {
                applyMode(readMode(p));
            } else if (PREF_DIRECT.equals(key)) {
                Set<String> before = direct;
                direct = readDirect(p);
                // a pooled connection keeps the route it was opened with
                App.evictConnections();
                installSelector();
                ProxyFinder f = finder;
                String selected = selectedSource(p);
                if (MODE_AUTO.equals(mode) && f != null
                        && !ProxyFinder.targetOf(selected, before).equals(ProxyFinder.targetOf(selected, direct))) {
                    f.recheckCurrent(); // the proxy is now checked against another site
                }
            } else if ((PREF_SOURCE.equals(key) || Sources.PREF.equals(key)) && MODE_AUTO.equals(mode)) {
                // the proxy in use is checked against the new site; one it rejects is replaced
                ProxyFinder f = finder;
                if (f != null) {
                    f.recheckCurrent();
                }
            }
        };
        prefs.registerOnSharedPreferenceChangeListener(listener);
        system = ProxySelector.getDefault();
        installSelector();
        Authenticator.setDefault(new ProxyOnlyAuthenticator(Consts.PROXY_HOST, Consts.PROXY_PORT,
                Consts.PROXY_USER, Consts.PROXY_PASSWORD));
        if (MODE_AUTO.equals(mode)) {
            finder.recheckCurrent();
        }
    }

    /**
     * Installs a new instance of our selector. The shared OkHttp client keeps the one it was built with, which reads
     * the same state; site pages (HttpURLConnection) take the current default for each connection, and their pool
     * reuses a connection only for the selector it was opened with, so a new instance after a change of route keeps a
     * page from going out on a pooled connection of the old route.
     */
    private static void installSelector() {
        List<Proxy> builtin = Collections.singletonList(proxy());
        ProxySelector.setDefault(new Selector(system,
                () -> route(mode, configured(), builtin, AppProxy::autoProxies), AppProxy::proxied,
                AppProxy::onConnectFailed));
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
            installSelector();
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

    @Nullable
    private static String selectedSource(@NonNull SharedPreferences prefs) {
        try {
            return prefs.getString(PREF_SOURCE, null);
        } catch (ClassCastException e) {
            return null;
        }
    }

    /** Stores whether {@code provider} goes direct; the listener applies it. */
    public static void setDirect(@NonNull SharedPreferences prefs, @NonNull Provider provider, boolean goesDirect) {
        Set<String> now = new HashSet<>(readDirect(prefs)); // nothing stored reads as an immutable empty set
        if (goesDirect ? now.add(provider.prefValue) : now.remove(provider.prefValue)) {
            prefs.edit().putStringSet(PREF_DIRECT, now).apply();
        }
    }

    /** Every source direct, or every source through the proxy: one write, so one change of route. */
    public static void setAllDirect(@NonNull SharedPreferences prefs, boolean goDirect) {
        Set<String> now = new HashSet<>();
        if (goDirect) {
            for (Provider provider : Provider.values()) {
                now.add(provider.prefValue);
            }
        }
        prefs.edit().putStringSet(PREF_DIRECT, now).apply();
    }

    /** True when the user set {@code provider} to go direct; otherwise it takes the proxy when the mode has one. */
    public static boolean isDirect(@NonNull Provider provider) {
        return direct.contains(provider.prefValue);
    }

    /**
     * True when the mode's proxy can carry a request now: the built-in proxy of a build that has it, or a verified
     * public proxy in the automatic mode. Otherwise "through the proxy" is in fact direct.
     */
    public static boolean proxyUsable() {
        switch (mode) {
            case MODE_BUILTIN:
                return configured();
            case MODE_AUTO:
                return !autoProxies().isEmpty();
            default:
                return false;
        }
    }

    /** The stored {@link #PREF_DIRECT} set; a wrongly typed value means none. */
    @NonNull
    public static Set<String> readDirect(@NonNull SharedPreferences prefs) {
        try {
            Set<String> stored = prefs.getStringSet(PREF_DIRECT, null);
            return stored == null ? Collections.<String>emptySet() : new HashSet<>(stored);
        } catch (ClassCastException e) {
            return Collections.emptySet();
        }
    }

    /** True when requests to {@code host} take the proxy route of the mode: any host but one of a direct source's. */
    public static boolean proxied(@Nullable String host) {
        return isProxied(Provider.ofNetworkHost(host), direct);
    }

    /** Pure: an unknown host (null provider) follows the mode; a known one unless its source is in {@code direct}. */
    static boolean isProxied(@Nullable Provider provider, @NonNull Set<String> direct) {
        return provider == null || !direct.contains(provider.prefValue);
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
     * From {@link SiteHttp}: the site at {@code host} refused a request (403, 429). In the automatic mode, when that
     * request went through the proxy, the proxy in use is checked by one probe and replaced when the site refuses it.
     * Never throws.
     */
    public static void reportSuspect(@Nullable String host) {
        try {
            ProxyFinder f = finder;
            if (MODE_AUTO.equals(mode) && f != null && proxied(host)) {
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
     * Routes web traffic to a host {@code proxied} accepts through the proxies {@code route} names, in order; without
     * any, or for another host, defers to the system selector. A failed connection is reported to {@code failed}
     * (never thrown back into the client) and passed on to the system selector.
     */
    static final class Selector extends ProxySelector {
        private final ProxySelector fallback;
        private final Supplier<List<Proxy>> route;
        private final Predicate<String> proxied;
        private final BiConsumer<SocketAddress, IOException> failed;

        Selector(@Nullable ProxySelector fallback, @NonNull Supplier<List<Proxy>> route,
                @NonNull BiConsumer<SocketAddress, IOException> failed) {
            this(fallback, route, host -> true, failed);
        }

        Selector(@Nullable ProxySelector fallback, @NonNull Supplier<List<Proxy>> route,
                @NonNull Predicate<String> proxied, @NonNull BiConsumer<SocketAddress, IOException> failed) {
            this.fallback = fallback;
            this.route = route;
            this.proxied = proxied;
            this.failed = failed;
        }

        @Override
        public List<Proxy> select(URI uri) {
            String scheme = uri == null ? null : uri.getScheme();
            boolean web = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme) || "socket".equalsIgnoreCase(scheme);
            if (web && proxied.test(hostOf(uri))) {
                List<Proxy> proxies = route.get();
                if (proxies != null && !proxies.isEmpty()) {
                    return proxies;
                }
            }
            return fallback != null ? fallback.select(uri) : Collections.singletonList(Proxy.NO_PROXY);
        }

        /** The host of {@code uri}, lower case and without {@code www.}; null when it has none. */
        @Nullable
        private static String hostOf(@NonNull URI uri) {
            String host = uri.getHost();
            if (host == null) {
                return null;
            }
            host = host.toLowerCase(java.util.Locale.ROOT);
            return host.startsWith("www.") ? host.substring(4) : host;
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
