package com.fanok.audiobooks;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** «Оформление → Размер шрифта»: the font scale of a percentage of the system's ({@link FontScale#effective}). */
public class FontScaleTest {

    private static final float DELTA = 0.0001f;

    @Test
    public void thePercentageMultipliesTheSystemScale() {
        assertEquals(0.85f, FontScale.effective(1f, 85), DELTA);
        assertEquals(1.15f, FontScale.effective(1f, 115), DELTA);
        // a large system font made smaller for this app only: the users' request
        assertEquals(1.105f, FontScale.effective(1.3f, 85), DELTA);
    }

    @Test
    public void theSettingStaysWithinTheBoundsTheLayoutsHold() {
        assertEquals(FontScale.FLOOR, FontScale.effective(0.85f, 50), DELTA);
        assertEquals(FontScale.CEILING, FontScale.effective(1.3f, 200), DELTA);
        assertEquals(2f, FontScale.effective(1f, 200), DELTA);
        assertEquals(0.5f, FontScale.effective(1f, 50), DELTA);
    }

    @Test
    public void aSystemScaleBeyondTheBoundsIsNotPulledBack() {
        // the setting only moves it its own way: never larger when asked smaller, never smaller when asked larger
        assertEquals(2.5f, FontScale.effective(2.5f, 130), DELTA);
        assertEquals(2f, FontScale.effective(2.5f, 80), DELTA);
        assertEquals(0.4f, FontScale.effective(0.4f, 80), DELTA);
        assertEquals(0.52f, FontScale.effective(0.4f, 130), DELTA);
    }
}
