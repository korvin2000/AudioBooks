package com.fanok.audiobooks.region;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import java.io.EOFException;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The region of the user's connection ({@link Region}): checked once per process {@link #START_DELAY_S} after the
 * app starts ({@link #start}, like the update check), on a thread of its own, by {@link RegionCheck}; the result is
 * kept in the settings ({@link #last}) and logged. The requests bypass the app's proxy ({@link DirectNet}); a system
 * VPN, which an app cannot bypass, gives the VPN's region, and the result says so.
 */
public final class AppRegion {

    /** The region of the last check ({@link #last}); the source screens watch it. */
    public static final String PREF_REGION = "region";
    static final String PREF_COUNTRY = "region_country";
    static final String PREF_SOURCE = "region_source";
    static final String PREF_VIA = "region_via";
    static final String PREF_VPN = "region_vpn";
    static final String PREF_AT = "region_at";
    private static final long START_DELAY_S = 5;
    private static final String TAG = "AppRegion";
    private static boolean started; // main thread

    private AppRegion() {
    }

    /** Schedules the check, once per process. */
    public static void start(@NonNull Context context) {
        if (started) {
            return;
        }
        started = true;
        Context app = context.getApplicationContext();
        ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "region");
            thread.setDaemon(true);
            return thread;
        });
        worker.schedule(() -> {
            try {
                check(app);
            } finally {
                worker.shutdown();
            }
        }, START_DELAY_S, TimeUnit.SECONDS);
    }

    /** The result of the last check; null before the first one. */
    @Nullable
    public static RegionCheck.Result last(@NonNull Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        try {
            String region = prefs.getString(PREF_REGION, null);
            return region == null ? null : new RegionCheck.Result(Region.valueOf(region),
                    prefs.getString(PREF_COUNTRY, ""), RegionCheck.Source.valueOf(prefs.getString(PREF_SOURCE, "NONE")),
                    prefs.getString(PREF_VIA, ""), prefs.getBoolean(PREF_VPN, false));
        } catch (ClassCastException | IllegalArgumentException e) {
            return null;
        }
    }

    /** One check, on the worker; never throws. */
    private static void check(@NonNull Context app) {
        long began = SystemClock.elapsedRealtime();
        List<String> failed = new ArrayList<>();
        DirectNet net = new DirectNet();
        RegionCheck.Result result;
        try {
            result = RegionCheck.run(device(app), net, () -> table(app), failed);
        } catch (RuntimeException e) {
            Log.w(TAG, "Region check failed", e);
            return;
        } finally {
            net.close();
        }
        PreferenceManager.getDefaultSharedPreferences(app).edit()
                .putString(PREF_REGION, result.region.name())
                .putString(PREF_COUNTRY, result.country)
                .putString(PREF_SOURCE, result.source.name())
                .putString(PREF_VIA, result.via)
                .putBoolean(PREF_VPN, result.vpn)
                .putLong(PREF_AT, System.currentTimeMillis())
                .apply();
        Log.i(TAG, result + " in " + (SystemClock.elapsedRealtime() - began) + " ms"
                + (failed.isEmpty() ? "" : "; did not tell: " + failed));
    }

    /** The table, read in place: about 3 KB at open, then at most one block of 512 bytes a lookup. */
    @Nullable
    private static IpRegions table(@NonNull Context app) {
        AssetManager assets = app.getAssets();
        try (AssetFileDescriptor fd = assets.openFd(IpRegions.ASSET)) {
            return IpRegions.open((position, buffer, offset, length) -> read(assets, position, buffer, offset, length),
                    fd.getLength());
        } catch (IOException e) {
            Log.w(TAG, "No IP region table", e);
            return null;
        }
    }

    /** {@code length} bytes of the table from {@code position}: it lies uncompressed in the APK (app/build.gradle). */
    private static void read(AssetManager assets, long position, byte[] buffer, int offset, int length)
            throws IOException {
        try (AssetFileDescriptor fd = assets.openFd(IpRegions.ASSET); FileInputStream in = fd.createInputStream()) {
            FileChannel channel = in.getChannel();
            ByteBuffer to = ByteBuffer.wrap(buffer, offset, length);
            while (to.hasRemaining()) {
                if (channel.read(to, fd.getStartOffset() + position + to.position() - offset) < 0) {
                    throw new EOFException();
                }
            }
        }
    }

    /** What the device knows: the active network, the data SIM's mobile network and country, the time zone. */
    @NonNull
    private static RegionCheck.Device device(@NonNull Context app) {
        RegionCheck.Device device = new RegionCheck.Device();
        try {
            ConnectivityManager connectivity = app.getSystemService(ConnectivityManager.class);
            Network network = connectivity == null ? null : connectivity.getActiveNetwork();
            NetworkCapabilities caps = network == null ? null : connectivity.getNetworkCapabilities(network);
            device.online = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            device.vpn = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
            LinkProperties link = network == null ? null : connectivity.getLinkProperties(network);
            if (link != null) {
                List<InetAddress> addresses = new ArrayList<>();
                for (LinkAddress address : link.getLinkAddresses()) {
                    addresses.add(address.getAddress());
                }
                device.addresses = addresses;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "No network state", e);
        }
        try {
            TelephonyManager phone = app.getSystemService(TelephonyManager.class);
            if (phone != null) {
                int data = SubscriptionManager.getDefaultDataSubscriptionId();
                if (data != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    phone = phone.createForSubscriptionId(data);
                }
                device.networkCountry = nonNull(phone.getNetworkCountryIso());
                device.roaming = phone.isNetworkRoaming();
                device.simCountry = nonNull(phone.getSimCountryIso());
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "No mobile network state", e);
        }
        try {
            // "001" (the world) for Etc/UTC and the like: no country
            device.zoneCountry = nonNull(android.icu.util.TimeZone.getRegion(java.util.TimeZone.getDefault().getID()));
        } catch (RuntimeException e) {
            device.zoneCountry = "";
        }
        return device;
    }

    @NonNull
    private static String nonNull(@Nullable String value) {
        return value == null ? "" : value;
    }
}
