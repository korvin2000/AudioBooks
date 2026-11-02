package com.fanok.audiobooks.activity;

import static com.fanok.audiobooks.Consts.handleUserInput;

import android.app.Activity;
import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.SearchRecentSuggestions;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.format.DateUtils;
import android.text.style.RelativeSizeSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.arellomobile.mvp.MvpAppCompatActivity;
import com.arellomobile.mvp.presenter.InjectPresenter;
import com.arellomobile.mvp.presenter.ProvidePresenter;
import com.bumptech.glide.Glide;
import com.fanok.audiobooks.Consts;
import com.fanok.audiobooks.GridSpacingItemDecoration;
import com.fanok.audiobooks.MySuggestionProvider;
import com.fanok.audiobooks.R;
import com.fanok.audiobooks.Url;
import com.fanok.audiobooks.adapter.BooksListAddapter;
import com.fanok.audiobooks.adapter.GenreListAddapter;
import com.fanok.audiobooks.adapter.SearchebleAdapter;
import com.fanok.audiobooks.databinding.ActivitySearchableBinding;
import com.fanok.audiobooks.fragment.SourceTexts;
import com.fanok.audiobooks.interface_pacatge.searchable.SearchableView;
import com.fanok.audiobooks.model.FavoritePeople;
import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.pojo.GenrePOJO;
import com.fanok.audiobooks.pojo.SearcheblPOJO;
import com.fanok.audiobooks.pojo.SearchebleArrayPOJO;
import com.fanok.audiobooks.policy.ParentalControl;
import com.fanok.audiobooks.presenter.SearchbalePresenter;
import com.fanok.audiobooks.search.Genre;
import com.fanok.audiobooks.search.Provider;
import com.fanok.audiobooks.search.SearchGrouper.Group;
import com.fanok.audiobooks.search.SearchGrouper.Narration;
import com.fanok.audiobooks.search.SearchGrouper.Offer;
import com.fanok.audiobooks.search.SearchMatcher;
import com.fanok.audiobooks.search.SearchOrder;
import com.fanok.audiobooks.search.SearchRequest;
import com.fanok.audiobooks.search.SearchUi;
import com.fanok.audiobooks.search.SourceInfo;
import com.fanok.audiobooks.search.TextNorm;
import com.fanok.audiobooks.util.RussianDates;
import com.fanok.audiobooks.util.RussianResources;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jetbrains.annotations.NotNull;

public class SearchableActivity extends MvpAppCompatActivity implements SearchableView {

    private static final String TAG = "SearchableActivity";

    /** The submitted query, kept for a screen recreated after its process died (the presenter is new then). */
    private static final String STATE_QUERY = "query";
    /** Fill of a source tile in its state's colour, faint enough for the text in every theme (22 %). */
    private static final int FILL_ALPHA = 56;
    /** A source tile's second line while it searches, and after a timeout, stop or error. */
    private static final String RUNNING_MARK = "\u2026", FAILED_MARK = "\u2013";
    /** A source tile's second line while it searches long ("долго отвечает"): an hourglass. */
    private static final String SLOW_MARK = "\u23F3";
    private static final SearchRequest.Field[] SCOPE_FIELDS = {SearchRequest.Field.TITLE, SearchRequest.Field.AUTHOR,
            SearchRequest.Field.READER, SearchRequest.Field.SERIES};
    private static final int[] SCOPE_NAMES = {R.string.search_scope_title, R.string.search_scope_author,
            R.string.search_scope_reader, R.string.search_scope_series};
    /** The orders of the menu; "newest first" only for a person's books (in a search a site's order is relevance). */
    private static final SearchOrder.Sort[] SORTS = {SearchOrder.Sort.NEWEST, SearchOrder.Sort.RELEVANCE,
            SearchOrder.Sort.ARRIVAL, SearchOrder.Sort.TITLE, SearchOrder.Sort.AUTHOR, SearchOrder.Sort.READER,
            SearchOrder.Sort.SHORTEST, SearchOrder.Sort.LONGEST, SearchOrder.Sort.SITE};
    private static final int[] SORT_NAMES = {R.string.person_search_sort_newest, R.string.search_sort_relevance,
            R.string.search_sort_arrival, R.string.search_sort_title, R.string.search_sort_author,
            R.string.search_sort_reader, R.string.search_sort_shortest, R.string.search_sort_longest,
            R.string.search_sort_site};
    /** {@link #personIntent}: the kind of the person ({@link FavoritePeople#AUTHOR} or {@link FavoritePeople#READER}). */
    public static final String EXTRA_PERSON_KIND = "person_kind";
    /** {@link #personIntent}: the person's name as shown. */
    public static final String EXTRA_PERSON_NAME = "person_name";
    /** Show the match score (0-100) on the cards: off by default, it is a ranking key, not a probability (§6.2). */
    private static final String PREF_SHOW_SCORE = "search_show_score";

    @InjectPresenter
    SearchbalePresenter mPresenter;

    private int mModelId;

    /** The screen is being recreated (a turn, or a process that died): set before the presenter is provided. */
    private boolean mRecreated;

    /** The query the screen showed before it was recreated, for a presenter that comes back after its process died. */
    private String mSavedQuery;

    private ActivitySearchableBinding binding;

    private BooksListAddapter mAddapterBooks;

    private GenreListAddapter mAddapterGenre;

    private SearchebleAdapter mAdapterAutors;

    private SearchebleAdapter mAdapterSeries;

    /** The last state drawn, for the menus and the chips' actions. */
    private SearchUi.State mState;

    /** The source chips after "All", by source; the state orders them. */
    private final Map<Provider, TextView> mChips = new HashMap<>();

    private TextView mAllChip;

    private Menu mMenu;

    /** The kind of the person whose books the menu was made for (no scope, "newest first"); -1: a text search. */
    private int mPersonKind = -1;

    /**
     * The books of a person on every site, newest first as far as each site tells: a favourite author's
     * ({@link FavoritePeople#AUTHOR}) or reader's ({@link FavoritePeople#READER}). The text search's saved order,
     * scope, genres and last search stay as they are; a typed query goes back to them.
     */
    public static Intent personIntent(Context c, int kind, String name) {
        return new Intent(c, SearchableActivity.class)
                .putExtra(Consts.ARG_MODEL, Consts.MODEL_BOOKS)
                .putExtra(EXTRA_PERSON_KIND, kind)
                .putExtra(EXTRA_PERSON_NAME, name);
    }

    /** The person of a {@link #personIntent}; null for any other intent. */
    private static String personOf(Intent intent) {
        String name = intent == null ? null : intent.getStringExtra(EXTRA_PERSON_NAME);
        return name == null || name.trim().isEmpty() ? null : name.trim();
    }

    private static int personKindOf(Intent intent) {
        return personOf(intent) == null ? -1 : intent.getIntExtra(EXTRA_PERSON_KIND, FavoritePeople.AUTHOR);
    }

