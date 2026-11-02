package com.fanok.audiobooks.interface_pacatge.searchable;

import android.view.LayoutInflater;
import android.view.View;
import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.pojo.GenrePOJO;
import com.fanok.audiobooks.pojo.SearchebleArrayPOJO;

/** Clicks carry the item the adapter showed, not a position into a list that may have changed since. */
public interface SearchablePresenter {

    void onDestroy();

    void loadBoks();

    void loadNext();

    void retryFailed();

    void onGenreItemClick(GenrePOJO item);

    void onBookItemClick(BookPOJO book);

    void onBookItemLongClick(View view, BookPOJO book, LayoutInflater layoutInflater);

    void onAutorsListItemClick(SearchebleArrayPOJO item);

    void onSeriesListItemClick(SearchebleArrayPOJO item);

    void filterAutorsAndSeries();

}
