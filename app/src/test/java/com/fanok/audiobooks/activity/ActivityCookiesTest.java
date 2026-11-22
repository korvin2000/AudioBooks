package com.fanok.audiobooks.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fanok.audiobooks.net.SiteHttp;
import com.fanok.audiobooks.search.Provider;
import com.fanok.audiobooks.source.SlushatKnigi;

import org.junit.Test;

public class ActivityCookiesTest {

    @Test
    public void sessionCookieIsMatchedByExactNameAndSplitOnTheFirstEquals() {
        assertEquals("abc", ActivityCookies.sessionCookie("x=1; PHPSESSID=abc; y=2"));
        assertEquals("a=b", ActivityCookies.sessionCookie("PHPSESSID=a=b"));
        assertEquals("", ActivityCookies.sessionCookie("OLD_PHPSESSID=abc; PHPSESSIDX=1"));
        assertEquals("", ActivityCookies.sessionCookie("PHPSESSID="));
        assertEquals("", ActivityCookies.sessionCookie("PHPSESSID"));
        assertEquals("", ActivityCookies.sessionCookie(null));
    }

    @Test
    public void thePassOfSlushatKnigisHumanCheckGoesToItsPagesOnly() {
        assertTrue(ActivityCookies.passes(Provider.SLUSHAT_KNIGI));
        SlushatKnigi.setCheck(ActivityCookies.cookie("PHPSESSID=1; bgate=x.y", SlushatKnigi.CHECK_COOKIE));
        try {
            assertEquals("x.y", SiteHttp.connect(SlushatKnigi.INDEX).request().cookie(SlushatKnigi.CHECK_COOKIE));
            assertFalse("its playlists are another host's", SiteHttp.connect("https://s1.redirectto.cc/list.json")
                    .request().hasCookie(SlushatKnigi.CHECK_COOKIE));
        } finally {
            SlushatKnigi.setCheck("");
        }
        assertFalse(SiteHttp.connect(SlushatKnigi.INDEX).request().hasCookie(SlushatKnigi.CHECK_COOKIE));
    }
}
