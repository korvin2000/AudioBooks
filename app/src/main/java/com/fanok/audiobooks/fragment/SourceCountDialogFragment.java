package com.fanok.audiobooks.fragment;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;
import androidx.preference.PreferenceManager;

import com.fanok.audiobooks.R;
import com.fanok.audiobooks.search.Provider;
import com.fanok.audiobooks.search.SourceSnapshot;
import com.fanok.audiobooks.search.Sources;
import com.google.android.material.slider.Slider;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * «Активные источники»: how many of the first sites of the user's order are active, 1..all, picked with − / +, the
 * slider or a remote's arrows and previewed on the strip of every site and in the status line. The order never
 * changes; nothing is stored before «Применить», which saves the count with the sources screens' undo.
 */
public class SourceCountDialogFragment extends DialogFragment {

    public static final String TAG = "SourceCountDialogFragment";
    private static final String STATE_SHOWN = "shown";

    /** The count picked, stored only by «Применить». */
    private int shown;
    private TextView value;
    private TextView status;
    private TextView left;
    private TextView right;
    private SourceSegmentsView strip;
    private Slider slider;
    private View less;
    private View more;

    /** The settings row: «Активны 13 из 29: Akniga, Книга в ухе и ещё 11». */
    @NonNull
    static String summary(@NonNull Context context) {
        List<Provider> active = Sources.active();
        List<String> names = titles(active, 0, active.size(), false);
        String list = names.size() > 2 ? context.getString(R.string.sources_names_more, names.get(0), names.get(1),
                names.size() - 2) : TextUtils.join(", ", names);
        return context.getString(names.size() == 1 ? R.string.sources_count_summary_one
                : R.string.sources_count_summary, names.size(), Provider.values().length, list);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        shown = savedInstanceState != null ? savedInstanceState.getInt(STATE_SHOWN) : Sources.activeCount();
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        View view = LayoutInflater.from(builder.getContext()).inflate(R.layout.dialog_source_count, null, false);
        value = view.findViewById(R.id.sourceCountValue);
        status = view.findViewById(R.id.sourceCountStatus);
        left = view.findViewById(R.id.sourceCountLeft);
        right = view.findViewById(R.id.sourceCountRight);
        strip = view.findViewById(R.id.sourceCountStrip);
        slider = view.findViewById(R.id.sourceCountSlider);
        less = view.findViewById(R.id.sourceCountLess);
        more = view.findViewById(R.id.sourceCountMore);

        int total = Sources.all().size();
        ((TextView) view.findViewById(R.id.sourceCountOf)).setText(getString(R.string.sources_sheet_of, total));
        ((TextView) view.findViewById(R.id.sourceCountFull)).setText(getString(R.string.sources_count_full, total));
        slider.setValueTo(total);
        slider.setLabelFormatter(v -> String.valueOf(Math.round(v)));
        // the strip's cells span the slider's track (half a gap between cells in): the thumb stands on their line
        slider.addOnLayoutChangeListener((s, l, t, r, b, ol, ot, or, ob) -> {
            int side = slider.getTrackSidePadding() + Math.round(getResources().getDisplayMetrics().density);
            if (strip.getPaddingLeft() != side) {
                strip.setPadding(side, 0, side, 0);
            }
        });
        slider.addOnChangeListener((s, v, fromUser) -> {
            if (fromUser && Math.round(v) != shown) {
                s.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            }
            if (v < 1) {
                s.setValue(1); // the track starts a cell before the first site: one stays active
                return;
            }
            pick(Math.round(v));
        });
        less.setOnClickListener(v -> pick(shown - 1));
        more.setOnClickListener(v -> pick(shown + 1));
        render();
        return builder.setTitle(R.string.sources_count_title)
                .setView(view)
                .setPositiveButton(R.string.apply, (dialog, which) -> apply())
                .setNegativeButton(R.string.cancel, null)
                .create();
    }

