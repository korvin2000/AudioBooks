package com.fanok.audiobooks.adapter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.preference.PreferenceManager;
import com.fanok.audiobooks.R;
import com.google.android.material.color.MaterialColors;

/**
 * Marks of a book the catalog or the search shows that the user met before: in the favourites, the downloads, the
 * history. A stripe down the card's leading edge (its foreground), a segment per mark, and the title of a book opened
 * before dimmed, as a chapter listened to is in the player's list: the card keeps its size and its texts.
 */
public final class BookMarks {

    public static final int HISTORY = 1;
    public static final int FAVORITE = 2;
    public static final int DOWNLOADED = 4;

    /** «Оформление → Метки книг в каталоге». */
    public static final String PREF = "book_marks_shown";

    private static final int DOWNLOADED_COLOR = 0xFF66BB6A;
    private static final int HISTORY_COLOR = 0xFF9575CD;
    /** On a light card, where the two above are too pale for a stripe. */
    private static final int DOWNLOADED_COLOR_LIGHT = 0xFF388E3C;
    private static final int HISTORY_COLOR_LIGHT = 0xFF673AB7;
    /** The title of a book opened before; still at least 4.5:1 on each theme's card. */
    private static final int OPENED_ALPHA = 0x99;

    private BookMarks() {
    }

    /** Whether the lists mark books at all. */
    public static boolean enabled(@NonNull Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF, true);
    }

    /** Shows {@code marks} (0: none) on a row: {@code card} its whole item, {@code title} in it. */
    static void bind(@NonNull View card, @NonNull TextView title, int marks) {
        int text = MaterialColors.getColor(title, R.attr.colorPrimaryText);
        marks &= HISTORY | FAVORITE | DOWNLOADED;
        if (marks == 0) {
            card.setForeground(null);
            title.setTextColor(text);
            return;
        }
        Drawable stripe = card.getForeground() instanceof Stripe ? card.getForeground() : new Stripe(card, text);
        ((Stripe) stripe).set(marks);
        card.setForeground(stripe);
        title.setTextColor((marks & HISTORY) != 0 ? ColorUtils.setAlphaComponent(text, OPENED_ALPHA) : text);
    }

    /**
     * A stripe down the card's leading edge, split along its height into a segment per mark (the favourite, downloaded,
     * opened, from the top), and a hairline of the card's colour that parts it from the cover it lies on.
     */
    private static final class Stripe extends Drawable {
        private static final float WIDTH_DP = 4;
        private static final float HAIRLINE_DP = 1;
        private static final int[] ORDER = {FAVORITE, DOWNLOADED, HISTORY};
        private final float width;
        private final float hairline;
        /** The marks' colours, as {@link #ORDER}. */
        private final int[] colors;
        private final int cardColor;
        private final Paint paint = new Paint();
        private int marks;

        /** @param text the card's text colour: dark on a light card, whose stripe takes the deeper colours */
        Stripe(@NonNull View card, int text) {
            float density = card.getResources().getDisplayMetrics().density;
            width = WIDTH_DP * density;
            hairline = HAIRLINE_DP * density;
            boolean light = ColorUtils.calculateLuminance(text) < 0.5;
            colors = new int[]{MaterialColors.getColor(card, R.attr.favoriteColor),
                    light ? DOWNLOADED_COLOR_LIGHT : DOWNLOADED_COLOR, light ? HISTORY_COLOR_LIGHT : HISTORY_COLOR};
            cardColor = MaterialColors.getColor(card, R.attr.backgroundItem);
        }

        void set(int marks) {
            if (this.marks != marks) {
                this.marks = marks;
                invalidateSelf();
            }
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            float left = rtl ? bounds.right - width : bounds.left;
            float segment = bounds.height() / (float) Math.max(1, Integer.bitCount(marks));
            float top = bounds.top;
            for (int i = 0; i < ORDER.length; i++) {
                if ((marks & ORDER[i]) != 0) {
                    paint.setColor(colors[i]);
                    canvas.drawRect(left, top, left + width, top + segment, paint);
                    top += segment;
                }
            }
            paint.setColor(cardColor);
            float inner = rtl ? left - hairline : left + width;
            canvas.drawRect(inner, bounds.top, inner + hairline, bounds.bottom, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            // opaque: a card's foreground is not faded
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
