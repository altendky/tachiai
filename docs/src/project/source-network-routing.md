# Per-source network routing research

Research for [issue #35](https://github.com/altendky/tachiai/issues/35), checked
2026-10-08 against repository commit
`0e6bb1ff66ab715c8b4824f7e4fb19b9a22fdebc`.
This section is the original documentation-only assessment, not a routing implementation or a new
playback experiment. No phone, VPN configuration, credentials or provider
sessions were inspected or changed for this research.

The subsequent [connection-import prototype](connection-import.md) implements
guided Proton configuration handoff, protected local profile storage and separate
route/provider setup screens. Provider defaults cover their streams/feeds;
conflicting historical defaults require explicit review. Saved-profile playback stops before provider
preparation; System network keeps the existing path.
That importer-only milestone did not implement or test any routing backend described here. The user now
prioritizes independent native playback routes, including necessary ABEMA setup
and licensing requests, over external companion VPN automation; the external
system network remains the current implementation, not the intended final UX.

## Subsequent native transport implementation

The user subsequently requested implementing both initial mechanisms: HTTP
CONNECT and WireGuard, with Proton-exported profiles as the known VPN input.
The debug native prototype now has session-owned authenticated loopback CONNECT
bridges. HTTP CONNECT and SOCKS5 backends connect to explicit proxies; WireGuard
uses upstream wireguard-go's in-memory netstack, not Android `VpnService`. Origin HTTPS remains
end-to-end and uses normal certificate/hostname checks. No TLS interception,
provider algorithm change, credential capture or System-network fallback is added.

Provider route choices are frozen per presentation. Identical canonical profiles
share one tunnel/bridge, including duplicate imports, to avoid competing
WireGuard endpoints for one peer. Native Twitch validation/access/HLS and ABEMA
bundle cache misses/MPD/media use explicit connection factories. Cached ABEMA
guest/source/initial-license requests remain unchanged Chromium requests, routed
by an awaited process proxy override in its dedicated host. The two ABEMA
helpers share their provider's route; different simultaneous ABEMA routes are
not implemented. Twitch's native route is independent of that override. The
historical page-backed comparison does not accept imported ABEMA routes.

The loopback listener requires fresh ephemeral credentials before dialing and
admits only reviewed HTTPS destination host families on port 443. HTTP upstream
proxy credentials are scoped to its CONNECT handshake, not origin headers.
HTTP proxies do not encrypt their outer authentication; do not treat them as
HTTPS proxies. WireGuard destination DNS uses the profile's DNS inside its
netstack; missing DNS is rejected instead of resolving destinations on System
network. Endpoint bootstrap DNS and outer proxy/tunnel connections intentionally
follow the system network, which may itself include an external VPN.

The subsequent SOCKS5 backend uses the already-pinned `golang.org/x/net/proxy`
client with `ContextDialer`, TCP CONNECT and remote hostname resolution. Its
small method guard rejects unsupported negotiation and requires RFC 1929 when
the profile contains credentials. Destination DNS never falls back locally.
Like HTTP proxy authentication, SOCKS5 username/password authentication is
unencrypted on the outer proxy connection.
[Pinned Go client API](https://pkg.go.dev/golang.org/x/net@v0.59.0/proxy#SOCKS5),
[RFC 1929](https://www.rfc-editor.org/rfc/rfc1929.html).
The same loopback admission,
provider-origin/TLS policies, cancellation and tracked-socket cleanup apply.
No UDP association, proxy TLS, system VPN, provider login, or new media origin is
added. [Configuration and executed fixture coverage](connection-import.md#socks5-configuration)
describe the implemented subset; there is no commercial SOCKS5 playback result.

Teardown closes players/helpers before clearing Chromium's override and closing
owned transports. Stop, background and the existing presentation deadline govern
route lifetime too. Failed or unconfirmed cleanup blocks another run; force-stop
and relaunch may be required. Role swaps retain route ownership. Import/Save
still does not activate a route; Open viewer is the explicit connection action.

Route initialization receives a per-presentation preparation owner before a
backend constructor starts. Stop, background, destruction and presentation expiry
signal pending initialization; a backend returned after cancellation is closed
instead of published. Native adapters must register only bounded, nonblocking
cancel signals before network work, remove those registrations after initialization
and before freeing handles, and perform wait/join/destruction on their worker.
Signal or late-backend cleanup failure remains a process-wide admission blocker,
including after Activity recreation. This contract adds no VPN engine; the
registered proxy/WireGuard constructors retain their existing behavior.

Eight new JVM checks passed, including blocked initialization, late completion,
cleanup failure and 200 cancellation/removal races. Three Go token checks and the
existing complete Go race suite passed. The actual ARM64/x86-64 API-26 JNI build
passed 16 KiB LOAD checks; packaged native entries are uncompressed and 16 KiB
aligned. Full Android tests, lint, instrumentation compilation, release isolation,
both APK assemblies and scoped hooks passed; both certificates match the shared
debug identity. All 38 combined route/provider/picker and actual JNI checks passed
on the disposable Android 16/API 36 x86-64 emulator. Four additional real cached
Activity checks passed for Back, background/destruction, expiry while routes are
empty and cleanup-failure admission after recreation. Their temporary test-only
process override was restored before the final ordinary-manifest build. No new
VPN handshake, provider playback, persistent-device modification or 16 KiB runtime
observation is claimed by this contract change.

This is implementation, not verified provider compatibility. Build, native
fixtures, installed-WebView proxy authentication, actual Proton playback and
TV results must be recorded independently below; earlier system-Proton playback
does not establish this new path. The remaining research sections retain their
original evidence and estimates.

### Transport verification on 2026-10-08

- The pinned Android build passed 600 debug and 416 diagnostic JVM tests,
  instrumentation compilation, release isolation and both APK assemblies.
  Both APK signing certificates match the shared debug-key reference. Lint has
  no errors; two new proxy-feature warnings remain despite explicit runtime
  capability checks, alongside the existing warnings.
- Seven Go fixtures passed, including a synthetic WireGuard peer handshake,
  destination DNS inside the tunnel, authenticated HTTP CONNECT byte forwarding,
  cancellation and rejection without direct fallback. Repeated race tests and
  vet passed. Native binaries cover arm64 and x86_64 with 16 KiB ELF alignment;
  this does not establish 32-bit Android or Shield compatibility.
- On the Pixel 6, Android 17 and System WebView 153.0.8010.36, the installed
  browser completed the local proxy authentication callback with synthetic
  credentials. The fixture deliberately returned 502 without contacting any
  provider; it proves the callback, not browser DNS-leak freedom.
  All 27 focused device tests passed, including actual JNI initialization and
  teardown for both transports, browser authentication and route/setup UI
  regressions. Synthetic WireGuard JNI initialization alone is not a handshake.
- With the user's saved Proton Japan configuration assigned to ABEMA and
  System network assigned to Twitch, native ABEMA News completed fresh guest,
  source and initial CDM setup and rendered video. Chillhop Radio also rendered,
  and both native playback clocks advanced concurrently during the bounded run.
  Android reported no active system VPN. The original provider page was not
  loaded; this tests the in-app tunnel rather than the companion Proton app.
  No fresh acoustic confirmation, packet-level leak audit or long-duration
  reliability claim is made. The account state was anonymous ABEMA plus the
  existing saved Twitch grant; no account credentials were inspected.
- One interrupted preparation/retry reported a playback-cleanup
  failure and refused another run. Force-stopping Tachiai restored operation;
  teardown under interruption still warrants further device investigation.

HTTP CONNECT has owned transport/TLS fixtures, not a commercial-proxy playback
observation. Provider acceptance of any particular exit remains an independent
condition; a saved profile name is not proof of its location or compatibility.

## Recommendation and scope

Keep the external system VPN as the current baseline. If routing becomes an
approved feature, start with named profiles and a credential-free, native HTTP
proxy fixture. Do not label that result complete per-source routing until all
of a source's preparation, licensing and media requests follow its route.
ABEMA's Chromium requests make that a separate architectural gate.

An app-owned WireGuard userspace network stack is a plausible later backend,
not a drop-in capability of the Android WireGuard tunnel library. Android's
ordinary per-app VPN rules and a WebView profile cannot select independent
exits for Tachiai's two feeds. A remote HTTP proxy is the smaller first
experiment, provided its request coverage, authentication and failure behavior
are verified.

These recommendations are provisional. The existing
[requirements](requirements.md#initial-non-goals) exclude geographic-restriction
circumvention and VPN bypass from the initial product. Researching transport
options does not change that scope, establish provider permission, confer
content entitlement or authorize new private API/DRM behavior. Any integrated
routing implementation needs an explicit product/security review. Existing
[media-origin approvals](media-origin-approvals.md) and
[security boundaries](security-and-privacy.md) still apply behind a proxy.

Evidence classifications below mean:

- **Documented:** primary platform/vendor documentation or inspected source.
- **Inferred:** an engineering consequence or proposed design, not tested here.
- **Unknown:** exact application behavior still needs validation.

There is no exact-application routing test establishing compatibility on the
Pixel 6 or NVIDIA Shield. Phone findings do not imply TV, iOS or desktop support.

## What needs routing in this application

The current default Prototype uses native players, but not exclusively native
HTTP. A route must belong to a playback session, not its visual A/B label or
primary/secondary position. Swapping videos must not swap sockets or exits.
Two copies of one source must be able to select different profiles; hostname
rules alone cannot distinguish those copies.

The following code ownership is **Documented** at the baseline commit. Links
are fixed to that commit, not a changing branch.

| Surface | Current transport | Routing implication |
| --- | --- | --- |
| Twitch grant validation, device/token requests | Native `HttpsURLConnection` | Native connection factory seam; external browser activation is separate. |
| Twitch playback-access query | Native bounded HTTP | Route this along with subsequent HLS, not just video segments. |
| Twitch HLS manifests and segments | Media3 custom bounded datasource | Per-session datasource injection is the useful seam. |
| ABEMA public runtime bundle download/cache verification | Separate native HTTPS downloader | Declare whether immutable public artifacts use a shared system route or the session route; do not silently exempt them. |
| ABEMA fresh guest, media token, source metadata/gateway | Chromium `fetch` in the cached bootstrap WebView | Not affected by native HTTP connection factories. |
| ABEMA selected MPD, refresh, initialization/index/media | Native bounded HTTP and Media3 | Preserve source admission, budgets, Range handling and TLS checks. |
| ABEMA initial license exchange | Native CDM callback delegates to the slot's Chromium runtime | License HTTP is Chromium, not a native `HttpMediaDrmCallback` transport. |

Evidence:

- [Twitch transport](https://github.com/altendky/tachiai/blob/0e6bb1ff66ab715c8b4824f7e4fb19b9a22fdebc/apps/android/app/src/main/kotlin/net/fstab/tachiai/provider/twitch/TwitchDeviceHttpTransport.kt#L46-L117)
  and [access/media policy](https://github.com/altendky/tachiai/blob/0e6bb1ff66ab715c8b4824f7e4fb19b9a22fdebc/apps/android/app/src/main/kotlin/net/fstab/tachiai/provider/twitch/TwitchNativePlayback.kt#L20-L64).
- [Native datasource](https://github.com/altendky/tachiai/blob/0e6bb1ff66ab715c8b4824f7e4fb19b9a22fdebc/apps/android/app/src/main/kotlin/net/fstab/tachiai/platform/media/BoundedMediaDataSource.kt#L21-L159)
  and [access transport](https://github.com/altendky/tachiai/blob/0e6bb1ff66ab715c8b4824f7e4fb19b9a22fdebc/apps/android/app/src/main/kotlin/net/fstab/tachiai/platform/net/AccessProbeHttp.kt#L37-L98).
- [ABEMA preparation and native creation](https://github.com/altendky/tachiai/blob/0e6bb1ff66ab715c8b4824f7e4fb19b9a22fdebc/apps/android/app/src/debug/kotlin/net/fstab/tachiai/provider/abema/CachedPrototypeAbemaSession.kt#L196-L455)
  and [Chromium bootstrap requests](https://github.com/altendky/tachiai/blob/0e6bb1ff66ab715c8b4824f7e4fb19b9a22fdebc/apps/android/app/src/debug/assets/abema/native-bootstrap.js#L129-L347).

Both feed slots currently run in one presentation process. Historical and
cached prototypes have different named processes and WebView data-directory
suffixes, but neither creates a process per feed or a separate application UID.
Both cached ABEMA helpers use the same process/profile. The older page-backed
and single-WebView experiments have broader browser subresource traffic,
including provider-controlled media and advertisements; they are not covered
by this native request map.
[Debug process declarations](https://github.com/altendky/tachiai/blob/0e6bb1ff66ab715c8b4824f7e4fb19b9a22fdebc/apps/android/app/src/debug/AndroidManifest.xml#L3-L18),
[WebView initialization](https://github.com/altendky/tachiai/blob/0e6bb1ff66ab715c8b4824f7e4fb19b9a22fdebc/apps/android/app/src/debug/kotlin/net/fstab/tachiai/feature/presentation/PrototypeActivity.kt#L79-L86).

Historical page-backed ABEMA and the composite's Twitch child keep page assets,
browser authentication/storage, original-player manifests/media, license and
advertisement/analytics traffic in Chromium. The generic interceptor serves
packaged resources, not a full native routing replacement. Inspect those paths
separately if they become routing targets:
[BrowserPane interception](https://github.com/altendky/tachiai/blob/0e6bb1ff66ab715c8b4824f7e4fb19b9a22fdebc/apps/android/app/src/main/kotlin/net/fstab/tachiai/platform/web/BrowserPane.kt#L189-L203),
[composite browser setup](https://github.com/altendky/tachiai/blob/0e6bb1ff66ab715c8b4824f7e4fb19b9a22fdebc/apps/android/app/src/main/kotlin/net/fstab/tachiai/feature/diagnostic/AbemaTwitchSingleWebContentsProbe.kt#L47-L59),
[official embed construction](https://github.com/altendky/tachiai/blob/0e6bb1ff66ab715c8b4824f7e4fb19b9a22fdebc/apps/android/app/src/main/assets/twitch/composite.js#L71-L75).
External browser authorization and other installed apps remain outside a slot's
connection factory. Routing never licenses Tachiai to collect their cookies.

The prior [native experiments](native-access-experiments.md) recorded
user-controlled system Proton connectivity, not per-feed routing or independently
verified exit geography. A VPN icon proves neither the selected exit nor a
provider's acceptance of it. Provider session/IP affinity across preparation,
license, manifests and refresh remains **Unknown**.

## Candidate comparison

Effort is an author estimate for a bounded prototype by an engineer familiar
with this app, not a quote or schedule commitment. Production security, TV UX,
testing and maintenance add work. Costs are categories, not current plan prices.
All independent-feed outcomes in this table are untested in Tachiai.

| Approach | Actual selection boundary / fit | External VPN coexistence | Setup and operating cost | Estimated prototype effort |
| --- | --- | --- | --- | --- |
| External VPN with guidance/status/handoff | Whole device or application; good current shared-route baseline, not two independent feeds | Uses the existing VPN | Installed companion, account and any subscription; no Tachiai tunnel infrastructure | Days for guidance; provider automation has unknown API support |
| Native HTTP/CONNECT/SOCKS proxy | Explicit client/connection per session; technically matched to native seams, incomplete for ABEMA Chromium | Proxy connections normally follow the system route, possibly through Proton | User-supplied endpoint and optional proxy credential; service fee or gateway/egress cost | Weeks for bounded native coverage and fixtures |
| WebView proxy override | Host-process-wide; mismatched to two independent helpers in the current process | Proxy's outer connection still follows system routing | Same endpoint costs; potentially isolated host processes and IPC | Weeks for one shared route; several weeks or more for per-feed isolation |
| Import WireGuard into stock Android tunnel backend | System VPN / selected applications; not a per-feed socket API | A new system VPN displaces the existing service | User configuration plus server/provider account | Weeks for shared routing, consent and lifecycle; does not solve independent feeds |
| App-owned WireGuard userspace stack | Explicit dialer/netstack per route; plausible independent-feed backend with local proxy bridge for WebView | No second OS VPN required; outer tunnel may itself traverse Proton | Imported secret configuration, native bindings, endpoint infrastructure and bandwidth | Months; high uncertainty around Android/TV integration and confinement |
| Custom `VpnService` with one or multiple upstream tunnels | Application TUN plus custom flow broker; app lists alone cannot identify feeds | Replaces Proton; a single engine could multiplex upstreams, but would need source-aware flow mapping | VPN consent, foreground service, tunnel configuration and infrastructure | Months for genuine per-source routing and leak/recovery testing |

The complementary matrix describes proposed UX, not existing Tachiai controls.
All new backends need separate phone and Shield validation; iOS/desktop remain
later investigations rather than inherited support.

| Approach | Configuration level / everyday usability | Switching behavior | Android phone/tablet and TV fit |
| --- | --- | --- | --- |
| External companion | Simple named guidance; configure account/exit in another app | Manual companion switch then retry; both feeds may be interrupted | Existing phone workflow; companion TV availability varies; no per-feed control |
| Native proxy | Intermediate import with simple saved-profile selection; advanced auth/timeouts only where supported | Session-scoped restart; ordered fallback only after confinement tests | Native APIs available; TV needs usable import; ABEMA coverage incomplete |
| WebView override | Shared route is simple; independent routes require hidden host-process complexity | Await override completion and restart affected browser session; current shared process affects both | Runtime feature check on every device; independent hosts unverified |
| Stock WireGuard backend | Intermediate secret-file import, then simple tunnel selection | System VPN/tunnel replacement; shared-route interruption | Android library documented; TV consent/import/lifecycle unverified |
| Userspace WireGuard | Intermediate import; advanced DNS/address/MTU; simple per-source profile once validated | Explicit route-instance restart; no assumed automatic provider recovery | Custom bindings/bridge required; no Tachiai phone/TV compatibility evidence |
| Custom OS VPN | Advanced engine setup can be hidden behind named profiles; consent/service remains visible | TUN/app-rule changes may affect both feeds; custom flow ownership needed | Android service APIs available; independent routing/TV UX unverified |
| Guided remote gateway | Guided endpoint import; self-host administration remains advanced | Inherits selected proxy/tunnel backend behavior | Client backend determines support; reachable gateway alone proves no device fit |

### External VPN and companion profiles

**Documented:** Android permits one active `VpnService` per user/profile.
Allowed/disallowed VPN lists select applications, not playback panes. A VPN may
permit bypass via `allowBypass`; lockdown prevents non-VPN connections.
[Android VPN guide](https://developer.android.com/develop/connectivity/vpn).

**Inferred:** Keep an explicit “System network” option, show only observed VPN
presence and offer an ordinary launcher handoff to a user-selected companion.
Return to Tachiai with a manual retry, not a claim that a named exit connected.
Do not depend on Proton private activities or UI automation as a production API.
No supported public third-party profile-selection/result API was established
by this research; the [Proton app manifest](https://github.com/ProtonVPN/android-app/blob/master/app/src/main/AndroidManifest.xml)
is not such a contract.

For a simple setup, the user chooses the companion's profile themselves, then
both feeds inherit the system route. Companion split tunneling can include
Tachiai while excluding other apps, but cannot split Tachiai A from B. This is
acceptable if both sources can use the same network; it is explicitly not the
independent-route feature.

### Native HTTP proxies

**Documented:** Android's Java URL API provides explicit
[`openConnection(Proxy)`](https://developer.android.com/reference/java/net/URL#openConnection(java.net.Proxy)).
That API also warns that protocol handlers without proxy support may ignore
the parameter; exact HTTPS/SOCKS behavior must pass the fixture, not merely compile.
Media3 supports custom `HttpDataSource` factories and recommends contract tests
for custom stacks.
[Media3 network stacks](https://developer.android.com/media/media3/exoplayer/network-stacks).

**Inferred:** Supply a session-bound connection factory to existing transports,
including validation, access, bundles where required, manifests and media.
Avoid global `ProxySelector`, `Authenticator` or process-network changes for
per-source selection. A new networking library is optional, not prerequisite
architecture. Keep the existing bounded datasource's policy outside the chosen
transport; replacing it with an unrestricted Media3 factory would regress the
current safety model.

HTTP CONNECT can transport provider HTTPS without terminating provider TLS.
An HTTPS proxy adds encryption to the proxy connection. SOCKS support, proxy
authentication and DNS resolution differ between native libraries and Chromium;
do not promise one imported proxy definition works identically in both.
Native proxy credentials must be scoped to the proxy and never forwarded to an
origin. Certificate failures must remain fatal.

For the first native fixture, use a credential-free HTTP CONNECT proxy for
controlled HTTPS origins, assign it to one source and leave the other on System
network. Java's documented [`Proxy`](https://developer.android.com/reference/java/net/Proxy)
selects HTTP/SOCKS and an address, not a TLS-to-proxy scheme. Supporting an HTTPS
proxy securely is a separate transport capability to validate. Intermediate
users could then import supported named proxy profiles. ABEMA must remain
labelled unsupported for a profile until its Chromium route is also handled.

### WebView scope and isolation

**Documented:** `ProxyController` is process-specific, applies to all WebViews
in its scope, requires the `PROXY_OVERRIDE` feature and applies changes
asynchronously. Wait for the completion listener before loading. Its override
ignores system proxy settings; this does not mean it bypasses a system VPN.
[ProxyController](https://developer.android.com/reference/androidx/webkit/ProxyController).
Rules accept HTTP, HTTPS or SOCKS endpoints, scheme filters, ordered alternatives
and direct/bypass rules.
[ProxyConfig.Builder](https://developer.android.com/reference/androidx/webkit/ProxyConfig.Builder).

**Documented:** WebView profiles separate browsing data. The current API has
no documented per-profile proxy setter.
[Profile](https://developer.android.com/reference/androidx/webkit/Profile).
Separate host processes require distinct WebView data directories configured
before initialization.
[ProcessGlobalConfig](https://developer.android.com/reference/androidx/webkit/ProcessGlobalConfig).
WebView networking resides in the embedding host process, not independently in
each sandboxed renderer.
[Chromium WebView architecture](https://chromium.googlesource.com/chromium/src/+/HEAD/android_webview/docs/architecture.md).

**Inferred:** Separate ABEMA helper host processes plus explicit IPC could be a
future boundary for distinct overrides while keeping native presentation in
the viewer. This requires fresh session ownership, cancellation and crash
handling; it is not obtained by creating another WebView or renderer. The
historical two-provider single WebView cannot route frames independently by
calling `ProxyController`. Hostname bypass rules fail for duplicate sources or
shared CDNs and must not stand in for source identity.

**Documented for Chromium, not verified for the installed WebView:** its proxy
documentation describes proxy-side destination DNS for HTTP and SOCKS5, TCP-only
SOCKS URL traffic, no SOCKS5 authentication, and ignored credentials embedded in
manual proxy URLs. HTTPS proxies do not negotiate QUIC.
[Chromium proxy behavior](https://chromium.googlesource.com/chromium/src/+/HEAD/net/docs/proxy.md).
**Unknown:** exact WebView authentication callbacks, auxiliary DNS, service
workers, bypass behavior and pooled-connection transitions. A local proxy
bridge would need its own authorization and isolation, not an unauthenticated
LAN listener. Never reroute browser content through a native JavaScript bridge
or convert intercepted requests into unrestricted native fetches.

### WireGuard and OS VPN alternatives

**Documented:** the official Android embedding option is the tunnel library.
Its `GoBackend` establishes an Android `VpnService` TUN, protects outer sockets
and brings down its current tunnel before bringing up another.
[Embedding options](https://www.wireguard.com/embedding/),
[GoBackend implementation](https://git.zx2c4.com/wireguard-android/tree/tunnel/src/main/java/com/wireguard/android/backend/GoBackend.java).
Importing two files into that backend is not evidence of simultaneous routes.

**Documented:** wireguard-go has an in-memory netstack; its upstream HTTP
example connects a WireGuard device to that stack and supplies its dialer to
an HTTP transport.
[Netstack implementation](https://git.zx2c4.com/wireguard-go/tree/tun/netstack/tun.go),
[HTTP-client example](https://git.zx2c4.com/wireguard-go/tree/tun/netstack/examples/http_client.go).
**Inferred:** multiple independently owned stacks could support per-source
routes without an OS VPN. Android bindings and a bounded loopback CONNECT/SOCKS
bridge for WebView would still have to be built and tested. This is not a
reason to introduce a shared Rust/KMP core.

**Documented:** Proton supplies standard WireGuard configurations selected by
the user on its account site. Its guide discusses simultaneous tunnels with
compatible clients/routers and separate address/DNS configuration. That does
not establish compatibility with Android's stock tunnel backend.
[Proton configuration guide](https://protonvpn.com/support/wireguard-configurations).
**Unknown:** plan accounting for several custom tunnels, configuration lifetime,
revocation and support for a proposed Tachiai backend. Account login remains
outside Tachiai; import a user-exported configuration, not Proton credentials.

A custom OS VPN could forward flows into multiple upstream tunnels inside its
one service. **Inferred:** it would require explicit source-aware socket/flow
ownership, probably a broker shared with application transports. Destination
IP/port inference alone is unreliable for shared hosts and duplicates. Android
[`Network` socket APIs](https://developer.android.com/reference/android/net/Network)
can select a network, but do not inherently identify a feed or override VPN
policy. Process-wide binding would affect both current slots. OS VPN lifecycle
also requires user consent, cancellation/revocation handling and foreground
service operation; app rules change at TUN establishment.
[`VpnService.Builder`](https://developer.android.com/reference/android/net/VpnService.Builder).

License review is a prerequisite, not permission to copy implementations:
[WireGuard Android](https://git.zx2c4.com/wireguard-android/tree/COPYING)
is Apache-2.0;
[wireguard-go](https://git.zx2c4.com/wireguard-go/tree/LICENSE) is MIT;
[gVisor](https://github.com/google/gvisor/blob/master/LICENSE) includes
Apache-2.0 and additional file notices. Pin actual dependencies, inventory
transitive/native components and distribute required notices before shipping.

### Guided profiles and remote gateways

These are setup options layered on the transports above, not a seventh routing
primitive. Support user-supplied standard proxy or WireGuard profiles first,
with optional provider-specific import guidance. A future catalogue could
describe supported capabilities without embedding provider account passwords.
No profile format should be hard-coded to Proton, Japan or ABEMA.

A self-hosted gateway needs a reachable host, secure administration, software
updates, bandwidth and an acceptable exit network. A managed proxy/VPN needs
an account, suitable endpoint/plan and verification of simultaneous-use limits.
Neither guarantees provider acceptance. Remote gateways must be explicit
forwarding endpoints, not servers that receive provider cookies, decrypt
protected media or rebroadcast it.

Operating cost depends mostly on exit count, concurrent throughput and egress
allowance. As an illustrative calculation, two feeds totaling 8 Mbit/s consume
about 3.6 GB/hour before protocol overhead; this is an assumption, not measured
Tachiai usage. Obtain current endpoint/hosting quotes before choosing a service.
External guidance adds no Tachiai hosting cost; user-supplied endpoints move
that cost to the user. Built-in tunnel software does not make exits free.

## Proposed profile and switching UX

This model is **Inferred** and not implemented:

- A named reusable profile identifies a backend and one or more ordered endpoint
  alternatives, a credential reference and supported capabilities. Keep a stable
  profile ID separate from its display name. Example names are “Home gateway”,
  “Travel proxy” and “System network”, not a baked-in provider/region.
- Resolve a slot's explicit session override first, then its saved-source
  preference, then its provider default, then System network. Selecting the same
  source twice permits two different session overrides. Show the resolved
  profile beside each selected source before playback.
- Freeze the resolved route for each preparation/playback attempt. Generic
  presentation owns selection/status; platform networking owns transports;
  provider adapters declare request coverage and prepare through the selected
  route. No provider selectors or protocols belong in a generic route profile.
- Show “Configured”, “Connecting”, “Transport checked”, “Preparing source”,
  “Playing” or a specific failure. Endpoint reachability is not provider
  compatibility; a provider error is not proof of VPN failure.

“System network” means **no application proxy/tunnel override**. It may already
go through Proton or another OS VPN. Do not call it “VPN off” or “physical
direct”. Selecting underlying Wi-Fi/cellular outside a VPN is a separate,
explicit capability subject to VPN bypass/lockdown policy, not a silent fallback.

Keep the initial UX small: source row → Network → named profile → Apply/restart
this source. Simple users can open their companion VPN and retry. Intermediate
users import a proxy and assign separate profiles to the feeds. Advanced users
can inspect backend-specific endpoint, DNS, tunnel address/AllowedIPs, MTU,
keepalive and timeout settings when supported. Do not display inert universal
“DNS”, “UDP” or “bypass VPN” switches for backends that cannot honor them.

During playback, a manual switch should cancel in-flight preparation/requests,
close old pools/tunnels, release the affected provider/CDM session and prepare
afresh. Warn that playback will be interrupted; do not promise position or
alignment preservation. Requested timing intent may remain visible, but any
measured timeline relationship must be invalidated. If two feeds explicitly
share a route instance, disclose whether its restart affects both.

That affected-source-only recovery is a future requirement. The current viewer
deliberately tears down the pair and its shared foreground budget after a
member failure, Stop or background transition. A routing implementation must
either keep that joint-stop behavior explicit or separately review session/group
ownership before promising independent restart. Connection-factory injection
alone does not change the lifecycle contract.
[Current joint cleanup](https://github.com/altendky/tachiai/blob/0e6bb1ff66ab715c8b4824f7e4fb19b9a22fdebc/apps/android/app/src/debug/kotlin/net/fstab/tachiai/feature/presentation/PrototypeActivity.kt#L309-L342).

Fallback should progress in stages:

1. Manual switching with explicit failures and an optional “Try next profile”.
2. Bounded ordered alternatives for pre-session transport failures, after
   fixture evidence establishes failure confinement.
3. Optional health-based selection only after false positives, provider/session
   affinity and rate limits have been characterized.

Never silently fall back to System network. Bound attempts, deadlines and
cooldowns; cancellation must stop retries. Authentication failures, provider
denials, unexpected origins, TLS errors and DRM failures must not trigger route
rotation. Even an HTTP timeout after provider state was created may require a
fresh session and explicit approval to restart. Automatic alternatives should
not imply WebView's built-in proxy precedence guarantees fail-closed behavior.

Phone setup can use Android's file picker for local configuration import.
TV should offer D-pad-accessible profile selection and a deliberate phone-assisted
import/pairing flow rather than expecting long secrets typed by remote. QR/code
pairing must not put a private key or credential in a public URL. Secure pairing,
file availability and secret deletion on TV are future design/test work.
**Documented:** Proton itself offers Android TV support from Android 8 and a
phone-assisted account flow; its split tunneling is application selection, not
feed selection.
[Android TV guide](https://protonvpn.com/support/android-tv),
[split tunneling](https://protonvpn.com/support/protonvpn-split-tunneling).
The available Shield's OS, engine, import UX and sustained performance remain
**Unknown**. iOS and desktop need separate API/lifecycle research.

## Security, reliability and performance gates

- Store imported private keys and proxy credentials locally, encrypted with
  platform-backed keys, excluded from backups and diagnostics. Keep route
  credentials separate from provider grants. Redact imports by default; explicit
  export is a secret-bearing operation with a warning and controlled destination.
  Deleting a profile must delete its secret reference and stop its sessions.
- Never log complete proxy/config URLs, signed manifests, credentials, tokens,
  provider cookies, license bodies or imported files. Record only route IDs,
  sanitized backend/status codes and bounded timings. Debugging does not require
  uploading a configuration to a health-check service.
- Preserve origin TLS and normal certificate validation. No custom interception
  CA or TLS-error bypass. A proxy/tunnel operator can observe destinations,
  timing and bandwidth; ordinary HTTPS does not expose provider plaintext to a
  CONNECT proxy. Protect proxy authentication on the outer path as well.
- Verify destination DNS, proxy/tunnel endpoint bootstrap DNS, IPv4 and IPv6,
  service-worker/subresource paths, direct/bypass defaults and refresh after
  connectivity changes. SOCKS TCP forwarding does not prove UDP/QUIC coverage.
  An HTTP-only design may intentionally use TCP, but that must not allow an
  alternate UDP path to escape the selected route.
- Route redirects only under existing source/origin policy; a proxy must not
  turn a rejected origin into an allowed one. Preserve CDM/session binding and
  opaque license handling; this work adds no renewal, keys or DRM extraction.
- Specify behavior on sleep, backgrounding, process death, network handover,
  VPN loss, tunnel revocation and profile deletion. Any future continuous
  background tunnel must meet Android service/distribution requirements; this
  research adds no permission or manifest entry.
- Benchmark startup latency, buffering, seeking/recovery, two-feed throughput,
  CPU, memory, thermal load and battery. Proxy detours and nested tunnels may
  worsen latency and live-window recovery; no performance improvement is
  established. Include JNI/Go ABI packaging, updates, dependency notices and
  vulnerability maintenance in a userspace-tunnel estimate.

## Cheapest validation sequence

Use controlled HTTPS origins, proxies and tunnel endpoints without provider
credentials first. Synthetic responses can exercise preparation, manifest,
segment, license-like and refresh paths without expanding real DRM scope.
No step below was performed for this research.

| Gate | Smallest useful check | Pass / fail criterion |
| --- | --- | --- |
| Native isolation | Two identical fixture sources, each using a different session connection factory | Origin observes intended exits for every request; swapping UI changes neither route; no global-client leakage. |
| Failure confinement | Stop one proxy mid-request, force timeout/cancel and a forbidden redirect | Only the affected session fails; no fallback/direct request, credential spill or policy bypass; cancellation ends retries. |
| Chromium scope | Two helper WebViews in one process, then separately hosted helpers if approved | Shared-process override demonstrably shared; isolated design routes all fetch/subresource/service-worker paths independently before it is accepted. |
| Protocol/credential coverage | IPv4/IPv6, destination/endpoint DNS, proxy authentication and possible UDP traffic observed at owned infrastructure | No unintended destination path; origin never receives proxy credentials; unsupported protocol/auth mode rejected clearly. |
| Lifecycle/coexistence | Existing external VPN, its loss/lockdown, Wi-Fi handover and process restart | Defined stop/reprepare behavior, no physical-network escape or stale pooled socket; outer tunnel route accurately described. |
| Userspace WireGuard | One stack/dialer to an owned endpoint, then two stacks plus bounded WebView proxy bridge | Independent exits and failure scopes established, imported DNS/address behavior verified, acceptable two-feed throughput without an OS VPN takeover. |
| Phone/TV usability | Non-secret imports and saved-profile switching on Pixel 6 and Shield | D-pad/touch setup, error recovery and deletion work; exact OS/WebView feature support recorded. |
| Provider fit, only after approval | One bounded live and replay attempt per provider with full route coverage, then duplicate/mixed pairs | Record preparation/license/refresh outcomes and interruption behavior; successful transport alone does not count as playback or provider support. |

Provider validation must separately record device, OS, WebView/native engine,
configured route and independently observed exit if available, account state,
content identity/type and actual video/audio results. Do not infer exit country
from a VPN marker or a successful manifest request. The current five-minute
debug playback limits and one-shot license scope remain unchanged.

## Remaining decisions

The next implementation proposal should choose only the controlled native proxy
fixture and a minimal route model, or explicitly explain why a different gate
is cheaper. Before calling it a product feature, resolve:

1. Is independent routing worth the added setup and security burden, or does a
   shared external route meet the initial need?
2. Can ABEMA helper requests be isolated without widening provider/DRM scope,
   and how should external authorization browsers be presented?
3. Which proxy authentication/DNS modes are actually supported across native
   HTTP and the installed WebView, including TV?
4. Does a provider require one stable exit through guest/access/license/media
   refresh, and what restart policy is safe after transport failure?
5. Would a userspace WireGuard bridge justify its dependency/maintenance cost
   compared with an ordinary remote proxy?

Reject, for now, promises of per-feed isolation from app split tunneling,
per-profile WebView storage, renderer separation, domain-only rules, stock
Android WireGuard multi-file import or media-only proxying. Reject silent direct
fallback, credential/cookie copying, private companion control and DRM/region
circumvention as shortcuts. None is needed to validate the transport seams.
