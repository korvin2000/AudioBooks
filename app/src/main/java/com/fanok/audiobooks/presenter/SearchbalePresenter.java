package com.fanok.audiobooks.presenter;


import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
import com.arellomobile.mvp.InjectViewState;
import com.arellomobile.mvp.MvpPresenter;
import com.fanok.audiobooks.Consts;
import com.fanok.audiobooks.R;
import com.fanok.audiobooks.Url;
import com.fanok.audiobooks.adapter.BookSheetDownload;
import com.fanok.audiobooks.adapter.BookSheetPeople;
import com.fanok.audiobooks.interface_pacatge.searchable.SearchableModel;
import com.fanok.audiobooks.interface_pacatge.searchable.SearchablePresenter;
import com.fanok.audiobooks.interface_pacatge.searchable.SearchableView;
import com.fanok.audiobooks.model.AutorsModel;
import com.fanok.audiobooks.model.BooksDBModel;
import com.fanok.audiobooks.model.BooksModel;
import com.fanok.audiobooks.model.FavoritePeople;
import com.fanok.audiobooks.model.SearchebleModel;
import com.fanok.audiobooks.net.SiteHttp;
import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.pojo.GenrePOJO;
import com.fanok.audiobooks.pojo.SearcheblPOJO;
import com.fanok.audiobooks.pojo.SearchebleArrayPOJO;
import com.fanok.audiobooks.policy.DefaultCover;
import com.fanok.audiobooks.policy.ParentalControl;
import com.fanok.audiobooks.search.Genre;
import com.fanok.audiobooks.search.GenrePicks;
import com.fanok.audiobooks.search.PersonSearch;
import com.fanok.audiobooks.search.Provider;
import com.fanok.audiobooks.search.ProviderPage;
import com.fanok.audiobooks.search.SearchGrouper.Narration;
import com.fanok.audiobooks.search.SearchGrouper.Offer;
import com.fanok.audiobooks.search.SearchOrder;
import com.fanok.audiobooks.search.SearchRequest;
import com.fanok.audiobooks.search.SearchSession;
import com.fanok.audiobooks.search.SearchSnapshot;
import com.fanok.audiobooks.search.SearchUi;
import com.fanok.audiobooks.search.SearchUrls;
import com.fanok.audiobooks.search.SourceHealth;
import com.fanok.audiobooks.search.SourceProbe;
import com.fanok.audiobooks.search.TextNorm;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.squareup.picasso.Picasso;
import io.reactivex.BackpressureStrategy;
import io.reactivex.Completable;
import io.reactivex.Maybe;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.PublishSubject;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * The search screen. For books ({@link Consts#MODEL_BOOKS}) every active source is asked at once; the cards, each
 * source's state, the request, tab, order and grouping go through {@link SearchUi#reduce} off the main thread (at most
 * every {@link #REDUCE_MS}) and the screen draws the one state that comes out. The last search is kept
 * ({@link SearchSnapshot}): opening the screen again shows it without a request, and so does submitting the same
 * request within {@link #FRESH_MS}. For authors and readers ({@link Consts#MODEL_AUTOR}, {@link Consts#MODEL_ARTIST})
 * the current source alone is asked, as before.
 * <p>
 * The books of one person ({@link #searchPerson}, a favourite author or reader) are the same screen over other sources
 * ({@link PersonSearch}), newest first: it never writes the text search's order, scope or genres, never saves or
 * restores a snapshot, asks for no author/series chips; a typed query goes back to the text search as it was.
 */
@InjectViewState
public class SearchbalePresenter extends MvpPresenter<SearchableView> implements
        SearchablePresenter {

    private static final String TAG = "SearchbalePresenter";
    /** A site's search may take this long; one that declares slow pages ({@code timeoutMs}) gets longer. */
    private static final long SOURCE_TIMEOUT_SECONDS = 20;
    /** Arrivals within this time make one recomputation of the screen. */
    static final long REDUCE_MS = 300;
    /** The same request submitted again within this time shows its saved results instead of asking every site. */
    static final long FRESH_MS = 10 * 60 * 1000;
    private static final String PREF_SORT = "search_sort";
    private static final String PREF_GROUPED = "search_grouped";
    private static final String PREF_SCOPE = "search_scope";
    private static final String PREF_GENRES = "search_genres";
    /** Each source's last live search answer: "NAME:1:millis" (1: a failure with nothing found, 0: anything else). */
    private static final String PREF_ANSWERS = "search_answers";

    private final int mModelId;
    private final Context mContext;
    private final SharedPreferences mPreferences;
    private final BooksDBModel mBooksDBModel;

    // MODEL_BOOKS: the multi-source book search plus the author/series chips
    private BooksModel mBooksModel;
    private SearchableModel mSearchableModel;
    private SearchSession<BookPOJO> mSession;
    private Disposable mChips;
    /** The sites' own author and series lists for the query; the plates add the found cards' ones. */
    private SearcheblPOJO mSearcheblPOJO;
    private int filter = -1;
    private final ArrayList<Offer> mOffers = new ArrayList<>();
    private final HashMap<String, Offer> mOfferByUrl = new HashMap<>();
    private final HashMap<Provider, Integer> mArrived = new HashMap<>();
    private List<Provider> mSources = Collections.emptyList();
    private Set<SearchRequest.Field> mScope = EnumSet.noneOf(SearchRequest.Field.class);
    /** The genres to keep; empty: every genre. Kept across searches, like the scope. */
    private Set<Genre> mGenres = EnumSet.noneOf(Genre.class);
    private SearchRequest mRequest = SearchRequest.anywhere("");
    private SearchOrder.Sort mSort = SearchOrder.Sort.RELEVANCE;
    private boolean mGrouped = true;
    private boolean mSearched;
    private long mRestoredAt;
    /** When the shown results were fetched: the start of the last full pass, or a restored snapshot's time. */
    private long mFetchedAt;
    private int mGeneration;
    private SearchUi.State mState;
    private final PublishSubject<SearchUi.Input> mReduce = PublishSubject.create();
    private Disposable mReducing;
    private SearchSnapshot.Store mStore;
    /** Slow by nature (a longer page limit than the default): marked from the start. */
    private EnumSet<Provider> mSlow = EnumSet.noneOf(Provider.class);
    /** Silent the last time: asked, never waited for, until the listener retries one. */
    private EnumSet<Provider> mSilent = EnumSet.noneOf(Provider.class);
    /** Recomputes the screen when the clock alone changes it (a slow mark, the end of a wait). */
    private Disposable mTick;

    // the books of one person (FavoritePeople.AUTHOR or READER); -1 in a text search
    private int mPersonKind = -1;
    private String mPersonName;
    /** The person's source of each planned site: whether a site's cards are its listing's, what it knows. */
    private final Map<Provider, PersonSearch.Source> mPersonSources = new EnumMap<>(Provider.class);
    /** Sites whose page of the person is known or was learnt in this search: a card teaches only the others. */
    private final Set<Provider> mPersonKnown = EnumSet.noneOf(Provider.class);
    /** Reads the person's saved pages before the sources are planned. */
    private Disposable mPersonLoad;

    // MODEL_AUTOR / MODEL_ARTIST: one source, the current one
    private AutorsModel mModelGenre;
    private SearchSession<GenrePOJO> mGenreSession;
    private ArrayList<GenrePOJO> genre;

    private String query;

    /**
     * @param restoreLast show the last search at once: the screen was opened without a request (the search button),
     *                    or it comes back after its process died
     * @param ownQuery    the query this screen showed before its process died: another search's snapshot is not its
     *                    own; null when unknown
     */
    public SearchbalePresenter(int modelId, @NonNull Context context, boolean restoreLast, String ownQuery) {
        mContext = context.getApplicationContext();
        mPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        mModelId = modelId;
        mBooksDBModel = new BooksDBModel(context);
        switch (mModelId) {
            case Consts.MODEL_BOOKS:
                mSearcheblPOJO = new SearcheblPOJO();
                mSearchableModel = new SearchebleModel();
                mBooksModel = new BooksModel();
                mStore = new SearchSnapshot.Store(snapshotDir(mContext));
                mSort = sortOf(mPreferences.getString(PREF_SORT, null));
                mGrouped = mPreferences.getBoolean(PREF_GROUPED, true);
                mScope = scopeOf(mPreferences.getString(PREF_SCOPE, ""));
                mGenres = namesOf(Genre.class, mPreferences.getString(PREF_GENRES, ""));
                // one reduce at a time, always of the newest facts: inputs that came during a reduce are dropped
                mReducing = mReduce.toFlowable(BackpressureStrategy.LATEST)
                        .throttleLatest(REDUCE_MS, TimeUnit.MILLISECONDS, Schedulers.computation(), true)
                        .onBackpressureLatest()
                        .observeOn(Schedulers.single(), false, 1)
                        .flatMapMaybe(in -> Maybe.fromCallable(() -> SearchUi.reduce(in))
                                .doOnError(e -> Log.e(TAG, "reduce", e))
                                .onErrorComplete(), false, 1)
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(this::show, e -> Log.e(TAG, "search screen", e));
                SearchSnapshot last = restoreLast ? mStore.load(System.currentTimeMillis()) : null;
                if (last != null && (ownQuery == null || com.fanok.audiobooks.search.TextNorm.key(ownQuery)
                        .equals(com.fanok.audiobooks.search.TextNorm.key(last.text)))) {
                    restore(last);
                } else {
                    publish(); // "find a book, an author…" until the first request
                }
                break;
            case Consts.MODEL_AUTOR:
            case Consts.MODEL_ARTIST:
                genre = new ArrayList<>();
                mModelGenre = new AutorsModel();
                getViewState().showSeriesAndAutors(null);
                break;
        }
    }

    /** Where the last search is kept: private files that are not backed up (queries stay on the device). */
    public static File snapshotDir(Context context) {
        return new File(context.getNoBackupFilesDir(), "search");
    }

    public void setQuery(@NonNull String query) {
        this.query = query;
    }

    /** The submitted query; null for a presenter that has not searched (a new one after the process died). */
    public String getQuery() {
        return query;
    }

    /** Selects the source tab (-1: "All"); the screen follows from the state, without a request. */
    public void setFilter(int filter) {
        this.filter = filter;
        publish();
    }

    /** Where the words must be found; empty: anywhere. The loaded cards are only scored again: no request (§5.3). */
    public void setScope(Set<SearchRequest.Field> scope) {
        if (mPersonKind >= 0) {
            return; // a person's books are looked for everywhere; the text search's scope stays as saved
        }
        mScope = scope.isEmpty() ? EnumSet.noneOf(SearchRequest.Field.class) : EnumSet.copyOf(scope);
        mPreferences.edit().putString(PREF_SCOPE, joinNames(mScope)).apply();
        mRequest = new SearchRequest(mRequest.text, mScope);
        publish();
    }

    /** The genres to keep ("Чужой" in science fiction only); empty: every genre. The loaded cards only: no request. */
    public void setGenres(Set<Genre> genres) {
        mGenres = genres.isEmpty() ? EnumSet.noneOf(Genre.class) : EnumSet.copyOf(genres);
        if (mPersonKind < 0) {
            mPreferences.edit().putString(PREF_GENRES, joinNames(mGenres)).apply();
        }
        publish();
    }

    /** The order; a person's books keep theirs to themselves, the text search's saved one stays. */
    public void setSort(SearchOrder.Sort sort) {
        mSort = sort;
        if (mPersonKind < 0) {
            mPreferences.edit().putString(PREF_SORT, sort.name()).apply();
        }
        publish();
    }

    public void setGrouped(boolean grouped) {
        mGrouped = grouped;
        mPreferences.edit().putBoolean(PREF_GROUPED, grouped).apply();
        publish();
    }

    @Override
    public void onDestroy() {
        if (mModelId == Consts.MODEL_BOOKS) {
            save();
            if (mReducing != null) {
                mReducing.dispose();
            }
            if (mTick != null) {
                mTick.dispose();
            }
        }
        if (mSession != null) {
            mSession.dispose();
        }
        if (mGenreSession != null) {
            mGenreSession.dispose();
        }
        if (mChips != null) {
            mChips.dispose();
        }
        if (mPersonLoad != null) {
            mPersonLoad.dispose();
        }
        mBooksDBModel.closeDB();
    }

    /** Leaving the screen, or turning it: the search is saved as it stands, in case the process does not come back. */
    @Override
    public void detachView(SearchableView view) {
        super.detachView(view);
        save();
    }

    @Override
    public void onGenreItemClick(GenrePOJO item) {
        String tag;
        switch (mModelId) {
            case Consts.MODEL_AUTOR:
                tag = "autorBooks";
                break;
            case Consts.MODEL_ARTIST:
                tag = "artistBooks";
                break;
            default:
                return;
        }
        String url = item.getUrl();
        if (url.contains("genre")) url += "<page>/";
        getViewState().returnResult(url, item.getName(), Consts.MODEL_BOOKS, tag);
    }

    /**
     * A tap on a row: a single card opens; a work with one recording on one catalog opens its best card; otherwise
     * the listener chooses the recording and the site (search-refactoring.md §14 p.3).
     */
    @Override
    public void onBookItemClick(BookPOJO book) {
        SearchUi.Row row = mState == null ? null : mState.row(book.getUrl());
        if (row == null) {
            // a row of the previous state, while the new one is computed: only a card it still knows opens
            Offer o = mModelId == Consts.MODEL_BOOKS ? mOfferByUrl.get(book.getUrl()) : null;
            if (o != null) {
                open(o.book);
            } else if (mModelId != Consts.MODEL_BOOKS) {
                getViewState().startBookActivity(book);
            }
            return;
        }
        if (row.group == null || row.group.offers.size() == 1) {
            open(row.offer.book);
            return;
        }
        List<Narration> narrations = row.group.narrations;
        if (narrations.size() == 1 && narrations.get(0).catalogs() == 1) {
            open(narrations.get(0).best.book);
            return;
        }
        getViewState().showNarrations(row.offer.book.getName(), narrations);
    }

    /** The listener chose a card of a group. */
    public void openOffer(String url) {
        Offer o = mOfferByUrl.get(url);
        if (o != null) {
            open(o.book);
        }
    }

    /** A book opens its page; a site's collection ("Подборка", a list of books) opens as that site's listing. */
    private void open(BookPOJO book) {
        Provider p = Provider.ofUrl(book.getUrl());
        if (p != null && p.parser.opensAsListing(book.getUrl())) {
            getViewState().returnResult(book.getUrl(), book.getName(), Consts.MODEL_BOOKS, "collectionBooks");
        } else {
            getViewState().startBookActivity(book);
        }
    }

    @Override
    public void loadBoks() {
        loadBoks(false);
    }

    /** Searches {@link #query}; {@code force}: ask the sites even when a fresh saved result of it exists. */
    public void loadBoks(boolean force) {
        if (query == null) {
            return;
        }
        getViewState().setNotFoundVisibile(false);
        getViewState().showSourcesFailed(0);
        if (mModelId == Consts.MODEL_BOOKS) {
            // the same text search submitted again: the scope and tab chosen on it since the last save stay
            boolean same = mSearched && mPersonKind < 0 && query.equals(mRequest.text);
            Set<SearchRequest.Field> scope = mScope;
            int tab = filter;
            leavePerson();
            long now = System.currentTimeMillis();
            SearchSnapshot last = force ? null : mStore.load(now);
            if (last != null && last.text.equals(query) && now - last.savedAt < FRESH_MS && !last.cards.isEmpty()
                    && complete(last)) {
                restore(last);
                if (same) {
                    Provider shown = Provider.ofSource(tab);
                    if (tab == -1 || shown != null && mSources.contains(shown)) {
                        filter = tab;
                    }
                    setScope(scope); // publishes
                }
                return;
            }
            loadChips(query);
            startBooksSearch(query);
        } else {
            genre = new ArrayList<>();
            getViewState().showDataGenres(genre);
            startGenreSearch(query);
        }
    }

    /** "Обновить": the person's books asked again, or the text search asking every site. */
    public void refresh() {
        if (mPersonKind >= 0) {
            startPersonSearch();
        } else {
            loadBoks(true);
        }
    }

    /**
     * The books of a person ({@link FavoritePeople#AUTHOR} or {@link FavoritePeople#READER}) on every active site,
     * newest first as far as each site tells ({@link PersonSearch}). The text search's order, scope, genres and last
     * search stay as saved; nothing of this search is saved.
     */
    public void searchPerson(int kind, @NonNull String name) {
        if (mModelId != Consts.MODEL_BOOKS) {
            return;
        }
        mPersonKind = kind;
        mPersonName = name;
        query = name;
        mSort = SearchOrder.Sort.NEWEST;
        mGenres = EnumSet.noneOf(Genre.class); // a saved genre filter would hide the person's other books
        getViewState().setNotFoundVisibile(false);
        getViewState().showSourcesFailed(0);
        startPersonSearch();
    }

    /** The person's saved pages are read off the main thread; then every site is planned and asked. */
    private void startPersonSearch() {
        if (mSession != null) {
            mSession.dispose();
            mSession = null;
        }
        if (mChips != null) {
            mChips.dispose();
            mChips = null;
        }
        if (mPersonLoad != null) {
            mPersonLoad.dispose();
        }
        if (mTick != null) {
            mTick.dispose();
            mTick = null;
        }
        mGeneration++;
        clearOffers();
        mPersonSources.clear();
        mPersonKnown.clear();
        mSearcheblPOJO = new SearcheblPOJO(); // no author/series chips: the plates come from the cards
        mSources = Collections.emptyList();
        filter = -1;
        mRestoredAt = 0;
        mFetchedAt = System.currentTimeMillis();
        mSearched = false; // until the sites are planned
        mRequest = SearchRequest.anywhere(mPersonName); // a listing's card is the person's however its field is spelled
        final int gen = mGeneration;
        final int kind = mPersonKind;
        final String name = mPersonName;
        final List<Provider> sites = enabled();
        mPersonLoad = Single.fromCallable(() -> pages(FavoritePeople.get(mContext).find(kind, name), sites))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(urls -> planPerson(gen, urls), e -> {
                    Log.w(TAG, "favourite person's pages", e);
                    planPerson(gen, Collections.<Provider, String>emptyMap());
                });
    }

    /** The saved pages of a favourite person by site; none for a person who is not a favourite. */
    private static Map<Provider, String> pages(FavoritePeople.Person person, List<Provider> sites) {
        Map<Provider, String> urls = new EnumMap<>(Provider.class);
        if (person != null) {
            for (Provider p : sites) {
                String url = person.urlFor(p);
                if (url != null) {
                    urls.put(p, url);
                }
            }
        }
        return urls;
    }

    private void planPerson(int gen, Map<Provider, String> urls) {
        if (gen != mGeneration || mPersonKind < 0) {
            return; // another search started meanwhile
        }
        final int kind = mPersonKind;
        final String name = mPersonName;
        // a page found by a people search is kept for the next time (nothing for a person who is not a favourite)
        PersonSearch.Found found = (p, url) -> AndroidSchedulers.mainThread().scheduleDirect(() -> {
            if (gen == mGeneration) {
                mPersonKnown.add(p);
            }
            FavoritePeople.get(mContext).learnUrl(kind, name, url);
        });
        List<PersonSearch.Source> planned = PersonSearch.plan(name, kind == FavoritePeople.READER, urls, enabled(),
                mPersonNet, found);
        List<Provider> sources = new ArrayList<>();
        for (PersonSearch.Source s : planned) {
            sources.add(s.provider);
            mPersonSources.put(s.provider, s);
            if (s.listing()) {
                mPersonKnown.add(s.provider);
            }
        }
        mSources = sources;
        mSearched = true;
        classify(sources);
        mSession = session(new ArrayList<SearchSession.Source<BookPOJO>>(planned));
        mSession.search(name);
        publish();
    }

    /** Back to the text search: its saved order and genres apply again (its scope was never changed). */
    private void leavePerson() {
        if (mPersonKind < 0) {
            return;
        }
        mPersonKind = -1;
        mPersonName = null;
        mPersonSources.clear();
        mPersonKnown.clear();
        if (mPersonLoad != null) {
            mPersonLoad.dispose();
            mPersonLoad = null;
        }
        mSort = sortOf(mPreferences.getString(PREF_SORT, null));
        mGenres = namesOf(Genre.class, mPreferences.getString(PREF_GENRES, ""));
    }

    /** The sites as the catalog and the people lists reach them, for a person's sources (called off the main thread). */
    private final PersonSearch.Net mPersonNet = new PersonSearch.Net() {
        private final AutorsModel people = new AutorsModel();

        @Override
        public ProviderPage<BookPOJO> listing(Provider p, String url, int page) throws IOException {
            return p.parser.loadListing(p.parser.pageUrl(url, page), page); // BooksPresenter's paging
        }

        @Override
        public ProviderPage<GenrePOJO> people(Provider p, boolean reader, String name) throws IOException {
            return people.search(p, reader ? SearchUrls.Category.PERFORMERS : SearchUrls.Category.AUTHORS, name, 1);
        }

        @Override
        public ProviderPage<BookPOJO> books(Provider p, String query, int page) throws IOException {
            return mBooksModel.search(p, query, page);
        }
    };

    /** Every source of a saved search had answered: a pass that was left half-way is asked again on submit. */
    private static boolean complete(SearchSnapshot s) {
        for (SearchSnapshot.Source src : s.sources) {
            if (src.status == null) {
                return false;
            }
        }
        return true;
    }

    /**
     * The next page: on a site's tab of that site alone, otherwise of every site that found something and has more;
     * a slow site still on its first page no longer holds the others back (W15), and a stopped one waits for "retry".
     */
    @Override
    public void loadNext() {
        if (mModelId != Consts.MODEL_BOOKS) {
            if (mGenreSession != null && mGenreSession.loadNext()) {
                getViewState().showProgres(true);
            }
            return;
        }
        if (mSession == null || mState == null) {
            return;
        }
        Provider tab = Provider.ofSource(filter);
        boolean started = false;
        for (SearchUi.Chip c : mState.chips) {
            if ((tab == null || c.source == tab) && c.kind == SearchUi.ChipKind.FOUND && c.hasMore) {
                started |= mSession.loadNext(c.source.name());
            }
        }
        if (started) {
            publish();
        }
    }

    /** Every source that failed or was stopped asks again for the page it did not get. */
    @Override
    public void retryFailed() {
        if (mModelId != Consts.MODEL_BOOKS) {
            if (mGenreSession != null && !mGenreSession.isRunning() && mGenreSession.failedCount() > 0) {
                getViewState().setNotFoundVisibile(false);
                getViewState().showSourcesFailed(0);
                getViewState().showProgres(true);
                mGenreSession.retryFailed();
            }
            return;
        }
        if (mState != null) {
            for (Provider p : mState.failed) {
                retry(p);
            }
        }
        publish();
    }

    /** The source {@code p} alone asks again (a long press on its chip, or its tab's button). */
    public void retrySource(Provider p) {
        retry(p);
        publish();
    }

    /**
     * A failed source asks again for its page; a stopped one loads it; a source that answered is left alone. The
     * listener asked for it: the pass waits for it, even when it was silent the last time.
     */
    private void retry(Provider p) {
        mSilent.remove(p);
        if (mSession == null || mSession.retry(p.name())) {
            return;
        }
        for (SearchSession.State s : mSession.states()) {
            if (s.name.equals(p.name()) && !s.running && s.status == null) {
                mSession.loadNext(p.name()); // stopped before it answered: its page is still to load
            }
        }
    }

    /** Stops waiting: what came stays, the sites that did not answer can be asked again. */
    public void stop() {
        if (mSession != null) {
            mSession.dispose();
            publish();
            save();
        }
    }

    private SearchSession<BookPOJO> booksSession(List<Provider> providers) {
        List<SearchSession.Source<BookPOJO>> sources = new ArrayList<>();
        for (Provider provider : providers) {
            sources.add(new SearchSession.Source<BookPOJO>() {
                @Override
                public String name() {
                    return provider.name();
                }

                @Override
                public Single<ProviderPage<BookPOJO>> load(String query, int page) {
                    return Single.fromCallable(() -> mBooksModel.search(provider, query, page));
                }

                @Override
                public long timeoutSeconds() {
                    return provider.requestSeconds();
                }
            });
        }
        return session(sources);
    }

    /** A search over {@code sources}: its cards become offers, its answers and its end publish the screen. */
    private SearchSession<BookPOJO> session(List<SearchSession.Source<BookPOJO>> sources) {
        return new SearchSession<>(sources, book -> Url.canonical(book.getUrl()), Schedulers.io(),
                AndroidSchedulers.mainThread(), SOURCE_TIMEOUT_SECONDS, new SearchSession.Listener<BookPOJO>() {
            @Override
            public void onItems(List<BookPOJO> items) {
                arrived(items);
                publish();
            }

            @Override
            public void onFinished(List<BookPOJO> items, int failedSources) {
                arrived(items);
                publish();
                save();
            }

            @Override
            public void onSourceState(SearchSession.State state) {
                recordAnswer(state);
                publish();
            }
        });
    }

    private void startBooksSearch(String q) {
        if (mSession != null) {
            mSession.dispose();
        }
        mGeneration++;
        clearOffers();
        filter = -1;
        mRestoredAt = 0;
        mFetchedAt = System.currentTimeMillis();
        mSearched = true;
        mRequest = new SearchRequest(q, mScope);
        List<Provider> sources = enabled();
        mSources = sources;
        classify(sources);
        mSession = booksSession(sources);
        mSession.search(q);
        publish();
    }

    /** The active sites the search may ask, the one selected in the settings first. */
    private List<Provider> enabled() {
        List<Provider> sources = new ArrayList<>();
        for (Provider provider : Provider.selectedFirst()) {
            if (mPreferences.getBoolean(provider.prefKey, true)) {
                sources.add(provider);
            }
        }
        return sources;
    }

    /** Which of {@code sources} are slow by nature, and which were silent the last time. */
    private void classify(List<Provider> sources) {
        mSlow = EnumSet.noneOf(Provider.class);
        for (Provider p : sources) {
            if (p.parser.timeoutMs() > SiteHttp.TIMEOUT_MS) {
                mSlow.add(p); // Bookish, Audioboo, Аудиокниги.fun
            }
        }
        mSilent = silent(sources);
    }

    /**
     * The sources silent the last time: their last live search answer was a failure with nothing found, or a check of
     * the sites made after that answer failed them; whichever is newer decides.
     */
    private EnumSet<Provider> silent(List<Provider> sources) {
        Map<String, long[]> answers = answers(mPreferences.getString(PREF_ANSWERS, ""));
        Map<Provider, SourceProbe.Result> checks = SourceHealth.last(mPreferences);
        EnumSet<Provider> out = EnumSet.noneOf(Provider.class);
        for (Provider p : sources) {
            long[] answer = answers.get(p.name());
            SourceProbe.Result check = checks.get(p);
            boolean silent = check != null && (answer == null || check.at > answer[1])
                    ? ProviderPage.isFailure(check.status) : answer != null && answer[0] == 1;
            if (silent) {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * A source answered a live load (never a restored one): its answer is kept, with its own time, for the next search
     * ({@link #silent}); one that answers is waited for again in this one too.
     */
    private void recordAnswer(SearchSession.State st) {
        if (st.running || st.status == null) {
            return;
        }
        boolean failed = st.count == 0 && st.isFailure();
        Provider p = providerOf(st.name);
        if (p != null && !failed) {
            mSilent.remove(p);
        }
        if (mPersonKind >= 0) {
            return; // a person's listing or lookup says nothing of the site's text search
        }
        Map<String, long[]> answers = answers(mPreferences.getString(PREF_ANSWERS, ""));
        answers.put(st.name, new long[]{failed ? 1 : 0, System.currentTimeMillis()});
        StringBuilder stored = new StringBuilder();
        for (Map.Entry<String, long[]> e : answers.entrySet()) {
            stored.append(stored.length() == 0 ? "" : ",").append(e.getKey()).append(':').append(e.getValue()[0])
                    .append(':').append(e.getValue()[1]);
        }
        mPreferences.edit().putString(PREF_ANSWERS, stored.toString()).apply();
    }

    /** {@link #PREF_ANSWERS} read: name → {failed (1 or 0), millis}; a broken entry is skipped. */
    static Map<String, long[]> answers(String stored) {
        Map<String, long[]> out = new LinkedHashMap<>();
        for (String entry : stored == null ? new String[0] : stored.split(",")) {
            String[] parts = entry.trim().split(":");
            if (parts.length == 3) {
                try {
                    out.put(parts[0], new long[]{Long.parseLong(parts[1]), Long.parseLong(parts[2])});
                } catch (NumberFormatException ignored) {
                    // not ours: skipped
                }
            }
        }
        return out;
    }

    /** Shows a saved search as it was left, with no request; its sources can go on from their saved pages. */
    private void restore(SearchSnapshot s) {
        if (mSession != null) {
            mSession.dispose();
        }
        mGeneration++;
        clearOffers();
        List<Provider> sources = new ArrayList<>();
        Map<String, ProviderPage.Status> statuses = new HashMap<>();
        Map<String, Integer> cursors = new HashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        Map<String, String> queries = new HashMap<>();
        Set<String> caseRetried = new HashSet<>();
        for (SearchSnapshot.Source src : s.sources) {
            Provider p = providerOf(src.name);
            if (p == null) {
                continue;
            }
            sources.add(p);
            if (src.query != null) {
                queries.put(src.name, src.query);
            }
            if (src.caseRetried) {
                caseRetried.add(src.name);
            }
            if (src.status != null) {
                try {
                    statuses.put(src.name, ProviderPage.Status.valueOf(src.status));
                } catch (IllegalArgumentException ignored) {
                    // a status of a later version: as if the source had not answered
                }
            }
            cursors.put(src.name, src.nextPage);
            counts.put(src.name, src.count);
        }
        List<BookPOJO> books = new ArrayList<>();
        for (SearchSnapshot.Card c : s.cards) {
            Provider p = providerOf(c.source);
            if (p != null && sources.contains(p)) {
                books.add(c.book);
            }
        }
        mSources = sources;
        classify(sources); // its next pages are marked and waited for as in a live search
        mSession = booksSession(sources);
        mSession.restore(s.text, books, statuses, cursors, counts, queries, caseRetried);
        arrived(mSession.items());
        query = s.text;
        mScope = scopeOf(join(s.scope));
        mRequest = new SearchRequest(s.text, mScope);
        Provider tab = providerOf(s.tab);
        filter = tab != null && sources.contains(tab) ? tab.sourceId : -1;
        mSearched = !s.text.isEmpty() && !s.sources.isEmpty(); // an old snapshot keeps only its request
        mRestoredAt = s.savedAt;
        mFetchedAt = s.savedAt;
        if (mChips != null) {
            mChips.dispose();
            mChips = null;
        }
        mSearcheblPOJO = s.people != null ? s.people : new SearcheblPOJO();
        getViewState().showQuery(s.text);
        publish();
    }

    private void clearOffers() {
        mOffers.clear();
        mOfferByUrl.clear();
        mArrived.clear();
        mState = null;
    }

    /**
     * Turns the session's new items into offers, once each, in order of arrival. A site's own rank is its arrival
     * count, the site's own sequence (a source loads its pages one after another): relevance in a search, the person's
     * books newest first in a person's listing ({@link PersonSearch#rank}).
     */
    private void arrived(List<BookPOJO> items) {
        boolean reader = mPersonKind == FavoritePeople.READER;
        TextNorm.Person person = mPersonKind >= 0 ? TextNorm.person(mPersonName) : null;
        for (BookPOJO book : items) {
            String url = book.getUrl();
            if (url == null || book.getName() == null || mOfferByUrl.containsKey(url)) {
                continue;
            }
            Provider p = Provider.ofUrl(url);
            Integer before = mArrived.get(p);
            int n = before == null ? 0 : before;
            mArrived.put(p, n + 1);
            int rank;
            if (person != null) {
                PersonSearch.Source source = p == null ? null : mPersonSources.get(p);
                rank = PersonSearch.rank(source != null && source.listing(), n);
                learn(p, book, person, reader);
            } else {
                // Bookish lists by date and База Книг by views: their order says nothing about the match (§6.5)
                rank = p == Provider.BOOKISH || p == Provider.BAZA_KNIG ? Integer.MAX_VALUE : n;
            }
            int order = mSources.indexOf(p);
            Offer o = new Offer(book, p, order < 0 ? mSources.size() : order, rank, mOffers.size());
            mOffers.add(o);
            mOfferByUrl.put(url, o);
        }
    }

    /**
     * A card of a site whose page of the person is not known yet teaches it ({@link PersonSearch#teaches}: a field of
     * that one person, the very same name): the favourite keeps it, so the next time the site is listed.
     */
    private void learn(Provider p, BookPOJO book, TextNorm.Person person, boolean reader) {
        if (p == null || mPersonKnown.contains(p)) {
            return;
        }
        PersonSearch.Source source = mPersonSources.get(p);
        if (source == null || source.mode != PersonSearch.Mode.SEARCH) {
            return; // a site with a people search learns only its one exact hit: namesakes are never guessed
        }
        String page = PersonSearch.teaches(book, person, reader);
        if (page != null && Provider.ofUrl(page) == p) {
            mPersonKnown.add(p);
            FavoritePeople.get(mContext).learnUrl(mPersonKind, mPersonName, page);
        }
    }

    /** Asks for the screen to be computed again from the current facts. */
    private void publish() {
        if (mModelId != Consts.MODEL_BOOKS) {
            return;
        }
        SearchUi.Input in = new SearchUi.Input();
        in.request = mRequest;
        in.genres = mGenres;
        in.offers = new ArrayList<>(mOffers);
        in.sources = mSources;
        Map<String, SearchSession.State> states = new LinkedHashMap<>();
        if (mSession != null) {
            for (SearchSession.State s : mSession.states()) {
                states.put(s.name, s);
            }
        }
        in.states = states;
        in.tab = Provider.ofSource(filter);
        in.sort = mSort;
        in.grouped = mGrouped;
        in.searched = mSearched;
        in.restoredAt = mRestoredAt;
        in.generation = mGeneration;
        in.people = mSearcheblPOJO;
        in.nowMs = System.currentTimeMillis(); // the clock of SearchSession.State#startedAt (the main scheduler's)
        in.slow = EnumSet.copyOf(mSlow);
        in.silent = EnumSet.copyOf(mSilent);
        in.personKind = mPersonKind;
        Set<String> shown = new HashSet<>();
        if (mState != null) {
            for (SearchUi.Row r : mState.rows) {
                shown.add(r.key);
            }
        }
        in.shownIds = shown;
        mReduce.onNext(in);
    }

    private void show(SearchUi.State state) {
        if (state.input.generation != mGeneration) {
            return; // a late result of a superseded search
        }
        mState = state;
        getViewState().showState(state);
        if (mTick != null) {
            mTick.dispose();
            mTick = null;
        }
        if (state.nextChangeAt > 0) {
            long delay = Math.max(0, state.nextChangeAt - System.currentTimeMillis()) + 20;
            mTick = Completable.timer(delay, TimeUnit.MILLISECONDS, AndroidSchedulers.mainThread())
                    .subscribe(this::publish);
        }
    }

    /** Saves the current search for the next visit (§8); a person's books never replace the last text search. */
    private void save() {
        if (mModelId != Consts.MODEL_BOOKS || mSession == null || !mSearched || mPersonKind >= 0) {
            return;
        }
        SearchSnapshot s = new SearchSnapshot();
        s.savedAt = mFetchedAt > 0 ? mFetchedAt : System.currentTimeMillis();
        s.people = mSearcheblPOJO;
        s.text = mRequest.text;
        for (SearchRequest.Field f : mScope) {
            s.scope.add(f.name());
        }
        Provider tab = Provider.ofSource(filter);
        s.tab = tab == null ? null : tab.name();
        s.sort = mSort.name();
        s.grouped = mGrouped;
        Map<String, Integer> cursors = mSession.cursors();
        Map<String, String> queries = mSession.queries();
        for (SearchSession.State st : mSession.states()) {
            SearchSnapshot.Source src = new SearchSnapshot.Source();
            src.name = st.name;
            src.status = st.status == null ? null : st.status.name();
            src.count = st.count;
            src.nextPage = cursors.get(st.name);
            String own = queries.get(st.name);
            src.query = own == null || own.equals(mSession.query()) ? null : own;
            src.caseRetried = mSession.caseRetried(st.name);
            s.sources.add(src);
        }
        for (Offer o : mOffers) {
            if (o.source == null) {
                continue;
            }
            SearchSnapshot.Card c = new SearchSnapshot.Card();
            c.source = o.source.name();
            c.rank = o.nativeRank;
            c.book = o.book;
            s.cards.add(c);
        }
        mStore.save(s);
    }

    private static Provider providerOf(String name) {
        if (name == null) {
            return null;
        }
        try {
            return Provider.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The text search's saved order; "newest first" is a person's books' alone (a search's site order is relevance). */
    static SearchOrder.Sort sortOf(String name) {
        if (name != null) {
            try {
                SearchOrder.Sort sort = SearchOrder.Sort.valueOf(name);
                return sort == SearchOrder.Sort.NEWEST ? SearchOrder.Sort.RELEVANCE : sort;
            } catch (IllegalArgumentException ignored) {
                // an order of a later version
            }
        }
        return SearchOrder.Sort.RELEVANCE;
    }

    static Set<SearchRequest.Field> scopeOf(String stored) {
        return namesOf(SearchRequest.Field.class, stored);
    }

    /** The constants named in {@code stored} ("A,B"); an empty or unknown name (of a later version) is skipped. */
    static <E extends Enum<E>> Set<E> namesOf(Class<E> type, String stored) {
        Set<E> out = EnumSet.noneOf(type);
        for (String part : stored == null ? new String[0] : stored.split(",")) {
            try {
                out.add(Enum.valueOf(type, part.trim()));
            } catch (IllegalArgumentException ignored) {
                // empty, or a name of a later version
            }
        }
        return out;
    }

    private static String joinNames(Set<? extends Enum<?>> names) {
        StringBuilder stored = new StringBuilder();
        for (Enum<?> e : names) {
            stored.append(stored.length() == 0 ? "" : ",").append(e.name());
        }
        return stored.toString();
    }

    private static String join(List<String> parts) {
        StringBuilder s = new StringBuilder();
        for (String p : parts == null ? Collections.<String>emptyList() : parts) {
            s.append(s.length() == 0 ? "" : ",").append(p);
        }
        return s.toString();
    }

    private void startGenreSearch(String q) {
        if (mGenreSession != null) {
            mGenreSession.dispose();
        }
        final Provider provider = Provider.ofSource(Consts.getSOURCE());
        final SearchUrls.Category category = mModelId == Consts.MODEL_AUTOR
                ? SearchUrls.Category.AUTHORS : SearchUrls.Category.PERFORMERS;
        if (provider == null || SearchUrls.url(provider, category, q, 1) == null) {
            getViewState().showToast(R.string.section_not_available);
            getViewState().setNotFoundVisibile(true);
            return;
        }
        SearchSession.Source<GenrePOJO> source = new SearchSession.Source<GenrePOJO>() {
            @Override
            public String name() {
                return provider.name();
            }

            @Override
            public Single<ProviderPage<GenrePOJO>> load(String query, int page) {
                return Single.fromCallable(() -> mModelGenre.search(provider, category, query, page));
            }
        };
        mGenreSession = new SearchSession<>(Collections.singletonList(source), GenrePOJO::getUrl, Schedulers.io(),
                AndroidSchedulers.mainThread(), SOURCE_TIMEOUT_SECONDS, new SearchSession.Listener<GenrePOJO>() {
            @Override
            public void onItems(List<GenrePOJO> items) {
                genre = new ArrayList<>(items);
                getViewState().showDataGenres(genre);
            }

            @Override
            public void onFinished(List<GenrePOJO> items, int failedSources) {
                genre = new ArrayList<>(items);
                getViewState().showDataGenres(genre);
                getViewState().showProgres(false);
                getViewState().showSourcesFailed(failedSources);
                getViewState().setNotFoundVisibile(isNotFound(true, failedSources, genre.isEmpty()));
            }
        });
        getViewState().showProgres(true);
        mGenreSession.search(q);
    }

    /** Author and series chips: independent of the book list and of each other (SRCH-13). */
    private void loadChips(String q) {
        if (mChips != null) {
            mChips.dispose();
        }
        mSearcheblPOJO = new SearcheblPOJO();
        mChips = mSearchableModel.chips(mPreferences, q)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(pojo -> {
                    mSearcheblPOJO = mSearcheblPOJO.concat(mSearcheblPOJO, pojo);
                    publish();
                }, e -> Log.w(TAG, "author and series chips", e));
    }

    /** "Not found" only for a finished search that found nothing while every source answered. */
    static boolean isNotFound(boolean finished, int failedSources, boolean nothingFound) {
        return finished && failedSources == 0 && nothingFound;
    }

    @Override
    public void onAutorsListItemClick(SearchebleArrayPOJO item) {
        getViewState().returnResult(item.getUrl(), item.getName(), Consts.MODEL_BOOKS, "searchebleBooks");
    }

    @Override
    public void onSeriesListItemClick(SearchebleArrayPOJO item) {
        getViewState().returnResult(item.getUrl(), item.getName(), Consts.MODEL_BOOKS, "searchebleBooks");
    }

    /** The book menu, for the row's own card (a group's representative). */
    @Override
    public void onBookItemLongClick(View view, BookPOJO shown, LayoutInflater layoutInflater) {
        SearchUi.Row row = mState == null ? null : mState.row(shown.getUrl());
        Offer known = mOfferByUrl.get(shown.getUrl());
        if (row == null && known == null) {
            return; // a display copy (a group's facts) must never reach the favourites
        }
        final BookPOJO bookPOJO = row != null ? row.offer.book : known.book;

        @SuppressLint("InflateParams") View layout = layoutInflater.inflate(
                R.layout.bootom_sheet_books_menu, null);
        final BottomSheetDialog dialog = new BottomSheetDialog(view.getContext());
        dialog.setContentView(layout);

        TextView open = layout.findViewById(R.id.open);
        TextView add = layout.findViewById(R.id.addFavorite);
        TextView remove = layout.findViewById(R.id.removeFavorite);
        TextView genre = layout.findViewById(R.id.genre);
        TextView author = layout.findViewById(R.id.author);
        TextView artist = layout.findViewById(R.id.artist);
        TextView series = layout.findViewById(R.id.series);

        ImageView imageView = layout.findViewById(R.id.imageView);
        // masked as the row is: the row's copy carries its group's most restricted genre
        Drawable mask = ParentalControl.coverMask(imageView.getContext(), shown);
        if (mask == null) {
            mask = ParentalControl.coverMask(imageView.getContext(), bookPOJO);
        }
        if (mask != null) {
            imageView.setImageDrawable(mask);
        } else if (DefaultCover.replaces(bookPOJO.getPhoto())) {
            imageView.setImageDrawable(DefaultCover.drawable(imageView.getContext(), bookPOJO.getUrl()));
        } else {
            Picasso.get()
                    .load(bookPOJO.getPhoto())
                    .error(android.R.drawable.ic_menu_gallery)
                    .placeholder(android.R.drawable.ic_menu_gallery)
                    .into(imageView);
        }

        TextView title = layout.findViewById(R.id.title);
        title.setText(bookPOJO.getName());

        TextView authorName = layout.findViewById(R.id.authorName);
        if (bookPOJO.getAutor() != null) {
            authorName.setText(bookPOJO.getAutor());
            authorName.setVisibility(View.VISIBLE);
        } else {
            authorName.setVisibility(View.GONE);
        }

        if (bookPOJO.getSeries() == null || bookPOJO.getUrlSeries() == null) {
            series.setVisibility(View.GONE);
        } else {
            series.setVisibility(View.VISIBLE);
        }

        if (mBooksDBModel.inFavorite(bookPOJO)) {
            add.setVisibility(View.GONE);
            remove.setVisibility(View.VISIBLE);
        } else {
            add.setVisibility(View.VISIBLE);
            remove.setVisibility(View.GONE);
        }

        open.setOnClickListener(view1 -> {
            dialog.dismiss();
            open(bookPOJO);
        });

        add.setOnClickListener(view1 -> {
            dialog.dismiss();
            mBooksDBModel.addFavorite(bookPOJO);
        });

        remove.setOnClickListener(view1 -> {
            dialog.dismiss();
            mBooksDBModel.removeFavorite(bookPOJO);
        });

        if (bookPOJO.getUrlGenre() != null) {
            genre.setVisibility(View.VISIBLE);
            genre.setOnClickListener(view1 -> {
                dialog.dismiss();
                GenrePicks.record(view1.getContext(), bookPOJO.getGenre());
                getViewState().returnResult(bookPOJO.getUrlGenre(),
                        bookPOJO.getGenre(), Consts.MODEL_BOOKS, "searchebleBooks");
            });
        } else {
            genre.setVisibility(View.GONE);
        }

        if (bookPOJO.getUrlAutor() != null) {
            author.setVisibility(View.VISIBLE);
            author.setOnClickListener(view1 -> {
                dialog.dismiss();
                getViewState().returnResult(bookPOJO.getUrlAutor(),
                        bookPOJO.getAutor(), Consts.MODEL_BOOKS, "searchebleBooks");
            });
        } else {
            author.setVisibility(View.GONE);
        }

        if (bookPOJO.getUrlArtist() != null) {
            artist.setVisibility(View.VISIBLE);
            artist.setOnClickListener(view1 -> {
                dialog.dismiss();
                getViewState().returnResult(bookPOJO.getUrlArtist(),
                        bookPOJO.getArtist(), Consts.MODEL_BOOKS, "searchebleBooks");
            });
        } else {
            artist.setVisibility(View.GONE);
        }

        series.setOnClickListener(view12 -> {
            dialog.dismiss();
            getViewState().returnResult(bookPOJO.getUrlSeries(),
                    bookPOJO.getSeries(), Consts.MODEL_BOOKS, "searchebleBooks");
        });
        BookSheetPeople.bind(layout, bookPOJO); // stars for the author and the reader, once their rows are set
        BookSheetDownload.bind(layout, bookPOJO, dialog); // the real offer, not a group's display copy
        dialog.show();
    }
}
