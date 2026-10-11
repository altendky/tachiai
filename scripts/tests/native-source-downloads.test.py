#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Run the real downloader with fake curl/sleep; no network or native builds."""

import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
FIXTURE = ROOT / "apps/android/openconnect-fixture"
PAYLOAD = b"synthetic pinned archive bytes; never extracted\n"
HASH = hashlib.sha256(PAYLOAD).hexdigest()

FAKE_CURL = r'''
import json
import os
from pathlib import Path
import sys

args = sys.argv[1:]
output = Path(args[args.index("--output") + 1])
state_path = Path(os.environ["TEST_DOWNLOAD_STATE"])
state = json.loads(state_path.read_text())
name = output.name.removesuffix(".part")
attempt = sum(call["name"] == name for call in state["calls"])
state["calls"].append({"name": name, "args": args, "partial_existed": output.exists()})
state_path.write_text(json.dumps(state))
plan = json.loads(os.environ["TEST_DOWNLOAD_PLAN"])
sequence = plan.get(name, [0])
code = sequence[min(attempt, len(sequence) - 1)]
if code == "signal":
    import signal
    output.write_bytes(b"partial before termination")
    os.kill(os.getppid(), signal.SIGTERM)
    sys.exit(0)
output.write_bytes(b"incorrect bytes" if code == "bad" else
                   b"partial failed download" if code else
                   b"synthetic pinned archive bytes; never extracted\n")
sys.exit(0 if code == "bad" else code)
'''

FAKE_SLEEP = r'''
import json
import os
from pathlib import Path
import sys

path = Path(os.environ["TEST_DOWNLOAD_STATE"])
state = json.loads(path.read_text())
state["delays"].append(sys.argv[1:])
path.write_text(json.dumps(state))
'''


class DownloadTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="native-download-", dir="/tmp/agents")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.fixture = self.root / "fixture"
        self.fixture.mkdir()
        shutil.copyfile(FIXTURE / "fetch-sources.sh", self.fixture / "fetch-sources.sh")
        self.rows = [line.split("\t") for line in (FIXTURE / "sources.tsv").read_text().splitlines()]
        # Keep real pinned names/URLs, replacing only hashes for in-memory fake bytes.
        (self.fixture / "sources.tsv").write_text("".join(f"{name}\t{url}\t{HASH}\n" for name, url, _ in self.rows))
        self.cache = self.root / "cache with spaces"
        self.cache.mkdir()
        self.bin = self.root / "bin"
        self.bin.mkdir()
        for name, body in (("curl", FAKE_CURL), ("sleep", FAKE_SLEEP)):
            script = self.bin / name
            script.write_text(f"#!{sys.executable}\n" + body)
            script.chmod(0o755)
        self.state = self.root / "state.json"
        self.state.write_text(json.dumps({"calls": [], "delays": []}))

    def run_download(self, plan=None):
        environment = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
                           TEST_DOWNLOAD_STATE=str(self.state), TEST_DOWNLOAD_PLAN=json.dumps(plan or {}))
        result = subprocess.run(["bash", str(self.fixture / "fetch-sources.sh"), str(self.cache)],
                                env=environment, capture_output=True, text=True, timeout=8)
        return result, json.loads(self.state.read_text())

    def assert_no_partial(self):
        self.assertEqual(list(self.cache.glob("*.part")), [])

    def test_all_archives_preserve_urls_security_flags_and_verified_bytes(self):
        result, state = self.run_download()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(state["calls"]), len(self.rows))
        self.assertEqual(state["delays"], [])
        for row, call in zip(self.rows, state["calls"], strict=True):
            name, url, _ = row
            self.assertEqual(call["name"], name)
            self.assertEqual(call["args"][0], "--disable")
            self.assertIn(url, call["args"])
            for option, value in (("--proto", "=https"), ("--proto-redir", "=https"),
                                  ("--connect-timeout", "10"), ("--max-time", "60")):
                self.assertEqual(call["args"][call["args"].index(option) + 1], value)
            for flag in ("--fail", "--location", "--tlsv1.2"):
                self.assertIn(flag, call["args"])
            self.assertNotIn("--insecure", call["args"])
            self.assertNotIn("--retry", call["args"])
            self.assertFalse(call["partial_existed"])
            self.assertIn(f"{name} (attempt 1/3)", result.stdout)
            self.assertEqual((self.cache / name).read_bytes(), PAYLOAD)
        self.assert_no_partial()

    def test_dns_and_timeout_then_success_are_bounded_and_remove_partial_bytes(self):
        name = self.rows[0][0]
        (self.cache / (name + ".part")).write_bytes(b"stale previous run")
        result, state = self.run_download({name: [6, 28, 0]})
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = [call for call in state["calls"] if call["name"] == name]
        self.assertEqual(len(calls), 3)
        self.assertTrue(all(not call["partial_existed"] for call in calls))
        self.assertEqual(state["delays"], [["2"], ["2"]])
        self.assertEqual((self.cache / name).read_bytes(), PAYLOAD)
        self.assert_no_partial()

    def test_exhaustion_preserves_last_exit_names_archive_and_stops_queue(self):
        name = self.rows[1][0]
        result, state = self.run_download({name: [6, 7, 28]})
        self.assertEqual(result.returncode, 28)
        self.assertEqual([call["name"] for call in state["calls"]], [self.rows[0][0], name, name, name])
        self.assertEqual(state["delays"], [["2"], ["2"]])
        self.assertIn(f"Failed pinned archive: {name} after 3 attempts (curl exit 28)", result.stderr)
        self.assertEqual((self.cache / self.rows[0][0]).read_bytes(), PAYLOAD)
        self.assertFalse((self.cache / name).exists())
        self.assert_no_partial()

    def test_checksum_mismatch_after_success_is_not_retried_or_promoted(self):
        name = self.rows[0][0]
        for plan, attempts, delays in ((["bad", 0], 1, []), ([6, "bad", 0], 2, [["2"]])):
            with self.subTest(plan=plan):
                self.state.write_text(json.dumps({"calls": [], "delays": []}))
                result, state = self.run_download({name: plan})
                self.assertEqual(result.returncode, 1)
                self.assertEqual(len(state["calls"]), attempts)
                self.assertEqual(state["delays"], delays)
                self.assertIn(f"Checksum mismatch for pinned archive: {name}", result.stderr)
                self.assertFalse((self.cache / name).exists())
                self.assert_no_partial()

    def test_verified_cache_is_reused_without_curl_or_delays(self):
        for name, _, _ in self.rows:
            (self.cache / name).write_bytes(PAYLOAD)
        result, state = self.run_download()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(state, {"calls": [], "delays": []})
        self.assert_no_partial()

    def test_corrupt_cache_refuses_without_replacement_or_download(self):
        name = self.rows[0][0]
        (self.cache / name).write_bytes(b"corrupt existing archive")
        result, state = self.run_download()
        self.assertEqual(result.returncode, 1)
        self.assertEqual(state, {"calls": [], "delays": []})
        self.assertIn(f"Checksum mismatch for cached pinned archive: {name}", result.stderr)
        self.assertEqual((self.cache / name).read_bytes(), b"corrupt existing archive")
        self.assert_no_partial()

    def test_termination_cleans_unverified_partial_and_does_not_promote(self):
        name = self.rows[0][0]
        result, state = self.run_download({name: ["signal"]})
        self.assertEqual(result.returncode, 143)
        self.assertEqual(len(state["calls"]), 1)
        self.assertEqual(state["delays"], [])
        self.assertFalse((self.cache / name).exists())
        self.assert_no_partial()


if __name__ == "__main__":
    Path("/tmp/agents").mkdir(exist_ok=True)
    unittest.main()
