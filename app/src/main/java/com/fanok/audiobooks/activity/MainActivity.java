package com.fanok.audiobooks.activity;

import static com.fanok.audiobooks.Consts.handleUserInput;

import com.fanok.audiobooks.FontScale;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.Log;
import androidx.media3.exoplayer.offline.DownloadService;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;
import com.arellomobile.mvp.MvpAppCompatActivity;
import com.arellomobile.mvp.presenter.InjectPresenter;
import com.arellomobile.mvp.presenter.ProvidePresenter;
import com.fanok.audiobooks.AppThemes;
import com.fanok.audiobooks.BuildConfig;
import com.fanok.audiobooks.Consts;
import com.fanok.audiobooks.R;
import com.fanok.audiobooks.adapter.ListFocus;
import com.fanok.audiobooks.fragment.BooksFragment;
import com.fanok.audiobooks.fragment.FavoriteFragment;
import com.fanok.audiobooks.fragment.ProxyDialogFragment;
import com.fanok.audiobooks.fragment.SettingsFragment;
import com.fanok.audiobooks.fragment.SourcesDialogFragment;
import com.fanok.audiobooks.interface_pacatge.main.MainView;
import com.fanok.audiobooks.net.AppProxy;
import com.fanok.audiobooks.net.ProxyFinder;
import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.pojo.StorageUtil;
import com.fanok.audiobooks.presenter.MainPresenter;
import com.fanok.audiobooks.service.ExoDownloadService;
import com.fanok.audiobooks.service.MediaPlayerService;
import com.fanok.audiobooks.region.AppRegion;
import com.fanok.audiobooks.search.Provider;
import com.fanok.audiobooks.search.SourceSnapshot;
import com.fanok.audiobooks.search.Sources;
import com.fanok.audiobooks.update.Updater;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;


