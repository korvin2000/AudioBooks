"""Collects every author and reader of the sites whose drawer has no Авторы/Исполнители (search/Provider rows with
authors = null) into sorted lists, the source of the app's built-in people lists (build_people.py).

    python tools/people/scrape.py BAZA_KNIG GOLOSOM ...   # crawl (resumable) into tools/people/raw/<ROW>.jsonl
    python tools/people/scrape.py --lists                 # raw/*.jsonl -> lists/<ROW>.authors.tsv, .readers.tsv

Needs requests and beautifulsoup4. Gentle by design: one request at a time per site, a 1.5-3 s pause after each,
a long back-off on errors (429, 5xx, a challenge page) and a stop when they persist; run sites in parallel, never a
site twice. A site with an index of its people is read from it; any other one from its whole book listing (every
person on every card), so the list holds exactly the people the catalog shows. A list line: name TAB url TAB books.
"""
import collections
import json
import os
import random
import re
import sys
import time
import unicodedata
from urllib.parse import quote, unquote, urljoin

import requests
from bs4 import BeautifulSoup

HERE = os.path.dirname(os.path.abspath(__file__))
RAW, LISTS = os.path.join(HERE, 'raw'), os.path.join(HERE, 'lists')
UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36'
DLE_BOOK = r'/\d+-[^/?#]+\.html$'

