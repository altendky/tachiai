#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Manage one persistent local AVD without touching a connected phone."""

import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import tempfile
import time
import tomllib
import urllib.request
import zipfile


ROOT = Path(__file__).resolve().parents[1]
PROFILE = ROOT / "apps/android/dev-device.toml"
CERTIFICATE = "A258F5F71E7D828F51A3AAF6AA94F97B9DBFBD64E0CE08394A59C120914A7A17"
APPLICATION = "net.fstab.tachiai"
PROCESS_MEMORY = ("VmRSS", "VmHWM", "RssAnon", "RssFile", "RssShmem", "VmSwap", "VmSize", "VmPeak")
ROLLUP_MEMORY = ("Rss", "Pss", "Pss_Anon", "Pss_File", "Pss_Shmem", "Private_Clean", "Private_Dirty",
                 "Anonymous", "AnonHugePages", "Swap", "SwapPss")
HOST_MEMORY = ("MemTotal", "MemAvailable", "SwapTotal", "SwapFree")
MEMORY_REASONS = {"CONFIGURATION_UNVERIFIED", "METADATA_INVALID", "METRICS_INVALID", "METRICS_UNAVAILABLE", "IDENTITY_UNVERIFIED",
                  "IDENTITY_CHANGED", "PROCESS_DISAPPEARED", "PROCESS_NOT_VISIBLE", "PROCESS_AMBIGUOUS",
                  "PERMISSION_DENIED", "PROC_UNAVAILABLE"}


class MemoryUnavailable(Exception):
    """Closed diagnostic category; never contains a proc path or raw input."""


def bounded_proc_read(path, maximum=65536):
    with path.open("rb") as source:
        value = source.read(maximum + 1)
    if len(value) > maximum:
        raise MemoryUnavailable("METADATA_INVALID")
    return value


def resolve_memory_identity(path):
    try:
        return path.resolve(strict=True)
    except RuntimeError:
        # Python 3.12 reports symlink loops as RuntimeError rather than OSError.
        raise MemoryUnavailable("IDENTITY_UNVERIFIED") from None


def memory_object(path):
    metadata = path.stat()
    return metadata.st_dev, metadata.st_ino


def memory_counters(raw, fields):
    values = {}
    for line in raw.decode("ascii").splitlines():
        key, separator, rest = line.partition(":")
        if not separator or key not in fields:
            continue
        match = re.fullmatch(r"\s*([0-9]{1,19})\s+kB\s*", rest)
        if key in values or match is None or int(match[1]) > 2**63 - 1:
            raise MemoryUnavailable("METRICS_INVALID")
        values[key] = int(match[1])
    if set(values) != set(fields):
        raise MemoryUnavailable("METRICS_INVALID")
    return values