    @ProvidePresenter
    SearchbalePresenter provide() {
        Intent intent = getIntent();
        int model = intent.getIntExtra(Consts.ARG_MODEL, Consts.MODEL_BOOKS);
        // the search button opens the last search; so does a screen whose process died (its presenter is new);
        // a person's books are asked again instead, and the last search is left as it is
        return new SearchbalePresenter(model, getApplicationContext(),
                personOf(intent) == null && (mRecreated || !isSearch(intent)), mSavedQuery);
    }

    private static boolean isSearch(Intent intent) {
        return intent != null && (Intent.ACTION_SEARCH.equals(intent.getAction())
                || MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH.equals(intent.getAction()));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "onCreate: called");
        mRecreated = savedInstanceState != null;
        mSavedQuery = savedInstanceState == null ? null : savedInstanceState.getString(STATE_QUERY);
        super.onCreate(savedInstanceState);
        binding = ActivitySearchableBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        SharedPreferences pref = PreferenceManager
                .getDefaultSharedPreferences(this);

        String themeName = pref.getString("pref_theme", getString(R.string.theme_dark_value));
        if (themeName.equals(getString(R.string.theme_dark_value))) {
            setTheme(R.style.AppTheme_SwipeOnClose);
        } else if (themeName.equals(getString(R.string.theme_light_value))) {
            setTheme(R.style.LightAppTheme_SwipeOnClose);
        } else if (themeName.equals(getString(R.string.theme_black_value))) {
            setTheme(R.style.AppThemeBlack_SwipeOnClose);
        }

        Intent intent = getIntent();
        mModelId = intent.getIntExtra(Consts.ARG_MODEL, Consts.MODEL_BOOKS);
        mPersonKind = mModelId == Consts.MODEL_BOOKS ? personKindOf(intent) : -1;

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        binding.searchView.onActionViewExpanded();
        binding.searchView.setIconified(false);

        SearchManager searchManager = (SearchManager) getSystemService(Context.SEARCH_SERVICE);
        if (searchManager != null) {
            binding.searchView.setSearchableInfo(searchManager.getSearchableInfo(getComponentName()));
        }

        Objects.requireNonNull(getSupportActionBar()).setDisplayHomeAsUpEnabled(true);


        int orientation = this.getResources().getConfiguration().orientation;

        int isTablet = getResources().getInteger(R.integer.isTablet);

        if (isTablet == 0) {
            if (orientation == Configuration.ORIENTATION_PORTRAIT) {
                setLayoutManager();
            } else {
                setLayoutManager(2);
            }
        }
        if (isTablet == 2) {
            if (orientation == Configuration.ORIENTATION_PORTRAIT) {
                setLayoutManager(2);
            } else {
                setLayoutManager(4);
            }
        } else if (isTablet == 1) {
            if (orientation == Configuration.ORIENTATION_PORTRAIT) {
                setLayoutManager(2);
            } else {
                setLayoutManager(3);
            }
        }

