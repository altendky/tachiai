#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Closed fixtures plus one local Node/POSIX clock check; no provider calls."""
import copy
import importlib.util
import io
import json
from pathlib import Path
import shutil
import sys
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[2] / "apps/web/scripts/abema-backend-episode-manifest.py"
spec = importlib.util.spec_from_file_location("web_abema_episode_manifest", SCRIPT)
module = importlib.util.module_from_spec(spec); sys.modules[spec.name] = module; spec.loader.exec_module(module)
episode, bootstrap = module.episode, module.bootstrap
PRIVATE, NONCE = "PRIVATE_SYNTHETIC_EPISODE_MPD", "a" * 32
END = 2000000000000
ADVERTISED = "https://vod-abematv.akamaized.net/program/" + episode.EPISODE + "/manifest.mpd?advertised=fresh%2Bvalue"
TEMPLATE = "https://license.p-c3-e.abema-tv.com/fixture/{ct}/{cid}{?t,pt}"


def fixtures():
    metadata = {"id": episode.EPISODE, "playback": {"arin": "FRESH_ARIN"}}
    gateway = {"streams": [{"drm": {"systemId": episode.DRM_SYSTEMS["CUSTOM"], "license": {"urlTemplate": TEMPLATE}},
        "manifests": [{"arin": "FRESH_MANIFEST", "url": ADVERTISED, "adInsertion": {"mode": 4}}]}]}
    binding = {"nonce": NONCE, "episodeId": episode.EPISODE, "arin": "FRESH_ARIN", "expiresAtMs": END,
        "browser": {"name": "Chrome", "major": "156", "os": "Linux", "engine": "Blink"},
        "uri": ADVERTISED + "&t=" + PRIVATE + "&enc=clear", "licenseUri": "https://license.p-c3-e.abema-tv.com/fixture/program/" + episode.EPISODE + "?t=" + PRIVATE,
        "streamIndex": 0, "manifestIndex": 0, "drm": "CUSTOM", "keySystem": "CUSTOM", "adMode": "CSAI", "gatewayCalls": 1}
    return binding, NONCE, metadata, END, gateway, PRIVATE


def graph():
    return {"coverage": "DECLARED_SUBSET", "periods": 1, "adaptationSets": 2, "representations": 3,
        "contentProtection": True, "cencPresent": True, "psshPresent": True, "keyIdMetadata": True,
        "segmentModel": "TEMPLATE", "auxiliary": {key: False for key in module.AUXILIARY},
        "references": {"sameOrigin": 0, "otherOrigin": 0, "unresolved": 2, "invalid": 0}}


def receipt():
    return {"nonce": NONCE, "outcome": "MPD_OBSERVED", "browserReadable": True, "corsPermission": "CORS_READABLE",
        "status": 200, "mime": "DASH_XML", "dispatched": True, "graph": graph(), "adContract": "UNKNOWN", "cleanupConfirmed": True}


