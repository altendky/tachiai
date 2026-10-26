#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Opt-in development-only exact-source proof; stops before all media/DRM I/O."""

import importlib.util
import json
from pathlib import Path
import re
import resource
import shutil
import sys
import tempfile
import urllib.parse
import uuid

_spec = importlib.util.spec_from_file_location("web_abema_bootstrap", Path(__file__).with_name("abema-backend-bootstrap.py"))
bootstrap = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = bootstrap  # The native transport's spawn target is importable.
_spec.loader.exec_module(bootstrap)
Refused = bootstrap.Refused
CHANNEL = "abema-news"
CHANNEL_LIMIT = 512 * 1024
ORIGIN_FAMILY = "APPROVED_ABEMA_AKAMAI_SINGLE_LABEL"


class CatalogueRefused(Refused):
    def __init__(self, reason, evidence=None):
        if reason not in {"SIZE", "JSON", "SCHEMA", "EXACT_MATCH", "OPEN", "STATUS", "DRM", "PLAYLIST"}:
            raise Refused()
        self.reason, self.evidence = reason, evidence or {}
        if set(self.evidence) - {"statusPresent", "drmPresent", "drmExplicitFalse", "drmExplicitTrue"} or any(type(value) is not bool for value in self.evidence.values()):
            raise Refused()
        super().__init__()


def drm_evidence(channel):
    status = channel.get("status")
    return {"statusPresent": "status" in channel,
            "drmPresent": isinstance(status, dict) and "drm" in status,
            "drmExplicitFalse": isinstance(status, dict) and status.get("drm") is False,
            "drmExplicitTrue": isinstance(status, dict) and status.get("drm") is True}


def usable_playlist(value):
    if not isinstance(value, str) or not 1 <= len(value) <= 2048 or re.search(r"[\x00-\x20\\]", value) or "#" in value:
        raise Refused()
    raw = value.split("?", 1)[0]
    if "%" in raw or any(part in {".", ".."} for part in raw.split("/")):
        raise Refused()
    parsed = urllib.parse.urlsplit(value)
    if parsed.scheme != "https" or parsed.username is not None or parsed.password is not None or parsed.port is not None or parsed.fragment or ":" in parsed.netloc or parsed.netloc != parsed.hostname:
        raise Refused()
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,54}-abematv\.akamaized\.net", parsed.hostname or "") or not parsed.path.startswith("/channel/" + CHANNEL + "/") or not parsed.path.endswith(".mpd") or not re.fullmatch(r"[A-Za-z0-9_./~$-]+", parsed.path):
        raise Refused()
    return parsed


