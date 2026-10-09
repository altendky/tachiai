# Product-flow prototype

## Audio-first adaptation follow-up

The agreed first step is correct native transfer measurement, independent
per-player bandwidth estimates and audio-first track selection. The subsequent
user-approved default is video Auto, without the provisional 1280 × 720 maximum.
Audio preference must not depend on pane size, primary/floating role, mute or
mix volume. Video remains independently
adaptive within the provider's available tracks and device capabilities.
Auto retains Media3's physical-display viewport and device constraints alongside
bandwidth/buffer adaptation. It does not force the highest quality, infer each
pane's size or establish external-TV/casting behavior. 1080p/4K can be eligible
when the source, display and decoder support them; this is not a device-tested
TV or 4K capability claim.

Use Media3's supported audio ranking (including language, role, codec and device
constraints), with multiple simultaneous adaptive selections disabled: when
video is present, retain a fixed best-ranked audio selection while video adapts.
This does not manufacture better audio or separate bundled audio/video. A
single audio track has no quality alternatives; an audio-only source may still
adapt. This is an audio preference, not an audio continuity guarantee under
insufficient bandwidth or a global highest-bitrate/video preference.

Transfer lifecycle callbacks must preserve all existing request policies,
deadlines, byte limits, cancellation, route ownership and token-safe failures.
Diagnostics expose bounded numeric track characteristics and bandwidth
estimates only, never provider URLs, IDs, labels, tokens or raw Format objects.
Controlled provider-free tests must show video reduction/recovery, fixed audio
selection and isolated measurements; actual provider switching remains a
separate device observation.

The implementation uses the pinned SDK's [transfer lifecycle](https://developer.android.com/reference/androidx/media3/datasource/BaseDataSource)
and [track selector policy](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/trackselection/DefaultTrackSelector.java).
More → Playback status reports actual input video/audio formats separately from
eligible/selected adaptive tracks, a per-player bandwidth estimate and bounded
track alternatives. "Samples observed" means callbacks reached the meter;
short samples can still leave its startup estimate in use. No provider
identifier, language, label or raw metadata is displayed or logged.

Per-pane/render-target policy and coordinated shared-route
budgets follow later. Auto does not restore the removed 720p preference.
Android TV, mirrored displays, remote casting receivers and future non-Android
players need platform-specific render-target information; phone screen size
must not silently define every target's video quality. Generic preferences and
reported capabilities remain separate from platform/player implementation.

### October 8 quality verification — before Auto

The pinned build passed 1,024 JVM tests, instrumentation compilation, release
isolation and APK assembly; lint reported zero errors, ten warnings and one
hint. Both APKs matched the shared debug signing certificate. Scoped repository
hooks, including secret scanning and the documentation build, passed.

On Pixel 6, Android 17/API 37, all eleven provider-free instrumentation tests
passed: transfer lifecycle/error/cancellation accounting, independent meters,
fixed supported audio selection and controlled video downshift/recovery. The
selector fixtures require a Looper-backed thread, matching real ExoPlayer;
their first runner-thread attempt failed before assertions during spatializer
initialization. The corrected fixtures do not disable device constraints.

A bounded native live run of Twitch Virtual Japan and Chillhop Radio on their
configured System-network route, using the existing saved device grant,
showed both players running. Actual input video changed from 852 × 480 to
1280 × 720 AVC, with independent sampled bandwidth estimates. More → Playback
status displayed numeric current formats and alternatives. Stereo AAC remained
reported at 48 kHz and 44.1 kHz respectively; bitrate was unavailable and shown
as unknown. These manifests exposed one audio track each, so this is not
evidence of choosing between real-provider audio qualities. Region/exit and
Turbo/subscription effects were not verified. Controlled bandwidth reduction,
TV/casting behavior and acoustic quality remain separate checks.

An additional bounded native ABEMA News + Twitch Chillhop live run used the
existing imported Proton Japan route for ABEMA and System network for Twitch.
Fresh guest licensing reached ready, both players reported playing and video
frames, and ABEMA input video changed from 854 × 480 to 1280 × 720 AVC. ABEMA's
status offered AAC alternatives at 192/128/64 kb/s, selected only the 192 kb/s
option, and reported consumed stereo AAC at approximately 190 kb/s/48 kHz.
Twitch remained stereo AAC/44.1 kHz with bitrate unavailable. The two meters
reported separate samples. Swapping primary/floating roles retained the reported
audio formats and both players' playing state. This observes the existing guest
live adapter, not new DRM handling, a replay test or a verified account/region
entitlement.

