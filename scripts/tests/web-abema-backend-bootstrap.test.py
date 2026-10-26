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
                5304: m => m.exports = {rM: {toJSON: value => ({device_type: value.deviceType,
                  device_id: value.deviceId, application_key_secret: value.applicationKeySecret})}},
                50052: m => m.exports = {bq: {DEVICE_TYPE_WEB: 3}},
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
            for malformed in [application.replace("DEVICE_TYPE_WEB: 3", "DEVICE_TYPE_WEB: 2"),
                              application.replace("device_type: value.deviceType,", "unexpected: true, device_type: value.deviceType,"),
                              application.replace("device_id: value.deviceId", "device_id: " + json.dumps(DEVICE_UUID))]:
                (root / "application.js").write_text(malformed)
                refused = subprocess.run(["node", "-e", code, str(helper), str(root)], capture_output=True, timeout=8)
                self.assertEqual(refused.returncode, 1)
                self.assertEqual(refused.stdout, b"")
                self.assertEqual(refused.stderr, b"")


if __name__ == "__main__":
    unittest.main()
