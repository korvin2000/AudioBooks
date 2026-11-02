# Book search rework — progress, decisions and hand-over notes

Written 2 November 2026 at the end of the session that implemented the search rework. Read this first in any
session that continues the search work, then `git log --oneline -15` and `git status`.

Related documents (all in the repository root or `tasks/`):

| Document | What it is |
|---|---|
| `search-refactoring.md` | The specification (Russian). §3 weaknesses W01–W37, §5 query model, §6 post-processing, §7 per-source strategy, §8 snapshot, §9 UI, §11 stages, §14 owner decisions (binding), appendix A algorithms, appendix B test corpus. |
| `search-optimization.md` | Codex analysis (Russian): problems F01–F16, error reactions §9, UI §11, acceptance scenarios A01–A33 §13. |
| `tasks/search-plan.md` | Plan and status written in this session (Russian): concept, decision table, done/next lists, risks. |
| `CLAUDE.md` | Build commands and the emulator safety rules — mandatory. |

## 1. Where things stand (git)

| Ref | Content | State |
|---|---|---|
| `master` @ `0442ce9` | Merge of `feature/search-rework` (`b6f9e3b` main work, `d48599a` emulator fixes, `c36aca0` compact source tiles — Opus variant) | merged, verified on the emulator from a master build |
| `feature/search-compact-block` @ `ef48358` | `2ff71a9` compact summary + author/series block (Opus 5.5) and `ef48358` review fixes | **not merged yet** — owner was shown a before/after screenshot; merge with `--no-ff` when approved |
| `feature/search-rework` | merged into master | can be deleted |
| `worktree-wf_a5f8f528-d23-1` (Opus tiles `8e4fa70`, +`7fa315b` parent fix on a detached commit) and `worktree-wf_a5f8f528-d23-2` (Fable text tabs `dc18a1f`) | the two chip design variants; Opus was chosen | can be deleted; Fable kept only as an alternative |
| `.claude/worktrees/source-cards` (`feature/source-cards`) | **another session's** work on source cards | not ours — never touch, it runs its own Gradle builds |

Nothing was pushed to a remote. APKs (git-ignored) in `test-apk/`: `app-release-master-0442ce9.apk`,
`app-release-compact-block.apk`, `app-release-chips-opus.apk`, `app-release-chips-fable.apk`.

Checks at the end: `:app:testDebugUnitTest` 963 tests, 0 failures (1 pre-existing skip); `:app:assembleRelease`
(R8, signed) builds; every user-visible change was checked on the emulator (release APK, `adb install -r`).

## 2. What was built

### 2.1 Stage 0 (spec §3.1, W01–W05, W33, W34)
- W01: the TV layout has the same ids as the phone layout (no NPE on TV).
- W02: the submitted query is saved in `savedInstanceState`; after process death the presenter restores the last
  snapshot **only if it is the screen's own query** (`ownQuery`), else the query is put back without auto-start.
- W03/W04: the tab is part of the reducer state; "not found" is judged per tab (`SearchUi.Empty.TAB_*`).
- W05: clicks hand over the item the adapter showed (`getItem(position)`, `NO_POSITION` guard), never a list index.
- W33: dead strings removed; W34: the toolbar "up" button is found by parent, not by Russian content description.

### 2.2 Engine — `search/SearchSession.java`
- `Listener.onSourceState(State)` (default method) + `State{name, running, status, count, hasMore}`; `states()`.
- Per-source operations: `loadNext(name)`, `retry(name)`, `isRunning(name)`/`hasMore(name)`; `loadNext()` and
  `retryFailed()` start **idle** sources only and no longer wait for a global barrier (W15).
- The case-variant retry (SRCH-10) fires right after that source's empty **first page of the original query**,
  once per source (`caseTried`), not after the slowest site (W16; worst case ~90 s → ~45 s).
- `pending` is accumulated across concurrent runs; `finish()` when it reaches 0.
- `restore(q, items, statuses, cursors, counts, queries, caseRetried)`, `cursors()`, `queries()`,
  `caseRetried(name)`: a saved search continues with each source's own query (other letter case) and cursor.
- `dispose()` is used as "stop": never-answered sources keep their cursor and show as STOPPED.

