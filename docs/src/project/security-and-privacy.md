# Security and privacy

Tachiai loads authenticated third-party pages and therefore handles sensitive
browser state even though it does not operate an account service.

## Authentication boundary

- Provider credentials are entered only into provider-controlled pages.
- Tachiai does not add key listeners, form listeners, DOM serialization, or
  debugging instrumentation to login and account pages.
- Adapter injection is enabled only on exact allowlisted playback origins and
  routes after navigation is complete.
- Passwords, cookies, OAuth tokens, license requests, and signed media URLs are
  excluded from logs, crash reports, exports, and saved presentations.
- A saved presentation contains public resource identity such as a channel or
  page URL, layout, audio policy, and requested offset—not authenticated state.

### Debug device-authorization probe

The user authorized an own-client Twitch device-flow experiment. It is gated
by `BuildConfig.DEBUG` and an explicit `TWITCH_DEVICE_AUTH` launch extra; it
does not change the existing player or provider browser profile. The registered
public client ID is not a secret. No client secret or borrowed client identity
is used in the original own-client cases. The separately approved provider-client
case below does not change those cases or their exact-client validation.

### Explicit provider-client identity experiment

On 2026-10-05 the user separately approved testing the observed provider-client
device-authorization pattern, without copying Kodi code. Three additive debug
cases authorize/save, check live access and check replay access. The fixed public
provider web identifier is not Tachiai's application registration or a secret.
This is an unsupported identity experiment, not permission to distribute an
integration or a claim of provider approval. The user privately checks the
application identity before approving externally; unexpected permissions must
be declined. Only zero requested scopes and the existing bounded scope convention
are accepted. No provider client secret is obtained or used.

The provider grant has a separate no-backup encrypted file, AndroidKeyStore key
alias, authenticated-data binding and exact-client record codec. No automatic
migration, own-token fallback, browser-cookie transfer, refresh or credential
export occurs. Every explicit reuse validates the exact provider client, user,
lifetime and scope convention. Repository-owned leases additionally prevent
same-revision callbacks from crossing slots. Local Forget affects only the
selected slot, not the other grant or provider revocation.

Its private access requests use the existing independently authored query with
the explicit blank Client-ID diagnostic setting. Responses stay bounded; only
closed status/error categories and access-field presence are reported. Signature
and value are discarded, with no playlist, segment, player, integrity spoofing,
ad suppression or license work. Native activation codes and private browser
approval screens are excluded from diagnostic capture. Earlier own-only cases
and their encrypted record remain intact.

The user later authorized an additional fixed Smart TV identity comparison
(`PROVIDER_SMART_TV`) after the web-identity device start was rejected. It uses
its own third encrypted file/key/AAD/client binding, never overwriting either
earlier slot. An explicitly selected closed provider profile binds the provider
callback to its exact client ID; Tachiai's own callback still accepts only its
own registration. Consent wording and closed profile markers distinguish the
identities. All scope, activation, foreground, response bounds and no-media
restrictions above remain unchanged. Other clients' device-flow success is not
provider permission, supported integration or proof of playback.

An additive Smart TV lifetime-inspection case can accept omitted/integer-zero
grant expiry and integer-zero official validation expiry only transiently.
Exact identity/user/zero-scope checks remain mandatory; null/missing validation
expiry is not accepted. This mode refuses both token handoff callbacks before
I/O, never saves a token, and applies a maximum 30-second acceptance deadline
from token-poll start, consuming foreground waits and rejecting late validation.
It is not an exact wall-clock erasure timer during bounded blocking I/O. Zero
does not mean permanent validity. Strict saved-token cases remain unchanged.

The subsequently approved PROVIDER_SMART_TV_LOCAL cases use a fourth encrypted
slot and a new profile-bound version-2 record, not migration or sharing of the
same-client strict record. Only this explicit save mode accepts the observed
omitted/zero grant expiry and zero validation expiry for local retention. It
requires a provider-only callback and official validation within 30 seconds of
token-poll start. Retention is capped at one hour from that start, reduced by
known positive provider expiry. Each explicit reuse validates again, applies
a maximum 30-second access deadline capped by stored remaining retention, and
never renews the record. Strict modes and callback-free inspection remain intact.
The one-hour policy is experimental and wall-clock-dependent across restarts,
not inferred Twitch expiry or a secure erasure timer. Rollback before save time
rejects; other clock changes can distort elapsed time. Expired records are not
used or automatically deleted. Local Forget overwrites only this new slot.

The probe calls only Twitch's official device, token and validation endpoints
over ordinary verified HTTPS, with redirects disabled. It requests an empty
`scopes` value; acceptance is experimental, and rejection must not silently
increase permissions. Polling respects the provider interval, expiry and
`slow_down`. Responses have a 16 KiB limit, socket timeouts and a cumulative
elapsed-time checkpoint budget; that budget is not an exact wall-clock bound
on an already-blocked operation. Cancellation closes the active connection.

The provider's allowlisted activation link opens externally through an explicit
Brave or Chrome package-targeted Android URL intent, with no fallback to Twitch's
app. This controls initial dispatch, not any later browser-initiated delegation.
An unavailable or blocked browser cancels the attempt. The user checks its identity
and authorizes privately. No provider credential UI is hosted, inspected or
instrumented by this probe. The native screen shows the transient user code,
which must not be captured in diagnostic output.

