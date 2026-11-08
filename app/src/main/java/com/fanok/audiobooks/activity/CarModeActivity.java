package com.fanok.audiobooks.activity;

import static com.fanok.audiobooks.activity.BookActivity.Broadcast_SET_IMAGE;
import static com.fanok.audiobooks.activity.BookActivity.Broadcast_SET_PROGRESS;
import static com.fanok.audiobooks.activity.BookActivity.Broadcast_SET_SELECTION;
import static com.fanok.audiobooks.presenter.BookPresenter.Broadcast_PLAY;
import static com.fanok.audiobooks.presenter.BookPresenter.Broadcast_SEEK_NEXT_30;
import static com.fanok.audiobooks.presenter.BookPresenter.Broadcast_SEEK_PREVIOUS_10;
import static com.fanok.audiobooks.presenter.BookPresenter.Broadcast_SHOW_TITLE;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;
import com.fanok.audiobooks.AppThemes;
import com.fanok.audiobooks.R;
import com.fanok.audiobooks.policy.DefaultCover;
import com.fanok.audiobooks.policy.EmbeddedCover;
import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.service.MediaPlayerService;
import com.squareup.picasso.Callback;
import com.squareup.picasso.Picasso;
import com.squareup.picasso.RequestCreator;
import java.io.File;
import java.util.Locale;
import java.util.Objects;

/**
 * «Режим авто»: the player for the road. The book in the player, a big play/pause, the time left in the chapter and
 * two half-screen targets: back 15 s, forward 30 s. It sends the player's own commands and shows what the player
 * broadcasts, so it keeps no state; the screen stays on while it is shown.
 */
public class CarModeActivity extends AppCompatActivity {

    /** Its own steps, as in the owner's sketch, not the seek step setting of the other players: back a little to
     * catch a missed phrase, forward more. */
    private static final int BACK_S = 15;
    private static final int FORWARD_S = 30;

    private ImageView mCover;
    private ImageButton mPlay;
    private TextView mRemaining;
    private ProgressBar mProgress;
    /** The book whose cover is shown. */
    private String mCoverOf;

    /** The cover read from the audio of the book shown, when its site's own is never requested. */
    private final EmbeddedCover.Listener mEmbeddedCover = (key, cover) -> {
        BookPOJO book = MediaPlayerService.currentBook();
        if (book != null && EmbeddedCover.isOf(key, mCoverOf)) {
            mCoverOf = null;
            showCover(book);
        }
    };

