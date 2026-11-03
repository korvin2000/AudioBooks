package com.fanok.audiobooks.sync;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.format.DateUtils;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;
import com.fanok.audiobooks.R;
import com.fanok.audiobooks.service.MediaPlayerService;
import java.io.IOException;
import java.net.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import okhttp3.CookieJar;
import okhttp3.OkHttpClient;

/**
 * Backup and sync of the library, the history, the listening positions and the settings through a file in the
 * user's cloud (tasks/cloud-sync.md). The module's facade; the app knows only {@link #install} and the settings
 * screen ({@link SyncSettingsFragment}). It decides when to sync (the app comes to the foreground or goes to the
 * background, the player saved a place, the user asks), runs one {@link SyncRun} at a time on its own thread and
 * keeps the connection: which of the {@link #SERVICES}, which account, how the last sync went.
 */
public final class CloudSync {

    /** Every preference of the sync starts so; none is synced, and the default file stays out of backups. */
    static final String PREF_PREFIX = "sync_";
    /** The connected cloud's {@link CloudService#id}; "" when none. */
    private static final String PREF_SERVICE = PREF_PREFIX + "service";
    /** The cloud the user last chose to sign in to: a sign-in of another one that ends later is stale. */
    private static final String PREF_CHOSEN = PREF_PREFIX + "chosen";
    /** The account: the name the status shows, the secret the cloud's API needs. */
    private static final String PREF_ACCOUNT = PREF_PREFIX + "account", PREF_TOKEN = PREF_PREFIX + "token";
    /** When the last sync succeeded (epoch ms); why the last one failed ("" after a success). */
    private static final String PREF_AT = PREF_PREFIX + "at", PREF_ERROR = PREF_PREFIX + "error";

    /** The clouds this build can sync with, in the order offered. */
    static final List<CloudService> SERVICES = Collections.unmodifiableList(Arrays.<CloudService>asList(
            new GoogleDrive.Service(), new YandexDisk.Service()));

    private static final String TAG = "CloudSync";

    /** Automatic syncs start at least this long apart; a manual one at once. */
    private static final long GAP_MS = 20_000;
    /** After the player saved a place: soon once it pauses, now and then while it plays. */
    private static final long PAUSED_MS = 30_000, PLAYING_MS = 5 * 60_000;

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

    /** Starts following the app: call once, at process start. */
    public static void install(@NonNull Application app) {
        app.registerActivityLifecycleCallbacks(new Foreground());
        MediaPlayerService.setCheckpointListener((checkpoint, playing) ->
                request(app, playing ? PLAYING_MS : PAUSED_MS));
    }

    // ---- when ----

