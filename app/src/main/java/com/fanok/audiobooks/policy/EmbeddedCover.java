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
import com.fanok.audiobooks.CookesExeption;
import com.fanok.audiobooks.Url;
import com.fanok.audiobooks.model.AudioModel;
import com.fanok.audiobooks.net.AppProxy;
import com.fanok.audiobooks.room.AppDatabase;
import com.fanok.audiobooks.room.BooksAudioEntity;
import com.fanok.audiobooks.search.Provider;
import com.fanok.audiobooks.source.Audioboo;
import com.fanok.audiobooks.util.DownloadStorageHelper;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
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
import org.jsoup.HttpStatusException;

/**
 * Covers of a site whose own cover images are never requested ({@link DefaultCover#replaces}: audioboo, whose
 * Cloudflare starts refusing the app's pages too once its images are asked for), taken instead from the book's first
 * audio file: audioboo's MP3s on archive.org carry the front cover in their ID3 tag. Only audioboo's books are read,
 * never from audio on audioboo itself: by a bounded Range request for the tag only, over the route the book's audio
 * takes (direct or through the proxy, as set for the site), or from the downloaded file. Two lanes, so at most two
 * reads go at once: the opened or playing book ({@link #fetch}) at once, and the rows the lists show
 * ({@link #request}) one after another, the topmost shown first, while they are shown. A book's first chapter is the
 * one kept for it (opened or downloaded before: its downloaded copy, else its URL), else, for a row, its book page's,
 * read as the book screen reads it but tried once: one page at a time, {@link Pace#PAGE_GAP_MS} apart, none for
 * {@link Pace#PAGE_STOP_MS} after the site's check (the only requests to the site). A cover is kept in memory and in
 * {@code cacheDir/covers} by book URL and told to the listeners (the book page, the player, car mode, the lists); a
 * book without one is remembered as such, an item gone (404, 410) for the process, and a read that failed otherwise is
 * tried again later, later each time, from the same source (a row's failure never holds back the opened book). Whatever
 * fails leaves the default cover.
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

    private static final LruCache<String, Bitmap> MEMORY = new LruCache<String, Bitmap>(8 << 20) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    /** The first chapter of a book never opened, as its page gave it ("" for none); not kept in the database. */
    private static final LruCache<String, String> AUDIO = new LruCache<>(256);
    /** Audio found gone (404, 410, no such file): not asked again in this process. */
    private static final Set<String> GONE = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Demand DEMAND = new Demand();
    private static final Pace PACE = new Pace();
    private static final Set<Listener> LISTENERS = new CopyOnWriteArraySet<>();
    /** The opened or playing book: never behind a row. */
    private static final ExecutorService URGENT = Executors.newSingleThreadExecutor();
    /** The lists' rows, one at a time. */
    private static final ExecutorService ROWS = Executors.newSingleThreadExecutor();
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

    /** The cover read for {@code bookUrl} while it is in memory, never decoded from disk: for a list's bind. */
    @Nullable
    public static Bitmap memory(@Nullable String bookUrl) {
        return bookUrl == null || bookUrl.isEmpty() ? null : MEMORY.get(Url.canonical(bookUrl));
    }

    /** The cached file of the cover read before for {@code bookUrl}, for a loader to decode off the main thread. */
    @Nullable
    public static File saved(@NonNull Context context, @Nullable String bookUrl) {
        File file = bookUrl == null || bookUrl.isEmpty() ? null : file(context, Url.canonical(bookUrl), ".jpg");
        return file != null && file.isFile() ? file : null;
    }

    /**
     * Reads the cover of the opened or playing book at {@code bookUrl} on its own lane, never behind a list's rows:
     * from {@code audioUrl}, its first chapter (a URL or a downloaded file), else from the first chapter kept for it.
     * Nothing when it was read already in this process.
     */
    public static void fetch(@NonNull Context context, @Nullable String bookUrl, @Nullable String audioUrl) {
        if (isBook(bookUrl)) {
            Context app = context.getApplicationContext();
            String key = Url.canonical(bookUrl);
            String audio = audioUrl == null || audioUrl.isEmpty() ? null : audioUrl;
            URGENT.execute(() -> read(app, key, bookUrl, audio, false));
        }
    }

    /**
     * A row of a list shows the book at {@code position} and asks for its cover until it {@link #forget}s it: the
     * waiting book nearest the top goes first (the one asked first among equals). Rows of several lists may ask for one
     * book: it waits while any does.
     */
    public static void request(@Nullable String bookUrl, int position) {
        want(bookUrl, position, true);
    }

    /** A row asking for the book ({@link #request}) shows it at {@code position} now, or asks again after its turn. */
    public static void move(@Nullable String bookUrl, int position) {
        want(bookUrl, position, false);
    }

    private static void want(@Nullable String bookUrl, int position, boolean anew) {
        if (isBook(bookUrl) && DEMAND.want(Url.canonical(bookUrl), bookUrl, position, anew)) {
            ROWS.execute(EmbeddedCover::nextRow);
        }
    }

    /** A row asking for the book ({@link #request}) left its list: none asking, it waits no more (a read goes on). */
    public static void forget(@Nullable String bookUrl) {
        if (isBook(bookUrl)) {
            DEMAND.drop(Url.canonical(bookUrl));
        }
    }

    private static boolean isBook(@Nullable String bookUrl) {
        return Provider.ofUrl(bookUrl) == Provider.AUDIOBOO;
    }

    private static void nextRow() {
        String[] row = DEMAND.next();
        if (row != null) {
            try {
                read(App.getInstance(), row[0], row[1], null, true);
            } finally {
                DEMAND.done(row[0]);
            }
        }
    }

    /**
     * One book's turn on its lane: its cover from the cache, else read from {@code audioUrl}, else from the first
     * chapter kept for it or, for a row, given by its page ({@link #page}). A remote source that failed a moment ago is
     * not read again yet ({@link Pace#may}); a local copy is read at once. The opened book asking while the other lane
     * reads it is read once that read fails. Nothing thrown leaves the lane.
     */
    private static void read(Context app, String key, String bookUrl, @Nullable String audioUrl, boolean row) {
        String[] urgent = row ? null : new String[]{bookUrl, audioUrl};
        if (DEMAND.taken(key, urgent)) {
            return; // read in this process, or being read on the other lane
        }
        String audio = audioUrl;
        boolean took = false;
        boolean failed = false;
        try {
            Bitmap cover = cached(app, bookUrl);
            if (cover != null || file(app, key, ".none").exists()) {
                took = DEMAND.take(key, null);
                if (cover != null) {
                    publish(key, cover);
                }
                return;
            }
            if (audio == null) {
                audio = kept(app, bookUrl);
            }
            if (audio == null || GONE.contains(audio)) {
                audio = AUDIO.get(key); // never opened, or its kept chapter gone: what its page gave, when asked before
            }
            boolean paged = row && audio == null; // a row's page gives it
            if ((paged || isRemote(audio))
                    && !PACE.may(key, paged ? bookUrl : audio, row, SystemClock.elapsedRealtime())) {
                return; // it failed a moment ago: asked again later
            }
            if (paged) {
                audio = page(key, bookUrl);
            }
            if (audio == null || audio.isEmpty() || GONE.contains(audio) || DefaultCover.replaces(audio)
                    || !(took = DEMAND.take(key, urgent))) {
                return; // nothing to read (never the site itself), or the other lane took the book meanwhile
            }
            byte[] tag = isRemote(audio) ? remoteTag(bookUrl, audio) : localTag(app, audio);
            byte[] picture = tag == null ? null : Id3.frontCover(tag);
            cover = picture == null ? null : decode(picture);
            PACE.read(key);
            if (cover == null) {
                // the whole start of the file was read and it has no tag, no picture or no image: so for good
                createQuietly(file(app, key, ".none"));
                return;
            }
            MEMORY.put(key, cover);
            publish(key, cover);
            save(cover, file(app, key, ".jpg"));
        } catch (FileNotFoundException e) {
            Log.w(TAG, "no cover from " + audio + ": " + e); // the item was removed (404, 410) or the file deleted
            GONE.add(audio);
            if (audio.equals(AUDIO.get(key))) { // its page's: the page may give another, asked after the book's wait
                AUDIO.remove(key);
                PACE.failed(key, bookUrl, row, SystemClock.elapsedRealtime());
            }
            failed = true;
        } catch (IOException e) {
            // not read (no route, a timeout, a page instead of the audio): tried again later, later each time
            Log.w(TAG, "no cover from " + audio + ": " + e);
            PACE.failed(key, audio, row, SystemClock.elapsedRealtime());
            failed = true;
        } catch (RuntimeException e) {
            // the book not taken yet: its database or cached file failed, asked again later; taken: a broken picture,
            // tried again on the next start
            Log.w(TAG, "no cover of " + bookUrl + " from " + audio + ": " + e);
        } finally {
            String[] again = took ? DEMAND.end(key, failed) : null;
            if (again != null) { // the opened book asked meanwhile
                URGENT.execute(() -> read(app, key, again[0], again[1], false));
            }
        }
    }

    private static void publish(String key, Bitmap cover) {
        MAIN.post(() -> {
            for (Listener listener : LISTENERS) {
                listener.onCover(key, cover);
            }
        });
    }

    /**
     * A chapter kept for the book (opened or downloaded before): the first whose local copy opens, else the first remote
     * URL; null when none is.
     */
    @Nullable
    private static String kept(Context app, String bookUrl) {
        String remote = null;
        for (BooksAudioEntity chapter : AppDatabase.getDatabase(app).booksAudioDao().getByBookUrl(bookUrl)) {
            if (chapter.ordinal >= -1) { // below -1: a row kept for its copy only
                String local = DownloadStorageHelper.localCopyOf(app, chapter);
                if (local != null) {
                    return local;
                }
                if (remote == null && isRemote(chapter.urlAudio)) {
                    remote = chapter.urlAudio;
                }
            }
        }
        return remote;
    }

    /**
     * The first chapter of a book never opened, from its page loaded as the book screen loads it but tried once
     * ({@link AudioModel#loadOnce}), so the site's check is handled as there; one page at a time,
     * {@link Pace#PAGE_GAP_MS} apart, only while its row is shown, none for {@link Pace#PAGE_STOP_MS} after the site
     * answered with its check or a refusal, or refused the stored clearance. Nothing is told the user: the row keeps
     * its default cover.
     */
    @Nullable
    private static String page(String key, String bookUrl) {
        long wait = PACE.pageIn(SystemClock.elapsedRealtime());
        if (wait < 0) {
            return null;
        }
        SystemClock.sleep(wait);
        if (!DEMAND.wanted(key)) {
            return null; // its row left the screen meanwhile
        }
        String clearance = Audioboo.clearance();
        try {
            String audio = AudioModel.loadOnce(bookUrl).get(0).getUrl();
            PACE.paged(SystemClock.elapsedRealtime(), Pace.refused(clearance, Audioboo.clearance()));
            AUDIO.put(key, audio == null ? "" : audio);
            return audio;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "no page of " + bookUrl + ": " + e);
            long now = SystemClock.elapsedRealtime();
            boolean stop = Pace.stopsPages(e) || Pace.refused(clearance, Audioboo.clearance());
            PACE.paged(now, stop);
            if (!stop) {
                PACE.failed(key, bookUrl, true, now);
            }
            return null;
        }
    }

    private static boolean isRemote(@Nullable String audioUrl) {
        return audioUrl != null && (audioUrl.startsWith("http://") || audioUrl.startsWith("https://"));
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
            int answer = Pace.answer(response.code(), type == null ? null : type.type());
            if (answer != Pace.AUDIO) {
                String what = "HTTP " + response.code() + " " + type; // e.g. a page of a proxy or a portal
                throw answer == Pace.GONE ? new FileNotFoundException(what) : new IOException(what);
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

    /** Best effort: a cover not saved is shown all the same, and read again on the next start. */
    private static void save(Bitmap cover, File file) {
        File temp = new File(file.getPath() + ".part");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            cover.compress(Bitmap.CompressFormat.JPEG, 90, out);
        } catch (IOException e) {
            Log.w(TAG, "cover not saved: " + e);
            temp.delete();
            return;
        }
        if (!temp.renameTo(file)) {
            temp.delete();
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

    /**
     * Which books are wanted and which are taken, free of Android classes: the books rows of the lists ask for (how
     * many rows each, of any list), the one nearest the top first (the one asked first among equals), and the books a
     * lane took (being read, or done in this process). The last row leaving only stops the wait: a book taken stays
     * taken until its read ends, so no two read it; the opened book asking meanwhile is read again if that read fails.
     */
    static final class Demand {

        /** Book key → how many rows ask for it. */
        private final Map<String, Integer> rows = new HashMap<>();
        /** Book key → its URL, in the order asked, and → the list position of its row. */
        private final LinkedHashMap<String, String> waiting = new LinkedHashMap<>();
        private final Map<String, Integer> at = new HashMap<>();
        private final Set<String> taken = new HashSet<>();
        /** A book being read → the opened book's latest request that came meanwhile, {book URL, audio URL}, or null. */
        private final Map<String, String[]> reading = new HashMap<>();
        /** The row whose turn it is, while its list still shows it. */
        @Nullable
        private String current;

        /**
         * A row shown at {@code position} asks for the book ({@code anew}: one row more, until {@link #drop}; else one
         * asking already): true when it joins the queue; false when it is in it already (it moves to
         * {@code position}), its turn is under way, its book is taken or no row asks for it.
         */
        synchronized boolean want(String key, String bookUrl, int position, boolean anew) {
            if (anew) {
                rows.merge(key, 1, Integer::sum);
            }
            if (!rows.containsKey(key) || taken.contains(key) || key.equals(current)) {
                return false;
            }
            at.put(key, position);
            return waiting.put(key, bookUrl) == null;
        }

        /** A row asks no more: once none does, the book waits no more, and its current turn is no longer wanted. */
        synchronized void drop(String key) {
            if (rows.computeIfPresent(key, (k, n) -> n > 1 ? n - 1 : null) == null) {
                waiting.remove(key);
                at.remove(key);
                done(key);
            }
        }

        /** The waiting row nearest the top, {key, book URL}, now the current one; null when none waits. */
        @Nullable
        synchronized String[] next() {
            current = null;
            for (String key : waiting.keySet()) {
                if (current == null || at.get(key) < at.get(current)) {
                    current = key;
                }
            }
            if (current == null) {
                return null;
            }
            at.remove(current);
            return new String[]{current, waiting.remove(current)};
        }

        /** The turn of {@code key} ended: shown still, it may be asked for again. */
        synchronized void done(String key) {
            if (key.equals(current)) {
                current = null;
            }
        }

        /** Whether the current row is {@code key} and its list still shows it. */
        synchronized boolean wanted(String key) {
            return key.equals(current);
        }

        /** A lane takes the book for its read until {@link #end}: false when it is taken already (as {@link #taken}). */
        synchronized boolean take(String key, @Nullable String[] urgent) {
            if (taken(key, urgent)) {
                return false;
            }
            taken.add(key);
            reading.put(key, null);
            return true;
        }

        /** Whether the book is taken; while it is being read, the opened book's request {@code urgent} waits for it. */
        synchronized boolean taken(String key, @Nullable String[] urgent) {
            if (urgent != null && reading.containsKey(key)) {
                reading.put(key, urgent);
            }
            return taken.contains(key);
        }

        /**
         * Its read ended; {@code failed}: the book may be asked for again, and the opened book's request that waited
         * for it is returned, to be read now (null when none did).
         */
        @Nullable
        synchronized String[] end(String key, boolean failed) {
            String[] urgent = reading.remove(key);
            if (!failed) {
                return null;
            }
            taken.remove(key);
            return urgent;
        }
    }

    /** When the site's pages may be asked and a book read again, and what a failure means; free of Android classes. */
    static final class Pace {

        /** Book pages go one at a time, at least this far apart. */
        static final long PAGE_GAP_MS = 3_000;
        /** No page is asked this long after the site answered one with its check or a refusal. */
        static final long PAGE_STOP_MS = 30 * 60_000L;
        /** A book whose read failed waits this long, twice as long after each further failure, up to RETRY_MAX_MS. */
        static final long RETRY_MS = 60_000;
        static final long RETRY_MAX_MS = 30 * 60_000L;
        /** After this many failures in a row (the network is likely gone) the rows wait ROWS_PAUSE_MS. */
        static final int FAILURES_IN_A_ROW = 3;
        static final long ROWS_PAUSE_MS = 2 * 60_000L;
        /** What an answer to the tag's request is: the audio, an item gone, a failure that may pass. */
        static final int AUDIO = 0;
        static final int GONE = 1;
        static final int RETRY = 2;

        private long nextPage;
        private long pagesStoppedUntil;
        private long rowsPausedUntil;
        private int failures;
        /** Book key → its last failure. */
        private final Map<String, Failure> books = new HashMap<>();

        /** What failed (the book's page or audio URL), on which lane, how often in a row, and until when it waits. */
        private static final class Failure {
            String source;
            boolean row;
            int count;
            long until;
        }

        /** How long until the next page may be asked: 0 now, -1 when pages are stopped. */
        synchronized long pageIn(long now) {
            return now < pagesStoppedUntil ? -1 : Math.max(0, nextPage - now);
        }

        /** A page was asked (it ended at {@code now}); {@code stop}: the site answered with its check or a refusal. */
        synchronized void paged(long now, boolean stop) {
            nextPage = now + PAGE_GAP_MS;
            if (stop) {
                pagesStoppedUntil = now + PAGE_STOP_MS;
            }
        }

        /**
         * Whether the book may be read from {@code source} now: not while that source waits after its failure (the
         * opened book's lane waits only after its own), nor (a row) while the rows pause. Another source goes at once.
         */
        synchronized boolean may(String key, String source, boolean row, long now) {
            Failure failure = books.get(key);
            boolean waits = failure != null && now < failure.until && source.equals(failure.source)
                    && (row || !failure.row);
            return !waits && (!row || now >= rowsPausedUntil);
        }

        /** Reading the book from {@code source} (its page or audio URL) failed on its lane (a row's or not). */
        synchronized void failed(String key, String source, boolean row, long now) {
            Failure failure = books.computeIfAbsent(key, k -> new Failure());
            if (!source.equals(failure.source)) {
                failure.source = source;
                failure.count = 0;
            }
            failure.row = row;
            failure.until = now + Math.min(RETRY_MAX_MS, RETRY_MS << Math.min(failure.count++, 5));
            if (++failures >= FAILURES_IN_A_ROW) {
                rowsPausedUntil = now + ROWS_PAUSE_MS;
            }
        }

        /** The book's audio answered (with a cover or without). */
        synchronized void read(String key) {
            books.remove(key);
            failures = 0;
        }

        /** Pure: an answer to the tag's request is the audio, an item gone (404, 410), else a failure that may pass. */
        static int answer(int code, @Nullable String mediaType) {
            if (code == 404 || code == 410) {
                return GONE;
            }
            // a 403, 429, 5xx or a page of a proxy or a portal: none says the book has no cover
            return code / 100 == 2 && !"text".equals(mediaType) ? AUDIO : RETRY;
        }

        /** Pure: whether the stored clearance a page went out with ({@code before}) was refused (forgotten) meanwhile. */
        static boolean refused(String before, String after) {
            return !before.isEmpty() && !before.equals(after);
        }

        /** Pure: whether a page failed with the site's check or a refusal (403, 429, 503), wrapped or not. */
        static boolean stopsPages(@Nullable Throwable e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof CookesExeption) {
                    return true;
                }
                if (t instanceof HttpStatusException) {
                    int code = ((HttpStatusException) t).getStatusCode();
                    return code == 403 || code == 429 || code == 503;
                }
            }
            return false;
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
