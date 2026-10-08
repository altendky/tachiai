# Implementation plan

The immediate goal is evidence, not a polished cross-platform product.

## Current handoff — 2026-10-08

The debug application now preserves the historical experiment home and offers
a separate [Prototype source-picker/native-viewer flow](prototype-ux.md).
Six fixed ABEMA/Twitch live/replay entries can fill either slot, including
duplicate selections. Twitch live offers Izgonnabemei, Chillhop Radio and Virtual
Japan. Opening the viewer explicitly starts automatic bounded preparation in
two labelled panes. Each feed independently becomes video or a safe error
message; a successful feed can play while the other prepares or fails. Sources
returns to the picker with the choices retained. Its cached ABEMA path no longer
requires the full provider page or its consent overlay; the old web-page startup
remains a comparison.

The agreed UX is portrait stacking without forced equal heights, landscape
primary plus movable floating secondary with tap-to-swap, joint Play/Pause,
overall volume, relative mix with fine-adjust arrows, per-feed mute and named
relative timing controls. Individual transport is not primary. Capabilities
are optional; exact curves/defaults and control sizing/timers remain
provisional. See [the preliminary viewer](preliminary-native-viewer.md).

Earlier bounded native pair runs have user-confirmed mixed audio and tested
advance/delay primitives. The latest cached ABEMA duplicate News and fixed free
sumo-replay runs rendered native video without a full page, but have no fresh
acoustic confirmation. Neither result proves every source combination,
sustained alignment or supported provider operation. Conditions and detailed
results remain in [native-access experiments](native-access-experiments.md).

The approved cached runtime uses verified public bundles downloaded outside the
APK, fresh anonymous guest/source setup, unchanged provider selectors/helper
and one initial opaque exchange with a fresh native CDM. Conservative free
metadata, exact source/declared-file checks and explicit
[media-origin approvals](media-origin-approvals.md) remain in force. There is no
password collection, helper-algorithm copy, key dump, response reuse or renewal.
The direct-manifest prototype does not implement a complete provider client-ad
or tracking lifecycle; MediaTailor resolution and unknown modes remain blocked.
These are debug experiments, not release/provider-support decisions. Follow the
[security boundaries](security-and-privacy.md) before changing their scope.

### Verification handoff

The separate handoff/tooling change adds the omitted offline EME JavaScript
observer fixture tests and two Python collector self-tests to local hooks/CI,
plus synthetic tests for the release-isolation checker. Android CI also
run `compileDebugAndroidTestKotlin`; this compiles instrumentation sources but
does **not** execute device tests.

The `verifyReleaseIsolation` smoke check inspects the merged release manifest
for components declared in the debug/diagnostic manifests, and assets for the
current `abema/` and `browser-lab/` roots. It is not a release APK or bytecode
audit: guarded main-source Twitch diagnostic code remains present. Future
prototype asset roots must be added to this check explicitly.
CI push and manual runs now restore the shared debug signing key from the
`ANDROID_DEBUG_KEYSTORE_BASE64` Actions secret, assemble the debug APK and verify
its signer with `apksigner` against the documented identity before publishing a
`tachiai-debug-<commit>` artifact. The restored key is removed even if the build
fails. Pull-request runs retain the checks without receiving the key or building
an APK. The README documents downloading and installing the artifact.

Local verification on October 7 passed all repository hooks, including the
secrets scan, offline fixtures and documentation build. The controlled Android
build passed `test lint :app:compileDebugAndroidTestKotlin
:app:verifyReleaseIsolation assembleDebug`; the debug APK certificate matched
the shared signing procedure. The CI-only task graph contained no APK packaging
or signing tasks. Existing compiler/lint/Gradle deprecation warnings remain;
no new phone playback or instrumentation execution is claimed by this tidy.

### Remaining integration work

