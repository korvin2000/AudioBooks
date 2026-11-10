package com.fanok.audiobooks.activity;

import com.fanok.audiobooks.FontScale;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;
import com.fanok.audiobooks.AppThemes;
import com.fanok.audiobooks.Consts;
import com.fanok.audiobooks.CookesExeption;
import com.fanok.audiobooks.R;
import com.fanok.audiobooks.Url;
import com.fanok.audiobooks.databinding.ActivityCookiesBinding;
import com.fanok.audiobooks.model.BookPages;
import com.fanok.audiobooks.net.AppProxy;
import com.fanok.audiobooks.net.SiteHttp;
import com.fanok.audiobooks.search.Provider;
import com.fanok.audiobooks.source.Audioboo;
import com.r0adkll.slidr.Slidr;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.jsoup.Connection;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;


/**
 * Lets the user pass the site's browser check in a real browser; the session is kept only once a real request of the
 * app with it returns a normal page (UI-17). Leaving early never touches the stored session. baza-knig: its session
 * cookie. audioboo: Cloudflare's clearance, bound to the browser's own User-Agent, which the
 * app then sends to that site ({@link Audioboo#saveClearance}). Opened by a list the site refused
 * ({@link #EXTRA_PROVIDER}), it closes itself once the session is accepted, so the list loads again. The check runs on
 * the very page refused ({@link #EXTRA_URL}); audioboo's book page the app is still refused is read from this browser.
 */
public class ActivityCookies extends AppCompatActivity {

