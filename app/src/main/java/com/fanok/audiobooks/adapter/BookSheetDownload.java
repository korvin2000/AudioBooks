package com.fanok.audiobooks.adapter;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.fanok.audiobooks.R;
import com.fanok.audiobooks.activity.ClearSavedActivity;
import com.fanok.audiobooks.download.BookDownloader;
import com.fanok.audiobooks.download.DownloadIds;
import com.fanok.audiobooks.download.DownloadMonitor;
import com.fanok.audiobooks.download.DownloadStatus;
import com.fanok.audiobooks.download.DownloadStatusText;
import com.fanok.audiobooks.pojo.BookPOJO;

/**
 * The «Скачать аудиокнигу» row of the book long-press sheet (bootom_sheet_books_menu): the whole book downloads
 * without its screen ({@link BookDownloader}). While its downloads run the row tells their state («Скачивается
 * 12/40», «Пауза 35%») and opens «Загрузки»; otherwise it downloads what is not held yet (all of a new book, the rest
 * of a partly downloaded one, a failed chapter again; nothing, said so, for a complete one).
 */
public final class BookSheetDownload {

    private BookSheetDownload() {
    }

    public static void bind(@NonNull View sheet, @NonNull BookPOJO book, @NonNull Dialog dialog) {
        TextView row = sheet.findViewById(R.id.download);
        Context context = sheet.getContext();
        if (!BookDownloader.canDownload(context, book)) {
            row.setVisibility(View.GONE);
            return;
        }
        DownloadStatus status = DownloadMonitor.get().statusOfBook(DownloadIds.bookKey(book.getUrl()));
        String running = running(status) ? DownloadStatusText.brief(context, status) : null;
        row.setText(running != null ? running : context.getString(R.string.download_book));
        row.setVisibility(View.VISIBLE);
        row.setOnClickListener(v -> {
            dialog.dismiss();
            Activity activity = activityOf(context);
            if (running != null && activity != null) {
                ClearSavedActivity.startActivity(activity); // as the book screen's download line does
            } else {
                BookDownloader.downloadAll(context, book);
            }
        });
    }

    /** Downloads under way, paused or waiting: their place is «Загрузки», a new tap would only queue them again. */
    private static boolean running(@NonNull DownloadStatus status) {
        switch (status.state) {
            case DownloadStatus.DOWNLOADING:
            case DownloadStatus.STALLED:
            case DownloadStatus.QUEUED:
            case DownloadStatus.WAITING_NETWORK:
            case DownloadStatus.PAUSED:
                return true;
            default:
                return false;
        }
    }

    @Nullable
    private static Activity activityOf(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }
}
