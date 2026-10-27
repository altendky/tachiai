#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Opt-in exact replay MPD metadata only; no media, ad or DRM operations."""
import importlib.util
import json
import math
from pathlib import Path
import resource
import shutil
import sys
import tempfile

_spec = importlib.util.spec_from_file_location("web_abema_episode_source", Path(__file__).with_name("abema-backend-episode-source.py"))
episode = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = episode
_spec.loader.exec_module(episode)
bootstrap, Refused = episode.bootstrap, episode.Refused
OUTCOMES = {"MPD_OBSERVED", "TRANSPORT_REFUSED", "XML_REFUSED", "LIFETIME_REFUSED"}
MIMES = {"NOT_CHECKED", "UNSUPPORTED", "DASH_XML", "APPLICATION_XML", "TEXT_XML"}
AUXILIARY = {"utc", "xlink", "steering", "patch", "location", "eventStream", "templateIndex"}
REFERENCES = {"sameOrigin", "otherOrigin", "unresolved", "invalid"}


def graph_valid(value):
    if not isinstance(value, dict) or set(value) != {"coverage", "periods", "adaptationSets", "representations", "contentProtection", "cencPresent", "psshPresent", "keyIdMetadata", "segmentModel", "auxiliary", "references"}: return False
    if value["coverage"] != "DECLARED_SUBSET" or type(value["segmentModel"]) is not str or value["segmentModel"] not in {"NONE", "TEMPLATE", "LIST", "BASE", "MIXED"}: return False
    if any(type(value[key]) is not int or not 0 <= value[key] <= 10000 for key in ("periods", "adaptationSets", "representations")): return False
    if sum(value[key] for key in ("periods", "adaptationSets", "representations")) > 10000: return False
    if any(type(value[key]) is not bool for key in ("contentProtection", "cencPresent", "psshPresent", "keyIdMetadata")) or (value["psshPresent"] or value["keyIdMetadata"]) and not value["cencPresent"]: return False
    if not isinstance(value["auxiliary"], dict) or set(value["auxiliary"]) != AUXILIARY or any(type(item) is not bool for item in value["auxiliary"].values()): return False
    if not isinstance(value["references"], dict) or set(value["references"]) != REFERENCES or any(type(item) is not int or not 0 <= item <= 2048 for item in value["references"].values()) or sum(value["references"].values()) > 2048: return False
    return True


def validate_receipt(value, nonce):
    if not isinstance(value, dict) or set(value) != {"nonce", "outcome", "browserReadable", "corsPermission", "status", "mime", "dispatched", "graph", "adContract", "cleanupConfirmed"} or value["nonce"] != nonce or value["cleanupConfirmed"] is not True:
        raise Refused()
    if type(value["outcome"]) is not str or value["outcome"] not in OUTCOMES or type(value["browserReadable"]) is not bool or type(value["dispatched"]) is not bool or value["adContract"] != "UNKNOWN" or type(value["mime"]) is not str or value["mime"] not in MIMES: raise Refused()
    if value["corsPermission"] != ("CORS_READABLE" if value["browserReadable"] else "UNESTABLISHED"): raise Refused()
    if value["browserReadable"]:
        if not value["dispatched"] or type(value["status"]) is not int or not 100 <= value["status"] <= 599: raise Refused()
    elif value["status"] is not None or value["mime"] != "NOT_CHECKED": raise Refused()
    if value["outcome"] == "MPD_OBSERVED":
        if not value["browserReadable"] or value["status"] != 200 or value["mime"] not in MIMES - {"NOT_CHECKED", "UNSUPPORTED"} or not graph_valid(value["graph"]): raise Refused()
    elif value["graph"] is not None: raise Refused()
    if value["outcome"] == "XML_REFUSED" and (not value["browserReadable"] or value["status"] != 200 or value["mime"] not in MIMES - {"NOT_CHECKED", "UNSUPPORTED"}): raise Refused()
    return value