# task: (first page, page N, pager regex over hrefs | ('css', attr)); listing tasks name the author/reader/book links
SITES = {
    'BAZA_KNIG': dict(list=('https://baza-knig.top/', 'https://baza-knig.top/page/{}/', r'/page/(\d+)/$'),
                      author=r'^https://baza-knig\.top/xfsearch/autor/', reader=r'^https://baza-knig\.top/xfsearch/ispolnitel/',
                      book=DLE_BOOK),
    'KNIGOBLUD': dict(list=('https://www.knigoblud.club/', 'https://www.knigoblud.club/{}', ('.PageNavLine1 a[data-page]', 'data-page')),
                      cards='.bookListItem',     # a person is a random UUID: the list links the search instead
                      person='https://www.knigoblud.club/search?q={}&page='),
    'OTRUB': dict(authors=('https://otrub.in/authors.html', 'https://otrub.in/authors.html?p={}', r'authors\.html\?p=(\d+)$'),
                  readers=('https://otrub.in/readers.html', 'https://otrub.in/readers.html?p={}', r'readers\.html\?p=(\d+)$'),
                  author=r'^https://otrub\.in/authors/.', reader=r'^https://otrub\.in/readers/.'),
    'POLEKNIG': dict(list=('https://poleknig.com/', 'https://poleknig.com/?p={}', r'^https://poleknig\.com/\?p=(\d+)$'),
                     author=r'^https://poleknig\.com/authors/\d+$', reader=r'^https://poleknig\.com/readers/\d+$',
                     book=r'^https://poleknig\.com/books/\d+$'),
    'AKNIG_XYZ': dict(list=('https://s2.aknig.xyz/', 'https://s2.aknig.xyz/page/{}/', r'/page/(\d+)/$'),
                      author=r'^https://s2\.aknig\.xyz/xfsearch/author/', reader=r'^https://s2\.aknig\.xyz/xfsearch/reader/',
                      book=DLE_BOOK),
    'AUDIOKNIGA_LIFE': dict(list=('https://audiokniga.life/', 'https://audiokniga.life/page/{}/', r'/page/(\d+)/$'),
                            author=r'^https://audiokniga\.life/xfsearch/avtor/',
                            reader=r'^https://audiokniga\.life/xfsearch/ispolnitel/', book=DLE_BOOK),
    'BIBLUS': dict(list=('https://biblus.org/', 'https://biblus.org/?p={}', ('a[data-page]', 'data-page')),
                   author=r'^https://biblus\.org/authors/.', reader=r'^https://biblus\.org/readers/.',
                   book=r'^https://biblus\.org/(?!genres\.html|search\.html)[^/?#]+\.html$'),
    'AUDIOKNIGA_ONLINE': dict(list=('https://audiokniga-online.ru/', 'https://audiokniga-online.ru/page/{}/', r'/page/(\d+)/$'),
                              author=r'^https://audiokniga-online\.ru/xfsearch/avtor/',
                              reader=r'^https://audiokniga-online\.ru/xfsearch/ispolnitel/', book=DLE_BOOK),
    'SLUSHAT_KNIGI': dict(authors=('https://slushat-knigi.com/tags/', None, None),
                          author=r'^https://slushat-knigi\.com/tags/[^#]', reader=r'^$'),
    'BOOKISH': dict(list=('https://bookish.site/', 'https://bookish.site/page/{}/', r'/page/(\d+)/$'),
                    author=r'^https://bookish\.site/xfsearch/avtor/', reader=r'^https://bookish\.site/xfsearch/chitaet/',
                    book=DLE_BOOK),
    'FANTLAB': dict(readers=('https://fantlab.ru/dictors?letter=А', None, r'^/dictors\?letter=.$'),
                    author=r'^$', reader=r'^https://fantlab\.ru/dictor\d+$'),                 # narrators' cards
    'GOLOSOM': dict(list=('https://golosom.org/', 'https://golosom.org/{}', ('a[data-page]', 'data-page')),
                    author=r'^https://golosom\.org/author/.', reader=r'^https://golosom\.org/performer/.',
                    book=r'^https://golosom\.org/book/.'),
    'SLUSHKIN': dict(list=('https://slushkin.online/', 'https://slushkin.online/page/{}/', r'/page/(\d+)/$'),
                     author=r'^https://slushkin\.online/xfsearch/avtor/', reader=r'^https://slushkin\.online/xfsearch/ozv/',
                     book=DLE_BOOK),
    'AUDIOKNIGIVSE': dict(list=('https://audioknigivse.ru/', 'https://audioknigivse.ru/page/{}/', r'/page/(\d+)/$'),
                          author=r'^https://audioknigivse\.ru/xfsearch/avtor/',
                          reader=r'^https://audioknigivse\.ru/xfsearch/zvuk/', book=DLE_BOOK),
    'AUDIOKNIZHKA': dict(list=('https://audioknizhka.org/', 'https://audioknizhka.org/page/{}/', r'/page/(\d+)/$'),
                         author=r'^https://audioknizhka\.org/xfsearch/author/', reader=r'^$',   # readers are not linked
                         book=DLE_BOOK),
    'AUDIOKNIGI_CC': dict(list=('https://audioknigi.cc/', 'https://audioknigi.cc/page/{}/', r'/page/(\d+)/$'),
                          author=r'^https://audioknigi\.cc/xfsearch/avtor/',
                          reader=r'^https://audioknigi\.cc/xfsearch/ispolnitel/', book=DLE_BOOK),
    'MDS': dict(list=('https://mds-old.ru/?sort=new', 'https://mds-old.ru/?sort=new&page={}', None), pages=74,
                author=r'^https://mds-old\.ru/author/[^/]+$', reader=r'^$', book=r'^https://mds-old\.ru/author/[^/]+/.'),
    'STAROERADIO': dict(list=('https://staroeradio.ru/collection/0-9', None, r'^/collection/[^/?#]+$'),
                        titles='a:has(.mp3name)', book=r'^https://staroeradio\.ru/audio/\d+$',
                        person='https://staroeradio.ru/search?q={}'),   # no person pages: the search for the name
}
CHALLENGE = re.compile(r'cf-challenge|<title>Just a moment|ddos-guard\.net/|challenge-platform', re.I)

# staroeradio.ru: no person pages or links; the names are in the titles, split as Staroeradio.title splits them
SR_GROUPS = re.compile(r'(?:\s*[-,]?\s*\((?:[^()]|\([^()]*\))*\))+\s*$')
SR_GROUP = re.compile(r'\(((?:[^()]|\([^()]*\))*)\)')
SR_CAST = re.compile(r'(?:^|[\s.])(?:читае?т|читают|(?:исп|уч|вед|чит)(?![а-яё])\.?:?)\s*(.+)', re.I)
SR_NOTE = re.compile(r'(?:[,\s]+(?:[Зз]ап(?![а-яё])|ч\.\s*\d).*|,?\s+(?:18|19|20)\d\d\s*г?\.?)$')
SR_LR = re.compile(r'ЛР\s+(\D+?)\s*(?:\d{4}|\d{2})\s*г?\.?')
SR_LR_NEXT = re.compile(r'(\s[A-ZА-ЯЁ]\.?)\s+(?=[A-ZА-ЯЁ][a-zа-яё])')
SR_PARTICLES = {'де', 'ди', 'да', 'дю', 'фон', 'ван', 'дер', 'ле', 'ля', 'ла', 'эль', 'аль', 'бин', 'ибн'}
SR_NOT_A_PERSON = re.compile(r'(?:и\s+)?др\.?|\?+|и другие|авт\.\s.+|')


