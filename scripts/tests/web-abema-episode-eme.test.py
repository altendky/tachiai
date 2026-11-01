#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Owned EME parent/route fixtures only; no provider or saved profile access."""

import copy
import importlib.util
import io
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[2] / "apps/web/scripts"
spec = importlib.util.spec_from_file_location("web_abema_route_eme", SCRIPTS / "abema-route-eme.py")
route_eme = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = route_eme
spec.loader.exec_module(route_eme)
eme, bootstrap = route_eme.eme, route_eme.bootstrap
PRIVATE, NONCE, END = "PRIVATE_SYNTHETIC_EME_VALUE", "a" * 32, 2000000000000


def descriptor():
    return {"kind": "loopback-connect", "host": "127.0.0.1", "port": 12345,
            "username": "u" * 24, "password": "p" * 24, "realm": "r" * 24}


def fixtures():
    episode = eme.episode.EPISODE
    uri = "https://vod-abematv.akamaized.net/program/" + episode + "/manifest.mpd"
    license_uri = "https://license.p-c3-e.abema-tv.com/abematv-dash"
    metadata = {"id": episode, "playback": {"arin": "FRESH_ARIN"}}
    gateway = {"streams": [{"drm": {"systemId": eme.episode.DRM_SYSTEMS["CUSTOM"],
        "license": {"urlTemplate": license_uri + "{?t,pt,ct,cid}"}},
        "manifests": [{"arin": "FRESH_MANIFEST", "url": uri, "adInsertion": {"mode": 4}}]}]}
    binding = {"nonce": NONCE, "episodeId": episode, "arin": "FRESH_ARIN", "expiresAtMs": END,
        "browser": {"name": "Chrome", "major": "156", "os": "Linux", "engine": "Blink"},
        "uri": uri + "?t=" + PRIVATE + "&enc=clear", "licenseUri": license_uri + "?t=" + PRIVATE + "&ct=program&cid=" + episode,
        "streamIndex": 0, "manifestIndex": 0, "drm": "CUSTOM", "keySystem": "CUSTOM", "adMode": "CSAI", "gatewayCalls": 1}
    return binding, NONCE, metadata, END, gateway, PRIVATE


def receipt(**changes):
    return {"nonce": NONCE, "outcome": "UPDATE_ACCEPTED", "stage": "UPDATE", "manifestReadable": True,
        "manifestStatus": 200, "licenseReadable": True, "licenseStatus": 200, "sessionCreated": True,
        "requestGenerated": True, "licenseDispatched": True, "updateAccepted": True,
        "sessionClosed": True, "cleanupConfirmed": True, **changes}


