package com.fanok.audiobooks.room;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * A place the listener marked in a book (model.BookmarkRules, activity.BookmarkSheet): the chapter by its index in the
 * book's full list and by its name, the position in it and an optional note. Not the catalog's marks on books
 * (adapter.BookMarks).
 */
@Entity(tableName = "bookmark", indices = {@Index("url_book")})
public class BookmarkEntity {
    @PrimaryKey(autoGenerate = true)
    public int id;

    @NonNull
    @ColumnInfo(name = "url_book")
    public String urlBook = "";

    /** The chapter's index in the book's full list, as checkpoints keep it: the same online and offline. */
    @ColumnInfo(name = "chapter_index")
    public int chapterIndex;

    /** The chapter's name then: the way back when the site's list changed. */
    @NonNull
    @ColumnInfo(name = "chapter_name")
    public String chapterName = "";

    @ColumnInfo(name = "position_ms")
    public long positionMs;

    /** Null for none. */
    @ColumnInfo(name = "note")
    public String note;

    /** Epoch ms; orders the marks made at one place. */
    @ColumnInfo(name = "created_at")
    public long createdAt;
}
