package com.fanok.audiobooks.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fanok.audiobooks.Consts;
import com.fanok.audiobooks.net.SiteHttp;
import org.jsoup.Connection;
import org.junit.After;
import org.junit.Test;

/** The session of the user's own pass of audioboo's browser check: sent to audioboo only, and only once accepted. */
public class AudiobooClearanceTest {

    private static final String AGENT = "Mozilla/5.0 (Linux; Android 14; wv) Chrome/131.0 Mobile Safari/537.36";

    @After
    public void forget() {
        Audioboo.setClearance("", "");
    }

    @Test
    public void withoutASessionTheRequestsStayAsTheyWere() {
        Audioboo.setClearance("", AGENT);
        assertTrue(new Audioboo().cookies().isEmpty());
        assertNull(new Audioboo().userAgent());
        Connection.Request request = SiteHttp.connect(Audioboo.SERVER + "/page/2/").request();
        assertEquals(Consts.USER_AGENT, request.header("User-Agent"));
        assertTrue(request.cookies().isEmpty());
    }

    @Test
    public void anAcceptedSessionGoesToTheSiteWithItsBrowsersUserAgent() {
        Audioboo.setClearance("abc", AGENT);
        Connection.Request own = SiteHttp.connect(Audioboo.SERVER + "/page/2/").request();
        assertEquals(AGENT, own.header("User-Agent"));
        assertEquals("abc", own.cookie(Audioboo.CLEARANCE_COOKIE));

        Connection.Request other = SiteHttp.connect("https://archive.org/download/x/1.mp3").request();
        assertEquals(Consts.USER_AGENT, other.header("User-Agent"));
        assertNull(other.cookie(Audioboo.CLEARANCE_COOKIE));
    }
}
