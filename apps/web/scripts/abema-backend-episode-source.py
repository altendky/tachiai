#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Opt-in legacy-seed episode SOURCE/CONFIG proof; no media or DRM operations."""
import base64
from contextlib import contextmanager
import importlib.util
import json
import multiprocessing
import os
from pathlib import Path
import re
import resource
import shutil
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import uuid

_spec = importlib.util.spec_from_file_location("web_abema_source", Path(__file__).with_name("abema-backend-source.py"))
source = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = source
_spec.loader.exec_module(source)
bootstrap, Refused = source.bootstrap, source.Refused
EPISODE = "394-72_s10_p8529"  # Code-defined legacy seed, never a saved-selection claim.
GATEWAY = "https://streaming-api-cf.p-c2-x.abema-tv.com/v1/playbackResources/"
LIMIT = 512 * 1024
REASONS = {"SCHEMA", "IDENTITY", "FREE", "WINDOW", "RESTRICTION", "DTO", "GATEWAY", "SELECTION",
           "SOURCE_MODE", "SOURCE_URI", "CONFIGURATION", "BINDING", "CALL_CAP", "LIFETIME", "RUNTIME"}
HELPER_CONFIGURATION_CHECKS = {"HELPER_FACTORY", "HELPER_KEY_SYSTEM", "HELPER_LICENSE_TYPE", "HELPER_SOURCE_TYPE", "HELPER_CALL_CAP", "HELPER_OUTPUT"}
CONFIGURATION_CHECKS = HELPER_CONFIGURATION_CHECKS | {"PARENT_PROTECTION", "PARENT_AD_MODE", "PARENT_CALL_CAP",
    "PARENT_PROTECTION_ASSOCIATION", "PARENT_AD_MODE_ASSOCIATION", "PARENT_LICENSE_ENDPOINT", "PARENT_LICENSE_TEMPLATE", "PARENT_LICENSE_QUERY",
    "PARENT_TEMPLATE_TEXT", "PARENT_RESULT_TEXT", "PARENT_TEMPLATE_SCHEME", "PARENT_RESULT_SCHEME",
    "PARENT_TEMPLATE_HOST", "PARENT_RESULT_HOST", "PARENT_TEMPLATE_PATH", "PARENT_RESULT_PATH", "PARENT_PATH_BINDING"}
# Public system identifiers from the same pinned provider mapping, not keys.
DRM_SYSTEMS = {"WIDEVINE": "edef8ba9-79d6-4ace-a3c8-27dcd51d21ed", "CUSTOM": "81376844-f976-481e-a84e-cc25d39b0b33"}
AD_MODES = {0: "NONE", 1: "ABEMA_DEFAULT", 4: "CSAI", "AD_INSERTION_MODE_NONE": "NONE",
            "AD_INSERTION_MODE_ABEMA_DEFAULT": "ABEMA_DEFAULT", "AD_INSERTION_MODE_CSAI": "CSAI"}


class EpisodeRefused(Refused):
    def __init__(self, reason, configuration_check=None):
        if type(reason) is not str or reason not in REASONS: raise Refused()
        if configuration_check is not None and (reason != "CONFIGURATION" or type(configuration_check) is not str or configuration_check not in CONFIGURATION_CHECKS): raise Refused()
        self.reason = reason
        self.configuration_check = configuration_check


def refuse(reason, configuration_check=None): raise EpisodeRefused(reason, configuration_check)


def arin_value(value):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_:.~-]{1,256}", value) or value in {".", ".."}: refuse("IDENTITY")
    return value