### October 8 Auto verification

After removing the video maximum, the same pinned build passed 1,024 JVM tests,
lint (zero errors, ten warnings, one hint), release isolation and both APK
assemblies/signature checks. The verified debug APK updated the phone in place.
All twelve provider-free instrumentation tests passed on Pixel 6/Android 17:
the production policy retains the SDK's physical-display viewport default;
owned 1080p and 4K viewport/capability fixtures permit those video selections,
and controlled 1080p downshift/recovery retains fixed supported audio.
The 4K fixture is a selection test, not actual 4K decoding or TV verification.
Scoped repository hooks and the documentation build passed.

In a bounded live check with the same device, saved Twitch grant and configured
ABEMA Proton Japan/Twitch System routes, Twitch Chillhop consumed 1920 × 1080
AVC with unchanged stereo AAC/44.1 kHz. Initial ABEMA preparation failed with
the generic preparation category; its cause was not established. A single
retry completed fresh guest/helper/CDM preparation and rendered native News
alongside Twitch, initially at 854 × 480 AVC with approximately 189 kb/s stereo
AAC/48 kHz, then at 1920 × 1080 AVC/4 Mb/s with the same audio format. Both
native players reported playing at 1080p. No provider login, route settings or
licensing behavior was changed.

## Hard-failure recovery

Unconfirmed playback or route cleanup blocks **Open viewer** and presents one
recovery dialog while the prototype Activity is resumed. The dialog names a
closed recovery category, explains the blocked operation and offers **Ignore**
and **Restart**. Ignore acknowledges the incident; it never clears the cleanup
guard or permits another viewing session. **Recovery options** remains available
on the picker, along with Routes and readable Providers setup. Ordinary route
shutdown temporarily disables playback without raising a hard-failure incident.
The first incident persists across Activity recreation; background dismissal is
not an acknowledgement and repeated callbacks do not open overlapping dialogs.

Restart writes a bounded private, one-use picker checkpoint, launches the normal
main menu and terminates only the originating dedicated prototype process using
Android's [process termination API](https://developer.android.com/reference/android/os/Process#killProcess(int)).
It does not clear saved routes, provider instances, authorization, cached bundles
or diagnostics. The next fresh prototype restores selected, cleared and stale
instance choices without starting playback. A malformed checkpoint leaves both
feeds unassigned and requires recovery rather than selecting default identities.
Checkpoint or main-menu launch failure keeps the process alive and playback
blocked with fixed safe feedback and a bounded `RECOVERY_RESTART` diagnostic
observation. Neither raw exception messages nor provider
URLs enter the dialog. This recovery presentation is independent of the
[failure journal](failure-diagnostics.md) and does not repair the underlying
cleanup failure itself.

October 9 verification used the owned disposable Android 16/API 36 x86-64
emulator, with no provider playback or route connection. Fifteen JVM cases,
thirteen picker cases (including a 240 dp recovery viewport) and ten actual
dedicated-process cleanup/recreation cases passed. A System UI ANR initially
held input focus; after closing that system error, the unchanged interaction
tests passed. This was a test-device condition, not a playback recovery result.

A separate ordinary-app run used an intentionally malformed private checkpoint
to trigger the real dialog on a 640 × 320 landscape screen. Ignore kept recovery
active; scrolling allowed replay A to be chosen while B stayed unassigned.
Restart removed the old prototype PID, preserved the main PID and returned to
the main menu. A fresh prototype PID restored those choices and consumed the
checkpoint. Synthetic encrypted route, provider and authorization records and
an owned cache-state sentinel kept identical checksums; subsequent store reads
and decryption passed. This checks shared recovery/process behavior without
using real grants, provider content or the persistent development device for
failure injection.

## Current setup vocabulary and navigation

### Manual quality preferences