At the user's request, an explicit Copy code button copies only the current
transient user code to Android's system clipboard, not the activation URL,
private device code or token. It sets the
[sensitive clipboard flag](https://developer.android.com/develop/ui/views/touch-and-input/copy-paste#sensitive-content)
to hide Android 13+ clipboard previews; this is not encrypted storage or a
restriction on the intended browser/keyboard pasting the code. Copy replaces the
current clipboard entry. There is no automatic copy, clipboard readback,
diagnostic capture or claim of immediate clipboard clearing when the challenge
ends. The button disappears with the active challenge, and success/failure
feedback contains no code or exception detail.

Tokens remain worker-local, are validated for the exact client, a user identity,
positive lifetime and the zero-scope response convention, then references are dropped.
The grant may omit `scope` under OAuth's unchanged-scope response rule; success
still requires Twitch's separate validation response to contain an explicit
empty `scopes` array or present JSON null. Null follows the observed response
and Twitch CLI's nil-slice/no-scopes convention; it is an experimental
interpretation, not a documented user-token schema guarantee.
Explicit null, malformed and nonempty grant scopes remain
rejected. Fixed parser-issue categories and OMITTED/EMPTY scope-field-shape
markers contain no field values and are not themselves validation results.
After client, user and expiry checks, a separate validation-scope marker
distinguishes OMITTED, NULL, EMPTY, NONEMPTY and OTHER without scope names,
runtime class names or raw values. Omitted and malformed validation scopes
remain rejected, and nonempty arrays are permission mismatches. Null is accepted
only within this empty-scope probe, after grant and identity/lifetime checks.

Refresh tokens and account names are excluded from normalized responses. There
is no token storage, session-cookie transfer, playback handoff or refresh
implementation in the original OAuth-only case.
Reference disposal is not a claim of secure memory erasure. Rotation, disposal
of the probe screen or process loss cancels rather than restoring the attempt.
Backgrounding alone does not dispose the screen: challenge expiry continues,
and token polling waits for the Activity to resume. New network I/O is gated
before sending, but an already-sent token request is not forcibly interrupted.
A background-associated I/O failure may be retried after return; a timeout
increases the polling interval. A lost token response can consume the one-time
code, so this does not guarantee recovery of approval. Ordinary foreground
failures remain terminal. After a grant, only idempotent validation may resume;
the token remains worker-local until validation, cancellation or its own expiry,
calculated conservatively from the token request start. No foreground service,
background-network permission or system-policy exemption is added.
Logs contain only fixed phase/endpoint/request-stage/network-category names,
attempt numbers, lifecycle/browser-handoff markers, numeric HTTP status and
bounded elapsed milliseconds. The last fixed failure summary also remains in
native UI memory. Exception messages, stack traces, response bodies, headers,
URLs, codes, account identities and tokens remain excluded. Categories derive
from exception types, not message parsing, and do not prove a root cause.

Successful validation proves app OAuth only, not an authenticated web player,
Turbo playback, advertisement suppression or permission to use private feeds.

### Separate native-access cases

The user subsequently approved the additional debug-only
[native access cases](native-access-experiments.md). Existing browser and
OAuth-only examples remain intact. In the new OAuth access cases only, an
optional worker-local callback receives the freshly validated own-client token
and conservative expiry for exactly one selected Twitch access query. Other
client IDs cannot use the callback. Its Authorization header is confined to
the fixed Twitch GraphQL endpoint, never ABEMA or a browser. Access outcome
is separate from OAuth status. No playlist, media, refresh operation or
persistent login is implemented.

Anonymous ABEMA checks fetch public metadata and exactly one advertised
query-free allowlisted HTTPS DASH document, never segments, keys or licenses.
Normal TLS and redirect rejection remain intact. Closed case, endpoint,
outcome and HTTP-status markers are the only new output; no raw body, signed
source, signature/value, identity or exception text is exposed. Field/marker
presence is not permission, playback evidence or a DRM configuration.

The additive 2026-10-06 ABEMA format cases preserve that DASH comparison and
add lexical standard DRM-system/common-encryption hints plus an advertised
HLS master/one-child comparison.
HLS accepts only query-free verified HTTPS `.m3u8` sources on the exact observed
CDN, first from anonymous public metadata and then optionally the master's first
regular variant. Protected/malformed masters stop before child dispatch;
the child is classified without recursive traversal. No media segments,
application secret, guest token, account/browser credential, key or license is
requested. Raw manifests and references remain worker-local, never output or
persisted. The user-selected system VPN is outside Tachiai; no proxy, tunnel,
TLS exception or region-acceptance guarantee is added.

### Later bounded native playback prototype

The separately approved [native Twitch playback prototype](native-access-experiments.md#additive-native-twitch-playback-prototype)
uses the saved LOCAL provider-profile validation gate, not browser credentials
or the own-client callback above. It explicitly fetches signed playlists/media
for one foreground-only stream, at most two minutes and remaining retention;
all earlier cases remain available. OAuth/client headers stay at the fixed access
endpoint. Media imports no cookies/session, follows no redirects, caches/downloads
nothing, and rejects encrypted/session-key playlists plus all key/DRM requests.
No ABEMA native media/license path is added.

Signed sources and provider responses stay transient; Media3 cause-chain logging
is disabled and exceptions are sanitized. Diagnostics expose only closed event/
status/rejection categories, with a narrow public CloudFront distribution-host
observation that excludes arbitrary hosts and all URL/path/query data. Manifest
404 classification reads at most 4 KiB and logs no body/error text. The strict
destination case remains intact; a separate recorded comparison permits exactly
the distribution advertised by the accepted replay master, not `*.cloudfront.net`.
Public channel/replay inputs are bounded and not persisted/logged. These private
experiments do not settle identity-sharing/account risk, Turbo or provider terms.

Additive native timing diagnostics expose normalized numeric media clocks,
window/default positions, signed out-of-window positions/live offsets and closed
state/capability markers only. They include no media item, track metadata or URI.
Timing actions remain foreground/lease/session-budget gated and do not renew
retention or playback time. Relative clamping cannot reverse direction; explicit
default-live recovery stays within the advertised window. Polls are host-bound,
and newer actions invalidate delayed observations from earlier requests.

The additive native replay-pair comparison shares the same acceptance deadline,
retention and media budget between two hosts. Failure/Stop/background releases
both and their one presentation-level audio-focus request. Member autofocus
and player-view controls are disabled only for this case, preventing a second
focus owner or playback outside group control. Focus loss cancels restoration;
gain never auto-plays. Pair seeks are bounded, capability/ad checked and expose
only numeric requested/observed offsets and fixed A/B markers. The signed
source stays transient; no token/profile storage, transport or DRM boundary is
relaxed, and no older comparison is removed.

On 2026-10-06 the user requested a seven-day LOCAL saved-login cap instead of
one hour, while keeping individual playback tests short. Existing records are
not automatically migrated or renewed on use. A separate explicit extension
case can revalidate and retain a still-available LOCAL grant longer, at most
seven days and shorter for a known positive validation lifetime. It enforces
exact LOCAL profile, official validation, 30-second/old-retention acceptance,
foreground, cancellation and current lease; expired records require private
authorization. No refresh token, private account data, approval code or media
is accessed by extension. Integer-zero expiry remains an experimental local
policy exception, not a permanent-provider-session guarantee.

### Explicit saved own-client authorization

The user requested a separate reusable-token case. Only TWITCH AUTHORIZE SAVE
retains a freshly validated own-client access token; original OAuth-only and
fresh access cases retain their discard behavior. The saved slot is bounded,
AES-GCM encrypted with an AndroidKeyStore key, bound to application/slot/version,
atomically replaced, and stored in `noBackupFilesDir`. No refresh token, account
name, provider password or browser session is stored. There is no plaintext
fallback or credential export, and no claim that key backing is hardware-backed.

Saved live/replay actions perform official token validation before every request,
enforcing exact client/user/lifetime and the existing experimental zero-scope
convention. Invalid, expired or unreadable entries cannot proceed to access;
they require explicit reconnect/Forget. A private access rejection does not erase
a token that passed official validation. These are dormant stored credentials
for explicit probes, not an active maintained OAuth session. Future persistent
product sessions still need Twitch's startup/hourly validation lifecycle.

Worker-local redacted wrappers keep tokens out of Compose, intents, logs and
saved state. Process-wide serialization/revision guards prevent a delayed save
from undoing Forget. Local Forget overwrites only this encrypted slot; it does
not revoke the provider grant or clear any browser session. Scratch bytes are
cleared where practical, but Java strings/app compromise/debuggability preclude
a secure-memory-erasure claim. Installation and tests must never inspect or
export the credential file, key or decrypted token.

### Opt-in private-access error classification

Two additional saved-token cases inspect bounded Twitch error JSON only inside
the worker, after the same official validation. Only these explicit cases read
HTTP 401/403 bodies; the earlier probes retain their original behavior. The
fixed endpoint, no redirects, verified TLS, foreground/expiry/revision gates
and cancellation remain unchanged. Diagnostic responses are limited to 16 KiB
(including HTTP 200/400), at most sixteen GraphQL errors, and bounded message
and code strings. They do not request media, playlists or new permissions.

Only known error paths are parsed. Exact allowlisted wording/codes become closed
provider-reported category enums; unknown/malformed/empty bodies become closed
shape markers. Multiple categories are retained without raw strings. Output
contains only the selected case, HTTP status, shape and category names. No body,
arbitrary field names/values, headers, exception text, account identity or
signature/value is emitted or persisted. HTTP 401 alone never diagnoses an
invalid OAuth token, and these classifications do not establish a root cause
or alter the encrypted credential. Reading an error is not a permission grant.

The later explicit blank-client live/replay comparisons reuse that same worker
and validated own-client token. They change only the private Client-ID header
to an empty value; no provider identity or credential is imported. The transport
requires an authenticated Twitch POST and diagnostic mode for this exception;
ABEMA/anonymous and earlier cases retain their original header policy. A closed
access-field-presence marker may be emitted after a successful error-free
response; signature/value remain worker-local and are discarded, never used
for playlist/media requests. No Kodi source is copied into this implementation.

## Browser hosting

- Use normal persistent site storage so the provider, not Tachiai, owns the
  login session.
- Offer per-provider logout guidance and an explicit clear-site-data action.
- Do not silently clear cookies on upgrade or ordinary playback failure.
- Do not share a WebView data directory with unrelated untrusted content.
- Keep WebView debugging disabled in shared/release builds.
- Never ignore certificate, hostname, safe-browsing, or mixed-content errors to
  make a provider page load.
- External navigation leaves Tachiai or opens a clearly identified full-site
  surface rather than inheriting provider adapter privileges.

The existing prototype also has a debug-only normal-web rendering comparison
that delegates script dialogs to Android instead of cancelling them. Console
text remains suppressed, and it does not enable screenshots, WebView DevTools,
login-page scripts or session readback. Default dialogs have separate Android
windows that do not inherit the Activity secure flag; do not capture or inspect
their provider content. The comparison requires no credential entry, is not
selected for the authentication popup/direct-login probes, and cannot relax
dialog suppression in a release build. Native dimensions and closed callback
markers are the diagnostic surface for this experiment.

### Native ABEMA request-format experiment

The separately approved 2026-10-06 experiment adds a debug-source-only
`AbemaNativeRequestActivity` in a separate `:abema_request_probe` process.
Its explicit Prepare request action reads anonymous News channel metadata and
the one advertised allowlisted DASH manifest through the existing verified-HTTPS,
no-redirect transport. An ambient Java cookie store causes refusal, not import
or deletion. There is no WebView, account login or arbitrary URL input.

The bounded namespace-aware XML parser rejects DTD/entities, malformed or
unsupported initialization, conflicting same-scope declarations and excess
elements/depth/IDs. Only standard CENC default key identifiers are transiently
consumed to build a common-system version-1 PSSH with no opaque payload. No
provider PSSH, license URL or proprietary helper is interpreted. Clear Key is
an explicit diagnostic choice based on earlier attached-browser-CDM evidence,
not a DRM selection inferred from those identifiers or permission to play.

Direct Android MediaDrm operations create one session and prepare a streaming
request, then close the session and release the CDM on the same serial worker.
The interface has no license/provisioning transport, response submission,
player, segment retrieval or key export. Not-provisioned is a terminal outcome.
Only bounded request byte count, closed request/type/destination categories,
standard JSON shape, identifier count and equality-to-input boolean category
leave that boundary. Requests, identifiers, PSSH bytes, destination URLs and
exception details are never output or saved. Clearing owned byte arrays is
best effort, not secure memory-erasure proof.

Stop/background/rotation invalidate the run and close its HTTP connection;
revision, resumed-state and 30-second acceptance checks prevent stale results.
There is no automatic resume. A blocking CDM call cannot be forcibly interrupted
or bounded by that acceptance deadline; worker-owned cleanup runs when it
returns, and a second run cannot overlap it. All new source/resources/activity
declarations are excluded from release and the separate fixture build.
Request preparation does not establish license compatibility, authorization,
response-helper feasibility or native playback.

### Authorized opaque native/browser broker investigation

On 2026-10-06 the user explicitly approved a narrow debug-only exception to
the no-provider-page-bridge rule: investigate passing one native CDM challenge
through unchanged ABEMA JavaScript and submitting its opaque response to the
same native session. This is an unsupported private-runtime feasibility probe,
not a selected product architecture or provider permission. Earlier examples
and their no-handoff boundaries remain unchanged.

The exception does not permit credential entry/collection, cookie/token export,
license/key diagnostic dumps, proprietary algorithm reproduction, media capture,
advertisement suppression, TLS exceptions or DRM/entitlement bypass. Provider
authorization stays in an isolated browser profile. Any opaque exchange must
use a separate debug-only host with DevTools disabled, exact playback-route and
document/session/lifecycle binding, bounded payloads, explicit foreground start
and cleanup. Only closed availability/preparation/submission outcomes may enter
diagnostics. Initial helper-interface discovery may use the existing inspectable
profile but must return only closed availability labels, never opaque responses.

An additive debug prototype is implemented. The shared-dash capture comparison
reached readiness and returned RESPONSE_ACCEPTED for one anonymous live News
native Clear Key session on the test phone; see the
[runtime follow-up](native-access-experiments.md#broker-runtime-follow-up--2026-10-06).
Its session closes before publishing that result. Native playback, other
content/device compatibility and durable licensing remain unverified.
A public SDK/loader was not found. A native playback experiment needs its own
bounded session-ownership design; never reuse this probe's response or closed
session for a different native session.

### Offline unchanged-helper initialization

The October 7 [offline helper-runtime experiment](native-access-experiments.md#offline-unchanged-helper-initialization--2026-10-07)
loads a hash-pinned public asset and initializes its unchanged helper factory
only, in a disposable network-disabled/read-only/resource-bounded container.
It accepts no provider identity, challenge, response or browser state and never
calls the returned processing operation. Provider exports remain inside the
context; diagnostics are fixed categories, booleans and bounded counts.
Node `vm` is not a security sandbox, so real provider code must not be run on
the unrestricted host. No proprietary module body is committed, guest/bootstrap
algorithm reproduced or live licensing boundary changed by this probe.

The additive [runtime-cached Android case](native-access-experiments.md#runtime-downloaded-cached-helper--2026-10-07)
downloads that exact reviewed public asset over ordinary HTTPS and verifies its
hash/size before atomic app-cache storage and on every reuse. Provider code is
not packaged or committed; no unreviewed version is automatically executed.
A separate debug process/profile executes initialization only under an owned
origin, exact intercepted resources, network/cookie/storage restrictions and a
CSP without inline/eval/connect/frame/worker/media permission. No identity,
challenge, response, source or native bridge is supplied. Errors/rejections
produce fixed categories only; readiness settles before the runtime is destroyed.
The public-code cache is evictable, not credential/session storage. Existing
playback/licensing cases and their bounds are unchanged.

### Native News playback comparison

After the response-acceptance result, the user explicitly directed continued
implementation toward the separate native playback case. The additive debug
comparison keeps the existing broker process/profile and exact anonymous News
page, with DevTools off and no credential entry. The default one-exchange,
request-only and original-page examples remain available.

Media3 owns a fresh Clear Key session and generates its own initial challenge;
the existing unchanged browser helper returns an opaque response to that same
session. There is one initial exchange per playback attempt. Provisioning,
renewal, retry, offline keys, alternate license transport and response reuse
are refused. The prior acceptance-probe response/session is never imported.
Media3 owns response bytes after handoff; application code must not wipe them
while the library is using them. This does not promise secure-memory erasure.

Playback uses the exact DASH URI advertised by anonymous News metadata, with
bounded HTTPS GET requests restricted to exact initialization/index/segment
files resolved from that manifest by Media3 on the same News CDN. No browser
cookies, provider credentials or authorization headers reach native media
requests. Redirects, foreign sources and DRM datasource requests fail closed.
The native budget is two foreground minutes; navigation, Stop/background and
deadline cancel the browser wait before releasing the native player/session.
The original provider player and its advertisements remain intact. Optional
original-video muting is an explicit audio-isolation control, not ad removal.
No protected video is recorded, copied or re-encoded. Native frame/audio,
licensing lifetime, ad equivalence and broader content compatibility remain
experimental until device observations establish each property.

The initial guessed `/channel/abema-news/` media root proved incorrect and is
not used for native segment requests. It remains required for the advertised
News MPD itself. Every loaded MPD is structurally preflighted and must retain
the initial public key-ID set; Media3 handles standard BaseURL/template
resolution. There are finite caps on expansion, generated paths and manifest
snapshots. One prior declared-file snapshot supports in-flight refresh work;
all snapshots close with playback. No directory or whole-CDN wildcard is used.
Finite dynamic indexes are intersected with the advertised history window when
its epoch/depth are known; their entire closed-period history is not admitted.
Actual segment boundary times exclude expired/beyond-lookahead timeline tails.
For dynamic indexes, at most the two-minute attempt's future segment numbers
are generated for URL admission; this neither fetches nor makes future media
available. Actual requests still follow Media3 and the foreground budget.

This debug comparison explicitly substitutes direct UTC timing from the phone's
wall clock in the native manifest model, preventing Media3's implicit SNTP or
external HTTP clock synchronization. That clock's accuracy is an experimental
assumption. The original browser manifest/player is not modified. No extra
clock endpoint, credential, advertisement or DRM behavior is thereby authorized.

### Additive free replay source comparison

The user approved a separate native free-sumo replay case, and subsequently
requested native live/live and replay/replay pairs. The first replay prerequisite
is an explicit debug source-observation extra in the existing isolated broker
process. Only `https://abema.tv/video/episode/394-72_s10_p8529` is admitted;
the News and earlier modes remain unchanged. Unchanged original dash.js
`initialize`/`attachSource` calls are observed without changing their arguments,
receiver, results or original playback. Only closed source-shape/CDN categories
leave the observation boundary. No query-bearing source, cookies, opaque
payload or source URL is logged or persisted. A changed selected source revokes
the one-player binding. This prerequisite does not fetch native replay media
or issue a native license request. Native replay remains unverified.

The user subsequently approved a separate replay-native flag that can hand off
the exact original selected signed manifest URL transiently inside the same
application/device. It admits only the exact DS VOD CDN candidate and the fixed
episode identity; the older source-only and News flags remain unchanged. The
candidate comes from [maintainer-authored public integration configuration](https://git.drmlab.io/Mike/Unshackle-Services/src/branch/main/AbemaTV/config.yaml),
not an official API or proof of the current source. Runtime `DS_VOD_AKAMAI`
classification must match before native fetching. Unknown sources fail closed.
No URL/query is logged, persisted or reconstructed; browser cookies and headers
are not copied. The query is not appended to segments automatically. Native
requests use ordinary verified HTTPS/no redirects and only exact MPD-declared
files on the same exact CDN, under the existing two-minute foreground budget.
The same fresh native CDM and unchanged one-initial-exchange browser helper are
used; source/DRM changes are not permission to retry or broaden this policy.
The exact source/readiness is checked again after the initial MPD fetch and
before native player construction. Source binding is a startup/handshake check,
not cancellation of an already-sent fetch at the instant the provider changes.
Source replacement revokes handoff while its observer is installed. After the
one-shot response is consumed, helper hooks restore; same-document transitions
thereafter are not detected by that observer and remain an unresolved limit.

The additive native LIVE/REPLAY pair uses these same ABEMA source/license
boundaries and the existing same-device encrypted Twitch LOCAL grant. Twitch
validation/access run in the broker-process worker, not in the WebView. Stored
grant identity is checked before validation/access, on the native loader with
at most one disk/decryption check per second, and by a five-second foreground
worker poll. The shared budget sees cheap state only. Cross-process Forget or
replacement is bounded detection, not atomic revocation; no store migration or
new authorization is automatic. Both players prepare paused under a shared
foreground deadline and one presentation audio-focus owner. The user approved
raising only these pair experiments to five minutes on October 7 to allow
coordinated listening; earlier single-player/same-Twitch cases keep two minutes.
The cap still starts at native preparation, never renews, and ends immediately
on background, Stop or invalid authorization. Joint
Play waits for original ABEMA pause/mute confirmation; a newer Pause, seek,
focus loss or teardown invalidates that asynchronous Play completion. The
original WebView/player stays attached and none of the licensing limits expand.

The October 7 refresh-refusal diagnostics add only closed parser reason enums
and bounded structural counts, emitted on summary changes. A refusal's counts
describe the visited prefix, not the entire document. No raw XML, identifier,
hash, attribute value, URL, opaque payload or exception text is logged. Parser
acceptance, initial ID-set binding and licensing/source rules remain unchanged;
absence of default IDs does not establish clear media or permit a fallback.

The separately approved `LIVE_TRANSITIONS` comparison permits only a wholly
declaration-free refresh after an initial protected native manifest. It adds a
bounded local-name/namespace protection-marker audit and initial-baseline-known
scheme/value/scope binding; new protected IDs, protection shapes or marker
shapes refuse. Before publishing declared media paths, the declaration-free
branch also requires every parsed Media3 representation to lack DRM init data.
No declaration or period is stripped, and absence of declarations is not proof
of unencrypted samples. Opaque payloads remain unread. The policy is transient
and owned by one serial manifest loader; only closed verdicts are logged.
Its opt-in five-minute DRM-session keepalive preserves the existing session
across clear intervals, not a cached response or second exchange. Player release
on Stop/background/error/deadline still releases it. The older strict cases,
clear-sample/key requirement, single exchange, no renewal/provisioning, source
binding, exact-file policy and unchanged original helper remain intact.

The later `LIVE_TRANSITIONS_ALIGNED` and fixed 130-second `..._LATE_START`
comparisons add a one-shot arm for the **unused initial** helper only. The old
two-minute capture deadline still must be live and READY. Native preparation
supplies at most its remaining five-minute foreground budget; the helper's
independent ceiling is document start plus seven minutes, with no rearming or
revival after use/expiration/Stop/pagehide. Native run/browser/exact-route guards
and a three-second boolean arm acknowledgment fail closed. The late start uses
the existing budget and resolves native sources/Twitch leases only afterward.
Pending starts cancel on lifecycle/Stop and cannot restart in the background.
No response cache, renewal, second exchange, provisioning, helper algorithm,
URL/source/identifier logging or provider authentication is added. Existing
strict and unaligned examples retain their two-minute helper lifetime.

The separate `..._ALIGNED_PREWARM` News comparison retains that same one-shot
exchange and requests standard Media3 initial preacquisition only after the
entire real-manifest parser succeeds, including declared-file publication and
an active-budget check. Its synthetic initialization format contains only the
already validated source IDs in the existing common PSSH, with no license URL;
actual MPDs/track formats are unchanged. The same non-multisession manager owns
ordinary playback and prewarming. Source preparation/release is forwarded
one-for-one; one held reference is dropped on final source teardown and cannot
rearm. Media3 queues initial work on its playback thread and guards release.
The broker is sealed before player release. Scheduling is not keys-ready;
only closed prewarm/request/keys/error markers are output. Session eviction can
still occur, but a second exchange remains refused. No response/key cache,
provisioning, renewal, offline license or provider-algorithm change is added.

Additive relative-control cases reuse these same fixed sources and boundaries.
The generic planner reads normalized timing/capability snapshots only, selects
one full A movement or opposite B movement before dispatch, and uses the
existing joint hold/seek/check/resume transaction. No unrelated timestamps are
subtracted. Partial steps, unavailable windows, reported ads and busy actions
refuse; uncertain platform/settlement failure never triggers another side's
seek. Mixed live/replay cases independently select the already bounded Twitch
source type and ABEMA policy; they do not accept arbitrary provider routes,
tokens, sources, helper lifetime or recovery inputs. Chosen side/movement and
closed result enums are the only new diagnostic values. Old examples remain.

Explicit Catch up A/B in those relative comparisons calls only the selected
live player's existing SDK default-position command after holding both. It
cannot become an automatic nudge fallback or request new source/auth/license
data. Any possible default dispatch invalidates the prior timing anchor;
bounded READY/current-window observation leaves both paused for explicit Play.
No reload, retry, renewal or foreground-budget extension is added.

The additive [preliminary native viewer](preliminary-native-viewer.md) accepts
only those four relative cases and reuses their exact source/auth/license
policies and guarded joint Play. Two TextureView-backed video surfaces permit
overlapping layout, not frame capture or protected-output export. The original
WebView remains attached/nonzero behind the viewer and available through setup.
Only the viewer Activity handles rotation/screen size in place; it does not
renew the five-minute foreground budget or recreate the helper/player group.
Other teardown conditions remain terminal. Audio controls map app-owned
overall/mix/mute state to existing bounded gain setters; no new browser or
opaque-message inspection is added.

The October 7 [product-flow prototype](prototype-ux.md) composes two independent
slots from the same four fixed source kinds, including duplicate selections.
Its dedicated `:prototype_player` process/profile does not import historical
browser sessions. Each ABEMA slot has its own unchanged helper and fresh native
CDM, with one initial exchange per slot and no cross-slot response reuse. Open
viewer explicitly starts a shared five-minute foreground budget, including
preparation; every original web player must confirm paused/muted before joint
Play. Failures/background/Stop close both. This is a new UX over unsupported
debug-only adapters, not a release integration or expanded provider permission.

The subsequent no-page startup uses a different `:prototype_cached_player`
process and `prototype-cached-player` profile, keeping the original case as a
comparison. A tiny owned document serves four pinned, runtime-downloaded public
code assets. The user subsequently requested removing our own CSAI-adapter
prerequisite. Its fifth-chunk preflight no longer runs during startup; existing
public-code cache files are not deleted. This debug path attempts the originally
selected direct replay content without an ad/analytics driver, Google SDK, ad
requests or fabricated ad-completion/tracking events. It does not claim equivalent
provider ad behavior or permission for distribution. It executes selected module factories, not application entry
callbacks, and delegates provider algorithms unchanged. The only network routes
admitted are reviewed guest, media-token, fixed replay-metadata, playback-resource
and initial-license APIs over verified HTTPS. Redirects, login pages, forms,
native JavaScript bridges, popups, permissions, remote scripts and original
media elements are unavailable. The same-origin guest request may create normal
provider cookies in this isolated profile; no cookie value is read, copied or
sent to the native player. Account passwords and existing sessions are not used.
Transient identities and source/configuration values stay within the runtime;
only the exact selected manifest and fresh opaque CDM response cross the narrow
native boundary. The response helper's two arguments are the encoded fresh
provider envelope and that guest identity. No algorithms, keys, private response
bodies or provider assets are added to repository/APK. Logs contain only closed
stage/state categories, module counts/IDs and HTTP status. Actual access/DRM
rejections remain fatal. Server-stitched session URLs and unknown source modes
still fail when source resolution is not implemented; an ad-mode label alone is
not treated as evidence of a server-enforced completion receipt.
The existing shared foreground budget and one-exchange-per-slot limits remain.

The user subsequently approved the single-label `*-abematv.akamaized.net`
media-host family for this cached prototype only. Source path/query restrictions
and exact manifest-declared membership still apply; other network routes and
historical cases are unchanged. The explicit diagnostic exception is a bounded
no-backup origin-review journal: timestamps, decision/stage/rule enums and
`https://hostname/<redacted-path>` patterns, never signed paths or queries.
Unknown eligible origins are recorded PENDING and blocked before media access;
no journal entry grants permission. Explicit user approval/rejection is recorded
in [media-origin approvals](media-origin-approvals.md) and implemented as reviewed
compiled policy. Journal corruption/write/capacity failures stop the run.

The transport-wait follow-up adds only five boolean observations: callback
occurrence, preconfiguration order, existing exact configured-request match,
strict optional player association and standard top-document encrypted-event
occurrence. No additional URL getter, request field, body, header, initData,
identifier or opaque response is inspected/exported. The captured reference
clears on Stop; late callbacks/events cannot revive the helper. These bits are
diagnostic history, not readiness or proof of sample protection. The exact
boolean-only native schema refuses other shapes. Licensing and timers remain
unchanged.

### Explicit ABEMA playback inspection

The user authorized an inspectable ABEMA WebView experiment on 2026-10-06.
The `debug` APK alone adds `AbemaInspectionActivity` in the existing application,
with a separate `:abema_inspection` process and `abema-inspection` WebView data
directory. This avoids enabling the process-wide debugger for ordinary provider
or authentication panes. It requires Android 9/API 28 for profile isolation and
does not fall back to the ordinary profile on older devices. The diagnostic
profile persists across runs; no account cookies are imported, and no site data
is deleted. Provider-created guest/session state may still exist in this profile.

The native warning identifies broad DevTools access and prohibits credential
entry. Only fixed News and known sumo replay pages may navigate top-level;
popups, external intents and other routes close or refuse the experiment.
Protected-media permission remains narrowly available to ABEMA, while other
permissions are denied. TLS, safe browsing, file access and mixed-content rules
remain intact. The APK adds no page scripts, native bridge, license helper, body
interception, key access or native playback handoff. The optional startup
collector exception below adds its own temporary observer. Debugging is disabled before
teardown on pause, close, blocked navigation or renderer loss. Release contains
neither this activity nor its resources; the existing browser laboratory is unchanged.

DevTools is not a metadata-only security boundary: while enabled, the authorized
local debugger can inspect sensitive page state. Route guards cannot prevent a
provider from presenting inline account UI on a playback route. Do not enter
credentials, inspect account UI, request cookies or response bodies, export HAR,
or dump raw protocol events. The repository's `scripts/abema-inspection.py`
collector emits only closed license-host route markers (including an “other”
route label), aggregate network counts, setup confirmation, HTTP status and
response-type classifications, stops at non-playback navigation, and has bounded
duration/output. The later user-approved coverage extension recursively attaches
only to related dedicated workers with ABEMA asset/blob origins and child frames
on the same fixed playback routes. It does not discover browser-wide targets,
attach service/shared workers, pause JavaScript or inspect refused targets.
Optional media diagnostics emit only closed event categories, CDM/key-system
classification, reported encryption schemes and a small set of booleans; player
identifiers, source URLs, titles, track details, messages and error data are not
output. Target/session and media-output ceilings are separate from the license
event ceiling. Unknown or unsupported coverage is not evidence of absent DRM.
Raw CDP messages can contain sensitive metadata transiently in
the collector process; they are not saved or printed. The collector does not
send challenges, replay licenses or inspect their contents. This exception is
for the separately selected playback experiment, not general account inspection.

The additive `--handshake-metadata` option also reports closed resource/API/
license/other-provider origin categories, HTTP method categories, post-data
presence (not its content), response status/MIME and bounded event order.
It sends the same CDP commands as the default collector: no Runtime, page
injection, body/header readback or new native bridge. It emits at most 64
sequence markers and retains at most 64 internal session-scoped request IDs;
aggregate counts continue after output saturation, with tracking limits marked.
Origins do not prove business operations, and omitted/limited traffic does not
prove absent authorization or licensing. Paths, queries and hostnames remain
absent from diagnostic output.

The independent `--handshake-role-metadata` option adds a finite exact-host
configuration-role map and closed resource, media-token-candidate, DASH-license,
HLS-license or other route labels. License labels require a known license host;
resource/media-token candidates require their statically observed GET/path shape.
The media-token classifier checks only the five fixed raw query names, never
their values. Output contains bounded path-segment count, query-presence boolean,
method/post-data presence, status/MIME and boolean-or-unknown cache indicators.
Missing indicators remain unknown; false disk-cache alone does not prove a fresh
network exchange. Request/cache correlation is session-scoped and resets on
redirect. Role/route agreement is not exact URL or response-body identity proof.

Each of seven roles has a 16-marker output budget and 16 tracked-request ceiling;
other-route traffic has a four-marker/four-request sublimit, preserving room for
recognized routes. Limit notices and aggregate route counts distinguish output
saturation from zero observations; failures count only retained correlations.
This flag sends no additional CDP commands and reads no bodies, cookies, request
headers, tokens or license data. Static configuration roles and observed HTTP
success do not establish native authorization, entitlements or helper permission.

The user subsequently authorized startup candidate/support diagnostics. The
separate opt-in `scripts/abema-eme-startup.py` enables Runtime events and installs
`abema-eme-startup.js` before an ordinary reload of the selected original page.
Its main-world observer is active only on the exact two playback routes in the
top frame, without query/fragment. It passes EME capability calls through with
the original receiver/arguments and returns the original promise; no request
configuration, fulfilled object, rejection reason or DRM-session method is read.
It emits only a fixed key-system category and request/accept/reject/throw result,
with at most 16 calls. Only nonce-tagged primitive console strings from the
verified default main-frame execution context are accepted, capped at 35 unique
markers. Setup aborts on forbidden navigation; reload is bound to the last
verified loader when available, and additional main-document navigation stops
the run rather than reusing request IDs. Other console arguments, previews and
stacks are suppressed. Page markers remain untrusted diagnostics, not an
authentication/security boundary.

This is observational instrumentation, not perfectly passive collection: it
changes method identity and adds promise handlers, affecting unhandled-rejection
bookkeeping. It neither changes capability results nor forces a DRM selection.
Accepted capability requests do not establish the selected/attached playback
CDM. The wrapper restores its captured method on timeout, pagehide or the next
out-of-route call, without overwriting a later provider replacement. Timers can
be throttled; per-call/outcome deadline checks independently suppress late
observation. Future-document registration removal is acknowledged, but does
not restore the current document; close the isolated inspection activity after
every run or failure to destroy it. No profile/site data is cleared. The older
metadata collector remains unchanged and does not inject scripts or enable
Runtime. Neither tool pauses JavaScript or requests bodies, cookies, keys,
source, configuration objects or native handoff.

### Inspectable browser laboratory

The `diagnostic` Android build type is a separate, deliberately inspectable
application: `net.fstab.tachiai.diagnostic`, labeled **Tachiai Browser Lab**.
It uses the shared development signing identity, never a production key. Its
separate application ID provides separate app-private browser storage; it does
not import the regular application's provider sessions. The diagnostic manifest
removes the provider application's activity and registers only the fixture
launcher, so ordinary debug intent extras cannot enter an authentication probe.

The laboratory enables WebView DevTools, permits screenshots by omitting our
`FLAG_SECURE`, and uses normal browser script dialogs. Those differences are
intentional diagnostic capabilities, not production defaults. The visible
banner identifies this mode. Its only content is a credential-free app-owned
layout/dialog fixture; remote resources, other packaged assets, external
navigation, popups and permissions remain unavailable. It contains no native
JavaScript bridge and does not collect entered prompt text. TLS, safe-browsing,
mixed-content and file-access protections remain unchanged.

Ordinary `debug` paths and the `release` APK retain their existing provider protections
and do not contain the laboratory activity/assets. The separately selected debug
ABEMA exception above does not extend this laboratory. Debuggability is process-wide,
not an authentication-safe per-pane toggle. The laboratory must not be extended
to live authentication inspection as a workaround for a rejected tool action.
It verifies hosting and diagnostic access, not Twitch's actual page layout,
authentication compatibility, or a solution to its transient blank rendering.

## Script and bridge isolation

A provider page must not receive a broad `addJavascriptInterface` object. A
compromised page or provider-side cross-site-scripting bug could otherwise call
native methods with the application's authority.

Prefer evaluating small commands into an allowlisted page and reading narrow,
structured results. If asynchronous page-to-native messages become necessary,
validate the source origin, frame, message version, command set, and payload
size before accepting them.

Shared provider scripts must be packaged with the application or loaded from a
project-controlled, integrity-protected source. They must not be updated from
an arbitrary remote URL merely to repair a selector.

## Media boundary

Focus mode changes layout around the original provider player. It does not
capture decoded frames, remove DRM, record segments, suppress advertisements,
or retransmit content.

Screenshots and screen recording may yield protected black surfaces; Tachiai
must not attempt to bypass that behavior. Diagnostic capture should be limited
to Tachiai UI and non-sensitive textual state.

## Distribution trust

People installing a sideloaded APK must trust the binary that renders provider
login pages. Keep the project source available, make builds reproducible where
practical, publish checksums, use a stable signing identity, and clearly label
debug or experimental builds. Never commit signing keys.