    /** The screen at the chosen font scale (FontScale). */
    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(FontScale.wrap(newBase));
    }

    /** The {@link Provider} name of the site; without it baza-knig. */
    public static final String EXTRA_PROVIDER = "provider";
    /**
     * The page the site refused. The check is passed and verified on it, never on the start page: the site
     * may let that through without any clearance, which would "verify" one its other pages refuse.
     */
    private static final String EXTRA_URL = "url";
    /** With {@link #EXTRA_URL}, a book's page: the copy this browser shows serves if the app is still refused. */
    private static final String EXTRA_BOOK = "book";

    private static final String TAG = "ActivityCookies";
    private static final String SESSION_COOKIE = "PHPSESSID";

    private ActivityCookiesBinding binding;
    private Disposable verification;
    private String lastTried = "";
    private boolean verified;
    /** audioboo: the clearance and the browser's User-Agent, not baza-knig's session. */
    private boolean clearance;
    private String server;
    /** What is checked: {@link #EXTRA_URL} when it is the site's, else {@link #server}. */
    private String page;
    private boolean book;
    private boolean taken;
    private boolean proxyHinted;

    /** Whether the browser check of {@code provider} can be passed here, so a list it refused offers it. */
    public static boolean passes(@Nullable Provider provider) {
        return provider == Provider.AUDIOBOO || provider == Provider.BAZA_KNIG;
    }

    @NonNull
    public static Intent intent(@NonNull Context context, @NonNull Provider provider) {
        return new Intent(context, ActivityCookies.class).putExtra(EXTRA_PROVIDER, provider.name());
    }

    /** The check of the page at {@code url} that {@code provider} refused; {@code book}: a book's page. */
    @NonNull
    public static Intent intent(@NonNull Context context, @NonNull Provider provider, @NonNull String url,
            boolean book) {
        return intent(context, provider).putExtra(EXTRA_URL, url).putExtra(EXTRA_BOOK, book);
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppThemes.apply(this, R.style.AppTheme_NoAnimTheme, R.style.LightAppTheme_NoAnimTheme,
                R.style.AppThemeBlack_NoAnimTheme);
        super.onCreate(savedInstanceState);
        binding = ActivityCookiesBinding.inflate(getLayoutInflater());
        View view = binding.getRoot();
        setContentView(view);
        Slidr.attach(this);

        clearance = Provider.AUDIOBOO.name().equals(getIntent().getStringExtra(EXTRA_PROVIDER));
        server = clearance ? Audioboo.SERVER + "/" : Url.SERVER_BAZA_KNIG;
        String asked = getIntent().getStringExtra(EXTRA_URL);
        page = asked != null && Url.isHost(Url.canonical(asked), server) ? Url.canonical(asked) : server;
        book = clearance && !page.equals(server) && getIntent().getBooleanExtra(EXTRA_BOOK, false);
        setSupportActionBar(binding.toolbar);
        setTitle(Url.hostOf(server));
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setHomeButtonEnabled(true);
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        Toast.makeText(this, R.string.wait_loading, Toast.LENGTH_LONG).show();
        binding.webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (!verifyCurrentSession() && (verification == null || verification.isDisposed())) {
                    takePageIfShown(); // no clearance to try or being tried, yet the browser has the book's page
                }
            }
        });
        binding.webView.getSettings().setLoadsImagesAutomatically(true);
        binding.webView.getSettings().setJavaScriptEnabled(true);
        if (!clearance) {
            binding.webView.getSettings().setUserAgentString(Consts.USER_AGENT);
        } // audioboo: the browser as it is; the app takes its User-Agent, to which the clearance is bound
        binding.webView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        binding.webView.loadUrl(page);
    }

    /**
     * A book's page the app is still refused although this browser shows it (the clearance bound to the browser
     * itself, the app reaching the site through a proxy): the book screen reads this copy ({@link BookPages#offer}).
     * Only that very page with its chapters is taken, never another one the user went to or a challenge or error page.
     */
    private void takePageIfShown() {
        String shown = binding == null ? null : binding.webView.getUrl();
        if (!book || taken || shown == null || isDestroyed() || !Url.samePage(shown, page)
                || SiteHttp.isChallenge(null, binding.webView.getTitle())) {
            return;
        }
        taken = true;
        binding.webView.evaluateJavascript("[location.href, document.documentElement.outerHTML]", json -> {
            Document doc = null;
            try {
                JsonArray got = JsonParser.parseString(json).getAsJsonArray();
                String href = got.get(0).getAsString();
                Document parsed = Jsoup.parse(got.get(1).getAsString(), href);
                if (Url.samePage(href, page) && !SiteHttp.isChallenge(null, parsed.title())
                        && !Audioboo.parseTracks(parsed, page).isEmpty()) {
                    doc = parsed;
                }
            } catch (RuntimeException e) { // no chapter list: not the book's page as the screen needs it
                Log.i(TAG, "Page not taken: " + e);
            }
            if (doc == null || isFinishing() || isDestroyed()) {
                taken = false;
                return;
            }
            BookPages.offer(page, doc);
            verified = true;
            setResult(RESULT_OK);
            finish();
        });
    }

    /**
     * Checks the WebView's current session once per distinct value: one request of the app's own to the site.
     *
     * @return whether a check went out
     */
    private boolean verifyCurrentSession() {
        final String name = clearance ? Audioboo.CLEARANCE_COOKIE : SESSION_COOKIE;
        final String session = cookie(CookieManager.getInstance().getCookie(server), name);
        // a clearance equal to the stored one is checked too: the site may refuse it to the app now (a new address)
        if (session.isEmpty() || session.equals(lastTried)) {
            return false;
        }
        final String agent = binding.webView.getSettings().getUserAgentString();
        lastTried = session;
        if (verification != null) {
            verification.dispose();
        }
        verification = Single.fromCallable(() -> {
                    // Cloudflare binds the clearance to the browser's UA: the request must look like that browser
                    Connection connection = SiteHttp.asBrowser(SiteHttp.connect(page), agent).cookie(name, session);
                    SiteHttp.parse(SiteHttp.execute(connection)); // a challenge page throws
                    return session;
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(ok -> {
                    if (clearance) {
                        Audioboo.saveClearance(ok, agent);
                    } else {
                        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(this);
                        pref.edit().putString("cookes_baza_knig", ok).apply();
                        Consts.setBazaKnigCookies(ok);
                        // the session the site issued the app's own requests earlier no longer shadows it
                        SiteHttp.forget(Url.SERVER_BAZA_KNIG, SESSION_COOKIE);
                    }
                    verified = true;
                    Toast.makeText(this, R.string.cookies_loaded, Toast.LENGTH_SHORT).show();
                    if (getIntent().hasExtra(EXTRA_PROVIDER)) {
                        setResult(RESULT_OK);
                        finish();
                    }
                }, e -> {
                    Log.i(TAG, "Session not accepted yet: " + e);
                    lastTried = ""; // the next page load may try again
                    if (clearance && !proxyHinted && !AppProxy.MODE_OFF.equals(AppProxy.mode())
                            && !AppProxy.isDirect(Provider.AUDIOBOO)) {
                        // this browser goes out directly while the app reaches the site through the proxy: the
                        // clearance is bound to the address it was issued to
                        proxyHinted = true;
                        Toast.makeText(this, R.string.site_check_proxy, Toast.LENGTH_LONG).show();
                    }
                    if (e instanceof CookesExeption
                            || e instanceof HttpStatusException && ((HttpStatusException) e).getStatusCode() == 403) {
                        takePageIfShown(); // refused, not a network failure: the next page load verifies again
                    }
                });
        return true;
    }

    /** The value of {@code PHPSESSID} in a {@code Cookie} header string, or "" when absent or malformed. */
    @NonNull
    static String sessionCookie(@Nullable String cookieHeader) {
        return cookie(cookieHeader, SESSION_COOKIE);
    }

    /** The value of the cookie {@code name} in a {@code Cookie} header string, or "" when absent or malformed. */
    @NonNull
    static String cookie(@Nullable String cookieHeader, @NonNull String name) {
        if (cookieHeader == null) {
            return "";
        }
        for (String pair : cookieHeader.split(";")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            if (name.equals(pair.substring(0, eq).trim())) {
                return pair.substring(eq + 1).trim();
            }
        }
        return "";
    }

    @Override
    protected void onDestroy() {
        if (verification != null) {
            verification.dispose();
        }
        if (isFinishing() && !verified) {
            Toast.makeText(this, R.string.cookies_not_verified, Toast.LENGTH_LONG).show();
        }
        if (binding != null) {
            binding.webView.stopLoading();
            binding.webView.destroy();
            binding = null; // a late script result finds no page to take
        }
        super.onDestroy();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull final MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
        }
        return super.onOptionsItemSelected(item);
    }
}
