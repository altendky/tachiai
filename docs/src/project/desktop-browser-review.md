# Desktop browser interface and library availability review

Reviewed 2026-10-17 against Android commit
`97ab8c14fe82524a69601b34056046688f7bf53a`.
Scope: the whole Tachiai application in a desktop browser on Windows, macOS and
Linux, including settings, provider accounts, catalogs, routes and two-feed
playback. This is a source/documentation review, not a browser implementation or
new playback experiment. No extension, companion, browser profile or provider
session was installed, modified or tested for this review.

The subsequent [shared Rust core](shared-rust-core.md) adds an initial timing
library and browser/Wasm interface. It does not establish desktop provider
playback, account handoff or browser-route coverage.

## Assessment

A complete browser interface is plausible. The picker, configured providers,
settings, layout, controls and persistent public configuration fit the web
platform. Supported embeds or app-controlled media can provide playback.

An ordinary website/PWA has significant gaps relative to the current Android
application: provider authentication handoff, ABEMA playback integration,
independent routes, protected local grants and controllable live timelines.
Installing a PWA does not grant extension or native networking privileges.

A browser front end with an extension and/or local companion is the strongest
candidate for fuller functionality, but **not a demonstrated solution**. A
companion can supply native transports; it does not automatically route browser
media or make ABEMA's Android licensing exchange work with a desktop CDM.

The immediate feasibility gates are actual ABEMA playback/control and account
handoff in the selected browser, followed by complete route coverage. Generic
interface work should not be mistaken for progress on those gates.

**Documented** below means an upstream interface exists; **Inferred** means it
appears suitable for Tachiai; **Unknown** means its important application fit is
unverified. A **Gap** is a requirement without an established equivalent.
Browser/library support and provider support are separate claims.

## Deployment forms

| Form | What can live there | Limits and fit |
| --- | --- | --- |
| HTTPS website | Entire interface, public configuration, browser networking, permitted media/embeds and supported OAuth | **Documented primitives.** Cross-origin, framing, cookie and socket restrictions remain. No direct equivalent of the current native transport stack. |
| Installed PWA | Website plus app-like launch/window and cacheable application assets | **Documented.** Installation does not elevate permissions. A cached shell does not provide offline provider playback. Install behavior varies by browser/OS. [PWA installation][pwa] |
| Browser app plus WebExtension | Host-permitted network requests, provider playback-page integration and browser proxy configuration | **Inferred architecture.** Requires explicit permissions and browser-specific implementation. Permissions alone do not establish provider framing, account or DRM compatibility. |
| Browser app plus local companion | Native tunnels, OS-backed credential storage and owned process lifetimes behind a small control interface | **Inferred architecture.** A separately installed program needs packaging and updates on each OS. Browser-to-transport attachment is still a feasibility gate. |
| Browser app, extension and companion together | Browser presentation, permissioned browser integration and native transport/storage | **Strongest parity candidate.** More deployment complexity; extension/helper loss, proxy ownership and recovery need deliberate handling. No reason to add all three before proving their need. |

A locally served browser interface is another hosting choice for the companion
case. It does not remove cross-origin restrictions on provider requests.
No project-operated service, media restreaming backend or mandatory cloud account
is selected by this review.

## Interface and shared application logic

