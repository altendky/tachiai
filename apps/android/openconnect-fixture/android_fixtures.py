#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""Opt-in owned ocserv/JNI proof on an installed disposable-emulator APK.

Requires an explicitly supplied immutable image built by ocserv.Dockerfile.
No provider credentials, production allowlists, client TUN or host routes are used.
Synthetic credentials and logs remain in the supplied session temporary root.
"""

import argparse
import http.server
import json
import os
from pathlib import Path
import re
import ssl
import subprocess
import sys
import tempfile
import time
import uuid


DEVICES = {
    "standard": ("emulator-5582", "tachiai-issue-tests"),
    "16k": ("emulator-5584", "tachiai-issue-tests-16k"),
}
DEVICE_ROOT = "/data/local/tmp/tachiai-openconnect-owned"
TEST_CLASS = "net.fstab.tachiai.platform.network.NativeOpenConnectRouteTest"
SERVER_CONFIG = '''auth = "certificate"
cert-user-oid = 2.5.4.3
tcp-port = 4443
udp-port = 0
run-as-user = root
run-as-group = root
socket-file = /tmp/ocserv-socket
pid-file = /tmp/ocserv.pid
server-cert = /fixture/server.pem
server-key = /fixture/server.key
ca-cert = /fixture/ca.pem
isolate-workers = false
max-clients = 2
max-same-clients = 1
rate-limit-ms = 0
keepalive = 60
dpd = 60
compression = false
tls-priorities = "NORMAL:-VERS-TLS1.0:-VERS-TLS1.1"
auth-timeout = 15
cookie-timeout = 15
persistent-cookies = false
rekey-time = 0
use-occtl = false
log-level = 2
device = owned
ipv4-network = 10.2.0.0
ipv4-netmask = 255.255.255.0
dns = 10.2.0.1
ping-leases = false
mtu = 1280
cisco-client-compat = false
dtls-psk = false
dtls-legacy = false
client-bypass-protocol = false
'''


def command(*parts: str, timeout: int = 30, include_stderr: bool = False) -> str:
    try:
        result = subprocess.run(parts, check=False, capture_output=True, text=True, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired) as failure:
        raise RuntimeError("Owned fixture command did not finish") from failure
    if result.returncode:
        # Never surface captured native/server output or staged credential data.
        raise RuntimeError("Owned fixture command failed")
    return result.stdout + result.stderr if include_stderr else result.stdout


def docker(*parts: str) -> str:
    # Docker forwards the container's stderr to its own stderr for `logs`.
    return command("docker", *parts, include_stderr=parts[0] == "logs").strip()


def certificates(root: Path, label: str) -> None:
    def openssl(*parts: str) -> None:
        command("openssl", *parts)

    def key(name: str) -> None:
        openssl("genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-256", "-out", str(root / f"{name}.key"))

    def ca(name: str, common_name: str) -> None:
        key(name)
        openssl("req", "-new", "-x509", "-sha256", "-days", "1", "-key", str(root / f"{name}.key"),
                "-subj", f"/CN={common_name}", "-addext", "basicConstraints=critical,CA:TRUE",
                "-addext", "keyUsage=critical,keyCertSign,cRLSign", "-out", str(root / f"{name}.pem"))

    def leaf(name: str, signer: str, common_name: str, usage: str, dns_name: str | None = None) -> None:
        key(name)
        openssl("req", "-new", "-sha256", "-key", str(root / f"{name}.key"), "-subj", f"/CN={common_name}",
                "-out", str(root / f"{name}.csr"))
        extensions = "basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\n" + f"extendedKeyUsage={usage}\n"
        if dns_name:
            extensions += f"subjectAltName=DNS:{dns_name}\n"
        (root / f"{name}.ext").write_text(extensions)
        openssl("x509", "-req", "-sha256", "-days", "1", "-in", str(root / f"{name}.csr"),
                "-CA", str(root / f"{signer}.pem"), "-CAkey", str(root / f"{signer}.key"), "-CAcreateserial",
                "-extfile", str(root / f"{name}.ext"), "-out", str(root / f"{name}.pem"))

    ca("ca", f"Owned-Gateway-CA-{label}")
    ca("origin-ca", f"Owned-Origin-CA-{label}")
    leaf("server", "ca", "fixture.invalid", "serverAuth", "fixture.invalid")
    leaf("client", "ca", f"Owned-Client-{label}", "clientAuth")
    leaf("origin", "origin-ca", "owned-route.test", "serverAuth", "owned-route.test")
    (root / "ocserv.conf").write_text(SERVER_CONFIG)
    # CapDrop=ALL excludes DAC override: the container must read this mount.
    # The parent session directory remains 0700 and all keys are synthetic.
    root.chmod(0o755)
    for path in root.iterdir():
        path.chmod(0o644)


def server() -> None:
    # Reuse the existing owned DNS, bind-after-auth and ocserv lifecycle fixture.
    # Its source is mounted read-only from this same checkout by the controller.
    import ocserv_owned_server as owned

    marker = os.environ.get("TACHIAI_OWNED_MARKER")
    if marker not in ("identity-A", "identity-B"):
        raise RuntimeError("Owned server identity is invalid")
    effective = next(line.split()[1] for line in Path("/proc/self/status").read_text().splitlines() if line.startswith("CapEff:"))
    if int(effective, 16) != ((1 << 12) | (1 << 6)):
        raise RuntimeError("Owned server capabilities differ from fixture contract")

    def bind_retry(sock: object, port: int) -> None:
        # The three test methods may run in any order. Wait across negative
        # setup tests until the first successful client's inner address exists.
        deadline = time.monotonic() + 180
        while True:
            try:
                sock.bind(("10.2.0.1", port))
                return
            except OSError:
                if time.monotonic() >= deadline:
                    raise RuntimeError("Owned inner service did not become ready") from None
                time.sleep(0.05)

    owned.bind_retry = bind_retry

    class Origin(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def do_GET(self) -> None:
            if self.path not in ("/", "/stream"):
                self.send_error(404)
                return
            streaming = self.path == "/stream"
            body = (marker + "\n").encode("ascii") if streaming else marker.encode("ascii") * 4096
            self.send_response(200)
            self.send_header("Content-Length", str(1 << 30 if streaming else len(body)))
            self.end_headers()
            try:
                if streaming:
                    print("owned-https-stream", flush=True)
                    # Deliberately unfinished until the route closes; its body
                    # is entirely fixed fixture data and uses bounded memory.
                    while True:
                        self.wfile.write(body)
                        self.wfile.flush()
                        time.sleep(0.01)
                else:
                    self.wfile.write(body)
                    print("owned-https-response", flush=True)
            except (BrokenPipeError, ConnectionResetError, ssl.SSLError):
                pass

        def log_message(self, *_: object) -> None:
            pass

    owned.Origin = Origin
    owned.main()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--temp-root", type=Path, required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--device", choices=DEVICES, default="standard")
    parser.add_argument("--image", required=True, help="Immutable sha256 ID of the owned ocserv image")
    arguments = parser.parse_args()
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", arguments.image):
        raise RuntimeError("An immutable owned ocserv image is required")
    os.umask(0o077)
    serial, avd = DEVICES[arguments.device]

    def adb(*parts: str, timeout: int = 10) -> str:
        return command(arguments.adb, "-s", serial, *parts, timeout=timeout)

    # First ADB action must identify the exact disposable AVD. Never infer a
    # device from whichever transport is attached or use tachiai-dev/a phone.
    if adb("emu", "avd", "name").splitlines() != [avd, "OK"]:
        raise RuntimeError("Disposable emulator AVD identity did not match")
    if arguments.device == "16k" and adb("shell", "getconf", "PAGE_SIZE").strip() != "16384":
        raise RuntimeError("Owned 16 KiB emulator has an unexpected page size")

    root = Path(tempfile.mkdtemp(prefix="android-openconnect-", dir=arguments.temp_root)).resolve()
    staged = root / "device"
    staged.mkdir()
    source = Path(__file__).resolve()
    owned_source = source.with_name("ocserv_owned_server.py")
    if not owned_source.is_file():
        raise RuntimeError("Existing owned server fixture is missing")
    containers: list[str] = []
    network = "tachiai-oc-jni-" + uuid.uuid4().hex
    network_created = False
    device_staged = False
    try:
        docker("network", "create", "--driver", "bridge", network)
        network_created = True
        for name, marker in (("a", "identity-A"), ("b", "identity-B")):
            fixture = root / name
            fixture.mkdir()
            certificates(fixture, name.upper())
            container = docker(
                "run", "--detach", "--cap-drop", "ALL", "--cap-add", "NET_ADMIN", "--cap-add", "SETGID",
                "--device", "/dev/net/tun", "--security-opt", "no-new-privileges", "--read-only",
                "--tmpfs", "/tmp:rw,nosuid,nodev,noexec,size=8m", "--network", network,
                "--publish", "127.0.0.1::4443/tcp", "--mount", f"type=bind,src={fixture},dst=/fixture,readonly",
                "--mount", f"type=bind,src={source},dst=/android_fixtures.py,readonly",
                "--mount", f"type=bind,src={owned_source},dst=/ocserv_owned_server.py,readonly",
                "--env", "PYTHONDONTWRITEBYTECODE=1", "--env", f"TACHIAI_OWNED_MARKER={marker}",
                "--entrypoint", "python3", arguments.image, "/android_fixtures.py", "--server",
            )
            containers.append(container)
            inspection = json.loads(docker("inspect", container))[0]
            host = inspection["HostConfig"]
            if (host["Privileged"] or host["NetworkMode"] != network or
                    set(host["CapAdd"]) != {"CAP_NET_ADMIN", "CAP_SETGID"} or host["CapDrop"] != ["ALL"]):
                raise RuntimeError("Owned gateway isolation differs from fixture contract")
            published = inspection["NetworkSettings"]["Ports"]["4443/tcp"]
            if len(published) != 1 or published[0]["HostIp"] != "127.0.0.1":
                raise RuntimeError("Owned gateway publication is not host-loopback-only")
            port = int(published[0]["HostPort"])
            if not 1 <= port <= 65535:
                raise RuntimeError("Owned gateway publication is invalid")
            deadline = time.monotonic() + 8
            while "initialized ocserv 1.1.6" not in docker("logs", container):
                if time.monotonic() >= deadline or not json.loads(docker("inspect", container))[0]["State"]["Running"]:
                    raise RuntimeError("Owned gateway did not become ready")
                time.sleep(0.05)
            if docker("exec", container, "ip", "route", "show", "default"):
                raise RuntimeError("Owned gateway retained an outbound default route")
            endpoint = f"https://fixture.invalid:{port}/"
            total = len(endpoint.encode()) + sum((fixture / value).stat().st_size for value in ("ca.pem", "client.pem", "client.key"))
            if total > 8192:
                raise RuntimeError("Owned profile exceeds the native import bound")
            (staged / f"{name}-endpoint.txt").write_text(endpoint)
            for target, original in (("ca.pem", "ca.pem"), ("cert.pem", "client.pem"), ("key.pem", "client.key"), ("origin-ca.pem", "origin-ca.pem")):
                (staged / f"{name}-{target}").write_bytes((fixture / original).read_bytes())
            if name == "a":
                (staged / "wrong-name-endpoint.txt").write_text(f"https://wrong.invalid:{port}/")

        # This path is reserved for the owned fixture. Remember it as soon as
        # mkdir succeeds so even partial pushes are removed on test failure.
        adb("shell", "mkdir", "-p", DEVICE_ROOT)
        device_staged = True
        for path in sorted(staged.iterdir()):
            adb("push", str(path), DEVICE_ROOT + "/" + path.name)
        result = adb("shell", "am", "instrument", "-w", "-r", "-e", "class", TEST_CLASS,
                     "-e", "openconnectOwnedRoot", DEVICE_ROOT,
                     "net.fstab.tachiai.test/androidx.test.runner.AndroidJUnitRunner", timeout=230)
        (root / "instrumentation.log").write_text(result)
        if "OK (3 tests)" not in result or any(value in result for value in ("FAILURES!!!", "INSTRUMENTATION_FAILED", "INSTRUMENTATION_ABORTED")):
            raise RuntimeError("Owned JNI instrumentation did not pass all three tests")
        for index, container in enumerate(containers):
            logs = docker("logs", container)
            if any(value not in logs for value in ("owned-dns-response", "owned-https-response")):
                raise RuntimeError("Owned encrypted DNS/HTTPS evidence is missing")
            if index == 0 and "owned-https-stream" not in logs:
                raise RuntimeError("Owned in-flight cancellation evidence is missing")
        print(f"Owned Android JNI/TLS/DNS/HTTPS/isolation/cancellation passed; logs: {root}")
    finally:
        cleanup_failed = False
        for index, container in enumerate(containers):
            try:
                (root / f"server-{index}.log").write_text(docker("logs", container))
            except RuntimeError:
                print("Owned gateway logs could not be collected", file=sys.stderr)
            try:
                docker("rm", "--force", container)
            except RuntimeError:
                cleanup_failed = True
        if network_created:
            try:
                docker("network", "rm", network)
            except RuntimeError:
                cleanup_failed = True
        if device_staged:
            try:
                adb("shell", "rm", "-rf", DEVICE_ROOT)
            except RuntimeError:
                cleanup_failed = True
        if cleanup_failed:
            raise RuntimeError(f"Owned fixture cleanup requires attention; inspect session logs: {root}")
        # Preserve synthetic inputs and private logs for session review.


if __name__ == "__main__":
    try:
        if sys.argv[1:] == ["--server"]:
            server()
        else:
            main()
    except RuntimeError as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1) from None
