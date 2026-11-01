"""Bounded private ownership of the development WireGuard route child."""

import importlib.util
from contextlib import nullcontext
import json
import os
from pathlib import Path
import secrets
import selectors
import subprocess
import sys
import time

_spec = importlib.util.spec_from_file_location("web_abema_route_transport", Path(__file__).with_name("abema-route-transport.py"))
transport = sys.modules.get(_spec.name)
if transport is None:
    transport = importlib.util.module_from_spec(_spec)
    sys.modules[_spec.name] = transport
    _spec.loader.exec_module(transport)


class RouteOwnershipRefused(Exception):
    pass


class RouteCleanupUnconfirmed(RouteOwnershipRefused):
    pass


def strict_record(raw):
    def fields(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise RouteOwnershipRefused()
            result[key] = value
        return result

    try:
        value = json.loads(raw.decode("utf8", errors="strict"), object_pairs_hook=fields)
    except (ValueError, UnicodeError):
        raise RouteOwnershipRefused() from None
    if type(value) is not dict:
        raise RouteOwnershipRefused()
    if value == {"phase": "starting"}:
        return value
    if set(value) == {"phase", "port"} and value["phase"] == "ready" and type(value["port"]) is int and 1 <= value["port"] <= 65535:
        return value
    reasons = {"STOP", "EOF", "SIGNAL", "LEASE", "INPUT_TIMEOUT", "INPUT_REJECTED", "INVALID_CONTROL", "OUTPUT_FAILED", "STARTUP_TIMEOUT", "STARTUP_FAILED"}
    if set(value) == {"phase", "reason", "cleanup"} and value["phase"] == "closed" and type(value["reason"]) is str and value["reason"] in reasons and type(value["cleanup"]) is str and value["cleanup"] in {"CONFIRMED", "UNCONFIRMED"}:
        return value
    raise RouteOwnershipRefused()


class RouteOwner:
    def __init__(self, binary, profile, allowed_hosts, lease_seconds=900, completing_cleanup=nullcontext):
        try:
            bounded = type(profile) is str and 1 <= len(profile.encode("utf8")) <= 8192 and type(allowed_hosts) is str and 1 <= len(allowed_hosts.encode("utf8")) <= 4096
        except UnicodeError:
            bounded = False
        if not bounded or type(lease_seconds) is not int or not 1 <= lease_seconds <= 900:
            raise RouteOwnershipRefused()
        path = Path(binary)
        if not path.is_absolute() or not path.is_file() or not os.access(path, os.X_OK):
            raise RouteOwnershipRefused()
        self.binary, self.profile, self.allowed_hosts, self.lease_seconds = str(path), profile, allowed_hosts, lease_seconds
        self.completing_cleanup = completing_cleanup
        self.process = self.streams = self.route = None
        self.pending = bytearray()
        self.closed = None
        self.finished = False
        self.cleanup_confirmed = False
        self.username, self.password, self.realm = (secrets.token_urlsafe(24) for _ in range(3))

    def __repr__(self):
        return "RouteOwner(redacted)"

    def _event(self, deadline):
        while True:
            if b"\n" in self.pending:
                raw, _, remaining = self.pending.partition(b"\n")
                self.pending = bytearray(remaining)
                if len(raw) > 512:
                    raise RouteOwnershipRefused()
                return strict_record(bytes(raw))
            if len(self.pending) > 512 or time.monotonic() >= deadline:
                raise RouteOwnershipRefused()
            for key, _ in self.streams.select(min(0.1, max(0, deadline - time.monotonic()))):
                data = os.read(key.fileobj.fileno(), 1024)
                if not data:
                    self.streams.unregister(key.fileobj)
                elif key.fileobj is self.process.stderr:
                    raise RouteOwnershipRefused()  # Never copy child errors into output.
                else:
                    self.pending.extend(data)
            if not self.streams.get_map() and b"\n" not in self.pending:
                raise RouteOwnershipRefused()

    def _write(self, data, deadline):
        stream = self.process.stdin
        if stream is None or stream.closed:
            raise RouteOwnershipRefused()
        os.set_blocking(stream.fileno(), False)
        with selectors.DefaultSelector() as writable:
            writable.register(stream, selectors.EVENT_WRITE)
            remaining = memoryview(data)
            while remaining:
                if time.monotonic() >= deadline:
                    raise RouteOwnershipRefused()
                if writable.select(min(0.1, max(0, deadline - time.monotonic()))):
                    try:
                        remaining = remaining[os.write(stream.fileno(), remaining):]
                    except BlockingIOError:
                        pass

    def __enter__(self):
        try:
            packet = {"profile": self.profile, "username": self.username, "password": self.password,
                      "realm": self.realm, "allowedHosts": self.allowed_hosts, "leaseSeconds": self.lease_seconds}
            wire = json.dumps(packet, ensure_ascii=True, separators=(",", ":")).encode("ascii") + b"\n"
            if len(wire) > 32769:
                raise RouteOwnershipRefused()
            with self.completing_cleanup():
                self.process = subprocess.Popen([self.binary], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                                env={"PATH": os.defpath, "LANG": "C.UTF-8"}, start_new_session=True)
                self.streams = selectors.DefaultSelector()
                for stream in (self.process.stdout, self.process.stderr):
                    self.streams.register(stream, selectors.EVENT_READ)
            self._write(wire, time.monotonic() + 4)
            self.profile = None
            packet = wire = None
            deadline = time.monotonic() + 26
            event = self._event(deadline)
            if event["phase"] == "closed":
                self.closed = event
                raise RouteOwnershipRefused()
            if event != {"phase": "starting"}:
                raise RouteOwnershipRefused()
            event = self._event(deadline)
            if event["phase"] == "closed":
                self.closed = event
                raise RouteOwnershipRefused()
            if event["phase"] != "ready":
                raise RouteOwnershipRefused()
            self.route = transport.validate_route({"kind": "loopback-connect", "host": "127.0.0.1", "port": event["port"],
                                                   "username": self.username, "password": self.password, "realm": self.realm})
            return self
        except BaseException as error:
            if not self.close():
                raise RouteCleanupUnconfirmed() from None
            if not isinstance(error, Exception):
                raise
            raise RouteOwnershipRefused() from None

    def close(self):
        with self.completing_cleanup():
            return self._close()

    def _close(self):
        if self.finished:
            return self.cleanup_confirmed
        self.finished = True
        if self.process is None:
            self.profile = self.route = None
            self.username = self.password = self.realm = None
            self.cleanup_confirmed = True
            return True
        confirmed = False
        try:
            if self.closed is None:
                if self.process.poll() is None:
                    try:
                        self._write(b"stop\n", time.monotonic() + 0.5)
                    except (OSError, RouteOwnershipRefused):
                        pass
                if self.process.stdin is not None:
                    self.process.stdin.close()
                event = self._event(time.monotonic() + 6)
                if event["phase"] == "closed":
                    self.closed = event
            confirmed = self.closed is not None and self.closed["cleanup"] == "CONFIRMED"
            code = self.process.wait(timeout=1)
            # The terminal record must be the last output, with no stderr.
            extra = bool(self.pending)
            for stream in (self.process.stdout, self.process.stderr):
                os.set_blocking(stream.fileno(), False)
                try:
                    extra |= bool(os.read(stream.fileno(), 1))
                except BlockingIOError:
                    extra = True  # A surviving writer prevents EOF confirmation.
            if extra:
                confirmed = False
            # A rejected configuration can confirm cleanup without succeeding.
            if self.route is not None and code != 0:
                confirmed = False
        except (OSError, subprocess.TimeoutExpired, RouteOwnershipRefused):
            confirmed = False
        finally:
            self.profile = self.route = None
            self.username = self.password = self.realm = None
            if self.process is not None:
                if self.process.poll() is None:
                    self.process.terminate()
                    try:
                        self.process.wait(timeout=6)
                    except subprocess.TimeoutExpired:
                        self.process.kill()
                        self.process.wait(timeout=2)
                    confirmed = False
                for stream in (self.process.stdin, self.process.stdout, self.process.stderr):
                    if stream is not None:
                        stream.close()
            if self.streams is not None:
                self.streams.close()
            if not confirmed and self.process is not None:
                self.closed = None
            self.cleanup_confirmed = confirmed
        return confirmed

    def __exit__(self, *error):
        if not self.close():
            raise RouteCleanupUnconfirmed() from None