| Current item or behavior | Browser equivalent | Fit and concerning gaps |
| --- | --- | --- |
| Compose screens, provider rows, tabs, drag handles, menus and dialogs | HTML/CSS/DOM with JavaScript/TypeScript and an optional UI framework | **Documented platform primitives.** The full settings/picker UI can be implemented here. Preserve accessibility, keyboard operation, stable layout and autosave behavior; no UI framework is selected. |
| Source/provider identities, capabilities, state transitions and planners | Rust/Wasm with JavaScript bindings, Kotlin/JS shared module, or equivalent web implementation | **Documented toolchains; inferred extraction.** Both can expose shared logic to a JavaScript/TypeScript front end. Platform dependencies and export interfaces need separation. Sharing the core does not require sharing the UI. [Rust/Wasm bindings][wasm-bindgen], [Kotlin web targets][kotlin] |
| OkHttp metadata/catalog requests | Fetch API; extension-origin requests with host permissions where appropriate | **Partial equivalent.** Native endpoint access does not establish browser CORS access. Content-script requests and extension-origin requests have different permissions. [Fetch][fetch], [Extension networking][extension-network] |
| Configured streams, provider order and preferences | IndexedDB, with small non-secret preferences in browser storage | **Documented.** Storage is origin/profile-bound and may be cleared or evicted. Add schema/version checks and explicit public-configuration export/import if durability requires it. [IndexedDB][idb], [Storage retention][storage] |
| Profile-file imports and pasted public links | File input/drag-and-drop and normal text entry | **Documented.** Browser file access is user-mediated. Preserve bounded input validation; importing a route profile does not activate a tunnel. [File API][files] |
| Keep the screen awake only during video | Screen Wake Lock API | **Documented with feature/permission checks.** Release it outside playback. The system can reject or release it; settings must not acquire a wake lock. [Wake lock][wake] |
| Worker execution and foreground lifecycle | Web Workers, visibility events and app-owned cancellation/state | **Partial equivalent.** Workers do not grant native privileges or guarantee process lifetime. Hidden-tab throttling/discard and browser exit differ from Android Activity lifecycle. [Workers][workers], [Visibility][visibility] |

Browser storage is not an Android Keystore replacement. Web Crypto offers
cryptographic primitives, but same-origin script compromise can still invoke
operations with available keys. Do not describe browser encryption or a
non-extractable CryptoKey as proof of equivalent OS-protected grant storage.
[Web Crypto][crypto]

With migration cost explicitly not a deciding concern, the current recommendation
is to investigate a Rust common core with native bindings for Android/iOS and
Wasm bindings for the browser. Expose commands, events and state; keep browser
effects in web adapters and the UI in web-native code. This does not grant Wasm
native networking privileges or replace useful existing Go/native transports.
No shared core has been implemented or selected as a settled architecture by
this review. [UniFFI][uniffi], [wasm-bindgen][wasm-bindgen]

## Playback interfaces and libraries

Two hosting models must remain distinct: Tachiai-controlled media elements and
provider-owned frames/pages. A JavaScript player library can control its own
video; it does not acquire control of an unrelated provider iframe.

| Current Android mechanism | Available browser item | Fit and concerning gaps |
| --- | --- | --- |
| Native media hosts and stable pane swaps | Two HTML video elements, CSS layout and retained player instances | **Inferred equivalent for app-controlled media.** Avoid recreating players when swapping visual roles. Actual dual decode, sustained output, memory/power and duplicate-provider-session behavior need testing. |
| Play/pause, gain/mute, time, buffering, seeks and rate | HTMLMediaElement APIs and events | **Documented.** Map actual capabilities and asynchronous outcomes; a dispatched seek is not settled playback. These APIs are not freely accessible inside cross-origin provider frames. [HTML media][media] |
| Media3 HLS | hls.js; Shaka Player; native HLS where supported | **Documented candidates.** Codec/container support, CORS, provider session and DRM compatibility remain separate. hls.js light builds omit EME and alternate-audio features; its documented Clear Key path is incomplete. [hls.js][hls], [Shaka][shaka] |
| Media3 DASH | dash.js or Shaka Player using Media Source Extensions | **Documented player libraries.** Their manifest/segment handling is plausible; Android data-source and DRM callbacks are not reusable unchanged. [dash.js setup][dash], [Shaka][shaka] |
| Fresh native CDM and opaque initial ABEMA exchange | Encrypted Media Extensions with the browser's available CDM; player license-request/response hooks | **Critical unknown.** EME is a standard interface, not an interchangeable Android MediaDrm implementation. Verify key system, initialization, encryption/codec support, response contract, origin/session binding and output restrictions. [EME][eme], [dash.js DRM][dash-drm] |
| Video/audio selection and audio-first adaptation policy | Library rendition/track selection APIs and reported media metrics | **Partial equivalent.** Reimplement the policy against each host's real capabilities. A provider embed may expose fewer controls; advertised quality and consumed formats are distinct. |
| Joint controls, replay alignment, live window and back-to-live | Media-element time/ranges plus player live APIs, or provider SDK controls | **Partial equivalent.** There is no guaranteed common event clock. Sliding windows, ads, latency corrections and rebuffering can invalidate alignment; platform readback must drive capability decisions. |
| Two-feed mix/fade | Per-element volume/mute or provider SDK gain controls | **Inferred equivalent when both hosts expose gain.** It does not require extracting audio. Web Audio processing has additional CORS/protected-media restrictions and must not be assumed available. [Web Audio security][web-audio], [Protected media restrictions][eme-restrictions] |

