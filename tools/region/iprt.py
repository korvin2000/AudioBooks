"""IPRT, the format of the app's IP-region table: the encoder (build_family, write_file) of build_ip_regions.py and a
reference reader (Reader) it checks the file with.

Everything that reaches the file or decides a lookup is integer-exact; app/.../region/IpRegions.java mirrors it bit for
bit. iprt.md is the byte-level description. In short:
  * The key is the top 24 bits of an IPv4 address (a /24) or the top 48 bits of an IPv6 one (a /48); each family maps
    its keys onto 4 regions. Keys of non-public addresses are wildcards: any answer will do.
  * The map is the minimal binary trie over the key space (a node is a leaf when its public keys share a region).
    It is coded as its preorder walk, a split flag per node and a label per leaf, each flag one binary decision of an
    adaptive range coder. The walk needs no stack: the node after a leaf is the largest aligned block that starts
    where the leaf ends. Contexts: depth, left/right child, the depth of the previous leaf, the previous label (and
    the one before it, for a new label).
  * Cells: the key space is cut into aligned blocks (the leaves of a small 'cell tree', an adaptive radix table)
    until each cell is either uniform, answered from the resident index without any read, or holds a coded walk of
    at most 512 bytes and 8192 decisions. Cells with a uniform half of /12 or more are cut too. Each coded cell
    restarts the coder and the model; its probabilities start at trained priors and adapt within the cell.
  * A lookup descends the resident cell tree (O(depth)), reads one block, and decodes its walk up to the key.
"""
import bisect
import math
import struct

MAGIC = b'IPRT'
VERSION = 2
HEADER = 16
SECTION = 18
# P(bit = 1) * 65536 of the 16 prior levels: logistic steps of 0.75.
LEVELS = (236, 497, 1042, 2168, 4427, 8714, 16062, 26695, 38841, 49474, 56822, 61109, 63368, 64494, 65039, 65300)
PMIN, PMAX = 32, 65504
OTHER, RUSSIA, UKRAINE, WEST = 0, 1, 2, 3
PCLASS = (0, 1, 1, 2)  # previous label -> context class: other, Russia or Ukraine, west
CHOICES = ((3, 1, 2), (3, 0, 2), (3, 0, 1), (0, 1, 2))  # after a label, the three others, likeliest first

FAMILIES = {
    # family: (key bits, start of the coded node, its depth, wildcard key ranges [lo, hi))
    4: (24, 0, 0, ((0x000000, 0x010000), (0x0A0000, 0x0B0000), (0x644000, 0x648000), (0x7F0000, 0x800000),
                   (0xA9FE00, 0xA9FF00), (0xAC1000, 0xAC2000), (0xC0A800, 0xC0A900), (0xE00000, 0x1000000))),
    6: (48, 0x200000000000, 3, ((0, 0x200000000000), (0x400000000000, 1 << 48))),  # only 2000::/3 is coded
}
PARAMS = ((4, 4), (4, 5), (8, 4), (8, 5))  # (n0, shift) the encoder tries: prior weight, adaptation-rate floor


def ctz(x, cap):
    """Trailing zero bits of x, at most cap (cap for 0)."""
    return cap if x == 0 else min((x & -x).bit_length() - 1, cap)


# ---------------------------------------------------------------------------------------------------- range coder

class Encoder:
    """LZMA-style binary range coder: 32-bit range, carry propagation; the first output byte (always 0) is dropped."""

    def __init__(self):
        self.low, self.range, self.cache, self.pending, self.first = 0, 0xFFFFFFFF, 0, 1, True
        self.out = bytearray()

    def _shift(self):
        if self.low < 0xFF000000 or self.low > 0xFFFFFFFF:
            carry, byte = self.low >> 32, self.cache
            while True:
                if self.first:
                    self.first = False
                else:
                    self.out.append((byte + carry) & 0xFF)
                byte = 0xFF
                self.pending -= 1
                if self.pending == 0:
                    break
            self.cache = (self.low >> 24) & 0xFF
        self.pending += 1
        self.low = (self.low & 0x00FFFFFF) << 8

    def bit(self, p, b):
        """Codes bit b with P(b = 1) = p / 65536."""
        bound = (self.range >> 16) * p
        if b:
            self.range = bound
        else:
            self.low += bound
            self.range -= bound
        while self.range < 0x1000000:
            self.range <<= 8
            self._shift()

    def finish(self):
        """Ends with the value of the final interval that has the most trailing zero bits and drops trailing zero
        bytes: the reader reads zeros past the end."""
        hi = self.low + self.range - 1
        k = 32
        while (hi >> k) << k < self.low:
            k -= 1
        self.low = (hi >> k) << k
        for _ in range(5):
            self._shift()
        while self.out and self.out[-1] == 0:
            self.out.pop()
        return bytes(self.out)


