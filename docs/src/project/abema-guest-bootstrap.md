# ABEMA guest-only preparation

Issue [#150](https://github.com/altendky/tachiai/issues/150) adds an explicit
debug observation entry for a fresh ABEMA guest. It is a prerequisite experiment
for the account work in [#100](https://github.com/altendky/tachiai/issues/100) and
[#103](https://github.com/altendky/tachiai/issues/103), with no existing-account
connection, profile lookup, device challenge, renewal or playback implementation.

## Implemented boundary

`AbemaGuestBootstrapActivity` uses its own `:abema_guest_bootstrap` process and
`abema-guest-bootstrap` WebView profile, configured before any WebView API. Opening
the activity reads saved provider configuration only. It ignores external extras;
the user selects an existing ABEMA instance and explicitly taps **Prepare guest**.
Provider names and saved route titles are shown; no account identifiers or grant
values enter the UI. The activity does not alter provider, route or configured
source records.

The selected instance's UUID, ABEMA service, saved route mode/connection ID and
profile fingerprint are captured and reread on workers. A missing imported route
fails; it cannot become System access. The narrow guest route purpose admits
`abema.tv` only. Public-code downloads use that route and the existing reviewed
four-chunk size/SHA-256 cache. Cache files contain public code only. All four
unchanged chunks register factories; application/runtime callbacks do not run.
Only the guest dependency path is invoked. There is no source/CDM/license helper,
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

Device ID, application secret and guest ID/bearer stay in the JavaScript closure.
Native code polls only fixed status/failure categories. No JavaScript interface
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

## Verification and observation

Provider-free JS fixtures exercise selected factories, one fresh request,
status-only exposure, strict response parsing, cancellation, timeout and late
responses. JVM policy/lifecycle/route fixtures cover exact endpoint refusal,
install/clear ordering, owner changes, deadline/background/close gates and
failed-cleanup admission. Android fixtures inspect actual secure WebView settings
and execute the owned JS with entirely synthetic modules/fetch and network loads
blocked. These fixtures do not establish provider acceptance.

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

No real provider/device observation is claimed by this implementation document.
The pinned first-party guest request uses included credentials; this experiment
deliberately omits them. Cookie-free acceptance is unverified. Rejection remains
a technical failure and does not enable a cookie-bearing fallback. Even success
would verify only fresh guest preparation under the recorded conditions. Account
profile/client agreement, intended-account approval, grant lifetime, revocation
and durable renewal remain unresolved before #103 can report Connected.
