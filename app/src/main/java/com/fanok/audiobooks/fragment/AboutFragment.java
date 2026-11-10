package com.fanok.audiobooks.fragment;

import static android.content.Context.CLIPBOARD_SERVICE;
import static android.content.Context.UI_MODE_SERVICE;

import android.app.UiModeManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;
import com.fanok.audiobooks.BuildConfig;
import com.fanok.audiobooks.R;
import com.fanok.audiobooks.activity.ActivitySendEmail;
import com.fanok.audiobooks.activity.MainActivity;
import com.fanok.audiobooks.adapter.ListFocus;
import com.fanok.audiobooks.region.AppRegion;
import com.fanok.audiobooks.region.Region;
import com.fanok.audiobooks.region.RegionCheck;
import com.fanok.audiobooks.update.Updater;
import com.google.android.material.navigation.NavigationView;
import java.util.Locale;
import org.jetbrains.annotations.NotNull;

public class AboutFragment extends PreferenceFragmentCompat {

    /** TV: the remote's row, kept across this screen's views (a «Поддержать» group opened over it) and re-creations. */
    private final ListFocus mListFocus = new ListFocus();

    @Override
    public void onNavigateToScreen(PreferenceScreen preferenceScreen) {
        AboutFragment settingsFragment = new AboutFragment();
        Bundle args = new Bundle();
        args.putString("rootKey", preferenceScreen.getKey());
        settingsFragment.setArguments(args);
        if (getActivity() != null) {
            getActivity().getSupportFragmentManager()
                    .beginTransaction()
                    .replace(getId(), settingsFragment)
                    .addToBackStack(null)
                    .commit();
        }
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {

        if (getArguments() != null) {
            String key = getArguments().getString("rootKey");
            setPreferencesFromResource(R.xml.about_preferences, key);
        } else {
            setPreferencesFromResource(R.xml.about_preferences, rootKey);
        }

        requireActivity().setTitle(R.string.menu_about);
        PreferenceManager.setDefaultValues(
                requireActivity().getApplicationContext(),
                R.xml.about_preferences,
                false);

        preferenceClickListner("version", preference -> {
            Context app = requireContext().getApplicationContext();
            Toast.makeText(app, R.string.update_checking, Toast.LENGTH_SHORT).show();
            // a newer version is offered by MainActivity's dialog; here only the other outcomes are told
            Updater.check(app, result -> {
                if (result == Updater.Result.LATEST || result == Updater.Result.FAILED) {
                    Toast.makeText(app, result == Updater.Result.LATEST ? R.string.update_latest
                            : R.string.update_failed, Toast.LENGTH_LONG).show();
                }
            });
            return true;
        });

        preferenceClickListner("privacy", preference -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(
                    requireContext());
            builder.setTitle(getString(R.string.privacy))
                    .setMessage(getString(R.string.privacy_message))
                    .setIcon(R.drawable.ic_privacy)
                    .setCancelable(false)
                    .setNegativeButton(getString(R.string.yes),
                            (dialog, id) -> dialog.cancel());
            AlertDialog alert = builder.create();
            alert.show();
            return true;
        });

        preferenceClickListner("yandexForm", preference -> {
            openInBrowser("http://mdpu.mcdir.ru/yd.html");
            return true;
        });

        preferenceClickListner("qiwi", preference -> {
            openInBrowser("http://qiwi.com/n/ONAMA629");
            return true;
        });

        preferenceClickListner("disable_battary_optimize", preference -> {
            boolean enabled;
            PowerManager pm = (PowerManager) requireContext().getSystemService(Context.POWER_SERVICE);
            UiModeManager uiModeManager = (UiModeManager) requireContext().getSystemService(
                    UI_MODE_SERVICE);
            if (uiModeManager != null && uiModeManager.getCurrentModeType()
                    == Configuration.UI_MODE_TYPE_TELEVISION) {
                enabled = false;

            } else if (pm != null) {
                enabled = !pm.isIgnoringBatteryOptimizations(requireContext().getPackageName());
            } else {
                enabled = false;
            }

            if (enabled) {
                Toast.makeText(getContext(), R.string.enabled, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(getContext(), R.string.disenabled, Toast.LENGTH_SHORT).show();
            }

            return true;
        });


        Preference.OnPreferenceClickListener clickToCopy = preference -> {
            ClipboardManager clipboardManager =
                    (ClipboardManager) requireContext().getSystemService(
                            CLIPBOARD_SERVICE);
            String text = preference.getSummary().toString().replaceAll(" ", "");
            ClipData clipData = ClipData.newPlainText("Source Text", text);
            if (clipboardManager != null) {
                clipboardManager.setPrimaryClip(clipData);
                Toast.makeText(getContext(), getString(R.string.copied), Toast.LENGTH_SHORT).show();
            }
            return true;
        };

        preferenceClickListner("write_autor", preference -> {
            Intent intent = new Intent(getContext(), ActivitySendEmail.class);
            startActivity(intent);
            return true;
        });

        preferenceClickListner("open_4pda", preference -> {
            openInBrowser("https://4pda.to/forum/index.php?showtopic=1128418");
            return true;
        });

        preferenceClickListner("open_github", preference -> {
            openInBrowser("https://github.com/korvin2000/AudioBooks");
            return true;
        });

        // the credit of the IP table (CC BY 4.0) asks for a link
        preferenceClickListner("about_region", preference -> {
            openInBrowser("https://db-ip.com");
            return true;
        });

        preferenceClickListner("open_telegram", preference -> {
            // the Telegram app when one is installed (tg: is its own scheme), otherwise the group's page in a browser
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=AudioKnijki")));
            } catch (ActivityNotFoundException e) {
                openInBrowser("https://t.me/AudioKnijki");
            }
            return true;
        });

        preferenceClickListner("open_rustore", preference -> {
            openInBrowser("https://apps.rustore.ru/app/com.fanok.audiobooks");
            return true;
        });

        preferenceClickListner("monoUAH", clickToCopy);
        preferenceClickListner("monoUSD", clickToCopy);
        preferenceClickListner("monoEUR", clickToCopy);
        preferenceClickListner("psb", clickToCopy);
        preferenceClickListner("privat", clickToCopy);
        preferenceClickListner("alpha", clickToCopy);
        //preferenceClickListner("paypal", clickToCopy);
        preferenceClickListner("yd", clickToCopy);
        preferenceClickListner("wmu", clickToCopy);
        preferenceClickListner("wmr", clickToCopy);
        preferenceClickListner("wmz", clickToCopy);
        preferenceClickListner("wme", clickToCopy);


    }

