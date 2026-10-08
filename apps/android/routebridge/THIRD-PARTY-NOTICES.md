# Android route bridge dependencies

This Android-only bridge embeds upstream networking implementations; Tachiai
does not copy WireGuard cryptography or provider algorithms. The reproducible
versions and integrity hashes are in `go.mod` and `go.sum`.

- WireGuard Go, WireGuard LLC: MIT, `licenses/WIREGUARD-MIT.txt`.
- gVisor netstack, The gVisor Authors: Apache-2.0 and BSD-3-Clause portions,
  `licenses/GVISOR-Apache-2.0.txt` and `licenses/GO-BSD-3-Clause.txt`.
- Go runtime and golang.org/x/mobile, x/crypto, x/net, x/sys, x/time,
  The Go Authors: BSD-3-Clause, `licenses/GO-BSD-3-Clause.txt`.
- github.com/google/btree, Google: Apache-2.0,
  `licenses/BTREE-Apache-2.0.txt`.

Only transitively imported packages are compiled into the AAR. The module
graph also includes build tools and platform-specific modules that are not
Android runtime code. Both AAR ABIs are compiled from the same pinned module
graph; generated Java/native binaries remain in the ignored `build` directory.
