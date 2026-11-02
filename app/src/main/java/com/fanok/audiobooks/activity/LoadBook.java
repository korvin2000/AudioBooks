package com.fanok.audiobooks.activity;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Bundle;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;
import com.fanok.audiobooks.Consts;
import com.fanok.audiobooks.CookesExeption;
import com.fanok.audiobooks.net.RateLimitedException;
import com.fanok.audiobooks.R;
import com.fanok.audiobooks.Url;
import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.search.Provider;
import com.fanok.audiobooks.source.SourceParser;
import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import java.io.IOException;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;

public class LoadBook extends AppCompatActivity {

    private BookPOJO mBookPOJO;
    private String mUrl;
    private Context mContext;
    @Nullable
    private Disposable mLoad;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.load_book_activity);

        SharedPreferences pref = PreferenceManager
                .getDefaultSharedPreferences(this);

        String themeName = pref.getString("pref_theme", getString(R.string.theme_dark_value));
        if (themeName.equals(getString(R.string.theme_dark_value))) {
            setTheme(R.style.AppTheme_NoAnimTheme);
        } else if (themeName.equals(getString(R.string.theme_light_value))) {
            setTheme(R.style.LightAppTheme_NoAnimTheme);
        } else if (themeName.equals(getString(R.string.theme_black_value))) {
            setTheme(R.style.AppThemeBlack_NoAnimTheme);
        }

        mContext = this;
        Intent intent = getIntent();
        String extra = intent.getStringExtra("url");
        String data = Intent.ACTION_SEND.equals(intent.getAction())
                ? linkIn(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)) // some apps share a Spanned
                : intent.getData() == null ? null : intent.getData().toString();
        mUrl = bookUrl(extra, data);
        if (mUrl == null) {
            // a deep link to a host the app does not scrape, a shared text without such a link, or no URL at all
            Toast.makeText(this, R.string.unknown_source, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (extra == null && (intent.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) == 0) {
            // a link from another app opens in the app's own task, not inside the sender's (where a link to the book
            // already open in the background would silently do nothing)
            startActivity(new Intent(this, LoadBook.class).putExtra("url", mUrl)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
            return;
        }
        if (opensAsListing(mUrl)) {
            // a site's collection is a list of books: its listing opens, never a book page without chapters
            openListing();
            return;
        }
        // never trusted from an intent: this activity is exported, so the PIN check always runs (SEC-05)
        if (mUrl.equals(BookActivity.getShowingView())) finish();
    }

    @Override
    protected void onDestroy() {
        if (mLoad != null) {
            mLoad.dispose(); // Back while loading: the book must not open later
        }
        super.onDestroy();
    }

    /**
     * The provider book URL from the explicit in-app extra or an external link, on its current host; null when
     * unknown. An external link also loses its fragment and becomes https; the extra is kept as it is, so the
     * identities of saved books do not change.
     */
    static String bookUrl(String extra, String data) {
        String url = extra != null ? extra : data;
        if (url == null || Provider.ofUrl(url) == null) {
            return null;
        }
        String canonical = Url.canonical(url);
        if (extra != null) {
            return canonical;
        }
        int hash = canonical.indexOf('#');
        return (hash < 0 ? canonical : canonical.substring(0, hash)).replaceFirst("^(?i)http://", "https://");
    }

    /** The first link to a site of the app in a shared or pasted text (https:// added when typed without); else null. */
    static String linkIn(CharSequence text) {
        if (text == null) {
            return null;
        }
        for (String token : text.toString().split("\\s+")) {
            String link = token.replaceAll("^[(\\[<\u00ab\"'\u201c]+|[.,;:!?)\\]>\u00bb\"'\u201d]+$", "");
            if (link.isEmpty()) {
                continue;
            }
            if (!link.contains("://")) {
                link = "https://" + link;
            }
            if (Provider.ofUrl(link) != null) {
                return link;
            }
        }
        return null;
    }

    /** {@link #bookUrl} of the first site link in {@code text}; null when there is none. */
    public static String bookUrlIn(CharSequence text) {
        return bookUrl(null, linkIn(text));
    }

    /** The URL is a list of books (a site's collection), not a book. */
    public static boolean opensAsListing(String url) {
        SourceParser parser = Provider.parserOf(url);
        return parser != null && parser.opensAsListing(url);
    }

    /** The main screen's listing of {@code mUrl}, as a book page's author link opens it. */
    private void openListing() {
        MainActivity.startMainActivity(this, Consts.FRAGMENT_AUDIOBOOK, mUrl);
        finish();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mUrl == null || mLoad != null) {
            return; // loads once: returning to the screen must not fetch and open the book a second time
        }
        BookPOJO.getDescription(mUrl)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Observer<BookPOJO>() {
                    @Override
                    public void onError(@NotNull Throwable e) {
                        if (e instanceof IOException && String.valueOf(e.getMessage()).startsWith("no book on page")) {
                            // an author, series or genre page: the list of its books
                            openListing();
                            return;
                        }
                        int message;
                        if (e instanceof CookesExeption) {
                            message = CookesExeption.messageFor(e.getMessage());
                        } else if (e instanceof RateLimitedException) {
                            message = R.string.site_rate_limited;
                        } else if (e instanceof IllegalArgumentException
                                && String.valueOf(e.getMessage()).startsWith("unsupported host")) {
                            message = R.string.worong_data_sorce;
                        } else {
                            message = R.string.error_load_data;
                        }
                        Toast.makeText(mContext, message, Toast.LENGTH_LONG).show();
                        finish();
                    }

                    @Override
                    public void onNext(@NotNull BookPOJO bookPOJO) {
                        mBookPOJO = bookPOJO;
                    }

                    @Override
                    public void onSubscribe(@NotNull Disposable d) {
                        mLoad = d;
                    }

                    @Override
                    public void onComplete() {
                        BookActivity.startNewActivity(mContext, mBookPOJO);
                    }
                });
    }

    @Override
    public Resources.Theme getTheme() {
        Resources.Theme theme = super.getTheme();

        SharedPreferences pref = PreferenceManager
                .getDefaultSharedPreferences(this);

        String themeName = pref.getString("pref_theme", getString(R.string.theme_dark_value));
        if (themeName.equals(getString(R.string.theme_dark_value))) {
            theme.applyStyle(R.style.AppTheme_NoAnimTheme, true);
        } else if (themeName.equals(getString(R.string.theme_light_value))) {
            theme.applyStyle(R.style.LightAppTheme_NoAnimTheme, true);
        } else if (themeName.equals(getString(R.string.theme_black_value))) {
            theme.applyStyle(R.style.AppThemeBlack_NoAnimTheme, true);
        }


        return theme;
    }

}
