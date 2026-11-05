"""Builds app/src/main/assets/people/people.bin, the built-in Авторы/Исполнители lists of the sites that have no people
index of their own (search/PeopleIndex.java), from the crawler's lists (scrape.py --lists).

Run after the lists change, with python 3 and no packages:
    python -I tools/people/build_people.py

Reads tools/people/lists/<ROW>.<kind>.tsv (name TAB url TAB books) for the lists in ROWS, writes the file, decodes it
back and checks every list against its tsv (same entries in the same order, byte-identical urls), then prints the
size of each section.

Format PPL1. One dictionary of the names of all lists, then each list as references into it, so a name shared by
many sites is stored once. All but the header is range-coded with adaptive bits (class Rc, mirrored by
PeopleIndex.Rc); each list is a stream of its own, so reading a list decodes the dictionary and that list (and the
first block of its ref list, if it has one) only.
  header  "PPL1", then varints (7 bits a byte, low first, high bit = more) and strings (varint byte count +
          UTF-8): V words, N names, A chars, A char steps (the alphabet ascending), A + 1 code length bytes (symbol
          0 = end of word), the char context classes, the char contexts used; T slug tables, each: varint n, n x
          (varint char, string); M lists, each: row and prefix (each: varint chars shared with the list before's,
          string of the rest), byte kind (0 authors, 1 readers), byte rule, varint param, varint base, varint ref
          (list number + 1, 0 = none), string suffix, varint entries, varint stream bytes; then varint dictionary
          stream bytes. The streams follow: dictionary, then the lists in header order.
  dictionary: the distinct words of all names in name order, front-coded: shared prefix length, then the other
          chars, each down a canonical Huffman code tree with an adaptive bit per node, the states chosen by the
          two chars before (the classes - 1 most frequent apart, the rarer as one); after each word its count as
          a non-first word of a name. Then the distinct names in list order: word count, the first word as a step
          from the previous name's, each other word by its static count.
  list:   first per entry in tsv order its name and books (List.members): with a ref list, whether the name is
          the ref's next one, else a step from the previous entry's name (0 = back, then how far back); books
          (0 = unknown), for a name of the ref whether they are the ref's. Then per entry its url op and the op's
          data; url = prefix + key + suffix. Op 0: the rule's key of the name; 1: the rule's key of the name with
          its last word moved first; 2: an explicit key (text, percent-encoded under rules 1-2); 3: the whole url
          as text. The rule's key: 0 text; 1 the name percent-encoded; 2 the lower-case name percent-encoded; 3 the
          name's slug by table <param>; 4 base + a <param>-bit number, in decimal; 5 a UUID (<param> = 122: version
          4, its 6 fixed bits left out; 128: any).
"""
import bisect
import collections
import heapq
import os
import re
import sys
import urllib.parse

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
LISTS = os.path.join(HERE, 'lists')
OUT = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'people', 'people.bin')
# the sites without a people index of their own (Provider rows with authors = null) and the lists they get
ROWS = {'BAZA_KNIG': 'authors readers', 'KNIGOBLUD': 'authors readers', 'POLEKNIG': 'authors',
        'AKNIG_XYZ': 'authors readers', 'AUDIOKNIGA_LIFE': 'authors readers', 'BIBLUS': 'authors readers',
        'AUDIOKNIGA_ONLINE': 'authors readers', 'SLUSHAT_KNIGI': 'authors', 'BOOKISH': 'authors readers',
        'GOLOSOM': 'authors readers',
        'FANTLAB': 'readers'}  # not a site of the app: fantlab.ru's narrators, whose cards the readers lists open
KINDS = ('authors', 'readers')
TEXT, PCT, PCT_LOWER, SLUG, INT, UUID = range(6)
ALNUM = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789'
NUMBER = re.compile(r'0|[1-9][0-9]*')
UUID_ANY = re.compile(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}')
UUID_V4 = re.compile(r'[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}')
# where the slug learner starts: the common Russian transliteration; each site's table is learnt from its urls
SEED = dict(zip('абвгдеёжзийклмнопрстуфхцчшщъыьэюя',
                'a b v g d e e zh z i j k l m n o p r s t u f h c ch sh sch _ y _ e yu ya'.split()))
