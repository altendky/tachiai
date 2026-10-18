# iOS interface and library availability review

Reviewed 2026-10-17 against Android commit
`97ab8c14fe82524a69601b34056046688f7bf53a`.
Scope: iPhone and iPad equivalents for the current primary application,
including configured providers, accounts, routes and the native two-feed viewer.
This is a source and documentation review. No iOS application was built, and no
iOS device, Simulator, account or playback experiment was performed.

Platform-landscape update, 2026-10-17: the merged
[shared Rust core](shared-rust-core.md) adds an initial timing library used by
Android and a generated Swift interface. No Apple application, framework slices
or Apple-device integration have been built or verified. The original Android
baseline above remains the historical inventory for this review; the changes
below update sharing and distribution claims without asserting Apple support.
See the [tvOS comparison](tvos-interface-review.md) for the related Apple TV
target and the combined Apple distribution assessment.

## Assessment

iOS provides credible equivalents for the application shell, HLS playback,
player controls, audio mixing, secure storage and browser profiles. A native
presentation shell is feasible in principle. The current Android application
cannot be treated as a directly portable implementation, however.

The largest unresolved areas are:

1. **ABEMA protected playback:** compatibility between its selected delivery,
   opaque helper exchange and Apple's media/key-session interfaces.
2. **ABEMA account handoff and automatic renewal:** the Android flow observes
   trusted HTTPS subresource requests, which is not an established WKWebView
   capability.
3. **Complete per-provider routing:** documented browser and API proxy settings
   do not establish that native media, license and redirect requests follow
   the same route.
4. **Runtime isolation and recovery:** Android's named processes, bound services
   and controlled player-process restart need an Apple-specific design.

These are feasibility gates for equivalent behavior. Choosing SwiftUI or a
cross-platform UI library does not resolve them.

Throughout this review, **Documented** means an upstream interface exists;
**Inferred** means the interface appears suitable for Tachiai's purpose;
**Unknown** means an important part of that fit is unverified. Documented API
availability does not mean tested provider compatibility. **Gap** identifies a
requirement for which an equivalent implementation has not been established.

## Application and supporting libraries

| Android item or requirement | Available iOS interface/library | Fit and concerning gaps |
| --- | --- | --- |
| Kotlin, Jetpack Compose and Material 3 | Swift/SwiftUI with UIKit interop; alternatively Kotlin Multiplatform and Compose Multiplatform | **Documented.** Native lists, navigation, menus, dialogs and adaptive layouts are available. Compose Multiplatform's iOS target is stable, but existing Android activities, resources and dependencies still need separation. [SwiftUI][swiftui], [Compose iOS][compose] |
| Presentation models, source identity, display names, capability and alignment planners | Existing Rust timing core with generated Swift interface; further Rust extraction or equivalent Swift models | **Implemented sharing is narrow:** the initial timing library exists. Most listed application rules remain Android code. Preserve platform-neutral rules and tests while separating effects; generated Swift does not establish Apple linkage. [Shared core](shared-rust-core.md), [KMP integration][kmp-integration] |
| OkHttp and explicit native HTTP clients | Foundation URLSession; Ktor Client with its Darwin engine for shared Kotlin | **Documented.** Ktor Darwin uses URLSession; OkHttp itself is listed for JVM/Android. Cancellation, redirects, bounded response parsing, TLS and route ownership need equivalent policies in the selected engine. [Ktor engines][ktor] |
| `org.json` and custom record codecs | Swift Codable/Foundation JSON; `kotlinx.serialization` for shared Kotlin | **Documented alternatives.** Existing parsing and record validation are not automatically portable. Keep size limits, schema versions and account/instance bindings. [Kotlin serialization][serialization] |
| ZXing for device-authorization QR generation | Core Image `CIQRCodeGenerator` | **Documented direct functional alternative.** Generating a QR image does not require adding a third-party scanner dependency. [Apple QR generator][qr] |
| File/Open/Share import of route profiles and public stream links | SwiftUI file importer, document types and an iOS Share extension | **Documented import building blocks.** File imports use security-scoped access; an Android share intent is not an iOS extension implementation. Preserve bounded reads and explicit import validation. [File importer][file-import], [Share extensions][share] |
| Preferences, configured streams and provider order | UserDefaults for non-secret preferences; app-container files for structured records | **Documented primitives.** Provider grants, private keys and proxy passwords belong in protected storage, not ordinary preferences. Autosave, stable identifiers and drag ordering are application rules. [UserDefaults][defaults] |
| Screen awake only while displaying video | `UIApplication.isIdleTimerDisabled` | **Documented.** Enable only for the viewer's required lifetime and restore it on exit; settings and provider pages should retain normal idle behavior. [Idle timer][idle] |

