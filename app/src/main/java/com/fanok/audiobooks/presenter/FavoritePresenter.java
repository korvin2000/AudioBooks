package com.fanok.audiobooks.presenter;


import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.preference.PreferenceManager;
import com.arellomobile.mvp.InjectViewState;
import com.arellomobile.mvp.MvpPresenter;
import com.fanok.audiobooks.App;
import com.fanok.audiobooks.Consts;
import com.fanok.audiobooks.R;
import com.fanok.audiobooks.fragment.BooksFragment;
import com.fanok.audiobooks.interface_pacatge.favorite.FavoriteView;
import com.fanok.audiobooks.model.AudioDBModel;
import com.fanok.audiobooks.model.BooksDBModel;
import com.fanok.audiobooks.model.FavoriteModel;
import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.search.GenrePicks;
import com.fanok.audiobooks.search.Provider;
import com.fanok.audiobooks.search.SourceLibrary;
import com.fanok.audiobooks.util.DownloadStorageHelper;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.schedulers.Schedulers;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.jetbrains.annotations.NotNull;

@InjectViewState
@OptIn(markerClass = UnstableApi.class)
public class FavoritePresenter
 extends MvpPresenter<FavoriteView> implements
        com.fanok.audiobooks.interface_pacatge.favorite.FavoritePresenter {

    public static final int NOT_SAVED = FavoriteModel.NOT_SAVED;

    public static final int SAVED = FavoriteModel.SAVED;

    public static final int SAVED_ALL = FavoriteModel.SAVED_ALL;


    private ArrayList<BookPOJO> books;

    private ArrayList<BookPOJO> flter = null;

    private BooksDBModel mBooksDBModel;

    private FavoriteModel mFavoriteModel;

    private final ArrayList<BookPOJO> filterSearch;

    /** The collection last given to the list: every row action resolves its position there. */
    @Nullable
    private ArrayList<BookPOJO> shown;

    private final int table;

    private boolean isLoading = false;

    private String mQuery = "";

    private Context mContext;

    private boolean firstOpen;

    private boolean scanScheduled = false;

    private boolean isFilterSaved;

    /** The site the list is narrowed to (the source filter), or null. */
    @Nullable
    private Provider source;

    /** The source filter's last offer: each value shown, with its site. */
    private final Map<String, Provider> sourceChoices = new HashMap<>();

    /** The book hidden by the last swipe, until the undo offer ends. */
    @Nullable
    private BookPOJO pendingRemoval;
    private int pendingRemovalIndex;
    /** The hidden book was in {@link #flter}: the undo puts it back there too. */
    private boolean pendingRemovalFiltered;

    private final CompositeDisposable disposables = new CompositeDisposable();


    public FavoritePresenter(int table) {
        this(table, null);
    }

    /** The list {@code table}, narrowed to {@code source} from the start (a source card's counter). */
    public FavoritePresenter(int table, @Nullable Provider source) {
        filterSearch = new ArrayList<>();
        firstOpen = true;
        this.table = table;
        this.source = source;
    }


    @Override
    public void loadBooks() {
        if (table == Consts.TABLE_SAVED && mContext != null && !scanScheduled) {
            scanScheduled = true;
            DownloadStorageHelper.scanAndImportDownloadedBooks(mContext, () -> {
                scanScheduled = false;
                loadBooksInternal();
            });
            return;
        }
        loadBooksInternal();
    }

    private void loadBooksInternal() {
        if (isLoading || mFavoriteModel == null) {
            return;
        }
        commitRemove();
        getViewState().showError(false);
        getViewState().showProgres(true);
        isLoading = true;
        disposables.add(mFavoriteModel.getBooks(table)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(bookPOJOS -> {
                    books = bookPOJOS;
                    onBooksLoaded();
                }, e -> {
                    isLoading = false;
                    getViewState().showProgres(false);
                    getViewState().showError(true);
                }));
    }

    private void onBooksLoaded() {
        if (source != null) {
            flter = null; // the site's books as they are now, new ones included (bySource)
        }
        if (flter != null) {
            ArrayList<BookPOJO> newFilter = new ArrayList<>();
            for (BookPOJO bookPOJO : books) {
                for (BookPOJO filterPojo : flter) {
                    if (filterPojo.getUrl().equals(bookPOJO.getUrl())) {
                        newFilter.add(bookPOJO);
                    }
                }
            }
            flter = newFilter;
            finishLoad(flter);
            return;
        }
        if (firstOpen && mContext != null && table != Consts.TABLE_HISTORY) {
            firstOpen = false;
            SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(mContext);
            String sort = pref.getString("pref_sort_favorite", mContext.getString(R.string.sort_value_date));
            if (mContext.getString(R.string.sort_value_saved).equals(sort) && table != Consts.TABLE_SAVED) {
                // the "saved" order needs the download state of every book: one worker pass, then sort
                sortBySavedState(books, () -> finishLoad(bySource()));
                return;
            }
            if (!mContext.getString(R.string.sort_value_date).equals(sort)) {
                books.sort(getComparator(sort));
            }
        }
        firstOpen = false;
        finishLoad(bySource());
    }

    /** The loaded books, or only those of {@link #source} (the source filter), its title the subtitle. */
    private ArrayList<BookPOJO> bySource() {
        if (source == null) {
            return books;
        }
        flter = SourceLibrary.only(books, BookPOJO::getUrl, source);
        getViewState().setSubTitle(source.title);
        return flter;
    }

    private void finishLoad(ArrayList<BookPOJO> list) {
        show(list);
        getViewState().showProgres(false);
        getViewState().updateFilter();
        isLoading = false;
    }

    @Override
    public void onCreate(Context context) {
        if (mContext != null) {
            return; // the presenter outlives the view; models are made once
        }
        mContext = context;
        mBooksDBModel = new BooksDBModel(context);
        mFavoriteModel = new FavoriteModel(context);
    }


    @Override
    public void detachView(FavoriteView view) {
        // the undo offer leaves with the view; the deletion the user asked for still happens
        commitRemove();
        super.detachView(view);
    }

    @Override
    public void onDestroy() {
        commitRemove();
        disposables.dispose();
        if (mBooksDBModel != null) {
            mBooksDBModel.closeDB();
        }
        if (mFavoriteModel != null) {
            mFavoriteModel.closeDB();
        }
        mContext = null;
        super.onDestroy();
    }

    /** Gives {@code list} to the list on screen; the row actions resolve against it ({@link #bookAt}). */
    private void show(@NonNull ArrayList<BookPOJO> list) {
        shown = list;
        getViewState().showData(list);
    }

    @Nullable
    private BookPOJO bookAt(int position) {
        List<BookPOJO> list = shown;
        if (list == null || position < 0 || position >= list.size()) {
            return null;
        }
        return list.get(position);
    }

    @Override
    public void onBookItemClick(int position) {
        BookPOJO book = bookAt(position);
        if (book != null) {
            open(book);
        }
    }

    /** A book opens its page; a site's collection kept from the search is a list of books: its listing opens. */
    public void open(@NonNull BookPOJO book) {
        Provider provider = Provider.ofUrl(book.getUrl());
        if (provider != null && provider.parser.opensAsListing(book.getUrl())) {
            openListing(book.getUrl(), book.getName(), "collectionBooks");
        } else {
            getViewState().showBooksActivity(book);
        }
    }

    /** A listing chosen elsewhere (an author, series or collection of the search) opens as from the catalog. */
    public void openListing(@NonNull String url, String title, @NonNull String tag) {
        getViewState().showFragment(BooksFragment.newInstance(url, R.string.menu_audiobooks, title,
                Consts.MODEL_BOOKS), tag);
    }

    @Override
    public void onBookItemLongClick(int position) {
        BookPOJO book = bookAt(position);
        if (book != null) {
            getViewState().showBookMenu(book);
        }
    }

    @Override
    public void onMenuGenre(@NonNull String url, String title) {
        GenrePicks.record(App.getInstance(), title);
        getViewState().showFragment(BooksFragment.newInstance(url, R.string.menu_audiobooks, title,
                Consts.MODEL_BOOKS), "genreBooks");
    }

    @Override
    public void onMenuAutor(@NonNull String url, String title) {
        if (!url.isEmpty()) {
            getViewState().showFragment(BooksFragment.newInstance(url, R.string.menu_audiobooks, title,
                    Consts.MODEL_BOOKS), "autorBooks");
        }
    }

    @Override
    public void onMenuArtist(@NonNull String url, String title) {
        getViewState().showFragment(BooksFragment.newInstance(url, R.string.menu_audiobooks, title,
                Consts.MODEL_BOOKS), "artistBooks");
    }

    @Override
    public void onMenuSeries(@NonNull String url, String title) {
        getViewState().showFragment(BooksFragment.newInstance(url + "?page=", R.string.menu_audiobooks, title,
                Consts.MODEL_BOOKS), "seriesBooks");
    }

    /** Hides the book at {@code position}; it is deleted only when the undo offer ends (UI-10). */
    @Override
    public void onRemove(int position) {
        BookPOJO book = bookAt(position);
        if (book != null) {
            onRemove(book);
        }
    }

    @Override
    public void onRemove(@NonNull BookPOJO book) {
        if (books == null || !books.contains(book)) {
            return;
        }
        commitRemove();
        pendingRemoval = book;
        pendingRemovalIndex = books.indexOf(book);
        pendingRemovalFiltered = flter != null && flter.contains(book);
        books.remove(book);
        if (flter != null) {
            flter.remove(book);
        }
        filterSearch.remove(book);
        ArrayList<BookPOJO> list = shown == null ? books : shown;
        list.remove(book);
        show(list);
        getViewState().showUndoRemove(book);
    }

    @Override
    public void undoRemove() {
        BookPOJO book = pendingRemoval;
        if (book == null) {
            return;
        }
        pendingRemoval = null;
        // back into the whole list; the filter is made again from it, in its order (flter follows books)
        int index = Math.min(Math.max(pendingRemovalIndex, 0), books.size());
        books.add(index, book);
        if (flter != null) {
            List<BookPOJO> members = new ArrayList<>(flter);
            if (pendingRemovalFiltered) {
                members.add(book);
            }
            flter = SourceLibrary.among(books, members);
        }
        onSearch(mQuery);
    }

    @Override
    public void commitRemove() {
        final BookPOJO book = pendingRemoval;
        if (book == null || mContext == null) {
            pendingRemoval = null;
            return;
        }
        pendingRemoval = null;
        final Context app = mContext.getApplicationContext();
        if (table == Consts.TABLE_SAVED) {
            if (book.getUrl() != null) {
                // rows and the recorded copies, on the storage worker
                DownloadStorageHelper.removeBooks(app, Collections.singletonList(book.getUrl()),
                        (removed, total, failed) -> {
                            if (failed > 0) {
                                android.widget.Toast.makeText(app, app.getString(R.string.remove_partial, removed, total, failed),
                                        android.widget.Toast.LENGTH_LONG).show();
                                loadBooksInternal();
                            }
                        });
            }
            return;
        }
        DownloadStorageHelper.submit(() -> {
            BooksDBModel booksDBModel = new BooksDBModel(app);
            if (table == Consts.TABLE_FAVORITE) {
                booksDBModel.removeFavorite(book);
            } else if (table == Consts.TABLE_HISTORY) {
                booksDBModel.removeHistory(book);
                AudioDBModel audioDBModel = new AudioDBModel(app);
                audioDBModel.remove(book.getUrl());
                audioDBModel.closeDB();
            }
            booksDBModel.closeDB();
        });
    }

    /** Empties the whole list, rows on the storage worker; the toolbar asked and the user confirmed. */
    @Override
    public void clearAll() {
        if (mContext == null) {
            return;
        }
        // a load or a filter still on its way would bring the old rows back; the swiped book goes with the rest
        disposables.clear();
        isLoading = false;
        pendingRemoval = null;
        books = new ArrayList<>();
        flter = null;
        isFilterSaved = false;
        source = null;
        getViewState().setSubTitle("");
        getViewState().showProgres(false);
        onSearch(mQuery);
        // the tables as they are once the deletion ran, whatever was read meanwhile
        clearTable(mContext.getApplicationContext(), table, () -> {
            if (mContext != null) {
                loadBooks();
            }
        });
    }

    /**
     * Favorites, or history with its listening positions (as the settings' "Очистить" does), on the storage worker;
     * {@code then} runs on the main thread afterwards.
     */
    public static void clearTable(@NonNull Context app, int table, @Nullable Runnable then) {
        DownloadStorageHelper.submit(() -> {
            BooksDBModel booksDBModel = new BooksDBModel(app);
            if (table == Consts.TABLE_FAVORITE) {
                booksDBModel.clearFavorite();
            } else if (table == Consts.TABLE_HISTORY) {
                booksDBModel.clearHistory();
                AudioDBModel audioDBModel = new AudioDBModel(app);
                audioDBModel.clearAll();
                audioDBModel.closeDB();
            }
            booksDBModel.closeDB();
            if (then != null) {
                new Handler(Looper.getMainLooper()).post(then);
            }
        });
    }

    @Override
    public void onSearch(String qery) {
        mQuery = qery == null ? "" : qery;
        filterSearch.clear();
        List<BookPOJO> source = flter != null ? flter : books;
        if (source != null) {
            for (BookPOJO book : source) {
                if (book.getName().toLowerCase().contains(mQuery.toLowerCase())) {
                    filterSearch.add(book);
                }
            }
        }
        show(filterSearch);
    }

    @Override
    public void onOptionsItemSelected(int id) {
        if (books == null || id == R.id.filter || id == R.id.order) {
            return;
        }
        if (id == R.id.genre_filter || id == R.id.autor_filter ||
                id == R.id.artist_filter || id == R.id.series_filter) {
            loadFilterValues(id);
        } else if (id == R.id.source_filter) {
            offerSources();
        } else if (id == R.id.date) {
            loadBooks();
        } else if (id == R.id.saved_filter) {
            if (!isFilterSaved) {
                final ArrayList<BookPOJO> all = new ArrayList<>(books);
                withSavedStates(all, states -> {
                    ArrayList<BookPOJO> saved = new ArrayList<>();
                    for (BookPOJO bookPOJO : all) {
                        Integer state = states.get(bookPOJO.getUrl());
                        if (state != null && state != NOT_SAVED) {
                            saved.add(bookPOJO);
                        }
                    }
                    flter = saved;
                    isFilterSaved = true;
                    source = null;
                    getViewState().setSubTitle(mContext.getString(R.string.menu_saved));
                    onSearch(mQuery);
                });
            } else {
                getViewState().setSubTitle("");
                flter = null;
                isFilterSaved = false;
                source = null;
                onSearch(mQuery);
            }
        } else if (id == R.id.saved) {
            sortBySavedState(books, () -> {
                reorderFilter();
                onSearch(mQuery);
            });
        } else {
            books.sort(getComparator(id));
            reorderFilter();
            onSearch(mQuery);
        }
    }

    /** A sort orders the whole list; the filter keeps its books in that order, so an undo can make it again. */
    private void reorderFilter() {
        if (flter != null) {
            flter = SourceLibrary.among(books, flter);
        }
    }

    private interface StatesCallback {
        void onStates(@NonNull Map<String, Integer> states);
    }

    private void withSavedStates(@NonNull List<BookPOJO> list, @NonNull StatesCallback callback) {
        if (mFavoriteModel == null) {
            return;
        }
        getViewState().showProgres(true);
        disposables.add(mFavoriteModel.savedStates(list)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(states -> {
                    getViewState().showProgres(false);
                    callback.onStates(states);
                }, e -> {
                    getViewState().showProgres(false);
                    getViewState().showToast(R.string.error_load_data);
                }));
    }

    /** Sorts {@code list} in place by the precomputed download state, most complete first, then {@code then}. */
    private void sortBySavedState(@NonNull ArrayList<BookPOJO> list, @NonNull Runnable then) {
        withSavedStates(list, states -> {
            list.sort((a, b) -> Integer.compare(stateOf(states, b), stateOf(states, a)));
            then.run();
        });
    }

    private static int stateOf(@NonNull Map<String, Integer> states, @NonNull BookPOJO book) {
        Integer state = states.get(book.getUrl());
        return state == null ? NOT_SAVED : state;
    }

    /** The sites of the listed books, each with its number of books there, most first. */
    private void offerSources() {
        if (mContext == null) {
            return;
        }
        List<String> urls = new ArrayList<>();
        for (BookPOJO book : books) {
            urls.add(book.getUrl());
        }
        sourceChoices.clear();
        ArrayList<String> values = new ArrayList<>();
        for (Map.Entry<Provider, Integer> site : SourceLibrary.sites(urls).entrySet()) {
            String value = mContext.getString(R.string.sources_filter_value, site.getKey().title, site.getValue());
            sourceChoices.put(value, site.getKey());
            values.add(value);
        }
        if (!values.isEmpty()) {
            getViewState().showFilterValues(R.id.source_filter, values);
        }
    }

    private void loadFilterValues(final int id) {
        final Callable<ArrayList<String>> query;
        if (id == R.id.genre_filter) {
            query = () -> mBooksDBModel.getGenre(table);
        } else if (id == R.id.autor_filter) {
            query = () -> mBooksDBModel.getAutors(table);
        } else if (id == R.id.artist_filter) {
            query = () -> mBooksDBModel.getArtists(table);
        } else {
            query = () -> mBooksDBModel.getSeries(table);
        }
        disposables.add(io.reactivex.Single.fromCallable(query)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(values -> {
                    if (!values.isEmpty()) {
                        getViewState().showFilterValues(id, values);
                    }
                }, e -> getViewState().showToast(R.string.error_load_data)));
    }

    @Override
    public void onFilterSelected(int filterId, String value) {
        if (books == null) {
            return;
        }
        if (value == null) {
            getViewState().setSubTitle("");
            flter = null;
            source = null;
            onSearch(mQuery);
            return;
        }
        if (filterId == R.id.source_filter) {
            source = sourceChoices.get(value);
            flter = null;
            bySource();
            onSearch(mQuery);
            return;
        }
        source = null;
        getViewState().setSubTitle(value);
        ArrayList<BookPOJO> filter = new ArrayList<>();
        for (BookPOJO book : books) {
            String text;
            if (filterId == R.id.genre_filter) {
                text = book.getGenre();
            } else if (filterId == R.id.autor_filter) {
                text = book.getAutor();
            } else if (filterId == R.id.artist_filter) {
                text = book.getArtist();
            } else if (filterId == R.id.series_filter) {
                text = book.getSeries();
            } else {
                return;
            }
            if (value.equals(text)) {
                filter.add(book);
            }
        }
        flter = filter;
        onSearch(mQuery);
    }

    private Comparator<BookPOJO> getComparator(@NotNull String sort) {
        if (mContext.getString(R.string.sort_value_name).equals(sort)) {
            return getComparator(R.id.name);
        } else if (mContext.getString(R.string.sort_value_genre).equals(sort)) {
            return getComparator(R.id.genre);
        } else if (mContext.getString(R.string.sort_value_autor).equals(sort)) {
            return getComparator(R.id.autor);
        } else if (mContext.getString(R.string.sort_value_artist).equals(sort)) {
            return getComparator(R.id.artist);
        } else if (mContext.getString(R.string.sort_value_series).equals(sort)) {
            return getComparator(R.id.series);
        } else {
            return getComparator(R.id.date);
        }
    }

    private interface Field {
        String of(BookPOJO book);
    }

    /** Orders by one text field; books without it go last. */
    private static Comparator<BookPOJO> byField(@NonNull Field field) {
        return (bookPOJO, t1) -> {
            String a = field.of(bookPOJO);
            String b = field.of(t1);
            boolean aEmpty = a == null || a.isEmpty();
            boolean bEmpty = b == null || b.isEmpty();
            if (aEmpty && bEmpty) {
                return 0;
            } else if (aEmpty) {
                return 1;
            } else if (bEmpty) {
                return -1;
            }
            return a.compareTo(b);
        };
    }

    private Comparator<BookPOJO> getComparator(int sort) {
        if (sort == R.id.name) {
            return byField(BookPOJO::getName);
        } else if (sort == R.id.genre) {
            return byField(BookPOJO::getGenre);
        } else if (sort == R.id.autor) {
            return byField(BookPOJO::getAutor);
        } else if (sort == R.id.artist) {
            return byField(BookPOJO::getArtist);
        } else if (sort == R.id.series) {
            return byField(BookPOJO::getSeries);
        } else {
            return (bookPOJO, t1) -> 0;
        }
    }
}
