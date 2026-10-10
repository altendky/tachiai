# Configured sources and provider catalogs

Tachiai's configured-source foundation separates provider discovery from the
items a user chooses to retain. It is an Android prototype foundation, not a
claim that either provider exposes a supported external catalog or account API.

## Ownership and identity

Each configured item has a Tachiai UUID and an owning provider-instance UUID.
The item's public provider resource has an adapter-owned kind and identifier,
plus an explicit selection intent: channel, exact broadcast, on-demand video,
or collection. Display metadata and availability are separate from identity.
Two instances can configure the same resource independently. Within an instance,
adding the same resource and intent selects the existing item instead of creating
a duplicate. Different intents remain distinct.

An ongoing channel is not a particular broadcast. A series or annual sumo title
is a collection, not a promise to play an arbitrary latest episode. On-demand
items can include content that was never live. Unknown, upcoming, offline,
expired and unavailable resources remain configured until explicitly removed.
Refreshing status must preserve their identities and bindings.

Provider account collections are discovery inputs. Add creates a local item;
local removal does not change Following, My List or history upstream. Losing
account access or an upstream favorite does not remove a local choice.

## Polymorphic catalog boundary

`ProviderCatalog` is bound to a provider instance. Its implementation owns the
selected-route transport and account lifecycle. The shared surface exposes
capabilities, catalog pages, exact lookup, status refresh and playback resolution.
Consumers close their instance-bound catalog when its work ends; implementations
release or cancel owned resources without deleting configured selections.
Following, My List and future History collections carry provider-supplied labels
and independent access states. Unsupported, not verified, authorization required,
reconnection required and insufficient scope are distinct from an empty list.

The registry validates provider and instance ownership. Consumers receive
normalized public resources and bounded metadata, not provider response objects,
tokens or signed media URLs. Pagination cursors are transient adapter-owned
values; adapters must bind them to query, parent resource, account and session
revision. Cursors are not configured identities or diagnostic fields.
Resource-scoped browsing lets a saved series or channel expose explicit child
episode/video choices where the adapter advertises that capability.

Catalog resolution retains the exact selected public resource. The existing
browser `ProviderAdapter`, native `PrototypeFeedSession` and media-control
`NativePairMember` remain separate interfaces. A session factory prepares a
resolved resource behind those boundaries. Collection navigation and account
access do not automatically confer playback entitlement or native compatibility.

The legacy bridge resolves only an exact supported public resource back to its
original sample. Removing and re-adding that resource may change its local UUID
without changing playback support. A different identity, provider, kind or intent
cannot become the fixed News or replay sample through that bridge. Consumers
validate the item's owning instance before preparing playback. Provider-specific
dynamic resolution is subsequent integration work.

## Persistence and migration

Configured lists use separate per-instance encrypted no-backup records under the
existing Android private-store infrastructure. Each record is versioned and
bounded to 32 items and 8192 encoded bytes. The global secret-store limit, existing
key aliases and other provider/route/grant records are unchanged. JVM and file
locks cover read-modify-write; writes use the existing encrypted AtomicFile path.
An invalid or unknown-version record fails rather than regenerating samples.

An absent list is a read-only legacy projection. It seeds that instance with
the existing sample sources for its service, preserving custom source labels
and copying legacy quality defaults. Deterministic item UUIDs derive from
instance UUID and legacy source name. The first explicit list mutation commits
the validated projection. An explicitly empty saved list remains empty on
restart and is never reseeded.

Routes and authorization remain owned by their existing stores. Legacy source
metadata and quality records are not rewritten by projection. The existing
explicit reset/review policy for obsolete four-source settings is preserved;
the catalog does not guess routes from those records.

Configured feed references encode local item and instance UUIDs with a version
marker. Historical enum/instance references map deterministically to these IDs.
Null and malformed slots remain unassigned; stale references cannot select
another instance or sample. The configured recovery reader accepts historical
and new formats on the same bounded private checkpoint path, and writes only
local identities. It consumes only valid records; malformed data remains visible
as a restoration failure.

