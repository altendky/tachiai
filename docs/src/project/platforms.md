# Cross-platform implementation landscape

Reviewed 2026-10-17 against Android and Rust baseline
`49a0fa6ae90ad6b90b751331a6e95714e21c8d37`. This is a research and planning
snapshot, not a declaration that new platforms work. It covers application
interfaces, libraries, shared code, development equipment, testing and
distribution, including installation outside official app stores.

## Overall direction

Keep a shared Rust domain library with platform-owned players, authentication,
network effects, storage and presentation. Extend the existing Android
application for Android TV/Google TV. Use related Swift application hosts for
iOS/iPadOS and tvOS, with separate capability gates. Investigate a desktop web
front end independently of whether an extension or local companion is needed.
These are recommendations; additional platform implementations are not present.

Android TV has the highest immediate implementation reuse. iOS and tvOS share
substantial Apple development skills and frameworks, but tvOS lacks the embedded
WebView needed by today's ABEMA runtime path. Web has the easiest independent
interface distribution, but significant sandbox constraints on routing and
provider integration. Installation feasibility and playback feasibility must
be evaluated separately.

Detailed reviews:

- [Android TV and Google TV](android-tv-interface-review.md): Android library
  reuse, remote/focus design, native ABIs, decoders, WebView and sideloading.
- [iOS and iPadOS](ios-interface-review.md): Apple equivalents for Android
  interfaces, libraries, account flow, native media and routes.
- [Apple TV / tvOS](tvos-interface-review.md): reuse from iOS, missing browser
  runtime, television controls, device testing and provisioning.
- [Desktop web](desktop-browser-review.md): Windows/macOS/Linux browsers,
  HTTPS/PWA, extensions and local companions.
- [Distribution comparison](platform-distribution.md): independent installation,
  signing, expiration and update ownership across all targets.
- [Validation roadmap](platform-validation-plan.md): cheapest experiments that
  resolve the important gaps before committing to a full port.

**Implemented/observed** means code or bounded evidence recorded in this
repository. **Documented** means an upstream interface exists. **Inferred**
means the proposed combination appears plausible. **Unknown** means the exact
Tachiai/provider/device combination has not been established. API availability
does not establish provider support, entitlement or device performance.

## Platform comparison

| Target | Application interfaces and libraries | Reuse and controlling gaps | Development and current evidence |
| --- | --- | --- | --- |
| Android phone/tablet | Kotlin, Compose, Media3 HLS/DASH, WebView/AndroidX WebKit, OkHttp, Android Keystore, native routebridge and Rust/UniFFI | **Implemented baseline.** Native two-feed hosts, provider accounts/catalogues and session-owned routes exist; sustained reliability and broader hardware compatibility remain bounded by evidence. | Linux/Android SDK/NDK builds; physical Pixel 6 and persistent emulator observations are recorded. Emulator results do not establish hardware DRM, audio or decoding parity. |
| Android TV / Google TV | Same Android services, Media3, WebView and native transports; Compose for TV and deliberate D-pad/focus behavior | **Inferred highest reuse.** Remote input, TV WebView/provider behavior, two decoders and HDMI output need validation. Current ARM64/x86-64-only APK excludes ARM32-only TVs. | Linux toolchain remains useful. TV emulator for navigation; actual TV/remote required for playback. Available Shield model/ABI has not been inventoried or tested. |
| iOS / iPadOS | SwiftUI/UIKit, AVFoundation/AVKit, WKWebView, AuthenticationServices, Foundation/Network, Keychain, Rust/UniFFI Swift | **Documented interfaces; unproven integration.** Native media/DRM delivery, ABEMA helper compatibility, account handoff and complete per-provider routing are gates. Kotlin Android player/services do not port directly. | macOS/Xcode and real Apple devices needed for meaningful build/playback validation. Swift binding generation on Linux is implemented; Apple linking and runtime tests are not. |
| Apple TV / tvOS | Related SwiftUI/UIKit, AVFoundation/AVKit, AuthenticationServices and Rust Swift facade; TV focus and remote interaction | **Partial Apple reuse.** No public WKWebView: ABEMA's current browser helper needs a separately verified solution. Web authentication availability does not supply a browser/CDM runtime. Two-player hardware/output behavior is unknown. | macOS/Xcode; Simulator for UI, physical Apple TV/remote/display for integration. No Tachiai tvOS build or test exists. |
| Desktop web on Windows/macOS/Linux | TypeScript/JavaScript UI, existing Rust/Wasm facade, HTML media, provider embeds or candidate HLS/DASH libraries; optional extension/companion | **Partial equivalent.** Generic interface/timing is feasible. ABEMA playback/control, account handoff, CORS/CDM behavior and full route coverage remain unknown. PWA installation grants no native transport privileges. | Linux can build the web/core pieces and test Chromium/Firefox. Windows/macOS and Safari/CDM/device coverage need their actual environments. No whole web application exists. |

