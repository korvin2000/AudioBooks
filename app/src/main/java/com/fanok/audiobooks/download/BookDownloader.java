package com.fanok.audiobooks.download;

import android.annotation.SuppressLint;
import android.content.Context;
import android.widget.Toast;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import com.fanok.audiobooks.Consts;
import com.fanok.audiobooks.R;
import com.fanok.audiobooks.activity.LoadBook;
import com.fanok.audiobooks.model.AudioListDBModel;
import com.fanok.audiobooks.model.AudioModel;
import com.fanok.audiobooks.pojo.AudioPOJO;
import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.policy.ParentalControl;
import com.fanok.audiobooks.presenter.BookDescriptionPresenter;
import com.fanok.audiobooks.search.Provider;
import com.fanok.audiobooks.util.DownloadUtil;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The whole book downloaded from a list's book menu, without its screen (which would hand the book to the player): the
 * chapters are read from the site as the book screen reads them, kept as its rows, and every one not held yet is
 * queued. From there the downloads are the book screen's own: their notification shows the progress, the book joins
 * «Загруженные» when a chapter is in, the book screen shows their state.
 */
public final class BookDownloader {

    /** Books whose chapters are being read; main thread only. */
    private static final Set<String> PREPARING = new HashSet<>();

    private BookDownloader() {
    }

    /** A site's book the list may download: not imported, not a site's collection, allowed by parental control. */
    public static boolean canDownload(@NonNull Context context, @NonNull BookPOJO book) {
        String url = book.getUrl();
        return url != null && !url.isEmpty() && !url.contains("/imported/") && Provider.parserOf(url) != null
                && !LoadBook.opensAsListing(url) && ParentalControl.isAllowed(context, book);
    }

    /** Queues every chapter of {@code book} not held yet; says what happened in a toast. */
    @MainThread
    @SuppressLint("CheckResult") // runs to its end whatever screen started it
    public static void downloadAll(@NonNull Context context, @NonNull BookPOJO book) {
        final Context app = context.getApplicationContext();
        final String url = book.getUrl();
        if (!Consts.isNetworkAvailable(app)) {
            toast(app, app.getString(R.string.error_no_network));
            return;
        }
        if (!PREPARING.add(url)) {
            toast(app, app.getString(R.string.download_book_preparing));
            return;
        }
        toast(app, app.getString(R.string.download_book_preparing));
        new AudioModel(app).getAudio(url)
                .lastOrError()
                .subscribeOn(Schedulers.io())
                .map(chapters -> queue(app, book, chapters))
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally(() -> PREPARING.remove(url))
                .subscribe(
                        added -> toast(app, added > 0 ? app.getString(R.string.download_book_started, book.getName())
                                : app.getString(R.string.download_book_nothing)),
                        error -> toast(app, error instanceof Refusal && error.getMessage() != null
                                ? error.getMessage() : app.getString(BookDescriptionPresenter.errorMessage(error))));
    }

    /** On a worker: the chapters as the book screen keeps them, then their downloads; the number queued. */
    private static int queue(Context app, BookPOJO book, List<AudioPOJO> chapters) throws Refusal {
        if (chapters.isEmpty()) {
            throw new Refusal(null);
        }
        AudioPOJO first = chapters.get(0);
        if (chapters.size() == 1 && (first.getUrl() == null || first.getUrl().isEmpty())) {
            throw new Refusal(first.getName()); // the site's own word on why there is nothing to play
        }
        new AudioListDBModel(app).syncChapters(book, chapters);
        List<String> urls = new ArrayList<>(chapters.size());
        for (AudioPOJO chapter : chapters) {
            urls.add(chapter.getUrl());
        }
        try {
            // in the foreground: an app sent to the background meanwhile is refused here, not by a service that
            // could no longer go to the foreground (a crash on Android 12+)
            return DownloadUtil.addDownloads(app, book, chapters, urls, true);
        } catch (IllegalStateException background) {
            throw new Refusal(app.getString(R.string.download_book_background));
        }
    }

    private static void toast(Context app, CharSequence text) {
        Toast.makeText(app, text, Toast.LENGTH_SHORT).show();
    }

    /** Nothing was queued, and why: the site's word on a book with no chapter, or the app gone to the background. */
    private static final class Refusal extends Exception {

        Refusal(String message) {
            super(message);
        }
    }
}
