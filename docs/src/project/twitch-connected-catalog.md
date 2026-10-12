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
