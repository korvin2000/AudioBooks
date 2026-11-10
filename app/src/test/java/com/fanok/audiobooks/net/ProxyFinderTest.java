package com.fanok.audiobooks.net;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import com.fanok.audiobooks.Url;
import com.fanok.audiobooks.search.Provider;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.HttpUrl;

public class ProxyFinderTest {

    private static final String TARGET = "https://akniga.org/";
    private static final String UKNIG = Provider.UKNIG.server + "/";
    private static final ProxySources.Source HTTP_LIST =
            new ProxySources.Source("http", "https://lists.example/http.txt", ProxySources.Format.IPPORT, Proxy.Type.HTTP);
    private static final ProxySources.Source MIXED_LIST =
            new ProxySources.Source("mixed", "https://lists.example/mixed.txt", ProxySources.Format.PROTOIPPORT, null);

    /** Scripted answers of the fake network. */
    private interface Script {
        /** The milliseconds of the check of {@code label} on {@code url} (attempt 1 the first), a throw, or null for the defaults. */
        Long answer(String label, String url, int attempt) throws IOException;
    }

    /** Lists by source name; a proxy answers control servers and sites when its label has a latency here. */
    private static final class FakeNetwork implements ProxyFinder.Network {
        final Map<String, String> lists = new ConcurrentHashMap<>();
        final Map<String, Long> latency = new ConcurrentHashMap<>();
        final AtomicInteger fetches = new AtomicInteger();
        final AtomicInteger controls = new AtomicInteger();
        final AtomicInteger sites = new AtomicInteger();
        /** "label url" of every check, in the order the checks started. */
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        /** Checks so far by "label url". */
        final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
        /** Checks still to fail, by proxy label (a transient failure). */
        final Map<String, Integer> failNext = new ConcurrentHashMap<>();
        /** "label page": the site answers this proxy with a 403. */
        final Set<String> refused = ConcurrentHashMap.newKeySet();
        /** Control servers and sites that answer no proxy. */
        final Set<String> down = ConcurrentHashMap.newKeySet();
        /** The control servers answer the device directly; false: its network is down. */
        volatile boolean direct = true;
        /** Direct checks so far (not in {@link #calls}). */
        final AtomicInteger directs = new AtomicInteger();
        volatile Script script = (label, url, attempt) -> null;
        volatile Runnable onFetch = () -> { };
        volatile long delayMs;

        @NonNull
        @Override
        public String fetch(@NonNull ProxySources.Source source) throws IOException {
            fetches.incrementAndGet();
            onFetch.run();
            String body = lists.get(source.name);
            if (body == null) {
                throw new IOException("unreachable");
            }
            return body;
        }

        @Override
        public long control(@NonNull ProxyCandidate candidate, @NonNull ProxyFinder.Control control, int timeoutMs)
                throws IOException {
            controls.incrementAndGet();
            return answer(candidate, control.url, false);
        }

        @Override
        public long site(@NonNull ProxyCandidate candidate, @NonNull String page, int timeoutMs) throws IOException {
            sites.incrementAndGet();
            return answer(candidate, page, true);
        }

        @Override
        public void reachable(@NonNull ProxyFinder.Control control, int timeoutMs) throws IOException {
            directs.incrementAndGet();
            if (!direct) {
                throw new IOException("no network");
            }
        }

        private long answer(ProxyCandidate candidate, String url, boolean site) throws IOException {
            String label = candidate.label();
            String key = label + " " + url;
            calls.add(key);
            int attempt = attempts.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
            if (delayMs > 0) {
                sleep(delayMs);
            }
            Long scripted = script.answer(label, url, attempt);
            if (scripted != null) {
                return scripted;
            }
            Long ms = latency.get(label);
            if (ms == null || down.contains(url)) {
                throw new IOException("dead");
            }
            Integer failures = failNext.get(label);
            if (failures != null && failures > 0) {
                failNext.put(label, failures - 1);
                throw new IOException("transient");
            }
            if (site && refused.contains(key)) {
                throw new ProxyFinder.SiteRefused(403, candidate);
            }
            return ms;
        }
    }

