package com.fanok.audiobooks.adapter;

import android.app.UiModeManager;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.view.ViewParent;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.RecyclerView;
import com.fanok.audiobooks.App;
import com.fanok.audiobooks.R;
import com.fanok.audiobooks.activity.MainActivity;

/**
 * A TV remote's place in a list whose rows have keys (URLs). On a TV the side menu comes first in the window, so when
 * the focused row leaves (a reload rebuilds the rows, Back re-creates the screen) Android hands the focus to the menu.
 * The row is remembered by its key and focused again once the new rows are laid out; a section just opened from the
 * menu focuses its first row. Off a TV it does nothing. One per screen, kept across its views.
 */
public final class ListFocus {

    /** An adapter whose rows have keys: the book's or the person's URL. */
    public interface Keyed {

        @Nullable
        String keyAt(int position);

        /** The row of {@code key}, or -1. */
        int positionOf(@NonNull String key);
    }

    private static final String SAVED = "listFocus";

    private static Boolean sTv;

    private boolean mTv;

    /** The focused row when the screen was left; to focus again if its view is re-created. */
    private String mLeft;

    /** The row to focus once the list has rows again, while the focus is lost (nowhere, or dropped on the menu). */
    private String mPending;

    public static boolean isTv(@NonNull Context context) {
        UiModeManager modes = (UiModeManager) context.getSystemService(Context.UI_MODE_SERVICE);
        return modes != null && modes.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;
    }

    /** The app runs on a TV: list adapters keep their rows across updates there (touch devices are left as they were). */
    public static boolean isTv() {
        if (sTv == null) {
            sTv = isTv(App.getInstance());
        }
        return sTv;
    }

    /** From onCreateView: a TV, and the row remembered across the activity's re-creation. */
    public void attach(boolean tv, @Nullable Bundle saved) {
        mTv = tv;
        if (saved != null && mPending == null) {
            mPending = saved.getString(SAVED);
        }
    }

    public void onPause(@Nullable RecyclerView list) {
        mLeft = focusedKey(list);
    }

    /**
     * Back to the same view: the window gives its focus back by itself; a screen that {@code reloads} its rows on
     * resume focuses the row again after that reload, which may come before the focus is back.
     */
    public void onResume(boolean reloads) {
        if (reloads && mLeft != null) {
            mPending = mLeft;
        }
        mLeft = null;
    }

    /** The view goes (another screen replaces it): its focused row is focused again in the next one. */
    public void onDestroyView() {
        if (mLeft != null) {
            mPending = mLeft;
        }
        mLeft = null;
    }

    public void onSaveInstanceState(@NonNull Bundle out) {
        String key = mLeft != null ? mLeft : mPending;
        if (key != null) {
            out.putString(SAVED, key);
        }
    }

    /** Right before the rows are replaced: the focused row, for {@link #afterUpdate}. */
    @Nullable
    public String beforeUpdate(@Nullable RecyclerView list) {
        return focusedKey(list);
    }

    /**
     * Right after {@code owner}'s rows were replaced: focuses the row that had the focus; else, while the focus is
     * lost, the row remembered from before; else the first row, if the side-menu entry that opened this screen still
     * holds the focus.
     */
    public void afterUpdate(@NonNull RecyclerView list, @Nullable String focused, @NonNull Fragment owner) {
        RecyclerView.Adapter<?> adapter = list.getAdapter();
        if (!mTv || !(adapter instanceof Keyed)) {
            return;
        }
        boolean section = takeSectionFocus(owner); // the request is this screen's, whatever its rows
        if (adapter.getItemCount() == 0) {
            return;
        }
        String key = focused;
        if (key == null && mPending != null) {
            if (focusLost(owner.getActivity(), list)) {
                key = mPending;
            } else {
                mPending = null; // the remote went elsewhere meanwhile
            }
        }
        if (key != null) {
            final String row = key;
            list.post(() -> { // after the layout the update asked for; the row is looked up then, it may have moved
                if (row.equals(mPending)) {
                    mPending = null;
                }
                RecyclerView.Adapter<?> now = list.getAdapter();
                if (now instanceof Keyed) {
                    focus(list, ((Keyed) now).positionOf(row), true);
                }
            });
        } else if (section) {
            focusFirst(list);
        }
    }

    /**
     * For a list without keys ({@code owner}'s rows were set): its first row takes the focus if the side-menu entry
     * that opened the screen still holds it.
     */
    public static void afterSectionUpdate(@NonNull RecyclerView list, @NonNull Fragment owner) {
        RecyclerView.Adapter<?> adapter = list.getAdapter();
        if (takeSectionFocus(owner) && adapter != null && adapter.getItemCount() > 0) {
            focusFirst(list);
        }
    }

    /** The first row of {@code list} takes the focus after the next layout. */
    public static void focusFirst(@NonNull RecyclerView list) {
        list.post(() -> focus(list, 0, true));
    }

    private static boolean takeSectionFocus(@NonNull Fragment owner) {
        FragmentActivity activity = owner.getActivity();
        return activity instanceof MainActivity && ((MainActivity) activity).takeSectionFocus(owner);
    }

    /** Nothing has the focus, or Android dropped it on the side menu, or it is in the list. */
    private static boolean focusLost(@Nullable FragmentActivity activity, @NonNull RecyclerView list) {
        View focus = activity == null ? null : activity.getCurrentFocus();
        if (focus == list) {
            return true;
        }
        for (ViewParent parent = focus == null ? null : focus.getParent(); parent instanceof View;
                parent = parent.getParent()) {
            if (parent == list || ((View) parent).getId() == R.id.liner_nav_view) {
                return true;
            }
        }
        return focus == null;
    }

    @Nullable
    private String focusedKey(@Nullable RecyclerView list) {
        if (!mTv || list == null || !(list.getAdapter() instanceof Keyed)) {
            return null;
        }
        View row = list.getFocusedChild();
        int position = row == null ? RecyclerView.NO_POSITION : list.getChildAdapterPosition(row);
        return position == RecyclerView.NO_POSITION ? null : ((Keyed) list.getAdapter()).keyAt(position);
    }

    private static void focus(@NonNull RecyclerView list, int position, boolean scroll) {
        RecyclerView.Adapter<?> adapter = list.getAdapter();
        if (adapter == null || position < 0 || position >= adapter.getItemCount() || !list.isAttachedToWindow()) {
            return;
        }
        RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(position);
        if (holder != null) {
            holder.itemView.requestFocus();
        } else if (scroll) {
            list.scrollToPosition(position);
            list.post(() -> focus(list, position, false));
        }
    }
}
