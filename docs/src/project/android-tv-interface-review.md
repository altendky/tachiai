# Android TV and Google TV implementation review

Reviewed 2026-10-17 against Tachiai commit
`49a0fa6ae90ad6b90b751331a6e95714e21c8d37`.
Scope: Android TV OS devices, including Google TV, compared with the current
Android phone/tablet implementation. This is documentation and source research;
no TV was installed, paired, provisioned or tested for this review. The project
mentions an available NVIDIA Shield, but its exact model, OS, ABI, browser engine
and playback capabilities have not been recorded here.

## Assessment

Android TV is the closest additional target to today's application. It can reuse
the Kotlin application services, Rust library, Media3 hosts, Android WebView
adapters, encrypted storage and route backends. Google TV belongs in this same
Android TV OS implementation family; it does not call for another core language
or media backend. Google's documentation addresses app distribution to both
experiences. [Google TV and Android TV][google-tv]

The work is nevertheless more than enabling a launcher flag. The immediate
unknowns are complete remote operation, the target device's native ABI, two
simultaneous decoder/key sessions, and ABEMA's browser/helper/account path on
the TV's actual WebView. Phone success establishes none of these TV results.
Direct APK installation is a credible development and personal-distribution
path; Play distribution is optional for that use.

**Implemented** below means present in Tachiai's Android source. **Documented**
means an upstream interface exists. **Inferred** means reuse appears plausible.
**Unknown** means the required TV behavior has not been established. These labels
do not claim provider approval or device validation.

## Interfaces, libraries and reuse

| Area | Existing implementation or TV interface | Assessment and remaining work |
| --- | --- | --- |
| Application and launcher | Android activities, manifest and Gradle project | **Implemented groundwork:** optional `android.software.leanback`, touchscreen-not-required and `LEANBACK_LAUNCHER` already exist. **Unknown:** end-to-end usable TV flow. TV launcher metadata is required even for a conveniently launchable sideload. [TV app setup][setup] |
| Portable rules | Existing `tachiai-core`, UniFFI Kotlin binding and Android `:core` module | **Implemented reuse:** the same timing plans and tests can serve TV. The core does not supply navigation, players, routing, accounts or hardware support. See [shared Rust core](shared-rust-core.md). |
| Settings, source picker and catalogue UI | Existing mobile Compose Material 3; Compose for TV provides `androidx.tv:tv-material` | **Documented alternative; adaptation required.** Reuse models/actions, but use TV components and a deliberate focus design. Mobile and TV Material themes/components are distinct; mixing them casually can produce inconsistent behavior. [Compose for TV][compose] |
| Native viewer | Existing Media3 HLS/DASH players, Android view surfaces, pair controller, mix and timing adapters | **Inferred high reuse.** Retain session ownership and Rust planning, add TV input/focus integration. Media3 documents forwarding D-pad events to `PlayerView`, with special handling when hosted through Compose. Tachiai's custom two-feed controls need their own mapping. [Media3 TV input][media3-tv] |
| Provider APIs and native HTTP | Kotlin adapters and OkHttp with explicit session-owned transports | **Inferred high reuse.** TV does not impose the browser sandbox that obstructs a web port. Provider device identity, account entitlement, exact source and route still require the same validation. |
| ABEMA login and runtime helper | Android WebView, request observation, verified runtime bundles, isolated services and opaque CDM handoff | **Inferred reuse; critical unknown.** Android TV permits WebView login surfaces, but supplies no assumed general-purpose browser. That is not evidence that ABEMA's pages, email input, helper runtime and capture flow work on a particular TV. [TV layout/browser constraints][layouts] |
| Browser routing | AndroidX WebKit `ProxyController`, feature-gated WebView functionality | **Documented interfaces; device gate.** Query runtime feature support and preserve refusal when the required proxy feature is absent. A TV's OS version alone does not prove its WebView supports the required feature. Proxy override applies to application WebViews rather than an arbitrary per-tab proxy. [ProxyController][proxy], [WebViewFeature][webview-features] |
| Imported routes | Existing HTTP CONNECT, SOCKS5 and userspace WireGuard/OpenVPN/OpenConnect routebridge | **Inferred reuse on packaged ABIs.** Native dependencies, resolver/IPv6 behavior, loopback proxy access, cancellation and confirmed cleanup must pass on TV. Existing process-wide ABEMA proxy constraints remain; a TV port does not create independent browser routing. See [route implementation](connection-import.md). |
| Secret storage and isolation | Android Keystore, encrypted no-backup files, named processes and private bound services | **Inferred reuse.** Preserve account/instance binding and grant isolation; test keystore operations and process death on the chosen TV. Do not copy the phone's cookies or grants as a shortcut. |
| Lifecycle and system controls | Android lifecycle/audio focus; MediaSession APIs for remote/system transport | **Documented building blocks.** Map the presentation as one logical media session, with an explicit policy for one available feed, two feeds, seeking and system Play/Pause. Do not let two independent sessions fight over remote commands. [TV media session][media-session] |