class EmeTests(unittest.TestCase):
    def test_opt_in_is_exact_and_old_metadata_mode_cannot_enter_eme(self):
        for arguments in ([], ["--run"], ["--run-route-replay-metadata", "--peer-idle-confirmed", "--route-runner", "/x", "--wireguard-file", "/x"],
                          ["--run-route-replay-eme", "--route-runner", "/x", "--wireguard-file", "/x"]):
            output = io.StringIO()
            with patch.object(route_eme.comparison, "read_profile", side_effect=AssertionError("Must remain inert")):
                self.assertEqual(route_eme.main(arguments, output), 2)
            self.assertEqual(json.loads(output.getvalue()), {"phase": "ROUTE_EME", "outcome": "OPT_IN_REQUIRED"})
        with self.assertRaises(bootstrap.Refused):
            bootstrap.request_spec("ASSET", {"name": "dash.js"})
        request = bootstrap.request_spec("EME_ASSET", {"name": "dash.js"})
        self.assertEqual(request.full_url, "https://abema.tv/assets/compat/62ff7b58b1651206.abema-dashjs.js")
        for fields in ({"name": "application.js"}, {"name": "dash.js", "uri": PRIVATE}):
            with self.assertRaises(bootstrap.Refused): bootstrap.request_spec("EME_ASSET", fields)

    def test_receipt_rejects_unbound_statuses_incomplete_session_and_private_fields(self):
        self.assertEqual(eme.validate_receipt(receipt(), NONCE), receipt())
        for changes in ({"nonce": PRIVATE}, {"manifestStatus": True}, {"licenseStatus": "200"},
                        {"manifestReadable": False}, {"licenseReadable": False}, {"sessionCreated": False},
                        {"requestGenerated": False}, {"licenseDispatched": False}, {"updateAccepted": False},
                        {"sessionClosed": False}, {"cleanupConfirmed": False}, {"stage": PRIVATE},
                        {"outcome": PRIVATE}, {"outcome": "RESPONSE_REFUSED"}, {"key": PRIVATE}):
            with self.subTest(changes=changes), self.assertRaises(bootstrap.Refused):
                eme.validate_receipt(receipt(**changes), NONCE)
        # A confirmed event can remain evidence when the attempt later expires;
        # it cannot be promoted into the successful outcome.
        self.assertTrue(eme.validate_receipt(receipt(outcome="LIFETIME_REFUSED"), NONCE)["updateAccepted"])
        denied = receipt(outcome="LICENSE_REFUSED", stage="LICENSE", licenseStatus=403, updateAccepted=False)
        self.assertEqual(eme.validate_receipt(denied, NONCE)["licenseStatus"], 403)
        early = receipt(outcome="RESPONSE_REFUSED", stage="TRANSFORM", manifestReadable=False, manifestStatus=None,
            licenseReadable=False, licenseStatus=None, sessionCreated=False, requestGenerated=False,
            licenseDispatched=False, updateAccepted=False, sessionClosed=False)
        for bad in (early, receipt(outcome="LICENSE_REFUSED", stage="INPUT", updateAccepted=False),
                    receipt(outcome="UPDATE_REFUSED", stage="GENERATE_REQUEST", updateAccepted=False),
                    receipt(outcome="CANCELLED", stage="LICENSE", requestGenerated=False, licenseDispatched=False,
                            licenseReadable=False, licenseStatus=None, updateAccepted=False)):
            with self.assertRaises(bootstrap.Refused): eme.validate_receipt(bad, NONCE)

    def test_browser_and_backend_public_pins_match_without_downloading(self):
        script = "import {runtimePins} from " + json.dumps((SCRIPTS / "abema-browser-episode-eme.mjs").as_uri()) + ";process.stdout.write(JSON.stringify(runtimePins));"
        result = bootstrap.run_command([shutil.which("node"), "--input-type=module", "-e", script], 5, 2048)
        self.assertTrue(result.code == 0 and not result.stderr, "Browser pin metadata unavailable")
        pins = bootstrap.strict_json(result.output, 2048)
        self.assertEqual(pins, [{"name": name, "size": size, "sha256": digest}
                               for name, _, size, digest in (*bootstrap.ASSETS, bootstrap.EME_ASSET)])

    def run_bound(self, root, output, runner, *, args=None, deadline=None):
        public = root / "source"
        public.mkdir()
        raw = b"PUBLIC_OWNED_SCRIPT"
        digest = bootstrap.hashlib.sha256(raw).hexdigest()
        assets = tuple((name, "unused", len(raw), digest) for name in ("application.js", "support.js", "legacy.js", "utilities.js"))
        for name, *_ in assets: (public / name).write_bytes(raw)
        attempt = eme.EpisodeEmeAttempt(output, descriptor(), runner=runner)
        attempt.public_runtime, attempt.private_user = public, PRIVATE
        attempt.source_deadline = attempt.deadline = deadline or bootstrap.time.monotonic() + 20
        attempt.source_expiry = END
        with patch.object(bootstrap, "ASSETS", assets), patch.object(bootstrap, "EME_ASSET", ("dash.js", "unused", len(raw), digest)), \
                patch.object(attempt, "request", return_value=raw), \
                patch.object(eme.tempfile, "mkdtemp", return_value=str(root / "browser")):
            (root / "browser").mkdir()
            attempt.bound_episode(*(args or fixtures()))
        return attempt

    def test_parent_keeps_private_binding_and_route_in_pipe_and_removes_runtime(self):
        output, calls = io.StringIO(), []
        def runner(arguments, timeout, limit, input_data, display):
            packet = json.loads(input_data)
            calls.append((arguments, timeout, packet))
            self.assertEqual(set(packet), {"request", "route", "userId", "licenseUri"})
            self.assertEqual(packet["route"], descriptor())
            self.assertEqual(packet["userId"], PRIVATE)
            self.assertEqual(packet["request"]["mediaToken"], PRIVATE)
            self.assertLessEqual(timeout, 20)
            self.assertTrue(display)
            runtime = Path(arguments[-1])
            self.assertEqual({item.name for item in (runtime / "public").iterdir()},
                             {"application.js", "support.js", "legacy.js", "utilities.js", "dash.js"})
            for item in (runtime / "public").iterdir(): self.assertEqual(item.read_bytes(), b"PUBLIC_OWNED_SCRIPT")
            self.assertFalse(any(PRIVATE in argument for argument in arguments))
            # Exercise the real browser input validator with this exact parent
            # packet, without launching a browser or making any network request.
            script = "import {validateEmePacket} from " + json.dumps((SCRIPTS / "abema-browser-episode-eme.mjs").as_uri()) + ";let input=\"\";for await(const part of process.stdin)input+=part;validateEmePacket(JSON.parse(input));process.stdout.write(\"VALID\");"
            result = bootstrap.run_command([shutil.which("node"), "--input-type=module", "-e", script], 5, 128, input_data=input_data)
            self.assertTrue(result.code == 0 and not result.stderr and result.output == b"VALID", "Parent/browser private packet mismatch")
            return bootstrap.CommandResult(0, json.dumps(receipt()).encode())
        with tempfile.TemporaryDirectory(prefix="eme-parent-") as directory:
            root = Path(directory)
            attempt = self.run_bound(root, output, runner)
            self.assertFalse((root / "browser").exists())
            with self.assertRaises(bootstrap.Refused): attempt.bound_episode(*fixtures())
        self.assertEqual(len(calls), 1)
        self.assertNotIn(PRIVATE, output.getvalue())
        self.assertEqual(json.loads(output.getvalue())["emeOutcome"], "UPDATE_ACCEPTED")

    def test_stale_or_foreign_binding_cannot_start_browser(self):
        for field, value in (("uri", fixtures()[0]["uri"].replace(PRIVATE, "STALE")), ("drm", "WIDEVINE"),
                             ("licenseUri", fixtures()[0]["licenseUri"].replace("license.p-c3-e.abema-tv.com", "private.invalid"))):
            args = copy.deepcopy(fixtures())
            args[0][field] = value
            with self.subTest(field=field), tempfile.TemporaryDirectory(prefix="eme-refuse-") as directory, self.assertRaises(bootstrap.Refused):
                self.run_bound(Path(directory), io.StringIO(), lambda *a, **kw: self.fail("Must not launch"), args=args)

    def test_uncertain_child_cleanup_retains_runtime_and_prevents_acceptance(self):
        def timeout(*args, **options):
            raise bootstrap.Refused(PRIVATE)
        cases = [timeout, lambda *a, **kw: bootstrap.CommandResult(0, b"PRIVATE_MALFORMED_RESPONSE"),
                 lambda *a, **kw: bootstrap.CommandResult(0, json.dumps(receipt(cleanupConfirmed=False)).encode()),
                 lambda *a, **kw: bootstrap.CommandResult(1, b'{"cleanupConfirmed":true}'),
                 lambda *a, **kw: bootstrap.CommandResult(0, json.dumps(receipt()).encode(), stderr=True)]
        for runner in cases:
            with self.subTest(runner=runner), tempfile.TemporaryDirectory(prefix="eme-cleanup-") as directory:
                root, output = Path(directory), io.StringIO()
                with self.assertRaises(bootstrap.CleanupUnconfirmed):
                    self.run_bound(root, output, runner)
                self.assertTrue((root / "browser").exists())
                self.assertNotIn(PRIVATE, output.getvalue())

    def test_provider_refusal_stays_request_owned_and_still_closes_runtime(self):
        output = io.StringIO()
        denied = receipt(outcome="LICENSE_REFUSED", stage="LICENSE", licenseStatus=403, updateAccepted=False)
        with tempfile.TemporaryDirectory(prefix="eme-refusal-") as directory:
            root = Path(directory)
            with self.assertRaises(bootstrap.Refused):
                self.run_bound(root, output, lambda *a, **kw: bootstrap.CommandResult(0, json.dumps(denied).encode()))
            self.assertFalse((root / "browser").exists())
        result = json.loads(output.getvalue())
        self.assertEqual((result["manifestStatus"], result["licenseStatus"], result["emeOutcome"]), (200, 403, "LICENSE_REFUSED"))
        self.assertNotIn(PRIVATE, output.getvalue())

    def test_route_and_attempt_cleanup_are_independent_and_termination_fails(self):
        options = ["--run-route-replay-eme", "--peer-idle-confirmed", "--route-runner", "/owned", "--wireguard-file", "/owned"]
        class Trial:
            def __init__(self, *a, **kw):
                self.route = descriptor()
                self.confirmed = route_ok
            def __enter__(self): return self
            def __exit__(self, *a):
                if terminate: raise bootstrap.Terminated()
            def close(self): return self.confirmed
        class Attempt:
            def __init__(self, *a): self.cleanup_confirmed = attempt_ok
            def run(self): return 0
        for route_ok, attempt_ok, terminate in ((True, True, False), (False, True, False),
                                                (True, False, False), (True, True, True)):
            output = io.StringIO()
            with patch.object(route_eme.comparison, "read_profile", return_value=PRIVATE), \
                    patch.object(route_eme.comparison.owner, "RouteOwner", Trial), \
                    patch.object(eme, "EpisodeEmeAttempt", Attempt), patch.object(route_eme.shutil, "which", return_value="owned"), \
                    patch.object(route_eme.resource, "setrlimit"):
                status = route_eme.main(options, output)
            final = json.loads(output.getvalue().splitlines()[-1])
            self.assertEqual(status == 0, route_ok and attempt_ok and not terminate)
            self.assertEqual(final["cleanupConfirmed"], route_ok and attempt_ok)
            self.assertEqual(final["routeCleanupConfirmed"], route_ok)
            self.assertEqual(final["attemptCleanupConfirmed"], attempt_ok)
            self.assertFalse(final["hostRoutesChanged"])
            self.assertNotIn(PRIVATE, output.getvalue())


if __name__ == "__main__":
    unittest.main()
