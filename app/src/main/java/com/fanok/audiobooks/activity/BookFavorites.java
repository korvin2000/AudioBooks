package com.fanok.audiobooks.activity;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;
import com.fanok.audiobooks.Consts;
import com.fanok.audiobooks.R;
import com.fanok.audiobooks.model.BooksDBModel;
import com.fanok.audiobooks.model.FavoritePeople;
import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.pojo.DescriptionPOJO;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import io.reactivex.schedulers.Schedulers;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The favourites of the book screen: the shown book itself, and its authors and readers as favourite people. One per
 * {@link BookActivity}, so the toolbar, the ★ tab, the header, the labels and the cover's sheet show one state. The
 * listeners hear every change, also one made to {@link FavoritePeople} elsewhere, on the main thread. The book's
 * state is read and written on one background thread, in order; the people's state is in memory.
 */
public final class BookFavorites implements FavoritePeople.Listener {

    public interface Listener {
        /** Called on the main thread. */
        void onFavoritesChanged();
    }

    private static final String TAG = "BookFavorites";
    private static final int[] KINDS = {FavoritePeople.AUTHOR, FavoritePeople.READER};
    /** The kind of the panel's row of the book itself. */
    private static final int BOOK = -1;

    /** The people of one role on this book: the names as its field lists them, and the field's listing URL. */
    private static final class Field {
        List<String> names = Collections.emptyList();
        @Nullable
        String url;

        /** Takes a field that names someone (a blank one keeps what is known); true when the names changed. */
        boolean set(@Nullable String field, @Nullable String fieldUrl) {
            if (field == null || field.trim().isEmpty()) {
                return false;
            }
            List<String> next = FavoritePeople.split(field);
            url = fieldUrl == null || fieldUrl.trim().isEmpty() ? null : fieldUrl.trim();
            boolean changed = !next.equals(names);
            names = next;
            return changed;
        }
    }

    private final BookPOJO mBook;
    private final BooksDBModel mBooks;
    private final FavoritePeople mPeople;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> mListeners = new CopyOnWriteArrayList<>();
    private final Field mAuthors = new Field();
    private final Field mReaders = new Field();
    private boolean mIsBook;
    /** Counts the book's toggles: a read of its state begun before one is stale. */
    private int mBookToggles;
    @Nullable
    private BottomSheetDialog mSheet;

    BookFavorites(@NonNull Context context, @NonNull BookPOJO book) {
        mBook = book;
        mBooks = new BooksDBModel(context.getApplicationContext());
        mPeople = FavoritePeople.get(context);
        mAuthors.set(book.getAutor(), book.getUrlAutor());
        mReaders.set(book.getArtist(), book.getUrlArtist());
        mIsBook = mBooks.inFavorite(book); // one indexed count, so the first frame shows the right star
        mPeople.addListener(this);
    }

    /** The book page's people (names and listing URLs), better than the card's the screen was opened with. */
    public void setPeople(@Nullable DescriptionPOJO d) {
        if (d == null) {
            return;
        }
        boolean changed = mAuthors.set(d.getAutor(), d.getAutorUrl()) | mReaders.set(d.getArtist(), d.getArtistUrl());
        // a favourite met on this site's page learns that site's listing of them
        for (int kind : KINDS) {
            Field field = field(kind);
            if (field.url != null && !field.names.isEmpty()) {
                mPeople.learnUrl(kind, field.names.get(0), field.url);
            }
        }
        if (changed) {
            fire();
        }
    }

    public boolean isBook() {
        return mIsBook;
    }

    /** Adds the shown book to the favourites or removes it; returns the new state. */
    public boolean toggleBook() {
        boolean on = !mIsBook;
        mIsBook = on;
        mBookToggles++;
        background(() -> {
            if (on) {
                mBooks.addFavorite(mBook);
            } else {
                mBooks.removeFavorite(mBook);
            }
        });
        fire();
        return on;
    }

    /** Reads whether the book is a favourite (another screen may have changed it) and tells the listeners if so. */
    void refresh() {
        int toggles = mBookToggles;
        background(() -> {
            for (int kind : KINDS) {
                mPeople.count(kind); // loads the people's keys here rather than on the main thread
            }
            boolean on = mBooks.inFavorite(mBook);
            mMain.post(() -> {
                if (toggles == mBookToggles && on != mIsBook) {
                    mIsBook = on;
                    fire();
                }
            });
        });
    }

    /** The book's people of {@code kind} (FavoritePeople.AUTHOR or READER), as its field lists them. */
    public List<String> people(int kind) {
        return field(kind).names;
    }

    public boolean has(int kind, String name) {
        return mPeople.has(kind, name);
    }

    /** Whether any of the book's people of {@code kind} is a favourite. */
    public boolean any(int kind) {
        for (String name : people(kind)) {
            if (has(kind, name)) {
                return true;
            }
        }
        return false;
    }

    /** Adds or removes a person of the book; returns the new state. The field's URL is the page of its first person. */
    public boolean toggle(int kind, String name) {
        Field field = field(kind);
        String url = !field.names.isEmpty() && field.names.get(0).equals(name) ? field.url : null;
        return mPeople.toggle(kind, name, url);
    }

