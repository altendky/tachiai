#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Offline exact-MPD protocol, XML/privacy and owned worker lifecycle tests."""
import base64
import importlib.util
import io
import json
import os
from pathlib import Path
import signal
import shutil
import subprocess
import sys
import tempfile
import unittest
import time
from types import SimpleNamespace
from unittest.mock import patch

SOURCE = Path(__file__).resolve().parents[2] / "apps/web/scripts/abema-backend-manifest.py"
spec = importlib.util.spec_from_file_location("web_abema_manifest", SOURCE)
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)
PRIVATE = "PRIVATE_SYNTHETIC_MANIFEST_SENTINEL"
NONCE = "a" * 32
URI = "https://linear-abematv.akamaized.net/channel/abema-news/manifest.mpd"
CHANNEL = {"id": "abema-news", "viewingOpened": True, "status": {}, "playback": {"dash": URI}}
BOUND_URI = URI + "?t=" + PRIVATE + "&enc=clear&dt=pc_chrome&ut=1"
BROWSER = {"name": "Chrome", "major": "156", "os": "Linux", "engine": "Blink"}
MPD = b'<MPD xmlns="urn:mpeg:dash:schema:mpd:2011"><Period/></MPD>'


def encoded(value):
    return json.dumps(value).encode()


def fields(**changes):
    value = {"binding": encoded({"uri": BOUND_URI, "channelId": "abema-news", "nonce": NONCE, "browser": BROWSER}),
             "nonce": NONCE, "media_token": PRIVATE, "channel": CHANNEL}
    value.update(changes)
    return value


class Reply:
    def __init__(self, data=MPD, status=200, **headers):
        self.data, self.status, self.reads, self.closed = data, status, 0, False
        self.headers = {"Content-Type": "application/dash+xml", **headers}

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.closed = True

    def read1(self, maximum):
        self.reads += 1
        chunk, self.data = self.data[:maximum], self.data[maximum:]
        return chunk


class Opener:
    def __init__(self, reply):
        self.reply, self.requests = reply, []

    def open(self, request, timeout):
        self.requests.append((request, timeout))
        return self.reply


class Pipe:
    def __init__(self, reply=None):
        self.closed, self.reply, self.timeout, self.maximum = False, reply, None, None

    def close(self):
        self.closed = True

    def poll(self, timeout):
        self.timeout = timeout
        return self.reply is not None

    def recv_bytes(self, maximum):
        self.maximum = maximum
        return self.reply


class Process:
    def __init__(self, stubborn=False, termination_stubborn=False):
        self.started = self.closed = self.terminated = self.killed = False
        self.stubborn, self.termination_stubborn = stubborn, termination_stubborn

    def start(self):
        self.started = True

    def join(self, timeout):
        pass

    def is_alive(self):
        return self.started and (self.stubborn or not self.killed and (self.termination_stubborn or not self.terminated))

    def terminate(self):
        self.terminated = True

    def kill(self):
        self.killed = True

    def close(self):
        self.closed = True


class Context:
    def __init__(self, reply=None, **process_options):
        self.receiver, self.sender, self.process = Pipe(reply), Pipe(), Process(**process_options)

    def Pipe(self, duplex):
        self.duplex = duplex
        return self.receiver, self.sender

    def Process(self, **arguments):
        self.arguments = arguments
        return self.process