**More → Quality** opens per-feed video and audio choices inside the viewer.
**Use stream default** inherits that stream's saved choice; **Auto for this
feed** explicitly overrides it for this session. Supported manual choices name
only numeric resolution/bitrate/channel/sample-rate properties and a closed
codec name. Audio and video have separate preferences. **Save stream default**
persists the effective choice only for that stream and media kind; **Reset stream
default** restores that kind's Auto default without removing either feed's
session override. Resetting a feed means choosing **Use stream default**.

Precedence is feed override → saved stream default → Auto. Two copies of one
stream share saved defaults and retain independent feed overrides. Saving or
resetting a stream default updates currently inheriting copies. Stop, Sources,
background disposal and a new viewing session discard feed overrides. Saved
defaults use a separate bounded encrypted record with process/file locking;
they do not rewrite routes, legacy stream settings or authorization. An unreadable
record blocks setup and is never silently replaced with Auto.

Manual requests resolve exact numeric descriptors within the currently selected
content-equivalent SDK group, using supported tracks and retained video/audio
and physical-viewport constraints. They do not select another language, role or
camera group. Unknown codecs, incomplete dimensions/audio properties and
unsupported choices are unavailable. Track changes rebuild runtime overrides;
missing or ambiguous preferences stay visible while the SDK uses Auto. A single
audio track offers no separate quality ladder. Bundled audio/video can be coupled;
the UI does not promise independent changes merely because two controls exist.
Auto retains the prior audio-first ranking, adaptive video, device constraints
and separate per-player bandwidth meters.

The panel distinguishes requested preference and selection status from actual
consumed formats, which can lag or differ. Saving is explicit and failures retain
the previous stored record. Controls pause during a save or timing transaction,
and remain scrollable at compact sizes. No SDK group, format identity, provider
metadata, token or URL enters stored preferences or UI. This adds no provider
endpoints, reload, authorization, media-origin permission or DRM handling.

Provider-free model/store, selector, lifecycle, redaction and UI fixtures accompany
this implementation. Local Android JVM tests, lint, instrumentation compilation,
release isolation and both APK assemblies passed; both certificates matched
the shared debug identity. All 30 quality/selector and affected viewer UI tests
passed on a separate clean Android 16/API 36 x86-64 emulator. The existing
bandwidth isolation fixture now pins its SDK network type before sampling,
retaining the real meters and transfer assertions while avoiding asynchronous
initial-estimate resets. Scoped repository checks and documentation build passed;
no actual provider manual-switching, new audio observation, TV, casting or
physical-device performance result is claimed here.

The native viewer's Timing panel has two direct-action rows, **Advance A** and
**Advance B**, each with 0.1 s, 0.25 s, 0.5 s, 1 s and 5 s buttons in that order.
A press immediately requests that feed's relative advance; there is no shared
step selector. Descriptions name both sources and the duration, and directional
focus follows the rows. All steps are disabled while an adjustment is busy or
two playable feeds are unavailable. Feedback still reports requested timing,
not measured synchronization. Compact layouts retain scrollable controls and
48 dp button targets. Cancelling More preserves the open controls and panel;
Hide and video/background taps retain their explicit dismissal behavior.

The product terms are **route / provider / stream / feed**. The picker assigns
streams to A/B feeds; its separate **Routes** and **Providers** buttons open
network configuration management and provider configuration respectively.
Provider type is separate from a configured instance: multiple named ABEMA or
Twitch instances may each choose their own route, and Twitch instances have
separate saved LOCAL logins. The default Twitch instance retains the original
grant without copying it. The picker assigns each feed a stream and instance
together; duplicate streams remain independent playback sessions. Simultaneous
ABEMA instances must use the same canonical route because their WebView proxy
override is shared; incompatible routes block the run before any route starts.
The earlier per-stream setup buttons are no longer in this flow.
Imported profiles and provider configuration are preserved. Obsolete four-stream
settings are not migrated: the picker offers an explicit stream-settings reset
before playback. Current-format ambiguous defaults/overrides require explicit
provider review, with no fallback.
The cached native prototype now connects imported WireGuard/HTTP CONNECT routes
at Open viewer, without automatic System-network fallback. See
[route and provider setup](connection-import.md) for implementation scope and
verification limits. Historical observations below retain their original terms.

