package com.fanok.audiobooks.search;

import io.reactivex.Observable;
import io.reactivex.Scheduler;
import io.reactivex.Single;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.functions.Function;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * One generation-scoped search over several sources. Every {@link #search} starts a new generation:
 * the previous run is disposed and its late answers are ignored. Sources run in parallel, each bounded
 * by a timeout and reduced to a {@link ProviderPage}; results are merged (deduplicated by {@code key})
 * and published as they arrive, with every source's {@link State} as it starts and answers. Per-source page
 * cursors let {@link #loadNext} continue the sources that still have pages, each as soon as it is idle, and
 * {@link #loadNext(String)} / {@link #retry(String)} one source alone, whatever the others are doing. A source
 * that found nothing is asked once more, at once, in the other letter case. All methods and callbacks run on
 * the {@code main} scheduler.
 */
public final class SearchSession<T> {

    public interface Source<T> {
        String name();

        Single<ProviderPage<T>> load(String query, int page);

        /** How long this source may take when that is longer than the session's limit (a slow site); 0: the session's. */
        default long timeoutSeconds() {
            return 0;
        }
    }

    public interface Listener<T> {
        /** New items arrived: the merged list so far, a copy, in order of arrival. */
        void onItems(List<T> items);

        /** Every source of the pass has answered (or none was enabled). */
        void onFinished(List<T> items, int failedSources);

        /** A source started loading or answered. */
        default void onSourceState(State state) {
        }
    }

    /** Where one source stands in the current generation. */
    public static final class State {

        public final String name;
        /** A page of it is loading. */
        public final boolean running;
        /** Its last answer; null before the first one. */
        public final ProviderPage.Status status;
        /** Items it contributed so far (after deduplication). */
        public final int count;
        /** It has a next page to load. */
        public final boolean hasMore;
        /**
         * When the page it is loading was asked for (the main scheduler's clock, millis); a retry in the other letter
         * case goes on from the first ask. 0 when it is not loading, or unknown.
         */
        public final long startedAt;

        State(String name, boolean running, ProviderPage.Status status, int count, boolean hasMore) {
            this(name, running, status, count, hasMore, 0);
        }

        State(String name, boolean running, ProviderPage.Status status, int count, boolean hasMore, long startedAt) {
            this.name = name;
            this.running = running;
            this.status = status;
            this.count = count;
            this.hasMore = hasMore;
            this.startedAt = startedAt;
        }

        public boolean isFailure() {
            return status == ProviderPage.Status.ERROR || status == ProviderPage.Status.TIMEOUT
                    || status == ProviderPage.Status.BLOCKED;
        }
    }

    private static final class Arrival<T> {
        final Source<T> source;
        final int pageNo;
        final String query;
        final ProviderPage<T> page;

        Arrival(Source<T> source, int pageNo, String query, ProviderPage<T> page) {
            this.source = source;
            this.pageNo = pageNo;
            this.query = query;
            this.page = page;
        }
    }

    private final List<Source<T>> sources;
    private final Function<T, String> key;
    private final Scheduler io;
    private final Scheduler main;
    private final long timeoutSeconds;
    /**
     * Sources loading at once; the rest start as these answer. Today's number of sites, so their search runs as before;
     * it bounds the fan-out on a phone's network once there are more.
     */
    static final int MAX_PARALLEL = 13;
    private final Listener<T> listener;

    private final CompositeDisposable running = new CompositeDisposable();
    private final ArrayList<T> items = new ArrayList<>();
    private final HashSet<String> seen = new HashSet<>();
    /** Next page per source; a source leaves the map when it has no more pages. */
    private final LinkedHashMap<Source<T>, Integer> nextPage = new LinkedHashMap<>();
    private final LinkedHashMap<Source<T>, String> queryOf = new LinkedHashMap<>();
    private final LinkedHashMap<Source<T>, ProviderPage.Status> statuses = new LinkedHashMap<>();
    private final LinkedHashSet<Source<T>> failed = new LinkedHashSet<>();
    private final HashSet<Source<T>> loading = new HashSet<>();
    private final HashMap<Source<T>, Long> startedAt = new HashMap<>();
    private final HashSet<Source<T>> caseTried = new HashSet<>();
    private final LinkedHashMap<Source<T>, Integer> counts = new LinkedHashMap<>();
    private int generation;
    private int pending;
    private String query = "";

    public SearchSession(List<Source<T>> sources, Function<T, String> key, Scheduler io, Scheduler main,
            long timeoutSeconds, Listener<T> listener) {
        this.sources = new ArrayList<>(sources);
        this.key = key;
        this.io = io;
        this.main = main;
        this.timeoutSeconds = timeoutSeconds;
        this.listener = listener;
    }

    /** Starts a new generation for {@code q}: page 1 of every source, in parallel. */
    public void search(String q) {
        generation++;
        running.clear();
        pending = 0;
        items.clear();
        seen.clear();
        statuses.clear();
        failed.clear();
        nextPage.clear();
        queryOf.clear();
        loading.clear();
        caseTried.clear();
        counts.clear();
        query = q;
        for (Source<T> s : sources) {
            nextPage.put(s, 1);
            queryOf.put(s, q);
        }
        run(new ArrayList<>(sources));
    }

    /**
     * Loads the next page of every idle source that still has one, without waiting for the busy ones; false when
     * nothing was started.
     */
    public boolean loadNext() {
        ArrayList<Source<T>> batch = new ArrayList<>();
        for (Source<T> s : nextPage.keySet()) {
            if (!failed.contains(s) && !loading.contains(s)) {
                batch.add(s);
            }
        }
        if (batch.isEmpty()) {
            return false;
        }
        run(batch);
        return true;
    }

    /** Loads the next page of the source {@code name} alone; false when it is busy, failed or has no more. */
    public boolean loadNext(String name) {
        Source<T> s = byName(name);
        if (s == null || !nextPage.containsKey(s) || failed.contains(s) || loading.contains(s)) {
            return false;
        }
        run(Collections.singletonList(s));
        return true;
    }

    /** Re-requests the same page from the idle sources whose last answer was a failure. */
    public boolean retryFailed() {
        ArrayList<Source<T>> batch = new ArrayList<>();
        for (Source<T> s : failed) {
            if (!loading.contains(s)) {
                batch.add(s);
            }
        }
        if (batch.isEmpty()) {
            return false;
        }
        failed.removeAll(batch);
        run(batch);
        return true;
    }

    /** Re-requests the same page from the source {@code name} alone, if its last answer was a failure. */
    public boolean retry(String name) {
        Source<T> s = byName(name);
        if (s == null || !failed.contains(s) || loading.contains(s)) {
            return false;
        }
        failed.remove(s);
        run(Collections.singletonList(s));
        return true;
    }

    public boolean isRunning() {
        return pending > 0;
    }

    /** Whether the source {@code name} has a next page it can load. */
    public boolean hasMore(String name) {
        Source<T> s = byName(name);
        return s != null && nextPage.containsKey(s) && !failed.contains(s);
    }

    /** The states of every source of the session, in the order of the sources. */
    public List<State> states() {
        List<State> out = new ArrayList<>(sources.size());
        for (Source<T> s : sources) {
            out.add(state(s));
        }
        return out;
    }

    private Source<T> byName(String name) {
        for (Source<T> s : sources) {
            if (s.name().equals(name)) {
                return s;
            }
        }
        return null;
    }

    private State state(Source<T> s) {
        Integer count = counts.get(s);
        Long started = startedAt.get(s);
        boolean busy = loading.contains(s);
        return new State(s.name(), busy, statuses.get(s), count == null ? 0 : count,
                nextPage.containsKey(s) && !failed.contains(s), busy && started != null ? started : 0);
    }

    public boolean hasMore() {
        for (Source<T> s : nextPage.keySet()) {
            if (!failed.contains(s)) {
                return true;
            }
        }
        return false;
    }

    public int failedCount() {
        return failed.size();
    }

    /** The last status of every source that has answered in this generation, by source name. */
    public Map<String, ProviderPage.Status> statuses() {
        LinkedHashMap<String, ProviderPage.Status> out = new LinkedHashMap<>();
        for (Map.Entry<Source<T>, ProviderPage.Status> e : statuses.entrySet()) {
            out.put(e.getKey().name(), e.getValue());
        }
        return out;
    }

    public List<T> items() {
        return new ArrayList<>(items);
    }

    /**
     * Takes up a saved search without a request: its query, items, and per source the last answer, the count and the
     * next page to load ({@code null}: none). A source that was still loading has no answer: its page loads again on
     * {@link #loadNext(String)}.
     */
    public void restore(String q, List<T> saved, Map<String, ProviderPage.Status> lastStatus,
            Map<String, Integer> cursor, Map<String, Integer> count) {
        restore(q, saved, lastStatus, cursor, count, Collections.<String, String>emptyMap(), null);
    }

    /**
     * As above, with each source's own query (the other letter case it went on with) and the sources that already had
     * their case retry ({@code null}: every one).
     */
    public void restore(String q, List<T> saved, Map<String, ProviderPage.Status> lastStatus,
            Map<String, Integer> cursor, Map<String, Integer> count, Map<String, String> queries,
            java.util.Set<String> caseRetried) {
        dispose();
        items.clear();
        seen.clear();
        statuses.clear();
        failed.clear();
        nextPage.clear();
        queryOf.clear();
        caseTried.clear();
        counts.clear();
        query = q;
        for (T item : saved) {
            String k;
            try {
                k = key.apply(item);
            } catch (Exception e) {
                k = null;
            }
            if (k == null || seen.add(k)) {
                items.add(item);
            }
        }
        for (Source<T> s : sources) {
            if (caseRetried == null || caseRetried.contains(s.name())) {
                caseTried.add(s); // a restored search does not repeat its case retries
            }
            String own = queries.get(s.name());
            queryOf.put(s, own == null ? q : own);
            ProviderPage.Status status = lastStatus.get(s.name());
            if (status != null) {
                statuses.put(s, status);
            }
            Integer page = cursor.get(s.name());
            if (page != null) {
                nextPage.put(s, page);
            }
            if (status == ProviderPage.Status.ERROR || status == ProviderPage.Status.TIMEOUT
                    || status == ProviderPage.Status.BLOCKED) {
                failed.add(s);
            }
            Integer n = count.get(s.name());
            counts.put(s, n == null ? 0 : n);
        }
    }

    /** The next page of every source ({@code null}: none), by name: what a saved search needs to go on. */
    public Map<String, Integer> cursors() {
        LinkedHashMap<String, Integer> out = new LinkedHashMap<>();
        for (Source<T> s : sources) {
            out.put(s.name(), nextPage.get(s));
        }
        return out;
    }

    public String query() {
        return query;
    }

    /** The query each source goes on with (the other letter case after an empty first answer), by name. */
    public Map<String, String> queries() {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (Source<T> s : sources) {
            out.put(s.name(), queryOf.get(s) == null ? query : queryOf.get(s));
        }
        return out;
    }

    /** Whether the source {@code name} already had its retry in the other letter case. */
    public boolean caseRetried(String name) {
        Source<T> s = byName(name);
        return s != null && caseTried.contains(s);
    }

    /** Cancels everything; later answers are dropped. */
    public void dispose() {
        generation++;
        pending = 0;
        loading.clear();
        running.clear();
    }

    private void run(List<Source<T>> batch) {
        run(batch, null);
    }

    /** Loads the cursor page of every source of {@code batch}; {@code since}: the time their wait started, else now. */
    private void run(List<Source<T>> batch, Long since) {
        final int gen = generation;
        if (batch.isEmpty()) {
            listener.onFinished(items(), failed.size());
            return;
        }
        pending += batch.size();
        final HashSet<Source<T>> left = new HashSet<>(batch);
        List<Observable<Arrival<T>>> calls = new ArrayList<>(batch.size());
        for (final Source<T> s : batch) {
            final Integer cursor = nextPage.get(s);
            final int page = cursor == null ? 1 : cursor;
            final String q = queryOf.get(s) == null ? query : queryOf.get(s);
            loading.add(s);
            startedAt.put(s, since != null ? since : main.now(TimeUnit.MILLISECONDS));
            calls.add(Single.defer(() -> s.load(q, page))
                    .subscribeOn(io)
                    .timeout(Math.max(timeoutSeconds, s.timeoutSeconds()), TimeUnit.SECONDS, io)
                    .onErrorReturn(e -> ProviderPage.<T>failed(e))
                    .map(p -> new Arrival<>(s, page, q, p))
                    .toObservable());
        }
        for (Source<T> s : batch) {
            listener.onSourceState(state(s));
        }
        // merge subscribes lazily: a source's timeout starts when it gets a slot, not while it waits for one
        running.add(Observable.merge(calls, MAX_PARALLEL)
                .observeOn(main)
                .subscribe(a -> {
                    if (gen == generation) {
                        left.remove(a.source);
                        apply(a);
                    }
                }, e -> {
                    if (gen == generation) {
                        // not expected (every load ends in a page): settle the batch's unanswered sources
                        pending -= left.size();
                        loading.removeAll(left);
                        left.clear();
                        if (pending == 0) {
                            finish();
                        }
                    }
                }));
    }

    private void apply(Arrival<T> a) {
        Source<T> s = a.source;
        ProviderPage<T> page = a.page;
        pending--;
        loading.remove(s);
        statuses.put(s, page.status);
        int contributed = 0;
        boolean added = false;
        for (T item : page.items) {
            String k;
            try {
                k = key.apply(item);
            } catch (Exception e) {
                k = null;
            }
            if (k == null || seen.add(k)) {
                items.add(item);
                added = true;
                contributed++;
            }
        }
        Integer before = counts.get(s);
        counts.put(s, (before == null ? 0 : before) + contributed);
        if (page.isFailure()) {
            failed.add(s); // the cursor stays, so a retry asks for the same page
        } else {
            failed.remove(s);
            // a page of nothing but seen items ends the source, as in listings: some providers
            // repeat their last page for any page number past the end
            if (page.hasMore && (added || page.items.isEmpty())) {
                nextPage.put(s, nextPage.get(s) == null ? 2 : nextPage.get(s) + 1);
            } else {
                nextPage.remove(s);
            }
        }
        if (added) {
            listener.onItems(items());
        }
        // a source that found nothing for the query is asked once more, right away, in the other letter case
        // (SRCH-10): it no longer waits for the slowest site of the pass
        String variant = caseVariant(query);
        if (page.status == ProviderPage.Status.EMPTY && a.pageNo == 1 && query.equals(a.query) && variant != null
                && caseTried.add(s)) {
            queryOf.put(s, variant);
            nextPage.put(s, 1);
            // one search of the site, in two letter cases: its wait goes on from the first ask
            run(Collections.singletonList(s), startedAt.get(s));
            return;
        }
        listener.onSourceState(state(s));
        if (pending == 0) {
            finish();
        }
    }

    private void finish() {
        listener.onFinished(items(), failed.size());
    }

    /** The query in the other letter case: lower when it has capitals, otherwise capitalised; null when identical. */
    static String caseVariant(String q) {
        if (q == null || q.isEmpty()) {
            return null;
        }
        String lower = q.toLowerCase(Locale.ROOT);
        if (!lower.equals(q)) {
            return lower;
        }
        String capitalised = q.substring(0, 1).toUpperCase(Locale.ROOT) + q.substring(1);
        return capitalised.equals(q) ? null : capitalised;
    }
}
