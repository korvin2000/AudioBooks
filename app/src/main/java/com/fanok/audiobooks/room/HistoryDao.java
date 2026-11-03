package com.fanok.audiobooks.room;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

@Dao
public interface HistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(HistoryEntity entity);

    @Delete
    void delete(HistoryEntity entity);

    @Query("DELETE FROM history WHERE url_book = :url")
    void deleteByUrl(String url);

    @Query("DELETE FROM history")
    void deleteAll();

    /**
     * Oldest opened first. By the time, not the row id: a cloud sync adds books opened on another device at their
     * own time; undated rows (0: older versions, restored ones) come first, in the order they were added.
     */
    @Query("SELECT * FROM history ORDER BY opened_at, id")
    List<HistoryEntity> getAll();

    /** The books whose page was opened at {@code since} or later (epoch ms), oldest first like {@link #getAll}. */
    @Query("SELECT * FROM history WHERE opened_at >= :since ORDER BY opened_at, id")
    List<HistoryEntity> getSince(long since);

    @Query("SELECT * FROM history ORDER BY opened_at DESC, id DESC LIMIT 1")
    HistoryEntity getLast();

    @Query("SELECT * FROM history WHERE url_book = :url")
    HistoryEntity getByUrl(String url);

    @Query("SELECT COUNT(id) FROM history WHERE url_book = :url")
    int count(String url);

    /** The page of every book, for counting the books of each site (search.SourceLibrary). */
    @Query("SELECT url_book FROM history")
    List<String> getUrls();

    @Query("SELECT genre FROM history WHERE genre <> '' GROUP BY genre ORDER BY genre ASC")
    List<String> getGenres();

    @Query("SELECT author FROM history WHERE author <> '' GROUP BY author ORDER BY author ASC")
    List<String> getAuthors();

    @Query("SELECT artist FROM history WHERE artist <> '' GROUP BY artist ORDER BY artist ASC")
    List<String> getArtists();

    @Query("SELECT series FROM history WHERE series <> '' GROUP BY series ORDER BY series ASC")
    List<String> getSeries();
}
