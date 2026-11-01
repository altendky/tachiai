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
