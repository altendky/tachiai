#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Opt-in exact fresh-source MPD proof. No initialization/media/license/CDM I/O."""

import base64
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
import urllib.request
import xml.etree.ElementTree as ET

_spec = importlib.util.spec_from_file_location("web_abema_source", Path(__file__).with_name("abema-backend-source.py"))
source = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = source
_spec.loader.exec_module(source)
bootstrap, Refused = source.bootstrap, source.Refused
# Preserve existing caller/test names without reloading source or bootstrap.
cleanup_owned_browser = source.owned_browser.cleanup_owned_browser
_process_stat = source.owned_browser._process_stat
_live_group = source.owned_browser._live_group
MPD_LIMIT = 512 * 1024
REQUEST_SECONDS = 10
MIMES = {"application/dash+xml": "DASH_XML", "application/xml": "APPLICATION_XML", "text/xml": "TEXT_XML"}
MIME_VALUES = set(MIMES.values()) | {"UNSUPPORTED", "NOT_CHECKED"}
MPD_NAMESPACE = "urn:mpeg:dash:schema:mpd:2011"
CENC_NAMESPACE = "urn:mpeg:cenc:2013"
XINCLUDE_NAMESPACE = "http://www.w3.org/2001/XInclude"


def manifest_request_spec(fields):
    """One closed operation: the bound, advertised, fresh News source only."""
    if not isinstance(fields, dict) or set(fields) != {"binding", "nonce", "media_token", "channel"}:
        raise Refused()
    if not isinstance(fields["nonce"], str) or not re.fullmatch(r"[a-f0-9]{32}", fields["nonce"]) or not bootstrap.bounded_token(fields["media_token"]):
        raise Refused()
    raw_channel = json.dumps({"channels": [fields["channel"]]}).encode("utf8")
    if len(raw_channel) > 16384:
        raise Refused()
    channel = source.select_channel(raw_channel)
    if channel != fields["channel"]:
        raise Refused()
    source.validate_binding(fields["binding"], fields["nonce"], fields["media_token"], channel)
    binding = bootstrap.strict_json(fields["binding"], 8192)
    return urllib.request.Request(binding["uri"], method="GET", headers={
        "Accept": "application/dash+xml, application/xml, text/xml", "Accept-Encoding": "identity",
    })


class ManifestReply:
    def __init__(self, status, mime, accepted, body=b"", browser_readable=None, cors_permission=None):
        self.status, self.mime, self.accepted, self.body = status, mime, accepted, body
        self.browser_readable, self.cors_permission = browser_readable, cors_permission

    def __repr__(self):
        return "ManifestReply(redacted)"


def native_manifest_exchange(fields, opener):
    request = manifest_request_spec(fields)
    try:
        response = opener.open(request, timeout=REQUEST_SECONDS)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        status = response.status
        if type(status) is not int or not 100 <= status <= 599:
            raise Refused()
        if status != 200:
            return ManifestReply(status, "NOT_CHECKED", False)  # Never read refusal/redirect bodies.
        mime = MIMES.get(response.headers.get("Content-Type", "").split(";", 1)[0].strip().lower(), "UNSUPPORTED")
        refused = ManifestReply(status, mime, False)
        if mime == "UNSUPPORTED" or response.headers.get("Content-Encoding", "identity").lower() != "identity":
            return refused
        length = response.headers.get("Content-Length")
        if length is not None and (not length.isascii() or not length.isdecimal() or int(length) > MPD_LIMIT):
            return refused
        chunks, size = [], 0
        while True:
            chunk = response.read1(min(8192, MPD_LIMIT + 1 - size))
            if not chunk:
                break
            size += len(chunk)
            if size > MPD_LIMIT:
                return refused
            chunks.append(chunk)
        body = b"".join(chunks)
        if not body or length is not None and len(body) != int(length):
            return refused
        return ManifestReply(status, mime, True, body)


def manifest_worker(sender, fields):
    # No downloaded code executes in the network owner. The hard parent deadline
    # covers open/read, and private data crosses only bounded multiprocessing IPC.
    try:
        with open(os.devnull, "wb") as silent:
            os.dup2(silent.fileno(), 1)
            os.dup2(silent.fileno(), 2)
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), bootstrap.NoRedirect())
        reply = native_manifest_exchange(fields, opener)
        sender.send_bytes(json.dumps({"status": reply.status, "mime": reply.mime,
                                      "accepted": reply.accepted, "body": base64.b64encode(reply.body).decode("ascii")}).encode("ascii"))
    except BaseException:
        try:
            sender.send_bytes(b'{"refused":true}')
        except BaseException:
            pass
    finally:
        sender.close()


