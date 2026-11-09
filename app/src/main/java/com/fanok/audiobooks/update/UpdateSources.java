package com.fanok.audiobooks.update;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a new version of the app is published and how each place says so; pure Java, so the parsing is tested on the JVM.
 * GitHub releases first, then the owner's mirror and its copy on Yandex Disk, then the app's topic on 4pda (readable by
 * guests only in its "lofi" form; its files need a forum login).
 */
public final class UpdateSources {

    /** Redirects to {@code …/releases/tag/<tag>} of the newest release (drafts and pre-releases excluded). */
    static final String GITHUB_LATEST = "https://github.com/korvin2000/AudioBooks/releases/latest";
    static final String GITHUB_DOWNLOAD = "https://github.com/korvin2000/AudioBooks/releases/download/";
    static final String MIRROR = "https://www.abc-guitars.com/data/audiobook/";
    static final String MIRROR_INFO = MIRROR + "update.txt";
    /**
     * The listing of the owner's public Yandex Disk folder {@code https://disk.yandex.ru/d/UsQE3CEkNPSOUw} (the mirror's
     * files again): each file with a fresh direct link, which lives a few hours, and its SHA-256. Not the API's
     * {@code …/download}, which answers 429 to anonymous callers.
     */
    static final String DISK_LIST = "https://cloud-api.yandex.net/v1/disk/public/resources?public_key="
            + "https%3A%2F%2Fdisk.yandex.ru%2Fd%2FUsQE3CEkNPSOUw&limit=100"
            + "&fields=_embedded.items.name,_embedded.items.file,_embedded.items.sha256";
    public static final String FORUM_TOPIC = "https://4pda.to/forum/lofiversion/index.php?t1128418.html";

    private static final Pattern TAG = Pattern.compile("/releases/tag/([^/?#]+)$");
    private static final Pattern VERSION = Pattern.compile("\\d+(?:\\.\\d+)*");
    /** A file name of the mirror: no path, nothing but an APK. */
    private static final Pattern FILE = Pattern.compile("[A-Za-z0-9._-]+\\.apk");
    private static final Pattern SUM = Pattern.compile("(?m)^([0-9a-fA-F]{64})\\s+\\*?(\\S+\\.apk)\\s*$");
    /** An entry of the topic header's download list: {@code <strong>Версия: 7.8</strong> <a … href="…">}. */
    private static final Pattern FORUM_ENTRY = Pattern.compile(
            "Версия:\\s*(\\d+(?:\\.\\d+)*)\\s*</strong>\\s*<a\\b[^>]*?href=\"([^\"]+)\"");
    private static final Pattern FORUM_FILE = Pattern.compile(
            "href=\"(https://4pda\\.to/forum/dl/post/\\d+/([^\"/]+\\.apk))\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern FORUM_PAGE = Pattern.compile("t1128418-(\\d+)\\.html");
    /** Topic pages searched for a release's file, from the last one back. */
    static final int FORUM_PAGES = 5;
    private static final Pattern FORUM_ATTACH = Pattern.compile(
            "href=\"(https://4pda\\.to/forum/index\\.php\\?act=attach[^\"]+)\"");

    private UpdateSources() {
    }

    /** A published version: where its APK is and, when the source gives it, the APK's SHA-256 (lower case). */
    public static final class Release {
        @NonNull public final String version;
        @NonNull public final String apkUrl;
        @Nullable public final String sha256;
        /** The forum post with the file, for a 4pda release: the user logs in there. */
        @Nullable public final String page;

        Release(@NonNull String version, @NonNull String apkUrl, @Nullable String sha256, @Nullable String page) {
            this.version = version;
            this.apkUrl = apkUrl;
            this.sha256 = sha256 == null ? null : sha256.toLowerCase(Locale.ROOT);
            this.page = page;
        }

        @NonNull
        @Override
        public String toString() {
            return version + " " + apkUrl;
        }
    }

    /** Compares dotted numeric versions ("7.10" > "7.9.1" > "7.9"); a part that is not a number counts as 0. */
    public static int compare(@NonNull String a, @NonNull String b) {
        String[] x = a.trim().split("\\.");
        String[] y = b.trim().split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            long p = i < x.length ? number(x[i]) : 0;
            long q = i < y.length ? number(y[i]) : 0;
            if (p != q) {
                return p < q ? -1 : 1;
            }
        }
        return 0;
    }

