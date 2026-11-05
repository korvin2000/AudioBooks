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
import androidx.recyclerview.widget.RecyclerView;
import com.fanok.audiobooks.R;
import com.fanok.audiobooks.activity.NarratorActivity;
import com.fanok.audiobooks.model.FavoritePeople;
import com.fanok.audiobooks.pojo.GenrePOJO;
import com.google.android.material.snackbar.Snackbar;
import java.util.ArrayList;
import java.util.List;

/** @noinspection ClassEscapesDefinedScope*/
public class GenreListAddapter extends RecyclerView.Adapter<GenreListAddapter.MyHolder> {

    /** Payload of a rebind that only re-reads the star. */
    private static final Object STAR = new Object();

    /** Set once the hint after the very first favourite person ever was shown. */
    private static final String HINT_PREF = "people_star_hint_shown";

    private RecyclerTushListner listner;
    private ArrayList<GenrePOJO> mModel;
    /** FavoritePeople.AUTHOR / READER when rows show a favourite star; -1 (genres) shows none. */
    private int mStarKind = -1;

    /** Rows get a star at the end that toggles that person in FavoritePeople of {@code kind}. */
    public void showStars(int kind) {
        mStarKind = kind;
    }

    /** Re-reads the stars (another screen may have changed the favourites); rebinds only the stars. */
    public void refreshStars() {
        if (mStarKind >= 0 && getItemCount() > 0) {
            notifyItemRangeChanged(0, getItemCount(), STAR);
        }
    }

    public void setClickListner(RecyclerTushListner listner) {
        this.listner = listner;
    }

    @SuppressLint("NotifyDataSetChanged")
    public void setItem(ArrayList<GenrePOJO> newModel) {
        mModel = newModel == null ? new ArrayList<>() : new ArrayList<>(newModel);
        notifyDataSetChanged();
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
                if (person == null || mStarKind < 0) {
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
            lp.setMarginEnd(mStarKind < 0
                    ? mReting.getResources().getDimensionPixelSize(R.dimen.text_margin) : 0);
            if (mStarKind < 0) {
                mStar.setVisibility(View.GONE);
                return;
            }
            boolean on = FavoritePeople.get(mStar.getContext()).has(mStarKind, person.getName());
            mStar.setActivated(on);
            mStar.setContentDescription(mStar.getContext().getString(
                    on ? R.string.people_star_remove : R.string.people_star_add));
            mStar.setVisibility(View.VISIBLE);
        }

        /** The very first person ever added says once where the favourites are kept. */
        private void firstAddHint(View view) {
            SharedPreferences pref = getDefaultSharedPreferences(view.getContext());
            if (!pref.getBoolean(HINT_PREF, false)) {
                pref.edit().putBoolean(HINT_PREF, true).apply();
                Snackbar.make(view, mStarKind == FavoritePeople.AUTHOR
                        ? R.string.people_star_hint_author : R.string.people_star_hint_reader,
                        Snackbar.LENGTH_LONG).show();
            }
        }
    }
}
