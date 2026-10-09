#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""Owned encrypted routed-data proof; client never opens an OS TUN device."""

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time

from fixtures import certificates, command, profile


def docker(*arguments: str) -> str:
    return subprocess.check_output(["docker", *arguments], text=True, stderr=subprocess.STDOUT).strip()


def origin_certificate(root: Path) -> None:
    command(["openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:P-256",
             "-noenc", "-keyout", "origin-ca.key", "-out", "origin-ca.pem", "-days", "1",
             "-subj", "/CN=OwnedOriginCA", "-addext", "basicConstraints=critical,CA:TRUE",
             "-addext", "keyUsage=critical,keyCertSign,cRLSign"], root)
    command(["openssl", "req", "-new", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:P-256",
             "-noenc", "-keyout", "origin.key", "-out", "origin.csr", "-subj", "/CN=origin.owned-route.test",
             "-addext", "subjectAltName=DNS:origin.owned-route.test", "-addext", "extendedKeyUsage=serverAuth",
             "-addext", "keyUsage=critical,digitalSignature"], root)
    command(["openssl", "x509", "-req", "-in", "origin.csr", "-CA", "origin-ca.pem", "-CAkey", "origin-ca.key",
             "-CAcreateserial", "-out", "origin.pem", "-days", "1", "-copy_extensions", "copy"], root)


def server_config(root: Path, pending: bool, transport: str) -> None:
    protocol = "udp" if transport == "udp" else "tcp-server"
    (root / "server.conf").write_text(f'''port 1194
proto {protocol}
dev tun
topology subnet
server 10.50.0.0 255.255.255.0
ca /fixture/ca.pem
cert /fixture/OwnedServer.pem
key /fixture/OwnedServer.key
dh none
remote-cert-tls client
tls-version-min 1.2
push "dhcp-option DNS 10.50.0.1"
keepalive 1 3
data-ciphers AES-256-GCM
verb 3
''')
    if pending:
        with (root / "server.conf").open("a") as config:
            config.write("management /tmp/owned-management.sock unix\nmanagement-client-auth\nauth-user-pass-optional\nhand-window 600\n")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--image", required=True, help="Immutable local image ID from the pinned fixture Dockerfile")
    parser.add_argument("--go-binary", type=Path, required=True)
    parser.add_argument("--native-binary", type=Path)
    parser.add_argument("--temp-root", type=Path, required=True)
    parser.add_argument("--transport", choices=("udp", "tcp"), default="udp")
    arguments = parser.parse_args()
    if not arguments.image.startswith("sha256:"):
        parser.error("Use immutable built fixture image ID")
    os.umask(0o077)
    root = Path(tempfile.mkdtemp(prefix="native-routed-", dir=arguments.temp_root))
    containers: list[str] = []
    succeeded = False
    try:
        for index, marker in enumerate(("identity-A", "identity-B", "pending-auth")):
            fixture = root / str(index)
            fixture.mkdir()
            certificates(fixture)
            origin_certificate(fixture)
            server_config(fixture, index == 2, arguments.transport)
            # Synthetic fixture keys only. The enclosing session root remains
            # private; container root with DAC_OVERRIDE dropped needs read access.
            fixture.chmod(0o755)
            for path in fixture.iterdir():
                path.chmod(0o644)
            container = docker("run", "--detach", "--cap-drop", "ALL", "--cap-add", "NET_ADMIN",
                               "--device", "/dev/net/tun", "--security-opt", "no-new-privileges",
                               "--read-only", "--tmpfs", "/tmp:rw,nosuid,nodev,noexec,size=8m",
                               "--publish", f"127.0.0.1::1194/{arguments.transport}", "--mount",
                               f"type=bind,src={fixture},dst=/fixture,readonly",
                               "--env", f"TACHIAI_OWNED_MARKER={marker}", "--env",
                               f"TACHIAI_OWNED_PENDING={'1' if index == 2 else ''}", arguments.image)
            containers.append(container)
            inspection = json.loads(docker("inspect", container))[0]
            host = inspection["HostConfig"]
            if (host["Privileged"] or host["NetworkMode"] == "host" or
                    host["CapAdd"] != ["CAP_NET_ADMIN"] or host["CapDrop"] != ["ALL"]):
                raise RuntimeError("Owned server isolation differs from authorized fixture")
            published = inspection["NetworkSettings"]["Ports"][f"1194/{arguments.transport}"]
            if len(published) != 1 or published[0]["HostIp"] != "127.0.0.1":
                raise RuntimeError("Owned server port is not loopback-only")
            deadline = time.monotonic() + 8
            while "OWNED_READY" not in docker("logs", container):
                if time.monotonic() >= deadline or not json.loads(docker("inspect", container))[0]["State"]["Running"]:
                    raise RuntimeError("Owned server did not become ready")
                time.sleep(0.05)
            client_profile = profile(fixture, int(published[0]["HostPort"]))
            if arguments.transport == "tcp":
                client_profile = client_profile.replace("proto udp\n", "proto tcp-client\n")
            (fixture / "client.ovpn").write_text(client_profile)
        if arguments.native_binary:
            subprocess.run([str(arguments.native_binary.resolve()), str(root / "2" / "client.ovpn"), "pending"],
                           check=True, timeout=5)
        environment = os.environ | {"TACHIAI_OPENVPN_ROUTED_ROOT": str(root)}
        process = subprocess.Popen([str(arguments.go_binary.resolve()), "-test.run", "^TestOpenVPNNativeRoutedData$",
                                    "-test.timeout", "30s"], env=environment)
        deadline = time.monotonic() + 35
        disconnected = False
        try:
            while process.poll() is None:
                if (root / "disconnect-second").exists() and not disconnected:
                    docker("stop", "--time", "1", containers[1])
                    disconnected = True
                if time.monotonic() >= deadline:
                    process.kill()
                    raise RuntimeError("Owned routed fixture exceeded bound")
                time.sleep(0.02)
            if process.returncode:
                raise RuntimeError("Owned routed Go fixture failed")
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=3)
        if not disconnected:
            raise RuntimeError("During-traffic disconnect fixture did not run")
        for index, container in enumerate(containers):
            logs = docker("logs", container)
            (root / f"server-{index}.log").write_text(logs)
            if index == 2 and "OWNED_AUTH_PENDING_SENT" not in logs:
                raise RuntimeError("Owned AUTH_PENDING evidence missing")
            if index < 2 and ("OWNED_DNS" not in logs or "OWNED_HTTPS" not in logs):
                raise RuntimeError("Owned encrypted DNS/HTTPS evidence missing")
        succeeded = True
        print(f"Owned outer {arguments.transport.upper()} encrypted DNS/TCP/HTTPS, distinct tunnel identities, TLS rejection and traffic cancellation passed")
    finally:
        for index, container in enumerate(containers):
            try:
                (root / f"server-{index}.log").write_text(docker("logs", container))
            except subprocess.CalledProcessError:
                pass
            try:
                docker("rm", "--force", container)
            except subprocess.CalledProcessError:
                pass
        if succeeded:
            shutil.rmtree(root)
        else:
            print(f"Synthetic-only failure fixture preserved: {root}")


if __name__ == "__main__":
    main()
