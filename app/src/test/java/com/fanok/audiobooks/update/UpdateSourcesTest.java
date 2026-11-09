package com.fanok.audiobooks.update;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fanok.audiobooks.update.UpdateSources.Release;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class UpdateSourcesTest {

    private static final String APK_SHA = "a8d9f079" + "0123456789abcdef0123456789abcdef0123456789abcdef" + "0000b282";
    private static final String TXT_SHA = "13d67bfe" + "0123456789abcdef0123456789abcdef0123456789abcdef" + "0000f88d";
    private static final String APK_LINK = "https://downloader.disk.yandex.ru/disk/aa11/6af2083a/apk"
            + "?filename=AudioBooks-8.3.apk&disposition=attachment";
    private static final String TXT_LINK = "https://downloader.disk.yandex.ru/disk/bb22/6af2083a/txt"
            + "?filename=update.txt&disposition=attachment";
    /** What the API's rate limit answers. */
    private static final String RATE_LIMITED = "{\"error\":\"TooManyRequestsError\",\"message\":\"Too many requests\"}";
    /** The folder's listing as the public API answers (2026-11-09), the links shortened. */
    private static final String DISK = "{\"_embedded\":{\"items\":["
            + "{\"name\":\"AudioBooks-8.3.apk\",\"file\":\"" + APK_LINK + "\",\"sha256\":\"" + APK_SHA + "\"},"
            + "{\"name\":\"update.txt\",\"file\":\"" + TXT_LINK + "\",\"sha256\":\"" + TXT_SHA + "\"}]}}";

    /** The lofi topic page as 4pda serves it (2026-10-31), cut to the header's download list and two posts' files. */
    private static final String TOPIC = "<div class=\"postcontent\">Версия: 7.8<br>…"
            + "<span style=\"color:blue\"><strong>Скачать:</strong></span><br>"
            + "<strong>Версия: 7.8</strong> <a title=\"Ссылка\" rel=\"nofollow\" "
            + "href=\"https://4pda.to/forum/index.php?showtopic=1128418&amp;view=findpost&amp;p=145728948\" "
            + "target=\"_blank\">от 28/10/2026 (удален2000)</a><br>"
            + "<strong>Версия: 7.7</strong> <a title=\"Ссылка\" rel=\"nofollow\" "
            + "href=\"https://4pda.to/forum/index.php?showtopic=1128418&amp;view=findpost&amp;p=145700355\" "
            + "target=\"_blank\">от 27/10/2026 (удален2000)</a><br>"
            + "<strong>Версия: 7.4 </strong> <a title=\"Ссылка\" rel=\"nofollow\" "
            + "href=\"https://4pda.to/forum/index.php?showtopic=1128418&amp;view=findpost&amp;p=145669993\" "
            + "target=\"_blank\">обновление с GitHub</a><br>"
            + "<strong>Версия: 7.3</strong> <a class=\"ipb-attach attach-file\" "
            + "href=\"https://4pda.to/forum/dl/post/36400708/audiobook-7.3.apk\" data-rel=\"lyteframe\">x</a></div>"
            + "<div class=\"ipbpagespan\"><a href=\"https://4pda.to/forum/lofiversion/index.php?t1128418-20.html\">2</a></div>"
            + "<div class=\"postcontent\"><a class=\"ipb-attach attach-file\" "
            + "href=\"https://4pda.to/forum/dl/post/36417090/AudioBooks-7.7.apk\" title=\"Скачать AudioBooks-7.7.apk\">"
            + "</a><a href=\"https://4pda.to/forum/dl/post/36429233/NewScreenshot01.png\"></a>"
            + "<a class=\"ipb-attach attach-file\" href=\"https://4pda.to/forum/dl/post/36429236/AudioBooks-7.8.apk\" "
            + "title=\"Скачать AudioBooks-7.8.apk\"></a></div>";

    @Test
    public void comparesDottedVersionsAsNumbers() {
        assertTrue(UpdateSources.compare("7.10", "7.9.1") > 0);
        assertTrue(UpdateSources.compare("7.9.1", "7.9") > 0);
        assertTrue(UpdateSources.compare("7.8", "7.9.1") < 0);
        assertEquals(0, UpdateSources.compare("7.9.0", "7.9"));
        assertEquals(0, UpdateSources.compare("7.9.1", "7.9.1"));
    }

    @Test
    public void githubReleaseComesFromTheTagAndItsChecksums() {
        String tag = UpdateSources.githubTag("https://github.com/korvin2000/AudioBooks/releases/tag/v7.9.1");
        assertEquals("v7.9.1", tag);
        assertEquals("7.9.1", UpdateSources.versionOf(tag));
        assertNull(UpdateSources.versionOf("nightly"));
        assertNull(UpdateSources.githubTag("https://github.com/korvin2000/AudioBooks/releases"));

        String sums = "BFA478d6d9e12d776bfe4d7e5346de708fbacce2c4aa009dc3b401bbdae596c0  AudioBooks-7.9.1.apk\n"
                + "127ce02877793fcaaef1024c2b8607d224c75d64ba6921e7a40c1f931574fed2  AudioBooks-7.9.1.aab\n";
        Release release = UpdateSources.githubRelease(tag, "7.9.1", sums);
        assertEquals("https://github.com/korvin2000/AudioBooks/releases/download/v7.9.1/AudioBooks-7.9.1.apk",
                release.apkUrl);
        assertEquals("bfa478d6d9e12d776bfe4d7e5346de708fbacce2c4aa009dc3b401bbdae596c0", release.sha256);
        assertNull(UpdateSources.githubRelease(tag, "7.9.1", "127ce0  AudioBooks-7.9.1.aab"));
    }

    @Test
    public void mirrorFileNamesTheApkNextToIt() {
        Release release = UpdateSources.mirrorRelease("\r\nversion=7.9.1\r\nfile=AudioBooks-7.9.1.apk\r\n");
        assertEquals("7.9.1", release.version);
        assertEquals("https://www.abc-guitars.com/data/audiobook/AudioBooks-7.9.1.apk", release.apkUrl);
        assertNull(release.sha256);

        String sha = "bfa478d6d9e12d776bfe4d7e5346de708fbacce2c4aa009dc3b401bbdae596c0";
        assertEquals(sha, UpdateSources.mirrorRelease("version=8.0\nfile=a.apk\nsha256=" + sha).sha256);
        assertNull(UpdateSources.mirrorRelease("version=8.0\nfile=../x.apk"));
        assertNull(UpdateSources.mirrorRelease("version=8.0"));
        assertNull(UpdateSources.mirrorRelease("<html>Not found</html>"));
    }

    @Test
    public void diskListingGivesEachFilesLink() {
        assertArrayEquals(new String[]{TXT_LINK, TXT_SHA}, UpdateSources.diskFile(DISK, "update.txt"));
        assertArrayEquals(new String[]{APK_LINK, APK_SHA}, UpdateSources.diskFile(DISK, "AudioBooks-8.3.apk"));
        assertNull(UpdateSources.diskFile(DISK, "missing.apk"));
        assertNull(UpdateSources.diskFile(RATE_LIMITED, "update.txt"));
        assertNull(UpdateSources.diskFile("<html>Bad gateway</html>", "update.txt"));
        assertNull(UpdateSources.diskFile("", "update.txt"));
    }

    @Test
    public void diskReleaseTakesTheApkFromTheListing() {
        Release release = UpdateSources.diskRelease("version=8.3\r\nfile=AudioBooks-8.3.apk\r\n", DISK);
        assertEquals("8.3", release.version);
        assertEquals(APK_LINK, release.apkUrl);
        assertEquals(APK_SHA, release.sha256);

        String sha = "bfa478d6d9e12d776bfe4d7e5346de708fbacce2c4aa009dc3b401bbdae596c0";
        assertEquals(sha, UpdateSources.diskRelease("version=8.3\nfile=AudioBooks-8.3.apk\nsha256=" + sha, DISK).sha256);
        // no hash in the listing, none in update.txt: the file is not checked against one
        assertNull(UpdateSources.diskRelease("version=8.3\nfile=AudioBooks-8.3.apk",
                DISK.replace(",\"sha256\":\"" + APK_SHA + "\"", "")).sha256);
    }

    @Test
    public void diskReleaseRejectsBadInput() {
        String text = "version=8.3\nfile=AudioBooks-8.3.apk";
        assertNull(UpdateSources.diskRelease("version=8.4\nfile=AudioBooks-8.4.apk", DISK)); // not in the folder
        assertNull(UpdateSources.diskRelease("version=8.3\nfile=../x.apk", DISK));
        assertNull(UpdateSources.diskRelease("<html>Not found</html>", DISK));
        assertNull(UpdateSources.diskRelease(text, RATE_LIMITED));
        assertNull(UpdateSources.diskRelease(text, "<html>Bad gateway</html>"));
        assertNull(UpdateSources.diskRelease(text, ""));
        assertNull(UpdateSources.diskRelease(text, DISK.replace("https://downloader.disk.yandex.ru/disk/aa11",
                "http://downloader.disk.yandex.ru/disk/aa11")));
    }

    @Test
    public void yandexDiskIsAskedFirstOnlyInRussia() {
        assertEquals(Arrays.asList("github", "mirror", "disk"), UpdateSources.routeOrder(false, "github", "mirror", "disk"));
        assertEquals(Arrays.asList("disk", "github", "mirror"), UpdateSources.routeOrder(true, "github", "mirror", "disk"));
    }

    @Test
    public void forumHeaderGivesTheNewestVersionAndItsPost() {
        assertArrayEquals(new String[]{"7.8",
                        "https://4pda.to/forum/index.php?showtopic=1128418&view=findpost&p=145728948"},
                UpdateSources.forumNewest(TOPIC));
        assertNull(UpdateSources.forumNewest("<title>Just a moment...</title>"));
    }

    @Test
    public void forumFileIsTheLinkItselfOrTheApkNamedForTheVersion() {
        String post = "https://4pda.to/forum/index.php?showtopic=1128418&view=findpost&p=145728948";
        assertEquals("https://4pda.to/forum/dl/post/36429236/AudioBooks-7.8.apk",
                UpdateSources.forumFile(post, TOPIC, "7.8"));
        assertEquals("https://4pda.to/forum/dl/post/36400708/audiobook-7.3.apk", UpdateSources.forumFile(
                "https://4pda.to/forum/dl/post/36400708/audiobook-7.3.apk", TOPIC, "7.3"));
        assertNull(UpdateSources.forumFile(post, TOPIC, "7.9")); // not "7.9" inside "7.9.1" either
        assertNull(UpdateSources.forumFile(post, TOPIC.replace("7.7.apk", "17.7.apk"), "7.7"));
        assertEquals(Collections.singletonList("https://4pda.to/forum/lofiversion/index.php?t1128418-20.html"),
                UpdateSources.forumLastPages(TOPIC));
        assertTrue(UpdateSources.forumLastPages("<p>one page</p>").isEmpty());
        String pager = "t1128418-20.html t1128418-40.html t1128418-160.html t1128418-180.html";
        assertEquals(Arrays.asList("https://4pda.to/forum/lofiversion/index.php?t1128418-180.html",
                "https://4pda.to/forum/lofiversion/index.php?t1128418-160.html",
                "https://4pda.to/forum/lofiversion/index.php?t1128418-140.html",
                "https://4pda.to/forum/lofiversion/index.php?t1128418-120.html",
                "https://4pda.to/forum/lofiversion/index.php?t1128418-100.html"),
                UpdateSources.forumLastPages(pager));
    }

    @Test
    public void forumDownloadPageGivesTheSessionLink() {
        String page = "<div>AudioBooks-7.8.apk Размер: 4.66 МБ.</div><a id=\"REyqJTZAz12x\" "
                + "href=\"https://4pda.to/forum/index.php?act=attach&amp;id=36429236&amp;dlsess=277331685e\" "
                + "target=\"_blank\">Скачать AudioBooks-7.8.apk</a>";
        assertEquals("https://4pda.to/forum/index.php?act=attach&id=36429236&dlsess=277331685e",
                UpdateSources.forumAttach(page));
        assertNull(UpdateSources.forumAttach("<p>Ошибка 404</p>"));
    }
}
