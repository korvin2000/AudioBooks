package com.fanok.audiobooks.policy;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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
}
