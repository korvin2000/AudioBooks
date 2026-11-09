package com.fanok.audiobooks.fragment;

import static com.fanok.audiobooks.Consts.getAttributeColor;
import static com.fanok.audiobooks.Consts.setColorPrimeriTextInIconItemMenu;
import static com.fanok.audiobooks.presenter.FavoritePeoplePresenter.FILTER_BOOKS;
import static com.fanok.audiobooks.presenter.FavoritePeoplePresenter.FILTER_GENRE;
import static com.fanok.audiobooks.presenter.FavoritePeoplePresenter.FILTER_NONE;
import static com.fanok.audiobooks.presenter.FavoritePeoplePresenter.FILTER_SITE;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.PopupMenu;
import androidx.appcompat.widget.SearchView;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.fanok.audiobooks.Consts;
import com.fanok.audiobooks.GridSpacingItemDecoration;
import com.fanok.audiobooks.R;
import com.fanok.audiobooks.activity.MainActivity;
import com.fanok.audiobooks.activity.SearchableActivity;
import com.fanok.audiobooks.adapter.FavoritePeopleAdapter;
import com.fanok.audiobooks.adapter.ListFocus;
import com.fanok.audiobooks.databinding.FragmentFavoriteBinding;
import com.fanok.audiobooks.model.BooksDBModel;
import com.fanok.audiobooks.model.FavoritePeople;
import com.fanok.audiobooks.presenter.FavoritePeoplePresenter;
import com.fanok.audiobooks.presenter.FavoritePeoplePresenter.Row;
import com.fanok.audiobooks.search.Narrators;
import com.fanok.audiobooks.search.Provider;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.snackbar.Snackbar;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import it.xabaras.android.recyclerview.swipedecorator.RecyclerViewSwipeDecorator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Избранное → Авторы or Чтецы ({@link FavoritePeople#AUTHOR} / {@link FavoritePeople#READER}): a tap searches every
 * site for the person's books, a long press offers their site pages, a swipe removes them (with undo). The rows are
 * read off the main thread on every return to the screen and when the favourites change.
 */
public class FavoritePeopleFragment extends Fragment implements FavoritePeople.Listener {
    private static final String ARG_KIND = "kind";
    private static final String PREF_SORT = "favorite_people_sort";
    private static final String STATE_FILTER = "filter";
    private static final String STATE_FILTER_VALUE = "filterValue";
    /** The sorts by {@link FavoritePeoplePresenter} index. */
    private static final int[] SORT_IDS = {R.id.people_sort_date, R.id.people_sort_name, R.id.people_sort_books};
    /** Hidden while nobody is in the list: there is nothing to search, filter, sort or clear. */
    private static final int[] LIST_IDS = {R.id.app_bar_search, R.id.filter, R.id.order, R.id.clear_list};

    private int kind;
    private final FavoritePeoplePresenter mList = new FavoritePeoplePresenter();
    private FragmentFavoriteBinding binding;
    private FavoritePeopleAdapter mAdapter;
    @Nullable
    private Menu mMenu;
    @Nullable
    private Disposable mLoad;
    private boolean mLoaded;
    /** Nobody is in the list (the menu hides {@link #LIST_IDS}). */
    private boolean mEmpty = true;
    @Nullable
    private BottomSheetDialog mSheet;

    private final ActivityResultLauncher<Intent> searchLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                Intent data = result.getData();
                String url = data == null ? null : data.getStringExtra("url");
                if (result.getResultCode() == Activity.RESULT_OK && url != null) {
                    // an author, series or collection chosen in the search opens as its listing
                    String tag = data.getStringExtra("tag");
                    openListing(url, data.getStringExtra("name"), tag == null ? "searchebleBooks" : tag);
                }
            });

    public static FavoritePeopleFragment newInstance(int kind) {
        FavoritePeopleFragment fragment = new FavoritePeopleFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_KIND, kind);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        kind = requireArguments().getInt(ARG_KIND, FavoritePeople.AUTHOR);
        mList.setSort(PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getInt(PREF_SORT, FavoritePeoplePresenter.SORT_DATE));
        if (savedInstanceState != null) {
            mList.setFilter(savedInstanceState.getInt(STATE_FILTER, FILTER_NONE),
                    savedInstanceState.getString(STATE_FILTER_VALUE));
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_FILTER, mList.filter());
        outState.putString(STATE_FILTER_VALUE, mList.filterValue());
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        binding = FragmentFavoriteBinding.inflate(inflater, container, false);
        requireActivity().setTitle(R.string.menu_favorite);
        FavoritesSwitcher.install(this, kind);
        requireActivity().addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
                menuInflater.inflate(R.menu.favorite_people_options_menu, menu);
                mMenu = menu;
                for (int id : LIST_IDS) {
                    if (id != R.id.filter) {
                        setColorPrimeriTextInIconItemMenu(menu.findItem(id), requireContext());
                    }
                }
                SearchView searchView = (SearchView) menu.findItem(R.id.app_bar_search).getActionView();
                if (searchView != null) {
                    searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                        @Override
                        public boolean onQueryTextChange(String s) {
                            mList.setQuery(s);
                            show();
                            return false;
                        }

                        @Override
                        public boolean onQueryTextSubmit(String s) {
                            return false;
                        }
                    });
                }
                syncMenu();
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem item) {
                int id = item.getItemId();
                if (id == R.id.clear_list) {
                    Context app = requireContext().getApplicationContext();
                    FavoriteFragment.confirmClear(requireContext(), FavoritesSwitcher.title(kind),
                            kind == FavoritePeople.READER ? R.string.favorite_people_clear_readers
                                    : R.string.favorite_people_clear_authors,
                            () -> FavoritePeople.get(app).clear(kind));
                } else if (id == R.id.people_site_filter) {
                    offerSites();
                } else if (id == R.id.people_genre_filter) {
                    ArrayList<String> genres = mList.genres();
                    offer(FILTER_GENRE, genres, genres);
                } else if (id == R.id.people_books_filter) {
                    setFilter(mList.filter() == FILTER_BOOKS ? FILTER_NONE : FILTER_BOOKS, null);
                } else {
                    int sort = indexOf(SORT_IDS, id);
                    if (sort < 0) {
                        return false; // the filter and sort icons open their menus
                    }
                    mList.setSort(sort);
                    PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
                            .putInt(PREF_SORT, sort).apply();
                    item.setChecked(true);
                    show();
                }
                return true;
            }
        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);

        mAdapter = new FavoritePeopleAdapter();
        mAdapter.setListener((row, longClick) -> {
            if (longClick) {
                showSheet(row);
            } else {
                search(row);
            }
        });
        binding.list.setAdapter(mAdapter);

        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder,
                    @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                Row row = mAdapter == null ? null : mAdapter.getItem(viewHolder.getBindingAdapterPosition());
                if (row != null) {
                    remove(row);
                }
            }

            @Override
            public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView recyclerView,
                    @NonNull RecyclerView.ViewHolder viewHolder, float dX, float dY,
                    int actionState, boolean isCurrentlyActive) {
                new RecyclerViewSwipeDecorator.Builder(c, recyclerView, viewHolder, dX, dY,
                        actionState, isCurrentlyActive)
                        .addBackgroundColor(getAttributeColor(requireContext(), R.attr.deleteColor))
                        .addActionIcon(R.drawable.ic_delete_swipe)
                        .setIconHorizontalMargin(TypedValue.COMPLEX_UNIT_DIP, 24)
                        .create()
                        .decorate();
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
            }
        }).attachToRecyclerView(binding.list);

        // the columns of the books' lists: 1 or 2 on a phone, 2 or 3 on a tablet
        boolean portrait = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        int columns = getResources().getInteger(R.integer.isTablet) > 0 ? (portrait ? 2 : 3) : (portrait ? 1 : 2);
        binding.list.setLayoutManager(columns == 1 ? new LinearLayoutManager(getContext())
                : new GridLayoutManager(getContext(), columns));
        binding.list.addItemDecoration(new GridSpacingItemDecoration(columns,
                (int) getResources().getDimension(R.dimen.recycler_item_margin), true));

        FavoritePeople.get(requireContext()).addListener(this);
        if (mLoaded) {
            show(); // back from another screen: the rows read before, until onResume reads them again
        }
        return binding.getRoot();
    }

    @Override
    public void onResume() {
        super.onResume();
        MainActivity activity = (MainActivity) getActivity();
        if (activity != null) {
            NavigationView navigationView = activity.getNavigationView();
            if (navigationView != null) {
                navigationView.setCheckedItem(R.id.nav_favorite);
            } else {
                activity.highlightNav(R.id.layout_nav_favorite);
            }
        }
        load();
    }

    @Override
    public void onDestroyView() {
        FavoritePeople.get(requireContext()).removeListener(this);
        if (mLoad != null) {
            mLoad.dispose();
            mLoad = null;
        }
        if (mSheet != null) {
            mSheet.dismiss();
            mSheet = null;
        }
        FavoritesSwitcher.uninstall(this);
        mMenu = null;
        mAdapter = null;
        super.onDestroyView();
        binding = null;
    }

    /** Another screen (a book, the search, the site lists) changed this list: the screen on top reads it again. */
    @Override
    public void onFavoritePeopleChanged(int changed) {
        if (changed == kind && isResumed()) {
            load();
        }
    }

    /** The people with what the favourites and the history say of them, off the main thread. */
    private void load() {
        if (mLoad != null) {
            mLoad.dispose();
        }
        if (!mLoaded) {
            binding.progressBarLayout.getRoot().setVisibility(View.VISIBLE);
        }
        final Context app = requireContext().getApplicationContext();
        final int k = kind;
        mLoad = Single.fromCallable(() -> {
                    BooksDBModel db = new BooksDBModel(app);
                    ArrayList<Row> rows = FavoritePeoplePresenter.rows(k, FavoritePeople.get(app).all(k),
                            db.getAllFavorite(), db.getAllHistory());
                    if (k == FavoritePeople.READER) { // a reader's card opens from the row, as in Исполнители
                        for (Row row : rows) {
                            row.card = Narrators.card(row.person.name);
                        }
                    }
                    return rows;
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(rows -> {
                    mLoaded = true;
                    binding.progressBarLayout.getRoot().setVisibility(View.GONE);
                    mList.setRows(rows);
                    show();
                }, e -> {
                    binding.progressBarLayout.getRoot().setVisibility(View.GONE);
                    mAdapter.setItems(new ArrayList<>());
                    showPlaceholder(R.string.error_load_data, R.string.retry, this::load);
                });
    }

    /** The rows that pass the search and the filter; an empty list says how to fill it. */
    private void show() {
        if (binding == null || mAdapter == null) {
            return;
        }
        boolean empty = mList.rows().isEmpty();
        if (empty != mEmpty) {
            mEmpty = empty;
            if (empty) {
                mList.setQuery("");
                mList.setFilter(FILTER_NONE, null);
            }
            syncMenu();
        }
        ArrayList<Row> shown = mList.shown();
        mAdapter.setItems(shown);
        ListFocus.afterSectionUpdate(binding.list, this); // opened from the TV's side menu: its first row
        if (empty) {
            boolean readers = kind == FavoritePeople.READER;
            showPlaceholder(readers ? R.string.favorite_people_empty_readers : R.string.favorite_people_empty_authors,
                    readers ? R.string.favorite_people_open_readers : R.string.favorite_people_open_authors, () -> {
                        MainActivity activity = (MainActivity) getActivity();
                        if (activity != null) {
                            activity.openSection(readers ? R.id.nav_artist : R.id.nav_autor);
                        }
                    });
        } else {
            showPlaceholder(shown.isEmpty() ? R.string.favorite_people_nothing_found : 0, 0, null);
        }
        FavoritesSwitcher.setSubtitle(this, filterTitle());
    }

    /** The centred message instead of the list, with a button if {@code button} is not 0; {@code 0} hides it. */
    private void showPlaceholder(int message, int button, @Nullable Runnable action) {
        if (message == 0) {
            binding.placeholder.getRoot().setVisibility(View.GONE);
            return;
        }
        binding.placeholder.placeholderText.setText(message);
        binding.placeholder.placeholderRetry.setVisibility(button != 0 ? View.VISIBLE : View.GONE);
        if (button != 0 && action != null) {
            binding.placeholder.placeholderRetry.setText(button);
            binding.placeholder.placeholderRetry.setOnClickListener(v -> action.run());
        }
        binding.placeholder.getRoot().setVisibility(View.VISIBLE);
    }

    /** The menu as the list is: its items only with somebody in it, the filter icon marked while one is on. */
    private void syncMenu() {
        Menu menu = mMenu;
        if (menu == null) {
            return;
        }
        for (int id : LIST_IDS) {
            MenuItem item = menu.findItem(id);
            if (item != null) {
                if (mEmpty && item.isActionViewExpanded()) {
                    item.collapseActionView();
                }
                item.setVisible(!mEmpty);
            }
        }
        MenuItem filter = menu.findItem(R.id.filter);
        if (filter == null) {
            return;
        }
        if (mList.filter() == FILTER_NONE) {
            filter.setIcon(R.drawable.ic_filter);
            setColorPrimeriTextInIconItemMenu(filter, requireContext());
        } else {
            filter.setIcon(R.drawable.ic_filter_active);
        }
        menu.findItem(R.id.people_books_filter).setChecked(mList.filter() == FILTER_BOOKS);
        menu.findItem(R.id.people_site_filter).setVisible(!mList.sites().isEmpty());
        menu.findItem(R.id.people_genre_filter).setVisible(!mList.genres().isEmpty());
        menu.findItem(SORT_IDS[mList.sort()]).setChecked(true);
    }

    private void setFilter(int filter, @Nullable String value) {
        mList.setFilter(value == null && filter != FILTER_BOOKS ? FILTER_NONE : filter, value);
        syncMenu();
        show();
    }

    /** The filter on, as the subtitle says it; empty without one. */
    @NonNull
    private String filterTitle() {
        if (mList.filter() == FILTER_SITE) {
            Provider site = FavoritePeoplePresenter.provider(mList.filterValue());
            return site == null ? "" : site.title;
        } else if (mList.filter() == FILTER_BOOKS) {
            return getString(R.string.favorite_people_filter_books);
        } else if (mList.filter() == FILTER_GENRE && mList.filterValue() != null) {
            return mList.filterValue();
        }
        return "";
    }

    /** The sites of the people, each with the number of people there. */
    private void offerSites() {
        List<String> labels = new ArrayList<>();
        List<String> values = new ArrayList<>();
        for (Map.Entry<Provider, Integer> site : mList.sites().entrySet()) {
            labels.add(getString(R.string.sources_filter_value, site.getKey().title, site.getValue()));
            values.add(site.getKey().name());
        }
        offer(FILTER_SITE, labels, values);
    }

    /** A popup of {@code labels} after "Все"; a choice sets {@code filter} to its value. */
    private void offer(int filter, @NonNull List<String> labels, @NonNull List<String> values) {
        if (binding == null || labels.isEmpty()) {
            return;
        }
        PopupMenu popup = new PopupMenu(requireContext(), binding.view, Gravity.END);
        popup.getMenu().add(1, 0, 0, R.string.all);
        for (int i = 0; i < labels.size(); i++) {
            popup.getMenu().add(1, i + 1, 0, labels.get(i));
        }
        popup.setOnMenuItemClickListener(item -> {
            int index = item.getItemId();
            setFilter(index == 0 ? FILTER_NONE : filter, index == 0 ? null : values.get(index - 1));
            return true;
        });
        popup.show();
    }

    /** The person's books on every site, newest first; a listing chosen there comes back here to open. */
    private void search(@NonNull Row row) {
        searchLauncher.launch(SearchableActivity.personIntent(requireContext(), kind, row.person.name));
    }

    private void openListing(@NonNull String url, @Nullable String title, @NonNull String tag) {
        MainActivity activity = (MainActivity) getActivity();
        if (activity != null) {
            activity.showFragment(BooksFragment.newInstance(url, R.string.menu_audiobooks, title,
                    Consts.MODEL_BOOKS), tag);
        }
    }

    /** Out of the favourites at once; "Отменить" puts the person back as they were (pages and date added). */
    private void remove(@NonNull Row row) {
        FavoritePeople people = FavoritePeople.get(requireContext());
        people.remove(kind, row.person.name);
        mList.remove(row);
        show();
        final int k = kind;
        final String name = row.person.name;
        Snackbar.make(binding.getRoot(), getString(R.string.favorite_people_removed, name), Snackbar.LENGTH_LONG)
                .setAction(R.string.undo, v -> people.undoRemove(k, name))
                .show();
    }

    @SuppressLint("InflateParams")
    private void showSheet(@NonNull Row row) {
        if (mSheet != null) {
            mSheet.dismiss();
        }
        View layout = getLayoutInflater().inflate(R.layout.bottom_sheet_person_menu, null);
        final BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
        dialog.setContentView(layout);
        mSheet = dialog;
        dialog.setOnDismissListener(d -> {
            if (mSheet == dialog) {
                mSheet = null;
            }
        });
        ((TextView) layout.findViewById(R.id.personName)).setText(row.person.name);
        ((TextView) layout.findViewById(R.id.personDesc)).setText(
                FavoritePeopleAdapter.describe(requireContext(), row));
        ViewGroup actions = layout.findViewById(R.id.personActions);
        addAction(dialog, actions, R.drawable.ic_magnify, getString(R.string.favorite_people_search),
                () -> search(row));
        boolean readers = kind == FavoritePeople.READER;
        for (String url : row.person.urls) {
            Provider site = Provider.ofUrl(url);
            if (site != null) {
                addAction(dialog, actions, readers ? R.drawable.ic_artist_bottom_sheet : R.drawable.ic_autor_bottom_sheet,
                        getString(R.string.favorite_people_open_on, site.title),
                        () -> openListing(url, row.person.name, readers ? "artistBooks" : "autorBooks"));
            }
        }
        addAction(dialog, actions, R.drawable.ic_minus_circle, getString(R.string.favorite_people_remove),
                () -> remove(row));
        dialog.show();
    }

    private void addAction(@NonNull BottomSheetDialog dialog, @NonNull ViewGroup parent, int icon,
            @NonNull String text, @NonNull Runnable action) {
        TextView view = (TextView) getLayoutInflater().inflate(R.layout.bottom_sheet_person_row, parent, false);
        view.setText(text);
        view.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
        view.setOnClickListener(v -> {
            dialog.dismiss();
            if (binding != null) {
                action.run();
            }
        });
        parent.addView(view);
    }

    private static int indexOf(@NonNull int[] ids, int id) {
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] == id) {
                return i;
            }
        }
        return -1;
    }
}
