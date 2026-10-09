# Native library replacement and rebuild materials

The distributed debug APK accompanies `routebridge-native-source.tar.xz`.
Its relative SHA256SUMS covers exact Android application/Go/native sources,
patches, build recipes, notices, pinned upstream archives and native artifact
identities. No signing key, provider grant or exported private configuration is
included. The application remains under its existing MIT/Apache terms. The
OpenConnect library uses LGPL 2.1; selected GnuTLS/Nettle/GMP components require
the LGPL 3 path described in the retained licenses and source notices.

The eight libraries are dynamically linked, separately packaged ordinary `.so`
files with stable SONAMEs. The adapter uses OpenConnect's public library ABI;
the application/Go JNI code does not include upstream private native objects.
The supplied source and recipes permit rebuilding and replacing the covered
libraries and relinking the JNI/application. The application license does not
restrict reverse engineering to debug modifications to these libraries.

## Rebuild from the exact source

Extract the companion and verify `sha256sum --check SHA256SUMS` from its root.
Use the versions in `mise.toml`/`mise.lock`: Go 1.27.2, Java 21 and the Android
SDK/NDK versions declared by the provided scripts/Gradle sources. The native
helpers require a Linux C build environment with make, autoconf, automake,
gettext, libtool, pkg-config, cmake, ninja, perl, curl, tar, patch and uv.

Set ANDROID_HOME to the SDK and ANDROID_NDK_ROOT to its NDK 30.0.16248370.
Create `apps/android/routebridge/build/openconnect-native/downloads` and copy
the six archives from `upstream/openconnect` there. Likewise copy the five
archives from `upstream/openvpn` into
`apps/android/routebridge/build/openvpn-native/downloads`. Both native builders
verify their pinned hashes before use; their ordinary cache locations are
inside the source tree and never modify the system SDK or library installations.

Run `bash scripts/build-android-routebridge.sh`. It builds both complete ABI
closures, normalizes library SONAMEs by relinking generated recipes, verifies
the exact eight-library dependency set/16 KiB alignment, and creates the JNI
AAR, embedded notices and new matching source companion. Source/patch/recipe
fingerprints govern cache reuse; external-library hashes are also incorporated
into cgo's inputs so a changed native library forces JNI relinking.

For a modified covered library, retain its public ABI and required policy entry
points. Modify the included sources/patches and native recipe as needed, rebuild
the corresponding library and rerun the binding/application steps. Updating a
pin requires an explicit new verified source hash. There is no binary patching,
signature whitelist for native libraries, remote authorization service or
provider secret required to load a rebuilt library. Every distributed modified
binary needs its own matching notices/source/relink materials.

The Android project builds with its provided Gradle wrapper. The repository's
normal debug-build checks require the shared debug certificate. That private
key is deliberately absent. A recipient building a personally modified copy
can use their own Android debug certificate by updating the provided debug
signing configuration and `VerifyDebugKeystore` expected fingerprint in
`apps/android/app/build.gradle.kts` to their certificate. This does not require
the original signing key. Build `:app:assembleDebug` and verify its certificate
with the SDK apksigner before installing on a recipient-owned test device.

Android requires the same signer for an in-place update. A differently signed
copy can be installed after uninstalling the original package on that test
device; uninstalling removes that package's saved data. The recipient controls
that choice. No bootloader unlocking, platform signature, additional installer
authorization key or application server permission is required for an ordinary
debug sideload. These instructions do not authorize an agent to replace the
project's shared key or reset the persistent development device.

## Retained per-build evidence

`native-artifact-identities/ABI` directories contain NDK properties, the ABI,
relative native input checksums, their fingerprint and the eight final library
checksums. Exact covered source archives include their per-file notices and
bundled GnuTLS libtasn1/unistring sources. The complete native-build recipes
retain generated sources, object files and link logs in the selected local
cache; they are reproducible from this companion. No proprietary/native object
is required to regenerate or relink the provided application.
