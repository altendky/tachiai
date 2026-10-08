#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.12"
# dependencies = ["websockets>=15,<16"]
# ///
"""Observe closed ABEMA playback metadata through an explicitly forwarded local CDP port.

Never requests bodies, cookies, DOM, screenshots, script source, or credentials.
CDP itself exposes sensitive data; only this collector's output is restricted.
"""
import argparse
import asyncio
import contextlib
import io
import json
import re
import unittest
import urllib.request
from unittest.mock import AsyncMock, MagicMock, patch
from urllib.parse import urlsplit

import websockets

PLAYBACK_PATHS = {"/now-on-air/abema-news", "/video/episode/394-72_s10_p8529"}
TARGET_FILTER = [{"type": "worker"}, {"type": "iframe"}, {"exclude": True}]
HANDSHAKE_KINDS = ("RESOURCE_ORIGIN", "LICENSE_ORIGIN", "API_ORIGIN", "PROVIDER_OTHER_ORIGIN")
ROLE_HOSTS = {
    "streaming-api-cf.p-c2-x.abema-tv.com": "RESOURCE_CONFIG",
    "license.abema.io": "LICENSE_LEGACY",
    "license.p-c3-e.abema-tv.com": "LICENSE_PROXY_CONFIG",
    "zeus-api.p-c3-e.abema-tv.com": "ZEUS_CONFIG",
    "zeus-api.d-c3-e.abema-tv.com": "ZEUS_CONFIG",
    "api.abema.io": "API_LEGACY",
    "api.p-c3-e.abema-tv.com": "API_CONFIG",
    "media.p-c3-e.abema-tv.com": "MEDIA_CONFIG",
}
ROLE_KINDS = tuple(dict.fromkeys(ROLE_HOSTS.values()))
ROLE_ROUTES = ("RESOURCE_ROUTE", "MEDIA_TOKEN_ROUTE_CANDIDATE", "DASH_LICENSE_ROUTE", "HLS_LICENSE_ROUTE", "OTHER")
MEDIA_TOKEN_QUERY_NAMES = {"osName", "osVersion", "osLang", "osTimezone", "appVersion"}


def handshake_role(value, method=None):
    """Fixed public configuration roles, not proof of active business operations."""
    if not isinstance(value, str) or len(value) > 8192:
        return None
    try:
        url = urlsplit(value)
        if url.scheme != "https" or url.netloc != url.hostname or url.fragment:
            return None
        role = ROLE_HOSTS.get(url.hostname)
        if role is None:
            return None
        route = "OTHER"
        if role in {"LICENSE_LEGACY", "LICENSE_PROXY_CONFIG"}:
            route = {"/abematv-dash": "DASH_LICENSE_ROUTE", "/abematv-hls": "HLS_LICENSE_ROUTE"}.get(url.path, "OTHER")
        if role == "RESOURCE_CONFIG" and method == "GET" and not url.query and \
                re.fullmatch(r"/v1/playbackResources/[A-Za-z0-9._~:-]{1,256}", url.path) and \
                url.path.rsplit("/", 1)[1] not in {".", ".."}:
            route = "RESOURCE_ROUTE"
        if role == "API_CONFIG" and method == "GET" and url.path == "/v1/media/token":
            # Examine raw fixed name positions only, never query values.
            pairs = url.query.split("&")
            names = [pair.partition("=")[0] for pair in pairs]
            if len(names) == 5 and set(names) == MEDIA_TOKEN_QUERY_NAMES and all("=" in pair for pair in pairs):
                route = "MEDIA_TOKEN_ROUTE_CANDIDATE"
        segments = len([part for part in url.path.split("/") if part])
        return {"host_role": role, "route_kind": route, "path_segments": min(segments, 9),
                "has_query": bool(url.query)} # 9 means nine or more, never raw path.
    except (ValueError, TypeError, AttributeError):
        return None


def role_request_summary(request):
    role = handshake_role(request.get("url", ""), request.get("method"))
    if role is None:
        return None
    method = request.get("method")
    return {**role, "role_event": "REQUEST",
            "method": method if method in ("GET", "POST", "OPTIONS", "HEAD") else "OTHER",
            "has_post_data": request.get("hasPostData") if type(request.get("hasPostData")) is bool else None}


def role_response_summary(response, method=None):
    role = handshake_role(response.get("url", ""), method)
    if role is None:
        return None
    status = response.get("status")
    return {**role, "role_event": "RESPONSE",
            "status": int(status) if type(status) in (int, float) and 100 <= status <= 599 and status == int(status) else None,
            "mime_class": mime_class(response.get("mimeType")),
            "disk_cache": response.get("fromDiskCache") if type(response.get("fromDiskCache")) is bool else None,
            "service_worker": response.get("fromServiceWorker") if type(response.get("fromServiceWorker")) is bool else None,
            "prefetch_cache": response.get("fromPrefetchCache") if type(response.get("fromPrefetchCache")) is bool else None}


class RoleMetadata:
    """Per-role capacity keeps API chatter from consuming license/resource slots."""
    def __init__(self, enabled):
        self.enabled = enabled
        self.counts = {role: {"requests": 0, "responses": 0, "failures": 0} for role in ROLE_KINDS}
        self.route_counts = {role: {route: 0 for route in ROLE_ROUTES} for role in ROLE_KINDS}
        self.markers = {role: 0 for role in ROLE_KINDS}
        self.other_markers = {role: 0 for role in ROLE_KINDS}
        self.requests = {}
        self.tracking_limited = set()

    def emit(self, summary, target):
        role = summary["host_role"]
        # Reserve most of each role's output for identified route families.
        if summary["route_kind"] == "OTHER":
            if self.other_markers[role] >= 4:
                if self.other_markers[role] == 4:
                    print(json.dumps({"role_other_output_limit": role}), flush=True)
                    self.other_markers[role] += 1
                return
            self.other_markers[role] += 1
        if self.markers[role] < 16:
            self.markers[role] += 1
            print(json.dumps({**summary, "target": target, "role_sequence": self.markers[role]}), flush=True)
        elif self.markers[role] == 16:
            self.markers[role] += 1
            print(json.dumps({"role_output_limit": role}), flush=True)

    def request(self, key, request, redirect, target):
        if not self.enabled:
            return
        if isinstance(redirect, dict):
            self.response(key, redirect, target, redirect=True)
        self.requests.pop(key, None)
        summary = role_request_summary(request)
        if summary is None:
            return
        role = summary["host_role"]
        self.counts[role]["requests"] += 1
        self.route_counts[role][summary["route_kind"]] += 1
        role_entries = [entry for entry in self.requests.values() if entry["host_role"] == role]
        if len(role_entries) < 16 and (summary["route_kind"] != "OTHER" or
                                      sum(entry["route_kind"] == "OTHER" for entry in role_entries) < 4):
            self.requests[key] = {**summary, "served_from_cache": None}
        else:
            self.tracking_limited.add(role)
        self.emit(summary, target)

    def response(self, key, response, target, *, redirect=False):
        if not self.enabled:
            return
        correlated = self.requests.get(key)
        summary = role_response_summary(response, correlated["method"] if correlated else None)
        if summary is None:
            return
        self.counts[summary["host_role"]]["responses"] += 1
        # Closed role/route agreement is necessary, not proof of exact URL identity.
        if correlated and all(correlated[field] == summary[field] for field in ("host_role", "route_kind")):
            summary.update(method=correlated["method"], served_from_cache=correlated["served_from_cache"])
        else:
            summary.update(method="UNKNOWN", served_from_cache=None)
        if redirect:
            summary["redirect"] = True
        self.emit(summary, target)

    def cached(self, key, target):
        if not self.enabled or key not in self.requests:
            return
        request = self.requests[key]
        if request["served_from_cache"] is not True:
            request["served_from_cache"] = True
            self.emit({"role_event": "SERVED_FROM_CACHE", "host_role": request["host_role"],
                       "route_kind": request["route_kind"]}, target)

    def finished(self, key, target, *, failed=False):
        if not self.enabled:
            return
        request = self.requests.pop(key, None)
        if failed and request is not None:
            self.counts[request["host_role"]]["failures"] += 1
            self.emit({"role_event": "FAILURE", "host_role": request["host_role"],
                       "route_kind": request["route_kind"]}, target)

    def detached(self, removed):
        self.requests = {key: value for key, value in self.requests.items() if key[0] not in removed}

    def final(self):
        if self.enabled:
            print(json.dumps({"role_counts": self.counts, "role_route_requests": self.route_counts,
                              "role_markers": {role: min(count, 16) for role, count in self.markers.items()},
                              "role_tracking_limited": sorted(self.tracking_limited)}), flush=True)


