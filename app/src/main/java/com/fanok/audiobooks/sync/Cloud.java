package com.fanok.audiobooks.sync;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.IOException;
import java.io.InterruptedIOException;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** The sync file in one cloud, read and written whole. Every call blocks: worker thread only. */
interface Cloud {

    String FILE = "audiobooks-sync.json.gz";

    /** Larger bodies are refused: the file is tens of kilobytes, a library of thousands of books a few megabytes. */
    long MAX_BYTES = 20L << 20;

    /** The file with its version tag, or null when there is none yet. */
    @Nullable
    Remote read() throws IOException;

    /** The file's version tag now (metadata only), or null when there is none. */
    @Nullable
    String tag() throws IOException;

    /** Creates or overwrites the file. */
    void write(@NonNull byte[] data) throws IOException;

    /**
     * {@code request} with up to two retries, 1 s and 2 s apart, while the server answers 423, 429 or 5xx (busy,
     * rate limit, maintenance). The caller closes the response.
     */
    @NonNull
    static Response send(@NonNull OkHttpClient http, @NonNull Request request) throws IOException {
        for (int attempt = 0; ; attempt++) {
            Response response = http.newCall(request).execute();
            int code = response.code();
            if (attempt == 2 || code != 423 && code != 429 && code < 500) {
                return response;
            }
            response.close();
            try {
                Thread.sleep(1000L << attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted");
            }
        }
    }

    /** The body of a successful response, at most {@link #MAX_BYTES}. */
    @NonNull
    static byte[] bytes(@NonNull Response response) throws IOException {
        ResponseBody body = response.body();
        if (body.contentLength() > MAX_BYTES) {
            throw new IOException("Sync file is too large: " + body.contentLength());
        }
        byte[] data = body.bytes();
        if (data.length > MAX_BYTES) {
            throw new IOException("Sync file is too large: " + data.length);
        }
        return data;
    }

    final class Remote {
        final byte[] data;
        /** Changes with every write of the file; compared only for equality. */
        final String tag;

        Remote(@NonNull byte[] data, @NonNull String tag) {
            this.data = data;
            this.tag = tag;
        }
    }

    /** The access is gone (revoked, expired, another account): the user has to sign in again. */
    final class AuthException extends IOException {
        AuthException(String message) {
            super(message);
        }
    }
}
