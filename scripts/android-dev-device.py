#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Manage one persistent local AVD without touching a connected phone."""

import argparse
import hashlib
import os
from pathlib import Path
import platform
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
