# Apple TV interface, library and distribution review

Reviewed 2026-10-17. Scope: a native tvOS application for Apple TV, compared with
the [iPhone/iPad investigation](ios-interface-review.md), the current Android
application and the merged [Rust timing core](shared-rust-core.md). This is a
documentation review. No Apple application, Apple framework package, Simulator
build, installation or provider playback experiment was performed.

**Assessment:** tvOS is closely related to iOS development and is a plausible
target. Installation outside the public App Store is documented. The greater
uncertainty is equivalent provider behavior: tvOS has no supported embedded web
view, and neither Apple's media APIs nor its authentication APIs establish an
equivalent ABEMA helper, account handoff, DRM or independently routed player.

**Documented** identifies upstream availability; **Inferred** identifies a
reasonable design candidate; **Unknown** identifies unverified Tachiai fit.
Availability, successful installation and successful provider playback are
separate pieces of evidence.

## What can be shared with iOS

| Area | Apple interface or library | Tachiai fit and remaining work |
| --- | --- | --- |
| Application shell | Swift, SwiftUI and UIKit | **Documented** Apple TV tools. **Inferred:** share presentation models and selected components, with separate iOS/tvOS targets and availability checks. A touch screen layout is not a remote interface. [tvOS development][tvos] |
| Remote navigation | UIKit focus engine; SwiftUI focus state and sections | **Documented.** Specify initial focus, directional movement, Back/Menu behavior, modal dismissal, focus restoration after a failed feed and reachable controls in both panes. Replace touch drag gestures with remote-operable alternatives. [Apple TV focus][focus], [SwiftUI focus][swiftui-focus] |
| Native media | AVFoundation AVPlayer/AVPlayerItem/AVPlayerLayer; AVKit where appropriate | **Documented** HLS playback building blocks. **Inferred:** reuse an Apple playback adapter and compose two players. **Unknown:** concurrent protected playback, exact quality controls, seek/live-window semantics and sustained device resource use. [AVPlayer][player], [AVKit][avkit] |
| Audio | AVAudioSession and per-player volume/mute | **Documented** shared framework family. **Unknown:** simultaneous audio, interruptions, HDMI/Bluetooth route changes, AirPlay behavior and TV remote volume expectations in our two-feed viewer. [Audio session][audio], [Player volume][volume] |
| HTTP, preferences and storage | Foundation URLSession, Codable, UserDefaults, app-container files and Security/Keychain | **Documented** building blocks. **Inferred:** substantial Apple adapter reuse. Keep secrets in protected storage, preserve account/route bindings and decide whether Apple TV user profiles isolate grants. Do not automatically synchronize provider cookies or grants between devices. [URLSession][session], [Keychain][keychain], [Apple TV users][users] |
| Portable timing logic | Existing Rust core and generated UniFFI Swift interface | **Implemented only in its current narrow scope:** the Rust timing rules are integrated on Android. **Unknown:** Apple target compilation/linking, device/Simulator slices, Swift calling code and framework packaging. The core currently owns no authentication, player, transport or persistent state. [Shared core](shared-rust-core.md) |

Build the shared Apple pieces behind platform interfaces, with distinct TV
navigation and lifecycle behavior. This permits reuse without making iOS support
an inaccurate proxy for tvOS support. Actual target availability must be checked
against the chosen Xcode SDK and deployment version.

Rust's current platform guide lists ARM64 tvOS device and ARM64 Simulator as
Tier 2 without host tools, with targets available through rustup. The x86-64
Simulator target remains Tier 3. Most standard-library functionality is
supported, with documented incomplete process-command behavior; corresponding
Xcode tvOS SDKs are required. This improves the toolchain starting point but
does not establish that Tachiai's pinned toolchain, UniFFI runtime or transitive
native libraries have been built for those targets. Prefer an Apple silicon
Mac for the initial device/Simulator matrix and verify the pinned targets
before designing packaging. [Rust tvOS targets][rust-tvos]

## Browser and authentication differences

**Documented:** Apple says web views are unsupported on tvOS. There is no public
WKWebView equivalent for loading ABEMA's account page or browser helper inside
the application. Private WebKit interfaces are not a supported porting plan.
[Apple web-view guidance][webview]

**Documented:** ASWebAuthenticationSession is available on tvOS starting at 16.0,
according to Apple's API metadata. Missing WKWebView does **not** mean all web
authentication APIs are missing. This API completes a callback-based web
authentication session; it is not an app-controlled DOM, cookie store or network
interceptor. **Unknown:** the exact TV/companion interaction, callback fit and
route used for each provider must be established on current hardware. Its
availability does not establish ABEMA's Android account-page handoff.
[Web authentication][web-auth], [API availability metadata][web-auth-metadata]