## October 7 direction

The historical experiment home keeps its existing cases and gains a separate
Prototype · choose two sources entry. This is a first attempt at the product
workflow, not another enumerated diagnostic case. Its unsupported playback
adapters still ship only in the regular debug APK; this does not settle release
distribution, provider permission or durable playback.

The first catalogue contains ABEMA News live, the previously tested free sumo
replay, Twitch Izgonnabemei live, and the previously tested Rocket League replay.
Service, playback kind and source identity are separate from viewer slot A/B.
Both ordered slots accept any catalogue entry, including identical choices;
duplicates own independent playback hosts. Future service/channel selection
can grow the catalogue without redefining the presentation as ABEMA + Twitch.
Izgonnabemei replaces RelaxBeats only in this new flow. Its acoustic balance and
live availability are not assumed; overall/mix controls remain available.

Open viewer is the explicit start intent. It prepares the two feeds, chooses
each live SDK default once before establishing an alignment anchor, confirms
any original ABEMA web players paused/muted, and requests joint native playback
in the landscape floating-video layout. There are no diagnostic Start/Play
steps in this startup workflow. The original web-startup comparison may still
need a provider-consent tap; the no-page runtime has no consent overlay.
Subsequent Play/Pause, overall/mix/mute, timing, swap and drag reuse the generic
viewer. Slot labels remain distinct even when both sources are identical.

## October 8 channel choices and independent feed errors

The catalogue now also offers Twitch Chillhop Radio and Virtual Japan live,
alongside Izgonnabemei. These are fixed public channel choices; availability is
determined during preparation, not assumed from the catalogue.

Open viewer immediately presents two labelled panes. Each starts with a
preparation message and independently becomes video or a scrollable error.
A successful feed starts without waiting for the other feed. A failed feed
closes its own session while a healthy feed continues. If both fail, the viewer
keeps both errors visible. Sources or Android Back explicitly stops playback
and returns to the picker, retaining the selected sources.

In landscape, tapping a floating preparation/error pane swaps it with the
primary feed, just as tapping floating video does. Taps on either the message
or empty panel space use the same action. Scrolling a long error only scrolls
its text. Swapping retains the existing players; it does not restart or retry
a feed. The More menu also offers Swap primary feed. Portrait retains its
fixed A/B order, and tapping an error pane toggles the controls.

The older full-page ABEMA comparison waits until its original web player can
confirm paused/muted, or its slot fails and closes, before starting native
playback. This prevents audio from a hidden provider page during preparation;
the default cached flow has no original provider page to wait for.

Errors retain the first known cause, using fixed messages and bounded HTTP or
player codes. HTTP 404 says that the stream was not found and that a live
channel may be offline; it does not assert that offline status was verified.
Login, network-policy, licensing, unsupported media and preparation failures
have distinct messages. Provider response text, exceptions and token-bearing
URLs never enter these panels.

Play/Pause and audio work with a single ready feed. Relative timing and joint
catch-up require two playable feeds. A late second feed still passes its
original-player pause checks before joining playback. Background, Stop and the
unchanged shared five-minute deadline stop the presentation. A cleanup failure
also stops both feeds because continued playback cannot be confirmed safe.
This change adds no authentication, DRM exchange, retry or network permissions.
Device playback and visual verification of this follow-up remain pending; no
phone installation was performed for this change.

## Routed playback cleanup

Safe diagnostics on `tachiai-dev` (Android 16/API 36, x86-64) captured a
main-thread `ILLEGAL_STATE` at routed HTTPS response closure after backgrounding
cached ABEMA live playback. Native-host cleanup then became unconfirmed and
blocked another viewer request. Two earlier Back cleanups completed normally.
Twitch's separate `MEDIA_NOT_FOUND` observation does not establish a cleanup
cause. Saved routes, authorization and cached bundles were preserved; ABEMA
used the existing anonymous guest path. Region and saved Twitch account state
were not revalidated for this bounded experiment.

