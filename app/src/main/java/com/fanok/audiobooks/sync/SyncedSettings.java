package com.fanok.audiobooks.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import androidx.preference.PreferenceManager;
import com.fanok.audiobooks.AppThemes;
import com.fanok.audiobooks.Consts;
import com.fanok.audiobooks.R;
import com.fanok.audiobooks.pojo.EqualizerSettings;
import com.fanok.audiobooks.pojo.StorageUtil;
import com.fanok.audiobooks.presenter.BookPresenter;
import com.fanok.audiobooks.search.Provider;
import com.fanok.audiobooks.search.Sources;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * The settings that follow the user to every device, as text in {@link Snapshot#prefs}: a whitelist with the type
 * and the valid values of each, since the app reads them with fixed types. Device matters (paths, proxies and routes,
 * cookies, the parental PIN, caches) are not on it.
 */
final class SyncedSettings {

    /** Default preferences holding text. */
    private static final List<String> TEXTS = Arrays.asList("pref_theme", "books_adapter_layout_pref", "seek_step",
            "pref_sort_favorite", "pref_start_screen", Sources.PREF, Sources.PREF_MAX, Sources.SELECTED);
    /** The list settings among them, with the array of their values. */
    private static final Map<String, Integer> LISTS = new HashMap<>();
    /** Default preferences holding a flag; also the search checkbox of every source. */
    private static final List<String> FLAGS = new ArrayList<>(Arrays.asList("history_procent", "toolbar_title",
            "toolbar_favorite", "toolbar_favorite_author", "toolbar_favorite_reader", "toolbar_share",
            "toolbar_speed", "toolbar_download", "toolbar_sleep_timer", "toolbar_equalizer", "toolbar_settings",
            "toolbar_open_site", "toolbar_main_screen", "toolbar_refresh", "autoPlayPref", "reproductionPref",
            "seekButtonsPref", "seekToPausePref", "rewind_back", "safeNavigationPref", "scrollToSelectedPref",
            "search_pref", "pref_autoload_last_book", "flip_pause"));
    /** Settings of the {@link StorageUtil#STORAGE} file: a float and a JSON text. */
    private static final String SPEED = "speed", EQUALIZER = "equalizer";

    static {
        for (Provider provider : Provider.values()) {
            FLAGS.add(provider.prefKey);
        }
        LISTS.put("pref_theme", R.array.settings_them_values);
        LISTS.put("books_adapter_layout_pref", R.array.books_adapter_layout_values);
        LISTS.put("seek_step", R.array.seek_step_values);
        LISTS.put("pref_sort_favorite", R.array.settings_sort_values);
        LISTS.put("pref_start_screen", R.array.settings_start_screen_values);
    }

    private SyncedSettings() {
    }

    /** The synced settings that are set here, as text. */
    static Map<String, String> read(Context app) {
        Map<String, String> out = new TreeMap<>();
        Map<String, ?> values = PreferenceManager.getDefaultSharedPreferences(app).getAll();
        for (String key : TEXTS) {
            if (values.get(key) instanceof String) {
                out.put(key, (String) values.get(key));
            }
        }
        for (String key : FLAGS) {
            if (values.get(key) instanceof Boolean) {
                out.put(key, values.get(key).toString());
            }
        }
        values = storage(app).getAll();
        if (values.get(SPEED) instanceof Float) {
            out.put(SPEED, values.get(SPEED).toString());
        }
        if (values.get(EQUALIZER) instanceof String) {
            out.put(EQUALIZER, (String) values.get(EQUALIZER));
        }
        return out;
    }

    /**
     * Writes the settings {@code to} changed against {@code was}, each with its type, unless it changed here
     * meanwhile; a value this version does not take stays in {@code kept} as it is here. Then what holds a setting
     * in memory (the sources, the night mode, the speed) follows it.
     */
    static void apply(Context app, Map<String, String> was, Map<String, String> to, Map<String, String> kept) {
        SharedPreferences main = PreferenceManager.getDefaultSharedPreferences(app), storage = storage(app);
        Map<String, String> now = read(app);
        SharedPreferences.Editor mainEdit = main.edit(), storageEdit = storage.edit();
        Set<String> written = new HashSet<>();
        for (Map.Entry<String, String> e : to.entrySet()) {
            String key = e.getKey(), value = e.getValue();
            if (value.equals(was.get(key))) {
                continue;
            }
            if (!allowed(app, key, value)) {
                DeviceState.unapplied(kept, was, key);
                continue;
            }
            if (!Objects.equals(now.get(key), was.get(key))) {
                continue;
            }
            if (FLAGS.contains(key)) {
                mainEdit.putBoolean(key, Boolean.parseBoolean(value));
            } else if (key.equals(SPEED)) {
                storageEdit.putFloat(key, speed(value));
            } else if (key.equals(EQUALIZER)) {
                storageEdit.putString(key, value); // the player loads it when it starts
            } else {
                mainEdit.putString(key, value);
            }
            written.add(key);
        }
        if (written.isEmpty()) {
            return;
        }
        mainEdit.apply();
        storageEdit.apply();
        new Handler(Looper.getMainLooper()).post(() -> {
            if (written.contains(Sources.PREF) || written.contains(Sources.PREF_MAX)
                    || written.contains(Sources.SELECTED)) {
                Sources.load(main); // may change the selection: it must stay an active source
                String selected = main.getString(Sources.SELECTED, null);
                if (selected != null) {
                    Consts.setSOURCE(selected);
                }
            }
            if (written.contains("pref_theme")) {
                AppThemes.applyNightMode(app);
            }
            if (written.contains(SPEED)) { // as stored now: the user may have changed it again meanwhile
                BookPresenter.setSpeedWithoutBroadcast(storage.getFloat(SPEED, 1));
            }
        });
    }

    /** Whether this version takes {@code value} for the synced setting {@code key}. */
    private static boolean allowed(Context app, String key, String value) {
        Integer values = LISTS.get(key);
        if (values != null) { // one of the list's values: the start screen, for one, is read as a number
            return Arrays.asList(app.getResources().getStringArray(values)).contains(value);
        }
        if (FLAGS.contains(key)) {
            return value.equals("true") || value.equals("false");
        }
        switch (key) {
            case SPEED:
                return speed(value) > 0;
            case EQUALIZER:
                return equalizer(value);
            case Sources.PREF:
                return true; // Sources.load keeps the known sources of any text
            case Sources.PREF_MAX:
                try {
                    int max = Integer.parseInt(value);
                    return max >= Sources.MIN_LIMIT && max <= Sources.MAX_LIMIT;
                } catch (NumberFormatException e) {
                    return false;
                }
            case Sources.SELECTED:
                for (Provider provider : Provider.values()) {
                    if (provider.prefValue.equals(value)) {
                        return true;
                    }
                }
                return false;
            default:
                return false; // not synced by this version
        }
    }

    private static float speed(String value) {
        try {
            float speed = Float.parseFloat(value);
            return speed > 0 && speed <= 4 ? speed : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean equalizer(String value) {
        try {
            return new Gson().fromJson(value, EqualizerSettings.class) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static SharedPreferences storage(Context app) {
        return app.getSharedPreferences(StorageUtil.STORAGE, Context.MODE_PRIVATE);
    }
}
