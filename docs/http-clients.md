# HTTP clients in AudioBooks

Reference for humans and LLM agents: which HTTP stacks the app uses, where each one lives, what goes through it, and
the rules that keep them consistent. Paths are relative to `app/src/main/java/com/fanok/audiobooks/` unless they start
with `app/`. Symbols are given as `Class.member`; line numbers are deliberately omitted (they drift).

## TL;DR

- There is **no single client**. There are four stacks:
  1. **`net/SiteHttp`** — jsoup over Android `HttpURLConnection`. Every **site page**: listings, book pages, chapters
     lists, search, comments, series, JSON pages. This is the main "book" path; all parsers in `source/` use it.
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

- `User-Agent: Consts.USER_AGENT` (desktop Chrome on Windows), `Referer: https://www.google.com/`,
  `Accept-Language: ru-RU,…`.
- Timeout `TIMEOUT_MS` (10 s), or `provider.parser.timeoutMs()` for a known site; body limit `MAX_BODY_BYTES` (4 MB);
  `ignoreHttpErrors(true)` (status is checked by `parse` / `throwForStatus`).
- **Cookies, in this order:** cookies the host set earlier (`JAR`, per host, process lifetime) → the site's own cookies
  from `provider.parser.cookies()` for names the jar does not hold (sent only to `provider.server`'s host).
- **User-Agent override:** `provider.parser.userAgent()` when non-null, own host only (audioboo with a verified browser
  session; null for every other site → the default UA).

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
2. **Application interceptor — defaults.** Adds `User-Agent: Consts.USER_AGENT` and the owning site's `Referer`
   (`Provider.refererOf(host)`) only when the caller did not set them.
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
  `SourceParser.referer()`/`hosts()`/`refererHosts()` say the CDN needs it.
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
| `net/ProxyFinder.OkNetwork` (`base()`, `lists()`, `through(candidate)`) | Fetching public proxy lists and testing candidates | Explicit proxy per request, no pool reuse, strict timeouts |
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

1. Without a stored browser session, audioboo page requests are byte-for-byte the old ones: `Consts.USER_AGENT`, no
   `cf_clearance`, POST (`AudiobooClearanceTest`).
2. No GET for an audioboo image ever leaves the app (OkHttp image guard + `DefaultCover.replaces` branches for Glide).
3. A site's own cookies and User-Agent go only to its own host (`SiteHttp.connect`, `MemoryCookieJar.with`).
4. Proxy logins go only to the proxy (`AppProxy.keepProxyLoginToProxy`, `ProxyOnlyAuthenticator`).
5. Cloud sync and region checks never use the app's proxy or cookies.

## Where to change things

| Change | Edit |
|---|---|
| Default UA / Referer / language for pages | `Consts.USER_AGENT`, `SiteHttp.REFERRER`, `SiteHttp.ACCEPT_LANGUAGE` |
| Default UA for the shared client | Interceptor in `App.onCreate` (falls back to `Consts.USER_AGENT`) |
| A new site needing cookies/UA/POST/Referer | Override the matching `SourceParser` default in `source/<Site>.java`; no client code changes |
| A new site whose images must not be requested | `DefaultCover.replaces` (and keep its URLs away from Glide) |
| Timeouts | `SiteHttp.TIMEOUT_MS`, `SourceParser.timeoutMs()`, or the special client's builder |
| Add a site to the browser-check offer | `ActivityCookies.passes` + the cookie/UA handling in `ActivityCookies.verifyCurrentSession` |

## Tests touching the clients

`app/src/test/java/com/fanok/audiobooks/`: `net/SiteHttpTest`, `AppDownloadNetworkTest`, `source/AudiobooClearanceTest`,
`policy/EmbeddedCoverTest`, `policy/DefaultCoverTest`, `activity/ActivityCookiesTest`, `source/SourceContractTest`,
`source/LiveDumpTest` (live, opt-in).
