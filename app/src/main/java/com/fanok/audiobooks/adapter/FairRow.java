package com.fanok.audiobooks.adapter;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

/**
 * A row of two one-line texts, the first {@code 0dp} with weight 1, the second {@code wrap_content}, that share the
 * width fairly: both keep their natural width while they fit; otherwise the longer one gives way first, and neither
 * is cut below half the row (a plain LinearLayout lets the second take the whole row and hide the first). With
 * {@code gravity="end"} a lone second text stays at the end; the first takes no margins (the room is the two widths).
 * The book list's author | reader line.
 */
public class FairRow extends LinearLayout {

    public FairRow(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        View start = getChildAt(0);
        View end = getChildAt(1);
        if (end == null || start.getVisibility() == GONE || end.getVisibility() == GONE) {
            return;
        }
        int room = start.getMeasuredWidth() + end.getMeasuredWidth(); // the weighted start got what end left
        int startHeight = MeasureSpec.makeMeasureSpec(start.getMeasuredHeight(), MeasureSpec.EXACTLY);
        start.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), startHeight); // its natural width
        int endWidth = Math.min(end.getMeasuredWidth(), Math.max(room / 2, room - start.getMeasuredWidth()));
        start.measure(MeasureSpec.makeMeasureSpec(room - endWidth, MeasureSpec.EXACTLY), startHeight);
        end.measure(MeasureSpec.makeMeasureSpec(endWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(end.getMeasuredHeight(), MeasureSpec.EXACTLY));
    }
}
