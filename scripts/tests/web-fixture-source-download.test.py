#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Exercise the actual fixture downloader without network or native compilation."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
PAYLOAD = b"owned synthetic archive; deliberately never extracted\n"
PIN = "b072aed6871998cce9b36e7774033105ca29e33632be5b6347f3206898e0756a"

FAKE_CURL = r'''
import json, os, signal, sys
from pathlib import Path
args = sys.argv[1:]
output = Path(args[args.index("--output") + 1])
state = Path(os.environ["FIXTURE_DOWNLOAD_STATE"])
value = json.loads(state.read_text())
attempt = len(value["calls"])
value["calls"].append({"args": args, "partial_existed": output.exists()})
state.write_text(json.dumps(value))
plan = json.loads(os.environ["FIXTURE_DOWNLOAD_PLAN"])
code = plan[min(attempt, len(plan) - 1)]
output.write_bytes(b"incorrect bytes" if code == "bad" else b"partial bytes" if code else
                   b"owned synthetic archive; deliberately never extracted\n")
if code == "signal":
    os.kill(os.getppid(), signal.SIGTERM)
    sys.exit(0)
sys.exit(0 if code == "bad" else code)
'''
FAKE_SLEEP = r'''
import json, os, sys
from pathlib import Path
state = Path(os.environ["FIXTURE_DOWNLOAD_STATE"])
value = json.loads(state.read_text())
value["delays"].append(sys.argv[1:])
state.write_text(json.dumps(value))
'''
FAKE_TAR = r'''
import json, os, sys
from pathlib import Path
state = Path(os.environ["FIXTURE_DOWNLOAD_STATE"])
value = json.loads(state.read_text())
value["tar"] += 1
state.write_text(json.dumps(value))
sys.exit(91)
'''


class DownloadTests(unittest.TestCase):
    def setUp(self):
        Path("/tmp/agents").mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix="web-fixture-download-", dir="/tmp/agents")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.script = self.root / "scripts/build-web-fixture-ffmpeg.sh"
        self.script.parent.mkdir()
        source = (ROOT / "scripts/build-web-fixture-ffmpeg.sh").read_text()
        self.assertEqual(source.count(PIN), 1)
        self.script.write_text(source.replace(PIN, hashlib.sha256(PAYLOAD).hexdigest()))
        self.cache = self.root / "apps/web/build/tools"
        self.cache.mkdir(parents=True)
        self.archive = self.cache / "ffmpeg-8.1.tar.xz"
        self.bin = self.root / "fake-bin"
        self.bin.mkdir()
        for name, body in (("curl", FAKE_CURL), ("sleep", FAKE_SLEEP), ("tar", FAKE_TAR)):
            path = self.bin / name
            path.write_text(f"#!{sys.executable}\n" + body)
            path.chmod(0o755)
        self.state = self.root / "state.json"
        self.state.write_text(json.dumps({"calls": [], "delays": [], "tar": 0}))

    def run_builder(self, plan=None, override=None):
        environment = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
                           FIXTURE_DOWNLOAD_STATE=str(self.state), FIXTURE_DOWNLOAD_PLAN=json.dumps(plan or [0]))
        environment.pop("FFMPEG_ENCRYPTED", None)
        if override is not None:
            environment["FFMPEG_ENCRYPTED"] = str(override)
        result = subprocess.run(["bash", str(self.script)], env=environment, capture_output=True, text=True, timeout=8)
        self.assertEqual([path for path in self.cache.glob(".ffmpeg-8.1.*") if path.name != ".ffmpeg-8.1.lock"],
                         [], "Unverified staging survived")
        return result, json.loads(self.state.read_text())

    def test_timeout_retries_use_fresh_bytes_same_upstream_pin_and_security_bounds(self):
        result, state = self.run_builder([7, 28, 0])
        self.assertEqual(result.returncode, 91, result.stderr)  # Fake extraction stops before configure/build.
        self.assertEqual(self.archive.read_bytes(), PAYLOAD)
        self.assertEqual(state["delays"], [["2"], ["2"]])
        self.assertEqual(state["tar"], 1)
        self.assertEqual(len(state["calls"]), 3)
        for call in state["calls"]:
            args = call["args"]
            self.assertEqual(args[0], "--disable")
            self.assertFalse(call["partial_existed"])
            self.assertIn("https://ffmpeg.org/releases/ffmpeg-8.1.tar.xz", args)
            for option, value in (("--proto", "=https"), ("--proto-redir", "=https"),
                                  ("--connect-timeout", "15"), ("--max-time", "120"), ("--max-redirs", "2")):
                self.assertEqual(args[args.index(option) + 1], value)
            self.assertIn("--fail", args); self.assertNotIn("--insecure", args)

    def test_transport_exhaustion_preserves_last_failure_and_never_promotes_partial_bytes(self):
        result, state = self.run_builder([6, 7, 28])
        self.assertEqual(result.returncode, 28)
        self.assertEqual(len(state["calls"]), 3); self.assertEqual(state["tar"], 0)
        self.assertFalse(self.archive.exists())
        self.assertIn("after 3 attempts (curl exit 28)", result.stderr)

    def test_tls_http_and_local_failures_are_not_retried(self):
        for code in (22, 23, 35, 60):
            with self.subTest(code=code):
                self.state.write_text(json.dumps({"calls": [], "delays": [], "tar": 0}))
                result, state = self.run_builder([code, 0])
                self.assertEqual(result.returncode, code)
                self.assertEqual(len(state["calls"]), 1); self.assertEqual(state["delays"], [])
                self.assertFalse(self.archive.exists()); self.assertEqual(state["tar"], 0)

    def test_checksum_mismatch_is_not_retried_or_promoted(self):
        result, state = self.run_builder(["bad", 0])
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(state["calls"]), 1); self.assertEqual(state["delays"], [])
        self.assertFalse(self.archive.exists()); self.assertEqual(state["tar"], 0)

    def test_verified_cache_is_reused_and_corrupt_cache_is_preserved_without_download(self):
        self.archive.write_bytes(PAYLOAD)
        result, state = self.run_builder()
        self.assertEqual(result.returncode, 91); self.assertEqual(state["calls"], [])
        self.archive.write_bytes(b"corrupt existing cache")
        self.state.write_text(json.dumps({"calls": [], "delays": [], "tar": 0}))
        result, state = self.run_builder()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.archive.read_bytes(), b"corrupt existing cache")
        self.assertEqual(state["calls"], []); self.assertEqual(state["tar"], 0)

    def test_termination_removes_unverified_staging_without_promoting_an_archive(self):
        result, state = self.run_builder(["signal"])
        self.assertEqual(result.returncode, 143)
        self.assertEqual(len(state["calls"]), 1); self.assertEqual(state["tar"], 0)
        self.assertFalse(self.archive.exists())

    def test_explicit_packager_override_retains_its_existing_no_download_path(self):
        executable = self.bin / "packager"
        executable.write_text("#!/bin/sh\nexit 0\n")
        executable.chmod(0o755)
        result, state = self.run_builder(override=executable)
        self.assertEqual(result.returncode, 0)
        self.assertEqual(state, {"calls": [], "delays": [], "tar": 0})


if __name__ == "__main__":
    unittest.main()
