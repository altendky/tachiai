# Preliminary native viewer

## Direction agreed October 7

This is an additive debug presentation over the existing bounded native pair,
not a replacement for the browser or native diagnostic examples. Source
selection, authentication, licensing and timing transactions stay outside the
generic layout. There is no new playback or provider permission claim.

- Portrait: ABEMA followed by Twitch, using observed video aspect ratios
  (16:9 fallback), not forced equal halves. When height is constrained, fit both
  without cropping video.
- Landscape: one primary video fills the viewing area with aspect-fit
  letterboxing; the secondary floats above it. Drag the secondary to move it;
  tap it to swap primary. Swap changes placement, never player identity,
  playback position, audio or source. Normalized floating position survives
  orientation changes within this foreground session.
- Primary control tray: joint Play/Pause, Audio, Timing, More. Per-feed
  transport is deferred, not promoted into this control space.
- Tapping the primary toggles ordinary controls; tapping empty viewing space
  reveals them too. They hide after four idle
  seconds while playing, but remain when paused or an adjustment panel is open.
  Audio and Timing replace one another rather than stacking.
- Overall volume scales the whole mix. The mix/fade slider changes relative
  contributions. Each feed has a mute that does not move the slider or
  redistribute gain; unmute restores its contribution. No per-feed volume
  sliders appear in the viewer.
- Mix arrows move one percentage point of slider travel per tap toward the
  labelled feed. Long press repeats every 150 ms after Android's long-press
  threshold. Release, cancellation, dismissal, Stop or teardown ends repeat.
  A Centre mix button resets balance without changing overall volume or mute.
- Timing names feeds rather than screen positions: Advance ABEMA relative to
  Twitch, or the inverse. A one-/five-second step selector delegates the exact
  requested step to the existing capability planner. Busy actions disable
  nudges. Readback is requested adjustment, never measured synchronization.
- More provides accessible primary swap, explicit per-live-feed catch-up,
  setup/diagnostics and Stop both. Catch-up retains the existing joint hold,
  anchor invalidation and explicit-Play requirement.

## Provisional choices and limits

The first fade curve is **centre-unity**, not constant-sum or equal-power:
centre gives both feeds equal gain; moving toward either side keeps that feed
at its overall gain and linearly attenuates the other. Endpoints select a single
feed. Defaults are overall 50%, mix centre, neither muted. Gains stay in 0–1,
but this is not a limiter or a promise about acoustic loudness/clipping. Curve,
repeat speed, floating size (32% of width) and idle timeout need listening/use
feedback. Device media volume remains separate from this app's overall gain.

The viewer is touch-first. Accessible buttons/slider descriptions exist, but
TV remote interaction and floating-video accessibility are not yet certified.
This diagnostic UI/status text is English-only; production localization is
deferred, with the view-local lint exemption recorded in code.
Layout state is in-memory, not saved across background/process death.
Orientation/screen-size changes are handled only by the new viewer Activity;
older diagnostic lifecycle remains unchanged. Other configuration changes,
background, Stop, errors and the five-minute test cap still terminate playback.

The new viewer uses two TextureView-backed Media3 surfaces to permit overlap.
That is a presentation experiment, not protected-frame capture. Verify ABEMA
output and concurrent decoding on the actual device before calling it working.
The original ABEMA WebView stays attached, nonzero-sized, behind the viewer;
the existing guarded joint Play pauses/mutes its original video. Returning to
setup exposes it without rebuilding the helper. No extra exchange, helper
algorithm, source route, token transfer, renewal or budget extension is added.

## Launch and verification

In the existing debug native access menu, choose a Viewer case. The standalone
debug entry is `net.fstab.tachiai.feature.diagnostic.NativePairViewerActivity`,
with `net.fstab.tachiai.extra.ABEMA_NATIVE_PAIR` set to one of
`LIVE_RELATIVE`, `REPLAY_RELATIVE`, `LIVE_REPLAY_RELATIVE` or
`REPLAY_LIVE_RELATIVE`. Other modes refuse. Use the established ABEMA playback
connection, let the original page/helper become ready, then Start. The viewer
appears prepared/paused; joint Play retains the original pause/mute gate.

An ordinary debug app-icon launch now opens this same native-access menu.
Explicit diagnostic intents still select their prior examples, and the menu's
Earlier browser presentation button preserves the old default presentation.
Release startup is unchanged. For the in-app floating-video layout, use
More → Landscape / PiP layout after preparing the viewer; the inverse menu item
returns to portrait/stacked layout. This requests Activity orientation without
changing system auto-rotation settings, rebuilding players or extending timers.
It is an in-app secondary video, not Android's separate system PiP window.

Pure JVM tests cover gain endpoints/centre/monotonicity, independent overall
scaling, mute restoration, fine-step limits, portrait sizing, swap geometry,
floating clamps, normalized rotation and strict case admission. Existing
playback/timing/helper tests continue to apply.

Device checks still needed: both moving videos/audio through portrait and
landscape, drag versus tap, swap without session/helper restart, mix arrows and
long-press cancellation, panel reopening, one-/five-second timing, safe insets,
catch-up/refusal readback, and Stop/background termination. Check the same
process and one initial exchange throughout rotation, not merely a plausible
screenshot. Record actual outcomes separately from these expected behaviors.

## October 7 build and phone results

Final APK SHA-256:
`ba363300030eaa3477a6e65316145158598ce2b2aae95ec0f2670f7b885d1195`
(16,675,283 bytes, host-owned). The pinned SDK 37.2 container passed 466 debug
JVM tests, lint and APK assembly. The unchanged browser helper passed its 60
fixtures. The shared debug signing certificate matched the documented identity
after every successful assembly. Scoped hooks/documentation build passed;
lint retains five earlier warnings and one earlier hint, with no new warnings.

