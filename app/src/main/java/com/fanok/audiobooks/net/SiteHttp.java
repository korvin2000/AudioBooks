package com.fanok.audiobooks.net;

import com.fanok.audiobooks.Consts;
import com.fanok.audiobooks.CookesExeption;
import com.fanok.audiobooks.Url;
import com.fanok.audiobooks.search.Provider;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import org.jsoup.Connection;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import okhttp3.HttpUrl;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URL;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one way to fetch a provider page: shared defaults (user agent, referrer, timeout, bounded body),
 * a per-host cookie jar, platform TLS validation, and classification of challenge/error pages.
 * Challenge pages (Cloudflare, DDoS-Guard) raise {@link CookesExeption}; 429 raises {@link RateLimitedException}, and a
 * host that said when to come back ({@code Retry-After}) is not asked again before then; other HTTP errors raise
 * {@link HttpStatusException}; parsers therefore never see an error page as content.
 */
public final class SiteHttp {

    public static final int TIMEOUT_MS = 10_000;
    static final int MAX_REDIRECTS = 5;
    public static final int MAX_BODY_BYTES = 4 * 1024 * 1024;
    private static final String REFERRER = "https://www.google.com/";
    /** What a Russian browser sends; a request without it looks like a bot to some anti-bot walls. */
    public static final String ACCEPT_LANGUAGE = "ru-RU,ru;q=0.8,en-US;q=0.5,en;q=0.3";
    private static final Map<String, Map<String, String>> JAR = new ConcurrentHashMap<>();
    /** Hosts that answered 429 with a {@code Retry-After}: until when (epoch millis) they are not asked. */
    private static final Map<String, Long> LIMITED_UNTIL = new ConcurrentHashMap<>();

    private SiteHttp() {
    }

    /** A pre-configured connection for callers that need extra cookies, headers or the raw response. */
    public static Connection connect(String url) {
        Connection connection = Jsoup.connect(url)
                .userAgent(Consts.USER_AGENT)
                .referrer(REFERRER)
                .header("Accept-Language", ACCEPT_LANGUAGE)
                .timeout(TIMEOUT_MS)
                .maxBodySize(MAX_BODY_BYTES)
                .ignoreHttpErrors(true);
        Map<String, String> cookies = JAR.get(Url.hostOf(url));
        if (cookies != null) {
            connection.cookies(cookies);
        }
        Provider provider = Provider.ofUrl(url);
        if (provider != null) {
            connection.timeout(provider.parser.timeoutMs());
            // the site's own cookies (a session the user pasted in settings), to its own host only, never a former one;
            // a newer one the site issued (in the jar) wins
            Map<String, String> own = Url.isHost(url, provider.server)
                    ? provider.parser.cookies() : Collections.<String, String>emptyMap();
            String agent = Url.isHost(url, provider.server) ? provider.parser.userAgent() : null;
            if (agent != null) {
                connection.userAgent(agent); // the browser's whose session the site's own cookies carry
            }
            for (Map.Entry<String, String> cookie : own.entrySet()) {
                if (cookies == null || !cookies.containsKey(cookie.getKey())) {
                    connection.cookie(cookie.getKey(), cookie.getValue());
                }
            }
        }
        return connection;
    }

    /** Drops the cookie {@code name} the host of {@code url} issued, so the site's own one (a new session) is sent. */
    public static void forget(String url, String name) {
        Map<String, String> cookies = JAR.get(Url.hostOf(url));
        if (cookies != null) {
            cookies.remove(name);
        }
    }

    /** GET {@code url} and parse it; the document's base URI is the final URL, so {@code absUrl} works. */
    public static Document get(String url) throws IOException {
        return parse(execute(connect(url)));
    }

