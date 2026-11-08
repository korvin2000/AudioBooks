package com.fanok.audiobooks.presenter;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import com.fanok.audiobooks.pojo.AudioPOJO;
import com.fanok.audiobooks.service.PlaybackSettings;
import java.util.Arrays;
import org.junit.Test;

/** The book screen's speed as text, on the slider (touch) and by minus / plus (TV), and the chapter data fed to BookProgress. */
public class BookPresenterSpeedTest {

    @Test
    public void speedTextUsesACommaAndDropsTrailingZeros() {
        assertEquals("1×", BookPresenter.speedText(1f));
        assertEquals("1,5×", BookPresenter.speedText(1.5f));
        assertEquals("1,25×", BookPresenter.speedText(1.25f));
        assertEquals("1,05×", BookPresenter.speedText(1.05f));
        assertEquals("0,25×", BookPresenter.speedText(0.25f));
        assertEquals("2,5×", BookPresenter.speedText(2.5f));
    }

    @Test
    public void speedTextShowsTheSpeedPlayed() {
        assertEquals("2,5×", BookPresenter.speedText(10f));
        assertEquals("0,25×", BookPresenter.speedText(0.1f));
    }

    @Test
    public void theSliderReachesBothEndsOfThePlayersRange() {
        assertEquals(PlaybackSettings.MIN_SPEED, BookPresenter.speedAt(0), 0f);
        int last = BookPresenter.speedStep(PlaybackSettings.MAX_SPEED);
        assertEquals(45, last);
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
    public void minusAndPlusMoveByATenth() {
        assertEquals(1.1f, BookPresenter.speedStepped(1f, 1), 1e-6f);
        assertEquals(0.9f, BookPresenter.speedStepped(1f, -1), 1e-6f);
        assertEquals(1.6f, BookPresenter.speedStepped(1.5f, 1), 1e-6f);
    }

    @Test
    public void aSpeedBetweenTenthsGoesToTheNextTenth() {
        // kept by the old slider (0.05x steps)
        assertEquals(1.3f, BookPresenter.speedStepped(1.25f, 1), 1e-6f);
        assertEquals(1.2f, BookPresenter.speedStepped(1.25f, -1), 1e-6f);
    }

    @Test
    public void bothEndsOfThePlayersRangeAreReachedAndKept() {
        assertEquals(PlaybackSettings.MIN_SPEED, BookPresenter.speedStepped(0.3f, -1), 0f);
        assertEquals(PlaybackSettings.MIN_SPEED, BookPresenter.speedStepped(PlaybackSettings.MIN_SPEED, -1), 0f);
        assertEquals(0.3f, BookPresenter.speedStepped(PlaybackSettings.MIN_SPEED, 1), 1e-6f);
        assertEquals(PlaybackSettings.MAX_SPEED, BookPresenter.speedStepped(2.4f, 1), 1e-6f);
        assertEquals(PlaybackSettings.MAX_SPEED, BookPresenter.speedStepped(PlaybackSettings.MAX_SPEED, 1), 0f);
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