class Decoder:
    def __init__(self, data):
        self.data, self.pos, self.range, self.code = data, 0, 0xFFFFFFFF, 0
        for _ in range(4):
            self.code = (self.code << 8) | self._byte()

    def _byte(self):
        b = self.data[self.pos] if self.pos < len(self.data) else 0
        self.pos += 1
        return b

    def bit(self, p):
        bound = (self.range >> 16) * p
        if self.code < bound:
            self.range, b = bound, 1
        else:
            self.code -= bound
            self.range -= bound
            b = 0
        while self.range < 0x1000000:
            self.range <<= 8
            self.code = ((self.code << 8) | self._byte()) & 0xFFFFFFFF
        return b


# ---------------------------------------------------------------------------------------------------- model

def rates(n0, shift):
    """Adaptation rate by update count, 15-bit fixed point: 1 / (n + n0 + 1.5), never below 2^-shift."""
    floor, out, n = 32768 >> shift, [], 0
    while True:
        r = 65536 // (2 * (n + n0) + 3)
        if r <= floor:
            return out + [floor]
        out.append(r)
        n += 1


class Model:
    """Adaptive P(1) per context, starting at the stored prior levels after every reset() (a block start)."""

    def __init__(self, priors, n0, shift):
        self.priors, self.rate = priors, rates(n0, shift)
        self.last = len(self.rate) - 1
        self.p, self.n = {}, {}

    def reset(self):
        self.p.clear()
        self.n.clear()

    def prob(self, ctx):
        p = self.p.get(ctx)
        return LEVELS[self.priors[ctx]] if p is None else p

    def update(self, ctx, b):
        p, n = self.prob(ctx), self.n.get(ctx, 0)
        p += ((b << 16) - p) * self.rate[n] >> 15  # floor division, as Java's >> on int
        self.p[ctx] = PMAX if p > PMAX else PMIN if p < PMIN else p
        self.n[ctx] = n + 1 if n < self.last else n

    def code(self, enc, ctx, b):
        enc.bit(self.prob(ctx), b)
        self.update(ctx, b)

    def decode(self, dec, ctx):
        b = dec.bit(self.prob(ctx))
        self.update(ctx, b)
        return b


def levels_of(counts):
    """Prior levels from [zeros, ones] per context: the level nearest, in the logistic domain, to the smoothed share
    of ones; an unused context repeats the level before it (cheapest to store)."""
    out, prev = [], 8
    for z, o in counts:
        if z + o:
            p = (o + 0.4) / (z + o + 0.8)
            prev = max(0, min(15, int(round(math.log(p / (1 - p)) / 0.75 + 7.5))))
        out.append(prev)
    return out


def code_priors(levels):
    """The prior levels, range coded: a 'same as before' flag (context 0), else the level as a 4-bit tree (1..15)."""
    enc, model, prev = Encoder(), Model([8] * 16, 0, 4), 8
    for v in levels:
        model.code(enc, 0, 1 if v == prev else 0)
        if v != prev:
            node = 1
            for i in range(3, -1, -1):
                model.code(enc, node, (v >> i) & 1)
                node = node * 2 + ((v >> i) & 1)
        prev = v
    return enc.finish()


def decode_priors(data, count):
    dec, model, out, prev = Decoder(data), Model([8] * 16, 0, 4), [], 8
    for _ in range(count):
        if not model.decode(dec, 0):
            node = 1
            while node < 16:
                node = node * 2 + model.decode(dec, node)
            prev = node - 16
        out.append(prev)
    return out


