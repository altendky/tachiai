#!/usr/bin/env bash
set -euo pipefail

# Build the replaceable LGPL dependency closure, never a system VPN executable.
task_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
task_fixture="$task_root/apps/android/openconnect-fixture"
: "${ANDROID_NDK_ROOT:?Use the existing read-only Android NDK}"
task_ndk="$ANDROID_NDK_ROOT"
test "$(sed -n 's/^Pkg.Revision = //p' "$task_ndk/source.properties")" = 30.0.16248370
task_build="${TACHIAI_OPENCONNECT_BUILD_ROOT:-$task_root/apps/android/routebridge/build/openconnect-native}"
mkdir -p "$task_build/downloads"
task_build="$(cd "$task_build" && pwd)"
# Keep generated upstream trees outside the application's Go package discovery.
printf 'module net.fstab.tachiai/openconnect-native-build\n' > "$task_build/go.mod"
"$task_fixture/fetch-sources.sh" "$task_build/downloads"
task_inputs="$task_build/current-native-inputs.sha256"
(
  cd "$task_fixture"
  sha256sum sources.tsv patches/*.patch adapter.c adapter.h memory_file.c memory_file.h \
    fetch-sources.sh build-android-deps.sh refresh-android-openconnect.sh \
    normalize-android-libs.py verify-android-libs.py
) > "$task_inputs"
for task_abi in arm64-v8a x86_64; do
  case "$task_abi" in arm64-v8a) task_arch=aarch64 ;; x86_64) task_arch=x86_64 ;; esac
  task_target="$task_build/$task_abi"
  task_reuse=false
  if test -f "$task_target/native-inputs.sha256" && \
    cmp -s "$task_inputs" "$task_target/native-inputs.sha256" && \
    cmp -s "$task_ndk/source.properties" "$task_target/ndk-source.properties" && \
    test "$(< "$task_target/ABI")" = "$task_arch"; then
    (cd "$task_target" && sha256sum --check --status build-inputs.sha256 && \
      sha256sum --check --status native-input-fingerprint.txt)
    (cd "$task_target/prefix/lib" && sha256sum --check --status SHA256SUMS)
    task_reuse=true
  fi
  if ! "$task_reuse"; then
    task_log="$task_build/$task_abi-build.log"
    bash "$task_fixture/build-android-deps.sh" "$task_ndk" "$task_build/downloads" "$task_arch" | tee "$task_log"
    task_dependency="$(sed -n 's/^Native dependency build: //p' "$task_log")"
    test -d "$task_dependency/prefix"
    uv run "$task_fixture/normalize-android-libs.py" "$task_dependency" "$task_ndk" "$task_arch"
    task_refresh_log="$task_build/$task_abi-refresh.log"
    bash "$task_fixture/refresh-android-openconnect.sh" "$task_ndk" "$task_build/downloads" "$task_arch" "$task_dependency" | tee "$task_refresh_log"
    task_fresh="$(sed -n 's/^Fresh Android OpenConnect build: //p' "$task_refresh_log")"
    test -d "$task_fresh/jni-libs"
    mkdir -p "$task_target/prefix/lib"
    cp "$task_fresh/jni-libs/"*.so "$task_fresh/jni-libs/SHA256SUMS" "$task_target/prefix/lib/"
    cp "$task_fresh/native-inputs.sha256" "$task_fresh/ndk-source.properties" \
      "$task_fresh/ABI" "$task_fresh/build-inputs.sha256" "$task_fresh/native-input-fingerprint.txt" "$task_target/"
    printf '%s\n' "$task_dependency" "$task_fresh" > "$task_target/corresponding-builds.txt"
  else
    printf 'Reusing verified OpenConnect native inputs for %s\n' "$task_abi"
  fi
  uv run "$task_fixture/verify-android-libs.py" "$task_target/prefix/lib" "$task_ndk" "$task_arch"
done
