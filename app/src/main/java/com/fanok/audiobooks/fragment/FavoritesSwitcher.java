package com.fanok.audiobooks.fragment;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.PopupMenu;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import com.fanok.audiobooks.Consts;
import com.fanok.audiobooks.R;
import com.fanok.audiobooks.activity.MainActivity;
import com.fanok.audiobooks.model.BooksDBModel;
import com.fanok.audiobooks.model.FavoritePeople;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import java.util.function.IntConsumer;

/**
 * The title of Избранное: "★ Книги ⌄" on the activity's shared toolbar, a menu of the three lists (books, authors,
 * readers) with their sizes. The list on screen puts it up and takes it down with its view; the choice is remembered,
 * so the drawer, the start screen and the links open the list chosen last. The history puts up the same switcher for
 * its listening status (FavoriteFragment).
 */
public final class FavoritesSwitcher {
    /** The books; the people lists are {@link FavoritePeople#AUTHOR} and {@link FavoritePeople#READER}. */
    public static final int BOOKS = -1;
    /** The tag every list of Избранное is shown under: switching keeps the back stack as it is. */
    public static final String TAG = "favorite";
    private static final String PREF_KIND = "favorites_kind";
    private static final int[] KINDS = {BOOKS, FavoritePeople.AUTHOR, FavoritePeople.READER};
    private static final int[] TITLES = {R.string.favorites_books, R.string.favorites_authors,
            R.string.favorites_readers};

    private FavoritesSwitcher() {
    }

    /** The list chosen last (the books at first). */
    @NonNull
    public static Fragment remembered(@NonNull Context context) {
        return create(PreferenceManager.getDefaultSharedPreferences(context).getInt(PREF_KIND, BOOKS));
    }

    @NonNull
    static Fragment create(int kind) {
        return kind == FavoritePeople.AUTHOR || kind == FavoritePeople.READER
                ? FavoritePeopleFragment.newInstance(kind)
                : FavoriteFragment.newInstance(R.string.menu_favorite, Consts.TABLE_FAVORITE);
    }

    static int title(int kind) {
        return TITLES[index(kind)];
    }

    private static int index(int kind) {
        return kind == FavoritePeople.AUTHOR ? 1 : kind == FavoritePeople.READER ? 2 : 0;
    }

    /** Opens the menu of a switcher: {@code anchor} is the switcher, {@code arrow} its arrow. */
    interface Opener {
        void open(@NonNull MainActivity activity, @NonNull View anchor, @NonNull View arrow);
    }

    /** Puts the switcher on the toolbar for {@code owner}, the list {@code kind} on screen, in place of the title. */
    static void install(@NonNull Fragment owner, int kind) {
        Context context = owner.getContext();
        if (context == null) {
            return;
        }
        String name = context.getString(title(kind));
        install(owner, R.drawable.ic_favorite_remove, name, context.getString(R.string.favorites_switcher_desc, name),
                (activity, anchor, arrow) -> open(activity, anchor, arrow, kind));
    }

    /**
     * Puts a switcher ("{@code icon} {@code name} ⌄") on the toolbar for {@code owner}, in place of the title; a tap
     * calls {@code opener}. Избранное and the history (its listening status) share it.
     */
    static void install(@NonNull Fragment owner, @DrawableRes int icon, @NonNull String name,
            @NonNull String description, @NonNull Opener opener) {
        MainActivity activity = owner.getActivity() instanceof MainActivity ? (MainActivity) owner.getActivity() : null;
        Toolbar toolbar = activity == null ? null : activity.findViewById(R.id.toolbar);
        ActionBar bar = activity == null ? null : activity.getSupportActionBar();
        if (toolbar == null || bar == null) {
            return;
        }
        // one switcher per toolbar: the list coming in may take it over before the leaving one lets it go
        View view = tagged(toolbar);
        if (view == null) {
            view = LayoutInflater.from(toolbar.getContext())
                    .inflate(R.layout.toolbar_favorites_switcher, toolbar, false);
            toolbar.addView(view);
            toolbar.setTag(R.id.favoritesSwitcher, view);
        }
        view.setTag(owner);
        bar.setDisplayShowTitleEnabled(false);
        bar.setSubtitle(null);
        ((ImageView) view.findViewById(R.id.favoritesSwitcherIcon)).setImageResource(icon);
        ((TextView) view.findViewById(R.id.favoritesSwitcherTitle)).setText(name);
        view.setContentDescription(description);
        view.findViewById(R.id.favoritesSwitcherSubtitle).setVisibility(View.GONE);
        View arrow = view.findViewById(R.id.favoritesSwitcherArrow);
        arrow.setRotation(0);
        final View anchor = view;
        view.setOnClickListener(v -> opener.open(activity, anchor, arrow));
    }

