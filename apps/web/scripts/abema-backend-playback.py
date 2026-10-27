#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Explicit fresh GLOBAL News decoder proof; no licensing or CDM operations."""
import importlib.util
import json
from pathlib import Path
import resource
import shutil
import sys
import tempfile

_spec = importlib.util.spec_from_file_location("web_abema_manifest", Path(__file__).with_name("abema-backend-manifest.py"))
manifest = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = manifest
_spec.loader.exec_module(manifest)
source, bootstrap, Refused = manifest.source, manifest.bootstrap, manifest.Refused
_spec = importlib.util.spec_from_file_location("web_abema_modern_context", Path(__file__).with_name("abema-modern-context.py"))
modern = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = modern
_spec.loader.exec_module(modern)


class ModernTransport(bootstrap.NativeTransport):
    def __init__(self, metadata=None, **kwargs):
        super().__init__(**kwargs)
        self.metadata, self.ready, self.channels_used = metadata or modern.MetadataTransport(), None, False
        self.preparation_started = False

    def prepare(self, deadline, report, mapper):
        if self.preparation_started or deadline <= bootstrap.time.monotonic(): raise Refused()
        self.preparation_started = True
        def get(operation, fields):
            status, body = self.metadata.request(operation, fields, deadline - bootstrap.time.monotonic())
            report("HTTP_STATUS", status=status)
            if status != 200: raise Refused()
            return body
        region = modern.extract_region(get("REGION_PAGE", {}))
        if region["isAllowed"] is not True or region["divisionCode"] != 1 or region["cdnURL"] != modern.REGIONS["GLOBAL"]: raise Refused()
        if region["status"]: get("SELECTED_REGION", {"selected": "GLOBAL"})
        query = mapper(region)
        if not isinstance(query, dict) or query != {"division": 1} or type(query.get("division")) is not int or deadline <= bootstrap.time.monotonic(): raise Refused()
        self.ready = deadline
        report("MODERN_CONTEXT_READY", globalContext=True, deviceFlowCompleted=False, entitlementEstablished=False)

    def request(self, operation, fields, timeout):
        if operation != "CHANNELS": return super().request(operation, fields, timeout)
        if self.ready is None or self.channels_used or fields != {} or timeout <= 0 or bootstrap.time.monotonic() >= self.ready: raise Refused()
        self.channels_used = True
        return self.metadata.request("MODERN_CHANNELS", {"query": {"division": 1}}, min(timeout, self.ready - bootstrap.time.monotonic()))


def map_division(attempt, region):
    attempt.phase = "REGION_MAPPER"
    runtime_root = Path(tempfile.mkdtemp(prefix="abema-region-"))
    uncertain = False
    try:
        runtime, config = runtime_root / "runtime", runtime_root / "docker-config"
        runtime.mkdir(); config.mkdir(mode=0o700)
        for name, _, size, digest in bootstrap.ASSETS:
            raw = attempt.request("ASSET", {"name": name})
            if len(raw) != size or bootstrap.hashlib.sha256(raw).hexdigest() != digest: raise Refused()
            (runtime / name).write_bytes(raw); (runtime / name).chmod(0o444)
        for target, original in (("abema-source-helper.cjs", "abema-region-helper.cjs"), ("abema-guest-helper.cjs", "abema-guest-helper.cjs")):
            (runtime / target).write_bytes(Path(__file__).with_name(original).read_bytes()); (runtime / target).chmod(0o444)
        container = bootstrap.OwnedContainer(runtime, config, attempt.runner, attempt.deadline, helper="abema-source-helper.cjs", private=True)
        with container.opened() as identity:
            result = container.command("start", "--attach", "--interactive", identity, timeout=10, limit=8192,
                                       input_data=json.dumps(region).encode())
            if result.code != 0 or result.stderr: raise Refused()
            value = bootstrap.strict_json(result.output, 8192)
            if not isinstance(value, dict) or set(value) != {"query", "mapperMatched"} or value["mapperMatched"] is not True or not isinstance(value["query"], dict) or value["query"] != {"division": 1} or type(value["query"].get("division")) is not int: raise Refused()
        return value["query"]
    except bootstrap.CleanupUnconfirmed:
        uncertain = True
        attempt.retained_context_runtime = runtime_root
        raise
    finally:
        with bootstrap.completing_cleanup():
            if not uncertain:
                try: shutil.rmtree(runtime_root)
                except BaseException: attempt.retained_context_runtime = runtime_root; raise bootstrap.CleanupUnconfirmed() from None