def handshake_kind(value):
    """Origin categories only; never expose paths, queries or arbitrary hostnames."""
    try:
        url = urlsplit(value)
        if url.scheme != "https" or not url.hostname or url.netloc != url.hostname:
            return None
        if url.hostname == "streaming-api-cf.p-c2-x.abema-tv.com":
            return "RESOURCE_ORIGIN"
        if url.hostname == "license.abema.io":
            return "LICENSE_ORIGIN"
        if url.hostname == "api.abema.io":
            return "API_ORIGIN"
        if url.hostname.endswith((".abema.io", ".abema-tv.com")):
            return "PROVIDER_OTHER_ORIGIN"
    except (ValueError, TypeError, AttributeError):
        return None
    return None


def handshake_request_summary(request):
    kind = handshake_kind(request.get("url", ""))
    if not kind:
        return None
    method = request.get("method")
    return {"handshake_event": "REQUEST", "kind": kind,
            "method": method if method in ("GET", "POST", "OPTIONS", "HEAD") else "OTHER",
            "has_post_data": request.get("hasPostData") if type(request.get("hasPostData")) is bool else None}


def handshake_response_summary(response):
    kind = handshake_kind(response.get("url", ""))
    if not kind:
        return None
    status = response.get("status")
    return {"handshake_event": "RESPONSE", "kind": kind,
            "status": int(status) if type(status) in (int, float) and 100 <= status <= 599 and status == int(status) else None,
            "mime_class": mime_class(response.get("mimeType"))}


def related_target_allowed(target):
    """Called only for auto-attachments emitted by an already accepted target."""
    if target.get("type") == "iframe":
        return playback_target({**target, "type": "page"})
    if target.get("type") != "worker":
        return False
    try:
        value = target.get("url", "")
        if value.startswith("blob:https://abema.tv/"):
            return re.fullmatch(r"blob:https://abema\.tv/[A-Za-z0-9-]+", value) is not None
        url = urlsplit(value)
        return (url.scheme == "https" and url.netloc == "abema.tv" and not url.query and not url.fragment
                and url.path.startswith("/assets/") and url.path.endswith(".js"))
    except (ValueError, TypeError, AttributeError):
        return False


def mime_class(value):
    if not isinstance(value, str):
        return "OTHER"
    return {"application/json": "JSON", "application/octet-stream": "OCTET_STREAM",
            "application/dash+xml": "DASH", "application/vnd.apple.mpegurl": "HLS",
            "application/x-mpegURL": "HLS", "audio/mpegurl": "HLS", "video/mp4": "MP4",
            "audio/mp4": "MP4"}.get(value, "OTHER")


def scheme_class(value):
    if not isinstance(value, str):
        return "OTHER"
    return {"Unencrypted": "UNENCRYPTED", "unencrypted": "UNENCRYPTED", "CENC": "CENC", "CBCS": "CBCS",
            "cenc": "CENC", "cbcs": "CBCS"}.get(value, "OTHER")


def media_property_summary(prop):
    name, value = prop.get("name"), prop.get("value")
    if not isinstance(name, str):
        return None
    booleans = {"kIsCdmAttached": "CDM_ATTACHED", "kIsVideoDecryptingDemuxerStream": "VIDEO_DECRYPTING_DEMUXER",
                "kIsAudioDecryptingDemuxerStream": "AUDIO_DECRYPTING_DEMUXER",
                "kIsPlatformVideoDecoder": "PLATFORM_VIDEO_DECODER", "kIsStreaming": "STREAMING"}
    if name in booleans:
        return {"property": booleans[name], "value": {"true": True, "false": False}.get(value) if isinstance(value, str) else None}
    if name not in {"kSetCdm", "kAudioTracks", "kVideoTracks"}:
        return None
    if not isinstance(value, str) or len(value) > 16384:
        return None
    try:
        parsed = json.loads(value)
    except ValueError:
        return None
    if name == "kSetCdm" and isinstance(parsed, dict):
        system = parsed.get("key_system")
        return {"property": "CDM_CONFIG", "key_system": {
            "com.widevine.alpha": "WIDEVINE", "org.w3.clearkey": "CLEARKEY"}.get(system, "OTHER") if isinstance(system, str) else "OTHER"}
    if isinstance(parsed, list):
        schemes = sorted({scheme_class(track.get("encryption scheme"))
                          for track in parsed[:8] if isinstance(track, dict)})
        return {"property": "AUDIO_TRACKS" if name == "kAudioTracks" else "VIDEO_TRACKS", "schemes": schemes}
    return None


def media_event_summary(event):
    value = event.get("value")
    if not isinstance(value, str) or len(value) > 16384:
        return None
    try:
        parsed = json.loads(value)
        name = parsed.get("event")
    except (ValueError, AttributeError):
        return None
    if not isinstance(name, str):
        return None
    kind = {"kPlay": "PLAY", "kPause": "PAUSE", "kEnded": "ENDED", "kLoad": "LOAD",
            "kPipelineStateChange": "PIPELINE_STATE_CHANGE", "kHlsSegmentFetch": "HLS_SEGMENT_FETCH"}.get(name)
    if not kind:
        return None
    result = {"media_event": kind}
    if name == "kLoad":
        try:
            url = urlsplit(parsed.get("url", ""))
            result["source_class"] = ("BLOB" if url.scheme == "blob" else
                                      "HLS_URL" if url.path.endswith(".m3u8") else
                                      "DASH_URL" if url.path.endswith(".mpd") else "OTHER")
        except (ValueError, TypeError):
            result["source_class"] = "OTHER"
    return result


def playback_target(target):
    try:
        url = urlsplit(target.get("url", ""))
        return (
            target.get("type") == "page"
            and url.scheme == "https"
            and url.netloc == "abema.tv"
            and url.path in PLAYBACK_PATHS
            and not url.query
            and not url.fragment
        )
    except (ValueError, TypeError):
        return False


def license_kind(url):
    try:
        parsed = urlsplit(url)
        if parsed.scheme != "https" or parsed.netloc != "license.abema.io":
            return None
        return {"/abematv-dash": "DASH_LICENSE", "/abematv-hls": "HLS_LICENSE"}.get(parsed.path, "OTHER_LICENSE_ROUTE")
    except (ValueError, TypeError):
        return None


def response_summary(response):
    kind = license_kind(response.get("url", ""))
    if not kind:
        return None
    status = response.get("status")
    # Header values never leave this classifier. Exact equality matches the observed static filter condition.
    headers = response.get("headers", {})
    exact_json = any(name.lower() == "content-type" and value == "application/json" for name, value in headers.items())
    return {"event": "response", "kind": kind,
            "status": int(status) if type(status) in (int, float) and 100 <= status <= 599 and status == int(status) else None,
            "mime_class": mime_class(response.get("mimeType")), "content_type_exact_json": exact_json}


def navigation_blocked(method, params, main_frame):
    if method == "Page.frameNavigated" and not params.get("frame", {}).get("parentId"):
        return not playback_target({"type": "page", "url": params["frame"].get("url", "")})
    if method == "Page.navigatedWithinDocument" and (main_frame is None or params.get("frameId") == main_frame):
        return not playback_target({"type": "page", "url": params.get("url", "")})
    return False


