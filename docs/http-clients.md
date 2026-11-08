# HTTP clients in AudioBooks

Reference for humans and LLM agents: which HTTP stacks the app uses, where each one lives, what goes through it, and
the rules that keep them consistent. Paths are relative to `app/src/main/java/com/fanok/audiobooks/` unless they start
with `app/`. Symbols are given as `Class.member`; line numbers are deliberately omitted (they drift).

## TL;DR

- There is **no single client**. There are four stacks:
  1. **`net/SiteHttp`** — jsoup over Android `HttpURLConnection`. Every **site page**: listings, book pages, chapters
     lists, search, comments, series, JSON pages. This is the main "book" path; all parsers in `source/` use it.
     Its headers come from **`net/BrowserHeaders`** (the browser fingerprint below).
  2. **Shared OkHttp client** — built once in `App.onCreate`, returned by `App.getOkHttpClient()`. **Audio
     playback, downloads, Picasso images, embedded covers, narrator cards, update checks.**
  3. **Glide** — its own `HttpURLConnection` fetcher (no OkHttp integration). **Cover images in lists** and a few
     other images.
  4. **WebView** — Chromium's own network stack. **Browser checks** (`activity/ActivityCookies`) and the forum login
     (`activity/ForumLoginActivity`).
- A few **special-purpose OkHttp clients** are built separately on purpose (region check, cloud sync, proxy search and
  proxy test). They never touch the sites' content.
- **Routing (direct vs proxy) is process-wide**: `net/AppProxy` installs a `java.net.ProxySelector` and an
  `Authenticator`, so stacks 1–3 follow the user's proxy setting per host without per-request code. **WebView ignores
  it.**
- **Every request says it is a browser, completely** (`net/BrowserHeaders`): a `User-Agent` that names Chrome but
  arrives without the headers Chrome sends on every request (`Accept`, `Accept-Language`, `Sec-Ch-Ua*` client hints,
  `Sec-Fetch-*`) is exactly what an anti-bot wall treats as a bot. See "The browser the requests claim to be".

## The browser the requests claim to be (`net/BrowserHeaders`)

**File:** `net/BrowserHeaders.java`. Built, never fetched: the headers are derived from the `User-Agent` in use, so
user agent and fingerprint always describe the same browser.

| Method | Who uses it | What it adds |
|---|---|---|
| `page(userAgent)` | `SiteHttp.asBrowser`, so every site page | `User-Agent`, `Accept` (HTML), `Accept-Language`, `Sec-Ch-Ua`, `Sec-Ch-Ua-Mobile`, `Sec-Ch-Ua-Platform`, `Sec-Fetch-Dest: document`, `Sec-Fetch-Mode: navigate`, `Sec-Fetch-Site: cross-site` (the app's referrer is a search engine), `Sec-Fetch-User: ?1`, `Upgrade-Insecure-Requests: 1` |
| `resource(userAgent, image)` | The shared OkHttp client's defaults interceptor (`App.defaultHeaders`), `net/Covers.glide` | `Accept` (image or `*/*`), `Accept-Language`, the same client hints. No `User-Agent`: the caller sets it (it may be a site's own) |