def sr_names(text):
    """{'a': authors, 'r': readers} of a staroeradio title 'Автор - Название - (исп.: А, Б), (Зап.: 1976г.)'."""
    full = re.sub(r'\s+', ' ', re.sub(r'</?b>', '', text)).strip()
    tail = SR_GROUPS.search(full)
    cut = tail.start() if tail else len(full)
    head = re.sub(r'[\s,-]+$', '', full[:cut])
    dash = head.find(' - ')
    who = re.sub(r'[\s,]+$', '', head[:dash]) if dash > 0 else ''
    if not all(one.strip() and len(one.split()) <= 4 and all(w.lstrip('(«"')[:1].isupper() or w in SR_PARTICLES
                                                              for w in one.split()) for one in who.split(',')):
        who = ''
    authors = [n.strip() for n in who.split(',') if n.strip()] if head else []
    readers = []
    for group in SR_GROUP.finditer(full[cut:] if head else ''):
        g = group.group(1).strip()
        cast, lr = SR_CAST.search(g), SR_LR.fullmatch(g)
        if cast:
            names = cast.group(1)
        elif lr and not re.match(r'[Зз]ап(?:ись)?(?![а-яё])', lr.group(1)):
            names = SR_LR_NEXT.sub(r'\1, ', lr.group(1))
        else:
            continue
        note = SR_NOTE.search(names)
        for one in re.split('[,;]', names[:note.start()] if note else names):
            name = re.sub(r'\s+и\s+др\.?$', '', one.strip())
            if re.fullmatch(r'[Аа]вт(?:оры?)?\.?', name):
                readers += authors
            elif not SR_NOT_A_PERSON.fullmatch(name):
                readers.append(name)
    return {'a': authors, 'r': readers}


def clean(name):
    name = ''.join(c for c in name if unicodedata.category(c) != 'Cf')     # BOM, LRM and other invisible marks
    return re.sub(r'\s+', ' ', name).strip(' ,;-')


def books(text):
    """A people index's book count: '12 книг', 'Книг: 46', '4'; 0 for 'нет книг', None where it gives none."""
    m = re.search(r'(\d+)', text)
    return int(m.group(1)) if m else 0 if 'нет' in text else None


