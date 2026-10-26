# Web client implementation

The first implemented slice is a local browser playback harness in `apps/web`,
tracked by [#364](https://github.com/altendky/tachiai/issues/364) under the
[web client tracker, #363](https://github.com/altendky/tachiai/issues/363).
It uses TypeScript, Vite and Shaka Player with the existing Rust/Wasm timing
facade. This is controlled-media integration infrastructure, not a complete
web application or demonstrated ABEMA/Twitch playback.

The [harness README](../../../apps/web/README.md) provides clean-checkout build,
development and production-test commands. Generated assets are served under a
non-root `/harness/` base path, including the matching JavaScript/Wasm pair and
dependency notices. Tests exercise the production build with ordinary Chrome.

The [controlled two-feed fixture](web-pair-fixture.md) adds independent A/B
assignment, retained layout, joint transport, shared mix and relative timing.
Its diagnostic milestone is implemented under #368; full product and provider
acceptance remain open.

## Implemented boundary

One adapter owns each video element, Shaka player, observations, transport/gain/
quality commands and disposal. The harness serializes cleanup before admitting a
replacement and refuses retired callbacks. Shared Rust plans relative seeks;
the browser host dispatches them and checks observed settlement. Clocks are
checked integer milliseconds with unknown values left absent. Source-specific
authentication, DRM exchanges and network effects stay outside Rust.

Both HLS and DASH fixtures are packaged locally, using FFmpeg stream copy, from
the repository's [owned controlled media](controlled-mirroring-fixture.md).
Their committed hashes are checked before packaging. They have no provider
sessions, advertisements or protected content. The diagnostic controls are not
an alternate web product design.

## Verification scope

On October 25, 2026, all eight production-build browser tests passed on this Linux
host with Google Chrome 156.0.8078.47. Both local HLS and DASH decoded 640×360
frames, advanced playback and completed paused relative seeks. The trial used
fresh test contexts, no provider account and only loopback fixture requests;
region and acoustic output were not measured. The shared Rust/native/browser
checks also passed. These observations establish only this bounded host trial.

The browser suite checks actual controlled-media decoding/advancement, user-action
play, pause, observed seek settlement through the real Wasm planner, gain/mute and
quality requests, load failure, replacement during preparation, stale events and
stop/disposal. Clock-conversion checks cover signed values and refusal of unknown
or inexact inputs. The existing Rust/native/browser binding suite remains the
shared-rules check. These checks do not establish audible output or provider
integration, and generic CI/Chrome success does not establish other engines.

## Remaining work

- [#365](https://github.com/altendky/tachiai/issues/365): Twitch account-bound HLS
  playback through Tachiai-owned media, including browser request/session rules.
- [#366](https://github.com/altendky/tachiai/issues/366): ABEMA browser CDM,
  source/session/entitlement binding and opaque licensing behavior. The initial
  [bootstrap investigation](web-abema-feasibility.md) records direct-origin
  restrictions and bounded anonymous backend viability, not ABEMA playback support.
- [#367](https://github.com/altendky/tachiai/issues/367): account persistence,
  full route coverage and whether an extension or companion is needed. The
  [account/route assessment](web-accounts-and-routes.md) records the request matrix,
  extension-free backend candidate and decisions needed before retention/deployment.
- [#368](https://github.com/altendky/tachiai/issues/368): the common picker and
  two-feed viewer, followed by combined provider and acoustic acceptance.

Provider work may produce a reproducible blocker rather than a working session;
record that outcome separately from support. No extension, native companion,
hosted media relay or browser-specific product UX has been selected here.
