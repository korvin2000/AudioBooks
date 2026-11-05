package com.fanok.audiobooks.search;

import com.fanok.audiobooks.App;
import com.fanok.audiobooks.pojo.GenrePOJO;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The built-in Авторы / Исполнители lists of the sites without a people index of their own:
 * assets/people/people.bin, format PPL1, written (and described in full) by tools/people/build_people.py. One
 * dictionary holds the names of all lists; each list is a stream of references into it with the book counts and how
 * to make each url. A list is read whole: the dictionary, then that list's stream (and the names of its ref list, if
 * it has one), all range-coded with adaptive bits (class Rc, the twin of the builder's). The url rules (pct, slug,
 * rotate and the lower case) must stay in step with the builder's. A list() takes tens of milliseconds and a few MB:
 * call it off the main thread. Thread-safe; nothing is cached.
 */
public final class PeopleIndex {

    /** A site's drawer URL of its built-in list is {@code SERVER + AUTHORS} or {@code SERVER + READERS}. */
    public static final String AUTHORS = "/#authors", READERS = "/#readers";

    static final String ASSET = "people/people.bin";

    private static final int TEXT = 0, PCT = 1, PCT_LOWER = 2, SLUG = 3, INT = 4, UUID = 5;

    /** States of one Rc.num() model. */
    private static final int NUM = 33 + 16 * 33;

    private static final String HEX = "0123456789abcdef", UPPER_HEX = "0123456789ABCDEF";

    private final byte[] d;

    private final int words, names, classes, slots, dictionary;

    private final char[] alphabet;

    private final int[] child, rank;

    private final char[][] tableChars;

    private final String[][] tableTexts;

    private final String[] rows, prefixes, suffixes;

    private final int[] kinds, rules, params, refs, entries, offsets;

    private final long[] bases;

    public static boolean isBuiltIn(String url) {
        return url != null && (url.endsWith(AUTHORS) || url.endsWith(READERS));
    }

    /** The built-in list at {@code url} ({@link #isBuiltIn}), most books first: the people lists' "popular" order. */
    public static ProviderPage<GenrePOJO> load(String url) throws IOException {
        Provider site = Provider.require(url);
        try (InputStream in = App.getInstance().getAssets().open(ASSET)) {
            List<GenrePOJO> people = of(in).list(site.name(), url.endsWith(READERS));
            Collections.sort(people, (a, b) -> b.getReting() - a.getReting()); // stable: then by name
            return ProviderPage.of(people, false);
        }
    }

