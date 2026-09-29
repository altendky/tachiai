# Android playback spike

This page records the assumptions, observation format, and results for the
first Android experiment. Playback claims apply only to the recorded device,
provider page, channel state, and test conditions.

## Scaffolded experiment

The application currently presents two visible panes:

- a top-level ABEMA WebView starting at the known-current public replay
  `https://abema.tv/video/episode/394-72_s10_p8529` with the experimental
  Windows Chrome-like identity that produced playback on the tested device;
- Twitch's top-level mobile page for the `midnightsumo` channel, which is the
  intact provider player that produced playback on the tested device.

The ABEMA replay is a bounded feasibility resource, not a stable promise of
the active Grand Sumo live program. The viewer may navigate within the exact
ABEMA origins to another available program. Commands are injected only on
ABEMA `/video/` and `/channels/` routes, never on account or login routes. The
desktop identity is undocumented by ABEMA for Android and remains a provisional
experiment rather than a supported compatibility claim.

The official Twitch embed diagnostic is served by `WebViewAssetLoader` at
`appassets.androidplatform.net`, passes that host as the embed's `parent`,
disables autoplay, and enables third-party cookies only for that browser
surface. It reported a live channel but did not visibly start on the tested
Pixel 6. The normal two-feed spike therefore uses Twitch's original top-level
mobile player without extracting its stream. A minimal controlled public HTTPS
page remains a future embed comparison if the packaged origin is the cause.

Debug builds accept a validated Twitch channel override through the
`net.fstab.tachiai.extra.TWITCH_CHANNEL` activity string extra. This exists to
repeat embed tests with a channel confirmed live at the time of observation;
it does not change the default `midnightsumo` pairing and is ignored by release
builds.

Debug builds also accept the boolean
`net.fstab.tachiai.extra.TWITCH_ONLY` activity extra. It renders the selected
Twitch resource as a single generic presentation pane so provider playback can
be isolated from dual-pane sizing and system-bar overlap. All layouts apply
safe drawing insets. Debug builds permit compact two-pane rendering even when
a provider's documented minimum size is not met; this is a test affordance,
not a proposed compliant product layout. Release builds retain the size guard.
The single-pane diagnostic omits Tachiai's title, card, and margins, but keeps
a compact row of generic media controls above the provider browser.
Tachiai keeps the screen awake only while its activity is visible so a playback
experiment is not interrupted by the device timeout.

An ABEMA-only diagnostic uses the boolean
`net.fstab.tachiai.extra.ABEMA_ONLY` and accepts an allowlisted public page in
`net.fstab.tachiai.extra.ABEMA_URL`. The optional debug-only
`net.fstab.tachiai.extra.ABEMA_USER_AGENT` value can be `mobile-chrome` or
`desktop-chrome`; omitting it preserves WebView's identity. The overrides are
an unsupported experiment for distinguishing page routing from WebView
identity, not a production compatibility mechanism. They are accepted only on
ABEMA `/video/` and `/channels/` routes, derive the installed Chromium version
from WebView's real identity, and fail closed to the default identity when that
is not possible. Browser failures expose only a generic network error code,
HTTP status, or TLS-resource category and never record a requested URL or
response content.

The additional debug-only boolean `net.fstab.tachiai.extra.TWITCH_FULL_SITE`
selects Twitch's own top-level mobile HTTPS channel page for comparison with
the official embed. On an exact validated channel route, the Twitch adapter
applies an experimental focus correction to the original provider player. The
correction does not replace the media element: it expands Twitch's aspect-ratio,
player, video, and advertisement-wrapper elements and reapplies those styles if
the page replaces the player. It restores only the CSS properties it still owns
when a provider modal appears or navigation leaves the selected channel route,
then attempts to reapply them if the route becomes eligible again. The class
fragments used by this correction are provisional observations, not a stable
Twitch API. The forced debug focus surface does not yet provide the product's
required user-facing exit control.

When `TWITCH_ONLY` is set, the additional debug-only string extra
`net.fstab.tachiai.extra.TWITCH_SECONDARY_CHANNEL` creates a second independent
Twitch pane. It uses a 60/40 primary/secondary split in landscape and exposes a
generic Swap action.
It exists only to test concurrent playback, independent control, and session-
preserving pane exchange; it is not a proposed final phone layout.