Apple also documents iPhone/iPad-assisted Apple TV sign-in using
AuthenticationServices and associated domains. That mechanism requires a
relationship with the relevant domain; it is not permission to collect ABEMA
passwords or claim ABEMA's domain. A provider-supported device-code flow can use
a displayed URL/code or QR code and avoid TV text entry. Twitch documents a
device-code grant for limited-input clients. **Unknown:** an equivalent authorized
ABEMA account-linking flow has not been established for Tachiai.
[Apple TV sign-in][sign-in], [Twitch device authorization][twitch-device]

A companion or QR flow is useful only if it produces a provider-supported,
TV-bound authorization result. Copying an iPhone browser's cookies or CDM
session is not an established handoff. Revalidate source, account, entitlement
and route on the target device.

JavaScriptCore can execute JavaScript, but is not a DOM, browser-origin model,
WebView storage/network implementation or browser CDM environment. It cannot be
assumed to run ABEMA's unchanged opaque helper/bootstrap merely because a bundle
is JavaScript. Inspect exact runtime dependencies before proposing reuse; do not
copy helper algorithms or extract keys to replace unavailable browser behavior.
[JavaScriptCore][jsc], [Current security boundary](security-and-privacy.md)

## Media, DRM and routing gates

The current Android ABEMA path uses selected-source validation, public browser
bundles, an opaque initial exchange and a fresh Android CDM. It is not simply an
HLS link. AVPlayer's HLS support and AVContentKeySession's Clear Key/FairPlay
identifiers do not prove compatibility with Android DASH/cenc packaging, an
Android license response or ABEMA's browser session. Establish an authorized
Apple-compatible delivery and opaque key-session exchange for both live and
replay. ABEMA's own Apple TV application, if supported, would establish only
first-party delivery; it would not give Tachiai a reusable API.
[Apple content keys][keys], [FairPlay][fairplay],
[Provider boundary](provider-integrations.md)

Twitch's documented account APIs and device authorization do not themselves
provide a native playback URL. Its web embed cannot simply replace the native
player on a platform without the required supported web view. The current
private playback resolver and selected client identity require their own Apple
compatibility and maintenance evaluation. [Twitch embeds][twitch-embed]

| Routing requirement | Available interface | Evidence and gap |
| --- | --- | --- |
| Explicit native HTTP route | URLSessionConfiguration proxy configurations and Network ProxyConfiguration | **Documented** APIs; verify target availability. **Unknown:** pinned transport ports, DNS behavior, credentials, redirects, IPv6 and failure without direct fallback. Browser authentication and native media do not automatically inherit an HTTP client's proxy. [Session proxies][session-proxy], [Proxy configuration][proxy] |
| App-contained WireGuard/CONNECT, OpenVPN and OpenConnect | Port candidates from the current Go/C/C++ native closure | **Inferred portability**, not compiled tvOS libraries. Android JNI/AAR packaging and build scripts cannot be linked unchanged. Verify socket/process restrictions, cancellation, native source/license obligations and each protocol/profile subset. [Route implementation](source-network-routing.md) |
| System packet-tunnel VPN | NetworkExtension app extension on tvOS 17+ | **Documented**, but tvOS per-app packet-tunnel mode is unsupported. Apple's app-proxy deployment table does not list tvOS. A system VPN is not proof of two independently selected provider exits. Entitlements and provisioning still apply to development/ad hoc builds. [Apple deployment table][network-extension] |
| Native player media/license route | AVURLAsset/resource loading and Apple media networking | **Unknown complete coverage.** No complete per-player proxy equivalent has been established. Verify manifests, variants, segments, subtitles, redirects, key requests, renewal and errors through each intended exit, with two simultaneous feeds. [Resource loader][resource-loader] |

Keep app-contained route-library portability separate from permission to deploy
a NetworkExtension VPN. Neither a system VPN nor a local proxy is an established
replacement for independent per-provider routes. Off-store installation does
not disable TLS, entitlements, sandboxing, DRM or framework availability checks.

## Distribution and installation

These are supported delivery categories, not a promise that Tachiai qualifies
for every capability or region. Developer membership is separate from build
configuration: a Release build can still use development/ad hoc provisioning;
calling a build Debug does not decide its installation lifetime.