async def observe(port, duration, *, handshake=False, handshake_roles=False):
    # Explicit direct loopback transport; do not forward these requests through environment proxies.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(f"http://127.0.0.1:{port}/json/list", timeout=5) as response:
        raw = response.read(1024 * 1024 + 1)
    if len(raw) > 1024 * 1024:
        raise ValueError("target inventory exceeded limit")
    targets = [target for target in json.loads(raw) if playback_target(target)]
    print(json.dumps({"playback_targets": len(targets)}), flush=True)
    if len(targets) != 1:
        return
    parsed = urlsplit(targets[0]["webSocketDebuggerUrl"])
    if (parsed.scheme != "ws" or parsed.hostname not in {"127.0.0.1", "localhost"}
            or parsed.username or parsed.password or parsed.query or parsed.fragment
            or not re.fullmatch(r"/devtools/page/[A-Za-z0-9_-]+", parsed.path)):
        raise ValueError("unexpected debugger endpoint")
    socket = f"ws://127.0.0.1:{port}{parsed.path}"
    async with websockets.connect(socket, max_size=4 * 1024 * 1024, open_timeout=5) as connection:
        sessions = {"": {"type": "PAGE", "target": targets[0].get("id")}}
        pending = {}
        command_id = 0

        async def send(method, params=None, session=""):
            nonlocal command_id
            command_id += 1
            command = {"id": command_id, "method": method}
            if params is not None:
                command["params"] = params
            if session:
                command["sessionId"] = session
            pending[(session, command_id)] = method
            await asyncio.wait_for(connection.send(json.dumps(command)), 1)

        async def configure(session, page):
            # Never request post data, bodies, cookies, DOM, runtime evaluation or source.
            await send("Network.enable", {"maxPostDataSize": 0}, session)
            if page:
                await send("Page.enable", session=session)
                await send("Page.getFrameTree", session=session)
            await send("Media.enable", session=session)
            await send("Target.setAutoAttach", {"autoAttach": True, "waitForDebuggerOnStart": False,
                                               "flatten": True, "filter": TARGET_FILTER}, session)

        await configure("", True)
        print("OBSERVING_METADATA_ONLY", flush=True)
        deadline = asyncio.get_running_loop().time() + duration
        requests = {}
        counts = {"requests": 0, "responses": 0, "failures": 0}
        network_counts = {"requests": 0, "responses": 0, "failures": 0}
        target_counts = {kind: {"requests": 0, "responses": 0, "failures": 0}
                         for kind in ("PAGE", "WORKER", "FRAME")}
        response_types = {kind: {} for kind in target_counts}
        players = {}
        media_seen = set()
        main_frames = {}
        setup_acks = set()
        attached_total = 0
        attachment_attempts = 0
        refusal_reported = False
        handshake_counts = {kind: {"requests": 0, "responses": 0, "failures": 0} for kind in HANDSHAKE_KINDS}
        handshake_requests = {}
        handshake_markers = 0
        handshake_tracking_limited = False
        roles = RoleMetadata(handshake_roles)

        def emit_handshake(summary, target_kind):
            nonlocal handshake_markers
            if handshake_markers < 64:
                handshake_markers += 1
                print(json.dumps({**summary, "target": target_kind, "sequence": handshake_markers}), flush=True)
            elif handshake_markers == 64:
                handshake_markers += 1
                print("HANDSHAKE_OUTPUT_LIMIT", flush=True)
        try:
            while asyncio.get_running_loop().time() < deadline:
                try:
                    event = json.loads(await asyncio.wait_for(connection.recv(), deadline - asyncio.get_running_loop().time()))
                except TimeoutError:
                    break
                session = event.get("sessionId", "")
                if session not in sessions:
                    continue
                target_kind = sessions[session]["type"]
                acknowledged = pending.pop((session, event.get("id")), None)
                if acknowledged:
                    if "error" in event and acknowledged == "Target.detachFromTarget":
                        print("STOPPED_TARGET_DETACH_FAILED", flush=True)
                        break
                    if "error" in event and acknowledged in {"Network.enable", "Page.enable", "Page.getFrameTree"}:
                        print("STOPPED_CDP_SETUP_FAILED", flush=True)
                        break
                    if acknowledged in {"Media.enable", "Target.setAutoAttach"}:
                        print(json.dumps({"capability": "MEDIA" if acknowledged == "Media.enable" else "RELATED_TARGETS",
                                          "target": target_kind, "supported": "error" not in event}), flush=True)
                    if not session and event.get("id") in {1, 2, 3} and "result" in event:
                        setup_acks.add(event["id"])
                        if setup_acks == {1, 2, 3}:
                            print("CDP_SETUP_CONFIRMED", flush=True)
                    if acknowledged == "Page.getFrameTree":
                        frame = event["result"]["frameTree"]["frame"]
                        if not playback_target({"type": "page", "url": frame.get("url", "")}):
                            print("STOPPED_NON_PLAYBACK_NAVIGATION", flush=True)
                            break
                        main_frames[session] = frame["id"]
                method, params = event.get("method"), event.get("params", {})
                if method == "Target.attachedToTarget":
                    attachment_attempts += 1
                    info, child = params["targetInfo"], params["sessionId"]
                    known_targets = {entry["target"] for entry in sessions.values()}
                    if attachment_attempts > 16:
                        print("STOPPED_ATTACHMENT_LIMIT", flush=True)
                        break
                    if (attached_total >= 8 or not related_target_allowed(info)
                            or (info.get("parentId") is not None and info["parentId"] not in known_targets)):
                        await send("Target.detachFromTarget", {"sessionId": child}, session)
                        if not refusal_reported:
                            print("RELATED_TARGET_REFUSED", flush=True)
                            refusal_reported = True
                        continue
                    child_kind = "WORKER" if info["type"] == "worker" else "FRAME"
                    sessions[child] = {"type": child_kind, "target": info["targetId"], "parent": session}
                    attached_total += 1
                    print(json.dumps({"attached": child_kind}), flush=True)
                    await configure(child, child_kind == "FRAME")
                    continue
                if method == "Target.detachedFromTarget":
                    removed = {params.get("sessionId")}
                    for _ in range(8):
                        removed.update(key for key, entry in sessions.items() if key and entry.get("parent") in removed)
                    for key in removed:
                        if key and key in sessions:
                            sessions.pop(key)
                            main_frames.pop(key, None)
                    # An ancestor's detached session tree is no longer eligible for collection.
                    pending = {key: value for key, value in pending.items() if key[0] not in removed}
                    handshake_requests = {key: value for key, value in handshake_requests.items() if key[0] not in removed}
                    roles.detached(removed)
                    continue
                if method == "Target.targetInfoChanged":
                    info = params["targetInfo"]
                    if any(entry["target"] == info.get("targetId") for key, entry in sessions.items() if key):
                        if not related_target_allowed(info):
                            print("STOPPED_RELATED_TARGET_NAVIGATION", flush=True)
                            break
                if method == "Page.frameNavigated" and not params.get("frame", {}).get("parentId"):
                    main_frames[session] = params["frame"].get("id")
                if (navigation_blocked(method, params, main_frames.get(session))
                        or (session and method == "Page.frameNavigated"
                            and params.get("frame", {}).get("id") == main_frames.get(session)
                            and not playback_target({"type": "page", "url": params["frame"].get("url", "")}))):
                    print("STOPPED_NON_PLAYBACK_NAVIGATION", flush=True)
                    break
                request_key = (session, params.get("requestId"))
                if method == "Network.requestWillBeSent":
                    roles.request(request_key, params.get("request", {}), params.get("redirectResponse"), target_kind)
                    if handshake:
                        redirect = params.get("redirectResponse")
                        redirect_summary = handshake_response_summary(redirect) if isinstance(redirect, dict) else None
                        if redirect_summary:
                            handshake_counts[redirect_summary["kind"]]["responses"] += 1
                            emit_handshake({**redirect_summary, "redirect": True}, target_kind)
                        handshake_requests.pop(request_key, None) # A redirect can reuse its request ID.
                        summary = handshake_request_summary(params.get("request", {}))
                        if summary:
                            handshake_counts[summary["kind"]]["requests"] += 1
                            if len(handshake_requests) < 64:
                                handshake_requests[request_key] = summary["kind"]
                            else:
                                handshake_tracking_limited = True
                            emit_handshake(summary, target_kind)
                    network_counts["requests"] += 1
                    target_counts[target_kind]["requests"] += 1
                    kind = license_kind(params.get("request", {}).get("url", ""))
                    if kind:
                        counts["requests"] += 1
                        requests[request_key] = kind
                        print(json.dumps({"event": "request", "kind": kind, "target": target_kind}), flush=True)
                elif method == "Network.responseReceived":
                    roles.response(request_key, params.get("response", {}), target_kind)
                    if handshake:
                        summary = handshake_response_summary(params.get("response", {}))
                        if summary:
                            handshake_counts[summary["kind"]]["responses"] += 1
                            emit_handshake(summary, target_kind)
                    network_counts["responses"] += 1
                    target_counts[target_kind]["responses"] += 1
                    response = params.get("response", {})
                    category = mime_class(response.get("mimeType"))
                    response_types[target_kind][category] = response_types[target_kind].get(category, 0) + 1
                    summary = response_summary(response)
                    if summary:
                        counts["responses"] += 1
                        print(json.dumps({**summary, "target": target_kind}), flush=True)
                elif method == "Network.loadingFailed":
                    roles.finished(request_key, target_kind, failed=True)
                    if handshake and request_key in handshake_requests:
                        kind = handshake_requests.pop(request_key)
                        handshake_counts[kind]["failures"] += 1
                        emit_handshake({"handshake_event": "FAILURE", "kind": kind}, target_kind)
                    network_counts["failures"] += 1
                    target_counts[target_kind]["failures"] += 1
                    if request_key in requests:
                        counts["failures"] += 1
                        print(json.dumps({"event": "failure", "kind": requests[request_key], "target": target_kind}), flush=True)
                elif method == "Network.loadingFinished":
                    roles.finished(request_key, target_kind)
                    handshake_requests.pop(request_key, None)
                elif method == "Network.requestServedFromCache":
                    roles.cached(request_key, target_kind)
                elif method in {"Media.playerPropertiesChanged", "Media.playerEventsAdded"}:
                    player_key = (session, params.get("playerId"))
                    if player_key not in players and len(players) < 8:
                        players[player_key] = len(players) + 1
                    if player_key in players:
                        classifier = media_property_summary if method.endswith("PropertiesChanged") else media_event_summary
                        entries = params.get("properties" if method.endswith("PropertiesChanged") else "events", [])
                        for entry in entries[:64]:
                            summary = classifier(entry)
                            if summary:
                                output = {**summary, "player": players[player_key], "target": target_kind}
                                marker = json.dumps(output, sort_keys=True)
                                if marker not in media_seen and len(media_seen) < 64:
                                    media_seen.add(marker)
                                    print(marker, flush=True)
                if sum(counts.values()) >= 24:
                    print("STOPPED_EVENT_LIMIT", flush=True)
                    break
        finally:
            # Never pause workers; turn off recursive attachment on every accepted session.
            # Closing this collector's socket also disposes its debugging sessions.
            cleanup_failed = False
            for session in reversed(list(sessions)):
                try:
                    await asyncio.wait_for(send("Target.setAutoAttach", {"autoAttach": False,
                                                                          "waitForDebuggerOnStart": False,
                                                                          "flatten": True}, session), 1)
                except (OSError, TimeoutError, websockets.exceptions.WebSocketException):
                    cleanup_failed = True
            if cleanup_failed:
                print("CLEANUP_COMMAND_UNAVAILABLE", flush=True)
        print(json.dumps({"closed_counts": counts, "network_counts": network_counts,
                          "target_counts": target_counts, "response_types": response_types,
                          "media_markers": len(media_seen)}), flush=True)
        if handshake:
            print(json.dumps({"handshake_counts": handshake_counts,
                              "handshake_markers": min(handshake_markers, 64),
                              "handshake_tracking_limited": handshake_tracking_limited}), flush=True)
        roles.final()


