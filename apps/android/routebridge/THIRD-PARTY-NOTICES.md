# Android route bridge dependencies

This Android-only bridge embeds upstream networking implementations; Tachiai
does not copy WireGuard cryptography or provider algorithms. The reproducible
versions and integrity hashes are in `go.mod` and `go.sum`.

- WireGuard Go, WireGuard LLC: MIT, `licenses/WIREGUARD-MIT.txt`.
- gVisor netstack, The gVisor Authors: Apache-2.0 and BSD-3-Clause portions,
  `licenses/GVISOR-Apache-2.0.txt` and `licenses/GO-BSD-3-Clause.txt`.
- Go runtime and golang.org/x/mobile, x/crypto, x/net, x/sys, x/time,
  The Go Authors: BSD-3-Clause, `licenses/GO-BSD-3-Clause.txt`.
  The SOCKS5 backend imports the pinned x/net/proxy client; it adds no module
  or separate cryptographic implementation.
- github.com/google/btree, Google: Apache-2.0,
  `licenses/BTREE-Apache-2.0.txt`.

Only transitively imported packages are compiled into the AAR. The module
graph also includes build tools and platform-specific modules that are not
Android runtime code. Both AAR ABIs are compiled from the same pinned module
graph; generated Java/native binaries remain in the ignored `build` directory.

The certificate-only OpenVPN backend is documented separately
in `native/README.md`. Its pinned OpenVPN3 root notice offers MPL-2.0
(`licenses/OPENVPN3-MPL-2.0.txt`). The official 2024 global relicensing grant and
the retained older header notices are documented in `native/README.md`;
upstream notices are preserved without relabeling them. Standalone Asio is under Boost-1.0
(`licenses/ASIO-Boost-1.0.txt`). The Android AAR statically links these
dependencies; its accompanying source archive preserves the exact source,
adapter, build recipe and notices.

The Android native backend pins OpenSSL 3.5.5 (Apache-2.0,
`licenses/OPENSSL-Apache-2.0.txt`), fmt 10.1.0 (MIT,
`licenses/FMT-MIT.txt`) and only LZ4 1.10.0's library (BSD-2-Clause,
`licenses/LZ4-BSD-2-Clause.txt`, not its GPL tools). Static NDK30 libc++, libc++abi
and unwind notices are preserved in the full vendor
`licenses/NDK30-LLVM-NOTICE.txt`. `native/README.md` and the native build helper
identify immutable source archives, hashes and the accompanying source payload.
