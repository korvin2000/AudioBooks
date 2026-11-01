package com.fanok.audiobooks.region;

import com.fanok.audiobooks.Consts;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.util.concurrent.TimeUnit;

import okhttp3.CookieJar;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * {@link RegionCheck.Net} on the device's own route, for the connection's real address: an OkHttp client of its own
 * with no proxy (the app's proxy is a ProxySelector, which an explicit proxy overrides), cookies or redirects, and
 * short timeouts; STUN over UDP, which no proxy takes. A system VPN still carries both.
 */
final class DirectNet implements RegionCheck.Net {

    private static final long BODY_BYTES = 8192;
    private static final int STUN_TIMEOUT_MS = 1000;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .callTimeout(3, TimeUnit.SECONDS)
            .build();

    @Override
    public String get(String url) throws IOException {
        Request request = new Request.Builder().url(url).header("User-Agent", Consts.USER_AGENT).build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }
            return response.peekBody(BODY_BYTES).string();
        }
    }

    @Override
    public InetAddress stun(String host, int port) throws IOException {
        return Stun.ask(host, port, STUN_TIMEOUT_MS);
    }

    /** Lets the client's threads and connections go: the check is over. */
    void close() {
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }
}
