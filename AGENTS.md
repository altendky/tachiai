# Agent context

Start with [the project documentation](docs/src/project/index.md).

## Current project state

Tachiai has an Android feasibility implementation, not a production integration.
The debug application preserves historical experiments and adds a separate
[source-picker/native-viewer prototype](docs/src/project/prototype-ux.md).
Distinguish implemented behavior, bounded device observations and proposals.

The first concrete pairing is ABEMA's Grand Sumo coverage with the
`midnightsumo` Twitch channel, but the product model must remain a generic
multi-stream presentation capable of live and replay sources.

## Default development device

Use the persistent `tachiai-dev` Android emulator for routine local deployment,
UI inspection, debugging and playback experiments. Follow the
[development-device guide](docs/src/project/android-development.md); explicitly
target and verify its AVD identity rather than selecting whichever ADB device is
attached. Preserve its saved authorization, routes and app data. Do not run
destructive/resetting tests on this persistent device.

An explicit user request to deploy to, inspect or debug their phone overrides
the emulator default for that task. Verify the physical device identity and
availability before operating on it. The emulator default does not prohibit
requested phone work or require another device-choice approval for that same
task.

If the emulator is unavailable or a check needs physical-device fidelity,
diagnose/report the limitation and ask before using the phone unless the user
has already requested or authorized phone use for the current task. Never
silently fall back to an attached phone. Emulator observations do not establish
physical-device DRM, decoding, audio or performance behavior.

## Architecture boundaries

Prefer provider-supported playback. Historical web comparisons keep the
provider's original media element, DRM session, advertisements and login flow
intact; focus mode must not capture, record or re-encode protected video.
The user-approved debug native experiments are narrow exceptions documented in
[native-access evidence](docs/src/project/native-access-experiments.md) and
[security boundaries](docs/src/project/security-and-privacy.md), not permission
to expand private APIs, authentication or DRM handling. The cached ABEMA path
uses verified runtime-only public bundles, fresh guest/source setup and one
opaque initial exchange with a fresh native CDM; it does not load the full
provider page. Do not copy helper algorithms, extract keys, reuse responses or
add renewal without a separately reviewed scope.

Provider-specific DOM, source, authentication and licensing knowledge belongs
behind narrow adapters. Generic presentation, layout, audio mixing and alignment
must not depend directly on ABEMA or Twitch selectors or private protocols.
Capabilities are optional; requested offsets are not proof of synchronization.
Review the explicit [media-origin approvals](docs/src/project/media-origin-approvals.md)
before broadening cached prototype network policy; a review journal never
grants access.

Never collect provider passwords. Use provider-controlled login pages or the
explicitly documented device-authorization cases and protected local grant
storage. Do not inject scripts on login pages,
expose native JavaScript bridges to provider content, transfer session cookies
between devices, bypass TLS errors, or log URLs that may contain tokens.

Treat Android phone/tablet and Android TV as the first implementation target.
Treat iPhone/iPad, Windows, macOS, and Linux as later platform investigations,
not as capabilities inherited automatically from Android WebView.

Record device, OS, browser-engine, region, account state, content type, and
observed behavior for every playback experiment. Update
[decisions](docs/src/project/decisions.md) only when evidence settles a choice;
keep unresolved findings in [open questions](docs/src/project/open-questions.md).
