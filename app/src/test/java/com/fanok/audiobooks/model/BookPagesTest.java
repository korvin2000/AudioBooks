package com.fanok.audiobooks.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fanok.audiobooks.net.SiteHttp;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** One opening of a book fetches its page once: the description and the chapter list share it. */
public class BookPagesTest {

    private static final String PAGE = "<html><body><h1>Книга</h1></body></html>";

    /** Counts its fetches; each URL of a test is new, so the shared cache of other tests does not interfere. */
    private static final class Counting implements BookPages.Fetch {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public Document get(String url) {
            calls.incrementAndGet();
            return Jsoup.parse(PAGE, url);
        }
    }

    @Test
    public void aSecondReaderWithinTheTtlGetsItsOwnCopyOfTheSamePage() throws IOException {
        Counting fetch = new Counting();
        String url = "https://example.org/book-ttl";
        Document first = BookPages.get(url, fetch, 1_000);
        Document second = BookPages.get(url, fetch, 1_000 + BookPages.TTL_MS - 1);
        assertEquals(1, fetch.calls.get());
        assertNotSame(first, second);
        first.select("h1").remove(); // a parser changing its copy changes nobody else's
        assertEquals("Книга", second.select("h1").text());
        BookPages.get(url, fetch, 1_000 + BookPages.TTL_MS);
        assertEquals("an old page is fetched again", 2, fetch.calls.get());
    }

    @Test
    public void forgetMakesTheNextReaderFetch() throws IOException {
        Counting fetch = new Counting();
        String url = "https://example.org/book-forget";
        BookPages.get(url, fetch, 5_000);
        BookPages.forget(url);
        BookPages.get(url, fetch, 5_001);
        assertEquals(2, fetch.calls.get());
    }

    @Test
    public void aFailureIsNotKept() throws IOException {
        String url = "https://example.org/book-fail";
        AtomicInteger calls = new AtomicInteger();
        BookPages.Fetch flaky = u -> {
            if (calls.incrementAndGet() == 1) {
                throw new IOException("refused");
            }
            return Jsoup.parse(PAGE, u);
        };
        try {
            BookPages.get(url, flaky, 7_000);
            fail();
        } catch (IOException expected) {
            assertEquals("refused", expected.getMessage());
        }
        assertEquals("Книга", BookPages.get(url, flaky, 7_001).select("h1").text());
        assertEquals(2, calls.get());
    }

    @Test
    public void readersAtTheSameTimeShareOneFetch() throws Exception {
        String url = "https://example.org/book-together";
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        BookPages.Fetch slow = u -> {
            calls.incrementAndGet();
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
            return Jsoup.parse(PAGE, u);
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Document> description = pool.submit(() -> BookPages.get(url, slow));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            Future<Document> chapters = pool.submit(() -> BookPages.get(url, slow));
            Thread.sleep(100); // the chapter list now waits for the description's fetch
            release.countDown();
            assertEquals("Книга", description.get(5, TimeUnit.SECONDS).select("h1").text());
            assertEquals("Книга", chapters.get(5, TimeUnit.SECONDS).select("h1").text());
            assertEquals(1, calls.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void aReaderWhoseSharedFetchFailedTriesItself() throws Exception {
        String url = "https://example.org/book-retry";
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        BookPages.Fetch firstFails = u -> {
            if (calls.incrementAndGet() == 1) {
                started.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
                throw new IOException("cancelled");
            }
            return Jsoup.parse(PAGE, u);
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Document> leader = pool.submit(() -> BookPages.get(url, firstFails));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            Future<Document> joined = pool.submit(() -> BookPages.get(url, firstFails));
            Thread.sleep(100);
            release.countDown();
            try {
                leader.get(5, TimeUnit.SECONDS);
                fail();
            } catch (java.util.concurrent.ExecutionException expected) {
                assertTrue(expected.getCause() instanceof IOException);
            }
            assertEquals("Книга", joined.get(5, TimeUnit.SECONDS).select("h1").text());
            assertEquals(2, calls.get());
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * The screen whose load fetches the shared page goes away mid-page (Rx disposes it: its thread is interrupted).
     * jsoup then ends the body quietly; that cut page, still with its title but without what comes later (akniga's
     * player key), must not reach the chapter list that waits for the same page: it fetches the page itself.
     */
    @Test
    public void aPageCutByAnInterruptIsNotShared() throws Exception {
        CountDownLatch halfSent = new CountDownLatch(1);
        CountDownLatch rest = new CountDownLatch(1);
        String head = "<html><head><title>Книга</title></head><body><h1>Книга</h1>" + pad(20_000);
        String middle = pad(20_000);
        String end = "<script>var LIVESTREET_SECURITY_KEY = 'k';</script><div id=end></div></body></html>";
        AtomicInteger requests = new AtomicInteger();
        try (ServerSocket server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
            Thread serving = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket client = server.accept()) {
                        readHead(client.getInputStream());
                        byte[][] parts = {head.getBytes(StandardCharsets.UTF_8), middle.getBytes(StandardCharsets.UTF_8),
                                end.getBytes(StandardCharsets.UTF_8)};
                        OutputStream out = client.getOutputStream();
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\nContent-Length: "
                                + (parts[0].length + parts[1].length + parts[2].length)
                                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                        out.write(parts[0]);
                        out.flush();
                        if (requests.incrementAndGet() == 1) { // the first reader gets the rest slowly
                            halfSent.countDown();
                            rest.await(30, TimeUnit.SECONDS);
                            out.write(parts[1]);
                            out.flush();
                            Thread.sleep(300);
                        } else {
                            out.write(parts[1]);
                        }
                        out.write(parts[2]);
                        out.flush();
                    } catch (IOException e) {
                        // the cut reader closed its end: serve the next one
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            });
            serving.start();
            String url = "http://127.0.0.1:" + server.getLocalPort() + "/book-cut";
            ExecutorService pool = Executors.newFixedThreadPool(2);
            // as on Android, where jsoup reads through HttpURLConnection (the JVM's HttpClient fails such a read)
            System.setProperty("jsoup.useHttpClient", "false");
            try {
                Future<Document> description = pool.submit(() -> BookPages.get(url, SiteHttp::get));
                assertTrue(halfSent.await(5, TimeUnit.SECONDS));
                Future<Document> chapters = pool.submit(() -> BookPages.get(url, SiteHttp::get));
                Thread.sleep(200); // the chapter list now waits for the description's fetch
                assertTrue(description.cancel(true)); // the description's screen is gone mid-page
                rest.countDown();
                Document page = chapters.get(10, TimeUnit.SECONDS);
                assertEquals("the whole page, not the cut one", 1, page.select("#end").size());
                assertEquals("the chapter list fetched the page itself", 2, requests.get());
            } finally {
                System.clearProperty("jsoup.useHttpClient");
                pool.shutdownNow();
            }
        }
    }

    private static String pad(int length) {
        StringBuilder text = new StringBuilder("<p>");
        while (text.length() < length) {
            text.append("Глава. ");
        }
        return text.append("</p>").toString();
    }

    private static void readHead(InputStream in) throws IOException {
        for (int last = 0, c; (c = in.read()) != -1; ) {
            last = (last << 8 | c) & 0xFFFFFFFF;
            if (last == 0x0D0A0D0A) {
                return;
            }
        }
    }
}