| `xhr(sameOrigin)` | `SiteHttp.xhr(url, referer)` (an adapter's playlist/AJAX call), applied by `SiteHttp.asXhrIfAjax` in `SiteHttp.executeOnce` to every request carrying `X-Requested-With` | Fetch metadata of a script's request: `Sec-Fetch-Dest: empty`, `Sec-Fetch-Mode: cors`, `Sec-Fetch-Site: same-origin` (Referer on the same origin) or `cross-site`; `Accept: */*` in place of the page's; `Sec-Fetch-User` and `Upgrade-Insecure-Requests` removed |

**Page or XHR is decided per request, not per site.** One site serves both: its pages must look like a navigation (the
anti-bot wall checks exactly that), its AJAX endpoints like a script's request — `audioknigi.pro`'s playlist answers a
"navigation" with `403 {"error":"same_origin_required"}`. The source adapter declares the kind at the call: a page through
`SiteHttp.connect`/`get`, an AJAX endpoint through `SiteHttp.xhr(url, referer)` (`AudioknigiPro.tracks`,
`AudioknigiFun.tracks`, `Akniga.post`), which adds `X-Requested-With: XMLHttpRequest` and the page's Referer;
`SiteHttp` derives the rest of the metadata from those two. A per-adapter switch would be wrong: the same adapter makes
both kinds.

`net/ProxyFinder.OkNetwork.site` uses `page(Consts.USER_AGENT)` too: a candidate proxy is judged by the request the
app will actually make to that site, not by a bare-`User-Agent` probe that a wall may answer differently.

- The client hints are the **low-entropy** ones Chrome sends with every request; they are derived from the agent:
  desktop Chrome/Windows → `?0` + `"Windows"`, a WebView/Chrome on Android → `?1` + `"Android"` and the
  `Android WebView` brand, and the `v=` brand versions always equal the agent's major version.
- **Firefox** (`Firefox/…`): no client hints at all (Firefox has no UA-CH), Firefox's `Accept-Language`
  (`ru-RU,ru;q=0.8,en-US;q=0.5,en;q=0.3`) and `Accept`.
- A Chrome older than 100 (`DownloadUtil.DESKTOP_UA`, Chrome 89 for a site's media) gets no hints: that version sent
  them in a form of its own, so inventing a modern list would be the mismatch this class exists to avoid.
- `Accept-Encoding` is deliberately **not** set anywhere: jsoup asks for `gzip` itself and decodes it, and on OkHttp a
  caller-set `Accept-Encoding` would switch off the stack's transparent gzip (downloads would get compressed bytes).

## Why the requests impersonate a browser — a deliberate, owner-accepted risk

The app's HTTP clients present themselves as an ordinary desktop browser (a full Chrome fingerprint, or Firefox/mobile
when a site's verified session uses one) instead of a neutral client. This is a conscious choice by the project owner,
not an accident, and it is documented here so the trade-off is on the record.

**Why.** Some free sources (notably `audioboo.org`) sit behind a Cloudflare anti-bot wall whose checks are, for a small
free hobby reader of free public audiobooks, far stricter than the traffic warrants. The site's owner was contacted,
agreed the checks are excessive, promised to relax them, and **in the meantime suggested** that the app identify its
HTTP client as a normal browser so its users keep reaching the free books. `net/BrowserHeaders` implements exactly that:
a `User-Agent` that names a browser plus every header that browser actually sends, so the claim is internally consistent
(a half-disguise — a browser `User-Agent` with none of the matching headers — is what a wall flags, and would help no
one).

**What the owner accepts.**
- This is browser impersonation to pass an anti-bot challenge. Even with the site owner's blessing for `audioboo.org`,
  the same browser fingerprint now goes to **every** source the app talks to (it is set in the shared stacks, not per
  site), so the posture is app-wide, not a one-site exception. The owner accepts this for a non-commercial app that only
  fetches freely published content.
- It may run against the letter of Cloudflare's or a site's terms even where the content itself is free. The owner
  judges the benefit to users of free books to outweigh that for this project and takes responsibility for it.
- The disguise is intended as **temporary**, pending the `audioboo.org` owner removing the excessive checks. When that
  happens the fingerprint can be dropped back to a neutral client (or kept, at the owner's discretion).
- It is a maintenance cost: `Consts.USER_AGENT` and its derived client hints must be bumped together as browser
  versions move on, or the fingerprint ages into a new tell.

**What it is _not_.** No credentials, tokens, personal data or user identifiers are sent to gain access — only standard
request headers that a real browser would send anyway. The change is confined to how requests describe themselves; it
does not bypass authentication, defeat payment, or touch any private or paid resource. The existing privacy invariants
below (site cookies/UA stay on their own host; proxy logins stay on the proxy; cloud-sync and region checks stay
direct) are unchanged.

## Decision table: which stack to use

| You need to… | Use | Why |
|---|---|---|
| Fetch/parse an HTML page of a source site | `SiteHttp.get(url)` / `SiteHttp.connect(url)` + `SiteHttp.execute` + `SiteHttp.parse` | Site cookies, UA, challenge detection, 429 handling, POST-only sites, redirect rules |
| Fetch a JSON API of a source site | `SiteHttp.getJson(url)` / `SiteHttp.json(connection)` | Same rules as pages; body is checked to look like JSON |
| Submit a site's search form (DLE) | `SiteHttp.postForm(url)` | Moves the query string into a POST form |
| Stream or download audio | `util/DownloadUtil.upstreamFor(...)` (media3 `OkHttpDataSource` on the shared client) | Per-site route (`AppProxy.mediaClient`), media cookies/headers, full-body guard |
| Load a cover into an `ImageView` (non-list) | Picasso (`Picasso.get()`) | Goes through the shared OkHttp client: UA, Referer, proxy, image guard |
| Load a cover in a list | Glide + `net/Covers.glide(url)` | Adds the owning site's Referer when it needs one |
| Any other app-level request (APIs, updates) | `App.getOkHttpClient()` (or `.newBuilder()` of it) | Shared pool, cookies, proxy, UA defaults |
| Let the user pass a site's browser check | `activity/ActivityCookies` (`ActivityCookies.intent(context, provider)`) | Real browser engine; the app reuses the verified session |
| Something that must never use the app's proxy or cookies | A dedicated client like `region/DirectNet` or `sync/CloudSync.http()` | See "Special-purpose clients" |

## 1. `SiteHttp` — site pages (jsoup)

**File:** `net/SiteHttp.java`. **Transport:** `Jsoup.connect` → Android `HttpURLConnection` (platform OkHttp/Conscrypt
under the hood, not the app's OkHttp).

**Used by:** every `source/*` parser (`SourceParser.fetch`, `loadListing`, `tracks`, `comments`, `series`, search),
`model/*` (`AudioModel`, `BookDescriptionModel` through `model/BookPages`), `search/*` (source probes, ranking),
`net/ProxyFinder` site checks for `postsPages()` sites, `activity/ActivityCookies` (verification request).

### API

| Method | Purpose |
|---|---|
| `connect(url)` | Pre-configured jsoup `Connection` (see defaults below) for callers that add cookies/headers/data |
| `get(url)` | GET (or POST for `postsPages()` sites) + `parse`; the document's base URI is the final URL |
| `postForm(url)` | POST the URL's query as a form to its path (DLE search/sort forms) |
| `execute(connection)` | Runs a connection; routes `postsPages()` sites through `executePosted`; records response cookies in `JAR` |
| `executePosted(connection, server)` | POST with manual redirect following on the same site (jsoup would turn POST into GET) |
| `body(connection)` | Raw body as text |
| `getJson(url)`, `json(connection)` | JSON pages (`net/JsonPage`), rejects HTML/challenge answers |
| `parse(response[, maxBytes])` | HTML → `Document`; throws `CookesExeption` on a challenge page (`isChallenge`: `cf-mitigated` header or challenge title) |
| `throwForStatus(response)` | HTTP errors as `HttpStatusException` |
| `forget(url, name)` | Drops one cookie the host set in `JAR` (used when a newer session must win) |

### Defaults set by `connect(url)`

- `User-Agent: Consts.USER_AGENT` (desktop Chrome on Windows) and every other header of that browser
  (`SiteHttp.asBrowser` → `BrowserHeaders.page`), `Referer: https://www.google.com/`.
- Timeout `TIMEOUT_MS` (10 s), or `provider.parser.timeoutMs()` for a known site; body limit `MAX_BODY_BYTES` (4 MB);
  `ignoreHttpErrors(true)` (status is checked by `parse` / `throwForStatus`).
- **Cookies, in this order:** cookies the host set earlier (`JAR`, per host, process lifetime) → the site's own cookies
  from `provider.parser.cookies()` for names the jar does not hold (sent only to `provider.server`'s host).
- **User-Agent override:** `provider.parser.userAgent()` when non-empty, own host only (audioboo with a verified
  browser session; null for every other site → the default UA). The whole fingerprint follows it
  (`SiteHttp.userAgentOf` → `BrowserHeaders.page`), because Cloudflare binds `cf_clearance` to IP + User-Agent and a
  desktop Chrome's client hints under the WebView's agent would belie it.

### Site-specific behaviour hooked into it

- `SourceParser.postsPages()` (true for `source/Audioboo`): all page requests are POSTed (`executePosted`); its
  Cloudflare challenged GETs. Redirects are followed by hand on the same site only, up to `MAX_REDIRECTS`.
- Rate limits: a 429 with `Retry-After` marks the host in `LIMITED_UNTIL`; requests are refused locally until then
  (`net/RateLimitedException`, surfaced as `ProviderPage.Status.RATE_LIMITED`).
- Challenges: `CookesExeption` → `ProviderPage.Status.BLOCKED` → UI message `R.string.site_challenge`, or for sites
  whose check can be passed in a browser (`ActivityCookies.passes(provider)`: audioboo, baza-knig) an offer
  «Пройти» (`BooksView.showSiteCheckOffer`, `Description.showSiteCheckOffer`).
- In auto-proxy mode a challenged/blocked page reports the route as suspect (`AppProxy.reportSuspect`), which may
  re-verify the proxy.
- `model/BookPages.get(url, fetch)` coalesces identical book-page requests for 60 s (description, chapters and
  audioboo comments share one request).

## 2. Shared OkHttp client — media, images, app-level requests

**Built in:** `App.onCreate` (`okhttp3.OkHttpClient.Builder` with `cookieJar(COOKIE_JAR)`), stored in
`App.okHttpClientInstance`. **Getters:** `App.getOkHttpClient()`; `App.getDownloadHttpClient()` (same client restricted
to HTTP/1.1 so parallel download segments get separate connections — faster on per-connection-throttled CDNs).
**Pool reset:** `App.evictConnections()` after a proxy change.

### Configuration (in order)

1. **Application interceptor — image guard.** A `GET` for an image path (`App.isImage`: `.jpg .jpeg .png .gif .webp`)
   to a host where `policy/DefaultCover.replaces(url)` is true (audioboo) is answered locally with a synthetic `404`,
   never sent. Reason: GETs for audioboo images make its Cloudflare refuse the app's page requests too.
2. **Application interceptor — defaults.** Adds `User-Agent: Consts.USER_AGENT`, the rest of that browser
   (`BrowserHeaders.resource`: `Accept`, `Accept-Language`, client hints — derived from the agent actually in use, so a
   site's own `DownloadUtil.DESKTOP_UA` or the WebView agent of the forum route gets its own) and the owning site's
   `Referer` (`Provider.refererOf(host)`), each only when the caller did not set it.
3. `eventListener` → `AppProxy.reportConnectFailure` for failed proxy connects; `fastFallback(false)`.
4. `proxyAuthenticator(AppProxy::proxyLogin)` and network interceptor `AppProxy::keepProxyLoginToProxy` (the built-in
   proxy's login is never sent to a site).
5. Cookie jar `net/MemoryCookieJar` (`App.getCookieJar()`): process-lifetime, per host/path/scheme;
   `MemoryCookieJar.with(server, cookies)` adds a site's media cookies for one playback/download.

### Users

| Caller | What | Notes |
|---|---|---|
| `util/DownloadUtil.upstreamFor` | media3 `OkHttpDataSource` for playback and downloads | `AppProxy.mediaClient(provider, client)` picks the book's site route for its audio CDNs; `SourceParser.mediaCookies/mediaHeaders`; `FullBodyDataSource` guards cut bodies |
| `util/DownloadUtil.downloaderFactory` | media3 downloads | `App.getDownloadHttpClient()` (HTTP/1.1) |
| Picasso (singleton set in `App.onCreate` with `OkHttp3Downloader`) | Covers on the book page, full-screen cover, other-books strip, favorites/history sheets, downloads list, comments avatars, notification/media-session artwork (`service/MediaPlayerService.loadArtwork`), car mode | Image guard applies |
| `policy/EmbeddedCover` | Range read of the ID3 tag of a book's first MP3 (archive.org for audioboo) | Through `AppProxy.mediaClient` + 30 s call timeout; one book at a time |
| `util/DownloadStorageHelper.exportCover` | `cover.jpg` next to exported chapters | Image guard applies |
| `search/Narrators` | Narrator cards (fantlab) | |
| `update/Updater` | Update check and APK download | Tries `AppProxy.updateRoutes(base)`; the 4pda forum route uses `forumClient` = this client + WebView cookies + the WebView's UA |

## 3. Glide — list covers

**No Glide module / OkHttp integration** in the project: Glide uses its default `HttpUrlFetcher`
(`HttpURLConnection`, `Dalvik/…` User-Agent, no cookies). It still follows the process-wide `ProxySelector`.

- `net/Covers.glide(url)` returns the URL, or a `GlideUrl` with the owning site's `Referer` when
  `SourceParser.referer()`/`hosts()`/`refererHosts()` say the CDN needs it; that `GlideUrl` also replaces Glide's
  `Dalvik/…` agent with `Consts.USER_AGENT` and its image headers (`BrowserHeaders.resource`). Plain-URL covers keep
  Glide's own agent and cache key.
- Users: `adapter/BooksListAddapter` (list covers), `adapter/SeriesListAddapter` (series rows),
  `activity/NarratorActivity`; `SourcesAdapter`, `SourceCardFragment`, `SearchableActivity` load **bundled assets**
  (`SourceInfo.uri`), not network.
- The OkHttp image guard does **not** see Glide. Audioboo URLs are therefore never handed to Glide: the call sites
  branch on `DefaultCover.replaces(photo)` and show `DefaultCover.drawable(...)` (gradient, or the embedded cover once
  `policy/EmbeddedCover` has read one).

## 4. WebView — browser checks and logins

| Screen | Purpose | Cookies / UA |
|---|---|---|
| `activity/ActivityCookies` | The user passes a site's browser check; the app verifies the session with one `SiteHttp` request and stores it | baza-knig: `PHPSESSID`, WebView UA forced to `Consts.USER_AGENT`, stored in prefs `cookes_baza_knig` → `BazaKnig.cookies()`. audioboo (`EXTRA_PROVIDER=AUDIOBOO`): `cf_clearance` + the WebView's **own** UA (Cloudflare binds clearance to IP + UA), prefs `cookies_audioboo` / `user_agent_audioboo` → `Audioboo.cookies()` / `Audioboo.userAgent()`; closes with `RESULT_OK` when opened from an error offer |
| `activity/ForumLoginActivity` | 4pda login for updates | WebView cookies, read by `Updater.forumClient` |

WebView does **not** use `AppProxy`'s selector: if a site is routed through the proxy, a browser-check session is
bound to a different IP than the app's requests (the check screen says so).

## 5. Special-purpose clients (deliberately separate)

| Where | Purpose | Why separate |
|---|---|---|
| `region/DirectNet` | Region/geolocation probes for `region/RegionCheck` | `Proxy.NO_PROXY`, no cookies, no redirects, 2–3 s timeouts: must measure the device's own network |
| `sync/CloudSync.http()` | Cloud sync (tokens) | `Proxy.NO_PROXY`, no cookies: tokens never pass through the sites' proxies |
| `net/ProxyFinder.OkNetwork` (`base()`, `lists()`, `through(candidate)`) | Fetching public proxy lists and testing candidates | Explicit proxy per request, no pool reuse, strict timeouts. Its site check sends the browser headers of a page (`BrowserHeaders.page`), so a proxy that passes it is one the app's own page requests pass through |
| `net/AppProxy.check(config, url)` | "Test" button of the proxy settings | Only the typed proxy and its login |

## Routing: `net/AppProxy`

- Installed once as the process-wide `ProxySelector` + `Authenticator`; modes `off`, `builtin` (own proxy,
  `net/ProxyConfig`), `auto` (verified public proxy from `net/ProxyFinder`).
- Per-source route: a site set to go direct (`AppProxy.PREF_DIRECT`, region defaults via `applyRegionDefaults`)
  bypasses the proxy for its hosts and `SourceParser.hosts()`; other hosts (shared CDNs) follow the mode.
- `AppProxy.mediaClient(provider, client)` gives media requests the book's site route; `AppProxy.updateRoutes` gives
  the update check several routes to try.
- Applies to `SiteHttp`, the shared OkHttp client and Glide. Not to WebView. Not to the special clients that set
  `Proxy.NO_PROXY` or an explicit proxy.

## Site-specific rules that cut across stacks

| Rule | Where |
|---|---|
| Sites that need POST for pages | `SourceParser.postsPages()` → `SiteHttp.executePosted` |
| Site cookies for pages | `SourceParser.cookies()` (own host only, jar wins) |
| Site User-Agent for pages | `SourceParser.userAgent()` (own host only; null = `Consts.USER_AGENT`) |
| Site cookies/headers for media | `SourceParser.mediaCookies(bookUrl)`, `mediaHeaders(bookUrl)` → `DownloadUtil` |
| Referer for images/media | `SourceParser.referer()`, `hosts()`, `refererHosts()` → `Provider.refererOf` (OkHttp interceptor) and `Covers.glide` |
| Covers never requested from a site | `policy/DefaultCover.replaces(url)` (audioboo): call sites + OkHttp image guard; covers come from `policy/EmbeddedCover` (ID3 art of the first MP3) or the gradient default |
| Browser-check session | `ActivityCookies` → `Audioboo.setClearance` / `Consts.setBazaKnigCookies`, loaded at start in `App.onCreate` |

## Invariants (do not break)

1. Without a stored browser session, audioboo page requests claim no other browser than the app's default one:
   `Consts.USER_AGENT` with that agent's headers, no `cf_clearance`, POST (`AudiobooClearanceTest`); with a session they
   carry the session browser's agent **and** that agent's hints.
2. No GET for an audioboo image ever leaves the app (OkHttp image guard + `DefaultCover.replaces` branches for Glide).
3. A site's own cookies and User-Agent go only to its own host (`SiteHttp.connect`, `MemoryCookieJar.with`).
4. Proxy logins go only to the proxy (`AppProxy.keepProxyLoginToProxy`, `ProxyOnlyAuthenticator`).
5. Cloud sync and region checks never use the app's proxy or cookies.

## Where to change things

| Change | Edit |
|---|---|
| Default UA / Referer for pages | `Consts.USER_AGENT`, `SiteHttp.REFERRER` |
| Language, `Accept`, client hints, `Sec-Fetch-*` (any stack) | `net/BrowserHeaders` (`SiteHttp.ACCEPT_LANGUAGE` is its Chrome language) |
| Default UA for the shared client | Interceptor in `App.onCreate` (falls back to `Consts.USER_AGENT`) |
| A new site needing cookies/UA/POST/Referer | Override the matching `SourceParser` default in `source/<Site>.java`; no client code changes |
| A new site whose images must not be requested | `DefaultCover.replaces` (and keep its URLs away from Glide) |
| Timeouts | `SiteHttp.TIMEOUT_MS`, `SourceParser.timeoutMs()`, or the special client's builder |
| Add a site to the browser-check offer | `ActivityCookies.passes` + the cookie/UA handling in `ActivityCookies.verifyCurrentSession` |

## Tests touching the clients

`app/src/test/java/com/fanok/audiobooks/`: `net/SiteHttpTest`, `net/BrowserHeadersTest`, `AppDownloadNetworkTest`,
`source/AudiobooClearanceTest`,
`policy/EmbeddedCoverTest`, `policy/DefaultCoverTest`, `activity/ActivityCookiesTest`, `source/SourceContractTest`,
`source/LiveDumpTest` (live, opt-in).