class EmulatorMemory:
    """Read scalar proc metadata only; never execute ADB or read guest files."""

    def __init__(self, proc, executable, avd, name, port):
        self.proc, self.executable, self.avd = proc, executable, avd
        self.name, self.port = name, str(port)

    def start_ticks(self, process):
        stat = bounded_proc_read(process / "stat", 4096).decode("ascii")
        before, separator, after = stat.rpartition(") ")
        fields = after.split()
        if not separator or before.partition(" (")[0] != process.name or len(fields) < 20 or \
                re.fullmatch(r"[0-9]{1,19}", fields[19]) is None or int(fields[19]) > 2**63 - 1:
            raise MemoryUnavailable("IDENTITY_UNVERIFIED")
        if fields[0] in ("Z", "X", "x"):
            raise MemoryUnavailable("PROCESS_DISAPPEARED")
        if fields[0] not in ("R", "S", "D", "T", "t", "W", "K", "I", "P"):
            raise MemoryUnavailable("IDENTITY_UNVERIFIED")
        return int(fields[19])

    def context(self, process):
        try:
            if memory_object(self.proc / "self/ns/mnt") != memory_object(process / "ns/mnt") or \
                    memory_object(self.proc / "self/root") != memory_object(process / "root"):
                raise MemoryUnavailable("IDENTITY_UNVERIFIED")
        except FileNotFoundError:
            raise MemoryUnavailable("IDENTITY_UNVERIFIED") from None

    def owned_data(self, process, arguments):
        if "-datadir" in arguments:
            position = arguments.index("-datadir")
            if arguments.count("-datadir") != 1 or position + 1 >= len(arguments):
                return False
            directory = Path(arguments[position + 1])
            return directory.is_absolute() and resolve_memory_identity(directory) == self.avd and \
                memory_object(process / "root" / self.avd.relative_to("/")) == memory_object(self.avd)
        # Default AVD selection comes from launcher environment, which is never
        # read. Require kernel FD metadata proving the owned userdata instead.
        for count, descriptor in enumerate((process / "fd").iterdir()):
            if count >= 1024:
                raise MemoryUnavailable("IDENTITY_UNVERIFIED")
            try:
                target = descriptor.readlink()
                if target.is_absolute() and target.name in ("userdata-qemu.img", "userdata-qemu.img.qcow2", "userdata.img") and \
                        resolve_memory_identity(target).parent == self.avd:
                    if memory_object(descriptor) != memory_object(self.avd / target.name):
                        raise MemoryUnavailable("IDENTITY_UNVERIFIED")
                    return True
            except FileNotFoundError:
                continue  # An unrelated descriptor can close during inspection.
        return False

    def identity(self, process):
        ticks = self.start_ticks(process)
        self.context(process)
        if resolve_memory_identity(process / "exe") != self.executable:
            return None
        if memory_object(process / "exe") != memory_object(self.executable):
            raise MemoryUnavailable("IDENTITY_UNVERIFIED")
        raw = bounded_proc_read(process / "cmdline")
        if not raw or not raw.endswith(b"\0"):
            raise MemoryUnavailable("IDENTITY_UNVERIFIED")
        arguments = raw.decode("utf-8").split("\0")[:-1]
        for flag, expected in (("-avd", self.name), ("-port", self.port)):
            if arguments.count(flag) != 1 or arguments.index(flag) + 1 >= len(arguments) or \
                    arguments[arguments.index(flag) + 1] != expected:
                return None
        if not self.owned_data(process, arguments):
            raise MemoryUnavailable("IDENTITY_UNVERIFIED")
        if self.start_ticks(process) != ticks:
            raise MemoryUnavailable("IDENTITY_CHANGED")
        self.context(process)
        return ticks

    def pressure(self):
        values = {}
        for line in bounded_proc_read(self.proc / "pressure/memory", 1024).decode("ascii").splitlines():
            parts = line.split()
            if len(parts) != 5 or parts[0] not in ("some", "full") or parts[0] in values:
                raise MemoryUnavailable("METRICS_INVALID")
            item = {}
            for part, key in zip(parts[1:], ("avg10", "avg60", "avg300", "total")):
                name, separator, value = part.partition("=")
                if not separator or name != key or re.fullmatch(r"[0-9]{1,19}(?:\.[0-9]{1,6})?", value) is None:
                    raise MemoryUnavailable("METRICS_INVALID")
                number = int(value) if key == "total" and "." not in value else float(value)
                if key == "total" and (not isinstance(number, int) or number > 2**63 - 1) or key != "total" and number > 100:
                    raise MemoryUnavailable("METRICS_INVALID")
                item["total_us" if key == "total" else key] = number
            values[parts[0]] = item
        if set(values) != {"some", "full"}:
            raise MemoryUnavailable("METRICS_INVALID")
        return values

    def snapshot(self):
        matches = []
        for count, process in enumerate(self.proc.iterdir()):
            if count >= 8192:
                raise MemoryUnavailable("PROC_UNAVAILABLE")
            if re.fullmatch(r"[1-9][0-9]{0,9}", process.name) is None:
                continue
            try:
                if bounded_proc_read(process / "comm", 256).strip() != b"qemu-system-x86":
                    continue
                ticks = self.identity(process)
                if ticks is not None:
                    matches.append((process, ticks))
                    if len(matches) != 1:
                        raise MemoryUnavailable("PROCESS_AMBIGUOUS")
            except FileNotFoundError:
                continue  # A process can exit during the initial bounded scan.
        if not matches:
            raise MemoryUnavailable("PROCESS_NOT_VISIBLE")
        process, ticks = matches[0]
        try:
            counters = memory_counters(bounded_proc_read(process / "status", 16384), PROCESS_MEMORY)
        except FileNotFoundError:
            raise MemoryUnavailable("PROCESS_DISAPPEARED") from None
        host = memory_counters(bounded_proc_read(self.proc / "meminfo"), HOST_MEMORY)
        pressure = self.pressure()
        try:
            rollup = {"status": "AVAILABLE", "kib": memory_counters(bounded_proc_read(process / "smaps_rollup", 16384), ROLLUP_MEMORY)}
        except (OSError, UnicodeError, MemoryUnavailable) as error:
            rollup = {"status": "UNAVAILABLE", "reason": "METRICS_UNAVAILABLE" if isinstance(error, FileNotFoundError) else memory_reason(error)}
        try:
            identity = self.identity(process)
        except FileNotFoundError:
            raise MemoryUnavailable("PROCESS_DISAPPEARED") from None
        if identity != ticks:
            raise MemoryUnavailable("IDENTITY_CHANGED")
        return {"status": "AVAILABLE", "pid": int(process.name), "start_ticks": ticks,
                "process_kib": counters, "host_kib": host, "pressure": pressure, "rollup": rollup}


