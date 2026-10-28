# Twitch browser diagnostic

This is reproducible investigation infrastructure for
[#365](https://github.com/altendky/tachiai/issues/365), using the
[#364 web harness](web-client.md)'s pinned Shaka dependency, generated Rust/Wasm
module, controlled test media and production browser test pipeline. It is not a
connected-account feature, provider playback acceptance, a new web UX, or an
extension/companion deployment decision. Issue365 remains open until the actual
browser-local authorization and selected live/replay playback evidence is known.

## Explicit local execution

Follow the [harness setup](../../../apps/web/README.md) to install dependencies,
prepare assets and build. The default command refuses to start a trial; network
access and each exact catalogue case must be selected explicitly:

```sh
mise exec -- npm run probe:twitch --prefix apps/web
mise exec -- npm run probe:twitch --prefix apps/web -- --network --case=live:virtualjapan --case=replay:2080217716
mise exec -- npm run probe:twitch --prefix apps/web -- --network --headed --case=live:virtualjapan --case=replay:2080217716
```

The network commands start a fresh automated Chrome context and local production
preview server on 127.0.0.1:4173. It prints a short-lived activation code for the
provider's Twitch Activate page. The user controls provider login and consent in
their own browser; the diagnostic never opens or instruments that login page.
No provider credentials, cookies, saved Android grants, imported browser profile,
client secret, refresh operation or new application registration is used.
Do not approve an old code: each run owns one fresh challenge and its deadline.

The default launch explicitly uses headless Chrome; `--headed` opens headed
Chrome while preserving the same transport, profile and authorization rules.
The activation event reports the selected launch mode. A headed automated
browser remains different from an ordinary user-controlled browsing session.
The flag alone does not authorize network access or select any playback case.

The separate `--browser-failures` flag enables a passive Chrome diagnostic during
each selected preparation, plus up to 250 ms of event delivery after an explicit
`refused` result. The default runner creates no additional CDP
session. This flag also requires `--network` and an exact selected case; it does
not initiate authorization by itself. For example:

```sh
mise exec -- npm run probe:twitch --prefix apps/web -- --network --headed --browser-failures --case=live:virtualjapan
```

Only the fresh owned diagnostic page gets a CDP session, with application
listeners for `Network.loadingFailed` and `Network.responseReceivedExtraInfo`.
No provider/login page is instrumented.
The diagnostic makes no request, response-body, header or cookie queries and
does not change Fetch, interception, caching or security behavior. However,
`Network.enable` is domain-wide: URL/header/cookie-bearing protocol metadata can
traverse the automation client even without application listeners. This is a
closed collector/output boundary, not a metadata-free automation transport.

The separate `browserFailure` result says `window=selected-preparation`, or
`window=selected-preparation-plus-up-to-250ms-delivery` when the bounded refusal
delivery wait starts. Both shapes always say `association=unattributed`.
It reports setup/teardown availability, a bounded
none/one/multiple/overflow observation category and only the first observed
failure's allowlisted resource, cancellation, CORS, blocked and network-error
categories, plus optional bounded numeric `first.http`. After sixteen observations the count saturates at overflow; no event
list is retained. Arbitrary error text is compared only against exact known
constants and discarded; unknown values become `other`. Timestamps and
`failedParameter` are not read or retained. The new extra-info handler reads only
`requestId` and `statusCode`, each once. A private table holds at most sixteen
bounded ASCII request IDs and one numeric status or an ambiguous marker per ID,
plus the first failure's bounded ID. IDs never enter output. Both event arrival
orders are supported; unrelated IDs cannot supply the first failure's status.
Every duplicate extra-info event for an ID makes it permanently ambiguous,
including equal statuses, or invalid/missing/throwing status values before or
after a valid status. Ambiguity removes any previously matched `first.http`.
Table overflow permanently disables all status correlation and removes a prior
status while retaining the legacy failure counts/categories. Malformed IDs or
status values cannot fabricate HTTP. Disarm, expiry and close clear all private
IDs/table entries; only an already matched closed numeric value can remain.
The
collector independently checks the output shape. Setup and teardown failures
remain closed results and do not skip media/grant cleanup.

The [official CDP Network declarations](https://raw.githubusercontent.com/ChromeDevTools/devtools-protocol/master/pdl/domains/Network.pdl)
document extra-info status for failures that do not deliver a normal response
event, including CORS errors. They also specify that a network request ID does
not identify an individual HTTP request within that network operation. Therefore
`first.http` is an observed extra-info status for that first failed browser
network ID within the bounded window, not necessarily an individual/final HTTP
response and never proof of a selected media/manifest request. A redirect can
reuse an ID; one observed 302 is not a final-response claim. Missing `first.http`
means unavailable evidence, including absent extra-info, ambiguity or overflow.

This observation is not bound to the media manifest request. Source APIs,
preflights, other requests and delayed events can overlap preparation. The
listener disarms synchronously on runner cancellation and before runner
cleanup/replacement, then independently removes both listeners, disables its
Network domain and detaches.
Only an explicitly refused preparation with the opt-in collector armed can open
one fixed delivery window. A monotonic deadline rejects callbacks after 250 ms
even if the timer task itself is delayed. Cancellation, disarm or close interrupts
the wait immediately; unavailable/unarmed/closed collectors do not wait. Success,
an exception without an explicit refusal, and the default runner never drain.
The existing trial deadline is unchanged and its cancellation interrupts this
wait. Teardown and media/grant cleanup still run if the wait fails.
Preparation can internally retire a refused player before settling;
events from that retirement can appear in this unattributed window.
A missing observation after either window or an absent CORS field is unavailable
evidence, never CORS success. Generic network
failure does not prove a redirect or HTTP status. The existing request-owned
`media` diagnostic stays separate. No provider failure cause has been identified.
A fresh provider attempt validated authorization, then refused the manifest
Fetch while the immediate collector reported setup ready, teardown confirmed and
no observation; media destruction and fresh-grant revocation were confirmed.
The local delivery-gap reproduction below does not establish that attempt's cause.

Only the two named existing catalogue samples are admitted. A refused selected
case ends the playback sequence; it does not try a different channel/replay,
client identity, unauthenticated access, provider embed, route or media relay.
The observed replay distribution `dgeft87wbj63p.cloudfront.net` is admitted only
for a selected replay; this is not a general CloudFront rule. The other media
families match Android's existing experimental `ttvnw.net`/`twitchcdn.net` policy.
These bounds establish neither current availability nor entitlement of a sample.

The run stops after at most 15 minutes, plus bounded in-flight work and cleanup.
The runner handles SIGINT and SIGTERM itself and disables Playwright's automatic
handlers for those signals, keeping Chrome available until its finally block
attempts the owned page's cleanup receipt. Sending either signal to the verified
owned Node runner cancels current browser requests, retires the owned player and attempts
revocation of this run's fresh access grant. Closing the page or losing the
browser/network can prevent confirmed revocation; `unconfirmed` is a failure to
establish cleanup, never a claim of revoked authorization. `no-grant-held` means
no access grant was adopted in browser memory, not that the provider could not
have issued an unread response during an interrupted request.
For automated execution, target only the verified owned Node process when
cancelling. A process-group interrupt, another signal or a hard kill can still
close Chrome before the runner confirms media retirement and revocation. The
handler change does not make an `unconfirmed` receipt successful.

## Account and source ownership

The device flow pins public client `ue6666qo983tsx6so1t0vnawi233wa` and exactly
`user:read:follows`. This is the already selected provider Smart TV public
identifier, not Tachiai's application registration or a supported playback
contract. Scope selection alone is not playback entitlement. The first successful
validation pins the user ID in browser memory; revalidation must preserve exact
client, user, scope and accepted lifetime shape before each source request. Account IDs
never leave that runtime. Refresh data is ignored and never retained.

An issuance response may omit `expires_in`, as the second activated trial below
observed. Omission permits only immediate validation; authorization still needs
the exact client/user/scope binding. Validation accepts a finite positive expiry
of at most 365 days or a present numeric zero under the local policy below.
Present zero or malformed issuance expiry and missing/malformed validation expiry
are refused. OAuth permits omitted issuance expiry when expiry is supplied
another way, and Twitch documents validation as a source of remaining lifetime:
[OAuth token response](https://www.rfc-editor.org/rfc/rfc6749.html#section-5.1),
[Twitch validation](https://dev.twitch.tv/docs/authentication/validate-tokens/).

Issuance and validation deadlines are anchored before their requests. The hard
grant cap is the minimum of the fixed 15-minute session deadline, any positive
issuance deadline, the earlier cap and any new positive validation deadline. Responses
after that cap are refused; revalidation never extends it. Authorized request
timeouts also respect the cap. Cleanup revokes the fresh grant on refusal or
completion; no token/refresh persistence or refresh operation is added.

A present numeric zero, observed in the third activated trial below, supplies no
provider deadline. It leaves the earlier cap and fixed local session deadline
intact; a later positive validation can only shorten that cap. This is unknown
provider lifetime with at most 15 minutes of local memory retention, not a claim
of expiration, non-expiration or entitlement. Exact binding and revalidation
before every selected source still apply. This matches the selected Android
catalogue's `allowUnspecifiedLifetime` classification without importing its
persistent retention policy. Twitch's HTTP 200 establishes current validation;
its documentation does not establish what this client's zero means over time.

OAuth requests use the documented
[device flow](https://dev.twitch.tv/docs/authentication/getting-tokens-oauth/#device-code-grant-flow),
[validation](https://dev.twitch.tv/docs/authentication/validate-tokens/) and
[revocation](https://dev.twitch.tv/docs/authentication/revoke-tokens/) endpoints.
Those documents do not establish private GraphQL/HLS playback support or that
this selected public client will accept a browser session.

Authenticated playback access retains Android's current
`resolveTwitchPlayback` client-header comparison: the GraphQL `Client-ID` header is empty,
while OAuth issuance, validation and revocation remain bound to the exact public
client above. This is a fixed, documented experimental request shape, not an
accidentally omitted OAuth identity, and there is no automatic alternate-header
retry. A readable GraphQL response must match the exact requested live/replay
field and bounded signature/value shape. Its opaque signed value is used
unchanged for that same selected manifest path; its internal claims are not
treated as independently verified source/entitlement evidence.

### Verified browser backend and live permission

Both live and replay source queries now declare `playerBackend: "mediaplayer"`.
The default manifest keeps its existing v2 path and five query fields, adding
only `player_backend=mediaplayer`. The default probe does not change OAuth client/scope,
the empty GraphQL Client-ID comparison, browser Origin, cookies, integrity,
redirect/TLS/CORS policy or media admission. It does not import other browser
metadata or retry another profile. The separate
[explicit live comparison](#explicit-unversioned-live-comparison) can pin an
unversioned path before authorization.

Only the live query also requests
`authorization { isForbidden forbiddenReasonCode }`. Before publishing a live
URI or creating a player, the adapter requires an explicit boolean false plus
reason `NONE`, null or absent. True always refuses, including true with `NONE`.
Missing/malformed authorization, a non-boolean flag, false with another known
reason, or false with an unknown/non-string reason refuses. An opaque token's
internal claims cannot replace this structured field.

The closed `livePermission` diagnostic distinguishes `not-forbidden`, `forbidden`
and `unavailable`, with reason `NONE`, `UNAUTHORIZED`, `UNAUTHORIZED_ENTITLEMENTS`,
`GEOBLOCKED`, `CHANNEL_RESTRICTED_BANNED_USER`, `absent` or `other`. No arbitrary
provider string or account/source identifier enters this field. It survives
subsequent owned-media failure/cleanup, and a new source attempt clears it before
revalidation so replay or a failed source cannot inherit an old live result.
The CLI independently validates phase/status/reason associations and snapshots
the primitive fields once before projecting them.

False with null/absent reason is an explicit conservative adapter rule: the
public runtime query AST does not establish schema types or nullability. An
accepted false result is limited source-permission evidence, not complete
entitlement, advertising, platform or playback acceptance. The existing media
attempt follows it under the same policy; there is no new source-only mode or
extra activation. Replay keeps its verified signature/value selection without
inventing a structured VOD authorization field.

The primary-source check used public guest HTML only in memory to locate
query-free static assets; no login/profile, signed response or provider player
runtime was inspected. The relevant current official assets are:

- [Player/source asset 61456](https://assets.twitch.tv/assets/61456-bf3a1f15bb71ab6f65dd.js),
  333,321 bytes, SHA-256
  `67863bedfa543bc49a92525f8be184735296b25e5c5d5da9cdbdd70426c39dd0`.
  Modules 889554/117186 declare the backend and live authorization selection;
  module 768821 declares the manifest backend parameter. Its builder supports
  both versioned and unversioned paths; no actual guest cohort was observed.
- [Client/config asset 95120](https://assets.twitch.tv/assets/95120-2bcdd5afbbdea7868a33.js),
  526,416 bytes, SHA-256
  `d74fce69d6cadd05f4aa48304fe816b83d8846549760443ef9ba2d57bced1408`.
  Module 600424 declares the five reason-code strings above. Browser client,
  device, build/session and conditional integrity context are not transplanted.

These are independently expressed protocol declarations, not copied provider
helper algorithms. Static contract differences do not establish the cause of
the observed manifest Fetch refusal or imply that a valid media response lacks
CORS support. Offline synthetic and owned-media tests verify
the strict guard, closed diagnostics, refusal before media creation, permitted
continuation, source replacement and cleanup; they are not provider acceptance.

### Explicit unversioned live comparison

The fresh PR #398 trial on reviewed commit
`5bba925f6d062c26baafd63c8224e953ea244487` validated authorization and returned
live permission `not-forbidden` with reason `NONE`, then refused Virtual Japan
at player-load: manifest GET, checkpoint `fetch`, refusal `fetch-rejected`, SDK
category 1/code 1002. Its bounded passive window captured one uncanceled Fetch
failure with `MissingAllowOriginHeader` and extra-info HTTP 200. This event
remains explicitly unattributed to the selected manifest; HTTP 200 does not
establish a readable or valid media response. Media destruction and fresh-grant
revocation were confirmed. See the [recorded result](https://github.com/altendky/tachiai/issues/365#issuecomment-6457327325).

An explicit diagnostic-only comparison can select the other live path declared
by the same official public endpoint builder:

```sh
mise exec -- npm run probe:twitch --prefix apps/web -- --network --headed --browser-failures --manifest-endpoint=unversioned-live --case=live:virtualjapan
```

The default remains `v2`; `--manifest-endpoint=v2` selects it explicitly.
The unversioned comparison requires exactly the single existing live case. Replay,
unknown values, duplicate/conflicting flags and arbitrary paths are refused at
CLI validation before a browser or provider operation starts. The page also
validates the exact endpoint choice before its first challenge request and pins
it for that session; another begin call cannot replace it. Its source guard
refuses replay before revalidation or access calls.

Only `/api/v2/channel/hls/virtualjapan.m3u8` changes to
`/api/channel/hls/virtualjapan.m3u8`. The selected OAuth identity, scope, fresh
grant policy, exact source query and permission guard, opaque signature/value, manifest
query parameters, headers and media admission remain the same. Normal Fetch,
CORS/TLS, cookie omission, redirect refusal, response handling, single-attempt
behavior and media/grant cleanup also remain in place. Activation and preparation
output include only the closed `manifestEndpoint` label for reproducibility.
Each activated run obtains its own fresh grant and source response; no grant or
signed response is reused across runs.

This is a one-variable counterfactual, not a corrected/default endpoint or an
automatic retry/fallback. Both paths exist in the observed official static code;
the current browser cohort and cause of the previous refusal remain unknown.
An owned-media outcome can establish progress even without attributing a passive
browser event. A second refusal would bound these two tested profiles, not prove
that all ordinary website-owned playback needs an extension or companion. No
actual provider result for the unversioned comparison is established by its
synthetic/owned tests.

### Explicit native HTML video diagnostic

An independent page can test ordinary browser-native HLS playback while keeping
the same browser-local account/source preparation. This is HTML video inside
the website, not an OS companion, extension, media relay or selected product
architecture. Select it explicitly before authorization:

```sh
mise exec -- npm run probe:twitch --prefix apps/web -- --network --headed --transport=native-hls --case=live:virtualjapan
```

`--transport=mse` is the unchanged default. Native mode accepts only the single
existing live case and v2 manifest endpoint; replay, another endpoint, unknown
transport and duplicate/conflicting flags refuse before browser/provider startup.
There is no automatic fallback. Activation, preparation and playback carry the
closed selected `transport` label. Native playback reports `live: "unknown"`:
selecting a live catalogue item does not establish its observed timeline.

The separate page imports the account/source adapter and native lifecycle only,
with no provider scripts. OAuth/GQL still use the exact existing client, scope,
permission guard and closed Fetch transport. The signed source stays inside the
page and passes the existing live-media admission check before assignment.
Native media has its own HTTPS-only CSP for the existing live CDN families;
the replay CloudFront exception is absent. The fresh browser context blocks
service workers and imports no cookies or saved grants.

An absent `crossorigin` attribute deliberately uses normal no-CORS HTML media
loading. This can play opaque media without making its bytes readable to script;
it does not disable browser security. It also uses normal browser cookie and
redirect semantics: a fresh profile has no imported cookies, but may adopt and
send new cookies during the trial. Native elements do not expose the MSE Fetch
adapter's per-request omit, redirect-error, cache, admission, byte-limit or timeout
controls. Initial admission and descendant CSP remain distinct boundaries. See
[HTML media fetching](https://html.spec.whatwg.org/multipage/media.html#concept-media-load-resource)
and [CORS settings](https://html.spec.whatwg.org/multipage/urls-and-fetching.html#create-a-potential-cors-request).

Owned Chrome 156.0.8078.47/Linux observations established no-ACAO native decoding
and CSP refusal before network access for disallowed child playlists, segments
and child-302 destinations. Permitted master-A → playlist-B → media-B decoded.
Permitted segments crossing away from their playlist origin failed even without
a separate initialization resource or with conventional target ACAO; those are
failed decoding controls, not playback acceptance. Chromium's
[HLS origin checks](https://chromium.googlesource.com/chromium/src/+/HEAD/media/filters/hls_network_access_impl.cc)
corroborate that tainted segment/header/key data must share the media playlist's
origin unless the responses are untainted. Current source is not an exact build
identification. Twitch's actual descendant topology remains unknown.

The native media owner bounds loading at thirty seconds; the runner retains its
fifteen-minute trial cap. Cancellation, replacement generations, stale events and retirement are
guarded; cleanup pauses, removes the source, reloads and removes the element,
and independently revokes the fresh account grant. Failures report only closed
native phases/refusals and optional MediaError code 1–4, with an independent CLI
projection; no error message, signed URL, media body or pixels enter output.

The existing diagnostic Play button starts muted observation of frame/time
advancement. Acoustic output and provider advertisement/timeline binding remain
unverified; seek stays unsupported. Earlier local native HLS observations found
automatic rendition/default audio but no public manual audio/video-track APIs.
This diagnostic adds no product Quality exception or claim of equivalent common
controls. Actual provider decoding and any product adoption remain separate.

All grant-bearing requests stay in the owned browser page. In the default MSE
diagnostic, a closed Fetch
transport admits only exact OAuth/GraphQL endpoint/method pairs, forces normal
CORS mode, omits cookies, disables caching and uses `redirect: error`. The
diagnostic replaces Shaka's HTTPS scheme plugin with its own bounded Fetch
transport, using the reviewed media policy and the same cookie/redirect rules;
it does not fall back to XHR or Shaka's default network plugins. Native playback
fallback is disabled, and the page's media CSP permits only blob-backed MSE.
Grants never accompany CDN requests. New destinations cause refusal and require
investigation.

The production runner installs no Playwright request interception. Such routing
can synthesize CORS preflight responses in Chromium and cannot enforce redirects
by rechecking each redirected request, so it would distort this investigation.
Actual browser CORS acceptance remains an outstanding provider observation.

## Player and diagnostic boundary

The separate `twitch-proof.html` entry owns a Shaka instance and video element.
The existing `BrowserPlayer` remains fixture-only: provider sources are never
mislabelled as controlled fixtures and its policy is not broadened. The provider
host follows the same lifetime pattern: muted initial media, single-attempt
preparation, guarded retirement, safe observations, bounded preparation and
refusal to admit another player after failed destruction. Concurrent stop/close
operations share one cached retirement promise; new preparation cannot be
admitted until destruction is confirmed. Failed destruction remains sticky.
It loads the matching
Rust/Wasm module, but provider seek dispatch remains unsupported until Twitch
ad/timeline binding is verified. It does not inherit the fixture's ad-free seek
permission. No provider media is copied, recorded, re-encoded or exported.

Only fixed result categories, the public selected case, numeric frame dimensions,
decode/advance booleans and the short-lived activation code leave the browser.
The runner suppresses browser console messages before loading libraries and
does not retain screenshots, video, traces, HAR, storage, provider bodies,
tokens, signed URLs, account identifiers or unrestricted errors. Acoustic output
is explicitly untested. A future runtime report must record host/OS/browser,
region/account condition, selected content, observed behavior and cleanup.

The session/host diagnostic getter and CLI refusal results expose only a closed
stage enum and an optional integer HTTP status from 100 through 599. Stages
distinguish device challenge, token polling, validation, source access and player
attach/load/playback/cleanup. Network failures may have no HTTP status; media
status is the latest response observed in that stage, not proof of why a decoder
failed. Original preparation failure stages survive subsequent cleanup. Provider
strings, response fields, token shapes and raw player/network errors do not
enter this diagnostic result.

The token-poll/validation stages also expose only a fixed refusal reason: `cancelled`,
`deadline`, `token-type`, `expiry-missing`, `expiry-zero` or `expiry-invalid`.
These identify the existing post-response checks without returning the token
type, expiry value, access/refresh grant or other response fields. The CLI
independently allowlists the reason, and cleanup preserves the failed observation.
The CLI admits `token-type` only at token polling. Lowercase bearer remains
required; issuance omission and numeric zero validation use only the bounded
policy above.

Media stages additionally expose a closed `media` snapshot: checkpoint/refusal,
normalized `manifest`/`segment`/`other` request kind, `GET`/`HEAD`/`OTHER` method,
and optional integer SDK category (1–10) and code (1–99999). No SDK error object,
message, data, cause, stack, header, URL or provider body is returned. The CLI
independently allowlists every field/value and admits media evidence only at
player stages. The request filter binds an observer to its owned request before
the asynchronous scheme-plugin dispatch; later callbacks keep that original
player/generation. First refusal survives concurrent responses and cleanup, while
numeric SDK annotations may explain it without replacing it.

Checkpoints separate SDK-only load, request filtering, plugin/Fetch admission,
Fetch, response headers, body reading and completion. Refusals distinguish local
policy, Fetch rejection, the existing ten-second transport timeout, owned abort,
readable HTTP rejection, body reading/size, processing and SDK failure. The host's
existing thirty-second load deadline has an explicit `load-timeout` marker.
`fetch-rejected` does not identify CORS: redirects, CSP, DNS/TLS and other network
failures can produce the same unreadable result. No HTTP value means none was
observed before the latched failure, not proof that no request occurred. A known
HTTP value is still only the latest readable response before failure.

### Activated browser trial: October 26, 2026

The fresh user-activated trial on revision
`f099c590cd363ac6d1b2483407916e6dca777c19` used Linux x86-64 and Chrome
156.0.8078.47, a new browser context and default host networking. Exit country
was not independently measured. The selected sequence was Virtual Japan live,
then the documented replay, stopping on any refusal without an alternative.

After activation, token polling received HTTP 200 and captured this run's fresh
access grant. The diagnostic refused while still at `token-poll`, before account
validation, source preparation or media playback. Cleanup reported `destroyed`
and `revoked`; the listener ended and the loopback server was absent afterward.
No grant, provider body, account identifier, signed URL or media capture was
retained. No selected playback case was reached.

That revision reported no individual refusal reason. Cancellation/deadline,
the exact token-type check and the finite-expiry check remain candidates; this
observation does not establish which failed or justify changing the acceptance
policy. The fixed reasons above prepare a further fresh trial to distinguish
those cases. The revoked grant and old activation code must not be reused.

The next fresh user-activated trial ran on signed diagnostic revision
`cb2cc0f0288cd9dc7c77dd264dd10398c73c8d6c`, in the same bounded Linux/Chrome
environment with a new browser context and default networking. It received
HTTP 200 at token polling and reported `reason=expiry-missing`: the issuance
response omitted the expiry field. It still stopped before validation, source
preparation or playback, then reported media destroyed and grant revoked. The
listener ended and its loopback server was absent. No raw response/grant was
retained. This identifies the omitted issuance field; it does not establish the
validation response's lifetime, account binding or playback acceptance.

The third fresh trial ran on signed revision
`66d09e2d0742ee4b90e59a1cddf07383d7bc2e8e`, with the omission-only policy and
monotonic deadlines, in the same bounded Linux/Chrome environment and a fresh
browser context. It reached validation HTTP 200 with the exact client, scope and
valid user binding checks passed, then refused with `reason=expiry-zero`.
This establishes the returned numeric zero, not a provider expiration meaning.
No source or media request followed. Media destruction and fresh-grant revocation
were confirmed; the listener ended and the loopback server was absent.

The zero-as-unknown local policy above is the resulting bounded compatibility
change. Controlled regressions do not substitute for another fresh activated
provider trial; no revoked grant or old device code is reused.

The fourth fresh trial on signed revision
`2452d11a14cf8dbc6ff1e9aaedd18856465b9b88` validated authorization, then refused
the selected `live:virtualjapan` source at `player-load`, with no readable media
HTTP status in that revision's diagnostic. No decoding or replay case followed.
Media destruction and fresh-grant revocation were confirmed. The runner ended
normally after reporting the refusal; exit zero was not playback acceptance.
This result does not identify CORS or distinguish local admission, unreadable
Fetch, timeout or pre-request SDK failure. The closed media observations above
are the next diagnostic step; they do not relax any security or playback policy.

The refreshed instrumented trial on signed revision
`21ad5a5853763251a0827df9c9e7a6dc2bdb2b08` validated authorization, then refused
the same live source at `player-load`. Its first media failure was manifest GET,
checkpoint `fetch`, refusal `fetch-rejected`, SDK category 1 and code 1002. No
HTTP response was exposed, decoding was not reached and the replay case was not
attempted. The player was destroyed, the fresh grant revoked and the loopback
server closed. This narrows the boundary to Fetch but does not distinguish CORS,
redirects, CSP, DNS/TLS or another network failure. Exit zero remains normal
completion after reporting a refusal, not playback success.

That trial used the default automated launch. A separate owned blank-page
control with Chrome 156.0.8078.47 verified that the default launch was headless;
explicit `headless: false` was headed. Both reported webdriver, and neither
control made provider requests. The subsequent headed comparison below reached
the same Fetch refusal. The earlier instrumented listener was interrupted when the
user requested a fresh prompt and returned media/grant `unconfirmed`. Its exact
cleanup cause was not isolated: a process-group interrupt or Playwright's former
automatic signal handler could have closed Chrome before revocation. Its original
receipt remains unresolved and is not changed by the refreshed trial's confirmed
cleanup or the subsequent signal-ownership fix.

The fresh headed comparison on signed revision
`5474d0d9cf9b5125b9cddba811ed24680e00fd1a` used Linux x86-64 and Chrome
156.0.8078.47 with a fresh context and default host networking; exit country was
not independently measured. Authorization validated, then `live:virtualjapan`
refused at `player-load`: manifest GET, checkpoint `fetch`, refusal
`fetch-rejected`, SDK category 1/code 1002 and no readable HTTP status. No replay
or decoding followed. Media destruction and fresh-grant revocation were
confirmed, and the owned runner/server ended. This matches the headless failure
boundary without identifying its cause. That revision did not have the passive
CDP opt-in above; this change adds no new provider trial or active grant.

## Offline verification and outstanding acceptance

The media test command also executes the actual runner source with real Chrome
and a clearly synthetic proof page on a dynamic loopback port. This fixture has
no provider/OAuth requests, real grant, media APIs or capture. It signals only
the owned Node PID, separately with SIGINT and SIGTERM, and requires completion
of a delayed synthetic media/grant cleanup receipt before Chrome/server closure.
A negative control restoring Playwright's old automatic SIGINT handling exits
without that receipt. This verifies runner signal ownership, not actual provider
revocation or the cause of the earlier interruption.

The passive failure suite uses real Chrome and dynamic loopback servers for
missing-ACAO CORS, `redirect:error`, a refused TCP connection and successful Fetch.
It verifies closed failure categories without intercepting requests; only the
owned success-control body is consumed and discarded to avoid manufacturing an
abort failure by cancelling it. No provider body is read. The redirect
destination remains unrequested and credentials absent.
The original direct-collector fixture separately waits for an expected CDP event
to verify its schema. New regressions execute the actual runner source with a
synthetic proof page and normal Fetch for those four cases, without awaiting a
host callback or failure event between Fetch settlement and the preparation
return. Refused attempts exercise the production delivery window; success returns
ready and never drains. Default-runner, cancelled-drain and rejected-drain controls
verify independent protocol teardown and synthetic media/grant cleanup. No
request is intercepted, no real grant exists and no provider cause is inferred.
The actual-runner matrix additionally distinguishes missing-ACAO HTTP 200 and
403 through `first.http`, reports 302 for refused redirects, omits HTTP for
refused/reset TCP, and retains no failure for readable 200. Synthetic controls
cover both event orders, unrelated IDs, duplicate/poisoned IDs, overflow,
one-read private getters and late/expired callbacks. No response header, cookie,
body, URL, request-will-be-sent listener or additional protocol query is used for
correlation. This is a local mechanism test, not provider status acceptance.
Synthetic tests cover private-property sentinels, unknown enums, first-observed
latching, overflow, malformed output associations, stale generations and bounded
protocol failure, including never-settling disable/detach operations and their
late completion without changing frozen evidence. The runner signal fixture
also verifies that the opt-in session
detaches before the delayed synthetic cleanup receipt and that a CDP teardown
refusal or timeout does not prevent that receipt. Its default case creates no
CDP session.

A separate bounded Linux/headed Chrome 156.0.8078.47 reproduction on the prior
runner compared immediate freeze with a 250 ms delivery wait, three times each
for missing-ACAO CORS, redirect refusal, TCP refusal and success. Every failure
was absent at immediate freeze and observed once with the wait; all nine of
those observations arrived after preparation settlement. Success reported none
in both modes. All 27 natural/synthetic control attempts completed their owned
cleanup receipts, with no redirect destination requested, credentials observed
or unexpected requests. This establishes a local diagnostic event-delivery gap.
It does not identify the cause of the actual provider manifest Fetch refusal
or turn an unattributed browser failure into source-request association.

```sh
mise exec -- npm run build --prefix apps/web
mise exec -- npm run test:twitch:offline --prefix apps/web
mise exec -- npm run test:twitch:owned --prefix apps/web
mise exec -- npm run test:twitch:media --prefix apps/web
```

The session tests use only synthetic responses. They cover exact client/scope/
user validation, poll cadence/slowdown/deadline, catalogue and origin admission,
authenticated request shape and selected manifest binding, response bounds,
late-response cancellation, fresh-grant revocation, pending/concurrent destruction
and sticky cleanup failure.
The separate Chrome test uses intercepted synthetic OAuth/GraphQL responses and
the existing controlled fixture media. It decodes 640×360 frames, advances,
refuses unverified provider seeking and disposes the player/revokes the synthetic
grant. The existing harness suite verifies actual Rust-planned fixture seeking
separately. No provider request leaves this test. Its preview uses port 4178.
An additional ordinary-browser test uses two real loopback HTTP servers without
request interception. It confirms that a denied CORS preflight reaches the
server and prevents POST, an accepted preflight permits POST, cookies are omitted
and not adopted, and a redirect's forbidden destination receives no request.
Only explicitly synthetic host/fixture tests use intercepted responses;
it is not evidence of provider CORS or redirect behavior.

The media boundary suite uses a separate loopback fixture server on port 4190.
It invokes the same production plugin factory with an owned HTTP admission
callback; only fixture tests register HTTP, while production installation stays
HTTPS-only. Real local Fetch tests use no interception for CORS refusal, redirect
refusal, a closed TCP endpoint, readable 403, body-read interruption, ten-second
timeout, abort, bad-playlist HTTP 200 and delayed generation callbacks. SDK-only
failure and a clearly synthetic host stall verify the SDK/error projection and
thirty-second marker. The host-stall test intercepts synthetic OAuth/GraphQL
only and starts no media request. These tests retain no screenshots, recordings,
traces or unrestricted errors; all provider endpoints remain synthetic or blocked.
The deferred response/reader-cancellation races use synthetic in-page Fetch
responses to hold cleanup while another response arrives. They test latching,
and are distinct from the actual loopback CORS/redirect/TCP/body/timeout checks.

These tests cannot establish actual consent, CORS acceptance, provider entitlement,
advertisements, signed manifest/CDN behavior, live-window operation, audio or
renewal. The latest activated observations reached source preparation and stopped
at media Fetch refusal before playback. Any further provider diagnostic needs a
fresh reviewed trial; local test success is not provider acceptance.
