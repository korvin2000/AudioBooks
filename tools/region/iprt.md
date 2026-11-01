# IPRT 2: a compact IP-to-region table you can look up without loading it

IPRT stores the app's IP-to-region map (4 regions: 0 other, 1 Russia, 2 Ukraine, 3 west) for IPv4 in /24 blocks and
for IPv6 in /48 blocks. It reproduces the partition built by `tools/region/build_ip_regions.py` exactly on every
public address. The table is 104,183 bytes; the current varint file is 395,199 bytes, or 148,256 deflated. A lookup
reads at most one block of at most 512 bytes, after a one-time read of a 2,776-byte resident part.

Reference code: `iprt.py` (encoder and reference reader), `app/src/main/java/com/fanok/audiobooks/region/IpRegions.java` (the app's reader).
Everything is integer arithmetic, so the Python and Java readers decode exactly the same way.

## 1. The idea

1. **The map as a binary trie.** The key of an address is its top 24 bits (IPv4) or top 48 bits (IPv6). In the
   minimal binary trie over that key space, a node is a leaf when all of its public keys share one region. Internet
   allocations are CIDR blocks, which are exactly trie nodes, so the trie is the natural shape of the data. A /16
   allocation is a single leaf; it costs no length and no alignment.
2. **The trie's preorder walk, range coded.** Each node gives one binary decision (split or leaf), and each leaf
   gives a label (unchanged, or a new region). Every decision is coded by a binary range coder with an adaptive
   probability per context.
3. **The walk needs no stack.** The node after a leaf that ends at `x` is the largest aligned block starting at `x`:
   depth `max(bits - ctz(x), top depth)`. A decoder therefore holds only `(x, d)` plus a few context variables.
4. **Contexts.** Each decision is conditioned on:
   - the node's depth (allocation sizes are depth-specific);
   - whether the node is a left or right child;
   - the depth of the previous leaf (how fine-grained the neighbourhood is);
   - the class of the previous label;
   - for a new label, the previous two labels (west/other alternate).

   A right child whose left sibling is a leaf must change label, so that decision is never coded.
5. **Learned priors that adapt locally.** Each block starts its context probabilities at trained prior levels (16
   levels, 4 bits per context) and adapts them while decoding, with a count-based rate. Adaptation captures local
   regularity, such as an APNIC zone with a /32 island in every /26. It makes the file 14% smaller than static
   probabilities would.
6. **Cells, an adaptive radix table.** The key space is cut into aligned blocks, the leaves of a small "cell tree".
   A cut is made while a cell's walk is too large (more than 512 bytes or more than 8,192 decisions), and also while
   either half of a cell at prefix length up to 12 is uniform. A uniform cell is answered from memory, with no read.
   A coded cell is one block, an independent range-coded stream. The cell tree costs 1 bit per node, plus 1 byte per
   uniform cell and about 3 bytes per coded cell.
7. **Wildcards.** Keys of non-public addresses can take any region, which removes splits. The IPv4 wildcards are 0/8,
   10/8, 100.64/10, 127/8, 169.254/16, 172.16/12, 192.168/16 and 224/3. For IPv6, only 2000::/3 is coded.

## 2. File layout (big-endian)

```
offset 0     header, 16 bytes
offset 16    IPv4 section: section header (18 bytes), priors, cell tree, cell table
             IPv6 section: same layout
offset R     IPv4 blocks (D4 bytes), then IPv6 blocks (D6 bytes); file length = R + D4 + D6
```

The bytes `[0, R)` are the resident part: the reader reads them once, in two reads (header, then the rest).

### 2.1 Header (16 bytes)

| off | size | field |
|---|---|---|
| 0 | 4 | magic `IPRT` |
| 4 | 1 | version = 2 |
| 5 | 1 | coding = `T` (0x54) |
| 6 | 2 | year of the source data |
| 8 | 1 | month of the source data |
| 9 | 1 | 0 (reserved) |
| 10 | 2 | 0 (reserved) |
| 12 | 4 | R, the length of the resident part (header and both sections) |

### 2.2 Family constants (fixed by the format)

| family | bits | top (first coded key) | topd (its depth) | contexts N |
|---|---|---|---|---|
| IPv4 | 24 | 0 | 0 | 1208 |
| IPv6 | 48 | 0x200000000000 (2000::/3) | 3 | 2216 |

The context count is `N = 24 * (2 * (bits - topd) + 1) + 32`, laid out as:

- **split contexts:** `[0, SAME)`, where `SAME = 24 * (bits - topd)`;
- **label contexts:** `[SAME, CHOICE)`, where `CHOICE = SAME + 24 * (bits - topd + 1)`;
- **new-label contexts:** `[CHOICE, CHOICE + 32)`.

### 2.3 Family section

| off | size | field |
|---|---|---|
| 0 | 2 | B, the block size cap (512) |
| 2 | 2 | C, the number of cells (at most 16384) |
| 4 | 2 | K, the number of coded cells (blocks) |
| 6 | 1 | n0, the prior weight of the adaptation |
| 7 | 1 | shift, which sets the adaptation-rate floor 2^-shift |
| 8 | 2 | P, the bytes of the prior table |
| 10 | 2 | T, the bytes of the cell tree |
| 12 | 2 | E, the bytes of the cell table |
| 14 | 4 | D, the bytes of this family's blocks |

These 18 bytes are followed by the prior table (P bytes), the cell tree (T bytes) and the cell table (E bytes).

- **Prior table.** A range-coded stream (§3) decoded with 16 contexts, all at level 8, with n0 = 0 and shift = 4.
  It is decoded as:
  ```
  prev = 8
  for c in 0 .. N-1:
      if bit(ctx 0) == 0:
          node = 1
          repeat 4 times: node = 2 * node + bit(ctx node)
          prev = node - 16
      prior[c] = prev
  ```
- **Cell tree.** Bits are read MSB-first in preorder from the root node `(top, topd)`. A 1 means the node is cut
  into its halves (the left half follows, then the right). A 0 means the node is a cell. The tree uses `2C - 1`
  bits; the rest of the last byte is zero.
- **Cell table.** One entry per cell, in preorder (which is also key order), as LEB128 varints (7 bits per byte,
  low group first). An entry `v < 4` is a uniform cell of region `v`. An entry `v >= 4` is a coded cell whose block
  is `B - (v - 4)` bytes long. It is followed by a varint `state = lastd | prev << 6 | prev2 << 8`: the walk state
  before the cell's first leaf (the previous leaf's depth, and the previous two labels). The blocks of coded cells
  are stored back to back in cell order.