    private static long number(String part) {
        try {
            return Long.parseLong(part.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The release tag in the {@code Location} of {@link #GITHUB_LATEST}, or null. */
    @Nullable
    static String githubTag(@Nullable String location) {
        Matcher m = location == null ? null : TAG.matcher(location);
        return m != null && m.find() ? m.group(1) : null;
    }

    /** The version a tag names ("v7.9.1" → "7.9.1"), or null when it names none. */
    @Nullable
    static String versionOf(@NonNull String tag) {
        String v = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
        return VERSION.matcher(v).matches() ? v : null;
    }

    /** The release's APK from its {@code SHA256SUMS.txt}: the APK listed there with its hash; null when none is. */
    @Nullable
    static Release githubRelease(@NonNull String tag, @NonNull String version, @NonNull String sums) {
        Matcher m = SUM.matcher(sums);
        return m.find() ? new Release(version, GITHUB_DOWNLOAD + tag + "/" + m.group(2), m.group(1), null) : null;
    }

    /** The mirror's {@code update.txt}: {@code version=}, {@code file=} and an optional {@code sha256=}; null when unusable. */
    @Nullable
    static Release mirrorRelease(@NonNull String text) {
        String[] info = info(text);
        return info == null ? null : new Release(info[0], MIRROR + info[1], info[2], null);
    }

    /** The release on Yandex Disk: the {@code update.txt} {@code text} names a file, whose link {@code listing} gives. */
    @Nullable
    static Release diskRelease(@NonNull String text, @NonNull String listing) {
        String[] info = info(text);
        String[] link = info == null ? null : diskFile(listing, info[1]);
        return link == null ? null : new Release(info[0], link[0], info[2] != null ? info[2] : link[1], null);
    }

    /**
     * {@code name}'s direct link in {@link #DISK_LIST}'s answer and its SHA-256 (null when the listing has none); null
     * when the answer is not a listing (an error, a page) or the file is not in it. {@code [0]} the link, {@code [1]} the hash.
     */
    @Nullable
    static String[] diskFile(@NonNull String listing, @NonNull String name) {
        try {
            JsonElement items = child(child(JsonParser.parseString(listing), "_embedded"), "items");
            if (items != null && items.isJsonArray()) {
                for (JsonElement item : items.getAsJsonArray()) {
                    String link = string(item, "file");
                    if (link != null && link.startsWith("https://") && name.equals(string(item, "name"))) {
                        return new String[]{link, sum(string(item, "sha256"))};
                    }
                }
            }
        } catch (JsonParseException e) {
            // not JSON: no listing
        }
        return null;
    }

    @Nullable
    private static JsonElement child(@Nullable JsonElement parent, @NonNull String name) {
        return parent != null && parent.isJsonObject() ? parent.getAsJsonObject().get(name) : null;
    }

    @Nullable
    private static String string(@Nullable JsonElement parent, @NonNull String name) {
        JsonElement value = child(parent, name);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    /** {@code sha} when it is a SHA-256 in hex, else null. */
    @Nullable
    private static String sum(@Nullable String sha) {
        return sha != null && sha.matches("[0-9a-fA-F]{64}") ? sha : null;
    }

    /**
     * An {@code update.txt}: {@code [0]} the version, {@code [1]} the file (no path, an APK), {@code [2]} the SHA-256 or
     * null; null when the text is not one.
     */
    @Nullable
    private static String[] info(@NonNull String text) {
        String version = null;
        String file = null;
        String sha = null;
        for (String line : text.split("\\r?\\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(eq + 1).trim();
            if (key.equals("version")) {
                version = value;
            } else if (key.equals("file")) {
                file = value;
            } else if (key.equals("sha256")) {
                sha = value;
            }
        }
        if (version == null || !VERSION.matcher(version).matches() || file == null || !FILE.matcher(file).matches()) {
            return null;
        }
        return new String[]{version, file, sum(sha)};
    }

    /**
     * The places asked on one route, in order: GitHub, the mirror, Yandex Disk; in Russia Yandex Disk first, as it is
     * what answers there while GitHub and the mirror stall.
     */
    @NonNull
    static <T> List<T> routeOrder(boolean russia, @NonNull T github, @NonNull T mirror, @NonNull T disk) {
        return russia ? Arrays.asList(disk, github, mirror) : Arrays.asList(github, mirror, disk);
    }

    /**
     * The newest version in the topic header's download list ("Скачать:"), with the link next to it: a forum post or
     * the file itself. Null when the list is not found. {@code [0]} the version, {@code [1]} the link.
     */
    @Nullable
    static String[] forumNewest(@NonNull String html) {
        int start = html.indexOf("Скачать:");
        if (start < 0) {
            return null;
        }
        Matcher m = FORUM_ENTRY.matcher(html);
        m.region(start, html.length());
        String[] best = null;
        while (m.find()) {
            if (best == null || compare(m.group(1), best[0]) > 0) {
                best = new String[]{m.group(1), m.group(2).replace("&amp;", "&")};
            }
        }
        return best;
    }

    /** The forum's file link for {@code version} in a topic page: the link itself, or the last APK named for it. */
    @Nullable
    static String forumFile(@NonNull String link, @NonNull String html, @NonNull String version) {
        if (FORUM_FILE.matcher("href=\"" + link + "\"").matches()) {
            return link;
        }
        Pattern named = Pattern.compile("(?i).*[^0-9.]" + Pattern.quote(version) + "\\.apk");
        String found = null;
        Matcher m = FORUM_FILE.matcher(html);
        while (m.find()) {
            if (named.matcher(m.group(2)).matches()) {
                found = m.group(1);
            }
        }
        return found;
    }

    /**
     * The topic's last lofi pages after the first one, newest first, at most {@link #FORUM_PAGES}: a release post is
     * recent, but talk after it may push it off the last page. Empty when the topic has one page.
     */
    @NonNull
    static List<String> forumLastPages(@NonNull String html) {
        int last = 0;
        int step = Integer.MAX_VALUE; // the posts per page: the smallest offset the pager links
        Matcher m = FORUM_PAGE.matcher(html);
        while (m.find()) {
            int offset = Integer.parseInt(m.group(1));
            last = Math.max(last, offset);
            if (offset > 0) {
                step = Math.min(step, offset);
            }
        }
        List<String> pages = new ArrayList<>();
        for (int offset = last; offset > 0 && pages.size() < FORUM_PAGES; offset -= step) {
            pages.add("https://4pda.to/forum/lofiversion/index.php?t1128418-" + offset + ".html");
        }
        return pages;
    }

    /** The file link of a 4pda download page (the one with the countdown), or null. */
    @Nullable
    static String forumAttach(@NonNull String html) {
        Matcher m = FORUM_ATTACH.matcher(html);
        return m.find() ? m.group(1).replace("&amp;", "&") : null;
    }
}
