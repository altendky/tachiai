#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Owned subprocess/TLS/CONNECT route tests; no provider or saved profile access."""

import base64
import contextlib
import http.server
import importlib.util
import io
import json
import os
from pathlib import Path
import selectors
import shlex
import shutil
import signal
import socket
import socketserver
import ssl
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch
import urllib.error
import urllib.request

SCRIPTS = Path(__file__).resolve().parents[2] / "apps/web/scripts"


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / file)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


owner = load("web_abema_route_owner", "abema-route-owner.py")
route = owner.transport
comparison = load("web_abema_route_comparison", "abema-route-comparison.py")
PRIVATE = "PRIVATE_SYNTHETIC_ROUTE_VALUE"


def descriptor(port):
    return {"kind": "loopback-connect", "host": "127.0.0.1", "port": port,
            "username": "u" * 24, "password": "p" * 24, "realm": "r" * 24}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class OwnedHTTP(http.server.ThreadingHTTPServer):
    daemon_threads = True

    def handle_error(self, *args):
        pass


class Origin(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.server.received.append(dict(self.headers))
        if self.path == "/redirect":
            self.send_response(302)
            self.send_header("Location", "https://unrequested.test/private")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        self.send_response(200)
        self.send_header("Content-Length", "2")
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b"{}")

    def log_message(self, *args):
        pass


