package org.jsoup.helper;

import com.fanok.audiobooks.Url;
import com.fanok.audiobooks.search.Provider;

import okhttp3.CookieJar;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.Buffer;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.util.concurrent.TimeUnit;

/**
 * jsoup's requests over HTTP/2, for the sites that ask for it ({@code SourceParser.http2()}). jsoup has an HTTP/2
 * transport of its own on a JVM only ({@code java.net.http}, which Android lacks); on Android it speaks HTTP/1.1
 * ({@code HttpURLConnection}), which no browser does to such a site. This one is the app's OkHttp client, put where
 * jsoup keeps its own ({@link RequestDispatch#clientConstructor}): redirects, cookies, the body and its limits stay
 * jsoup's, so {@code SiteHttp} sees no difference. It lives in jsoup's package because that contract is package-private
 * (jsoup 1.21.2: a jsoup update that changes it fails to compile here).
 */
public final class OkHttpExecutor extends RequestExecutor {

    private static volatile OkHttpClient client;
    private Response answer;
    /** jsoup's own connection, which answers for every other site as it does without this class. */
    private RequestExecutor platform;

    /** jsoup asks {@code shared} (its pool, proxy route and proxy login) for the pages of the sites wanting HTTP/2. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void install(OkHttpClient shared) {
        // one request, as jsoup made it: jsoup follows the redirects and carries the cookies itself
        client = shared.newBuilder().followRedirects(false).followSslRedirects(false)
                .cookieJar(CookieJar.NO_COOKIES).build();
        try {
            RequestDispatch.clientConstructor = (Constructor) OkHttpExecutor.class.getConstructor(
                    HttpConnection.Request.class, HttpConnection.Response.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    public OkHttpExecutor(HttpConnection.Request request, HttpConnection.Response previousResponse) {
        super(request, previousResponse);
    }

    @Override
    HttpConnection.Response execute() throws IOException {
        if (prevRes != null && prevRes.executor instanceof OkHttpExecutor) {
            prevRes.executor.safeClose(); // jsoup leaves the answer of a redirect it follows open
        }
        String url = req.url().toString();
        Provider provider = Provider.ofUrl(url);
        if (provider == null || !provider.parser.http2() || !Url.isHost(url, provider.server)) {
            platform = new UrlConnectionExecutor(req, prevRes);
            return platform.execute();
        }
        Headers.Builder headers = new Headers.Builder();
        CookieUtil.applyCookiesToRequest(req, headers::addUnsafeNonAscii);
        req.multiHeaders().forEach((name, values) -> values.forEach(value -> headers.addUnsafeNonAscii(name, value)));
        RequestBody body = null;
        if (req.method().hasBody()) {
            Buffer form = new Buffer();
            HttpConnection.Response.writePost(req, form.outputStream());
            body = RequestBody.create(form.readByteArray(), null); // its Content-Type is among the headers
        }
        try {
            answer = client.newBuilder()
                    .connectTimeout(req.timeout(), TimeUnit.MILLISECONDS)
                    .readTimeout(req.timeout(), TimeUnit.MILLISECONDS)
                    .build()
                    .newCall(new Request.Builder().url(req.url()).headers(headers.build())
                            .method(req.method().name(), body).build())
                    .execute();
            HttpConnection.Response res = new HttpConnection.Response(req);
            res.executor = this;
            res.method = req.method();
            res.url = req.url();
            res.statusCode = answer.code();
            res.statusMessage = answer.message();
            res.contentType = answer.header("Content-Type");
            res.contentLength = (int) Math.min(answer.body().contentLength(), Integer.MAX_VALUE);
            res.prepareResponse(answer.headers().toMultimap(), prevRes);
            return res;
        } catch (IOException | RuntimeException e) {
            safeClose();
            throw e;
        }
    }

    @Override
    InputStream responseBody() throws IOException {
        return platform != null ? platform.responseBody() : answer.body().byteStream();
    }

    @Override
    void safeClose() {
        if (platform != null) {
            platform.safeClose();
        } else if (answer != null) {
            answer.close();
        }
    }
}
