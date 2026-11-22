package com.fanok.audiobooks.activity;

import android.app.Activity;
import android.app.Dialog;
import android.view.View;
import android.view.Window;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.WindowCompat;
import com.bumptech.glide.Glide;
import com.fanok.audiobooks.R;
import com.fanok.audiobooks.SpectrumView;
import com.fanok.audiobooks.adapter.BooksListAddapter;
import com.fanok.audiobooks.pojo.BookPOJO;
import com.fanok.audiobooks.service.SeekStep;
import com.fanok.audiobooks.util.ChapterText;
import com.google.android.material.color.MaterialColors;

/**
 * The book's cover over the whole screen with the player, opened by a tap on the cover in the description: above the
 * cover the playing chapter, where the book stands, the chapter's progress and a spectrum meter; under it the open
 * player's five buttons, as large as the screen allows. In landscape the cover is on the left, the rest beside it. A
 * tap anywhere but on a button closes it, as the bare cover did (the buttons' row swallows a near miss), and so does
 * Back; the cover still zooms. It keeps no state: BookActivity, started under it all along, wires the buttons to the
 * player sheet's own handlers and shows here what the sheet shows ({@link #showPlaying}, {@link #showChapter},
 * {@link #showLine}, {@link #showTime}).
 */
final class CoverPlayer extends Dialog {

    final View rewind;
    final View previous;
    final View play;
    final View next;
    final View forward;
    /** The buttons' row: a snackbar over the cover is anchored above it. */
    final View controls;
    private final ImageView mCover;
    private final ImageView mPlayIcon;
    private final TextView mChapter;
    private final TextView mLine;
    private final ProgressBar mProgress;
    private final TextView mElapsed;
    private final TextView mLeft;
    private final SpectrumView mMeter;

    /** {@code photo}: the cover to show unless the book has its own ({@link BooksListAddapter#loadCover}). */
    CoverPlayer(@NonNull Activity activity, @NonNull BookPOJO book, @Nullable String photo) {
        super(activity, R.style.CoverPlayer);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_cover_player);
        Window window = getWindow();
        if (window != null) {
            // the status bar in the screen's colour, its icons dark on a light one: hidden, it would leave the
            // camera's cutout a band of the screen under it
            int background = MaterialColors.getColor(window.getDecorView(), R.attr.backgraundColor);
            window.setStatusBarColor(background);
            WindowCompat.getInsetsController(window, window.getDecorView())
                    .setAppearanceLightStatusBars(ColorUtils.calculateLuminance(background) > 0.5);
        }
        findViewById(R.id.coverPlayer).setOnClickListener(v -> dismiss());
        mCover = findViewById(R.id.coverImage);
        mCover.setOnClickListener(v -> dismiss()); // PhotoView's single tap, never part of a double tap or a pinch
        BooksListAddapter.loadCover(mCover, book, photo);
        TextView title = findViewById(R.id.coverBookTitle); // landscape only
        if (title != null) {
            title.setText(book.getName() == null ? "" : book.getName().trim());
        }
        mChapter = findViewById(R.id.coverChapter);
        mLine = findViewById(R.id.coverLine);
        mProgress = findViewById(R.id.coverProgress);
        mElapsed = findViewById(R.id.coverElapsed);
        mLeft = findViewById(R.id.coverLeft);
        mMeter = findViewById(R.id.coverMeter);
        rewind = findViewById(R.id.coverRewind);
        previous = findViewById(R.id.coverPrevious);
        play = findViewById(R.id.coverPlay);
        mPlayIcon = findViewById(R.id.coverPlayIcon);
        next = findViewById(R.id.coverNext);
        forward = findViewById(R.id.coverForward);
        controls = findViewById(R.id.coverControls);
        // a tap seeks by the step set in the settings, and the icons show it, as in the sheet
        ((ImageView) rewind).setImageResource(SeekStep.rewindIcon(activity));
        rewind.setContentDescription(SeekStep.rewindLabel(activity));
        ((ImageView) forward).setImageResource(SeekStep.forwardIcon(activity));
        forward.setContentDescription(SeekStep.forwardLabel(activity));
        setOnShowListener(d -> play.requestFocus()); // a TV remote starts on play
    }

    /** The play button's icon, as the sheet's; the meter runs while it is the pause one. */
    void showPlaying(int icon, @NonNull CharSequence label) {
        mPlayIcon.setImageResource(icon);
        play.setContentDescription(label);
        mMeter.setPlaying(icon == R.drawable.ic_pause);
    }

    void showChapter(@NonNull CharSequence name) {
        mChapter.setText(name);
    }

    /** "Глава 3 из 16 · осталось 1 ч. 32 мин.", the book's size before it is started; none: no line. */
    void showLine(@NonNull String line) {
        mLine.setText(line);
        mLine.setVisibility(line.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** The chapter's progress, the time played and the time left; nothing until its length is known. */
    void showTime(int ms, int endMs) {
        int shown = endMs > 0 ? View.VISIBLE : View.INVISIBLE;
        mProgress.setVisibility(shown);
        mElapsed.setVisibility(shown);
        mLeft.setVisibility(shown);
        mProgress.setMax(Math.max(endMs, 1));
        mProgress.setProgress(ms);
        mElapsed.setText(ChapterText.duration(ms / 1000));
        mLeft.setText("−" + ChapterText.duration(Math.max(0, endMs - ms) / 1000));
    }

    @Override
    protected void onStop() {
        // the cover's bitmap goes with the dialog, not with the book screen
        Glide.with(getContext().getApplicationContext()).clear(mCover);
        super.onStop();
    }
}