def memory_reason(error):
    if isinstance(error, MemoryUnavailable):
        return str(error) if str(error) in MEMORY_REASONS else "PROC_UNAVAILABLE"
    if isinstance(error, PermissionError):
        return "PERMISSION_DENIED"
    if isinstance(error, UnicodeError):
        return "METADATA_INVALID"
    return "PROC_UNAVAILABLE"


def properties(path):
    return dict(
        (part.strip() for part in line.split("=", 1))
        for line in path.read_text().splitlines()
        if "=" in line and not line.lstrip().startswith("#")
    )


class Device:
    def __init__(self, state, profile):
        self.state = state.resolve()
        if self.state == ROOT or ROOT in self.state.parents:
            raise ValueError("Device state must stay outside the repository.")
        self.profile = profile
        self.spec = profile["device"]
        self.sdk = self.state / "sdk"
        self.avds = self.state / "avd"
        self.avd = self.avds / (self.spec["name"] + ".avd")
        self.serial = "emulator-" + str(self.spec["port"])
        self.tools = self.sdk / "cmdline-tools" / profile["sdk"]["command_line_version"] / "bin"
        self.env = dict(os.environ, ANDROID_HOME=str(self.sdk), ANDROID_SDK_ROOT=str(self.sdk),
                        ANDROID_AVD_HOME=str(self.avds))

    def run(self, *args, capture=False, **kwargs):
        return subprocess.run([str(a) for a in args], env=self.env, check=True,
                              text=True, capture_output=capture, **kwargs)

    def adb(self, *args, **kwargs):
        return self.run(self.sdk / "platform-tools/adb", "-s", self.serial, *args, **kwargs)

    def verify_sdk(self):
        source = self.tools.parent / "source.properties"
        if not source.is_file() or properties(source).get("Pkg.Revision") != self.profile["sdk"]["command_line_version"]:
            raise ValueError("Command-line tools revision does not match the profile.")
        for package, revision in self.profile["sdk"]["revisions"].items():
            source = self.sdk.joinpath(*package.split(";")) / "source.properties"
            if not source.is_file() or properties(source).get("Pkg.Revision") != revision:
                raise ValueError(f"SDK revision mismatch: {package}; expected {revision}. "
                                 "Review the profile; no automatic device migration is allowed.")

    def verify_avd(self):
        index = self.avds / (self.spec["name"] + ".ini")
        if index.is_symlink() or self.avd.is_symlink() or not index.is_file():
            raise ValueError("AVD registry is missing or redirected; refusing access.")
        registered = properties(index).get("path", "")
        if not registered or not Path(registered).is_absolute() or Path(registered).resolve() != self.avd:
            raise ValueError("AVD registry points to another data directory; refusing access.")
        marker = self.avd / "tachiai-profile.toml"
        recorded = tomllib.loads(marker.read_text()) if marker.is_file() else {}
        # Preferences are intentionally reconfigurable; hardware and SDK are
        # still frozen to the owned device and cannot silently migrate.
        if recorded.get("device") != self.spec or recorded.get("sdk") != self.profile["sdk"]:
            raise ValueError("Existing AVD is unowned or its definition differs. "
                             "It was not modified; review it or choose a separate device.")
        config = properties(self.avd / "config.ini")
        image = self.spec["system_image"].replace(";", "/") + "/"
        expected = {"image.sysdir.1": image, "hw.ramSize": str(self.spec["ram_mb"]),
                    "hw.cpu.ncore": str(self.spec["cores"]),
                    "hw.keyboard": "yes" if self.spec["keyboard"] else "no"}
        if any(config.get(key) != value for key, value in expected.items()):
            raise ValueError("Existing AVD hardware/image differs; it was not modified.")

    def bootstrap(self, seed):
        if (self.tools / "sdkmanager").is_file():
            return
        destination = self.tools.parent
        if destination.exists():
            raise ValueError("Incomplete command-line tools already exist; refusing overwrite.")
        self.state.mkdir(parents=True, exist_ok=True, mode=0o700)
        if seed:
            source = seed / "cmdline-tools/latest"
            if properties(source / "source.properties").get("Pkg.Revision") != self.profile["sdk"]["command_line_version"]:
                raise ValueError("Seed command-line tools do not match the selected version.")
            shutil.copytree(source, destination)
            if (seed / "licenses").is_dir() and not (self.sdk / "licenses").exists():
                shutil.copytree(seed / "licenses", self.sdk / "licenses")
            return
        print("Downloading the pinned Google command-line tools bootstrap…", flush=True)
        with tempfile.TemporaryDirectory(prefix="bootstrap-", dir=self.state) as folder:
            archive = Path(folder) / "tools.zip"
            urllib.request.urlretrieve(self.profile["sdk"]["bootstrap_url"], archive)
            actual = hashlib.sha1(archive.read_bytes()).hexdigest()
            if actual != self.profile["sdk"]["bootstrap_sha1"]:
                raise ValueError("Command-line tools checksum mismatch.")
            with zipfile.ZipFile(archive) as zipped:
                for entry in zipped.infolist():
                    path = Path(entry.filename)
                    if path.is_absolute() or ".." in path.parts or not path.parts or path.parts[0] != "cmdline-tools":
                        raise ValueError("Unexpected bootstrap archive member.")
                zipped.extractall(folder)
            shutil.copytree(Path(folder) / "cmdline-tools", destination)
            for executable in (destination / "bin").iterdir():
                executable.chmod(executable.stat().st_mode | 0o111)

    def setup(self, seed):
        if platform.system() != "Linux" or platform.machine() != "x86_64":
            raise ValueError("This initial profile/bootstrap supports Linux x86-64 only.")
        # Check ownership/definition BEFORE downloads or any AVD creation.
        index = self.avds / (self.spec["name"] + ".ini")
        if self.avd.exists() or index.exists():
            self.verify_avd()
            self.verify_sdk()
            print("Existing development device preserved; no changes made.")
            return
        self.bootstrap(seed)
        packages = []
        for package, revision in self.profile["sdk"]["revisions"].items():
            source = self.sdk.joinpath(*package.split(";")) / "source.properties"
            if source.exists():
                if properties(source).get("Pkg.Revision") != revision:
                    raise ValueError(f"Refusing to upgrade existing SDK package {package}.")
            else:
                packages.append(package)
        if packages:
            print("Installing selected SDK packages; accept Google licenses if prompted.", flush=True)
            self.run(self.tools / "sdkmanager", f"--sdk_root={self.sdk}", *packages)
        self.verify_sdk()
        self.avds.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.run(self.tools / "avdmanager", "create", "avd", "--name", self.spec["name"],
                 "--package", self.spec["system_image"], "--device", self.spec["hardware"],
                 "--path", self.avd, input="no\n")
        config = self.avd / "config.ini"
        values = properties(config)
        values.update({"hw.ramSize": str(self.spec["ram_mb"]),
                       "hw.cpu.ncore": str(self.spec["cores"]),
                       "hw.keyboard": "yes" if self.spec["keyboard"] else "no"})
        config.write_text("".join(f"{key}={value}\n" for key, value in values.items()))
        (self.avd / "tachiai-profile.toml").write_bytes(PROFILE.read_bytes())
        self.verify_avd()
        print(f"Created {self.spec['name']}; persistent data: {self.avd}")

    def connected(self):
        output = self.run(self.sdk / "platform-tools/adb", "devices", capture=True, timeout=10).stdout
        for line in output.splitlines():
            fields = line.split()
            if fields and fields[0] == self.serial:
                if len(fields) != 2 or fields[1] != "device":
                    raise ValueError("Selected emulator exists but is not online.")
                names = self.adb("emu", "avd", "name", capture=True, timeout=10).stdout.splitlines()
                if not names or names[0] != self.spec["name"]:
                    raise ValueError("Emulator port belongs to another AVD; refusing access.")
                paths = self.adb("emu", "avd", "path", capture=True, timeout=10).stdout.splitlines()
                if not paths or not Path(paths[0]).is_absolute() or Path(paths[0]).resolve() != self.avd:
                    raise ValueError("Running emulator belongs to another data directory; refusing access.")
                return True
        return False

    def ready(self):
        self.verify_avd()
        if not self.connected():
            raise ValueError("Development emulator is not running; start it first.")
        if self.adb("shell", "getprop", "sys.boot_completed", capture=True, timeout=10).stdout.strip() != "1":
            raise ValueError("Development emulator has not finished booting.")

    def memory(self, proc=Path("/proc")):
        utc = datetime.datetime.now(datetime.UTC).isoformat(timespec="seconds")
        try:
            self.verify_sdk()
            self.verify_avd()
            executable = (self.sdk / "emulator/qemu/linux-x86_64/qemu-system-x86_64").resolve(strict=True)
        except (OSError, ValueError, RuntimeError):
            return {"utc": utc, "status": "UNAVAILABLE", "reason": "CONFIGURATION_UNVERIFIED"}
        try:
            result = EmulatorMemory(proc, executable, self.avd, self.spec["name"], self.spec["port"]).snapshot()
        except (OSError, UnicodeError, MemoryUnavailable) as error:
            result = {"status": "UNAVAILABLE", "reason": memory_reason(error)}
        return {"utc": utc, **result}

    def start(self, headless, cold_boot=False, gpu=None):
        selected_gpu = gpu if gpu is not None else self.profile["runtime"]["gpu"]
        if selected_gpu not in ("auto", "host", "software", "swiftshader", "swangle", "lavapipe"):
            raise ValueError("Unsupported graphics backend; emulator was not started.")
        self.verify_sdk()
        self.verify_avd()
        if self.connected():
            if gpu is not None or cold_boot:
                raise ValueError("Emulator is already running; stop it before changing renderer or cold-booting.")
            self.configure()
            print(f"Already running: {self.serial}")
            return
        self.run(self.sdk / "emulator/emulator", "-accel-check")
        log = self.state / "emulator.log"
        with log.open("a") as output:
            process = subprocess.Popen(
                [str(self.sdk / "emulator/emulator"), "-avd", self.spec["name"],
                 "-port", str(self.spec["port"]), "-gpu", selected_gpu, "-no-boot-anim",
                 *(["-no-snapshot-load"] if cold_boot else []),
                 *(["-no-window"] if headless else [])], env=self.env,
                stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT,
                start_new_session=True)
        print(f"Starting persistent device; log: {log}", flush=True)
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise ValueError(f"Emulator exited ({process.returncode}); inspect {log}.")
            try:
                self.ready()
            except (ValueError, subprocess.CalledProcessError, subprocess.TimeoutExpired):
                time.sleep(2)
                continue
            self.configure()
            print(f"Ready: {self.serial}")
            return
        raise ValueError(f"Boot timed out; process left intact for diagnosis. Inspect {log}.")

    def stop(self):
        self.verify_avd()
        if not self.connected():
            raise ValueError("Development emulator is not running.")
        self.adb("emu", "kill", timeout=15)
        # Normal shutdown can spend time saving its snapshot. Do not report
        # completion while a following start could still target that process.
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            output = self.run(self.sdk / "platform-tools/adb", "devices", capture=True, timeout=10).stdout
            if not any(line.split() and line.split()[0] == self.serial for line in output.splitlines()):
                return
            time.sleep(2)
        raise ValueError("Emulator is still shutting down; left intact, without forced termination.")

    def configure(self):
        preferences = self.profile["preferences"]
        night = preferences["night_mode"]
        navigation = preferences["navigation"]
        if night not in ("yes", "no", "auto") or navigation not in ("threebutton", "gestural"):
            raise ValueError("Unsupported Android preferences; no settings were applied.")
        self.ready()
        self.adb("shell", "cmd", "uimode", "night", night, timeout=15)
        self.adb("shell", "cmd", "overlay", "enable-exclusive", "--user", "0", "--category",
                 "com.android.internal.systemui.navbar." + navigation, timeout=15)

    def install(self, apk):
        self.ready()
        result = self.run(self.sdk / "build-tools/37.0.0/apksigner", "verify", "--print-certs",
                          apk.resolve(), capture=True)
        certs = [line.split("certificate SHA-256 digest: ", 1)[1].upper() for line in result.stdout.splitlines()
                 if "certificate SHA-256 digest: " in line]
        if certs != [CERTIFICATE]:
            raise ValueError("APK signer does not match the shared debug certificate.")
        badging = self.run(self.sdk / "build-tools/37.0.0/aapt2", "dump", "badging",
                           apk.resolve(), capture=True).stdout
        if not badging.startswith(f"package: name='{APPLICATION}' "):
            raise ValueError("APK application ID is not Tachiai's development application.")
        self.adb("install", "-r", apk.resolve())
        self.adb("shell", "am", "start", "-a", "android.intent.action.MAIN",
                 "-c", "android.intent.category.LAUNCHER", "-n", APPLICATION + "/.MainActivity")


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    base = Path(os.environ.get("XDG_DATA_HOME", str(Path.home() / ".local/share")))
    parser.add_argument("--state-dir", type=Path, default=base / "tachiai/android")
    commands = parser.add_subparsers(dest="command", required=True)
    setup = commands.add_parser("setup")
    setup.add_argument("--seed-sdk", type=Path, help="Reuse matching command-line tools and accepted licenses only")
    start = commands.add_parser("start")
    start.add_argument("--headless", action="store_true")
    start.add_argument("--cold-boot", action="store_true", help="Skip Quick Boot snapshot; preserve userdata")
    start.add_argument("--gpu",
                       choices=("auto", "host", "software", "swiftshader", "swangle", "lavapipe"),
                       help="Override the profile renderer; stop first to change an existing process")
    commands.add_parser("stop")
    commands.add_parser("status")
    commands.add_parser("memory", help="Read bounded host/emulator memory metadata only; never start or change a device")
    commands.add_parser("configure", help="Apply non-secret Android preferences from the repo profile")
    install = commands.add_parser("install")
    install.add_argument("apk", nargs="?", type=Path,
                         default=ROOT / "apps/android/app/build/outputs/apk/debug/app-debug.apk")
    args = parser.parse_args()
    device = Device(args.state_dir, tomllib.loads(PROFILE.read_text()))
    if args.command == "setup":
        device.setup(args.seed_sdk)
    elif args.command == "start":
        device.start(args.headless, args.cold_boot, args.gpu)
    elif args.command == "stop":
        device.stop()
    elif args.command == "status":
        device.ready()
        print(f"Ready: {device.spec['name']} ({device.serial}); data: {device.avd}")
    elif args.command == "memory":
        print(json.dumps(device.memory(), sort_keys=True))
    elif args.command == "configure":
        device.configure()
    elif args.command == "install":
        device.install(args.apk)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        print(f"Development device: {error}", file=sys.stderr)
        sys.exit(1)
