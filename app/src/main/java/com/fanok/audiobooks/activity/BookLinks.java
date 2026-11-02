package com.fanok.audiobooks.activity;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import com.fanok.audiobooks.R;
import com.fanok.audiobooks.Url;
import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.util.DeletionPlanner;
import java.util.ArrayList;
import java.util.List;

/**
 * The book screen's "Поделиться" both ways: send or copy the book's link, open another book by a pasted link, and on
 * Android 12+ the system page where the sites' links are set to open in the app.
 */
final class BookLinks {

    private BookLinks() {
    }

    /** The menu of the book's link; an imported book has no real link, so it only offers to open one. */
    static void show(@NonNull Activity a, @NonNull BookPOJO book) {
        String url = Url.canonical(book.getUrl()); // a former host is shared as the current one
        List<Integer> items = new ArrayList<>();
        if (!DeletionPlanner.isImportedBook(url)) {
            items.add(R.string.book_link_send);
            items.add(R.string.book_link_copy);
        }
        items.add(R.string.book_link_open);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            items.add(R.string.book_link_defaults);
        }
        if (items.size() == 1) {
            askLink(a, url);
            return;
        }
        CharSequence[] labels = new CharSequence[items.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = a.getString(items.get(i));
        }
        new AlertDialog.Builder(a)
                .setTitle(R.string.share)
                .setItems(labels, (d, which) -> {
                    int item = items.get(which);
                    if (item == R.string.book_link_send) {
                        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, url)
                                .putExtra(Intent.EXTRA_SUBJECT, book.getName());
                        a.startActivity(Intent.createChooser(send, null));
                    } else if (item == R.string.book_link_copy) {
                        copy(a, book.getName(), url);
                    } else if (item == R.string.book_link_open) {
                        askLink(a, url);
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        openByDefault(a);
                    }
                })
                .show();
    }

    /**
     * Asks for a book's link and opens it through {@link LoadBook} (the PIN check, the loading and its errors) above
     * this screen, so Back returns here. The field is filled from the clipboard only now, after the explicit tap,
     * since Android 12+ announces every read of it. {@code current} is the shown book's link: it is not offered.
     */
    static void askLink(@NonNull Activity a, @NonNull String current) {
        EditText field = new EditText(a);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field.setHint(R.string.book_link_hint);
        String clip = clipboardLink(a);
        if (clip != null && !Url.samePage(clip, current)) {
            field.setText(clip);
        }
        FrameLayout box = new FrameLayout(a);
        int pad = (int) (20 * a.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(field);
        AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle(R.string.book_link_open_title)
                .setView(box)
                .setPositiveButton(R.string.open, (d, which) -> {
                    String link = LoadBook.bookUrlIn(field.getText());
                    if (link != null) {
                        a.startActivity(new Intent(a, LoadBook.class).putExtra("url", link));
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
        Button open = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        TextWatcher check = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String link = LoadBook.bookUrlIn(s);
                boolean same = link != null && Url.samePage(link, current);
                open.setEnabled(link != null && !same);
                field.setError(s.toString().trim().isEmpty() ? null
                        : link == null ? a.getString(R.string.unknown_source)
                                : same ? a.getString(R.string.book_link_same) : null);
            }
        };
        field.addTextChangedListener(check);
        check.afterTextChanged(field.getText());
    }

    /** The site link the clipboard holds, on its current host; null when it holds none. */
    @Nullable
    private static String clipboardLink(Context context) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            return null;
        }
        // the text or the URI itself, never coerceToText: for a URI item it reads the provider on the main thread
        ClipData.Item item = clip.getItemAt(0);
        CharSequence text = item.getText() != null ? item.getText()
                : item.getUri() != null ? item.getUri().toString() : null;
        return LoadBook.bookUrlIn(text);
    }

    private static void copy(Context context, @Nullable String label, String url) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(label, url));
        // Android 13+ confirms a copy itself
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show();
        }
    }

    /** The system "Открывать по умолчанию" page of this app, where the user enables the sites' links once. */
    @RequiresApi(Build.VERSION_CODES.S)
    private static void openByDefault(Activity a) {
        Uri app = Uri.parse("package:" + a.getPackageName());
        try {
            a.startActivity(new Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, app));
        } catch (ActivityNotFoundException e) {
            a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app)); // a build without the page
        }
    }
}
