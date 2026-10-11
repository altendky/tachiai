# ABEMA guest preparation and metadata verification

Issue [#150](https://github.com/altendky/tachiai/issues/150) adds an explicit
debug observation entry for a fresh ABEMA guest. It is a prerequisite experiment
for the account work in [#100](https://github.com/altendky/tachiai/issues/100) and
[#103](https://github.com/altendky/tachiai/issues/103), with no existing-account
connection, renewal or playback implementation. Issue
[#155](https://github.com/altendky/tachiai/issues/155) adds a separate explicit,
memory-only guest profile verification action. Its bounded provider observation
is recorded below; existing-account linking remains separate.
Issue [#157](https://github.com/altendky/tachiai/issues/157) adds a separately
explicit guest-authenticated check of one fixed episode. Its bounded saved-route
acceptance is recorded below; configured-item refresh in
[#153](https://github.com/altendky/tachiai/issues/153) uses a separate common-catalog
consumer described below. Existing-account access remains separate.
Issue [#160](https://github.com/altendky/tachiai/issues/160) adds a separate explicit
memory-only device-authorization challenge check and discards the response.
Provider-controlled account approval and grant/session handling remain separate.
Issue [#163](https://github.com/altendky/tachiai/issues/163) adds a distinct explicit
pre-approval device-token response observation. It makes one request and discards
the response; it does not implement an account connection or a polling loop.

## Implemented boundary

`AbemaGuestBootstrapActivity` uses its own `:abema_guest_bootstrap` process and
`abema-guest-bootstrap` WebView profile, configured before any WebView API. Opening
the activity reads saved provider configuration only. It ignores external extras;
the user selects an existing ABEMA instance and explicitly taps **Prepare guest**.
The separate **Verify guest profile** action captures an immutable profile
operation before preparation and starts its own fresh guest. Neither action
starts automatically, accepts an operation/identity from an Intent or connects
an existing account.
**Verify guest episode metadata** likewise captures an immutable episode
operation and uses only the fixed public episode `394-72_s10_p8529`. It accepts
no resource argument or external extras and starts a separate fresh guest.
The separate **Verify device authorization challenge** action prepares its own
fresh guest and bound profile, requests one challenge, then discards it. It does
not show a code, open an approval browser or establish an account connection.
Provider names and saved route titles are shown; no account identifiers or grant
values enter the UI. The activity does not alter provider, route or configured
source records.

The selected instance's UUID, ABEMA service, saved route mode/connection ID and
profile fingerprint are captured and reread on workers. A missing imported route
fails; it cannot become System access. The narrow guest route purpose admits
`abema.tv` only. Public-code downloads use that route and the existing reviewed
four-chunk size/SHA-256 cache. Cache files contain public code only. All four
unchanged chunks register factories; application/runtime callbacks do not run.
Only the guest dependency path and, for profile/episode verification, the unchanged
configuration module are invoked. There is no source/CDM/license helper,
media element, provider page or account form.

The process-owned proxy is installed before loading the owned document. Its
policy admits exact owned assets and one
`POST https://abema.tv/api/auth/login/guest`. The app-owned orchestration calls
unchanged provider application-key and DTO modules, creates a fresh UUID and
uses a cookie-free, no-store, redirect-refusing request. Response reading is
bounded to 64 KiB; a strict flat guest DTO rejects duplicated or unknown fields,
trailing data, malformed UTF-8 and invalid identity/token types and bounds. The
only admitted response fields are `user_id`, `access_token` and optional
`refresh_token`; refresh is discarded rather than adopted. An upstream schema
change must fail for review.

Retained device/application/grant/profile session state stays in the JavaScript
closure. Diagnostic polling exposes only fixed status/failure categories. Platform
request objects necessarily carry bearer headers and private profile URIs
transiently; admission never reads/copies GET headers and projects only the three
fixed CORS preflight fields for OPTIONS. The profile URI is retained only as
bounded attempt-owned policy bookkeeping. Private request data is never logged,
persisted or displayed.
The debug diagnostic tag
reports the selected route-mode enum and closed outcome, without provider names,
route IDs/configuration or guest fields. No JavaScript interface
is installed. Cookies, DOM storage, browser cache, file/content access, popups,
permissions, forms, frames and media are disabled. Existing browser cookies are
never inspected, copied, deleted or reused. String references are released on
stop; complete heap wiping of immutable JS strings is not claimed.

Foreground/revision/deadline and worker ownership checks guard dispatch and
publication. One attempt has a 45-second preparation budget; its guest fetch is
limited to 15 seconds. Stop/background invalidates admission, aborts pending
requests and destroys the WebView before clearing the proxy. Route/backend
cleanup completes on workers. A missing cleanup callback, failure or timeout
blocks further attempts in that process, including activity recreation. A late
success cannot reopen admission. If WebView destruction cannot be confirmed,
the process retains that context and its selected override/backend: clearing to
System would be unsafe while JavaScript might still run. It does not dispatch
route clear, automatically close that backend or kill the process. Old-context
callbacks cannot cancel a replacement run; callback removal, cleanup admission
completion and outcome publication occur in one main-thread turn. Successful preparation also discards the guest
and ends the runtime before reporting **Guest prepared**.

## Optional guest profile binding

Static inspection of the pinned unchanged
[first-party 5206 asset](https://abema.tv/assets/compat/3850f3e68c3aa3c0.5206.js)
(SHA-256 `d342d230d1f24a90a576597b673990a412a94bb5465cfa9b7567a9dc04e85b81`)
identified the configured API origin in module 74284, the profile GET in module
67578, nested wire identity `profile.userId` in module 33673 and profile-derived
future client identity in module 50093. The mapped profile has no verified
guest-versus-registered flag. This is static first-party implementation evidence,
not a documented external account API or proof of intended-account consent.

Profile mode reads that unchanged configuration module, requires exact origin
`https://api.p-c3-e.abema-tv.com`, and makes one
`GET /v1/users/{freshGuestId}` with the fresh guest bearer before teardown. The
separate route purpose admits only `abema.tv` and that profile host. Owned request
policy admits the original assets/guest POST plus a normalized bounded identity
path, one GET and at most one preceding CORS OPTIONS for that same path. OPTIONS
requires the owned origin, requested GET method and only the authorization
request header. Cached preflight can omit OPTIONS. Guest-only policy/CSP remains
unchanged; challenge, renewal, collection, media and legacy API origins remain
refused. No automatic retry or cookie-bearing fallback is implemented.

The profile request uses CORS, omitted credentials, no-store and redirect refusal,
with a 15-second fetch budget inside the original 45-second attempt budget. Its
body is limited to 64 KiB, strict UTF-8, 16 nesting levels, 4,096 JSON nodes and
1,000 items per array. Duplicate keys, including escaped spellings, malformed or
missing identities, trailing content and mismatched `profile.userId` refuse
verification. Parsing projects only the matched bounded identity; names,
images, subscriptions and profile body are not adopted into session/UI state.
The matched guest ID is retained only as a JS-owned prospective client binding.

The fixed **GUEST_PROFILE_BOUND** result is published after the existing worker
owner reread and confirmed runtime destruction/route cleanup. Stop, background,
deadline, stale callbacks and poisoned-cleanup behavior apply equally to both
operations. Native diagnostics expose fixed failure categories only, including
profile HTTP/response/timeout refusal. No provider account grant is persisted,
Connected status published or account-backed capability enabled. Successful
teardown discards all transient guest/profile references.

## Optional device-authorization challenge verification

The same retained first-party 5206 asset supplies the device-authorization
endpoint in module 24968, the unchanged request DTO in module 2953 and the
profile-derived client identity used by QR controller module 22590. The wire
request is JSON containing only `client_id`. The provider's shared driver
attaches the current bearer but also performs retry/recovery and reads refusal
bodies; this one-shot operation uses Tachiai's bounded fetch and the unchanged
pure DTO instead of that driver. These are static first-party findings, not an
official third-party API or proof of cookie-free acceptance.

`VERIFY_DEVICE_AUTHORIZATION` first prepares one fresh guest, then makes the
profile GET and verifies `profile.userId` agrees with that guest. Only after
agreement does it serialize the bound client identity and make one
`POST https://abema.tv/api/auth/oauth/device/authorization`, with the fresh guest
bearer and JSON Accept/Content-Type headers. Its distinct
`ABEMA_GUEST_DEVICE_AUTHORIZATION` route purpose admits only the already reviewed
`abema.tv` and modern profile host. The owned policy and CSP add only the exact
challenge POST after the guest POST and one profile GET, with at most one
matching profile preflight. Existing purposes remain unchanged. Cookie, cache,
redirect, retry and renewal handling remain refused; the token-polling endpoint
is not admitted.

The challenge response is limited to 64 KiB and strict UTF-8/JSON validation,
including escaped duplicate keys and unpaired surrogates. It must be a flat
object with exactly `device_code`, `user_code`, `verification_uri`,
`verification_uri_complete` and `expires_in`. Codes must be bounded nonempty
visible ASCII without controls: at most 2,048 characters for the device code
and 64 for the user code. No guessed provider code format is imposed. Expiry must
be a positive integer of at most 3,600 seconds. Both URIs must be at most 2,048
visible ASCII characters and canonical HTTPS `abema.tv` destinations without
credentials, explicit ports,
fragments, controls or malformed encodings. Neither URI is fetched, launched or
exported. The 200-only success status, expiry ceiling and destination restriction
are conservative local acceptance rules: actual provider status, lifetime and
destination schema remain runtime checks rather than verified navigation rules.

Each fetch retains its 15-second limit. The original 45-second monotonic
operation deadline also guards the challenge sequence; challenge expiry never
extends that deadline. Private response fields and guest/profile references are
discarded in JavaScript. Only fixed **CHALLENGE_VALIDATED_AND_DISCARDED** can leave
the runtime, after the native owner reread and confirmed destruction/route
cleanup. Strings cannot be reliably wiped from the heap. Cancellation, owner or
foreground loss, expired completion and uncertain cleanup prevent success;
existing poisoned-process retention applies.

No challenge export API, browser launch, token polling, account adoption,
protected grant persistence, Connected state, collection/history capability or
playback/license handling is added. The episode service and its public-metadata
IPC retain their existing contract. Intended-account approval, verified polling
errors, account profile agreement and expiry/revocation/renewal remain
[#103](https://github.com/altendky/tachiai/issues/103).

Verification on 2026-10-11 passed 1,948 JVM tests, 35 direct JavaScript fixtures,
lint and release isolation. Eight selected Android fixtures passed in the
isolated guest process with networking blocked, including the actual owned
challenge JavaScript and existing metadata IPC. The temporary instrumentation
process override was removed, and the ordinary test APK was rebuilt, verified
and restored before the real request. Both APKs match the shared debug signing
identity.

One explicit challenge attempt on persistent `tachiai-dev` (`emulator-5580`),
Android 16/API 36, Google APIs x86-64 and WebView 133.0.6943.137 completed through
the selected saved connection in 8.255 seconds. Its fixed outcome was
**CHALLENGE_VALIDATED_AND_DISCARDED**, published after confirmed cleanup; native
controls were re-enabled. This establishes one accepted HTTP 200 response
matching the locally bounded five-field/code/URI/expiry shape. The egress region
was not independently remeasured. The account state remained a transient fresh
guest; no existing account was linked, and no media or license request occurred.
No private code, returned URI or exact provider expiry was exported, recorded or
navigated. Encrypted saved configuration and grant records remained
byte-for-byte unchanged; the normal app entry was restored afterward.

This observation does not establish account approval, polling, returned-account
identity, expiry/revocation/renewal or cross-device behavior. Those remain in
[#103](https://github.com/altendky/tachiai/issues/103).

## Optional pre-approval device-token observation

The separate **Observe one device-token response** action prepares a fresh guest,
verifies its matching server profile and obtains one strictly validated challenge.
Only this operation retains the private device code and challenge lifetime in
its JavaScript stack until the request ends. It uses the unchanged pure token
DTO in module 2953 of the pinned 5206 asset, as invoked by first-party QR
controller module 22590. The original guest bearer, profile-bound client ID,
device code, device-code grant type and numeric WEB device type must agree in
both the DTO and independently checked serialized four-field JSON body.

Its distinct `ABEMA_GUEST_DEVICE_TOKEN_OBSERVATION` route purpose and owned
policy admit the existing guest/profile/challenge sequence followed by exactly
one `POST https://abema.tv/api/auth/oauth/device/token`. Existing guest, challenge,
metadata and playback purposes remain unchanged. The token fetch has a
15-second limit within the original 45-second monotonic operation deadline.
Admission also checks a conservative challenge deadline measured from challenge
request dispatch. Cookies, cache, redirects, repeats, recovery and renewal remain
refused. No returned verification URI or approval page is opened.

A separate refusal reader bounds bytes to 64 KiB and uses strict UTF-8 and the
existing bounded duplicate-free JSON parser. It recognizes only flat subsets
of `code`, `message` and `reason` with reviewed types. Message contents are
discarded. A predefined field-set category and exact numeric or symbolic
code/reason vocabulary can survive; unknown fields, wrong types, unknown values,
malformed or oversized bodies produce fixed categories. Numeric strings are not
coerced. The vocabulary comes from access-token recovery module 22246; it does
not by itself establish device-token pending or terminal semantics. HTTP status
is categorized independently, and HTTP 400 never implies approval pending.
Unexpected 2xx responses are aborted without touching their body, parsing a
grant or adopting account state.

Native code independently validates the six-field observation against finite
allowlists and operation-specific combinations. Only the joined closed category
can reach UI/logs, after matching-owner, foreground and deadline checks and
confirmed runtime/route cleanup. No private identity, code, URI, token, provider
message or raw response leaves JavaScript. The original challenge-and-discard
operation and catalog metadata IPC retain their contracts. String references
are released on teardown; complete heap wiping is not claimed.

No polling loop, saved grant, Connected status, account collection/history,
playback or license handling is added. Verification on 2026-10-11 passed 2,005 JVM
tests in 239 reports, 46 direct JavaScript checks, lint, release isolation,
instrumentation compilation and both signed APK assemblies. Ten selected
synthetic guest/metadata Android fixtures passed with networking blocked,
including token refusal and unexpected-success body avoidance. The temporary
guest-process instrumentation override was removed; the ordinary test APK was
rebuilt, verified and restored before actual acceptance. Both APKs match the
shared debug signing identity.

One explicit pre-approval attempt on persistent `tachiai-dev` (`emulator-5580`),
Android 16/API 36, Google APIs x86-64, WebView 133.0.6943.137 completed through
the selected saved connection in 10.795 seconds. Its fixed outcome was
**TOKEN_OBSERVATION_HTTP_400_FLAT_CODE_MESSAGE_CODE_AUTHORIZATION_PENDING_REASON_ABSENT**,
published after confirmed cleanup; controls were re-enabled. This establishes
one visible HTTP 400 with a strictly typed flat code/message response whose code
matched the reviewed authorization-pending vocabulary and whose reason field
was absent. Provider message text and private fields were discarded. No approval
page was opened, account grant adopted, media/license requested or saved record
changed. The account context remained a fresh transient guest; egress region
was not independently remeasured. Normal app entry was restored afterward.

The host OOM killer terminated the previous emulator before deployment; after
the build completed, normal startup of the same persistent AVD succeeded and
encrypted records still matched the earlier baseline. Memory growth remains
the independent investigation [#166](https://github.com/altendky/tachiai/issues/166),
not a claimed provider failure. The bounded successful observation does not
qualify denied/expired/revoked responses, account approval or durable session
lifetime. Those remain [#103](https://github.com/altendky/tachiai/issues/103).

## Optional exact episode verification

Module 74284 of the same pinned first-party asset supplies the coherent current
API origin. Static DTO module 91060 supplies raw program `id` and nested
`episode.title`. A separately verified coherent current public revision, distinct
from the pinned guest chunks, establishes an actual bearer-authenticated program
caller at the same API origin. Its module 62073 allows an omitted division query;
the primary episode gateway additionally supplies provider-derived division and
`include=tvod`. Neither static path establishes current guest access or entitlement.

The current entry is
[9298](https://abema.tv/assets/compat/4ad4fadda744ab29.9298.js), SHA-256
`34207e8d8ff54721927e7dc93edcdfa20c2af99e50c66c8ad5defe5e8e0f03e5`.
Its coherent terminal chunks are
[417](https://abema.tv/assets/compat/5c2204aa07c3f44b.417.bundle.js), SHA-256
`dcd0df95575f3059425e44f43ac4983a9c5c425667608fce93af9d8a631e37f2`, and
[9361](https://abema.tv/assets/compat/de87d72968864070.9361.bundle.js), SHA-256
`3458c695f85106b211f3d23d8554a2442f2e968ced0d0d427d5d751a8602a0fd`.
The observed cross-origin CORS transport uses the active bearer and defaults to
same-origin credentials, which exclude browser cookies for this API. No extra
application-signature/client-ID header or registered-account-only guard was
found in these callers. The provider wrapper can renew after 401 and retry once;
that behavior is excluded from this one-shot operation. Current chunk hashes
are not mixed into the older pinned guest runtime.

`VERIFY_EPISODE` requires that unchanged configuration module's exact modern
origin before preparing the guest. It then makes one cookie-free, no-store,
redirect-refusing CORS GET to
`https://api.p-c3-e.abema-tv.com/v1/video/programs/394-72_s10_p8529`, using the fresh
guest bearer. The distinct `ABEMA_GUEST_EPISODE` route purpose permits only
`abema.tv` for guest/assets and the modern API host. Its owned request policy and
CSP admit only the original document/assets, one guest POST and that exact
episode GET, with at most one preceding same-path OPTIONS under the same strict
preflight rules. No profile, second episode, fallback, retry or renewal request
is admitted. Guest-only/profile purposes remain unchanged.

The episode fetch has its own 15-second limit within the original 45-second
attempt. Its streamed body is limited to 512 KiB, strict UTF-8, 16 nesting levels,
4,096 JSON nodes and 1,000 array items. The shared strict JSON reader preserves
the original profile's 64 KiB bound and validation behavior. Episode validation
rejects duplicate or escaped-duplicate keys anywhere, malformed/trailing JSON,
unpaired surrogates in any string, a mismatched raw `id`, and a missing/unsafe
nested title. The title must be trimmed, 1–160 UTF-16 code units and free of Unicode
control/format characters. It retains only identity/title in the JS closure;
source URLs, status, terms and other values are discarded without being fetched
or interpreted as availability, entitlement or playability.

Only fixed **EXACT_EPISODE_METADATA** can leave the runtime, after the worker owner
reread and confirmed destruction/route cleanup. No identity/title projection API
is exposed. Episode failures distinguish observed HTTP 401/403/404/410/429 and
closed 3xx/4xx/5xx/other ranges from fetch/transport/CORS rejection with no visible
response (`EPISODE_TRANSPORT`), an observed redirected success response,
invalid status/body/metadata and timeout. Only integer HTTP 100–599 values are
classified; arbitrary numeric status, error bodies and exception text are never
exported. Redirect-refusing fetch can itself reject into the transport category;
that category does not identify a particular network or CORS cause. Guest-only
and profile failure behavior remains unchanged. The same foreground, cancellation,
stale-callback and poisoned-cleanup gates apply. No durable grant, account
Connected state, service/Binder handoff, catalog capability or configured-item
write is added. Acceptance is limited to the separately recorded exact episode
and device context below.

The initial July resource `394-72_s10_p8510` produced the combined legacy
`GUEST_EPISODE_HTTP` result in one explicitly started attempt on 2026-10-11.
After refining only the diagnostics, a separate explicit attempt with that
same resource produced **GUEST_EPISODE_HTTP_404**, establishing a visible HTTP
404 rather than a fetch failure. Neither attempt opened refusal bodies or
accepted episode metadata. The cause of the 404 is unverified; it is not proof
of an existing-account requirement. Both completed confirmed runtime/route
cleanup and reenabled the controls. Protected encrypted records were unchanged.
The refined build passed all 23 direct JavaScript cases and 1,903 JVM cases,
lint, instrumentation compilation, release isolation and shared signer checks.
It was installed data-preservingly on persistent `tachiai-dev`, Android 16/API 36
Google APIs x86-64, WebView 133.0.6943.137, region unknown, existing account not
used. No profile, challenge, collection, playback or license request was made.

The fixed debug resource is now the September day-15 highlights
`394-72_s10_p8529`, previously recorded in the
[successful replay experiment](native-access-experiments.md#native-free-replay-and-held-timing--2026-10-06).
Its check requires another explicit action after rebuilding; this resource
change is not a fallback within the July attempt or a substitution for a user's
configured item. The separate anonymous probe retains its original July ID.

The separate September check on 2026-10-11 completed with
**EXACT_EPISODE_METADATA** through **SAVED_CONNECTION**: the response supplied the
exact requested raw ID and a strictly validated nested title. Only the fixed
result left the JS closure, after final owner reread, runtime destruction and
route cleanup; controls were enabled afterward. Fresh guest and metadata were
discarded. No refusal-body read, profile/account/challenge/collection, source,
playback or license request occurred, and no fallback or retry was used.

Context: persistent `tachiai-dev`, Android 16/API 36 Google APIs x86-64, Google
WebView 133.0.6943.137, region unknown, existing provider account not used.
The installed app SHA-256 was
`59649510284d341f5567807d5588483235b59fb53b6112c8223646387745b3a7`;
the restored ordinary test APK SHA-256 was
`f33707b3e813042af860b10e8b9d46d20969351cd4c21f6cc978fbc10837e82e`.
Both use the shared debug certificate. Full checks passed 1,903 JVM cases,
23 direct JavaScript cases, lint, instrumentation compilation and release
isolation. All four networking-blocked isolated guest-process Android fixtures
passed in 0.687 seconds without skips. The temporary instrumentation override
was removed; source, merged and packaged manifests were verified ordinary and
the ordinary test APK rebuilt, signer-verified and reinstalled. Protected
encrypted records were byte-for-byte unchanged across update, fixtures,
restoration and the completed real check.

This establishes guest metadata access for this exact episode/context. It does
not establish availability, entitlement, playback, general catalog integration,
My List/history access or an existing-account connection. Missing configured
items must retain their own identities; the debug resource is never a catalog
fallback.

On 2026-10-11, the separately opt-in anonymous #153 probes selected historical
`api.abema.io` and modern `api.p-c3-e.abema-tv.com` origins in separate runs. Both
same-path GETs returned HTTP 401 (6.199 and 6.193 seconds respectively) through
**SAVED_CONNECTION** on persistent `tachiai-dev`, Android 16/API 36 Google APIs
x86-64, Google WebView 133.0.6943.137, region unknown. Neither error body was
opened. Each run made one anonymous GET without cookies, authorization, redirects,
retries or origin fallback, completed cleanup and left protected records
unchanged. The modern-only `createAbemaEpisodeCatalog` route and explicit
`AbemaEpisodeMetadataProbeDeviceTest` remain reproducible refusal probes. These
401 results establish anonymous refusal in this context; they establish neither
an existing-account requirement nor guest-authenticated metadata acceptance.

## Configured episode metadata refresh

The debug Android catalog's explicit episode refresh uses `FETCH_EPISODE` in a
nonexported, same-UID bound service in the existing `:abema_guest_bootstrap`
process. Binding does not make a provider request. The management Activity stays
in place; a valid explicit request captures its instance, canonical public episode
ID, memory-only owner proof, nonce and monotonic deadline. JS and native admission
require that exact episode identity; omitting it refuses before guest dispatch.
The fixed diagnostic sample is never substituted for a configured resource.

The service and diagnostic Activity share one process coordinator, admission
gate, proxy override, runtime settings and cleanup/retention owner. Protected
configuration, bundle and backend work stays on workers. WebView, proxy and
Binder callbacks stay on main. Before any provider dispatch, the service rereads
the instance/route and compares its memory-only SHA-256 ownership proof with the
caller's captured proof. No raw profile, route credential or guest field crosses
IPC. The proof is private, is never logged or persisted and does not itself
authorize a request.

The caller sends a nonce-bound foreground/revision pulse every 200 ms, with a
one-second lease. Cancellation, closure, Binder death, lease expiry and the
immutable operation deadline invalidate admission. This bounds cross-process
notification latency; it does not provide instantaneous foreground observation.
Binding is included in the 45-second operation budget. Cleanup can finish for
another 20 seconds, but that grace never admits requests or publishes an expired
candidate. The original destroy-before-clear rule and strongly retained poisoned
runtime/backend apply across both entry points.

Only `FETCH_EPISODE` exposes a JS metadata projection, containing the validated
public ID and title. Native code independently validates the exact requested
identity and title, then returns that projection after confirmed destruction,
route cleanup and a final worker ownership check. Diagnostic operations retain
their status/stop-only API. Guest values, profile data, raw response metadata,
source URLs and licensing information never enter the catalog result. Stopping
the runtime clears the projection.

The existing common catalog/controller/store flow applies the returned title
to the matching configured entry with UNKNOWN availability and no inferred
schedule. It preserves the configured UUID, provider instance, resource, current
order, quality preferences and unrelated records. The separate legacy
`SourceSetup.name` remains unchanged; there is no separate configured local-label
field. Local public-link import remains request-free. A missing episode retains
its configuration and reports NOT_FOUND; 429 reports bounded RATE_LIMITED.
Unexplained 401/403, transport, response, timeout, owner or cleanup failures
remain technical failures. They do not imply that an existing account is needed.
Channel continuations are invalidated on both successful and failed refresh.

This adds metadata refresh only. My List, provider history, existing-account
connection, scheduled-slot/series discovery and selected-resource playback retain
their separate verification boundaries.

On 2026-10-11, one separately opt-in read-only check through the actual Android
catalog and headless service returned **EXACT_EPISODE_METADATA** for
`394-72_s10_p8529` through **SAVED_CONNECTION**. The selected verifier passed in
11.856 seconds without skips; it checked exact normalized identity, UNKNOWN
availability, no schedule, captured-owner agreement and encrypted-record equality.
The public title was not printed. Runtime cleanup is part of the service's
successful-result contract; facade close alone is not independent proof of
remote teardown or Binder release. This catalog observation is separate from
the earlier fixed diagnostic check.

Context: persistent `tachiai-dev`, Android 16/API 36 Google APIs x86-64, WebView
133.0.6943.137, region unknown, existing account not used. Installed app SHA-256
`4047d6ff107809f6f541e5249abedc48d1154e243c4edfc7d8ae983a35e7e294`;
ordinary test APK SHA-256
`9f13e89a586871a56fc44906d62f2c4ed9d9e914d83bd9788a55ed3688e29480`.
Both shared certificates match the procedure. Protected encrypted records were
byte-for-byte unchanged across update, isolated fixtures, ordinary-test restoration
and the real read-only check. The passive launcher menu established the test's
foreground state; no provider page or playback action was opened.

Checks passed 1,939 JVM cases with zero failures/errors/skips, 27 direct JS
cases, Android lint, instrumentation compilation and release isolation. All
seven networking-blocked isolated guest-process Android fixtures passed in
0.952 seconds without skips. The temporary instrumentation override was removed;
ordinary source/merged/packaged manifests verified and the ordinary test APK
rebuilt, signer-verified and reinstalled. The first build caught an incorrect
test callback type, corrected before device deployment. Independent review also
caught supplementary Unicode format validation and cleanup-deadline drift; both
were fixed with regression coverage before verification. The actual
adapter/controller/store preservation path is covered separately with synthetic
memory stores; this device check deliberately makes no configured-item write.

## Verification and observation

Provider-free JS fixtures exercise selected factories, one fresh request,
status-only exposure, strict response parsing, cancellation, timeout and late
responses. JVM policy/lifecycle/route fixtures cover exact endpoint refusal,
install/clear ordering, owner changes, deadline/background/close gates and
failed-cleanup admission. Android fixtures inspect actual secure WebView settings
and execute the owned JS with entirely synthetic modules/fetch and network loads
blocked. These fixtures do not establish provider acceptance.
Profile fixtures additionally exercise the actual owned JS with synthetic
configuration/fetch: identity agreement, strict JSON bounds, HTTP/redirect/network
failures and cancellation during response/body reading. Policy fixtures exercise
one-shot GET/preflight admission and the closed route purpose. These are not
observations of Chromium preflight interception or real server CORS acceptance.
Episode fixtures exercise exact identity/nested-title validation, full JSON and
UTF-8/body bounds, HTTP refusal, late body cancellation and timeout. The synthetic
Android fixture executes the actual owned JS with a fixed program response,
networking blocked and only status/stop exported. These checks do not establish
real guest bearer acceptance, current endpoint/schema compatibility or CORS.

The Android guest fixtures skip in the ordinary instrumentation process before
any WebView/CookieManager API. To run them, use a temporary test-only instrumentation
target-process override for `net.fstab.tachiai:abema_guest_bootstrap` and select only
`AbemaGuestBootstrapDeviceTest`. They initialize the same dedicated guest suffix
before WebView, leave cookies disabled and make no provider request or saved-store
write. These isolated synthetic guest-profile fixtures can be run on verified
`tachiai-dev` while preserving its ordinary profiles/data; default-profile and
resetting instrumentation remains limited to disposable devices. Do not run both
the real observation activity and instrumentation concurrently in that process.

The real observation is explicitly opt-in. On the independently verified
`tachiai-dev` AVD, launch the debug activity with an explicitly targeted ADB serial:

```text
adb -s <verified-tachiai-dev-serial> shell am start -n net.fstab.tachiai/.feature.diagnostic.AbemaGuestBootstrapActivity
```

Select the saved ABEMA instance/route and tap **Prepare guest** once. Record only
the safe outcome category, device/OS/WebView version, selected route mode and
known/unknown region. Compare the provider registry, source configuration,
configured items and saved route records before/after through existing safe
readers; never print secret contents. Do not reset, clear app data, clear browser
profiles or run resetting/default-profile instrumentation on the persistent AVD.
For a separately authorized profile observation, choose **Verify guest profile**
once and apply the same before/after saved-record comparison and safe context
recording. Do not capture the request URI, headers, profile body or auth screens.
Profile rejection remains a technical limitation and admits no fallback.
For the separately selected episode check, tap **Verify guest episode metadata**
once and use the same protected-record comparison/context recording. Inspect
only the fixed outcome; do not capture requests, body or title. Any refusal is a
technical result and triggers no second origin, account or playback request.

On 2026-10-11, the combined implementation passed 1,891 JVM tests, Android lint,
instrumentation compilation, release isolation and shared-debug-certificate APK
verification. All 11 provider-free JS fixtures and scoped pre-commit/documentation
checks passed. Both isolated guest-process Android fixtures passed on persistent
`tachiai-dev` in 0.402 seconds. The temporary instrumentation process override was
restored before the final build; the ordinary test APK was reinstalled without
running default-profile or resetting fixtures.

One explicitly started real guest preparation then reported **PREPARED** through
the unchanged **SAVED_CONNECTION** route. Device context: persistent `tachiai-dev`,
Android 16/API 36 Google APIs x86-64, Google WebView 133.0.6943.137, region unknown,
existing provider account not used. The exact guest POST accepted the bounded
strict response with cookies disabled and credentials omitted. The owned runtime
was destroyed and route cleanup confirmed before publication. Saved encrypted
provider/source/configured-item/route/grant records were byte-for-byte unchanged
across installation, isolated fixtures and this observation; comparison exported
only an aggregate hash, with no names or private values.

This establishes cookie-free fresh guest acceptance only under those conditions.
The pinned first-party request normally uses included credentials; rejection in
another context remains a technical failure and enables no cookie-bearing
fallback. Existing-account profile/client agreement, intended-account approval, grant
lifetime, revocation and durable renewal remain unresolved before #103 can report
Connected. No account profile/challenge/collection, media or licensing request was
made by this guest flow.

The #155 profile extension passed 1,896 JVM tests, Android lint, instrumentation
compilation, release isolation and shared-debug-certificate APK verification.
All 16 provider-free JS fixtures passed. The three isolated guest-process Android
fixtures passed in 0.545 seconds with synthetic modules/fetch and networking
blocked; the dedicated-process guards did not skip them. The temporary test
manifest process override was then restored.

One explicit **Verify guest profile** attempt on the same verified persistent
device/OS/engine and unchanged **SAVED_CONNECTION** route reported
**GUEST_PROFILE_BOUND**. Region remained unknown and the existing provider account
was not used. The fresh guest and cookie-free CORS profile response passed strict
validation, with returned `profile.userId` matching the guest identity. Runtime
destruction and route cleanup completed before the fixed confirmation and
re-enabled controls. Saved encrypted records remained byte-for-byte unchanged
across installation, isolated fixtures and the completed real check. Only fixed
controls/categories and aggregate encrypted-record hashes were inspected; no
private URI, request headers, profile body, account names or auth screenshots
were captured. This acceptance does not establish which Chromium preflight
callbacks occurred.

The matched identity establishes only this fresh guest's server/client binding.
It does not connect an existing account or settle intended-account approval,
durable grant lifetime, revocation, renewal or My List/history access. No device
challenge, account collection, media, source or licensing operation was added or
requested by this extension.
