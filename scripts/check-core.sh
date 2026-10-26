#!/usr/bin/env bash
set -euo pipefail

task_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$task_root"
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$task_root/build/core/target}"
cargo fmt --all --check
cargo clippy --locked --workspace --all-targets -- -D warnings
cargo test --locked --workspace
bash scripts/build-core-bindings.sh
bash scripts/build-web-core.sh
node packages/core-web/smoke.mjs
