package com.fanok.audiobooks.util;

import android.graphics.Outline;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import androidx.annotation.NonNull;
import com.fanok.audiobooks.R;

/**
 * Gently rounded corners for a book's cover: of the picture as it is drawn, which may leave a part of its view empty
 * (the player's cover fits a fixed box), so all four corners are the picture's own. The view's shadow, when it has
 * an elevation, follows the same shape.
 */
public final class CoverCorners {

    private CoverCorners() {
    }

    public static void round(@NonNull ImageView view) {
        float radius = view.getResources().getDimension(R.dimen.book_cover_radius);
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                RectF shown = new RectF(0, 0, v.getWidth(), v.getHeight());
                Drawable picture = view.getDrawable();
                if (picture != null) {
                    RectF drawn = new RectF(picture.getBounds());
                    view.getImageMatrix().mapRect(drawn);
                    drawn.offset(view.getPaddingLeft(), view.getPaddingTop());
                    shown.intersect(drawn); // no common part (nothing drawn yet): the view's own bounds stay
                }
                outline.setRoundRect(Math.round(shown.left), Math.round(shown.top), Math.round(shown.right),
                        Math.round(shown.bottom), radius);
            }
        });
        view.setClipToOutline(true);
        // a picture of another shape asks for a layout, which alone does not rebuild the outline of a view whose
        // size stays
        view.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                v.invalidateOutline());
    }
}
