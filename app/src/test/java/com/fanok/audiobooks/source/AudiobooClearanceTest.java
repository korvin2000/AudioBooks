package com.fanok.audiobooks.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fanok.audiobooks.Consts;
import com.fanok.audiobooks.CookesExeption;
import com.fanok.audiobooks.model.BookPages;
import com.fanok.audiobooks.net.RateLimitedException;
import com.fanok.audiobooks.net.SiteHttp;
import com.fanok.audiobooks.search.ProviderPage;
import org.jsoup.Connection;
import org.jsoup.HttpStatusException;
import org.jsoup.nodes.Document;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

/** The session of the user's own pass of audioboo's browser check: sent to audioboo only, and only once accepted. */
public class AudiobooClearanceTest {

    private static final String AGENT = "Mozilla/5.0 (Linux; Android 14; wv) Chrome/131.0 Mobile Safari/537.36";
    private static final String OLD_AGENT = "Mozilla/5.0 (Linux; Android 13; wv) Chrome/130.0 Mobile Safari/537.36";
    private static final String PAGE = Audioboo.SERVER + "/page/2/";

    /** The requests the fake site got, as {@link SiteHttp} builds them with the adapter's session. */
    private final List<Connection.Request> sent = new ArrayList<>();

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
        // the rest of that browser: a desktop Chrome's hints under a mobile Agent would not be its own
        assertEquals("?1", own.header("Sec-Ch-Ua-Mobile"));
        assertEquals("\"Android\"", own.header("Sec-Ch-Ua-Platform"));
        assertEquals("navigate", own.header("Sec-Fetch-Mode"));

        Connection.Request other = SiteHttp.connect("https://archive.org/download/x/1.mp3").request();
        assertEquals(Consts.USER_AGENT, other.header("User-Agent"));
        assertNull(other.cookie(Audioboo.CLEARANCE_COOKIE));
        assertEquals("?0", other.header("Sec-Ch-Ua-Mobile"));
    }

    @Test
    public void aChallengedClearanceIsForgottenAndTheRequestGoesOnceMoreAsBefore() throws IOException {
        Audioboo.setClearance("abc", AGENT);
        Audioboo.withClearance(PAGE, site(new CookesExeption(PAGE), null));
        assertEquals(2, sent.size());
        assertEquals(AGENT, sent.get(0).header("User-Agent"));
        assertEquals("abc", sent.get(0).cookie(Audioboo.CLEARANCE_COOKIE));
        // the app's own request, as without a session
        assertEquals(Consts.USER_AGENT, sent.get(1).header("User-Agent"));
        assertEquals("?0", sent.get(1).header("Sec-Ch-Ua-Mobile"));
        assertTrue(sent.get(1).cookies().isEmpty());
        assertEquals("", Audioboo.clearance());
    }

    @Test
    public void withoutAClearanceAChallengeIsNotRetried() throws IOException {
        try {
            Audioboo.withClearance(PAGE, site(new CookesExeption(PAGE), null));
            fail("a challenge without a clearance is the caller's");
        } catch (CookesExeption expected) {
            assertEquals(1, sent.size());
        }
    }

    @Test
    public void aSecondChallengeReachesTheCaller() throws IOException {
        Audioboo.setClearance("abc", AGENT);
        try {
            Audioboo.withClearance(PAGE, site(new CookesExeption(PAGE)));
            fail("the check is offered again");
        } catch (CookesExeption expected) {
            assertEquals(2, sent.size());
            assertEquals("", Audioboo.clearance());
        }
    }

    @Test
    public void aClearanceGotMeanwhileStays() throws IOException {
        Audioboo.setClearance("old", OLD_AGENT);
        final List<Connection.Request> during = new ArrayList<>();
        Audioboo.withClearance(PAGE, url -> {
            sent.add(SiteHttp.connect(url).request());
            if (sent.size() > 1) {
                return new Document(url);
            }
            Audioboo.saveClearance("new", AGENT); // the user passed the check while the old one was refused
            during.add(SiteHttp.connect(url).request());
            throw new CookesExeption(url);
        });
        assertEquals("new", Audioboo.clearance());
        // a request already out keeps its one session: agent and cookie of the same
        assertEquals(OLD_AGENT, during.get(0).header("User-Agent"));
        assertEquals("old", during.get(0).cookie(Audioboo.CLEARANCE_COOKIE));
        assertEquals(2, sent.size());
        assertEquals(AGENT, sent.get(1).header("User-Agent"));
        assertEquals("new", sent.get(1).cookie(Audioboo.CLEARANCE_COOKIE));
    }

    @Test
    public void otherFailuresKeepTheClearance() {
        IOException[] failures = {new HttpStatusException("HTTP error fetching URL", 503, PAGE),
                new HttpStatusException("HTTP error fetching URL", 403, PAGE), new RateLimitedException(PAGE, 30),
                new SocketTimeoutException("Read timed out"), new InterruptedIOException("interrupted")};
        for (IOException failure : failures) {
            Audioboo.setClearance("abc", AGENT);
            sent.clear();
            try {
                Audioboo.withClearance(PAGE, site(failure));
                fail(failure.toString());
            } catch (IOException e) {
                assertSame(failure, e);
            }
            assertEquals(failure.toString(), 1, sent.size());
            assertEquals(failure.toString(), "abc", Audioboo.clearance());
        }
    }

    /** A list offers the browser check for a detected challenge only, not for a plain 403/503 (an outage). */
    @Test
    public void onlyADetectedChallengeIsOfferedTheCheck() {
        ProviderPage<String> challenge = ProviderPage.failed(new CookesExeption(PAGE));
        assertEquals(ProviderPage.Status.BLOCKED, challenge.status);
        assertTrue(challenge.challenge);
        assertTrue("a failed step of a load keeps it", challenge.<Integer>asFailure().challenge);
        assertEquals("the check goes to the page refused", PAGE, challenge.<Integer>asFailure().challengedUrl);
        for (int code : new int[]{403, 503}) {
            ProviderPage<String> plain = ProviderPage.failed(new HttpStatusException("HTTP error fetching URL", code,
                    PAGE));
            assertEquals("the route offer or the toast, as before", ProviderPage.Status.BLOCKED, plain.status);
            assertFalse(plain.challenge);
        }
        assertFalse(ProviderPage.failed(new SocketTimeoutException()).challenge);
    }

    /**
     * The site answering the n-th request with {@code answers[n]}, the last one again after them: a failure is thrown,
     * null is a page. Each request is recorded in {@link #sent}.
     */
    private BookPages.Fetch site(final Exception... answers) {
        return url -> {
            sent.add(SiteHttp.connect(url).request());
            Exception answer = answers[Math.min(sent.size(), answers.length) - 1];
            if (answer instanceof IOException) {
                throw (IOException) answer;
            }
            if (answer != null) {
                throw (RuntimeException) answer;
            }
            return new Document(url);
        };
    }
}
