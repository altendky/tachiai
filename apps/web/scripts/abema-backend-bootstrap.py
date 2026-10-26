#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Opt-in anonymous backend proof: guest, matching profile, media token, discard.

No existing accounts/cookies, source, manifest, media, license or key access.
Provider code runs unchanged in a disposable isolated container, never on the
host. node:vm is not a sandbox. This is diagnostic source, not a web backend.
"""

import base64
from contextlib import contextmanager
import hashlib
import json
import multiprocessing
import os
from pathlib import Path
import re
import selectors
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

# Measured diagnostic runtime (Node 24.15), independent of application tooling.
# Keep the tested digest until a replacement's helper behavior is verified.
IMAGE = "mcr.microsoft.com/playwright@sha256:9bd26ad900bb5e0f4dee75839e957a89ae89c2b7ab1e76050e559790e946b948"
OWNER_LABEL = "net.fstab.tachiai.abema-bootstrap-owner"
ASSETS = (
    ("application.js", "3850f3e68c3aa3c0.5206.js", 1801458, "d342d230d1f24a90a576597b673990a412a94bb5465cfa9b7567a9dc04e85b81"),
    ("support.js", "a3d61a8507a3f772.framework.js", 326667, "4dc7e3f6d08af278d1b7077d79c3bf5a381e5ff6934189a7d8b0f1219e8190cf"),
    ("legacy.js", "1deb672fea92d35e.8204.bundle.js", 18786, "8e973ee101b83fb13d6a20d5be53b9c9d7642a522547c7069acf4bac00e28a5d"),
    ("utilities.js", "7977ee0696dadeb7.9328.js", 187143, "0187e58193b73f6e261f8ca799c7d75d76b4138813e17b1d57c4d8a6919bc466"),
)
RESPONSE_LIMIT = 65536
ASSET_LIMIT = 2 * 1024 * 1024
IPC_LIMIT = 3 * 1024 * 1024
ATTEMPT_SECONDS = 120
WEB_DEVICE_TYPE = 3
FRESH_UUID = re.compile(r"[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}")
_termination_guard = None


class Refused(Exception):
    """Fixed failure with no provider message, URL, payload or cause output."""


class CleanupUnconfirmed(Refused):
    pass


class Terminated(BaseException):
    pass


class TerminationGuard:
    """Cooperative SIGTERM unwinds owned work; SIGKILL cannot run cleanup."""
    def __init__(self):
        self.received, self.pending, self.cleaning = False, False, 0

    def __enter__(self):
        global _termination_guard
        self.previous = signal.getsignal(signal.SIGTERM)
        self.previous_guard = _termination_guard
        _termination_guard = self
        signal.signal(signal.SIGTERM, self.handle)
        return self

    def handle(self, number, frame):
        self.received = True
        # Repeated cooperative signals must not interrupt the first cleanup.
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        if self.cleaning:
            self.pending = True
        else:
            raise Terminated()

    def __exit__(self, *args):
        global _termination_guard
        signal.signal(signal.SIGTERM, self.previous)
        _termination_guard = self.previous_guard


@contextmanager
def completing_cleanup():
    guard = _termination_guard
    if guard is not None:
        guard.cleaning += 1
    try:
        yield
    finally:
        if guard is not None:
            guard.cleaning -= 1
            if guard.cleaning == 0 and guard.pending and sys.exc_info()[0] is None:
                guard.pending = False
                raise Terminated()


def strict_json(raw, limit=RESPONSE_LIMIT, max_nodes=4096, max_depth=16):
    if not isinstance(raw, bytes) or not 1 <= len(raw) <= limit:
        raise Refused()

    def pairs(items):
        value = {}
        for key, item in items:
            if key in value:
                raise Refused()
            value[key] = item
        return value

    value = json.loads(raw.decode("utf-8", errors="strict"), object_pairs_hook=pairs,
                       parse_constant=lambda _: (_ for _ in ()).throw(Refused()))
    nodes = 0

    def visit(item, depth=0):
        nonlocal nodes
        nodes += 1
        if depth > max_depth or nodes > max_nodes or isinstance(item, list) and len(item) > 1000:
            raise Refused()
        if isinstance(item, dict):
            for key, child in item.items():
                visit(key, depth + 1)
                visit(child, depth + 1)
        elif isinstance(item, list):
            for child in item:
                visit(child, depth + 1)
        elif isinstance(item, str) and any(0xD800 <= ord(character) <= 0xDFFF for character in item):
            raise Refused()

    visit(value)
    return value


def bounded_token(value):
    return isinstance(value, str) and 1 <= len(value) <= 8192 and all(33 <= ord(character) <= 126 for character in value)


def validate_guest_request(body):
    value = strict_json(body, 16384)
    if not isinstance(value, dict) or set(value) != {"device_type", "device_id", "application_key_secret"}:
        raise Refused()
    if type(value["device_type"]) is not int or value["device_type"] != WEB_DEVICE_TYPE or not isinstance(value["device_id"], str) or not FRESH_UUID.fullmatch(value["device_id"]) or not bounded_token(value["application_key_secret"]):
        raise Refused()
    return body


def request_spec(operation, fields):
    """Only closed operations can construct a URL. No arbitrary fetch interface."""
    headers = {"Accept": "application/json", "Accept-Encoding": "identity"}
    body = None
    if operation == "ASSET":
        asset = next((item for item in ASSETS if item[0] == fields.get("name")), None)
        if asset is None or set(fields) != {"name"}:
            raise Refused()
        url = "https://abema.tv/assets/compat/" + asset[1]
        headers["Accept"] = "application/javascript, text/javascript"
    elif operation == "GUEST":
        body = fields.get("body")
        if set(fields) != {"body"} or not isinstance(body, bytes) or not 1 <= len(body) <= 16384:
            raise Refused()
        validate_guest_request(body)
        url = "https://abema.tv/api/auth/login/guest"
        headers["Content-Type"] = "application/json"
    elif operation in {"PROFILE", "MEDIA_TOKEN"}:
        if not bounded_token(fields.get("bearer")):
            raise Refused()
        headers["Authorization"] = "Bearer " + fields["bearer"]
        if operation == "PROFILE":
            user_id = fields.get("user_id")
            if set(fields) != {"bearer", "user_id"} or not isinstance(user_id, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,256}", user_id):
                raise Refused()
            url = "https://api.p-c3-e.abema-tv.com/v1/users/" + user_id
        else:
            if set(fields) != {"bearer"}:
                raise Refused()
            # Fixed public diagnostic context, not the caller's personal settings.
            query = urllib.parse.urlencode({"osName": "pc", "osVersion": "1.0.0", "osLang": "en-US",
                                          "osTimezone": "UTC", "appVersion": "v26.1001.1"})
            url = "https://api.p-c3-e.abema-tv.com/v1/media/token?" + query
    elif operation == "CHANNELS" and fields == {}:
        url = "https://api.abema.io/v1/channels"
    else:
        raise Refused()
    return urllib.request.Request(url, data=body, headers=headers, method="POST" if body else "GET")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


def native_exchange(operation, fields, opener):
    request = request_spec(operation, fields)
    try:
        response = opener.open(request, timeout=10)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        status = response.status
        if type(status) is not int or not 100 <= status <= 599:
            raise Refused()
        if status != 200:
            return status, b""  # Never consume arbitrary refusal bodies/redirect targets.
        maximum = ASSET_LIMIT if operation == "ASSET" else 512 * 1024 if operation == "CHANNELS" else RESPONSE_LIMIT
        length = response.headers.get("Content-Length")
        if length is not None and (not length.isascii() or not length.isdecimal() or int(length) > maximum):
            raise Refused()
        content_type = response.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
        allowed = {"application/javascript", "text/javascript", "application/x-javascript"} if operation == "ASSET" else {"application/json"}
        if content_type not in allowed or response.headers.get("Content-Encoding", "identity").lower() != "identity":
            raise Refused()
        chunks, size = [], 0
        while True:
            chunk = response.read1(min(8192, maximum + 1 - size))
            if not chunk:
                break
            size += len(chunk)
            if size > maximum:
                raise Refused()
            chunks.append(chunk)
        body = b"".join(chunks)
        if length is not None and len(body) != int(length):
            raise Refused()
        return status, body


def native_worker(sender, operation, fields):
    # No downloaded code executes here. The private IPC never enters stdout,
    # stderr, command arguments or files; a separate process gives a hard deadline.
    try:
        with open(os.devnull, "wb") as silent:
            os.dup2(silent.fileno(), 1)
            os.dup2(silent.fileno(), 2)
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        status, body = native_exchange(operation, fields, opener)
        sender.send_bytes(json.dumps({"status": status, "body": base64.b64encode(body).decode("ascii")}).encode("ascii"))
    except BaseException:
        try:
            sender.send_bytes(b'{"refused":true}')
        except BaseException:
            pass
    finally:
        sender.close()


class NativeTransport:
    def __init__(self, context=None):
        self.context = context or multiprocessing.get_context("spawn")

    def request(self, operation, fields, timeout):
        request_spec(operation, fields)  # Refuse before process creation.
        if timeout <= 0:
            raise Refused()
        receiver, sender = self.context.Pipe(duplex=False)
        process = self.context.Process(target=native_worker, args=(sender, operation, fields), daemon=True)
        started = False
        try:
            with completing_cleanup():
                process.start()
                started = True
                sender.close()
            if not receiver.poll(timeout):
                raise Refused()
            reply = strict_json(receiver.recv_bytes(IPC_LIMIT), IPC_LIMIT)
            if not isinstance(reply, dict) or set(reply) != {"status", "body"} or type(reply["status"]) is not int or not 100 <= reply["status"] <= 599 or not isinstance(reply["body"], str):
                raise Refused()
            body = base64.b64decode(reply["body"], validate=True)
            if len(body) > (ASSET_LIMIT if operation == "ASSET" else 512 * 1024 if operation == "CHANNELS" else RESPONSE_LIMIT):
                raise Refused()
            return reply["status"], body
        finally:
            with completing_cleanup():
                sender.close()
                receiver.close()
                if started:
                    process.join(timeout=0.2)
                    if process.is_alive():
                        process.terminate()
                        process.join(timeout=2)
                    if process.is_alive():
                        process.kill()
                        process.join(timeout=2)
                    if process.is_alive():
                        raise CleanupUnconfirmed()
                process.close()


class CommandResult:
    def __init__(self, code, output=b"", stderr=False):
        self.code, self.output, self.stderr = code, output, stderr

    def __repr__(self):
        return "CommandResult(redacted)"


def run_command(args, timeout, limit=32768, input_data=None, display=False):
    if input_data is not None and (not isinstance(input_data, bytes) or len(input_data) > 32768):
        raise Refused()
    process = None
    streams = selectors.DefaultSelector()
    output, stderr_size = bytearray(), 0
    end = time.monotonic() + timeout
    environment = {"PATH": os.defpath, "LANG": "C.UTF-8"}
    if display:
        for name in ("DISPLAY", "XAUTHORITY"):
            if name in os.environ:
                environment[name] = os.environ[name]
    try:
        with completing_cleanup():
            process = subprocess.Popen(args, stdin=subprocess.PIPE if input_data is not None else subprocess.DEVNULL,
                                       stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                       env=environment, start_new_session=display)
            for stream in (process.stdout, process.stderr):
                streams.register(stream, selectors.EVENT_READ)
            if input_data is not None:
                os.set_blocking(process.stdin.fileno(), False)
                streams.register(process.stdin, selectors.EVENT_WRITE)
                pending = memoryview(input_data)
        while streams.get_map():
            remaining = end - time.monotonic()
            if remaining <= 0:
                raise Refused()
            for key, _ in streams.select(min(0.5, remaining)):
                if key.fileobj is process.stdin:
                    if pending:
                        pending = pending[os.write(process.stdin.fileno(), pending):]
                    if not pending:
                        streams.unregister(process.stdin)
                        process.stdin.close()
                    continue
                chunk = os.read(key.fileobj.fileno(), 4096)
                if not chunk:
                    streams.unregister(key.fileobj)
                elif key.fileobj is process.stdout:
                    output.extend(chunk)
                    if len(output) > limit:
                        raise Refused()
                else:
                    stderr_size += len(chunk)
                    if stderr_size > 4096:
                        raise Refused()
        return CommandResult(process.wait(timeout=max(0.1, end - time.monotonic())), bytes(output), stderr_size > 0)
    finally:
        with completing_cleanup():
            streams.close()
            if process is not None:
                if display:
                    # A timed-out browser launcher may have Chrome descendants.
                    # The fresh process group belongs solely to this command.
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                if process.poll() is None:
                    process.kill()
                    process.wait(timeout=5)
                process.stdout.close()
                process.stderr.close()
                if process.stdin is not None:
                    process.stdin.close()


class OwnedContainer:
    def __init__(self, runtime, config, runner=run_command, deadline=None, helper="abema-guest-helper.cjs", private=False):
        self.runtime, self.config, self.runner = runtime, config, runner
        self.deadline = deadline
        if helper not in {"abema-guest-helper.cjs", "abema-source-helper.cjs"}:
            raise Refused()
        self.helper, self.private = helper, private
        self.nonce = uuid.uuid4().hex
        self.name = "tachiai-abema-bootstrap-" + self.nonce

    def command(self, *args, timeout=15, limit=32768, cleanup=False, input_data=None):
        if not cleanup and self.deadline is not None:
            timeout = min(timeout, self.deadline - time.monotonic())
            if timeout <= 0:
                raise Refused()
        command = ["docker", "--config", str(self.config), *args]
        if input_data is not None:
            if not self.private:
                raise Refused()
            return self.runner(command, timeout, limit, input_data=input_data)
        return self.runner(command, timeout, limit)

    def inspect(self, identity, cleanup=False):
        result = self.command("inspect", "--format", "{{json .}}", identity, limit=65536, cleanup=cleanup)
        if result.code != 0 or result.stderr:
            raise Refused()
        actual = strict_json(result.output, 65536)
        if actual.get("Name") != "/" + self.name or actual.get("Config", {}).get("Labels", {}).get(OWNER_LABEL) != self.nonce:
            raise Refused()
        return actual

    def owned_ids(self):
        result = self.command("ps", "--all", "--quiet", "--no-trunc", "--filter", "label=" + OWNER_LABEL + "=" + self.nonce,
                              "--filter", "name=^/" + self.name + "$", limit=1024, cleanup=True)
        if result.code != 0 or result.stderr:
            raise Refused()
        identities = result.output.decode("ascii").split()
        if len(identities) > 1 or any(not re.fullmatch(r"[0-9a-f]{64}", identity) for identity in identities):
            raise Refused()
        return identities

    def cleanup(self):
        with completing_cleanup():
            try:
                for identity in self.owned_ids():
                    self.inspect(identity, cleanup=True)  # Never remove a pre-existing conflicting container.
                    result = self.command("rm", "--force", identity, limit=1024, cleanup=True)
                    if result.code != 0 or result.stderr:
                        raise Refused()
                if self.owned_ids():
                    raise Refused()
            except BaseException:
                raise CleanupUnconfirmed() from None

    @contextmanager
    def opened(self):
        try:
            # Let the bounded create settle before cooperative cancellation checks
            # ownership; killing its client first can race daemon-side creation.
            with completing_cleanup():
                result = self.command("create", "--pull", "never", "--name", self.name, "--label", OWNER_LABEL + "=" + self.nonce,
                                      *( ["--interactive", "--ulimit", "core=0:0"] if self.private else [] ),
                                      "--network", "none", "--read-only", "--cap-drop", "ALL", "--log-driver", "none",
                                      "--security-opt", "no-new-privileges", "--user", "65534:65534", "--pids-limit", "64",
                                      "--memory", "256m", "--cpus", "1", "--mount",
                                      "type=bind,src=" + str(self.runtime) + ",dst=/proof,readonly", "--workdir", "/proof",
                                      "--entrypoint", "node", IMAGE, "/proof/" + self.helper, limit=1024)
            identity = result.output.decode("ascii").strip()
            if result.code != 0 or result.stderr or not re.fullmatch(r"[0-9a-f]{64}", identity):
                raise Refused()
            actual = self.inspect(identity)
            config, host, mounts = actual["Config"], actual["HostConfig"], actual["Mounts"]
            if config["Image"] != IMAGE or config["User"] != "65534:65534" or host["NetworkMode"] != "none" or not host["ReadonlyRootfs"] or host["Privileged"] or "ALL" not in host["CapDrop"] or host["LogConfig"]["Type"] != "none" or not any(value.startswith("no-new-privileges") for value in host["SecurityOpt"]):
                raise Refused()
            if len(mounts) != 1 or mounts[0]["Source"] != str(self.runtime) or mounts[0]["Destination"] != "/proof" or mounts[0]["RW"]:
                raise Refused()
            if host["Memory"] != 256 * 1024 * 1024 or host["NanoCpus"] != 1_000_000_000 or host["PidsLimit"] != 64:
                raise Refused()
            if self.private and (not config.get("OpenStdin") or not any(item.get("Name") == "core" and item.get("Soft") == 0 and item.get("Hard") == 0 for item in host.get("Ulimits", []))):
                raise Refused()
            yield identity
        finally:
            self.cleanup()


class Attempt:
    def __init__(self, output, transport=None, runner=run_command):
        self.output, self.transport, self.runner = output, transport or NativeTransport(), runner
        self.phase, self.cleanup_confirmed = "START", True
        self.deadline = time.monotonic() + ATTEMPT_SECONDS

    def report(self, outcome, **fields):
        self.output.write(json.dumps({"phase": self.phase, "outcome": outcome, **fields}) + "\n")
        self.output.flush()

    def complete(self, media_token):
        self.report("ANONYMOUS_BOOTSTRAP_ACCEPTED", grantsPersisted=False, sourceRequests=False, mediaRequests=False, licenseRequests=False)

    def request(self, operation, fields):
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise Refused()
        status, body = self.transport.request(operation, fields, min(15, remaining))
        self.report("HTTP_STATUS", status=status)
        if status != 200:
            raise Refused()
        return body

    def prepare(self):
        self.phase = "ASSETS"
        temporary = tempfile.TemporaryDirectory(prefix="tachiai-abema-bootstrap-")
        try:
            directory = temporary.name
            scratch = Path(directory)
            runtime, config = scratch / "runtime", scratch / "docker-config"
            runtime.mkdir(mode=0o755)
            config.mkdir(mode=0o700)  # Never consult the caller's Docker credentials/configuration.
            for name, _, size, digest in ASSETS:
                raw = self.request("ASSET", {"name": name})
                if len(raw) != size or hashlib.sha256(raw).hexdigest() != digest:
                    raise Refused()
                destination = runtime / name
                destination.write_bytes(raw)
                destination.chmod(0o444)
            helper = runtime / "abema-guest-helper.cjs"
            helper.write_bytes(Path(__file__).with_name("abema-guest-helper.cjs").read_bytes())
            helper.chmod(0o444)
            self.report("PINNED_ASSETS_VERIFIED", count=4)
            self.phase = "HELPER"
            container = OwnedContainer(runtime, config, self.runner, self.deadline)
            with container.opened() as identity:
                self.report("CONTAINER_POLICY_VERIFIED", networkDisabled=True, readOnly=True, loggingDisabled=True)
                result = container.command("start", "--attach", identity, timeout=20)
                if result.code != 0 or result.stderr:
                    raise Refused()
                wire = strict_json(result.output, 32768)
                if not isinstance(wire, dict) or set(wire) != {"body"} or not isinstance(wire["body"], str):
                    raise Refused()
                body = wire["body"].encode("utf-8")
                validate_guest_request(body)
            # The helper/container and public bundles end before any guest grant exists.
            self.report("REQUEST_PREPARED")
        finally:
            with completing_cleanup():
                try:
                    temporary.cleanup()
                except BaseException:
                    raise CleanupUnconfirmed() from None
        return body

    def run(self):
        try:
            body = self.prepare()
            self.phase = "GUEST"
            guest = strict_json(self.request("GUEST", {"body": body}))
            body = None
            if not isinstance(guest, dict) or not {"user_id", "access_token"} <= set(guest) <= {"user_id", "access_token", "refresh_token"}:
                raise Refused()
            user_id, bearer = guest.get("user_id"), guest.get("access_token")
            if not isinstance(user_id, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,256}", user_id) or not bounded_token(bearer) or "refresh_token" in guest and not bounded_token(guest["refresh_token"]):
                raise Refused()
            guest.clear()
            self.report("FRESH_GUEST_ACCEPTED")
            self.phase = "PROFILE"
            profile = strict_json(self.request("PROFILE", {"user_id": user_id, "bearer": bearer}))
            if not isinstance(profile, dict) or not isinstance(profile.get("profile"), dict) or profile["profile"].get("userId") != user_id:
                raise Refused()
            profile.clear()
            self.report("MATCHING_PROFILE_ACCEPTED", identityMatched=True)
            self.phase = "MEDIA_TOKEN"
            media = strict_json(self.request("MEDIA_TOKEN", {"bearer": bearer}))
            if not isinstance(media, dict) or not bounded_token(media.get("token")):
                raise Refused()
            user_id = bearer = None
            self.complete(media["token"])
            media.clear()
            return 0
        except Terminated:
            self.report("TERMINATED")
            return 1
        except CleanupUnconfirmed:
            self.cleanup_confirmed = False
            self.report("CLEANUP_UNCONFIRMED")
            return 1
        except BaseException:
            self.report("REFUSED")
            return 1
        finally:
            self.phase = "CLEANUP"
            self.report("FINISHED", cleanupConfirmed=self.cleanup_confirmed, grantsPersisted=False)


def main(arguments=None, output=None):
    arguments = sys.argv[1:] if arguments is None else arguments
    output = sys.stdout if output is None else output
    if arguments != ["--run"]:
        output.write('{"phase":"START","outcome":"OPT_IN_REQUIRED"}\n')
        return 2
    if not shutil.which("docker"):
        output.write('{"phase":"START","outcome":"DOCKER_UNAVAILABLE"}\n')
        return 1
    attempt, termination = Attempt(output), TerminationGuard()
    try:
        with termination:
            status = attempt.run()
    except Terminated:
        status = 1
    if termination.received:
        output.write(json.dumps({"phase": "CLEANUP", "outcome": "TERMINATED",
                                 "cleanupConfirmed": attempt.cleanup_confirmed, "grantsPersisted": False}) + "\n")
        return 1
    return status


if __name__ == "__main__":
    sys.exit(main())
