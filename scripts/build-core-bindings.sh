#!/usr/bin/env bash
set -euo pipefail

task_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$task_root"
test "$(rustc --version | cut -d ' ' -f 2)" = 1.98.1
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$task_root/build/core/target}"
case "$(uname -s)" in
  Linux) task_library=libtachiai_core_ffi.so ;;
  Darwin) task_library=libtachiai_core_ffi.dylib ;;
  *) printf '%s\n' 'Native binding generation currently requires Linux or macOS' >&2; exit 1 ;;
esac

cargo build --locked --release -p tachiai-core-ffi -p tachiai-core-bindgen
mkdir -p build/core/native/host build/core/bindings/kotlin build/core/bindings/swift
cp "$CARGO_TARGET_DIR/release/$task_library" "build/core/native/host/$task_library"
for task_language in kotlin swift; do
  "$CARGO_TARGET_DIR/release/tachiai-core-bindgen" generate \
    --library "$CARGO_TARGET_DIR/release/$task_library" \
    --language "$task_language" --out-dir "build/core/bindings/$task_language" --no-format
done
uv run packages/core-ffi/collect-licenses.py
