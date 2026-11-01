"""Builds app/src/main/assets/region/ip-regions.bin, the app's own IP-to-region table (region/IpRegions.java).

Source: DB-IP "IP to Country Lite" (https://db-ip.com/db/download/ip-to-country-lite), CC BY 4.0 — the app credits
it in "О программе". Each range's country becomes one of the app's four regions (region/Region.java), adjacent
ranges of one region merge, IPv4 is rounded to /24 and IPv6 to /48 blocks (the majority region of each block; at
these sizes under 0.02% of any region's space moves). Reserved and unassigned space (ZZ) joins the range before it:
the app looks up public addresses only.

Run before a release, with python 3 and no packages (about two minutes):
    python -I tools/region/build_ip_regions.py                      # downloads this month's file
    python -I tools/region/build_ip_regions.py dbip-country-lite-2026-11.csv.gz

Format: IPRT (iprt.md, iprt.py), a range-coded binary trie cut into blocks of at most 512 bytes; the app reads
about 3 KB once and then at most one block per lookup, in place in the APK (stored uncompressed, app/build.gradle).
"""
import collections
import datetime
import gzip
import importlib.util
import os
import re
import socket
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
OUT = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'region', 'ip-regions.bin')

# the encoder, by path: python -I leaves this folder off sys.path (and no __pycache__ next to it)
sys.dont_write_bytecode = True
_spec = importlib.util.spec_from_file_location('iprt', os.path.join(HERE, 'iprt.py'))
iprt = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(iprt)

# keep in step with Region.WEST_COUNTRIES
EU = 'AT BE BG HR CY CZ DK EE FI FR DE GR IE IT LV LT LU MT NL PL PT RO SK SI ES SE HU'
EU_PARTS = 'AX GF GP MQ RE YT MF'
WEST_OTHERS = 'GB US JP CH NO IS LI CA AU IL'
US_PARTS = 'PR GU VI AS MP UM'
GB_PARTS = 'JE GG IM GI'
WEST = set((' '.join([EU, EU_PARTS, WEST_OTHERS, US_PARTS, GB_PARTS])).split())
OTHER, RUSSIA, UKRAINE, WEST_CODE = 0, 1, 2, 3


def region(cc):
    if cc == 'RU':
        return RUSSIA
    if cc == 'UA':
        return UKRAINE
    return WEST_CODE if cc in WEST else OTHER


def address(text):
    family = socket.AF_INET6 if ':' in text else socket.AF_INET
    return int.from_bytes(socket.inet_pton(family, text), 'big')


def read(path):
    """The rows of the CSV as (start, end, country or None) per family, in order."""
    v4, v6 = [], []
    with gzip.open(path, 'rt', encoding='ascii') as f:
        for line in f:
            start, end, cc = line.rstrip('\n').split(',')
            row = (address(start), address(end), None if cc == 'ZZ' else cc)
            (v6 if ':' in start else v4).append(row)
    for rows in (v4, v6):
        rows.sort()
        for (s1, e1, _), (s2, _, _) in zip(rows, rows[1:]):
            assert s2 > e1, 'overlapping rows'
    return v4, v6


def partition(rows):
    """[(start, region)] covering the whole space; unassigned rows join the entry before them."""
    out = []
    for start, _, cc in rows:
        if cc is None:
            continue
        r = region(cc)
        if not out:
            out.append((0, r))
        elif out[-1][1] != r:
            out.append((start, r))
    return out


def rounded(entries, shift, total):
    """The partition in blocks of 1 << shift, each block taking the region that holds most of it (OTHER on a tie)."""
    block = 1 << shift
    mixed = collections.defaultdict(collections.Counter)
    runs = []
    for i, (s, r) in enumerate(entries):
        e = entries[i + 1][0] if i + 1 < len(entries) else total
        b0, b1 = s >> shift, (e - 1) >> shift
        if b0 == b1:
            mixed[b0][r] += e - s
            continue
        first, last = b0, b1
        if s & (block - 1):
            mixed[b0][r] += (b0 + 1) * block - s
            first = b0 + 1
        if e & (block - 1):
            mixed[b1][r] += e - b1 * block
            last = b1 - 1
        if last >= first:
            runs.append((first, last, r))
    for b, shares in mixed.items():
        best = max(shares.items(), key=lambda kv: (kv[1], kv[0] == OTHER))[0]
        runs.append((b, b, best))
    runs.sort()
    out, pos = [], 0
    for lo, hi, r in runs:
        assert lo == pos, 'a hole in the partition'
        pos = hi + 1
        if not out or out[-1][1] != r:
            out.append((lo, r))
    assert pos << shift == total
    return out  # starts in blocks


def check(data, runs):
    """Reads the first, a middle and the last key of every 16th run back through iprt.Reader (public keys only)."""
    reader = iprt.Reader(lambda pos, n: data[pos:pos + n])
    for family, entries in runs.items():
        bits = iprt.FAMILIES[family][0]
        keys = {}
        for i in range(0, len(entries), 16):
            s, r = entries[i]
            e = entries[i + 1][0] if i + 1 < len(entries) else 1 << bits
            for key in (s, (s + e) // 2, e - 1):
                if public(key, family):
                    keys[key] = r
        got = reader.lookup_sorted(family, sorted(keys))
        bad = [k for k, g in zip(sorted(keys), got) if g[0] != keys[k]]
        assert not bad, 'IPv%d: %d of %d keys read back wrong' % (family, len(bad), len(keys))


def public(key, family):
    return not any(lo <= key < hi for lo, hi in iprt.FAMILIES[family][3])


def download(day):
    name = 'dbip-country-lite-%04d-%02d.csv.gz' % (day.year, day.month)
    path = os.path.join(HERE, name)
    if not os.path.exists(path):
        urllib.request.urlretrieve('https://download.db-ip.com/free/' + name, path)
    return path


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else download(datetime.date.today())
    month = re.search(r'(\d{4})-(\d{2})', os.path.basename(path))
    year, mon = (int(month.group(1)), int(month.group(2))) if month else (0, 0)
    v4, v6 = read(path)
    blocks4 = rounded(partition(v4), 8, 1 << 32)
    blocks6 = rounded(partition(v6), 80, 1 << 128)
    sections = [iprt.build_family(4, blocks4), iprt.build_family(6, blocks6)]
    data = iprt.write_file(sections, year, mon)
    check(data, {4: blocks4, 6: blocks6})
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, 'wb') as f:
        f.write(data)
    print('%s: %d IPv4 and %d IPv6 runs, %d bytes, DB-IP %04d-%02d' % (
        OUT, len(blocks4), len(blocks6), len(data), year, mon))


if __name__ == '__main__':
    main()
