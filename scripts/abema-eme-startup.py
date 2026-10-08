#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.12"
# dependencies = ["websockets>=15,<16"]
# ///
"""Opt-in, bounded ABEMA playback capability observer; reloads the selected page.

No configs, init data, keys, challenges, license bodies, cookies or page source
are requested. Close the isolated inspection activity after EACH run, including
failures: removing a new-document script does not unwrap the current document.
"""
import argparse
import asyncio
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import re
import secrets
import unittest
import urllib.request
from urllib.parse import urlsplit
from unittest.mock import AsyncMock, MagicMock, patch

import websockets

spec = importlib.util.spec_from_file_location("abema_metadata", Path(__file__).with_name("abema-inspection.py"))
metadata = importlib.util.module_from_spec(spec)
spec.loader.exec_module(metadata)


def source(nonce, duration):
    if not re.fullmatch(r"[a-f0-9]{32}", nonce) or not 1 <= duration <= 60:
        raise ValueError("invalid observer bounds")
    return Path(__file__).with_suffix(".js").read_text().replace("__NONCE__", nonce).replace(
        "__DURATION_MS__", str(duration * 1000))


def console_summary(params, nonce):
    """Only one primitive string and an exact local marker grammar; never stringify objects."""
    args = params.get("args")
    if params.get("type") != "debug" or not isinstance(args, list) or len(args) != 1:
        return None
    arg = args[0]
    if not isinstance(arg, dict) or arg.get("type") != "string" or not isinstance(arg.get("value"), str):
        return None
    value = arg["value"]
    prefix = f"TACHIAI_EME_{nonce}|"
    if len(value) > 160 or not value.startswith(prefix):
        return None
    payload = value[len(prefix):]
    if payload in {"READY", "UNAVAILABLE", "RESTORE_FAILED"}:
        return {"observer": payload}
    match = re.fullmatch(r"([1-9]|1[0-6])\|(WIDEVINE|CLEARKEY|PLAYREADY|FAIRPLAY|OTHER)\|"
                         r"(REQUEST|ACCEPTED|REJECTED|THREW|OBSERVER_FAILED)", payload)
    if match:
        return {"call": int(match[1]), "key_system": match[2], "outcome": match[3]}
    return None


class StartupTrace:
    def __init__(self, nonce):
        self.nonce = nonce
        self.frame = None
        self.context = None
        self.document = 0
        self.armed = False
        self.blocked = False
        self.seen = set()
        self.calls = {}
        self.completed = set()
        self.markers = 0
        self.ready = False

    def event(self, event):
        if event.get("sessionId") or self.blocked:
            return
        method, params = event.get("method"), event.get("params", {})
        if metadata.navigation_blocked(method, params, self.frame):
            self.blocked = True
            self.context = None
            print("STOPPED_NON_PLAYBACK_NAVIGATION", flush=True)
            return
        if method == "Page.frameNavigated" and not params.get("frame", {}).get("parentId"):
            if self.armed:
                if self.document:
                    self.blocked = True
                    self.context = None
                    print("STOPPED_ADDITIONAL_DOCUMENT", flush=True)
                    return
                self.document = 1
            self.frame = params["frame"].get("id")
            self.context = None
        elif method == "Runtime.executionContextsCleared":
            self.context = None
        elif method == "Runtime.executionContextDestroyed" and params.get("executionContextId") == self.context:
            self.context = None
        elif method == "Runtime.executionContextCreated":
            context = params.get("context", {})
            aux = context.get("auxData", {})
            if aux.get("isDefault") is True and aux.get("frameId") == self.frame:
                self.context = context.get("id")
        elif (method == "Runtime.consoleAPICalled" and self.armed and self.context is not None
              and self.document == 1
              and params.get("executionContextId") == self.context):
            summary = console_summary(params, self.nonce)
            if not summary or self.markers >= 35:
                return
            marker = json.dumps(summary, sort_keys=True)
            if marker in self.seen:
                return
            if "call" in summary:
                call = summary["call"]
                if summary["outcome"] == "REQUEST":
                    if call in self.calls:
                        return
                    self.calls[call] = summary["key_system"]
                else:
                    if self.calls.get(call) != summary["key_system"] or call in self.completed:
                        return
                    self.completed.add(call)
            elif summary.get("observer") == "READY":
                self.ready = True
            self.seen.add(marker)
            self.markers += 1
            print(marker, flush=True)


