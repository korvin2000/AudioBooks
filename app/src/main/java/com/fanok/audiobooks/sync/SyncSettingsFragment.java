package com.fanok.audiobooks.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import com.fanok.audiobooks.R;
import com.fanok.audiobooks.adapter.ListFocus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * «Облачная синхронизация»: the cloud to sync with, «Синхронизировать сейчас» and how the last sync went. Opened by
 * the app's settings from its preference's {@code android:fragment}; the clouds come from {@link CloudSync#SERVICES}.
 */
public class SyncSettingsFragment extends PreferenceFragmentCompat
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String CLOUD = "cloud", NOW = "now";

    /** Each cloud's sign-in action, by its id. */
    private final Map<String, Runnable> signIns = new HashMap<>();

    /** TV: the remote's row, kept across this screen's re-creations. */
    private final ListFocus listFocus = new ListFocus();

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        // every cloud's sign-in is bound on every creation, in one order: its results find their way back here
        for (CloudService service : CloudSync.SERVICES) {
            signIns.put(service.id(), service.signIn(this, CloudSync.finisher(requireContext(), service)));
        }
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.sync_preferences, rootKey);
        ListPreference cloud = findPreference(CLOUD);
        Preference now = findPreference(NOW);
        if (cloud != null && now != null) {
            cloud.setOnPreferenceChangeListener((preference, value) -> {
                choose(value.toString());
                return false; // shown as connected once signed in
            });
            now.setOnPreferenceClickListener(preference -> {
                CloudSync.now(requireContext());
                Toast.makeText(requireContext(), R.string.sync_started, Toast.LENGTH_SHORT).show();
                return true;
            });
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        listFocus.restore(this, savedInstanceState);
    }

    @Override
    public void onResume() {
        super.onResume();
        requireActivity().setTitle(R.string.sync_service);
        PreferenceManager.getDefaultSharedPreferences(requireContext()).registerOnSharedPreferenceChangeListener(this);
        show();
    }

    @Override
    public void onPause() {
        listFocus.onPause(this);
        PreferenceManager.getDefaultSharedPreferences(requireContext()).unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        listFocus.onSaveInstanceState(outState);
    }

    @Override
    public void onDestroyView() {
        listFocus.onDestroyView();
        super.onDestroyView();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences preferences, @Nullable String key) {
        if (key != null && key.startsWith(CloudSync.PREF_PREFIX)) {
            show();
        }
    }

    /** The clouds this device can use (the connected one even when it no longer can: to switch it off) and the status. */
    private void show() {
        ListPreference cloud = findPreference(CLOUD);
        Preference now = findPreference(NOW);
        Context context = getContext();
        if (cloud == null || now == null || context == null) {
            return;
        }
        CloudService connected = CloudSync.connected(context);
        List<CharSequence> titles = new ArrayList<>(), ids = new ArrayList<>();
        titles.add(getString(R.string.sync_off));
        ids.add("");
        for (CloudService service : CloudSync.SERVICES) {
            if (service == connected || service.available(context)) {
                titles.add(getString(service.title()));
                ids.add(service.id());
            }
        }
        cloud.setEntries(titles.toArray(new CharSequence[0]));
        cloud.setEntryValues(ids.toArray(new CharSequence[0]));
        cloud.setValue(connected == null ? "" : connected.id());
        cloud.setSummary(cloud.getEntry());
        String status = CloudSync.status(context);
        now.setEnabled(connected != null);
        now.setSummary(status != null ? status
                : getString(ids.size() > 1 ? R.string.sync_summary : R.string.sync_unavailable));
    }

    private void choose(String id) {
        Context context = requireContext();
        CloudService connected = CloudSync.connected(context);
        CloudService chosen = CloudSync.find(id);
        CloudSync.choose(context, chosen); // a sign-in to another cloud still under way ends unheeded
        if (chosen == null) {
            if (connected != null) {
                new AlertDialog.Builder(context)
                        .setTitle(R.string.app_name)
                        .setMessage(R.string.sync_off_confirm)
                        .setPositiveButton(R.string.yes, (dialog, which) -> CloudSync.disconnect(context))
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            }
            return;
        }
        if (chosen == connected && CloudSync.working(context)) {
            return; // connecting a working one anew would only forget what was agreed with it
        }
        signIns.get(chosen.id()).run();
    }
}
