# Cross-platform validation roadmap

Proposed 2026-10-17 from the [platform landscape](platforms.md). This is a
sequence of bounded experiments, not a completed test report or authorization
to modify devices. Preserve existing phone/emulator app data; use separately
identified test devices and accounts where destructive testing is necessary.

## Resolve expensive uncertainty first

| Workstream | Cheapest decisive experiment | Evidence required before expanding |
| --- | --- | --- |
| Android TV / Google TV | Inventory the available Shield's model, API and supported ABIs; run provider-free navigation and two-player samples | Installable native outputs, complete remote operation, two decoders and audible mix on the actual TV. Add ARM32 outputs only if the selected support scope requires them. |
| Apple shared development | On a Mac, build the existing Rust timing library into device/Simulator artifacts and call it from a minimal Swift host | Reproducible iOS and tvOS linking, type/error/lifetime behavior and packaging. Swift source generation alone is insufficient. |
| iOS / iPadOS provider feasibility | Test the provider-controlled account path and one entitled ABEMA source using an explicitly identified player/browser strategy | Actual Apple media delivery and DRM compatibility, verified account/source binding, persisted login and a clear explanation of remaining unavailable capabilities. |
| tvOS ABEMA feasibility | Establish whether a supported or otherwise approved native media/bootstrap and account flow exists without WKWebView | A viable helper/session/DRM path. AuthenticationServices or JavaScriptCore availability alone does not supply today's browser runtime. Stop short of building the full UI if this gate remains unresolved. |
| Desktop web provider feasibility | Start with an ABEMA-listed desktop browser; test the intended integration form and a Twitch embed alongside it | Actual two-feed playback/control, origin/framing/CDM behavior, live/replay capability limits and account handoff. A working standalone ABEMA tab or generic HLS sample is insufficient. |
| Per-provider routes | Exercise one chosen backend through the exact platform host | Auth, catalogue, redirect, manifest, segment, license and resolver/IPv6 coverage; cancellation/cleanup; no unintended direct fallback. Repeat for browser/helper traffic rather than testing native HTTP alone. |
| Independent distribution | Install a normal signed build through the selected nonstore channel, then update in place | Retained configuration, expected account state, usable launch entry, update/signing continuity and documented renewal requirements. Developer-mode success must be labeled as such. |

Android TV is the first full-port candidate because it reuses Android services.
Apple and web provider feasibility can proceed as small independent spikes;
neither should wait for a polished settings UI to expose a fundamental media or
route gap. This ordering is a recommendation, not a requirement to finish all TV
work before investigating Apple/web.

## Shared conformance fixtures

Keep pure timing/seek tests in Rust and binding contract checks in each host.
As more rules are extracted, add common fixtures for configuration validation,
source identity, route ordering and recovery transitions. Platform observations
must be normalized explicitly; absent clocks, expired live windows and
unsupported capabilities must not be replaced with convenient fabricated data.

Hosts still need tests for their own asynchronous completion, player disposal,
session replacement and lifecycle behavior. Shared planning tests cannot
establish AVPlayer/Media3/browser seek completion, actual audio output or
provider entitlement. Match documented abilities and outcomes across hosts
rather than requiring identical internal implementations.

## Evidence record for each experiment

Record the app/core revision, OS/device/model and ABI, engine/player/CDM version,
region, local account state, provider source/content type, route configuration
and the precise observed result. Record consumed formats, buffering, dropped
frames, memory and an acoustic audio check when evaluating playback. Include
the display/HDMI chain for television tests and the browser/extension/companion
versions for desktop tests.

Exercise live/live, live/replay and replay/replay where each host actually
supports them; duplicate sources must retain independent session ownership.
Check background/Home, sleep, resume, route loss, account expiration, one-feed
failure, recovery, source replacement and application restart. A short free
sample, one codec or one account plan does not establish all content support.

Keep provider credential entry user-controlled. Store only sanitized evidence,
never passwords, session cookies, grant values or token-bearing URLs. Use the
existing [security boundaries](security-and-privacy.md) and
[media-origin approvals](media-origin-approvals.md); a research proposal does
not broaden them.

## Readiness labels

Keep milestones separate: **interface available**, **build/link works**,
**provider-free sample works**, **provider session works**, **route verified**,
**two-feed hardware trial passed**, and **distribution/update rehearsed**.
Only the measured milestone is earned. Current Android evidence is the baseline;
the new platform reviews do not earn any additional runtime milestone.