Continue bounded source-pair, mixed-audio, lifecycle and relative-timing checks,
including live-window expiry and rebuffer/recovery behavior. Supported provider
auth/entitlement, advertising, durable runtime dependencies/flags and any
long-running licensing require separate review; the current prototype does not
settle them. Broader source selection, presentation persistence and Android TV
input/playback are still product work. Do not introduce shared Rust/KMP merely
to tidy the prototype.

The phase plan and chronology below preserve the original web-first work and
comparison results. Statements about the then-current candidate are historical,
not instructions to replace the native Prototype default.

## Phase 0: repository foundation

- Initialize the Git repository with `main` as the default branch.
- Add the Android project under `apps/android/` using Kotlin and Compose.
- Add mise, pre-commit, Renovate, markdown, and CI configuration consistent
  with current sibling repositories where it serves this project.
- Select the Android application ID and project license.
- Use the documented shared Android debug signing identity for every
  development or debug APK.

### Phase 0 scaffold status

The local repository is initialized on `main`. The initial scaffold uses one
Kotlin/Compose application module under `apps/android/`, application ID
`net.fstab.tachiai`, and the dual MIT/Apache-2.0 license. It keeps generic
presentation state, Android browser hosting, and provider adapters in separate
packages.

The scaffold is not playback evidence. Its ABEMA route, Twitch packaged-page
origin, protected-media origins, cookie behavior, and mobile/TV layout all
remain experimental assumptions recorded in the
[Android playback spike](android-playback-spike.md).

Hosted CI runs unit tests and lint. Push and manual runs also assemble a debug
APK using the documented stable debug certificate supplied through the Actions
secret and verify the resulting APK before upload. Controlled local/container
builds continue to require that same identity and certificate verification.

Repository automation follows the shared Carl and Onshape MCP pattern.
Renovate runs under the narrowly scoped `altendky-renovate` GitHub App and
updates the Gradle version catalog and wrapper, GitHub Actions, mise tools and
lock data, and pre-commit hooks. Mergify admits only explicitly `enqueue`-labeled
non-draft pull requests targeting `main`; Renovate approval does not enqueue a
change. The queue relies on GitHub's protected aggregate `all` check. These
behaviors require the corresponding GitHub Apps, repository credential names,
label, and ruleset and must not be described as operational until a manual
Renovate run and a queued pull request have succeeded.

On 2026-09-27, the pinned Android tools container completed `test`, `lint`, and
`assembleDebug` against compile SDK 37.2. The resulting APK passed
`apksigner verify` and matched the documented shared debug certificate. This
validates the scaffold and signing workflow only; it is not playback or device
evidence.

The same day's device spike found that the packaged official Twitch embed did
not visibly start, while Twitch's original top-level mobile player did play in
Tachiai on a Pixel 6. A route-guarded provider adapter can expand that original
player to the browser surface without extracting or replacing its media
element. This is one-device feasibility evidence only; the remaining Phase 1
criteria still require concurrent ABEMA playback, audio control, lifecycle and
recovery checks, sustained playback, and Android TV testing.

A follow-up on the same Pixel 6 exercised native commands against Twitch's
original media element and two independent top-level Twitch browser surfaces.
Pause/play frame comparisons, independent mute state, concurrent decode, a
60/40 primary/secondary layout, and keyed session-preserving Swap all worked in
the bounded diagnostic. This advances the browser-hosting and presentation
tooling, but does not satisfy Phase 1: actual acoustic mixing, ABEMA/Twitch
concurrency, sustained playback, delayed-live retention, advertisement
durability, and Android TV remain to be tested.

An ABEMA follow-up used a known-current free replay and added a debug-only
single-pane matrix for default WebView, mobile Chrome-like, and desktop
Chrome-like identities. The native ABEMA app played the replay and entered
PiP, but Android audio focus caused ABEMA and Twitch to pause one another; the
separate PiP surface also cannot satisfy Tachiai's sizing, swap, alignment, or
mixing requirements. A later same-URL control rendered ABEMA's region rejection
in Chrome and every WebView identity, so the in-process comparison remains
blocked until Chrome first passes on a stable accepted network path. No private
stream, credential, or DRM work was introduced.