These libraries are alternatives to investigate, not selected dependencies or
proof of equivalent playback. Prefer unchanged provider-supported players when
they satisfy the product. For an app-owned player, preserve entitlement, required
advertising and opaque licensing boundaries. Do not substitute extracted keys,
software key maps or copied helper algorithms because a library supports them.

Manifest, segment and license fetches can have different CORS, header, redirect
and retry requirements. Shaka documents these networking constraints; adding a
player library does not remove them. [Player networking][shaka-network]

### Provider-specific consequences

**ABEMA:** a first-party desktop website is available, but a public interactive
embed or third-party account-handoff interface was not established in this
review. Framing, control, remembered login and renewal are **Unknown** for a
Tachiai origin. A working ABEMA tab does not prove that the same content works
inside a frame or through an app-owned EME player.

The Android no-page path also relies on verified runtime bundles, fresh setup
and Android-specific CDM/session integration. Desktop JavaScript availability
does not establish unchanged helper compatibility or permit assuming that the
Android opaque response can be passed to a different CDM.

**Twitch:** its official interactive embed provides gain/mute, play/pause,
quality selection and replay time/seeking. Live seek, current time and duration
are explicitly unavailable through those documented controls, so this path
does not establish parity with our native live timeline. Minimum 400×300 player
dimensions and parent/origin, visibility and autoplay requirements affect the
floating pane. Clips have no interactive JavaScript player interface.
[Twitch embed][twitch]

Our current Twitch native account/private playback path is a different adapter.
Moving it to web requests needs separate authentication, CORS and session
verification; choosing the official embed does not port that implementation.

## Browser and operating-system targets

ABEMA lists Windows Chrome/Edge and macOS Chrome/Safari. Linux, Firefox and Brave
are not listed as supported desktop configurations. This does not prove that
they fail; it prevents treating them as provider-supported targets without
additional evidence. [ABEMA supported devices][abema]

| Candidate target | Useful starting evidence | Remaining concern |
| --- | --- | --- |
| Windows Chrome or Edge | Listed ABEMA desktop browser configurations | Test the exact browser, CDM, content and integration form; Chrome and Edge are not identical DRM environments. |
| macOS Chrome or Safari | Listed ABEMA desktop browser configurations | Safari's native HLS/FairPlay path and extension APIs differ from Chromium. One working browser does not validate the other. |
| Linux Chrome | Relevant player libraries support Linux/browser playback | ABEMA support and the actual installed codec/CDM combination remain unverified. Source-built Chromium is not interchangeable with official Chrome's bundled DRM components. |
| Firefox | Useful player/extension interfaces, including richer proxy request context | ABEMA does not list it; provider compatibility must precede selecting it for routing convenience. |
| Brave or other Chromium-derived browsers | Similar engine lineage | Provider support, privacy/shield settings, cookies and installed CDM must be verified independently; Chrome results are insufficient. |

Shaka's browser/key-system matrix is useful for library feasibility, not a
provider support matrix. Probe the actual key-system/codec/encryption/robustness
configuration and then play the intended source. HDCP/display-chain and
content-specific restrictions can differ from an ordinary free sample.
[Shaka support][shaka], [ABEMA content/display limitations][abema-content]

The older Android experiments using a desktop Chrome identity remain Android
WebView observations. They are not desktop Chrome, desktop DRM or Linux evidence.

