#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Explicit finite provider-pair proof; all private control stays on pipes."""

from concurrent.futures import ThreadPoolExecutor
import importlib.util
import json
import os
from pathlib import Path
import re
import resource
import selectors
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time

spec = importlib.util.spec_from_file_location("provider_pair_backend", Path(__file__).with_name("provider-pair-backend.py"))
backend = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = backend
spec.loader.exec_module(backend)
bootstrap, comparison = backend.bootstrap, backend.comparison
CASES = {"live:virtualjapan", "replay:2080217716"}
ALLOWED_HOSTS = comparison.ALLOWED_HOSTS + ",license.p-c3-e.abema-tv.com"
HOST_SECONDS, ROUTE_SECONDS, CLEANUP_SECONDS = 450, 480, 90
JOB_CLEANUP_SECONDS = 25
ID = re.compile(r"[a-f0-9]{32}")


def wire(value):
    return json.dumps(value, ensure_ascii=True, separators=(",", ":")).encode("ascii") + b"\n"


def write_private(process, value, deadline):
    pending = memoryview(wire(value))
    if len(pending) > 65536 or process.stdin is None or process.stdin.closed:
        raise bootstrap.Refused()
    os.set_blocking(process.stdin.fileno(), False)
    with selectors.DefaultSelector() as ready:
        ready.register(process.stdin, selectors.EVENT_WRITE)
        while pending:
            if time.monotonic() >= deadline:
                raise bootstrap.Refused()
            if ready.select(min(0.1, deadline - time.monotonic())):
                try:
                    pending = pending[os.write(process.stdin.fileno(), pending):]
                except BlockingIOError:
                    pass


class FreshJob:
    """One private child; cancellation never claims its provider cleanup."""
    def __init__(self, route, start_ns, end_ns, root, factory=subprocess.Popen):
        self.route, self.start_ns, self.end_ns, self.root = route, start_ns, end_ns, root
        self.factory, self.cancelled = factory, threading.Event()
        self.cleanup_confirmed = False
        self.reaped = False

    def cancel(self):
        self.cancelled.set()

    def run(self):
        process = None
        selected = selectors.DefaultSelector()
        data, stderr_size = bytearray(), 0
        code, terminated_at, result = None, None, None
        deadline = self.end_ns / 10**9
        try:
            if self.cancelled.is_set() or time.monotonic() >= deadline:
                self.cleanup_confirmed = self.reaped = True
                return None
            self.root.mkdir(mode=0o700)
            node = shutil.which("node")
            if node is None:
                raise bootstrap.Refused()
            # The worker's nested browser snapshot also needs the selected
            # Node toolchain; it need not live in the OS default PATH.
            env = {"PATH": os.path.dirname(os.path.abspath(node)) + os.pathsep + os.defpath,
                   "LANG": "C.UTF-8", "TMPDIR": str(self.root)}
            for key in ("DISPLAY", "XAUTHORITY"):
                if key in os.environ:
                    env[key] = os.environ[key]
            with bootstrap.completing_cleanup():
                process = self.factory([sys.executable, str(Path(__file__).with_name("provider-pair-backend.py")),
                                        "--fresh-owned", str(self.root)], stdin=subprocess.PIPE,
                                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env, start_new_session=True)
                for stream in (process.stdout, process.stderr):
                    selected.register(stream, selectors.EVENT_READ)
            write_private(process, {"route": self.route.private_packet(), "requestStartedNs": str(self.start_ns),
                                    "deadlineNs": str(self.end_ns)}, min(deadline, time.monotonic() + 3))
            process.stdin.close()
            while selected.get_map() or process.poll() is None:
                now = time.monotonic()
                if terminated_at is None and (self.cancelled.is_set() or now >= deadline):
                    process.terminate()  # Cooperative TerminationGuard unwinds its owned helpers.
                    terminated_at = now
                if terminated_at is not None and now - terminated_at >= JOB_CLEANUP_SECONDS:
                    raise bootstrap.CleanupUnconfirmed()
                for key, _ in selected.select(0.05):
                    chunk = os.read(key.fileobj.fileno(), 4096)
                    if not chunk:
                        selected.unregister(key.fileobj)
                    elif key.fileobj is process.stderr:
                        stderr_size += len(chunk)
                        if stderr_size > 4096:
                            raise bootstrap.Refused()
                    else:
                        if len(data) + len(chunk) > 65536:
                            raise bootstrap.Refused()
                        data.extend(chunk)
            code = process.wait(timeout=1)
            self.reaped = True
            value = bootstrap.strict_json(bytes(data), 65536)
            if (not isinstance(value, dict) or set(value) != {"outcome", "packet", "cleanupConfirmed"}
                    or value["outcome"] not in {"READY", "REFUSED"} or type(value["cleanupConfirmed"]) is not bool
                    or stderr_size or value["outcome"] == "READY" and code != 0):
                raise bootstrap.Refused()
            self.cleanup_confirmed = value["cleanupConfirmed"]
            if (value["outcome"] == "READY" and self.cleanup_confirmed and not self.cancelled.is_set()
                    and time.monotonic() < deadline):
                result = value["packet"]
        except BaseException:
            result = None
        finally:
            selected.close()
            if process is not None:
                if process.poll() is None:
                    self.cleanup_confirmed = False
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                    try:
                        process.wait(timeout=2)
                        self.reaped = True
                    except subprocess.TimeoutExpired:
                        self.reaped = False
                for stream in (process.stdin, process.stdout, process.stderr):
                    if stream is not None:
                        stream.close()
            self.route = None
        return result


