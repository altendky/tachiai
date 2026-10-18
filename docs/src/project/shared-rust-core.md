# Shared Rust core

The Cargo workspace separates reusable product rules from application hosts.
The first extracted behavior is playback timing: absolute and relative seek
planning, live-default recovery planning and checked content-clock arithmetic.
It is used by the Android application's existing HLS and DASH hosts through a
separate Android library module. It does not own players, accounts, routes,
networking, secrets, persistence or a user interface.

## Packages and consumers

| Item | Purpose | Consumer |
| --- | --- | --- |
| `packages/core` / `tachiai-core` | Dependency-free domain library with typed snapshots and plans | Other Rust applications or binding facades |
| `packages/core-ffi` | Thin UniFFI record/enum conversion and native exports | Generated Kotlin and Swift bindings |
| `packages/core-web` | Thin wasm-bindgen facade with typed JavaScript results | Browser applications and other JavaScript consumers |
| `tools/core-bindgen` | Generator pinned to the native runtime version | Local builds and CI |
| `apps/android/core` / `:core` | Reusable Android library containing Kotlin bindings and native runtime dependencies | Android applications, including `:app` |

The core has no Android, Media3, JVM, UniFFI or JavaScript dependency. Native and
web facades delegate decisions rather than duplicating them. The Rust consumer
example and web example/smoke test illustrate use without Tachiai's Android app.
The generated Android library has its own namespace and no dependency on the
app's activities, Compose UI, saved configuration or provider code.

Generated bindings and libraries are one versioned unit. UniFFI's generated
compatibility checks remain enabled; there is no duplicate Kotlin algorithm or
silent fallback if the native library is missing or incompatible. This is an
initial `0.1` API, not a promise of stable Rust/native ABI or saved-data format.

## Behavior and platform boundary

The core receives only explicit optional millisecond clocks and capabilities.
Signed window-relative positions can be stale or outside a moving live window.
Unknown values are absent, not Android unset-time constants. Plans are typed
variants: requested, clamped, unchanged, unavailable, unsupported, advertisement
blocked or outside the window. Only target-bearing variants carry a target.

A host still decides whether it may dispatch, executes the actual operation,
observes completion and applies existing lifetime/ownership checks. In
particular, a live-default plan must use the player's default-position command;
its observed target is not permission to use arbitrary seeking. Nothing in a
plan establishes acoustic synchronization or extends a media budget.

Android's existing timing snapshot retains buffered ranges, playback state and
other platform observations for its current callers. Its adapter sends only
the portable subset to Rust and translates the returned plan. Media3 state
numbers, `Prototype` identities, routes and provider session data do not enter
the shared API.

## Compatibility choices

The user requested that migration cost not determine the shared language, and
asked for compatibility-driven choices to be explicit.

| Choice | Reason | What could differ without compatibility concerns |
| --- | --- | --- |
| Keep current Kotlin wrapper entry points | Limit the first integration to replacing the rules behind existing callers | A later Android cleanup can use the generated model directly; the Rust API does not require the wrappers |
| Preserve current seek outcome precedence | Keep user-visible planning/refusal behavior comparable during phone acceptance | A fresh design could report clamping separately from an unchanged action, or choose a different reason when several refusals apply |
| Require a known current position for an absolute seek | Preserve the existing Android rule, including its unchanged-position check | A fresh design could permit a bounded absolute seek with an unknown current position, returning a requested/clamped plan without checking for no change |
| Preserve signed stale-window arithmetic | Existing recovery logic can observe a position outside the new live window; overflow and direction reversal must remain safe | A different host could normalize observations differently, but must not discard moving-window information without reviewing recovery semantics |
| Keep API 26 and ARM64/x86-64 in Android packaging | Match current Android native support and in-place installation | These are Android build choices, not limitations on the Rust domain model |
| Keep current application ID, signer and stored records | Preserve the phone's configured providers, grants and routes | The Rust core neither reads nor defines those records; a different application can use its own storage and identity |

There is no old Rust API, binary consumer or Rust persistence format to emulate.
No obsolete Android naming or wire protocol is introduced solely to preserve
compatibility. Future core extractions should use normalized capabilities rather
than importing platform enums or provider/browser implementation details.

## Build and verification

Use the pinned Rust/tooling configuration through mise. Provision the check tools
and Wasm target explicitly on a fresh machine: mise selects the toolchain but
does not necessarily install the components requested by `rust-toolchain.toml`.
From the repository root:

```sh
mise exec -- rustup component add rustfmt clippy
mise exec -- rustup target add wasm32-unknown-unknown
mise run core
cargo run --locked -p tachiai-core --example seek
```

The core check runs formatting, Clippy, Rust tests, native binding generation,
Wasm compilation and the JavaScript smoke test. Generated outputs are under
ignored `build/core/`: native host/Android libraries, Kotlin/Swift sources and
the web module. The compiler and generators are pinned; Cargo.lock is committed.

Before building Android, provision its targets in the selected toolchain
(before mounting that toolchain read-only in a build container):

```sh
mise exec -- rustup target add aarch64-linux-android x86_64-linux-android
```

Then, in the documented Android SDK/NDK environment:

```sh
bash scripts/build-android-core.sh
bash scripts/build-android-routebridge.sh
cd apps/android
./gradlew --no-daemon test lint :app:assembleDebug :core:assembleRelease
```

The Android module also prepares its native inputs before compilation. JVM
tests load the host Rust library through the generated production bindings;
they do not substitute a second implementation. Provider-free instrumentation
tests exercise the same Android timing facade with the packaged device library.
Continue the [Android signing and deployment procedure](android-development.md).

`apps/android/core` configures a release Maven publication and source artifact
for other Android applications. Its native binaries and generated bindings must
be consumed together, along with the declared JNA dependency and license assets.
This does not publish an external package or establish an artifact registry.

Swift source/header/module-map generation can run on Linux, but actual Apple
target compilation, framework packaging and iOS execution need macOS/Xcode.
Wasm/JavaScript checks do not establish provider playback in a browser. The
library adds no new ABEMA/Twitch account, DRM, origin or route capability.

Required native dependency notices accompany the library; the application's
native source companion also includes the Rust workspace and rebuild inputs.
Rust and JNA native libraries must meet the application's ABI and 16 KiB page
requirements, as well as its existing APK certificate checks.

The user accepted the initial implementation after an in-place Pixel 6 phone
trial. Automated binding checks complement that acceptance; they do not
establish behavior on other platforms.
