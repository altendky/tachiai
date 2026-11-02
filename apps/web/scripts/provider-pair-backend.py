#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Private one-use fresh ABEMA preparation for the finite provider-pair proof.

This worker's stdout is a PRIVATE pipe to its parent, never a diagnostic stream.
No provider packet, route descriptor, grant or private error is written to disk.
"""

import importlib.util
import io
import json
import os
from pathlib import Path
import resource
import sys

spec = importlib.util.spec_from_file_location("web_abema_episode_eme", Path(__file__).with_name("abema-backend-episode-eme.py"))
eme = sys.modules.get(spec.name)
if eme is None:
    eme = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = eme
    spec.loader.exec_module(eme)
bootstrap, episode, manifest, comparison = eme.bootstrap, eme.episode, eme.manifest, eme.comparison

SOURCE = "abema-replay-394-72_s10_p8529"
SOURCE_SECONDS = 120


def owned_directory(value):
    path = Path(value)
    if (not path.is_absolute() or path.resolve() != path or not path.is_dir()
            or path.stat().st_uid != os.getuid() or path.stat().st_mode & 0o077):
        raise bootstrap.Refused()
    return path


def validate_config(value, now=None):
    now = bootstrap.time.monotonic_ns() if now is None else now
    if not isinstance(value, dict) or set(value) != {"route", "requestStartedNs", "deadlineNs"}:
        raise bootstrap.Refused()
    route = bootstrap.route_transport.validate_route(value["route"])
    for key in ("requestStartedNs", "deadlineNs"):
        if not isinstance(value[key], str) or not eme.re.fullmatch(r"[1-9][0-9]{0,18}", value[key]):
            raise bootstrap.Refused()
    start, end = int(value["requestStartedNs"]), int(value["deadlineNs"])
    if route is None or not start <= now < end <= start + SOURCE_SECONDS * 10**9:
        raise bootstrap.Refused()
    return route, start, end


class FreshPairAttempt(eme.EpisodeEmeAttempt):
    def __init__(self, route, start, deadline, public, **dependencies):
        # Existing reports are discarded in bounded RAM; never forwarded raw.
        super().__init__(io.StringIO(), route, **dependencies)
        self.deadline = min(self.deadline, deadline / 10**9, start / 10**9 + SOURCE_SECONDS)
        self.started_ns, self.public, self.packet = start, public, None

    def report(self, *_arguments, **_fields):
        pass

    def bound_episode(self, binding, nonce, metadata, expiry, gateway, media_token):
        self.alive(expiry)
        if (self.eme_used or self.public_runtime is None or self.private_user is None
                or binding["drm"] != "CUSTOM" or binding["adMode"] != "CSAI"):
            raise bootstrap.Refused()
        self.eme_used = True
        self.public.mkdir(mode=0o700)
        for name, _, size, digest in bootstrap.ASSETS:
            raw = (self.public_runtime / name).read_bytes()
            self._public_asset(name, raw, size, digest)
        name, _, size, digest = bootstrap.EME_ASSET
        raw = self.request("EME_ASSET", {"name": name})
        self._public_asset(name, raw, size, digest)
        request = manifest.private_input(binding, nonce, metadata, expiry, gateway, media_token,
                                         min(self.deadline, self.source_deadline))
        if not self.started_ns <= int(request["clockSampleNs"]) < int(request["deadlineNs"]):
            raise bootstrap.Refused()
        self.packet = {"request": request, "userId": self.private_user, "licenseUri": binding["licenseUri"],
                       "requestStartedNs": str(self.started_ns)}
        if len(json.dumps(self.packet, separators=(",", ":")).encode("ascii")) > 32768:
            self.packet = None
            raise bootstrap.Refused()

    def _public_asset(self, name, raw, size, digest):
        self.alive()
        if len(raw) != size or bootstrap.hashlib.sha256(raw).hexdigest() != digest:
            raise bootstrap.Refused()
        file = self.public / name
        with file.open("xb") as destination:
            destination.write(raw)
        file.chmod(0o400)


def main(arguments=None, input_=None, output=None):
    arguments = sys.argv[1:] if arguments is None else arguments
    input_, output = sys.stdin.buffer if input_ is None else input_, sys.stdout if output is None else output
    attempt, result = None, {"outcome": "REFUSED", "packet": None, "cleanupConfirmed": True}
    if len(arguments) != 2 or arguments[0] != "--fresh-owned":
        output.write(json.dumps(result))
        return 2
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    try:
        with bootstrap.TerminationGuard():
            root = owned_directory(arguments[1])
            # All worker scratch directories stay inside this exact owned job.
            os.environ["TMPDIR"] = str(root)
            import tempfile
            tempfile.tempdir = None
            value = bootstrap.strict_json(input_.read(16385), 16384)
            route, start, deadline = validate_config(value)
            value = None
            attempt = FreshPairAttempt(route, start, deadline, root / "public")
            code = attempt.run()
            result["cleanupConfirmed"] = attempt.cleanup_confirmed
            if code == 0 and attempt.cleanup_confirmed and attempt.packet is not None:
                attempt.alive()
                result.update(outcome="READY", packet=attempt.packet)
    except BaseException:
        if attempt is not None:
            result["cleanupConfirmed"] = attempt.cleanup_confirmed
    output.write(json.dumps(result, ensure_ascii=True, separators=(",", ":")))
    output.flush()
    return 0 if result["outcome"] == "READY" and result["cleanupConfirmed"] else 1


if __name__ == "__main__":
    sys.exit(main())
