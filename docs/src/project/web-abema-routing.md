# Local ABEMA route diagnostic

This is a development-only prerequisite for [#366](https://github.com/altendky/tachiai/issues/366).
It routes the existing exact replay source/MPD-metadata experiment through one
temporary userspace WireGuard instance. It does not select a web deployment,
installed companion, account store or product Routes implementation.

The host command reuses Android's ordinary-Go routebridge transport. It creates
an authenticated ephemeral loopback HTTP CONNECT listener, with provider TLS
remaining end-to-end. Destination TCP and DNS use the configured userspace
network stack; endpoint bootstrap DNS and outer WireGuard UDP use the host
connection. No OS TUN, root access or default-route change is requested.

## Run boundary

Complete the [web harness setup](../../../apps/web/README.md) first. Build the
ordinary host executable from its existing module:

```sh
cd apps/android/routebridge
go build -mod=readonly -o ../../web/build/route-trial ./cmd/route-trial
```

From the repository root, after confirming the selected peer is idle on every
other device:

```sh
uv run apps/web/scripts/abema-route-comparison.py \
  --run-route-replay-metadata --peer-idle-confirmed \
  --route-runner /absolute/path/to/apps/web/build/route-trial \
  --wireguard-file /absolute/path/to/export.conf
```

The existing export is read once inside the consumer. Its contents are never
copied into arguments, environment variables, logs or runtime files. Fresh local
proxy credentials travel only over private process pipes. The profile is not
imported into the app or retained in a new store. Do not run the same WireGuard
peer concurrently on another device: its endpoint can roam between clients.

The Go runner requires one bounded JSON line on stdin, then keeps stdin open as
its ownership signal. Explicit Stop, EOF, signals and lease expiry close the
route. Input, startup and cleanup have separate deadlines. An unresolved
constructor or close reports unconfirmed cleanup and exits nonzero; process
exit does not upgrade that verdict. The comparison uses a 180-second route lease
around the existing 120-second source attempt. `READY` establishes a listener,
not a successful tunnel handshake or measured Japan exit. The final report keeps
route cleanup and provider-attempt cleanup separate; its overall confirmation
requires both.

## Matching the two request paths

The explicit comparison injects one immutable private descriptor into both
backend workers and the source-bound browser manifest request. Runtime assets,
guest/profile/media-token setup and episode metadata/gateway operations use the
same route as the browser MPD request. The identity-only setup snapshot still
aborts all page requests and makes no provider operation. Downloaded provider
helpers retain their network-disabled containers.

Python uses an explicit HTTPS connection with authentication only in CONNECT
headers. It does not consult inherited proxy or `NO_PROXY` settings, and does
not follow redirects. Its routed TLS context retains normal certificate and
hostname verification without environment-driven TLS key logging.

The owned browser receives only the local proxy address in Playwright options.
Proxy usernames/passwords are never installed as generic HTTP credentials. A
private CDP hook answers only an exact proxy-source/origin/realm/Basic challenge;
origin-server and repeated challenges are canceled. Request-paused events resume
unchanged, without URL, header, body or response overrides. Owned tests verify
actual CORS preflight acceptance/denial and redirect behavior separately.

Only the exact owned loopback page server bypasses the proxy. Other implicit
loopback bypasses are disabled. QUIC and nonproxied WebRTC UDP are disabled for
this bounded TCP experiment. This is not a complete whole-browser traffic or
DNS/IPv6 leak audit. The hook is installed on the single owned diagnostic page;
it is not a general browser launcher or provider-login automation. The existing
replay source/session/expiry guards and the runner's reviewed-host allowlist
remain active.

The metadata experiment performs no initialization, media, advertisement,
license or certificate request and creates no CDM session. A readable MPD does
not establish replay decoding, entitlement, audible output, renewal or protected
browser playback. Compare source/account/region conditions before attributing
the earlier HTTP 403; a profile named Japan does not measure its exit region.

## Verification

The host Go suite includes actual owned WireGuard handshake, tunneled DNS and
CONNECT tests. Runner tests cover private input, strict decoding, cancellation,
lease expiry, constructor/cleanup deadlines and closed diagnostics.

```sh
uv run scripts/tests/web-abema-route.test.py
TACHIAI_LOCAL_CHROME_TEST=1 node --test scripts/tests/web-abema-route-browser.test.mjs
```

The Python suite uses owned TLS and proxy endpoints to verify explicit routing
despite poisoned proxy/bypass environment, CONNECT-only credentials, normal TLS
trust, refusal without direct fallback, child ownership and output redaction.
Chrome fixtures verify real proxy 407 and same-realm origin 401 handling, exact
harness bypass, CORS preflights, redirects, unavailable proxies and ordinary
untrusted-certificate refusal. A full routed replay CLI fixture returns a closed
transport-refusal receipt after owned TLS rejection, with cleanup confirmed;
the same envelope without the explicit flag refuses before browser launch.

These are Linux/Chrome owned-fixture results. No saved profile, provider account,
provider request, OS route change, TLS bypass or real Japan exit was used in
those tests. Physical/device behavior and actual provider-route acceptance remain
separate observations.

Primary interface references: [Chromium proxy behavior](https://chromium.googlesource.com/chromium/src/+/HEAD/net/docs/proxy.md),
[CDP Fetch authentication](https://chromedevtools.github.io/devtools-protocol/tot/Fetch/#type-AuthChallenge).

## Routed metadata observation

On November 1, 2026, one fresh metadata-only attempt on merged revision
`2e8401725a4c0f985e8402d05e769defebe616c2` completed successfully. It used Linux
x86-64, an owned headed Chrome major 156, a fresh anonymous guest/profile/token,
the exact replay above, and the existing Windscribe Tokyo Shinkansen export in
one temporary userspace route. The host runner was built from signed clean
`f521141d588a8b352b51a658e14a69c6fe4792b4`, SHA-256
`dc8415e2c8a8d9c62b5773eb0100340008b1dca6f88e3ecd7be30e5bf8a6a5c2`.
The profile stayed inside the private consumer. Exact browser patch, effective
exit country and independent complete route coverage were not measured.

Pinned assets, fresh guest, matching profile, media token, conservative exact
replay eligibility and gateway all returned HTTP 200. The unchanged selector
accepted source/session/token and the existing configuration subset with CUSTOM
protection and CSAI mode. The browser's exact normal-CORS MPD GET returned readable
HTTP 200 and DASH XML. The declared-subset graph reported one Period, two
AdaptationSets, ten Representations and SegmentTemplate, with content protection,
cenc, pssh and key-ID metadata present. Five references were unresolved by this
limited classifier, none invalid; all seven auxiliary-presence flags were false.
No PSSH/key interpretation or manifest retention followed.

The command exited zero. Route and attempt cleanup were independently confirmed,
with no host-route change, saved profile copy or persisted guest grant. No
initialization, media, ad, certificate, license or CDM operation occurred. The
earlier default-host 403 was not the failing boundary in this new routed/fresh
context; this later single run does not isolate its cause. Exact evidence is
recorded on [the investigation](https://github.com/altendky/tachiai/issues/366#issuecomment-6564017525).

## Separate one-initial-exchange EME diagnostic

The following distinct opt-in command continues a new fresh replay preparation
into one browser EME exchange. The metadata command above remains MPD-only.
After confirming that the selected WireGuard peer is idle elsewhere:

```sh
uv run apps/web/scripts/abema-route-eme.py \
  --run-route-replay-eme --peer-idle-confirmed \
  --route-runner /absolute/path/to/route-trial \
  --wireguard-file /absolute/path/to/export.conf
```

The original 120-second preparation/source deadline and 180-second route lease
remain. The source must select CUSTOM without a Widevine fallback. Only the new
command admits the source-validated initial-license host in its temporary route.
The exact license URI remains independently bound to the fresh program/session/
token configuration. The browser receives the verified fresh guest identity
needed by the unchanged response helper; no bearer, device ID, saved account or
cookie is transferred. These values use bounded private pipes, never files or
command arguments.

All four existing public bundles plus the already reviewed dash library are
downloaded at runtime, hash/size checked and independently verified by the browser
owner before execution. Application entry callbacks and dash auto-creation stay
disabled. No provider code is packaged, no original provider page/player is
loaded, and no response algorithm is copied into Tachiai.

The fresh page fetches only its bound MPD, validates the namespaced standard
CENC identifiers and constructs a standard common-system PSSH without decoding
provider-specific PSSH payloads. It requests a temporary `org.w3.clearkey` session,
checks its initial message against those same identifiers, and sends the original
message bytes in one exact JSON POST with normal browser CORS/TLS, omitted
credentials and refused redirects. The unchanged provider response helper uses
that attempt's profile-verified user identity. Its result goes through the
unchanged provider dash ClearKey adapter and `toJWK()` directly into the same
fresh browser session's `update()`. Tachiai does not inspect, normalize, export,
persist or reuse the returned keys.

This explicit common-PSSH/JSON-POST contract is a bounded diagnostic choice; it
does not reproduce automatic dash key-system/header selection. There is no
alternate header/body retry, renewal, second exchange, native fallback or media
relay. Browser-readable manifest and license statuses are reported separately;
the closed result retains the operational stage and requires session closure
and owned browser/server cleanup. A late accepted update cannot make an expired
or canceled attempt succeed. Overall cleanup still requires independent route
and provider-attempt confirmation.

The command stops at CDM update acceptance. It makes no initialization-segment,
media, advertisement or certificate request and creates no player. It does not
establish usable decoded content, source entitlement, provider ad equivalence,
renewal or web support. The existing no-license decoder and source-only helpers
retain their restrictions. Controlled owned fixtures and actual provider
observations are separate evidence.

### Initial provider exchange observation

One fresh run on signed clean
`9b0df7df1e5b3b242224cfcb35a8925a69bae2d1` passed on November 1, 2026. The
complete route/setup/exchange/cleanup operation took 52.95 wall seconds and
exited zero; this is not a media-playback duration. Host: Ubuntu 26.04.1 LTS,
Linux 7.0.0-34-generic x86-64. The installed Google Chrome reported
156.0.8078.79, and the fresh owned headed runtime verified Linux/Chrome major 156.
Browser ClearKey used a temporary session with no persistent-state or distinctive
identifier permission. Exit country and independent complete route coverage
were not measured. The existing WireGuard export and unchanged host runner
were the same as the metadata observation; no saved provider account or Android
cookie/grant was used.

Fresh guest, matching profile, media token, conservative exact-program
eligibility, gateway, source/session/token/configuration subset and all five
public runtime pins passed. The unchanged selector chose CUSTOM and CSAI. Chrome
read both the exact MPD GET and the single initial license Fetch as HTTP 200.
The initial request came from the fresh browser CDM; the unchanged provider
response helper and dash serializer produced the opaque bytes accepted by that
same session's update. The result reported UPDATE_ACCEPTED at UPDATE, with
session creation, request generation, license dispatch and explicit session
closure all confirmed. No provider CORS/header override or TLS bypass was used.

Browser/attempt cleanup and temporary-route cleanup were independently confirmed,
with no host-route change, persisted guest grant or profile copy. No player,
initialization/media segment, ad, certificate, renewal or second exchange
followed. This establishes one initial browser-CDM response-acceptance boundary,
not usable decryption, frames, acoustic output, entitlement, ad equivalence,
sustained operation or protected replay support. The next acceptance boundary
is source-bound protected decoding while retaining fresh session ownership and
the verified initial exchange.

The standard initialization/session interfaces are defined by
[CENC initialization data](https://www.w3.org/TR/eme-initdata-cenc/) and
[Encrypted Media Extensions](https://www.w3.org/TR/encrypted-media/).

## Separate protected replay decoder diagnostic

The distinct decoder command adds an owned muted Shaka 5.3.3 player to a new
fresh CUSTOM/CSAI replay preparation. The MPD-only and initial-exchange commands
keep their earlier limits. After building the web assets and confirming that
the selected WireGuard peer is idle elsewhere:

```sh
mise run web
uv run apps/web/scripts/abema-route-playback.py \
  --run-route-replay-playback --peer-idle-confirmed \
  --route-runner /absolute/path/to/route-trial \
  --wireguard-file /absolute/path/to/export.conf
```

The original source deadline of 120 seconds and route lease of 180 seconds stay
fixed. Browser preparation has a 30-second limit; observation stops after five
seconds of decoded media advancement or eight wall seconds, whichever occurs
first, within the remaining original lifetime. These are diagnostic bounds,
not a duration establishing sustained playback or renewal.

The renderer independently checks the fixed replay, advertisement, fresh token,
license URI and remaining lifetime. It audits namespaced standard CENC key IDs,
requires a stable set across manifest refreshes and supplies public temporary
ClearKey `DrmInfo` before SDK content filtering. Provider PSSH payloads remain
inert. The original MPD is not rewritten. Initialization/media requests retain
the existing exact SDK reference, stream, URI, range and current/previous
snapshot admission; unknown auxiliary transports remain refused.

One standard temporary browser session supplies the original initial request.
The initial exchange reuses the verified runtime-only response helper and dash
serializer. Standard EME hooks bind the actual session, challenge and update
without reading SDK key caches or response key fields. Readiness waits for the
actual native update acceptance, rather than SDK load completion. No alternate
key system, persistent session, certificate, advertisement, renewal or second
license request is admitted. The player stays muted; frame callbacks report only
counts, dimensions and media advancement. No video pixels or audio are captured.

The private proof server serves a frozen map of its own built scripts/Wasm,
dedicated proof HTML and verified public runtime assets on loopback. It exposes
no media relay or generic request endpoint. The page retains normal browser
CORS/TLS and receives no route credential, bearer or saved account. The provider
runtime's global network APIs remain blocked; the admitted player transport uses
the separately captured native Fetch. Headerly is not part of this command.

Retirement is terminal even before preparation. Cleanup independently closes
and awaits the actual session, destroys the SDK player, independently checks
standard MediaKeys detachment, restores owned hooks,
then closes the browser/server and temporary route. First closure uncertainty
stays uncertain after repeated cleanup and blocks another CDM owner in that
realm. Numeric evidence before and after
closure keeps readable license HTTP status separate from earlier MPD status;
missing/invalid renderer receipts cannot establish CDM cleanup.

Owned fixture tests use a hash-verified repository H.264/AAC source encrypted
locally with a fixed **public synthetic** key. They cover successful five-second
decoding, wrong-key/no-license refusal, exact one-exchange admission, original
lifetime, response identity, late retirement and sticky closure failure.

Asset preparation builds a separate pinned FFmpeg 8.1 copy-only tool from the
official source archive for this fixture. Older packagers can put encryption
metadata in the initialization file or accumulate earlier samples across later
fragments. The generator checks actual fragment-local `senc`/`saio`/`saiz`
metadata, sample counts and offsets before publishing fixture provenance. An
explicit `FFMPEG_ENCRYPTED` override must satisfy those same checks; the ordinary
clear-fixture `FFMPEG` choice remains independent. These are owned test-media
checks and do not parse or rewrite provider segments.

Run the owned suites:

```sh
npm run test:abema:protected --prefix apps/web
uv run scripts/tests/web-abema-episode-playback.test.py
TACHIAI_LOCAL_CHROME_TEST=1 xvfb-run --auto-servernum \
  node --test scripts/tests/web-abema-browser-episode-playback.test.mjs
```

Those fixtures establish the mechanism only. Actual provider decoding,
entitlement, ad equivalence, acoustic output, paired operation and sustained
playback require their own observations. This development diagnostic adds no
product Routes/account controls or common UX exception.

### Initial protected provider decode observation

One fresh run on signed clean
`8930f87cf1fe5df438120d0947c4b8a7f514122a` passed on November 1, 2026. The
complete route/setup/decode/cleanup command took 54.82 wall seconds and exited
zero. Host: Ubuntu 26.04.1 LTS, Linux 7.0.0-34-generic x86-64; the fresh owned
headed Google Chrome runtime reported 156.0.8078.79. Browser ClearKey used one
temporary session and the audited standard CENC initialization. The same
previously verified userspace route binary and user-authorized Windscribe Tokyo
export supplied both backend and browser traffic. Exit country and independent
complete route coverage were not measured. No saved provider account, Android
cookie or grant was used.

Fresh anonymous guest, matching profile, media token, conservative exact-program
eligibility, gateway, source/session/token/configuration subset and all five
runtime pins passed. The unchanged selector chose the fixed replay
`394-72_s10_p8529`, CUSTOM and CSAI. The owned player read one MPD, two
initializations and eight media segments; readable manifest/media and license
statuses were HTTP 200. Exactly one selected key-system access, MediaKeys object,
temporary session, initial message/request, license POST/response and accepted
native update were recorded. No DRM admission refusal or SDK error occurred.

Before stopping, actual frame callbacks reported **36 decoded frames at
854 × 480 and 5,739 ms of media advancement**. Cleanup retained those counts,
changed the policy to expected CANCELLED, explicitly closed that same session,
verified MediaKeys detachment and destroyed the player/browser/server. Both
provider-attempt cleanup and temporary-route cleanup were confirmed, with no
host-route change, persisted guest grant or profile copy.

This establishes bounded protected replay decoding through the Tachiai-owned
browser player in this exact environment. The player was muted; acoustic output,
quality/seek/alignment controls, paired feeds, advertisements, renewal and
sustained operation were not tested. Actual provider codec/track metadata was
not exported. Conservative free-window eligibility and successful decoding do
not independently establish entitlement or provider ad equivalence. No
certificate, advertisement, alternate header/body retry, renewal, second
exchange, Headerly/CORS override, TLS bypass, capture or media relay occurred.

## Separate mixed-browser transport fixtures

The next owned-only transport slice for
[#368](https://github.com/altendky/tachiai/issues/368) leaves the ABEMA route
options and authentication module unchanged. A separate mixed-only options
builder pins either existing Twitch live or replay case. It adds explicit
HTTPS/443 System bypass rules for the existing Twitch OAuth/GraphQL hosts and
media families; only replay adds the one already admitted distribution host.
ABEMA and unknown destinations retain the restrictive authenticated proxy.
This is destination selection, without a direct fallback, PAC or host-route edit.

A separate development-only CDP Fetch owner combines exact proxy authentication
with the existing scoped Headerly media behavior: remove only request Origin,
then set only response Access-Control-Allow-Origin to the exact loopback harness
origin. The browser-controlled initiating Origin, GET/HEAD and Fetch/XHR type
must match, and the response remains bound to the original request ID, URL,
method, frame and resource type. OAuth/GraphQL receive no header rewrite.
Original status and every other header remain unchanged; no body inspection,
fulfillment, redirect, cookie or TLS override is added. This is privileged
diagnostic header rewriting, not ordinary website CORS support or a selected
shipping extension/companion architecture. Existing ordinary browser rules are
independent of this disposable-browser mechanism.

Owned Chrome 156.0.8078.79 fixtures verify the split using an authenticated
refusing proxy and a DNS-remapped loopback TLS-preface sink. Accepted System
destinations never reach the proxy; ABEMA, unknowns, lookalikes and other
schemes/ports never reach System. No provider TCP/TLS session or response is
used. Separate HTTP loopback fixtures verify CORS off/on/off, same-origin child
requests, wrong initiator/method refusal, exact response bytes/status, ordinary
untrusted-TLS refusal, bounded state and cleanup. The HTTP fixture seam cannot
admit a remote target. These tests establish mechanisms and boundaries, not an
actual provider HTTPS result or complete route coverage.

Run the browser-free protocol cases with:

```sh
node --test scripts/tests/web-mixed-route-browser.test.mjs scripts/tests/web-mixed-browser-headers.test.mjs
```

Set `TACHIAI_LOCAL_CHROME_TEST=1` to include the owned Chrome checks after the
usual web dependency/browser setup. They retain no provider profile, grant,
request URL or unrestricted trace. A real mixed-provider trial still needs
provider-bound player adapters, fresh user-controlled Twitch activation before
the original ABEMA attempt starts, same-session blocked Play and independent
CDM closure, grant revocation, browser and route cleanup. No such trial is
performed by these fixtures.
