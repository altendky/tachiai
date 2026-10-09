#!/usr/bin/env bash
set -euo pipefail
task_fixture="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
task_ndk="${1:?Pass the existing read-only NDK path}"
task_cache="${2:?Pass an absolute disposable artifact cache}"
[[ "$task_ndk" = /* && "$task_cache" = /* ]]
mkdir -p "$task_cache"
task_tools="$task_ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
for task_abi in aarch64 x86_64; do
  task_library="$task_cache/memory-file-$task_abi.so"
  "$task_tools/$task_abi-linux-android26-clang" -std=c11 \
    -Wall -Wextra -Werror -Wpedantic -fPIC -shared \
    -Wl,-z,max-page-size=16384 "$task_fixture/memory_file.c" -o "$task_library"
  "$task_tools/llvm-readelf" --program-headers --dyn-syms --wide "$task_library" > "$task_library.elf.txt"
  # The named bionic memfd_create API30 symbol must never be required.
  if "$task_tools/llvm-nm" --undefined-only "$task_library" | rg -q 'memfd_create'; then exit 1; fi
  "$task_tools/llvm-readelf" --program-headers --wide "$task_library" | \
    awk '/LOAD/ { seen=1; if ($NF != "0x4000") exit 1 } END { if (!seen) exit 1 }'
done
printf 'PASS: API26 anonymous-FD helper arm64/x86_64 links through syscall; 16KiB LOAD alignment. Runtime support untested.\n'
