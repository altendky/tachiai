# Controlled two-feed browser fixture

This is a provider-free controller milestone under
[#368](https://github.com/altendky/tachiai/issues/368), which remains open.
The diagnostic page at `/harness/pair.html` uses the existing owned HLS/DASH
fixtures, two independent Shaka players and the production Rust/Wasm timing
facade. It is not the published product client or an approved permanent web UX.

## Implemented scope

Use the single controlled-source list's independent A/B checkboxes to assign
each feed, including duplicates, then explicitly
Watch. Watch requests playback as each selected feed becomes ready. No extra
Play step is assumed; an autoplay refusal remains a closed `blocked` result.
Each slot owns its own player and lifetime. The other ready feed can play while
one selected fixture is unavailable.

The controller follows the Android viewer's current pure presentation rules:

- Initial overall volume is 50%, balance is centred and neither feed is muted.
  The centre-unity mix retains the favoured feed's overall gain while attenuating
  its partner. Muting does not move balance or redistribute gain; unmuting
  restores that feed's contribution. Mix arrows move one percentage point.
- A starts as landscape primary. The secondary starts at the top right, at
  32% of available width capped by 65% of available height. Dragging, tapping
  to swap and viewport changes update geometry without replacing media elements
  or players. Portrait always places A above B, fitted using observed ratios.
- Joint Play/Pause controls ready feeds. Relative timing provides the common
  Advance A/B 0.1/0.25/0.5/1/5-second steps. Each operation holds both and leaves
  them paused, uses the existing Rust planner, prefers the named feed forward,
  and considers the partner backward only before dispatch. Short clamping is
  refused; an uncertain dispatch or settlement never tries the opposite feed.
- A 100 ms request has a 50 ms settlement tolerance; larger full steps use at
  most 100 ms. A `seeked` event at an unchanged clock cannot settle 100 ms.
  Requested adjustment is recorded when the selected seek dispatch is accepted,
  before settlement; failed or timed-out settlement retains that request ledger.
  It is not measured inter-stream synchronization.
- Retry replaces only the failed slot after confirmed disposal, retains its
  selected fixture and the partner, restores a known replay position where a
  full seek is available, and preserves mix, mute and geometry. Recovery marks
  the previous timing anchor invalid. Current Play intent is rechecked against
  the new slot; a retired session's asynchronous result cannot own its successor.
- Stop, Sources and hidden/page-retirement signals dispose both slots. Uncertain
  cleanup blocks a replacement presentation. A late load, play or seek completion
  cannot publish an old presentation's state into its successor.

The page exposes the verified Sources, joint transport, Audio, Timing, More and
Hide subset as diagnostic controls. Back/Escape unwinds menu, panel, toolbar and
viewer in that order. A playing viewer without a panel/menu hides its toolbar
after four idle seconds; a panel or pause keeps it available. More contains
only primary swap and safe playback observations. It does not advertise fake
Providers, Routes or other unimplemented operations.

## Run and verification

Use the [web harness build procedure](../../../apps/web/README.md), then visit
`/harness/pair.html`. The production build includes both diagnostic pages; the
existing single-player harness and its checks remain available at `/harness/`.

From `apps/web`:

```sh
npm run build
npm test
```

Browser regressions exercise actual controlled decoding and the generated Wasm,
duplicate-source independence, Watch playback, the common gain curve and mute
restoration, retained geometry, all timing steps, full-step refusal, one-dispatch
failure, independent retry, teardown and stale completion. Adversarial event and
cleanup fixtures supplement actual media tests; they are not provider evidence.
Tests use a fresh ordinary Chrome context and retain no screenshots, video or
traces. The 60-second fixtures contain H.264 video and AAC audio; fixture A has
a 440 Hz tone and fixture B an 880 Hz tone. Advancing clocks, decoded frames and
gain readback are distinct from acoustic confirmation.

### Buffered format observations

The existing Playback status includes independent `playheadFormats` for each
feed, separate from selected SDK track IDs and requested quality. The pinned
Shaka player enables `streaming.observeQualityChanges` and projects its
`mediaqualitychanged` events into bounded video dimensions/frame rate, audio
channel count/sample rate, declared bitrate and closed codec names. Unknown or
invalid numbers are omitted; unknown codecs become `OTHER`. No raw SDK format,
track/representation identifier, label, language, role, URL, token or DRM data
enters these new fields. Declared bitrate describes the stream's metadata; it is
not measured transfer throughput or a bandwidth estimate.

The `last-sdk-buffered-playhead` basis means Shaka observed a format associated with
buffered content at its polled playhead. These are the last such observations,
not decoder callbacks or proof of continuous consumption. They can lag, remain
unchanged while paused, or be absent when that playback path supplies no quality
events. Selected tracks are never substituted for missing observations.
The optional `observedAtPositionMs` is that event's bounded media-timeline
position, not a shared clock or a freshness guarantee. An unbuffered seek/gap
does not erase the last observation: Shaka need not emit a new event when the
same format becomes buffered again.
The controlled HLS trial reports AAC and channel count but omits sample rate and
video bitrate; the DASH trial supplies them. Known fixture properties and an
aggregate HLS variant bitrate do not fill those gaps.
Independent decoded-frame checks establish video dimensions separately; neither
mechanism establishes decoded audio, audible output or acoustic quality.
Failure, disposal and replacement clear observations, and retired SDK callbacks
cannot repopulate a failed/disposed session or its successor. Mix/mute, pane
movement and swaps retain the observations with their original player.

## Remaining acceptance

Full responsive control-dock/Fit video behavior, Quality preferences, manual
orientation, complete product picker/settings/navigation,
browser history integration, provider accounts/routes and other full-client
features remain pending. This fixture neither removes those requirements nor
approves a platform-specific substitute. Any concrete UX deviation still needs
the explicit discussion required by the common design instructions.

Buffered-format observations establish only the Shaka mechanism above. Separate
video/audio preference controls, multiple-rendition switching and actual-provider
format/decoder behavior remain unverified; this does not complete Quality
acceptance or promise independent choices for coupled audio/video streams.

Actual ABEMA/Twitch live/live, live/replay and replay/replay combinations,
provider-bound duplicate sessions, DRM, complete route coverage, entitlement,
renewal and sustained two-feed playback remain separate gates. Synthetic
retirement events establish controller cleanup dispatch, not every operating
system/browser background or process-loss outcome. No acoustic output, physical
device, provider content, account state or region was tested by this fixture.
