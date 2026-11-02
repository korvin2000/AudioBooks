package com.fanok.audiobooks.adapter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import com.fanok.audiobooks.R;
import com.fanok.audiobooks.presenter.FavoritePeoplePresenter.Row;
import com.fanok.audiobooks.search.Provider;
import java.util.ArrayList;
import java.util.List;

/** The rows of Избранное → Авторы / Чтецы: the name and what the library knows of the person; no star (all are). */
public class FavoritePeopleAdapter extends RecyclerView.Adapter<FavoritePeopleAdapter.Holder> {

    public interface Listener {
        void onRow(@NonNull Row row, boolean longClick);
    }

    private final List<Row> mRows = new ArrayList<>();
    @Nullable
    private Listener mListener;

    public void setListener(@Nullable Listener listener) {
        mListener = listener;
    }

    @SuppressLint("NotifyDataSetChanged")
    public void setItems(@NonNull List<Row> rows) {
        mRows.clear();
        mRows.addAll(rows);
        notifyDataSetChanged();
    }

    @Nullable
    public Row getItem(int position) {
        return position >= 0 && position < mRows.size() ? mRows.get(position) : null;
    }

    /** "Акнига, Книга в ухе · 3 книги в избранном": the sites known for the person and their favourite books. */
    @NonNull
    public static String describe(@NonNull Context context, @NonNull Row row) {
        StringBuilder out = new StringBuilder();
        for (Provider p : row.sites) {
            out.append(out.length() > 0 ? ", " : "").append(p.title);
        }
        String books = row.books > 0
                ? context.getResources().getQuantityString(R.plurals.favorite_people_books, row.books, row.books)
                : context.getString(R.string.favorite_people_no_books);
        return out.length() > 0 ? out.append(" · ").append(books).toString() : books;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.genre_recycler_item, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Row row = mRows.get(position);
        holder.name.setText(row.person.name);
        holder.desc.setText(describe(holder.itemView.getContext(), row));
    }

    @Override
    public int getItemCount() {
        return mRows.size();
    }

    class Holder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView desc;

        Holder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.name);
            desc = itemView.findViewById(R.id.desc);
            itemView.findViewById(R.id.reting).setVisibility(View.GONE);
            itemView.setOnClickListener(v -> click(false));
            itemView.setOnLongClickListener(v -> click(true));
        }

        private boolean click(boolean longClick) {
            Row row = getItem(getBindingAdapterPosition());
            if (row != null && mListener != null) {
                mListener.onRow(row, longClick);
            }
            return true;
        }
    }
}