class ClassifierTests(unittest.TestCase):
    def test_role_allowlist_has_no_arbitrary_host_or_authority_fallback(self):
        for host, role in ROLE_HOSTS.items():
            result = handshake_role(f"https://{host}/SECRET?token=SECRET")
            self.assertEqual(role, result["host_role"])
            self.assertNotIn("SECRET", json.dumps(result))
            self.assertNotIn(host, json.dumps(result))
        for value in ("http://license.abema.io/abematv-dash", "https://user@license.abema.io/",
                      "https://license.abema.io:443/", "https://license.abema.io.evil.example/",
                      "https://unknown.abema-tv.com/", "https://api.p-c3-e.abema-tv.com/#SECRET",
                      None, [], "https://[bad", "x" * 8193):
            self.assertIsNone(handshake_role(value))

    def test_resource_route_and_media_token_candidates_are_exact_shape_and_method(self):
        resource = "https://streaming-api-cf.p-c2-x.abema-tv.com/v1/playbackResources/abema-news"
        self.assertEqual("RESOURCE_ROUTE", handshake_role(resource, "GET")["route_kind"])
        for value, method in ((resource + "?token=SECRET", "GET"), (resource, "POST"),
                              (resource + "/extra", "GET"), (resource.replace("abema-news", ".."), "GET"),
                              (resource.replace("playbackResources", "%70laybackResources"), "GET")):
            self.assertEqual("OTHER", handshake_role(value, method)["route_kind"])
        query = "&".join(f"{name}=SECRET" for name in sorted(MEDIA_TOKEN_QUERY_NAMES))
        media = f"https://api.p-c3-e.abema-tv.com/v1/media/token?{query}"
        self.assertEqual("MEDIA_TOKEN_ROUTE_CANDIDATE", handshake_role(media, "GET")["route_kind"])
        for value, method in ((media, "POST"), (media + "&osName=SECRET", "GET"),
                              (media + "&unexpected=SECRET", "GET"),
                              (media.replace("osName=SECRET", "other=SECRET"), "GET"),
                              (media.replace("osName=SECRET", "osName"), "GET"),
                              (media.replace("api.p-c3-e.abema-tv.com", "api.abema.io"), "GET"),
                              (media.replace("/token?", "/token/extra?"), "GET")):
            self.assertEqual("OTHER", handshake_role(value, method)["route_kind"])

    def test_license_route_labels_require_a_license_role(self):
        for host, role in ROLE_HOSTS.items():
            for path, expected in (("/abematv-dash", "DASH_LICENSE_ROUTE"),
                                   ("/abematv-hls", "HLS_LICENSE_ROUTE")):
                result = handshake_role(f"https://{host}{path}?token=SECRET", "POST")
                self.assertEqual(expected if role in {"LICENSE_LEGACY", "LICENSE_PROXY_CONFIG"}
                                 else "OTHER", result["route_kind"])
                self.assertNotIn("SECRET", json.dumps(result))

    def test_role_payloads_and_cache_flags_are_closed_and_unknown_preserving(self):
        url = "https://license.p-c3-e.abema-tv.com/abematv-dash?token=SECRET"
        request = role_request_summary({"url": url, "method": "POST", "hasPostData": True,
                                        "postData": "SECRET", "headers": {"Authorization": "SECRET"}})
        self.assertEqual("LICENSE_PROXY_CONFIG", request["host_role"])
        self.assertEqual("DASH_LICENSE_ROUTE", request["route_kind"])
        response = role_response_summary({"url": url, "status": 200, "mimeType": "application/json",
                                          "fromDiskCache": False, "fromServiceWorker": True,
                                          "fromPrefetchCache": "SECRET", "body": "SECRET"})
        self.assertIs(response["disk_cache"], False)
        self.assertIs(response["service_worker"], True)
        self.assertIsNone(response["prefetch_cache"])
        self.assertNotIn("SECRET", json.dumps([request, response]))
        self.assertEqual(9, handshake_role(url.replace("/abematv-dash", "/SECRET" * 50))["path_segments"])
        self.assertIsNone(role_response_summary({"url": url, "status": True})["status"])

    def test_handshake_origins_are_closed_and_authority_checked(self):
        for host, kind in (("streaming-api-cf.p-c2-x.abema-tv.com", "RESOURCE_ORIGIN"),
                           ("api.abema.io", "API_ORIGIN"), ("license.abema.io", "LICENSE_ORIGIN"),
                           ("other.abema.io", "PROVIDER_OTHER_ORIGIN"),
                           ("other.abema-tv.com", "PROVIDER_OTHER_ORIGIN")):
            self.assertEqual(kind, handshake_kind(f"https://{host}/SECRET?token=SECRET"))
        for value in ("https://abema.io.evil.example/", "https://abema-tv.com.evil.example/",
                      "https://user@api.abema.io/", "https://api.abema.io:443/", "http://api.abema.io/",
                      "https://notabema.io/", "https://[bad", None, [], "https://abema.tv/login"):
            self.assertIsNone(handshake_kind(value))

    def test_handshake_summaries_never_read_or_emit_sensitive_values(self):
        request = {"url": "https://api.abema.io/SECRET?token=SECRET", "method": "POST",
                   "hasPostData": True, "postData": "SECRET", "headers": {"Authorization": "SECRET"}}
        self.assertEqual({"handshake_event": "REQUEST", "kind": "API_ORIGIN", "method": "POST",
                          "has_post_data": True}, handshake_request_summary(request))
        response = {"url": request["url"], "status": 200, "mimeType": "application/json",
                    "headers": {"Set-Cookie": "SECRET"}, "body": "SECRET"}
        self.assertEqual({"handshake_event": "RESPONSE", "kind": "API_ORIGIN", "status": 200,
                          "mime_class": "JSON"}, handshake_response_summary(response))
        request.update(method="SECRET", hasPostData="SECRET")
        response.update(status="SECRET", mimeType="SECRET")
        results = [handshake_request_summary(request), handshake_response_summary(response)]
        self.assertNotIn("SECRET", json.dumps(results))
        self.assertEqual("OTHER", results[0]["method"])
        self.assertIsNone(results[0]["has_post_data"])
        self.assertIsNone(results[1]["status"])

    def test_spa_account_navigation_stops_only_the_main_frame(self):
        params = {"frameId": "main", "url": "https://abema.tv/login"}
        self.assertTrue(navigation_blocked("Page.navigatedWithinDocument", params, "main"))
        self.assertTrue(navigation_blocked("Page.navigatedWithinDocument", params, None))
        self.assertFalse(navigation_blocked("Page.navigatedWithinDocument", params, "other"))
        params["url"] = "https://abema.tv/now-on-air/abema-news"
        self.assertFalse(navigation_blocked("Page.navigatedWithinDocument", params, "main"))

    def test_full_navigation_stops_account_pages_not_child_frames(self):
        params = {"frame": {"id": "main", "url": "https://abema.tv/login"}}
        self.assertTrue(navigation_blocked("Page.frameNavigated", params, "main"))
        params["frame"]["parentId"] = "other"
        self.assertFalse(navigation_blocked("Page.frameNavigated", params, "main"))

    def test_exact_playback_targets(self):
        for path in PLAYBACK_PATHS:
            self.assertTrue(playback_target({"type": "page", "url": f"https://abema.tv{path}"}))

    def test_reject_navigation_variants_and_accounts(self):
        for url in ("https://abema.tv/login", "https://abema.tv/account", "https://abema.tv/now-on-air/other",
                    "https://abema.tv/now-on-air/abema-news?token=SECRET", "https://user@abema.tv/now-on-air/abema-news",
                    "https://abema.tv:443/now-on-air/abema-news", "https://abema.tv/now-on-air/abema-news#fragment",
                    "https://abema.tv.evil.example/now-on-air/abema-news", "https://[bad", "about:blank"):
            self.assertFalse(playback_target({"type": "page", "url": url}))
        self.assertFalse(playback_target({"type": "iframe", "url": "https://abema.tv/now-on-air/abema-news"}))

    def test_only_exact_license_host_and_closed_routes(self):
        self.assertEqual("DASH_LICENSE", license_kind("https://license.abema.io/abematv-dash?t=SECRET"))
        self.assertEqual("HLS_LICENSE", license_kind("https://license.abema.io/abematv-hls"))
        self.assertEqual("OTHER_LICENSE_ROUTE", license_kind("https://license.abema.io/other?token=SECRET"))
        for url in ("http://license.abema.io/abematv-dash",
                    "https://license.abema.io:443/abematv-dash", "https://user@license.abema.io/abematv-dash",
                    "https://license.abema.io.evil.example/abematv-dash", "https://[bad"):
            self.assertIsNone(license_kind(url))

    def test_output_never_contains_provider_values(self):
        output = response_summary({"url": "https://license.abema.io/abematv-dash?t=SECRET",
                                   "status": 200, "mimeType": "application/json",
                                   "headers": {"Set-Cookie": "SECRET", "Content-Type": "application/json"},
                                   "body": "SECRET"})
        self.assertEqual({"event": "response", "kind": "DASH_LICENSE", "status": 200,
                          "mime_class": "JSON", "content_type_exact_json": True}, output)
        self.assertNotIn("SECRET", json.dumps(output))
        output = response_summary({"url": "https://license.abema.io/SECRET?token=SECRET",
                                   "status": 200, "mimeType": "SECRET", "headers": {"SECRET": "SECRET"}})
        self.assertEqual("OTHER_LICENSE_ROUTE", output["kind"])
        self.assertNotIn("SECRET", json.dumps(output))

    def test_mime_and_header_are_not_conflated(self):
        base = {"url": "https://license.abema.io/abematv-dash", "status": 403,
                "mimeType": "application/json", "headers": {"Content-Type": "application/json; charset=utf-8"}}
        self.assertFalse(response_summary(base)["content_type_exact_json"])
        base.update(mimeType="provider-specific-value", status="provider-specific-value")
        self.assertEqual("OTHER", response_summary(base)["mime_class"])
        self.assertIsNone(response_summary(base)["status"])
        self.assertIsNone(response_summary({"url": "https://abema.tv/login"}))

    def test_only_owned_playback_frames_and_abema_workers_are_eligible(self):
        for url in ("blob:https://abema.tv/abc-123", "https://abema.tv/assets/player.js"):
            self.assertTrue(related_target_allowed({"type": "worker", "url": url}))
        for url in ("blob:https://other.example/abc", "https://abema.tv/login",
                    "https://abema.tv/assets/player.js?token=SECRET", "https://user@abema.tv/assets/player.js",
                    "https://abema.tv.evil.example/assets/player.js", "about:blank"):
            self.assertFalse(related_target_allowed({"type": "worker", "url": url}))
        self.assertFalse(related_target_allowed({"type": "iframe", "url": "https://abema.tv/login"}))
        self.assertFalse(related_target_allowed({"type": "service_worker", "url": "https://abema.tv/assets/player.js"}))
        self.assertTrue(related_target_allowed({"type": "iframe", "url": "https://abema.tv/now-on-air/abema-news"}))

    def test_media_values_are_strictly_classified(self):
        self.assertEqual({"property": "CDM_ATTACHED", "value": True},
                         media_property_summary({"name": "kIsCdmAttached", "value": "true"}))
        for value in (["SECRET"], "SECRET", {}, "null"):
            self.assertIsNone(media_property_summary({"name": "kIsCdmAttached", "value": value})["value"])
        self.assertIsNone(media_property_summary({"name": ["SECRET"], "value": "SECRET"}))
        self.assertIsNone(media_property_summary({"name": "kFrameUrl", "value": "SECRET"}))
        result = media_property_summary({"name": "kSetCdm", "value": json.dumps({
            "key_system": "com.widevine.alpha", "SECRET": "SECRET"})})
        self.assertEqual({"property": "CDM_CONFIG", "key_system": "WIDEVINE"}, result)
        self.assertEqual("OTHER", media_property_summary({"name": "kSetCdm", "value": '{"key_system": []}'})["key_system"])
        tracks = media_property_summary({"name": "kVideoTracks", "value": json.dumps([
            {"encryption scheme": "cenc", "SECRET": "SECRET"}, {"encryption scheme": "SECRET"}])})
        self.assertEqual({"property": "VIDEO_TRACKS", "schemes": ["CENC", "OTHER"]}, tracks)
        self.assertNotIn("SECRET", json.dumps([result, tracks]))
        self.assertEqual("OTHER", mime_class(["SECRET"]))
        self.assertEqual("CENC", scheme_class("CENC"))
        self.assertEqual("CBCS", scheme_class("CBCS"))
        self.assertEqual("OTHER", scheme_class([]))

    def test_media_events_do_not_expose_source_or_event_payloads(self):
        for url, category in (("blob:https://abema.tv/SECRET", "BLOB"),
                              ("https://other.example/file.m3u8?token=SECRET", "HLS_URL")):
            result = media_event_summary({"value": json.dumps({"event": "kLoad", "url": url, "SECRET": "SECRET"})})
            self.assertEqual({"media_event": "LOAD", "source_class": category}, result)
        self.assertIsNone(media_event_summary({"value": '{"event": "SECRET"}'}))
        self.assertIsNone(media_event_summary({"value": '{"event": []}'}))
        self.assertIsNone(media_event_summary({"value": 'SECRET'}))


