#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""Owned loopback TLS/profile fixtures. OpenVPN server uses dev null, no OS TUN.

This proves native negotiation and packet descriptor setup, not routed traffic.
All certificates/keys are generated inside the supplied session temporary root.
"""

import argparse
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time


def command(arguments: list[str], root: Path) -> None:
    subprocess.run(arguments, cwd=root, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def certificates(root: Path) -> None:
    for name in ("ca", "other-ca"):
        command(["openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:P-256",
                 "-noenc", "-keyout", f"{name}.key", "-out", f"{name}.pem", "-days", "1",
                 "-subj", f"/CN=Owned-{name}", "-addext", "basicConstraints=critical,CA:TRUE",
                 "-addext", "keyUsage=critical,keyCertSign,cRLSign"], root)
    for name, role in (("OwnedServer", "serverAuth"), ("OwnedClient", "clientAuth"), ("WrongRole", "clientAuth")):
        command(["openssl", "req", "-new", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:P-256",
                 "-noenc", "-keyout", f"{name}.key", "-out", f"{name}.csr", "-subj", f"/CN={name}",
                 "-addext", f"extendedKeyUsage={role}", "-addext", "keyUsage=critical,digitalSignature"], root)
        command(["openssl", "x509", "-req", "-in", f"{name}.csr", "-CA", "ca.pem", "-CAkey", "ca.key",
                 "-CAcreateserial", "-out", f"{name}.pem", "-days", "1", "-copy_extensions", "copy"], root)


def profile(root: Path, port: int, ca: str = "ca", name: str = "OwnedServer") -> str:
    content = (f"client\ndev tun\nproto udp\nremote 127.0.0.1 {port}\nnobind\n"
               f"remote-cert-tls server\nverify-x509-name {name} name\ntls-version-min 1.2\n")
    for block, filename in (("ca", f"{ca}.pem"), ("cert", "OwnedClient.pem"), ("key", "OwnedClient.key")):
        content += f"<{block}>\n{(root / filename).read_text()}</{block}>\n"
    return content


def port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as endpoint:
        endpoint.bind(("127.0.0.1", 0))
        return endpoint.getsockname()[1]


def fixture(binary: Path, root: Path, contents: str, mode: str, go_binary: Path | None = None,
            run_native: bool = True) -> None:
    path = root / "client.ovpn"
    path.write_text(contents)
    if run_native:
        subprocess.run([str(binary), str(path), mode], check=True, timeout=15)
    if go_binary:
        environment = os.environ | {"TACHIAI_OPENVPN_FIXTURE_PROFILE": str(path),
                                    "TACHIAI_OPENVPN_FIXTURE_MODE": mode}
        test = "TestOpenVPNNativePreparationCancellation" if mode == "policy" else "TestOpenVPNNativeOwnedFixture"
        subprocess.run([str(go_binary), "-test.run", f"^{test}$", "-test.timeout", "10s"],
                       check=True, timeout=15, env=environment)
        if mode == "policy":
            subprocess.run([str(go_binary), "-test.run", "^TestOpenVPNNativeUnconfirmedCleanup$", "-test.timeout", "10s"],
                           check=True, timeout=15, env=environment)


def server(binary: Path, root: Path, role: str, case: tuple[str, str, str], go_binary: Path | None,
           run_native: bool = True) -> None:
    endpoint = port()
    config = root / "server.conf"
    config.write_text(f"""local 127.0.0.1
port {endpoint}
proto udp
dev null
tls-server
ca {root}/ca.pem
cert {root}/{role}.pem
key {root}/{role}.key
dh none
remote-cert-tls client
tls-version-min 1.2
ifconfig 10.50.0.1 255.255.255.0
topology subnet
push "topology subnet"
push "ifconfig 10.50.0.2 255.255.255.0"
push "dhcp-option DNS 10.50.0.1"
data-ciphers AES-256-GCM
cipher AES-256-GCM
verb 3
float
""")
    with (root / "server-process.log").open("w") as output:
        process = subprocess.Popen(["openvpn", "--config", str(config)], stdout=output, stderr=output)
        try:
            deadline = time.monotonic() + 3
            while True:
                if process.poll() is not None:
                    raise RuntimeError("Owned dev-null server exited; inspect session fixture log")
                with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
                    try:
                        probe.bind(("127.0.0.1", endpoint))
                    except OSError:
                        break
                if time.monotonic() >= deadline:
                    raise RuntimeError("Owned server failed to bind")
                time.sleep(0.01)
            ca, name, mode = case
            fixture(binary, root, profile(root, endpoint, ca, name), mode, go_binary, run_native)
        finally:
            process.terminate()
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=3)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--temp-root", type=Path, required=True)
    parser.add_argument("--go-binary", type=Path)
    arguments = parser.parse_args()
    os.umask(0o077)
    # Preserve synthetic-only fixture files on failure for bounded debugging.
    root = Path(tempfile.mkdtemp(prefix="native-owned-", dir=arguments.temp_root))
    certificates(root)
    go_binary = arguments.go_binary.resolve() if arguments.go_binary else None
    # Keep an owned UDP endpoint bound without replying. A closed port could
    # fail immediately via ICMP and would not prove connection timeout/cancel.
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as blackhole:
        blackhole.bind(("127.0.0.1", 0))
        fixture(arguments.binary.resolve(), root, profile(root, blackhole.getsockname()[1]), "policy", go_binary)
    for case in [("ca", "OwnedServer", "connected"), ("ca", "WrongName", "rejected"),
                 ("other-ca", "OwnedServer", "rejected")]:
        server(arguments.binary.resolve(), root, "OwnedServer", case, None)
        if go_binary:
            server(arguments.binary.resolve(), root, "OwnedServer", case, go_binary, False)
    server(arguments.binary.resolve(), root, "WrongRole", ("ca", "WrongRole", "rejected"), None)
    if go_binary:
        server(arguments.binary.resolve(), root, "WrongRole", ("ca", "WrongRole", "rejected"), go_binary, False)
    import shutil
    shutil.rmtree(root)


if __name__ == "__main__":
    main()