    /** A snackbar's Undo of {@link #toggle}: removes one just added, puts one just removed back with all its URLs. */
    public void undo(int kind, String name) {
        if (has(kind, name)) {
            mPeople.remove(kind, name);
        } else {
            mPeople.undoRemove(kind, name);
        }
    }

    public void addListener(Listener l) {
        if (l != null) {
            mListeners.addIfAbsent(l);
        }
    }

    public void removeListener(Listener l) {
        mListeners.remove(l);
    }

    @Override
    public void onFavoritePeopleChanged(int kind) {
        fire();
    }

    /** The cover's long press: the panel as a bottom sheet. */
    public void showSheet(@NonNull Activity a) {
        if (mSheet != null) {
            mSheet.dismiss();
        }
        BottomSheetDialog sheet = new BottomSheetDialog(a);
        View panel = LayoutInflater.from(a).inflate(R.layout.view_favorites_panel, null, false);
        bindPanel(panel, sheet::dismiss);
        NestedScrollView scroll = new NestedScrollView(a); // many people, landscape or large text: it scrolls
        scroll.addView(panel);
        sheet.setContentView(scroll);
        sheet.setOnDismissListener(dialog -> {
            if (mSheet == sheet) {
                mSheet = null;
            }
        });
        mSheet = sheet;
        sheet.show();
    }

    /**
     * Fills {@code panel} (view_favorites_panel) and keeps it current while it is attached: a row per favourite to
     * toggle (the whole row), then the ways to "Избранное" and "История"; {@code close} runs after those (the sheet
     * goes), may be null.
     */
    public void bindPanel(@NonNull View panel, @Nullable Runnable close) {
        LinearLayout rows = panel.findViewById(R.id.favoritesRows);
        Listener update = () -> showRows(rows);
        panel.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                addListener(update);
                showRows(rows);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                removeListener(update);
            }
        });
        if (panel.isAttachedToWindow()) {
            addListener(update);
        }
        showRows(rows);
        panel.findViewById(R.id.openFavorites).setOnClickListener(
                v -> open(v, Consts.FRAGMENT_FAVORITE, "favorite", close));
        panel.findViewById(R.id.openHistory).setOnClickListener(
                v -> open(v, Consts.FRAGMENT_HISTORY, "history", close));
    }

    /** The screen is gone: no more FavoritePeople news, no sheet left open. */
    void release() {
        mPeople.removeListener(this);
        mListeners.clear();
        if (mSheet != null) {
            mSheet.dismiss();
        }
    }

    private Field field(int kind) {
        return kind == FavoritePeople.AUTHOR ? mAuthors : mReaders;
    }

    private void fire() {
        for (Listener l : mListeners) {
            l.onFavoritesChanged();
        }
    }

    /** The book row, then a row per author and per reader; rebuilt only when the people change, else only rebound. */
    private void showRows(LinearLayout rows) {
        List<Integer> kinds = new ArrayList<>();
        List<String> names = new ArrayList<>();
        kinds.add(BOOK);
        names.add(mBook.getName() == null ? "" : mBook.getName().trim());
        for (int kind : KINDS) {
            for (String name : people(kind)) {
                kinds.add(kind);
                names.add(name);
            }
        }
        String signature = kinds.toString() + names;
        if (!signature.equals(rows.getTag())) {
            rows.removeAllViews();
            LayoutInflater inflater = LayoutInflater.from(rows.getContext());
            for (int i = 0; i < kinds.size(); i++) {
                int kind = kinds.get(i);
                String name = names.get(i);
                View row = inflater.inflate(R.layout.item_favorites_toggle, rows, false);
                ((ImageView) row.findViewById(R.id.icon)).setImageResource(kind == BOOK
                        ? R.drawable.ic_book_open_outline
                        : kind == FavoritePeople.AUTHOR ? R.drawable.ic_autor_bottom_sheet
                                : R.drawable.ic_artist_bottom_sheet);
                ((TextView) row.findViewById(R.id.name)).setText(name);
                row.setOnClickListener(v -> {
                    if (kind == BOOK) {
                        toggleBook();
                    } else {
                        toggle(kind, name);
                    }
                });
                rows.addView(row);
            }
            rows.setTag(signature);
        }
        Context context = rows.getContext();
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            int kind = kinds.get(i);
            boolean on = kind == BOOK ? mIsBook : has(kind, names.get(i));
            row.findViewById(R.id.star).setActivated(on);
            ((TextView) row.findViewById(R.id.state)).setText(context.getString(R.string.book_fav_row_state,
                    context.getString(kind == BOOK ? R.string.book_fav_role_book
                            : kind == FavoritePeople.AUTHOR ? R.string.book_fav_role_author
                                    : R.string.book_fav_role_reader),
                    context.getString(on ? R.string.book_fav_on : R.string.book_fav_off)));
        }
    }

    /** MainActivity on that list above this screen, so Back returns to the book; the URL is only a non-empty marker. */
    private static void open(View v, int fragment, String marker, @Nullable Runnable close) {
        MainActivity.startMainActivity(v.getContext(), fragment, marker);
        if (close != null) {
            close.run();
        }
    }

    private static void background(Runnable task) {
        Schedulers.single().scheduleDirect(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                Log.w(TAG, "book favourite not read or written", e);
            }
        });
    }
}
