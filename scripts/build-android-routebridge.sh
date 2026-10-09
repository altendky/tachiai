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
bash "$task_root/scripts/build-android-openconnect.sh"
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
task_openconnect="${TACHIAI_OPENCONNECT_BUILD_ROOT:-$task_bridge/build/openconnect-native}"
task_openconnect="$(cd "$task_openconnect" && pwd)"
for task_abi in arm64-v8a x86_64; do
  task_target="$task_bridge/build/openconnect-native/$task_abi/prefix/lib"
  mkdir -p "$task_target"
  if test "$(cd "$task_target" && pwd)" != "$task_openconnect/$task_abi/prefix/lib"; then
    # Only this builder's generated cgo staging is replaced; never the source
    # cache. Stale libraries must not enter the JNI link or final AAR.
    rm -rf "$task_target"
    mkdir -p "$task_target"
    cp "$task_openconnect/$task_abi/prefix/lib/"*.so "$task_target/"
  fi
done
cd "$task_bridge"
# Discard only this builder's generated staging from older flattened layouts
# before package discovery. The final companion retains its nested go.mod.
rm -rf build/openvpn-source-companion
rm -rf build/openconnect-source-companion
rm -rf build/native-source-companion
go test ./...
# cgo's default allowlist excludes this linker visibility flag. Admit only
# the exact flag used by the reviewed static native dependency boundary.
export CGO_LDFLAGS_ALLOW='^-Wl,--exclude-libs,ALL$'
# Go's cache does not detect changed external C archives. Include their content
# identity in the cgo compilation flags so a native edit always relinks JNI.
task_native_identity="$(sha256sum "$task_bridge/build/openvpn-native/"*/prefix/lib/*.a "$task_bridge/build/openconnect-native/"*/prefix/lib/*.so | awk '{print $1}' | sha256sum | awk '{print $1}')"
export CGO_CFLAGS="${CGO_CFLAGS:+$CGO_CFLAGS }-DTACHIAI_OPENVPN_NATIVE_INPUTS_$task_native_identity"
gomobile bind -target=android/arm64,android/amd64 -androidapi=26 \
  -tags=openvpn_validation,openconnect_validation -trimpath -o build/routebridge.aar .
# Package redistributable notices in the AAR/APK assets, not just source docs.
rm -rf build/license-payload
mkdir -p build/license-payload/assets/routebridge-licenses
cp licenses/*.txt THIRD-PARTY-NOTICES.md build/license-payload/assets/routebridge-licenses/
cp "$task_root/LICENSE-MIT" "$task_root/LICENSE-APACHE" build/license-payload/assets/routebridge-licenses/
mkdir -p build/license-payload/assets/routebridge-licenses/openconnect build/license-payload/jni
cp "$task_root/apps/android/openconnect-fixture/COPYING.LGPL" \
  build/license-payload/assets/routebridge-licenses/openconnect/OPENCONNECT-LGPL-2.1.txt
cp "$task_root/apps/android/openconnect-fixture/notices/"*.txt build/license-payload/assets/routebridge-licenses/openconnect/
for task_abi in arm64-v8a x86_64; do
  mkdir -p "build/license-payload/jni/$task_abi"
  cp "$task_bridge/build/openconnect-native/$task_abi/prefix/lib/"*.so "build/license-payload/jni/$task_abi/"
done
(cd build/license-payload && zip -q -r ../routebridge.aar assets jni)
# Every packaged ABI must support Android's 16-KiB page-size requirement.
task_extract="$task_bridge/build/verify"
rm -rf "$task_extract" build/verify-openconnect
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
  "$task_readelf" -d "$task_library" | awk '/NEEDED/{if ($NF !~ /^\[lib(c|dl|m|log|android|gmp|nettle|hogweed|gnutls|xml2|z|openconnect|tachiai_openconnect)\.so\]$/) {print "Unexpected JNI dependency: " $NF > "/dev/stderr"; exit 1}}'
done
for task_abi in arm64-v8a x86_64; do
  case "$task_abi" in arm64-v8a) task_arch=aarch64 ;; x86_64) task_arch=x86_64 ;; esac
  task_package="$task_bridge/build/verify-openconnect/$task_abi"
  mkdir -p "$task_package"
  # Verify the extracted bytes, not only the source cache that supplied them.
  for task_library in "$task_extract/jni/$task_abi/"*.so*; do
    if test "$(basename "$task_library")" != libgojni.so; then
      cp "$task_library" "$task_package/"
    fi
  done
  uv run "$task_root/apps/android/openconnect-fixture/verify-android-libs.py" "$task_package" "$ANDROID_NDK_HOME" "$task_arch"
  cmp "$task_package/SHA256SUMS" "$task_openconnect/$task_abi/prefix/lib/SHA256SUMS"
done
# LGPL application/relink material keeps the exact Android source and both
# native transport recipes in repository-relative layout. No build outputs,
# local SDK settings, credentials or signing keys belong in this companion.
task_companion="$task_bridge/build/native-source-companion"
mkdir -p "$task_companion/upstream/openconnect" "$task_companion/upstream/openvpn"
while IFS=$'\t' read -r task_archive _task_url _task_hash; do
  cp "$task_openconnect/downloads/$task_archive" "$task_companion/upstream/openconnect/"
done < "$task_root/apps/android/openconnect-fixture/sources.tsv"
for task_archive in openvpn3 asio openssl fmt lz4; do
  cp "$task_native/downloads/$task_archive.tar.gz" "$task_companion/upstream/openvpn/"
done
(cd "$task_root" && tar --exclude='*/build' --exclude='*/.gradle' --exclude='*/.kotlin' --exclude='*/local.properties' \
  --exclude='*/__pycache__' --exclude='*.pyc' -cf - apps/android scripts LICENSE-MIT LICENSE-APACHE mise.toml mise.lock) | \
  tar -xf - -C "$task_companion"
cp "$task_root/apps/android/openconnect-fixture/RELINKING.md" "$task_companion/RELINKING.md"
mkdir -p "$task_companion/native-artifact-identities"
for task_abi in arm64-v8a x86_64; do
  mkdir -p "$task_companion/native-artifact-identities/$task_abi"
  cp "$task_openconnect/$task_abi/native-inputs.sha256" "$task_openconnect/$task_abi/ndk-source.properties" \
    "$task_openconnect/$task_abi/ABI" "$task_openconnect/$task_abi/build-inputs.sha256" \
    "$task_openconnect/$task_abi/native-input-fingerprint.txt" \
    "$task_openconnect/$task_abi/prefix/lib/SHA256SUMS" "$task_companion/native-artifact-identities/$task_abi/"
done
(cd "$task_companion" && find . -type f ! -path ./SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > ../openconnect-source-SHA256SUMS)
mv build/openconnect-source-SHA256SUMS "$task_companion/SHA256SUMS"
tar -cJf build/routebridge-native-source.tar.xz -C build native-source-companion
sha256sum build/routebridge.aar build/routebridge-native-source.tar.xz
