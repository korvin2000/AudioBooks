package com.fanok.audiobooks.sync;

import android.content.Context;
import android.text.TextUtils;
import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.fanok.audiobooks.BuildConfig;
import com.fanok.audiobooks.R;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.yandex.authsdk.YandexAuthException;
import com.yandex.authsdk.YandexAuthLoginOptions;
import com.yandex.authsdk.YandexAuthOptions;
import com.yandex.authsdk.YandexAuthResult;
import com.yandex.authsdk.YandexAuthSdk;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** The sync file in the app folder of Yandex Disk, through the REST API with the user's OAuth token. */
final class YandexDisk implements Cloud {

    private static final String API = "https://cloud-api.yandex.net/v1/disk/resources";
    private static final String PATH = "app:/" + FILE;
    private static final MediaType OCTETS = MediaType.get("application/octet-stream");

    private final Context app;
    private final String auth;
    private final OkHttpClient http;

    YandexDisk(@NonNull Context app, @NonNull String token, @NonNull OkHttpClient http) {
        this.app = app;
        this.auth = "OAuth " + token;
        this.http = http;
    }

    /**
     * Yandex Disk for the sync: offered when the build has a registered OAuth app (YANDEX_CLIENT_ID). Sign-in is
     * Yandex's own LoginSDK: the Yandex app if installed, else a browser tab, else a WebView; the user signs in to their
     * Yandex ID and comes back here with a token. The OAuth app's Android platform (package and signing key) is what
     * lets the browser hand the token back.
     */
    static final class Service implements CloudService {

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
            if (BuildConfig.YANDEX_CLIENT_ID.isEmpty()) {
                return () -> { }; // the SDK refuses to start without a client id; the option is not offered then
            }
            ActivityResultLauncher<YandexAuthLoginOptions> login = host.registerForActivityResult(
                    YandexAuthSdk.create(new YandexAuthOptions(host.requireContext())).getContract(), result -> {
                        if (result instanceof YandexAuthResult.Success) {
                            String token = ((YandexAuthResult.Success) result).getToken().getValue();
                            Context app = host.requireContext().getApplicationContext();
                            finish.accept(() -> account(app, token));
                        } else if (result instanceof YandexAuthResult.Failure) {
                            YandexAuthException e = ((YandexAuthResult.Failure) result).getException();
                            finish.accept(() -> {
                                throw new IOException("Yandex: " + TextUtils.join(", ", e.getErrors()), e);
                            });
                        } // cancelled: the user closed the sign-in
                    });
            return () -> login.launch(new YandexAuthLoginOptions());
        }

        /**
         * The account behind {@code token}, once the app folder answers: a Yandex ID without a Disk is turned away
         * here rather than failing every sync. Only the app folder: the Disk's own info (/v1/disk, with the login)
         * needs a permission the app does not ask for, and answers 403 to this token.
         */
        private static CloudAccount account(Context app, String token) throws IOException {
            new YandexDisk(app, token, CloudSync.http()).tag();
            return new CloudAccount(null, token);
        }

        @NonNull
        @Override
        public Cloud open(@NonNull Context app, @NonNull CloudAccount account, @NonNull OkHttpClient http) {
            return new YandexDisk(app, account.token != null ? account.token : "", http);
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

    private void check(Response response) throws IOException {
        if (response.isSuccessful()) {
            return;
        }
        if (response.code() == 401) {
            throw new AuthException("Yandex Disk: sign in again");
        }
        if (response.code() == 507) {
            throw new IOException("Yandex Disk is full");
        }
        JsonObject json = json(response);
        String error = string(json, "error");
        if (error != null && error.contains("UnsupportedUserAccountType")) { // a Yandex ID registered with other mail
            throw new IOException(app.getString(R.string.sync_yandex_no_disk));
        }
        String text = string(json, "message");
        if (text == null) {
            text = string(json, "description");
        }
        throw new IOException("Yandex Disk HTTP " + response.code() + (text != null ? ": " + text : ""));
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
}
