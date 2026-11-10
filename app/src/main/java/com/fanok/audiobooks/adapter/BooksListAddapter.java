package com.fanok.audiobooks.adapter;

import static android.content.Context.MODE_PRIVATE;
import static android.content.Context.UI_MODE_SERVICE;
import static com.fanok.audiobooks.activity.ParentalControlActivity.PARENTAL_CONTROL_PREFERENCES;

import android.annotation.SuppressLint;
import android.app.UiModeManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadIndex;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.fanok.audiobooks.App;
import com.fanok.audiobooks.Consts;
import com.fanok.audiobooks.FontScale;
import com.fanok.audiobooks.R;
import com.fanok.audiobooks.Url;
import com.fanok.audiobooks.model.AudioDBModel;
import com.fanok.audiobooks.model.AudioListDBModel;
import com.fanok.audiobooks.model.BooksDBModel;
import com.fanok.audiobooks.model.ListeningProgress;
import com.fanok.audiobooks.pojo.AudioListPOJO;
import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.policy.DefaultCover;
import com.fanok.audiobooks.policy.EmbeddedCover;
import com.fanok.audiobooks.search.Provider;
import com.fanok.audiobooks.util.DownloadUtil;
import com.github.lzyzsd.circleprogress.DonutProgress;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** @noinspection ClassEscapesDefinedScope*/
public class BooksListAddapter extends RecyclerView.Adapter<BooksListAddapter.MyHolder> implements ListFocus.Keyed {

    private ArrayList<BookPOJO> mModel;

    private OnListItemSelectedInterface mListener;
    private OnListItemLongSelectedInterface mLongListener;
    private AudioListDBModel mAudioListDBModel;
    private AudioDBModel mAudioDBModel;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final android.os.Handler mainHandler = new android.os.Handler(Looper.getMainLooper());
    private final java.util.Map<String, Integer> progressCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Integer> downloadCache = new java.util.concurrent.ConcurrentHashMap<>();
    /** Source captions that replace the site's name, by book URL ("5 сайтов" for a group in the search). */
    private java.util.Map<String, String> mSourceLabels = java.util.Collections.emptyMap();

    /** Leading and trailing white space, Unicode spaces included (String.trim() keeps U+00A0, U+2003). */
    private static final java.util.regex.Pattern EDGE_SPACES = java.util.regex.Pattern.compile("^[\\s\\p{Z}]+|[\\s\\p{Z}]+$");
    /** Lines shown in place of the genre, by book URL ("3 озвучки · совпал чтец"); the genre itself stays the book's. */
    private java.util.Map<String, String> mNotes = java.util.Collections.emptyMap();
    /** A row that stands for several books (a group in the search): their URLs, whose best marks it shows. */
    private java.util.Map<String, java.util.List<String>> mAliases = java.util.Collections.emptyMap();
    private static final Object STATUS_CHANGED = new Object();
    /** A row's cover was read from its audio: only the picture is set again. */
    private static final Object COVER = new Object();
    /** The list while in a window, and its screen's lifecycle (null: not found). */
    @Nullable
    private RecyclerView mList;
    @Nullable
    private Lifecycle mHost;
    /** The rows ask only while the screen is started: one on top (the book's) leaves the list in its window. */
    private final LifecycleEventObserver mHostState = (owner, event) -> {
        if (event == Lifecycle.Event.ON_STOP && mList != null) {
            rows(mList, false);
        } else if (event == Lifecycle.Event.ON_START) {
            notifyItemRangeChanged(0, getItemCount(), COVER); // what was read meanwhile; they ask again
        }
    };

