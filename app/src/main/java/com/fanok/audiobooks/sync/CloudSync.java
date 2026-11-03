package com.fanok.audiobooks.sync;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.format.DateUtils;
import android.util.AtomicFile;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;
import com.fanok.audiobooks.AppThemes;
import com.fanok.audiobooks.Consts;
import com.fanok.audiobooks.R;
import com.fanok.audiobooks.model.AudioDBModel;
import com.fanok.audiobooks.model.BooksDBModel;
import com.fanok.audiobooks.model.FavoritePeople;
import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.pojo.EqualizerSettings;
import com.fanok.audiobooks.pojo.FavoritePersonPOJO;
import com.fanok.audiobooks.pojo.StorageUtil;
import com.fanok.audiobooks.pojo.TimeStartPOJO;
import com.fanok.audiobooks.presenter.BookPresenter;
import com.fanok.audiobooks.room.AppDatabase;
import com.fanok.audiobooks.room.BookBaseEntity;
import com.fanok.audiobooks.room.FavoriteEntity;
import com.fanok.audiobooks.room.HistoryEntity;
import com.fanok.audiobooks.search.Provider;
import com.fanok.audiobooks.search.Sources;
import com.fanok.audiobooks.service.Checkpoint;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.gson.Gson;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import okhttp3.CookieJar;
import okhttp3.OkHttpClient;

/**
 * Backup and sync of the library (favourite books, authors and readers), the history, the listening positions and
 * the settings through one file in the user's Google Drive or Yandex Disk (tasks/cloud-sync.md). A sync reads the
 * cloud copy, merges it with this device's state against the state of its last sync ({@link Snapshot}), applies the
 * difference here and uploads the result when the cloud lacks something. One at a time, on its own thread.
 */
public final class CloudSync {

    /** The connected cloud: "" (none), {@link #GOOGLE} or {@link #YANDEX}; set only after a successful sign-in. */
    public static final String PREF_SERVICE = "sync_service";
    public static final String GOOGLE = "google";
    public static final String YANDEX = "yandex";
    /** Broadcast in this app after a sync changed the book lists or the favourite people. */
    public static final String ACTION_CHANGED = "com.fanok.audiobooks.sync.CHANGED";
    /** Every preference of the sync starts so: the settings screen refreshes its status on them, none is synced. */
    public static final String PREF_PREFIX = "sync_";
    /** The Google account (its email), shown in the status. */
    private static final String PREF_ACCOUNT = "sync_account";
    /** The Yandex OAuth token. Default preferences stay out of Auto Backup and device transfer. */
    private static final String PREF_TOKEN = "sync_token";
    /** This installation's name in {@link Snapshot#seq}. */
    private static final String PREF_DEVICE = "sync_device";
    /** When the last sync succeeded (epoch ms). */
    private static final String PREF_AT = "sync_at";
    /** Why the last sync failed; "" after a success. */
    private static final String PREF_ERROR = "sync_error";

    private static final String TAG = "CloudSync";

    /** Automatic syncs start at least this long apart; a manual one at once. */
    private static final long GAP_MS = 20_000;
    /** After a position is saved: soon once the player pauses, now and then while it plays. */
    private static final long PAUSED_MS = 30_000, PLAYING_MS = 5 * 60_000;

    /** Synced default preferences holding text, then flags; also the search checkbox of every source. */
    private static final List<String> TEXTS = Arrays.asList("pref_theme", "books_adapter_layout_pref", "seek_step",
            "pref_sort_favorite", "pref_start_screen", Sources.PREF, Sources.PREF_MAX, Sources.SELECTED);
    /** The list settings among them, with the array of their values. */
    private static final Map<String, Integer> LISTS = new HashMap<>();
    private static final List<String> FLAGS = new ArrayList<>(Arrays.asList("history_procent", "toolbar_title",
            "toolbar_favorite", "toolbar_favorite_author", "toolbar_favorite_reader", "toolbar_share",
            "toolbar_speed", "toolbar_download", "toolbar_sleep_timer", "toolbar_equalizer", "toolbar_settings",
            "toolbar_open_site", "toolbar_main_screen", "toolbar_refresh", "autoPlayPref", "reproductionPref",
            "seekButtonsPref", "seekToPausePref", "rewind_back", "safeNavigationPref", "scrollToSelectedPref",
            "search_pref", "pref_autoload_last_book"));
    /** Synced settings of the {@link StorageUtil#STORAGE} file: a float and a JSON text. */
    private static final String SPEED = "speed", EQUALIZER = "equalizer";