        switch (mModelId) {
            case Consts.MODEL_BOOKS:
                // with listening progress and download marks, as in the favourites (W23)
                mAddapterBooks = new BooksListAddapter(true);
                binding.list.setAdapter(mAddapterBooks);
                mAdapterAutors = new SearchebleAdapter();
                mAdapterSeries = new SearchebleAdapter();
                binding.authorList.setLayoutManager(
                        new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
                binding.seriesList.setLayoutManager(
                        new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
                binding.authorList.setAdapter(mAdapterAutors);
                binding.seriesList.setAdapter(mAdapterSeries);
                break;
            case Consts.MODEL_GENRE:
            case Consts.MODEL_AUTOR:
            case Consts.MODEL_ARTIST:
                mAddapterGenre = new GenreListAddapter();
                if (mModelId == Consts.MODEL_AUTOR) {
                    mAddapterGenre.showStars(FavoritePeople.AUTHOR); // a star adds the person to the favourites
                } else if (mModelId == Consts.MODEL_ARTIST) {
                    mAddapterGenre.showStars(FavoritePeople.READER);
                }
                binding.list.setAdapter(mAddapterGenre);
                binding.sourcesScroll.setVisibility(View.GONE);
                break;
        }

        binding.action.setOnClickListener(v -> onAction());

        binding.list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                if (dy == 0) {
                    loadToFill(); // a layout changed the visible range
                    return;
                }
                int lastVisible = ((LinearLayoutManager) Objects.requireNonNull(
                        recyclerView.getLayoutManager())).findLastVisibleItemPosition();
                if (mModelId != Consts.MODEL_GENRE && dy > 0 && lastVisible > getCount() - 3) {
                    mPresenter.loadNext();
                }
            }
        });

        // clicks hand over the item the adapter showed: the presenter's lists change as results arrive (W05)
        if (mAddapterGenre != null) {
            mAddapterGenre.setClickListner((view1, position) -> {
                GenrePOJO item = mAddapterGenre.getItem(position);
                if (item != null) {
                    mPresenter.onGenreItemClick(item);
                }
            });
        }

        if (mAddapterBooks != null) {
            mAddapterBooks.setListener((view12, position) -> {
                BookPOJO book = mAddapterBooks.getItem(position);
                if (book != null) {
                    mPresenter.onBookItemClick(book);
                }
            });

            mAddapterBooks.setLongListener((view13, position) -> {
                BookPOJO book = mAddapterBooks.getItem(position);
                if (book != null) {
                    mPresenter.onBookItemLongClick(view13, book, getLayoutInflater());
                }
            });
        }


        if (mAdapterSeries != null) {
            mAdapterSeries.setListener((view, position) -> {
                SearchebleArrayPOJO item = mAdapterSeries.getItem(position);
                if (item != null) {
                    mPresenter.onSeriesListItemClick(item);
                }
            });
        }


        if (mAdapterAutors != null) {
            mAdapterAutors.setListener((view, position) -> {
                SearchebleArrayPOJO item = mAdapterAutors.getItem(position);
                if (item != null) {
                    mPresenter.onAutorsListItemClick(item);
                }
            });
        }

        // холодный ACTION_SEARCH (голосовой поиск, экземпляр под BookActivity) приходит в onCreate;
        // при пересоздании (поворот) запрос уже хранится в презентере. После смерти процесса презентер новый:
        // он показывает сохранённый поиск, а если его нет — запрос возвращается в поле без автозапуска (W02)
        // a person's books after the process died are simply asked again: the intent still names the person
        if (savedInstanceState == null || mPresenter.getQuery() == null && mPersonKind >= 0) {
            handleIntent(intent);
        } else if (mPresenter.getQuery() == null) {
            String query = savedInstanceState.getString(STATE_QUERY);
            if (query != null) {
                binding.searchView.setQuery(query, false);
            }
        }
    }

    /** Back on the authors / readers list: a star may have changed elsewhere (a book, the favourites). */
    @Override
    protected void onResume() {
        super.onResume();
        if (mAddapterGenre != null && (mModelId == Consts.MODEL_AUTOR || mModelId == Consts.MODEL_ARTIST)) {
            mAddapterGenre.refreshStars();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        String query = mPresenter.getQuery();
        if (query == null && binding != null) {
            query = binding.searchView.getQuery().toString();
        }
        outState.putString(STATE_QUERY, query);
    }

    private int getCount() {
        if (mAddapterBooks != null) {
            return mAddapterBooks.getItemCount();
        } else if (mAddapterGenre != null) {
            return mAddapterGenre.getItemCount();
        } else {
            return 0;
        }
    }

    /** Back from a book: its listening progress and download marks may have changed. */
    @Override
    protected void onRestart() {
        super.onRestart();
        if (mAddapterBooks != null) {
            mAddapterBooks.reloadStatuses();
        }
    }

    @Override
    protected void onDestroy() {
        if (mAddapterBooks != null) {
            mAddapterBooks.close();
        }
        mAddapterBooks = null;
        mAddapterGenre = null;
        mAdapterAutors = null;
        mAdapterSeries = null;
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (mModelId == Consts.MODEL_BOOKS) {
            getMenuInflater().inflate(R.menu.search_options_menu, menu);
            SubMenu sorts = menu.findItem(R.id.search_sort).getSubMenu();
            if (sorts != null) {
                for (int i = 0; i < SORTS.length; i++) {
                    if (SORTS[i] != SearchOrder.Sort.NEWEST || mPersonKind >= 0) {
                        sorts.add(R.id.search_sort, Menu.FIRST + i, i, SORT_NAMES[i]);
                    }
                }
                sorts.setGroupCheckable(R.id.search_sort, true, true);
            }
            mMenu = menu;
            updateScoreItem();
            updateMenu();
        }
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
        } else if (item.getGroupId() == R.id.search_sort) {
            mPresenter.setSort(SORTS[id - Menu.FIRST]);
            return true;
        } else if (id == R.id.search_scope) {
            showScopeDialog();
            return true;
        } else if (id == R.id.search_genres) {
            showGenresDialog();
            return true;
        } else if (id == R.id.search_group) {
            mPresenter.setGrouped(!item.isChecked());
            return true;
        } else if (id == R.id.search_show_score) {
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                    .putBoolean(PREF_SHOW_SCORE, !item.isChecked()).apply();
            item.setChecked(!item.isChecked());
            if (mState != null) {
                showRows(mState);
            }
            return true;
        } else if (id == R.id.search_refresh) {
            mPresenter.refresh();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void updateMenu() {
        if (mMenu == null || mState == null) {
            return;
        }
        MenuItem scope = mMenu.findItem(R.id.search_scope);
        if (scope != null) {
            scope.setIcon(mState.input.request.isAnywhere() ? R.drawable.ic_filter : R.drawable.ic_filter_active);
            scope.setVisible(mPersonKind < 0); // a person's books are looked for everywhere
        }
        MenuItem sort = mMenu.findItem(R.id.search_sort);
        for (int i = 0; i < SORTS.length && sort != null; i++) {
            if (SORTS[i] == mState.input.sort) {
                // the order is no longer an icon: the overflow says which one is on
                String name = getString(SORT_NAMES[i]);
                sort.setTitle(getString(R.string.search_sort_current,
                        name.substring(0, 1).toLowerCase(java.util.Locale.ROOT) + name.substring(1)));
                MenuItem chosen = sort.hasSubMenu() ? sort.getSubMenu().findItem(Menu.FIRST + i) : null;
                if (chosen != null) {
                    chosen.setChecked(true);
                }
            }
        }
        MenuItem genres = mMenu.findItem(R.id.search_genres);
        if (genres != null) {
            genres.setTitle(getString(R.string.search_genres_current, mState.input.genres.isEmpty()
                    ? getString(R.string.search_genres_every) : genreNames(mState.input.genres)));
        }
        MenuItem group = mMenu.findItem(R.id.search_group);
        if (group != null) {
            group.setChecked(mState.input.grouped);
        }
        MenuItem score = mMenu.findItem(R.id.search_show_score);
        if (score != null) {
            score.setChecked(showScore());
        }
    }

    private void updateScoreItem() {
        MenuItem score = mMenu == null ? null : mMenu.findItem(R.id.search_show_score);
        if (score != null) {
            score.setChecked(showScore());
        }
    }

    /** Where to look: any of title, author, reader, series; none of them is "anywhere". */
    private void showScopeDialog() {
        Set<SearchRequest.Field> scope = mState == null ? EnumSet.noneOf(SearchRequest.Field.class)
                : mState.input.request.scope;
        final boolean[] checked = new boolean[SCOPE_FIELDS.length];
        String[] names = new String[SCOPE_FIELDS.length];
        for (int i = 0; i < SCOPE_FIELDS.length; i++) {
            checked[i] = scope.contains(SCOPE_FIELDS[i]);
            names[i] = getString(SCOPE_NAMES[i]);
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.search_scope)
                .setMultiChoiceItems(names, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(R.string.search_scope_done, (d, w) -> {
                    Set<SearchRequest.Field> chosen = EnumSet.noneOf(SearchRequest.Field.class);
                    for (int i = 0; i < SCOPE_FIELDS.length; i++) {
                        if (checked[i]) {
                            chosen.add(SCOPE_FIELDS[i]);
                        }
                    }
                    mPresenter.setScope(chosen);
                })
                .setNeutralButton(R.string.search_scope_anywhere,
                        (d, w) -> mPresenter.setScope(EnumSet.noneOf(SearchRequest.Field.class)))
                .show();
    }

    /** Which genres to keep among the found cards; none chosen is every genre. */
    private void showGenresDialog() {
        Genre[] all = Genre.values();
        Set<Genre> current = mState == null ? EnumSet.noneOf(Genre.class) : mState.input.genres;
        final boolean[] checked = new boolean[all.length];
        String[] names = new String[all.length];
        for (int i = 0; i < all.length; i++) {
            checked[i] = current.contains(all[i]);
            names[i] = getString(all[i].title);
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.search_genres)
                .setMultiChoiceItems(names, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(R.string.search_scope_done, (d, w) -> {
                    Set<Genre> chosen = EnumSet.noneOf(Genre.class);
                    for (int i = 0; i < all.length; i++) {
                        if (checked[i]) {
                            chosen.add(all[i]);
                        }
                    }
                    mPresenter.setGenres(chosen);
                })
                .setNeutralButton(R.string.search_genres_all,
                        (d, w) -> mPresenter.setGenres(EnumSet.noneOf(Genre.class)))
                .show();
    }

    /** One genre by name, more as "Фантастика и фэнтези +2". */
    private String genreNames(Set<Genre> genres) {
        String first = getString(genres.iterator().next().title);
        return genres.size() == 1 ? first : first + " +" + (genres.size() - 1);
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        if (mModelId != Consts.MODEL_BOOKS && personOf(intent) != null) {
            // the authors / readers screen cannot show books: a books screen takes the person over
            finish();
            startActivity(intent);
            return;
        }
        setIntent(intent);
        handleIntent(intent);
        if (binding != null) {
            binding.list.requestFocus();
        }
    }

    private void handleIntent(Intent intent) {
        String person = personOf(intent);
        if (binding != null && person != null && mModelId == Consts.MODEL_BOOKS) {
            binding.searchView.setQuery(person, false);
            binding.searchView.clearFocus(); // the books come at once: the keyboard would only cover them
            setPersonKind(personKindOf(intent));
            mPresenter.searchPerson(personKindOf(intent), person);
        } else if (binding != null && isSearch(intent)) {
            String query = intent.getStringExtra(SearchManager.QUERY);
            String link = LoadBook.bookUrlIn(query);
            if (link != null) {
                // a pasted book link opens the book; it is not a query to remember
                startActivity(new Intent(this, LoadBook.class).putExtra("url", link));
                return;
            }
            if (query != null) {
                SearchRecentSuggestions suggestions = new SearchRecentSuggestions(this,
                        MySuggestionProvider.AUTHORITY, MySuggestionProvider.MODE);
                suggestions.saveRecentQuery(query, null);
                binding.searchView.setQuery(query, false);
                // a query from outside (voice, another screen) is already submitted: the keyboard would only cover
                // the results; clearFocus also cancels the pending show of the expanded search view
                binding.searchView.clearFocus();
                mPresenter.setQuery(query);
                mPresenter.loadBoks();
            }
        }
    }

    @Override
    public Resources.Theme getTheme() {
        Resources.Theme theme = super.getTheme();

        SharedPreferences pref = PreferenceManager
                .getDefaultSharedPreferences(this);

        String themeName = pref.getString("pref_theme", getString(R.string.theme_dark_value));
        if (themeName.equals(getString(R.string.theme_dark_value))) {
            theme.applyStyle(R.style.AppTheme_SwipeOnClose, true);
        } else if (themeName.equals(getString(R.string.theme_light_value))) {
            theme.applyStyle(R.style.LightAppTheme_SwipeOnClose, true);
        } else if (themeName.equals(getString(R.string.theme_black_value))) {
            theme.applyStyle(R.style.AppThemeBlack_SwipeOnClose, true);
        }

        return theme;
    }

    @Override
    public void setLayoutManager(int count) {
        binding.list.setLayoutManager(new GridLayoutManager(this, count));
        setItemDecoration(count);
    }

    @Override
    public void setLayoutManager() {
        binding.list.setLayoutManager(new LinearLayoutManager(this));
        setItemDecoration(1);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        View currentFocus = getCurrentFocus();
        if (currentFocus != null) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (isUpButton(currentFocus)) {
                    Toolbar toolbar = findViewById(R.id.toolbar);
                    toolbar.requestFocus();
                }
                if (currentFocus.getId() == R.id.toolbar) {
                    View chip = selectedChip();
                    if (chip != null) {
                        chip.requestFocus();
                    }
                }
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                if (currentFocus.getParent() == binding.sources || currentFocus.getId() == R.id.toolbar) {
                    View menuItem = findViewById(R.id.searchView);
                    if (menuItem != null) {
                        menuItem.requestFocus();
                    }
                }
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    /** The toolbar's navigation ("up") button: the toolbar's own image button, whatever the locale (W34). */
    private boolean isUpButton(View view) {
        return view instanceof ImageButton && view.getParent() == binding.toolbar;
    }

    private View selectedChip() {
        if (mState != null && mState.tab != null) {
            return mChips.get(mState.tab.source);
        }
        return mAllChip;
    }

    @Override
    public void showState(SearchUi.State state) {
        if (mAddapterBooks == null) {
            return;
        }
        boolean first = mState == null;
        boolean otherView = !first && otherView(mState.input, state.input);
        mState = state;
        if (state.input.searched || state.input.personKind >= 0) {
            setPersonKind(state.input.personKind); // a typed query left the person's books, or a new person came
        }
        showChips(state);
        showProgress(state);
        showSummary(state);
        showRows(state);
        showSeriesAndAutors(state.plates);
        updateMenu();
        if (otherView) {
            binding.list.scrollToPosition(0); // another tab, scope, order or search: its top, not the old offset
        }
        if (first && !state.rows.isEmpty()) {
            binding.searchView.clearFocus(); // restored results: the keyboard would cover them (W24)
        }
    }

    /** The menu follows the mode: a person's books have "newest first" and no scope. */
    private void setPersonKind(int kind) {
        if (kind != mPersonKind) {
            mPersonKind = kind;
            invalidateOptionsMenu();
        }
    }

    @Override
    public void showQuery(String query) {
        binding.searchView.setQuery(query, false);
    }

    /**
     * The recordings of one work, numbered, each with the sites that carry it; a site opens its card. A full line
     * parts the recordings, a short one the sites of one recording.
     */
    @Override
    public void showNarrations(String title, List<Narration> narrations) {
        LayoutInflater inflater = getLayoutInflater();
        Resources res = RussianResources.of(this);
        Set<Provider> catalogs = new HashSet<>();
        for (Narration n : narrations) {
            for (Offer o : n.offers) {
                if (o.source != null) {
                    catalogs.add(o.source.original()); // mirrors of one catalog count once, as on the row
                }
            }
        }
        View head = inflater.inflate(R.layout.search_narrations_title, null);
        ((TextView) head.findViewById(R.id.narrationsTitle)).setText(
                title == null ? getString(R.string.search_narrations_title) : title);
        ((TextView) head.findViewById(R.id.narrationsSubtitle)).setText(
                res.getQuantityString(R.plurals.search_narrations, narrations.size(), narrations.size()) + " "
                        + res.getQuantityString(R.plurals.search_on_sites, catalogs.size(), catalogs.size()));

        int href = attrColor(R.attr.hrefColor);
        int line = ColorUtils.setAlphaComponent(attrColor(R.attr.subTextColor), 64);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, dp(8));
        AlertDialog[] dialog = new AlertDialog[1];
        for (int i = 0; i < narrations.size(); i++) {
            Narration n = narrations.get(i);
            if (i > 0) {
                list.addView(divider(line, dp(24)));
            }
            View header = inflater.inflate(R.layout.search_narration_header, list, false);
            TextView number = header.findViewById(R.id.narrationNumber);
            number.setText(String.valueOf(i + 1));
            number.setTextColor(href);
            GradientDrawable badge = new GradientDrawable();
            badge.setShape(GradientDrawable.OVAL);
            badge.setColor(ColorUtils.setAlphaComponent(href, FILL_ALPHA));
            number.setBackground(badge);
            String reader = n.best.book.getArtist();
            reader = reader == null || reader.trim().isEmpty() ? getString(R.string.search_reader_unknown)
                    : reader.trim();
            ((TextView) header.findViewById(R.id.narrationReader)).setText(reader);
            List<String> facts = new ArrayList<>();
            String time = timeOf(n.offers);
            if (!time.isEmpty()) {
                facts.add(time);
            }
            if (!n.edition.isEmpty()) {
                facts.add(n.edition);
            }
            if (n.catalogs() > 1) {
                facts.add(res.getQuantityString(R.plurals.search_sites, n.catalogs(), n.catalogs()));
            }
            TextView factsLine = header.findViewById(R.id.narrationFacts);
            factsLine.setText(join(facts, " · "));
            factsLine.setVisibility(facts.isEmpty() ? View.GONE : View.VISIBLE);
            header.setContentDescription(getString(R.string.search_narration_spoken, i + 1, reader)
                    + (facts.isEmpty() ? "" : ", " + join(facts, ", ")));
            list.addView(header);
            for (int j = 0; j < n.offers.size(); j++) {
                if (j > 0) {
                    list.addView(divider(line, dp(64))); // under the site names, past the icons
                }
                Offer o = n.offers.get(j);
                View row = siteRow(inflater, list, o, href);
                row.setOnClickListener(v -> {
                    dialog[0].dismiss();
                    mPresenter.openOffer(o.url());
                });
                list.addView(row);
            }
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        dialog[0] = new MaterialAlertDialogBuilder(this)
                .setCustomTitle(head)
                .setView(scroll)
                .setNegativeButton(R.string.close, null)
                .show();
    }

    /** A site of a recording: its icon (its letter until the icon is drawn), name and the card's own length. */
    private View siteRow(LayoutInflater inflater, LinearLayout parent, Offer o, int href) {
        View row = inflater.inflate(R.layout.search_narration_site, parent, false);
        String name = o.source != null ? o.source.title : Url.hostOf(o.url());
        TextView letter = row.findViewById(R.id.siteLetter);
        letter.setText(o.source != null ? SourceTexts.letter(o.source) : "");
        letter.setBackground(SourceTexts.rounded(href, dp(7)));
        ImageView icon = row.findViewById(R.id.siteIcon);
        if (o.source != null) {
            Glide.with(icon).load(SourceInfo.uri(o.source, SourceInfo.ICON)).into(icon);
        }
        ((TextView) row.findViewById(R.id.siteName)).setText(name);
        String time = Consts.formatTimeDisplay(o.book.getTime());
        TextView length = row.findViewById(R.id.siteTime);
        length.setText(time);
        length.setVisibility(time.isEmpty() ? View.GONE : View.VISIBLE);
        row.setContentDescription(time.isEmpty() ? name : name + ", " + time);
        return row;
    }

    /** The first known length of {@code offers} (the best card first); empty when none says. */
    private static String timeOf(List<Offer> offers) {
        for (Offer o : offers) {
            String time = Consts.formatTimeDisplay(o.book.getTime());
            if (!time.isEmpty()) {
                return time;
            }
        }
        return "";
    }

    /** A 1dp line from {@code start} to 24dp before the end. */
    private View divider(int color, int start) {
        View line = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                Math.max(1, dp(1)));
        lp.setMarginStart(start);
        lp.setMarginEnd(dp(24));
        line.setLayoutParams(lp);
        line.setBackgroundColor(color);
        return line;
    }

    private int dp(int dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                getResources().getDisplayMetrics()));
    }

    /**
     * "All", then one chip per source in the state's order: the most found first, then the searching ones, those
     * that found nothing, and last the ones that did not answer. The chips are moved, never made again, so a
     * focused or selected one stays in sight.
     */
    private void showChips(SearchUi.State state) {
        if (mAllChip == null) {
            mAllChip = newChip();
            mAllChip.setOnClickListener(v -> mPresenter.setFilter(-1));
        }
        List<View> order = new ArrayList<>();
        order.add(mAllChip);
        Set<Provider> present = new HashSet<>();
        for (SearchUi.Chip c : state.chips) {
            TextView chip = mChips.get(c.source);
            if (chip == null) {
                Provider p = c.source;
                chip = newChip();
                chip.setOnClickListener(v -> mPresenter.setFilter(p.sourceId));
                chip.setOnLongClickListener(v -> {
                    mPresenter.retrySource(p);
                    return true;
                });
                mChips.put(p, chip);
            }
            bindChip(chip, c);
            order.add(chip);
            present.add(c.source);
        }
        mChips.keySet().retainAll(present);
        bindAllChip(state);
        boolean same = binding.sources.getChildCount() == order.size();
        for (int i = 0; same && i < order.size(); i++) {
            same = binding.sources.getChildAt(i) == order.get(i);
        }
        if (same) {
            return;
        }
        View focused = binding.sources.getFocusedChild();
        binding.sources.removeAllViews();
        for (View chip : order) {
            binding.sources.addView(chip);
        }
        if (focused != null) {
            focused.requestFocus(); // a TV remote keeps its place
        }
        View selected = state.tab == null ? null : mChips.get(state.tab.source);
        if (selected != null) {
            // a moved tab stays in sight
            binding.sourcesScroll.post(() -> {
                int x = binding.sourcesScroll.getScrollX();
                if (selected.getLeft() < x || selected.getRight() > x + binding.sourcesScroll.getWidth()) {
                    binding.sourcesScroll.smoothScrollTo(Math.max(0, selected.getLeft() - dp(16)), 0);
                }
            });
        }
    }

    private TextView newChip() {
        return (TextView) getLayoutInflater().inflate(R.layout.search_source_chip, binding.sources, false);
    }

    private void bindChip(TextView chip, SearchUi.Chip c) {
        String name = c.source.title;
        String count;
        String description;
        int color;
        switch (c.kind) {
            case FOUND:
                count = c.count + (c.hasMore ? "+" : "");
                description = c.hasMore ? getString(R.string.search_chip_found_more, name, c.count)
                        : getString(R.string.search_chip_found, name, c.count);
                color = ContextCompat.getColor(this, R.color.source_up);
                break;
            case EMPTY:
                count = "0";
                description = getString(R.string.search_chip_empty, name);
                color = Color.TRANSPARENT;
                break;
            case RUNNING:
                count = c.slow ? SLOW_MARK : RUNNING_MARK;
                description = getString(c.slow ? R.string.search_chip_slow : R.string.search_chip_running, name);
                color = attrColor(R.attr.subTextColor);
                break;
            case TIMEOUT:
            case RATE_LIMITED:
            case STOPPED:
                // passing troubles, amber: the same request goes through later
                count = FAILED_MARK;
                description = getString(R.string.search_chip_failed, name, statusName(c.kind));
                color = ContextCompat.getColor(this, R.color.source_partial);
                break;
            default:
                count = FAILED_MARK;
                description = getString(R.string.search_chip_failed, name, statusName(c.kind));
                color = ContextCompat.getColor(this, R.color.source_down);
                break;
        }
        paint(chip, name, count, description, color, c.selected, c.kind == SearchUi.ChipKind.EMPTY);
    }

    private void bindAllChip(SearchUi.State state) {
        String name = getString(R.string.search_all_sources);
        String count = state.allCount > 0 ? String.valueOf(state.allCount) : RUNNING_MARK;
        int color;
        switch (state.pass) {
            case RUNNING:
                color = attrColor(R.attr.subTextColor);
                break;
            case DONE:
                color = ContextCompat.getColor(this, R.color.source_up);
                break;
            case PARTIAL:
                color = ContextCompat.getColor(this, R.color.source_partial);
                break;
            case FAILED:
                color = ContextCompat.getColor(this, R.color.source_down);
                break;
            default:
                color = Color.TRANSPARENT;
                break;
        }
        String description = name + (state.allCount > 0 ? " " + state.allCount : "");
        paint(mAllChip, name, count, description, color, state.tab == null, false);
    }

    /**
     * A flat square tile, name over a smaller count. State: the fill colour (running grey, found green, timeout
     * amber, error red, empty none and dimmed); selection: a 2dp underline in the link colour and bold text.
     */
    private void paint(TextView chip, String name, String count, String description, int color, boolean selected,
            boolean dim) {
        SpannableString text = new SpannableString(name + "\n" + count);
        text.setSpan(new RelativeSizeSpan(0.8f), name.length() + 1, text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        chip.setText(text);
        chip.setContentDescription(description);
        chip.setSelected(selected);
        Drawable fill = new ColorDrawable(Color.alpha(color) == 0 ? Color.TRANSPARENT
                : ColorUtils.setAlphaComponent(color, FILL_ALPHA));
        if (selected) {
            LayerDrawable layers = new LayerDrawable(
                    new Drawable[]{fill, new ColorDrawable(attrColor(R.attr.hrefColor))});
            layers.setLayerGravity(1, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL);
            layers.setLayerHeight(1, Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2,
                    getResources().getDisplayMetrics())));
            fill = layers;
        }
        chip.setBackground(fill);
        chip.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        chip.setAlpha(dim ? 0.6f : 1f);
    }

    private String statusName(SearchUi.ChipKind kind) {
        switch (kind) {
            case TIMEOUT:
                return getString(R.string.sources_status_timeout);
            case BLOCKED:
                return getString(R.string.sources_status_blocked);
            case RATE_LIMITED:
                return getString(R.string.sources_status_rate_limited);
            case STOPPED:
                return getString(R.string.sources_status_stopped);
            default:
                return getString(R.string.sources_status_error);
        }
    }

    private int attrColor(int attr) {
        TypedValue value = new TypedValue();
        getTheme().resolveAttribute(attr, value, true);
        return value.resourceId != 0 ? ContextCompat.getColor(this, value.resourceId) : value.data;
    }

    private void showProgress(SearchUi.State state) {
        if (state.pass == SearchUi.Pass.RUNNING) {
            binding.progress.setMax(Math.max(1, state.planned));
            binding.progress.setProgressCompat(state.answered, true);
            binding.progress.setVisibility(View.VISIBLE);
        } else {
            binding.progress.setVisibility(View.INVISIBLE);
        }
    }

    /** One line: from when, the scope, how far, what failed; and the one action that helps now. */
    private void showSummary(SearchUi.State state) {
        List<String> parts = new ArrayList<>();
        if (state.input.personKind >= 0) {
            // the field shows the name: this says whose books these are
            parts.add(getString(state.input.personKind == FavoritePeople.READER ? R.string.person_search_reader
                    : R.string.person_search_author));
        }
        if (state.input.restoredAt > 0) {
            parts.add(getString(R.string.search_summary_restored, when(state.input.restoredAt)));
        }
        if (!state.input.request.isAnywhere()) {
            parts.add(getString(R.string.search_summary_scope, scopeNames(state.input.request.scope)));
        }
        if (!state.input.genres.isEmpty()) {
            parts.add(getString(R.string.search_summary_genres, genreNames(state.input.genres)));
        }
        int shown = state.rows.size();
        switch (state.pass) {
            case RUNNING:
                parts.add(getString(R.string.search_summary_running, state.answered, state.planned, shown));
                break;
            case DONE:
            case PARTIAL:
                parts.add(RussianResources.of(this).getQuantityString(R.plurals.search_found_books, shown, shown) + " "
                        + RussianResources.of(this).getQuantityString(R.plurals.search_on_sites, state.sites, state.sites));
                break;
            case FAILED:
                parts.add(getString(R.string.search_summary_none_answered));
                break;
            default:
                break;
        }
        if (!state.waiting.isEmpty() && state.pass != SearchUi.Pass.RUNNING) {
            // the pass no longer waits for them: their cards join when they come
            parts.add(getString(R.string.search_summary_waiting, names(state.waiting)));
        }
        if (!state.failed.isEmpty() && state.pass != SearchUi.Pass.FAILED) {
            parts.add(getString(R.string.search_summary_failed, names(state.failed)));
        }
        if (state.hiddenByScope > 0) {
            parts.add(getString(R.string.search_summary_hidden, state.hiddenByScope));
        }
        if (state.unknown > 0) {
            parts.add(getString(R.string.search_summary_unknown, state.unknown));
        }
        binding.summary.setText(join(parts, " · "));

        String action = null;
        if (state.pass == SearchUi.Pass.RUNNING) {
            action = getString(R.string.search_action_stop);
        } else if (state.empty == SearchUi.Empty.TAB_FAILED && state.tab != null) {
            action = getString(R.string.search_action_retry_one, state.tab.source.title);
        } else if (state.empty == SearchUi.Empty.SCOPE_HID_ALL) {
            action = getString(R.string.search_action_everywhere);
        } else if (!state.failed.isEmpty()) {
            action = getString(R.string.search_action_retry, state.failed.size());
        } else if (state.input.restoredAt > 0) {
            action = getString(R.string.search_action_refresh);
        } else if (state.hasMore && state.tab == null && state.pass == SearchUi.Pass.DONE) {
            action = getString(R.string.search_action_more);
        }
        binding.action.setText(action);
        binding.action.setVisibility(action == null ? View.GONE : View.VISIBLE);
        binding.summaryRow.setVisibility(parts.isEmpty() && action == null ? View.GONE : View.VISIBLE);
    }

    private void onAction() {
        SearchUi.State state = mState;
        if (state == null) {
            mPresenter.retryFailed(); // the authors / readers screen: its one action
            return;
        }
        if (state.pass == SearchUi.Pass.RUNNING) {
            mPresenter.stop();
        } else if (state.empty == SearchUi.Empty.TAB_FAILED && state.tab != null) {
            mPresenter.retrySource(state.tab.source);
        } else if (state.empty == SearchUi.Empty.SCOPE_HID_ALL) {
            mPresenter.setGenres(EnumSet.noneOf(Genre.class));
            mPresenter.setScope(EnumSet.noneOf(SearchRequest.Field.class));
        } else if (!state.failed.isEmpty()) {
            mPresenter.retryFailed();
        } else if (state.input.restoredAt > 0) {
            mPresenter.loadBoks(true);
        } else {
            mPresenter.loadNext();
        }
    }

    private boolean showScore() {
        return PreferenceManager.getDefaultSharedPreferences(this).getBoolean(PREF_SHOW_SCORE, false);
    }

    private void showRows(SearchUi.State state) {
        ArrayList<BookPOJO> books = new ArrayList<>(state.rows.size());
        Map<String, String> labels = new HashMap<>();
        Map<String, String> notes = new HashMap<>();
        Map<String, List<String>> aliases = new HashMap<>();
        boolean score = showScore();
        ParentalControl.Store parental = ParentalControl.store(this);
        for (SearchUi.Row row : state.rows) {
            books.add(display(row, labels, notes, aliases, score, parental));
        }
        // a list the listener has not scrolled stays at its top: a better card that arrives goes above the first
        // one shown, where the list would otherwise keep its place and hide it
        boolean atTop = !binding.list.canScrollVertically(-1);
        mAddapterBooks.submit(books, labels, notes, aliases);
        if (atTop) {
            binding.list.scrollToPosition(0);
        }
        String empty = emptyText(state);
        binding.booksNotFound.setText(empty);
        binding.booksNotFound.setVisibility(empty == null ? View.GONE : View.VISIBLE);
    }

    /**
     * The card a row shows. A group of several cards shows its representative with the group's facts: "5 сайтов",
     * its readers, its recordings and why it matched; its URL is the group's id, by which a tap finds it again. The
     * facts go to the adapter by that id, never into the card's own fields: the genre stays a real one, so that the
     * parental control still masks the cover, and a group masks when any of its books would.
     */
    private BookPOJO display(SearchUi.Row row, Map<String, String> labels, Map<String, String> noteLines,
            Map<String, List<String>> aliases, boolean score, ParentalControl.Store parental) {
        Group g = row.group;
        BookPOJO b = row.offer.book;
        List<String> notes = new ArrayList<>();
        SearchMatcher.Match m = row.match;
        // a person's books: every card is the person's, how its fields match the name says nothing
        boolean person = mState != null && mState.input.personKind >= 0;
        if (!person && !m.fields.isEmpty() && !m.fields.contains(SearchRequest.Field.TITLE)) {
            if (m.fields.contains(SearchRequest.Field.READER)) {
                notes.add(getString(R.string.search_matched_reader));
            } else if (m.fields.contains(SearchRequest.Field.SERIES)) {
                notes.add(getString(R.string.search_matched_series));
            }
        }
        if (row.offer.listing) {
            notes.add(getString(R.string.search_collection)); // a list of books: it opens as the site's listing
        }
        if (row.unknown) {
            notes.add(getString(R.string.search_no_scope_data));
        } else if (!person && m.tier == SearchMatcher.Tier.E && !m.fields.isEmpty()) {
            notes.add(getString(R.string.search_similar));
        }
        if (score && !m.fields.isEmpty()) {
            notes.add(getString(R.string.search_score, g != null ? g.score : m.score));
        }
        boolean several = g != null && g.offers.size() > 1;
        if (several && g.narrations.size() > 1) {
            notes.add(0, RussianResources.of(this).getQuantityString(R.plurals.search_narrations, g.narrations.size(),
                    g.narrations.size()));
        }
        if (!notes.isEmpty()) {
            noteLines.put(row.key, join(notes, " · "));
        }
        if (!several && row.key.equals(b.getUrl())) {
            return b;
        }
        BookPOJO d = copy(b);
        d.setUrl(row.key);
        if (several) {
            List<String> urls = new ArrayList<>();
            for (Offer o : g.offers) {
                urls.add(o.url());
                if (!ParentalControl.isAllowed(parental, o.book)) {
                    d.setGenre(o.book.getGenre()); // the group masks as its most restricted book
                }
            }
            aliases.put(row.key, urls);
            d.setArtist(readers(g, mState == null ? SearchOrder.Sort.RELEVANCE : mState.input.sort));
            int sites = g.catalogs();
            if (sites > 1) {
                labels.put(row.key, RussianResources.of(this).getQuantityString(R.plurals.search_sites, sites, sites));
            } else if (row.offer.source != null) {
                labels.put(row.key, row.offer.source.title);
            }
        }
        return d;
    }

    /** The listener changed what the list shows (not only what arrived): a new search, tab, scope, order or grouping. */
    private static boolean otherView(SearchUi.Input before, SearchUi.Input after) {
        return before.generation != after.generation || before.tab != after.tab || before.sort != after.sort
                || before.grouped != after.grouped || !before.request.equals(after.request)
                || !before.genres.equals(after.genres);
    }

    /**
     * The readers of a group's recordings: two names, then "+N"; ordered by reader, the one the group is filed under
     * comes first (sites spell a name "Имя Фамилия" or "Фамилия Имя").
     */
    private static String readers(Group g, SearchOrder.Sort sort) {
        List<String> candidates = new ArrayList<>();
        String first = sort == SearchOrder.Sort.READER ? SearchOrder.firstReader(g) : null;
        if (first != null) {
            candidates.add(first);
        }
        for (Narration n : g.narrations) {
            candidates.add(n.best.book.getArtist());
        }
        // one name once, however it is spelled: "Симаков Алексей" and "Алексей Симаков" are one reader
        Set<String> people = new java.util.HashSet<>();
        List<String> list = new ArrayList<>();
        for (String r : candidates) {
            if (r != null && !r.trim().isEmpty() && people.add(TextNorm.person(r).words.toString())) {
                list.add(r.trim());
            }
        }
        if (list.size() <= 2) {
            return join(list, ", ");
        }
        return join(list.subList(0, 2), ", ") + " +" + (list.size() - 2);
    }

    private String emptyText(SearchUi.State state) {
        String query = state.input.request.text;
        String tab = state.tab == null ? "" : state.tab.source.title;
        switch (state.empty) {
            case START:
                return getString(R.string.search_empty_start);
            case NO_SOURCES:
                return getString(R.string.search_empty_no_sources);
            case NOTHING:
                return getString(R.string.search_empty_nothing, query);
            case NOTHING_SOME_FAILED:
                return getString(R.string.search_empty_nothing_failed, names(state.failed));
            case WAITING:
                return getString(R.string.search_empty_waiting, names(state.waiting));
            case ALL_FAILED:
                return getString(R.string.search_empty_all_failed);
            case SCOPE_HID_ALL:
                return getString(R.string.search_empty_scope);
            case TAB_SEARCHING:
                return getString(R.string.search_empty_tab_searching, tab);
            case TAB_NOTHING:
                return getString(R.string.search_empty_tab_nothing, tab);
            case TAB_FAILED:
                return getString(R.string.search_empty_tab_failed, tab, statusName(state.tab.kind));
            default:
                return null; // NONE, or SEARCHING: the chips and the summary say it
        }
    }

    private String scopeNames(Set<SearchRequest.Field> scope) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < SCOPE_FIELDS.length; i++) {
            if (scope.contains(SCOPE_FIELDS[i])) {
                names.add(getString(SCOPE_NAMES[i]));
            }
        }
        return join(names, ", ");
    }

    /** Up to two site names, else "Библус, Голосом +2". */
    private static String names(List<Provider> sources) {
        List<String> names = new ArrayList<>();
        for (Provider p : sources) {
            names.add(p.title);
        }
        if (names.size() <= 2) {
            return join(names, ", ");
        }
        return join(names.subList(0, 2), ", ") + " +" + (names.size() - 2);
    }

    private String when(long at) {
        String time = RussianDates.time(at);
        return DateUtils.isToday(at) ? time : RussianDates.date(at) + " " + time;
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder s = new StringBuilder();
        for (String p : parts) {
            s.append(s.length() == 0 ? "" : sep).append(p);
        }
        return s.toString();
    }

    private static BookPOJO copy(BookPOJO b) {
        BookPOJO d = new BookPOJO();
        d.setName(b.getName());
        d.setUrl(b.getUrl());
        d.setPhoto(b.getPhoto());
        d.setAutor(b.getAutor());
        d.setUrlAutor(b.getUrlAutor());
        d.setArtist(b.getArtist());
        d.setUrlArtist(b.getUrlArtist());
        d.setTime(b.getTime());
        d.setSeries(b.getSeries());
        d.setUrlSeries(b.getUrlSeries());
        d.setGenre(b.getGenre());
        d.setUrlGenre(b.getUrlGenre());
        d.setReting(b.getReting());
        d.setComents(String.valueOf(b.getComents()));
        d.setDesc(b.getDesc());
        return d;
    }

    @Override
    public void showDataGenres(ArrayList<GenrePOJO> genres) {
        try {
            if (mAddapterGenre != null) {
                if (genres != null && !genres.isEmpty()) {
                    mAddapterGenre.setItem(genres);
                } else {
                    // Если пришел пустой список, очищаем адаптер
                    mAddapterGenre.clearItem();
                }
            }
        } catch (Exception e) {
            Log.e("showData", "Error displaying genre data", e);
            showToast(R.string.error_display_data);
        }
    }

    @Override
    public void clearData() {
        if (mAddapterBooks != null) mAddapterBooks.clearItem();
        if (mAddapterGenre != null) mAddapterGenre.clearItem();
    }

    @Override
    public void showToast(int message) {
        Toast.makeText(this, getResources().getText(message),
                Toast.LENGTH_LONG).show();
    }

    @Override
    public void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    /** The authors / readers screen: "not found" in the empty area. */
    @Override
    public void setNotFoundVisibile(boolean b) {
        if (mAddapterGenre == null) {
            return;
        }
        binding.booksNotFound.setText(R.string.no_books);
        binding.booksNotFound.setVisibility(b ? View.VISIBLE : View.GONE);
    }

    /** The authors / readers screen: an indeterminate line under the toolbar. */
    @Override
    public void showProgres(boolean b) {
        if (mAddapterGenre == null) {
            return;
        }
        if (b) {
            binding.progress.setVisibility(View.INVISIBLE);
            binding.progress.setIndeterminate(true);
            binding.progress.setVisibility(View.VISIBLE);
        } else {
            binding.progress.setVisibility(View.INVISIBLE);
        }
    }

    /** Results that end on screen (a tablet's grid holds a whole page) leave nothing to scroll for the next page. */
    private void loadToFill() {
        // not while a pass runs: each layout of a short list would start the next pages again (W25)
        boolean running = mState != null && mState.pass == SearchUi.Pass.RUNNING;
        if (mModelId != Consts.MODEL_GENRE && !running && getCount() > 0 && !binding.list.canScrollVertically(1)) {
            mPresenter.loadNext();
        }
    }

    /** The authors / readers screen: its failed source, with "Повторить". */
    @Override
    public void showSourcesFailed(int count) {
        if (mAddapterGenre == null) {
            return;
        }
        if (count > 0) {
            binding.summary.setText(RussianResources.of(this).getQuantityString(
                    R.plurals.search_sources_failed, count, count));
            binding.action.setText(R.string.retry);
            binding.action.setVisibility(View.VISIBLE);
            binding.summaryRow.setVisibility(View.VISIBLE);
        } else {
            binding.summaryRow.setVisibility(View.GONE);
        }
    }

    @Override
    public void returnResult(String url, String name, int modelId, String tag) {
        Intent intent = new Intent();
        intent.putExtra("url", url);
        intent.putExtra("name", name);
        intent.putExtra("mModelId", modelId);
        intent.putExtra("tag", tag);
        setResult(Activity.RESULT_OK, intent);
        finish();
    }

    @Override
    public void showSeriesAndAutors(SearcheblPOJO searcheblPOJO) {
        if (mAdapterSeries != null && mAdapterAutors != null && searcheblPOJO != null) {
            binding.topList.setVisibility(View.VISIBLE);
            if (searcheblPOJO.getAutorsList().isEmpty()) {
                binding.autors.setVisibility(View.GONE);
                binding.authorList.setVisibility(View.GONE);
            } else {
                binding.autors.setVisibility(View.VISIBLE);
                binding.authorList.setVisibility(View.VISIBLE);
                binding.autors.setText(searcheblPOJO.getAutorsCount());
                mAdapterAutors.setItem(searcheblPOJO.getAutorsList());
            }

            if (searcheblPOJO.getSeriesList().isEmpty()) {
                binding.series.setVisibility(View.GONE);
                binding.seriesList.setVisibility(View.GONE);
            } else {
                binding.series.setVisibility(View.VISIBLE);
                binding.seriesList.setVisibility(View.VISIBLE);
                binding.series.setText(searcheblPOJO.getSeriesCount());
                mAdapterSeries.setItem(searcheblPOJO.getSeriesList());
            }
        } else {
            binding.topList.setVisibility(View.GONE);
        }

    }

    @Override
    public void startBookActivity(@NotNull @NonNull BookPOJO bookPOJO) {

        if (Url.isHost(bookPOJO.getUrl(), Url.SERVER_ABMP3)) {
            // ABMP3 search cards carry only title and URL: load the full description first
            Intent intent = new Intent(this, LoadBook.class);
            intent.putExtra("url", bookPOJO.getUrl());
            startActivity(intent);
        } else {
            BookActivity.startNewActivity(this, bookPOJO);
        }
    }

    private void setItemDecoration(int count) {
        int spacing = (int) getResources().getDimension(R.dimen.recycler_item_margin);
        boolean includeEdge = true;
        binding.list.addItemDecoration(new GridSpacingItemDecoration(count, spacing, includeEdge));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (handleUserInput(getApplicationContext(), event.getKeyCode())) {
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }


}
