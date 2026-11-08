package com.fanok.audiobooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fanok.audiobooks.net.BrowserHeaders;

import okhttp3.OkHttpClient;
import okhttp3.Request;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * The shared client's default headers ({@link App#defaultHeaders()}) on both routes the app runs: the device's own one
 * and a proxy. What the headers are is {@link com.fanok.audiobooks.net.BrowserHeadersTest}'s; here it is that they are
 * the same either way, that the client is made for both the same way ({@code AppProxy.mediaClient}/{@code
 * updateRoutes}/the download client use {@code newBuilder()}), and that a route's own headers are none of their
 * business (a proxy login is the network interceptor's, and never this one's).
 */
public class AppHeadersTest {

    @Test
    public void aDirectRequestCarriesTheBrowsersHeaders() throws Exception {
        try (OneRequest server = new OneRequest()) {
            try (okhttp3.Response response = client(Proxy.NO_PROXY).newCall(server.get("/book.html")).execute()) {
                assertEquals(200, response.code());
            }
            Headers sent = server.head();
            assertEquals(Consts.USER_AGENT, sent.get("User-Agent"));
            assertEquals(BrowserHeaders.CHROME_LANGUAGE, sent.get("Accept-Language"));
            assertEquals("*/*", sent.get("Accept"));
            assertEquals("?0", sent.get("Sec-Ch-Ua-Mobile"));
            assertEquals("\"Windows\"", sent.get("Sec-Ch-Ua-Platform"));
            assertNotNull(sent.get("Sec-Ch-Ua"));
            // a route header: the defaults neither add one nor hide one the caller set
            assertNull(sent.get("Proxy-Authorization"));
        }
    }

