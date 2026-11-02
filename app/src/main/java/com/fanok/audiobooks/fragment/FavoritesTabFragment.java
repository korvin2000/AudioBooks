package com.fanok.audiobooks.fragment;

import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.fanok.audiobooks.R;
import com.fanok.audiobooks.activity.BookActivity;

/**
 * The book screen's ★ tab: the favourites panel of the book, its authors and its readers (BookFavorites), the same
 * panel the cover's long press opens, then the ways to "Избранное" and "История".
 */
public class FavoritesTabFragment extends Fragment {

    public FavoritesTabFragment() {
        super(R.layout.fragment_book_favorites);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (getActivity() instanceof BookActivity) {
            ((BookActivity) getActivity()).favorites().bindPanel(view.findViewById(R.id.favoritesPanel), null);
        }
    }
}