    private final BroadcastReceiver mPlayer = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Broadcast_SET_IMAGE.equals(intent.getAction())) {
                boolean playing = intent.getIntExtra("id", R.drawable.ic_play) == R.drawable.ic_pause;
                mPlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
                mPlay.setContentDescription(getString(playing ? R.string.pause : R.string.play));
            } else if (Broadcast_SET_SELECTION.equals(intent.getAction())) {
                // a chapter, maybe of another book (one whose screen was still loading took the player)
                BookPOJO book = MediaPlayerService.currentBook();
                if (book != null) {
                    showCover(book);
                }
            } else {
                showTime(intent.getIntExtra("timeCurrent", 0), intent.getIntExtra("timeEnd", 0));
            }
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        AppThemes.apply(this, R.style.AppTheme_CarMode, R.style.LightAppTheme_CarMode, R.style.AppThemeBlack_CarMode);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_car_mode);
        mCover = findViewById(R.id.cover); // none in landscape
        mPlay = findViewById(R.id.play);
        mRemaining = findViewById(R.id.remaining);
        mProgress = findViewById(R.id.progress);

        findViewById(R.id.close).setOnClickListener(v -> finish());
        mPlay.setOnClickListener(v -> send(Broadcast_PLAY, 0, v));
        View rewind = findViewById(R.id.rewind);
        rewind.setOnClickListener(v -> send(Broadcast_SEEK_PREVIOUS_10, BACK_S, v));
        rewind.setContentDescription(getString(R.string.seek_back_n, BACK_S));
        ((ImageView) findViewById(R.id.rewindIcon)).setImageResource(R.drawable.ic_rewind_15);
        View forward = findViewById(R.id.forward);
        forward.setOnClickListener(v -> send(Broadcast_SEEK_NEXT_30, FORWARD_S, v));
        forward.setContentDescription(getString(R.string.seek_forward_n, FORWARD_S));
        ((ImageView) findViewById(R.id.forwardIcon)).setImageResource(R.drawable.ic_fast_forward_30);
        showTime(0, 0);
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(Broadcast_SET_PROGRESS);
        filter.addAction(Broadcast_SET_IMAGE);
        filter.addAction(Broadcast_SET_SELECTION);
        ContextCompat.registerReceiver(this, mPlayer, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        EmbeddedCover.listen(mEmbeddedCover);
        BookPOJO book = MediaPlayerService.currentBook();
        if (book == null) {
            finish(); // the player was stopped meanwhile
            return;
        }
        mCoverOf = null; // its cover may have been read from its audio while this screen was away
        showCover(book);
        send(Broadcast_SHOW_TITLE, 0, null); // the player answers with its play icon and progress
    }

    @Override
    protected void onStop() {
        unregisterReceiver(mPlayer);
        EmbeddedCover.unlisten(mEmbeddedCover);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (mCover != null) {
            Picasso.get().cancelRequest(mCover); // its callback must not outlive the screen
        }
        super.onDestroy();
    }

    /**
     * One of the player's commands, a seek by {@code seconds} (0: none); a tap is confirmed by a short vibration, so
     * the eyes can stay on the road.
     */
    private void send(@NonNull String action, int seconds, @Nullable View tapped) {
        if (MediaPlayerService.currentBook() == null) {
            finish(); // the player was stopped (a sleep timer): nothing listens; the book's screen starts it again
            return;
        }
        if (tapped != null) {
            tapped.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
        Intent command = new Intent(action).setPackage(getPackageName());
        if (seconds > 0) {
            command.putExtra("ms", seconds * 1000L); // MediaPlayerService takes it over the seek step
        }
        sendBroadcast(command);
    }

    /** The chapter's progress and the time left in it, as "−00:04:12". */
    private void showTime(int currentMs, int endMs) {
        mProgress.setMax(Math.max(endMs, 1));
        mProgress.setProgress(Math.min(currentMs, endMs));
        int left = Math.max(0, endMs - currentMs) / 1000;
        mRemaining.setText(endMs <= 0 ? "−--:--:--"
                : String.format(Locale.ROOT, "−%02d:%02d:%02d", left / 3600, left / 60 % 60, left % 60));
    }

    /** Its cover, else the default one (the same as in its lists), also while it loads and when it cannot. */
    private void showCover(@NonNull BookPOJO book) {
        if (mCover == null || Objects.equals(book.getUrl(), mCoverOf)) {
            return;
        }
        mCoverOf = book.getUrl();
        Drawable fallback = DefaultCover.drawable(this, book.getUrl());
        String photo = book.getPhoto();
        if (photo == null || photo.trim().isEmpty() || DefaultCover.replaces(photo)) {
            mCover.setImageDrawable(fallback); // a site whose covers are never requested: the one from its audio
            fitCover();
            return;
        }
        RequestCreator request = photo.startsWith("/") ? Picasso.get().load(new File(photo)) : Picasso.get().load(photo);
        request.placeholder(fallback).error(fallback).into(mCover, new Callback.EmptyCallback() {
            @Override
            public void onSuccess() {
                fitCover();
            }
        });
    }

    /** The cover's frame takes the loaded cover's shape, from a book's 2:3 to a square, so nothing is cut off. */
    private void fitCover() {
        Drawable cover = mCover.getDrawable();
        if (cover == null || cover.getIntrinsicWidth() <= 0 || cover.getIntrinsicHeight() <= 0) {
            return;
        }
        float ratio = (float) cover.getIntrinsicWidth() / cover.getIntrinsicHeight();
        ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) mCover.getLayoutParams();
        params.dimensionRatio = String.valueOf(Math.max(2f / 3, Math.min(1f, ratio)));
        mCover.setLayoutParams(params);
    }
}