def eligible_episode(raw, now_ms):
    try: value = bootstrap.strict_json(raw, LIMIT, max_nodes=16000, max_depth=32)
    except (ValueError, Refused, UnicodeError, RecursionError): refuse("SCHEMA")
    if not isinstance(value, dict) or value.get("id") != EPISODE: refuse("IDENTITY")
    if not isinstance(value.get("playback"), dict): refuse("IDENTITY")
    arin_value(value["playback"].get("arin"))
    if not isinstance(value.get("label"), dict) or value["label"].get("free") is not True: refuse("FREE")
    status = value.get("mediaStatus")
    if status is not None and (not isinstance(status, dict) or "drm" in status and type(status["drm"]) is not bool or
        "downloadOnlyDrm" in status and status["downloadOnlyDrm"] is not False): refuse("RESTRICTION")
    if any(value.get(key) is not None for key in ("rentalInfo", "precedenceTerm", "deviceRestriction")): refuse("RESTRICTION")
    trial = value.get("trialWatching")
    if trial is not None and (not isinstance(trial, dict) or set(trial) - {"enabled"} or
                              "enabled" in trial and trial["enabled"] is not False): refuse("RESTRICTION")
    for key, fields, extras in (("partnerService", {"id", "name", "logoUrl"}, set()),
                               ("externalProvider", {"originalId", "originalSeriesId", "originalSeasonId", "originalProgramId"}, {"type"})):
        item = value.get(key)
        if item is not None and (not isinstance(item, dict) or set(item) - (fields | extras) or
            any(item.get(field, "") != "" for field in fields) or
            key == "externalProvider" and not (type(item.get("type", 0)) is int and item.get("type", 0) == 0 or item.get("type") == "EXTERNAL_PROVIDER_TYPE_UNKNOWN")): refuse("RESTRICTION")
    external = value.get("externalContent")
    if external is not None:
        if not isinstance(external, dict) or set(external) - {"link", "linkText", "buttonText", "marks"}: refuse("RESTRICTION")
        for key, maximum in (("link", 2048), ("linkText", 512), ("buttonText", 512)):
            if key in external and (not isinstance(external[key], str) or len(external[key]) > maximum): refuse("RESTRICTION")
        marks = external.get("marks")
        if marks is not None and (not isinstance(marks, dict) or set(marks) - {"gambling"} or
                                  "gambling" in marks and marks["gambling"] is not False): refuse("RESTRICTION")
    if "partnerContentViewingAuthorities" in value and value["partnerContentViewingAuthorities"] != []: refuse("RESTRICTION")
    terms = value.get("terms")
    if not isinstance(terms, list) or not 1 <= len(terms) <= 64: refuse("WINDOW")
    ends = [value.get("freeEndAt")]
    for term in terms:
        if not isinstance(term, dict) or set(term) != {"onDemandType", "endAt"} or term["onDemandType"] not in (3, "VIDEO_ON_DEMAND_TYPE_ADVERTISING"): refuse("WINDOW")
        ends.append(term["endAt"])
    if any(type(end) is not int or not 0 < end < 100000000000 or end * 1000 <= now_ms for end in ends): refuse("WINDOW")
    return value, min(ends) * 1000


def request_spec(operation, fields):
    headers = {"Accept": "application/json", "Accept-Encoding": "identity"}
    if operation == "EPISODE" and set(fields) == {"bearer"} and bootstrap.bounded_token(fields["bearer"]):
        headers["Authorization"] = "Bearer " + fields["bearer"]
        uri = "https://api.p-c3-e.abema-tv.com/v1/video/programs/" + EPISODE
    elif operation == "GATEWAY" and set(fields) == {"arin"}:
        uri = GATEWAY + arin_value(fields["arin"])
    else: refuse("CALL_CAP")
    return urllib.request.Request(uri, method="GET", headers=headers)


def exchange(operation, fields, opener):
    request = request_spec(operation, fields)
    try: response = opener.open(request, timeout=10)
    except urllib.error.HTTPError as error: response = error
    with response:
        status = response.status
        if type(status) is not int or not 100 <= status <= 599: refuse("SCHEMA")
        if status != 200: return status, b""
        if response.headers.get("Content-Type", "").split(";", 1)[0].strip().lower() != "application/json" or response.headers.get("Content-Encoding", "identity").lower() != "identity": refuse("SCHEMA")
        length = response.headers.get("Content-Length")
        if length is not None and (not length.isascii() or not length.isdecimal() or int(length) > LIMIT): refuse("SCHEMA")
        chunks, size = [], 0
        while True:
            chunk = response.read1(min(8192, LIMIT + 1 - size))
            if not chunk: break
            size += len(chunk)
            if size > LIMIT: refuse("SCHEMA")
            chunks.append(chunk)
        body = b"".join(chunks)
        if length is not None and len(body) != int(length): refuse("SCHEMA")
        return status, body


def episode_worker(sender, operation, fields):
    try:
        with open(os.devnull, "wb") as silent: os.dup2(silent.fileno(), 1); os.dup2(silent.fileno(), 2)
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), bootstrap.NoRedirect())
        status, body = exchange(operation, fields, opener)
        sender.send_bytes(json.dumps({"status": status, "body": base64.b64encode(body).decode("ascii")}).encode())
    except BaseException:
        try: sender.send_bytes(b'{"refused":true}')
        except BaseException: pass
    finally: sender.close()


