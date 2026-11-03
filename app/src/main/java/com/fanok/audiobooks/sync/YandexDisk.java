package com.fanok.audiobooks.sync;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Base64;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.fanok.audiobooks.BuildConfig;
import com.fanok.audiobooks.R;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * The sync file in the app folder of Yandex Disk, through the REST API. Sign-in: the browser shows a confirmation code
 * that the user types into the app; PKCE stands in for a client secret, which an app cannot keep.
 */
final class YandexDisk implements Cloud {

    private static final String API = "https://cloud-api.yandex.net/v1/disk/resources";
    private static final String PATH = "app:/" + FILE;
    private static final MediaType OCTETS = MediaType.get("application/octet-stream");

    private final String auth;
    private final OkHttpClient http;

    YandexDisk(@NonNull String token, @NonNull OkHttpClient http) {
        this.auth = "OAuth " + token;
        this.http = http;
    }

    /**
     * Yandex Disk for the sync: offered when the build has a registered OAuth app (YANDEX_CLIENT_ID). The user
     * allows the access in the browser, whose page then shows a code to type in here.
     */
    static final class Service implements CloudService {

        /** The fragment result of the code the user typed in. */
        private static final String CODE_KEY = "yandex-code";

        @NonNull
        @Override
        public String id() {
            return "yandex";
        }

        @Override
        public int title() {
            return R.string.sync_yandex;
        }

        @Override
        public boolean available(@NonNull Context context) {
            return !BuildConfig.YANDEX_CLIENT_ID.isEmpty();
        }

        @NonNull
        @Override
        public Runnable signIn(@NonNull Fragment host, @NonNull Consumer<Callable<CloudAccount>> finish) {
            host.getChildFragmentManager().setFragmentResultListener(CODE_KEY, host, (key, result) -> {
                String code = result.getString(CodeDialog.CODE, ""), verifier = result.getString(CodeDialog.STATE, "");
                finish.accept(() -> new CloudAccount(null, token(CloudSync.http(), code, verifier)));
            });
            return () -> {
                String verifier = verifier();
                CodeDialog.ask(host, CODE_KEY, R.string.sync_yandex, R.string.sync_yandex_code, verifier);
                try {
                    host.startActivity(new Intent(Intent.ACTION_VIEW, authUrl(verifier)));
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(host.requireContext(), R.string.sync_no_browser, Toast.LENGTH_LONG).show();
                }
            };
        }

        @NonNull
        @Override
        public Cloud open(@NonNull Context app, @NonNull CloudAccount account, @NonNull OkHttpClient http) {
            return new YandexDisk(account.token != null ? account.token : "", http);
        }
    }

    /** A new PKCE code verifier, kept until the user enters the code. */
    @NonNull
    static String verifier() {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        return base64(random);
    }

    /** The page where the user picks an account, allows access and gets the code for {@link #token}. */
    @NonNull
    static Uri authUrl(@NonNull String verifier) {
        byte[] challenge;
        try {
            challenge = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return Uri.parse("https://oauth.yandex.ru/authorize").buildUpon()
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("client_id", BuildConfig.YANDEX_CLIENT_ID)
                .appendQueryParameter("code_challenge", base64(challenge))
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("force_confirm", "yes")
                .build();
    }

    /** Exchanges the code the user typed for an access token. */
    @NonNull
    static String token(@NonNull OkHttpClient http, @NonNull String code, @NonNull String verifier)
            throws IOException {
        Request request = new Request.Builder().url("https://oauth.yandex.ru/token").post(new FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code.trim())
                .add("client_id", BuildConfig.YANDEX_CLIENT_ID)
                .add("code_verifier", verifier)
                .build()).build();
        try (Response response = Cloud.send(http, request)) {
            if (!response.isSuccessful()) {
                throw failure("Yandex sign-in", response, "error_description", "error");
            }
            return field(json(response), "access_token");
        }
    }

