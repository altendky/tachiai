# Agent context

Start with [the project documentation](docs/src/project/index.md).

## Current project state

Tachiai's [source picker, provider settings and native viewer](docs/src/project/prototype-ux.md)
are the primary Android application in ordinary debug and release builds.
Historical experiments remain developer-only; the local `inspect` variant keeps
its separate inspection exception. Existing `Prototype` class/file names may
remain compatibility identities, not a requirement to exclude product features
from release. Distinguish implemented behavior, bounded device observations and
proposals; promotion does not establish provider support or production readiness.

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
Historical user-approved debug native experiments are documented in
[native-access evidence](docs/src/project/native-access-experiments.md) and
[security boundaries](docs/src/project/security-and-privacy.md). On 2026-10-10
the user separately approved general ABEMA and Twitch access, including
undocumented endpoints where a needed operation has no documented API, and
replacement of experiments after actual configured selections work. Follow the
[current integration scope](docs/src/project/security-and-privacy.md#general-provider-integration-approval--2026-10-10)
and updated issue bodies; absence of API documentation or the old fixed-sample
permission limits must not become another approval gate. The cached ABEMA path
uses verified runtime-only public bundles, fresh guest/source setup and one
opaque initial exchange with a fresh native CDM; it does not load the full
provider page. Do not copy helper algorithms, extract keys, reuse responses or
add unverified renewal. General access still requires verified source/session
binding and provider entitlement; it does not authorize these shortcuts.

Provider-specific DOM, source, authentication and licensing knowledge belongs
behind narrow adapters. Generic presentation, layout, audio mixing and alignment
must not depend directly on ABEMA or Twitch selectors or private protocols.
Capabilities are optional; requested offsets are not proof of synchronization.
Review the explicit [media-origin approvals](docs/src/project/media-origin-approvals.md)
before broadening cached prototype network policy; a review journal never
grants access.

Never collect provider passwords. Use provider-controlled login pages or
verified account-linking/device-authorization flows and protected local grant
storage. Do not inject scripts on login pages,
expose native JavaScript bridges to provider content, transfer session cookies
between devices, bypass TLS errors, or log URLs that may contain tokens.

The user-approved, local-only `inspect` build is an explicit exception to
screenshot, UI hierarchy and DevTools restrictions, including provider login and
account pages. Agents may observe these surfaces and use local debugging tools
for DOM inspection and interaction without asking for another inspection approval.
Provider credential entry remains user-controlled; do not collect passwords or
automatically log or retain secrets. This does not authorize app-injected login
scripts, native JavaScript bridges, TLS bypasses, route/account-validation changes
or protected-media extraction. Ordinary builds retain their protections. Follow
the [inspection build guide](docs/src/project/android-development.md#local-inspection-build)
and [scope](docs/src/project/security-and-privacy.md#local-inspection-build-approval--2026-10-15).

Treat Android phone/tablet and Android TV as the first implementation target.
Treat iPhone/iPad, Windows, macOS, and Linux as later platform investigations,
not as capabilities inherited automatically from Android WebView.

Record device, OS, browser-engine, region, account state, content type, and
observed behavior for every playback experiment. Update
[decisions](docs/src/project/decisions.md) only when evidence settles a choice;
keep unresolved findings in [open questions](docs/src/project/open-questions.md).
