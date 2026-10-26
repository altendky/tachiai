#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Closed, provider-free GLOBAL context and decoded evidence protocol tests."""
import importlib.util
import io
import json
import multiprocessing
from pathlib import Path
import shutil
import sys
import unittest
from contextlib import contextmanager
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[2] / "apps/web/scripts/abema-backend-playback.py"
spec = importlib.util.spec_from_file_location("web_abema_playback", SCRIPT)
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)
bootstrap, modern = module.bootstrap, module.modern
PRIVATE, NONCE = "PRIVATE_SYNTHETIC_PLAYBACK", "a" * 32
URI = "https://linear-abematv.akamaized.net/channel/abema-news/manifest.mpd"
REGION = {"isAllowed": True, "status": False, "isoCountryCode": "US", "divisionCode": 1, "cdnURL": modern.REGIONS["GLOBAL"]}


def wire(value): return json.dumps(value).encode()


def page(region=REGION): return b"<script>window.__CLIENT_REGION__ = " + wire(region) + b";</script>"


def fields():
    return {"binding": wire({"uri": URI + "?t=" + PRIVATE + "&enc=clear&dt=pc_chrome&ut=1", "nonce": NONCE,
        "channelId": "abema-news", "browser": {"name": "Chrome", "major": "156", "os": "Linux", "engine": "Blink"}}),
        "nonce": NONCE, "media_token": PRIVATE,
        "channel": {"id": "abema-news", "viewingOpened": True, "status": {}, "playback": {"dash": URI}}}


def observation(**changes):
    return {"nonce": NONCE, "outcome": "DECODED", "stage": "play", "publicationStage": "PUBLISH", "boundary": "NONE",
        "http": 200, "frames": 30, "advancedMs": 2000, "width": 640, "height": 360,
        "counts": {"manifest": 1, "initialization": 2, "segment": 2, "deniedDrm": 0},
        "formats": {"basis": "last-sdk-buffered-playhead", "video": {"kind": "video", "codec": "AVC", "observedAtPositionMs": 1000}},
        "mediaKeySessions": False, "cleanupConfirmed": True, **changes}


class Metadata:
    def __init__(self, region=REGION): self.region, self.calls = region, []
    def request(self, operation, fields, timeout):
        self.calls.append(operation)
        if operation == "REGION_PAGE": return 200, page(self.region)
        if operation == "SELECTED_REGION": return 200, b""
        if operation == "MODERN_CHANNELS": return 200, b'{"channels":[]}'
        raise AssertionError("Unexpected operation")


class Reply:
    def __init__(self, data=b"{}", status=200, headers=None):
        self.data, self.status, self.reads = data, status, 0
        self.headers = headers or {"Content-Type": "application/json"}
    def __enter__(self): return self
    def __exit__(self, *args): pass
    def read1(self, maximum):
        self.reads += 1
        chunk, self.data = self.data[:maximum], self.data[maximum:]
        return chunk


class Opener:
    def __init__(self, reply): self.reply = reply
    def open(self, *args, **kwargs): return self.reply


