package com.fanok.audiobooks.adapter;


import static androidx.preference.PreferenceManager.getDefaultSharedPreferences;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import com.fanok.audiobooks.R;
import com.fanok.audiobooks.activity.NarratorActivity;
import com.fanok.audiobooks.fragment.FavoritesSwitcher;
import com.fanok.audiobooks.model.FavoritePeople;
import com.fanok.audiobooks.pojo.GenrePOJO;
import com.fanok.audiobooks.search.Genre;
import com.fanok.audiobooks.search.GenrePicks;
import com.google.android.material.snackbar.Snackbar;
import java.util.ArrayList;
import java.util.List;

/** @noinspection ClassEscapesDefinedScope*/
public class GenreListAddapter extends RecyclerView.Adapter<GenreListAddapter.MyHolder> implements ListFocus.Keyed {

    /** Payload of a rebind that only re-reads the star. */
    private static final Object STAR = new Object();

    /** Set once the hint after the very first favourite person ever was shown. */
    private static final String HINT_PREF = "people_star_hint_shown";
    /** The same for the very first favourite genre. */
    private static final String GENRE_HINT_PREF = "genre_star_hint_shown";
    /** {@link #mStarKind} of rows without a star. */
    private static final int NO_STARS = -1;

    private RecyclerTushListner listner;
    private ArrayList<GenrePOJO> mModel;
    /**
     * FavoritePeople.AUTHOR / READER or {@link FavoritesSwitcher#GENRES} when rows show a favourite star;
     * {@link #NO_STARS} shows none.
     */
    private int mStarKind = NO_STARS;
    /** The genre a genre list is narrowed to: a star of a row naming more genres stars this one. */
    @Nullable
    private Genre mNarrowedTo;

    /** Rows get a star at the end that toggles that person in FavoritePeople of {@code kind}. */
    public void showStars(int kind) {
        mStarKind = kind;
    }

    /**
     * Rows of a site's genres get a star for the app's genres they name ({@link GenrePicks#toggleStar}), in a list
     * narrowed to the genre {@code narrowedTo} (null: not narrowed).
     */
    public void showGenreStars(@Nullable Genre narrowedTo) {
        mStarKind = FavoritesSwitcher.GENRES;
        mNarrowedTo = narrowedTo;
    }

    /** Re-reads the stars (another screen may have changed the favourites); rebinds only the stars. */
    public void refreshStars() {
        if (mStarKind != NO_STARS && getItemCount() > 0) {
            notifyItemRangeChanged(0, getItemCount(), STAR);
        }
    }

    public void setClickListner(RecyclerTushListner listner) {
        this.listner = listner;
    }

    /**
     * A new list. On a TV a page added after the rows shown is an insertion, so they are not rebuilt and the remote's
     * focus stays on its row (touch devices rebuild, as before); anything else (a sorted list takes a page in between; people lists run to tens of thousands,
     * too many for a diff) is rebuilt, and ListFocus brings the focus back.
     */
    @SuppressLint("NotifyDataSetChanged")
    public void setItem(ArrayList<GenrePOJO> newModel) {
        ArrayList<GenrePOJO> before = mModel;
        mModel = newModel == null ? new ArrayList<>() : new ArrayList<>(newModel);
        int old = before == null ? 0 : before.size();
        if (ListFocus.isTv() && old > 0 && mModel.size() > old && startsWith(mModel, before)) {
            notifyItemRangeInserted(old, mModel.size() - old);
        } else {
            notifyDataSetChanged();
        }
    }

    /** The same entries (a page appended to the presenter's list keeps them): none of them changed. */
    private static boolean startsWith(ArrayList<GenrePOJO> list, ArrayList<GenrePOJO> start) {
        for (int i = 0; i < start.size(); i++) {
            if (list.get(i) != start.get(i)) {
                return false;
            }
        }
        return true;
    }

    @Nullable
    @Override
    public String keyAt(int position) {
        GenrePOJO entry = getItem(position);
        return entry == null ? null : entry.getUrl();
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

    public void clearItem() {
        if (mModel != null) {
            int oldSize = mModel.size();
            if (oldSize > 0) {
                mModel.clear();
                notifyItemRangeRemoved(0, oldSize);
            }
        }
    }

    /** The entry shown at {@code position}, or null when the position is no longer in the list. */
    public GenrePOJO getItem(int position) {
        return mModel == null || position < 0 || position >= mModel.size() ? null : mModel.get(position);
    }

    @NonNull
    @Override
    public MyHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int i) {

        View view = LayoutInflater.from(viewGroup.getContext()).inflate(
                R.layout.genre_recycler_item, viewGroup, false);

        return new MyHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MyHolder myHolder, int i) {
        myHolder.bind(mModel.get(i));
    }