class ManifestTests(unittest.TestCase):
    def test_opt_in_is_exact_without_any_browser_provider_or_worker(self):
        with patch.object(module, "ManifestAttempt", side_effect=AssertionError("must not start")):
            for arguments in ([], ["--run"], ["--run-source"], ["--run-manifest", PRIVATE], [PRIVATE]):
                output = io.StringIO()
                self.assertEqual(module.main(arguments, output), 2)
                self.assertNotIn(PRIVATE, output.getvalue())

    def test_exact_bound_get_has_no_auth_cookie_or_supplied_transport_url(self):
        request = module.manifest_request_spec(fields())
        self.assertEqual(request.full_url, BOUND_URI)
        self.assertEqual(request.get_method(), "GET")
        self.assertIsNone(request.data)
        self.assertIsNone(request.get_header("Authorization"))
        self.assertIsNone(request.get_header("Cookie"))
        self.assertIsNone(request.get_header("Proxy-authorization"))
        self.assertIsNone(request.get_header("Referer"))
        self.assertEqual(request.get_header("Accept-encoding"), "identity")
        with self.assertRaises(module.Refused):
            module.manifest_request_spec({**fields(), "url": URI})

    def test_request_revalidates_nonce_token_exact_channel_advertisement_browser_and_origin(self):
        for changes in ({"nonce": "b" * 32}, {"media_token": "stale"},
                        {"channel": {**CHANNEL, "id": "another"}},
                        {"channel": {**CHANNEL, "playback": {"dash": URI.replace("manifest", "other")}}},
                        {"channel": {**CHANNEL, "unexpected": PRIVATE}}, {"binding": b"x" * 8193}):
            with self.assertRaises((module.Refused, ValueError)):
                module.manifest_request_spec(fields(**changes))
        binding = json.loads(fields()["binding"])
        for change in ({"uri": BOUND_URI.replace("linear-abematv.akamaized.net", "private.example")},
                       {"uri": BOUND_URI.replace("abema-news/", "another/")},
                       {"uri": BOUND_URI + "&t=" + PRIVATE}, {"uri": BOUND_URI + "#"},
                       {"browser": {**BROWSER, "os": "Windows"}}):
            with self.assertRaises((module.Refused, ValueError)):
                module.manifest_request_spec(fields(binding=encoded({**binding, **change})))

    def test_non200_and_redirect_bodies_are_never_read(self):
        for status in (201, 301, 302, 307, 308, 401, 403, 404, 500):
            response = Reply(PRIVATE.encode(), status)
            opener = Opener(response)
            reply = module.native_manifest_exchange(fields(), opener)
            self.assertEqual((reply.status, reply.mime, reply.accepted, reply.body), (status, "NOT_CHECKED", False, b""))
            self.assertEqual(response.reads, 0)
            self.assertTrue(response.closed)
            self.assertEqual(len(opener.requests), 1)
            self.assertEqual(opener.requests[0][1], 10)
        self.assertIsNone(module.bootstrap.NoRedirect().redirect_request(None, None, 302, PRIVATE, {}, PRIVATE))

    def test_mime_and_header_refusals_do_not_consume_bodies(self):
        for headers in ({"Content-Type": "text/html"}, {"Content-Type": PRIVATE},
                        {"Content-Encoding": "gzip"}, {"Content-Length": "524289"},
                        {"Content-Length": "-1"}, {"Content-Length": "1e9"}):
            response = Reply(PRIVATE.encode(), **headers)
            reply = module.native_manifest_exchange(fields(), Opener(response))
            self.assertFalse(reply.accepted)
            self.assertEqual(reply.body, b"")
            self.assertEqual(response.reads, 0)
            self.assertTrue(response.closed)
            self.assertNotIn(PRIVATE, repr(reply))

    def test_xml_mime_types_body_bound_and_content_length_are_checked(self):
        for mime, classification in module.MIMES.items():
            reply = module.native_manifest_exchange(fields(), Opener(Reply(**{"Content-Type": mime + "; charset=utf-8"})))
            self.assertTrue(reply.accepted)
            self.assertEqual((reply.mime, reply.body), (classification, MPD))
        for raw, length in ((b"", None), (MPD, "999"), (b"x" * (module.MPD_LIMIT + 1), None)):
            response = Reply(raw, **({"Content-Length": length} if length else {}))
            reply = module.native_manifest_exchange(fields(), Opener(response))
            self.assertFalse(reply.accepted)
            self.assertEqual(reply.body, b"")
            self.assertTrue(response.closed)

    def test_mpd_parser_reports_structure_without_ids_urls_or_decoding_pssh(self):
        self.assertEqual(module.inspect_mpd(MPD), {"cencPresent": False, "psshPresent": False})
        raw = ('<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" xmlns:c="urn:mpeg:cenc:2013">'
               '<Period><AdaptationSet><ContentProtection c:default_KID="' + PRIVATE + '">'
               '<c:pssh>deliberately invalid base64 ' + PRIVATE + '</c:pssh></ContentProtection>'
               '<Representation id="' + PRIVATE + '"><BaseURL>https://private.invalid/' + PRIVATE + '</BaseURL>'
               '</Representation></AdaptationSet></Period></MPD>').encode()
        evidence = module.inspect_mpd(raw)
        self.assertEqual(evidence, {"cencPresent": True, "psshPresent": True})
        self.assertNotIn(PRIVATE, json.dumps(evidence))
        self.assertEqual(module.inspect_mpd(b'\xef\xbb\xbf' + MPD), {"cencPresent": False, "psshPresent": False})

    def test_dtd_internal_external_parameter_entities_and_xinclude_are_refused(self):
        documents = [
            b'<!DOCTYPE MPD [<!ENTITY x "payload">]>' + MPD,
            b'<!DOCTYPE MPD SYSTEM "file:///private">' + MPD,
            b'<!DOCTYPE MPD [<!ENTITY % x SYSTEM "https://private.invalid/"> %x;]>' + MPD,
            b'<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" xmlns:xi="http://www.w3.org/2001/XInclude"><xi:include href="file:///private"/></MPD>',
            b'<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" xmlns:xi="http://www.w3.org/2001/XInclude"><xi:fallback/></MPD>',
            b'<MPD xmlns="urn:mpeg:dash:schema:mpd:2011">&external;</MPD>',
        ]
        with patch.object(module.urllib.request, "urlopen", side_effect=AssertionError("XML must not fetch")):
            for raw in documents:
                with self.assertRaises((module.Refused, module.ET.ParseError)):
                    module.inspect_mpd(raw)

    def test_malformed_wrong_namespace_encoding_size_depth_and_tree_limits_refuse(self):
        for raw in (b"", b"x" * (module.MPD_LIMIT + 1), b"<MPD>", b"<MPD/>", b"<html/>",
                    b"\xff", MPD.decode().encode("utf-16"),
                    b'<MPD xmlns="urn:mpeg:dash:schema:mpd:2011">' + b'<x>' * 65 + b'</x>' * 65 + b'</MPD>',
                    b'<MPD xmlns="urn:mpeg:dash:schema:mpd:2011">' + b'<x/>' * 10001 + b'</MPD>',
                    ('<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" ' + ' '.join('a' + str(i) + '="x"' for i in range(65)) + '/>').encode()):
            with self.assertRaises((module.Refused, module.ET.ParseError, UnicodeError)):
                module.inspect_mpd(raw)

    def test_worker_deadline_single_use_and_timeout_cleanup_are_bounded(self):
        context = Context()
        transport = module.ManifestTransport(context)
        with self.assertRaises(module.Refused):
            transport.request(fields(), 100)
        self.assertEqual(context.receiver.timeout, 10)
        self.assertFalse(context.duplex)
        self.assertIs(context.arguments["target"], module.manifest_worker)
        self.assertTrue(context.arguments["daemon"])
        self.assertTrue(context.process.terminated)
        self.assertTrue(context.process.closed)
        self.assertTrue(context.receiver.closed and context.sender.closed)
        with self.assertRaises(module.Refused):
            transport.request(fields(), 1)

    def test_worker_ipc_rejects_arbitrary_metadata_bad_schema_body_and_unbounded_replies(self):
        good = {"status": 200, "mime": "DASH_XML", "accepted": True, "body": base64.b64encode(MPD).decode()}
        context = Context(encoded(good))
        reply = module.ManifestTransport(context).request(fields(), 2)
        self.assertEqual(reply.body, MPD)
        for value in ({**good, "uri": PRIVATE}, {**good, "mime": PRIVATE},
                      {**good, "status": True}, {**good, "accepted": False},
                      {**good, "body": base64.b64encode(b"x" * (module.MPD_LIMIT + 1)).decode()},
                      {**good, "body": "invalid base64"}):
            context = Context(encoded(value))
            with self.assertRaises((module.Refused, ValueError)):
                module.ManifestTransport(context).request(fields(), 2)
            self.assertTrue(context.receiver.closed and context.sender.closed and context.process.closed)

    def test_worker_kill_escalation_and_unconfirmed_cleanup_are_not_silently_accepted(self):
        context = Context(termination_stubborn=True)
        with self.assertRaises(module.Refused):
            module.ManifestTransport(context).request(fields(), 0.1)
        self.assertTrue(context.process.killed and context.process.closed)
        context = Context(stubborn=True)
        with self.assertRaises(module.bootstrap.CleanupUnconfirmed):
            module.ManifestTransport(context).request(fields(), 0.1)
        self.assertTrue(context.process.killed)

    def test_sigterm_unwinds_private_worker_and_closes_pipes(self):
        context = Context()
        def interrupt(timeout):
            signal.raise_signal(signal.SIGTERM)
        context.receiver.poll = interrupt
        with module.bootstrap.TerminationGuard():
            with self.assertRaises(module.bootstrap.Terminated):
                module.ManifestTransport(context).request(fields(), 1)
        self.assertTrue(context.process.terminated and context.process.closed)
        self.assertTrue(context.sender.closed and context.receiver.closed)

    def test_bound_hook_emits_only_closed_evidence_and_discards_manifest(self):
        for status, raw, accepted in ((200, MPD, True), (403, b"", False), (200, b"not XML " + PRIVATE.encode(), False)):
            calls, output = [], io.StringIO()
            reply = module.ManifestReply(status, "DASH_XML" if status == 200 else "NOT_CHECKED", status == 200, raw)
            class Transport:
                def request(self, data, timeout):
                    calls.append(data)
                    module.manifest_request_spec(data)
                    return reply
            attempt = module.ManifestAttempt(output, manifest_transport=Transport())
            if accepted:
                attempt.bound_source(**fields())
            else:
                with self.assertRaises(module.Refused):
                    attempt.bound_source(**fields())
            self.assertEqual(len(calls), 1)
            self.assertEqual(reply.body, b"")
            self.assertNotIn(PRIVATE, output.getvalue())
            self.assertNotIn(URI, output.getvalue())
            self.assertEqual("MANIFEST_ACCEPTED" in output.getvalue(), accepted)

    def test_default_source_hook_remains_inert_and_manifest_uncertainty_propagates(self):
        attempt = module.source.SourceAttempt(io.StringIO())
        self.assertIsNone(attempt.bound_source(**fields()))
        class Transport:
            def request(self, *args):
                raise module.bootstrap.CleanupUnconfirmed()
        attempt = module.ManifestAttempt(io.StringIO(), manifest_transport=Transport())
        with self.assertRaises(module.bootstrap.CleanupUnconfirmed):
            attempt.bound_source(**fields())

    def test_xml_cancellation_preserves_termination_and_discards_body(self):
        reply = module.ManifestReply(200, "DASH_XML", True, MPD)
        class Transport:
            def request(self, *args):
                return reply
        output = io.StringIO()
        attempt = module.ManifestAttempt(output, manifest_transport=Transport())
        with patch.object(module, "inspect_mpd", side_effect=module.bootstrap.Terminated):
            with self.assertRaises(module.bootstrap.Terminated):
                attempt.bound_source(**fields())
        self.assertEqual(reply.body, b"")
        self.assertNotIn("MANIFEST_XML_REFUSED", output.getvalue())

    def test_full_attempt_reports_manifest_worker_cleanup_uncertainty_without_private_error(self):
        class Transport:
            def request(self, *args):
                raise module.bootstrap.CleanupUnconfirmed(PRIVATE)
        class Attempt(module.ManifestAttempt):
            def browser_snapshot(self):
                return {}

            def prepare(self):
                return b"unused"

        # Keep the existing base owner/error/cleanup path and exercise the new
        # continuation directly, without creating real accounts or processes.
        attempt = Attempt(io.StringIO(), manifest_transport=Transport())
        responses = [
            encoded({"user_id": "synthetic_user", "access_token": PRIVATE}),
            encoded({"profile": {"userId": "synthetic_user"}}), encoded({"token": PRIVATE}),
        ]
        attempt.request = lambda *args: responses.pop(0)
        attempt.complete = lambda token: attempt.bound_source(**fields())
        self.assertEqual(attempt.run(), 1)
        self.assertIn('"cleanupConfirmed": false', attempt.output.getvalue())
        self.assertNotIn(PRIVATE, attempt.output.getvalue())


