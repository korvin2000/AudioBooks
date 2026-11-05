package com.fanok.audiobooks.activity;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.bumptech.glide.Glide;
import com.fanok.audiobooks.AppThemes;
import com.fanok.audiobooks.R;
import com.fanok.audiobooks.search.Narrators;
import com.r0adkll.slidr.Slidr;

import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

/**
 * A narrator's card, read from their page on fantlab.ru each time it opens ({@link Narrators#parse}): the readers
 * lists open it from the mark beside a reader fantlab knows.
 */
public class NarratorActivity extends AppCompatActivity {

    private static final String EXTRA_NAME = "name", EXTRA_URL = "url";

    private Disposable mLoad;

    public static void start(@NonNull Context context, @NonNull String name, @NonNull String url) {
        context.startActivity(new Intent(context, NarratorActivity.class).putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_URL, url));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        AppThemes.apply(this, R.style.AppTheme_SwipeOnClose, R.style.LightAppTheme_SwipeOnClose,
                R.style.AppThemeBlack_SwipeOnClose);
        super.onCreate(savedInstanceState);
        String url = getIntent().getStringExtra(EXTRA_URL);
        if (url == null) {
            finish();
            return;
        }
        setContentView(R.layout.activity_narrator);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(getIntent().getStringExtra(EXTRA_NAME));
            actionBar.setSubtitle(Uri.parse(url).getHost());
        }
        Slidr.attach(this);
        findViewById(R.id.narratorOpen).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, R.string.browser_not_found, Toast.LENGTH_SHORT).show();
            }
        });
        mLoad = Single.fromCallable(() -> Narrators.load(url))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::show, e -> {
                    findViewById(R.id.narratorProgress).setVisibility(View.GONE);
                    findViewById(R.id.narratorError).setVisibility(View.VISIBLE);
                });
    }

    private void show(Narrators.Card card) {
        findViewById(R.id.narratorProgress).setVisibility(View.GONE);
        if (card.picture != null) { // fetched with the card, on the route that served it
            ImageView photo = findViewById(R.id.narratorPhoto);
            photo.setVisibility(View.VISIBLE);
            Glide.with(photo).load(card.picture).into(photo);
        }
        text(R.id.narratorFacts, TextUtils.join("\n", card.facts));
        text(R.id.narratorBio, card.bio);
        findViewById(R.id.narratorBioTitle).setVisibility(card.bio.isEmpty() ? View.GONE : View.VISIBLE);
        if (text(R.id.narratorBooks, TextUtils.join("\n", card.books))) {
            text(R.id.narratorBooksTitle, getString(R.string.narrator_books, card.books.size()));
        }
    }

    /** Shows {@code value} in the view, or hides the view when it is empty; true when shown. */
    private boolean text(int id, String value) {
        TextView view = findViewById(id);
        view.setText(value);
        view.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
        return !value.isEmpty();
    }

    @Override
    protected void onDestroy() {
        if (mLoad != null) {
            mLoad.dispose();
        }
        super.onDestroy();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
