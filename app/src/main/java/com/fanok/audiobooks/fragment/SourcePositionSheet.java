package com.fanok.audiobooks.fragment;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.fanok.audiobooks.R;
import com.fanok.audiobooks.search.Provider;
import com.fanok.audiobooks.search.SourceInfo;
import com.fanok.audiobooks.search.Sources;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.slider.Slider;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * "Место в списке" of a site's card: a new place picked with the buttons, the slider or the two shortcuts, previewed
 * before anything changes — the strip of every site in the user's order with the line after the active ones, rebuilt
 * live, and by name who shifts by one place and who crosses the line. "Переместить" hands the place to the card
 * ({@link #RESULT}), which moves the site with its undo.
 */
public class SourcePositionSheet extends BottomSheetDialogFragment {

    public static final String TAG = "SourcePositionSheet";
    /** Sent to the parent's fragment manager with {@link #KEY_POSITION}, the new place (0-based). */
    public static final String RESULT = "source_position";
    public static final String KEY_POSITION = "position";
    private static final String ARG_PROVIDER = "provider";
    private static final String STATE_TO = "to";

    @Override
    public int getTheme() {
        return R.style.ThemeOverlay_App_SourceSheet;
    }

    /** What a move would change. */
    static final class Preview {
        /** The order after the move. */
        final List<Provider> order;
        final boolean wasActive;
        final boolean active;
        /** The other sites that shift by one place and stay on their side of the line, in the old order. */
        final List<Provider> shifted;
        /** The other sites that cross the line: into the active ones, out of them. */
        final List<Provider> activated;
        final List<Provider> deactivated;

        Preview(@NonNull List<Provider> order, boolean wasActive, boolean active, @NonNull List<Provider> shifted,
                @NonNull List<Provider> activated, @NonNull List<Provider> deactivated) {
            this.order = order;
            this.wasActive = wasActive;
            this.active = active;
            this.shifted = shifted;
            this.activated = activated;
            this.deactivated = deactivated;
        }

        /**
         * Pure: {@code provider} moved to {@code to} (0-based) in {@code order} with {@code active} active sites, as
         * {@link Sources#moveTo} does it: the sites between shift by one place and the active count stays, so a site
         * shifted over the line crosses it.
         */
        @NonNull
        static Preview of(@NonNull List<Provider> order, int active, @NonNull Provider provider, int to) {
            List<Provider> after = Sources.moved(order, provider, to);
            List<Provider> shifted = new ArrayList<>();
            List<Provider> activated = new ArrayList<>();
            List<Provider> deactivated = new ArrayList<>();
            for (Provider site : order) {
                int before = order.indexOf(site);
                int now = after.indexOf(site);
                if (site == provider || before == now) {
                    continue;
                }
                boolean wasActive = before < active;
                boolean isActive = now < active;
                if (wasActive == isActive) {
                    shifted.add(site);
                } else if (isActive) {
                    activated.add(site);
                } else {
                    deactivated.add(site);
                }
            }
            return new Preview(after, order.indexOf(provider) < active, after.indexOf(provider) < active, shifted,
                    activated, deactivated);
        }
    }

    private Provider provider;
    /** The place picked, 0-based. */
    private int to;
    private final Map<Provider, SourceInfo> infos = new EnumMap<>(Provider.class);
    private final CompositeDisposable disposables = new CompositeDisposable();
    private TextView subtitle;
    private TextView value;
    private TextView of;
    private SourceSegmentsView strip;
    private Slider slider;
    private TextView changesTitle;
    private TextView changes;
    private View up;
    private View down;
    private View move;

    @NonNull
    public static SourcePositionSheet newInstance(@NonNull Provider provider) {
        SourcePositionSheet sheet = new SourcePositionSheet();
        Bundle args = new Bundle();
        args.putString(ARG_PROVIDER, provider.name());
        sheet.setArguments(args);
        return sheet;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        provider = Provider.valueOf(requireArguments().getString(ARG_PROVIDER));
        to = savedInstanceState != null ? savedInstanceState.getInt(STATE_TO) : Sources.all().indexOf(provider);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.sheet_source_position, container, false);
        subtitle = view.findViewById(R.id.sheetSubtitle);
        value = view.findViewById(R.id.sheetValue);
        of = view.findViewById(R.id.sheetOf);
        strip = view.findViewById(R.id.sheetStrip);
        slider = view.findViewById(R.id.sheetSlider);
        changesTitle = view.findViewById(R.id.sheetChangesTitle);
        changes = view.findViewById(R.id.sheetChanges);
        up = view.findViewById(R.id.sheetUp);
        down = view.findViewById(R.id.sheetDown);
        move = view.findViewById(R.id.sheetMove);

        int total = Sources.all().size();
        slider.setValueFrom(1);
        slider.setValueTo(total);
        slider.setStepSize(1);
        slider.setValue(Math.max(0, Math.min(total - 1, to)) + 1);
        slider.setLabelFormatter(v -> getString(R.string.sources_sheet_value, Math.round(v)));
        slider.addOnChangeListener((s, v, fromUser) -> {
            if (fromUser) {
                to = Math.round(v) - 1;
                render();
            }
        });
        up.setOnClickListener(v -> pick(to - 1));
        down.setOnClickListener(v -> pick(to + 1));
        view.findViewById(R.id.sheetFirst).setOnClickListener(v -> pick(0));
        view.findViewById(R.id.sheetLastActive).setOnClickListener(v -> pick(Sources.activeCount() - 1));
        view.findViewById(R.id.sheetCancel).setOnClickListener(v -> dismiss());
        move.setOnClickListener(v -> {
            if (to != Sources.all().indexOf(provider)) {
                Bundle result = new Bundle();
                result.putInt(KEY_POSITION, to);
                getParentFragmentManager().setFragmentResult(RESULT, result);
            }
            dismiss();
        });
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        render();
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
        Dialog dialog = getDialog();
        if (dialog instanceof BottomSheetDialog) {
            // all of it at once: the preview below the slider is the point
            BottomSheetBehavior<?> behavior = ((BottomSheetDialog) dialog).getBehavior();
            behavior.setSkipCollapsed(true);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TO, to);
    }

    @Override
    public void onDestroyView() {
        disposables.clear();
        super.onDestroyView();
    }

    private void pick(int position) {
        to = Math.max(0, Math.min(Sources.all().size() - 1, position));
        render();
    }

    private void render() {
        if (getView() == null) {
            return;
        }
        Context context = requireContext();
        List<Provider> order = Sources.all();
        int active = Sources.activeCount();
        int from = order.indexOf(provider);
        to = Math.max(0, Math.min(order.size() - 1, to));
        Preview preview = Preview.of(order, active, provider, to);

        subtitle.setText(getString(R.string.sources_sheet_subtitle, provider.title, from + 1, active,
                Sources.MAX_ACTIVE));
        value.setText(getString(R.string.sources_sheet_value, to + 1));
        of.setText(getString(R.string.sources_sheet_of, order.size()));
        if (Math.round(slider.getValue()) != to + 1) {
            slider.setValue(to + 1);
        }
        up.setEnabled(to > 0);
        down.setEnabled(to < order.size() - 1);
        move.setEnabled(to != from);

        int[] cells = new int[preview.order.size()];
        for (int i = 0; i < cells.length; i++) {
            int color = SourceTexts.accent(context, infos.get(preview.order.get(i)));
            cells[i] = i < active ? color : ColorUtils.setAlphaComponent(color, 0x59);
        }
        strip.setCells(cells, active, preview.order.indexOf(provider));
        strip.setContentDescription(getString(R.string.sources_sheet_strip, to + 1, order.size(), active));

        changesTitle.setVisibility(to == from ? View.GONE : View.VISIBLE);
        changes.setText(to == from ? getString(R.string.sources_sheet_none) : lines(order, from, preview));
        changes.setTextColor(ContextCompat.getColor(context, to == from ? R.color.sources_text_secondary
                : R.color.sources_text_primary));
    }

    /** "Что изменится", line by line: the site itself, the ones that shift, each one that crosses the line. */
    @NonNull
    private String lines(@NonNull List<Provider> order, int from, @NonNull Preview preview) {
        List<String> lines = new ArrayList<>();
        String me = getString(R.string.sources_sheet_me, provider.title, from + 1, to + 1);
        if (preview.active != preview.wasActive) {
            me += getString(preview.active ? R.string.sources_sheet_me_on : R.string.sources_sheet_me_off);
        }
        lines.add(me);
        if (!preview.shifted.isEmpty()) {
            List<String> names = new ArrayList<>();
            List<Integer> places = new ArrayList<>();
            for (Provider site : preview.shifted) {
                names.add(site.title);
                places.add(order.indexOf(site) + 1);
            }
            int first = Collections.min(places);
            int last = Collections.max(places);
            String range = first == last ? getString(R.string.sources_sheet_value, first)
                    : getString(R.string.sources_sheet_range, first, last);
            boolean one = names.size() == 1;
            int words = to < from ? (one ? R.string.sources_sheet_down_one : R.string.sources_sheet_down)
                    : (one ? R.string.sources_sheet_up_one : R.string.sources_sheet_up);
            lines.add(getString(words, range, SourceTexts.names(names, getString(R.string.sources_names_more))));
        }
        for (Provider site : preview.activated) {
            lines.add(getString(R.string.sources_sheet_flip_on, site.title, order.indexOf(site) + 1,
                    preview.order.indexOf(site) + 1));
        }
        for (Provider site : preview.deactivated) {
            lines.add(getString(R.string.sources_sheet_flip_off, site.title, order.indexOf(site) + 1,
                    preview.order.indexOf(site) + 1));
        }
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            text.append(text.length() == 0 ? "" : "\n").append("• ").append(line);
        }
        return text.toString();
    }
}