public class MainActivity extends MvpAppCompatActivity
        implements NavigationView.OnNavigationItemSelectedListener, MainView {

    /** The screen at the chosen font scale (FontScale). */
    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(FontScale.wrap(newBase));
    }
    private static final String TAG = "MainActivity";
    private static final String EXSTRA_FRAGMENT = "startFragment";
    private static final String EXSTRA_URL = "url";
    /** Sent by the source screens ({@link #startSourcesSettings}, {@link #startCatalog}): what to open on arrival. */
    private static final String EXTRA_SOURCES = "sources";
    private static final String SOURCES_SETTINGS = "settings";
    private static final String SOURCES_CATALOG = "catalog";
    private static final String SOURCES_LIBRARY = "library";
    /** With {@link #SOURCES_LIBRARY}: the list ({@code Consts.TABLE_*}) and the {@code sourceId} it is narrowed to. */
    private static final String EXTRA_TABLE = "sourcesTable";
    private static final String EXTRA_SOURCE_ID = "sourcesSourceId";
    /** The setup before and after a change of the catalog source ({@link SourceSnapshot#encode}) and what changed. */
    private static final String EXTRA_UNDO = "sourcesUndo";
    private static final String EXTRA_UNDO_AFTER = "sourcesUndoAfter";
    private static final String EXTRA_UNDO_TEXT = "sourcesUndoText";
    private static final int SNACKBAR_MS = 6000;
    /** In {@link #preferences}: the notification permission was put to the user once (either answer). */
    private static final String PREF_NOTIFICATION_ASKED = "notification_asked";
    private static boolean closeApp = false;
    /** True on a fresh creation only: a restored activity keeps its fragments and has had its first-launch dialogs. */
    private boolean freshStart = false;



    public static boolean isCloseApp() {
        return closeApp;
    }

    private ArrayList<TextView> mTextViewArrayList;

    /** The side-menu entry chosen last (TV) and the screen it opened, until that screen's list takes the focus. */
    private View mSectionFrom;

    private Fragment mSectionFor;

    @InjectPresenter
    MainPresenter mPresenter;

    @ProvidePresenter
    MainPresenter provideBookPresenter() {
        return new MainPresenter(getApplicationContext());
    }

    private NavigationView navigationView;
    /** A request of the source screens, handled once the activity is resumed (fragments can be shown). */
    private Intent pendingSources;
    private final DialogQueue dialogs = new DialogQueue();
    private boolean updateQueued;
    /** The run-out of automatic proxies ({@link ProxyFinder.State#exhaustedAt}) whose new-search offer was answered. */
    private static long proxyOfferedFor;
    /** That offer waits in {@link #dialogs}. */
    private boolean proxyOfferQueued;
    private final ProxyFinder.Listener proxyListener = this::offerProxyRescan;
    private Runnable permissionDone;

    private SharedPreferences preferences;

    private final ActivityResultLauncher<String> notificationPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (permissionDone != null) {
                    Runnable done = permissionDone;
                    permissionDone = null;
                    done.run();
                }
            });


    public static void setCloseApp(boolean closeApp) {
        MainActivity.closeApp = closeApp;
    }

    public NavigationView getNavigationView() {
        return navigationView;
    }

    public static void startMainActivity(@NonNull Context context, int fragment, String url) {
        if (url == null || url.isEmpty()) {
            startMainActivity(context, fragment);
            return;
        }

        Intent intent = new Intent(context, MainActivity.class);
        intent.putExtra(EXSTRA_FRAGMENT, fragment);
        intent.putExtra(EXSTRA_URL, url);
        context.startActivity(intent);
    }

    public static void startMainActivity(@NonNull Context context, int fragment) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.putExtra(EXSTRA_FRAGMENT, fragment);
        context.startActivity(intent);
    }

    /**
     * The app anew on the catalog source's {@code section} ({@code Consts.FRAGMENT_*}: "Аудиокниги", or the list the
     * source was switched from), in place of {@code activity}'s task: after the source was switched. Not a launch, so
     * «Слушать последнюю книгу» does not start the book again.
     */
    public static void restartOnCatalog(@NonNull Activity activity, int section) {
        activity.startActivity(new Intent(activity, MainActivity.class)
                .putExtra(EXSTRA_FRAGMENT, section)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        activity.finish();
    }

    public ArrayList<TextView> getTextViewArrayList() {
        return mTextViewArrayList;
    }

    /** Settings → "Источники и сеть" with the sources dialog open, from a screen above this one. */
    public static void startSourcesSettings(@NonNull Context context) {
        context.startActivity(sourcesIntent(context, SOURCES_SETTINGS));
    }

    /**
     * The catalog source's "Аудиокниги", from a screen above this one, after the source changed; {@code undo} and
     * {@code after} (the setup before and after it, {@link SourceSnapshot#encode}) and {@code text} make the snackbar
     * that offers to put the change back.
     */
    public static void startCatalog(@NonNull Context context, @Nullable String undo, @Nullable String after,
            @Nullable String text) {
        context.startActivity(sourcesIntent(context, SOURCES_CATALOG).putExtra(EXTRA_UNDO, undo)
                .putExtra(EXTRA_UNDO_AFTER, after).putExtra(EXTRA_UNDO_TEXT, text));
    }

    /**
     * The favorites, the history or the downloads ({@code Consts.TABLE_*}) narrowed to {@code provider}, from its card
     * on a screen of its own: the main screen, as the card's other links open it; Back follows its own screens.
     */
    public static void startLibrary(@NonNull Context context, @NonNull Provider provider, int table) {
        context.startActivity(sourcesIntent(context, SOURCES_LIBRARY).putExtra(EXTRA_TABLE, table)
                .putExtra(EXTRA_SOURCE_ID, provider.sourceId));
    }

    @NonNull
    private static Intent sourcesIntent(@NonNull Context context, @NonNull String what) {
        return new Intent(context, MainActivity.class).putExtra(EXTRA_SOURCES, what)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.hasExtra(EXTRA_SOURCES)) {
            pendingSources = intent;
        }
    }

    /** A request of the source screens, once: the extras leave the intent, so a recreation does not repeat it. */
    private void handleSources(@NonNull Intent intent) {
        String what = intent.getStringExtra(EXTRA_SOURCES);
        String undo = intent.getStringExtra(EXTRA_UNDO);
        String after = intent.getStringExtra(EXTRA_UNDO_AFTER);
        String text = intent.getStringExtra(EXTRA_UNDO_TEXT);
        Provider library = Provider.ofSource(intent.getIntExtra(EXTRA_SOURCE_ID, -1));
        int table = intent.getIntExtra(EXTRA_TABLE, Consts.TABLE_FAVORITE);
        intent.removeExtra(EXTRA_SOURCES);
        intent.removeExtra(EXTRA_UNDO);
        intent.removeExtra(EXTRA_UNDO_AFTER);
        intent.removeExtra(EXTRA_UNDO_TEXT);
        intent.removeExtra(EXTRA_SOURCE_ID);
        intent.removeExtra(EXTRA_TABLE);
        if (SOURCES_SETTINGS.equals(what)) {
            showSourcesSettings();
        } else if (SOURCES_CATALOG.equals(what)) {
            openCatalog(SourceSnapshot.decode(undo), SourceSnapshot.decode(after), text);
        } else if (SOURCES_LIBRARY.equals(what) && library != null) {
            showLibrary(library, table);
        }
    }

    /**
     * The favorites, the history or the downloads ({@code Consts.TABLE_*}) narrowed to {@code provider}, above the
     * screen on top; Back returns to it. A tag of each list and site, so another one is never taken for it.
     */
    public void showLibrary(@NonNull Provider provider, int table) {
        showFragment(FavoriteFragment.newInstance(table, provider), "library:" + table + ":" + provider.name());
    }

    /** Settings → "Источники и сеть" with the sources dialog open; Back returns here. */
    public void showSourcesSettings() {
        showSourcesSettings(false);
    }

    /** {@link #showSourcesSettings()}; with {@code autoRank} the dialog starts "Автоподбор" as it opens. */
    public void showSourcesSettings(boolean autoRank) {
        showFragment(SettingsFragment.withSourcesDialog(autoRank), "settingsSources");
    }

    /**
     * Opens the catalog source's "Аудиокниги" after the source screens changed it; with {@code before}, {@code after}
     * and {@code text} a snackbar says what changed and its "Отменить" restores {@code before} and opens that catalog
     * again, unless the setup is no longer {@code after} (changed since: kept, and said so).
     */
    public void openCatalog(@Nullable SourceSnapshot before, @Nullable SourceSnapshot after, @Nullable String text) {
        mPresenter.onItemSelected(R.id.nav_audiobooks);
        View anchor = findViewById(R.id.container);
        if (before == null || after == null || text == null || anchor == null) {
            return;
        }
        Snackbar.make(anchor, text, SNACKBAR_MS)
                .setAction(R.string.sources_undo, v -> {
                    if (before.restoreIf(PreferenceManager.getDefaultSharedPreferences(this), after)) {
                        mPresenter.onItemSelected(R.id.nav_audiobooks);
                    } else {
                        Toast.makeText(this, R.string.sources_undo_stale, Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    /**
     * For the screen of the side-menu entry chosen last (TV), when its rows come: true once, if that entry still holds
     * the focus (the remote has not moved on meanwhile), and the screen's first row should take it.
     */
    public boolean takeSectionFocus(@NonNull Fragment screen) {
        if (screen != mSectionFor) {
            return false;
        }
        View from = mSectionFrom;
        mSectionFrom = null;
        mSectionFor = null;
        return from != null && from.isFocused();
    }

    /** Marks the permanent drawer's entry {@code layoutId} (a tablet in landscape) as the section on screen. */
    public void highlightNav(int layoutId) {
        if (mTextViewArrayList == null) {
            return;
        }
        TypedValue selected = new TypedValue();
        getTheme().resolveAttribute(R.attr.mySelectableItemBackground, selected, true);
        TypedValue plain = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, plain, true);
        for (TextView view : mTextViewArrayList) {
            view.setBackgroundResource(view.getId() == layoutId ? selected.resourceId : plain.resourceId);
        }
    }

    /** The "Источники" entry names the catalog source, as it is when the drawer starts to open. */
    private void updateSourcesEntry() {
        Provider catalog = Provider.current();
        MenuItem item = navigationView == null ? null : navigationView.getMenu().findItem(R.id.nav_sources);
        View action = item == null ? null : item.getActionView();
        TextView badge = action == null ? null : action.findViewById(R.id.navSourcesCatalog);
        if (badge != null) {
            badge.setText(catalog == null ? null : catalog.title);
        }
    }

    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    @Override
    protected void onStart() {
        super.onStart();
        ProxyFinder.get(this).addListener(proxyListener); // onResume looks at the state of now
        if (!freshStart) {
            return;
        }
        freshStart = false;
        try {
            // a new process has its download manager paused until the service is created: what a restart
            // interrupted goes on (a pause of the user's is a stop reason of its own and stays)
            DownloadService.start(this, ExoDownloadService.class);
        } catch (IllegalStateException e) {
            Log.w(TAG, "downloads not resumed", e);
        }

        Intent intent = getIntent();
        int fragment = intent.getIntExtra(EXSTRA_FRAGMENT, -1);
        String url = intent.getStringExtra(EXSTRA_URL);
        if (url != null && !url.isEmpty() && fragment != -1) {
            mPresenter.startFragment(fragment, url);
        } else if (fragment != -1) {
            // a section asked for by name (restartOnCatalog after a source switch): it, never the last book over it
            mPresenter.startFragment(fragment, true);
        } else {
            SharedPreferences pref = PreferenceManager
                    .getDefaultSharedPreferences(this);
            fragment = Integer.parseInt(pref.getString("pref_start_screen", "0"));
            boolean notificationClick = intent.getBooleanExtra("notificationClick", false);
            mPresenter.startFragment(fragment, notificationClick);
            if (!notificationClick && pref.getBoolean("pref_play_last_book", false)) {
                playLastBook();
            }
        }
        if (intent.hasExtra(EXTRA_SOURCES)) {
            pendingSources = intent; // above the start screen, so Back leads to it
        }

        // one decision at a time, each with its reason (UI-18); parental control is in the settings, not asked here
        if (!preferences.getBoolean("first", false)) {
            dialogs.add(this::showPrivacyDialog);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && !preferences.getBoolean(PREF_NOTIFICATION_ASKED, false)
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            dialogs.add(this::showNotificationRationale);
        }
        if (shouldAskBatteryOptimization()) {
            dialogs.add(this::showBatteryDialog);
        }
        if (!PreferenceManager.getDefaultSharedPreferences(this).contains(Sources.PREF_RANKED_AT)) {
            dialogs.add(this::showAutoRankDialog);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateNowPlayingEntry();
        updateSourcesEntry();
        if (pendingSources != null) {
            Intent sources = pendingSources;
            pendingSources = null;
            handleSources(sources);
        }
        Updater.setListener(this::offerUpdate);
        offerUpdate();
        offerProxyRescan(ProxyFinder.get(this).state());
    }

    @Override
    protected void onPause() {
        Updater.setListener(null);
        super.onPause();
    }

    @Override
    protected void onStop() {
        ProxyFinder.get(this).removeListener(proxyListener);
        super.onStop();
    }

    /**
     * The automatic proxies all stopped answering ({@link ProxyFinder.State#exhaustedAt}), no search found another
     * ({@link ProxyFinder.Phase#FAILED}: one under way is waited for) and a source takes the proxy: a new search is
     * offered, once for that run-out, after the dialogs queued before it. Meanwhile such sources go direct.
     */
    private void offerProxyRescan(@NonNull ProxyFinder.State state) {
        if (!proxyOfferQueued && rescanDue(state)) {
            proxyOfferQueued = true;
            dialogs.add(this::showProxyRescanDialog);
        }
    }

    /** {@code state} is a run-out not offered yet, in the automatic mode, with an active source through the proxy. */
    private static boolean rescanDue(@NonNull ProxyFinder.State state) {
        if (state.phase != ProxyFinder.Phase.FAILED || state.current != null || state.exhaustedAt == 0
                || state.exhaustedAt == proxyOfferedFor || !AppProxy.MODE_AUTO.equals(AppProxy.mode())) {
            return false;
        }
        for (Provider provider : Sources.active()) {
            if (!AppProxy.isDirect(provider)) {
                return true;
            }
        }
        return false;
    }

    /**
     * "Прокси не отвечают": "Найти заново" starts a search and opens the proxy dialog, which shows it run; the next
     * dialog waits for that one to close. Only an answer (a cancel too) settles the offer: a dialog closed by
     * recreation is offered again.
     */
    private void showProxyRescanDialog(@NonNull Runnable done) {
        proxyOfferQueued = false;
        ProxyFinder.State state = ProxyFinder.get(this).state();
        if (isFinishing() || isDestroyed() || !rescanDue(state)) {
            done.run(); // the state moved on while it waited
            return;
        }
        long offered = state.exhaustedAt;
        boolean[] accepted = new boolean[1];
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.proxy_exhausted_title)
                .setMessage(R.string.proxy_exhausted_message)
                .setPositiveButton(R.string.proxy_rescan, (d, which) -> {
                    proxyOfferedFor = offered;
                    ProxyFinder.get(this).rescan();
                    FragmentManager fragments = getSupportFragmentManager();
                    if (!fragments.isStateSaved()) { // else it cannot be shown: the queue goes on at once
                        accepted[0] = true;
                        afterDialog(ProxyDialogFragment.class, done);
                        if (fragments.findFragmentByTag(ProxyDialogFragment.TAG) == null) {
                            new ProxyDialogFragment().show(fragments, ProxyDialogFragment.TAG);
                        }
                    }
                })
                .setNegativeButton(R.string.later, (d, which) -> proxyOfferedFor = offered)
                .setOnCancelListener(d -> proxyOfferedFor = offered)
                .setOnDismissListener(d -> {
                    if (!accepted[0]) {
                        done.run();
                    }
                })
                .show();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).requestFocus(); // a remote's first press answers
    }

    /** A downloaded update (or one on 4pda that needs a login) is put to the user once, after other dialogs. */
    private void offerUpdate() {
        Updater.Offer offer = Updater.pending(this);
        if (offer == null || updateQueued) {
            return;
        }
        updateQueued = true;
        dialogs.add(done -> showUpdateDialog(offer, done));
    }

    private void showUpdateDialog(@NonNull Updater.Offer offer, @NonNull Runnable done) {
        if (isFinishing() || isDestroyed()) {
            updateQueued = false;
            done.run();
            return;
        }
        // any answer puts it off for a day (a check by hand shows it again); a dialog closed by recreation does not
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.update_title)
                .setIcon(R.drawable.ic_update)
                .setNegativeButton(R.string.update_later, (dialog, which) -> Updater.snooze(this, offer))
                .setOnCancelListener(dialog -> Updater.snooze(this, offer))
                .setOnDismissListener(dialog -> {
                    updateQueued = false;
                    done.run();
                });
        String version = offer.release.version;
        if (offer.apk != null) {
            builder.setMessage(getString(R.string.update_ready, version, BuildConfig.VERSION_NAME))
                    .setPositiveButton(R.string.update_install, (dialog, which) -> {
                        Updater.snooze(this, offer);
                        Updater.install(this, offer);
                    });
        } else {
            String page = offer.release.page;
            builder.setMessage(getString(R.string.update_forum, version, BuildConfig.VERSION_NAME))
                    .setNeutralButton(R.string.update_in_browser, (dialog, which) -> {
                        Updater.snooze(this, offer);
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(page)));
                        } catch (ActivityNotFoundException e) {
                            Toast.makeText(this, R.string.browser_not_found, Toast.LENGTH_SHORT).show();
                        }
                    });
            if (getPackageManager().hasSystemFeature(PackageManager.FEATURE_WEBVIEW)) {
                builder.setPositiveButton(R.string.update_forum_login, (dialog, which) -> {
                    Updater.snooze(this, offer);
                    ForumLoginActivity.start(this, page);
                });
            }
        }
        builder.show();
    }

    /** "Now playing" is offered only while the player service holds a book. */
    private void updateNowPlayingEntry() {
        boolean visible = MediaPlayerService.isRunning() && MediaPlayerService.currentBook() != null;
        if (navigationView != null) {
            MenuItem item = navigationView.getMenu().findItem(R.id.nav_now_playing);
            if (item != null) {
                item.setVisible(visible);
            }
        }
        View textView = findViewById(R.id.layout_nav_now_playing);
        if (textView != null) {
            textView.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private boolean shouldAskBatteryOptimization() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) {
            return false;
        }
        return !ListFocus.isTv() && !new StorageUtil(this).loadBattaryOptimizeDisenbled();
    }

    private void showPrivacyDialog(@NonNull Runnable done) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.privacy))
                .setMessage(getString(R.string.privacy_message))
                .setIcon(R.drawable.ic_privacy)
                .setCancelable(false)
                .setPositiveButton(getString(R.string.yes), (dialog, id) -> {
                    preferences.edit().putBoolean("first", true).apply();
                    dialog.cancel();
                })
                .setOnDismissListener(dialog -> done.run())
                .show();
    }

    /** Asked once: either answer is remembered, so it does not come back with every new main screen. */
    private void showNotificationRationale(@NonNull Runnable done) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setMessage(R.string.notification_permission_rationale)
                .setCancelable(false)
                .setPositiveButton(R.string.continue_, (dialog, which) -> {
                    preferences.edit().putBoolean(PREF_NOTIFICATION_ASKED, true).apply();
                    permissionDone = done;
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
                })
                .setNegativeButton(R.string.cancel, (dialog, which) -> {
                    preferences.edit().putBoolean(PREF_NOTIFICATION_ASKED, true).apply();
                    done.run();
                })
                .show();
    }

    private void showBatteryDialog(@NonNull Runnable done) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setMessage(R.string.setIgnoredBatteryOptimyze)
                .setNegativeButton(R.string.cancel, null)
                .setCancelable(true)
                .setNeutralButton(R.string.do_not_how, (dialogInterface, i) -> setBattaryOptimizeDisenbled(true))
                .setPositiveButton(R.string.ok,
                        (dialogInterface, i) -> mPresenter.openSettingsOptimizeBattery(dialogInterface))
                .setOnDismissListener(dialog -> done.run())
                .show();
    }

    /**
     * The one-time offer of "Автоподбор": any answer, a cancel too, stores {@link Sources#PREF_RANKED_AT} as 0, so it
     * never comes back; "Подобрать" opens the sources dialog, which runs it and stores its time. The next start-up
     * dialog waits for the sources dialog to close, not to come over it.
     */
    private void showAutoRankDialog(@NonNull Runnable done) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean[] accepted = new boolean[1];
        new AlertDialog.Builder(this)
                .setTitle(R.string.sources_autorank_offer_title)
                .setMessage(R.string.sources_autorank_offer_message)
                .setPositiveButton(R.string.sources_autorank_offer_yes, (dialog, which) -> {
                    accepted[0] = true;
                    afterDialog(SourcesDialogFragment.class, done);
                    showSourcesSettings(true);
                })
                .setNegativeButton(R.string.sources_autorank_offer_no, null)
                .setOnDismissListener(dialog -> {
                    if (!prefs.contains(Sources.PREF_RANKED_AT)) {
                        prefs.edit().putLong(Sources.PREF_RANKED_AT, 0).apply();
                    }
                    if (!accepted[0]) {
                        done.run();
                    }
                })
                .show();
    }

    /** Runs {@code done} once, when the dialog of {@code type} that is about to open (or is open) is gone. */
    private void afterDialog(@NonNull Class<? extends Fragment> type, @NonNull Runnable done) {
        FragmentManager fragments = getSupportFragmentManager();
        fragments.registerFragmentLifecycleCallbacks(new FragmentManager.FragmentLifecycleCallbacks() {
            @Override
            public void onFragmentDestroyed(@NonNull FragmentManager fm, @NonNull Fragment fragment) {
                if (type.isInstance(fragment)) {
                    fragments.unregisterFragmentLifecycleCallbacks(this);
                    if (!isChangingConfigurations() && !isFinishing()) {
                        done.run(); // a rotation ends this activity's queue: the rest waits for the next start
                    }
                }
            }
        }, true);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        View currentFocus = getCurrentFocus();
        if (currentFocus!=null){
            if(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (currentFocus.getId()==R.id.layout_nav_audiobooks||currentFocus.getId()==R.id.toolbar) {
                    View menuItem = findViewById(R.id.app_bar_search);
                    if (menuItem != null && menuItem.requestFocus()) {
                        return true; // handled: unhandled, the key would move the focus on past the search
                    }
                }
            }
            if(keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                // handled as on the right: unhandled, the key would move the focus on, one step past the target
                if (currentFocus.getId()==R.id.app_bar_search){
                    Toolbar toolbar = findViewById(R.id.toolbar);
                    if (toolbar != null && toolbar.requestFocus()) {
                        return true;
                    }
                }
                if (currentFocus.getId()==R.id.toolbar){
                    View view = findViewById(R.id.layout_nav_audiobooks);
                    if (view != null && view.requestFocus()) {
                        return true;
                    }
                }
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // the style before any inflation; the preference is read once here (UI-19)
        AppThemes.apply(this, R.style.AppTheme_NoAnimTheme, R.style.LightAppTheme_NoAnimTheme,
                R.style.AppThemeBlack_NoAnimTheme);
        super.onCreate(savedInstanceState);
        closeApp = false;
        Log.d(TAG, "onCreate: called");

        setContentView(R.layout.activity_main);
        Updater.start(this); // once per process: a check in a few seconds, then every 1.5 h
        AppRegion.start(this); // once per process: the region of the connection, in a few seconds

        preferences = getSharedPreferences("FIRST", Context.MODE_PRIVATE);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);


        DrawerLayout drawer = findViewById(R.id.drawer_layout);
        navigationView = findViewById(R.id.nav_view);
        if (drawer != null && navigationView != null) {
            ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                    this, drawer, toolbar, R.string.navigation_drawer_open,
                    R.string.navigation_drawer_close);
            drawer.addDrawerListener(toggle);
            drawer.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
                @Override
                public void onDrawerStateChanged(int newState) {
                    // the entry shows the player as it is when the drawer starts to open
                    if (newState != DrawerLayout.STATE_IDLE && !drawer.isDrawerOpen(GravityCompat.START)) {
                        updateNowPlayingEntry();
                        updateSourcesEntry();
                    }
                }
            });
            toggle.syncState();
            navigationView.setNavigationItemSelectedListener(this);
        } else {
            LinearLayout linearLayout = findViewById(R.id.liner_nav_view);
            if (linearLayout != null) {
                mTextViewArrayList = new ArrayList<>();
                final TypedValue outValue = new TypedValue();
                getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue,
                        true);
                final TypedValue SelectedValue = new TypedValue();
                getTheme().resolveAttribute(R.attr.mySelectableItemBackground, SelectedValue, true);
                for (int i = 0; i < linearLayout.getChildCount(); i++) {
                    View view = linearLayout.getChildAt(i);
                    if (view instanceof TextView) {
                        mTextViewArrayList.add((TextView) view);
                        view.setOnClickListener(view1 -> {
                            if (view1.getId() == R.id.layout_nav_exit) {
                                MediaPlayerService.exitApp(this);
                                return;
                            }
                            if (view1.getId() == R.id.layout_nav_now_playing) {
                                // opens the book screen, so the current section stays highlighted
                                mPresenter.onItemSelected(view1.getId());
                                updateNowPlayingEntry(); //a dead entry (the player stopped meanwhile) hides itself
                                return;
                            }
                            for (TextView textView : mTextViewArrayList) {
                                textView.setBackgroundResource(outValue.resourceId);
                            }
                            view1.setBackgroundResource(SelectedValue.resourceId);
                            mSectionFrom = view1; // showFragment ties it to the screen opened
                            mSectionFor = null;
                            mPresenter.onItemSelected(view1.getId());
                            mSectionFrom = mSectionFor == null ? null : mSectionFrom;
                        });

                    }
                }
            }
        }


        freshStart = savedInstanceState == null;
        if (getSupportFragmentManager().findFragmentByTag(ProxyDialogFragment.TAG) != null) {
            dialogs.add(done -> afterDialog(ProxyDialogFragment.class, done)); // restored open: offers wait for it
        }


        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                DrawerLayout drawer = findViewById(R.id.drawer_layout);
                if (drawer != null && drawer.isDrawerOpen(GravityCompat.START)) {
                    drawer.closeDrawer(GravityCompat.START);
                    return;
                }


                if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
                    if (drawer == null) {
                        LinearLayout linearLayout = findViewById(R.id.liner_nav_view);
                        if (linearLayout != null) {
                            final TypedValue outValue = new TypedValue();
                            getTheme().resolveAttribute(android.R.attr.selectableItemBackground,
                                    outValue,
                                    true);
                            for (int i = 0; i < linearLayout.getChildCount(); i++) {
                                View view = linearLayout.getChildAt(i);
                                if (view instanceof TextView) {
                                    view.setBackgroundResource(outValue.resourceId);
                                }
                            }
                        }
                    }
                    getSupportFragmentManager().popBackStack();
                    return;
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
                setEnabled(true);
            }
        });
    }


    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.nav_exit) {
            MediaPlayerService.exitApp(this);
            return true;
        }
        mPresenter.onItemSelected(item.getItemId());
        DrawerLayout drawer = findViewById(R.id.drawer_layout);
        if (drawer != null) {
            drawer.closeDrawer(GravityCompat.START);
        }
        return true;
    }

    @Override
    public void openActivity(@NotNull Intent intent) {
        startActivity(intent);
    }

    /**
     * Shows {@code fragment} under {@code tag}. The fragment manager's back stack is the only navigation state
     * (UI-03): a screen already on top is kept, an older copy of the same screen leaves the stack before the new
     * one is pushed, and nothing is committed after the state was saved.
     */
    @Override
    public void showFragment(@NotNull Fragment fragment, @NonNull String tag) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        if (fragmentManager.isStateSaved()) {
            return;
        }
        Fragment current = fragmentManager.findFragmentById(R.id.container);
        // the same tag on another URL (a filter-bar period rewrote the current one) is a different listing
        if (current != null && tag.equals(current.getTag()) && !tag.equals("searchebleBooks")
                && Objects.equals(urlArg(current), urlArg(fragment))) {
            // the side-menu entry of the section on screen, chosen again with a TV remote: into its list, at the top
            View list = mSectionFrom == null || !mSectionFrom.isFocused() || current.getView() == null ? null
                    : current instanceof PreferenceFragmentCompat ? ((PreferenceFragmentCompat) current).getListView()
                    : current.getView().findViewById(R.id.list);
            if (list instanceof RecyclerView && ((RecyclerView) list).getAdapter() != null
                    && ((RecyclerView) list).getAdapter().getItemCount() > 0) {
                ListFocus.focusFirst((RecyclerView) list);
            }
            return;
        }
        if (mSectionFrom != null) {
            mSectionFor = fragment;
        }
        fragmentManager.popBackStackImmediate(tag, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        Fragment top = fragmentManager.findFragmentById(R.id.container);
        FragmentTransaction transaction = fragmentManager.beginTransaction();
        transaction.replace(R.id.container, fragment, tag);
        // the root screen still on top after the pop and re-opened on the URL it was opened on (a filter-bar
        // period rewrote its current one) is replaced in place, so Back does not show a copy of it; another
        // listing under the same tag (an author's, opened from a book) is pushed
        String opened = top != null && top.getArguments() != null
                ? top.getArguments().getString(BooksFragment.ARG_OPENED_URL) : null;
        if (top != null && !(tag.equals(top.getTag()) && opened != null && opened.equals(urlArg(fragment)))) {
            transaction.addToBackStack(tag);
        }
        transaction.commit();

    }

    /**
     * Another list of Избранное (books, authors, readers) in place of the one on screen, under its tag: Back leads
     * where it led before and the back stack keeps its depth. The screen's own entry is popped and pushed again in
     * one go, so nothing in between is drawn.
     */
    public void switchFavorites(@NonNull Fragment fragment) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        Fragment current = fragmentManager.findFragmentById(R.id.container);
        if (fragmentManager.isStateSaved() || current == null || current.getTag() == null) {
            return;
        }
        String tag = current.getTag();
        boolean pushed = fragmentManager.popBackStackImmediate(tag, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        FragmentTransaction transaction = fragmentManager.beginTransaction().replace(R.id.container, fragment, tag);
        if (pushed) {
            transaction.addToBackStack(tag);
        }
        transaction.commit();
        fragmentManager.executePendingTransactions();
    }

    /** A drawer section ({@code R.id.nav_*}), as if chosen there: an empty list's way to where it is filled. */
    public void openSection(int navId) {
        mPresenter.onItemSelected(navId);
    }

    private static String urlArg(Fragment fragment) {
        Bundle args = fragment.getArguments();
        return args == null ? null : args.getString("url");
    }

    @Override
    public void showToast(final int id) {
        Toast.makeText(this, id, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void setBattaryOptimizeDisenbled(boolean b) {
        new StorageUtil(this).storeBattaryOptimizeDisenbled(b);
    }

    @Override
    protected void onDestroy() {
        //playback belongs to the player service: closing (or rotating) this screen neither stops nor hides it
        super.onDestroy();
        closeApp = true;
    }


    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return handleUserInput(this, event) || super.dispatchKeyEvent(event);
    }

    /**
     * «Слушать последнюю книгу»: the player resumes the book listened to last (its checkpoint, chapters from the
     * database) in the background; never over a player already up, nothing when no book was listened to yet. A book
     * only opened since (loaded paused) is not the one listened to.
     */
    private void playLastBook() {
        String url = new StorageUtil(this).loadLastPlayedUrl();
        if (MediaPlayerService.isRunning() || url == null) {
            return;
        }
        try {
            ContextCompat.startForegroundService(this, new Intent(this, MediaPlayerService.class)
                    .setAction(MediaPlayerService.ACTION_PLAY).putExtra(MediaPlayerService.EXTRA_BOOK_URL, url));
        } catch (RuntimeException e) { // ForegroundServiceStartNotAllowedException is an IllegalStateException
            Log.w(TAG, "playLastBook: the player cannot start", e);
        }
    }

    @Override
    public void showBooksActivity(@Nullable BookPOJO bookPOJO) {
        if (bookPOJO == null) return;
        if (LoadBook.opensAsListing(bookPOJO.getUrl())) {
            // a collection from history or "last book": its listing, as tapping its card opens it (BooksPresenter.open)
            showFragment(BooksFragment.newInstance(bookPOJO.getUrl(), R.string.menu_audiobooks, bookPOJO.getName(),
                    Consts.MODEL_BOOKS), "collectionBooks");
            return;
        }
        BookActivity.startNewActivity(this, bookPOJO);
    }
}