def publish_public(job, destination):
    destination.mkdir(mode=0o700, exist_ok=True)
    for name, _, size, digest in (*bootstrap.ASSETS, bootstrap.EME_ASSET):
        source = job.root / "public" / name
        if source.resolve() != source or not source.is_file():
            raise bootstrap.Refused()
        raw = source.read_bytes()
        if len(raw) != size or bootstrap.hashlib.sha256(raw).hexdigest() != digest:
            raise bootstrap.Refused()
        target = destination / name
        if target.exists():
            if target.is_symlink() or target.read_bytes() != raw:
                raise bootstrap.Refused()
        else:
            with target.open("xb") as output:
                output.write(raw)
            target.chmod(0o400)


def validate_control(raw):
    value = bootstrap.strict_json(raw, 16384)
    if not isinstance(value, dict) or not isinstance(value.get("event"), str):
        raise bootstrap.Refused()
    if value["event"] in {"fresh", "cancelFresh"}:
        expected = {"event", "id"} if value["event"] == "cancelFresh" else {"event", "id", "requestStartedNs", "deadlineNs"}
        if set(value) != expected or not isinstance(value["id"], str) or not ID.fullmatch(value["id"]):
            raise bootstrap.Refused()
        if value["event"] == "fresh":
            for key in ("requestStartedNs", "deadlineNs"):
                if not isinstance(value[key], str) or not re.fullmatch(r"[1-9][0-9]{0,18}", value[key]):
                    raise bootstrap.Refused()
            start, end = int(value["requestStartedNs"]), int(value["deadlineNs"])
            if not start <= time.monotonic_ns() < end <= start + 120 * 10**9:
                raise bootstrap.Refused()
        return value
    if value["event"] == "activation" and set(value) == {"event", "userCode", "verificationUri"}:
        if (not isinstance(value["userCode"], str) or not re.fullmatch(r"[A-Z0-9-]{1,32}", value["userCode"])
                or value["verificationUri"] != "https://www.twitch.tv/activate"):
            raise bootstrap.Refused()
        return value
    if value == {"event": "ready"}:
        return value
    if value["event"] == "decodeObserved":
        if (set(value) != {"event", "evidence", "minimumAdvanceMs", "sampledJointAdvanceMs", "feeds"}
                or value["evidence"] != "SAMPLED_JOINT_ADVANCEMENT" or type(value["sampledJointAdvanceMs"]) is not int
                or not 5000 <= value["sampledJointAdvanceMs"] <= 120000 or type(value["minimumAdvanceMs"]) is not int
                or value["minimumAdvanceMs"] != 5000 or not isinstance(value["feeds"], dict) or set(value["feeds"]) != {"A", "B"}):
            raise bootstrap.Refused()
        for feed in value["feeds"].values():
            if (not isinstance(feed, dict) or set(feed) != {"decodedFrames", "mediaAdvanceMs", "width", "height"}
                    or any(type(number) is not int for number in feed.values())
                    or not 1 <= feed["decodedFrames"] <= 1000000 or not 5000 <= feed["mediaAdvanceMs"] <= 120000
                    or not 1 <= feed["width"] <= 16384 or not 1 <= feed["height"] <= 16384):
                raise bootstrap.Refused()
            if feed["mediaAdvanceMs"] < value["sampledJointAdvanceMs"]:
                raise bootstrap.Refused()
        return value
    if value["event"] == "finished":
        # This schema intentionally cannot carry any raw page/provider object.
        expected = {"event", "outcome", "decodeObserved", "playbackFailureObserved", "mediaCleanupConfirmed", "cdmCleanupConfirmed",
                    "grantCleanup", "rendererCleanupConfirmed", "browserCleanupConfirmed", "authCleanupConfirmed"}
        if (set(value) != expected or value["outcome"] not in {"STOPPED", "EXPIRED", "REFUSED"}
                or value["grantCleanup"] not in {"revoked", "no-grant-held", "unconfirmed"}
                or any(type(value[key]) is not bool for key in expected - {"event", "outcome", "grantCleanup"})):
            raise bootstrap.Refused()
        return value
    raise bootstrap.Refused()


