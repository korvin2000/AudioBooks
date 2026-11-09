package com.fanok.audiobooks.fragment;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.MenuProvider;
import androidx.core.widget.NestedScrollView;
import androidx.core.widget.TextViewCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.Lifecycle;
import androidx.preference.PreferenceManager;

import com.bumptech.glide.Glide;
import com.fanok.audiobooks.Consts;
import com.fanok.audiobooks.R;
import com.fanok.audiobooks.activity.MainActivity;
import com.fanok.audiobooks.activity.SourceActivity;
import com.fanok.audiobooks.model.BooksDBModel;
import com.fanok.audiobooks.net.AppProxy;
import com.fanok.audiobooks.net.ProxyFinder;
import com.fanok.audiobooks.region.Region;
import com.fanok.audiobooks.search.Provider;
import com.fanok.audiobooks.search.SourceHealth;
import com.fanok.audiobooks.search.SourceInfo;
import com.fanok.audiobooks.search.SourceLibrary;
import com.fanok.audiobooks.search.SourceProbe;
import com.fanok.audiobooks.search.SourceSnapshot;
import com.fanok.audiobooks.search.Sources;
import com.google.android.material.chip.Chip;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.switchmaterial.SwitchMaterial;

import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The card of one site, laid out like a book's screen: the site's colour in the header, its mobile page where a book has
 * its cover, the facts beside it (books, server, age, response, VPN for the user's region, new releases, mirror), the
 * two actions, then "Ваши настройки" — on or off, the place in the order, the route, the way to the sources dialog —
 * then its books in the user's favorites, history and downloads, and below them what the site is and does. Every change applies at once with an undo ({@link SourceActions}); the card
 * keeps no copy of the state and redraws whenever a preference of the sources changes. Hosted by
 * {@link SourceActivity} on a phone and by the pane of {@link SourcesFragment} on a tablet in landscape.
 */
public class SourceCardFragment extends Fragment {

    /** Whoever can show another site's card in place of this one (the tablet's pane); otherwise a new screen. */
    public interface Host {
        void openCard(@NonNull Provider provider);
    }

    private static final String TAG = "SourceCardFragment";
    private static final String ARG_PROVIDER = "provider";
    /** The header's tint: the site's colour at about a third, fading into the background. */
    private static final int HEAD_ALPHA = 0x57;
    private static final int ICON_DP = 18;
    /** The lists of «В вашей библиотеке» at {@link SourceLibrary#FAVORITES}, {@code HISTORY}, {@code SAVED}. */
    private static final int[] LIBRARY_TABLES = {Consts.TABLE_FAVORITE, Consts.TABLE_HISTORY, Consts.TABLE_SAVED};
    private static final int[] LIBRARY_LABELS = {R.string.sources_library_favorites, R.string.sources_library_history,
            R.string.sources_library_saved};

    private Provider provider;
    private SharedPreferences prefs;
    private final Map<Provider, SourceInfo> infos = new EnumMap<>(Provider.class);
    private boolean hasCover;
    /** On a phone the toolbar names the site only once the head, which names it too, has scrolled away. */
    private boolean titleInToolbar;
    /** A check of this site is under way, on the route {@link #checkRoute} ({@link SourceHealth#viaProxy}). */
    private boolean checking;
    private boolean checkRoute;
    @Nullable
    private Disposable checkRun;
    private final CompositeDisposable disposables = new CompositeDisposable();
    /** The state line of each related site's row, redrawn with the rest. */
    private final Map<Provider, TextView> relatedStates = new EnumMap<>(Provider.class);
    @Nullable
    private View root;
    private float density;
    @ColorInt
    private int secondary;
    @ColorInt
    private int warn;
    @ColorInt
    private int ok;

    private View head;
    private TextView badgeCatalog;
    private TextView badgeNew;
    private TextView factBooks;
    private TextView factServer;
    private TextView factSince;
    private TextView factResponse;
    private View check;
    private View checkingView;
    private TextView factVpn;
    private TextView factUpdates;
    private TextView factMirror;
    private TextView openCatalog;
    private View activeRow;
    private TextView activeSummary;
    private SwitchMaterial activeSwitch;
    private TextView meterCount;
    private TextView meterFree;
    private SourceSegmentsView meter;
    private TextView positionSummary;
    private View positionUp;
    private View positionDown;
    private TextView proxySummary;
    private SwitchMaterial proxySwitch;
    private TextView proxyHint;
    /** «В вашей библиотеке», hidden while the site has no book in the user's lists. */
    private View library;
    private final TextView[] counters = new TextView[LIBRARY_TABLES.length];

    private final Runnable refresh = () -> {
        if (checking && SourceHealth.viaProxy(provider) != checkRoute) {
            startCheck(); // the route changed under the check: its result would tell the old route
        }
        render();
    };
    /** A field: SharedPreferences holds its listeners weakly. */
    private final SharedPreferences.OnSharedPreferenceChangeListener listener = (p, key) -> {
        if (root != null && SourceTexts.watched(key)) {
            // once for a burst of changes, and after every listener of this one ran (AppProxy reads its routes there)
            root.removeCallbacks(refresh);
            root.post(refresh);
        }
    };
    /** The proxy's state the card shows; the search's progress alone does not redraw it. */
    @Nullable
    private SourceTexts.ProxyState proxyState;
    private final ProxyFinder.Listener finderListener = state -> {
        if (root != null && SourceTexts.proxyState(state) != proxyState) {
            render();
        }
    };
    /** The switches act on the user's changes only: never on a restored or a drawn state (onStart draws them). */
    private final CompoundButton.OnCheckedChangeListener activeListener = (button, on) -> {
        if (isResumed()) {
            setActive(on);
        }
    };
    private final CompoundButton.OnCheckedChangeListener proxyListener = (button, on) -> {
        if (isResumed()) {
            setDirect(!on);
        }
    };

    /** The toolbar's actions on the card's own screen; the list's screen has its own. */
    private final MenuProvider menuProvider = new MenuProvider() {
        @Override
        public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
            inflater.inflate(R.menu.source_card, menu);
        }

        @Override
        public boolean onMenuItemSelected(@NonNull MenuItem item) {
            if (item.getItemId() == R.id.sourceMenuSite) {
                SourceTexts.openSite(requireContext(), provider);
            } else if (item.getItemId() == R.id.sourceMenuCheck) {
                check();
            } else if (item.getItemId() == R.id.sourceMenuManage) {
                manage();
            } else {
                return false;
            }
            return true;
        }
    };

    /** What the card reads off the main thread: the facts of every site and whether this one has its cover. */
    private static final class Loaded {
        final Map<Provider, SourceInfo> infos;
        final boolean cover;

        Loaded(@NonNull Map<Provider, SourceInfo> infos, boolean cover) {
            this.infos = infos;
            this.cover = cover;
        }
    }

    @NonNull
    public static SourceCardFragment newInstance(@NonNull Provider provider) {
        SourceCardFragment fragment = new SourceCardFragment();
        Bundle args = new Bundle();
        args.putString(ARG_PROVIDER, provider.name());
        fragment.setArguments(args);
        return fragment;
    }

    /** The fragment tag of {@code provider}'s card. */
    @NonNull
    public static String tag(@NonNull Provider provider) {
        return "source:" + provider.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        provider = Provider.valueOf(requireArguments().getString(ARG_PROVIDER));
        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        getChildFragmentManager().setFragmentResultListener(SourcePositionSheet.RESULT, this,
                (key, result) -> move(result.getInt(SourcePositionSheet.KEY_POSITION)));
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_source_card, container, false);
        root = view;
        Context context = view.getContext();
        density = getResources().getDisplayMetrics().density;
        secondary = ContextCompat.getColor(context, R.color.sources_text_secondary);
        warn = ContextCompat.getColor(context, R.color.sources_warn);
        ok = ContextCompat.getColor(context, R.color.sources_ok);

        head = view.findViewById(R.id.sourceHead);
        badgeCatalog = view.findViewById(R.id.sourceBadgeCatalog);
        badgeNew = view.findViewById(R.id.sourceBadgeNew);
        factBooks = view.findViewById(R.id.sourceFactBooks);
        factServer = view.findViewById(R.id.sourceFactServer);
        factSince = view.findViewById(R.id.sourceFactSince);
        factResponse = view.findViewById(R.id.sourceFactResponse);
        check = view.findViewById(R.id.sourceCheck);
        checkingView = view.findViewById(R.id.sourceChecking);
        factVpn = view.findViewById(R.id.sourceFactVpn);
        factUpdates = view.findViewById(R.id.sourceFactUpdates);
        factMirror = view.findViewById(R.id.sourceFactMirror);
        openCatalog = view.findViewById(R.id.sourceOpenCatalog);
        activeRow = view.findViewById(R.id.sourceActiveRow);
        activeSummary = view.findViewById(R.id.sourceActiveSummary);
        activeSwitch = view.findViewById(R.id.sourceActiveSwitch);
        meterCount = view.findViewById(R.id.sourceMeterCount);
        meterFree = view.findViewById(R.id.sourceMeterFree);
        meter = view.findViewById(R.id.sourceMeter);
        positionSummary = view.findViewById(R.id.sourcePositionSummary);
        positionUp = view.findViewById(R.id.sourcePositionUp);
        positionDown = view.findViewById(R.id.sourcePositionDown);
        proxySummary = view.findViewById(R.id.sourceProxySummary);
        proxySwitch = view.findViewById(R.id.sourceProxySwitch);
        proxyHint = view.findViewById(R.id.sourceProxyHint);

        ((TextView) view.findViewById(R.id.sourceTitle)).setText(provider.title);
        TextView host = view.findViewById(R.id.sourceHost);
        host.setText(provider.host());
        host.setOnClickListener(v -> SourceTexts.openSite(requireContext(), provider));
        view.findViewById(R.id.sourceOpenSite).setOnClickListener(v -> SourceTexts.openSite(requireContext(), provider));
        openCatalog.setOnClickListener(v -> openCatalog());
        check.setOnClickListener(v -> check());
        factResponse.setOnClickListener(v -> check());
        activeSwitch.setOnCheckedChangeListener(activeListener);
        activeRow.setOnClickListener(v -> {
            if (activeSwitch.isEnabled()) {
                activeSwitch.toggle();
            }
        });
        view.findViewById(R.id.sourcePositionRow).setOnClickListener(v -> showPositionSheet());
        positionUp.setOnClickListener(v -> move(Sources.all().indexOf(provider) - 1));
        positionDown.setOnClickListener(v -> move(Sources.all().indexOf(provider) + 1));
        proxySwitch.setOnCheckedChangeListener(proxyListener);
        view.findViewById(R.id.sourceProxyRow).setOnClickListener(v -> proxySwitch.toggle());
        view.findViewById(R.id.sourceManageRow).setOnClickListener(v -> manage());
        proxyHint.setMovementMethod(LinkMovementMethod.getInstance());
        factMirror.setMovementMethod(LinkMovementMethod.getInstance());
        bindFeatures(view);
        ViewGroup slot = view.findViewById(R.id.sourceLibrarySlot);
        inflater.inflate(R.layout.source_card_library, slot, true);
        library = slot;
        counters[SourceLibrary.FAVORITES] = slot.findViewById(R.id.sourceLibraryFavorites);
        counters[SourceLibrary.HISTORY] = slot.findViewById(R.id.sourceLibraryHistory);
        counters[SourceLibrary.SAVED] = slot.findViewById(R.id.sourceLibrarySaved);
        for (int i = 0; i < counters.length; i++) {
            int table = LIBRARY_TABLES[i];
            counters[i].setOnClickListener(v -> openLibrary(table));
        }
        if (requireActivity() instanceof SourceActivity) {
            requireActivity().addMenuProvider(menuProvider, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
        }
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Context app = requireContext().getApplicationContext();
        Provider site = provider;
        disposables.add(SourceTexts.infos(app)
                .map(map -> new Loaded(map, hasAsset(app, site, SourceInfo.COVER)))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(loaded -> {
                    infos.putAll(loaded.infos);
                    hasCover = loaded.cover;
                    bindInfo();
                    render();
                }, e -> Log.w(TAG, "No source facts", e)));
        if (requireActivity() instanceof SourceActivity && view instanceof NestedScrollView) {
            View title = view.findViewById(R.id.sourceTitle);
            titleInToolbar = true; // so the first call clears it
            showTitleInToolbar(false);
            ((NestedScrollView) view).setOnScrollChangeListener((NestedScrollView.OnScrollChangeListener)
                    (v, x, y, oldX, oldY) -> showTitleInToolbar(y > bottomIn(title, v)));
        }
    }

    private void showTitleInToolbar(boolean shown) {
        ActionBar bar = ((AppCompatActivity) requireActivity()).getSupportActionBar();
        if (bar == null || shown == titleInToolbar) {
            return;
        }
        titleInToolbar = shown;
        bar.setTitle(shown ? provider.title : null);
        bar.setSubtitle(shown ? provider.host() : null);
    }

    /** The bottom of {@code view} in the content coordinates of its scrolling ancestor. */
    private static int bottomIn(@NonNull View view, @NonNull View scroll) {
        int y = view.getBottom();
        for (ViewParent parent = view.getParent(); parent instanceof View && parent != scroll;
                parent = parent.getParent()) {
            y += ((View) parent).getTop();
        }
        return y;
    }

    @Override
    public void onStart() {
        super.onStart();
        prefs.registerOnSharedPreferenceChangeListener(listener);
        ProxyFinder.get(requireContext()).addListener(finderListener);
        render(); // a change made while the card was away (the settings, another card)
        loadLibrary(); // and in the lists (a book added, a download removed)
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
    public void onDestroyView() {
        disposables.clear(); // every load and check ends with the views it would draw
        checking = false;
        checkRun = null;
        relatedStates.clear();
        root = null;
        head = null;
        badgeCatalog = null;
        badgeNew = null;
        factBooks = null;
        factServer = null;
        factSince = null;
        factResponse = null;
        check = null;
        checkingView = null;
        factVpn = null;
        factUpdates = null;
        factMirror = null;
        openCatalog = null;
        activeRow = null;
        activeSummary = null;
        activeSwitch = null;
        meterCount = null;
        meterFree = null;
        meter = null;
        positionSummary = null;
        positionUp = null;
        positionDown = null;
        proxySummary = null;
        proxySwitch = null;
        proxyHint = null;
        library = null;
        Arrays.fill(counters, null);
        super.onDestroyView();
    }

    // ---------------------------------------------------------------- what the site is: drawn once its facts are read

    /** The features the app's code tells: the drawer sections the site has, other narrations, the sorts. */
    private void bindFeatures(@NonNull View view) {
        feature(view.findViewById(R.id.sourceFeatureGenres), provider.sections != null);
        feature(view.findViewById(R.id.sourceFeatureAuthors), provider.authors != null);
        feature(view.findViewById(R.id.sourceFeaturePerformers), provider.performers != null);
        feature(view.findViewById(R.id.sourceFeatureReaders), provider.parser.findsOtherReaders());
        TextView sorts = view.findViewById(R.id.sourceSorts);
        String line = SourceTexts.sorts(view.getContext(), provider);
        sorts.setVisibility(line == null ? View.GONE : View.VISIBLE);
        if (line != null) {
            sorts.setText(getString(R.string.sources_sorts, line));
            icon(sorts, R.drawable.ic_sort, secondary);
        }
    }

    private void feature(@NonNull Chip chip, boolean on) {
        chip.setChipIconVisible(on);
        chip.setAlpha(on ? 1 : 0.45f);
        chip.setContentDescription(on ? chip.getText()
                : getString(R.string.sources_feature_missing, chip.getText()));
    }

    private void bindInfo() {
        View view = root;
        if (view == null) {
            return;
        }
        Context context = view.getContext();
        SourceInfo info = infos.get(provider);
        int accent = SourceTexts.accent(context, info);
        head.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{ColorUtils.setAlphaComponent(accent, HEAD_ALPHA), Color.TRANSPARENT}));
        bindCover(view, accent);
        badgeNew.setVisibility(SourceTexts.isNew(info) ? View.VISIBLE : View.GONE);

        TextView tagline = view.findViewById(R.id.sourceTagline);
        show(tagline, info == null ? null : info.tagline);
        String month = info == null ? null : SourceTexts.month(context, info.booksAt);
        String books = info == null || info.books == null ? null : SourceTexts.booksLine(context, info.books);
        show(factBooks, books == null ? null : SourceTexts.join(books, month == null ? null
                : getString(R.string.sources_as_of, month)));
        icon(factBooks, R.drawable.ic_source_books, secondary);
        String server = info == null ? null : SourceTexts.server(context, info);
        factServer.setText(server == null ? getString(R.string.sources_fact_server_unknown)
                : getString(R.string.sources_fact_server, server));
        icon(factServer, R.drawable.ic_web, secondary);
        String year = info == null ? null : SourceTexts.year(info.since);
        factSince.setText(year == null ? getString(R.string.sources_fact_since_unknown)
                : getString(R.string.sources_fact_since, year));
        icon(factSince, R.drawable.ic_calendar_range, secondary);
        show(factUpdates, info == null || info.updates == null ? null
                : getString(R.string.sources_fact_updates, info.updates));
        icon(factUpdates, R.drawable.ic_source_new, secondary);
        bindMirror(context);

        show(view.findViewById(R.id.sourceAbout), info == null ? null : info.about);
        bindFragments(view.findViewById(R.id.sourceFragments), info);
        bindTakedowns(view.findViewById(R.id.sourceTakedowns), info);
        bindPlusMinus(view, info);
        TextView note = view.findViewById(R.id.sourceRegionsNote);
        show(note, month == null ? null : getString(R.string.sources_availability_note, month));
        ((TextView) view.findViewById(R.id.sourceFooter)).setText(month == null ? getString(R.string.sources_footer_plain)
                : getString(R.string.sources_footer, month));
        bindRelated(view, info);
    }

    /** The site's mobile page in the cover's place; without one, its colour with the icon and the title. */
    private void bindCover(@NonNull View view, @ColorInt int accent) {
        ImageView cover = view.findViewById(R.id.sourceCover);
        View fallback = view.findViewById(R.id.sourceCoverFallback);
        cover.setVisibility(hasCover ? View.VISIBLE : View.GONE);
        fallback.setVisibility(hasCover ? View.GONE : View.VISIBLE);
        if (hasCover) {
            cover.setContentDescription(getString(R.string.sources_cover, provider.title));
            Glide.with(cover).load(SourceInfo.uri(provider, SourceInfo.COVER)).into(cover);
            return;
        }
        GradientDrawable shape = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{accent, ColorUtils.blendARGB(accent, Color.BLACK, 0.45f)});
        shape.setCornerRadius(10 * density);
        fallback.setBackground(shape);
        ImageView icon = view.findViewById(R.id.sourceCoverIcon);
        Glide.with(icon).load(SourceInfo.uri(provider, SourceInfo.ICON)).into(icon);
        ((TextView) view.findViewById(R.id.sourceCoverTitle)).setText(provider.title);
    }

    /** "Зеркало: Golosknig": the sites with the same catalog in the app, each opening its card. */
    private void bindMirror(@NonNull Context context) {
        List<Provider> mirrors = provider.mirrors();
        if (mirrors.isEmpty()) {
            factMirror.setVisibility(View.GONE);
            return;
        }
        SpannableStringBuilder text = new SpannableStringBuilder(context.getString(mirrors.size() == 1
                ? R.string.sources_fact_mirror : R.string.sources_fact_mirrors)).append(' ');
        for (int i = 0; i < mirrors.size(); i++) {
            Provider mirror = mirrors.get(i);
            if (i > 0) {
                text.append(", ");
            }
            int start = text.length();
            text.append(mirror.title);
            text.setSpan(new ClickableSpan() {
                @Override
                public void onClick(@NonNull View widget) {
                    open(mirror);
                }
            }, start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        factMirror.setText(text);
        factMirror.setVisibility(View.VISIBLE);
        icon(factMirror, R.drawable.ic_file_copy, secondary);
    }

    private void bindFragments(@NonNull TextView view, @Nullable SourceInfo info) {
        SourceInfo.Fragments fragments = info == null ? null : info.fragments;
        if (fragments == null) {
            view.setVisibility(View.GONE);
            return;
        }
        view.setVisibility(View.VISIBLE);
        switch (fragments) {
            case NO:
                view.setText(R.string.sources_fragments_no);
                icon(view, R.drawable.ic_source_full, ok);
                break;
            case SOME:
                view.setText(R.string.sources_fragments_some);
                icon(view, R.drawable.ic_content_cut, warn);
                break;
            default:
                view.setText(R.string.sources_fragments_yes);
                icon(view, R.drawable.ic_content_cut, warn);
                break;
        }
    }

    private void bindTakedowns(@NonNull TextView view, @Nullable SourceInfo info) {
        Boolean takedowns = info == null ? null : info.takedowns;
        view.setVisibility(takedowns == null ? View.GONE : View.VISIBLE);
        if (takedowns != null) {
            view.setText(takedowns ? R.string.sources_takedowns_yes : R.string.sources_takedowns_no);
            icon(view, R.drawable.ic_source_gavel, takedowns ? warn : ok);
        }
    }

    /** The pluses, then the minuses: short phrases with a sign before each. */
    private void bindPlusMinus(@NonNull View view, @Nullable SourceInfo info) {
        LinearLayout pluses = view.findViewById(R.id.sourcePluses);
        LinearLayout minuses = view.findViewById(R.id.sourceMinuses);
        pluses.removeAllViews();
        minuses.removeAllViews();
        List<String> plus = info == null ? Collections.<String>emptyList() : info.pluses;
        List<String> minus = info == null ? Collections.<String>emptyList() : info.minuses;
        view.findViewById(R.id.sourcePlusMinusTitle).setVisibility(plus.isEmpty() && minus.isEmpty()
                ? View.GONE : View.VISIBLE);
        for (String line : plus) {
            pluses.addView(point(pluses, line, R.drawable.ic_source_plus, ok));
        }
        for (String line : minus) {
            minuses.addView(point(minuses, line, R.drawable.ic_source_minus, ContextCompat.getColor(
                    view.getContext(), R.color.sources_bad)));
        }
    }

    @NonNull
    private TextView point(@NonNull ViewGroup parent, @NonNull String line, @DrawableRes int icon,
            @ColorInt int color) {
        TextView view = (TextView) LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_source_point, parent, false);
        view.setText(line);
        icon(view, icon, color);
        return view;
    }

    /** The related sites: the app's mirrors (the same catalog) first, then the editors' (the same new releases). */
    private void bindRelated(@NonNull View view, @Nullable SourceInfo info) {
        LinearLayout list = view.findViewById(R.id.sourceRelated);
        list.removeAllViews();
        relatedStates.clear();
        List<Provider> sites = new ArrayList<>(provider.mirrors());
        if (info != null) {
            for (Provider site : info.related) {
                if (site != provider && !sites.contains(site)) {
                    sites.add(site);
                }
            }
        }
        view.findViewById(R.id.sourceRelatedTitle).setVisibility(sites.isEmpty() ? View.GONE : View.VISIBLE);
        LayoutInflater inflater = LayoutInflater.from(view.getContext());
        for (Provider site : sites) {
            View row = inflater.inflate(R.layout.item_source_related, list, false);
            TextView letter = row.findViewById(R.id.sourceRelatedLetter);
            letter.setText(SourceTexts.letter(site));
            letter.setBackground(SourceTexts.rounded(SourceTexts.accent(view.getContext(), infos.get(site)),
                    8 * density));
            ImageView icon = row.findViewById(R.id.sourceRelatedIcon);
            Glide.with(icon).load(SourceInfo.uri(site, SourceInfo.ICON)).into(icon);
            ((TextView) row.findViewById(R.id.sourceRelatedName)).setText(site.title);
            ((TextView) row.findViewById(R.id.sourceRelatedKind)).setText(site.isMirrorOf(provider)
                    ? R.string.sources_related_mirror : R.string.sources_related_same_new);
            relatedStates.put(site, row.findViewById(R.id.sourceRelatedState));
            row.setOnClickListener(v -> open(site));
            list.addView(row);
        }
    }

    // ---------------------------------------------------------------- the live state: drawn at every change

    private void render() {
        View view = root;
        if (view == null || !isAdded()) {
            return;
        }
        Context context = view.getContext();
        SourceInfo info = infos.get(provider);
        Region region = SourceTexts.region(context);
        List<Provider> order = Sources.all();
        int index = order.indexOf(provider);
        int active = Sources.activeCount();
        boolean isActive = index < active;

        badgeCatalog.setVisibility(provider == Provider.current() ? View.VISIBLE : View.GONE);
        renderResponse(context, SourceHealth.last(prefs).get(provider));
        renderVpn(context, info, region);
        openCatalog.setText(isActive ? R.string.sources_open_catalog : R.string.sources_enable_and_open);
        renderActive(order, index, active);
        renderPosition(order, index);
        renderProxy(context, info, region);
        renderRegions(view, info, region);
        for (Map.Entry<Provider, TextView> entry : relatedStates.entrySet()) {
            int position = order.indexOf(entry.getKey()) + 1;
            entry.getValue().setText(getString(position <= active ? R.string.sources_related_active
                    : R.string.sources_related_inactive, position));
        }
    }

    private void renderResponse(@NonNull Context context, @Nullable SourceProbe.Result result) {
        check.setVisibility(checking ? View.INVISIBLE : View.VISIBLE);
        checkingView.setVisibility(checking ? View.VISIBLE : View.GONE);
        String line = SourceTexts.checkLine(context, result, System.currentTimeMillis());
        factResponse.setText(checking ? getString(R.string.sources_checking) : line == null
                ? getString(R.string.sources_fact_never) : getString(R.string.sources_fact_response, line));
        int color = SourceTexts.statusColor(result);
        icon(factResponse, R.drawable.ic_speedometer, checking || color == 0 ? secondary : color);
    }

    /** "Без VPN · Украина", "Нужен прокси · Россия", or no facts: the blocks for the user's region. */
    private void renderVpn(@NonNull Context context, @Nullable SourceInfo info, @Nullable Region region) {
        Boolean blocked = info == null ? null : info.blockedIn(region);
        String name = SourceTexts.regionName(context, region);
        if (blocked == null) {
            factVpn.setText(R.string.sources_vpn_unknown);
            factVpn.setTextColor(MaterialColors.getColor(factVpn, R.attr.colorPrimaryText));
            icon(factVpn, R.drawable.ic_source_shield_outline, secondary);
        } else {
            factVpn.setText(getString(blocked ? R.string.sources_vpn_needed : R.string.sources_vpn_ok, name));
            factVpn.setTextColor(blocked ? warn : ok);
            icon(factVpn, blocked ? R.drawable.ic_source_shield_alert : R.drawable.ic_shield_check, blocked ? warn : ok);
        }
    }

    /**
     * The switch and its consequences, said before the tap: the place an enabled site takes, the site that leaves at
     * the limit, why the only active site cannot be switched off. The meter shows the count and this site's cell.
     */
    private void renderActive(@NonNull List<Provider> order, int index, int active) {
        boolean isActive = index < active;
        int limit = Math.min(Sources.maxActive(), order.size());
        boolean only = isActive && active == 1;
        setChecked(activeSwitch, isActive, activeListener);
        activeSwitch.setEnabled(!only);
        boolean atLimit = !isActive && active >= limit;
        String summary;
        if (only) {
            summary = getString(R.string.sources_active_only);
        } else if (isActive) {
            summary = getString(R.string.sources_active_on, index + 1, active);
        } else if (!atLimit) {
            summary = getString(R.string.sources_active_off, active + 1);
        } else {
            summary = getString(R.string.sources_active_limit, limit, order.get(active - 1).title);
        }
        activeSummary.setText(summary);
        activeSummary.setTextColor(atLimit ? warn : secondary);

        int accent = ContextCompat.getColor(requireContext(), R.color.sources_accent);
        int track = ContextCompat.getColor(requireContext(), R.color.sources_track);
        int[] cells = new int[limit];
        for (int i = 0; i < cells.length; i++) {
            cells[i] = i >= active ? track : i == index ? accent : ColorUtils.setAlphaComponent(accent, 0x99);
        }
        meter.setCells(cells, -1, isActive ? index : -1);
        meterCount.setText(getString(R.string.sources_meter_count, active, limit));
        meter.setContentDescription(meterCount.getText());
        meterFree.setText(active >= limit ? getString(R.string.sources_section_limit)
                : getString(R.string.sources_meter_free, limit - active));
        meterFree.setTextColor(active >= limit ? warn : secondary);
    }

    private void renderPosition(@NonNull List<Provider> order, int index) {
        positionSummary.setText(getString(R.string.sources_position_value, index + 1, order.size()));
        enable(positionUp, index > 0);
        enable(positionDown, index < order.size() - 1);
        positionUp.setContentDescription(getString(R.string.sources_position_up, Math.max(1, index)));
        positionDown.setContentDescription(getString(R.string.sources_position_down,
                Math.min(order.size(), index + 2)));
    }

    /**
     * The route switch; under it, why "через прокси" goes direct now ({@link SourceTexts#proxyState}) with the way out:
     * the proxy switched off or not set up in the settings, or no working proxy found ("Найти заново"); or the site
     * going direct where the user's region needs the proxy.
     */
    private void renderProxy(@NonNull Context context, @Nullable SourceInfo info, @Nullable Region region) {
        boolean direct = AppProxy.isDirect(provider);
        proxyState = SourceTexts.proxyState(context);
        String reason = SourceTexts.proxyReason(context, proxyState);
        setChecked(proxySwitch, !direct, proxyListener);
        proxySummary.setText(direct ? getString(R.string.sources_proxy_direct) : reason != null
                ? getString(R.string.sources_proxy_idle, reason) : getString(R.string.sources_proxy_on));
        boolean none = proxyState == SourceTexts.ProxyState.NONE;
        int action = proxyState == SourceTexts.ProxyState.OFF ? R.string.sources_proxy_enable
                : proxyState == SourceTexts.ProxyState.UNSET ? R.string.sources_proxy_setup
                : none ? R.string.proxy_rescan : 0; // a search, or no network: nothing to do
        if (!direct && action != 0) {
            hint(getString(R.string.sources_proxy_idle_hint, SourceTexts.sentence(reason)), action,
                    none ? R.drawable.bg_source_hint_warn : R.drawable.bg_source_hint,
                    none ? R.drawable.ic_source_alert : R.drawable.ic_source_info, none ? warn : secondary,
                    none ? this::rescanProxies : this::showProxyDialog);
        } else if (direct && info != null && Boolean.TRUE.equals(info.blockedIn(region))) {
            hint(getString(R.string.sources_proxy_needed_hint, SourceTexts.regionName(context, region)),
                    R.string.sources_proxy_needed_action, R.drawable.bg_source_hint_warn, R.drawable.ic_source_alert,
                    warn, () -> setDirect(false));
        } else {
            proxyHint.setVisibility(View.GONE);
        }
    }

    /** {@code text} and then {@code link}, a tappable word that runs {@code action}, in a tinted box. */
    private void hint(@NonNull String text, int link, @DrawableRes int background, @DrawableRes int icon,
            @ColorInt int iconColor, @NonNull Runnable action) {
        SpannableStringBuilder line = new SpannableStringBuilder(text).append(' ');
        int start = line.length();
        line.append(getString(link));
        line.setSpan(new ClickableSpan() {
            @Override
            public void onClick(@NonNull View widget) {
                action.run();
            }
        }, start, line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        proxyHint.setText(line);
        proxyHint.setBackgroundResource(background);
        icon(proxyHint, icon, iconColor);
        proxyHint.setVisibility(View.VISIBLE);
    }

    /** Россия, Украина, Запад: where the site works without a proxy; the user's region marked. */
    private void renderRegions(@NonNull View view, @Nullable SourceInfo info, @Nullable Region mine) {
        tile(view.findViewById(R.id.sourceRegionRussia), Region.RUSSIA, info, mine);
        tile(view.findViewById(R.id.sourceRegionUkraine), Region.UKRAINE, info, mine);
        tile(view.findViewById(R.id.sourceRegionWest), Region.WEST, info, mine);
    }

    private void tile(@NonNull View tile, @NonNull Region region, @Nullable SourceInfo info, @Nullable Region mine) {
        Context context = tile.getContext();
        TextView name = tile.findViewById(R.id.sourceRegionName);
        TextView state = tile.findViewById(R.id.sourceRegionState);
        boolean isMine = region == mine;
        name.setText(SourceTexts.regionName(context, region));
        if (isMine) {
            icon(name, R.drawable.ic_source_marker, ContextCompat.getColor(context, R.color.sources_accent));
        } else {
            TextViewCompat.setCompoundDrawablesRelative(name, null, null, null, null);
        }
        Boolean blocked = info == null ? null : info.blockedIn(region);
        state.setText(blocked == null ? R.string.sources_availability_unknown
                : blocked ? R.string.sources_availability_proxy : R.string.sources_availability_ok);
        state.setTextColor(blocked == null ? secondary : blocked ? warn : ok);
        tile.setBackgroundResource(isMine ? R.drawable.bg_source_tile_mine : R.drawable.bg_source_tile);
        tile.setContentDescription(SourceTexts.spoken(name.getText().toString(), state.getText().toString(),
                isMine ? getString(R.string.sources_region_mine) : null));
    }

    // ---------------------------------------------------------------- the user's library: read at every return

    /** Counts this site's books in the favorites, the history and the downloads, off the main thread. */
    private void loadLibrary() {
        Context app = requireContext().getApplicationContext();
        Provider site = provider;
        disposables.add(Single.fromCallable(() -> {
                    BooksDBModel model = new BooksDBModel(app);
                    try {
                        int[] count = SourceLibrary.count(model.getUrls(Consts.TABLE_FAVORITE),
                                model.getUrls(Consts.TABLE_HISTORY), model.getUrls(Consts.TABLE_SAVED)).get(site);
                        return count == null ? new int[LIBRARY_TABLES.length] : count;
                    } finally {
                        model.closeDB();
                    }
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::bindLibrary, e -> Log.w(TAG, "No library counts", e)));
    }

    /** "Избранное 5 · История 23 · Загружено 2": a zero dimmed and inert, the section hidden when all are zero. */
    private void bindLibrary(@NonNull int[] count) {
        if (root == null) {
            return;
        }
        boolean any = false;
        for (int i = 0; i < counters.length; i++) {
            counters[i].setText(getString(LIBRARY_LABELS[i], count[i]));
            enable(counters[i], count[i] > 0);
            any |= count[i] > 0;
        }
        library.setVisibility(any ? View.VISIBLE : View.GONE);
    }

    /** The list {@code table} narrowed to this site on the main screen, as the card's other links open it. */
    private void openLibrary(int table) {
        FragmentActivity activity = requireActivity();
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).showLibrary(provider, table);
        } else {
            MainActivity.startLibrary(activity, provider, table);
        }
    }

    // ---------------------------------------------------------------- actions

    private void setActive(boolean on) {
        if (root != null && on != Sources.isActive(provider)) {
            SourceActions.setActive(root, prefs, provider, on);
        }
        render();
    }

    private void move(int position) {
        if (root != null) {
            SourceActions.move(root, prefs, provider, position);
        }
        render();
    }

    /** The other route; an active site, or one under a check, is checked again on it, as the sources dialog does. */
    private void setDirect(boolean direct) {
        if (root != null && direct != AppProxy.isDirect(provider)) {
            SourceActions.setDirect(root, prefs, provider, direct, this::showProxyDialog);
            if (checking || Sources.isActive(provider)) {
                startCheck();
            }
        }
        render();
    }

    /** Checks this site now, through its route, unless a check is under way; the result is kept and shown at once. */
    private void check() {
        if (!checking) {
            startCheck();
        }
    }

    /** A new check of this site on its current route, in place of one under way. */
    private void startCheck() {
        if (root == null) {
            return;
        }
        if (checkRun != null) {
            disposables.remove(checkRun); // disposes it
        }
        checking = true;
        checkRoute = SourceHealth.viaProxy(provider);
        render();
        checkRun = SourceHealth.check(prefs, Collections.singletonList(provider), 1)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> { }, e -> checked(), this::checked);
        disposables.add(checkRun);
    }

    private void checked() {
        checking = false;
        checkRun = null;
        render();
    }

    /**
     * Makes the site the catalog source (switched on first when it is inactive) and opens its "Аудиокниги" on the main
     * screen, which offers the undo there.
     */
    private void openCatalog() {
        Context context = requireContext();
        SourceSnapshot before = SourceSnapshot.take(prefs);
        String text = SourceActions.makeCatalog(context, prefs, provider, before);
        SourceSnapshot after = SourceSnapshot.take(prefs);
        FragmentActivity activity = requireActivity();
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).openCatalog(text == null ? null : before, after, text);
        } else {
            MainActivity.startCatalog(activity, text == null ? null : before.encode(), after.encode(), text);
        }
    }

    /** Settings → "Источники и сеть" with the sources dialog open. */
    private void manage() {
        FragmentActivity activity = requireActivity();
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).showSourcesSettings();
        } else {
            MainActivity.startSourcesSettings(activity);
        }
    }

    private void showPositionSheet() {
        if (getChildFragmentManager().findFragmentByTag(SourcePositionSheet.TAG) == null) {
            SourcePositionSheet.newInstance(provider).show(getChildFragmentManager(), SourcePositionSheet.TAG);
        }
    }

    /** Also from a snackbar, which may outlive the card. */
    private void showProxyDialog() {
        if (isAdded() && getChildFragmentManager().findFragmentByTag(ProxyDialogFragment.TAG) == null) {
            new ProxyDialogFragment().show(getChildFragmentManager(), ProxyDialogFragment.TAG);
        }
    }

    /** "Найти заново": a new search of the automatic mode, in the proxy dialog that shows it run. */
    private void rescanProxies() {
        ProxyFinder.get(requireContext()).rescan();
        showProxyDialog();
    }

    /** Another site's card: in place of this one where the host can, else on a screen of its own. */
    private void open(@NonNull Provider site) {
        Host host = getParentFragment() instanceof Host ? (Host) getParentFragment()
                : getActivity() instanceof Host ? (Host) getActivity() : null;
        if (host != null) {
            host.openCard(site);
        } else {
            SourceActivity.start(requireContext(), site);
        }
    }

    // ---------------------------------------------------------------- small view helpers

    /** {@code icon}, {@value #ICON_DP} dp, before the text, in {@code color}. */
    private void icon(@NonNull TextView view, @DrawableRes int icon, @ColorInt int color) {
        Drawable drawable = AppCompatResources.getDrawable(view.getContext(), icon);
        if (drawable == null) {
            return;
        }
        drawable = drawable.mutate();
        int size = (int) (ICON_DP * density);
        drawable.setBounds(0, 0, size, size);
        TextViewCompat.setCompoundDrawablesRelative(view, drawable, null, null, null);
        TextViewCompat.setCompoundDrawableTintList(view, ColorStateList.valueOf(color));
    }

    private static void show(@NonNull TextView view, @Nullable CharSequence text) {
        view.setText(text);
        view.setVisibility(text == null || text.length() == 0 ? View.GONE : View.VISIBLE);
    }

    private static void setChecked(@NonNull CompoundButton button, boolean checked,
            @NonNull CompoundButton.OnCheckedChangeListener listener) {
        button.setOnCheckedChangeListener(null);
        button.setChecked(checked);
        button.setOnCheckedChangeListener(listener);
    }

    private static void enable(@NonNull View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1 : 0.38f);
    }

    private static boolean hasAsset(@NonNull Context context, @NonNull Provider provider, @NonNull String name) {
        try (InputStream ignored = context.getAssets().open(SourceInfo.dir(provider) + "/" + name)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