    {
        // a row moved (DiffUtil) waits at its new place; told after the list (observers are told last first)
        registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onItemRangeMoved(int fromPosition, int toPosition, int itemCount) {
                if (mList != null) {
                    rows(mList, true);
                }
            }
        });
    }

    /** A cover read anywhere (the book screen, the player, a row) shows at once in this list's rows of the book. */
    private final EmbeddedCover.Listener mCovers = (key, cover) -> {
        for (int i = 0; mModel != null && i < mModel.size(); i++) {
            if (EmbeddedCover.isOf(key, mModel.get(i).getUrl())) {
                notifyItemChanged(i, COVER);
            }
        }
    };

    /** Listens while the list is in a window, so the static listener set never holds a list that is gone. */
    private final View.OnAttachStateChangeListener mWindow = new View.OnAttachStateChangeListener() {
        @Override
        public void onViewAttachedToWindow(@NonNull View view) {
            EmbeddedCover.listen(mCovers);
            watch((RecyclerView) view);
            notifyItemRangeChanged(0, getItemCount(), COVER); // what was read while it was away; asks again
        }

        @Override
        public void onViewDetachedFromWindow(@NonNull View view) {
            EmbeddedCover.unlisten(mCovers);
            watch(null);
            rows((RecyclerView) view, false);
        }
    };

    @UnstableApi
    class MyHolder extends RecyclerView.ViewHolder {

        private final TextView mArtist;

        private final TextView mAutor;

        /** The list's author | reader line, which fits the names itself; null in the big list's layout. */
        private final FairRow mNames;

        private final TextView mComents;

        private final DonutProgress mDonutProgress;

        /** The percent with its "listened" label inside the ring; null where the ring draws the percent (TV). */
        private final View mListened;

        private final TextView mListenedPercent;

        private final TextView mGenre;

        private final ImageView mImageView;

        private final ImageView mIsDownload;

        private final SharedPreferences mPreferences;

        private final TextView mReting;

        private final TextView mSiresle;

        private final TextView mSource;

        private final TextView mTime;

        private final TextView mTitle;

        /** The book whose cover this row waits for, showing the default one meanwhile; null when it shows its own. */
        private String mCoverOf;
        /** The book this row asked for while in the list, until it has its cover, shows another or leaves. */
        private String mAsked;


        @UnstableApi
        MyHolder(@NonNull final View itemView) {
            super(itemView);

            mIsDownload = itemView.findViewById(R.id.is_download);
            mImageView = itemView.findViewById(R.id.imageView);
            mTitle = itemView.findViewById(R.id.title);
            mGenre = itemView.findViewById(R.id.genre);
            mReting = itemView.findViewById(R.id.reting);
            mComents = itemView.findViewById(R.id.coments);
            mSiresle = itemView.findViewById(R.id.series);
            mTime = itemView.findViewById(R.id.time);
            mAutor = itemView.findViewById(R.id.autor);
            mArtist = itemView.findViewById(R.id.artist);
            mNames = itemView.findViewById(R.id.names);
            LinearLayout linearLayout = itemView.findViewById(R.id.contentConteiner);
            mSource = itemView.findViewById(R.id.source);
            mDonutProgress = itemView.findViewById(R.id.donutProgress);
            mListened = itemView.findViewById(R.id.listened);
            mListenedPercent = itemView.findViewById(R.id.listenedPercent);
            mPreferences = mArtist.getContext().getSharedPreferences(PARENTAL_CONTROL_PREFERENCES,
                    MODE_PRIVATE);
            // a tap while the list is being rebound has no position: ignore it rather than index with -1
            linearLayout.setOnClickListener(view -> {
                int position = getBindingAdapterPosition();
                if (mListener != null && position != RecyclerView.NO_POSITION) {
                    mListener.onItemSelected(view, position);
                }
            });


            linearLayout.setOnLongClickListener(view -> {
                int position = getBindingAdapterPosition();
                if (mLongListener != null && position != RecyclerView.NO_POSITION) {
                    mLongListener.onItemLongSelected(view, position);
                }
                return true;
            });

            mAudioDBModel = new AudioDBModel(itemView.getContext().getApplicationContext());
            mAudioListDBModel = new AudioListDBModel(itemView.getContext().getApplicationContext());

        }

        void bind(BookPOJO book) {
            if (book.getName() == null || book.getUrl() == null) {
                throw new NullPointerException();
            }

            bindCover(book);

            String label = mSourceLabels.get(book.getUrl());
            Provider provider = Provider.ofUrl(book.getUrl());
            if (label != null) {
                mSource.setText(label);
                mSource.setVisibility(View.VISIBLE);
            } else if (provider != null) {
                mSource.setText(provider.title);
                mSource.setVisibility(View.VISIBLE);
            } else {
                mSource.setVisibility(View.GONE);
            }


            String titleStr = book.getName();
            if (titleStr != null) {
                mTitle.setText(titleStr.trim().replace("\n", " "));
            } else {
                mTitle.setText("");
            }

            String note = mNotes.get(book.getUrl());
            String genreLine = note != null ? note : book.getGenre();
            if (genreLine != null && !genreLine.isEmpty()) {
                mGenre.setVisibility(View.VISIBLE);
                mGenre.setText(genreLine);
            } else {
                mGenre.setVisibility(View.GONE);
            }
            String retingStr = book.getReting();
            if (retingStr != null && !retingStr.equals("0")) {
                mReting.setText(retingStr);
                mReting.setVisibility(View.VISIBLE);
            } else {
                mReting.setVisibility(View.GONE);
            }

            if (book.getComents() != 0) {
                mComents.setText(String.valueOf(book.getComents()));
                mComents.setVisibility(View.VISIBLE);
            } else {
                mComents.setVisibility(View.GONE);
            }

            if (mNames != null) {
                mNames.setNames(book.getAutor(), book.getArtist());
            } else {
                if (book.getAutor() != null && !book.getAutor().isEmpty()) {
                    mAutor.setText(book.getAutor());
                    mAutor.setVisibility(View.VISIBLE);
                } else {
                    mAutor.setVisibility(View.GONE);
                }
                // readers come with trailing spaces (an em space too), which would keep the reader off the row's end
                mArtist.setText(book.getArtist() == null ? null
                        : EDGE_SPACES.matcher(book.getArtist()).replaceAll(""));
            }
            if (book.getTime() != null && !book.getTime().isEmpty()) {
                mTime.setText(Consts.formatTimeDisplay(book.getTime()));
                mTime.setVisibility(View.VISIBLE);
            } else {
                mTime.setVisibility(View.GONE);
            }
            if (book.getSeries() != null && !book.getSeries().isEmpty()) {
                mSiresle.setText(book.getSeries()); // its book icon is the layout's drawableStart, as for the author
                mSiresle.setVisibility(View.VISIBLE);
            } else {
                mSiresle.setVisibility(View.GONE);
            }

            if (procent) {
                Integer pct = best(progressCache, book.getUrl());
                if (pct == null || pct == -1) {
                    setVisible(false);
                } else {
                    mDonutProgress.setProgress(pct);
                    mDonutProgress.setText(pct + "%");
                    if (mListenedPercent != null) mListenedPercent.setText(pct + "%");
                    setVisible(true);
                }
            } else {
                setVisible(false);
            }

            boolean isImported = book != null && book.getUrl() != null && book.getUrl().contains("/imported/");
            Integer dlStatus = best(downloadCache, book.getUrl());
            if (isImported || dlStatus == null || dlStatus == 0) {
                mIsDownload.setVisibility(View.GONE);
            } else if (dlStatus == 1) {
                mIsDownload.setImageDrawable(
                        ContextCompat.getDrawable(mIsDownload.getContext(), R.drawable.ic_check_1));
                mIsDownload.setContentDescription(mIsDownload.getContext().getString(R.string.download_state_partial));
                mIsDownload.setVisibility(View.VISIBLE);
            } else {
                mIsDownload.setImageDrawable(
                        ContextCompat.getDrawable(mIsDownload.getContext(), R.drawable.ic_check_all));
                mIsDownload.setContentDescription(mIsDownload.getContext().getString(R.string.download_state_full));
                mIsDownload.setVisibility(View.VISIBLE);
            }


        }

        /**
         * The row's cover. A book of a site whose covers are never requested (audioboo) shows the one read from its
         * audio: from memory, else its file, which Glide decodes off the main thread; while there is none, the default
         * cover shows and the row, while in the list, asks for it ({@link #ask}).
         */
        void bindCover(BookPOJO book) {
            String wanted = null;
            String photo = book.getPhoto();
            Drawable mask = com.fanok.audiobooks.policy.ParentalControl.coverMask(mImageView.getContext(), book);
            if (mask != null) {
                Glide.with(mImageView).load(mask).into(mImageView);
            } else if (DefaultCover.replaces(photo)
                    || (photo == null || photo.isEmpty()) && DefaultCover.replaces(book.getUrl())) {
                Drawable gradient = DefaultCover.gradient(mImageView.getContext(), book.getUrl());
                Bitmap read = EmbeddedCover.memory(book.getUrl());
                File saved = read == null ? EmbeddedCover.saved(mImageView.getContext(), book.getUrl()) : null;
                RequestManager glide = Glide.with(mImageView);
                (read != null ? glide.load(read) : saved != null ? glide.load(saved) : glide.load(gradient))
                        .placeholder(gradient).into(mImageView);
                if (read == null && saved == null) {
                    wanted = book.getUrl();
                }
            } else if (photo != null && !photo.isEmpty()) {
                if (photo.startsWith("content://")) {
                    Glide.with(mImageView).load(android.net.Uri.parse(photo)).into(mImageView);
                } else if (photo.startsWith("/") || photo.contains(":\\")) {
                    Glide.with(mImageView).load(new java.io.File(photo)).into(mImageView);
                } else {
                    Glide.with(mImageView).load(com.fanok.audiobooks.net.Covers.glide(photo)).into(mImageView);
                }
            } else {
                mImageView.setImageDrawable(
                        ContextCompat.getDrawable(mImageView.getContext(), android.R.drawable.ic_menu_gallery));
            }
            mCoverOf = wanted;
            ask(); // rebound in the list (a payload, a status): asks again, or no more
        }

        /**
         * In the list (attached) while its screen is started, the row asks for its book's cover while it waits for it,
         * at its position: the topmost goes first ({@link EmbeddedCover#request}); prefetched or cached off screen, it
         * does not.
         */
        void ask() {
            if (mAsked != null && !mAsked.equals(mCoverOf)) {
                leaveCover();
            }
            if (mCoverOf != null && itemView.isAttachedToWindow()
                    && (mHost == null || mHost.getCurrentState().isAtLeast(Lifecycle.State.STARTED))) {
                int at = getBindingAdapterPosition(); // with the changes not laid out yet
                at = at == RecyclerView.NO_POSITION ? getLayoutPosition() : at;
                if (mAsked == null) {
                    EmbeddedCover.request(mAsked = mCoverOf, at);
                } else {
                    EmbeddedCover.move(mAsked, at);
                }
            }
        }

        /** The row asks no more: it has its cover, shows another book, left the list or its screen stopped. */
        void leaveCover() {
            if (mAsked != null) {
                EmbeddedCover.forget(mAsked);
                mAsked = null;
            }
        }

        private void setVisible(boolean b) {
            if (mListened != null) mListened.setVisibility(b ? View.VISIBLE : View.GONE);
            if (b) {
                mImageView.setColorFilter(
                        ContextCompat.getColor(mImageView.getContext(), R.color.imageTint));
                mDonutProgress.setVisibility(View.VISIBLE);
            } else {
                mDonutProgress.setVisibility(View.GONE);
                mImageView.setColorFilter(ContextCompat.getColor(mImageView.getContext(),
                        android.R.color.transparent));
            }
        }
    }


    public interface OnListItemLongSelectedInterface {

        void onItemLongSelected(View view, int position);
    }

    public interface OnListItemSelectedInterface {

        void onItemSelected(View view, int position);
    }

    private final boolean procent;

    public BooksListAddapter() {
        procent = false;
    }

    public BooksListAddapter(boolean showProcent) {
        this.procent = showProcent;
    }

    @SuppressLint("NotifyDataSetChanged")
    public void clearItem() {
        if (mModel != null) {
            mModel = new ArrayList<>();
            notifyDataSetChanged();
        }
    }

    public void close() {
        if (mAudioListDBModel != null) {
            mAudioListDBModel.closeDB();
        }
        if (mAudioDBModel != null) {
            mAudioDBModel.closeDB();
        }
    }

    @Override
    public int getItemCount() {
        if (mModel == null) {
            return 0;
        } else {
            return mModel.size();
        }
    }

    /** The book shown at {@code position}, or null when the position is no longer in the list. */
    public BookPOJO getItem(int position) {
        return mModel == null || position < 0 || position >= mModel.size() ? null : mModel.get(position);
    }

    @Nullable
    @Override
    public String keyAt(int position) {
        BookPOJO book = getItem(position);
        return book == null ? null : book.getUrl();
    }

    @Override
    public int positionOf(@NonNull String key) {
        for (int i = 0; mModel != null && i < mModel.size(); i++) {
            if (key.equals(mModel.get(i).getUrl())) {
                return i;
            }
        }
        return -1;
    }

    @UnstableApi
    @Override
    public void onBindViewHolder(@NonNull MyHolder myHolder, int i) {
        myHolder.bind(mModel.get(i));
    }

    @UnstableApi
    @Override
    public void onBindViewHolder(@NonNull MyHolder myHolder, int i, @NonNull List<Object> payloads) {
        if (!payloads.isEmpty() && Collections.frequency(payloads, COVER) == payloads.size()) {
            myHolder.bindCover(mModel.get(i)); // the rest of the row, and the remote's focus on it, stay
        } else {
            super.onBindViewHolder(myHolder, i, payloads);
        }
    }

    /** A row came into the list (shown): it asks for its cover, if it waits for one. */
    @OptIn(markerClass = UnstableApi.class)
    @Override
    public void onViewAttachedToWindow(@NonNull MyHolder holder) {
        super.onViewAttachedToWindow(holder);
        holder.ask();
    }

    /** A row left the list (scrolled off, cached or recycled): it asks no more. */
    @OptIn(markerClass = UnstableApi.class)
    @Override
    public void onViewDetachedFromWindow(@NonNull MyHolder holder) {
        super.onViewDetachedFromWindow(holder);
        holder.leaveCover();
    }

    /** The list's rows ask again ({@code ask}), or none does (it left its window, this adapter or its screen stopped). */
    @OptIn(markerClass = UnstableApi.class)
    private void rows(RecyclerView list, boolean ask) {
        for (int i = 0; i < list.getChildCount(); i++) {
            RecyclerView.ViewHolder holder = list.getChildViewHolder(list.getChildAt(i));
            if (holder instanceof MyHolder) {
                if (ask) {
                    ((MyHolder) holder).ask();
                } else {
                    ((MyHolder) holder).leaveCover();
                }
            }
        }
    }

    /** Follows the screen of {@code list} (its context, unwrapped to the activity), or none (null). */
    private void watch(@Nullable RecyclerView list) {
        if (mHost != null) {
            mHost.removeObserver(mHostState);
        }
        Context context = list == null ? null : list.getContext();
        while (context instanceof ContextWrapper && !(context instanceof LifecycleOwner)) {
            context = ((ContextWrapper) context).getBaseContext();
        }
        mList = list;
        mHost = context instanceof LifecycleOwner ? ((LifecycleOwner) context).getLifecycle() : null;
        if (mHost != null) {
            mHost.addObserver(mHostState); // started, it is told ON_START at once
        }
    }

    @UnstableApi
    @NonNull
    @Override
    public MyHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int i) {
        Context context = viewGroup.getContext();
        String listType = PreferenceManager.getDefaultSharedPreferences(context).getString(
                "books_adapter_layout_pref", context.getString(R.string.books_adapter_layout_list_value));
        boolean medium = listType.equals(context.getString(R.string.books_adapter_layout_medium_list_value));
        boolean big = medium || listType.equals(context.getString(R.string.books_adapter_layout_big_list_value));
        View view = LayoutInflater.from(context).inflate(
                big ? R.layout.books_recycler_item_big : R.layout.books_recycler_item, viewGroup, false);
        if (medium) {
            medium(view);
        } else {
            // the row's fixed height holds larger text: the row and its cover grow with the font scale
            FontScale.grow(view, view.findViewById(R.id.imageView), view.findViewById(R.id.donutProgress));
        }
        return new MyHolder(view);
    }

    /**
     * The medium list: the big list's row as tall as its text or its cover, whichever is taller. The cover, in the big
     * one's proportions, is as tall as six lines of the text (a one-line title, the genre, series, author and reader
     * in the genre's size, and the duration) and in the row's middle. The free space is above the last line
     * (duration and source), which so keeps to the bottom as on the other cards, not above the series.
     */
    private static void medium(View row) {
        row.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
        int height = 5 * lineHeight(row.findViewById(R.id.genre)) + lineHeight(row.findViewById(R.id.time));
        int width = height * row.getResources().getDimensionPixelSize(R.dimen.recycler_item_image_width_big)
                / row.getResources().getDimensionPixelSize(R.dimen.recycler_item_height_big);
        View cover = row.findViewById(R.id.imageView);
        cover.getLayoutParams().width = width;
        cover.getLayoutParams().height = height;
        ((RelativeLayout.LayoutParams) cover.getLayoutParams()).addRule(RelativeLayout.CENTER_VERTICAL);
        // a wrap_content LinearLayout counts its match_parent children only when all are: so is the cover's column
        // as the text's, and the row takes the text's height when that is the taller
        ((View) cover.getParent()).getLayoutParams().height = ViewGroup.LayoutParams.MATCH_PARENT;
        ViewGroup.LayoutParams ring = row.findViewById(R.id.donutProgress).getLayoutParams();
        ring.width = width;
        ring.height = width;
        View gap = row.findViewById(R.id.gap);
        ViewGroup column = (ViewGroup) gap.getParent();
        column.removeView(gap);
        column.addView(gap, column.getChildCount() - 1);
    }

    /** A one-line text's height, with its font padding and icon. */
    private static int lineHeight(View text) {
        text.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        return text.getMeasuredHeight();
    }

    /**
     * A new list. On a TV a list already shown changes by URL (a page more is an insertion, a book moved in the
     * history a move), so the rows that stay are not rebuilt and the remote's focus stays on its row; a full rebuild
     * would remove the focused row and the focus would go to the side menu. Touch devices rebuild, as before.
     */
    @SuppressLint("NotifyDataSetChanged")
    public void setItem(ArrayList<BookPOJO> newModel) {
        final ArrayList<BookPOJO> before = mModel;
        // a copy: the presenter keeps appending to its own list, the adapter must not see that
        mModel = newModel == null ? new ArrayList<>() : new ArrayList<>(newModel);
        preloadStatuses(mModel);
        if (before == null || before.isEmpty() || !ListFocus.isTv()) {
            notifyDataSetChanged();
        } else {
            diffByUrl(before, mModel, mSourceLabels, mSourceLabels, mNotes, mNotes).dispatchUpdatesTo(this);
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    public void refreshItem() {
        notifyDataSetChanged();
    }

    /**
     * For a list that changes while results arrive (the search): rows are matched by URL, so only what changed moves
     * and the rest keeps its place and focus; {@code labels} replace the site's name by URL; progress and download
     * marks load for books not seen before only.
     */
    public void submit(ArrayList<BookPOJO> items, java.util.Map<String, String> labels,
            java.util.Map<String, String> notes, java.util.Map<String, java.util.List<String>> aliases) {
        final ArrayList<BookPOJO> before = mModel == null ? new ArrayList<>() : mModel;
        final ArrayList<BookPOJO> after = new ArrayList<>(items);
        final java.util.Map<String, String> labelsBefore = mSourceLabels;
        final java.util.Map<String, String> labelsAfter = labels == null
                ? java.util.Collections.<String, String>emptyMap() : new java.util.HashMap<>(labels);
        final java.util.Map<String, String> notesBefore = mNotes;
        final java.util.Map<String, String> notesAfter = notes == null
                ? java.util.Collections.<String, String>emptyMap() : new java.util.HashMap<>(notes);
        androidx.recyclerview.widget.DiffUtil.DiffResult diff = diffByUrl(before, after, labelsBefore, labelsAfter,
                notesBefore, notesAfter);
        mModel = after;
        mSourceLabels = labelsAfter;
        mNotes = notesAfter;
        mAliases = aliases == null ? java.util.Collections.<String, java.util.List<String>>emptyMap()
                : new java.util.HashMap<>(aliases);
        diff.dispatchUpdatesTo(this);
        ArrayList<BookPOJO> unseen = new ArrayList<>();
        for (String url : statusUrls(after)) {
            if (!downloadCache.containsKey(url)) {
                unseen.add(byUrl(url));
            }
        }
        if (!unseen.isEmpty()) {
            preloadStatuses(unseen);
        }
    }

    /** Loads the progress and download marks of every row again (the listener may have listened or downloaded). */
    public void reloadStatuses() {
        if (mModel == null || mModel.isEmpty()) {
            return;
        }
        ArrayList<BookPOJO> all = new ArrayList<>();
        for (String url : statusUrls(mModel)) {
            all.add(byUrl(url));
        }
        preloadStatuses(all);
    }

    /** The URLs whose marks the rows show: a row's own, or those of the books it stands for. */
    private java.util.Set<String> statusUrls(java.util.List<BookPOJO> rows) {
        java.util.Set<String> urls = new java.util.LinkedHashSet<>();
        for (BookPOJO b : rows) {
            if (b.getUrl() == null) {
                continue;
            }
            java.util.List<String> alias = mAliases.get(b.getUrl());
            urls.addAll(alias != null ? alias : java.util.Collections.singletonList(b.getUrl()));
        }
        return urls;
    }

    private static BookPOJO byUrl(String url) {
        BookPOJO b = new BookPOJO();
        b.setUrl(url);
        return b;
    }

    /** The best mark among the books a row stands for: the most listened, the most downloaded. */
    private Integer best(java.util.Map<String, Integer> cache, String url) {
        java.util.List<String> alias = mAliases.get(url);
        if (alias == null) {
            return cache.get(url);
        }
        Integer best = null;
        for (String u : alias) {
            Integer v = cache.get(u);
            if (v != null && (best == null || v > best)) {
                best = v;
            }
        }
        return best;
    }

    /** Rows matched by URL; a row changes when its card, its source caption or its note does. */
    private static androidx.recyclerview.widget.DiffUtil.DiffResult diffByUrl(final ArrayList<BookPOJO> before,
            final ArrayList<BookPOJO> after, final java.util.Map<String, String> labelsBefore,
            final java.util.Map<String, String> labelsAfter, final java.util.Map<String, String> notesBefore,
            final java.util.Map<String, String> notesAfter) {
        return androidx.recyclerview.widget.DiffUtil.calculateDiff(new androidx.recyclerview.widget.DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return before.size();
            }

            @Override
            public int getNewListSize() {
                return after.size();
            }

            @Override
            public boolean areItemsTheSame(int o, int n) {
                return java.util.Objects.equals(before.get(o).getUrl(), after.get(n).getUrl());
            }

            @Override
            public boolean areContentsTheSame(int o, int n) {
                BookPOJO a = before.get(o);
                BookPOJO b = after.get(n);
                return sameCard(a, b) && java.util.Objects.equals(labelsBefore.get(a.getUrl()), labelsAfter.get(b.getUrl()))
                        && java.util.Objects.equals(notesBefore.get(a.getUrl()), notesAfter.get(b.getUrl()));
            }
        });
    }

    private static boolean sameCard(BookPOJO a, BookPOJO b) {
        return java.util.Objects.equals(a.getName(), b.getName()) && java.util.Objects.equals(a.getAutor(), b.getAutor())
                && java.util.Objects.equals(a.getArtist(), b.getArtist())
                && java.util.Objects.equals(a.getTime(), b.getTime())
                && java.util.Objects.equals(a.getGenre(), b.getGenre())
                && java.util.Objects.equals(a.getPhoto(), b.getPhoto())
                && java.util.Objects.equals(a.getSeries(), b.getSeries())
                && java.util.Objects.equals(a.getReting(), b.getReting()) && a.getComents() == b.getComents();
    }

    @OptIn(markerClass = UnstableApi.class)
    private void preloadStatuses(ArrayList<BookPOJO> newModel) {
        if (newModel == null || newModel.isEmpty()) {
            progressCache.clear();
            downloadCache.clear();
            return;
        }
        executor.execute(() -> {
            BooksDBModel dbModel = null;
            AudioListDBModel audioListModel = null;
            AudioDBModel audioModel = null;
            DownloadIndex downloadIndex = null;
            try {
                Context ctx = App.getInstance();
                dbModel = new BooksDBModel(ctx);
                audioListModel = new AudioListDBModel(ctx);
                audioModel = new AudioDBModel(ctx);
                downloadIndex = DownloadUtil.getDownloadManager().getDownloadIndex();

                for (BookPOJO b : newModel) {
                    String url = b.getUrl();
                    if (url == null) continue;

                    boolean saved = dbModel.inSaved(url);

                    // the history's status filter uses the same percent
                    int progress = procent ? ListeningProgress.of(audioListModel, audioModel, url) : -1;
                    progressCache.put(url, progress);

                    int dl = 0;
                    if (saved) {
                        if (url.contains("/imported/")) {
                            dl = 2;
                        } else if (audioListModel != null) {
                            ArrayList<AudioListPOJO> list = audioListModel.get(url);
                            if (list != null && !list.isEmpty()) {
                                int count = 0;
                                // A chapter counts as downloaded when its row's app-written copy or imported local file
                                // still exists, or when its Media3 download (resolved by chapter identity) is complete.
                                java.util.Set<String> withCopy = new java.util.HashSet<>();
                                for (com.fanok.audiobooks.room.BooksAudioEntity row : audioListModel.getRows(url)) {
                                    if (com.fanok.audiobooks.util.DownloadStorageHelper.localCopyOf(ctx, row) != null) {
                                        withCopy.add(row.urlAudio);
                                    }
                                }
                                java.util.Map<String, Download> downloads = java.util.Collections.emptyMap();
                                if (downloadIndex != null) {
                                    java.util.List<String> urls = new java.util.ArrayList<>();
                                    for (AudioListPOJO pojo : list) {
                                        if (pojo != null && pojo.getAudioUrl() != null) urls.add(pojo.getAudioUrl());
                                    }
                                    try {
                                        downloads = DownloadUtil.downloadsByChapterUrl(ctx, downloadIndex, url, urls);
                                    } catch (Exception ignored) {}
                                }
                                for (AudioListPOJO pojo : list) {
                                    if (pojo == null) continue;
                                    String aUrl = pojo.getAudioUrl();
                                    boolean isTrackDownloaded = withCopy.contains(aUrl);
                                    if (!isTrackDownloaded && aUrl != null && aUrl.startsWith("/")) {
                                        isTrackDownloaded = new java.io.File(aUrl).exists();
                                    }
                                    if (!isTrackDownloaded) {
                                        Download d = downloads.get(aUrl);
                                        isTrackDownloaded = d != null && d.state == Download.STATE_COMPLETED;
                                    }
                                    if (isTrackDownloaded) {
                                        count++;
                                    }
                                }

                                if (count >= list.size() && count > 0) {
                                    dl = 2;
                                } else if (count > 0) {
                                    dl = 1;
                                } else {
                                    dl = 0;
                                }
                            } else {
                                dl = 0;
                            }
                        } else {
                            dl = 0;
                        }
                    } else {
                        dl = 0;
                    }
                    downloadCache.put(url, dl);
                }
            } catch (Exception e) {
                android.util.Log.e("ADAPT", "prefetch error", e);
            } finally {
                if (dbModel != null) dbModel.closeDB();
                if (audioListModel != null) audioListModel.closeDB();
                if (audioModel != null) audioModel.closeDB();
            }
            // a payload rebinds the rows in place: no cross-fade, no lost focus
            mainHandler.post(() -> {
                if (mModel != null) {
                    notifyItemRangeChanged(0, mModel.size(), STATUS_CHANGED);
                }
            });
        });
    }

    public void setListener(
            OnListItemSelectedInterface listener) {
        mListener = listener;
    }

    public void setLongListener(
            OnListItemLongSelectedInterface longListener) {
        mLongListener = longListener;
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        recyclerView.addOnAttachStateChangeListener(mWindow);
        if (recyclerView.isAttachedToWindow()) {
            EmbeddedCover.listen(mCovers);
            watch(recyclerView);
        }
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        recyclerView.removeOnAttachStateChangeListener(mWindow);
        EmbeddedCover.unlisten(mCovers);
        watch(null);
        rows(recyclerView, false);
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }
}
