package com.fanok.audiobooks.region;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;

public class StunTest {

    private static final byte[] ID = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};

    @Test
    public void request() {
        byte[] r = Stun.request(ID);
        assertEquals(20, r.length);
        assertArrayEquals(new byte[]{0, 1, 0, 0, 0x21, 0x12, (byte) 0xA4, 0x42}, java.util.Arrays.copyOf(r, 8));
        assertEquals(ID[11], r[19]);
    }

    @Test
    public void xorMappedIpv4() throws Exception {
        byte[] packet = response(0x0101, ID, attribute(0x0020, xored(InetAddress.getByName("77.88.8.8"), ID)));
        assertEquals(InetAddress.getByName("77.88.8.8"), Stun.mapped(packet, packet.length, ID));
    }

    @Test
    public void xorMappedIpv6() throws Exception {
        InetAddress v6 = InetAddress.getByName("2a02:6b8::feed:ff");
        byte[] packet = response(0x0101, ID, attribute(0x0020, xored(v6, ID)));
        assertEquals(v6, Stun.mapped(packet, packet.length, ID));
    }

    /** stun.miwifi.com and stun.voipbuster.com answer with MAPPED-ADDRESS only; XOR wins when both come. */
    @Test
    public void mappedAddress() throws Exception {
        byte[] plain = attribute(0x0001, plain(InetAddress.getByName("39.128.0.7")));
        byte[] packet = response(0x0101, ID, concat(attribute(0x8022, "software".getBytes("UTF-8")), plain));
        assertEquals(InetAddress.getByName("39.128.0.7"), Stun.mapped(packet, packet.length, ID));
        byte[] both = response(0x0101, ID, concat(plain, attribute(0x8020, xored(InetAddress.getByName("5.6.7.8"), ID))));
        assertEquals(InetAddress.getByName("5.6.7.8"), Stun.mapped(both, both.length, ID));
    }

    @Test
    public void notAnAnswerToUs() throws Exception {
        byte[] value = xored(InetAddress.getByName("77.88.8.8"), ID);
        byte[] other = ID.clone();
        other[0] = 99;
        assertNull("another transaction", Stun.mapped(response(0x0101, other, attribute(0x0020, value)), 32, ID));
        assertNull("an error response", Stun.mapped(response(0x0111, ID, attribute(0x0020, value)), 32, ID));
        byte[] packet = response(0x0101, ID, attribute(0x0020, value));
        assertNull("cut short", Stun.mapped(packet, 19, ID));
        assertNull("no address", Stun.mapped(packet, 20, ID));
    }

    private static byte[] response(int type, byte[] id, byte[] attributes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(type >> 8);
        out.write(type);
        out.write(attributes.length >> 8);
        out.write(attributes.length);
        out.write(0x21);
        out.write(0x12);
        out.write(0xA4);
        out.write(0x42);
        out.write(id, 0, 12);
        out.write(attributes, 0, attributes.length);
        return out.toByteArray();
    }

    private static byte[] attribute(int type, byte[] value) {
        int padded = (value.length + 3) / 4 * 4;
        byte[] a = new byte[4 + padded];
        a[0] = (byte) (type >> 8);
        a[1] = (byte) type;
        a[2] = (byte) (value.length >> 8);
        a[3] = (byte) value.length;
        System.arraycopy(value, 0, a, 4, value.length);
        return a;
    }

    private static byte[] plain(InetAddress address) {
        byte[] ip = address.getAddress();
        byte[] v = new byte[4 + ip.length];
        v[1] = (byte) (ip.length == 4 ? 1 : 2);
        v[2] = 0x12;
        v[3] = 0x34;
        System.arraycopy(ip, 0, v, 4, ip.length);
        return v;
    }

    private static byte[] xored(InetAddress address, byte[] id) {
        byte[] v = plain(address);
        byte[] key = new byte[16];
        key[0] = 0x21;
        key[1] = 0x12;
        key[2] = (byte) 0xA4;
        key[3] = 0x42;
        System.arraycopy(id, 0, key, 4, 12);
        for (int i = 4; i < v.length; i++) {
            v[i] ^= key[i - 4];
        }
        return v;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] c = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, c, a.length, b.length);
        return c;
    }
}