    /** The same through an HTTP proxy — the request reaches the proxy, headers and all, not the site. */
    @Test
    public void aProxiedRequestCarriesTheSameHeaders() throws Exception {
        try (OneRequest proxy = new OneRequest()) {
            Proxy route = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(
                    InetAddress.getLoopbackAddress(), proxy.port()));
            Request request = new Request.Builder().url("http://site.example/book.html")
                    .header("Proxy-Authorization", "Basic dXNlcjpwYXNz").build();
            try (okhttp3.Response response = client(route).newCall(request).execute()) {
                assertEquals(200, response.code());
            }
            Headers sent = proxy.head();
            // the proxy got the request itself (an absolute URI), so these are the headers the site would see
            assertEquals("http://site.example/book.html", sent.get(":request-target"));
            assertEquals(Consts.USER_AGENT, sent.get("User-Agent"));
            assertEquals(BrowserHeaders.CHROME_LANGUAGE, sent.get("Accept-Language"));
            assertEquals("?0", sent.get("Sec-Ch-Ua-Mobile"));
            assertNotNull(sent.get("Sec-Ch-Ua"));
            // the caller's proxy login is left exactly as the caller set it (AppProxy.keepProxyLoginToProxy decides
            // what leaves the app; this interceptor only fills in browser headers)
            assertEquals("Basic dXNlcjpwYXNz", sent.get("Proxy-Authorization"));
        }
    }

    /** A client made from the shared one for another route ({@code newBuilder()}) keeps the same defaults. */
    @Test
    public void aRouteBuiltFromTheSharedClientKeepsTheDefaults() throws Exception {
        try (OneRequest server = new OneRequest()) {
            OkHttpClient shared = new OkHttpClient.Builder().addInterceptor(App.defaultHeaders()).build();
            OkHttpClient other = shared.newBuilder().proxy(Proxy.NO_PROXY).build();
            try (okhttp3.Response response = other.newCall(server.get("/x")).execute()) {
                assertEquals(200, response.code());
            }
            assertEquals(Consts.USER_AGENT, server.head().get("User-Agent"));
        }
    }

    /** A header the caller set is the caller's: it wins over the default, and the hints follow that agent. */
    @Test
    public void theCallersHeadersWinAndTheHintsFollowThem() throws Exception {
        try (OneRequest server = new OneRequest()) {
            String webView = "Mozilla/5.0 (Linux; Android 14; wv) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Version/4.0 Chrome/131.0.6778.135 Mobile Safari/537.36";
            Request request = server.get("/api/books").newBuilder()
                    .header("User-Agent", webView)          // a site's own agent, e.g. the forum route's WebView
                    .header("Accept", "application/json")   // the caller's own Accept
                    .header("Referer", "https://example.org/")
                    .build();
            try (okhttp3.Response response = client(Proxy.NO_PROXY).newCall(request).execute()) {
                assertEquals(200, response.code());
            }
            Headers sent = server.head();
            assertEquals(webView, sent.get("User-Agent"));
            assertEquals("application/json", sent.get("Accept"));
            assertEquals("https://example.org/", sent.get("Referer"));
            assertEquals("?1", sent.get("Sec-Ch-Ua-Mobile"));
            assertEquals("\"Android\"", sent.get("Sec-Ch-Ua-Platform"));
        }
    }

    /** An image request is asked as a browser asks for an image (the audioboo guard is a separate interceptor). */
    @Test
    public void anImageRequestAsksForAnImage() throws Exception {
        try (OneRequest server = new OneRequest()) {
            try (okhttp3.Response response = client(Proxy.NO_PROXY).newCall(server.get("/covers/1.jpg")).execute()) {
                assertEquals(200, response.code());
            }
            assertTrue(server.head().get("Accept").startsWith("image/"));
        }
    }

    private static OkHttpClient client(Proxy route) {
        return new OkHttpClient.Builder().proxy(route).addInterceptor(App.defaultHeaders()).build();
    }

    /** A server that accepts one request, answers it, and keeps the head that arrived. Works as a proxy too. */
    private static final class OneRequest implements Closeable {
        private static final String CRLF = "\r\n";

        private final ServerSocket socket;
        private final Thread thread;
        private final CopyOnWriteArrayList<String> heads = new CopyOnWriteArrayList<>();

        OneRequest() throws IOException {
            socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            thread = new Thread(this::serve, "one-request");
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return socket.getLocalPort();
        }

        /** A GET of {@code path} on this server. */
        Request get(String path) {
            return new Request.Builder().url("http://127.0.0.1:" + port() + path).build();
        }

        /** The head of the request that arrived (it is awaited: the answer went out after it was read). */
        Headers head() throws InterruptedException {
            thread.join(TimeUnit.SECONDS.toMillis(5));
            return new Headers(heads.isEmpty() ? "" : heads.get(0));
        }

        private void serve() {
            try (Socket client = socket.accept()) {
                heads.add(readHead(client.getInputStream()));
                byte[] out = "ok".getBytes(StandardCharsets.UTF_8);
                String response = "HTTP/1.1 200 OK" + CRLF + "Content-Type: text/plain" + CRLF
                        + "Content-Length: " + out.length + CRLF + "Connection: close" + CRLF + CRLF;
                OutputStream os = client.getOutputStream();
                os.write(response.getBytes(StandardCharsets.ISO_8859_1));
                os.write(out);
                os.flush();
            } catch (IOException ignored) {
                // the client went away: nothing more to read, the test asserts on what did arrive
            }
        }

        private static String readHead(InputStream in) throws IOException {
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            String end = CRLF + CRLF;
            int matched = 0;
            for (int b; matched < end.length() && (b = in.read()) >= 0; ) {
                head.write(b);
                matched = b == end.charAt(matched) ? matched + 1 : (b == '\r' ? 1 : 0);
            }
            return new String(head.toByteArray(), StandardCharsets.ISO_8859_1);
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    /** The request line and headers of one request; header names are case-insensitive, as on the wire. */
    private static final class Headers {
        private final String head;

        Headers(String head) {
            this.head = head;
        }

        /** The value of {@code name}; null when the request carried none. {@code :request-target} is its target. */
        String get(String name) {
            String[] lines = head.split("\r\n");
            if (":request-target".equals(name)) {
                String[] requestLine = lines.length == 0 ? new String[0] : lines[0].split(" ");
                return requestLine.length > 1 ? requestLine[1] : null;
            }
            for (String line : lines) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().toLowerCase(Locale.ROOT)
                        .equals(name.toLowerCase(Locale.ROOT))) {
                    return line.substring(colon + 1).trim();
                }
            }
            return null;
        }
    }
}
