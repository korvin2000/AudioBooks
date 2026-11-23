package com.fanok.audiobooks.source;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.Nullable;

import com.fanok.audiobooks.App;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A site's own scripts run off screen, to ask them what only they know (akniga's player: the passphrase of the day).
 * A blank page at the site's address in a WebView that is never shown, loads nothing from the network and goes
 * nowhere: the caller hands over the scripts, which run one after another as the page would run them (one that fails
 * does not stop the next). There is no bridge into the app: all that comes back is the value of one expression.
 */
final class PageScripts {

    private static final String TAG = "PageScripts";
    private static final long TIMEOUT_S = 20;

    private PageScripts() {
    }

    /** Whether this thread may ask: not the main one, which the wait would block, and not a unit test. */
    static boolean canAsk() {
        return App.getInstance() != null && Looper.myLooper() != Looper.getMainLooper();
    }

    /**
     * The value of {@code question} after {@code scripts} ran in a blank page at {@code pageUrl}, as
     * {@code evaluateJavascript} hands it over (JSON; "null" when the question threw). Null when there was no asking
     * ({@link #canAsk}), no WebView on the device or no answer in time.
     */
    @Nullable
    static String ask(String pageUrl, List<String> scripts, String question) {
        App app = App.getInstance();
        if (!canAsk()) {
            return null;
        }
        CountDownLatch done = new CountDownLatch(1);
        String[] answer = {null};
        WebView[] view = {null};
        Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> {
            try {
                view[0] = open(app, scripts, question, json -> {
                    answer[0] = json;
                    done.countDown();
                });
                view[0].loadDataWithBaseURL(pageUrl, "<!doctype html><html><head></head><body></body></html>",
                        "text/html", "utf-8", null);
            } catch (RuntimeException e) { // no WebView on the device, or it is being updated
                Log.w(TAG, "no WebView to ask", e);
                done.countDown();
            }
        });
        try {
            if (!done.await(TIMEOUT_S, TimeUnit.SECONDS)) {
                Log.w(TAG, "the scripts of " + pageUrl + " did not answer in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        main.post(() -> {
            if (view[0] != null) {
                view[0].destroy();
            }
        });
        return answer[0];
    }

    @SuppressLint("SetJavaScriptEnabled")
    private static WebView open(App app, List<String> scripts, String question, ValueCallback<String> answer) {
        WebView view = new WebView(app);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true); // a script that reads localStorage must not stop on it
        settings.setBlockNetworkLoads(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        view.setWebViewClient(new WebViewClient() {
            private boolean asked;

            @Override
            public void onPageFinished(WebView page, String url) {
                if (asked) {
                    return;
                }
                asked = true;
                for (String script : scripts) {
                    page.evaluateJavascript(script, null);
                }
                page.evaluateJavascript(question, answer);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView page, String url) {
                return true; // the scripts stay on this page
            }
        });
        return view;
    }
}