# ---------------------------------------------------------------------------------------------------- partition

def masked(runs, family):
    """The ground-truth runs [(start, region)] as [(start, region or None)]: wildcard ranges None, equal
    neighbours merged."""
    bits, _, _, wild = FAMILIES[family]
    total, out = 1 << bits, []
    for i, (s, r) in enumerate(runs):
        e = runs[i + 1][0] if i + 1 < len(runs) else total
        pieces = [(s, e, r)]
        for lo, hi in wild:
            nxt = []
            for a, b, rr in pieces:
                if rr is None or b <= lo or a >= hi:
                    nxt.append((a, b, rr))
                    continue
                if a < lo:
                    nxt.append((a, lo, rr))
                nxt.append((max(a, lo), min(b, hi), None))
                if b > hi:
                    nxt.append((hi, b, rr))
            pieces = nxt
        for a, b, rr in pieces:
            if not out or out[-1][1] != rr:
                out.append((a, rr))
    return out


def public_size(s, e, family):
    w = 0
    for lo, hi in FAMILIES[family][3]:
        a, b = max(s, lo), min(e, hi)
        if b > a:
            w += b - a
    return e - s - w


# ---------------------------------------------------------------------------------------------------- the trie walk

def t_layout(family):
    bits, top, topd, _ = FAMILIES[family]
    depths = bits - topd
    same = depths * 24  # split contexts: 24 per depth topd .. bits-1
    choice = same + (depths + 1) * 24  # label contexts: 24 per depth topd .. bits
    return {'bits': bits, 'top': top, 'topd': topd, 'same': same, 'choice': choice, 'contexts': choice + 32}


def t_ctx(L, d, lc, lastd, prev):
    """Split context of a node at depth d (lc: a left child), or label context after adding L['same']."""
    ld = lastd - d
    ld = 0 if ld < 0 else 3 if ld > 2 else ld + 1
    return ((d - L['topd']) * 2 + lc) * 12 + ld * 3 + PCLASS[prev]


def t_events(parts, family):
    """The leaves of the minimal trie in preorder, as (x, depth, label, state, events): state = lastd | prev << 6 |
    prev2 << 8 before the leaf's first event (lastd: depth of the previous leaf; prev, prev2: the label of the
    previous leaf and the label before that one), events = [(context, bit)] from the node after the previous leaf
    down to this leaf, and its label."""
    L = t_layout(family)
    bits, topd = L['bits'], L['topd']
    starts = [s for s, _ in parts]
    regs = [r for _, r in parts]
    x, end, d, lc = L['top'], L['top'] + (1 << (bits - topd)), topd, 1
    prev, prev2, lastd = WEST, OTHER, topd
    leaves, ev = [], []
    state = lastd | prev << 6 | prev2 << 8
    while x < end:
        size = 1 << (bits - d)
        seen, uniform = None, True
        for i in range(bisect.bisect_right(starts, x) - 1, bisect.bisect_left(starts, x + size)):
            r = regs[i]
            if r is not None:
                if seen is None:
                    seen = r
                elif r != seen:
                    uniform = False
                    break
        if d < bits:
            ev.append((t_ctx(L, d, lc, lastd, prev), 0 if uniform else 1))
        if not uniform:
            d, lc = d + 1, 1
            continue
        forced = not lc and lastd == d  # a right child after a leaf sibling: its label differs
        lab = prev if seen is None else seen
        if forced and lab == prev:  # only a wildcard-only leaf could get here
            lab = CHOICES[prev][0]
        if not forced:
            ev.append((L['same'] + t_ctx(L, d, lc, lastd, prev), 1 if lab == prev else 0))
        if lab != prev:
            k, c = CHOICES[prev].index(lab), L['choice'] + prev * 4 + prev2
            ev.append((c, 1 if k == 0 else 0))
            if k:
                ev.append((c + 16, 1 if k == 1 else 0))
            prev2, prev = prev, lab
        leaves.append((x, d, lab, state, ev))
        lastd, x = d, x + size
        ev, state = [], lastd | prev << 6 | prev2 << 8
        d, lc = max(bits - ctz(x, bits), topd), 0
    return leaves


