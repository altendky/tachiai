#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Run one forced ABEMA renewal on an explicitly chosen device and judge it (#689).

Each run is a real ABEMA account confirmation for the saved account. Run it by
hand only: never in CI or a loop. The debug build's renew-now trigger runs the
pre-expiry timer's automatic path now; only the due time is bypassed.

Steps: optionally `adb install -r` (keeps app data), launch Tachiai with the
launcher intent so the stream picker takes over in its own process, fire the
trigger, optionally freeze Tachiai's default and approval-browser processes
while Android ranks them cached (the cached-app freezer case, #682), collect
renewal, approval and freezer log lines, and print a pass/fail summary.

Only closed-constant log lines from Tachiai's renewal/approval tags and
ActivityManager freezer lines naming Tachiai's processes are kept. No URL,
identity or token is read or printed.
"""

import argparse
import importlib.util
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import threading
import time


ROOT = Path(__file__).resolve().parents[1]
sys.dont_write_bytecode = True  # Loading the shared device constants leaves no cache in the repository.
_spec =importlib.util.spec_from_file_location("android_dev_device", ROOT / "scripts/android-dev-device.py")
_device = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_device)
CERTIFICATE = _device.CERTIFICATE
APPLICATION = _device.APPLICATION

BROWSER = APPLICATION + ":abema_approval_browser"
# The processes whose freezing stalls a renewal (#682): the default process runs
# the operation, route engine and deadlines; the approval browser runs the page.
FREEZE_TARGETS = (APPLICATION, BROWSER)
AUTHORITY = APPLICATION + ".debug.abema-renewal"
TAGS = ("TachiaiAbemaRenewal", "TachiaiAbemaApproval")
TRIGGER_RESULTS = {"STARTED", "NO_ACCOUNT", "AMBIGUOUS", "RUNNING", "WINDOW_SPENT", "MANUAL_APPROVAL",
                   "UNAVAILABLE", "INVALID_REQUEST"}
# `logcat -v epoch`: seconds, pid, tid, priority, tag, message.
LOG_LINE = re.compile(r"\s*(\d+\.\d+)\s+\d+\s+\d+\s+[VDIWEF]\s+([^:]+?)\s*:\s?(.*)")
FREEZER = re.compile(r"(?:freezing|froze|unfreezing|unfroze)\s+\d+\s+(" + re.escape(APPLICATION) + r"(?::[\w.]+)?)\b")
OPERATION = re.compile(r"OPERATION_RESULT result=([A-Z_]+) cleanupConfirmed=(true|false)\b")
FROZE_PROCESS = re.compile(r"\bfreezing\s+\d+\s+(" + re.escape(APPLICATION) + r"(?::[\w.]+)?)\b")


class CheckError(Exception):
    """A setup refusal before or instead of a provider attempt."""


def parse_line(line):
    """Return (seconds, tag, message) for a kept line, otherwise None."""
    match = LOG_LINE.fullmatch(line.rstrip("\r\n"))
    if match is None:
        return None
    seconds, tag, message = float(match.group(1)), match.group(2), match.group(3)
    if tag in TAGS:
        return seconds, tag, message
    if tag == "ActivityManager" and FREEZER.search(message):
        return seconds, tag, message
    return None


def parse_trigger(output):
    """`content call` prints `Result: Bundle[{result=STARTED}]`."""
    match = re.search(r"\bresult=([A-Z_]+)\b", output)
    if match is None or match.group(1) not in TRIGGER_RESULTS:
        return None
    return match.group(1)


def process_states(dump):
    """Map Tachiai process names to (adjustment, frozen) from `dumpsys activity processes`."""
    states = {}
    current = None
    for line in dump.splitlines():
        record = re.match(r"\s*\*APP\* UID \d+ ProcessRecord\{\w+ \d+:([^/\s]+)/", line)
        if record:
            current = record.group(1) if record.group(1).startswith(APPLICATION) else None
            if current:
                states[current] = [None, None]
            continue
        if current is None:
            continue
        adjustment = re.search(r"\boom adj: max=\d+ curRaw=-?\d+ setRaw=-?\d+ cur=(-?\d+) set=-?\d+", line)
        if adjustment and states[current][0] is None:
            states[current][0] = int(adjustment.group(1))
        frozen = re.search(r"\bisFrozen=(true|false)\b", line)
        if frozen:
            states[current][1] = frozen.group(1) == "true"
    return {name: tuple(value) for name, value in states.items()}


def freezer_cutoff(settings):
    match = re.search(r"\bfreezer_cutoff_adj=(\d+)\b", settings)
    return int(match.group(1)) if match else 900


def judge(lines, triggered_at, actions):
    """Summarize kept lines after the trigger. Returns a dict; passed is the verdict.

    actions are the freeze step's (process, device seconds). A process counts
    as frozen during the operation only while it still had a part in it: the
    default process until OPERATION_RESULT, the approval browser until its
    BROWSER_RESULT. Once the browser has delivered and confirmed its cleanup,
    Android may freeze its idle process; that is reported separately.
    """
    result = cleanup = None
    ended = {}
    freezes = [(seconds, name) for name, seconds in actions]
    for seconds, tag, message in lines:
        if seconds < triggered_at:
            continue
        if tag == "ActivityManager":
            frozen = FROZE_PROCESS.search(message)
            if frozen and frozen.group(1) in FREEZE_TARGETS:
                freezes.append((seconds, frozen.group(1)))
            continue
        if message.startswith("BROWSER_RESULT "):
            ended.setdefault(BROWSER, seconds)
        operation = OPERATION.search(message)
        if operation and result is None:
            result, cleanup = operation.group(1), operation.group(2) == "true"
            ended[APPLICATION] = seconds
    finished = ended.get(APPLICATION)
    working, idle = set(), set()
    for seconds, name in freezes:
        if finished is not None and seconds > finished:
            continue
        part = ended.get(name, finished)
        (working if part is None or seconds <= part else idle).add(name)
    return {
        "result": result or "NONE",
        "cleanupConfirmed": cleanup,
        "froze": sorted(working),
        "frozeAfterItsPart": sorted(idle - working),
        "duration": None if finished is None else finished - triggered_at,
        "passed": result == "SUCCESS" and cleanup is True and not working,
    }


def browser_done(lines, triggered_at):
    return any(seconds >= triggered_at and message.startswith("BROWSER_RESULT ") for seconds, _, message in lines)


class Device:
    def __init__(self, adb, serial):
        self.adb_path, self.serial = adb, serial

    def adb(self, *args, timeout=60, check=True):
        result = subprocess.run([self.adb_path, "-s", self.serial, *map(str, args)], text=True,
                                capture_output=True, timeout=timeout)
        if check and result.returncode != 0:
            raise CheckError(f"adb {args[0]} failed with status {result.returncode}.")
        return result.stdout

    def shell(self, *args, **kwargs):
        return self.adb("shell", *args, **kwargs)

    def verify(self, expected_avd):
        if self.adb("get-state", timeout=10).strip() != "device":
            raise CheckError("Selected device is not online.")
        if expected_avd is not None:
            names = self.adb("emu", "avd", "name", timeout=10, check=False).splitlines()
            if not names or names[0].strip() != expected_avd:
                raise CheckError("Selected device is not the expected AVD; refusing access.")
        if f"package:{APPLICATION}" not in self.shell("pm", "list", "packages", APPLICATION).split():
            raise CheckError("Tachiai is not installed on the selected device.")

    def freezer_supported(self):
        help_text = self.shell("am", "help", check=False)
        if not re.search(r"^\s*freeze \[--sticky\] <PROCESS>", help_text, re.M) or \
                not re.search(r"^\s*unfreeze \[--sticky\] <PROCESS>", help_text, re.M):
            return False
        return re.search(r"\buse_freezer=true\b", self.shell("dumpsys", "activity", check=False)) is not None

    def launch(self):
        leanback = "feature:android.software.leanback" in self.shell("pm", "list", "features").split()
        category = "android.intent.category.LEANBACK_LAUNCHER" if leanback else "android.intent.category.LAUNCHER"
        self.shell("am", "start", "-W", "-a", "android.intent.action.MAIN", "-c", category,
                   "-n", APPLICATION + "/.MainActivity", timeout=60)


def verify_apk(apk, sdk):
    tools = sorted((sdk / "build-tools").glob("*/apksigner")) if sdk else []
    if not tools:
        raise CheckError("apksigner is unavailable; set ANDROID_HOME to verify the APK signer.")
    output = subprocess.run([tools[-1], "verify", "--print-certs", apk], text=True, capture_output=True,
                            timeout=120).stdout
    certificates = [line.split("certificate SHA-256 digest: ", 1)[1].strip().upper()
                    for line in output.splitlines() if "certificate SHA-256 digest: " in line]
    if certificates != [CERTIFICATE]:
        raise CheckError("APK signer does not match the shared debug certificate.")
    aapt = tools[-1].parent / "aapt2"
    if aapt.exists():
        badging = subprocess.run([aapt, "dump", "badging", apk], text=True, capture_output=True, timeout=120).stdout
        if not badging.startswith(f"package: name='{APPLICATION}' "):
            raise CheckError("APK application ID is not Tachiai's development application.")


def default_adb():
    for candidate in (os.environ.get("ADB"),
                      os.environ.get("ANDROID_HOME") and str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb"),
                      str(Path.home() / ".local/share/tachiai/android/sdk/platform-tools/adb")):
        if candidate and Path(candidate).is_file():
            return candidate
    return shutil.which("adb") or "adb"


class LogReader:
    """Streams logcat from now on; keeps only lines parse_line accepts."""

    def __init__(self, device):
        self.lines, self.lock = [], threading.Lock()
        self.process = subprocess.Popen([device.adb_path, "-s", device.serial, "logcat", "-v", "epoch", "-T", "1"],
                                        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True,
                                        errors="replace")
        self.thread = threading.Thread(target=self.read, daemon=True)
        self.thread.start()

    def read(self):
        for line in self.process.stdout:
            kept = parse_line(line)
            if kept:
                with self.lock:
                    self.lines.append(kept)

    def snapshot(self):
        with self.lock:
            return list(self.lines)

    def close(self):
        self.process.terminate()
        try:
            self.process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.process.kill()


def device_seconds(device):
    return float(device.shell("date", "+%s.%N", timeout=10).strip()[:17])


def run(args):
    device = Device(args.adb, args.serial)
    device.verify(args.expect_avd)
    if args.freeze and not device.freezer_supported():
        raise CheckError("This device has no usable `am freeze`/`unfreeze` or cached-app freezer; "
                         "run without --freeze.")
    if args.apk:
        verify_apk(args.apk.resolve(), Path(os.environ["ANDROID_HOME"]) if os.environ.get("ANDROID_HOME")
                   else Path.home() / ".local/share/tachiai/android/sdk")
        device.adb("install", "-r", args.apk.resolve(), timeout=300)
        print("Installed with install -r (app data kept).")
    device.launch()
    print(f"Launched; waiting {args.settle:g} s for the stream picker to take over.")
    time.sleep(args.settle)
    cutoff = freezer_cutoff(device.shell("dumpsys", "activity", "settings", check=False)) if args.freeze else None
    reader = LogReader(device)
    try:
        return observe(args, device, reader, cutoff)
    finally:
        reader.close()


def freeze_cached(device, reader, triggered_at, cutoff, actions):
    """Freeze each target Android ranks cached while it still has a part in the operation."""
    states = process_states(device.shell("dumpsys", "activity", "processes", APPLICATION, check=False))
    finished = {BROWSER} if browser_done(reader.snapshot(), triggered_at) else set()
    for name in FREEZE_TARGETS:
        adjustment, _ = states.get(name, (None, None))
        # The freezer's own rule: only a process ranked cached is frozen. `am
        # freeze` alone would also freeze a process kept out of the cached
        # state, which a device never does on its own.
        if adjustment is None or adjustment < cutoff or name in finished or name in (done for done, _ in actions):
            continue
        device.shell("am", "freeze", name, check=False)
        actions.append((name, device_seconds(device)))
        print(f"Froze {name} (cached, adj {adjustment}) at +{actions[-1][1] - triggered_at:.1f} s")


def observe(args, device, reader, cutoff):
    actions = []
    time.sleep(1)
    triggered_at = device_seconds(device)
    command = ["content", "call", "--uri", f"content://{AUTHORITY}", "--method", "renew"]
    if args.instance:
        command += ["--arg", args.instance]
    trigger = parse_trigger(device.shell(*command, timeout=60, check=False))
    print(f"Trigger: {trigger or 'NO_REPLY'}")
    if trigger != "STARTED":
        return 2
    try:
        began = time.monotonic()
        frozen_since = None
        while time.monotonic() - began < args.timeout:
            if judge(reader.snapshot(), triggered_at, actions)["result"] != "NONE":
                break
            if args.freeze and args.freeze_after <= time.monotonic() - began <= args.freeze_window and \
                    len(actions) < len(FREEZE_TARGETS):
                freeze_cached(device, reader, triggered_at, cutoff, actions)
                if actions and frozen_since is None:
                    frozen_since = time.monotonic()
            if frozen_since is not None and time.monotonic() - frozen_since >= args.freeze_seconds:
                break
            time.sleep(1)
    finally:
        for name, _ in actions:
            device.shell("am", "unfreeze", name, check=False)
        if actions:
            print(f"Unfroze {', '.join(name for name, _ in actions)}")
    if not actions and judge(reader.snapshot(), triggered_at, actions)["result"] == "NONE":
        # Android froze them on its own: thaw them to collect the late result.
        states = process_states(device.shell("dumpsys", "activity", "processes", APPLICATION, check=False))
        thawed = [name for name in FREEZE_TARGETS if states.get(name, (None, None))[1]]
        for name in thawed:
            device.shell("am", "unfreeze", name, check=False)
        if thawed:
            print(f"No result within {args.timeout:g} s; thawed frozen {', '.join(thawed)} to collect it")
    stalled = judge(reader.snapshot(), triggered_at, actions)["result"] == "NONE"
    if stalled:
        # After a thaw, overdue deadlines report at once (#682).
        end = time.monotonic() + 30
        while time.monotonic() < end and judge(reader.snapshot(), triggered_at, actions)["result"] == "NONE":
            time.sleep(1)
    time.sleep(2)
    lines = reader.snapshot()
    summary = judge(lines, triggered_at, actions)
    print("\nCollected lines:")
    for seconds, tag, message in lines:
        if seconds >= triggered_at:
            print(f"  +{seconds - triggered_at:7.2f} s {tag}: {message}")
    if args.log_file:
        args.log_file.write_text("".join(f"{seconds:.3f} {tag}: {message}\n" for seconds, tag, message in lines))
    cleanup = "unknown" if summary["cleanupConfirmed"] is None else str(summary["cleanupConfirmed"]).lower()
    duration = "none (no result)" if summary["duration"] is None else f"{summary['duration']:.1f} s"
    late = " (reported only after a thaw)" if stalled and summary["duration"] is not None else ""
    print("\nABEMA renewal check: " + ("PASS" if summary["passed"] else "FAIL"))
    print(f"  result: {summary['result']}")
    print(f"  cleanupConfirmed: {cleanup}")
    print(f"  froze during operation: {', '.join(summary['froze']) or 'no'}")
    if summary["frozeAfterItsPart"]:
        print(f"  froze after its part ended: {', '.join(summary['frozeAfterItsPart'])}")
    print(f"  duration: {duration}{late}")
    if args.freeze:
        steps = "; ".join(f"{name} at +{at - triggered_at:.1f} s" for name, at in actions)
        print(f"  freeze step: {steps or 'no process was cached while it had a part'}")
    return 0 if summary["passed"] else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("serial", help="ADB serial of the device to use; there is no default device")
    parser.add_argument("--expect-avd", help="refuse unless the emulator reports this AVD name, e.g. tachiai-dev")
    parser.add_argument("--apk", type=Path, help="debug APK to install first with `adb install -r`")
    parser.add_argument("--freeze", action="store_true",
                        help="freeze the default and approval-browser processes once Android ranks them cached")
    parser.add_argument("--freeze-after", type=float, default=3,
                        help="seconds after the trigger before freezing, once the page is under way (default 3)")
    parser.add_argument("--freeze-window", type=float, default=30,
                        help="seconds after the trigger to keep watching for cached processes (default 30)")
    parser.add_argument("--freeze-seconds", type=float, default=100,
                        help="seconds to hold the freeze, past the 90-second operation deadline (default 100)")
    parser.add_argument("--settle", type=float, default=5, help="seconds to wait after launch (default 5)")
    parser.add_argument("--timeout", type=float, default=120,
                        help="seconds to wait for a result before treating the renewal as stalled (default 120)")
    parser.add_argument("--instance", help="ABEMA provider instance, when more than one has a saved account")
    parser.add_argument("--log-file", type=Path, help="also write the kept lines here")
    parser.add_argument("--adb", default=default_adb(), help="adb executable")
    return run(parser.parse_args())


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (CheckError, OSError, subprocess.SubprocessError) as error:
        print(f"ABEMA renewal check: {error}", file=sys.stderr)
        sys.exit(2)
