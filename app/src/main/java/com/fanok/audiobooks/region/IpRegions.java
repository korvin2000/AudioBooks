package com.fanok.audiobooks.region;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Arrays;

/**
 * The app's own IP-to-region table, {@link #ASSET}, built by tools/region/build_ip_regions.py from DB-IP's free
 * country data: the region of a public address the app learnt without a service that knows countries. IPv4 in /24 and
 * IPv6 in /48 blocks, about 100 KB in the IPRT format (tools/region/iprt.md).
 *
 * <p>The table is never loaded: {@link #open} reads its resident part (about 3 KB: per family the prior
 * probabilities and the cell tree), and a lookup descends the cell tree in memory, then either answers at once (a
 * uniform cell) or reads one block of at most 512 bytes and decodes it up to the address. A block is the range-coded
 * preorder walk of the minimal binary trie of the region map: a split flag per node and a label per leaf, with
 * adaptive binary contexts that restart at the block's trained priors. The walk keeps no stack, because after a leaf
 * ending at x comes the largest aligned block starting at x. Only public addresses are answered right.
 */
final class IpRegions implements RegionCheck.AddressTable {

    static final String ASSET = "region/ip-regions.bin";

    /** Positional reads of the table, e.g. the asset's FileChannel or a RandomAccessFile. */
    interface Source {
        void read(long position, byte[] buffer, int offset, int length) throws IOException;
    }

    private static final int HEADER = 16;
    private static final int PMIN = 32;
    private static final int PMAX = 65504;
    /** P(1) * 65536 of the 16 prior levels (logistic steps of 0.75). */
    private static final int[] LEVELS = {236, 497, 1042, 2168, 4427, 8714, 16062, 26695, 38841, 49474, 56822, 61109,
            63368, 64494, 65039, 65300};
    /** The previous label's context class: other, Russia or Ukraine, west. */
    private static final int[] PCLASS = {0, 1, 1, 2};
    /** After label r, the three others, likeliest first: CHOICES[3 * r + k]. */
    private static final int[] CHOICES = {3, 1, 2, 3, 0, 2, 3, 0, 1, 0, 1, 2};
    /** A cell-tree entry with this bit is a cell; else it is the index of the node's right child. */
    private static final char CELL = 0x8000;
    /** A cell with this bit is uniform (its region in the low bits); else the low bits are its block's index. */
    private static final char UNIFORM = 0x4000;

    private final Source source;
    private final Family v4;
    private final Family v6;
    /** The month of the DB-IP data. */
    final int year;
    final int month;

    private IpRegions(Source source, Family v4, Family v6, int year, int month) {
        this.source = source;
        this.v4 = v4;
        this.v6 = v6;
        this.year = year;
        this.month = month;
    }

    /** Reads the header and the resident part ({@code length}: the table's size); a bad table is an IOException. */
    static IpRegions open(Source source, long length) throws IOException {
        if (length < HEADER) {
            throw new IOException("not an IP region table");
        }
        byte[] head = new byte[HEADER];
        source.read(0, head, 0, HEADER);
        if (head[0] != 'I' || head[1] != 'P' || head[2] != 'R' || head[3] != 'T' || head[4] != 2 || head[5] != 'T') {
            throw new IOException("not an IP region table");
        }
        long resident = (head[12] & 0xFFL) << 24 | (head[13] & 0xFF) << 16 | (head[14] & 0xFF) << 8 | head[15] & 0xFF;
        if (resident < HEADER || resident > Math.min(length, 1 << 20)) {
            throw damaged();
        }
        byte[] body = new byte[(int) resident - HEADER];
        source.read(HEADER, body, 0, body.length);
        try {
            Cursor in = new Cursor(body);
            Family v4 = new Family(24, 0L, 0, in, (int) resident);
            Family v6 = new Family(48, 0x200000000000L, 3, in, v4.dataEnd);
            if (in.pos != body.length || v6.dataEnd != length) {
                throw damaged();
            }
            return new IpRegions(source, v4, v6, (head[6] & 0xFF) << 8 | head[7] & 0xFF, head[8] & 0xFF);
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new IOException("a damaged IP region table", e);
        }
    }

