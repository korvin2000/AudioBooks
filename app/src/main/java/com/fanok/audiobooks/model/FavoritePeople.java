package com.fanok.audiobooks.model;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.Nullable;
import com.fanok.audiobooks.pojo.FavoritePersonPOJO;
import com.fanok.audiobooks.room.AppDatabase;
import com.fanok.audiobooks.room.FavoritePersonDao;
import com.fanok.audiobooks.room.FavoritePersonEntity;
import com.fanok.audiobooks.search.Provider;
import com.fanok.audiobooks.search.TextNorm;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Favourite authors and readers: one row per person and kind, whatever site it came from. A person is its
 * {@link #key}, so "Кинг Стивен" and "Стивен Кинг" are one favourite. The keys of each kind live in memory (loaded
 * once, on first use) and change at once, so the UI reads them on the main thread; the DB writes follow in order on
 * the class's own thread, and the blocking reads wait for them.
 */
public final class FavoritePeople {
    public static final int AUTHOR = 0;
    public static final int READER = 1;

    private static final String TAG = "FavoritePeople";
    /** The separators of TextNorm.persons, kept here because the names are split as written. */
    private static final Pattern PEOPLE_BREAKS = Pattern.compile("\\s*(?:[,;/&]|\\s\\s*и\\s+)\\s*");
    private static final Pattern AND_OTHERS = Pattern.compile("(?:\\s+и)?\\s+др\\.?\\s*$");

    /** Removed rows kept for {@link #undoRemove}, at most. */
    private static final int UNDO_ROWS = 16;

    private static volatile FavoritePeople sInstance;