class Crawl:
    def __init__(self, row):
        self.row, self.cfg = row, SITES[row]
        self.http = requests.Session()
        self.http.headers.update({'User-Agent': UA, 'Accept-Language': 'ru-RU,ru;q=0.9'})
        self.http.cookies.set('lang', 'ru')
        os.makedirs(RAW, exist_ok=True)
        self.out = os.path.join(RAW, row + '.jsonl')
        self.done = set()
        if os.path.exists(self.out):
            for line in open(self.out, encoding='utf-8'):
                r = json.loads(line)
                self.done.add((r['t'], r['n']))
        self.logf = open(os.path.join(RAW, row + '.log'), 'a', encoding='utf-8')

    def log(self, *a):
        line = time.strftime('%H:%M:%S ') + ' '.join(map(str, a))
        print(self.row, line, flush=True)
        self.logf.write(line + '\n'); self.logf.flush()

    def get(self, url):
        """The page, after the pause; retries with a growing back-off, raises after the last try."""
        for attempt in range(7):
            time.sleep(random.uniform(1.5, 3.0))
            try:
                r = self.http.get(url, timeout=60)
                if r.status_code == 404:
                    return None
                if r.status_code == 200 and not CHALLENGE.search(r.text[:20000]):
                    return BeautifulSoup(r.content, 'html.parser')
                wait = int(r.headers.get('Retry-After', '0') or 0) if r.status_code == 429 else 0
                why = f'HTTP {r.status_code}' + (' challenge' if r.status_code == 200 else '')
            except requests.RequestException as e:
                wait, why = 0, type(e).__name__
            wait = max(wait, 30 * 2 ** attempt)
            self.log('retry', url, why, f'in {wait}s')
            time.sleep(wait)
        raise SystemExit(f'{self.row}: giving up on {url}')

    def last(self, doc, base, pager, n):
        if pager is None:
            return self.cfg.get('pages', 1)     # mds-old: load-more paging, no pager links
        if isinstance(pager, tuple):
            nums = [a.get(pager[1], '') for a in doc.select(pager[0])]
        else:
            nums = [m.group(1) for a in doc.find_all('a', href=True) for m in [re.search(pager, urljoin(base, a['href']))] if m]
        return max([n] + [int(x) for x in nums if x.isdigit()])

    def links(self, doc, base):
        """[kind, url, name, book url or None] of every person link on a listing page."""
        cfg, out = self.cfg, []
        if 'titles' in cfg:                                  # staroeradio: the names are in the titles, not links
            for a in doc.select(cfg['titles']):
                book = urljoin(base, a['href']).split('#')[0]
                if re.search(cfg['book'], book):
                    for kind, names in sr_names(a.select_one('.mp3name').get_text()).items():
                        out += [[kind, cfg['person'].format(quote(n, safe='')), n, book] for n in names]
            return out
        if 'cards' in cfg:                                   # knigoblud: uuid links, told apart by the ✍️ / 🎙️ marks
            for card in doc.select(cfg['cards']):
                cover = card.select_one('a[href]')
                book = urljoin(base, cover['href']) if cover else None
                for block in card.select('.bookListItemMetaBlock'):
                    kind = None
                    for el in block.children:
                        t = getattr(el, 'name', None)
                        if t == 'span':
                            s = el.get_text()
                            kind = 'a' if '✍' in s else 'r' if '🎙' in s else None
                        elif t == 'a' and kind:
                            out.append([kind, urljoin(base, el['href']), clean(el.get_text(' ')), book])
            return out
        book_rx = re.compile(cfg['book'])
        for a in doc.find_all('a', href=True):
            url = urljoin(base, a['href']).split('#')[0]
            plain = unquote(url)
            kind = 'a' if re.search(cfg['author'], plain) else 'r' if re.search(cfg['reader'], plain) else None
            if not kind:
                continue
            book = None
            for up in a.parents:                             # the card: the closest box with a book link
                found = {urljoin(base, b['href']).split('#')[0] for b in up.find_all('a', href=True)}
                found = {b for b in found if book_rx.search(b)}
                if found:
                    book = found.pop() if len(found) == 1 else None
                    break
            out.append([kind, url, clean(a.get_text(' ')), book])
        return out

    def entries(self, doc, base, kind):
        """[kind, url, name, count] of a people index page."""
        rx, out = self.cfg['author' if kind == 'a' else 'reader'], []
        for a in doc.find_all('a', href=True):
            url = urljoin(base, a['href']).split('#')[0]
            if not re.search(rx, unquote(url)):
                continue
            named = a.select_one('[title], b')                # otrub: <div title=name>, audiokniga-online: <b>
            name = clean(named.get('title') or named.get_text(' ')) if named else clean(a.get_text(' '))
            rest = ' '.join([a.get('title', ''), a.get_text(' ')] + [s.get_text(' ') for s in a.find_next_siblings('span', limit=1)])
            out.append([kind, url, name, books(rest.replace(named.get_text(' ') if named else a.get_text(' '), '', 1))])
        return out

    def run(self, max_pages=None, start=1):
        for task in ('readers', 'authors', 'list'):           # the short indexes first
            if task not in self.cfg:
                continue
            first, page, pager = self.cfg[task]
            n = last = start if task == 'list' else 1   # --from=N: a second process takes the tail
            prev = None
            while n <= last and (max_pages is None or n <= max_pages):
                if (task, n) in self.done and n > 1 and page:    # pages named by links are found as they are read
                    n += 1
                    continue
                url = first if n == 1 else page.format(n) if page else pages[n - 1]
                doc = self.get(url)
                if doc is None:
                    self.log('404', url)
                    if n > 1 and (task, n) not in self.done:
                        self.save(task, n, url, [], 0)
                    n += 1
                    continue
                if page is None and pager and n == 1:          # pages named by links (fantlab's letters)
                    found = (urljoin(url, a['href']) for a in doc.find_all('a', href=True) if re.search(pager, a['href']))
                    pages = list(dict.fromkeys([first] + list(found)))
                    last = len(pages)
                elif page:
                    last = self.last(doc, url, pager, last)
                if task == 'list':
                    items = self.links(doc, url)
                    seen = {i[3] for i in items if i[3]}
                    if seen and seen == prev:                # a page past the end repeats the last one
                        self.log('end at', n - 1, 'of', last)
                        break
                    prev, cards = seen, len(seen)
                else:
                    items = self.entries(doc, url, 'a' if task == 'authors' else 'r')
                    cards = len(items)
                    if page is None and pager and sum(1 for i in items if i[2]) >= 250:   # fantlab: 250 a page
                        k = int(url.rsplit('&page=', 1)[1]) + 1 if '&page=' in url else 2
                        pages.insert(n, url.split('&page=')[0] + f'&page={k}')
                        last += 1
                if not items:
                    self.log('empty', url)
                if (task, n) not in self.done:
                    self.save(task, n, url, items, cards)
                if n % 100 == 0 or n == last:
                    self.log(task, n, '/', last)
                n += 1
        self.log('done')

    def save(self, task, n, url, items, cards):
        with open(self.out, 'a', encoding='utf-8') as f:
            f.write(json.dumps({'t': task, 'n': n, 'u': url, 'c': cards, 'l': items}, ensure_ascii=False) + '\n')
        self.done.add((task, n))


