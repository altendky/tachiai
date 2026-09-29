# Implementation plan

The immediate goal is evidence, not a polished cross-platform product.

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

Hosted CI runs unit tests and lint but does not assemble a debug APK. An APK
must use the documented stable debug certificate, so APK assembly and
certificate verification remain in the controlled local/container workflow
until CI can receive that identity securely.

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

Do not begin private manifest or license integration merely to improve visual
polish. Reconsider it only when all of the following are recorded:

- an accepted requirement cannot be met through intact web playback;
- the failed capability is important enough to justify continuing maintenance;
- provider terms and the intended private distribution have been reviewed;
- a platform-specific prototype demonstrates a bounded implementation;
- failure and account-risk consequences are understood.
