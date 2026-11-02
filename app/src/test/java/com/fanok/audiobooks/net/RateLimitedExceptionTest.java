package com.fanok.audiobooks.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fanok.audiobooks.search.ProviderPage;
import org.jsoup.HttpStatusException;
import org.junit.Test;

/** HTTP 429: how long the site asks to wait, how a page reports it, and that the host is left alone meanwhile. */
public class RateLimitedExceptionTest {

    /** Wed, 21 Oct 2015 07:28:00 GMT. */
    private static final long AT = 1445412480000L;

    @Test
    public void retryAfterIsSecondsOrAnHttpDate() {
        assertEquals(120, RateLimitedException.retryAfterSeconds("120", AT));
        assertEquals(5, RateLimitedException.retryAfterSeconds(" 5 ", AT));
        assertEquals(30, RateLimitedException.retryAfterSeconds("Wed, 21 Oct 2015 07:28:30 GMT", AT));
        assertEquals("a moment in the past", 0, RateLimitedException.retryAfterSeconds("Wed, 21 Oct 2015 07:00:00 GMT", AT));
        assertEquals(0, RateLimitedException.retryAfterSeconds("soon", AT));
        assertEquals(0, RateLimitedException.retryAfterSeconds(null, AT));
        assertEquals(0, RateLimitedException.retryAfterSeconds("-4", AT));
        assertEquals("a day is cut to an hour", RateLimitedException.MAX_RETRY_AFTER_SECONDS,
                RateLimitedException.retryAfterSeconds("86400", AT));
    }

    @Test
    public void aPageOf429IsRateLimitedWithItsWaitNotBlocked() {
        ProviderPage<String> limited = ProviderPage.failed(new RateLimitedException("https://aknigi24.com/search", 7));
        assertEquals(ProviderPage.Status.RATE_LIMITED, limited.status);
        assertEquals(7, limited.retryAfterSeconds);
        assertTrue(limited.isFailure());
        ProviderPage<String> plain = ProviderPage.failed(new HttpStatusException("x", 429, "https://a.org"));
        assertEquals(ProviderPage.Status.RATE_LIMITED, plain.status);
        assertEquals(0, plain.retryAfterSeconds);
        assertEquals(ProviderPage.Status.BLOCKED,
                ProviderPage.failed(new HttpStatusException("x", 403, "https://a.org")).status);
        assertEquals(ProviderPage.Status.BLOCKED,
                ProviderPage.failed(new HttpStatusException("x", 503, "https://a.org")).status);
    }

    @Test
    public void aLimitedHostIsLeftAloneUntilItsWaitIsOver() {
        SiteHttp.limit("mirror.example", "site.example", 5, AT);
        assertEquals(4, SiteHttp.limitedFor("mirror.example", AT + 1_000));
        assertEquals("the host that answered too", 1, SiteHttp.limitedFor("site.example", AT + 4_500));
        assertEquals(0, SiteHttp.limitedFor("site.example", AT + 5_000));
        assertEquals("forgotten once over", 0, SiteHttp.limitedFor("site.example", AT));
        SiteHttp.limit("other.example", "other.example", 0, AT);
        assertEquals("no wait said, no wait kept", 0, SiteHttp.limitedFor("other.example", AT));
        assertEquals(0, SiteHttp.limitedFor("mirror.example", AT + 9_000));
        SiteHttp.limit("busy.example", "busy.example", 60, AT);
        SiteHttp.limit("busy.example", "busy.example", 5, AT);
        assertEquals("of answers that cross, the longest wait stands", 50, SiteHttp.limitedFor("busy.example", AT + 10_000));
    }
}
