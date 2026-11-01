package com.fanok.audiobooks.region;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A STUN Binding request (RFC 8489): the public address a server sees this device's UDP come from. Plain UDP, so it
 * passes where web services are blocked and never takes the app's HTTP proxy.
 */
final class Stun {

    static final int MAGIC = 0x2112A442;
    private static final int BINDING_REQUEST = 0x0001;
    private static final int BINDING_SUCCESS = 0x0101;
    private static final int MAPPED_ADDRESS = 0x0001;
    private static final int XOR_MAPPED_ADDRESS = 0x0020;
    /** The XOR-MAPPED-ADDRESS of the RFC 3489 drafts, which some servers still send. */
    private static final int XOR_MAPPED_ADDRESS_OLD = 0x8020;
    private static final int TRIES = 2;

    private Stun() {
    }

    /** The address {@code host}:{@code port} sees, asked over IPv4 when the host has an IPv4 address. */
    static InetAddress ask(String host, int port, int timeoutMs) throws IOException {
        InetAddress server = ipv4First(InetAddress.getAllByName(host));
        byte[] transaction = new byte[12];
        ThreadLocalRandom.current().nextBytes(transaction);
        byte[] request = request(transaction);
        byte[] buffer = new byte[548];
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(timeoutMs);
            InetSocketAddress to = new InetSocketAddress(server, port);
            for (int i = 0; i < TRIES; i++) {
                socket.send(new DatagramPacket(request, request.length, to));
                try {
                    while (true) {
                        DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
                        socket.receive(reply);
                        InetAddress mapped = reply.getAddress().equals(server)
                                ? mapped(buffer, reply.getLength(), transaction) : null;
                        if (mapped != null) {
                            return mapped;
                        }
                    }
                } catch (SocketTimeoutException again) {
                    // the request or its answer was lost: once more
                }
            }
        }
        throw new SocketTimeoutException("no STUN answer from " + host);
    }

    private static InetAddress ipv4First(InetAddress[] all) throws UnknownHostException {
        for (InetAddress address : all) {
            if (address instanceof Inet4Address) {
                return address;
            }
        }
        if (all.length == 0) {
            throw new UnknownHostException();
        }
        return all[0];
    }

    /** A Binding request with the 12-byte {@code transaction} id and no attributes. */
    static byte[] request(byte[] transaction) {
        byte[] packet = new byte[20];
        packet[1] = BINDING_REQUEST;
        putInt(packet, 4, MAGIC);
        System.arraycopy(transaction, 0, packet, 8, 12);
        return packet;
    }

    /**
     * The address in {@code length} bytes of {@code packet} when they are a Binding success for {@code transaction}:
     * its XOR-MAPPED-ADDRESS, else its MAPPED-ADDRESS; null for anything else.
     */
    static InetAddress mapped(byte[] packet, int length, byte[] transaction) {
        if (length < 20 || unsigned16(packet, 0) != BINDING_SUCCESS || getInt(packet, 4) != MAGIC) {
            return null;
        }
        for (int i = 0; i < 12; i++) {
            if (packet[8 + i] != transaction[i]) {
                return null;
            }
        }
        int end = Math.min(length, 20 + unsigned16(packet, 2));
        InetAddress plain = null;
        for (int at = 20; at + 4 <= end; ) {
            int type = unsigned16(packet, at);
            int size = unsigned16(packet, at + 2);
            int value = at + 4;
            if (value + size > end) {
                break;
            }
            if (type == XOR_MAPPED_ADDRESS || type == XOR_MAPPED_ADDRESS_OLD) {
                InetAddress xored = address(packet, value, size, true);
                if (xored != null) {
                    return xored;
                }
            } else if (type == MAPPED_ADDRESS && plain == null) {
                plain = address(packet, value, size, false);
            }
            at = value + (size + 3) / 4 * 4;
        }
        return plain;
    }

    /** An address attribute: a zero byte, the family (1 IPv4, 2 IPv6), the port, the address. */
    private static InetAddress address(byte[] packet, int at, int size, boolean xored) {
        int family = packet[at + 1];
        int bytes = family == 1 ? 4 : family == 2 ? 16 : 0;
        if (bytes == 0 || size < 4 + bytes) {
            return null;
        }
        byte[] address = new byte[bytes];
        for (int i = 0; i < bytes; i++) {
            // XOR with the magic cookie, then (IPv6) the transaction id: bytes 4..19 of the header
            address[i] = (byte) (packet[at + 4 + i] ^ (xored ? packet[4 + i] : 0));
        }
        try {
            return InetAddress.getByAddress(address);
        } catch (UnknownHostException impossible) {
            return null;
        }
    }

    private static int unsigned16(byte[] b, int at) {
        return (b[at] & 0xFF) << 8 | (b[at + 1] & 0xFF);
    }

    private static int getInt(byte[] b, int at) {
        return (b[at] & 0xFF) << 24 | (b[at + 1] & 0xFF) << 16 | (b[at + 2] & 0xFF) << 8 | (b[at + 3] & 0xFF);
    }

    private static void putInt(byte[] b, int at, int value) {
        b[at] = (byte) (value >>> 24);
        b[at + 1] = (byte) (value >>> 16);
        b[at + 2] = (byte) (value >>> 8);
        b[at + 3] = (byte) value;
    }
}
