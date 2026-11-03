package com.fanok.audiobooks.sync;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import androidx.annotation.Nullable;
import com.fanok.audiobooks.model.AudioDBModel;
import com.fanok.audiobooks.model.BooksDBModel;
import com.fanok.audiobooks.model.FavoritePeople;
import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.pojo.FavoritePersonPOJO;
import com.fanok.audiobooks.pojo.StorageUtil;
import com.fanok.audiobooks.pojo.TimeStartPOJO;
import com.fanok.audiobooks.room.AppDatabase;
import com.fanok.audiobooks.room.BookBaseEntity;
import com.fanok.audiobooks.room.FavoriteEntity;
import com.fanok.audiobooks.room.HistoryEntity;
import com.fanok.audiobooks.service.Checkpoint;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What this device holds of the synced state, read as a {@link Snapshot} and changed to match a merged one: the
 * favourite books, the history, the favourite people (Room), the listening positions (the player's checkpoints and
 * the book lists' last-chapter rows) and the {@link SyncedSettings}.
 */
final class DeviceState {

    private DeviceState() {
    }

    /** This device's state now. */
    static Snapshot read(Context app) {
        Snapshot s = new Snapshot();
        AppDatabase db = AppDatabase.getDatabase(app);
        List<FavoriteEntity> favorites = db.favoriteDao().getAll();
        for (int i = favorites.size() - 1; i >= 0; i--) { // newest first
            s.fav.put(favorites.get(i).urlBook, 0L);
            s.books.put(favorites.get(i).urlBook, book(favorites.get(i)));
        }
        for (HistoryEntity e : db.historyDao().getAll()) {
            s.hist.put(e.urlBook, e.openedAt);
            s.books.put(e.urlBook, book(e));
        }
        for (FavoritePersonPOJO p : FavoritePeople.get(app).backup()) {
            s.people.put(Snapshot.person(p.getKind(), FavoritePeople.key(p.getName())), p);
        }
        Map<String, String> chapters = new HashMap<>();
        for (TimeStartPOJO audio : new AudioDBModel(app).getAll()) {
            chapters.put(audio.getUrl(), audio.getName());
        }
        String prefix = Checkpoint.key("");
        for (Map.Entry<String, ?> e : storage(app).getAll().entrySet()) {
            Checkpoint c = e.getKey().startsWith(prefix) && e.getValue() instanceof String
                    ? Checkpoint.parse((String) e.getValue()) : null;
            if (c != null && e.getKey().equals(Checkpoint.key(c.bookUrl))) {
                s.pos.put(c.bookUrl, new Snapshot.Pos(c.chapterIndex, c.positionMs, c.at, chapters.get(c.bookUrl)));
            }
        }
        s.prefs = SyncedSettings.read(app);
        return s;
    }

    /**
     * Writes what {@code merged} changed against {@code mine} and returns the state this device now shares with the
     * cloud: its next base. What cannot be applied here (a book without a title, a setting this version does not
     * take) stays in it as this device has it, so the next merge reads the cloud's value as the cloud's, not as one
     * deleted here. A value that changed here since {@code mine} was read (a book opened, a place played meanwhile)
     * is left as it is: it is newer, and the next sync takes it up.
     */
    static Snapshot apply(Context app, Snapshot mine, Snapshot merged) throws Exception {
        Snapshot kept = merged.copy();
        boolean books = applyBooks(app, mine, merged, kept);
        boolean people = applyPeople(app, mine, merged, kept);
        applyPlaces(app, mine, merged, kept);
        SyncedSettings.apply(app, mine.prefs, merged.prefs, kept.prefs);
        if (books || people) {
            app.sendBroadcast(new Intent(BooksDBModel.ACTION_CHANGED).setPackage(app.getPackageName()));
        }
        return kept;
    }

    /** The favourites and the history, in one transaction; whether any row changed. */
    private static boolean applyBooks(Context app, Snapshot mine, Snapshot merged, Snapshot kept) {
        AppDatabase db = AppDatabase.getDatabase(app);
        BooksDBModel books = new BooksDBModel(app);
        return db.runInTransaction(() -> {
            boolean changed = false;
            List<String> added = new ArrayList<>();
            for (String url : merged.fav.keySet()) {
                if (!mine.fav.containsKey(url)) {
                    added.add(url);
                }
            }
            for (int i = added.size() - 1; i >= 0; i--) { // oldest first: the newest ends on top
                String url = added.get(i);
                BookPOJO book = valid(url, merged.books.get(url));
                if (book == null) {
                    unapplied(kept.fav, mine.fav, url);
                } else if (!books.inFavorite(url)) {
                    books.addFavorite(book);
                    changed = true;
                }
            }
            for (String url : mine.fav.keySet()) {
                if (!merged.fav.containsKey(url) && books.inFavorite(url)) {
                    books.removeFavorite(mine.books.get(url));
                    changed = true;
                }
            }
            for (Map.Entry<String, Long> e : merged.hist.entrySet()) {
                String url = e.getKey();
                Long was = mine.hist.get(url);
                if (e.getValue().equals(was)) {
                    continue;
                }
                BookPOJO book = valid(url, merged.books.get(url));
                HistoryEntity now = db.historyDao().getByUrl(url);
                if (book == null) {
                    unapplied(kept.hist, mine.hist, url);
                } else if (Objects.equals(now == null ? null : now.openedAt, was)) {
                    books.addHistory(book, e.getValue());
                    changed = true;
                }
            }
            for (Map.Entry<String, Long> e : mine.hist.entrySet()) {
                HistoryEntity now = merged.hist.containsKey(e.getKey()) ? null
                        : db.historyDao().getByUrl(e.getKey());
                if (now != null && now.openedAt == e.getValue()) {
                    books.removeHistory(mine.books.get(e.getKey()));
                    changed = true;
                }
            }
            return changed;
        });
    }

    /** The favourite people, through FavoritePeople's own write thread; whether any row changed. */
    private static boolean applyPeople(Context app, Snapshot mine, Snapshot merged, Snapshot kept) throws Exception {
        List<FavoritePersonPOJO> added = new ArrayList<>(), removed = new ArrayList<>();
        for (Map.Entry<String, FavoritePersonPOJO> e : merged.people.entrySet()) {
            FavoritePersonPOJO p = e.getValue();
            if (mine.people.containsKey(e.getKey())) {
                continue;
            }
            // a person keyed by another version's name rules would come back here under another key
            String key = FavoritePeople.key(p.getName());
            if ((p.getKind() == FavoritePeople.AUTHOR || p.getKind() == FavoritePeople.READER) && key != null
                    && e.getKey().equals(Snapshot.person(p.getKind(), key))) {
                added.add(p);
            } else {
                unapplied(kept.people, mine.people, e.getKey());
            }
        }
        for (Map.Entry<String, FavoritePersonPOJO> e : mine.people.entrySet()) {
            if (!merged.people.containsKey(e.getKey())) {
                removed.add(e.getValue());
            }
        }
        if (added.isEmpty() && removed.isEmpty()) {
            return false;
        }
        FavoritePeople.get(app).update(added, removed);
        return true;
    }

    /** The listening positions: the player's checkpoints, then the book lists' last chapter and its seconds. */
    private static void applyPlaces(Context app, Snapshot mine, Snapshot merged, Snapshot kept) {
        SharedPreferences storage = storage(app);
        SharedPreferences.Editor places = storage.edit();
        // the database waits until the places are written: a place the player saves between this loop's check
        // and the apply would be overwritten, so that window stays as short as the loop
        Map<String, Snapshot.Pos> moved = new HashMap<>();
        for (Map.Entry<String, Snapshot.Pos> e : merged.pos.entrySet()) {
            String url = e.getKey();
            Snapshot.Pos to = e.getValue(), was = mine.pos.get(url);
            if (to.equals(was)) {
                continue;
            }
            // the place of a book neither listed nor played here would only fill the storage
            if (url.isEmpty() || to.c < 0 || to.ms < 0
                    || was == null && !merged.fav.containsKey(url) && !merged.hist.containsKey(url)) {
                unapplied(kept.pos, mine.pos, url);
                continue;
            }
            Checkpoint now = Checkpoint.parse(storage.getString(Checkpoint.key(url), null));
            if (Objects.equals(now == null ? null : new Snapshot.Pos(now.chapterIndex, now.positionMs, 0, null),
                    was)) {
                // a player holding this book takes the place up at its next Play
                places.putString(Checkpoint.key(url), new Checkpoint(url, to.c, to.ms, to.t).serialize());
                moved.put(url, to);
            }
        }
        places.apply();
        AudioDBModel audio = new AudioDBModel(app);
        for (Map.Entry<String, Snapshot.Pos> e : moved.entrySet()) {
            String title = e.getValue().n;
            if (title != null && !title.isEmpty()) {
                audio.add(e.getKey(), title);
                audio.setTime(e.getKey(), (int) (e.getValue().ms / 1000));
            }
        }
    }

    /** {@code key} stays in {@code kept} as this device has it: the cloud's value is not taken here. */
    static <V> void unapplied(Map<String, V> kept, Map<String, V> mine, String key) {
        V value = mine.get(key);
        if (value == null) {
            kept.remove(key);
        } else {
            kept.put(key, value);
        }
    }

    /** {@code book} when a row of {@code url} can be made of it. */
    @Nullable
    private static BookPOJO valid(String url, @Nullable BookPOJO book) {
        return book != null && !book.isNull() && url.equals(book.getUrl()) && book.getName() != null
                && !book.getName().trim().isEmpty() ? book : null;
    }

    private static BookPOJO book(BookBaseEntity e) {
        BookPOJO book = e.toPojo();
        book.setDesc(null);
        return book;
    }

    private static SharedPreferences storage(Context app) {
        return app.getSharedPreferences(StorageUtil.STORAGE, Context.MODE_PRIVATE);
    }
}
