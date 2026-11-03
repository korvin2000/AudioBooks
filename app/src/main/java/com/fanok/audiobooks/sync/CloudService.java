package com.fanok.audiobooks.sync;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import okhttp3.OkHttpClient;

/**
 * A cloud the sync can keep its file in: it names itself, tells whether it works on this device, signs the user in
 * and opens the file of the account. {@link CloudSync#SERVICES} lists them; a new cloud is one implementation and
 * one entry there.
 */
interface CloudService {

    /** Stored as the connected cloud: never changes. */
    @NonNull
    String id();

    @StringRes
    int title();

    /** Whether it can be offered on this device (its client library, its registration in this build). */
    boolean available(@NonNull Context context);

    /**
     * Prepares the sign-in on {@code host} while it is being created (activity results are registered then). The
     * returned action asks the user; once they answer, {@code finish} gets the blocking last step that names the
     * account (null when they closed the sign-in; throws, with the reason, when it was refused), which the sync runs
     * on its own thread.
     */
    @NonNull
    Runnable signIn(@NonNull Fragment host, @NonNull Consumer<Callable<CloudAccount>> finish);

    /** The sync file of {@code account} here. */
    @NonNull
    Cloud open(@NonNull Context app, @NonNull CloudAccount account, @NonNull OkHttpClient http);
}
