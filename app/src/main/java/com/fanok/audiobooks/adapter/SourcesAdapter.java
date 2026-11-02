package com.fanok.audiobooks.adapter;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.widget.ImageViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.fanok.audiobooks.R;
import com.fanok.audiobooks.fragment.SourceSegmentsView;
import com.fanok.audiobooks.fragment.SourceTexts;
import com.fanok.audiobooks.net.AppProxy;
import com.fanok.audiobooks.region.Region;
import com.fanok.audiobooks.search.Provider;
import com.fanok.audiobooks.search.SourceInfo;
import com.fanok.audiobooks.search.SourceProbe;
import com.fanok.audiobooks.search.Sources;
import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The rows of the source list ({@code SourcesFragment}): every site in the user's order under a header for the active
 * and one for the inactive ones, or one flat list sorted by books or by response, each row keeping its place number. A
 * row shows the site's icon, title, badges, book count, last check and route; the order, the active count, the catalog
 * source and the routes are read live at each bind, the rest comes with {@link #show}.
 */
public class SourcesAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    /** How the list is sorted: only the view, never the user's order. */
    public enum Order { USER, BOOKS, RESPONSE }

    public interface Listener {
        /** A row was tapped: the site's card. */
        void onOpen(@NonNull Provider provider);

        /** A row was long-pressed (the D-pad and TalkBack path too): the row's menu at {@code anchor}. */
        void onMenu(@NonNull Provider provider, @NonNull View anchor);
    }

    /** The header over a section of the user's order: the active sites, or the inactive ones. */
    public static final class Header {
        public final boolean active;
        public final int count;

        Header(boolean active, int count) {
            this.active = active;
            this.count = count;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Header && ((Header) o).active == active && ((Header) o).count == count;
        }

        @Override
        public int hashCode() {
            return (active ? 1 : 0) * 31 + count;
        }
    }

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ROW = 1;
    /** An inactive site's row, faded like the sources dialog's. */
    private static final float INACTIVE_ALPHA = 0.55f;

    private final Listener listener;
    private final List<Object> rows = new ArrayList<>();
    private Map<Provider, SourceInfo> infos = Collections.emptyMap();
    private Map<Provider, SourceProbe.Result> results = Collections.emptyMap();
    private Set<Provider> checking = Collections.emptySet();
    @Nullable
    private Region region;
    @Nullable
    private Provider selected;

    public SourcesAdapter(@NonNull Listener listener) {
        this.listener = listener;
        setHasStableIds(true);
    }

    /**
     * Shows {@code rows} (from {@link #rows}) with the facts, the last checks, the sites being checked, the user's
     * region and the site whose card is open beside the list (null on a phone).
     */
    public void show(@NonNull List<Object> rows, @NonNull Map<Provider, SourceInfo> infos,
            @NonNull Map<Provider, SourceProbe.Result> results, @NonNull Set<Provider> checking,
            @Nullable Region region, @Nullable Provider selected) {
        this.rows.clear();
        this.rows.addAll(rows);
        this.infos = infos;
        this.results = results;
        this.checking = checking;
        this.region = region;
        this.selected = selected;
        notifyDataSetChanged();
    }

    /**
     * Pure: the rows for {@code sort}. {@link Order#USER}: the {@code active} first sites of {@code order} under a
     * {@link Header}, the rest under another. Otherwise one flat list: by {@code books} (most first, unknown last) or
     * by response (fastest first, then the sites that failed, then the unknown); ties keep the user's order.
     */
    @NonNull
    public static List<Object> rows(@NonNull List<Provider> order, int active, @NonNull Order sort,
            @NonNull Map<Provider, Integer> books, @NonNull Map<Provider, SourceProbe.Result> results) {
        List<Object> rows = new ArrayList<>();
        if (sort == Order.USER) {
            rows.add(new Header(true, active));
            rows.addAll(order.subList(0, active));
            if (active < order.size()) {
                rows.add(new Header(false, order.size() - active));
                rows.addAll(order.subList(active, order.size()));
            }
            return rows;
        }
        List<Provider> sorted = new ArrayList<>(order);
        if (sort == Order.BOOKS) {
            Collections.sort(sorted, (a, b) -> Integer.compare(booksKey(books.get(b)), booksKey(books.get(a))));
        } else {
            Collections.sort(sorted, (a, b) -> Long.compare(responseKey(results.get(a)), responseKey(results.get(b))));
        }
        rows.addAll(sorted);
        return rows;
    }

    private static int booksKey(@Nullable Integer books) {
        return books == null ? -1 : books;
    }

    /** Pure: the response sort key: the time of a site that answered, one with no time, a failure, unknown. */
    static long responseKey(@Nullable SourceProbe.Result result) {
        if (result == null) {
            return Long.MAX_VALUE;
        }
        if (result.isUp()) {
            return result.millis > 0 ? result.millis : Long.MAX_VALUE - 2;
        }
        return Long.MAX_VALUE - 1;
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position) instanceof Header ? TYPE_HEADER : TYPE_ROW;
    }

    @Override
    public long getItemId(int position) {
        Object row = rows.get(position);
        return row instanceof Header ? (((Header) row).active ? -1 : -2) : ((Provider) row).ordinal();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        return viewType == TYPE_HEADER
                ? new HeaderHolder(inflater.inflate(R.layout.item_sources_header, parent, false))
                : new RowHolder(inflater.inflate(R.layout.item_sources_row, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object row = rows.get(position);
        if (holder instanceof HeaderHolder) {
            bindHeader((HeaderHolder) holder, (Header) row);
        } else {
            bindRow((RowHolder) holder, (Provider) row);
        }
    }

    private void bindHeader(@NonNull HeaderHolder holder, @NonNull Header header) {
        Context context = holder.itemView.getContext();
        holder.label.setText(header.active ? R.string.sources_section_active : R.string.sources_section_inactive);
        holder.note.setVisibility(header.active ? View.GONE : View.VISIBLE);
        holder.meter.setVisibility(header.active ? View.VISIBLE : View.GONE);
        if (!header.active) {
            holder.count.setText(String.valueOf(header.count));
            holder.limit.setVisibility(View.GONE);
            return;
        }
        int limit = Math.min(Sources.maxActive(), Provider.values().length);
        holder.count.setText(context.getString(R.string.sources_section_count, header.count, limit));
        holder.limit.setVisibility(header.count >= limit ? View.VISIBLE : View.GONE);
        int accent = ContextCompat.getColor(context, R.color.sources_accent);
        int track = ContextCompat.getColor(context, R.color.sources_track);
        int[] cells = new int[limit];
        for (int i = 0; i < cells.length; i++) {
            cells[i] = i < header.count ? accent : track;
        }
        holder.meter.setCells(cells, -1, -1);
        holder.meter.setContentDescription(holder.count.getText());
    }

    private void bindRow(@NonNull RowHolder holder, @NonNull Provider provider) {
        Context context = holder.itemView.getContext();
        List<Provider> order = Sources.all();
        int index = order.indexOf(provider);
        boolean active = index < Sources.activeCount();
        SourceInfo info = infos.get(provider);
        float density = context.getResources().getDisplayMetrics().density;

        holder.position.setText(String.valueOf(index + 1));
        holder.letter.setText(SourceTexts.letter(provider));
        holder.letter.setBackground(SourceTexts.rounded(SourceTexts.accent(context, info), 9 * density));
        Glide.with(holder.icon).load(SourceInfo.uri(provider, SourceInfo.ICON)).into(holder.icon);
        holder.title.setText(provider.title);
        boolean catalog = provider == Provider.current();
        holder.catalog.setVisibility(catalog ? View.VISIBLE : View.GONE);
        holder.fresh.setVisibility(SourceTexts.isNew(info) ? View.VISIBLE : View.GONE);
        String books = info != null && info.books != null ? SourceTexts.booksLine(context, info.books) : null;
        holder.meta.setText(books);
        holder.meta.setVisibility(books == null ? View.GONE : View.VISIBLE);

        boolean busy = checking.contains(provider);
        SourceProbe.Result result = results.get(provider);
        int color = SourceTexts.statusColor(result);
        String status = SourceTexts.statusText(context, result);
        holder.status.setText(status);
        holder.status.setVisibility(busy ? View.INVISIBLE : View.VISIBLE);
        holder.dot.setVisibility(busy ? View.INVISIBLE : View.VISIBLE);
        holder.dot.setBackgroundTintList(ColorStateList.valueOf(color != 0 ? color
                : ColorUtils.setAlphaComponent(holder.secondary, 0x66)));
        holder.progress.setVisibility(busy ? View.VISIBLE : View.GONE);

        String route = bindRoute(holder, provider, info, context);

        holder.itemView.setAlpha(active ? 1 : INACTIVE_ALPHA);
        holder.itemView.setBackgroundColor(provider == selected
                ? ContextCompat.getColor(context, R.color.sources_selected) : 0);
        holder.itemView.setContentDescription(SourceTexts.spoken(
                context.getString(R.string.sources_position_short, index + 1), provider.title,
                catalog ? context.getString(R.string.sources_badge_catalog) : null,
                active ? null : context.getString(R.string.sources_inactive), books,
                busy ? context.getString(R.string.sources_checking) : status, route));
        holder.itemView.setOnClickListener(v -> listener.onOpen(provider));
        holder.itemView.setOnLongClickListener(v -> {
            listener.onMenu(provider, v);
            return true;
        });
    }

    /**
     * The route icon: a shield in the accent through the proxy (faded while the proxy is off), an arrow direct, an amber
     * shield direct where the user's region needs the proxy. Returns its words.
     */
    @NonNull
    private String bindRoute(@NonNull RowHolder holder, @NonNull Provider provider, @Nullable SourceInfo info,
            @NonNull Context context) {
        boolean direct = AppProxy.isDirect(provider);
        int icon;
        int tint;
        int words;
        if (!direct) {
            icon = R.drawable.ic_shield_check;
            tint = ContextCompat.getColor(context, R.color.sources_accent);
            words = R.string.sources_route_proxy;
        } else if (info != null && Boolean.TRUE.equals(info.blockedIn(region))) {
            icon = R.drawable.ic_source_shield_alert;
            tint = ContextCompat.getColor(context, R.color.sources_warn);
            words = R.string.sources_route_needed;
        } else {
            icon = R.drawable.ic_arrow_right;
            tint = holder.secondary;
            words = R.string.sources_route_direct;
        }
        holder.route.setImageResource(icon);
        ImageViewCompat.setImageTintList(holder.route, ColorStateList.valueOf(tint));
        holder.route.setAlpha(!direct && AppProxy.MODE_OFF.equals(AppProxy.mode()) ? 0.4f : 1);
        return context.getString(words);
    }

    static final class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView label;
        final TextView count;
        final TextView limit;
        final TextView note;
        final SourceSegmentsView meter;

        HeaderHolder(@NonNull View itemView) {
            super(itemView);
            label = itemView.findViewById(R.id.sourcesHeaderLabel);
            count = itemView.findViewById(R.id.sourcesHeaderCount);
            limit = itemView.findViewById(R.id.sourcesHeaderLimit);
            note = itemView.findViewById(R.id.sourcesHeaderNote);
            meter = itemView.findViewById(R.id.sourcesHeaderMeter);
        }
    }

    static final class RowHolder extends RecyclerView.ViewHolder {
        final TextView position;
        final TextView letter;
        final ImageView icon;
        final TextView title;
        final TextView catalog;
        final TextView fresh;
        final TextView meta;
        final View dot;
        final TextView status;
        final View progress;
        final ImageView route;
        final int secondary;

        RowHolder(@NonNull View itemView) {
            super(itemView);
            position = itemView.findViewById(R.id.sourceRowPosition);
            letter = itemView.findViewById(R.id.sourceRowLetter);
            icon = itemView.findViewById(R.id.sourceRowIcon);
            title = itemView.findViewById(R.id.sourceRowTitle);
            catalog = itemView.findViewById(R.id.sourceRowCatalog);
            fresh = itemView.findViewById(R.id.sourceRowNew);
            meta = itemView.findViewById(R.id.sourceRowMeta);
            dot = itemView.findViewById(R.id.sourceRowDot);
            status = itemView.findViewById(R.id.sourceRowStatus);
            progress = itemView.findViewById(R.id.sourceRowProgress);
            route = itemView.findViewById(R.id.sourceRowRoute);
            secondary = MaterialColors.getColor(itemView, android.R.attr.textColorSecondary);
        }
    }
}
