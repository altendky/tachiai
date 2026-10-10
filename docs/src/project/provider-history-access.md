# Provider watch-history access

This is partial research for
[issue #102](https://github.com/altendky/tachiai/issues/102), reviewed on
2026-10-09. History can help discover previously viewed channels and on-demand
items without making them favorites. No supported native account-history
retrieval path was established for ABEMA or Twitch; #102 remains open.

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

## Shared fixture and integration plan

Reuse the instance-bound `ProviderCatalog`, optional collections and storage from
[issue #96](https://github.com/altendky/tachiai/issues/96), through the common
management flow in [issue #97](https://github.com/altendky/tachiai/issues/97).
Use the common picker and identities. Distinguish unsupported, not verified,
authorization required, reconnect required and missing scope from empty success.

Public synthetic fixtures can demonstrate the consumer independently:

- Populated, empty and paginated history; bounded opaque cursors bound to query,
  instance and account/session revision.
- Catch-up, never-live video and collections; explicit child choice and exact
  identity resolution without latest-episode substitution.
- Expired/unavailable items, transient failures and access loss, distinct from
  empty success or a fresh guest account.
- Explicit Add and instance-local deduplication; browsing alone adds nothing.
- Logout/account switching during requests: reject stale results/cursors, clear
  account-derived cache and retain configured items.

These are remaining fixtures, not delivered tests or real-provider acceptance.
Integration must verify the intended account, pagination/ordering, normalized
identities and access loss. Local removal never changes upstream history;
upstream deletion never removes configured items. History mutation and local
viewing recording remain outside #102.

## Privacy, ownership and remaining dependencies

History is private account data. Adapter/controller work must own the selected
provider instance, its verified account and route. No silent System-route,
different-account, fresh-guest, Following, broadcaster-archive or local-history
fallback is acceptable. Missing or revoked access remains explicit.

Keep transient browsing/cache bounded and account-scoped; clear it on logout or
account change. Logs, diagnostics and recovery checkpoints must exclude history
lists, viewed times, progress, tokens, private URLs and raw responses. Persist
only required public resource/display metadata for items explicitly added by the
user, under the existing protected configured-store boundary. A broad provider
export is not a browsing cache and must not be copied into that store.

The ABEMA account-backed portion requires
[issue #103](https://github.com/altendky/tachiai/issues/103)'s working
instance-owned connection **and a verified supported history-retrieval path**.
Connection success alone does not establish collection access. Twitch has its
own access prerequisite; it does not depend on ABEMA account connection. Research
and generic fixture/UI work remain independent, and history must not block the
initial public catalog/Following/My List issues.

Before retrieval, verify access, identities, metadata semantics, ownership and
retention. For Twitch, decide whether a separately reviewed user-selected export
import can safely identify useful items. Browser launch, public URL import and
fixtures do not satisfy account-history acceptance. Keep #102 and account-backed
work open until completed or an evidenced capability limit is explicitly resolved.
