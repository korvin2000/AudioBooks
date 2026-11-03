package com.fanok.audiobooks.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * The sync is a module the app plugs in at one place: App.onCreate calls CloudSync.install, and preferences.xml
 * opens its screen by name. No other app code may know it, so it can be replaced or removed there alone.
 */
public class SyncBoundaryTest {

    private static final String MODULE = "com.fanok.audiobooks.sync";

    @Test
    public void onlyTheAppClassReachesIntoTheModule() throws IOException {
        Path sources = Paths.get("src/main/java");
        assertTrue("run from the app module: " + sources.toAbsolutePath(), Files.isDirectory(sources));
        Path module = sources.resolve(MODULE.replace('.', '/'));
        List<String> users;
        try (Stream<Path> files = Files.walk(sources)) {
            users = files.filter(f -> f.toString().endsWith(".java") && !f.startsWith(module))
                    .filter(f -> read(f).contains(MODULE))
                    .map(f -> sources.relativize(f).toString().replace('\\', '/'))
                    .collect(Collectors.toList());
        }
        assertEquals(java.util.Collections.singletonList("com/fanok/audiobooks/App.java"), users);
    }

    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