| Channel | iPhone/iPad | Apple TV | Friction and applicability |
| --- | --- | --- | --- |
| Personal Team / local Xcode | Documented personal-device testing | Xcode's signing guide covers Personal Teams and running on wireless tvOS devices; no Tachiai install tested | Free account: up to 10 App IDs, three devices and three apps per device; profiles expire after seven days and require rebuild/reinstallation. Verify the actual tvOS profile allocation with our account. Advanced capabilities vary by membership; tvOS Associated Domains is unavailable in the free-account column. [Account limits][account], [Xcode workflow][signing], [tvOS capabilities][capabilities] |
| Paid development or ad hoc | Documented registered-device installation | Explicitly documented for tvOS | Paid Developer Program; up to 100 devices per product family per membership year, including Apple TV separately from iPhone/iPad. Adding devices requires an updated profile/build; disabling one does not reclaim its annual slot. No public App Store listing is required. [Ad hoc profiles][adhoc], [Device registration][devices], [Install registered builds][registered] |
| TestFlight | Supported beta distribution | Supported beta distribution | Apple-hosted testing, not independent permanent sideloading. Builds last up to 90 days. Up to 100 internal team testers or 10,000 external testers; the first external build needs beta review and subsequent builds may need it. New builds and eventual expiry remain operational work. [Overview][testflight-overview], [Tester/review limits][testflight] |
| Enterprise internal distribution | Conditional organization program | Apple lists enterprise tvOS capabilities | For eligible legal organizations with 100+ employees, proprietary internal employee apps and Apple's verification. It is not a personal/public sideloading workaround. [Enterprise eligibility][enterprise], [tvOS capabilities][capabilities] |
| Regional alternative marketplace / direct website | Conditional iOS/iPadOS channels described below | No tvOS channel established by these programs | Region, OS, agreements and notarization still apply. Mac Developer ID/direct distribution is a different program and cannot be used as evidence of Apple TV installation. [Alternative distribution][alternative], [Distribution package requirements][package] |
| Public App Store | Normal public distribution option | Normal public distribution option | Apple review and applicable service, downloaded-code, browser and VPN rules require an architectural review. Successful local playback does not prove store acceptance. [Review rules][review] |

Paid development/ad hoc profiles are time-limited too. Apple Developer Technical
Support describes approximately one-year validity and recommends inspecting
the actual profile's expiration fields. Record the embedded profile's
ExpirationDate, or CreationDate plus TimeToLive, and its signing certificate's
expiry/revocation state for each delivered artifact. Membership renewal does
not silently replace an already installed profile: budget for signed updates
before expiry, device registration changes and certificate renewal. Do not
promise every build a full year merely because the account is paid.
[Apple DTS explanation][expiry], [Provisioning profile anatomy][profile-anatomy],
[Certificate roles][certificates]

### Regional alternatives are not worldwide IPA downloads

As of this review, Apple's consumer support page lists alternative marketplaces
in the EU, Japan and Brazil. Feature scope differs by region. Its developer
package requirements currently identify iOS apps, adding iPadOS in the EU;
direct Web Distribution of ordinary apps is documented for the EU on iOS 17.5+
and iPadOS 18+. Apple Account region and physical-location/eligibility checks
apply; verify the current user and developer requirements before selecting one.
[Regional overview][alternative], [Package eligibility][package],
[EU Web Distribution][web-distribution]

EU Web Distribution still requires authorization, accepted developer agreements,
notarization, a registered website domain and continuing responsibilities. The
current page records a terms change effective October 1, 2026; old summaries
of entry thresholds or fees should not be used without checking current terms.
These mobile programs do not document a general direct-install option for
tvOS. For our own Apple TV, paid registered-device deployment is the concrete
off-store path to validate first. [Current EU terms][web-distribution]

## First experiments and decision gates

The [casting review](casting.md) distinguishes screen mirroring from AirPlay
player routing and from a complete TV application with optional phone control.

| Priority | Smallest experiment | Evidence required |
| --- | --- | --- |
| Gate | On a Mac/Xcode, build an empty iOS target and tvOS target with the same generated Rust API | Separate device/Simulator framework slices link, timing fixtures agree, artifact versions match; record SDK/device/OS and signing/profile expiry |
| Gate | Install and update that tvOS app through Xcode, then paid ad hoc provisioning if selected | Pairing, Developer Mode requirements, remote launch, registered-device inclusion and update/data-retention behavior established; no assumption from iPhone installation |
| Gate | Test callback web authentication and Twitch device authorization on actual Apple TV | Current TV/companion UI, callback binding, user cancellation, intended routes and token validation observed; ABEMA remains separate until its authorized handoff exists |
| Gate | Establish selected ABEMA live and replay delivery, helper/runtime and opaque Apple CDM exchange | Source/account/session binding and entitlement preserved; no protected-media extraction or invented license conversion |
| Gate | Two controlled HTTPS/HLS feeds through distinct intended routes | Complete request graph, no direct fallback, cancellation/cleanup and route independence proven before provider playback |
| High | Two actual authorized provider feeds on named Apple TV hardware | Decode/DRM concurrency, mixed audio, remote focus, panel dismissal, seek/live recovery, failure isolation, sleep/wake and sustained memory/throughput recorded |