class ManifestTransport:
    """Single-use, fixed-operation worker with bounded private IPC and teardown."""
    def __init__(self, context=None):
        self.context, self.used = context or multiprocessing.get_context("spawn"), False

    def request(self, fields, timeout):
        manifest_request_spec(fields)  # Refuse before creating a process.
        if self.used or timeout <= 0:
            raise Refused()
        self.used = True
        receiver, sender = self.context.Pipe(duplex=False)
        process = self.context.Process(target=manifest_worker, args=(sender, fields), daemon=True)
        started = False
        try:
            with bootstrap.completing_cleanup():
                process.start()
                started = True
                sender.close()
            if not receiver.poll(min(timeout, REQUEST_SECONDS)):
                raise Refused()
            reply = bootstrap.strict_json(receiver.recv_bytes(bootstrap.IPC_LIMIT), bootstrap.IPC_LIMIT)
            if not isinstance(reply, dict) or set(reply) != {"status", "mime", "accepted", "body"} or type(reply["status"]) is not int or not 100 <= reply["status"] <= 599 or not isinstance(reply["mime"], str) or reply["mime"] not in MIME_VALUES or type(reply["accepted"]) is not bool or not isinstance(reply["body"], str):
                raise Refused()
            body = base64.b64decode(reply["body"], validate=True)
            if len(body) > MPD_LIMIT or (not reply["accepted"] and body) or (reply["accepted"] and (reply["status"] != 200 or reply["mime"] not in MIMES.values() or not body)):
                raise Refused()
            return ManifestReply(reply["status"], reply["mime"], reply["accepted"], body)
        finally:
            with bootstrap.completing_cleanup():
                sender.close()
                receiver.close()
                if started:
                    process.join(timeout=0.2)
                    if process.is_alive():
                        process.terminate()
                        process.join(timeout=2)
                    if process.is_alive():
                        process.kill()
                        process.join(timeout=2)
                    if process.is_alive():
                        raise bootstrap.CleanupUnconfirmed()
                process.close()


