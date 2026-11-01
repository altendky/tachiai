"""Private preparation for the separate one-initial-exchange browser EME probe."""

from contextlib import contextmanager
import importlib.util
import json
from pathlib import Path
import re
import shutil
import sys
import tempfile

_spec = importlib.util.spec_from_file_location("web_abema_route_comparison", Path(__file__).with_name("abema-route-comparison.py"))
comparison = sys.modules.get(_spec.name)
if comparison is None:
    comparison = importlib.util.module_from_spec(_spec)
    sys.modules[_spec.name] = comparison
    _spec.loader.exec_module(comparison)
manifest, episode, bootstrap = comparison.manifest, comparison.episode, comparison.bootstrap

OUTCOMES = {"INPUT_REFUSED", "RUNTIME_REFUSED", "MANIFEST_REFUSED", "INIT_DATA_REFUSED", "EME_REFUSED",
            "LICENSE_REFUSED", "RESPONSE_REFUSED", "UPDATE_REFUSED", "LIFETIME_REFUSED", "CANCELLED",
            "UPDATE_ACCEPTED", "CLEANUP_REFUSED"}
STAGES = {"INPUT", "RUNTIME", "MANIFEST", "INIT_DATA", "CREATE_SESSION", "GENERATE_REQUEST",
          "LICENSE", "TRANSFORM", "UPDATE", "CLOSE"}
FLAGS = {"manifestReadable", "licenseReadable", "sessionCreated", "requestGenerated", "licenseDispatched",
         "updateAccepted", "sessionClosed", "cleanupConfirmed"}
OUTCOME_STAGES = {"INPUT_REFUSED": {"INPUT"}, "RUNTIME_REFUSED": {"RUNTIME"}, "MANIFEST_REFUSED": {"MANIFEST"},
    "INIT_DATA_REFUSED": {"INIT_DATA"}, "EME_REFUSED": {"CREATE_SESSION", "GENERATE_REQUEST", "LICENSE", "TRANSFORM", "UPDATE"},
    "LICENSE_REFUSED": {"LICENSE"}, "RESPONSE_REFUSED": {"TRANSFORM"}, "UPDATE_REFUSED": {"UPDATE"},
    "LIFETIME_REFUSED": STAGES - {"CLOSE"}, "CANCELLED": STAGES - {"CLOSE"},
    "UPDATE_ACCEPTED": {"UPDATE"}, "CLEANUP_REFUSED": {"CLOSE"}}


def validate_receipt(value, nonce):
    if (not isinstance(value, dict) or set(value) != FLAGS | {"nonce", "outcome", "stage", "manifestStatus", "licenseStatus"}
            or value["nonce"] != nonce or type(value["outcome"]) is not str or value["outcome"] not in OUTCOMES
            or type(value["stage"]) is not str or value["stage"] not in STAGES
            or any(type(value[key]) is not bool for key in FLAGS) or value["cleanupConfirmed"] is not True):
        raise bootstrap.Refused()
    for prefix in ("manifest", "license"):
        status = value[prefix + "Status"]
        if value[prefix + "Readable"]:
            if type(status) is not int or not 100 <= status <= 599:
                raise bootstrap.Refused()
        elif status is not None:
            raise bootstrap.Refused()
    stage = value["stage"]
    if (stage not in OUTCOME_STAGES[value["outcome"]]
            or stage in {"INPUT", "RUNTIME"} and (value["manifestReadable"] or value["licenseReadable"] or value["sessionCreated"])
            or stage in {"INPUT", "RUNTIME", "MANIFEST", "INIT_DATA", "CREATE_SESSION"} and value["sessionCreated"]
            or stage not in {"LICENSE", "TRANSFORM", "UPDATE"} and value["licenseDispatched"]
            or stage != "UPDATE" and value["updateAccepted"]
            or stage in {"INIT_DATA", "CREATE_SESSION", "GENERATE_REQUEST", "LICENSE", "TRANSFORM", "UPDATE"}
            and not (value["manifestReadable"] and value["manifestStatus"] == 200)
            or stage in {"GENERATE_REQUEST", "LICENSE", "TRANSFORM", "UPDATE"} and not value["sessionCreated"]
            or stage in {"LICENSE", "TRANSFORM", "UPDATE"} and not value["requestGenerated"]
            or stage in {"TRANSFORM", "UPDATE"} and not (value["licenseReadable"] and value["licenseStatus"] == 200)):
        raise bootstrap.Refused()
    if (value["sessionClosed"] != value["sessionCreated"]
            or value["sessionCreated"] and not (value["manifestReadable"] and value["manifestStatus"] == 200)
            or value["requestGenerated"] and not value["sessionCreated"]
            or value["licenseDispatched"] and not value["requestGenerated"]
            or value["licenseReadable"] and not value["licenseDispatched"]
            or value["updateAccepted"] and not (value["licenseReadable"] and value["licenseStatus"] == 200)
            or value["outcome"] == "UPDATE_ACCEPTED" and (not value["updateAccepted"] or value["stage"] != "UPDATE")
            or value["updateAccepted"] and value["outcome"] not in {"UPDATE_ACCEPTED", "LIFETIME_REFUSED", "CANCELLED", "EME_REFUSED"}
            or value["outcome"] == "CLEANUP_REFUSED"):
        raise bootstrap.Refused()
    return value