Unlike a new iOS or browser implementation, this target should not need a new
provider media abstraction merely to invoke the platform player. A sensible
initial shape is one Android application with a TV-specific presentation layer
and shared Android services. This is a design recommendation, not a claim that
all existing phone screens should be reused unchanged.

## TV interaction and accessibility

The D-pad, Select and Back must reach every action without a touch screen or
mouse. Audit focus order and restoration across lists, tabs, dialogs and
asynchronous changes, rather than trusting automatic nearest-neighbor focus
selection. [TV navigation][navigation]

For Tachiai specifically, define remote operations for selecting A/B, swapping
the floating feed, moving/resizing it, mix/fade, timing adjustment, quality and
per-feed recovery. Drag handles need a remote-operable move action; floating
video cannot require dragging. Back should dismiss a panel before leaving the
viewer, with focus restored to the opener. Controls must not disappear while the
user is operating them. These are proposed acceptance criteria, not measured
defects on a TV.

TV layouts need readable text at viewing distance, landscape composition and
safe control placement near display edges. Phone proportions and tiny icon
targets are unsuitable defaults. [TV layout guidance][layouts]
TalkBack, meaningful action labels, captions and text scaling are separate
checks. Slider operation is especially relevant to timing and audio mixing;
Google documents TV slider accessibility actions rather than relying on touch
gestures. [TV accessibility][accessibility], [TV TalkBack and sliders][talkback]

Email login and profile imports are likely the least comfortable remote tasks.
First test the existing provider-controlled pages and system keyboard. The
existing Twitch device-authorization display is a useful reuse candidate.
An ABEMA companion-device sign-in flow would need its own verified provider
mechanism; it cannot be inferred from Twitch or implemented by copying sessions
between devices.

## Media, hardware and native packaging gates

Two `ExoPlayer` instances are possible API objects; that does not guarantee two
simultaneous decodes at a requested quality. Query codec profiles, resolution/
frame-rate support and maximum instances, then run the actual pair. Android's
resource-manager configuration explicitly distinguishes secure/non-secure
coexistence and multiple secure codecs. A single-feed 4K advertisement is not
evidence of two protected 1080p feeds or two 4K feeds. [OEM codec resources][codec]

Media3 uses platform decoders and DRM implementations. Clear Key support is
documented for DASH, but the exact ABEMA exchange, key-session lifetime and
content remain provider/device checks. HDMI output restrictions, codec choice,
surface stacking and an external display chain must be observed; the emulator
cannot establish them. [Media3 devices][devices], [Media3 DRM][drm]

There is a concrete installation gap before those tests: Tachiai currently
packages only `arm64-v8a` and `x86_64`, with minimum API 26. Both the Rust module
and Go/C/C++ routebridge would need additional outputs to support a 32-bit ARM
device. A 64-bit processor does not by itself establish compatible Android
userspace. Check the exact device's supported ABIs before scheduling a trial.
Google continues supporting 32-bit TV devices while adding 64-bit requirements
for Play submissions; supporting more existing TVs may therefore require
`armeabi-v7a`, or an explicit narrower device list. The documented TV Play change
took effect August 1, 2026; 64-bit native builds targeting API 35+ also need
16 KiB page compatibility. [TV native-architecture transition][abis]

Long-running tests should record both audio tracks acoustically, dropped frames,
buffering, memory and route throughput. In particular, the existing phone's
network-dependent Twitch failures should not be relabeled as TV decoder limits.
Test HDMI audio, mute/fade behavior and interruption/resume; encoded passthrough
or an external receiver must not defeat the application's two-feed mix policy.

## Distribution, including outside official stores

| Channel | Install and update path | Operational implications for Tachiai |
| --- | --- | --- |
| Developer/personal ADB | Signed APK installed from an authorized workstation; USB or supported network debugging | Best first TV trial. Select and verify the exact device; preserve app data with in-place updates. Wireless pairing is documented for TV on Android 13+, while older-device network debugging depends on its supported setup. This is not an unattended consumer updater. [ADB deployment][adb] |
| Direct download, local file or private server | User transfers/opens the signed APK through an installer-capable app and permits that installation source | No Play listing is needed. On Android 8+ the unknown-app permission is per installing source; TV settings/file-manager availability varies. Hosting a download does not provide automatic updates, easy discovery or a usable TV launcher. [Alternative distribution][alternative] |
| Alternative store | Store delivers the APK and handles its own discoverability/update workflow | Android permits other marketplaces. Assess TV support and its signing/build policies before choosing one. It does not remove provider terms, licensing, native-source obligations or device limitations. [Alternative distribution][alternative] |
| Google Play TV | TV opt-in/review, store assets and an appropriate release artifact | Provides consumer discovery/update infrastructure, but requires TV quality and release compliance. One package/listing can cover phone and TV, with a dedicated TV track when appropriate. [Play TV distribution][play-tv] |

