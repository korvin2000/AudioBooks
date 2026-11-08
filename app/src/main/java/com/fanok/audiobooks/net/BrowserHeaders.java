package com.fanok.audiobooks.net;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a request says about the browser it comes from: the {@code User-Agent} and the headers a real browser always
 * sends with it.
 *
 * <p>An anti-bot wall (Cloudflare in front of audioboo.org) may refuse a request that claims to be a desktop Chrome in
 * {@code User-Agent} but carries none of the headers that same Chrome sends on <em>every</em> request: no
 * {@code Accept}, no {@code Accept-Language}, no client hints ({@code Sec-Ch-Ua*}), no {@code Sec-Fetch-*}. A browser
 * cannot be made to omit them, so their absence is a fingerprint of a bot. This class derives the whole set from the
 * User-Agent in use, so the two always agree: a desktop Chrome gets desktop hints, the Android WebView whose session
 * the user passed a site's check in gets mobile ones, Firefox (which sends no client hints at all) gets none.
 *
 * <p>The headers are built, never fetched: {@link SiteHttp} puts the {@link #page} ones on site pages and {@code App}
 * the {@link #resource} ones on the shared OkHttp client. The defaults are the desktop Chrome of
 * {@link com.fanok.audiobooks.Consts#USER_AGENT}.
 */
public final class BrowserHeaders {

    /** {@code Accept-Language} of a Russian Chrome (Firefox sends a slightly different string). */
    public static final String CHROME_LANGUAGE = "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7";
    /** {@code Accept-Language} of a Russian Firefox. */
    public static final String FIREFOX_LANGUAGE = "ru-RU,ru;q=0.8,en-US;q=0.5,en;q=0.3";

    /** The {@code Accept} a Chrome sends for a page the user opens (a navigation). */
    static final String HTML_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,"
            + "image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7";
    /** The same for Firefox. */
    static final String FIREFOX_HTML_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,"
            + "image/webp,*/*;q=0.8";
    /** The {@code Accept} of an image request. */
    static final String IMAGE_ACCEPT = "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8";
    /** The {@code Accept} of a subresource that is not an image (an audio stream, an API answer). */
    static final String ANY_ACCEPT = "*/*";

    /**
     * Client hints ({@code Sec-Ch-Ua*}) exist since Chrome 89, but their brand list took its current form only later;
     * an older Chrome-Agent therefore gets none rather than a list no Chrome of that version sent.
     */
    static final int CLIENT_HINTS_SINCE = 100;

    private static final Pattern CHROME = Pattern.compile("(?:Chrome|Chromium)/(\\d+)");
    private static final Pattern FIREFOX = Pattern.compile("Firefox/(\\d+)");

    private BrowserHeaders() {
    }

    /**
     * The headers a browser with {@code userAgent} sends when the user opens a page, in the order Chrome sends them:
     * the {@code User-Agent}, {@code Accept}, {@code Accept-Language}, the client hints, and the {@code Sec-Fetch-*}
     * headers of a navigation that came from another site (the app's referrer is a search engine).
     */
    public static Map<String, String> page(String userAgent) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", userAgent);
        headers.put("Accept", isFirefox(userAgent) ? FIREFOX_HTML_ACCEPT : HTML_ACCEPT);
        headers.put("Accept-Language", language(userAgent));
        hints(headers, userAgent);
        headers.put("Sec-Fetch-Dest", "document");
        headers.put("Sec-Fetch-Mode", "navigate");
        headers.put("Sec-Fetch-Site", "cross-site");
        headers.put("Sec-Fetch-User", "?1");
        headers.put("Upgrade-Insecure-Requests", "1");
        return headers;
    }

    /**
     * The headers a browser with {@code userAgent} sends for a page's subresource (a cover, an audio stream, an API
     * answer): its {@code Accept}, {@code Accept-Language} and the same client hints. The caller sets the
     * {@code User-Agent} itself, as it may be a site's own ({@code SourceParser.mediaHeaders}).
     */
    public static Map<String, String> resource(String userAgent, boolean image) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", image ? IMAGE_ACCEPT : ANY_ACCEPT);
        headers.put("Accept-Language", language(userAgent));
        hints(headers, userAgent);
        return headers;
    }

    /** Headers a browser sends with a navigation only ({@link #page}), never with a script's request. */
    static final String[] NAVIGATION_ONLY = {"Sec-Fetch-User", "Upgrade-Insecure-Requests"};

    /**
     * The fetch metadata of a script's request (an XHR) from a page: not a navigation. A site's endpoint may refuse a
     * "navigation" to it ({@code audioknigi.pro}'s playlist answers 403 {@code same_origin_required}).
     */
    public static Map<String, String> xhr(boolean sameOrigin) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Sec-Fetch-Dest", "empty");
        headers.put("Sec-Fetch-Mode", "cors");
        headers.put("Sec-Fetch-Site", sameOrigin ? "same-origin" : "cross-site");
        return headers;
    }

    /** True for the {@code Accept} {@link #page} sends: a navigation's, which an XHR replaces. */
    static boolean isPageAccept(String accept) {
        return HTML_ACCEPT.equals(accept) || FIREFOX_HTML_ACCEPT.equals(accept);
    }

    /** The {@code Accept-Language} of the browser {@code userAgent} (the Firefox one when that is a Firefox). */
    public static String language(String userAgent) {
        return isFirefox(userAgent) ? FIREFOX_LANGUAGE : CHROME_LANGUAGE;
    }

    /** True when {@code userAgent} is a Firefox, which sends no client hints at all. */
    static boolean isFirefox(String userAgent) {
        return userAgent != null && FIREFOX.matcher(userAgent).find();
    }

    /** The major version of the Chrome (Chromium, WebView) in {@code userAgent}; 0 when there is none. */
    static int chromeMajor(String userAgent) {
        if (userAgent == null) {
            return 0;
        }
        Matcher matcher = CHROME.matcher(userAgent);
        if (!matcher.find()) {
            return 0;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** True for a phone or tablet browser: Chrome and WebView on Android, Safari on iOS. */
    static boolean isMobile(String userAgent) {
        if (userAgent == null) {
            return false;
        }
        return userAgent.contains("Android") || userAgent.contains("iPhone") || userAgent.contains("iPad")
                || userAgent.contains("Mobile");
    }

    /** The {@code Sec-Ch-Ua-Platform} value of {@code userAgent}; null for a system Chrome has no name for. */
    static String platform(String userAgent) {
        if (userAgent == null) {
            return null;
        }
        if (userAgent.contains("Android")) {
            return "Android";
        }
        if (userAgent.contains("iPhone") || userAgent.contains("iPad")) {
            return "iOS";
        }
        if (userAgent.contains("Windows NT")) {
            return "Windows";
        }
        if (userAgent.contains("Macintosh")) {
            return "macOS";
        }
        if (userAgent.contains("Linux") || userAgent.contains("X11")) {
            return "Linux";
        }
        return null;
    }

    /** The three low-entropy client hints Chrome sends with every request; none for Firefox and old Chrome. */
    private static void hints(Map<String, String> headers, String userAgent) {
        if (isFirefox(userAgent)) {
            return;
        }
        int major = chromeMajor(userAgent);
        if (major < CLIENT_HINTS_SINCE) {
            return;
        }
        String system = platform(userAgent);
        if (system == null) {
            return;
        }
        headers.put("Sec-Ch-Ua", brands(userAgent, major));
        headers.put("Sec-Ch-Ua-Mobile", isMobile(userAgent) ? "?1" : "?0");
        headers.put("Sec-Ch-Ua-Platform", "\"" + system + "\"");
    }

    /**
     * The {@code Sec-Ch-Ua} brand list of Chrome {@code major}: the browser's own name first, its Chromium base, and
     * the GREASE brand browsers add so that sites cannot depend on the exact list. A WebView names itself.
     */
    static String brands(String userAgent, int major) {
        String version = "v=\"" + major + "\"";
        String webView = webViewBrand(userAgent);
        if (webView != null) {
            return "\"Chromium\";" + version + ", \"" + webView + "\";" + version + ", \"Not-A.Brand\";v=\"99\"";
        }
        return "\"Google Chrome\";" + version + ", \"Chromium\";" + version + ", \"Not=A?Brand\";v=\"24\"";
    }

    /** {@code Android WebView} for a WebView Agent ({@code ; wv}), null for a browser window. */
    private static String webViewBrand(String userAgent) {
        String lower = userAgent.toLowerCase(Locale.ROOT);
        boolean webView = lower.contains("; wv") || lower.contains(" wv)") || lower.contains("version/4.0");
        return webView ? "Android WebView" : null;
    }
}
