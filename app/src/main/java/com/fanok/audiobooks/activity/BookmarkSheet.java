package com.fanok.audiobooks.activity;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;
import com.fanok.audiobooks.R;
import com.fanok.audiobooks.model.BookmarkRules;
import com.fanok.audiobooks.presenter.BookPresenter;
import com.fanok.audiobooks.room.AppDatabase;
import com.fanok.audiobooks.room.BookmarkDao;
import com.fanok.audiobooks.room.BookmarkEntity;
import com.fanok.audiobooks.service.MediaPlayerService;
import com.fanok.audiobooks.util.ChapterText;
import com.fanok.audiobooks.util.RussianDates;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.snackbar.Snackbar;
import io.reactivex.schedulers.Schedulers;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * «Закладки» of the book screen, a bottom sheet: «Добавить закладку» marks the place the player is at, only while it
 * holds this book (the service, which knows the place exactly, stores the mark), then the book's marks in book order.
 * A tap goes to a mark; its ⋮ edits the note or deletes it, with an undo. The marks are read and written on one
 * background thread, in order. One per BookActivity, made when first opened; the sheet goes with the screen.
 */
final class BookmarkSheet {

    /** Goes to a mark: true when the player goes there (the sheet closes), false when it cannot (the user was told). */
    interface Host {
        boolean goTo(@NonNull BookmarkEntity mark);
    }

    private static final String TAG = "BookmarkSheet";
    private static final int MENU_NOTE = 1;
    private static final int MENU_DELETE = 2;

    private final String mBookUrl;
    private final String mBookName;
    private final Host mHost;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    @Nullable
    private Activity mActivity;
    @Nullable
    private BottomSheetDialog mSheet;
    @Nullable
    private View mPanel;
    @Nullable
    private AlertDialog mNoteDialog;
    /** The marks shown, in their order. */
    private List<BookmarkEntity> mShown = Collections.emptyList();
    private boolean mListening;

