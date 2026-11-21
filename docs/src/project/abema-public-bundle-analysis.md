# ABEMA public bundle analysis

This page records a static analysis of the public ABEMA JavaScript chunks that
the Android native bootstrap and guest runtime download, verify and load. The
goal is to understand what Tachiai actually uses from them. That understanding
informs two questions: could more of the work run natively, and where does
startup time go?

Unless a row says it was measured, findings are **static first-party
implementation evidence** from minified public code. They are not a documented
provider API or a statement of provider support. They also do not change the
[security boundaries](security-and-privacy.md#general-provider-integration-approval--2026-10-10).
In particular, this analysis characterizes the license helper's interface and
host dependencies only. It does not decode, reproduce or document the helper's
response-processing algorithm, any keys, or the application-key secret.

Analysis date: 2026-11-19. Environment: Linux desktop, Node 24.21 with Acorn 8
for parsing and Prettier 3 for readability; the outbound network was not in
Japan. No provider code was executed. Compile timings constructed
`vm.Script` objects without running them.

## Pinned assets and how they are used

Since 2026-11-20, `AbemaPublicBundleCache.kt` pins build `v26.1119.1` (see
[Pin refresh](#pin-refresh--2026-11-20)). The analysis on this page was made on
the previous pins, build `v26.1001.1`. Module counts and timings below describe
that build unless a row says otherwise.

| Pin (current) | Chunk id | Bytes | SHA-256 | Loaded by |
| --- | --- | --- | --- | --- |
| `7e1d1d3889f47778.9627.js` (APPLICATION) | 9627 | 1,801,875 | `1fa2860d7ec94db123ce7e8f321b4de9b4663dd1733410757d4f1559bba35e73` | native + guest runtimes |
| `8a1c293b222b2390.framework.js` | 26 | 326,334 | `eefb65e83c3fb1f82af5ce599f154c6e422bd7d3dab2aaa19c3ac9b5425e8412` | native + guest runtimes |
| `836465d169ab61b6.8204.bundle.js` | 8204 | 18,618 | `aabc84d2af5da5ccd259fee7d28df295eb5e40f43d66e2b235db9d40b6747b41` | native + guest runtimes |
| `4a27c7be953150a2.9328.js` | 9328 | 186,525 | `24ec3275b74ebc9bca669255a121bb182944a6b5466ae21ba312752cf7d04da8` | native + guest runtimes |
| `7459be98c1074c5c.youboralib.js` (CSAI_SUPPORT) | 5504 | 160,733 | unchanged | pinned; not loaded by either Android runtime |

The analysed `v26.1001.1` pins were:

| Pin (previous) | Chunk id | Modules | Bytes | Loaded by |
| --- | --- | --- | --- | --- |
| `3850f3e68c3aa3c0.5206.js` (APPLICATION) | 5206 | 785 | 1,801,458 | native + guest runtimes |
| `a3d61a8507a3f772.framework.js` | 26 | 101 | 326,667 | native + guest runtimes |
| `1deb672fea92d35e.8204.bundle.js` | 8204 | 17 | 18,786 | native + guest runtimes |
| `7977ee0696dadeb7.9328.js` | 9328 | 64 | 187,143 | native + guest runtimes |
| `7459be98c1074c5c.youboralib.js` (CSAI_SUPPORT) | 5504 | 86 | 160,733 | pinned; not loaded by either Android runtime |

All five previous URLs still served byte-identical content on 2026-11-19 and
2026-11-20.

Each chunk has the form
`(globalThis.__LOADABLE_LOADED_CHUNKS__ ||= []).push([[chunkId], {moduleId: factory, …}])`.
None of the four loaded chunks includes the webpack runtime or entry callbacks.
`native-bootstrap.js` and `guest-bootstrap.js` each implement their own small
`__webpack_require__` (`load`, with `d`/`r`/`n`/`o`/`g`/`nmd` helpers). They
register every factory but execute only those that are reached from explicit
`load(id)` calls.

Every native or guest preparation therefore:

1. reads all four chunks from the cache directory and hashes them (about 2.33 MB);
2. serves them to a fresh WebView through `shouldInterceptRequest` with
   `Cache-Control: no-store`;
3. has V8 parse and compile all four scripts, 967 factories in total;
4. executes only the reached factory closure.

## Module map

### Modules loaded directly by Tachiai

| Module | Export used | Role (static reading) | Used by |
| --- | --- | --- | --- |
| 74284 | `kt`, `FH` | API origin table (`api`, `license`, `playbackResources`, …) | native, guest |
| 24968 | `T` | `/api/auth/*` path constants (guest login, device authorization/token) | native, guest |
| 5304 | `rM`, `V8` | Guest-login request/response DTOs (`device_id`, `application_key_secret`, `device_type`; `user_id`, `access_token`, `refresh_token`) | native, guest |
| 50052 | `bq` | `DeviceType` enum (WEB = 3) | native, guest |
| 2833 | `b2`, `B9` | Application-key secret derivation: an iterated WebCrypto HMAC-SHA256 chain over an embedded static secret, the device UUID and current UTC time fields. `B9` gives the next whole hour | native, guest |
| 2953 | `pG`, `dK` | Device-authorization and device-token DTOs | guest |
| 29441 | `Z` | localStorage key names (`abm_token`, …) | native (modern channel) |
| 60506 | `N` | Parsed user-agent singleton (browser/OS name, version) | native |
| 87328 | `xl` | Desktop-versus-mobile layout flag (default DESKTOP) | native |
| 17453 (was 91966) | `sD` | Feature-flag client; uninitialized `stringVariation` returns the fallback | native |
| 91060 | `OW` | Program (episode) metadata DTO | native replay |
| 17046 | `PlaybackResourceGateway` | Unauthenticated `GET {playbackResources}/v1/playbackResources/{arin or channelId}`. It retries twice on unexpected statuses, with 1 s and 2 s backoff, then filters, chains and sorts streams | native replay |
| 18278 | `WebStreamFilter` | Stream/manifest admission by DRM, technology, capability and ad mode | native replay |
| 29067 | `WebManifestComparator` | Ranks stream/manifest candidates | native replay |
| 43306 | mappings | DRM system-ID map, use case/technology/MIME maps, `isRawKeyStream` | native replay |
| 45808 | `PlaybackResource*` | Protobuf-JSON message/enum definitions (32.6 KB) | native replay |
| 32025 | `getManifestQuery` | Adds `disable_ad`, `utc_timing` and `max_height` query items | native replay |
| 65364 | `createMediaURL` | Appends `t`, `enc` and `dtid` to slot/program manifests | native replay |
| 73418 | `createConfiguration` | Builds the license URL per key system (`custom` → `/abematv-dash`) | native replay |
| 75284 | key-system IDs | Widevine / FairPlay / custom UUIDs | native replay |
| 78307 | `rC` | `{license}/playready`, `/widevine`, `/abematv-dash` | native channel/slot |
| 21470 | `tw` | Legacy channel/slot playlist selector. It picks the DASH or yospace playlist and adds `t`, `enc`, `dt=pc_*`, `ccf`, `dtid`, `ut=1` and `sw` | native channel/slot |
| 68610 | `Y1` | Source type enum (`streaming`/`progressive`) | native channel/slot |
| 40579 | `c` | Channel model | native (modern channel) |
| 42561 | `w` | Division query from `__CLIENT_REGION__` | native (modern channel) |
| 44680 | `L` | `deviceTypeId` lookup (`/v1/media/deviceTypeId?os=&osVersion=&drm=`), cached in storage | native (modern channel) |
| 27594 | default | Full **crypto-js** library (35 sub-modules) | native (helper dependency) |
| 14405 | default | Opaque license-response helper: `factory(cryptoJs)` returns `operation(ArrayBuffer, userId) → ArrayBuffer` | native |

### Transitive closures

The closures were computed statically. They count every `require(N)` call that
appears in a factory body, so they over-approximate what actually runs.

| Closure | Modules | Factory bytes | Notes |
| --- | --- | --- | --- |
| Native bootstrap (all roots above) | 309 | 770,537 | 33% of the 2.33 MB that is parsed |
| Guest bootstrap | 14 | 98,269 | 69,296 bytes of that is Luxon (framework module 40310), reached only through 2833 → 32746 for a seconds-from-milliseconds helper |
| License helper (14405 + 27594) | 37 | 119,252 | Agrees with the earlier on-device probe's 37 modules / 119,430 characters |
| Application key (2833) | 7 | 74,948 | Again mostly Luxon |
| Gateway, filter, comparator and URL/config builders | 29 | 56,718 | |

Per-root attribution shows the pure logic Tachiai needs is small, but two tiny
modules drag in most of the native closure:

- `21470` (legacy playlist selector, 1.6 KB) reaches 233 modules / 550 KB.
  That is through `1955` (EME capability cache), which reaches `91966` (feature
  flags) and then React, bunshi and analytics glue.
- `44680` (device type id, 1.9 KB) reaches 159 modules / 376 KB.
- `91966` alone reaches 225 modules / 545 KB. The native bootstrap uses it only
  to obtain an uninitialized flag client whose fallback is always returned.

Unresolved static references are harmless:

- `401` and `500` in module 9355 come from a local `createResponse` parameter
  shadowing the require name.
- `521`, `523` and `528` in 14405 are references inside the helper's own nested
  module table.
- `48488` is an empty `crypto` stub that lives in the webpack runtime chunk and
  is only reached in a `try` block.

### License helper characterization (interface only)

- Module 14405 is 55,435 bytes. It contains its own nested module table, a
  single dispatch `switch`, and no `eval`, `Function(…)` or `debugger`.
- Its only host globals are `window`, used as a global-object fallback
  (`typeof window` → `r.g`), plus crypto-js's own `self`/`window`/`globalThis`
  crypto lookup. It uses no DOM, network, storage or timer APIs.
- Between the pinned build and the 2026-11-19 live build, all 37 helper-closure
  modules are **identical after identifier renaming**: same token shape and
  same literals. The helper did not change across that rebuild.

The closure's only possible platform dependency is crypto-js's secure-random
lookup. If the helper never calls `WordArray.random`, that lookup is unused.
This has not been determined, and determining it is a runtime check, not an
algorithm review.

## Request chain driven by the bundles

What follows is the native bootstrap (`native-bootstrap.js`). Arrows are
sequential awaits in the app-owned orchestration. Square brackets mark Kotlin
work that precedes the WebView.

```text
[saved-account acquire IPC (account mode)] → [4 × cache read + SHA-256] → [WebView load, parse 2.33 MB]
  guest mode:   guest login POST abema.tv/api/auth/login/guest (2833 + 5304)
  → media token GET api…/v1/media/token
  replay:  → program metadata GET api…/v1/video/programs/{id}
           → playbackResources GET streaming-api-cf…/v1/playbackResources/{arin} (17046)
  slot:    → region page GET abema.tv/now-on-air/abema-news (SSR HTML, parse __CLIENT_REGION__)
           → CDN region GET (if status=true)
           → slot metadata GET api…/v1/media/slots/{id}
           → legacy selector (21470) + legacy license URL (78307)
  modern channel: → region page → CDN region → deviceTypeId GET (44680)
           → channels GET api…/v1/channels (≤1 MiB) → legacy selector
  → READY (Kotlin polls status() every 100 ms)
  → native MPD / CDM request → begin(): license POST → helper 14405 → take()
```

### Measured network observations (desktop, outside Japan, 2026-11-19)

| Request | Observation |
| --- | --- |
| `abema.tv/now-on-air/abema-news`, Android UA | `cache-control: no-store`, `x-cache: MISS`. Time to first byte 1.0–2.35 s over five samples; gzip body ≈31 KB (224 KB uncompressed). `__CLIENT_REGION__` starts at byte ≈65 K |
| Other SSR routes (`/`, `/timetable`) | TTFB ≈1.0 s; also carry `__CLIENT_REGION__`. 404 pages do not carry it |
| `ds-glb-linear-abematv.akamaized.net/region` | ≈0.2 s; HTTP 200 with a 2-byte body even from outside Japan, consistent with the existing note that this check is not region proof |
| `streaming-api-cf…/v1/playbackResources/abema-news` | HTTP 400 `Specified ARIN is not supported`, consistent with the existing finding that linear News must use the legacy selector |
| Pinned 5206 chunk | Served gzip-encoded (490 KB on the wire) with `cache-control: no-store` |

Region-page latency from Japan, or from a VPN exit there, was not measured.
These figures show the shape of the cost (server-side rendering), not its
on-device value.

### Existing device timing context

From [native access experiments](native-access-experiments.md#cached-native-bootstrap-without-the-provider-page--2026-10-07)
(Pixel 6, WebView 153):

- Helper-only factory initialization took 381–427 ms when the cache was hit.
- Fully cached code preparation took about 70–92 ms.
- Opening the viewer to both first native News frames took 10.6–12.9 s.

That page already concluded that most startup time is network, session, source
and licensing work rather than code.

### Compile cost (desktop V8, compile only, median of 15)

| Input | Bytes | Compile | Code-cache size | With code cache |
| --- | --- | --- | --- | --- |
| 5206 | 1,801,458 | 78.4 ms | 170 KB | 1.6 ms |
| framework | 326,667 | 19.9 ms | 35 KB | 0.3 ms |
| 9328 | 187,143 | 10.0 ms | 19 KB | 0.2 ms |
| 8204 | 18,786 | 1.1 ms | 4 KB | <0.1 ms |
| All four | 2,334,058 | 109.6 ms | 229 KB | 2.0 ms |
| Native closure only (309) | 773,741 | 37.6 ms | 76 KB | 0.7 ms |
| Helper closure only (37) | 119,504 | 5.8 ms | 8 KB | 0.1 ms |

This is top-level compile only. Lazily compiled functions add more cost when
they first run. Mobile CPUs are typically several times slower, so on-device
values are unmeasured estimates, not observations.

## Pin durability

The live site has rebuilt since the pins were taken. Desktop and mobile entry
pages now reference `8a1c293b222b2390.framework.js`, `4a27c7be953150a2.9328.js`
and `7235b06ae31ed875.9627.js`. The 5206 chunk is no longer referenced; its
successor is chunk 9627 (1,801,760 bytes). The runtime maps chunk 8204 to
`bc0cf249bb46f180.8204.bundle.js`.

The pinned URLs still resolve, but only because the CDN retains old assets. The
cache lives under `cacheDir`, which Android may evict. If the provider purges
the old assets, a later cache miss becomes a hard preparation failure.

An alpha-normalized comparison (identifiers renamed, token shape and literals
compared) of the 309-module native closure against the live build found:

- **124 identical** after renaming, including the entire helper closure.
- About 180 modules with **cosmetic structure changes only**. These come from
  the minifier inlining `r.d` value maps and dropping `"use strict"`. Their
  string literals are unchanged except in 9 modules. Of those, 74284 drops the
  `webview` origin and adds `recaptchaEnterprise`; 40745 adds one viewing-
  authority enum value; the others are similarly small.
- **8 modules gone.** Among the direct roots, only **91966 → 17453** is
  affected; it has the same `sD` export. The rest are React/bunshi internals
  that were renumbered.
- All other root module IDs and every export name Tachiai uses (`kt`, `FH`,
  `T`, `rM`, `V8`, `bq`, `B9`, `b2`, `tw`, `Y1`, `rC`, `c`, `w`, `L`, `OW`,
  `Z`, `N`, `xl`, `sD`, `pG`, `dK`) are unchanged. The current closure resolves
  completely inside the four corresponding current chunks.

A pin refresh would therefore need four new URL/hash/size identities plus one
module-ID change in `native-bootstrap.js`. That holds for this rebuild only;
module IDs are not a stable contract.

### Pin refresh — 2026-11-20

The live build had moved again by 2026-11-20 (`v26.1119.1`, `APP_SHA`
`9947bd5f`). The application chunk was now `7e1d1d3889f47778.9627.js` and the
runtime mapped 8204 to `836465d169ab61b6.8204.bundle.js`. Framework and 9328 were
unchanged from 2026-11-19. Tachiai now pins those four chunks. The previous
pins still resolved, so this is a planned move, not a repair.

The closure was re-checked statically against this build in the same way
(Acorn parse, no provider code executed). The roots and exports were taken from
`native-bootstrap.js` and `guest-bootstrap.js`.

- Every module ID and export the bootstraps use exists in the new chunks.
  The only exception is again `91966`, now `17453` with the same `sD` export.
- The license-helper closure (14405 plus crypto-js 27594, 37 modules) is
  identical to the previous pins after identifier renaming.
- The only new unresolved reference, `34269`, is the renumbered `20054`. Both
  are required by module 23980 and defined outside the four chunks, so nothing
  new is unresolved.
- The bootstrap's mirrored application version moved from `v26.1001.1` to
  `v26.1119.1`, matching the pinned build's own media-token and client-info
  value.

The web diagnostics in `apps/web/scripts` moved in step. Their safety test
requires the diagnostic pins to be the reviewed Android assets. The same static
check covered the guest, source, episode-source and region helpers: every
module ID and export they use exists in the new chunks. Their mirrored
application version moved to `v26.1119.1` too. No web diagnostic was run
against the provider.

#### Emulator observation — 2026-11-20

The setup was:

- `tachiai-dev`, an Android 16 x86_64 emulator with WebView 133.0.6943.137;
- the ABEMA route through a WireGuard Tokyo exit;
- the saved ABEMA account (account mode) for playback.

Emulator results do not establish physical-device DRM or decode behaviour.

| Case | Result |
| --- | --- |
| Live News channel, first start after install | `cache=DOWNLOADED_VERIFIED support=DOWNLOADED_VERIFIED legacy=DOWNLOADED_VERIFIED utilities=DOWNLOADED_VERIFIED`, `helper=READY` (272 modules, last 78307), keys loaded, video frames advancing |
| Series catalog operation (大相撲 collection episodes) | Guest catalog runtime listed episodes |
| Schedule and channel-program catalog operations | Listed broadcast slots |
| Replay (11月場所 13日目 highlight) | `cache=CACHE_HIT …` on every later start, `helper=READY`, keys loaded, video frame |
| Broadcast slot | Not established |
| Modern channel context | Not run |

On the broadcast slots:

- Four News and anime slots were refused by the provider's own slot flags, at
  `SLOT_FREE` (two) or `SLOT_WINDOW` (two). No bundle module runs before those
  checks.
- One of them was also refused at `SLOT_WINDOW` by the build with the previous
  pins, so the refusals are not a regression.

Within one viewer process, transient route failures also occurred (media
token `FETCH_REJECTED_BEFORE_HTTP`, account acquire failure). They cleared
after the app restarted, and they are independent of the bundles.

## Speed-up opportunities

These are ranked by expected impact. All are proposals, none implemented.
Items marked *policy* change a deliberate boundary and need an explicit user
decision first.

1. **Instrument the remaining phases first.** Closed logcat markers already
   bracket the worker start, account lease and bundle reads
   (`startup=WORKER_BEGIN` … `startup=BUNDLES_READY`), and helper state changes
   are logged as the 100 ms poll observes them. Inside the WebView, `metadata()`
   reports only the current phase. Closed per-phase markers for guest login,
   media token, region, metadata, gateway, READY, license and first frame
   would show where the remaining startup time goes before anything is
   optimized.
2. **Run independent requests concurrently in the app-owned orchestration.**
   - The region page does not depend on the bearer token. On slot and
     modern-channel paths it can start immediately, in parallel with guest
     login and the media token. Measured TTFB was 1–2.3 s, so this is
     potentially the largest single saving.
   - The media token, program metadata and slot metadata need only the bearer
     token. They can run concurrently, saving one or two round trips.
   - Kotlin can read and hash the four cached chunks concurrently with the
     account-acquire IPC instead of afterwards, and can start creating the
     WebView/renderer before the bytes are ready.
3. **Shorten the READY hand-off.** The fixed 100 ms `status()` polling adds
   0–100 ms per transition. A tighter poll keeps the existing no-bridge
   boundary; a JavaScript interface or message port would not.
4. **Reduce parse work.** Two options:
   - Serve only the reached factories. These are byte-identical slices of the
     verified chunk; offsets are fixed per pinned SHA-256, so an offset table
     can accompany each pin. Saves about two thirds of top-level compile.
   - Get V8 code-cache reuse. Intercepted `no-store` responses cannot use the
     WebView HTTP/code cache.
   Expected gain is tens to low hundreds of milliseconds. It is worthwhile only
   if instrumentation shows compile on the critical path.
5. *Policy:* **Share a guest identity across short-lived catalog operations.**
   Each catalog operation (Schedule, Series, Slot, Search) currently starts an
   isolated process and WebView, parses 2.33 MB and creates a fresh provider
   guest, all to mint one bearer token. Fresh-guest-per-operation is
   deliberate, so this is a scope decision, not a bug.

## Native implementation assessment

| Component | Size of logic | Native port feasibility | Boundary status |
| --- | --- | --- | --- |
| Endpoint constants, DTOs, enums | trivial | Straightforward | Current design keeps provider protocol in unchanged modules; porting is a design change, not a forbidden one |
| URL/query builders (`createMediaURL`, `getManifestQuery`, `LicenseURLBuilder`, legacy selector `tw`) | a few hundred lines | Straightforward; `tw` depends on UA/capability inputs | Same as above |
| Stream filter, comparator, playback-resource JSON | ≈ 40 KB with protobuf-JSON DTO | Moderate (DTO port) | Same as above |
| Region parse, device-type lookup, channel list | small | Already partly in owned JS | Same as above |
| Application-key secret (2833) | ≈ 1 KB plus a static secret | Technically trivial | *Policy:* copying a provider algorithm and embedding its secret; needs explicit user approval. No performance benefit (milliseconds in JS) |
| License helper (14405 + crypto-js) | 119 KB opaque | — | **Not permitted:** “copy helper algorithms” is prohibited. It must stay unchanged provider code |

A native rewrite does not remove the need for a JavaScript engine, because the
helper must stay unchanged provider code. The helper closure has no DOM
dependency, though, so a non-WebView isolate is a candidate host:
[`androidx.javascriptengine`](https://developer.android.com/develop/ui/views/layout/webapps/jsengine).
It offers out-of-process V8 without a WebView, `provideNamedData` for
ArrayBuffers, and promise returns. Whether its isolate exposes the globals the
helper or crypto-js might touch is undocumented and must be verified on device.
Feature support also depends on the installed WebView. The resulting
architecture would be:

- Kotlin does all networking, with the existing route, TLS and policy controls.
- An isolate runs only the unchanged helper closure; if the policy item above
  is approved, it could also run 2833.

This removes the WebView renderer and the 2.33 MB parse. It does **not** address
the network chain, which existing evidence says dominates.

### Widevine as a helper-free path (open)

The provider's own selection logic offers Widevine broadly:

- `WebStreamFilter` → `getDrms` admits `widevine` for channel sources and
  `widevine` + `custom` for program/slot sources.
- `WebManifestComparator` ranks Widevine/FairPlay ahead of custom.
- `createConfiguration` builds `{license}/widevine` with `t`, `ct`, `cid` and,
  for mobile app types, `dt=web_android`.

Media3 supports standard Widevine through Android `MediaDrm`. If a selected
resource advertises a Widevine stream and the license server accepts an
Android CDM request with the web media token, playback would need **no provider
JavaScript for licensing at all**. It would also stay within the opaque-CDM
boundary.

This is unverified:

- The current native filter deliberately admits only custom DASH.
- No VOD playback-resource stream list has been inspected. That needs a bearer
  token to read program metadata for the ARIN.
- Non-DRM legacy channels (`enc=clear`) would still use the custom path.
- Earlier WebView traces of ABEMA's own player registered both key systems
  with Widevine preferred, yet News and the free sumo replay ended on a Clear
  Key CDM (see [open questions](open-questions.md) and
  [native-access experiments](native-access-experiments.md#related-target-and-media-follow-up)).
  Which conditions select each path is unresolved, so ranking alone does not
  predict Widevine availability for a given source.

It needs a bounded device experiment recorded under the usual playback-evidence
rules.

## Reproduction

The analysis scripts parse chunks with Acorn and never execute provider code.
They cover module-graph extraction, closure computation, per-module
pretty-printing, alpha-normalized cross-build diff, free-global listing and
compile-only timing. They were kept outside the repository in a session scratch
directory. No provider code or derived module source is stored in this
repository.