## 3. Range decoder and adaptive model

**Range decoder.** It reads a block of n bytes and treats bytes past the block as 0. To start, set
`range = 0xFFFFFFFF` and set `code` to the first 4 bytes, big-endian. With `p = P(1) * 65536`, one bit is decoded as:

```
bound = (range >> 16) * p
if code < bound:  range = bound;                  bit = 1
else:             code -= bound; range -= bound;  bit = 0
while range < 2^24:  range <<= 8;  code = (code << 8 | next byte) mod 2^32
```

The encoder is the LZMA-style carry-propagating range encoder with its first output byte (always 0) dropped. To
finish, it picks the value in the final interval with the most trailing zero bits, writes it, and drops trailing
zero bytes, because the reader pads with zeros. A block therefore ends after the last byte that matters.

**Model.** Each context holds `(p, n)`.

- **Start of a block.** `p = LEVELS[prior level]` and `n = 0`, where
  `LEVELS = 236, 497, 1042, 2168, 4427, 8714, 16062, 26695, 38841, 49474, 56822, 61109, 63368, 64494, 65039, 65300`
  (logistic steps of 0.75).
- **Rate table.** `rate[i] = floor(65536 / (2 * (i + n0) + 3))` for as long as that value exceeds
  `floor = 32768 >> shift`; the last entry is `floor`. This adapts at `1 / (n + n0 + 1.5)`, never below 2^-shift.
- **After decoding bit b with p.** `p += ((b << 16) - p) * rate[n] >> 15`, where `>>` is an arithmetic, flooring
  shift. Then clamp p to `[32, 65504]` and set `n = min(n + 1, last index)`.

## 4. The walk of a coded cell

A cell is the aligned node `(lo, D)`, covering keys `[lo, lo + 2^(bits - D))`. Its root is split by definition, so
the walk starts at the left child, with `state` taken from the cell table:

```
x = lo; d = D + 1; lc = 1; lastd, prev, prev2 = state & 63, state >> 6 & 3, state >> 8 & 3
loop:                                                   # one leaf per iteration
    while d < bits and bit(ctx(d, lc, lastd, prev)) == 1:    d += 1; lc = 1      # descend
    if (lc == 1 or lastd != d) and bit(SAME + ctx(d, lc, lastd, prev)) == 1:
        pass                                            # same label as the previous leaf
    else:                                               # a new label (forced after a leaf sibling)
        c = CHOICE + 4 * prev + prev2
        k = 0 if bit(c) == 1 else (1 if bit(c + 16) == 1 else 2)
        prev2, prev = prev, CHOICES[prev][k]
    if key < x + 2^(bits - d):  return prev             # the leaf [x, x + 2^(bits-d)) holds the key
    lastd = d; x += 2^(bits - d); d = max(bits - ctz(x), topd); lc = 0
```

