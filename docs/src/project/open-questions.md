# Open questions

## Product

- Which primary/secondary layouts are necessary on a phone, tablet, and
  television?
- Which swap gesture or control is fastest while remaining clear for touch and
  D-pad users?
- Should saved presentations store a preferred reference pane and initial
  requested offset?
- What accessible descriptive label should accompany the **Matta!** control?
- Can both provider players expose reliable independent volume levels, or will
  some combinations require a reduced mute/unmute mix?
- How should initial mute be enforced when a provider creates or replaces its
  media element after main-frame load? The current command is best effort only.

## ABEMA

- What stable public page identifies the currently active Grand Sumo channel or
  program?
- Can a provider-supported, non-circumventing region and network context first
  pass the same episode in Chrome and then distinguish default WebView, mobile
  Chrome-like, and desktop Chrome-like identity behavior without changing any
  other condition? VPN observations remain separate unsupported diagnostics.
- Does that page play in Android WebView on the target phone and Shield in the
  user's actual region?
- Is the active player a top-level media element, an iframe, or a closed shadow
  tree?
- Which DOM container can be focused without removing required controls or ads?
- Does live pause/resume retain delayed media, for how long, and across program
  or advertisement transitions?
- Can the web account flow use ABEMA's ID and one-time password on Android TV?
- Do free channels and replay content use different DRM or player paths?
- Does the original ABEMA video remain playable after optional onboarding is
  declined across restarts, live programs, advertisements, and other WebView
  versions? The one successful answer remains an unsupported Android desktop-
  identity experiment.
- Is any public provider partnership or supported integration available beyond
  ABEMA's website and native applications? No public embed or playback SDK was
  found in the official material reviewed for the spike.

## Twitch

- Why did the official embed report a live channel but never begin visible
  playback after browser-surface gestures on the Pixel 6?
- Does top-level mobile Twitch playback and exact-route focus mode behave
  consistently on other Android WebView versions and Android TV?
- Do the observed mobile-player class fragments remain stable, and does focus
  survive prerolls, midrolls, quality changes, and player replacement?
- Should Tachiai retain native generic media controls, expose Twitch's original
  controls, or provide both, and can every choice remain D-pad reachable?
- Does the live embed retain a paused position or resume at the live edge?
- How do Twitch prerolls and midrolls affect the user's intended alignment?
- Is video-only embed sufficient, or does the first useful version need chat?

## Alignment

- What is the typical ABEMA-versus-`midnightsumo` latency difference?
- Does that difference drift materially during one match day?
- Which feed normally needs delaying?
- What adjustment granularity is useful and reliable?
- How should Tachiai communicate that an accumulated pause is not a measured
  offset?
- What user interaction establishes a common event anchor for replay/replay or
  live/replay viewing?
- When should an advertisement or rebuffer automatically invalidate alignment
  confidence?

## Android

- Minimum Android API level and target API level.
- Whether one APK should contain both mobile and TV launchers.
- Whether target devices can sustain two protected 720p or 1080p streams
  without thermal or memory problems. One Pixel 6 decoded ABEMA and Twitch and
  mixed both audio tracks briefly in a single WebContents, but sustained tests
  remain open.
- Whether the single-WebContents result survives ads, player replacement,
  background/foreground transitions, hardware media buttons, process restart,
  other WebView versions, and Android TV.
- Whether ABEMA permits Tachiai to add Twitch's official cross-origin child
  frame and whether Twitch accepts ABEMA ancestor names for this use over time.
  A successful render is technical evidence, not provider approval.
- How to expose independent Twitch controls from the native shell without a
  broad bridge or violating the cross-origin boundary. The user could operate
  Twitch's own frame controls, while ABEMA required its own visible unmute
  overlay in the successful mixed-audio run.
- How remote focus reaches provider controls when full-site mode is necessary.
- Whether cookies, popup windows, and protected-media permissions behave
  consistently across phone and Shield WebView versions.

## Other platforms

- Browser extension, installed-browser controller, or embedded wrapper for
  Windows and macOS.
- Whether ABEMA's content-security policy permits a Twitch iframe inserted by
  an extension.
- Which Linux distribution and Google Chrome version constitute the first
  experimental target.
- Whether ABEMA plays in WKWebView and whether a Safari extension provides a
  better iPhone experience.
- How iOS builds would be shared if TestFlight review or 90-day expiration is
  unacceptable.

## Repository and delivery

- Whether Tachiai should be added to Tome's tracked repository registry.
- Whether the first manual Renovate run successfully updates all intended
  dependency sources and regenerates `mise.lock` in its restricted runner.
- Whether Mergify's injected branch protection and the aggregate `all` check
  behave as expected on the first explicitly enqueued pull request.
- Shared APK update and checksum distribution mechanism.
- Whether reproducible Android builds are practical with the selected WebView
  and adapter asset approach.