## Security assumptions to verify

- Protected-media permission is granted only when it is the sole requested
  resource and the requesting origin is exactly an allowlisted provider
  origin. The initial allowlist contains ABEMA's page origins and
  `player.twitch.tv`; real requests may prove this list incomplete.
- JavaScript and DOM storage are enabled because both provider pages require
  them. File access, content access, mixed content, automatic popup creation,
  broad native JavaScript bridges, and WebView debugging are disabled.
- Provider cookies use Android WebView's persistent app-private storage.
  Persistence and login behavior have not been observed yet.
- Ordinary external main-frame navigation opens outside the provider pane.
  The experimental ABEMA desktop-identity pane permits only exact-origin
  `/video/` and `/channels/` routes; account and other non-playback routes open
  outside it. Non-interceptable disallowed commits are stopped and the
  allowlisted page is restored. A future account flow therefore needs a
  separate default-identity, unprivileged full-site policy after observation.
- Playback remains user-gesture-gated. The native controls request provider
  player operations but do not claim that the operation succeeded or that a
  live pause retained media. `initiallyMuted` is currently one best-effort
  command after main-frame completion, not a guarantee for a media element
  created or replaced later; keep device output controlled during experiments.
- On an exact validated top-level Twitch channel route, each command reacquires
  the largest connected visible original `video` element before using standard
  media properties. Commands remain bound to the channel selected for that pane,
  including a recognized mobile-to-`www` canonical-host change. Volume changes
  are clamped in 10% steps. A reported media property change is browser-state
  evidence, not proof of acoustic output.
- Twitch autoplay remains disabled. Its packaged page provides an explicit
  in-page Start control above, and never over, the official player so mobile
  Chromium can associate playback with a gesture in the browser surface.
  WebView's media-gesture requirement remains enabled for every provider.

## Platform assumptions to verify

- Minimum API 26 follows the current sibling Android baseline. Compile API 37.2
  is the published platform required by the selected stable AndroidX versions,
  and target API is 37; device coverage has not settled the final minimum.
- One APK currently declares both mobile and television launchers. D-pad
  reachability, TV launcher presentation, and Shield compatibility are
  untested.
- Landscape uses two side-by-side panes and portrait stacks them. Debug builds
  deliberately permit sizes below Twitch embed's documented 400-by-300 minimum
  so a compact phone can test concurrency; release builds retain the size gate,
  and an eventual compliant phone layout still needs an evidence-driven design.
- Two WebViews may exceed decoder, memory, thermal, or audio-focus limits on a
  target device.
- Home/background pauses and resumes each WebView through the Activity
  lifecycle. Activity recreation currently reloads the initial resources
  rather than restoring WebView navigation state; that recovery behavior must
  be observed before adding state persistence.

## Observation record

Create one record per content/device combination. Do not include cookies,
tokens, license messages, signed media URLs, or credentials.

```text
Date and local time:
Device model:
Android version:
Android System WebView package and version:
Region/network context:
Provider and public resource identity:
Live, replay, or unknown:
Anonymous or signed in:
Pane dimensions and reported video resolution:
User actions:
Observed playback/audio behavior:
Advertisement or rebuffer behavior:
Lifecycle action and recovery result:
Sanitized error text:
Result: pass / fail / inconclusive
```

The first success run must keep both streams rendering for at least thirty
minutes, select exactly one audible pane, exercise Home/resume and relaunch,
and confirm that failure or recovery in one pane does not make the other pane
unusable.

## Observed runs

### Pixel 6 debug run — 2026-09-27 19:22 EDT

- Device: Google Pixel 6 (`oriole`), Android 17, portrait, 1080 by 2400
  physical pixels and a reported 411 dp application width.
- Browser engine: `com.google.android.webview` 153.0.8010.36.
- Region and network transport: not recorded. No provider login was performed
  in Tachiai's WebViews.
- Layout: both panes were rendered in a vertical stack. Each WebView reported
  bounds of approximately 1058 by 915 physical pixels. The trailing recovery
  control was clipped, so the native control row does not yet fit this phone.
- ABEMA: the public sumo title page rendered. Tachiai reported that the
  provider player was not visible. Selecting ABEMA's in-page “watch in app”
  action redirected to the ABEMA Play Store listing; no video played in the
  WebView.
