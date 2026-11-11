package com.fanok.audiobooks;

/** Bounds the chapter list while leaving room for the cover. */
public final class ChapterListSizing {
    private ChapterListSizing() { }

    public static int maxHeight(int availableHeight, int rowHeight) {
        return Math.max(0, Math.min(availableHeight / 2, rowHeight * 6));
    }
}