    @Override
    public void onStart() {
        super.onStart();
        render(); // the buttons exist once the dialog is shown
        slider.requestFocus(); // a remote's arrows move the line at once
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_SHOWN, shown);
    }

    private void pick(int count) {
        count = Math.max(1, Math.min(Sources.all().size(), count));
        if (count != shown) {
            shown = count;
            render();
        }
    }

    private void render() {
        Context context = requireContext();
        List<Provider> order = Sources.all();
        int active = Sources.activeCount();
        value.setText(String.valueOf(shown));
        if (Math.round(slider.getValue()) != shown) {
            slider.setValue(shown);
        }
        SourcesDialogFragment.enable(less, shown > 1);
        SourcesDialogFragment.enable(more, shown < order.size());
        status.setText(statusLine(context.getResources().getStringArray(R.array.sources_count_status),
                getString(R.string.sources_names_more), order, active, shown, Provider.current()));
        status.setTextColor(ContextCompat.getColor(context, shown > active ? R.color.sources_ok
                : shown < active ? R.color.sources_warn : R.color.sources_text_secondary));
        int[] colors = {ContextCompat.getColor(context, R.color.sources_accent),
                ContextCompat.getColor(context, R.color.sources_ok),
                ContextCompat.getColor(context, R.color.sources_warn),
                ContextCompat.getColor(context, R.color.sources_track)};
        int[] cells = new int[order.size()];
        for (int i = 0; i < cells.length; i++) {
            cells[i] = colors[i < Math.min(active, shown) ? 0 : i < shown ? 1 : i < active ? 2 : 3];
        }
        strip.setCells(cells, -1, -1); // the slider's thumb is the line
        strip.setContentDescription(status.getText());
        left.setText(TextUtils.join("   ", titles(order, shown - 2, shown, true)));
        right.setText(TextUtils.join("   ", titles(order, shown, shown + 2, true)));
        Button ok = getDialog() instanceof AlertDialog
                ? ((AlertDialog) getDialog()).getButton(DialogInterface.BUTTON_POSITIVE) : null;
        if (ok != null) {
            ok.setEnabled(shown != active);
        }
    }

    /** Stores the count picked, the order as it is, and offers the undo. */
    private void apply() {
        int active = Sources.activeCount();
        if (shown == active) {
            return;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        List<Provider> order = Sources.all();
        SourceSnapshot before = SourceSnapshot.take(prefs);
        boolean moved = Sources.save(prefs, order, shown);
        List<String> changed = titles(order, Math.min(active, shown), Math.max(active, shown), false);
        String text = getString(R.string.sources_count_done, shown, (shown > active ? "+" : "−") + changed.size(),
                SourceTexts.names(changed, getString(R.string.sources_names_more)));
        if (moved) {
            text += SourceTexts.SEP + getString(R.string.sources_done_catalog, order.get(0).title);
        }
        View anchor = getParentFragment() != null && getParentFragment().getView() != null
                ? getParentFragment().getView() : requireActivity().findViewById(android.R.id.content);
        SourceActions.show(anchor, prefs, before, SourceSnapshot.take(prefs), text);
    }

    /**
     * Pure: the status line of {@code shown} active of {@code order} while {@code active} are: {@code words} are
     * {@code R.array.sources_count_status} (unchanged: some, one, all; on: one, several; off: one, several; the catalog),
     * {@code more} is {@code R.string.sources_names_more}. Below the {@code catalog} source the catalog moves to the
     * first site.
     */
    @NonNull
    static String statusLine(@NonNull String[] words, @NonNull String more, @NonNull List<Provider> order, int active,
            int shown, @Nullable Provider catalog) {
        if (shown == active) {
            return shown == order.size() ? String.format(Locale.ROOT, words[2], shown)
                    : shown == 1 ? String.format(Locale.ROOT, words[1], order.get(0).title)
                    : String.format(Locale.ROOT, words[0], shown);
        }
        List<String> changed = titles(order, Math.min(active, shown), Math.max(active, shown), false);
        String text = String.format(Locale.ROOT, words[(shown > active ? 3 : 5) + (changed.size() == 1 ? 0 : 1)],
                SourceTexts.names(changed, more));
        int at = order.indexOf(catalog);
        return shown < active && at >= shown && at < active
                ? text + SourceTexts.SEP + String.format(Locale.ROOT, words[7], order.get(0).title) : text;
    }

    /** The titles of the places {@code from} up to {@code to} (0-based) that exist; numbered: «13 Аудиокнига Life». */
    @NonNull
    private static List<String> titles(@NonNull List<Provider> order, int from, int to, boolean numbered) {
        List<String> titles = new ArrayList<>();
        for (int i = Math.max(0, from); i < Math.min(order.size(), to); i++) {
            titles.add(numbered ? (i + 1) + " " + order.get(i).title : order.get(i).title);
        }
        return titles;
    }
}
