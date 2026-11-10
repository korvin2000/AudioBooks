package com.fanok.audiobooks.fragment;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ArrayAdapter;
import android.widget.GridView;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import com.fanok.audiobooks.Consts;
import com.fanok.audiobooks.R;
import com.fanok.audiobooks.net.AppProxy;
import com.fanok.audiobooks.search.Provider;
import com.fanok.audiobooks.search.Sources;

import java.util.ArrayList;
import java.util.List;

/**
 * The globe's list in the book listings: the active sources ({@link Sources#active()}) in the user's order, each by its
 * place and, when it goes through the proxy, the shield of the source screens; under them, fixed, the card of the
 * listing's site. A rounded panel in the activity's own theme (its item colour, text and ripple; the accent of the
 * source screens), hung under the toolbar and never over it: a list longer than the room scrolls, opens at the selected
 * source and keeps its scrollbar in view. Where one column would scroll and the window is wide enough for two (a phone
 * on its side, a tablet, a TV), it takes two. The D-pad moves in the list and down to the card; Enter chooses.
 */
public final class SourceMenu {

    public interface Listener {
        /** A source was chosen (the panel is already dismissed). */
        void onSource(@NonNull Provider source);

        /** The footer was chosen (the panel is already dismissed). */
        void onAbout(@NonNull Provider site);
    }

    /** A column's width at the font scale of 100%; it grows with larger text, up to 150%. */
    private static final int COLUMN_DP = 224;
    /** The panel's distance from the toolbar, and from the window's side and bottom. */
    private static final int GAP_DP = 4;
    private static final int MARGIN_DP = 8;
    private static final int ELEVATION_DP = 8;
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private SourceMenu() {
    }

    /**
     * Shows the panel under the toolbar of {@code anchor}, the globe. {@code site}: the listing's site for the footer
     * «Об источнике «…»», null: no footer. The selected source is the one of {@link Consts#getSOURCE()}.
     */
    public static void show(@NonNull View anchor, @Nullable Provider site, @NonNull Listener listener) {
        List<Provider> sources = new ArrayList<>(Sources.active());
        if (sources.isEmpty()) {
            return;
        }
        int selected = -1;
        for (int i = 0; i < sources.size(); i++) {
            if (sources.get(i).sourceId == Consts.getSOURCE()) {
                selected = i;
            }
        }
        Context context = activityOf(anchor.getContext());
        View panel = LayoutInflater.from(context).inflate(R.layout.source_menu_panel, null);
        GridView list = panel.findViewById(R.id.source_menu_list);
        View divider = panel.findViewById(R.id.source_menu_divider);
        View about = panel.findViewById(R.id.source_menu_about);
        Rows rows = new Rows(context, sources, selected);
        list.setAdapter(rows);

        // the room: from just under the toolbar to the window's bottom, within its sides
        float density = context.getResources().getDisplayMetrics().density;
        int margin = Math.round(MARGIN_DP * density);
        Rect frame = new Rect();
        anchor.getWindowVisibleDisplayFrame(frame);
        View bar = toolbarOf(anchor);
        int[] at = new int[2];
        bar.getLocationOnScreen(at);
        int top = at[1] + bar.getHeight() + Math.round(GAP_DP * density);
        int room = frame.bottom - margin - top;
        int across = frame.width() - 2 * margin;
        float scale = Math.max(1f, Math.min(1.5f, context.getResources().getConfiguration().fontScale));
        int column = Math.min(across, Math.round(COLUMN_DP * density * scale));

        // one column while it holds every source; two where it would scroll and the window is wide enough
        int row = measure(rows.getView(0, null, list), column);
        int fixed = list.getPaddingTop() + list.getPaddingBottom() + panel.getPaddingBottom();
        int footer = 0;
        if (site != null) {
            TextView text = panel.findViewById(R.id.source_menu_about_text);
            text.setText(context.getString(R.string.sources_about_listing, site.title));
            footer = measure(divider, column) + measure(about, column);
        } else {
            divider.setVisibility(View.GONE);
            about.setVisibility(View.GONE);
        }
        int columns = sources.size() * row > room - fixed - footer && across >= 2 * column ? 2 : 1;
        int width = columns * column;
        if (columns == 2 && site != null) {
            footer = measure(divider, width) + measure(about, width); // the wider card may take a line less
        }
        int lines = (sources.size() + columns - 1) / columns;
        int height = Math.min(fixed + footer + lines * row, Math.max(room, fixed + footer + row));
        list.setNumColumns(columns);

        PopupWindow popup = new PopupWindow(context); // the platform's popup style: it unfolds from the globe
        popup.setContentView(panel);
        popup.setWidth(width);
        popup.setHeight(height);
        popup.setBackgroundDrawable(ContextCompat.getDrawable(context, R.drawable.source_menu_surface));
        popup.setElevation(ELEVATION_DP * density);
        popup.setOverlapAnchor(false);
        popup.setFocusable(true); // the D-pad and Back
        popup.setOutsideTouchable(true);
        popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        list.setOnItemClickListener((parent, view, position, id) -> {
            popup.dismiss();
            listener.onSource(sources.get(position));
        });
        if (site != null) {
            about.setOnClickListener(v -> {
                popup.dismiss();
                listener.onAbout(site);
            });
        }
        if (selected >= 0) {
            list.setItemChecked(selected, true);
            list.setSelection(Math.max(0, selected / columns - 2) * columns); // two rows above it stay in view
        }

        anchor.getLocationOnScreen(at);
        int end = at[0] + anchor.getWidth();
        boolean rtl = anchor.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        int dx = rtl ? 0 : Math.min(end, frame.right - margin) - end; // its end 8 dp in from the window's edge
        popup.showAsDropDown(anchor, dx, top - at[1] - anchor.getHeight(), Gravity.END);
        if (!anchor.isInTouchMode()) {
            // the remote's highlight moves onto the selected source, where the first layout left it in view
            int current = Math.max(selected, 0);
            list.post(() -> {
                list.requestFocus();
                list.setSelection(current);
            });
        }
    }

