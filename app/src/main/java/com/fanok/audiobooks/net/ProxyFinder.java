package com.fanok.audiobooks.net;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fanok.audiobooks.App;
import com.fanok.audiobooks.Consts;
import com.fanok.audiobooks.Url;
import com.fanok.audiobooks.search.Provider;
import com.fanok.audiobooks.search.Sources;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import okhttp3.ConnectionPool;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * The "Автоматический подбор" proxy mode: downloads public proxy lists ({@link ProxySources}) and keeps the candidates
 * that pass a control check: one HTTPS request through the proxy to an independent connectivity-check server
 * ({@link #CONTROLS}), whose answer a proxy cannot fake. An audiobook site, often down, never judges a proxy: the
 * selected one ({@link #targetOf}) only ranks the verified proxies, those it serves first. {@link AppProxy} routes
 * traffic through the proxy in use and its spares ({@link #proxiesForRequest()}) and reports a suspect proxy: a
 * transport failure leaves the proxy when control checks fail through it and pass through a later one, or when none
 * passes but the control server answers directly (the list runs out); a site's refusal swaps it with a spare that the
 * site serves. An exhausted list starts a new search; a search that finds nothing keeps the list in use. Automatic
 * searches run at most once per {@link #AUTO_SCAN_INTERVAL_MS} and never without a network; the user's "Найти заново"
 * ({@link #rescan()}) always may. Listeners hear every state change on the main thread.
 */
public final class ProxyFinder {

    /** {@link #KEPT}: the last search found nothing, the list found before stays in use. */
    public enum Phase { IDLE, SCANNING, READY, KEPT, FAILED, NO_NETWORK }

    /** Called on the main thread. */
    public interface Listener {
        void onProxyState(@NonNull State state);
    }

    /** A consistent snapshot of the finder. */
    public static final class State {
        @NonNull
        public final Phase phase;
        /** Candidates whose control check finished in the running (or last) search, out of {@link #total}. */
        public final int tested;
        public final int total;
        /** Proxies the running (or last) search verified by a control check. */
        public final int found;
        /** The proxy in use, or null. */
        @Nullable
        public final ProxyCandidate current;
        /** Verified proxies after the one in use, not failed yet. */
        public final int spares;
        /** When the last search finished (wall clock), or 0. */
        public final long lastScanAt;
        /** Verified proxies of the running (or last) search through which {@link #site} answered and refused. */
        public final int refused;
        /** Candidates of the running (or last) search that failed the control check: dead, silent, tunnel refused. */
        public final int unreachable;
        /** The home page the running (or last) search ranked its proxies by; null when it ranked none. */
        @Nullable
        public final String site;
        /** Verified proxies that passed {@link #site}. */
        public final int sitePassed;
        /**
         * When the proxies found all stopped answering (wall clock), while no proxy has been in use since; 0 otherwise.
         * One value per run-out, so the UI can offer a new search once for it.
         */
        public final long exhaustedAt;

        State(@NonNull Phase phase, int tested, int total, int found, @Nullable ProxyCandidate current, int spares,
                long lastScanAt, int refused, int unreachable, @Nullable String site, int sitePassed,
                long exhaustedAt) {
            this.phase = phase;
            this.tested = tested;
            this.total = total;
            this.found = found;
            this.current = current;
            this.spares = spares;
            this.lastScanAt = lastScanAt;
            this.refused = refused;
            this.unreachable = unreachable;
            this.site = site;
            this.sitePassed = sitePassed;
            this.exhaustedAt = exhaustedAt;
        }
    }

    /** The finder's network: list downloads, control checks and site checks. Replaced in tests. */
    interface Network {
        /** The text of a list (at most {@link #MAX_LIST_BYTES}), fetched directly, never through a proxy. */
        @NonNull
        String fetch(@NonNull ProxySources.Source source) throws IOException;

        /** Asks {@code control} through the candidate; returns the milliseconds it took, or throws unless it passes. */
        long control(@NonNull ProxyCandidate candidate, @NonNull Control control, int timeoutMs) throws IOException;

        /**
         * Opens the site's home page {@code page} through the candidate; returns the milliseconds it took, or throws
         * when it fails, a {@link SiteRefused} when the site's answer is not {@link ProxyFinder#usable} (a 403
         * geo-block or challenge, a 429, a 5xx).
         */
        long site(@NonNull ProxyCandidate candidate, @NonNull String page, int timeoutMs) throws IOException;

        /** Asks {@code control} directly, through no proxy; returns when it passes, throws otherwise. */
        void reachable(@NonNull Control control, int timeoutMs) throws IOException;
    }

    /**
     * A connectivity-check server: it always answers {@link #code} and, when given, {@link #marker} within the first
     * {@link #MARKER_BYTES} of the body. Over HTTPS with the certificate checked, a proxy cannot fake that answer.
     */
    static final class Control {
        final String url;
        final int code;
        @Nullable
        final String marker;

        Control(@NonNull String url, int code, @Nullable String marker) {
            this.url = url;
            this.code = code;
            this.marker = marker;
        }

        /** The server's own answer: exactly its code, and its marker in {@code body} when it has one. */
        boolean passes(int code, @NonNull String body) {
            return code == this.code && (marker == null || body.contains(marker));
        }

        @NonNull
        @Override
        public String toString() {
            return url;
        }
    }

    /** The site answered through the proxy with a code that is not {@link #usable}: the tunnel works, the site refuses. */
    static final class SiteRefused extends IOException {
        final int code;

        SiteRefused(int code, @NonNull ProxyCandidate candidate) {
            super("HTTP " + code + " through " + candidate);
            this.code = code;
        }
    }

    /**
     * The control servers, one per operator (so another server is another operator's), all measured 2026-11-03 over
     * HTTPS. Not Cloudflare (its TLS is throttled in Russia), not Microsoft (no HTTPS).
     */
    static final List<Control> CONTROLS = Collections.unmodifiableList(Arrays.asList(
            new Control("https://www.gstatic.com/generate_204", 204, null),
            new Control("https://detectportal.firefox.com/success.txt", 200, "success"),
            new Control("https://captive.apple.com/hotspot-detect.html", 200, "Success"),
            new Control("https://connectivity-check.ubuntu.com/", 204, null)));

    static final String PREF_LIST = "pref_proxy_auto_list";
    static final int KEEP = 8;
    /** A search stops at this many control-verified proxies: a verified one passes again only 60-70 % of the time, spares matter. */
    static final int ENOUGH = 8;
    /** Verified proxies offered after the one in use, so a client fails over within the same call. */
    static final int SPARES_IN_ROUTE = 2;
    /** The site check of a search ends once this many passed: the proxy in use and its spares in the route. */
    static final int SITE_READY = SPARES_IN_ROUTE + 1;
    /** The site check's own threads: the control probes still running do not delay it. */
    static final int SITE_THREADS = 4;
    /** The site check's share of a search: the control checks take no new candidate this long before the deadline. */
    static final long SITE_BUDGET_MS = 12_000;
    /** A site on which the proxy in use and its spare both failed is not compared again for this long. */
    static final long SITE_COOLDOWN_MS = 60_000;
    static final int MAX_CANDIDATES = 400;
    static final int PROBE_THREADS = 24;
    /** Per connect, read and write of a check in a search or of a spare in a confirmation; its call gets twice this. */
    static final int PROBE_TIMEOUT_MS = 5_000;
    /** The same for a confirmation of the proxy in use: a call of at most 15 s. */
    static final int RECHECK_TIMEOUT_MS = 7_500;
    /** Per connect and read of a list: not above the lists client's call timeout, {@link #FETCH_DEADLINE_MS}. */
    static final int LIST_TIMEOUT_MS = 10_000;
    static final long SCAN_DEADLINE_MS = 60_000;
    /** The list downloads get this much of the search (they take 0.1-0.5 s each); the checks get the rest. */
    static final long FETCH_DEADLINE_MS = 12_000;
    static final long AUTO_SCAN_INTERVAL_MS = 10 * 60_000;
    static final long NO_NETWORK_RETRY_MS = 30_000;
    /** After a confirmation passed, reports of the same proxy (or site) are not checked again for this long. */
    static final long CONFIRM_QUIET_MS = 15_000;
    /** A failed control confirmation is repeated once after this pause (Wi-Fi still associating, a captive portal). */
    static final long RETRY_DELAY_MS = 5_000;
    static final int MAX_LIST_BYTES = 256 * 1024;
    /** A control's marker is looked for in this much of its body: the answers are a few bytes. */
    static final int MARKER_BYTES = 1024;

    private static volatile ProxyFinder instance;

    private final SharedPreferences prefs;
    private final Network network;
    private final Executor main;
    private final Executor worker;
    private final BooleanSupplier online;
    /** The home page that ranks and compares the proxies, or null when no site takes the proxy: {@link #targetOf}. */
    private final Supplier<String> target;
    private final LongSupplier clock;
    private final Random random;
    private final List<ProxySources.Source> sources;
    /** Each list's yield of the last searches, for the next one's quotas; guarded by this. */
    private final ProxySources.ListStats stats;
    private final int probeThreads;
    /** Told when the proxy in use changes (pooled connections through the old route are then dropped). */
    private final Runnable switched;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean scanning = new AtomicBoolean();
    /** Replaced in tests. */
    volatile long retryDelayMs = RETRY_DELAY_MS;
    volatile long scanDeadlineMs = SCAN_DEADLINE_MS;
    volatile long siteBudgetMs = SITE_BUDGET_MS;

    // guarded by this
    private List<ProxyCandidate> verified = Collections.emptyList();
    private int index;
    private long lastScanAt;
    private Phase phase = Phase.IDLE;
    private int tested;
    private int total;
    private int found;
    private int refused;
    private int unreachable;
    @Nullable
    private String site;
    private int sitePassed;
    /** {@link State#exhaustedAt}, saved with the list; cleared by {@link #updateCurrent} once a proxy is in use. */
    private long exhaustedAt;
    /** The proxy whose last control confirmation passed, and when: see {@link #CONFIRM_QUIET_MS}. */
    private ProxyCandidate confirmed;
    private long confirmedAt;
    /**
     * The confirmations asked for and not started yet: a transport one, one on the first site of {@link #target} (read
     * when it runs), and one on the home page of a site that refused.
     */
    private boolean transportAsked;
    private boolean targetAsked;
    @Nullable
    private String siteAsked;
    /** A confirmation is queued or running on the worker. */
    private boolean confirming;
    /** Home page -> until when it is not compared again. */
    private final Map<String, Long> siteQuiet = new HashMap<>();

    /** Derived from verified/index under the lock; read without it by the selector. */
    private volatile ProxyCandidate current;
    private volatile Proxy currentProxy;
    /** The proxy in use and up to {@link #SPARES_IN_ROUTE} spares; empty without a verified proxy. */
    private volatile List<Proxy> route = Collections.emptyList();
    private volatile long nextAutoScanAt;

    ProxyFinder(@NonNull SharedPreferences prefs, @NonNull Network network, @NonNull Executor main,
            @NonNull Executor worker, @NonNull BooleanSupplier online, @NonNull Supplier<String> target,
            @NonNull LongSupplier clock, @NonNull Random random, @NonNull List<ProxySources.Source> sources,
            int probeThreads, @NonNull Runnable switched) {
        this.prefs = prefs;
        this.network = network;
        this.main = main;
        this.worker = worker;
        this.online = online;
        this.target = target;
        this.clock = clock;
        this.random = random;
        this.sources = sources;
        this.stats = ProxySources.ListStats.fromJson(readSaved(prefs, ProxySources.ListStats.PREF));
        this.probeThreads = probeThreads;
        this.switched = switched;
        Saved saved = Saved.fromJson(readSaved(prefs, PREF_LIST));
        synchronized (this) {
            verified = saved.proxies;
            index = saved.index;
            lastScanAt = saved.scannedAt;
            nextAutoScanAt = saved.scannedAt > 0 ? saved.scannedAt + AUTO_SCAN_INTERVAL_MS : 0;
            updateCurrent();
            // a run-out as saved; a list saved before that was kept, which ran out: now
            exhaustedAt = current != null ? 0 : saved.exhaustedAt > 0 ? saved.exhaustedAt
                    : verified.isEmpty() ? 0 : clock.getAsLong();
            phase = current != null ? Phase.READY : exhaustedAt > 0 ? Phase.FAILED : Phase.IDLE;
        }
    }

    /** The process-wide finder, created on first use. */
    @NonNull
    public static ProxyFinder get(@NonNull Context context) {
        ProxyFinder finder = instance;
        if (finder == null) {
            synchronized (ProxyFinder.class) {
                finder = instance;
                if (finder == null) {
                    Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
                    SharedPreferences prefs = app.getSharedPreferences(app.getPackageName() + "_preferences",
                            Context.MODE_PRIVATE);
                    Handler handler = new Handler(Looper.getMainLooper());
                    finder = new ProxyFinder(prefs, new OkNetwork(), handler::post,
                            Executors.newSingleThreadExecutor(daemon("proxy-finder")),
                            () -> !Consts.isOffline(app), () -> targetOf(prefs), System::currentTimeMillis,
                            new Random(), ProxySources.ALL, PROBE_THREADS, App::evictConnections);
                    instance = finder;
                }
            }
        }
        return finder;
    }

    /** The finder if it exists yet; the selector never creates it. */
    @Nullable
    static ProxyFinder peek() {
        return instance;
    }

    public void addListener(@NonNull Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(@NonNull Listener listener) {
        listeners.remove(listener);
    }

    @NonNull
    public synchronized State state() {
        int spares = current != null ? verified.size() - index - 1 : 0;
        return new State(phase, tested, total, found, current, spares, lastScanAt, refused, unreachable, site,
                sitePassed, exhaustedAt);
    }

    /** The proxy in use, or null when none is verified. */
    @Nullable
    public Proxy currentProxy() {
        return currentProxy;
    }

    /**
     * For the selector: the proxy in use, then up to {@link #SPARES_IN_ROUTE} verified spares, so a client tries
     * them in order within one call. Empty without a verified proxy; a search then starts when one is due.
     */
    @NonNull
    List<Proxy> proxiesForRequest() {
        List<Proxy> proxies = route;
        if (proxies.isEmpty()) {
            scanIfDue();
        }
        return proxies;
    }

    /** The automatic mode was chosen: search unless a proxy is known (an automatic search, so rate-limited). */
    public void ensureProxy() {
        if (currentProxy == null) {
            scanIfDue();
        }
    }

    /** "Найти заново": a new search now, whenever the last one was, unless one is running. */
    public void rescan() {
        startScan(true);
    }

    /** An automatic search, when the last one is old enough. */
    void scanIfDue() {
        if (clock.getAsLong() >= nextAutoScanAt) {
            startScan(false);
        }
    }

    /** App start in the automatic mode: the proxy in use is confirmed by control checks ({@link #confirmTransport}). */
    public void recheckCurrent() {
        ask(true, null, false);
    }

    /**
     * Another catalog site chosen, or the direct set changed: the proxy in use is compared on the new first site
     * ({@link #confirmSite}); nothing when no site takes the proxy.
     */
    public void recheckSite() {
        ask(false, null, true); // the site is read when the comparison runs: a later change, or none, counts
    }

    /**
     * A client reported a failure through {@code address}, see {@link #reportFailure(SocketAddress, boolean)}.
     */
    public void reportFailure(@Nullable SocketAddress address) {
        reportFailure(address, false);
    }

    /**
     * A client reported a failure through {@code address}. Such reports are hints only: OkHttp also reports a cancelled
     * call (a skipped chapter) or any error while sending the request, so the proxy in use is confirmed by control
     * checks ({@link #confirmTransport}) and moves on only when they fail. Only the proxy in use counts (a spare, or a
     * proxy already left behind, does not), and offline reports are not the proxy's. A proxy whose confirmation passed
     * within {@link #CONFIRM_QUIET_MS} is not checked again unless the failure is {@code definite} (a refused tunnel or
     * handshake of a call that was not cancelled).
     */
    public void reportFailure(@Nullable SocketAddress address, boolean definite) {
        ProxyCandidate candidate = current;
        if (candidate == null || !candidate.hasAddress(address) || !online.getAsBoolean()) {
            return;
        }
        synchronized (this) {
            if (!definite && candidate.equals(confirmed) && clock.getAsLong() - confirmedAt < CONFIRM_QUIET_MS) {
                return;
            }
        }
        ask(true, null, false);
    }

    /**
     * The site at {@code host} refused a request (403, 429) through the proxy in use: the proxy is compared with the
     * first spare on that site's home page ({@link #confirmSite}). A host of no known site is ignored, and so is a
     * report while offline.
     */
    public void reportSuspect(@Nullable String host) {
        Provider provider = Provider.ofNetworkHost(host);
        if (provider != null && current != null && online.getAsBoolean()) {
            askSite(provider.server + "/");
        }
    }

    private void askSite(@NonNull String page) {
        synchronized (this) {
            if (quiet(page)) {
                return;
            }
        }
        ask(false, page, false);
    }

    /**
     * Queues a confirmation on the worker, one at a time: one asked while another is queued joins it, one asked while
     * another runs runs again afterwards, with the inputs of then (the proxy in use, the first site of {@link #target}).
     */
    private void ask(boolean transport, @Nullable String page, boolean retarget) {
        synchronized (this) {
            transportAsked |= transport;
            targetAsked |= retarget;
            if (page != null) {
                siteAsked = page;
            }
            if (confirming) {
                return;
            }
            confirming = true;
        }
        queueConfirm();
    }

    private void queueConfirm() {
        if (!execute(this::confirm)) {
            synchronized (this) {
                confirming = false;
                transportAsked = false;
                targetAsked = false;
                siteAsked = null;
            }
        }
    }

    /** On the worker: the confirmations asked so far, the transport one first; what is asked meanwhile runs next. */
    private void confirm() {
        boolean transport;
        boolean retarget;
        String page;
        synchronized (this) {
            transport = transportAsked;
            retarget = targetAsked;
            page = siteAsked;
            transportAsked = false;
            targetAsked = false;
            siteAsked = null;
        }
        try {
            if (transport) {
                confirmTransport();
            }
            if (page != null) {
                confirmSite(page);
            }
            String first = retarget ? target.get() : null; // the site of now; none: nothing to compare
            if (first != null && !first.equals(page)) {
                confirmSite(first);
            }
        } finally {
            boolean again;
            synchronized (this) {
                again = transportAsked || targetAsked || siteAsked != null;
                confirming = again;
            }
            if (again) {
                queueConfirm();
            }
        }
    }

    /**
     * The proxy in use by control checks: one, and after {@link #RETRY_DELAY_MS} one of another operator. When both
     * fail, the spares are checked in order by the second server, each within {@link #PROBE_TIMEOUT_MS}: the first that
     * passes takes over, the proxy in use and the dead spares before it are left ({@link #advancePast}). When none
     * passes, the same server asked directly decides: it answers, so the proxies are dead, not the network, and the
     * list runs out; it fails too, so the network may be at fault (online means only that Android has an active
     * network): the list stays. Either way a search starts when due.
     */
    private void confirmTransport() {
        ProxyCandidate candidate = current;
        if (candidate == null) {
            scanIfDue();
            return;
        }
        List<Control> controls = new ArrayList<>(CONTROLS);
        Collections.shuffle(controls, random); // one server per operator: the second is another operator's
        for (int attempt = 0; attempt < 2; attempt++) {
            if (!online.getAsBoolean() || current != candidate || (attempt > 0 && !pause(retryDelayMs))) {
                return;
            }
            try {
                refresh(candidate, network.control(candidate, controls.get(attempt), RECHECK_TIMEOUT_MS));
                return;
            } catch (IOException | RuntimeException e) {
                // once more through another operator, then the spares decide
            }
        }
        for (ProxyCandidate spare : spares()) {
            if (!online.getAsBoolean() || current != candidate) {
                return;
            }
            try {
                refresh(spare, network.control(spare, controls.get(1), PROBE_TIMEOUT_MS));
                advancePast(candidate, spare);
                return;
            } catch (IOException | RuntimeException e) {
                // dead as well, or the network: the next one, then the direct check, decides
            }
        }
        if (!online.getAsBoolean() || current != candidate) {
            return;
        }
        if (directWorks(controls.get(1))) {
            advancePast(candidate, null);
        } else {
            scanIfDue(); // a common outage, not the proxies': the list stays
        }
    }

    /** True when {@code control} answers the device directly: the network works. */
    private boolean directWorks(@NonNull Control control) {
        try {
            network.reachable(control, RECHECK_TIMEOUT_MS);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * The proxy in use on {@code page}, a site's home page. It passes: nothing changes. It fails: the first spare is
     * asked the same; it passes, the two swap ({@link #promote}); it fails too (or there is none), nothing changes and
     * the site is not compared again for {@link #SITE_COOLDOWN_MS}. A site never makes a proxy leave the list.
     */
    private void confirmSite(@NonNull String page) {
        ProxyCandidate candidate = current;
        synchronized (this) {
            if (candidate == null || quiet(page)) {
                return;
            }
        }
        if (!online.getAsBoolean()) {
            return;
        }
        try {
            network.site(candidate, page, RECHECK_TIMEOUT_MS);
            quietFor(page, CONFIRM_QUIET_MS);
            return;
        } catch (IOException | RuntimeException e) {
            // the spare decides
        }
        if (!online.getAsBoolean() || current != candidate) {
            return;
        }
        ProxyCandidate spare = spare();
        if (spare != null) {
            try {
                long ms = network.site(spare, page, RECHECK_TIMEOUT_MS);
                promote(candidate, spare.checked(Math.max(ms, 1), clock.getAsLong()));
                quietFor(page, CONFIRM_QUIET_MS);
                return;
            } catch (IOException | RuntimeException e) {
                // neither: the site, not the proxy
            }
        }
        quietFor(page, SITE_COOLDOWN_MS);
    }

    // guarded by this
    private boolean quiet(@NonNull String page) {
        Long until = siteQuiet.get(page);
        return until != null && clock.getAsLong() < until;
    }

    private synchronized void quietFor(@NonNull String page, long ms) {
        siteQuiet.put(page, clock.getAsLong() + ms);
    }

    /** The first spare after the proxy in use, or null. */
    @Nullable
    private synchronized ProxyCandidate spare() {
        return current != null && index + 1 < verified.size() ? verified.get(index + 1) : null;
    }

    /** The spares after the proxy in use, in order; none without one. */
    @NonNull
    private synchronized List<ProxyCandidate> spares() {
        return current != null ? new ArrayList<>(verified.subList(index + 1, verified.size()))
                : Collections.<ProxyCandidate>emptyList();
    }

    private static boolean pause(long ms) {
        if (ms <= 0) {
            return true;
        }
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * The proxy in use failed: {@code next}, a spare that passed, takes over and the proxies before it are left; null
     * leaves them all: the list runs out ({@link Phase#FAILED}, {@link State#exhaustedAt}), a search starts when due.
     */
    private void advancePast(@NonNull ProxyCandidate failed, @Nullable ProxyCandidate next) {
        boolean exhausted;
        synchronized (this) {
            int to = next == null ? verified.size() : verified.indexOf(next);
            if (current != failed || to <= index) {
                return;
            }
            index = to;
            updateCurrent();
            exhausted = current == null;
            if (exhausted) {
                exhaustedAt = clock.getAsLong();
            }
            save();
            if (phase != Phase.SCANNING) {
                phase = exhausted ? Phase.FAILED : phase == Phase.KEPT ? Phase.KEPT : Phase.READY;
            }
        }
        publish();
        if (exhausted) {
            scanIfDue();
        }
    }

    /** The first spare, which a site served where the proxy in use failed, takes its place; the old one follows it. */
    private void promote(@NonNull ProxyCandidate failed, @NonNull ProxyCandidate spare) {
        synchronized (this) {
            if (current != failed || index + 1 >= verified.size() || !verified.get(index + 1).equals(spare)) {
                return;
            }
            List<ProxyCandidate> list = new ArrayList<>(verified);
            list.set(index, spare);
            list.set(index + 1, failed);
            verified = Collections.unmodifiableList(list);
            updateCurrent();
            save();
        }
        publish();
    }

    /** A control check of {@code candidate} passed in {@code ms}: its latency, and the quiet of {@link #CONFIRM_QUIET_MS}. */
    private synchronized void refresh(@NonNull ProxyCandidate candidate, long ms) {
        confirmed = candidate;
        confirmedAt = clock.getAsLong();
        int at = verified.indexOf(candidate);
        if (at < 0) {
            return;
        }
        List<ProxyCandidate> list = new ArrayList<>(verified);
        list.set(at, candidate.checked(ms, clock.getAsLong()));
        verified = Collections.unmodifiableList(list);
        if (current == candidate) {
            updateCurrent();
        }
        save();
    }

    private void startScan(boolean manual) {
        if (!scanning.compareAndSet(false, true)) {
            return;
        }
        synchronized (this) {
            long now = clock.getAsLong();
            if (!manual && now < nextAutoScanAt) {
                scanning.set(false);
                return;
            }
            nextAutoScanAt = now + AUTO_SCAN_INTERVAL_MS;
            phase = Phase.SCANNING;
            tested = 0;
            total = 0;
            found = 0;
            refused = 0;
            unreachable = 0;
            site = null;
            sitePassed = 0;
        }
        publish();
        if (!execute(this::runScan)) {
            synchronized (this) {
                phase = current != null ? Phase.READY : Phase.FAILED;
            }
            scanning.set(false);
            publish();
        }
    }

    private boolean execute(@NonNull Runnable task) {
        try {
            worker.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    private void runScan() {
        try {
            if (!online.getAsBoolean()) {
                synchronized (this) {
                    phase = Phase.NO_NETWORK;
                    nextAutoScanAt = clock.getAsLong() + NO_NETWORK_RETRY_MS;
                }
                return;
            }
            Found found = scan();
            if (found.verified.isEmpty() && !online.getAsBoolean()) {
                // the network went away during the search: nothing was disproved, the known list stays
                synchronized (this) {
                    phase = Phase.NO_NETWORK;
                    nextAutoScanAt = clock.getAsLong() + NO_NETWORK_RETRY_MS;
                }
                return;
            }
            remember(found.perList);
            adopt(rank(found.passed, found.verified, KEEP));
        } catch (RuntimeException e) {
            synchronized (this) {
                phase = current != null ? Phase.READY : Phase.FAILED;
            }
        } finally {
            scanning.set(false);
            publish();
        }
    }

    /** What one search found: the control-verified proxies, those the site also served, each list's control tally. */
    static final class Found {
        final List<ProxyCandidate> verified;
        /** Copies of some of {@link #verified} with the site's latency. */
        final List<ProxyCandidate> passed;
        /** {list name: [ok, tried]} of this search's control checks, for {@link ProxySources.ListStats}. */
        final Map<String, int[]> perList;

        Found(@NonNull List<ProxyCandidate> verified, @NonNull List<ProxyCandidate> passed,
                @NonNull Map<String, int[]> perList) {
            this.verified = verified;
            this.passed = passed;
            this.perList = perList;
        }
    }

    /** One finished check: the candidate, the checked copy when it passed, whether a site answered and refused. */
    private static final class Check {
        final ProxyCandidate candidate;
        @Nullable
        final ProxyCandidate ok;
        final boolean refused;

        Check(@NonNull ProxyCandidate candidate, @Nullable ProxyCandidate ok, boolean refused) {
            this.candidate = candidate;
            this.ok = ok;
            this.refused = refused;
        }
    }

    /**
     * Downloads the lists, control-checks candidates until {@link #ENOUGH} pass or the site check's share of the
     * deadline is reached, then ranks the verified ones by the site ({@link #siteCheck}), which can only reorder them.
     */
    @NonNull
    private Found scan() {
        long start = System.nanoTime();
        long deadline = start + TimeUnit.MILLISECONDS.toNanos(scanDeadlineMs);
        String page = target.get(); // one snapshot per search
        long discoverBy = page != null ? deadline - TimeUnit.MILLISECONDS.toNanos(siteBudgetMs) : deadline;
        Found controlled;
        ExecutorService pool = Executors.newFixedThreadPool(probeThreads, daemon("proxy-probe"));
        try {
            List<List<ProxyCandidate>> lists = fetchAll(pool,
                    Math.min(discoverBy, start + TimeUnit.MILLISECONDS.toNanos(FETCH_DEADLINE_MS)));
            List<ProxyCandidate> previous;
            int[] quota = new int[lists.size()];
            synchronized (this) {
                previous = new ArrayList<>(verified.subList(Math.min(index, verified.size()), verified.size()));
                for (int i = 0; i < quota.length; i++) {
                    quota[i] = stats.quota(sources.get(i).name);
                }
            }
            List<ProxyCandidate> candidates = ProxySources.select(previous, lists, quota, MAX_CANDIDATES, random);
            List<Control> controls = new ArrayList<>(CONTROLS);
            Collections.shuffle(controls, random); // once per search, then in turn as the checks start
            progress(0, candidates.size(), 0, 0);
            controlled = discover(pool, candidates, controls, origins(lists, candidates, previous), discoverBy);
        } finally {
            pool.shutdownNow(); // the control probes still running are not waited for
        }
        if (page == null || controlled.verified.isEmpty()) {
            return controlled;
        }
        long siteBy = Math.min(deadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(siteBudgetMs));
        return new Found(controlled.verified, siteCheck(best(controlled.verified, KEEP), page, siteBy),
                controlled.perList);
    }

    /** One list per source, in the same order; a list that failed or came too late is empty. */
    @NonNull
    private List<List<ProxyCandidate>> fetchAll(@NonNull ExecutorService pool, long deadline) {
        List<Future<List<ProxyCandidate>>> futures = new ArrayList<>();
        for (ProxySources.Source source : sources) {
            futures.add(pool.submit(() -> ProxySources.parse(source, network.fetch(source))));
        }
        List<List<ProxyCandidate>> lists = new ArrayList<>();
        for (Future<List<ProxyCandidate>> future : futures) {
            try {
                lists.add(future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
            } catch (ExecutionException | TimeoutException e) {
                future.cancel(true);
                lists.add(Collections.<ProxyCandidate>emptyList()); // lists.get(i) stays sources.get(i)'s
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return lists;
    }

    /** The list each candidate is credited to, the first that has it; the known proxies are rechecks, not samples. */
    @NonNull
    private Map<ProxyCandidate, String> origins(@NonNull List<List<ProxyCandidate>> lists,
            @NonNull List<ProxyCandidate> candidates, @NonNull List<ProxyCandidate> previous) {
        Set<ProxyCandidate> sampled = new HashSet<>(candidates);
        sampled.removeAll(previous);
        Map<ProxyCandidate, String> origin = new HashMap<>();
        for (int i = 0; i < lists.size(); i++) {
            for (ProxyCandidate candidate : lists.get(i)) {
                if (sampled.contains(candidate)) {
                    origin.putIfAbsent(candidate, sources.get(i).name);
                }
            }
        }
        return origin;
    }

    /**
     * Control-checks the candidates, each once (a failure is final for the search), the servers taken in turn as the
     * checks start, until {@link #ENOUGH} pass, all are checked or {@code deadline}. Each list is credited with its
     * completed checks; one cancelled or never started is not counted.
     */
    @NonNull
    private Found discover(@NonNull ExecutorService pool, @NonNull List<ProxyCandidate> candidates,
            @NonNull List<Control> controls, @NonNull Map<ProxyCandidate, String> origin, long deadline) {
        CompletionService<Check> checks = new ExecutorCompletionService<>(pool);
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger turn = new AtomicInteger();
        for (ProxyCandidate candidate : candidates) {
            checks.submit(() -> {
                if (stop.get() || System.nanoTime() > deadline) {
                    return null;
                }
                Control control = controls.get(Math.floorMod(turn.getAndIncrement(), controls.size()));
                try {
                    long ms = network.control(candidate, control, PROBE_TIMEOUT_MS);
                    return new Check(candidate, candidate.checked(Math.max(ms, 0), clock.getAsLong()), false);
                } catch (IOException | RuntimeException e) {
                    return new Check(candidate, null, false);
                }
            });
        }
        List<ProxyCandidate> working = new ArrayList<>();
        Map<String, int[]> perList = new HashMap<>();
        int done = 0;
        int silent = 0;
        try {
            while (done < candidates.size() && working.size() < ENOUGH) {
                long wait = deadline - System.nanoTime();
                Future<Check> result = wait > 0 ? checks.poll(wait, TimeUnit.NANOSECONDS) : null;
                if (result == null) {
                    break;
                }
                done++;
                Check check = finished(result);
                if (check == null) {
                    continue;
                }
                String list = origin.get(check.candidate);
                if (list != null) {
                    int[] tally = perList.computeIfAbsent(list, name -> new int[2]);
                    tally[0] += check.ok != null ? 1 : 0;
                    tally[1]++;
                }
                if (check.ok != null) {
                    working.add(check.ok);
                } else {
                    silent++;
                }
                progress(working.size() + silent, candidates.size(), working.size(), silent);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            stop.set(true);
        }
        return new Found(working, Collections.<ProxyCandidate>emptyList(), perList);
    }

    /**
     * Asks the site's home page {@code page} through each of {@code shortlist} on a pool of its own, until
     * {@link #SITE_READY} passed, all answered or {@code deadline}. Returns the copies that passed, with the site's
     * latency; a refusal or a timeout leaves a proxy verified, behind them.
     */
    @NonNull
    private List<ProxyCandidate> siteCheck(@NonNull List<ProxyCandidate> shortlist, @NonNull String page,
            long deadline) {
        siteProgress(page, 0, 0);
        ExecutorService pool = Executors.newFixedThreadPool(SITE_THREADS, daemon("proxy-site"));
        CompletionService<Check> checks = new ExecutorCompletionService<>(pool);
        AtomicBoolean stop = new AtomicBoolean();
        for (ProxyCandidate candidate : shortlist) {
            checks.submit(() -> {
                if (stop.get() || System.nanoTime() > deadline) {
                    return null;
                }
                try {
                    long ms = network.site(candidate, page, PROBE_TIMEOUT_MS);
                    return new Check(candidate, candidate.checked(Math.max(ms, 0), clock.getAsLong()), false);
                } catch (SiteRefused e) {
                    return new Check(candidate, null, true);
                } catch (IOException | RuntimeException e) {
                    return new Check(candidate, null, false);
                }
            });
        }
        List<ProxyCandidate> passed = new ArrayList<>();
        int done = 0;
        int refusals = 0;
        try {
            while (done < shortlist.size() && passed.size() < SITE_READY) {
                long wait = deadline - System.nanoTime();
                Future<Check> result = wait > 0 ? checks.poll(wait, TimeUnit.NANOSECONDS) : null;
                if (result == null) {
                    break;
                }
                done++;
                Check check = finished(result);
                if (check != null && check.ok != null) {
                    passed.add(check.ok);
                } else if (check != null && check.refused) {
                    refusals++;
                }
                siteProgress(page, passed.size(), refusals);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            stop.set(true);
            pool.shutdownNow();
        }
        return passed;
    }

    /** The check of a completed future; null when it never started or broke. */
    @Nullable
    private static Check finished(@NonNull Future<Check> result) throws InterruptedException {
        try {
            return result.get();
        } catch (ExecutionException e) {
            return null;
        }
    }

    private void progress(int tested, int total, int found, int unreachable) {
        synchronized (this) {
            this.tested = tested;
            this.total = total;
            this.found = found;
            this.unreachable = unreachable;
        }
        publish();
    }

    private void siteProgress(@NonNull String site, int passed, int refused) {
        synchronized (this) {
            this.site = site;
            this.sitePassed = passed;
            this.refused = refused;
        }
        publish();
    }

    /** Adds one search's control checks to each list's yield memory; lists no longer in {@link #sources} are forgotten. */
    private synchronized void remember(@NonNull Map<String, int[]> perList) {
        for (Map.Entry<String, int[]> entry : perList.entrySet()) {
            stats.add(entry.getKey(), entry.getValue()[0], entry.getValue()[1]);
        }
        Set<String> names = new HashSet<>();
        for (ProxySources.Source source : sources) {
            names.add(source.name);
        }
        stats.counts.keySet().retainAll(names);
        prefs.edit().putString(ProxySources.ListStats.PREF, stats.toJson()).apply();
    }

    /**
     * A search's list replaces the one in use. An empty one keeps it, when there is one ({@link Phase#KEPT}): a search
     * that verifies nothing (the network blocks the proxies or the control servers) disproves nothing found before.
     */
    private synchronized void adopt(@NonNull List<ProxyCandidate> ranked) {
        lastScanAt = clock.getAsLong();
        if (ranked.isEmpty() && current != null) {
            phase = Phase.KEPT;
        } else {
            verified = Collections.unmodifiableList(new ArrayList<>(ranked));
            index = 0;
            updateCurrent();
            phase = current != null ? Phase.READY : Phase.FAILED;
        }
        save();
    }

    /** A site check passes on 200-399 only: a page, a partial page or a redirect of the site itself. */
    static boolean usable(int code) {
        return code >= 200 && code < 400;
    }

    /**
     * Pure: the adopted order, at most {@code keep}: the proxies that passed the site ({@code passed}, by its latency)
     * first, then the rest of {@code verified} (by the control latency); {@link #best}'s /24 rule within each tier.
     */
    @NonNull
    static List<ProxyCandidate> rank(@NonNull List<ProxyCandidate> passed, @NonNull List<ProxyCandidate> verified,
            int keep) {
        List<ProxyCandidate> out = best(passed, keep);
        List<ProxyCandidate> rest = new ArrayList<>(verified);
        rest.removeAll(passed);
        out.addAll(best(rest, keep - out.size()));
        return out;
    }

    /**
     * The {@code keep} fastest of {@code working}: the fastest of each /24 first, then the rest by latency, so the proxy
     * in use and its spares ({@link #SPARES_IN_ROUTE}) are not nodes of one farm while other subnets work.
     */
    @NonNull
    static List<ProxyCandidate> best(@NonNull List<ProxyCandidate> working, int keep) {
        List<ProxyCandidate> sorted = new ArrayList<>(working);
        Collections.sort(sorted, Comparator.comparingLong(ProxyCandidate::latencyMs));
        List<ProxyCandidate> out = new ArrayList<>();
        Set<String> subnets = new HashSet<>();
        for (ProxyCandidate candidate : sorted) {
            if (out.size() < keep && subnets.add(ProxySources.prefix(candidate.host(), 3))) {
                out.add(candidate);
            }
        }
        for (ProxyCandidate candidate : sorted) {
            if (out.size() < keep && !out.contains(candidate)) {
                out.add(candidate);
            }
        }
        return out;
    }

    // guarded by this
    private void updateCurrent() {
        ProxyCandidate before = current;
        ProxyCandidate candidate = index >= 0 && index < verified.size() ? verified.get(index) : null;
        List<Proxy> proxies = new ArrayList<>();
        if (candidate != null) {
            for (int i = index; i < verified.size() && i <= index + SPARES_IN_ROUTE; i++) {
                proxies.add(verified.get(i).toProxy());
            }
        }
        current = candidate;
        currentProxy = candidate != null ? proxies.get(0) : null;
        route = Collections.unmodifiableList(proxies);
        if (candidate != null) {
            exhaustedAt = 0; // a proxy is in use again: the run-out is over
        }
        if (before == null ? candidate != null : !before.equals(candidate)) {
            try {
                switched.run();
            } catch (RuntimeException e) {
                // dropping pooled connections is best effort
            }
        }
    }

    // guarded by this
    private void save() {
        prefs.edit().putString(PREF_LIST, Saved.toJson(verified, index, lastScanAt, exhaustedAt)).apply();
    }

    private void publish() {
        if (listeners.isEmpty()) {
            return;
        }
        State state = state();
        main.execute(() -> {
            for (Listener listener : listeners) {
                listener.onProxyState(state);
            }
        });
    }

    @Nullable
    private static String readSaved(@NonNull SharedPreferences prefs, @NonNull String key) {
        try {
            return prefs.getString(key, null);
        } catch (ClassCastException e) {
            return null;
        }
    }

    /**
     * The home page that ranks the proxies of a search and judges a site's refusal: the catalog source selected in the
     * settings unless it goes direct ({@link AppProxy#PREF_DIRECT}), else the first active source that takes the proxy;
     * null when none does.
     */
    @Nullable
    static String targetOf(@NonNull SharedPreferences prefs) {
        String selected;
        try {
            selected = prefs.getString("sorce_books", null);
        } catch (RuntimeException e) {
            selected = null; // a wrongly typed value: the default site
        }
        return targetOf(selected, AppProxy.readDirect(prefs));
    }

    /** {@link #targetOf(String, Set, List)} with the active sources: what {@link AppProxy}'s direct-set listener compares. */
    @Nullable
    static String targetOf(@Nullable String selected, @NonNull Set<String> direct) {
        return targetOf(selected, direct, Sources.active());
    }

    /** Pure: {@link #targetOf(SharedPreferences)} for the selected {@code prefValue}, the direct and the active sources. */
    @Nullable
    static String targetOf(@Nullable String selected, @NonNull Set<String> direct, @NonNull List<Provider> active) {
        if (selected != null && !direct.contains(selected)) {
            for (Provider provider : Provider.values()) {
                if (selected.equals(provider.prefValue)) {
                    return provider.server + "/";
                }
            }
        }
        for (Provider provider : active) {
            if (!direct.contains(provider.prefValue)) {
                return provider.server + "/";
            }
        }
        return null;
    }

    private static ThreadFactory daemon(@NonNull String name) {
        AtomicInteger count = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, name + "-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * The persisted list: the verified proxies, the one in use, when they were found and when the list ran out
     * ({@link State#exhaustedAt}, 0 in a list saved before it was kept) (v1). A v2 list, which also kept the sites its
     * search tried, loads the same; those fields are ignored.
     */
    static final class Saved {
        final List<ProxyCandidate> proxies;
        final int index;
        final long scannedAt;
        final long exhaustedAt;

        Saved(@NonNull List<ProxyCandidate> proxies, int index, long scannedAt, long exhaustedAt) {
            this.proxies = proxies;
            this.index = index;
            this.scannedAt = scannedAt;
            this.exhaustedAt = exhaustedAt;
        }

        @NonNull
        static String toJson(@NonNull List<ProxyCandidate> proxies, int index, long scannedAt, long exhaustedAt) {
            JsonObject root = new JsonObject();
            root.addProperty("v", 1);
            root.addProperty("current", index);
            root.addProperty("scannedAt", scannedAt);
            root.addProperty("exhaustedAt", exhaustedAt);
            JsonArray array = new JsonArray();
            for (ProxyCandidate proxy : proxies) {
                JsonObject item = new JsonObject();
                item.addProperty("host", proxy.host());
                item.addProperty("port", proxy.port());
                item.addProperty("type", proxy.typeKey());
                item.addProperty("ms", proxy.latencyMs());
                item.addProperty("at", proxy.checkedAt());
                array.add(item);
            }
            root.add("proxies", array);
            return root.toString();
        }

        /** Anything unreadable is an empty list: the finder then searches again. */
        @NonNull
        static Saved fromJson(@Nullable String json) {
            List<ProxyCandidate> proxies = new ArrayList<>();
            if (json == null || json.isEmpty()) {
                return new Saved(Collections.unmodifiableList(proxies), 0, 0, 0);
            }
            try {
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                JsonArray array = root.has("proxies") ? root.getAsJsonArray("proxies") : new JsonArray();
                for (JsonElement element : array) {
                    JsonObject item = element.getAsJsonObject();
                    String host = item.get("host").getAsString();
                    int port = item.get("port").getAsInt();
                    Proxy.Type type = ProxyCandidate.typeOf(item.get("type").getAsString());
                    if (type == null || !ProxySources.isPublicIpv4(host) || port < 1 || port > 65535) {
                        continue;
                    }
                    long ms = item.has("ms") ? item.get("ms").getAsLong() : ProxyCandidate.UNCHECKED;
                    long at = item.has("at") ? item.get("at").getAsLong() : 0;
                    proxies.add(new ProxyCandidate(host, port, type, ms, at));
                }
                int index = root.has("current") ? root.get("current").getAsInt() : 0;
                long scannedAt = root.has("scannedAt") ? root.get("scannedAt").getAsLong() : 0;
                long exhaustedAt = root.has("exhaustedAt") ? root.get("exhaustedAt").getAsLong() : 0;
                return new Saved(Collections.unmodifiableList(proxies), Math.max(0, index), scannedAt, exhaustedAt);
            } catch (RuntimeException e) {
                return new Saved(Collections.<ProxyCandidate>emptyList(), 0, 0, 0);
            }
        }
    }

    /**
     * The real network. Lists come directly ({@link Proxy#NO_PROXY}), never through the proxy in use. A check is one
     * request through the candidate without following redirects. A control check is a GET of the control server and
     * passes on that server's own answer only ({@link Control#passes}); asked directly ({@link #reachable}), it tells
     * dead proxies from a dead network. A site check is a GET with a one-byte Range (a POST for a site whose pages are
     * posted, as {@link SiteHttp} sends) and passes on a {@link ProxyFinder#usable} answer. Over HTTPS the TLS
     * handshake with the real server proves the tunnel reached it. No connection outlives its check.
     */
    static final class OkNetwork implements Network {
        /** Built on first use, on the finder's threads: the finder itself is created during app start. */
        private OkHttpClient base;
        private OkHttpClient lists;

        private synchronized OkHttpClient base() {
            if (base == null) {
                base = new OkHttpClient.Builder()
                        .proxy(Proxy.NO_PROXY)
                        .connectionPool(new ConnectionPool(0, 1, TimeUnit.SECONDS))
                        .retryOnConnectionFailure(false)
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .build();
            }
            return base;
        }

        private synchronized OkHttpClient lists() {
            if (lists == null) {
                lists = base().newBuilder()
                        .followRedirects(true)
                        .followSslRedirects(true)
                        .connectTimeout(LIST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .readTimeout(LIST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .writeTimeout(LIST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .callTimeout(FETCH_DEADLINE_MS, TimeUnit.MILLISECONDS)
                        .build();
            }
            return lists;
        }

        /**
         * A client through {@code proxy} alone ({@link Proxy#NO_PROXY}: directly): {@code timeoutMs} per connect, read
         * and write, twice that per call.
         */
        @NonNull
        private OkHttpClient through(@NonNull Proxy proxy, int timeoutMs) {
            return base().newBuilder()
                    .proxy(proxy)
                    .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .callTimeout(timeoutMs * 2L, TimeUnit.MILLISECONDS)
                    .build();
        }

        @NonNull
        @Override
        public String fetch(@NonNull ProxySources.Source source) throws IOException {
            Request request = new Request.Builder().url(source.url).header("User-Agent", Consts.USER_AGENT).build();
            try (Response response = lists().newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("HTTP " + response.code() + " from " + source.name);
                }
                return readCapped(response.body().byteStream(), MAX_LIST_BYTES);
            }
        }

        @Override
        public long control(@NonNull ProxyCandidate candidate, @NonNull Control control, int timeoutMs)
                throws IOException {
            return ask(through(candidate.toProxy(), timeoutMs), control, " through " + candidate);
        }

        @Override
        public void reachable(@NonNull Control control, int timeoutMs) throws IOException {
            ask(through(Proxy.NO_PROXY, timeoutMs), control, " directly");
        }

        /** One GET of {@code control} by {@code client}: the milliseconds it took; throws unless it passes. */
        private static long ask(@NonNull OkHttpClient client, @NonNull Control control, @NonNull String route)
                throws IOException {
            Request request = new Request.Builder().url(control.url).header("User-Agent", Consts.USER_AGENT).build();
            long start = System.nanoTime();
            try (Response response = client.newCall(request).execute()) {
                String body = control.marker == null ? "" : readCapped(response.body().byteStream(), MARKER_BYTES);
                if (!control.passes(response.code(), body)) {
                    throw new IOException("HTTP " + response.code() + " from " + control + route);
                }
                return Math.max(1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            }
        }

        @Override
        public long site(@NonNull ProxyCandidate candidate, @NonNull String page, int timeoutMs) throws IOException {
            Request.Builder builder = new Request.Builder().url(page);
            // the site is judged by the request the app will make: the same browser a page request of SiteHttp
            // claims to be, headers and all (a User-Agent alone is what a bot wears)
            for (Map.Entry<String, String> header : BrowserHeaders.page(Consts.USER_AGENT).entrySet()) {
                builder.header(header.getKey(), header.getValue());
            }
            Provider provider = Provider.ofUrl(page);
            if (provider != null && provider.parser.postsPages() && Url.isHost(page, provider.server)) {
                // like SiteHttp: the site's wall answers a GET with a challenge, a POST passes
                builder.post(new FormBody.Builder().build());
            } else {
                builder.header("Range", "bytes=0-0");
            }
            long start = System.nanoTime();
            try (Response response = through(candidate.toProxy(), timeoutMs).newCall(builder.build()).execute()) {
                if (!usable(response.code())) {
                    throw new SiteRefused(response.code(), candidate);
                }
                return Math.max(1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            }
        }

        private static String readCapped(@NonNull InputStream in, int max) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while (out.size() < max && (read = in.read(buffer, 0, Math.min(buffer.length, max - out.size()))) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