    @Nullable
    @Override
    public Remote read() throws IOException {
        String tag = tag();
        String href = tag != null ? link(url("/download").build(), 404) : null;
        if (href == null) {
            return null;
        }
        // A signed storage URL: the token must not travel there.
        try (Response response = Cloud.send(http, new Request.Builder().url(href).build())) {
            if (response.code() == 404) {
                return null;
            }
            check(response);
            return new Remote(Cloud.bytes(response), tag);
        }
    }

    @Nullable
    @Override
    public String tag() throws IOException {
        try (Response response = call(new Request.Builder().url(url("").addQueryParameter("fields", "md5").build()))) {
            if (response.code() == 404) {
                return null;
            }
            check(response);
            return field(json(response), "md5");
        }
    }

    @Override
    public void write(@NonNull byte[] data) throws IOException {
        HttpUrl upload = url("/upload").addQueryParameter("overwrite", "true").build();
        String href = link(upload, 409);
        if (href == null) { // the app folder is not there yet (new account, or the user deleted it)
            HttpUrl folder = HttpUrl.get(API).newBuilder().addQueryParameter("path", "app:/").build();
            // best effort: if the folder could not be made, the next link request says why
            call(new Request.Builder().url(folder).put(RequestBody.create(new byte[0]))).close();
            href = link(upload, 0);
        }
        int code;
        try (Response response = Cloud.send(http, new Request.Builder().url(href).put(RequestBody.create(data, OCTETS))
                .build())) {
            check(response);
            code = response.code();
        }
        // 202: accepted, still being moved into the Disk. A sync that read the old file meanwhile would take this
        // device's state for one the cloud never saw: wait a while until the Disk holds it, else fail this sync
        // (its base is then not kept; the next one finds the file in place)
        String md5 = md5(data);
        for (int i = 0; code == 202 && !md5.equals(tag()); i++) {
            if (i == 10) {
                throw new IOException("Yandex Disk has not stored the file yet");
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted");
            }
        }
    }

    /** Lower-case hex, as the Disk reports it. */
    private static String md5(byte[] data) {
        try {
            return String.format(Locale.ROOT, "%032x", new BigInteger(1, MessageDigest.getInstance("MD5").digest(data)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private HttpUrl.Builder url(String endpoint) {
        return HttpUrl.get(API + endpoint).newBuilder().addQueryParameter("path", PATH);
    }

    private Response call(Request.Builder request) throws IOException {
        return Cloud.send(http, request.header("Authorization", auth).build());
    }

    /** The one-off storage URL that {@code url} hands out, or null when the API answers {@code absent}. */
    @Nullable
    private String link(HttpUrl url, int absent) throws IOException {
        try (Response response = call(new Request.Builder().url(url))) {
            if (response.code() == absent) {
                return null;
            }
            check(response);
            return field(json(response), "href");
        }
    }

    private static void check(Response response) throws IOException {
        if (response.isSuccessful()) {
            return;
        }
        if (response.code() == 401) {
            throw new AuthException("Yandex Disk: sign in again");
        }
        if (response.code() == 507) {
            throw new IOException("Yandex Disk is full");
        }
        throw failure("Yandex Disk", response, "message", "description");
    }

    /** "{@code what} HTTP code: text", the text from the first of two fields of the error JSON that is present. */
    private static IOException failure(String what, Response response, String first, String second)
            throws IOException {
        JsonObject json = json(response);
        String text = string(json, first);
        if (text == null) {
            text = string(json, second);
        }
        return new IOException(what + " HTTP " + response.code() + (text != null ? ": " + text : ""));
    }

    /** The body as a JSON object; empty when it is not one (an HTML error page of a proxy or the storage). */
    private static JsonObject json(Response response) throws IOException {
        try {
            JsonElement json = JsonParser.parseString(response.body().string());
            return json.isJsonObject() ? json.getAsJsonObject() : new JsonObject();
        } catch (JsonParseException e) {
            return new JsonObject();
        }
    }

    @Nullable
    private static String string(JsonObject json, String name) {
        JsonElement value = json.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static String field(JsonObject json, String name) throws IOException {
        String value = string(json, name);
        if (value == null || value.isEmpty()) {
            throw new IOException("Yandex Disk: no " + name + " in the answer");
        }
        return value;
    }

    private static String base64(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }
}