The same Chrome control later passed after the user changed VPN exits. Default
and mobile Chrome-like WebViews then loaded ABEMA's anonymous episode shell but
never exposed a visible player. A Windows Chrome-like identity reached ABEMA's
desktop application and initialized Widevine, but an optional demographic
survey initially obscured the episode. After an explicitly approved reset of
Tachiai's app-private data and selection of the survey's “Later” action, the
original ABEMA video element visibly played the replay in WebView. Unmuting and
raising its initially zero element volume created an Android audio track, while
the phone's system media volume remained zero. The free replay did not present
a login requirement. The normal two-feed spike now selects this experimental
desktop identity and the known replay alongside Twitch's proven top-level
mobile player. Because ABEMA documents VPN use as unsupported, these results
classify implementation behavior but do not settle supported regional
operation, mixed-provider concurrency, or acoustic mixing.

The two-feed follow-up then confirmed simultaneous ABEMA and Twitch video in
Tachiai and audible output from each provider individually. Twitch required a
standard `volumechange` event in addition to media-property changes; a rebuilt
adapter and APK made Tachiai's Play and Sound controls produce retained,
audible Twitch playback. Mixed output still failed: each stock WebView created
its own Chromium audio-focus delegate, and the later full-gain request paused
the earlier provider in either order. The separate-browser-surface choice is
therefore still provisional and does not yet meet the product's mixed-audio
requirement on the tested Pixel 6.

A controlled same-WebView follow-up first played two generated audio elements
inside one app-owned document. Android reported one Chromium audio-focus
delegate and one active Tachiai output while both elements remained playing.
A second debug-only probe then kept ABEMA's original replay page top-level and
inserted Twitch's official `player.twitch.tv` embed as a child frame. With live
channel `izgonnabemei`, muted Twitch autoplay, and a user tap on ABEMA's own
unmute overlay, both original provider videos continued advancing and the user
confirmed both audio tracks were audible. Android reported one Tachiai
Chromium audio-focus owner. This is a bounded pass for mixed audio on the tested
Pixel 6 and makes a single WebContents the leading Android topology to
investigate. It is not evidence of ABEMA approval, a durable integration,
independent volume control, live-sumo behavior, sustained playback, or Android
TV support. The composition remains debug-only while those questions are open.

## Phase 1: Android playback spike

The 2026-10-03 independent-control follow-up remains in the debug-only
single-WebContents probe. It adds a narrow SDK wrapper for Twitch, per-pane
native commands and status, bounded acknowledgment polling, and strict child
asset/message policies. Eleven JavaScript protocol checks and forty Android unit
tests passed, as did Android lint and debug APK assembly in the pinned tools
container using SDK 37.2. `apksigner` matched the shared debug certificate, and
the APK updated the Pixel 6 installation without clearing its private browser
profile. A subsequent bounded Pixel 6 experiment rendered both original
players, exercised native play/pause, and obtained user-confirmed mixed audio
with the added SDK wrapper. Provider first-use audio activation and independent
native mute/volume behavior remain unresolved; see the
[recorded conditions and limits](android-playback-spike.md).

Build the smallest application that can answer the blocking questions:

1. Host ABEMA as the top-level document in an Android WebView and navigate to
   the active Grand Sumo page.
2. Host an official Twitch embed for `midnightsumo` as a child frame in that
   same WebView, preserving both original provider players. Retain the
   separate top-level Twitch page only as a diagnostic comparison.
3. Require explicit user interaction to start both players.
4. Independently mute and play/pause the panes.
5. Preserve browser site data across process restarts.
6. Exercise anonymous ABEMA viewing, then an account flow if the chosen content
   requires it.
7. Run on an Android phone and the NVIDIA Shield.

For each result, record device model, Android version, WebView version, network
region, URL/resource, login state, resolutions, and observed errors.

### Spike success criteria