SEED = {c: x.strip('_') for c, x in SEED.items()}


# ---------------------------------------------------------------------------------------------------- text rules
# mirrored in PeopleIndex.java: keep them in step

def low(s):
    """Lower case as the app makes it: Character.toLowerCase of the chars below U+0500, the others as they are."""
    return ''.join(c.lower() if ord(c) < 0x500 and len(c.lower()) == 1 else c for c in s)


def pct(s):
    """Percent-encoded UTF-8, all but A-Z a-z 0-9 - . _ ~ (upper-case hex)."""
    return urllib.parse.quote(s, safe='')


def slug(s, table):
    """Lower case, each char by the table (unlisted: ASCII letters and digits as they are, others '-'), runs of '-'
    to one, none at the ends."""
    out = []
    for c in low(s):
        x = table.get(c)
        out.append(x if x is not None else c if c.isascii() and c.isalnum() else '-')
    return re.sub('-+', '-', ''.join(out)).strip('-')


def rotate(name):
    """The name with its last word first ("Ксения Большакова" -> "Большакова Ксения")."""
    w = name.split(' ')
    return ' '.join(w[-1:] + w[:-1])


def derive(rule, table, name):
    return pct(name) if rule == PCT else pct(low(name)) if rule == PCT_LOWER else slug(name, table)


def units(s):
    """UTF-16 code units: the decoder's chars."""
    b = s.encode('utf-16-le', 'surrogatepass')
    return [b[i] | b[i + 1] << 8 for i in range(0, len(b), 2)]


def string(u):
    return b''.join(bytes((c & 0xFF, c >> 8)) for c in u).decode('utf-16-le', 'surrogatepass')


def order(name):
    """scrape.py's list order: Russian first, case- and ё-blind (then exact, to be total)."""
    k = name.casefold().replace('ё', 'е')
    first = next((c for c in k if c.isalpha()), '')
    return ('а' <= first <= 'я') * -2 + ('a' <= first <= 'z') * -1, k, name


def common(a, b):
    n = 0
    while n < len(a) and n < len(b) and a[n] == b[n]:
        n += 1
    return n


