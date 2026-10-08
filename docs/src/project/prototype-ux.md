# Product-flow prototype

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

## Source assignment follow-up

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
it. Stop, background, failed member/authorization or budget expiry closes both.
Original-pause callbacks are aggregated once per slot; failure, timeout or a
newer command prevents late callbacks from starting playback. Timing readback
is requested adjustment, not measured common-event alignment.

The UX is preliminary and English-only. Source choices and layout are not yet
a persisted user presentation. Login recovery remains in the historical menu;
the new flow reports failure rather than requesting credentials itself. These
are remaining product tasks, not verified production behavior.

## Verification

Pure tests exercise all 16 ordered catalogue selections, distinct slot labels,
service/kind coverage, original-pause aggregation/duplicates/failure/timeout/
cancellation, remaining-budget invalidation and child-request ownership without
renewing the parent's deadline. An Android UI fixture checks
duplicate selection reaches Open viewer. These tests do not prove all 16
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
