# Requirements

## Presentation

- A presentation contains an ordered collection of stream panes rather than a
  fixed ABEMA pane and Twitch pane.
- The first useful release exposes two simultaneous panes while keeping the
  persisted and in-memory model capable of more than two.
- Each pane identifies a provider, a provider-specific resource locator, and a
  playback kind such as live, replay, or unknown.
- The viewer can choose a layout appropriate to the device. The first layouts
  are side-by-side landscape and one large primary pane with a smaller
  secondary pane.
- The viewer can swap the primary and secondary panes quickly without
  destroying or reloading either provider browser surface.
- Each pane has independent visible, muted, volume, and focus state where the
  underlying player supports those operations.
- Both panes can remain audible with independently adjustable levels where the
  underlying players support volume control. Muting either pane remains a
  convenient special case of that mix.
- The application provides an immediate way to leave focus mode and restore
  the complete provider page for login, consent, errors, account management,
  or channel selection.

## Playback sources

- The first supported ABEMA resource is its Grand Sumo live coverage.
- The first supported Twitch resource is the `midnightsumo` channel.
- The source model must accommodate live channels, provider-hosted replays or
  videos on demand, and mixtures of the two.
- A provider adapter advertises observed capabilities rather than the generic
  UI assuming every stream can seek, report time, pause behind live, return to
  live, set playback rate, or authenticate identically.
- Loss of an optional capability must produce a reduced but understandable UI,
  not a silent alignment failure.

## Alignment

- Manual alignment is part of the first useful two-feed experience, not a
  later automatic-synchronization enhancement.
- The viewer chooses a reference pane and adjusts another pane earlier or
  later relative to it.
- Initial live alignment may delay one stream by pausing it while the other
  continues, if that provider demonstrably resumes behind the live edge.
- The UI distinguishes a user-requested or accumulated offset from a measured
  media-clock offset.
- A reset action returns a source to its provider-defined live edge or reloads
  it when no supported live-edge control exists.
- Replay alignment should support relative seeking when both players expose
  positions and seeking.
- Mixed live/replay alignment should remain possible through manual anchors,
  even when automatic clock comparison is unavailable.
- Rebuffering, advertisements, player replacement, or navigation may invalidate
  alignment. The application must show that alignment is uncertain and allow
  quick correction.
- Automatic content recognition, audio fingerprinting, and frame analysis are
  deferred. DRM-protected output must not be captured to implement them.

## Sessions and login

- Anonymous playback is used when the provider permits it.
- A user signs in only on a provider-controlled page rendered by the platform
  browser component.
- Browser cookies and site data persist between launches unless the viewer
  chooses to clear them.
- The project does not collect, copy, synchronize, or log provider passwords,
  session cookies, media-license messages, or access tokens.
- Each installation authenticates independently. Session cookies are not moved
  between a phone, television, or desktop.
- Android TV should prefer ABEMA's supported ID and one-time-password account
  sharing flow when account state is needed.
- Twitch viewing should not require OAuth merely to play a public stream.
  Twitch OAuth is added only for a feature that requires Twitch API access.

## Platforms and distribution

- Android phone/tablet and Android TV are the first supported family.
- The Android UI is usable with touch, a D-pad remote, and common television
  aspect ratios.
- APK sideloading is an intended distribution method. Play Store publication
  is not a current goal.
- iPhone/iPad, Windows, macOS, and Linux are desired but must each pass an
  explicit playback and DRM spike before becoming supported.
- The project does not require a project-operated account service. A small
  static HTTPS document for the Twitch embed may be used if its `parent`
  requirement cannot be met from app-packaged content.

## Quality and safety

- Provider-specific selectors, scripts, and quirks are isolated behind adapters.
- Provider-page changes fail visibly and leave full-site mode available.
- Experiments record the device, OS, browser engine, region, authentication
  state, content kind, and exact observed outcome.
- Development and shared APKs follow the host's documented Android signing
  procedure. Signing material is never committed.
- No recording, downloading, rebroadcasting, DRM circumvention, or VPN bypass
  is part of the initial product.

## Initial non-goals

- Hosting or restreaming provider content.
- Circumventing geographic restrictions.
- Guaranteeing sub-second automatic synchronization.
- Hiding required provider controls or advertisements.
- Chat moderation, subscription management, or a general Twitch client.
- A project backend for user accounts or cross-device session synchronization.