### Sharing strategy

A SwiftUI shell can use native Apple hosts alongside either a Kotlin
Multiplatform core or a Rust core with generated Swift/Kotlin bindings. UniFFI
documents support for both languages; framework packaging and platform builds
still need verification. [UniFFI][uniffi]

With migration cost explicitly not a deciding concern, the current recommendation
is to investigate Rust for shared models, validation, provider parsing, state
machines and planning. Keep playback, authentication, storage and lifecycle
effects behind platform adapters. The initial timing extraction is implemented;
expanding that scope remains a recommendation, and Apple integration remains
unverified. Shared behavior does not require shared UI.

The current Android dependency catalogue includes Activity/Compose, lifecycle,
AndroidX WebKit, Media3, OkHttp, ZXing and a generated routebridge AAR. An AAR,
JVM dependency or Android API cannot simply be linked into an iOS target.
KMP can generate an Apple framework, but selecting it would require a scoped
extraction rather than moving the entire current source tree unchanged.

## Playback and presentation

| Current behavior | Available iOS interface | Fit and concerning gaps |
| --- | --- | --- |
| Media3 HLS player for Twitch | AVFoundation `AVPlayer`, `AVPlayerItem`, `AVPlayerLayer` | **Documented native HLS primitives.** Actual Twitch access still depends on valid provider sessions and selected-source resolution. [AVPlayer][avplayer] |
| Independent primary/secondary hosts, duplicate selections, stable swaps | Two player instances and render layers composed by SwiftUI/UIKit | **Inferred.** View placement and swaps can preserve host identity. Concurrent decode capacity, protected sessions, heat, memory and power require a named physical-device test. |
| Per-feed mute and application volume/mix/fade | `AVPlayer.volume` and `isMuted` | **Documented.** These control gain relative to system volume. They are not a general device-volume setter; use the system volume UI if that is intended. [Player volume][volume] |
| One audio-focus owner for the presentation; pause both on interruption | Shared `AVAudioSession`, interruption and audio-route notifications | **Documented primitives.** Reproduce the group pause/resume policy. `mixWithOthers` concerns other apps, not merely the two players inside Tachiai. Native-player and WKWebView combinations still need an acoustic test. [Audio session][audio] |
| Replay seek, live window and back-to-live controls | Player time, `seekableTimeRanges`, buffered ranges and asynchronous seek completion | **Documented primitives; semantic mapping required.** Live-window movement, expired targets and readback must feed the existing capability model. A wall-clock timestamp may be absent or unsuitable. [Seekable ranges][seekable] |
| Joint hold/seek/check/resume and relative alignment | AVPlayer seek/preroll and `setRate(_:time:atHostTime:)` | **Partial equivalent.** Scheduling does not preload media; preroll/readiness and disabling automatic stall waiting are required considerations. Host-clock coordination does not identify the same real-world event in two independent streams. [Scheduled playback][scheduled] |
| Independent video/audio quality preferences and actual consumed formats | Preferred bitrate/resolution, media selection and player access/metrics logs | **Partial equivalent.** Adaptive preferences are not established equivalents of exact Media3 track overrides. Throughput, advertised bitrate and consumed format must not be conflated. [Bitrate preference][bitrate], [Resolution preference][resolution], [Observed bitrate][observed] |
| Android DASH/Clear Key player and opaque initial licensing response | `AVContentKeySession`, including `.clearKey` and `.fairPlayStreaming` | **Critical unknown.** Apple does expose a Clear Key identifier. Its existence does not establish Android MediaDrm JSON response, initialization, DASH/cenc packaging or ABEMA session compatibility. [Content keys][keys], [Clear Key][clearkey] |

### ABEMA delivery is a separate feasibility gate

The Android native path combines selected-source verification, fresh browser
runtime setup, verified public bundles, an unchanged opaque helper exchange
and a fresh Android CDM. It is not merely a URL supplied to a player. Its
Media3 DASH data source and DRM session callbacks are platform implementations.
AVPlayer documents native HLS playback; the selected Android DASH `.mpd` is not
an established AVPlayer input. A content-key API is not itself a DASH player.

