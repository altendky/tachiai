"""Private fresh-source preparation for the separate protected decoding probe."""

import importlib.util
import json
from pathlib import Path
import shutil
import sys
import tempfile

_spec = importlib.util.spec_from_file_location("web_abema_episode_eme", Path(__file__).with_name("abema-backend-episode-eme.py"))
eme = sys.modules.get(_spec.name)
if eme is None:
    eme = importlib.util.module_from_spec(_spec)
    sys.modules[_spec.name] = eme
    _spec.loader.exec_module(eme)
comparison, manifest, episode, bootstrap = eme.comparison, eme.manifest, eme.episode, eme.bootstrap

OUTCOMES = {"INPUT_REFUSED", "RUNTIME_REFUSED", "PREPARE_REFUSED", "PLAY_REFUSED", "PLAYBACK_REFUSED",
            "DECODE_OBSERVED", "LIFETIME_REFUSED", "CANCELLED", "CLEANUP_REFUSED"}
STAGES = {"INPUT", "RUNTIME", "PREPARE", "PLAY", "OBSERVE", "CLOSE"}
COUNTS = {"manifest", "initialization", "segment", "deniedDrm"}
CDM_ORDER = ("accesses", "mediaKeys", "sessions", "messages", "initialRequests", "responses", "updates")
CDM_COUNTS = set(CDM_ORDER)
CDM_FLAGS = {"updateAccepted", "sessionClosed", "violation"}
EVIDENCE = {"stage", "boundary", "http", "publicationStage", "auxiliaryReason", "utcScheme", "sdkCode", "frames",
            "advancedMs", "width", "height", "counts", "licenseRequests", "licenseResponses", "licenseStatus", "licenseTransformed", "cdm"}


def bounded(value, maximum):
    return type(value) is int and 0 <= value <= maximum


def validate_evidence(value):
    if (not isinstance(value, dict) or set(value) != EVIDENCE
            or any(type(value[key]) is not str for key in ("stage", "boundary", "publicationStage"))
            or any(value[key] is not None and type(value[key]) is not str for key in ("auxiliaryReason", "utcScheme"))
            or value["stage"] not in {"idle", "prepare", "ready", "play", "cleanup"}
            or value["boundary"] not in {"NONE", "EXPIRED", "CANCELLED", "MANIFEST", "PROTECTION", "AUXILIARY", "EARLY_INDEX", "UNDECLARED", "RESOURCE", "LIMIT", "HTTP", "NETWORK"}
            or value["publicationStage"] not in {"IDLE", "STREAMS", "INDEX", "REFERENCES", "FILTER", "PUBLISH"}
            or value["auxiliaryReason"] not in {None, "UTC_TIMING", "SEGMENT_BASE", "CONTENT_STEERING", "PATCH_LOCATION", "XLINK", "XINCLUDE", "CHANGED_LOCATION", "TEMPLATE_INDEX", "REQUEST_TYPE", "DISPATCH_TYPE"}
            or value["utcScheme"] not in {None, "DIRECT", "HTTP_HEAD", "HTTP_XSDATE", "OTHER"}
            or any(value[key] is not None and (type(value[key]) is not int or not 100 <= value[key] <= 599) for key in ("http", "licenseStatus"))
            or value["sdkCode"] is not None and (not bounded(value["sdkCode"], 99999) or value["sdkCode"] == 0)
            or not bounded(value["frames"], 1000000) or not bounded(value["advancedMs"], 120000)
            or not bounded(value["width"], 16384) or not bounded(value["height"], 16384)
            or not isinstance(value["counts"], dict) or set(value["counts"]) != COUNTS
            or any(not bounded(value["counts"][key], 512) for key in COUNTS)
            or not bounded(value["licenseRequests"], 1) or not bounded(value["licenseResponses"], 1)
            or type(value["licenseTransformed"]) is not bool
            or not isinstance(value["cdm"], dict) or set(value["cdm"]) != CDM_COUNTS | CDM_FLAGS
            or any(not bounded(value["cdm"][key], 1) for key in CDM_COUNTS)
            or any(type(value["cdm"][key]) is not bool for key in CDM_FLAGS)):
        raise bootstrap.Refused()
    if (value["frames"] > 0 and (not value["width"] or not value["height"])
            or value["advancedMs"] > 0 and not value["frames"]
            or any(value["cdm"][later] > value["cdm"][earlier] for earlier, later in zip(CDM_ORDER, CDM_ORDER[1:]))
            or value["cdm"]["sessionClosed"] and value["cdm"]["sessions"] != 1
            or value["cdm"]["updateAccepted"] and value["cdm"]["updates"] != 1
            or value["licenseResponses"] > value["licenseRequests"]
            or value["licenseTransformed"] != (value["licenseResponses"] == 1)
            or value["licenseStatus"] is not None and value["licenseRequests"] != 1):
        raise bootstrap.Refused()
    return value


