#!/usr/bin/env bash
set -euo pipefail
task_fixture="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
task_cache="${1:?Pass an absolute disposable source cache}"
[[ "$task_cache" = /* ]]
mkdir -p "$task_cache"
while IFS=$'\t' read -r task_name task_url task_sha; do
  task_archive="$task_cache/$task_name"
  if [[ ! -f "$task_archive" ]]; then
    curl --fail --location --proto '=https' --tlsv1.2 --max-time 60 \
      "$task_url" --output "$task_archive.part"
    printf '%s  %s\n' "$task_sha" "$task_archive.part" | sha256sum --check --status
    mv "$task_archive.part" "$task_archive"
  fi
  printf '%s  %s\n' "$task_sha" "$task_archive" | sha256sum --check --status
done < "$task_fixture/sources.tsv"
printf 'Verified pinned official source archives: %s\n' "$task_cache"