    /** The activity of {@code context}: its theme colours the panel, not the toolbar's dark overlay. */
    @NonNull
    private static Context activityOf(@NonNull Context context) {
        for (Context c = context; c instanceof ContextWrapper; c = ((ContextWrapper) c).getBaseContext()) {
            if (c instanceof Activity) {
                return c;
            }
        }
        return context;
    }

    /** The toolbar that holds {@code anchor}, or the anchor itself. */
    @NonNull
    private static View toolbarOf(@NonNull View anchor) {
        for (ViewParent parent = anchor.getParent(); parent instanceof View; parent = parent.getParent()) {
            if (parent instanceof Toolbar) {
                return (View) parent;
            }
        }
        return anchor;
    }

    /** {@code view}'s height at {@code width}, as its parent would measure it, with its vertical margins. */
    private static int measure(@NonNull View view, int width) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        int height = params != null && params.height >= 0
                ? View.MeasureSpec.makeMeasureSpec(params.height, View.MeasureSpec.EXACTLY)
                : View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), height);
        int margins = 0;
        if (params instanceof ViewGroup.MarginLayoutParams) {
            margins = ((ViewGroup.MarginLayoutParams) params).topMargin
                    + ((ViewGroup.MarginLayoutParams) params).bottomMargin;
        }
        return view.getMeasuredHeight() + margins;
    }

    /**
     * The rows. The shield: the source goes "через прокси" ({@link AppProxy#isDirect} false) and a proxy mode is on
     * ({@link SourceTexts#proxyState} not OFF); faded, as on the source list, while no proxy carries it.
     */
    private static final class Rows extends ArrayAdapter<Provider> {
        private final int selected;
        private final SourceTexts.ProxyState proxy;
        @Nullable
        private final String reason;

        Rows(@NonNull Context context, @NonNull List<Provider> sources, int selected) {
            super(context, R.layout.source_menu_row, sources);
            this.selected = selected;
            proxy = SourceTexts.proxyState(context);
            reason = SourceTexts.proxyReason(context, proxy);
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View row, @NonNull ViewGroup parent) {
            Context context = getContext();
            if (row == null) {
                row = LayoutInflater.from(context).inflate(R.layout.source_menu_row, parent, false);
            }
            Provider source = getItem(position);
            boolean current = position == selected;
            boolean proxied = proxy != SourceTexts.ProxyState.OFF && source != null && !AppProxy.isDirect(source);
            String title = source == null ? "" : source.title;
            TextView number = row.findViewById(R.id.source_menu_number);
            number.setText(String.valueOf(position + 1));
            number.setTypeface(current ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            TextView name = row.findViewById(R.id.source_menu_title);
            name.setText(title);
            name.setTypeface(current ? MEDIUM : Typeface.DEFAULT);
            View shield = row.findViewById(R.id.source_menu_proxy);
            shield.setVisibility(proxied ? View.VISIBLE : View.GONE);
            shield.setAlpha(proxy == SourceTexts.ProxyState.WORKS ? 1f : 0.4f);
            row.setContentDescription(SourceTexts.spoken(
                    context.getString(R.string.sources_position_short, position + 1), title,
                    current ? context.getString(R.string.sources_badge_catalog) : null,
                    proxied ? context.getString(R.string.sources_route_proxy) : null, proxied ? reason : null));
            return row;
        }
    }
}
