package com.fanok.audiobooks.presenter;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import com.fanok.audiobooks.pojo.AudioPOJO;
import com.fanok.audiobooks.service.PlaybackSettings;
import java.util.Arrays;
import org.junit.Test;

/** The book screen's speed as text and on the dialog's slider, and the chapter data fed to BookProgress. */
public class BookPresenterSpeedTest {

    @Test
    public void speedTextUsesACommaAndDropsTrailingZeros() {
        assertEquals("1×", BookPresenter.speedText(1f));
        assertEquals("1,5×", BookPresenter.speedText(1.5f));
        assertEquals("1,25×", BookPresenter.speedText(1.25f));
        assertEquals("1,05×", BookPresenter.speedText(1.05f));
        assertEquals("0,25×", BookPresenter.speedText(0.25f));
        assertEquals("4×", BookPresenter.speedText(4f));
    }

    @Test
    public void speedTextShowsTheSpeedPlayed() {
        assertEquals("4×", BookPresenter.speedText(10f));
        assertEquals("0,25×", BookPresenter.speedText(0.1f));
    }

    @Test
    public void theSliderReachesBothEndsOfThePlayersRange() {
        assertEquals(PlaybackSettings.MIN_SPEED, BookPresenter.speedAt(0), 0f);
        int last = BookPresenter.speedStep(PlaybackSettings.MAX_SPEED);
        assertEquals(75, last);
        assertEquals(PlaybackSettings.MAX_SPEED, BookPresenter.speedAt(last), 0f);
    }

    @Test
    public void everyStepIsAFiftiethAndOneIsOnTheWay() {
        assertEquals(1f, BookPresenter.speedAt(BookPresenter.speedStep(1f)), 0f);
        assertEquals(0.3f, BookPresenter.speedAt(1), 0f);
        assertEquals(1.5f, BookPresenter.speedAt(BookPresenter.speedStep(1.5f)), 0f);
        // a speed kept by the old dialog (tenths) is still a position of the slider
        assertEquals(1.1f, BookPresenter.speedAt(BookPresenter.speedStep(1.1f)), 0f);
    }

    @Test
    public void aSubsetChapterIsCountedInTheFullList() {
        int[] held = {2, 5, 6};
        assertEquals(5, BookPresenter.ordinal(held, 1));
        assertEquals(1, BookPresenter.ordinal(null, 1));
    }

    @Test
    public void chapterSecondsKeepTheirOrderAndUnknownLengths() {
        AudioPOJO first = new AudioPOJO();
        first.setTime(600);
        AudioPOJO unknown = new AudioPOJO();
        assertArrayEquals(new int[]{600, 0}, BookPresenter.secondsOf(Arrays.asList(first, unknown)));
    }
}
