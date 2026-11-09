package com.fanok.audiobooks.fragment;

import static android.content.Context.CLIPBOARD_SERVICE;
import static android.content.Context.UI_MODE_SERVICE;

import android.app.UiModeManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.text.Selection;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.PopupMenu;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.arellomobile.mvp.MvpAppCompatFragment;
import com.arellomobile.mvp.presenter.InjectPresenter;
import com.arellomobile.mvp.presenter.ProvidePresenter;
import com.fanok.audiobooks.Consts;
import com.fanok.audiobooks.MarginItemDecoration;
import com.fanok.audiobooks.R;
import com.fanok.audiobooks.activity.BookActivity;
import com.fanok.audiobooks.activity.BookFavorites;
import com.fanok.audiobooks.activity.ImageFullScreenActivity;
import com.fanok.audiobooks.activity.MainActivity;
import com.fanok.audiobooks.activity.SourceActivity;
import com.fanok.audiobooks.adapter.BooksOtherAdapter;
import com.fanok.audiobooks.adapter.ExpandableText;
import com.fanok.audiobooks.interface_pacatge.book_content.Description;
import com.fanok.audiobooks.model.FavoritePeople;
import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.pojo.DescriptionPOJO;
import com.fanok.audiobooks.pojo.OtherArtistPOJO;
import com.fanok.audiobooks.pojo.StorageUtil;
import com.fanok.audiobooks.policy.DefaultCover;
import com.fanok.audiobooks.policy.EmbeddedCover;
import com.fanok.audiobooks.presenter.BookDescriptionPresenter;
import com.fanok.audiobooks.presenter.BookPresenter;
import com.fanok.audiobooks.search.GenrePicks;
import com.fanok.audiobooks.search.Provider;
import com.fanok.audiobooks.service.Checkpoint;
import com.fanok.audiobooks.service.MediaPlayerService;
import com.fanok.audiobooks.util.BookHeader;
import com.fanok.audiobooks.util.BookProgress;
import com.fanok.audiobooks.util.RemainingTime;
import com.fanok.audiobooks.util.SeriesPeek;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.snackbar.Snackbar;
import com.google.gson.Gson;
import com.squareup.picasso.Picasso;
import java.util.ArrayList;
import java.util.List;

