#!/usr/bin/env bash
# Build only the owned encrypted fixture packager. Ordinary FFMPEG is unchanged.
set -euo pipefail

if [[ -n "${FFMPEG_ENCRYPTED:-}" ]]; then
  [[ -x "$FFMPEG_ENCRYPTED" ]] || { printf '%s\n' 'FFMPEG_ENCRYPTED must name an executable packager.' >&2; exit 1; }
  exit 0
fi

for tool in cc make curl tar xz sha256sum timeout flock; do
  command -v "$tool" >/dev/null || { printf 'Owned FFmpeg build requires %s, or set FFMPEG_ENCRYPTED.\n' "$tool" >&2; exit 1; }
done

fixture_repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
fixture_tools="$fixture_repo/apps/web/build/tools"
fixture_prefix="$fixture_tools/ffmpeg-8.1"
fixture_archive="$fixture_tools/ffmpeg-8.1.tar.xz"
fixture_sha256=b072aed6871998cce9b36e7774033105ca29e33632be5b6347f3206898e0756a
fixture_recipe=$(sha256sum "${BASH_SOURCE[0]}" | cut -d ' ' -f 1)
fixture_platform="$(uname -s)-$(uname -m)"
mkdir -p -- "$fixture_tools"
[[ ! -L "$fixture_tools" ]] || { printf '%s\n' 'Owned FFmpeg tools directory must not be a symlink.' >&2; exit 1; }
exec 9>"$fixture_tools/.ffmpeg-8.1.lock"
flock -w 360 9

fixture_marker() {
  printf 'tachiai-owned-ffmpeg-v1\nversion=8.1\nsource_sha256=%s\nrecipe_sha256=%s\nplatform=%s\nbinary_sha256=%s\n' \
    "$fixture_sha256" "$fixture_recipe" "$fixture_platform" "$1"
}

if [[ -e "$fixture_prefix" || -L "$fixture_prefix" ]]; then
  if [[ -d "$fixture_prefix" && ! -L "$fixture_prefix" && -f "$fixture_prefix/bin/ffmpeg" && ! -L "$fixture_prefix/bin/ffmpeg" &&
        -x "$fixture_prefix/bin/ffmpeg" && -f "$fixture_prefix/.fixture-build" && ! -L "$fixture_prefix/.fixture-build" ]]; then
    fixture_binary_sha=$(sha256sum "$fixture_prefix/bin/ffmpeg" | cut -d ' ' -f 1)
    if cmp -s "$fixture_prefix/.fixture-build" <(fixture_marker "$fixture_binary_sha"); then
      exit 0
    fi
  fi
  printf '%s\n' 'Owned FFmpeg cache marker mismatch; review/remove apps/web/build/tools/ffmpeg-8.1 and rerun, or set FFMPEG_ENCRYPTED.' >&2
  exit 1
fi

fixture_stage=$(mktemp -d "$fixture_tools/.ffmpeg-8.1.XXXXXXXX")
trap 'rm -rf -- "$fixture_stage"' EXIT
if [[ -e "$fixture_archive" || -L "$fixture_archive" ]]; then
  [[ -f "$fixture_archive" && ! -L "$fixture_archive" ]] || { printf '%s\n' 'Owned FFmpeg archive cache is not a regular file.' >&2; exit 1; }
else
  curl --fail --silent --show-error --location --proto '=https' --proto-redir '=https' \
    --connect-timeout 15 --max-time 120 --max-redirs 2 \
    https://ffmpeg.org/releases/ffmpeg-8.1.tar.xz --output "$fixture_stage/ffmpeg-8.1.tar.xz"
  printf '%s  %s\n' "$fixture_sha256" "$fixture_stage/ffmpeg-8.1.tar.xz" | sha256sum --check --status
  mv -- "$fixture_stage/ffmpeg-8.1.tar.xz" "$fixture_archive"
fi
printf '%s  %s\n' "$fixture_sha256" "$fixture_archive" | sha256sum --check --status || {
  printf '%s\n' 'Owned FFmpeg source archive checksum mismatch.' >&2; exit 1;
}
tar -xJf "$fixture_archive" --no-same-owner -C "$fixture_stage"
(
  cd -- "$fixture_stage/ffmpeg-8.1"
  timeout --kill-after=10s 60 ./configure --prefix="$fixture_stage/install" \
    --disable-everything --disable-autodetect --disable-network --disable-doc --disable-debug \
    --disable-ffplay --disable-ffprobe --disable-x86asm --enable-ffmpeg \
    --enable-protocol=file --enable-demuxer=mov --enable-muxer=dash,mp4 \
    --enable-parser=aac,h264 --enable-decoder=aac,h264 --enable-bsf=aac_adtstoasc,h264_mp4toannexb
  timeout --kill-after=10s 300 make -j2
  timeout --kill-after=10s 30 make install
)
fixture_version=$(timeout --kill-after=10s 10 "$fixture_stage/install/bin/ffmpeg" -version)
[[ "$fixture_version" == 'ffmpeg version 8.1 '* ]] || { printf '%s\n' 'Owned FFmpeg build version mismatch.' >&2; exit 1; }
fixture_binary_sha=$(sha256sum "$fixture_stage/install/bin/ffmpeg" | cut -d ' ' -f 1)
fixture_marker "$fixture_binary_sha" > "$fixture_stage/install/.fixture-build"
mv -- "$fixture_stage/install" "$fixture_prefix"
