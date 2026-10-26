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

On the same date, a bounded native-HTTPS workflow fetched all four approved public
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

The direct-browser blocker and limited backend proof leave source/account/session binding, manifests/segments,
opaque license exchange, live/replay controls, required advertising, renewal,
quality, sustained two-feed output and acoustic acceptance untested. No account
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

## Next decision

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