def validate_receipt(value, nonce):
    if (not isinstance(value, dict) or set(value) != {"nonce", "outcome", "stage", "before", "after", "explicitClose", "cleanupConfirmed", "chromeVersion"}
            or value["nonce"] != nonce or type(value["outcome"]) is not str or value["outcome"] not in OUTCOMES
            or type(value["stage"]) is not str or value["stage"] not in STAGES
            or type(value["explicitClose"]) is not str or value["explicitClose"] not in {"NOT_ATTEMPTED", "CONFIRMED", "UNCONFIRMED"}
            or type(value["cleanupConfirmed"]) is not bool
            or value["chromeVersion"] is not None and (not isinstance(value["chromeVersion"], str)
                or not eme.re.fullmatch(r"156\.[0-9.]{1,32}", value["chromeVersion"]))):
        raise bootstrap.Refused()
    before, after = value["before"], value["after"]
    for evidence in (before, after):
        if evidence is not None:
            validate_evidence(evidence)
    if (value["stage"] in {"INPUT", "RUNTIME"} and (before is not None or after is not None or value["explicitClose"] != "NOT_ATTEMPTED")
            or value["cleanupConfirmed"] and (before is not None or after is not None) and value["explicitClose"] != "CONFIRMED"
            or not value["cleanupConfirmed"] and value["outcome"] != "CLEANUP_REFUSED"):
        raise bootstrap.Refused()
    if (value["explicitClose"] == "CONFIRMED" and (after is None or after["stage"] != "cleanup"
            or after["cdm"]["sessionClosed"] != (after["cdm"]["sessions"] == 1))
            or value["stage"] in {"PREPARE", "PLAY", "OBSERVE"} and value["explicitClose"] != "CONFIRMED"
            or value["explicitClose"] == "UNCONFIRMED" and value["cleanupConfirmed"]
            or value["outcome"] == "CLEANUP_REFUSED" and (value["stage"] != "CLOSE" or value["cleanupConfirmed"])
            or value["stage"] == "CLOSE" and value["outcome"] != "CLEANUP_REFUSED"
            or value["stage"] == "INPUT" and value["outcome"] != "INPUT_REFUSED"
            or value["stage"] == "RUNTIME" and value["outcome"] != "RUNTIME_REFUSED"
            or value["stage"] == "PREPARE" and value["outcome"] not in {"PREPARE_REFUSED", "LIFETIME_REFUSED", "CANCELLED"}
            or value["stage"] == "PLAY" and value["outcome"] not in {"PLAY_REFUSED", "LIFETIME_REFUSED", "CANCELLED"}
            or value["stage"] == "OBSERVE" and value["outcome"] not in {"PLAYBACK_REFUSED", "DECODE_OBSERVED", "LIFETIME_REFUSED", "CANCELLED"}):
        raise bootstrap.Refused()
    if before is not None and after is not None and (
            after["frames"] < before["frames"] or after["advancedMs"] < before["advancedMs"]
            or any(after["counts"][key] < before["counts"][key] for key in COUNTS)
            or any(after["cdm"][key] < before["cdm"][key] for key in CDM_COUNTS)
            or after["licenseRequests"] < before["licenseRequests"] or after["licenseResponses"] < before["licenseResponses"]):
        raise bootstrap.Refused()
    if value["outcome"] == "DECODE_OBSERVED" and (
            value["stage"] != "OBSERVE" or value["explicitClose"] != "CONFIRMED" or before is None
            or before["stage"] != "play" or before["boundary"] != "NONE" or before["sdkCode"] is not None
            or before["frames"] < 1 or before["advancedMs"] < 5000
            or before["counts"]["manifest"] < 1 or before["counts"]["initialization"] < 1 or before["counts"]["segment"] < 1 or before["counts"]["deniedDrm"] != 0
            or before["licenseRequests"] != 1 or before["licenseResponses"] != 1 or before["licenseStatus"] != 200
            or not before["licenseTransformed"] or not before["cdm"]["updateAccepted"] or before["cdm"]["sessionClosed"] or before["cdm"]["violation"]
            or any(before["cdm"][key] != 1 for key in CDM_COUNTS) or not value["cleanupConfirmed"]
            or not after["cdm"]["sessionClosed"] or after["cdm"]["violation"]
            or after["boundary"] != "CANCELLED" or after["sdkCode"] is not None):
        raise bootstrap.Refused()
    return value