The final APK updated the Pixel 6/oriole without clearing app storage. Android
17, WebView 153.0.8010.36, phone unlocked, system VPN marker present; the user
confirmed Proton Japan/ABEMA connection. ABEMA used existing guest/browser
state, without credential entry. Twitch used the existing same-device LOCAL
grant, validated by the existing preparation path. Playback acceptance does
not identify or certify the VPN exit or establish provider support.

Two `LIVE_RELATIVE` sessions rendered native ABEMA News and RelaxBeats. ABEMA
showed changing news/interview video; Twitch's mostly-artwork scene and changing
music captions rendered, while both normalized clocks reported advancing READY
playback. New acoustic confirmation was requested but not obtained in this
layout test; earlier mixed-audio evidence is not a new listening result.

- First run, PID 30619: one initial request at 14:29:33, keys at 14:29:34.
  Inspection consumed the small paused live window; Play refused at 14:30:11
  rather than resuming an expired point. Explicit ABEMA/Twitch catch-up at
  14:31:25/29 followed by Play at 14:31:30 restored both. Landscape at 14:32:33,
  floating tap-to-swap and return to portrait at 14:33:52 retained the process,
  advancing playback and one initial exchange. The five-minute cap ended at
  14:34:26, not a demonstrated license expiration.
- Second run, PID 1800: immediate joint Play after both READY at 14:37:09.
  Audio/Timing panels reopened/replaced each other without a parent-attachment
  error. A left-arrow tap changed mix 50 to 49; a 950-ms right-arrow hold
  changed 49 to 53 and stopped on release. Overall changed 50 to 25 without
  moving mix; ABEMA mute changed its button to Unmute without moving either
  slider. No volume refusal was displayed. Unmute, centre reset and overall
  50 restoration worked. These are UI/SDK request checks, not loudness tests.
- Relative +1, -1, +5 and -5 seconds at 14:40:06/08/11/13 each selected the
  existing A movement and returned REQUESTED. Both resumed advancing afterward;
  requested ledger returned to zero. This does not establish common-event
  alignment accuracy or exact acoustic timing.
- Landscape at 14:41:48 and a drag moved Twitch from top-right toward the
  middle-left while ABEMA remained primary; drag did not swap feeds. Playback
  continued with the same process/exchange. The cap ended at 14:41:58 before
  the follow-up dragged-pane/panel-avoidance screenshot, so that check remains
  pending. The primary/two-TextureView overlap itself rendered successfully.

Both sessions obeyed the unchanged five-minute preparation-start cap. Original
rotation settings (auto rotation off, portrait) were restored, and the debug
native-access menu was opened after teardown. Older diagnostic cases remain.
The first replay startup waited for the provider player behind the web prompt;
the new viewer's replay/mixed-type layouts have not yet been device-tested.
Other pending checks include long-press cancellation specifically on dismissal
and Stop, accessible/TV navigation, saved layout, listening to fade curves, and
making the large landscape adjustment panel more compact. Layout size/curve
choices remain provisional, despite the successful bounded rendering checks.

### App-icon startup and explicit landscape follow-up

The follow-up APK SHA-256 is
`124dfd400a465374c8cffa12b72666798caf77195849725cd85c0b1d6133c69f`
(16,675,283 bytes). The same pinned build environment passed 471 debug JVM
tests, lint and assembly, with the same earlier lint findings. APK signing
verification matched the shared debug certificate before installation.

On the same unlocked Pixel 6, Android 17 and WebView 153.0.8010.36, with the
system VPN marker present and existing guest/local authorization state, a
force-stopped ordinary MAIN/LAUNCHER launch opened the native-access menu
(`route=NATIVE_ACCESS`). No provider login was entered. Viewer · LIVE prepared
ABEMA News and RelaxBeats; joint Play was requested immediately after both
reported READY. More → Landscape / PiP layout at 15:05:57 rendered ABEMA primary
with Twitch floating at top-right. PID 8277 was retained, with no intervening
pause/destroy lifecycle marker; both normalized content clocks kept advancing
and reported playing through 15:06:45. System auto-rotation remained off. This
checks the explicit layout entry while auto-rotation is disabled, not a new
acoustic result, exact synchronization result or replay-layout test. The
inverse portrait menu entry has not been separately exercised in this build.

### Portrait background tap follow-up

The viewer now handles empty-space taps at both the stage and outer viewer
levels, revealing controls without intercepting child buttons, sliders or
floating-pane gestures. The updated APK SHA-256 is
`f1be43dc87cf5f2d121963d9f90248306880285ae86c497fb74ef7f3ac42bb2a`
(16,675,283 bytes). All 471 JVM tests, lint and assembly passed; lint retained
its five earlier warnings and one hint. The app and instrumentation APKs matched
the shared debug certificate and were installed without clearing app storage.

Two focused Android instrumentation tests passed on the Pixel 6/Android 17:
portrait empty stage below both panes and the reserved footer outside the
stage each revealed a hidden control tray. These use the actual viewer in an
attached blank fixture Activity, with no provider pages, authorization, media
or VPN requirement. The first unattached test fixture failed because its queued
click did not execute; it was corrected before the passing run. This is touch
dispatch evidence, not a new provider-playback or acoustic test. Scoped hooks
and documentation build passed. The general mise invocation hit an unrelated
tool-lock/install error; explicitly selecting the installed pre-commit tool
ran the same scoped checks without changing tool configuration.
