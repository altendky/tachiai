#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""Stage synthetic owned OpenVPN servers and run installed disposable-emulator JNI tests."""

import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time

from fixtures import certificates, profile
from routed_fixtures import docker, origin_certificate, server_config


IMAGE = "sha256:fef2d19086ba5d965f1d9fab825ca461e9cbb8904625247aab129bd1f4d2269b"
DEVICES = {
    "standard": ("emulator-5582", "tachiai-issue-tests"),
    "16k": ("emulator-5584", "tachiai-issue-tests-16k"),
}
DEVICE_ROOT = "/data/local/tmp/tachiai-openvpn-owned"
TEST_CLASS = "net.fstab.tachiai.platform.network.NativeOpenVpnRouteTest"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--temp-root", type=Path, required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--transport", choices=("udp", "tcp"), required=True)
    parser.add_argument("--device", choices=DEVICES, default="standard")
    arguments = parser.parse_args()
    serial, avd = DEVICES[arguments.device]
    # Explicit serial validation excludes phones and the persistent tachiai-dev AVD.
    # The first ADB operation is identity verification, before staging or running.
    identity = subprocess.check_output([arguments.adb, "-s", serial, "emu", "avd", "name"], text=True, timeout=10)
    if identity.splitlines() != [avd, "OK"]:
        raise RuntimeError("Disposable emulator AVD identity did not match")

    def adb(*parts: str, timeout: int = 10) -> str:
        return subprocess.check_output([arguments.adb, "-s", serial, *parts], text=True,
                                       stderr=subprocess.STDOUT, timeout=timeout)

    os.umask(0o077)
    if arguments.device == "16k" and adb("shell", "getconf", "PAGE_SIZE").strip() != "16384":
        raise RuntimeError("Owned 16 KiB emulator has an unexpected page size")
    root = Path(tempfile.mkdtemp(prefix=f"android-openvpn-{arguments.transport}-", dir=arguments.temp_root))
    staged = root / "device"
    staged.mkdir()
    containers: list[str] = []
    try:
        for name, marker in (("a", "identity-A"), ("b", "identity-B")):
            fixture = root / name
            fixture.mkdir()
            certificates(fixture)
            origin_certificate(fixture)
            server_config(fixture, False, arguments.transport)
            fixture.chmod(0o755)
            for path in fixture.iterdir():
                path.chmod(0o644)
            container = docker("run", "--detach", "--cap-drop", "ALL", "--cap-add", "NET_ADMIN",
                               "--device", "/dev/net/tun", "--security-opt", "no-new-privileges",
                               "--read-only", "--tmpfs", "/tmp:rw,nosuid,nodev,noexec,size=8m",
                               "--publish", f"127.0.0.1::1194/{arguments.transport}", "--mount",
                               f"type=bind,src={fixture},dst=/fixture,readonly",
                               "--env", f"TACHIAI_OWNED_MARKER={marker}", IMAGE)
            containers.append(container)
            inspection = json.loads(docker("inspect", container))[0]
            host = inspection["HostConfig"]
            if (host["Privileged"] or host["NetworkMode"] == "host" or
                    host["CapAdd"] != ["CAP_NET_ADMIN"] or host["CapDrop"] != ["ALL"]):
                raise RuntimeError("Owned server isolation differs from fixture contract")
            published = inspection["NetworkSettings"]["Ports"][f"1194/{arguments.transport}"]
            if len(published) != 1 or published[0]["HostIp"] != "127.0.0.1":
                raise RuntimeError("Owned server publication is not host-loopback-only")
            deadline = time.monotonic() + 8
            while "OWNED_READY" not in docker("logs", container):
                if time.monotonic() >= deadline or not json.loads(docker("inspect", container))[0]["State"]["Running"]:
                    raise RuntimeError("Owned gateway did not become ready")
                time.sleep(0.05)
            contents = profile(fixture, int(published[0]["HostPort"]))
            contents = contents.replace("remote 127.0.0.1 ", "remote 10.0.2.2 ")
            if arguments.transport == "tcp":
                contents = contents.replace("proto udp\n", "proto tcp-client\n")
            (staged / f"{name}.ovpn").write_text(contents)
            (staged / f"{name}-origin-ca.pem").write_bytes((fixture / "origin-ca.pem").read_bytes())
        correct = (staged / "a.ovpn").read_text()
        wrong_ca = correct.replace((root / "a" / "ca.pem").read_text(), (root / "b" / "ca.pem").read_text())
        if wrong_ca == correct:
            raise RuntimeError("Owned wrong-CA profile was not distinct")
        (staged / "wrong-ca.ovpn").write_text(wrong_ca)
        (staged / "wrong-name.ovpn").write_text(correct.replace("verify-x509-name OwnedServer name", "verify-x509-name WrongServer name"))
        adb("shell", "mkdir", "-p", DEVICE_ROOT)
        for path in sorted(staged.iterdir()):
            adb("push", str(path), DEVICE_ROOT + "/" + path.name)
        result = adb("shell", "am", "instrument", "-w", "-r", "-e", "class", TEST_CLASS,
                     "-e", "openvpnOwnedRoot", DEVICE_ROOT,
                     "net.fstab.tachiai.test/androidx.test.runner.AndroidJUnitRunner", timeout=140)
        (root / "instrumentation.log").write_text(result)
        if "OK (3 tests)" not in result or "FAILURES!!!" in result or "INSTRUMENTATION_FAILED" in result:
            raise RuntimeError("Owned Android JNI instrumentation did not pass all three tests")
        for container in containers:
            logs = docker("logs", container)
            if "OWNED_DNS" not in logs or "OWNED_HTTPS" not in logs:
                raise RuntimeError("Owned encrypted DNS/HTTPS server evidence missing")
        print(f"Owned Android {arguments.transport.upper()} JNI/TLS/DNS/HTTPS/isolation/cancellation passed; logs: {root}")
    finally:
        cleanup_failed = False
        for index, container in enumerate(containers):
            try:
                (root / f"server-{index}.log").write_text(docker("logs", container))
            except subprocess.CalledProcessError:
                print("Owned gateway logs could not be collected")
            try:
                docker("rm", "--force", container)
            except subprocess.CalledProcessError:
                cleanup_failed = True
                print("Owned gateway cleanup failed")
        try:
            adb("shell", "rm", "-rf", DEVICE_ROOT)
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
            cleanup_failed = True
            print("Owned device fixture cleanup failed")
        if cleanup_failed:
            raise RuntimeError("Owned fixture cleanup requires attention; inspect session logs")
        # Preserve synthetic inputs and logs for this session's evidence/review.


if __name__ == "__main__":
    main()