class EpisodeTransport:
    def __init__(self, context=None):
        self.context, self.used = context or multiprocessing.get_context("spawn"), set()

    def request(self, operation, fields, timeout):
        request_spec(operation, fields)
        if operation in self.used or timeout <= 0: refuse("CALL_CAP")
        self.used.add(operation)
        receiver, sender = self.context.Pipe(duplex=False)
        process = self.context.Process(target=episode_worker, args=(sender, operation, fields), daemon=True)
        started = False
        try:
            with bootstrap.completing_cleanup(): process.start(); started = True; sender.close()
            if not receiver.poll(min(10, timeout)): refuse("LIFETIME")
            value = bootstrap.strict_json(receiver.recv_bytes(bootstrap.IPC_LIMIT), bootstrap.IPC_LIMIT)
            if not isinstance(value, dict) or set(value) != {"status", "body"} or type(value["status"]) is not int or not 100 <= value["status"] <= 599 or not isinstance(value["body"], str): refuse("SCHEMA")
            body = base64.b64decode(value["body"], validate=True)
            if len(body) > LIMIT or value["status"] != 200 and body: refuse("SCHEMA")
            return value["status"], body
        finally:
            with bootstrap.completing_cleanup():
                sender.close(); receiver.close()
                if started:
                    process.join(0.2)
                    if process.is_alive(): process.terminate(); process.join(2)
                    if process.is_alive(): process.kill(); process.join(2)
                    if process.is_alive(): raise bootstrap.CleanupUnconfirmed()
                process.close()


def media_uri(value):
    if not isinstance(value, str) or not 1 <= len(value) <= 2048 or re.search(r"[\x00-\x20\\#]", value): refuse("SOURCE_URI")
    uri = urllib.parse.urlsplit(value)
    if uri.scheme != "https" or uri.username is not None or uri.password is not None or uri.port is not None or uri.netloc != uri.hostname or not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,54}-abematv\.akamaized\.net", uri.hostname or "") or not uri.path.endswith(".mpd") or not re.fullmatch(r"[A-Za-z0-9_./~$-]+", uri.path) or any(part in {".", ".."} for part in uri.path.split("/")) or not any(re.fullmatch(re.escape(EPISODE) + r"(?:\.[A-Za-z0-9_-]+)?", part) for part in uri.path.split("/")): refuse("SOURCE_URI")
    return uri


def license_query_expression(value):
    """Recognize one terminal RFC6570 scalar query expression, not an evaluator."""
    match = re.search(r"\{([?&])([^{}]*)\}$", value)
    if match is None:
        if "{?" in value or "{&" in value: refuse("CONFIGURATION", "PARENT_LICENSE_TEMPLATE")
        return value, ()
    prefix, operator, variables = value[:match.start()], match[1], match[2].split(",")
    if not 1 <= len(variables) <= 4 or len(set(variables)) != len(variables) or set(variables) - {"t", "pt", "ct", "cid"} or "{?" in prefix or "{&" in prefix:
        refuse("CONFIGURATION", "PARENT_LICENSE_TEMPLATE")
    # A new query cannot follow a literal query. Continuation must extend a
    # nonempty literal query; its individual pairs are validated separately.
    if operator == "?" and "?" in prefix or operator == "&" and ("?" not in prefix or not prefix.split("?", 1)[1] or prefix.endswith("&")):
        refuse("CONFIGURATION", "PARENT_LICENSE_TEMPLATE")
    return prefix, tuple(variables)


