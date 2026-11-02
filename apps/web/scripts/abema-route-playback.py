#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Explicit finite protected-replay decode probe using a temporary route."""

import importlib.util
import json
from pathlib import Path
import resource
import shutil
import sys

_spec = importlib.util.spec_from_file_location("web_abema_episode_playback", Path(__file__).with_name("abema-backend-episode-playback.py"))
playback = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = playback
_spec.loader.exec_module(playback)
comparison, bootstrap = playback.comparison, playback.bootstrap
ALLOWED_HOSTS = comparison.ALLOWED_HOSTS + ",license.p-c3-e.abema-tv.com"


def main(arguments=None, output=None):
    arguments = sys.argv[1:] if arguments is None else arguments
    output = sys.stdout if output is None else output
    if (len(arguments) != 6 or arguments[:3] != ["--run-route-replay-playback", "--peer-idle-confirmed", "--route-runner"]
            or arguments[4] != "--wireguard-file"):
        output.write('{"phase":"ROUTE_PLAYBACK","outcome":"OPT_IN_REQUIRED"}\n')
        return 2
    if sys.platform != "linux" or not shutil.which("docker") or not shutil.which("node"):
        output.write('{"phase":"ROUTE_PLAYBACK","outcome":"RUNTIME_UNAVAILABLE"}\n')
        return 1
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    route = attempt = None
    outcome, status = "REFUSED", 1
    termination = bootstrap.TerminationGuard()
    try:
        with termination:
            profile = comparison.read_profile(arguments[5])
            route = comparison.owner.RouteOwner(arguments[3], profile, ALLOWED_HOSTS, lease_seconds=180,
                                                 completing_cleanup=bootstrap.completing_cleanup)
            profile = None
            with route:
                output.write('{"phase":"ROUTE_PLAYBACK","outcome":"READY","mode":"WIREGUARD","exitRegionMeasured":false}\n')
                output.flush()
                attempt = playback.EpisodePlaybackAttempt(output, route.route)
                status = attempt.run()
                outcome = "FINISHED"
    except bootstrap.Terminated:
        status, outcome = 1, "TERMINATED"
    except BaseException:
        status = 1
    finally:
        route_confirmed = route is None or route.close()
        attempt_confirmed = attempt is None or attempt.cleanup_confirmed
        confirmed = route_confirmed and attempt_confirmed
        if termination.received:
            status, outcome = 1, "TERMINATED"
        if not confirmed:
            status, outcome = 1, "CLEANUP_UNCONFIRMED"
        output.write(json.dumps({"phase": "ROUTE_PLAYBACK_CLEANUP", "outcome": outcome,
            "cleanupConfirmed": confirmed, "routeCleanupConfirmed": route_confirmed,
            "attemptCleanupConfirmed": attempt_confirmed, "hostRoutesChanged": False,
            "profilePersisted": False, "backendAndBrowserRouteShared": attempt is not None,
            "trialSucceeded": status == 0}) + "\n")
    return status


if __name__ == "__main__":
    sys.exit(main())