class ContextTests(unittest.TestCase):
    def test_opt_in_never_starts_provider_browser_or_container(self):
        with patch.object(module, "PlaybackAttempt", side_effect=AssertionError("must not start")):
            for args in ([], ["--run"], ["--run-manifest"], ["--run-global-playback", PRIVATE]):
                output = io.StringIO(); self.assertEqual(module.main(args, output), 2)
                self.assertNotIn(PRIVATE, output.getvalue())

    def test_canonical_bootstrap_identity_is_shared_by_spawn_targets(self):
        self.assertIs(modern.bootstrap, bootstrap)
        self.assertIs(module.source.bootstrap, bootstrap)
        self.assertIs(sys.modules[modern.worker.__module__], modern)
        self.assertIs(sys.modules[bootstrap.native_worker.__module__], bootstrap)

    def test_region_extraction_is_unique_bounded_and_strict(self):
        self.assertTrue(modern.extract_region(page()) == REGION)
        for raw in (page() + page(), b"", b"x" * (modern.LIMIT + 1), b"\xff", page().replace(b";</script>", b"</script>"),
                    page({**REGION, "divisionCode": True}), page({**REGION, "cdnURL": "https://foreign.invalid"}),
                    page().replace(b'"status": false', b'"status":false,"status":true')):
            with self.assertRaises((module.Refused, ValueError, UnicodeError)): modern.extract_region(raw)

    def test_only_fixed_metadata_gets_are_reachable(self):
        for operation, fields_, expected in (("REGION_PAGE", {}, modern.PAGE),
                ("SELECTED_REGION", {"selected": "GLOBAL"}, modern.REGIONS["GLOBAL"]),
                ("MODERN_CHANNELS", {"query": {"division": 1}}, "https://api.p-c3-e.abema-tv.com/v1/channels?division=1")):
            request = modern.request_spec(operation, fields_)
            self.assertTrue(request.full_url == expected)
            self.assertEqual(request.get_method(), "GET"); self.assertIsNone(request.data)
            for header in ("Authorization", "Cookie", "Referer", "Proxy-authorization"): self.assertIsNone(request.get_header(header))
        for operation, fields_ in (("GET", {"url": PRIVATE}), ("REGION_PAGE", {"url": PRIVATE}),
                ("SELECTED_REGION", {"selected": PRIVATE}), ("MODERN_CHANNELS", {"query": {"division": True}}),
                ("MODERN_CHANNELS", {"query": {"division": 1, "url": PRIVATE}})):
            with self.assertRaises(module.Refused): modern.request_spec(operation, fields_)

    def test_global_context_and_catalogue_are_one_shot_and_deadline_bound(self):
        for selected in (False, True):
            metadata = Metadata({**REGION, "status": selected}); transport = module.ModernTransport(metadata)
            with patch.object(bootstrap.time, "monotonic", return_value=10):
                with self.assertRaises(module.Refused): transport.request("CHANNELS", {}, 10)
                transport.prepare(20, lambda *a, **k: None, lambda r: {"division": 1})
                self.assertTrue(transport.request("CHANNELS", {}, 10)[0] == 200)
                with self.assertRaises(module.Refused): transport.request("CHANNELS", {}, 10)
                with self.assertRaises(module.Refused): transport.prepare(20, lambda *a, **k: None, lambda r: {"division": 1})
            self.assertEqual(metadata.calls, ["REGION_PAGE"] + (["SELECTED_REGION"] if selected else []) + ["MODERN_CHANNELS"])

    def test_wrong_context_mapper_or_expiry_never_reaches_catalogue_or_guest(self):
        for changes, mapper in (({"isAllowed": False}, {"division": 1}), ({"divisionCode": 0}, {"division": 1}),
                ({"cdnURL": modern.REGIONS["DOMESTIC"]}, {"division": 1}), ({}, {}), ({}, {"division": True})):
            metadata = Metadata({**REGION, **changes}); transport = module.ModernTransport(metadata)
            with patch.object(bootstrap.time, "monotonic", return_value=10):
                with self.assertRaises(module.Refused): transport.prepare(20, lambda *a, **k: None, lambda r: mapper)
            self.assertEqual(metadata.calls, ["REGION_PAGE"])
        transport = module.ModernTransport(Metadata())
        with patch.object(bootstrap.time, "monotonic", return_value=10):
            with self.assertRaises(module.Refused): transport.prepare(10, lambda *a, **k: None, lambda r: {})
        self.assertFalse(transport.metadata.calls)

    def test_metadata_non200_and_selected_region_bodies_are_never_read(self):
        for operation, fields_, status in (("MODERN_CHANNELS", {"query": {"division": 1}}, 302),
                ("MODERN_CHANNELS", {"query": {"division": 1}}, 403), ("SELECTED_REGION", {"selected": "GLOBAL"}, 200)):
            reply = Reply(PRIVATE.encode(), status)
            self.assertEqual(modern.exchange(operation, fields_, Opener(reply)), (status, b""))
            self.assertEqual(reply.reads, 0)

    def test_metadata_mime_encoding_size_and_declared_length_are_enforced(self):
        for headers in ({"Content-Type": "text/html"}, {"Content-Type": "application/json", "Content-Encoding": "gzip"},
                {"Content-Type": "application/json", "Content-Length": "524289"},
                {"Content-Type": "application/json", "Content-Length": "-1"}):
            reply = Reply(PRIVATE.encode(), headers=headers)
            with self.assertRaises(module.Refused): modern.exchange("MODERN_CHANNELS", {"query": {"division": 1}}, Opener(reply))
            self.assertEqual(reply.reads, 0)
        for reply in (Reply(b"x" * (modern.LIMIT + 1)), Reply(b"{}", headers={"Content-Type": "application/json", "Content-Length": "3"})):
            with self.assertRaises(module.Refused): modern.exchange("MODERN_CHANNELS", {"query": {"division": 1}}, Opener(reply))

    def test_spawn_canonical_import_and_closed_worker_refuse_before_network(self):
        # This launches the actual spawn import graph, with an operation refused
        # before opener.open; no provider request or browser/container is started.
        context = multiprocessing.get_context("spawn")
        receiver, sender = context.Pipe(duplex=False)
        process = context.Process(target=modern.worker, args=(sender, "UNADMITTED", {}), daemon=True)
        try:
            process.start(); sender.close()
            self.assertTrue(receiver.poll(10))
            self.assertEqual(receiver.recv_bytes(100), b'{"refused":true}')
            process.join(5); self.assertFalse(process.is_alive()); self.assertEqual(process.exitcode, 0)
        finally:
            sender.close(); receiver.close()
            if process.is_alive(): process.kill(); process.join(5)
            process.close()

    def test_context_failure_and_uncertain_cleanup_remain_distinct(self):
        for exception, outcome, confirmed in ((module.Refused, "REFUSED", True),
                (bootstrap.Terminated, "TERMINATED", True), (bootstrap.CleanupUnconfirmed, "CLEANUP_UNCONFIRMED", False)):
            transport = Metadata(); transport.prepare = lambda *a: (_ for _ in ()).throw(exception())
            output = io.StringIO(); attempt = module.PlaybackAttempt(output, transport=transport)
            with patch.object(module.source.SourceAttempt, "run", side_effect=AssertionError("must not begin guest")):
                self.assertEqual(attempt.run(), 1)
            events = [json.loads(row) for row in output.getvalue().splitlines()]
            self.assertEqual(events[0]["outcome"], outcome); self.assertIs(events[-1]["cleanupConfirmed"], confirmed)

    def test_mapper_runtime_uses_verified_public_files_and_preserves_uncertain_cleanup(self):
        data, created = b"synthetic public module", []
        class Attempt:
            phase, deadline, runner, retained_context_runtime = "START", 100, None, None
            def request(self, operation, fields_):
                self_test.assertEqual((operation, fields_), ("ASSET", {"name": "public.js"}))
                return data
        class Container:
            def __init__(self, runtime, config, runner, deadline, **options):
                created.append(runtime.parent)
                self_test.assertEqual(options, {"helper": "abema-source-helper.cjs", "private": True})
                self_test.assertTrue((runtime / "public.js").read_bytes() == data)
                self_test.assertTrue((runtime / "abema-source-helper.cjs").read_text().startswith("// Owned invocation"))
                self_test.assertTrue((runtime / "abema-guest-helper.cjs").is_file())
                self_test.assertEqual(config.stat().st_mode & 0o777, 0o700)
            @contextmanager
            def opened(self):
                try: yield "synthetic-owned-id"
                finally:
                    if uncertain: raise bootstrap.CleanupUnconfirmed()
            def command(self, *args, **kwargs):
                self_test.assertEqual(args, ("start", "--attach", "--interactive", "synthetic-owned-id"))
                self_test.assertTrue(json.loads(kwargs["input_data"]) == REGION)
                return bootstrap.CommandResult(0, b'{"query":{"division":1},"mapperMatched":true}')
        self_test = self
        for uncertain in (False, True):
            attempt = Attempt()
            with patch.object(bootstrap, "ASSETS", [("public.js", "unused", len(data), bootstrap.hashlib.sha256(data).hexdigest())]), patch.object(bootstrap, "OwnedContainer", Container):
                try:
                    if uncertain:
                        with self.assertRaises(bootstrap.CleanupUnconfirmed): module.map_division(attempt, REGION)
                        self.assertTrue(attempt.retained_context_runtime.is_dir())
                    else:
                        self.assertEqual(module.map_division(attempt, REGION), {"division": 1})
                        self.assertFalse(created[-1].exists())
                finally:
                    if attempt.retained_context_runtime: shutil.rmtree(attempt.retained_context_runtime)