def playback_report(raw, nonce):
    try: value = bootstrap.strict_json(raw, 8192)
    except BaseException: raise bootstrap.CleanupUnconfirmed() from None
    expected = {"nonce", "outcome", "stage", "publicationStage", "boundary", "auxiliaryReason", "utcScheme", "http", "sdkCode", "frames", "advancedMs", "width", "height", "counts", "formats", "mediaKeySessions", "cleanupConfirmed"}
    if not isinstance(value, dict) or set(value) - expected or value.get("nonce") != nonce or value.get("cleanupConfirmed") is not True: raise bootstrap.CleanupUnconfirmed()
    if not (expected - {"http", "sdkCode", "auxiliaryReason", "utcScheme"}) <= set(value): raise Refused()
    if value.get("outcome") not in {"DECODED", "BOUNDARY", "REFUSED"} or value.get("stage") not in {"idle", "input", "browser", "page", "prepare", "ready", "play", "cleanup"} or value.get("publicationStage") not in {"IDLE", "STREAMS", "INDEX", "REFERENCES", "FILTER", "PUBLISH"} or value.get("boundary") not in {"NONE", "EXPIRED", "CANCELLED", "MANIFEST", "PROTECTION", "AUXILIARY", "EARLY_INDEX", "UNDECLARED", "RESOURCE", "LIMIT", "HTTP", "NETWORK"} or value.get("mediaKeySessions") is not False: raise Refused()
    if "auxiliaryReason" in value and (type(value["auxiliaryReason"]) is not str or value["boundary"] != "AUXILIARY" or value["auxiliaryReason"] not in {
            "UTC_TIMING", "SEGMENT_BASE", "CONTENT_STEERING", "PATCH_LOCATION", "XLINK", "XINCLUDE",
            "CHANGED_LOCATION", "TEMPLATE_INDEX", "REQUEST_TYPE", "DISPATCH_TYPE"}): raise Refused()
    if "utcScheme" in value and (type(value["utcScheme"]) is not str or value.get("auxiliaryReason") != "UTC_TIMING" or value["utcScheme"] not in {
            "DIRECT", "HTTP_HEAD", "HTTP_XSDATE", "OTHER"}): raise Refused()
    if value.get("auxiliaryReason") == "UTC_TIMING" and "utcScheme" not in value: raise Refused()
    for name, maximum in (("http", 599), ("sdkCode", 100000), ("frames", 1000000), ("advancedMs", 120000), ("width", 16384), ("height", 16384)):
        if name in value and (type(value[name]) is not int or not 0 <= value[name] <= maximum): raise Refused()
    if "http" in value and value["http"] < 100: raise Refused()
    counts = value.get("counts")
    if not isinstance(counts, dict) or set(counts) != {"manifest", "initialization", "segment", "deniedDrm"} or any(type(item) is not int or not 0 <= item <= 64 for item in counts.values()): raise Refused()
    formats = value.get("formats")
    if not isinstance(formats, dict) or set(formats) - {"basis", "audio", "video"} or formats.get("basis") != "last-sdk-buffered-playhead": raise Refused()
    for kind in ("audio", "video"):
        if kind not in formats: continue
        item = formats[kind]
        bounds = {"declaredBitrateBps": 2147483647, "observedAtPositionMs": 2**53 - 1,
                  **({"width": 16384, "height": 16384, "frameRate": 1000} if kind == "video" else {"channelCount": 64, "sampleRateHz": 768000})}
        codecs = {"AVC", "HEVC", "AV1", "VP8", "VP9", "OTHER"} if kind == "video" else {"AAC", "OPUS", "VORBIS", "AC3", "EAC3", "MP3", "OTHER"}
        if not isinstance(item, dict) or set(item) - ({"kind", "codec"} | bounds.keys()) or item.get("kind") != kind or item.get("codec") not in codecs: raise Refused()
        for key, number in item.items():
            if key in {"kind", "codec"}: continue
            if (type(number) not in ({int, float} if key == "frameRate" else {int}) or
                    not (0 <= number if key == "observedAtPositionMs" else 0 < number) or number > bounds[key]): raise Refused()
    if value["outcome"] == "DECODED" and (value.get("frames", 0) < 2 or value.get("advancedMs", 0) < 1000 or value.get("width", 0) < 1 or value.get("height", 0) < 1 or value["boundary"] != "NONE" or counts["deniedDrm"] or min(counts["manifest"], counts["initialization"], counts["segment"]) < 1): raise Refused()
    return {key: item for key, item in value.items() if key not in {"nonce", "cleanupConfirmed"}}