class EpisodeEmeAttempt(episode.EpisodeAttempt):
    """The existing fresh source binding ends in one owned routed EME page."""

    def __init__(self, output, route, runner=episode.episode_command, transport=None, metadata=None):
        self.route = bootstrap.route_transport.validate_route(route)
        if self.route is None:
            raise bootstrap.Refused()
        super().__init__(output, transport=transport or bootstrap.NativeTransport(proxy_route=self.route),
                         runner=runner, metadata=metadata or episode.EpisodeTransport(proxy_route=self.route))
        self.private_user = self.public_runtime = None
        self.eme_used = False
        self.retained_eme_runtime = None

    def complete_private(self, context):
        # Only the fresh guest identity needed by the unchanged response helper
        # crosses into the owned page. Bearer/device IDs stay with their owners.
        user = context.get("userId")
        if not isinstance(user, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,256}", user):
            raise bootstrap.Refused()
        self.private_user = user
        try:
            return super().complete_private(context)
        finally:
            self.private_user = None

    @contextmanager
    def runtime(self):
        with super().runtime() as (runtime, config):
            self.public_runtime = runtime
            try:
                yield runtime, config
            finally:
                self.public_runtime = None

    def bound_episode(self, binding, nonce, metadata, expiry, gateway, media_token):
        self.phase = "EPISODE_EME"
        self.alive(expiry)
        if self.eme_used or self.public_runtime is None or self.private_user is None or binding["drm"] != "CUSTOM":
            raise bootstrap.Refused()
        self.eme_used = True
        private = None
        runtime = Path(tempfile.mkdtemp(prefix="k-"))
        uncertain = False
        try:
            episode.source.owned_browser.validate_runtime_path(runtime)
            public = runtime / "public"
            public.mkdir(mode=0o700)
            # These are public bundles only, already downloaded for the source
            # proof. No grant, manifest, challenge or response is written here.
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
            self.phase = "EPISODE_EME"
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
            # Once launch is attempted, process-group cleanup alone cannot
            # establish the required explicit CDM-session closure verdict.
            uncertain = True
            result = self.runner([shutil.which("node"), str(Path(__file__).with_name("abema-browser-episode-eme.mjs")),
                                 "--owned-runtime", str(runtime)], min(30, remaining), 8192,
                                 input_data=private, display=True)
            private = None
            try:
                receipt = bootstrap.strict_json(result.output, 8192)
            except BaseException:
                uncertain = True
                raise bootstrap.CleanupUnconfirmed() from None
            result.output = b""
            if not isinstance(receipt, dict) or receipt.get("cleanupConfirmed") is not True:
                uncertain = True
                raise bootstrap.CleanupUnconfirmed()
            if result.code != 0 or result.stderr:
                raise bootstrap.Refused()
            validate_receipt(receipt, nonce)
            uncertain = False
            self.report("BROWSER_EME_RESULT", emeOutcome=receipt["outcome"], **{key: value for key, value in receipt.items()
                                               if key not in {"nonce", "outcome", "cleanupConfirmed"}},
                        initializationRequests=False, mediaRequests=False, adRequests=False,
                        certificateRequests=False, entitlementEstablished=False)
            self.alive(expiry)  # A late response remains evidence, never success.
            if receipt["outcome"] != "UPDATE_ACCEPTED":
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
