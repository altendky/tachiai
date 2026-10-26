#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Canonical snapshot ownership regressions; no provider or Docker operations."""
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

SOURCE = Path(__file__).resolve().parents[2] / "apps/web/scripts/abema-backend-source.py"
spec = importlib.util.spec_from_file_location("web_abema_source", SOURCE)
source = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = source
spec.loader.exec_module(source)
bootstrap, owned = source.bootstrap, source.owned_browser
NAVIGATOR = {"userAgent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/156.0.0.0 Safari/537.36",
             "platform": "Linux x86_64", "maxTouchPoints": 0,
             "userAgentData": {"mobile": False, "platform": "Linux"}}


def packet(**changes):
    return json.dumps({"version": "156.0.8078.47", "navigator": NAVIGATOR, "cleanupConfirmed": True, **changes}).encode()


class NoProvider:
    def request(self, *args):
        raise AssertionError("Snapshot must precede all provider operations")


class SnapshotTests(unittest.TestCase):
    def attempt(self, runner):
        return source.SourceAttempt(io.StringIO(), NoProvider(), runner)

    def test_normal_snapshot_owns_short_runtime_and_requires_clean_receipt(self):
        paths = []
        def runner(args, timeout, limit, **kwargs):
            runtime = Path(args[-1]); paths.append(runtime)
            self.assertTrue(runtime.is_dir()); self.assertTrue(runtime.name.startswith("s-"))
            self.assertEqual(runtime.parent, Path(tempfile.gettempdir()))
            self.assertEqual(args[-3:-1], ["--snapshot", "--owned-runtime"])
            self.assertLessEqual(timeout, 20); self.assertEqual(limit, 4096)
            self.assertEqual(kwargs, {"display": True})
            return bootstrap.CommandResult(0, packet())
        attempt = self.attempt(runner)
        self.assertEqual(attempt.browser_snapshot(), NAVIGATOR)
        self.assertFalse(paths[0].exists()); self.assertTrue(attempt.cleanup_confirmed)
        self.assertIsNone(attempt.retained_snapshot_runtime)
        with self.assertRaises(bootstrap.Refused): attempt.browser_snapshot()
        self.assertEqual(len(paths), 1)

    def test_unknown_or_false_receipt_is_sticky_retained_and_distinct_before_provider_work(self):
        for raw in (packet(cleanupConfirmed=False), packet().replace(b', "cleanupConfirmed": true', b''), b'private-invalid-json'):
            calls = []
            def runner(args, *rest, **kwargs):
                calls.append(args)
                return bootstrap.CommandResult(0, raw)
            attempt = self.attempt(runner)
            try:
                self.assertEqual(attempt.run(), 1)
                self.assertFalse(attempt.cleanup_confirmed)
                self.assertTrue(attempt.retained_snapshot_runtime.is_dir())
                report = attempt.output.getvalue()
                self.assertIn('"outcome": "CLEANUP_UNCONFIRMED"', report)
                self.assertIn('"cleanupConfirmed": false', report)
                self.assertNotIn('"outcome": "REFUSED"', report)
                self.assertNotIn("private-invalid-json", report)
                with self.assertRaises(bootstrap.CleanupUnconfirmed): attempt.browser_snapshot()
                self.assertEqual(len(calls), 1)
            finally:
                if attempt.retained_snapshot_runtime: shutil.rmtree(attempt.retained_snapshot_runtime)

    def test_interruption_and_timeout_before_launch_keep_original_classification_and_remove_runtime(self):
        for failure in (bootstrap.Terminated, bootstrap.Refused):
            paths = []
            def runner(args, *rest, **kwargs):
                paths.append(Path(args[-1])); raise failure()
            attempt = self.attempt(runner)
            with self.assertRaises(failure): attempt.browser_snapshot()
            self.assertTrue(attempt.cleanup_confirmed); self.assertFalse(paths[0].exists())
            self.assertIsNone(attempt.retained_snapshot_runtime)

    def test_interrupted_unregistered_launch_retains_runtime_without_signaling(self):
        def runner(args, *rest, **kwargs):
            (Path(args[-1]) / "launch-started.json").write_text('{"started":true}')
            raise bootstrap.Terminated()
        attempt = self.attempt(runner)
        try:
            with patch.object(owned.os, "killpg", side_effect=AssertionError("Unverified launch must not signal")):
                self.assertEqual(attempt.run(), 1)
            self.assertFalse(attempt.cleanup_confirmed)
            self.assertTrue(attempt.retained_snapshot_runtime.is_dir())
            self.assertIn("CLEANUP_UNCONFIRMED", attempt.output.getvalue())
        finally:
            if attempt.retained_snapshot_runtime: shutil.rmtree(attempt.retained_snapshot_runtime)

    def test_stale_unrelated_owner_never_authorizes_signal_or_runtime_removal(self):
        unrelated = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"], start_new_session=True)
        def runner(args, *rest, **kwargs):
            runtime = Path(args[-1])
            (runtime / "launch-started.json").write_text('{"started":true}')
            (runtime / "browser-owner.json").write_text(json.dumps({"pid": unrelated.pid, "processGroup": unrelated.pid,
                "startTime": "0", "userDataDir": str(runtime / "profile")}))
            raise bootstrap.Terminated()
        attempt = self.attempt(runner)
        try:
            with patch.object(owned.os, "killpg", side_effect=AssertionError("No unrelated signal")):
                with self.assertRaises(bootstrap.CleanupUnconfirmed): attempt.browser_snapshot()
            self.assertIsNone(unrelated.poll()); self.assertFalse(attempt.cleanup_confirmed)
            self.assertTrue(attempt.retained_snapshot_runtime.is_dir())
        finally:
            unrelated.terminate(); unrelated.wait(timeout=5)
            if attempt.retained_snapshot_runtime: shutil.rmtree(attempt.retained_snapshot_runtime)

    def test_long_caller_root_is_refused_before_launch_without_root_fallback(self):
        with tempfile.TemporaryDirectory(prefix="long-" + "x" * 65) as directory:
            with patch.object(tempfile, "tempdir", directory):
                attempt = self.attempt(lambda *args, **kwargs: self.fail("Path budget must precede launch"))
                with self.assertRaises(bootstrap.Refused): attempt.browser_snapshot()
            self.assertEqual(list(Path(directory).iterdir()), [])
            self.assertTrue(attempt.cleanup_confirmed)
        suffix = owned.SOCKET_SUFFIX
        owned.validate_runtime_path(Path("/" + "a" * (owned.SOCKET_PATH_LIMIT - len(suffix) - 1)))
        with self.assertRaises(bootstrap.Refused):
            owned.validate_runtime_path(Path("/" + "a" * (owned.SOCKET_PATH_LIMIT - len(suffix))))

    def test_canonical_bootstrap_identity_survives_snapshot_and_spawn_without_network(self):
        attempt = self.attempt(lambda *args, **kwargs: bootstrap.CommandResult(0, packet()))
        attempt.browser_snapshot()
        self.assertIs(owned.bootstrap, bootstrap)
        self.assertIs(sys.modules[bootstrap.native_worker.__module__].native_worker, bootstrap.native_worker)
        context = bootstrap.multiprocessing.get_context("spawn")
        receiver, sender = context.Pipe(duplex=False)
        # Invalid operation is refused by the actual worker before opener.open.
        process = context.Process(target=bootstrap.native_worker, args=(sender, "INVALID", {}), daemon=True)
        try:
            process.start(); sender.close()
            self.assertTrue(receiver.poll(5))
            self.assertEqual(receiver.recv_bytes(4096), b'{"refused":true}')
        finally:
            sender.close(); receiver.close(); process.join(2)
            if process.is_alive(): process.terminate(); process.join(2)
            process.close()

    @unittest.skipUnless(os.environ.get("TACHIAI_LOCAL_CHROME_TEST") == "1", "explicit local desktop Chrome check")
    def test_actual_sanitized_snapshot_preserves_desktop_classification_and_removes_runtime(self):
        paths, groups = [], []
        actual_popen = subprocess.Popen
        def popen(*args, **kwargs):
            self.assertLessEqual(set(kwargs["env"]), {"PATH", "LANG", "DISPLAY", "XAUTHORITY"})
            return actual_popen(*args, **kwargs)
        def runner(args, timeout, limit, **kwargs):
            runtime = Path(args[-1]); paths.append(runtime)
            result = bootstrap.run_command(args, timeout, limit, **kwargs)
            self.assertEqual(result.stderr, False)
            groups.append(json.loads((runtime / "browser-owner.json").read_text())["processGroup"])
            self.assertFalse(owned._live_group(groups[-1]))
            return result
        attempt = self.attempt(runner)
        with patch.object(bootstrap.subprocess, "Popen", side_effect=popen):
            nav = attempt.browser_snapshot()
        self.assertEqual(nav["platform"], "Linux x86_64")
        self.assertIn("Chrome/156.", nav["userAgent"]); self.assertNotIn("HeadlessChrome", nav["userAgent"])
        self.assertFalse(paths[0].exists()); self.assertTrue(attempt.cleanup_confirmed)

    def actual_cancellation(self, interrupted):
        unrelated = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"], start_new_session=True)
        actual_signal, actual_popen = os.killpg, subprocess.Popen
        groups, node_groups, signals, paths = [], [], [], []
        def record_signal(group, number): signals.append(group); return actual_signal(group, number)
        def popen(*args, **kwargs):
            self.assertLessEqual(set(kwargs["env"]), {"PATH", "LANG", "DISPLAY", "XAUTHORITY"})
            process = actual_popen(*args, **kwargs); node_groups.append(process.pid); return process
        def runner(args, timeout, limit, **kwargs):
            runtime = Path(args[-1]); paths.append(runtime)
            helper = SOURCE.with_name("abema-browser-manifest.mjs").as_uri()
            code = "import { launchOwnedBrowser } from " + json.dumps(helper) + "; await launchOwnedBrowser(process.argv[1]); await new Promise(()=>{});"
            timer = threading.Timer(4, lambda: os.kill(os.getpid(), signal.SIGTERM)) if interrupted else None
            try:
                if timer: timer.start()
                return bootstrap.run_command([args[0], "--input-type=module", "-e", code, str(runtime)],
                                             15 if interrupted else 4, limit, **kwargs)
            finally:
                if timer: timer.cancel(); timer.join()
                groups.append(json.loads((runtime / "browser-owner.json").read_text())["processGroup"])
                # Chrome may already exit when Node's automation pipe closes.
                # The parent must still verify that no live owned group remains.
        attempt = self.attempt(runner)
        try:
            with patch.object(bootstrap.os, "killpg", side_effect=record_signal), \
                    patch.object(bootstrap.subprocess, "Popen", side_effect=popen), bootstrap.TerminationGuard():
                with self.assertRaises(bootstrap.Terminated if interrupted else bootstrap.Refused): attempt.browser_snapshot()
            self.assertNotEqual(groups[0], node_groups[0]); self.assertFalse(owned._live_group(groups[0]))
            self.assertFalse(paths[0].exists()); self.assertTrue(attempt.cleanup_confirmed)
            self.assertIsNone(attempt.retained_snapshot_runtime); self.assertIsNone(unrelated.poll())
            self.assertTrue(set(signals) <= {node_groups[0], groups[0]}); self.assertNotIn(unrelated.pid, signals)
        finally:
            unrelated.terminate(); unrelated.wait(timeout=5)

    @unittest.skipUnless(os.environ.get("TACHIAI_LOCAL_CHROME_TEST") == "1", "explicit local desktop Chrome interruption")
    def test_actual_sanitized_interruption_closes_detached_chrome_only(self):
        self.actual_cancellation(True)

    @unittest.skipUnless(os.environ.get("TACHIAI_LOCAL_CHROME_TEST") == "1", "explicit local desktop Chrome timeout")
    def test_actual_sanitized_timeout_closes_detached_chrome_only(self):
        self.actual_cancellation(False)


if __name__ == "__main__":
    unittest.main()