class BrowserPlaybackTransport:
    def __init__(self, runner=bootstrap.run_command):
        self.runner, self.used, self.retained_runtime = runner, False, None

    def request(self, fields, timeout):
        manifest.manifest_request_spec(fields)
        if self.used or timeout < 10: raise Refused()
        self.used = True
        binding = bootstrap.strict_json(fields["binding"], 8192)
        private = json.dumps({"uri": binding["uri"], "nonce": fields["nonce"], "mediaToken": fields["media_token"],
            "advertisedUris": source.advertised_playlists(fields["channel"]), "remainingMs": min(120000, int(timeout * 1000))}).encode()
        if len(private) > 16384: raise Refused()
        runtime = Path(tempfile.mkdtemp(prefix="p-")); uncertain = False
        try:
            source.owned_browser.validate_runtime_path(runtime)
            result = self.runner([shutil.which("node"), str(Path(__file__).with_name("abema-browser-playback.mjs")), "--owned-runtime", str(runtime)], min(timeout, 45), 8192, input_data=private, display=True)
            try: report = playback_report(result.output, fields["nonce"])
            except bootstrap.CleanupUnconfirmed: uncertain = True; raise
            if result.stderr or result.code not in {0, 1} or result.code == 1 and report["outcome"] != "REFUSED": raise Refused()
            return report
        finally:
            with bootstrap.completing_cleanup():
                try: source.owned_browser.cleanup_owned_browser(runtime)
                except BaseException: uncertain = True
                if not uncertain:
                    try: shutil.rmtree(runtime)
                    except BaseException: uncertain = True
                if uncertain: self.retained_runtime = runtime; raise bootstrap.CleanupUnconfirmed() from None


class PlaybackAttempt(source.SourceAttempt):
    def __init__(self, output, transport=None, runner=bootstrap.run_command, playback=None):
        super().__init__(output, transport or ModernTransport(), runner)
        self.playback = playback or BrowserPlaybackTransport(runner)
        self.retained_context_runtime = None

    def run(self):
        self.phase = "MODERN_CONTEXT"
        try: self.transport.prepare(self.deadline, self.report, lambda region: map_division(self, region))
        except bootstrap.CleanupUnconfirmed:
            self.cleanup_confirmed = False; self.report("CLEANUP_UNCONFIRMED")
        except bootstrap.Terminated: self.report("TERMINATED")
        except BaseException: self.report("REFUSED")
        else: return super().run()
        self.phase = "CLEANUP"
        self.report("FINISHED", cleanupConfirmed=self.cleanup_confirmed, grantsPersisted=False)
        return 1

    def bound_source(self, binding, nonce, media_token, channel):
        self.phase = "BROWSER_PLAYBACK"
        report = self.playback.request({"binding": binding, "nonce": nonce, "media_token": media_token, "channel": channel}, self.deadline - bootstrap.time.monotonic())
        outcome = report.pop("outcome")
        self.report("PLAYBACK_OBSERVATION", playbackOutcome=outcome, sourceBound=True, **report, grantsPersisted=False)
        if outcome != "DECODED": raise Refused()


def main(arguments=None, output=None):
    arguments = sys.argv[1:] if arguments is None else arguments
    output = sys.stdout if output is None else output
    if arguments != ["--run-global-playback"]:
        output.write('{"phase":"START","outcome":"EXACT_OPT_IN_REQUIRED"}\n'); return 2
    try: resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    except (OSError, ValueError): output.write('{"phase":"START","outcome":"CORE_LIMIT_UNAVAILABLE"}\n'); return 1
    if not shutil.which("docker") or not shutil.which("node"):
        output.write('{"phase":"START","outcome":"RUNTIME_UNAVAILABLE"}\n'); return 1
    attempt, guard = PlaybackAttempt(output), bootstrap.TerminationGuard()
    try:
        with guard: status = attempt.run()
    except bootstrap.Terminated: status = 1
    if guard.received:
        output.write(json.dumps({"phase": "CLEANUP", "outcome": "TERMINATED", "cleanupConfirmed": attempt.cleanup_confirmed, "grantsPersisted": False}) + "\n")
        return 1
    return status


if __name__ == "__main__":
    sys.exit(main())