A real synthetic TCP/TLS fixture reproduces a cancelled body read racing
response closure with an `Unbalanced enter/exit` exception. Routed body reads
and closure now share ownership, with cancellation sent before waiting for the
reader. Ownership waiting is limited to one second per connection; a timeout
remains an unconfirmed cleanup failure and leaves the connection available for
cleanup retry. The limit is per connection, so pathological waits can accumulate.
No TLS, credential, redirect, origin or fallback acceptance changes are added.

Media teardown attempts all owned request groups, player and quality releases
even when an earlier step fails. The first failure is still rethrown, later
failures remain associated, and cleanup admission gates are retained. The DASH
opaque-exchange waiter is sealed before the other release attempts. Repeated
close calls remain idempotent. Recovery dialogs are separate work.

The tested follow-up APK completed two ordinary background cleanups after
ABEMA reached native `READY`/`VIDEO_FRAME` on the same emulator, and a new viewer
request was admitted between them. The journal retained the old failure evidence
and recorded no new cleanup failure in those two runs. An earlier guest
preparation attempt failed before native playback and succeeded on one normal
retry; its cause was not established. Four focused disposable-emulator tests
passed, including actual HLS/DASH player release after injected transport
failures and diagnostic retention. These observations do not qualify phone
playback, audio, performance or provider reliability.

## Source assignment follow-up

The subsequent provider-tree picker groups the options under ABEMA and Twitch
headings, with all children visible and indented. Source rows retain independent
A/B checkboxes. A small read-only dash indicator in each provider's A/B column
shows whether a child from that provider is selected for the column; it never
assigns a source itself. Child labels omit the repeated provider name, while
accessibility descriptions and viewer labels retain the full source identity.

The picker now renders one catalogue with independent A/B checkboxes. Checking
a row replaces only that slot's previous source; both boxes on one row are
allowed. Unchecking the current choice leaves that slot unassigned and disables
Open viewer until both have choices. Source identities remain saveable across
rotation, and only a complete ordered selection reaches existing preparation.
This changes selection UI, not duplicate-host ownership or provider handling.
Build and device verification for this follow-up are recorded separately below.

The October 7 follow-up passed the controlled SDK 37.2 `test`, lint,
instrumentation compilation, release manifest/assets check and assembly of app
and test APKs. Both certificates matched the shared signing procedure. The app
APK SHA-256 is
`25718a393dbe73e99cbabbcd2badd51ab735937d1ed9fb6575a9c79126bef4a7`
(16,202,635 bytes, host-owned). Scoped hooks and documentation build passed.
The Pixel 6/Android 17 installation retained app storage. All three focused
provider-free picker tests passed: one catalogue/duplicate choice, replacement
and clearing, and consecutive A/B callbacks before recomposition. Actual picker
inspection confirmed labelled checkboxes; rotating an incomplete draft to
landscape retained B's replay selection and disabled Open viewer. The original
portrait rotation was restored. No provider page, login or playback was needed
for these checks, and no new decoding/audio evidence is claimed.

## Historical source configuration follow-up

The earlier **Source setup** on each picker row edited that resource's display name, default
connection and optional independent A/B overrides. **Add connection · Proton /
import** leads to the guided profile importer. This remains a fixed catalogue,
not service/channel browsing. Names and profile references persist encrypted;
unsaved nonsecret edits survive rotation. The picker shows the resolved routes.
Imported-route playback is deliberately unavailable until a transport backend
exists: Open viewer stops before preparation, preserves the selected pair and
does not silently fall back. System network means Android's current connection,
including an active external VPN. See [connection setup](connection-import.md)
for storage, failure behavior and verification limits.

## Prototype theme

The picker, preparation screen and native viewer now share a restrained warm
neutral palette with gold actions and teal mix controls. Android day/night
resources follow the system setting; Compose uses those same colour roles.
Rounded control surfaces distinguish selected, normal and disabled states.
The video stage stays black and its feed labels stay white on dark chips.
Historical browser screens and provider pages are not restyled.

The prototype Activity alone selects the matching platform theme for menus and
preparation text. System-bar icons use the app theme outside playback and light
icons over the black video stage. Explicit bar backgrounds support older Android
versions, but visual verification remains limited to the phone below.
Changing system theme during playback can recreate the Activity and stop the
existing bounded session; seamless mid-session theme switching is not claimed.
No provider, authentication, media or DRM handling changes accompany the theme.

