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
retain their restrictions. Runtime provider acceptance for this new command is
unverified; controlled owned fixtures and actual provider observations must be
reported separately.

The standard initialization/session interfaces are defined by
[CENC initialization data](https://www.w3.org/TR/eme-initdata-cenc/) and
[Encrypted Media Extensions](https://www.w3.org/TR/encrypted-media/).