class BrowserManifestTests(unittest.TestCase):
    def packet(self, **changes):
        return {"nonce": NONCE, "browserReadable": True, "corsPermission": "CORS_READABLE",
                "status": 200, "mime": "DASH_XML", "accepted": True,
                "body": base64.b64encode(MPD).decode(), "cleanupConfirmed": True, **changes}

    def test_browser_private_uri_uses_only_stdin_and_owned_runtime_is_removed(self):
        calls = []
        def runner(args, timeout, limit, **kwargs):
            calls.append((args, timeout, limit))
            self.assertTrue(Path(args[-1]).is_dir())
            self.assertNotIn(PRIVATE, repr(args))
            self.assertNotIn(URI, repr(args))
            self.assertEqual(json.loads(kwargs["input_data"]), {"uri": BOUND_URI, "nonce": NONCE,
                "mediaToken": PRIVATE, "advertisedUris": [URI]})
            self.assertLessEqual(len(kwargs["input_data"]), module.BROWSER_INPUT_LIMIT)
            self.assertTrue(kwargs["display"])
            return module.bootstrap.CommandResult(0, encoded(self.packet()))
        transport = module.BrowserManifestTransport(runner)
        reply = transport.request(fields(), 100)
        self.assertEqual((reply.body, reply.browser_readable, reply.cors_permission), (MPD, True, "CORS_READABLE"))
        self.assertFalse(Path(calls[0][0][-1]).exists())
        self.assertEqual(calls[0][1], 30)
        with self.assertRaises(module.Refused):
            transport.request(fields(), 1)
        self.assertEqual(len(calls), 1)

    def test_browser_binding_refusal_happens_before_any_process_or_fetch(self):
        with self.assertRaises(module.Refused):
            module.BrowserManifestTransport(lambda *args, **kwargs: self.fail("must not start")).request(fields(media_token="stale"), 1)

    @unittest.skipUnless(shutil.which("node"), "Node needed only for private-envelope refusal fixture")
    def test_browser_private_envelope_byte_limit_refuses_before_launch_without_echoing_data(self):
        with tempfile.TemporaryDirectory() as directory:
            runtime = Path(directory)
            oversized = (PRIVATE.encode() * (module.BROWSER_INPUT_LIMIT // len(PRIVATE) + 1))
            result = subprocess.run([shutil.which("node"), str(SOURCE.with_name("abema-browser-manifest.mjs")),
                "--owned-runtime", str(runtime)], input=oversized, capture_output=True, timeout=5)
            self.assertEqual(json.loads(result.stdout), {"refused": True, "cleanupConfirmed": True})
            self.assertEqual(result.stderr, b"")
            self.assertNotIn(PRIVATE.encode(), result.stdout)
            self.assertFalse((runtime / "launch-started.json").exists())

    def test_manifest_repeats_complete_advertisement_binding_and_browser_receives_only_private_context(self):
        advertised = URI + "?context=" + PRIVATE
        bound = advertised + "&t=" + PRIVATE + "&enc=clear&dt=pc_chrome&ut=1"
        alternate = URI.replace("manifest.mpd", "alternate.mpd")
        channel = {**CHANNEL, "playback": {"dash": advertised, "yospace": {"dash": alternate}}}
        binding = encoded({"uri": bound, "channelId": "abema-news", "nonce": NONCE, "browser": BROWSER})
        data = fields(channel=channel, binding=binding)
        self.assertEqual(module.manifest_request_spec(data).full_url, bound)
        calls = []
        def runner(args, timeout, limit, **kwargs):
            calls.append(args)
            self.assertNotIn(PRIVATE, repr(args)); self.assertNotIn(URI, repr(args))
            self.assertEqual(json.loads(kwargs["input_data"]), {"uri": bound, "nonce": NONCE,
                "mediaToken": PRIVATE, "advertisedUris": [advertised, alternate]})
            return module.bootstrap.CommandResult(0, encoded(self.packet(status=403, accepted=False, body="")))
        reply = module.BrowserManifestTransport(runner).request(data, 20)
        self.assertEqual(reply.status, 403); self.assertEqual(len(calls), 1)
        self.assertFalse(Path(calls[0][-1]).exists())
        for changed in (bound.replace("context=" + PRIVATE, "context=mutated"),
                        bound.replace("context=" + PRIVATE + "&", ""), bound + "&new=unadvertised"):
            bad = fields(channel=channel, binding=encoded({"uri": changed, "channelId": "abema-news", "nonce": NONCE, "browser": BROWSER}))
            with self.assertRaises(module.Refused):
                module.manifest_request_spec(bad)
            with self.assertRaises(module.Refused):
                module.BrowserManifestTransport(lambda *a, **kw: self.fail("must not start")).request(bad, 1)

    def test_browser_cancellation_removes_owned_runtime_and_preserves_termination(self):
        paths = []
        def runner(args, *remaining, **kwargs):
            paths.append(Path(args[-1]))
            raise module.bootstrap.Terminated()
        with self.assertRaises(module.bootstrap.Terminated):
            module.BrowserManifestTransport(runner).request(fields(), 1)
        self.assertFalse(paths[0].exists())

    def test_interrupted_launch_without_verified_ownership_retains_runtime_fail_closed(self):
        def runner(args, *remaining, **kwargs):
            (Path(args[-1]) / "launch-started.json").write_text('{"started":true}')
            raise module.bootstrap.Terminated()
        transport = module.BrowserManifestTransport(runner)
        try:
            with self.assertRaises(module.bootstrap.CleanupUnconfirmed):
                transport.request(fields(), 1)
            self.assertTrue(transport.retained_runtime.is_dir())
        finally:
            if transport.retained_runtime:
                shutil.rmtree(transport.retained_runtime)

    def test_stale_or_unrelated_process_record_never_authorizes_a_signal(self):
        unrelated = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"], start_new_session=True)
        def runner(args, *remaining, **kwargs):
            runtime = Path(args[-1])
            (runtime / "launch-started.json").write_text('{"started":true}')
            (runtime / "browser-owner.json").write_text(json.dumps({"pid": unrelated.pid,
                "processGroup": unrelated.pid, "startTime": "0", "userDataDir": str(runtime / "profile")}))
            raise module.bootstrap.Terminated()
        transport = module.BrowserManifestTransport(runner)
        try:
            with patch.object(module.os, "killpg", side_effect=AssertionError("no unrelated signal")):
                with self.assertRaises(module.bootstrap.CleanupUnconfirmed):
                    transport.request(fields(), 1)
            self.assertIsNone(unrelated.poll())
            self.assertTrue(transport.retained_runtime.is_dir())
        finally:
            unrelated.terminate(); unrelated.wait(timeout=5)
            if transport.retained_runtime:
                shutil.rmtree(transport.retained_runtime)

    @unittest.skipUnless(os.environ.get("TACHIAI_LOCAL_CHROME_TEST") == "1", "explicit local Chrome cancellation check")
    def test_actual_detached_chrome_is_closed_on_node_cancellation_without_unrelated_signals(self):
        unrelated = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"], start_new_session=True)
        browser_group, node_group, signals = [], [], []
        actual_signal = os.killpg
        def record_signal(group, number):
            signals.append(group)
            return actual_signal(group, number)
        def runner(args, *remaining, **kwargs):
            runtime = Path(args[-1])
            helper = SOURCE.with_name("abema-browser-manifest.mjs").as_uri()
            code = "import { launchOwnedBrowser } from " + json.dumps(helper) + "; await launchOwnedBrowser(process.argv[1], {headless:true}); await new Promise(()=>{});"
            process = subprocess.Popen([args[0], "--input-type=module", "-e", code, str(runtime)],
                                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
            node_group.append(process.pid)
            try:
                end = time.monotonic() + 10
                while not (runtime / "browser-owner.json").exists():
                    if process.poll() is not None or time.monotonic() >= end:
                        self.fail("owned local Chrome registration did not complete")
                    time.sleep(0.025)
                owner = json.loads((runtime / "browser-owner.json").read_text())
                browser_group.append(owner["processGroup"])
                self.assertNotEqual(browser_group[0], node_group[0])
                self.assertTrue(module._live_group(browser_group[0]))
                raise module.bootstrap.Terminated()
            finally:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=5)
        transport = module.BrowserManifestTransport(runner)
        try:
            with patch.object(module.os, "killpg", side_effect=record_signal):
                with self.assertRaises(module.bootstrap.Terminated):
                    transport.request(fields(), 20)
            self.assertFalse(module._live_group(browser_group[0]))
            self.assertIsNone(transport.retained_runtime)
            self.assertIsNone(unrelated.poll())
            self.assertNotIn(unrelated.pid, signals)
            self.assertIn(node_group[0], signals)
            self.assertTrue(set(signals) <= {node_group[0], browser_group[0]})
        finally:
            unrelated.terminate(); unrelated.wait(timeout=5)
            if transport.retained_runtime:
                # An ownership uncertainty must remain reviewable, not be hidden.
                self.fail("local Chrome cleanup was not confirmed")

    def test_browser_reply_rejects_stale_nonce_private_extras_and_hidden_status_claims(self):
        bad = [self.packet(nonce="b" * 32), self.packet(uri=PRIVATE), self.packet(body="invalid base64"),
               self.packet(body=base64.b64encode(b"x" * (module.MPD_LIMIT + 1)).decode()),
               self.packet(browserReadable=False), self.packet(status=None), self.packet(accepted=False),
               self.packet(corsPermission="PROVIDER_PRIVATE_VALUE")]
        for packet in bad:
            runner = lambda *args, **kwargs: module.bootstrap.CommandResult(0, encoded(packet))
            with self.assertRaises((module.Refused, ValueError)):
                module.BrowserManifestTransport(runner).request(fields(), 1)
        runner = lambda *args, **kwargs: module.bootstrap.CommandResult(0, encoded(self.packet(cleanupConfirmed=False)))
        with self.assertRaises(module.bootstrap.CleanupUnconfirmed):
            module.BrowserManifestTransport(runner).request(fields(), 1)

    def test_unreadable_browser_response_does_not_invent_status_or_fallback(self):
        packet = self.packet(browserReadable=False, corsPermission="UNESTABLISHED", status=None,
                             mime="NOT_CHECKED", accepted=False, body="")
        runner = lambda *args, **kwargs: module.bootstrap.CommandResult(0, encoded(packet))
        transport = module.BrowserManifestTransport(runner)
        output = io.StringIO()
        attempt = module.BrowserManifestAttempt(output, manifest_transport=transport)
        with patch.object(module.ManifestTransport, "request", side_effect=AssertionError("no native fallback")):
            with self.assertRaises(module.Refused):
                attempt.bound_source(**fields())
        report = output.getvalue()
        self.assertIn('"phase": "BROWSER_MANIFEST"', report)
        self.assertIn('"browserReadable": false', report)
        self.assertIn('"corsPermission": "UNESTABLISHED"', report)
        self.assertNotIn('"status"', report)
        self.assertNotIn(PRIVATE, report)

    def test_browser_and_native_modes_have_separate_exact_opt_in(self):
        fake = SimpleNamespace(run=lambda: 0, cleanup_confirmed=True)
        with patch.object(module.resource, "setrlimit"), patch.object(module.shutil, "which", return_value="available"), \
                patch.object(module, "ManifestAttempt", return_value=fake) as native, \
                patch.object(module, "BrowserManifestAttempt", return_value=fake) as browser:
            self.assertEqual(module.main(["--run-browser-manifest"], io.StringIO()), 0)
            browser.assert_called_once()
            native.assert_not_called()
            self.assertEqual(module.main(["--run-manifest"], io.StringIO()), 0)
            native.assert_called_once()
            self.assertEqual(module.main(["--run-browser-manifest", PRIVATE], io.StringIO()), 2)


if __name__ == "__main__":
    unittest.main()