def trial_succeeded(receipt, cleanup_confirmed):
    return bool(cleanup_confirmed and receipt is not None and receipt["decodeObserved"]
                and not receipt["playbackFailureObserved"] and receipt["outcome"] in {"STOPPED", "EXPIRED"})


class BrowserTrial:
    def __init__(self, route, root, twitch_case, sources, deadline, output, job_factory=FreshJob):
        self.route, self.root, self.case, self.sources = route, root, twitch_case, sources
        self.deadline, self.output, self.job_factory = deadline, output, job_factory
        self.jobs, self.futures, self.attempts = {}, {}, 0
        self.cleanup_confirmed = False
        self.browser_receipt = None

    def report(self, value):
        self.output.write(json.dumps(value, separators=(",", ":")) + "\n")
        self.output.flush()

    def terminal_receipt(self, process, pending):
        """Drain cleanup evidence after failure, without admitting more work."""
        end = min(self.deadline, time.monotonic() + 20)
        rows = 0
        with selectors.DefaultSelector() as drain:
            for stream in (process.stdout, process.stderr):
                drain.register(stream, selectors.EVENT_READ)
            while time.monotonic() < end:
                while b"\n" in pending:
                    row, _, rest = pending.partition(b"\n")
                    pending = bytearray(rest)
                    rows += 1
                    if rows > 32 or len(row) > 16384:
                        raise bootstrap.Refused()
                    value = bootstrap.strict_json(bytes(row), 16384)
                    if isinstance(value, dict) and value.get("event") == "finished":
                        if pending:
                            raise bootstrap.Refused()
                        return validate_control(bytes(row))
                    # Late controls cannot launch a new job during retirement.
                if not drain.get_map():
                    return None
                for key, _ in drain.select(min(0.1, max(0, end - time.monotonic()))):
                    chunk = os.read(key.fileobj.fileno(), 4096)
                    if not chunk:
                        drain.unregister(key.fileobj)
                    elif key.fileobj is process.stdout:
                        pending.extend(chunk)
                        if len(pending) > 32768:
                            raise bootstrap.Refused()
                    # Stderr remains untrusted and is never retained or logged.
        return None

    def run(self):
        process, selected = None, selectors.DefaultSelector()
        executor = ThreadPoolExecutor(max_workers=2)
        pending, stderr_size, user_input = bytearray(), 0, bytearray()
        child_clean, received, control_count = False, None, 0
        try:
            env = {"PATH": os.defpath, "LANG": "C.UTF-8"}
            for key in ("DISPLAY", "XAUTHORITY"):
                if key in os.environ:
                    env[key] = os.environ[key]
            with bootstrap.completing_cleanup():
                process = subprocess.Popen([shutil.which("node"), str(Path(__file__).with_name("provider-pair-browser.mjs")),
                                            "--owned-runtime", str(self.root)], stdin=subprocess.PIPE,
                                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env, start_new_session=True)
                for stream in (process.stdout, process.stderr):
                    selected.register(stream, selectors.EVENT_READ)
            write_private(process, {"schema": 1, "route": self.route.private_packet(), "twitchCase": self.case,
                                   "sources": self.sources, "deadlineNs": str(int((self.deadline - CLEANUP_SECONDS) * 10**9)),
                                   "expiresAtMs": int((time.time() + self.deadline - CLEANUP_SECONDS - time.monotonic()) * 1000)},
                          time.monotonic() + 3)
            try:
                selected.register(sys.stdin.buffer, selectors.EVENT_READ)
            except (ValueError, OSError):
                pass
            stop_sent = False
            while received is None:
                now = time.monotonic()
                if now >= self.deadline - CLEANUP_SECONDS + 15:
                    raise bootstrap.Refused()
                if not stop_sent and now >= self.deadline - CLEANUP_SECONDS:
                    write_private(process, {"type": "stop"}, now + 1)
                    stop_sent = True
                for request_id, future in list(self.futures.items()):
                    if future.done():
                        packet = future.result()
                        job = self.jobs[request_id]
                        if packet is not None:
                            publish_public(job, self.root / "public")
                        write_private(process, {"type": "freshResult", "id": request_id, "packet": packet},
                                      min(self.deadline, time.monotonic() + 2))
                        del self.futures[request_id]
                        packet = None
                if process.poll() is not None and not selected.get_map():
                    raise bootstrap.Refused()
                for key, _ in selected.select(0.05):
                    chunk = os.read(key.fileobj.fileno(), 4096)
                    if not chunk:
                        selected.unregister(key.fileobj)
                        if key.fileobj is process.stdout and received is None:
                            raise bootstrap.Refused()
                        continue
                    if key.fileobj is process.stderr:
                        stderr_size += len(chunk)
                        if stderr_size:
                            raise bootstrap.Refused()
                        continue
                    if key.fileobj not in (process.stdout, process.stderr):
                        user_input.extend(chunk)
                        if len(user_input) > 4096:
                            user_input.clear()
                        while b"\n" in user_input:
                            row, _, rest = user_input.partition(b"\n")
                            user_input = bytearray(rest)
                            if bytes(row) in {b"stop", b"stop\r"} and not stop_sent:
                                write_private(process, {"type": "stop"}, time.monotonic() + 1)
                                stop_sent = True
                        continue
                    pending.extend(chunk)
                    if len(pending) > 32768:
                        raise bootstrap.Refused()
                    while b"\n" in pending:
                        row, _, rest = pending.partition(b"\n")
                        pending = bytearray(rest)
                        control_count += 1
                        if control_count > 32:
                            raise bootstrap.Refused()
                        value = validate_control(bytes(row))
                        if value["event"] == "fresh":
                            request_id = value["id"]
                            if (stop_sent or self.attempts >= 4 or request_id in self.jobs or len(self.futures) >= 2):
                                raise bootstrap.Refused()
                            self.attempts += 1
                            job = self.job_factory(self.route, int(value["requestStartedNs"]),
                                                   min(int(value["deadlineNs"]), int((self.deadline - CLEANUP_SECONDS) * 10**9)),
                                                   self.root / ("j" + str(self.attempts)))
                            self.jobs[request_id] = job
                            self.futures[request_id] = executor.submit(job.run)
                        elif value["event"] == "cancelFresh":
                            if value["id"] not in self.jobs:
                                raise bootstrap.Refused()
                            self.jobs[value["id"]].cancel()
                        elif value["event"] == "finished":
                            if received is not None or pending:
                                raise bootstrap.Refused()
                            received = value
                        else:
                            self.report(value)
            self.browser_receipt = received
            process.stdin.close()
            code = process.wait(timeout=5)
            child_clean = code == 0 and stderr_size == 0 and not pending
            for stream in (process.stdout, process.stderr):
                os.set_blocking(stream.fileno(), False)
                try:
                    child_clean = child_clean and not os.read(stream.fileno(), 1)
                except BlockingIOError:
                    child_clean = False
        except BaseException:
            child_clean = False
        finally:
            with bootstrap.completing_cleanup():
                if process is not None and process.poll() is None:
                    try:
                        write_private(process, {"type": "stop"}, time.monotonic() + 0.5)
                    except BaseException:
                        pass
                for job in self.jobs.values():
                    job.cancel()
                selected.close()
                if process is not None and received is None:
                    try:
                        received = self.terminal_receipt(process, pending)
                        self.browser_receipt = received
                    except BaseException:
                        pass
                executor.shutdown(wait=True, cancel_futures=True)
                if process is not None:
                    if process.poll() is None:
                        process.terminate()
                        try:
                            process.wait(timeout=20)
                        except subprocess.TimeoutExpired:
                            os.killpg(process.pid, signal.SIGKILL)
                            process.wait(timeout=2)
                        child_clean = False
                    for stream in (process.stdin, process.stdout, process.stderr):
                        if stream is not None:
                            stream.close()
                group_closed = True
                try:
                    backend.episode.source.owned_browser.cleanup_owned_browser(self.root)
                except BaseException:
                    group_closed = False
                backend_clean = all(job.cleanup_confirmed and job.reaped for job in self.jobs.values())
                browser_clean = received is not None and all(received[key] for key in (
                    "mediaCleanupConfirmed", "cdmCleanupConfirmed", "rendererCleanupConfirmed", "browserCleanupConfirmed", "authCleanupConfirmed")) and received["grantCleanup"] != "unconfirmed"
                self.cleanup_confirmed = bool(child_clean and group_closed and backend_clean and browser_clean)
                self.report({"phase": "PAIR_RECEIPT", "receipt": received, "backendCleanupConfirmed": backend_clean,
                             "ownedBrowserGroupClosed": group_closed, "cleanupConfirmed": self.cleanup_confirmed,
                             "freshAbemaAttempts": self.attempts, "acousticObservation": False})
        return 0 if trial_succeeded(received, self.cleanup_confirmed) else 1


