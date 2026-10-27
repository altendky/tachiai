#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Offline exact-episode safety and old-mode isolation; no provider calls."""
import copy
from contextlib import contextmanager
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[2] / "apps/web/scripts/abema-backend-episode-source.py"
spec = importlib.util.spec_from_file_location("web_abema_episode_source", SCRIPT)
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)
bootstrap = module.bootstrap
PRIVATE = "PRIVATE_SYNTHETIC_VALUE"
NONCE = "a" * 32
DEVICE = "00000000-0000-4000-8000-000000000000"
NOW = 1800000000000
END = NOW // 1000 + 600
EPISODE = module.EPISODE
URI = "https://vod-abematv.akamaized.net/program/" + EPISODE + "/manifest.mpd?context=fresh%2Bvalue"
TEMPLATE = "https://license.p-c3-e.abema-tv.com/abematv-dash?t={t}&ct={ct}&cid={cid}"


def wire(value): return json.dumps(value).encode()


def metadata(**changes):
    return {"id": EPISODE, "label": {"free": True}, "playback": {"arin": "FRESH_ARIN"},
        "freeEndAt": END, "terms": [{"onDemandType": 3, "endAt": END}], **changes}


def gateway():
    return {"streams": [{"drm": {"systemId": module.DRM_SYSTEMS["WIDEVINE"], "license": {"urlTemplate": TEMPLATE}},
        "manifests": [{"arin": "FRESH_MANIFEST", "url": URI, "adInsertion": {"mode": 0}}]}]}


def binding():
    return {"nonce": NONCE, "episodeId": EPISODE, "arin": "FRESH_ARIN", "expiresAtMs": END * 1000,
        "browser": {"name": "Chrome", "major": "156", "os": "Linux", "engine": "Blink"},
        "uri": URI + "&t=" + PRIVATE + "&enc=wv", "licenseUri": TEMPLATE.replace("{t}", PRIVATE).replace("{ct}", "program").replace("{cid}", EPISODE),
        "streamIndex": 0, "manifestIndex": 0, "drm": "WIDEVINE", "keySystem": "WIDEVINE", "adMode": "NONE", "gatewayCalls": 1}


