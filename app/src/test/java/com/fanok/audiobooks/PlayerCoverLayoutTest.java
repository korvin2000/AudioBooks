package com.fanok.audiobooks;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

/** The expanded player shows the book's cover above the chapter list. */
public class PlayerCoverLayoutTest {
    @Test public void coverSitsAboveTheChapterList() throws Exception {
        String layout = new String(Files.readAllBytes(Paths.get("src/main/res/layout/player.xml")),
                StandardCharsets.UTF_8);
        int cover = layout.indexOf("android:id=\"@+id/playerCover\"");
        int list = layout.indexOf("android:id=\"@+id/list\"");
        assertTrue("the player has a cover", cover >= 0);
        assertTrue("the cover comes before the chapter list", cover < list);
    }

    @Test public void coverFitsWithoutCroppingAndListHasBoundedHeight() throws Exception {
        String layout = new String(Files.readAllBytes(Paths.get("src/main/res/layout/player.xml")),
                StandardCharsets.UTF_8);
        assertTrue(layout.contains("android:scaleType=\"fitCenter\""));
        assertTrue(layout.contains("com.fanok.audiobooks.ChapterListView"));
    }

    @Test public void chapterHeightReservesRoomAndLimitsRows() {
        org.junit.Assert.assertEquals(120, ChapterListSizing.maxHeight(1200, 20));
        org.junit.Assert.assertEquals(50, ChapterListSizing.maxHeight(100, 20));
        org.junit.Assert.assertEquals(0, ChapterListSizing.maxHeight(0, 20));
    }

    @Test public void playerIncludesBookDetailsAndChapterHeading() throws Exception {
        String layout = new String(Files.readAllBytes(Paths.get("src/main/res/layout/player.xml")),
                StandardCharsets.UTF_8);
        assertTrue(layout.contains("@+id/playerBookTitle"));
        assertTrue(layout.contains("@+id/playerBookAuthor"));
        assertTrue(layout.contains("@+id/playerChaptersHeading"));
    }
}
