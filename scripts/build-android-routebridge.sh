#!/usr/bin/env bash
set -euo pipefail

# Run in the documented disposable Android SDK build environment. Host tools
# cross-compile ARM64/x86_64 directly; no emulated ARM compiler is involved.
task_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
task_bridge="$task_root/apps/android/routebridge"
task_mobile_version=v0.0.0-20260908204917-8b95e45f8d3e
task_ndk_version=30.0.16248370
test "$(go env GOVERSION)" = go1.27.2
: "${ANDROID_HOME:?Use the disposable Android build SDK}"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$task_ndk_version"
test -f "$ANDROID_NDK_HOME/source.properties"
mkdir -p "$task_bridge/build/tools"
export GOBIN="$task_bridge/build/tools"
export PATH="$GOBIN:$PATH"
export GOTOOLCHAIN=local
go install "golang.org/x/mobile/cmd/gomobile@$task_mobile_version"
go install "golang.org/x/mobile/cmd/gobind@$task_mobile_version"
gomobile init
export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
bash "$task_root/scripts/build-android-openvpn.sh"
task_native="${TACHIAI_OPENVPN_BUILD_ROOT:-$task_bridge/build/openvpn-native}"
task_native="$(cd "$task_native" && pwd)"
# cgo uses SRCDIR paths. Copies remain valid when a repository/cache is mounted
# at a different path in a disposable build container; absolute symlinks do not.
for task_abi in arm64-v8a x86_64; do
  task_target="$task_bridge/build/openvpn-native/$task_abi/prefix/lib"
  mkdir -p "$task_target"
  if test "$(cd "$task_target" && pwd)" != "$task_native/$task_abi/prefix/lib"; then
    cp "$task_native/$task_abi/prefix/lib/"*.a "$task_target/"
  fi
done
cd "$task_bridge"
# Discard only this builder's generated staging from older flattened layouts
# before package discovery. The final companion retains its nested go.mod.
rm -rf build/openvpn-source-companion
go test ./...
# cgo's default allowlist excludes this linker visibility flag. Admit only
# the exact flag used by the reviewed static native dependency boundary.
export CGO_LDFLAGS_ALLOW='^-Wl,--exclude-libs,ALL$'
# Go's cache does not detect changed external C archives. Include their content
# identity in the cgo compilation flags so a native edit always relinks JNI.
task_native_identity="$(sha256sum "$task_bridge/build/openvpn-native/"*/prefix/lib/*.a | awk '{print $1}' | sha256sum | awk '{print $1}')"
export CGO_CFLAGS="${CGO_CFLAGS:+$CGO_CFLAGS }-DTACHIAI_OPENVPN_NATIVE_INPUTS_$task_native_identity"
gomobile bind -target=android/arm64,android/amd64 -androidapi=26 \
  -tags=openvpn_validation -trimpath -o build/routebridge.aar .
# Package redistributable notices in the AAR/APK assets, not just source docs.
mkdir -p build/license-payload/assets/routebridge-licenses
cp licenses/*.txt THIRD-PARTY-NOTICES.md build/license-payload/assets/routebridge-licenses/
cp "$task_root/LICENSE-MIT" "$task_root/LICENSE-APACHE" build/license-payload/assets/routebridge-licenses/
(cd build/license-payload && zip -q -r ../routebridge.aar assets)
# Every packaged ABI must support Android's 16-KiB page-size requirement.
task_extract="$task_bridge/build/verify"
mkdir -p "$task_extract"
unzip -o -q build/routebridge.aar 'jni/*' -d "$task_extract"
task_readelf="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
for task_library in "$task_extract/jni/arm64-v8a/libgojni.so" "$task_extract/jni/x86_64/libgojni.so"; do
  test -f "$task_library"
  case "$task_library" in
    */arm64-v8a/*) task_machine=AArch64 ;;
    */x86_64/*) task_machine='Advanced Micro Devices X86-64' ;;
  esac
  "$task_readelf" -h "$task_library" | awk -v expected="$task_machine" '/Machine:/{sub(/^.*Machine: +/, ""); if ($0 != expected) exit 1; found=1} END {if (!found) exit 1}'
  "$task_readelf" -l "$task_library" | awk '/LOAD/{if ($NF != "0x4000" && $NF != "0x10000") exit 1; found=1} END {if (!found) exit 1}'
  "$task_readelf" -d "$task_library" | awk '/NEEDED/{if ($NF != "[libc.so]" && $NF != "[libdl.so]" && $NF != "[libm.so]" && $NF != "[liblog.so]" && $NF != "[libandroid.so]") {print "Unexpected JNI dependency: " $NF > "/dev/stderr"; exit 1}}'
done
# Publish exact native sources/notices with every distributable debug APK.
task_companion="$task_bridge/build/openvpn-source-companion"
# Retain repository-relative layout so the included recipes and SRCDIR/native
# cgo include paths work when the standalone companion is extracted.
rm -rf "$task_companion"
mkdir -p "$task_companion/apps/android/routebridge" "$task_companion/scripts" "$task_companion/upstream"
cp "$task_root/LICENSE-MIT" "$task_root/LICENSE-APACHE" "$task_companion/"
cp "$task_native/downloads/"*.tar.gz "$task_companion/upstream/"
cp "$task_root/scripts/build-android-routebridge.sh" "$task_root/scripts/build-android-openvpn.sh" "$task_companion/scripts/"
cp ./*.go go.mod go.sum THIRD-PARTY-NOTICES.md "$task_companion/apps/android/routebridge/"
cp -R native licenses "$task_companion/apps/android/routebridge/"
(cd "$task_companion" && find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > ../openvpn-source-SHA256SUMS)
mv build/openvpn-source-SHA256SUMS "$task_companion/SHA256SUMS"
tar -czf build/routebridge-openvpn-source.tar.gz -C build openvpn-source-companion
sha256sum build/routebridge.aar build/routebridge-openvpn-source.tar.gz