Simulator success is useful for models and navigation; it does not establish
hardware DRM, decoder limits, HDMI/audio behavior or real installation updates.
Inspect builds can expose app-owned diagnostics but cannot remove platform or
protected-media restrictions. iOS and tvOS should share verified adapters while
maintaining separate capability and experiment records.

## Primary sources

API and distribution links were reviewed on 2026-10-17. Apple platform SDKs,
agreements and regional eligibility can change; reconfirm them when selecting
the first deployment target and before publishing.

| Source group | What it establishes |
| --- | --- |
| [tvOS][tvos], [focus][focus], [AVPlayer][player], [Keychain][keychain] | Native application building blocks; no provider-specific compatibility claim |
| [Web-view guidance][webview], [ASWebAuthenticationSession][web-auth] and [metadata][web-auth-metadata] | Missing public embedded web view differs from available callback web authentication |
| [NetworkExtension deployment][network-extension] | tvOS packet tunnel availability and missing per-app mode |
| [Account limits][account], [ad hoc][adhoc], [devices][devices], [Apple DTS expiry][expiry] | Local/registered-device installation, limits and time-bound provisioning |
| [TestFlight][testflight-overview], [enterprise][enterprise], [regional alternatives][alternative], [EU web][web-distribution] | Beta, organization and regional distribution are distinct channels |

[tvos]: https://developer.apple.com/tvos/
[focus]: https://developer.apple.com/documentation/uikit/about-focus-interactions-for-apple-tv
[swiftui-focus]: https://developer.apple.com/documentation/swiftui/focusstate
[player]: https://developer.apple.com/documentation/avfoundation/avplayer
[avkit]: https://developer.apple.com/documentation/avkit
[audio]: https://developer.apple.com/documentation/avfaudio/avaudiosession
[volume]: https://developer.apple.com/documentation/avfoundation/avplayer/volume
[session]: https://developer.apple.com/documentation/foundation/urlsession
[keychain]: https://developer.apple.com/documentation/security/keychain-services
[users]: https://developer.apple.com/videos/play/wwdc2022/110384/
[webview]: https://developer.apple.com/design/human-interface-guidelines/web-views
[web-auth]: https://developer.apple.com/documentation/authenticationservices/aswebauthenticationsession
[web-auth-metadata]: https://developer.apple.com/tutorials/data/documentation/authenticationservices/aswebauthenticationsession.json
[sign-in]: https://developer.apple.com/videos/play/wwdc2021/10279/
[twitch-device]: https://dev.twitch.tv/docs/authentication/getting-tokens-oauth/#device-code-grant-flow
[jsc]: https://developer.apple.com/documentation/javascriptcore/jscontext
[keys]: https://developer.apple.com/documentation/avfoundation/avcontentkeysession
[fairplay]: https://developer.apple.com/streaming/fps/
[twitch-embed]: https://dev.twitch.tv/docs/embed/video-and-clips/
[session-proxy]: https://developer.apple.com/documentation/foundation/urlsessionconfiguration/proxyconfigurations
[proxy]: https://developer.apple.com/documentation/network/proxyconfiguration
[network-extension]: https://developer.apple.com/documentation/technotes/tn3134-network-extension-provider-deployment
[resource-loader]: https://developer.apple.com/documentation/avfoundation/avassetresourceloaderdelegate
[account]: https://developer.apple.com/help/account/basics/about-your-developer-account
[signing]: https://help.apple.com/xcode/mac/current/en.lproj/dev60b6fbbc7.html
[capabilities]: https://developer.apple.com/help/account/reference/supported-capabilities-tvos/
[adhoc]: https://developer.apple.com/help/account/provisioning-profiles/create-an-ad-hoc-provisioning-profile/
[devices]: https://developer.apple.com/help/account/devices/devices-overview/
[registered]: https://help.apple.com/xcode/mac/current/en.lproj/dev7ccaf4d3c.html
[testflight-overview]: https://developer.apple.com/help/app-store-connect/test-a-beta-version/testflight-overview/
[testflight]: https://developer.apple.com/testflight/
[enterprise]: https://developer.apple.com/programs/enterprise/
[alternative]: https://support.apple.com/en-ie/118110
[package]: https://developer.apple.com/help/app-store-connect/managing-alternative-distribution/get-an-alternative-distribution-package-id
[web-distribution]: https://developer.apple.com/support/web-distribution-eu/
[review]: https://developer.apple.com/app-store/review/guidelines/
[expiry]: https://developer.apple.com/forums/thread/823069
[profile-anatomy]: https://developer.apple.com/documentation/technotes/tn3125-inside-code-signing-provisioning-profiles
[certificates]: https://developer.apple.com/help/account/certificates/certificates-overview
[rust-tvos]: https://doc.rust-lang.org/rustc/platform-support/apple-tvos.html
