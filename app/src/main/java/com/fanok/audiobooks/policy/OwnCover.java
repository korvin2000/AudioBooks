package com.fanok.audiobooks.policy;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.fanok.audiobooks.Url;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * A cover the user chose for a book ("Своя обложка…"), for one whose site has none: a JPEG among the app's files,
 * shown before any other wherever the book's cover shows. Kept on this device only. Each choice gets a new file
 * name, so no image cache serves the one it replaced.
 */
public final class OwnCover {

    private static final int MAX_SIDE = 1200;
    private static final String DIR = "own_covers";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    /** Book key → its file's name, read from the folder once. */
    @Nullable
    private static Map<String, String> names;

    private OwnCover() {
    }

    /** The cover chosen for {@code bookUrl}, or null. */
    @Nullable
    public static File of(@NonNull Context context, @Nullable String bookUrl) {
        if (bookUrl == null || bookUrl.isEmpty()) {
            return null;
        }
        String name = names(context).get(key(bookUrl));
        return name == null ? null : new File(new File(context.getFilesDir(), DIR), name);
    }

    /** Saves {@code image} as the cover of {@code bookUrl}, scaled down; {@code done} gets the outcome on the main thread. */
    public static void set(@NonNull Context context, @NonNull String bookUrl, @NonNull Uri image,
            @NonNull Consumer<Boolean> done) {
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            boolean saved = false;
            try {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                try (InputStream in = app.getContentResolver().openInputStream(image)) {
                    BitmapFactory.decodeStream(in, null, bounds);
                }
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 1;
                while (Math.max(bounds.outWidth, bounds.outHeight) / (options.inSampleSize * 2) >= MAX_SIDE) {
                    options.inSampleSize *= 2;
                }
                Bitmap bitmap;
                try (InputStream in = app.getContentResolver().openInputStream(image)) {
                    bitmap = BitmapFactory.decodeStream(in, null, options);
                }
                if (bitmap != null) {
                    String key = key(bookUrl);
                    File file = new File(dir(app), key + "-" + System.currentTimeMillis() + ".jpg");
                    try (OutputStream out = new FileOutputStream(file)) {
                        saved = bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
                    }
                    if (saved) {
                        replace(app, key, file.getName());
                    } else {
                        file.delete();
                    }
                }
            } catch (Exception | OutOfMemoryError e) {
                saved = false;
            }
            boolean outcome = saved;
            new Handler(Looper.getMainLooper()).post(() -> done.accept(outcome));
        });
    }

    /** Forgets the cover chosen for {@code bookUrl}: the site's (or the default one) shows again. */
    public static void clear(@NonNull Context context, @NonNull String bookUrl) {
        replace(context, key(bookUrl), null);
    }

    private static synchronized void replace(@NonNull Context context, @NonNull String key, @Nullable String name) {
        String old = name == null ? names(context).remove(key) : names(context).put(key, name);
        if (old != null) {
            new File(dir(context), old).delete();
        }
    }

    private static synchronized Map<String, String> names(@NonNull Context context) {
        if (names == null) {
            names = new HashMap<>();
            File[] files = dir(context).listFiles();
            for (int i = 0; files != null && i < files.length; i++) {
                String name = files[i].getName();
                int dash = name.indexOf('-');
                if (dash > 0) {
                    names.put(name.substring(0, dash), name);
                }
            }
        }
        return names;
    }

    @NonNull
    private static File dir(@NonNull Context context) {
        File dir = new File(context.getFilesDir(), DIR);
        dir.mkdirs();
        return dir;
    }

    /** A book's file name stem: the hash of its canonical URL. */
    @NonNull
    public static String key(@NonNull String bookUrl) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-1").digest(Url.canonical(bookUrl).getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            return Integer.toHexString(Url.canonical(bookUrl).hashCode());
        }
    }
}
