package com.fanok.audiobooks.policy;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.LruCache;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.Metadata;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.metadata.id3.ApicFrame;
import androidx.media3.extractor.metadata.id3.Id3Decoder;
import com.fanok.audiobooks.App;
import com.fanok.audiobooks.Url;
import com.fanok.audiobooks.net.AppProxy;
import com.fanok.audiobooks.search.Provider;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Covers of a site whose own cover images are never requested ({@link DefaultCover#replaces}: audioboo, whose
 * Cloudflare starts refusing the app's pages too once its images are asked for), taken instead from the book's first
 * audio file: audioboo's MP3s on archive.org carry the front cover in their ID3 tag. Read in the background, one book
 * at a time and once per book in a process, by a bounded Range request for the tag only, over the route the book's
 * audio takes (direct or through the proxy, as set for the site), or from the downloaded file; kept in
 * {@code cacheDir/covers} by book URL, so the book page, the player, car mode and the lists show it from then on
 * ({@link DefaultCover#drawable}). Whatever fails leaves the default cover; the site itself is never asked.
 */
public final class EmbeddedCover {

    /** Told on the main thread when a book's cover has been read; {@code key} is its {@link Url#canonical} URL. */
    public interface Listener {
        void onCover(@NonNull String key, @NonNull Bitmap cover);
    }

    private static final String TAG = "EmbeddedCover";
    /** A tag larger than this is not read (this one is ~160 KB: a 400x400 JPEG). */
    private static final int MAX_TAG = 3 << 20;
    private static final int MAX_SIDE = 512;
    private static final int TIMEOUT_S = 30;
    /** After a remote read failed (no route, a timeout), remote reads wait this long: queued books skip them. */
    private static final long PAUSE_MS = 10 * 60_000L;

    private static final LruCache<String, Bitmap> MEMORY = new LruCache<String, Bitmap>(8 << 20) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    /** Books asked for in this process: each is read once; one that could not be reached may be asked again. */
    private static final Set<String> ASKED = Collections.newSetFromMap(new ConcurrentHashMap<>());
    /** Until when (elapsed realtime) remote reads are skipped, after one failed. */
    private static volatile long pausedUntil;
    private static final Set<Listener> LISTENERS = new CopyOnWriteArraySet<>();
    /** One read at a time: a quiet background job, never a burst. */
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private EmbeddedCover() {
    }

    public static void listen(@NonNull Listener listener) {
        LISTENERS.add(listener);
    }

    public static void unlisten(@NonNull Listener listener) {
        LISTENERS.remove(listener);
    }

    /** Whether {@code key} (of {@link Listener#onCover}) is the book at {@code bookUrl}. */
    public static boolean isOf(@NonNull String key, @Nullable String bookUrl) {
        return bookUrl != null && key.equals(Url.canonical(bookUrl));
    }

    /** The cover read before for {@code bookUrl}: from memory, else from its cached file; null when there is none. */
    @Nullable
    public static Bitmap cached(@NonNull Context context, @Nullable String bookUrl) {
        if (bookUrl == null || bookUrl.isEmpty()) {
            return null;
        }
        String key = Url.canonical(bookUrl);
        Bitmap cover = MEMORY.get(key);
        if (cover == null) {
            File file = file(context, key, ".jpg");
            cover = file.isFile() ? BitmapFactory.decodeFile(file.getPath()) : null; // small: at most 512 px
            if (cover != null) {
                MEMORY.put(key, cover);
            }
        }
        return cover;
    }

    /**
     * Reads the cover of the book at {@code bookUrl} from {@code audioUrl}, its first chapter (a URL or a downloaded
     * file), in the background; the listeners get it. Nothing when the book was asked for already in this process.
     */
    public static void fetch(@NonNull Context context, @Nullable String bookUrl, @Nullable String audioUrl) {
        if (bookUrl == null || bookUrl.isEmpty() || audioUrl == null || audioUrl.isEmpty()) {
            return;
        }
        String key = Url.canonical(bookUrl);
        if (!ASKED.add(key)) {
            return;
        }
        Context app = context.getApplicationContext();
        WORKER.execute(() -> {
            Bitmap cover = cached(app, bookUrl);
            if (cover == null && !file(app, key, ".none").exists()) {
                if (isRemote(audioUrl) && SystemClock.elapsedRealtime() < pausedUntil) {
                    ASKED.remove(key); // the source was unreachable a moment ago: asked again on a later visit
                    return;
                }
                cover = read(app, key, bookUrl, audioUrl);
            }
            if (cover != null) {
                Bitmap found = cover;
                MAIN.post(() -> {
                    for (Listener listener : LISTENERS) {
                        listener.onCover(key, found);
                    }
                });
            }
        });
    }

    @Nullable
    private static Bitmap read(Context app, String key, String bookUrl, String audioUrl) {
        try {
            byte[] tag = isRemote(audioUrl) ? remoteTag(bookUrl, audioUrl) : localTag(app, audioUrl);
            byte[] picture = tag == null ? null : Id3.frontCover(tag);
            Bitmap cover = picture == null ? null : decode(picture);
            if (cover == null) {
                // the whole start of the file was read and it has no tag, no picture or no image: so for good
                createQuietly(file(app, key, ".none"));
                return null;
            }
            save(cover, file(app, key, ".jpg"));
            MEMORY.put(key, cover);
            return cover;
        } catch (IOException e) {
            // not read (no route, a timeout, a page instead of the audio): nothing is remembered but a pause
            Log.w(TAG, "no cover from " + audioUrl + ": " + e);
            if (isRemote(audioUrl)) {
                pausedUntil = SystemClock.elapsedRealtime() + PAUSE_MS;
            }
            ASKED.remove(key);
            return null;
        } catch (RuntimeException e) {
            Log.w(TAG, "no cover from " + audioUrl + ": " + e); // a broken picture: tried again on the next start
            return null;
        }
    }

    private static boolean isRemote(String audioUrl) {
        return audioUrl.startsWith("http://") || audioUrl.startsWith("https://");
    }

    /** The tag at the start of a remote file: one request for its first bytes, read only as far as the tag goes. */
    @Nullable
    private static byte[] remoteTag(String bookUrl, String audioUrl) throws IOException {
        OkHttpClient client = AppProxy.mediaClient(Provider.ofUrl(bookUrl), App.getOkHttpClient()).newBuilder()
                .callTimeout(TIMEOUT_S, TimeUnit.SECONDS).build();
        Request request = new Request.Builder().url(audioUrl).header("Range", "bytes=0-" + (MAX_TAG - 1)).build();
        try (Response response = client.newCall(request).execute()) {
            ResponseBody body = response.body();
            MediaType type = body.contentType();
            if (!response.isSuccessful() || type != null && "text".equals(type.type())) {
                throw new IOException("HTTP " + response.code() + " " + type); // e.g. a page of a proxy or a portal
            }
            return Id3.tag(body.byteStream());
        }
    }

    @Nullable
    private static byte[] localTag(Context app, String audioUrl) throws IOException {
        try (InputStream in = audioUrl.startsWith("content://")
                ? app.getContentResolver().openInputStream(Uri.parse(audioUrl))
                : new FileInputStream(audioUrl.startsWith("file://") ? Uri.parse(audioUrl).getPath() : audioUrl)) {
            return in == null ? null : Id3.tag(in);
        }
    }

    @Nullable
    private static Bitmap decode(byte[] picture) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(picture, 0, picture.length, options);
        int side = Math.max(options.outWidth, options.outHeight);
        if (side <= 0) {
            return null;
        }
        options.inJustDecodeBounds = false;
        options.inSampleSize = 1;
        while (side / options.inSampleSize > MAX_SIDE) {
            options.inSampleSize *= 2;
        }
        return BitmapFactory.decodeByteArray(picture, 0, picture.length, options);
    }

    private static void save(Bitmap cover, File file) throws IOException {
        File temp = new File(file.getPath() + ".part");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            cover.compress(Bitmap.CompressFormat.JPEG, 90, out);
        }
        if (!temp.renameTo(file)) {
            temp.delete();
            throw new IOException("cannot save " + file);
        }
    }

    private static void createQuietly(File file) {
        try {
            file.createNewFile();
        } catch (IOException ignored) {
            // asked again on the next start
        }
    }

    @NonNull
    private static File file(Context context, String key, String suffix) {
        File dir = new File(context.getCacheDir(), "covers");
        dir.mkdirs();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(key.getBytes("UTF-8"));
            return new File(dir, String.format(Locale.ROOT, "%040x", new BigInteger(1, digest)) + suffix);
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The ID3v2 part, free of Android classes. */
    static final class Id3 {

        private Id3() {
        }

        /**
         * The ID3v2 tag at the start of {@code in}, header included; null when there is none or it is too large.
         * A stream that ends inside it throws: that is a cut transfer, not a file without a tag.
         */
        @Nullable
        static byte[] tag(@NonNull InputStream in) throws IOException {
            byte[] header = new byte[10];
            readFully(in, header, 0, 10);
            if (header[0] != 'I' || header[1] != 'D' || header[2] != '3') {
                return null;
            }
            int size = (header[6] & 0x7f) << 21 | (header[7] & 0x7f) << 14 | (header[8] & 0x7f) << 7 | header[9] & 0x7f;
            if (size <= 0 || size > MAX_TAG - 10) {
                return null;
            }
            byte[] tag = Arrays.copyOf(header, 10 + size);
            readFully(in, tag, 10, size);
            if (tag[3] == 4) {
                tag[5] &= ~0x10; // a v2.4 footer is not in the bytes read: Id3Decoder would take ten more off the tag
            }
            return tag;
        }

        /** The front cover's bytes in {@code tag} (else any picture in it); null when it has none. */
        @OptIn(markerClass = UnstableApi.class)
        @Nullable
        static byte[] frontCover(@NonNull byte[] tag) {
            // pictures only: nested chapter frames (CHAP/CTOC) are never decoded, however deep
            Metadata metadata = new Id3Decoder((major, a, b, c, d) -> a == 'A' && b == 'P' && c == 'I' && d == 'C'
                    || major == 2 && a == 'P' && b == 'I' && c == 'C').decode(tag, tag.length);
            byte[] picture = null;
            for (int i = 0; metadata != null && i < metadata.length(); i++) {
                Metadata.Entry entry = metadata.get(i);
                if (entry instanceof ApicFrame && (picture == null || ((ApicFrame) entry).pictureType == 3)) {
                    picture = ((ApicFrame) entry).pictureData;
                }
            }
            return picture;
        }

        static void readFully(InputStream in, byte[] buffer, int offset, int length) throws IOException {
            while (length > 0) {
                int read = in.read(buffer, offset, length);
                if (read < 0) {
                    throw new EOFException();
                }
                offset += read;
                length -= read;
            }
        }
    }
}
