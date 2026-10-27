# Bounded ABEMA browser decoder diagnostic

This implements the next local diagnostic for
[#366](https://github.com/altendky/tachiai/issues/366). Controlled owned media
decodes through the new transport in ordinary Chrome. The first fresh ABEMA trial
stopped in the MPD's auxiliary-feature audit before decoder preparation; actual
ABEMA decoded-frame acceptance remains unverified. The earlier
[preparation and MPD observations](web-abema-feasibility.md) do not prove clear
media or decoder/CDM acceptance.

## Reproduction and evidence

Install the pinned web dependencies, Wasm/owned fixtures, ordinary Chrome, and
preinstalled diagnostic Docker image described in the
[harness README](../../../apps/web/README.md) and
[bootstrap instructions](web-abema-feasibility.md#reproduction), then build:

```sh
mise exec -- npm run build --prefix apps/web
```

The following command explicitly performs fresh provider operations. It is
excluded from tests and CI:

```sh
mise exec -- uv run apps/web/scripts/abema-backend-playback.py --run-global-playback
```

No other argument form starts provider, browser or container work. The command
accepts no supplied URL, grant, account, route or browser profile. It first reads
the fixed News page's single current region context, requires allowed GLOBAL
division 1, and conditionally performs its fixed selected-region GET. Four public
bundles are downloaded and checked against the existing pinned sizes/hashes;
their unchanged region mapper runs inside the existing owned networkless
container. The resulting query must be exactly division 1. The one-shot current
catalogue request uses the modern channels endpoint. The canonical bootstrap,
fresh guest/profile/media token, actual Chrome identity and unchanged source
selector then retain their existing checks. No device/account authorization is
added.

The parent repeats the exact current channel/advertisement/token/nonce/browser
binding. A bounded private stdin envelope reaches the owned Node browser process;
that process independently validates the binding and gives only the selected
URI, nonce and remaining lifetime to the isolated owned page. No provider cookie
or token storage, request interception or generic URL broker is present. Existing
source-only and MPD-only commands retain their earlier behavior.

The fresh headed Chrome 156 process uses its real navigator and ordinary browser
security. After preparation the runner clicks the muted Play diagnostic button
and observes at most eight seconds. Output is a closed outcome/boundary/stage,
optional numeric HTTP/SDK code, bounded decoded-video frame-callback count,
advancing position, dimensions, admitted request counts and
`last-sdk-buffered-playhead` closed format observations. It emits no URLs,
identities, grants, raw error payloads or SDK metadata. Frame callbacks are counted;
no pixels, frames, canvas, screenshots, recordings, traces or acoustic output are
captured. `DECODED` requires at least two callbacks, one second of advancing
position, positive dimensions and init/media responses without a refusal.
Only the last safe numeric callback position/dimensions survive cleanup, so a
later failure preserves the observed decoding history while remaining a boundary
outcome. Retired callbacks cannot change these observations; active format state
still clears on retirement.

An auxiliary refusal can also report one fixed `auxiliaryReason`: `UTC_TIMING`,
`SEGMENT_BASE`, `CONTENT_STEERING`, `PATCH_LOCATION`, `XLINK`, `XINCLUDE`,
`CHANGED_LOCATION`, `TEMPLATE_INDEX`, `REQUEST_TYPE` or `DISPATCH_TYPE`.
`UTC_TIMING` additionally reports only `utcScheme` values `DIRECT`, `HTTP_HEAD`,
`HTTP_XSDATE` or `OTHER`; recognized classes match the exact 2012/2014 DASH scheme
identifiers. No UTC value, timestamp, URL or unknown scheme string is retained.
These fields describe the first refusal and survive retirement. They
grant no auxiliary request admission. Node and Python independently
check the fixed values and their boundary/reason association before output.

This remains a bounded Linux development-browser observation. Linux is not an
ABEMA-listed desktop environment; this command does not establish support on
Linux, Windows/macOS, other browsers, other sources or a deployed product.

### First provider trial: October 26, 2026

The command above ran against signed revision
`80007e371c37b7f02b8add2028aeb5bc439bc2cf` on Linux x86-64 with ordinary headed
Chrome 156.0.8078.47, a fresh anonymous guest and the exact `abema-news` live
source. Default host networking was used; exit country was not independently
measured. The fresh provider page selected allowed GLOBAL division 1. The
selected-region, guest, matching-profile, media-token and channel requests
succeeded, and the unchanged selector's source, session and token bindings
passed. This is the public modern catalogue subset described above, without
device/account authorization or an entitlement conclusion.

The actual browser manifest Fetch returned HTTP 200. Its raw XML audit refused
with `boundary=AUXILIARY` and `publicationStage=IDLE`, before returning the MPD to
Shaka. The manifest counter increments only after that audit succeeds, so its
zero value does not mean no MPD was fetched. All initialization, media-segment,
denied-DRM and decoded-frame counters were zero; no advancing playback or positive
dimensions were observed. No license request or MediaKeySession was created.
Browser, server, container and private-runtime cleanup was confirmed, with no
grant persisted.

That revision grouped UTC timing, SegmentBase/index, steering/patch,
XLink/XInclude and changed-Location refusals under the same auxiliary boundary.
The trial did not retain the MPD or identify which individual feature triggered
it. The diagnostic now distinguishes those fixed audit reasons and UTC scheme
classes without retaining provider XML or URLs.

A second fresh trial on signed revision
`579a7618c95211e972768fda79efb1e645f5da51` used the same Linux/headed Chrome
156.0.8078.47, anonymous GLOBAL News source, owned loopback origin and default
host connection. Fresh context, guest/profile, media-token, exact channel,
verified assets and unchanged source-selector binding passed. The browser MPD
GET returned readable HTTP 200, then the XML audit reported
`AUXILIARY / UTC_TIMING / DIRECT`, with publication stage `IDLE`. No
initialization/media requests, decoded frames, license requests or
MediaKeySessions followed. Cleanup was confirmed with no grant persisted. Exit
country was not independently measured; full device flow and entitlement remain
unestablished.

This identifies the first refusal as an inline direct UTC timing declaration;
it does not establish that its value is valid or that admitting it would complete
parsing or playback. Its raw value was not retained. The bounded direct-only
admission below has controlled fixture verification and the fresh observation
that follows.
Neither trial establishes a DRM blocker or extension requirement. No fallback
followed.

### First decoded provider observation

A fresh trial on signed revision
`780554673dda1167a2356cd97548c4984bf290c3` used Linux x86-64, headed Chrome
156.0.8078.47, a fresh anonymous GLOBAL News live source, owned dynamic loopback
origin and default host networking. Exit country was not independently measured.
Fresh context, guest/profile, media token, exact channel, pinned assets and
unchanged selector binding passed again. The valid inline direct declaration
passed without altering the MPD or adding a timing request.

The owned Shaka/MSE player decoded 156 frames at 1280×720 and advanced 5,172 ms,
with boundary `NONE`, readable HTTP 200 and publication stage `PUBLISH`. The
accepted counters were two manifests, two initialization requests and 19 media
requests. Buffered-playhead observations reported AVC video and AAC audio;
decoded dimensions and advancement were numeric observations, not captured
pixels. Playback was muted and acoustic output was not tested. This is a bounded
observation, not sustained playback, manual quality or alignment acceptance.

Normal browser CORS/TLS, omitted credentials, refused redirects, exact reference
ownership, source/lifetime limits and protection denial remained active. No
license request or MediaKeySession was created. Owned cleanup was confirmed,
with no grant persisted. No provider embed, extension or media relay was used;
fresh source preparation still used the documented local backend/isolated helper,
so this does not establish website-origin-only bootstrap or select deployment.
No manifest, timing value, signed URL, provider body or media capture was retained.

This establishes this anonymous News source's bounded browser decoding. It does
not establish Grand Sumo, replay, entitled/protected sources, browser EME/opaque
licensing, advertising completeness, renewal or a complete product integration.
The full provider device flow and entitlement remain unestablished.

## Transport and lifecycle boundary

The XML audit permits only root-level DASH `UTCTiming` elements with the exact
`urn:mpeg:dash:utc:direct:2014` or legacy `:2012` scheme. The original MPD bytes
reach the pinned parser unchanged. The value must use this deliberately narrow
ISO subset: four-digit years 0001–9999, a valid Gregorian calendar date,
`T` followed by 00–23 hours and 00–59 minutes/seconds, optional one to three
fractional second digits, and explicit `Z` or an offset from `-14:00` to
`+14:00` (14-hour offsets require zero minutes). It must parse to a finite date.
Whitespace, missing zones, invalid calendar normalization, leap seconds, 24:00,
extended years and greater fractional precision are refused. The maximum spelling
is 29 characters. This subset is a diagnostic constraint, not complete XML Schema
date-time support. Invalid direct declarations retain `UTC_TIMING / DIRECT`.

Shaka 5.3.2 computes the direct value's clock offset locally with `Date.parse`
and `Date.now`; it issues no timing request. Its direct branch otherwise silently
uses zero for an invalid value, which is why the audit validates first.
`dash.clockSyncUri` is explicitly empty; every TIMING networking request remains
denied. HTTP-head/xsdate/iso, NTP and unknown schemes are still refused even when
listed after a usable direct declaration. The audit continues all same-element
protection/XLink and descendant checks after accepting direct timing.

The pinned parser applies clock synchronization during initial live manifest
construction when presentation-start timing is used; it does not resynchronize
on ordinary refresh. Every refreshed MPD still passes the audit before parsing
and publication. Direct timing affects media availability decisions, never source
or grant expiry, which retain their monotonic lifetime. The embedded time can be
inaccurate if a provider or intermediary serves an old MPD; client `no-store`
does not prove upstream freshness. This is not evidence of alignment between
feeds. See the [DASH-IF direct-time guidance][dash-direct] and
[pinned parser implementation][shaka-utc]. No timing server, new origin, license
operation or UX change is added.

The separate ABEMA diagnostic uses pinned Shaka 5.3.2's compiled debug distribution
because its init/media reference methods retain their public names. The ordinary
fixture/Twitch build is unchanged. Browser console methods, including assert,
are silenced before the provider diagnostic page loads; no debug log or trace is collected. The
debug dependency's unused Closure module-loader `eval` produces a build warning;
the page does not allow JavaScript `unsafe-eval`.

The page asserts `Permissions-Policy: encrypted-media=()` before constructing a
player. Protection declarations, encrypted SDK streams, and encrypted-media
events stop playback. LICENSE, KEY and SERVER_CERTIFICATE networking types are
refused independently; all other unadmitted types are refused as well. No
license endpoint, CDM session, keys, certificate exchange, DRM-ignore setting,
renewal or fallback is implemented. A missing PSSH declaration never establishes
that actual media is unencrypted.

All admitted media bytes use actual browser Fetch with CORS, credentials omitted,
redirects refused and no-store. The HTTPS plugin replaces Shaka's other HTTP/data
schemes and checks an unchanged per-request admission receipt before Fetch. There
is no XHR or native-media fallback. CSP permits only owned scripts, blob MSE media
and the selected manifest's exact HTTPS origin; another declared origin stops for
verification. The approved general integration scope remains applicable.

The manifest response is bounded and audited before Shaka parses it. DTD/entity,
XInclude/XLink, UTC/index/steering/patch requests, changed Location, protection
metadata and unbounded timelines stop. The wrapper validates each SDK manifest
before the SDK filter and binds the accepted model only after that filter
resolves; rejection aborts transport and retires the player without publishing
the candidate. Initial and refreshed manifests share this gate. Index construction
must be network-free. Init and media requests must match the SDK's resolved declared
reference URI, byte range, stream object, source lifetime and current or immediately
previous snapshot. A CDN family or sibling directory alone never admits a request;
foreign hosts and unverified media queries stop. Later snapshot publication
evicts older declarations. Dispatch consumes one admission receipt and rechecks
its retained snapshot/stream tuple before Fetch and during reads. Eviction of
an in-flight receipt aborts the attempt before stale bytes can reach Shaka;
duplicate Range aliases and post-admission header mutations stop before Fetch.
This deliberately supports a small DASH subset and may
stop on legitimate provider manifest features that need separate verification.

Source lifetime is at most the canonical attempt's remaining 120 seconds;
preparation is limited to 30 seconds. Requests are at most ten seconds each,
64 total/four concurrent, three manifest admissions, 512 KiB per MPD,
32 MiB per media response and 64 MiB aggregate. Retries, ABR and prefetch are
disabled. Range responses must have matching HTTP 206 Content-Range and length.
Stop/expiry abort transport and retire the owned player; concurrent stop callers
share destruction. An unconfirmed player/browser/process receipt is sticky and
prevents replacement. The parent uses the shared browser ownership verifier,
short runtime allocation/socket-budget preflight, known-process cleanup and
distinct retained-runtime reporting on uncertainty.

## Offline checks

```sh
mise exec -- uv run scripts/tests/web-abema-backend-playback.test.py
mise exec -- node --test scripts/tests/web-abema-browser-playback.test.mjs
TACHIAI_LOCAL_CHROME_TEST=1 mise exec -- node --test scripts/tests/web-abema-browser-playback.test.mjs
mise exec -- npm run test:abema:local --prefix apps/web
```

These use only synthetic protocol values, controlled owned fixtures and loopback
servers. They cover closed input/output, fresh context, no-provider refusal,
cleanup receipts, exact reference/range/stream ownership, templates/BaseURL,
snapshot eviction, early index refusal, source expiry, denied protection types,
each fixed auxiliary refusal and UTC scheme class, refusal preservation after
cleanup, independent invalid/private auxiliary-output rejection,
bounded direct-time date/zone validation, unchanged MPD bytes, actual pinned SDK
clock offset without timing requests, invalid-refresh refusal and owned direct
fixture decode/retirement,
actual CORS/redirect/credential behavior, frame advancement and shared/failed
retirement. The browser suite uses port 4186 and retains no capture artifacts.
Browser-free protocol tests run in repository hooks; CI adds local Chrome tests.
No actual provider acceptance is inferred from these tests.

[dash-direct]: https://dash-industry-forum.github.io/docs/DASH-IF-IOP-v4.3.pdf#page=125
[shaka-utc]: https://github.com/shaka-project/shaka-player/blob/v5.3.2/lib/dash/dash_parser.js
