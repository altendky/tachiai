#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Offline bootstrap safety regressions; no provider access or real Docker jobs."""

import hashlib
import importlib.util
import io
import json
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SOURCE = Path(__file__).resolve().parents[2] / "apps/web/scripts/abema-backend-bootstrap.py"
spec = importlib.util.spec_from_file_location("web_abema_bootstrap", SOURCE)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
PRIVATE = "PRIVATE_SYNTHETIC_SENTINEL"
IDENTITY = "fresh_synthetic_user"
CONTAINER = "a" * 64
DEVICE_UUID = "00000000-0000-4000-8000-000000000000"
GUEST_VALUE = {"device_type": 3, "device_id": DEVICE_UUID, "application_key_secret": PRIVATE}
ASSET_BYTES = b"public synthetic fixture"
ASSETS = tuple((name, "fixture-" + name, len(ASSET_BYTES), hashlib.sha256(ASSET_BYTES).hexdigest())
               for name in ("application.js", "support.js", "legacy.js", "utilities.js"))


def encoded(value):
    return json.dumps(value).encode()


class Transport:
    def __init__(self, profile=IDENTITY, guest_status=200, asset=ASSET_BYTES):
        self.profile, self.guest_status, self.asset = profile, guest_status, asset
        self.calls = []

    def request(self, operation, fields, timeout):
        self.calls.append((operation, fields))
        if operation == "ASSET":
            return 200, self.asset
        if operation == "GUEST":
            return self.guest_status, encoded({"user_id": IDENTITY, "access_token": PRIVATE, "refresh_token": PRIVATE})
        if operation == "PROFILE":
            return 200, encoded({"profile": {"userId": self.profile}})
        if operation == "MEDIA_TOKEN":
            return 200, encoded({"token": PRIVATE})
        raise AssertionError("Unexpected operation")


class Docker:
    def __init__(self, conflict=False, cleanup_failure=False, helper_failure=False, unsafe_mount=False, ambiguous_create=False):
        self.calls, self.created = [], False
        self.conflict, self.cleanup_failure = conflict, cleanup_failure
        self.helper_failure, self.unsafe_mount = helper_failure, unsafe_mount
        self.ambiguous_create = ambiguous_create
        self.name = self.nonce = self.runtime = None

    def __call__(self, args, timeout, limit):
        self.calls.append(args)
        operation = args[3]
        if operation == "create":
            self.name = args[args.index("--name") + 1]
            self.nonce = args[args.index("--label") + 1].split("=", 1)[1]
            self.runtime = args[args.index("--mount") + 1].split(",dst=", 1)[0].removeprefix("type=bind,src=")
            if self.conflict:
                return module.CommandResult(1, b"", True)
            self.created = True
            if self.ambiguous_create:
                raise module.Refused()
            return module.CommandResult(0, CONTAINER.encode())
        if operation == "inspect":
            value = {
                "Name": "/" + self.name,
                "Config": {"Labels": {module.OWNER_LABEL: self.nonce}, "Image": module.IMAGE, "User": "65534:65534"},
                "HostConfig": {"NetworkMode": "none", "ReadonlyRootfs": True, "Privileged": False,
                               "CapDrop": ["ALL"], "LogConfig": {"Type": "none"}, "SecurityOpt": ["no-new-privileges"],
                               "Memory": 256 * 1024 * 1024, "NanoCpus": 1_000_000_000, "PidsLimit": 64},
                "Mounts": [{"Source": self.runtime, "Destination": "/proof", "RW": self.unsafe_mount}],
            }
            return module.CommandResult(0, encoded(value))
        if operation == "start":
            return module.CommandResult(1 if self.helper_failure else 0,
                                        encoded({"body": json.dumps(GUEST_VALUE)}), self.helper_failure)
        if operation == "ps":
            if "label=" + module.OWNER_LABEL + "=" + self.nonce not in args:
                raise AssertionError("Cleanup must select exact ownership label")
            return module.CommandResult(0, CONTAINER.encode() if self.created else b"")
        if operation == "rm":
            if self.cleanup_failure:
                return module.CommandResult(1, b"", True)
            self.created = False
            return module.CommandResult(0)
        raise AssertionError("Unexpected Docker operation")


class Reply:
    def __init__(self, data=b"{}", status=200, **headers):
        self.data, self.status, self.reads, self.closed = data, status, 0, False
        self.headers = {"Content-Type": "application/json", **headers}

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
        self.requests.append(request)
        return self.reply


