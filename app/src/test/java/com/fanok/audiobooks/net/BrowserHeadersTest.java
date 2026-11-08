package com.fanok.audiobooks.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fanok.audiobooks.Consts;
import org.junit.Test;

import java.util.Map;

/** The browser a request claims to be: every header that browser sends, and all of them about the same browser. */
public class BrowserHeadersTest {

    /** An Android WebView as the audioboo check screen reports it (ActivityCookies stores it with the clearance). */
    private static final String WEBVIEW = "Mozilla/5.0 (Linux; Android 14; wv) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Version/4.0 Chrome/131.0.6778.135 Mobile Safari/537.36";
    private static final String FIREFOX = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 "
            + "Firefox/132.0";
    /** The old Chrome of a site's own {@code mediaHeaders} (DownloadUtil.DESKTOP_UA): it sent hints in another form. */
    private static final String OLD_CHROME = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, "
            + "like Gecko) Chrome/89.0.4389.82 Safari/537.36";

    @Test
    public void theDefaultAgentIsAWholeDesktopChrome() {
        String major = String.valueOf(BrowserHeaders.chromeMajor(Consts.USER_AGENT));
        Map<String, String> headers = BrowserHeaders.page(Consts.USER_AGENT);
        assertEquals(Consts.USER_AGENT, headers.get("User-Agent"));
        assertTrue(headers.get("Accept").startsWith("text/html,application/xhtml+xml"));
        assertEquals("ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7", headers.get("Accept-Language"));
        assertEquals("\"Google Chrome\";v=\"" + major + "\", \"Chromium\";v=\"" + major
                + "\", \"Not=A?Brand\";v=\"24\"", headers.get("Sec-Ch-Ua"));
        assertEquals("?0", headers.get("Sec-Ch-Ua-Mobile"));
        assertEquals("\"Windows\"", headers.get("Sec-Ch-Ua-Platform"));
        // the app's referrer is a search engine, so the navigation comes from another site
        assertEquals("document", headers.get("Sec-Fetch-Dest"));
        assertEquals("navigate", headers.get("Sec-Fetch-Mode"));
        assertEquals("cross-site", headers.get("Sec-Fetch-Site"));
        assertEquals("?1", headers.get("Sec-Fetch-User"));
        assertEquals("1", headers.get("Upgrade-Insecure-Requests"));
    }

    @Test
    public void theHintsAreThoseOfTheAgentInUseNotOfTheDefault() {
        Map<String, String> headers = BrowserHeaders.page(WEBVIEW);
        assertEquals("?1", headers.get("Sec-Ch-Ua-Mobile"));
        assertEquals("\"Android\"", headers.get("Sec-Ch-Ua-Platform"));
        assertEquals("\"Chromium\";v=\"131\", \"Android WebView\";v=\"131\", \"Not-A.Brand\";v=\"99\"",
                headers.get("Sec-Ch-Ua"));
    }

    @Test
    public void firefoxSendsNoClientHints() {
        Map<String, String> headers = BrowserHeaders.page(FIREFOX);
        assertEquals(FIREFOX, headers.get("User-Agent"));
        assertNull(headers.get("Sec-Ch-Ua"));
        assertNull(headers.get("Sec-Ch-Ua-Mobile"));
        assertNull(headers.get("Sec-Ch-Ua-Platform"));
        assertEquals("ru-RU,ru;q=0.8,en-US;q=0.5,en;q=0.3", headers.get("Accept-Language"));
        assertTrue(headers.get("Accept").startsWith("text/html"));
        assertEquals("navigate", headers.get("Sec-Fetch-Mode"));
    }

    @Test
    public void aChromeThatNeverSentTheseHintsGetsNone() {
        assertNull(BrowserHeaders.resource(OLD_CHROME, false).get("Sec-Ch-Ua"));
        assertNull(BrowserHeaders.page(OLD_CHROME).get("Sec-Ch-Ua-Mobile"));
    }

    @Test
    public void anAgentThatIsNoBrowserStillGetsTheHeadersOfOne() {
        Map<String, String> headers = BrowserHeaders.page("Dalvik/2.1.0 (Linux; U; Android 14)");
        assertEquals("Dalvik/2.1.0 (Linux; U; Android 14)", headers.get("User-Agent"));
        assertNull(headers.get("Sec-Ch-Ua"));
        assertEquals("ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7", headers.get("Accept-Language"));
        assertEquals("navigate", headers.get("Sec-Fetch-Mode"));
    }

    /** A subresource names no User-Agent: the caller's may be the site's own (SourceParser.mediaHeaders). */
    @Test
    public void resourceHeadersLeaveTheUserAgentToTheCaller() {
        Map<String, String> image = BrowserHeaders.resource(Consts.USER_AGENT, true);
        assertNull(image.get("User-Agent"));
        assertTrue(image.get("Accept").startsWith("image/"));
        assertEquals("\"Windows\"", image.get("Sec-Ch-Ua-Platform"));
        assertEquals("*/*", BrowserHeaders.resource(Consts.USER_AGENT, false).get("Accept"));
    }
}
