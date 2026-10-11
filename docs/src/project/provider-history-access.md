# Provider watch-history access

This is partial research for
[issue #102](https://github.com/altendky/tachiai/issues/102), reviewed on
2026-10-09. History can help discover previously viewed channels and on-demand
items without making them favorites. A bounded undocumented ABEMA read-only
adapter is now implemented as described below; intended-account retrieval
remains unverified. An optional bounded Twitch Continue Watching adapter is
implemented below, with real Smart TV compatibility still unverified. No
comprehensive Twitch account-history retrieval path was established; #102
remains open.

## ABEMA read-only History adapter — 2026-10-11

The debug ABEMA catalog exposes History through the same collection browsing
and explicit Add interface as My List. It retrieves provider-owned viewing
history rather than recording Tachiai usage. Browsing does not mutate provider
history, automatically configure an item or remove existing configured choices.

Coherent first-party web bundles identify a bearer-authenticated GET on the
reviewed API origin at `/v1/video/viewing/histories`, with `limit=20`,
`include=liveEvent` and an optional opaque `next` continuation. Twenty is the
selected client page bound, not a verified provider maximum. The adapter
preserves provider order; it does not infer a chronological ordering contract.

Strict projection joins each history reference's type and source ID to its
matching timeshift, video or live-event dataset entry. Timeshifts retain exact
channel/slot broadcast identity; videos retain exact episode identity; live
events retain their own broadcast identity. Duplicate references retain the
first exact resource. Missing, conflicting or unknown joins refuse the page.
Only public identities and titles enter the catalog. Availability stays UNKNOWN;
viewed time, progress, completion, scheduling and entitlement are not inferred.
Responses, datasets and continuation values have finite local bounds.

History and My List use separate account bindings and separate endpoint proofs.
History is available only after fresh intended-account validation, an invalid
bearer refusal and a strictly parsed positive History page with confirmed
cleanup. Profile or My List success does not establish History access. Collection
kind is bound into transport admission, response projection, lease context,
the one-use in-memory first-page seed and continuation handles. A History
authorization refusal discards its private browsing state without erasing the
protected grant or revoking an independently valid My List binding. Uncertain
resource cleanup still blocks account admission for the entire provider instance.

The opt-in `AbemaHistoryDeviceTest` requires `tachiaiReadOnlyAbemaHistory=true`,
an explicit provider instance, ordinary application-process targeting and the
verified tachiai-dev AVD. It skips before private access by default. After the
owner completes account approval, it requests one History browse through the
production factory, without probing My List or requesting another page. It
checks encrypted-record and route ownership preservation, foreground/deadline
gates and exact conservative projection, and logs only closed outcomes and
counts. A separate protected-record baseline must be established after the
owner's intentional grant write. No real account or device check has yet run;
source fixtures cannot establish account access, history coverage or retention.

## Twitch bounded Continue Watching adapter — 2026-10-11

The debug Twitch catalog additionally offers **Continue watching** through the
shared collection and explicit Add interface. This is provider-owned unfinished
VOD viewing, not complete chronological History, Following membership or
Tachiai usage recording. Comprehensive History remains NOT_VERIFIED. Browsing
never configures entries automatically, modifies provider history or removes
already configured exact videos. Refresh and native playback keep their existing
independent metadata, account and entitlement checks.

The first-party `FollowedStreamsContinueWatching` query and
`ContinueWatchingViewedVideoEdge`/`PreviewCardVideo` fragments select
`currentUser.id`, the first twenty `viewedVideos` edges, their `history.position`
and `history.updatedAt`, and video identity, title, duration and owner identity.
The reduced fixed query requests only those known fields. There is no cursor or
invented pagination. Twenty is the first-party client's selected bound, not
evidence of full account-history coverage or a provider maximum.

The first-party shelf preserves provider edge order, includes nonzero progress
and duration updated within an inclusive fourteen-day cutoff, and defaults to
at most 95% watched, including exactly 95%. Its threshold is dynamically
configurable on Twitch. Tachiai implements that bounded default projection; it
does not claim complete shelf parity. Progress/duration use numeric seconds
ratio arithmetic, while update time is passed to JavaScript Date by that client.
Raw schema types and nullability are not verified. Tachiai's local restrictions
accept finite nonnegative numeric progress/duration no greater than one year,
integer epoch milliseconds or a strict ISO instant for update time, unique exact
video IDs and valid owner IDs/logins. Strict UTF-8 JSON rejects duplicate object
members, unknown joins, malformed/error pages, excessive nesting and a response
over 64 KiB. These are conservative local bounds, not a provider contract.
Only exact VIDEO resources, public titles and UNKNOWN availability enter the
shared catalog; progress, update times and provider responses are not persisted
or displayed and cannot extend grant retention.

The transport sends one fixed JSON POST to `https://gql.twitch.tv/gql` through a
separate closed route purpose. It uses the same maintained Smart TV client and
OAuth grant as account-backed discovery, with a private no-cookie/no-ambient-auth
client even on System. It does not copy browser cookies, request another scope,
retry with a browser/blank client identity, fabricate integrity headers or follow
redirects. Exact client/user/Following scope validation, original seven-day
unknown-expiry retention, captured instance/route/generation, foreground ownership
and final protected-record rereads remain required.

Collection access defaults to NOT_VERIFIED. A separate proof requires an actual
HTTP 401/403 response to an invalid sentinel grant followed by HTTP 200 and a
strictly parsed page naming the intended maintained account. A successful
ordinary account check or playback lease cannot establish this proof. HTTP 200
with null `currentUser`, GraphQL errors or a different user cannot establish it
either. The first positive page is kept in RAM for one use only; its proof and
original combined operation have a thirty-second bound. No provider-specific
error text is guessed into an authorization result. An endpoint refusal clears
only Continue Watching proof and its seed, preserving a valid Following grant.
Unverified/refused access is never reported as a successful empty collection.

Every HTTP owner, close receipt and temporary route remains owned until cleanup
is confirmed. Unknown cleanup retains the actual handles and poisons admission
for that exact provider instance for the lifetime of the process. The existing
Android connection binding checks this for account validation/Connect, catalog
operations and selected-resource playback; new facades cannot bypass it. Local
Forget does not infer cleanup or release retained handles. There is no timeout,
late-receipt unpoisoning or automatic route/backend release while HTTP remains
uncertain. A normally observed collection HTTP refusal does not poison admission.

Public primary evidence fetched cookie-free, without authorization, redirects,
retries or script execution on 2026-10-11:

| Public asset | Bytes | SHA-256 |
| --- | ---: | --- |
| [Query and shelf filter](https://assets.twitch.tv/assets/90896-1e602a6ec0b0b67636b5.js) | 14,706 | `d1d2787210630c7d0d169051b5794ff268a6db33b30be1ba2a2ac4d7c80b5a6f` |
| [Video fragment](https://assets.twitch.tv/assets/pages.following.components.following-continue-watching-4073694f3703384e4565.js) | 6,771 | `6a8244ca347393eb9676f36a866661d0dd824ef2c4e144adbea8408572dbf3e4` |
| [Entry HTML](https://www.twitch.tv/) | 203,359 | `ffc69f74767466a91da43c06e8fcf4ce0710c51dee4ff2f7fccaeeec4a694d38` |
| [Direct core bundle](https://assets.twitch.tv/assets/core-ec1e57f743ef3b2d5fa6.js) | 950,047 | `0ab994272d818080de5c2a5901c3805f06f821eb9cce17755dd6ca5ba5c9088f` |
| [Direct entry bundle](https://assets.twitch.tv/assets/51010-c3c438f2c6a3b853753d.js) | 14,064 | `1433fe889d6ff3e461bf687fb43265302ebf7deef42736e4e11992cd6f82ed2d` |

The HTML manifest references the exact query/video assets above, establishing
coherent public provenance. The two directly referenced bundles do not expose
the imported query hook's complete transport construction. The HTML's separate
PlaybackAccessToken warm-up posts OAuth with a browser client identity and an
optional device cookie; it does not establish Continue Watching's mandatory
headers, cookie/integrity requirements or compatibility with the Smart TV grant.
No real-account GraphQL request or device observation has run. A provider refusal
or incompatible negative-proof convention may keep this optional collection
unavailable while Following works normally.

`TwitchContinueWatchingDeviceTest` skips before private access by default. Its
explicit opt-in is `tachiaiReadOnlyTwitchContinueWatching=true` plus a
`twitchProviderInstance` and verified tachiai-dev AVD in the ordinary app process.
It requires an already READY grant with more than ninety seconds of retained
validity, maintains foreground/route ownership, forbids protected writes and
consent/refresh/reconnect, and refuses validation that would require expiry
tightening. It exercises the same production collection/session/route classes,
but this extra write-refusing fixture guard is not ordinary connection behavior.
One sentinel/positive proof consumes at most one intended-account page with no
retry, continuation, Add or playback. It verifies encrypted-record and route
preservation and logs only closed result categories and entry counts. Intended
account, scalar shape, empty/unfinished VOD behavior and native playback still
require separate authorized runtime acceptance; synthetic tests prove only the
local safety and projection contract.

## User-approved meaning and access — 2026-10-10

The requested feature shows **provider-owned viewing history inside Tachiai**
so the user can explicitly add previously watched channels, shows or exact
videos to the configured list. Tachiai usage tracking, local viewing recording
and analytics are not authorized by this feature and require a separate product
discussion. Browsing history does not automatically configure entries or mutate
provider history.

Direct retrieval of the intended account's history is the primary path to
investigate. Prefer documented APIs; where none exists, the user now authorizes
research and implementation through undocumented provider endpoints under the
[general integration scope](security-and-privacy.md#general-provider-integration-approval--2026-10-10).
The initial absence of a documented API is not another permission blocker.
Account ownership, legitimate access, pagination, metadata semantics and privacy
still require verification before advertising retrieval.

A manual import from a provider export selected by the user is a possible
fallback, not an accepted initial UX or an established schema. No export has
been supplied or examined. It must not become a prerequisite for direct history
integration, automatically request exports or persist a broad account-data copy.
The updated [issue #102](https://github.com/altendky/tachiai/issues/102) records
these distinctions and the independent account-backed dependencies.

## Research boundary

Official help, API and authorization documentation supplied this evidence.
Host-browser engine, region and account state were not established. No login,
account data/export, device, credentials, provider contact, private endpoint or
playback experiment was used. Some help pages required full article URLs or
indexed official text. These are published features, not observed user history.

## Capability and access matrix

| Provider/surface | Documented behavior | Retrieval/access finding |
| --- | --- | --- |
| ABEMA history UI | Catch-up programs and played videos in its history page | Existing-account feature confirmed; supported external retrieval not verified. Coverage of all live viewing is unknown. |
| ABEMA account | Automatically issued account identity owns My List and history | Current fresh-guest playback does not connect the user's existing account. Paid membership is a separate property. |
| Twitch personalization/Continue Watching | Recommendations use previous viewing; a Following-page shelf includes previously watched completed streams | A partial history-derived surface, not comprehensive chronological history or a verified third-party retrieval API. |
| Twitch data request | User-requested export includes live/VOD viewing and chat activity | A provider export route exists; automatic history browsing/import is not implemented or verified. |
| Twitch Helix/scopes | Published videos and Following have documented endpoints | No account watch-history endpoint or matching scope found in the reviewed reference and scope inventory. |

ABEMA's [history help](https://help.abema.tv/hc/ja/articles/360020600092)
describes its own history page and deletion controls. Its
[account documentation](https://help.abema.tv/hc/ja/articles/360025402691)
links preferences and history to the automatically issued identity. Neither
page supplies an external history interface. See the separate
[ABEMA access research](abema-catalog-access.md) for account-connection limits.

Twitch documents viewing-based
[content personalization](https://help.twitch.tv/s/article/how-to-customize-content)
and a Continue Watching shelf on its Following page containing previously
watched completed streams. That partial history-derived surface does not make
Following membership or recommendations into comprehensive account history.
Neither establishes a native retrieval endpoint. The reviewed
[API reference](https://dev.twitch.tv/docs/api/reference/) and
[scope inventory](https://dev.twitch.tv/docs/authentication/scopes/)
did not establish account-history access. This is a bounded negative finding,
not proof that no other supported integration exists. Experimental playback
grants and a Following scope do not become history authorization.

Twitch's [data-request guide](https://help.twitch.tv/s/article/requesting-your-data-from-twitch)
documents desktop export requests with categories/date ranges and completion
within 14 days. Viewing/chat data covers live/VOD activity, including per-day/
per-channel watched minutes, page visits, chat and searches. The VOD-history
category concerns changes to the user's own videos, not viewing. No export was
examined. A future import needs format/privacy validation; exports do not
establish real-time pagination, exact video IDs or resume/completion metadata.

## Shipped capability reporting

[Issue #130](https://github.com/altendky/tachiai/issues/130) makes **History**
explicit in the shipped sample/ABEMA and connected Twitch adapters. Its access
is **not verified**, independently of whether Following or a catalog account is
connected. The current evidence does not justify a definitive provider-wide
unsupported claim or a promise that connecting an account unlocks retrieval.

Direct Twitch History browsing fails before catalog validation, refresh or
metadata HTTP; the sample ABEMA adapter also returns not verified. The native
ABEMA account adapter now has a separate conditional History path described
above; its access depends on its own endpoint proof. The existing
shared controller preflight skips unavailable collection browsing, clears the
discovery surface and retains configured items. Users can return to ordinary
All/Following/My List discovery with its existing access rules. Generic unverified
copy now states the uncertainty rather than asking for connection as a remedy.

Production adapter, controller and Compose fixtures cover reporting, request
suppression, retained configured items and return to ordinary discovery. This is
implemented capability reporting, not real history retrieval or account acceptance.
That earlier #130 change added no endpoint, scope, export import,
viewed-time/progress field or history mutation. The new bounded ABEMA endpoint
adapter still requires intended-account acceptance; #102 remains open for that
and the remaining Twitch retrieval and privacy requirements.

## Content identity, timing and availability

History differs from explicit My List/Following and local Tachiai viewing logs.
Cover live channels, catch-up/replays and never-live on-demand items according
to verified returned identities. Preserve channels, exact videos and series
collections distinctly; collections require explicit child selection. Viewing
a channel does not identify an exact replay.

Twitch [Get Videos](https://dev.twitch.tv/docs/api/reference/#get-videos)
retrieves published videos by video ID, broadcaster or category; it is not an
account's watched-video list. Its creation/publication times are not viewed
times, and video duration is not a user's progress. Twitch's
[video documentation](https://dev.twitch.tv/docs/api/videos)
describes archives, highlights and uploaded videos, with availability limited
by provider retention or deletion. History membership would not confer playback
rights or prove that a video still exists.

ABEMA explains that catch-up content has
[program-specific viewing limits](https://help.abema.tv/hc/ja/articles/360013513952)
and some programs are unavailable after broadcast. It separately documents
[automatic removal of expired My List items](https://help.abema.tv/hc/ja/articles/4434908040217).
That is not evidence that history follows the same expiry rule. Keep provider
history retention, content availability and Tachiai-owned retention separate.

Viewed time, ordering and resume/completion metadata should appear only when a
verified source supplies them with understood semantics. The current shared
entry model has no viewed-time/progress fields. Add bounded transient fields
only after the retrieval design proves a need; never fill them from download
time, publication time, aggregate minutes, array order or guessed timestamps.

## Shared consumer fixtures and integration gates

Reuse the instance-bound `ProviderCatalog`, optional collections and storage from
[issue #96](https://github.com/altendky/tachiai/issues/96), through the common
management flow in [issue #97](https://github.com/altendky/tachiai/issues/97).
Use the common picker and identities. Distinguish unsupported, not verified,
authorization required, reconnect required and missing scope from empty success.

Public synthetic history adapters now exercise the actual shared controller,
screen and protected configured-record codec, independently of provider access:

- Populated, empty and paginated collections, including an empty intermediate
  page and duplicate resource identities across pages.
- Exact video and collection identities, explicit child choice, and
  expired/unavailable entries that remain eligible for explicit local Add.
- Add/deduplication and local removal through the real configured store;
  browsing alone writes nothing and local removal leaves upstream fixtures intact.
- Access loss clears discovery rows, continuation, search and lookup drafts;
  retry revalidates the same requested collection without substituting All.
  An invalidated continuation also clears old rows before retrying the collection.
- Closing clears discovery immediately; late responses cannot restore it,
  while configured items and already accepted local writes remain preserved.
- A 500-unique-item limit per query retains the first items, stops continuation
  with an incomplete-results notice, and resets for a new query.

These are shared consumer fixtures, not verified provider history, content-type
coverage or account acceptance. The fixture entries do not invent viewed time,
progress or history ordering semantics. Integration still must verify the
intended account, query/instance/session-bound cursors, pagination/ordering,
normalized identities and access loss. Upstream deletion never removes
configured items. History mutation and local viewing recording remain outside
issue #102. The discovery-cache behavior is tracked in
[issue #116](https://github.com/altendky/tachiai/issues/116).

## Privacy, ownership and remaining dependencies

History is private account data. Adapter/controller work must own the selected
provider instance, its verified account and route. No silent System-route,
different-account, fresh-guest, Following, broadcaster-archive or local-history
fallback is acceptable. Missing or revoked access remains explicit.

The shared manager retains at most 500 unique discovery entries per query.
Search and lookup drafts stay in memory, outside saved Activity state; access
loss, close and controller replacement reset them. Provider adapters still must
detect account/session changes and reject stale responses and continuations so
the manager can clear account-derived discovery. Detection happens at the next
request or lifecycle invalidation; this does not establish an idle cross-process
account-change observer. Logs, diagnostics and recovery
checkpoints must exclude history
lists, viewed times, progress, tokens, private URLs and raw responses. Persist
only required public resource/display metadata for items explicitly added by the
user, under the existing protected configured-store boundary. A broad provider
export is not a browsing cache and must not be copied into that store.

The ABEMA account-backed portion requires
[issue #103](https://github.com/altendky/tachiai/issues/103)'s working
instance-owned connection **and a verified history-retrieval path**.
Connection success alone does not establish collection access. Twitch has its
own access prerequisite; it does not depend on ABEMA account connection. Research
and generic fixture/UI work remain independent, and history must not block the
initial public catalog/Following/My List issues.

Before retrieval, verify access, identities, metadata semantics, ownership and
retention. Investigate direct account history first; any user-selected export
fallback needs its own format/privacy and UX decision. Browser launch, public
URL import and
fixtures do not satisfy account-history acceptance. Keep #102 and account-backed
work open until completed or an evidenced capability limit is explicitly resolved.
