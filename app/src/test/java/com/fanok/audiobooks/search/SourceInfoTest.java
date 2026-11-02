package com.fanok.audiobooks.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fanok.audiobooks.region.Region;

import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Every site has its facts and pictures in the assets, readable and small; nothing else lives there. */
public class SourceInfoTest {

    /** What the pictures may weigh: they ship in every APK. */
    private static final long COVER_BYTES = 12 * 1024;
    private static final long ICON_BYTES = 5 * 1024;
    private static final long INFO_BYTES = 2 * 1024;
    private static final Set<String> REGIONS = new HashSet<>(Arrays.asList("RU", "UA"));

    private static File root() {
        File dir = new File("src/main/assets/" + SourceInfo.ROOT);
        return dir.isDirectory() ? dir : new File("app/src/main/assets/" + SourceInfo.ROOT);
    }

    private static File file(Provider provider, String name) {
        return new File(root().getParentFile(), SourceInfo.dir(provider) + "/" + name);
    }

    private static SourceInfo read(Provider provider) throws IOException {
        try (Reader in = new InputStreamReader(new FileInputStream(file(provider, SourceInfo.INFO)),
                StandardCharsets.UTF_8)) {
            return SourceInfo.parse(in);
        }
    }

    @Test
    public void everySiteHasItsFactsAndIcon() throws IOException {
        for (Provider provider : Provider.values()) {
            File info = file(provider, SourceInfo.INFO);
            assertTrue(provider + ": " + info, info.isFile());
            assertTrue(provider + " info.json is small", info.length() <= INFO_BYTES);
            File icon = file(provider, SourceInfo.ICON);
            assertTrue(provider + ": " + icon, icon.isFile());
            assertTrue(provider + " icon is small", icon.length() <= ICON_BYTES);
            File cover = file(provider, SourceInfo.COVER); // optional: a site behind a challenge has none
            assertTrue(provider + " cover is small", !cover.exists() || cover.length() <= COVER_BYTES);

            SourceInfo facts = read(provider);
            assertTrue(provider + " about", facts.about.length() >= 150 && facts.about.length() <= 400);
            assertTrue(provider + " books", facts.books == null || facts.books > 0);
            assertTrue(provider + " booksAt", facts.booksAt == null || facts.booksAt.matches("\\d{4}-\\d{2}"));
            assertTrue(provider + " country", facts.country == null || facts.country.matches("[A-Z]{2}"));
            assertTrue(provider + " since", facts.since == null || facts.since.matches("\\d{4}(-\\d{2})?"));
            assertTrue(provider + " blocked", facts.blocked == null || REGIONS.containsAll(facts.blocked));
            assertFalse(provider + " is not related to itself", facts.related.contains(provider));
            assertTrue(provider + " pluses", facts.pluses.size() <= 4);
            assertTrue(provider + " minuses", facts.minuses.size() <= 4);
        }
    }

    @Test
    public void noFolderWithoutASite() {
        Set<String> known = new HashSet<>();
        for (Provider provider : Provider.values()) {
            known.add(SourceInfo.dir(provider).substring(SourceInfo.ROOT.length() + 1));
        }
        File[] dirs = root().listFiles();
        assertNotNull(dirs);
        for (File dir : dirs) {
            if (!known.contains(dir.getName())) {
                fail("no site for " + dir);
            }
        }
    }

    @Test
    public void aSiteBlockedInARegionWantsTheProxyThere() {
        SourceInfo ru = SourceInfo.parse(new StringReader("{\"about\": \"Сайт.\", \"blocked\": [\"RU\"]}"));
        assertEquals(Boolean.TRUE, ru.blockedIn(Region.RUSSIA));
        assertEquals(Boolean.FALSE, ru.blockedIn(Region.UKRAINE));
        assertEquals(Boolean.FALSE, ru.blockedIn(Region.WEST));
        assertNull("no region yet", ru.blockedIn(null));
        assertNull("unknown", SourceInfo.parse(new StringReader("{\"about\": \"Сайт.\"}")).blockedIn(Region.RUSSIA));
    }

    @Test
    public void anUnknownFactIsNullAndAMalformedFileIsAnError() {
        SourceInfo facts = SourceInfo.parse(new StringReader("{\"about\": \"Сайт.\", \"books\": null, "
                + "\"fragments\": \"some\", \"related\": [\"knigoblud\"], \"accent\": \"#164041\"}"));
        assertNull(facts.books);
        assertNull(facts.blocked);
        assertNull(facts.takedowns);
        assertEquals(SourceInfo.Fragments.SOME, facts.fragments);
        assertEquals(Arrays.asList(Provider.KNIGOBLUD), facts.related);
        assertEquals(Integer.valueOf(0xFF164041), facts.accent);
        assertTrue(facts.mirrors.isEmpty());
        try {
            SourceInfo.parse(new StringReader("{\"books\": 5}"));
            fail("no about");
        } catch (IllegalArgumentException expected) {
            // the file is broken: the loader logs it and the card goes without facts
        }
    }
}
