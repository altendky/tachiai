# Native playback access experiments

## ABEMA public player/licensing investigation — 2026-10-06

Read-only static inspection of anonymous ABEMA News HTML and the public
`5206` application and `abema-dashjs` chunks found application-supplied
`com.widevine.alpha` configuration. The application obtains a license URL
template from playback-resource metadata and adds a media token, plus optional
ticket/content fields. Its response filter conditionally processes
`abematv-dash` responses whose content type is exactly `application/json`.
The underlying bundled Widevine adapter also has a binary-response path; a
separate bundled DRMToday JSON adapter must not be mistaken for evidence of
ABEMA's active path. No embedded secret, token-generation or proprietary
response-processing algorithm was reproduced or used.

These are static observations, not a runtime trace or a native license contract.
ABEMA's [engineering presentation](https://speakerdeck.com/ygoto3/designing-a-playback-client-for-large-scale-live-sports-events)
describes DASH/Widevine on Android, while its
[license-proxy article](https://developers.cyberagent.co.jp/blog/archives/49162/)
describes internal entitlement checks. Neither establishes a third-party helper
that can service Media3's own challenge. A browser's existing response/session
must not be assumed transferable to another DRM session.

The user subsequently approved a debug-only isolated ABEMA inspection screen
inside the existing app to distinguish the actual response path without
credentials, tokens, license-body capture or native handoff. Its
[host/tool boundaries](security-and-privacy.md#explicit-abema-playback-inspection)
and [launch procedure](android-playback-spike.md#build-modes-for-browser-diagnostics)
are documented separately. Implementation and classifier tests are not device
playback evidence; runtime results must record the engine, network/account
conditions and closed output before settling a conclusion.

### Inspection build and first launch

The pinned Android tools container passed 315 debug and 314 diagnostic unit
tests (629 total, zero failures/errors), debug lint/assembly and release Kotlin
compilation. Seven standalone collector tests also passed, including rejection
of account/SPA navigation and suppression of provider values. Debug lint retained
the existing two warnings and one hint. Targeted repository hooks, documentation
build and redacted secrets scans passed. The first container attempt selected
its unwritable bundled SDK and failed before compilation; explicitly selecting
the established `/sdk` mount resolved that environment error.

The APK is 16,407,019 bytes, host-owned, SHA-256
`ca95633fd6aed5742f6b45e81bf6642020c80248f397ff7c5b40bcdeffe1b57c`.
`apksigner verify --print-certs` matched the shared debug identity before the
data-preserving phone update. The processed debug manifest includes the isolated
activity; the release manifest and compiled release classes exclude it.

The first Pixel 6 launch used Android 17 and Android System WebView
`153.0.8010.36`, the explicit desktop identity, and the separate diagnostic
profile without imported account state. Its isolated process emitted ENABLED
at 10:48:03 and DISABLED at 10:48:05 device time. Shazam was foreground when
the subsequent activity check ran. No license metadata or body was collected,
and the connection/exit condition was not confirmed for this launch. This does
not establish a working page, the reason for the initial stop, or a license
response path. The temporary debugger forward was removed, and phone testing
was suspended pending availability. Earlier examples and profile data remain.

After the user confirmed phone availability and Proton connectivity, a retry at
10:51:52 enabled then disabled inspection immediately. A temporary screenshot
confirmed the secure lock screen, so the playback/metadata check remains pending
unlock. Re-entering the same process did not raise a profile-initialization
error. No ABEMA response-path conclusion is drawn from either interrupted launch.

### Unlocked inspection results

After unlocking, the same Pixel 6/Android 17/System WebView `153.0.8010.36`
loaded both fixed pages in the isolated diagnostic profile. The desktop identity
remained the unsupported comparison above. The user confirmed Proton was
connected and the VPN icon was visible; the exit location was not independently
measured. No account credentials or imported cookies were used. ABEMA's
introductory survey appeared repeatedly; choosing its gray “Later” option did
not enter demographic or account information.

Between approximately 11:06 and 11:12 device time, temporary screenshots showed
the September day-15 sumo replay advancing and News showing its current broadcast,
including after reload. This is bounded original-WebView video evidence, not
native playback, an audio confirmation, sustained playback or a licensing
contract. Screenshots remain temporary, not repository artifacts.

The initial 45-second traces matched neither exact tracked license route. A
small collector refinement then added aggregate network-event counts and an
`OTHER_LICENSE_ROUTE` marker for other paths on the exact HTTPS
`license.abema.io` host, without printing paths, queries, headers or bodies.
Successful CDP setup acknowledgments now produce a fixed confirmation marker.

| Bounded page trace | All network requests / responses / failures | Matched license-host requests / responses / failures |
| --- | --- | --- |
| News reload, collector attached before reload | 312 / 261 / 51 | 0 / 0 / 0 |
| Replay selection, collector attached before navigation | 169 / 143 / 27 | 0 / 0 / 0 |

These aggregate failures are not classified as playback, region or DRM errors;
some responses/failures can belong to requests initiated before attachment.
The counts demonstrate live page-target network observation, not exhaustive
coverage of worker/CDM networking. No license HTTP status or response type was
observed, so the static JSON-processing branch remains unverified. Other hosts,
other targets, requests before attachment or retained browser state remain
possible explanations; no site data or DRM state was cleared to force licensing.
Eight collector self-tests pass, including mocked CDP traffic proving that
unrelated requests do not exhaust the selected-license event ceiling and that
provider values remain absent from output. No license/helper/native handoff
was implemented. The next investigation must establish the active browser
transport/target coverage before treating the zero count as a licensing finding.
Inspection was then closed; the final lifecycle markers report DISABLED.
The temporary debugger forward was removed and the original auto-rotation and
user-rotation settings restored. Provider/profile data and older cases remain.

### Related-target and media follow-up

The user approved broader related-target coverage and media diagnostics. Only
the collector and documentation changed; the installed APK, original player,
profile and preceding examples were not replaced. The same unlocked Pixel 6,
Android 17, WebView `153.0.8010.36` and unsupported desktop identity were used
at approximately 11:40–11:49 device time. The VPN icon remained visible;
the exit country/provider acceptance was not independently measured. No account
credentials or imported cookies were used. News and the same free sumo replay
both visibly played after dismissing the introductory survey with “Later.”

Both optional `Media.enable` and page-scoped related-target autoattachment
acknowledged success. No eligible dedicated-worker or separate-frame session
attached during these traces; shared/service workers and unknown-origin/route
targets are deliberately excluded. Thus worker session handling is covered by
mocked tests, not established as an active ABEMA device transport. Attachment
never pauses JavaScript, and there is no browser-wide target discovery.

Settled-page Media diagnostics, collected separately after each page loaded,
reported the following for the video-bearing player:

| Observation | News | Free sumo replay |
| --- | --- | --- |
| CDM configuration | Clear Key (`org.w3.clearkey`) | Clear Key (`org.w3.clearkey`) |
| CDM attached | true | true |
| Audio/video decrypting demuxer | both true | both true |
| Platform video decoder | true | true |
| Load source category | BLOB | BLOB |
| Audio/video track encryption | OTHER with initial classifier | CENC in final classifier run |

The initial scheme classifier recognized lowercase `cenc`/`cbcs`, but not
Chromium's uppercase enum serialization. Adding only the fixed uppercase
aliases changed the replay's classification to CENC in a fresh 30-second trace;
it did not modify browser behavior. News was not rerun with that refinement,
so its earlier OTHER classification is not retroactively changed. Auxiliary
audio-only players also reported unencrypted tracks; those must not be confused
with the video-bearing player. A navigation trace initially contained cached
News markers before selecting Replay, so separate settled-page traces were
used for this comparison rather than attributing every event to Replay.

The 45-second settled News trace counted 47 requests, 43 responses and four
failures, with eight DASH-MIME and 19 MP4-MIME responses. The settled Replay
trace counted 44 requests, 38 responses and eight failures; the final 30-second
replay refinement counted 12 requests, 12 responses and one failure, including
eight MP4-MIME responses. Every trace still matched zero requests/responses on
the exact tracked license host. Counts may include pre-attachment requests and
are not necessarily unique across targets. Unknown MIME values remain OTHER;
no manifest, media or license body was retrieved by the collector.

This establishes a Clear Key CDM path for these specific browser experiments,
not that all ABEMA sources use Clear Key or that the static Widevine branch is
unused everywhere. CENC is encrypted media; the Clear Key name does not mean
clear/unencrypted media, available keys or an authorized native integration.
No key value, challenge, license body, native handoff or proprietary processing
algorithm was inspected or reproduced. The earlier generic Widevine hypothesis
does not describe the CDM configuration observed here. The remaining native
gate is a documented/authorized acquisition/configuration contract for the
active path, not simply choosing a DRM UUID in Media3.

The collector now has 17 passing tests for strict origin/route eligibility,
session-scoped request IDs, recursive ownership/refusal, detached descendants,
bounded attachment attempts, optional-domain failure, account navigation and
media-output redaction/deduplication. Review caught nested-detach commands being
sent through the wrong parent and an unbounded refused-attachment command map;
both were corrected and regression-tested. The existing certificate-verified
APK was reused without rebuild. Inspection was closed, final lifecycle markers
reported DISABLED, the exact debugger forward was removed and rotation settings
were restored. Profile/site data and earlier examples remain intact.

Diagnostic fields follow Chromium's public
[media property definitions](https://raw.githubusercontent.com/chromium/chromium/main/media/base/media_log_properties.h),
[serialization](https://raw.githubusercontent.com/chromium/chromium/main/media/base/media_serializers.h)
and [related-target protocol](https://raw.githubusercontent.com/ChromeDevTools/devtools-protocol/master/pdl/domains/Target.pdl).
These explain the metadata, not ABEMA's third-party licensing permission.

### Public key-system selection follow-up

On 2026-10-06, anonymous computer-side HTTPS reads of the News entrypoint
confirmed that its four relevant static script references matched the cached
entrypoint. Fresh application, `5206` and `abema-dashjs` bytes matched the
previously inspected copies. This check used no browser account or phone
session, and does not establish computer-side playback or region acceptance.
The public [application chunk](https://abema.tv/assets/compat/3850f3e68c3aa3c0.5206.js)
and [player chunk](https://abema.tv/assets/compat/284cdb771dbf8da3.abema-dashjs.js)
are mutable provider assets, not a supported integration contract. Source
excerpts were parsed with Acorn and literal tokens masked before output;
only standard key-system names and closed structural classifications were
retained. No key value, challenge, license body or proprietary transformation
was investigated or reproduced.

The application has distinct capability/device-classification and
player-selection stages:

| Stage | Static observation | Limit |
| --- | --- | --- |
| Capability cache (`5206`, module 1955) | Tests browser EME support with fixed audio/video configurations; caches supported entries in Widevine → PlayReady → Clear Key preference order | A capability result is not the selected playback CDM |
| Device classification (module 44680) | Awaits EME capability initialization, including before checking cached device identity; maps browser OS/version and preferred DRM into a device-type request and caches the returned deviceTypeId | This is device discovery, not playback-resource acquisition; its preferred-capability getter returns null when Clear Key is first |
| Channel metadata (modules 40724/99796) | Adds a successful deviceTypeId to a browser metadata request, then initializes channel/catalog state | Neither these consumers nor the device-type DTO establish a native source/license contract |
| DASH player setup (module 47994, `PlayerDashJS`) | Registers both Widevine and Clear Key protection data, using the same input license URL and a lower numeric priority for Widevine | Registration does not prove either entry is used for this content |
| Bundled DASH selection (`abema-dashjs`, module 1737) | Discovers candidates from content-protection/segment initialization signaling, sorts candidates by ascending configured priority, then requests key-system access | Static structure does not reveal the actual candidate list or accepted request |
| Actual phone playback (preceding media trace) | The video-bearing News and replay players reported Clear Key CDMs | The runtime inputs and exact branch producing that choice remain unobserved |

[dash.js documents](https://dashif.org/dash.js/pages/usage/drm.html#key-system-priority)
lower numeric values as higher preference. Its current public
[selection controller](https://github.com/Dash-Industry-Forum/dash.js/blob/development/src/streaming/protection/controllers/ProtectionController.js)
combines content-protection candidates, application preferences and browser
capability negotiation. The bundled selection structure above independently
confirms the priority/candidate distinction; neither static implementation
establishes the precise cause on this phone.

The trace therefore corrects a Widevine-only reading of the public setup,
but does not settle whether content signaling, browser capability negotiation
or another runtime condition caused Clear Key selection. In particular, it
does not establish that spoofing the desktop identity caused the choice or
that Widevine was rejected. No new phone experiment, debugger breakpoint,
page injection or APK build was performed for this static follow-up.

ABEMA's historical
[platform-protection presentation](https://speakerdeck.com/ygoto3/designing-a-playback-client-for-large-scale-live-sports-events)
and [playback-engineering article](https://developers.cyberagent.co.jp/blog/archives/53660/)
describe internal platform-dependent protection, not a public native Clear Key
SDK or entitlement flow. No documented third-party native acquisition contract
was found in the reviewed official material; this is a research limit, not
proof of technical impossibility. Media3's
[ClearKey/CENC DASH support](https://developer.android.com/media/media3/exoplayer/drm)
does not supply ABEMA authorization or compatible license processing.

The next useful diagnostic is a bounded record of candidate key-system names
and capability success/failure categories during original-player startup,
without reading initialization data, keys, challenges or license bodies.
The existing collector does not expose that sequence; any extension must keep
its closed-output and account-navigation boundaries. A native handoff remains
unimplemented. Intact ABEMA WebView playback remains the demonstrated path
while the native acquisition gate is unresolved.

### Startup observer preparation

The user approved the candidate/support trace after the static follow-up.
An additive `scripts/abema-eme-startup.py` and small playback-only observer now
record at most 16 browser EME capability calls with closed key-system and
request/accepted/rejected/throw categories. They do not inspect configurations,
resolved objects, errors, initialization data or DRM sessions. The original
metadata collector and APK are unchanged; earlier comparisons remain available.
The [explicit instrumentation limits](security-and-privacy.md#explicit-abema-playback-inspection)
and [startup procedure](android-playback-spike.md#build-modes-for-browser-diagnostics)
describe ordinary reload, temporary method observation and required activity
closure after every run. An accepted capability request is not proof of the
selected playback CDM, and unavailable instrumentation is not zero candidates.

Nine Python tests and nine JavaScript tests pass for closed output/correlation,
setup/navigation/cleanup failures, receiver/argument/promise/exception
preservation, route/iframe guards, timeout/restoration and request limits.
The existing 17 metadata-collector tests also pass. Review identified continued
setup after blocked navigation and cross-document request-ID reuse; both are
guarded and regression-tested. Targeted hooks, documentation build, secrets
checks and diff checks passed. No Android build or phone experiment was done:
the user reclaimed the phone while tooling was prepared. At that point, actual
startup candidate/support outcomes remained pending a new device run.

### Startup capability results — 2026-10-06

The user returned the unlocked phone for two short startup traces. Conditions
remained Pixel 6, Android 17 and System WebView `153.0.8010.36`, the unsupported
desktop identity and persistent isolated `abema-inspection` profile. Android
reported an active Proton VPN; its exit geography was not independently
measured. No account credentials or imported cookies were used; existing
provider-created guest/session state was retained. The inspection activity's
screen-on flag kept the display awake. Landscape was selected temporarily and
the previous rotation settings restored afterward.

The first attempt stopped during setup, before emitting candidate outcomes.
It followed a landscape activity recreation; the exact failure cause was not
established. Inspection was closed and its forward removed before retrying.
A fixed command-name/numeric-code failure marker was added, without printing
raw protocol error messages. The failure did not recur on the fresh News run.

At approximately 13:51–13:54 device time, separate 45-second News and free sumo
replay traces each reported READY, five requests, five completed outcomes and
11 total closed markers. Both had the same sequence:

| Observed request | News outcome | Replay outcome |
| --- | --- | --- |
| Initial Clear Key request (call 1) | ACCEPTED | ACCEPTED |
| Initial Widevine request (call 2) | ACCEPTED | ACCEPTED |
| Initial PlayReady request (call 3) | REJECTED | REJECTED |
| Initial unclassified system request (call 4) | REJECTED | REJECTED |
| Later Clear Key request (call 5) | ACCEPTED | ACCEPTED |

Requests 1–4 were issued before their outcomes; their completion order was
Clear Key, PlayReady, OTHER, then Widevine. The unclassified name was not
output or retroactively assigned another category. News issued the later
Clear Key request before onboarding was dismissed; Replay issued it after
tapping the original gray “Later” button. Temporary screenshots showed News
broadcast video and the replay progressing from its poster to sumo footage.
No audio confirmation, sustained decoding or native playback was tested.

The initial four-call shape matches the public capability probe, while the
later Clear Key-only call is consistent with subsequent player negotiation.
That phase attribution is an inference from static structure and event order:
no call stacks, request configurations or returned capability objects were
inspected. These tests establish that Widevine passed the initial requested
configuration; they rule out its rejection by that initial probe as the
explanation for these runs. They do not establish Widevine compatibility with
this content, its absence from a later candidate list, a rejected later
Widevine attempt, or the precise content/resource decision selecting Clear Key.
The preceding Media traces, not capability acceptance alone, establish actual
Clear Key CDM attachment. No license/key data or native handoff was inspected.

Both successful runs acknowledged future-document observer removal, then the
isolated activity was closed to destroy the current document. All three owned
debugger forwards were removed. Final lifecycle markers reported DISABLED at
13:54:34 and 13:54:35; rotation returned to `user_rotation=0` and
`accelerometer_rotation=1`. The existing certificate-verified APK was reused;
provider/profile data and prior examples were preserved. The startup tool's
nine Python and nine JavaScript tests and the metadata collector's 17 tests
still pass after the diagnostic-marker addition. Native acquisition/configuration
remains an unresolved gate, not a demonstrated Media3 playback path.

### Native/helper integration boundary — 2026-10-06

A bounded static follow-up corrected the ownership of module 44680: its DTO
contains deviceTypeId, its gateway accepts OS/version/DRM classification, and
its browser cache retains device identity. It awaits capability initialization
even before testing cached identity. Module 40724 adds that identity to channel
metadata; its consumers in module 99796 initialize channel routes and general
application startup. These are not a discovered playback-resource helper.
The cache must not be cleared merely to force a different DRM path.

The actual browser-player factory is createPlayerBrowser in module 89206,
installed on ContentSessionManagerBrowser by module 80678. Its inputs include
stream, playbackUrl, content, view and zeusClient. A DASH branch creates
PlayerDashJS, passes playbackUrl to dashOnlyInitialize, and obtains the
licenseURL input from opaque zeusClient.drm.createConfiguration output. Other
branches examine keySystemId and stream.isRawKey before selecting an HLS or
plain-video host; these tests do not identify the active News/replay branch.
This is a useful adapter boundary, not a verified independently callable
native licensing helper: browser views/media elements and provider-owned
configuration/session machinery are dependencies. Only field names and
literal-masked call structure were inspected; DRM configuration processing
was not followed. No source or license value was emitted or reused.

Repository inspection separates the remaining native work:

| Boundary | Evidence | Remaining work |
| --- | --- | --- |
| Source and standard initialization data | Advertised News DASH was accessible on the phone; current inspection is lexical only | Determine actual source/configuration ownership and whether standard signaling is sufficient, without emitting identifiers or protection payloads |
| Native hosting | Existing Media3 1.11.1 host supplies bounded playback, timing and audio grouping, but constructs only HLS sources | An additive DASH case would need the DASH module and source factory; preserve existing clear-HLS examples |
| Authorization/license handling | Generic native transport deliberately rejects DRM requests and allows only body-free/header-free GET requests | Establish a provider-specific acquisition/license contract; do not broadly relax the generic transport or reuse browser DRM sessions |

The pinned [Media3 1.11.1 DASH parser](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer_dash/src/main/java/androidx/media3/exoplayer/dash/manifest/DashManifestParser.java)
can construct common initialization data from the standard common-encryption
descriptor and valid default_KID attributes. Thus the existing lexical result
“common encryption without a recognized DRM-system marker” is not proof that
Media3 cannot initialize Clear Key. Whether this source supplies sufficient
standard signaling remains unverified, and initialization support does not
supply an ABEMA license response.

JavaScript remains an experimental option, not a selected architecture. A
browser-dependent helper could remain in an isolated WebView; a self-contained
helper could potentially run in a separate runtime. Neither integration has
been demonstrated. Any helper must serve the native player's own authorization/
license exchange while keeping credentials and protection processing private;
it must not export browser keys or transfer a browser DRM session. Do not add
a broad provider-to-native bridge or reproduce proprietary DRM transformations.
The bounded caller/configuration trace reaches the opaque provider DRM
configuration boundary, not its processing implementation. A native attempt
still needs an authorized way to supply compatible acquisition/configuration
for the native session; another EME capability trace would not establish that.

Reviewed official engineering material describes ABEMA's internal Fluffy
subsystem, not an externally usable SDK. No public native/JavaScript licensing
helper was found in those sources or the public ABEMA organization; this does
not rule out a private partner interface. The official
[company contact page](https://abematv.co.jp/pages/394742/contact) offers a service
inquiry route. If needed, ask whether an outside Android app may use Media3
with ABEMA-provided entitlement/license handling while preserving ads and
account rights, and request the responsible technical contact. No inquiry was
sent; contacting the provider requires separate user approval.

ABEMA also [documents email-code and ID/one-time-password account sharing](https://help.abema.tv/hc/ja/articles/28570588900377)
for its own web/mobile/TV surfaces. Leave these as provider-page authentication
options if account-required content needs them. The preceding successful News
and free replay inspections were anonymous; an account challenge has not been
shown to block those examples or to resolve the native license boundary.
No phone use, APK build, license request or native ABEMA playback occurred in
this static follow-up. The user deferred a controlled DRM fixture unless a
later failure makes it useful for distinguishing platform and provider errors.

### Source and handshake interface trace — 2026-10-06

The user approved tracing acquisition and handshake inputs upstream of the
browser factory. Literal-redacted AST inspection of the same anonymous public
assets established this candidate source pipeline:

```text
ContentSessionManagerImpl (42717)
  → ContentSessionImpl (8057)
  → StreamController (78101)
  → PlaybackResourceGateway (17046, reexported by 48897)
  → PlaybackResource DTO (45808) / WebStreamFilter (18278)
  → selected manifest / playback URL / optional Zeus decision
  → createPlayerBrowser (89206)
```

The gateway's fetch call supplies browser fetch mode and an accept header;
it has no explicit method/body, Authorization header, registration call or
media-token argument at that callsite. Browser fetch therefore defaults to GET,
but this is not a tested native anonymous-access contract. The resource DTO has
streams, subtitle tracks and monitoring overrides. Streams describe use case,
streaming technology, encoding, device capability, quality, duration, DRM and
manifest chains. Manifest descriptors carry URL, failover, ad-insertion and
CDN-balancing information; DRM descriptors include systemId, properties and an
opaque license URL template. Filtering includes DRM/platform compatibility and
delivery/ad-mode policy, not just EME capability acceptance. Do not discard these
policies or equate the first manifest with the provider's chosen resource.

The manager receives clientInformation and passes it to each session, along
with an injected getZeusClient callback returning its managed client. Playback
URL construction separately consumes the user media token and device context.
Feature-gated Zeus activation/decision can replace the manifest URL before
player construction. Credential acquisition is outside this traced gateway;
no token was obtained, displayed, generated or reused in this investigation.

The DRM facade's createConfiguration takes DRM type, license/certificate URL
templates, clientInformation and content. Its URL builder consumes mediaToken,
optional ticketToken and optional content type/ID. It returns keySystemId and
licenseUrl, sometimes certificateUrl. For DASH, PlayerDashJS receives the
license URL plus userId from clientInformation.user.id. Although
dashOnlyInitialize accepts a Zeus client and fourth flag, this implementation
does not use either argument. It initializes dash.js, registers one ABEMA
license **response** filter and supplies serverURL/priority for Widevine and
Clear Key. No ABEMA license-request filter was found in that wrapper.

The static byte-exchange sequence is browser EME key message → dash.js
request preparation/filters → XHR → response filters → selected dash.js
license-server adapter's getLicenseMessage → browser session update.
ABEMA's response filter and dash.js's final license-message conversion are
different layers. The provider callback reads response URL/data/headers,
the constructor's userId, imported provider helpers and standard byte/text/JSON
APIs. No DOM, video or existing MediaKeySession dependency was found in that
closure. Its proprietary helper implementation was not inspected or reproduced.
The facade's setLicenseKeyResolver method is empty, not a reusable native hook.

**Inference:** unchanged provider JavaScript could potentially normalize a
response for a native session without transferring browser DRM state. This is
a stronger candidate than the earlier opaque factory boundary, not proof of
compatibility. It still requires the matching provider identity, fresh media
authorization, the correct native request transport and final CDM-compatible
response bytes. Different native/browser requests need investigation rather
than an assumption of byte-for-byte equivalence. No helper was invoked and no
license/key content was captured. The pinned
[Media3 callback interface](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/drm/MediaDrmCallback.java)
supplies the native request/opaque-response boundary; the
[dash.js filter documentation](https://dashif.org/dash.js/pages/usage/drm.html#modifying-the-license-payload)
describes its separate filter boundary. Neither establishes ABEMA permission or
native-session interoperability.

#### Bounded handshake metadata preparation and phone result

An additive `--handshake-metadata` flag on the existing collector classifies
the exact public-static resource origin, the prior license/API origins and
other ABEMA service origins. It reports only method categories, a boolean/
unknown post-data-presence field, HTTP status, MIME categories and event order.
It reads no request body/header values or response body; paths, queries and
hostnames are not emitted. Origin categories are not verified operation names:
other-provider traffic may include telemetry, activation, catalog or media.
Request correlation remains internal/session-scoped, bounded to 64 in-flight
requests, cleaned on completion/detach/redirect, with a limit indicator.
After 64 ordered markers, aggregate counters continue without further events.
Default metadata behavior and the earlier startup observer remain available.

The first ADB attempt failed because the execution sandbox could not bind the
ADB server listener. Host-access inventory succeeded; this was a tooling
permission failure, not a phone or ABEMA failure. The Pixel 6 was unlocked,
running Android 17/WebView 153.0.8010.36. Android reported VPN connectivity;
exit geography and provider acceptance were not independently measured.
The existing certificate-verified APK and isolated persistent anonymous profile
were reused, with the unsupported desktop identity unchanged. No credential or
account code was entered. No APK build, browser-state reset or rotation change
was performed.

A 45-second News trace at approximately 14:52–14:53 device time attached before
the native Reload action. Media metadata again reported an attached Clear Key
CDM and CENC audio/video tracks with decrypting demuxers/platform video decoding.
Temporary screenshots showed the broadcast in a small portrait player with an
onboarding overlay; the subsequent Later tap was not verified to dismiss it.
There was no acoustic or sustained-playback confirmation.

The trace counted 299 network requests, 251 responses and 46 failures, including
eight DASH-MIME and 24 MP4-MIME responses. Resource, license and API exact-origin
categories each matched zero. The broader provider-origin category counted
149 requests, 142 responses and six tracked failures, with GET/POST/OPTIONS and
JSON/204 responses visible before its output limit. Tracking also hit its
in-flight ceiling; those failure counts are incomplete. No eligible related
worker/frame attached. The run therefore does **not** identify resource,
activation or license operations. Static fallback origins need active-route
verification before interpreting zero matches or replaying a request.

Inspection reported DISABLED at 14:54:12 and 14:54:13; the exact tcp:45713
forward was removed and the subsequent forward list was empty. Screenshots
remain temporary. Review then caught omitted redirectResponse metadata; an
opt-in-only correction records its closed status before request-ID reuse,
with provider-to-foreign and provider-to-provider regression cases. That change
was not rerun on the phone. Twenty-three collector tests, nine startup Python
tests and the startup JavaScript suite passed. This strengthens the interface map, but
native ABEMA playback is still unimplemented. The next useful experiment is
active-operation/request-shape classification and a native request comparison
that initially stops before network/license handling, not another generic DRM
capability fixture. Only after that should a narrowly scoped unchanged-JavaScript
normalization adapter be considered; do not extract keys or copy proprietary
processing into Tachiai.

### Native request-format probe — 2026-10-06

The next additive experiment has been implemented in debug sources only.
`AbemaNativeRequestActivity` and its separate process leave the prior WebView,
native-access and Twitch examples intact; the inspection toolbar gains a launch
button. There is no automatic start or native ABEMA playback implementation.

Its fixed News path uses anonymous channel metadata and the advertised,
query-free allowlisted MPD. A bounded SAX parser accepts namespace-correct
common-CENC default identifiers in AdaptationSet/Representation scopes;
distinct audio/video identifiers are allowed. Unknown/ambiguous initialization
fails closed. Identifiers remain transient and are not keys or DRM-system
selection evidence. A standard common-system version-1 PSSH is constructed
with no provider payload, following the
[W3C CENC initialization format](https://www.w3.org/TR/eme-initdata-cenc/).
The explicitly selected Clear Key diagnostic uses Android's
[MediaDrm request API](https://developer.android.com/reference/android/media/MediaDrm#getKeyRequest(byte[],%20byte[],%20java.lang.String,%20int,%20java.util.HashMap%3Cjava.lang.String,%20java.lang.String%3E))
directly, without a player/session manager or network DRM callback.

The platform boundary returns closed shape/type/destination categories, request
length (capped at 16 KiB; oversized is a fixed sentinel), identifier count and
whether the standard request names the same identifier set as its input.
Neither request bytes, identifiers, initialization, URLs nor exceptions escape.
The session closes and the CDM releases on its owning worker before publication.
No provision/license request, key response, media segment or provider helper
execution is implemented. This tests request preparation/format only, not
authorization, a working exchange or browser/native response compatibility.

#### Verification and phone results

The pinned Android tools container used the established SDK 37.2 and unchanged
shared key. Its first invocation failed because Java's home resolved to the
image user's directory rather than the mounted key. Explicit disposable Java
home configuration fixed that; a writable home directory then removed the
nonfatal analytics-settings warning. No signing key was generated or replaced.
Debug tests pass: 338 total, including 23 new parser/request/cleanup tests.
The 314 fixture-variant tests remained passing/up-to-date. `lintDebug`, release
Kotlin compilation and debug assembly passed. Lint retains two existing warnings
and one hint; the new string-resource warning was corrected. Compiled classes
and merged manifests exclude the probe from release and the fixture variant.
Each built APK passed `apksigner` with the required shared certificate.
The final host-owned APK is 16,410,635 bytes, SHA-256
`f233941ba5d10be8842ea07558e76fcf1e19dbacd70e96a7fed0759da0af54a2`.
Targeted pre-commit checks, secrets scanning and documentation build passed.

The Pixel 6 was unlocked, on Android 17; this native experiment uses no browser
engine, while installed WebView remains 153.0.8010.36 for the comparison cases.
It uses anonymous News linear/live initialization, not a replay or account.
Android showed an active combined Wi-Fi/VPN transport; the user reported Proton
Japan and reconnected. The first overly narrow transport check missed that
combined transport, so no VPN outage or cause of playback behavior is inferred.
Exit geography/official regional support remain unverified. Updates preserved
application/browser data; no credentials/codes were entered or profile cleared.

At 15:14:40 device EDT, HTTP 200 and valid initialization reached REQUEST_FAILED.
Finer stage diagnostics at 15:19:49 showed REQUEST_METADATA_UNAVAILABLE:
MediaDrm had returned the request, but our classifier threw. Android's
[Pattern reference](https://developer.android.com/reference/java/util/regex/Pattern#behavior-starting-from-api-level-10-android-23)
documents a JVM/ICU difference requiring literal right-brace escaping. Escaping
literal array/object delimiters fixed the phone result; review also narrowed
whitespace to JSON's four allowed characters with malformed-whitespace tests.
This was an application diagnostic defect, not an ABEMA/CDM rejection.

At 15:24:16, and again at 15:26:34 with the final reviewed APK, the News check
reported HTTP 200, valid initialization and
REQUEST_PREPARED: STANDARD_KIDS_JSON, 54 bytes, one identifier, TEMPORARY,
SAME_SET, INITIAL and EMPTY default destination. Session/CDM cleanup completed
before publication, without a cleanup-failure marker. This establishes that
standard advertised initialization can produce a native request on this phone.
It does not show matching browser request bytes, active ABEMA authorization,
accepted native licensing, usable response normalization or playback. No license
or provisioning transport, response/key operation or media player was invoked.

A final immediate Home/background and single-top return reused the same native
activity instance and displayed Stopped, with no new request-result marker or
automatic restart in the bounded check. Close/reopen separately returned Ready.
This corroborates cancellation/result suppression, not a guarantee that an
already-sent metadata request never reached the server or that a blocking CDM
operation is instantly interrupted. The probe was closed, Tachiai backgrounded,
and the debugger-forward list was empty. Screenshots remain temporary only.

The parallel static-origin follow-up also narrowed the browser-trace gap:
the resource origin is a fixed input to PlaybackResourceGateway, but actual
manifest and license destinations are resource-response-selected.
The cached anonymous linear-page HTML already contains a materialized media
reference, so no newly observed resource lookup is plausible, not established;
this does not explain absent license matches or generalize to replay.
Two dedicated Zeus origins are
identifiable; the media-token driver/base-origin module is absent from the
cached chunks. A broad API/config origin must not be labeled authentication or
the active license server solely from its hostname. Zero exact-origin matches
remain an attribution/coverage gap, not absent authorization or DRM proof.
The public license-proxy configuration is actively assigned to a browser global,
but its downstream consumer is opaque in the cached chunks; it is a distinct
candidate role, not confirmation of the dash.js destination. The next bounded
browser comparison should distinguish these exact static host/route roles,
method and cache/service-worker indicators, without outputting URL or query
values, reading bodies or reproducing proprietary response processing.

### Configured-route handshake comparison — 2026-10-06

The follow-up resolved the previously missing injected API driver in the
same page-loaded [public ABEMA bundle](https://abema.tv/assets/compat/3850f3e68c3aa3c0.5206.js).
`fetchMediaToken` uses the fixed configured API origin
`api.p-c3-e.abema-tv.com`, GET `/v1/media/token`, and the five query names
`osName`, `osVersion`, `osLang`, `osTimezone`, `appVersion`. The driver's
`getWithCacheInvalidation` name does not imply an appended cache-buster. It can
attach the current stored bearer by default; this is not an established
anonymous/stateless native call. No live header/token was inspected.

Public guest bootstrap uses first-party POST `/api/auth/login/guest`, JSON and
included browser credentials. Its input schema includes `deviceType`,
`applicationKeySecret`, `deviceId`, optional `previousUserId`; successful state
is coupled to the browser profile. First-party POST `/api/auth/accessToken`
updates that state. These are static interface findings, not native calls or
authorization to reproduce the application-secret algorithm. That algorithm
and the proprietary response-processing implementation were not inspected.

The page-loaded [ABEMA dash.js asset](https://abema.tv/assets/compat/284cdb771dbf8da3.abema-dashjs.js)
identifies version `4.7.2-abema.4`. Its generic Clear Key adapter sends a JSON
POST with `application/json`, requests a JSON response, applies registered
response filters, then converts the normalized `keys` entries to the standard
JWK Set supplied to EME. ABEMA's unchanged proprietary response normalizer and
the generic adapter are distinct stages. Static player configuration has
server URL/priority, without a custom request filter/header/credential option;
generic license transport defaults to no credentials. This does not prove
native request equivalence or allow transfer of provider identity/license data.
See [dash.js filter ordering](https://dashif.org/dash.js/pages/usage/drm.html#modifying-the-license-payload)
and [the EME Clear Key interface](https://www.w3.org/TR/encrypted-media-1/#clear-key).

The additive collector flag maps only eight exact public configuration hosts to
seven closed roles, with independent route/output/tracking budgets. A review
caught path-only license labeling on non-license configured hosts; labels now
require a known license role, with cross-role lookalike tests. The original
collector and earlier handshake mode send the same commands and retain their
output behavior. No body/header/cookie readback, Runtime or new injection is
introduced. Missing cache/post-data fields remain unknown.

Conditions: Pixel 6, Android 17, WebView 153.0.8010.36, unchanged verified debug
APK and isolated persistent inspection profile. The user reported a reconnected
Proton Japan connection; VPN icon was visible, not independently verified exit
geography or provider-supported regional operation. No account credentials
were entered/imported; provider-created anonymous guest state may persist.
News is linear/live; the known free sumo highlight is replay. Screenshots are
temporary only, and no account UI, tokens or licenses were captured.

At approximately 15:47–15:48 device EDT, attaching before News Reload observed
one `API_CONFIG / MEDIA_TOKEN_ROUTE_CANDIDATE` GET, HTTP 200 JSON, then one
`LICENSE_PROXY_CONFIG / DASH_LICENSE_ROUTE` POST with post-data presence true,
HTTP 200 JSON. Both responses reported disk/service-worker/prefetch false;
the separate served-from-cache indicator was unknown. A later player reported
attached Clear Key, CENC audio/video, decrypting demuxers and Play. This
corroborates the active configured proxy, fixing the older wrong-origin trace;
it does not inspect response content, demonstrate native licensing or establish
sustained/acoustic playback. Resource and Zeus roles had zero observations.
Other API tracking saturated its intentional four-entry sublimit, while the
recognized media-token and license markers remained visible.

The first 45-second source transition to replay observed a media-token-candidate
GET/200 JSON but no license-proxy marker before the run ended. Optional
onboarding remained visible. Dismissing its tooltip and Later control then
exposed visible sumo video. Absence in that bounded transition is not proof of
license-free replay; the subsequent comparisons distinguish that gap.

The next replay Reload returned the media-token-candidate GET/200 JSON again,
but showed optional onboarding and no new license marker during its 45 seconds.
A separately attached 60-second observation followed by Later then observed
the configured DASH license POST/200 JSON and a new attached Clear Key player
with CENC audio/video, decrypting demuxers, platform video decoder and Play.
License cache indicators were false/false/false with separate cache unknown.
This establishes the same active proxy role for this replay, not identical
resource, entitlement, license bytes or native compatibility. The operator
closed that activity before the collector finished; CLEANUP_COMMAND_UNAVAILABLE
and INSPECTION_UNAVAILABLE prevent claiming final aggregates/orderly collector
shutdown for that run. Activity logging confirmed debugging disabled and its
exact ADB forward was removed.

At approximately 15:57–15:58, a fresh replay activity and attached 30-second
repeat followed by Later confirmed one configured DASH license POST/200 JSON
and the attached Clear Key/CENC audio/video player with Play. It completed
normally with one license request/response, zero tracked license failures and
no role-tracking limit. No new media-token candidate was observed within this
post-load interval. Resource/Zeus/legacy-license roles again had zero matches.
The activity was then closed and its exact forward removed; debugging-disabled
logging confirmed teardown. No sustained or acoustic claim follows.

The collector's 31 self-tests pass, including eight new exact-role/route,
unknown-preserving cache, telemetry saturation, redirect/session/detach and
old-mode command/output regression tests. Scoped secrets/Markdown/documentation
checks passed. No Android source, dependencies or APK changed for this extension.

#### Remaining integration gate

[Official ABEMA engineering](https://developers.cyberagent.co.jp/blog/archives/53660/)
describes its internal cross-platform Fluffy playback core, including Android,
Web, iOS and Unity, and its Golem reference/test player. This corroborates that
the needed abstraction exists inside ABEMA, not that it is available to Tachiai.
A bounded official-site/documentation/GitHub search found no public native SDK
artifact, license/entitlement contract or unchanged-JavaScript helper loader.
Private partner access remains unknown, not disproved by that search.

An unchanged-JavaScript broker remains structurally plausible but unverified:
provider-owned code would retain guest authorization and identity, perform the
original license/normalization steps, and return only an opaque standard CDM
response to one matching native session. No such public callable interface was
found. Loading internal bundle modules or injecting a challenge/response channel
would cross the current no-provider-page-bridge inspection boundary and needs
an explicit new decision before implementation. No browser cookies/tokens,
passwords, license/key dumps, proprietary algorithm reproduction, advertisement
suppression or DRM/entitlement bypass is proposed or authorized here. Neither
technical feasibility nor provider permission for this broker is established.
Repeating metadata traces alone will not resolve that integration gate.

### Authorized one-exchange broker prototype — 2026-10-06

The user explicitly approved the narrow debug-only native/browser bridge
experiment. New debug sources preserve the inspection and request-only cases.
`AbemaOpaqueBrokerActivity` uses its own process and persistent anonymous
WebView profile, fixed News route and existing unsupported desktop identity.
It disables DevTools before loading and never exposes `addJavascriptInterface`.
The original provider player, advertisements and browser authorization remain
in that profile. No credentials may be entered and no cookie/token is exported.

The independently authored document-start wrapper uses the page's loadable
runtime callback and unchanged `PlayerDashJS.loadLib` to retain only the
provider-bound response-filter function and selected Clear Key license URL
inside its closure. It adds an observational generic request filter to verify
the first initial POST/JSON/no-credentials transport and zero headers or exactly
the fixed JSON content type. Configuration alone is not readiness. Additional
matching requests/configuration/filter replacement revoke the exchange instead
of silently reusing context. Instrumentation failure returns the original
player; refused/error states are terminal. Closed normal-factory Dash/non-Dash,
Dash-create and Dash-load markers distinguish selection from a pending load;
they do not expose the factory configuration. This changes wrapper/promise timing
and method identities, so it is not perfectly passive observation.

The explicit native action fetches advertised public News initialization and
opens one Android Clear Key session. Its standard JSON challenge travels
unchanged through a bounded browser POST to the captured exact license-proxy
route, with ordinary TLS, no cookies, redirects or retry. The captured unchanged
provider filter and original dash.js ClearKey serializer produce only an opaque
response for that same native session. The wrapper does not reproduce provider
algorithms, inspect key values or export identity/authorization. Matching News
identifiers across browser/native requests, applicable entitlement, timing and
response compatibility remain experimental assumptions; rejection is normal,
not grounds to alter identifiers or bypass provider policy.

Only closed helper/preparation/submission/cleanup markers enter native UI/logs.
Challenge/response byte ceilings are 16/64 KiB; the browser request aborts after
20 seconds, native acceptance is bounded to 30 seconds, and helper capture to
120 seconds. These do not forcibly interrupt a blocking CDM or synchronous
provider call. Native revision/document/foreground checks reject late replies;
failed response waits seal their future and wipe unclaimed owned bytes. Owned
arrays are cleared and the native session/CDM close on the worker before result
publication. Java/JavaScript strings and provider objects preclude secure-memory
erasure claims. No native player, media retrieval, provisioning, renewal or
offline key set is implemented. RESPONSE_ACCEPTED would establish CDM response
acceptance only, not decoding or native playback.

Synthetic browser tests cover provider-call preservation, bound filter and
generic adapter use, active transport, source/authority rules, one-shot and
terminal refusal, byte bounds, replacement, timeout and late completion. Native
fake-CDM tests cover one-session ownership, request matching, response ceilings,
cancellation, stage failures, cleanup and completed-response/timeout races.
These do not validate license compatibility on the phone. The initial phone
launch returned to the older view before readiness; its cause remains unresolved.
Later lifecycle-instrumented launches stayed open without a recorded navigation
refusal or renderer-loss marker. On the same Pixel 6 / Android 17 / WebView
`153.0.8010.36`, the new anonymous profile showed News/onboarding with a VPN icon
visible; neither exit geography nor account entitlement was independently
verified. No credentials were entered or transferred.

The first helper remained CAPTURING. Static review found a real fixture defect:
the provider runtime does not invoke a callback for an empty chunk-ID tuple.
The corrected wrapper composes the real queued player chunk's existing runtime
callback, retaining its receiver, arguments, result and exceptions. It guards
required module availability before requiring the player, creates the generic
adapter only after the original load resolves, and refuses changed post-bootstrap
ordering instead of inventing a fallback. Current script/module IDs and ordering
are private mutable interfaces, not a supported provider contract.

The corrected phone run reached WAITING_PLAYER but not READY. A separate
original-page-only launch omitted the broker wrapper using the same host/profile;
it rendered News/onboarding, but this screenshot comparison does not establish
continuous playback or which player technology was selected. The user reclaimed
the phone before the new closed factory/load markers could be tested. No native
exchange, license response submission or native playback has run in this case.
Twenty-seven browser fixture tests now cover the startup ordering and diagnostic
call preservation as well as the earlier boundaries; seven new fake-CDM tests
cover native ownership/cleanup. The next phone check must distinguish onboarding/
alternate selection from Dash load before any explicit native exchange.

Local verification: 345 debug unit tests and the 27 browser fixtures pass.
Debug assembly/lint, release compilation, release/fixture asset exclusion and
shared signing-certificate verification passed. Lint retains two pre-existing
warnings and one hint; new feature-guard/string warnings were corrected.
Scoped code/documentation checks passed, and review against the turn's starting
files preserves older examples and finds no introduced generated-file noise.
The bounded final review found no remaining blocker. Direct redacted Gitleaks
scans of the debug sources, debug tests and browser fixture found no leaks.
Final debug APK: 16,412,118 bytes, owned by the host user, SHA-256
`16ae057a72939115d86801a5002494f2c42cc43cd8d18a6529fbd4348f075f59`.
This final diagnostic revision has not been installed while the user is using
the phone. The earlier corrected-loader APK was installed without data clearing.

### Broker runtime follow-up — 2026-10-06

The preceding diagnostic APK was subsequently installed without clearing data.
On the same Pixel 6 / Android 17 / previously recorded WebView
`153.0.8010.36`, repeated anonymous News launches reached WAITING_PLAYER and
expired at the 120-second capture bound without enabling Native exchange.
Optional onboarding and portrait/landscape comparisons did not establish the
selected player implementation. Exit geography and entitlement remained
unverified; no credentials were entered.

A separate 20-second inspection of the original News page in the older
inspection profile recorded a video-bearing player with attached Clear Key
CDM, CENC audio/video tracks, decrypting demuxers and PLAY. Auxiliary unencrypted
audio players are not evidence about that video player's protection. Closed
helper-interface discovery confirmed the expected compatibility app, player
and dash.js asset families and queued player/factory module availability.
This is evidence about the inspection profile, not native playback or the
broker profile's selected runtime. The inspector was closed, debugging disabled
and its local forwarding removed before returning to the broker.

The additive broker diagnostics now retain monotonic factory/create/load
booleans, a capped installation count, wrapper-ownership checks, top-document
VIDEO play-event presence and generic iframe presence. Android accepts only
the exact fixed Boolean fields and bounded count, with repeated foreground,
view, revision and route checks. A play event or iframe alone does not prove
successful media playback, a particular implementation or license acceptance.
A second runtime installation now revokes capture without erasing call history.

The user privately accepted the new anonymous profile's terms. The initial
post-consent page explicitly rejected the region; a VPN icon was visible, but
the user then confirmed the playback VPN needed enabling again. Following the
user's reconnect and an explicit reload at 19:37 device time, the original
News video visibly advanced after optional onboarding was dismissed. Before
expiry, diagnostics reported one installation, available factory and retained
hook ownership, with a top-document play event and an iframe. Factory, Dash
create, load call and load resolution all remained false. The helper stayed
WAITING_PLAYER; no native challenge, broker license exchange or CDM response
submission ran. Whether another runtime/player path or attachment timing
explains these missed calls remains unresolved. Region rejection and helper
readiness must be diagnosed separately.

Local follow-up verification: 29 browser fixtures and 345 debug unit tests
pass; debug assembly/lint passed with the same two pre-existing warnings and
one hint. The shared debug certificate fingerprint was verified before the
data-preserving installation at 19:31 device time. Updated APK: 16,412,118
bytes, SHA-256
`07aee34b41660637051529cb14aacaad1ede2f32419e7f53604bc08e90925105`.
An independent bounded source review found no blocking diagnostic regression.
Earlier examples, opaque handoff restrictions and native-playback assumptions
remain unchanged.

The next additive diagnostic revision observed the active lower call path on
the phone at 19:48 device time: common manager factory installation occurred,
but neither a content-session call nor its factory call was recorded. The
shared dash.js namespace was called and its first factory created a raw player,
followed by a top-document VIDEO play event and visible News video. No dash.js
auto-create video marker was recorded. These findings identify a viable
observation boundary, not the exact lazy route implementation or native
compatibility. The original legacy-load broker still did not reach READY.

A separately selected `ABEMA_BROKER_SHARED_DASH_CAPTURE` comparison now keeps
the legacy case as the default and uses that first shared factory's raw player
as its candidate. Adapter construction and request-observer installation occur
before returning the unchanged player to the provider; failures preserve that
return. The common configuration/filter/initial-transport gates remain required.
Second factory creation or another namespace factory call revokes capture.
Legacy class-load resolution becomes observation-only in this comparison so
it cannot capture the same player twice. Observer setup rechecks liveness after
provider calls; reentrant stop unregisters its observer and does not resurrect
adapter state or install later hooks. The first raw player could be an ad or
another source: its correspondence to native News initialization remains an
experimental assumption, not a reason to alter identifiers or provider policy.
No second player or additional exchange is authorized by this comparison.

At 19:55 device time the verified APK was installed without clearing data and
the shared-factory case was explicitly launched. It reached WAITING_TRANSPORT
and then READY at 19:56:11 while the original News video played. The explicit
native action at 19:56:57 returned RESPONSE_ACCEPTED at 19:57:02: the Android
Clear Key CDM accepted the opaque provider-processed response in the same
session that generated the native challenge. The outcome is published only
after native session/CDM cleanup; owned request/response arrays are wiped.
The original web video continued advancing. No challenge/response contents,
keys, cookies or credentials were inspected, logged or persisted.

This establishes one anonymous live News response-acceptance result under the
user's reconnected system VPN condition on this phone. It does not establish
correct native decryption/decoding, a usable native player, replay/sumo or paid
content compatibility, renewal/key rotation, sustained playback, other devices,
supported provider operation or permission to distribute this integration.
The next separately bounded experiment would make a native player own its DRM
session and use the same unchanged browser helper for that session's challenge;
the already closed probe session/response must not be reused for another session.

Final shared-factory verification: 37 browser fixtures and 345 debug unit tests
pass. Debug Kotlin lint retains the same two warnings and one hint; assembly,
scoped pre-commit/documentation checks, targeted lifecycle review and shared
signing-certificate verification passed. Final APK: 16,412,118 bytes,
host-user owned, SHA-256
`dbd5fab1bbe7c19fb9cbbbc2cd4b0023d0d2e983f4e579cb02420fe9de41954a`.
The preceding lower-path diagnostic APK was also installed and verified; its
SHA-256 was `9b252b0a6085b7fa613960031fe9d7f43867f63c8d44ad13653a9e045c0039b7`.
Earlier examples remain available; no native playback is added by these changes.

### Native News playback comparison — 2026-10-06

Following response acceptance, the user requested continued progress through
successful intermediate steps. An additive debug comparison now gives Media3
its own fresh Clear Key session. Its initial request must contain exactly the
public News MPD's standard key identifiers before the existing, unchanged
browser helper handles it. The opaque response is handed back only to that
player-owned session. The older acceptance, legacy capture and original-page
examples remain available.

The provider adapter selects only the advertised anonymous News DASH URL and
standard initialization. The generic platform host uses separate bounded
manifest/media data sources, one initial DRM exchange, no provisioning or
renewal, zero load retries and a two-minute foreground budget. Source requests
require no process-wide cookie handler, carry no imported authorization and
refuse redirects. Every media request must remain on the exact News CDN host
and `/channel/abema-news/` path root, without query parameters. Whether all actual
segments satisfy that allowlist remains an experimental assumption. Failure
does not authorize guessed URLs, identity substitutions or alternative licenses.

The original web player remains visible and intact. An explicit Mute web
control allows native audio isolation; Media3 does not request audio focus away
from that player. Native output must establish DRM_KEYS_LOADED, a rendered
VIDEO_FRAME, advancing video and acoustic confirmation independently from the
original browser. None of those playback observations is established by the
earlier RESPONSE_ACCEPTED result.

The implementation uses Media3's documented
[DRM session support](https://developer.android.com/media/media3/exoplayer/drm)
and [opaque callback response ownership](https://developer.android.com/reference/androidx/media3/exoplayer/drm/MediaDrmCallback.Response).
The callback response is not wiped before Media3 consumes it; no claim is made
about secure erasure inside the library or platform CDM. Cancellation seals the
browser wait before releasing the player/session. A constructor failure also
releases an allocated player even before the activity can own it.

At implementation time, native video/audio, live seek-window behavior, sustained
playback, key rotation, replay/sumo/paid content and ad equivalence remain
unverified. This prototype does not settle a production playback architecture.

The first verified installation at 20:23 device time restored the old activity
intent; that host was closed and the new case explicitly launched. Its original
page stalled at WAITING_PLAYER in the initially reduced viewport, including an
explicit reload. A preserved full-height shared-factory control launched at
20:25:16 reached READY at 20:25:35 and visibly played News. This comparison does
not isolate viewport from transient network/startup variation. The native case
therefore now hides the unused native surface during original-page startup and
shows it only when the user explicitly starts native playback. No license
exchange occurred during the stalled startup attempts.

The startup-adjusted comparison launched at 20:28:24 reached READY at 20:28:56;
the original browser's top-level video was playing by 20:28:58. Native playback
was explicitly started at 20:29:32. The native data source read the DASH manifest
with HTTP 200 at 20:29:36, but a subsequent media request was refused by the
News-only URI allowlist at 20:29:37. Media3 reported PLAYER_FAILED code 2000 and
the player was released. No native DRM request, keys-loaded event, decoded frame
or native audio was observed. This is a media-route policy failure before license
exchange, not evidence that the DRM response fails native decryption.

An additive closed-category diagnostic now distinguishes authority, user info,
fragment, query, News path root and path syntax without exposing URL values or
widening access. A host-side public MPD structure check could not complete under
the host's network condition; it supplied no route evidence and did not request
media or licenses. Actual media-route compatibility still requires device
evidence before any allowlist adjustment.

The categorical retry reached READY at 20:34:49 after the original page's
advertisement/playback flow. Native start at 20:34:58 again read the MPD with
HTTP 200 at 20:35:02. Every refused media request was classified CHANNEL_ROOT:
the HTTPS authority, absence of user info/fragment/query and port checks passed,
but the requested path did not begin `/channel/abema-news/`. The player again
failed with code 2000 and released before any DRM callback. This establishes
that the guessed News path-root policy is incompatible with this advertised
MPD's actual Media3 requests; it does not establish a provider rejection.
No refused request was sent and no URL, initialization/media bytes or key
material was inspected or retained. Path syntax after the root check remains
unclassified, so the result does not independently validate that syntax.

The proposed next step is standard MPD-declared initialization/segment path
binding for this exact anonymous News source, not whole-CDN access. This would
require reviewing BaseURL/template resolution and preserving strict authority,
query, redirect and session limits. It is not implemented or verified yet.
The phone was returned to Home with the probe closed after documenting the
failure; the system VPN was left unchanged.

Conditions for these device attempts: Pixel 6, Android 17, Android System WebView
153.0.8010.36 with the existing debug desktop identity, isolated anonymous
profile, live ABEMA News, user-reported Proton Japan connection and an observed
active VPN transport. Original News playback, rather than the VPN icon alone,
established acceptance for these attempts. Native playback used Media3 1.11.1
and no imported browser cookies, provider account or authorization headers.

Final verification: 37 browser fixtures and 355 debug JVM tests pass; lint,
assembly, scoped pre-commit/documentation checks, redacted secret scans and
targeted lifecycle review passed. Every built APK matched the required shared
debug certificate before installation. The final data-preservingly installed
APK has SHA-256
`47669b0aa684f564f3cb28ac480bff5253479833197b92662f5e86bab362f9fe`.
The first native-test APK was
`767cf389be5f35cba6186cb6a2009d613f8fbdc47602c7fa3317ed01b61513f3`;
the startup-adjusted APK was
`e2311a175365f235132b2dad16f0fa26edefb9d241b5beaa82c3f51d8f62bf2b`.

### Manifest-declared native media paths follow-up — 2026-10-06

The user clarified that the guessed-root failure was an implementation issue
to fix autonomously, not a decision requiring another approval. The native News
case now delegates ordinary DASH resolution to Media3's
[manifest parser](https://developer.android.com/reference/androidx/media3/exoplayer/dash/manifest/DashManifestParser)
and [representation/index model](https://developer.android.com/reference/androidx/media3/exoplayer/dash/manifest/Representation).
It admits only the exact resolved initialization, index and finite segment URLs
from the fetched News MPD on the existing query-free HTTPS CDN authority. The
manifest datasource remains bound to the exact original advertised MPD; another
Location is refused. Earlier examples and the metadata MPD-root predicate remain.

The 256 KiB XML/key-ID preflight runs before Media3. Timeline expansion is
capped at 32,768 elements, BaseURL expansion at 16 inherited parents and 64
calls, representations at 64, periods at 16 and segment windows at 8,192 each.
Unique paths cap at 32,768 and generation attempts at 65,536. Current and one
prior snapshot are retained atomically; cancellation clears and seals them.
Dynamic URL enumeration includes at most the attempt's two-minute lookahead,
not requests or claims of future media availability. Unknown/oversized models
are unsupported by this bounded probe instead of gaining arbitrary CDN access.

The tagged [Media3 1.11.1 source](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer_dash/src/main/java/androidx/media3/exoplayer/dash/DashMediaSource.java)
also revealed implicit SNTP when a dynamic manifest lacks direct/HTTP timing.
The earlier generic transport bounds did not independently constrain this
clock path. The new native parser explicitly uses the phone's wall clock through
standard direct UTC timing, with no separate clock request. This is an unverified
clock-accuracy assumption; the original web player and its timing remain intact.

Initial JVM verification caught two local policy issues before installation:
Java URI normalization alone does not reject a leading dot-dot component, and
the extracted common validator changed an old empty-path diagnostic category.
Explicit dot-component rejection and preservation of the old comparison's
category fix those cases. No failed-test APK was installed.

Synthetic parser fixtures now run on Android rather than mocked JVM Android
APIs. They exercise inherited BaseURL and formatted Number/Time/Bandwidth/
RepresentationID/dollar templates, SegmentBase ranges, foreign/query/Location/
entity refusal, bounded positive/negative timeline expansion, direct-clock
normalization and terminal cancellation. They do not request media or licenses.
All seven synthetic parser tests passed on the Pixel 6 / Android 17. The build
also passed 361 debug JVM tests, lint and debug/test APK assembly; both APKs
matched the shared debug signing certificate before data-preserving installation.
The installed playback APK SHA-256 was
`35382c744019b5a816426a93fa0bc1bca3af3002287d64a60af99912f0152914`.

A fresh original News context reached READY. At 21:01 device time the native
player fetched the manifest and declared media with HTTP 200, generated its own
initial DRM request, received the unchanged browser-helper response and reported
DRM_KEYS_LOADED, VIDEO_FRAME, READY and PLAYING. The large native surface visibly
advanced through changing News footage. With Mute web requested and native
volume at 50%, the user confirmed hearing News audio. The original smaller web
player remained intact. Manifest refreshes continued about every five seconds
through the two-minute foreground budget, with bounded declared-path counts
falling from 140 to 104 and no observed second DRM exchange or player error.
The budget then stopped/released native playback. This is observed native News
video/audio, not just a successful license-response acceptance probe.

Conditions: Pixel 6, Android 17, Android System WebView 153.0.8010.36, existing
debug desktop identity and anonymous isolated browser profile, live free News,
Media3 1.11.1, user-enabled Proton Japan/system VPN. Original News playback
established accepted connectivity for this attempt. Native media carried no
browser cookies or account authorization. Only closed markers/scalar counts
and temporary credential-free surface screenshots were inspected; no opaque
license/key material or protected media was exported, saved or re-encoded.
Replay/sumo, paid entitlements, renewal/key rotation, long-duration reliability,
dual-native mixing and provider permission remain unresolved.

An additive timing row now reuses generic native seek plans for Pause, Play,
Back 5 s, Forward 5 s and explicit Live recovery. Diagnostics contain only
normalized clocks/capabilities and action outcomes; no media identifiers or
URLs. Live shifts clamp to the current advertised window and refuse a clamp
that would reverse the requested direction. Request acceptance is not treated
as settled playback. The two-minute budget is unchanged. Reload/cancellation
also hides the detached native surface and clears its stale PLAYING status so
the original page regains its startup viewport. Device timing results follow
when observed; the earlier after-timeout pause tap proves nothing.

### Native News timing and refresh limit — 2026-10-06

The timing APK (`d4e98247dbb960ce613dbce06727a3425509caf2ffa3c287e95608e657546685`)
passed 361 debug JVM tests, lint and debug/test assembly, and matched the shared
certificate before installation without clearing data. Nine existing generic
timing-plan tests cover bounds, unknown clocks, advertisements, arithmetic
overflow, direction-reversing clamps and unchanged budgets. Review caught an
initial-unavailable sampling gap; sampling now continues until its host ends.
Lint reports five warnings/one hint, including the new DASH host's missing
explicit IDLE branch and a `toUri` style suggestion; no lint errors occurred.

The first original document stayed at its startup splash/WAITING_PLAYER without
creating a player. One explicit Reload reached READY about 27 seconds later.
At 21:13 device time, fresh native DRM_KEYS_LOADED, VIDEO_FRAME and PLAYING
appeared. Conditions and opaque boundaries are the same as the preceding run;
the original web audio was explicitly mute-requested, native volume was 50%.

The MPD exposed a sliding seekable window of about 60–61 seconds. Position in
that window changes as its origin advances, so the comparison uses normalized
content time (`windowStartMs + positionMs`), not raw position alone:

| Paused action | Content clock relative to held reference | Observed result |
| --- | ---: | --- |
| Pause | 0 ms | Clock held; playing/playWhenReady false |
| Back 5 s | −5,000 ms | Exact content-clock change; new frame event, then READY |
| Forward 5 s | 0 ms | Exact return to reference; frame event and READY |
| Play | advancing | PLAYING resumed, retaining added delay |

The paused snapshots showed the same news photograph at these points, so they
do not prove visually distinguishable/frame-accurate alignment. Running Back
and Forward also returned to PLAYING after buffering; the reported live offset
changed from about 32.2 to 38.3 seconds and then 33.5 seconds. Buffering/wall time
means those running after-samples are not exact five-second deltas. A subsequent
10.1-second hold preserved the content clock and increased offset from 33.5 to
43.6 seconds; resume retained about 43.6 seconds. Explicit Live recovery reduced
it to about 22.2 seconds and resumed playback. This is a default playback target,
not zero latency or movement into future footage.

At 21:14:29, manifest refresh policy began refusing the source while buffered
playback continued. The old wrapper emitted only REFUSED, so the cause was not
identified. At 21:14:52, a Forward 5 s request clamped to the stale 60.868-second
window end and failed with PLAYER_FAILED code 2000, followed by STOPPED. It is
not successful edge recovery, and the unresolved refresh failure confounds
that edge test. Later taps after STOPPED had no player to control. The two-minute
timer still closed at its original deadline. No second native license request
was observed. Content/key transition is only a hypothesis, not a conclusion.

The follow-up adds closed refusal stages and distinguishes an unrecognized
protection preflight from a changed public key-ID set without logging any IDs
or opaque material. It also corrects the wrapper's error type: a plain
IOException is retried by Media3 even with a zero minimum retry count, as the
[tagged error policy](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/upstream/DefaultLoadErrorHandlingPolicy.java)
shows. Policy refusal now uses a sanitized, non-retryable manifest ParserException
without its raw cause. This preserves the boundary rather than permitting the
previously refused model. A synthetic Android fixture verifies its category,
message, missing cause and closed stage. The first eight-fixture phone run
exposed a test-only expectation mismatch: Media3 appends fixed malformed/type
metadata to its error message. The assertion now includes that SDK suffix; the
message still contains no raw cause or source data.

A fresh 21:19–21:21 attempt reached native keys/frame/playback again. With
healthy manifest refreshes, nine paused Forward 5 s taps advanced the content
clock until the last request clamped to the advertised 60.048-second window end.
That request buffered rather than failing. Play was requested while buffering;
the next ordinary refresh delivered another frame, READY and PLAYING about
2.7 seconds after the clamped seek. Another brief rebuffer recovered, and all
refreshes continued through the original deadline without another DRM exchange.
This establishes one healthy-manifest edge recovery, not universal edge safety.

The reported live offset became slightly negative near this edge (about −3.1
seconds while held). Native clock normalization/stream timestamps therefore
must not be interpreted as verified physical live latency or future footage:
the media was already available in the advertised window and fetched normally.
Phone-clock accuracy and stream-clock mapping remain experimental assumptions.
The earlier refusal did not reproduce in this attempt. A separate transition
repeat uses finer closed model/index/path stages and added-versus-removed public
key-ID categories; it does not admit new identifiers, add another exchange or
relax any refused policy. The next fresh attempt (21:26–21:28) again loaded
native keys/video/playback and refreshed 104 declared paths through its original
two-minute deadline without refusal or another exchange. It does not reproduce
or explain the earlier 21:14 failure. Before this run, all eight updated Android
fixtures passed, including the corrected SDK-message expectation.

Read-only source review identified a separate concrete transition risk:
[Media3 finite indexes](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer_dash/src/main/java/androidx/media3/exoplayer/dash/manifest/SegmentBase.java)
can report their entire historical range after an open live period gains an end.
The wrapper now intersects finite dynamic indexes with the advertised history
window plus the unchanged two-minute URL lookahead when the epoch/history depth
are known. It retains all existing caps, static/unbounded fallbacks and license
guards; no cap is raised. Boundary candidates must actually overlap the interval
because SDK segment-number lookup clamps before/after an explicit timeline.

New synthetic fixtures use an injected fixed clock: an open ten-hour period
becomes finite when subsequent periods appear. The old calculation would admit
18,000 historical segments and refuse the bound; the new calculation expects
25 old and 65 new segment files, no segments in a period beyond lookahead, and
only three exact initialization files. Separate fixtures exclude entirely
expired or beyond-lookahead explicit timelines. These fixtures target a proven
SDK/wrapper interaction, not a proven diagnosis of the earlier phone failure.
All ten synthetic Android parser fixtures subsequently passed. The final News
APK SHA-256 was
`3495ef4138a5bbd95de54b62423cdbf7a7eba442d6fcc0347ca26d7fb9fcf627`,
with the shared signing certificate verified. A fresh 21:34 attempt reached
DRM_KEYS_LOADED, VIDEO_FRAME and PLAYING; refreshes remained healthy at 104
declared paths through the last 21:35:12 observation. The last observation is
not a claim that the entire subsequent budget completed without error.

### Native mixed-pair preparation and replay source boundary — 2026-10-06

The user requested two native comparison cases: live Twitch + live ABEMA News,
and recorded Twitch + the approved free sumo replay. Existing browser and native
single-source/same-Twitch replay examples remain intact. A new provider-neutral
`NativeMixedPair` controller prepares joint Play/Pause/focus, individual volumes,
and held per-pane relative seeks. It checks the selected player's own stable
live content clock or replay position before optional joint resume (100 ms
tolerance, eight-second timeout). Its signed adjustment ledger is a requested
change relative to a user-chosen event anchor, not a measured difference between
unrelated provider timestamps. Pair UI/hosting and simultaneous native decoding
are not implemented or device-verified by this preparation alone.

An additive `ABEMA_BROKER_REPLAY_SOURCE_PROBE` extra selects only the previously
tested `394-72_s10_p8529` free replay in the isolated broker profile. The original
dash.js source attachment calls are observed unchanged. The source-only button
does not invoke the native CDM or fetch native media; outputs are closed shape
and CDN categories, not source URLs or query values.

The prerequisite APK (`5f40e4f38dc0562489ff318b0d5ae1e8b8bb668618460422246e59ef9243558d`)
passed debug unit tests/lint/assembly and certificate verification before a
data-preserving update. A first warm dispatch delivered an intent to the old
activity; a separate force-stop/cold launch correctly selected the new case.
On the Pixel 6 / Android 17 / WebView 153.0.8010.36, with the existing unsupported
desktop identity, isolated profile and user-enabled Proton system VPN, the
episode first displayed optional onboarding. Its My List tip was closed and
the visible Later action selected; no credentials or account consent were
entered. The original player then rendered sumo footage and its licensing
helper reached READY at 21:46:16 device time.

At 21:46:14 the selected source classified as `QUERY / OTHER`, not the query-free
News CDN path. No URL/query/opaque payload was printed, persisted or requested
by native playback. That source therefore remains refused by the current native
policy. This is a source-policy boundary, not a failed native license exchange,
DRM incompatibility finding or proof of native replay. A replay-only exact
provider-selected-source policy needs a deliberate decision before native
replay and either combined comparison can be claimed tested.

Preparation verification: 375 debug JVM tests passed, including fourteen new
mixed-pair controller tests; all 43 broker JavaScript fixtures passed. Debug
lint reported no errors, five warnings and one hint. Targeted pre-commit checks,
including docs build and secret checks, passed; a follow-up explicit directory
scan used the installed scanner executable because the standalone mise shim
had no version selected. Review found no source-only fetch/CDM path or changed
News broker surface. The final debug APK SHA-256 was
`bb9c9b4bccf3d79f1464d1434e2b302a14833d57a74016dff9275923f8da1379`,
16,664,822 bytes, host-owned, with the required shared certificate verified.
The phone still used the earlier source-prerequisite APK for the observations
above; no native pair was installed or played. Tachiai was stopped afterward
to release the phone. No existing example/data was removed or commit made.

### Native free replay and held timing — 2026-10-06

The approved additive native-replay route subsequently admitted the original
selected source as `QUERY / DS_VOD_AKAMAI`, keeping the earlier source-only
refusal intact. Conditions remained Pixel 6 / Android 17 / WebView
153.0.8010.36, unsupported desktop identity, isolated persistent browser profile,
user-enabled Proton system VPN and the exact free `394-72_s10_p8529` replay.
No sign-in or payment was requested; account/exit identity was not inspected.

With APK SHA-256
`a2138d73c24e4b781d3d41ef961c9705ec209637fdbe5c5f148a0634b32b8009`,
the initial source and native MPD requests returned 200 and declared 860 exact
media paths. At 22:17:23 device time the native player reached
DRM_RESPONSE_HANDED_OFF, DRM_KEYS_LOADED, VIDEO_FRAME, READY and PLAYING.
The large native surface showed changing sumo footage and its clock advanced.
Duration was 584,984 ms; live/dynamic were false and seeking was available.

Held native timing settled from 48,466 ms to 43,466 ms after Back 5 s, then to
48,466 ms after Forward 5 s. Both requests produced subsequent VIDEO_FRAME and
READY events. These are exact clock readbacks, not frame-accurate image-match
proof: the immediate comparison screenshots were taken before reliable frame
settlement. Play resumed and the clock advanced to 76,821 ms before the expected
two-minute TIME_LIMIT at 22:19:19. No second native DRM exchange or playback
error was observed in this run.

The original web player was mute-requested but still running. The user heard
audio and then silence, but the question overlapped intentional native pauses
and the budget expiry. Isolated native replay audio therefore remains
unconfirmed. A replay-only Pause web diagnostic now returns only paused/muted
booleans and a bounded media clock, with a read-only repeat five seconds later.
Its coordinated native-only audio check remains pending. Pausing the original
video after startup is distinct from disposing its WebView or preparing the
unchanged helper without original playback; neither renewal nor headless
bootstrap has been established.

The diagnostic update passed 382 debug JVM tests and 47 broker/timing JavaScript
fixtures, with no lint errors (five existing warnings and one hint), scoped
pre-commit/docs checks and explicit source-directory secret scans. Final APK
SHA-256 was
`d34ddf36e449846a35b060042733e835e751c3577c65bcba46ea4c90f3050162`,
16,665,874 bytes, host-owned, with the shared signing certificate verified.
It was installed without clearing app data. Slow original-page initialization
on October 6 exhausted the helper budget before native startup, so no isolated
audio result was obtained. On the next authorized October 7 attempt, the phone
was connected/unlocked and Android reported Proton VPN connected, but the
original page explicitly rejected the current region at 07:04 device time.
Native startup was not attempted. The VPN marker alone therefore still does
not establish an ABEMA-accepted connection; the native-only check remains
pending restoration of original-page playback.

The user confirmed the exit had already been Japan and reconnected it. A fresh
07:07:54 Tachiai launch still showed the explicit region rejection. Opening the
same public episode in the phone's real Chrome browser also displayed that
rejection at 07:09. This comparison places the current blocker outside a
Tachiai-only rendering failure; it does not identify the provider's decision
mechanism or independently establish the exit's geography. No login/session
transfer, VPN setting change or native DRM request was attempted.

#### Coordinated native-only replay succeeds — 2026-10-07

After the user's next connection adjustment, a fresh 07:48:15 launch reached
the original replay and READY at 07:49:15. The optional questionnaire was
dismissed with Later. The same final APK and Pixel 6 / Android 17 / WebView
153.0.8010.36 / unsupported desktop identity / isolated profile were used;
Proton was user-controlled, with exit identity and account state uninspected.
Native startup at 07:50:14 reached DRM_KEYS_LOADED, VIDEO_FRAME and PLAYING
at 07:50:18 through the unchanged one-initial-exchange helper.

At 07:50:58 the original web video was explicitly paused and muted at
101,404 ms. Its read-only repeat at 07:51:03 remained paused/muted at exactly
101,404 ms. The native clock continued from 37,423 ms at 07:50:55 to
112,459 ms at 07:52:11, with changing sumo footage and no intervening pause,
seek or error observed. The user confirmed moving large native video and
audible commentary during the announced native-only window at 50% volume.
This establishes short native replay video/audio without concurrent original
web playback, not WebView disposal, startup without the original player,
license renewal, longer sessions or simultaneous native Twitch/ABEMA mixing.

#### Additive native mixed-pair host — 2026-10-07

The debug broker activity now accepts a separate `ABEMA_NATIVE_PAIR` LIVE or
REPLAY mode. LIVE selects News/RelaxBeats; REPLAY selects the approved free
sumo episode and Twitch VOD 2080217716 at 70 minutes. Earlier flags and examples
are retained. DASH now implements the same generic member interface as HLS,
with its original single-player autoplay default unchanged. The pair prepares
both paused, keeps the original ABEMA WebView attached, and confirms original
pause/mute before joint Play requests one native group audio-focus owner.
Controls select A (ABEMA) or B (Twitch) for earlier/later five-second seeks,
holding both until the selected clock settles before optional resume, plus
independent mute/50% volume. The adjustment ledger records requested changes,
not event alignment or subtraction of unrelated source clocks.

The existing Twitch LOCAL slot is validated in the isolated process. Stored
record identity is rechecked before validation/access, on native loader I/O
(disk checks throttled to one second), and every five seconds on the worker.
The budget's main-thread checks use cheap state only; cross-process Forget is
bounded detection rather than instant revocation. A one-shot command gate
prevents a delayed original-web pause callback from overriding a newer Pause,
shift, focus loss, Stop or Play. The exact ABEMA replay source is checked again
after Twitch resolution. Source/DRM/media policies and the shared two-minute
foreground deadline are not broadened.

The first installed pair APK
(`5e11eac1ae46a710f4662474938eaf7e71143c8d19c08c5945953e6ee347673f`)
passed 382 JVM tests, 48 broker/timing fixtures, lint/assembly and signing
verification. A fresh REPLAY-pair launch at 08:06:33 showed the original ABEMA
region rejection, despite Android reporting Proton connected and an unlocked
phone. Native pair preparation was not started. Both mixed live/live and
replay/replay playback, decoding, acoustic mixing and paired seek outcomes
therefore remain pending, not failed CDM or paired-player observations.

Final verification added three one-shot/stale-Play command-gate regression tests:
385 debug JVM tests and all 48 broker/timing JavaScript fixtures pass. Debug
lint has no errors, five existing warnings and one hint. Scoped pre-commit/docs
checks and explicit source-directory secret scans passed. Final host-owned APK
is 16,671,474 bytes, SHA-256
`3ca7451ab5be7d219bc5969ea9ea4a150e8591a2622b43260fe5c4096816a2d5`,
with the required shared certificate verified. The data-preserving update was
installed; it is not yet a paired playback result. Review retained earlier
examples and found no generated APK/image/build additions, provider-policy
broadening, credentials in logs or Git mutation.

#### First native replay/replay playback — 2026-10-07

After the user reconnected Proton, a fresh 08:18:07 REPLAY-pair launch reached
the optional questionnaire rather than the region-rejection page. Later was
selected. Conditions remained the same Pixel 6 / Android 17 / WebView
153.0.8010.36 / isolated unsupported desktop browser profile, anonymous/free
ABEMA replay and previously saved Twitch LOCAL authorization. Provider account
details, exact exit and entitlements were not inspected.

Start at 08:19:52 selected the exact replay source, fetched its MPD with HTTP
200, validated the existing LOCAL slot with HTTP 200 and resolved Twitch
access with HTTP 200. The ABEMA source recheck passed. Both native players
prepared paused: ABEMA loaded its own DRM keys and frame, while Twitch loaded
the existing moving-match VOD at 4,200,000 ms. Both reached READY.

Joint Play at 08:20:50 first confirmed original ABEMA paused/muted at
191,550 ms; the read-only repeat five seconds later retained that exact clock.
Native A advanced from 1,744 to 57,486 ms and native B from 4,201,781 to
4,257,519 ms during the observed joint window. Screenshots showed changing
sumo and Rocket League footage together. Android reported one Tachiai
`NativePlaybackAudioGroup` focus owner, with no focus loss. No seek/pause was
issued during the announced acoustic check; its user confirmation remains
pending. The expected shared TIME_LIMIT stopped the case at 08:21:52.
This establishes bounded simultaneous native replay decoding/playback, not
confirmed mixed audio, paired timing results, exact event sync or longer runs.

#### Coordinated native live mixing — 2026-10-07

The user requested either readiness coordination or a longer listening window.
Only the mixed-provider pair now has an explicitly selected five-minute
foreground cap from native preparation, still reduced by remaining saved
retention. Earlier examples retain two minutes; Stop, background and invalid
authorization remain terminal. This changes no helper lifetime, licensing
exchange, renewal or media/source policy. Two budget regression tests cover
the five-minute ceiling and retention/foreground/Stop behavior. Verification
passed 387 debug JVM tests, 48 JavaScript fixtures, lint (no errors, five existing
warnings and one hint), assembly, scoped hooks/docs checks and certificate
verification. The installed host-owned APK is 16,671,474 bytes, SHA-256
`0ae8d85ff4aa19640da42d683f86534c02ecb921ad97c19751262bc47246d365`.

On the same Pixel 6 / Android 17 / WebView 153.0.8010.36, a fresh LIVE pair
selected anonymous ABEMA News and RelaxBeats with the existing saved Twitch
LOCAL grant. The isolated unsupported desktop browser identity and user-controlled
Proton connection remained; exit/account identity and entitlements were not
inspected. Native preparation at 08:31:29 returned validation/access HTTP 200,
DASH/HLS media HTTP 200, native DRM keys and frames, and both READY. Joint Play
at 08:31:44 confirmed original web pause/mute first. Both native clocks advanced
continuously through 08:35:45. The user confirmed hearing both News audio and
RelaxBeats music, with active News video; Twitch's mostly static artwork is not
moving-event evidence. This is a bounded mixed-provider native live-audio pass,
not sustained synchronization, provider support or renewal evidence.

Running A earlier/later requests at 08:36:20 and 08:36:25 each held both players,
rendered a subsequent frame, reached READY and resumed both automatically. The
shared deadline ended at 08:36:29 before B taps; those late taps issued no seek.
In a separate repeat, waiting paused about forty seconds before joint Play aged
both content points behind their current advertised windows. Earlier requests
were refused as OUTSIDE_WINDOW; later requests were explicitly CLAMPED to the
window start and resumed both, not exact five-second changes. This distinguishes
buffered playback retention from arbitrary seeking outside the current window.
An immediate-start repeat then started both at 08:40:50. B later requested
19,003 ms at 08:40:52 and B earlier requested 11,729 ms at 08:40:58; both were
REQUESTED (not clamped), rendered subsequent frames and resumed both players.
A earlier requested 26,256 ms at 08:41:03 and likewise resumed both. Automatic
resume is guarded by the selected held target check within 100 ms, not proof of
frame/acoustic precision or unchanged cross-provider alignment after buffering.

At 08:41:04 a subsequent News manifest refresh classified its initialization
as UNRECOGNIZED and failed PREFLIGHT; declared-path parsing did not proceed.
A later at 08:41:08 was requested but then ended in PLAYER_FAILED code 3004
and whole-pair teardown. The earlier successful A later run remains separate;
this repeat did not complete it. No protection-policy relaxation or second
native licensing exchange was attempted. A clear/ad/program transition versus
initialization-parser limitation remains unclassified. This recurring refresh
boundary, together with finite live seeking and rebuffer drift, is a reliability
gate despite the confirmed live mixed-audio pass.

#### Longer coordinated replay-pair repeat — 2026-10-07

The same verified five-minute APK and phone/profile/network conditions were
used for the separate replay pair. Recurring optional onboarding delayed the
original player past earlier helper deadlines; those attempts never prepared
native media. A test-harness timestamp command also failed and was stopped,
then replaced by checking the latest closed readiness marker. These are startup/
harness limitations, not native DRM rejection; no helper budget was extended.

After dismissing the visible Later prompt, native preparation at 08:48:54 passed
LOCAL validation/access HTTP 200 and the exact replay source recheck. ABEMA
loaded its own keys/frame/READY; Twitch prepared the moving-match anchor at
4,200,000 ms. Joint Play at 08:49:07 confirmed original web pause/mute at
291,615 ms before both native PLAYING events; the read-only web repeat retained
that exact clock. Native A advanced from 1,726 to 37,162 ms and B from 4,201,740
to 4,237,185 ms in the first observed window. Android identified one native
group focus owner, and the user confirmed hearing both Japanese sumo and Rocket
League commentary at 50% each. No seek or pause overlapped that listening check.
This confirms bounded mixed-provider replay audio as well as the earlier live
audio pass, not sustained decoding, precise alignment or helper independence.

Joint Pause at 08:51:16 held A at 128,836 ms and B at 4,328,851 ms. The four
sequential actions each produced REQUESTED, a subsequent VIDEO_FRAME/READY and
an exact held clock readback; the unselected player's clock stayed fixed:

| Selected action | Held before (ms) | Held after (ms) |
| --- | ---: | ---: |
| A earlier 5 s | 128,836 | 123,836 |
| A later 5 s | 123,836 | 128,836 |
| B earlier 5 s | 4,328,851 | 4,323,851 |
| B later 5 s | 4,323,851 | 4,328,851 |

The final panel reported the selected held target observed within 100 ms and
the requested adjustment ledger returned to zero. Joint Play at 08:51:44 again
confirmed original web paused/muted at 291,615 ms, resumed both, and produced
advancing native clocks. This passes paired replay bidirectional primitives on
these fixed resources, not frame/acoustic-precision alignment. No second DRM
exchange, renewal, background or rotation test was introduced.

#### Closed refresh-refusal diagnostics — 2026-10-07

The user approved a diagnostic follow-up to the live refresh failure. The
existing bounded SAX initialization parser now optionally reports one closed
reason and counts of visited periods, adaptation sets, protection elements,
default-ID declarations and known CENC/CBCS/other protection categories. Counts
are bounded by the unchanged byte/element limits and describe only the visited
prefix when parsing refuses. The native manifest preflight logs the first shape
and subsequent changes, deduplicating identical summaries. No identifiers,
hashes, attributes, payloads, raw XML, URLs or exception messages are output.

Reasons distinguish absent default IDs, declarations, byte/element/depth/ID
limits, namespace/scope/hierarchy errors, scheme/cipher mismatch, invalid/zero
IDs, same-scope conflicts, malformed XML and parser setup/general failures.
The original return value and acceptance checks remain unchanged; an ordinary
diagnostic callback exception cannot affect acceptance. Unknown protection
without a default-ID attribute remains ignored exactly as before, and opaque
protection payloads are not inspected. No default IDs is not proof of clear
media or an advertisement; the diagnostic does not classify entitlement,
decrypt content or authorize another license exchange. Device reproduction of
the earlier unrecognized refresh was pending at this preparation checkpoint.

Verification passed 398 debug JVM tests (eleven new diagnostic tests), all 48
broker JavaScript fixtures, debug assembly and lint with no errors and the same
five warnings/one hint. Scoped source hooks and a redacted debug-source scan
passed. Read-only review confirmed unchanged acceptance conditions and closed
output. The host-owned APK is 16,671,474 bytes, SHA-256
`963cbc224e33b36644ff5e94cc5429dcda23601456b233aa54be63faef25ec3e`;
its certificate matched the required shared debug identity after both builds.

The data-preserving update was installed on the same unlocked Pixel 6 / Android
17 / WebView 153.0.8010.36 with the isolated unsupported desktop identity and
user-controlled Proton connection. Exit/account identity stayed uninspected.
A 09:18:32 LIVE launch reached original top-document play but stayed at
WAITING_TRANSPORT until the unchanged helper deadline at 09:20:35; native
preparation did not start. A fresh 09:21:38 launch reached READY and native
preparation at 09:22:09. LOCAL validation/access returned HTTP 200; its initial
native DASH diagnostic was ACCEPTED / periods 1 / adaptations 2 / protections 4 /
default-ID declarations 2 / CENC 2 / CBCS 0 / other 2. These are structural
categories, not identifiers or DRM-system inference. Its own native keys/frame
loaded, both players reached READY, and joint Play succeeded at 09:22:21.
This confirmed the new diagnostic runs on-device; the refresh refusal had not
yet recurred at this checkpoint.

In that healthy repeat, all four running live nudges were REQUESTED (not
clamped), produced subsequent frames/READY, passed the selected held-target
check and resumed both. B later/backward targets were 14,547 and 14,365 ms at
09:23:19 and 09:23:24; A backward/later targets were 27,017 and 31,356 ms at
09:23:29 and 09:23:34. These are targets from different running samples, not
inverse pairs of a fixed clock. Both content clocks continued afterward;
the initial structural summary stayed accepted during these actions. This
adds a successful paired live ABEMA forward repeat without resolving the
earlier manifest-transition refusal or proving preserved event alignment.

##### Refusal reproduced and classified

At 09:25:01 the refreshed MPD remained ACCEPTED but changed to two periods and
four adaptation sets, still four protection elements/two default-ID declarations
(CENC 2, CBCS 0, other 2). Native playback continued without a second license
exchange. At 09:25:56 the full SAX parse completed with NO_DEFAULT_ID: two periods,
four adaptation sets, zero protection elements and zero default-ID declarations.
This is not a malformed XML, namespace/cipher, identifier-format or limit refusal.
The unchanged initialization-set preflight refused that MPD before declared-path
publishing or Media3 refresh parsing. Already buffered A and B clocks continued;
at 09:26:23 A reported PLAYER_FAILED 3004, then STOPPED and whole-pair teardown,
before the five-minute deadline. No seek overlapped this manifest refusal.

The immediate failure is therefore our requirement for the initial protected
initialization on every refresh. Clear-looking periods replacing protected
periods are a plausible explanation for this News transition, not proof of
unencrypted samples, advertisement identity or all earlier unclassified failures.
No raw manifest, identifier, key, license or provider credential was inspected
or exported. The current policy remains unchanged. A next comparison would need
to distinguish explicitly unprotected periods from unsupported/missing DRM
metadata while preserving declared-file binding, all provider content/ads,
the existing CDM session and refusal of new/unknown protection or renewal.

#### Additive whole-document transition comparison — 2026-10-07

The user approved a separate `ABEMA_NATIVE_PAIR=LIVE_TRANSITIONS` case. The
older `LIVE` and `REPLAY` cases retain their strict refresh preflight and DRM
session lifetime. This comparison keeps the same News source, original WebView,
native pair controls, five-minute foreground budget and one initial opaque
exchange. It does not remove periods, advertisements or protection metadata.

The first native MPD must still contain exactly the resolved initial nonempty
ID set. A bounded second SAX audit learns transient protection scheme/value,
namespace and AdaptationSet/Representation scope shapes from that first MPD.
Later protected MPDs must retain the exact ID set and introduce no new shapes
or DRM-marker kind/namespace/scope. These shapes are initial-baseline-known,
not universally recognized or officially supported protection systems. There
are at most 32 protection shapes and 64 marker shapes, with bounded retained
names/values; opaque payload text is not read or compared. A policy instance is
owned by one serial manifest loader, never shared across native players.

A wholly declaration-free refresh is admitted only after that protected start,
after complete bounded XML validation and absence of any local-name protection,
default-ID or known DRM-init marker, including namespace lookalikes. Before any
new file paths are published, Media3's parsed representations must also have no
DRM initialization data. This is a comparison of manifest declarations, not
proof of clear samples; encrypted/unsupported samples may still fail normally.
There is no per-period protection rewrite or fallback decryption path.

Only this case gives the existing Media3 DRM session a five-minute keepalive
through clear intervals. Stop, background, error or the shared deadline still
release the player/session. Clear-sample-without-key playback remains disabled;
provisioning, renewal and a second exchange remain refused. Exact declared-file
binding, verified TLS, source selection, helper and encrypted Twitch LOCAL
grant rules are unchanged. Closed transition verdicts replace detailed shape
logging in this mode; no IDs, signatures, XML, URLs or payloads are output.

Device transition playback and return to protected content remain unverified
at this implementation checkpoint. Synthetic tests are not proof of live ad
transitions or session reuse.

Verification passed 408 debug JVM tests (ten new transition-policy tests), all
48 original broker JavaScript fixtures, debug assembly and lint. The Pixel 6
passed all 12 real Media3 parser instrumentation tests, including unchanged
protected/clear/protected document parsing with declared files retained and
model-policy refusal before file publication. Both app and instrumentation APK
certificates matched the shared debug identity. The installed host-owned app APK
is 16,671,474 bytes, SHA-256
`a91c35799fedcbb20c0f4026eb14509b22d3dc88fcf9fa44c0b262a5c1dbc6bf`.
Read-only policy review, scoped hooks and a redacted debug-source secrets scan
passed; generated build artifacts remain ignored.

The first device attempt launched at 09:43:25 under the same Pixel 6 / Android
17 / WebView 153.0.8010.36 and user-controlled Proton connection. Account/exit
identity stayed uninspected. Native preparation began at 09:45:11; protected
initialization, one DRM exchange, keys, frames and both READY succeeded. Manual
joint Play waited until 09:45:53, after Twitch's initially paused content clock
had aged outside its advertised short live window. Both ran from buffered media,
then Twitch failed with code 1002 (behind-live-window) at 09:46:23 and the whole
pair stopped. No ABEMA declaration-free refresh occurred in that attempt. This
is not evidence of a transition-policy or license failure.

One fresh repeat launched at 09:47:46. A temporary local startup coordinator
requested Start immediately at broker READY (09:48:36), then Play at 09:48:51,
about one second after both native players became READY. It reads only the
closed scoped app markers and uses the already inspected native controls; no
provider source, credential or opaque payload is involved. The same single
exchange loaded native ABEMA keys/frames and both clocks advanced while normal
protected refreshes remained accepted. Timing nudges are deliberately omitted
from this transition comparison to avoid overlapping seek failures.

That repeat retained SAME_PROTECTION throughout and ended at the expected
TIME_LIMIT at 09:53:36. Both native content clocks continued advancing until
the cap, without another exchange or player/policy failure. This establishes
ordinary protected playback with the comparison's keepalive, not clear-period
decoding or protected-session reuse after a clear transition.

##### Actual declaration-free refresh and late initial license failure

The final fresh attempt launched at 09:54:16, reached broker READY at 09:55:11
and native preparation at 09:55:12. The initial MPD was INITIAL_PROTECTED at
09:55:21; both native frames/READY loaded and joint Play succeeded at 09:55:27.
No native DRM request, response or keys-loaded event occurred at preparation.
Protected declarations in an MPD do not prove that the selected samples already
needed a DRM session; this run therefore did not establish an initialized native
session before the declaration-free interval.

At 09:55:51 the transition became NO_DECLARATIONS and exact declared files were
published successfully. Repeated refreshes stayed admitted through 09:56:46,
with A and B clocks advancing at READY/playing and no overlapping seeks. At
09:56:51 the MPD returned to SAME_PROTECTION and again published files. The
earlier strict NO_DEFAULT_ID preflight failure was not reproduced: the new
manifest/model gate admitted this real transition without dropping periods or
rewriting protection data. Acoustic confirmation and a moving-picture capture
during this particular interval were not collected.

At 09:56:51.949 the **first** native DRM_REQUESTED event occurred, followed by
DRM_REFUSED at .984 and DRM_FAILED at 09:56:52. Buffered playback continued until
PLAYER_FAILED 6004 and whole-pair teardown at 09:57:13. The native gate emits
REQUESTED only after accepting initial type, same-set challenge metadata and
its unused one-shot slot; this was not a refused second exchange or changed-ID
request. No response-handoff/keys-loaded event occurred in this attempt.

The unchanged browser helper starts its hard 120-second clock on document
installation (around 09:54:21 here), independently of native preparation or the
five-minute pair budget. Its `live()` check and timeout clear its transport and
refuse `begin()` after that clock expires. The first native challenge arrived
about 31 seconds after that helper window: a local lifetime mismatch is the
identified next boundary, not evidence of a provider HTTP/license rejection.
No new helper/renewal/second-exchange permission was added. The phone was
returned Home after terminal failure. A next separately approved comparison
could align only the unused initial-exchange helper lifetime with the existing
foreground pair deadline; it must still stop after one response and refuse
renewal, source changes, new protection and all second exchanges. Initialized
protected-to-clear-to-protected session reuse remains unverified.

#### Aligned unused initial-helper lifetime — 2026-10-07

The user approved continued work on the local lifetime mismatch. Separate
`ABEMA_NATIVE_PAIR=LIVE_TRANSITIONS_ALIGNED` and
`LIVE_TRANSITIONS_ALIGNED_LATE_START` cases preserve the older strict and
unaligned transition comparisons. They use the same News source, native pair,
manifest/model checks, CDM keepalive and five-minute foreground deadline.

The original two-minute document capture limit remains until READY. Only an
unused/live/READY helper in an aligned case exposes `armInitialExchange`.
Native preparation requests one arm with its remaining foreground time, a
primitive integer in 1..300000 ms. It must complete as literal `true` within
three seconds under the current run/browser/route/foreground guards; refusal
or timeout fails the run rather than falling back. The helper cannot arm twice,
revive after expiration/Stop/pagehide, or arm after use. Its old expiry timer is
replaced once, with validity independently bounded by the armed deadline and
document start plus seven minutes (at most two minutes capture plus five pair
minutes). Native foreground cancellation/deadline remains independently active.
Stop and one-shot response consumption clear the timer and captured transport.

This changes only the lifetime of an unused initial exchange in these explicit
cases. It does not reauthorize an exchange already consumed, extend response
transport timeouts, renew a DRM session, permit provisioning, change source
selection or copy provider algorithms. Existing replacement/STALE checks and
verified HTTP/license response bounds remain intact. Only a closed arm verdict
is logged; no account, challenge, response, URL or payload is output.

The late-start variant waits a fixed 130 seconds before resolving sources or
constructing native players. The delay consumes the same five-minute budget;
it does not grant five extra minutes of playback. This makes an initial request
after the old helper lifetime reproducible without relying on live scheduling.
No native source or Twitch validation lease is resolved before the wait.
Pending preparation is removed on cancellation and its running flag cleared,
so a stopped wait cannot block Reload or revive after background/destruction.
All older cases prepare immediately.

Verification passed 413 debug JVM tests (five fixed-case tests), 56 broker
JavaScript fixtures (eight new lifetime fixtures), assembly and lint. The
fixtures cover original deadline/API preservation, late first exchange,
one-shot/invalid arm, delayed-timer expiration, timer rescheduling/restoration,
pending response cancellation and replacement revocation. Read-only review and
scoped source hooks passed. Both successive builds matched the shared debug
signing certificate. The installed host-owned app APK is 16,671,474 bytes,
SHA-256 `dd2fc5e6cdc6ccace90e8e793897ebdf006e891b0e58e2295dcad94174ac7f69`.
On-device late exchange and transition durability remain unverified at this
implementation checkpoint.

##### Late first exchange and pending-start cancellation observed

On the same unlocked Pixel 6 / Android 17 / WebView 153.0.8010.36, with the
user-controlled Proton VPN presence marker and uninspected exit/account state,
the late-start variant launched at 10:13:52. At 10:14:16 native preparation
armed the unused helper successfully, then Stop at 10:14:19 canceled its pending
start. Reload reached fresh READY, and Start at 10:14:36 armed again only in the
new document/run. No native preparation from the canceled run revived.

The fixed 130-second wait completed before fresh source/Twitch validation and
native preparation. The initial DRM request occurred at 10:16:53.705, roughly
33 seconds after the reloaded document's old 120-second helper window would
have ended. Its one response was handed off at 10:16:55.180, native keys loaded,
both frames/READY appeared and joint Play succeeded at 10:16:59. Both native
clocks advanced through TIME_LIMIT at 10:19:36. This passes a real late initial
exchange and ordinary playback within the unchanged pair cap. It does not
establish renewed licensing or initialized-session reuse through a clear period.
No acoustic recheck was requested for this lifecycle-specific run.

Review also tightened the native arm acknowledgment to an immediate three-second
deadline, rather than beginning that wait only after delayed preparation. A
run-bound/identity guard releases pending-start bookkeeping even if main-loop
dispatch is delayed past the deadline; it cannot clear a newer run. These
additional lifecycle guards require the subsequently verified update before the
next phone comparison. Earlier device arm acknowledgments completed within
milliseconds; late/blocked callback behavior was not deliberately injected.

The two earlier real declaration-free refreshes occurred near :25 and :55.
Timing the next five-minute immediate aligned attempt around :25 is an
experimental scheduling hypothesis, not a verified provider/ad schedule. The
desired follow-up begins with actual native keys loaded, then observes the
manifest transition and return without another exchange. Original web playback
will remain attached and pause/mute-confirmed as in the other native pair cases.

##### Real transition with a late first exchange observed

The next immediate aligned run used the tightened cancellation/acknowledgment
build: APK SHA-256
`ceb514dc5118eee6d4891610ee4184a9ff5e1f622a169f4cc8b3552ba766e93f`,
16,671,474 host-owned bytes. The same 413 JVM tests, 56 JavaScript fixtures,
assembly/lint and shared-certificate verification passed. Device, engine, VPN
presence and uninspected account/exit conditions were unchanged.

The helper began capture at 10:22:56.891 and armed at 10:23:44.014. Joint native
Play at 10:23:58.417 produced frames and advancing clocks, but **no native keys
had loaded yet**. The real manifest changed from accepted protection to
NO_DECLARATIONS at 10:24:22.408 and back to SAME_PROTECTION at 10:25:22.411.
The first native DRM request at 10:25:35.854 was about 39 seconds beyond the
old helper expiry. One response was handed off at 10:25:39.231 and keys loaded
at .240. Native News frames continued, including a moving presenter inspected
on screen; Twitch's artwork and native clock continued. The pair reached its
original TIME_LIMIT at 10:28:44.059 without DRM refusal or a second exchange.

This establishes bounded playback through a real declaration-free refresh
and return, with a late **first** exchange under the aligned lifetime. It does
not establish an already initialized session surviving a clear interval,
identify advertisements, or establish renewal/rotation support. Original web
playback remained attached and pause/mute-confirmed; no new acoustic check was
needed for this lifecycle test.

After return, A earlier, A later and B earlier requested in-window targets and
settled/resumed. B later was requested only 0.15 seconds before the cap; that
attempt did not observe settlement. A fresh short repeat initially failed to
reach helper READY and made no native request. A page-preparation retry then
loaded native keys/frames and both feeds played. B later requested +5000 ms
at 10:39:50.995, reached READY at 10:39:51.098 and joint playback resumed at
.187. Its reported live offset decreased from about 99.5 to 94.8 seconds.
The run was deliberately stopped after that check. These remain request/clock
tests, not frame-accurate alignment or moving-event evidence for Twitch artwork.

##### Separate accepted-manifest prewarm comparison

`ABEMA_NATIVE_PAIR=LIVE_TRANSITIONS_ALIGNED_PREWARM` is an additive News-only
comparison, not an automatic fallback. It retains the aligned helper,
protected-first real-manifest checks, declared-file policy, five-minute cap
and one opaque initial exchange. The actual MPD and playback track formats are
not rewritten. Only after the complete bounded parser succeeds, including
media-path publication and a fresh active-budget check, it asks the same
Media3 manager to preacquire a session with the already validated source IDs
and the existing common-PSSH builder. No license URL is supplied in that format.

The source still owns setPlayer/prepare/release. A synchronized forwarding
wrapper holds the single preacquired reference until final balanced source
release, and refuses rearming after teardown. Media3's documented any-thread
preacquisition posts actual work onto its playback looper; queued acquisition
checks source preparation/release. A separate closed keys-loaded event is
needed to distinguish requested prewarm from completed initialization.
Ordinary playback acquisition still uses the same non-multisession manager.
Stop/background/deadline seals the broker before releasing the player.

This is standard initial CDM initialization, not a second exchange, license
cache, provisioning or renewal. Resource-pressure session eviction can still
occur; the unchanged one-shot gate must refuse any subsequent exchange. The
experimental goal is keys-before-clear, clear playback and protected return
without another exchange; that outcome is not yet verified. See the official
[preacquisition contract](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/drm/DrmSessionManager.java)
and [manager lifecycle](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/drm/DefaultDrmSessionManager.java).

The first device prewarm run began preparation at 10:46:43.209 with the same
phone/engine/VPN-presence conditions. INITIAL_PROTECTED was admitted at
10:46:50.476; prewarm requested at .509 and the one native exchange requested
at .544. Its response was handed off at 10:46:52.035, with a distinct
DRM_PREWARM_KEYS_LOADED at .042, ordinary keys-loaded at .057 and a native
frame at .085. Both sides reached READY and joint Play began at 10:46:56.372.
This verifies standard initial preparation before Play with one exchange.
The run was deliberately stopped after that check; no declaration-free
interval occurred, so session reuse remains unverified.

Follow-up verification passed 422 JVM tests (eight prewarm ownership/exception
tests and six fixed-case tests), all 56 broker fixtures, assembly and lint
(the existing five warnings/one hint, no errors). On the phone, 13 real Media3
parser tests plus one SDK queued-prewarm cancellation test passed: model,
preflight, media-path publication and expired-budget refusals make no accepted
notification; release on the playback looper before queued acquisition makes
zero key/provisioning requests. Scoped hooks/docs and a redacted debug-source
secrets scan passed. The final installed app is 16,671,474 host-owned bytes,
SHA-256 `4691ad2b1bf6b56ca4efefd7a8cd668945172a9908972834149244253741820d`.
App and rebuilt instrumentation APKs matched the shared debug certificate.

A timed follow-up started at 10:54:07. The temporary startup coordinator
returned before the new PID appeared; the already launched process was
resolved and monitored without changing the app. ABEMA never reached player
capture/READY, stopped at its original two-minute capture deadline, and an
inspectable credential-free screenshot showed the provider's region-rejection
page. Android still showed the Proton VPN presence marker; accepted exit and
account state were not inspected. No native exchange/player started in that
attempt. The user was asked to restore the usual working playback connection.
This is a pre-playback regional failure, not evidence against DRM prewarming.

After the user reconnected Proton, a short recovery run on the same phone and
installed build launched at 11:01:24. The helper reached READY at 11:01:48.604,
armed at 11:01:49.173, and accepted the protected initial manifest. Prewarm
requested at 11:01:56.279; the single response was handed off at 11:01:58.104,
prewarm keys loaded at .115 and a native frame appeared at .151. Joint Play
began at 11:02:02.405, with both native content clocks advancing in subsequent
samples. The run was stopped after the connection/preparation check, without
waiting out its five-minute cap or asking for another acoustic confirmation.
No declaration-free transition occurred, so initialized-session reuse remains
the next device question. The previously observed :25/:55 timing suggests a
short run near the next candidate boundary, but is not a guaranteed schedule.

The next timed prewarm run used that same APK and Pixel 6/Android 17/WebView
153.0.8010.36, with the user-owned Japan connection and uninspected account
state. Launch at 11:22:23 briefly paused before resuming at 11:23:11; native
preparation began at 11:23:43.474. INITIAL_PROTECTED was admitted at
11:23:49.874, with one request at .941, response at 11:23:51.447 and both
prewarm/ordinary keys-loaded at .465. Joint Play began at 11:23:55.452.
Native News video was inspected and both content clocks advanced. The recorded
sequence through 11:26:59 had no NO_DECLARATIONS event or second exchange;
both clocks still advanced in samples through 11:28:23. The original cap
ended the run at 11:28:43.542. Earlier markers had rolled out of the device
log buffer by the final read, so the last portion is not a retained complete
DRM/transition trace. No real clear interval was observed in this run and it
does not establish initialized-session reuse. In particular, :25/:55 is not
a reliable transition schedule. Further runs should retain only the scoped
closed marker stream while it is emitted instead of relying on end snapshots.

#### Additive native relative-nudge comparison — 2026-10-07

The same relative comparison cases now include explicit **Catch up A/B** for
live-window recovery, separate from ordinary five-second nudges. Recovery
requires both sources READY and ad-free and the selected source's live-default
command and valid advertised default/window. Replay, missing capability, invalid
default, busy transaction, or hold failure refuses without a default dispatch.
An expired current position is allowed for this explicit operation. Both players
are held; only the selected SDK default command is dispatched. A possible
dispatch invalidates the requested-adjustment anchor, including ambiguous
failure; later nudges cannot make the old ledger valid again. Its historical
number is retained internally, not displayed as an active adjustment or reset
to apparent synchronization.

The eight-second recovery check observes both held READY/ad-free and the selected
live position inside its current positive-duration window. It does not compare
against a stale default timestamp, establish exact live-edge arrival, or prove
decoded playback. Success leaves both paused for explicit Play; if both points
expired, recover each before Play. Pause, focus loss, Stop and teardown cancel
the check. There is no automatic retry, source reload, reauthorization, new
license exchange or deadline extension. Ordinary relative-control evidence
below predates this addition; recovery verification follows here.

The recovery APK passed assembly, lint (zero errors/five existing warnings),
448 JVM tests including ten recovery cases, 60 unchanged browser-helper fixtures
and scoped hooks/documentation build. Read-only review found no blocker and a
redacted debug-source secrets sweep found no leaks. It is 16,674,698 host-owned
bytes, SHA-256
`10e66246509e951a6f0a17d356502d6c6e1eaa081a31beca8b799df62d8804eb`,
with the shared debug certificate verified before the data-preserving update.

The first LIVE_RELATIVE check used the same Pixel 6/Android 17/WebView
153.0.8010.36, existing saved LOCAL Twitch grant, uninspected ABEMA account
state and user-owned Japan playback connection (VPN transport present; source
acceptance established by this run, not by that marker). One initial native
exchange loaded keys at 12:34:54.777–778, followed by joint Play. Pause at
12:35:19.730 aged both live positions negative. Catch up A at 12:36:17.499
requested its SDK default; A reached READY at 12:36:21.123. Catch up B at
12:36:34.906 reached READY at .335 in the next second. Credential-free readback
confirmed the held/current-window check and invalid prior timing anchor.
However, inspection then delayed Play until 12:37:02.995, when both positions
were negative again. Buffered clocks advanced briefly with changing News video,
but Twitch ended with code 1002 at 12:37:33.059. This verifies the separate
default requests/held checks, not successful sustained recovery/resume. A later
input after PAIR_STOPPED dispatched nothing. A prompt coordinated repeat is
needed to avoid consuming the finite recovered window during inspection.

The fresh LIVE_RELATIVE repeat loaded keys once at 12:38:54.429 and played both.
Pause at 12:39:14.300 again aged both positions negative. A default request at
12:39:59.417 reached READY at 12:40:02.079; B's default request at
12:40:02.954 reached READY at .396. Both remained held with the invalid-anchor
readback. Prompt explicit Play at 12:40:04.955 resumed both in-window, with
advancing clocks and changing News footage. Relative +5000 at 12:40:51.367
selected A +5000, reached READY at .424 and resumed both; the invalid-anchor
warning stayed visible rather than claiming the new nudge restored alignment.
This establishes bounded recovery/resume for both live SDKs when the recovered
points are still current, not unlimited pause retention or exact edge arrival.
No new acoustic confirmation or precise event alignment is claimed.

Both clocks continued advancing without a playback failure or second exchange
until the original TIME_LIMIT at 12:43:48.291, about three minutes forty seconds
after recovery. The scoped marker stream retained the complete initial exchange
and recovery sequence. This is a completed bounded test, not renewal or
long-duration reliability.

Following the inspection-delay failure, the relative examples additionally opt
into a fresh current-window check before joint Play. A live/dynamic point with
unknown/nonpositive duration, unknown/negative position or position beyond
duration refuses and holds both before acquiring focus. No default seek is
automatic; explicit catch-up remains required. Replay playback and the older
comparison cases retain their previous Play behavior. This guard prevents a
known stale resume request, not window expiry between snapshots and dispatch
or later continuous playback failures. Guard device verification follows.

The final guard build passed 451 JVM tests (thirteen recovery/guard cases),
assembly, lint and scoped hooks/docs; the debug-source secrets sweep again found
no leaks. Every build in this follow-up matched the shared signing certificate.
The final installed APK is 16,674,698 host-owned bytes, SHA-256
`426cc3d3991f802e98dd7294c181d17126d6c67c5dab32f7b91953768c9495ce`.
The gate remains default-off for older comparisons; relative cases alone opt in.

REPLAY_LIVE_RELATIVE on that final APK reached one ABEMA replay keys-loaded at
12:46:58.173 and joint Play at 12:47:03.431 on the same phone/engine/network
conditions. Pause at 12:47:35.825 held A at 32,151 ms. Catch up A returned
UNSUPPORTED at .861 without dispatch or anchor invalidation. After B's position
expired, Play returned false at 12:48:17.706; readback explicitly required
catch-up and both stayed held. B was then at −25,827 ms while A stayed at
32,151 ms. Catch up B at 12:48:19.281 reached READY at .748 and the held-window
readback marked the prior anchor invalid. Prompt Play at 12:48:21.440 resumed
both; the sumo footage changed and both clocks advanced through 12:48:48.750.
The run was deliberately stopped for the inverse mixed check, not a completed
five-minute test. No second exchange or acoustic recheck occurred. This verifies
live-only recovery with a replay peer and the stale-Play guard on the B side.

The inverse LIVE_REPLAY_RELATIVE case used the same final guard APK and
conditions. One native News exchange loaded ordinary/prewarm keys at
12:49:26.262–263. Joint Play at 12:49:30.654 preceded Pause at 12:49:56.923,
which held Twitch's replay at 4,226,129 ms. Catch up B returned UNSUPPORTED at
.975, without movement or anchor invalidation. A expired during the pause;
Play refused at 12:50:44.229 and the visible readback required explicit catch-up.
A's default request at 12:50:45.953 reached READY at 12:50:49.069, while B stayed
fixed. Held readback marked the anchor invalid. Prompt Play at 12:50:51.160
resumed both; changing News/Rocket League footage and advancing clocks continued
through 12:52:21.758 without a second exchange or playback failure. The app was
force-stopped after this short check, before its cap. No acoustic recheck was
requested. Both mixed directions now have live-only recovery, replay refusal
and stale-Play refusal checks; the two-live recovery repeat separately reached
its cap. Exact edge arrival, new alignment anchoring, renewal and arbitrary
content remain outside these observations.

Final failure-path verification additionally preserves **Stop required** if a
later hold fails during dispatch refusal or recovery timeout; it never claims
both paused in that case. The follow-up passed 452 JVM tests (fourteen recovery/
guard cases), assembly, lint, scoped hooks/docs and read-only review. Normal
recovery behavior was unchanged; the device runs above used the prior guard
APK, while these extra failure paths were verified with injected JVM failures.
The certificate-verified final installed APK is 16,674,698 host-owned bytes,
SHA-256 `75ea69babb12527ea3867224402e9326a34629e1e1ad35d5a2aeb11ceb10bb63`.
Tachiai remains stopped after the update. No commits or pushes were made.

Separate `ABEMA_NATIVE_PAIR=LIVE_RELATIVE` and `REPLAY_RELATIVE` cases keep all
older examples and their per-side earlier/later controls. LIVE_RELATIVE uses
the existing News/RelaxBeats pair, transition policy, aligned unused helper
and prewarm. REPLAY_RELATIVE keeps the existing free sumo/Rocket League pair,
strict manifest checks and original helper lifetime. Both keep the same
five-minute foreground cap, original-web pause/mute Play gate, audio owner
and source/authentication limits. No new provider route, response, identifier,
renewal or source extraction is added.

The new controls say **Advance A vs B** and **Advance B vs A**, five seconds
each. Advance means selecting later footage, not adding delay. A positive
relative request can advance A or move B backward; a negative request reverses
those operations. The provider-independent planner considers A first and uses
the equivalent B movement only if A cannot make the entire requested step.
It evaluates each source's own live/replay window and stable live content
clock, never subtracting unrelated provider timestamps.

Both snapshots must be READY and neither may report an ad. Unknown/stale
windows, unavailable live clocks, arithmetic overflow, or no full-step
candidate refuse before holding either player. Relative actions do not silently
accept partial clamping. The chosen operation is rechecked against fresh
snapshots before dispatch, then goes through the existing joint hold, one
selected seek, held-target check (100 ms tolerance/8 s cap) and joint resume.
Busy relative actions refuse without canceling a pending seek. Candidate
fallback occurs only before dispatch; platform/hold/settlement failure never
automatically tries the other feed afterward. Older per-side controls retain
their explicit clamping behavior for comparison.

The on-screen readback and closed logs identify the chosen A/B movement.
The scalar remains **requested** relative movement, not a measured common-event
offset, frame/audio precision or synchronization lock. Snapshot/hold latency,
live-window movement and decode buffering can still alter actual perceived
alignment. Build and on-device verification results follow separately; these
controls are not yet promoted to settled product UX.

Verification passed 436 JVM tests (seven new planner cases, six mixed-controller
cases and one fixed-mode case), assembly and lint. These cover both signs and
all live/replay combinations, full A preference, equivalent B fallback, partial
clamp rejection, stale/unknown windows, ads, overflow, busy refusal, fresh
snapshot rechecking and no redispatch after a platform failure. Read-only review
and scoped hooks/docs passed; the debug-source secrets scan found no leaks.
The installed APK is 16,673,370 host-owned bytes, SHA-256
`176dde8bf53ce8033f3e0756c060eb78278d9cb16bbde939841f6bf94fca19a0`.
Both successive relative-control builds matched the shared debug certificate.

On the same unlocked Pixel 6 / Android 17 / WebView 153.0.8010.36, with the
user-owned Japan playback connection and uninspected account state,
REPLAY_RELATIVE loaded the original fixed free episode. It initially waited
for the original player behind the visible onboarding overlay. After tapping
its observed Later button, helper READY appeared at 11:36:36.411, native
preparation at 11:36:37.583 and one successful native exchange/keys loaded.
Both native copies were deliberately left READY and paused: A at 0 ms, B at
4,200,000 ms.

At 11:37:27.529, **Advance B vs A** requested relative −5000 ms and chose B
movement +5000 ms. B reached READY at .650 and held 4,205,000 ms while A stayed
at zero. At 11:37:50.759, **Advance A vs B** requested +5000 ms and chose A
movement +5000 ms. A held exactly 5000 ms while B stayed at 4,205,000 ms. The
visible readback confirmed the held target within 100 ms and a zero requested
ledger after the inverse relative requests; the two different programs are
not thereby event-aligned. Joint Play at 11:38:28.721 succeeded, with both
native clocks advancing. The run was deliberately stopped after that check.
No acoustic recheck was requested; earlier mixed-audio confirmation remains
separate evidence. Closed marker output was temporarily retained during this
run so rollover could not erase its initial exchange or chosen movements.

The first LIVE_RELATIVE attempt reached original player/WAITING_TRANSPORT but
never READY before its unchanged two-minute capture deadline at 11:41:01.893.
No native pair or exchange started. A credential-free screenshot showed a
small original food/commercial-looking video behind the onboarding overlay;
its identity/protection was not verified. A second attempt reached READY at
11:44:56.026, about 111 seconds into the valid capture window, but the temporary
runner had already stopped watching. No native exchange started before expiry.
The runner now watches until the existing app deadline/STOPPED marker; this
corrects automation, not provider behavior, and does not extend capture limits.

The next fresh LIVE_RELATIVE run reached READY at 11:46:52.241 and native
preparation at .886. Initial protected manifest acceptance at 11:46:59.769
triggered prewarm and the single exchange; ordinary/prewarm keys-loaded markers
appeared at 11:47:01.579–580, before joint Play at 11:47:06.140. A real
NO_DECLARATIONS refresh appeared at 11:48:19.706 and SAME_PROTECTION returned at
11:49:24.911. Both clocks kept advancing across that interval and return, with
native frames and no second exchange. A later screenshot showed native News
footage. This establishes continued playback across the bounded declaration
transition after native session initialization on this run; it does not prove
which samples were clear, advertisement identity, arbitrary key rotation or
renewal. Closed markers retained the initial exchange and transition sequence.

While running, relative +5000 ms chose A +5000 at 11:49:52.987; A reached READY
at 11:49:53.044 and both resumed at .115. Relative −5000 chose A −5000 at
11:50:18.664; READY at .973 and joint resume at .990 followed. The requested
ledger returned to zero, without claiming common-event alignment. After a
deliberate pause at 11:50:36.034, held +5000 moved A's content clock from
1,791,388,210,262 to 1,791,388,215,262 ms while B stayed at
1,791,388,134,660–661 ms. The visible held-target readback passed within 100 ms.

During the longer pause both points aged outside their live windows. The
inverse request at 11:51:15.188 correctly returned NO_FULL_STEP without moving
either source or changing the ledger. Joint Play resumed buffered clocks, but
A then failed with Media3 code 1002 at 11:51:35.636. This is an out-of-window
resume failure, not a failed relative dispatch or second license exchange. A
fresh paused preparation similarly aged before manual inspection: one +5000
held check passed near A's window start, its later inverse refused, and Twitch
eventually reported code 1002 after resume. These runs do not establish long
pause recovery; an immediate-on-READY held regression follows. The temporary
coordinator performs the checks immediately to avoid inspection/tool-call
latency consuming the finite live window. No automatic recovery or app deadline
extension is introduced.

The immediate-on-READY repeat completed both held live signs successfully.
At 11:55:45.036 relative +5000 selected A +5000; READY followed at .137.
At 11:55:46.897 relative −5000 selected A −5000; READY followed at
11:55:47.629. Both credential-free readbacks reported the held target within
100 ms and the inverse restored the requested ledger to zero. Joint Play at
11:55:48.493 started both; their clocks continued advancing through the
11:56:24 observation. The unchanged original-web pause/mute gate remained.
This separates in-window held adjustment from the separately observed
long-pause window expiry. No acoustic recheck or precise event alignment is
claimed by this regression.

Two further additive cases, LIVE_REPLAY_RELATIVE and REPLAY_LIVE_RELATIVE,
independently select the same fixed News/free-sumo ABEMA source and
RelaxBeats/Rocket League Twitch source. The first name component is always
A/ABEMA; the second is B/Twitch. The ABEMA page binding, strict replay checks
or News transition/prewarm policy follow A alone. Twitch's source resolution,
CDN allowance and fixed replay start follow B alone. Old case names retain
their original matching source types. No arbitrary source, URL, token,
renewal, pause recovery or new helper lifetime input is added. These cases are
for validating mixed-type relative dispatch, not new production source support.
The additive build passed 438 JVM tests, lint and assembly; all older modes'
matching source types and both new independent bindings are covered. Scoped
hooks/docs and the redacted debug-source secrets scan passed. The APK is
16,673,370 host-owned bytes, SHA-256
`792db21c8b9151e47df38b60c844640f190c769b64efe5e3d25b7221c62a0bf0`;
its certificate matched the shared debug key before the data-preserving update.
Phone results follow separately.

The first LIVE_REPLAY_RELATIVE launch reached WAITING_TRANSPORT, then expired
at 12:03:07.777 with no native source resolution or exchange. A fresh retry
dismissed the visible onboarding Later button while capture was still valid;
the original small video rendered, but the same transport gate never reached
READY before STOPPED at 12:05:52.025. Hook-ownership markers remained true until
teardown. No native mixed-type playback claim follows from either run. This
gate waits for the captured original player's approved license-request shape;
current markers do not distinguish absent requests, ordering or a different
visible player. It is not a Twitch replay failure or proven regional rejection.
No helper limit is extended and no expired capture is revived.

REPLAY_LIVE_RELATIVE on the same phone/engine/network condition reached native
ABEMA replay keys-loaded at 12:08:12.893 and both READY by 12:08:17.197 after
dismissing the visible original-page onboarding. Relative +5000 chose A to
5000 ms at 12:08:17.481; READY followed at .587. Relative −5000 chose A back
to zero at 12:08:19.369; READY followed at .982. Both on-screen held-target
readbacks passed within 100 ms and the requested ledger returned to zero.
Joint Play at 12:08:20.977 started both. Snapshots separately identified A as
static/replay and B as dynamic/live, with both clocks advancing through
12:10:03; a credential-free screenshot showed moving sumo footage above the
RelaxBeats artwork. Original-web pause/mute remained the joint Play gate.
The run was deliberately stopped after this check. Mixed audio was not
acoustically rechecked; prior confirmation remains separate evidence.

To distinguish the unresolved News capture wait, five closed metadata bits
are added: requestFilterCalled, requestBeforeConfiguration,
configuredRequestMatched, capturedVideoPlayed and encryptedEventSeen. The
request URL is still read only by the existing configured exact-match check;
no request/body/header/initData content leaves the boundary. Strict player
identity refines the existing optional `_dashjs_player` association marker,
and the retained reference clears on Stop. The encrypted marker is standard
event occurrence on any top-document VIDEO, not proof that the captured player
was encrypted. These monotonic observations do not authorize playback or
replace readiness. Exact native metadata validation is updated in lockstep.
All 60 browser fixtures passed, including preconfiguration noninspection,
unrelated request, exact association and late-event/Stop checks. Read-only
review found no added provider-value reads, readiness/lifetime change or
handshake change. Build/device results follow separately.

The diagnostic build again passed 438 JVM tests, lint and assembly, plus the
60 browser fixtures. The redacted debug-source scan found no leaks. Its APK is
16,673,370 host-owned bytes, SHA-256
`e46bdbf634e757bb0c7dc047be2563675935c44ee88b1ea54e2799418a74265d`;
post-build signing verification matched the shared debug certificate before
the data-preserving phone update.

The next LIVE_REPLAY_RELATIVE launch succeeded without changing the gate or
limits. After early onboarding dismissal, WAITING_TRANSPORT at 12:14:10.106
changed to READY at 12:14:11.108. The new metadata showed callback occurrence
and exact configured match true, preconfiguration false and encrypted-event
presence true; optional captured-video association remained false. That false
association does not refute playback because it depends on the provider's
optional `_dashjs_player` property. One initial native request and prewarm keys
loaded at 12:14:23.436. The run cannot retrospectively diagnose the previous
expired captures, nor establish that the new diagnostic bits caused success.

Both were READY by 12:14:25.714. Held relative +5000 chose A +5000 at
12:14:26.317, then −5000 chose A −5000 at 12:14:27.958. Both readbacks reported
the selected target within 100 ms, the requested ledger returned to zero and
joint Play at 12:14:29.793 resumed both. Snapshots identified A as live/dynamic
and B as replay/static, with advancing clocks; screenshots showed changing
News and Rocket League footage. The original-web pause/mute gate stayed
unchanged. Together with the earlier replay/live run, all four fixed
live/replay type combinations now have bounded relative-control device checks.
This is not measured event synchronization or a production reliability claim.

In that same live/replay run, twelve explicit held **Advance A vs B** requests
at two-second spacing tested a live-edge fallback. All twelve selected a full
step. The first eight advanced A within its current live window. At
12:16:45.124 the ninth instead chose B −5000 to 4,313,461 ms; B reached READY
at .937. The eleventh at 12:16:49.255 likewise chose B −5000 to 4,308,461 ms
and reached READY at .703. The intervening tenth and final twelfth selected A
again as its sliding window advertised another full forward step. Final held
readback reported target within 100 ms and requested +60,000 ms. Thus the
planner can change which side moves as live capacity changes, without partial
clamping or trying to advance beyond that source's advertised window. This
is planner-time fallback, not automatic retry after a failed seek.

After the edge checks, a longer inspection pause again aged A's held point
outside its advertised window. Joint Play at 12:17:57.832 resumed both buffered
clocks, which advanced through 12:18:20 with no failure marker observed before
the user took the phone. Tachiai was force-stopped for that handoff; neither a
five-minute-cap completion nor sustained out-of-window recovery is claimed.

## ABEMA native format comparison — 2026-10-06

The user deferred per-feed proxy/VPN support and enabled system Proton Japan
for this phone experiment. Tachiai adds no routing setting or tunnel. Conditions:
Pixel 6, Android 17, native verified HTTPS (no browser engine or imported browser
session), anonymous ABEMA News linear/live metadata and manifests. Android
reported an active `ch.protonvpn.android` VPN; the Japan selection is user-reported,
not an independently measured exit location or proof of provider support.

Rerunning the unchanged `ABEMA ANONYMOUS` case at 09:28 device time reached
`ABEMA_DASH / DASH_PROTECTION_MARKERS_PRESENT / HTTP 200`. The earlier phone
and computer checks returned 403 with region/exit unverified. The new result
establishes manifest access under the current condition, not that the VPN alone
caused the change, a license entitlement or native playback.

Three appended debug cases retain that original comparison:

- `ABEMA DASH FORMAT` classifies an advertised DASH document for lexical
  common-encryption, Widevine, PlayReady, Clear Key, Marlin or multiple known
  system markers using the [public DASH-IF identifiers](https://dashif.org/identifiers/content_protection/).
  It is not XML/schema validation or DRM configuration; unknown identifiers
  are not displayed, and recognized markers need not describe every track.
- `ABEMA ANONYMOUS HLS` reads only the original News HLS source advertised by
  public metadata, with no synthesized URL or guest authorization.
- `ABEMA HLS VARIANT` additionally inspects at most the first regular
  variant explicitly advertised by an unprotected-looking master. That source
  must pass the same query-free HTTPS exact-CDN `.m3u8` policy. No alternative
  variant/host, separate audio rendition, recursion, segment, key or license is
  requested. `STREAM-INF` alone does not establish whether a variant has video.

HLS outcomes distinguish master/media markers, protection markers and a literal
`abematv-license://` key-reference hint. A master without key tags does not
establish unencrypted child media. Even a media playlist without detected keys
does not prove native decoding, entitlement or sustained playback. Missing or
denied content is not diagnosed as region, authentication or DRM failure.
Bodies and source/key/license references remain transient and absent from
UI/logs/storage. Responses stay bounded at 1 MiB metadata and 256 KiB per
manifest; the existing foreground, connection cancellation, verified TLS and
redirect rejection apply to each request. These cases do not implement an ABEMA
Media3 player or a private license/key protocol.

### Format comparison results

The first APK (`f372d435df0ee7eef411a9cdc1c599e18f36d0e0581fa1a060dcb0fd3de8fe26`)
passed 310 debug and 313 diagnostic unit tests, lint and assembly. Its shared
debug certificate matched the documented identity, and `adb install -r`
preserved app data. The cold native-access launch was used after update because
the initial warm intent brought up the earlier browser comparison instead.
No browser/profile settings were changed.

| Selected phone case | Closed result | What this establishes |
| --- | --- | --- |
| DASH FORMAT, first inspector | DASH_PROTECTION_MARKERS_PRESENT / HTTP 200 | Advertised MPD accepted; explicit Widevine marker not detected by the lexical inspection |
| ANONYMOUS HLS | HLS_MASTER_MARKERS_PRESENT / HTTP 200 | Advertised News HLS master accepted |
| HLS VARIANT | HLS_ABEMA_KEY_MARKERS_PRESENT / HTTP 200 | Selected one-child comparison encountered an ABEMA-specific key reference, not clear-media playback |
| DASH FORMAT, refined inspector | DASH_COMMON_ENCRYPTION_MARKERS_PRESENT / HTTP 200 | Common-encryption scheme detected; no recognized DRM-system identifier detected by this inspection |

The first HLS runs completed at 09:38 and 09:39 device time; the refined DASH
run completed at 09:43. Only native closed status markers and credential-free
menu screenshots were inspected. No raw manifest/source/key identifier,
provider account data, media, license or key was output or saved. The test
fixture and classifiers do not establish which alternate tracks/variants,
sumo streams or replay resources use the same protection scheme.

The refined inspector uses public DASH-IF scheme identifiers and preserves the
older generic DASH outcome. Common encryption is not itself a complete DRM
configuration. Media3 supports standard Widevine through Android MediaDrm, but
requires the corresponding media-item DRM/license configuration; these public
News metadata/manifests have not supplied an established authorized native
license path. See [Media3 DRM](https://developer.android.com/media/media3/exoplayer/drm).
The HLS `abematv-license://` reference is not an ordinary HTTPS key URL to pass
to the existing clear-media host. Do not rewrite it, import an application
secret, mint guest authorization, retrieve keys or replay a provider license
session. A documented/authorized standard DRM integration remains an unresolved
gate, not proof that native playback is technically impossible. Official
[ABEMA engineering](https://developers.cyberagent.co.jp/blog/archives/49162/)
describes its license-proxy entitlement checks, not a public integration API.

Final verification passed 311 debug and 314 diagnostic unit tests (625 total,
zero failures/errors), lint with the same two existing warnings/one hint, and
debug assembly. Fourteen added unit tests cover lexical scheme/protection
classification, exact first-variant rules, one/two-manifest request ceilings,
pre-child foreground refusal, ABEMA failure routing, URI/header isolation,
response bounds and redirect rejection. Android's `org.json` metadata selection
is not covered by these Android-free unit tests; the phone HLS cases exercised
that path. Existing Kotlin test warnings and Gradle deprecation notices remain.

The final host-owned APK is 15,954,626 bytes, SHA-256
`93b8b6f999d5f3dda4a57e3a50a6fb817bf2d61fc13cc9b29b7aa47cd1637005`.
`apksigner verify --print-certs` matched the shared debug certificate before
installation. The refined DASH case passed after the final data-preserving
update/cold launch. The phone was left idle on the comparison menu; no native
ABEMA player was constructed. Earlier examples, provider sessions and saved
Twitch authorization remain untouched by these additions.

## Additive two-native-replay comparison — 2026-10-06

The user approved proceeding from the single-native timing tests to mixed
audio and relative alignment. `TWITCH NATIVE REPLAY PAIR` adds two copies of
the same moving-match replay (`2080217716`) without replacing earlier cases.
Its initial implementation is an experiment, not verified dual playback.

One fresh LOCAL validation/access resolution supplies the transient source to
both hosts. Both initial parsed playlists must still fit the same acceptance
deadline; both share one unchanged two-minute/remaining-retention budget.
The existing exact observed replay-CDN exception, transport restrictions,
encryption refusal and no-capture/no-cache boundaries remain unchanged.
Background, Stop, disposal, expiry or failure of either player tears down both.
No additional account scope, browser session, ABEMA key/license or native media
path is introduced.

A generic Android audio group owns one `AudioFocusRequest`; its two members
opt out of Media3's individual focus management and start prepared/paused.
Play requests focus before starting either member. Any focus loss, including
ducking, pauses both and cancels pending restoration; focus gain does not
automatically resume. The pair's original player-view controls are disabled
so they cannot bypass this owner. Single-player cases retain their automatic
focus and original controls. Independent bounded player-volume sliders start
at 50%, with zero usable for acoustic isolation. This design follows Android's
[focus ownership and request/loss guidance](https://developer.android.com/media/optimize/audio-focus);
one owner alone will not prove both tracks are audible.

The generic replay planner assumes a common content/time origin, explicitly
established here by using the same source. Positive `A−B` means A shows later
footage than B, not that A has more delay. A is top/left and B bottom/right.
Signed ±1/±5-second inputs adjust the sampled offset; Sync requests zero.
The 70-minute button requests both at the known moving-match anchor and zero
offset. Live/dynamic, unknown-clock, unseekable, ad and out-of-range cases
refuse before seeking rather than silently distort the offset.

Transactions hold both, seek to B's sampled position/common anchor and A's
requested relative target, then wait at most eight seconds for both paused
READY clocks within 100 ms. Only a pair that was jointly playing resumes;
manual Pause, focus loss, partial refusal or timeout leaves both paused. No
request renews the session. Requested and measured offsets are separate;
running rebuffer drift, exact frame/audio precision and sustained alignment
remain experimental assumptions. Diagnostics expose fixed A/B markers, closed
events/status and normalized clocks, never the source or credential.

### Pair build and initial device gate

The pinned SDK 37.2 container passed 289 debug and 292 diagnostic unit tests
(581 total, no failures/errors), lint and debug assembly. Nine new pair tests
cover offset signs/anchors/bounds/overflow, unsupported and ad cases,
paused-READY target checks, focus denial/loss, restoration cancellation,
timeout/partial failure and guaranteed cleanup attempts after a throwing member.
Scoped hooks/documentation and redacted source/test Gitleaks checks passed.
Lint has only the two pre-existing warnings and one pre-existing hint.

`apksigner verify --print-certs` matched the shared debug identity. The final
host-owned APK is 15,940,996 bytes, SHA-256
`e885cb2735c6a521b161c7548f0bf05dab6b4c5107af7a186219e99d4d006972`.
An independent read-only review identified a teardown-hardening opportunity;
cleanup now attempts both members and focus even after a member exception,
with a fixed failure marker instead of exception details.

`adb install -r` preserved the Pixel 6's app data. The additive menu and pair
controls rendered in portrait. Initial Prepare returned `EXPIRED / HTTP 0`
before any network/media request: the existing saved LOCAL record had exceeded
its one-hour local cap. The final APK was then installed and the existing
LOCAL SAVE case opened for private renewal. This gate is not provider rejection
or playback evidence. Conditions: Pixel 6, Android 17, native Media3 1.11.1/
HTTPS path; region/VPN exit, exact account and subscription state uninspected,
and no provider/network settings changed. No private activation UI or code was
inspected. Pair playback, acoustic mixing, relative offset restoration, focus
loss, background and timeout remain pending until renewal.

### First native-pair playback and audio result

Private approval completed with TOKEN/VALIDATE 200, omitted grant lifetime,
zero validation lifetime, null scopes and LOCAL SAVED. No private challenge,
account detail or token was inspected. Pair Prepare then returned VALIDATE/
ACCESS 200; both hosts fetched/parsing playlists and media, rendered frames
and reached paused READY. Their reported replay duration was 14,407.370 seconds.

The common 70-minute anchor settled both paused clocks at 4,200.000 seconds.
The +5-second input then settled A at 4,205.000 and B at 4,200.000 seconds.
Play produced PLAYING on both with advancing clocks. Running samples showed
A−B about 4.989–4.992 seconds, and the user confirmed hearing doubled/delayed
commentary from both copies at 50% each. This is a bounded native mixed-audio
pass on this Pixel 6, not a precise acoustic offset measurement.

A running +1-second transaction requested A at 4,246.696 and B at 4,240.704
seconds (target offset 5.992 seconds from the observed prior offset).
Both paused, sought, buffered, rendered and returned READY/PLAYING without
another Play tap. Subsequent observed offsets were approximately
6.013, 6.005 and 6.007 seconds. This is substantially closer clock restoration
than the earlier web experiment, not frame-accurate or guaranteed synchronization.
The unchanged two-minute cap stopped the group with cleanup failure false.
A later −5-second tap happened after teardown and issued no seek; negative
running adjustment, Sync, independent-volume isolation, external focus loss,
background and sustained behavior still require additional device checks.

In a second short run, both copies again prepared and reached the common
70-minute anchor. A running −5-second input requested offset −4.980 seconds,
with targets A=4,197.711 and B=4,202.691. Both held and resumed; separated
running samples measured −5.157 seconds and a public match screenshot showed
different moving replay frames with observed offset −5.158 seconds. The roughly
178 ms target error is material: the 100 ms per-player held tolerance and
resume/buffering behavior are not a promise of precise relative alignment.

Running Sync then targeted both at 4,244.220 seconds and returned both to
PLAYING. Subsequent samples measured offsets +5, −1, −6, −8 and −3 ms while
both clocks advanced. This bounded clock convergence is not acoustic/frame
precision or a sustained lock. Android's own focus history identified one
`NativePlaybackAudioGroup` request per playing pair and abandonment on teardown;
no individual member focus requests were introduced. Home stopped the group
with cleanup failure false; returning via Recents did not add any PLAYING
event before the subsequent build update. Independent-volume isolation,
external competing focus, ads, other sources and sustained behavior remain open.
No public match screenshots were retained in the repository.

## Seven-day LOCAL retention follow-up — 2026-10-06

The user requested longer saved-login retention to avoid repeated private
approval, not longer individual playback tests. The chosen local cap changes
from one hour to seven days; the playback cap stays two minutes. This is a
debug retention policy, not Twitch's expiry or approval of the observed
provider identity. Historical one-hour experiment results below remain accurate.

New authorization can save for at most seven days, shortened by known positive
grant/validation expiry. Existing encrypted version-2 records keep their stored
shorter expiry; changing the cap alone does not retroactively extend them.
The additive `TWITCH SMART TV LOCAL EXTEND` action validates an AVAILABLE LOCAL
grant with the fixed official endpoint, exact selected client/user/scopes and
expiry checks, within 30 seconds and the old remaining retention. Only this
explicit action may rewrite its local deadline: at most seven days from the
fresh validation start, shortened by a positive validation lifetime. Integer
zero permits only the chosen local cap, never a permanent-validity claim.

No device/poll challenge, browser approval, playlist, media request or refresh
token is used by extension. Wrong-profile, missing, expired, malformed,
revoked/rejected, stale, cancelled or background attempts cannot extend a record.
Every ordinary playback/access start still validates freshly and never renews
stored retention. Wall-clock dependence and provider-identity/account risks
remain unchanged. Twitch requires validation on startup and hourly for maintained
OAuth sessions; these foreground tests last at most two minutes and do not
maintain a background session. See [official validation guidance](https://dev.twitch.tv/docs/authentication/validate-tokens/).

“Smart TV” labels the observed OAuth application/client identity, not Tachiai's
APK or native player, an installed Twitch/TV app, a transferred browser session,
or a supported Android-TV integration. Tachiai's own registered identity remains
available in earlier cases; it validated but was rejected by private playback
access. The borrowed provider identity is an unsupported comparison. Twitch's
[registration guidance](https://dev.twitch.tv/docs/authentication/register-app/)
warns against sharing client IDs and possible API-access suspension. Successful
private personal playback does not settle the distributable integration gate.

### Retention verification and installed result

The final pinned build passed 297 debug and 300 diagnostic unit tests (597
total, zero failures/errors), lint with only existing warnings/hint and debug
assembly. Eight additional extension tests cover preservation of older hourly
records on ordinary use, explicit bounded extension/recreation, positive lifetime,
profile/missing/expired/malformed refusal, validation rejection, acceptance expiry,
wall rollback versus initial monotonic retention, stale/Forget/background/cancel
and storage/network failure. Read-only review prompted the initial monotonic
retention guard; rejected attempts leave stored bytes unchanged.

`apksigner verify --print-certs` matched the shared debug identity. The final
host-owned APK is 15,953,861 bytes, SHA-256
`ad7616dca85a225c80151c7fd6313e6e5f8f20395378b14840e428ad259958e7`.
It updated the Pixel 6 with app data preserved. Explicit LOCAL EXTEND then
returned official VALIDATE 200 / SAVED, without DEVICE/TOKEN/browser approval.
After a cold process restart, pair Prepare independently returned VALIDATE/
ACCESS 200 / USED; both hosts fetched media, rendered and reached paused READY.
Stop released them and the app was left on its cases menu. No credential file,
token value or account field was inspected. Actual seven-day survival/revocation
has not elapsed or been tested; this proves today's extension/save/reload path.

## Additive native Twitch playback prototype

After local save and both fresh-validated access checks succeeded, the user
approved fetching playlists/media in two new debug examples on 2026-10-05:
TWITCH NATIVE LIVE (Bob Ross) and TWITCH NATIVE REPLAY (`2080217716`). All earlier
examples remain selectable. Nothing runs until Start; no new grant is requested
by playback, and saved-token retention is not extended. Playback and audible
output were unverified at the initial build; the dated device results below
record subsequent live/replay success. Turbo remains unverified.

The independently written resolver uses the observed Usher v2 live/replay paths
with separately encoded signature/token and `platform=web`, `allow_source=true`,
`allow_audio_only=true`. This is a private protocol observation, not a supported
Helix playback contract or copied client source. References: pinned Xtra
[live resolver](https://github.com/crackededed/Xtra/blob/a3cbb0325f3330573a6738f7d36a2c5b785f4d1c/app/src/main/java/com/github/andreyasadchy/xtra/repository/PlayerRepository.kt#L95-L117)
and [replay resolver](https://github.com/crackededed/Xtra/blob/a3cbb0325f3330573a6738f7d36a2c5b785f4d1c/app/src/main/java/com/github/andreyasadchy/xtra/repository/PlayerRepository.kt#L617-L628).
There are no ad-suppression, proxy, integrity or low-latency special parameters.
Playlists and advertisements are not edited or filtered.

Risk review: Twitch's official [registration guidance](https://dev.twitch.tv/docs/authentication/register-app)
warns that application client IDs must not be shared and API access can be
suspended. Successful validation/access does not establish permission to reuse
its Smart TV identity, account safety or Turbo benefits. Current legal agreement
pages retrieved as footer-only content; this is not a completed current legal
review or clearance. The existing native-integration gate remains unresolved;
this private personal prototype tests an accepted unmet playback/alignment need,
not a selected distributable architecture. Authentication and private contracts
may cease to work; account/access consequences are not known to be limited to
this local test. No ABEMA license or private native media path is added.

The saved LOCAL profile and official validation remain unchanged. The access
query and initial parsed playlist must fit the existing at-most-30-second
acceptance window. A separate media-session budget begins after access resolution
and is at most two minutes, shortened by remaining saved local retention. It
does not reinterpret that budget as OAuth validity. Stop, leaving the case,
backgrounding, disposal, retention expiry or a superseded lease release the
player and disconnect active requests; resume does not automatically restart.
Socket timeouts and checkpoint budgets are bounded, not exact hard wall-clock
deadlines for every blocked I/O operation.

The generic platform host uses [Media3 1.11.1](https://developer.android.com/jetpack/androidx/releases/media3)
and [HLS](https://developer.android.com/media/media3/exoplayer/hls), with a
provider-supplied URI policy. It prefers 720p or lower where available, starts at 50% volume
with ordinary Android audio focus, and provides standard player controls.
This is one stream only, not new mixing/alignment UX. No media cache, download,
background service, DRM configuration, license request or encryption-key fetch.
Encrypted/session-key playlists fail before parsing; the DRM-classified
datasource independently refuses to open, even though Media3 constructs it for
unencrypted streams too. A failed seek/decoder/format remains a test result.

Media HTTPS accepts only experimental `ttvnw.net`/`twitchcdn.net` host families,
no userinfo/fragments/nonstandard ports. All redirects and unknown destinations,
including multi-tenant CloudFront hosts, fail closed. This incomplete policy may
reject legitimate replay delivery; it is not a verified CDN contract. Every
request is checked, with 512 KiB manifest and 32 MiB media-response bounds,
5-second connect/10-second read timeouts and a 30-second per-response checkpoint
budget. Media3 may retry inside the unchanged session budget. OAuth/client
headers stay on the fixed access endpoint; media gets no authorization headers,
imported cookies or browser profile. A process-wide cookie handler prevents the
media request rather than silently importing its session.

Signed sources are transient, redacted wrapper objects, never UI, intent,
saved state, diagnostics or disk. This is reference disposal, not secure memory
erasure. Media3 logging is disabled before construction and remains off for the
process to avoid late signed-URI cause-chain logging. Native diagnostics contain
only source-kind/endpoint/event enums and numeric status/error codes. Manifest
bytes, successful parsing, media bytes, first video frame and playing state are
separate observations; none establishes audible output or lack of ads. A user
audio confirmation is required. See the dated device results below.

### Computer verification; phone pending — 2026-10-05

The pinned Docker build passed 265 debug and 268 diagnostic unit tests, debug
lint (only the two pre-existing warnings and one hint) and debug assembly.
Twenty-one new tests cover URI encoding/resource limits, the redacted source,
media host policy, session/retention/stop budgets, lexical encryption guards,
and fake-HTTPS transport behavior: no credential headers/cookie inheritance,
redirect and key refusal, declared/streamed bounds, ranges, setup cancellation,
post-read deadline checks and exception redaction. These JVM tests do not prove
the Android JSON/parser/decoder behavior against actual provider responses;
DataSpec-specific header/body rejection is enforced but not directly exercised
by the Android-free transport test seam.

`apksigner verify --print-certs` matched the documented shared debug identity.
The host-owned APK is 15,874,129 bytes, SHA-256
`0a3c0a5b24bee18d1c579174a069500016c4718d3ab0456ca3e44f66db12e5bc`.
Scoped pre-commit/documentation checks and a redacted source/test secrets sweep
passed. Review against the task-start snapshots and an independent read-only
review found no blocker; changes are the two additive cases, their provider
resolver/platform media host, aligned Media3 dependencies, tests and these
experiment notes. No generated repository files were added or prior examples
removed. The phone was handed back to the user during implementation; this APK
has not been installed or tested on-device. Live/replay playback, audible output,
stop/background/restart and timeout release are the next checks.

### Initial phone attempt — 2026-10-06

`adb install -r` updated the unlocked Pixel 6 without clearing app data. The
new native menu and LIVE case rendered. The explicit live Start returned
`authorization=EXPIRED / HTTP 0`: the overnight saved LOCAL record had exceeded
its one-hour local retention, so no validation, access, playlist or media request
was made. This is the local retention policy, not evidence of Twitch revocation
or a playback failure. The existing LOCAL SAVE case was opened for fresh private
user approval; playback remains pending.

Conditions: Pixel 6, Android 17; native diagnostic UI/HTTPS path rather than a
browser player. Current network region/exit and account/subscription state were
not inspected, and no VPN/app setting or stored authorization was cleared. Only
credential-free menu/READY views and closed native logs were inspected.

### Renewed grant and first playlist attempts — 2026-10-06

Private Brave approval followed by return to Tachiai produced TOKEN 200,
omitted grant expiry/scope, official VALIDATE 200 with zero expiry/null scopes,
and LOCAL storage SAVED. No private approval page or code was inspected.
Both subsequent native cases reused that saved grant and separately passed fresh
official validation and private access HTTP 200, without another approval:

| Case | Media observations | Result |
| --- | --- | --- |
| Bob Ross live | First manifest HTTP 404; no manifest/media bytes accepted | Player error 2000; released/stopped |
| Replay `2080217716` | Manifest bytes HTTP 200; playlist parsed; a subsequent destination failed host policy | No media bytes/first frame; player error 2000; released/stopped |

Live 404 is not a login rejection and does not establish a wrong endpoint or
offline channel. A pinned primary Streamlink
[fixture](https://github.com/streamlink/streamlink/blob/a8a66ecc83fc4665423c771e6d976bce4e4dcf65/tests/plugins/test_twitch.py#L1063-L1108)
distinguishes a reported `transcode_does_not_exist` JSON 404 from generic 404.
The independently written follow-up reads at most 4 KiB of a manifest-404
body and emits only that exact reported category or UNCLASSIFIED, never raw
body/error text. Redirects, other statuses, media errors and oversize bodies
do not reach this classifier. No endpoint/header/ad policy is changed.

Replay has not failed decoding or authentication: the initial provider playlist
was accepted, and our destination policy stopped before media. A follow-up
diagnostic can reveal only a syntactically bounded public CloudFront
distribution hostname, not arbitrary hosts, URLs, queries or paths. Unknown
destinations remain blocked; there is no broad CloudFront allow rule. Public
channel/replay resource fields permit an explicitly selected availability
comparison without replacing defaults or earlier examples; resource values are
validated, transient and not logged. Actual video/audio, CDN authorization,
stop/background/restart/timeout playback behavior and Turbo remain pending.

Conditions: same Pixel 6/Android 17, native Media3 1.11.1 and HTTPS, privately
renewed LOCAL provider profile with exact validation. Account identity,
subscription state and network region/exit were not inspected; no VPN/provider
app setting or browser data was changed. Screenshots were confined to native
menus/player status and temporary storage. No media, signed source or credential
was persisted in the repository.

### Bounded diagnostics and native live success — 2026-10-06

The diagnostic update passed 270 debug/273 diagnostic tests, debug lint with
only the existing warnings/hint, assembly, shared-certificate verification,
scoped hooks/docs and a redacted source/test secrets sweep. Its APK was
15,878,479 bytes, SHA-256
`c2cf699f3b0190fbbb2ec31fea17f97c4e0310bd6c54e1a562ee5b8aac82a018`.
Installation preserved the newly saved grant across the app/process update.

The replay repeated VALIDATE/ACCESS 200, accepted/parsing the initial manifest,
then reported the exact blocked public distribution
`dgeft87wbj63p.cloudfront.net`, without any URL/path/query output. Bob Ross
repeated HTTP 404 and the bounded JSON classification was UNCLASSIFIED; no
specific server cause was established. Changing only the transient public live
resource to `relaxbeats` passed fresh validation/access, manifest bytes 200,
playlist parsing and media bytes 200, then VIDEO_FRAME, READY and PLAYING.
The native surface visibly rendered that channel's video and the user confirmed
hearing its music at 50% player volume. This demonstrates single-stream native
live video/audio on this Pixel 6. The two-minute cap subsequently emitted
LIMIT_REACHED then STOPPED and cleared the player. It does not establish Bob Ross's availability,
Turbo/no-ads, replay playback, live seeking/buffer retention, dual mixing,
cross-device support or provider permission.

An additional TWITCH NATIVE REPLAY OBSERVED CDN case keeps the strict replay
case intact and permits exactly the above distribution advertised by the
accepted replay playlist. It does not allow other CloudFront destinations,
redirects or any new headers. All session, lifecycle, cookie, URI-shape and
encryption/key protections remain unchanged. This host observation is not a
stable Twitch CDN contract; a future legitimate distribution may still fail
closed. Conditions are unchanged: Pixel 6/Android 17, native Media3 1.11.1,
LOCAL provider grant revalidated per start, unknown/uninspected region and
subscription details; no account/credential/source or media retention.

### Exact-observed-CDN native replay success — 2026-10-06

The additional comparison passed 271 debug/274 diagnostic unit tests, debug
lint with only the existing warnings/hint, assembly and shared-certificate
verification. The host-owned debug APK is 15,879,893 bytes, SHA-256
`38b9626ee86107274ea1896627950473d76a2834e7f0fba8836aeabbe67c6f11`.
Installation retained the saved grant without another approval. The exact-host
opt-in applies only to this replay case; the strict cases remain unchanged.

Replay `2080217716` passed fresh VALIDATE/ACCESS 200, then manifest bytes 200,
playlist parsing, media bytes 200, VIDEO_FRAME, READY and PLAYING. The native
surface rendered the Rocket League RLCS Major 1 introduction countdown. The
user confirmed audible replay audio at 50% player volume. The two-minute cap
then emitted LIMIT_REACHED and STOPPED and cleared the surface. An explicit
restart reused the saved grant, revalidated and reached video/PLAYING again;
the Stop button produced the stopped status and cleared the surface.
Backgrounding an active replay with Home and returning through Android's
recent-apps switcher retained that stopped status and empty surface, without an
automatic restart. An ordinary explicit launch first reset the debug route to
the older browser example, so that attempt was not counted as a clean resume
comparison. Leaving the replay via Back returned to the cases menu with no new
playback events afterward. Cancellation during an outstanding network request
was not established: that last start reached PLAYING before Back was processed.

Final scoped hooks and documentation build passed, the redacted full source/test
Gitleaks sweep found no leaks, and diff inspection found no generated-file noise
or removed examples. Temporary native-screen screenshots were not retained in
the repository.

These are single-stream native live and recorded video/audio observations on
the same Pixel 6/Android 17 with Media3 1.11.1, not proof of seeking/alignment,
dual mixing, retained live buffering, sustained operation or Turbo benefits.
Region/exit and account/subscription details remain uninspected. No browser
session, signed URL, credentials or media were retained in the repository.

## Direction and boundaries

### Additive native timing cases — prepared 2026-10-06

After single-stream live/replay playback and audible output succeeded, the user
approved testing native forward/backward seeking and live retention/catch-up.
TWITCH NATIVE LIVE TIMING (`relaxbeats`) and TWITCH NATIVE REPLAY TIMING
(`2080217716`) are additional examples; all prior cases remain intact. The
replay timing case explicitly selects the already observed exact CDN exception.
Neither case changes authorization, source requests, ads, redirects, keys,
buffer configuration or the two-minute foreground/remaining-retention budget.
Pause and seek do not extend that budget. No ABEMA native playback is added.

The provider-independent platform seam reports normalized numeric positions,
duration/window length, buffered endpoint, live offset and window/default
positions where available, plus closed state/capability booleans. Unknown
clocks remain unavailable, not zero. Back/Forward request minus/plus five
seconds, bounded to the currently advertised window; a clamped request is
explicit. Pause/Play preserve explicit user control. Replay's 70-minute anchor
selects previously observed match footage. Catch up requests Media3's default
live position, not zero latency or a future frame. Command acceptance, the
discontinuity observation and delayed readback are distinct evidence.

[Media3 live streaming](https://developer.android.com/media/media3/exoplayer/live-streaming)
documents moving-window positions, live offsets and seek-to-default behavior.
A live position alone can decrease when the window advances. The probe also
reports window-origin plus position when both are known, without claiming that
this media timestamp is a verified broadcaster/content clock. Its ordinary
live-speed behavior remains unchanged. Local back-buffer retention is not
configured: this first comparison measures the existing host and the provider's
advertised window, not an invented DVR archive. Paused media may age out and
fail; no automatic restart, reload or authorization fallback is introduced.

Pure tests cover directions, bounds/no-op, unknown clocks, unsupported/ad
refusal, overflow, live-default versus replay, sliding-window clock arithmetic
and unchanged session expiry. Dated build and device outcomes follow.

#### Initial timing device comparison and probe correction

The first timing APK (`77f3a6371bad3f675895890bd51d9b2e94f99156cb2c8c33ff7f0418966ae3ad`,
15,900,188 bytes) passed 279 debug/282 diagnostic tests, lint with the existing
warnings/hint, assembly and shared-certificate verification. It installed
without clearing data on the Pixel 6/Android 17; both cases passed fresh
validation/access with the existing LOCAL grant. Native Media3 1.11.1 and HTTPS
were used, not a browser player. Region/exit and account/subscription details
were not inspected; no VPN or provider app setting was changed.

The replay's duration was 14,407.370 seconds. Pausing held 25.258 seconds;
the explicit 70-minute anchor settled at 4,200.000 seconds, state READY and
playWhenReady false. Back 5 settled at 4,195.000; Forward 5 returned to
4,200.000. Temporary native screenshots showed match-clock/frame differences:
4:25 at the anchor, 4:30 backward, then the original 4:25 frame forward. Play
then produced PLAYING and an advancing 4,201.382-second readback. This is
isolated replay clock/frame evidence, not measured two-feed/acoustic alignment.
The two-minute limit subsequently released/stopped the player.

RelaxBeats live advertised a dynamic, seekable 30-second window with a
12-second default position and a known epoch window origin. The initial
running live offset was approximately 24.5 seconds. Pause held the derived
content time at 1,791,288,191,498 ms while window-relative position decreased
as the window advanced. The diagnostic converted negative positions to unknown;
Backward and Catch up then returned UNAVAILABLE without sending a seek. This
was an identified local normalization/planner limitation, not a Twitch command
rejection. The live session hit its unchanged two-minute cap while paused.

The follow-up retains signed position/live-offset observations, computes the
derived content clock with checked signed addition, and permits explicit
default-live recovery without requiring the old position to remain inside the
window. Relative moves must not reverse direction merely because a stale
position is bounded; such a move is refused as outside the current window.
Regression tests cover these cases. The corrected live results follow; no
retained-live or catch-up success was inferred merely from the correction.

#### Corrected native live timing results — 2026-10-06

The final corrected APK passed 280 debug/283 diagnostic unit tests, debug lint
with the existing warnings/hint, assembly and shared-certificate verification.
The host-owned APK is 15,900,671 bytes, SHA-256
`b8d5c9bba8204524c081cf9781d58f4960967de1117ee1c299199ee07b4d25eb`.
It updated the same Pixel 6 without clearing data or requesting another grant.
Both validation and access returned 200. Conditions remained Android 17,
Media3 1.11.1, native HTTPS, uninspected region/subscription details, no VPN or
provider settings changed, and no buffer/speed/source-policy changes.

RelaxBeats again advertised a dynamic, seekable 30-second window with a
12-second default position. Both running seek actions produced a SEEK
discontinuity, buffering, a new frame, READY and PLAYING:

| Action | Before position in that window | Requested target | Discontinuity position | Running offset afterward |
| --- | ---: | ---: | ---: | ---: |
| Backward 5 s | 14.554 s | 9.554 s | 9.554 s | 30.607–30.615 s, versus 25.174 s before |
| Forward 5 s | 8.727 s | 13.736 s | 13.736 s | 25.862 s, versus 30.615 s before |

The forward target differs from the separately logged before-sample plus five
seconds by 9 ms because the player advanced between sampling and planning.
Derived content time changed from 1,791,288,662,303 to 1,791,288,657,303 ms at
the backward discontinuity, and from 1,791,288,674,476 to 1,791,288,679,485 ms
at the forward discontinuity. These are actual media-clock direction changes,
not just accepted requests; rebuffer delay means the settled running offset
does not equal exactly five seconds. The fixture showed music artwork, not a
moving-event content timestamp. No acoustic/frame-accurate synchronization or
new human audio confirmation was measured in this timing run.

Pause/resume retained additional delay even after the point left the advertised
window. Pause's derived content clock held at approximately
1,791,288,686,839–840 ms while window-relative position became negative and
reported live offset increased from 25.859 to 63.396 seconds. On Play, the
clock advanced to 1,791,288,688,373 ms with PLAYING/READY and offset 63.367
seconds. Separated later samples continued advancing with offset about
63.36–63.37 seconds. Thus this bounded hold retained about 37.5 seconds of
additional delay; it is not a measured maximum or a guarantee that more old
media remains fetchable/seekable. The 30-second advertised seek window is not
the same as the lifetime of media already held by the native player.

Explicit Catch up then recovered from position −23.602 seconds to the default
12-second position. The derived clock advanced from 1,791,288,708,147 to
1,791,288,743,749 ms; after buffering, READY/PLAYING returned with live offset
28.186 seconds. Further running samples stayed near 28.19 seconds. This is
provider-default recovery, not zero latency. A relative backward request from
an already expired point still cannot invent older history; direction-reversing
clamps are refused. Arbitrary small seeks within old buffered media outside the
advertised window, eviction, network interruption, ads and source transitions
remain untested.

The original two-minute cap emitted LIMIT_REACHED and STOPPED after all actions;
pause, seek and catch-up did not renew it. The app was left stopped on its cases
menu. Final scoped hooks/docs and redacted source/test Gitleaks checks passed;
review against the timing-start snapshots found no removed examples or generated
repository-file noise. No phone screenshots, media, credentials or signed
sources were retained in the repository.

On 2026-10-05 the user approved investigating native playback for Twitch and
ABEMA. Provider players are useful when they simplify integration, not a
product requirement. Useful retained live delay and authenticated Twitch
playback remain unmet by the tested browser approach. The user asked to retain
prior experiments: browser, timing, layout, authentication and OAuth-only cases
remain available. This is an additional access experiment, not a replacement
playback architecture.

The application-fit investigation separates provider access, authorized
media/DRM configuration, then native player control and mixing. Media3 supports
standard Widevine via Android MediaDrm in suitable DASH and fMP4 HLS formats
([Android documentation](https://developer.android.com/media/media3/exoplayer/drm)).
That documented technical fit does not supply ABEMA authorization or licenses.
The initial access-only stage added no native player. The later, separately
approved Twitch playback prototype below adds Media3 without DRM/key handling.

## Other clients: architectural evidence, not permission

- Xtra uses native Media3 and private playback authorization/playlist
  interfaces. Its Helix and GraphQL client/token configurations are distinct;
  its Android-TV device flow does not establish that Tachiai's own public OAuth
  client can use that interface. See its pinned
  [player repository](https://github.com/crackededed/Xtra/blob/a3cbb0325f3330573a6738f7d36a2c5b785f4d1c/app/src/main/java/com/github/andreyasadchy/xtra/repository/PlayerRepository.kt)
  and [authentication helper](https://github.com/crackededed/Xtra/blob/a3cbb0325f3330573a6738f7d36a2c5b785f4d1c/app/src/main/java/com/github/andreyasadchy/xtra/util/TwitchApiHelper.kt).
- Twire uses native Media3/HLS, but its private playback request uses a
  separate client identity without its user OAuth token. It does not establish
  authenticated Turbo playback for Tachiai. See its pinned
  [stream URL task](https://github.com/twireapp/Twire/blob/d9e8f39489e8c9099ec7d92adf22ef294be3107b/app/src/main/java/com/perflyst/twire/tasks/GetStreamURL.kt).
- Streamlink's ABEMA route uses an embedded application secret, guest-token
  minting and proprietary HLS key processing. Those techniques are outside
  this no-key-extraction/no-DRM-workaround experiment.
  [Public implementation](https://github.com/streamlink/streamlink/blob/master/src/streamlink/plugins/abematv.py).
- ABEMA's own engineering description discusses entitlement checks in its
  license proxy, not a public playback integration contract.
  [ABEMA delivery system](https://developers.cyberagent.co.jp/blog/archives/49162/).

These are source observations, not tests of those applications on this phone.
No GPL code, embedded application secret, integrity identity, ad-suppression
path or proxy has been incorporated. These initial cases did not use a borrowed
Twitch client; the separately approved later identity experiment is described below.

## Computer-side access checks

Conditions: development computer, anonymous HTTPS, network region unknown,
no browser engine, account/session cookies or OAuth token. These checks do not
use the phone's Japan playback connection.

| Case | Observed result | What remains unknown |
| --- | --- | --- |
| Twitch Bob Ross live, Tachiai public client, anonymous GraphQL access | HTTP 400; closed classification CLIENT_REJECTED | Own-client authenticated phone access; replay access; media authorization; Turbo |
| ABEMA anonymous channel metadata | HTTP 200; News advertises HLS and DASH; no exact sumo channel found | Phone access and authorized playback |
| Exactly the advertised News DASH document | HTTP 403 | Rejection cause; format/protection; authorized license path |

Raw bodies, authorization fields and source URLs were neither displayed nor
saved. No segments, keys or licenses were fetched. HTTP 403 does not
distinguish DRM from regional or authentication policy.

## Additive phone cases

Launch the existing debug app with
`net.fstab.tachiai.extra.NATIVE_ACCESS_PROBE=true`. Existing launch extras
remain available, including `TWITCH_DEVICE_AUTH` for validation only. The new
route has five separately selected cases:

1. Anonymous ABEMA News metadata and exactly its advertised DASH document.
2. Anonymous Twitch Bob Ross live access, using Tachiai's client.
3. Anonymous recorded access for the existing replay `2080217716`; its current
   availability is unverified.
4. Fresh own-client OAuth followed by one Bob Ross live access check.
5. Fresh own-client OAuth followed by one recorded access check for that replay.

Nothing runs automatically on launch. OAuth cases require new private user
authorization through the existing external-browser device flow. They request
no additional permissions and lock the client to Tachiai's public ID. After
exact-client/user/lifetime/scope validation, the worker uses the token once
for the selected query, then drops references. The OAuth-only case has no
handoff. Access failure is separate from OAuth success; tokens, signature/value,
browser cookies, manifest URLs and refresh state are never persisted or
exposed to UI/logs. Reference disposal is not secure memory erasure.

The transport accepts fixed API endpoints and, for ABEMA, only an advertised
query-free HTTPS MPD on `linear-abematv.akamaized.net`. It does not synthesize
alternative sources or follow redirects. Response bounds are 1 MiB metadata,
256 KiB MPD and 64 KiB Twitch JSON, with socket timeouts and a cumulative
checkpoint budget (not an exact wall-clock limit for blocked I/O).
Cancellation closes the active connection. Anonymous cases cancel on
backgrounding; OAuth retains foreground-return behavior. The optional access
call is foreground/expiry gated without retry. TLS verification remains intact.

ABEMA reports lexical DASH/protection markers only, not schema validity,
Widevine configuration or playability. Twitch AUTHORIZATION_FIELDS_PRESENT
means only bounded signature/value fields were present without an error;
they are discarded without playlist resolution or media requests. Neither
outcome proves entitlement, provider permission or Turbo playback.

### Build and device results — 2026-10-05

The pinned disposable Android tools container passed 160 debug unit tests,
lint (two existing warnings and one hint), and debug assembly. `apksigner`
matched the documented shared debug certificate. The host-owned APK is
12,347,398 bytes, SHA-256
`5a90fbbea9792e0a91a7d274100a6f7415bffaf2f6936383056da4b87a8ea6d2`.
Scoped pre-commit/documentation checks and a redacted source/test Gitleaks
sweep passed. Snapshot review and independent review preserved prior work;
no dependencies, permissions or generated repository files were added.

`adb install -r` succeeded without clearing app data. CREATE / OTHER followed
by NEW_INTENT / NATIVE_ACCESS confirmed the additive route. The native menu
was visible and the three anonymous controls produced these closed markers:

| Phone case | Result |
| --- | --- |
| ABEMA News anonymous | Metadata parsing reached the advertised MPD; ABEMA_DASH / HTTP_REJECTED / HTTP 403 |
| Twitch Bob Ross live anonymous | TWITCH_ACCESS / HTTP_REJECTED / HTTP 400 |
| Twitch replay `2080217716` anonymous | TWITCH_ACCESS / HTTP_REJECTED / HTTP 400 |

Conditions: Pixel 6, Android 17, native HTTPS with no browser engine or account
token. The phone's current network region/exit acceptance was not independently
verified; no VPN settings were changed. Existing provider browser sessions are
not used by these native anonymous requests. Phone HTTP 400 was not narrowed
to the computer's CLIENT_REJECTED category; do not assume identical causes.
Only closed native log markers and the credential-free menu were inspected.
No media, license or key request occurred, and this is not native playback
evidence. Both fresh OAuth access cases remain pending private user approval.

### Fresh own-client live access — subsequent phone result

Two private approvals on the same Pixel 6 / Android 17 and APK subsequently
recorded TOKEN 200, omitted grant scope, VALIDATE 200, validation scopes NULL,
then TWITCH_LIVE_OAUTH / HTTP_REJECTED / HTTP 401 and OAuth SUCCEEDED. The
401 came from private playback access, not Twitch's official token validator.
The null field was the accepted experimental no-scopes convention, not a
separate authentication failure. No raw response or token was inspected.
This establishes own-client OAuth validation followed by rejected private live
access, not native playback or a diagnosed reason for that rejection. Replay
authenticated access remains untested. These fresh cases discarded tokens.

### Saved-token cases — user-authorized extension

The user then requested retaining the validated token instead of repeatedly
authorizing. Three additional cases preserve all five original access cases:

- TWITCH AUTHORIZE SAVE: fresh own-client device grant and validation, then
  encrypted local storage. Storage outcome is separate from OAuth status.
- TWITCH LIVE SAVED and TWITCH REPLAY SAVED: explicitly reuse that stored token,
  requiring fresh official validation before each selected access request.

The slot uses AndroidKeyStore AES-256/GCM, a new provider-generated IV for each
write, authenticated application/slot/version binding, and bounded atomic
replacement in `noBackupFilesDir`. There is no plaintext fallback, token display,
refresh-token storage, browser cookie transfer or credential-file inspection.
Existing backup exclusion settings remain unchanged. Hardware key backing is
not assumed or attested. Reference/scratch-buffer disposal is not secure memory
erasure, and a debuggable/compromised app process is still a trusted boundary.

The current grant's monotonic remaining lifetime becomes an encrypted wall-clock
expiry so storage can survive process loss/reboot. Local expiry (with a ten-second
margin), backward-clock detection, schema/client checks and decryption failure
block reuse. Every use then validates exact Tachiai client, nonempty user,
positive expiry and the existing empty/null scope convention. Only successful
validation supplies a new request-local monotonic deadline. No device/token
request, browser approval or refresh is made by saved-use actions. No background
session or hourly polling is created; these remain explicit one-request probes,
not a continuously authenticated product session. Twitch requires startup and
hourly validation for maintained sessions; any future persistent active session
must implement that lifecycle ([official validation guidance](https://dev.twitch.tv/docs/authentication/validate-tokens/)).

A process-wide lock/revision protects key/file operations and prevents pending
old save callbacks from resurrecting a locally forgotten token. Cancellation is
checked before save; a write already committing can finish and remains saved.
Cached requests cancel on background/disposal and gate access on foreground,
expiry and the current storage revision. Failed official validation performs no
access request. A private access 401 does not invalidate an officially validated
token; network failures likewise do not silently replace it with a new grant.
Expired, invalid or unreadable stored entries remain blocked until explicit
reconnection/replacement or Forget; no automatic cleanup of experiments is added.

Forget overwrites only the encrypted token slot with empty authenticated data.
It does not delete earlier examples, clear browser sessions or revoke the grant
at Twitch. A failed overwrite is reported, not presented as successful logout.
No token/key/ciphertext is read through ADB, exported, or exposed to diagnostic
output. The user must authorize once in the save case because prior workers
already discarded their tokens; they cannot be recovered from logs.

The corrected Docker build passed all 173 debug unit tests, lint with the same
two existing warnings/one hint, and debug assembly. `apksigner` matched the
shared debug identity. The host-owned APK is 12,347,457 bytes with SHA-256
`5cdb229783d354e1a8f094a3c689f51b8134ca823ebc1fde860cea00a160fe5c`.
Scoped pre-commit/documentation checks and the redacted source/test Gitleaks
sweep passed. Independent review caught an unsupported `AtomicFile.exists`
call before installation; it was corrected to bounded `openRead` with backup
recovery and narrowly classified absence. No permissions/dependencies or
generated repository files were added; earlier cases remain intact.

Installation with `adb install -r` succeeded without clearing data. The Pixel 6
recorded NEW_INTENT / NATIVE_ACCESS, displayed all eight case buttons, and the
explicit saved-live action returned NO_TOKEN / validation HTTP 0 without any
validation/access request or new authorization. Only credential-free native
UI and closed markers were inspected; no credential file or key was inspected.
The save screen is prepared for one new private approval. Actual AndroidKeyStore
encryption/decryption, retained-token live/replay reuse after recreation and
local Forget remain pending that approval; JVM round trips are not device
persistence evidence.

### Saved-token reuse and process persistence — 2026-10-05

The next private save approval on the same Pixel 6 / Android 17 and APK
`5cdb229783d354e1a8f094a3c689f51b8134ca823ebc1fde860cea00a160fe5c`
recorded TOKEN 200, omitted grant scope, VALIDATE 200, NULL validation scopes,
storage SAVED and OAuth SUCCEEDED. The existing save path therefore completed
AndroidKeyStore encryption and the atomic encrypted-file write; the token,
key and ciphertext were not inspected or exported.

The explicit saved-live and saved-replay actions then each recorded official
VALIDATE 200, private TWITCH_ACCESS / HTTP_REJECTED / HTTP 401, and saved-use
USED / validation HTTP 200. USED means the validated token reached the bounded
access request, not successful playback. Neither action requested a new device
grant or browser approval. This establishes decryption/reuse while the private
playback authorization gate remains rejected for both source kinds.

After both requests completed, only Tachiai was stopped and cold-started into
the native menu. The new process recorded CREATE / NATIVE_ACCESS and the
saved-live action again produced VALIDATE 200, private access 401 and USED.
This establishes retained-token decryption and revalidation across a real
process restart on this phone. It does not establish reboot survival, long-term
expiry/revocation behavior, hardware key backing or provider permission.

Conditions: native HTTPS, no browser engine for reuse, user-authorized own
Tachiai client, Bob Ross live and replay `2080217716`. Current network region
and exit acceptance were not independently verified. No VPN/provider-app
settings, browser sessions or app data were cleared. Only fixed native logs
and the credential-free case menu were inspected; local Forget was deliberately
not exercised, so the saved credential remains available for further tests.
The next proposed diagnostic is bounded private-error classification, without
raw bodies/headers/token output, to narrow the access rejection separately from
the successfully validated OAuth grant. No native media player is established.

### Additive error cases and request-contract comparison

TWITCH LIVE ERRORS and TWITCH REPLAY ERRORS reuse the same encrypted credential
and official validation gate, without a new device approval. The eight earlier
cases remain available and unchanged. Nothing runs automatically. The new
cases additionally read HTTP 401/403 JSON at the fixed private endpoint, with
a smaller 16 KiB response bound, no redirects, at most sixteen GraphQL errors
and bounded message/code strings. Raw bodies, authorization fields, headers
and arbitrary JSON keys never reach UI/logs or persisted diagnostics.

The classifier emits closed body-shape and exact-vocabulary category enums.
Recognized client/token/authentication/permission/integrity/query wording is a
provider-reported indication, not a verified cause; HTTP status alone does not
select one. Unknown wording remains UNCLASSIFIED, malformed fields are explicit,
and multiple indications remain visible without their text. Success-shaped
responses are not native playback evidence. These cases do not change request
headers/query, fetch playlists/media, replace the token or introduce retries.
Normalized-field JVM tests alone are not production-parser evidence; the
following phone runs exercise the actual Android JSON parser for observed
responses, not every malformed-response fixture.

The pinned Xtra source comparison corroborates live `String!`, replay `ID!`,
`platform=web`, `playerType=site`, selected signature/value fields and the GQL
`OAuth` authorization prefix (Helix uses `Bearer`). Its fallback queries contain
no `playerBackend`. See the pinned
[live query](https://github.com/crackededed/Xtra/blob/a3cbb0325f3330573a6738f7d36a2c5b785f4d1c/app/src/main/graphql/StreamPlaybackAccessToken.graphql),
[replay query](https://github.com/crackededed/Xtra/blob/a3cbb0325f3330573a6738f7d36a2c5b785f4d1c/app/src/main/graphql/VideoPlaybackAccessToken.graphql)
and [header helper](https://github.com/crackededed/Xtra/blob/a3cbb0325f3330573a6738f7d36a2c5b785f4d1c/app/src/main/java/com/github/andreyasadchy/xtra/util/TwitchApiHelper.kt).

Differences remain: Xtra first attempts a persisted query; its fallback omits
`operationName` and names the query `null`, and its non-integrity access path
adds a random `X-Device-Id`. The source does not establish these differences
as requirements or reasons for Tachiai's rejection. No identity/header or
integrity behavior is copied. Its distinct client/token configurations still
do not establish own-client eligibility. See its
[request serialization](https://github.com/crackededed/Xtra/blob/a3cbb0325f3330573a6738f7d36a2c5b785f4d1c/app/src/main/java/com/github/andreyasadchy/xtra/repository/GraphQLRepository.kt)
and [player setup](https://github.com/crackededed/Xtra/blob/a3cbb0325f3330573a6738f7d36a2c5b785f4d1c/app/src/main/java/com/github/andreyasadchy/xtra/repository/PlayerRepository.kt).
No obvious type/header mismatch was found; that is not acceptance evidence.

The first new phone run returned official validation 200 followed by private
401 / ERROR_FIELDS / UNCLASSIFIED plus AUTHENTICATION_REQUIRED_REPORTED for
both live and replay. No raw response was inspected. A primary developer's
[public report](https://github.com/TwitchPotPlayer/TwitchPotPlayer/issues/31)
records two additional exact private-endpoint rejection phrases, one for the
Authorization token and one for the Client-ID header. Those fixed phrases were
added to the closed vocabulary for a second comparison; the historical report
is vocabulary evidence, not proof of this phone's cause or a current contract.

#### Final build and phone comparison — 2026-10-05

The final pinned Docker build passed 182 debug unit tests, lint (the same two
existing warnings and one hint) and debug assembly. `apksigner` matched the
documented shared debug identity. The host-owned APK is 12,347,457 bytes,
SHA-256 `08c77a37f31c4b1147db2fab207cd3aae6c4283954f15e4e997933c9438ec156`.
Scoped pre-commit/documentation checks and redacted source/test Gitleaks passed.
Review against the task-start snapshot kept prior experiments, dependencies,
permissions, token storage and original request contract intact; no generated
repository files were added.

`adb install -r` retained app data. Android's package-update surface briefly
intercepted the first menu launch; reopening after update recorded the native
route. The final saved-token live and replay cases each produced:

| Stage | Both source kinds |
| --- | --- |
| Official validation | HTTP 200 |
| Private playback access | HTTP 401 / ERROR_FIELDS |
| Closed reported categories | TOKEN_REJECTION_REPORTED and AUTHENTICATION_REQUIRED_REPORTED |
| Saved-token handoff | USED / validation HTTP 200; no fresh grant or approval |

The private service explicitly rejects this supplied token, despite immediate
official validation success. This narrows the rejection to private-endpoint
token acceptance, not its underlying reason. Client/token eligibility, private
authentication policy or another request requirement remain unresolved. No
client/integrity/query-specific category was reported by the recognized
vocabulary; that does not rule those causes out. Reauthorizing the same flow
has not been shown to help. A useful next control would exercise a scope-free
official Helix operation with the same saved token before further private
request-contract comparisons, keeping account fields out of output.

Conditions: Pixel 6, Android 17, native HTTPS (no browser engine), user-authorized
Tachiai client, Bob Ross live and replay `2080217716`; current region and exit
acceptance not independently verified. No VPN/provider-app setting, browser
session or app data was changed. Only credential-free native controls and
closed logs were inspected; no raw response, credential/key/ciphertext, media,
playlist or license was inspected. The saved token remains available and
Forget/reboot/long-term expiry tests remain pending. This is access evidence,
not native playback, Turbo entitlement or provider permission.

### Independent blank-client comparison

After a read-only comparison of Kodi's Twitch add-on, the user explicitly
requested trying the pattern without copying its code. The additive
TWITCH LIVE BLANK CLIENT and TWITCH REPLAY BLANK CLIENT cases isolate the
empty Client-ID header observed in that reference's authenticated playback
[header construction](https://github.com/anxdpanic/plugin.video.twitch/blob/82f4a7b424f4952af3edcf071f7a2d6ba3432f3a/resources/lib/twitch_addon/addon/api.py#L434-L445).
The implementation is written against Tachiai's existing transport/adapters;
no Kodi source is copied, vendored, linked or installed.

Only the selected private-request Client-ID header changes to an explicit empty
value, not an omitted header. The same saved own-client token, official exact
client/user/lifetime/scope validation, query/resource, OAuth scheme, fixed
endpoint, TLS, redirect rejection, 16 KiB bound and foreground/revision/expiry
gates remain in place. The transport still requires a valid declared client
identity, authenticated POST and explicit diagnostic mode. Anonymous and ABEMA
cases cannot enable this exception. Earlier cases remain unchanged.

These new cases additionally classify bounded signature/value presence after
HTTP 200 without errors, discarding both values. Only a PRESENT/ABSENT marker
is exposed; it is not proof of playlist access, playable media or Turbo. No
new grant, provider-client identity, browser session, extra permissions, private
token from another app, playlist/media request or player is added.

This is one request-header comparison, not a reproduction of Kodi's full
authentication path. Kodi's separate benefits login defaults to a provider
web-client identity, while ordinary public-app login supplies Helix features;
see its [separate device-login route](https://github.com/anxdpanic/plugin.video.twitch/blob/82f4a7b424f4952af3edcf071f7a2d6ba3432f3a/resources/lib/twitch_addon/routes/device_login_private.py#L11-L31).
The reference's [September report](https://github.com/anxdpanic/plugin.video.twitch/issues/719)
describes working ordinary playback but rejected private playback after using
an own app ID for benefits login. Source patterns and other users' observations
do not establish acceptance for Tachiai's token.

#### Blank-client build and device results — 2026-10-05

The pinned Docker build passed 185 debug unit tests, lint (two existing warnings
and one hint) and debug assembly. Negative HTTP test fixtures also emit Kotlin
unused-expression warnings; no build or test failed. `apksigner` matched the
shared debug identity. The host-owned APK is 12,347,457 bytes, SHA-256
`1a931cafd87845c54e75383440eb19db49cda80aefe187314e4a8dfaf91c1258`.
Scoped pre-commit/documentation checks and a redacted source/test Gitleaks sweep
passed. Independent review and comparison against the task-start snapshots
found no material regression, copied Kodi implementation, new dependency or
permission, or generated repository-file noise.

Installation with `adb install -r` retained app data. The native menu showed
all twelve cases, preserving the ten earlier examples. The explicit new live
and replay actions each produced official VALIDATE 200, private HTTP 401 /
ERROR_FIELDS / TOKEN_REJECTION_REPORTED plus AUTHENTICATION_REQUIRED_REPORTED,
accessFields ABSENT, and saved-use USED / validation HTTP 200. Neither action
requested new authorization or changed the stored credential. Configuring the
blank client header did not resolve rejection for either source kind.
The original TWITCH LIVE ERRORS case was then rerun on the same build/process
and returned validation 200 followed by the same private 401 categories and
USED, without the new presence marker. This is a same-build original-path
control, not an acoustic/media test.

Conditions: same Pixel 6 / Android 17, native HTTPS with no browser engine,
saved own-client authorization, Bob Ross live and replay `2080217716`. Region
and exit acceptance were not independently verified; VPN/provider-app settings,
browser sessions and app data were unchanged. Only credential-free native UI
and closed markers were inspected. No token/key/ciphertext, raw body, playlist,
media or license was inspected. Unit tests confirm an empty configured header;
the actual TLS wire transmission was not captured, so do not claim wire-level
verification or exact equivalence to Kodi's transport.

This comparison did not resolve access, but does not test every part of Kodi's separate
provider-client playback-authentication path. It does not diagnose private
client/token eligibility or justify changing identities. No native playback
or Turbo result was established; prior login, playback and access cases and
the encrypted saved token remain available for comparison.

## Additive provider-client authorization case — initial device request rejected

The user explicitly approved the remaining provider-client device-login pattern
on 2026-10-05 after the own-client blank-header comparison failed. This is a new
authority boundary, not an inferred response to a 401. Earlier statements that
no borrowed identity was used describe the earlier cases, which remain intact.
No Kodi code was copied. The public identifier observed in the reference is
`kimne78kx3ncx6brgo4mv6wki5h1ko`; provenance is the pinned reference's
[default identity selection](https://github.com/anxdpanic/plugin.video.twitch/blob/82f4a7b424f4952af3edcf071f7a2d6ba3432f3a/resources/lib/twitch_addon/addon/utils.py#L383-L387).
It is not Tachiai's registration, secret or supported playback API contract.

New cases:

- TWITCH PROVIDER AUTHORIZE SAVE: zero-scope device authorization using only the
  fixed provider identity, private external approval and exact official validation
  before saving to its independent encrypted slot.
- TWITCH PROVIDER LIVE: validate that saved provider grant, then use the existing
  Bob Ross access query with an explicit empty Client-ID diagnostic header.
- TWITCH PROVIDER REPLAY: the same path for replay `2080217716`.

The original own-client file/key/AAD and codec format are unchanged. New file,
key alias and authenticated-data binding isolate the provider slot. Opposite
client records and cross-repository leases fail closed. Neither slot falls back
to the other. No refresh token, browser session or existing Twitch-app credential
is reused. Approval identity is explicitly not Tachiai; the user must cancel
unexpected permissions. No transient activation code or private approval screen
may be captured. Exact client/user/lifetime/zero-scope validation precedes every
reuse. Presence markers discard access values and do not prove playback or Turbo.

The initial updated debug build passed 198 unit tests and lint (two existing
warnings and a hint), certificate verification against the shared debug identity,
scoped repository/docs checks and redacted source secret scanning. One transient
internal Kotlin/lint failure cleared on an unchanged-source rerun. The public
provider client identifier triggered a token-shaped scan rule; its narrow inline
exception documents that it is a public client ID, not a credential. The diff
was reviewed against the starting worktree, preserving previous examples and
excluding generated artifacts.

Pixel 6 / Android 17 phone results, native HTTPS without a browser engine:
the original saved own-client live error case still returned official validation
200 and private access 401 with the previous closed rejection categories. The
new provider saved-live case returned NO_TOKEN / validation HTTP 0, with no
validation/access dispatch or fallback to the own record. Then provider
authorize/save returned DEVICE HTTP 400 / REJECTED before issuing a challenge.
No activation code, external approval or provider token existed in this attempt;
the user did not need to approve anything. Region and exit acceptance were not
independently verified; account state for this new grant is none. App data,
provider/browser sessions and VPN settings were unchanged. Only credential-free
native UI and closed status markers were inspected.

A follow-up diagnostic adds exact known-error vocabulary mapped to closed
provider-reported categories for the initial DEVICE 400 only. Unknown/malformed
messages are not exposed, raw bodies are never logged, and categories are not
independently verified causes. Its verified debug APK passed 202 unit tests,
lint with the same existing warnings/hint, shared-certificate verification,
scoped repository/docs checks and the redacted source scan. APK SHA-256:
`29b25244008a97b076b65919015ac6ab30dc9cba4ba128e38a959e337366a68e`.
After an app-data-preserving update, a repeated provider-device start reported
HTTP 400 / CLIENT_REJECTION_REPORTED / REJECTED, still before any challenge or
grant. No user approval is required for this failed attempt. No provider saved
live/replay access could run because no provider token exists.

A bounded recheck of the pinned reference confirmed this is its device-start
identity, not merely a playback-query ID: its
[benefits route](https://github.com/anxdpanic/plugin.video.twitch/blob/82f4a7b424f4952af3edcf071f7a2d6ba3432f3a/resources/lib/twitch_addon/routes/device_login_private.py#L23-L31)
passes the selected provider client and explicitly empty scopes to a
[form POST](https://github.com/anxdpanic/plugin.video.twitch/blob/82f4a7b424f4952af3edcf071f7a2d6ba3432f3a/resources/lib/twitch_addon/addon/device_oauth.py#L17-L29).
Protocol observation was not code copying or a test of Kodi itself. A separate
[maintainer runtime report](https://github.com/rangermix/TwitchDropsMiner/issues/118)
also describes client-specific device-initiation rejection; that supports a
client-eligibility hypothesis, not a diagnosis of Tachiai's server-side cause.
The protocol identity pattern has not succeeded.
Whether the provider registration accepts this device request, its zero-scope
conventions and live/replay acceptance remain unverified; no grant or access
success is claimed. No playlist, media, integrity token, ad suppression, DRM or
native player is implemented.

## Additive Smart TV identity comparison

On 2026-10-05 the user approved investigating currently viable device-login
identities and made the phone available. The application-fit question is narrower
than choosing a playback architecture: can an identity issue a device challenge,
produce an exactly validated zero-scope grant, and separately satisfy the existing
live/replay access queries? None of those stages alone proves media or Turbo.

Evidence reviewed:

| Identity | Reported device flow | Reported authenticated API fit | Tachiai fit confidence |
| --- | --- | --- | --- |
| Provider web | DEVICE 400, reproduced in Tachiai | No fresh grant to test | Initial flow rejected on this phone |
| Mobile web | DEVICE 200 and validated grant | Tested GraphQL calls 401 | Poor next access candidate; playback unknown |
| Smart TV / SMARTBOX | DEVICE 200 and completed login | Account identity and channel discovery accepted; campaign catalog null | Challenge now issued in Tachiai; grant/access pending |

The recent primary
[device-start report](https://github.com/rangermix/TwitchDropsMiner/issues/118#browser-and-endpoint-investigation)
and [authenticated matrix](https://github.com/rangermix/TwitchDropsMiner/issues/112#issuecomment-5764905469)
are corroboration, not tests in Tachiai. The selected public Smart TV ID,
`ue6666qo983tsx6so1t0vnawi233wa`, is recorded in the reference's
[pinned identity definitions](https://github.com/rangermix/TwitchDropsMiner/blob/1182d0172458db4e23a236e9d9c078fd3b1fd9c7/src/config/client_info.py#L107-L114).
Its historical [device contract](https://github.com/rangermix/TwitchDropsMiner/blob/1922be434b2ca26d6667627ad6e53520cc68879a/src/auth/auth_state.py#L81-L153)
requests explicitly empty scopes. That reference also used additional identity
headers and omitted scopes on token polling; their necessity is unproven. Its
current release replaced the device path for its campaign feature. No reference
implementation code is copied and no claim of a currently complete solution is made.

Three additional examples select only PROVIDER_SMART_TV: TWITCH SMART TV
AUTHORIZE SAVE, TWITCH SMART TV LIVE and TWITCH SMART TV REPLAY. Their fixed ID,
encrypted file, key alias, authenticated-data binding and client-bound record are
distinct from both Tachiai and the prior provider-web slot. Earlier examples and
records remain unchanged. The comparison changes the selected fixed identity,
not HTTP headers, requested permissions, activation allowlist, token schema,
browser dispatch, access query or blank private Client-ID diagnostic setting.
Exact selected-client validation and cross-repository lease guards apply before
save/reuse. No automatic fallback, refresh, session transfer, secret, playlist,
media or integrity/DRM work is introduced.

Assumptions: this provider registration still permits this minimal zero-scope
request and returns the accepted activation/scope shapes; a grant may then be
accepted by private access. These are unverified until separately tested. Smart
TV identity does not turn the phone into Android TV or establish provider support.
The user checks the provider's actual consent identity privately and must decline
unexpected permissions. No activation code or private approval screen is captured.

Build verification: 213 debug unit tests passed; lint completed with the same two
existing warnings and hint. Shared debug-certificate fingerprint matches the
Tome procedure. Scoped code/docs checks and redacted source secret scanning
passed. The spelling checker mistook the public ID prefix for prose; a narrow
exact-ID regex exception preserves all other checks. Post-change review found no
identity/storage/routing blocker, and the diff against the starting worktree
preserves earlier examples without generated-file noise. The verified debug APK
was installed as an app-data-preserving update; SHA-256:
`fd7612886841936a74dc17dcb5ca5365afb99288146b920cbc69f170cd0cd791`.

Initial phone result: Pixel 6 / Android 17, native HTTPS without a browser engine,
no new account grant yet, region/exit acceptance not independently verified.
Smart TV saved-live returned NO_TOKEN / validation HTTP 0 without requesting
validation/access or falling back to either old slot. AUTHORIZE SAVE then
returned DEVICE HTTP 200 and reached WAITING under the unchanged activation
allowlist. A first TOKEN HTTP 400 appeared while the attempt remained waiting;
no grant or validation success is claimed. Unlike the web identity, this exact
minimal Smart TV request produced a valid device challenge. The user was asked
to open Brave, privately inspect the provider consent and return to Tachiai.
No activation code/private browser screen was captured; VPN settings, browser
sessions and earlier records were not changed. Grant validation, persistence,
private live/replay access and actual playback remain unverified at this point.

### Approval result and additive lifetime inspection

The user subsequently approved privately in Brave and returned to Tachiai.
TOKEN HTTP 200 was followed by INVALID_RESPONSE / GRANT_LIFETIME before any
official validation, save or access request. The strict case did not report the
field shape, so omission versus zero versus another invalid representation is
not known from that strict attempt. The returned token was discarded; earlier
saved slots are intact.

A fourth Smart TV example, TWITCH SMART TV LIFETIME INSPECTION, is validation-only.
It reports closed grant/validation lifetime categories, never raw bodies or
values. This explicit fixed-identity inspection can provisionally accept omitted
or integer-zero grant expiry and integer-zero validation expiry. Missing/null or
malformed validation expiry remains rejected, as do unexpected identity, user,
token or scopes. Both save/access callbacks are prohibited before network I/O.
The existing strict save and access examples are unchanged.

The inspection's acceptance deadline is at most 30 seconds from the token poll's
start, shorter if a positive grant lifetime says so. Foreground waits consume
that budget and late validation cannot succeed. Socket I/O retains its existing
timeouts/checkpoint bounds; this is not an exact wall-clock token-erasure timer.
The token stays worker-local and is discarded when the attempt ends; no storage,
refresh, playback-access query, playlist or media request is made.

A historical primary [Smart TV validation report](https://github.com/DevilXD/TwitchDropsMiner/issues/104#issuecomment-1363915607)
observed zero expiry. The reference's [grant handler](https://github.com/DevilXD/TwitchDropsMiner/blob/3892dfe59e8835486966cd668c9e128c36b7cd95/twitch.py#L315-L341)
does not consume grant expiry, which is implementation tolerance, not proof of
today's response. Twitch's [official validation documentation](https://dev.twitch.tv/docs/authentication/validate-tokens/#how-to-validate-a-token)
does not define zero as permanent validity. Any current zero observation will
therefore remain unspecified lifetime, not a permanent-token claim. Supporting
saved use with unknown lifetime requires a separate explicit local retention
policy and fresh official validation; this inspection does not implement it.

Inspection build verification: 225 unit tests passed, including 12 new lifetime
inspection tests. Debug assembly and lint succeeded; the shared debug signing
certificate matches the Tome identity. The redacted source secret scan found no
leaks. APK SHA-256:
`b9e3108012d2d6eedd9a53826bb9ef73096cd7f90e8fabaa6a02038bafce8cda`.
Scoped code/docs checks and a read-only post-change review passed; no generated
files were added to the source diff. The APK was installed without clearing app
data. On the same unlocked Pixel 6 / Android 17, the new inspection's DEVICE
request returned HTTP 200 and reached WAITING; its first TOKEN HTTP 400 was still
pending approval, not a terminal rejection. The fixed identity and READY screen
were verified before Start. No activation or private browser screen was captured.

After fresh private approval in Brave, the user returned to Tachiai. Closed
native markers reported TOKEN HTTP 200 / lifetime OMITTED / grant scope OMITTED,
then VALIDATE HTTP 200 / lifetime ZERO / validation scopes NULL / SUCCEEDED.
The selected Smart TV client identity and bounded user field passed validation,
as did the existing experimental null/no-scopes convention. Thus the observed
response shapes, rather than failed device authorization, explain the earlier
strict lifetime rejection. Zero's provider lifetime semantics remain unspecified.
The inspection discarded the token and made no save or playback-access callback.

Environment: same Pixel 6 / Android 17; token and validation used native HTTPS,
with Brave only for private approval. A signed-in account was approved privately;
account details and subscription benefits were not inspected. Network region/exit
acceptance was not separately verified, and no VPN settings changed. No live or
recorded content was requested in this case. Only closed native markers were
read; no activation/private browser screenshot or raw response was captured.
Smart TV grant validation is now locally observed; saved reuse, live/replay
access, media playback and Turbo behavior remain separate unverified stages.

### Additive locally retained Smart TV cases

After the validation-only success, the user approved a separate time-limited
saved-token case followed by live/replay access checks. Three new examples,
TWITCH SMART TV LOCAL SAVE, TWITCH SMART TV LOCAL LIVE and TWITCH SMART TV LOCAL
REPLAY, select PROVIDER_SMART_TV_LOCAL. They use the same observed public Smart
TV identifier but a fourth independent encrypted file/key/AAD, plus a version-2
record bound to the local profile. Strict Smart TV version-1 records and the
other earlier cases remain unchanged; no token is copied between slots.

This experiment permits the observed omitted/zero grant expiry and integer-zero
official validation expiry. It still requires exact client/user validation and
the bounded zero-scope convention, rejects null/missing/malformed validation
expiry, and prohibits own-client callbacks or combining retention with the
callback-free inspection. Initial official validation must finish within a
maximum 30-second acceptance budget from token-poll start. Only a successful
validation can save, for at most one hour from that start, reduced by any known
positive grant/validation expiry. One hour is a chosen local test policy, not
provider lifetime, permission or a permanent-token interpretation.

Each explicit saved use validates officially again, then allows the existing
single bounded access query for at most 30 seconds, reduced by known positive
validation expiry and the stored remaining retention. It never renews storage,
refreshes a token or requests another device grant automatically. Foreground,
cancellation, revision and repository-owned lease guards remain in place.
Expired or unreadable records cannot proceed; no expired files are deleted
automatically. Explicit Forget overwrites only the local slot without revoking
the provider grant or changing earlier records.

Retention persists using the existing wall-clock expiry format with a ten-second
safety margin; within an access attempt it is also capped by a monotonic deadline.
Wall-clock rollback before the saved timestamp fails closed. Clock changes still
after that timestamp across process restarts can misrepresent actual elapsed
time; the policy is not a tamper-resistant one-hour wall-clock guarantee. Fresh
official validation remains mandatory even when storage appears available.

No playlists, media, integrity tokens, client secrets, browser session transfer,
provider passwords or refresh tokens are introduced. Private access signature
and value are discarded. Phone save/reuse/access results for these new examples
are pending; the prior validation success is not evidence of playback or Turbo.

Local-retention build verification: 241 unit tests passed (15 new retention
tests and one new routing test). The first run exposed a test expectation that
omitted the one-hour cap for a positive two-hour provider expiry; the returned
one-hour implementation deadline was correct. The expectation was corrected
and the full verification rerun passed. Debug assembly, lint with the same two
existing warnings/hint, shared-certificate verification, scoped code/docs checks,
redacted source scan and read-only implementation review passed. The final diff
preserves earlier examples; generated APK/docs outputs remain ignored.
APK SHA-256: `3fb5286270f13a1a72825941474f329171d7825bdef36cecba04de9daf78e1a6`.

The verified APK was installed without clearing app data. On the same unlocked
Pixel 6 / Android 17, native saved-live first returned NO_TOKEN / validation HTTP
0, confirming no older-slot fallback or validation/access request. LOCAL SAVE
then returned DEVICE HTTP 200 and reached WAITING; a TOKEN HTTP 400 was still
pending approval. The fixed local profile and READY screen were checked before
Start, with no activation/private browser capture. Private approval, save and
subsequent live/replay access remain pending. Native HTTPS is the protocol engine;
Brave is offered only for private approval, and region/exit acceptance has not
been independently checked. No VPN setting or provider browser session changed.

### Observed local save and live/replay access

After private Brave approval, the user returned to Tachiai. Closed markers
reported TOKEN HTTP 200 / lifetime OMITTED / grant scope OMITTED, then VALIDATE
HTTP 200 / lifetime ZERO / validation scopes NULL / storage SAVED / SUCCEEDED
for PROVIDER_SMART_TV_LOCAL. This is observed official validation and local
encrypted saving under the explicit one-hour policy, not a permanent token.

Two explicit saved uses then ran sequentially without another device grant:

| Case | Fresh official validation | Private access response | Saved use |
| --- | --- | --- | --- |
| Live, `bobross` | HTTP 200 | HTTP 200 / NO_ERROR / accessFields PRESENT | USED |
| Replay, public VOD `2080217716` | HTTP 200 | HTTP 200 / NO_ERROR / accessFields PRESENT | USED |

The classifier's UNCLASSIFIED category is a placeholder when no parsed error
messages/codes are present in a NO_ERROR result; it is not a reported provider
failure. Signature/value stayed
inside the bounded response worker and were discarded. No playlist, media,
integrity token, license, DRM operation or advertisement experiment ran. These
results demonstrate acceptance of these two existing access queries with the
explicit blank Client-ID diagnostic header and a validated saved Smart TV grant,
not that the returned authorization can actually fetch or play media.

Environment: Pixel 6 / Android 17, native HTTPS for official validation and
private GraphQL queries; Brave used only for private approval. The account was
approved by the user, but identity/subscription details were not inspected and
Turbo benefits were not tested. Region/exit acceptance was not independently
verified and no VPN setting changed. Live/replay describe the requested public
resources; actual content availability was not separately checked by playback.
Only credential-free native case screens and closed native logs were inspected;
no code, clipboard, token file/key, private browser screen or raw response was
captured. Earlier records/cases remain intact. Long-term expiry/revocation,
process-restart reuse, provider permission and actual playback remain unverified.
Documentation checks passed; no new source or APK change was needed for this run.

### Activation-code copy convenience

The user requested a Copy code button to avoid remembering the activation code
during the external-browser handoff. The debug authorization screen now copies
only the current user code on explicit tap, marks the clip sensitive to suppress
Android 13+ previews, and shows fixed success/failure feedback. It does not copy
the activation URL/private device code/token, read clipboard contents, or log
clipboard errors. The button and feedback clear with the active challenge;
clipboard clearing is not promised. Clipboard caveats are recorded in
[security and privacy](security-and-privacy.md).

Verification: 244 unit tests passed, including three focused copy tests for the
exact user-code payload, absent challenge and clipboard failure. Debug assembly,
lint with the same existing warnings/hint, shared-certificate verification,
scoped checks and the redacted source scan passed. An Android-version fallback
removed newly introduced constant-availability warnings. Source diff review
found only this convenience, tests and documentation, with no generated noise.
APK SHA-256: `ed41b6f6528a14f1b764170147de711dcef41dc11f30e38904b66168b16ae548`.
Actual clipboard paste behavior on the phone still needs user confirmation; no
real activation code or clipboard content was captured for testing.
The final APK was installed without clearing app data, superseding the waiting
attempt. On the same unlocked Pixel 6 / Android 17, a fresh LOCAL SAVE request
returned DEVICE HTTP 200 / WAITING, with its first TOKEN HTTP 400 still pending
approval. Only the credential-free READY screen and closed native markers were
inspected; Copy code and private Brave approval are left to the user.

## Offline unchanged-helper initialization — 2026-10-07

The user approved investigating a minimal runtime before deciding how to reduce
ABEMA startup. Static inspection and an offline interface probe used the exact
anonymous public [application chunk](https://abema.tv/assets/compat/3850f3e68c3aa3c0.5206.js),
whose SHA-256 is
`d342d230d1f24a90a576597b673990a412a94bb5465cfa9b7567a9dc04e85b81`.
Its 1,801,458 bytes matched a fresh public retrieval. No authenticated request,
phone use, credential entry, guest registration, license or media request was
part of this follow-up.

The configured response callback in module 47994 imports provider helper 14405
and supplies utility module 27594. The former is about 55 KB of obfuscated
JavaScript; the latter aggregates crypto/encoding utilities. Inclusion of hash,
cipher and encoding implementations does not establish which the helper uses.
At its call boundary, the helper receives encoded response data and the current
provider user ID; its output is decoded and parsed before dash.js's separate
standard Clear Key serialization. This is executable processing plus dynamic
session inputs, not an identified universal content key. No algorithm was
reproduced, key extracted or embedded constant printed.

`scripts/abema-helper-runtime.cjs` registers the unchanged external chunk in a
minimal JavaScript context, does not run queued application entry/runtime
callbacks, and requires only those helper/utility modules and their dependencies.
Standard Webpack export, cache and module-normalization mechanics are supplied;
provider module bodies are not edited or committed. The probe calls only the
helper factory with its bundled utility library and verifies a callable result.
It never invokes that result, supplies a response or user identity, or reads
exports into diagnostics. Only closed outcomes, booleans and bounded counts leave
the context. Browser APIs are unavailable, dynamic code generation is disabled,
and individual evaluations have one/two-second timeouts, including queued
Promise microtasks. Cross-context exception properties are never inspected;
execution failures have one fixed category rather than provider-derived text.
The CLI additionally waits one event-loop turn under a process-local rejection
guard, never inspecting rejection reasons; any unhandled rejection refuses the
probe instead of entering stderr. Diagnostic serialization failures are caught
inside the timed context before host-side shape validation.

Conditions: Linux container, Node 24.15.0 in local
`mcr.microsoft.com/playwright:v1.60.0-noble`, no browser engine/device/CDM,
no account state, no playback content and no regional connection because
networking was disabled. The container was disposable, non-root, read-only,
capability-dropped, privilege-escalation-disabled, limited to 256 MB/one CPU/64
processes, and mounted only the probe and public asset read-only. Node `vm` is
not a security sandbox; these outer restrictions are required for real assets.

Both NO_WINDOW and WINDOW_ALIAS profiles reached INTERFACE_READY: 37 modules
loaded, totaling 119,430 source characters. Neither supplied DOM, storage,
network, media or CDM APIs. The utility initialization probed unavailable APIs
three/four times, respectively; a probe is not an executed API operation.
The first harness rejected capability probes and a later attempt omitted
Webpack's standard `nmd` utility; those harness failures were corrected before
this result. This measures neither response-processing time nor user startup.

Seventeen synthetic harness regressions passed, including asset/hash rejection,
module identity/caching, skipped entry callbacks, module-normalization support,
missing dependencies, unavailable browser APIs, unexpected interface shape,
exception/output redaction, disabled dynamic compilation, synchronous/Promise
timeouts, refusal to read provider exception getters, guarded diagnostic
serialization and suppression/refusal of unhandled Promise rejection reasons.
The existing 60 opaque-broker regressions also passed. No Android source or APK
changed, and no provider bundle or generated artifact was added to the repository.

The Promise-timeout regression runs in an eight-second/SIGKILL-bounded plain
subprocess: interrupting a VM Promise inside Node 26's test-runner async scope
initially caused async-hook corruption. The host sandbox then denied that
subprocess; the complete seventeen-test suite passed in the same isolated Node
24.15 container used for the public asset. These were harness/test-environment
failures, not ABEMA license results. Review also moved provider exception catches
inside the timed context so VM internals cannot inspect provider stack getters
after evaluation. No provider algorithm or actual processing operation changed.

**Established:** the current helper's module loading and factory initialization
do not require the full ABEMA page/player. **Still unverified:** its response
operation's runtime requirements, minimal provider-authorized session/source
bootstrap, fresh-response/native-CDM compatibility outside the existing browser
path, renewals and end-to-end startup savings. The original provider's guest,
media authorization, source-selection and entitlement/ad policy must not be
silently replaced or dropped. A later integration must preserve the existing
exact-source, matching identity/session, opaque response and one-exchange bounds.
This offline result does not select a release architecture or grant provider
permission. No live licensing or authorization probe is implemented here.

## Runtime-downloaded cached helper — 2026-10-07

The user approved downloading the reviewed public bundle at runtime instead of
including it in the APK. An additive debug-only **ABEMA cached helper · download
and initialize** home-screen case tests that boundary. Existing prototype,
browser and native playback examples remain unchanged; this is initialization
only, not a replacement ABEMA startup path.

The native HTTPS downloader admits only the exact public chunk and SHA-256 from
the offline experiment above. It uses ordinary certificate verification, no
credentials/cookies, no redirects, bounded socket/foreground time and a 2 MiB
size limit. An app-private cache verifies every hit, writes verified downloads
through an atomic replacement and retains no account/session/license material.
Corrupt code is not executed; a failed replacement preserves the existing file.
Android may evict this cache. There is no automatic update discovery/execution:
another provider version needs an explicit compatibility and hash review.

The unchanged chunk is served alongside three small app-owned loader assets at
an isolated app-owned origin. A dedicated debug process/WebView profile precedes
cookie APIs. Network loads, cookies, DOM storage, frames, workers, media, dynamic
code generation, native bridges, popups and permissions are unavailable or
refused by settings, exact resource interception and CSP. The loader registers
the chunk without executing its application entry callback, initializes only
the helper/utility dependency closure and never calls or exports the resulting
processing function. No provider identity, challenge, response, source or CDM
enters this case. Script errors/rejections revoke readiness without inspecting
their contents. A settling turn and two native READY observations at least
100 ms apart precede acceptance; the WebView is then destroyed. Only closed
outcomes and bounded module/character/byte/time counts enter the UI/log.
Stop/background/rotation cancels the run, and foregrounding does not resume it.

Device conditions: Pixel 6 / Android 17 / Android System WebView 153.0.8010.36,
foreground portrait, anonymous public static-code retrieval and no account or
playback content. The user's normal phone network was left unchanged; provider
region/exit acceptance was not tested by this static asset request. The signed
debug update installed without clearing app data. Only the owned fixture UI and
closed `TachiaiAbemaBundle` markers were inspected.

| Run | Cache outcome | Cache preparation | Complete initialization |
| --- | --- | --- | --- |
| First run | DOWNLOADED_VERIFIED | 2,999 ms | 3,698 ms |
| Same-process repeat | CACHE_HIT | 31 ms | 403 ms |
| After app force-stop/relaunch | CACHE_HIT | 42 ms | 381 ms |

Every successful runtime reported 37 modules / 119,430 source characters,
matching the offline probe. A further repeat completed at 427 ms; Stop then
reported STOPPED. That last run completed before the tap, so it is not evidence
of in-flight device cancellation. A separate immediate-background run logged
CACHE_HIT at 18 ms then CANCELLED without a late READY result. Bringing the
same task forward retained CANCELLED and an enabled Start button, without
automatic restart. Unit tests additionally cover cancellation and preservation
of prior/unrelated files. The cache was retained, and no historical case or
account data was deleted.

Verification: 505 Android unit tests passed (25 new cache/transport/policy
regressions), plus 12 synthetic browser-loader regressions, including an actual
queued Promise rejection and readiness settling. Debug assembly, lint with the
same five warnings/one hint, shared signing-certificate comparison, scoped
pre-commit checks, secret scanning, documentation build and diff whitespace
check passed. The build emitted a non-fatal metrics-file permission warning in
the disposable container. The first signing wrapper failed to parse apksigner's
new `V2 Signer` label despite a matching fingerprint; the corrected wrapper and
settled-source rebuild succeeded without changing the key. The APK is
16,675,521 bytes, owned by the host user, SHA-256
`649fab85c62d3c66fc88f619a4dbcd85342e797907bcdb5d6777df712b589886`.
APK asset inspection found only the existing broker and the three small owned
loader files under `assets/abema/`, not the provider bundle. Scoped review found
no generated/provider asset added to the repository.

Factory initialization does not establish response processing, minimal session/source
bootstrap, licensing, playback, renewal or end-to-end startup savings. The
existing exact-source, matching-session and one-exchange boundaries still apply
to any later playback integration. Provider permission/distribution risk is
not settled by successful public-code loading.

## Cached native bootstrap without the provider page — 2026-10-07

The user asked to replace the prototype's full-page/onboarding preparation, not
merely demonstrate cached helper initialization. The normal Prototype entry now
uses a separate cached-bootstrap activity/profile; **Prototype comparison ·
original web startup** preserves the earlier implementation and all historical
cases. No provider bundle is stored in the repository or APK.

Four exact public assets are downloaded through the existing bounded HTTPS
transport and verified on every cache hit. Updates are not discovered or executed
automatically. The application pin is unchanged; the additional reviewed pins are:

| Asset | Bytes | SHA-256 |
| --- | --- | --- |
| `a3d61a8507a3f772.framework.js` | 326,667 | `4dc7e3f6d08af278d1b7077d79c3bf5a381e5ff6934189a7d8b0f1219e8190cf` |
| `7977ee0696dadeb7.9328.js` | 187,143 | `0187e58193b73f6e261f8ca799c7d75d76b4138813e17b1d57c4d8a6919bc466` |
| `1deb672fea92d35e.8204.bundle.js` | 18,786 | `8e973ee101b83fb13d6a20d5be53b9c9d7642a522547c7069acf4bac00e28a5d` |

The tiny attached runtime loads selected unchanged factories, not application
entry callbacks, provider pages, video elements or login forms. It creates a
fresh guest with the provider's unchanged application-key helper, obtains a
media token and delegates source selection. Guest cookies can be created normally
in this isolated profile, but no existing browser/account state is imported or
cookie value read/exported. Each slot retains its own fresh identity, selected
manifest and one-shot opaque exchange with its own native CDM. The response
helper takes two arguments: encoded fresh response and guest ID; the earlier
three-argument interpretation was a static pretty-printer mistake involving a
parenthesized comma expression, not a different provider protocol.

Initial phone iterations established guest/media-token HTTP 200, then exposed
three implementation mistakes: a session manager used as a flag adapter, an
array treated as a nested manifest object, and the linear News slug sent to the
newer resource gateway. That last request returned HTTP 400; an anonymous public
comparison identified unsupported ARIN, not a region or DRM failure. Public route
inspection established News's actual legacy playlist selector and license
composition. It also identified a missing utility dependency before native
construction. These failures did not load a full-page fallback.

News now reads the advertised fixed channel, preserves the unchanged selector's
choice with `enforcePlainStream=false`, and uses the public legacy Clear Key
endpoint bound to fresh media token, channel ID and content type. Only the exact
selected News MPD expands the older query-free policy for reviewed selector
parameters; historical policies remain unchanged. An unsupported selected
advertising URI is refused, not replaced by another playlist. Replay uses fixed
program metadata and its advertised ARIN, unchanged filter/comparator and
configuration builder, with a deliberately conservative free-window/restriction
gate. Provider `drm=false` or an omitted hint can select Clear Key; it is neither
an entitlement check nor proof that samples are unencrypted. The bounded
protected-MPD/KID/CDM checks remain independent and mandatory.

### Live device results

Conditions: Pixel 6 / Android 17 / Android System WebView 153.0.8010.36, foreground
landscape, two anonymous native News copies in the new profile. The VPN marker
was present under the user's usual ABEMA playback connection; exit geography
was not independently verified. No provider page or original media element was
created. Both sessions independently requested their initial license, handed
off their own opaque response, loaded DRM keys, reached READY/PLAYING and rendered
native video in the primary/floating layout. Both content clocks advanced.

| Run | Code cache | Open viewer to both first native frames |
| --- | --- | --- |
| First successful no-page run | Three hits, utility chunk downloaded | 12.9 s |
| Fresh process after force-stop | Four hits per slot | 10.6 s |

The first run's factory closure was 271 modules. Both feeds continued through
manifest refreshes for the five-minute foreground budget, with brief buffering
and recovery and no observed second license request. The deadline then tore down
the pair; the current callback reports FAILED even for that deliberate budget
expiration. This is not an unexplained mid-budget DRM failure. The fully cached
repeat prepared its code in about 70–92 ms, so most remaining startup time is
network/session/source/licensing work, not full-page rendering. A genuinely empty
four-asset cache was not measured in this playback run. The user did not perform
a new acoustic check; new-path audio remains unconfirmed, despite earlier native
audio evidence. No assumption about audio is recorded as a new observation.

Current remote feature evaluation, ad-cluster/device classification and complete
advertising tracking/overlays are not reproduced. The unchanged SDK's defaults
are an explicit experimental assumption, not equivalence to current full-player
policy. No ad-disable parameter, forced plain-stream selection, guessed ARIN,
account/password capture, response/key dump, cross-slot reuse or renewal was
introduced. Successful initial playback does not settle provider permission,
distribution, other content, long-lived sessions or version durability.

### Replay gate follow-up

The same device/connection's two-copy sumo replay attempt completed fresh guest,
media-token and fixed program metadata requests with HTTP 200. It stopped before
source or licensing at the conservative restriction gate. A closed field-specific
repeat identified `trialWatching`, not a network, region or DRM rejection. Public
DTO module 91060 represents its disabled default as `{}` or `{enabled:false}`.
The follow-up accepts only those strictly typed known-field inactive shapes;
enabled trials, malformed/unknown fields, rental, device, precedence and partner
conditions remain refused. No program response, token or license body was logged.
The next fresh repeat passed that trial gate and stopped at the remaining
partner/external metadata gate, again before source/licensing. Public DTO 91060
and program entity 15684 also construct empty informational records; the latter
always initializes external-content link/text/button strings to empty defaults.
The follow-up permits only strictly known-key empty-string partner/external
records, a default UNKNOWN/zero external-provider type and absent/false gambling
marks. Meaningful values, unknown fields, malformed records and nonempty partner
authorities remain refused. Its device repeat identified nonempty external-content
metadata, not external-provider eligibility. Anonymous public desktop chunk 9852
(module 17351) renders that field as promotional link/text/button UI; mobile
chunk 2910 (module 21643) checks the distinct external-provider, region, license,
free terms and source availability for playback eligibility. The follow-up accepts
strictly typed bounded promotional strings without navigation, fetching or logging.
Unknown fields and gambling-warning marks remain refused. That promotional UI and
its impression/click tracking are not implemented; this is another production
equivalence gate, not a claim that all provider presentation is preserved.

Static inspection also found a replay capability mismatch before its device
source check: the original composition hardcoded Widevine's `enc=wv` while the
native host supports only the provider's protected custom/Clear Key path.
A narrow capability wrapper now delegates the original stream/manifest filter
and retains the original comparator, admitting only protected custom DASH
streams before ranking. It never searches later candidates for an ad-free mode:
the first supported candidate's selected ad mode must itself be supported or
preparation stops. The unchanged configuration must identify the custom key
system and exact allowed endpoint; that selection uses `enc=clear`, as in the
provider's public mapping. This is media-capability selection, not converting a
Widevine response, forcing unencrypted media or disabling advertisements.
The corrected device repeat passed the metadata gates but stopped at the owned
gateway-request policy, before receiving a playback-resource response. A closed
shape diagnostic identified DOT_OR_TILDE in the provider-advertised identifier;
no identifier/URL was emitted. The unchanged gateway interpolates that ARIN
directly into its fixed HTTPS GET path, with no query or authorization header.
The endpoint's bounded identifier pattern now admits those standard unreserved
characters while retaining no-query/no-fragment/no-encoded-path/no-traversal
checks. Regression tests cover both accepted characters and refused route changes.
The next two-copy repeat completed the gateway request and reached its selected
protected custom DASH candidate, then refused before URL construction/native
licensing because the first selected manifest's ad mode was unsupported. A
closed follow-up marker was ADS_UNSUPPORTED, not ABEMA_DEFAULT. The unchanged
manifest filter narrowed the expected remaining modes to CSAI or MediaTailor.
The next fresh-process repeat on the same phone reported **ADS_CSAI** at
21:03:31 local time, after the fixed replay's gateway HTTP 200. That is now a
measured mode, not an inference. No later ad-free candidate was selected and no source or
license URL was rewritten to avoid the ad adapter.

The no-page News proof remains valid, but no-page replay playback is incomplete.
Removing the full app means its advertisement orchestration cannot be assumed
to exist in the smaller runtime. The original page-backed prototype remains
available for comparison. The own metadata and identifier-policy mistakes above
were corrected; this result is not evidence of a new native replay DRM failure,
because native licensing has not yet been attempted on this path.

### Selected CSAI dependency preflight (historical)

The user approved including necessary runtime bundles first, with simplification
deferred until playback works. Public adapter inspection identified the unchanged
IMA adapter (68863), its factory (74766), driver (54105) and SDK injector (90616)
in the already cached application chunk. Its Youbora IMA adapter dependency
(26026) and core/version dependencies are in one additional public asset:

| Asset | Bytes | SHA-256 |
| --- | --- | --- |
| `7459be98c1074c5c.youboralib.js` | 160,733 | `c7c1b2c250b5f526ebefc7085a54b50e338d636421729d5a2ead3d5315640fea` |

That replay iteration downloaded/cached/verified this fifth chunk; News still downloaded only
the original four. The owned loader checks the CSAI adapter and driver exports
without creating an adapter/analytics plugin, injecting the SDK, requesting ads
or constructing a native content player. Importing the vendor core applies
conditional polyfills and reads the owned document's query for log level;
static inspection did not identify import-time network or storage operations.
Existing CSP and exact-request restrictions remain in place, and console output
is suppressed. This is dependency preflight, not completed advertising support.

The provider factory injects the official Google IMA HTML5 SDK and requests the
provider's program VMAP. It requires a real, visible ad display/video container,
not the current hidden 1×1 bootstrap host. It starts the SDK internally and emits
ad-break start/end plus ad start/end callbacks. Native content must remain held
during each entire ad break; ending one ad within a pod must not resume content.
Volume, foreground cleanup and native content-time reporting also need wiring.
The SDK injector resolves even on script-load error, so explicit
`window.google.ima` readiness validation is required. Google's
[integration guide](https://developers.google.com/interactive-media-ads/docs/sdks/html5/client-side/get-started)
likewise requires a visible ad container and separate content pause/resume handling.

Fresh guest ID, fixed program ID, actual browser/OS/UA and non-premium empty-plan
state can supply the public request context. Unestablished privacy, geography,
connection classification and third-party identities must not be fabricated.
No ad-disable flag or substituted ad-free manifest is proposed. Ad SDK/resource
network policy and lifecycle integration are the next work, not evidence that
another large application bundle is necessary. The alternative MediaTailor
adapter is already in the application chunk but was not selected in this test;
its session manifest/tracking path is not implemented or device-verified.

The higher-level IMA adapter creates a Youbora ad adapter that expects attachment
to the full player's analytics plugin. Standalone playback must not substitute
a fake/no-op plugin and call that equivalent. The unchanged lower-level factory
(74766) is a candidate for preserving real ad playback/lifecycle without that
wrapper; analytics attachment would remain an explicit integration gap. Its
provider cluster implementation currently returns unchanged zero/empty defaults,
not a remote cluster response. The upfront reviewed network destinations are
the official IMA loader and `https://avod.ad.abema.io/v1/pmap`; returned VAST
wrappers, creatives and tracking destinations remain dynamic. Those two known
destinations do not justify admitting arbitrary HTTPS resources.

The current bootstrap's `frame-src 'none'`, hidden container and denied remote
scripts deliberately cannot run IMA. This is our current hosting policy, not a
measured operating-system rejection. An ad host needs a reviewed, separate
capability policy rather than globally weakening every provider/login pane.
Google's [display-container contract](https://developers.google.com/interactive-media-ads/docs/sdks/html5/client-side/reference/class/google.ima.AdDisplayContainer)
requires a persistent SDK iframe, correctly sized DOM container and direct user
activation. Its [WebView setup guidance](https://developers.google.com/admob/android/browser/webview)
also recommends browser storage, third-party cookies, autoplay and network-loaded
content for advertising. Our generated document and current settings are not
claimed equivalent to that supported configuration; actual compatibility needs
a device check before deciding which pieces can be omitted.

The fifth-chunk preflight was built, signed and installed without clearing app
data. On the same Pixel 6/profile/connection, one slot logged
DOWNLOADED_VERIFIED and the other CACHE_HIT. Both loaded 163 module factories and
passed all checked CSAI adapter/driver exports, then intentionally reported
ADS_CSAI before source/configuration/licensing. This proves the checked import
closure is present; it does not prove complete SDK dependencies, actual ad
playback or native replay licensing. The phone is not left in a playback test.

Verification: 515 JVM tests and 34 synthetic no-page JavaScript cases passed,
along with lint and debug assembly. Certificate verification matched the shared
debug signing certificate. APK SHA-256:
`78b288ff2524884b95fab4a6f3d19632cc174199a289799d8c7f4be7a075f8b0`
(16,675,768 bytes, host-owned). The extra provider code is only in the runtime
cache, not the APK or repository. Existing warnings remain; no release/provider
permission claim, ad-free fallback, account flow or additional DRM exchange was
added.

### Removal of the owned ad-adapter prerequisite

The user asked whether the selected CSAI mode really implies a server-enforced
ad-completion requirement, then explicitly requested removing the check we had
added. The existing ADS_CSAI refusal was entirely our own unsupported-adapter
guard, before content URL construction or native licensing. It was not a server
denial. Public content-session inspection shows CSAI callbacks pausing/resuming
the local content player; the content URL and DRM configuration are constructed
separately. No completion receipt flowing into those builders was identified.
This does not establish absence of any server-side dependency or provider policy.

The current debug prototype removes that prerequisite and the fifth-chunk
preflight from startup. Both live and replay again load four pinned chunks.
The reviewed fifth identity/cache evidence remains available; existing public
cache files and original page-backed examples are not deleted. The first original
provider-ranked native-compatible direct manifest proceeds through unchanged
query/media-URL/DRM builders. No source reordering, ad-disable/premium parameter,
fabricated completion/tracking event, content/DRM response mutation, credential
transfer or license renewal is added. A separate client-side ad player is not
created on this path, so this is explicitly not equivalent provider ad playback.

NONE, CSAI and ABEMA_DEFAULT use the direct manifest path. The latter retains
whatever advertisements the selected manifest contains; it does not establish
complete tracking. MediaTailor still reports SOURCE_SESSION_REQUIRED because
its selected session must return a new manifest URL; using the original URL
would not implement that source. Unknown modes report SOURCE_MODE_UNSUPPORTED.
Neither case searches a later ad-free alternative. Existing free-window,
restriction, exact-network, protected-MPD/KID and fresh one-exchange-CDM checks
remain. Real media/license failures will stop rather than be synthesized into
success.

On the same Pixel 6 / Android 17 / System WebView 153.0.8010.36, two fresh replay
slots used four cache hits and completed guest, media-token, program and gateway
requests. They reached helper READY with 87 factories, rather than refusing at
ADS_CSAI. The first installed repeat then stopped at the existing native selected-
source policy, before requesting the manifest or license. A closed-diagnostic
repeat identified AUTHORITY / VOD_AKAMAI: the original gateway selected
`vod-abematv.akamaized.net`, while the earlier page-backed replay policy permits
only `ds-vod-abematv.akamaized.net`. No URL, query, token or response body was
logged. The allowlist has not been widened or the URL rewritten. This is another
owned source-policy mismatch, not a provider ad-completion or DRM rejection.
The changed native replay still has no playback/audio evidence. Existing News
and original comparison evidence remains unchanged; no acoustic check was asked.

The installed diagnostic APK passed 515 JVM tests, lint and debug assembly,
with the shared certificate fingerprint verified. The updated JavaScript suite
passed 34 synthetic cases, including the selected CSAI URL/configuration/opaque
exchange path, four-chunk startup, no later-source substitution and malformed
ad modes. These fixtures are not real provider responses or end-to-end playback.
Scoped hooks, secret detection, documentation build and diff whitespace checks
passed. APK SHA-256:
`02a753098faee3ad9ec5a5968d258e882bfe6b331b4c1c70efecd6876d8e62e6`
(16,675,768 bytes, host-owned). Installation preserved app data and historical
examples. No provider bundle was added to APK/repository, no broad networking
exception was added and no phone playback test remains running.

### User-approved media-host family and durable review

The user approved broadening the cached prototype from the older exact media
hosts to one-label `*-abematv.akamaized.net` destinations, with other eligible
origins blocked for explicit approval/rejection. This corrects the observed
gateway-selected `vod-abematv` authority mismatch without changing fixed content
eligibility, query/path rules, MPD-declared membership, HTTPS/redirect handling,
provider source ordering, license configuration or the one-exchange native CDM.
Cached News uses the same opt-in family. Historical examples retain their exact
host defaults. [The approval ledger](media-origin-approvals.md) records the
boundary and future decision workflow.

A bounded app-private no-backup journal retains first-seen timestamps, closed
categories and redacted origin patterns only. Unknown eligible origins are
PENDING, not implicitly granted or user-rejected. Explicit decisions are
documented and compiled into policy; journal entries never authorize access.
The CLI validates the entire bounded file before printing normalized evidence.
There is no recurring monitoring. Writer locking is process-local, appropriate
to the single cached prototype process; first new observations currently perform
small synchronous journal writes, an experimental implementation rather than a
final performance design.

Verification passed 525 JVM tests, including ten new CDN-policy/journal cases,
34 no-page bootstrap fixtures, 12 cached-helper fixtures and three review-reader
cases. Lint/debug assembly passed with the existing five warnings and one hint;
new nullability warnings found during the first compile were fixed and the
final rebuild was clean of those warnings. Both builds verified the shared debug
certificate. The final host-owned APK is 16,675,768 bytes, SHA-256:
`46dbdd8bbf6f7b80c4a6b2d5c3f259f93761dd9157d37b3c131f55420b4ded37`.
Scoped hooks, secret detection, documentation build and diff whitespace checks
passed. APK inspection shows only owned ABEMA harness assets, not downloaded
provider bundles. Installation used an in-place update preserving app data.

The same Pixel 6 / Android 17 / System WebView 153.0.8010.36, cached prototype
profile, guest-only account state and VPN-marked connection ran two copies of
the fixed free sumo replay. The VPN marker was visible; the exact exit was not
independently inspected. PID 17255 started preparation at 21:49:44.218 local.
Both slots used four cache hits and reached helper READY with 87 factories,
then source ALLOWED. Manifest/media reads returned HTTP 200. Each independent
native CDM requested its initial license and loaded its response; both native
players emitted VIDEO_FRAME at 21:49:59.112, about 14.9 seconds after starting.
The landscape primary/floating surfaces visibly rendered sumo, and both
playing clocks advanced. No original ABEMA page/video or client ad adapter was
loaded. The bounded reader found APPROVED selected-manifest and declared-media
observations for `https://vod-abematv.akamaized.net/<redacted-path>` and zero
pending observations. No source URL/token/path or opaque response was printed.
This proves the local hostname mismatch is corrected and the direct replay
can play; it does not establish full ad/analytics equivalence, sustained
operation beyond the foreground budget, renewal or provider support. No new
acoustic confirmation was requested.

Both replay clocks continued past a minute. A short buffer recovered through
the existing joint-play gate; later readback showed about 1.17 seconds of
relative offset. The user subsequently reported that they may have adjusted
alignment during the test. This is therefore not established automatic drift
or evidence that buffering caused the offset; an untouched repeat is needed
to distinguish manual adjustment from recovery behavior. A fresh-process
two-News repeat (PID 18047, same device/profile/connection) began at
21:51:27.933 and emitted first native frames at 21:51:38.492/38.502, about
10.6 seconds later, with independent initial CDM exchanges. Both clocks
advanced; a brief buffer also recovered, with roughly one second of relative
offset observed afterward. Its cause is likewise unverified, not attributed to
buffering by this observation alone. The validated journal retained both earlier
replay observations and
added APPROVED selected-manifest/declared-media patterns for
`https://linear-abematv.akamaized.net/<redacted-path>`, with no pending
observations. No additional origin approval was required. The run was stopped
after this short regression check, returning the phone to the source picker.

## Unresolved gates

- Twitch GraphQL is not the supported Helix playback API. Own-client OAuth
  validation does not imply acceptance there. Rejection is a stopping result,
  not a reason to borrow another application's identity without explicit new
  authority. The isolated case above has that user authority, not provider support.
- Provider permission and distribution/account risk remain unresolved. The
  attempted official Twitch developer-agreement page returned only its footer
  to the research reader; that is not a completed terms review. Real private
  playback integration still requires the implementation decision gate.
- ABEMA needs an authorized source and license configuration if encrypted.
  Standard Media3 DRM support alone cannot resolve that requirement. No secret
  derivation, key extraction, CDM bypass or license replay is proposed.
- Even successful access leaves two-decoder performance, mixed audio,
  retained live windows, ad transitions and relative seek control untested
  in a native player.