- Both first-use streams render concurrently for at least thirty minutes.
- One stream can supply audio while the other remains muted.
- Leaving and returning to the application has defined, recoverable behavior.
- A later launch retains provider login where the provider permits it.
- No credentials or session-bearing URLs appear in application logs.
- Failure of either pane leaves the other usable and exposes a recovery action.

If ABEMA fails, distinguish unsupported WebView/DRM, region/content policy,
authentication, user-agent handling, and ordinary page error before changing
architecture.

## Phase 2: focus mode and manual live alignment

- Create the provider adapter interface and ABEMA adapter.
- Retain the original ABEMA player container and hide unrelated page chrome.
- Add full-site/focus-mode switching and visible adapter diagnostics.
- Determine whether pause/resume preserves a behind-live position and for how
  long.
- Add explicit hold controls such as 250 ms, 500 ms, 1 s, and 5 s.
- Display accumulated requested delay and an uncertainty indicator.
- Add **Matta!** as a possible label for the immediate hold/realign action,
  while retaining an accessible descriptive label.
- Add return-to-live or reload recovery.

### Alignment experiment

Before capability-aware relative actions, isolate forward and backward for
each of Twitch/ABEMA and live/recorded. The
[timing capability matrix](timing-capability-matrix.md) records the first
eight-action classification and live hold-retention tests. Recording seeks
worked in both directions; ABEMA live exposed bounded seekable media and
retained delay, but needs moving-program confirmation. Twitch live seeking
is unsupported by its official SDK and useful delay retention remains
unverified. Do not build relative logic that assumes all four cases work.

Before timing different providers, use the debug-only same-Twitch-VOD page to
exercise independent forward seeks and sampled replay offsets. Its app-page
controls call Twitch's documented SDK directly; they do not extend generic
presentation commands with provider-specific seeking. Record measured device
results and uncertainty in the [playback spike](android-playback-spike.md)
before treating subsecond adjustments as usable product behavior.

The first Pixel 6 test matched all three paused step sizes on both copies and
the user heard delayed speech after original Twitch speaker interaction.
Running seeks showed that rebuffer latency can change the final offset by more
than the requested increment. The next timing-tooling experiment should compare
explicit paired hold/seek/resume with observed post-seek correction before
promoting a simple running relative seek into a generic alignment control.
The debug-only replay diagnostic now implements the bounded paired-hold
experiment through provider-independent packaged JavaScript and Twitch SDK
adapters, with relative Forward/Backward and explicit sampled lead/lag. A
bounded Pixel 6 test matched held targets and resumed both videos, but running
offsets still missed requested targets by roughly a second. Keeping both
players visible with a compact toolbar made app-page Play work where the large
header/scroll layout did not. Accurate running correction, restoration
acknowledgment, and smaller-viewport behavior remain unresolved; post-seek
automatic correction is not implemented.

Use the ABEMA Grand Sumo and `midnightsumo` pairing to record:

- which feed is normally earlier and the observed offset range;
- whether the offset changes across thirty minutes;
- behavior across each provider's advertisements;
- behavior after network rebuffering;
- the smallest perceptible and reliably controllable adjustment;
- whether live pause retains content or resumes at the live edge.

## Phase 3: generic presentation and replay

- Replace hard-coded panes with a presentation containing pane specifications.
- Add saved presentations without storing browser sessions.
- Add generic capability-derived controls.
- Test a Twitch VOD and an ABEMA replay/video.
- Implement replay position observation and seeking where supported.
- Add a manual event-anchor workflow for live/replay and replay/replay pairs.
- Add tests for capability reduction, offset state, layout state, and recovery.

## Phase 4: desktop experiment

Prototype the provider adapters in supported desktop browsers. First determine
whether an extension can reshape ABEMA and host or tile the Twitch player
without being blocked by the page's content-security policy. Compare that with
a native wrapper only after the browser experiment.

## Phase 5: Apple experiment

