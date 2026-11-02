#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Real owner-loop/private-pipe fixtures; no browser, route or provider work."""

from concurrent.futures import Future
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch


SCRIPTS = Path(__file__).resolve().parents[2] / "apps/web/scripts"
SPEC = importlib.util.spec_from_file_location("provider_pair_owner_fixture", SCRIPTS / "provider-pair-route.py")
host = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = host
SPEC.loader.exec_module(host)

RECEIPT = {"event": "finished", "outcome": "STOPPED", "decodeObserved": False,
    "playbackFailureObserved": False, "mediaCleanupConfirmed": True, "cdmCleanupConfirmed": True,
    "grantCleanup": "no-grant-held", "rendererCleanupConfirmed": True,
    "browserCleanupConfirmed": True, "authCleanupConfirmed": True}


class FixtureRoute:
    def private_packet(self):
        # Required closed envelope only. Nothing connects to this synthetic route.
        return {"kind": "loopback-connect", "host": "127.0.0.1", "port": 12345,
            "username": "u" * 24, "password": "p" * 24, "realm": "r" * 24}


class CompletedExecutor:
    """Make a completing job deterministic without replacing the owner I/O loop."""
    def __init__(self, **_options):
        pass

    def submit(self, action):
        future = Future()
        try:
            future.set_result(action())
        except BaseException as error:
            future.set_exception(error)
        return future

    def shutdown(self, **_options):
        pass


CHILD = """
import json, os, pathlib, signal, sys, time
signal.alarm(5)
root, mode = pathlib.Path(sys.argv[1]), sys.argv[2]
json.loads(sys.stdin.readline())
receipt = json.loads(sys.argv[3])
def emit(value):
    print(json.dumps(value), flush=True)
if mode == 'missing':
    sys.exit(0)
if mode == 'fresh-broken-pipe':
    start = time.monotonic_ns()
    emit({'event':'fresh','id':'a'*32,'requestStartedNs':str(start),'deadlineNs':str(start+2_000_000_000)})
    end = time.monotonic()+2
    while not (root/'job-started').exists():
        if time.monotonic() >= end:
            raise RuntimeError('Owned job did not start')
        time.sleep(0.005)
    os.close(0)
    emit(receipt)
    (root/'finished-sent').touch()
    sys.exit(0)
if mode == 'stderr':
    os.write(2, b'OWNED_SYNTHETIC_WARNING\\n')
stop = json.loads(sys.stdin.readline())
assert stop == {'type':'stop'}
emit(receipt)
"""


class BrowserOwnerTests(unittest.TestCase):
    def run_owner(self, mode, stop_line=b"stop\n", fail_probe=False):
        processes = []
        native_popen = subprocess.Popen
        native_set_blocking = os.set_blocking
        with tempfile.TemporaryDirectory(prefix="pair-owner-fixture-") as directory:
            root = Path(directory)
            read_fd, write_fd = os.pipe()
            if mode == "stop":
                os.write(write_fd, stop_line)
            output = io.StringIO()
            trial = host.BrowserTrial(FixtureRoute(), root, "live:virtualjapan",
                {"A": "abema", "B": "twitch"}, time.monotonic() + host.CLEANUP_SECONDS + 3, output)

            def launch(_command, **options):
                self.assertEqual(Path(_command[1]).name, "provider-pair-browser.mjs")
                process = native_popen([sys.executable, "-u", "-c", CHILD,
                    str(root), mode, json.dumps(RECEIPT)], **options)
                processes.append(process)
                return process

            class Job:
                cleanup_confirmed = True
                reaped = True

                def __init__(self, *_args):
                    pass

                def run(self):
                    (root / "job-started").touch()
                    end = time.monotonic() + 2
                    while not (root / "finished-sent").exists():
                        if time.monotonic() >= end:
                            raise AssertionError("Owned terminal fixture did not finish")
                        time.sleep(0.005)
                    return None

                def cancel(self):
                    pass

            trial.job_factory = Job
            def set_blocking(descriptor, blocking):
                if fail_probe and processes and descriptor == processes[0].stdout.fileno():
                    raise OSError("Owned final probe refused")
                return native_set_blocking(descriptor, blocking)
            try:
                with os.fdopen(read_fd, "rb", buffering=0) as user_input, contextlib.ExitStack() as stack:
                    stack.enter_context(patch.object(host.sys, "stdin", SimpleNamespace(buffer=user_input)))
                    stack.enter_context(patch.object(host.subprocess, "Popen", side_effect=launch))
                    stack.enter_context(patch.object(host.os, "set_blocking", side_effect=set_blocking))
                    if mode == "fresh-broken-pipe":
                        stack.enter_context(patch.object(host, "ThreadPoolExecutor", CompletedExecutor))
                    result = trial.run()
                reports = [json.loads(line) for line in output.getvalue().splitlines()]
                report = reports[-1]
                self.assertEqual(report["phase"], "PAIR_RECEIPT")
                self.assertTrue(report["backendCleanupConfirmed"])
                self.assertTrue(report["ownedBrowserGroupClosed"])
                return result, trial, report
            finally:
                os.close(write_fd)
                for process in processes:
                    if process.poll() is None:
                        process.kill()
                    process.wait(timeout=2)
                    self.assertIsNotNone(process.returncode)

    def test_stop_then_terminal_receipt_and_eof_preserves_authoritative_cleanup(self):
        for line in (b"stop\n", b"stop\r\n"):
            with self.subTest(line=line):
                result, trial, report = self.run_owner("stop", line)
                self.assertEqual(report["receipt"], RECEIPT)
                self.assertEqual(trial.browser_receipt, RECEIPT)
                self.assertTrue(report["cleanupConfirmed"])
                self.assertEqual(result, 1)  # A closed synthetic receipt is not decoding acceptance.

    def test_eof_without_terminal_receipt_never_infers_renderer_or_grant_cleanup(self):
        result, trial, report = self.run_owner("missing")
        self.assertIsNone(report["receipt"])
        self.assertIsNone(trial.browser_receipt)
        self.assertFalse(report["cleanupConfirmed"])
        self.assertEqual(result, 1)

    def test_final_pipe_probe_error_retains_receipt_without_confirming_cleanup(self):
        result, trial, report = self.run_owner("stop", fail_probe=True)
        self.assertEqual(report["receipt"], RECEIPT)
        self.assertEqual(trial.browser_receipt, RECEIPT)
        self.assertFalse(report["cleanupConfirmed"])
        self.assertEqual(result, 1)

    def test_completed_fresh_job_cannot_hide_a_queued_terminal_receipt_after_stdin_closes(self):
        result, trial, report = self.run_owner("fresh-broken-pipe")
        self.assertEqual(report["freshAbemaAttempts"], 1)
        self.assertEqual(report["receipt"], RECEIPT)
        self.assertEqual(trial.browser_receipt, RECEIPT)
        self.assertFalse(report["cleanupConfirmed"])
        self.assertEqual(result, 1)

    def test_stderr_failure_retains_a_later_terminal_receipt_without_becoming_success(self):
        result, trial, report = self.run_owner("stderr")
        self.assertEqual(report["receipt"], RECEIPT)
        self.assertEqual(trial.browser_receipt, RECEIPT)
        self.assertFalse(report["cleanupConfirmed"])
        self.assertEqual(result, 1)


if __name__ == "__main__":
    unittest.main()
