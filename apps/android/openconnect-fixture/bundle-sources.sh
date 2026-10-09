#!/usr/bin/env bash
set -euo pipefail
task_fixture="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
task_cache="${1:?Pass the absolute verified source cache}"
[[ "$task_cache" = /* ]]
task_output="$task_cache/openconnect-source-companion.tar.xz"
[[ ! -e "$task_output" ]]
"$task_fixture/fetch-sources.sh" "$task_cache"
task_stage="$(mktemp -d "$task_cache/source-companion.XXXXXXXXXX")"
task_root="$task_stage/openconnect-source-companion"
mkdir -p "$task_root/archives" "$task_root/adapter"
while IFS=$'\t' read -r task_name _task_url _task_sha; do
  cp "$task_cache/$task_name" "$task_root/archives/"
done < "$task_fixture/sources.tsv"
cp -R "$task_fixture/". "$task_root/adapter/"
tar -cJf "$task_output" -C "$task_stage" openconnect-source-companion
sha256sum "$task_output" > "$task_output.sha256"
rm -r "$task_stage"
printf 'Exact source/patch/build/license companion: %s\n' "$task_output"