The linked platform reviews provide primary sources for the documented interface
claims and identify alternatives without treating them as chosen dependencies.
Google TV is an Android TV OS experience in this scope, not another independent
application platform. Desktop web does not establish mobile-web or TV-browser
support. A native Windows/macOS/Linux shell is an alternative deployment model,
not a delivered target in this research.

## Shared library boundary

The [shared Rust core](shared-rust-core.md) is already used by Android for timing
and seek planning. `packages/core` is dependency-free domain code;
`packages/core-ffi` supplies generated Kotlin/Swift interfaces;
`packages/core-web` supplies the Wasm/JavaScript interface. Android bindings and
the small browser binding example have checks; generated Swift sources do not
establish an Apple application.

| Candidate responsibility | Sharing assessment | Boundary to preserve |
| --- | --- | --- |
| Checked timing arithmetic, seek planning and capability-based results | **Implemented shared rules** | Hosts observe their player and dispatch the plan; a requested offset is not measured synchronization. |
| Source identity, provider/name display rules, configuration validation, route ordering and recovery policy | **Possible future extraction** | Most currently remain Android code. Share typed policy and fixtures only after defining host effects and failure semantics. |
| Provider response parsing and catalogue models | **Possible partial extraction** | Endpoints, entitlement, browser origin restrictions and session binding differ. Reusable parsing does not establish portable credentials or licensing. |
| Player objects, native DRM/CDM sessions, WebViews, socket execution, keychain/keystore and lifecycle | **Platform adapters** | Rust does not confer unavailable OS/browser permissions. Keep opaque secrets and player state out of generic rules. |
| Touch, TV focus/remote, accessibility and desktop interaction | **Platform presentation** | Share product concepts and capability vocabulary; do not require the same widget hierarchy or input gestures. |

There is no defensible reuse percentage yet. Android TV reuses substantially
more of the existing implementation than Apple or web. iOS/tvOS can share new
Apple modules, but their browser and media capabilities differ. All hosts can
consume the same pure rules without requiring a shared UI framework.

Rust itself has relevant targets. Current upstream documentation lists ARM64
tvOS device and ARM64 Simulator targets as tier 2, with standard libraries
available through rustup; Intel tvOS Simulator is tier 3. Apple SDKs from Xcode
are still required. Confirm availability in the repository's pinned compiler
before adding packaging jobs. [Rust tvOS targets][rust-tvos]

The browser Wasm target supports pure computation but lacks ordinary operating
system facilities such as a usable filesystem or spawned OS threads. Keep future
core effects explicit rather than assuming a native networking/storage crate
will acquire equivalent browser powers. [Rust Wasm target][rust-wasm]

Native Swift distribution would need built device/Simulator libraries and an
XCFramework/Swift package, not just generated Swift files. Android already has
an AAR publication configuration; the web binding has build outputs, not a
published application. Publishing libraries and distributing end-user apps are
separate release tasks. [Apple binary Swift packages][swift-binary]

## Common acceptance requirements

Every target must demonstrate selected live/replay combinations, two-feed audio,
optional timing controls, recovery, provider-controlled login, retained local
accounts and explicit route failure behavior. Every network path matters:
authentication, catalogue, redirects, manifests, media, license requests, DNS
and IPv6. A proxy/VPN installed on a device is not evidence that each path uses
the selected route.

Do not port Android's opaque ABEMA/CDM exchange by assuming another player accepts
the same response. Do not replace missing browser functionality with copied
helper algorithms, extracted keys or session transfer between devices. A new
platform needs its own verified entitlement and source/session binding within
the existing [security boundaries](security-and-privacy.md).

The [validation roadmap](platform-validation-plan.md) specifies evidence to
collect. No new provider, account, distribution or physical-device experiment
was performed for this review.

[rust-tvos]: https://doc.rust-lang.org/rustc/platform-support/apple-tvos.html
[rust-wasm]: https://doc.rust-lang.org/rustc/platform-support/wasm32-unknown-unknown.html
[swift-binary]: https://developer.apple.com/documentation/xcode/distributing-binary-frameworks-as-swift-packages
