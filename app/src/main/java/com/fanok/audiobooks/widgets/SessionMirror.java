package com.fanok.audiobooks.widgets;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;
import com.fanok.audiobooks.Hourglass;
import com.fanok.audiobooks.pojo.StorageUtil;
import com.fanok.audiobooks.service.Checkpoint;
import com.fanok.audiobooks.service.MediaPlayerService;
import java.io.ByteArrayOutputStream;

/**
 * Mirrors what the player's session shows into the {@link NowPlaying} the provider serves, keeps it for a cold start
 * and tells the trusted launchers when it changes. Without the user's consent or an installed launcher it only follows
 * the session in memory (a few fields per publication): no cover, no disk, no broadcast; it never uses the network.
 * Main thread; binder threads only read {@link #allowed}, {@link #ready} and {@link #current}.
 */
final class SessionMirror implements MediaPlayerService.SessionListener,
        SharedPreferences.OnSharedPreferenceChangeListener {

    /** Settings → General: the user's consent, given until they take it back (a hobby app's book, nothing private). */
    static final String PREF = "external_widgets";

    private static final String TAG = "ExternalWidgets";
    /** At most two CHANGED a second, as the launchers ask. */
    private static final long CHANGED_DELAY_MS = 500;
    private static final int COVER_SIDE = 256;
    private static final int COVER_BYTES = 256 * 1024;

    volatile boolean allowed;
    /** The kept book was read, or there is none: until then a request is answered "later". */
    volatile boolean ready;
    @Nullable
    volatile NowPlaying current;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Held here: preferences keep their listeners weakly. */
    private final SharedPreferences settings;
    private final SharedPreferences saved;
    /** Apart from the rest, which is written on every change: the cover only when the book's picture changes. */
    private final SharedPreferences savedCover;
    /** One picture encoded at a time: the last one, by its address. */
    @Nullable
    private String coverUri;
    @Nullable
    private byte[] cover;

    // the last publication, rebuilt after the main-thread task that made it: its metadata and state as a pair
    @Nullable
    private MediaMetadataCompat metadata;
    @Nullable
    private PlaybackStateCompat state;
    private float speed = 1f;
    @Nullable
    private int[] seconds;
    @Nullable
    private Hourglass sleepTimer;
    private int sleepMinutes;
    private boolean rebuildPosted, changedPosted, restoreStarted;

    SessionMirror(@NonNull Context context) {
        this.context = context.getApplicationContext();
        settings = PreferenceManager.getDefaultSharedPreferences(this.context);
        saved = this.context.getSharedPreferences("external_widgets", Context.MODE_PRIVATE);
        savedCover = this.context.getSharedPreferences("external_widgets_cover", Context.MODE_PRIVATE);
        settings.registerOnSharedPreferenceChangeListener(this);
        allowed = settings.getBoolean(PREF, true);
        if (allowed) {
            restore();
        }
    }

    @Override
    public void onSession(@Nullable MediaMetadataCompat metadata, @NonNull PlaybackStateCompat state, float speed,
            @Nullable int[] seconds, @Nullable Hourglass sleepTimer, int sleepMinutes) {
        this.metadata = metadata;
        this.state = state;
        this.speed = speed;
        this.seconds = seconds;
        this.sleepTimer = sleepTimer;
        this.sleepMinutes = sleepMinutes;
        postRebuild();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, @Nullable String key) {
        if (!PREF.equals(key)) {
            return;
        }
        allowed = prefs.getBoolean(PREF, true);
        if (allowed) {
            restore();
            postRebuild();
        }
        //at once and both ways, the revocation too: the launcher asks again and is refused
        sendChanged();
    }

    private void postRebuild() {
        if (!rebuildPosted) {
            rebuildPosted = true;
            handler.post(this::rebuild);
        }
    }

    private void rebuild() {
        rebuildPosted = false;
        if (state == null) {
            return;
        }
        try {
            long now = SystemClock.elapsedRealtime();
            NowPlaying last = current;
            boolean wanted = allowed && launcherInstalled();
            //the book left the session (stop, end of the service): the widget keeps it where it stopped
            NowPlaying shown = metadata != null ? shown(metadata, state, wanted)
                    : last != null ? last.paused(now) : null;
            if (shown != null) {
                sleep(shown, now);
                publish(shown, wanted);
            }
        } catch (RuntimeException e) {
            //a fault here must never reach the player's thread
            Log.e(TAG, "rebuild failed", e);
        }
    }

    @NonNull
    private NowPlaying shown(@NonNull MediaMetadataCompat metadata, @NonNull PlaybackStateCompat state,
            boolean wanted) {
        NowPlaying shown = new NowPlaying();
        shown.title = NowPlaying.title(metadata.getString(MediaMetadataCompat.METADATA_KEY_ALBUM));
        shown.chapter = NowPlaying.text(metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE));
        shown.credits = NowPlaying.credits(metadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST),
                metadata.getString(MediaMetadataCompat.METADATA_KEY_COMPOSER));
        shown.chapterIndex = (int) metadata.getLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER) - 1;
        shown.chapterCount = (int) metadata.getLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS);
        shown.durationMs = metadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION);
        shown.playing = state.getState() == PlaybackStateCompat.STATE_PLAYING;
        shown.positionMs = Math.max(0, state.getPosition());
        shown.sampledAtMs = state.getLastPositionUpdateTime();
        shown.speed = speed > 0 ? speed : 1f;
        shown.lengths(seconds);
        shown.coverUri = metadata.getString(MediaMetadataCompat.METADATA_KEY_ART_URI);
        shown.cover = wanted ? cover(shown.coverUri, metadata) : null;
        shown.bookUrl = MediaPlayerService.currentBookUrl();
        return shown;
    }

    /**
     * The player's sleep timer as the widget shows it (level 3): a countdown counts only while the book plays, so a
     * paused one has no deadline; an end-of-chapter one has no minutes.
     */
    private void sleep(@NonNull NowPlaying shown, long now) {
        Hourglass timer = sleepTimer;
        shown.timerOn = timer != null && timer.isRunning();
        shown.timerChapter = shown.timerOn && timer.isEndOfChapter();
        shown.timerPaused = shown.timerOn && timer.isPaused();
        shown.timerRemainingMs = shown.timerOn && !shown.timerChapter ? timer.getTimeRemaining() : 0;
        shown.timerDeadlineMs = shown.timerOn && !shown.timerPaused && !shown.timerChapter
                ? now + shown.timerRemainingMs : 0;
        //a timer set to a clock time has no length of its own: the minutes left stand for it
        shown.timerMinutes = !shown.timerOn || shown.timerChapter ? 0
                : sleepMinutes > 0 ? sleepMinutes : (int) ((shown.timerRemainingMs + 59_999) / 60_000);
    }

    private void publish(@NonNull NowPlaying shown, boolean wanted) {
        current = shown;
        ready = true;
        if (!wanted) {
            return;
        }
        save(shown.paused(SystemClock.elapsedRealtime()));
        if (!changedPosted) {
            changedPosted = true;
            handler.postDelayed(() -> {
                changedPosted = false;
                if (allowed) {
                    sendChanged();
                }
            }, CHANGED_DELAY_MS);
        }
    }

    /**
     * A launcher that reads the snapshot is installed. The consent is on by default: without one, keeping the book and
     * encoding its cover would be work for nobody.
     */
    private boolean launcherInstalled() {
        for (String launcher : ExternalWidgetsProvider.LAUNCHERS) {
            try {
                context.getPackageManager().getPackageInfo(launcher, 0);
                return true;
            } catch (PackageManager.NameNotFoundException e) {
                //not this one
            }
        }
        return false;
    }

    private void sendChanged() {
        String action = context.getPackageName() + ExternalWidgetsProvider.SUFFIX + ".CHANGED";
        for (String launcher : ExternalWidgetsProvider.LAUNCHERS) {
            context.sendBroadcast(new Intent(action).setPackage(launcher));
        }
    }

    /** The cover as PNG, encoded once per picture; null while the session has none. */
    @Nullable
    private byte[] cover(@Nullable String uri, @NonNull MediaMetadataCompat metadata) {
        if (!TextUtils.equals(uri, coverUri)) {
            coverUri = uri;
            cover = null;
        }
        Bitmap bitmap = cover == null ? metadata.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART) : null;
        if (bitmap != null && (cover = png(bitmap)) != null) {
            //kept with its address: a cold start shows it only with the book that has this picture
            savedCover.edit().putString("uri", uri)
                    .putString("png", Base64.encodeToString(cover, Base64.NO_WRAP)).apply();
        }
        return cover;
    }

    /** At most {@link #COVER_SIDE} px a side and {@link #COVER_BYTES}: halved until it fits. */
    @Nullable
    private static byte[] png(@NonNull Bitmap bitmap) {
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        for (int side = COVER_SIDE; side >= 32; side /= 2) {
            float k = Math.min(1f, side / (float) Math.max(w, h));
            Bitmap small = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(w * k)),
                    Math.max(1, Math.round(h * k)), true);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            if (small.compress(Bitmap.CompressFormat.PNG, 100, png) && png.size() <= COVER_BYTES) {
                return png.toByteArray();
            }
        }
        return null;
    }

    /** Kept stopped, as {@link NowPlaying#paused} leaves it: a cold start shows it and never runs it on. */
    private void save(@NonNull NowPlaying kept) {
        saved.edit()
                .putString("title", kept.title)
                .putString("chapter", kept.chapter)
                .putString("credits", kept.credits)
                .putInt("index", kept.chapterIndex)
                .putInt("count", kept.chapterCount)
                .putLong("position", kept.positionMs)
                .putLong("duration", kept.durationMs)
                .putFloat("speed", kept.speed)
                .putLong("start", kept.chapterStartMs)
                .putLong("book", kept.bookMs)
                .putString("cover", kept.coverUri)
                .putString("url", kept.bookUrl)
                .apply();
    }

    /** Reads the kept book once, off the main thread; a live session that came first wins. */
    private void restore() {
        if (restoreStarted) {
            return;
        }
        restoreStarted = true;
        new Thread(() -> {
            NowPlaying read = null;
            try {
                read = read();
            } catch (RuntimeException e) {
                Log.w(TAG, "the kept book is unreadable", e);
            }
            NowPlaying kept = read;
            handler.post(() -> {
                if (current == null && kept != null) {
                    current = kept.paused(SystemClock.elapsedRealtime());
                }
                ready = true;
            });
        }, "widgets-restore").start();
    }

    @Nullable
    private NowPlaying read() {
        if (!saved.contains("title")) {
            return null;
        }
        NowPlaying kept = new NowPlaying();
        kept.title = saved.getString("title", "");
        kept.chapter = saved.getString("chapter", "");
        kept.credits = saved.getString("credits", "");
        kept.chapterIndex = saved.getInt("index", -1);
        kept.chapterCount = saved.getInt("count", 0);
        kept.positionMs = saved.getLong("position", 0);
        kept.durationMs = saved.getLong("duration", 0);
        kept.speed = saved.getFloat("speed", 1f);
        kept.chapterStartMs = saved.getLong("start", 0);
        kept.bookMs = saved.getLong("book", -1);
        //a cold start resumes from the app's checkpoint: in the same chapter the widget shows where Play will start
        //(this record follows changes only, the checkpoint also a playing book); indices agree for a whole list only
        kept.bookUrl = saved.getString("url", null);
        Checkpoint last = new StorageUtil(context).loadLastCheckpoint();
        if (kept.bookUrl != null && kept.bookMs >= 0 && last != null && kept.bookUrl.equals(last.bookUrl)
                && last.chapterIndex == kept.chapterIndex) {
            kept.positionMs = last.positionMs;
        }
        kept.coverUri = saved.getString("cover", null);
        String png = kept.coverUri != null && kept.coverUri.equals(savedCover.getString("uri", null))
                ? savedCover.getString("png", null) : null;
        kept.cover = png != null ? Base64.decode(png, Base64.NO_WRAP) : null;
        return kept;
    }
}