    public static PeopleIndex of(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 18);
        byte[] buf = new byte[1 << 16];
        for (int n; (n = in.read(buf)) > 0; ) {
            out.write(buf, 0, n);
        }
        return of(out.toByteArray());
    }

    public static PeopleIndex of(byte[] file) {
        if (file.length < 4 || file[0] != 'P' || file[1] != 'P' || file[2] != 'L' || file[3] != '1') {
            throw new IllegalArgumentException("not a PPL1 people index");
        }
        return new PeopleIndex(file);
    }

    private PeopleIndex(byte[] d) {
        this.d = d;
        int[] at = {4};
        words = varint(at);
        names = varint(at);
        alphabet = new char[varint(at)];
        for (int i = 0, c = 0; i < alphabet.length; i++) {
            alphabet[i] = (char) (c += varint(at));
        }
        int[] lengths = new int[alphabet.length + 1];
        for (int i = 0; i < lengths.length; i++) {
            lengths[i] = d[at[0]++];
        }
        // the canonical code of the lengths as a tree: child[2 * node + bit] = an inner node (> 0) or ~symbol
        Integer[] syms = new Integer[lengths.length];
        for (int s = 0; s < syms.length; s++) {
            syms[s] = s;
        }
        Arrays.sort(syms, (a, b) -> lengths[a] != lengths[b] ? lengths[a] - lengths[b] : a - b);
        child = new int[2 * syms.length];
        rank = new int[syms.length];
        for (int i = 0, code = 0, n = lengths[syms[0]], nodes = 1; i < syms.length; i++, code++) {
            int s = syms[i], node = 0;
            code <<= lengths[s] - n;
            n = lengths[s];
            for (int j = n - 1; j > 0; j--) {
                int branch = 2 * node + (code >> j & 1);
                if (child[branch] == 0) {
                    child[branch] = nodes++;
                }
                node = child[branch];
            }
            child[2 * node + (code & 1)] = ~s;
            rank[s] = i;
        }
        classes = varint(at);
        slots = varint(at);
        tableChars = new char[varint(at)][];
        tableTexts = new String[tableChars.length][];
        for (int t = 0; t < tableChars.length; t++) {
            int n = varint(at);
            tableChars[t] = new char[n];
            tableTexts[t] = new String[n];
            for (int i = 0; i < n; i++) {
                tableChars[t][i] = (char) varint(at);
                tableTexts[t][i] = string(at);
            }
        }
        int m = varint(at);
        rows = new String[m];
        prefixes = new String[m];
        suffixes = new String[m];
        kinds = new int[m];
        rules = new int[m];
        params = new int[m];
        refs = new int[m];
        entries = new int[m];
        offsets = new int[m + 1];
        bases = new long[m];
        for (int i = 0; i < m; i++) {
            String row = i > 0 ? rows[i - 1] : "", prefix = i > 0 ? prefixes[i - 1] : "";
            rows[i] = row.substring(0, varint(at)) + string(at);    // each after what it shares with the list before
            prefixes[i] = prefix.substring(0, varint(at)) + string(at);
            kinds[i] = d[at[0]++];
            rules[i] = d[at[0]++];
            params[i] = varint(at);
            bases[i] = varint(at);
            refs[i] = varint(at) - 1;
            suffixes[i] = string(at);
            entries[i] = varint(at);
            offsets[i + 1] = offsets[i] + varint(at);
        }
        int size = varint(at);
        dictionary = at[0];
        for (int i = 0; i <= m; i++) {
            offsets[i] += dictionary + size;
        }
    }

    public boolean has(String row, boolean readers) {
        return find(row, readers) >= 0;
    }

    /** The list in the site's order (Russian names first), each person's book count as reting when known. */
    public List<GenrePOJO> list(String row, boolean readers) {
        int l = find(row, readers);
        if (l < 0) {
            return new ArrayList<>();
        }
        // the dictionary: words (chars in one buffer), their counts as non-first words, then names as word ids
        Rc rc = new Rc(d, dictionary);
        int nodes = alphabet.length;
        int[] slot = new int[classes * classes], chars = states(slots * nodes);
        int[] pre = states(4 * NUM), freq = states(NUM);
        Arrays.fill(slot, -1);
        char[] wc = new char[words * 8 + 256];
        int[] ws = new int[words + 1], cum = new int[words + 1];
        for (int i = 0, used = 0, p = 0; i < words; i++) {
            int start = ws[i];
            p = rc.num(pre, NUM * Math.min(p, 3));
            if (wc.length < start + p + 256) {
                wc = Arrays.copyOf(wc, wc.length * 2);
            }
            System.arraycopy(wc, i > 0 ? ws[i - 1] : 0, wc, start, p);
            int end = start + p, c1 = p > 0 ? sym(wc[end - 1]) : 0, c2 = p > 1 ? sym(wc[end - 2]) : 0;
            for (int x; ; c2 = c1, c1 = ~x) {               // each char down the code tree, one adaptive bit per node
                int key = Math.min(rank[c1], classes - 1) * classes + Math.min(rank[c2], classes - 1);
                if (slot[key] < 0) {
                    slot[key] = used++;
                }
                int base = slot[key] * nodes, node = 0;
                while ((x = child[2 * node + rc.bit(chars, base + node)]) > 0) {
                    node = x;
                }
                if (x == ~0) {
                    break;
                }
                if (end == wc.length) {
                    wc = Arrays.copyOf(wc, wc.length * 2);
                }
                wc[end++] = alphabet[~x - 1];
            }
            ws[i + 1] = end;
            cum[i + 1] = cum[i] + rc.num(freq, 0);
        }
        int[] size = states(4 * NUM), step = states(2 * NUM), back = states(1);
        int[] ns = new int[names + 1], nw = new int[names * 2 + 16];
        for (int i = 0, k = 1, dw = 0, w0 = 0, at = 0; i < names; i++) {
            k = rc.num(size, NUM * Math.min(k - 1, 3)) + 1;
            int neg = rc.bit(back, 0);
            dw = rc.num(step, dw == 0 ? NUM : 0);
            w0 = neg == 1 ? w0 - dw : w0 + dw;
            if (nw.length < at + k) {
                nw = Arrays.copyOf(nw, Math.max(nw.length * 2, at + k));
            }
            nw[at++] = w0;
            for (int j = 1; j < k; j++) {
                nw[at++] = rc.freq(cum);
            }
            ns[i + 1] = at;
        }
        // the list: each entry's name and books, then its url
        int[] ids = new int[entries[l]], books = new int[ids.length];
        rc = members(l, ids, books);
        int rule = rules[l], param = params[l];
        int[] ops = states(4), text = null;
        List<GenrePOJO> out = new ArrayList<>(ids.length);
        StringBuilder sb = new StringBuilder(256);
        for (int i = 0; i < ids.length; i++) {
            int op = rc.tree(ops, 2, 0), id = ids[i], b = books[i];
            sb.setLength(0);
            for (int j = ns[id]; j < ns[id + 1]; j++) {
                int w = nw[j];
                sb.append(wc, ws[w], ws[w + 1] - ws[w]).append(' ');
            }
            sb.setLength(sb.length() - 1);
            String name = sb.toString(), key = null;
            if (op >= 2 || rule == TEXT) {
                if (text == null) {
                    text = states(NUM + 256 * 256);
                }
                key = rc.text(text);
            }
            sb.setLength(0);
            if (op == 3) {
                sb.append(key);
            } else {
                sb.append(prefixes[l]);
                String src = op == 1 ? rotate(name) : name;
                if (key != null) {
                    if (rule == PCT || rule == PCT_LOWER) {
                        pct(sb, key, false);
                    } else {
                        sb.append(key);
                    }
                } else if (rule == PCT || rule == PCT_LOWER) {
                    pct(sb, src, rule == PCT_LOWER);
                } else if (rule == SLUG) {
                    slug(sb, src, param);
                } else if (rule == INT) {
                    sb.append(bases[l] + rc.raw(param));
                } else {
                    uuid(sb, rc, param);
                }
                sb.append(suffixes[l]);
            }
            GenrePOJO p = new GenrePOJO(name, sb.toString());
            if (b > 0) {
                p.setReting(b);
            }
            out.add(p);
        }
        return out;
    }

    /**
     * Reads each entry's name id and books: with a ref list, whether the name is the ref's next one, else a step from
     * the name before (0 = back, then how far back); books, for a name of the ref whether they are the ref's.
     * Returns the list's decoder, at its urls.
     */
    private Rc members(int l, int[] ids, int[] books) {
        int[] has = null, keys = {};
        if (refs[l] >= 0) {                              // the ref's names (sorted, as keys) and their books
            int r = refs[l], n = 0;
            int[] ri = new int[entries[r]], rb = new int[ri.length];
            members(r, ri, rb);
            has = new int[names];
            Arrays.fill(has, -1);
            for (int i = 0; i < ri.length; i++) {
                has[ri[i]] = rb[i];
            }
            keys = new int[ri.length];
            for (int x = 0; x < names; x++) {
                if (has[x] >= 0) {
                    keys[n++] = x;
                }
            }
            keys = Arrays.copyOf(keys, n);
        }
        Rc rc = new Rc(d, offsets[l]);
        int[] step = states(2 * NUM), num = states(3 * NUM), flags = states(2);
        for (int i = 0, id = -1; i < ids.length; i++) {
            int j = Arrays.binarySearch(keys, id + 1);
            if (j < 0) {
                j = -j - 1;
            }
            if (j < keys.length && rc.bit(flags, 0) == 1) {
                id = keys[j];
            } else {
                int fwd = rc.num(step, 0);
                id = fwd > 0 ? id + fwd : id - rc.num(step, NUM);
            }
            ids[i] = id;
            books[i] = has != null && has[id] >= 0 ? rc.bit(flags, 1) == 1 ? has[id] : rc.num(num, NUM)
                    : rc.num(num, has != null ? 2 * NUM : 0);
        }
        return rc;
    }

    private int find(String row, boolean readers) {
        for (int i = 0; i < rows.length; i++) {
            if (rows[i].equals(row) && kinds[i] == (readers ? 1 : 0)) {
                return i;
            }
        }
        return -1;
    }

    private int sym(char c) {
        return Arrays.binarySearch(alphabet, c) + 1;
    }

    // ------------------------------------------------------------------------------------------------ url rules

    /** Character.toLowerCase of the chars below U+0500 but U+0130 (which the builder's Python lowers to two). */
    private static char low(char c) {
        return c < 0x500 && c != 0x130 ? Character.toLowerCase(c) : c;
    }

    /** Appends s (lower-cased) as percent-encoded UTF-8: all but A-Z a-z 0-9 - . _ ~. */
    private static void pct(StringBuilder sb, String s, boolean lower) {
        for (int i = 0; i < s.length(); i++) {
            int c = lower ? low(s.charAt(i)) : s.charAt(i);
            if (c < 0x80) {
                if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || "-._~".indexOf(c) >= 0) {
                    sb.append((char) c);
                } else {
                    hex(sb, c);
                }
            } else if (c < 0x800) {
                hex(sb, 0xC0 | c >> 6);
                hex(sb, 0x80 | c & 63);
            } else {
                if (Character.isHighSurrogate((char) c) && i + 1 < s.length()) {
                    c = Character.toCodePoint((char) c, s.charAt(++i));
                    hex(sb, 0xF0 | c >> 18);
                    hex(sb, 0x80 | c >> 12 & 63);
                } else {
                    hex(sb, 0xE0 | c >> 12);
                }
                hex(sb, 0x80 | c >> 6 & 63);
                hex(sb, 0x80 | c & 63);
            }
        }
    }

    private static void hex(StringBuilder sb, int b) {
        sb.append('%').append(UPPER_HEX.charAt(b >> 4)).append(UPPER_HEX.charAt(b & 15));
    }

    /** Appends the slug: lower case, each char by the table (else ASCII letters and digits as they are, the rest '-'),
     * one '-' between parts. */
    private void slug(StringBuilder sb, String s, int table) {
        boolean dash = false;
        for (int i = 0, start = sb.length(); i < s.length(); i++) {
            char c = low(s.charAt(i));
            int t = Arrays.binarySearch(tableChars[table], c);
            String x = t >= 0 ? tableTexts[table][t] : null;
            if (x == null ? !(c >= 'a' && c <= 'z' || c >= '0' && c <= '9') : x.equals("-")) {
                dash = true;
            } else if (x == null || !x.isEmpty()) {
                if (dash && sb.length() > start) {
                    sb.append('-');
                }
                dash = false;
                if (x == null) {
                    sb.append(c);
                } else {
                    sb.append(x);
                }
            }
        }
    }

    /** The name with its last word first. */
    private static String rotate(String name) {
        int i = name.lastIndexOf(' ');
        return i < 0 ? name : name.substring(i + 1) + ' ' + name.substring(0, i);
    }

    /** Appends a UUID of 128 raw bits, or of a version 4 one's 122 free bits (15 digits, 2 variant bits, 15 digits). */
    private static void uuid(StringBuilder sb, Rc rc, int bits) {
        char[] h = new char[32];
        if (bits == 122) {
            long a = rc.raw(60), v = rc.raw(2), b = rc.raw(60);
            for (int i = 0; i < 15; i++) {
                h[i < 12 ? i : i + 1] = HEX.charAt((int) (a >>> 56 - 4 * i) & 15);
                h[17 + i] = HEX.charAt((int) (b >>> 56 - 4 * i) & 15);
            }
            h[12] = '4';
            h[16] = HEX.charAt(8 | (int) v);
        } else {
            for (int half = 0; half < 2; half++) {
                long v = rc.raw(64);
                for (int i = 0; i < 16; i++) {
                    h[16 * half + i] = HEX.charAt((int) (v >>> 60 - 4 * i) & 15);
                }
            }
        }
        sb.append(h, 0, 8).append('-').append(h, 8, 4).append('-').append(h, 12, 4).append('-').append(h, 16, 4)
                .append('-').append(h, 20, 12);
    }

    // ------------------------------------------------------------------------------------------------ bytes

    private int varint(int[] at) {
        int v = 0;
        for (int shift = 0; ; shift += 7) {
            int b = d[at[0]++];
            v |= (b & 0x7F) << shift;
            if (b >= 0) {
                return v;
            }
        }
    }

    private String string(int[] at) {
        int n = varint(at);
        at[0] += n;
        return new String(d, at[0] - n, n, StandardCharsets.UTF_8);
    }

    private static int[] states(int n) {
        int[] m = new int[n];
        Arrays.fill(m, Rc.HALF);
        return m;
    }

    /**
     * LZMA-style range decoder over adaptive bits. A state: the 16-bit probability of a 0 | its update count << 16;
     * the n-th update moves it by 1/(n + 1.5), from the LIMIT-th on by 1/(LIMIT + 1.5).
     */
    private static final class Rc {

        static final int LIMIT = 30, PMIN = 32, HALF = 32768;

        static final int[] RATE = new int[LIMIT + 1];

        static {
            for (int n = 0; n <= LIMIT; n++) {
                RATE[n] = 8192 / (2 * n + 3);
            }
        }

        private final byte[] d;

        private int pos;

        private long range = 0xFFFFFFFFL, code;

        Rc(byte[] d, int pos) {
            this.d = d;
            for (int i = 1; i < 5; i++) {
                code = code << 8 | d[pos + i] & 0xFF;
            }
            this.pos = pos + 5;
        }

        /** One bit, 0 with probability p / 65536. */
        private int code(int p) {
            long bound = (range >>> 16) * p;
            int b = code < bound ? 0 : 1;
            if (b == 0) {
                range = bound;
            } else {
                code -= bound;
                range -= bound;
            }
            normalize();
            return b;
        }

        private void normalize() {
            while (range < 1 << 24) {
                range <<= 8;
                code = code << 8 | (pos < d.length ? d[pos++] & 0xFF : 0);
            }
        }

        int bit(int[] m, int i) {
            int s = m[i], p = s & 0xFFFF, b = code(p);
            p += ((b == 0 ? 65536 : 0) - p) * RATE[s >>> 16] >> 12;
            m[i] = Math.min(Math.max(p, PMIN), 65536 - PMIN) | Math.min((s >>> 16) + 1, LIMIT) << 16;
            return b;
        }

        long raw(int n) {
            long r = 0;
            while (n-- > 0) {
                r = r << 1 | code(HALF);
            }
            return r;
        }

        /** v >= 0 as Elias gamma of v + 1 in m[o...]: the bit length in unary, the 4 bits below the leading one by a
         * bit tree per length, the lower bits raw. */
        int num(int[] m, int o) {
            int k = 0;
            while (bit(m, o + k) == 1) {
                k++;
            }
            int r = 1;
            for (int j = 0; j < k; j++) {
                r = r << 1 | (r < 16 ? bit(m, o + 33 + 16 * k + r) : code(HALF));
            }
            return r - 1;
        }

        /** An n-bit value along a bit tree in m[o + 1...]. */
        int tree(int[] m, int n, int o) {
            int r = 1;
            for (int j = 0; j < n; j++) {
                r = r << 1 | bit(m, o + r);
            }
            return r - (1 << n);
        }

        /** A symbol by static counts: cum[s] = the counts of the symbols below s. */
        int freq(int[] cum) {
            int tot = cum[cum.length - 1], lo = 0, hi = cum.length - 2;
            long r = range / tot, v = Math.min(code / r, tot - 1);
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (cum[mid] <= v) {
                    lo = mid;
                } else {
                    hi = mid - 1;
                }
            }
            code -= r * cum[lo];
            range = r * (cum[lo + 1] - cum[lo]);
            normalize();
            return lo;
        }

        /** UTF-8 text: its byte count, then each byte by a bit tree keyed by the byte before. */
        String text(int[] m) {
            byte[] b = new byte[num(m, 0)];
            for (int i = 0, prev = 0; i < b.length; i++) {
                b[i] = (byte) (prev = tree(m, 8, NUM + 256 * prev));
            }
            return new String(b, StandardCharsets.UTF_8);
        }
    }
}
