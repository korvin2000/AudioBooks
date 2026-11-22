package com.fanok.audiobooks.service;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The library a car browses (Android Auto, a car's own media centre): the root holds a few lists, a list holds books,
 * and a book plays by its URL, the media id {@link MediaPlayerService#playFromMediaId} has always taken; the chapters
 * of the book in the player keep their numbers as ids. The ids and limits live here, apart from the service, so they
 * are tested on the JVM.
 */
public final class BrowseTree {

    public static final String NODE_CURRENT = "node:current";
    public static final String NODE_HISTORY = "node:history";
    public static final String NODE_FAVORITE = "node:favorite";
    public static final String NODE_SAVED = "node:saved";

    /** Android Auto on the phone: head units and the desktop head unit browse through it. */
    public static final String ANDROID_AUTO = "com.google.android.projection.gearhead";

    /** A list in a car is short (a head unit cuts long ones while driving), and a whole list crosses one binder call. */
    public static final int MAX_ITEMS = 50;

    private BrowseTree() {
    }

    /**
     * The root's lists in order, the book in the player first while there is one. Four at most: Android Auto shows the
     * root as that many tabs.
     */
    @NonNull
    public static List<String> rootNodes(boolean hasBook) {
        List<String> nodes = new ArrayList<>();
        if (hasBook) {
            nodes.add(NODE_CURRENT);
        }
        nodes.add(NODE_HISTORY);
        nodes.add(NODE_FAVORITE);
        nodes.add(NODE_SAVED);
        return nodes;
    }

    /** Whether a media id names a book by its URL; a node's id or a chapter's number never does. */
    public static boolean isBook(@Nullable String mediaId) {
        return mediaId != null && (mediaId.startsWith("https://") || mediaId.startsWith("http://"));
    }

    /**
     * The first of the at most {@link #MAX_ITEMS} chapters a car lists: from the one before the playing one, so the
     * next ones follow; a short book is listed whole. The session's queue keeps every chapter.
     */
    public static int firstListedChapter(int count, int playing) {
        return Math.max(0, Math.min(playing - 1, count - MAX_ITEMS));
    }

    /** A book's subtitle: «author — reader», either one alone, or null without both. */
    @Nullable
    public static String subtitle(@Nullable String author, @Nullable String reader) {
        String a = author != null ? author.trim() : "";
        String r = reader != null ? reader.trim() : "";
        if (a.isEmpty()) {
            return r.isEmpty() ? null : r;
        }
        return r.isEmpty() ? a : a + " — " + r;
    }
}
