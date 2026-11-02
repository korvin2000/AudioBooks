package com.fanok.audiobooks.room;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** A favourite author or reader (model.FavoritePeople): one row per kind and person, whatever site it came from. */
@Entity(tableName = "favorite_person", indices = {@Index(value = {"kind", "person_key"}, unique = true)})
public class FavoritePersonEntity {
    @PrimaryKey(autoGenerate = true)
    public int id;

    /** FavoritePeople.AUTHOR or READER. */
    @ColumnInfo(name = "kind")
    public int kind;

    /** The name as first added, for display. */
    @NonNull
    @ColumnInfo(name = "name")
    public String name = "";

    /** FavoritePeople.key(name): the same person however spelled. */
    @NonNull
    @ColumnInfo(name = "person_key")
    public String personKey = "";

    /** The person's listing pages on the sites, newline-separated, at most one per site. */
    @NonNull
    @ColumnInfo(name = "urls")
    public String urls = "";

    @ColumnInfo(name = "added_at")
    public long addedAt;
}
