package com.fanok.audiobooks.fragment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fanok.audiobooks.fragment.SourcePositionSheet.Preview;
import com.fanok.audiobooks.search.Provider;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The position sheet's preview: who shifts by one place and who crosses the line of the active sites. */
public class SourcePositionSheetTest {

    private static final Provider A = Provider.AKNIGA;
    private static final Provider B = Provider.KNIGAVUHE;
    private static final Provider C = Provider.IZIBUK;
    private static final Provider D = Provider.ABMP3;
    private static final Provider E = Provider.BAZA_KNIG;
    /** {@code [A, B, C | D, E]}: three active. */
    private static final List<Provider> ORDER = Arrays.asList(A, B, C, D, E);

    @Test
    public void movedDownOverTheLineTheSiteLeavesAndTheFirstInactiveComesIn() {
        Preview preview = Preview.of(ORDER, 3, A, 3);
        assertEquals(Arrays.asList(B, C, D, A, E), preview.order);
        assertTrue(preview.wasActive);
        assertFalse(preview.active);
        assertEquals(Arrays.asList(B, C), preview.shifted);
        assertEquals(Collections.singletonList(D), preview.activated);
        assertEquals(Collections.<Provider>emptyList(), preview.deactivated);
    }

    @Test
    public void movedUpOverTheLineTheLastActiveLeaves() {
        Preview preview = Preview.of(ORDER, 3, E, 0);
        assertEquals(Arrays.asList(E, A, B, C, D), preview.order);
        assertFalse(preview.wasActive);
        assertTrue(preview.active);
        assertEquals("shifted down, on their side", Arrays.asList(A, B, D), preview.shifted);
        assertEquals(Collections.<Provider>emptyList(), preview.activated);
        assertEquals(Collections.singletonList(C), preview.deactivated);
    }

    @Test
    public void aMoveOnOneSideCrossesNothing() {
        Preview preview = Preview.of(ORDER, 3, C, 0);
        assertEquals(Arrays.asList(C, A, B, D, E), preview.order);
        assertEquals(Arrays.asList(A, B), preview.shifted);
        assertTrue(preview.activated.isEmpty() && preview.deactivated.isEmpty());
    }

    @Test
    public void theSamePlaceChangesNothingAndAPlaceBeyondTheEndIsTheLast() {
        Preview same = Preview.of(ORDER, 3, B, 1);
        assertEquals(ORDER, same.order);
        assertTrue(same.shifted.isEmpty() && same.activated.isEmpty() && same.deactivated.isEmpty());
        assertEquals(Arrays.asList(A, C, D, E, B), Preview.of(ORDER, 3, B, 99).order);
    }
}
