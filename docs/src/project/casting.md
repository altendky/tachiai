# Casting: screen mirroring and TV applications

Reviewed 2026-10-17 against Tachiai revision
`f5d1aed5ec79809e73de78cb688ccb932033e100`. This records the user's product
direction and a documentation review. No mirroring session, TV installation,
remote-control integration or provider playback test was performed.

## Agreed product scope

Tachiai's useful television experiences preserve the complete presentation:
both feeds, layout/swap, audio mix, relative timing and recovery controls.
The user chose two models: **screen mirroring** from the running application,
and a **TV application** running the presentation itself. Handing a single
provider feed to a television is outside the primary requirements. A familiar
Cast/AirPlay button is not sufficient evidence of the intended experience.

This settles scope, not feasibility or an implementation order. Platform
interfaces below are **Documented** where upstream guidance exists; proposed
Tachiai fit is **Inferred**, and untested combinations are **Unknown**.

## Ownership in each model

| Responsibility | Screen mirroring | TV application |
| --- | --- | --- |
| Provider requests, account and DRM sessions | Remain on the source phone/computer in a true display-mirroring path; verify the system does not substitute media handoff | Established on the TV with valid entitlement and source/session binding |
| Selected provider routes | Source application owns provider traffic; local display discovery/transport is a separate network path | TV application must implement and verify its selected routes; the phone's Windscribe connection is not automatically inherited |
| Decode, composition, timing and audio mix | Source application runs both feeds; the display link must preserve the composed picture and audible mix | TV runs both feeds, applies timing plans and owns mix/layout/recovery |
| Controls and lifecycle | Source controls remain authoritative; lock/background, interruptions and display loss need validation | TV remote must cover all actions; phone control is an optional additional interface with pairing/reconnection work |

These are intended ownership boundaries. System mirroring may add an encoding
and transport stage; it does not eliminate source decode, thermal or battery
cost. Shared Rust timing rules can serve TV hosts, while capture/display APIs,
media sessions, route execution and input remain platform responsibilities.
See the [platform landscape](platforms.md).

## Android Chromecast implementation plan

