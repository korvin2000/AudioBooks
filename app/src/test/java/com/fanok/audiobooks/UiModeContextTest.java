package com.fanok.audiobooks;

import static org.junit.Assert.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

/** Guard against obtaining the Android UI mode service with a short-lived activity context. */
public class UiModeContextTest {
    @Test public void uiModeServiceUsesApplicationContext() throws Exception {
        String[] files = {"fragment/BooksFragment.java", "fragment/DescriptionBookFragment.java",
                "fragment/SettingsFragment.java", "fragment/AboutFragment.java"};
        for (String file : files) {
            String source = new String(Files.readAllBytes(Paths.get("src/main/java/com/fanok/audiobooks", file)),
                    StandardCharsets.UTF_8);
            assertFalse(file, source.matches("(?s).*requireContext\\(\\)\\.getSystemService\\(\\s*UI_MODE_SERVICE\\).*"));
        }
        String focus = new String(Files.readAllBytes(Paths.get("src/main/java/com/fanok/audiobooks/adapter/ListFocus.java")), StandardCharsets.UTF_8);
        assertFalse(focus.contains("context.getSystemService(Context.UI_MODE_SERVICE)"));
        String themes = new String(Files.readAllBytes(Paths.get("src/main/java/com/fanok/audiobooks/AppThemes.java")), StandardCharsets.UTF_8);
        assertFalse(themes.contains("context.getSystemService(UiModeManager.class)"));
    }
}
