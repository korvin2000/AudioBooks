package com.fanok.audiobooks.widgets;

import android.annotation.SuppressLint;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.fanok.audiobooks.service.MediaPlayerService;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * The book being played, for launcher widgets the user allowed (Settings → General): level 2 of the GENESIS LAUNCHER
 * contract (genesis_launcher/integration.txt §4, the one "Аудиокниги Nova" serves), found by the launcher at
 * {@code <package>.externalwidgets}. Display data only: commands go through the media session. The integration is
 * this package, the provider and {@code <queries>} entries of the manifest, the setting and strings_widgets.xml;
 * the player only offers {@link MediaPlayerService.SessionListener}.
 */
public final class ExternalWidgetsProvider extends ContentProvider {

    static final String SUFFIX = ".externalwidgets";

    /** Launchers by package, with the SHA-256 of their signing certificate; each needs a manifest {@code <queries>}. */
    static final Map<String, String> TRUSTED = Collections.singletonMap("ru.genesis.launcher",
            "9A86D6167BC916F8FF9433C5F10FFDA301F2507370A6C6E8A125473B24F3ADC0");

    /** Requests a caller may make in any second; more are answered "later". */
    static final int RATE = 4;

    private static final String TAG = "ExternalWidgets";

    private final Map<String, long[]> calls = new HashMap<>();
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
        if (!"snapshot".equals(method)) {
            throw new IllegalArgumentException("unsupported: " + method);
        }
        try {
            String caller = getCallingPackage();
            if (!mirror.allowed || caller == null || !trusted(caller)) {
                throw new SecurityException("not allowed");
            }
            long now = SystemClock.elapsedRealtime();
            boolean admitted;
            synchronized (calls) {
                long[] times = calls.get(caller);
                if (times == null) {
                    calls.put(caller, times = new long[RATE]);
                }
                admitted = admit(times, now);
            }
            return admitted && mirror.ready ? snapshot(mirror.current, now) : later();
        } catch (SecurityException e) {
            throw e;
        } catch (RuntimeException e) {
            //only some exceptions cross the binder, any other one would end the app: this one reads "unavailable"
            Log.e(TAG, "snapshot failed", e);
            throw new IllegalStateException("snapshot failed");
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

    /** A launcher of {@link #TRUSTED} signed with its certificate (on API 28+ also an earlier one it rotated from). */
    @SuppressLint("PackageManagerGetSignatures") // below 28: exactly one signer, compared in full
    @SuppressWarnings("deprecation")
    private boolean trusted(@NonNull String pkg) {
        String hex = TRUSTED.get(pkg);
        if (hex == null) {
            return false;
        }
        byte[] sha = new byte[hex.length() / 2];
        for (int i = 0; i < sha.length; i++) {
            sha[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        PackageManager pm = getContext().getPackageManager();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return pm.hasSigningCertificate(pkg, sha, PackageManager.CERT_INPUT_SHA256);
        }
        try {
            Signature[] signers = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures;
            return signers != null && signers.length == 1
                    && Arrays.equals(sha, MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray()));
        } catch (PackageManager.NameNotFoundException | NoSuchAlgorithmException e) {
            return false;
        }
    }

    /** integration.txt §4.2: the keys and their exact types. */
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
        if (shown.cover != null) {
            b.putByteArray("coverPng", shown.cover);
        }
        return b;
    }

    @NonNull
    private static Bundle later() {
        Bundle b = new Bundle();
        b.putString("error", "retry_later");
        b.putLong("retryAfterMs", 500L);
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
