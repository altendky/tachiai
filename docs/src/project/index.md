# Project

Tachiai is a personal multi-stream presentation application. It places two or
more independently delivered video streams into one viewing layout and lets
the viewer adjust their relative timing.

The motivating case is watching ABEMA's Grand Sumo video while listening to or
watching the `midnightsumo` Twitch commentary. The architecture must not encode
that pairing as the product model: other live/live, live/replay, and
replay/replay combinations should fit the same stream-pane and alignment
concepts.

## Current status

As of 2026-10-08, the Android debug application has a source-picker and native
two-feed viewer alongside the retained experiment home. The default Prototype
flow uses bounded native Twitch and cached ABEMA preparation; the older
full-page ABEMA startup remains a comparison, not a required step in that flow.
The [product prototype](prototype-ux.md),
[native evidence](native-access-experiments.md) and
[preliminary viewer](preliminary-native-viewer.md) record implementation and
device conditions. Native video/audio and timing primitives have passed bounded
Pixel 6 tests, but this is not a supported provider integration or a release
readiness claim. The latest cached ABEMA runs rendered duplicate live and replay
sources without a full provider page; those runs have no fresh acoustic
confirmation.

### Historical browser milestones

The September 28 scaffold and browser investigations remain useful comparisons.
One Pixel 6 experiment established that
Twitch's original top-level mobile web player can play and fill Tachiai's
landscape browser surface after a provider-specific focus correction. A
follow-up briefly rendered and independently controlled two same-channel Twitch
players and swapped their keyed primary/secondary surfaces. The native ABEMA
app played a current free replay, but its separate PiP surface and Android
audio-focus behavior do not satisfy Tachiai's embedded two-feed model. An
unsupported Windows Chrome-like identity later played that replay inside
Tachiai and created an Android audio track. Two separate WebViews subsequently
rendered both providers concurrently, but their independent Chromium audio-focus
delegates paused one another. A debug-only alternative kept ABEMA top-level and
inserted Twitch's official player as a child frame in the same WebView. Both
videos advanced and the user heard both audio tracks concurrently in a bounded
Pixel 6 test. This settles the local audio-focus feasibility question, not the
product integration: sustained decoding, supported ABEMA operation, login
persistence, timing adjustment, and the durability and policy acceptability of
the composition across devices and player transitions remain unverified.

The first implementation target is one Android application supporting phones,
tablets, and Android TV. The first device investigation should include the
available NVIDIA Shield. iPhone/iPad and desktop platforms are desired after
the Android feasibility spike establishes which logic can actually be shared.

## Product direction

The agreed preliminary interaction model is:

- Select two sources, including two copies of the same source, then explicitly
  open the viewer. The current catalogue has fixed live/replay entries for
  ABEMA and Twitch, with Izgonnabemei, Chillhop Radio and Virtual Japan as Twitch
  live choices. Each pane shows preparation or a safe error while available
  feeds play. Sources returns to the picker; richer channel selection remains
  future work.
- Portrait stacks the feeds without forcing equal heights. Landscape uses a
  full-screen primary and movable floating secondary; tapping the secondary
  swaps roles without replacing either session.
- Joint Play/Pause is primary; individual transport controls are not yet in
  the primary control space.
- Overall volume and a relative mix/fade control, with fine-adjust arrows,
  complement per-feed mute. Separate per-feed volume sliders are not the UX.
- Relative timing names the feeds and uses their optional advance/delay
  capabilities. A requested shift is not measured common-event synchronization.

The [product-flow prototype](prototype-ux.md) records the current implementation.
Exact fade curves, defaults, floating size and control-hide timing remain
provisional. Unsupported native playback adapters remain debug-only.
The cached prototype's [media-origin approvals](media-origin-approvals.md)
record user-approved CDN boundaries and the review process for new origins.

[Per-source network routing research](source-network-routing.md) compares future
proxy/VPN options and setup profiles. Its original assessment is historical;
the subsequent debug implementation adds provider-owned HTTP CONNECT and
userspace WireGuard routes, with verification limits recorded separately.
Different simultaneous routes for duplicate ABEMA feeds remain unimplemented.
The debug-only
[connection importer](connection-import.md) adds guided Proton export and
Share/Open/file-picker handoffs into encrypted saved profiles, without activating
any route or changing playback. Separate Routes and Providers screens manage
profiles and assign provider defaults for all their streams/feeds; ambiguous
earlier per-stream route settings require explicit review. Obsolete four-stream
settings require an explicit stream-settings reset rather than migration; saved
routes and provider configuration are retained. The cached native viewer connects
the selected route before provider preparation; unsupported or failed routes
never silently fall back. System network retains existing playback.

The historical web-first approach keeps provider-controlled web players intact
inside embedded browser content and remains available for comparison:

- ABEMA remains the top-level site in an Android WebView.
- That mixed-audio candidate places Twitch's supported player embed in
  a child frame of that same WebView so Chromium treats both media elements as
  one media-session/audio-focus group. The prior separate-WebView layout
  remains useful diagnostic evidence. Neither is the default native Prototype
  flow.
- Provider login stays inside provider-controlled pages and browser storage.
- Focus mode reshapes a page around its original player instead of copying
  decoded video or initially reproducing private playback APIs.
- Alignment begins as explicit user control and degrades according to the
  controls each source exposes.

Recorded browser limitations led to explicitly approved, bounded native
experiments. The cached ABEMA path downloads and verifies public runtime bundles
instead of packaging them, creates a fresh anonymous session, delegates the
unchanged opaque helper exchange and uses a fresh native CDM. It does not
establish supported authentication, complete provider advertisement behavior,
renewal or unrestricted content access. See the
[security boundaries](security-and-privacy.md) and
[open questions](open-questions.md) before expanding these experiments.

## Repository direction

The [persistent Android development device](android-development.md) defines
reproducible local emulator setup and safe lifecycle/install commands. Its
private app state remains local, outside the repository; emulator setup is not
evidence of provider playback or physical-device parity.

The intended layout follows the documentation-first and platform-shell
conventions used by sibling projects such as Dose Goose:

```text
apps/
  android/
  ios/                    # reserved until an iOS spike begins
packages/
  web-adapters/           # possible shared provider-page code
docs/src/project/
```

This layout is provisional. In particular, a shared Rust core, Kotlin
Multiplatform module, TypeScript package, or desktop wrapper should not be
created until the spike identifies meaningful portable logic.