Test ABEMA and Twitch independently in WKWebView and Safari before selecting an
iOS shell. Treat application distribution and playback capability as separate
go/no-go decisions.

## Native extraction decision gate

The following chronology begins with the original access-only cases; the
current approved debug native implementation and its limits are summarized in
the handoff above. These early cases are preserved, not expanded retroactively.

The user approved an additive native-playback access investigation on
2026-10-05, prompted by live alignment and authenticated playback limitations.
The separate [access cases](native-access-experiments.md) test anonymous
metadata/manifest access and Tachiai's own Twitch client, optionally after a
fresh validated device grant. They do not resolve playlists, request ABEMA
keys/licenses, or implement a native player. Keep prior browser and OAuth-only
examples available for comparison. The gates below still apply before a real
private playback integration is selected.

The user subsequently requested retaining validated own-client tokens. An
additional encrypted save case and explicit saved live/replay cases keep the
fresh/discard experiments intact. Official validation precedes each reuse;
local Forget affects only the saved slot. This is credential reuse for bounded
access probes, not a playback implementation or active persistent OAuth session.

After the own-token blank-header comparison also failed, the user explicitly
approved a separate provider-client device-login experiment. Additive
authorize/save, saved live and saved replay cases use a fixed observed public
provider identity and a separate encrypted token slot. They preserve all earlier
examples, independently implement the protocol pattern without Kodi code, and
still stop at bounded access-field presence. This approval does not settle
provider support, terms, Turbo, native playback or the integration gate below.

The provider-web DEVICE request was rejected before challenge issuance. The user
then approved researching currently viable identities and testing on the phone.
An additional fixed Smart TV profile preserves both earlier profiles, with its
own encrypted slot and three authorize/save/live/replay examples. The narrow
comparison changes identity only; it does not relax scopes, request headers,
activation checks or token validation. Equivalent-client reports justify a
bounded test, not a supported product authentication direction.

Private approval produced TOKEN HTTP 200, but the strict grant-lifetime check
rejected it before validation/save. An additive fixed Smart TV lifetime-inspection
case records expiry shapes and validates within a maximum 30-second local
acceptance budget. This non-persistent, callback-free case can inspect
omitted/zero grant expiry and integer-zero validation expiry. It does not change
strict saved examples, establish permanent validity, or fetch playback access.
The inspection subsequently passed official validation on the Pixel 6: grant
expiry was omitted, validation expiry was zero, and the exact selected identity,
user and experimental null/no-scopes checks passed. Its token was discarded.
The user then approved additive local-save/live/replay cases with a chosen
one-hour local retention cap, shorter for known expiry, in a fourth encrypted
slot and profile-bound record. Official validation precedes save and every use;
each use is capped at 30 seconds and remaining retention, without record renewal.
This wall-clock-dependent experiment does not establish provider expiry or
permission. Strict cases remain intact. On the Pixel 6, local save succeeded and
two explicit saved uses each passed fresh official validation: both live and
replay access returned HTTP 200 with no reported errors and expected access
fields present. Their signature/value were discarded; no playlist or media was
requested. Actual playback, Turbo, process-restart reuse and provider permission
remain unverified; access success alone does not settle the integration gate.

