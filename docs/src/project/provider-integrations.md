# Provider integrations

## ABEMA

ABEMA is the first primary-picture provider. The initial experiment loads the
ordinary ABEMA site as the top-level page of an Android WebView. It must not be
placed in an iframe, because Tachiai should not depend on ABEMA permitting
third-party framing.

ABEMA currently documents Android Chrome and current Android WebView as
supported environments. It also documents that some content is unavailable in
phone and tablet browsers and must be watched in its native app. The available
official material reviewed for this spike did not expose a public iframe,
JavaScript player SDK, Android playback SDK, or playback API comparable to
Twitch's embed. This is evidence about public documentation, not proof that
private partner integrations do not exist.

ABEMA documents its linear television channels as free, although program and
regional availability still vary. Account-free viewing should therefore be
attempted first. A desktop user-agent override on Android is not a documented
supported environment. The current two-feed spike selects it only because it
produced bounded replay playback on the tested device; this does not make it a
supported or durable integration.

When an account is needed, a future default-identity full-site mode should let
the user complete ABEMA's own account flow. The current desktop-identity spike
allows playback routes only and sends account navigation outside its pane. On a
television, prefer ABEMA's documented device sharing mechanism using an account
ID and a short-lived one-time password. Tachiai must not copy an authenticated
cookie jar from another device.

The first focus-mode adapter should:

1. Identify the visible ABEMA player container on an allowlisted playback URL.
2. Preserve that container and its descendants in the original document.
3. Hide or move unrelated page chrome without hiding advertisements or required
   player controls.
4. restore the full page immediately on request or adapter failure.
5. Observe player replacement and reacquire the active media element.
6. Report whether play, pause, mute, position, seek, live-edge, and rate
   operations actually work.

Do not assume that calling `pause()` on a live ABEMA media element preserves a
delayed position. That is a first-spike measurement.

The installed ABEMA app successfully played one public replay and entered
Android picture-in-picture on the tested Pixel 6. That surface remains owned by
ABEMA and the system rather than Tachiai. Audio-focus tests alternately paused
ABEMA or Twitch when the other player resumed, and Tachiai cannot size, swap,
align, or independently mix the PiP surface. Native app handoff remains useful
for comparison testing but is not the current product integration candidate.

On the tested free replay, Chrome's anonymous mobile page explicitly required
the native app for full viewing. Tachiai's default WebView and a mobile
Chrome-like identity loaded the first-party episode shell but exposed no
visible video. A Windows Chrome-like identity selected ABEMA's desktop
application and initialized Widevine, but an optional demographic survey and
an unsuitable clipped desktop layout initially obscured the result. After a
clean Tachiai-only browser profile and the survey's “Later” action, the original
video element visibly played through Tachiai's generic Play command. Raising
the initially zero media-element volume created an Android audio track, although
system media volume remained zero and no acoustic or mixed-audio result was
measured. None of these observations indicates that an ABEMA account is
required for the replay.

## Twitch

Twitch is the first commentary provider. Prefer Twitch's supported embed or
ordinary top-level web player rather than deriving its private HLS playback
URL.

The first resource is the `midnightsumo` channel. Public viewing should work
without Twitch OAuth. If the product later includes chat, following, or
subscription features, Twitch's full interactive embed provides its own login
experience.

Twitch currently requires embedded experiences to use HTTPS, include a valid
`parent` domain, retain approved player elements, and meet minimum dimensions.
Mobile playback also requires user interaction. The spike must determine
whether a page delivered through Android WebView asset loading can satisfy the
`parent` requirement. The fallback is a minimal static HTTPS page on a
controlled origin; it does not need a user-account backend.

On one Pixel 6 run, the packaged official embed reported a live channel but
did not begin visible playback. Twitch's ordinary top-level mobile channel page
did play its original media element. An exact-selected-channel adapter then
expanded the original player subtree to the browser viewport. That route is the
current provisional Android candidate, but provider-original controls,
advertisements, lifecycle recovery, other WebView versions, and Android TV
remain unverified. This result does not justify private stream extraction or
presenting the observed DOM class fragments as a supported Twitch interface.

In a later bounded run on the same device, Tachiai's generic controls
reacquired Twitch's original visible media element on every command. Play,
pause, mute, sound, and volume changes behaved independently in two
same-channel panes, and the keyed panes swapped size without recreating their
browser sessions. Twitch initially reset plain media-property changes to muted
and zero volume; dispatching the standard `volumechange` event made the setting
stick, and the rebuilt adapter's Play and Sound controls produced user-confirmed
audible output from `arcajazz`. A mixed-provider follow-up also showed ABEMA and
Twitch video advancing concurrently. Their separate Chromium audio-focus
delegates paused one another when either pane started audible playback, so the
current two-WebView design fails the mixed-audio requirement on this device.
The adapter also narrowly recognizes Twitch's exact selected-channel
“Open in App / Keep using web” prompt and selects the web button; login and
legal prompts are outside that behavior. A cold `arcajazz` launch verified that
behavior in the rebuilt APK on the tested Pixel 6. Twitch's own transport
controls and long-duration behavior remain unverified.

A subsequent debug-only topology kept ABEMA top-level and inserted Twitch's
official `player.twitch.tv` player as a cross-origin child frame in the same
WebView. Twitch muted autoplay visibly advanced for live channel
`izgonnabemei`. After the user tapped ABEMA's own visible unmute overlay, both
videos continued and the user confirmed both audio tracks were audible.
Android reported one Tachiai Chromium audio-focus owner, rather than the two
competing delegates seen with separate WebViews. The native ABEMA Sound command
did not dismiss that provider overlay, and native code cannot directly control
the cross-origin Twitch frame. This is a promising hosting result, not a public
ABEMA integration contract or evidence that the arrangement will survive
provider changes.

Twitch's documented player API supports play and pause for live video, but
live seeking and live current-time reporting are not available. Tachiai must
not present Twitch live offset as measured merely because it timed a pause.
Replay/VOD embeds provide more useful seeking and position controls.

## Generic web media

A later generic adapter may support a user-supplied page containing an HTML
media element. It must begin read-only, disclose which capabilities were
detected, and avoid arbitrary native bridges. General URL entry materially
expands the navigation and security surface and is not required for the first
ABEMA/Twitch spike.

## Native extraction

An actively maintained Streamlink plugin demonstrates that ABEMA private APIs
and license handling have been reverse engineered. This is evidence of
technical possibility, not a selected architecture or a promise of current
reliability.

Native extraction would improve timeline visibility and alignment control, but
would also couple Tachiai to undocumented authentication, playback-token, and
license behavior. It remains deferred until intact provider playback fails a
specific recorded requirement.
