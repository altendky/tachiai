# ABEMA catalog and account access

This is the bounded documentation research for
[issue #100](https://github.com/altendky/tachiai/issues/100), reviewed on
2026-10-09. Published ABEMA features and public content identities provide a
selection model, but do not establish a supported external catalog or account
integration. The remaining account-access gates below keep #100 open.

## Evidence boundary

Research used official ABEMA help and public content pages. It did not use an
ABEMA account, credentials, private API probes, a device, changed network routes,
or playback/DRM experiments. The research browser's region, engine and account
state were not established. An attempt to open the public sumo live page returned
a regional restriction; other pages exposed published program metadata. These
observations establish public identities and documented service features, not
current regional availability, playback entitlement or Android behavior.

The existing cached native prototype creates fresh guest state and prepares
fixed sample resources. It is not an existing user's account connection or a
general catalog integration. Its reviewed guest/metadata/playback requests do
not grant broader catalog, authentication or licensing scope. See the
[security boundaries](security-and-privacy.md) and
[media-origin approvals](media-origin-approvals.md).

## Features and access capabilities

| Feature | Official ABEMA behavior | Tachiai implementation boundary |
| --- | --- | --- |
| Public discovery | Search, genres and timetable on provider pages | Provider-controlled browsing is a candidate handoff. A supported external paged catalog/search API was not verified. |
| Future broadcasts | Timetable describes one week ahead and one week back | Save an already published slot before it starts; a future announcement does not supply an unpublished slot ID. |
| My List | Programs, episodes and series; new-episode and broadcast notifications | Native collection retrieval is not verified. Explicit local import is separate from account-list synchronization. |
| Expired items | My List automatically removes expired content | Tachiai retains its configured identities until local removal, independently of provider availability or list membership. |
| Watch history | Catch-up programs and played videos | Account-backed access is follow-up #102. Coverage of all live-channel viewing is not established. |
| Channel preferences | TV preview permits custom channel/competition ordering | Ordering is not proof of a favorites collection, account synchronization or external retrieval. |
| Account identity | Automatically issued ID owns plans, profile, My List and history | Fresh guest state, an existing account and paid membership are different concepts. |
| Account sharing | Provider-controlled email verification or ID/one-time-password flow | A documented provider page flow does not establish a Tachiai-owned native grant, session or readable account collection. |

Sources: [timetable](https://help.abema.tv/hc/ja/articles/360022626672),
[My List](https://help.abema.tv/hc/ja/articles/4540947034265),
[future-program and series notifications](https://help.abema.tv/hc/ja/articles/360015492792),
[My List expiry](https://help.abema.tv/hc/ja/articles/4434908040217),
[watch history](https://help.abema.tv/hc/ja/articles/360020600092),
[TV preview ordering](https://help.abema.tv/hc/ja/articles/50290433400985),
[account identity](https://help.abema.tv/hc/ja/articles/360025402691), and
[account sharing](https://help.abema.tv/hc/ja/articles/28570588900377).

Official documentation confirms that existing account data can be shared with
ABEMA's browser/app surfaces. It does not document a third-party token exchange
or collection API in the material reviewed. No supported external interface was
found; this is a bounded negative finding, not proof that partner integrations
or other supported interfaces do not exist. Native access should remain
explicitly not verified rather than appear as an empty account list.

## Public identities and saved intent

ABEMA's sumo offering is several resource kinds, not one item per day or basho.

| Kind | Published example | Meaning of a saved selection |
| --- | --- | --- |
| Channel | `sumo`, used by the January 2025 and July 2026 slots below | That channel's broadcast when available; no promise of uninterrupted availability between tournaments. |
| Scheduled slot | July 19 day eight lower divisions, `A63wMmchm3x8Uo`, 08:00-15:40 | Exact day, division and time segment. |
| Scheduled slot | July 19 day eight makuuchi, `A63wR6irBEskZM`, 15:40-20:00 | A different exact broadcast on the same channel/day. |
| Title/series collection | `394-72`, currently named ABEMA大相撲2026 | A collection spanning tournament and video categories; explicit child selection is required. |
| Episode/video | `394-72_s10_p8510`, July day eleven makuuchi highlights | One exact on-demand video with its own availability. |

Official examples: [January 2025 lower-division slot](https://abema.tv/channels/sumo/slots/EEmFZd5BP5YY2K),
[July 2026 lower divisions](https://abema.tv/channels/sumo/slots/A63wMmchm3x8Uo),
[July 2026 makuuchi](https://abema.tv/channels/sumo/slots/A63wR6irBEskZM),
[2026 title](https://abema.tv/video/title/394-72), and
[July highlights episode](https://abema.tv/video/episode/394-72_s10_p8510).

The annual title page groups live/catch-up, highlights, makuuchi footage,
lower-division footage and special videos. Its current label does not prove
cross-year identity continuity. The expired July slot pages currently display
My List ineligibility; the cause was not verified. Do not assume every sumo
slot supports My List merely because the feature supports scheduled programs
generally.

Sumo-derived programming can also appear on another channel, as the
[March 2025 SPORTS rebroadcast](https://abema.tv/channels/world-sports/slots/FBvtWZ9wCseNN3)
illustrates. Content subject, channel and exact broadcast identity are separate.
No verified slot-to-episode mapping or recurring-program resolver follows from
these examples.

The catalog must include on-demand shows, series and episodes that were never
live, in addition to live broadcasts and catch-up videos. A saved series remains
a collection: browsing its children and selecting an episode is different from
silently choosing a latest episode. Known published future slots can be retained
before first broadcast. For an unpublished occurrence, retain only a known
channel or supported collection identity with explicit behavior; do not invent
an ID or substitute another day, division, program or fixed sample.

## Implementation recommendation

Public resource normalization and local import can proceed without collecting
account state. An ABEMA adapter can recognize reviewed public channel, slot,
title and episode links, reject account and signed-media URLs, and retain the
normalized public identity in Tachiai's own per-instance list. Unknown
availability and unsupported playback remain explicit. Provider-controlled
browsing and copying a public link offer a candidate discovery handoff; Android
sharing from provider applications and end-to-end provider behavior still need
validation.

The bounded [local public-link import](configured-catalog.md#abema-public-link-import)
from [issue #108](https://github.com/altendky/tachiai/issues/108) implements manual
paste/preview/Add for those public identity shapes. It does not inspect account
pages or request provider metadata. The separate
[Android public-link share handoff](configured-catalog.md#android-public-link-sharing)
accepts a bare link with explicit instance selection and a one-time preview;
compatibility with ABEMA's own sharing UI remains unobserved. Unknown
availability and the exact-sample-only playback boundary remain explicit.

That handoff does not implement native All/search or account-list retrieval.
Selecting one public link from a provider account page is manual import, not
proof that Tachiai can retrieve My List or history. No account-page scraping,
script injection, native bridge, cookie transfer or private authentication
workaround is proposed.

Map public identities into the shared catalog contract from
[issue #96](https://github.com/altendky/tachiai/issues/96). Keep ABEMA's slot,
series and episode semantics inside its adapter. Report public browsing,
structured search, exact lookup, collection-child navigation, My List, history
and playback as independently verified capabilities. Public identity is separate
from upcoming/live/offline/expired/unavailable status and account/region access.
Preserve configured choices when account access, metadata or upstream favorites
disappear.

[Issue #101](https://github.com/altendky/tachiai/issues/101) must carry the actual
selected resource through preparation and playback. The current cached sample
path takes a live/replay distinction and fixes its identities; catalog labels
alone cannot implement resource selection. Unsupported imported resources must
not resolve to News or the fixed replay.

[Issue #103](https://github.com/altendky/tachiai/issues/103) can develop common
connection-state/controller fixtures and an isolated provider-browser design.
Real account connection requires verification of Android behavior, instance
ownership, selected route, session persistence and a non-invasive connection
check. Opening ABEMA externally does not demonstrate that a Tachiai instance is
connected. Without that verification, report account access as not verified.

Provider credentials and codes stay on ABEMA-controlled pages. Distinct browser
storage/account isolation and process-wide WebView routing must be established
before promising multiple independently connected ABEMA instances. Account
connection must not repurpose fresh-guest native state or imply expanded native
authentication, entitlement or DRM handling.

## Remaining gates and issue status

The research establishes public identity distinctions, retention semantics,
documented account features and a bounded import direction. It does not settle
the implementation-ready account-access acceptance of #100. Keep #100 open for:

- A supported structured catalog and existing-account My List access design,
  or an explicit resolution of those capability limits. A missing API search
  result is not sufficient proof of permanent lack of support.
- Between-basho channel availability, annual title rollover, exact
  slot-to-catch-up mapping and any recurring resolver.
- Whether channel ordering is account-owned/synchronized and retrievable.
- Verified Android account connection, session/account isolation, route
  ownership, persistence, switching, expiry/revocation and logout behavior.
- Required discovery/account origins and endpoints reviewed separately from
  current cached playback network approvals.

Account-backed My List in #101 requires both #103's working instance-owned
connection and verified collection access. A connection alone, public import
or fixture success does not satisfy that acceptance. Account-backed history in
[issue #102](https://github.com/altendky/tachiai/issues/102) additionally requires
its own supported retrieval findings. Public import and fixture/UI work remain
independent of these account-backed gates; outstanding work must remain tracked.
