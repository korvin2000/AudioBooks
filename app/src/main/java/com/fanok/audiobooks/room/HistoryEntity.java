package com.fanok.audiobooks.room;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;

@Entity(tableName = "history", indices = {@Index(value = {"url_book"}, unique = true)})
public class HistoryEntity extends BookBaseEntity {
    /** When the book's page was last opened (epoch ms); 0 for rows from before version 20 and restored ones. */
    @ColumnInfo(name = "opened_at", defaultValue = "0")
    public long openedAt;
}