    @Override
    public void onResume() {
        super.onResume();
        Preference version = findPreference("version");
        if (version != null) {
            // the hint in blue: it is the only thing on this screen that does something on a click
            SpannableString hint = new SpannableString(getString(R.string.update_check_hint));
            hint.setSpan(new ForegroundColorSpan(ContextCompat.getColor(requireContext(), R.color.sources_link)), 0,
                    hint.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            version.setSummary(TextUtils.concat(
                    getString(R.string.version) + " " + BuildConfig.VERSION_NAME + "\n", hint));
            version.setTitle(getString(R.string.app_name));
        }

        Preference region = findPreference("about_region");
        if (region != null) {
            // "Регион: Россия"; for the west and the rest the country too: "Регион: Запад · Германия"
            RegionCheck.Result last = AppRegion.last(requireContext());
            boolean known = last != null && last.source != RegionCheck.Source.NONE;
            String name = SourceTexts.regionName(requireContext(), known ? last.region : null);
            if (known && last.region != Region.RUSSIA && last.region != Region.UKRAINE
                    && last.country != null && last.country.length() == 2) {
                name += " · " + new Locale("", last.country).getDisplayCountry(new Locale("ru"));
            }
            region.setTitle(known ? getString(R.string.about_region, name) : name);
        }

        MainActivity activity = (MainActivity) getActivity();
        if (activity != null) {
            // also when back from another section, which left its title and subtitle (a genre, an author) there
            activity.setTitle(R.string.menu_about);
            ActionBar bar = activity.getSupportActionBar();
            if (bar != null) {
                bar.setSubtitle(null);
            }
            NavigationView navigationView = activity.getNavigationView();
            if (navigationView != null) {
                navigationView.setCheckedItem(R.id.nav_about);
            } else {
                activity.highlightNav(R.id.layout_nav_about);
            }
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mListFocus.restore(this, savedInstanceState);
    }

    @Override
    public void onPause() {
        super.onPause();
        mListFocus.onPause(this);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        mListFocus.onSaveInstanceState(outState);
    }

    @Override
    public void onDestroyView() {
        mListFocus.onDestroyView();
        super.onDestroyView();
    }

    /** Opens {@code url} in a browser; devices without one get a message instead of a crash. */
    private void openInBrowser(@NotNull String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(getContext(), R.string.browser_not_found, Toast.LENGTH_SHORT).show();
        }
    }

    private void preferenceClickListner(@NotNull String key,
            @NotNull Preference.OnPreferenceClickListener click) {
        Preference preference = findPreference(key);
        if (preference != null) {
            preference.setOnPreferenceClickListener(click);
        }
    }
}