    /** An automatic sync in {@code delayMs} (if a cloud is connected), unless one is already due by then. */
    static synchronized void request(@NonNull Context context, long delayMs) {
        if (connected(context) == null) {
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
    static void now(@NonNull Context context) {
        synchronized (CloudSync.class) {
            lastRun = -GAP_MS;
        }
        request(context, 0);
    }

    private static void run(Context app, int number) {
        synchronized (CloudSync.class) {
            if (number == planned) { // a cancelled run that had already started must not forget the next one
                pending = null;
            }
            lastRun = SystemClock.elapsedRealtime();
        }
        SharedPreferences prefs = prefs(app);
        CloudService service = connected(app);
        if (service == null) {
            return;
        }
        ConnectivityManager network = app.getSystemService(ConnectivityManager.class);
        String error = "";
        try {
            if (network == null || network.getActiveNetwork() == null) {
                throw new IOException(app.getString(R.string.sync_offline));
            }
            CloudAccount account = new CloudAccount(prefs.getString(PREF_ACCOUNT, null),
                    prefs.getString(PREF_TOKEN, null));
            SyncRun.run(app, service.open(app, account, http()));
        } catch (Cloud.AuthException e) {
            error = app.getString(R.string.sync_sign_in_again);
        } catch (Snapshot.NewerException e) {
            error = app.getString(R.string.sync_update_app);
        } catch (Exception e) {
            Log.w(TAG, "Sync failed", e);
            error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        }
        if (connected(app) == service) { // not disconnected meanwhile
            SharedPreferences.Editor edit = prefs.edit().putString(PREF_ERROR, error);
            if (error.isEmpty()) {
                edit.putLong(PREF_AT, System.currentTimeMillis());
            }
            edit.apply();
        }
    }

    /**
     * The app comes to the foreground (to learn what the other devices did since) or goes to the background (to
     * tell them what was done here, before the process may be gone); a recreated screen is neither.
     */
    private static final class Foreground implements Application.ActivityLifecycleCallbacks {
        private int started;
        private boolean recreating;

        @Override
        public void onActivityStarted(@NonNull Activity activity) {
            if (started++ == 0 && !recreating) {
                request(activity, 0);
            }
            recreating = false;
        }

        @Override
        public void onActivityStopped(@NonNull Activity activity) {
            recreating = activity.isChangingConfigurations();
            if (--started == 0 && !recreating) {
                request(activity, 0);
            }
        }

        @Override
        public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
        }

        @Override
        public void onActivityResumed(@NonNull Activity activity) {
        }

        @Override
        public void onActivityPaused(@NonNull Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
        }

        @Override
        public void onActivityDestroyed(@NonNull Activity activity) {
        }
    }

    // ---- with what ----

    /** The connected cloud; null when none, or one this build no longer has. */
    @Nullable
    static CloudService connected(@NonNull Context context) {
        return find(prefs(context).getString(PREF_SERVICE, ""));
    }

    @Nullable
    static CloudService find(@NonNull String id) {
        for (CloudService service : SERVICES) {
            if (service.id().equals(id)) {
                return service;
            }
        }
        return null;
    }

    /**
     * The user chose {@code service} (null: none): a sign-in to another cloud that ends later is ignored. Kept after
     * connecting, so a later attempt to the same cloud still counts.
     */
    static void choose(@NonNull Context context, @Nullable CloudService service) {
        prefs(context).edit().putString(PREF_CHOSEN, service == null ? "" : service.id()).apply();
    }

    /**
     * The end of {@code service}'s sign-in: its last step runs on the sync's thread and connects the account, unless
     * the user chose another cloud, or none, meanwhile.
     */
    @NonNull
    static Consumer<Callable<CloudAccount>> finisher(@NonNull Context context, @NonNull CloudService service) {
        Context app = context.getApplicationContext();
        return last -> WORKER.execute(() -> {
            try {
                CloudAccount account = last.call();
                if (service.id().equals(prefs(app).getString(PREF_CHOSEN, null))) {
                    connect(app, service, account);
                }
            } catch (Exception e) {
                Log.w(TAG, "Sign-in failed", e);
                String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                MAIN.post(() -> Toast.makeText(app, app.getString(R.string.sync_sign_in_failed, reason),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    /** On the sync's thread: a new connection starts from a union, as the first sync of a device. */
    private static void connect(Context app, CloudService service, CloudAccount account) {
        SyncRun.forget(app);
        prefs(app).edit().putString(PREF_SERVICE, service.id()).putString(PREF_ACCOUNT, account.name)
                .putString(PREF_TOKEN, account.token).putString(PREF_ERROR, "").remove(PREF_AT).apply();
        now(app);
    }

    /** Stops syncing; the cloud file, and the cloud's grant on the user's other devices, stay. */
    static void disconnect(@NonNull Context context) {
        Context app = context.getApplicationContext();
        WORKER.execute(() -> {
            SyncRun.forget(app);
            // the choice is the screen's: the user may have chosen another cloud while this waited its turn
            prefs(app).edit().putString(PREF_SERVICE, "").remove(PREF_ACCOUNT).remove(PREF_TOKEN).remove(PREF_ERROR)
                    .remove(PREF_AT).apply();
        });
    }

    /** Whether the last sync succeeded: choosing the connected cloud again has nothing to mend then. */
    static boolean working(@NonNull Context context) {
        return prefs(context).getString(PREF_ERROR, "").isEmpty();
    }

    /** The status line: the account and the last sync, or why it failed. */
    @NonNull
    static String status(@NonNull Context context) {
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

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    /** Straight to the cloud: never through the app's proxies for the sites, which would see the tokens. */
    static synchronized OkHttpClient http() {
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