Apple's native HLS/FairPlay path is an available alternative architecture only
if ABEMA supplies a compatible authorized delivery and license flow. FairPlay
deployment credentials have eligibility requirements; they are not a generic
replacement for the Android exchange. Do not infer compatibility by changing
a manifest format, copying a helper algorithm or transferring keys. [FairPlay][fps]

ABEMA lists Safari on supported iPhones and its own iPhone/iPad application.
That establishes first-party Apple delivery, not support for arbitrary
WKWebView embedding or a third-party native playback interface. The browser
path should be investigated separately from native playback. [ABEMA devices][abema-devices]

Twitch offers documented OAuth and web embeds. Tachiai's current native
playback instead uses the selected Smart TV client identity and private
playback resolution; that remains a provider-maintenance risk on iOS as on
Android. The official embed is an alternative to investigate, with parent/origin,
minimum-size and mobile activation constraints that matter for a small second
pane. It is not established as equivalent to the native viewer. [Twitch OAuth][twitch-oauth],
[Twitch embed][twitch-embed]

## Browser, authentication and runtime hosting

| Android mechanism | Available iOS interface | Fit and concerning gaps |
| --- | --- | --- |
| Persistent isolated browser/profile state | WKWebView with UUID-addressed `WKWebsiteDataStore` profiles | **Documented on iOS 17+.** Bind each store to the intended provider instance and route; test restart and same-account reuse. A Safari login is not evidence that Tachiai's store is signed in. [WebKit profiles][profiles] |
| Provider-controlled OAuth browser flow | `ASWebAuthenticationSession` | **Documented for callback-based authentication.** Shared/ephemeral browser-session choices exist. It does not expose arbitrary provider-page state or replace a missing callback contract. No per-session proxy attachment was verified; it must not be assumed to inherit Tachiai's website data store or selected provider route. [Web authentication][web-auth], [Ephemeral sessions][ephemeral] |
| ABEMA email grant handoff | WKWebView for provider-controlled sign-in; native validation would need an established handoff | **Gap.** Android observes an account-page HTTPS subresource request's URL, method and headers through `shouldInterceptRequest`. Ordinary navigation callbacks do not establish equivalent access to HTTPS XHR request headers. |
| ABEMA automatic renewal from a remembered browser login | Persistent website data plus an iOS renewal coordinator | **Unknown.** Retaining a browser session is available; safely obtaining and validating the renewed grant still depends on resolving the handoff gap. |
| App-owned HTTPS bootstrap assets and request interception | `WKURLSchemeHandler` for custom schemes; normal HTTPS hosting | **Partial equivalent.** WebKit rejects handlers for schemes it already handles, including HTTPS. A custom scheme changes origin/security semantics and is not a drop-in replacement for the current intercepted HTTPS runtime. [Scheme handlers][scheme] |
| Running selected unchanged ABEMA browser bundles | WKWebView; JavaScriptCore `JSContext` for JavaScript execution | **Unknown bundle compatibility.** JavaScriptCore is an engine, not a browser DOM/media environment. Web APIs, origins, CSP, storage and opaque helper behavior need verification before claiming asset reuse. [JavaScriptCore][jsc] |
| Separate approval, preparation and playback processes; Binder/UID checks; restart relay | Apple framework isolation, app extensions and platform-specific ownership mechanisms | **Gap in demonstrated equivalence.** Android named processes and caller-UID IPC are not portable. iOS 26 host-defined app extensions exist, but their suitability for these media/browser lifetimes and recovery is unverified. [App extensions][extensions] |

For ABEMA, reaching an account-management screen does not by itself establish
that Tachiai has validated and saved the account. A feasibility test must prove
the provider-controlled email flow, intended account identity, trusted handoff,
restart persistence and silent renewal independently. Continue to keep passwords
on provider pages and avoid app-injected login scripts or generic native bridges.

## Routes and native transports

The required behavior is narrower than a device-wide VPN: each provider has an
explicit route, independently owned sessions and ordered startup fallback.
Routes must not silently become direct connections when a proxy or tunnel
fails. Multiple exits/services must remain distinguishable from one global VPN.