async def observe(port, duration):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(f"http://127.0.0.1:{port}/json/list", timeout=5) as response:
        raw = response.read(1024 * 1024 + 1)
    if len(raw) > 1024 * 1024:
        raise ValueError("target inventory limit")
    targets = [target for target in json.loads(raw) if metadata.playback_target(target)]
    print(json.dumps({"playback_targets": len(targets)}), flush=True)
    if len(targets) != 1:
        return
    endpoint = urlsplit(targets[0]["webSocketDebuggerUrl"])
    if (endpoint.scheme != "ws" or endpoint.hostname not in {"localhost", "127.0.0.1"}
            or endpoint.username or endpoint.password or endpoint.query or endpoint.fragment
            or not re.fullmatch(r"/devtools/page/[A-Za-z0-9_-]+", endpoint.path)):
        raise ValueError("unexpected endpoint")
    trace = StartupTrace(secrets.token_hex(16))
    async with websockets.connect(f"ws://127.0.0.1:{port}{endpoint.path}",
                                  max_size=4 * 1024 * 1024, open_timeout=5) as connection:
        command_id = 0
        registration = None

        async def command(method, params=None, *, cleanup=False):
            nonlocal command_id
            if trace.blocked and not cleanup:
                raise ValueError("navigation stopped")
            command_id += 1
            selected = command_id
            await asyncio.wait_for(connection.send(json.dumps({"id": selected, "method": method,
                                                                 "params": params or {}})), 1)
            deadline = asyncio.get_running_loop().time() + 3
            while asyncio.get_running_loop().time() < deadline:
                event = json.loads(await asyncio.wait_for(connection.recv(), deadline - asyncio.get_running_loop().time()))
                trace.event(event)
                if event.get("id") == selected and not event.get("sessionId"):
                    if "error" in event:
                        code = event["error"].get("code")
                        print(json.dumps({"command_failed": method,
                                          "protocol_error_code": code if type(code) is int else None}), flush=True)
                        raise ValueError("command unavailable")
                    return event.get("result", {})
            raise TimeoutError()

        try:
            await command("Page.enable")
            frame = (await command("Page.getFrameTree"))["frameTree"]["frame"]
            if not metadata.playback_target({"type": "page", "url": frame.get("url", "")}):
                raise ValueError("not playback")
            trace.frame = frame["id"]
            await command("Runtime.enable")
            # Recheck after setup; never install on a newly navigated account page.
            frame = (await command("Page.getFrameTree"))["frameTree"]["frame"]
            if trace.blocked or not metadata.playback_target({"type": "page", "url": frame.get("url", "")}):
                raise ValueError("not playback")
            registration = (await command("Page.addScriptToEvaluateOnNewDocument", {
                "source": source(trace.nonce, duration)}))["identifier"]
            if trace.blocked:
                raise ValueError("navigation stopped")
            trace.armed = True
            reload_params = {"ignoreCache": False}
            if isinstance(frame.get("loaderId"), str) and frame["loaderId"]:
                reload_params["loaderId"] = frame["loaderId"]
            await command("Page.reload", reload_params)
            print("OBSERVING_EME_STARTUP_ONLY", flush=True)
            deadline = asyncio.get_running_loop().time() + duration
            while not trace.blocked and asyncio.get_running_loop().time() < deadline:
                try:
                    trace.event(json.loads(await asyncio.wait_for(connection.recv(), deadline - asyncio.get_running_loop().time())))
                except TimeoutError:
                    break
        finally:
            trace.armed = False
            if registration is not None:
                try:
                    await command("Page.removeScriptToEvaluateOnNewDocument", {"identifier": registration}, cleanup=True)
                    print("STARTUP_REGISTRATION_REMOVED", flush=True)
                except (OSError, TimeoutError, ValueError, websockets.exceptions.WebSocketException):
                    print("STARTUP_CLEANUP_UNCONFIRMED", flush=True)
            print("CLOSE_INSPECTION_ACTIVITY_REQUIRED", flush=True)
        print(json.dumps({"startup_markers": trace.markers, "observer_ready": trace.ready,
                          "candidate_calls": len(trace.calls),
                          "unresolved_calls": len(trace.calls) - len(trace.completed)}), flush=True)


