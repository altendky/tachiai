# Route import and provider setup prototype

## Route / provider / stream / feed

The setup vocabulary separates **route** (system network,
VPN or proxy path), **provider type** (ABEMA/Twitch), **provider instance** (a
named configuration of one type), **stream** (specific live or
recorded content) and **feed** (a running instance assigned to A or B). A saved
route configuration does not mean traffic is using that route. Two copies of
one stream create independent feeds. Provider-native channel/video/replay terms
may appear when browsing content; broader browsing is not implemented yet.

**Prototype → Routes** opens the existing import/manage screen. **Prototype →
Providers** opens the configured instances. Add ABEMA/Twitch instance creates a
separate configuration; Configure edits its name and default route for its
streams. The picker lists the catalogue under each instance and assigns a
stream together with that instance to A or B. Provider setup also links to
Routes for importing another configuration. Importing never automatically
selects it. Selecting a route is an explicit Save action. Per-feed route
overrides are not exposed in this instance-default workflow yet.

### Multiple provider instances

The initial ABEMA and Twitch instances have fixed reserved UUIDs and names.
New instances receive random stable UUIDs and persisted default names such as
Twitch 2; a custom name never changes identity. Names are limited to 64 printable
characters, unique within a provider type, and may be reset to that instance's
own default by leaving the field blank. The prototype permits at most 16
instances. Creating or saving an instance starts no route or playback. It does
not clone another instance's route or login; new instances use System network
and have no saved Twitch grant.

The default Twitch UUID returns the exact historical Smart TV LOCAL repository,
file, keystore alias and authenticated-encryption binding. Its grant is never
copied. Each new Twitch UUID derives a separate constrained encrypted no-backup
slot and process repository; Connect, revalidation and Forget affect only that
instance. The authorization identity, zero scopes, retention, foreground gates
and playback validation remain unchanged. Historical diagnostic cases continue
to use their original fixed repositories. ABEMA instances retain anonymous guest
playback; no ABEMA account authorization is introduced.

Both feeds may run one stream through one instance, or the same stream through
different instances. Each feed still owns a separate playback session; the
selected instance supplies its route and, for Twitch, its grant. Missing or
type-mismatched instance IDs block playback until explicit reassignment rather
than choosing another account. Feed source and UUID are saved together in
Activity and picker recreation state, including after the native view replaces
Compose. Malformed or unchecked saved slots stay unassigned; recreation opens
the picker without automatically restarting playback. Secrets and account
identities are not saved there. Viewer labels include the instance name.

ABEMA's WebView proxy override remains process-wide. A worker preflight checks
every selected ABEMA instance against canonical route configurations before
creating any route backend: simultaneous ABEMA feeds need the same configuration
(duplicate imports qualify), or all need System network. Missing routes among
simultaneous ABEMA instances or a System/imported mismatch block the run with an
explicit explanation. Independent
Twitch routes remain supported, and existing route sharing/conflict constraints
remain in force. Names or imported record IDs do not establish route equality.

The separately encrypted instance registry uses a JVM lock plus an OS file lock
across each complete read or mutation. An absent registry presents a read-only
view of existing provider settings under the deterministic default IDs. Pending
legacy choices and their earlier references remain pending. The first explicit
create/save atomically persists the complete view, including unresolved choices;
it rewrites no legacy source/provider/imported-route/grant record. Later reads
use that registry. Corruption, failed saves and stale IDs cannot replace it or
silently invent System routing. Obsolete stream metadata still requires the
existing explicit reset.

Provider-free JVM fixtures cover migration, retained pending routes, corruption,
failed writes, cross-store locks, bounds, stable names, stale/type-mismatched
bindings, grant/lease/Forget isolation and canonical ABEMA route preflight.
Compose fixtures exercise create/name validation, instance-specific login
placement, explicit save, duplicate streams across instances and recreation.
A repository-accessor fixture checks default-object identity without reading or
writing grants. All 16 newly added JVM checks passed, along with the complete
Android JVM suite, lint, instrumentation compilation, release isolation and both
APK assemblies. Both APK certificates matched the shared debug identity. All
37 combined instance, picker, provider, importer, handoff and JNI checks passed
on the disposable Android 16/API 36 x86-64 emulator. The importer fixtures first
settle text focus and the keyboard before their real Save taps.

