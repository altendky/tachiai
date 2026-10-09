# Certificate-only OpenVPN route backend

This directory implements and validates a route-only native OpenVPN adapter.
The debug route registry exposes the certificate-only subset described below.
The default debug routebridge builder includes the `openvpn_validation` Go build
tag and pinned native dependencies for ARM64/x86_64. Native crosscompilation and
host fixtures are separate from Android runtime verification. The client creates no
Android VpnService, OS TUN interface, system route or system DNS configuration.

## Source and licensing

OpenVPN3 is selected under its MPL-2.0 option. The official
[2024 relicensing commit](https://github.com/OpenVPN/openvpn3/commit/c42bea718ca65c8e879a9eaca2fe6e70cd6be692)
expressly grants the Core library dual MPL-2.0/AGPLv3 licensing for embedding in
other projects. The exact pinned source retains that global license without
excluding older headers. Some compiled headers still carry GPLv3-only notices;
the evidence-based interpretation is that these predate the later global grant.
Those upstream notices remain unchanged, and this interpretation must accompany
the source payload rather than silently relabeling files. Tachiai's own license
does not change. Upstream source is unmodified;
copyright/SPDX notices remain in the pinned archive. The covered source for any
distributed native binary must remain available, with its exact build changes
and an accompanying source notice. The adapter uses public client/TunBuilder
APIs in separate files. Asio is under the Boost Software License 1.0.
The [official generated license documentation](https://openvpn.github.io/openvpn3/md_LICENSE.html)
and exact-pin [root license](https://github.com/OpenVPN/openvpn3/blob/2f74c607f56294e39c9fab253f6dd87a2ed6e9ab/LICENSE.md)
and [contributor agreement](https://github.com/OpenVPN/openvpn3/blob/2f74c607f56294e39c9fab253f6dd87a2ed6e9ab/CLA.md)
provide the retained provenance for the global grant interpretation.

| Source | Immutable commit | HTTPS archive SHA-256 |
| --- | --- | --- |
| [OpenVPN3](https://github.com/OpenVPN/openvpn3/tree/2f74c607f56294e39c9fab253f6dd87a2ed6e9ab) | `2f74c607f56294e39c9fab253f6dd87a2ed6e9ab` | `4e01b6047f1be3eedfc2592576f27333574d8ff974cc810062a42b5f15f2b084` |
| [Asio 1.36.0](https://github.com/chriskohlhoff/asio/tree/231cb29bab30f82712fcd54faaea42424cc6e710) | `231cb29bab30f82712fcd54faaea42424cc6e710` | `5def09efbd4be199dd6ddca53a2c99b9eef696f6b430910d896594b04ff59108` |
| [OpenSSL 3.5.5](https://github.com/openssl/openssl/tree/67b5686b4419b4cb8caa502711c41815f5279751) | `67b5686b4419b4cb8caa502711c41815f5279751` | `f7c4a5e19fa02056e2bff4240bf474a3935207eb8fce6ee56175350acc6e2c32` |
| [fmt 10.1.0](https://github.com/fmtlib/fmt/tree/e57ca2e3685b160617d3d95fcd9e789c4e06ca88) | `e57ca2e3685b160617d3d95fcd9e789c4e06ca88` | `2d18d7b1c393791a180c8321cedd702093b5527625be955178bef8af2b1cf6db` |
| [LZ4 1.10.0 library](https://github.com/lz4/lz4/tree/ebb370ca83af193212df4dcbadcc5d87bc0de2f0) | `ebb370ca83af193212df4dcbadcc5d87bc0de2f0` | `eb1a93e934d4fd29df6e2061ba0bf447568561764d758f4a5662c0e29370ffa9` |

Archives use `https://codeload.github.com/{owner}/{repository}/tar.gz/{commit}`.
Verify their SHA-256 values before extraction/building. Do not use moving refs.
The license texts are retained in `../licenses/OPENVPN3-MPL-2.0.txt` and
`../licenses/ASIO-Boost-1.0.txt`. Host validation additionally links host OpenSSL,
fmt and LZ4; these host libraries are not Android artifacts. The Android builder
crosscompiles the pinned crypto and other dependencies for each ABI, packages
licenses/source notices, and verifies 16-KiB ELF alignment.

`scripts/build-android-openvpn.sh` builds only native dependencies with
read-only NDK `30.0.16248370`, API 26 and static libc++ for ARM64/x86_64. Set
`ANDROID_NDK_ROOT` to that existing SDK. The writable cache defaults to
`apps/android/routebridge/build/openvpn-native`; `TACHIAI_OPENVPN_BUILD_ROOT`
may select an existing isolated cache. It verifies archive hashes, builds static PIC
OpenSSL (without shared modules, engines or configuration autoload), fmt and the
BSD-licensed LZ4 library only, then the adapter. A shared link fixture forces the
complete native closure, refuses undefined symbols, checks 16-KiB LOAD alignment
and rejects dynamic crypto/compression/C++ runtime dependencies. That fixture
shared object is never packaged or executed by the app. The helper invokes no
Gradle, gomobile, SDK manager, ADB or device operations.
Complete cached outputs are reused only when the native source/configuration
fingerprint matches. Previously verified explicit validation caches can be
adopted when their recorded native sources match exactly; ABI, alignment and
dependency checks run again. Changed inputs require rebuilding.

`source-payload/` contains exact upstream archives, our native/build files and
license notices; `source-payload.sha256` records them. Preserve and publish that
payload with any later distributable native artifact. OpenSSL's Apache-2.0, fmt's
MIT and LZ4's BSD-2-Clause texts are retained in `../licenses/`. The NDK30 LLVM
NOTICE is retained verbatim for static libc++/libc++abi/unwind provenance; its full
vendor notice also describes toolchain components beyond the linked runtime.

`scripts/build-android-routebridge.sh` invokes that helper, copies static archives
from an external cache into the module's cgo ABI paths, then binds gomobile with
`openvpn_validation`. Copies avoid host-only symlinks across container mounts.
The resulting `libgojni.so` files must have the expected machine, 16-KiB LOAD
alignment, and only Android libc/libdl/libm/liblog dependencies. All license texts
and notices are embedded in the AAR's `assets/routebridge-licenses` directory.
The builder also creates `build/routebridge-openvpn-source.tar.gz` containing
exact upstream archives, our native and Go sources/build recipes, notices and a
relative-path SHA-256 manifest. CI uploads this companion with each signed debug
APK in the same artifact; it must remain available with redistributed binaries.

## Profile and ownership boundary

The native parser accepts at most 8 KiB, a single `client`, `dev tun`, `proto
udp` or `proto tcp-client`, one `remote NUMERIC_IP PORT`, `remote-cert-tls server`,
`verify-x509-name NAME name`, `tls-version-min 1.2` or `1.3`, optional `nobind`,
and exactly one inline CA/certificate/unencrypted key block. Comments must occupy
whole lines. Unknown/duplicate directives, scripts, files, credential requests,
encrypted keys, proxies, TAP and compression directives are refused. Core
evaluation must confirm certificate autologin without external PKI or challenges.
Raw profile/log/error/event strings never cross the C ABI; failures have fixed
categories. No core packet/config dumping is enabled.
Hostname endpoints are explicitly unsupported and refused before networking;
there is no silent address substitution. Numeric IPs still require the separate
server certificate name. Unspecified, multicast, broadcast, mapped IPv6,
link-local IPv6 and zoned addresses are refused. Protocol-family-specific
directives such as `udp4`/`udp6` are outside this initial subset.

TunBuilder records assigned IP addresses, plain DNS and MTU, then transfers one
end of an owned AF_UNIX datagram socketpair to OpenVPN3. Routes are confined to
the userspace stack. Excluded routes and unsupported DNS/proxy features fail;
there is no host resolver/dial fallback for inner destinations. The packet peer
must preserve one complete raw IPv4/IPv6 packet per datagram. Go owns a duplicate
of the peer FD and validates framing, lengths and truncation before injection.

The native cancellation latch covers cancellation before start and while
connecting. The conditional Go wrapper checks a context throughout preparation,
with an independent 30-second deadline even for callers without a deadline.
Server `AUTH_PENDING` requests are refused immediately in this certificate-only
subset, including requests to extend the server's authentication timer.
After preparation, one worker owns native status waits/destruction. It cancels the
core, closes the Go packet bridge and joins/destroys the native client. AF_UNIX
datagrams do not provide reliable stream EOF when another duplicate closes;
the Go bridge must be explicitly closed on native failure/cancellation.
Reconnect fails the session instead of replacing its packet stack invisibly.

Cancellation state alone does not prove worker completion. Checked destruction
waits at most five seconds for an explicit worker-finished signal after native
connect returns, then joins and frees only a finished worker. If completion is
unconfirmed, the opaque handle and worker are retained, never freed or detached;
OpenVPN initialization stays blocked until process shutdown. The worker returns
the fixed cleanup failure. Checked route cleanup preserves that safe error for
all concurrent/repeated Close callers; existing void-cleanup route callers keep
their previous behavior. Host-only injection holds a real native worker to prove
the five-second failure, nonblocking token cancellation, fixed result code and
refusal to initialize a second engine. Only that owned fixture releases its gate
afterward to clean test resources; no Android artifact contains the test hooks.

The reusable bridge owns a gVisor channel endpoint directly. Its bounded
128-packet queue supports concurrent packet writes and closure. Lifetime
cancellation stops pending DNS/TCP creation before closing the SDK stack and
waiting for protocol workers. The bridge does not use a separate WriteNotify
notification channel. Packet buffers/views are released after consumption.
DNS A/AAAA families resolve and dial independently; a usable family cancels and
joins the losing workers before returning. Silent AAAA must not consume the
deadline for an already reachable A answer.

The generic `RoutePreparation` token is created and registered with the playback
owner before native preparation. Cancel signals only its Go context; the setup
worker owns stop, checked join and release. `NewOpenVPNPrepared` consumes one token
once and returns `GetCode`/`GetRoute`, with fixed IDs: 0 ready, 1 configuration,
2 TLS refusal, 3 connection failure, 4 cancelled, 5 timed out, 6 cleanup
unconfirmed. Failed results own no route. Successful callers own the returned
route and close it through the backend; cleanup cancels the token and native
tunnel. Removing the Kotlin cancellation registration must exclude in-flight
callbacks before owner handoff/free. This worktree's native helper does not run
or verify those Kotlin/Activity lifecycle checks.

## Host verification

Use a session temporary root and task-specific Go build cache/temp directories.
With verified extracted sources and host OpenSSL 3, fmt, LZ4, C++20, CMake and
Ninja available:

```sh
cmake -S apps/android/routebridge/native -B "$task_native_build" -G Ninja \
  -DOPENVPN3_SOURCE_DIR="$task_openvpn_source" \
  -DASIO_SOURCE_DIR="$task_asio_source" -DCMAKE_BUILD_TYPE=Debug
cmake --build "$task_native_build" --parallel 2
cd apps/android/routebridge
go test -race -count=10 -timeout 90s ./...
CGO_LDFLAGS="-L$task_native_build" go test -tags=openvpn_validation -race -c \
  -o "$task_native_build/routebridge-native.test"
cd ../../..
uv run apps/android/routebridge/native/fixtures.py \
  --binary "$task_native_build/tachiai_openvpn_test" \
  --go-binary "$task_native_build/routebridge-native.test" \
  --temp-root "$task_session_root"
```

Observed Linux host verification used Go 1.27.2, GCC 15.2.0, OpenSSL 3.5.5,
fmt 10.1.0 and LZ4 1.10.0. The full default Go route suite passed ten race-enabled
runs after the queue ownership correction. Packet fixtures cover independent
stacks with identical virtual addresses, explicit DNS/TCP, IPv4/IPv6 and UDP
framing, malformed/truncated packets, missing DNS refusal, pending DNS/TCP
cancellation, real socket backpressure and concurrent close. DNS fixtures also
cover TCP fallback with CNAME answers and reject mismatched IDs/questions,
unrelated addresses and CNAME cycles, and cover reachable A with an unanswered
AAAA query on a dual-stack client. No host interfaces or routes are used by
the inner stacks.

The native fixture generates synthetic PKI only in the supplied temporary root
and starts a fresh owned loopback OpenVPN2 server with `dev null` for each TLS
case. Valid inline PKI negotiation, assigned address/DNS/MTU, single FD transfer,
bounded timeout against an owned silent UDP endpoint, pre-start/concurrent
cancellation and native-to-Go FD ownership
pass. Wrong CA, server name and server certificate role produce an explicit TLS
refusal category, rather than merely a connection timeout. Go tests also cover
context cancellation and concurrent bridge cleanup. Failed synthetic fixtures
are retained temporarily for debugging; successful runs remove their PKI.

The dev-null fixture alone does not establish routed traffic. Separate
`routed_fixtures.py` verification now passes actual encrypted DNS/TCP/HTTPS over
both outer UDP and TCP, with two independent synthetic PKIs and identical inner
`10.50.0.0/24` addresses.
Each unprivileged client uses the existing authenticated loopback CONNECT route,
its native engine, packet socketpair and private gVisor stack. A synthetic origin
serves a distinct 40-KiB identity marker through each tunnel; cancellation of one
does not alter the other's marker. Wrong origin CA and name fail with typed x509
errors. Context cancellation during streaming closes within one second;
stopping the other server fails active traffic and closes the native session
within six seconds. A third owned server sends `AUTH_PENDING` with a 600-second
request; certificate-only preparation refuses it within two seconds.

Owned server containers alone open Linux TUN devices. Each has only
`CAP_NET_ADMIN` effective, no host network or privileged mode, a read-only
filesystem, no-new-privileges and loopback-only ephemeral UDP or TCP ports. Owned DNS
and HTTPS bind the server tunnel address; no forwarding or host route/DNS changes
are needed. The fixture base is official Debian pinned to
`sha256:96e378d7e6531ac9a15ad505478fcc2e69f371b10f5cdf87857c4b8188404716`;
OpenVPN is `2.6.14-0+deb12u2`, Python is `3.11.2-1+b1`. The built image is selected
by immutable image ID because transitive security packages may change.

An exploratory outer-DNS experiment ran the same host test binary and libraries
in a nonroot, cap-none disposable namespace, with only an owned silent DNS server
and a namespace-specific resolver file. Cancellation after an observed unanswered
query returned caller/native teardown within one second, but upstream uses a
detached `getaddrinfo` worker; this did not prove termination of libc lookup or
remaining retries. Consequently the first profile subset now requires numeric
endpoints and cannot initiate this bootstrap lookup. Hostname rejection is tested
in native policy and the Go wrapper. The obsolete resolver harness was removed;
the experiment remains evidence for this bounded support decision.

```sh
docker build -t tachiai-owned-openvpn:fixture-50 \
  -f apps/android/routebridge/native/owned-server.Dockerfile \
  apps/android/routebridge/native
task_owned_image=$(docker image inspect tachiai-owned-openvpn:fixture-50 --format '{{.Id}}')
uv run apps/android/routebridge/native/routed_fixtures.py \
  --image "$task_owned_image" --go-binary "$task_native_build/routebridge-native.test" \
  --native-binary "$task_native_build/tachiai_openvpn_test" --temp-root "$task_session_root"
# Repeat with --transport tcp for the accepted tcp-client profile subset.
```

The owned AUTH_PENDING fixture uses the documented
[OpenVPN 2.6.14 management protocol](https://raw.githubusercontent.com/OpenVPN/openvpn/v2.6.14/doc/management-notes.txt).
These host results are distinct from the Android checks below.

## Android verification on 2026-10-09

The actual generated JNI factory passed configuration/pre-cancel refusal and
existing proxy/WireGuard smoke tests. The owned Android orchestrator then passed
all three opt-in cases with outer UDP and TCP on the separate Android 16/API 36
x86-64 `tachiai-issue-tests` emulator. The TCP cases also passed on a separate
Android 16/API 36 x86-64 `tachiai-issue-tests-16k` emulator with its measured
page size of 16,384 bytes. These cases prove independent gateway CA/name checks,
two simultaneous userspace tunnels, explicit tunnel DNS, authenticated CONNECT,
normal origin hostname verification with separate owned origin CAs, distinct
40 KiB HTTPS bodies, cancellation during traffic and repeated confirmed cleanup.

The initial owned Android run exposed a teardown crash: a derived cancellation
member was destroyed before Core's base destructor unregistered its stop scopes.
The adapter now places cancellation in an earlier base so it outlives Core.
Both native ABIs were rebuilt and the JNI link was invalidated by actual archive
content hashes before rerunning the successful cases. This is adapter lifetime
ownership; upstream Core source remains unchanged.

The ARM64/x86-64 API-26 libraries have 16 KiB LOAD alignment and only standard
Android dynamic dependencies. Host test hooks are absent. The matching source
companion contains the exact native/Go/scripts/notices and five verified upstream
archives in a layout usable by the included build recipes. Shared debug APK
certificates are verified after every assembly. ARM64 runtime, physical-device
decoding/audio/performance, commercial-provider profiles, media playback and TV
compatibility remain unobserved. No persistent development-device or account
state was changed.

After installing matching debug and test APKs, run:

```sh
uv run apps/android/routebridge/native/android_fixtures.py \
  --adb /path/to/adb --temp-root /absolute/session/root --transport udp
# Repeat with --transport tcp, or --transport tcp --device 16k.
```

The orchestrator accepts only the two fixed disposable serial/AVD pairs, checks
identity before staging synthetic credentials and measures page size for the
16 KiB target. It never installs an APK or selects an attached phone.
