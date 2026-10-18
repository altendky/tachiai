#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Generate or verify owned, unprotected media for the test-APK-only fixture.

No downloads, devices, credentials or application stores are accessed. Encoding
is explicit; normal invocation verifies committed files rather than rewriting
them. FFmpeg builds may produce different bytes: review and commit the matching
manifest when deliberately regenerating with another encoder version.
"""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "apps/android/app/src/androidTest/assets/controlled-mirroring"
NAMES = ("a.mp4", "b.mp4")


def run(arguments):
    return subprocess.run(arguments, check=True, capture_output=True, text=True).stdout


def verify():
    manifest = json.loads((ASSETS / "media.json").read_text())
    assert manifest["schema"] == 1
    assert set(manifest["files"]) == set(NAMES)
    for name in NAMES:
        data = (ASSETS / name).read_bytes()
        entry = manifest["files"][name]
        assert len(data) == entry["bytes"]
        assert hashlib.sha256(data).hexdigest() == entry["sha256"]
        assert entry["video"] == "h264" and entry["audio"] == "aac"
        assert entry["duration_seconds"] == 60
        print(f"{name}: SHA-256 and {len(data)} bytes match committed manifest")


def generate(args):
    ASSETS.mkdir(parents=True, exist_ok=True)
    version = run([args.ffmpeg, "-version"]).splitlines()[0]
    manifest = {"schema": 1, "generator": version, "files": {}}
    for name, label, frequency, offset, hue in (
        ("a.mp4", "A", 440, 0, 0), ("b.mp4", "B", 880, 0.5, 90)
    ):
        # Each generated source has motion, its own frame/time counter, and
        # a quiet 180-ms tone pulse per second. B pulses half a second later.
        video = (
            f"hue=h={hue},drawtext=fontfile='{args.font}':"
            f"text='{label} {frequency} Hz  frame %{{eif\\:n\\:d\\:4}}':"
            "fontsize=28:fontcolor=white:box=1:boxcolor=black@0.8:x=12:y=12,"
            f"drawtext=fontfile='{args.font}':text='%{{pts\\:hms}}':"
            "fontsize=28:fontcolor=white:box=1:boxcolor=black@0.8:x=12:y=h-45"
        )
        audio = f"aevalsrc=0.15*sin(2*PI*{frequency}*t)*lt(mod(t+{offset}\\,1)\\,0.18):s=48000:d=60"
        arguments = [
            args.ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=24:duration=60",
            "-f", "lavfi", "-i", audio, "-vf", video,
            "-map_metadata", "-1", "-fflags", "+bitexact",
            "-c:v", "libx264", "-threads:v", "1", "-preset", "slow", "-crf", "32",
            "-profile:v", "baseline", "-pix_fmt", "yuv420p", "-g", "24",
            "-keyint_min", "24", "-sc_threshold", "0", "-flags:v", "+bitexact",
            "-c:a", "aac", "-b:a", "64k", "-ar", "48000", "-ac", "1",
            "-flags:a", "+bitexact", "-movflags", "+faststart", "-t", "60",
            str(ASSETS / name),
        ]
        run(arguments)
        probe = json.loads(run([
            args.ffprobe, "-v", "error", "-show_streams", "-show_format",
            "-of", "json", str(ASSETS / name),
        ]))
        streams = {stream["codec_type"]: stream for stream in probe["streams"]}
        assert set(streams) == {"audio", "video"}
        assert streams["video"]["codec_name"] == "h264"
        assert streams["audio"]["codec_name"] == "aac"
        assert streams["video"]["width"] == 640 and streams["video"]["height"] == 360
        assert float(probe["format"]["duration"]) == 60
        data = (ASSETS / name).read_bytes()
        manifest["files"][name] = {
            "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data),
            "video": "h264", "audio": "aac", "duration_seconds": 60,
            "width": 640, "height": 360, "frames_per_second": 24,
            "tone_hz": frequency, "pulse_offset_seconds": offset,
        }
    (ASSETS / "media.json").write_text(json.dumps(manifest, indent=2) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--generate", action="store_true")
    parser.add_argument("--ffmpeg", default="ffmpeg")
    parser.add_argument("--ffprobe", default="ffprobe")
    parser.add_argument("--font", default="/usr/share/fonts/truetype/freefont/FreeMonoBold.ttf")
    args = parser.parse_args()
    if args.generate:
        assert Path(args.font).is_file(), "Supply an installed font with --font"
        generate(args)
    verify()


if __name__ == "__main__":
    main()