    /** POST the query of {@code url} as a form to its path (DLE search and sort forms read either). */
    public static Document postForm(String url) throws IOException {
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null) {
            throw new IOException("bad url " + url);
        }
        Connection connection = connect(parsed.newBuilder().query(null).build().toString())
                .method(Connection.Method.POST);
        for (String name : parsed.queryParameterNames()) {
            connection.data(name, parsed.queryParameter(name));
        }
        return parse(execute(connection));
    }

    /**
     * Executes a connection built by {@link #connect} and remembers its cookies for the requested host. Jsoup
     * merges cookies across redirects, so after a cross-host redirect nothing is recorded (they cannot be attributed).
     */
    public static Connection.Response execute(Connection connection) throws IOException {
        String url = connection.request().url().toString();
        Provider provider = Provider.ofUrl(url);
        if (provider != null && provider.parser.postsPages() && Url.isHost(url, provider.server)) {
            return executePosted(connection, provider.server);
        }
        return executeOnce(connection);
    }

    /**
     * A site that {@code postsPages} (audioboo.org: its Cloudflare answers every GET with a challenge while POST passes),
     * where Jsoup would re-send a POST as GET when it follows a 301/302/303/308. Its pages are therefore POSTed
     * (DataLife Engine reads the query of a POST too), {@code /page/1/} is dropped up front because the site redirects
     * it, and redirects on {@code server}
     * (never to plain HTTP from HTTPS) are followed by hand with the same POST and form. Any other redirect is an
     * HTTP error.
     */
    static Connection.Response executePosted(Connection connection, String server) throws IOException {
        connection.method(Connection.Method.POST).followRedirects(false);
        connection.url(Url.withoutFirstPage(connection.request().url().toString()));
        for (int hop = 0; ; hop++) {
            Connection.Response response = executeOnce(connection);
            int status = response.statusCode();
            if (status < 300 || status > 399) {
                return response;
            }
            //the redirect's body is not needed, but its connection must be released
            response.bufferUp();
            URL next = redirectTarget(response.url(), response.header("Location"));
            if (next == null || hop >= MAX_REDIRECTS || !sameSite(response.url(), next, server)) {
                throw new HttpStatusException("Redirect not followed", status, response.url().toString());
            }
            connection.url(next);
        }
    }

    /** {@code location} resolved against {@code base}; a bare {@code ?query} keeps the path. Null when unusable. */
    static URL redirectTarget(URL base, String location) {
        if (location == null || location.trim().isEmpty()) {
            return null;
        }
        String target = location.trim();
        if (target.startsWith("?")) {
            target = base.getPath() + target;
        }
        try {
            return new URL(base, target);
        } catch (java.net.MalformedURLException e) {
            return null;
        }
    }

    /** {@code next} is on {@code server}, on the same port or the default one, and not a downgrade from HTTPS. */
    private static boolean sameSite(URL current, URL next, String server) {
        boolean https = "https".equalsIgnoreCase(next.getProtocol());
        if (!https && !next.getProtocol().equalsIgnoreCase(current.getProtocol())) {
            return false;
        }
        int port = next.getPort() < 0 ? next.getDefaultPort() : next.getPort();
        int currentPort = current.getPort() < 0 ? current.getDefaultPort() : current.getPort();
        return Url.isHost(next.toString(), server) && (port == currentPort || port == next.getDefaultPort());
    }

    private static Connection.Response executeOnce(Connection connection) throws IOException {
        String url = connection.request().url().toString();
        String requestedHost = Url.hostOf(url);
        int wait = limitedFor(requestedHost, System.currentTimeMillis());
        if (wait > 0) {
            throw new RateLimitedException(url, wait); // the site asked not to be asked before then
        }
        Connection.Response response = connection.execute();
        if (response.statusCode() == RateLimitedException.STATUS) {
            long answered = System.currentTimeMillis(); // the wait counts from the answer, not from the ask
            limit(requestedHost, Url.hostOf(response.url().toString()),
                    RateLimitedException.retryAfterSeconds(response.header("Retry-After"), answered), answered);
        }
        if (response.statusCode() == 403 || response.statusCode() == 429) {
            // the automatic proxy may be the one refused, by the host that answered (after redirects)
            AppProxy.reportSuspect(Url.hostOf(response.url().toString()));
        }
        Map<String, String> cookies = response.cookies();
        if (!cookies.isEmpty() && requestedHost.equals(Url.hostOf(response.url().toString()))) {
            JAR.computeIfAbsent(requestedHost, k -> new ConcurrentHashMap<>()).putAll(cookies);
        }
        return response;
    }

    /**
     * The body of a JSON or text answer (a playlist, an XHR), classified like a page: a challenge or HTTP >= 400 is an
     * exception, never content.
     */
    public static String body(Connection connection) throws IOException {
        Connection.Response response = execute(connection.ignoreContentType(true)).bufferUp();
        parse(response);
        return response.body();
    }

    /** {@link #json} of a GET of {@code url}. */
    public static JsonPage getJson(String url) throws IOException {
        return json(connect(url));
    }

    /**
     * A JSON answer of {@code connection} (built by {@link #connect}; GET or POST). The body decides, not the
     * Content-Type (sites serve JSON as {@code text/html}): an answer that is no JSON (an anti-bot page with status 200,
     * an error page) is a challenge, an HTTP error or an {@link IOException}, never "no data".
     */
    public static JsonPage json(Connection connection) throws IOException {
        Connection.Response response = execute(connection.ignoreContentType(true)).bufferUp();
        String url = response.url().toString();
        if (response.bodyAsBytes().length >= MAX_BODY_BYTES) {
            throw new IOException("Response larger than " + MAX_BODY_BYTES + " bytes: " + url);
        }
        requireWhole(url);
        String body = response.body();
        if (!looksLikeJson(body)) {
            parse(response); // a challenge or an HTTP error throws its own exception
            throw new IOException("not JSON: " + url);
        }
        throwForStatus(response);
        try {
            return new JsonPage(url, JsonParser.parseString(body));
        } catch (JsonParseException e) {
            throw new IOException("malformed JSON: " + url, e);
        }
    }

    private static final char BOM = 0xFEFF;

    /** Pure: the body starts (after a BOM and blanks) like a JSON object or list. */
    static boolean looksLikeJson(String body) {
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '{' || c == '[') {
                return true;
            }
            if (!Character.isWhitespace(c) && c != BOM) {
                return false;
            }
        }
        return false;
    }

    /**
     * The seconds {@code host} asked not to be asked any more ({@code Retry-After} of its last 429), at {@code nowMs};
     * 0 when it may be asked.
     */
    static int limitedFor(String host, long nowMs) {
        Long until = LIMITED_UNTIL.get(host);
        if (until == null) {
            return 0;
        }
        if (nowMs >= until) {
            LIMITED_UNTIL.remove(host, until);
            return 0;
        }
        return (int) ((until - nowMs + 999) / 1000);
    }

    /**
     * A 429 of {@code requestedHost} (answered by {@code answeredHost}) asking to wait {@code seconds}; 0: no wait. Of
     * answers that cross, the longest wait stands.
     */
    static void limit(String requestedHost, String answeredHost, int seconds, long nowMs) {
        if (seconds <= 0) {
            return;
        }
        long until = nowMs + seconds * 1000L;
        LIMITED_UNTIL.merge(requestedHost, until, Math::max);
        LIMITED_UNTIL.merge(answeredHost, until, Math::max);
    }

    /** Throws for an HTTP error: 429 → {@link RateLimitedException} with its wait, other ≥ 400 → {@link HttpStatusException}. */
    public static void throwForStatus(Connection.Response response) throws HttpStatusException {
        int code = response.statusCode();
        String url = response.url().toString();
        if (code == RateLimitedException.STATUS) {
            throw new RateLimitedException(url,
                    RateLimitedException.retryAfterSeconds(response.header("Retry-After"), System.currentTimeMillis()));
        }
        if (code >= 400) {
            throw new HttpStatusException("HTTP error fetching URL", code, url);
        }
    }

    /** Parses a response or throws: a challenge page → {@link CookesExeption}, HTTP ≥ 400 → {@link #throwForStatus}. */
    public static Document parse(Connection.Response response) throws IOException {
        return parse(response, MAX_BODY_BYTES);
    }

    /** As {@link #parse(Connection.Response)}, for a response fetched with {@code maxBodySize(maxBytes)}. */
    public static Document parse(Connection.Response response, int maxBytes) throws IOException {
        String url = response.url().toString();
        if (response.bodyAsBytes().length >= maxBytes) {
            // Jsoup silently truncates at maxBodySize; a cut page would parse as "no more items", so refuse it.
            throw new IOException("Response larger than " + maxBytes + " bytes: " + url);
        }
        if (response.statusCode() == RateLimitedException.STATUS) {
            throwForStatus(response); // a rate limit, whatever page it comes with: the same request goes through later
        }
        Document document = response.parse();
        requireWhole(url); // after the parse: jsoup reads even buffered bytes through its interruptible stream
        if (isChallenge(response.header("cf-mitigated"), document.title())) {
            throw new CookesExeption(url);
        }
        throwForStatus(response);
        return document;
    }

    /**
     * jsoup ends a body quietly when the reading thread is interrupted (a load disposed mid-page, e.g. its screen
     * closed): what it read is then a cut page — the title there, akniga's player key not — that must never be parsed
     * or shared ({@code BookPages}) as the whole one.
     */
    private static void requireWhole(String url) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("interrupted reading " + url);
        }
    }

    /**
     * Pure: Cloudflare marks every challenge with {@code cf-mitigated}; older walls are known by title (DDoS-Guard's
     * captcha says "Проверка безопасности" in Russia).
     */
    static boolean isChallenge(String cfMitigated, String title) {
        if (cfMitigated != null) {
            return true;
        }
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        return t.contains("just a moment") || t.contains("attention required")
                || t.contains("ddos-guard") || t.contains("checking your browser")
                || t.contains("security verification") || t.contains("проверка безопасности");
    }
}
