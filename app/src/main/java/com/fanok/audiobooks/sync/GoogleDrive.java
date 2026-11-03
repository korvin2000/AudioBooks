package com.fanok.audiobooks.sync;

import android.accounts.Account;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.fanok.audiobooks.R;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.ClearTokenRequest;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * {@link Cloud} in the app's hidden Google Drive folder (appDataFolder), over the REST API with an access token from
 * Play services. No client id in code: Google matches the Android OAuth client by package name and signing key.
 */
final class GoogleDrive implements Cloud {

    static final String SCOPE = "https://www.googleapis.com/auth/drive.appdata";

    private static final String API = "https://www.googleapis.com/drive/v3/";
    private static final String UPLOAD = "https://www.googleapis.com/upload/drive/v3/files";
    private static final MediaType GZIP = MediaType.get("application/gzip");
    private static final MediaType JSON = MediaType.get("application/json; charset=UTF-8");

    private final Context app;
    private final String email;
    private final OkHttpClient http;
    @Nullable
    private String token;
    /** The sync file, kept after a write: a list right after a create may not show the new file yet. */
    @Nullable
    private String id;

    GoogleDrive(Context context, String email, OkHttpClient http) {
        this.app = context.getApplicationContext();
        this.email = email;
        this.http = http;
    }

    /** Google Drive for the sync: offered with Google Play services; the user signs in on Google's consent screen. */
    static final class Service implements CloudService {

        @NonNull
        @Override
        public String id() {
            return "google";
        }

        @Override
        public int title() {
            return R.string.sync_google;
        }

        @Override
        public boolean available(@NonNull Context context) {
            return GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
                    == ConnectionResult.SUCCESS;
        }

        @NonNull
        @Override
        public Runnable signIn(@NonNull Fragment host, @NonNull Consumer<Callable<CloudAccount>> finish) {
            ActivityResultLauncher<IntentSenderRequest> consent = host.registerForActivityResult(
                    new ActivityResultContracts.StartIntentSenderForResult(), result -> {
                        Intent data = result.getData();
                        if (result.getResultCode() == Activity.RESULT_OK && data != null) { // else cancelled
                            Context app = host.requireContext().getApplicationContext();
                            finish.accept(() -> account(
                                    Identity.getAuthorizationClient(app).getAuthorizationResultFromIntent(data)));
                        }
                    });
            return () -> authorize(host.requireContext(), null)
                    .addOnSuccessListener(result -> {
                        if (!result.hasResolution() || result.getPendingIntent() == null) {
                            finish.accept(() -> account(result)); // granted before
                        } else if (host.isAdded()) {
                            consent.launch(new IntentSenderRequest.Builder(result.getPendingIntent()).build());
                        }
                    })
                    .addOnFailureListener(e -> finish.accept(() -> {
                        throw e;
                    }));
        }

        @NonNull
        @Override
        public Cloud open(@NonNull Context app, @NonNull CloudAccount account, @NonNull OkHttpClient http) {
            return new GoogleDrive(app, account.name != null ? account.name : "", http);
        }

        /** The account of a grant: the grant does not name it, the Drive does. */
        private static CloudAccount account(AuthorizationResult result) throws IOException {
            String token = result.getAccessToken();
            if (token == null) {
                throw new IOException("No access granted");
            }
            return new CloudAccount(email(CloudSync.http(), token), null);
        }
    }

    /** Drive access for {@code email}, or for an account the user picks when it is null. */
    static Task<AuthorizationResult> authorize(Context context, @Nullable String email) {
        AuthorizationRequest.Builder request = AuthorizationRequest.builder()
                .setRequestedScopes(Collections.singletonList(new Scope(SCOPE)));
        if (email != null) {
            request.setAccount(new Account(email, "com.google"));
        }
        return Identity.getAuthorizationClient(context).authorize(request.build());
    }

    /** The account the token belongs to: the authorization result does not name it. */
    static String email(OkHttpClient http, String token) throws IOException {
        Request request = new Request.Builder().url(API + "about?fields=user(emailAddress)")
                .header("Authorization", "Bearer " + token).build();
        try (Response response = check(Cloud.send(http, request))) {
            return json(response).getAsJsonObject("user").get("emailAddress").getAsString();
        }
    }

    @Nullable
    @Override
    public Remote read() throws IOException {
        String tag = list();
        if (tag == null) {
            return null;
        }
        try (Response response = call(new Request.Builder().url(API + "files/" + id + "?alt=media"))) {
            return response.code() == 404 ? null : new Remote(Cloud.bytes(check(response)), tag);
        }
    }

    @Nullable
    @Override
    public String tag() throws IOException {
        return list();
    }