[Issue #303](https://github.com/altendky/tachiai/issues/303) tracks this work:

1. [Setup entry, #304](https://github.com/altendky/tachiai/issues/304): the
   source picker's **More options → Screen sharing** opens compact guidance,
   the normal Google Home launcher or Android Cast settings. Start sharing,
   return to the picker, then tap Watch. This entry is included in ordinary
   builds independently of developer tools. Missing/restricted launch handlers
   leave guidance and the other action available.
2. [Physical acceptance record](chromecast-hardware-acceptance.md), consolidating
   #305 and dependent on the delivered #304: verify complete output, audio,
   routes and lifecycle on a named phone/Chromecast pair before claiming
   playback support. The user deferred hardware testing on 2026-10-18; this
   work remains in a draft PR until explicitly resumed.

The [controlled media fixture](controlled-mirroring-fixture.md) supplies two
locally generated, unprotected clips and a bounded test-APK-only viewer for
that preparation. Its local decoding/control checks do not establish receiver
output, acoustic mix or provider acceptance.

There is no current documented public Cast SDK entry established here for
starting Google's native screen mirroring from Tachiai. The deprecated Remote
Display API's support check always returns false. Android's public Cast settings
action opens configuration, takes no input and returns no connection result.
Launch success is not a Connected state. Google Home/Android owns device
selection, sharing consent, transport and stopping; no private activity names,
media-URL handoff or app-managed capture/encode service is introduced.
[Cast settings][cast-settings], [Remote Display status][remote-display]

The native viewer currently disposes playback on pause. Opening Home/settings
while playing would stop the feeds, so the initial workflow establishes sharing
before playback. It retains existing account/window protections, audio policy
and viewer-only keep-awake behavior. Seamless setup during playback and
background/locked-phone continuity remain unimplemented, not promises implied
by the new menu action. Ordinary viewer screens do not set Tachiai's secure
window flag; protected provider/account windows and independent DRM restrictions
remain separate. No new receiver acceptance was performed for the setup change.

## Mirroring candidates and limits

| Path | Documented availability | Tachiai fit and unknowns |
| --- | --- | --- |
| Android screen casting to Google Cast devices/TVs | Google Home documents Android screen/app mirroring on the same Wi-Fi network | **Unknown:** actual source/receiver compatibility, both video surfaces, mixed audio, quality, latency and lock/background behavior. A receiver being an Android TV does not by itself validate the chosen display path. [Android screen casting][android-cast] |
| Apple screen mirroring to Apple TV, compatible TVs or Macs | Apple documents Control Center screen mirroring separately from video streaming | **Unknown:** protected surfaces, audio and layout preservation. AirPlay video routing is a different mode: Apple's multiview guidance describes selecting one player for a constrained external video route, not sending the whole composition. [Screen mirroring][airplay], [Multiview routing][apple-multiview] |
| Chrome tab/screen casting | Chrome documents tab and whole-screen casting; whole-screen guidance names Mac, Windows and Chromebook | **Unknown:** desktop Tachiai/provider feasibility comes first. Tab audio and desktop audio differ: Google warns screen-cast audio may remain on the computer. Linux whole-screen parity is not established by that guidance. [Chrome casting][chrome-cast] |
| Miracast wireless display | Microsoft documents Windows wireless projection | **Unknown:** exact sender/receiver hardware, driver, protected-output and audio compatibility. Do not assume Android phones, Google Cast devices and AirPlay receivers implement Miracast interchangeably. [Miracast][miracast] |
| Wired external display | Useful comparison/control path for a named source device, adapter and TV | **Unknown:** supported display mode, composition, audio and protected output. Treat this as display validation, not proof of any wireless protocol. |

An Android secure window can be excluded from non-secure displays. Protected
surfaces also require appropriate secure output; missing support can produce
blank video. Tachiai's ordinary security policy and local inspection exception
must be evaluated separately. A successful inspect-build mirror would not prove
ordinary-build support or authorize relaxing its protections. This research
does not introduce app-managed capture, re-encoding or DRM bypasses.
[Secure windows][secure-window], [Secure display capability][secure-display],
[Existing security boundaries](security-and-privacy.md)

## TV applications and adjacent ecosystems

The [Android TV/Google TV review](android-tv-interface-review.md) and
[Apple TV/tvOS review](tvos-interface-review.md) own their library, account,
DRM, routing and remote-navigation assessments. Android TV has the closest
implementation reuse; tvOS's missing embedded WebView remains an ABEMA helper
gap. Both need actual two-feed hardware trials. Neither requires single-feed
handoff to provide a useful Tachiai experience.

Fire TV, Roku, Samsung/LG native TV platforms and a desktop/browser application
on an HDMI-connected computer are further candidates for full-presentation
hosts. They remain separate investigations, not implied support. Generic
media-URL receivers and DLNA-style handoff do not satisfy the primary scope.
Independent installation and update requirements remain in the
[distribution comparison](platform-distribution.md).

Optional phone control should send source selections and presentation commands
to a TV-owned session. Pairing, authentication, state readback, command ordering,
stale controllers and reconnect behavior need a designed protocol. Do not copy
provider cookies, grants or CDM state from the phone as an installation shortcut.
Whether the TV continues after controller loss must be specified and tested.

## First validation steps

1. **Inventory a named path:** source device/OS/build variant, receiver
   model/firmware, protocol, network topology, display/audio chain and supported
   secure output. Verify discovery with the selected provider route active;
   local-network reachability and provider exit selection are separate checks.
2. **Controlled mirroring trial:** two permitted test videos with distinct audio
   cues and visual clocks. Require both panes, swap/layout, both audible tracks,
   mute/fade and timing controls on the TV. Record resolution, frame drops,
   interaction delay, A/V delay, relative timing drift, thermals and battery.
   Agree acceptable measured thresholds before claiming a quality pass.
3. **Lifecycle trial:** test rotation, panel use, notifications, lock, Home,
   interruption, network loss, disconnect and reconnect. Record whether video
   freezes, audio splits between devices or output is blank. Declare supported
   lifecycle conditions explicitly; source-independent continuation is not
   assumed for mirroring. Prefer app-only sharing where available and assess
   credential/notification exposure before mirroring account pages.
4. **Authorized provider trial:** repeat on normal builds with selected
   live/replay combinations, valid local accounts and intended routes. Require
   two-feed output without unapproved security changes. Record engine/player,
   region, account state and content identity; retain no secrets or protected
   recordings. Controlled samples alone do not establish provider support.
5. **TV-app trial:** follow the existing platform roadmap, then validate complete
   remote operation and, if implemented, phone pairing/reconnect and controller
   loss. Require TV-local provider authorization and complete route coverage,
   plus an in-place installation/update rehearsal.

Keep outcomes per device and mode: discovery success, controlled composition,
provider playback, ordinary-build security compatibility, audio/timing quality
and lifecycle behavior are separate milestones. A single-feed Cast success or
a screenshot of settings earns none of the two-feed playback milestones.

[android-cast]: https://support.google.com/chromecast/answer/6059461
[airplay]: https://support.apple.com/en-us/102661
[apple-multiview]: https://developer.apple.com/videos/play/wwdc2025/302/
[chrome-cast]: https://support.google.com/chromecast/answer/3228332
[miracast]: https://learn.microsoft.com/en-us/windows-hardware/design/device-experiences/wireless-projection-implementing-over-miracast
[secure-window]: https://developer.android.com/security/fraud-prevention/activities
[secure-display]: https://developer.android.com/reference/android/view/Display#FLAG_SECURE
[cast-settings]: https://developer.android.com/reference/android/provider/Settings#ACTION_CAST_SETTINGS
[remote-display]: https://developers.google.com/android/reference/com/google/android/gms/cast/CastRemoteDisplay
