#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Owned provider-pair host/worker fixtures. No providers, routes or real CDM."""

import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[2] / "apps/web/scripts"
spec = importlib.util.spec_from_file_location("provider_pair_route", SCRIPTS / "provider-pair-route.py")
host = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = host
spec.loader.exec_module(host)
backend, bootstrap = host.backend, host.bootstrap
PRIVATE = "PRIVATE_SYNTHETIC_PAIR_VALUE"


def descriptor():
    return {"kind": "loopback-connect", "host": "127.0.0.1", "port": 12345,
            "username": "u" * 24, "password": "p" * 24, "realm": "r" * 24}


class Route:
    def private_packet(self):
        return descriptor()


def bound_fixture(expiry):
    episode, nonce = backend.episode.EPISODE, "a" * 32
    uri = "https://vod-abematv.akamaized.net/program/" + episode + "/manifest.mpd"
    endpoint = "https://license.p-c3-e.abema-tv.com/abematv-dash"
    metadata = {"id": episode, "playback": {"arin": "FRESH_ARIN"}}
    gateway = {"streams": [{"drm": {"systemId": backend.episode.DRM_SYSTEMS["CUSTOM"],
        "license": {"urlTemplate": endpoint + "{?t,pt,ct,cid}"}},
        "manifests": [{"arin": "FRESH_MANIFEST", "url": uri, "adInsertion": {"mode": 4}}]}]}
    binding = {"nonce": nonce, "episodeId": episode, "arin": "FRESH_ARIN", "expiresAtMs": expiry,
        "browser": {"name": "Chrome", "major": "156", "os": "Linux", "engine": "Blink"},
        "uri": uri + "?t=" + PRIVATE + "&enc=clear", "licenseUri": endpoint + "?t=" + PRIVATE + "&ct=program&cid=" + episode,
        "streamIndex": 0, "manifestIndex": 0, "drm": "CUSTOM", "keySystem": "CUSTOM", "adMode": "CSAI", "gatewayCalls": 1}
    return binding, nonce, metadata, expiry, gateway, PRIVATE


class BackendTests(unittest.TestCase):
    def test_configuration_strict_and_original_deadline_before_preparation(self):
        start = time.monotonic_ns()
        value = {"route": descriptor(), "requestStartedNs": str(start), "deadlineNs": str(start + 120 * 10**9)}
        self.assertEqual(backend.validate_config(value, now=start + 1)[1:], (start, start + 120 * 10**9))
        for changes in ({"url": "https://private.invalid"}, {"deadlineNs": str(start + 120 * 10**9 + 1)},
                        {"requestStartedNs": str(start + 2)}, {"deadlineNs": "1e19"}, {"route": None}):
            bad = {**value, **changes}
            with self.assertRaises(bootstrap.Refused): backend.validate_config(bad, now=start + 1)
        with tempfile.TemporaryDirectory() as temporary:
            attempt = backend.FreshPairAttempt(Route().private_packet(), start - 10**9, start + 119 * 10**9, Path(temporary) / "public")
            self.assertLessEqual(attempt.deadline, (start - 10**9) / 10**9 + 120)

    def test_fresh_bound_packet_crosses_actual_node_validator_with_original_clock(self):
        start = time.monotonic_ns() - 10**9
        deadline = start + 90 * 10**9
        expiry = int(time.time() * 1000) + 90000
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            old_runtime = root / "old"; old_runtime.mkdir()
            pins = []
            for name in ("application.js", "support.js", "legacy.js", "utilities.js"):
                raw = ("OWNED_" + name).encode()
                (old_runtime / name).write_bytes(raw)
                pins.append((name, "PUBLIC_UNUSED", len(raw), hashlib.sha256(raw).hexdigest()))
            dash = b"OWNED_DASH"
            dash_pin = ("dash.js", "PUBLIC_UNUSED", len(dash), hashlib.sha256(dash).hexdigest())
            with patch.object(bootstrap, "ASSETS", pins), patch.object(bootstrap, "EME_ASSET", dash_pin):
                attempt = backend.FreshPairAttempt(descriptor(), start, deadline, root / "public")
                attempt.source_deadline, attempt.source_expiry = deadline / 10**9, expiry
                attempt.public_runtime, attempt.private_user = old_runtime, PRIVATE
                attempt.request = lambda operation, fields: dash if operation == "EME_ASSET" and fields == {"name": "dash.js"} else self.fail("Unexpected operation")
                attempt.bound_episode(*bound_fixture(expiry))
                packet = attempt.packet
                self.assertEqual(set(packet), {"request", "userId", "licenseUri", "requestStartedNs"})
                self.assertEqual(packet["requestStartedNs"], str(start))
                self.assertLessEqual(int(packet["request"]["deadlineNs"]), deadline)
                self.assertEqual({item.name for item in (root / "public").iterdir()}, {pin[0] for pin in (*pins, dash_pin)})
                with self.assertRaises(bootstrap.Refused): attempt.bound_episode(*bound_fixture(expiry))
                # Construct the exact old EME envelope, keeping new timing only
                # in the provider-frame boundary. No Chrome/network is involved.
                script = "import{validateEmePacket}from " + json.dumps((SCRIPTS / "abema-browser-episode-eme.mjs").as_uri()) + ";let raw='';for await(const chunk of process.stdin)raw+=chunk;const {packet,route}=JSON.parse(raw);validateEmePacket({request:packet.request,route,userId:packet.userId,licenseUri:packet.licenseUri});if(!(BigInt(packet.requestStartedNs)<=BigInt(packet.request.clockSampleNs)&&BigInt(packet.request.clockSampleNs)<BigInt(packet.request.deadlineNs)))throw Error();process.stdout.write('VALID');"
                result = bootstrap.run_command([shutil.which("node"), "--input-type=module", "-e", script], 5, 128,
                    input_data=json.dumps({"packet": packet, "route": descriptor()}).encode())
                self.assertEqual((result.code, result.stderr, result.output), (0, False, b"VALID"))

    def test_worker_default_is_inert_and_does_not_echo_unknown_cli(self):
        output = io.StringIO()
        with patch.object(backend, "FreshPairAttempt", side_effect=AssertionError("Must stay inert")):
            self.assertEqual(backend.main([PRIVATE], io.BytesIO(b""), output), 2)
        self.assertNotIn(PRIVATE, output.getvalue())


