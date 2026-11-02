#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Owned protected playback parent/route tests. No provider or profile access."""

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
spec = importlib.util.spec_from_file_location("web_abema_route_playback", SCRIPTS / "abema-route-playback.py")
route_playback = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = route_playback
spec.loader.exec_module(route_playback)
playback, bootstrap = route_playback.playback, route_playback.bootstrap
PRIVATE, NONCE, END = "PRIVATE_SYNTHETIC_PLAYBACK_VALUE", "a" * 32, 2000000000000


def descriptor():
    return {"kind": "loopback-connect", "host": "127.0.0.1", "port": 12345,
            "username": "u" * 24, "password": "p" * 24, "realm": "r" * 24}


def fixtures():
    episode = playback.episode.EPISODE
    uri = "https://vod-abematv.akamaized.net/program/" + episode + "/manifest.mpd"
    license_uri = "https://license.p-c3-e.abema-tv.com/abematv-dash"
    metadata = {"id": episode, "playback": {"arin": "FRESH_ARIN"}}
    gateway = {"streams": [{"drm": {"systemId": playback.episode.DRM_SYSTEMS["CUSTOM"],
        "license": {"urlTemplate": license_uri + "{?t,pt,ct,cid}"}},
        "manifests": [{"arin": "FRESH_MANIFEST", "url": uri, "adInsertion": {"mode": 4}}]}]}
    binding = {"nonce": NONCE, "episodeId": episode, "arin": "FRESH_ARIN", "expiresAtMs": END,
        "browser": {"name": "Chrome", "major": "156", "os": "Linux", "engine": "Blink"},
        "uri": uri + "?t=" + PRIVATE + "&enc=clear", "licenseUri": license_uri + "?t=" + PRIVATE + "&ct=program&cid=" + episode,
        "streamIndex": 0, "manifestIndex": 0, "drm": "CUSTOM", "keySystem": "CUSTOM", "adMode": "CSAI", "gatewayCalls": 1}
    return binding, NONCE, metadata, END, gateway, PRIVATE


def evidence(closed=False, **changes):
    return {"stage": "cleanup" if closed else "play", "boundary": "CANCELLED" if closed else "NONE", "http": None,
        "publicationStage": "PUBLISH", "auxiliaryReason": None, "utcScheme": None, "sdkCode": None,
        "frames": 130, "advancedMs": 5400, "width": 640, "height": 360,
        "counts": {"manifest": 1, "initialization": 2, "segment": 8, "deniedDrm": 0},
        "licenseRequests": 1, "licenseResponses": 1, "licenseStatus": 200, "licenseTransformed": True,
        "cdm": {**{key: 1 for key in playback.CDM_COUNTS}, "updateAccepted": True, "sessionClosed": closed, "violation": False}, **changes}


def receipt(**changes):
    return {"nonce": NONCE, "outcome": "DECODE_OBSERVED", "stage": "OBSERVE", "before": evidence(), "after": evidence(True),
            "explicitClose": "CONFIRMED", "cleanupConfirmed": True, "chromeVersion": "156.0.0.0", **changes}