def validate_binding(value, phase, nonce, metadata, expiry, gateway, media_token):
    if phase not in {"metadata", "selection"}: refuse("BINDING")
    common = {"nonce", "episodeId", "arin", "expiresAtMs", "browser"}
    selected = {"uri", "licenseUri", "streamIndex", "manifestIndex", "drm", "keySystem", "adMode", "gatewayCalls"}
    if not isinstance(value, dict) or set(value) != common | (selected if phase == "selection" else set()) or value["nonce"] != nonce or value["episodeId"] != EPISODE or value["arin"] != metadata["playback"]["arin"] or type(value["expiresAtMs"]) is not int or value["expiresAtMs"] != expiry or value["browser"] != {"name": "Chrome", "major": "156", "os": "Linux", "engine": "Blink"}: refuse("BINDING")
    if phase == "metadata": return
    if type(value["drm"]) is not str or value["drm"] not in {"WIDEVINE", "CUSTOM"} or value["keySystem"] != value["drm"]: refuse("CONFIGURATION", "PARENT_PROTECTION")
    if type(value["adMode"]) is not str or value["adMode"] not in {"NONE", "CSAI", "ABEMA_DEFAULT"}: refuse("CONFIGURATION", "PARENT_AD_MODE")
    if type(value["gatewayCalls"]) is not int or value["gatewayCalls"] != 1: refuse("CONFIGURATION", "PARENT_CALL_CAP")
    try:
        streams = gateway["streams"]
        index, position = value["streamIndex"], value["manifestIndex"]
        if type(index) is not int or type(position) is not int or not 0 <= index < len(streams) or not 0 <= position < len(streams[index]["manifests"]): refuse("BINDING")
        stream = streams[index]; manifest = stream["manifests"][position]
        mode = manifest["adInsertion"]["mode"]
        if stream["drm"]["systemId"] != DRM_SYSTEMS[value["drm"]]: refuse("CONFIGURATION", "PARENT_PROTECTION_ASSOCIATION")
        if type(mode) not in {int, str} or AD_MODES.get(mode) != value["adMode"]: refuse("CONFIGURATION", "PARENT_AD_MODE_ASSOCIATION")
        if sum(item.get("arin") == manifest["arin"] for candidate in streams for item in candidate.get("manifests", [])) != 1: refuse("BINDING")
        advertised = media_uri(manifest["url"])
        uri = media_uri(value["uri"])
        base_query, query = source.playlist_query(advertised.query), source.playlist_query(uri.query)
        reserved = {"t", "enc", "dtid"}
        if reserved & base_query.keys() or uri[:3] != advertised[:3] or query != {**base_query, "t": media_token, "enc": "wv" if value["drm"] == "WIDEVINE" else "clear"}: refuse("BINDING")
        for role, item in (("TEMPLATE", stream["drm"]["license"]["urlTemplate"]), ("RESULT", value["licenseUri"])):
            if not isinstance(item, str) or len(item) > 4096 or re.search(r"[\x00-\x20\\#]", item): refuse("CONFIGURATION", "PARENT_" + role + "_TEXT")
        template_text, query_variables = license_query_expression(stream["drm"]["license"]["urlTemplate"])
        template = urllib.parse.urlsplit(template_text)
        license_uri = urllib.parse.urlsplit(value["licenseUri"])
        for role, item in (("TEMPLATE", template), ("RESULT", license_uri)):
            if item.scheme != "https": refuse("CONFIGURATION", "PARENT_" + role + "_SCHEME")
            if item.netloc != "license.p-c3-e.abema-tv.com": refuse("CONFIGURATION", "PARENT_" + role + "_HOST")
            # Expand only the two nonsecret, exact source-context markers. No
            # generic URI-template evaluation or token-bearing path is admitted.
            path = item.path.replace("{ct}", "program").replace("{cid}", EPISODE) if role == "TEMPLATE" else item.path
            if item.fragment or not re.fullmatch(r"/[A-Za-z0-9_./~-]{1,255}", path) or any(part in {".", ".."} for part in path.split("/")): refuse("CONFIGURATION", "PARENT_" + role + "_PATH")
        expected_path = template.path.replace("{ct}", "program").replace("{cid}", EPISODE)
        if license_uri.path != expected_path: refuse("CONFIGURATION", "PARENT_PATH_BINDING")
        template_query, license_query = source.playlist_query(template.query), source.playlist_query(license_uri.query)
        if template_query.keys() & set(query_variables): refuse("CONFIGURATION", "PARENT_LICENSE_TEMPLATE")
        # The current caller supplies no ticket token. Only an expression's
        # undefined pt may be omitted; a literal pt pair is never adopted.
        template_query.update({key: "{" + key + "}" for key in query_variables if key != "pt"})
        replacements = {"t": media_token, "ct": "program", "cid": EPISODE}
        path_context = {key for key in ("ct", "cid") if "{" + key + "}" in template.path}
        required_query = set(replacements) - path_context
        if set(template_query) - {"t", "ct", "cid", "tt", "tk", "dt"} or any(template_query.get(key) != "{" + key + "}" for key in required_query) or any(template_query[key] != "{" + key + "}" for key in path_context & template_query.keys()) or any("{" in item or "}" in item for key, item in template_query.items() if key not in replacements): refuse("CONFIGURATION", "PARENT_LICENSE_TEMPLATE")
        if license_query != {key: replacements.get(key, item) for key, item in template_query.items()}: refuse("CONFIGURATION", "PARENT_LICENSE_QUERY")
    except EpisodeRefused: raise
    except (KeyError, TypeError, ValueError, IndexError, Refused): refuse("BINDING")