    private static void sleep(long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted");
        }
    }

    /** A check that hangs until the finder gives up on it (its pool is shut down). */
    private static Long stall() throws IOException {
        sleep(60_000);
        throw new IOException("timed out");
    }

    private static Long fail() throws IOException {
        throw new IOException("scripted failure");
    }

    private final FakePrefs prefs = new FakePrefs();
    private final FakeNetwork network = new FakeNetwork();
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final AtomicBoolean online = new AtomicBoolean(true);
    private final List<ProxyFinder.State> heard = new ArrayList<>();
    private final AtomicInteger switches = new AtomicInteger();

    private final AtomicReference<String> target = new AtomicReference<>(TARGET);

    private ProxyFinder finder() {
        return finder(Runnable::run);
    }

    private ProxyFinder finder(Executor worker) {
        return finder(worker, 4);
    }

    private ProxyFinder finder(Executor worker, int probeThreads) {
        ProxyFinder finder = new ProxyFinder(prefs, network, Runnable::run, worker, online::get, target::get,
                now::get, new Random(3), Arrays.asList(HTTP_LIST, MIXED_LIST), probeThreads, switches::incrementAndGet);
        finder.retryDelayMs = 0;
        finder.addListener(heard::add);
        return finder;
    }

    private ProxyFinder.Saved saved() {
        return ProxyFinder.Saved.fromJson(prefs.getString(ProxyFinder.PREF_LIST, null));
    }

    /** Runs the queued worker tasks, including those they queue. */
    private static void drain(List<Runnable> queue) {
        while (!queue.isEmpty()) {
            queue.remove(0).run();
        }
    }

    private void lists(String http, String mixed) {
        network.lists.put(HTTP_LIST.name, http);
        network.lists.put(MIXED_LIST.name, mixed);
    }

    /** {@code count} working HTTP proxies "40.i.1.1:80" in the http list, each in its own /16, latency 100 + i. */
    private List<String> working(int count) {
        List<String> labels = new ArrayList<>();
        StringBuilder list = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            String label = "40." + i + ".1.1:80";
            labels.add(label);
            list.append(label).append('\n');
            network.latency.put(label, 100L + i);
        }
        lists(list.toString(), "");
        return labels;
    }

    private static boolean isControl(String url) {
        for (ProxyFinder.Control control : ProxyFinder.CONTROLS) {
            if (control.url.equals(url)) {
                return true;
            }
        }
        return false;
    }

    /** The control servers asked so far, in order; of {@code label} only unless null. */
    private List<String> controlsAsked(String label) {
        List<String> urls = new ArrayList<>();
        synchronized (network.calls) {
            for (String call : network.calls) {
                String[] parts = call.split(" ", 2);
                if (isControl(parts[1]) && (label == null || label.equals(parts[0]))) {
                    urls.add(parts[1]);
                }
            }
        }
        return urls;
    }

    @Test
    public void aScanKeepsTheWorkingProxiesFastestFirst() {
        lists("1.1.1.1:80\n2.2.2.2:80\n3.3.3.3:80\n", "socks5://4.4.4.4:1080\nhttp://5.5.5.5:8080\nsocks4://6.6.6.6:1080\n");
        network.latency.put("2.2.2.2:80", 900L);
        network.latency.put("4.4.4.4:1080", 120L);
        network.latency.put("5.5.5.5:8080", 400L);
        ProxyFinder finder = finder();

        finder.rescan();

        ProxyFinder.State state = finder.state();
        assertEquals(ProxyFinder.Phase.READY, state.phase);
        assertEquals(5, state.total);
        assertEquals(5, state.tested);
        assertEquals(3, state.found);
        assertEquals(2, state.unreachable);
        assertEquals(now.get(), state.lastScanAt);
        assertNotNull(state.current);
        assertEquals("4.4.4.4:1080", state.current.label());
        assertSame(Proxy.Type.SOCKS, state.current.type());
        assertEquals(120L, state.current.latencyMs());
        assertEquals(now.get(), state.current.checkedAt());
        assertEquals(state.current.toProxy(), finder.currentProxy());
        assertEquals("one control check per candidate", 5, network.controls.get());
        assertEquals("then the verified ones ask the site", 3, network.sites.get());
        assertEquals(TARGET, state.site);
        assertEquals(3, state.sitePassed);
        assertEquals(ProxyFinder.Phase.SCANNING, heard.get(0).phase);
        assertEquals(ProxyFinder.Phase.READY, heard.get(heard.size() - 1).phase);

        ProxyFinder.Saved saved = saved();
        assertEquals(Arrays.asList("4.4.4.4:1080", "5.5.5.5:8080", "2.2.2.2:80"), labels(saved.proxies));
        assertEquals(0, saved.index);
    }

    @Test
    public void aScanStopsOnceEnoughProxiesWork() {
        StringBuilder list = new StringBuilder();
        for (int i = 1; i <= 40; i++) {
            // each in its own /16: the subnet caps take them all
            list.append("7.").append(i).append(".7.1:80\n");
            network.latency.put("7." + i + ".7.1:80", (long) i);
        }
        lists(list.toString(), "");
        network.delayMs = 50;
        ProxyFinder finder = finder();

        finder.rescan();

        assertEquals(ProxyFinder.ENOUGH, finder.state().found);
        assertEquals(ProxyFinder.ENOUGH, saved().proxies.size());
        assertTrue("checks stop early", network.controls.get() < 40);
    }

    @Test
    public void bestKeepsTheFastestEight() {
        List<ProxyCandidate> working = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            working.add(new ProxyCandidate("8.8.1." + i, 80, Proxy.Type.HTTP).checked(1000 - i * 10L, 1));
        }
        List<ProxyCandidate> best = ProxyFinder.best(working, ProxyFinder.KEEP);
        assertEquals(8, best.size());
        assertEquals("8.8.1.12:80", best.get(0).label());
        assertEquals("8.8.1.5:80", best.get(7).label());
        for (int i = 1; i < best.size(); i++) {
            assertTrue(best.get(i - 1).latencyMs() <= best.get(i).latencyMs());
        }
        assertTrue(ProxyFinder.best(Collections.emptyList(), 8).isEmpty());
    }

    @Test
    public void bestPrefersSparesFromOtherSubnets() {
        List<ProxyCandidate> working = Arrays.asList(
                new ProxyCandidate("45.74.31.1", 1080, Proxy.Type.SOCKS).checked(100, 1),
                new ProxyCandidate("45.74.31.2", 1080, Proxy.Type.SOCKS).checked(110, 1),
                new ProxyCandidate("45.74.31.3", 1080, Proxy.Type.SOCKS).checked(120, 1),
                new ProxyCandidate("8.8.8.8", 80, Proxy.Type.HTTP).checked(300, 1),
                new ProxyCandidate("9.9.9.9", 80, Proxy.Type.HTTP).checked(400, 1));
        assertEquals("the route is not three nodes of one farm",
                Arrays.asList("45.74.31.1:1080", "8.8.8.8:80", "9.9.9.9:80"), labels(ProxyFinder.best(working, 3)));
        assertEquals("then the rest by latency",
                Arrays.asList("45.74.31.1:1080", "8.8.8.8:80", "9.9.9.9:80", "45.74.31.2:1080", "45.74.31.3:1080"),
                labels(ProxyFinder.best(working, ProxyFinder.KEEP)));
    }

    private static ProxyCandidate checked(String host, long ms) {
        return new ProxyCandidate(host, 80, Proxy.Type.HTTP).checked(ms, 1);
    }

    @Test
    public void theSitesTierComesFirstAndEachTierSpreadsOverSubnets() {
        // control latencies: a farm in 45.74.31.x and three single hosts
        List<ProxyCandidate> verified = Arrays.asList(checked("45.74.31.1", 100), checked("45.74.31.2", 110),
                checked("45.74.31.3", 120), checked("45.74.31.4", 60), checked("8.8.8.8", 300),
                checked("9.9.9.9", 400), checked("7.7.7.7", 50));
        // the site served two farm nodes and 9.9.9.9, in its own latencies
        List<ProxyCandidate> passed = Arrays.asList(checked("45.74.31.2", 200), checked("45.74.31.3", 150),
                checked("9.9.9.9", 500));
        assertEquals("the site's tier by its latency, the fastest of each /24 first; then the others by the control's",
                Arrays.asList("45.74.31.3:80", "9.9.9.9:80", "45.74.31.2:80",
                        "7.7.7.7:80", "45.74.31.4:80", "8.8.8.8:80", "45.74.31.1:80"),
                labels(ProxyFinder.rank(passed, verified, ProxyFinder.KEEP)));
        assertEquals("the latency kept is the site's", 150L,
                ProxyFinder.rank(passed, verified, ProxyFinder.KEEP).get(0).latencyMs());
        assertEquals("the cut takes from the control tier",
                Arrays.asList("45.74.31.3:80", "9.9.9.9:80", "45.74.31.2:80", "7.7.7.7:80"),
                labels(ProxyFinder.rank(passed, verified, 4)));
        assertEquals("no site tier: the control order", labels(ProxyFinder.best(verified, ProxyFinder.KEEP)),
                labels(ProxyFinder.rank(Collections.<ProxyCandidate>emptyList(), verified, ProxyFinder.KEEP)));
    }

    @Test
    public void theProxiesTheSiteServesComeFirst() {
        lists("1.1.1.1:80\n2.2.2.2:80\n3.3.3.3:80\n", "");
        network.latency.put("1.1.1.1:80", 100L);
        network.latency.put("2.2.2.2:80", 200L);
        network.latency.put("3.3.3.3:80", 300L);
        network.refused.add("1.1.1.1:80 " + TARGET);
        network.script = (label, url, attempt) -> url.equals(TARGET) && label.equals("3.3.3.3:80") ? 50L : null;
        ProxyFinder finder = finder();

        finder.rescan();

        assertEquals("the fastest by the control is refused by the site: it is the last",
                Arrays.asList("3.3.3.3:80", "2.2.2.2:80", "1.1.1.1:80"), labels(saved().proxies));
        ProxyFinder.State state = finder.state();
        assertEquals(3, state.found);
        assertEquals(2, state.sitePassed);
        assertEquals(1, state.refused);
        assertEquals(50L, state.current.latencyMs());
    }

    @Test
    public void aSiteThatRefusesOrStallsRemovesNoProxy() {
        List<String> labels = working(4);
        network.refused.add(labels.get(0) + " " + TARGET);
        network.refused.add(labels.get(1) + " " + TARGET);
        network.script = (label, url, attempt) -> url.equals(TARGET) && !label.equals(labels.get(0))
                && !label.equals(labels.get(1)) ? stall() : null;
        ProxyFinder finder = finder();
        finder.siteBudgetMs = 300;

        finder.rescan();

        ProxyFinder.State state = finder.state();
        assertEquals(ProxyFinder.Phase.READY, state.phase);
        assertEquals(4, state.found);
        assertEquals(0, state.sitePassed);
        assertEquals(2, state.refused);
        assertEquals("every control-verified proxy stays, by its control latency", labels, labels(saved().proxies));
    }

    @Test
    public void aSiteThatIsDownStillLeavesAList() {
        List<String> labels = working(3);
        network.down.add(TARGET);
        ProxyFinder finder = finder();

        finder.rescan();

        assertEquals(ProxyFinder.Phase.READY, finder.state().phase);
        assertEquals(labels, labels(saved().proxies));
        assertEquals(0, finder.state().sitePassed);
        assertEquals("a site that does not answer refuses nothing", 0, finder.state().refused);
        assertEquals(3, network.sites.get());
    }

    @Test
    public void theSiteIsAskedByEightAtMostAndNoMoreOnceThreePassed() {
        List<String> labels = working(ProxyFinder.ENOUGH);
        // the three fastest pass, the rest would hang until the budget ran out
        Set<String> passing = new HashSet<>(labels.subList(0, 3));
        network.script = (label, url, attempt) -> url.equals(TARGET) && !passing.contains(label) ? stall() : null;
        ProxyFinder finder = finder(Runnable::run, 24);
        finder.siteBudgetMs = 20_000;

        long start = System.nanoTime();
        finder.rescan();
        long ms = (System.nanoTime() - start) / 1_000_000;

        assertTrue("the site check ends once three passed: " + ms + " ms", ms < 10_000);
        assertTrue(String.valueOf(network.sites.get()), network.sites.get() <= ProxyFinder.KEEP);
        assertEquals(ProxyFinder.SITE_READY, finder.state().sitePassed);
        assertEquals(passing, new HashSet<>(labels(saved().proxies).subList(0, 3)));
        assertEquals(ProxyFinder.ENOUGH, saved().proxies.size());
    }

    @Test
    public void theDeadlineKeepsTheControlSuccessesCollected() {
        StringBuilder list = new StringBuilder();
        Set<String> fast = new HashSet<>();
        for (int i = 1; i <= 10; i++) {
            String label = "41." + i + ".1.1:80";
            list.append(label).append('\n');
            network.latency.put(label, 100L + i);
            if (i <= 3) {
                fast.add(label);
            }
        }
        lists(list.toString(), "");
        // the others never answer a control server; no proxy brings the site
        network.script = (label, url, attempt) -> url.equals(TARGET) || !fast.contains(label) ? stall() : null;
        ProxyFinder finder = finder(Runnable::run, 16);
        finder.scanDeadlineMs = 1_500;
        finder.siteBudgetMs = 500;

        finder.rescan();

        ProxyFinder.State state = finder.state();
        assertEquals(ProxyFinder.Phase.READY, state.phase);
        assertEquals(3, state.found);
        assertEquals("the unfinished checks are not counted", 3, state.tested);
        assertEquals(fast, new HashSet<>(labels(saved().proxies)));
        assertEquals(0, state.sitePassed);
    }

    @Test
    public void withoutASiteThatTakesTheProxyNoSiteRanksThem() {
        List<String> labels = working(3);
        target.set(null);
        ProxyFinder finder = finder();

        finder.rescan();

        assertEquals(0, network.sites.get());
        assertNull(finder.state().site);
        assertEquals(ProxyFinder.Phase.READY, finder.state().phase);
        assertEquals(labels, labels(saved().proxies));

        // nor is a refusal or another source compared on any site
        finder.recheckSite();
        finder.reportSuspect("cdn.example");
        assertEquals(0, network.sites.get());
    }

    @Test
    public void theControlServersTakeTurnsAndOneDownStopsNothing() {
        working(12);
        String down = ProxyFinder.CONTROLS.get(0).url;
        network.down.add(down);
        ProxyFinder finder = finder(Runnable::run, 1);

        finder.rescan();

        List<String> asked = controlsAsked(null);
        assertEquals("the first four are all four servers", new HashSet<>(asked.subList(0, 4)).size(),
                ProxyFinder.CONTROLS.size());
        for (int i = ProxyFinder.CONTROLS.size(); i < asked.size(); i++) {
            assertEquals("in turn: " + asked, asked.get(i - ProxyFinder.CONTROLS.size()), asked.get(i));
        }
        assertEquals(ProxyFinder.ENOUGH, finder.state().found);
        int failed = Collections.frequency(asked, down);
        assertTrue("the search went on past the server that is down", failed >= 2);
        assertEquals(failed, finder.state().unreachable);
    }

    @Test
    public void aSearchThatFindsNothingKeepsTheListInUse() {
        lists("1.1.1.1:80\n2.2.2.2:80\n", "");
        network.latency.put("1.1.1.1:80", 100L);
        network.latency.put("2.2.2.2:80", 200L);
        ProxyFinder finder = finder();
        finder.rescan();
        int switched = switches.get();

        now.addAndGet(60_000L);
        network.latency.clear();
        finder.rescan();

        ProxyFinder.State state = finder.state();
        assertEquals(ProxyFinder.Phase.KEPT, state.phase);
        assertEquals("1.1.1.1:80", state.current.label());
        assertEquals(1, state.spares);
        assertEquals(0, state.found);
        assertEquals(2, state.unreachable);
        assertEquals(now.get(), state.lastScanAt);
        assertEquals(Arrays.asList("1.1.1.1:80", "2.2.2.2:80"), labels(saved().proxies));
        assertEquals(now.get(), saved().scannedAt);
        assertEquals(switched, switches.get());
        assertEquals(ProxyFinder.Phase.KEPT, heard.get(heard.size() - 1).phase);

        // leaving the proxy in use keeps the mark: the list is still the old one
        network.latency.put("2.2.2.2:80", 200L);
        finder.reportFailure(finder.currentProxy().address(), true);
        assertEquals("2.2.2.2:80", finder.state().current.label());
        assertEquals(ProxyFinder.Phase.KEPT, finder.state().phase);
    }

    @Test
    public void aConfirmedFailureMovesToTheNextProxy() {
        lists("1.1.1.1:80\n2.2.2.2:80\n", "");
        network.latency.put("1.1.1.1:80", 100L);
        network.latency.put("2.2.2.2:80", 200L);
        ProxyFinder finder = finder();
        finder.rescan();
        int scans = network.fetches.get();
        InetSocketAddress first = (InetSocketAddress) finder.currentProxy().address();

        network.latency.remove("1.1.1.1:80");
        int checks = network.calls.size();
        finder.reportFailure(first);
        assertEquals("two control checks, then the spare's", checks + 3, network.calls.size());
        finder.reportFailure(first);
        finder.reportFailure(InetSocketAddress.createUnresolved("9.9.9.9", 80));
        assertEquals("a proxy no longer in use is not checked", checks + 3, network.calls.size());

        assertEquals("2.2.2.2:80", finder.state().current.label());
        assertEquals(ProxyFinder.Phase.READY, finder.state().phase);
        assertEquals(1, saved().index);

        // the spare has just passed a control check: a hint about it waits out the quiet
        network.latency.remove("2.2.2.2:80");
        finder.reportFailure(finder.currentProxy().address());
        assertEquals("2.2.2.2:80", finder.state().current.label());

        // without a spare, and the control server does not answer directly either: the last one stays (the network
        // may be at fault); the last search is less than ten minutes old: no automatic search yet
        network.direct = false;
        now.addAndGet(ProxyFinder.CONFIRM_QUIET_MS);
        finder.reportFailure(finder.currentProxy().address());
        assertEquals("2.2.2.2:80", finder.state().current.label());
        assertEquals(1, network.directs.get());
        assertEquals(scans, network.fetches.get());

        // once a search is due, the next failure starts one; it finds nothing, so the list stays
        now.addAndGet(ProxyFinder.AUTO_SCAN_INTERVAL_MS);
        finder.reportFailure(finder.currentProxy().address());
        assertTrue(network.fetches.get() > scans);
        assertEquals("2.2.2.2:80", finder.state().current.label());
        assertEquals(ProxyFinder.Phase.KEPT, finder.state().phase);
    }

    @Test
    public void deadLeadersDoNotHideALiveLaterProxy() {
        List<String> labels = working(4);
        target.set(null); // no site ranks them: the list goes by their latency
        ProxyFinder finder = finder();
        finder.rescan();
        assertEquals(labels.subList(0, 3), routeLabels(finder));

        // the proxy in use and the next two died; the fourth, beyond the route of a request, still works
        for (String dead : labels.subList(0, 3)) {
            network.latency.remove(dead);
        }
        network.calls.clear();
        finder.reportFailure(finder.currentProxy().address(), true);

        List<String> checked = new ArrayList<>();
        for (String call : network.calls) {
            checked.add(call.split(" ", 2)[0]);
        }
        assertEquals("twice the proxy in use, then each spare in turn",
                Arrays.asList(labels.get(0), labels.get(0), labels.get(1), labels.get(2), labels.get(3)), checked);
        assertEquals(labels.get(3), finder.state().current.label());
        assertEquals(ProxyFinder.Phase.READY, finder.state().phase);
        assertEquals(3, saved().index);
        assertEquals("a proxy answered: the network works, nothing is asked directly", 0, network.directs.get());
    }

    @Test
    public void theListRunsOutWhenNoProxyAnswersButTheControlServerDoes() {
        lists("1.1.1.1:80\n2.2.2.2:80\n", "");
        network.latency.put("1.1.1.1:80", 100L);
        network.latency.put("2.2.2.2:80", 200L);
        ProxyFinder finder = finder();
        finder.rescan();
        int fetches = network.fetches.get();
        int switched = switches.get();
        assertEquals(0, finder.state().exhaustedAt);

        // both died; the control server answers the device itself: the proxies, not the network
        network.latency.clear();
        finder.reportFailure(finder.currentProxy().address(), true);

        ProxyFinder.State state = finder.state();
        assertEquals(ProxyFinder.Phase.FAILED, state.phase);
        assertNull(state.current);
        assertNull(finder.currentProxy());
        assertEquals(now.get(), state.exhaustedAt);
        assertEquals(1, network.directs.get());
        assertEquals("the index past the end", 2, saved().index);
        assertEquals("the connections through the last one are dropped", switched + 1, switches.get());
        assertEquals(ProxyFinder.Phase.FAILED, heard.get(heard.size() - 1).phase);
        assertEquals("the last search is less than ten minutes old", fetches, network.fetches.get());
        assertTrue("a restart keeps the run-out", finder().state().exhaustedAt > 0);

        // the automatic search, once due, finds nothing: the same run-out
        now.addAndGet(ProxyFinder.AUTO_SCAN_INTERVAL_MS);
        assertTrue(finder.proxiesForRequest().isEmpty());
        assertTrue(network.fetches.get() > fetches);
        assertEquals(ProxyFinder.Phase.FAILED, finder.state().phase);
        assertEquals(state.exhaustedAt, finder.state().exhaustedAt);

        // one that finds a proxy ends it
        network.latency.put("2.2.2.2:80", 200L);
        finder.rescan();
        assertEquals("2.2.2.2:80", finder.state().current.label());
        assertEquals(0, finder.state().exhaustedAt);
    }

    @Test
    public void aRestartKeepsTheRunOutAfterAFailedSearch() {
        lists("1.1.1.1:80\n", "");
        network.latency.put("1.1.1.1:80", 100L);
        ProxyFinder finder = finder();
        finder.rescan();
        network.latency.clear();
        finder.reportFailure(finder.currentProxy().address(), true);
        long exhaustedAt = finder.state().exhaustedAt;
        assertTrue(exhaustedAt > 0);

        // within the search's cooldown: the run-out, not an idle finder, so «Найти заново» is offered
        now.addAndGet(1);
        ProxyFinder.State restarted = finder().state();
        assertEquals(ProxyFinder.Phase.FAILED, restarted.phase);
        assertEquals(exhaustedAt, restarted.exhaustedAt);

        // the next search finds nothing and saves an empty list: the run-out stays
        now.addAndGet(ProxyFinder.AUTO_SCAN_INTERVAL_MS);
        assertTrue(finder.proxiesForRequest().isEmpty());
        assertTrue(saved().proxies.isEmpty());
        restarted = finder().state();
        assertEquals(ProxyFinder.Phase.FAILED, restarted.phase);
        assertEquals(exhaustedAt, restarted.exhaustedAt);
    }

    @Test
    public void aSiteRecheckReadsTheSiteWhenItRuns() {
        lists("1.1.1.1:80\n2.2.2.2:80\n", "");
        network.latency.put("1.1.1.1:80", 100L);
        network.latency.put("2.2.2.2:80", 200L);
        List<Runnable> queued = new ArrayList<>();
        ProxyFinder finder = finder(queued::add);
        finder.rescan();
        drain(queued);
        assertNotNull(finder.currentProxy());

        // asked for B, then every source went direct before the worker came to it: nothing is compared
        target.set("https://b.example/");
        finder.recheckSite();
        target.set(null);
        network.calls.clear();
        drain(queued);
        assertTrue(network.calls.isEmpty());

        // asked for B, then C chosen before it ran: C is compared
        target.set("https://b.example/");
        finder.recheckSite();
        target.set("https://c.example/");
        drain(queued);
        assertEquals(1, network.calls.size());
        assertTrue(network.calls.get(0).endsWith("https://c.example/"));
    }


    @Test
    public void aFailedControlCheckIsRepeatedThroughAnotherOperator() {
        lists("1.1.1.1:80\n2.2.2.2:80\n", "");
        network.latency.put("1.1.1.1:80", 100L);
        network.latency.put("2.2.2.2:80", 200L);
        ProxyFinder finder = finder();
        finder.rescan();
        network.calls.clear();
        AtomicBoolean failed = new AtomicBoolean();
        network.script = (label, url, attempt) ->
                label.equals("1.1.1.1:80") && isControl(url) && failed.compareAndSet(false, true) ? fail() : null;

        finder.recheckCurrent();

        List<String> asked = controlsAsked("1.1.1.1:80");
        assertEquals(2, asked.size());
        assertNotEquals("another operator's server", asked.get(0), asked.get(1));
        assertEquals("1.1.1.1:80", finder.state().current.label());
        assertTrue("the spare is not needed", controlsAsked("2.2.2.2:80").isEmpty());
    }

    @Test
    public void theProxyInUseIsLeftOnlyWhenTheSparePassesWhereItFailed() {
        lists("1.1.1.1:80\n2.2.2.2:80\n3.3.3.3:80\n", "");
        network.latency.put("1.1.1.1:80", 100L);
        network.latency.put("2.2.2.2:80", 200L);
        network.latency.put("3.3.3.3:80", 300L);
        ProxyFinder finder = finder();
        finder.rescan();
        int switched = switches.get();

        network.calls.clear();
        network.latency.remove("1.1.1.1:80");
        finder.reportFailure(finder.currentProxy().address(), true);
        List<String> asked = controlsAsked("1.1.1.1:80");
        assertEquals(2, asked.size());
        assertEquals("the spare asks the server of the retry", Collections.singletonList(asked.get(1)),
                controlsAsked("2.2.2.2:80"));
        assertEquals("2.2.2.2:80", finder.state().current.label());
        assertEquals(switched + 1, switches.get());
        assertEquals(1, saved().index);

        // the proxy in use and the spare both fail, and nothing answers directly: the network, not the proxy; the list
        // stays
        network.calls.clear();
        network.latency.remove("2.2.2.2:80");
        network.latency.remove("3.3.3.3:80");
        network.direct = false;
        int fetches = network.fetches.get();
        finder.reportFailure(finder.currentProxy().address(), true);
        assertEquals(3, network.calls.size());
        assertEquals(1, network.directs.get());
        assertEquals("2.2.2.2:80", finder.state().current.label());
        assertEquals(1, finder.state().spares);
        assertEquals(1, saved().index);
        assertEquals("the last search is less than ten minutes old", fetches, network.fetches.get());

        // once one is due, such an outage starts a search; finding nothing, it keeps the list
        now.addAndGet(ProxyFinder.AUTO_SCAN_INTERVAL_MS);
        finder.reportFailure(finder.currentProxy().address(), true);
        assertTrue(network.fetches.get() > fetches);
        assertEquals(ProxyFinder.Phase.KEPT, finder.state().phase);
        assertEquals("2.2.2.2:80", finder.state().current.label());
    }

    @Test
    public void failuresWhileOfflineAreNotTheProxys() {
        lists("1.1.1.1:80\n2.2.2.2:80\n", "");
        network.latency.put("1.1.1.1:80", 100L);
        network.latency.put("2.2.2.2:80", 200L);
        ProxyFinder finder = finder();
        finder.rescan();
        network.latency.remove("1.1.1.1:80");
        int checks = network.calls.size();
        online.set(false);
        finder.reportFailure(finder.currentProxy().address(), true);
        finder.reportFailure(finder.currentProxy().address());
        finder.reportSuspect(Url.hostOf(TARGET));
        assertEquals("1.1.1.1:80", finder.state().current.label());
        assertEquals(checks, network.calls.size());
    }

    @Test
    public void aReportQueuesOneConfirmationAndAPassingOneKeepsTheProxy() {
        lists("1.1.1.1:80\n2.2.2.2:80\n", "");
        network.latency.put("1.1.1.1:80", 100L);
        network.latency.put("2.2.2.2:80", 200L);
        List<Runnable> queue = new ArrayList<>();
        ProxyFinder finder = finder(queue::add);
        finder.rescan();
        drain(queue);
        int controls = network.controls.get();
        int sites = network.sites.get();
        InetSocketAddress first = (InetSocketAddress) finder.currentProxy().address();

        // a burst of reports (a cancelled call, the event listener, a refusal) queues one confirmation
        finder.reportFailure(first);
        finder.reportFailure(first, true);
        finder.reportSuspect(Url.hostOf(TARGET));
        assertEquals(1, queue.size());
        assertEquals("nothing moves before it runs", "1.1.1.1:80", finder.state().current.label());

        network.latency.put("1.1.1.1:80", 80L);
        drain(queue);
        assertEquals(controls + 1, network.controls.get());
        assertEquals(sites + 1, network.sites.get());
        assertEquals("the transport first, then the site", "1.1.1.1:80 " + TARGET,
                network.calls.get(network.calls.size() - 1));
        assertEquals("1.1.1.1:80", finder.state().current.label());
        assertEquals("the control check refreshes the latency", 80L, finder.state().current.latencyMs());

        // a proxy (or site) that just passed is not checked again for a while, unless the failure is definite
        finder.reportFailure(first);
        finder.reportSuspect(Url.hostOf(TARGET));
        assertTrue(queue.isEmpty());
        finder.reportFailure(first, true);
        assertEquals(1, queue.size());
        drain(queue);
        now.addAndGet(ProxyFinder.CONFIRM_QUIET_MS);
        finder.reportFailure(first);
        assertEquals(1, queue.size());
        drain(queue);
        assertEquals("1.1.1.1:80", finder.state().current.label());
        assertEquals(controls + 3, network.controls.get());
    }

    @Test
    public void aFailedControlCheckIsRetriedOnceBeforeTheSpareDecides() {
        lists("1.1.1.1:80\n2.2.2.2:80\n", "");
        network.latency.put("1.1.1.1:80", 100L);
        network.latency.put("2.2.2.2:80", 200L);
        ProxyFinder finder = finder();
        finder.rescan();
        int controls = network.controls.get();

        // a transient failure (Wi-Fi associating, a captive portal): the retry passes
        network.failNext.put("1.1.1.1:80", 1);
        finder.recheckCurrent();
        assertEquals(controls + 2, network.controls.get());
        assertEquals("1.1.1.1:80", finder.state().current.label());

        // both fail and the spare passes: the next proxy
        network.failNext.put("1.1.1.1:80", 2);
        now.addAndGet(ProxyFinder.CONFIRM_QUIET_MS);
        finder.reportFailure(finder.currentProxy().address());
        assertEquals(controls + 5, network.controls.get());
        assertEquals("2.2.2.2:80", finder.state().current.label());
    }

    @Test
    public void aSparesReportIsIgnored() {
        lists("1.1.1.1:80\n2.2.2.2:80\n", "");
        network.latency.put("1.1.1.1:80", 100L);
        network.latency.put("2.2.2.2:80", 200L);
        ProxyFinder finder = finder();
        finder.rescan();
        network.latency.remove("1.1.1.1:80");
        int checks = network.calls.size();

        finder.reportFailure(finder.proxiesForRequest().get(1).address(), true);
        finder.reportFailure(finder.proxiesForRequest().get(1).address());

        assertEquals(checks, network.calls.size());
        assertEquals("1.1.1.1:80", finder.state().current.label());
    }

    @Test
    public void aSitesRefusalPutsASpareItServesFirst() {
        lists("1.1.1.1:80\n2.2.2.2:80\n3.3.3.3:80\n", "");
        network.latency.put("1.1.1.1:80", 100L);
        network.latency.put("2.2.2.2:80", 200L);
        network.latency.put("3.3.3.3:80", 300L);
        ProxyFinder finder = finder();
        finder.rescan();
        int switched = switches.get();
        String host = Url.hostOf(Provider.UKNIG.server);
        network.calls.clear();

        finder.reportSuspect("cdn.example");
        assertTrue("a host of no known site", network.calls.isEmpty());

        // the refusing host's own home page judges; the proxy in use passes it: nothing changes
        finder.reportSuspect(host);
        assertEquals(Collections.singletonList("1.1.1.1:80 " + UKNIG), network.calls);
        finder.reportSuspect(host);
        assertEquals("it just passed", 1, network.calls.size());

        // the site refuses the proxy in use and serves the spare: they swap, nothing leaves the list
        network.refused.add("1.1.1.1:80 " + UKNIG);
        now.addAndGet(ProxyFinder.CONFIRM_QUIET_MS);
        finder.reportSuspect(host);
        assertEquals(Arrays.asList("1.1.1.1:80 " + UKNIG, "1.1.1.1:80 " + UKNIG, "2.2.2.2:80 " + UKNIG),
                network.calls);
        assertEquals("2.2.2.2:80", finder.state().current.label());
        assertEquals(2, finder.state().spares);
        assertEquals(Arrays.asList("2.2.2.2:80", "1.1.1.1:80", "3.3.3.3:80"), labels(saved().proxies));
        assertEquals(0, saved().index);
        assertEquals(switched + 1, switches.get());
        assertEquals("2.2.2.2:80", heard.get(heard.size() - 1).current.label());
        assertTrue("no control check: the transport is not in doubt", controlsAsked(null).isEmpty());
    }

    @Test
    public void aSiteThatRefusesTheSpareTooIsLeftAloneForAWhile() {
        lists("1.1.1.1:80\n2.2.2.2:80\n", "");
        network.latency.put("1.1.1.1:80", 100L);
        network.latency.put("2.2.2.2:80", 200L);
        ProxyFinder finder = finder();
        finder.rescan();
        String host = Url.hostOf(Provider.UKNIG.server);
        network.refused.add("1.1.1.1:80 " + UKNIG);
        network.refused.add("2.2.2.2:80 " + UKNIG);
        network.calls.clear();

        finder.reportSuspect(host);
        assertEquals(Arrays.asList("1.1.1.1:80 " + UKNIG, "2.2.2.2:80 " + UKNIG), network.calls);
        assertEquals("the site, not the proxy: nothing changes", Arrays.asList("1.1.1.1:80", "2.2.2.2:80"),
                labels(saved().proxies));
        assertEquals("1.1.1.1:80", finder.state().current.label());

        finder.reportSuspect(host);
        target.set(UKNIG);
        finder.recheckSite();
        now.addAndGet(ProxyFinder.SITE_COOLDOWN_MS - 1);
        finder.reportSuspect(host);
        assertEquals("the site is not compared again for a while", 2, network.calls.size());
        target.set(TARGET);
        finder.recheckSite();
        assertEquals("another site is", Arrays.asList("1.1.1.1:80 " + UKNIG, "2.2.2.2:80 " + UKNIG,
                "1.1.1.1:80 " + TARGET), network.calls);

        now.addAndGet(1);
        finder.reportSuspect(host);
        assertEquals(5, network.calls.size());
        assertEquals("1.1.1.1:80", finder.state().current.label());
    }

    @Test
    public void anotherSiteComparesTheProxyInUseOnIt() {
        lists("1.1.1.1:80\n2.2.2.2:80\n", "");
        network.latency.put("1.1.1.1:80", 100L);
        network.latency.put("2.2.2.2:80", 200L);
        ProxyFinder finder = finder();
        finder.rescan();
        network.refused.add("1.1.1.1:80 " + UKNIG);
        network.calls.clear();

        // what AppProxy's listener does when "sorce_books" changes in the automatic mode
        target.set(UKNIG);
        finder.recheckSite();

        assertEquals(Arrays.asList("1.1.1.1:80 " + UKNIG, "2.2.2.2:80 " + UKNIG), network.calls);
        assertEquals("2.2.2.2:80", finder.state().current.label());
        assertEquals(Arrays.asList("2.2.2.2:80", "1.1.1.1:80"), labels(saved().proxies));
    }

    @Test
    public void aRecheckAskedWhileOneRunsRunsAgainWithTheNewSite() {
        lists("1.1.1.1:80\n2.2.2.2:80\n", "");
        network.latency.put("1.1.1.1:80", 100L);
        network.latency.put("2.2.2.2:80", 200L);
        List<Runnable> queue = new ArrayList<>();
        ProxyFinder finder = finder(queue::add);
        finder.rescan();
        drain(queue);
        network.calls.clear();
        AtomicBoolean once = new AtomicBoolean();
        network.script = (label, url, attempt) -> {
            if (url.equals(TARGET) && once.compareAndSet(false, true)) {
                // the user picks another site while the check of the first one runs
                target.set(UKNIG);
                finder.recheckSite();
            }
            return null;
        };

        finder.recheckSite();
        assertEquals(1, queue.size());
        drain(queue);

        assertEquals(Arrays.asList("1.1.1.1:80 " + TARGET, "1.1.1.1:80 " + UKNIG), network.calls);
    }

    @Test
    public void aRecheckIsQueuedOnceWhileOneIsWaiting() {
        lists("1.1.1.1:80\n2.2.2.2:80\n", "");
        network.latency.put("1.1.1.1:80", 100L);
        network.latency.put("2.2.2.2:80", 200L);
        List<Runnable> queue = new ArrayList<>();
        ProxyFinder finder = finder(queue::add);
        finder.rescan();
        drain(queue);

        // every save of the source list asks for a recheck
        finder.recheckCurrent();
        finder.recheckCurrent();
        finder.recheckSite();
        finder.reportSuspect(Url.hostOf(TARGET));
        assertEquals(1, queue.size());
        drain(queue);
        finder.recheckCurrent();
        assertEquals("once it ran, the next one is queued", 1, queue.size());
        drain(queue);
        assertEquals("1.1.1.1:80", finder.state().current.label());
    }

    @Test
    public void theStateCountsRefusalsAndUnreachables() {
        lists("1.1.1.1:80\n2.2.2.2:80\n3.3.3.3:80\n", "");
        network.latency.put("1.1.1.1:80", 100L);
        network.latency.put("2.2.2.2:80", 100L);
        network.refused.add("1.1.1.1:80 " + TARGET);
        network.refused.add("2.2.2.2:80 " + TARGET);
        ProxyFinder finder = finder();

        finder.rescan();

        ProxyFinder.State state = finder.state();
        assertEquals("the site refused both, the control servers verified them", ProxyFinder.Phase.READY,
                state.phase);
        assertEquals(3, state.tested);
        assertEquals(2, state.found);
        assertEquals(2, state.refused);
        assertEquals("one never answered a control server", 1, state.unreachable);
        assertEquals(TARGET, state.site);
        assertEquals(0, state.sitePassed);

        // the counters belong to one search
        network.refused.clear();
        finder.rescan();
        assertEquals(0, finder.state().refused);
        assertEquals(1, finder.state().unreachable);
        assertEquals(2, finder.state().found);
        assertEquals(2, finder.state().sitePassed);
    }

    @Test
    public void aListIsCreditedWithItsControlChecksOnly() {
        StringBuilder http = new StringBuilder();
        StringBuilder mixed = new StringBuilder();
        for (int i = 1; i <= 4; i++) {
            String label = "33." + i + ".1.1:80";
            http.append(label).append('\n');
            network.latency.put(label, 100L + i);
            network.refused.add(label + " " + TARGET); // the site refuses every one
            mixed.append("http://34.").append(i).append(".1.1:8080\n"); // dead
        }
        lists(http.toString(), mixed.toString());
        ProxyFinder finder = finder(Runnable::run, 1);

        finder.rescan();

        assertEquals(4, finder.state().refused);
        assertEquals(ProxyFinder.Phase.READY, finder.state().phase);
        ProxySources.ListStats stats = ProxySources.ListStats.fromJson(
                prefs.getString(ProxySources.ListStats.PREF, null));
        assertArrayEquals("the site's refusals are not the list's", new int[]{4, 4}, stats.counts.get("http"));
        assertArrayEquals(new int[]{0, 4}, stats.counts.get("mixed"));
    }

    @Test
    public void listYieldSteersTheNextScansQuotas() {
        // the last searches: the http list verified 0 of 20, the mixed one 14 of 20; "gone" is no list any more
        prefs.edit().putString(ProxySources.ListStats.PREF, "{\"http\":[0,20],\"mixed\":[14,20],\"gone\":[1,9]}")
                .apply();
        StringBuilder http = new StringBuilder();
        StringBuilder mixed = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            http.append("31.").append(i).append(".1.1:80\n");
            mixed.append("http://32.").append(i).append(".1.1:8080\n");
        }
        lists(http.toString(), mixed.toString());
        ProxyFinder finder = finder(Runnable::run, 1);

        finder.rescan();

        int fromMixed = 0;
        for (String call : network.calls.subList(0, 10)) {
            if (call.startsWith("32.")) {
                fromMixed++;
            }
        }
        assertEquals("a round takes 1 of the http list and 4 of the mixed one", 8, fromMixed);
        ProxySources.ListStats stats = ProxySources.ListStats.fromJson(
                prefs.getString(ProxySources.ListStats.PREF, null));
        assertArrayEquals("halved, then this search's 0 of 20", new int[]{0, 30}, stats.counts.get("http"));
        assertArrayEquals(new int[]{7, 30}, stats.counts.get("mixed"));
        assertFalse(stats.counts.containsKey("gone"));
    }

    @Test
    public void theTargetIsTheSelectedOrElseTheFirstActiveProxiedSite() {
        Set<String> none = Collections.emptySet();
        List<Provider> active = Arrays.asList(Provider.AKNIGA, Provider.IZIBUK, Provider.OTRUB, Provider.UKNIG);
        assertEquals(site(Provider.UKNIG), ProxyFinder.targetOf(Provider.UKNIG.prefValue, none, active));
        assertEquals("nothing selected: the first active one", site(Provider.AKNIGA),
                ProxyFinder.targetOf(null, none, active));

        Set<String> direct = new HashSet<>(Arrays.asList(Provider.UKNIG.prefValue, Provider.AKNIGA.prefValue));
        assertEquals("the selected source goes direct: the first active one that takes the proxy",
                site(Provider.IZIBUK), ProxyFinder.targetOf(Provider.UKNIG.prefValue, direct, active));

        assertEquals("the selected source even when not active", site(Provider.UKNIG),
                ProxyFinder.targetOf(Provider.UKNIG.prefValue, none, Collections.singletonList(Provider.AKNIGA)));
        assertEquals("an unknown selection: the first active one", site(Provider.AKNIGA),
                ProxyFinder.targetOf("no such source", none, Arrays.asList(Provider.AKNIGA, Provider.OTRUB)));

        Set<String> all = new HashSet<>(Arrays.asList(Provider.AKNIGA.prefValue, Provider.OTRUB.prefValue));
        assertNull("nothing takes the proxy: no site",
                ProxyFinder.targetOf(Provider.AKNIGA.prefValue, all, Arrays.asList(Provider.AKNIGA, Provider.OTRUB)));
    }

    private static String site(Provider provider) {
        return provider.server + "/";
    }

    @Test
    public void aSearchThatLosesTheNetworkKeepsTheKnownList() {
        lists("1.1.1.1:80\n2.2.2.2:80\n", "");
        network.latency.put("1.1.1.1:80", 100L);
        network.latency.put("2.2.2.2:80", 200L);
        ProxyFinder finder = finder();
        finder.rescan();
        long scannedAt = finder.state().lastScanAt;

        now.addAndGet(60_000L);
        network.onFetch = () -> {
            online.set(false);
            network.latency.clear();
        };
        finder.rescan();

        assertEquals(ProxyFinder.Phase.NO_NETWORK, finder.state().phase);
        assertEquals("1.1.1.1:80", finder.state().current.label());
        assertEquals(1, finder.state().spares);
        assertEquals(scannedAt, finder.state().lastScanAt);
        assertEquals(Arrays.asList("1.1.1.1:80", "2.2.2.2:80"), labels(saved().proxies));

        // the retry comes after the short no-network pause
        network.onFetch = () -> { };
        network.latency.put("1.1.1.1:80", 100L);
        online.set(true);
        assertEquals("back online, the kept proxy is in use again", ProxyFinder.Phase.READY,
                finder.state().phase);
        int fetches = network.fetches.get();
        now.addAndGet(ProxyFinder.NO_NETWORK_RETRY_MS);
        finder.scanIfDue();
        assertTrue(network.fetches.get() > fetches);
        assertEquals(ProxyFinder.Phase.READY, finder.state().phase);
    }

    @Test
    public void noScanWithoutNetwork() {
        lists("1.1.1.1:80\n", "");
        network.latency.put("1.1.1.1:80", 100L);
        online.set(false);
        ProxyFinder finder = finder();

        finder.ensureProxy();

        assertEquals(ProxyFinder.Phase.NO_NETWORK, finder.state().phase);
        assertEquals(0, network.fetches.get());
        assertEquals(0, network.calls.size());
        online.set(true);
        now.addAndGet(ProxyFinder.NO_NETWORK_RETRY_MS);
        finder.ensureProxy();
        assertEquals(ProxyFinder.Phase.READY, finder.state().phase);
    }

    @Test
    public void automaticScansAreRateLimitedButAManualOneAlwaysRuns() {
        lists("1.1.1.1:80\n", "");
        ProxyFinder finder = finder();

        finder.ensureProxy();
        assertEquals("nothing found and no list to keep", ProxyFinder.Phase.FAILED, finder.state().phase);
        assertTrue(saved().proxies.isEmpty());
        int fetches = network.fetches.get();
        finder.ensureProxy();
        now.addAndGet(ProxyFinder.AUTO_SCAN_INTERVAL_MS - 1);
        finder.ensureProxy();
        assertEquals(fetches, network.fetches.get());

        network.latency.put("1.1.1.1:80", 100L);
        finder.rescan();
        assertEquals(ProxyFinder.Phase.READY, finder.state().phase);
        assertTrue(network.fetches.get() > fetches);
    }

    @Test
    public void aRescanTriesTheRemainingVerifiedProxiesFirst() {
        lists("1.1.1.1:80\n2.2.2.2:80\n", "");
        network.latency.put("1.1.1.1:80", 100L);
        network.latency.put("2.2.2.2:80", 200L);
        ProxyFinder finder = finder();
        finder.rescan();
        network.lists.clear();

        finder.rescan();

        assertEquals("the lists are unreachable, the known proxies still work", "1.1.1.1:80", finder.state().current.label());
        assertEquals(2, finder.state().found);
    }

    @Test
    public void theListSurvivesARestartAndIsRecheckedOnStart() {
        lists("1.1.1.1:80\n2.2.2.2:80\n", "");
        network.latency.put("1.1.1.1:80", 100L);
        network.latency.put("2.2.2.2:80", 200L);
        finder().rescan();

        ProxyFinder restarted = finder();
        assertEquals(ProxyFinder.Phase.READY, restarted.state().phase);
        assertEquals("1.1.1.1:80", restarted.state().current.label());

        network.latency.remove("1.1.1.1:80");
        int fetches = network.fetches.get();
        restarted.recheckCurrent();
        assertEquals("2.2.2.2:80", restarted.state().current.label());
        assertEquals(fetches, network.fetches.get());

        network.latency.put("2.2.2.2:80", 50L);
        restarted.recheckCurrent();
        assertEquals(50L, restarted.state().current.latencyMs());
    }

    @Test
    public void theRouteOffersTheProxyInUseAndTwoSpares() {
        lists("1.1.1.1:80\n2.2.2.2:80\n3.3.3.3:80\n", "socks5://4.4.4.4:1080\n");
        network.latency.put("1.1.1.1:80", 100L);
        network.latency.put("2.2.2.2:80", 200L);
        network.latency.put("3.3.3.3:80", 300L);
        network.latency.put("4.4.4.4:1080", 400L);
        // the search's site check stops once three passed: on a real network the 400 ms one answers last and is cut
        // off. The fake answers at once whatever latency it reports, so it waits until the check is over, not racing
        network.script = (label, url, attempt) -> url.equals(TARGET) && label.equals("4.4.4.4:1080") ? stall() : null;
        ProxyFinder finder = finder();
        finder.rescan();

        assertEquals(Arrays.asList("1.1.1.1:80", "2.2.2.2:80", "3.3.3.3:80"), routeLabels(finder));
        assertEquals(3, finder.state().spares);
        assertSame(finder.currentProxy(), finder.proxiesForRequest().get(0));

        // a client that failed over to a spare reports the proxy in use first: that one counts; the spare that is not
        // in use yet does not
        network.latency.remove("1.1.1.1:80");
        finder.reportFailure(finder.proxiesForRequest().get(1).address());
        assertEquals("1.1.1.1:80", finder.state().current.label());
        finder.reportFailure(finder.proxiesForRequest().get(0).address());
        assertEquals(Arrays.asList("2.2.2.2:80", "3.3.3.3:80", "4.4.4.4:1080"), routeLabels(finder));
        assertEquals(2, finder.state().spares);

        network.latency.remove("2.2.2.2:80");
        finder.reportFailure(finder.currentProxy().address(), true);
        network.latency.remove("3.3.3.3:80");
        finder.reportFailure(finder.currentProxy().address(), true);
        assertEquals(Collections.singletonList("4.4.4.4:1080"), routeLabels(finder));
        assertEquals(0, finder.state().spares);
    }

    @Test
    public void pooledConnectionsAreDroppedWhenTheProxyInUseChanges() {
        lists("1.1.1.1:80\n2.2.2.2:80\n", "");
        network.latency.put("1.1.1.1:80", 100L);
        network.latency.put("2.2.2.2:80", 200L);
        ProxyFinder finder = finder();
        assertEquals(0, switches.get());

        finder.rescan();
        assertEquals("direct to the first proxy", 1, switches.get());
        finder.recheckCurrent();
        assertEquals("the same proxy, checked again", 1, switches.get());
        finder.rescan();
        assertEquals("a new search that keeps the proxy in use", 1, switches.get());
        network.latency.remove("1.1.1.1:80");
        finder.reportFailure(finder.currentProxy().address(), true);
        assertEquals(2, switches.get());
        network.latency.remove("2.2.2.2:80");
        network.direct = false;
        finder.reportFailure(finder.currentProxy().address(), true);
        assertEquals("the last one stays while nothing answers directly: no change, nothing dropped", 2,
                switches.get());
        network.direct = true;
        finder.reportFailure(finder.currentProxy().address(), true);
        assertEquals("the list ran out: the connections through the last one are dropped", 3, switches.get());
    }

    @Test
    public void aListThatFailsToDownloadDoesNotStopTheScan() {
        network.lists.put(MIXED_LIST.name, "http://5.5.5.5:8080\n");
        network.latency.put("5.5.5.5:8080", 300L);
        ProxyFinder finder = finder();

        finder.rescan();

        assertEquals(ProxyFinder.Phase.READY, finder.state().phase);
        assertEquals("5.5.5.5:8080", finder.state().current.label());
    }

    @Test
    public void onlyAnAnswerFrom200To399IsUsable() {
        for (int code : new int[]{200, 204, 206, 301, 302, 304, 399}) {
            assertTrue(String.valueOf(code), ProxyFinder.usable(code));
        }
        for (int code : new int[]{0, 101, 199, 400, 403, 407, 429, 451, 500, 502, 503}) {
            assertFalse(String.valueOf(code), ProxyFinder.usable(code));
        }
    }

    @Test
    public void aControlPassesOnItsExactCodeAndMarkerOnly() {
        ProxyFinder.Control empty = new ProxyFinder.Control("https://check.example/generate_204", 204, null);
        assertTrue(empty.passes(204, ""));
        for (int code : new int[]{200, 206, 301, 302, 403, 407, 503}) {
            assertFalse(String.valueOf(code), empty.passes(code, ""));
        }
        ProxyFinder.Control marked = new ProxyFinder.Control("https://check.example/success.txt", 200, "success");
        assertTrue(marked.passes(200, "success\n"));
        assertFalse("a portal's page", marked.passes(200, "<html>Sign in</html>"));
        assertFalse("the marker is case-sensitive", marked.passes(200, "SUCCESS"));
        assertFalse(marked.passes(204, "success"));
        assertEquals(4, ProxyFinder.CONTROLS.size());
        for (ProxyFinder.Control control : ProxyFinder.CONTROLS) {
            assertTrue(control.url, control.url.startsWith("https://"));
        }
    }

    @Test
    public void theRealControlCheckWantsTheServersOwnAnswer() throws Exception {
        ProxyFinder.OkNetwork real = new ProxyFinder.OkNetwork();
        ProxyFinder.Control empty = new ProxyFinder.Control("http://check.invalid/generate_204", 204, null);
        ProxyFinder.Control marked = new ProxyFinder.Control("http://check.invalid/success.txt", 200, "success");
        Object[][] cases = {
                {empty, 204, "", true}, {empty, 200, "", false}, {empty, 302, "", false},
                {marked, 200, "success\n", true}, {marked, 200, "<html>login</html>", false}, {marked, 403, "success", false},
        };
        for (Object[] c : cases) {
            ProxyFinder.Control control = (ProxyFinder.Control) c[0];
            try (ServerSocket proxy = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                String[] requestLine = new String[1];
                Thread thread = new Thread(() -> answerOnce(proxy, (Integer) c[1], (String) c[2], requestLine));
                thread.start();
                ProxyCandidate candidate = new ProxyCandidate("127.0.0.1", proxy.getLocalPort(), Proxy.Type.HTTP);
                boolean passed;
                try {
                    assertTrue(real.control(candidate, control, 2_000) >= 1);
                    passed = true;
                } catch (IOException e) {
                    passed = false;
                }
                thread.join(5_000);
                assertEquals(control + " " + c[1] + " " + c[2], c[3], passed);
                assertEquals("a plain GET of the server", "GET " + control.url + " HTTP/1.1", requestLine[0]);
            }
        }
    }

    @Test
    public void theRealSiteCheckPassesAPageAndRejectsABlockThroughAnHttpProxy() throws Exception {
        ProxyFinder.OkNetwork real = new ProxyFinder.OkNetwork();
        for (int status : new int[]{206, 302, 403, 429, 503}) {
            try (ServerSocket proxy = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                String[] requestLine = new String[1];
                Thread thread = new Thread(() -> answerOnce(proxy, status, "", requestLine));
                thread.start();
                ProxyCandidate candidate = new ProxyCandidate("127.0.0.1", proxy.getLocalPort(), Proxy.Type.HTTP);
                boolean passed;
                try {
                    assertTrue(real.site(candidate, "http://catalog.invalid/", 2_000) >= 1);
                    passed = true;
                } catch (ProxyFinder.SiteRefused e) {
                    assertEquals("the site answered and refused", status, e.code);
                    passed = false;
                }
                thread.join(5_000);
                assertEquals(String.valueOf(status), status < 400, passed);
                assertEquals("the proxy is asked for the site", "GET http://catalog.invalid/ HTTP/1.1", requestLine[0]);
            }
        }
    }

    @Test
    public void theRealSiteCheckPostsToAudiobooAndGetsAnyOtherSite() throws Exception {
        ProxyFinder.OkNetwork real = new ProxyFinder.OkNetwork();
        String audioboo = HttpUrl.get(Url.SERVER_AUDIOBOO).newBuilder().scheme("http").build().toString();
        assertTrue(Url.isHost(audioboo, Url.SERVER_AUDIOBOO));
        for (String page : new String[]{audioboo, "http://catalog.invalid/"}) {
            try (ServerSocket proxy = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                String[] requestLine = new String[1];
                Thread thread = new Thread(() -> answerOnce(proxy, 200, "", requestLine));
                thread.start();
                ProxyCandidate candidate = new ProxyCandidate("127.0.0.1", proxy.getLocalPort(), Proxy.Type.HTTP);
                assertTrue(real.site(candidate, page, 2_000) >= 1);
                thread.join(5_000);
                String method = page.equals(audioboo) ? "POST" : "GET";
                assertEquals(method + " " + page + " HTTP/1.1", requestLine[0]);
            }
        }
    }

    /** An HTTP proxy that answers one request itself with {@code status} and {@code body}. */
    private static void answerOnce(ServerSocket server, int status, String body, String[] requestLine) {
        try (Socket socket = server.accept()) {
            BufferedReader request = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            requestLine[0] = request.readLine();
            for (String line = request.readLine(); line != null && !line.isEmpty(); line = request.readLine()) {
                // headers are not needed
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            OutputStream out = socket.getOutputStream();
            out.write(("HTTP/1.1 " + status + " X\r\nLocation: http://catalog.invalid/next\r\n"
                    + "Content-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.write(bytes);
            out.flush();
        } catch (IOException ignored) {
            // the test fails on its own assertions
        }
    }

    private static List<String> routeLabels(ProxyFinder finder) {
        List<String> labels = new ArrayList<>();
        for (Proxy proxy : finder.proxiesForRequest()) {
            InetSocketAddress address = (InetSocketAddress) proxy.address();
            labels.add(address.getHostString() + ":" + address.getPort());
        }
        return labels;
    }

    @Test
    public void theSavedListRoundTripsAndOlderListsLoad() {
        List<ProxyCandidate> proxies = Arrays.asList(
                new ProxyCandidate("1.2.3.4", 8080, Proxy.Type.HTTP).checked(420, 1_700_000_000_000L),
                new ProxyCandidate("5.6.7.8", 1080, Proxy.Type.SOCKS).checked(950, 1_700_000_000_001L));
        String json = ProxyFinder.Saved.toJson(proxies, 1, 1_700_000_000_002L, 1_700_000_000_003L);
        assertFalse("no sites are kept any more", json.contains("target") || json.contains("refused"));
        ProxyFinder.Saved saved = ProxyFinder.Saved.fromJson(json);
        assertEquals(proxies, saved.proxies);
        assertEquals(1, saved.index);
        assertEquals(1_700_000_000_002L, saved.scannedAt);
        assertEquals(1_700_000_000_003L, saved.exhaustedAt);
        assertEquals(420, saved.proxies.get(0).latencyMs());
        assertEquals(1_700_000_000_001L, saved.proxies.get(1).checkedAt());
        assertSame(Proxy.Type.SOCKS, saved.proxies.get(1).type());

        String items = "\"proxies\":[{\"host\":\"1.2.3.4\",\"port\":8080,\"type\":\"http\",\"ms\":420,\"at\":6},"
                + "{\"host\":\"5.6.7.8\",\"port\":1080,\"type\":\"socks5\"}]";
        // a list saved before the sites were kept, and one that kept them
        for (String old : new String[]{"{\"v\":1,\"current\":1,\"scannedAt\":5," + items + "}",
                "{\"v\":2,\"current\":1,\"scannedAt\":5,\"target\":\"https://uknig.com/\","
                        + "\"refused\":[\"https://akniga.org/\"]," + items + "}"}) {
            ProxyFinder.Saved loaded = ProxyFinder.Saved.fromJson(old);
            assertEquals(old, proxies, loaded.proxies);
            assertEquals(1, loaded.index);
            assertEquals(5, loaded.scannedAt);
            assertEquals("no run-out kept yet", 0, loaded.exhaustedAt);
        }
    }

    @Test
    public void anUnreadableSavedListIsEmpty() {
        assertTrue(ProxyFinder.Saved.fromJson(null).proxies.isEmpty());
        assertTrue(ProxyFinder.Saved.fromJson("").proxies.isEmpty());
        assertTrue(ProxyFinder.Saved.fromJson("not json").proxies.isEmpty());
        assertTrue(ProxyFinder.Saved.fromJson("[1,2]").proxies.isEmpty());
        String tampered = "{\"current\":0,\"proxies\":[{\"host\":\"127.0.0.1\",\"port\":80,\"type\":\"http\"},"
                + "{\"host\":\"1.2.3.4\",\"port\":80,\"type\":\"socks4\"},{\"host\":\"1.2.3.4\",\"port\":80,\"type\":\"http\"}]}";
        assertEquals(Collections.singletonList("1.2.3.4:80"), labels(ProxyFinder.Saved.fromJson(tampered).proxies));
        prefs.edit().putInt(ProxyFinder.PREF_LIST, 3).apply();
        ProxyFinder finder = finder();
        assertEquals(ProxyFinder.Phase.IDLE, finder.state().phase);
        assertNull(finder.currentProxy());
    }

    @Test
    public void candidatesCompareByAddressAndType() {
        ProxyCandidate a = new ProxyCandidate("1.2.3.4", 80, Proxy.Type.HTTP);
        assertEquals(a, a.checked(5, 6));
        assertFalse(a.equals(new ProxyCandidate("1.2.3.4", 80, Proxy.Type.SOCKS)));
        assertTrue(a.hasAddress(InetSocketAddress.createUnresolved("1.2.3.4", 80)));
        assertTrue(a.hasAddress(new InetSocketAddress("1.2.3.4", 80)));
        assertFalse(a.hasAddress(InetSocketAddress.createUnresolved("1.2.3.4", 81)));
        assertFalse(a.hasAddress(null));
    }

    private static List<String> labels(List<ProxyCandidate> proxies) {
        List<String> labels = new ArrayList<>();
        for (ProxyCandidate proxy : proxies) {
            labels.add(proxy.label());
        }
        return labels;
    }
}