    @Override
    public void onBindViewHolder(@NonNull MyHolder myHolder, int i, @NonNull List<Object> payloads) {
        if (payloads.contains(STAR)) {
            myHolder.bindStar(mModel.get(i));
        } else {
            super.onBindViewHolder(myHolder, i, payloads);
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


    public interface RecyclerTushListner {
        void onClickItem(View view, int position);
    }

    class MyHolder extends RecyclerView.ViewHolder {

        private final TextView mDescription;

        private final TextView mName;

        private final TextView mReting;

        private final ImageButton mStar;

        private final ImageButton mCard;

        MyHolder(@NonNull final View itemView) {
            super(itemView);

            mName = itemView.findViewById(R.id.name);
            mReting = itemView.findViewById(R.id.reting);
            mDescription = itemView.findViewById(R.id.desc);
            mStar = itemView.findViewById(R.id.star);
            mCard = itemView.findViewById(R.id.card);
            mCard.setOnClickListener(view -> {
                GenrePOJO person = getItem(getBindingAdapterPosition());
                if (person != null && person.getCard() != null) {
                    NarratorActivity.start(view.getContext(), person.getName(), person.getCard());
                }
            });
            mStar.setOnClickListener(view -> {
                int position = getBindingAdapterPosition();
                GenrePOJO person = getItem(position);
                if (person == null || mStarKind == NO_STARS) {
                    return;
                }
                if (mStarKind == FavoritesSwitcher.GENRES) {
                    if (GenrePicks.toggleStar(view.getContext(), person.getName(), mNarrowedTo)) {
                        firstAddHint(view);
                    }
                    refreshStars(); // other rows may name the same genre
                    return;
                }
                if (FavoritePeople.get(view.getContext()).toggle(mStarKind, person.getName(), person.getUrl())) {
                    firstAddHint(view);
                }
                notifyItemChanged(position, STAR);
            });
            itemView.setOnClickListener(view -> {
                int position = getBindingAdapterPosition();
                if (listner != null && position != RecyclerView.NO_POSITION) {
                    listner.onClickItem(itemView, position);
                }
            });
            // TV: the row keeps focus, so the focus search never picks its buttons; D-pad goes row → card → star
            itemView.setOnKeyListener((view, keyCode, event) -> keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    && event.getAction() == KeyEvent.ACTION_DOWN && (focus(mCard) || focus(mStar)));
            mCard.setOnKeyListener((view, keyCode, event) -> event.getAction() == KeyEvent.ACTION_DOWN
                    && (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? focus(mStar)
                    : keyCode == KeyEvent.KEYCODE_DPAD_LEFT && itemView.requestFocus()));
            mStar.setOnKeyListener((view, keyCode, event) -> keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                    && event.getAction() == KeyEvent.ACTION_DOWN && (focus(mCard) || itemView.requestFocus()));
        }

        private boolean focus(View view) {
            return view.getVisibility() == View.VISIBLE && view.requestFocus();
        }

        void bind(GenrePOJO book) {
            if (book.getName() == null || book.getUrl() == null) {
                throw new NullPointerException();
            }
            mName.setText(book.getName());
            // a count the description already says is not repeated beside it
            String description = book.getDescription();
            if (book.getReting() != 0 && (description == null
                    || !description.replaceAll("\\D", "").equals(String.valueOf(book.getReting())))) {
                mReting.setText(String.valueOf(book.getReting()));
                mReting.setVisibility(View.VISIBLE);
            } else {
                mReting.setVisibility(View.GONE);
            }
            mDescription.setText(description);
            mCard.setVisibility(book.getCard() == null ? View.GONE : View.VISIBLE);
            bindStar(book);
        }

        void bindStar(GenrePOJO person) {
            // the number stands right next to the star; without the star it keeps its margin from the edge
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) mReting.getLayoutParams();
            boolean genres = mStarKind == FavoritesSwitcher.GENRES;
            boolean starred = mStarKind != NO_STARS && (!genres || Genre.of(person.getName()) != null);
            lp.setMarginEnd(starred ? 0 : mReting.getResources().getDimensionPixelSize(R.dimen.text_margin));
            if (!starred) { // a site genre none of the app's genres matches cannot be a favourite
                mStar.setVisibility(View.GONE);
                return;
            }
            boolean on = genres ? GenrePicks.starred(GenrePicks.favorites(mStar.getContext()), person.getName())
                    : FavoritePeople.get(mStar.getContext()).has(mStarKind, person.getName());
            mStar.setActivated(on);
            mStar.setContentDescription(mStar.getContext().getString(
                    on ? R.string.people_star_remove : R.string.people_star_add));
            mStar.setVisibility(View.VISIBLE);
        }

        /** The very first person or genre ever added says once where the favourites are kept. */
        private void firstAddHint(View view) {
            boolean genres = mStarKind == FavoritesSwitcher.GENRES;
            String key = genres ? GENRE_HINT_PREF : HINT_PREF;
            SharedPreferences pref = getDefaultSharedPreferences(view.getContext());
            if (!pref.getBoolean(key, false)) {
                pref.edit().putBoolean(key, true).apply();
                Snackbar.make(view, genres ? R.string.genre_star_hint : mStarKind == FavoritePeople.AUTHOR
                        ? R.string.people_star_hint_author : R.string.people_star_hint_reader,
                        Snackbar.LENGTH_LONG).show();
            }
        }
    }
}
