package com.fanok.audiobooks.room;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

@Dao
public interface BookmarkDao {

    /** @return the row's id; a mark with its old id (an undone delete) comes back as it was. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(BookmarkEntity entity);

    @Query("UPDATE bookmark SET note = :note WHERE id = :id")
    void updateNote(int id, String note);

    @Query("DELETE FROM bookmark WHERE id = :id")
    void delete(int id);

    /** In book order: chapter, then position, then the order they were made in. */
    @Query("SELECT * FROM bookmark WHERE url_book = :urlBook ORDER BY chapter_index, position_ms, created_at")
    List<BookmarkEntity> getByBook(String urlBook);
}
