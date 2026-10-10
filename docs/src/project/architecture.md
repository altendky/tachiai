# Architecture

Tachiai separates generic presentation behavior from platform playback/browser
hosting and provider-specific source, authentication and page knowledge.

```text
native application shell
  -> presentation coordinator
       -> pane state and layout
       -> alignment requests
       -> capability-derived controls
  -> provider adapter per pane
       -> source preparation and bounded authentication/licensing
       -> supported playback commands and observations
  -> platform playback host
       -> native media lifecycle and optional timing capabilities
       -> or browser page, original media/DRM, cookies and login
```

## Presentation model

A presentation is a list of pane specifications plus presentation-wide layout
state. A pane should be representable without requiring a provider to expose a
raw media URL.

Conceptually, each pane contains:

```text
id
provider kind
resource locator
live / replay / unknown kind
visible and focus state
audio policy
requested relative offset
observed capability set
runtime health and alignment confidence
```

The presentation coordinator decides which controls make sense from the
capability set. It does not reach into ABEMA or Twitch page structure.

## Provider adapter

The [configured-catalog foundation](configured-catalog.md) adds a separate,
instance-bound discovery contract and persistent public resource identities.
Catalog/account capabilities feed the shared management UI; provider-specific
identifiers and response parsing stay in adapters. This boundary complements the
browser adapter and native session/control interfaces. It does not imply that
provider catalog authorization grants native playback access.

A provider adapter translates a small generic command and observation surface
into native-source or provider-page behavior. Likely capabilities include:

- ready and currently playing
- play and pause
- muted and volume
- current position
- duration
- seek absolute or relative
- live-edge position or return to live
- playback-rate adjustment
- full-site and focus modes
- authentication or consent required
- player replaced or navigation occurred

Every capability is optional. Unsupported and not-yet-measured are distinct
states.

Historical browser adapters may use injected CSS and JavaScript on allowlisted playback
pages. They should keep the provider's original player subtree attached to its
document. Moving or cloning the media element is deferred because it can break
site state, advertisements, fullscreen behavior, or DRM.

A browser adapter must reacquire the visible media element after single-page-app
navigation, advertisements, or player replacement. A DOM observer may help,
but selectors and recovery behavior remain provider-specific.

## Alignment strategies

Alignment is capability-driven. The current native planner chooses a complete
movement of one feed or the opposite movement of the other from normalized
capability/window snapshots, then uses a joint hold/seek/check/resume transaction.
It cannot advance a live source into the future, assume unlimited retained
history or retry the other side after an uncertain dispatch. Requested relative
shifts and sampled player clocks are not common-event synchronization.

The historical browser strategies remain useful where native capabilities are
unavailable:

| Source combination | Initial strategy |
| --- | --- |
| Live + live | Pause the earlier pane to accumulate delay; reset/reload to remove delay |
| Replay + replay | Seek one pane relative to a user-chosen anchor; periodically compare reported positions |
| Live + replay | Establish a manual event anchor, then seek or pause the controllable pane |
| Opaque player | Expose manual provider controls and an external stopwatch-style offset aid |

Browser hold-based controls use Android's monotonic clock to record how
long a pane was deliberately held. That is a requested offset, not proof that
the provider retained the same media position.

A future controller may make small playback-rate corrections when both players
expose trustworthy positions. It must not alter rate when audio quality or the
provider player makes the correction objectionable.

## Android shell

The Android implementation uses Kotlin and Jetpack Compose with one
conventional Gradle `app` module. Avoid premature Android library modules.

Probable feature areas are:

```text
app/                 application wiring and top-level navigation
feature/presentation presentation editor and viewer
feature/sources      provider and resource selection
feature/settings     site data, diagnostics, and appearance
data/                saved presentations and preferences
platform/web         WebView lifecycle, permissions, cookies, and popups
platform/tv          D-pad focus and television integration
provider/            adapter interfaces and packaged adapter assets
designsystem/        theme and reusable controls
```

### Current debug native prototype

The [product prototype](prototype-ux.md) separates its fixed source catalogue,
slot ownership and viewer state from provider preparation. Generic presentation
models and commands do not inspect DOM or licensing data. Platform Media3
hosting owns decoding, lifecycle, bounded execution and advertised timing
capabilities; provider adapters own source/authentication/licensing policy.
Duplicate selections have independent hosts. Layout and primary/secondary swaps
preserve feed identity and sessions.

