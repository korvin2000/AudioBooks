package com.fanok.audiobooks.widgets;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;
import com.fanok.audiobooks.presenter.SleepTimerPresenter;
import com.fanok.audiobooks.service.MediaPlayerService;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * The book being played, for launcher widgets the user allowed (Settings → General): levels 2 and 3 of the GENESIS
 * LAUNCHER contract (genesis_launcher/integration_updated.txt §5, §7; "Аудиокниги Nova" serves the same), found by the
 * launcher at {@code <package>.externalwidgets}. Display data and the sleep timer only: playback goes through the
 * media session. The integration is this package, the provider and {@code <queries>} entries of the manifest, the
 * setting and strings_widgets.xml; the player only offers {@link MediaPlayerService.SessionListener}.
 */
public final class ExternalWidgetsProvider extends ContentProvider {

    static final String SUFFIX = ".externalwidgets";

    /** The launchers asked and told: each also needs a manifest {@code <queries>} entry for its CHANGED. */
    static final Set<String> LAUNCHERS = Collections.singleton("ru.genesis.launcher");

    /** Requests a caller may make in any second; more are answered "later". */
    static final int RATE = 4;

    /** One sleep timer command per this many ms (integration_updated.txt §5.5). */
    private static final long TIMER_COMMAND_MS = 250;

    private static final String TAG = "ExternalWidgets";

    private final Map<String, long[]> calls = new HashMap<>();
    private long lastTimerCommand;
    private SessionMirror mirror;

    @Override
    public boolean onCreate() {
        //runs before the application and any service: the player meets the mirror with its first state
        mirror = new SessionMirror(getContext());
        MediaPlayerService.setSessionListener(mirror);
        return true;
    }

    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        if (!"snapshot".equals(method) && !"sleepTimer".equals(method)) {
            throw new IllegalArgumentException("unsupported: " + method);
        }
        try {
            String caller = getCallingPackage();
            if (!mirror.allowed || !LAUNCHERS.contains(caller)) {
                throw new SecurityException("not allowed");
            }
            long now = SystemClock.elapsedRealtime();
            if ("sleepTimer".equals(method)) {
                return sleepTimer(extras, now);
            }
            boolean admitted;
            synchronized (calls) {
                long[] times = calls.get(caller);
                if (times == null) {
                    calls.put(caller, times = new long[RATE]);
                }
                admitted = admit(times, now);
            }
            return admitted && mirror.ready ? snapshot(mirror.current, now) : later(500);
        } catch (SecurityException | IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            //only some exceptions cross the binder, any other one would end the app: this one reads "unavailable"
            Log.e(TAG, method + " failed", e);
            throw new IllegalStateException(method + " failed");
        }
    }

    /** Takes a request at {@code now} into the caller's last {@link #RATE} times, unless they all fall in a second. */
    static boolean admit(@NonNull long[] times, long now) {
        int oldest = 0;
        for (int i = 1; i < times.length; i++) {
            if (times[i] < times[oldest]) {
                oldest = i;
            }
        }
        if (times[oldest] != 0 && now - times[oldest] < 1000) {
            return false;
        }
        times[oldest] = now;
        return true;
    }

    /**
     * The widget sets the player's own sleep timer (§5.5): the command the app's timer screen sends, with the
     * user's warning options for minutes. Accepted is not applied: the player applies it, and CHANGED follows.
     */
    @NonNull
    private Bundle sleepTimer(@Nullable Bundle extras, long now) {
        String mode = extras != null ? extras.getString("mode") : null;
        int minutes = extras != null ? extras.getInt("minutes", 0) : 0;
        if (!timerCommand(mode, minutes)) {
            throw new IllegalArgumentException("sleepTimer " + mode + " " + minutes);
        }
        synchronized (this) {
            if (now - lastTimerCommand < TIMER_COMMAND_MS) {
                Bundle later = later(TIMER_COMMAND_MS);
                later.putBoolean("accepted", false);
                return later;
            }
            lastTimerCommand = now;
        }
        Bundle answer = new Bundle();
        //the command neither starts the player nor restores a book: without one there is nothing to time
        boolean target = MediaPlayerService.currentBookUrl() != null;
        answer.putBoolean("accepted", target);
        if (target) {
            Context context = getContext();
            Intent command = "minutes".equals(mode)
                    ? SleepTimerPresenter.quickStart(PreferenceManager.getDefaultSharedPreferences(context))
                            .putExtra("time", minutes * 60_000L)
                    : new Intent(SleepTimerPresenter.Broadcast_SleepTimer).putExtra("start", "end_of_chapter".equals(mode))
                            .putExtra("endOfChapter", true);
            context.sendBroadcast(command.setPackage(context.getPackageName()));
        }
        return answer;
    }

    /** A command §5.5 allows: minutes for 1..1440 of them, the chapter's end or off without any. */
    static boolean timerCommand(@Nullable String mode, int minutes) {
        return "minutes".equals(mode) ? minutes >= 1 && minutes <= 1440
                : ("end_of_chapter".equals(mode) || "off".equals(mode)) && minutes == 0;
    }

    /** integration_updated.txt §7.2 and §5.4: the keys and their exact types. */
    @NonNull
    private static Bundle snapshot(@Nullable NowPlaying shown, long now) {
        Bundle b = new Bundle();
        b.putInt("schemaVersion", 1);
        b.putBoolean("hasBook", shown != null);
        if (shown == null) {
            return b;
        }
        b.putString("title", shown.title);
        b.putString("chapter", shown.chapter);
        b.putString("credits", shown.credits);
        b.putInt("chapterIndex", shown.chapterIndex);
        b.putInt("chapterCount", shown.chapterCount);
        b.putLong("positionMs", shown.positionMs);
        b.putLong("durationMs", shown.durationMs);
        b.putString("positionUnit", "milliseconds");
        b.putBoolean("reader", false);
        b.putBoolean("playing", shown.playing);
        b.putFloat("speed", shown.speed);
        b.putInt("bookPercent", shown.bookPercent(now));
        b.putBoolean("bookPercentApproximate", shown.approximate());
        b.putLong("sampledAtElapsedMs", shown.sampledAtMs);
        if (shown.bookMs > 0) {
            //§3.3 way A, only with every chapter's length: the book's time, the position paired with positionMs
            b.putLong("bookDurationMs", shown.bookMs);
            b.putLong("bookPositionMs", shown.chapterStartMs + shown.positionMs);
        }
        if (shown.cover != null) {
            b.putByteArray("coverPng", shown.cover);
        }
        b.putBoolean("sleepTimerSupported", true);
        b.putBoolean("sleepTimerEnabled", shown.timerOn);
        b.putString("sleepTimerMode", !shown.timerOn ? "off" : shown.timerChapter ? "end_of_chapter" : "minutes");
        b.putBoolean("sleepTimerPaused", shown.timerPaused);
        b.putLong("sleepTimerRemainingMs", shown.timerRemainingMs);
        b.putLong("sleepTimerDeadlineElapsedMs", shown.timerDeadlineMs);
        b.putInt("sleepTimerDurationMinutes", shown.timerMinutes);
        return b;
    }

    @NonNull
    private static Bundle later(long ms) {
        Bundle b = new Bundle();
        b.putString("error", "retry_later");
        b.putLong("retryAfterMs", ms);
        return b;
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
            @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        throw new UnsupportedOperationException();
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        throw new UnsupportedOperationException();
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection,
            @Nullable String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