def select_channel(raw):
    if not isinstance(raw, bytes) or not 1 <= len(raw) <= CHANNEL_LIMIT:
        raise CatalogueRefused("SIZE")
    try:
        value = bootstrap.strict_json(raw, CHANNEL_LIMIT, max_nodes=16000, max_depth=32)
    except (Refused, ValueError, UnicodeError, RecursionError):
        raise CatalogueRefused("JSON") from None
    if not isinstance(value, dict) or not isinstance(value.get("channels"), list) or len(value["channels"]) > 500:
        raise CatalogueRefused("SCHEMA")
    channels = value["channels"]
    if any(not isinstance(channel, dict) or not isinstance(channel.get("id"), str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}", channel["id"]) for channel in channels):
        raise CatalogueRefused("SCHEMA")
    matches = [channel for channel in channels if channel["id"] == CHANNEL]
    if len(matches) != 1:
        raise CatalogueRefused("EXACT_MATCH")
    channel = matches[0]
    playback, status = channel.get("playback"), channel.get("status")
    if channel.get("viewingOpened") is not True:
        raise CatalogueRefused("OPEN")
    evidence = drm_evidence(channel)
    if status is not None and not isinstance(status, dict):
        raise CatalogueRefused("STATUS", evidence)
    if isinstance(status, dict) and "drm" in status and status["drm"] is not False:
        raise CatalogueRefused("DRM", evidence)
    if not isinstance(playback, dict):
        raise CatalogueRefused("PLAYLIST")
    try:
        usable_playlist(playback.get("dash"))
    except (Refused, ValueError):
        raise CatalogueRefused("PLAYLIST") from None
    alternate = playback.get("yospace")
    if "yospace" in playback and (not isinstance(alternate, dict) or "dash" in alternate and (not isinstance(alternate["dash"], str) or len(alternate["dash"]) > 2048 or re.search(r"[\x00-\x20\\]", alternate["dash"]))):
        raise CatalogueRefused("PLAYLIST")
    # Only selector inputs cross into downloaded code; no catalogue titles,
    # descriptions, other channels, account profile or bearer/refresh grant.
    # The verified Chrome/Linux selector prefers DASH. Preserve its advertised
    # DASH choices exactly, but do not forward unrelated catalogue metadata.
    playlists = {"dash": playback["dash"]}
    if "yospace" in playback:
        playlists["yospace"] = {"dash": alternate["dash"]} if "dash" in alternate else {}
    selected = {"id": CHANNEL, "viewingOpened": True, "playback": playlists}
    if "status" in channel:
        selected["status"] = None if status is None else {"drm": status["drm"]} if "drm" in status else {}
    if len(json.dumps(selected).encode()) > 16384:
        raise CatalogueRefused("SIZE")
    return selected


def validate_browser(raw):
    value = bootstrap.strict_json(raw, 4096)
    if not isinstance(value, dict) or set(value) != {"version", "navigator"} or not isinstance(value["version"], str) or not re.fullmatch(r"156\.[0-9.]{1,32}", value["version"]):
        raise Refused()
    nav = value["navigator"]
    if not isinstance(nav, dict) or set(nav) != {"userAgent", "platform", "maxTouchPoints", "userAgentData"} or nav["platform"] != "Linux x86_64" or type(nav["maxTouchPoints"]) is not int or not 0 <= nav["maxTouchPoints"] <= 32:
        raise Refused()
    ua = nav["userAgent"]
    if not isinstance(ua, str) or not 1 <= len(ua) <= 500 or not ua.isascii() or re.search(r"[\x00-\x1f\x7f]", ua) or "X11; Linux x86_64" not in ua or not re.search(r"Chrome/156\.", ua) or "HeadlessChrome" in ua:
        raise Refused()
    data = nav["userAgentData"]
    if data is not None and (not isinstance(data, dict) or set(data) != {"mobile", "platform"} or data["mobile"] is not False or data["platform"] != "Linux"):
        raise Refused()
    return nav


def validate_binding(raw, nonce, media_token, channel):
    value = bootstrap.strict_json(raw, 8192)
    if not isinstance(value, dict) or set(value) != {"nonce", "channelId", "uri", "browser"} or value["nonce"] != nonce or value["channelId"] != CHANNEL or value["browser"] != {"name": "Chrome", "major": "156", "os": "Linux", "engine": "Blink"}:
        raise Refused()
    parsed = usable_playlist(value["uri"])
    # Bind the unchanged selector's choice to an advertised playlist, preserving
    # its decision rather than guessing or retrying a plain/ad-free alternative.
    roots = [channel["playback"]["dash"]]
    if isinstance(channel["playback"].get("yospace"), dict):
        roots.append(channel["playback"]["yospace"].get("dash"))
    if value["uri"].split("?", 1)[0] not in [root.split("?", 1)[0] for root in roots if isinstance(root, str)]:
        raise Refused()
    if re.search(r"%(?![A-Fa-f0-9]{2})", parsed.query):
        raise Refused()
    pairs = urllib.parse.parse_qsl(parsed.query, keep_blank_values=True, strict_parsing=True, errors="strict")
    names = [name for name, _ in pairs]
    if len(set(names)) != len(names) or set(names) - {"t", "enc", "dt", "ut", "sw"} or any(not item or part.split("=", 1)[0] != name for part, (name, item) in zip(parsed.query.split("&"), pairs)):
        raise Refused()
    query = dict(pairs)
    if query.get("t") != media_token or query.get("enc") != "clear" or query.get("ut") != "1" or query.get("dt") != "pc_chrome" or "sw" in query and query["sw"] != "0":
        raise Refused()
    return ORIGIN_FAMILY


class SourceAttempt(bootstrap.Attempt):
    def bound_source(self, binding, nonce, media_token, channel):
        """Private extension point; the source-only proof performs no more I/O."""

    def browser_snapshot(self):
        result = self.runner([shutil.which("node"), str(Path(__file__).with_name("abema-browser-snapshot.mjs")), "--snapshot"], 20, 4096, display=True)
        if result.code != 0 or result.stderr:
            raise Refused()
        return validate_browser(result.output)

    def source(self, media_token, navigator):
        self.phase = "CHANNELS"
        try:
            channel = select_channel(self.request("CHANNELS", {}))
        except CatalogueRefused as refusal:
            self.report("CATALOGUE_REFUSED", reason=refusal.reason, **refusal.evidence)
            raise Refused() from None
        self.report("EXACT_CHANNEL_ACCEPTED", exactChannel=True, metadataConservative=True, **drm_evidence(channel))
        self.phase = "SOURCE_ASSETS"
        temporary = tempfile.TemporaryDirectory(prefix="tachiai-abema-source-")
        try:
            scratch = Path(temporary.name)
            runtime, config = scratch / "runtime", scratch / "docker-config"
            runtime.mkdir(mode=0o755)
            config.mkdir(mode=0o700)
            for name, _, size, digest in bootstrap.ASSETS:
                raw = self.request("ASSET", {"name": name})
                if len(raw) != size or bootstrap.hashlib.sha256(raw).hexdigest() != digest:
                    raise Refused()
                (runtime / name).write_bytes(raw)
                (runtime / name).chmod(0o444)
            for name in ("abema-guest-helper.cjs", "abema-source-helper.cjs"):
                (runtime / name).write_bytes(Path(__file__).with_name(name).read_bytes())
                (runtime / name).chmod(0o444)
            nonce = uuid.uuid4().hex
            private_input = json.dumps({"channel": channel, "mediaToken": media_token, "navigator": navigator, "nonce": nonce}).encode()
            if len(private_input) > 32768:
                raise Refused()
            self.phase = "SOURCE"
            container = bootstrap.OwnedContainer(runtime, config, self.runner, self.deadline, helper="abema-source-helper.cjs", private=True)
            with container.opened() as identity:
                self.report("SOURCE_CONTAINER_POLICY_VERIFIED", networkDisabled=True, coreDumpsDisabled=True, readOnly=True, loggingDisabled=True)
                result = container.command("start", "--attach", "--interactive", identity, timeout=20, limit=8192, input_data=private_input)
                private_input = None
                if result.code != 0 or result.stderr:
                    raise Refused()
                classification = validate_binding(result.output, nonce, media_token, channel)
            self.report("SOURCE_BINDING_ACCEPTED", sourceMatched=True, sessionMatched=True, tokenMatched=True,
                        originClassification=classification, manifestRequests=False, mediaRequests=False,
                        licenseRequests=False, mediaKeySessions=False, grantsPersisted=False)
            self.bound_source(result.output, nonce, media_token, channel)
        finally:
            with bootstrap.completing_cleanup():
                try:
                    temporary.cleanup()
                except BaseException:
                    raise bootstrap.CleanupUnconfirmed() from None

    def run(self):
        # Capture actual local browser identity before introducing a fresh grant.
        # The reused flow clears/discards all private values on process exit.
        try:
            self.phase = "BROWSER"
            self.navigator = self.browser_snapshot()
            self.report("BROWSER_VERIFIED", browser="Chrome", major=156, os="Linux")
        except BaseException:
            self.report("REFUSED")
            return 1
        return super().run()

    def complete(self, media_token):
        # Base calls this only after matching the profile and validating the token.
        self.source(media_token, self.navigator)


def check_catalogue(output, transport=None):
    """One grant-free fixed public GET; no browser, Docker or source selector."""
    attempt = bootstrap.Attempt(output, transport=transport)
    attempt.phase = "CHANNELS"
    cleanup_confirmed = True
    try:
        channel = select_channel(attempt.request("CHANNELS", {}))
        attempt.report("CATALOGUE_ACCEPTED", exactChannel=True, **drm_evidence(channel), originClassification=ORIGIN_FAMILY)
        return 0
    except CatalogueRefused as refusal:
        attempt.report("CATALOGUE_REFUSED", reason=refusal.reason, **refusal.evidence)
        return 1
    except bootstrap.CleanupUnconfirmed:
        cleanup_confirmed = False
        attempt.report("CLEANUP_UNCONFIRMED")
        return 1
    except bootstrap.Terminated:
        attempt.report("TERMINATED")
        return 1
    except BaseException:
        attempt.report("REFUSED")
        return 1
    finally:
        attempt.phase = "CLEANUP"
        attempt.report("FINISHED", cleanupConfirmed=cleanup_confirmed, grantsPersisted=False)


def main(arguments=None, output=None):
    arguments = sys.argv[1:] if arguments is None else arguments
    output = sys.stdout if output is None else output
    if arguments not in (["--run-source"], ["--check-catalogue"]):
        output.write('{"phase":"START","outcome":"OPT_IN_REQUIRED"}\n')
        return 2
    # No private-input process may write a core image, including spawned workers.
    try:
        resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    except (OSError, ValueError):
        output.write('{"phase":"START","outcome":"CORE_LIMIT_UNAVAILABLE"}\n')
        return 1
    if arguments == ["--check-catalogue"]:
        try:
            with bootstrap.TerminationGuard():
                return check_catalogue(output)
        except bootstrap.Terminated:
            output.write('{"phase":"CLEANUP","outcome":"TERMINATED","grantsPersisted":false}\n')
            return 1
    if not shutil.which("docker") or not shutil.which("node"):
        output.write('{"phase":"START","outcome":"RUNTIME_UNAVAILABLE"}\n')
        return 1
    attempt, termination = SourceAttempt(output), bootstrap.TerminationGuard()
    try:
        with termination:
            status = attempt.run()
    except bootstrap.Terminated:
        status = 1
    if termination.received:
        output.write(json.dumps({"phase": "CLEANUP", "outcome": "TERMINATED", "cleanupConfirmed": attempt.cleanup_confirmed, "grantsPersisted": False}) + "\n")
        return 1
    return status


if __name__ == "__main__":
    sys.exit(main())
