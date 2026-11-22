package com.fanok.audiobooks.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SeekUndoTest {

    @Test
    public void smallMovesWithinAChapterAreNoJump() {
        assertFalse(SeekUndo.isJump(2, 10_000, 2, 10_000 + SeekUndo.JUMP_MS));
        assertTrue(SeekUndo.isJump(2, 10_000, 2, 10_001 + SeekUndo.JUMP_MS));
        assertTrue(SeekUndo.isJump(2, 100_000, 2, 10_000)); // backwards too
        assertTrue(SeekUndo.isJump(2, 10_000, 3, 10_000)); // another chapter at once
    }

    @Test
    public void aJumpOffersTheWayBack() {
        SeekUndo undo = new SeekUndo();
        SeekUndo.Place back = undo.move(1000, 4, 120_000, 4, 900_000);
        assertNotNull(back);
        assertEquals(4, back.chapter);
        assertEquals(120_000, back.positionMs);
    }

    @Test
    public void aSmallMoveOffersNothing() {
        assertNull(new SeekUndo().move(1000, 4, 120_000, 4, 150_000));
    }

    @Test
    public void jumpsInARowKeepTheFirstPlaceWhileTheOfferLasts() {
        SeekUndo undo = new SeekUndo();
        undo.move(1000, 4, 120_000, 4, 900_000);
        SeekUndo.Place back = undo.move(1000 + SeekUndo.WINDOW_MS - 1, 4, 900_000, 5, 0);
        assertNotNull(back);
        assertEquals(4, back.chapter);
        assertEquals(120_000, back.positionMs);
    }

    @Test
    public void anOldOfferIsForgottenAndTheNewJumpStartsFromItsOwnPlace() {
        SeekUndo undo = new SeekUndo();
        undo.move(1000, 4, 120_000, 4, 900_000);
        SeekUndo.Place back = undo.move(1000 + SeekUndo.WINDOW_MS, 4, 900_000, 7, 0);
        assertNotNull(back);
        assertEquals(4, back.chapter);
        assertEquals(900_000, back.positionMs);
    }

    @Test
    public void scrubbingBackToTheStartOffersNothing() {
        SeekUndo undo = new SeekUndo();
        undo.move(1000, 4, 120_000, 4, 900_000);
        assertNull(undo.move(2000, 4, 900_000, 4, 125_000));
        // and the next jump starts from where the listener is now
        SeekUndo.Place back = undo.move(3000, 4, 125_000, 4, 700_000);
        assertNotNull(back);
        assertEquals(125_000, back.positionMs);
    }

    @Test
    public void clearForgetsTheOffer() {
        SeekUndo undo = new SeekUndo();
        undo.move(1000, 4, 120_000, 4, 900_000);
        undo.take();
        SeekUndo.Place back = undo.move(2000, 4, 900_000, 4, 10_000);
        assertNotNull(back);
        assertEquals(900_000, back.positionMs);
    }

    @Test
    public void theOfferIsTakenOnce() {
        SeekUndo undo = new SeekUndo();
        assertFalse(undo.pending());
        undo.move(1000, 4, 120_000, 6, 0);
        assertTrue(undo.pending());
        SeekUndo.Place back = undo.take();
        assertNotNull(back);
        assertEquals(4, back.chapter);
        assertEquals(120_000, back.positionMs);
        assertFalse(undo.pending());
        assertNull(undo.take());
    }
}
