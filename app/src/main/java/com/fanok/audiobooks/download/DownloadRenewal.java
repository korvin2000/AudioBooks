package com.fanok.audiobooks.download;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadIndex;
import androidx.media3.exoplayer.offline.DownloadManager;
import androidx.media3.exoplayer.offline.DownloadRequest;

import com.fanok.audiobooks.Url;
import com.fanok.audiobooks.model.AudioModel;
import com.fanok.audiobooks.model.BookPages;
import com.fanok.audiobooks.pojo.AudioPOJO;
import com.fanok.audiobooks.util.DownloadUtil;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Downloads whose signed address expired before they finished (ИзиБук, Библус, Audioknigi.pro, 4Read…: hours to
 * days). When the site refuses one (401, 403), the book page is read again, as playback does
 * ({@code MediaPlayerService.refreshChapters}), and the book's unfinished downloads — failed ones only when refused —
 * are queued again under their id and cache key at their chapter's fresh address: the cached bytes are kept, a pause
 * stays. A download the fresh list does not prove to be the same file ({@link DownloadIds#renewedUrl}) stays as it
 * was; so does one refused again before its renewed address gave a byte (the site refuses more than the address),
 * until the user resumes or restarts it.
 */
@OptIn(markerClass = UnstableApi.class)
public final class DownloadRenewal {

    private static final String TAG = "DownloadRenewal";
    private static final Object LOCK = new Object();
    /** Refused download ids by book key, while the book's chapter list is read; guarded by {@link #LOCK}. */
    private static final Map<String, Set<String>> REFUSED = new HashMap<>();
    /** Bytes each renewed download held when renewed; guarded by {@link #LOCK}. */
    private static final Map<String, Long> RENEWED = new HashMap<>();

    private DownloadRenewal() {
    }

    /** From the download listener: {@code download} failed with {@code error}; a refused address is renewed. */
    @MainThread
    @SuppressLint("CheckResult") // runs to its end, whatever screen is open
    public static void failed(@NonNull Context context, @NonNull Download download, @Nullable Exception error) {
        int status = httpStatusOf(error);
        final String bookUrl = DownloadUtil.bookUrlOf(download.request);
        if ((status != 401 && status != 403) || bookUrl == null) return;
        String id = download.request.id;
        final String book = DownloadIds.bookKey(bookUrl);
        synchronized (LOCK) {
            Long before = RENEWED.remove(id);
            if (before != null && download.getBytesDownloaded() <= before) {
                Log.w(TAG, "renewed address refused again: " + status + " host=" + download.request.uri.getHost());
                return;
            }
            Set<String> ids = REFUSED.get(book);
            boolean reading = ids != null;
            if (!reading) REFUSED.put(book, ids = new HashSet<>());
            ids.add(id);
            if (reading) return; // the chapter list on its way renews this one too
        }
        final Context app = context.getApplicationContext();
        BookPages.forget(Url.canonical(bookUrl)); // a fresh page, fresh addresses
        new AudioModel(app).getAudio(bookUrl)
                .lastOrError()
                .subscribeOn(Schedulers.io())
                .map(chapters -> renewals(app, bookUrl, chapters, taken(book)))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(renewals -> {
                    // the manager itself, on its looper: no service start, which Android may refuse in the background
                    DownloadManager manager = DownloadUtil.getDownloadManager();
                    for (Map.Entry<DownloadRequest, Integer> renewal : renewals.entrySet()) {
                        manager.addDownload(renewal.getKey(), renewal.getValue());
                    }
                    Log.i(TAG, renewals.size() + " downloads renewed, host=" + Url.hostOf(bookUrl));
                }, e -> {
                    taken(book);
                    Log.w(TAG, "chapter list refresh failed: " + e.getClass().getSimpleName()
                            + " host=" + Url.hostOf(bookUrl));
                });
    }

    /** The user resumes or restarts download {@code id}, every download when null: a refusal renews it again. */
    public static void forget(@Nullable String id) {
        synchronized (LOCK) {
            if (id == null) {
                RENEWED.clear();
            } else {
                RENEWED.remove(id);
            }
        }
    }

    private static Set<String> taken(String book) {
        synchronized (LOCK) {
            Set<String> ids = REFUSED.remove(book);
            return ids != null ? ids : Collections.<String>emptySet();
        }
    }

    /**
     * On a worker: each unfinished download of {@code bookUrl} ({@code refused} ones of the failed) at its chapter's
     * address in {@code chapters}, with the stop reason it keeps; downloads that cannot be renewed are left out.
     */
    private static Map<DownloadRequest, Integer> renewals(Context context, String bookUrl, List<AudioPOJO> chapters,
                                                          Set<String> refused) throws IOException {
        List<String> urls = new ArrayList<>(chapters.size());
        for (AudioPOJO chapter : chapters) urls.add(chapter.getUrl());
        List<DownloadIds.Entry> entries = DownloadUtil.entriesFor(context, bookUrl, urls);
        DownloadIndex index = DownloadUtil.getDownloadManager().getDownloadIndex();
        Map<DownloadRequest, Integer> renewals = new LinkedHashMap<>();
        for (String id : DownloadUtil.downloadIdsOfBook(index, bookUrl)) {
            Download d = index.getDownload(id);
            boolean due = d != null && (d.state == Download.STATE_FAILED ? refused.contains(id)
                    : d.state == Download.STATE_QUEUED || d.state == Download.STATE_STOPPED
                    || d.state == Download.STATE_DOWNLOADING);
            String url = due ? DownloadIds.renewedUrl(id, d.request.uri.toString(), urls, entries) : null;
            if (url == null) continue;
            DownloadRequest old = d.request;
            renewals.put(new DownloadRequest.Builder(id, Uri.parse(url))
                    .setMimeType(old.mimeType)
                    .setStreamKeys(old.streamKeys)
                    .setKeySetId(old.keySetId)
                    .setCustomCacheKey(old.customCacheKey)
                    .setData(old.data)
                    .build(), d.stopReason);
            synchronized (LOCK) {
                RENEWED.put(id, d.getBytesDownloaded());
            }
        }
        if (renewals.isEmpty() && !refused.isEmpty()) {
            Log.w(TAG, "the chapter list no longer holds the refused downloads, host=" + Url.hostOf(bookUrl));
        }
        return renewals;
    }

    private static int httpStatusOf(@Nullable Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof HttpDataSource.InvalidResponseCodeException) {
                return ((HttpDataSource.InvalidResponseCodeException) t).responseCode;
            }
        }
        return -1;
    }
}
