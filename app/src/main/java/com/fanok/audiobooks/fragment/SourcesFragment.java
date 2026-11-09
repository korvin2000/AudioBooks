package com.fanok.audiobooks.fragment;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.fanok.audiobooks.R;
import com.fanok.audiobooks.activity.MainActivity;
import com.fanok.audiobooks.activity.SourceActivity;
import com.fanok.audiobooks.adapter.SourcesAdapter;
import com.fanok.audiobooks.net.AppProxy;
import com.fanok.audiobooks.net.ProxyFinder;
import com.fanok.audiobooks.region.Region;
import com.fanok.audiobooks.search.Provider;
import com.fanok.audiobooks.search.SourceHealth;
import com.fanok.audiobooks.search.SourceInfo;
import com.fanok.audiobooks.search.SourceProbe;
import com.fanok.audiobooks.search.Sources;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.chip.Chip;
import com.google.android.material.navigation.NavigationView;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Источники", the drawer section: every site in the user's order, as the sources dialog has them, with what the app
 * knows of each (books, last check, route) and the region and proxy state on top. A row opens the site's card: a
 * screen of its own on a phone ({@link SourceActivity}), the right pane on a tablet in landscape (master–detail). The
 * list keeps no copy of the state: it redraws from {@link Sources}, {@link AppProxy} and {@link SourceHealth} whenever
 * one of their preferences changes, wherever the change came from (the card, the dialog, a check).
 */
public class SourcesFragment extends Fragment implements SourcesAdapter.Listener, SourceCardFragment.Host {

    /** The back-stack tag of the section. */
    public static final String TAG = "sources";
    private static final String STATE_ORDER = "order";
    private static final String STATE_SELECTED = "selected";
    /** Sites checked at once by the refresh button, as in the sources dialog. */
    private static final int PARALLEL = 4;
    private static final int MENU_UP = 1;
    private static final int MENU_DOWN = 2;
    private static final int MENU_TOGGLE = 3;
    private static final int MENU_ROUTE = 4;

    private SharedPreferences prefs;
    private final Map<Provider, SourceInfo> infos = new EnumMap<>(Provider.class);
    private final Set<Provider> checking = EnumSet.noneOf(Provider.class);
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final SourcesAdapter adapter = new SourcesAdapter(this);
    private SourcesAdapter.Order order = SourcesAdapter.Order.USER;
    /** The site whose card is open in the pane (tablets). */
    @Nullable
    private Provider selected;
    /** Checks under way; the refresh button is a spinner while there is one. */
    private int running;
    private boolean twoPane;
    @Nullable
    private View root;
    private Chip regionChip;
    private Chip proxyChip;
    private Chip checkedChip;
    /** The toolbar subtitle this screen set, cleared when it leaves unless another screen set its own. */
    @Nullable
    private CharSequence subtitle;
    private final Runnable refresh = this::render;
    /** The proxy's state the chip and the rows show; the search's progress alone does not redraw them. */
    @Nullable
    private SourceTexts.ProxyState proxyState;
    private final ProxyFinder.Listener finderListener = state -> {
        if (root != null && SourceTexts.proxyState(state) != proxyState) {
            render();
        }
    };
    /** A field: SharedPreferences holds its listeners weakly. */
    private final SharedPreferences.OnSharedPreferenceChangeListener listener = (p, key) -> {
        if (root != null && SourceTexts.watched(key)) {
            // once for a burst of changes, and after every listener of this one ran (AppProxy reads its routes there)
            root.removeCallbacks(refresh);
            root.post(refresh);
        }
    };

    private final MenuProvider menuProvider = new MenuProvider() {
        @Override
        public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
            inflater.inflate(R.menu.sources_list, menu);
        }

        @Override
        public void onPrepareMenu(@NonNull Menu menu) {
            MenuItem item = menu.findItem(R.id.sourcesRefresh);
            if (item != null) {
                item.setActionView(running > 0 ? progressView() : null);
            }
        }

