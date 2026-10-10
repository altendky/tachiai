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

The implementation and synthetic fixtures do not establish real-provider
acceptance. Scoped Smart TV consent, actual Helix responses, native playback
acceptance and provider refresh/revocation
and imported-route behavior still require supported account/device observations.
No account or provider requests were used to implement this slice. Anonymous
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
by the user; real native acceptance remains unverified. Adding an item does not
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
Actual schedule responses and account/route acceptance remain unobserved.

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

Android UI and decoder checks use synthetic isolated fixtures. Emulator results
do not establish actual account acceptance, physical-device playback, DRM or TV
behavior. Scoped Smart TV consent and lifetime/refresh shapes, anonymous discovery
and selected-source playback limits must remain visible until their own evidence
is obtained. Historical zero-scope playback observations do not establish the
shared scoped connection's provider acceptance.