public class DescriptionBookFragment extends MvpAppCompatFragment
        implements Description, BookProgress.Listener, BookFavorites.Listener {

    private static final String TAG = "DescriptionBookFragment";
    private static final String ARG_BOOK_POJO = "arg_book_pojo";
    private static final int MAX_LINES = 4;

    private TextView mTitle;
    private ImageView mImageView;
    /** The default cover is shown: the one read from the book's audio replaces it when it comes. */
    private boolean mDefaultCoverShown;
    private final EmbeddedCover.Listener mEmbeddedCover = (key, cover) -> {
        if (mDefaultCoverShown && mImageView != null && EmbeddedCover.isOf(key, bookUrl())) {
            mImageView.setImageBitmap(cover);
        }
    };
    private TextView mReting;
    private TextView mGenre;
    private TextView mTime;
    private TextView mAuthor;
    private TextView mArtist;
    private TextView mSeries;
    /** The Автор / Чтец labels toggle that person as a favourite; the Серия label opens the series like its name. */
    private TextView mAuthorLabel;
    private TextView mArtistLabel;
    private TextView mSeriesLabel;
    /** Under the time of a started book: how much is behind, and a note beside it (the speed, or the chapter). */
    private View mTimeProgressRow;
    private ProgressBar mTimeProgress;
    private TextView mTimeNote;
    private LinearLayout mSeriesConteiner;
    /** The book's site beside the cover, which opens its card; absent on TV. */
    @Nullable
    private TextView mSource;
    private TextView mDesc;
    private TextView mShowMore;
    /** Copies the description; beside "show less" only, absent on TV. */
    @Nullable
    private TextView mCopyDesc;
    private RecyclerView mRecommendedBooks;
    private TextView mRecommendedBooksTitle;
    private ScrollView mScrollView;
    private ProgressBar mProgressBar;
    private TextView mFavorite;
    private TextView mLike;
    private TextView mDisLike;
    private View mDescLine;
    private View mOtherBookLine;
    @InjectPresenter
    BookDescriptionPresenter mPresenter;
    private BooksOtherAdapter mAdapterBooksRecomended;
    /** Whether the description is unfolded; survives re-binds of the same view. */
    private boolean descExpanded;
    /** The bound description, for unfolding it once there are no recommendations below. */
    @Nullable
    private String descText;
    private boolean mTv;
    @Nullable
    private String mBookUrl;
    /** The header's parts: the author field (may be empty) and the title without it. */
    private String mHeaderAuthor = "";
    private String mHeaderTitle = "";
    /** A long press copied the header: its release must not toggle a favourite as well. */
    private boolean mHeaderLongPressed;
    /** The site's length of the book, shown until it is started. */
    @Nullable
    private String mSiteTime;
    /** Where the book was left, read once per resume; the playing book's position comes from BookProgress. */
    @Nullable
    private Checkpoint mCheckpoint;
    @Nullable
    private RemainingTime mRemaining;
    @Nullable
    private int[] mRemainingOf;
    /** What the time line shows now: it is set again only when this changes, about once a minute. */
    @Nullable
    private String mTimeShown;
    @Nullable
    private SeriesPeek mSeriesPeek;

    public static DescriptionBookFragment newInstance(@NonNull BookPOJO pojo) {
        Bundle args = new Bundle();
        args.putString(ARG_BOOK_POJO, new Gson().toJson(pojo));
        DescriptionBookFragment fragment = new DescriptionBookFragment();
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    private String getLocalCoverFallback() {
        try {
            Bundle args = getArguments();
            if (args != null && args.containsKey(ARG_BOOK_POJO)) {
                BookPOJO pojo = new Gson().fromJson(args.getString(ARG_BOOK_POJO), BookPOJO.class);
                if (pojo != null && getContext() != null) {
                    String cover = com.fanok.audiobooks.util.DownloadStorageHelper.findLocalCoverForBook(getContext(), pojo);
                    if (cover != null && !cover.isEmpty()) {
                        return cover;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** The book's page URL, which its list item's default cover is keyed by; {@code poster} when unknown. */
    private String coverKey(String poster) {
        String url = bookUrl();
        return url != null ? url : poster;
    }

    @Nullable
    private String bookUrl() {
        try {
            Bundle args = getArguments();
            if (args != null && args.containsKey(ARG_BOOK_POJO)) {
                BookPOJO pojo = new Gson().fromJson(args.getString(ARG_BOOK_POJO), BookPOJO.class);
                if (pojo != null) {
                    return pojo.getUrl();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** The book's site under the duration and ratings, opening the site's card; hidden for an unknown host. */
    private void bindSource() {
        if (mSource == null) {
            return;
        }
        Provider provider = Provider.ofUrl(bookUrl());
        mSource.setVisibility(provider == null ? View.GONE : View.VISIBLE);
        if (provider != null) {
            mSource.setText(provider.title);
            mSource.setContentDescription(getString(R.string.sources_book_site, provider.title));
            mSource.setOnClickListener(view -> SourceActivity.start(requireContext(), provider));
        }
    }

    /** The book screen's favourites (BookActivity's), which the labels, the header and the cover act on. */
    @Nullable
    private BookFavorites favorites() {
        return getActivity() instanceof BookActivity ? ((BookActivity) getActivity()).favorites() : null;
    }

    private void bindFavoriteControls() {
        mAuthorLabel.setOnClickListener(view -> togglePeople(FavoritePeople.AUTHOR, view));
        mArtistLabel.setOnClickListener(view -> togglePeople(FavoritePeople.READER, view));
        mSeriesLabel.setOnClickListener(view -> openSeries());
        // the same panel as the ★ tab; a handled long press gives its haptic feedback itself
        mImageView.setOnLongClickListener(view -> {
            BookFavorites favorites = favorites();
            if (favorites == null) {
                return false;
            }
            favorites.showSheet(requireActivity());
            return true;
        });
        mTitle.setOnLongClickListener(view -> {
            mHeaderLongPressed = true;
            copy("title", mTitle.getText());
            return true;
        });
        BookFavorites favorites = favorites();
        if (favorites != null) {
            favorites.addListener(this);
        }
    }

    /** The labels and the header in the colours of what is a favourite now. */
    private void bindFavorites() {
        if (mTitle == null) {
            return;
        }
        BookFavorites favorites = favorites();
        if (favorites != null) {
            bindLabel(mAuthorLabel, favorites, FavoritePeople.AUTHOR, R.drawable.ic_autor);
            bindLabel(mArtistLabel, favorites, FavoritePeople.READER, R.drawable.ic_artist);
        }
        if (mHeaderTitle.isEmpty()) {
            return; // as before: no title, no header
        }
        SpannableStringBuilder header = new SpannableStringBuilder();
        if (!mHeaderAuthor.isEmpty()) {
            appendLink(header, mHeaderAuthor, favorites != null && favorites.any(FavoritePeople.AUTHOR),
                    () -> togglePeople(FavoritePeople.AUTHOR, mTitle));
            header.append(" — ");
        }
        appendLink(header, mHeaderTitle, favorites != null && favorites.isBook(), this::toggleBook);
        mTitle.setText(header);
    }

    /** A favourite: the label in favoriteColor with a star for its icon, so it reads without colour too. */
    private void bindLabel(TextView label, BookFavorites favorites, int kind, @DrawableRes int icon) {
        boolean on = favorites.any(kind);
        label.setTextColor(MaterialColors.getColor(label, on ? R.attr.favoriteColor : R.attr.subTextColor));
        label.setCompoundDrawablesRelativeWithIntrinsicBounds(on ? R.drawable.ic_favorite_label : icon, 0, 0, 0);
        label.setContentDescription(getString(on ? R.string.book_desc_label_on : R.string.book_desc_label_off,
                label.getText()));
    }

    /** A part of the header that toggles on a tap, in favoriteColor while a favourite, never underlined. */
    private void appendLink(SpannableStringBuilder header, String text, boolean favorite, Runnable toggle) {
        int color = MaterialColors.getColor(mTitle, favorite ? R.attr.favoriteColor : R.attr.colorPrimaryText);
        int start = header.length();
        header.append(text);
        header.setSpan(new ClickableSpan() {
            @Override
            public void onClick(@NonNull View widget) {
                CharSequence shown = ((TextView) widget).getText();
                if (shown instanceof Spannable) {
                    Selection.removeSelection((Spannable) shown); // the press highlight
                }
                toggle.run();
            }

            @Override
            public void updateDrawState(@NonNull TextPaint paint) {
                paint.setColor(color);
                paint.setUnderlineText(false);
            }
        }, start, header.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** One person in the field is toggled at once; several are offered as a checkable list. */
    private void togglePeople(int kind, View anchor) {
        BookFavorites favorites = favorites();
        if (favorites == null) {
            return;
        }
        List<String> names = favorites.people(kind);
        if (names.size() == 1) {
            toggle(favorites, kind, names.get(0));
        } else if (names.size() > 1) {
            PopupMenu popup = new PopupMenu(anchor.getContext(), anchor);
            for (int i = 0; i < names.size(); i++) {
                popup.getMenu().add(Menu.NONE, i, i, names.get(i)).setCheckable(true)
                        .setChecked(favorites.has(kind, names.get(i)));
            }
            popup.setOnMenuItemClickListener(item -> {
                toggle(favorites, kind, names.get(item.getItemId()));
                return true;
            });
            popup.show();
        }
    }

    private void toggle(BookFavorites favorites, int kind, String name) {
        boolean on = favorites.toggle(kind, name);
        int text = kind == FavoritePeople.AUTHOR
                ? on ? R.string.book_desc_author_on : R.string.book_desc_author_off
                : on ? R.string.book_desc_reader_on : R.string.book_desc_reader_off;
        undoable(getString(text, name), () -> favorites.undo(kind, name));
    }

    private void toggleBook() {
        BookFavorites favorites = favorites();
        if (favorites == null) {
            return;
        }
        boolean on = favorites.toggleBook();
        undoable(getString(on ? R.string.book_desc_book_on : R.string.book_desc_book_off), favorites::toggleBook);
    }

    /** Every toggle here can be taken back; the views follow BookFavorites' change events. */
    private void undoable(String text, Runnable undo) {
        View root = getView();
        if (root != null) {
            Snackbar.make(root, text, Snackbar.LENGTH_LONG).setAction(R.string.undo, view -> undo.run()).show();
        }
    }

    private void openSeries() {
        BookActivity activity = (BookActivity) getActivity();
        if (activity != null) {
            activity.setTabPostion(getString(R.string.tab_text_3));
        }
    }

    /**
     * The time line: the book's length until it is started, then what is left at the current speed, with a progress
     * line; a listened book, or the chapter when a chapter's length is unknown. It follows the progress the screen
     * already receives for the playing book and is computed once from its checkpoint otherwise; views change only
     * when the shown text does, about once a minute.
     */
    private void showTime() {
        if (mTime == null) {
            return;
        }
        String url = mBookUrl;
        int[] seconds = url == null ? null : BookProgress.seconds(url);
        if (seconds != null && seconds != mRemainingOf) { // evicted lengths: keep the last known ones
            mRemainingOf = seconds;
            mRemaining = new RemainingTime(seconds);
        }
        boolean live = url != null && url.equals(MediaPlayerService.currentBookUrl()) && BookProgress.chapter(url) >= 0;
        int chapter = live ? BookProgress.chapter(url) : mCheckpoint != null ? mCheckpoint.chapterIndex : -1;
        long position = live ? BookProgress.positionMs(url) : mCheckpoint != null ? mCheckpoint.positionMs : 0;
        float speed = BookPresenter.getSpeed();
        RemainingTime.Line line = mRemaining == null ? null : mRemaining.at(chapter, position, speed);
        long sum = mRemaining == null ? 0 : mRemaining.total();
        String total = mSiteTime != null && !mSiteTime.isEmpty() ? mSiteTime
                : sum > 0 ? RemainingTime.format(sum) : "";
        int state = line == null ? RemainingTime.TOTAL : line.state;
        String text = total;
        String note = null;
        switch (state) {
            case RemainingTime.LEFT:
                text = getString(R.string.book_desc_time_left, RemainingTime.format(line.left));
                if (Math.abs(speed - 1f) > 0.01f) {
                    note = getString(R.string.book_desc_time_speed, RemainingTime.speed(speed));
                }
                break;
            case RemainingTime.DONE:
                text = getString(R.string.listened_label);
                note = total.isEmpty() ? null : getString(R.string.book_desc_time_of, total);
                break;
            case RemainingTime.CHAPTER:
                note = getString(R.string.book_desc_time_chapter, line.chapter, line.chapters);
                if (total.isEmpty()) {
                    text = note;
                    note = null;
                }
                break;
            default:
                break;
        }
        String shown = state + text + '\n' + note;
        if (shown.equals(mTimeShown)) {
            return;
        }
        mTimeShown = shown;
        mTime.setText(text);
        mTime.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        mTime.setCompoundDrawablesRelativeWithIntrinsicBounds(
                state == RemainingTime.DONE ? R.drawable.ic_book_listened : R.drawable.ic_clock, 0, 0, 0);
        boolean progress = line != null && state != RemainingTime.TOTAL && !text.isEmpty();
        mTimeProgressRow.setVisibility(progress ? View.VISIBLE : View.GONE);
        if (progress) {
            mTimeProgress.setProgress(line.permille);
        }
        mTimeNote.setText(note);
        mTimeNote.setVisibility(note == null ? View.GONE : View.VISIBLE);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {


        View view = inflater.inflate(R.layout.fragment_book_description, container, false);

        mTitle = view.findViewById(R.id.title);
        mImageView = view.findViewById(R.id.imageView);
        EmbeddedCover.listen(mEmbeddedCover);
        mReting = view.findViewById(R.id.reting);
        mGenre = view.findViewById(R.id.genre);
        mTime = view.findViewById(R.id.time);
        mAuthor = view.findViewById(R.id.author);
        mArtist = view.findViewById(R.id.artist);
        mSeries = view.findViewById(R.id.series);
        mAuthorLabel = view.findViewById(R.id.authorLabel);
        mArtistLabel = view.findViewById(R.id.artistLabel);
        mSeriesLabel = view.findViewById(R.id.seriesLabel);
        mTimeProgressRow = view.findViewById(R.id.timeProgressRow);
        mTimeProgress = view.findViewById(R.id.timeProgress);
        mTimeNote = view.findViewById(R.id.timeNote);
        mSeriesConteiner = view.findViewById(R.id.seriesConteiner);
        mSource = view.findViewById(R.id.bookSource);
        mDesc = view.findViewById(R.id.desc);
        mShowMore = view.findViewById(R.id.showMore);
        mCopyDesc = view.findViewById(R.id.copyDesc);
        mRecommendedBooks = view.findViewById(R.id.recommendedBooks);
        mRecommendedBooksTitle = view.findViewById(R.id.recommendedBooksTitle);
        mScrollView = view.findViewById(R.id.scrollView);
        mProgressBar = view.findViewById(R.id.progressBar);
        mFavorite = view.findViewById(R.id.favorite);
        mLike = view.findViewById(R.id.like);
        mDisLike = view.findViewById(R.id.disLike);
        mDescLine = view.findViewById(R.id.descLine);
        mOtherBookLine = view.findViewById(R.id.otherBookLine);

        mAdapterBooksRecomended = new BooksOtherAdapter();
        mRecommendedBooks.setAdapter(mAdapterBooksRecomended);
        // On a touch screen the list never takes the focus: shown after the description, it was handed the focus and
        // the page scrolled down to it, the title going under the tabs. RecyclerView sets this in its constructor.
        mRecommendedBooks.setFocusableInTouchMode(false);
        mRecommendedBooks.setLayoutManager(
                new LinearLayoutManager(getContext(), LinearLayoutManager.HORIZONTAL, false));
        int margin = (int) requireContext().getResources().getDimension(R.dimen.books_other_margin);
        mRecommendedBooks.addItemDecoration(new MarginItemDecoration(margin));
        mBookUrl = bookUrl();
        bindFavoriteControls();
        UiModeManager uiModeManager = (UiModeManager) requireContext().getSystemService(UI_MODE_SERVICE);
        mTv = uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;
        if (!mTv) {
            // the header's author and title are links (on TV the labels do it: links would trap the D-pad);
            // the release ending a long press (a copy) is not a tap
            mTitle.setMovementMethod(new LinkMovementMethod() {
                @Override
                public boolean onTouchEvent(TextView widget, Spannable buffer, MotionEvent event) {
                    int action = event.getActionMasked();
                    if (action == MotionEvent.ACTION_DOWN) {
                        mHeaderLongPressed = false;
                    } else if (action == MotionEvent.ACTION_UP && mHeaderLongPressed) {
                        Selection.removeSelection(buffer);
                        return true;
                    }
                    return super.onTouchEvent(widget, buffer, event);
                }
            });
        } else {

            // Вешаем слушатель на ПОСЛЕДНИЙ интерактивный элемент в этом фрагменте.
            // В данном случае, это RecyclerView с рекомендованными книгами.
            mRecommendedBooks.setOnKeyListener((v, keyCode, event) -> {
                // Реагируем только на нажатие кнопки
                if (event.getAction() != KeyEvent.ACTION_DOWN) {
                    return false;
                }

                RecyclerView.LayoutManager layoutManager = mRecommendedBooks.getLayoutManager();
                if (layoutManager == null) {
                    return false;
                }

                // Получаем позицию текущего сфокусированного элемента
                View focusedChild = layoutManager.getFocusedChild();
                if (focusedChild == null) {
                    return false;
                }
                int focusedPos = layoutManager.getPosition(focusedChild);

                // Получаем общее количество элементов
                int itemCount = layoutManager.getItemCount();

                // Если нажата кнопка "ВНИЗ" и фокус на ПОСЛЕДНЕМ элементе списка
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && focusedPos == itemCount - 1) {
                    // Находим плеер в родительской Activity
                    View playerView = requireActivity().findViewById(R.id.player);
                    if (playerView != null) {
                        // Передаем фокус плееру
                        playerView.requestFocus();
                    }
                    // Возвращаем true, говоря, что мы обработали это событие
                    return true;
                }

                // Для всех остальных случаев позволяем RecyclerView работать как обычно
                return false;
            });
        }


        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mBookUrl != null) {
            mCheckpoint = new StorageUtil(requireContext()).loadCheckpoint(mBookUrl);
            BookProgress.addListener(this);
        }
        showTime();
        if (mSeriesPeek != null) {
            mSeriesPeek.schedule();
        }
    }

    @Override
    public void onPause() {
        // another tab, the screen off or another screen: no updates and no motion until it is back
        BookProgress.removeListener(this);
        if (mSeriesPeek != null) {
            mSeriesPeek.cancel();
        }
        super.onPause();
    }

    @Override
    public void onBookProgress(@NonNull String bookUrl) {
        if (bookUrl.equals(mBookUrl)) {
            showTime();
        }
    }

    @Override
    public void onFavoritesChanged() {
        bindFavorites();
    }

    /** The book screen's one «Пройти» offer: the chapters fail on the same page and offer it too. */
    @Override
    public void showSiteCheckOffer(com.fanok.audiobooks.search.Provider site) {
        if (getActivity() instanceof BookActivity) {
            ((BookActivity) getActivity()).showSiteCheckOffer(site);
        }
    }

    @Override
    public void onDestroyView() {
        // the presenter outlives this view: drop every reference to the destroyed tree (UI-12)
        BookFavorites favorites = favorites();
        if (favorites != null) {
            favorites.removeListener(this);
        }
        if (mSeriesPeek != null) {
            mSeriesPeek.cancel();
            mSeriesPeek = null;
        }
        mTimeShown = null;
        if (mImageView != null) {
            Picasso.get().cancelRequest(mImageView);
        }
        EmbeddedCover.unlisten(mEmbeddedCover);
        mDefaultCoverShown = false;
        mTitle = null;
        mImageView = null;
        mReting = null;
        mGenre = null;
        mTime = null;
        mAuthor = null;
        mArtist = null;
        mSeries = null;
        mAuthorLabel = null;
        mArtistLabel = null;
        mSeriesLabel = null;
        mTimeProgressRow = null;
        mTimeProgress = null;
        mTimeNote = null;
        mSeriesConteiner = null;
        mSource = null;
        mDesc = null;
        mShowMore = null;
        mCopyDesc = null;
        mRecommendedBooks = null;
        mRecommendedBooksTitle = null;
        mScrollView = null;
        mProgressBar = null;
        mFavorite = null;
        mLike = null;
        mDisLike = null;
        mDescLine = null;
        mOtherBookLine = null;
        mAdapterBooksRecomended = null;
        super.onDestroyView();
    }

    @Override
    public void showDescription(@NonNull DescriptionPOJO description) {
        if (mTitle == null) {
            return; // no view attached; Moxy re-delivers on the next attach
        }

        if (mTv) {
            // Устанавливаем наш селектор в качестве фона для всех интерактивных элементов
            // Система сама будет показывать/скрывать рамку при смене фокуса.
            mImageView.setBackgroundResource(R.drawable.focusable);
            mGenre.setBackgroundResource(R.drawable.focusable);
            mAuthor.setBackgroundResource(R.drawable.focusable);
            mArtist.setBackgroundResource(R.drawable.focusable);
            mSeries.setBackgroundResource(R.drawable.focusable);
            mAuthorLabel.setBackgroundResource(R.drawable.focusable);
            mArtistLabel.setBackgroundResource(R.drawable.focusable);
            mSeriesLabel.setBackgroundResource(R.drawable.focusable);
            mShowMore.setBackgroundResource(R.drawable.focusable);
        }
        try {
            BookFavorites favorites = favorites();
            if (favorites != null) {
                favorites.setPeople(description);
            }
            mHeaderAuthor = description.getAutor() == null ? "" : description.getAutor().trim();
            mHeaderTitle = BookHeader.title(mHeaderAuthor, description.getTitle());
            bindFavorites();

            String poster = description.getPoster();
            String localCover = getLocalCoverFallback();
            if (localCover != null && !localCover.isEmpty()) {
                poster = localCover;
            }
            mDefaultCoverShown = poster != null && DefaultCover.replaces(poster);
            if (poster != null && !poster.trim().isEmpty()) {
                if (DefaultCover.replaces(poster)) {
                    Picasso.get().cancelRequest(mImageView);
                    mImageView.setImageDrawable(DefaultCover.drawable(mImageView.getContext(), coverKey(poster)));
                } else if (poster.startsWith("content://")) {
                    Picasso.get()
                            .load(android.net.Uri.parse(poster))
                            .placeholder(android.R.drawable.ic_menu_gallery)
                            .error(android.R.drawable.ic_menu_gallery)
                            .into(mImageView);
                } else if (poster.startsWith("/") || poster.contains(":\\")) {
                    Picasso.get()
                            .load(new java.io.File(poster))
                            .placeholder(android.R.drawable.ic_menu_gallery)
                            .error(android.R.drawable.ic_menu_gallery)
                            .into(mImageView);
                } else {
                    Picasso.get()
                            .load(poster)
                            .placeholder(android.R.drawable.ic_menu_gallery)
                            .error(android.R.drawable.ic_menu_gallery)
                            .into(mImageView);
                }
            } else {
                Picasso.get().load(android.R.drawable.ic_menu_gallery)
                        .into(mImageView);
            }

            BookActivity activity = (BookActivity) getActivity();

            String remotePoster = description.getPoster();
            if (activity != null && remotePoster != null && !remotePoster.trim().isEmpty()) {
                mImageView.setOnClickListener(view ->
                        ImageFullScreenActivity.start(activity,
                                remotePoster, description.getTitle(),
                                mImageView, coverKey(remotePoster)));
            }

            if (description.getGenre() != null && !description.getGenre().isEmpty()) {
                mGenre.setText(description.getGenre());
            } else if (mGenre.getVisibility() != View.GONE) {
                mGenre.setVisibility(View.GONE);
            }

            String retingStr = description.getReiting();
            if (retingStr != null && !retingStr.equals("0") && !retingStr.isEmpty()) {
                mReting.setText(retingStr);
                mReting.setVisibility(View.VISIBLE);
            } else {
                mReting.setVisibility(View.GONE);
            }

            mSiteTime = description.getTime();
            mTimeShown = null;
            showTime();


            if (description.getAutor() != null) {
                mAuthor.setText(description.getAutor());
            }
            if (description.getArtist() != null) {
                mArtist.setText(description.getArtist());
            }

            String seriesStr = description.getSeries();
            if (seriesStr != null && !seriesStr.isEmpty()) {
                mSeries.setText(seriesStr);
                if (activity != null) {
                    activity.showSiries();
                }
                // a cut name scrolls once now and then; a long press shows it again
                if (mSeriesPeek == null) {
                    mSeriesPeek = new SeriesPeek(mSeries, mBookUrl != null ? mBookUrl : seriesStr);
                }
                if (isResumed()) {
                    mSeriesPeek.schedule();
                }
                mSeries.setOnLongClickListener(view -> mSeriesPeek != null && mSeriesPeek.play());
            } else if (mSeriesConteiner.getVisibility() != View.GONE) {
                mSeriesConteiner.setVisibility(View.GONE);
            }

            bindSource();

            if (description.isOtherReader() && activity != null) {
                ArrayList<OtherArtistPOJO> otherReaders = description.getOtherReaders();
                if (otherReaders != null && !otherReaders.isEmpty()) {
                    activity.otherArtistsFound(otherReaders); // the description search found them
                } else {
                    activity.loadOtherArtist();
                }
            }


            descText = description.getDescription();
            final String text = descText;
            ExpandableText.bind(mDesc, mShowMore, mCopyDesc, text, MAX_LINES, descExpanded);
            mShowMore.setOnClickListener(view1 -> {
                descExpanded = !descExpanded;
                if (mDesc != null) {
                    ExpandableText.bind(mDesc, mShowMore, mCopyDesc, text, MAX_LINES, descExpanded);
                }
            });
            if (mCopyDesc != null) {
                mCopyDesc.setOnClickListener(view1 -> copy("description", text));
            }
            if (text == null || text.isEmpty()) {
                mDescLine.setVisibility(View.GONE);
            }


            if (!description.getFavorite().equals("0")) {
                mFavorite.setText(description.getFavorite());
                mFavorite.setVisibility(View.VISIBLE);
            } else {
                mFavorite.setVisibility(View.GONE);
            }
            mLike.setText(String.valueOf(description.getLike()));
            mDisLike.setText(String.valueOf(description.getDisLike()));

            if (description.getDisLike().equals("0") && description.getLike().equals("0")) {
                mDisLike.setVisibility(View.GONE);
                mLike.setVisibility(View.GONE);
            } else {
                mDisLike.setVisibility(View.VISIBLE);
                mLike.setVisibility(View.VISIBLE);
            }

            if (description.getGenreUrl() != null && !description.getGenreUrl().isEmpty()) {
                mGenre.setOnClickListener(view -> {
                    GenrePicks.record(requireContext(), description.getGenre());
                    MainActivity.startMainActivity(requireContext(), Consts.FRAGMENT_AUDIOBOOK,
                            description.getGenreUrl());
                });
            }

            if (description.getAutorUrl() != null && !description.getAutorUrl().isEmpty()) {
                mAuthor.setOnClickListener(
                        view -> MainActivity.startMainActivity(requireContext(),
                                Consts.FRAGMENT_AUDIOBOOK, description.getAutorUrl()));
            }

            if (description.getArtistUrl() != null && !description.getArtistUrl().isEmpty()) {
                mArtist.setOnClickListener(
                        view -> MainActivity.startMainActivity(requireContext(),
                                Consts.FRAGMENT_AUDIOBOOK, description.getArtistUrl()));
            }

            mSeries.setOnClickListener(view -> openSeries());
        } catch (RuntimeException e) {
            // a bad field must not take the screen down, and never restart it: the same data would come back
            Log.e(TAG, "showDescription: ", e);
            showToast(R.string.error_load_data);
        }
    }

    public void showOtherBooks(@NonNull ArrayList<BookPOJO> data) {
        if (mAdapterBooksRecomended == null || mRecommendedBooks == null) {
            return;
        }
        mAdapterBooksRecomended.setData(data);
        if (!data.isEmpty()) {
            mRecommendedBooks.setVisibility(View.VISIBLE);
            mRecommendedBooksTitle.setVisibility(View.VISIBLE);
            showOtherBooksLine(true);
        } else {
            mRecommendedBooks.setVisibility(View.GONE);
            mRecommendedBooksTitle.setVisibility(View.GONE);
            showOtherBooksLine(false);
        }

    }

    private void copy(@NonNull String label, @Nullable CharSequence text) {
        ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null || text == null) {
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text.toString()));
        // Android 13+ confirms a copy itself
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(requireContext(), R.string.copied, Toast.LENGTH_SHORT).show();
        }
    }

    /** Nothing is recommended below: the description unfolds instead of leaving the space below empty. */
    private void expandDescription() {
        if (descExpanded) {
            return;
        }
        descExpanded = true;
        if (mDesc != null && descText != null) {
            ExpandableText.bind(mDesc, mShowMore, mCopyDesc, descText, MAX_LINES, true);
        }
    }

    @Override
    public void showProgress(boolean b) {
        if (mProgressBar == null) {
            return;
        }
        if (b) {
            mProgressBar.setVisibility(View.VISIBLE);
            mScrollView.setVisibility(View.INVISIBLE);
        } else {
            mProgressBar.setVisibility(View.GONE);
            mScrollView.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void showToast(final int id) {
        Toast.makeText(getContext(), getResources().getText(id),
                Toast.LENGTH_LONG).show();
    }

    @Override
    public void showOtherBooksLine(boolean b) {
        if (mOtherBookLine != null) {
            mOtherBookLine.setVisibility(b ? View.VISIBLE : View.GONE);
        }
        if (!b) {
            expandDescription(); // the recommendations came empty or failed
        }
    }

    @ProvidePresenter
    BookDescriptionPresenter provide() {
        BookPOJO pojo = new Gson().fromJson(
                requireArguments().getString(ARG_BOOK_POJO), BookPOJO.class);
        if (pojo == null) {
            throw new NullPointerException();
        }
        return new BookDescriptionPresenter(pojo);
    }
}
