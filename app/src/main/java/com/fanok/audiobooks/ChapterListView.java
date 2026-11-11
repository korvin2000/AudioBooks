package com.fanok.audiobooks;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

/** A wrap-content list bounded by screen space and approximately six single-line chapters. */
public class ChapterListView extends RecyclerView {
    public ChapterListView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int available = MeasureSpec.getSize(heightSpec);
        View sample = LayoutInflater.from(getContext()).inflate(R.layout.audio_recycler_item, this, false);
        ((TextView) sample.findViewById(R.id.title)).setText("Chapter");
        ((TextView) sample.findViewById(R.id.time)).setText("00:00:00");
        sample.measure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int max = ChapterListSizing.maxHeight(available, sample.getMeasuredHeight());
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
    }
}
