package com.fanok.audiobooks.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DefaultCoverTest {

    @Test
    public void sitePlaceholdersAreNoCover() {
        assertTrue(DefaultCover.placeholder("https://izib.uk/images/poster.png"));
        assertTrue(DefaultCover.placeholder("https://otrub.in/images/poster.png"));
        assertTrue(DefaultCover.placeholder("https://site.ru/templates/audiobooksdark/images/no-img.png"));
        assertTrue(DefaultCover.placeholder("https://m1.audiomir.xyz/public/adminpanel/images/noimage.jpg"));
        assertTrue(DefaultCover.placeholder("https://site.ru/images/editions/nocover-audio.jpg"));
        assertTrue(DefaultCover.placeholder("https://cdn.audiobook-mp3.com/no-cover2.jpg"));
        assertTrue(DefaultCover.placeholder("https://site.ru/images/no-poster.png?v=2"));
        assertTrue(DefaultCover.placeholder("https://site.ru/templates/abook/dleimages/no_image.jpg"));
        // real covers, whatever their names hold
        assertFalse(DefaultCover.placeholder("https://r7.audioknigi.xyz/6021823c8de5c247/pic/eb46d8d98a7001f6.jpg"));
        assertFalse(DefaultCover.placeholder("https://site.ru/uploads/posts/2020-06/1591644383_default.jpeg"));
        assertFalse(DefaultCover.placeholder("https://site.ru/uploads/books/12/poster.png"));
        assertFalse(DefaultCover.placeholder("https://site.ru/uploads/nocturne.jpg"));
        assertFalse(DefaultCover.placeholder(null));
    }

    @Test
    public void audiobooCoversAreReplaced() {
        assertTrue(DefaultCover.replaces("https://audioboo.org/uploads/posts/2024-01/cover.jpg"));
        assertTrue(DefaultCover.replaces("https://www.audioboo.org/uploads/cover.webp"));
        // saved before the move to audioboo.org
        assertTrue(DefaultCover.replaces("https://audioboo.ru/uploads/cover.jpg"));
        // staroeradio's pictures are real covers
        assertFalse(DefaultCover.replaces("https://staroeradio.ru/ap/staroeradio/images/pics/078167.jpg"));
    }

    @Test
    public void otherCoversAreLoaded() {
        assertFalse(DefaultCover.replaces("https://izib.uk/covers/1.jpg"));
        assertFalse(DefaultCover.replaces("content://com.android.providers/cover/1"));
        assertFalse(DefaultCover.replaces("/storage/emulated/0/AudioBooks/cover.jpg"));
        assertFalse(DefaultCover.replaces(""));
        assertFalse(DefaultCover.replaces(null));
    }

    @Test
    public void newBooksTakeTheNextPaletteInTurn() {
        int first = DefaultCover.paletteOf("https://audioboo.org/turn/1.html");
        for (int i = 1; i < 10; i++) {
            assertEquals((first + i) % 8, DefaultCover.paletteOf("https://audioboo.org/turn/" + (i + 1) + ".html"));
        }
    }

    @Test
    public void aBookKeepsItsPalette() {
        int palette = DefaultCover.paletteOf("https://audioboo.org/keep/1.html");
        DefaultCover.paletteOf("https://audioboo.org/keep/2.html");
        assertEquals(palette, DefaultCover.paletteOf("https://audioboo.org/keep/1.html"));
        // the same book under the host it had before
        assertEquals(palette, DefaultCover.paletteOf("https://audioboo.ru/keep/1.html"));
    }
}