    /** The region of {@code address} when it is a public one; null for any other ({@link #isPublic}) or a failed read. */
    @Override
    public Region of(InetAddress address) {
        if (!isPublic(address)) {
            return null;
        }
        byte[] a = address.getAddress();
        try {
            if (a.length == 4) {
                return Region.values()[v4.region(source, (a[0] & 0xFF) << 16 | (a[1] & 0xFF) << 8 | a[2] & 0xFF)];
            }
            long key = 0;
            for (int i = 0; i < 6; i++) {
                key = key << 8 | (a[i] & 0xFF);
            }
            return Region.values()[v6.region(source, key)];
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * True for an address that can be the connection's on the internet: not private, shared (CGNAT 100.64/10),
     * loopback, link-local, multicast, reserved or for documentation; for IPv6 a global unicast one (2000::/3)
     * other than Teredo, 6to4 and documentation.
     */
    static boolean isPublic(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] a = address.getAddress();
        int b0 = a[0] & 0xFF;
        int b1 = a[1] & 0xFF;
        int b2 = a[2] & 0xFF;
        if (a.length == 4) {
            return b0 != 0 && b0 < 224
                    && !(b0 == 100 && (b1 & 0xC0) == 64)
                    && !(b0 == 192 && b1 == 0 && (b2 == 0 || b2 == 2))
                    && !(b0 == 198 && (b1 & 0xFE) == 18)
                    && !(b0 == 198 && b1 == 51 && b2 == 100)
                    && !(b0 == 203 && b1 == 0 && b2 == 113);
        }
        int b3 = a[3] & 0xFF;
        return (b0 & 0xE0) == 0x20
                && !(b0 == 0x20 && b1 == 0x01 && b2 == 0 && b3 == 0)
                && !(b0 == 0x20 && b1 == 0x01 && b2 == 0x0D && b3 == 0xB8)
                && !(b0 == 0x20 && b1 == 0x02);
    }

    private static IOException damaged() {
        return new IOException("a damaged IP region table");
    }

    /** One address family: its key space, coding parameters, cell tree and blocks. */
    private static final class Family {
        final int bits;
        final int topd;
        final long top;
        final long end;
        final int same;
        final int choice;
        final int contexts;
        /** The prior level of each context, two to a byte (the even context in the low half). */
        final byte[] priors;
        final int[] rate;
        /** The cell tree in preorder; an internal node's left child is the next entry. */
        final char[] tree;
        /** Block b is the bytes [blockOff[b], blockOff[b + 1]) of the table. */
        final int[] blockOff;
        /** The walk state before a block's first leaf: lastd | prev << 6 | prev2 << 8. */
        final char[] blockState;
        final int dataEnd;
        private final int maxBlock;
        private int node;
        private int block;
        private int bit;

        Family(int bits, long top, int topd, Cursor in, int dataStart) throws IOException {
            this.bits = bits;
            this.top = top;
            this.topd = topd;
            end = top + (1L << (bits - topd));
            same = (bits - topd) * 24;
            choice = same + (bits - topd + 1) * 24;
            contexts = choice + 32;
            maxBlock = in.u16();
            int cells = in.u16();
            int blocks = in.u16();
            int n0 = in.u8();
            int shift = in.u8();
            int priorsLength = in.u16();
            int treeLength = in.u16();
            int tableLength = in.u16();
            long dataLength = in.u32();
            if (cells == 0 || cells > UNIFORM || blocks > cells || shift > 15 || treeLength * 8 < 2 * cells - 1
                    || dataLength > Integer.MAX_VALUE - dataStart) {
                throw damaged();
            }
            byte[] flat = new byte[8];
            Arrays.fill(flat, (byte) 0x88);
            Coder c = new Coder(in.data, in.pos, in.pos + priorsLength, flat, rates(0, 4), 16);
            priors = new byte[(contexts + 1) / 2];
            for (int i = 0, level = 8; i < contexts; i++) {
                if (c.bit(0) == 0) {
                    int n = 1;
                    while (n < 16) {
                        n = n * 2 + c.bit(n);
                    }
                    level = n - 16;
                }
                priors[i >> 1] |= (byte) (level << ((i & 1) << 2));
            }
            in.pos += priorsLength;
            rate = rates(n0, shift);
            tree = new char[2 * cells - 1];
            blockOff = new int[blocks + 1];
            blockState = new char[blocks];
            blockOff[0] = dataStart;
            bit = in.pos * 8;
            Cursor table = new Cursor(in.data);
            table.pos = in.pos + treeLength;
            parse(in.data, table, topd);
            dataEnd = dataStart + (int) dataLength;
            if (node != tree.length || block != blocks || table.pos != in.pos + treeLength + tableLength
                    || blockOff[blocks] != dataEnd) {
                throw damaged();
            }
            in.pos = table.pos;
        }

        /** Reads the cell tree's preorder bits (1 = cut in two, 0 = a cell) and each cell's entry. */
        private void parse(byte[] data, Cursor table, int depth) throws IOException {
            if (node == tree.length) {
                throw damaged();
            }
            int i = node++;
            if ((data[bit >> 3] >> (7 - (bit++ & 7)) & 1) == 1) {
                if (depth >= bits) {
                    throw damaged();
                }
                parse(data, table, depth + 1);
                tree[i] = (char) node;
                parse(data, table, depth + 1);
                return;
            }
            long v = table.varint();
            if (v < 4) {
                tree[i] = (char) (CELL | UNIFORM | v);
                return;
            }
            if (block == blockState.length || v - 4 > maxBlock) {
                throw damaged();
            }
            blockOff[block + 1] = blockOff[block] + maxBlock - (int) (v - 4);
            blockState[block] = (char) table.varint();
            tree[i] = (char) (CELL | block++);
        }

        int region(Source source, long key) throws IOException {
            if (key < top || key >= end) {
                return 0; // outside 2000::/3: never public
            }
            int i = 0;
            int d = topd;
            long lo = top;
            char t;
            while ((t = tree[i]) < CELL) {
                long half = 1L << (bits - d - 1);
                if (key - lo >= half) {
                    lo += half;
                    i = t;
                } else {
                    i++;
                }
                d++;
            }
            if ((t & UNIFORM) != 0) {
                return t & 3;
            }
            int b = t & (UNIFORM - 1);
            int n = blockOff[b + 1] - blockOff[b];
            byte[] data = new byte[n];
            source.read(blockOff[b], data, 0, n);
            return walk(new Coder(data, 0, n, priors, rate, contexts), lo, d + 1, 1, blockState[b], key);
        }

        /** Decodes the trie walk from the node (x, d) (lc: a left child) up to the leaf holding key. */
        private int walk(Coder c, long x, int d, int lc, int state, long key) {
            int lastd = state & 63;
            int prev = state >> 6 & 3;
            int prev2 = state >> 8 & 3;
            while (true) {
                while (d < bits && c.bit(context(d, lc, lastd, prev)) == 1) {
                    d++;
                    lc = 1;
                }
                // a right child after a leaf sibling must differ from it: no 'same' flag
                if (lc == 0 && lastd == d || c.bit(same + context(d, lc, lastd, prev)) == 0) {
                    int ctx = choice + prev * 4 + prev2;
                    int label = CHOICES[prev * 3 + (c.bit(ctx) == 1 ? 0 : c.bit(ctx + 16) == 1 ? 1 : 2)];
                    prev2 = prev;
                    prev = label;
                }
                x += 1L << (bits - d);
                if (key < x) {
                    return prev;
                }
                lastd = d;
                d = Math.max(bits - Math.min(Long.numberOfTrailingZeros(x), bits), topd);
                lc = 0;
            }
        }

        /** The split context of a node at depth d; add {@code same} for its label's. */
        private int context(int d, int lc, int lastd, int prev) {
            int ld = lastd - d;
            ld = ld < 0 ? 0 : ld > 2 ? 3 : ld + 1;
            return ((d - topd) * 2 + lc) * 12 + ld * 3 + PCLASS[prev];
        }
    }

    /** Adaptation rates by update count (15-bit fixed point): 1 / (n + n0 + 1.5), never below 2^-shift. */
    private static int[] rates(int n0, int shift) {
        int floor = 32768 >> shift;
        int n = 0;
        while (65536 / (2 * (n + n0) + 3) > floor) {
            n++;
        }
        int[] rate = new int[n + 1];
        for (int i = 0; i < n; i++) {
            rate[i] = 65536 / (2 * (i + n0) + 3);
        }
        rate[n] = floor;
        return rate;
    }

    /** Binary range decoder (LZMA-style, 32-bit) with an adaptive probability per context. */
    private static final class Coder {
        private final byte[] data;
        private final int end;
        private int pos;
        private long range = 0xFFFFFFFFL;
        private long code;
        private final byte[] priors;
        private final int[] rate;
        /** P(1) << 8 | update count per context; 0 while still at the prior. */
        private final int[] state;

        Coder(byte[] data, int pos, int end, byte[] priors, int[] rate, int contexts) {
            this.data = data;
            this.pos = pos;
            this.end = end;
            this.priors = priors;
            this.rate = rate;
            state = new int[contexts];
            for (int i = 0; i < 4; i++) {
                code = code << 8 | next();
            }
        }

        private int next() {
            return pos < end ? data[pos++] & 0xFF : 0;
        }

        int bit(int ctx) {
            int s = state[ctx];
            int p = s == 0 ? LEVELS[priors[ctx >> 1] >> (ctx << 2 & 4) & 15] : s >>> 8;
            int n = s & 0xFF;
            long bound = (range >>> 16) * p;
            int b;
            if (code < bound) {
                range = bound;
                b = 1;
            } else {
                code -= bound;
                range -= bound;
                b = 0;
            }
            p += ((b << 16) - p) * rate[n] >> 15;
            while (range < 0x1000000L) {
                range <<= 8;
                code = code << 8 | next();
            }
            state[ctx] = (p > PMAX ? PMAX : p < PMIN ? PMIN : p) << 8 | (n + 1 < rate.length ? n + 1 : n);
            return b;
        }
    }

    /** Big-endian fields and varints of the resident part. */
    private static final class Cursor {
        final byte[] data;
        int pos;

        Cursor(byte[] data) {
            this.data = data;
        }

        int u8() {
            return data[pos++] & 0xFF;
        }

        int u16() {
            return u8() << 8 | u8();
        }

        long u32() {
            return (long) u16() << 16 | u16();
        }

        long varint() throws IOException {
            long n = 0;
            for (int shift = 0; shift < 63; shift += 7) {
                int b = u8();
                n |= (long) (b & 0x7F) << shift;
                if (b < 0x80) {
                    return n;
                }
            }
            throw damaged();
        }
    }
}
