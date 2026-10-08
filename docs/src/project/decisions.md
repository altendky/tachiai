# Decisions

## Settled

- The project name and application label are **Tachiai**.
- The name refers to the mutually timed start of a sumo bout.
- The first useful pairing is ABEMA Grand Sumo video with the `midnightsumo`
  Twitch channel.
- The product model is generic multi-stream presentation, not a hard-coded
  ABEMA/Twitch or sumo client.
- Both live and replay sources are in scope.
- Product terminology is **route / provider / stream / feed**: a route is the
  network path (saved configuration is not proof of an active connection), a
  provider supplies selectable live/recorded streams, and a feed is an active
  instance of a stream. Duplicate stream selections create independent feeds.
  Routes and Providers have separate setup screens; provider default routes
  apply to all their streams. Per-feed overrides are a later optional control.
- User-adjustable relative timing is a central feature.
- The first useful viewing experience has two simultaneous feeds with an
  overall volume, relative mix/fade with fine-adjust arrows, and per-feed mute;
  separate per-feed volume sliders are not primary controls.
- A source picker selects both feeds and permits duplicate selections. Portrait
  stacks them without forced equal heights; landscape has a full-screen primary
  and movable floating secondary, tapped to swap roles.
- Joint Play/Pause is primary. Per-feed transport can be added later outside
  the initial primary control space.
- One feed is normally primary and the other secondary, with an easy swap that
  preserves both provider sessions and playback surfaces.
- Manual alignment controls are required in that two-feed experience.
- Android phone/tablet and Android TV are the first implementation family.
- iPhone/iPad, Windows, macOS, and Linux are desired later targets.
- Private sharing and sideloading are intended; Play Store publication and
  marketing are not current goals.
- Provider-controlled pages or explicitly scoped device-authorization flows
  handle login. Tachiai does not collect passwords; saved grants follow the
  documented [security boundaries](security-and-privacy.md).
- The original playback approach kept supported provider web players intact;
  those comparisons remain available.
- Focus mode should reshape the page around the original player rather than
  extract or copy decoded video.
- Launching ABEMA as a separate native app or system picture-in-picture window
  is a diagnostic fallback, not the two-feed Tachiai architecture. Tachiai must
  own both presentation surfaces well enough to size, swap, align, and mix
  them.
- Recorded web limitations justified user-approved, bounded debug native
  experiments. Their existence does not settle provider support, release
  architecture or permission to broaden authentication/licensing protocols.
- Playback and timing capabilities remain optional and source-dependent.
- Alignment claims distinguish requested delay from measured media-clock
  synchronization.
- The Android application ID is `net.fstab.tachiai`.
- Source code and documentation are dual-licensed under MIT or Apache-2.0.

## Provisional

- Android uses Kotlin, Jetpack Compose, and one Gradle application module.
- Android phone and TV ship from one project, with adaptive layouts and
  television-specific launcher and D-pad behavior; TV playback is not verified.
- The current debug [Prototype flow](prototype-ux.md) uses native players and
  four fixed live/replay source entries with independent slot ownership. Cached
  ABEMA preparation loads four verified runtime-only public bundles, fresh guest
  and source setup, unchanged provider selectors/helper and a fresh native CDM
  with one initial exchange, without a full provider page. This remains an
  unsupported experiment with conservative free-replay and network gates.
- Exact mix curve/defaults, floating size and control-hide timing are
  provisional. Native timing acts within advertised capabilities, not an
  unlimited live buffer or an automatic content-matching guarantee.
- Android's historical mixed-audio browser candidate uses one
  WebView/WebContents with ABEMA top-level and Twitch's official player in a
  cross-origin child frame.
  This produced two advancing videos and user-confirmed mixed audio in one
  bounded Pixel 6 run. Provider policy, independent control, sustained use,
  other WebView versions, and Android TV remain unverified, so the topology is
  not settled.
- Historical browser adapters use packaged JavaScript and CSS on exact
  allowlisted playback pages.
- Browser Twitch commands remain bound to the channel selected for their pane even when
  Twitch uses an explicitly recognized canonical host. ABEMA currently allows
  navigation from a selected title or listing to another exact-origin playback
  route, where commands reacquire that route's visible original media element.
- Twitch's top-level mobile channel page is a proven Android diagnostic
  fallback. Full-size playback of the original Twitch player was observed in
  Tachiai on one Pixel 6 after an exact-route, provider-specific focus
  correction. Native play, pause, mute, sound, and volume-property commands
  controlled the original media element, and two same-channel players decoded
  concurrently in a keyed 60/40 primary/secondary layout. A later mixed-provider
  run confirmed simultaneous Twitch and ABEMA motion and audible output from
  either pane individually. It also showed that the two Chromium audio-focus
  delegates pause one another when the other pane starts audible playback, so
  a separate-browser-surface choice does not satisfy mixed audio on the tested
  Pixel 6. The same-WebContents probe avoided that focus conflict, so the
  focused top-level Twitch page remains a historical diagnostic, not the native
  Prototype topology. Selector durability, sustained advertisement behavior,
  other WebView versions, and Android TV remain unverified.
- ABEMA's ordinary first-party web page remains the only discovered in-process
  provider-supported candidate. No public official ABEMA embed or playback SDK
  was found. A Windows Chrome-like identity produced bounded replay video and
  an Android audio track in Tachiai on one Pixel 6. The historical two-feed web
  comparison selects that identity as an explicitly unsupported experiment,
  not as a settled product solution. Default and mobile Chrome-like identities
  exposed no playable video in the same run.
- Provider adapter assets may become the first shared cross-platform package.
- The earlier browser UI supports two keyed panes while the model represents a
  list. Its diagnostic layout uses a 60/40 landscape split because a 2:1 split was
  too narrow for the secondary Twitch page on the tested Pixel 6. Compact
  two-pane rendering is temporarily permitted in debug builds below provider
  minimum dimensions to measure concurrency; release builds retain the size
  gate, and this is not a final layout decision.
- Android currently uses minimum API 26, compile API 37.2, and target API 37,
  pending evidence from the actual target devices.
- **Matta!** may label a realignment interaction, paired with descriptive
  accessibility text.

## Deferred

- A shared Rust core or UniFFI layer.
- Kotlin Multiplatform.
- Electron, CEF, Tauri, or another desktop wrapper choice.
- Audio fingerprinting or automatic content matching.
- Project accounts, cloud synchronization, and a project-operated backend.
- Chat and broader Twitch account features.
- Recording, downloading, or retransmitting provider content.
