# Agent context

Start with [the project documentation](docs/src/project/index.md).

## Current project state

Tachiai is in the documentation and feasibility-spike stage. There is no
implemented application yet. Do not describe proposed behavior as tested.

The first concrete pairing is ABEMA's Grand Sumo coverage with the
`midnightsumo` Twitch channel, but the product model must remain a generic
multi-stream presentation capable of live and replay sources.

## Architecture boundaries

Prefer provider-supported web playback before private stream extraction. Keep
the provider's original media element, DRM session, advertisements, and login
flow intact. A focus mode may reshape the page around the original player; it
must not capture, record, or re-encode protected video.

Provider-specific DOM knowledge belongs behind narrow adapters. Generic
presentation, layout, audio-selection, and alignment behavior must not depend
directly on ABEMA or Twitch selectors.

Never collect provider passwords. Let provider pages authenticate inside a
persistent, app-private browser profile. Do not inject scripts on login pages,
expose native JavaScript bridges to provider content, transfer session cookies
between devices, bypass TLS errors, or log URLs that may contain tokens.

Treat Android phone/tablet and Android TV as the first implementation target.
Treat iPhone/iPad, Windows, macOS, and Linux as later platform investigations,
not as capabilities inherited automatically from Android WebView.

Record device, OS, browser-engine, region, account state, content type, and
observed behavior for every playback experiment. Update
[decisions](docs/src/project/decisions.md) only when evidence settles a choice;
keep unresolved findings in [open questions](docs/src/project/open-questions.md).