class BrowserManifestTransport:
    """One real browser fetch from an owned loopback origin, never a native fallback."""
    def __init__(self, runner=bootstrap.run_command):
        self.runner, self.used = runner, False
        self.retained_runtime = None

    def request(self, fields, timeout):
        manifest_request_spec(fields)
        if self.used or timeout <= 0:
            raise Refused()
        self.used = True
        binding = bootstrap.strict_json(fields["binding"], 8192)
        private_input = json.dumps({"uri": binding["uri"], "nonce": fields["nonce"]}).encode("utf8")
        runtime = Path(tempfile.mkdtemp(prefix="tachiai-browser-manifest-"))
        try:
            source.owned_browser.validate_runtime_path(runtime)
            result = self.runner([shutil.which("node"), str(Path(__file__).with_name("abema-browser-manifest.mjs")),
                                  "--owned-runtime", str(runtime)], min(timeout, 30),
                                 (MPD_LIMIT * 4 + 2) // 3 + 8192, input_data=private_input, display=True)
            private_input = None
            value = bootstrap.strict_json(result.output, bootstrap.IPC_LIMIT)
            if not isinstance(value, dict) or value.get("cleanupConfirmed") is not True:
                raise bootstrap.CleanupUnconfirmed()
            expected = {"nonce", "browserReadable", "corsPermission", "status", "mime", "accepted", "body", "cleanupConfirmed"}
            if result.code != 0 or result.stderr or set(value) != expected or value["nonce"] != fields["nonce"] or type(value["browserReadable"]) is not bool or type(value["accepted"]) is not bool or not isinstance(value["corsPermission"], str) or value["corsPermission"] not in {"CORS_READABLE", "UNESTABLISHED"} or not isinstance(value["mime"], str) or value["mime"] not in MIME_VALUES or not isinstance(value["body"], str):
                raise Refused()
            readable = value["browserReadable"]
            if readable != (value["corsPermission"] == "CORS_READABLE") or (readable and (type(value["status"]) is not int or not 100 <= value["status"] <= 599)) or (not readable and (value["status"] is not None or value["mime"] != "NOT_CHECKED" or value["accepted"])):
                raise Refused()
            body = base64.b64decode(value["body"], validate=True)
            if len(body) > MPD_LIMIT or (not value["accepted"] and body) or (value["accepted"] and (not readable or value["status"] != 200 or value["mime"] not in MIMES.values() or not body)):
                raise Refused()
            return ManifestReply(value["status"], value["mime"], value["accepted"], body,
                                 browser_readable=readable, cors_permission=value["corsPermission"])
        finally:
            with bootstrap.completing_cleanup():
                try:
                    cleanup_owned_browser(runtime)
                    shutil.rmtree(runtime)
                except BaseException:
                    self.retained_runtime = runtime
                    raise bootstrap.CleanupUnconfirmed() from None


def inspect_mpd(raw):
    """Parse structural names only; never read IDs/URLs/pssh text or decode it."""
    if not isinstance(raw, bytes) or not 1 <= len(raw) <= MPD_LIMIT:
        raise Refused()
    text = raw.decode("utf-8-sig", errors="strict")
    if "\0" in text or re.search(r"<!\s*(?:DOCTYPE|ENTITY)\b", text, re.IGNORECASE):
        raise Refused()
    # ElementTree has no external resource loader here; DTD/entity declarations
    # are rejected before parsing, and XInclude expansion is never invoked.
    document = ET.fromstring(text, parser=ET.XMLParser())
    if document.tag != "{" + MPD_NAMESPACE + "}MPD":
        raise Refused()
    count, cenc, pssh = 0, False, False
    pending = [(document, 0)]
    while pending:
        element, depth = pending.pop()
        count += 1
        if count > 10000 or depth > 64 or len(element.attrib) > 64 or not isinstance(element.tag, str) or element.tag.startswith("{" + XINCLUDE_NAMESPACE + "}"):
            raise Refused()
        if element.tag.startswith("{" + CENC_NAMESPACE + "}") or any(name.startswith("{" + CENC_NAMESPACE + "}") for name in element.attrib):
            cenc = True
        if element.tag == "{" + CENC_NAMESPACE + "}pssh":
            pssh = True
        pending.extend((child, depth + 1) for child in element)
    return {"cencPresent": cenc, "psshPresent": pssh}


class ManifestAttempt(source.SourceAttempt):
    manifest_phase = "MANIFEST"

    def __init__(self, output, transport=None, runner=bootstrap.run_command, manifest_transport=None):
        super().__init__(output, transport, runner)
        self.manifest_transport = manifest_transport or ManifestTransport()

    def bound_source(self, binding, nonce, media_token, channel):
        self.phase = self.manifest_phase
        fields = {"binding": binding, "nonce": nonce, "media_token": media_token, "channel": channel}
        try:
            reply = self.manifest_transport.request(fields, self.deadline - bootstrap.time.monotonic())
        except (bootstrap.CleanupUnconfirmed, bootstrap.Terminated):
            raise
        except BaseException:
            self.report("MANIFEST_TRANSPORT_REFUSED", sourceBound=True, manifestAccepted=False)
            raise Refused() from None
        self.report_transport(reply)
        if not reply.accepted:
            self.report("MANIFEST_REFUSED", sourceBound=True, manifestAccepted=False)
            raise Refused()
        try:
            evidence = inspect_mpd(reply.body)
        except bootstrap.Terminated:
            raise
        except BaseException:
            self.report("MANIFEST_XML_REFUSED", sourceBound=True, manifestAccepted=False)
            raise Refused() from None
        finally:
            reply.body = b""
        self.report("MANIFEST_ACCEPTED", sourceBound=True, manifestAccepted=True, **evidence,
                    initializationRequests=False, segmentRequests=False, licenseRequests=False,
                    mediaKeySessions=False, manifestPersisted=False, grantsPersisted=False)

    def report_transport(self, reply):
        self.report("HTTP_STATUS", status=reply.status, mime=reply.mime, sourceBound=True)


class BrowserManifestAttempt(ManifestAttempt):
    manifest_phase = "BROWSER_MANIFEST"

    def __init__(self, output, transport=None, runner=bootstrap.run_command, manifest_transport=None):
        super().__init__(output, transport, runner, manifest_transport or BrowserManifestTransport(runner))

    def report_transport(self, reply):
        evidence = {"sourceBound": True, "mime": reply.mime, "browserReadable": reply.browser_readable,
                    "corsPermission": reply.cors_permission}
        if reply.status is None:
            self.report("BROWSER_FETCH_REFUSED", **evidence)
        else:
            self.report("HTTP_STATUS", status=reply.status, **evidence)


def main(arguments=None, output=None):
    arguments = sys.argv[1:] if arguments is None else arguments
    output = sys.stdout if output is None else output
    if arguments not in (["--run-manifest"], ["--run-browser-manifest"]):
        output.write('{"phase":"START","outcome":"OPT_IN_REQUIRED"}\n')
        return 2
    try:
        resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    except (OSError, ValueError):
        output.write('{"phase":"START","outcome":"CORE_LIMIT_UNAVAILABLE"}\n')
        return 1
    if not shutil.which("docker") or not shutil.which("node"):
        output.write('{"phase":"START","outcome":"RUNTIME_UNAVAILABLE"}\n')
        return 1
    attempt_class = BrowserManifestAttempt if arguments == ["--run-browser-manifest"] else ManifestAttempt
    attempt, termination = attempt_class(output), bootstrap.TerminationGuard()
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