def arguments_valid(arguments):
    if len(arguments) not in {8, 12} or arguments[:3] != ["--run-provider-pair-proof", "--peer-idle-confirmed", "--route-runner"] or arguments[4] != "--wireguard-file" or arguments[6] != "--twitch-case" or arguments[7] not in CASES:
        return None
    sides = {"A": "abema", "B": "twitch"}
    if len(arguments) == 12:
        if arguments[8] != "--a" or arguments[10] != "--b" or arguments[9] not in sides.values() or arguments[11] not in sides.values():
            return None
        sides = {"A": arguments[9], "B": arguments[11]}
    return arguments[3], arguments[5], arguments[7], sides


def main(arguments=None, output=None):
    args = arguments_valid(sys.argv[1:] if arguments is None else arguments)
    output = sys.stdout if output is None else output
    if args is None:
        output.write('{"phase":"PAIR","outcome":"OPT_IN_REQUIRED"}\n')
        return 2
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    if sys.platform != "linux" or not shutil.which("docker") or not shutil.which("node"):
        output.write('{"phase":"PAIR","outcome":"RUNTIME_UNAVAILABLE"}\n')
        return 1
    binary, profile_path, twitch_case, sources = args
    route = trial = root = None
    status, outcome = 1, "REFUSED"
    termination = bootstrap.TerminationGuard()
    deadline = time.monotonic() + HOST_SECONDS
    try:
        with termination:
            root = Path(tempfile.mkdtemp(prefix="p-"))
            root.chmod(0o700)
            backend.episode.source.owned_browser.validate_runtime_path(root)
            profile = comparison.read_profile(profile_path)
            route = comparison.owner.RouteOwner(binary, profile, ALLOWED_HOSTS, lease_seconds=ROUTE_SECONDS,
                                                 completing_cleanup=bootstrap.completing_cleanup)
            profile = None
            with route:
                trial = BrowserTrial(route.route, root, twitch_case, sources, deadline, output)
                status = trial.run()
                outcome = "FINISHED"
    except bootstrap.Terminated:
        outcome = "TERMINATED"
    except BaseException:
        pass
    finally:
        with bootstrap.completing_cleanup():
            route_clean = route is None or route.close()
            attempt_clean = trial is None or trial.cleanup_confirmed
            confirmed = route_clean and attempt_clean
            if confirmed and root is not None:
                try:
                    shutil.rmtree(root)
                except BaseException:
                    confirmed = False
            if not confirmed:
                status, outcome = 1, "CLEANUP_UNCONFIRMED"
            if termination.received:
                status, outcome = 1, "TERMINATED"
            output.write(json.dumps({"phase": "PAIR_CLEANUP", "outcome": outcome, "cleanupConfirmed": confirmed,
                                     "routeCleanupConfirmed": route_clean, "attemptCleanupConfirmed": attempt_clean,
                                     "profilePersisted": False, "hostRoutesChanged": False, "trialSucceeded": status == 0}) + "\n")
    return status


if __name__ == "__main__":
    sys.exit(main())