    @Override
    public void write(@NonNull byte[] data) throws IOException {
        if (id == null) {
            list();
        }
        if (id != null) {
            Request.Builder update = new Request.Builder().url(UPLOAD + "/" + id + "?uploadType=media&fields=id")
                    .patch(RequestBody.create(data, GZIP));
            try (Response response = call(update)) {
                if (response.code() != 404) {
                    check(response);
                    return;
                }
            }
            id = null; // deleted meanwhile (the user can delete the hidden data in Drive's settings)
        }
        MultipartBody body = new MultipartBody.Builder().setType(MediaType.get("multipart/related"))
                .addPart(RequestBody.create("{\"name\":\"" + FILE + "\",\"parents\":[\"appDataFolder\"]}", JSON))
                .addPart(RequestBody.create(data, GZIP)).build();
        try (Response response = check(call(new Request.Builder().url(UPLOAD + "?uploadType=multipart&fields=id")
                .post(body)))) {
            id = json(response).get("id").getAsString();
        }
    }

    /**
     * The tag of the sync file, or null when there is none. Always the oldest file: two devices creating it at once
     * converge on the same one, and the others are left alone.
     */
    @Nullable
    private String list() throws IOException {
        HttpUrl url = HttpUrl.get(API + "files").newBuilder()
                .addQueryParameter("spaces", "appDataFolder")
                .addQueryParameter("q", "name = '" + FILE + "'")
                .addQueryParameter("orderBy", "createdTime")
                .addQueryParameter("pageSize", "10")
                .addQueryParameter("fields", "files(id,version)").build();
        try (Response response = check(call(new Request.Builder().url(url)))) {
            JsonArray files = json(response).getAsJsonArray("files");
            if (files == null || files.size() == 0) {
                return null;
            }
            JsonObject file = files.get(0).getAsJsonObject();
            id = file.get("id").getAsString();
            return id + "/" + file.get("version").getAsString();
        }
    }

    /** {@code request} with the access token; a rejected token is cleared and the call made once more. */
    private Response call(Request.Builder request) throws IOException {
        String used = token();
        Response response = Cloud.send(http, request.header("Authorization", "Bearer " + used).build());
        if (response.code() != 401) {
            return response;
        }
        response.close();
        token = null;
        try {
            Tasks.await(Identity.getAuthorizationClient(app)
                    .clearToken(ClearTokenRequest.builder().setToken(used).build()), 10, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException ignored) {
            // authorize() may then return the same token: the retry gets 401 again and asks to reconnect
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted");
        }
        return Cloud.send(http, request.header("Authorization", "Bearer " + token()).build());
    }

    /** Silent authorization on the worker thread: consent or a removed account needs the user in Settings. */
    private String token() throws IOException {
        if (token != null) {
            return token;
        }
        AuthorizationResult result;
        try {
            result = Tasks.await(authorize(app, email), 30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ApiException) {
                int status = ((ApiException) cause).getStatusCode();
                if (status == CommonStatusCodes.SIGN_IN_REQUIRED || status == CommonStatusCodes.INVALID_ACCOUNT) {
                    throw new AuthException("Google account " + email + " is not available: connect again");
                }
            }
            throw new IOException("Google authorization failed: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted");
        } catch (TimeoutException e) {
            throw new IOException("Google authorization timed out", e);
        }
        if (result.hasResolution() || result.getAccessToken() == null) {
            throw new AuthException("Google Drive access is withdrawn: connect again");
        }
        return token = result.getAccessToken();
    }

    /** The response if it succeeded; otherwise closes it and throws with the code and the server's message. */
    private static Response check(Response response) throws IOException {
        if (response.isSuccessful()) {
            return response;
        }
        int code = response.code();
        String message = "";
        String reason = "";
        try {
            JsonObject error = JsonParser.parseString(response.peekBody(4096).string()).getAsJsonObject()
                    .getAsJsonObject("error");
            message = error.get("message").getAsString();
            reason = error.getAsJsonArray("errors").get(0).getAsJsonObject().get("reason").getAsString();
        } catch (IOException | RuntimeException ignored) {
            // not Drive's error JSON (a gateway page, an empty body): the code alone
        }
        response.close(); // callers wrap check(...) in try-with-resources, which never sees a thrown response
        if (code == 401) {
            throw new AuthException("Google Drive HTTP 401: connect again");
        }
        if (code == 403 && "storageQuotaExceeded".equals(reason)) {
            throw new IOException("Google Drive is full: free some space");
        }
        throw new IOException("Google Drive HTTP " + code + (message.isEmpty() ? "" : ": " + message));
    }

    private static JsonObject json(Response response) throws IOException {
        return JsonParser.parseString(response.body().string()).getAsJsonObject();
    }
}
