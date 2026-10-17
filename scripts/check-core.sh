#!/usr/bin/env bash
set -euo pipefail

task_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$task_root"
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$task_root/build/core/target}"
cargo fmt --all --check
cargo clippy --locked --workspace --all-targets -- -D warnings
cargo test --locked --workspace
bash scripts/build-core-bindings.sh
cargo build --locked --release -p tachiai-core-web --target wasm32-unknown-unknown
mkdir -p build/core/web
wasm-bindgen "$CARGO_TARGET_DIR/wasm32-unknown-unknown/release/tachiai_core_web.wasm" \
  --target web --out-dir build/core/web --out-name tachiai_core_web
node packages/core-web/smoke.mjs