- Twitch: the official Twitch Android app was already installed, but this did
  not affect the embedded surface. The packaged `midnightsumo` embed remained
  black, and Tachiai's Play action produced no visible response. Channel live
  status was not independently established.
- Result: fail for both current in-WebView playback paths. This run does not
  establish whether either provider's official Android app can play the target
  content on this device and network.

Native-app follow-up on the same device and network:

- ABEMA 10.184.0 accepted the public title deep link but displayed its generic
  “failed to display ABEMA; please wait a while” error screen. The error did
  not identify region, account state, content entitlement, or network as its
  cause. ABEMA's help documentation says availability varies by country and
  program, so region remains a hypothesis rather than an observed cause.
- Twitch accepted the `midnightsumo` channel deep link and rendered the
  channel normally. It reported “Last live Today,” establishing that the
  channel was offline during the WebView test. The black embedded surface and
  inert Play action are therefore inconclusive for Twitch playback support;
  they must be repeated with a channel known to be live at test time.

### Pixel 6 Twitch live-channel follow-up — 2026-09-27 20:58 EDT

- Device and browser engine: the same Pixel 6, Android 17, and Android System
  WebView 153.0.8010.36 as the initial run. The phone was rotated to landscape;
  Tachiai used the full safe display area for one provider browser surface.
- Provider and content: Twitch channel `queenbee`, anonymously in Tachiai's
  app-private WebView profile. The official Twitch app and the packaged embed
  both reported the channel live during the experiment.
- Packaged official embed: the Twitch SDK emitted its online state, but neither
  Tachiai's Play action, an in-page Start action, nor direct interaction with
  the player produced visible playback. Increasing the embed to a single
  full-display pane did not change the result. This is a failure for this
  specific embed configuration, not evidence that Twitch embeds cannot work in
  Android WebView generally.
- Top-level channel page: Twitch first displayed its own “Open in App / Keep
  using web” choice. The original video subsequently played in Tachiai's
  WebView, but the unmodified mobile page rendered it at roughly 230 by 130
  physical pixels inside a wide banner.
- Playback evidence: the user observed playback, and successive device
  screenshots showed different program frames. Audio, pause retention,
  advertisement transitions, and sustained playback duration were not
  recorded.
- Sanitized layout observation: the top document contained the original
  `video` element rather than an inaccessible player iframe or closed shadow
  tree. Its surrounding aspect-ratio element was approximately 545 by 307 CSS
  pixels, while the video itself had been compressed to approximately 545 by
  58 CSS pixels. Stable-looking class fragments included
  `playerContainerMWeb`, `video-player__`, `video-ref`, and
  `stream-display-ad__wrapper_squeezeback`. No URLs, cookies, media manifests,
  tokens, or credentials were collected.
- Focus-mode correction: an initial attempt enlarged the width but retained
  the squeezed height. The refined exact-route correction expanded Twitch's
  original aspect-ratio, player, video-reference, advertisement-wrapper, and
  video elements. The original video then filled Tachiai's usable landscape
  viewport with normal aspect-ratio bars. Successive captures showed different
  frames, and Android foreground-activity inspection confirmed that Tachiai,
  not the installed Twitch application, was visible.
- Interaction limits: an immediate capture after a center tap showed Twitch's
  promotional, follow, celebration, and chat overlays, but no playback
  transport or audio controls. Provider-original playback-control reachability
  therefore remains unverified rather than failed. Player replacement is
  attempted by a mutation observer and periodic reacquisition, but
  advertisement and actual player-replacement transitions were not exercised.
- Native control evidence: two captures about ten seconds apart while paused
  were pixel-identical in the video crop. After Play, about 98% of pixels in
  the same crop changed between captures. Mute and Sound reported the expected
  media-property states. Twitch initially retained a volume of 0; Volume Up
  changed the reported value to 10%. Acoustic output and how long a paused live
  position is retained were not independently measured.
- Concurrent playback: two independent `queenbee` channel pages both advanced
  in a same-channel diagnostic. Pausing the left pane produced no changed
  pixels there while about 98% of the right video crop changed. The panes also
  reported independent muted and unmuted states.