def private_input(binding, nonce, metadata, expiry, gateway, media_token, deadline):
    episode.validate_binding(binding, "selection", nonce, metadata, expiry, gateway, media_token)
    if sys.platform != "linux" or bootstrap.time.get_clock_info("monotonic").implementation != "clock_gettime(CLOCK_MONOTONIC)": raise Refused()
    sample = bootstrap.time.monotonic_ns()
    # Round the existing float deadline down to a whole millisecond. No child
    # startup can grant another duration or round the deadline into the future.
    end = math.floor(deadline * 1000) * 1000000
    if not sample < end <= sample + 120000000000 or int(bootstrap.time.time() * 1000) >= expiry: raise episode.EpisodeRefused("LIFETIME")
    stream = gateway["streams"][binding["streamIndex"]]
    manifest = stream["manifests"][binding["manifestIndex"]]
    return {"episodeId": episode.EPISODE, "nonce": nonce, "uri": binding["uri"], "mediaToken": media_token,
        "protection": binding["drm"], "adMode": binding["adMode"],
        "advertisement": {"uri": manifest["url"], "systemId": stream["drm"]["systemId"], "adMode": manifest["adInsertion"]["mode"]},
        "clockSampleNs": str(sample), "deadlineNs": str(end), "expiresAtMs": expiry}


class EpisodeManifestTransport:
    def __init__(self, runner=bootstrap.run_command):
        self.runner, self.used, self.retained_runtime = runner, False, None

    def request(self, binding, nonce, metadata, expiry, gateway, media_token, deadline):
        packet = private_input(binding, nonce, metadata, expiry, gateway, media_token, deadline)
        if self.used: raise Refused()
        self.used = True
        wire = json.dumps(packet, separators=(",", ":")).encode()
        if len(wire) > 16384: raise Refused()
        runtime = Path(tempfile.mkdtemp(prefix="e-")); uncertain = False
        try:
            episode.source.owned_browser.validate_runtime_path(runtime)
            remaining = (int(packet["deadlineNs"]) - bootstrap.time.monotonic_ns()) / 1000000000
            if remaining <= 0: raise episode.EpisodeRefused("LIFETIME")
            result = self.runner([shutil.which("node"), str(Path(__file__).with_name("abema-browser-episode-manifest.mjs")),
                "--owned-runtime", str(runtime)], min(30, remaining), 8192, input_data=wire, display=True)
            wire = None
            value = bootstrap.strict_json(result.output, 8192)
            result.output = b""
            if not isinstance(value, dict) or value.get("cleanupConfirmed") is not True:
                uncertain = True; raise bootstrap.CleanupUnconfirmed()
            if result.code != 0 or result.stderr: raise Refused()
            return validate_receipt(value, nonce)
        finally:
            with bootstrap.completing_cleanup():
                try:
                    episode.source.owned_browser.cleanup_owned_browser(runtime)
                    if not uncertain: shutil.rmtree(runtime)
                except BaseException:
                    uncertain = True
                if uncertain:
                    self.retained_runtime = runtime
                    raise bootstrap.CleanupUnconfirmed() from None


class EpisodeManifestAttempt(episode.EpisodeAttempt):
    def __init__(self, output, transport=None, runner=episode.episode_command, metadata=None, manifest=None):
        super().__init__(output, transport, runner, metadata)
        self.manifest_transport = manifest or EpisodeManifestTransport(runner)

    def bound_episode(self, binding, nonce, metadata, expiry, gateway, media_token):
        self.phase = "EPISODE_MANIFEST"
        self.alive(expiry)
        receipt = self.manifest_transport.request(binding, nonce, metadata, expiry, gateway, media_token,
            min(self.deadline, self.source_deadline))
        self.alive(expiry)  # Includes browser/process cleanup time; no late adoption.
        fields = {key: value for key, value in receipt.items() if key not in {"nonce", "outcome", "cleanupConfirmed"}}
        self.report("EPISODE_MPD_RESULT", mpdOutcome=receipt["outcome"], **fields,
            initializationRequests=False, mediaRequests=False, adRequests=False, licenseRequests=False,
            certificateRequests=False, mediaKeySessions=False, entitlementEstablished=False)
        if receipt["outcome"] != "MPD_OBSERVED": raise Refused()


def main(arguments=None, output=None):
    arguments, output = sys.argv[1:] if arguments is None else arguments, sys.stdout if output is None else output
    if arguments != ["--run-episode-manifest"]:
        output.write('{"phase":"START","outcome":"OPT_IN_REQUIRED"}\n'); return 2
    if not shutil.which("docker") or not shutil.which("node"):
        output.write('{"phase":"START","outcome":"RUNTIME_UNAVAILABLE"}\n'); return 1
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    attempt, termination = EpisodeManifestAttempt(output), bootstrap.TerminationGuard()
    try:
        with termination: status = attempt.run()
    except bootstrap.Terminated: status = 1
    if termination.received:
        output.write(json.dumps({"phase": "CLEANUP", "outcome": "TERMINATED", "cleanupConfirmed": attempt.cleanup_confirmed, "grantsPersisted": False}) + "\n")
        return 1
    return status


if __name__ == "__main__": sys.exit(main())