    /** Takes the switcher down if {@code owner} has it; the toolbar shows the title again. */
    static void uninstall(@NonNull Fragment owner) {
        View view = switcher(owner);
        if (view == null) {
            return;
        }
        Toolbar toolbar = toolbar(owner);
        if (view.getParent() == null && toolbar != null && toolbar.hasExpandedActionView()) {
            // an expanded search keeps the switcher off the toolbar and would put it back on the next screen
            toolbar.collapseActionView();
        }
        if (view.getParent() instanceof Toolbar) {
            ((Toolbar) view.getParent()).removeView(view);
        }
        if (toolbar != null) {
            toolbar.setTag(R.id.favoritesSwitcher, null);
        }
        ActionBar bar = ((MainActivity) owner.requireActivity()).getSupportActionBar();
        if (bar != null) {
            bar.setDisplayShowTitleEnabled(true);
        }
    }

    /** The subtitle under the switcher (an active filter); false if {@code owner} has no switcher. */
    static boolean setSubtitle(@NonNull Fragment owner, @Nullable CharSequence text) {
        View view = switcher(owner);
        if (view == null) {
            return false;
        }
        TextView subtitle = view.findViewById(R.id.favoritesSwitcherSubtitle);
        subtitle.setText(text);
        subtitle.setVisibility(text == null || text.length() == 0 ? View.GONE : View.VISIBLE);
        return true;
    }

    /** The switcher {@code owner} has, also while an expanded search keeps it off the toolbar (no parent then). */
    @Nullable
    private static View switcher(@NonNull Fragment owner) {
        Toolbar toolbar = toolbar(owner);
        View view = toolbar == null ? null : tagged(toolbar);
        return view != null && view.getTag() == owner ? view : null;
    }

    @Nullable
    private static Toolbar toolbar(@NonNull Fragment owner) {
        return owner.getActivity() == null ? null : owner.getActivity().findViewById(R.id.toolbar);
    }

    /** The toolbar keeps its switcher in a tag: findViewById cannot see it while a search is expanded. */
    @Nullable
    private static View tagged(@NonNull Toolbar toolbar) {
        Object view = toolbar.getTag(R.id.favoritesSwitcher);
        return view instanceof View ? (View) view : null;
    }

    /** The menu of the lists with their sizes, counted off the main thread; the arrow points up while it is open. */
    @SuppressLint("CheckResult") // one short read; the result checks the view is still there
    private static void open(@NonNull MainActivity activity, @NonNull View anchor, @NonNull View arrow, int kind) {
        if (arrow.getRotation() != 0) {
            return; // already opening or open
        }
        arrow.setRotation(180);
        Context app = activity.getApplicationContext();
        Single.fromCallable(() -> {
                    FavoritePeople people = FavoritePeople.get(app);
                    return new int[]{new BooksDBModel(app).getUrls(Consts.TABLE_FAVORITE).size(),
                            people.count(FavoritePeople.AUTHOR), people.count(FavoritePeople.READER)};
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(counts -> showMenu(activity, anchor, arrow, kind, counts),
                        e -> showMenu(activity, anchor, arrow, kind, null));
    }

    private static void showMenu(@NonNull MainActivity activity, @NonNull View anchor, @NonNull View arrow, int kind,
            @Nullable int[] counts) {
        String[] names = new String[KINDS.length];
        for (int i = 0; i < KINDS.length; i++) {
            String name = activity.getString(TITLES[i]);
            names[i] = counts == null ? name : activity.getString(R.string.favorites_list_count, name, counts[i]);
        }
        choose(activity, anchor, arrow, names, index(kind), i -> {
            int chosen = KINDS[i];
            if (chosen != kind) {
                PreferenceManager.getDefaultSharedPreferences(activity).edit().putInt(PREF_KIND, chosen).apply();
                activity.switchFavorites(create(chosen));
            }
        });
    }

    /**
     * The menu of a switcher: {@code names} with the one at {@code checked} ticked; {@code pick} gets the index
     * chosen. The arrow points up while it is open.
     */
    static void choose(@NonNull MainActivity activity, @NonNull View anchor, @NonNull View arrow,
            @NonNull String[] names, int checked, @NonNull IntConsumer pick) {
        if (!anchor.isAttachedToWindow() || activity.isFinishing()) {
            arrow.setRotation(0);
            return;
        }
        PopupMenu popup = new PopupMenu(activity, anchor);
        Menu menu = popup.getMenu();
        for (int i = 0; i < names.length; i++) {
            menu.add(0, i, i, names[i]);
        }
        menu.setGroupCheckable(0, true, true);
        menu.getItem(checked).setChecked(true);
        popup.setOnMenuItemClickListener(item -> {
            pick.accept(item.getItemId());
            return true;
        });
        arrow.setRotation(180);
        popup.setOnDismissListener(p -> arrow.setRotation(0));
        popup.show();
    }
}
