# Decisions

## Settled

- The project name and application label are **Tachiai**.
- The name refers to the mutually timed start of a sumo bout.
- The first useful pairing is ABEMA Grand Sumo video with the `midnightsumo`
  Twitch channel.
- The product model is generic multi-stream presentation, not a hard-coded
  ABEMA/Twitch or sumo client.
- Both live and replay sources are in scope.
- User-adjustable relative timing is a central feature.
- The first useful viewing experience has two simultaneous feeds with mixed,
  independently adjustable audio.
- One feed is normally primary and the other secondary, with an easy swap that
  preserves both provider sessions and playback surfaces.
- Manual alignment controls are required in that two-feed experience.
- Android phone/tablet and Android TV are the first implementation family.
- iPhone/iPad, Windows, macOS, and Linux are desired later targets.
- Private sharing and sideloading are intended; Play Store publication and
  marketing are not current goals.
- Provider-controlled pages handle login. Tachiai does not collect passwords.
- The first playback approach keeps supported provider web players intact.
- Focus mode should reshape the page around the original player rather than
  extract or copy decoded video.
- Launching ABEMA as a separate native app or system picture-in-picture window
  is a diagnostic fallback, not the two-feed Tachiai architecture. Tachiai must
  own both presentation surfaces well enough to size, swap, align, and mix
  them.
- Native/private stream extraction is deferred until a specific requirement
  cannot be met by intact web playback.
- Alignment claims distinguish requested delay from measured media-clock
  synchronization.
- The Android application ID is `net.fstab.tachiai`.
- Source code and documentation are dual-licensed under MIT or Apache-2.0.

## Provisional

- Android uses Kotlin, Jetpack Compose, and one Gradle application module.
- Android phone and TV ship from one project, with adaptive layouts and
  television-specific launcher and D-pad behavior.
- Android's leading mixed-audio candidate uses one WebView/WebContents with
  ABEMA top-level and Twitch's official player in a cross-origin child frame.
  This produced two advancing videos and user-confirmed mixed audio in one
  bounded Pixel 6 run. Provider policy, independent control, sustained use,
  other WebView versions, and Android TV remain unverified, so the topology is
  not settled.
- Provider adapters use packaged JavaScript and CSS on exact allowlisted
  playback pages.
- Twitch commands remain bound to the channel selected for their pane even when
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
  focused top-level Twitch page remains diagnostic rather than the leading
  product topology. Selector durability, sustained advertisement behavior,
  other WebView versions, and Android TV remain unverified.
- ABEMA's ordinary first-party web page remains the only discovered in-process
  provider-supported candidate. No public official ABEMA embed or playback SDK
  was found. A Windows Chrome-like identity produced bounded replay video and
  an Android audio track in Tachiai on one Pixel 6. The current two-feed spike
  selects that identity as an explicitly unsupported experiment, not as a
  settled product solution. Default and mobile Chrome-like identities exposed
  no playable video in the same run.
- Provider adapter assets may become the first shared cross-platform package.
- The first UI supports two keyed panes while the model represents a list. Its
  current debug candidate uses a 60/40 landscape split because a 2:1 split was
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
