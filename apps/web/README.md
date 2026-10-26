# Browser playback harness

This local diagnostic host is the first web implementation slice, tracked by
[#364](https://github.com/altendky/tachiai/issues/364). It plays controlled,
unprotected fixtures through Tachiai-owned video elements and Shaka Player,
with the production Rust/Wasm timing facade. It is not the product source picker,
provider/account implementation or a provider playback acceptance result.

## Build and run

Use the repository's pinned mise tools. Install the Wasm target, FFmpeg and an
ordinary Google Chrome executable. FFmpeg only packages the existing owned MP4
fixtures with stream copy; no provider media is downloaded and no new media is
encoded. If FFmpeg is not on PATH, set `FFMPEG=/path/to/ffmpeg` for preparation.

From the repository root:

```sh
mise exec -- rustup target add wasm32-unknown-unknown
mise exec -- npm ci --ignore-scripts --prefix apps/web
mise exec -- npm run prepare:assets --prefix apps/web
mise exec -- npm run dev --prefix apps/web
```

Open the printed development URL with `/harness/`. Load a fixture, then press
Play. Muted is initially enabled. The fixture selector offers A/B in HLS/DASH;
its controls and observations are diagnostic interfaces, not a new product UX.

For the production build and tests:

```sh
mise exec -- npm run build --prefix apps/web
mise exec -- npm test --prefix apps/web
```

If Chrome or its system dependencies are missing, install the browser explicitly
with `npx playwright install --with-deps chrome` from `apps/web`. On a host with
those prerequisites, `mise run web` performs a clean dependency install,
asset preparation, type/build checks and browser tests. `mise run core` retains
the Rust/native-binding/actual JavaScript-Wasm checks.

The tests start their own production preview server on loopback port 4173 and
exercise the `/harness/` base path. They use a fresh browser context, not a user's
open browser/profile. They retain no screenshots, recordings or traces. Generated
bindings, fixtures, notices, distribution files and test output are ignored under
`build/`; dependencies use the committed npm lockfile. Upstream player and Rust
dependency notices accompany the generated site under `licenses/`.

## Boundaries

- `BrowserPlayer` owns one Shaka instance, media element and cancellable lifetime.
  `Harness` serializes cleanup and refuses stale results on replacement. A failed
  cleanup prevents new playback rather than silently admitting another session.
- Observations expose optional clocks/capabilities and public fixture identity.
  Live observations normalize the moving seek window; stale signed positions are
  retained. Unsupported or unknown clocks are not replaced by zero.
- Relative seeks use the generated Rust planner and free its objects. A requested
  or clamped plan is dispatched, then a bounded media `seeked` observation checks
  settlement. This does not establish inter-feed synchronization.
- Error payloads stay inside the player; application results use closed values.
  There are no provider credentials, authentication adapters, network brokers,
  routing changes, public URL import or provider media in this slice.
- Fixtures have one rendition and no advertisements. Their timing/gain behavior
  does not establish provider quality, ad policy, DRM, live-window support or
  acoustic output. Actual live sources and their capability binding need their
  separate provider investigations.

See the [web evidence and next steps](../../docs/src/project/web-client.md).

## Controlled pair fixture

`/harness/pair.html` adds a separate provider-free controller milestone with
explicit A/B assignment and Watch, independent duplicate sessions, common
mix/mutes, retained primary/floating geometry, full-step relative timing and
individual retry. Watch requests playback as each feed becomes ready. The
existing single-player diagnostic page is retained.

This is not the full product picker/viewer or provider acceptance. Complete
quality/orientation/settings controls and provider/account/route integration
remain pending. See the [fixture scope and remaining acceptance](
../../docs/src/project/web-pair-fixture.md). Issue #368 remains open.
