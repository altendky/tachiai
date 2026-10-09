# OpenConnect native backend and owned fixtures

This directory supplies the bounded certificate-only OpenConnect backend for
issue #52, its pinned native builds and owned host/Android fixtures. The client
uses an owned datagram socketpair and private userspace stack, without a system
TUN, vhost or OS route. The separate disposable ocserv server fixture alone uses
TUN with the approved NET_ADMIN/SETGID capabilities; it changes no host routes.
All fixture credentials and endpoints are owned synthetic inputs. Provider
authentication, DRM and reviewed destination policies are unchanged.

## Reproduce

Run `apps/android/openconnect-fixture/run-host.sh /absolute/disposable/cache` on
a Linux host with a C compiler, make, pkg-config, patch, curl, tar, timeout and
development packages for GnuTLS, libxml2 and zlib. Loopback TCP/socketpair access
is required. The script downloads only the official HTTPS release archive when
absent and verifies its SHA-256 before extraction. It preserves each fresh
source/build/log directory in the selected cache for review and relinking.
It never installs a library or changes the system OpenConnect version.

- Source: [OpenConnect 9.21 release archive](https://www.infradead.org/openconnect/download/openconnect-9.21.tar.gz).
- SHA-256: `5b32369467db6e5f317aa1ed12cfcbb81ed00bdbc765450b6bfcbdc300944a58`.
- The hash pins downloaded bytes; no independent upstream signature-verification
  result is claimed. Host TLS/dependency packages are recorded environment
  inputs, not pinned Android dependency artifacts.
- GnuTLS stays thread-enabled. Optional libproxy, GSSAPI, stoken, PSKC, PC/SC,
  TSS2, external browser and vhost are disabled. Library construction leaves
  its script pointer unset; no script/device setup API is called. The configure
  script requires a default script path even for this embedding, so it receives
  a nonexistent path. Both DTLS and compression are disabled per session.
- TLS is restricted to TLS 1.2/1.3. Only an explicit owned CA is trusted. The
  failed-certificate callback always rejects. The form/config callbacks are
  null and HTTP authentication is disabled. Resolver callbacks accept only the
  configured fixture authority/port and return numeric loopback addresses.

The harness generates its CA, server/client certificates and private keys in
memory. Client-facing credential inputs are sealed anonymous `memfd` objects;
no PEM or private-key file is written. The initial host proof reopened them via
`/proc/self/fd/<fd>`. The Android backend uses the direct bounded-read adaptation
described below.
All progress logging is suppressed. The VPN session cookie is synthetic,
runtime-only and never emitted or persisted. The fixture uses a separate
thread-confined `openconnect_info` for each lifecycle; only command-pipe writes
come from the controlling thread.

## Proven host behavior and native adaptations

The unmodified constructor/free path closes an unrelated owned FD 0 sentinel
when no command pipe was created. `0001-initialize-command-writer.patch` gives
`cmd_fd_write` its intended invalid default. The reproducible script first
requires the unmodified probe's specific failure, then requires the corrected
probe to preserve FD 0. A second case forces actual command-pipe creation
failure by temporarily reducing the fixture process's FD limit to zero, then
restores its limit and checks the unrelated sentinel survived cleanup too.

With GnuTLS 3.8.12, the unmodified TLS session flags let a stalled handshake wait
inside GnuTLS despite the nonblocking transport. A cancellation written after
the owned server receives ClientHello misses the fixture's five-second join.
`0002-use-nonblocking-gnutls-handshake.patch` adds `GNUTLS_NONBLOCK`, leaving trust,
certificate validation, ciphers and protocol unchanged. The script requires
this specific regression before the patch and successful bounded cancellation
after it. GnuTLS recommends that flag for asynchronous TLS operation.
[GnuTLS asynchronous operation](https://gnutls.org/manual/html_node/Asynchronous-operation.html).

The positive harness repeats six cases three times: certificate-only XML
authentication and CSTP packet roundtrip; cancellation while an auth response
is pending; wrong server hostname; wrong CA; unsupported password form; and
cancellation during a stalled TLS handshake. The owned server verifies that
the actual client certificate was presented and trusted. It accepts the normal
TLS reconnect between authentication and CSTP, exercising certificate reload
from the still-open memory FDs. Only `openconnect_obtain_cookie() == 0` is success:
cancellation returns **positive 1**. Mainloop cancellation returns `-EINTR`.

`openconnect_setup_tun_fd()` accepts one end of a caller-owned `AF_UNIX`
`SOCK_DGRAM` pair and sets NONBLOCK/CLOEXEC on it. The test checks byte-for-byte
raw IPv4 datagrams in both directions through genuine library CSTP framing.
The public command pipe interrupts the native worker. The library closes its
command pipe when freed, but does **not** close either caller-owned datagram
endpoint, including after mainloop cancellation. The harness checks those FDs
remain valid, closes them explicitly and checks the complete lifecycle's FD
inventory has no new retained descriptor identities. The inventory compares
target/inode identities so closing an old FD cannot mask a newly leaked one;
an owned negative case checks this. No worker frees another worker's session.

The public command-pipe API does not mark both endpoints CLOEXEC. The original
harness marks its visible writer; the restricted adapter patches pipe creation
to use `pipe2(O_CLOEXEC)` and checks both ends. No child process is executed.
The datagram pair also needs explicit closure/disconnect signaling: Unix
datagrams do not reliably signal peer EOF.

## Backend scope and staged evidence

### Internal adapter

`adapter.h`, `adapter.c` and `memory_file.c` implement an internal owned C ABI.
`toc_create` validates required CA and matching client certificate/unencrypted
private key, an ASCII HTTPS endpoint without credentials/query/fragment, an
explicit numeric IPv4 bootstrap, and an **8 KiB total profile byte bound**.
It creates sealed anonymous credential FDs, a cancellation channel and a
datagram pair before connecting. There is no host-DNS or direct-dial fallback.
It accepts only the identifiable restricted 9.21 build.

`toc_take_packet_fd` transfers the peer once. The adapter owns the native end,
credential FDs and single native worker. The owner cancels, joins, explicitly
closes its packet peer/bridge and then destroys the session. `toc_destroy`
refuses an unjoined worker. Timeouts preserve ownership so the owner can retry
join; they never free an active worker. Cancel writes and library free share a
mutex, including after native failure. The caller must exclude further calls
before destroying the opaque handle. Only fixed enum statuses and a numeric
IPv4/DNS/MTU snapshot cross the ABI; no cookie, certificate detail or endpoint is
returned or logged. Caller-owned profile buffers are not wiped by this ABI.
OpenConnect/GnuTLS keep runtime copies; secure wiping of every upstream heap
allocation is not established.

The dedicated policy patches reject **all redirects**, before endpoint mutation
or a new TLS connection; auth forms, SSO/browser/CSD/host-scan and DTD input,
before authentication state can execute; and the upstream legacy GET fallback
after XML rejection. XML parsing disallows external network access and bounds
the response before tree allocation. Config persistence callbacks are absent.
HTTP auth, DTLS, compression, IPv6 and optional external helpers stay disabled.
The initial snapshot requires explicit unicast IPv4 DNS, MTU 576..9000, and no
IPv6/split-route/split-DNS/PAC settings. Post-establishment reconnect/rekey
reconnects fail closed because initial packet-stack parameters are immutable.
Ordinary auth-to-CSTP TLS reconnection to the exact original endpoint remains
supported. A temporary XML session-token allocation discovered by LeakSanitizer
is now cleared and freed after the library copies it.

The earlier adapter fixture repeated twelve cases three times: the original six plus
SSO, modern host-scan, legacy CSD, cross-authority redirect, HTTP Basic challenge
and DTD rejection. It also checks pre-start cancellation, active-worker destroy
refusal, one-time packet ownership transfer, failed input/key validation and
sealed-memory mutation rejection. Two concurrent sessions use independently
generated CAs/credentials and distinct raw packet identifiers, proving those
owned native sessions do not exchange each other's credential/packet state.
These raw-packet cases are separate from the subsequent userspace DNS/HTTPS
and actual ocserv isolation results below.

### Android anonymous memory probe

`check-android-memory.sh /absolute/read-only/ndk /absolute/disposable/cache`
compiles only the memory helper for API 26, arm64 and x86_64. The probe passed
with NDK 30.0.16248370: neither ELF requires bionic's API 30 `memfd_create`
symbol, and both have 16 KiB-aligned LOAD segments. The implementation calls
`syscall(__NR_memfd_create)` with CLOEXEC and sealing. Actual Android SELinux
denied the initial procfs-reopen path. `0011-read-sealed-credential-fds.patch`
adds direct `pread` loading of the adapter's owned sealed FDs inside the library,
with explicit FD lifetime through auth-to-CSTP reconnection, bounded PEM data and
wiping of temporary read buffers. It does not establish wiping every upstream
heap copy. Syscall, sealing or read failure closes resources and fails;
there is **no plaintext disk substitute**. The actual JNI credential path passed
on both Android page-size fixtures recorded below.

### Full Android native build

`build-android-deps.sh NDK CACHE aarch64` and the equivalent `x86_64` invocation
build all pinned native inputs in separate disposable prefixes with API 26.
`normalize-android-libs.py BUILD NDK ABI` then re-links Nettle and zlib with
unversioned Android SONAMEs, removes generated libtool cache RUNPATH and verifies
the exact dependency closure. It copies eight ordinary shared libraries into
`BUILD/jni-libs`: `libgmp.so`, `libnettle.so`, `libhogweed.so`, `libgnutls.so`,
`libxml2.so`, `libz.so`, `libopenconnect.so` and `libtachiai_openconnect.so`.
Each component remains dynamically separated and replaceable; no ELF binary
rewriting is used. The normalization step is required: Nettle defaults to
versioned SONAMEs, and zlib's configure silently chooses static-only when LLD
rejects its shared-link probe's undefined version-script assignments. The step
explicitly configures a real zlib shared linker and rejects static substitution.

When only OpenConnect patches or the C adapter change, the offline
`refresh-android-openconnect.sh NDK CACHE ABI DEPENDENCY_BUILD` path reuses a
previously reviewed normalized dependency build. It requires every pinned source
archive in `CACHE`, checks the six dependency libraries match that build's
headers/link prefix and copies them without modifying the dependency build.
OpenConnect is freshly extracted and receives **all** patches in sorted order,
including HTTP truncation/framing/header/read bounds in `0007`–`0010` and direct
sealed-FD credential loading in `0011`. OpenConnect and the adapter are
cross-compiled for API 26 in a new disposable prefix; generated
libtool recipes suppress cache RUNPATH before linking. The original full build
and normalization commands remain the clean rebuild path for CI or dependency
changes. Reusing arbitrary binary libraries without their exact sources and
recorded build/relink inputs is outside this refresh contract.

Both paths use `verify-android-libs.py PACKAGE NDK ABI` to require exactly eight
ordinary `.so` files, the selected ELF machine, exact SONAMEs and packaged/system
dependency closure, no RPATH/RUNPATH, nonempty 16 KiB-aligned LOAD segments and no
strong API 30 `memfd_create` dependency. NDK 30's arm64 compiler runtime can add
an optional weak reference as an API-level presence check; its absent-symbol
branch is permitted. The adapter itself uses the syscall, with no such bionic
symbol. The package retains per-library ELF reports and
`SHA256SUMS`. These are static compile/link checks; Android load, JNI, APK ZIP and
16 KiB runtime results must be recorded separately.

The refresh retains its full patched source, object/generated files, configure
arguments and logs, a snapshot of the exact adapter/patch/build/license inputs,
input checksums, NDK version and the referenced immutable dependency build path.
Retain that dependency build's sources, objects, link recipes and notices plus
the verified release archives as well. A refresh tree alone is not the complete
corresponding source or relinking material for all eight LGPL/other components;
the application/source companion must include the matching material and
replacement instructions before distribution.

For local artifact-cache reuse, `native-inputs.sha256` records relative-path
hashes of the pinned source manifest, every patch, adapter/header/memory inputs
and fetch/full-build/refresh/normalize/verify recipes. `build-inputs.sha256`
records the hashes of that manifest, `ndk-source.properties` and `ABI`.
`native-input-fingerprint.txt` hashes `build-inputs.sha256` and is published only
after successful package verification. Recompute these manifests from current
inputs and require the same fingerprint, then verify package `SHA256SUMS` and
ELF checks again; a cache path or successful earlier build is insufficient.
This fingerprint identifies native inputs, not corresponding-source completeness
or runtime compatibility.

Both complete native closure builds and normalization passed with the existing
read-only NDK 30.0.16248370. The adapter links with `--no-undefined`. Every one of
the eight packaged ELFs has its exact filename as SONAME, only packaged/system
dependencies, no RPATH/RUNPATH and 16 KiB LOAD alignment. These static checks are
separate from the actual JNI runtime results below. The initial host
fixture still uses its recorded host dependency versions; replacing its TLS
library alone with a minimal no-PKCS11 GnuTLS build is incompatible with the
host OpenConnect configuration's detected PKCS11 symbols. That failed substitution
is not a current-GnuTLS runtime validation claim. The complete Android closure
configures against its own matching dependency headers and libraries.

The compiler's actual dependency sidecars can be inventoried with
`audit-dependencies.py BUILD BUILD/license-inventory.json`. This flags GNU GPL
notices without a Lesser/Library alternative for human review and retains
the included SDK/header paths. It excludes executable/test directories and is
not a complete legal SBOM. A separate source scan found GPL-only GnuTLS OpenSSL
compatibility files (disabled and unlinked) and Nettle's `twofishdata.c` table
generator (unlinked). The selected GnuTLS library, included libtasn1/unistring,
gnulib headers, Nettle library and GMP notices inspected use Lesser or dual
licensing; no hidden GPL-only library-header conflict has been found.

`bundle-sources.sh CACHE` packages verified exact release archives, C ABI/fixture
sources, patches, build/relink scripts and retained notices as a local source
companion with a checksum. Publish that matching downloadable companion beside
any distributed native artifact. It does not contain an Android application,
JNI binding or APK; an integrated distribution additionally needs its exact
corresponding application/build/relink materials and applicable installation
instructions. No signing keys or credentials belong in these companions.

The initial proof ran on Linux 7.0.0-34-generic, x86_64, with GnuTLS 3.8.12,
libxml2 2.15.2 and zlib 1.3.1. Eighteen adapted lifecycle cases passed with no FD
count changes, including a metadata-only syscall trace. The trace excludes
payloads and credential buffers. It showed only loopback TCP connects and no
TUN/vhost device opens. Construction cleanup passed after reproducing its
unmodified failure. These initial executable host results are separate from
source inspection and the subsequent Android runtime observations.

The initial minimal owned AnyConnect server was **not ocserv interoperability evidence**.
Its raw IPv4 payload is deliberately synthetic, not a working TCP/DNS netstack
or provider request. It does not prove concurrent independent route stacks,
destination DNS isolation, real HTTPS traffic through the tunnel, rekey,
reconnect/snapshot updates, certificate expiry/revocation, arbitrary cancellation
timing, Android memfd/proc runtime access, full Android dependency ABI linking,
or 16 KiB runtime compatibility. Sanitizer results for the owned adapter do not
imply an instrumented upstream dependency suite.

The subsequent Go owner uses the reviewed common userspace packet bridge,
a preparation handle created before network work and an independent 30-second
deadline. Cancellation closes the bridge and joins the native worker before
destroying it; unconfirmed cleanup retains the handle and blocks another
OpenConnect initialization until process shutdown. Only fixed failure statuses
cross the public API. HTTPS origin admission and no direct fallback are unchanged.

With all eleven patches, the fresh 42-case C lifecycle suite, additional FD
negative checks, 30 bounded HTTP fixtures and ten whole Go race-suite runs passed.
The fresh owned `TestOpenConnectOwnedOCServInteroperability` also passed using
two independently generated PKIs and identical inner address ranges, with real
tunnel DNS and HTTPS. These are distinct from the initial raw-packet host proof.

All three actual Android owned cases passed on Android 16/API 36 x86-64
emulators with measured 4,096-byte and 16,384-byte pages. They exercised the
actual JNI and sealed-FD path: malformed/pre-cancelled preparation, wrong gateway
CA/name, and two sessions with distinct PKIs and identical inner address ranges,
tunnel DNS and distinct 40 KiB HTTPS bodies. Wrong origin CA, cancellation,
repeated close and the unaffected second session were checked. Both ARM64 and
x86-64 dynamic closures and ELF alignment passed static verification. These
results do not establish commercial-provider compatibility, media playback,
ARM64 execution, physical-device performance or Android TV behavior.

## License and corresponding source

The OpenConnect library uses LGPL 2.1; its exact release license is retained as
`COPYING.LGPL`. Modified upstream files carry dated Tachiai notices through
the supplied patches. The cache retains the unchanged release archive, fully
extracted patched sources, configure arguments, generated build files, build
logs, library objects/shared library and the executable fixture. Together with
`lifecycle.c` and this script, these provide the materials to reproduce and
relink this host proof. A local cache is not a distributable application's
corresponding-source publication.

The Android build packages eight separate dynamic libraries and embedded
notices. Every distributed debug APK accompanies its matching
`routebridge-native-source.tar.xz`, containing exact application/native sources,
pinned archives, patches, build inputs and
[replacement/rebuild instructions](RELINKING.md).
Notices alone are insufficient. The initial host dependency graph includes
p11-kit/libidn2; the pinned Android closure disables these and retains bundled
libtasn1/unistring notices. The application retains its existing licensing.
[OpenConnect license](https://www.infradead.org/openconnect/licence.html).

`sources.tsv` and `fetch-sources.sh` pin official source archives for the
minimal build: OpenConnect 9.21, GnuTLS 3.8.13, Nettle 3.10.2, GMP 6.3.0,
libxml2 2.15.4 and zlib 1.3.2. These hashes were checked against downloaded HTTPS
bytes; no independent signature validation is claimed. GnuTLS 3.8.13 and
libxml2 2.15.4 supersede the initial host environment's versions. A source pin
alone does not prove a completed build. Optional p11-kit/IDN/TPM/compression
dependencies stay disabled; included libtasn1/unistring remain part of the exact GnuTLS
archive and retain their per-file notices. GnuTLS stays thread-enabled.
[GnuTLS releases](https://www.gnutls.org/news.html),
[libxml2 releases](https://download.gnome.org/sources/libxml2/2.15/).

GnuTLS's library is LGPL 2.1-or-later; Nettle/GMP use LGPL 3-or-later or GPL 2-or-later.
The selected combined native library path must satisfy LGPL 3 obligations,
including corresponding application/relink and applicable installation materials;
it cannot be described as LGPL 2.1-only. libxml2 is MIT and zlib uses the zlib
license. `notices/` retains exact LGPL/GPL, libxml2, zlib and bundled GnuTLS
inih/crau license texts. This inventory does not replace per-file license review
or constitute a complete legal SBOM. The full
verified archives, patches, build flags and retained objects are the source and
relink inputs; this directory alone is not a binary redistribution package.