class StartupTests(unittest.TestCase):
    def test_root_context_route_and_call_correlation(self):
        trace = StartupTrace("a" * 32)
        trace.frame, trace.armed, trace.document = "main", True, 1
        def context(identifier, frame="main", default=True):
            trace.event({"method": "Runtime.executionContextCreated", "params": {"context": {
                "id": identifier, "auxData": {"frameId": frame, "isDefault": default}}}})
        def console(payload, identifier=1, **extra):
            trace.event({"method": "Runtime.consoleAPICalled", "params": {"executionContextId": identifier,
                "type": "debug", "args": [{"type": "string", "value": f"TACHIAI_EME_{trace.nonce}|{payload}"}],
                "stackTrace": {"SECRET": "SECRET"}}, **extra})
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            context(2, "child")
            console("READY", 2)
            context(3, default=False)
            console("READY", 3)
            context(1)
            console("1|CLEARKEY|ACCEPTED")  # No preceding request.
            console("1|CLEARKEY|REQUEST", sessionId="foreign")
            console("1|CLEARKEY|REQUEST")
            console("1|WIDEVINE|ACCEPTED")  # Wrong candidate.
            console("1|CLEARKEY|ACCEPTED")
            console("1|CLEARKEY|REJECTED")  # Already completed.
            console("1|CLEARKEY|ACCEPTED")
            trace.event({"method": "Page.navigatedWithinDocument", "params": {
                "frameId": "main", "url": "https://abema.tv/login"}})
            console("2|CLEARKEY|REQUEST")
        self.assertEqual(2, trace.markers)
        self.assertTrue(trace.blocked)
        self.assertNotIn("SECRET", output.getvalue())

    def test_console_marker_ceiling(self):
        trace = StartupTrace("a" * 32)
        trace.frame, trace.context, trace.armed, trace.document = "main", 1, True, 1
        with contextlib.redirect_stdout(io.StringIO()):
            for call in range(1, 17):
                for outcome in ("REQUEST", "ACCEPTED"):
                    trace.event({"method": "Runtime.consoleAPICalled", "params": {
                        "executionContextId": 1, "type": "debug", "args": [{"type": "string",
                        "value": f"TACHIAI_EME_{trace.nonce}|{call}|CLEARKEY|{outcome}"}]}})
        self.assertEqual(32, trace.markers)
        self.assertEqual(16, len(trace.calls))

    def test_second_document_stops_before_reusing_call_ids(self):
        trace = StartupTrace("a" * 32)
        trace.frame, trace.armed = "main", True
        navigation = {"method": "Page.frameNavigated", "params": {"frame": {
            "id": "main", "url": "https://abema.tv/now-on-air/abema-news"}}}
        with contextlib.redirect_stdout(io.StringIO()) as output:
            trace.event(navigation)
            self.assertEqual(1, trace.document)
            self.assertFalse(trace.blocked)
            trace.event(navigation)
        self.assertTrue(trace.blocked)
        self.assertIn("STOPPED_ADDITIONAL_DOCUMENT", output.getvalue())

    def test_closed_console_grammar(self):
        nonce = "a" * 32
        params = {"type": "debug", "args": [{"type": "string", "value": f"TACHIAI_EME_{nonce}|1|CLEARKEY|ACCEPTED"}]}
        self.assertEqual({"call": 1, "key_system": "CLEARKEY", "outcome": "ACCEPTED"}, console_summary(params, nonce))
        for payload in ("SECRET", "1|SECRET|ACCEPTED", "17|CLEARKEY|REQUEST", "1|CLEARKEY|SECRET",
                        "1|CLEARKEY|REJECTED|SECRET", "1|CLEARKEY|ACCEPTED\nSECRET"):
            params["args"][0]["value"] = f"TACHIAI_EME_{nonce}|{payload}"
            self.assertIsNone(console_summary(params, nonce))
        self.assertIsNone(console_summary({"type": "debug", "args": [{"type": "object", "description": "SECRET"}]}, nonce))

    def test_source_bounds(self):
        self.assertNotIn("__NONCE__", source("a" * 32, 45))
        self.assertNotIn("__DURATION_MS__", source("a" * 32, 45))
        for nonce, duration in (("SECRET", 45), ("a" * 32, 61), ("a" * 32, 0)):
            with self.assertRaises(ValueError):
                source(nonce, duration)