class TWalk:
    """The lookup walk: from the node (x, d) (lc: a left child) with the walk state before it, seek(key) decodes
    leaves in preorder up to the one holding key and returns its region. A lookup makes one seek; later seeks must
    not go back (verify.py batches the keys of one block)."""

    def __init__(self, model, dec, family, x, d, lc, state):
        self.model, self.dec, self.L = model, dec, t_layout(family)
        self.x, self.d, self.lc, self.end, self.events = x, d, lc, x, 0
        self.lastd, self.prev, self.prev2 = state & 63, (state >> 6) & 3, (state >> 8) & 3

    def seek(self, key):
        while key >= self.end:
            self._leaf()
        return self.prev

    def _leaf(self):
        model, dec, L = self.model, self.dec, self.L
        bits, topd = L['bits'], L['topd']
        x, d, lc, prev, prev2, lastd = self.x, self.d, self.lc, self.prev, self.prev2, self.lastd
        while d < bits:
            self.events += 1
            if not model.decode(dec, t_ctx(L, d, lc, lastd, prev)):
                break
            d, lc = d + 1, 1
        if lc or lastd != d:
            self.events += 1
            same = model.decode(dec, L['same'] + t_ctx(L, d, lc, lastd, prev))
        else:
            same = 0
        if not same:
            c = L['choice'] + prev * 4 + prev2
            self.events += 1
            if model.decode(dec, c):
                lab = CHOICES[prev][0]
            else:
                self.events += 1
                lab = CHOICES[prev][1] if model.decode(dec, c + 16) else CHOICES[prev][2]
            prev2, prev = prev, lab
        x += 1 << (bits - d)
        self.x = self.end = x
        self.prev, self.prev2, self.lastd, self.lc = prev, prev2, d, 0
        self.d = max(bits - ctz(x, bits), topd)


# ---------------------------------------------------------------------------------------------------- cells

def make_cells(family, leaves, levels, block, decisions, n0, shift, split):
    """Cuts the key space into aligned cells: [(lo, depth, None, region)] for a uniform cell, [(lo, depth, bytes,
    state)] for a coded one. A cell is cut in two while its walk exceeds `block` bytes or `decisions` decisions, or
    while a half of depth <= `split` is uniform."""
    bits, top, topd, _ = FAMILIES[family]
    xs = [x for x, *_ in leaves]
    model = Model(levels, n0, shift)
    cells = []

    def uniform(lo, d):
        """The leaf covering the aligned node (lo, d), or None when the node is split."""
        i = bisect.bisect_right(xs, lo) - 1
        x, dd = leaves[i][0], leaves[i][1]
        return leaves[i] if dd <= d else None

    def rec(lo, d):
        leaf = uniform(lo, d)
        if leaf is not None:
            cells.append((lo, d, None, leaf[2]))
            return
        half = lo + (1 << (bits - d - 1))
        if d + 1 <= split and (uniform(lo, d + 1) or uniform(half, d + 1)):
            rec(lo, d + 1)
            rec(half, d + 1)
            return
        i0, i1 = bisect.bisect_left(xs, lo), bisect.bisect_left(xs, lo + (1 << (bits - d)))
        skip = d - max(bits - ctz(lo, bits), topd) + 1  # the split flags of this node and the nodes above it
        events = leaves[i0][4][skip:] + [e for leaf in leaves[i0 + 1:i1] for e in leaf[4]]
        if len(events) <= decisions:
            enc = Encoder()
            model.reset()
            for c, b in events:
                model.code(enc, c, b)
            data = enc.finish()
            if len(data) <= block:
                cells.append((lo, d, data, leaves[i0][3]))
                return
        rec(lo, d + 1)
        rec(half, d + 1)

    rec(top, topd)
    return cells


