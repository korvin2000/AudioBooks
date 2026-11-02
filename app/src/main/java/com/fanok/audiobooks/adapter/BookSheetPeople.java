package com.fanok.audiobooks.adapter;

import android.view.View;
import android.widget.ImageButton;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.TooltipCompat;
import com.fanok.audiobooks.R;
import com.fanok.audiobooks.model.FavoritePeople;
import com.fanok.audiobooks.pojo.BookPOJO;
import java.util.List;

/** The favourite stars of the author and reader rows in the book long-press sheet (bootom_sheet_books_menu). */
public final class BookSheetPeople {

    private BookSheetPeople() {
    }

    /** Call once the sheet's rows have their visibility: a star shows only beside a visible row with a name. */
    public static void bind(View sheet, BookPOJO book) {
        star(sheet, R.id.author, R.id.authorStar, FavoritePeople.AUTHOR, book.getAutor(), book.getUrlAutor());
        star(sheet, R.id.artist, R.id.artistStar, FavoritePeople.READER, book.getArtist(), book.getUrlArtist());
    }

    /** The star toggles the field's FIRST person; the field's URL is that person's listing page. */
    private static void star(View sheet, int rowId, int starId, int kind, @Nullable String field,
            @Nullable String url) {
        ImageButton star = sheet.findViewById(starId);
        List<String> people = FavoritePeople.split(field);
        if (people.isEmpty() || sheet.findViewById(rowId).getVisibility() != View.VISIBLE) {
            star.setVisibility(View.GONE);
            return;
        }
        String name = people.get(0);
        FavoritePeople favorites = FavoritePeople.get(sheet.getContext());
        show(star, name, favorites.has(kind, name));
        star.setOnClickListener(v -> show(star, name, favorites.toggle(kind, name, url)));
        star.setVisibility(View.VISIBLE);
    }

    private static void show(ImageButton star, String name, boolean on) {
        star.setActivated(on);
        String text = star.getContext().getString(
                on ? R.string.people_star_remove_name : R.string.people_star_add_name, name);
        star.setContentDescription(text);
        TooltipCompat.setTooltipText(star, text);
    }
}
