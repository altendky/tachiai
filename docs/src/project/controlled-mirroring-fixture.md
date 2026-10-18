# Controlled media fixture for mirroring preparation

Prepared for [Chromecast hardware acceptance, #305](https://github.com/altendky/tachiai/issues/305).
This is a provider-free test fixture, not a Chromecast implementation or a
completed receiver acceptance report. Physical testing still requires the
explicitly identified and authorized phone/receiver pair. No provider media,
account login, grant, route, external URL, screenshot or recording is used.

## Scope and implementation

`ControlledMirroringDeviceTest` lives entirely under `src/androidTest`, along
with both MP4 assets and their SHA-256 manifest. It runs against the ordinary
`debug` application and refuses the local inspection variant. Nothing adds an
activity to a production/debug manifest or changes window, DRM, network or
capture policy. The existing blank `androidx.activity.ComponentActivity` host
comes from the debug UI-test manifest dependency.

Two actual Media3 players decode locally generated, unprotected H.264 baseline
640×360/24-fps video with mono AAC audio. Each 60-second clip loops. A has a
440-Hz tone pulse at each second; B has an 880-Hz pulse shifted by half a second
and a different video hue. Both have moving test patterns, frame counters and
time counters rendered into the video. The samples were generated for this
fixture; no third-party movie or provider content is included. They are not
1080p/4K, protected-media or network-throughput acceptance samples.

The fixture inflates the production `native_viewer_player` TextureView layout
and uses `NativePairViewer`, `PrototypePlaybackController`,
`NativePlaybackAudioGroup` and the existing Rust-backed seek planner. Its narrow
`NativePairMember` adapter observes real player clocks, readiness, gain and SDK
rendered-output-buffer counters. Its `AssetDataSource` only opens the fixed
test-APK assets; it cannot silently fall back to a network media request.

The persistent header always exposes **Stop fixture** and numeric local-player
readback. Stop, Back, the observation deadline, playback failure, Activity
pause/stop/destruction and test-finally cleanup all end this owned fixture.
Cleanup attempts every player and the shared audio-focus owner even if an
earlier cleanup operation fails. Android releases process-owned media resources
if the process is killed; no fixture service or separate background worker is
created. Rotation/background deliberately ends the blank-host fixture rather
than claiming the primary app's in-place rotation or background behavior.
In particular, More → Landscape/Portrait can recreate this host and terminate
the run through `ON_PAUSE`; unlike the product viewer it has no in-place
orientation handling. Swapping panes within the current orientation is still
available. Actual product rotation remains a separate acceptance check.

The fixture reads no application configuration and mutates no saved providers,
accounts or routes. Starting instrumentation can restart the target process
and end an existing viewer, so finish ordinary playback before running it. Do
not clear application data, uninstall the application or run unrelated/resetting
test classes on a persistent development device.

## Local preparation and media provenance

Follow the existing [Android build/signing guide](android-development.md) for
the ordinary debug application and its matching debug Android-test APK. Verify
both artifacts and use an in-place update under the existing shared debug
certificate. Install only on the explicitly selected development device; a
phone trial requires the user's physical-device authorization. A matching
ordinary-debug APK is essential: an inspect APK is not an equivalent baseline.

The reproducible encoding recipe is `scripts/controlled-mirroring-media.py`.
Normal invocation verifies the committed files without rewriting them:

```sh
uv run scripts/controlled-mirroring-media.py
```

Explicit regeneration requires local FFmpeg with `drawtext`, `testsrc2`,
`aevalsrc`, `libx264` and AAC, plus FFprobe. It uses a locally installed
FreeMonoBold font; the font file is not bundled. Example:

```sh
uv run scripts/controlled-mirroring-media.py --generate \
  --ffmpeg /path/to/ffmpeg --ffprobe /path/to/ffprobe \
  --font /usr/share/fonts/truetype/freefont/FreeMonoBold.ttf
```

The generated `src/androidTest/assets/controlled-mirroring/media.json` records
the encoder version, exact byte counts, SHA-256 values and controlled media
properties. Regeneration with another encoder/font version may produce
different bytes; review the clips and matching manifest as one change.
Generation probes both output files for video/audio codecs, dimensions and
duration. Instrumentation verifies the manifest hashes before opening a host.
No encoder or font is installed automatically, and no media is downloaded.

## Automatic provider-free checks

After verifying the device identity and installing matching APKs, select a
single test method explicitly. The serial below must be the verified device,
not the first ADB entry. These commands are instructions, not a record that
the tests have run:

```sh
adb -s "$VERIFIED_DEVICE_SERIAL" shell am instrument -w -r \
  -e class 'net.fstab.tachiai.feature.presentation.ControlledMirroringDeviceTest#twoLocalPlayersRenderAdvanceAndApplyViewerMixTimingThenStop' \
  net.fstab.tachiai.test/androidx.test.runner.AndroidJUnitRunner

adb -s "$VERIFIED_DEVICE_SERIAL" shell am instrument -w -r \
  -e class 'net.fstab.tachiai.feature.presentation.ControlledMirroringDeviceTest#finishingTheHostStopsAndReleasesBothActualPlayersOnPause' \
  net.fstab.tachiai.test/androidx.test.runner.AndroidJUnitRunner
```

The first check requires two READY players, increasing content clocks and
increasing rendered-buffer counters. It uses real viewer mute/balance/centre
controls, checks actual player gains, requests a one-second relative timing
change through the production controls and waits for real paused seek
settlement. It then presses the visible Stop button and verifies both actual
players were released. The second finishes only the owned host Activity and
verifies real `ON_PAUSE` cleanup after both players have rendered.

These checks establish neither acoustic audio output nor a visible image on a
receiver. A rendered buffer count is SDK decoder evidence, not a screenshot or
a measurement of the TV. Timers also bound preparation and the automatic run.
The manual observation test skips by default.

## Bounded manual mirroring observation

For the first controlled receiver trial, use **whole-screen sharing**, started
in Google Home before invoking instrumentation. The test launches the existing
blank host with `NEW_TASK` and does not clear the app's task, but the
instrumentation command itself can restart its target process. It therefore
cannot promise preservation of an already selected app-only sharing task.
App-only sharing needs its own lifetime experiment; do not report it as tested
by this procedure.

1. On the authorized unlocked phone, use Google Home to choose the named
   Chromecast and **Cast my screen**. Choose whole-screen sharing for this
   fixture. Verify the intended display is receiving before proceeding. Avoid
   unrelated notifications or credential pages while sharing.
2. Run the following command with an explicit observation time from **1 to
   600 seconds**. The timer begins after local media is READY; preparation has
   its own bounded limit. Stop or lifecycle changes may end the run earlier.

   ```sh
   adb -s "$VERIFIED_PHONE_SERIAL" shell am instrument -w -r \
     -e class 'net.fstab.tachiai.feature.presentation.ControlledMirroringDeviceTest#manualControlledMirroringObservation' \
     -e controlledMirror true -e controlledMirrorSeconds 120 \
     net.fstab.tachiai.test/androidx.test.runner.AndroidJUnitRunner
   ```

3. With the fixture visible on the phone and TV, observe both independent
   moving counters, swap panes where available, use Audio to mute each source and
   alter the balance, and use Timing for a visible relative shift. Distinguish
   requested timing from observed clocks and listening results. Use the phone's
   normal system volume control if needed; the test does not change it.
4. Press **Stop fixture**, or allow the deadline to expire. Confirm playback
   stops. Stop mirroring using Google Home or Android's sharing controls. The
   fixture does not own or stop the system mirroring session.

Instrumentation reports only fixed phase/termination labels and numeric
local-player measurements. A player/fixture error fails the manual test rather
than being reported as successful observation. An orderly user/background stop
is merely termination, not proof that all physical acceptance criteria passed.

Record the exact phone, OS, ordinary-debug revision, receiver firmware/model,
display/audio chain, sharing mode and bounded observations in the
[casting evidence](casting.md). #305 remains open until actual receiver output,
audible mix, playback/routing and lifecycle requirements are evaluated. This
local replay fixture cannot establish provider entitlement, protected surface
capture, source-route coverage, live-window behavior or latency by itself.
