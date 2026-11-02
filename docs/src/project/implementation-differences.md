# Implementation difference ledger

This ledger records implemented system integration differences that support the
common UX. It does not approve user-visible exceptions; those belong in the
[UX variation register](ux-variations.md). A mechanism's visible product
consequences still need the explicit approval required by
[AGENTS.md](../../../AGENTS.md#common-ux-across-implementations).

## Android phone and TV

The initial October 18 audit uses
`d60ce4a3485305ae4681d8dca6b33cd8e48bb449`. Both targets use the same Android
application, Kotlin provider/player/storage code and Rust timing core.

| Area | Implemented difference | System purpose and boundary | Evidence |
| --- | --- | --- | --- |
| Foreground media integration | TV creates a foreground pair media session and sets the Activity's media controller; phone does not create that TV-specific session. Both use the existing guarded pair play/pause operations. | Connect Android media-key delivery to the foreground TV viewer, with no second audio-focus owner or new background playback service. This records wiring, not approval of remote button UX. | [Activity][media-session] |
| Launcher integration | The same APK declares ordinary Android and Leanback launcher categories, with Leanback and touchscreen support marked optional. | Make the app discoverable through the platform's applicable launcher without requiring a touchscreen. This records manifest integration, not approval of different in-app navigation or visual design. | [Manifest][manifest] |
| Device-mode detection | TV adaptations use Android's Configuration UI mode; ordinary phone mode uses the shared default path. | Select the applicable system integration inside one application. Detection is not authorization for every UX branch it selects. | [Configuration detection][detection] |

## Other implementations

No iOS or tvOS application implementation differences are established here.
Library choices, feasibility gaps and proposed adapters remain in the
[platform research](platforms.md) until implemented. Do not turn research
assumptions into implemented behavior or approved UX exceptions.

### Browser diagnostic infrastructure

The [web harness and controlled pair](web-client.md) are implemented diagnostic
infrastructure, rather than a complete web product. Each browser feed owns an
HTML video element and Shaka instance; Android owns a Media3 player. Browser
timing calls the generated Rust/Wasm facade, while Android uses the generated
native bindings. Both retain the same Rust seek rules, with media observation,
dispatch and settlement owned by their platform hosts.

These are factual playback mechanisms. Controlled-media tests establish neither
provider playback nor equivalent product coverage. The separate Twitch diagnostic
and ABEMA preparation/manifest experiments remain bounded investigations; their
account, route, DRM and lifecycle findings do not approve a web UX exception.

The separate Twitch browser diagnostic retains the native comparison's selected
OAuth client and empty GraphQL client header, while explicitly declaring the
verified `mediaplayer` backend and validating structured live source permission
before owned media creation. Native's legacy signature/value comparison is not
treated as that browser permission guard. Replay retains its existing field
selection. This is a bounded diagnostic contract difference with closed output,
not provider playback acceptance or a product UX change. See the
[source contract and limits](web-twitch-proof.md#verified-browser-backend-and-live-permission).
The separate [two-player diagnostic](web-twitch-proof.md#two-player-diagnostic)
shares one temporary account authorization across two fresh serialized source
preparations and independently owned Shaka/video sessions. Its realm-wide
transport binds each exact request object to one feed before asynchronous
dispatch, rather than replacing one global handler per player. Common gain and
geometry functions are reused; fixture seek/quality permissions are not inherited.
This is diagnostic wiring, not a complete connected-account viewer or UX exception.
Its independent A/B selections now pin either existing live or replay case before
authorization. Only a replay-owned request can admit the already known exact
replay CDN, enforced by the source guard, request filter and routed transport.
The page CSP names that one host without granting a live partner access. Owned
dual-decoder tests cover all four assignments; actual replay remains unverified.
Its optional browser-failure diagnostic also uses bounded ephemeral CDP network
IDs to correlate one observed numeric HTTP status with its first failed browser
network operation. Duplicate/ambiguous IDs or overflow suppress the status; IDs
are cleared on freeze and never output. This remains explicitly unattributed to
the selected source/manifest, does not change Fetch/security policy and adds no
product UX behavior.

The Twitch diagnostic can also pin an explicit unversioned live-manifest
comparison before authorization; its default remains the native comparison's v2
path. The alternative is limited to the one named live diagnostic case, changes
only that path and retains source/account binding, request/security policy and
cleanup. This is an explicit counterfactual with closed endpoint labels, not a
product endpoint selection, fallback or UX exception. Its
[scope and evidence](web-twitch-proof.md#explicit-unversioned-live-comparison)
remain separate from provider playback acceptance.

An explicitly selected Twitch native HTML diagnostic uses an owned browser
video element instead of Shaka/MSE. Its separate page pins the existing single
live case and either the default v2 or explicit unversioned endpoint before
authorization, validates the same account/source/permission
contract and bounds native descendants with CSP. Normal native cookie/redirect
semantics differ from the default MSE Fetch omit/redirect-error boundary; no
per-request Fetch enforcement or manual track-control parity is claimed.
Native timeline classification remains unknown and provider seek unsupported.
This is a bounded experiment, not an extension/companion choice or product UX
exception. See its [scope and evidence](web-twitch-proof.md#explicit-native-html-video-diagnostic).

The separate [ABEMA decoder diagnostic](web-abema-playback.md) now implements a
bounded fresh GLOBAL metadata/bootstrap entry and an owned Shaka DASH transport
whose exact SDK references admit init/media bytes. Its page disables encrypted
media; Android's verified native initial licensing path is not ported. Controlled
fixture decoding verifies this mechanism locally. Validated inline direct UTC
timing is parsed without a timing request; a fresh anonymous News source decoded
for about five seconds with confirmed cleanup. Protected sources, Sumo/replay,
sustained operation and acoustic output remain unverified. This adds no product
controls or platform UX exception.

The [local ABEMA route diagnostic](web-abema-routing.md) reuses the existing Go
userspace WireGuard transport in a bounded host command. A private immutable
loopback proxy descriptor reaches the exact replay's backend workers and owned
browser MPD request. Proxy-only CDP authentication and unchanged request
continuation preserve the normal browser CORS/TLS checks; Python authenticates
only CONNECT and ignores environment bypass settings. This is development
infrastructure with no product Routes/account or UX change. Owned fixtures verify
the mechanism; one fresh routed replay metadata trial returned a browser-readable
MPD200 with independent route/attempt cleanup. Actual Japan egress, full route
coverage and protected playback remain unproved.

The separate one-initial-exchange EME diagnostic uses a fresh browser temporary
ClearKey session rather than Android FrameworkMediaDrm. Its explicit standard
common-PSSH request feeds the original CDM message to the exact bound license
URI; the unchanged provider response helper and dash ClearKey serializer return
opaque bytes directly to that same browser session. This is a bounded diagnostic
contract, not automatic provider-player parity or a native-normalizer port. It
adds no media player, product setting, stored account, renewal or UX variation.
One fresh routed Chrome trial accepted the unchanged helper/serializer output
in the same temporary browser session and confirmed its closure. This is initial
exchange acceptance, without segment decoding or protected-playback acceptance.

The distinct [protected replay decoder diagnostic](web-abema-routing.md#separate-protected-replay-decoder-diagnostic)
adds muted Shaka 5.3.3 MSE decoding to a new fresh CUSTOM/CSAI source and one
initial temporary browser exchange. Its public stream model receives audited
standard CENC identifiers before SDK filtering; exact initialization/media
references retain the existing request admission. Standard EME method ownership
tracks the actual session/update/closure independently of SDK destruction.
Owned encrypted fixtures verify bounded decoding and refusal/retirement paths;
one fresh routed Chrome 156 trial also decoded 36 protected replay frames at
854 × 480 with 5,739 ms advancement and confirmed native/session/route cleanup.
These establish no acoustic, entitlement, ad-equivalence or sustained acceptance.
The no-license decoder and earlier EME command retain their separate limits.
This adds no product controls or platform UX exception.

<https://github.com/altendky/tachiai/blob/7c10e906ede38a0bfa88cb97b0f895a8c0c38148/apps/web/src/player.ts>

<https://github.com/altendky/tachiai/blob/7c10e906ede38a0bfa88cb97b0f895a8c0c38148/apps/web/src/pair.ts>

### Controlled encrypted/clear browser pair

The separate [owned encrypted/clear pair](web-pair-fixture.md#owned-encrypted-dash-and-clear-hls)
reuses the common viewer through an injectable player-session interface. Its
encrypted DASH SDK/media/CDM owner stays in a same-origin child document; the
parent retains the iframe surface and copies bounded observations. Clear HLS
keeps the existing parent video owner. Android keeps native Media3/DRM ownership.
Both browser adapters share the Rust seek planner and browser seek-settlement
rules. Direct synchronous child Play preserves the user-action call; confirmed
CDM closure and MediaKeys detachment precede iframe removal. Uncertain cleanup
blocks replacement. This is owned-only diagnostic wiring, with no provider route,
account, ad or UX exception. The original bounded protected decoder is unchanged.

Separate [mixed transport fixtures](web-abema-routing.md#separate-mixed-browser-transport-fixtures)
implement destination-based browser proxy selection: ABEMA retains the existing
authenticated route while the pinned Twitch case explicitly bypasses it for
its existing HTTPS/443 catalogue. This has no direct fallback or host-route edit.
A single development-only CDP owner can combine exact proxy authentication with
the existing scoped Headerly media Origin/ACAO rewrite, without reading media
bodies or altering status, cookies or TLS. Owned Chrome fixtures verify these
mechanisms; no actual mixed provider session or shipping architecture is selected.
Android retains its native per-provider routing and is unaffected. These helpers
add no product controls or UX exception.

### Controlled browser fixture formats

The provider-free browser fixture now reports bounded audio/video format
observations in its existing Playback status. Android uses Media3 input-format
callbacks; the browser adapter enables Shaka 5.3.2's quality observer and projects
`mediaqualitychanged` metadata associated with buffered content at its polled
playhead. The browser explicitly identifies this `last-sdk-buffered-playhead` basis,
keeps it separate from requested/selected tracks and omits unsupported or unknown
numeric properties. Neither the event nor selected tracks establish decoder or
acoustic output. This mechanism adds no Quality controls, saved preferences,
provider requests or approved UX exception. Its
[fixture scope and limits](web-pair-fixture.md#buffered-format-observations)
remain distinct from product/provider acceptance.

### Controlled browser quality preferences

The controlled pair now uses the common More → Quality labels, defaults and
override/save/reset behavior. Its browser policy delegates to Shaka's configured
ABR manager after SDK content/support filtering, retaining exact comparable audio
while video adapts. Android uses Media3 track-selection overrides and bandwidth
meters. Both distinguish numeric requests from observed formats; browser format
events retain the buffered-playhead qualification above.

Browser fixture defaults use a separate bounded/versioned IndexedDB record with
transactional per-kind writes and cross-tab/focus refresh. Android configured
streams use its encrypted configured-source store. The browser record accepts
only public fixture identity and numeric preferences; it stores no grants or
provider metadata and establishes no account-storage or hosting decision.
These mechanisms preserve the common scopes without adding a UX exception.

### Controlled browser Fit and control dock

The browser fixture follows the existing native Fit toggle, stage reservations,
floating avoidance and responsive control-dock formulas using CSS logical pixels
and measured DOM toolbar/content heights. Android uses its View measurement in
dp; the implemented Fit/reservation rules keep the same panel threshold,
minimum targets, capped scroll body and normalized floating position. Android
also measures compact status/live rows; this bounded fixture retains its
existing external status output. The browser's owned diagnostic header stays
outside its measured usable viewer. This adds no fullscreen/orientation-lock mechanism,
provider behavior or platform-specific UX exception.

### Controlled browser session history

The fixture projects the common Back layers into owned browser history entries;
Android dispatches Back inside its Activity/view hierarchy. A tab-scoped registry
contains only nonsecret navigation descriptors. Active player ownership remains
in memory, and a history entry cannot start playback. Retirement invalidates that
ownership synchronously before cleanup; pagehide does not initiate a traversal,
and restored pages keep the cleanup barrier. The separate
[approved web retirement policy](ux-variations.md#approved-exceptions) defines
Forward/reload/BFCache behavior. These mechanisms do not change provider account,
route, media or licensing behavior.

The implementation treats [history traversal](https://html.spec.whatwg.org/multipage/nav-history-apis.html#the-history-interface)
as asynchronous and keeps cleanup admission across the
[browser's frozen-page lifecycle](https://web.dev/articles/bfcache).

[media-session]: https://github.com/altendky/tachiai/blob/d60ce4a3485305ae4681d8dca6b33cd8e48bb449/apps/android/app/src/main/kotlin/net/fstab/tachiai/feature/presentation/PrototypeActivity.kt#L888-L900
[manifest]: https://github.com/altendky/tachiai/blob/d60ce4a3485305ae4681d8dca6b33cd8e48bb449/apps/android/app/src/main/AndroidManifest.xml#L9-L33
[detection]: https://github.com/altendky/tachiai/blob/d60ce4a3485305ae4681d8dca6b33cd8e48bb449/apps/android/app/src/main/kotlin/net/fstab/tachiai/feature/presentation/TelevisionPresentation.kt#L23-L26
