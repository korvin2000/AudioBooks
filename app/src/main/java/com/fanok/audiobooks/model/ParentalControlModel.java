package com.fanok.audiobooks.model;


import androidx.annotation.NonNull;
import com.fanok.audiobooks.pojo.GenrePOJO;
import com.fanok.audiobooks.pojo.ParentControlPOJO;
import com.fanok.audiobooks.search.Provider;
import io.reactivex.Observable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Loads the category lists the allow-list editor offers. A source whose list cannot be loaded is reported in
 * {@link Loaded#failedSources}: its categories are then absent from the editor, so its books stay blocked
 * (unknown category) rather than silently allowed.
 */
public class ParentalControlModel {

    public static final class Loaded {
        public final ArrayList<ParentControlPOJO> items = new ArrayList<>();
        public final List<String> failedSources = new ArrayList<>();
    }

    private interface Loader extends Callable<ArrayList<ParentControlPOJO>> {
    }

    public Observable<Loaded> getBooks() {
        return Observable.fromCallable(() -> {
            Loaded loaded = new Loaded();
            for (Provider provider : Provider.values()) { // in Provider order
                if (provider.sections != null) {
                    load(loaded, provider.title,
                            () -> categories(provider.title, provider.parser.categories(provider.sections)));
                }
            }
            return loaded;
        });
    }

    private static void load(@NonNull Loaded loaded, @NonNull String source, @NonNull Loader loader) {
        try {
            ArrayList<ParentControlPOJO> items = loader.call();
            if (items.isEmpty()) {
                loaded.failedSources.add(source);
            } else {
                loaded.items.addAll(items);
            }
        } catch (Exception e) {
            android.util.Log.w("ParentalControlModel", "Categories of " + source + " not loaded", e);
            loaded.failedSources.add(source);
        }
    }

    /** {@code genres} of {@code source} as parental-control rows; a row without name or URL is left out. */
    private static ArrayList<ParentControlPOJO> categories(@NonNull String source, @NonNull List<GenrePOJO> genres) {
        ArrayList<ParentControlPOJO> result = new ArrayList<>();
        for (GenrePOJO genre : genres) {
            if (genre.getUrl() != null && genre.getName() != null) {
                result.add(new ParentControlPOJO(source, genre.getName(), genre.getUrl()));
            }
        }
        return result;
    }

}
