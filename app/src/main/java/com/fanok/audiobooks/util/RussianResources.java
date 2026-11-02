package com.fanok.audiobooks.util;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;

import androidx.annotation.NonNull;

import java.util.Locale;

/**
 * The app's resources read as Russian on any device language. The app speaks Russian only, but {@code Resources}
 * picks a plural's form by the device's language: an English device read «2 сайтов» and «2 озвучек» where Russian
 * says «2 сайта», «2 озвучки». Use it for every {@code getQuantityString}; the strings themselves are the same.
 */
public final class RussianResources {

    private static final Locale RUSSIAN = new Locale("ru");
    /** The application's configuration {@link #russian} was made for: a new one (rotation, font scale) makes it anew. */
    private static Configuration madeFor;
    private static Resources russian;

    private RussianResources() {
    }

    /** {@code context}'s resources when the device speaks Russian, otherwise the same resources in Russian. */
    @NonNull
    public static synchronized Resources of(@NonNull Context context) {
        Resources res = context.getResources();
        if (RUSSIAN.getLanguage().equals(res.getConfiguration().getLocales().get(0).getLanguage())) {
            return res;
        }
        Context app = context.getApplicationContext(); // not the activity: the cache must not hold a screen
        Configuration config = app.getResources().getConfiguration();
        if (russian == null || !config.equals(madeFor)) {
            Configuration ru = new Configuration(config);
            ru.setLocale(RUSSIAN);
            russian = app.createConfigurationContext(ru).getResources();
            madeFor = new Configuration(config);
        }
        return russian;
    }
}