class StartupCollectorTests(unittest.IsolatedAsyncioTestCase):
    async def run_trace(self, *, fail=None, account=False, cleanup_failure=False, navigation_during=None):
        target = {"type": "page", "url": "https://abema.tv/now-on-air/abema-news",
                  "webSocketDebuggerUrl": "ws://localhost/devtools/page/test"}
        response, opener, connection, socket = MagicMock(), MagicMock(), AsyncMock(), MagicMock()
        response.read.return_value = json.dumps([target]).encode()
        opener.open.return_value.__enter__.return_value = response
        events = asyncio.Queue()
        calls = []

        async def send(raw):
            call = json.loads(raw)
            calls.append(call)
            method = call["method"]
            if method == navigation_during:
                await events.put({"method": "Page.navigatedWithinDocument", "params": {
                    "frameId": "main", "url": "https://abema.tv/login"}})
            if method == fail or (cleanup_failure and method == "Page.removeScriptToEvaluateOnNewDocument"):
                await events.put({"id": call["id"], "error": {"message": "SECRET"}})
                return
            result = {}
            if method == "Page.getFrameTree":
                result = {"frameTree": {"frame": {"id": "main", "url": target["url"], "loaderId": "loader"}}}
            elif method == "Page.addScriptToEvaluateOnNewDocument":
                result = {"identifier": "SECRET-registration"}
            elif method == "Page.reload":
                if account:
                    await events.put({"method": "Page.navigatedWithinDocument", "params": {
                        "frameId": "main", "url": "https://abema.tv/login"}})
                else:
                    await events.put({"method": "Page.frameNavigated", "params": {"frame": {
                        "id": "main", "url": target["url"]}}})
                    await events.put({"method": "Runtime.executionContextCreated", "params": {"context": {
                        "id": 1, "auxData": {"isDefault": True, "frameId": "main"}}}})
                    for value in ("READY", "1|CLEARKEY|REQUEST", "1|CLEARKEY|ACCEPTED"):
                        await events.put({"method": "Runtime.consoleAPICalled", "params": {
                            "executionContextId": 1, "type": "debug", "args": [{"type": "string",
                            "value": f"TACHIAI_EME_{'a' * 32}|{value}"}], "stackTrace": {"SECRET": "SECRET"}}})
            await events.put({"id": call["id"], "result": result})

        async def recv():
            if events.empty():
                raise TimeoutError()
            return json.dumps(await events.get())

        connection.send.side_effect, connection.recv.side_effect = send, recv
        socket.__aenter__, socket.__aexit__ = AsyncMock(return_value=connection), AsyncMock(return_value=False)
        output, error = io.StringIO(), None
        with patch("urllib.request.build_opener", return_value=opener), \
                patch("websockets.connect", return_value=socket), patch("secrets.token_hex", return_value="a" * 32), \
                contextlib.redirect_stdout(output):
            try:
                await observe(36295, 1)
            except ValueError as caught:
                error = caught
        return output.getvalue(), calls, error

    async def test_setup_reload_and_acknowledged_cleanup(self):
        output, calls, error = await self.run_trace()
        self.assertIsNone(error)
        self.assertNotIn("SECRET", output)
        self.assertIn('"outcome": "ACCEPTED"', output)
        self.assertIn("STARTUP_REGISTRATION_REMOVED", output)
        self.assertIn("CLOSE_INSPECTION_ACTIVITY_REQUIRED", output)
        methods = [call["method"] for call in calls]
        self.assertEqual(["Page.enable", "Page.getFrameTree", "Runtime.enable", "Page.getFrameTree",
                          "Page.addScriptToEvaluateOnNewDocument", "Page.reload",
                          "Page.removeScriptToEvaluateOnNewDocument"], methods)
        self.assertEqual({"ignoreCache": False, "loaderId": "loader"}, calls[5]["params"])
        self.assertNotIn("runImmediately", calls[4]["params"])

    async def test_setup_failure_never_reloads_and_injection_failure_requires_close(self):
        for failure in ("Runtime.enable", "Page.addScriptToEvaluateOnNewDocument"):
            output, calls, error = await self.run_trace(fail=failure)
            self.assertIsNotNone(error)
            self.assertNotIn("SECRET", output)
            self.assertNotIn("Page.reload", [call["method"] for call in calls])
            self.assertIn("CLOSE_INSPECTION_ACTIVITY_REQUIRED", output)

    async def test_cleanup_failure_is_explicit_and_account_navigation_stops(self):
        output, calls, error = await self.run_trace(account=True, cleanup_failure=True)
        self.assertIsNone(error)
        self.assertNotIn("SECRET", output)
        self.assertIn("STOPPED_NON_PLAYBACK_NAVIGATION", output)
        self.assertIn("STARTUP_CLEANUP_UNCONFIRMED", output)
        self.assertIn("CLOSE_INSPECTION_ACTIVITY_REQUIRED", output)
        self.assertEqual(0, json.loads(output.splitlines()[-1])["startup_markers"])
        self.assertEqual("Page.removeScriptToEvaluateOnNewDocument", calls[-1]["method"])

    async def test_navigation_during_setup_prevents_reload_but_allows_cleanup(self):
        for during in ("Page.enable", "Runtime.enable", "Page.addScriptToEvaluateOnNewDocument"):
            output, calls, error = await self.run_trace(navigation_during=during)
            self.assertIsNotNone(error)
            self.assertNotIn("SECRET", output)
            methods = [call["method"] for call in calls]
            self.assertNotIn("Page.reload", methods)
            if during != "Page.addScriptToEvaluateOnNewDocument":
                self.assertNotIn("Page.addScriptToEvaluateOnNewDocument", methods)
            else:
                self.assertEqual("Page.removeScriptToEvaluateOnNewDocument", methods[-1])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int)
    parser.add_argument("--seconds", type=int, default=45)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        unittest.main(argv=[__file__])
    elif not args.port or not 1024 <= args.port <= 65535 or not 1 <= args.seconds <= 60:
        parser.error("provide an isolated local forwarded port and 1–60 seconds")
    else:
        try:
            asyncio.run(observe(args.port, args.seconds))
        except (OSError, TimeoutError, ValueError, KeyError, TypeError, AttributeError,
                websockets.exceptions.WebSocketException):
            print("STARTUP_INSPECTION_UNAVAILABLE_CLOSE_ACTIVITY", flush=True)
            raise SystemExit(1) from None