| Android mechanism/library | Available iOS item | Fit and concerning gaps |
| --- | --- | --- |
| Process-scoped AndroidX WebView proxy override | `WKWebsiteDataStore.proxyConfigurations`, HTTP CONNECT/SOCKS5 proxy configurations | **Documented on iOS 17+.** Data-store scope may improve per-instance separation. Separate stores/routes and proxy credentials still need an actual failure/isolation test. [WebKit proxy API][wk-proxy] |
| Explicit routed OkHttp connection factories | `URLSessionConfiguration.proxyConfigurations` and Network `ProxyConfiguration` | **Documented.** Proxy credentials and a failover flag are available. Keep direct failover disabled; verify DNS, redirects and failure behavior. This does not configure AVPlayer. [Session proxies][session-proxy], [Proxy configuration][proxy], [Failover][failover] |
| Media3 media-request factories, including manifests and segments | AVPlayer/AVURLAsset; `AVAssetResourceLoaderDelegate` for supported resource loading | **Critical unknown.** No public per-asset proxy option was verified in this review. Check the installed Xcode SDK and Apple guidance before committing to a design. Resource loading is not demonstrated as a complete HLS/DRM network interception layer. [Asset options][asset-options], [Resource loader][resource-loader] |
| Userspace WireGuard/netstack plus local CONNECT listener | Existing Go core as a port candidate; official WireGuardKit as a separate Apple VPN integration | **Inferred core portability; documented WireGuardKit availability.** WireGuardKit is not proof of independent per-player routing. Compile the exact pinned core and verify its socket/data paths before reuse. [WireGuard embedding][wireguard], [Userspace example][netstack] |
| OpenVPN 3 Core behind native routebridge | Upstream C++ OpenVPN 3 Core, also used for iOS | **Documented upstream platform use.** Android JNI/AAR, build scripts and linking need replacement; preserve the current supported certificate/profile subset. Upstream offers MPL-2.0/AGPL-3 licensing alternatives; record the selected option for the Apple build. [OpenVPN Core][openvpn] |
| Patched OpenConnect C library and its TLS/crypto dependency closure | Upstream OpenConnect source; an Apple build is a port candidate | **Gap.** No maintained official Swift/iOS wrapper was established. Our Android shared-library replacement/rebuild material does not establish an appropriate iOS packaging, signing or LGPL compliance design. This requires review, not an assumption of either permission or prohibition. [OpenConnect][openconnect] |
| System VPN fallback as an alternative design | NetworkExtension packet-tunnel provider and provisioned entitlement | **Documented but different scope.** Packet-tunnel per-app mode requires managed devices; iOS app proxy providers are managed-device-only. Selecting an app does not distinguish its two providers. One enabled enterprise VPN configuration is not multiple simultaneous provider tunnels. [Deployment limits][ne-deployment], [Tunnel manager][tunnel-manager] |

The complete routing proof must include account checks, bundles, DNS,
master/variant manifests, media/audio/subtitle segments, redirects, licensing
and renewal requests. Success of a metadata request or region check covers only
that request. Test both distinct concurrent exits and failure without direct
fallback. Android's current limitation for different routes on duplicate ABEMA
feeds must not be advertised as already solved on either platform.

A local proxy/resource-loader design is an investigation option, not a selected
solution. It must preserve TLS, provider entitlement, supported media loading
and opaque DRM boundaries. Do not assume URLSession settings are inherited by
every Apple framework or that adding a system VPN preserves route independence.

## Security, inspection and development tools

| Requirement | Available iOS item | Fit and concerning gaps |
| --- | --- | --- |
| Android Keystore keys, AES-GCM and atomic no-backup grant records | Keychain, CryptoKit AES-GCM, file Data Protection and backup exclusion | **Documented building blocks.** Choose device-only accessibility and non-synchronization deliberately; preserve slot/account binding and missing-key failure semantics. Device-only Keychain items do not migrate to another device, but this is not the same as blanket exclusion from same-device backups. [Keychain accessibility][keychain], [AES-GCM][aes], [Backup exclusion][backup] |
| Local-only inspect variant with browser debugging | Separate Xcode build configuration and `WKWebView.isInspectable` | **Documented inspection API.** Default is false; a local configuration can enable Safari Web Inspector. CI/archive/distribution exclusion needs explicit build checks, as Android has today. It does not remove OS/DRM protection. [Web Inspector][inspector] |
| Normal-build screenshot restrictions | Scene capture-state observation, screenshot notifications and sensitive-view handling | **Policy gap.** No general equivalent to Android's window-wide `FLAG_SECURE` was established. A screenshot notification reports an event; it does not establish prevention. FairPlay can independently black out protected video even in inspection builds; audio still needs its own capture policy. [Capture handling][capture], [Screenshot notification][screenshot], [Protected capture][protected-capture] |
| Bounded diagnostics without provider secrets | Unified logging and app-owned structured failure records | **Inferred implementation.** Keep closed error categories, bounded records and redaction; framework error descriptions or signed URLs should not be exported wholesale. [Logging][logging] |
| JVM tests, Compose/Espresso tests and ADB tooling | Swift Testing, XCTest/XCUIAutomation, Simulator and Xcode device tools | **Documented equivalents.** Existing fixtures/expectations can guide new tests; Android test code and ADB commands do not transfer unchanged. Real-device media/DRM evidence remains necessary. [Apple testing][testing] |
| Linux/Docker Android build and shared debug keystore | macOS/Xcode, Apple signing/provisioning and Apple-target frameworks | **Toolchain gap for this laptop's existing workflow.** A Mac build environment and signing access must be arranged. The Android debug certificate is unrelated to Apple signing. KMP and gomobile do not remove the Xcode requirement. [KMP setup][kmp-setup], [gomobile][gomobile] |

