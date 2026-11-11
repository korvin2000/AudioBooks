package com.fanok.audiobooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * The UI mode service is asked for in two places, both through the application context: ListFocus.isTv (is this a
 * TV) and AppThemes (the app's night mode). A screen that asks for it itself is kept by Android 14 after it is
 * closed (ListFocus.isTv), so no other class may name the service.
 */
public class UiModeBoundaryTest {

    @Test
    public void onlyListFocusAndAppThemesNameTheService() throws IOException {
        Path sources = Paths.get("src/main/java");
        assertTrue("run from the app module: " + sources.toAbsolutePath(), Files.isDirectory(sources));
        List<String> users;
        try (Stream<Path> files = Files.walk(sources)) {
            users = files.filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> {
                        String source = read(f);
                        return source.contains("UiModeManager") || source.contains("UI_MODE_SERVICE");
                    })
                    .map(f -> sources.relativize(f).toString().replace('\\', '/'))
                    .sorted()
                    .collect(Collectors.toList());
        }
        assertEquals(Arrays.asList("com/fanok/audiobooks/AppThemes.java",
                "com/fanok/audiobooks/adapter/ListFocus.java"), users);
        for (String user : users) {
            String source = read(sources.resolve(user));
            assertEquals(user + " asks every time through the application context",
                    count(source, "getSystemService("), count(source, "getApplicationContext()"));
        }
    }

    private static int count(String source, String part) {
        int count = 0;
        for (int at = source.indexOf(part); at != -1; at = source.indexOf(part, at + part.length())) {
            count++;
        }
        return count;
    }

    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