### 2.3 Post-processing (pure Java, JVM-tested)
- `TextNorm`: NFC + lower case + ё→е; any non-letter/digit is a word break; stress marks U+0300/U+0301 dropped;
  Latin look-alikes (`aceopxybhkmt`) folded to Cyrillic only inside a Cyrillic word whose every Latin letter has a
  twin; `splitEdition` moves only marker words ("полная версия", "радиоспектакль"…) to the edition and keeps
  "часть 2"/"том 1" in the title; `persons`/`person`: names as sets of words, initials apart, one patronymic dropped
  only in names of 3+ space-separated words (middle, else last), hyphenated surnames never treated as patronymics
  and also added as a joined word; `samePerson` = subset of words (endings never forgiven: Котов ≠ Котова).
- `StringSim` (MIT notice, port of java-string-similarity): `osa(a, b, max)` with length cut-off, `bigrams`, `dice`.
- `SearchRequest{text, scope}` with `Field {TITLE, AUTHOR, SERIES, READER}` (empty scope = anywhere).
- `SearchMatcher`: `Card` (normalized fields computed once), word quality 1 / 0.9 prefix (≥4) / 0.8 OSA-1 (≥5) /
  0.65 OSA-2 (≥9), numbers exact only; Kuhn–Munkres max-weight matching (weight 1000 + 100·q·fieldWeight); tiers
  A (whole field = query) … E (partial); score 0–100; +8 phrase, +10 whole, +3 "Author — Title" (only when both
  sides matched). Field weights anywhere: title 1.0, author 0.95, series 0.9, reader 0.9.
- `SearchGrouper`: `Offer` (book, source, user order, native rank, arrival, card, minutes, cached match per request);
  groups by exact key (title without edition + author words) → authorless card joins the single authored group of
  its title → fuzzy merge inside author-word buckets (compatible authors, Dice ≥ 0.92, equal numbers incl. Roman
  i…lxxxix) → authorless join again → narrations by (reader words, edition) and duration clusters
  (≤ max(5 min, 10 %)); narrations ordered: known reader first, most catalogs, most cards. Group id = smallest URL,
  but an id already shown keeps it (`shownIds`). Mirrors count once (`Provider.original()`).
- `SearchOrder`: `Sort {RELEVANCE, ARRIVAL, TITLE, AUTHOR, READER, SHORTEST, LONGEST, SITE}`, Russian collation,
  empty names / unknown lengths last; `firstReader(group)` is the reader a group is filed under.
