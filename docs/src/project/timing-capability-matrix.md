# Timing capability matrix

This is a bounded playback experiment, not a production capability contract.
Forward means later footage; backward means earlier footage. A hold increases
delay over elapsed wall time and is not an immediate backward seek. Forward on
live content must remain within already received, currently seekable media.

## Native Twitch comparison — 2026-10-06

On the same Pixel 6/Android 17, additive Media3 1.11.1 native timing cases
observed replay backward/forward five-second settled clock and match-frame
changes. Running live backward/forward also changed the media clock within a
30-second advertised window and returned to playing after buffering. A bounded
hold/resume retained about 37.5 seconds of added live delay; default-live recovery
then reduced the reported offset from about 63.4 to 28.2 seconds.

See [exact measurements and conditions](native-access-experiments.md#corrected-native-live-timing-results--2026-10-06).
The live music-artwork fixture does not prove moving-event/acoustic alignment.
Thirty seconds of advertised seeking is not a measured retention maximum: old
buffered playback resumed outside that window, but arbitrary small seeks there,
eviction, ads and sustained/dual playback remain untested. Relative actions
refuse a direction-reversing clamp; catch-up is an explicit separate recovery.
No ABEMA native media or license handling was added.

The original four-case web results below remain unchanged. These native timing
observations must not be attributed to Twitch's official embed SDK.

## Native ABEMA News comparison — 2026-10-06

On the same Pixel 6/Android 17, Media3 1.11.1 native free News played changing
video and user-confirmed audio through its own DRM session and the bounded,
unchanged browser-helper exchange. This is an anonymous live News experiment,
not evidence for recorded/sumo/paid sources or a provider-supported API.

| Action | Observed result | Limit |
| --- | --- | --- |
| Backward 5 s | Exact −5,000 ms held content-clock shift; running playback recovered | Within the advertised ~60-second live window; paused footage was a still news photo |
| Forward 5 s | Exact +5,000 ms held content-clock shift; running playback recovered | A healthy-manifest edge repeat recovered after waiting; an earlier stale-window edge request failed |
| Hold/resume | ~10 seconds of extra live delay retained | Short foreground test, not maximum retention |
| Live recovery | Offset reduced from ~43.5 to ~22.2 seconds; playback resumed | SDK default target, not zero latency |

Manifest refreshes refused the model for an as-yet unclassified reason in one
attempt, but remained healthy in a subsequent two-minute edge repeat. Slightly
negative reported edge offsets expose uncertainty in native clock normalization;
they are not physical-latency or future-footage claims. General native replay,
transition/rotation handling, general edge reliability and sustained mixing
remain unverified. See [measurements and failure context](native-access-experiments.md#native-news-timing-and-refresh-limit--2026-10-06).

## Native ABEMA free replay comparison — 2026-10-06

The additive fixed free sumo replay case played changing native video on the
same phone and settled held Back 5 s from 48,466 to 43,466 ms and Forward 5 s
back to 48,466 ms. Play resumed afterward. This establishes both seek directions
for this bounded replay, not every ABEMA recording or frame-accurate alignment.
An October 7 coordinated repeat confirmed moving native video and user-heard
commentary while the original web video was paused/muted with a fixed clock.
The earlier ambiguous acoustic report remains separate; renewal, WebView
disposal and sustained mixing remain unverified. See
[conditions and limits](native-access-experiments.md#native-free-replay-and-held-timing--2026-10-06).

## Native mixed-provider pairs — 2026-10-07

The additive pair host has played ABEMA/Twitch native replays together with
advancing clocks and changing sumo/Rocket League footage. A coordinated native
News/RelaxBeats live pair subsequently advanced both clocks and produced
user-confirmed mixed audio at 50% each, with original ABEMA web playback
paused/muted. Twitch live paired ±5-second requests and ABEMA live paired −5
resumed both after the selected target check. ABEMA paired +5 resumed in an
earlier run, but a later repeat stopped after an unrecognized protection
initialization on manifest refresh. Closed diagnostics later reproduced a
completed MPD parse with no protection/default-ID declarations; our unchanged
initialization gate refused it, and playback stopped after buffered media.
A longer replay-pair repeat also produced
user-confirmed sumo/Rocket League mixed audio with the original web video held.
Held paired replay ±5-second requests settled exactly on both selected clocks,
with the other clock unchanged, and both resumed afterward. These are unrelated
programs, not a measured common-event offset; seek/rebuffer hold time can change
the resulting offset.

A separately selected LIVE_TRANSITIONS comparison retains the original strict
cases and adds bounded whole-document declaration-free refresh admission, an SDK
no-DRM-data check and same-session keepalive. A five-minute protected live pair
reached its intended cap without another exchange or playback failure. It had
no declaration-free refresh, so it does not establish transition durability.
Another attempt admitted a real declaration-free interval and return to the
same protection shapes. Its first native DRM request then arrived after the
unchanged browser helper's two-minute lifetime and was refused; buffered media
ran out and the pair stopped. There had been no initial native keys/session to
reuse in that attempt. Separate aligned-helper cases subsequently passed both
a fixed late-first-exchange test and a real declaration-free refresh/return
with its late first exchange, continuing to the original five-minute cap.
Those runs loaded keys only on return. A later LIVE_RELATIVE prewarm run loaded
native keys before Play, then continued across a real NO_DECLARATIONS refresh
and SAME_PROTECTION return without a second exchange. This settles that bounded
initialized-session transition check on the Pixel 6, not sample protection,
advertisement identity, rotation or renewal. Old examples and one-exchange
limits remain intact.
See the [comparison evidence](native-access-experiments.md#additive-whole-document-transition-comparison--2026-10-07).

| Native provider/source | Earlier footage | Later footage | Remaining limit |
| --- | --- | --- | --- |
| Twitch replay | Exact held −5 s in mixed pair | Exact held +5 s in mixed pair | Fixed public VOD; frame/audio precision and sustained lock unverified |
| ABEMA free replay | Exact held −5 s in mixed pair | Exact held +5 s in mixed pair | Fixed free episode; helper remains attached; renewal unverified |
| Twitch live | In-window −5 s held-target check and joint resume | In-window +5 s held-target check and joint resume | Finite advertised window; artwork is not moving-event evidence |
| ABEMA News live | In-window −5 s held-target check and joint resume | In-window +5 s held-target check and joint resume, including post-transition repeat | Declaration-free interval/return passed after initialized keys with one exchange; sample protection, rotation and renewal unverified |

Additive LIVE_RELATIVE/REPLAY_RELATIVE cases now expose **Advance A vs B** and
its inverse: each selects exactly one full five-second A movement or opposite
B fallback using that source's own window. Both mixed replay signs passed held
checks (including B fallback at A's replay start); both live signs passed
running held-target checks and joint resume. Neither partial clamping nor an
expired live point silently fulfills a relative request. A long pause aged both
live points outside their windows: the inverse refused, and later buffered
resume ended with code 1002. Recovery remains a separate explicit operation,
not an automatic side effect of alignment. The requested movement ledger is not
a measured difference between unrelated programs.

Explicit Catch up A/B now requests the selected live SDK default with both
players held and invalidates the prior adjustment anchor. In a bounded two-live
device repeat, both expired points were recovered separately, then prompt Play
advanced both clocks with changing News video; a later ordinary nudge resumed
without restoring the invalid anchor. Inspection delays can age recovered points
out again. An opt-in current-window Play guard for these relative examples is
now refused stale resume on both providers in mixed live/replay device checks.
Each recovered live side left its replay peer fixed, and explicit Play resumed
both afterward. Catch-up on either provider's replay refused without movement
or anchor invalidation.
READY/in-window is not exact live-edge settlement, decoded-frame precision or
unlimited recovery. Replay catch-up is unsupported; no reload/renewal is added.
See [relative-control evidence](native-access-experiments.md#additive-native-relative-nudge-comparison--2026-10-07).

The fixed ABEMA-replay/Twitch-live relative case also passed both held five-
second signs and joint resume on the same phone, with independently observed
replay/live flags and advancing clocks. Two inverse live/replay News captures
expired at WAITING_TRANSPORT before native preparation. A subsequent retry with
closed wait diagnostics reached the same unchanged gate and passed both held
signs and joint resume, with native moving News/Rocket League footage. All four
fixed type combinations therefore have bounded relative-control device checks;
the intermittent initial capture wait remains unresolved. No new content route
or authentication is introduced by these mixed-type examples.

The live/replay edge test also observed equivalent B-backward fallback when
A lacked another full forward step; later requests selected A again when its
sliding window made the step available. Twelve held requests passed without
partial clamping. This is selection before dispatch, not a retry after failure.

The five-minute pair-only cap allows a listening window without permitting
renewal. Older cases keep the unchanged two-minute helper; separately selected
aligned cases extend only an unused initial exchange to the pair deadline.
Waiting paused can age the content point
outside an advertised live window: backward requests then refused, while forward
requests clamped to the current window start rather than moving exactly five
seconds. Buffered playback beyond that window does not imply arbitrary seeking
there. See [coordinated evidence](native-access-experiments.md#coordinated-native-live-mixing--2026-10-07).

## Pixel 6 experiment — 2026-10-04

Environment shared by all four cases:

- Pixel 6 (`oriole`), Android 17, Android System WebView 153.0.8010.36.
- Tachiai debug build, landscape, one original provider player visible at a
  time. This isolates primitives; it does not test simultaneous alignment or
  mixed audio. The screen remained awake during the experiment.
- Existing app-private browser storage was preserved and not inspected.
  Neither provider demanded sign-in, payment, or a subscription change. The
  account's exact state and entitlements were not independently established.
- The system VPN indicator was visible. The user authorized proceeding with
  that indicator; the exact exit and accepted region were not independently
  verified. ABEMA loaded without its previously observed region-rejection
  message. VPN operation remains an unsupported experimental condition, not a
  supported regional-access solution.
- Twitch used its official SDK in an app-owned packaged page with the default
  WebView identity. ABEMA kept its original top-level player under the
  previously investigated, unsupported Windows Chrome-like browser identity.
- Readbacks are rounded to milliseconds. Matching displayed timestamps is not
  frame-accurate or acoustic alignment evidence. Requests are distinguished
  from subsequent observations; `paused: false` alone is not proof of playback.

| Provider/source | Backward 5 s | Forward 5 s | Evidence and limit |
| --- | --- | --- | --- |
| Twitch recording | Observed clock match | Observed clock match | Paused SDK seeks matched their targets; earlier moving-match playback was established separately. |
| Twitch live | Unsupported through tested SDK | Unsupported through tested SDK | Both controls refused without issuing a seek. A timed hold did not retain the requested additional latency. |
| ABEMA recording | Observed clock and frame change | Observed clock and frame change | Original media element settled at both targets; playback resumed afterward. |
| ABEMA live | Observed clock match within exposed window | Observed clock match within exposed window | Original element settled at both targets and retained delay after resume. The live channel was showing an intermission; footage-level confirmation remains open. |

The results do **not** establish all eight product actions. In particular,
Twitch live still lacks a verified useful timing primitive on the supported
embed path. ABEMA's live results need a moving-program repeat and transition
tests before promotion from experimental clock evidence.

## Twitch recording

Public fixture: [Rocket League VOD 2080217716](https://www.twitch.tv/videos/2080217716).
The timing probe starts this VOD at 70 minutes, where the earlier two-copy
experiment rendered match footage. SDK duration was 14,407.230 seconds.
Native Play produced the SDK's `PLAYING` event and an advancing clock before
the isolated seek test.

| Action | Held starting clock | Requested target | Settled paused readback |
| --- | ---: | ---: | ---: |
| Backward 5 s | 4255.535 | 4250.535 | 4250.535 |
| Forward 5 s | 4250.535 | 4255.535 | 4255.535 |

The SDK reported `SEEK` and retained pause state. This run did not independently
compare frames or sound at each target. Previously documented moving-video
and running relative-control observations remain separate evidence.

## Twitch live

Public fixture: [RelaxBeats](https://www.twitch.tv/relaxbeats), reported online
and playing during the run. Native and app-page SDK Play worked while the
original player was visible.

[Twitch's documented embed API](https://dev.twitch.tv/docs/embed/video-and-clips/)
limits `seek`, current time, and duration to recordings. The diagnostic therefore
reports live time/duration as unavailable and refuses both seek directions.
This is an explicit SDK capability refusal, not a failed attempted live seek
and not a finding about every possible Twitch hosting approach.

A hold experiment used documented Pause and Play rather than inventing a live
timestamp. The strongest sample began after startup statistics had settled:

- Before Hold 15: `PLAYING`, not paused, SDK broadcaster latency 7.155 seconds.
- During hold: `PAUSE`, paused. Reported latency remained 7.155 seconds; it was
  not interpreted as an advancing live clock while paused.
- Timer elapsed 15,003 milliseconds. Resume produced `PLAYING`, not paused,
  with reported latency 7.663 seconds; later samples were 7.790 and 7.728.
- The expected approximately 15 seconds of added latency was not retained in
  these running statistics. Buffer statistics also changed sharply and were
  not treated as a reliable independent delay measurement.

An earlier five-second hold ran across unstable startup readings (40.089
seconds before, 6.971 and 6.939 afterward); it cannot establish a controlled
delay comparison. There was no content-derived clock or acoustic comparison
in either hold. The bounded conclusion is that this tested Pause/Play sequence
does not establish useful retained live delay, not that all Twitch live delay
is impossible. Automatic resynchronization is a plausible explanation, not
independently proven. The SDK documents live resynchronization as one reason
for a `SEEK` event but does not promise pause-position retention.

## ABEMA recording

Public fixture: [Grand Sumo day-15 highlights, 394-72_s10_p8529](https://abema.tv/video/episode/394-72_s10_p8529).
The optional demographic prompt reappeared; its visible Later action was used.
No browser data was cleared, authentication performed, or terms changed.
The original element reported duration 585.094 seconds and seekable range
`[0, 585.094]`. Play first advanced the clock; Pause held it at 44.981 seconds.

| Action | Held starting clock | Requested target | Settled paused readback |
| --- | ---: | ---: | ---: |
| Backward 5 s | 44.981 | 39.981 | 39.981 |
| Forward 5 s | 39.981 | 44.981 | 44.981 |

Both observations reported `seeking: false` and `readyState: 4`. Temporary
screenshots showed different sumo frames at the two timestamps: the ring and
crowd versus a wrestler close-up. Native Play then advanced to 53.487 seconds
with pause false and ready state 4. The provider's unmute overlay remained;
this isolated experiment makes no acoustic claim.

## ABEMA live

Public fixture: [ABEMA NEWS](https://abema.tv/now-on-air/abema-news), an ordinary
live channel, not a replay or a Premium catch-up route. The diagnostic changed
only the original visible media element within its advertised seekable ranges.
It did not request older segments, invoke private APIs, or change entitlements.

The element used epoch-like timestamps with non-finite duration. Its initial
seekable interval was approximately 60 seconds. After pausing, buffered older
media remained exposed and the interval grew beyond 60 seconds; a fixed
60-second maximum must not be inferred from the initial sample.

| Action | Held starting clock | Requested target | Settled paused readback |
| --- | ---: | ---: | ---: |
| Backward 5 s | 1791125587.353 | 1791125582.353 | 1791125582.353 |
| Forward 5 s | 1791125582.353 | 1791125587.353 | 1791125587.353 |

Each target was inside the then-exposed seekable window. Both settled with
`seeking: false` and `readyState: 4`; no boundary clamping was needed. After
Play, time advanced to 1791125590.740 and then 1791125620.967. Distance from
the last seekable endpoint remained approximately 63.6–63.7 seconds rather
than immediately returning to its initial approximately 20-second distance.
This distance is **not** broadcaster latency and is not comparable directly
to Twitch's SDK latency statistic.

A further Hold 15 retained additional delay:

- Before: time 1791125629.611, not paused, endpoint distance 64.421 seconds.
- Held: the same time, paused, after 15,008 milliseconds; endpoint distance
  78.526 seconds.
- Resumed: time 1791125637.138, not paused, endpoint distance 78.769 seconds.
- A separated later sample advanced to 1791125682.302 with endpoint distance
  78.923 seconds.

Visual inspection showed the channel's static intermission and an ABEMA
information overlay, not a moving news segment. Consequently this run
establishes bounded media-clock operation and retained delay, but cannot prove
that the selected footage changed visually or that every live program supports
it. Repeat with a moving program, ads, player replacement, and window eviction.
No forward-to-future request or expired-window recovery was tested on device.

## Diagnostic scope and reproduction

The new screen is debug-only. It offers Read, Play, Pause, Back 5, Forward 5,
Hold 5, Hold 15, and Edge. Pause first for isolated seeks; the seek controls
preserve pause state. Hold restores playback only if the source was initially
playing. ABEMA player-element replacement, a Twitch SDK resource-ID change,
or page exit cancels its bounded resume request. ABEMA source changes or ads
that reuse the same element are not detected by this identity check and remain
untested.
Bounds are observed dynamically, not assumed. The Edge control is experimental
and was not exercised in this run.

Launch the installed debug APK with a validated public fixture, for example:

```sh
adb shell am force-stop net.fstab.tachiai
adb shell am start -n net.fstab.tachiai/.MainActivity \
  --ez net.fstab.tachiai.extra.TIMING_PROBE true \
  --es net.fstab.tachiai.extra.TIMING_PROVIDER abema \
  --es net.fstab.tachiai.extra.TIMING_KIND live \
  --es net.fstab.tachiai.extra.TIMING_RESOURCE https://abema.tv/now-on-air/abema-news
```

Use provider `twitch` with resource `2080217716`/kind `replay`, or
`relaxbeats`/kind `live`; ABEMA replay uses the exact episode URL above.
Only the selected playback route is eligible for diagnostic evaluation.
ABEMA route guards run before script initialization and again when selecting
the original element, including before a delayed resume. No scripts run on
login routes and no native JavaScript bridge is exposed. Native readbacks
exclude media URLs, credentials, cookies, DRM data, and provider error text;
stale callbacks after navigation or newer requests are ignored.

Generic timing-command bookkeeping is separate from the provider backends.
Twitch uses its documented SDK; ABEMA-specific route and original-element
selection remain in the diagnostic adapter. The production presentation and
provider command interfaces have not been expanded into a premature alignment
architecture.

## Verification and next experiment

The final probe APK passed 47 Android unit tests, Android lint with the same
two existing warnings, and debug assembly. Six timing JavaScript cases cover
holds, disposal, bounds, live refusal, and actual SDK-adapter wiring; the 40
existing replay/coordinator/composite cases also passed. Targeted repository
checks and the documentation build passed. `apksigner` verified the shared
debug signing certificate against the documented identity.

The final installed APK SHA-256 was
`6c3ec35cd8a50d14b4c8ab2c87dab43b2975f846cd4e16d6867811ea267699b6`.
Twitch's recording test preceded the last route/callback safety changes and
used APK `bebdb2de248b4eae43c9eca0ebf11ffb7ac9e378b399999808cfe1dbc41cc922`;
its SDK backend was unchanged. Twitch live and both ABEMA cases used the final
APK. Installation preserved app-private browser data. Temporary phone images
are not repository artifacts.

Next, investigate retained Twitch live delay/recovery within the supported
player's controls, repeat ABEMA live with moving footage, and measure source
transitions and bounds. Only then design relative actions around explicit
seek/hold/return-live capabilities, state confidence, and observed recovery.
Do not represent a requested pause duration as a measured alignment offset.

## Turbo login follow-up — prepared 2026-10-04

The user confirmed an existing Turbo subscription. This is account-entitlement
information, not confirmation that Tachiai's WebView is signed in or that the
tested embed recognizes Turbo.

[Twitch's Stream Rewind documentation](https://help.twitch.tv/s/article/stream-rewind?language=en_US)
describes retained pause and live rewind for Turbo viewers on qualifying
Affiliate/Partner channels with Rewind enabled. Channel subscribers instead
require the channel's ad-free subscriber benefit. Broadcasters must enable
past-broadcast storage, automatic VOD publication, and Rewind; DJ category and
DJ Program streams are excluded. Thus the earlier entitlement-unverified live
test does not rule out this feature. Lack of ads alone is not eligibility.
The [public SDK](https://dev.twitch.tv/docs/embed/video-and-clips/) still does
not document live seeking, and Rewind availability in Android WebView and the
official embed remains unverified.

A new debug-only session probe opens Twitch's first-party login page in the
normal app-private persistent WebView profile. It never reads, copies, logs,
or exports cookies or account data. Neither login nor original-web modes have
adapter scripts, focus mode, telemetry, or native JavaScript bridges. Android
`FLAG_SECURE` protects the entire session probe, including original-web modes
where Twitch could show a login modal without changing the channel URL.
During sign-in, agents must not take screenshots, UI/accessibility dumps,
console captures, page-source captures, or debugging traces. The user enters
credentials and 2FA directly into Twitch and explicitly taps Done afterward;
Done persists cookies without reading their values and is not a login-success
detector.

Only exact HTTPS `m.twitch.tv` and `www.twitch.tv` origins are permitted for
top-level navigation. Unknown authentication hosts and popup windows fail
closed rather than being guessed or handed to an external browser. If that
blocks a legitimate provider flow, report the failure and investigate the
specific supported origin/flow without capturing sensitive redirect URLs.
The installed Twitch app's private session and Chrome's cookie jar are not
imported. Twitch API OAuth would not by itself establish a web-player session.

After Done, the probe offers unmodified Web, experimental Desktop web, and
the existing official-Embed timing diagnostic. The desktop identity is a
separate playback-only comparison; login always uses the default identity.
Switching modes preserves normal browser storage but does not establish that
first-party authentication survives third-party embed cookie restrictions.
No popup behavior is relaxed in the normal player or embed.

Provisional fixture: [Bob Ross](https://www.twitch.tv/bobross), whose public
page was labelled live during research. Actual device playback, broadcaster
Rewind settings, and Turbo recognition still require observation. This avoids
assuming the previous music fixture is eligible under the DJ exclusion.

```sh
adb shell am force-stop net.fstab.tachiai
adb shell am start -n net.fstab.tachiai/.MainActivity \
  --ez net.fstab.tachiai.extra.TWITCH_SIGN_IN true \
  --es net.fstab.tachiai.extra.TWITCH_CHANNEL bobross
```

The next device comparison is user-confirmed login and original seek-bar
availability, backward/forward and pause retention through Twitch's own
controls, then the same checks in the embed. Compare Low Latency on and off
separately where the original player offers Settings / Advanced. Twitch
[documents that viewer setting](https://help.twitch.tv/s/article/low-latency-video?language=en_US)
as a latency/quality option, not arbitrary alignment control. None of these
entitled playback results has been observed yet.

The session-probe APK passed 50 Android unit tests, lint with the same two
existing warnings, debug assembly, the 46 JavaScript regression cases, targeted
secret/format checks, and the documentation build. The final certificate
matched the documented shared debug identity. APK SHA-256:
`36084b0ecf576c58261b0f8db971a6b5fec8360ca26156b0394a5b241779f257`.
It installed without clearing data on the same Pixel 6 and was launched for
manual sign-in. Login-page rendering and authentication success have not yet
been confirmed by the user; no login UI capture or inspection was performed.

The first login attempt subsequently failed visibly: the user reported a
white page with only Tachiai's Done button and the host's Ready label. At the
user's explicit permission, a bounded accessibility check inspected native
status and WebView bounds only: the WebView occupied `[0,380][1080,2211]`,
reported no nested accessible content, and the native host reported page-load
completion. This does not identify the cause or prove that Twitch's scripts
initialized. No credentials, page source, cookies, console, or network trace
were inspected. One unchanged reload was attempted. Twitch's public mobile
login URL redirected to `https://www.twitch.tv/login` in the research browser,
so the next probe starts at that canonical URL directly, retains default
identity, adds a native Retry button, and labels page-load completion as
form/login unverified. This is a retry hypothesis, not a verified fix or a
reason to clear browser data.

The canonical-URL retry passed the same 50 Android tests, lint with two existing
warnings, debug assembly, signing-identity verification, and targeted repository
checks. APK SHA-256:
`fcd8f9c104da8fff420c3711c61742b7846ebd8e5be481340d5fe4ce8392f5df`.
It installed without clearing data and was launched for user confirmation.
This does not yet establish a rendered form or successful login. If it fails,
Twitch's [Everything embed](https://dev.twitch.tv/docs/embed/everything/)
documents a provider-owned Sign In UI and popup flow worth a separate
experiment; it does not guarantee Android WebView authentication. Twitch's
[supported desktop-browser list](https://help.twitch.tv/s/article/supported-browsers?language=en_US)
does not list WebView, and neither user-agent spoofing nor API OAuth should be
presented as a supported website-login solution.

### Full-embed authentication experiment — 2026-10-04

The user confirmed that the canonical direct-login retry remained a blank
light-gray page. Neither direct-login attempt produced a usable form. No
authentication success or Turbo recognition has been established.

With user approval, the next session probe starts on an app-owned wrapper of
Twitch's [Everything embed](https://dev.twitch.tv/docs/embed/everything/), using
its documented `Twitch.Embed` SDK and video-only layout, with the intent of
using its original Sign In controls. Their presence in this configuration was
not verified before installation. It does not inspect provider DOM, read account/session state, or
automatically start playback. The SDK's published deployment on this date
constructed a `www.twitch.tv/login?popup=true` window and accepted its login
success message only from `https://www.twitch.tv`; see the
[public SDK entry](https://embed.twitch.tv/embed/v1.js) and
[deployment-specific auth chunk](https://assets.twitch.tv/assets/42053-3b6a97993aa5683186dc.js).
That published code is origin evidence, not a stable WebView-authentication
contract. Its storage-access behavior and embed session recognition remain
experimental.

Only this wrapper opts into a user-gesture-triggered popup, limited to one
window and exact HTTPS `www.twitch.tv` navigation. Generic browser hosting
contains no Twitch selectors or host constants; the adapter supplies the
allowlist. The popup shares Tachiai's normal app-private WebView profile, has
no asset loader, adapter scripts, telemetry, native JavaScript bridge, cookie
readback, or external navigation handoff, and denies media/device permissions
and nested popup creation. No installed-app or Chrome credentials are imported.
Social-login redirects are intentionally out of scope and will fail closed.

A separate native header identifies the committed HTTPS host without exposing
paths, queries, or tokens. The WebView remains hidden for its initial blank
document and every navigation until an allowlisted HTTPS document commits;
unknown destinations and errors hide it. Android
[warns that popup initiators cannot be reliably identified](https://developer.android.com/reference/android/webkit/WebChromeClient#onCreateWindow(android.webkit.WebView,%20boolean,%20boolean,%20android.os.Message)):
an iframe may request the window, so the parent page is not evidence of the
requester's origin. Navigation checks are defense in depth, not a guarantee
that no server redirect can initiate a network request before being stopped.
The separate popup window also receives `FLAG_SECURE` and `FLAG_KEEP_SCREEN_ON`.
Authentication-session pages suppress default JavaScript dialogs and console
forwarding; default WebView script dialogs do not inherit the secure flag.
Ordinary playback panes retain popup denial and their previous defaults.

The earlier no-popup rule describes the failed direct-login probe; this is a
narrow separate opt-in, not a general relaxation. The same Pixel 6 / Android 17 /
WebView 153.0.8010.36 is the intended test device. Network region and actual
provider acceptance are not reverified for this login experiment. Account
entitlement remains user-reported Turbo, with WebView authentication unknown.
The user must operate Twitch's Sign In and enter credentials/2FA privately;
agents must not capture or inspect the authentication UI. Native Done still
does not verify login. A visible form, completed popup, recognized Turbo,
live Rewind, and persistence must each be confirmed separately before testing
entitled timing behavior.

The full-embed experiment passed 56 Android unit tests, 49 JavaScript checks,
lint with the two pre-existing warnings only, targeted formatting/secrets checks,
and the documentation build. The Android-build skill kept assembly in the
pinned disposable SDK container and reused the existing debug keystore;
post-build `apksigner` verification matched the documented certificate.
The final APK is 12,430,003 bytes, owned by the normal host user, with SHA-256
`54db2e0a290f21b53c68159c9ea49630ca8a1eda1bc1053520d1fc69514dbe05`.
It installed on the paired Pixel 6 without clearing data. No authentication UI
was captured or inspected. Form rendering and authentication remain pending
user confirmation; build/install success is not playback or login evidence.
Final diff inspection found no newly included generated build artifacts and
preserved the pre-existing composite/timing worktree changes.

The user subsequently reported seeing Bob Ross and starting video playback by
tapping the player, without a login popup. Clicking the Twitch logo did create
a native popup, but it displayed Tachiai's fixed “Sign-in window blocked or
unavailable” label. This confirms that user-triggered popup creation is reached,
not that the clicked control invoked authentication or that the login route
failed. That label covers navigation rejection and network/HTTP/TLS errors;
the specific native failure status has been requested without inspecting the
popup or provider content. Playback is user-confirmed for this bounded test;
Sign In availability, authentication, and Turbo recognition remain unverified.

### Privacy-safe native popup diagnostics — 2026-10-04

The user requested a screenshot of the current non-sensitive failure screen.
With screenshot protection intact, ADB returned a black app surface, which
cannot diagnose the popup. The temporary image was removed. A proposed native
status accessibility check was blocked before execution because Android's dump
would capture the entire tree before filtering, potentially including provider
authentication content. It was not retried or bypassed. The user then manually
reported the parent label “Page load finished; Twitch form and login are
unverified.” Code inspection established that popup failures shared the page's
status slot, which a later parent-page completion could overwrite; that label
does not identify the popup failure.

With explicit user approval, the debug build now records native popup events
under `TachiaiPopup`. The logging interface accepts only a closed event enum,
an optional closed destination-category enum, a locally incremented popup
attempt number, and optional numeric WebView/HTTP error codes. It accepts no
URL, hostname string, path, query, provider error description, exception,
console message, account state, cookie, token, or page content. Destination
classification is performed locally; `m.twitch.tv` is a vetted alternate
provider-origin category for this adapter but **remains blocked**, and its
actual address is not logged. Unknown destinations become `OTHER`, without
inspection or guessing. Release logging is disabled by `BuildConfig.DEBUG`.

Events distinguish popup creation/rejection, allowed/blocked/blank navigation,
visible/ignored commit, document completion, network/HTTP/TLS failure, renderer
termination, denied nested windows/permissions, and close. Page completion is
not a form-rendering or authentication-success signal. Popup failure state is
now separate from page state and survives page loading, page completion, and
popup close. Only a new successfully opened popup attempt resets that failure;
older attempt callbacks cannot overwrite its successor. A fixed diagnostic
code is also shown in the popup failure label.

Read diagnostics from the current Tachiai PID and this tag only, using
[Android's allowlist-style log filtering](https://developer.android.com/tools/logcat#filter-log-output):

```sh
adb shell pidof net.fstab.tachiai
adb logcat -b main --pid=REPORTED_PID -d -v brief 'TachiaiPopup:D' '*:S'
```

Do not clear system logs, enable WebView remote debugging, collect provider
console output, inspect auth pages, or collect general application/system logs
to diagnose this probe. Its private browser profile, popup allowlist, media
permissions, TLS policy, and screenshot protection are unchanged. The next
bounded experiment is to reproduce the logo popup, inspect these fixed native
events, then separately exercise Twitch's actual authentication control.

The logging/status build passed 61 Android unit tests, the 49 JavaScript
regression checks, lint with the same two existing warnings, debug assembly,
targeted secrets/format checks, and documentation rendering. Tests cover fixed
serialization, disabled release logging, classification without navigation
widening, failure retention through page completion/close, and older callbacks
after both opened and rejected newer attempts. Its certificate matched the
shared debug identity after each build. Final APK SHA-256:
`d4e056debf513b41749ab8cba0b3f032e531a866660b5aa5218b32485ab22dca`,
12,347,339 bytes, normal host-user ownership. Diff review found no new generated
files or changes to unrelated pre-existing work. It installed without clearing
browser storage and was launched with a fresh diagnostic Activity. Native logging delivery and
the reproduced popup's reason still require a fresh device interaction.

On the same installed logging APK and Pixel 6, the user clicked the Twitch logo
again. Current-process, `TachiaiPopup`-only collection returned `OPENED`, then
`NAVIGATION_BLOCKED destination=ALTERNATE_PROVIDER_HTTPS` for attempt 1. This
verifies native log delivery and identifies policy rejection as the immediate
popup failure. The adapter's only alternate diagnostic HTTPS host is
`m.twitch.tv`; classification requires that exact host, HTTPS, no user info,
and the default/443 port. No actual URI or page content was collected or logged.
This logo-navigation result is not an authentication failure. The allowlist
remains unchanged, and actual Sign In/Follow-triggered login is still untested.

### Video-with-chat login comparison — prepared 2026-10-04

The user approved a narrow configuration comparison: only the debug session
wrapper now requests `layout: 'video-with-chat'` through the same official
Everything SDK. Twitch
[documents this layout](https://dev.twitch.tv/docs/embed/everything/#embed-parameters)
for live channels, with chat below video at narrow widths. Exposing an actual
Log In/Sign In control is the hypothesis, not verified device behavior. The
native instruction now directs the user to Twitch's chat-area login control
instead of its logo. No chat message should be sent, subscription made, or
channel followed merely to trigger authentication.

The Bob Ross fixture, manual playback, app-private browser profile, default
identity, exact `www.twitch.tv` popup allowlist, fixed native logging, and
screenshot protection are unchanged. The separate timing/video-only probes
and product layout are unchanged. Same intended device: Pixel 6 / Android 17 /
WebView 153.0.8010.36; network region/provider acceptance are not reverified,
Turbo remains user-reported, and the WebView's authentication state remains
unknown. The user will report whether chat and a login control render and will
enter any credentials privately. Popup document completion alone will not be
treated as a usable form or successful login.

The chat-layout APK passed 61 Android unit tests, 49 JavaScript checks, lint
with the same two existing warnings, debug assembly, targeted repository
checks, and documentation rendering. The final certificate matched the shared
debug identity. APK SHA-256:
`9e9a0d78408d95ed2432cff310a5017fa00e89a50cebb59b63f1240d31f96a79`,
12,347,339 bytes with normal host-user ownership. It installed without clearing
browser data and was launched as a fresh diagnostic Activity. Diff review
confirmed the implementation change is limited to the session layout,
corresponding test expectation, and native instruction; prior experiments and
generated build artifacts were not added or altered in Git. Chat/control
rendering and authentication still await user confirmation; no authentication
UI capture or inspection was performed.

### Launch-routing correction — prepared 2026-10-04

The user reported that the installed chat comparison still showed the old
ABEMA/Twitch presentation and Twitch's app/web prompt. Thus the preceding
successful install/start commands did **not** verify that the intended session
screen was visible. Package metadata confirmed a debuggable Tachiai
installation. A normal targeted probe launch returned Android's warning that
the intent was delivered to the existing topmost Activity, with no new Activity
created. This establishes the existing-instance path, not the reason every
earlier apparently fresh launch showed the wrong screen.

Code inspection found no `onNewIntent` handler and non-observable reads of the
Activity's initial intent. Android
[requires explicit intent replacement](https://developer.android.com/reference/android/app/Activity#onNewIntent(android.content.Intent))
for later delivery. The Activity now calls the superclass, replaces its intent,
and advances a debug-only observable revision. The existing routed content is
keyed on that revision so both changed and identical debug probe requests reset
their remembered screens and release old WebViews. No manifest launch-mode or
provider-policy change was made. Release routing/revision behavior is unchanged.
Debug relaunches intentionally reset transient player/probe state; the persistent
app-private browser profile is not cleared.

A debug-only `TachiaiLaunch` marker records closed source/route enums (`CREATE`
or `NEW_INTENT`, `TWITCH_SESSION` or `OTHER`) and a local revision only. It does
not log the intent, extras, URLs, channels, page contents, or account data. Read
that fixed tag only from Tachiai's current process, just like `TachiaiPopup`.
Unit tests verify repeated revision updates, unchanged release behavior, and
fixed logging with release output disabled. Actual Activity/Compose delivery
must additionally be checked on-device via the native marker and the user's
visible-screen report; these unit tests do not simulate Android lifecycle
delivery or prove that chat/login renders.

The routing build passed 64 Android unit tests, 49 JavaScript checks, lint with
the same two pre-existing warnings, debug assembly, targeted repository checks,
and documentation rendering. Its certificate matched the documented shared
debug identity. APK SHA-256:
`135a025b72e28c1847b95ac8146b536b0e99f88b23c692bd15728969a588285b`,
12,347,339 bytes with normal host-user ownership. Diff review found no new
generated-file noise and preserved the pre-existing playback experiments.

Installation succeeded without clearing app/browser storage, but the subsequent
launch sequence reported Android System UI's `PackageUpdateActivity` rather
than Tachiai, and the final Tachiai PID check returned no process. Consequently
that sequence does **not** verify `onNewIntent`, screen replacement, or native
launch-log delivery. The user reclaimed the phone before another attempt;
device testing is paused, with no further phone commands. On resumption, first
launch the session probe and verify its fixed native route marker, then exercise
existing-instance and repeated probe delivery. The user's visible-screen report
is still required before attempting Twitch's actual chat login control.

On resumption, the Pixel 6 was connected through the existing wireless ADB
pairing. A bare session launch brought the restored task forward; Tachiai's
current-process marker reported `CREATE route=OTHER revision=0`, not the desired
probe. A subsequent bare launch also had no new marker in the collected native
log. Explicit `FLAG_ACTIVITY_SINGLE_TOP` (`am start -f 0x20000000`) then produced
`NEW_INTENT route=TWITCH_SESSION revision=1`. Repeating that same explicit launch
produced `NEW_INTENT route=TWITCH_SESSION revision=2` in the same process. This
verifies receipt/replacement of repeated session intents and observable revision
updates on the existing Activity, not chat rendering or authentication. Use the
explicit single-top flag for this bounded debug workflow; do not infer success
from `am start`'s “delivered” warning alone. No manifest flags were changed.

Only fixed `TachiaiLaunch`/`TachiaiPopup` events from Tachiai's current PID and
the main log buffer were collected. No popup event occurred during these
launches. The phone was left on the requested session route, and visible chat
and login-control confirmation was requested from the user without capturing
or inspecting the protected page. OS/engine, region, account, and entitlement
conditions remain as recorded for the prepared chat comparison; no new network
acceptance or authentication evidence was collected.

The user confirmed that the chat area rendered but reported no visible login
control. Tapping its entry area displayed the channel's regular rules popup,
according to the user. This verifies the visible chat comparison and an
interactive provider overlay, not an authenticated session, Turbo recognition,
or a usable login form. Current-process `TachiaiPopup`-only collection returned
no events; this interaction therefore provided no evidence of a native child
window. The test instructions explicitly excluded sending a chat message.

After the channel rules, the user reported that tapping the entry area only
opened the keyboard. They were instructed not to type or send anything and to
use Tachiai's “Done — open Twitch” control to try the original manual web mode.
They then reported a brief visible item followed by a gray background. Neither
keyboard availability nor that page appearance establishes login or failure of
authentication. Fixed launch/popup logs did not identify this top-level page's
load outcome, and no protected page was captured or inspected.

### Native page lifecycle diagnostics — prepared 2026-10-04

The protected session adapters now explicitly opt into `TachiaiPage` logging
through `BrowserRequest.logNativePageEvents`; normal requests opt out. Release
builds emit nothing even for opted-in requests. Each opted-in debug WebView
gets a local numeric browser ID at creation, independent of provider data.
Only closed event kinds and optional native numeric network/HTTP error codes
cross the logging boundary: `STARTED`, `COMMIT_VISIBLE`, `FINISHED`,
`NAVIGATION_BLOCKED`, `NETWORK_ERROR`, `HTTP_ERROR`, `TLS_RESOURCE_ERROR`, and
`RENDERER_GONE`. Network/HTTP errors are main-frame-only. Android's TLS callback
does not identify the main frame, so its marker explicitly includes resource
failures. No URI, hostname, path, query, description, exception, headers, console
message, page text, cookies, or account state is logged.

These events come from native browser callbacks, not injected scripts or page
inspection. Visible commit and document completion are milestones, not proof
of a rendered form, successful login, video playback, or Turbo recognition.
Navigation policy, identities, media permissions, screenshot protection, and
the persistent profile are unchanged. Read only the current Tachiai process
and these fixed tags; do not broaden collection to provider/system output:

```sh
adb logcat -b main --pid=REPORTED_PID -d -v brief 'TachiaiPage:D' 'TachiaiPopup:D' 'TachiaiLaunch:D' '*:S'
```

Helper unit tests cover the explicit opt-in/default opt-out, release gate,
fixed serialization, restricted code fields, and distinct stable browser IDs.
They do not simulate actual Android WebView callbacks. The next device test
will correlate the requested session launch, manual web transition, and user
screen report with these fixed native milestones, without inspecting any
authentication content. Gray-page cause and login remain unresolved.

This build passed 67 Android unit tests, 49 JavaScript tests (run with
`--test-isolation=none` to expose individual test results), lint with the same
two pre-existing warnings, targeted formatting/secrets checks, and documentation
rendering. The Android-build skill kept the build in the pinned disposable SDK
container and reused the stable debug keystore read-only. `apksigner` matched
the documented shared SHA-256 certificate fingerprint. The final debug APK is
12,347,339 bytes, normal host-user-owned, with SHA-256
`a9815d7afbf26dc859578f6da6e413e32101afce3f4a069dbc8571ff9012565d`.
Diff inspection found no generated-file additions or unrelated cleanup.

It installed without clearing browser storage. The same Pixel 6 reached
`NEW_INTENT route=TWITCH_SESSION revision=1`; the new wrapper WebView reported
`STARTED`, `COMMIT_VISIBLE`, and `FINISHED` for browser 1, with no logged native
error or popup event. This verifies delivery of opted-in native page markers,
not a successfully rendered embedded child or login. The user was asked to
repeat the manual-web transition; reproduction and its outcome remain pending.

The user reproduced the gray background after “Done — open Twitch” and
Twitch's “Keep using web” action. Browser 2's native sequence was `STARTED`,
`FINISHED`, `COMMIT_VISIBLE`, `FINISHED`; no `NAVIGATION_BLOCKED`, main-frame
network/HTTP failure, TLS resource failure, renderer termination, or child
popup event appeared in the collected trace. This is a completed native
document-load milestone with user-reported unusable rendering, not proof that
all subresources loaded or that provider application/authentication succeeded.
The next suggested comparison is the existing unscripted Desktop web mode;
its identity change remains experimental rather than a supported login fix.

The final documentation-only recheck was blocked before execution by the host
mise configuration: its global lockfile could not be read under the active
version, then a global `tools.opencode.path` template failed resolution. Clean
shell and outside-sandbox retries did not resolve the repository checks. No
global configuration or lockfile was changed. APK build, certificate
verification, tests, and the earlier source/docs checks passed before this
tooling failure; the last experiment-note additions still need a successful
documentation check. Phone commands continued through a clean shell using ADB,
without broadening diagnostic collection.

The user also reported a blank background in the unscripted Desktop web
comparison. A later current-process, fixed-tag collection returned no retained
events despite the same Tachiai PID; it does not establish absence of errors
during that transition. The earlier retained manual-web trace remains the
only native load outcome established for the gray-page reproduction. A Chrome
control was then requested for the same public Bob Ross channel, with the user
reporting rendering/login-control availability and no browser session/page
inspection. Chrome's separate profile is not a source of cookies for Tachiai.

The user confirmed the same public channel rendered in Chrome, with no login
request. That settles rendering for this bounded Chrome control, not logged-in
state or Turbo recognition. At the user's request for a direct login page,
Chrome was directed to Twitch's canonical `https://www.twitch.tv/login` route
for a private user-reported form-rendering control. No page capture, inspection,
credential entry by the agent, or session transfer was performed. A successful
Chrome login would not establish authentication in Tachiai's separate profile;
the previous blank direct-login WebView experiment remains unresolved.

The user confirmed that the direct login form rendered in Chrome. To repeat
the earlier embedded direct-login test with current native diagnostics, the
session probe now exposes “Direct Twitch login” in its native controls. This
selects the already-existing unscripted `SIGN_IN` stage and canonical route;
it adds no new provider origin, identity override, session handling, or page
inspection. The native instruction asks the user not to enter credentials yet.
The separate full-embed comparison remains available and unchanged. Chrome
form rendering is verified by user report; direct-login form rendering in
Tachiai remains pending. Global-tooling check limitations recorded above still
apply until a successful final repository check can run.

The native-control rebuild passed all 67 Android unit tests and lint with the
same two existing warnings in the pinned disposable build environment. The
existing debug key was mounted read-only, and `apksigner` matched the stable
documented certificate. APK SHA-256:
`8f200c234d7d829b3b4fa34bb075d733d5ce8539183217d68534f69ea64d05be`,
12,347,339 bytes with normal host-user ownership. This change is limited to the
native stage-selection buttons and the rendering-test instruction; no provider
scripts or policies changed. The regular final repository/docs checks remain
blocked by the recorded host mise problem; no alternate toolchain or global
configuration repair was performed.

Installation succeeded without clearing app/browser data, and fixed native
logging confirmed `NEW_INTENT route=TWITCH_SESSION revision=1` in the new
Tachiai process. The user was asked to select “Direct Twitch login” and report
only whether the form appears. Embedded direct-form rendering and successful
authentication are still pending; the native route marker proves neither.

After the user reported tapping the direct-login control and requested a log
check, current-process fixed-tag collection showed two additional
`CREATE route=TWITCH_SESSION revision=0` markers and new browser instances.
The latest browser (4) reported `STARTED`, `FINISHED`, `COMMIT_VISIBLE`,
`FINISHED`, then another `STARTED`, `FINISHED`, `COMMIT_VISIBLE`. No recorded
navigation block, native network/HTTP/TLS error, renderer termination, or popup
event appeared in this retained trace. That does not establish form rendering
or absence of subresource/frontend failures. The cause of the Activity
recreations is unknown. Probe stage uses transient remembered state, so an
Activity recreation can return it to the initial full-embed stage; launch
markers alone do not identify the selected stage. User confirmation of the
direct-login native instruction and visible form remains necessary.

The user subsequently confirmed that the native header still said “Direct
Twitch login rendering test” and the content below was blank. This verifies
selection of the direct-login stage and a failed usable-form rendering test
under the current app/profile/network conditions. Chrome rendered that same
canonical route's form by user report. This is a WebView-versus-Chrome
comparison, not proof of its cause: the normal Chrome and app-private profiles
differ, as can resource caching and provider/browser behavior. Authentication
was not attempted or established. Do not equate native document completion
with a usable form or mark login successful based on the chat keyboard.

Read-only hosting review found no obvious mismatch in JavaScript, DOM storage,
cookies, or the exact allowed Twitch routes. The full embed renders through the
same secure browser container, reducing but not eliminating a layout/lifecycle
explanation. Native cancellation of script dialogs is a concrete Chrome/WebView
difference, but no evidence identifies it as the blank-page cause; protections
must not be disabled to test it. Native-only bounded viewport/lifecycle markers
and fixed dialog-cancellation occurrences are possible next diagnostics, not
implemented or verified behavior.

Twitch's [Everything embed guide](https://dev.twitch.tv/docs/embed/everything/)
documents authentication prompts/popups for login/follow/subscribe. Its
[supported browsers article](https://help.twitch.tv/s/article/supported-browsers?language=en_US)
does not establish Android WebView support. The observed difference is
consistent with provider initialization or WebView compatibility problems but
does not prove either. Chrome login or OAuth access tokens are not a supported
website/embed session transfer. Before another implementation change, return
to the official full embed and ask the user privately whether its own profile
menu offers Log Out. The prior keyboard/rules behavior could reflect an
already-authenticated chat session; it does not establish one. Do not send a
message, follow/subscribe, or log out merely to provoke a login prompt.

### Actual embed authentication popup and outside help — 2026-10-04

The user independently clicked “Gift a sub” and reported that it opened the
login popup. They were instructed to stop before gifting/payment and report
only whether the actual login form is visible. Current-process fixed native
events showed popup 1 `OPENED`, `NAVIGATION_ALLOWED destination=ALLOWED_HTTPS`,
`PAGE_FINISHED`, `COMMIT_VISIBLE`, and `PAGE_FINISHED`, all destination-qualified
events classified as allowed HTTPS. The configured popup allowlist is still
exactly `www.twitch.tv`. No native popup failure was recorded; no popup page,
URL, account data, or credentials were inspected. Usable-form rendering and
authentication remain pending user confirmation. This actual SDK popup is a
different path from the blank standalone login page and prevents a conclusion
that WebView authentication is generically unavailable. No gift or payment was
requested as a test action.

At the user's request, read-only outside research found these useful leads:

- A [2020 Twitch forum report](https://discuss.dev.twitch.com/t/issues-with-webview-and-oauth/24960?page=2)
  describes browser embed login working while Android/iOS WebView embed login
  fails. It concerns post-login redirect/session behavior on an old deployment,
  not our current pre-form blank page; its app-OAuth discussion is not a fix for
  website/embed authentication.
- A [February 2026 Grayjay report](https://github.com/futo-org/grayjay-android/issues/3073)
  reports Twitch rejecting an older configured Chrome identity and accepting a
  newer one. Its explicit unsupported-browser error differs from our blank
  page, and Tachiai retains the installed engine version. It is not evidence
  that identity spoofing is necessary or supported here.
- The [official WebView embed issue](https://github.com/twitchdev/issues/issues/149)
  is an older Android 10/player-rendering report, not a matching login bug, but
  establishes a developer-product reporting venue.
- [Twitch's developer support page](https://dev.twitch.tv/support/)
  directs developers to its forum, TwitchDev Discord, and developer-product bug
  tracker. If escalation is needed, include the device/engine, public wrapper
  configuration, Chrome-versus-WebView rendering comparison, and fixed native
  callback sequence only. Exclude protected screenshots, credentials, cookies,
  full provider URLs, console output, and account-identifying details.

No community post or upstream issue was created. Actual SDK-popup form/login
results should be established before escalating the standalone-page failure.

The user then confirmed that the real embed popup displayed username and
password boxes. Usable login-form rendering is therefore user-verified in this
bounded Pixel 6 experiment, unlike the standalone canonical route. No form
capture or inspection was performed. The user was invited to sign in privately
inside Twitch's popup and stop before any gift/purchase; credentials and any
challenge are entirely user/provider-controlled. Successful authentication,
recognition by the original embed, persistence across a restart, Turbo
recognition, and entitled live timing controls remain unverified.

The user then reported that submitting login in the real SDK popup produced
Twitch's “Your browser is not currently supported” rejection, with recommended
browser/help links. This establishes a user-observed authentication rejection,
not successful login or proof of its cause. Fixed native events for the retained
popup attempt showed `OPENED`, allowed HTTPS navigation, document completion,
and visible commit without a native host failure. Provider authentication
responses, entered data, page contents, and support-link destinations were not
inspected or logged. No repeated automated attempt, identity spoofing, cookie
clearing, challenge bypass, or support-origin allowlist broadening was done.

A [first-hand Chrome Community report](https://support.google.com/chrome/thread/324296312/twitch-tv-your-browser-is-not-currently-supported?hl=en)
also describes this rejection in Chrome and Edge. It demonstrates that the
wording alone is not a definitive WebView-only diagnosis, not that our device
has the same cause. The next control is one private user-controlled login
attempt in Chrome on the unchanged connection. Previous Chrome evidence
verified form rendering only. Chrome was directed to the canonical login page;
its result is pending and cannot establish or transfer Tachiai authentication.

The user confirmed that login in the explicitly opened Chrome app succeeded
under the unchanged connection, and noted Brave as their usual browser.
Android's native WebView service metadata identifies the active provider as
`com.google.android.webview` version `153.0.8010.36`, installed/enabled and
preferred. Tachiai's WebViews and authentication popup use that Android System
WebView provider, not Brave or the separately launched Chrome app. The default
browser selection does not switch Tachiai's engine/profile. Chrome success
narrows the observed failure to the WebView/app-private authentication context
under these conditions; it does not identify which engine, identity, storage,
profile, or provider-integrity difference caused rejection. A generic account
or connection inability to log in is not supported by this Chrome control.

Chrome's [Custom Tabs overview](https://developer.chrome.com/docs/android/custom-tabs)
distinguishes WebView's separate state from browser-powered Custom Tabs, which
use the preferred browser's state. A Custom Tab could support ordinary external
browser authentication, but its successful login would not authenticate this
existing WebView embed. Do not treat it as a cookie-transfer mechanism or a
completed solution for mixed-audio two-feed playback. No Brave/Chrome session
data was read or copied, and the Chrome result is user-reported only.

### Default-identity and viewport comparison — prepared 2026-10-04

A deeper read-only comparison found a confirmed July 2026 Android login fix in
Grayjay: its Twitch plugin removed a forced Chrome 125 identity and retained
the WebView default. The [maintainer's explanation](https://github.com/futo-org/grayjay-android/issues/3073#issuecomment-4933738250)
and [reporter's confirmation](https://github.com/futo-org/grayjay-android/issues/3073#issuecomment-4940601280)
prevent treating our rejection as proof of a universal WebView login ban.
Grayjay detects authorization headers for its own plugin; this is not evidence
that Twitch's Everything embed inherits an authenticated website session, and
that extraction is not part of Tachiai's experiment.

Tachiai already assigns the unchanged default identity in the full-embed
parent and copies it to the actual SDK popup. JavaScript, DOM storage, global
cookie acceptance, and third-party cookies are enabled in both. No separate
profile or data-directory override is present. Grayjay's native login enables
wide viewport and overview mode; Tachiai previously left both at their defaults.
Those are rendering differences, not verified explanations for rejection
after credential submission. The installed Chromium version's
[settings implementation](https://chromium.googlesource.com/chromium/src/+/153.0.8010.36/android_webview/java/src/org/chromium/android_webview/AwSettings.java)
updates the native UA only when its value changes, so assigning an unchanged
default is not equivalent to Grayjay's former forced identity.

The new debug-only `TachiaiSettings` marker records closed MAIN/POPUP roles and
native booleans: identity equals the local engine default, popup identity
matches parent, JavaScript, DOM storage, cookie acceptance settings, viewport,
overview, multiple-window support, automatic-window permission, and blocking
mixed content. It never records the identity string, cookies, URLs, page
content, account state, or provider responses. It is gated by debug mode and
the existing request opt-in; ordinary requests and release builds emit nothing.
These settings establish configuration, not successful runtime storage or login.
The popup snapshot is taken before transport installs its pending contents;
it does not verify post-transport or provider-runtime behavior.
The popup keeps genuine SDK transport and its existing security policies.

The direct-login probe now offers a native default/wide-viewport comparison.
Only `useWideViewPort` changes and the browser is recreated; overview stays
false, identity and profile stay unchanged, and no provider script is injected.
The full embed, actual popup, and ordinary channel modes remain unchanged.
Both cases are rendering-only: do not enter credentials or clear site data.
The assumption that wide viewport might explain the blank standalone page is
explicitly unverified; it is not proposed as a fix for the popup's later
unsupported-browser response. An Activity recreation still resets the transient
probe selection, so correlate the visible native header with fixed launch logs.

Native package metadata currently identifies the same Pixel 6 WebView as
`com.google.android.webview` `153.0.8010.36`, and Chrome's installed update as
`154.0.8037.94` (factory package `149.0.7827.5`). These are current metadata,
not a reconstruction of Chrome's version during the earlier successful login.
Android 17 and device identity are unchanged from the preceding experiment.
Current region/exit acceptance and browser account state have not been inspected
or re-established. Content is the public Bob Ross live-channel embed and the
canonical login rendering probe. No new authentication outcome is established.

The normal mise-based targeted checks run again; the earlier tooling blocker
did not recur. This does not imply a global configuration repair by this agent.

The final comparison build passed 70 Android unit tests and lint with the two
existing ModifierParameter/IconMissingDensityFolder warnings. Targeted secrets,
formatting, session JavaScript tests, and documentation rendering also passed.
The Android-build skill kept both builds in the pinned disposable environment
and reused the stable key read-only; each `apksigner` check matched its documented
SHA-256 fingerprint. Final APK SHA-256:
`07d70c0d4ed907ace53f5a3daa1d466fd27092f1ed9054be9dd01536d278e59b`,
12,347,339 bytes, host-user-owned. No generated-file additions or unrelated
cleanup were introduced; pre-existing composite/replay work was preserved.
Independent review found no blockers and explicitly retained the native/runtime
and unit-test/device-verification distinctions above.

Installation succeeded without clearing site data. The initially restored
Activity reported `OTHER`; a subsequent explicit single-top launch established
`NEW_INTENT route=TWITCH_SESSION revision=1`. Current-process fixed-tag logs
then confirmed MAIN default identity, JavaScript, DOM storage and both cookie
acceptance settings true, wide viewport/overview/automatic windows false,
multiple-window support and mixed-content blocking true. Native document-load
markers followed without a retained native failure. This validates marker
delivery and the main configuration, not rendered content or authentication.
The user was asked to compare direct-login default/wide viewport and report
form/no form without entering credentials. That comparison and an actual popup
settings snapshot are still pending; do not mark either as tested.

The user reported that direct login remained blank with both default and wide
viewport, first in portrait and then in landscape. Current-process native logs
confirmed direct-login instances with `wideViewport=false` and then `true`;
overview remained false and the other recorded direct-login settings matched.
Both variants reached native completion/visible-commit milestones without a
retained native error. A recorded Activity recreation returned to the initial
embed, followed by another default/wide direct pair; do not infer additional
provider outcomes from the recreation. Neither orientation nor wide viewport
alone fixed usable-form rendering in this bounded test. This says nothing new
about the actual popup's post-submit rejection or authenticated embed state.

The remaining viewport comparison adds a native “Wide + overview” option to
the direct-login probe. Against “Wide viewport,” only `loadWithOverviewMode`
changes. The overview option requires wide viewport and cannot affect the full
embed, popup, or manual channel mode. Ordinary requests retain false defaults.
No profile, identity, URL, cookie, navigation or script policy changes. This
is the final viewport lead from the Grayjay settings comparison, not a supported
authentication fix; rendering results are pending. No credentials should be
entered for this test.

The overview-comparison build passed 71 Android unit tests, lint with the same
two existing warnings, and targeted formatting/secrets/session/documentation
checks. The Android-build skill used the same pinned container and read-only
shared debug key; `apksigner` matched the documented certificate. APK SHA-256:
`f2549ada3e604709ab344654c1f74de55b81c7bc6aa53303da4243f5734f87ac`,
12,347,339 bytes, host-user-owned. Installation retained app/browser data.
An explicit single-top launch and current-process markers confirmed the full
embed probe with unchanged default settings and native load milestones.
No overview-mode device rendering result or popup authentication result has yet
been established. Scope review preserved existing work and found no introduced
generated files or unrelated cleanup.

The user subsequently reported all three direct-login viewport choices blank.
Current-process fixed markers confirmed DEFAULT (`false/false`), WIDE
(`true/false`), and WIDE_OVERVIEW (`true/true`) for wide/overview settings,
followed by further repetitions. Identity, JavaScript, storage, cookie acceptance
and the other recorded security/window settings matched between the direct
variants. Each reached native completion and visible-commit milestones without
a retained native failure marker. The latest combined-mode report did not
separately specify orientation; only default/wide already have explicit
portrait-and-landscape user confirmation. These viewport comparisons did not
produce usable standalone form rendering under the recorded conditions.
They do not identify a frontend/subresource cause or explain the actual SDK
popup's earlier post-submit unsupported-browser rejection. No credentials were
requested or inspected. Further viewport tuning is deferred.

The full-embed route was relaunched on the unchanged installation/profile so
the user can open Twitch's own login popup and leave it open without entering
credentials. The existing native settings logger can then compare popup
configuration with its parent; no new APK or auth-page instrumentation is needed.
The user was instructed not to gift/purchase anything to provoke authentication.
Popup configuration and this new form-rendering observation remain pending.

The user independently selected Gift, saw Twitch's separate-window warning,
selected its Log In button, and reported a white overlay containing username
and password fields. No gift/purchase or credential entry was requested. Fixed
native markers confirmed popup 1 opened, navigated to allowed HTTPS, completed
and committed visibly with no retained popup failure. Its pre-transport native
snapshot showed default identity and parent identity equality, JavaScript,
DOM storage and both cookie acceptance settings true; wide viewport, overview,
multiple-window support and automatic windows false; mixed-content blocking
true. This finds no configured identity/storage mismatch, not successful runtime
session propagation or authentication. The form is user-verified again; the
blank standalone-page result remains distinct.

The user explicitly authorized inspecting the presently open form and confirmed
no password was entered. An attempted accessibility inspection that would
discard field text before output was rejected by the tool's automatic safety
gate because the full authentication tree would first be read. No dump was
executed, and the rejection was not retried or bypassed. This is an inspection
tool limitation, not evidence about Twitch or Tachiai's rendering failure.
Safe native window metadata instead showed two visible Tachiai surfaces, with
landscape bounds `[128,74][2274,1080]` and `[0,0][2400,1080]`. No page content,
field values, cookie/session data or protected screenshot was collected.
Custom dialog/window/permission handling remains a possible influence; no
callback-occurrence comparison or minimal-host baseline has yet established it
as the cause. No browser policy or implementation changed in this observation.

### Diagnostics access audit and native callback coverage — 2026-10-04

The user identified insufficient inspection access as a blocking development
problem and requested distinguishing app choices from system restrictions.
Read-only source review and Android documentation establish:

| Layer | Observation and ownership |
| --- | --- |
| Tachiai | `BrowserPane` explicitly forces WebView debugging off, including debug builds. The session and popup explicitly set `FLAG_SECURE`. Session clients suppress console output and cancel alert/confirm/prompt/before-unload calls. Popup/navigation/permission restrictions are also our hosting policies. |
| Android | [WebView debugging](https://developer.android.com/reference/android/webkit/WebView#setWebContentsDebuggingEnabled(boolean)) is supported and app-wide, not per pane. [FLAG_SECURE](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SECURE) enforces requested screenshot/display protection; this is not a general Android ban on inspecting WebViews. |
| Agent execution | The rejected authentication accessibility-tree read was blocked by the tool's safety gate, independently of Android or Twitch. User authorization was acknowledged, but the rejection was not bypassed through another inspection route. |

Blanket dialog cancellation changes browser semantics, not just observability;
see [WebChromeClient](https://developer.android.com/reference/android/webkit/WebChromeClient).
The existing successful native document milestones also omitted subresource
failures. These omissions left our custom hosting policies insufficiently
observable and must not be used to attribute failures solely to Twitch.

The next build adds closed native event kinds for cancelled script dialogs,
window requests/policy denial, permission grants/denials, and suppressed console
ERROR/WARNING occurrences. Console handling reads severity only, never message,
source identifier or line number, and emits each of those severities at most
once per WebView lifetime. Main callbacks share the existing browser ID with
load events. Popup events retain their own local ID and terminal guards.

Native subresource network/HTTP failures are now separate informational events:
only native numeric error/status codes are recorded. They neither fail the
main page nor hide/stop the popup. Each WebView/popup deduplicates and caps them
at eight distinct kind/code pairs for its lifetime, then emits one limit marker.
No request URL, headers, body, description, MIME type, provider string, field
value or session state enters these diagnostics. Existing debug/request gates
and fixed-tag/current-process collection remain in effect. This is native
callback monitoring, not login-page script injection or a network trace.

All hosting/security decisions remain unchanged in this build. No DevTools
endpoint or screenshot access is enabled, no dialog behavior is restored, and
no authentication-page inspection is performed. The markers improve observable
coverage; they do not solve full page-inspection access or establish a login
failure cause. Unit tests cover fixed serialization, release/opt-out gates,
numeric-code restrictions, deduplication and lifetime caps, not actual Android
callback integration. Rendering-only direct-login and popup tests are pending.

An app-owned, credential-free debugging fixture in an isolated debug process
and browser profile is a candidate next tooling step, not implemented behavior.
Because WebView debugging is app/process-wide, enabling it for one pane in the
current provider process would also expose other provider WebViews. Do not
present a per-pane toggle as isolation or use debug access to bypass the rejected
authentication inspection. A genuinely minimal-host comparison and secure,
user-operated replacements for cancelled dialogs also remain unimplemented.

The callback-coverage build passed 76 Android unit tests, lint with the same two
existing warnings, targeted formatting/secrets checks and documentation rendering.
Independent scoped review found no blockers, while retaining the callback
integration and full-inspection limits above. The Android-build skill used the
pinned disposable SDK container and read-only stable signing key; `apksigner`
matched the documented certificate. APK SHA-256:
`2c312c12910bb8763749473cbf312cdd923a76e9a152fcef062b475d8282092c`,
12,347,339 bytes, host-user-owned. Installation retained app/browser data;
scope/diff review found no generated-file additions or unrelated cleanup.

An explicit session launch in the updated process confirmed unchanged MAIN
configuration and delivery of both `CONSOLE_WARNING_SUPPRESSED` and
`CONSOLE_ERROR_SUPPRESSED` during full-embed initialization, alongside native
commit/completion milestones. Only severity occurrences were collected. Such
warnings/errors can arise from any content in the WebView and do not identify
a failing component, diagnose the standalone blank page, or prove that our
dialog/navigation policies fired. This verifies those callback markers on this
device, not all new callback paths. Direct-login and actual popup reproduction
with the additional markers remain pending, as does full diagnostic inspection.

The user reopened the actual sign-in popup in the callback-coverage build.
Current-process native markers confirmed `WINDOW_REQUESTED`, popup 1 opened,
allowed HTTPS navigation, visible commit and completion with unchanged default
identity/parent equality and JavaScript/storage/cookie settings. A subsequent
`SUBRESOURCE_HTTP_ERROR code=429` was recorded for the popup. No cancelled-script-
dialog, denied-permission, nested-window-denial, navigation-block or console
error/warning popup marker appeared in the retained trace. The main embed's
earlier console severity markers remain separate from popup events.

[HTTP 429](https://www.rfc-editor.org/rfc/rfc6585#section-4) denotes rate limiting,
but the native event deliberately does not identify the URL, responding host,
resource type or body. It cannot establish whether essential authentication,
an unrelated third party, or another resource failed, and does not explain
the earlier unsupported-browser result. No credential submission was requested
for this rendering/configuration comparison. Repeated reload/login attempts
were discouraged; no Retry-After value or cooldown duration was established.

At the user's request, the next controlled checks revisit direct login and
normal web mode once each, not the viewport matrix. They retain the same
installed APK, default identity, persistent profile and native restrictions.
The user was asked to close the sign-in popup and select direct login with
default viewport, then report appearance. That reproduction and subsequent
normal-web check are pending; do not infer rendering from callback milestones.

The user subsequently reported the direct-login page blank on the unchanged
callback-coverage installation. Live fixed-tag collection from the same process
recorded browser 4 with default identity, JavaScript/storage and both cookie
acceptance settings enabled; wide/overview and multiple/automatic windows were
disabled, with mixed-content blocking retained. STARTED, FINISHED and
COMMIT_VISIBLE events appeared without a retained subresource failure, console
severity, cancelled-dialog, denied-permission or blocked-navigation marker for
that instance. This does not establish a clean frontend or identify the cause:
the bounded native callbacks do not cover every browser failure. Device, OS and
engine remain the recorded Pixel 6 / Android 17 / WebView 153.0.8010.36; no new
region, account-state or orientation observation was made, and no credentials
were requested. The normal-web check remains pending.

For the subsequent normal-web check, the user reported initial rendering,
followed by a blank page, then rendering returning. No further reload or mode
change was requested between those reports. The same-process browser 5 retained
the recorded default settings and emitted native load/visible-commit milestones,
one console WARNING and one ERROR occurrence, then subresource network error
codes `-2` and `-1`. Android defines these as hostname-lookup failure and generic
error respectively; see [WebViewClient constants](https://developer.android.com/reference/android/webkit/WebViewClient#ERROR_HOST_LOOKUP).
No retained cancelled-dialog, denied-permission, blocked-navigation or
renderer-gone marker appeared for that instance. No resource URL, responding
host, console text or page content was collected. These failures may concern
essential or unrelated resources; their role in the transient blank state is
unresolved. The rendering recovery is user-observed, not proof of successful
authentication, entitlement, stable playback or an identified fix. Device/OS/
engine conditions remain as above; no new account, region or orientation state
was inspected.

The user then clarified that the returned content was squashed to a very short
height. This qualifies the rendering recovery: usable sizing was not restored.
Whether the short area is the native browser surface, the provider page layout
or only the player has not yet been distinguished. No sizing setting or focus
adapter was changed for this normal-web comparison.

The user clarified that the whole page, not just the video, was short. Its
native browser bounds were not measured in this run, so this still does not
separate native host sizing from provider layout. At the user's request for
unhindered debug tooling alongside deployment protections, an isolated
credential-free [browser laboratory](android-playback-spike.md#build-modes-for-browser-diagnostics)
was implemented and its screenshot, debugger and resize access verified.
It does not inspect the existing authentication profile or identify this
provider-page failure; provider rendering and login investigation remain open.

### Real-probe geometry and default-dialog comparison — 2026-10-04

The user approved returning to the existing prototype rather than adding more
fixture work. The next build records the real WebView's native physical width
and height through layout callbacks, linked to the existing lifecycle browser
ID. Only numeric dimensions and closed requested-role/dialog-policy labels are
logged. Adjacent identical sizes are suppressed; returning to a previous size
is recorded. Each browser permits 32 size changes then one overflow marker.
No changing-height geometry UI, DOM/CSS viewport readback, scripts, screenshots
or page inspection is added. These dimensions measure the native surface, not
the visible provider player or its internal layout.

Normal WEB already has no focus script or adapter diagnostic injection. Its
new “Web — default dialogs” comparison changes only a debug request flag for
standard browser alert/confirm/prompt/before-unload handling. Default dialogue
handling is delegated to Android rather than automatically cancelled; closed
callback-type markers record delegation, not a shown or completed dialog.
Console messages remain suppressed with only severity markers. The default
flag is false, is ignored outside debug builds, and is not selected by direct
login, desktop-web, full-embed or authentication-popup requests. Storage,
identity, viewport, navigation, media permissions and TLS policies remain the
same. Both requests remain in the persistent existing profile.

This is a one-variable hosting comparison, not a completely stock-browser
baseline. Navigation and other callbacks remain custom, and a standard script
dialog may block progression until the user responds. Default dialogs have
their own Android windows, which do not inherit the Activity's secure flag;
do not inspect their provider text or capture them. Remote debugging and the
probe's existing secure window remain unchanged. No credential submission or
authentication inspection is needed for the experiment.

The debug session launch accepts the additional closed string extra
`net.fstab.tachiai.extra.TWITCH_WEB_MODE`: `web` selects the baseline and
`default-dialogs` selects the comparison. Other values retain the original
full-embed start. This works only with the existing debug `TWITCH_SIGN_IN`
probe and validated channel. Each delivered launch creates a fresh browser;
the dialog flag is also part of the generic browser recreation key. Requested
role labels describe the chosen probe, not inspected provider navigation state.
Build and device results are pending. Compare one load each at the same
orientation/channel and distinguish user-reported appearance from callback
milestones; persistent/provider state changes remain sequential-test confounders.

The Android-build skill used the established pinned SDK container and read-only
shared debug key. The build passed 82 Android unit tests, lint with the same two
existing warnings, debug APK assembly, and targeted session/secrets/documentation
checks. `apksigner` matched the documented certificate. Host-owned APK size is
12,347,339 bytes, SHA-256:
`977e46bf9f51e4999cc2432d71e3a48b7bc48732e6b328a8629a0938d070315a`.
Scoped review found no blockers; tests cover helper/adapter wiring, not real
dialog delegation or Compose recreation. No generated files or unrelated
cleanup were added, and existing work was preserved.

The updated APK installed over the existing `net.fstab.tachiai` without clearing
data. An explicit single-top debug launch delivered the normal-web request in
the updated process after Android restored its existing task. On the Pixel 6,
Android 17, WebView 153.0.8010.36, native markers identified browser 1 as WEB
with dialogs CANCELLED and dimensions 2146 × 754 physical pixels. Commit and
completion followed, with console WARNING/ERROR occurrences and subresource
network codes `-2` and `-1`. The retained trace showed no geometry shrink or
cancelled-dialog occurrence. The phone was unlocked; account and current network
region were not inspected, and no credentials were requested. User appearance
confirmation and the subsequent default-dialog comparison remain pending.
Do not assign those full-height dimensions retroactively to the preceding APK's
short-page report or infer provider rendering from native milestones.

The user reported Twitch's “Keep using web” prompt on that baseline, selected
the web option as requested, and then reported the page squashed. Live native
collection retained no intervening geometry change from 2146 × 754 pixels.
This distinguishes the reproduced short-looking page from a collapsed native
WebView in this run. It does not identify CSS, zoom, page state, failed resources
or another provider-side cause. The existing browser/dialog/navigation policies
remain possible influences on page behavior. The same channel/profile was then
launched once in DEFAULT_DIALOGS_WEB; appearance and callback comparison are
pending, with no credential entry requested.

The comparison's native marker confirmed WEB with dialogs DEFAULT and the same
recorded browser settings. Its geometry was 2146 × 691 rather than 754 pixels
high: the longer stage label in the native header introduced a viewport
confound. No cancelled or delegated script-dialog marker appeared in the initial
trace; console WARNING/ERROR occurrences remained. Before interpreting a
rendering comparison, the WEB header text is now identical in both modes, with
dialog-policy identity retained in native markers. This narrowly scoped label
correction is for experiment validity, not layout polish. Rebuild/device
verification of equal pane heights is pending.

The corrected header build passed 83 Android unit tests, the same bounded lint
warnings, assembly and certificate verification with the same pinned SDK
container/read-only shared key. Its host-owned 12,347,339-byte APK SHA-256 is
`66e979178eedeeb86bd6d09a3c2fa9a11e4c36237f96592b6e6450abc0a00d44`.
Installation again retained the existing application/browser data. The explicit
default-dialog launch in the updated process recorded WEB / dialogs DEFAULT
and 2146 × 754 physical pixels, matching the preceding baseline. Native load
milestones, console WARNING/ERROR occurrences and subresource network codes
`-2`/`-1` followed, without a retained script-dialog cancellation or delegation
marker. This verifies the equal-height native comparison and its selected
policy, not an actual default-dialog callback or improved provider rendering.
The user was asked to select the web option once if offered and report sizing;
that appearance outcome remains pending. No screenshots, provider scripts,
credential submission or authentication inspection were performed.

The user reported the corrected default-dialog comparison still squashed after
the requested web selection. Live native collection retained no size change
from 2146 × 754 and no script-dialog cancellation or delegation event. The
normal-web baseline and this default-dialog variant therefore both reproduced
the short-looking page inside a full-height native surface. Enabling default
dialog handling did not correct this run; automatic cancellation was not
observed firing. This narrows the investigation without proving a universal
cause or clearing every custom host policy. Internal page layout, viewport/zoom
state and resource failures remain unresolved. The transient blank-page and
unsupported-browser authentication outcomes remain separate investigations.

### Authentication popup nested-window dispatch — 2026-10-04

The user reports that portrait is less squashed than landscape and prioritizes
the login rejection over layout. This is a visual report, not a measured
portrait viewport comparison; no connection to authentication rejection has
been demonstrated. Layout investigation is paused.

Public Android documentation identifies a host-policy difference: disabling
multiple-window support converts `window.open()` and `target="_blank"` into
navigation in the existing WebView instead of invoking `onCreateWindow`.
Consequently, the authentication popup's existing nested-window denial callback
may not observe such requests. The popup now enables multiple-window dispatch
while retaining automatic-window permission disabled and returning false from
its nested `onCreateWindow` callback. This makes the existing denial explicit
rather than permitting silent same-window conversion. It does not allow another
window or change the official SDK's original transport relationship.

Source: [Android WebSettings](https://developer.android.com/reference/android/webkit/WebSettings#setSupportMultipleWindows(boolean)).

This is a narrowly scoped hosting correction, not a verified Twitch login fix.
No evidence yet shows that Twitch's rejected submission attempts a nested
window. Gesture-free requests may be blocked independently by automatic-window
permission, so an absent denial callback does not exclude every nested-window
attempt. Legitimate nested links that previously replaced the popup will now
be denied; that enforces the intended policy but may affect compatibility.
Default identity, persistent storage, cookie settings, origin guards,
TLS checks, permissions, protected windows, console suppression and dialog
cancellation are unchanged. The normal-WEB default-dialog trial did not exercise
the real authentication popup; a popup dialog-policy comparison is deferred
until a private submission's closed callback markers justify that change.

Build, installation and actual submission results are pending. The next test
uses the existing prototype's full official embed, with the user opening the
provider's login popup and privately attempting login once. Collect only fixed
native settings/lifecycle/error/dialog/window markers and the user's result;
do not inspect the form, credentials, session, URLs or provider console text.
The settings serialization test distinguishes nested-window dispatch enabled
from automatic-popup permission; it does not execute Android callbacks.

The Android-build skill used the established pinned SDK container and read-only
shared debug key. All 84 Android unit tests passed; lint retained only the two
existing `ModifierParameter` / `IconMissingDensityFolder` warnings. APK assembly
and `apksigner` verification passed with the documented certificate. The
host-owned APK is 12,347,339 bytes, SHA-256:
`60f8f64f782e80af993cd1e6378d058a71f309645d6aa32589e7352a16e039b7`.
Targeted pre-commit checks, including secrets detection and documentation build,
passed. Scoped read-only review found no blocker, with the compatibility and
callback-observability limitations recorded above. No generated-file additions,
layout changes or unrelated cleanup were introduced by this correction.

The user reclaimed the phone before installation. Do not operate it until the
user returns it; installation, runtime settings and login results remain
unverified. The planned test device remains Pixel 6 / Android 17 / WebView
153.0.8010.36, but recheck the engine on resumption. Region, network and account
state are not newly verified by this build-only work.

On 2026-10-05 the user returned the phone. The unchanged verified APK checksum
was confirmed and `adb install -r` succeeded without clearing data. WebView is
still 153.0.8010.36 on the Pixel 6. The explicit original full-embed launch
recorded EMBED / dialogs CANCELLED, default identity, JavaScript/DOM storage and
first-/third-party cookies enabled, and native geometry 1080 × 1642 physical
pixels. A subresource network error `-2` and page completion were recorded;
completion does not establish provider rendering or login readiness. No popup
has opened in this process yet, so its runtime settings and the authentication
outcome remain pending. Current region/network acceptance and account state
are not inspected or inferred from these markers.

The user then reported the actual provider popup form presented, with the
keyboard still shown. Native markers in the same process show a recreated
EMBED browser (landscape geometry), `WINDOW_REQUESTED`, popup 1 opened, an
allowlisted HTTPS navigation/commit and page completion. The popup's native
configuration snapshot confirms default identity matching its parent,
JavaScript/DOM storage and first-/third-party cookies enabled,
`multipleWindows=true`, `automaticWindows=false`, and mixed content blocked.
This is a pre-transport settings snapshot, not a browser-internal post-handoff
readback. A suppressed console-error occurrence was recorded without its text.
No retained nested-window denial, dialog cancellation or terminal popup failure
marker was seen before submission. Parent geometry later reached zero height
while the popup/keyboard was active; that is not measurement of the popup or
proof its form collapsed. No authentication content was inspected. Private
submission and its outcome remain pending.

The user privately submitted once and reported the same browser-not-supported
message. The retained native trace adds popup 1 `SUBRESOURCE_HTTP_ERROR` with
code 400. The resource/endpoint, response body and provider error code were not
inspected, so this must not be attributed to a particular login/integrity API.
This callback is informational: Tachiai does not intercept or replace that
subresource response or stop the popup because of it. No retained nested-window
denial, script-dialog cancellation or terminal main-document error appeared in
this collection. Absence of a callback does not exclude independent browser
blocking, unretained events or other host effects. The dispatch correction did
not resolve this attempt, and the authentication cause remains unresolved.

The user asked about the error's links. Live authentication content is not
inspected through alternate tool routes; their exact targets remain unknown.
Public search located Twitch's
[Supported Browsers](https://help.twitch.tv/s/article/supported-browsers?language=en_US)
article, but direct retrieval returned the help portal's CSS error rather than
the article body. Do not claim this is a verified target of either popup link.

The user reported long-press behaving as drag and clicking the help links not
navigating. Subsequent native collection retained two `NESTED_WINDOW_DENIED`
events for popup 1. This is consistent with those clicks requesting another
window and Tachiai rejecting it; exact link targets and one-to-one click/event
correspondence are not inspected. The earlier submission trace did not show
these events, so do not attribute the login rejection to these later help-link
denials. The new dispatch setting makes this intended host restriction visible.

### Independent public-page source check — 2026-10-05

The user clarified that direct loading should also be investigated here,
independently of the phone. The research web reader returned no extracted text
for either `https://www.twitch.tv/login` or its `?popup=true` variant; that is
not evidence of browser rendering failure. An attempted local in-app-browser
tab could not be created because that browser is unavailable, and the computer
tool's browser inventory was empty. No local rendering result was obtained.

An anonymous HTTP fetch of the popup variant succeeded with 185,636 bytes of
HTML and title Twitch. No phone/session cookies, credentials or login/API
requests were used. A bounded read of its 29 referenced public static scripts
found `https://help.twitch.tv/s/article/supported-browsers` in
[the public settings asset](https://assets.twitch.tv/config/settings.a346c0e26e35ab87386587f3d6bd61f2.js)
and a `window.opener` reference in
[a startup bundle](https://assets.twitch.tv/assets/21956-1764bec9bf11526bf6b6.js).
These are deployment-specific public-source observations, not inspection of
the user's live authentication page. The support URL's presence does not map
either exact error link to it; an opener reference alone does not establish
which login behavior depends on it. Lazy-loaded authentication chunks, runtime
behavior and the rejection cause have not been systematically analyzed yet.

The user authorized Brave's scraping profile for a local Playwright rendering
comparison. Computer-use browser inventory remains empty, but a separately
available Playwright MCP is configured for `/usr/bin/brave-browser`,
`Profile 2`; local documentation identifies that as the scraping profile.
Its extension connection is pending user approval/availability, not a verified
rendering result. Do not use the normal profile, submit credentials or inspect
stored session material for this comparison.

Further anonymous static-source research located the lazy-loaded
[login renderer](https://assets.twitch.tv/assets/32683-40511fffc5990b8ef8a4.js).
Its `AuthFormLoginError` switch groups `InvalidIntegrity` (5021),
`MissingIntegrity` (5022), `IntegrityFailed` (5025) and `IntegrityUnexpected`
(5026) into the browser-not-supported message and its two inline links. The
first two enum values appear in that renderer; the latter two are defined in
[the startup bundle](https://assets.twitch.tv/assets/21956-1764bec9bf11526bf6b6.js).
Both links use `targetBlank=true` and respectively obtain their destinations
from `kpsdk_deprecated_url` and `kpsdk_helpsite_url`. The public settings asset
currently supplies the exact same Supported Browsers article URL for both.
This replaces the earlier unknown public-source mapping, not a direct
inspection of the live phone's link elements or response.

These deployment-specific frontend mappings explain why the visible message
does not by itself prove a literal unsupported browser version. They provide
a lead toward the integrity/error-handling path, not proof of which branch
fired on the phone: its native HTTP 400 event contains no endpoint, body or
provider error code. They also support, without proving one-to-one event
correspondence, the inference that the later help clicks requested new windows
which Tachiai denied. No integrity proofs/tokens were inspected, generated,
replayed or patched; no protected login request was made in this research.

The public
[standalone authentication bundle](https://assets.twitch.tv/assets/features.auth.components.standalone-auth-pages-26d6ed7d622beb2880cc.js)
also posts an `authenticationSuccess` message through `window.opener` after
success. That supports retaining the SDK-created popup relationship, not
reproducing the login URL as an independent window; it does not explain this
pre-success rejection. Static startup research additionally identified dynamic
integrity-script loading/readiness handling, with a public settings timeout of
90 seconds. Ordinary script-loading/readiness is a legitimate diagnostic lead,
not a verified WebView fix or authorization to alter integrity checks.

The Playwright tab-list attempt ultimately timed out after 300 seconds without
opening or inspecting a page. The user was asked to open the scraping profile
and approve its extension connection if prompted. Local browser rendering,
runtime resource comparison and any account state remain unverified; no
automatic retry or fallback to the normal profile was performed.

### Brave public login rendering comparison — 2026-10-05

After user-authorized retries, Playwright connected to the designated Brave
scraping profile. Earlier read-only host diagnostics found the selected MCP
processes had the correct absolute Brave/data-directory/profile arguments but
lacked desktop display variables present in their parent Codex process. That
was a plausible silent-launch problem: the public MCP launcher discards browser
output and waits for its extension. However, connection subsequently succeeded
without an agent configuration change. Do not settle missing environment
forwarding as this failure's cause or apply the proposed fix on that evidence.

A separate task-owned tab loaded both public `https://www.twitch.tv/login?popup=true`
and `https://www.twitch.tv/login`. Both reached title Log In - Twitch and a
visible password field / Log In button. For the plain route, a follow-up also
confirmed two textbox-role elements and a visible first textbox. Earlier exact
username/autocomplete selector probes returned false; that was not proof of an
absent username field. No input values were read and no credentials submitted.

Passive browser-host listeners on the plain-route navigation collected script
responses until the password field appeared. All observed script responses were
HTTP 200, with no script-request failure in that bounded interval. This included
the existing startup/config assets, the identified lazy login renderer and
standalone auth bundle, and a script from `k.twitchcdn.net`. Public asset paths
were retained only for `assets.twitch.tv`; other script responses logged host
and status only. No query strings, headers, cookies, request/response bodies,
provider console text, integrity values or authentication API results were read.
Successful script downloads do not establish execution/readiness, later
resource success, integrity acceptance or successful authentication.

This is a desktop Brave reference, not a phone WebView reproduction. The host
Brave executable reported 154.1.96.61; the extension's browser-version API
returned Extension-Bridge and its viewport API returned null, so those APIs
did not independently verify runtime engine version or dimensions. Existing
scraping-profile storage was neither inspected nor cleared; anonymous HTTP
source research must not be confused with a verified fresh browser profile.
Account state and current region/network acceptance remain unverified. The
phone is still the previously recorded Pixel 6 / Android 17 / WebView
153.0.8010.36; no new phone test occurred in this comparison.

The public page was replaced with about:blank after observation. Automatically
generated Playwright snapshot/console artifacts are removed without reading
their content; sanitized text evidence is retained here instead. No app code,
browser settings, provider protections or MCP configuration changed. The next
useful investigation is a narrowly bounded comparison of ordinary WebView
resource-loading callbacks against this reference, not another credential
submission or integrity-check alteration.

### Public response provenance and native resource probe — 2026-10-05

Further bounded public-source tracing found that the ordinary password-login
handler in the startup bundle posts to the provider and returns `error_code`
from a non-success JSON response. The login renderer passes that value to the
error UI described above. The reviewed handler locally produces only separate
network/JSON-parse errors, not those four integrity error codes. SDK preloading
has a caught failure/telemetry path, not a demonstrated local unsupported-browser
veto. No direct user-agent/version/WebView refusal was found in this reviewed
ordinary-login path. This is response-driven UI evidence, not proof enforcement
is exclusively server-side: the opaque SDK could inspect the browser or mediate
requests. Its internals and the phone's response body remain uninspected.

The next debug-only popup probe classifies a pinned snapshot of public script
paths from the anonymous source research into CONFIG_SCRIPT, BOOTSTRAP_SCRIPT,
AUTH_UI_SCRIPT and PROTECTION_SCRIPT. Provider-specific origin/path knowledge
stays behind the Twitch diagnostic adapter; generic hosting accepts only closed
categories. Exact HTTPS origins, no userinfo, default/443 ports and exact raw
paths are required. Queries/fragments are not classified or retained. Changed
deployments and unknown resources produce no category marker. Authentication
submission endpoints are deliberately not classified.

`TachiaiResource` records REQUEST_OBSERVED from `shouldInterceptRequest` and
matched native NETWORK_ERROR / HTTP_ERROR callbacks, with numeric codes and
monotonic milliseconds since popup construction. It retains at most sixteen
distinct kind/category/code markers and one overflow marker per popup. The
budget/close guard are synchronized for WebView's background request callbacks;
terminal failure/dismissal stops recording. It requires both a native-diagnostic
opt-in and a debug build. Existing generic unknown-resource failures remain.
Subresources still return null from interception, retaining WebView's original
network handling; no requests, headers, response bodies, cookies, tokens, login
DOM, console text or integrity proofs are inspected, replaced or replayed.

[Android's callback contract](https://developer.android.com/reference/android/webkit/WebViewClient)
does not make REQUEST_OBSERVED a successful download, script execution or SDK
readiness signal. Some requests/redirect targets may not reach interception.
No reported error likewise does not establish success. Compare the Brave
reference's observed HTTP 200 script responses with Android's observed requests
and native failures, not with assumed HTTP 200 responses. Waiting longer is an
experimental scheduling comparison, not a verified remedy or readiness check.
Build, installation, rendering and submission outcomes are recorded separately
below; this implementation description does not establish them.

The pinned Android tools container passed 91 debug unit tests, lint (the two
existing ModifierParameter / IconMissingDensityFolder warnings) and APK
assembly. Seven new helper tests cover public-resource classification, bounded
serialization, opt-in gating, concurrency and closure; these are not WebView
callback integration tests. The certificate matched the documented shared
debug identity. APK SHA-256 is
`e2b1e6ca7a5f33d3a977f57fbe5da8a753ba30935ecc8423ee65cd0f8f7933b4`;
the host-owned file is 12,347,339 bytes. Targeted repository checks passed,
including secret scanning, session JavaScript tests and documentation build.
The narrow diff was inspected against the starting worktree; unrelated changes
were preserved and no generated files were added.

`adb install -r` succeeded without clearing data on the Pixel 6, Android 17,
WebView 153.0.8010.36 (reverified). The first post-install launch was delivered
during restart and the new process recorded the default OTHER route, not the
requested authentication probe. A subsequent explicit intent targets the
existing full embed for `bobross`. The phone was awake; Tachiai's visible
activity keeps it awake without changing the system timeout. Network region,
account state and successful form/resource rendering remain unverified.

The explicit second intent recorded TWITCH_SESSION in the new process and
EMBED / dialogs CANCELLED. Main settings retained default identity, enabled
JavaScript/DOM storage and first-/third-party cookies, multiple-window dispatch
and blocked mixed content; automatic windows remained disabled. Portrait
geometry was 1080 × 1516, later 1080 × 1453 physical pixels. Main-document
commit/completion and a generic subresource HTTP 404 were recorded. That 404
has no endpoint attribution and must not be called an integrity-script failure.
The popup has not opened in this collection; the user was asked to open its
form without submitting credentials, so popup classification remains pending.

The user subsequently confirmed the popup form visible. On the same device
and engine, the parent was recreated in landscape at 2146 × 439, later
2146 × 376 physical pixels. Its later zero height while the popup/keyboard
was active is not a measurement of popup geometry. The actual popup recorded
default identity matching the parent, the unchanged native settings above,
allowed HTTPS navigation/commit and page completion. Its native resource trace
observed CONFIG_SCRIPT at 250 ms, BOOTSTRAP_SCRIPT at 284 ms,
PROTECTION_SCRIPT at 429 ms and AUTH_UI_SCRIPT at 475 ms after popup
construction. No matched category error, overflow or generic popup resource
failure appeared in the retained pre-submission trace. The earlier parent
HTTP 404 and network -1 remain unattributed parent events, not popup failures.

This establishes that requests for each pinned public-resource category reached
native interception. It does not establish successful responses, every auth-UI
chunk, script execution, SDK readiness or integrity acceptance. The user was
asked to leave the form open for about two minutes before one private
submission to compare delayed submission with the earlier rejection. The
wait duration and submission outcome have not yet been confirmed; native
monotonic log formatting and `/proc/uptime` use different clock bases here and
were not treated as a verified popup-age calculation. Region/network and
account state remain uninspected. No authentication content was captured.

After that delayed-submission instruction, the user privately attempted login
once and reported the same failure. The exact wait duration was not measured.
The same popup's retained native trace added SUBRESOURCE_HTTP_ERROR 400 and
one suppressed console-error occurrence. No matched CONFIG_SCRIPT,
BOOTSTRAP_SCRIPT, AUTH_UI_SCRIPT or PROTECTION_SCRIPT error or overflow marker
appeared. No retained nested-window denial, cancelled script dialog or terminal
main-document failure appeared in this submission collection. HTTP 400 still
has no endpoint/type/body/provider-error-code attribution; it cannot establish
the exact integrity branch or whether the opaque SDK initialized successfully.

Result: the requested delayed attempt did not resolve the visible rejection,
and this probe found no missing request or reported native failure for the
pinned public-script categories. It has reached its diagnostic limit for
successful downloads/execution/readiness. Do not claim all resources loaded,
enforcement is exclusively server-side, or Android WebView authentication is
universally impossible. No additional credential retries or browser-setting
changes are justified by this trace alone. Supported browser-hosting options
need a separate feasibility investigation against the two-feed, mixed-audio,
swap and alignment requirements; an external browser login is not evidence
of an authenticated app-private WebView session.

### Chrome-like identity popup comparison — 2026-10-05

The user requested an identity override before changing hosting architecture.
The debug-only Everything session adapter now selects MOBILE_CHROME instead of
DEFAULT. Its existing native helper removes the WebView `; wv` and `Version/4.0`
markers while retaining the actual engine version and the remaining Android
identity. The existing real WebViewTransport popup copies its parent's native
user-agent before navigation, so the embed and provider-owned popup receive the
same identity. Direct-login and other diagnostic stages are unchanged.

Only this request identity and its unit-test expectation changed. There is no
new login-page injection, SDK patch, browser engine, cookie transfer, TLS
exception, dialog/popup policy, viewport change or resource interception.
Persistent app-private data is preserved. Android may also change user-agent
client hints as a consequence of its native UA override; they are not separately
patched or inspected here. This is an unsupported compatibility experiment,
not a claim to be actual Chrome, a known accepted identity, or a proven fix.
Build, installation and runtime outcomes remain separate from the change.

The Android-build workflow passed all 91 debug unit tests, lint (the same two
existing warnings) and assembly. The signing certificate matched the shared
debug identity. The host-owned APK is 12,347,339 bytes with SHA-256
`2731229b4357afab77ce60e0be3af4b68b59f2f604a20c78d1f917fcac098efd`.
Targeted pre-commit checks, including session JavaScript tests, secret scanning
and documentation build, passed. Diff inspection against the starting snapshots
confirmed only the adapter request identity, its existing expectation and this
experiment documentation changed; no generated files or unrelated cleanup.
`adb install -r` succeeded without clearing app data. Only Tachiai was stopped
and relaunched into the same `bobross` Everything probe so an old popup cannot
retain the default identity. Runtime popup identity and acceptance remain to be
verified independently.

The fresh process recorded TWITCH_SESSION and EMBED / dialogs CANCELLED, with
MAIN `defaultIdentity=false`, confirming the override took effect in the
parent. Other recorded settings stayed unchanged. Landscape geometry remained
2146 × 439 physical pixels. Main commit/completion and an unattributed parent
HTTP 404 were recorded. The user is asked to open the same provider-owned popup
and attempt login once privately; its native identity snapshot and submission
outcome remain pending. No new accepted network region or account state is
inferred from the parent load.

The user privately submitted and reported that the attempt took longer but
produced the same unsupported-browser error. No duration was independently
measured. The subsequent process-filtered main-log collection retained only
popup 1 SUBRESOURCE_NETWORK_ERROR -2; earlier popup settings/resource markers
were no longer present in that buffer, so this collection cannot independently
verify the popup's runtime identity or attribute the network error to a script
or submission. The parent override was verified before the attempt, and code
copies that identity to the child; that is separate from a retained popup
snapshot. Do not report an HTTP 400 or successful resource loading for this
attempt without evidence. Result: the mobile Chrome-like comparison did not
resolve the user's visible rejection; acceptance and cause remain unsettled.

### Browser-backed hosting qualification — 2026-10-05

The user asked whether browser-backed hosting preserves the existing layout
and stream controls. For standard Android Custom Tabs, it is not a drop-in
WebView replacement. The
[official overview](https://developer.chrome.com/docs/android/custom-tabs)
documents installed-browser session/features and distinguishes WebView for
app-driven JavaScript injection. Custom Tabs launch a browser-owned Activity;
[partial-tab APIs](https://developer.chrome.com/docs/android/custom-tabs/guide-partial-custom-tabs)
provide bottom/side-sheet sizing, not an arbitrary embedded View or provider-DOM
control API. Standard hosting does not expose our current `evaluateJavascript`
mechanism for ABEMA focus, child-frame composition and media commands.

Inference for Tachiai: app-owned web-page layout and Twitch's documented SDK
logic are potential portable pieces, but the current ABEMA-top-level adapter
cannot simply move unchanged to a Custom Tab. Mixed audio, source swap and
relative alignment for the actual ABEMA/Twitch composition would require a
different way to preserve those controls and new playback validation. This is
a documented API-fit limitation, not a tested Custom Tabs playback failure or
a claim that every browser-backed architecture is impossible. No Custom Tabs
implementation, authentication retry or architecture migration was performed.

### Own-client device-authorization probe — 2026-10-05

The user chose to implement Twitch's device flow in Tachiai and registered its
own PUBLIC application. A debug-only native probe now requests an empty-scope
device challenge, hands the exact allowlisted activation link to Android's
external URL handler, polls at the provider interval and validates the resulting
user token against Tachiai's client ID and empty scopes. It drops token
references afterward. No website cookies, shared TV client IDs, private GraphQL,
raw media, credentials or playback adapter changes are involved.

Protocol references are Twitch's
[device-code grant documentation](https://dev.twitch.tv/docs/authentication/getting-tokens-oauth/#device-code-grant-flow),
[token validation](https://dev.twitch.tv/docs/authentication/validate-tokens/)
and [RFC 8628 polling rules](https://datatracker.ietf.org/doc/html/rfc8628#section-3.5).
Empty-scope acceptance starts as an experimental assumption, not a documented
guarantee; observed results are recorded below. The link may use Twitch's app rather than
a browser. Neither outcome shares a browser session with Tachiai's WebView.

The intended device is the previously tested Pixel 6 / Android 17; device,
browser/handler, account, region/network and authorization outcomes must be
recorded when actually observed, not inherited from earlier playback runs.
There is no media source in this probe. Unit tests use synthetic responses and
exercise polling, expiry, URL/schema limits, cancellation, exact client/scopes,
request formatting, HTTP bounds and redacted diagnostics. Transport tests
replace the Android JSON decoder; production JSON normalization still requires
runtime validation. Success would prove only app OAuth; authenticated playback
and Turbo entitlement remain separate open questions.

The final Docker build passed all 115 debug unit tests, lint (the same two
existing warnings) and assembly, without the intermediate JSON compiler
warning. The certificate matched the shared debug identity. The host-owned APK
is 12,347,339 bytes with SHA-256
`ac4d2a377bc1e72e722639aede6f189d64e33a48d15d0804e887540b2d40d8d9`.
Targeted pre-commit/documentation checks passed. A direct source/test Gitleaks
scan passed after a line-specific public-client-ID false-positive annotation;
no scanner-wide suppression was added. Review against starting snapshots found
only the new probe/protocol/tests, explicit launch route and scoped documentation
changes, preserving earlier prototype work and excluding generated-file noise.

`adb install -r` succeeded without clearing app data, and Tachiai alone was
stopped and relaunched into the device-auth route. ADB reconfirmed Pixel 6 /
Android 17 and installed WebView 153.0.8010.36; this native probe does not use
WebView for authorization. No account, external handler, region or network
condition was inspected. The user was asked to Start, open activation and
authorize privately only for Tachiai with no requested permissions. Actual
token issuance and validation remain pending that interaction.

The fresh process recorded CREATE / TWITCH_DEVICE_AUTH, REQUESTING,
DEVICE HTTP 200 and WAITING, followed by TOKEN HTTP 400 without a terminal
phase. This establishes that the own-client empty-scope challenge was accepted
and parsed by the production JSON decoder. It does not establish approval,
token issuance or validation; the retained HTTP status alone does not expose
the pending response body. No activation code or browser content was captured.

### Device authorization failure diagnostics — 2026-10-05

The user twice reported a black screen after approval, then clarified that it
was inside Twitch's installed app; Tachiai displayed Network request failed.
The retained Tachiai trace contained REQUESTING, DEVICE HTTP 200, WAITING,
TOKEN HTTP 400 and NETWORK_ERROR, with no retained successful token status or
VALIDATING marker. This does not prove approval reached the OAuth server or
identify the failing I/O operation. ADB reported Android's global background
data restriction disabled, which does not rule out individual app/network
policies. The external authentication page and account details were not inspected.

At the user's request, the probe now retains and logs a fixed failure summary:
endpoint, request stage, exception-type category and bounded elapsed time.
Stages distinguish connection creation, configuration, sending, status retrieval,
reading and decoding. Categories distinguish DNS, socket timeout, TLS, socket
connection, interruption, our elapsed-response budget and otherwise unclassified
I/O. These are diagnostic classifications, not confirmed root causes; a WRITE
failure can occur while the HTTP implementation establishes DNS/TLS internally.
Intentional cancellation suppresses the transport failure report in the tested
blocked-request case. No automatic retry, permission increase, TLS relaxation
or token retention was added. Attempt numbers and fixed lifecycle markers help
correlate foreground/background transitions without inspecting browser content.

Activation now has explicit Brave and Chrome buttons. Android's
[package-targeted intent](https://developer.android.com/reference/android/content/Intent#setPackage(java.lang.String))
limits initial resolution to that browser; there is no installed-Twitch fallback.
It does not guarantee that subsequent browser navigation never delegates.
[Android's launch guidance](https://developer.android.com/training/package-visibility/use-cases#open-urls)
allows starting these URL intents without querying installed packages or adding
manifest visibility permissions. Authentication acceptance and the source of the
black screen remain unverified. Tests cover failure categories/stages, bounded
timing, cancellation suppression, fixed summary text and browser package choices.
The updated Docker build passed all 120 debug unit tests, lint (the same two
existing warnings) and assembly. The signing certificate matched the shared
debug identity. The host-owned APK is 12,347,339 bytes with SHA-256
`c4029a07f09f0259c0dd6c2cc549257346522742e550f444e1dba8b3108597e1`.
Targeted pre-commit/documentation checks and direct source/test Gitleaks scans
passed. Review against this task's snapshots confirmed only diagnostic/browser
routing code, tests and scoped documentation changed; no new dependencies,
manifest permissions, playback changes or generated-file noise were introduced.

`adb install -r` succeeded without clearing data. The initial ordinary launch
recorded CREATE / OTHER and only brought the task forward; the route was not
assumed from the command's success. A subsequent single-top intent recorded
NEW_INTENT / TWITCH_DEVICE_AUTH / revision 1, confirming route delivery. New
browser dispatch, failure-summary behavior and private approval/validation
remain pending the user's retry. No approval was performed by the agent.

### Foreground-aware device polling — 2026-10-05

The new trace recorded Brave handoff STARTED, ON_PAUSE, TOKEN HTTP 400,
ON_STOP, then TOKEN / WRITE / DNS / 358 ms and NETWORK_ERROR. The user
clarified that the black screen was in Twitch's app rather than Brave; an
activity snapshot also included Twitch's LandingActivity. Initial Brave dispatch
therefore did not keep all later navigation in that browser. Neither its page
content nor the approval request was inspected.

Read-only Android policy diagnostics then showed Tachiai's UID 10482 with
`blocked=APP_BACKGROUND` and `effective=APP_BACKGROUND`; its standby bucket was
10 and RUN_IN_BACKGROUND / RUN_ANY_IN_BACKGROUND defaulted to allow. This
is a current UID-specific block, distinct from the previously disabled global
background-data restriction. It supports a lifecycle/network-policy explanation
for the poll failure but does not independently prove the exact failing packet
or the cause of Twitch's black screen. The registered localhost redirect is not
used by this device flow; no redirect callback or registration change was added.

The user approved a foreground-only polling change. A platform-neutral gate
now tracks Activity resume/pause and pause generations. The native screen
closes that gate before launching activation. The worker waits before each
token poll, retaining the original device challenge and deadline; expiry is
not reset while away. Transport checks block new POST/GET network I/O if the
gate closes in the handoff race. An already-sent token POST is allowed to
finish, not forcibly cancelled merely for backgrounding.

An I/O failure associated with a pause generation is deferred until return;
ordinary foreground failures still terminate. Background-associated socket
timeouts double the poll interval, bounded by challenge lifetime. A post-send
failure is outcome-ambiguous: retry may report invalid/already-used code and
require a fresh authorization, rather than recovering a lost grant. If a grant
arrives while away, its token stays only in worker memory until foreground
validation or token expiry; the device code is never polled again. Validation
uses the separate token deadline conservatively based on request start.
Rotation/disposal, process loss and Cancel still end the attempt.

Unit tests cover foreground gating, resume without challenge replacement,
unchanged device expiry, pause/resume during failed I/O, timeout backoff,
foreground failures, grant arrival while stopped, separate token expiry,
idempotent validation retry and ambiguous consumed code. No browser credentials,
session sharing, foreground service, network-policy changes or playback changes
are involved. The separate browser-to-Twitch handoff problem is not claimed
to be fixed.

Final Docker verification passed all 135 debug unit tests, lint (the same two
existing warnings) and assembly. The certificate matched the shared debug
identity. The host-owned APK is 12,347,339 bytes with SHA-256
`7943584f24c2712b0e2262b88ba6074ba579f4cad6389ddd9b038ea1af0addca`.
Targeted pre-commit/documentation checks and direct source/test Gitleaks scans
passed. Snapshot diff review confirmed only the foreground gate, probe protocol,
transport checkpoints, lifecycle wiring, tests and scoped records changed;
earlier prototype work was preserved and generated artifacts stayed excluded.
`adb install -r` succeeded without clearing app data. The fresh process first
recorded CREATE / OTHER; the initial launch did not establish the probe route.
Redelivery after startup with a waited single-top intent recorded NEW_INTENT /
TWITCH_DEVICE_AUTH / revision 1. The next private approval/foreground-return
result remains pending; successful route delivery is not a playback/auth result.

### Token-response schema qualification — 2026-10-05

The next retained attempt showed DEVICE HTTP 200, Brave handoff, ON_STOP /
PAUSED, then ON_START / ON_RESUME / WAITING and TOKEN HTTP 200. It ended
in INVALID_RESPONSE without a VALIDATING marker. This confirms that the
foreground-return path resumed polling and reached a successful HTTP response;
it does not establish a valid user token or identify the response schema. The
body and token values were not inspected or captured.

Code inspection found that the grant parser required a `scope` field even for
the explicitly empty-scope request. [RFC 6749 section 5.1](https://www.rfc-editor.org/rfc/rfc6749.html#section-5.1)
permits omission when the granted scope is unchanged from the request. Omission
is therefore a protocol-compatible candidate explanation, not a verified fact
about the preceding response. The parser now permits true omission or an empty
array, but still rejects explicit null, other types and nonempty grant scopes.
Twitch's separate `/validate` response must still contain explicit empty scopes,
the exact Tachiai client ID, a user identity and positive expiry before success.

Fixed response-issue enums now distinguish grant type/lifetime/scope/token
checks, required validation fields, JSON decoding and size/schema failures.
They appear in native logs and the failure UI without bodies, messages, stack
traces, account values or token values. A fixed OMITTED/EMPTY grant-scope marker
records field shape only, not a valid grant or verified scopes. Tests cover
omitted-scope success, malformed grant-scope rejection, independent scope
validation and exact closed issue markers.

The Docker build passed all 139 debug unit tests, lint (the same two warnings)
and assembly; its certificate matched the shared debug identity. The host-owned
APK is 12,347,339 bytes with SHA-256
`e79dad5f596b009d3549fa82eacaf3c2aaf88d3702bc44ed98ce14d1652fc897`.
Targeted pre-commit/documentation checks and direct source/test Gitleaks scans
passed. Snapshot review confirmed only parser qualification, closed diagnostics,
tests and scoped documentation changed; no new permissions, dependencies,
playback behavior or generated-file noise. Installation with `adb install -r`
succeeded without clearing data. The new private authorization and actual scope
shape/validation results remain pending; omission remains a hypothesis for the
earlier rejection until observed by the new markers.

### Validation scope-field diagnostics — 2026-10-05

The next Pixel 6 / Android 17 attempt using the installed debug APK recorded
DEVICE HTTP 200, Brave handoff, ON_STOP / PAUSED, then ON_RESUME / WAITING,
TOKEN HTTP 200 and `grantScope=OMITTED`. It reached VALIDATING and VALIDATE
HTTP 200, then `responseIssue=VALIDATION_SCOPES` and INVALID_RESPONSE. This
establishes actual grant-scope omission and foreground-return progress. The
ordered validation checks accepted the exact client ID, a nonempty user
identity and positive expiry before rejecting the scopes representation.
It does not establish zero permissions, complete OAuth success, an embed
session or Turbo playback. This native HTTP experiment has no playback content
or WebView dependency. External account authorization was performed privately
by the user; browser login state and network region were not independently
inspected or changed in this retry.

The old issue marker proves only that the normalized `scopes` value was not
a list; it cannot distinguish absence, JSON null or another type. Twitch's
[validation documentation](https://dev.twitch.tv/docs/authentication/validate-tokens/)
describes a string array. An official-forum example reports null for an app
token, but that is not evidence of null semantics for this user token.
No raw response, identity or token was captured.

The narrow follow-up adds closed validation field-shape markers: OMITTED,
NULL, EMPTY, NONEMPTY and OTHER. They are emitted only after client/user/expiry
checks and retained in the native failure UI. Acceptance remains unchanged:
only an explicit empty array succeeds; a nonempty array is a permission
mismatch, and missing/null/other types fail schema validation. Tests cover
all five shapes, string/number/object rejection and suppression of the marker
when an earlier identity or expiry check fails. No permissions, provider
browser policy, login instrumentation or playback behavior changed.

The Docker build passed all 141 debug unit tests, lint with the same two
existing warnings, and assembly. `apksigner` matched the documented shared
debug certificate. The host-owned APK is 12,347,339 bytes with SHA-256
`419f8801bf17cfcd5260100d26c686a3007d0413bb7f26de990ff2ecd1b4015f`.
Scoped pre-commit/documentation checks and direct source/test Gitleaks scans
passed. Review against the task snapshot confirmed only closed diagnostics,
tests and the associated records changed, with no generated-file additions.
The validation field shape remains pending a new private authorization; the
previous transient token was discarded and cannot be revalidated.
Installation with `adb install -r` succeeded without clearing app data. The new
process recorded NEW_INTENT / TWITCH_DEVICE_AUTH / revision 1 and ON_RESUME,
confirming the updated native probe is ready for the user's private retry.

### Observed null validation scopes — 2026-10-05

The user's next private authorization on the same Pixel 6 / Android 17 returned
authorization-response HTTP 200. The closed grant-scope diagnostic was
`grantScope=OMITTED`, with VALIDATE HTTP 200 and
`validationScopes=NULL`, followed by the existing VALIDATION_SCOPES rejection.
Client ID, nonempty user identity and positive expiry had passed before this
marker. This establishes present JSON null, not an omitted field or an empty
array. Account page contents, raw response and token values were not inspected;
network region was not independently rechecked. This is a native OAuth test,
not a playback or WebView experiment.

First-party source was then inspected at Twitch CLI commit
`fd7dac646eea56c9ac9c1d36893e611de855c21e`:
[validation decoder](https://github.com/twitchdev/twitch-cli/blob/fd7dac646eea56c9ac9c1d36893e611de855c21e/internal/login/login.go)
uses Go's `[]string` and ordinary JSON unmarshal, while its
[token command](https://github.com/twitchdev/twitch-cli/blob/fd7dac646eea56c9ac9c1d36893e611de855c21e/cmd/token.go)
classifies user tokens by user ID and reports zero-length scopes as None for
either token type. [Go's decoder](https://pkg.go.dev/encoding/json#Unmarshal)
maps JSON null to a nil slice. Together with the actual zero-scope-request
trace, this supports interpreting present null as the no-scopes convention.
This is an evidence-backed experimental interpretation, not a new guarantee
in Twitch's array-based validation documentation.

The debug probe now accepts an explicit empty array or present null only after
the zero-scope grant and exact-client/user/lifetime checks. Missing, malformed
and nonempty validation scopes remain rejected. The fixed shape stays visible
after successful validation as well as failure; no response values are logged.
Tests cover the actual omitted-grant/null-validation sequence and rejection of
identity or expiry failures even when scopes are null. No new permission,
credential storage, session transfer, playback handoff or private API is added.
Actual completion with the updated parser remains pending a new authorization.

Docker verification passed all 142 debug unit tests, lint with the same two
existing warnings, and assembly. `apksigner` matched the shared debug
certificate. The host-owned APK is 12,347,339 bytes with SHA-256
`6f739c87b91081119f874443d904b53939a6a6b8e6827226b1a9e2679c333e97`.
Scoped pre-commit/documentation checks and direct source/test Gitleaks scans
passed. Snapshot and independent review confirmed the change is limited to
present-null validation handling, the experimental UI/shape label, regression
tests and associated documentation. Earlier prototype work is preserved;
no permissions, dependencies or generated files were added.
`adb install -r` succeeded without clearing app data. The new process recorded
NEW_INTENT / TWITCH_DEVICE_AUTH / revision 1 and ON_RESUME, confirming the
updated probe route is ready. The next authorization outcome remains pending.

### Own-client device OAuth completes — 2026-10-05

The next private authorization on the same Pixel 6 / Android 17 and APK
`6f739c87b91081119f874443d904b53939a6a6b8e6827226b1a9e2679c333e97`
recorded DEVICE HTTP 200, Brave handoff STARTED, ON_STOP / PAUSED, then
ON_RESUME / WAITING and authorization-response HTTP 200. The closed grant-scope
diagnostic was `grantScope=OMITTED`, with VALIDATE HTTP 200,
`validationScopes=NULL` and SUCCEEDED. The exact Tachiai client ID, nonempty
user identity and positive expiry checks passed. Present null was accepted
under the explicitly documented experimental no-scopes convention.

This is a bounded pass for Tachiai's own public-client device authorization
and foreground-return validation on this phone. The worker dropped token
references and closed the transport; no token was saved or handed to playback.
The agent inspected only closed native markers, not provider credentials,
account values or raw responses. External account state and region were not
independently inspected, and this native HTTP test does not exercise WebView
or any live/replay content. Authenticated embed playback, Turbo entitlements,
login persistence and a supported OAuth-to-playback connection remain open.