class BootstrapTests(unittest.TestCase):
    def attempt(self, docker=None, transport=None):
        docker, transport = docker or Docker(), transport or Transport()
        output = io.StringIO()
        with patch.object(module, "ASSETS", ASSETS):
            status = module.Attempt(output, transport, docker).run()
        return status, output.getvalue(), docker, transport

    def test_default_and_unknown_arguments_never_start_provider_or_runtime_work(self):
        with patch.object(module, "Attempt", side_effect=AssertionError("must not start")):
            for arguments in ([], ["--help"], [PRIVATE], ["--run", PRIVATE]):
                output = io.StringIO()
                self.assertEqual(module.main(arguments, output), 2)
                self.assertNotIn(PRIVATE, output.getvalue())

    def test_request_bindings_reject_arbitrary_urls_extra_fields_and_private_path_injection(self):
        request = module.request_spec("GUEST", {"body": encoded(GUEST_VALUE)})
        self.assertEqual(request.full_url, "https://abema.tv/api/auth/login/guest")
        self.assertEqual(request.get_method(), "POST")
        self.assertEqual(request.get_header("Content-type"), "application/json")
        self.assertIsNone(request.get_header("Cookie"))
        self.assertIsNone(request.get_header("Proxy-authorization"))
        request = module.request_spec("PROFILE", {"user_id": IDENTITY, "bearer": PRIVATE})
        self.assertEqual(request.full_url, "https://api.p-c3-e.abema-tv.com/v1/users/" + IDENTITY)
        request = module.request_spec("MEDIA_TOKEN", {"bearer": PRIVATE})
        self.assertIn("osTimezone=UTC", request.full_url)
        self.assertNotIn(PRIVATE, request.full_url)
        for operation, fields in [("URL", {"url": "https://private.example"}),
                                  ("PROFILE", {"user_id": "a/b", "bearer": PRIVATE}),
                                  ("PROFILE", {"user_id": IDENTITY, "bearer": PRIVATE + "\n"}),
                                  ("MEDIA_TOKEN", {"bearer": PRIVATE, "url": "https://private.example"}),
                                  ("ASSET", {"name": "../../private"})]:
            with self.assertRaises(module.Refused):
                module.request_spec(operation, fields)

    def test_guest_dto_requires_exact_web_identity_fresh_uuid_shape_and_bounded_secret(self):
        self.assertEqual(module.validate_guest_request(encoded(GUEST_VALUE)), encoded(GUEST_VALUE))
        for value in [{}, {**GUEST_VALUE, "unexpected": True}, {**GUEST_VALUE, "device_type": "3"},
                      {**GUEST_VALUE, "device_type": True}, {**GUEST_VALUE, "device_type": 2},
                      {**GUEST_VALUE, "device_id": "00000000-0000-1000-8000-000000000000"},
                      {**GUEST_VALUE, "device_id": "00000000-0000-4000-0000-000000000000"},
                      {**GUEST_VALUE, "device_id": DEVICE_UUID + "/private"},
                      {**GUEST_VALUE, "application_key_secret": PRIVATE + "\n"},
                      {**GUEST_VALUE, "application_key_secret": "x" * 8193}]:
            with self.assertRaises(module.Refused):
                module.request_spec("GUEST", {"body": encoded(value)})

    def test_json_refuses_duplicates_limits_nonfinite_numbers_and_invalid_unicode(self):
        for value in [b'{"user_id":"a","user_id":"b"}', b'{"n":NaN}', b'"\\ud800"',
                      b"[" * 18 + b"0" + b"]" * 18, b"[" + b"0," * 1000 + b"0]", b"x" * 65537]:
            with self.assertRaises((module.Refused, ValueError)):
                module.strict_json(value)

    def test_native_transport_refusals_do_not_read_error_bodies_or_follow_redirects(self):
        for status in [302, 401, 403, 500]:
            reply = Reply(PRIVATE.encode(), status)
            self.assertEqual(module.native_exchange("MEDIA_TOKEN", {"bearer": PRIVATE}, Opener(reply)), (status, b""))
            self.assertEqual(reply.reads, 0)
            self.assertTrue(reply.closed)
        self.assertIsNone(module.NoRedirect().redirect_request(None, None, 302, PRIVATE, {}, PRIVATE))

    def test_response_types_sizes_lengths_and_encoding_are_bounded_before_acceptance(self):
        for reply in [Reply(**{"Content-Type": "text/html"}), Reply(**{"Content-Length": "65537"}),
                      Reply(**{"Content-Encoding": "gzip"}), Reply(**{"Content-Length": "8"}),
                      Reply(b"x" * 65537), Reply(**{"Content-Length": "-1"})]:
            with self.assertRaises(module.Refused):
                module.native_exchange("MEDIA_TOKEN", {"bearer": PRIVATE}, Opener(reply))
            self.assertTrue(reply.closed)

    def test_success_preserves_identity_and_cleans_runtime_before_guest_without_secret_output(self):
        status, output, docker, transport = self.attempt()
        self.assertEqual(status, 0)
        self.assertNotIn(PRIVATE, output)
        self.assertNotIn(IDENTITY, output)
        self.assertIn("ANONYMOUS_BOOTSTRAP_ACCEPTED", output)
        self.assertFalse(docker.created)
        self.assertFalse(Path(docker.runtime).exists())
        self.assertEqual([operation for operation, _ in transport.calls], ["ASSET"] * 4 + ["GUEST", "PROFILE", "MEDIA_TOKEN"])
        self.assertEqual([fields for operation, fields in transport.calls if operation == "PROFILE"],
                         [{"user_id": IDENTITY, "bearer": PRIVATE}])
        create = next(args for args in docker.calls if args[3] == "create")
        self.assertIn("none", create)
        self.assertIn("--log-driver", create)
        self.assertIn("--config", create)
        self.assertNotIn(PRIVATE, repr(docker.calls))

    def test_mismatched_profile_refuses_before_media_token_and_keeps_private_data_out_of_output(self):
        status, output, docker, transport = self.attempt(transport=Transport(profile="different_user"))
        self.assertEqual(status, 1)
        self.assertNotIn(PRIVATE, output)
        self.assertFalse(any(operation == "MEDIA_TOKEN" for operation, _ in transport.calls))
        self.assertFalse(docker.created)

    def test_bad_asset_pin_prevents_any_provider_helper_or_guest_execution(self):
        status, output, docker, transport = self.attempt(transport=Transport(asset=b"corrupt"))
        self.assertEqual(status, 1)
        self.assertEqual(docker.calls, [])
        self.assertEqual(len(transport.calls), 1)

    def test_diagnostic_pins_are_existing_reviewed_android_public_assets(self):
        policy = SOURCE.parents[2] / "android/app/src/main/kotlin/net/fstab/tachiai/provider/abema/AbemaPublicBundleCache.kt"
        source = policy.read_text()
        for _, filename, size, digest in module.ASSETS:
            self.assertIn("https://abema.tv/assets/compat/" + filename, source)
            self.assertIn(digest, source)
            self.assertIn(str(size), source.replace("_", ""))

    def test_conflicting_preexisting_container_is_not_deleted(self):
        status, output, docker, transport = self.attempt(docker=Docker(conflict=True))
        self.assertEqual(status, 1)
        self.assertFalse(any(args[3] == "rm" for args in docker.calls))
        self.assertFalse(any(operation == "GUEST" for operation, _ in transport.calls))

    def test_helper_failure_and_unsafe_mount_still_remove_only_owned_container(self):
        for docker in [Docker(helper_failure=True), Docker(unsafe_mount=True), Docker(ambiguous_create=True)]:
            status, output, docker, transport = self.attempt(docker=docker)
            self.assertEqual(status, 1)
            self.assertFalse(docker.created)
            self.assertFalse(Path(docker.runtime).exists())
            self.assertFalse(any(operation == "GUEST" for operation, _ in transport.calls))
            self.assertNotIn(PRIVATE, output)

    def test_native_worker_timeout_terminates_and_joins_without_output_or_secret_files(self):
        class Pipe:
            closed = False

            def close(self):
                self.closed = True

            def poll(self, timeout):
                return False

        class Process:
            started = closed = terminated = killed = False

            def start(self):
                self.started = True

            def join(self, timeout):
                pass

            def is_alive(self):
                return self.started and not self.terminated and not self.killed

            def terminate(self):
                self.terminated = True

            def kill(self):
                self.killed = True

            def close(self):
                self.closed = True

        class Context:
            def __init__(self):
                self.receiver, self.sender, self.process = Pipe(), Pipe(), Process()

            def Pipe(self, duplex):
                self.duplex = duplex
                return self.receiver, self.sender

            def Process(self, **fields):
                self.fields = fields
                return self.process

        context = Context()
        with self.assertRaises(module.Refused):
            module.NativeTransport(context).request("MEDIA_TOKEN", {"bearer": PRIVATE}, 0.1)
        self.assertFalse(context.duplex)
        self.assertTrue(context.fields["daemon"])
        self.assertTrue(context.process.terminated)
        self.assertTrue(context.process.closed)
        self.assertTrue(context.sender.closed and context.receiver.closed)

    def test_cleanup_uncertainty_blocks_guest_and_is_reported_explicitly(self):
        status, output, docker, transport = self.attempt(docker=Docker(cleanup_failure=True))
        self.assertEqual(status, 1)
        self.assertIn("CLEANUP_UNCONFIRMED", output)
        self.assertIn('"cleanupConfirmed": false', output)
        self.assertFalse(any(operation == "GUEST" for operation, _ in transport.calls))

    def test_sigterm_during_helper_or_cleanup_unwinds_owned_resources_without_guest(self):
        actual_attempt = module.Attempt
        previous_handler = signal.getsignal(signal.SIGTERM)
        for stage in ["create", "start", "rm"]:
            class SignallingDocker(Docker):
                sent = False

                def __call__(self, args, timeout, limit):
                    if args[3] == stage and not self.sent:
                        self.sent = True
                        signal.raise_signal(signal.SIGTERM)
                    return super().__call__(args, timeout, limit)

            docker, transport, output = SignallingDocker(), Transport(), io.StringIO()
            with patch.object(module, "ASSETS", ASSETS), patch.object(module.shutil, "which", return_value="docker"), \
                    patch.object(module, "Attempt", side_effect=lambda stream: actual_attempt(stream, transport, docker)):
                self.assertEqual(module.main(["--run"], output), 1)
            self.assertFalse(docker.created)
            self.assertFalse(Path(docker.runtime).exists())
            self.assertFalse(any(operation == "GUEST" for operation, _ in transport.calls))
            self.assertIn("TERMINATED", output.getvalue())
            self.assertNotIn(PRIVATE, output.getvalue())
            self.assertEqual(signal.getsignal(signal.SIGTERM), previous_handler)

    def test_subprocess_time_output_and_private_stderr_are_bounded_without_echo(self):
        with self.assertRaises(module.Refused):
            module.run_command([sys.executable, "-c", "import time; time.sleep(30)"], 0.1)
        with self.assertRaises(module.Refused):
            module.run_command([sys.executable, "-c", "print('x'*1000)"], 3, 64)
        result = module.run_command([sys.executable, "-c", "import sys; sys.stderr.write('" + PRIVATE + "'); sys.exit(1)"], 3)
        self.assertTrue(result.stderr)
        self.assertEqual(result.output, b"")
        self.assertNotIn(PRIVATE, repr(result))

    @unittest.skipUnless(shutil.which("node"), "Node needed only for synthetic helper fixtures")
    def test_helper_uses_synthetic_unchanged_factories_without_entry_callbacks(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ["support.js", "utilities.js", "legacy.js"]:
                (root / name).write_text('(self.__LOADABLE_LOADED_CHUNKS__ ||= []).push([[1], {}]);')
            application = '''
              (self.__LOADABLE_LOADED_CHUNKS__ ||= []).push([[1], {
                2833: m => m.exports = {B9: () => 1, b2: async () => "PRIVATE_SYNTHETIC_SENTINEL"},
                5304: m => m.exports = {rM: {toJSON: value => ({device_type: value.deviceType === 'DEVICE_TYPE_WEB' ? 3 : -1,
                  device_id: value.deviceId, application_key_secret: value.applicationKeySecret})}},
                50052: m => { if ('navigator' in globalThis || 'EventTarget' in globalThis ||
                  'URLSearchParams' in globalThis) throw new Error('guest globals must remain absent');
                  m.exports = {bq: {DEVICE_TYPE_WEB: 'DEVICE_TYPE_WEB'}}; },
                99999: () => { throw new Error("unused entry must not run"); }
              }, () => { throw new Error("queued callback must not run"); }]);
            '''
            (root / "application.js").write_text(application)
            helper = SOURCE.with_name("abema-guest-helper.cjs")
            code = "require(process.argv[1]).prepareGuestRequest(process.argv[2]).then(value=>process.stdout.write(value)).catch(()=>{process.exitCode=1})"
            result = subprocess.run(["node", "-e", code, str(helper), str(root)], capture_output=True, timeout=8)
            self.assertEqual(result.returncode, 0)
            self.assertEqual(result.stderr, b"")
            wire = json.loads(result.stdout)
            body = json.loads(wire["body"])
            self.assertEqual(body["application_key_secret"], PRIVATE)
            self.assertEqual(body["device_type"], 3)
            self.assertRegex(body["device_id"], "^" + module.FRESH_UUID.pattern + "$")
            module.validate_guest_request(wire["body"].encode())
            # A changed enum, DTO schema or stale identity must not leave IPC.
            for malformed in [application.replace("DEVICE_TYPE_WEB: 'DEVICE_TYPE_WEB'", "DEVICE_TYPE_WEB: 3"),
                              application.replace("device_type: value.deviceType ===", "unexpected: true, device_type: value.deviceType ==="),
                              application.replace("device_id: value.deviceId", "device_id: " + json.dumps(DEVICE_UUID))]:
                (root / "application.js").write_text(malformed)
                refused = subprocess.run(["node", "-e", code, str(helper), str(root)], capture_output=True, timeout=8)
                self.assertEqual(refused.returncode, 1)
                self.assertEqual(refused.stdout, b"")
                self.assertEqual(refused.stderr, b"")


source_spec = importlib.util.spec_from_file_location("web_abema_source", SOURCE.with_name("abema-backend-source.py"))
source_module = importlib.util.module_from_spec(source_spec)
source_spec.loader.exec_module(source_module)


class SourceProofTests(unittest.TestCase):
    def setUp(self):
        self.uri = "https://linear-abematv.akamaized.net/channel/abema-news/manifest.mpd"
        self.channel = {"id": "abema-news", "viewingOpened": True,
                        "status": {"drm": False}, "playback": {"dash": self.uri}}
        self.nonce = "b" * 32
        self.nav = {"userAgent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/156.0.0.0 Safari/537.36",
                    "platform": "Linux x86_64", "maxTouchPoints": 10, "userAgentData": None}
        self.browser = {"name": "Chrome", "major": "156", "os": "Linux", "engine": "Blink"}

    def binding(self, uri=None, **fields):
        return encoded({"nonce": self.nonce, "channelId": "abema-news", "browser": self.browser,
                        "uri": uri or self.uri + "?t=" + PRIVATE + "&enc=clear&dt=pc_chrome&ut=1", **fields})

    def validate(self, raw):
        return source_module.validate_binding(raw, self.nonce, PRIVATE, self.channel)

    def test_source_opt_in_is_exact_and_does_not_start_any_work(self):
        with patch.object(source_module, "SourceAttempt", side_effect=AssertionError("must not start")):
            for arguments in ([], ["--run"], ["--run-source", PRIVATE], [PRIVATE]):
                output = io.StringIO()
                self.assertEqual(source_module.main(arguments, output), 2)
                self.assertNotIn(PRIVATE, output.getvalue())

    def test_channels_request_is_single_closed_unauthenticated_destination(self):
        request = module.request_spec("CHANNELS", {})
        self.assertEqual(request.full_url, "https://api.abema.io/v1/channels")
        self.assertEqual(request.get_method(), "GET")
        self.assertIsNone(request.get_header("Authorization"))
        self.assertIsNone(request.get_header("Cookie"))
        for fields in ({"url": self.uri}, {"bearer": PRIVATE}, {"channel": "other"}):
            with self.assertRaises(module.Refused):
                module.request_spec("CHANNELS", fields)
        reply = Reply(b"x" * (512 * 1024 + 1))
        with self.assertRaises(module.Refused):
            module.native_exchange("CHANNELS", {}, Opener(reply))

    def test_channel_selection_is_exact_and_conservative_without_fallback(self):
        selected = source_module.select_channel(encoded({"channels": [self.channel]}))
        self.assertEqual(selected, self.channel)
        for channels in ([], [self.channel, self.channel], [{**self.channel, "id": "other"}],
                         [{**self.channel, "viewingOpened": value} for value in [False]],
                         [{**self.channel, "viewingOpened": 1}], [{**self.channel, "status": []}],
                         [{**self.channel, "status": {"drm": True}}],
                         [{**self.channel, "status": {"drm": "false"}}],
                         [{**self.channel, "playback": {"dash": self.uri, "yospace": []}}],
                         [{**self.channel, "playback": {"dash": self.uri, "yospace": {"dash": "unsafe\n"}}}]):
            with self.assertRaises(source_module.Refused):
                source_module.select_channel(encoded({"channels": channels}))
        for raw in (b'{"channels":[],"channels":[]}', b"x" * (512 * 1024 + 1)):
            with self.assertRaises(source_module.Refused):
                source_module.select_channel(raw)

    def test_source_dto_projects_only_verified_advertised_dash_choices(self):
        channel = {**self.channel, "title": PRIVATE, "unknown": PRIVATE,
                   "status": {"drm": False, "unknown": PRIVATE},
                   "playback": {"dash": self.uri, "unknown": PRIVATE,
                                "hls": PRIVATE, "yospace": {"dash": self.uri, "unknown": PRIVATE}}}
        selected = source_module.select_channel(encoded({"channels": [channel]}))
        self.assertNotIn(PRIVATE, json.dumps(selected))
        self.assertEqual(selected["playback"], {"dash": self.uri, "yospace": {"dash": self.uri}})
        missing = dict(self.channel)
        del missing["status"]
        self.assertNotIn("status", source_module.select_channel(encoded({"channels": [missing]})))
        for status in ({}, None):
            selected = source_module.select_channel(encoded({"channels": [{**self.channel, "status": status}]}))
            self.assertEqual(selected["status"], status)
            self.assertFalse(source_module.drm_evidence(selected)["drmPresent"])
            self.assertFalse(source_module.drm_evidence(selected)["drmExplicitFalse"])

    def test_catalogue_refusal_emits_only_closed_reason_and_presence_booleans(self):
        cases = [(b"", "SIZE"), (b"not JSON", "JSON"), (encoded({}), "SCHEMA"),
                 (encoded({"channels": []}), "EXACT_MATCH"),
                 (encoded({"channels": [{**self.channel, "viewingOpened": False}]}), "OPEN"),
                 (encoded({"channels": [{**self.channel, "status": []}]}), "STATUS"),
                 (encoded({"channels": [{**self.channel, "status": {"drm": True}}]}), "DRM"),
                 (encoded({"channels": [{**self.channel, "playback": {"dash": PRIVATE}}]}), "PLAYLIST")]
        for raw, reason in cases:
            with self.assertRaises(source_module.CatalogueRefused) as caught:
                source_module.select_channel(raw)
            self.assertEqual(caught.exception.reason, reason)
            self.assertTrue(all(type(value) is bool for value in caught.exception.evidence.values()))
        with self.assertRaises(source_module.Refused):
            source_module.CatalogueRefused(PRIVATE)
        with self.assertRaises(source_module.Refused):
            source_module.CatalogueRefused("DRM", {"drmPresent": PRIVATE})

    def test_public_catalogue_check_makes_exactly_one_grant_free_get_without_browser_or_helper(self):
        calls, output = [], io.StringIO()
        class CatalogueTransport:
            def request(self, operation, fields, timeout):
                calls.append((operation, fields))
                return 200, encoded({"channels": [{**self_channel, "status": {}}]})
        self_channel = self.channel
        self.assertEqual(source_module.check_catalogue(output, CatalogueTransport()), 0)
        self.assertEqual(calls, [("CHANNELS", {})])
        self.assertIn('"outcome": "CATALOGUE_ACCEPTED"', output.getvalue())
        self.assertIn('"drmPresent": false', output.getvalue())
        self.assertNotIn(PRIVATE, output.getvalue())
        self.assertNotIn(self.uri, output.getvalue())

        class CleanupFailure:
            def request(self, *args):
                raise source_module.bootstrap.CleanupUnconfirmed()
        output = io.StringIO()
        self.assertEqual(source_module.check_catalogue(output, CleanupFailure()), 1)
        self.assertIn('"cleanupConfirmed": false', output.getvalue())

    def test_playlist_refuses_unreviewed_origins_cross_source_and_ambiguous_url_forms(self):
        source_module.usable_playlist(self.uri)
        for uri in (self.uri.replace("https:", "http:"), self.uri.replace("linear-", "nested.linear-"),
                    self.uri.replace("akamaized.net", "akamaized.net.attacker.invalid"),
                    self.uri.replace("abema-news/", "another/"), self.uri + "#", self.uri + "#private",
                    self.uri.replace("/manifest", "/../manifest"), self.uri.replace("/manifest", "/%2e/manifest"),
                    self.uri.replace(".net/", ".net:443/"), self.uri.replace("//linear", "//user@linear"),
                    self.uri.replace("/manifest", "/bad\\manifest"), self.uri.replace("mpd", "m3u8")):
            with self.assertRaises((source_module.Refused, ValueError)):
                source_module.usable_playlist(uri)

    def test_browser_snapshot_requires_actual_desktop_chrome_linux_and_preserves_probe_values(self):
        snapshot = {"version": "156.0.8078.47", "navigator": self.nav}
        self.assertEqual(source_module.validate_browser(encoded(snapshot)), self.nav)
        for nav in ({**self.nav, "platform": "Win32"},
                    {**self.nav, "userAgent": self.nav["userAgent"].replace("Chrome/", "HeadlessChrome/")},
                    {**self.nav, "userAgentData": {"mobile": True, "platform": "Linux"}},
                    {**self.nav, "maxTouchPoints": True}):
            with self.assertRaises(source_module.Refused):
                source_module.validate_browser(encoded({**snapshot, "navigator": nav}))

    def test_binding_is_private_exact_source_nonce_token_browser_and_advertised_choice(self):
        self.assertEqual(self.validate(self.binding()), source_module.ORIGIN_FAMILY)
        for fields in ({"nonce": "c" * 32}, {"channelId": "other"},
                       {"browser": {**self.browser, "os": "Windows"}}, {"unexpected": PRIVATE}):
            with self.assertRaises(source_module.Refused):
                self.validate(self.binding(**fields))
        alternate = self.uri.replace("manifest.mpd", "alternate.mpd")
        with self.assertRaises(source_module.Refused):
            self.validate(self.binding(alternate + "?t=" + PRIVATE + "&enc=clear&dt=pc_chrome&ut=1"))
        self.channel["playback"]["yospace"] = {"dash": alternate}
        self.assertEqual(self.validate(self.binding(alternate + "?t=" + PRIVATE + "&enc=clear&dt=pc_chrome&ut=1")), source_module.ORIGIN_FAMILY)

    def test_binding_query_rejects_duplicates_unknown_encoded_names_and_wrong_session(self):
        good = "?t=" + PRIVATE + "&enc=clear&dt=pc_chrome&ut=1"
        for query in (good + "&t=" + PRIVATE, good + "&dtid=3", good + "&other=1", good + "&sw=1",
                      good.replace("t=", "%74=", 1), good.replace(PRIVATE, "stale"),
                      good.replace("enc=clear", "enc=wv"), good.replace("pc_chrome", "pc_unknown"),
                      good.replace("ut=1", "ut="), good + "&", good + "&sw=%"):
            with self.assertRaises((source_module.Refused, ValueError)):
                self.validate(self.binding(self.uri + query))

    def test_binding_preserves_exact_fresh_advertised_query_pairs_with_full_session_checks(self):
        self.channel["playback"]["yospace"] = {"dash": ""}
        self.assertEqual(source_module.advertised_playlists(self.channel), [self.uri])
        self.assertEqual(self.validate(self.binding()), source_module.ORIGIN_FAMILY)
        self.channel["playback"]["dash"] = self.uri + "?context=" + PRIVATE + "&metadata=a%20b%2Fc"
        uri = self.uri + "?context=" + PRIVATE + "&metadata=a+b%2Fc&t=" + PRIVATE + "&enc=clear&dt=pc_chrome&ut=1"
        self.assertEqual(self.validate(self.binding(uri)), source_module.ORIGIN_FAMILY)
        self.assertEqual(source_module.selected_advertised_playlist(uri, self.channel), self.channel["playback"]["dash"])
        for changed in (uri.replace("context=" + PRIVATE, "context=mutated"),
                        uri.replace("context=" + PRIVATE + "&", ""), uri + "&new=unadvertised",
                        uri.replace("context=", "%63ontext="), uri + "&context=" + PRIVATE,
                        uri.replace("metadata=a+b%2Fc", "metadata=%FF"),
                        uri.replace("&t=" + PRIVATE, "&t=stale"), uri.replace("enc=clear", "enc=wv"),
                        uri.replace("dt=pc_chrome", "dt=pc_unknown"), uri.replace("ut=1", "ut=2"), uri + "&sw=1"):
            with self.assertRaises((source_module.Refused, ValueError, UnicodeError)):
                self.validate(self.binding(changed))

    def test_advertisement_rejects_ambiguous_roots_reserved_collisions_and_malformed_pairs(self):
        session = "&t=" + PRIVATE + "&enc=clear&dt=pc_chrome&ut=1"
        for query in ("context=a&context=a", "%63ontext=a", "context=%", "context=%FF", "context=", "=value",
                      "&context=a", "context=a&", "&".join("field" + str(i) + "=x" for i in range(65)),
                      *(name + "=collision" for name in source_module.SESSION_QUERY_NAMES)):
            self.channel["playback"] = {"dash": self.uri + "?" + query}
            with self.assertRaises((source_module.Refused, ValueError, UnicodeError)):
                self.validate(self.binding(self.uri + "?" + query + session))
        self.channel["playback"] = {"dash": self.uri + "?context=a", "yospace": {"dash": self.uri + "?context=b"}}
        with self.assertRaises(source_module.Refused):
            self.validate(self.binding(self.uri + "?context=a" + session))
        self.channel["playback"] = {"dash": self.uri, "yospace": {"dash": self.uri}}
        with self.assertRaises(source_module.Refused):
            self.validate(self.binding())
        for name in source_module.SESSION_QUERY_NAMES:
            self.channel["playback"] = {"dash": self.uri + "?" + name + "=collision"}
            with self.assertRaises(source_module.Refused):
                self.validate(self.binding())

    def test_private_stdin_is_bounded_piped_and_not_in_arguments_or_stderr(self):
        result = module.run_command([sys.executable, "-c", "import sys; data=sys.stdin.buffer.read(); sys.stdout.write(str(len(data)))"], 3, 64, input_data=PRIVATE.encode())
        self.assertEqual(result.output, str(len(PRIVATE)).encode())
        with self.assertRaises(module.Refused):
            module.run_command([sys.executable, "-c", "pass"], 3, input_data=b"x" * 32769)

    def test_source_container_requires_private_stdin_core_limit_and_owned_cleanup(self):
        class PrivateDocker(Docker):
            def __call__(self, args, timeout, limit, **kwargs):
                result = super().__call__(args, timeout, limit)
                if args[3] == "inspect":
                    value = json.loads(result.output)
                    value["Config"]["OpenStdin"] = True
                    value["HostConfig"]["Ulimits"] = [{"Name": "core", "Soft": 0, "Hard": 0}]
                    result.output = encoded(value)
                return result
        for docker, accepted in ((PrivateDocker(), True), (Docker(), False)):
            with tempfile.TemporaryDirectory() as directory:
                container = module.OwnedContainer(Path(directory), Path(directory), docker, helper="abema-source-helper.cjs", private=True)
                if accepted:
                    with container.opened():
                        pass
                else:
                    with self.assertRaises(module.Refused):
                        with container.opened():
                            self.fail("unsafe private container admitted")
                self.assertFalse(docker.created)
                create = next(args for args in docker.calls if args[3] == "create")
                self.assertIn("core=0:0", create)
                self.assertIn("--interactive", create)
                self.assertNotIn(PRIVATE, repr(docker.calls))

    def test_source_completion_receives_token_only_after_profile_match(self):
        calls = []
        class Attempt(source_module.SourceAttempt):
            def browser_snapshot(self):
                return {}

            def prepare(self):
                return encoded(GUEST_VALUE)

            def source(self, media_token, navigator):
                calls.append(media_token)
        for profile, expected in ((IDENTITY, 0), ("wrong", 1)):
            calls.clear()
            output = io.StringIO()
            self.assertEqual(Attempt(output, Transport(profile=profile)).run(), expected)
            self.assertEqual(calls, [PRIVATE] if expected == 0 else [])
            self.assertNotIn(PRIVATE, output.getvalue())
            self.assertNotIn('"sourceRequests": false', output.getvalue())

    @unittest.skipUnless(shutil.which("node"), "Node needed only for synthetic helper fixtures")
    def test_source_helper_preserves_exact_selector_contract_and_never_inspects_thrown_values(self):
        helper = SOURCE.with_name("abema-source-helper.cjs")
        input_value = {"channel": self.channel, "mediaToken": PRIVATE, "navigator": self.nav, "nonce": self.nonce}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ("support.js", "utilities.js", "legacy.js"):
                (root / name).write_text('(self.__LOADABLE_LOADED_CHUNKS__ ||= []).push([[1], {}]);')
            application = '''
              (self.__LOADABLE_LOADED_CHUNKS__ ||= []).push([[1], {
                60506: m => m.exports = {N: {browser:{name:'Chrome', major:'156'}, os:{name:'Linux'}, engine:{name:'Blink'}}},
                68610: m => m.exports = {Y1:{Streaming:'streaming'}},
                21470: m => m.exports = {tw: input => {
                  if (input.preferDashPlaylist !== true || input.enforcePlainStream !== false ||
                    input.sourceParam.type !== 'streaming' || input.sourceParam.contentId !== 'abema-news' ||
                    input.sourceParam.isDrm !== false || Object.keys(input.sourceParam).length !== 5 ||
                    input.sourceParam.mediaToken !== 'PRIVATE_SYNTHETIC_SENTINEL' ||
                    typeof fetch !== 'undefined' || typeof document !== 'undefined' ||
                    typeof localStorage !== 'undefined' || navigator.platform !== 'Linux x86_64') throw new Error();
                  console.error(input); // Must discard even a private argument.
                  return input.sourceParam.playlists.dash + '?t=' + input.sourceParam.mediaToken + '&enc=clear&dt=pc_chrome&ut=1';
                }},
                99999: () => { throw new Error('unused licensing module must not run'); }
              }, () => { throw new Error('entry callback must not run'); }]);
            '''
            code = "const fs=require('node:fs'); try { process.stdout.write(require(process.argv[1]).selectSource(JSON.parse(fs.readFileSync(0,'utf8')),process.argv[2])); } catch { process.exitCode=1; }"
            for script, accepted in ((application, True),
                                     (application.replace("name:'Chrome'", "name:'Chrome Headless'"), False),
                                     (application.replace("console.error(input);", "throw Object.defineProperties({}, {message:{get(){process.stdout.write('BAD');}}, cause:{get(){process.stdout.write('BAD');}}});"), False)):
                (root / "application.js").write_text(script)
                result = subprocess.run(["node", "-e", code, str(helper), str(root)], input=encoded(input_value), capture_output=True, timeout=8)
                self.assertEqual(result.returncode, 0 if accepted else 1)
                self.assertEqual(result.stderr, b"")
                if accepted:
                    self.assertEqual(self.validate(result.stdout), source_module.ORIGIN_FAMILY)
                else:
                    self.assertEqual(result.stdout, b"")

    def test_source_refusal_and_success_both_cleanup_without_private_retention_or_fallback_requests(self):
        proof = self
        class SourceDocker(Docker):
            def __init__(self, stale=False):
                super().__init__()
                self.stale = stale

            def __call__(self, args, timeout, limit, **kwargs):
                result = super().__call__(args, timeout, limit)
                if args[3] == "inspect":
                    value = json.loads(result.output)
                    value["Config"]["OpenStdin"] = True
                    value["HostConfig"]["Ulimits"] = [{"Name": "core", "Soft": 0, "Hard": 0}]
                    result.output = encoded(value)
                elif args[3] == "start":
                    wire = json.loads(kwargs["input_data"])
                    proof.assertEqual(set(wire), {"channel", "mediaToken", "navigator", "nonce"})
                    proof.assertEqual(wire["mediaToken"], PRIVATE)
                    result.output = proof.binding(nonce="c" * 32 if self.stale else wire["nonce"])
                return result
        for stale in (False, True):
            docker, output, calls = SourceDocker(stale), io.StringIO(), []
            hooks = []
            test = self
            class HookAttempt(source_module.SourceAttempt):
                def bound_source(self, binding, nonce, media_token, channel):
                    test.assertFalse(docker.created)  # Helper closed before any extension I/O.
                    source_module.validate_binding(binding, nonce, media_token, channel)
                    hooks.append(True)
            attempt = HookAttempt(output, runner=docker)
            def request(operation, fields):
                calls.append((operation, fields))
                return encoded({"channels": [self.channel]}) if operation == "CHANNELS" else ASSET_BYTES
            attempt.request = request
            with patch.object(source_module.bootstrap, "ASSETS", ASSETS):
                if stale:
                    with self.assertRaises(source_module.Refused):
                        attempt.source(PRIVATE, self.nav)
                else:
                    attempt.source(PRIVATE, self.nav)
            self.assertEqual([operation for operation, _ in calls], ["CHANNELS"] + ["ASSET"] * 4)
            self.assertFalse(docker.created)
            self.assertFalse(Path(docker.runtime).exists())
            self.assertNotIn(PRIVATE, repr(docker.calls))
            self.assertNotIn(PRIVATE, output.getvalue())
            self.assertNotIn(self.uri, output.getvalue())
            self.assertEqual("SOURCE_BINDING_ACCEPTED" in output.getvalue(), not stale)
            self.assertEqual(len(hooks), 0 if stale else 1)


if __name__ == "__main__":
    unittest.main()
