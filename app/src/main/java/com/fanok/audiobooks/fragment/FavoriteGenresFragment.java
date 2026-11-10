package com.fanok.audiobooks.fragment;

import static com.fanok.audiobooks.Consts.getAttributeColor;
import static com.fanok.audiobooks.Consts.setColorPrimeriTextInIconItemMenu;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.fanok.audiobooks.GridSpacingItemDecoration;
import com.fanok.audiobooks.FontScale;
import com.fanok.audiobooks.R;
import com.fanok.audiobooks.activity.MainActivity;
import com.fanok.audiobooks.adapter.ListFocus;
import com.fanok.audiobooks.databinding.FragmentFavoriteBinding;
import com.fanok.audiobooks.search.Genre;
import com.fanok.audiobooks.search.GenrePicks;
import com.fanok.audiobooks.search.Provider;
import com.fanok.audiobooks.search.Sources;
import com.fanok.audiobooks.util.RussianResources;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.snackbar.Snackbar;
import it.xabaras.android.recyclerview.swipedecorator.RecyclerViewSwipeDecorator;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Избранное → Жанры: the app's genres the user starred ({@link GenrePicks#favorites}). A tap opens the selected
 * source's genre list narrowed to the genre, a long press offers it on every active source, a swipe removes it (with
 * undo), the plus adds genres from the app's list. At most a few dozen rows, read from the preferences on every show.
 */
public class FavoriteGenresFragment extends Fragment {
    private static final String PREF_SORT = "favorite_genres_sort";
    /** The tag of a narrowed genre list: the drawer's own Жанры under "genre" stays on the back stack. */
    private static final String TAG_GENRE = "favoriteGenre";
    /** The sorts by their index: newest first, by name, most opened first. */
    private static final int SORT_ADDED = 0, SORT_NAME = 1, SORT_PICKS = 2;
    private static final int[] SORT_IDS = {R.id.favorite_genres_sort_added, R.id.favorite_genres_sort_name,
            R.id.favorite_genres_sort_picks};
    /** Hidden while no genre is in the list: there is nothing to sort or clear. */
    private static final int[] LIST_IDS = {R.id.order, R.id.clear_list};

    private FragmentFavoriteBinding binding;
    private Adapter mAdapter;
    @Nullable
    private Menu mMenu;
    @Nullable
    private BottomSheetDialog mSheet;
    private int mSort;
    /** No genre is in the list (the menu hides {@link #LIST_IDS}). */
    private boolean mEmpty = true;
    /** A sync may change the favourites while they are shown. A field: SharedPreferences holds listeners weakly. */
    private final SharedPreferences.OnSharedPreferenceChangeListener mFavoritesListener = (prefs, key) -> {
        if (GenrePicks.PREF_FAVORITES.equals(key)) {
            show();
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mSort = PreferenceManager.getDefaultSharedPreferences(requireContext()).getInt(PREF_SORT, SORT_ADDED);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentFavoriteBinding.inflate(inflater, container, false);
        requireActivity().setTitle(R.string.menu_favorite);
        FavoritesSwitcher.install(this, FavoritesSwitcher.GENRES);
        requireActivity().addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
                menuInflater.inflate(R.menu.favorite_genres_options_menu, menu);
                mMenu = menu;
                for (int id : new int[]{R.id.favorite_genres_add, R.id.order, R.id.clear_list}) {
                    setColorPrimeriTextInIconItemMenu(menu.findItem(id), requireContext());
                }
                syncMenu();
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem item) {
                int id = item.getItemId();
                if (id == R.id.favorite_genres_add) {
                    pick();
                } else if (id == R.id.clear_list) {
                    Context app = requireContext().getApplicationContext();
                    FavoriteFragment.confirmClear(requireContext(), FavoritesSwitcher.title(FavoritesSwitcher.GENRES),
                            R.string.favorite_genres_clear, () -> {
                                GenrePicks.clearFavorites(app);
                                show();
                            });
                } else {
                    int sort = indexOf(SORT_IDS, id);
                    if (sort < 0) {
                        return false; // the sort icon opens its menu
                    }
                    mSort = sort;
                    PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
                            .putInt(PREF_SORT, sort).apply();
                    item.setChecked(true);
                    show();
                }
                return true;
            }
        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);