- Primary/secondary sizing: a 2:1 landscape split left the secondary page
  unable to expose a visible player. A 60/40 split rendered both original
  videos. Swap exchanged the keyed pane sessions and their status state without
  another app/web chooser or an observed browser reload.
- Provider transition: Twitch displayed an interstitial/advertisement in one
  pane while the other continued. Tachiai did not hide or bypass it; media
  commands reported no visible provider player until Twitch restored the
  video. This was a bounded transition observation, not a full advertisement
  durability test.
- Lifecycle: after Home and a hot return to Tachiai, the same browser surface
  again showed full-size playback and a later program frame; Android confirmed
  Tachiai was foreground. A cold app restart also recovered after Twitch showed
  its app/web chooser and the web option was selected. Whether media advances,
  pauses, or reconnects while backgrounded was not measured.
- Result: pass for visible full-size playback through Twitch's focused
  top-level mobile channel page on this device; pass for concurrent same-channel
  decode, separate media control, and keyed pane swapping; fail for the packaged
  official embed; pass for one hot Home/resume and one user-assisted cold
  recovery. Acoustic mixing, concurrent ABEMA/Twitch playback, advertisement
  durability, sustained playback, other WebView versions, and Android TV remain
  inconclusive. The top-level route remains an experimental candidate rather
  than a settled cross-device implementation choice.

### Pixel 6 ABEMA replay follow-up — 2026-09-28 00:08 EDT

- Device and browser engine: the same Pixel 6, Android 17, and Android System
  WebView 153.0.8010.36 as the Twitch experiments. ABEMA 10.184.0 and Chrome
  using the same Chromium version were installed.
- Provider and content: public free replay
  `https://abema.tv/video/episode/394-72_s10_p8529`, the September tournament
  day-15 makuuchi highlights. Tachiai's WebView profile was anonymous. The user
  accepted ABEMA's terms in the native app; that native profile is separate
  from WebView.
- Region and network: the user selected a Proton Japan exit. The exact server
  was not recorded. Android later reported an active, non-bypassable VPN whose
  UID range included Tachiai, but this does not prove that ABEMA accepted or
  geolocated the exit as Japan.
- Mobile Chrome initially reached the episode page and displayed ABEMA's own
  “continue in app to watch in full” policy. Its handoff opened the installed
  ABEMA app, where the free replay visibly advanced. This proves only that the
  selected content could play in that app/profile/network state.
- The native player entered Android picture-in-picture and remained visible
  over Tachiai. Starting Twitch then caused Android to fade, pause, and stop
  advancing ABEMA after Twitch requested audio focus. Resuming ABEMA from its
  PiP control caused Twitch's WebView to lose and abandon audio focus. Native
  PiP is therefore a useful control but not an embedded two-feed Tachiai
  surface, and this run did not produce concurrent motion with mixed audio.
- Direct WebView episode loads earlier produced Chromium
  `ERR_CONNECTION_ABORTED`; the public sumo discovery route later rendered an
  ABEMA shell with unresolved content placeholders. These observations were
  not stable enough to assign a cause.
- In a later same-session sequence, the default WebView identity, a mobile
  Chrome-like identity, and a desktop Chrome-like identity all rendered
  ABEMA's own Japanese “service unavailable from your region” page. A fresh
  load of the same URL in Chrome produced the same page. The common Chrome
  failure means that matrix is blocked by the network/region control and does
  not distinguish WebView, mobile-content, player, or DRM behavior. No user
  agent is a successful candidate from this run. The order was default, mobile,
  then desktop, and persistent WebView cookies, cache, storage, and service
  workers were not cleared; future divergent results require an explicit
  user-approved isolation or reversed-order procedure.
- After the user changed to another Japan VPN exit, the same Chrome control
  loaded the normal anonymous episode page again. It labeled the replay free
  and displayed ABEMA's mobile-browser policy to continue watching in the app;
  it did not request account authentication. Android reported that this new
  non-bypassable VPN covered Tachiai's UID, but ABEMA documents VPN use as
  unsupported, so the run remains diagnostic rather than production evidence.
- Under that passing Chrome control, the default WebView and mobile
  Chrome-like identity produced the same result: ABEMA's first-party episode
  shell, artwork, metadata, and app banner loaded, while later content remained
  placeholder-like and no visible original `video` element became available to
  Tachiai's Play command. Removing the WebView user-agent markers therefore did
  not change the observed outcome in this sequence.
