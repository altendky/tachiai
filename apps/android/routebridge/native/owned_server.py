#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""Synthetic origin and explicit DNS on an owned server's private tunnel IP."""

import http.server
import os
from pathlib import Path
import signal
import socket
import ssl
import struct
import subprocess
import threading
import time


ADDRESS = "10.50.0.1"
ROOT = Path("/fixture")
MARKER = os.environ["TACHIAI_OWNED_MARKER"].encode()


def pending_auth() -> None:
    endpoint = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    deadline = time.monotonic() + 5
    while True:
        try:
            endpoint.connect("/tmp/owned-management.sock")
            break
        except OSError:
            if time.monotonic() >= deadline:
                raise
            time.sleep(0.01)
    endpoint.sendall(b"version 5\n")
    pending = None
    with endpoint, endpoint.makefile("rb") as messages:
        for line in messages:
            if line.startswith(b">CLIENT:CONNECT,"):
                _, cid, kid = line.strip().split(b",")
                pending = (cid, kid)
            elif line.strip() == b">CLIENT:ENV,END" and pending:
                cid, kid = pending
                endpoint.sendall(b"client-pending-auth " + cid + b" " + kid + b" CR_TEXT:owned-fixture 600\n")
                pending = None
            elif b"SUCCESS:" in line and b"pending" in line.lower():
                print("OWNED_AUTH_PENDING_SENT", flush=True)
            elif line.startswith(b"ERROR:"):
                raise RuntimeError("Owned management AUTH_PENDING command refused")


def answer(query: bytes) -> bytes:
    if len(query) < 17:
        return b""
    offset = 12
    labels = []
    while offset < len(query) and query[offset]:
        size = query[offset]
        if size > 63 or offset + 1 + size > len(query):
            return b""
        labels.append(query[offset + 1:offset + 1 + size])
        offset += size + 1
    if offset + 5 != len(query):
        return b""
    kind, family = struct.unpack("!HH", query[offset + 1:])
    valid = b".".join(labels) == b"origin.owned-route.test" and kind == 1 and family == 1
    question = query[12:]
    reply = query[:2] + struct.pack("!HHHHH", 0x8180 if valid else 0x8183, 1, int(valid), 0, 0) + question
    if valid:
        reply += b"\xc0\x0c" + struct.pack("!HHIH", 1, 1, 0, 4) + socket.inet_aton(ADDRESS)
        print("OWNED_DNS", flush=True)
    return reply


def udp_dns() -> None:
    endpoint = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    endpoint.bind((ADDRESS, 53))
    while True:
        query, peer = endpoint.recvfrom(8192)
        reply = answer(query)
        if reply:
            endpoint.sendto(reply, peer)


def receive_exact(connection: socket.socket, count: int) -> bytes:
    result = b""
    while len(result) < count:
        part = connection.recv(count - len(result))
        if not part:
            raise EOFError
        result += part
    return result


def tcp_dns() -> None:
    endpoint = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    endpoint.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    endpoint.bind((ADDRESS, 53))
    endpoint.listen(16)
    while True:
        connection, _ = endpoint.accept()
        with connection:
            connection.settimeout(3)
            try:
                size = struct.unpack("!H", receive_exact(connection, 2))[0]
                if size > 8192:
                    continue
                reply = answer(receive_exact(connection, size))
                connection.sendall(struct.pack("!H", len(reply)) + reply)
            except (OSError, EOFError):
                pass


class Origin(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_arguments: object) -> None:
        pass

    def do_GET(self) -> None:
        print("OWNED_HTTPS", flush=True)
        if self.path == "/stream":
            self.send_response(200)
            self.send_header("Content-Length", str(1024 * 1024 * 1024))
            self.end_headers()
            try:
                while True:
                    self.wfile.write(MARKER + b"\n")
                    self.wfile.flush()
                    time.sleep(0.01)
            except OSError:
                return
        else:
            # Larger than one packet, to exercise segmentation and reassembly.
            payload = MARKER * 4096
            self.send_response(200)
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)


def main() -> None:
    capabilities = next(line.split()[1] for line in Path("/proc/self/status").read_text().splitlines()
                        if line.startswith("CapEff:"))
    if int(capabilities, 16) != 1 << 12:  # CAP_NET_ADMIN only.
        raise RuntimeError("Owned server effective capabilities differ from fixture contract")
    process = subprocess.Popen(["openvpn", "--config", str(ROOT / "server.conf")])
    signal.signal(signal.SIGTERM, lambda *_arguments: process.terminate())
    try:
        if os.environ.get("TACHIAI_OWNED_PENDING"):
            threading.Thread(target=pending_auth, daemon=True).start()
        deadline = time.monotonic() + 5
        while True:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
                try:
                    probe.bind((ADDRESS, 0))
                    break
                except OSError:
                    if process.poll() is not None or time.monotonic() >= deadline:
                        raise RuntimeError("Owned server tunnel unavailable")
                    time.sleep(0.01)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(ROOT / "origin.pem", ROOT / "origin.key")
        origin = http.server.ThreadingHTTPServer((ADDRESS, 443), Origin)
        origin.socket = context.wrap_socket(origin.socket, server_side=True)
        threading.Thread(target=udp_dns, daemon=True).start()
        threading.Thread(target=tcp_dns, daemon=True).start()
        threading.Thread(target=origin.serve_forever, daemon=True).start()
        print("OWNED_READY", flush=True)
        process.wait()
    finally:
        if process.poll() is None:
            process.terminate()
            process.wait(timeout=3)


if __name__ == "__main__":
    main()