An APK distributed outside Play still needs a production signing plan, a stable
package identity and compatible version codes for updates. Keep custody of the
app-signing key; an upload key is not necessarily the certificate on Play's
installed APK. If interchangeable Play/direct updates are a goal, choose a
compatible signing strategy before enrolling rather than discover a signature
mismatch later. Ordinary users should not have to uninstall and lose settings
to change channels. Current local debug/inspection signing is development
infrastructure, not the proposed public release identity. [App signing][signing]

Direct release hosting should publish the correct signed APK, version/ABI
requirements, integrity information, required license notices and matching
native-source companion. Updates initially may be explicit user installs; an
in-app update facility is separate work and must use the system's installation
and user-consent rules. Never distribute the local inspection exception as the
normal consumer artifact. See [Android build and inspection policy](android-development.md).

### Developer verification: dated scope matters

Google's current overview says protections began **September 30, 2026** for
participating-store installs on certified Android 7+ devices in **Brazil,
Indonesia, Singapore and Thailand**, with wider global expansion planned for
2027. This is developer identity/package registration, not a requirement that
every app be listed in Google Play. [Verification overview][verification]

Its current FAQ adds important boundaries: the initial deadline does not yet
cover direct sideloads or nonparticipating stores; outside-Play enforcement in
those regions initially covers **mobile/tablet**, while Play apps across form
factors must be registered. **ADB installation remains available without
registration.** Limited distribution offers up to 20 selected devices without
ID verification/fee, and a user advanced flow exists for unverified installs.
These paths are distinct from ordinary direct distribution; confirm eligibility
and the chosen TV's actual behavior. Do not describe September's rollout as an
Android TV sideload ban, or assume these dated exclusions will remain unchanged
during global expansion. [Verification FAQ][verification-faq]

## Smallest useful validation sequence

1. **Inventory the actual TV:** model, firmware/API, supported ABIs, RAM,
   WebView provider/version, required WebKit features, codec/DRM capability and
   installation/debugging options. Decide 64-bit-only scope versus an ARM32
   build before promising support.
2. **Provider-free remote trial:** use a TV emulator for focus/navigation and a
   real remote on hardware for the picker, settings, all panels, list ordering,
   recovery, Back and TalkBack. Keep the persistent phone/emulator data intact.
3. **Controlled media and routes:** play two unprotected samples through the
   intended route backends; check two decoders, audio mixing, default seek,
   pause/recovery, network loss and confirmed cleanup without direct fallback.
4. **Authorized provider trial:** fresh TV-local account setup, ABEMA selected
   live/replay plus Twitch live/replay, then duplicate ABEMA where authorized.
   Record region, account, route, content, actual formats and acoustic results;
   check remembered login and renewal after restart without exporting secrets.
5. **Distribution rehearsal:** sideload a normal signed release, launch from
   the TV home screen, then update it with accounts/routes retained. Separately
   evaluate Play only when the chosen public distribution scope calls for it.

The first useful milestone is a named device with a complete remote-operated
two-feed session and an in-place APK update. A manifest flag, successful Kotlin
compile, API capability query or TV emulator playback alone is insufficient.

[google-tv]: https://support.google.com/androidtv/answer/10267283
[setup]: https://developer.android.com/training/tv/get-started/create
[compose]: https://developer.android.com/training/tv/playback/compose
[media3-tv]: https://developer.android.com/media/media3/ui/androidtv
[layouts]: https://developer.android.com/training/tv/playback/compose/layouts
[navigation]: https://developer.android.com/training/tv/get-started/navigation
[accessibility]: https://developer.android.com/training/tv/accessibility
[talkback]: https://developer.android.com/training/tv/accessibility/talkback-support
[proxy]: https://developer.android.com/reference/androidx/webkit/ProxyController
[webview-features]: https://developer.android.com/reference/androidx/webkit/WebViewFeature
[media-session]: https://developer.android.com/training/tv/playback/media-session
[codec]: https://source.android.com/docs/core/media/oem
[devices]: https://developer.android.com/media/media3/exoplayer/supported-devices
[drm]: https://developer.android.com/media/media3/exoplayer/drm
[abis]: https://android-developers.googleblog.com/2025/08/64-bit-app-compatibility-for-google-tv-android-tv.html
[adb]: https://developer.android.com/tools/adb
[alternative]: https://developer.android.com/distribute/marketing-tools/alternative-distribution
[play-tv]: https://developer.android.com/training/tv/publishing/distribute
[signing]: https://developer.android.com/studio/publish/app-signing
[verification]: https://developer.android.com/developer-verification
[verification-faq]: https://developer.android.com/developer-verification/guides/faq