The October 8 theme APK passed controlled SDK 37.2 tests, lint, instrumentation
compilation, release isolation and app/test assembly. An initial lint failure
for an API-27 XML bar attribute was corrected without raising the minimum SDK;
the settled build has no lint errors and retains eight existing warnings and
one hint. Both signing certificates matched the shared debug identity. The app
APK is 16,916,260 bytes, host-owned, with SHA-256
`a016fbbbf65450a4d22c36cc900ee147212f92e72ad93cb8e9afbc827d2e860e`.

On Pixel 6/oriole, Android 17 with System WebView 153.0.8010.36, fifteen focused
provider-free phone tests passed in dark mode: picker assignment, resource and
Compose palette selection, selected/disabled text, contrast, timing dispatch,
dismissal and compact/large-font layout. The two theme tests also passed under
system light mode. Text contrast tests include panel compositing over both black
and white video. App storage was retained during installation.

Actual picker and preparation screens, landscape Audio/Timing/Status panels
and More popups were inspected in both modes. The light-mode mute selection was
distinct; video labels remained white on dark chips and video-stage system icons
remained light. Two cached guest ABEMA sumo replay copies rendered moving frames
in each short inspection, with the VPN marker present and no original web video.
Those observations concern theme/layout, not fresh acoustic confirmation or
timing precision. Each run was stopped explicitly; original system dark mode
was restored. No other device or older Android visual compatibility is verified.

## Playback and hosting boundaries

The no-page follow-up now routes the normal Prototype entry to
`CachedPrototypeActivity`, with a separate `prototype-cached-player` profile.
**Prototype comparison · original web startup** retains the page-backed flow
described below. The new path attaches only a tiny app-owned script runtime;
it does not load ABEMA's playback page, onboarding, or original video element.
It downloads and verifies four pinned public chunks: application, framework,
source utilities and a small legacy source selector. No provider asset is
included in the APK. The user explicitly requested removing our CSAI adapter
prerequisite: the normal prototype now attempts the selected replay content
without creating a separate ad player. The added fifth-chunk preflight is no
longer part of startup; its reviewed cache identity and recorded evidence remain.
This is a debug feasibility experiment, not provider-equivalent ad handling.

Each slot creates a fresh anonymous guest session and media token, calls the
unchanged provider source selector or filter/comparator, and owns
its unchanged response helper and independent native CDM. No existing browser
cookies, account login, response, or DRM session are imported. Preparation failure
stops explicitly, without reopening the original page. Replay considers only the
first provider-ranked source compatible with native protected custom DASH.
Direct NONE/CSAI/ABEMA_DEFAULT manifests proceed using unchanged provider URL
and DRM configuration builders. MediaTailor still stops because its session
manifest resolution is unimplemented, not because of an assumed ad-completion
receipt. Unknown modes stop explicitly; no later ad-free candidate is selected.
Live News uses advertised legacy channel playlists, not an
invented resource identifier for the newer gateway. Replay requires explicit free
metadata covering the five-minute session and refuses ambiguous rental, enabled trial,
partner, device or precedence conditions before dispatching its advertised ARIN.
Promotional external-content strings are bounded/typed but not opened or logged;
their UI/tracking is not yet reproduced and remains a production-equivalence gate.
Remote flag evaluation, ad-cluster discovery, full device classification and replay
entitlement equivalence remain experimental gates, not established equivalence
to the full player. Device results are recorded in
[native experiments](native-access-experiments.md).
The cached native path now admits the user-approved single-label
`*-abematv.akamaized.net` CDN family. New eligible origins remain blocked pending
approval and are saved as token-free patterns in a bounded app-private journal;
see [media-origin approvals](media-origin-approvals.md). Historical strict
examples and all other source/DRM/manifest-declaration restrictions remain.

The original comparison has a separate app-private `prototype-player` WebView profile in
`:prototype_player`, not another application. Existing historical browser
profiles/cases are retained; no cookie or credential transfer occurs. ABEMA
guest consent may therefore need to be accepted again. Only the two fixed
ABEMA playback routes are admitted; no credential entry is needed or added.