- The Windows Chrome-like identity selected ABEMA's desktop application. It
  initialized Android's Widevine implementation, but rendered an oversized,
  horizontally clipped desktop layout and an optional three-step demographic
  survey. No demographic data was supplied.
- With explicit user approval, Tachiai's app-private data was cleared to isolate
  the desktop run; Chrome and the native ABEMA app were not cleared. On the
  clean profile, the optional onboarding card was dismissed with its “Later”
  action. The episode then exposed an original visible `video` element. A
  Tachiai Play command advanced through distinct replay frames without opening
  the native app, and pause/play continued to address that element. No login
  prompt appeared.
- The ABEMA media element initially had both `muted` set and its own volume at
  0%. Sound followed by one 10% volume step created and started a 44.1 kHz
  Tachiai `AAudio` media player in Android's audio service. The phone's system
  media volume remained 0/25, so this establishes an audio pipeline but not
  audible output or mixing. The element's player stopped about ten seconds
  later; the bounded run did not determine whether that was content, buffering,
  or player lifecycle behavior.
- Result: fail for the native-app PiP approach as the required embedded
  two-feed experience; fail for playback in the two mobile WebView identities
  in this bounded run; pass for short replay video playback and audio-pipeline
  creation in the unsupported desktop identity after declining optional
  onboarding. Mixed-provider concurrency, audible mixing, focus mode,
  sustained playback, advertisements, live content, and supported regional
  operation remain unverified. No screenshot from the experiment is retained
  in the repository.

### Pixel 6 mixed-provider follow-up — 2026-09-28 19:32–20:15 EDT

- Device and browser engine: the same Pixel 6, Android 17, and Android System
  WebView 153.0.8010.36. Tachiai was foreground and used its persistent private
  WebView profile. The user selected the saved Proton `Abema` Japan profile;
  as above, that unsupported VPN condition is diagnostic rather than production
  evidence.
- Provider content: the ABEMA day-15 public replay above and temporary debug
  Twitch channel overrides. `midnightsumo` and `monstercat` were offline during
  the run. `TheBurntPeanut_247` and later `arcajazz` supplied live Twitch video;
  these substitutions test the generic provider pane and do not change the
  default `midnightsumo` pairing.
- Both original provider videos advanced concurrently in Tachiai's two-pane
  activity. Swapping primary and secondary panes retained their WebView
  sessions. This is a bounded pass for mixed-provider concurrent video decode
  and session-preserving swap, not for sustained, thermal, advertisement, or
  Android TV behavior.
- Twitch's “Keep using web” app-handoff choice recurred for each newly selected
  channel. Live DOM inspection found a distinct exact-text button paired with
  an “Open in App” link for the selected channel. The adapter now dismisses
  only that guarded channel-matching prompt on either the selected channel path
  or Twitch's observed canonical `/home` suffix; it does not accept other
  channel tabs or click login or legal prompts. A cold `arcajazz` launch in the
  rebuilt APK dismissed the chooser and remained in Tachiai's original Twitch
  WebView, so this narrow prompt handling passed on the tested device.
  Twitch's player also restored its internal muted state and zero volume after
  a plain media-property change. A user-gesture diagnostic that
  set a nonzero volume and dispatched the standard `volumechange` event was
  retained by Twitch at 50%; the user then confirmed audible Twitch output.
  The adapter now emits that event for mute and volume commands. After the
  rebuilt APK was installed, the user selected Tachiai's Play and Sound
  controls; the original Twitch element remained playing, unmuted, ready, and
  at 50%, Android started a Tachiai `AAudio` player, and the user confirmed
  audible `arcajazz` output. This is a pass for the rebuilt generic controls on
  this player state.
- With system media volume at 2/25, the user independently confirmed audible
  ABEMA output. Android reported active Tachiai `AAudio` media players, and no
  other app held audio focus.
- Mixed audio failed in the current two-WebView architecture. Starting ABEMA
  after Twitch caused Twitch to pause; restarting Twitch caused ABEMA to pause;
  starting ABEMA again paused Twitch. Live media inspection showed the stopped
  provider's original element paused while the other remained ready and
  playing. Android's audio-focus log recorded two Chromium
  `AudioFocusDelegate` clients in Tachiai's UID requesting full gain, with the
  later request delivering `handleLoss` to the earlier client. Android also
  reported multi-audio-focus disabled. This bidirectional result isolates the
  failure to audio-focus arbitration rather than decode capacity or a single
  provider.