## Authentication, origins and account isolation

| Android capability | Browser availability | Fit and concerning gaps |
| --- | --- | --- |
| Provider-controlled sign-in and remembered site state | Provider tab/popup or permitted frame; browser-managed cookies | **Documented primitives.** Third-party-cookie, SameSite and storage-partitioning behavior can differ from first-party navigation. Signing in on a provider origin does not give Tachiai access to its session. [Third-party cookies][cookies] |
| ABEMA account grant handoff | Supported provider callback, or a carefully bounded extension/native observation if established | **Gap.** Android observes a trusted account-page subresource request's method/URL/headers before native validation. An ordinary Tachiai page cannot observe another origin's HTTPS requests that way. Extension permissions may offer relevant request observation, but correct fields, provenance and account binding remain unverified. |
| Automatic renewal without unnecessary clicks | Reuse of a verified browser/provider grant lifecycle | **Unknown.** Retained cookies do not establish the native handoff, provider renewal behavior or entitlement. Reopening a signed-in page is not a completed account validation. |
| Twitch authorization and catalog access | Documented OAuth/device flows and validated tokens; browser or permissioned extension requests | **Documented protocol options.** Confirm the exact supported client/scopes and requested operation. Current Smart TV/native playback identity is not automatically an appropriate website client. [Twitch authentication][twitch-auth] |
| Multiple configured provider/account instances | Distinct app metadata; browser profiles or supported container mechanisms for site state | **Gap in equivalent isolation.** Two iframes do not automatically create independent cookie stores. Instance-bound grants, profile selection, login and route ownership need a tested design. |
| Application controls for provider frames | Provider SDK, origin-checked postMessage, or permissioned playback-page extension adapter | **Partial equivalent.** Same-origin policy limits direct DOM access; frame policies can prevent embedding. postMessage requires a cooperating receiver and strict source/origin/schema checks. [Origins][origins], [Frame policies][frames], [postMessage][messages] |

CORS permission, framing permission, cookie access, player control and provider
entitlement are different boundaries. Solving one does not solve the others.
`fetch` with `no-cors` yields an unreadable opaque response; it is not a manifest
or license-response access solution. [Fetch restrictions][fetch]

Provider credentials must stay on provider-controlled pages. This review does
not propose login-page script injection, generic native bridges exposed to
provider content, cookie transfer from Android, or disabling browser security.

## Routes, extensions and a native companion

| Requirement | Available mechanism | Fit and concerning gaps |
| --- | --- | --- |
| Ordinary website selects HTTP/SOCKS proxy or a native tunnel for each feed | No equivalent established in ordinary web APIs | **Gap.** Fetch does not expose the current native connection factory. Wasm/service workers do not grant arbitrary native TCP/UDP or browser-proxy privileges. |
| Chromium-family browser proxy configuration | Chrome proxy extension API with fixed rules or PAC | **Documented.** Configuration is regular/incognito scope, not a per-tab player factory. PAC host-based separation may distinguish providers' host families but cannot select two account/session routes to the same destination by instance identity alone. [Chrome proxy][chrome-proxy], [Chromium proxy behavior][chromium-proxy] |
| More contextual browser request routing | Firefox `proxy.onRequest` | **Documented candidate.** Request details include tab/frame/container context; some requests have no tab attribution. Verify every traffic class and provider support before relying on it. [Firefox proxy callback][firefox-proxy], [Request context][firefox-request] |
| Native WireGuard/OpenVPN/OpenConnect plus authenticated local CONNECT | Ported existing routebridge in a separately installed companion | **Inferred reuse.** It can own transports; proxy configuration or another verified mechanism must attach browser requests. Installing a helper or opening a native messaging port does not route video/license traffic. |
| Companion control and bounded status | Extension native messaging, or a separately secured local web endpoint | **Documented messaging; unverified application protocol.** Native messaging registers an executable for allowed extension origins; ordinary pages cannot directly call it. It is a control channel, not a media-routing mechanism. [Native messaging][native] |
| Ordered startup fallback, without direct escape on failure | Application route state plus verified browser proxy behavior | **Gap in semantic parity.** Browser proxy-list failover is not our account/entitlement-aware startup policy. PAC errors, proxy lists, ownership conflicts, extension removal and helper crashes must all be tested. |

