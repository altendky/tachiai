# Chromecast hardware acceptance

**Deferred by the user on 2026-10-18. No physical Chromecast trial has run.**
Keep the associated PR in draft until hardware testing is explicitly resumed
and the evidence below is recorded. Passing CI or emulator checks does not
complete this record. Receiver identification and phone access are not needed
while this work is deferred.

This record consolidates the remaining work from
[the casting plan, #303](https://github.com/altendky/tachiai/issues/303), and
[hardware acceptance, #305](https://github.com/altendky/tachiai/issues/305).
Their closure as superseded does not establish working Chromecast support.

## Delivered prerequisites

- [#306](https://github.com/altendky/tachiai/pull/306): ordinary source-picker
  **More options → Screen sharing**, with Google Home/public Android Cast
  settings guidance. Sharing is established before Watch; launching settings
  does not report a connected receiver.
- [#311](https://github.com/altendky/tachiai/pull/311): the
  [controlled two-video/audio fixture](controlled-mirroring-fixture.md), real
  local players and bounded observation procedure. Its emulator decoding,
  control, timing, runtime-build-gate and cleanup checks passed.
- Baseline at deferral: `438c3546c4b49bd5b8dae9f6574ab8a30d6b32a0`.
  Recheck current main when resuming. Rebuild matching ordinary-debug and test
  APKs using the [development/signing guide](android-development.md); temporary
  local casting builds are being discarded rather than reserved for this trial.

The [casting scope](casting.md) remains screen mirroring of the complete
presentation. Provider requests, accounts, routes, both players, composition,
mix and timing remain on the phone. No media-URL handoff, private Google Home
entry point, app-managed capture/encode/relay or security relaxation is planned.

## Trial inventory

Fill this in from the actual trial. Do not record credentials, cookies, grants,
signed URLs or protected recordings. Describe account/entitlement state without
identifying the account.

| Condition | Actual value |
| --- | --- |
| Date, app revision and ordinary build variant | Not recorded |
| Source phone, Android version and native/browser engines used | Not recorded |
| Receiver model and firmware | Not recorded |
| Google Home version and whole-screen/app-only sharing mode | Not recorded |
| Network topology, local discovery and selected provider routes | Not recorded |
| Provider region, account and entitlement state | Not recorded |
| Source/content identities and live/replay combination | Not recorded |
| TV/display, audio chain and secure-output conditions | Not recorded |

## Required observations

Use the fixture's documented whole-screen sharing procedure first. Its
instrumentation can restart the target process; it does not establish app-only
sharing lifetime or the product Activity's rotation behavior. Its low-resolution
samples are a composition probe, not full-resolution performance acceptance.

| Check | Evidence and outcome |
| --- | --- |
| Both independent moving fixture panes reach the TV | Not run |
| Both audio tracks, each mute and balance/fade reach the TV | Not run |
| Swap/layout and timing commands affect receiver output | Not run |
| Output/interaction delay, A/V delay and relative drift | Not measured |
| Sustained quality, frame drops, thermals and battery | Not measured |
| Authorized ABEMA/Twitch live/replay presentation in an ordinary build | Not run |
| Provider traffic retains selected routes without direct fallback; local sharing remains reachable | Not run |
| Stop, disconnect/reconnect, panels and rotation | Not run |
| Interruptions, Home/background and screen lock | Not run |
| Screen-awake behavior stays limited to video use | Not run |
| Protected account/login windows and decoder/DRM output restrictions | Not run |

Agree measured quality thresholds before claiming a quality pass. SDK rendered
buffers, requested gains and requested timing are separate from TV pixels,
audible output and measured synchronization. Record unsupported lifecycle
conditions explicitly: the current viewer disposes playback on pause.

Distinguish mirror-link failures from provider/network failures tracked in
[#297](https://github.com/altendky/tachiai/issues/297) and renewal behavior in
[#294](https://github.com/altendky/tachiai/issues/294). If a defect is confirmed,
make a bounded fix and rerun the affected observations. Preserve session
ownership, route/identity checks and ordinary security policy; broader capture
or background-playback architecture needs a separate plan.

## Completion record

Pending. Replace the unrun entries with named-device evidence and describe the
supported conditions, limitations and remaining defects before taking the PR
out of draft. If a platform/provider restriction prevents acceptance, record
the evidence and required product decision instead of treating CI as success.
