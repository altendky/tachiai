#!/usr/bin/env bash
set -euo pipefail
task_fixture="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
task_cache="${1:?Pass an absolute disposable source cache}"
[[ "$task_cache" = /* ]]
mkdir -p "$task_cache"
task_partial=''
trap 'if [[ -n "$task_partial" ]]; then rm -f -- "$task_partial"; fi' EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
while IFS=$'\t' read -r task_name task_url task_sha; do
  task_archive="$task_cache/$task_name"
  if [[ ! -f "$task_archive" ]]; then
    task_partial="$task_archive.part"
    # Three 60-second attempts and two 2-second delays: at most 184 seconds
    # of transfer/delay budget per archive, without curl's extra retries.
    for task_attempt in 1 2 3; do
      rm -f -- "$task_partial"
      printf 'Downloading pinned archive: %s (attempt %s/3)\n' "$task_name" "$task_attempt"
      if curl --disable --fail --location --proto '=https' --proto-redir '=https' \
        --tlsv1.2 --connect-timeout 10 --max-time 60 \
        "$task_url" --output "$task_partial"; then
        if ! printf '%s  %s\n' "$task_sha" "$task_partial" | sha256sum --check --status; then
          printf 'Checksum mismatch for pinned archive: %s\n' "$task_name" >&2
          exit 1
        fi
        mv "$task_partial" "$task_archive"
        task_partial=''
        break
      else
        task_status=$?
        rm -f -- "$task_partial"
        if [[ "$task_attempt" = 3 ]]; then
          printf 'Failed pinned archive: %s after 3 attempts (curl exit %s)\n' "$task_name" "$task_status" >&2
          exit "$task_status"
        fi
        sleep 2
      fi
    done
  fi
  if ! printf '%s  %s\n' "$task_sha" "$task_archive" | sha256sum --check --status; then
    printf 'Checksum mismatch for cached pinned archive: %s\n' "$task_name" >&2
    exit 1
  fi
done < "$task_fixture/sources.tsv"
printf 'Verified pinned official source archives: %s\n' "$task_cache"