- Android documents a single audio-focus holder and enforced focus behavior on
  Android 12 and later. AndroidX WebKit exposes per-WebView tab mute/unmute but
  no public per-WebView audio-focus policy. The public APIs reviewed therefore
  do not provide a supported switch that makes these two WebViews mix:
  <https://developer.android.com/media/optimize/audio-focus> and
  <https://developer.android.com/reference/androidx/webkit/WebViewCompat#setAudioMuted(android.webkit.WebView,boolean)>.
- Result: pass for simultaneous mixed-provider video and audible output from
  each provider individually; fail for simultaneous mixed audio using two
  stock WebViews on this device. The separate-browser-surface architecture is
  still provisional and needs a provider-supported single-focus hosting model
  or another supported audio strategy. No screenshot from this experiment is
  retained in the repository.

### Pixel 6 single-WebContents mixed-audio follow-up — 2026-09-28 21:09–21:54 EDT

- Device and environment: the same Pixel 6, Android 17, Android System WebView
  153.0.8010.36, foreground Tachiai process, persistent app-private WebView
  profile, and user-selected Proton `Abema` Japan profile. The VPN condition is
  an unsupported diagnostic variable, not a product mechanism or evidence of
  supported regional operation. The ABEMA profile was anonymous; no provider
  credentials were captured or transferred.
- A debug-only app-owned page first started two generated looping audio
  elements. Both reported playing concurrently. Android reported one Tachiai
  Chromium `AudioFocusDelegate` and one active `AAudio` output, supporting the
  hypothesis that media in one WebContents is aggregated under one Android
  focus owner.
- The provider probe then loaded the known ABEMA public replay as the top-level
  document with the same experimental Windows Chrome-like identity used in the
  earlier successful replay test. It inserted an official
  `https://player.twitch.tv/` cross-origin child frame occupying the right half
  of the page. The requested Twitch channel was validated, the frame declared
  only `abema.tv` and `www.abema.tv` as parents, and protected-media permission
  remained limited to exact ABEMA origins and `player.twitch.tv`. No JavaScript
  bridge, media extraction, credential access, or DRM workaround was added.
  The composite WebView enabled third-party cookies globally, not only for
  Twitch; this is a diagnostic privacy tradeoff that requires review before a
  product implementation. The probe performs no provider onboarding clicks and
  removes its frame and recurring script immediately if same-document
  navigation leaves an allowlisted ABEMA playback route.
- ABEMA's response allowed the composition in this run: its response contained
  `X-Frame-Options: sameorigin` but no observed content-security-policy header
  or meta policy that blocked this outgoing Twitch child frame. This is a
  point-in-time observation, not a promise that ABEMA permits or will continue
  to permit the arrangement.
- Twitch channel `arcajazz` went offline during the investigation. Replacement
  live channel `izgonnabemei` started visibly through the official embed when
  configured for documented muted autoplay. ABEMA's replay also advanced. Two
  screenshots several seconds apart showed different frames from both players;
  the screenshots remain temporary and are not retained in the repository.
- Twitch was initially audible after the user enabled sound in its official
  frame controls. Tachiai's native ABEMA Sound command did not clear ABEMA's
  provider-owned “click to unmute” overlay. After that visible overlay was
  tapped directly, both videos kept advancing and the user confirmed hearing
  both Twitch and ABEMA. Android showed one Tachiai Chromium audio-focus owner
  with full gain and one active Tachiai `AAudio` output.
- Result: bounded pass for two concurrent original provider videos and mixed
  provider audio in one WebContents on this Pixel 6. This demonstrates a viable
  public-platform mechanism for avoiding the separate-WebView audio-focus
  conflict. It does not yet establish independent native volume control,
  thirty-minute stability, advertisement or player-replacement recovery,
  lifecycle behavior, live ABEMA behavior, login persistence, Android TV
  behavior, provider approval, or a production-safe way to arrange arbitrary
  provider pairs. The probe remains debug-only and no captured image is stored
  in the repository.