Each ABEMA slot owns its own attached/nonzero WebView, nonce-bound unchanged
provider helper, pending source/opaque exchange, exact-source manifest policy
and native CDM session. It permits one initial exchange for that session, never
another slot's response, renewal, provisioning, key export or helper-algorithm
reproduction. Duplicate ABEMA choices do not share DRM responses or sessions.
This flow arms each unused initial helper once with the remaining shared
budget, including replay; historical replay examples keep their earlier
unaligned lifetime. Replay arming is an additive orchestration assumption, not
new licensing permission or renewal. Helper readiness waits at most 90 seconds.
Original players remain attached behind the native viewer. Unknown navigation,
source/protection changes outside existing policies and failed isolation stop
the session rather than silently selecting another path.

Twitch slots independently validate the existing same-device encrypted LOCAL
grant, resolve their fixed public resource and use existing bounded unencrypted
HLS playback. No token enters selection state, intents, viewer or logs. The
historical RelaxBeats/replay preparation overload remains unchanged.

Both members share one presentation audio-focus owner and a five-minute
foreground budget starting at Open viewer, including preparation waits. Source
selection, rotation, return from provider-page setup and controls do not renew
it. Stop, background or budget expiry closes both. The October 8 follow-up
isolates member/authorization failures to their own pane, except unsafe cleanup.
Original-pause callbacks are aggregated once per slot; failure, timeout or a
newer command prevents late callbacks from starting playback. Timing readback
is requested adjustment, not measured common-event alignment.

The UX is preliminary and English-only. Source choices and layout are not yet
a persisted user presentation. Login recovery remains in the historical menu;
the new flow reports failure rather than requesting credentials itself. These
are remaining product tasks, not verified production behavior.

## Verification

### October 8 provider-tree picker

The provider-tree follow-up passed 545 debug and 407 diagnostic JVM tests, lint,
instrumentation compilation, release isolation and app/test APK assembly in the
pinned SDK 37.2 container. Both APK certificates matched the shared debug key.
Scoped hooks and documentation build passed. On Pixel 6/oriole with Android 17,
all six provider-free picker tests passed, covering grouping, passive parent
indicators, per-column reassignment/clearing, duplicate choices, rapid callbacks
and the added Twitch channels. The app was updated with storage retained, and
its installed APK checksum matched the verified build. No provider playback or
new audio observation was performed; visual inspection was blocked by the
phone's lock screen.

### October 8 independent feed errors build

The pinned SDK 37.2 container passed 545 debug and 407 diagnostic JVM tests,
lint, instrumentation-test compilation, release manifest/assets isolation and
debug assembly. Lint reported no errors, nine warnings in existing code and
dependency-version notices, and one existing hint. The complete repository
hooks and documentation build passed. Provider-free UI fixtures for the added
channels, error visibility, Sources navigation, single-feed controls and compact
toolbar targets were compiled but not run on a device. No phone installation,
provider playback or new audio observation was performed.

The actual APK certificate matched the shared debug identity. Its SHA-256 is
`c7ba607448b0dd353c3bde715d019e02f4a8ddce00e27360dd05c94669039292`.

Pure tests exercise all 36 ordered catalogue selections, distinct slot labels,
service/kind coverage, original-pause aggregation/duplicates/failure/timeout/
cancellation, remaining-budget invalidation and child-request ownership without
renewing the parent's deadline. An Android UI fixture checks
duplicate selection reaches Open viewer. These tests do not prove all 36
combinations decode simultaneously on the phone. Record build/device results
below, including native audio confirmation and duplicate DRM-session evidence;
retain untested combinations as pending.

### October 7 build

The pinned SDK 37.2 container passed 480 JVM tests, lint and debug assembly.
The final app APK SHA-256 is
`2c4707dc3f04c3acf9f46de6172ea25dd601ea6ba5b70dae079668561570f52f`
(16,675,283 bytes, host-owned). Signing verification matched the shared debug
certificate, and installation updated the existing app without clearing data.
The test APK was also signed/verified. Scoped hooks, secret detection and the
documentation build passed. Final lint retained five earlier warnings and one
hint; the new layout-inflation warning was fixed, not baselined or suppressed.
The first compile's missing source reference and an intermediate lint result
during live edits were resolved by the settled rebuild. No signing bypass,
repository-wide formatting, generated tracked files or GitHub writes were added.