Current routebridge builds Android ARM64/x86-64 outputs. An iOS port would need
device and Simulator framework slices, Apple-specific C/C++ linking and its own
native-source/license companion. A successful Go compilation alone would not
verify the embedded OpenVPN/OpenConnect transport closure.

Personal-device Xcode installation and public distribution are separate tasks.
Apple's free Personal Team provisioning expires after seven days and has device
limits; TestFlight builds are available for up to 90 days and external testing
can require review. A downloadable unsigned archive is not a generally
installable equivalent to an Android APK. [Developer accounts][accounts],
[TestFlight][testflight]

### Distribution beyond the App Store

**Documented:** free Personal Teams allow up to 10 temporary App IDs, three
registered devices and three installed apps per device, with seven-day profiles.
Paid development/ad hoc installation avoids a public store listing but retains
registered-device, signing and provisioning requirements. A paid program permits
100 devices per product family per membership year; disabling one does not free
its slot during that year. Plan profile renewal and signed updates rather than
permanent installation. [Account limits][accounts], [Registered devices][devices]

**Documented, region-dependent:** Apple's current alternative-distribution pages
describe marketplaces in the EU, Japan and Brazil. This is not worldwide free
installation of arbitrary IPA files: OS version, account/location eligibility,
developer agreements and notarization still apply. Direct Web Distribution of
ordinary apps is documented for EU iOS 17.5+/iPadOS 18+; do not extend that claim
to tvOS or to every marketplace region. Recheck the current terms before choosing
a public distribution channel. [Alternative distribution][alternative],
[EU Web Distribution][web-distribution]