The current companion candidate forwards end-to-end TLS using CONNECT. Such a
bridge cannot add CORS headers inside encrypted origin responses. A separate
native fetch broker would introduce a different trust/session boundary and is
not selected as an implicit fix.

Chromium's proxy protocol details also matter: its SOCKS5 client does not support
SOCKS5 authentication. That is distinct from using the existing authenticated
HTTP CONNECT listener with a native upstream SOCKS5 backend. Do not substitute
proxy types without checking credentials, DNS and failure behavior.
[Chromium proxy behavior][chromium-proxy]

Full route proof must cover account checks, runtime bundles, DNS, manifests,
audio/video/subtitle segments, redirects, licensing and renewal. An exit-IP or
region-check success proves only its own request. It must also show that
unrelated browser traffic is not unintentionally captured by a global setting.
Different routes for duplicate ABEMA instances remain unimplemented on Android
and must not be claimed as existing desktop functionality.

### Reuse of the native transport code

The bounded Go CONNECT server, upstream proxy clients, WireGuard userspace
logic and cleanup/validation rules are promising reuse candidates. The entire
current package is not portable unchanged:

- Packet bridging imports Unix-only APIs and uses Unix datagram sockets/FDs.
- OpenVPN/OpenConnect adapters use POSIX socketpair, fcntl and Unix headers;
  Windows needs an actual portability layer.
- Non-Android C++ link flags and native dependency paths need a macOS-specific
  build; Android AAR/ABI outputs are not desktop packages.
- Linux host fixtures are useful existing evidence, but do not validate browser
  attachment, desktop distribution or commercial-provider operation.
- Pinned source, notices and OpenConnect's replacement/relink materials need
  matching desktop packaging. Upstream portability does not settle the whole
  patched dependency closure.

## Security, persistence and development workflow

| Requirement | Available item | Fit and concerning gaps |
| --- | --- | --- |
| Protected provider grants and private route material | Companion using macOS Keychain, Windows DPAPI or Linux Secret Service | **Documented OS building blocks.** Locked/unavailable credential stores, account/instance binding and permissions need tests. Extension storage and website IndexedDB are not automatically equivalent. [Keychain][keychain], [DPAPI][dpapi], [Secret Service][secret-service] |
| Local-only inspect build, safe normal diagnostics | Browser DevTools plus local development builds; bounded application failure records | **Policy difference.** An ordinary web app cannot promise Android-style screenshot/DevTools prevention. CDM/OS output protection is separate. Do not automatically retain HARs, tokens, cookies, signed URLs or raw licensing data. |
| Browser/helper trust | Narrow authenticated, versioned command protocol and explicit origin/extension admission | **Implementation requirement.** Never expose arbitrary URL fetch, shell execution or provider-native bridges. A web-accessible loopback endpoint must address authentication, origin/Host checks, request forgery and local-network access rules. [Local network access][local-access] |
| Owned process recovery and cancellation | Page/worker state, extension worker lifecycle, native process/port management | **Partial equivalent.** Browser exit, worker suspension and helper loss need cancellation and stale-callback guards. Do not infer successful cleanup from a timeout or lost port. [Extension lifecycle][worker-life] |
| Automated interface and contract tests | Browser unit tests and Playwright against suitable browser channels | **Documented tools.** Use controlled fixtures for routine CI; generic Chromium/WebKit automation is not evidence for the shipping browser's provider/CDM support. Playwright documents browser/OS codec differences. [Playwright browsers][playwright] |
| Cross-platform build and deployment | Web artifacts plus optional extension/native packages | **Inferred workflow.** UI development is possible on this Linux host. Real Windows browsers and macOS Safari need their own test environments; native libraries, installers, signing and updates remain OS-specific. |

