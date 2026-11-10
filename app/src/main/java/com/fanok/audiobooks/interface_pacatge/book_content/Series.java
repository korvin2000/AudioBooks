package com.fanok.audiobooks.interface_pacatge.book_content;

import androidx.annotation.NonNull;

import com.arellomobile.mvp.MvpView;
import com.arellomobile.mvp.viewstate.strategy.OneExecutionStateStrategy;
import com.arellomobile.mvp.viewstate.strategy.SkipStrategy;
import com.arellomobile.mvp.viewstate.strategy.StateStrategyType;
import com.fanok.audiobooks.pojo.SeriesPOJO;

import java.util.ArrayList;

public interface Series extends MvpView {

    void showProgress(boolean b);

    void showSeries(ArrayList<SeriesPOJO> data);

    @StateStrategyType(SkipStrategy.class)
    void showBook(@NonNull String url);

    // once: kept in the view state, a load error would pop up again on every re-attach (rotation)
    @StateStrategyType(OneExecutionStateStrategy.class)
    void showToast(int message);

    @StateStrategyType(OneExecutionStateStrategy.class)
    void showToast(String message);
}