def episode_command(args, timeout, limit=32768, input_data=None, **options):
    # Only this new proof needs the complete bounded metadata + gateway envelope.
    return bootstrap.run_command(args, timeout, limit, input_data=input_data,
                                 input_limit=2 * 1024 * 1024, **options)


class EpisodeAttempt(source.SourceAttempt):
    private_completion = True

    def __init__(self, output, transport=None, runner=episode_command, metadata=None):
        super().__init__(output, transport, runner)
        self.metadata = metadata or EpisodeTransport()
        self.retained_episode_runtime = None
        self.source_deadline = None
        self.source_expiry = None

    def bind_expiry(self, expiry, request_wall_ms, request_monotonic):
        if self.source_deadline is not None: refuse("LIFETIME")
        self.source_expiry = expiry
        self.source_deadline = min(self.deadline, request_monotonic + max(0, expiry - request_wall_ms) / 1000)
        self.alive(expiry)

    def alive(self, expiry=None):
        expiry = min(expiry, self.source_expiry) if expiry is not None and self.source_expiry is not None else self.source_expiry if expiry is None else expiry
        if bootstrap.time.monotonic() >= min(self.deadline, self.source_deadline if self.source_deadline is not None else self.deadline) or expiry is not None and int(bootstrap.time.time() * 1000) >= expiry: refuse("LIFETIME")

    def get(self, operation, fields, expiry=None):
        self.alive(expiry)
        status, body = self.metadata.request(operation, fields, min(self.deadline, self.source_deadline if self.source_deadline is not None else self.deadline) - bootstrap.time.monotonic())
        if type(status) is not int or not 100 <= status <= 599: refuse("SCHEMA")
        self.report("HTTP_STATUS", status=status)
        self.alive(expiry)
        if status != 200: refuse("GATEWAY" if operation == "GATEWAY" else "SCHEMA")
        return body

    @contextmanager
    def runtime(self):
        root = Path(tempfile.mkdtemp(prefix="abema-episode-")); uncertain = False
        try:
            runtime, config = root / "runtime", root / "docker-config"
            runtime.mkdir(mode=0o755); config.mkdir(mode=0o700)
            self.phase = "SOURCE_ASSETS"
            for name, _, size, digest in bootstrap.ASSETS:
                self.alive()
                raw = self.request("ASSET", {"name": name})
                self.alive()
                if len(raw) != size or bootstrap.hashlib.sha256(raw).hexdigest() != digest: refuse("RUNTIME")
                (runtime / name).write_bytes(raw); (runtime / name).chmod(0o444)
            for name in ("abema-guest-helper.cjs", "abema-episode-source-helper.cjs"):
                (runtime / name).write_bytes(Path(__file__).with_name(name).read_bytes()); (runtime / name).chmod(0o444)
            yield runtime, config
        except bootstrap.CleanupUnconfirmed:
            uncertain = True; self.retained_episode_runtime = root
            raise
        finally:
            with bootstrap.completing_cleanup():
                if not uncertain:
                    try: shutil.rmtree(root)
                    except BaseException:
                        self.retained_episode_runtime = root
                        raise bootstrap.CleanupUnconfirmed() from None

    def helper(self, runtime, config, envelope):
        self.alive(envelope["expiresAtMs"])
        private = json.dumps(envelope, ensure_ascii=True, separators=(",", ":")).encode()
        if len(private) > 2 * 1024 * 1024: refuse("SCHEMA")
        container = bootstrap.OwnedContainer(runtime, config, self.runner, min(self.deadline, self.source_deadline if self.source_deadline is not None else self.deadline),
            helper="abema-episode-source-helper.cjs", private=True)
        with container.opened() as identity:
            result = container.command("start", "--attach", "--interactive", identity, timeout=20, limit=16384, input_data=private)
            private = None
            if result.stderr or result.code not in {0, 1}: refuse("RUNTIME")
            value = bootstrap.strict_json(result.output, 16384)
            if isinstance(value, dict) and value.get("outcome") == "REFUSED":
                if set(value) == {"outcome", "reason", "configurationCheck"} and value["reason"] == "CONFIGURATION" and type(value["configurationCheck"]) is str and value["configurationCheck"] in HELPER_CONFIGURATION_CHECKS:
                    refuse(value["reason"], value["configurationCheck"])
                if set(value) != {"outcome", "reason"} or value["reason"] == "CONFIGURATION": refuse("RUNTIME")
                refuse(value["reason"])
            if result.code != 0: refuse("RUNTIME")
        self.alive(envelope["expiresAtMs"])
        return value

    def complete_private(self, context):
        try:
            if set(context) != {"userId", "bearer", "deviceId", "mediaToken"} or not bootstrap.FRESH_UUID.fullmatch(context["deviceId"]): refuse("IDENTITY")
            self.phase = "EPISODE"
            request_monotonic, request_wall_ms = bootstrap.time.monotonic(), int(bootstrap.time.time() * 1000)
            metadata, expiry = eligible_episode(self.get("EPISODE", {"bearer": context["bearer"]}), int(bootstrap.time.time() * 1000))
            self.bind_expiry(expiry, request_wall_ms, request_monotonic)
            self.report("EPISODE_ELIGIBLE", legacySeed=True, exactEpisode=True, conservativeFreeWindow=True, entitlementEstablished=False)
            nonce = uuid.uuid4().hex
            envelope = {"phase": "metadata", "episodeId": EPISODE, "metadata": metadata, "nonce": nonce,
                "expiresAtMs": expiry, "navigator": self.navigator,
                "context": {key: context[key] for key in ("userId", "deviceId", "mediaToken")}}
            with self.runtime() as (runtime, config):
                self.phase = "EPISODE_DTO"
                dto = self.helper(runtime, config, envelope)
                validate_binding(dto, "metadata", nonce, metadata, expiry, None, context["mediaToken"])
                self.phase = "GATEWAY"
                raw = self.get("GATEWAY", {"arin": dto["arin"]}, expiry)
                gateway = bootstrap.strict_json(raw, LIMIT, max_nodes=16000, max_depth=32)
                if not isinstance(gateway, dict) or not isinstance(gateway.get("streams"), list) or not 1 <= len(gateway["streams"]) <= 128: refuse("SCHEMA")
                envelope.update(phase="selection", gateway=gateway)
                self.phase = "SOURCE_CONFIG"
                binding = self.helper(runtime, config, envelope)
                validate_binding(binding, "selection", nonce, metadata, expiry, gateway, context["mediaToken"])
                self.alive(expiry)
                self.report("EPISODE_SOURCE_CONFIG_ACCEPTED", legacySeed=True, exactEpisode=True, sourceMatched=True,
                    sessionMatched=True, tokenMatched=True, configurationMatched=True, protection=binding["drm"],
                    adMode=binding["adMode"], manifestRequests=False, mediaRequests=False, licenseRequests=False,
                    certificateRequests=False, mediaKeySessions=False, deviceFlowCompleted=False, entitlementEstablished=False)
        except EpisodeRefused as failure:
            self.report("EPISODE_REFUSED", reason=failure.reason,
                **({"configurationCheck": failure.configuration_check} if failure.configuration_check is not None else {}))
            raise
        finally:
            context.clear()


def main(arguments=None, output=None):
    arguments = sys.argv[1:] if arguments is None else arguments
    output = sys.stdout if output is None else output
    if arguments != ["--run-episode-source"]:
        output.write('{"phase":"START","outcome":"OPT_IN_REQUIRED"}\n'); return 2
    if not shutil.which("docker") or not shutil.which("node"):
        output.write('{"phase":"START","outcome":"RUNTIME_UNAVAILABLE"}\n'); return 1
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    attempt, termination = EpisodeAttempt(output), bootstrap.TerminationGuard()
    try:
        with termination: status = attempt.run()
    except bootstrap.Terminated: status = 1
    if termination.received:
        output.write(json.dumps({"phase": "CLEANUP", "outcome": "TERMINATED", "cleanupConfirmed": attempt.cleanup_confirmed, "grantsPersisted": False}) + "\n")
        return 1
    return status


if __name__ == "__main__": sys.exit(main())
