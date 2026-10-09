#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""Server-only owned ocserv fixture; never included in Android artifacts."""

import http.server
import os
import signal
import socket
import ssl
import struct
import subprocess
import threading
import time


def bind_retry(sock: socket.socket, port: int) -> None:
    # ocserv creates its server-side address only after client authentication.
    deadline = time.monotonic() + 20
    while True:
        try:
            sock.bind(("10.2.0.1", port))
            return
        except OSError:
            if time.monotonic() >= deadline:
                raise
            time.sleep(0.05)


def dns() -> None:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    bind_retry(sock, 53)
    print("owned-dns-ready", flush=True)
    while True:
        query, peer = sock.recvfrom(512)
        if len(query) < 12 or query[4:6] != b"\x00\x01":
            continue
        offset = 12
        labels = []
        while offset < len(query) and query[offset]:
            size = query[offset]
            if size > 63 or offset + 1 + size > len(query):
                break
            labels.append(query[offset + 1:offset + 1 + size])
            offset += size + 1
        if offset + 5 != len(query):
            continue
        question = query[12:]
        record_type, record_class = struct.unpack("!HH", query[offset + 1:])
        answer = b""
        if labels == [b"owned-route", b"test"] and record_type == 1 and record_class == 1:
            answer = b"\xc0\x0c" + struct.pack("!HHIH", 1, 1, 0, 4) + socket.inet_aton("10.2.0.1")
        response = query[:2] + struct.pack("!HHHHH", 0x8180, 1, bool(answer), 0, 0) + question + answer
        sock.sendto(response, peer)
        print("owned-dns-response", flush=True)


class Origin(http.server.BaseHTTPRequestHandler):
    def do_GET(self) -> None:
        body = os.environ["TACHIAI_OWNED_MARKER"].encode("ascii")
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
        print("owned-https-response", flush=True)

    def log_message(self, *_: object) -> None:
        pass


def https() -> None:
    server = http.server.ThreadingHTTPServer(("", 0), Origin, bind_and_activate=False)
    bind_retry(server.socket, 443)
    server.server_activate()
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain("/fixture/origin.pem", "/fixture/origin.key")
    server.socket = context.wrap_socket(server.socket, server_side=True)
    print("owned-https-ready", flush=True)
    server.serve_forever()


def main() -> None:
    # Only container routes change. Remove outbound default access while keeping
    # its private bridge prefix for Docker's loopback bootstrap port mapping.
    subprocess.run(["ip", "route", "del", "default"], check=True)
    process = subprocess.Popen(["/usr/sbin/ocserv", "-f", "-d", "2", "-c", "/fixture/ocserv.conf"])
    for action in (dns, https):
        threading.Thread(target=action, daemon=True).start()
    signal.signal(signal.SIGTERM, lambda *_: process.terminate())
    raise SystemExit(process.wait())


if __name__ == "__main__":
    main()