The two prior viewer background-tap instrumented regressions passed again.
The initial source-picker test found no Compose hierarchy; the phone was
subsequently observed locked. Explicit idle synchronization was added per
the [Compose v2 testing guidance](https://developer.android.com/develop/ui/compose/testing/migrate-v2),
and its rerun passed on the unlocked phone. This gives three passing targeted
instrumented tests, including the two earlier viewer regressions.
All three also passed together against the final budget-fixed installation
(2.834 seconds): duplicate source selection and both portrait background-tap
control-reveal regressions.

### October 7 phone checks

The device is the same Pixel 6/oriole, Android 17, with Android System WebView
153.0.8010.36. The VPN indicator was present; that alone does not identify the
exit or prove provider acceptance. The new ABEMA profile uses guest/provider
state, not copied login cookies. Twitch reused the encrypted LOCAL grant, with
official validation returning HTTP 200 for each independently prepared slot.

Two copies of the known Twitch replay reached READY, automatically opened the
landscape floating viewer and emitted native frames. Both playback clocks
advanced from the 70-minute starting point. Tapping the floating surface swapped
the distinct A/B labels; Back closed both and returned to the picker. This
verifies duplicate Twitch preparation and the new automatic-start workflow,
not a measured synchronization lock.

ABEMA News plus the Twitch replay also reached native READY/frame milestones
and opened automatically. ABEMA's initial license request/ready events preceded
native playback. Both clocks then advanced, including ABEMA's live content-time
readback. The original ABEMA pause/mute confirmation was required before joint
Play. Guest setup took about a minute on this run; the oversized desktop provider
page exposed an onboarding usability risk. A subsequent prototype-only 35%
initial page scale is a provisional setup fix, not changed media handling.
Closed helper-state transition diagnostics contain only allowlisted enum names.
Acoustic confirmation is recorded
separately from native state.

The initial Bob Ross live choice returned media HTTP 404 after successful login
and access validation, stopping both slots as intended. Unavailability is a
plausible explanation, not a proven server cause. Monstercat and a historical
Vibfinity precheck also returned media 404. A separate credential-free query of
Twitch's public stream metadata reported all three OFFLINE, while RelaxBeats
and [Izgonnabemei](https://www.twitch.tv/izgonnabemei) reported LIVE. The latter,
previously chosen by the user, became the prototype choice without changing
media allowlists or authorization identity. Availability can change. A preliminary
duplicate ABEMA replay attempt timed out waiting for provider readiness, before
any native license event; it does not establish duplicate DRM-session failure.
Duplicate ABEMA and all sixteen device combinations remain unverified.

A resized duplicate-replay follow-up reached READY in both independent provider
helpers but failed before native licensing. Code inspection found that closing
the temporary manifest request group stopped its shared presentation budget.
The prototype now gives that request an independently closable child budget;
parent expiry, Stop, epoch and foreground checks still govern it. Two regression
tests cover cancellation direction and the unchanged original deadline. Earlier
waiting attempts had visible optional onboarding, but its causal role was not
established. No helper algorithm, DRM/key handling or media allowlist was changed.
The corrected APK's immediate device repeat displayed ABEMA's region-rejection
page in both original panes, before helper readiness or native licensing. The
budget fix is tested in isolation but its replay playback follow-up remains
blocked by this external connection condition; the VPN icon was still present.

The final APK's default ABEMA News + Izgonnabemei live pairing reached both
native READY/frame milestones and automatically entered the landscape viewer
about 27 seconds after Open viewer. Both live content-time readbacks continued
advancing. The original web pause/mute barrier passed before playback. This
confirms the new live/live orchestration on this phone, not acoustic balance,
common-event alignment, unlimited duration or availability on another day.
No new listening confirmation was received during these particular runs.

### Startup investigation

The separate [offline helper initialization experiment](native-access-experiments.md#offline-unchanged-helper-initialization--2026-10-07)
initialized unchanged provider code without the full webpage/player, using no
phone or live license. This supports investigating a smaller helper runtime;
it does not replace provider session setup, prove response processing or reduce
the current prototype's startup time. The installed application is unchanged.
