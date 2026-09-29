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

As of 2026-09-28, this repository contains planning documentation and an
initial Android feasibility scaffold. One Pixel 6 experiment established that
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

The initial approach keeps provider-controlled web players intact inside
embedded browser content:

- ABEMA remains the top-level site in an Android WebView.
- The current mixed-audio candidate places Twitch's supported player embed in
  a child frame of that same WebView so Chromium treats both media elements as
  one media-session/audio-focus group. The prior separate-WebView layout
  remains useful diagnostic evidence but is not the current product candidate.
- Provider login stays inside provider-controlled pages and browser storage.
- Focus mode reshapes a page around its original player instead of copying
  decoded video or initially reproducing private playback APIs.
- Alignment begins as explicit user control and degrades according to the
  controls each source exposes.

Native manifest extraction is not the first implementation path. It may be
reconsidered only after a recorded limitation of intact web playback makes it
necessary.

## Repository direction

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