class PlaybackTests(unittest.TestCase):
    def test_opt_in_is_exact_and_other_commands_are_inert(self):
        for args in ([], ["--run"], ["--run-route-replay-eme", "--peer-idle-confirmed", "--route-runner", "/x", "--wireguard-file", "/x"],
                     ["--run-route-replay-playback", "--route-runner", "/x", "--wireguard-file", "/x"]):
            output = io.StringIO()
            with patch.object(route_playback.comparison, "read_profile", side_effect=AssertionError("Must remain inert")):
                self.assertEqual(route_playback.main(args, output), 2)
            self.assertEqual(json.loads(output.getvalue()), {"phase": "ROUTE_PLAYBACK", "outcome": "OPT_IN_REQUIRED"})

    def test_receipt_requires_decoded_advance_and_actual_same_exchange_closure(self):
        self.assertEqual(playback.validate_receipt(receipt(), NONCE), receipt())
        bad = [receipt(nonce=PRIVATE), receipt(extra=PRIVATE), receipt(chromeVersion=PRIVATE),
               receipt(before=evidence(advancedMs=4999)), receipt(before=evidence(frames=0)),
               receipt(after=evidence(True, boundary="PROTECTION")), receipt(after=evidence(True, sdkCode=3016)),
               receipt(after=evidence(True, cdm={**evidence(True)["cdm"], "sessionClosed": False})),
               receipt(before=evidence(cdm={**evidence()["cdm"], "updateAccepted": False})),
               receipt(before=evidence(cdm={**evidence()["cdm"], "sessionClosed": True})),
               receipt(before=evidence(counts={**evidence()["counts"], "segment": 0})),
               receipt(before=evidence(licenseStatus=True)), receipt(before=evidence(http=PRIVATE)),
               receipt(before=evidence(counts={**evidence()["counts"], "private": PRIVATE})), receipt(before=evidence(stage=[]))]
        for value in bad:
            with self.subTest(value=value), self.assertRaises(bootstrap.Refused):
                playback.validate_receipt(value, NONCE)
        for stage in ("INPUT", "RUNTIME"):
            inert_lie = receipt(stage=stage, outcome=stage + "_REFUSED", before=evidence(), after=None, explicitClose="NOT_ATTEMPTED")
            with self.subTest(stage=stage), self.assertRaises(bootstrap.Refused):
                playback.validate_receipt(inert_lie, NONCE)
        for predecessor in playback.CDM_ORDER[:-1]:
            with self.subTest(predecessor=predecessor), self.assertRaises(bootstrap.Refused):
                playback.validate_evidence(evidence(cdm={**evidence()["cdm"], predecessor: 0}))
        with self.assertRaises(bootstrap.Refused):
            playback.validate_evidence(evidence(licenseResponses=0, licenseTransformed=True))
        uncertain = receipt(outcome="CLEANUP_REFUSED", stage="CLOSE", cleanupConfirmed=False, explicitClose="UNCONFIRMED")
        self.assertEqual(playback.validate_receipt(uncertain, NONCE), uncertain)

    def run_bound(self, root, output, runner, args=None):
        public = root / "source"
        public.mkdir()
        raw = b"PUBLIC_OWNED_SCRIPT"
        digest = bootstrap.hashlib.sha256(raw).hexdigest()
        assets = tuple((name, "unused", len(raw), digest) for name in ("application.js", "support.js", "legacy.js", "utilities.js"))
        for name, *_ in assets:
            (public / name).write_bytes(raw)
        attempt = playback.EpisodePlaybackAttempt(output, descriptor(), runner=runner)
        attempt.public_runtime, attempt.private_user = public, PRIVATE
        attempt.source_deadline = attempt.deadline = bootstrap.time.monotonic() + 20
        attempt.source_expiry = END
        with patch.object(bootstrap, "ASSETS", assets), patch.object(bootstrap, "EME_ASSET", ("dash.js", "unused", len(raw), digest)), \
                patch.object(attempt, "request", return_value=raw), patch.object(playback.tempfile, "mkdtemp", return_value=str(root / "browser")):
            (root / "browser").mkdir()
            attempt.bound_episode(*(args or fixtures()))
        return attempt

    def test_actual_parent_node_and_renderer_bind_the_same_private_packet(self):
        output, calls = io.StringIO(), []
        def runner(arguments, timeout, limit, input_data, display):
            calls.append(arguments)
            packet = json.loads(input_data)
            self.assertEqual(set(packet), {"request", "route", "userId", "licenseUri"})
            self.assertEqual(packet["route"], descriptor())
            self.assertEqual(packet["userId"], PRIVATE)
            self.assertLessEqual(timeout, 20)
            self.assertTrue(display)
            self.assertTrue(arguments[1].endswith("abema-browser-episode-playback.mjs"))
            runtime = Path(arguments[-1])
            self.assertEqual({item.name for item in (runtime / "public").iterdir()},
                             {"application.js", "support.js", "legacy.js", "utilities.js", "dash.js"})
            self.assertFalse(any(PRIVATE in item for item in arguments))
            script = "import {validatePlaybackPacket} from " + json.dumps((SCRIPTS / "abema-browser-episode-playback.mjs").as_uri()) + ";import {protectedReplayBinding} from " + json.dumps((SCRIPTS.parent / "src/abema-protected-binding.ts").as_uri()) + ";let text='';for await(const part of process.stdin)text+=part;const value=validatePlaybackPacket(JSON.parse(text));protectedReplayBinding({request:value.request,userId:value.userId,licenseUri:value.licenseUri,deadlineMs:performance.now()+1000});process.stdout.write('VALID');"
            result = bootstrap.run_command([shutil.which("node"), "--input-type=module", "-e", script], 5, 128, input_data=input_data)
            self.assertTrue(result.code == 0 and not result.stderr and result.output == b"VALID", "Parent/Node/renderer private packet mismatch")
            return bootstrap.CommandResult(0, json.dumps(receipt()).encode())
        with tempfile.TemporaryDirectory(prefix="playback-parent-") as directory:
            attempt = self.run_bound(Path(directory), output, runner)
            self.assertFalse((Path(directory) / "browser").exists())
            with self.assertRaises(bootstrap.Refused):
                attempt.bound_episode(*fixtures())
        self.assertEqual(len(calls), 1)
        self.assertNotIn(PRIVATE, output.getvalue())
        self.assertEqual(json.loads(output.getvalue())["playbackOutcome"], "DECODE_OBSERVED")

    def test_foreign_source_protection_mode_and_license_cannot_launch(self):
        for field, value in (("uri", fixtures()[0]["uri"].replace(PRIVATE, "STALE")), ("drm", "WIDEVINE"),
                             ("adMode", "NONE"), ("licenseUri", fixtures()[0]["licenseUri"].replace("license.p-c3-e.abema-tv.com", "private.invalid"))):
            args = copy.deepcopy(fixtures()); args[0][field] = value
            with self.subTest(field=field), tempfile.TemporaryDirectory(prefix="playback-refuse-") as directory, self.assertRaises(bootstrap.Refused):
                self.run_bound(Path(directory), io.StringIO(), lambda *a, **kw: self.fail("Must not launch"), args)

    def test_uncertain_runner_or_receipt_never_confirms_cleanup(self):
        def timeout(*args, **options):
            raise bootstrap.Refused(PRIVATE)
        uncertain = receipt(outcome="CLEANUP_REFUSED", stage="CLOSE", explicitClose="UNCONFIRMED", cleanupConfirmed=False)
        cases = [timeout, lambda *a, **kw: bootstrap.CommandResult(0, b"PRIVATE_INVALID"),
                 lambda *a, **kw: bootstrap.CommandResult(0, json.dumps(uncertain).encode()),
                 lambda *a, **kw: bootstrap.CommandResult(1, json.dumps(receipt()).encode()),
                 lambda *a, **kw: bootstrap.CommandResult(0, json.dumps(receipt()).encode(), stderr=True)]
        for runner in cases:
            with self.subTest(runner=runner), tempfile.TemporaryDirectory(prefix="playback-cleanup-") as directory:
                root, output = Path(directory), io.StringIO()
                with self.assertRaises(bootstrap.CleanupUnconfirmed):
                    self.run_bound(root, output, runner)
                self.assertTrue((root / "browser").exists())
                self.assertNotIn(PRIVATE, output.getvalue())
                if output.getvalue():
                    self.assertEqual(json.loads(output.getvalue())["before"]["advancedMs"], 5400)

    def test_license_status_is_independent_and_confirmed_refusal_cleans(self):
        before = evidence(stage="cleanup", boundary="HTTP", http=403, licenseStatus=403,
                          frames=0, advancedMs=0, width=0, height=0, licenseResponses=0, licenseTransformed=False,
                          cdm={**evidence(True)["cdm"], "responses": 0, "updates": 0, "updateAccepted": False})
        denied = receipt(outcome="PREPARE_REFUSED", stage="PREPARE", before=before, after=before)
        with tempfile.TemporaryDirectory(prefix="playback-http-") as directory:
            root, output = Path(directory), io.StringIO()
            with self.assertRaises(bootstrap.Refused):
                self.run_bound(root, output, lambda *a, **kw: bootstrap.CommandResult(0, json.dumps(denied).encode()))
            self.assertFalse((root / "browser").exists())
        self.assertEqual(json.loads(output.getvalue())["before"]["licenseStatus"], 403)

    def test_route_attempt_cleanup_and_termination_are_independent(self):
        options = ["--run-route-replay-playback", "--peer-idle-confirmed", "--route-runner", "/owned", "--wireguard-file", "/owned"]
        class Trial:
            def __init__(self, *args, **kwargs):
                self.route = descriptor()
                self.confirmed = route_ok
                self.lease = kwargs["lease_seconds"]
                self.asserted = kwargs["completing_cleanup"]
            def __enter__(self): return self
            def __exit__(self, *args):
                if terminate: raise bootstrap.Terminated()
            def close(self): return self.confirmed
        class Attempt:
            def __init__(self, *args): self.cleanup_confirmed = attempt_ok
            def run(self): return 0
        for route_ok, attempt_ok, terminate in ((True, True, False), (False, True, False), (True, False, False), (True, True, True)):
            output = io.StringIO()
            with patch.object(route_playback.comparison, "read_profile", return_value=PRIVATE), \
                    patch.object(route_playback.comparison.owner, "RouteOwner", Trial), patch.object(playback, "EpisodePlaybackAttempt", Attempt), \
                    patch.object(route_playback.shutil, "which", return_value="owned"), patch.object(route_playback.resource, "setrlimit"):
                status = route_playback.main(options, output)
            final = json.loads(output.getvalue().splitlines()[-1])
            self.assertEqual(status == 0, route_ok and attempt_ok and not terminate)
            self.assertEqual(final["cleanupConfirmed"], route_ok and attempt_ok)
            self.assertEqual(final["routeCleanupConfirmed"], route_ok)
            self.assertEqual(final["attemptCleanupConfirmed"], attempt_ok)
            self.assertNotIn(PRIVATE, output.getvalue())


if __name__ == "__main__":
    unittest.main()