class Proxy(socketserver.StreamRequestHandler):
    def handle(self):
        self.connection.settimeout(3)
        first = self.rfile.readline(1024)
        headers = {}
        for _ in range(20):
            line = self.rfile.readline(1024)
            if line == b"\r\n":
                break
            name, _, value = line.partition(b":")
            headers[name.lower()] = value.strip()
        self.server.connects.append((first, headers))
        if first != b"CONNECT example.test:443 HTTP/1.1\r\n" or headers.get(b"proxy-authorization") != self.server.auth:
            self.connection.sendall(b"HTTP/1.1 407 Proxy Authentication Required\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            return
        upstream = socket.create_connection(self.server.destination, timeout=3)
        with upstream:
            self.connection.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
            with selectors.DefaultSelector() as streams:
                streams.register(self.connection, selectors.EVENT_READ, upstream)
                streams.register(upstream, selectors.EVENT_READ, self.connection)
                while True:
                    events = streams.select(3)
                    if not events:
                        return
                    for key, _ in events:
                        data = key.fileobj.recv(16384)
                        if not data:
                            return
                        key.data.sendall(data)


class OwnedProxy(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def handle_error(self, *args):
        pass


@contextlib.contextmanager
def running(server):
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield server
    finally:
        server.shutdown()
        server.server_close()
        thread.join(3)


class RouteTests(unittest.TestCase):
    def test_descriptor_is_closed_copied_immutable_and_redacted(self):
        original = descriptor(12345)
        value = route.validate_route(original)
        original["password"] = PRIVATE
        self.assertEqual(value.password, "p" * 24)
        self.assertNotIn(value.password, repr(value))
        for field, invalid in (("host", "proxy.test"), ("port", True), ("port", 0), ("kind", "direct"), ("password", "short")):
            with self.subTest(field=field), self.assertRaises(route.RouteRefused):
                route.validate_route({**descriptor(12345), field: invalid})
        with self.assertRaises(route.RouteRefused):
            route.validate_route({**descriptor(12345), "bypass": "*"})

    def test_actual_connect_ignores_proxy_environment_and_preserves_tls(self):
        self.assertIsNotNone(shutil.which("openssl"))
        with tempfile.TemporaryDirectory(prefix="route-tls-") as temporary:
            root = Path(temporary)
            cert, key = root / "cert.pem", root / "key.pem"
            result = subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                "-subj", "/CN=example.test", "-addext", "subjectAltName=DNS:example.test", "-keyout", str(key), "-out", str(cert)],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10)
            self.assertEqual(result.returncode, 0)
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            context.load_cert_chain(cert, key)
            origin = OwnedHTTP(("127.0.0.1", 0), Origin)
            origin.received = []
            origin.socket = context.wrap_socket(origin.socket, server_side=True)
            proxy = OwnedProxy(("127.0.0.1", 0), Proxy)
            proxy.destination, proxy.connects = origin.server_address, []
            value = descriptor(proxy.server_address[1])
            proxy.auth = b"Basic " + base64.b64encode((value["username"] + ":" + value["password"]).encode())
            with running(origin), running(proxy):
                environment = {"NO_PROXY": "*", "no_proxy": "*", "HTTPS_PROXY": "http://127.0.0.1:1",
                    "ALL_PROXY": "http://127.0.0.1:1", "SSL_CERT_FILE": str(cert), "SSLKEYLOGFILE": str(root / "forbidden-keylog")}
                with patch.dict(os.environ, environment):
                    opener = route.make_opener(value, NoRedirect())
                    request = urllib.request.Request("https://example.test/", headers={"Authorization": "Bearer " + PRIVATE})
                    with opener.open(request, timeout=3) as response:
                        self.assertEqual(response.read(), b"{}")
                    self.assertEqual(len(proxy.connects), 1)
                    self.assertEqual(origin.received[0]["Host"], "example.test")
                    self.assertEqual(origin.received[0]["Authorization"], "Bearer " + PRIVATE)
                    self.assertNotIn("Proxy-Authorization", origin.received[0])
                    self.assertFalse((root / "forbidden-keylog").exists())
                    with self.assertRaises(urllib.error.HTTPError) as refused:
                        opener.open("https://example.test/redirect", timeout=3)
                    self.assertEqual(refused.exception.code, 302)
                    self.assertTrue(all(item[0] == b"CONNECT example.test:443 HTTP/1.1\r\n" for item in proxy.connects))
                    with self.assertRaises(route.RouteRefused):
                        opener.open("http://127.0.0.1:1/", timeout=1)
                # The same untrusted fixture must fail with ordinary default trust.
                with patch.dict(os.environ, {"SSL_CERT_FILE": str(root / "absent.pem"), "SSL_CERT_DIR": str(root / "absent")}):
                    with self.assertRaises(urllib.error.URLError):
                        route.make_opener(value, NoRedirect()).open("https://example.test/", timeout=3)
                self.assertEqual(len(origin.received), 2)
                # An unavailable explicit proxy cannot fall back to this origin.
                with socket.socket() as unused:
                    unused.bind(("127.0.0.1", 0))
                    unavailable = descriptor(unused.getsockname()[1])
                resolved = []
                actual_resolver = socket.getaddrinfo
                def resolver(host, *args, **kwargs):
                    resolved.append(host)
                    return actual_resolver(host, *args, **kwargs)
                with patch.dict(os.environ, {"NO_PROXY": "*", "SSL_CERT_FILE": str(cert)}), patch.object(socket, "getaddrinfo", resolver), self.assertRaises(urllib.error.URLError):
                    route.make_opener(unavailable, NoRedirect()).open("https://example.test/", timeout=1)
                self.assertNotIn("example.test", resolved)
                self.assertEqual(len(origin.received), 2)

    def child(self, root, mode):
        code = '''import json,os,sys
try: packet=json.loads(sys.stdin.readline())
except ValueError:
 print(json.dumps({"phase":"closed","reason":"INPUT_REJECTED","cleanup":"CONFIRMED"}),flush=True);sys.exit(1)
if len(sys.argv)!=1 or "SHOULD_NOT_INHERIT" in os.environ: sys.exit(3)
mode=MODE
if mode=="failed":
 print(json.dumps({"phase":"closed","reason":"STARTUP_FAILED","cleanup":"CONFIRMED"}),flush=True);sys.exit(1)
print(json.dumps({"phase":"starting"}),flush=True)
print(json.dumps({"phase":"ready","port":12345}),flush=True)
command=sys.stdin.readline()
print(json.dumps({"phase":"closed","reason":"STOP","cleanup":"UNCONFIRMED" if mode=="uncertain" else "CONFIRMED"}),flush=True)
if mode=="stderr": print(packet["profile"],file=sys.stderr,flush=True)
if mode=="trailing": print(packet["profile"],flush=True)
sys.exit(1 if mode=="nonzero" else 0)
'''.replace("MODE", repr(mode))
        path = root / mode
        path.write_text("#!/bin/sh\nexec " + shlex.quote(sys.executable) + " -c " + shlex.quote(code) + "\n")
        path.chmod(0o700)
        return path

    def test_real_child_lifetime_private_input_and_repeated_cleanup(self):
        with tempfile.TemporaryDirectory(prefix="route-owner-") as temporary:
            trial = owner.RouteOwner(self.child(Path(temporary), "normal"), PRIVATE, "example.test")
            with patch.dict(os.environ, {"SHOULD_NOT_INHERIT": PRIVATE}), trial:
                self.assertEqual(trial.route.port, 12345)
                self.assertNotIn(PRIVATE, repr(trial))
                self.assertIsNone(trial.profile)
            self.assertTrue(trial.close())
            self.assertEqual(trial.process.returncode, 0)
            self.assertIsNone(trial.route)

    def test_nonzero_or_extra_child_output_never_upgrades_cleanup(self):
        with tempfile.TemporaryDirectory(prefix="route-owner-") as temporary:
            for mode in ("uncertain", "stderr", "trailing", "nonzero"):
                with self.subTest(mode=mode):
                    trial = owner.RouteOwner(self.child(Path(temporary), mode), PRIVATE, "example.test")
                    with self.assertRaises(owner.RouteCleanupUnconfirmed):
                        with trial:
                            pass
                    self.assertFalse(trial.close())
                    self.assertIsNotNone(trial.process.poll())
            failed = owner.RouteOwner(self.child(Path(temporary), "failed"), PRIVATE, "example.test")
            with self.assertRaises(owner.RouteOwnershipRefused):
                failed.__enter__()
            self.assertTrue(failed.cleanup_confirmed)

    def test_real_termination_after_child_creation_preserves_published_owner(self):
        with tempfile.TemporaryDirectory(prefix="route-owner-") as temporary:
            trial = owner.RouteOwner(self.child(Path(temporary), "normal"), PRIVATE, "example.test",
                                     completing_cleanup=comparison.bootstrap.completing_cleanup)
            actual = subprocess.Popen
            created = []
            def launching(*args, **kwargs):
                process = actual(*args, **kwargs)
                created.append(process)
                os.kill(os.getpid(), signal.SIGTERM)
                return process
            try:
                with comparison.bootstrap.TerminationGuard(), patch.object(owner.subprocess, "Popen", launching):
                    with self.assertRaises(comparison.bootstrap.Terminated):
                        trial.__enter__()
                self.assertIs(trial.process, created[0])
                self.assertIsNotNone(created[0].poll())
                self.assertTrue(trial.cleanup_confirmed)
            finally:
                for process in created:
                    if process.poll() is None:
                        process.kill()
                        process.wait(2)

    def test_deferred_termination_after_success_is_nonzero_with_independent_cleanup(self):
        class Trial:
            route = route.validate_route(descriptor(12345))
            def __init__(self, *args, **kwargs):
                pass
            def __enter__(self):
                return self
            def __exit__(self, *error):
                raise comparison.bootstrap.Terminated()
            def close(self):
                return True
        class Attempt:
            cleanup_confirmed = True
            def run(self):
                return 0
        output = io.StringIO()
        with patch.object(comparison, "read_profile", return_value=PRIVATE), patch.object(comparison.owner, "RouteOwner", Trial), \
             patch.object(comparison, "routed_attempt", return_value=Attempt()), patch.object(comparison.shutil, "which", return_value="/owned/tool"):
            status = comparison.main(["--run-route-replay-metadata", "--peer-idle-confirmed", "--route-runner", "/owned/runner", "--wireguard-file", "/owned/export"], output)
        receipt = json.loads(output.getvalue().splitlines()[-1])
        self.assertEqual(status, 1)
        self.assertEqual(receipt["outcome"], "TERMINATED")
        self.assertFalse(receipt["trialSucceeded"])
        self.assertTrue(receipt["cleanupConfirmed"])

    def test_route_cleanup_cannot_hide_uncertain_browser_or_worker_cleanup(self):
        class Trial:
            route = route.validate_route(descriptor(12345))
            def __init__(self, *args, **kwargs):
                pass
            def __enter__(self):
                return self
            def __exit__(self, *error):
                pass
            def close(self):
                return True
        class Attempt:
            cleanup_confirmed = False
            def run(self):
                return 1
        output = io.StringIO()
        with patch.object(comparison, "read_profile", return_value=PRIVATE), patch.object(comparison.owner, "RouteOwner", Trial), \
             patch.object(comparison, "routed_attempt", return_value=Attempt()), patch.object(comparison.shutil, "which", return_value="/owned/tool"):
            status = comparison.main(["--run-route-replay-metadata", "--peer-idle-confirmed", "--route-runner", "/owned/runner", "--wireguard-file", "/owned/export"], output)
        receipt = json.loads(output.getvalue().splitlines()[-1])
        self.assertEqual(status, 1)
        self.assertEqual(receipt["outcome"], "CLEANUP_UNCONFIRMED")
        self.assertFalse(receipt["cleanupConfirmed"])
        self.assertTrue(receipt["routeCleanupConfirmed"])
        self.assertFalse(receipt["attemptCleanupConfirmed"])

    def test_comparison_requires_opt_in_and_preserves_one_private_route(self):
        output = io.StringIO()
        with patch.object(comparison, "read_profile", side_effect=AssertionError("No profile read")):
            self.assertEqual(comparison.main([], output), 2)
        self.assertNotIn(PRIVATE, output.getvalue())
        calls = []
        def consume(args, timeout, limit, **options):
            calls.append((args, options))
            return "result"
        routed = comparison.RoutedBrowserRunner(descriptor(12345), consume)
        args = ["/usr/bin/node", str(SCRIPTS / "abema-browser-episode-manifest.mjs"), "--owned-runtime", "/tmp/owned"]
        self.assertEqual(routed(args, 10, input_data=b'{"private":"synthetic"}'), "result")
        self.assertEqual(calls[0][0][-1], "--with-route")
        packet = json.loads(calls[0][1]["input_data"])
        self.assertEqual(packet["route"], descriptor(12345))
        self.assertEqual(packet["request"], {"private": "synthetic"})
        with self.assertRaises(comparison.bootstrap.Refused):
            routed(["node", "/other-script.js"], 10)
        attempt = comparison.routed_attempt(io.StringIO(), descriptor(12345), consume)
        self.assertEqual(attempt.transport.proxy_route, routed.route)
        self.assertEqual(attempt.metadata.proxy_route, routed.route)
        self.assertEqual(attempt.manifest_transport.runner.route, routed.route)

    def test_native_workers_use_the_private_descriptor_without_environment_proxy(self):
        selected = route.validate_route(descriptor(12345))
        made = []
        sentinel = object()
        def opener(value, redirect):
            made.append(value)
            return sentinel
        class Sender:
            def send_bytes(self, value):
                self.reply = value
            def close(self):
                self.closed = True
        sender = Sender()
        bootstrap, episode = comparison.bootstrap, comparison.episode
        with patch.object(bootstrap.os, "dup2"), patch.object(bootstrap.route_transport, "make_opener", opener):
            with patch.object(bootstrap, "native_exchange", return_value=(200, b"{}")) as exchange:
                bootstrap.native_worker(sender, "PROFILE", {}, selected)
                self.assertIs(exchange.call_args.args[2], sentinel)
            with patch.object(episode, "exchange", return_value=(200, b"{}")) as exchange:
                episode.episode_worker(sender, "EPISODE", {}, selected)
                self.assertIs(exchange.call_args.args[2], sentinel)
        self.assertEqual(made, [selected, selected])
        self.assertTrue(sender.closed)
        self.assertEqual(json.loads(sender.reply), {"status": 200, "body": "e30="})

    def test_closed_child_protocol_rejects_ambiguous_and_non_scalar_fields(self):
        for raw in (b'{"phase":"starting","phase":"ready"}',
                    b'{"phase":"closed","reason":[],"cleanup":"CONFIRMED"}',
                    b'{"phase":"ready","port":true}',
                    b'{"phase":"closed","reason":"STOP","cleanup":{}}'):
            with self.subTest(raw=raw), self.assertRaises(owner.RouteOwnershipRefused):
                owner.strict_record(raw)


if __name__ == "__main__":
    unittest.main()