    static {
        for (Provider provider : Provider.values()) {
            FLAGS.add(provider.prefKey);
        }
        LISTS.put("pref_theme", R.array.settings_them_values);
        LISTS.put("books_adapter_layout_pref", R.array.books_adapter_layout_values);
        LISTS.put("seek_step", R.array.seek_step_values);
        LISTS.put("pref_sort_favorite", R.array.settings_sort_values);
        LISTS.put("pref_start_screen", R.array.settings_start_screen_values);
    }

    private static final ScheduledExecutorService WORKER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "cloud-sync");
        t.setDaemon(true);
        return t;
    });

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** The planned sync, its number and when it is due (elapsedRealtime); guarded by the class. */
    private static ScheduledFuture<?> pending;
    private static int planned;
    private static long dueAt;
    /** When the last sync started (elapsedRealtime); guarded by the class. */
    private static long lastRun = -GAP_MS;

    private static OkHttpClient http;

    private CloudSync() {
    }

    // ---- scheduling ----

    /** An automatic sync in {@code delayMs} (if one is connected), unless one is already due by then. */
    public static synchronized void request(@NonNull Context context, long delayMs) {
        if (prefs(context).getString(PREF_SERVICE, "").isEmpty()) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        long at = Math.max(now + delayMs, lastRun + GAP_MS);
        if (pending != null && dueAt <= at) {
            return;
        }
        if (pending != null) {
            pending.cancel(false);
        }
        Context app = context.getApplicationContext();
        int number = ++planned;
        dueAt = at;
        pending = WORKER.schedule(() -> run(app, number), at - now, TimeUnit.MILLISECONDS);
    }

    /** «Синхронизировать сейчас». */
    public static void now(@NonNull Context context) {
        synchronized (CloudSync.class) {
            lastRun = -GAP_MS;
        }
        request(context, 0);
    }

    /** The player saved a position: the other devices learn it once it pauses, or every few minutes of playing. */
    public static void positionSaved(@NonNull Context context, boolean playing) {
        request(context, playing ? PLAYING_MS : PAUSED_MS);
    }

    private static void run(Context app, int number) {
        synchronized (CloudSync.class) {
            if (number == planned) { // a cancelled run that had already started must not forget the next one
                pending = null;
            }
            lastRun = SystemClock.elapsedRealtime();
        }
        SharedPreferences prefs = prefs(app);
        String service = prefs.getString(PREF_SERVICE, "");
        if (service.isEmpty()) {
            return;
        }
        ConnectivityManager network = app.getSystemService(ConnectivityManager.class);
        String error = "";
        try {
            if (network == null || network.getActiveNetwork() == null) {
                throw new IOException(app.getString(R.string.sync_offline));
            }
            sync(app, prefs, GOOGLE.equals(service)
                    ? new GoogleDrive(app, prefs.getString(PREF_ACCOUNT, ""), http())
                    : new YandexDisk(prefs.getString(PREF_TOKEN, ""), http()));
        } catch (Cloud.AuthException e) {
            error = app.getString(R.string.sync_sign_in_again);
        } catch (Snapshot.NewerException e) {
            error = app.getString(R.string.sync_update_app);
        } catch (Exception e) {
            Log.w(TAG, "Sync failed", e);
            error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        }
        if (!prefs.getString(PREF_SERVICE, "").isEmpty()) { // not disconnected meanwhile
            SharedPreferences.Editor edit = prefs.edit().putString(PREF_ERROR, error);
            if (error.isEmpty()) {
                edit.putLong(PREF_AT, System.currentTimeMillis());
            }
            edit.apply();
        }
    }

    // ---- the cycle ----

    private static void sync(Context app, SharedPreferences prefs, Cloud cloud) throws Exception {
        AtomicFile file = baseFile(app);
        Snapshot base = load(file);
        for (int attempt = 0; ; attempt++) {
            Cloud.Remote remote = cloud.read();
            // an unreadable cloud file is overwritten: what it held is lost anyway
            Snapshot theirs = remote == null ? null : Snapshot.parse(remote.data);
            Snapshot mine = read(app);
            Snapshot merged = Snapshot.merge(theirs != null && base != null && theirs.descends(base) ? base
                    : new Snapshot(), mine, theirs != null ? theirs : new Snapshot());
            // a torn clock is written anew, from what this device and the file saw
            boolean upload = theirs == null || theirs.torn || !merged.sameData(theirs);
            if (upload && !Objects.equals(remote == null ? null : remote.tag, cloud.tag())) {
                if (attempt == 2) {
                    throw new IOException("Sync file keeps changing: another device is syncing");
                }
                continue; // another device wrote meanwhile: merge that instead of overwriting it
            }
            Snapshot kept = apply(app, mine, merged);
            if (upload) {
                merged.count(device(prefs), theirs, base);
                merged.at = System.currentTimeMillis();
                cloud.write(merged.toBytes());
                kept.seq = merged.seq;
            } else {
                kept.seq = theirs.seq;
            }
            save(file, kept);
            return;
        }
    }

    /** This device's state now. */
    private static Snapshot read(Context app) {
        Snapshot s = new Snapshot();
        AppDatabase db = AppDatabase.getDatabase(app);
        List<FavoriteEntity> favorites = db.favoriteDao().getAll();
        for (int i = favorites.size() - 1; i >= 0; i--) { // newest first
            s.fav.put(favorites.get(i).urlBook, 0L);
            s.books.put(favorites.get(i).urlBook, book(favorites.get(i)));
        }
        for (HistoryEntity e : db.historyDao().getAll()) {
            s.hist.put(e.urlBook, e.openedAt);
            s.books.put(e.urlBook, book(e));
        }
        for (FavoritePersonPOJO p : FavoritePeople.get(app).backup()) {
            s.people.put(Snapshot.person(p.getKind(), FavoritePeople.key(p.getName())), p);
        }
        Map<String, String> chapters = new HashMap<>();
        for (TimeStartPOJO audio : new AudioDBModel(app).getAll()) {
            chapters.put(audio.getUrl(), audio.getName());
        }
        String prefix = Checkpoint.key("");
        for (Map.Entry<String, ?> e : storage(app).getAll().entrySet()) {
            Checkpoint c = e.getKey().startsWith(prefix) && e.getValue() instanceof String
                    ? Checkpoint.parse((String) e.getValue()) : null;
            if (c != null && e.getKey().equals(Checkpoint.key(c.bookUrl))) {
                s.pos.put(c.bookUrl, new Snapshot.Pos(c.chapterIndex, c.positionMs, c.at, chapters.get(c.bookUrl)));
            }
        }
        s.prefs = settings(prefs(app), storage(app));
        return s;
    }

    private static BookPOJO book(BookBaseEntity e) {
        BookPOJO book = e.toPojo();
        book.setDesc(null);
        return book;
    }

    /**
     * Writes what {@code merged} changed against {@code mine} and returns the state this device now shares with the
     * cloud: its next base. What cannot be applied here (a book without a title, a setting this version does not
     * take) stays in it as this device has it, so the next merge reads the cloud's value as the cloud's, not as one
     * deleted here. A value that changed here since {@code mine} was read (a book opened, a place played meanwhile)
     * is left as it is: it is newer, and the next sync takes it up.
     */
    private static Snapshot apply(Context app, Snapshot mine, Snapshot merged) throws Exception {
        Snapshot kept = merged.copy();
        AppDatabase db = AppDatabase.getDatabase(app);
        BooksDBModel books = new BooksDBModel(app);
        boolean[] changed = {false};
        db.runInTransaction(() -> {
            List<String> added = new ArrayList<>();
            for (String url : merged.fav.keySet()) {
                if (!mine.fav.containsKey(url)) {
                    added.add(url);
                }
            }
            for (int i = added.size() - 1; i >= 0; i--) { // oldest first: the newest ends on top
                String url = added.get(i);
                BookPOJO book = valid(url, merged.books.get(url));
                if (book == null) {
                    unapplied(kept.fav, mine.fav, url);
                } else if (!books.inFavorite(url)) {
                    books.addFavorite(book);
                    changed[0] = true;
                }
            }
            for (String url : mine.fav.keySet()) {
                if (!merged.fav.containsKey(url) && books.inFavorite(url)) {
                    books.removeFavorite(mine.books.get(url));
                    changed[0] = true;
                }
            }
            for (Map.Entry<String, Long> e : merged.hist.entrySet()) {
                String url = e.getKey();
                Long was = mine.hist.get(url);
                if (e.getValue().equals(was)) {
                    continue;
                }
                BookPOJO book = valid(url, merged.books.get(url));
                HistoryEntity now = db.historyDao().getByUrl(url);
                if (book == null) {
                    unapplied(kept.hist, mine.hist, url);
                } else if (Objects.equals(now == null ? null : now.openedAt, was)) {
                    books.addHistory(book, e.getValue());
                    changed[0] = true;
                }
            }
            for (Map.Entry<String, Long> e : mine.hist.entrySet()) {
                HistoryEntity now = merged.hist.containsKey(e.getKey()) ? null
                        : db.historyDao().getByUrl(e.getKey());
                if (now != null && now.openedAt == e.getValue()) {
                    books.removeHistory(mine.books.get(e.getKey()));
                    changed[0] = true;
                }
            }
        });

        List<FavoritePersonPOJO> put = new ArrayList<>(), gone = new ArrayList<>();
        for (Map.Entry<String, FavoritePersonPOJO> e : merged.people.entrySet()) {
            FavoritePersonPOJO p = e.getValue();
            if (mine.people.containsKey(e.getKey())) {
                continue;
            }
            // a person keyed by another version's name rules would come back here under another key
            String key = FavoritePeople.key(p.getName());
            if ((p.getKind() == FavoritePeople.AUTHOR || p.getKind() == FavoritePeople.READER) && key != null
                    && e.getKey().equals(Snapshot.person(p.getKind(), key))) {
                put.add(p);
            } else {
                unapplied(kept.people, mine.people, e.getKey());
            }
        }
        for (Map.Entry<String, FavoritePersonPOJO> e : mine.people.entrySet()) {
            if (!merged.people.containsKey(e.getKey())) {
                gone.add(e.getValue());
            }
        }
        if (!put.isEmpty() || !gone.isEmpty()) {
            FavoritePeople.get(app).sync(put, gone);
            changed[0] = true;
        }

        SharedPreferences storage = storage(app);
        SharedPreferences.Editor places = storage.edit();
        Map<String, Snapshot.Pos> moved = new HashMap<>();
        for (Map.Entry<String, Snapshot.Pos> e : merged.pos.entrySet()) {
            String url = e.getKey();
            Snapshot.Pos to = e.getValue(), was = mine.pos.get(url);
            if (to.equals(was)) {
                continue;
            }
            // the place of a book neither listed nor played here would only fill the storage
            if (url.isEmpty() || to.c < 0 || to.ms < 0
                    || was == null && !merged.fav.containsKey(url) && !merged.hist.containsKey(url)) {
                unapplied(kept.pos, mine.pos, url);
                continue;
            }
            Checkpoint now = Checkpoint.parse(storage.getString(Checkpoint.key(url), null));
            if (Objects.equals(now == null ? null : new Snapshot.Pos(now.chapterIndex, now.positionMs, 0, null),
                    was)) {
                // the player holding this book picks the place up at its next Play (MediaPlayerService.movedBySync)
                places.putString(Checkpoint.key(url), new Checkpoint(url, to.c, to.ms, to.t).serialize());
                moved.put(url, to);
            }
        }
        places.apply();
        AudioDBModel audio = new AudioDBModel(app);
        for (Map.Entry<String, Snapshot.Pos> e : moved.entrySet()) {
            String title = e.getValue().n;
            if (title != null && !title.isEmpty()) { // the book lists show the chapter's title and the seconds in it
                audio.add(e.getKey(), title);
                audio.setTime(e.getKey(), (int) (e.getValue().ms / 1000));
            }
        }

        applySettings(app, mine.prefs, merged.prefs, kept.prefs);
        if (changed[0]) {
            app.sendBroadcast(new Intent(ACTION_CHANGED).setPackage(app.getPackageName()));
        }
        return kept;
    }

    /** {@code key} stays in {@code kept} as this device has it: the cloud's value is not taken here. */
    private static <V> void unapplied(Map<String, V> kept, Map<String, V> mine, String key) {
        V value = mine.get(key);
        if (value == null) {
            kept.remove(key);
        } else {
            kept.put(key, value);
        }
    }

    /** {@code book} when a row of {@code url} can be made of it. */
    @Nullable
    private static BookPOJO valid(String url, @Nullable BookPOJO book) {
        return book != null && !book.isNull() && url.equals(book.getUrl()) && book.getName() != null
                && !book.getName().trim().isEmpty() ? book : null;
    }

    // ---- settings ----

    /** The synced settings that are set, as text. */
    private static Map<String, String> settings(SharedPreferences main, SharedPreferences storage) {
        Map<String, String> out = new TreeMap<>();
        Map<String, ?> values = main.getAll();
        for (String key : TEXTS) {
            if (values.get(key) instanceof String) {
                out.put(key, (String) values.get(key));
            }
        }
        for (String key : FLAGS) {
            if (values.get(key) instanceof Boolean) {
                out.put(key, values.get(key).toString());
            }
        }
        values = storage.getAll();
        if (values.get(SPEED) instanceof Float) {
            out.put(SPEED, values.get(SPEED).toString());
        }
        if (values.get(EQUALIZER) instanceof String) {
            out.put(EQUALIZER, (String) values.get(EQUALIZER));
        }
        return out;
    }

    /**
     * Writes the settings {@code to} changed against {@code was}, each with its type, unless it changed here
     * meanwhile; a value this version does not take stays in {@code kept} as it is here. Then what holds a setting
     * in memory (the sources, the night mode, the speed) follows it.
     */
    private static void applySettings(Context app, Map<String, String> was, Map<String, String> to,
            Map<String, String> kept) {
        SharedPreferences main = prefs(app), storage = storage(app);
        Map<String, String> now = settings(main, storage);
        SharedPreferences.Editor mainEdit = main.edit(), storageEdit = storage.edit();
        Set<String> written = new HashSet<>();
        for (Map.Entry<String, String> e : to.entrySet()) {
            String key = e.getKey(), value = e.getValue();
            if (value.equals(was.get(key))) {
                continue;
            }
            if (!allowed(app, key, value)) {
                unapplied(kept, was, key);
                continue;
            }
            if (!Objects.equals(now.get(key), was.get(key))) {
                continue;
            }
            if (FLAGS.contains(key)) {
                mainEdit.putBoolean(key, Boolean.parseBoolean(value));
            } else if (key.equals(SPEED)) {
                storageEdit.putFloat(key, speed(value));
            } else if (key.equals(EQUALIZER)) {
                storageEdit.putString(key, value); // the player loads it when it starts
            } else {
                mainEdit.putString(key, value);
            }
            written.add(key);
        }
        if (written.isEmpty()) {
            return;
        }
        mainEdit.apply();
        storageEdit.apply();
        MAIN.post(() -> {
            if (written.contains(Sources.PREF) || written.contains(Sources.PREF_MAX)
                    || written.contains(Sources.SELECTED)) {
                Sources.load(main); // may change the selection: it must stay an active source
                String selected = main.getString(Sources.SELECTED, null);
                if (selected != null) {
                    Consts.setSOURCE(selected);
                }
            }
            if (written.contains("pref_theme")) {
                AppThemes.applyNightMode(app);
            }
            if (written.contains(SPEED)) { // as stored now: the user may have changed it again meanwhile
                BookPresenter.setSpeedWithoutBroadcast(storage.getFloat(SPEED, 1));
            }
        });
    }

    /** Whether this version takes {@code value} for the synced setting {@code key}. */
    private static boolean allowed(Context app, String key, String value) {
        Integer values = LISTS.get(key);
        if (values != null) { // one of the list's values: the start screen, for one, is read as a number
            return Arrays.asList(app.getResources().getStringArray(values)).contains(value);
        }
        if (FLAGS.contains(key)) {
            return value.equals("true") || value.equals("false");
        }
        switch (key) {
            case SPEED:
                return speed(value) > 0;
            case EQUALIZER:
                return equalizer(value);
            case Sources.PREF:
                return true; // Sources.load keeps the known sources of any text
            case Sources.PREF_MAX:
                try {
                    int max = Integer.parseInt(value);
                    return max >= Sources.MIN_LIMIT && max <= Sources.MAX_LIMIT;
                } catch (NumberFormatException e) {
                    return false;
                }
            case Sources.SELECTED:
                for (Provider provider : Provider.values()) {
                    if (provider.prefValue.equals(value)) {
                        return true;
                    }
                }
                return false;
            default:
                return false; // not synced by this version
        }
    }

    private static float speed(String value) {
        try {
            float speed = Float.parseFloat(value);
            return speed > 0 && speed <= 4 ? speed : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean equalizer(String value) {
        try {
            return new Gson().fromJson(value, EqualizerSettings.class) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---- connection ----

    /** Whether {@code service} can be offered here. */
    public static boolean available(@NonNull Context context, @NonNull String service) {
        return GOOGLE.equals(service) ? GoogleDrive.available(context) : YANDEX.equals(service) && YandexDisk.available();
    }

    /**
     * Google sign-in: without a grant yet, {@code consent} gets the screen to show, whose result goes to
     * {@link #googleConsented}; with one, the account is connected at once.
     */
    public static void signInGoogle(@NonNull Context context, @NonNull Consumer<PendingIntent> consent) {
        Context app = context.getApplicationContext();
        GoogleDrive.authorize(context, null)
                .addOnSuccessListener(result -> {
                    if (result.hasResolution() && result.getPendingIntent() != null) {
                        consent.accept(result.getPendingIntent());
                    } else {
                        connectGoogle(app, result);
                    }
                })
                .addOnFailureListener(e -> failed(app, e));
    }

    /** The result of the consent screen; {@code data} is null when the user cancelled. */
    public static void googleConsented(@NonNull Context context, @Nullable Intent data) {
        if (data == null) {
            return;
        }
        Context app = context.getApplicationContext();
        try {
            connectGoogle(app, Identity.getAuthorizationClient(app).getAuthorizationResultFromIntent(data));
        } catch (Exception e) {
            failed(app, e);
        }
    }

    private static void connectGoogle(Context app, AuthorizationResult result) {
        String token = result.getAccessToken();
        if (token == null) {
            failed(app, new IOException("No access granted"));
            return;
        }
        WORKER.execute(() -> {
            try {
                connect(app, GOOGLE, GoogleDrive.email(http(), token), null);
            } catch (IOException e) {
                failed(app, e);
            }
        });
    }

    /** A new PKCE secret for {@link #yandexPage} and {@link #signInYandex}. */
    @NonNull
    public static String yandexSecret() {
        return YandexDisk.verifier();
    }

    /** The Yandex page that asks the user's consent and then shows the code to type in. */
    @NonNull
    public static Uri yandexPage(@NonNull String secret) {
        return YandexDisk.authUrl(secret);
    }

    /** Connects Yandex Disk with the {@code code} the user typed in. */
    public static void signInYandex(@NonNull Context context, @NonNull String code, @NonNull String secret) {
        Context app = context.getApplicationContext();
        WORKER.execute(() -> {
            try {
                connect(app, YANDEX, null, YandexDisk.token(http(), code, secret));
            } catch (IOException e) {
                failed(app, e);
            }
        });
    }

    /** On the worker: a new connection starts from a union, as the first sync of a device. */
    private static void connect(Context app, String service, @Nullable String account, @Nullable String token) {
        baseFile(app).delete();
        prefs(app).edit().putString(PREF_SERVICE, service).putString(PREF_ACCOUNT, account)
                .putString(PREF_TOKEN, token).putString(PREF_ERROR, "").remove(PREF_AT).apply();
        now(app);
    }

    /** Stops syncing; the cloud file stays. */
    public static void disconnect(@NonNull Context context) {
        Context app = context.getApplicationContext();
        // Google's grant is not revoked: that would withdraw it from the user's other devices too
        WORKER.execute(() -> {
            baseFile(app).delete();
            prefs(app).edit().putString(PREF_SERVICE, "").remove(PREF_ACCOUNT).remove(PREF_TOKEN).remove(PREF_ERROR)
                    .remove(PREF_AT).apply();
        });
    }

    private static void failed(Context app, Exception e) {
        Log.w(TAG, "Sign-in failed", e);
        String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        MAIN.post(() -> Toast.makeText(app, app.getString(R.string.sync_sign_in_failed, reason), Toast.LENGTH_LONG)
                .show());
    }

    /** Whether the last sync succeeded: choosing the connected cloud again has nothing to mend then. */
    public static boolean working(@NonNull Context context) {
        return prefs(context).getString(PREF_ERROR, "").isEmpty();
    }

    /** The status line: the account and the last sync, or why it failed. */
    @NonNull
    public static String status(@NonNull Context context) {
        SharedPreferences prefs = prefs(context);
        String error = prefs.getString(PREF_ERROR, "");
        if (!error.isEmpty()) {
            return context.getString(R.string.sync_failed, error);
        }
        long at = prefs.getLong(PREF_AT, 0);
        String last = context.getString(R.string.sync_last, at == 0 ? context.getString(R.string.sync_never)
                : DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME));
        String account = prefs.getString(PREF_ACCOUNT, null);
        return account == null || account.isEmpty() ? last : account + "\n" + last;
    }

    // ---- storage ----

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    private static SharedPreferences storage(Context context) {
        return context.getSharedPreferences(StorageUtil.STORAGE, Context.MODE_PRIVATE);
    }

    private static String device(SharedPreferences prefs) {
        String device = prefs.getString(PREF_DEVICE, null);
        if (device == null) {
            device = UUID.randomUUID().toString().substring(0, 8);
            prefs.edit().putString(PREF_DEVICE, device).apply();
        }
        return device;
    }

    /** The state of the last sync, kept out of backups: a restored app must not take it for its own. */
    private static AtomicFile baseFile(Context context) {
        return new AtomicFile(new File(context.getNoBackupFilesDir(), "sync-base.json.gz"));
    }

    @Nullable
    private static Snapshot load(AtomicFile file) {
        try {
            return Snapshot.parse(file.readFully());
        } catch (IOException | Snapshot.NewerException e) {
            return null;
        }
    }

    private static void save(AtomicFile file, Snapshot snapshot) throws IOException {
        FileOutputStream out = file.startWrite();
        try {
            out.write(snapshot.toBytes());
            file.finishWrite(out);
        } catch (IOException e) {
            file.failWrite(out);
            throw e;
        }
    }

    /** Straight to the cloud: never through the app's proxies for the sites, which would see the tokens. */
    private static synchronized OkHttpClient http() {
        if (http == null) {
            http = new OkHttpClient.Builder()
                    .proxy(Proxy.NO_PROXY)
                    .cookieJar(CookieJar.NO_COOKIES)
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .callTimeout(2, TimeUnit.MINUTES)
                    .build();
        }
        return http;
    }
}