The user then approved an additive single-stream native playback prototype.
The new Twitch native live/replay cases resolve a signed playlist and use a
bounded Media3 host; prior examples remain intact and ABEMA is unchanged.
The requirement motivating this test is useful retained live alignment plus
authenticated playback, not visual polish. The public Twitch registration
guidance was reviewed: sharing application client IDs may lead to API-access
suspension. The current legal agreement pages returned footer-only content
during retrieval, so a complete current terms review is not established.
Private personal debug testing is the intended scope, not distribution or a
supported integration selection. Access/client/account failure, changing private
contracts, CDN-policy rejection and decoder failure remain risks. This prototype
is a bounded evidence-gathering step; it does not settle the full integration
gate or authorize ABEMA license/key work. See the
[playback boundaries](native-access-experiments.md#additive-native-twitch-playback-prototype).

On 2026-10-06, fresh approval renewed the locally expired grant. The native
RelaxBeats live case then fetched/parsing playlists and media, rendered video,
and played user-confirmed audible music; its two-minute budget subsequently
released/stopped it. Recorded access and initial playlist parsing passed, but
the strict policy blocked the advertised CloudFront distribution. An additional
exact-observed-CDN replay comparison preserves that strict case and subsequently
fetched media, rendered the RLCS replay introduction and played user-confirmed
audio. Its two-minute timeout and explicit Stop cleared the player; an explicit
restart revalidated and played again. These bounded single-stream live/replay
results also observed background/return remaining stopped without auto-resume.
They establish neither alignment primitives,
dual-stream mixing, Turbo nor a supported authentication/integration decision.

A subsequent additive native timing comparison observed replay backward/forward
five-second seeks with settled clock and match-frame changes. Native Twitch live
also moved its media clock in both directions within an advertised 30-second
window. A bounded pause/resume retained about 37.5 seconds of added live delay,
and explicit default-live catch-up returned to playing. These primitives are
Media3 observations, not capabilities of the earlier official Twitch embed.
The old buffered point can leave the advertised seek window: retention is not
an unlimited DVR/seek guarantee. Rebuffering altered running offsets, and
maximum history, moving-event live confirmation, transitions, simultaneous
mixing and precise relative control remain untested. See the
[recorded conditions](native-access-experiments.md#corrected-native-live-timing-results--2026-10-06).

The next additive native replay-pair case compares two copies of the same
moving replay under one Android audio-focus owner and unchanged shared budget.
It adds independent volume, signed sampled A−B offsets, Sync and paired
hold/seek/READY-check restoration. Build/device evidence must be recorded in
the [comparison notes](native-access-experiments.md#additive-two-native-replay-comparison--2026-10-06)
before claiming mixed output or reliable running alignment. Earlier examples
remain available; live pairs and ABEMA native playback are not added here.

A bounded Pixel 6 pair run subsequently rendered both native replay copies,
obtained user-confirmed doubled audio and restored a running +1-second relative
transaction near its requested offset. A negative running transaction had about
178 ms target error; Sync converged sampled clocks near zero and background/
return remained stopped. External focus behavior and sustained precision still
need device checks. The user then
requested longer saved-login retention only: seven-day LOCAL saves and a
separate explicit fresh-validation extension, with ordinary starts nonrenewing
and playback still capped at two minutes. See the
[retention follow-up](native-access-experiments.md#seven-day-local-retention-follow-up--2026-10-06).
The explicit extension passed fresh validation/save on the Pixel 6, and a cold
restart reused it to prepare both copies without another private approval.

The user then deferred per-feed routing and enabled system Proton Japan for
ABEMA native feasibility checks. The unchanged News DASH access case returned
HTTP 200 with protection markers, unlike the earlier unverified-network 403.
Three appended format comparisons inspect DASH Widevine hints, the advertised
HLS master and at most one allowlisted advertised child playlist. They request
no media segments, keys or licenses and do not add ABEMA native playback.
The HLS comparison found ABEMA-specific key signaling; refined DASH inspection
found common encryption without a recognized DRM-system marker. An authorized
standard DRM/license configuration remains the native playback gate, not
manifest access or generic player plumbing. The final comparison APK passed
625 unit tests, lint, assembly and the shared signing check before the phone's
refined DASH run. No per-feed routing was added.
See [conditions and assumptions](native-access-experiments.md#abema-native-format-comparison--2026-10-06).

Do not begin private manifest or license integration merely to improve visual
polish. Reconsider it only when all of the following are recorded:

- an accepted requirement cannot be met through intact web playback;
- the failed capability is important enough to justify continuing maintenance;
- provider terms and the intended private distribution have been reviewed;
- a platform-specific prototype demonstrates a bounded implementation;
- failure and account-risk consequences are understood.
