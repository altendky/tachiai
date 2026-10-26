# Web account and route assessment

Assessment for [#367](https://github.com/altendky/tachiai/issues/367), October 25,
2026, following the [browser harness](web-client.md) and
[ABEMA bootstrap experiments](web-abema-feasibility.md). The owner's current
direction strongly prefers avoiding an extension. The evidence supports further
work on a web UI with browser-owned media and a narrowly scoped backend before
proposing a client installation. It does not select a hosted deployment, account
store, media relay, extension or local companion.

## Evidence vocabulary

**Measured** means a bounded result on this Linux/Chrome or native-HTTPS host.
**Documented** means an upstream interface exists. **Candidate** is an unverified
combination. **Unresolved** needs an implementation/experiment or product decision.
The ordinary browser, a server backend and an installed client helper have
different network and storage authority; success in one does not transfer to the
others.

The browser observations used fresh Chrome 156.0.8078.47 contexts, localhost
origin, no provider account and no retained grants. Temporary ClearKey/Widevine
configuration checks created no DRM sessions. The separate backend experiment
created and discarded a fresh anonymous ABEMA guest/profile/media-token chain,
without a selected source or media/license request. Exit region, acoustic output,
configured routes and DNS/IPv6 coverage were not measured.

## Request and integration matrix

| Operation | Ordinary website | Backend or optional installed mechanism | Remaining gate |
| --- | --- | --- | --- |
| Provider-controlled login | **Documented:** navigate to a provider page or supported authorization flow. Tachiai cannot read its private DOM/cookies/requests. | A backend can participate in a verified callback/device flow; an extension is only a candidate if a necessary handoff is otherwise unavailable. | Account selection, cancellation and positive return validation; no password collection or login-page injection. |
| Twitch authorization | **Measured:** selected public-client device challenge accepted from localhost; unactivated challenge was discarded. Token validation with a fixed invalid placeholder returned a readable 401. | Existing selected Smart TV public-client device flow is a candidate for a fresh browser-local grant; a new registration is not an established prerequisite. | Actual user consent, exact client/user/scope/expiry validation, private playback fit and renewal. |
| ABEMA guest/profile/media-token setup | **Measured:** current direct browser guest/media-token paths refuse origin preflights. **Measured:** native backend chain succeeds with a fresh matching anonymous identity. | A fixed-operation backend can avoid that browser-origin boundary without a client extension. | Backend/browser source, account, region/IP and DRM-session binding. A guest is not a connected provider account. |
| ABEMA account handoff | **Unresolved:** Android's trusted request observation is not an ordinary page API. | Investigate the existing provider device-authorization path through a narrow backend before proposing an extension observer. | Real provider consent/polling, original-account verification, lifecycle and protected adoption; anonymous success is insufficient. |
| Catalogue | **Measured headers:** public ABEMA channels reflect localhost CORS. Authenticated operations need individual checks. | Direct fetch where permitted, otherwise a specific authorized backend operation. | Account/instance provenance, response/resource validation and entitlement; public metadata is not playback authorization. |
| Verified runtime bundles | Direct cross-origin fetch/hash verification is blocked/unproven for the pinned bundle whose response lacks CORS permission. | **Measured:** backend fetch verifies all four existing pinned hashes/sizes before isolated unchanged-module execution. | Version/pin review, bounded execution and controlled delivery. Never replace verification with unverified cross-origin script execution. |
| Manifests and media | **Documented:** browser media/Shaka handles suitable HLS/DASH; controlled fixtures decode. No ABEMA selected-source acceptance yet. | Prefer direct browser media if provider CORS/session authority permits it. Consider a typed broker only for a measured gap. | Every redirect/CDN, source/session entitlement, advertisements, consumed formats and sustained playback. A backend setup call does not route browser segments. |
| Licensing and DRM | **Measured:** temporary ClearKey and Widevine configurations available; ABEMA license preflight returns 200 plus matching origin header. | Keep browser EME as candidate decryptor; a backend can relay only the verified opaque operation if its contract permits it. | Requested method/header permission, fresh CDM challenge/response, source/session binding, lifetime and output restrictions. No keys inspected/extracted or manually supplied to Shaka. |
| Configured proxy/VPN and DNS | An ordinary page has no equivalent of Android's per-provider native connection factory/tunnels. | Native backend transports are a candidate. An extension proxy API or local route/storage companion is a separate optional architecture. | Authentication, catalogue/bootstrap, redirects, manifests, segments, licensing/renewal, DNS/IPv6 and no unintended direct fallback. |

The browser same-origin boundary and extension-origin host-permission model are
different. Permissioned extension fetch does not automatically apply to content
scripts or confer provider entitlement. A CONNECT tunnel forwards end-to-end TLS;
it cannot rewrite the origin's encrypted CORS headers. Native messaging is a
control channel, not proof that browser media takes the native route.
[Chrome extension networking](https://developer.chrome.com/docs/extensions/develop/concepts/network-requests),
[proxy API](https://developer.chrome.com/docs/extensions/reference/api/proxy/),
[native messaging](https://developer.chrome.com/docs/extensions/develop/concepts/native-messaging)

Twitch's documented public-client device flow needs no client secret/redirect URI.
The selected experimental identity is not a claim of a provider-supported web
integration. Preserve it for the current investigation rather than declaring a
new app registration mandatory without evidence.
[Twitch device flow](https://dev.twitch.tv/docs/authentication/getting-tokens-oauth/#device-code-grant-flow)

## Persistence, isolation and recovery

| Concern | Browser-only facilities | Backend/installed candidate and acceptance |
| --- | --- | --- |
| Public configuration | Origin/profile-bound IndexedDB or browser storage, with schema/version checks. | Decide export/import and recovery needs without including private grants in public configuration. |
| Provider grants | Current diagnostics retain none. Browser memory is a bounded test lifecycle; storage/Web Crypto is not proof of Android Keystore equivalence. | A backend needs an explicit user/instance trust boundary and protected store before retention. A native companion could use OS credential storage; it is not selected. |
| Multiple provider/account instances | Separate app metadata does not create independent provider cookie stores. | Fresh instance-owned grants with verified identity; never silently borrow another account's session or route. |
| Restart, expiry and revocation | No persistence/renewal implementation or acceptance yet. | Require exact owner generations, coalesced replacement, verified adoption, expiry and stale-result refusal. Ordinary page lifetime is not a scheduled background worker. |
| Lost network/helper/permission | No automatic alternative path has been selected. | Fail visibly and preserve configured route/account identity. Test no direct escape, no capturing unrelated browsing and cleanup/recovery before claiming route parity. |

IndexedDB durability is origin/profile dependent, and script-accessible grants
remain exposed to compromised same-origin script. Cryptographic primitives or
non-extractable browser keys alone do not establish an OS-protected grant store.
[IndexedDB](https://developer.mozilla.org/en-US/docs/Web/API/IndexedDB_API),
[Web Crypto](https://developer.mozilla.org/en-US/docs/Web/API/Web_Crypto_API)

## Recommended next experiments and decisions

1. Prefer extension-free work: verify exact selected-source preparation through
   the bounded backend, then a fresh browser ClearKey exchange with unchanged
   helpers and an opaque response. Bootstrap success alone does not settle either.
2. Test direct browser manifest/segment access separately from backend setup;
   compare actual egress/session requirements before proposing any media broker.
3. Finish fresh browser-local Twitch consent/validation/playback and record live/
   replay capabilities, advertisement behavior and limitations independently.
4. Before retaining accounts or routes, discuss a concrete deployment/trust model:
   private single-user self-hosting versus a hosted service, backend ownership,
   protected storage, account isolation and full route responsibility. Shipping a
   server on every viewer's computer is a local companion; running a development
   server here does not select that distribution.
5. Choose supported browser/OS scope from real trials. Linux development, generic
   automation and an installed PWA do not establish Windows/macOS/Safari/mobile
   provider or background behavior.

No limited System-network product stage, hidden route settings, extra connection
steps or missing controls are approved by this assessment. Preserve the common
UX; discuss any concrete visible consequence separately before introducing it.
Do not add a mandatory Tachiai cloud account or public credential/media broker as
an implicit solution. [#363](https://github.com/altendky/tachiai/issues/363) retains
the overall web scope; [#365](https://github.com/altendky/tachiai/issues/365) and
[#366](https://github.com/altendky/tachiai/issues/366) retain provider acceptance.