class PlaybackTests(unittest.TestCase):
    def test_safe_decode_receipt_contains_no_private_binding(self):
        safe = module.playback_report(wire(observation()), NONCE)
        self.assertEqual(safe["outcome"], "DECODED"); self.assertNotIn("nonce", safe)
        self.assertNotIn(PRIVATE, json.dumps(safe)); self.assertNotIn("uri", safe)

    def test_unknown_false_or_wrong_owner_receipt_cannot_confirm_cleanup(self):
        for raw in (b"invalid", b"x" * 8193, wire(observation(cleanupConfirmed=False)), wire(observation(nonce="b" * 32)),
                    wire({key: val for key, val in observation().items() if key != "cleanupConfirmed"})):
            with self.assertRaises(bootstrap.CleanupUnconfirmed): module.playback_report(raw, NONCE)

    def test_closed_evidence_refuses_raw_metadata_nans_and_false_decodes(self):
        changes = [{"url": PRIVATE}, {"boundary": PRIVATE}, {"stage": PRIVATE}, {"publicationStage": PRIVATE},
            {"frames": True}, {"advancedMs": 0}, {"width": 0}, {"mediaKeySessions": True}, {"http": 0},
            {"counts": {"manifest": 1, "initialization": 0, "segment": 1, "deniedDrm": 0}},
            {"formats": {"basis": "last-sdk-buffered-playhead", "video": {"kind": "video", "codec": PRIVATE}}},
            {"formats": {"basis": "last-sdk-buffered-playhead", "audio": {"kind": "audio", "codec": "AVC"}}},
            {"formats": {"basis": "last-sdk-buffered-playhead", "video": {"kind": "video", "codec": "AVC", "channelCount": 2}}},
            {"formats": {"basis": "last-sdk-buffered-playhead", "video": {"kind": "video", "codec": "AVC", "width": 20000}}},
            {"formats": {"basis": "last-sdk-buffered-playhead", "video": {"kind": "video", "codec": "AVC", "width": float("nan")}}}]
        for changed in changes:
            with self.assertRaises((module.Refused, bootstrap.CleanupUnconfirmed)): module.playback_report(wire(observation(**changed)), NONCE)
        safe = module.playback_report(wire(observation(outcome="BOUNDARY", boundary="PROTECTION", publicationStage="FILTER", frames=0)), NONCE)
        self.assertEqual(safe["boundary"], "PROTECTION")
        self.assertEqual(safe["publicationStage"], "FILTER")

    def test_bound_source_reports_closed_observation_and_requires_actual_decode(self):
        for outcome in ("DECODED", "BOUNDARY", "REFUSED"):
            class Playback:
                def request(self, fields_, timeout): return module.playback_report(wire(observation(outcome=outcome)), NONCE)
            output = io.StringIO(); attempt = module.PlaybackAttempt(output, playback=Playback())
            arguments = fields()
            if outcome == "DECODED": attempt.bound_source(arguments["binding"], NONCE, PRIVATE, arguments["channel"])
            else:
                with self.assertRaises(module.Refused): attempt.bound_source(arguments["binding"], NONCE, PRIVATE, arguments["channel"])
            event = json.loads(output.getvalue())
            self.assertEqual(event["outcome"], "PLAYBACK_OBSERVATION"); self.assertEqual(event["playbackOutcome"], outcome)
            self.assertIs(event["sourceBound"], True); self.assertNotIn(PRIVATE, output.getvalue())

    def test_transport_private_input_repeats_full_binding_and_owns_short_runtime(self):
        paths = []
        def runner(args, timeout, limit, **kwargs):
            runtime = Path(args[-1]); paths.append(runtime)
            self.assertTrue(runtime.is_dir() and runtime.name.startswith("p-"))
            self.assertNotIn(PRIVATE, " ".join(args)); self.assertLessEqual(timeout, 45); self.assertEqual(limit, 8192)
            payload = json.loads(kwargs["input_data"])
            self.assertTrue(payload["uri"] == json.loads(fields()["binding"])["uri"])
            self.assertEqual(set(payload), {"uri", "nonce", "mediaToken", "advertisedUris", "remainingMs"})
            self.assertTrue(kwargs["display"]); return bootstrap.CommandResult(0, wire(observation()))
        transport = module.BrowserPlaybackTransport(runner)
        self.assertEqual(transport.request(fields(), 60)["outcome"], "DECODED")
        self.assertFalse(paths[0].exists())
        with self.assertRaises(module.Refused): transport.request(fields(), 60)
        changed = fields(); changed["nonce"] = "b" * 32
        with self.assertRaises(module.Refused): module.BrowserPlaybackTransport(runner).request(changed, 60)
        self.assertEqual(len(paths), 1)

    def test_runner_interruption_timeout_unknown_receipt_and_cleanup_failure(self):
        for exception in (bootstrap.Terminated, module.Refused):
            paths = []
            def runner(args, *a, **k): paths.append(Path(args[-1])); raise exception()
            with self.assertRaises(exception): module.BrowserPlaybackTransport(runner).request(fields(), 60)
            self.assertFalse(paths[0].exists())
        for receipt in (b"unknown", wire(observation(cleanupConfirmed=False))):
            transport = module.BrowserPlaybackTransport(lambda *a, **k: bootstrap.CommandResult(0, receipt))
            try:
                with self.assertRaises(bootstrap.CleanupUnconfirmed): transport.request(fields(), 60)
                self.assertTrue(transport.retained_runtime.is_dir())
            finally:
                if transport.retained_runtime: shutil.rmtree(transport.retained_runtime)
        transport = module.BrowserPlaybackTransport(lambda *a, **k: bootstrap.CommandResult(0, wire(observation())))
        try:
            with patch.object(module.source.owned_browser, "cleanup_owned_browser", side_effect=module.Refused):
                with self.assertRaises(bootstrap.CleanupUnconfirmed): transport.request(fields(), 60)
            self.assertTrue(transport.retained_runtime.is_dir())
        finally:
            if transport.retained_runtime: shutil.rmtree(transport.retained_runtime)


if __name__ == "__main__": unittest.main()
