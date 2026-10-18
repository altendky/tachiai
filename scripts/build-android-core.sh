#!/usr/bin/env bash
set -euo pipefail

task_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$task_root"
: "${ANDROID_HOME:?Use the documented Android SDK build environment}"
task_ndk="$ANDROID_HOME/ndk/30.0.16248370"
test -f "$task_ndk/source.properties"
case "$(uname -s)" in
  Linux) task_host=linux-x86_64 ;;
  *) printf '%s\n' 'Use the documented Linux Android build environment' >&2; exit 1 ;;
esac
task_tools="$task_ndk/toolchains/llvm/prebuilt/$task_host/bin"
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$task_root/build/core/target}"
bash scripts/build-core-bindings.sh

for task_abi in arm64-v8a x86_64; do
  case "$task_abi" in
    arm64-v8a)
      task_target=aarch64-linux-android
      task_linker=CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER
      task_machine=AArch64
      ;;
    x86_64)
      task_target=x86_64-linux-android
      task_linker=CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER
      task_machine='Advanced Micro Devices X86-64'
      ;;
  esac
  env "$task_linker=$task_tools/${task_target}26-clang" \
    cargo rustc --locked --release -p tachiai-core-ffi --target "$task_target" -- \
      -C link-arg=-Wl,-z,max-page-size=16384 \
      -C link-arg=-Wl,-z,common-page-size=16384 \
      -C link-arg=-Wl,--no-undefined
  task_destination="$task_root/build/core/native/android/$task_abi"
  mkdir -p "$task_destination"
  cp "$CARGO_TARGET_DIR/$task_target/release/libtachiai_core_ffi.so" "$task_destination/"
  task_library="$task_destination/libtachiai_core_ffi.so"
  "$task_tools/llvm-readelf" -h "$task_library" | awk -v expected="$task_machine" \
    '/Machine:/{sub(/^.*Machine: +/, ""); if ($0 != expected) exit 1; found=1} END {if (!found) exit 1}'
  "$task_tools/llvm-readelf" -W -l "$task_library" | awk \
    '/LOAD/{if ($NF != "0x4000" && $NF != "0x10000") exit 1; found=1} END {if (!found) exit 1}'
  "$task_tools/llvm-readelf" -d "$task_library" | awk \
    '/NEEDED/{if ($NF !~ /^\[lib(c|dl|m|log|android)\.so\]$/) {print "Unexpected core dependency: " $NF > "/dev/stderr"; exit 1}}'
  sha256sum "$task_library"
done