- `SearchUi.reduce(Input) → State`: chips (`ChipKind RUNNING/FOUND/EMPTY/TIMEOUT/ERROR/BLOCKED/STOPPED`; a source with
  `count > 0` is always FOUND — the session status is the *last page's*), pass (`IDLE/RUNNING/DONE/PARTIAL/FAILED/
  NO_SOURCES`), rows (`Row.key` = group id or card URL, `Row.match` captured at reduce time), scope split into
  shown / "no data for the scope" (kept, last) / hidden (counted), `Empty` reasons (start, nothing, some failed, all
  failed, scope hid all, tab searching/nothing/failed, no sources).
- `SearchSnapshot` + `Store`: process memory + `noBackupFilesDir/search/last.json` (Gson, tmp file then rename;
  on Windows `renameTo` fails over an existing file, so it deletes first); 3 days, older keeps only the request;
  ≤ 1500 cards (truncates the biggest source's tail and drops its next page); stores per-source status, count,
  next page, own query, case-retry flag, author/series chips, tab, sort, grouping. R8 keep rule in `proguard-rules.pro`.

### 2.4 Presenter — `presenter/SearchbalePresenter.java`
- Facts → `publish()` → `Flowable(LATEST).throttleLatest(300 ms) → onBackpressureLatest → observeOn(single, 1) →
  reduce → main`; stale results dropped by `generation`. Offers are created on main once per URL.
- Constructor `(model, context, restoreLast, ownQuery)`: restores the last snapshot when opened without a query or
  after process death (own query only); otherwise publishes the START state.
- Same query re-submitted within 10 min (`FRESH_MS`) shows the snapshot **only if every source had answered**.
- `mFetchedAt` = time of the last full pass (restored: snapshot time) is what `savedAt` stores; "Results from …"
  label (`mRestoredAt`) stays until a new full pass.
- Clicks resolve by row key; a group with one recording on one catalog opens its best card, otherwise
  `showNarrations` lists "reader · length · site · edition"; a display copy never reaches BookActivity/favourites.
- `loadNext()` (scroll, "Искать дальше") loads only chips FOUND with more pages (stopped sources wait for retry);
  `retrySource` retries failed or stopped sources only; `stop()` = dispose.
- Prefs: `search_sort`, `search_grouped`, `search_scope`; Activity: `search_show_score`.
- Unchanged: MODEL_AUTOR/MODEL_ARTIST/MODEL_GENRE modes (one source, `SearchSession<GenrePOJO>`), author/series chips
  from `SearchebleModel` (Книга в ухе, ABMP3), long-press `BottomSheetDialog` built in the presenter (spec §13 debt).

### 2.5 Screen — `activity/SearchableActivity.java`, layouts, resources
- Source row: flat two-line tiles (`search_source_chip.xml`, `Widget.App.SourceChip` — `parent="android:Widget.TextView"`
  is required), name over a smaller count ("12+", "0", "…", "–"); state = faint fill (22 %), selected = 2 dp
  underline in `hrefColor` + bold; TV focus outline via `source_tile_foreground`. Long press = retry that source.
- Determinate progress line (answered/planned), one summary line + one action ("Остановить", "Повторить (N)",
  "Повторить <site>", "Искать везде", "Обновить", "Искать дальше"), empty-state text with a reason.
- Toolbar menu: scope (multi-choice dialog + "Везде"; dot on the funnel when active), sort popup, overflow:
  grouping, "% соответствия" (off by default), refresh.
- Rows are rendered by the existing `BooksListAddapter` via `submit(items, labels, notes, aliases)` (DiffUtil by URL;
  `labels` replace the site name, `notes` replace the genre *line* only, `aliases` = URLs of a group whose best
  progress/download mark the row shows). `setItem` for catalog/favourites is unchanged; status refresh uses a
  payload (no cross-fade). `reloadStatuses()` on `onRestart`.
- Group display copy: URL = group id, readers line (sort reader first, one name once however spelled),
  "N сайтов" label, "N озвучек · совпал чтец · частичное совпадение" notes; **the genre stays real** so the parental
  control still masks covers (a group takes the most restricted member's genre).
- Scroll to top when generation/tab/scope/sort/grouping changes; the search field loses focus on an external query
  (voice) and when restored results appear; `searchable.xml` hint is `@string/search_hint`.
- Branch `feature/search-compact-block` (pending): summary row 32 dp, author/series labels inline before their plates,
  plates styled like tiles (13 sp, 36 dp, 2 dp gaps), new dimens `search_*` (shared `titleSize` etc. untouched).

### 2.6 Second session (2 November 2026, afternoon) — uncommitted on `master` until the owner asks
- **Author/series plates from every site** (`search/SearchPlates`, called from `SearchUi.reduce`, `State.plates`):
  the plates used to come only from Книга в ухе (authors + series, two requests in a 10 s budget) and ABMP3
  (authors) — with Книга в ухе off or slow there were no series at all. Now the sites' own lists (`Input.people`)
  are merged with the authors (one person per card) and series of the loaded cards whose field *alone* holds the
  query (`Offer.holds`, a scoped `SearchMatcher` run cached per request). One plate per person however spelled
  (`TextNorm.person(..).words`) and per series key; Книга в ухе's series names carry the author ("Кингсбридж Кена
  Фоллетта"), so a found series whose key is a prefix of a site's one merges into it. Most cards first; under a
  tab only that site's plates. Duplicates of Книга в ухе and ABMP3 ("Стивен Кинг" / "Кинг Стивен") now merge, so
  the count can be lower than before.
- **Source tabs sorted** (`SearchUi.CHIPS`): most found first, then searching, then 0, then the silent ones
  (timeout, error, blocked, stopped); ties keep the user's order. The activity moves the chip views (never re-makes
  them), restores a focused chip and scrolls a moved selected tab back into sight.
- **Choice of recordings** redesigned: custom title (work + "N озвучек на M сайтах"), numbered recording headers
  (reader, length · edition · sites), site rows with the site icon (asset `icon.webp`, letter until drawn), the
  card's own length, inset dividers between sites, full ones between recordings, a "Закрыть" button.
- **Sort in the overflow** as a submenu ("Порядок: по соответствию"); the toolbar keeps only the funnel.
- **Collections ("Подборки")**: akniga's search returns `/collection/...` cards; opening one as a book failed with
  "no playable chapters". `Offer.listing` (`parser.opensAsListing`) cards never group, show the note "подборка",
  and open as the site's listing (`returnResult(.., "collectionBooks")`); `FavoritePresenter` got the same guard.
- **Genre filter** (§4 item 2 below, done): overflow "Жанры: …" → multi-choice of `Genre.values()`; a card whose
  genres (`Offer.genres = Genre.allOf(..)`) miss the chosen ones is hidden, one without a known genre is kept last
  as "нет данных для фильтра"; pref `search_genres`; "Искать везде" clears the scope and the genres.
- **An unscrolled list stays at its top** (`showRows`): with DiffUtil a better card that arrived later went above the
  first visible row and stayed hidden (the live "Кинг" search opened on "Мисс Кинг", the collection "Летние
  приключения" was off-screen). A list the listener scrolled keeps its place.
- Книга в ухе's author/series request budget 10 → 20 s (`SearchebleModel`, two requests).
- Codex review (gpt-6.1-sol) found and we fixed: favourites' long-press "Open" and the favourites' search result
  ignored listings; initials dropped from person keys ("Л. Толстой" = "А. Толстой"); a found series merged into a
  site's longer one of another author ("Тень" → "Тень империи Иванова"), now only when the rest names the card's
  author; link-less cards were not counted; the genre filter split one work into a shown and a "no data" row — the
  genres now judge a whole work (any card with a chosen genre passes it).
- Known limit: a search started by voice (`MediaPlayerService`, no caller) closes on an author/series/collection
  tap without opening it (as before for plates and the long-press menu).
- Not the app's fault: on 2 Nov Audioboo answered Cloudflare 522 (origin down) and `audioknigi.fun` did not resolve.
- Checked: 1009 unit tests; release APK `test-apk/app-release-search-plates-v3.apk` on the emulator (tabs order, 20
  authors + 4 series for "Кинг", 11 series for "пикник на обочине", sort submenu, genres, the recordings dialog with
  15 recordings, the collection and a series plate opening their listings).

## 3. Decisions that deviate from the spec (and why)
- Ranking + grouping shipped together with stage 0–2 (spec §14 p.10 split them into releases): the owner asked for
  them now; "Как пришло" and "Группировать" in the menu restore the old view.
- Screen logic in a pure reducer, not in the presenter (no Robolectric in the project).
- Case retry moved from stage 5 to the engine now; local scope (5a) done before server-side scope paths (5b).
- Scope selector is a multi-choice dialog (PopupMenu closes after every tap); no structured "title AND author" form.
- Chips: no icons / rounding / outline (owner's explicit request, chosen variant: Opus 5.5 tiles).
- Narration choice is a simple dialog, not the `BottomSheetDialogFragment` sheet with availability checks (§9.5).
- Snapshot written with plain Java (tmp + rename), not `AtomicFile` — JVM-testable.

## 4. Remaining work (prioritized)
1. ~~Merge `feature/search-compact-block`~~ (merged, `de62053`).
2. ~~Genre filter~~ (done in §2.6).
3. **Ranking tuning**: for a one-word surname query ("кинг") title matches (weight 1.0) beat the author's books
   (0.95); consider boosting AUTHOR when the query is a single person-like word, or tie-breaking by number of
   catalogs. Keep the corpus tests (appendix B) green.
4. **"Slow" marker and time limits** (§7.6): `Input.nowMs` + expected time per source + presenter timer
   (8 s "долго отвечает", 20 s "stop waiting"); later an absolute pass limit.
5. **RATE_LIMITED + Retry-After** (W36): `ProviderPage.httpStatus/retryAfterSeconds`, one `SiteHttp.throwForStatus`
   for `parse` and `json`; catalog code in `BooksPresenter` compares with BLOCKED (do not offer proxy on 429);
   `SourcesDialogFragment` status text.
6. **Response cache** (§7.5): cache response *bodies* (Jsoup `Document` is not thread-safe), LRU by bytes (~8 MB),
   in-flight coalescing with consumer counting; ABMP3 one URL for all pages; Книга в ухе chips from the search page.
7. **"В вашей библиотеке"** row (favourites/history matched with `SearchMatcher`, no network).
8. **Narrations sheet + availability** (§6.6, §9.5): `BottomSheetDialogFragment` by group id, `AvailabilityMemory`,
   `availability()` per adapter (fragment / removed books), best offer skips removed ones.
9. **Measurement (stage 2.5)**: extend `LiveDumpTest` (8 queries per site); only the owner runs live requests. Then
   `SearchProfile` per adapter (`caseSensitive`, `expectedMs`, `scope`, `order`, `minQueryChars`, `titleOnlyParam`,
   `previewProne`) and `SearchPlanner`: neighbour-hint fallback for title-only sites (ABMP3, База Книг,
   Audiokniga-online), people search on every site that has it (derive from `SearchUrls.url(...) != null`; ABMP3 has
   authors), absorb MODEL_AUTOR/ARTIST (entry point `BooksPresenter` ~:565, also `FavoriteFragment`,
   `MediaPlayerService`).
10. **Transport (T09)**: OkHttp behind Jsoup with `Call.cancel()`; until then "Stop"/"Back" only drop
    subscriptions — threads wait for the site timeout (10–40 s). Host limiter instead of Aknigi24's internal 429
    retries, shared semaphore, "one request per mirror family" setting (off).
11. Smaller debts: "New above" freeze plaque (§6.5) if DiffUtil anchoring is not enough; duration filter
    (`ListingFilters.Duration`), "hide listened"; local suggestions while typing; TV: focus to the first result;
    long-press menu built in the Activity (not presenter); light-theme contrast of grey labels; 48 dp touch target of
    the summary action; reader strings with a whole cast (radio plays) are not split into people for display;
    delete merged/variant branches.

## 5. Lessons and pitfalls from this session
- **Emulator safety** (CLAUDE.md): one emulator, accelerated AEHD, started only with `emu.ps1 start/wait`, stopped with
  `emu.ps1 stop` and checked gone. Before starting: no `qemu`/`emulator`/`java` processes (another session builds in
  `.claude/worktrees/source-cards` — wait until its Gradle ends; a background loop polling `Get-Process java` worked).
  Only release APKs signed with the owner key, `adb install -r` (keeps data). The owner explicitly allowed running
  the emulator while the other session works, as long as it does not build at the same time.
- Emulator quirks: English locale → Russian plurals show "other" ("72 книг"); no speech recognizer → no mic in the
  SearchView (on phones the mic shows when the field is empty; with text it is replaced by ✕ — standard behaviour).
- adb from Git Bash: set `MSYS_NO_PATHCONV=1` or `/sdcard/...` becomes a Windows path; adb is at
  `C:/Users/user/AppData/Local/audiobook-audit-tools/android-sdk/platform-tools/adb.exe`. Cyrillic `--es query` works
  from Git Bash. Screenshots: `screencap -p /sdcard/x.png` + `pull`.
- Tooling: Python stdout is cp1251 here — use `PYTHONIOENCODING=utf-8`; bash heredocs with some content fail to parse
  — write scripts with the Write tool and run them; `git merge -F -` cannot read stdin here — use a message file.
- Android/aapt: a style named `Widget.App.X` without `parent` makes aapt look for a non-existent `Widget.App`.
  Bridge themes inflate `<Button>` as AppCompat — Material `app:inset*` attributes do nothing.
  Moxy provides the presenter inside `super.onCreate`: fields it needs (`mRecreated`, `mSavedQuery`) must be set
  before. The `searchable.xml` hint overrides `SearchView`'s `queryHint`.
- Gson reads `search.SearchSnapshot*` by reflection — keep the R8 rule when renaming or adding DTOs.
- Never overwrite real fields of a display copy that other code reads (parental control reads `genre`).
- A source's status is its last page's; use `count` to decide "found".
- Workflow agents in worktrees start from old commits sometimes — they fast-forwarded themselves; verify the base
  (`git merge-base --is-ancestor`) before building their commits. Build variants one after another in the main
  checkout (keystore.properties lives only there; debug APKs would require uninstalling the app).
- Independent review (several reviewers + a refuting skeptic per finding) found 13 real defects after all unit
  tests were green — worth repeating for the next large change.

## 6. Commands
```powershell
powershell -NoProfile -File C:/Users/user/AppData/Local/audiobook-audit-tools/g.ps1 C:/work2000/AudioBooks :app:testDebugUnitTest --tests 'com.fanok.audiobooks.search.*' --tests 'com.fanok.audiobooks.presenter.SearchbalePresenterTest'
powershell -NoProfile -File C:/Users/user/AppData/Local/audiobook-audit-tools/g.ps1 C:/work2000/AudioBooks :app:assembleRelease
powershell -NoProfile -File C:/Users/user/AppData/Local/audiobook-audit-tools/emu.ps1 start   # then: wait, stop
```
Tests to extend: `search/{TextNorm,StringSim,SearchMatcher,SearchGrouper,SearchOrder,SearchUi,SearchSession,SearchSnapshot}Test`,
`presenter/SearchbalePresenterTest`.