The helpers are:

- `ctx(d, lc, lastd, prev) = ((d - topd) * 2 + lc) * 12 + ld * 3 + pclass(prev)`, where `ld` is 0 if `lastd < d`,
  1 if `lastd = d`, 2 if `lastd = d + 1`, and 3 otherwise;
- `pclass`: other → 0, Russia → 1, Ukraine → 1, west → 2;
- `CHOICES`: other → (west, Russia, Ukraine); Russia → (west, other, Ukraine); Ukraine → (west, other, Russia);
  west → (other, Russia, Ukraine);
- `ctz(x)`, the trailing zero bits of x, capped at `bits`.

## 5. Lookup, step by step

1. Take the key: the top 24 bits of the IPv4 address, or the top 48 bits of the IPv6 address. An IPv6 key outside
   2000::/3 is never public; the answer is 0 and nothing is read.
2. Descend the resident cell tree: start at node 0 with `(lo, d) = (top, topd)`. While the node is cut, let
   `half = 2^(bits - d - 1)`. If `key - lo >= half`, take the right child (`lo += half`); otherwise take the left
   child. Then `d += 1`. This is at most 45 steps.
3. A uniform cell gives its region at once, with no read.
4. A coded cell: make one positional read of its block (at most B = 512 bytes), then run §4 up to the leaf that holds
   the key. The walk decodes at most 8,192 decisions; the average is 1,828 over `verify.py`'s queries.

`IpRegions` keeps the cell tree as a `char[]` in preorder:

- an internal node holds the index of its right child; its left child is the next entry;
- a cell holds `0x8000 | 0x4000 | region` (uniform) or `0x8000 | block` (coded).

Block offsets are an `int[]` and block states a `char[]`. Priors are kept as nibbles, two per byte.

## 6. How the encoder builds it (`encode.py`)

1. **Ground truth.** Run `read → partition → rounded` from `build_ip_regions.py`, imported unchanged. Wildcard key
   ranges become "any region".
2. **Minimal trie, in preorder.** A node is a leaf when its non-wildcard keys share one region. A wildcard-only leaf
   takes the previous label, which costs least. Each leaf has the events (context, bit) from the node after the
   previous leaf down to itself.
3. **Prior levels.** For each context, `p = (ones + 0.4) / (total + 0.8)` and the level is
   `round(ln(p / (1 - p)) / 0.75 + 7.5)`, clamped to [0, 15]. An unused context repeats the previous level.
4. **Cells.** Start at the root:
   - a uniform node becomes a uniform cell;
   - a node with a uniform half at prefix length up to 12 is cut;
   - otherwise the node's walk is coded, dropping the first leaf's split flags for the node and its ancestors, which
     the cell tree already implies. It becomes a coded cell if it fits in 512 bytes and 8,192 decisions; if not,
     the node is cut.
5. **Adaptation parameters.** For each family, try `(n0, shift)` in `(4,4), (4,5), (8,4), (8,5)` and keep the
   smallest result. The output is deterministic: rebuilding from the CSV gives byte-identical files.

## 7. Why it is compact

The measured effect of each choice is from `compare.py`. Every design listed is exact on the same 698,055 queries.

| design | file bytes |
|---|---|
| current varint stream (read whole, then scanned) | 395,199 |
| the same, deflated at level 9 (no random access) | 148,256 |
| the same, xz extreme (no random access) | 136,944 |
| deflate in 512-run chunks plus a chunk index (random access) | 178,754 |
| run coding: length class, alignment flag and leftover bits, next region; adaptive; frames | 109,263 |
| trie coding, same frames | 105,111 |
| trie coding, static probabilities (no adaptation), cells | 121,112 |
| **trie coding, adaptive, cells (shipped)** | **104,183** |

- **Trie over runs (−3.8%).** The trie is also leaner in contexts: the run coder needs 1.8 times the resident
  memory (14.7 KB against 8.2 KB in the frames container).
- **Adaptation (−14%).** Local statistics are learned within a block.
- **Cells over a fence index of frames (−0.9%).** The cells also give 29% fewer bytes and 30% fewer decisions per
  lookup.
