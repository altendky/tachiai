#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Opt-in, temporary WireGuard routing for the exact replay MPD diagnostic.

No OS routing changes, account storage, media/CDM access or deployed companion.
The selected peer must be idle on every other device before this command runs.
"""

import importlib.util
import json
import os
from pathlib import Path
import resource
import shutil
import stat
import sys

_spec = importlib.util.spec_from_file_location("web_abema_episode_manifest", Path(__file__).with_name("abema-backend-episode-manifest.py"))
manifest = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = manifest
_spec.loader.exec_module(manifest)
episode, bootstrap = manifest.episode, manifest.bootstrap
_spec = importlib.util.spec_from_file_location("web_abema_route_owner", Path(__file__).with_name("abema-route-owner.py"))
owner = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = owner
_spec.loader.exec_module(owner)

# Existing backend preparation hosts and the already admitted replay-MPD family.
# The runner cannot become a generic local proxy or follow an unreviewed CDN.
ALLOWED_HOSTS = "abema.tv,api.p-c3-e.abema-tv.com,api.abema.io,streaming-api-cf.p-c2-x.abema-tv.com,*-abematv.akamaized.net"


class RoutedBrowserRunner:
    def __init__(self, route, runner=episode.episode_command):
        self.route, self.runner = bootstrap.route_transport.validate_route(route), runner
        if self.route is None:
            raise bootstrap.Refused()

    def __call__(self, args, timeout, limit=32768, input_data=None, **options):
        path = Path(__file__).with_name("abema-browser-episode-manifest.mjs")
        if len(args) > 1 and args[1] == str(path):
            if len(args) != 4 or args[2] != "--owned-runtime" or not isinstance(input_data, bytes):
                raise bootstrap.Refused()
            packet = bootstrap.strict_json(input_data, 16384)
            wire = json.dumps({"request": packet, "route": self.route.private_packet()}, separators=(",", ":")).encode("utf8")
            if len(wire) > 32768:
                raise bootstrap.Refused()
            return self.runner([*args, "--with-route"], timeout, limit, input_data=wire, **options)
        # The setup snapshot makes no provider request; its context aborts all
        # page requests. Container helpers retain their network-disabled policy.
        if Path(args[0]).name in {"node", "nodejs"}:
            if len(args) < 2 or args[1] != str(path.with_name("abema-browser-snapshot.mjs")):
                raise bootstrap.Refused()
        return self.runner(args, timeout, limit, input_data=input_data, **options)


def routed_attempt(output, route, runner=episode.episode_command):
    route = bootstrap.route_transport.validate_route(route)
    if route is None:
        raise bootstrap.Refused()
    routed_runner = RoutedBrowserRunner(route, runner)
    return manifest.EpisodeManifestAttempt(output,
        transport=bootstrap.NativeTransport(proxy_route=route), runner=routed_runner,
        metadata=episode.EpisodeTransport(proxy_route=route),
        manifest=manifest.EpisodeManifestTransport(routed_runner))


def read_profile(path):
    # Read the existing export once in the consumer; never copy it to a runtime,
    # command argument, diagnostic, environment or ownership record.
    descriptor = os.open(path, os.O_RDONLY | os.O_CLOEXEC | os.O_NOFOLLOW)
    try:
        info = os.fstat(descriptor)
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or not 1 <= info.st_size <= 8192:
            raise bootstrap.Refused()
        raw = os.read(descriptor, 8193)
        if len(raw) != info.st_size or os.read(descriptor, 1):
            raise bootstrap.Refused()
        return raw.decode("utf8", errors="strict")
    finally:
        os.close(descriptor)


def main(arguments=None, output=None):
    arguments, output = sys.argv[1:] if arguments is None else arguments, sys.stdout if output is None else output
    expected = ["--run-route-replay-metadata", "--peer-idle-confirmed", "--route-runner", "--wireguard-file"]
    if len(arguments) != 6 or arguments[:3] != expected[:3] or arguments[4] != expected[3]:
        output.write('{"phase":"ROUTE","outcome":"OPT_IN_REQUIRED"}\n')
        return 2
    if sys.platform != "linux" or not shutil.which("docker") or not shutil.which("node"):
        output.write('{"phase":"ROUTE","outcome":"RUNTIME_UNAVAILABLE"}\n')
        return 1
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    trial = attempt = None
    outcome, status = "REFUSED", 1
    termination = bootstrap.TerminationGuard()
    try:
        with termination:
            profile = read_profile(arguments[5])
            trial = owner.RouteOwner(arguments[3], profile, ALLOWED_HOSTS, lease_seconds=180,
                                     completing_cleanup=bootstrap.completing_cleanup)
            profile = None
            with trial:
                output.write('{"phase":"ROUTE","outcome":"READY","mode":"WIREGUARD","exitRegionMeasured":false}\n')
                output.flush()
                attempt = routed_attempt(output, trial.route)
                status = attempt.run()
                outcome = "FINISHED"
    except bootstrap.Terminated:
        status, outcome = 1, "TERMINATED"
    except BaseException:
        status = 1  # Private file/process/provider errors never enter output.
    finally:
        route_confirmed = trial is None or trial.close()
        attempt_confirmed = attempt is None or attempt.cleanup_confirmed
        confirmed = route_confirmed and attempt_confirmed
        if not confirmed:
            status, outcome = 1, "CLEANUP_UNCONFIRMED"
        output.write(json.dumps({"phase": "ROUTE_CLEANUP", "outcome": outcome,
            "cleanupConfirmed": confirmed, "routeCleanupConfirmed": route_confirmed,
            "attemptCleanupConfirmed": attempt_confirmed, "hostRoutesChanged": False, "profilePersisted": False,
            "backendAndManifestRouteShared": attempt is not None, "trialSucceeded": status == 0}) + "\n")
    return status


if __name__ == "__main__":
    sys.exit(main())
