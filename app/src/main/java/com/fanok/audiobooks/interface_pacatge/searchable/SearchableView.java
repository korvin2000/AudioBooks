package com.fanok.audiobooks.interface_pacatge.searchable;


import androidx.annotation.NonNull;

import com.arellomobile.mvp.MvpView;
import com.arellomobile.mvp.viewstate.strategy.AddToEndSingleStrategy;
import com.arellomobile.mvp.viewstate.strategy.OneExecutionStateStrategy;
import com.arellomobile.mvp.viewstate.strategy.SkipStrategy;
import com.arellomobile.mvp.viewstate.strategy.StateStrategyType;
import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.pojo.GenrePOJO;
import com.fanok.audiobooks.pojo.SearcheblPOJO;
import com.fanok.audiobooks.search.SearchGrouper;
import com.fanok.audiobooks.search.SearchUi;

import java.util.ArrayList;
import java.util.List;

public interface SearchableView extends MvpView {

    @StateStrategyType(AddToEndSingleStrategy.class)
    void setLayoutManager();

    @StateStrategyType(AddToEndSingleStrategy.class)
    void setLayoutManager(int count);

    /** The book search's whole screen: chips, progress, summary, rows and why it is empty. */
    @StateStrategyType(AddToEndSingleStrategy.class)
    void showState(SearchUi.State state);

    /** Puts {@code query} into the search field without submitting it (a restored search). */
    @StateStrategyType(AddToEndSingleStrategy.class)
    void showQuery(String query);

    /** The recordings of one work and the sites of each, to choose from. */
    @StateStrategyType(OneExecutionStateStrategy.class)
    void showNarrations(String title, List<SearchGrouper.Narration> narrations);

    @StateStrategyType(AddToEndSingleStrategy.class)
    void showDataGenres(ArrayList<GenrePOJO> genres);

    @StateStrategyType(AddToEndSingleStrategy.class)
    void clearData();

    @StateStrategyType(OneExecutionStateStrategy.class)
    void showToast(int message);

    @StateStrategyType(OneExecutionStateStrategy.class)
    void showToast(String message);

    @StateStrategyType(AddToEndSingleStrategy.class)
    void showProgres(boolean b);

    @StateStrategyType(SkipStrategy.class)
    void returnResult(String url, String name, int modelId, String tag);

    @StateStrategyType(AddToEndSingleStrategy.class)
    void showSeriesAndAutors(SearcheblPOJO searcheblPOJO);

    @StateStrategyType(SkipStrategy.class)
    void startBookActivity(@NonNull BookPOJO bookPOJO);

    @StateStrategyType(AddToEndSingleStrategy.class)
    void setNotFoundVisibile(boolean b);

    /** How many sources failed in the last pass (0 hides the line and its retry action). */
    @StateStrategyType(AddToEndSingleStrategy.class)
    void showSourcesFailed(int count);

}
