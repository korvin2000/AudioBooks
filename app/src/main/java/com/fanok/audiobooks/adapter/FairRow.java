package com.fanok.audiobooks.adapter;

import android.content.Context;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * The book list's author | reader line: two one-line texts, the author at the start and the reader at the end, that
 * share the width so that as little as possible is lost. Each name comes with shorter spellings
 * ({@link NameFit#variants}); while measuring, the row measures them with the texts' own paint and shows the pair
 * {@link NameFit#choose} picks, cut with ".." only when no spelling fits. The full names stay the texts' content
 * descriptions. The reader's paddingStart is the gap between the two.
 * <p>
 * Widths come from the paint, never from {@code TextView.measure()}: a recycled text measured UNSPECIFIED answers
 * from its measure cache, with the width of a book shown before (the old row's random cuts). The texts are set here,
 * not by the adapter, and both are {@code 0dp} wide in the layout, so a new text only redraws them
 * ({@code TextView.checkForRelayout}) instead of asking for another layout pass.
 */
public class FairRow extends ViewGroup {

    private static final String CUT = "..";

    private TextView mAuthor;
    private TextView mReader;
    /** The names as given; their spellings, [0] in full, null for no name; their widths with the icons. */
    private String mAuthorName;
    private String mReaderName;
    private String[] mAuthors;
    private String[] mReaders;
    private int[] mAuthorWidths;
    private int[] mReaderWidths;
    /** The room the shown texts were chosen for, and the widths of the two halves. */
    private int mRoom = -1;
    private int mAuthorWidth;
    private int mReaderWidth;

    public FairRow(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mAuthor = (TextView) getChildAt(0);
        mReader = (TextView) getChildAt(1);
    }

    /** A book's names; a null or blank one hides its half. */
    public void setNames(String author, String reader) {
        if (TextUtils.equals(author, mAuthorName) && TextUtils.equals(reader, mReaderName)) {
            return; // the same book again: nothing to redo (both texts start hidden, as for no names)
        }
        mAuthorName = author;
        mReaderName = reader;
        String[][] names = NameFit.variants(author, reader);
        String[] authors = names[0];
        String[] readers = names[1];
        mAuthors = authors;
        mReaders = readers;
        mAuthorWidths = null;
        mReaderWidths = null;
        mRoom = -1;
        mAuthor.setVisibility(authors == null ? GONE : VISIBLE);
        mReader.setVisibility(readers == null ? GONE : VISIBLE);
        mAuthor.setContentDescription(authors == null ? null : authors[0]);
        mReader.setContentDescription(readers == null ? null : readers[0]);
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int room = Math.max(0, width - getPaddingLeft() - getPaddingRight());
        int childHeight = getChildMeasureSpec(heightSpec, getPaddingTop() + getPaddingBottom(),
                LayoutParams.WRAP_CONTENT);
        if (room != mRoom) {
            for (TextView text : new TextView[]{mAuthor, mReader}) { // resolves drawableStart: the icons' width
                if (text.getVisibility() != GONE) {
                    text.measure(MeasureSpec.makeMeasureSpec(room, MeasureSpec.AT_MOST), childHeight);
                }
            }
            fit(room);
        }
        int height = Math.max(measure(mAuthor, mAuthorWidth, childHeight),
                measure(mReader, mReaderWidth, childHeight));
        setMeasuredDimension(width, resolveSize(height + getPaddingTop() + getPaddingBottom(), heightSpec));
    }

    private void fit(int room) {
        if (mAuthorWidths == null && mReaderWidths == null) {
            mAuthorWidths = widths(mAuthor, mAuthors);
            mReaderWidths = widths(mReader, mReaders);
        }
        int[] choice = NameFit.choose(mAuthorWidths, mReaderWidths, room);
        mAuthorWidth = choice[2];
        mReaderWidth = choice[3];
        show(mAuthor, mAuthors, mAuthorWidths, choice[0], mAuthorWidth);
        show(mReader, mReaders, mReaderWidths, choice[1], mReaderWidth);
        mRoom = room;
    }

    private static int[] widths(TextView text, String[] names) {
        if (names == null) {
            return null;
        }
        int[] widths = new int[names.length];
        for (int k = 0; k < names.length; k++) {
            widths[k] = names[k] == null ? -1 : (int) Math.ceil(text.getPaint().measureText(names[k])) + 1
                    + text.getCompoundPaddingLeft() + text.getCompoundPaddingRight();
        }
        return widths;
    }

    private static void show(TextView text, String[] names, int[] widths, int step, int width) {
        if (names == null) {
            return;
        }
        String shown = widths[step] <= width ? names[step] : cut(text, names[step],
                width - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight() - 1);
        if (!TextUtils.equals(text.getText(), shown)) {
            text.setText(shown);
        }
    }

    /** The longest start of the name that fits with ".." after it, without a space or a separator before the dots. */
    private static String cut(TextView text, String name, float width) {
        TextPaint paint = text.getPaint();
        float dots = paint.measureText(CUT);
        if (width < dots) {
            return "";
        }
        int n = paint.breakText(name, true, width - dots, null);
        while (n > 0 && " ,;/&-(\"«".indexOf(name.charAt(n - 1)) >= 0) {
            n--;
        }
        return name.substring(0, n) + CUT;
    }

    /** Measures a shown text at its width; its height, 0 when hidden. */
    private static int measure(View child, int width, int heightSpec) {
        if (child.getVisibility() == GONE) {
            return 0;
        }
        child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightSpec);
        return child.getMeasuredHeight();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL; // the author at the start, the reader at the end
        View atLeft = rtl ? mReader : mAuthor;
        View atRight = rtl ? mAuthor : mReader;
        place(atLeft, getPaddingLeft(), bottom - top);
        place(atRight, right - left - getPaddingRight() - atRight.getMeasuredWidth(), bottom - top);
    }

    /** A shown text at x, centred in the row's height. */
    private void place(View child, int x, int height) {
        if (child.getVisibility() != GONE) {
            int y = getPaddingTop() + (height - getPaddingTop() - getPaddingBottom() - child.getMeasuredHeight()) / 2;
            child.layout(x, y, x + child.getMeasuredWidth(), y + child.getMeasuredHeight());
        }
    }
}
