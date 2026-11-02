package com.fanok.audiobooks.pojo;

import java.util.ArrayList;
import java.util.List;

/** A favourite author or reader in a backup (model.FavoritePeople). */
public class FavoritePersonPOJO {
    /** FavoritePeople.AUTHOR or READER. */
    private int kind;
    private String name;
    /** The person's listing pages on the sites; may be absent. */
    private ArrayList<String> urls;
    private long addedAt;

    public FavoritePersonPOJO() {
    }

    public FavoritePersonPOJO(int kind, String name, List<String> urls, long addedAt) {
        this.kind = kind;
        this.name = name;
        this.urls = urls == null ? null : new ArrayList<>(urls);
        this.addedAt = addedAt;
    }

    public int getKind() {
        return kind;
    }

    public String getName() {
        return name;
    }

    /** Never null. */
    public List<String> getUrls() {
        return urls == null ? new ArrayList<>() : urls;
    }

    public long getAddedAt() {
        return addedAt;
    }
}
