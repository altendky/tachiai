# Native bindings

This crate adapts portable `tachiai-core` values to UniFFI records, enums and
functions. It performs no planning arithmetic. Kotlin and Swift receive typed
snapshots, optional signed millisecond values and target-bearing plan variants.
There is no JSON command protocol or platform-specific sentinel in this API.

`bash scripts/build-core-bindings.sh` builds the host shared library and generates
Kotlin and Swift source bindings with the locked runtime/generator version.
Generated sources are in `build/core/bindings/{kotlin,swift}` and are rebuilt
alongside the library. The Android library module at `apps/android/core` packages
the Kotlin bindings, native ABIs and JNA dependency for application reuse.

Swift sources, a C FFI header and module map can be generated on Linux. Building
and testing an Apple-target static library or XCFramework requires the Apple
toolchain; generated source is not evidence of iOS compilation or runtime
compatibility. An Apple host should compile this crate for its device/simulator
targets and link the static library with matching generated bindings.

The `0.1.0` API is evolving. Keep generated bindings and native libraries from
the same build; UniFFI's compatibility/checksum validation remains enabled.
Android application identifiers, saved configuration and historical class names
are handled by the application adapter and are not library compatibility rules.