Extension permissions and updates become part of the trust model. Keep the
requested host/operation set bounded and make missing/revoked permission a
visible unavailable state. Browser proxy settings may affect an existing user's
whole browsing profile; a test must not silently overwrite unrelated settings.

Hosted UI talking to a loopback companion is not universally permission-free.
Modern browser local-network and mixed-content/CORS rules must be checked for
the chosen origin/protocol. An extension/native-messaging design avoids some
web-to-local endpoint issues while adding installation and extension-lifecycle
requirements; neither is chosen here.

## Gap register and cheapest useful validation

| Priority | Unresolved requirement | Smallest useful next check | Evidence required to proceed |
| --- | --- | --- | --- |
| Gate | ABEMA desktop playback integration | Start with the intended live and replay sources in a listed desktop browser, then test the proposed Tachiai host/player form | Intended source plays with valid entitlement, unchanged opaque DRM and necessary controls; no inference from an Android desktop identity |
| Gate | Browser account handoff and renewal | Establish a provider-supported callback or bounded trusted handoff for the proposed deployment | Correct account validation, restart persistence, expiry/revocation and silent renewal; not just an account page that looks signed in |
| Gate | Full selected-route coverage | Trace controlled HTTPS and HLS requests through two owned routes before provider traffic | All request classes use the intended exit; no direct fallback, unrelated-traffic takeover or credential crossover |
| High | Live alignment/control parity | Compare the official embed and app-owned player capability sets | Explicit replay/live capabilities and safe unavailable states; no invented live seek or assumed back-to-live behavior |
| High | Two actual provider streams, gain and layout | Run ABEMA + Twitch, then duplicate selections, in the intended composition | Both videos and audible tracks, stable swaps, volume/mute, ads/rebuffering recovery and sustained resource behavior |
| High | Profile/account/route isolation | Test two same-provider accounts/instances and overlapping destination hosts | Correct grants, browser state and routes remain distinct, or unsupported combinations are clearly declared |
| High | Failure ownership | Kill/restart helper, extension and page; revoke permission and change network | Cleanup/admission decisions remain conservative; no stale grant publication or accidental direct connection |
| High | Native portability and secret storage | Build/test each transport independently on target OSes | Supported profile subset, normal TLS, cancellation, OS storage failure behavior and matching license/source companion |
| Medium | Browser and distribution matrix | Validate named browser/OS/CDM versions and installed forms | Recorded source/account/region/content/output conditions; passing controlled CI is separate from real provider acceptance |

This supports a browser-first feasibility spike, not a commitment to a website
with complete Android parity. Prefer the smallest deployment form that meets
verified requirements. If a local companion or extension is required for a
feature, describe that requirement explicitly rather than calling it a
zero-install website.

## Repository evidence