    /** The service stored a mark: shown with the list, and its note offered at once. */
    private final BroadcastReceiver mAdded = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mSheet == null || !mBookUrl.equals(intent.getStringExtra("url"))) {
                return;
            }
            int id = intent.getIntExtra("id", -1);
            boolean added = intent.getBooleanExtra("added", true);
            update(null, () -> {
                Snackbar bar = bar(added ? R.string.bookmark_added : R.string.bookmark_exists);
                BookmarkEntity mark = shown(id);
                if (mark != null) {
                    bar.setAction(R.string.bookmark_note, v -> editNote(mark));
                }
                bar.show();
            });
        }
    };

    BookmarkSheet(@NonNull String bookUrl, @Nullable String bookName, @NonNull Host host) {
        mBookUrl = bookUrl;
        mBookName = bookName == null ? "" : bookName.trim();
        mHost = host;
    }

    void show(@NonNull Activity a) {
        release();
        mActivity = a;
        BottomSheetDialog sheet = new BottomSheetDialog(a);
        View panel = LayoutInflater.from(a).inflate(R.layout.sheet_bookmark, null, false);
        ((TextView) panel.findViewById(R.id.bookmarkBook)).setText(mBookName);
        panel.findViewById(R.id.bookmarkAdd).setOnClickListener(v -> {
            if (bindAdd()) {
                a.sendBroadcast(new Intent(BookPresenter.Broadcast_ADD_BOOKMARK).putExtra("url", mBookUrl)
                        .setPackage(a.getPackageName()));
            }
        });
        sheet.setContentView(panel);
        sheet.setOnDismissListener(dialog -> {
            if (mSheet == sheet) {
                listen(false);
                mSheet = null;
                mPanel = null;
                mShown = Collections.emptyList();
            }
        });
        mSheet = sheet;
        mPanel = panel;
        bindAdd();
        listen(true);
        sheet.show();
        update(null, null);
    }

    /** The screen is gone: nothing more heard, no sheet or note dialog left open. */
    void release() {
        listen(false);
        if (mNoteDialog != null) {
            mNoteDialog.dismiss();
            mNoteDialog = null;
        }
        if (mSheet != null) {
            mSheet.dismiss();
        }
        mSheet = null;
        mPanel = null;
        mActivity = null;
    }

    private void listen(boolean on) {
        if (on == mListening || mActivity == null) {
            return;
        }
        mListening = on;
        Context app = mActivity.getApplicationContext();
        if (on) {
            ContextCompat.registerReceiver(app, mAdded, new IntentFilter(BookActivity.Broadcast_BOOKMARK_ADDED),
                    ContextCompat.RECEIVER_NOT_EXPORTED);
        } else {
            app.unregisterReceiver(mAdded);
        }
    }

    /** «Добавить закладку» marks the player's place, so it is on only while the player holds this book. */
    private boolean bindAdd() {
        if (mPanel == null) {
            return false;
        }
        boolean here = mBookUrl.equals(MediaPlayerService.currentBookUrl());
        View add = mPanel.findViewById(R.id.bookmarkAdd);
        add.setEnabled(here);
        add.setAlpha(here ? 1f : 0.38f);
        mPanel.findViewById(R.id.bookmarkAddHint).setVisibility(here ? View.GONE : View.VISIBLE);
        return here;
    }

    /**
     * Runs {@code write} (none: only a read), then reads the book's marks, on the marks' thread; the sheet, while it is
     * the one open now, shows them and runs {@code then}. A write is done also when the sheet closed meanwhile (a note
     * saved after it).
     */
    private void update(@Nullable Consumer<BookmarkDao> write, @Nullable Runnable then) {
        if (mActivity == null) {
            return;
        }
        BottomSheetDialog sheet = mSheet;
        BookmarkDao dao = AppDatabase.getDatabase(mActivity).bookmarkDao();
        Schedulers.single().scheduleDirect(() -> {
            List<BookmarkEntity> marks;
            try {
                if (write != null) {
                    write.accept(dao);
                }
                marks = sheet == null ? null : dao.getByBook(mBookUrl);
            } catch (RuntimeException e) {
                Log.w(TAG, "bookmarks not read or written", e);
                return;
            }
            if (marks == null) {
                return;
            }
            mMain.post(() -> {
                if (mSheet == sheet) {
                    showMarks(marks);
                    if (then != null) {
                        then.run();
                    }
                }
            });
        });
    }

    private void showMarks(@NonNull List<BookmarkEntity> marks) {
        if (mPanel == null) {
            return;
        }
        mShown = marks;
        bindAdd();
        LinearLayout rows = mPanel.findViewById(R.id.bookmarkRows);
        rows.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(rows.getContext());
        for (BookmarkEntity mark : marks) {
            View row = inflater.inflate(R.layout.item_bookmark, rows, false);
            bindRow(row, mark);
            rows.addView(row);
        }
        mPanel.findViewById(R.id.bookmarkEmpty).setVisibility(marks.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void bindRow(@NonNull View row, @NonNull BookmarkEntity mark) {
        Context c = row.getContext();
        ((TextView) row.findViewById(R.id.bookmarkChapter)).setText(chapterText(c, mark));
        String day = RussianDates.dayAndTime(mark.createdAt); // Russian on any device language, as the app's dates
        ((TextView) row.findViewById(R.id.bookmarkPlace)).setText(c.getString(R.string.bookmark_place,
                ChapterText.duration((int) (mark.positionMs / 1000)), day));
        TextView note = row.findViewById(R.id.bookmarkNote);
        note.setText(mark.note);
        note.setVisibility(mark.note == null ? View.GONE : View.VISIBLE);
        row.setOnClickListener(v -> {
            if (mHost.goTo(mark) && mSheet != null) {
                mSheet.dismiss();
            }
        });
        View more = row.findViewById(R.id.bookmarkMenu);
        more.setOnClickListener(v -> {
            PopupMenu popup = new PopupMenu(v.getContext(), v);
            popup.getMenu().add(Menu.NONE, MENU_NOTE, MENU_NOTE, R.string.bookmark_note_edit);
            popup.getMenu().add(Menu.NONE, MENU_DELETE, MENU_DELETE, R.string.delete);
            popup.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == MENU_NOTE) {
                    editNote(mark);
                } else {
                    delete(mark);
                }
                return true;
            });
            popup.show();
        });
    }

    /** Gone at once; «Отменить» puts it back as it was, with its id, note and date. */
    private void delete(@NonNull BookmarkEntity mark) {
        update(dao -> dao.delete(mark.id), () -> bar(R.string.bookmark_deleted)
                .setAction(R.string.undo, v -> update(dao -> dao.insert(mark), null))
                .show());
    }

    /** A bar on the sheet's window: one made in the sheet's own layout is drawn under the sheet, unseen. */
    @NonNull
    private Snackbar bar(int text) {
        return Snackbar.make(mSheet.getWindow().getDecorView(), text, Snackbar.LENGTH_LONG);
    }

    /** The note in a dialog; «Сохранить» stores it, an empty one clears it. */
    private void editNote(@NonNull BookmarkEntity mark) {
        if (mActivity == null) {
            return;
        }
        Context context = mActivity;
        EditText field = new EditText(context);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setHint(R.string.bookmark_note_hint);
        field.setText(mark.note);
        field.setSelection(field.length());
        FrameLayout box = new FrameLayout(context);
        int pad = (int) (20 * context.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(field);
        if (mNoteDialog != null) {
            mNoteDialog.dismiss();
        }
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.bookmark_note_title)
                .setView(box)
                .setPositiveButton(R.string.bookmark_note_save, (d, which) -> {
                    String text = BookmarkRules.cleanNote(field.getText().toString());
                    update(dao -> dao.updateNote(mark.id, text), null);
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.setOnDismissListener(d -> {
            if (mNoteDialog == dialog) {
                mNoteDialog = null;
            }
        });
        mNoteDialog = dialog;
        dialog.show();
    }

    @Nullable
    private BookmarkEntity shown(int id) {
        for (BookmarkEntity mark : mShown) {
            if (mark.id == id) {
                return mark;
            }
        }
        return null;
    }

    /** The chapter as the player names it: «Глава 3» for a bare number, the number in the book for none. */
    @NonNull
    private static String chapterText(@NonNull Context c, @NonNull BookmarkEntity mark) {
        String name = ChapterText.shown(mark.chapterName).trim();
        if (name.isEmpty()) {
            return c.getString(R.string.bookmark_chapter_number, String.valueOf(mark.chapterIndex + 1));
        }
        return TextUtils.isDigitsOnly(name) ? c.getString(R.string.bookmark_chapter_number, name) : name;
    }
}
