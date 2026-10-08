# Android playback spike

The later [timing capability matrix](timing-capability-matrix.md) records
isolated forward/backward tests for Twitch and ABEMA, live and recorded, plus
bounded live pause-retention experiments. Those results supplement rather
than replace the historical runs below.

This page records the assumptions, observation format, and results for the
first Android experiment. Playback claims apply only to the recorded device,
provider page, channel state, and test conditions.

Before resuming an ABEMA phone experiment, remind the user to confirm the
current playback connection; they requested this reminder after forgetting the
VPN on 2026-10-03. Record the actual network condition rather than assuming the
previous run's profile is active. User-selected VPN observations remain
unsupported diagnostics, not evidence of supported regional operation.

## Native mixed-provider comparison prerequisites

Native live/live and replay/replay pairs have played in bounded phone tests;
mixed audio is user-confirmed for both. Both replay players settled held
bidirectional five-second seeks; a live refresh protection-preflight failure
remains unresolved.
The generic mixed-pair controller is separate from the same-Twitch-VOD alignment
controller: unrelated provider timestamps are never subtracted as event sync.
See [the current replay source boundary](native-access-experiments.md#native-mixed-pair-preparation-and-replay-source-boundary--2026-10-06).

The debug source-only prerequisite can be cold-launched without removing earlier
examples or clearing app data:

```sh
adb shell am force-stop net.fstab.tachiai
adb shell am start -n net.fstab.tachiai/.feature.diagnostic.AbemaOpaqueBrokerActivity \
  --ez net.fstab.tachiai.extra.ABEMA_BROKER_REPLAY_SOURCE_PROBE true
```

It displays the original free replay and reports only closed `TachiaiAbemaSource`
shape/CDN categories. It does not start a native replay or a pair.

An additive debug native pair can be cold-launched with the same activity and
`--es net.fstab.tachiai.extra.ABEMA_NATIVE_PAIR LIVE` or `REPLAY` instead of the
source-only flag. LIVE selects anonymous ABEMA News plus `relaxbeats`; REPLAY
selects the fixed free sumo episode plus Twitch moving-match VOD `2080217716`
at 70 minutes. These are unrelated sources, not an identical-feed sync test.
The existing saved Twitch LOCAL grant must validate; no new authorization or
session transfer is automatic. Both players prepare paused and the original
ABEMA web player must pause/mute successfully before joint Play acquires one
native audio-focus owner. Earlier/later buttons request each selected player's
own ±5-second seek with joint hold/settlement, not subtraction of provider
timestamps or advancing live into the future. The user-approved pair-only
foreground cap is five minutes from native preparation, giving time to confirm
readiness and listen; earlier cases keep two minutes. Both native replays have
played together; both replay/replay and News/RelaxBeats live/live pairs produced
user-confirmed mixed audio. Paired replay timing checks passed exact held ±5-second
clock shifts for each selected player, with the other unchanged. A live
manifest-refresh preflight refusal stopped one timing repeat.
Live preparation must start promptly: waiting paused can move its
content point outside the current advertised window.

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

### Build modes for browser diagnostics

The later user-approved debug ABEMA inspection is a separate activity/process
inside `net.fstab.tachiai`, not another application. It retains the original
provider player without adapter injection and uses an isolated persistent
diagnostic profile. Its Windows Chrome-like identity remains an unsupported
comparison. Only News and the known free sumo replay are selectable, and the
visible warning prohibits credentials. See the
[inspection boundaries](security-and-privacy.md#explicit-abema-playback-inspection).
This activity exists only in `assembleDebug`, not release or the fixture APK.

After shared-certificate verification and a data-preserving debug update:

```sh
adb shell am start -n net.fstab.tachiai/.feature.diagnostic.AbemaInspectionActivity
```

An optional `net.fstab.tachiai.extra.ABEMA_INSPECTION_SOURCE` string accepts only
`NEWS` or `REPLAY`; no arbitrary URL is accepted. The native News, Replay and
Reload controls operate the original provider page. Close/background disables
debugging and destroys this WebView without deleting its profile.

For the explicitly authorized metadata-only collector, obtain this activity's
process ID and forward its `webview_devtools_remote_<pid>` socket to a local
port with ADB. Do not forward the ordinary provider process or a browser app.
Run `uv run scripts/abema-inspection.py --port <local-port> --seconds 45`, then
Reload/tap the original player if needed. Remove that exact forward afterward.
The collector's `--self-test` covers its closed-output and route classifiers.
It also checks related-worker session routing, refused targets, optional-domain
failures and bounded/redacted media classifications. Closed capability markers
report whether Media and related-target attachment succeeded. The final counts
separate page/worker/frame traffic and response MIME categories; they are not
necessarily unique requests across targets. The collector does not clear site
data or pause workers to force a fresh license exchange.
It does not prove which JavaScript handler executed: matching response metadata
supports only a conditional inference from the separately inspected static code.

For the additive handshake comparison, append `--handshake-metadata` to that
collector command. Attach before using native Reload. Its bounded origin/method/
status sequence is not a body/header capture or a reliable operation label.
The [source/handshake trace](native-access-experiments.md#source-and-handshake-interface-trace--2026-10-06)
records the static map, the first phone result and its origin/output-coverage gaps.

For the later exact configured-host comparison, use the independent
`--handshake-role-metadata` flag instead. Attach before Reload or native source
selection. It reserves per-role output/tracking capacity for recognized routes
instead of allowing unrelated API traffic to hide later license observations.
Closed route labels, method, status/MIME and cache indicators do not expose raw
URLs, query values, bodies or credentials. Unknown cache flags stay unknown.
See the [configured-route results](native-access-experiments.md#configured-route-handshake-comparison--2026-10-06).
Close the isolated activity and remove its exact forward after the comparison.

The additive native request-format experiment is a separate debug-only screen
in the same application, not a replacement player. Open it with the inspection
screen's Native request format button, or:

```sh
adb shell am start -n net.fstab.tachiai/.feature.diagnostic.AbemaNativeRequestActivity
```

Confirm the current playback connection, then tap Prepare request explicitly.
It fetches News's anonymous advertised DASH initialization and prepares one
Android Clear Key streaming request; it stops before license/provisioning
traffic. No browser engine/profile participates. Native UI and the
`TachiaiAbemaRequest` log tag expose only closed metadata. Stop/background
cancel; Close returns the phone. See the
[experiment boundary](security-and-privacy.md#native-abema-request-format-experiment)
and [request-format results](native-access-experiments.md#native-request-format-probe--2026-10-06).

For the user-approved one-exchange native/browser broker, launch the separate
debug-only host after a shared-certificate-verified, data-preserving update:

```sh
adb shell am start -n net.fstab.tachiai/.feature.diagnostic.AbemaOpaqueBrokerActivity
```

This host uses its own persistent anonymous profile, fixed News route and
unsupported desktop identity. DevTools remains disabled; do not forward its
debugger or enter credentials. Let the original player initialize (decline
optional onboarding with Later if needed). Only `READY` enables Native exchange;
page load completion is not readiness. The explicit action prepares one native
challenge, passes it through unchanged provider code, submits its opaque
response to that same CDM session, then closes/releases it. This default case
does not play native media. Stop/background/navigation cancel; Reload explicitly starts a
new document/capture instead of reusing the old context. The warning does not
grant a general bridge or credential export. See the
[broker boundary](security-and-privacy.md#authorized-opaque-nativebrowser-broker-investigation).

For a same-host/profile control with no broker injection, close the current host
and add `--ez net.fstab.tachiai.extra.ABEMA_BROKER_ORIGINAL_ONLY true` to that
launch command. Native exchange is disabled in this control. Factory/load status
markers in the ordinary probe are closed diagnostics only, not playback proof.
The `TachiaiAbemaBroker` debug tag also reports fixed call-path booleans:
common manager factory/session calls, shared dash.js namespace/first factory
creation and the original playing video's dash.js auto-create marker. These
markers do not capture another player or enable Native exchange; the original
one-player configuration/filter/transport checks still govern readiness.
An additive shared-factory capture comparison preserves the default legacy
loader case. Close the current host first, then launch it with
`--ez net.fstab.tachiai.extra.ABEMA_BROKER_SHARED_DASH_CAPTURE true`.
This selects the first shared dash.js factory's raw player as the candidate;
it does not prove that candidate's source identity or native compatibility.
The same fixed News route, unchanged provider filter/serializer, initial
transport checks, one-exchange limit and DevTools-off boundary apply.
See the [observed startup limits](native-access-experiments.md#authorized-one-exchange-broker-prototype--2026-10-06).

For the additive native News playback comparison, close the current host, then
launch it with `--ez net.fstab.tachiai.extra.ABEMA_BROKER_NATIVE_PLAYBACK true`.
This selects shared-factory capture and adds a Media3 DASH surface without
removing the original web player. Wait for `READY`, then tap Native playback.
Media3 owns a fresh Clear Key session and sends its own initial challenge through
the unchanged browser helper; it never reuses the acceptance probe's session or
response. The foreground budget is two minutes, with one initial exchange and
no provisioning, renewal or retry. Mute web is an explicit audio-isolation
control; Native 50% restores native volume after Mute native. Native frame and
audio events are reported separately under `TachiaiAbemaPlayback`. Media access
is restricted to exact MPD-declared files on the bounded CDN authority, not a
guessed path prefix or whole-CDN permission. Native News video and user-confirmed
audio are observed on one Pixel 6; other content and durable operation are not.
Pause/Play, Back/Forward 5 s and explicit Live recovery reuse generic native
seek rules. `TachiaiAbemaTiming` reports only scalar clocks/capabilities and
before/request/after markers; requested seeks still require settled observation.
Reload clears the old native surface before initializing a new original page.
See the [native comparison boundary](security-and-privacy.md#native-news-playback-comparison)
and [observations](native-access-experiments.md#manifest-declared-native-media-paths-follow-up--2026-10-06).

For the separately authorized startup capability sequence, use
`uv run scripts/abema-eme-startup.py --port <local-port> --seconds 45` instead.
This opt-in tool temporarily observes browser EME capability calls on the fixed
top-level playback route and performs an ordinary reload; it does not inspect
configurations or license/session data. Keep credentials out of this profile.
It reports closed request IDs, standard key-system categories and support
outcomes, not selected-CDM proof. Close the isolated inspection activity after
**every** run/failure and remove the exact ADB forward. A successful
`STARTUP_REGISTRATION_REMOVED` marker prevents future injection but does not
restore the current document; activity closure destroys that document. A missing
READY marker or setup failure is unavailable instrumentation, not zero requests.
Run `uv run scripts/abema-eme-startup.py --self-test` and
`node --test scripts/tests/abema-eme-startup.test.cjs` for its classifiers,
command/cleanup routing and pass-through/restoration tests.

`assembleDebug` builds the existing protected provider spike at
`net.fstab.tachiai`. `assembleDiagnostic` builds the separate inspectable
**Tachiai Browser Lab** at `net.fstab.tachiai.diagnostic`; both development APKs
must reuse the documented shared debug key and pass certificate verification.
`assembleRelease` remains the deployment build and must not use that key.

Use the documented Android-build container and signing procedure with:

```sh
bash ../../scripts/build-android-routebridge.sh
bash gradlew --no-daemon testDebugUnitTest testDiagnosticUnitTest lintDebug lintDiagnostic assembleDebug assembleDiagnostic
```

The debug native route bindings require pinned Go 1.27.2 and Android NDK
30.0.16248370 in that disposable SDK. The bridge script runs its fixture tests
and cross-compiles ARM64/x86_64, then verifies 16-KiB ELF load alignment. It
packages required dependency notices; the AAR remains generated/ignored under
`routebridge/build/`. Build it before Gradle. These native bindings currently
limit this debug APK to 64-bit ARM/x86 devices; 32-bit Android and Shield
interoperability remain unverified. No emulated ARM compiler is used.

Verify each APK separately with `apksigner verify --print-certs`, then inspect
its application ID and merged launcher manifest. The fixture launcher should
exist only in the diagnostic APK, and the provider launcher only in the regular
APK. Installing the diagnostic artifact alongside the existing application must
not uninstall it or clear its provider storage.

```sh
adb install -r app/build/outputs/apk/diagnostic/app-diagnostic.apk
adb shell am start -n net.fstab.tachiai.diagnostic/net.fstab.tachiai.feature.diagnostic.BrowserLabActivity
```

The laboratory's banner states that screenshots and DevTools inspection apply
to a credential-free fixture, not provider accounts. Its native Short height /
Full height control and page viewport measurements permit a controlled sizing
comparison. Default alert/confirm/prompt dialogs, a static console marker and
blocked external link exercise the diagnostic host. DevTools may inspect only
this isolated fixture process; do not forward or inspect the provider profile.
See the [mode boundaries](security-and-privacy.md#inspectable-browser-laboratory).
Build, installation and device results must be recorded separately from these
implementation intentions. This does not yet diagnose Twitch's own squashed
page or remove restrictions from the authentication probe.

#### Laboratory verification — 2026-10-04 EDT

The Android-build skill used the existing pinned tools container and read-only
shared signing key. The first invocation stopped because AGP did not create a
unit-test component for the non-default build type. An explicit diagnostic-only
`HostTestBuilder` configuration enabled it; the retry passed 76 protected debug
unit tests (up-to-date), 79 diagnostic unit tests including three fixture-policy
tests, both lint tasks, and both APK assemblies. Lint retained only the existing
ModifierParameter and IconMissingDensityFolder warnings. Both `apksigner`
certificate fingerprints matched the documented identity.

The host-owned diagnostic APK is 11,835,943 bytes with SHA-256
`c2797090a4a1761eb841e8475a0edc36c8d0ed2c2d1f1eeec4455472f49f8938`.
The protected debug artifact retained its preceding SHA-256
`2c312c12910bb8763749473cbf312cdd923a76e9a152fcef062b475d8282092c`.
Merged manifests confirmed different application IDs, the laboratory's sole
fixture activity, and no laboratory activity in the protected APK. APK contents
confirmed the fixture assets occur only in the diagnostic artifact.

The lab installed alongside Tachiai without clearing either application's data
on the same Pixel 6, Android 17 and WebView 153.0.8010.36, in landscape. Region
and account state are not applicable to its local, credential-free content; no
provider state was inspected. A normal ADB screenshot visibly captured the
fixture. Its process-specific DevTools endpoint exposed exactly the expected
fixture target; native/browser measurements were available. The native height
toggle changed the browser from 2146 × 754 to 2146 × 263 physical pixels and
back; CSS viewport height changed from 287 to 100 and back at DPR 2.625.
Screenshots corroborated both sizes. This verifies fixture hosting/automation,
not Twitch's own short-page cause.

Default alert, confirm and prompt events were observed via DevTools. Resolving
their JavaScript results through DevTools left native Android dialog windows
visible, including the fixture's dummy prompt. They therefore require native
UI dismissal as well; do not claim CDP alone closes those windows. Restarting
only the fixture cleared its test dialogs; the provider app/profile was not
stopped or cleared. The blocked external link left the fixture target unchanged,
and an attempted launch of the diagnostic package's provider activity returned
the expected activity-not-found error. Temporary debugger forwards were removed.
No provider content, authentication tree, passwords or session storage was
captured, and diagnostic screenshots were not retained in the repository.

### Provider spike defaults

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

### Independent composite controls — implementation follow-up, 2026-10-03

The debug-only `net.fstab.tachiai.extra.SINGLE_WEB_CONTENTS_PROBE` now exposes
separate native Play, Pause, Mute, Sound, and volume-step controls for ABEMA and
Twitch, with independent status and a shared Reload action. ABEMA remains the
original top-level replay page with the experimental desktop identity. Twitch
is hosted through an app-owned child page at
`https://appassets.androidplatform.net/assets/twitch/composite.html`, containing
the original official Twitch player and its
[documented interactive SDK](https://dev.twitch.tv/docs/embed/video-and-clips/).
The declared ancestors are `appassets.androidplatform.net`, `abema.tv`, and
`www.abema.tv`. The wrapper reserves 40 CSS pixels for direct Start and Sound
controls and leaves the official player at least 400 by 300 CSS pixels.

Native Twitch commands use an exact-origin `postMessage` exchange, not DOM
access to the cross-origin media element or a native bridge. Requests and
replies validate their schema, source, origin, request ID, and random frame
session. Replies contain only fixed request-status values; polling stops after
about three seconds and is invalidated by newer same-pane commands, navigation,
reload, detach, or browser replacement. Leaving an ABEMA playback route removes
the wrapper and message listener. The composite exposes only its two approved
asset paths; unknown synthetic-origin requests receive a local 404 rather than
a network fallback. Global third-party cookies remain the earlier diagnostic
privacy tradeoff.

Experimental assumptions: the added appassets ancestor and restrictive wrapper
content-security policy may change playback compared with the previously
successful direct Twitch iframe. Native requests may not carry browser user
activation, so direct wrapper and provider controls remain available. An SDK
acknowledgment confirms a requested operation, not advancing video, audible
mixing, or a measured volume level; rapid volume steps may read stale SDK state.
No new playback result is established by the implementation or mocked tests.

The debug APK installed successfully on the Pixel 6 without clearing app data.
ADB confirmed Android 17 and Android System WebView 153.0.8010.36. The phone
remained locked after launch, so no wrapper rendering, playback, control, or
audio result was observed. Current network region and provider account state
were not reverified; the earlier anonymous/VPN observations do not establish
those conditions for this follow-up.

After unlocking, the same Pixel 6 rendered the app-owned wrapper and original
Twitch player under ABEMA. `arcajazz` was offline, but its SDK acknowledged
native Play and Pause requests through the validated exchange. Replacement
channel `relaxbeats` rendered a normal preroll followed by live music video;
the wrapper received Twitch's `PLAYING` event. Native Pause produced Twitch's
`PAUSE` event and visible paused controls, and native Play subsequently produced
`PLAYING` again. This is a bounded pass for wrapper loading and native Twitch
play/pause on this engine, not yet for audio or concurrent provider control.

ABEMA's optional onboarding was dismissed with its visible “Later” action;
the replay then showed the provider's region-rejection message. The current
connection was not yet established as accepted by ABEMA, and no new account
flow was exercised. Native Sound was acknowledged, but the audio-state sample
overlapped the user's switch to Proton, so it does not establish success or
failure of that command. The user subsequently selected the Proton `Abema`
Japan profile; its unsupported-VPN classification remains unchanged, and
playback under that new condition requires a fresh observation. Screenshots
remain temporary and are not retained in the repository.

After the user confirmed their Japan exit was connected, a fresh composite
launch reached ABEMA replay playback after the visible optional onboarding was
dismissed. Native ABEMA Pause returned a paused media-element state while Twitch
continued; native Play resumed ABEMA. Native ABEMA Sound still left its
provider-owned click-to-unmute overlay visible, which was cleared through a
direct tap on that overlay. Twitch native Sound and the wrapper's direct SDK
Sound both acknowledged requests; inspecting the transient original speaker
icon alone did not establish the retained state. Twitch's original speaker
control was also exercised, so first-use activation remains a separate
unresolved requirement rather than a proven native-only flow.

The user then confirmed hearing both ABEMA and RelaxBeats. Both providers still
rendered together, and Android reported one Tachiai Chromium audio-focus owner
with full gain and one active `AAudio` output. This is a bounded pass for mixed
audio with the additional appassets wrapper on this Pixel 6, under the
user-selected unsupported VPN and desktop-identity conditions. It is not a
thirty-minute stability result or evidence of supported regional operation.
Independent native Twitch mute/unmute and volume still require a controlled
retained-state/acoustic check rather than relying on SDK acknowledgments.

A native Twitch Mute request was acknowledged. One separated native volume-down
step returned `volume 80% requested` after an earlier `90%` request, supporting
that the SDK readback had retained the earlier level; the acoustic change was
not independently confirmed. ABEMA also showed a native 40% volume result.
The subsequent isolation check was not completed, and controls had also been
operated directly, so these observations do not settle independent native
audio control. During a later black/end-card interval, ABEMA native Play was
requested and replay video became visible again; no position observation was
available to classify it as a restart, resume, or provider transition. Twitch
Sound was restored at the end of the bounded run. The temporary thirty-minute
screen timeout and landscape lock were restored to their original one-minute
timeout and automatic rotation; Tachiai still keeps its visible activity awake.

The user's follow-up confirmed that adjusting independent provider volume
levels made both sources audible and balanced. This is a bounded acoustic pass
for independent level adjustment in that composite experiment; exact levels
and the complete native/direct control sequence were not recorded. Native-only
first-use activation, isolated mute checks, and sustained retention remain open.

### Same-Twitch-replay alignment diagnostic

The user selected two copies of a Twitch replay as the first manual alignment
test. The debug-only `net.fstab.tachiai.extra.REPLAY_ALIGNMENT_PROBE` opens one
app-owned page with two original official Twitch SDK players in one WebView.
The initial public VOD was `335921245` (Twitch Developers 101); the optional
string extra `net.fstab.tachiai.extra.TWITCH_VIDEO` accepts only a numeric public
VOD ID, not a URL or channel. This default is a test fixture, not a product
provider selection, and its continued availability is not guaranteed.

Each pane has direct Play, Pause, Sound, Mute, and forward +250 ms, +1 s, and
+5 s controls. Forward requests use the documented VOD-only `seek(seconds)`
operation, clamp to duration, and refuse invalid clocks. Buttons gate rapid
same-pane seeks while waiting up to five seconds for a plausible SDK clock
readback. Requested targets remain separate from the sampled positions;
readback is not proof of frame-accurate seeking. The page samples both clocks
every 250 ms and shows signed Right minus Left seconds. That difference is not
content-derived alignment, and buffering, ads, SDK caching, and scheduling can
make it misleading. Provider controls and frames remain intact; narrow screen
sizes scroll rather than shrink below Twitch's 400 by 300 CSS-pixel minimum.

Experimental assumptions: default WebView identity and the top-level appassets
ancestor may behave differently from the mixed-provider desktop-identity
diagnostic. Both players initially mute; direct Sound requests 50% volume.
Third-party cookies are allowed as in the earlier embed diagnostic. No account,
VPN, autoplay, subsecond seek precision, or actual playback result is inferred
from the implementation or mocked tests. No automatic drift correction,
private media extraction, or generic seek architecture is introduced.

The debug build passed 43 Android unit tests, lint (two existing warnings),
the eight new replay JavaScript cases and eleven composite cases, repository
checks, and APK signature verification against the documented shared key.
The verified APK installed on the same Pixel 6 (Android 17, WebView
153.0.8010.36) without clearing data. The immediate launch screenshot showed
Android's update-in-progress surface, not the replay page. The user then took
the phone for other work, so no replay rendering, playback, seek, or acoustic
alignment result has been established. Network region and account state were
not reverified for this experiment. Temporary timeout/rotation settings were
restored before yielding the phone.

Next device check: start both original players, pause both, and sample the
baseline difference before independently requesting +5 s, +1 s, and +250 ms.
The paused-clock check avoids confusing normal advancement with a completed
small seek. Then play both and compare audible echo/offset while moving one
forward. Record requested targets, observed clock deltas, rebuffer behavior,
and the smallest visibly/acoustically reliable adjustment separately.

On the next phone handoff, the same Pixel 6 rendered both original replay
embeds at once with the default WebView identity. The public VOD was available,
reported a 201.5-second duration, and did not demand login. Existing cookie
state and network region were not inspected; the system VPN indicator was
visible but does not verify a particular exit. Direct app-page Play started
both copies without needing the original player Play buttons. Both clocks
advanced and displayed matching content.

After pausing both, the following SDK samples were recorded in seconds. These
are rounded clock readbacks, not independent frame-timing measurements.

| Action while paused | Left sample | Right sample | Right minus Left |
| --- | ---: | ---: | ---: |
| Baseline | 34.273 | 34.260 | -0.013 |
| Right +5 s | 34.273 | 39.260 | 4.987 |
| Left +1 s | 35.273 | 39.260 | 3.987 |
| Left +250 ms | 35.523 | 39.260 | 3.737 |
| Left +5 s and Right +1 s | 40.523 | 40.260 | -0.263 |
| Right +250 ms | 40.523 | 40.510 | -0.013 |

All six requested targets matched the paused readbacks to the displayed
millisecond precision. The isolated first three steps left the other pane's
clock unchanged. Both larger and quarter-second steps are therefore feasible
through the official SDK on this particular replay/engine; exact decoded-frame
or acoustic precision is still unverified.

Direct Sound requests set both copies to 50%, and direct Play resumed both.
A running sample showed Left 54.286 and Right 54.280 seconds. A subsequent
Right +1 s request targeted 74.405 seconds, but the later running sample showed
Left 88.286 and Right 87.641, a -0.645-second offset rather than +1 second.
The net change is consistent with seek/rebuffer latency while the reference
keeps advancing; no buffer timing was independently measured. A requested
relative seek is not a guaranteed adjustment of the running pair's offset.
Acoustic confirmation was requested separately and is not inferred from the
Sound acknowledgments. The diagnostic's event label can remain “seeking” after
the clocks resume: it currently reflects the last SDK event, not independently
observed buffer state.

A later running sample showed Left 198.787 and Right 198.141 seconds with the
same -0.645-second offset, so it was retained over about 110 seconds of this
short replay. The right pane's status had changed to a Mute request during user
interaction; that interval is not an isolated acoustic test. The user reported
no audio, and again reported silence after an end-of-replay restart into spoken
content with fresh app-page Sound requests. Android's speaker media volume was
19/25 and was not muted. Sound acknowledgments therefore did not establish
audible activation in this top-level/default-identity experiment.

Scrolling exposed Twitch's intact original controls. Direct taps inside the
players paused them; each visible speaker icon showed a mute mark. Both
original speaker controls and original Play controls were then exercised.
The left speaker icon became unmuted, both clocks resumed, and the user
confirmed audible speech with a delay. User-operated forward controls also
changed a requested target during that interval, so no particular audible
offset is assigned to the confirmation. This is a bounded pass for hearing the
delayed pair after original-player interaction, not for app-page-only initial
unmute. The precise cause of initial Sound failure remains unresolved. The
running diagnostic was left available for the user's manual button experiment;
the original one-minute timeout and automatic rotation remain recorded for
restoration after that experiment.

### Relative replay controls and motion fixture follow-up

The user confirmed that the original forward controls shifted playback but
were not very controllable, requested relative ahead/behind adjustment and
visible direction feedback, and asked for moving video rather than slides.
The updated debug page selects Left or Right relative to the other, with Ahead
and Behind steps of 250 ms, 1 s, and 5 s, plus Match clocks and Cancel. “Leads”
means later in the replay's media timeline, not identified acoustic output.
Requested lead/lag is distinct from the latest sampled lead/lag.

An app-packaged, provider-independent JavaScript coordinator now runs a bounded
hold/seek/resume transaction through a narrow player interface. It samples each
pane's prior pause state, requests pause on both, waits up to four seconds for
stable paused clocks, seeks the selected pane to the requested signed offset
against the held reference, and waits up to five seconds for stable target
readback. It requests playback only for panes previously playing. A subsequent
nudge uses the retained desired offset rather than accumulating observed
restart skew; after boundary clamping, its base is the achievable held offset.
There is no automatic running correction. SDK readback does not establish
decoder readiness, and startup skew can still leave the running pair uncertain.

The Twitch adapter alone owns SDK calls and resource identities. Source or
duration changes, unavailable clocks, known blocked/ended playback, large
external timeline jumps, explicit cancellation, and bounded timeouts invalidate
the experiment's requested alignment. App-page controls disable during a
transaction; original provider controls remain usable. Detected original-player
intervention cancels instead of silently choosing a new anchor. These guards
rely on sampled SDK state and cannot detect every ad, brief intervention, or
cached observation. Page exit disposes the coordinator without further Play
requests. No generic native bridge, new shared package, or media extraction is
introduced.

The page now shows actual current public VOD IDs, rather than assuming the
initial requested VOD survives Twitch's recommendation transitions. Comparing
different current IDs disables relative controls. It also labels the last SDK
event separately from sampled pause state, and displays SDK mute/volume
readback without claiming audible output. A numeric public VOD-ID field reloads
both copies while keeping credentials and provider storage inside the browser.

The new default candidate is Rocket League VOD `2080217716`,
[RLCS Major 1 qualifier](https://www.twitch.tv/videos/2080217716). Ordinary public
page metadata was available when investigated. Its provisional start at thirty
minutes aims to skip the pre-show; continuous gameplay and playback at that
point still require a device observation. Other entered VODs start at zero.
The earlier Developers 101 ID remains usable through the source field. No
private API or OAuth was used to discover the candidate. Mocked tests validate
the coordinator and adapter wiring, not fixture playback or improved accuracy.

The updated APK passed 43 Android unit tests, lint (the same two existing
warnings), 17 generic coordinator cases, 10 replay-adapter/UI cases, and 11
composite cases. Repository checks passed, the packaged assets matched their
source checksums, and `apksigner` matched the documented shared debug identity.
The user took the phone during local verification, so this version has not yet
been installed or tested there. Its Rocket League start point and relative
control accuracy remain experimental assumptions. The original one-minute
timeout and automatic rotation were restored for that handoff.

On the subsequent handoff, the relative-control APK installed without clearing
data on the same Pixel 6, Android 17 and WebView 153.0.8010.36. Public VOD
`2080217716` reported a 14,407.2-second duration and did not demand login.
Existing provider storage was preserved but not inspected. A system VPN
indicator was visible; the network exit and region were not independently
verified. Thirty minutes was still a pre-show containing casters, tables, and
short gameplay highlights, not uninterrupted match footage.

The user reported that both app-page Play buttons did nothing. SDK readback
confirmed both paused despite the “play requested” labels. Original Twitch
Play controls started both. Subsequent app-page Pause and Play worked with the
players visible, including with both SDK audio states unmuted at 50%. However,
scrolling back to the large header left both paused again; app-page Play at
that scroll position did not resume them. Scrolling back down and repeating
the same SDK Play requests did resume both, without another original-player
Play tap. This repeatable viewport dependency is stronger evidence than the
initial activation hypothesis; it does not identify Twitch's exact visibility
threshold or establish that original interaction is never needed.

[Twitch's official documentation](https://dev.twitch.tv/docs/embed/video-and-clips/)
requires minimum dimensions and visibility for autoplay. The observed SDK
resume behavior is consistent with a visibility restriction, but the public
documentation does not by itself prove the cause of this experiment. Provider
frames must remain visible during control testing rather than using scrolling
to alternate between commands and largely offscreen video.

A narrowly scoped update moved bulky source and sampled-state diagnostics
below the two intact players and compacted the top timing toolbar. Buttons now
say Forward and Backward, with a selected Left/Right pane, rather than the
ambiguous Ahead/Behind. Forward means later media footage, not merely adding
delay. Play/Pause feedback waits for sampled state (and advancing time for
Play) or exposes a four-second observation timeout; SDK request success still
does not prove decoded output or sound. Smaller viewports and scrolling into
the lower diagnostics can still violate visibility, so this is a landscape
diagnostic improvement, not a settled responsive product layout.

The updated APK passed 43 Android unit tests, lint with the same two existing
warnings, 17 coordinator cases, 12 replay UI/adapter cases, and 11 composite
cases. Its certificate matched the shared debug identity. The APK SHA-256 was
`5581b39aa550a2531c7a394388ad709c04cb20cd50ac9032e7f288f30e721100`.
It installed without clearing app-private browser storage. Both original frames
were visible with the timing controls, and app-page Play started both directly
after launch without original Play interaction. Original speaker icons remained
muted despite app-page Sound requests; original speaker taps later cleared
those visible mute marks. Acoustic output was not inferred from either state.

Before the layout update, Match clocks on paused copies produced identical SDK
samples of 2038.386 seconds and preserved both paused states. After the update,
the first running Right Forward 5 s transaction cancelled on a detected clock
discontinuity. The exact discontinuity was not independently measured and
must not be classified as a user intervention. After clocks settled, a repeated
Forward 5 s transaction matched its held target and resumed both original
videos; the later running sample showed Right leading by 4.600 seconds.
The following Backward 5 s transaction also matched its held target and resumed
both, with the later running sample instead showing Left leading by 0.391
seconds. The last retained offset before the successful forward transaction
was approximately Right +0.719 seconds, so the requested targets were not
simply +5 and zero. These observations establish bounded bidirectional
operation, not accurate running alignment: restart skew still differed from
the requested target by roughly one second.

Original timelines were then moved to approximately 69 minutes, where both
frames rendered the same Rocket League match rather than a pre-show table.
This confirms moving match footage at that location, not continuous motion
throughout the VOD. No private API, player replacement, or media extraction was
used. A remaining coordinator risk is that its restoration Play requests are
not held behind a bounded acknowledgment phase; a rapid next nudge could sample
stale paused states. That risk and subsecond running accuracy require further
tooling before these controls are promoted into the product.

A subsequent running Match clocks request in that gameplay section cancelled
with the diagnostic's “Player intervention detected” label. Both videos
resumed; the later sample showed Left leading by 1.390 seconds. No external
interaction was independently established, so that label must not be treated
as proof that the user touched a player. Cached pause/clock state or provider
transitions remain plausible causes. Successful Play and bidirectional
requests do not settle paired-hold reliability.
