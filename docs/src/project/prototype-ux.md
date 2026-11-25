# Primary application flow

The source picker, provider settings and native viewer are the primary Android
application in ordinary debug and release builds, following the user's October
17 promotion request. `Prototype` class/file names, process names and profile
identities may remain for compatibility with saved state and existing tooling.
They do not make current functionality debug-only. Historical experiments below
retain their original dates, restrictions and verification limits; promotion
does not establish provider support or new device evidence.

## Android TV selection and settings — October 18

TV UI mode selects landscape-safe margins, labelled A/B assignment buttons and a
contrasting focus outline. Selection remains gold; focus has a separate outline.
The picker initially focuses Routes, then Providers when Routes is unavailable,
or the first rendered assignment. Shared settings retain their existing actions
and stores, with screen-level focus restoration and remote-operable controls.
Discovery tabs form a focus group; search keeps the keyboard Search action.
On TV, D-pad focus passes over the Search streams field without opening the
keyboard; Select starts editing, and Back (closing the keyboard), Up, Down or
leaving the field ends it ([#624](https://github.com/altendky/tachiai/issues/624)).
Provider reordering uses the existing handle menu without requiring a drag.
Twitch's device flow presents its activation QR and code on TV, without the
phone-specific Brave/Chrome launch buttons. Credential entry remains on the
provider's pages; no grants or browser sessions are transferred between devices.

Bounded API 36 Android TV emulator checks cover duplicate-source A/B selection,
Watch, Routes/Providers entry, help and Back, busy-state focus, delete-dialog
return, and D-pad discovery tabs plus search submission. They use synthetic
fixtures, not provider accounts. Provider-page remote input, physical TV decoding,
DRM, audio and sustained playback require separate evidence. Viewer remote
controls are described below; integrated TV acceptance is a separate work item.

### TV viewer controls

On TV, D-pad/Select reveals hidden controls and consumes that first press before
activating anything. The exception is a failed feed's own Retry/Go live, which
stays visible on the feed while the controls are hidden: Select on it, when
focused, activates it at once, as one tap does on phone
([#559](https://github.com/altendky/tachiai/issues/559)). Focus starts on Play/Pause, has a contrasting outline, and
keeps the overlay visible. Audio, Timing and More retain their opening
controls for focus restoration. Back closes a menu, then a panel, then the
[playback overlay](#playback-overlay--656-step-1); another Back with all
controls hidden asks "Stop playback?". Held mix adjustment
stops on release, cancellation, focus/window loss,
panel dismissal and session suspension. More offers floating-feed corner and
Small/Medium/Large presets plus swap, retaining the same player surfaces. TV omits
the phone orientation toggle; phone drag and default floating geometry remain.

One foreground TV media session sends Play/Pause to the existing guarded pair
transport. It adds no background playback service or competing audio-focus owner,
and releases before playback teardown. Media-key repeats and key-up do not repeat
the command. Independent feeds expose no shared seek action or position clock.
The session uses the
[unknown-position form of PlaybackState](https://developer.android.com/reference/android/media/session/PlaybackState.Builder#setState(int,long,float,long))
without a position-update timestamp, so Android cannot extrapolate a synthetic
shared timeline. These controls do not change provider authorization, per-feed
retry admission, session lifetime or routing policy.

Bounded synthetic checks passed twelve TV viewer/media-session tests, including
real system Back and injected hardware media keys, plus ten unchanged phone
panel/menu/touch regressions. These establish control dispatch and state/surface
retention with player doubles; actual two-video decoding and audio requests are
the separate controlled-media acceptance step.

## Playback lifetime — October 17

Normal viewing has no application-imposed five-minute cutoff. The presentation
continues while its foreground owner, selected routes and provider authorization
remain valid. Stop, background and unsafe cleanup still end playback. Startup,
individual network operations and explicitly bounded test/diagnostic sessions
keep their own deadlines. No timer renewal is needed merely to keep watching.
ABEMA replay availability follows the selected provider free/terms/timeshift
window; removing a local timer does not extend that window or a provider grant.
Earlier dated sections below describe the former experimental session limits.

Failed feed panes offer **Go live** when playback has fallen behind the live
window, or **Retry** for classified playback/network availability failures.
Recovery creates a fresh session for that feed on its retained selected route,
after its old session confirms cleanup. The other feed keeps playing; the viewer
retains layout, mix/mutes and quality preferences. Live starts at the current
provider default position; replay uses its last observed content position where
the current source supports that seek. The prior relative timing anchor becomes
invalid. Repeated taps cannot create concurrent attempts, and late callbacks
from a retired session cannot affect its replacement. Retries are user initiated;
account, entitlement, licensing, policy and unsafe cleanup failures retain their
existing settings/recovery requirements. No playback route switching is added.

Play rechecks each READY live feed's current window after provider-player
isolation. A live position that is missing or outside that window retires only
that feed through the same cleanup path and shows **Go live** in its pane. An
authorized ready partner can start without seeking, restarting or clearing its
requested playback. Unknown player state and advertisements retain their existing
guards. Replacement sessions cannot inherit an older Play request, and unsafe
cleanup still stops the presentation. This handles stale-window startup; it does
not resolve the underlying sustained transfer failures tracked in issue #297.

Twitch queues native events only when they deliver READY, a video frame, player
failure, a limit or Stop to the session. Other events retain synchronous first
failure capture and safe logging, while default manifest/media byte logging is
omitted. The media source already deduplicates those byte events to at most two
per player; this is bounded no-op dispatch cleanup, not a per-chunk speedup or a
measured buffering fix. Transfer listeners and opt-in read-cost counters remain
unchanged; #297 remains open.

On 2026-10-18, the integrated change passed 2,255 JVM cases, lint, release
isolation, app/test/unsigned-release assembly and scoped hooks. Its configured
muted Twitch replay/System-network fixture passed in 47.822 seconds on persistent
`tachiai-dev`, Android 16/API 36 x86-64, with WebView 133.0.6943.137 installed
and the existing saved catalog account. A frame rendered, position advanced
39.434 seconds, and all 159 samples reported playing with no terminal failure.
The final selected video was 712,897 bit/s and SDK bandwidth estimate 1,793,736
bit/s. Cleanup, original selections and encrypted-record preservation passed.
Exit country was not independently measured; no provider page or protected media
was captured. The differing final rendition and sequential runs do not establish
a dispatch-related speedup, sustained two-feed or physical-device acceptance.

## Main flow and settings — October 16

Normal launches open **Choose two streams**. Development and inspection builds
retain **Developer tools** in the picker's overflow menu to open the historical
experiment root; release omits that entry. Explicit developer diagnostic launches
retain their own entry behavior. Recovery still enters the separate main process
before terminating an unsafe viewer process.

The picker has no built-in streams and no first-run default pair. Without saved
choices both slots start empty, and an instance with no saved list shows the
existing empty-list message. Saved items with former built-in resources remain
ordinary [configured items](configured-catalog.md#persistence-and-migration).
Only the developer-only full-page comparison lists its fixed ABEMA News live and
sumo replay samples, because its page-backed sessions play only those.

Providers shows compact instance rows with a leading drag handle and trailing
three-dot menu. Tap a row to configure it. Dropping a drag saves the order; the
handle's menu also supports moving earlier or later. Delete asks for confirmation
and removes that instance and its streams from the configured setup, preserving
shared routes and other instances. All instances may be removed; an explicitly
empty registry stays empty, and adding again creates a fresh identity. Removal
does not sign out, revoke an already-running session, or erase orphaned encrypted
instance records. Missing identities cannot start new playback or silently use
another account. Configuring an instance opens one detail
page with name, connection, account actions and stream management, instead of
separate account and Manage streams navigation. Provider-controlled sign-in and
catalog browsing remain focused operations. Account controllers remain mounted
while their header scrolls offscreen or stream results reload.
Explicit account operations also suspend parent automatic renewal and catalog
loading until provider-return verification finishes.

The provider detail headline is the instance name, with an edit icon. A small
provider-type label appears only when the non-empty name does not already contain
the provider name, ignoring case; blank names display the provider name alone.
The same rule applies to paired name/type summaries. Name editing opens an
autosaving dialog. Page back arrows lead the title row, while page and section
help controls occupy its trailing end. Connection uses
compact ordered route rows, with drag handles, removal and an Add route menu
that also opens route import. Its existing heading shows startup progress,
the effective route or exhaustion in a fixed single-line area. Startup uses an
actual required provider read before keeping a route for the session; only
classified transport availability failures can try another configured choice.
Account transactions stay on their chosen route, and later playback failures
stop rather than switching automatically. ABEMA account settings use a compact
heading and a stable status/action row. The primary action reads Connect by email
or Open account, fits its label and aligns right beside the status. Its normal
visual height is 40dp, with a minimum 48dp touch target and a consistent gap before
the next section divider. Labels may wrap at larger text sizes without moving
neighboring sections; Sign in again, Check connection and confirmed Forget account
live in the overflow menu. Opening sign-in and checking the returned account use
the existing status row without a progress dialog. After half a second, an inline
spinner appears in reserved status space and the primary action becomes Cancel;
repeat connection clicks stay disabled during the handoff. Back can cancel a
pending foreground connection even when its settings row is scrolled away.
A mounted dialog outside the scrolling content remains only when a returned
grant actually enables manual Finish connection. Status changes do not insert
or remove settings rows. Section help uses info icons.

Search uses a trailing icon inside the field and the keyboard Search action;
both submit the same provider-wide query and respect loading and storage guards.
Find streams groups use a horizontally scrolling tab row, with account/access
indications kept in a reserved icon slot. Switching groups retains their loaded
roots while preserving the controls' current screen position. Shorter results
may temporarily retain enough trailing space to prevent a jump; scrolling toward
earlier content progressively retires that space without restoring it on reverse
scroll. Child Back and global search retain distinct contexts. Account-derived
results, transition space and scroll anchors remain in memory only.
Browsing a parent whose provider groups its children, such as an ABEMA series'
episode groups (#622), adds the same tab row below the child heading, in the
provider's order and opening on its first group. A group tab replaces the
listing in place without adding a Back step; Back leaves the parent and its
tabs. Parents without groups are unchanged.

Configured streams and Find streams are peer sections, without an outer Streams
heading. The compact Add link action and section help share the Configured
streams heading. Its dialog
resolves a URL or channel to a public resource for review, then explicitly adds
that resource. Lookup, validation and save failures stay in the dialog; Cancel
leaves the browse context alone. Raw input is never restored from saved state.

Discovery result rows use a plus icon with a descriptive Add accessibility label
and preserve already-added feedback. Configured streams use compact rows with drag handles, refresh and removal
icons. Dragging previews a new order and saves it once on drop; tapping a handle
also offers move actions for keyboard and accessibility use. Browsable titles
and subtitles form one target; the existing subtitle names the action, such as
“Browse programs › · Availability unknown”. A result with a known length
(an ABEMA scheduled broadcast or a Twitch video) ends its subtitle with it, in
the same format as episode rows (#608). Browse episodes shows replay
lengths like the picker's Episodes page (#672): while the listing is shown,
each finished ABEMA broadcast's real length is read in the background, a page
at a time, and replaces the slot span when it arrives. Live, in-progress,
upcoming and unreadable broadcasts keep the span, with no error. Any other
catalog request or navigation comes first and ends a running read. Known
lengths stay in memory for the app session, shared with the picker. Provider
adapters distinguish
channel programs, series episodes and broadcaster videos. Exact videos and
broadcasts do not advertise child browsing. Refresh and removal remain separate
targets. Removal requires confirmation. Refresh retrieves
the same resource's title, availability and schedule while retaining its saved
identity, order and quality; it does not start playback or renew authorization.

Successful configured-stream saves are silent. Transient progress and stream
errors overlay the page without changing list padding or inserting status rows;
retry remains available on the error surface. Capability explanations live in
section Info. Routine feedback must not move settings controls or stream rows.

Ordinary valid name and route changes save automatically. Accepted writes are
serialized and drained before returning, and new readers wait for outstanding
writes. Invalid names remain drafts; failed writes show a retry action and the
durable route. Changing a route invalidates old catalog/account bindings before
new operations. Import previews and destructive actions remain explicit.

Settings use section headings and horizontal dividers. Longer guidance lives
behind section **Info** controls; current status, validation and errors stay
beside their controls. Main and settings pages retain normal screen timeout.

The October 15 verification used the visible persistent `tachiai-dev` emulator
(Android 16/API 36, x86-64, WebView 133.0.6943.137). Normal launcher, combined
ABEMA account/stream page, Info, Developer tools and Back navigation were
observed. The existing account validated with its configured Windscribe route;
this does not establish exit region, new sign-in or playback behavior. The final
APK passed all 76 provider-free Compose fixtures and 1,967 JVM tests in each of
debug and inspection variants, both lints, release isolation and inspection APK
policy checks. APK signers matched the shared debug identity. Install and
synthetic fixtures preserved all seven encrypted records. Fresh browser sign-in,
physical-device behavior and TV interaction were not exercised in this UX check.

## Screen timeout

Main pages, source selection, settings, account pages and diagnostic helpers
follow Android's normal screen timeout. Native video views request keep-awake
while visible; hiding or removing those views releases their request. Activities
and authentication popups do not request keep-awake for the whole window.

## Remembered ABEMA account renewal — October 18

The application checks saved ABEMA accounts before configured discovery or
playback. When the native grant has expired, when the main process's
pre-expiry timer fires, or when the renewal job runs 15, 10 or 5 minutes before
its natural expiry,
one bounded application-owned operation reuses the remembered email browser
session through that instance's selected connection. The browser runs in an
unattached Service and launches no Activity. A trusted passive account request
can supply a replacement without Connect or Return clicks. Explicit account
setup remains foreground-controlled on ABEMA's pages; automatic renewal does
not enter codes, reset the browser session or inspect cookies.

Replacement requires the original account, a useful later decoded expiry,
fresh native profile and registered-email verification, confirmed browser and
route cleanup, and an atomic protected-store update. Concurrent consumers share
the attempt. Changed accounts/routes, Forget, cancellation and stale callbacks
cannot adopt its result. A direct same-account replacement with a later
expiry keeps existing account leases, including a playing ABEMA feed, which
then follows the new expiry. Other account changes still end them. While an
early renewal runs, a still-valid grant stays READY, so starting playback does
not wait for it. Otherwise readiness observers receive WAIT while the operation
owns a tentative replacement, and a failed attempt suppresses immediate resume
loops. A natively verified different registered account can ask the user to
choose the saved account. Missing candidates, timeouts, network/region/TLS
errors, 401/403 and unregistered-email results do not establish that sign-in or
an email code is required; ambiguous results remain temporary failures.

Since #635 step 1, foreground readiness retries a renewal it launched or joined
before answering. A temporary failure, including a changed-owner result, or a
cancellation the caller did not request relaunches the same owner and grant
snapshot after about 2, 5 and 15 seconds, at most three times, while observers
keep receiving WAIT. Each retry needs the previous attempt's confirmed cleanup, a
fresh matching owner capture and the device's single renewal slot, and must leave
at least 20 seconds before the caller's original deadline; otherwise the caller
receives the temporary failure. Final, interaction, storage, unconfirmed-cleanup,
refused-clock and caller-cancelled results are not retried, nor is an approval
browser that refuses admission, for example while a manual approval holds it.
Only this loop may relaunch before the 10-second temporary-failure replay ends;
other callers still receive that replay. The renewal job keeps its own retry
policy and Network.

Since #635 step 2, there is one renewal scope per account: the instance, its
grant snapshot and the route pool of the main process's own owner capture.
Screens that selected different candidates of the same pool, such as Episodes
and startup (first candidate) and Watch and Providers (effective candidate),
join one renewal instead of ending each other's. A caller's owner proof only
admits it: a proof that matches no current candidate is refused and starts no
work. In-flight work is superseded only by a changed grant snapshot or route
pool, and the retry loop follows the same scope.

Since #635 step 3, the approval browser's owner re-check reports
`OWNER_CHECK_FAILED` when the owner record could not be re-read (for example
an unreadable store), and `OWNER_CHANGED` only when a re-read owner no longer
matches. Both still end the attempt as a temporary failure, which the retry
loop may retry.

Since #635 step 4, the renewal job joins a renewal already in flight for the
same account scope instead of deferring, and adopts its result under the job's
own retry policy, within its own deadline and without sharing its Network.

Since #635 step 5, the resume-time startup readiness check is removed. Instead,
while the main process is alive, a timer renews each saved grant once at the
renewal job's first due time through the foreground readiness path and its retry
loop, joining any renewal already in flight. Renewal, acceptance, Forget and
route or setup changes reschedule it; a manual approval in progress postpones
it. The renewal job still covers a process that is not running. Screens are
unchanged.

Since #682, a renewal in flight keeps a JobScheduler job running, so Android
doesn't freeze the renewal while the stream picker or viewer, which run in a
separate process, are in front.
The timer also retries an attempt that ended without a result, 30 seconds and
then 2 minutes later. Screens are unchanged; see
[renewal stalls in frozen processes](source-network-routing.md#renewal-stalls-in-frozen-processes--2026-11-24).

Since #635 step 6, account access for playback, guest discovery and the
in-process ABEMA catalogs no longer fails outright on an expired saved grant.
The acquisition asks the main process's foreground readiness path (its own
route, no lead) to renew, joins any renewal already in flight, and validates the
renewed grant within its own deadline. It stops waiting 5 seconds before that
deadline and then fails as a temporary failure; the renewal keeps its own
90-second limit. Interaction, storage, unconfirmed-cleanup and exhausted-retry
results, a manual approval holding the approval browser, and callers on the
account worker that renewal itself uses fail as before. An unexpired grant never
waits.

Since #635 step 7, every screen words the saved ABEMA account's state with one
message set: Watch, Episodes, viewer feeds and Providers. **Ready** shows nothing
and proceeds. While a check runs, Providers and the viewer feed show
**Checking account connection…**, Watch keeps its provider-connection status and
Episodes its loading state; once the check observes a renewal in flight or
waiting to retry, each shows **Renewing the remembered ABEMA account…**. **Needs
you**: a different account says to open ABEMA account in Providers and choose
the saved account; a renewal that cannot continue by itself says to open ABEMA
account in Providers to check the connection. A **storage problem** keeps its own
text and also points there. **Temporarily unavailable** appears only after the
renewal's retries are exhausted. A viewer feed failed by its account check now
shows the same text as the other screens; a feed's account failure before any
check points to the same place in Providers. Each screen still owns its own
readiness observer, because a new check retires that observer's pending one;
since step 2 those observers join one renewal per account.

Pausing a readiness observer detaches that observer without cancelling admitted
work or extending its original 90-second deadline. The operation can continue
while the application process survives; it never revives a paused player,
account request or license budget. There is no scheduler or reliable closed-app
execution guarantee. The [production implementation and historical evidence](
native-access-experiments.md#operation-owned-abema-renewal-implementation--2026-10-18)
remain distinct: naturally expired physical-device adoption, actual signed-out
interaction and process-death publication behavior remain in #294.

### Opt-in minimized renewal observation

The existing debug instrumentation fixture accepts
`tachiaiAutomaticAbemaRenewal=minimized` on the explicitly verified persistent
`tachiai-dev` emulator. It requires a naturally expired saved grant and the
existing selected saved connection. It never changes the clock, fabricates
expiry, resets browser data or enters provider credentials.

The fixture waits for the original renewal operation to be pending, presses Home,
and requires another pending observation after that exact Activity stops. It
then observes the existing completion/account-replacement boundary without
resuming or accessing the Activity. A same-process Activity resume, restart or
host destruction permanently refuses the background claim. Verified READY must
arrive while the host remains stopped; only then may the fixture return the same
host and check original discovery, account ownership and protected-record
preservation. Foreground completion racing minimization cannot pass this mode.

After normal debug app/test APK signing verification and non-destructive
installation, select only this opted-in case:

```sh
adb -s emulator-5580 shell am instrument -w -r \
  -e class net.fstab.tachiai.provider.abema.account.AbemaAutomaticRenewalDeviceTest#optInExpiredGrantRenewsWithoutClicksAndResumesOriginalDiscovery \
  -e tachiaiAutomaticAbemaRenewal minimized \
  net.fstab.tachiai.test/androidx.test.runner.AndroidJUnitRunner
```

Return uses the unique owned app task captured before Home, requires its expected
top component, and then checks the original Activity identity. The provider-free
blank-Activity test exercises real Home/stop/task-return/resume separately from
account renewal. AndroidX's [resume driver](https://github.com/android/android-test/blob/main/core/java/androidx/test/core/app/InstrumentationActivityInvoker.java)
only dismisses its own empty Activities; Android's [owned-task return](https://developer.android.com/reference/android/app/ActivityManager.AppTask#moveToFront())
brings the actual task forward. Recreation still refuses the exact-host claim.

Closed output adds the mode, background phase/first failed guard, whether the
original host returned, and bounded nonselected-record mutation counts/category.
Only an exact diagnostic-journal identity gets its own category; other or mixed
changes stay separate. No record names, IDs, digests or grants enter output.
The identical-inventory/nonselected-digest preservation assertion remains strict,
including diagnostic-journal changes. Independent cleanup/preservation reporting
remains active on failure. The instrumentation process stays alive, and its
callbacks observe only that process. Pending-to-READY brackets terminal operation
completion, not the exact browser/bootstrap/store-commit timing. This fixture
does not by itself establish a successful run, physical visibility, process-loss
survival, genuine interaction classification or closed-app scheduling.

### First minimized observation — November 1, 2026 UTC

One observation using the Android inputs retained in signed
`690cba76d5547daa467165893756a68f44fb96ab` ran on verified persistent `tachiai-dev`,
Android 16/API 36 x86-64, WebView 133.0.6943.137 and ordinary debug 0.1.0. Both
installed APKs matched the shared development certificate. The existing selected
ABEMA saved-connection owner and remembered account were used; region/exit
country was not independently measured. Local-only preflight reported
EXPIRED_READY without provider access. No clock change, forced expiry, reset,
credential entry, cookie/grant transfer or phone operation occurred.

The run failed overall after 75.519 seconds. It reached verified same-account
replacement and terminal READY after the stopped-host pending observation, with
no failed background guard. Return then failed at phase RETURN/EXCEPTION before
original discovery or exact-host return could be established. Host closure and
preservation comparison completed, but nonselected records were not all unchanged;
the selected account record changed. The original closed report cannot identify
which other record changed, so neither preservation nor its cause is established.
The later counts/category extension does not retroactively explain that result.

Separate local-only postflight reported UNEXPIRED without another provider
operation. No renewal retry followed. Source review identified the AndroidX
Home-return limitation described above; the fixture now uses the owned task and
retains the first failed result. This establishes only the bounded stopped-host
terminal replacement observation. Complete minimized return/discovery/preservation
acceptance still needs a future naturally expired run; physical and process-loss
behavior, genuine interaction handling and scheduling remain open in #294.

## ABEMA broadcast live mode — October 14

Opening a currently airing configured broadcast selects its continuous live
channel after fresh matching broadcast metadata and time checks. The viewer
starts at the live edge and keeps the channel playing when that broadcast ends;
the saved broadcast, provider instance, route and preferences remain intact.
Future broadcasts refuse playback (the viewer then waits for the start; see
[waiting for live shows](#waiting-for-live-shows--november-20-2026)), while ended selections use exact catch-up
with their separate Free/Premium eligibility checks. Chase remains a separate
capability; linear live playback does not consume Chase authority.

The adapter pins the resolved mode and channel source/license identity for the
session. It retains account expiry, owner, route, foreground, deadline and cleanup
checks. ACCOUNT linear preparation uses the verified modern region/device/channel
context; a genuine Guest selection retains the existing legacy channel path.
An account failure does not fall back to Guest. The source-only diagnostic still
cannot create a player or exchange a license. Actual device verification of the
new live mode is recorded separately in the native-access evidence.
The first real native check was refused during region preparation before a
player was created; frame/movement and a broadcast-end transition remain
unverified. The implemented mode is not a device playback success claim.

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

### Playback overlay — #656 step 1

The video always fills the viewer. The basic controls are an overlay on it
rather than a bar that takes space from the feeds
([#656](https://github.com/altendky/tachiai/issues/656)):

- **Top bar:** **‹ Stop** (formerly Back to sources, still asking "Stop
  playback?"), then **Audio**, **Timing** (pair only) and **⋯** (More) at the end.
- **Bottom area:** **Play/Pause** for the pair or single feed. It also holds
  the position row, scrub bar and skips, on replays
  ([below](#replay-navigation--655-part-1)) and on live feeds
  ([#660](#live-skip-and-scrub--660)), where **Live** follows 1 min ⏩. Each
  live feed's own **Back to live A/B** is in ⋯ More (user, 2026-11-25).

Both bars sit on a dark translucent band (black at 60%) with light text, so
they stay legible over any video in light and dark themes; buttons keep their
surfaces, 48 dp targets and TV focus outlines. Phone, tablet and TV show the
same controls, labels and order. Focus starts on Play/Pause when the overlay
first appears. A remote reveal with Select focuses Play/Pause; other reveals
restore the last focused control but never Stop, so a reflexive Select cannot
ask to stop playback. A tap or any remote key reveals the overlay; it hides after four idle
seconds while playing, stays while paused, a panel is open or a control has
focus. There is no separate Hide button: a video tap hides the overlay on
phone, and Back hides it on phone and TV. The Back order is unchanged: menu,
then panel, then overlay, then "Stop playback?".

Panels keep their contents. **Timing** and **Quality** keep their own space:
Timing marks need both feeds in view, and Quality's long list is judged
against the picture. They open as a right-hand side panel below the top bar in
landscape at usable widths of at least 640 dp, otherwise as a bottom panel
above the transport. While **Fit video** is on, they shrink the video beside
them, as before; portrait always reserves the bottom panel. Fit is shown only
in these panels. **Audio** (short, adjusted by ear) and **Status** (read-only
text) are sheets in the same positions over the video and never resize it.
The floating feed stays clear of the visible bars and any open panel, using
its normalized position within that range, and returns when they hide.

The always-present two-line status readback is gone. Full status, including
the requested adjustment and quality details, is in **⋯ → Playback status**.
A change shows as a brief notice centered over the video below the top bar,
which fades after about three seconds: a new pair status, including play
messages and the volume-request warning, or a feed becoming unavailable or
ready, such as "B unavailable". The opening state is not announced, and no
notice appears while Status is open. Each feed's waiting, failure and recovery
message, with its Retry, Go live, Check now or Play again button, stays inside
its own pane. While controls are visible, that content is inset to stay below
the top bar, above the transport or a bottom panel and beside a side panel;
the pane and its video keep their bounds.

Remote handling is unchanged: the first key reveals the overlay without acting,
except Select on a focused pane recovery button (#559) and Left/Right skips
(#655, live #660), and focus restoration and the #617 protections remain. Keys
while the overlay is hidden pass through one path. The phone orientation
action stays in ⋯, and floating-feed drag and tap-to-swap are unchanged.

Pure layout and notice rules have JVM tests, and the viewer instrumentation
tests were updated for the overlay and compile. They have not yet been run on
a device for this change.

### Replay navigation — #655 part 1

Replays and on-demand items gain a position row, skips and a scrub bar in the
bottom of the overlay
([#655](https://github.com/altendky/tachiai/issues/655), decisions on
[#655](https://github.com/altendky/tachiai/issues/655#issuecomment-7109865042)
and [#656](https://github.com/altendky/tachiai/issues/656#issuecomment-7109787483)):

- **Position row:** the target feed's title (`A · <title>` in a pair), elapsed
  and total time and a scrub bar. Times are `h:mm:ss` once the total reaches an
  hour, otherwise `m:ss`, for example `2:14:05 / 4:45:00`.
- **Transport:** **⏪ 1 min**, **⏪ 10 s**, **Play/Pause**, **10 s ⏩**,
  **1 min ⏩**. They share up to 560 dp equally; screen readers hear "Back 1
  minute" through "Forward 1 minute". There are no ±10 min skips, no speed
  control and no jump to the broadcast's clock time (a later follow-up).
- **Live:** live feeds and live + replay pairs have the same position row,
  skips and scrub bar, within what each live feed's window holds
  ([below](#live-skip-and-scrub--660)), plus **Live**.

**Target and pairs.** The position shown is the primary feed's (after Swap,
the other feed's); if the primary is waiting or failed, the other replay's. A
skip or scrub in a timed pair moves **both feeds by the same amount, keeping
their offset**; moving one feed relative to the other stays with Timing. The
amount is shortened so neither feed leaves its range (a forward skip stops one
second before the nearer end, so skipping never ends a feed). If either feed
can't move — an advertisement, a live or unseekable feed, a feed still
preparing or buffering, or one at its start or end — neither moves and a brief
notice names the feed, such as "B is at its end; neither feed moved." Live
feeds have their own range ([below](#live-skip-and-scrub--660)). A pair
that is missing a feed does not skip the remaining one. The skip holds both
feeds, seeks both, waits up to 8 s for both held targets and resumes them if
they were playing; Pause during that wait leaves both at their targets,
paused. It records no timing settlement, so the requested offset, saved pair
offset, event marks and Timing history are unchanged. A single feed seeks
directly and keeps playing. Presses made while a pair skip settles add up and
run as one skip next.

**Scrub bar.** While choosing, the time shows the pending target, such as
`→ 1:42:10 / 3:24:00`, so the landing point is visible before it commits.
Touch drags commit on release. With a remote or keyboard, each Left/Right
press on the focused bar moves the target by 1% of the duration, at least
10 s (about 2 min on a 3:24 replay); after half a second of holding, steps
grow to 5% (about 10 min). The target stays within the replay. Select, or one
second without a change, commits; Back abandons it. Up/Down leave the bar and
commit a pending choice. The larger steps followed the user's SHIELD trial on
2026-11-23, where 10 s steps were too slow on 3–5 h replays.

**TV remote.** While the overlay is hidden on a replay or live feed,
**Left/Right skip −10 s/+10 s** and keep it hidden, flashing the new position
as a brief notice such as `+10 s · 2:14:15 / 4:45:00`; a held key skips once.
Where nothing can skip (a live feed that isn't seekable) they reveal the
overlay. **Up/Down** reveal it on the last focused control and **Select**
reveals it on Play/Pause. Media **fast-forward/rewind skip ±10 s** (once per
press) wherever skipping is available; Play/Pause keys are unchanged. Focus
moves top bar ↔ scrub bar ↔ transport with Up/Down.

**Phone.** A **double tap** on the left or right half of the video skips
−10 s/+10 s, and each further quick tap skips again. A single tap still shows
or hides the overlay, after the double-tap interval while skipping is
available (immediately otherwise). The floating feed's tap still swaps at
once and has no double tap. With the overlay hidden, a double-tap skip flashes
the position like the TV.

When the viewer opens before its feeds are ready, Play/Pause is still
disabled and focus falls back to another transport or top-bar control (never
Stop); it moves to Play/Pause once that is enabled, unless focus was moved
first.

Phone and TV show the same controls, labels and states; only the input
differs, as approved for this design (UX-012 in the
[UX variation register](ux-variations.md)). The Android TV media session still
reports no position or duration: a pair has no shared timeline and the viewer
shows the replay position, so it stays unknown. The controlled web fixture has
no replay transport ([ledger](implementation-differences.md)).

Pair skip planning, time formatting, key mapping, tap disambiguation and the
pair/single-feed seek transactions have JVM tests. New instrumentation tests
for the transport row, scrub focus, TV keys and phone double tap compile; they
have not been run on a device for this change.

### Live skip and scrub — #660

Live feeds, and pairs of a live and a replay feed, have the same transport as
replays: ±10 s and ±1 min skips, the scrub bar, TV Left/Right while the
overlay is hidden, fast-forward/rewind and phone double tap
([#660](https://github.com/altendky/tachiai/issues/660),
[decision 4, 2026-11-25](https://github.com/altendky/tachiai/issues/660#issuecomment-7167711457)).
They work within what the feed's window currently holds: a live ABEMA feed's
rewind buffer, or a Twitch feed's provider window. Skipping never builds
delay; older footage that was never recorded can't be reached.

- **Position:** the time reads how far the target feed is behind its live
  point, such as `−1:35` (`h:mm:ss` from an hour), or **LIVE** within 3 s of
  it. Screen readers hear "1:35 behind live" or "Live". There is no total.
- **Scrub bar:** spans the oldest available point on the left to the live
  point on the right. While choosing, the time shows the pending target, such
  as `→ −2:10` or `→ LIVE`. A pending choice keeps its distance behind live
  while the window moves. Remote steps are 10 s.
- **Forward:** stops at the nearer feed's live point, so skipping or scrubbing
  to the right end returns that feed to live and keeps the pair's offset. With
  that feed already live, a forward skip moves neither and says
  "A is live; neither feed moved." ("Already live." for a single feed).
- **Backward:** stops at the tightest feed's oldest point, about 5 s inside
  its window so the target isn't dropped while the pair settles. At that point
  the existing notice applies, such as "B is at its start; neither feed
  moved."
- **Live** (user, 2026-11-25): a compact button to the right of **1 min ⏩**,
  reached with Right from it on TV, the same on phone and TV. It skips to the
  right end: a single feed returns to its live point, and a pair moves both
  feeds forward together, keeping their Timing offset, until the nearer feed
  is live. Once the view is live it stays focusable but dimmed, reads "At
  live" to screen readers and says "Already live." when pressed. A single
  live feed without a transport (below) uses its Back to live instead.
- **A feed outside its window:** a live feed that slid behind its window (for
  example paused past its oldest point) can't take an ordinary skip; the
  notice names it, such as "B is outside its window; neither feed moved. Live
  brings it back." Live still brings the view to live. If moving both feeds
  together to the nearer live point also lands that feed back inside its
  window, the pair keeps its offset and the notice says so ("B was outside its
  window; both feeds moved forward to live together, keeping the offset.").
  Otherwise each live feed returns to its own live point, a replay stays where
  it is, and the notice says "…, so each feed returned to live; the offset was
  not kept." Like Back to live, that drops the applied offset until the pair
  is reopened. A feed ahead of its window's end can't be repaired this way;
  the notice points to Back to live in ⋯ More. Live pressed while a skip
  settles runs once it has settled.
- **Holding a pair:** a pair skip or Live plans while both feeds play, holds
  both, then plans again on the held feeds. A playing feed's position and its
  live point move on between those two reads, so the plans agree when each
  feed's move differs by at most 1 s, and the held plan is the one used. If
  something really changed, such as an advertisement starting or a feed
  stalling, nothing is sought, both feeds play on, and the notice names the
  feed and the reason, such as "B changed while holding both feeds; neither
  feed moved."
- **Pairs:** a skip moves both feeds by one amount, as for replays. Each
  feed's own **Back to live A · title** / **B · title** is in **⋯ More**
  (a single feed: **Back to live · title**), and still breaks the offset; the
  full-width Back to live bar and its hint line are gone (user, 2026-11-25).
  A live feed's target is confirmed on its content clock, since its window
  slides while the pair is held. **Start over** is shown only on replay views.
- **Unavailable:** a live feed that isn't seekable, or has no live point or
  stable content clock, has no skips or scrub bar, and Left/Right reveal the
  overlay, as before. Back to live stays in More.

The live point is where the player aims to play: its target live offset
behind the live edge. The distance behind it is the player's continuous live
offset less that target, so it doesn't jump as the window refreshes, and a
skip or Live to the right end lands exactly on it and reads LIVE. A feed
started at the window's default position, which the player snaps to a
segment start, can sit a little behind it; the 3 s margin covers that. Where
the player reports no target offset, the window's default position stands in;
that one steps with each refresh. Media3's live speed control may also nudge
a resumed feed toward its target offset.

A skip pressed while the moved feed is still buffering at its new position
(a single feed seeks without a hold) now waits and runs once the feed is
ready, like presses during a pair skip, for up to 8 s. Before, it was refused
as "Skipping is unavailable right now.", which appeared when a replay scrub
committed while the previous scrub's seek was still loading.

While a feed is behind the provider's own window, only the rendition the
rewind buffer recorded is available; automatic quality could select another
rendition that was never recorded. Track selection is not pinned for this
yet (a follow-up with [#618](https://github.com/altendky/tachiai/issues/618)).

The live skip plan (bounds, clamping, the target-aware live point, mixed
pairs, content-clock settlement while the window slides, and Live with a feed
outside its window, single and paired) and the position and notice text have
JVM tests. Instrumentation tests for live position, hidden Left/Right, the
live point, live scrubbing, Live (including from outside a window), Back to
live in More and a skip during single-feed buffering compile; they have not
been run on a device for this change.

### Scrub previews — #673

While choosing a scrub target on an ABEMA or Twitch replay or on-demand item,
a thumbnail of the target position shows above the position row, centred on the
pending point of the scrub bar and kept within the bar
([#673](https://github.com/altendky/tachiai/issues/673), user approval
2026-11-24). It is 16:9, 192 dp wide (a third of the width on a narrow view),
with a thin light border, on phone, tablet and TV alike; it follows touch drags
and remote steps the same way. In a pair it shows only the target feed's image
(the feed the position row names). It disappears when the scrub commits or is
abandoned.

- **Sources:** ABEMA's timeline sprites (one tile per 10 s) and Twitch's VOD
  seek-preview storyboard (one tile per few minutes on long VODs; Twitch
  chooses the spacing). See the
  [ABEMA](abema-catalog-access.md#timeline-thumbnails--673) and
  [Twitch](twitch-catalog-access.md#vod-seek-previews--673) notes and the
  [image-host approval](media-origin-approvals.md#scrub-preview-image-hosts--2026-11-24).
- **Fetching:** only while the scrub bar is in use: the target's sprite sheet
  first, then the neighbouring sheets, on two background workers; work for an
  older target is dropped. Decoded sheets stay in an 8 MB memory cache for the
  viewer; nothing is written to disk. Requests use the feed's own route. Logs
  name only fixed outcomes and timings (`TachiaiScrubPreview`), never an address.
- **Missing data:** a feed without thumbnails, a failed request or a tile past
  the end of a sheet shows no image; scrubbing, its time and its commit are
  unchanged. A later scrub retries failed requests once more.

On tachiai-dev (2026-11-24, ABEMA through a Windscribe Tokyo WireGuard route
from the US), a new sheet took about 1.2–1.8 s to appear (2.5–3 s on the first
connection) and positions within an already loaded sheet (10 minutes of an
ABEMA replay) appeared at once. Video frames at several positions matched their
tiles to within one tile. The Twitch path was verified against real responses
from a JVM check; its on-device check is still pending because the emulator's
Twitch connection needs reconnecting. The tile mapping and the provider parsing
have JVM tests; the viewer placement has no instrumentation test.

### Replay navigation — #655 part 2

Replays and on-demand items remember where you were
([#655](https://github.com/altendky/tachiai/issues/655), decisions on
[#655](https://github.com/altendky/tachiai/issues/655#issuecomment-7109865042)):

- **Resume.** Opening an ABEMA slot or episode, a Twitch video or a YouTube
  video that was watched before resumes it **automatically** at its saved
  position, alone or in a pair. While it seeks, its pane reads "Resuming at
  2:14:05…". Once the resumed feeds play, a brief notice says where:
  "Resumed at 2:14:05. Start over is in ⋯ More." or, in a pair, "Resumed A at
  2:14:05, B at 2:14:17. …". A saved position beyond the item's current
  length resumes just before its end.
- **No finished threshold or expiry** (user decision): reopening always
  resumes at the saved position. A position saved at the very end therefore
  plays out at once into the ended pane's **Play again**; that is accepted for
  now.
- **Start over** is in **⋯ More** on phone, tablet and TV, shown on replay
  views. It moves back to the start like a long skip: a single feed returns to
  0:00, and a pair moves both feeds by one amount until the earlier one is at
  its start, keeping their offset. It shows "Started over · 0:00 / 3:24:00",
  or the skip refusal notice (an advertisement, a missing feed). It also
  forgets the remembered positions; new ones are saved as playback continues.
- **Play again** on an ended replay starts it from its beginning, as before,
  and also forgets its saved position.
- **Retry** keeps resuming a failed replay at this session's last position.
  A Retry before the feed ever prepared resumes the saved position.

**What is saved.** Each feed of a replay view saves its own position: a view
where every selected feed is a replay. A replay paired with a live feed is
positioned relative to it through Timing, so neither is saved or resumed. Live
feeds are never saved. A position is taken only while the feed is ready and
outside an advertisement. Positions are written about every 10 seconds while
they change, and at once on Pause, Stop, leaving the viewer or going to the
background; a paused feed's unchanged position is not written again.

**Pairs and the saved offset.** Each feed resumes its own position. A saved
pair offset is relative to both feeds at their starts, and a resumed pair
already holds that displacement, so the viewer starts its applied offset at
the resumed difference (A − B) instead of zero. An enabled saved offset then
restores only the remaining difference, normally a fraction of a second, so
the pair's relative offset stays consistent with what was saved. If the feeds
drifted apart, for example because one played on while the other had failed,
the saved offset wins and one feed moves. Marked-timestamp offsets already
work from absolute positions and are unchanged. A pair with no saved offset
keeps both resumed positions, and Timing shows their difference as the
applied offset. Without a resume, startup is unchanged.

**Storage.** One encrypted, app-private, no-backup record holds at most 200
items, most recently saved first; the least recent beyond that is dropped.
Each item is a SHA-256 hash of its public catalog identity (provider, kind,
identity and intent) and a position, so titles, accounts and provider
instances never contribute, and two accounts share an item's position. Reads
and writes run on the worker under the same cross-process file lock as the
other stores. Unlike settings, an unreadable or invalid record reads as empty
and the next save replaces it, so it never blocks playback. Logs name only
fixed outcomes, never item identities.

The store, resume and save decisions, the resumed pair baseline and Start over
have JVM tests, including concurrent saves from two processes. A new
instrumentation test for Start over in More compiles; it has not been run on a
device for this change.

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

### Relative timing and event marks

The native viewer's Timing panel has two direct-action rows, **Advance A** and
**Advance B**, each with 0.1 s, 0.25 s, 0.5 s, 1 s and 5 s buttons followed by
**Mark**.
A press immediately requests that feed's relative advance; there is no shared
step selector. Descriptions name both sources and the duration, and directional
focus follows the rows. All steps are disabled while an adjustment is busy or
two playable feeds are unavailable. Feedback still reports requested timing,
not measured synchronization. Compact layouts retain scrollable controls and
48 dp button targets. Cancelling More preserves the open controls and panel;
video/background taps and Back retain their dismissal behavior.

An Advance request uses one complete legal movement: advancing that feed or
equivalently delaying its peer. Planning compares both options and prefers more
live-window headroom, with A retained on equal margins. It does not split a nudge
or dispatch an alternative after an uncertain seek.

Mark aligns a matching event. Press the first feed's Mark when an event appears
or is heard, then the other feed's Mark when the same event happens there. The
second mark automatically requests the full measured relative adjustment using
bounded seeks. It uses one feed when possible; otherwise it can split the exact
correction across both current windows, leaving room from their edges. Both
targets must settle before playback resumes. A zero gap needs no seek; an unavailable
full step is refused with visible feedback, without repeated automatic attempts.
The panel estimates which feed is ahead and which Advance row reduces any
remaining gap. It uses elapsed footage within each feed's own
clock, never a subtraction of unrelated provider timestamps. The estimate
updates after manual adjustments and while playback progresses; pauses and
buffering do not substitute wall time for footage. It is a human-marked estimate,
not a synchronization lock or a claim of frame/audio precision. Playback resumes
after a successful adjustment if both feeds were playing, following the existing
Advance behavior.

A later pair of Marks measures the remaining gap from the already adjusted
playback, adding or subtracting its finer correction rather than replacing the
first correction. Planning compares both single-feed options and can split a
correction when that leaves substantially more live-window headroom. It still
requests the exact correction and does not add a minimum-window-margin refusal.

A selected live target must be legal when dispatched. If its window advances
during loading, an originally playing Advance or Mark transaction may continue the same
SDK-confirmed held READY target only with its exact own clock, unchanged clock
kind and buffered data ahead, rechecked after focus and before each start. Both
current and buffered positions can be negative relative to a sliding window.
This follows the [Media3 readiness contract](https://developer.android.com/reference/androidx/media3/common/Player#STATE_READY)
and [live-window position model](https://developer.android.com/media/media3/exoplayer/live-streaming).
It adds no negative seek, retry or authorization renewal. A paused Mark transaction
whose selected target expires does not claim settlement; ordinary explicit Play
and Back to live retain their separate window guards.

Unknown historical adjustment totals remain unknown. They are labeled separately
from the latest Mark result, so an earlier failure does not keep branding a
successful fresh correction as an invalid anchor. A failed seek still reports
its specific readiness, clock, window, authorization or deadline cause, and a
refused pause retains its Stop-required warning.

Each mark stays in memory for the current pair. Mark requires two ready feeds
with known playback positions, outside advertisements and timing transactions.
The estimate waits during unavailable positions. Pressing an already **Marked**
button clears both marks without recording a replacement or undoing an applied
alignment. The next press starts a fresh measurement. A marked button can clear
the pair while clock positions are unavailable, once a timing transaction ends.
Successful alignment also clears both selections automatically, after the exact
requested settlement is observed. A zero-gap confirmed result clears them
immediately. A request, refusal or unfinished/canceled transaction does not claim
success or clear the marks. The saved immutable correspondence remains available
after the visible selections clear.
Changing a feed/session or its clock kind clears the marks. Swapping the floating
and primary panes preserves their A/B identities and marks. The same controls,
labels and behavior apply on Android phone, tablet and TV.

The Mark work passed shared Rust tests and the browser-binding smoke, the Android
JVM regression suite and focused timing/controller checks. Eight provider-free
UI fixtures passed on the physical Pixel 6 (Android 17/API 37), covering both
marking orders, exact correction dispatch and playback restoration, refused and
zero corrections, stale/retired controls, row focus and 48 dp targets in compact
portrait/landscape fixtures. These use synthetic playback members, not provider
video or an acoustic/frame comparison. Saved routes/accounts were preserved.
The ordinary debug build was installed and its shared signing identity verified;
the phone was returned to the normal launcher. Its APK SHA-256 is
`dabf821eaf5fce28545220259b4171494eaf350c5082fa3230bd2ef9039ee4dc`.
At the user's request, that same ordinary APK was also installed in place on the
NVIDIA SHIELD Android TV (Android 11/API 30), retaining its saved app data. This
confirms deployment, not a physical-TV playback or marked-alignment observation.

### Timing and overlay focus

A focused control keeps its identity across temporary busy/disabled states and
asynchronous updates. When it becomes available again, focus returns to that
same action. Select presses made while that action is temporarily unavailable
are consumed, including repeat and release events; they cannot activate an
automatically focused Pause button. Explicit navigation, touch, another action,
panel/menu changes and session changes supersede an older focus restoration.
The guard survives restoration itself: a later queued fallback focus change
cannot redirect the next Select to Sources or another control. That press is
consumed and the intended control regains focus; a fresh press can activate it.
Media Pause remains available. Reopened panels remember their last control,
including recreated Quality controls. Focused controls keep the overlay visible.
These rules apply to keyboard/remote focus across Android phone, tablet and TV;
they do not create focus in response to touch alone.

### Automatic feed recovery

Following the user's request on 2026-11-10, native viewing schedules recovery
independently for either or both selected feeds after eligible failures during
actual playback. A recent media DNS/connect/timeout or plain I/O failure,
transient HTTP response, or expired live window can start a bounded retry burst.
There are at most three automatic attempts, delayed by one, three and ten
seconds, and six attempts per ten-minute interval. Only a minute of sustained
clock/render progress resets the consecutive budget; READY alone does not.

Recovery uses the ordinary failed-slot cleanup and fresh preparation path,
keeping the healthy sibling's playback intact. Both-feed recovery can join a
newly prepared sibling within the same unchanged viewer request. Explicit
transport/timing actions cancel queued recovery and automatic resumption;
background/Stop ends the viewer. Existing route, provider-account, entitlement,
media-origin and cleanup checks apply to every attempt. TLS/cancellation,
licensing, policy and unsafe cleanup do not start an automatic retry loop.
Repeated failures leave the manual recovery action available.

ABEMA retries first check the existing account-readiness service, including
verified non-interactive renewal through the remembered browser session.
An account expiry signal may request that check once per incident; refusal or
a need for interaction stops recovery. A temporary readiness failure or check
deadline during an active automatic incident continues its bounded backoff
instead; it never starts an incident. The shared **Check connection** action
offers the same bounded check manually. Account settings wait for readiness
before loading their status, avoiding a stale reconnect warning after renewal,
and distinguish **Checking account connection…** from reading stream settings.

Recovery retains the pair's pre-failure enabled-offset intent across simultaneous
failures. Automatic resumption reuses the saved alignment hierarchy below;
an explicit Back-to-live or disabled offset is not silently re-enabled.

### Remembered pair offsets

The video panes currently omit their A/B source-title chips. Timing, source
selection and accessibility descriptions retain the feed identities. This
shared rule applies to Android phone, tablet and TV.

Timing includes an **Offset: On / Off** control and separate offset-value and
applied-offset readbacks. A successfully settled Advance or Mark adjustment
updates the pair's remembered correction automatically. Disabling removes the
currently applied correction through an exact bounded adjustment and retains
its value; enabling reapplies the saved relationship when its timelines appear
consistent, otherwise the session's seconds correction. The control changes its applied state
only after settlement, and a refused inverse does not report Off. It retains
focus across the same busy/save transitions as other timing controls.

The user approved automatic application when reopening a pair on 2026-11-07.
Both the fallback seconds correction and a settled Mark pair's own event
timestamps are now saved. Later manual adjustments update the correspondence's
desired residual without rewriting the actual event timestamps. Zero-gap marks
also save their correspondence. Refused, canceled or unconfirmed adjustments
preserve the previous saved data; clearing visible marks retains it until a new
measurement succeeds.

Once both feeds are prepared and READY, an enabled preference is tried once.
Consistent timeline evidence recalculates the needed movement from each feed's
elapsed footage since its marked event. It does not seek back to that event,
which can be outside the current DVR window. The actual movement can differ
from the fallback seconds correction; disabling reverses only the confirmed
movement from this session's baseline. A detected change or missing evidence
keeps the seconds correction as a fallback estimate, reported separately in
Timing. There is no partial clamp, periodic retry or additional live-default
seek, apart from the moved-feed recovery below. Disabled preferences do not
restore; a zero correction does not seek.

Timeline evidence is deliberately conservative: native DASH compares the
presentation epoch and period start; HLS requires program date/time and
overlapping sequence samples with matching clock mapping and discontinuity.
No overlap remains unverified. Replay compares duration within the exact saved
resource identity. These are consistency heuristics, not content-version proof
or confirmation of matching displayed frames, unchanged broadcaster delay or
audio synchronization. Only closed numeric metadata is retained, never signed
URLs, manifest contents or provider/account credentials.

While a viewer remains open, consistent overlapping observations advance an
in-memory witness without changing the marked event timestamps. This permits a
later feed retry to compare with the recently observed window. Unknown evidence
cannot advance a witness, and a new discontinuity is a change even when older
segments still overlap. A fresh application session starts from persisted
evidence; an expired HLS overlap can therefore remain unverified.

On 2026-11-10 the user approved using the previous offset as a better retry
estimate than zero when timestamp continuity is unavailable. Observations now
continue independently for the surviving feed while its partner is absent.
After Retry, both playing feeds first try the marked timestamp relationship.
Without consistent timestamps, the viewer estimates the movement needed to
recover its last settled playing relationship, using each live feed's distance
behind its own window end and replay position, sampled on a monotonic clock.
This avoids adding the entire saved correction again when the surviving feed
already retains part or all of it. If that session observation is unavailable,
the saved seconds correction is the fallback guess. Disabled offsets and
explicit superseding transport/timing actions still prevent automatic restore.
Existing readiness, advertisement, seek-window and settlement checks apply;
refused or unconfirmed movement never replaces the saved preference. Estimates
are not timeline-continuity proof, and changed live latency/window ends can
make them inaccurate. A retry still leaves the reversible scalar baseline
unknown, so it does not claim that the complete old correction can be toggled
off through an exact inverse.

On 2026-11-19 the user asked the viewer to recover when the feeds end up far
from the intended offset. On the SHIELD, ABEMA's player had repositioned itself
16 s toward live at the end of a commercial pod, with no user action and no
timing settlement, and the alignment was lost. Once an enabled offset has
settled and both feeds play, the viewer freezes their relationship from the
first steady sample: each live feed's continuous distance behind live, or a
replay's position, on a monotonic clock. Any change of pair, user playback
intent or timing settlement discards that reference: every seek, Mark, nudge,
Pause, Play or toggle. After a feed retry or Back to live the applied baseline
is unknown, so recovery stays off until the pair is reopened.

The viewer restores the frozen relationship only when all of these hold:

- both feeds have advanced in step with real time over the last second, so a
  stalled or frozen position, as during ABEMA's commercials, never triggers it;
- the relationship has moved by at least 3 s, or 10 s when a feed only offers
  its window-end distance, which saws by a refresh interval;
- the move has lasted 2 s.

The restore goes through the ordinary relative alignment, with its readiness,
advertisement, seek-window and settlement checks. It is logged as
`timing=OFFSET_RECOVERY`, and Timing reports "Alignment restored after a feed
moved on its own". It never changes or saves the preference. Attempts are at
least 15 s apart and at most three per ten minutes, so a correction the window
cannot hold does not loop; that count lasts for the viewer session, including
after a new alignment. Any one-sided move of at least 3 s qualifies, including
a feed that rebuffered while the other kept playing. A restore whose movement
cannot be confirmed leaves the offset unconfirmed, as any failed offset change
does, and playback may need Play. A feed whose provider shifts its content,
rather than its player, cannot be detected this way.

Live ABEMA feeds keep an in-memory rewind buffer of the provider's window plus
2 minutes ([#568](https://github.com/altendky/tachiai/issues/568); limits in
[security and privacy](security-and-privacy.md#live-rewind-buffer--2026-11-21)).
The feed's window grows to about 3 minutes as the buffer fills, so it can be
held or offset that far behind live. Ad periods the provider has dropped stay
available while they are buffered.

Measured on the SHIELD on 2026-11-21, live News with a Twitch feed on B:

- The window grew from 60 s to about 185 s within about 3 minutes.
- A 117 s hold then resumed from the buffer, about 151 s behind live.
- System shared memory rose by about 107 MB, and the Java heap was unaffected.
- A hold past the whole buffer was then refused as `LIVE_WINDOW_EXPIRED`.

On 2026-11-25 the user sized the buffer to the feed's delay
([#618](https://github.com/altendky/tachiai/issues/618)):

- **Floor plus growth.** A feed keeps the provider's window plus 2 minutes, so
  skipping back is instant. While it is held or offset further behind live,
  the buffer grows to its distance behind live plus 30 s. History the providers
  no longer offer can't be fetched again, so only what was recorded can be
  rewound to. At the start, the provider's window behind the starting point is
  recorded too.
- **Cap.** A 6:00 delay or 256 MB per feed, whichever comes first. An Advance,
  Mark or saved-offset restore that would hold a live feed more than 6:00
  behind live is refused with "Delay is limited to 6:00", and no feed moves.
- **Paused past the buffer.** Play on a live feed that was paused until it fell
  past its oldest recorded point resumes it from just inside that point,
  instead of failing into Back to live. When its partner can move forward by
  the same amount, both move and the pair keeps its offset. Otherwise only the
  stale feed moves, and the applied offset is reported as no longer held, as
  after Back to live. A notice names the feed, for example "A passed the oldest
  recorded point while paused, so playback resumed there." A feed that can't be
  sought still fails as before.

Live Twitch feeds play about 6 s behind
the live edge, using Twitch's low-latency playlists
([security and privacy](security-and-privacy.md#twitch-live-low-latency--2026-11-22)).

Measured on the SHIELD on 2026-11-22, one live channel, about 2.5 minutes per
setting:

| Setting | Behind the live edge | Buffered ahead |
| --- | --- | --- |
| Before | ≈ 20.9 s | 14–18 s |
| Low-latency playlists only | ≈ 18.4 s | 13–15 s |
| Plus a 6 s target | ≈ 7.9 s | 3–5 s |
| Plus prefetch hints as segments | ≈ 6.0 s | 6–8 s |

No setting rebuffered after startup. Since
[#618](https://github.com/altendky/tachiai/issues/618), live Twitch feeds keep
the same rewind buffer as ABEMA, beyond Twitch's window of about 30 s, with the
same floor, growth and 6:00 cap; the low-latency live edge is unchanged.

Each device stores its own encrypted, bounded pair preferences. Identity uses
the exact public source and provider-instance identities, excluding titles,
routes, authorization and local catalogue-item IDs. Reassigning distinct sources
between A/B reverses the correction/residual signs and swaps timestamp/evidence
sides; swapping the visible primary pane
does not. Identical exact sources retain logical A/B slot orientation because
their assignments cannot identify a reversal.

The existing v1 seconds records remain readable and migrate only on a successful
write. The v2 encrypted record remains bounded to 64 pairs and 8 KiB; timestamp
metadata can reach the byte limit sooner. Capacity failures preserve existing
records without eviction.

Requested-delta history is never saved as proof of a completed adjustment.
Pause/focus loss cancel pending restoration; Back to live, a replaced feed or an
uncertain movement makes the applied baseline unknown without deleting the
preference. After a feed replacement, an enabled consistent timestamp mapping
may restore once both current feeds are playing; unknown mapping does not cause
an automatic seek. This preserves the unknown scalar baseline, so automatic
reversal and remembering a new scalar total still require reopening the pair.
Fresh marks can save a new timestamp mapping independently of that baseline.
Explicit user
actions supersede a delayed startup restore. Read/write failures remain visible,
keep existing records and never imply a saved or applied result.

The ordinary debug build containing this feature passed 2,582 JVM cases with
one opt-in test skipped, scoped repository checks and the shared debug
certificate check. APK SHA-256 is
`eee934a0b04dd0f8fc7aeb13ebee7970421d3914fcd5b5ecc7d6dcf13a56618c`.
It was installed in place on the Pixel 6 and NVIDIA SHIELD without clearing
their saved state. All 25 provider-free timing, focus and offset-control cases
passed on the SHIELD, including both phone/TV configurations in offset/focus
fixtures. The phone had relocked before the new offset UI run, so that run was
not attempted; the earlier Back-to-live timing/focus run passed there while
unlocked. Synthetic clocks, storage and controls do not establish actual
provider alignment, persistence across provider latency changes or acoustic
synchronization. A fresh live marked-alignment retest remains outstanding.

The later timestamp-correspondence, automatic-mark-clearing and last-selection
update passed 2,699 JVM cases with one opt-in case skipped. App and test APKs use
the stable debug certificate. The latest app APK SHA-256 is
`cdc4f45e2919b96009a29ca4f585238d4bdf403b3adb8aa91ff117a02076e34f`.
Before the user's TV hold, 22 of 23 provider-free Mark/timing cases passed on the
SHIELD; the remaining case's old ad-state assertion was updated to test clearing
an existing mark during an ad and passed its targeted rerun. That run predates
automatic clearing and durable A/B selection. Their newer UI fixtures compile;
the phone was locked at the availability check and no further TV operation was
performed after the user asked to leave it alone. These checks do not establish
live-provider continuity, frame precision or acoustic synchronization.

System Back dismisses an open More menu first, then an Audio, Timing, Quality or
Status panel. Dismissing a panel keeps playback, volume/mute state and pane
selection unchanged and cancels any held adjustment button. On both phone and
TV, the next Back hides the overlay without stopping playback. Back with
all controls already hidden asks "Stop playback?" before returning to sources
([#617](https://github.com/altendky/tachiai/issues/617)). Keep watching is the
focused default, and Back on the dialog keeps playing. The top bar's Stop
(formerly the Back to sources button) and More's Stop ask the same. Stops the app makes itself, such as
failures, cleanup refusals and deadlines, don't ask. A timing step stays
focused while its adjustment runs, showing "Adjustment in progress", so a quick
repeated Select can't land on another control. The explicit Stop
button remains available; phone video taps and TV remote navigation
can reveal the hidden controls again.

The October 18 shared Back update passed ten phone-mode dismissal tests and
seven TV remote tests on the named API 36 TV emulator. Phone-mode fixtures
explicitly select normal UI mode; they are not physical-phone evidence. Both
existing local two-player TV flow cases passed, including a system Back that
hid the controls while both content clocks and rendered-frame counters kept
advancing, then Select revealed controls without pausing. Saved encrypted setup
was unchanged across installation and these checks. No provider media or
protected-video capture was involved.

Stopping a viewer ends request admission immediately. Network disconnect and
stream closure run on cleanup workers; player and quality release remain on the
player's application thread. Cleanup stays pending until native release and
provider preparation/cancellation work, including running finally blocks, have
settled. Helpers and routes stay owned until those receipts confirm completion.
Actual cleanup failure still blocks another viewer and offers recovery; pending
work alone does not count as failure or successful cleanup.

The **Back to live A / B** actions (in ⋯ More since 2026-11-25) target the
named live feed, including a single surviving feed. Completed replay and missing feeds have no live action;
current readiness, advertisement, SDK default-position capability, ownership and
busy checks determine whether the action is enabled. The same checks run again
before dispatch. An expired position can recover when the SDK still advertises
a valid live default in its current window.

Returning to live pauses every available feed and requests the selected feed's
SDK live default once. It waits at most eight seconds for the selected player to
be held READY within its current live window. When the available feed, or both
feeds in a pair, were playing before the action, playback resumes after fresh
ownership, audio-focus and position checks. An untouched peer can retain its
verified existing buffered live point under the same narrow continuation rules
as relative timing. Previously paused playback stays paused; a canceled or
failed check cannot restart it.
With two feeds, the attempted seek invalidates the timing anchor. Returning to
live does not restore alignment or establish an exact live-edge timestamp.
Pause, focus loss, member replacement/removal and session disposal cancel the
check. This reuses the guarded catch-up transaction without reloading a source,
renewing authorization or seeking the other feed as a fallback.

October 14 verification passed 2,782 JVM tests, lint, release isolation,
instrumentation compilation and APK assembly, with the shared debug signer.
All 29 affected viewer UI cases passed on persistent `tachiai-dev`, Android
16/API 36 x86-64, using fake members and blank player views. The checks cover
paired and surviving-feed recovery, changed capabilities/ownership, cancellation,
menu aliases, D-pad focus, compact large-font layouts and gesture ownership.
Popup cancellation injects one DOWN/UP pair at current window coordinates,
retaining every dismissal and no-tap-through assertion. Owned fixture inspection
confirmed the visible A/B controls and pause/resume guidance. Saved records stayed
byte-for-byte unchanged and the normal launcher was restored. Actual provider
live seeking, physical-device behavior and Android TV remain separate observations.

The product terms are **route / provider / stream / feed**. The picker assigns
streams to A/B feeds; its separate **Routes** and **Providers** buttons open
network configuration management and provider configuration respectively.
Routes and Providers share the upper-left header; Tachiai sits at the upper
right. Each provider heading includes its configured route on the same line.
The picker omits the assignment introduction, Stream/A/B header row, routine
playback-stopped message, separate A/B route summaries and experimental-playback
footer. Assignment checkboxes retain their accessible feed labels.
Provider type is separate from a configured instance: multiple named ABEMA or
Twitch instances may each choose their own route, and Twitch instances have
one saved Smart TV connection per instance for discovery and playback. Existing
retired-client records require explicit reconnect rather than token migration.
The picker assigns each feed a stream and instance
together; duplicate streams remain independent playback sessions. Simultaneous
ABEMA instances must use the same canonical route because their WebView proxy
override is shared; incompatible routes block the run before any route starts.
The earlier per-stream setup buttons are no longer in this flow.
Each saved provider instance now exposes **Manage streams**. Its Tachiai-owned
ordered list supplies the main picker; adding or removing items leaves upstream
account favorites unchanged. The common discovery UI is currently backed by
clearly labeled prototype samples, with provider account collections marked not
verified. Empty lists, unavailable resources and unresolved removed choices are
retained explicitly. See [configured sources](configured-catalog.md) for migration,
saved quality ownership, restart references and real-provider integration limits.
Configured exact Twitch videos within the existing native ID bound can also open
the debug replay viewer, starting at zero with that instance's Smart TV connection
and route. The exact historical replay keeps its experimental start position.
This path has provider-free session fixtures; arbitrary-video playback with a
real provider remains unobserved. Configured immutable Twitch broadcasters now
use worker-only current-login/live assessment, the shared Smart TV connection, and a
post-preparation ownership confirmation. Saved offline channels can be reassessed;
catalog connection failures remain distinct from playback-login failures. This
path also has provider-free fixtures; real connected-channel playback is unobserved.
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

With two selections, Open viewer immediately presents two labelled panes. Each starts with a
preparation message and independently becomes video or a scrollable error.
A successful feed starts without waiting for the other feed. A failed feed
closes its own session while a healthy feed continues. If both fail, the viewer
keeps both errors visible. Sources or Android Back, once confirmed, stops
playback and returns to the picker, retaining the selected sources.

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

### Network opens admitted before Stop — October 18

Back during real two-feed playback on `tachiai-tv-dev` produced
`PLAYBACK_CLEANUP_UNCONFIRMED`. Its bounded journal retained generic
`PLAYER_RELEASE / IO` and `FEED_CLEANUP_UNCONFIRMED`, without a corresponding
disconnect error or typed player-release timeout. This is the same Kotlin
teardown path used on phones.

The native cleanup receipt previously treated an unfinished admitted factory
as a permanent failure when sealed. Its cleanup snapshot could also omit the
resource returned later. Ownership is now reserved before each factory starts.
Sealing rejects new factories; worker-side cancellation runs before waiting
for admitted factories, and their eventual resources close before player and
quality release. A cancelled factory producing no resource does not invent a
cleanup failure. Synchronous close rejects pending factories without poisoning
the receipt, leaving them owned for asynchronous cleanup. Actual close errors,
executor refusal, arbitrary late ownership and player-release timeouts retain
their failure behavior.

Four deterministic regressions failed against the previous implementation;
all 17 receipt tests passed after the change. This identifies one teardown
defect; it does not resolve every sustained-playback network failure in #297.

On the named Android TV emulator (Android 16/API 36, x86-64,
WebView 143.0.7499.24), twelve native cleanup/coordinator tests passed,
including actual HLS and DASH players with synthetic input acquisition blocked
across Stop. Both existing local two-video TV flow tests also passed. Every
encrypted setup record was unchanged across installation and these tests.

A bounded configured ABEMA News live / Twitch Virtual Japan live check used
Windscribe Tokyo - Shinkansen for ABEMA and System network for Twitch, with
the existing TV-local account state. Both feeds reported first frame and
advancing playback. Back returned to an enabled picker without a recovery
dialog or a new cleanup failure, and Watch admitted another session without
restarting the app. No provider-page capture or credential entry was needed;
account expiry, exit country, acoustic output and physical TV behavior were
not independently verified. This is a short Stop/reopen check, not sustained
playback acceptance.

## Cached viewer cleanup ownership

The cached viewer uses a process-owned run acquired before route preparation.
It retains created routes, both feed sessions and helper contexts independently
of the Activity. Routes are registered before a worker delivers them to the
Activity; a helper is registered immediately after WebView creation, before
configuration can fail. Activity recreation or a cancelled preparation callback
cannot discard the last owning reference.

Disposal invalidates admission before invoking session callbacks. Confirmed
helper WebView destruction is a separate receipt from general cleanup success.
Only confirmed destruction permits proxy clearing, and only its matching
acknowledgment permits backend closure. Preparation must also have finished
before the routes can close. Each distinct route closes once after both slots
release it; one successfully closed failed slot can leave a healthy sibling
playing under the original shared deadline.

Native cleanup receipts own allocations before their factories can run and seal
new allocation before teardown. Work still allocating resources cannot produce
confirmed cleanup. Input-stream and connection close failures remain sticky;
all other cleanup steps are still attempted. A normally returning player release
also needs the absence of a release-time error. Session confirmation reads the
actual receipts before releasing route ownership.

Pending completion and known failure are separate states. A known cleanup
failure stops the group and starts bounded teardown immediately, while resources
still completing cleanup remain owned. Worker and internal player stop callbacks
contain cleanup failures so the queued safe recovery response can run.

Unconfirmed destruction, proxy dispatch or acknowledgment, cancellation or
backend closure retains the run and blocks replacement admission. A timeout or
late callback cannot manufacture cleanup confirmation. This state survives
Activity recreation in the same process and preserves the existing explicit
Ignore/Restart recovery flow; no automatic process termination or route
fallback is added. The separate metadata runtime and ordinary noncached viewer
retain their own lifecycle contracts.

Implementation and verification are tracked in
[#161](https://github.com/altendky/tachiai/issues/161). On 2026-10-11, the integrated
build passed 1,990 JVM checks, 46 bootstrap/helper JavaScript checks, lint, release
isolation and shared debug certificate verification. Seven selected synthetic
Android checks passed on `tachiai-dev` (Android 16/API 36, x86-64, WebView
133.0.6943.137), including real unstarted HLS/DASH player release and injected
in-memory owner/route/player receipts. These checks used no provider requests,
real proxy changes, saved-store writes or recovery reset. Encrypted saved records
were byte-for-byte unchanged, and the normal app entry was restored. Synthetic
ownership checks do not reproduce a provider-specific cleanup failure or establish
physical-device playback, audio or performance.

## Source assignment follow-up

The picker accepts one or two selected feeds. Clearing both leaves Watch
disabled; selecting only A or only B opens that exact slot full-stage in
portrait, landscape and TV layouts. No unused pane, floating rectangle, player,
provider preparation or route is created for the empty slot. A selected but
preparing or failed feed retains its pane and recovery actions; two selections
retain the two-pane layout even when one fails. Invalid selected items still
block Watch instead of being silently discarded.

Single-feed playback retains Play/Pause, overall volume, its mute and quality
controls, and applicable live recovery. Pair timing, mix/fade and floating-feed
actions are hidden. The selected feed uses overall gain directly; a missing
partner does not halve its volume. A/B remain logical identities, so B-only
playback retains B's session overrides, diagnostics and retry attribution.
Saved pair alignment is neither read nor applied for a singleton, and its stored
data remains intact. Last-selected A/B persistence retains an empty counterpart.

The single-feed update passed 2,709 JVM cases with one opt-in case skipped,
native-core build checks and app/test APK assembly with the stable debug
certificate. On the physical SHIELD (Android 11/API 30), 36 provider-free
viewer/timing/selection-restoration cases passed, followed by all 28 picker
cases after their phone-only checkbox/sharing fixtures were made explicit and
an incomplete provider setup fixture was repaired. The initial run was obscured
by the TV screensaver; the awake rerun established these results. A-only and
B-only geometry, volume/quality controls, selected failure recovery, two-selected
failure layout, Mark settlement and persisted partial selections are covered by
synthetic sources. This does not establish fresh provider decoding or audio.
The ordinary APK, SHA-256
`3d20fb1a614faba102347e55133cd71af85f9d82157bc759d0766a560b5a0422`,
was installed in place on the SHIELD and the ordinary app was reopened.

The subsequent provider-tree picker groups the options under ABEMA and Twitch
headings, with all children visible and indented. Source rows retain independent
A/B checkboxes. A small read-only dash indicator in each provider's A/B column
shows whether a child from that provider is selected for the column; it never
assigns a source itself. Child labels omit the repeated provider name, while
accessibility descriptions and viewer labels retain the full source identity.

The picker now renders one catalogue with independent A/B checkboxes. Checking
a row replaces only that slot's previous source; both boxes on one row are
allowed. Unchecking the current choice leaves that slot unassigned; Watch stays
available when the other selected source is valid. Source identities remain
saveable across rotation, and only validated selected sources reach preparation.
This changes selection UI, not duplicate-host ownership or provider handling.

Last-selected A/B choices also persist in their own encrypted no-backup record
across application restarts. Changes save both slots together, preserving partial,
empty and duplicate choices. Cold-start hydration restores the picker; Watch
still starts playback. Saved Activity state and explicit recovery checkpoints
take priority, including empty slots. A user edit supersedes a delayed read.
Only assignment state resets when hydration arrives, preserving picker navigation.
Removed source/provider-instance references become persisted empty slots with no
substituted source, so they never leave Watch blocked. Selection reads and writes share a process-level serial lane so Activity
recreation cannot let an older accepted write replace a newer choice. Storage
failures are visible and invalid records are not silently replaced.
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

### Collection episodes on the picker — November 19, 2026

[Issue #550](https://github.com/altendky/tachiai/issues/550) adds episode choice
for saved collections on the main picker. The user agreed the design on
2026-11-19 instead of an automatic newest-episode default (#549, not landed).
The provider's newest row for the sumo series was a preview talk, not the day's
coverage, so Tachiai does not choose an episode itself.

- **Episodes page.** A saved collection whose episodes can be browsed (today an
  ABEMA series or exact season, on API 28 and later) shows **Episodes ›** in its
  row. The row's subtitle reads "Collection · choose an episode", or names each
  feed's chosen episode ("A: …").
  - The page opens within the picker, the same on phone/tablet and TV. It lists
    episodes newest first in the provider's own order, with their provider
    titles. An episode with a known length shows it below the title, such as
    `1h 58m`, `45m`, or `40s` under a minute (#608).
  - **More** loads older pages, and **‹ Back** or system Back returns.
  - **Groups (#622).** A collection whose provider groups its contents shows
    those groups as tabs below the heading, in the provider's order, in the
    same style as the Find streams tabs. The page opens on the first group,
    which for ABEMA大相撲 is 生中継・見逃し (the full-day broadcasts). A tab
    lists its own contents from their newest page; collections without groups
    show no tabs.
  - **Broadcasts.** A group can list ABEMA broadcasts (slots) mixed with
    on-demand videos in ABEMA's order. A broadcast row shows its title and
    length like an episode row and is chosen for A or B the same way.
  - **Replay lengths (#672).** ABEMA lists a broadcast with its reserved slot
    span, which can be longer than the replay. While the page is shown, the
    real length of each finished broadcast is read from its replay manifest
    in the background and replaces the span when it arrives. A live,
    in-progress or upcoming broadcast, or one whose manifest cannot be read,
    keeps the span, with no error. Known lengths stay for the app session,
    shared with the provider screen's Browse episodes.
  - Loading, empty and failure states are shown, with Retry starting again
    from the newest page. Pages already loaded stay for the session.
  - Episode loads use their own worker, so they never delay Watch.
- **Choosing.** Episode rows have the same A/B controls as configured items.
  Choosing assigns that exact episode under the collection item; nothing is
  added to the configured list. The same collection can feed A and B with
  different episodes.
  - Unchecking the episode, or the collection row, empties that slot.
  - Choosing a different item for the slot drops its episode.
- **Watch.** With a collection assigned but no episode chosen, Watch stays
  disabled and the picker asks for an episode. There is no automatic default.
  Watch plays the chosen exact episode through the configured-episode path,
  or a chosen broadcast through the configured-slot path used for a slot added
  by link, with fresh eligibility checks. Retries and recovery keep it, and the viewer
  labels the feed with the episode title.
- **Remembering.** Chosen episodes persist with the A/B choices in saved
  Activity state, the encrypted last-selection record and the recovery
  checkpoint. Each stores the collection and episode public identities and the
  episode title.
  - Records without an episode keep their previous formats byte for byte.
    An episode keeps its original encoding; a broadcast uses a versioned form
    that also records the child's intent (#622).
  - An episode is dropped when its item no longer holds that collection.
- **Scope.** Collections without episode browsing keep the existing refusal.
  Each page is one series operation in the isolated metadata runtime. It took about 20–30 s until
  #629 (below) removed most of that preparation. Since #622 a first page makes
  three content-list requests (series, sections and contents) within that
  operation.

On the SHIELD (Android 11, signed-in ABEMA, Windscribe Tokyo route), the
Episodes page for ABEMA大相撲2026 listed, newest first:

- a stable-master preview talk;
- the new LIVE opening;
- 幕内全取組ハイライト for days 12, 11, 10 and so on.

Choosing day 12 for A, going Back and pressing Watch reached the episode's first
video frame. Provider-free JVM and JS fixtures cover the episode choice model,
selection, persistence formats, newest-first catalog continuations, IPC order
and the picker loader.

#### Account check before episodes — November 20, 2026

[#581](https://github.com/altendky/tachiai/issues/581): on the SHIELD the
Episodes page showed only "Episodes could not be loaded." after the saved ABEMA
grant expired. The failure journal recorded `ABEMA_ACCOUNT_EXPIRED`; logcat
recorded nothing. Discovery's account acquisition fails at once on an expired
grant and never starts renewal. Watch, feed Retry and Manage streams check the
account first (readiness), but the Episodes page did not.

- The first page and every Retry now run the same readiness check first. An
  expired grant starts or joins renewal while the page shows "Loading
  episodes…".
- A refusal shows the existing readiness message instead of the generic text,
  for example "ABEMA account could not be renewed. Open ABEMA account in
  Providers to check the connection." The wording and flow are the ones Watch
  already uses on every implementation; nothing platform-specific is added.
- **More** continues an already-admitted listing without another check.
- The page has its own readiness instance, so it never cancels Watch's check.
  Leaving the app ends a pending check like an interrupted load.
- Logcat now names the closed category of a failed lookup:
  - `TachiaiAbemaSeriesRuntime`, `TachiaiAbemaScheduleRuntime` and
    `TachiaiAbemaEpisodeRuntime` log the coordinator category, as
    `TachiaiAbemaSearchRuntime` already did.
  - `TachiaiAbemaGuestRuntime` logs `ACCOUNT_<failure>` (for example
    `ACCOUNT_EXPIRED`) when account acquisition refuses a lookup.
  - Lines hold closed constants only: no URL, identity, title or token.

The catalog IPC still reports an account refusal as a temporary failure. The
readiness check makes that a narrow race, and Retry runs the check again. A
dedicated IPC outcome was not added.

#### Episodes preparation time — November 23, 2026

[#629](https://github.com/altendky/tachiai/issues/629): opening Episodes spent
most of the 45 s operation deadline before the first provider request, and
several attempts ended `CANCELLED` at the deadline. Closed timing markers found
the cause: the metadata runtime re-read the saved provider and route records
(an encrypted, protected read) before and after every 8 KiB block of the four
cached public bundles, about 600 reads per operation. The bundle cache was
already hit on every warm run; the reads, not downloads or verification, were
the cost.

- Bundle reads and downloads now check cancellation, lease and deadline on
  every block, and re-read the saved owner at most every 500 ms and before
  each bundle and each download. The usual owner checks before the route
  lease, after the bundles and before any WebView request are unchanged.
  Identity/size/SHA-256 verification, the exact request admission, route
  rules and the deadline are unchanged.
- Bundles still come from the same on-disk cache and are hash-verified on
  every load. Keeping them in memory or warming a WebView was not needed.
- The timings and the remaining account check are recorded in
  [ABEMA metadata runtime](abema-metadata-runtime.md#preparation-timing).

On `tachiai-dev` (Windscribe Tokyo WireGuard, saved ABEMA account), opening
ABEMA大相撲2026 › Episodes and switching its episode groups succeeded on every
one of four cold-process and five warm-process operations after the change.
The first provider request came 4.2–4.9 s after preparation began, instead of
about 12–13 s on the same emulator before it (and about 34 s in the original
report).

### ABEMA channel between broadcasts — November 19, 2026

[#557](https://github.com/altendky/tachiai/issues/557) replaces the generic
"The provider could not prepare this feed." when an ABEMA live channel is not
airing a program Tachiai can play. On the SHIELD, 大相撲チャンネル served a
well-formed ten-period manifest with no protection declarations before the
day's broadcast. Live startup still refuses such a start, because its single
license exchange binds to the first manifest's key IDs. Only the wording changes.

- **Specific refusal.** A channel manifest with periods and no protection
  element, key ID or DRM marker anywhere fails with: "This channel isn't showing
  a program Tachiai can play right now. It may be between broadcasts. Try again
  when the program starts." Each sentence is on its own line, so it stays
  readable beside a floating feed. Every other refusal keeps its previous reason.
- **Last checked.** The pane adds "Last checked at 10:10:36 AM." in the device's
  time zone and locale. An off-air Retry otherwise ends in an identical pane,
  which looks as if nothing happened; the seconds make even a quick repeat visibly new.
- **Retry.** The pane offers **Retry**. This failure never starts automatic
  recovery. As with other retryable failures, the feed's selected route stays
  held while the pane waits, so Retry needs no new route. A saved specific
  broadcast that plays through its channel can show the same refusal.

On the SHIELD the refusal appeared repeatedly before the day's broadcast. Once
the channel switched to its protected broadcast manifest, Retry reached
licensed playback and video frames. The behavior and wording are the same on
phone/tablet and TV.

A next-program line from the channel timetable was implemented and then
withdrawn. During viewing, its guest-process lookup timed out every time on
the SHIELD and may disturb the held playback route.
[#560](https://github.com/altendky/tachiai/issues/560) tracked it behind
[#275](https://github.com/altendky/tachiai/issues/275). Once #275's guest lookups
leased the held route, the line returned in the waiting pane; see
[waiting for live shows](#waiting-for-live-shows--november-20-2026). On TV, a remote press on
Retry while the control bar is hidden only reveals the controls
([#559](https://github.com/altendky/tachiai/issues/559)). Starting automatically
when the program begins, and the ended indicator
([#553](https://github.com/altendky/tachiai/issues/553)), are separate.

### Ended feeds — November 19, 2026

[#553](https://github.com/altendky/tachiai/issues/553): a feed that reaches the
end of its media no longer sits on its last frame looking frozen. On the SHIELD,
an ABEMA replay ended after 9 min 51 s and a Twitch broadcaster's rerun ended
mid-session, both while the other feed kept playing.

- **Signal.** A prepared feed has ended when its own player reports its ended
  state outside an advertisement. The same player fact serves every provider:
  - ABEMA episode and slot replays: the last segment of a static DASH manifest.
  - ABEMA live slots: a dynamic manifest the provider closed.
  - Twitch videos and broadcasters: an HLS playlist the provider ended.
  - YouTube videos.

  Elapsed time, buffering and paused states never count as an end. A stream
  that disappears or errors instead keeps its existing failure, for example
  "The stream could not be found. A live channel may be offline."
- **Per feed.** The ended feed shows its own pane, "This replay has ended." or
  "This broadcast has ended.", chosen by the feed's configured replay or live
  kind. The other feed keeps playing, and the ended member leaves the pair as a
  failed one does. An end never starts automatic recovery and is not journaled
  as a failure.
- **Leaving.** A replay offers **Play again**, which reopens it from its
  beginning instead of its last remembered position, and forgets its saved
  position ([#655 part 2](#replay-navigation--655-part-2)). A broadcast offers
  **Retry**, which reopens the source, for example when the broadcaster is live
  again. Sources leaves the viewer as usual. There are no new seek or jump controls.

The presentation is the same on phone/tablet and TV.

### Waiting for live shows — November 20, 2026

[#587](https://github.com/altendky/tachiai/issues/587) lets a feed wait for its
show instead of failing, for example when Watch is pressed before sumo starts.
The user approved the design on 2026-11-20 for all implementations. It is one
presentation on phone, tablet and TV.

- **What waits.** A feed waits only on a provider signal:
  - a Twitch channel that is offline;
  - an ABEMA live channel between broadcasts (the #557 refusal);
  - an ABEMA broadcast slot before its start (the bootstrap now reports this
    as its own phase, with the slot's start in epoch seconds);
  - a live channel whose show ended (a Twitch or ABEMA channel reaching
    "This broadcast has ended." now waits for the next show).

  A slot or replay that ended keeps its ended state. Route, account, DRM and
  other refusals keep their current messages.
- **The pane.**
  - With a known start: "Not live yet. Starts at 9:30 PM (in 29 min)." The
    countdown shows hours and minutes, seconds under a minute, and the weekday
    for another local day. After a passed start: "Not live yet. Should have
    started at 9:30 PM."
  - Without one: "Waiting for this channel to go live."
  - A waiting ABEMA channel adds its scheduled program when known, see
    [next program](#next-program-on-a-waiting-abema-channel--november-22-2026).
  - The last line reads "Last checked 9:01 PM".
  - The button is **Check now**: it checks at once and restarts the timing.
- **Start times.**
  - ABEMA slots: the slot's own start.
  - Twitch: one status request when waiting begins, which also tells an
    offline channel from a missing one and carries any published schedule
    start (#122). Later checks in the same period skip it.
  - ABEMA channels: the next program's start from the channel's timetable
    (#560) times the checks only. The pane's first line stays unchanged.
- **Checks.**
  - Every minute; every 15 s within 2 minutes of a known start.
  - A slot isn't checked before its start, then every 15 s.
  - Each check adds up to 10% jitter. Rate limits double the interval.
  - Each check is an ordinary feed retry: the other feed is untouched, and a
    waiting feed spends no automatic recovery attempts.
  - ABEMA checks try the start directly. Only an account failure while waiting
    sends the next check through account readiness, once; a second account
    failure shows the usual account message.
  - Passing failures (network, provider 5xx, catalog unavailable) back off
    from 30 s; five in a row show the usual failure with Retry.
- **Going live.** The feed starts by itself, independently of the other feed,
  and plays even after a user pause. The existing pair-offset logic aligns the
  pair once both play.
- **Screen and lifecycle.** The viewer keeps the screen on while it is open,
  so the TV doesn't go to its screensaver while every feed waits. Leaving the
  app stops monitoring, as it stops playback.
- **Logs.** Waiting checks are not journaled as feed failures.

Provider-free JVM and JS tests cover the waiting policy, cadence, countdown
wording, slot-start plumbing and the Twitch offline mapping. Device
observation is recorded separately.

### Channels ABEMA isn't opening — November 22, 2026

[#632](https://github.com/altendky/tachiai/issues/632): between broadcasts,
ABEMA reports some live channels as not open for viewing
(`viewingOpened: false` on the channel list). Playback stays refused, but the
feed now waits instead of showing "The provider could not prepare this feed.".
It uses the same waiting checks and automatic start as a channel between
programs, and the pane says which state it is in:

- "This channel isn't open for viewing right now. Waiting for it to open." for
  a channel ABEMA isn't opening (user decision, 2026-11-22: always wait).
- "Waiting for this channel to go live." for a channel between programs, as
  before.

The next-program line from #560 applies to both. On the `tachiai-dev` emulator
(Windscribe Tokyo, saved account), ジャンプフェスタチャンネル (`jumpfesta`) was
off air. It showed the first line, then "Next: 夜桜さんちの大作戦 #1〜13【無料ビデオ1週間】
at 10:00 PM (in 2 h 8 min).", then "Last checked 7:51 PM". The channel-list
check now always reports its closed `CHANNEL_UNAVAILABLE` phase; other channel
phases stay diagnostic-only.

### Next program on a waiting ABEMA channel — November 22, 2026

[#560](https://github.com/altendky/tachiai/issues/560): a waiting ABEMA live
channel shows its next scheduled program. This rebuilds the line withdrawn from
[#557](https://github.com/altendky/tachiai/issues/557). Its guest lookup now
leases the route the feed already holds
([#275](https://github.com/altendky/tachiai/issues/275) stage 4), instead of
starting a competing device. The pane is the same on phone, tablet and TV.

- **The line.** It sits between the waiting line and "Last checked":
  - "Next: 大相撲LIVE 十一月場所 13日目 at 9:30 AM (in 29 min)." The time
    follows the device's zone and 12/24-hour setting. The countdown and the
    weekday for another local day match the waiting pane's.
  - "Scheduled now: 大相撲LIVE 十一月場所 13日目 (until 6:00 PM)." when the timetable lists a
    program now but the channel isn't playable yet.
  - Nothing when no program is known, the lookup fails or times out, or the
    next start has passed. The pane then reads exactly as before.
- **Source.** ABEMA's legacy timetable, filtered to the channel. Channels the
  timetable leaves out keep the plain pane. 大相撲チャンネル (`sumo`) is one of
  them; [#630](https://github.com/altendky/tachiai/issues/630) covers it.
- **Never in the way.**
  - The pane shows at once; the line appears when an answer arrives.
  - Lookups run on their own worker, one at a time per feed. Waiting checks,
    **Check now** and Retry never wait for them.
  - A result from an earlier lookup or an earlier viewer session is dropped.
  - No lookup starts while the next check goes through account readiness.
- **How often.** Only on a refresh of the waiting state, and at most once a
  minute.
  - An answer is reused for 30 minutes, or until its next start (or the current
    program's end) passes.
  - Failures back off from 5 minutes, doubling up to an hour. A rate limit
    waits at least until its retry time.
- **Checks.** The known start times the waiting checks like any known start:
  every 15 s within 2 minutes of it. It takes effect from the next scheduled
  check.
- **Boundaries.** Times appear only in this line. Channel **Browse programs**,
  the Schedule collection and configured rows still show no times; see
  [ABEMA catalog access](abema-catalog-access.md#public-schedule-snapshot).
  Logs hold no title, identity or URL.

Provider-free JVM and JS tests cover the projection, the now/next selection,
the wording, cadence and dropped results. The device acceptance in #560 is
still open: the line appearing within the deadline while the other feed plays,
and a Retry during a lookup staying unaffected.

### Provider text translation — #643

[#643](https://github.com/altendky/tachiai/issues/643) translates
provider-supplied text into the device's language for display. This is common
UX: phone, tablet, TV and future Apple and web clients follow the same rules.
Only the engine differs; see the
[implementation difference ledger](implementation-differences.md#android-phone-and-tv).

- **What is translated.** Provider text only:
  - configured stream titles and the picker's **A:**/**B:** episode lines;
  - Episodes tabs and rows;
  - Find streams, search, Schedule and browse/children rows, their group tabs
    and the browsed parent's name in the heading;
  - the viewer's feed labels and a waiting channel's scheduled program title.

  Tachiai's own labels, tab names and messages are never translated. Neither
  are titles Tachiai makes itself: link placeholders such as "ABEMA broadcast ·
  sumo/…" before provider metadata, "Unavailable Twitch video …", unchecked
  YouTube and Twitch links, and legacy built-in labels. Each is recognized by
  the function that builds it, for that exact resource.
- **Display.** The original shows at once. When a translation is ready it
  replaces the original in place; the two are not shown together. Nothing waits
  for translation, and row order, keys and focus stay the same. Rows keep
  their existing line limits, so a swap does not move remote focus.
- **Display only.** Search, matching, saved streams, saved episode choices,
  identities and the stored selection keep the original text.
- **Settings.**
  - **Providers → Translation → Translate provider text** turns it on or off.
    It is off by default. Its status line says when a model is downloading, or
    when translation is unavailable.
  - Each provider's details have a **Source language**: **Auto** or a fixed
    language. ABEMA defaults to **Japanese**; Twitch, YouTube and later
    providers default to **Auto**. **Auto** detects each string's language on
    the device. Text already in the device's language, or whose language is
    unclear, stays original.
  - A fixed language skips detection. This avoids kanji-only titles such as
    大相撲 or 十一月場所 being detected as Chinese.
  - When the fixed language has its own script, text with none of that script
    stays original. For example, a Latin-only title under ABEMA's Japanese is
    not translated. Japanese needs kana or kanji, Chinese Han, Korean Hangul,
    Russian Cyrillic, and so on. Latin-script languages are not checked.
- **On-device.** Provider text is translated on the device and is not sent to
  a translation service. Turning translation on allows a one-time model
  download from the platform's engine, about 30 MB per source language.
- **Unavailable.** If the device language or a source language is unsupported,
  or a model cannot be downloaded, originals stay and the setting says
  translation is unavailable. A failed download is retried after 5 minutes.
- **Cache.** Translations are cached in memory per process, at most 1,024
  entries. A key is the source text with its language pair; no provider
  identity or account data is kept with it. Nothing is cached on disk.
- **No glossary.** There is no sumo or other domain glossary, as decided.

Provider-free JVM tests cover the translator states (original first,
replacement, unsure, unsupported and same-language text kept, cache hits,
download failure and retry), Source language defaults, the script guard,
Tachiai-made titles and the stored setting.
The device record in #643 is still open: model download and translation on the
phone and SHIELD, and the quality of representative ABEMA titles. The ML Kit
usage terms raise a question for TV; see
[open questions](open-questions.md#product).

### Text in the video — #664

[#664](https://github.com/altendky/tachiai/issues/664) translates text that
appears in the video picture, such as ABEMA's sumo name and result cards, on
the device. The user decided the design on 2026-11-25. It is common UX: phone,
tablet and TV have the same setting, control and display.

- **Setting.** Each provider's details have **Text in video → Translate text
  in video**, beside its **Source language**. It is off by default for every
  provider. It is independent of **Translate provider text**, uses the
  provider's Source language and the same translation models, and turning it
  on starts that model's download for a fixed Source language. Its status line
  says when the model is downloading or translation is unavailable.
- **In-stream override.** **⋯ → Translate text in video** is a checkable item
  that turns it on or off for the stream being watched, overriding the provider
  default. The choice is not saved: it ends when the stream is closed, and
  reopening a stream uses the provider default. A brief notice confirms the
  change.
- **Main pane only.** It runs on the main (full-size) feed: the only feed, or
  the primary feed of a pair. The floating inset feed is never sampled.
  Swapping the primary feed moves translation to the new main feed, with that
  feed's own default or override. ABEMA and Twitch both offer it.
- **Unavailable feeds.** A feed whose frames cannot be read, such as a future
  secure-decoder (Widevine) feed, shows the item as "Translate text in video
  (unavailable for this feed)" and, when its default is on, a brief "Text in
  video: unavailable for this feed" notice. Tachiai never works around an
  unreadable picture. Today's native feeds are readable: Twitch and YouTube are
  clear and ABEMA uses ClearKey with a software CDM.
- **Stable text only.** About every 2 s the main feed's picture is sampled at
  960 × 540 (keeping its aspect). Text counts only when the same recognized
  text is in roughly the same box (overlap of at least half) in two
  consecutive samples, with lines at least 3% of the frame height (16 px at
  540). Tickers, moving and partial text are skipped. A translation is held
  until its source block changes or disappears for two samples, so it does not
  flicker. At most eight blocks are held. When the downscaled picture is
  unchanged, recognition is skipped and the previous result counts as the
  next sample.
- **No stacking.** A newly stable block replaces any held block whose box
  covers at least 30% of the smaller box, such as a new caption over an old
  one. The replaced text stays suppressed while it is still read. A name and
  its printed reading are not treated as overlapping.
- **Jumps start over.** Any position discontinuity drops all held blocks and
  chips for the feed, and text must settle again. This covers a seek or skip
  from any control (transport, scrub, remote Left/Right, fast-forward and
  rewind, double tap), Back to live, start over, a timing shift that seeks,
  and a resume at a new position. It is detected from the player's seek,
  seek-adjustment, skip, removal and item-change discontinuities, from a new
  player instance, and from the position moving more than 3 s from what
  playback explains between samples. A sample taken before the jump is
  discarded.
- **Display.** A near-opaque chip with the translation covers the source block,
  replacing it in place, as #643 does for titles. It is at least the size of
  the source, with text sized from the source's line height, and stays inside
  the area left clear by the visible top bar, transport and panels (#656).
  Chips take no focus and no touches. Text that is already in the device's
  language, whose language is unclear or that cannot be translated keeps the
  picture as it is. Vertical text gets no special handling.
- **Names with a printed reading.** The offline translator garbles proper
  names (on the SHIELD 王鵬 became "Rang" and 伯乃富士 "Mt."), while broadcasts
  commonly print a name's kana reading directly above it. A short kanji-only
  name (one to six kanji) with a kana-only reading directly above it is
  treated as a name with its reading (user decision, 2026-11-25). "Directly
  above" means the reading covers at least 60% of the narrower width, and the
  gap is at most about one line of the name. The name's chip shows the reading
  romanized instead of a translation, and the reading gets no chip of its own:
  おうほう over 王鵬 shows Ōhō, and はくのふじ over 伯乃富士 shows Hakunofuji.
  - **Lines, not blocks.** Pairing uses recognized lines and elements, before
    the stability and minimum-height filters, since a reading never gets its
    own chip.
    - A name may be a whole line, or a run of two or more kanji elements in a
      longer line, for example beside a rank.
    - A reading may be one line or several kana lines on one row, such as
      letter-spaced characters. Letter spacing is removed unless the name
      itself has a space.
    - The rest of the card (a rank or record in the same block) is translated
      piece by piece.
  - **Remembered.** A pairing is remembered while its name stays, so a sample
    that misses the small reading keeps it.
  - **Small readings.** A sumo card's reading is only about 10–12 px tall at
    540 lines, too small to read reliably. While a name has held still for two
    samples without a readable reading, the next capture is taken at up to
    1920 px wide. The worker scales it down for the normal read and reads an
    enlarged crop of the area above the name. This is limited to three crops
    per sample, and a name whose crop finds no reading is not retried while it
    stays. Moving text never triggers it. The larger frame is released as soon
    as no reading is sought.
  - Romanization is Hepburn. Long vowels take macrons (おう, おお → ō; うう → ū;
    ああ → ā; ええ → ē; katakana ー lengthens the previous vowel), while いい and
    えい are written out. Small ゃゅょ and small vowels combine (きゅう → kyū,
    ファ → fa), っ doubles the next consonant (っち → tchi), and ん before a vowel
    or y is written n'. Each word is capitalized.
  - Only such pairs are romanized. Other kana, including kana with no name
    below it, is translated like any other text. The rule follows the general
    reading-above-name convention and is not specific to sumo.
  - When the device language is Japanese, nothing is romanized.
  - **Limitation:** a name with no printed reading is still translated, and may
    be garbled. A name source such as provider metadata could come later.
- **On-device and transient.** Frames, recognized text and translations stay
  in memory; they are never saved, logged or sent off the device. Frames are
  released when the feed stops being translated or the stream closes. Logs
  under `TachiaiFrameText` hold only timings, block counts, states, pairing
  geometry (counts, overlap and gap ratios, and heights in pixels) and the
  sampling cadence. Translations use a separate in-memory cache of 128
  entries, so they never evict title translations.

Provider-free JVM tests cover stability and hold, box mapping and chip
placement within the overlay insets, enablement (provider default, temporary
override, unavailable frames, inset skipped), the unchanged-frame check,
romanization, the reading-above-name pairing, the stored setting, and that
logs carry only numbers.

On 2026-11-25 the coordinator measured a SHIELD Android TV (Android 11)
playing an ABEMA sumo day-15 replay as a single feed:

| Measure | Median | p95 | Max |
| --- | --- | --- | --- |
| `captureMs` | 3 | 4 | 5 |
| `ocrMs` | 212 | 423 | 604 |
| `intervalMs` | 2,235 | 2,441 | |
| `translate ms` | 53 | 193 | |

- No frame was skipped as unchanged (0 of 38).
- The player process used about 70% CPU (of 400%) with translation on and
  about 42% with it off, roughly 0.3 of a core more.
- There was no rebuffering in about 3 minutes.
- The More item turned translation off at once, and the chips cleared.
- Ordinary text translated well (26歳 → "26 years old", 東京都 → "Tokyo"), but
  names were garbled, which led to the reading rule above.
- On ABEMA News live, tables and captions translated well, and translation
  stayed on while the live transport was used. A chip from before a 1-minute
  skip back or a resume stayed on screen under new chips, which led to the
  jump reset and overlap replacement above.

The #664 acceptance is still open: the Pixel 6 measurement, and the
reading-over-name result on a device.

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

Exact configured ABEMA episodes now carry their immutable resource through the
cached session, rather than selecting a replay from a Boolean alone. Fresh modern
metadata, raw/converted identity and ARIN, free terms, selected source/MPD and
initial license must agree with that episode and its fresh token. Saved UNKNOWN
availability permits verification; unknown or ineligible runtime metadata refuses
playback and retains the configured item. Provider/route ownership is reread on
workers, and the process-owned cleanup boundary applies to both independent
duplicate feeds. The original full-page comparison refuses new configured
episodes. Exact configured linear channels use the same identity and ownership
boundary: a fresh channel snapshot must admit the selected ID, and its source,
MPD and initial channel license must agree. The historical comparison refuses
new channels. Slots, series and account-backed selections remain separate work;
these paths do not substitute News or a fixed replay. See
[configured episode playback](abema-catalog-access.md#exact-configured-episode-playback).
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