class EpisodeTests(unittest.TestCase):
    def test_opt_in_is_exact_and_inert(self):
        for args in ([], ["--run"], ["--run-episode-source", "--url=" + PRIVATE], ["--run-episode-source", "--episode=" + EPISODE]):
            output = io.StringIO()
            with patch.object(module, "EpisodeAttempt", side_effect=AssertionError("Must stay inert")):
                self.assertEqual(module.main(args, output), 2)
            self.assertNotIn(PRIVATE, output.getvalue())

    def test_eligibility_exact_identity_free_window_and_restriction_matrix(self):
        self.assertEqual(module.eligible_episode(wire(metadata()), NOW)[1], END * 1000)
        # Present true DRM is protected eligibility metadata, not a no-license inference.
        module.eligible_episode(wire(metadata(mediaStatus={"drm": True, "downloadOnlyDrm": False})), NOW)
        cases = [{"id": "other"}, {"label": {"free": 1}}, {"playback": {"arin": ".."}}, {"terms": []},
            {"freeEndAt": NOW // 1000}, {"freeEndAt": END * 1000}, {"freeEndAt": True},
            {"terms": [{"onDemandType": 1, "endAt": END}]}, {"terms": [{"onDemandType": 3, "endAt": END, "extra": 1}]},
            {"mediaStatus": {"drm": "false"}}, {"mediaStatus": {"downloadOnlyDrm": True}},
            {"rentalInfo": {}}, {"precedenceTerm": {}}, {"deviceRestriction": {}}, {"trialWatching": {"enabled": True}},
            {"partnerService": {"id": "partner"}}, {"externalProvider": {"type": False}},
            {"externalContent": {"marks": {"gambling": True}}}, {"partnerContentViewingAuthorities": ["authority"]}]
        for change in cases:
            with self.subTest(change=change), self.assertRaises(module.EpisodeRefused): module.eligible_episode(wire(metadata(**change)), NOW)
        for raw in (b'{"id":"x","id":"y"}', b'{"x":NaN}', b"x" * (module.LIMIT + 1), b'"\\ud800"'):
            with self.assertRaises(module.EpisodeRefused): module.eligible_episode(raw, NOW)

    def test_closed_metadata_operations_do_not_admit_media_license_or_arbitrary_url(self):
        episode = module.request_spec("EPISODE", {"bearer": PRIVATE})
        self.assertEqual(episode.get_method(), "GET")
        self.assertEqual(episode.get_header("Authorization"), "Bearer " + PRIVATE)
        self.assertTrue(episode.full_url.endswith("/v1/video/programs/" + EPISODE))
        gateway_request = module.request_spec("GATEWAY", {"arin": "FRESH_ARIN"})
        self.assertIsNone(gateway_request.get_header("Authorization"))
        for operation, fields in (("LICENSE", {}), ("MPD", {}), ("GATEWAY", {"url": PRIVATE}),
                                  ("GATEWAY", {"arin": "../escape"}), ("EPISODE", {"bearer": PRIVATE, "episode": "other"})):
            with self.assertRaises(module.EpisodeRefused): module.request_spec(operation, fields)

    def test_non_200_discards_body_and_bounds_response(self):
        class Reply:
            def __init__(self, status, body, headers=None): self.status, self.body, self.reads, self.headers = status, body, 0, headers or {"Content-Type": "application/json"}
            def __enter__(self): return self
            def __exit__(self, *args): pass
            def read1(self, size): self.reads += 1; part, self.body = self.body[:size], self.body[size:]; return part
        class Opener:
            def __init__(self, reply): self.reply = reply
            def open(self, request, timeout): return self.reply
        for status in (302, 403, 404, 500):
            reply = Reply(status, PRIVATE.encode())
            self.assertEqual(module.exchange("GATEWAY", {"arin": "FRESH_ARIN"}, Opener(reply)), (status, b""))
            self.assertEqual(reply.reads, 0)
        for reply in (Reply(200, b"{}", {"Content-Type": "text/html"}), Reply(200, b"{}", {"Content-Type": "application/json", "Content-Length": "3"}), Reply(200, b"x" * (module.LIMIT + 1))):
            with self.assertRaises(module.EpisodeRefused): module.exchange("GATEWAY", {"arin": "FRESH_ARIN"}, Opener(reply))

    def test_parent_repeats_source_query_configuration_and_context_binding(self):
        good = binding()
        validate = lambda packet, data=None: module.validate_binding(packet, "selection", NONCE, metadata(), END * 1000, data or gateway(), PRIVATE)
        validate(good)
        for change in ({"nonce": "b" * 32}, {"episodeId": "other"}, {"arin": "stale"}, {"expiresAtMs": END * 1000 + 1},
                       {"uri": good["uri"].replace(PRIVATE, "stale")}, {"uri": good["uri"] + "&extra=new"},
                       {"uri": good["uri"].replace("context=fresh%2Bvalue", "context=changed")},
                       {"uri": good["uri"] + "&enc=wv"}, {"uri": good["uri"].replace("enc=wv", "%65nc=wv")},
                       {"uri": good["uri"].replace(EPISODE, "other")}, {"uri": good["uri"].replace("https:", "http:")},
                       {"licenseUri": good["licenseUri"].replace(EPISODE, "other")}, {"licenseUri": good["licenseUri"] + "&extra=new"},
                       {"keySystem": "CUSTOM"}, {"drm": "CUSTOM", "keySystem": "CUSTOM", "uri": good["uri"].replace("enc=wv", "enc=clear")},
                       {"drm": []}, {"adMode": "CSAI"}, {"adMode": "SESSION_REQUIRED"}, {"gatewayCalls": True}, {"streamIndex": -1}, {"private": PRIVATE}):
            with self.subTest(fields=list(change)), self.assertRaises(module.EpisodeRefused): validate({**good, **change})
        for altered in (URI + "&t=collision", URI + "&enc=clear", URI + "&dtid=device", URI + "&context=duplicate", URI + "&bad=%xx"):
            data = gateway(); data["streams"][0]["manifests"][0]["url"] = altered
            with self.assertRaises(module.EpisodeRefused): validate(good, data)
        # The browser template, rather than an Android-specific route, owns the
        # configuration-only path. No result grants permission to fetch it.
        data, packet = gateway(), binding()
        data["streams"][0]["drm"]["license"]["urlTemplate"] = TEMPLATE.replace("/abematv-dash?", "/fixture-widevine?")
        packet["licenseUri"] = packet["licenseUri"].replace("/abematv-dash?", "/fixture-widevine?")
        validate(packet, data)
        for changed in ("/other", "/../escape", "/%2e%2e/escape", "//other.invalid", "/widevine#private"):
            broken = {**packet, "licenseUri": packet["licenseUri"].replace("/fixture-widevine?", changed + "?")}
            with self.assertRaises(module.EpisodeRefused): validate(broken, data)

    def test_configuration_checks_are_fixed_precise_and_preserve_first_guard(self):
        cases = [("PARENT_PROTECTION", {"keySystem": PRIVATE}, None),
            ("PARENT_PROTECTION", {"keySystem": PRIVATE, "gatewayCalls": 0}, None),
            ("PARENT_AD_MODE", {"adMode": PRIVATE}, None), ("PARENT_CALL_CAP", {"gatewayCalls": 0}, None),
            ("PARENT_PROTECTION_ASSOCIATION", {"drm": "CUSTOM", "keySystem": "CUSTOM"}, None),
            ("PARENT_AD_MODE_ASSOCIATION", {"adMode": "CSAI"}, None),
            ("PARENT_LICENSE_ENDPOINT", {"licenseUri": binding()["licenseUri"].replace("license.p-c3-e.abema-tv.com", "private.invalid")}, None),
            ("PARENT_LICENSE_TEMPLATE", {}, TEMPLATE + "&extra=" + PRIVATE),
            ("PARENT_LICENSE_QUERY", {"licenseUri": binding()["licenseUri"].replace(PRIVATE, "STALE")}, None)]
        for expected, changes, template in cases:
            raw = gateway()
            if template: raw["streams"][0]["drm"]["license"]["urlTemplate"] = template
            with self.subTest(expected=expected), self.assertRaises(module.EpisodeRefused) as caught:
                module.validate_binding({**binding(), **changes}, "selection", NONCE, metadata(), END * 1000, raw, PRIVATE)
            self.assertEqual((caught.exception.reason, caught.exception.configuration_check), ("CONFIGURATION", expected))
            self.assertNotIn(PRIVATE, str(caught.exception))
        for reason, check in (("BINDING", "PARENT_LICENSE_QUERY"), ("CONFIGURATION", PRIVATE), ("CONFIGURATION", [])):
            with self.assertRaises(module.Refused): module.EpisodeRefused(reason, check)

    def test_old_completion_never_receives_private_context_and_option_device_is_original_request(self):
        class Transport:
            def request(self, operation, fields, timeout):
                if operation == "GUEST": return 200, wire({"user_id": "fresh_user", "access_token": PRIVATE})
                if operation == "PROFILE": return 200, wire({"profile": {"userId": "fresh_user"}})
                if operation == "MEDIA_TOKEN": return 200, wire({"token": PRIVATE})
                raise AssertionError()
        class Old(bootstrap.Attempt):
            def prepare(self): return wire({"device_type": 3, "device_id": DEVICE, "application_key_secret": PRIVATE})
            def complete(self, token): self.received = token
            def complete_private(self, context): raise AssertionError("Old mode must not receive context")
        old = Old(io.StringIO(), Transport()); self.assertEqual(old.run(), 0); self.assertEqual(old.received, PRIVATE)
        self.assertNotIn(PRIVATE, old.output.getvalue())
        class New(Old):
            private_completion = True
            def complete(self, token): raise AssertionError("Opt-in must use private hook")
            def complete_private(self, context): self.observed = dict(context); self.reference = context
        new = New(io.StringIO(), Transport()); self.assertEqual(new.run(), 0)
        self.assertEqual(new.observed, {"userId": "fresh_user", "bearer": PRIVATE, "deviceId": DEVICE, "mediaToken": PRIVATE})
        self.assertEqual(new.reference, {}); self.assertNotIn(PRIVATE, new.output.getvalue())
        class Failed(New):
            def complete_private(self, context): self.reference = context; raise module.Refused()
        failed = Failed(io.StringIO(), Transport()); self.assertEqual(failed.run(), 1); self.assertEqual(failed.reference, {})

    def test_source_expiry_is_checked_before_gateway_or_late_private_binding(self):
        calls = []
        class Metadata:
            def request(self, *args): calls.append(args); return 200, b"{}"
        attempt = module.EpisodeAttempt(io.StringIO(), metadata=Metadata())
        with patch.object(bootstrap.time, "time", return_value=END + 1), self.assertRaises(module.EpisodeRefused):
            attempt.get("GATEWAY", {"arin": "FRESH_ARIN"}, END * 1000)
        self.assertEqual(calls, [])

    def test_two_phase_orchestration_is_closed_and_private_on_success_and_refusal(self):
        class Metadata:
            def __init__(self): self.calls = []
            def request(self, operation, fields, timeout):
                self.calls.append((operation, set(fields)))
                return 200, wire(metadata() if operation == "EPISODE" else gateway())
        class Attempt(module.EpisodeAttempt):
            @contextmanager
            def runtime(self):
                try: yield None, None
                finally: self.retired = True
            def helper(self, runtime, config, envelope):
                self.phases.append(envelope["phase"])
                self.assert_context = set(envelope["context"]) == {"userId", "deviceId", "mediaToken"}
                packet = binding(); packet["nonce"] = envelope["nonce"]
                if envelope["phase"] == "metadata": return {key: packet[key] for key in ("nonce", "episodeId", "arin", "expiresAtMs", "browser")}
                if self.attack is True: packet["uri"] = packet["uri"].replace(PRIVATE, "STALE")
                elif self.attack == "configuration": packet["keySystem"] = PRIVATE
                return packet
        for attack in (False, True, "configuration"):
            output, transport = io.StringIO(), Metadata()
            attempt = Attempt(output, metadata=transport); attempt.phases = []; attempt.attack = attack
            attempt.navigator = {"platform": "Linux x86_64"}
            context = {"userId": "fresh_user", "bearer": PRIVATE, "deviceId": DEVICE, "mediaToken": PRIVATE}
            with patch.object(bootstrap.time, "time", return_value=NOW / 1000):
                if attack:
                    with self.assertRaises(module.EpisodeRefused): attempt.complete_private(context)
                else: attempt.complete_private(context)
            self.assertEqual(context, {}); self.assertTrue(attempt.retired and attempt.assert_context)
            self.assertEqual(attempt.phases, ["metadata", "selection"])
            self.assertEqual(transport.calls, [("EPISODE", {"bearer"}), ("GATEWAY", {"arin"})])
            self.assertNotIn(PRIVATE, output.getvalue()); self.assertNotIn("https://", output.getvalue())
            self.assertEqual("EPISODE_SOURCE_CONFIG_ACCEPTED" in output.getvalue(), not attack)
            self.assertEqual('"configurationCheck": "PARENT_PROTECTION"' in output.getvalue(), attack == "configuration")

    def test_helper_cleanup_precedes_adoption_and_late_result_refuses(self):
        events = []
        class Container:
            def __init__(self, *args, **kwargs):
                self.private = kwargs.get("private") is True
                self.helper = kwargs.get("helper")
            @contextmanager
            def opened(self):
                try: yield "owned"
                finally: events.append("cleanup")
            def command(self, *args, **kwargs):
                self.assertion = kwargs.get("input_data") is not None
                events.append("result")
                return bootstrap.CommandResult(0, b'{}')
        attempt = module.EpisodeAttempt(io.StringIO())
        envelope = {"expiresAtMs": END * 1000}
        with patch.object(bootstrap, "OwnedContainer", Container), patch.object(bootstrap.time, "time", side_effect=[NOW / 1000, END + 1]):
            with self.assertRaises(module.EpisodeRefused) as caught: attempt.helper(None, None, envelope)
        self.assertEqual(caught.exception.reason, "LIFETIME"); self.assertEqual(events, ["result", "cleanup"])
        events.clear(); attempt.deadline = 1000; attempt.source_deadline = 700; attempt.source_expiry = END * 1000
        with patch.object(bootstrap, "OwnedContainer", Container), patch.object(bootstrap.time, "monotonic", side_effect=[699, 700]), patch.object(bootstrap.time, "time", return_value=NOW / 1000 - 3600):
            with self.assertRaises(module.EpisodeRefused): attempt.helper(None, None, envelope)
        self.assertEqual(events, ["result", "cleanup"])
        attempt.deadline = float("inf"); attempt.source_deadline = None; attempt.source_expiry = None
        class Uncertain(Container):
            @contextmanager
            def opened(self):
                yield "owned"
                raise bootstrap.CleanupUnconfirmed()
        with patch.object(bootstrap, "OwnedContainer", Uncertain), patch.object(bootstrap.time, "time", return_value=NOW / 1000):
            with self.assertRaises(bootstrap.CleanupUnconfirmed): attempt.helper(None, None, envelope)

    def test_request_start_expiry_is_monotonic_immutable_and_rollback_safe(self):
        attempt = module.EpisodeAttempt(io.StringIO()); attempt.deadline = 1000
        with patch.object(bootstrap.time, "monotonic", return_value=200), patch.object(bootstrap.time, "time", return_value=NOW / 1000):
            attempt.bind_expiry(END * 1000, NOW, 100)
        self.assertEqual(attempt.source_deadline, 700)
        for clock in (700, 701):
            with patch.object(bootstrap.time, "monotonic", return_value=clock), patch.object(bootstrap.time, "time", return_value=NOW / 1000 - 3600):
                with self.assertRaises(module.EpisodeRefused): attempt.alive(END * 1000)
        with self.assertRaises(module.EpisodeRefused): attempt.bind_expiry((END + 3600) * 1000, NOW, 100)
        delayed = module.EpisodeAttempt(io.StringIO()); delayed.deadline = 1000
        # A delayed response cannot start a new full free-window duration.
        with patch.object(bootstrap.time, "monotonic", return_value=701), patch.object(bootstrap.time, "time", return_value=NOW / 1000):
            with self.assertRaises(module.EpisodeRefused): delayed.bind_expiry(END * 1000, NOW, 100)
        self.assertEqual(delayed.source_deadline, 700)

    def test_runtime_cleanup_failure_retains_public_files_and_transport_is_one_use(self):
        attempt = module.EpisodeAttempt(io.StringIO())
        with tempfile.TemporaryDirectory(prefix="episode-cleanup-test-") as directory:
            root = Path(directory) / "runtime-parent"; root.mkdir()
            with patch.object(module.tempfile, "mkdtemp", return_value=str(root)), patch.object(bootstrap, "ASSETS", ()):
                with self.assertRaises(bootstrap.CleanupUnconfirmed):
                    with attempt.runtime(): raise bootstrap.CleanupUnconfirmed()
            self.assertEqual(attempt.retained_episode_runtime, root); self.assertTrue(root.is_dir())
        class NoSpawn:
            def Pipe(self, **kwargs): raise AssertionError("Consumed operation must not spawn")
        transport = module.EpisodeTransport(NoSpawn()); transport.used.add("GATEWAY")
        with self.assertRaises(module.EpisodeRefused): transport.request("GATEWAY", {"arin": "FRESH_ARIN"}, 1)
        with self.assertRaises(module.EpisodeRefused): transport.request("EPISODE", {"bearer": PRIVATE}, 0)

    def test_larger_private_pipe_is_episode_only_and_bounded(self):
        data = b"x" * 32769
        command = [sys.executable, "-c", "import sys; print(len(sys.stdin.buffer.read()))"]
        with self.assertRaises(module.Refused): bootstrap.run_command(command, 2, input_data=data)
        result = module.episode_command(command, 2, input_data=data)
        self.assertEqual((result.code, result.output, result.stderr), (0, b"32769\n", False))
        with self.assertRaises(module.Refused): module.episode_command(command, 2, input_data=b"x" * (2 * 1024 * 1024 + 1))

    def test_private_helper_refusal_diagnostics_have_independent_association_guard(self):
        class Container:
            def __init__(self, *args, **kwargs): pass
            @contextmanager
            def opened(self):
                try: yield "owned"
                finally: retired.append(True)
            def command(self, *args, **kwargs): return bootstrap.CommandResult(0, wire(reply))
        attempt = module.EpisodeAttempt(io.StringIO())
        valid = {"outcome": "REFUSED", "reason": "CONFIGURATION", "configurationCheck": "HELPER_FACTORY"}
        for reply in (valid, {**valid, "configurationCheck": PRIVATE}, {**valid, "configurationCheck": []},
                      {**valid, "configurationCheck": "PARENT_LICENSE_QUERY"}, {**valid, "reason": "BINDING"},
                      {**valid, "private": PRIVATE}, {"outcome": "REFUSED", "reason": "CONFIGURATION"}):
            retired = []
            with patch.object(bootstrap, "OwnedContainer", Container), patch.object(bootstrap.time, "time", return_value=NOW / 1000):
                with self.assertRaises(module.EpisodeRefused) as caught: attempt.helper(None, None, {"expiresAtMs": END * 1000})
            expected = ("CONFIGURATION", "HELPER_FACTORY") if reply == valid else ("RUNTIME", None)
            self.assertEqual((caught.exception.reason, caught.exception.configuration_check), expected)
            self.assertEqual(retired, [True]); self.assertNotIn(PRIVATE, str(caught.exception))

    def test_metadata_worker_cleanup_precedes_reply_on_timeout_cancel_and_uncertainty(self):
        for mode in ("ok", "timeout", "cancel", "uncertain"):
            events = []
            class Receiver:
                def poll(self, timeout):
                    if mode == "cancel": raise bootstrap.Terminated()
                    return mode != "timeout"
                def recv_bytes(self, maximum): return wire({"status": 200, "body": "e30="})
                def close(self): events.append("receiver-close")
            class Sender:
                def close(self): events.append("sender-close")
            class Process:
                def start(self): events.append("start")
                def join(self, timeout): events.append("join")
                def is_alive(self): return mode == "uncertain"
                def terminate(self): events.append("terminate")
                def kill(self): events.append("kill")
                def close(self): events.append("process-close")
            class Context:
                def Pipe(self, **options): return Receiver(), Sender()
                def Process(self, **options): return Process()
            transport = module.EpisodeTransport(Context())
            if mode == "ok": self.assertEqual(transport.request("EPISODE", {"bearer": PRIVATE}, 1), (200, b"{}"))
            else:
                exception = {"timeout": module.EpisodeRefused, "cancel": bootstrap.Terminated, "uncertain": bootstrap.CleanupUnconfirmed}[mode]
                with self.assertRaises(exception): transport.request("EPISODE", {"bearer": PRIVATE}, 1)
            self.assertIn("receiver-close", events); self.assertIn("sender-close", events)
            self.assertIn("join", events)
            if mode == "uncertain": self.assertIn("kill", events)
            else: self.assertEqual(events[-1], "process-close")
            with self.assertRaises(module.EpisodeRefused): transport.request("EPISODE", {"bearer": PRIVATE}, 1)


if __name__ == "__main__": unittest.main()
