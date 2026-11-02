# Bounded common provider viewer

This development diagnostic connects the existing common two-feed viewer to
independent ABEMA and Twitch adapters. It advances
[#368](https://github.com/altendky/tachiai/issues/368); it is not the full web
application. Provider settings, account persistence, catalogue management and
distribution remain separate work.

## Sources and controls

The closed catalogue contains the already investigated ABEMA replay
`394-72_s10_p8529` and one pinned Twitch case: Virtual Japan live or replay
`2080217716`. Either side can select either listed source, including duplicates.
Each selection owns a fresh independent media session. Source descriptors carry
only a public diagnostic ID and kind; grants, signed media URLs, route details
and protected replay bindings stay inside their adapters.

After preparation, Watch opens the common viewer. Layout, swaps, floating
movement, Fit, joint transport, overall volume, mix, mutes, More panels and the
approved Back/Forward retirement policy reuse the controlled viewer. A failed
feed can Retry without replacing its healthy partner. Retry obtains a fresh
source and consumes the original trial window; it does not revoke a still-valid
shared Twitch grant or claim to restore an unsupported replay position.

These provider adapters report relative timing as unsupported and do not
dispatch fixture seeks. The common Timing control retains its normal pause
behavior and reports the refusal without changing the requested adjustment.
Manual video/audio preferences also report unsupported. Auto leaves the SDK's
default selection in place; it does not claim verified independent ABR choices.
Numeric buffered-format observations remain distinct from requested preferences,
decoded-frame evidence and acoustic output.

Quality defaults use a separate versioned IndexedDB record containing only the
three known public case IDs and validated numeric preferences. Save is durable
within the disposable browser profile, including reload, and never falls back to
memory-only success. Malformed records are refused without replacement. This
does not select storage for a complete connected-account web application.

## Ownership and preparation

The parent coordinates one finite presentation. Twitch device activation occurs
before any ABEMA guest/source preparation. Each ABEMA feed has an isolated
same-origin child realm, a fresh source-bound packet and one native temporary
CDM session. The existing five public pinned bundles and opaque initial exchange
run unchanged. The host admits the child's exact public media origin only after
fresh preparation; no wildcard ABEMA media origin is added to the parent page.
The child exposes closed commands and numeric observations, not SDK or CDM
objects. Fixture seek/quality permissions are not inherited.

Twitch feeds own one video/Shaka session each and use the existing bound media
router. Fresh Twitch accesses are serialized under one temporary account.
Disposing one feed cancels obsolete queued source work without revoking that
account. Leaving the presentation independently disposes every media owner and
revokes the account, even if a partner fails to close.

The private loopback action accepts one fixed ABEMA source action, exact
Host/Origin/method/body/content type, a private launch capability and a fresh
one-use nonce. The backend begins only after that action; source packets are not
prefetched. They travel through private pipes and the authenticated local action,
never through command arguments, history, diagnostic output or durable storage.
This server has no arbitrary URL, local-file or media-relay endpoint.

The original ABEMA source and complete media-presentation cap are each at most
120 seconds, including preparation and blocked Play. Retries cannot extend them.
The host reserves a separate 180-second activation cutoff, a 450-second total
host budget with 90 seconds reserved for cleanup, and a 480-second route lease.
At most two media owners and four attempts per provider are admitted. Clock
rollback, expiry and presentation retirement close admission before cleanup.

## Explicit local trial

Use Linux, the supported headed Chrome 156 build and a valid existing Japan
WireGuard export. Confirm that the peer is idle before running this explicitly
opted-in diagnostic. Build the normal web assets and the existing route runner
as described in the [route guide](web-abema-routing.md).

```sh
uv run apps/web/scripts/provider-pair-route.py \
  --run-provider-pair-proof --peer-idle-confirmed \
  --route-runner /absolute/path/to/route-trial \
  --wireguard-file /absolute/path/to/export.conf \
  --twitch-case live:virtualjapan
```

Use `--twitch-case replay:2080217716` for the existing replay case. Optional
`--a abema|twitch --b abema|twitch` selects either assignment, including
duplicates. The default is ABEMA A and Twitch B.

The command opens a fresh owned browser and reports only the public activation
code/link. Open that public link in your usual external browser or another
device, then enter the displayed code on Twitch. The restricted trial browser
does not open a provider login popup or copy account cookies. The driver presses
Watch once ready.
After both expected feeds accumulate five seconds of sampled joint advancement,
it reports numeric decode evidence and keeps playing for a manual A-only,
B-only and mixed-audio check. Only sampling intervals where both frame and
media-time counters advance count toward that milestone. Those numbers cannot
establish audible output or timing within a sampling interval.
Finish with Sources, or enter `stop` in the host terminal, and wait for cleanup.
Closing a page/process alone cannot establish provider cleanup.

Preparation workers retain the parent's selected Node tool directory alongside
the OS default executable path. The terminal accepts LF/CRLF Stop lines. If a
parent pipe or stderr failure interrupts the normal loop, it admits no further
source work and drains a bounded authoritative terminal receipt. That receipt
remains diagnostic evidence; it cannot turn the original host failure into a
successful trial or manufacture missing cleanup.

The host combines the existing authenticated ABEMA route and exact Twitch System
bypass with the scoped development Headerly-equivalent continuation mechanism.
It does not change host routes, cookies, status codes, TLS validation or response
bodies. This is local diagnostic infrastructure, not a shipping browser access
architecture or a new product UX exception.

## Verification and remaining acceptance

Owned-only suites exercise actual encrypted/clear decoding, blocked Play,
duplicates, independent Retry, shared gain/mutes, honest timing/quality refusal,
durable defaults, Back/Forward retirement, late activation, private protocols
and cancellation. Outside-origin requests are blocked in the owned browser tests.
The pinned-loader checks optionally consume already verified public assets;
they never download them during tests.

Cleanup records native session closure and MediaKeys detachment independently
of SDK destruction, plus media, temporary grant, browser, backend and route
closure. Missing or contradictory evidence stays unconfirmed and blocks
replacement; child/process death cannot upgrade it.

The observations below distinguish one paired decode/cleanup pass from failed
attempts and missing acoustic acceptance. Existing individual provider
observations remain bounded evidence in their respective guides. Broader named-browser live/live, live/replay, replay/replay,
provider duplicates, audible output, sustained playback, advertisements,
entitlement and renewal remain separate acceptance. No full web app or provider
Quality parity is established here.

### First combined provider observation

One November 2, 2026 trial failed on Ubuntu 26.04.1 LTS/Linux 7.0.0-34-generic
x86-64 and fresh headed Google Chrome 156.0.8078.79. Host source was
`713a4b954adefdfdb470e924a3512a1ffb0d7ba1`; its ordinary Go route runner had a
clean matching VCS stamp. Existing generated public assets were used without a
revision manifest, so their exact source identity is unestablished. A later
provider trial must rebuild from reviewed source and retain its public asset
hashes.

The owner confirmed the saved Tokyo WireGuard peer was unused, completed fresh
Twitch activation, and supplied a screenshot showing rendered Virtual Japan
content while ABEMA replay displayed Playback failed. The owner reported
pressing Retry; the host recorded four ABEMA attempts. Each backend attempt closed, and none
published the source packet/public bundles. Static host verification found Node
only in the parent's selected toolchain, absent from the worker's stripped
default PATH. The nested browser snapshot therefore could not launch. No ABEMA
decode or combined audio acceptance was established. The intended route was the
existing Tokyo export for ABEMA and System for Twitch; exit region and complete
route coverage were not independently measured.

Terminal Stop then triggered a separately reproduced bytearray parsing error.
The old owner abandoned its output loop and retained no final browser receipt.
Backend attempts, the owned browser group and route reported closed, but player,
CDM and temporary-grant closure remained unconfirmed. This failed observation
is not retroactively upgraded by the local tool-handoff, Stop and bounded-drain
regressions.

### Repaired live/replay decoding and separate replay refusal

A later transaction on signed `02cbc47f025b56b78e63950bd5dc7d45cf9f84db`
used freshly rebuilt Rust/Wasm and web assets, a retained public source/output
hash manifest, and an ordinary Go runner with the same clean VCS stamp. Host,
browser, selected ABEMA replay and intended routes matched the preceding
conditions. The owner reconfirmed an unused Tokyo peer and completed fresh
Twitch activation. One fresh ABEMA preparation was used.

The real common viewer reported 5,023 ms of sampled joint advancement, counting
only intervals where both frame and media counters advanced. ABEMA contributed
118 decoded frames and 5,305 ms at 854×480; Twitch live contributed 81 frames and
5,256 ms at 1920×1080. The final receipt retained decodeObserved=true,
playbackFailureObserved=false and independent media, CDM/MediaKeys, renderer,
grant revocation, authentication-owner, backend, browser-group and route closure.
The command exited zero. The dimensions do not supply consumed codec/audio
formats, acoustic output, synchronization or a sustained playback duration.
The owner did not get to check A-only/B-only/fade audio before retirement, so
acoustic acceptance remains unproved. The earlier failed receipt stays failed.

A third separately activated trial changed Twitch to replay 2080217716 and
used the same reviewed source/assets. The owned browser disappeared after
activation; its terminal receipt was REFUSED without sampled decode or a
sampled playback failure. Browser/group, backend and route closure were
confirmed, while media/CDM/grant/renderer and authentication-owner cleanup were
unconfirmed. One ABEMA preparation was recorded. No Retry followed it.

The old receipt cannot distinguish a latched header guard or rejected CDP
continuation from page-close/disposal failure or timeout. Matching replay
route/header/CDN policies and a generic refusal do not establish the trigger.
The diagnostic follow-up exposes only the first closed failure category,
request/response/auth/cleanup stage, continuation-command enum and disposal
state, validated independently by Node and Python. It records no URL, header,
request identifier or raw error, changes no admission policy, and cannot
upgrade missing cleanup. Owned fixtures cover response/continuation failures
that close the page before renderer cleanup can be observed.

### Replay-only refusal and owned cancellation reproduction

A fourth trial on signed `a978dc35350ef5a09d7dda93fdd940361120465b`
used rebuilt, hashed public assets and a matching clean ordinary route runner.
The owner reconfirmed an unused Tokyo peer and activated Twitch through the
provider's documented code-prefilled URL. Both selected feeds were Twitch
replay 2080217716; no ABEMA preparation occurred. The window closed again.
The authoritative first diagnostic was CONTINUATION_FAILED / RESPONSE /
CONTINUE_REQUEST / FAILED. No sampled decode or audio acceptance was recorded.
Browser/group, backend and route closure were confirmed; renderer and grant
cleanup remained unconfirmed. This identifies a failed response-stage
continuation, not its request's cancellation state or the exact provider cause.

An independent owned-only Chrome experiment reproduced that same tuple: a
local server reset produced a failed-response pause, then an AbortController
canceled the request before its delayed continuation. The same CDP session
observed matching Network.loadingFailed with canceled=true before the
continuation rejected with the public Invalid InterceptionId protocol error.
Without cancellation, the original continuation succeeded. Substituting
failRequest or continueResponse did not repair the canceled-ID race. This is a
verified local mechanism, not retrospective proof of the provider trigger.

The scoped lifecycle retains at most 512 current admitted entries through
response continuation settlement. Only that exact uniquely matched entry, with
its unchanged request/response tuple and Network ID, can consume a same-session
positive cancellation receipt observed after admission and before the command
rejection. That terminal event may precede delivery of the response pause:
the exact canceled entry remains owned until its response settles or the owner
is disposed. Its response must still be pending and fully bound when the exact
known invalid-interception error for that command is tolerated. Nonpositive
terminal events invalidate a prior cancellation receipt and retire entries
without a pending response. Retained entries use the existing capacity and
original owner lifetime; overflow refuses further admission.
Missing, ambiguous, unrelated, false, before-admission or late cancellation,
other continuation errors and replaced/disposed ownership remain refused.
Entries retire by identity; no Network-only history can authorize a new Fetch
entry, and there is no command retry or response fabrication.

The closed diagnostic additionally exposes canceledContinuationObserved, a
monotonic boolean indicating this narrow case occurred. Historical four-field
diagnostics remain accepted; the optional boolean is strictly validated by both
Node and Python. It is not playback, account or cleanup authority. All original
origin, frame, method, response, header, status/body and TLS guards remain.
Owned real-Chrome tests retain a failed fetch while keeping the viewer alive;
independent media/CDM/grant cleanup is still required.

### Repaired replay retest and remaining diagnostic gap

A fifth provider trial used signed `ebd3710173fef0504919b6ebcb5019c56570cac5`,
fresh hashed public assets and the matching clean route runner on the same
Ubuntu/Chrome host. The owner confirmed the Tokyo peer was unused and activated
a fresh Twitch grant. Both feeds selected replay 2080217716 on System network;
there was no ABEMA preparation. The owner reported that the window closed
immediately. The first diagnostic remained CONTINUATION_FAILED / RESPONSE /
CONTINUE_REQUEST / FAILED, with canceledContinuationObserved=false. No sampled
joint advancement or acoustic acceptance was recorded. Backend, browser/group
and route closure were confirmed; renderer, CDM, grant and auth cleanup remained
unconfirmed. Host routes were unchanged.

The boolean reports whether the full narrow cancellation exception was applied.
False alone cannot distinguish missing cancellation from a missing/ambiguous
tracked entry, mismatched response identity, or a different protocol error.
The provider retest therefore does not establish the cause of this refusal or
validate the cancellation fix for this provider path. No automatic retry or
additional activation follows from that result.

The optional continuationFailure diagnostic now classifies only the first
failed continuation: exact known invalid-interception error versus Other;
tracked/untracked response with error/status versus a non-response stage; and
the current bounded cancellation eligibility state. All values are fixed enums.
No URL, request or Network ID, header, token, raw error or provider payload is
projected. Node and Python reject unknown fields/values and accept historical
diagnostics. These details do not broaden cancellation tolerance, change
request/header policy or turn process closure into a cleanup receipt.

### Untracked replay failure and cancellation ordering

A sixth provider trial used signed `e46e2f31e0e8a7c043650e13c95530a5d8f475e0`,
fresh hashed assets and matching clean runner after new readiness and unused-peer
confirmation. Both feeds selected Twitch replay 2080217716 on System network,
with no ABEMA preparation. The owner saw frames in both panes before the window
closed. The five-second sampled joint-advancement threshold was not reached and
no audio acceptance was requested. The first failed response continuation was
classified INVALID_INTERCEPTION_ID / UNTRACKED_ERROR / NO_ENTRY. Browser/group,
backend and route closure were confirmed; renderer/CDM/grant/auth cleanup
remained unconfirmed. This is a failed transaction with a separate bounded
visual observation.

The temporary Chrome profile uses the driver's scoped CDP header rewriting;
the Headerly extension is not loaded there. The owner's usual browser rules
remain unchanged. The driver's guard deliberately closes its page on the
failed continuation; the record does not establish a Chrome process crash.

Owned Chrome checks of nonadmitted POST reset, abort, server-auth cancellation
and redirect failures did not reproduce the response pause. A controlled event
delivery check did reproduce the exact diagnostic: an admitted GET's real
failed-response event was held while a real abort delivered a matching positive
Network cancellation, then the original response event was delivered unchanged.
The prior terminal handler had already removed its entry. This reproduces a
local lifecycle defect, without proving the provider trial's event order.

The corrected owner retains only that already-admitted, uniquely canceled
entry across this ordering. It preserves the initial media-header admission
and all response binding checks, and releases the entry at settlement or owner
disposal. The failed Fetch remains failed while the page can finish its normal
cleanup. No additional API tracking, provider permission, header change,
continuation retry or inferred grant/CDM cleanup is introduced.

### Missing interception acknowledgement and focused investigation

A seventh trial used signed `71f2f2844e147a9fbcce6f99ac69c590773b79aa`,
fresh hashed assets and matching clean runner after readiness and unused-peer
confirmation. A selected ABEMA replay and B selected Twitch replay 2080217716.
The owner reported that the window disappeared immediately after activation.
The first failed continuation again classified INVALID_INTERCEPTION_ID /
UNTRACKED_ERROR / NO_ENTRY; canceledContinuationObserved was false. There was
one fresh ABEMA preparation attempt, no sampled joint advancement and no
acoustic acceptance. Browser/group, backend and route closure were confirmed;
renderer/CDM/grant/auth cleanup remained unconfirmed. The retained canceled-entry
change therefore did not fix this provider failure. Further activation and
combined-provider checks were suspended to isolate the browser driver.

The installed Chrome was 156.0.8078.79, revision
`a21ce95c24ce3964416f09ae1862f53f319eb4d0`. Chromium's
[interception job lookup](https://raw.githubusercontent.com/chromium/chromium/a21ce95c24ce3964416f09ae1862f53f319eb4d0/content/browser/devtools/devtools_url_loader_interceptor.h)
returns the exact invalid-interception error when its job map has no matching
entry. Its
[continuation dispatcher](https://raw.githubusercontent.com/chromium/chromium/a21ce95c24ce3964416f09ae1862f53f319eb4d0/content/browser/devtools/devtools_url_loader_interceptor.cc)
performs that lookup before applying modifications. For a requestId-only
continuation, that acknowledgement means the command applied nothing and has
no job to resume. It does not establish cancellation, successful media or the
reason the job disappeared.

The owner now accepts only this narrow asynchronous acknowledgement: the
original pause was an untracked failed response with a declared Network error
reason and no HTTP response status; no entry existed or replaced it; the same
live page/session/owner sent exactly Fetch.continueRequest with requestId only;
and Chrome returned the exact known missing-interception error. Synchronous
throws, changed owners, tracked or replaced entries, authentication, header
modifications and other protocol errors remain fatal. The existing tracked
cancellation exception remains separate. No network admission, CDN permission,
header or credential policy changes.

Optional absentInterception and untrackedFailure diagnostics preserve the first
bounded lifecycle snapshot for these cases. They contain only validated enums,
booleans and capped counts/sequences, with no URLs, request/Network IDs, headers,
tokens, raw errors or provider payloads. Retirement denotes the first observed
retirement of that Fetch ID. Tuple relations use a temporary keyed digest only
inside the owner; no digest is projected. History overflow reports incomplete
observation and never grants admission or cleanup authority. Node and Python
share the strict field schema and accept historical diagnostics. An absent-job
acknowledgement does not set canceledContinuationObserved or imply cleanup.

Local verification passed 69 Node tests, including the opt-in real Chrome
checks, and 12 Python boundary tests. The focused Chrome check delivers a
controlled duplicate of a real failed-response event after its job has ended;
the resulting command receives Chrome's actual missing-job error. Both owned
videos then have more than five seconds of sampled joint advancement, followed
by explicit video removal, one owned revoke POST and normal browser cleanup.
This verifies handling of the actual negative acknowledgement, not the event
order of the provider failure or provider grant/CDM cleanup. The separate host
test verifies its explicit Stop and authoritative synthetic cleanup receipt.

A fresh anonymous token request and manifest request for the exact Twitch
replay both returned HTTP 200 without cookies, saved accounts or Authorization.
The token and manifest remained in memory. This permits a focused diagnostic
through the unchanged Twitch player without another owner activation; manifest
acceptance alone does not establish playback, audio or authenticated behavior.
