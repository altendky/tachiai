# ABEMA web bootstrap feasibility

Investigation for [#366](https://github.com/altendky/tachiai/issues/366), performed
October 25, 2026 using harness baseline
`2f7a587a3cca71d224418c00961253883da8dd7f`. The current Android bootstrap cannot be
moved unchanged to an ordinary Tachiai web origin: its guest and media-token
requests encounter an origin restriction before a selected playback session can
be established. This is a reproducible blocker for that path, not proof that all
possible browser integrations fail. A separate extension-free backend experiment
successfully completed fresh guest/profile/media-token setup, as recorded below.

## Environment and scope

The trial used Linux x86-64, official Google Chrome 156.0.8078.47 and a fresh
browser context at `http://127.0.0.1:4173`. No provider account/cookies were
present; no source was selected, because the failure precedes source preparation.
Networking used the host's default browser connection; exit country, DNS/IPv6
coverage and a provider-specific route were not measured. The installed Widevine
module inventory reported 4.10.3112.0, without establishing a working DRM session.
This host is not an ABEMA-listed desktop environment; Windows Chrome/Edge and
macOS Chrome/Safari remain separate validation targets.
[ABEMA supported environments](https://help.abema.tv/hc/ja/articles/360013743211-ABEMAを利用できるデバイス)

All requests were credential-free. The browser guest request used deliberately
incomplete JSON; the media-token request used a fixed invalid placeholder, not a
saved grant. No provider response body was read, no licensing response was
requested or inspected, no DRM session was created, and no media was played or
captured. No redirects, TLS exceptions or browser-security overrides were used.

## Measured results

| Boundary | Observation | What it establishes |
| --- | --- | --- |
| Browser EME, `org.w3.clearkey` | Temporary-session `cenc` AVC/AAC configuration accepted | This browser exposes the requested interface configuration; no key/session/license/playback acceptance follows. |
| Browser EME, `com.widevine.alpha` | Same configuration accepted | Separate interface availability only. |
| Browser guest request | Fetch failed with `TypeError` | No readable guest response from this origin. |
| Browser media-token request | Fetch failed with `TypeError` | No readable token response from this origin. |
| Guest preflight, localhost Origin | HTTP 403, no Access-Control-Allow-Origin | Server refuses the intended JSON POST's origin preflight. |
| Guest preflight, provider Origin control | HTTP 204, matching Access-Control-Allow-Origin | The same endpoint permits its provider origin in this control. |
| Media-token preflight, localhost Origin | HTTP 403, no Access-Control-Allow-Origin | Authorization-bearing GET is refused at this boundary. |
| License endpoint preflight, localhost Origin | HTTP 200, matching Access-Control-Allow-Origin | Only these response fields were checked; requested method/header permission, license contract and entitlement were not established. |

Additional credential-free HTTP observations found that public channel metadata
reflects localhost CORS, while a pinned public application bundle responds to
HEAD without Access-Control-Allow-Origin. Those were header observations, not
browser bootstrap, fetched-bundle verification or media acceptance. A synthetic
hosted Tachiai Origin also received guest-preflight 403 without that header.
Further probes found the same refusal for JSON POST to both legacy and modern
`/v1/users`, authorization-bearing legacy/modern media-token GETs (including the
current public OS/app query shape), and ABEMA device-authorization/token POSTs.
These narrow the exact request alternatives tested; they do not establish that
every possible extension-free protocol fails.

`TypeError` alone does not distinguish CORS from every network failure. The paired
preflight observations and provider-origin control are the narrower evidence for
the origin restriction here. CORS decisions belong to the responding server;
`no-cors` would provide an opaque response rather than usable session data.
[CORS behavior](https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/CORS)

## Extension-free backend result

On the same date, an initial ad-hoc bounded native-HTTPS workflow fetched all four approved public
bundles and verified their existing sizes/SHA-256 hashes. Only required unchanged
guest helper/DTO modules executed inside a disposable Node 24.15.0 container,
using the pinned diagnostic image
`mcr.microsoft.com/playwright@sha256:9bd26ad900bb5e0f4dee75839e957a89ae89c2b7ab1e76050e559790e946b948`.
The outer container, rather than Node VM, provided isolation: no network, read-only
filesystem/public-code mount, non-root user, dropped capabilities, no new
privileges, resource limits and Docker logging disabled. No host credentials or
Docker socket were mounted. The request body crossed only a bounded private pipe
into the native HTTPS owner; it was never printed.

That owner made one cookie-free guest POST (200), verified a matching fresh profile
through GET (200), and obtained a shape-valid media token through GET (200). It
used normal TLS, refused redirects and ambient proxy/cookie/authorization state,
then discarded the anonymous identities/grants. The container was removed and
public runtime files cleaned up. No source, manifest, media or license request was
made; no existing account was accessed. Exit region was not measured.

This proves anonymous backend transport/bootstrap viability without an extension.
It does not prove browser-direct access, account login, selected-source/DRM
playback, route equivalence or a hosted deployment. An installed backend on every
viewer's machine would be a companion; this development proof does not select one.

The first source-proof execution of the reusable diagnostic later stopped before
its guest POST. Review found an inherited guard mismatch: the pinned provider
module's web-device enum is the string `DEVICE_TYPE_WEB`; the unchanged provider
DTO serializer converts that enum to numeric wire value `3`. The reusable helper
had incorrectly required the enum itself to be numeric. Its synthetic fixture
had modeled the same incorrect type. The owned guard and fixture were corrected,
while retaining the exact numeric DTO check and the guest runtime's original
absent browser globals. A local, networkless execution of the unchanged pinned
guest factories then prepared an accepted DTO. The earlier successful ad-hoc
experiment is distinct from that corrected reusable version's live result below.

## Bounded selected-source proof

The next development diagnostic extends that fresh chain with exactly one
cookie-free GET of the existing legacy channel catalogue, bounded to 512 KiB.
It requires exactly `abema-news`, positively opened viewing, valid optional
`status.drm` metadata and an advertised DASH URL under the approved single-label
ABEMA Akamai family. Present DRM flags must be boolean false; absence remains
absent in the projected metadata. As in the current Android adapter, the
unchanged selector receives `isDrm: channel.status?.drm === true`; absence makes
that selector input false, without establishing that media is unencrypted or
entitled. These are preparation guards, not evidence of media
encryption, entitlement or playback. Malformed, missing, duplicate, closed or
incompatible metadata stops the attempt; no alternate channel/protocol/context
or ad-free fallback is attempted.

A fresh headed desktop Chrome context supplies its actual navigator input.
All its page network routes are blocked; it opens no provider page. The unchanged
pinned UA classifier must identify Chrome 156, Blink and Linux. Headless Chrome
has a distinct provider classification and is refused rather than given a
replacement user agent. The unchanged source selector receives only the exact
channel's playlists, the fresh media token and that browser classification, with
`preferDashPlaylist=true` and `enforcePlainStream=false`. Its static dependencies
include DRM names/constants; no detection or licensing operation is invoked.
Imported diagnostics receive process-local event registration and console
methods that discard arguments, without DOM, storage or network facilities.

This second helper runs in its own verified networkless, read-only, non-root
container with capabilities dropped, logging disabled and core dumps disabled.
Only the minimal selected-channel/browser/token context and a fresh attempt
nonce cross bounded stdin; its private result crosses bounded stdout. Neither
enters command arguments, files or logs. The HTTPS owner separately verifies the
nonce, exact channel, advertised playlist choice, fresh token, closed query
schema and approved origin family. It then discards the result without fetching
the MPD or any media/license resource or creating a MediaKeySession. Public
runtime files and owned containers are removed on both acceptance and refusal;
unconfirmed cleanup fails closed. Host loss or a hard kill cannot prove cleanup.

The unchanged pinned source helper passed a synthetic-input container trial
with actual headed Chrome 156.0.8078.47 on Linux. The 32 offline guest/source tests
cover selection/refusal, query/session/browser binding, unchanged selector
arguments, discard-only diagnostics, bounded private pipes and owned cleanup.

After pre-execution review and the guest guard correction, a fresh-chain trial
reached the catalogue's HTTP 200 response but refused at the metadata stage. That
version required an explicitly false DRM flag. A separately approved single
grant-free catalogue GET then reported only closed diagnostics: `DRM` refusal,
`statusPresent=true`, `drmPresent=false`, with neither an explicit true nor false.
No raw catalogue or URL was printed or retained. Offline comparison established
that the extra explicit-false requirement was stricter than the existing Android
and unchanged provider-selector contract. The corrected projection preserves
absence and retains the existing rejection of present true/nonboolean flags.

The final reviewed fresh-chain source-only trial on October 25, 2026
(October 26 UTC) succeeded:
guest POST 200, matching-profile GET 200 (`identityMatched=true`), shape-valid
media-token GET 200, and one channel-catalogue GET 200. It accepted exactly
`abema-news` with `statusPresent=true` and `drmPresent=false`. The unchanged
selector then returned an advertised source whose channel, attempt nonce and
fresh token passed independent validation (`sourceMatched`, `sessionMatched`
and `tokenMatched` all true). The public destination classification was
`APPROVED_ABEMA_AKAMAI_SINGLE_LABEL`; the private URI was discarded.
No MPD, media, license or MediaKeySession operation followed, no fallback was
tried, and cleanup was confirmed with no grants persisted. Default host
networking was used; exit region and provider-specific routing were not measured.
This establishes bounded anonymous source preparation without an extension,
not playback acceptance, browser-direct bootstrap or a selected deployment design.

## Manifest-only diagnostic gate

The separate development-only manifest proof preserves the source-only command's
stop boundary. Its private post-binding continuation can make one additional
cookie-free, authorization-header-free GET of the exact freshly selected MPD.
Before dispatch, the native owner repeats channel, attempt nonce, fresh-token,
advertised-playlist, query and approved-origin validation. It accepts no supplied
URL, redirect, arbitrary fetch, fallback or reusable session. The request has a
10-second worker deadline and a 512 KiB response bound; non-200 bodies are never
consumed. Status and MIME classification are closed diagnostics.

A 200 response with an admitted XML MIME type is parsed locally using Python's
standard ElementTree parser with UTF-8/document/tree limits, rejecting DTD/entity
declarations and XInclude. No external resource loader or inclusion expansion is
used. The only content evidence is namespaced MPD acceptance and structural
`cencPresent`/`psshPresent` booleans. IDs, attribute values, representation URLs and
PSSH text are not inspected or decoded. MPD bytes remain in bounded private IPC
and memory, then are discarded. No initialization data, segments, CDM, license
adapter or response filter is requested or invoked.

After pre-execution review, one fresh-chain manifest-only trial on October 26,
2026 at approximately 04:36 UTC used Linux x86-64 and the default host connection.
Headed Chrome 156.0.8078.47 supplied source-selection identity only. The MPD GET
used native Python urllib HTTPS, its default User-Agent, and no Origin or Referer
header; it was not a browser-origin request or CORS observation. Guest, matching profile,
media-token and exact News catalogue requests returned 200; the unchanged source
helper's channel, attempt nonce and fresh-token binding checks passed. The single
exact MPD GET then returned 403 with `sourceBound=true`,
`mime=NOT_CHECKED` and `manifestAccepted=false`. Its refusal body was not read,
so no cenc/pssh presence observation was made. The status alone does not establish
whether region, entitlement or another provider request policy caused refusal;
exit region and provider-specific routing were not measured.

The attempt stopped without fallback, retry, initialization/segment requests,
CDM or licensing operations. Owned browser/container/worker/public-runtime cleanup
was confirmed and no grants, private URI or MPD body were persisted. This is
evidence of CDN refusal for that request context, not regional diagnosis or
license/playback acceptance. The implementation's offline binding, MIME/size,
redirect-body, XML entity, privacy and worker teardown checks passed.

```sh
mise exec -- uv run scripts/tests/web-abema-backend-manifest.test.py
mise exec -- uv run apps/web/scripts/abema-backend-manifest.py --run-manifest
```

Only the second command contacts providers, and only with the exact opt-in flag.
It requires the source proof's same preinstalled image, dependencies and fresh
headed Chrome environment. Keep it outside routine CI. No additional provider
JavaScript is packaged by this gate.

### Separate browser-origin MPD gate

The explicit `--run-browser-manifest` command repeats the fresh chain and source
binding, then performs its sole MPD GET through actual headed Chrome on an owned
dynamic loopback origin. It uses a fresh profile with service workers blocked and
normal fetch CORS, credentials omitted, redirects refused, cache disabled and a
10-second/512 KiB bound. No request interception, fabricated preflight, native
response/header bypass, provider page or automatic native fallback is installed.
Chrome supplies its real User-Agent, Origin and Referer behavior.

Python revalidates the full binding before passing only the private URI and
attempt nonce through bounded stdin. The browser result and optional MPD bytes
return through bounded private stdout. A readable CORS response can report
`browserReadable=true`, `corsPermission=CORS_READABLE`, status and admitted MIME.
A rejected fetch reports `UNESTABLISHED` without inferring a hidden HTTP status
or distinguishing CORS from network/TLS/redirect failures. Non-200 bodies remain
unread; admitted XML uses the same structural-only parser.

After separate pre-execution review, one fresh browser-origin trial on October
26, 2026 at approximately 05:04 UTC used headed Chrome 156.0.8078.47 on Linux
x86-64 with the default host connection and a fresh owned loopback page. Guest,
matching profile, media-token and exact News catalogue requests returned 200;
fresh source, attempt nonce and token binding passed. The sole browser MPD GET
returned a readable 403: `sourceBound=true`, `browserReadable=true`,
`corsPermission=CORS_READABLE`, `mime=NOT_CHECKED`, `manifestAccepted=false`.
Normal Chrome CORS permitted reading this particular response from that origin;
HTTP access was refused. Its body was not consumed, so cenc/pssh presence remains
unobserved. Region, entitlement and other provider request policy remain possible
unresolved causes; exit region/provider routing were not measured. This result
does not extend the CORS finding to other endpoints or origins.

No retry, fallback, initialization/segment, CDM or license operation followed.
Browser/server/container/worker/runtime cleanup was confirmed, with no grants,
private URI or MPD persisted. The native and browser observations independently
establish refusal in their tested request contexts, without identifying its
cause or demonstrating license/playback acceptance.

The Node owner closes its loopback server/browser. Because Playwright detaches
Chrome, public process ownership records additionally bind its documented PID,
start time, process group and profile path to the fresh runtime. Python verifies
ownership before any forced group cleanup and removes that runtime only after
termination is confirmed. Interrupted/unverified launches retain it and fail
closed. No private URI, token or debugging endpoint enters those records.

```sh
mise exec -- uv run apps/web/scripts/abema-backend-manifest.py --run-browser-manifest
TACHIAI_LOCAL_CHROME_TEST=1 mise exec -- uv run scripts/tests/web-abema-backend-manifest.test.py
TACHIAI_LOCAL_CHROME_TEST=1 mise exec -- node --test scripts/tests/web-abema-browser-manifest.test.mjs
```

The first command is the separately opted-in provider trial. The latter commands
use synthetic data/local servers only: actual CORS rejection/readability,
credential omission, redirect refusal and detached-browser cancellation with an
unrelated process left untouched. Repository hooks run browser-free protocol
tests; the web CI job also runs these local Chrome regressions after installing
its browser dependencies. Neither CI path invokes a provider command.

## Public runtime interface and regional context

An anonymous public entry-page review located the opaque adapter missing from
the initial four bundles. The page-identified
[ABEMA DASH library](https://abema.tv/assets/compat/62ff7b58b1651206.abema-dashjs.js)
contains module 58482, reports version 4.7.2-abema.4, and measured 617,926 bytes
with SHA-256
`988f8bc1a196ae74faf5c9650ddce0d1104c4816a663e9ab534c6b2a1c87cab8`.
Module 9065 was found in the page-identified
[support library](https://abema.tv/assets/compat/7459be98c1074c5c.youboralib.js),
160,733 bytes with SHA-256
`c7c1b2c250b5f526ebefc7085a54b50e338d636421729d5a2ead3d5315640fea`,
matching the existing Android CSAI support pin. No provider JavaScript was
packaged in the application or added to its runtime pin list by this review.

One isolated DOM-free Node interface check could not load module 58482 despite
complete static dependencies. A separate fresh headed Chrome 156/Linux blank-page
check loaded both modules and verified FactoryMaker and the named ClearKey factory
lookup. Auto-creation was disabled before loading, all page routes and service
workers were blocked, no network request was attempted, and cleanup was confirmed.
No factory instance, player, CDM, response filter or licensing method was invoked.
This establishes browser interface availability; opaque response compatibility
with a fresh browser CDM remains untested.

After the manifest trials, two separate credential-free status-only checks used
the same direct host connection, normal TLS, no ambient proxy, no redirects and
no response-body consumption. The already approved global regional gate
`https://ds-glb-linear-abematv.akamaized.net/region` returned 200; the separate
domestic gate `https://ds-linear-abematv.akamaized.net/region` returned 403.
The [documented provider regional check](media-origin-approvals.md) treats 403 as
regional refusal. The domestic gate therefore refused this request context even
though the global gate accepted it. This does not measure the exit country or
prove that regional refusal is the sole cause of the selected MPD's 403.

## Android comparison and remaining gates

The current Android DASH host explicitly selects `C.CLEARKEY_UUID` with
`FrameworkMediaDrm`; it must not be described as a Widevine-only implementation.
Its provider helper exchange remains opaque. Browser ClearKey interface
availability does not establish that the unchanged helper accepts the browser's
challenge/session contract, even where both use common initialization data.

The Android browser/runtime can use a provider-shaped synthetic origin together
with native request hosting. An ordinary website cannot adopt another site's
origin. Moving the page to localhost, installing a PWA, adding Wasm or selecting
Shaka does not supply that hosting behavior or grant source entitlement.

The direct-browser blocker and bounded anonymous source proof leave account
authorization, browser DRM/session binding, successful manifest/segment access,
opaque license exchange, live/replay controls, required advertising, renewal,
quality, sustained two-feed output and acoustic acceptance unestablished. No account
grant was adopted or persisted. The probe closes its fresh context/browser and
owned loopback server after success or failure; it does not exercise media-player
cleanup, which has separate controlled-fixture coverage.

## Reproduction

Install the pinned npm dependencies and ordinary Chrome as described in the
[harness README](../../../apps/web/README.md). Stop any server on loopback port
4173; the probe refuses to share an occupied port. From the repository root:

```sh
mise exec -- npm run probe:abema --prefix apps/web
mise exec -- npm run probe:abema --prefix apps/web -- --network
```

The default performs only local-page/CDM-configuration checks. The explicit
`--network` mode adds the fixed credential-free public-origin requests and
preflight control. It prints only closed outcomes, HTTP status, normalized origin
permission and environment metadata. It accepts no token, URL, account/profile
path or source input; reads no response body; and creates no MediaKeySession.
Do not add this live provider diagnostic to routine CI. Results can change;
record a new dated observation rather than making server policy a test fixture.

The separate backend proof is opt-in and requires Docker with the pinned
diagnostic image already available. It never pulls images automatically. It
creates a fresh anonymous guest and discards its grants, so use it to reproduce
that experiment rather than merely inspect the recorded result:

```sh
mise exec -- uv run apps/web/scripts/abema-backend-bootstrap.py --run
mise exec -- uv run scripts/tests/web-abema-backend-bootstrap.test.py
```

Without `--run`, the backend command performs no provider or Docker operation.
The script owns an ordinary temporary directory (the caller may set TMPDIR),
mounts only its public runtime files read-only, and closes its container/public
files before creating the guest. Native HTTPS runs in bounded private workers;
no existing cookies, proxy credentials, Docker login configuration or grants are
inherited. Output contains fixed phases, HTTP status and boolean acceptance/
cleanup evidence, never request bodies, account IDs, tokens or private URLs.
The offline regression command uses synthetic replies/containers only; it runs
in repository checks and does not contact providers or run real Docker jobs.
Cooperative termination attempts owned cleanup; a hard process kill or host loss
cannot establish cleanup. Unconfirmed helper cleanup refuses the guest stage.

The separate source-only proof is explicitly opted into with:

```sh
mise exec -- uv run apps/web/scripts/abema-backend-source.py --run-source
```

It requires the same preinstalled diagnostic image, web npm dependencies and a
working desktop display for fresh headed Chrome. It accepts no supplied token,
URL, account or source. Without exactly `--run-source`, it performs no browser,
provider or Docker work. It makes no manifest, media, license or key request and
must remain outside routine CI. Core dumps are disabled for its owner and
private workers as well as the private-input container. The offline test command
above covers both proofs without real provider/Docker/browser jobs.

The separate `--check-catalogue` mode performs just one cookie-free, grant-free
GET of that fixed public catalogue. It opens no browser/container and does not
invoke the source selector. It emits only closed refusal categories
(`SIZE`, `JSON`, `SCHEMA`, `EXACT_MATCH`, `OPEN`, `STATUS`, `DRM`, `PLAYLIST`)
or acceptance, plus optional metadata-presence booleans. Transport refusals remain
fixed outcomes. This mode also requires explicit opt-in and stays outside CI.

## Next decision

Before another MPD or CDM attempt, compare the current provider/native source
context against the legacy-chain proof and establish one known working domestic
route and entitlement context. Reproduce the selected source in that context
before deciding whether extra backend transport is necessary. The observed
manifest and domestic-gate refusals do not themselves require an extension or
justify changing origin, entitlement or TLS enforcement.

The user's October 25 direction strongly prefers avoiding an extension, with one
potentially acceptable only if needed. This is not approval to install one or to
change any product UX. Keep browser-owned media/EME as the candidate playback
boundary and investigate browser-compatible provider bootstrap/account handoff
first. A narrowly scoped web backend is another installation-free candidate;
it needs explicit account/route/session trust design and is not selected here.
If those approaches cannot satisfy the requirements, investigate the smallest
bounded extension/native operation needed for the demonstrated origin gap. Extension
origin requests with exact host permissions are a candidate; content scripts do
not automatically acquire that access. Verify source/session provenance and
runtime byte hashes before using any result. Classic cross-origin script loading
is a different execution/verification design, not a substitute for verified bundle
fetches. A TLS-preserving CONNECT proxy alone does not rewrite CORS response
headers. [Chrome extension networking](https://developer.chrome.com/docs/extensions/develop/concepts/network-requests)

No extension, companion, generic fetch broker or media relay is selected or
installed by this investigation. [#367](https://github.com/altendky/tachiai/issues/367)
owns the account/route/deployment comparison. The existing entitlement, unchanged
helper, no-key-extraction and no-response-reuse boundaries remain in force. Any
resulting product UX difference still requires its specific approval.