Twitch native playback uses the explicitly approved device-grant/private-playback
comparisons, protected local grant storage and validation before use. It is not
the official embedded player or a supported third-party playback SDK. Earlier
own-client authorization and browser experiments remain separate examples.

The cached ABEMA adapter downloads four hash-pinned public bundles at runtime
and verifies cached bytes before reuse. Its small owned browser runtime loads
selected unchanged factories, not a full provider page. Fresh anonymous guest,
media and selected-source setup precede one opaque helper exchange with a fresh
native CDM. The fixed free replay additionally requires conservative metadata
checks. There is no helper-algorithm copy, key extraction, response reuse,
renewal or provisioning fallback. See the
[native evidence](native-access-experiments.md),
[security boundaries](security-and-privacy.md) and
[media-origin approvals](media-origin-approvals.md) for the exact scope.

The direct selected-manifest path currently has no separate client ad player
or tracking lifecycle. Accepting provider-ranked direct NONE, CSAI or
ABEMA_DEFAULT sources through unchanged builders is not equivalent to complete
provider advertising behavior; there is no ad-free alternate-source fallback.
MediaTailor and unknown modes remain refused pending their required resolution.
Supported provider operation, durable flags/entitlement handling, long-running
licenses and release integration remain unresolved.

The viewer's shared five-minute foreground budget includes preparation.
Stop/background/error teardown closes both hosts; rotation and layout changes
do not extend the budget. Bounded Pixel 6 successes do not establish Android TV
or cross-platform support. The agreed portrait-stack/landscape-floating controls
are documented in the [viewer layout](preliminary-native-viewer.md).

### Historical browser composition

The earlier Android mixed-audio candidate uses one WebView/WebContents: ABEMA
remains the top-level document and the official Twitch player is hosted in a
cross-origin child frame. A bounded Pixel 6 test found one Chromium audio-focus
delegate and simultaneous audible output, while separate provider WebViews
requested focus independently and paused one another. Keep the hosting topology
behind the platform browser boundary; generic presentation state must not know
which provider is top-level or framed.

This composition is provisional. It depends on ABEMA continuing to permit the
child frame, Twitch accepting the declared ABEMA ancestor names, and both
providers tolerating the shared WebContents. It also enables third-party
cookies for the whole composite WebView, including other third parties loaded
by ABEMA; Android WebView does not scope that setting to `player.twitch.tv`.
Provider pages must not receive a generic native JavaScript interface. If
adapter messages require a bridge, prefer a narrowly scoped mechanism that
validates the exact source origin and message shape. Never load Twitch's SDK
script into ABEMA's top-level JavaScript realm; the cross-origin frame must
retain Twitch's origin boundary.

The independent-control follow-up adds an app-owned HTTPS child page served by
`WebViewAssetLoader`. Twitch's documented SDK runs there and retains its own
cross-origin player below it, not in ABEMA's JavaScript realm. The provider
adapter translates generic pane commands into an origin-targeted `postMessage`
exchange; Android hosting knows only the command script, optional bounded reply
poll, and per-pane status. Both web endpoints validate source, origin, schema,
request identity, and a fresh frame-session identifier. There is no native
JavaScript bridge. Only the two explicitly approved child assets are exposed to
the composite, and other synthetic-origin requests fail locally. SDK replies
mean a command was requested, not that playback or acoustic output succeeded.
This extra nesting remains a debug-only experiment until on-device testing
verifies its minimum dimensions, activation, and mixed-audio behavior.

## Shared code

Do not create a shared Rust or Kotlin Multiplatform core merely because several
platforms are desired. The first likely portable artifact is provider adapter
JavaScript/CSS. A shared state-transition core becomes worthwhile only after
Android reveals nontrivial, testable presentation and alignment rules that
other shells can consume without platform browser dependencies.

## Desktop direction

An ordinary supported browser is more likely to have functioning DRM than a
newly bundled embedded-browser runtime. A browser extension or controlled
installed-browser experience should therefore be compared with native desktop
wrappers before selecting Electron, CEF, WebView2, WKWebView, or WebKitGTK.
