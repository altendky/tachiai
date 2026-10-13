# Connected Twitch catalog prototype

## Implemented boundary

The debug Twitch Manage streams adapter uses the owning instance's shared
[Smart TV connection](twitch-catalog-authorization.md) through Twitch's supported
Helix API. Following, channel search, exact user lookup, live status and published
channel videos share the provider-neutral catalog interface and management screen.
Adding, removing or reordering changes Tachiai's local configured list only.

Following is the initial collection. **Live channels** is the unfiltered live
listing; it does not represent every offline account. Text search includes offline
channels recently active on Twitch. Exact user ID, login or public channel URL
lookup supplements search for accounts that are absent from those results. A
published video URL selects that exact replay, highlight or uploaded video.

The original implementation and synthetic fixtures did not establish
real-provider acceptance and made no account or provider requests. The later
[bounded Following observation](#bounded-following-observation--2026-10-12)
establishes one maintained account's first page through System network on
tachiai-dev. A later [bounded configured replay observation](#bounded-configured-replay-observation--2026-10-12)
establishes native playback of one existing saved replay with that shared scoped
connection. Other discovery operations, live playback, other replays, provider
refresh/revocation and imported-route behavior still need separate observations. Anonymous
native browsing still needs its access architecture; no client secret or app-token
backend is assumed. These limits remain in
[#99](https://github.com/altendky/tachiai/issues/99).

## Identity and availability

Connected channel resources use the immutable numeric broadcaster ID with kind
`broadcaster`, rather than the login or transient stream ID. Login and channel URL
inputs resolve through Get Users before Add. Subsequent refresh uses the saved ID,
so a renamed or recycled login cannot silently select a different account.
Historical prototype `channel` resources remain separate; they are not migrated.

Offline channels can be configured. Published videos retain their exact ID.
Missing resources remain configured and can be reported unavailable; neither
missing content nor unfollowing/Forget deletes Tachiai's entries. Future unpublished
video IDs cannot be inferred from schedules. Catalog availability and playback
entitlement are separate from saved identity.

Newly discovered playback remains **not verified** against a real provider.
The debug viewer can now prepare [configured exact videos](configured-catalog.md#configured-twitch-video-playback)
within its unchanged native replay operations, using a freshly validated lease
from the owning instance's shared connection and saved route.
[Configured broadcaster playback](configured-catalog.md#configured-twitch-broadcaster-playback)
uses guarded transient current-login mapping and checks that login's ownership
again before publication. Catalog and native preparation bind the same validated
user and durable grant generation. This shared scoped-grant use is newly selected
by the user; the later [bounded observation](#bounded-configured-replay-observation--2026-10-12)
verifies one existing configured replay. Live and newly discovered playback remain
unverified. Adding an item does not
select a sample under the new label. Earlier token-only LOCAL grants are not
copied into the shared connection or selected as an automatic fallback.

**History** is also listed, with access explicitly **not verified** regardless of
Following/account connection. Selecting it cannot retrieve history or trigger a
new authorization exchange. See [history access](provider-history-access.md#shipped-capability-reporting).

## Known schedule context

[Issue #122](https://github.com/altendky/tachiai/issues/122) adds the supported
[channel schedule GET](https://dev.twitch.tv/docs/api/reference/#get-channel-stream-schedule)
to connected exact broadcaster lookup and broadcaster refresh. It uses the same
catalog session and selected metadata route, without another scope. Following,
search and live-list rows do not make an extra schedule request per channel.
Exact video lookup and local public-video import are unchanged.

One page, requested with a limit of 20, supplies bounded context. The adapter
selects the minimum qualifying start in that returned page, strictly after a
fresh selection-time clock reading. It does not claim a complete calendar or the
globally earliest occurrence. Segment and vacation intervals must be valid.
Any non-null cancellation suppresses the occurrence; a cutoff is not compared
with now to re-enable it. Tachiai also excludes intervals overlapping vacation,
using half-open overlap, so touching boundaries remain eligible. This is a
conservative display policy: Twitch's [schedule guide](https://dev.twitch.tv/docs/api/schedule)
does not say that vacation cancels or removes those segments.

The decoder retains only required public ownership and timing fields, validates
bounded RFC3339 timestamps, and rejects wrong broadcaster or malformed data.
Documented nullable cancellation/vacation fields must be present; treating omitted
fields as invalid is application policy pending real response observations.
Pagination is validated if present and is not followed. A schedule-specific 404,
valid empty schedule or page without qualifying context omits the date. Other
failures remain explicit failures. A 401 refresh restarts user, status and schedule
metadata together under the new grant; stale or closed work cannot publish.

The shared manager labels the optional date **Scheduled**. Broadcaster identity,
CHANNEL intent and independently observed LIVE/OFFLINE status remain unchanged;
a schedule does not supply a video identity or playback entitlement. Preview
writes nothing. Explicit Add saves the date as a configured metadata snapshot;
duplicate Add preserves the existing item's metadata and quality. The separate
[explicit metadata-refresh action](configured-catalog.md#explicit-metadata-refresh)
can update an existing configured snapshot using its exact resource, preserving
local identity, order and quality. Saved dates are not automatically updated.
Actual schedule responses remain unobserved; the later account and Following
checks do not establish schedule acceptance.

## Account, routing and pagination

Each adapter is bound to one stable provider instance, its saved route, maintained
shared grant and active foreground owner. Validation precedes access. Dispatch
and result publication check ownership and the durable grant generation. Pause,
Forget, reconnect or a route change rejects stale work. Local configured entries
remain readable when provider access is unavailable.

A closed route purpose admits only `id.twitch.tv` and `api.twitch.tv`, for both
System and imported routes. The historical playback route policy is unchanged.
The metadata wrapper admits fixed HTTPS GET endpoints and validated query keys
only. Every exchange owns and releases its route; uncertain cleanup blocks reuse.
Missing or broken imported routes cannot fall back to System.

Opaque pagination handles are transient and bounded, bound to instance, grant
generation, query and parent. Provider cursors are never configured identities or
diagnostic data. Empty continuation pages do not imply the end of a collection.
Wrong-scope handles and cursor cycles fail closed. A current-generation 401 allows
one maintained-grant refresh and operation retry; a 503 has one bounded retry.
429 responses supply a bounded retry deadline. Errors do not expose response text,
credentials, private grant records or raw cursors.

The shared manager retains at most 500 unique discovery items per query and
explains when further results were withheld. Access loss or an invalidated
continuation clears old discovery before a retry; configured items remain.
Search and lookup drafts stay in memory and clear on reload, access loss and
closure. Account changes are detected at the next request or lifecycle boundary;
there is no immediate idle cross-process account-change observer.

### Shared validation and replacement ownership

Android account, discovery and native-preparation sessions now attach to one
maintenance coordinator for the exact protected storage, provider instance,
Smart TV client, Following scope and captured route configuration. Display-name
changes preserve that owner; another storage binding, client, scope or route does
not share its work. The coordinator owns each validation/refresh transport
independently of the consumer that started it. A still-active peer can keep the
work alive when that first consumer closes; the final consumer's cancellation
ends admission without moving cleanup onto the UI thread.

[Twitch requires startup and hourly validation](https://dev.twitch.tv/docs/authentication/validate-tokens/)
and recommends [one refresh worker distributing its result](https://dev.twitch.tv/docs/authentication/refresh-tokens/).
Its [public device flow describes refresh tokens as single use](https://dev.twitch.tv/docs/authentication/getting-tokens-oauth/#device-code-grant-flow).
The coordinator reuses the existing strict validation and atomic rotation
transactions. One original 30-second flight budget includes waiting, selected
route setup, HTTP, cleanup and final admission; joining does not restart it.
Refresh failures that may have consumed the credential retain the existing
non-replayable recovery marker.

An enrolled foreground consumer can adopt a proven replacement on its worker,
including a consumer that was not waiting for the refresh. Adoption checks the
exact predecessor, durable successor, user/client/scope, current owner, clocks
and original validation timestamp. It does not accept an unrelated newer
same-user record or restart hourly freshness. Same-pair validation may tighten
the durable expiry without rotating identity. The original seven-day local
retention cap for unknown provider expiry remains unchanged. Forced validation
requires fresh provider evidence; a completed replacement receipt alone does
not satisfy it. A stale unauthorized callback cannot spend another refresh.

The native viewer's existing worker checks now adopt a proven successor before
checking durable currentness. They may wait once for an already active flight
bound to the exact predecessor or its privately proved successor chain. This
never starts maintenance, joins an unrelated account, retries a refresh or treats
a newer stored record as proof. Preparation-lock contention, owner reads, waiting,
session-lock contention and final protected admission share one original
30-second caller deadline. The old local
lease must remain admissible throughout; expiry or lifecycle loss ends waiting.
A healthy completed attempt that supplies no proof leaves the original lease
subject to its strict durable check; failed waiting never takes that fallback.

UI admission remains memory-only. An exact predecessor can remain locally
admissible during the successful worker handoff, bounded by its unchanged expiry,
retention and lifecycle, so the UI cannot falsely close the viewer between the
session update and preparation update. Worker requests still require the exact
current durable lease. The selected
live/replay source, original source-acceptance deadline and five-minute native
experiment budget remain unchanged. A source-access exchange stays bound to the
exact lease whose token it captured; a rotation during that exchange refuses its
result rather than admitting the predecessor token or repeating the request.
Preexpiry maintenance scheduling and
uninterrupted hourly viewing remain unverified. Pause, Forget, reconnect and route
changes reject late work. Unconfirmed HTTP cancellation or route cleanup
retains the actual owners and blocks shared admission for the process. The
registry releases idle coordinators after their bindings and sessions close.
Real-provider rotation and sustained-viewer recovery remain unobserved;
[#212](https://github.com/altendky/tachiai/issues/212) stays open for these and
ABEMA's separately unverified native renewal path.

Initial coordinator verification on 2026-10-13 UTC passed 2,527 JVM cases in 295 reports, with no
failures, errors or skips; this includes 23 coordinator cases in both debug and
diagnostic variants, existing session coverage, and registry/owned-route fixtures.
Three timing regressions first failed on the prior implementation and passed
after correction. Lint, release isolation, instrumentation compilation, APK
assemblies and the shared debug signer checks passed. The final build was
installed on persistent `tachiai-dev`, Android 16/API 36/x86_64. Opening its
existing Twitch account screen reached CONNECTED automatically with no activation
prompt or manual Connect/Validate action, through its saved System route. All 20
files under `no_backup` and `shared_prefs` stayed byte-identical across installation
and observation, and the ordinary launcher was restored. No browser was used;
exit region was unmeasured. This establishes one saved-account startup validation,
not real refresh, concurrent-provider behavior or sustained-playback recovery.

Viewer adoption verification on the same date passed all 2,550 JVM cases in 295
reports with no failures, errors or skips, including 30 coordinator cases in both
variants and 20 preparation cases. Fixtures cover completed and in-flight peer
rotation, consecutive proved successors, UI handoff, old expiry, caller deadline,
rollback, owner/lifecycle loss, Forget, unrelated replacement and cleanup poison.
The source-access binding regression first accepted an invalidated predecessor
exchange with its guards removed, then refused it with the guards restored.
Lint, release isolation, instrumentation compilation, APK assemblies, both shared
signers, scoped hooks and independent review passed. Compilation reported six
existing safe-call warnings in the unchanged replay instrumentation fixture.

The updated build on persistent `tachiai-dev` passed one existing configured
replay check in 31.317 seconds through the saved System route. Its retained account
made one official validation and one exact replay-access request, both HTTP 200,
followed by four admitted media requests. The native player rendered a frame and
advanced between READY observations; the muted quality, owner/source preservation,
original budget and actual native/HTTP/route cleanup checks passed. All 20 saved
files stayed byte-identical across installation, playback and launcher restoration.
The ordinary test manifest was retained. This check uses the harness's read-only
grant binding and establishes normal source preparation/playback compatibility;
shared rotation remains fixture evidence. Exit region was unmeasured and no
browser or new consent flow was used. It does not prove sustained playback,
preexpiry scheduling or media authorization renewal.

A follow-up regression confirmed that an unrelated completed validation attempt
could discard another viewer's admissible lease or its unadopted proved successor
after HTTP 500 or network failure. Markerless temporary outcomes now remain local
to the maintenance callers instead of overwriting peers' proof. Peers still
repeat durable identity, owner, expiry and freshness checks; initiating callers
receive the failure, and cleanup poison or actual recovery markers still reject
access. Both cases passed with server and network failures after the existing
lease case first failed. The final full rerun passed 2,552 JVM cases in 295 reports
with no failures, errors or skips, plus the same Android and signing checks.
The receipt-preservation APK also passed the same bounded replay check in 29.016 seconds, with
one validation/access pair (HTTP 200), four media requests, frame/movement,
confirmed cleanup and all 20 saved files unchanged. Its final diagnostic was
ADMISSION alongside REPLAY_ACCEPTED and no feed failure. That diagnostic is shared
with teardown callbacks; the recorded line does not establish when it changed.
The accepted playback outcome and cleanup receipts are recorded separately.

The overlapping no-proof case first failed, then passed with both HTTP 500 and
network failure while the viewer was deterministically waiting on the peer's
active attempt. Healthy completion now leaves strict original-grant admission
available; REFRESHING, recovery markers, changed generations, expiry and failed
waiting remain refused. Owner or protected-read delays cannot restart the original
preparation deadline. The complete final verification passed 2,554 JVM cases in
295 reports, with no failures, errors or skips, and the Android/signing checks.
One replay check on that exact final APK passed in 26.176 seconds with the same
one validation/access pair, four media requests, READY movement, confirmed cleanup,
diagnostic NONE and all 20 saved files unchanged. The normal launcher was restored.

## Local exact-video import

When a successful capability assessment reports catalog lookup unavailable, the
debug adapter offers a local lookup for a bare HTTPS Twitch video URL. This path
stores the exact numeric video identity with a fixed unverified label and unknown
availability; preview performs no provider request and Add remains explicit.
It requires a current foreground Twitch instance, but does not require a usable
metadata route or account grant. Unreadable instance/configured storage still
blocks ownership or persistence. Closing or changing the owner rejects late work.

The local path rejects channel URLs, logins, bare IDs, credentials, queries,
fragments and other unsupported input. Connected lookup continues to resolve
metadata and report its original failures. There is no same-request fallback from
a connected failure to local import. Reloading after access loss clears private
entries, pagination and drafts before a fresh local lookup. Capability assessment
may validate an existing grant; this is distinct from the request-free local
lookup. Imported resources do not broaden playback or network policy.

## Verification limits

Contract fixtures cover supported metadata/input forms, offline and missing items,
canonical identity, pagination, account access, rate limits, refresh and stale
results. Route fixtures cover confinement, cancellation and failed cleanup.
Shared manager fixtures cover initial collection, global search and configured
persistence through the same interface used by ABEMA.

The original Android UI and decoder checks used synthetic isolated fixtures.
They did not establish actual account acceptance, physical-device playback, DRM
or TV behavior. A bounded consent check observed exact scope, refresh presence and
omitted token expiry, followed by pre-validation rejection. A later check reached
official validation, passed identity/scope checks and returned zero expiry,
then was rejected by the former positive-expiry policy. See the
[authorization evidence](twitch-catalog-authorization.md#verification-limits).
Those historical checks did not establish validated account access. The later
maintained-account and Following checks below establish a narrower positive
result. Anonymous discovery remains unobserved. The later configured replay check
below establishes one selected source's native acceptance.
Historical zero-scope playback observations do not establish the shared scoped
connection's provider acceptance.

## Bounded Following observation — 2026-10-12

After the owner's [normal QR approval and saved-grant validation](twitch-catalog-authorization.md#maintained-account-acceptance--2026-10-12),
one explicitly opted-in `TwitchFollowingDeviceTest` passed on persistent
tachiai-dev: emulator-5580, Android 16/API 36, x86_64, configured **System** route,
foreground ordinary app process. The read used the maintained account, not a
guest. No provider browser or media player was opened; region was not separately
measured.

The production catalog session and adapter performed one official validation
GET, one first Following page GET with limit 20, and one Streams GET for exactly
the returned broadcaster IDs. All three returned HTTP 200. The adapter produced
20 unique canonical broadcaster resources with CHANNEL intent: four LIVE and
16 OFFLINE according to that status response, without schedule requests. The
Following page had a continuation handle; it was not followed. This is a snapshot
of that first page, not a complete account listing or a claim that those channels
remain live.

The device check executed one test with zero skips and passed in 4.966 seconds.
It independently checked captured route/instance ownership, the stored grant
generation, operation bounds and definite cleanup. All 20 files under
`no_backup` and `shared_prefs` had identical before/after digests. The normal
launcher screen was restored. No refresh, rejected-bearer probe, reconnect,
Following continuation, configured Add/removal, history or playback was attempted.

Two earlier checks failed because the fixture admitted only one Streams page:
the provider returned a status cursor and the production adapter correctly
requested its continuation. Both failed checks preserved the same 20 files.
The final fixture allows at most one status continuation for the same exact ID
batch, with the unchanged provider cursor and response; retries, unrelated
requests, cycles and a third page are refused. In the passing run the first
status response had no cursor, so a real second status page was not exercised.
Existing synthetic adapter coverage exercises status pagination separately.

Response bodies, credentials, broadcaster IDs, logins, titles, cursor values,
activation codes and record digests were not included in diagnostics or this
record. The positive marker contains only closed categories, HTTP statuses,
counts and booleans after preservation and cleanup checks. The fixture refuses
observed unfinished encrypted-file state before reads and assumes an idle device;
it does not claim exclusion of an external writer racing those checks.

This establishes bounded real Following access on one emulator. A separate
[bounded Continue Watching check](provider-history-access.md#maintained-account-continue-watching-acceptance--2026-10-12)
also passed. Search, exact
lookup, published-video discovery, schedules, imported routes, refresh/revocation,
broader native playback and history behavior remain separate acceptance work under
issues #99 and #102. The later replay check below establishes one configured source.
Physical-device and TV acceptance are not established.

## Bounded configured replay observation — 2026-10-12

An explicitly opted-in `ConfiguredTwitchPlaybackAcceptanceDeviceTest` used one
existing saved replay on verified persistent tachiai-dev: emulator-5580, Android
16/API 36, x86_64. It retained the maintained account's shared scoped Smart TV
grant and the owning instance's **System** route. Playback used native Media3 in
the foreground ordinary app process, without a provider browser; region was not
separately measured. The saved record contained five sources and one eligible
replay. The fixture privately selected that replay and checked its exact item,
resource, quality, record origin and route ownership. It did not add a source or
substitute a sample. The existing historical start-position rule was retained;
diagnostics did not record whether this source matched that historical identity,
so this observation does not establish newly added or arbitrary-video playback.

The first same-source observation executed one test with zero skips and failed
the movement check in 23.768 seconds. Official validation and the exact video
access request both returned HTTP 200, and an actual frame was rendered. Closed
native events included READY, PLAYING and VIDEO_FRAME followed by BUFFERING, but
the fixture did not establish two advancing READY samples. Its second sample's
state and the cause of buffering were not established. Preparation, worker,
native player, HTTP, route and Activity cleanup were confirmed; owner, item and
protected records were unchanged. This failed observation is not playback
acceptance or evidence of a production defect's cause.

A fixture-only correction polls locally for at most ten seconds, keeps its first
qualified nonnegative READY position on the same player, and requires another
READY sample at least 1.5 seconds later with a strictly increasing position.
Buffering preserves that baseline. Failure, foreground and continuation guards
remain active. READY/frame waits and sampling finish within the original run's
50-second observation limit, reserving 20 seconds for cleanup within the unchanged
70-second outer budget. This adds no endpoint, authorization or source retry and
does not extend provider or playback budgets.

One disclosed repeat of the same saved source passed: one test, zero skips,
29.784 seconds. The production catalog preparation made exactly one positive
validation request and one exact video access request, both HTTP 200, followed by
four production HLS requests. It rendered a frame and observed the same player in
READY at both position samples, at least 1.5 seconds apart with increasing
positions. Saved quality was accepted, mute and play requests succeeded, and no
feed failure was observed. Playback remained within budget. Preparation settled,
the worker terminated, and native player, HTTP, route and Activity cleanup were
all confirmed. This repeat does not establish why the first check failed or claim
a production playback fix.

The fixture's owner, exact configured item and protected-record checks passed.
Independent hashes of all 20 files under `no_backup` and `shared_prefs` were
byte-identical before both probes and after the repeat. No grant, profile, source,
Add, sign-in, refresh, reset or CDN policy change was made. The production APK was
unchanged; the ordinary shared-signed test APK was installed, its manifest had no
process overrides, and the normal launcher screen was restored. Diagnostics
contained only closed categories, HTTP statuses, counts and booleans; credentials,
source identities, URLs, response bodies and record digests were not recorded.

This establishes bounded native acceptance of one existing configured replay,
including shared scoped-grant use, rendered video, advancing positions and
definite cleanup. It does not establish the full picker/Add interaction, live
playback, other replays, imported routes, refresh/revocation, sustained playback,
audible output or physical-device/TV behavior.
[Issue #99](https://github.com/altendky/tachiai/issues/99) remains open.

## Bounded configured broadcaster acceptance harness

A separate explicitly opted-in method exercises one existing canonical Twitch
broadcaster through the production identity resolver, catalog preparation and
native session. It requires the provider instance and accepts an optional exact
configured-item UUID. Without that item argument, it freezes the first locally
eligible canonical broadcaster before any provider request. Legacy login-based
channel entries are excluded. A missing or offline item is a refusal; the check
does not scan other channels or substitute a sample.

The retained grant, captured route, exact configured item and saved quality remain
bound throughout the run. Its narrow request sequence validates the account,
looks up the frozen broadcaster ID and current live status, requests playback for
the verified login, then confirms that login still belongs to the original ID
before player publication. It allows at most two positive validations because
native preparation can require newer proof. Negative responses are refused before
refresh or retry handling. The fixture's read-only sessions share a private
coordinator; this tests the production provider/session components without
claiming the Android registry's complete lifecycle.

The existing 70-second outer budget reserves 20 seconds for cleanup. Acceptance
requires an actual frame, two READY position samples at least 1.5 seconds apart
with increasing position, applied quality/mute/play controls, no feed failure,
unchanged owner/item/protected records and confirmed native, worker, resolver,
HTTP, route and Activity cleanup. Uncertain owners remain retained. Diagnostics
contain only closed categories, counts, HTTP statuses and booleans. The replay
method retains its independent criteria. No consent, configured Add, source
replacement, clock forcing or media-policy expansion is part of this check.

Two bounded checks of the same saved broadcaster refused before any playback
access or media request: one test each, zero skips, 7.353 and 9.213 seconds. The
second check's closed diagnostics established one strictly parsed live row
matching the saved broadcaster ID and current login, with a pagination cursor.
Both positive account validation and the two identity/status requests returned
HTTP 200. Cleanup and protected-record preservation passed in both refusals.

The production resolver had rejected every stream-status response containing a
cursor. [Twitch's Get Streams reference](https://dev.twitch.tv/docs/api/reference/#get-streams)
describes the user-ID filter and cursor navigation. One exact live row establishes
that broadcaster's presence even when navigation metadata is present. The
resolver now accepts that row while retaining strict parsing and the final
login-to-ID confirmation. An empty page with a cursor remains indeterminate;
terminal empty pages remain offline, and duplicate or mismatched rows remain
refused. No continuation request is introduced and collection completeness is
not inferred. A new JVM regression failed on the previous predicate and passed
after this correction; it also checks confirmation, no continuation or refresh,
and unchanged grant bytes.

Full local verification passed: 2,677 JVM tests across 299 reports, zero failures,
errors or skips; Android lint, release isolation, instrumentation compilation,
both APK assemblies and the shared debug signing certificate. Fifteen selected
provider-free emulator cases passed with zero skips, including the nine new
sequence, cursor, request-header and actual HTTP cleanup guards and six existing
configured-broadcaster session cases. Scoped hooks and an independent review
passed. The ordinary test manifest has no process overrides.

One disclosed corrected check used the same saved broadcaster on persistent
`tachiai-dev`, Android 16/API 36 x86-64, retained scoped Smart TV account and saved
System network route. WebView was 133.0.6943.137; geographic region was not
established. It executed one test with zero skips and failed after 26.479 seconds.
Two account validations, all three identity/status/confirmation requests and the
single exact playback access request returned HTTP 200. The same matching live
row still contained a cursor. Six production media requests ran, and an actual
frame rendered. Saved quality, mute and play requests succeeded without a feed
failure, but sampling began READY and ended BUFFERING without obtaining a second
advancing READY sample within the unchanged bound. This verifies removal of the cursor refusal and frame
delivery; it does not establish native channel playback acceptance or explain
the buffering cause. No sampling or acceptance gate was weakened.

Preparation settled, the worker terminated, and native player, resolver, HTTP,
route and Activity cleanup were confirmed. Owner, exact item and protected-record
checks passed. All 20 files under `no_backup` and `shared_prefs` were independently
byte-identical before installation, after the provider-free cases and after the
native check. No consent, grant refresh, source/profile/route mutation, fallback
or media-policy expansion occurred. The normal launcher was restored.

This observation is separate from the successful replay check above. Full
configured-channel playback acceptance, picker/Add interaction, imported routes,
refresh/revocation, sustained playback, audible output and physical-device/TV
behavior remain unverified. [Issue #99](https://github.com/altendky/tachiai/issues/99)
remains open.