class CollectorTests(unittest.IsolatedAsyncioTestCase):
    async def run_trace(self, extra, *, handshake=False, handshake_roles=False):
        target = {"id": "root", "type": "page", "url": "https://abema.tv/now-on-air/abema-news",
                  "webSocketDebuggerUrl": "ws://localhost/devtools/page/test"}
        response, opener, connection, socket = MagicMock(), MagicMock(), AsyncMock(), MagicMock()
        response.read.return_value = json.dumps([target]).encode()
        opener.open.return_value.__enter__.return_value = response
        events = [{"id": 1, "result": {}}, {"id": 2, "result": {}},
                  {"id": 3, "result": {"frameTree": {"frame": {"id": "main", "url": target["url"]}}}}] + extra
        connection.recv.side_effect = [json.dumps(event) for event in events] + [TimeoutError()]
        socket.__aenter__, socket.__aexit__ = AsyncMock(return_value=connection), AsyncMock(return_value=False)
        output = io.StringIO()
        with patch("urllib.request.build_opener", return_value=opener), \
                patch("websockets.connect", return_value=socket), contextlib.redirect_stdout(output):
            await observe(36295, 1, handshake=handshake, handshake_roles=handshake_roles)
        return output.getvalue(), [json.loads(call.args[0]) for call in connection.send.call_args_list]

    async def test_role_mode_preserves_old_outputs_commands_and_redacts_values(self):
        request = {"method": "Network.requestWillBeSent", "params": {"requestId": "SECRET", "request": {
            "url": "https://license.p-c3-e.abema-tv.com/abematv-dash?token=SECRET", "method": "POST",
            "postData": "SECRET", "headers": {"Authorization": "SECRET"}}}}
        response = {"method": "Network.responseReceived", "params": {"requestId": "SECRET", "response": {
            "url": request["params"]["request"]["url"], "status": 200, "mimeType": "application/json"}}}
        for handshake in (False, True):
            before, commands = await self.run_trace([request, response], handshake=handshake)
            after, role_commands = await self.run_trace([request, response], handshake=handshake, handshake_roles=True)
            self.assertEqual(commands, role_commands)
            old_lines = [line for line in after.splitlines() if '"role_' not in line]
            self.assertEqual(before.splitlines(), old_lines)
            self.assertNotIn("SECRET", after)
            self.assertIn('"host_role": "LICENSE_PROXY_CONFIG"', after)
            self.assertIn('"method": "POST"', after)

    async def test_role_other_telemetry_does_not_hide_later_auth_and_license_routes(self):
        def request(index, url):
            return {"method": "Network.requestWillBeSent", "params": {"requestId": f"SECRET-{index}",
                    "request": {"url": url, "method": "GET"}}}
        extra = [request(index, "https://api.p-c3-e.abema-tv.com/SECRET") for index in range(70)]
        query = "&".join(f"{name}=SECRET" for name in sorted(MEDIA_TOKEN_QUERY_NAMES))
        media = f"https://api.p-c3-e.abema-tv.com/v1/media/token?{query}"
        extra += [request(100, media), {"method": "Network.responseReceived", "params": {"requestId": "SECRET-100",
                    "response": {"url": media, "status": 200, "mimeType": "application/json"}}},
                  request(101, "https://license.p-c3-e.abema-tv.com/abematv-dash?token=SECRET")]
        output, _ = await self.run_trace(extra, handshake_roles=True)
        self.assertNotIn("SECRET", output)
        self.assertIn('"route_kind": "MEDIA_TOKEN_ROUTE_CANDIDATE"', output)
        self.assertIn('"route_kind": "DASH_LICENSE_ROUTE"', output)
        final = json.loads(output.splitlines()[-1])
        self.assertEqual(71, final["role_counts"]["API_CONFIG"]["requests"])
        self.assertEqual(1, final["role_route_requests"]["API_CONFIG"]["MEDIA_TOKEN_ROUTE_CANDIDATE"])
        self.assertEqual(["API_CONFIG"], final["role_tracking_limited"])
        self.assertLessEqual(final["role_markers"]["API_CONFIG"], 16)

    async def test_role_cache_redirects_late_failures_and_completion_are_correlated(self):
        url = "https://license.p-c3-e.abema-tv.com/abematv-dash?token=SECRET"
        request = {"method": "Network.requestWillBeSent", "params": {"requestId": "SECRET", "request": {
            "url": url, "method": "POST"}}}
        cache = {"method": "Network.requestServedFromCache", "params": {"requestId": "SECRET"}}
        response = {"method": "Network.responseReceived", "params": {"requestId": "SECRET", "response": {
            "url": url, "status": 200, "mimeType": "application/json", "fromDiskCache": False}}}
        failure = {"method": "Network.loadingFailed", "params": {"requestId": "SECRET", "errorText": "SECRET"}}
        finished = {"method": "Network.loadingFinished", "params": {"requestId": "SECRET"}}
        redirect = {**request, "params": {**request["params"], "request": {"url": "https://other.example/SECRET"},
            "redirectResponse": {**response["params"]["response"], "status": 302}}}
        output, _ = await self.run_trace([request, cache, cache, response, failure,
            request, response, finished, failure, request, cache, redirect, failure], handshake_roles=True)
        self.assertNotIn("SECRET", output)
        self.assertEqual(2, output.count('"role_event": "SERVED_FROM_CACHE"'))
        self.assertIn('"served_from_cache": true', output)
        self.assertIn('"redirect": true', output)
        final = json.loads(output.splitlines()[-1])
        self.assertEqual({"requests": 3, "responses": 3, "failures": 1}, final["role_counts"]["LICENSE_PROXY_CONFIG"])

    async def test_role_tracking_is_per_role_bounded_and_detach_revokes_children(self):
        attach = {"method": "Target.attachedToTarget", "params": {"sessionId": "child", "targetInfo": {
            "type": "worker", "targetId": "worker", "parentId": "root", "url": "blob:https://abema.tv/abc"}}}
        request = {"method": "Network.requestWillBeSent", "params": {"requestId": "SECRET", "request": {
            "url": "https://license.p-c3-e.abema-tv.com/abematv-dash", "method": "POST"}}}
        failure = {"method": "Network.loadingFailed", "params": {"requestId": "SECRET", "errorText": "SECRET"}}
        extra = [attach, request, {**request, "sessionId": "child"}, {**failure, "sessionId": "child"},
                 {"method": "Target.detachedFromTarget", "params": {"sessionId": "child"}},
                 {**request, "sessionId": "child"}, failure]
        output, _ = await self.run_trace(extra, handshake_roles=True)
        self.assertEqual({"requests": 2, "responses": 0, "failures": 2},
                         json.loads(output.splitlines()[-1])["role_counts"]["LICENSE_PROXY_CONFIG"])
        many = [{**request, "params": {**request["params"], "requestId": f"SECRET-{index}"}} for index in range(70)]
        bounded, _ = await self.run_trace(many + [failure], handshake_roles=True)
        final = json.loads(bounded.splitlines()[-1])
        self.assertEqual(["LICENSE_PROXY_CONFIG"], final["role_tracking_limited"])
        self.assertEqual(16, final["role_markers"]["LICENSE_PROXY_CONFIG"])
        self.assertNotIn("SECRET", output + bounded)

    async def test_handshake_opt_in_preserves_default_and_bounds_output(self):
        request = {"method": "Network.requestWillBeSent", "params": {"requestId": "SECRET",
                   "request": {"url": "https://streaming-api-cf.p-c2-x.abema-tv.com/SECRET?token=SECRET",
                               "method": "GET", "postData": "SECRET"}}}
        response = {"method": "Network.responseReceived", "params": {"requestId": "SECRET", "response": {
            "url": request["params"]["request"]["url"], "status": 200, "mimeType": "application/json"}}}
        default, default_calls = await self.run_trace([request, response])
        self.assertNotIn("handshake", default)
        output, calls = await self.run_trace([request, response] * 40, handshake=True)
        self.assertNotIn("SECRET", output)
        self.assertEqual(64, output.count('"handshake_event"'))
        self.assertEqual(1, output.count("HANDSHAKE_OUTPUT_LIMIT"))
        final = json.loads(output.splitlines()[-1])
        self.assertEqual({"requests": 40, "responses": 40, "failures": 0},
                         final["handshake_counts"]["RESOURCE_ORIGIN"])
        self.assertFalse(final["handshake_tracking_limited"])
        self.assertEqual(default_calls, calls)

    async def test_handshake_failure_tracking_is_bounded_and_session_scoped(self):
        requests = [{"method": "Network.requestWillBeSent", "params": {"requestId": f"SECRET-{index}",
                     "request": {"url": "https://api.abema.io/SECRET", "method": "POST"}}} for index in range(70)]
        foreign = {"sessionId": "unattached", "method": "Network.loadingFailed", "params": {"requestId": "SECRET-0"}}
        failure = {"method": "Network.loadingFailed", "params": {"requestId": "SECRET-0", "errorText": "SECRET"}}
        output, _ = await self.run_trace(requests + [foreign, failure, failure], handshake=True)
        self.assertNotIn("SECRET", output)
        final = json.loads(output.splitlines()[-1])
        self.assertTrue(final["handshake_tracking_limited"])
        self.assertEqual(70, final["handshake_counts"]["API_ORIGIN"]["requests"])
        self.assertEqual(1, final["handshake_counts"]["API_ORIGIN"]["failures"])

    async def test_handshake_tracks_failures_after_response_and_releases_finished_requests(self):
        request = {"method": "Network.requestWillBeSent", "params": {"requestId": "SECRET", "request": {
            "url": "https://api.abema.io/SECRET", "method": "GET"}}}
        response = {"method": "Network.responseReceived", "params": {"requestId": "SECRET", "response": {
            "url": "https://api.abema.io/SECRET", "status": 200, "mimeType": "application/json"}}}
        failure = {"method": "Network.loadingFailed", "params": {"requestId": "SECRET", "errorText": "SECRET"}}
        finished = {"method": "Network.loadingFinished", "params": {"requestId": "SECRET"}}
        output, _ = await self.run_trace([request, response, failure, request, response, finished, failure], handshake=True)
        self.assertNotIn("SECRET", output)
        final = json.loads(output.splitlines()[-1])
        self.assertEqual({"requests": 2, "responses": 2, "failures": 1}, final["handshake_counts"]["API_ORIGIN"])
        foreign = {**request, "params": {**request["params"], "request": {"url": "https://other.example/SECRET"}}}
        redirected, _ = await self.run_trace([request, foreign, failure], handshake=True)
        self.assertEqual(0, json.loads(redirected.splitlines()[-1])["handshake_counts"]["API_ORIGIN"]["failures"])

    async def test_handshake_redirect_responses_are_classified_before_request_id_reuse(self):
        request = {"method": "Network.requestWillBeSent", "params": {"requestId": "SECRET", "request": {
            "url": "https://api.abema.io/SECRET", "method": "GET"}}}
        redirect = {**request, "params": {**request["params"], "request": {"url": "https://other.example/SECRET"},
            "redirectResponse": {"url": "https://api.abema.io/SECRET?token=SECRET", "status": 302,
                                 "mimeType": "text/html", "headers": {"Location": "SECRET"}}}}
        output, _ = await self.run_trace([request, redirect], handshake=True)
        self.assertNotIn("SECRET", output)
        self.assertIn('"status": 302', output)
        self.assertIn('"redirect": true', output)
        self.assertEqual({"requests": 1, "responses": 1, "failures": 0},
                         json.loads(output.splitlines()[-1])["handshake_counts"]["API_ORIGIN"])
        provider_redirect = {**redirect, "params": {**redirect["params"], "request": {
            "url": "https://streaming-api-cf.p-c2-x.abema-tv.com/SECRET", "method": "GET"}}}
        response = {"method": "Network.responseReceived", "params": {"requestId": "SECRET", "response": {
            "url": provider_redirect["params"]["request"]["url"], "status": 200, "mimeType": "application/json"}}}
        output, _ = await self.run_trace([request, provider_redirect, response], handshake=True)
        final = json.loads(output.splitlines()[-1])
        self.assertEqual(1, final["handshake_counts"]["API_ORIGIN"]["responses"])
        self.assertEqual({"requests": 1, "responses": 1, "failures": 0},
                         final["handshake_counts"]["RESOURCE_ORIGIN"])

    async def test_aggregate_traffic_does_not_end_license_observation_or_expose_values(self):
        target = {"type": "page", "url": "https://abema.tv/now-on-air/abema-news",
                  "webSocketDebuggerUrl": "ws://localhost/devtools/page/test"}
        response = MagicMock()
        response.read.return_value = json.dumps([target]).encode()
        opener = MagicMock()
        opener.open.return_value.__enter__.return_value = response
        connection = AsyncMock()
        events = [{"id": 1, "result": {}}, {"id": 2, "result": {}},
                  {"id": 3, "result": {"frameTree": {"frame": {"id": "main", "url": target["url"]}}}}]
        events += [{"method": "Network.requestWillBeSent", "params": {
            "requestId": "SECRET", "request": {"url": "https://other.example/SECRET"}}}] * 27
        events += [{"method": "Network.requestWillBeSent", "params": {
            "requestId": "SECRET", "request": {"url": "https://license.abema.io/abematv-dash?token=SECRET"}}},
                   {"method": "Network.responseReceived", "params": {"response": {
                       "url": "https://license.abema.io/abematv-dash?token=SECRET", "status": 200,
                       "mimeType": "application/octet-stream", "headers": {"Set-Cookie": "SECRET"}}}}]
        connection.recv.side_effect = [json.dumps(event) for event in events] + [TimeoutError()]
        socket = MagicMock()
        socket.__aenter__ = AsyncMock(return_value=connection)
        socket.__aexit__ = AsyncMock(return_value=False)
        output = io.StringIO()
        with patch("urllib.request.build_opener", return_value=opener), \
                patch("websockets.connect", return_value=socket), contextlib.redirect_stdout(output):
            await observe(36295, 1)
        result = output.getvalue()
        self.assertEqual(1, result.count("CDP_SETUP_CONFIRMED"))
        self.assertNotIn("SECRET", result)
        final = json.loads(result.splitlines()[-1])
        self.assertEqual({"requests": 1, "responses": 1, "failures": 0}, final["closed_counts"])
        self.assertEqual({"requests": 28, "responses": 1, "failures": 0}, final["network_counts"])
        self.assertEqual({"Network.enable", "Page.enable", "Page.getFrameTree", "Media.enable", "Target.setAutoAttach"},
                         {json.loads(call.args[0])["method"] for call in connection.send.call_args_list})

    async def test_worker_requests_are_session_scoped_and_detached_events_ignored(self):
        attach = {"method": "Target.attachedToTarget", "params": {"sessionId": "SECRET-child",
                  "targetInfo": {"type": "worker", "targetId": "SECRET-worker", "parentId": "root",
                                 "url": "blob:https://abema.tv/abc-123"}}}
        request = {"method": "Network.requestWillBeSent", "params": {"requestId": "SECRET-collision",
                   "request": {"url": "https://license.abema.io/abematv-dash?token=SECRET"}}}
        output, calls = await self.run_trace([attach, request, {**request, "sessionId": "SECRET-child"},
            {"method": "Network.loadingFailed", "sessionId": "SECRET-child", "params": {"requestId": "SECRET-collision"}},
            {"method": "Target.detachedFromTarget", "params": {"sessionId": "SECRET-child"}},
            {**request, "sessionId": "SECRET-child"}])
        self.assertNotIn("SECRET", output)
        final = json.loads(output.splitlines()[-1])
        self.assertEqual({"requests": 2, "responses": 0, "failures": 1}, final["closed_counts"])
        self.assertEqual(1, final["target_counts"]["WORKER"]["requests"])
        self.assertTrue(any(call.get("sessionId") == "SECRET-child" and call["method"] == "Network.enable" for call in calls))
        self.assertTrue(all(call["params"]["maxPostDataSize"] == 0 for call in calls if call["method"] == "Network.enable"))
        self.assertTrue(all(not call["params"]["waitForDebuggerOnStart"] for call in calls if call["method"] == "Target.setAutoAttach"))

    async def test_foreign_and_unowned_targets_are_refused_before_instrumentation(self):
        events = [{"method": "Target.attachedToTarget", "params": {"sessionId": "SECRET-child",
            "targetInfo": {"type": "worker", "targetId": "SECRET-target", "parentId": parent,
                           "url": url}}} for parent, url in (("root", "https://other.example/SECRET"),
                                                             ("foreign", "https://abema.tv/assets/player.js"))]
        events += [{"sessionId": "SECRET-child", "method": "Network.requestWillBeSent", "params": {"request": {
            "url": "https://license.abema.io/abematv-dash"}}}]
        output, calls = await self.run_trace(events)
        self.assertEqual(1, output.count("RELATED_TARGET_REFUSED"))
        self.assertNotIn("SECRET", output)
        self.assertFalse(any(call.get("sessionId") == "SECRET-child" for call in calls))
        self.assertEqual(0, json.loads(output.splitlines()[-1])["network_counts"]["requests"])

    async def test_optional_domains_report_unavailable_without_hiding_network(self):
        output, _ = await self.run_trace([{"id": 4, "error": {"message": "SECRET"}},
            {"id": 5, "error": {"message": "SECRET"}}, {"method": "Network.responseReceived", "params": {
                "response": {"url": "https://other.example/SECRET", "mimeType": "application/dash+xml"}}}])
        self.assertNotIn("SECRET", output)
        self.assertIn('"supported": false', output)
        self.assertEqual({"DASH": 1}, json.loads(output.splitlines()[-1])["response_types"]["PAGE"])

    async def test_media_output_is_deduplicated_redacted_and_bounded(self):
        entries = [{"name": "kIsCdmAttached", "value": "true"}, {"name": "kFrameTitle", "value": "SECRET"}]
        media = {"method": "Media.playerPropertiesChanged", "params": {"playerId": "SECRET", "properties": entries}}
        output, calls = await self.run_trace([media] * 70)
        self.assertNotIn("SECRET", output)
        self.assertEqual(1, json.loads(output.splitlines()[-1])["media_markers"])
        self.assertEqual({"Network.enable", "Page.enable", "Page.getFrameTree", "Media.enable", "Target.setAutoAttach"},
                         {call["method"] for call in calls})

    async def test_nested_refusal_uses_the_emitting_parent_and_bounds_attempts(self):
        parent = {"method": "Target.attachedToTarget", "params": {"sessionId": "child", "targetInfo": {
            "type": "worker", "targetId": "worker", "parentId": "root", "url": "blob:https://abema.tv/abc"}}}
        refused = {"sessionId": "child", "method": "Target.attachedToTarget", "params": {
            "sessionId": "SECRET", "targetInfo": {"type": "worker", "targetId": "SECRET", "parentId": "worker",
                                                   "url": "https://other.example/SECRET"}}}
        output, calls = await self.run_trace([parent] + [refused] * 20)
        self.assertNotIn("SECRET", output)
        self.assertIn("STOPPED_ATTACHMENT_LIMIT", output)
        detaches = [call for call in calls if call["method"] == "Target.detachFromTarget"]
        self.assertEqual(15, len(detaches))
        self.assertTrue(all(call.get("sessionId") == "child" for call in detaches))

    async def test_ancestor_detach_revokes_descendants_and_account_navigation_stops(self):
        parent = {"method": "Target.attachedToTarget", "params": {"sessionId": "child", "targetInfo": {
            "type": "worker", "targetId": "worker", "parentId": "root", "url": "blob:https://abema.tv/abc"}}}
        nested = {"sessionId": "child", "method": "Target.attachedToTarget", "params": {
            "sessionId": "nested", "targetInfo": {"type": "worker", "targetId": "nested-worker", "parentId": "worker",
                                                   "url": "https://abema.tv/assets/player.js"}}}
        request = {"sessionId": "nested", "method": "Network.requestWillBeSent", "params": {
            "requestId": "SECRET", "request": {"url": "https://license.abema.io/abematv-dash?token=SECRET"}}}
        output, calls = await self.run_trace([parent, nested,
            {"method": "Target.detachedFromTarget", "params": {"sessionId": "child"}}, request,
            {"method": "Page.navigatedWithinDocument", "params": {"frameId": "main", "url": "https://abema.tv/login"}}])
        self.assertIn("STOPPED_NON_PLAYBACK_NAVIGATION", output)
        self.assertNotIn("SECRET", output)
        self.assertEqual(0, json.loads(output.splitlines()[-1])["closed_counts"]["requests"])
        self.assertTrue(any(call.get("sessionId") == "nested" and call["method"] == "Network.enable" for call in calls))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int)
    parser.add_argument("--seconds", type=int, default=45)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--handshake-metadata", action="store_true",
                        help="opt in to bounded origin/method/status sequencing; no headers or bodies")
    parser.add_argument("--handshake-role-metadata", action="store_true",
                        help="opt in to exact configured-host/route/cache labels with per-role bounds; no headers or bodies")
    args = parser.parse_args()
    if args.self_test:
        unittest.main(argv=[__file__])
    elif not args.port or not 1024 <= args.port <= 65535 or not 1 <= args.seconds <= 60:
        parser.error("provide a local forwarded port and a duration of 1–60 seconds")
    else:
        try:
            asyncio.run(observe(args.port, args.seconds, handshake=args.handshake_metadata,
                                handshake_roles=args.handshake_role_metadata))
        except (OSError, ValueError, KeyError, TypeError, AttributeError, websockets.exceptions.WebSocketException):
            # Exception details may contain URLs; never print the exception text.
            print("INSPECTION_UNAVAILABLE", flush=True)
            raise SystemExit(1) from None
