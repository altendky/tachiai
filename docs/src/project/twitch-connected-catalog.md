# Connected Twitch catalog prototype

## Implemented boundary

The debug Twitch Manage streams adapter uses the owning instance's separate
[catalog authorization](twitch-catalog-authorization.md) through Twitch's supported
Helix API. Following, channel search, exact user lookup, live status and published
channel videos share the provider-neutral catalog interface and management screen.
Adding, removing or reordering changes Tachiai's local configured list only.

Following is the initial collection. **Live channels** is the unfiltered live
listing; it does not represent every offline account. Text search includes offline
channels recently active on Twitch. Exact user ID, login or public channel URL
lookup supplements search for accounts that are absent from those results. A
published video URL selects that exact replay, highlight or uploaded video.

The implementation and synthetic fixtures do not establish real-provider
acceptance. Scoped consent, actual Helix responses, provider refresh/revocation
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

Newly discovered playback remains **not verified**. The existing native viewer
bridges only its exact historical prototype resources. Adding a channel/video does
not expand native playback exceptions or select a sample under the new label.

## Account, routing and pagination

Each adapter is bound to one stable provider instance, its saved route, maintained
catalog grant and active foreground owner. Validation precedes access. Dispatch
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

## Verification limits

Contract fixtures cover supported metadata/input forms, offline and missing items,
canonical identity, pagination, account access, rate limits, refresh and stale
results. Route fixtures cover confinement, cancellation and failed cleanup.
Shared manager fixtures cover initial collection, global search and configured
persistence through the same interface used by ABEMA.

Android UI and decoder checks use synthetic isolated fixtures. Emulator results
do not establish actual account acceptance, physical-device playback, DRM or TV
behavior. The separate account connection, anonymous discovery and selected-source
playback limits must remain visible until their own evidence is obtained.
