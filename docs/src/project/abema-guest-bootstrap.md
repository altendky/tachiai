# ABEMA guest preparation and profile verification

Issue [#150](https://github.com/altendky/tachiai/issues/150) adds an explicit
debug observation entry for a fresh ABEMA guest. It is a prerequisite experiment
for the account work in [#100](https://github.com/altendky/tachiai/issues/100) and
[#103](https://github.com/altendky/tachiai/issues/103), with no existing-account
connection, device challenge, renewal or playback implementation. Issue
[#155](https://github.com/altendky/tachiai/issues/155) adds a separate explicit,
memory-only guest profile verification action. Its bounded provider observation
is recorded below; existing-account linking remains separate.

## Implemented boundary

`AbemaGuestBootstrapActivity` uses its own `:abema_guest_bootstrap` process and
`abema-guest-bootstrap` WebView profile, configured before any WebView API. Opening
the activity reads saved provider configuration only. It ignores external extras;
the user selects an existing ABEMA instance and explicitly taps **Prepare guest**.
The separate **Verify guest profile** action captures an immutable profile
operation before preparation and starts its own fresh guest. Neither action
starts automatically, accepts an operation/identity from an Intent or connects
an existing account.
Provider names and saved route titles are shown; no account identifiers or grant
values enter the UI. The activity does not alter provider, route or configured
source records.

The selected instance's UUID, ABEMA service, saved route mode/connection ID and
profile fingerprint are captured and reread on workers. A missing imported route
fails; it cannot become System access. The narrow guest route purpose admits
`abema.tv` only. Public-code downloads use that route and the existing reviewed
four-chunk size/SHA-256 cache. Cache files contain public code only. All four
unchanged chunks register factories; application/runtime callbacks do not run.
Only the guest dependency path and, for profile verification, the unchanged
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
closure. Native polling exposes only fixed status/failure categories. Platform
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
