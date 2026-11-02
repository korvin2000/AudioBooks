package com.fanok.audiobooks.room;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

@Dao
public interface FavoritePersonDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(FavoritePersonEntity entity);

    @Query("UPDATE favorite_person SET urls = :urls WHERE id = :id")
    void updateUrls(int id, String urls);

    @Query("DELETE FROM favorite_person WHERE kind = :kind AND person_key = :key")
    void delete(int kind, String key);

    @Query("DELETE FROM favorite_person WHERE id = :id")
    void deleteId(int id);

    @Query("DELETE FROM favorite_person WHERE kind = :kind")
    void deleteKind(int kind);

    @Query("DELETE FROM favorite_person")
    void deleteAll();

    /** Newest first. */
    @Query("SELECT * FROM favorite_person WHERE kind = :kind ORDER BY added_at DESC, id DESC")
    List<FavoritePersonEntity> getAll(int kind);

    @Query("SELECT * FROM favorite_person WHERE kind = :kind AND person_key = :key")
    FavoritePersonEntity get(int kind, String key);

    @Query("SELECT name FROM favorite_person WHERE kind = :kind")
    List<String> getNames(int kind);
}