## Delivery boundaries

The foundation in [issue #96](https://github.com/altendky/tachiai/issues/96)
provides models, contract, store, codecs, migration and the exact legacy bridge.
[Issue #97](https://github.com/altendky/tachiai/issues/97) activates configured
lists and local item/instance references in the common management/selection flow.
Providers → Manage streams opens the selected instance's saved list and a shared
discovery surface. Add, remove and reorder affect only that local list. Explicitly
empty lists stay empty, and removed selections stay unresolved until the user
chooses another item. The picker reloads on return; Watch freezes exact item and
instance snapshots before preparation. Saved quality defaults are item-owned;
duplicate feeds share those defaults and retain independent session overrides.
Restart checkpoints use the same local references and accept old enum choices.

The shipped discovery adapters retain **Prototype samples**. They browse and
search the existing public sample resources, with unknown availability. Following
and My List are explicitly not verified. Twitch direct lookup and both providers'
child navigation remain unsupported. Shared controller and UI fixtures exercise paging,
account access states and explicit collection children. These fixtures do not
establish either provider's real API support. Arbitrary resources and collections
cannot silently resolve to the News or replay sample, and known unavailable items
remain saved while Watch explains why playback is blocked.

### ABEMA public-link import

[Issue #108](https://github.com/altendky/tachiai/issues/108) adds local lookup of
published HTTPS `abema.tv` links in ABEMA's Manage streams screen. Previewing a
link adds nothing; explicit Add saves its normalized public resource under the
selected provider instance. Channel, exact channel/slot, episode and title links
remain channel, broadcast, video and collection intents respectively. Slot
identity includes its channel; a title never selects a latest episode.

Lookup makes no provider request. Generated labels have unknown availability and
no inferred schedule, existence or entitlement. Queries, fragments, credentials,
encoded paths, unrecognized hosts/paths and oversized input are rejected; only
bounded public identifiers enter the configured store. Public-link drafts remain
in memory rather than saved Activity state.

Imported items survive restart and unavailable My List access, using the existing
local ordering, deduplication and encrypted storage. Exact current sample
resources retain their existing playback support. Other resources remain saved
but fail as unsupported before route/session preparation; collections need
explicit child discovery that is not yet connected. This does not implement
native All/search, account lists, Android Share handling or dynamic ABEMA playback.

[Twitch integration #99](https://github.com/altendky/tachiai/issues/99) and
[ABEMA integration #101](https://github.com/altendky/tachiai/issues/101) implement
real discovery and selected-resource preparation behind the shared contract.
Their access investigations establish supported capability boundaries.
[History #102](https://github.com/altendky/tachiai/issues/102) is an optional
follow-up. ABEMA account-backed collection access also requires
[account connection #103](https://github.com/altendky/tachiai/issues/103) and a
verified collection-access path; guest discovery and fixtures remain independent.

## Local UI verification — 2026-10-09

The shared flow passed 32 provider-free instrumentation tests on a separate
disposable Android 16/API 36 x86-64 emulator. Tests cover both fixture providers,
collections, paging, local ordering, access/error states, restoration, unavailable
items, removed choices and keyboard/D-pad activation. Initial harness failures
were corrected by scrolling the lazy list before querying off-screen nodes and
explicitly entering keyboard input mode; the behavioral assertions were retained.

On the persistent `tachiai-dev` API 36 x86-64 emulator with software graphics,
touch opened Providers → Manage streams and keyboard/D-pad input moved focus
between its controls. Installation used the shared debug signer and an update;
saved route labels remained visible, and no configured list, route or provider
grant was reset or edited. The picker retained its compact header and inline
routes. This checks local UI behavior only. No provider account access, catalog
network request or playback/DRM experiment was performed, and no region or
physical-device behavior is established by these observations.