class EpisodeManifestTests(unittest.TestCase):
    def test_exact_option_and_source_only_hook_are_inert(self):
        for arguments in ([], ["--run"], ["--run-episode-manifest", "--url=" + PRIVATE]):
            output = io.StringIO()
            with patch.object(module, "EpisodeManifestAttempt", side_effect=AssertionError("Inert")):
                self.assertEqual(module.main(arguments, output), 2)
            self.assertNotIn(PRIVATE, output.getvalue())
        self.assertIsNone(episode.EpisodeAttempt(io.StringIO()).bound_episode(None, None, None, None, None, None))

    def test_parent_rebinds_original_gateway_and_only_passes_selected_private_context(self):
        args = fixtures(); deadline = bootstrap.time.monotonic() + 20
        packet = module.private_input(*args, deadline)
        self.assertEqual(set(packet), {"episodeId", "nonce", "uri", "advertisement", "mediaToken", "protection", "adMode", "clockSampleNs", "deadlineNs", "expiresAtMs"})
        self.assertEqual(packet["advertisement"], {"uri": ADVERTISED, "systemId": episode.DRM_SYSTEMS["CUSTOM"], "adMode": 4})
        self.assertLessEqual(int(packet["deadlineNs"]), deadline * 1000000000)
        for key, value in (("nonce", "stale"), ("drm", "WIDEVINE"), ("adMode", "NONE"), ("uri", args[0]["uri"].replace(PRIVATE, "STALE"))):
            changed = copy.deepcopy(args); changed[0][key] = value
            with self.assertRaises(module.Refused): module.private_input(*changed, deadline)
        for end in (bootstrap.time.monotonic() - 1, bootstrap.time.monotonic() + 121):
            with self.assertRaises(module.Refused): module.private_input(*args, end)
        with patch.object(bootstrap.time, "time", return_value=END / 1000):
            with self.assertRaises(module.Refused): module.private_input(*args, deadline)

    def test_linux_python_and_node_share_the_precise_monotonic_epoch(self):
        self.assertEqual(bootstrap.time.get_clock_info("monotonic").implementation, "clock_gettime(CLOCK_MONOTONIC)")
        before = bootstrap.time.clock_gettime_ns(bootstrap.time.CLOCK_MONOTONIC)
        result = bootstrap.run_command([shutil.which("node"), "-e", "process.stdout.write(process.hrtime.bigint().toString())"], 5, 128)
        after = bootstrap.time.clock_gettime_ns(bootstrap.time.CLOCK_MONOTONIC)
        self.assertTrue(result.code == 0 and not result.stderr and before <= int(result.output) <= after, "Node/POSIX monotonic contract failed")
        packet = module.private_input(*fixtures(), bootstrap.time.monotonic() + 10)
        code = "import('" + SCRIPT.with_name("abema-browser-episode-manifest.mjs").as_uri() + "').then(async m=>{let s='';for await(const c of process.stdin)s+=c;m.validateInput(JSON.parse(s));process.stdout.write('VALID');}).catch(()=>process.exitCode=1)"
        result = bootstrap.run_command([shutil.which("node"), "-e", code], 5, 128, input_data=json.dumps(packet).encode())
        self.assertTrue(result.code == 0 and not result.stderr and result.output == b"VALID", "Cross-language private binding refused")

    def test_output_is_closed_bounded_and_associated_without_raw_metadata(self):
        self.assertEqual(module.validate_receipt(receipt(), NONCE), receipt())
        for changes in ({"nonce": "stale"}, {"body": PRIVATE}, {"graph": {**graph(), "url": PRIVATE}},
                        {"outcome": PRIVATE}, {"adContract": "ACCEPTED"}, {"dispatched": False},
                        {"browserReadable": False}, {"status": True}, {"status": 403}, {"mime": "UNSUPPORTED"},
                        {"graph": {**graph(), "periods": True}}, {"graph": {**graph(), "coverage": PRIVATE}},
                        {"graph": {**graph(), "periods": 10000, "adaptationSets": 10000}},
                        {"graph": {**graph(), "cencPresent": False}}, {"graph": {**graph(), "psshPresent": False, "cencPresent": False, "keyIdMetadata": True}},
                        {"graph": {**graph(), "references": {"sameOrigin": 2049, "otherOrigin": 0, "unresolved": 0, "invalid": 0}}}):
            with self.assertRaises(module.Refused): module.validate_receipt({**receipt(), **changes}, NONCE)
        xml = {**receipt(), "outcome": "XML_REFUSED", "graph": None}
        module.validate_receipt(xml, NONCE)
        for changes in ({"dispatched": False}, {"browserReadable": False, "corsPermission": "UNESTABLISHED", "status": None, "mime": "NOT_CHECKED"}, {"status": 403}, {"mime": "UNSUPPORTED"}):
            with self.assertRaises(module.Refused): module.validate_receipt({**xml, **changes}, NONCE)

    def test_transport_cleanup_and_single_use_precede_receipt_adoption(self):
        events, paths = [], []
        def runner(args, timeout, limit, **options):
            paths.append(Path(args[-1])); self.assertTrue(timeout <= 30 and options.get("display") is True)
            packet = json.loads(options["input_data"]); self.assertEqual(packet["nonce"], NONCE)
            self.assertFalse(any(PRIVATE in arg or "https://" in arg for arg in args))
            events.append("response"); return bootstrap.CommandResult(0, json.dumps(receipt()).encode())
        transport = module.EpisodeManifestTransport(runner)
        with patch.object(episode.source.owned_browser, "cleanup_owned_browser", side_effect=lambda path: events.append("cleanup")):
            transport.request(*fixtures(), bootstrap.time.monotonic() + 20)
        self.assertEqual(events, ["response", "cleanup"]); self.assertFalse(paths[0].exists())
        with self.assertRaises(module.Refused): transport.request(*fixtures(), bootstrap.time.monotonic() + 20)

    def test_cancel_malformed_and_uncertain_cleanup_never_adopt_a_result(self):
        for mode in ("cancel", "malformed", "child-uncertain", "parent-uncertain"):
            paths, cleaned = [], []
            def runner(args, *unused, **options):
                paths.append(Path(args[-1]))
                if mode == "cancel": raise bootstrap.Terminated()
                data = {**receipt(), "cleanupConfirmed": mode != "child-uncertain"}
                if mode == "malformed": data["private"] = PRIVATE
                return bootstrap.CommandResult(0, json.dumps(data).encode())
            def cleanup(path):
                cleaned.append(path)
                if mode == "parent-uncertain": raise module.Refused()
            transport = module.EpisodeManifestTransport(runner)
            expected = bootstrap.Terminated if mode == "cancel" else bootstrap.CleanupUnconfirmed if "uncertain" in mode else module.Refused
            with patch.object(episode.source.owned_browser, "cleanup_owned_browser", side_effect=cleanup):
                with self.assertRaises(expected): transport.request(*fixtures(), bootstrap.time.monotonic() + 20)
            self.assertEqual(cleaned, paths)
            if "uncertain" in mode:
                self.assertEqual(transport.retained_runtime, paths[0]); self.assertTrue(paths[0].exists()); shutil.rmtree(paths[0])
            else: self.assertFalse(paths[0].exists())

    def test_parent_rechecks_program_deadline_after_browser_cleanup(self):
        output = io.StringIO()
        class Transport:
            def request(self, *args): attempt.source_deadline = bootstrap.time.monotonic() - 1; return receipt()
        attempt = module.EpisodeManifestAttempt(output, manifest=Transport()); attempt.source_deadline = attempt.deadline
        with self.assertRaises(episode.EpisodeRefused): attempt.bound_episode(*fixtures())
        self.assertNotIn("EPISODE_MPD_RESULT", output.getvalue()); self.assertNotIn(PRIVATE, output.getvalue())


if __name__ == "__main__": unittest.main()
