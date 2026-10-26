package com.fanok.audiobooks.policy;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DefaultCoverTest {

    @Test
    public void audiobooCoversAreReplaced() {
        assertTrue(DefaultCover.replaces("https://audioboo.org/uploads/posts/2024-01/cover.jpg"));
        assertTrue(DefaultCover.replaces("https://www.audioboo.org/uploads/cover.webp"));
        // saved before the move to audioboo.org
        assertTrue(DefaultCover.replaces("https://audioboo.ru/uploads/cover.jpg"));
    }

    @Test
    public void otherCoversAreLoaded() {
        assertFalse(DefaultCover.replaces("https://izib.uk/covers/1.jpg"));
        assertFalse(DefaultCover.replaces("content://com.android.providers/cover/1"));
        assertFalse(DefaultCover.replaces("/storage/emulated/0/AudioBooks/cover.jpg"));
        assertFalse(DefaultCover.replaces(""));
        assertFalse(DefaultCover.replaces(null));
    }
}
