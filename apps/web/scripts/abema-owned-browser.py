"""Narrow shared ownership verification, loaded after the canonical bootstrap."""
import os
from pathlib import Path
import signal
import sys

# Never reload bootstrap here: its exception and multiprocessing worker identity
# must remain the one established by abema-backend-source.py.
bootstrap = sys.modules["web_abema_bootstrap"]
SOCKET_SUFFIX = "/pw-00000000/browser/0000000000000000.sock"
SOCKET_PATH_LIMIT = 103  # Pinned Playwright 1.64.1 Unix socket fallback name.


def validate_runtime_path(runtime):
    if not runtime.is_absolute() or len((str(runtime) + SOCKET_SUFFIX).encode("utf8")) > SOCKET_PATH_LIMIT:
        raise bootstrap.Refused()


def _process_stat(pid):
    text = Path("/proc", str(pid), "stat").read_text()
    fields = text[text.rindex(") ") + 2:].split()
    return fields[0], int(fields[2]), fields[19]


def _live_group(process_group):
    # Public process state/group metadata only; never unrelated command lines.
    for entry in Path("/proc").iterdir():
        if not entry.name.isdecimal():
            continue
        try:
            state, group, _ = _process_stat(int(entry.name))
            if group == process_group and state not in {"Z", "X"}:
                return True
        except (FileNotFoundError, ProcessLookupError):
            continue
        except PermissionError:
            if entry.stat().st_uid == os.getuid():
                raise bootstrap.CleanupUnconfirmed() from None
    return False


def cleanup_owned_browser(runtime):
    """Close only the positively identified detached browser group, then verify."""
    marker, owner_file = runtime / "launch-started.json", runtime / "browser-owner.json"
    if not marker.exists() and not owner_file.exists():
        return  # No browser launch began; the owned Node group is already closed.
    if not owner_file.exists():
        raise bootstrap.CleanupUnconfirmed()  # Launch interrupted before ownership registration.
    value = bootstrap.strict_json(owner_file.read_bytes(), 4096)
    if not isinstance(value, dict) or set(value) != {"pid", "processGroup", "startTime", "userDataDir"} or type(value["pid"]) is not int or value["pid"] <= 1 or value["processGroup"] != value["pid"] or not isinstance(value["startTime"], str) or not value["startTime"].isascii() or not value["startTime"].isdecimal() or not isinstance(value["userDataDir"], str):
        raise bootstrap.CleanupUnconfirmed()
    profile = Path(value["userDataDir"])
    if not profile.is_absolute() or profile.resolve() != profile or not profile.is_relative_to(runtime.resolve()) or profile == runtime.resolve():
        raise bootstrap.CleanupUnconfirmed()
    pid = value["pid"]
    if not _live_group(pid):
        return
    try:
        state, group, started = _process_stat(pid)
        with Path("/proc", str(pid), "cmdline").open("rb") as process_arguments:
            raw = process_arguments.read(65537)
        args = raw.decode("utf8", errors="strict").split("\0")
        if len(raw) > 65536 or Path("/proc", str(pid)).stat().st_uid != os.getuid() or started != value["startTime"] or group != pid or "--remote-debugging-pipe" not in args or "--user-data-dir=" + str(profile) not in args:
            raise bootstrap.CleanupUnconfirmed()
        # A PID or stale ownership file alone never authorizes a signal.
        if _process_stat(pid)[1:] != (group, started):
            raise bootstrap.CleanupUnconfirmed()
        os.killpg(pid, signal.SIGKILL)
    except (FileNotFoundError, ProcessLookupError):
        if _live_group(pid):
            raise bootstrap.CleanupUnconfirmed() from None
    end = bootstrap.time.monotonic() + 5
    while _live_group(pid):
        if bootstrap.time.monotonic() >= end:
            raise bootstrap.CleanupUnconfirmed()
        bootstrap.time.sleep(0.05)