# ---------------------------------------------------------------------------------------------------- range coder
LIMIT, PMIN, HALF = 30, 32, 32768
RATE = [4096 * 2 // (2 * n + 3) for n in range(LIMIT + 1)]   # a state's n-th update moves it by 1/(n + 1.5)
NUM = 33 + 16 * 33                                             # states of one num() model


class Rc:
    """LZMA-style range coder over adaptive bits (a state: 16-bit probability of 0 | updates << 16). The same object
    writes (Rc()) or reads (Rc(data, pos)); every call takes the value to write and returns the value written or
    read, so the encoder and the decoder run the same model code."""

    def __init__(self, data=None, pos=0):
        self.range, self.dec = 0xFFFFFFFF, data is not None
        if self.dec:
            self.d, self.pos, self.code = data, pos + 5, int.from_bytes(data[pos + 1:pos + 5], 'big')
        else:
            self.low, self.cache, self.pend, self.out = 0, 0, 1, bytearray()

    def finish(self):
        for _ in range(5):
            self._shift()
        return bytes(self.out)

    def _shift(self):
        if self.low < 0xFF000000 or self.low >> 32:
            carry, t = self.low >> 32, self.cache
            while self.pend:
                self.out.append((t + carry) & 0xFF)
                t, self.pend = 0xFF, self.pend - 1
            self.cache = self.low >> 24 & 0xFF
        self.pend += 1
        self.low = self.low << 8 & 0xFFFFFFFF

    def _norm(self):
        while self.range < 1 << 24:
            self.range <<= 8
            if self.dec:
                self.code = (self.code << 8 | (self.d[self.pos] if self.pos < len(self.d) else 0)) & 0xFFFFFFFF
                self.pos += 1
            else:
                self._shift()

    def _code(self, p, b):
        bound = (self.range >> 16) * p
        if self.dec:
            b = self.code >= bound
            if b:
                self.code -= bound
        elif b:
            self.low += bound
        self.range = self.range - bound if b else bound
        self._norm()
        return int(b)

    def bit(self, m, i, b=0):
        s = m[i]
        p = s & 0xFFFF
        b = self._code(p, b)
        p += ((0 if b else 65536) - p) * RATE[s >> 16] >> 12
        m[i] = min(max(p, PMIN), 65536 - PMIN) | min((s >> 16) + 1, LIMIT) << 16
        return b

    def raw(self, n, v=0):
        r = 0
        for j in range(n - 1, -1, -1):
            r = r << 1 | self._code(HALF, v >> j & 1)
        return r

    def num(self, m, v=0, o=0):
        """v >= 0 as Elias gamma of v + 1 in the states m[o:o + NUM]: the bit length in unary, then the 4 bits
        below the leading one as a bit tree per length, the lower bits raw."""
        x = v + 1
        n = 0 if self.dec else x.bit_length() - 1
        k = 0
        while self.bit(m, o + k, int(k < n)):
            k += 1
        r = 1
        for j in range(k - 1, -1, -1):
            r = r << 1 | (self.bit(m, o + 33 + 16 * k + r, x >> j & 1) if r < 16 else self._code(HALF, x >> j & 1))
        return r - 1

    def tree(self, m, n, v=0, o=0):
        """An n-bit value along a bit tree in m[o + 1:o + 2^n]."""
        r = 1
        for j in range(n - 1, -1, -1):
            r = r << 1 | self.bit(m, o + r, v >> j & 1)
        return r - (1 << n)

    def freq(self, cum, s=0):
        """Symbol s by static counts: cum[i] = the counts of the symbols below i."""
        r = self.range // cum[-1]
        if self.dec:
            s = bisect.bisect_right(cum, min(self.code // r, cum[-1] - 1)) - 1
            self.code -= r * cum[s]
        else:
            self.low += r * cum[s]
        self.range = r * (cum[s + 1] - cum[s])
        self._norm()
        return s


def states(n):
    return [HALF] * n


def text(rc, m, s=None):
    """A string: num() of its UTF-8 byte count (m[0:NUM]), then each byte along a bit tree keyed by the byte
    before (m[NUM + 256 * before:])."""
    b = b"" if rc.dec else s.encode()
    out, prev = bytearray(), 0
    for i in range(rc.num(m, len(b))):
        prev = rc.tree(m, 8, 0 if rc.dec else b[i], NUM + 256 * prev)
        out.append(prev)
    return out.decode()


# ---------------------------------------------------------------------------------------------------- dictionary
CLASSES = (16, 32, 48, 64, 96)   # tried for the char model's context: the k - 1 most frequent chars apart, rarer as one


def huffman(counts):
    """Code lengths of a Huffman code for counts (all > 0)."""
    heap = [(c, i) for i, c in enumerate(counts)]
    heapq.heapify(heap)
    parent, nxt = {}, len(counts)
    while len(heap) > 1:
        (a, i), (b, j) = heapq.heappop(heap), heapq.heappop(heap)
        parent[i] = parent[j] = nxt
        heapq.heappush(heap, (a + b, nxt))
        nxt += 1
    out = []
    for i in range(len(counts)):
        n = 0
        while i in parent:
            i, n = parent[i], n + 1
        out.append(max(n, 1))
    return out


def tree(lengths):
    """The canonical code of the lengths as a tree: child[2 * node + bit] is an inner node (> 0; the root is 0) or
    ~symbol. Returns child, each symbol's path [(node, bit)] and each symbol's place in code order."""
    syms = sorted(range(len(lengths)), key=lambda s: (lengths[s], s))
    child, paths, code, n = [0, 0], {}, 0, lengths[syms[0]]
    for s in syms:
        code, n = code << lengths[s] - n, lengths[s]
        node, path = 0, []
        for j in range(n - 1, -1, -1):
            b = code >> j & 1
            path.append((node, b))
            if not j:
                child[2 * node + b] = ~s
            else:
                if not child[2 * node + b]:
                    child[2 * node + b] = len(child) // 2
                    child += [0, 0]
                node = child[2 * node + b]
        paths[s] = path
        code += 1
    return child, paths, {s: i for i, s in enumerate(syms)}


class Dictionary:
    """The words and names of all lists (format above). Built from names to write, or from the header to read."""

    def __init__(self, names=()):
        self.names = sorted(set(names), key=order)
        self.words = sorted({w for n in self.names for w in n.split(' ')}, key=order)
        assert all(w for n in self.names for w in n.split(' ')), 'a name with a double or outer space'
        chars, prev = collections.Counter(), []
        for w in self.words:
            u = units(w)
            chars.update(u[common(prev, u):])
            prev = u
        self.alphabet = sorted(chars)
        self.lengths = huffman([len(self.words)] + [chars[c] for c in self.alphabet]) if self.words else [1]
        self.V, self.N, self.slots, self.cls = len(self.words), len(self.names), 0, CLASSES[0]

    def code(self, rc):
        """Writes (rc writing) or reads the dictionary: returns (words as unit lists, names as word id lists)."""
        enc = not rc.dec
        child, paths, rank = tree(self.lengths)
        nodes = len(child) // 2
        sym = {c: i + 1 for i, c in enumerate(self.alphabet)}
        wid = {w: i for i, w in enumerate(self.words)}
        rest = collections.Counter(wid[w] for n in self.names for w in n.split(' ')[1:])
        slots, chars, pre, freq = {}, [], states(4 * NUM), states(NUM)
        words, prev, p_prev, cum = [], [], 0, [0]
        for i in range(self.V):
            u = units(self.words[i]) if enc else []
            p = rc.num(pre, common(prev, u), NUM * min(p_prev, 3))
            w = prev[:p]
            c1 = sym[w[-1]] if p else 0
            c2 = sym[w[-2]] if p > 1 else 0
            while True:                           # each char down the code tree, one adaptive bit per node
                key = min(rank[c1], self.cls - 1) * self.cls + min(rank[c2], self.cls - 1)
                if key not in slots:              # the states of a context: one per node, made on first use
                    slots[key] = len(slots)
                    chars += [HALF] * nodes
                base = slots[key] * nodes
                path = paths[sym[u[len(w)]] if len(w) < len(u) else 0] if enc else ()
                node, j = 0, 0
                while True:
                    x = child[2 * node + rc.bit(chars, base + node, path[j][1] if enc else 0)]
                    if x <= 0:
                        break
                    node, j = x, j + 1
                if x == ~0:
                    break
                w.append(self.alphabet[~x - 1])
                c1, c2 = ~x, c1
            words.append(w)
            cum.append(cum[-1] + rc.num(freq, rest[i]))
            prev, p_prev = w, p
        assert not self.slots or self.slots == len(slots)
        self.slots = len(slots)
        assert cum[-1] < 1 << 20, 'too many words for the static word code'
        size, step, back = states(4 * NUM), states(2 * NUM), states(1)
        names, prev, d_prev = [], [0], 0
        for i in range(self.N):
            ids = [wid[w] for w in self.names[i].split(' ')] if enc else [0]
            k = rc.num(size, len(ids) - 1, NUM * min(len(prev) - 1, 3)) + 1
            d = ids[0] - prev[0]
            neg = rc.bit(back, 0, int(d < 0))
            d = rc.num(step, abs(d), NUM * (d_prev == 0))
            ws = [prev[0] - d if neg else prev[0] + d]
            for j in range(1, k):
                ws.append(rc.freq(cum, ids[j] if enc else 0))
            names.append(ws)
            prev, d_prev = ws, d
        return words, names


# ---------------------------------------------------------------------------------------------------- lists
class List:
    """One (row, kind) list: entries [(name, url, books)], the url rule and each entry's op."""

    def __init__(self, row, kind, entries=()):
        self.row, self.kind, self.entries, self.n = row, kind, list(entries), len(entries)
        self.rule, self.param, self.base, self.ops, self.table, self.ref = TEXT, 0, 0, [], None, None
        self.prefix, self.suffix, self.keys = '', '', []
        if not entries:
            return
        urls = [u for _, u, _ in entries]
        pre = os.path.commonprefix(urls)
        self.prefix = pre[:max(pre.rfind('/'), pre.rfind('=')) + 1]     # a path's folder or a query's 'q='
        suf = os.path.commonprefix([u[::-1] for u in urls])[::-1].lstrip('-_%' + ALNUM)
        self.suffix = suf if all(len(u) >= len(self.prefix) + len(suf) for u in urls) else ''
        self.keys = [u[len(self.prefix):len(u) - len(self.suffix)] for u in urls]
        head = re.match(r'[^0-9]*', os.path.commonprefix(self.keys)).group()   # fantlab's 'dictor' of /dictor123
        if head and all(NUMBER.fullmatch(k[len(head):]) for k in self.keys):
            self.prefix += head
            self.keys = [k[len(head):] for k in self.keys]

    def slugs(self):
        """(name, key) pairs, if the keys look like slugs."""
        if sum(re.fullmatch(r'[a-z0-9]+(-[a-z0-9]+)*', k) is not None for k in self.keys) > .8 * self.n:
            return [(n, k) for (n, _, _), k in zip(self.entries, self.keys)]
        return []

    def op(self, table, name, key, url):
        """(op, data) of an entry under the list's rule."""
        rule = self.rule
        if rule in (PCT, PCT_LOWER, SLUG):
            if derive(rule, table, name) == key:
                return 0, None
            if ' ' in name and derive(rule, table, rotate(name)) == key:
                return 1, None
        if rule in (PCT, PCT_LOWER):
            try:
                plain = urllib.parse.unquote(key, errors='strict')
                if pct(plain) == key:
                    return 2, plain
            except UnicodeDecodeError:
                pass
            return 3, url
        if rule == INT and NUMBER.fullmatch(key) and 0 <= int(key) - self.base < 1 << self.param:
            return 0, int(key) - self.base
        if rule == UUID and (UUID_V4 if self.param == 122 else UUID_ANY).fullmatch(key):
            return 0, key
        return 2, key

    def choose(self, table):
        """The rule that leaves the fewest bytes, by a rough price of each op (table: the site's slug table)."""
        keys, cands = self.keys, [(TEXT, 0, 0), (PCT, 0, 0), (PCT_LOWER, 0, 0)] + [(SLUG, 0, 0)] * bool(table)
        nums = [int(k) for k in keys if NUMBER.fullmatch(k)]
        if len(nums) > self.n / 2 and max(nums) < 1 << 31:                      # PeopleIndex reads an int base
            cands.append((INT, (max(nums) - min(nums)).bit_length() or 1, min(nums)))
        if sum(UUID_ANY.fullmatch(k) is not None for k in keys) > self.n / 2:
            cands.append((UUID, 122 if all(UUID_V4.fullmatch(k) for k in keys if UUID_ANY.fullmatch(k)) else 128, 0))
        best = None
        for rule, param, base in cands:
            self.rule, self.param, self.base = rule, param, base
            ops = [self.op(table, n, k, u) for (n, u, _), k in zip(self.entries, keys)]
            price = sum((param / 8 if rule >= INT else 0) if o == 0 and rule else .2 if o == 1 else
                        1 + .75 * len(str(x).encode()) for o, x in ops)
            if best is None or price < best[0]:
                best = price, rule, param, base, ops
        _, self.rule, self.param, self.base, self.ops = best
        self.table = table if self.rule == SLUG else None

    def members(self, rc, ids=None, ref=None):
        """Writes (ids: each entry's name id) or reads each entry's name id and books: with a ref list (its ids and
        books), whether the name is the ref's next one, else a step from the name before (0 = back, then how far
        back); books, for a name of the ref whether they are the ref's. Returns (ids, books)."""
        enc = not rc.dec
        step, books, flags = states(2 * NUM), states(3 * NUM), states(2)
        has = dict(zip(*ref)) if ref else {}
        keys = sorted(has)
        out, nid = ([], []), -1
        for i in range(self.n):
            prev, nid = nid, ids[i] if enc else 0
            j = bisect.bisect_right(keys, prev)
            if j < len(keys) and rc.bit(flags, 0, int(nid == keys[j])):
                nid = keys[j]
            else:
                fwd = rc.num(step, nid - prev if nid > prev else 0)
                nid = prev + fwd if fwd else prev - rc.num(step, prev - nid, NUM)
            b = self.entries[i][2] if enc else 0
            if nid in has:
                b = has[nid] if rc.bit(flags, 1, int(b == has[nid])) else rc.num(books, b, NUM)
            else:
                b = rc.num(books, b, 2 * NUM if ref else 0)
            out[0].append(nid)
            out[1].append(b)
        return out

    def code(self, rc, words, names, tables, ids=None, ref=None):
        """Writes (ids: each entry's name id) or reads the list: members(), then each entry's url op and data.
        Returns [(name, url, books)]."""
        enc = not rc.dec
        ids, books = self.members(rc, ids, ref)
        ops, txt = states(4), states(NUM + 256 * 256)
        table = tables[self.param] if self.rule == SLUG else None
        out = []
        for i in range(self.n):
            op, x = self.ops[i] if enc else (0, None)
            op = rc.tree(ops, 2, op)
            name = ' '.join(string(words[w]) for w in names[ids[i]])
            url = None
            if op == 3:
                url = text(rc, txt, x)
            elif op == 2 or op == 0 and self.rule == TEXT:
                key = text(rc, txt, x)
                key = pct(key) if self.rule in (PCT, PCT_LOWER) else key
            elif op == 1:
                key = derive(self.rule, table, rotate(name))
            elif self.rule == INT:
                key = str(self.base + rc.raw(self.param, x or 0))
            elif self.rule == UUID:
                key = uuid(rc, self.param, x)
            else:
                key = derive(self.rule, table, name)
            out.append((name, url or self.prefix + key + self.suffix, books[i]))
        return out


def uuid(rc, bits, key=None):
    """A UUID as 128 raw bits, or for version 4 as 122: 15 hex digits, the variant's 2 free bits, 15 hex digits."""
    h = key.replace('-', '') if key else '0' * 32
    if bits == 122:
        a = rc.raw(60, int(h[:12] + h[13:16], 16))
        v = rc.raw(2, int(h[16], 16) & 3)
        b = rc.raw(60, int(h[17:], 16))
        a = '%015x' % a
        h = a[:12] + '4' + a[12:] + '%x' % (8 | v) + '%015x' % b
    else:
        h = '%016x' % rc.raw(64, int(h[:16], 16)) + '%016x' % rc.raw(64, int(h[16:], 16))
    return '-'.join((h[:8], h[8:12], h[12:16], h[16:20], h[20:]))


# ---------------------------------------------------------------------------------------------------- slug tables
def learn(pairs, table):
    """The char -> text table that derives the most keys from their names, from table on: a char's text gets the
    votes of the keys it would derive with every other char as it is; a few rounds settle it."""
    table = dict(table)
    for _ in range(10):
        votes = collections.Counter()
        for name, key in pairs:
            s = low(name)
            if slug(s, table) == key:
                for c in set(s):
                    votes[c, table.get(c)] += 1
                continue
            for c in set(s):
                if c == ' ' or c.isascii() and c.isalnum() or ord(c) > 0xFFFF:
                    continue
                parts = slug(s, {**table, c: '\0'}).split('\0')
                k = len(parts) - 1
                rest = len(key) - sum(map(len, parts))
                if not k or rest < 0 or rest % k:
                    continue
                x = key[len(parts[0]):len(parts[0]) + rest // k]
                if re.fullmatch('[a-z0-9]{0,4}', x) and slug(s, {**table, c: x}) == key:
                    votes[c, x] += 1
        best = {}
        for (c, x), v in votes.items():
            if v > votes[c, table.get(c)] and v > best.get(c, (0,))[0]:
                best[c] = v, x
        if not best:
            break
        table.update({c: x for c, (v, x) in best.items()})
    assert all(re.fullmatch('[a-z0-9]*', x) for x in table.values()), 'the decoder joins slugs by these rules'
    return {c: x for c, x in table.items() if not (c.isascii() and c.isalnum() and x == c)}


def ids(lst, nid):
    return [nid[n] for n, _, _ in lst.entries]


def members(lst, nid):
    return ids(lst, nid), [b for _, _, b in lst.entries]


def choose_refs(lists, nid):
    """Gives a list the ref list that shrinks its members() most (most lists have none); a ref has no ref."""
    def size(lst, ref=None):
        rc = Rc()
        lst.members(rc, ids(lst, nid), ref and members(ref, nid))
        return len(rc.finish())
    names = {id(lst): set(ids(lst, nid)) for lst in lists}
    gains = []
    for lst in lists:
        own = None
        for ref in lists:
            if ref is not lst and len(names[id(lst)] & names[id(ref)]) > .5 * len(names[id(lst)]):
                own = own or size(lst)
                gains.append((own - size(lst, ref), lst, ref))
    for gain, lst, ref in sorted(gains, key=lambda g: -g[0]):
        if gain > 32 and not lst.ref and not ref.ref and not any(o.ref is lst for o in lists):
            lst.ref = ref


# ---------------------------------------------------------------------------------------------------- file
def varint(v):
    out = bytearray()
    while True:
        out.append(v & 0x7F | (0x80 if v > 0x7F else 0))
        v >>= 7
        if not v:
            return bytes(out)


def vstr(s):
    b = s.encode()
    return varint(len(b)) + b


class Reader:
    def __init__(self, d, pos=0):
        self.d, self.pos = d, pos

    def int(self):
        v = shift = 0
        while True:
            b = self.d[self.pos]
            self.pos += 1
            v |= (b & 0x7F) << shift
            shift += 7
            if b < 0x80:
                return v

    def byte(self):
        self.pos += 1
        return self.d[self.pos - 1]

    def str(self):
        n = self.int()
        self.pos += n
        return self.d[self.pos - n:self.pos].decode()


def write(lists):
    names = [n for lst in lists for n, _, _ in lst.entries]
    dic = Dictionary(names)
    for row in dict.fromkeys(lst.row for lst in lists):          # a slug table per site, if its urls look so
        same = [lst for lst in lists if lst.row == row]
        pairs = [p for lst in same for p in lst.slugs()]
        table = learn(pairs, SEED) if pairs else None
        seen = {c for lst in same for n, _, _ in lst.entries for c in low(n)}
        for lst in same:
            lst.choose({k: v for k, v in table.items() if k in seen} if table else None)
    tables = []
    for lst in lists:
        if lst.rule == SLUG:
            if lst.table not in tables:
                tables.append(lst.table)
            lst.param = tables.index(lst.table)
    nid = {n: i for i, n in enumerate(dic.names)}
    choose_refs(lists, nid)
    best = None
    for dic.cls in CLASSES:                                         # the char context that packs best
        rc = Rc()
        words, names = dic.code(rc)
        stream = rc.finish()
        if best is None or len(stream) < len(best[0]):
            best = stream, dic.cls, dic.slots, words, names
        dic.slots = 0
    stream, dic.cls, dic.slots, words, names = best
    streams = [stream]
    for lst in lists:
        rc = Rc()
        lst.code(rc, words, names, tables, ids(lst, nid), lst.ref and members(lst.ref, nid))
        streams.append(rc.finish())
    head = bytearray(b'PPL1')
    head += varint(dic.V) + varint(dic.N) + varint(len(dic.alphabet))
    prev = 0
    for c in dic.alphabet:
        head += varint(c - prev)
        prev = c
    head += bytes(dic.lengths) + varint(dic.cls) + varint(dic.slots)
    head += varint(len(tables))
    for t in tables:
        head += varint(len(t))
        for c in sorted(t):
            head += varint(ord(c)) + vstr(t[c])
    head += varint(len(lists))
    row = prefix = ''
    for lst, s in zip(lists, streams[1:]):
        for old, new in ((row, lst.row), (prefix, lst.prefix)):   # each after what it shares with the list before
            n = common(old, new)
            head += varint(n) + vstr(new[n:])
        row, prefix = lst.row, lst.prefix
        head += bytes((KINDS.index(lst.kind), lst.rule)) + varint(lst.param) + varint(lst.base)
        head += varint(lists.index(lst.ref) + 1 if lst.ref else 0)
        head += vstr(lst.suffix) + varint(lst.n) + varint(len(s))
    head += varint(len(streams[0]))
    return bytes(head) + b''.join(streams), len(head), streams, tables, (dic.V, dic.N)


def read(d):
    """{(row, kind): [(name, url, books)]} of a file: the Python twin of PeopleIndex."""
    r = Reader(d)
    assert d[:4] == b'PPL1'
    r.pos = 4
    dic = Dictionary()
    dic.V, dic.N = r.int(), r.int()
    alphabet, c = [], 0
    for _ in range(r.int()):
        c += r.int()
        alphabet.append(c)
    dic.alphabet, dic.lengths = alphabet, list(d[r.pos:r.pos + len(alphabet) + 1])
    r.pos += len(alphabet) + 1
    dic.cls, dic.slots = r.int(), r.int()
    tables = []
    for _ in range(r.int()):
        tables.append({chr(r.int()): r.str() for _ in range(r.int())})
    lists, row, prefix = [], '', ''
    for _ in range(r.int()):
        row = row[:r.int()] + r.str()
        prefix = prefix[:r.int()] + r.str()
        lst = List(row, KINDS[r.byte()])
        lst.rule, lst.param, lst.base, lst.prefix, lst.ref = r.byte(), r.int(), r.int(), prefix, r.int() - 1
        lst.suffix, lst.n, lst.size = r.str(), r.int(), r.int()
        lists.append(lst)
    pos = [r.int() + r.pos]
    for lst in lists:
        pos.append(pos[-1] + lst.size)
    words, names = dic.code(Rc(d, r.pos))
    out = {}
    for i, lst in enumerate(lists):
        ref = lists[lst.ref].members(Rc(d, pos[lst.ref])) if lst.ref >= 0 else None
        out[lst.row, lst.kind] = lst.code(Rc(d, pos[i]), words, names, tables, ref=ref)
    return out


def load():
    lists = []
    for row, kinds in ROWS.items():
        for kind in kinds.split():
            path = os.path.join(LISTS, f'{row}.{kind}.tsv')
            if not os.path.exists(path):
                print('no', path)
                continue
            entries = []
            with open(path, encoding='utf-8', newline='\n') as f:
                for line in filter(None, f.read().split('\n')):
                    name, url, books = line.split('\t')
                    entries.append((name, url, int(books)))
            lists.append(List(row, kind, entries))
    return lists


def main():
    sys.stdout.reconfigure(errors='backslashreplace')
    lists = load()
    data, head, streams, tables, dic = write(lists)
    back = read(data)
    for lst in lists:
        got = back[lst.row, lst.kind]
        bad = [(a, b) for a, b in zip(lst.entries, got) if a != b]
        assert len(got) == lst.n and not bad, (lst.row, lst.kind, len(got), lst.n, bad[:3])
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, 'wb') as f:
        f.write(data)
    rules = ('text', 'pct', 'pct-lower', 'slug', 'int', 'uuid')
    print(f'{"header":40} {head:8}')
    print(f'{"dictionary: %d words, %d names" % dic:40} {len(streams[0]):8}')
    for lst, s in zip(lists, streams[1:]):
        ops = collections.Counter(o for o, _ in lst.ops)
        ref = f'  ref {lst.ref.row} {lst.ref.kind}' if lst.ref else ''
        print(f'{lst.row + " " + lst.kind:40} {len(s):8}  {lst.n:6} entries  {rules[lst.rule]:9} '
              f'ops {dict(sorted(ops.items()))}{ref}')
    print(f'{"total " + os.path.relpath(OUT, ROOT):40} {len(data):8}  {sum(l.n for l in lists)} entries, round trip ok')
    for i, t in enumerate(tables):
        print('slug table', i, ' '.join(f'{c}={x or "~"}' for c, x in sorted(t.items())), '(~ = dropped)')


if __name__ == '__main__':
    main()
