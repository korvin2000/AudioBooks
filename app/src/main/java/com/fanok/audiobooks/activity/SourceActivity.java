package com.fanok.audiobooks.activity;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.fanok.audiobooks.AppThemes;
import com.fanok.audiobooks.R;
import com.fanok.audiobooks.fragment.SourceCardFragment;
import com.fanok.audiobooks.search.Provider;
import com.r0adkll.slidr.Slidr;

/**
 * The card of one site on a screen of its own ({@link SourceCardFragment}): on a phone from the source list, and from
 * any other screen that names a site. The toolbar carries the site's title and host; the card adds its actions.
 */
public class SourceActivity extends AppCompatActivity {

    private static final String EXTRA_PROVIDER = "provider";

    public static void start(@NonNull Context context, @NonNull Provider provider) {
        context.startActivity(intent(context, provider));
    }

    @NonNull
    private static Intent intent(@NonNull Context context, @NonNull Provider provider) {
        return new Intent(context, SourceActivity.class).putExtra(EXTRA_PROVIDER, provider.name());
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        AppThemes.apply(this, R.style.AppTheme_SwipeOnClose, R.style.LightAppTheme_SwipeOnClose,
                R.style.AppThemeBlack_SwipeOnClose);
        super.onCreate(savedInstanceState);
        Provider provider = provider(getIntent());
        if (provider == null) {
            finish();
            return;
        }
        setContentView(R.layout.activity_source);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(provider.title);
            actionBar.setSubtitle(provider.host());
        }
        Slidr.attach(this);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .add(R.id.sourceContainer, SourceCardFragment.newInstance(provider),
                            SourceCardFragment.tag(provider))
                    .commit();
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Nullable
    private static Provider provider(@NonNull Intent intent) {
        String name = intent.getStringExtra(EXTRA_PROVIDER);
        for (Provider provider : Provider.values()) {
            if (provider.name().equals(name)) {
                return provider;
            }
        }
        return null;
    }
}
