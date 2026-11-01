package com.fanok.audiobooks.region;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetAddress;

/** The real asset, read the way the app reads it: positional reads, never the whole file. */
public class IpRegionsTest {

    private static RandomAccessFile file;
    private static IpRegions table;
    private static int reads;
    private static int bytes;

    @BeforeClass
    public static void open() throws IOException {
        file = new RandomAccessFile(new File("src/main/assets/" + IpRegions.ASSET), "r");
        table = IpRegions.open((position, buffer, offset, length) -> {
            reads++;
            bytes += length;
            file.seek(position);
            file.readFully(buffer, offset, length);
        }, file.length());
    }

    @AfterClass
    public static void close() throws IOException {
        file.close();
    }

    @Test
    public void regions() throws Exception {
        String[][] cases = {
                {"77.88.8.8", "RUSSIA"}, {"213.87.0.1", "RUSSIA"}, {"5.255.255.70", "RUSSIA"}, {"2a02:6b8::1", "RUSSIA"},
                {"176.8.0.1", "UKRAINE"},
                {"8.8.8.8", "WEST"}, {"1.1.1.1", "WEST"}, {"2001:4860:4860::8888", "WEST"}, {"2a00:1450::1", "WEST"},
                {"39.128.0.1", "OTHER"},
        };
        for (String[] c : cases) {
            assertEquals(c[0], Region.valueOf(c[1]), table.of(InetAddress.getByName(c[0])));
        }
        assertEquals(2026, table.year);
        assertTrue(table.month >= 1 && table.month <= 12);
    }

    @Test
    public void onlyPublicAddresses() throws Exception {
        for (String ip : new String[]{"10.1.2.3", "192.168.0.1", "100.64.0.1", "127.0.0.1", "fe80::1", "2001:db8::1"}) {
            assertNull(ip, table.of(InetAddress.getByName(ip)));
        }
    }

    @Test
    public void aLookupReadsOneSmallBlock() throws Exception {
        int before = reads;
        int beforeBytes = bytes;
        table.of(InetAddress.getByName("176.8.0.1"));
        assertEquals(1, reads - before);
        assertTrue(bytes - beforeBytes <= 512);
    }

    @Test
    public void notATable() {
        byte[] junk = "IPRG not this".getBytes();
        try {
            IpRegions.open((position, buffer, offset, length) -> System.arraycopy(junk, (int) position, buffer, offset,
                    length), junk.length);
            fail();
        } catch (IOException expected) {
            // the old format or anything else
        }
    }
}