        @Override
        public boolean onMenuItemSelected(@NonNull MenuItem item) {
            if (item.getItemId() == R.id.sourcesRefresh) {
                checkActive();
                return true;
            }
            if (item.getItemId() == R.id.sourcesManage) {
                manage();
                return true;
            }
            return false;
        }
    };

    /** True where the layout has the card pane: a tablet in landscape ({@code layout-sw600dp-land}). */
    static boolean twoPane(@NonNull Configuration configuration) {
        return configuration.smallestScreenWidthDp >= 600
                && configuration.orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        if (savedInstanceState != null) {
            order = SourcesAdapter.Order.valueOf(savedInstanceState.getString(STATE_ORDER, order.name()));
            String id = savedInstanceState.getString(STATE_SELECTED);
            selected = id == null ? null : Provider.valueOf(id);
        }
        Fragment card = getChildFragmentManager().findFragmentById(R.id.sourceDetail);
        if (card != null && !twoPane(getResources().getConfiguration())) {
            // turned to portrait: the pane is gone, and a restored card would find no container
            getChildFragmentManager().beginTransaction().remove(card).commitNowAllowingStateLoss();
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_sources, container, false);
        root = view;
        twoPane = view.findViewById(R.id.sourceDetail) != null;
        regionChip = view.findViewById(R.id.sourcesRegion);
        proxyChip = view.findViewById(R.id.sourcesProxy);
        checkedChip = view.findViewById(R.id.sourcesChecked);
        proxyChip.setOnClickListener(v -> showProxyDialog());
        checkedChip.setOnClickListener(v -> checkActive());

        MaterialButtonToggleGroup toggle = view.findViewById(R.id.sourcesOrder);
        toggle.check(order == SourcesAdapter.Order.BOOKS ? R.id.sourcesOrderBooks
                : order == SourcesAdapter.Order.RESPONSE ? R.id.sourcesOrderResponse : R.id.sourcesOrderUser);
        toggle.addOnButtonCheckedListener((group, id, checked) -> {
            if (checked) {
                order = id == R.id.sourcesOrderBooks ? SourcesAdapter.Order.BOOKS
                        : id == R.id.sourcesOrderResponse ? SourcesAdapter.Order.RESPONSE : SourcesAdapter.Order.USER;
                render();
            }
        });

        RecyclerView list = view.findViewById(R.id.sourcesList);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(adapter);
        RecyclerView.ItemAnimator animator = list.getItemAnimator();
        if (animator instanceof SimpleItemAnimator) {
            // a redraw changes rows in place; only a new order animates
            ((SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
        }
        requireActivity().addMenuProvider(menuProvider, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (twoPane) {
            if (selected == null) {
                selected = Sources.active().get(0);
            }
            if (getChildFragmentManager().findFragmentById(R.id.sourceDetail) == null) {
                showCard(selected);
            }
        }
        disposables.add(SourceTexts.infos(requireContext())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(map -> {
                    infos.putAll(map);
                    render();
                }, e -> Log.w(TAG, "No source facts", e)));
    }

    @Override
    public void onStart() {
        super.onStart();
        prefs.registerOnSharedPreferenceChangeListener(listener);
        ProxyFinder.get(requireContext()).addListener(finderListener);
        render(); // a change made while this screen was away (the card's screen, the settings)
    }

    @Override
    public void onResume() {
        super.onResume();
        requireActivity().setTitle(R.string.menu_sources);
        renderSubtitle();
        if (getActivity() instanceof MainActivity) {
            MainActivity activity = (MainActivity) getActivity();
            NavigationView navigationView = activity.getNavigationView();
            if (navigationView != null) {
                navigationView.setCheckedItem(R.id.nav_sources);
            } else {
                activity.highlightNav(R.id.layout_nav_sources);
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        ActionBar bar = actionBar();
        if (bar != null && subtitle != null && TextUtils.equals(bar.getSubtitle(), subtitle)) {
            bar.setSubtitle(null);
        }
    }

    @Override
    public void onStop() {
        prefs.unregisterOnSharedPreferenceChangeListener(listener);
        ProxyFinder.get(requireContext()).removeListener(finderListener);
        if (root != null) {
            root.removeCallbacks(refresh);
        }
        super.onStop();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_ORDER, order.name());
        if (selected != null) {
            outState.putString(STATE_SELECTED, selected.name());
        }
    }

    @Override
    public void onDestroyView() {
        disposables.clear();
        checking.clear();
        running = 0;
        if (root != null) {
            // the adapter outlives the view: it must not hold the old list and its rows
            ((RecyclerView) root.findViewById(R.id.sourcesList)).setAdapter(null);
        }
        root = null;
        regionChip = null;
        proxyChip = null;
        checkedChip = null;
        super.onDestroyView();
    }

    /** Draws the live state: the rows, the chips, the subtitle. */
    private void render() {
        if (root == null || !isAdded()) {
            return;
        }
        Context context = requireContext();
        Map<Provider, SourceProbe.Result> results = SourceHealth.last(prefs);
        Region region = SourceTexts.region(context);
        Map<Provider, Integer> books = new EnumMap<>(Provider.class);
        for (Map.Entry<Provider, SourceInfo> entry : infos.entrySet()) {
            if (entry.getValue().books != null) {
                books.put(entry.getKey(), entry.getValue().books);
            }
        }
        proxyState = SourceTexts.proxyState(context);
        boolean proxyOn = proxyState == SourceTexts.ProxyState.WORKS;
        adapter.show(SourcesAdapter.rows(Sources.all(), Sources.activeCount(), order, books, results), infos, results,
                checking, region, twoPane ? selected : null, proxyOn);

        regionChip.setText(SourceTexts.regionName(context, region));
        int proxied = 0;
        for (Provider provider : Sources.active()) {
            if (!AppProxy.isDirect(provider)) {
                proxied++;
            }
        }
        String reason = SourceTexts.proxyReason(context, proxyState);
        proxyChip.setText(reason == null ? getString(R.string.sources_chip_proxy_on, proxied)
                : SourceTexts.sentence(reason));
        proxyChip.setChipIconResource(proxyOn ? R.drawable.ic_shield_check : R.drawable.ic_source_shield_outline);
        String ago = SourceTexts.ago(context, SourceHealth.lastCheckedAt(prefs), System.currentTimeMillis());
        checkedChip.setText(ago == null ? getString(R.string.sources_chip_never)
                : getString(R.string.sources_chip_checked, ago));
        renderSubtitle();
    }

    private void renderSubtitle() {
        ActionBar bar = actionBar();
        Provider catalog = Provider.current();
        if (bar != null && catalog != null && isResumed()) {
            subtitle = getString(R.string.sources_list_subtitle, catalog.title);
            bar.setSubtitle(subtitle);
        }
    }

    @Nullable
    private ActionBar actionBar() {
        return getActivity() instanceof AppCompatActivity ? ((AppCompatActivity) getActivity()).getSupportActionBar()
                : null;
    }

    /** The refresh button while a check runs: a spinner in the toolbar's white. */
    @NonNull
    private View progressView() {
        Context context = requireContext();
        float density = getResources().getDisplayMetrics().density;
        ProgressBar bar = new ProgressBar(context);
        bar.setIndeterminateTintList(ColorStateList.valueOf(Color.WHITE));
        bar.setContentDescription(getString(R.string.sources_checking));
        FrameLayout frame = new FrameLayout(context);
        frame.setMinimumWidth((int) (48 * density));
        int size = (int) (24 * density);
        frame.addView(bar, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
        return frame;
    }

    /** Checks every active site now, as the dialog's refresh does; each row shows the check until its result comes. */
    private void checkActive() {
        if (running > 0) {
            return;
        }
        List<Provider> sites = new ArrayList<>(Sources.active());
        checking.addAll(sites);
        running++;
        requireActivity().invalidateMenu();
        render();
        disposables.add(SourceHealth.check(prefs, sites, PARALLEL)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    checking.remove(result.provider);
                    render();
                }, e -> checked(), this::checked));
    }

    private void checked() {
        running = Math.max(0, running - 1);
        if (running == 0) {
            checking.clear();
        }
        if (getActivity() != null) {
            getActivity().invalidateMenu();
        }
        render();
    }

    /** Also from a snackbar, which may outlive the screen. */
    private void showProxyDialog() {
        if (isAdded() && getChildFragmentManager().findFragmentByTag(ProxyDialogFragment.TAG) == null) {
            new ProxyDialogFragment().show(getChildFragmentManager(), ProxyDialogFragment.TAG);
        }
    }

    private void manage() {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).showSourcesSettings();
        }
    }

    private void showCard(@NonNull Provider provider) {
        getChildFragmentManager().beginTransaction()
                .replace(R.id.sourceDetail, SourceCardFragment.newInstance(provider), SourceCardFragment.tag(provider))
                .commit();
    }

    @Override
    public void onOpen(@NonNull Provider provider) {
        openCard(provider);
    }

    /** A card: in the pane on a tablet, a screen of its own on a phone. */
    @Override
    public void openCard(@NonNull Provider provider) {
        if (!twoPane) {
            SourceActivity.start(requireContext(), provider);
            return;
        }
        if (provider != selected) {
            selected = provider;
            showCard(provider);
            render();
        }
    }

    /** The row menu, the sources dialog's: up, down, on or off, and the route. */
    @Override
    public void onMenu(@NonNull Provider provider, @NonNull View anchor) {
        List<Provider> all = Sources.all();
        int index = all.indexOf(provider);
        boolean active = index < Sources.activeCount();
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        if (index > 0) {
            menu.getMenu().add(0, MENU_UP, 0, R.string.sources_move_up);
        }
        if (index < all.size() - 1) {
            menu.getMenu().add(0, MENU_DOWN, 0, R.string.sources_move_down);
        }
        if (!active || Sources.activeCount() > 1) {
            menu.getMenu().add(0, MENU_TOGGLE, 0, active ? R.string.sources_deactivate : R.string.sources_activate);
        }
        menu.getMenu().add(0, MENU_ROUTE, 0, AppProxy.isDirect(provider) ? R.string.sources_menu_proxy
                : R.string.sources_menu_direct);
        menu.setOnMenuItemClickListener(item -> {
            act(provider, item.getItemId());
            return true;
        });
        menu.show();
    }

    private void act(@NonNull Provider provider, int action) {
        if (root == null) {
            return;
        }
        int index = Sources.all().indexOf(provider);
        switch (action) {
            case MENU_UP:
                SourceActions.move(root, prefs, provider, index - 1);
                break;
            case MENU_DOWN:
                SourceActions.move(root, prefs, provider, index + 1);
                break;
            case MENU_TOGGLE:
                SourceActions.setActive(root, prefs, provider, !Sources.isActive(provider));
                break;
            default:
                SourceActions.setDirect(root, prefs, provider, !AppProxy.isDirect(provider), this::showProxyDialog);
                break;
        }
        render();
    }
}