def build_family(family, runs, block=512, decisions=8192, params=PARAMS, split=12):
    """One family's section: the cells, the priors and the adaptation parameters (the smallest of `params`)."""
    leaves = t_events(masked(runs, family), family)
    L = t_layout(family)
    counts = [[0, 0] for _ in range(L['contexts'])]
    for *_, ev in leaves:
        for c, b in ev:
            counts[c][b] += 1
    levels = levels_of(counts)
    best = None
    for n0, shift in params:
        cells = make_cells(family, leaves, levels, block, decisions, n0, shift, split)
        size = sum(len(c[2]) + 3 for c in cells if c[2] is not None)
        if best is None or size < best[0]:
            best = (size, n0, shift, cells)
    _, n0, shift, cells = best
    return {'family': family, 'block': block, 'decisions': decisions, 'cells': cells, 'priors': levels, 'n0': n0,
            'shift': shift, 'leaves': len(leaves), 'contexts': L['contexts']}


def write_file(sections, year, month):
    """Header, both families' resident parts (section header, priors, cell tree, cell table), then the blocks."""
    heads, blobs = [], []
    for f in sections:
        bits, top, topd, _ = FAMILIES[f['family']]
        cells, block = f['cells'], f['block']
        tree, table, i = [], bytearray(), 0
        stack = [(top, topd)]
        while stack:  # the cell tree in preorder: 1 = cut in two, 0 = a cell
            lo, d = stack.pop()
            if cells[i][0] == lo and cells[i][1] == d:
                tree.append(0)
                i += 1
            else:
                tree.append(1)
                stack.append((lo + (1 << (bits - d - 1)), d + 1))
                stack.append((lo, d + 1))
        for lo, d, data, v in cells:
            if data is None:
                put_varint(table, v)  # a uniform cell: its region
            else:
                put_varint(table, 4 + block - len(data))  # a coded cell: 4 + the block's slack, then the state
                put_varint(table, v)
        packed = bytes(sum(b << (7 - j) for j, b in enumerate(tree[k:k + 8])) for k in range(0, len(tree), 8))
        priors = code_priors(f['priors'])
        data = b''.join(c[2] for c in cells if c[2] is not None)
        nblocks = sum(1 for c in cells if c[2] is not None)
        heads.append(struct.pack('>HHHBBHHHI', block, len(cells), nblocks, f['n0'], f['shift'], len(priors),
                                 len(packed), len(table), len(data)) + priors + packed + bytes(table))
        blobs.append(data)
    resident = HEADER + sum(len(h) for h in heads)
    return (MAGIC + struct.pack('>BBHBBHI', VERSION, ord('T'), year, month, 0, 0, resident) + b''.join(heads)
            + b''.join(blobs))


def put_varint(out, n):
    while n >= 0x80:
        out.append((n & 0x7F) | 0x80)
        n >>= 7
    out.append(n)


def get_varint(data, pos):
    n = shift = 0
    while True:
        b = data[pos]
        pos += 1
        n |= (b & 0x7F) << shift
        shift += 7
        if b < 0x80:
            return n, pos


