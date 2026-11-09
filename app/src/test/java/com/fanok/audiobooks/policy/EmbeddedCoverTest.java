package com.fanok.audiobooks.policy;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fanok.audiobooks.BookRemovedException;
import com.fanok.audiobooks.CookesExeption;
import com.fanok.audiobooks.net.RateLimitedException;
import com.fanok.audiobooks.policy.EmbeddedCover.Demand;
import com.fanok.audiobooks.policy.EmbeddedCover.Pace;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.jsoup.HttpStatusException;
import org.junit.Test;

public class EmbeddedCoverTest {

    private static final byte[] FRONT = {1, 2, 3, 4};
    private static final byte[] OTHER = {9, 9};

    /** An ID3v2.3 APIC frame: no text encoding, a mime type, the picture type, an empty description, the data. */
    private static byte[] apic(int pictureType, byte[] data) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(0);
        body.write("image/jpeg".getBytes(StandardCharsets.ISO_8859_1));
        body.write(0);
        body.write(pictureType);
        body.write(0);
        body.write(data);
        byte[] content = body.toByteArray();
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write("APIC".getBytes(StandardCharsets.ISO_8859_1));
        frame.write(new byte[]{0, 0, (byte) (content.length >> 8), (byte) content.length, 0, 0});
        frame.write(content);
        return frame.toByteArray();
    }

    /** An ID3v2.3 tag of {@code frames}, followed by some audio. */
    private static byte[] file(byte[]... frames) throws IOException {
        ByteArrayOutputStream tagBody = new ByteArrayOutputStream();
        for (byte[] frame : frames) {
            tagBody.write(frame);
        }
        int size = tagBody.size();
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.write(new byte[]{'I', 'D', '3', 3, 0, 0,
                (byte) (size >> 21 & 0x7f), (byte) (size >> 14 & 0x7f), (byte) (size >> 7 & 0x7f), (byte) (size & 0x7f)});
        file.write(tagBody.toByteArray());
        file.write(new byte[]{(byte) 0xff, (byte) 0xfb, 0x10, 0x00}); // an MPEG frame header: not part of the tag
        return file.toByteArray();
    }

    @Test
    public void readsTheTagOnlyAndPicksTheFrontCover() throws IOException {
        byte[] file = file(apic(0, OTHER), apic(3, FRONT));
        InputStream in = new ByteArrayInputStream(file);

        byte[] tag = EmbeddedCover.Id3.tag(in);

        assertEquals(file.length - 4, tag.length);
        assertEquals(4, in.available()); // the audio after the tag is left unread
        assertArrayEquals(FRONT, EmbeddedCover.Id3.frontCover(tag));
    }

    @Test
    public void anyPictureWhenNoneIsTheFrontCover() throws IOException {
        assertArrayEquals(OTHER, EmbeddedCover.Id3.frontCover(EmbeddedCover.Id3.tag(
                new ByteArrayInputStream(file(apic(0, OTHER))))));
    }

    @Test
    public void nothingWithoutATagOrAPicture() throws IOException {
        assertNull(EmbeddedCover.Id3.tag(new ByteArrayInputStream(new byte[]{(byte) 0xff, (byte) 0xfb, 0x10, 0, 0, 0,
                0, 0, 0, 0, 0})));
        byte[] text = "TIT2".getBytes(StandardCharsets.ISO_8859_1);
        byte[] frame = new byte[text.length + 6 + 3];
        System.arraycopy(text, 0, frame, 0, 4);
        frame[7] = 3;
        frame[10] = 0;
        frame[11] = 'A';
        frame[12] = 'B';
        assertNull(EmbeddedCover.Id3.frontCover(EmbeddedCover.Id3.tag(new ByteArrayInputStream(file(frame)))));
    }

    /** A transfer cut inside the tag is not "no cover": it throws, so nothing is remembered for the book. */
    @Test(expected = EOFException.class)
    public void aCutTagThrows() throws IOException {
        byte[] file = file(apic(3, FRONT));
        EmbeddedCover.Id3.tag(new ByteArrayInputStream(Arrays.copyOf(file, 20)));
    }

    @Test
    public void aV24FooterFlagIsDropped() throws IOException {
        byte[] file = file(apic(3, FRONT));
        file[3] = 4;
        file[5] = 0x10;
        byte[] tag = EmbeddedCover.Id3.tag(new ByteArrayInputStream(file));
        assertEquals(0, tag[5]);
    }

    /** The row nearest the top is read first (the one asked first among equals), then the next; asked again, it moves. */
    @Test
    public void theTopmostRowGoesFirst() {
        Demand demand = new Demand();
        assertTrue(demand.want("a", "A", 4, true));
        assertTrue(demand.want("b", "B", 2, true));
        assertFalse(demand.want("a", "A", 4, false)); // asked again: not queued twice
        assertTrue(demand.want("c", "C", 2, true));
        assertTrue(demand.want("d", "D", 0, true));
        assertFalse(demand.want("a", "A", 1, false)); // scrolled up to 1
        assertArrayEquals(new String[]{"d", "D"}, demand.next());
        assertFalse(demand.want("d", "D", 0, false)); // its turn is under way: not queued twice
        demand.done("d");
        assertTrue(demand.want("d", "D", 0, false)); // its turn ended without a cover: asked again, it is queued again
        assertEquals("d", demand.next()[0]);
        assertEquals("a", demand.next()[0]);
        assertEquals("b", demand.next()[0]); // asked before "c" at the same position
        assertEquals("c", demand.next()[0]);
        assertNull(demand.next());
    }

    /** A row scrolled away waits no more and its page is not fetched; a read under way keeps its book to itself. */
    @Test
    public void aRowLeavingDropsOnlyItsWait() {
        Demand demand = new Demand();
        demand.want("a", "A", 0, true);
        demand.want("b", "B", 1, true);
        demand.drop("a");
        assertEquals("b", demand.next()[0]);
        assertTrue(demand.wanted("b"));
        assertTrue(demand.take("b", null));
        demand.drop("b");
        assertFalse(demand.wanted("b"));
        assertTrue(demand.taken("b", null));
        assertFalse(demand.take("b", null)); // no second reader of the same file
        assertFalse(demand.want("b", "B", 1, true));
        assertNull(demand.end("b", true)); // its read failed
        assertTrue(demand.want("b", "B", 1, false)); // shown still: back in the queue
        demand.drop("a"); // never mind a row not waiting
        assertEquals("b", demand.next()[0]);
    }

    /** The opened or playing book takes its book past the rows; the rows' comings and goings never cancel it. */
    @Test
    public void theOpenedBookIsIndependentOfTheRows() {
        Demand demand = new Demand();
        demand.want("a", "A", 1, true);
        demand.want("b", "B", 0, true);
        assertTrue(demand.take("b", new String[]{"B", null})); // its own lane, its row first in the rows
        demand.drop("b");
        assertTrue(demand.taken("b", null));
        assertFalse(demand.want("b", "B", 0, true));
        assertEquals("a", demand.next()[0]);
        assertNull(demand.next());
    }

    /** A book two lists show (a search over a list) waits until neither does: one list leaving cancels nothing. */
    @Test
    public void aBookWaitsWhileAnyListShowsIt() {
        Demand demand = new Demand();
        assertTrue(demand.want("a", "A", 3, true));
        assertFalse(demand.want("a", "A", 0, true)); // the search shows it too: queued once, now at 0
        demand.drop("a"); // the search closed
        assertArrayEquals(new String[]{"a", "A"}, demand.next());
        assertTrue(demand.wanted("a")); // the list shows it still: its page is fetched
        demand.drop("a");
        assertFalse(demand.wanted("a"));
        demand.done("a");
        assertFalse(demand.want("a", "A", 0, false)); // no row asks for it any more
        assertNull(demand.next());
    }

    /** The opened book asking while a row reads its book is read once that read fails: its latest request, once. */
    @Test
    public void theOpenedBookWaitingForARowsReadIsReadIfItFails() {
        Demand demand = new Demand();
        assertTrue(demand.take("a", null)); // a row reads it
        assertTrue(demand.taken("a", new String[]{"A", null})); // the opened book waits
        assertFalse(demand.take("a", new String[]{"A", "u"})); // and asks again, with its first chapter
        assertArrayEquals(new String[]{"A", "u"}, demand.end("a", true));
        assertFalse(demand.taken("a", null)); // to be read now
        assertTrue(demand.take("a", null));
        assertTrue(demand.taken("a", new String[]{"A", "u"}));
        assertNull(demand.end("a", false)); // the row read its cover (or none for good): nothing to read again
        assertTrue(demand.taken("a", new String[]{"A", "u"})); // read already: nothing waits for it
        assertTrue(demand.take("b", null));
        assertNull(demand.end("b", true)); // a failed read nobody waited for
    }

    @Test
    public void pagesGoOneGapApartAndStopForAWhileAfterTheSiteCheck() {
        Pace pace = new Pace();
        assertEquals(0, pace.pageIn(1_000));
        pace.paged(1_000, false);
        assertEquals(Pace.PAGE_GAP_MS, pace.pageIn(1_000));
        assertEquals(Pace.PAGE_GAP_MS - 500, pace.pageIn(1_500));
        assertEquals(0, pace.pageIn(1_000 + Pace.PAGE_GAP_MS));
        pace.paged(10_000, true);
        assertEquals(-1, pace.pageIn(10_000 + Pace.PAGE_STOP_MS - 1));
        assertEquals(0, pace.pageIn(10_000 + Pace.PAGE_STOP_MS));
        assertTrue(pace.may("a", "U", true, 10_000)); // rows whose audio is known go on meanwhile
    }

    @Test
    public void aFailedBookWaitsLongerEachTimeAndFailuresInARowPauseTheRows() {
        Pace pace = new Pace();
        pace.failed("a", "U", false, 0);
        assertFalse(pace.may("a", "U", false, Pace.RETRY_MS - 1));
        assertFalse(pace.may("a", "U", true, Pace.RETRY_MS - 1)); // the opened book's failure holds its rows back too
        assertTrue(pace.may("a", "U", false, Pace.RETRY_MS));
        assertTrue(pace.may("b", "V", true, 0)); // one book's failure stops no other
        pace.failed("a", "U", false, Pace.RETRY_MS);
        assertFalse(pace.may("a", "U", false, 3 * Pace.RETRY_MS - 1));
        assertTrue(pace.may("a", "U", false, 3 * Pace.RETRY_MS));
        pace.failed("c", "W", false, 0); // the third failure in a row
        assertFalse(pace.may("b", "V", true, Pace.ROWS_PAUSE_MS - 1));
        assertTrue(pace.may("b", "V", true, Pace.ROWS_PAUSE_MS));
        assertTrue(pace.may("b", "V", false, 0)); // the opened book is not paused
        pace.read("a");
        assertTrue(pace.may("a", "U", false, 0));
        pace.failed("d", "X", false, Pace.RETRY_MAX_MS); // counted from the answer that came: one pauses nothing
        assertTrue(pace.may("b", "V", true, Pace.RETRY_MAX_MS));
        for (int i = 0; i < 10; i++) {
            pace.failed("e", "Y", false, 0);
        }
        assertTrue(pace.may("e", "Y", false, Pace.RETRY_MAX_MS)); // never longer than the cap
    }

    /** A row's failure never holds back the opened book, and another source (a new audio URL) is read at once. */
    @Test
    public void aFailureHoldsBackOnlyItsSourceAndARowsOnlyTheRows() {
        Pace pace = new Pace();
        pace.failed("a", "U", true, 0); // a row's read of U failed
        assertFalse(pace.may("a", "U", true, 1));
        assertTrue(pace.may("a", "U", false, 1)); // the opened book reads it
        assertTrue(pace.may("a", "U2", true, 1)); // the page gave another URL
        pace.failed("a", "P", true, 0); // its page failed next: counted anew
        assertFalse(pace.may("a", "P", true, Pace.RETRY_MS - 1));
        assertTrue(pace.may("a", "P", true, Pace.RETRY_MS));
    }

    /** A stored clearance gone during a page's request was refused there: the site checks again. */
    @Test
    public void aClearanceGoneMeanwhileWasRefused() {
        assertTrue(Pace.refused("c1", ""));
        assertFalse(Pace.refused("c1", "c1"));
        assertFalse(Pace.refused("", "")); // none went out
        assertFalse(Pace.refused("", "c2")); // the user passed the check meanwhile
    }

    /** Only a removed item is not asked again; a proxy's page, a refusal or a server error may pass. */
    @Test
    public void anAnswerIsTheAudioAGoneItemOrAFailureThatMayPass() {
        assertEquals(Pace.AUDIO, Pace.answer(206, "audio"));
        assertEquals(Pace.AUDIO, Pace.answer(200, null));
        assertEquals(Pace.GONE, Pace.answer(404, "text"));
        assertEquals(Pace.GONE, Pace.answer(410, null));
        assertEquals(Pace.RETRY, Pace.answer(200, "text"));
        for (int code : new int[]{302, 403, 429, 500, 502, 503}) {
            assertEquals(Pace.RETRY, Pace.answer(code, "audio"));
        }
    }

    /** The site's check or a refusal of a page stops the pages, wrapped or not; a timeout or a page gone does not. */
    @Test
    public void theSiteCheckOrARefusalStopsThePages() {
        assertTrue(Pace.stopsPages(new CookesExeption("https://audioboo.org/1.html")));
        assertTrue(Pace.stopsPages(new RuntimeException(new HttpStatusException("refused", 403, "u"))));
        assertTrue(Pace.stopsPages(new RuntimeException(new RateLimitedException("u", 0))));
        assertTrue(Pace.stopsPages(new HttpStatusException("unavailable", 503, "u")));
        assertFalse(Pace.stopsPages(new RuntimeException(new SocketTimeoutException())));
        assertFalse(Pace.stopsPages(new RuntimeException(new HttpStatusException("gone", 404, "u"))));
        assertFalse(Pace.stopsPages(new BookRemovedException("u")));
        assertFalse(Pace.stopsPages(null));
    }
}