The [Apple TV review's distribution matrix](tvos-interface-review.md#distribution-and-installation)
compares personal development, paid development/ad hoc, TestFlight, enterprise
and regional alternatives, including expiry and update friction. These methods
change how an app is installed; they do not add unavailable operating-system
APIs or resolve provider DRM, browser or routing compatibility.

For App Store distribution, review third-party service authorization, downloaded
runtime code and VPN classification. Apple documents relevant rules in 5.2.2,
2.5.2/2.5.6 and 5.4; whether Tachiai's particular architecture is acceptable is
**Unknown**. App-contained transport is not automatically the same deployment as
a NetworkExtension VPN, nor does technical operation establish approval.
[App Review Guidelines][review]

## Gap register and cheapest useful validation

| Priority | Unresolved requirement | Smallest useful next check | Evidence required to proceed |
| --- | --- | --- | --- |
| Gate | Authorized ABEMA iOS media/key-session path | Identify provider-compatible delivery for one selected free live source and one replay; check actual asset/key-session support in Xcode and on a device | Intended sources render through an opaque authorized exchange; no key extraction, format assumptions or substituted content |
| Gate | Email account handoff and renewal | Trace provider-controlled sign-in in an isolated WKWebView using available public interfaces | Correct account is validated and saved, survives restart, and renews from a remembered session without unnecessary user interaction |
| Gate | Independent routing for the full playback request graph | First use controlled HTTPS endpoints and unprotected HLS with two sessions/stores/players | Distinct expected exits, complete request coverage, isolated credentials and failure without direct fallback, including IPv6/DNS and redirects |
| High | Two simultaneous provider players and audio mix | On a named iPhone/iPad, first run two test HLS sources, then actual ABEMA + Twitch and duplicate selections | Both videos and both audio tracks, swaps, gain controls, seek/live recovery, sustained resource use and interruptions recorded |
| High | Browser helper compatibility and process recovery | Load verified runtime assets under the proposed origin/profile model; exercise teardown and recreation | Same source/account/session ownership, fresh CDM/runtime when required, no stale callbacks or assumption that dropping a reference exits a process |
| High | Native route libraries and packaging | Build the pinned WireGuard/CONNECT core to an Apple framework, then add each C/C++ transport independently | Device/Simulator linkage, supported profile subset, safe credential handling, cancellation and a documented license/source/relink strategy |
| Medium | Quality-selection parity | Compare native Apple selection APIs with actual provider renditions | Distinguish supported preferences from exact selections and observed consumed formats; disable unavailable controls |
| Medium | Normal/inspect policy and repeatable development | Establish Mac/Xcode, provisioning, local inspect configuration and release guards | Reproducible build/install/debug path; inspection excluded from distribution; screenshots and protected surfaces described accurately |

The three gates should be investigated before a broad UI port. Public test
media can validate generic playback/routing without waiting for provider access.
Failures should narrow the supported capability set or trigger a deliberate
architecture choice; an external provider app or a global VPN must not be
silently presented as equivalent to the requested embedded, independently routed
two-stream experience.

## Repository evidence

The dependency catalogue and current native bridge are the inventory baseline:

- [Android dependency catalogue](https://github.com/altendky/tachiai/blob/97ab8c14fe82524a69601b34056046688f7bf53a/apps/android/gradle/libs.versions.toml)
- [Primary app dependencies and build variants](https://github.com/altendky/tachiai/blob/97ab8c14fe82524a69601b34056046688f7bf53a/apps/android/app/build.gradle.kts)
- [Native routebridge dependencies](https://github.com/altendky/tachiai/blob/97ab8c14fe82524a69601b34056046688f7bf53a/apps/android/routebridge/go.mod)
- [Android account browser handoff](https://github.com/altendky/tachiai/blob/97ab8c14fe82524a69601b34056046688f7bf53a/apps/android/app/src/main/kotlin/net/fstab/tachiai/feature/connections/AbemaApprovalBrowserActivity.kt#L355-L391)
- [Android protected DASH player](https://github.com/altendky/tachiai/blob/97ab8c14fe82524a69601b34056046688f7bf53a/apps/android/app/src/main/kotlin/net/fstab/tachiai/platform/media/BoundedNativeDashPlayer.kt)
- [Android secret storage](https://github.com/altendky/tachiai/blob/97ab8c14fe82524a69601b34056046688f7bf53a/apps/android/app/src/main/kotlin/net/fstab/tachiai/platform/storage/AndroidPrivateSecretStore.kt)

Related local documents: [architecture](architecture.md),
[casting and screen mirroring](casting.md),
[provider integrations](provider-integrations.md),
[native access evidence](native-access-experiments.md),
[route implementation and constraints](source-network-routing.md),
[security boundaries](security-and-privacy.md) and
[Android development/inspection workflow](android-development.md).

[swiftui]: https://developer.apple.com/documentation/swiftui/
[compose]: https://blog.jetbrains.com/kotlin/2025/05/compose-multiplatform-1-8-0-released-compose-multiplatform-for-ios-is-stable-and-production-ready/
[kmp-integration]: https://kotlinlang.org/docs/multiplatform/multiplatform-ios-integration-overview.html
[uniffi]: https://mozilla.github.io/uniffi-rs/latest/
[ktor]: https://ktor.io/docs/client-engines.html
[serialization]: https://kotlinlang.org/docs/serialization.html
[qr]: https://developer.apple.com/documentation/coreimage/ciqrcodegenerator
[file-import]: https://developer.apple.com/documentation/swiftui/view/fileimporter(ispresented:allowedcontenttypes:oncompletion:)
[share]: https://developer.apple.com/library/archive/documentation/General/Conceptual/ExtensibilityPG/Share.html
[defaults]: https://developer.apple.com/documentation/foundation/userdefaults
[idle]: https://developer.apple.com/documentation/uikit/uiapplication/isidletimerdisabled
[avplayer]: https://developer.apple.com/documentation/avfoundation/avplayer
[volume]: https://developer.apple.com/documentation/avfoundation/avplayer/volume
[audio]: https://developer.apple.com/documentation/avfaudio/avaudiosession
[seekable]: https://developer.apple.com/documentation/avfoundation/avplayeritem/seekabletimeranges
[scheduled]: https://developer.apple.com/documentation/avfoundation/avplayer/setrate(_:time:athosttime:)
[bitrate]: https://developer.apple.com/documentation/avfoundation/avplayeritem/preferredpeakbitrate
[resolution]: https://developer.apple.com/documentation/avfoundation/avplayeritem/preferredmaximumresolution
[observed]: https://developer.apple.com/documentation/avfoundation/avplayeritemaccesslogevent/observedbitrate
[keys]: https://developer.apple.com/documentation/avfoundation/avcontentkeysession
[clearkey]: https://developer.apple.com/documentation/avfoundation/avcontentkeysystem/clearkey
[fps]: https://developer.apple.com/streaming/fps/
[abema-devices]: https://help.abema.tv/hc/ja/articles/360013743211-ABEMAを利用できるデバイス
[twitch-oauth]: https://dev.twitch.tv/docs/authentication/getting-tokens-oauth/
[twitch-embed]: https://dev.twitch.tv/docs/embed/video-and-clips/
[profiles]: https://webkit.org/blog/14423/building-profiles-with-new-webkit-api/
[web-auth]: https://developer.apple.com/documentation/authenticationservices/aswebauthenticationsession
[ephemeral]: https://developer.apple.com/documentation/authenticationservices/aswebauthenticationsession/prefersephemeralwebbrowsersession
[scheme]: https://developer.apple.com/documentation/webkit/wkwebviewconfiguration/seturlschemehandler(_:forurlscheme:)
[jsc]: https://developer.apple.com/documentation/javascriptcore/jscontext
[extensions]: https://developer.apple.com/documentation/extensionfoundation/adding-support-for-app-extensions-to-your-app
[wk-proxy]: https://developer.apple.com/documentation/webkit/wkwebsitedatastore/proxyconfigurations-6g21z
[session-proxy]: https://developer.apple.com/documentation/foundation/urlsessionconfiguration/proxyconfigurations
[proxy]: https://developer.apple.com/documentation/network/proxyconfiguration
[failover]: https://developer.apple.com/documentation/network/proxyconfiguration/allowfailover
[asset-options]: https://developer.apple.com/documentation/avfoundation/initialization-options
[resource-loader]: https://developer.apple.com/documentation/avfoundation/avassetresourceloaderdelegate
[wireguard]: https://www.wireguard.com/embedding/
[netstack]: https://github.com/WireGuard/wireguard-go/blob/master/tun/netstack/examples/http_client.go
[openvpn]: https://github.com/OpenVPN/openvpn3
[openconnect]: https://github.com/openconnect/openconnect/blob/master/README.md
[ne-deployment]: https://developer.apple.com/documentation/technotes/tn3134-network-extension-provider-deployment
[tunnel-manager]: https://developer.apple.com/documentation/networkextension/netunnelprovidermanager
[keychain]: https://developer.apple.com/documentation/security/restricting-keychain-item-accessibility
[aes]: https://developer.apple.com/documentation/cryptokit/aes/gcm
[backup]: https://developer.apple.com/documentation/foundation/urlresourcevalues/isexcludedfrombackup
[inspector]: https://developer.apple.com/documentation/safari-developer-tools/enabling-inspecting-content-in-your-apps
[capture]: https://developer.apple.com/documentation/swiftui/protecting-sensitive-content-when-screen-sharing
[screenshot]: https://developer.apple.com/documentation/uikit/uiapplication/userdidtakescreenshotnotification
[protected-capture]: https://developer.apple.com/documentation/uikit/uiscreen/iscaptured
[logging]: https://developer.apple.com/documentation/os/logging
[testing]: https://developer.apple.com/documentation/xcode/testing
[kmp-setup]: https://kotlinlang.org/docs/multiplatform/quickstart.html
[gomobile]: https://pkg.go.dev/golang.org/x/mobile/cmd/gomobile
[accounts]: https://developer.apple.com/help/account/basics/about-your-developer-account
[testflight]: https://developer.apple.com/help/app-store-connect/test-a-beta-version/testflight-overview/
[review]: https://developer.apple.com/app-store/review/guidelines/
[devices]: https://developer.apple.com/help/account/devices/devices-overview/
[alternative]: https://support.apple.com/en-ie/118110
[web-distribution]: https://developer.apple.com/support/web-distribution-eu/
