package com.fanok.audiobooks.fragment;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fanok.audiobooks.R;
import com.google.android.material.color.MaterialColors;

/**
 * A row of rounded cells, each in its own colour, with an optional line after the first {@code divider} cells and one
 * cell drawn full height while the others are a little shorter: the meter of the active sources (a cell per allowed
 * one), and the strip of every source in the position sheet. Decorative: its owner sets the content description.
 */
public class SourceSegmentsView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float gap;
    private final float line;
    private final int lineColor;
    private int[] colors = new int[0];
    private int divider = -1;
    private int highlighted = -1;

    public SourceSegmentsView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        gap = 2 * density;
        line = 2 * density;
        lineColor = MaterialColors.getColor(context, R.attr.colorPrimaryText, 0xFF808080);
    }

    /**
     * Draws {@code colors}, one cell each; a line after the first {@code divider} cells (none for -1 or an end), and
     * the cell {@code highlighted} (-1: none) full height.
     */
    public void setCells(@NonNull int[] colors, int divider, int highlighted) {
        this.colors = colors.clone();
        this.divider = divider;
        this.highlighted = highlighted;
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        int n = colors.length;
        if (n == 0) {
            return;
        }
        float width = getWidth() - getPaddingLeft() - getPaddingRight();
        float height = getHeight() - getPaddingTop() - getPaddingBottom();
        boolean split = divider > 0 && divider < n;
        float splitSpace = split ? gap * 2 + line : 0;
        float cell = (width - gap * (n - 1) - splitSpace) / n;
        float radius = Math.min(cell, height) / 3;
        float inset = highlighted >= 0 ? height * 0.18f : 0;
        float x = getPaddingLeft();
        for (int i = 0; i < n; i++) {
            if (split && i == divider) {
                paint.setColor(lineColor);
                float start = x + gap / 2; // centred in the gap after the last cell before the line
                canvas.drawRect(start, getPaddingTop(), start + line, getPaddingTop() + height, paint);
                x += splitSpace;
            }
            float shrink = i == highlighted ? 0 : inset;
            rect.set(x, getPaddingTop() + shrink, x + cell, getPaddingTop() + height - shrink);
            paint.setColor(colors[i]);
            canvas.drawRoundRect(rect, radius, radius, paint);
            x += cell + gap;
        }
    }
}