class Reader:
    """An IPRT file opened through read(position, length) -> bytes: reads the resident part once, then at most one
    block per lookup. Counts reads and bytes."""

    def __init__(self, read):
        self._read = read
        self.reads = self.bytes = 0
        head = self.fetch(0, HEADER)
        if head[:4] != MAGIC or head[4] != VERSION or head[5] != ord('T'):
            raise ValueError('not an IPRT file')
        self.year, self.month, _, _, self.resident = struct.unpack('>HBBHI', head[6:16])
        body = self.fetch(HEADER, self.resident - HEADER)
        self.fam, pos, off = {}, 0, self.resident
        for family in (4, 6):
            bits, top, topd, _ = FAMILIES[family]
            block, ncells, nblocks, n0, shift, lp, lt, lc, ldata = struct.unpack('>HHHBBHHHI',
                                                                                  body[pos:pos + SECTION])
            pos += SECTION
            contexts = t_layout(family)['contexts']
            priors = decode_priors(body[pos:pos + lp], contexts)
            pos += lp
            bitsof = body[pos:pos + lt]
            pos += lt
            cell_values, p = [], pos
            for _ in range(ncells):
                v, p = get_varint(body, p)
                if v >= 4:
                    st, p = get_varint(body, p)
                    cell_values.append((block - (v - 4), st))
                else:
                    cell_values.append((None, v))
            if p != pos + lc:
                raise ValueError('a damaged IPRT file')
            pos += lc
            # the cell tree as the Java reader holds it: per preorder node, an internal node's right child index
            # (its left child is the next node), or a cell: ('U', region) or ('B', block index)
            nodes, offs, states, bit, cell = [], [off], [], iter(range(len(bitsof) * 8)), iter(cell_values)

            def parse(depth):
                k = next(bit)
                if (bitsof[k >> 3] >> (7 - (k & 7))) & 1:
                    if depth >= bits:
                        raise ValueError('a damaged IPRT file')
                    i = len(nodes)
                    nodes.append(None)
                    parse(depth + 1)
                    nodes[i] = len(nodes)
                    parse(depth + 1)
                    return
                n, v = next(cell)
                if n is None:
                    nodes.append(('U', v))
                else:
                    nodes.append(('B', len(states)))
                    offs.append(offs[-1] + n)
                    states.append(v)

            parse(topd)
            if len(nodes) != 2 * ncells - 1 or len(states) != nblocks or offs[-1] != off + ldata:
                raise ValueError('a damaged IPRT file')
            self.fam[family] = {'nodes': nodes, 'offs': offs, 'states': states, 'model': Model(priors, n0, shift),
                                'top': top, 'topd': topd, 'bits': bits, 'topend': top + (1 << (bits - topd))}
            off += ldata
        if pos != len(body):
            raise ValueError('a damaged IPRT file')
        self.open_reads, self.open_bytes = self.reads, self.bytes
        self.reads = self.bytes = 0

    def fetch(self, pos, n):
        self.reads += 1
        self.bytes += n
        data = self._read(pos, n)
        if len(data) != n:
            raise ValueError('short read')
        return data

    def locate(self, family, key):
        """('none', region) outside the coded space, ('cell', region) in a uniform cell, else ('block', (index, lo,
        depth)): a descent of the cell tree."""
        f = self.fam[family]
        if not f['top'] <= key < f['topend']:
            return 'none', OTHER
        i, lo, d, bits = 0, f['top'], f['topd'], f['bits']
        while not isinstance(f['nodes'][i], tuple):
            half = 1 << (bits - d - 1)
            if key >= lo + half:
                lo, i = lo + half, f['nodes'][i]
            else:
                i += 1
            d += 1
        kind, v = f['nodes'][i]
        return ('cell', v) if kind == 'U' else ('block', (v, lo, d))

    def walker(self, family, where, data):
        f = self.fam[family]
        b, lo, d = where
        f['model'].reset()
        return TWalk(f['model'], Decoder(data), family, lo, d + 1, 1, f['states'][b])

    def span(self, family, b):
        offs = self.fam[family]['offs']
        return offs[b], offs[b + 1] - offs[b]

    def lookup(self, family, key):
        """(region, decisions decoded) for a key; reads at most one block."""
        kind, v = self.locate(family, key)
        if kind != 'block':
            return v, 0
        w = self.walker(family, v, self.fetch(*self.span(family, v[0])))
        return w.seek(key), w.events

    def lookup_sorted(self, family, keys):
        """[(region, reads, bytes, decisions)] for ascending keys: per key exactly what lookup() returns and costs,
        but each block is read and decoded once (the walk of a lookup is a prefix of the walk of its block)."""
        out, cur, w = [], None, None
        for key in keys:
            kind, v = self.locate(family, key)
            if kind != 'block':
                out.append((v, 0, 0, 0))
                continue
            pos, n = self.span(family, v[0])
            if v[0] != cur:
                cur, w = v[0], self.walker(family, v, self._read(pos, n))
            out.append((w.seek(key), 1, n, w.events))
        return out

    def region(self, address):
        """The region of an address given as 4 or 16 bytes."""
        if len(address) == 4:
            return self.lookup(4, int.from_bytes(address[:3], 'big'))[0]
        return self.lookup(6, int.from_bytes(address[:6], 'big'))[0]