Two additional checks passed in the real cached-prototype Activity process:
feed bindings survive replacing Compose with the native view and then Activity
recreation, while malformed saved slots remain unassigned. That dedicated run
used a temporary test-only instrumentation process override; the ordinary test
manifest was restored before the final build. No saved grants were accessed or
changed. No multiple-account authorization, provider playback or device/region
behavior has been observed; the persistent development emulator was preserved.

Routes offers guided Proton and Windscribe exports alongside the generic file
and manual importer. **Add route · setup / import** in Providers opens those
choices; neither setup link selects or starts a route.

The subsequent [native transport implementation](source-network-routing.md#subsequent-native-transport-implementation)
adds HTTP CONNECT, SOCKS5, userspace WireGuard and certificate-only OpenVPN
backends to the cached native viewer.
Selecting and saving a profile is still configuration only; Open viewer starts
its transport explicitly. System network retains the existing playback path.
The transport integration added no provider login, new stream or DRM behavior.
Earlier importer-only evidence below predates transport integration and does
not verify it.
The user reported loading a Proton file in the preceding build; no configuration
contents were inspected, and that report does not establish an active tunnel.

### Route protocol boundaries

The debug route registry currently registers WireGuard, HTTP CONNECT, SOCKS5
and certificate-only OpenVPN.
Each handler owns format recognition, strict configuration validation, safe
setup labels, canonical configuration, sharing/conflict identities and backend
creation. The generic session owns the authenticated loopback proxy, HTTPS
connection lifecycle and cleanup through a backend contract. Concrete Go types
remain behind the native backend adapter. SOCKS5 uses the same reviewed
destination allowlist and authenticated loopback CONNECT bridge.

A playback run shares one session for identical canonical configurations,
including duplicate imports. WireGuard's handler supplies a separate peer
identity so differing settings for the same private/public-key pair cannot start
competing peers. That conflict policy is applied by the generic registry, without
WireGuard-specific logic in the viewer. Failed initialization remains a failed
route; it never selects System network automatically.

Saved records now write version 2 with a stable protocol ID and handler-owned
configuration text inside the existing encrypted, no-backup envelope. Version 1
records remain readable without rewriting; an explicit save/delete writes the
new format and retains the remaining profile IDs, names and canonical configurations.
Older APKs that understand only version 1 cannot read a record after that first
version 2 mutation; downgrading does not migrate the record back automatically.
Unknown protocol IDs, mismatched formats and corrupt records fail closed without
replacement. The eight-profile, 8 KiB total storage bound and strict 8 KiB UTF-8
import bound remain in force; version metadata also counts toward total storage.
Manual entry applies the same byte bound. The protocol refactor added no
provider authorization or device playback evidence; the subsequent SOCKS5
extension below uses those same boundaries.

The final refactor passed local Android JVM tests, lint, instrumentation
compilation, release isolation, both APK builds and Go transport tests. Both
APK certificates matched the shared debug identity. All six existing importer
screen and warm-handoff tests passed on a separate clean Android 16/API 36
x86-64 emulator. Registry/backend tests use controlled fixtures and do not
establish a genuine tunnel or provider playback result.

### OpenVPN configuration

The certificate-only prototype accepts one OpenVPN profile within the existing
8 KiB UTF-8 input and total protected-storage bounds. It requires `client`,
`dev tun`, `proto udp` or `proto tcp-client`, one `remote NUMERIC_IP PORT`,
`remote-cert-tls server`, `verify-x509-name NAME name`, `tls-version-min 1.2`
or `1.3`, and inline `<ca>`, `<cert>` and unencrypted `<key>` blocks.
`nobind` is optional. Only whole-line comments are accepted. The server name
is verified independently of its numeric transport address. Hostname remotes
are unsupported because the upstream bootstrap resolver does not provide
confirmed cancellable worker termination.

Unknown/duplicate options, passwords, external files, scripts/plugins, encrypted
keys, authentication challenges, TAP, compression directives, `tls-auth` and
`tls-crypt` are rejected. Standard commercial-provider exports that require
username/password authentication are outside this subset. The Windscribe setup
flow continues to use its WireGuard export.

Import preview hides certificates, private keys and the certificate-name
directive; it exposes only the numeric endpoint. Import/Save validates the
bounded grammar, not cryptographic validity or a working connection. The native
engine must validate the actual inline key/certificates and complete the TLS
handshake when playback starts. Protected storage and provider assignment use
the existing generic route records; credentials are never copied to provider
metadata or saved presentations. Canonical option order, port and IP spelling
share a route; any certificate/key/security-option change changes its identity.

OpenVPN3 runs through an owned datagram socketpair and private userspace stack,
without an Android system VPN. Destination DNS uses only the tunnel's assigned
DNS; unsupported pushed routes/DNS/proxy settings fail instead of falling back
to System. Normal origin HTTPS verification and the reviewed destination
allowlist remain unchanged. Preparation has an independent 30-second deadline.
Stop/background/destruction signal cancellation before the backend is published.
Unconfirmed bounded native cleanup retains its worker safely and prevents
another OpenVPN initialization until process shutdown.

The pinned native source/build recipe and licensing provenance are recorded in
[`routebridge/native/README.md`](../../../apps/android/routebridge/native/README.md).
Every distributed debug APK must retain the embedded notices and its matching
`routebridge-openvpn-source.tar.gz` companion. Owned tunnel results do not establish
commercial-provider compatibility, media playback, physical-device performance
or Android TV behavior.

Nine new parser/protected-store JVM tests passed alongside the complete Android
JVM suite, lint, release isolation, instrumentation compilation and both APK
assemblies. Both certificates match the shared debug identity. All 41 combined
importer/provider/picker/JNI tests passed on the disposable Android 16/API 36
x86-64 emulator, including the registered OpenVPN preview's redaction and real
explicit Save tap. Each of the three owned native tunnel cases passed with
outer UDP and TCP on that emulator, and with TCP on the measured 16 KiB emulator.
The actual ARM64 and x86-64 JNI libraries have verified 16 KiB LOAD and APK data
alignment; this does not claim ARM64 execution.

### SOCKS5 configuration

The debug importer accepts `socks5://proxy.example.test:1080` and an optional
`socks5://username:password@proxy.example.test:1080`. The port must be explicit.
IPv4 and bracketed IPv6 proxy endpoints are supported. Usernames and passwords
must each decode to 1–255 UTF-8 bytes; controls, invisible formatting characters
and malformed UTF-8 are rejected. In credentials, encode every character except
ASCII letters, digits, `-`, `.`, `_` and `~` as UTF-8 percent escapes. For example,
the synthetic password `p@ss+word` becomes `p%40ss%2Bword`; `+` never means space.
Only an empty path or `/` is accepted. Queries, fragments, extra options,
`socks5h://`, scripts and other authentication mechanisms are unsupported.

Canonical saved text normalizes host case, port spelling, the optional trailing
slash and credential escapes, so equivalent imports share one route. Distinct
endpoints or credentials retain separate sessions. The preview shows only proxy
type and endpoint; credentials remain in the existing encrypted, no-backup
configuration store and never enter provider settings or playback intent data.
Save and provider assignment still do not activate a route.

An anonymous profile requires the SOCKS5 no-auth method. A profile containing
credentials requires RFC 1929 username/password authentication and rejects a
proxy that selects no-auth instead. SOCKS5 does not encrypt its outer connection
or its username/password exchange; provider HTTPS remains end-to-end with its
existing CA and hostname checks. This backend supports TCP CONNECT only, with
destination hostnames resolved by the configured proxy. Only proxy bootstrap
DNS uses System network. Failure never tries a direct destination or changes the
saved provider selection. No Android VPN or device route is installed.

New owned-server fixtures cover negotiation, decoded authentication, remote
hostname forwarding, opaque bytes, TLS success and certificate/name rejection,
malformed/refused replies, cancellation, independent routes and teardown. JVM
fixtures cover canonical import identity, protected storage with existing
profiles, provider assignment, byte bounds and safe summaries. A JNI smoke
fixture initializes/closes the listener without contacting a proxy. Host Go
race tests and Android JVM tests, lint, release isolation and signed APK builds
passed. The pinned Go/NDK build produced ARM64 and x86-64 API-26 libraries with
16 KiB-compatible load segments. Both APK certificates match the shared debug
identity. All 15 JNI/import/handoff/provider-setting fixtures passed on the
disposable Android 16/API 36 x86-64 emulator, including credential-free SOCKS5
preview and explicit save. This does not establish commercial-proxy or provider
playback behavior; the persistent development device was preserved.

### Existing settings and reset

Imported configurations keep their existing encrypted record and IDs. Provider
defaults use a new, separately encrypted no-backup slot with JVM and OS file
locking across the complete operation. Current-format per-stream settings retain
their display names and route references. Consistent earlier defaults can seed
the provider default. Conflicting defaults or any explicit historical feed
override require review: no route is inferred and playback remains blocked until
the user selects and saves one provider route. Explicit provider setup supersedes
the historical route choices for that provider, without erasing those records.
Saving ABEMA does not resolve a pending Twitch choice. Corrupt records fail
closed, and a deleted route is shown as unavailable rather than replaced by
System network. Unsaved provider route references survive recreation; no keys,
credentials or imported configuration enter saved UI state.

The six-stream catalogue does not migrate the earlier four-stream record. A
recognized obsolete record blocks setup/playback and offers **Reset stream
settings**. Only that explicit action replaces stream metadata with current
defaults; saved imported routes and separate provider configuration are untouched.
Reads never reset automatically. Corrupt/unreadable records remain fail-closed
and are not eligible for this obsolete-record reset. This is prototype recovery,
not a backwards-compatibility guarantee.

The earlier source-oriented code/model names remain where renaming would broaden
the change; current UI and product documentation use the agreed terms. The old
per-stream editor is retained as historical code, not linked from the current
picker. The sections below record its earlier implementation and verification.

## Provider setup verification

### Twitch login in Providers

**Prototype → Providers → Configure a Twitch instance** presents saved login status,
Connect/Reconnect, explicit revalidation and local Forget beside the route
editor. The available/expired/unreadable status describes protected storage;
availability is not a claim that Twitch currently accepts the grant. Twitch
live/replay preparation still validates before use. Playback errors direct the
user back to Providers → Twitch.

This reuses the already approved debug Smart TV LOCAL device-authorization
flow, exact identity and zero scopes, the same process repository and the
unchanged encrypted no-backup slot for the deterministic default instance. Existing records remain usable without
copying, migration or a second grant. Historical diagnostic cases remain
available with their original behavior. No provider password, cookie, token,
account identity or raw response enters observable or saved UI state. Only
the transient activation code and the validated browser handoff appear while
connecting; neither is retained in recreation state or logs. Additional instances
use separate protected slots as described above.

Protected reads and writes run on a worker. Rotation, editor departure and
explicit Cancel cancel pending network operations and close their transports.
Connect pauses during the existing package-targeted Brave/Chrome handoff and resumes
after returning, subject to the original challenge and validation deadlines.
Revalidation cancels when backgrounded. Forget cancels pending network work;
once accepted, its bounded local clear runs independently of the editor
lifecycle, including when rotation/departure cancels its UI observer. New
commands wait until that clear completes. The existing repository lock orders
an already-entered save before the clear, preventing that save from restoring
the grant afterward. Successful Forget clears only the existing local slot
and invalidates playback through the existing stored-grant checks; it neither
revokes the provider grant nor clears browser sessions. If clearing fails,
the retained on-disk grant may remain usable by another process; the UI asks
the user to retry Forget before playback.

Revalidation calls the existing explicit local-retention extension: only a
still-available grant that passes fresh official validation can be retained
for up to seven days, shorter for known expiry. Expired grants require
Reconnect. Ordinary playback never extends stored retention automatically.
Provider route selection remains a separate explicit save; configuring or
changing login does not connect a route or start playback.

Added fake-transport controller coverage exercises exact authorization,
unexpected scopes, failed-save preservation, stored expiry, browser pause/resume,
explicit revalidation, delayed authorization after Forget, queued-clear editor
departure, pending-clear busy state, synchronous completion, foreground return,
already-entered saves, failed clears and background cancellation of revalidation.
Focused Compose
tests cover the Twitch-only section, safe status and explicit action controls.
All 14 controller JVM tests passed, along with the complete Android JVM suite,
lint, instrumentation compilation, release isolation and both APK assemblies.
Both certificates matched the shared debug identity. The focused seven
provider/login Compose tests passed on a separate clean Android 16/API 36
x86-64 emulator. Scoped repository checks and documentation build passed.
No private authorization, genuine account-site handoff, encrypted Android
persistence or provider playback test was performed for this change.

### Merged six-stream build

The merged build passed 580 debug and 416 diagnostic JVM tests, lint with no
errors (eight warnings and one hint), instrumentation compilation, release
manifest/assets isolation and both APK assemblies. Scoped repository hooks,
documentation build and a redacted source secrets scan passed without edits.
Both APK certificates matched the shared debug identity. Independent static
reviews found no merge-specific playback or setup regression; these are not
device playback observations.

The app APK is 16,916,319 bytes, host-owned, SHA-256
`9bcce48d02e3d7e27ca442ca784f83a9c41b7ebdf2f102f0ca152cef2cab1737`.
`adb install -r` updated the connected Pixel 6/oriole with app data retained;
the installed APK checksum matched. No phone UI, obsolete-reset, handoff or
provider playback test was run for this installation. Those runtime checks
remain pending, including corrected warm Share/Open handoffs.

### Earlier provider-screen build

The settled provider-screen build passed 562 debug and 404 diagnostic JVM tests,
lint, instrumentation compilation, release manifest/assets isolation and both
APK assemblies. Lint retains eight existing warnings and one hint. Both APKs
matched the shared debug signing certificate. Scoped hooks, documentation build,
redacted secrets scans and diff checks passed. No dependencies, imported records,
provider playback code or generated outputs were changed.

The host-owned app APK is 16,916,319 bytes, SHA-256
`a674f2c74e84fd166ee359ae3fe7307f33072fa0e3cf95c9a6a6e475ab7e747d`.
The host-owned test APK is 1,210,879 bytes, SHA-256
`83f9b1d644cdf4b8a8ce8ee084b94030d480033ae1d641bdc5cd95d12d7c10ee`.
`adb install -r` installed the app on Pixel 6/oriole, Android 17, retaining data.

The unlocked 22-test device run passed 19 tests, including all four new provider
screen tests, four stream-picker tests, two retained source-editor tests, three
route-screen tests and six intent/reader tests. Three Share/Open harness tests
failed at ActivityScenario launch: AndroidX matches action/data/type at every
lifecycle callback, but Tachiai deliberately clears the delivered intent before
CREATED to prevent attachment replay in saved state. Lifecycle diagnostics
confirmed that the importer itself reached RESUMED. The test-only correction
launches a clean explicit intent, then delivers an actual singleTask handoff;
the security behavior remains intact. This tests warm handoff, not cold launch.

The corrected repeat passed its six non-UI tests, but the phone had locked before
UI checks and all 16 UI tests lacked visible Compose hierarchies. That repeat is
not usable UI evidence. The corrected warm-handoff tests and actual Providers
Activity navigation remain pending an unlocked run. No provider login, VPN
change, playback or real configuration import was performed. Android encrypted
provider persistence, cold handoff, route activation and TV behavior remain
unverified; the user-reported Proton import is not a tunnel test.

## Historical importer scope

The October 8 follow-up adds a debug-only Connections screen, reached from
**Prototype → Connections · import / setup**. This implements configuration
handoff and protected local storage, **not** a proxy, VPN or per-feed routing
backend. Importing, saving or deleting a profile does not change playback,
Android's network or an existing Proton connection. Profiles remain labelled
saved only, not connected or tested. Historical playback examples are unchanged.

The user selected WireGuard and HTTP CONNECT as the first mechanisms and Proton
as the first guided provider. Other providers can supply the same supported
configuration types; neither the parser nor the profile identity is tied to
Proton, Japan, ABEMA or viewer slot A/B. HTTP proxy transport/authentication and
WireGuard userspace-stack feasibility remain separate gates in the
[routing assessment](source-network-routing.md).

## Three file handoffs

1. **Share to Tachiai:** receive one attachment through Android `ACTION_SEND`.
   A description accompanying the attachment is ignored, never fetched or
   interpreted as configuration. Sharing only a browser-page link is rejected.
2. **Open with Tachiai:** receive one content URI through `ACTION_VIEW`, including
   a matching ClipData permission handoff when supplied by the sender.
3. **Import file:** launch Android's document picker as an explicit fallback.
   The picker permits any file MIME type because `.conf` has no universal
   mapping; the same bounded parser still validates the selected bytes.

The exported Share/Open filters advertise plain text, `text/x-conf`,
`application/octet-stream` and `application/x-wireguard-profile`, not every file
or web link. Browser MIME choices and chooser behavior are device-dependent;
the actual Proton/Brave handoff is not established by platform intent tests.
[Android receiving shared data](https://developer.android.com/develop/ui/compose/sharing/receive),
[document picker](https://developer.android.com/training/data-storage/shared/documents-files).

All paths read a local `content://` URI through Android's resolver. They do not
fetch arbitrary HTTP URLs, open caller-supplied filesystem paths, request broad
storage access or retain durable URI permissions. The resolver enforces actual
access; an already-readable URI need not carry a new grant flag. Conflicting or
multiple attachments and Tachiai-owned content authorities are rejected.
Names, MIME types and declared sizes are untrusted and do not authorize bytes.

Each import converges on a credential-free endpoint/type preview, a user-entered
name and explicit **Save connection** or **Discard import**. No auto-save,
connectivity check, provider request or playback start occurs. Manual paste/entry
is also available, with hidden input and the same parser. No secrets or drafts
are stored in saveable UI state, Bundle or diagnostic output. Recreation discards
an unsaved draft; the incoming intent is cleared so it is not replayed.

## Historical source setup and connection assignment

Each row in **Prototype** now has **Source setup**. This edits the display name
and default connection for that existing resource, with optional **Feed A** and
**Feed B** overrides. The four existing live/replay resources remain fixed;
service/channel browsing and adding arbitrary sources are not implemented.
**Add connection · Proton / import** opens the same guided importer, then a
saved profile can be selected on return. Saving a source setup is explicit.

**System network** inherits Android's current network, including an external
Proton VPN if active. It does not mean bypass VPN or use the physical connection.
An override can inherit the source default, select System network explicitly,
or reference a saved profile. Two copies of one source can therefore resolve
different connection references. A/B identifies the ordered feeds, not their
primary/floating video roles; swapping video roles will not reinterpret setup.

These are assignments, not working route transports. The picker displays the
resolved connections. **Open viewer rejects any saved-profile assignment before
provider preparation**, preserves the chosen sources and explains that the
backend is unavailable. No implicit system fallback, VPN activation, provider
request or route test is performed. System-only selections retain the existing
playback path; settings and display names are frozen for a playback run.
Deleted/missing profile references remain visible as unavailable rather than
silently changing the selected route.

The source record stores only names, route modes and profile IDs/display names,
not WireGuard keys or proxy credentials. It uses a separate encrypted no-backup
slot. Missing initial storage gives explicit System defaults; corrupt or
unreadable storage disables playback rather than substituting defaults. A shared
in-process lock plus a fixed OS file lock covers the complete read/mutation,
because playback runs in separate processes and Android AtomicFile does not
serialize cross-process access. A user-approved save may complete after the
editor closes; readers wait for its committed record. Unsaved **nonsecret source
metadata** survives recreation, unlike secret connection-import drafts.

Remaining gates are a session-scoped transport backend, complete native and
ABEMA bootstrap/license request coverage, no-leak/failure tests, and actual
Proton/browser handoff and phone/TV usability checks. Configuration does not
prove endpoint reachability, content availability or provider permission.

## Guided Proton setup

**Set up Proton** opens `https://account.protonvpn.com/downloads` in an external
browser. The screen guides the user through Downloads → WireGuard configuration,
Android, a desired Japan server, Create and Download. Then open the downloaded
file with Tachiai or share that file into Tachiai. No filename renaming is needed
in our importer. File picking remains available when the browser offers neither
handoff. No account password, browser cookie or Proton app state is collected.
[Proton export instructions](https://protonvpn.com/support/wireguard-configurations).

This imports a configured server, not Proton's app profile, automatic server
selection, Smart Protocol or Stealth. Plan availability, configuration lifetime,
revocation, simultaneous tunnel behavior and provider acceptance are not inferred
from a successful import. A genuine browser handoff requires a separate
user-controlled export test; no private account-site API is added.

## Guided Windscribe setup

**Set up Windscribe** opens the official
[My Account WireGuard generator](https://windscribe.com/myaccount#configgenerator-wireguard)
in an external browser, alongside the existing Proton action. Sign in there,
choose Config Generator → WireGuard, then a location and port (Windscribe suggests
443 when unsure). Generate a new key pair for this device and choose Download
Config. Windscribe also permits selecting an existing generated key pair; using
a separate pair avoids accidentally reusing one peer on multiple devices.
Open or share the downloaded configuration file with Tachiai, then review and
explicitly save it. Use **Import file (fallback)** if the browser offers neither
handoff. Tachiai receives only the selected file, not provider passwords,
browser cookies, an account session or Windscribe app state.

Official guidance checked on 2026-10-08 says configuration generation requires
a paid account: Full Pro includes all locations, while Build-A-Plan permits
its paid locations. This flow uses WireGuard; Windscribe's OpenVPN and IKEv2
exports are not supported by Tachiai's importer.
[Windscribe export instructions](https://windscribe.com/knowledge-base/articles/where-do-i-access-my-wireguard-configs),
[supported export protocols](https://windscribe.com/features/config-generators).

The existing generic one-peer parser supports the fields documented in
[Windscribe's manual setup guide](https://windscribe.com/knowledge-base/articles/manual-wireguard-router-setup-guide-dd-wrt),
including a preshared key, DNS IP, interface address and endpoint. The added
synthetic fixtures use invented keys and an `.example.test` endpoint. They
cover parser canonicalization/redaction and the warm Share preview path with
keys hidden and explicit save still required. They are not genuine exported
configurations or evidence that a Windscribe connection works.

Public documentation establishes the export steps and plan requirements. Local
Android JVM tests, lint, instrumentation compilation, release isolation and both
APK assemblies passed; both certificates matched the shared debug identity.
All 13 connection screen, warm handoff, provider and historical source-editor
instrumentation tests passed on a separate clean Android 16/API 36 x86-64
emulator. The historical restoration test now dismisses the keyboard and checks
selection before and after restoration, distinguishing a missed tap from a
restoration defect. The persistent development emulator was untouched.

No authenticated browser export, actual Windscribe file handoff or Windscribe
tunnel/playback observation was performed. Warm handoff used synthetic files;
it does not establish provider connectivity. Importing does not control
Windscribe's app, provide automatic server selection, or establish endpoint
reachability or content availability.

## Supported data and boundaries

- Maximum input: 8 KiB, strict UTF-8 text, optionally with a leading BOM and CRLF.
- WireGuard: exactly one Interface and Peer; private/public keys, interface
  addresses, peer AllowedIPs and endpoint required. Optional DNS IPs, MTU,
  keepalive and preshared key are supported. Numeric IPv4/IPv6 CIDRs and bracketed
  IPv6 endpoints are validated without hostname resolution. Comments are removed
  before canonical encrypted storage. Keys must be canonical Base64, 32 bytes and
  nonzero. DNS search domains, IPv6 zones/mapped addresses, repeated fields,
  additional peers and other options are outside this first subset.
- Shell hooks, Table, SaveConfig, FwMark and ListenPort are rejected rather than
  executed or silently omitted. This imports supported data, not `wg-quick`
  commands. [WireGuard format](https://git.zx2c4.com/wireguard-tools/about/src/man/wg.8),
  [wg-quick options](https://git.zx2c4.com/wireguard-tools/about/src/man/wg-quick.8).
- HTTP proxy: one `http://host:port` URL, optional username/password user info,
  no query, fragment or non-root path. The preview excludes credentials. HTTPS
  proxies, SOCKS, archives, subscription formats and executable configuration
  are not accepted yet. Saving credentials does not establish safe proxy auth;
  transport implementation must separately protect the outer path.

Resolver/parse work is off the UI thread. Descriptor acquisition supplies an
Android cancellation signal. Regular files use position-based bounded reads;
Android 11+ pipes use nonblocking reads and bounded polling. Android 8–10 rejects
streaming descriptors explicitly, with local-download/manual-entry guidance,
without raising the app minimum SDK. Reads check a ten-second deadline and
cancellation; superseded/destroyed
imports do not deliver stale previews. A UI deadline also cancels late results.
Android cannot force a non-cooperating remote content provider to finish a Binder
descriptor-open call; that remains a platform boundary, not a hard thread-erasure
guarantee. File reading is separate from the saved-profile/manual-parse worker,
so paste remains available even when a provider's descriptor-open call hangs.

The profile record uses the existing AES-GCM AndroidKeyStore/AtomicFile storage
in a separate fixed no-backup slot, not a Twitch grant slot. Same-process locking
serializes mutations across Activity recreation. Maximums are eight profiles and
8 KiB total serialized plaintext. Full/corrupt/unavailable storage fails without
eviction or a plaintext fallback. Save/delete returns sanitized summaries from
the committed mutation, not a second read that could misreport success.

Deleting a connection overwrites the encrypted record without that entry. It
does not revoke a server-side configuration, erase its original downloaded file
or guarantee forensic erasure of old filesystem blocks. The preview explicitly
reminds the user that the original download remains outside Tachiai.

## Verification

### Initial importer-only verification

The settled October 8 build passed 546 debug and 395 diagnostic JVM tests,
including 13 new parser/store tests, debug lint, instrumentation compilation,
release manifest/assets isolation and app/test APK assembly. Lint retains eight
existing warnings and one hint, with no errors or new warnings. Both APKs passed
`apksigner verify --print-certs` and matched the shared debug certificate.
The pinned tools image reused the cached SDK 37.2 and shared signing key; no
SDK/minimum-version change, new dependency, shared native core or regenerated key
was needed. An initial missing script mount, Gradle cache-owner timeout, test-only
Compose import error and an API-30 lint failure were resolved before this result.
No locks were deleted, lint baseline added or signing bypass used.

The app APK is host-owned, 16,916,319 bytes, with SHA-256
`393c3be62728099ad009933f0d333343e13bad3edfb586f876cf1d5d48d0e932`.
The test APK is host-owned, 1,187,988 bytes, with SHA-256
`cad7ac0db2692bea894a3696f63b5e40330b4f08db6a04644558b4de2a384ebb`.
The APK contains the new importer classes and debug manifest entry. Scoped hooks,
documentation build and direct redacted secrets scans passed. Diff review found
only the importer, entry wiring, tests and documentation, with no tracked build
outputs or provider-playback changes.

The connected Pixel 6/oriole reported an unlocked Android session before tests,
but wireless ADB subsequently closed and discovery returned no devices. No new
APK was installed and no device tests were executed. The compiled Android tests
cover Share/Open preview, new-intent replacement, recreation, file-picker result
handling, URI ambiguity, real synthetic ContentProvider bytes, slow/cancelled
pipes, file slices/truncation and explicit save/discard controls. Compilation
does not establish those runtime results or actual file-picker chooser behavior.
Actual Proton export, Brave chooser behavior, encrypted Android persistence,
TV import and network connectivity remain unverified; importing a synthetic
fixture is not a VPN test.

### Source setup follow-up

The final source-setup build passed 554 debug and 399 diagnostic JVM tests,
lint, instrumentation compilation, release manifest/assets isolation and both
APK assemblies. The eight source model/store tests include independent duplicate
route resolution, fail-closed corruption and lock ownership/release. Lint retains
eight existing warnings and one hint. Both APK signatures match the shared debug
certificate. Scoped hooks, documentation build, redacted secrets scans and diff
checks passed; no dependencies, provider protocols or generated outputs changed.

The final host-owned app APK is 16,916,319 bytes, SHA-256
`5f0c87b76dcb37f8ec19363f32c1bc6ed298b9b751065df3c283efbda4b2672e`.
The host-owned test APK is 1,225,493 bytes, SHA-256
`fe7b938a56596ad6f40dc463fe68180e432284669acbc8c40172cf17bd3be019`.

Wireless ADB reconnected and `adb install -r` updated the Pixel 6/oriole,
Android 17, without clearing app data. Six intent/reader instrumentation tests
passed against actual synthetic provider bytes, including sliced files,
truncation, oversize, UTF-8, cancellation and timed pipes. The first full run
exposed an incorrect OPENABLE-category test assumption and a fixture-only Kotlin
runtime failure in the standalone test-provider process. The assertion now
matches the inspected AndroidX contract; the test-only provider uses Java without
depending on the target APK's Kotlin runtime. The corrected six-test run passed.

The phone subsequently locked. The eight compiled UI/handoff/source-restoration
tests have not yet completed on device. Actual Proton export/browser handoff,
Android encrypted profile/source persistence, picker-to-setup lifecycle and TV
usability remain unverified. No provider login, VPN change, playback request or
real configuration import was used for this follow-up.