class HostTests(unittest.TestCase):
    def test_cli_opt_in_and_duplicate_selections(self):
        output = io.StringIO()
        with patch.object(host.comparison, "read_profile", side_effect=AssertionError("No secret read")):
            self.assertEqual(host.main([PRIVATE], output), 2)
        self.assertNotIn(PRIVATE, output.getvalue())
        args = ["--run-provider-pair-proof", "--peer-idle-confirmed", "--route-runner", "/owned/runner", "--wireguard-file", "/owned/profile", "--twitch-case", "replay:2080217716"]
        self.assertIsNotNone(host.arguments_valid(args + ["--a", "abema", "--b", "abema"]))
        self.assertIsNotNone(host.arguments_valid(args + ["--a", "twitch", "--b", "twitch"]))
        self.assertIsNone(host.arguments_valid(args + ["--url", PRIVATE]))
        self.assertEqual(host.ROUTE_SECONDS, 480)

    def test_closed_control_does_not_admit_raw_objects_or_secret_fields(self):
        start = time.monotonic_ns()
        fresh = {"event": "fresh", "id": "a" * 32, "requestStartedNs": str(start), "deadlineNs": str(start + 10**9)}
        self.assertEqual(host.validate_control(host.wire(fresh))["event"], "fresh")
        for value in ({**fresh, "url": PRIVATE}, {**fresh, "deadlineNs": str(start + 120 * 10**9 + 1)},
                      {"event": "activation", "userCode": PRIVATE, "verificationUri": "https://private.invalid"},
                      {"event": "finished", "raw": PRIVATE}):
            with self.assertRaises(bootstrap.Refused): host.validate_control(host.wire(value))
        with self.assertRaises(bootstrap.Refused): host.validate_control(b'{"event":"ready","event":"ready"}')
        evidence = {"event": "decodeObserved", "evidence": "SAMPLED_JOINT_ADVANCEMENT", "minimumAdvanceMs": 5000,
            "sampledJointAdvanceMs": 5000, "feeds": {
            side: {"decodedFrames": 100, "mediaAdvanceMs": 5100, "width": 640, "height": 360} for side in ("A", "B")}}
        self.assertEqual(host.validate_control(host.wire(evidence)), evidence)
        evidence["feeds"]["A"]["decodedFrames"] = PRIVATE
        with self.assertRaises(bootstrap.Refused): host.validate_control(host.wire(evidence))

    def owned_job(self, script, seconds=3):
        temporary = tempfile.TemporaryDirectory(prefix="pair-job-owned-")
        root = Path(temporary.name) / "j1"
        def factory(_args, **options):
            return subprocess.Popen([sys.executable, "-c", script], **options)
        start = time.monotonic_ns()
        job = host.FreshJob(Route(), start, start + int(seconds * 10**9), root, factory=factory)
        return job, temporary

    def test_terminal_transport_diagnostics_are_closed_and_never_cleanup_authority(self):
        receipt = {"event": "finished", "outcome": "REFUSED", "decodeObserved": False,
            "playbackFailureObserved": False, "mediaCleanupConfirmed": False, "cdmCleanupConfirmed": False,
            "grantCleanup": "unconfirmed", "rendererCleanupConfirmed": False, "browserCleanupConfirmed": True,
            "authCleanupConfirmed": False}
        diagnostic = {"failure": "CONTINUATION_FAILED", "stage": "RESPONSE", "command": "CONTINUE_RESPONSE", "disposal": "FAILED"}
        value = {**receipt, "transportDiagnostic": diagnostic}
        self.assertEqual(host.validate_control(host.wire(value)), value)
        observed = {**receipt, "transportDiagnostic": {**diagnostic, "canceledContinuationObserved": True}}
        self.assertEqual(host.validate_control(host.wire(observed)), observed)
        self.assertFalse(host.trial_succeeded(value, True))
        for change in ({"url": PRIVATE}, {"failure": PRIVATE}, {"stage": []}, {"command": "Fetch.getResponseBody"},
                       {"disposal": 1}, {"canceledContinuationObserved": PRIVATE}, {"canceledContinuationObserved": 0}):
            with self.subTest(change=change), self.assertRaises(bootstrap.Refused):
                host.validate_control(host.wire({**receipt, "transportDiagnostic": {**diagnostic, **change}}))

    def test_owned_fresh_process_private_result_and_reaping(self):
        script = "import sys,json;value=json.load(sys.stdin);assert value['route']['username']=='u'*24;print(json.dumps({'outcome':'READY','packet':{'owned':'PRIVATE_SYNTHETIC'},'cleanupConfirmed':True}))"
        job, temporary = self.owned_job(script)
        with temporary, contextlib.redirect_stdout(io.StringIO()) as captured:
            self.assertEqual(job.run(), {"owned": "PRIVATE_SYNTHETIC"})
        self.assertEqual(captured.getvalue(), "")
        self.assertTrue(job.cleanup_confirmed and job.reaped)

    def test_nested_worker_retains_selected_node_without_inheriting_ambient_path(self):
        script = "import sys,json,shutil,subprocess,os;json.load(sys.stdin);node=shutil.which('node');assert node;assert shutil.which('ambient-only') is None;assert 'PAIR_PRIVATE_ENV' not in os.environ;value=subprocess.run([node,'--version'],check=True,capture_output=True,text=True).stdout.strip();assert value=='OWNED_NODE';print(json.dumps({'outcome':'READY','packet':{'owned':True},'cleanupConfirmed':True}))"
        job, temporary = self.owned_job(script)
        with temporary:
            root = Path(temporary.name)
            tools, ambient = root / "selected-tools", root / "ambient"
            tools.mkdir(); ambient.mkdir()
            node = tools / "node"
            node.write_text(f"#!{sys.executable}\nprint('OWNED_NODE')\n")
            node.chmod(0o755)
            unwanted = ambient / "ambient-only"
            unwanted.write_text("#!/bin/sh\nexit 0\n"); unwanted.chmod(0o755)
            with patch.dict(os.environ, PATH=str(tools) + os.pathsep + str(ambient), PAIR_PRIVATE_ENV=PRIVATE):
                self.assertEqual(job.run(), {"owned": True})
        self.assertTrue(job.cleanup_confirmed and job.reaped)

    def test_stderr_missing_receipt_and_timeout_cannot_be_cleanup_success(self):
        scripts = ["import sys;sys.stdin.read();sys.stderr.write('PRIVATE_SYNTHETIC');print('{}')",
                   "import sys;sys.stdin.read();print('PRIVATE_SYNTHETIC')",
                   "import sys,time,signal;signal.signal(signal.SIGTERM,signal.SIG_IGN);sys.stdin.read();time.sleep(60)"]
        for script in scripts:
            job, temporary = self.owned_job(script, seconds=0.15)
            with temporary, patch.object(host, "JOB_CLEANUP_SECONDS", 0.05), contextlib.redirect_stdout(io.StringIO()) as captured:
                self.assertIsNone(job.run())
            self.assertEqual(captured.getvalue(), "")
            self.assertTrue(job.reaped)
            self.assertFalse(job.cleanup_confirmed)

    def test_cancel_before_dispatch_never_launches(self):
        job, temporary = self.owned_job("raise AssertionError()")
        job.factory = lambda *_a, **_kw: self.fail("Cancelled job must not dispatch")
        job.cancel()
        with temporary: self.assertIsNone(job.run())
        self.assertTrue(job.reaped and job.cleanup_confirmed)

    def test_failed_trial_cannot_be_upgraded_by_complete_cleanup_or_earlier_decode(self):
        receipt = {"event": "finished", "outcome": "REFUSED", "decodeObserved": True,
            "playbackFailureObserved": True, "mediaCleanupConfirmed": True, "cdmCleanupConfirmed": True,
            "grantCleanup": "revoked", "rendererCleanupConfirmed": True, "browserCleanupConfirmed": True,
            "authCleanupConfirmed": True}
        closed = host.validate_control(host.wire(receipt))
        self.assertFalse(host.trial_succeeded(closed, True))
        receipt.update(outcome="STOPPED", playbackFailureObserved=False)
        self.assertTrue(host.trial_succeeded(host.validate_control(host.wire(receipt)), True))
        self.assertFalse(host.trial_succeeded(receipt, False))


if __name__ == "__main__":
    unittest.main()