- **Per-run cost.** The shipped table spends 4.24 bits per IPv4 run (66,061 bytes for 124,703 runs) and 2.78 bits
  per IPv6 run (35,346 bytes for 101,662 runs). A decision costs 0.75 bits on average for IPv4 and 0.33 bits for
  IPv6.
- **Ideas tried that did not pay** (measured, then dropped):
  - a recency cache or length repeats (the adaptive model already captures them);
  - mirror-subtree contexts (−0.4%, at 2× the contexts);
  - logistic mixing of 4 models (−2%, at 3× the contexts);
  - per-class prior tables (the extra tables cost more than they save);
  - 32 prior levels instead of 16 (−0.14% payload, at 2× the resident priors).

## 8. Costs and complexity

- **File:** 104,183 bytes. The resident part is 2,776 bytes:

  | family | priors | cell tree | cell table | section header | cells (uniform + coded) | block bytes |
  |---|---|---|---|---|---|---|
  | IPv4 | 286 | 57 | 771 | 18 | 228 (23 + 205) | 66,061 |
  | IPv6 | 605 | 75 | 930 | 18 | 299 (82 + 217) | 35,346 |

- **Open:** 2 reads, 2,776 bytes, O(resident) work. It decodes 3,424 prior levels and parses 1,052 tree nodes. Warm
  Java: 0.24 ms.
- **Resident memory in Java:** 6,500 bytes of arrays (IPv4 2,844, IPv6 3,656), plus object headers.
- **Lookup:** O(tree depth ≤ 45) in memory, then 0 or 1 positional read of at most 512 bytes, then at most 8,192
  binary decisions; the average is 1,828 over `verify.py`'s queries. A lookup allocates the block buffer (up to
  511 bytes) and the context state, an `int[N]` of 4.8 KB (IPv4) or 8.9 KB (IPv6). Both are fixed sizes,
  independent of the file.
- **Knobs** (encoder options, the format unchanged):
  - `--decisions 4096` cuts the average decisions per lookup by a third (to a maximum of 4,096), for +1.9% file
    size;
  - `--block` caps the bytes read;
  - `--split` sets the prefix length down to which uniform halves are cut off.

## 9. Measured results

| metric | value |
|---|---|
| file size | 104,183 bytes |
| resident part read at open | 2,776 bytes in 2 reads |
| Java resident arrays | 6,500 bytes |
| reads per lookup | 0 or 1 (avg 0.893 over all queries; 0.971 IPv4, 0.696 IPv6) |
| bytes per lookup | avg 286, max 511 (IPv4 avg 343, IPv6 avg 144) |
| decisions per lookup | avg 1,828, max 8,140 |
| no-read share | IPv4 6.95% of uniformly random public addresses, IPv6 98.4% of random 2000::/3 addresses |
| Java, JIT, file-backed | 25.6 µs/lookup (IPv4 28.4, IPv6 18.7) on a loaded Ryzen 5 5500U |
| Java, C1 only / interpreter only | 31 µs / 0.48 ms per IPv4 lookup |
| encode | 19.3 s from the CSV: 2.3 s ground truth, 16.6 s encoding with the 4-way parameter search; with `--quick`, 6.2 s encoding (104,303 bytes) |

Correctness: 0 mismatches on 1,698,055 queries in Python (`verify.py`) and in Java (`JavaCheck`). The queries are
every run start, start − 1 and end of both families; 500,000 uniform and 500,000 in-run random public IPv4
addresses; and 150,000 uniform and 150,000 in-run IPv6 addresses in 2000::/3. Each query is a full address with
random low bits.

## 10. Limits

- **Wildcards.** Correctness is guaranteed for every address outside the wildcard list in §1. That list is a subset
  of what the app's `IpRegions.isPublic` rejects. 192.0.0/24, 192.0.2/24, 198.18/15, 198.51.100/24, 203.0.113/24,
  2001::/32, 2001:db8::/32 and 2002::/16 are still coded exactly, so they are checked as if public. IPv6 outside
  2000::/3 answers 0.
- **Format limits.** At most 16,384 cells per family, blocks of at most 65,535 bytes, files under 2 GB (Java uses
  `int` offsets) and a resident part under 1 MB. The data uses under 2% of the cell limit (299 cells).
- **Cold lookups.** Speed depends on how warm the code is. Interpreted Java takes about 0.5 ms per lookup (285 ns per
  decision). On a phone, the first lookups before JIT/AOT may take about 1 ms. A smaller `--decisions` cap trades
  size for that.
- **No compatibility with IPRG version 1.** This is a new file; the app's reader and asset must change together. It
  must stay stored uncompressed in the APK (`noCompress`) to keep positional reads cheap.