- [Current Android dependencies](https://github.com/altendky/tachiai/blob/97ab8c14fe82524a69601b34056046688f7bf53a/apps/android/app/build.gradle.kts)
- [Native HLS host](https://github.com/altendky/tachiai/blob/97ab8c14fe82524a69601b34056046688f7bf53a/apps/android/app/src/main/kotlin/net/fstab/tachiai/platform/media/BoundedNativePlayer.kt)
- [Native DASH/CDM host](https://github.com/altendky/tachiai/blob/97ab8c14fe82524a69601b34056046688f7bf53a/apps/android/app/src/main/kotlin/net/fstab/tachiai/platform/media/BoundedNativeDashPlayer.kt)
- [Account browser handoff](https://github.com/altendky/tachiai/blob/97ab8c14fe82524a69601b34056046688f7bf53a/apps/android/app/src/main/kotlin/net/fstab/tachiai/feature/connections/AbemaApprovalBrowserActivity.kt#L355-L391)
- [Routebridge Unix packet boundary](https://github.com/altendky/tachiai/blob/97ab8c14fe82524a69601b34056046688f7bf53a/apps/android/routebridge/packetbridge.go)
- [Native dependency and license inventory](https://github.com/altendky/tachiai/blob/97ab8c14fe82524a69601b34056046688f7bf53a/apps/android/routebridge/THIRD-PARTY-NOTICES.md)

Related documents: [iOS review](ios-interface-review.md),
[architecture](architecture.md), [provider integrations](provider-integrations.md),
[native access evidence](native-access-experiments.md),
[network routing](source-network-routing.md),
[timing capabilities](timing-capability-matrix.md) and
[security boundaries](security-and-privacy.md).

[pwa]: https://developer.mozilla.org/en-US/docs/Web/Progressive_web_apps/Guides/Installing
[kotlin]: https://kotlinlang.org/docs/web-overview.html
[uniffi]: https://mozilla.github.io/uniffi-rs/latest/
[wasm-bindgen]: https://rustwasm.github.io/docs/wasm-bindgen/
[fetch]: https://developer.mozilla.org/en-US/docs/Web/API/Fetch_API/Using_Fetch
[extension-network]: https://developer.chrome.com/docs/extensions/develop/concepts/network-requests
[idb]: https://developer.mozilla.org/en-US/docs/Web/API/IndexedDB_API
[storage]: https://developer.mozilla.org/en-US/docs/Web/API/Storage_API/Storage_quotas_and_eviction_criteria
[files]: https://developer.mozilla.org/en-US/docs/Web/API/File_API
[wake]: https://developer.mozilla.org/en-US/docs/Web/API/Screen_Wake_Lock_API
[workers]: https://developer.mozilla.org/en-US/docs/Web/API/Web_Workers_API
[visibility]: https://developer.mozilla.org/en-US/docs/Web/API/Page_Visibility_API
[crypto]: https://developer.mozilla.org/en-US/docs/Web/API/Web_Crypto_API
[media]: https://html.spec.whatwg.org/multipage/media.html
[hls]: https://github.com/video-dev/hls.js
[shaka]: https://github.com/shaka-project/shaka-player
[dash]: https://dashif.org/dash.js/pages/quickstart/setup.html
[eme]: https://developer.mozilla.org/en-US/docs/Web/API/Encrypted_Media_Extensions_API
[dash-drm]: https://dashif.org/dash.js/pages/usage/drm.html
[web-audio]: https://www.w3.org/TR/webaudio-1.0/#MediaElementAudioSourceNode-security
[eme-restrictions]: https://www.w3.org/TR/encrypted-media/#media-element-restrictions
[shaka-network]: https://shaka-project.github.io/shaka-player/docs/api/tutorial-network-and-buffering-config.html
[twitch]: https://dev.twitch.tv/docs/embed/video-and-clips/
[abema]: https://help.abema.tv/hc/ja/articles/360013743211-ABEMAを利用できるデバイス
[abema-content]: https://help.abema.tv/hc/ja/articles/360015494032-視聴できないコンテンツがあります
[cookies]: https://developer.mozilla.org/en-US/docs/Web/Privacy/Guides/Third-party_cookies
[twitch-auth]: https://dev.twitch.tv/docs/authentication/getting-tokens-oauth/
[origins]: https://developer.mozilla.org/en-US/docs/Web/Security/Defenses/Same-origin_policy
[frames]: https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Content-Security-Policy/frame-ancestors
[messages]: https://developer.mozilla.org/en-US/docs/Web/API/Window/postMessage
[chrome-proxy]: https://developer.chrome.com/docs/extensions/reference/api/proxy
[chromium-proxy]: https://chromium.googlesource.com/chromium/src/+/HEAD/net/docs/proxy.md
[firefox-proxy]: https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/API/proxy/onRequest
[firefox-request]: https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/API/proxy/RequestDetails
[native]: https://developer.chrome.com/docs/extensions/develop/concepts/native-messaging
[keychain]: https://developer.apple.com/documentation/security/keychain-services
[dpapi]: https://learn.microsoft.com/en-us/windows/win32/api/dpapi/nf-dpapi-cryptprotectdata
[secret-service]: https://specifications.freedesktop.org/secret-service/latest/
[local-access]: https://developer.chrome.com/blog/local-network-access
[worker-life]: https://developer.chrome.com/docs/extensions/develop/concepts/service-workers/lifecycle
[playwright]: https://playwright.dev/docs/browsers