class EpisodePlaybackAttempt(eme.EpisodeEmeAttempt):
    """Fresh source/user lifecycle is shared; only this explicit sink decodes."""

    def bound_episode(self, binding, nonce, metadata, expiry, gateway, media_token):
        self.phase = "EPISODE_PLAYBACK"
        self.alive(expiry)
        if (self.eme_used or self.public_runtime is None or self.private_user is None
                or binding["drm"] != "CUSTOM" or binding["adMode"] != "CSAI"):
            raise bootstrap.Refused()
        self.eme_used = True
        runtime = Path(tempfile.mkdtemp(prefix="k-"))
        uncertain, private = False, None
        try:
            episode.source.owned_browser.validate_runtime_path(runtime)
            public = runtime / "public"
            public.mkdir(mode=0o700)
            for name, _, size, digest in bootstrap.ASSETS:
                self.alive(expiry)
                raw = (self.public_runtime / name).read_bytes()
                if len(raw) != size or bootstrap.hashlib.sha256(raw).hexdigest() != digest:
                    raise bootstrap.Refused()
                (public / name).write_bytes(raw)
                (public / name).chmod(0o444)
            self.phase = "EME_ASSET"
            name, _, size, digest = bootstrap.EME_ASSET
            raw = self.request("EME_ASSET", {"name": name})
            self.alive(expiry)
            if len(raw) != size or bootstrap.hashlib.sha256(raw).hexdigest() != digest:
                raise bootstrap.Refused()
            (public / name).write_bytes(raw)
            (public / name).chmod(0o444)
            raw = None
            self.phase = "EPISODE_PLAYBACK"
            packet = manifest.private_input(binding, nonce, metadata, expiry, gateway, media_token,
                                            min(self.deadline, self.source_deadline))
            private = json.dumps({"request": packet, "route": self.route.private_packet(),
                                  "userId": self.private_user, "licenseUri": binding["licenseUri"]},
                                 ensure_ascii=True, separators=(",", ":")).encode("ascii")
            if len(private) > 32768:
                raise bootstrap.Refused()
            remaining = min(self.deadline, self.source_deadline) - bootstrap.time.monotonic()
            if remaining <= 0:
                raise bootstrap.Refused()
            uncertain = True  # Process cleanup alone cannot establish CDM close.
            result = self.runner([shutil.which("node"), str(Path(__file__).with_name("abema-browser-episode-playback.mjs")),
                                 "--owned-runtime", str(runtime)], min(45, remaining), 8192,
                                 input_data=private, display=True)
            private = None
            try:
                receipt = bootstrap.strict_json(result.output, 8192)
                result.output = b""
                if result.code != 0 or result.stderr:
                    raise bootstrap.Refused()
                validate_receipt(receipt, nonce)
            except BaseException:
                raise bootstrap.CleanupUnconfirmed() from None
            uncertain = not receipt["cleanupConfirmed"]
            self.report("BROWSER_PLAYBACK_RESULT", playbackOutcome=receipt["outcome"],
                        **{key: value for key, value in receipt.items() if key not in {"nonce", "outcome", "cleanupConfirmed"}},
                        adRequests=False, certificateRequests=False, entitlementEstablished=False, acousticObservation=False)
            if not receipt["cleanupConfirmed"]:
                uncertain = True
                raise bootstrap.CleanupUnconfirmed()
            self.alive(expiry)
            if receipt["outcome"] != "DECODE_OBSERVED":
                raise bootstrap.Refused()
        finally:
            private = None
            with bootstrap.completing_cleanup():
                try:
                    episode.source.owned_browser.cleanup_owned_browser(runtime)
                except BaseException:
                    uncertain = True
                if not uncertain:
                    try:
                        shutil.rmtree(runtime)
                    except BaseException:
                        uncertain = True
                if uncertain:
                    self.cleanup_confirmed = False
                    self.retained_eme_runtime = runtime
                    raise bootstrap.CleanupUnconfirmed() from None