        mAdapter = new Adapter((genre, longClick) -> {
            if (longClick) {
                showSheet(genre);
            } else {
                open(genre, Provider.current());
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
                Genre genre = mAdapter == null ? null : mAdapter.getItem(viewHolder.getBindingAdapterPosition());
                if (genre != null) {
                    remove(genre);
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

        // the columns of the people lists: 1 or 2 on a phone, 2 or 3 on a tablet
        boolean portrait = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        int columns = getResources().getInteger(R.integer.isTablet) > 0 ? (portrait ? 2 : 3) : (portrait ? 1 : 2);
        binding.list.setLayoutManager(columns == 1 ? new LinearLayoutManager(getContext())
                : new GridLayoutManager(getContext(), columns));
        binding.list.addItemDecoration(new GridSpacingItemDecoration(columns,
                (int) getResources().getDimension(R.dimen.recycler_item_margin), true));
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
        PreferenceManager.getDefaultSharedPreferences(requireContext())
                .registerOnSharedPreferenceChangeListener(mFavoritesListener);
        show(); // a star may have changed in a site's Жанры
    }

    @Override
    public void onPause() {
        PreferenceManager.getDefaultSharedPreferences(requireContext())
                .unregisterOnSharedPreferenceChangeListener(mFavoritesListener);
        super.onPause();
    }

    @Override
    public void onDestroyView() {
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

    /** The favourites in the chosen order; an empty list says how to fill it. */
    private void show() {
        if (binding == null || mAdapter == null) {
            return;
        }
        Context context = requireContext();
        Map<String, ?> counts = GenrePicks.counts(context);
        List<Genre> genres = GenrePicks.favorites(context);
        Collections.reverse(genres); // newest first; the other orders keep it among equals
        if (mSort == SORT_NAME) {
            Collator collator = Collator.getInstance(new Locale("ru"));
            Collections.sort(genres, (a, b) -> collator.compare(getString(a.title), getString(b.title)));
        } else if (mSort == SORT_PICKS) {
            Collections.sort(genres, (a, b) -> Integer.compare(GenrePicks.opens(counts, b),
                    GenrePicks.opens(counts, a)));
        }
        boolean empty = genres.isEmpty();
        if (empty != mEmpty) {
            mEmpty = empty;
            syncMenu();
        }
        mAdapter.setItems(genres, counts);
        ListFocus.afterSectionUpdate(binding.list, this); // opened from the TV's side menu: its first row
        binding.placeholder.getRoot().setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            binding.placeholder.placeholderText.setText(R.string.favorite_genres_empty);
            binding.placeholder.placeholderRetry.setText(R.string.favorite_genres_add);
            binding.placeholder.placeholderRetry.setOnClickListener(v -> pick());
            binding.placeholder.placeholderRetry.setVisibility(View.VISIBLE);
        }
    }

    /** The menu as the list is: sorting and clearing only with a genre in it. */
    private void syncMenu() {
        Menu menu = mMenu;
        if (menu == null) {
            return;
        }
        for (int id : LIST_IDS) {
            menu.findItem(id).setVisible(!mEmpty);
        }
        menu.findItem(SORT_IDS[mSort >= 0 && mSort < SORT_IDS.length ? mSort : SORT_ADDED]).setChecked(true);
    }

    /**
     * The app's genres with the favourites ticked; "Готово" stars the newly ticked ones and drops the unticked ones,
     * so a genre a sync changed meanwhile keeps that change.
     */
    private void pick() {
        Context app = requireContext().getApplicationContext();
        Genre[] all = Genre.values();
        List<Genre> favorites = GenrePicks.favorites(app);
        final boolean[] checked = new boolean[all.length];
        String[] names = new String[all.length];
        for (int i = 0; i < all.length; i++) {
            checked[i] = favorites.contains(all[i]);
            names[i] = getString(all[i].title);
        }
        final boolean[] initial = checked.clone();
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.favorite_genres_pick)
                .setMultiChoiceItems(names, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(R.string.search_scope_done, (d, w) -> {
                    for (int i = 0; i < all.length; i++) {
                        if (checked[i] != initial[i]) {
                            GenrePicks.setFavorite(app, all[i], checked[i]);
                        }
                    }
                    show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** The genre list of {@code site} narrowed to {@code genre}; a site without genres says so. */
    private void open(@NonNull Genre genre, @Nullable Provider site) {
        MainActivity activity = (MainActivity) getActivity();
        if (activity == null) {
            return;
        }
        if (site == null || site.sections == null) {
            activity.showToast(R.string.section_not_available);
            return;
        }
        activity.showFragment(BooksFragment.newInstance(site.sections, genre, getString(genre.title)), TAG_GENRE);
    }

    /** Out of the favourites at once; "Отменить" puts the genre back at its place. */
    private void remove(@NonNull Genre genre) {
        Context app = requireContext().getApplicationContext();
        int index = GenrePicks.favorites(app).indexOf(genre);
        GenrePicks.setFavorite(app, genre, false);
        show();
        if (index >= 0) {
            Snackbar.make(binding.getRoot(), getString(R.string.favorite_people_removed, getString(genre.title)),
                            Snackbar.LENGTH_LONG)
                    .setAction(R.string.undo, v -> {
                        GenrePicks.addFavorite(app, genre, index);
                        show();
                    })
                    .show();
        }
    }

    /** "Открывали 3 раза" or "Ещё не открывали". */
    @NonNull
    private static String describe(@NonNull Context context, @NonNull Map<String, ?> counts, @NonNull Genre genre) {
        int opens = GenrePicks.opens(counts, genre);
        return opens > 0 ? RussianResources.of(context).getQuantityString(R.plurals.favorite_genres_picks, opens, opens)
                : context.getString(R.string.favorite_genres_never);
    }

    /** The genre on the selected source first, then on every other active source with genres; then its removal. */
    @SuppressLint("InflateParams")
    private void showSheet(@NonNull Genre genre) {
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
        ((TextView) layout.findViewById(R.id.personName)).setText(genre.title);
        ((TextView) layout.findViewById(R.id.personDesc)).setText(
                describe(requireContext(), GenrePicks.counts(requireContext()), genre));
        ViewGroup actions = layout.findViewById(R.id.personActions);
        List<Provider> sites = new ArrayList<>(Sources.active());
        Provider current = Provider.current();
        if (current != null && sites.remove(current)) {
            sites.add(0, current);
        }
        for (Provider site : sites) {
            if (site.sections != null) {
                addAction(dialog, actions, R.drawable.ic_genre_bottom_sheet,
                        getString(R.string.favorite_people_open_on, site.title), () -> open(genre, site));
            }
        }
        addAction(dialog, actions, R.drawable.ic_minus_circle, getString(R.string.favorite_people_remove),
                () -> remove(genre));
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

    /** The rows: the genre's name and how often it was opened, on the site lists' row without its number. */
    private static final class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
        interface Listener {
            void onRow(@NonNull Genre genre, boolean longClick);
        }

        private final Listener mListener;
        private List<Genre> mGenres = new ArrayList<>();
        private Map<String, ?> mCounts = Collections.emptyMap();

        Adapter(@NonNull Listener listener) {
            mListener = listener;
        }

        @SuppressLint("NotifyDataSetChanged") // a few dozen rows at most
        void setItems(@NonNull List<Genre> genres, @NonNull Map<String, ?> counts) {
            mGenres = genres;
            mCounts = counts;
            notifyDataSetChanged();
        }

        @Nullable
        Genre getItem(int position) {
            return position >= 0 && position < mGenres.size() ? mGenres.get(position) : null;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View row = LayoutInflater.from(parent.getContext()).inflate(R.layout.genre_recycler_item, parent, false);
            FontScale.grow(row);
            return new Holder(row);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Genre genre = mGenres.get(position);
            holder.name.setText(genre.title);
            holder.desc.setText(describe(holder.itemView.getContext(), mCounts, genre));
        }

        @Override
        public int getItemCount() {
            return mGenres.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final TextView name;
            final TextView desc;

            Holder(@NonNull View itemView) {
                super(itemView);
                name = itemView.findViewById(R.id.name);
                desc = itemView.findViewById(R.id.desc);
                itemView.findViewById(R.id.reting).setVisibility(View.GONE);
                itemView.setFocusable(true); // a click listener alone makes it focusable only from API 26
                itemView.setOnClickListener(v -> click(false));
                itemView.setOnLongClickListener(v -> click(true));
            }

            private boolean click(boolean longClick) {
                Genre genre = getItem(getBindingAdapterPosition());
                if (genre != null) {
                    mListener.onRow(genre, longClick);
                }
                return true;
            }
        }
    }
}