def order(name):
    """Russian-first, case- and ё-blind, as the app's ru collator roughly sorts."""
    k = name.casefold().replace('ё', 'е')
    first = next((c for c in k if c.isalpha()), '')
    return ('а' <= first <= 'я') * -2 + ('a' <= first <= 'z') * -1, k


def lists():
    os.makedirs(LISTS, exist_ok=True)
    for row in SITES:
        path = os.path.join(RAW, row + '.jsonl')
        if not os.path.exists(path):
            continue
        names = collections.defaultdict(collections.Counter)  # (kind, url) -> name counts
        cards = collections.defaultdict(set)                    # (kind, url) -> books (listing)
        index = {}                                              # (kind, url) -> count (people index)
        for line in open(path, encoding='utf-8'):
            r = json.loads(line)
            for kind, url, name, extra in r['l']:
                name = clean(name)                                  # names kept before clean() learnt more
                if not name:
                    continue
                names[kind, url][name] += 1
                if r['t'] == 'list':
                    if extra:
                        cards[kind, url].add(extra)
                else:
                    index[kind, url] = extra
        for kind, file in (('a', 'authors'), ('r', 'readers')):
            rows = []
            for (k, url), counter in names.items():
                if k != kind:
                    continue
                n = index.get((k, url)) or len(cards[k, url])
                if index.get((k, url)) == 0 and not cards[k, url]:
                    continue                                    # in the index, but no book on the site
                rows.append((counter.most_common(1)[0][0], url, n))
            if not rows:
                continue
            if 'person' in SITES[row]:                          # one search per name: namesakes merge
                merged = {}
                for name, url, n in rows:
                    m = merged.setdefault(name, [name, SITES[row]['person'].format(quote(name, safe='')), 0])
                    m[2] += n
                rows = [tuple(m) for m in merged.values()]
            rows.sort(key=lambda x: (order(x[0]), x[1]))
            with open(os.path.join(LISTS, f'{row}.{file}.tsv'), 'w', encoding='utf-8', newline='\n') as f:
                for name, url, n in rows:
                    f.write(f'{name}\t{url}\t{n}\n')
            print(row, file, len(rows))


if __name__ == '__main__':
    args = sys.argv[1:]
    if args == ['--lists']:
        lists()
    else:
        limit = None
        start = 1
        if args and args[0].startswith('--pages='):
            limit = int(args.pop(0)[8:])
        if args and args[0].startswith('--from='):
            start = int(args.pop(0)[7:])
        for row in args:
            Crawl(row).run(limit, start)
