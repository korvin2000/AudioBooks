package com.fanok.audiobooks.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/**
 * One sync of this device with a {@link Cloud}: reads the cloud copy, merges it with this device's state against
 * the base (the state this device last agreed on with the cloud), applies the difference here, uploads the result
 * when the cloud lacks something and keeps the new base. Blocking: the sync's thread only.
 */
final class SyncRun {

    /** This installation's name in {@link Snapshot#seq}; out of backups, as all of the sync's preferences. */
    private static final String PREF_DEVICE = CloudSync.PREF_PREFIX + "device";

    private SyncRun() {
    }

    static void run(Context app, Cloud cloud) throws Exception {
        AtomicFile file = baseFile(app);
        Snapshot base = load(file);
        for (int attempt = 0; ; attempt++) {
            Cloud.Remote remote = cloud.read();
            // an unreadable cloud file is overwritten: what it held is lost anyway
            Snapshot theirs = remote == null ? null : Snapshot.parse(remote.data);
            Snapshot mine = DeviceState.read(app);
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
            Snapshot kept = DeviceState.apply(app, mine, merged);
            if (upload) {
                merged.count(device(app), theirs, base);
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

    /** Forgets the base: the next sync, with whatever cloud, starts from a union, as the first one of a device. */
    static void forget(Context app) {
        baseFile(app).delete();
    }

    private static String device(Context app) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
        String device = prefs.getString(PREF_DEVICE, null);
        if (device == null) {
            device = UUID.randomUUID().toString().substring(0, 8);
            prefs.edit().putString(PREF_DEVICE, device).apply();
        }
        return device;
    }

    /** Kept out of backups: a restored app must not take it for its own. */
    private static AtomicFile baseFile(Context app) {
        return new AtomicFile(new File(app.getNoBackupFilesDir(), "sync-base.json.gz"));
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
}