    private final AppDatabase mDb;
    private final FavoritePersonDao mDao;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mWrites = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "favorite-people");
        t.setDaemon(true);
        return t;
    });
    private final CopyOnWriteArrayList<Listener> mListeners = new CopyOnWriteArrayList<>();
    /** The last removed rows by kind and key, for {@link #undoRemove}; used on the write thread only. */
    private final Map<String, FavoritePersonEntity> mRemoved = new LinkedHashMap<String, FavoritePersonEntity>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, FavoritePersonEntity> eldest) {
            return size() > UNDO_ROWS;
        }
    };
    /** Keys per kind, null until first used (and after {@link #reload}); guarded by {@code this}. */
    private Set<String> mAuthors;
    private Set<String> mReaders;

    public interface Listener {
        /** Called on the main thread. */
        void onFavoritePeopleChanged(int kind);
    }

    public static final class Person {
        public final int kind;
        public final String name;
        public final String key;
        /** Site listing URLs, at most one per Provider. */
        public final List<String> urls;
        public final long addedAt;

        public Person(int kind, String name, List<String> urls, long addedAt) {
            this.kind = kind;
            this.name = name;
            this.key = key(name);
            List<String> copy = new ArrayList<>();
            if (urls != null) {
                copy.addAll(urls);
            }
            this.urls = Collections.unmodifiableList(copy);
            this.addedAt = addedAt;
        }

        /** This person's listing page on {@code provider}'s site, or null. */
        @Nullable
        public String urlFor(Provider provider) {
            for (String url : urls) {
                if (provider != null && Provider.ofUrl(url) == provider) {
                    return url;
                }
            }
            return null;
        }
    }

    private FavoritePeople(Context context) {
        mDb = AppDatabase.getDatabase(context);
        mDao = mDb.favoritePersonDao();
        // before any other write: rows saved under a key an older TextNorm.person made are keyed anew
        write(() -> mDb.runInTransaction(() -> {
            for (int kind : new int[]{AUTHOR, READER}) {
                List<Integer> stale = new ArrayList<>();
                List<FavoritePersonEntity> healed = rekey(mDao.getAll(kind), stale);
                for (int id : stale) {
                    mDao.deleteId(id);
                }
                for (FavoritePersonEntity e : healed) {
                    mDao.insert(e);
                }
            }
        }));
    }

    /** The process singleton. */
    public static FavoritePeople get(Context context) {
        if (sInstance == null) {
            synchronized (FavoritePeople.class) {
                if (sInstance == null) {
                    sInstance = new FavoritePeople(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    /** Identity of a person: TextNorm.person(name) words + "|" + initials; null when the name has no letters. */
    @Nullable
    public static String key(@Nullable String name) {
        TextNorm.Person p = TextNorm.person(name);
        return p.isEmpty() ? null : p.words + "|" + p.initials;
    }

    /**
     * Splits a free-text people field ("Иванов, Петров и др.") into display names, in order, as written (trimmed),
     * with TextNorm.persons' separators; parts without letters and repeats of one person are dropped.
     */
    public static List<String> split(@Nullable String field) {
        List<String> out = new ArrayList<>();
        if (field == null) {
            return out;
        }
        String s = AND_OTHERS.matcher(field.trim()).replaceAll("");
        Set<String> seen = new HashSet<>();
        for (String part : PEOPLE_BREAKS.split(" " + s + " ")) {
            String name = part.trim();
            String key = key(name);
            if (key != null && seen.add(key)) {
                out.add(name);
            }
        }
        return out;
    }

    /**
     * {@code urls} plus {@code url}, newline-joined: only pages of a known site, the first one per site wins, blank and
     * multi-line values dropped.
     */
    public static String joinUrls(@Nullable List<String> urls, @Nullable String url) {
        List<String> all = new ArrayList<>();
        if (urls != null) {
            all.addAll(urls);
        }
        all.add(url);
        Set<Provider> sites = new HashSet<>();
        StringBuilder out = new StringBuilder();
        for (String u : all) {
            String v = u == null ? "" : u.trim();
            if (v.isEmpty() || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
                continue;
            }
            Provider p = Provider.ofUrl(v);
            if (p != null && sites.add(p)) {
                if (out.length() > 0) {
                    out.append('\n');
                }
                out.append(v);
            }
        }
        return out.toString();
    }

    static List<String> splitUrls(String urls) {
        List<String> out = new ArrayList<>();
        for (String u : urls == null ? new String[0] : urls.split("\n")) {
            if (!u.trim().isEmpty()) {
                out.add(u.trim());
            }
        }
        return out;
    }

    // ---- main-thread safe, from memory ----

    public boolean has(int kind, @Nullable String name) {
        String key = key(name);
        if (key == null) {
            return false;
        }
        synchronized (this) {
            return keys(kind).contains(key);
        }
    }

    public int count(int kind) {
        synchronized (this) {
            return keys(kind).size();
        }
    }

    /** Adds or removes; returns the new state. The DB write runs on the class's own single-thread executor. */
    public boolean toggle(int kind, String name, @Nullable String url) {
        String key = key(name);
        if (key == null) {
            return false;
        }
        boolean added;
        synchronized (this) {
            Set<String> keys = keys(kind);
            added = keys.add(key);
            if (!added) {
                keys.remove(key);
            }
        }
        if (added) {
            write(() -> insert(kind, name, key, url));
        } else {
            write(() -> delete(kind, key));
        }
        changed(kind);
        return added;
    }

    /** {@code url} = the site's listing page of this person, if known; an existing favourite only learns it. */
    public void add(int kind, String name, @Nullable String url) {
        String key = key(name);
        if (key == null) {
            return;
        }
        boolean added;
        synchronized (this) {
            added = keys(kind).add(key);
        }
        write(() -> insert(kind, name, key, url));
        if (added) {
            changed(kind);
        }
    }

    public void remove(int kind, String name) {
        String key = key(name);
        if (key == null) {
            return;
        }
        boolean removed;
        synchronized (this) {
            removed = keys(kind).remove(key);
        }
        if (removed) {
            write(() -> delete(kind, key));
            changed(kind);
        }
    }

    /**
     * Undoes the latest {@link #remove} (or toggle off) of the person: its row comes back with its URLs and addedAt;
     * without a removed row, as {@link #add} with no URL.
     */
    public void undoRemove(int kind, String name) {
        String key = key(name);
        if (key == null) {
            return;
        }
        boolean added;
        synchronized (this) {
            added = keys(kind).add(key);
        }
        write(() -> {
            FavoritePersonEntity e = mRemoved.remove(kind + "|" + key);
            if (e != null && mDao.get(kind, key) == null) {
                mDao.insert(e);
            } else {
                insert(kind, name, key, null);
            }
        });
        if (added) {
            changed(kind);
        }
    }

    /** Remembers a site page for a person who IS a favourite (one URL per site, Provider.ofUrl; first one wins). */
    public void learnUrl(int kind, String name, String url) {
        String key = key(name);
        if (key == null || url == null || Provider.ofUrl(url) == null) {
            return;
        }
        synchronized (this) {
            if (!keys(kind).contains(key)) {
                return;
            }
        }
        write(() -> {
            FavoritePersonEntity e = mDao.get(kind, key);
            if (e != null) {
                String urls = joinUrls(splitUrls(e.urls), url);
                if (!urls.equals(e.urls)) {
                    mDao.updateUrls(e.id, urls);
                }
            }
        });
    }

    public void clear(int kind) {
        synchronized (this) {
            keys(kind).clear();
        }
        write(() -> mDao.deleteKind(kind));
        changed(kind);
    }

    // ---- blocking reads: call off the main thread ----

    /** Newest addedAt first. */
    public List<Person> all(int kind) {
        List<Person> out = new ArrayList<>();
        for (FavoritePersonEntity e : read(() -> mDao.getAll(kind))) {
            out.add(person(e));
        }
        return out;
    }

    /** By key. */
    @Nullable
    public Person find(int kind, String name) {
        String key = key(name);
        if (key == null) {
            return null;
        }
        FavoritePersonEntity e = read(() -> mDao.get(kind, key));
        return e == null ? null : person(e);
    }

    /** Both kinds for a backup, newest first. Blocking. */
    public ArrayList<FavoritePersonPOJO> backup() {
        ArrayList<FavoritePersonPOJO> out = new ArrayList<>();
        for (int kind : new int[]{AUTHOR, READER}) {
            for (Person p : all(kind)) {
                out.add(new FavoritePersonPOJO(p.kind, p.name, p.urls, p.addedAt));
            }
        }
        return out;
    }

    /**
     * Replaces every row with {@code rows} (a validated backup, newest first), keeping addedAt and the URLs. Run
     * inside the caller's restore transaction, then call {@link #reload}.
     */
    public static void replaceAll(AppDatabase db, List<FavoritePersonPOJO> rows) {
        FavoritePersonDao dao = db.favoritePersonDao();
        dao.deleteAll();
        for (int i = rows.size() - 1; i >= 0; i--) { // oldest first: a repeated person keeps its newest row
            FavoritePersonPOJO row = rows.get(i);
            String key = key(row.getName());
            if (key == null) {
                continue;
            }
            FavoritePersonEntity e = new FavoritePersonEntity();
            e.kind = row.getKind();
            e.name = row.getName().trim();
            e.personKey = key;
            e.urls = joinUrls(row.getUrls(), null);
            e.addedAt = row.getAddedAt();
            dao.insert(e);
        }
    }

    /**
     * A cloud sync's changes in one transaction on the write thread, then {@link #reload}: {@code put} rows are added
     * with their URLs and addedAt (a person already here stays as is), {@code gone} rows are removed. Blocking.
     */
    public void sync(List<FavoritePersonPOJO> put, List<FavoritePersonPOJO> gone)
            throws ExecutionException, InterruptedException {
        mWrites.submit(() -> mDb.runInTransaction(() -> {
            for (FavoritePersonPOJO row : gone) {
                String key = key(row.getName());
                if (key != null) {
                    delete(row.getKind(), key);
                }
            }
            for (FavoritePersonPOJO row : put) {
                String key = key(row.getName());
                if (key == null || row.getKind() != AUTHOR && row.getKind() != READER
                        || mDao.get(row.getKind(), key) != null) {
                    continue;
                }
                FavoritePersonEntity e = new FavoritePersonEntity();
                e.kind = row.getKind();
                e.name = row.getName().trim();
                e.personKey = key;
                e.urls = joinUrls(row.getUrls(), null);
                e.addedAt = row.getAddedAt();
                mDao.insert(e);
            }
        })).get();
        reload();
    }

    /** Forgets the in-memory keys (the table was replaced behind this class, as by a restore) and tells the listeners. */
    public void reload() {
        synchronized (this) {
            mAuthors = null;
            mReaders = null;
        }
        changed(AUTHOR);
        changed(READER);
    }

    public void addListener(Listener l) {
        if (l != null) {
            mListeners.addIfAbsent(l);
        }
    }

    public void removeListener(Listener l) {
        mListeners.remove(l);
    }

    // ---- internals ----

    /** The keys of {@code kind}, loaded on first use; call holding {@code this}. */
    private Set<String> keys(int kind) {
        if (kind == AUTHOR) {
            if (mAuthors == null) {
                mAuthors = loadKeys(AUTHOR);
            }
            return mAuthors;
        }
        if (kind == READER) {
            if (mReaders == null) {
                mReaders = loadKeys(READER);
            }
            return mReaders;
        }
        throw new IllegalArgumentException("Unknown kind " + kind);
    }

    /** The keys of the saved names by today's {@link #key}, whatever key a row was saved under. */
    private Set<String> loadKeys(int kind) {
        Set<String> keys = new HashSet<>();
        for (String name : mDao.getNames(kind)) {
            String key = key(name);
            if (key != null) {
                keys.add(key);
            }
        }
        return keys;
    }

    /**
     * Rows of one kind, newest first (as getAll), healed to today's {@link #key}: one row per key where a stored key
     * is stale or two rows share one, the oldest (its id, name and addedAt) with the URLs of all (the oldest's first).
     * {@code replaced} gets the ids of the rows the healed ones replace; the others stay as they are.
     */
    static List<FavoritePersonEntity> rekey(List<FavoritePersonEntity> rows, List<Integer> replaced) {
        Map<String, List<FavoritePersonEntity>> byKey = new LinkedHashMap<>();
        for (int i = rows.size() - 1; i >= 0; i--) { // oldest first
            FavoritePersonEntity e = rows.get(i);
            String key = key(e.name);
            if (key != null) {
                List<FavoritePersonEntity> same = byKey.get(key);
                if (same == null) {
                    same = new ArrayList<>();
                    byKey.put(key, same);
                }
                same.add(e);
            }
        }
        List<FavoritePersonEntity> out = new ArrayList<>();
        for (Map.Entry<String, List<FavoritePersonEntity>> group : byKey.entrySet()) {
            List<FavoritePersonEntity> same = group.getValue();
            FavoritePersonEntity oldest = same.get(0);
            if (same.size() == 1 && group.getKey().equals(oldest.personKey)) {
                continue;
            }
            List<String> urls = new ArrayList<>();
            for (FavoritePersonEntity e : same) {
                replaced.add(e.id);
                urls.addAll(splitUrls(e.urls));
            }
            FavoritePersonEntity healed = new FavoritePersonEntity();
            healed.id = oldest.id;
            healed.kind = oldest.kind;
            healed.name = oldest.name;
            healed.personKey = group.getKey();
            healed.urls = joinUrls(urls, null);
            healed.addedAt = oldest.addedAt;
            out.add(healed);
        }
        return out;
    }

    /** On the write thread: deletes the row, kept for {@link #undoRemove}. */
    private void delete(int kind, String key) {
        FavoritePersonEntity e = mDao.get(kind, key);
        if (e != null) {
            mRemoved.put(kind + "|" + key, e);
            mDao.delete(kind, key);
        }
    }

    /** On the write thread: a new row, or the existing one learns {@code url}. */
    private void insert(int kind, String name, String key, @Nullable String url) {
        FavoritePersonEntity e = mDao.get(kind, key);
        if (e != null) {
            String urls = joinUrls(splitUrls(e.urls), url);
            if (!urls.equals(e.urls)) {
                mDao.updateUrls(e.id, urls);
            }
            return;
        }
        e = new FavoritePersonEntity();
        e.kind = kind;
        e.name = name.trim();
        e.personKey = key;
        e.urls = joinUrls(null, url);
        e.addedAt = System.currentTimeMillis();
        mDao.insert(e);
    }

    private static Person person(FavoritePersonEntity e) {
        return new Person(e.kind, e.name, splitUrls(e.urls), e.addedAt);
    }

    private void write(Runnable task) {
        mWrites.execute(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                Log.e(TAG, "Write failed", e);
            }
        });
    }

    /** Runs {@code task} after the pending writes; a failed or interrupted read throws (a backup must not pass as empty). */
    private <T> T read(Callable<T> task) {
        try {
            return mWrites.submit(task).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e.getCause());
        }
    }

    private void changed(int kind) {
        mMain.post(() -> {
            for (Listener l : mListeners) {
                l.onFavoritePeopleChanged(kind);
            }
        });
    }
}
