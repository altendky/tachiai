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
cd "$task_bridge"
go test ./...
gomobile bind -target=android/arm64,android/amd64 -androidapi=26 \
  -trimpath -o build/routebridge.aar .
# Package redistributable notices in the AAR/APK assets, not just source docs.
mkdir -p build/license-payload/assets/routebridge-licenses
cp licenses/*.txt THIRD-PARTY-NOTICES.md build/license-payload/assets/routebridge-licenses/
(cd build/license-payload && zip -q -r ../routebridge.aar assets)
# Every packaged ABI must support Android's 16-KiB page-size requirement.
task_extract="$task_bridge/build/verify"
mkdir -p "$task_extract"
unzip -o -q build/routebridge.aar 'jni/*' -d "$task_extract"
task_readelf="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
for task_library in "$task_extract/jni/arm64-v8a/libgojni.so" "$task_extract/jni/x86_64/libgojni.so"; do
  test -f "$task_library"
  "$task_readelf" -l "$task_library" | awk '/LOAD/{if ($NF != "0x4000" && $NF != "0x10000") exit 1; found=1} END {if (!found) exit 1}'
done
sha256sum build/routebridge.aar
