# ABEMA public Search and exact Seasons

The debug catalog under [#189](https://github.com/altendky/tachiai/issues/189)
implements provider-wide public Search behind `ProviderCatalog`, plus explicit
episodes under an exact Season. It uses the provider instance's saved route and
a fresh guest. My List, History and Schedule retain independent operations and
access gates. Two bounded Search observations on `tachiai-dev` returned valid
public metadata. Exact Season acceptance remains **unverified** because neither
result supplied a Season candidate. Static evidence and synthetic fixtures are
separate from an observed provider response.

Search replaces the channel-only filter in this composed catalog; Channels
browsing remains available. Search from a selected account collection starts a
public provider-wide query. A Series or Season remains a COLLECTION with its own
explicit Episodes action. Browsing children and returning Back preserves the
Search context and continuation. Add remains explicit. Configured identities,
order, quality and routes remain Tachiai-owned, including when discovery omits
an item or access is lost.

## Static first-party evidence

The retained runtime mapping and exact cookie-free public assets were checked
during research. No additional assets or provider API were fetched for this
implementation. These byte hashes identify retained evidence, not bundled
provider code or a permanent contract.

| Asset | Bytes | SHA-256 |
| --- | ---: | --- |
| [9298](https://abema.tv/assets/compat/4ad4fadda744ab29.9298.js) | 1803121 | `34207e8d8ff54721927e7dc93edcdfa20c2af99e50c66c8ad5defe5e8e0f03e5` |
| [Runtime app](https://abema.tv/assets/compat/0325e1e8d67ded19.app.js) | 6699 | `37423c01fc96698e8cae34675ecb64b63e598a6bb0c466da15b5af989ac276ce` |
| [6755](https://abema.tv/assets/compat/0ed1733b83642540.6755.bundle.js) | 22678 | `efa3e2b1a15fc8f6fbddc7f38af2c151134cd95a66ff0af8a1d70c19b4cf077a` |
| [7944](https://abema.tv/assets/compat/8b42930c33596be4.7944.bundle.js) | 38539 | `931b1e3a89d6cd95569bcf1e78d25eb47e3cc90c50d05aaf7fc82a36a672542f` |
| [5492](https://abema.tv/assets/compat/423c28bfb552a09a.5492.bundle.js) | 16173 | `7b15ac51c355a606637cdfff1add4903e2b4ed0089c741540647bd72edb1f454` |
| [3795](https://abema.tv/assets/compat/35a6b0a35f792be3.3795.bundle.js) | 24615 | `21d6351a212c51f452521fc18c87b499c4e263c761ab64f35091553d6dd61398` |
| [6245](https://abema.tv/assets/compat/3eaa977a7f089316.6245.bundle.js) | 106549 | `9f2b52d6cd326ab34ad8dd6d372ea9218f8dac6bd50db9964a661696f73f8c89` |

6755/module85507 and 7944/module24971 construct bearer GETs to
`/v1/search/modules` and `/v1/search/modules/{id}`. Summary uses `query`,
`device=WEB_MOBILE`, `itemLimit=8`; detail uses the same query/device and
`limit=20`, raw `offset`. Closed detail IDs are `ppv`, `nowonair`, `package`,
`episode`, `timeshift`, `scheduled`. Optional restrictions, subscriptions,
sorting, cursors and alternate endpoints are not inferred.

5492/module42862 describes `modules`/`module`, `id`, `displayName`,
`moduleType`, `totalItemCount`, and `items.content.results` with
`contentId`, `contentType`, `contentData`. 6755/module82860 joins the selected
union arm. Projection preserves Episode ID; Slot `channelId/contentId`;
Series ID; Season `videoSeason.seriesId/contentId`; and Live Event ID. Numeric
and named content/module enum forms are explicitly accepted. The serializers
omit empty repeated fields and zero counts. Absence is normalized only inside
validated Content/Spot containers; a missing count becomes zero and still must
cover actual rows. A canonical empty summary object becomes an empty module
list. Explicit null/wrong types, other roots lacking modules and a missing
identity-bearing detail module refuse. Counts must be safe nonnegative JSON integers,
identity/title strings, and exactly one matching content arm populated.
Navigation Spots are excluded. Unsupported arms, unknown content modules,
conflicting identities and malformed responses refuse instead of becoming empty.

3795/module88594 supplies optional `seasonId` to Series-program GET;
6245/module95597 pages the selected Season at limit20/order seq or -seq.
9298/module91060 supplies `series.id`, `season.id` and `season.seriesId`.
Exact Season children use existing order=seq with the filter and require all
three returned identities. Unfiltered Series requests and seven-field start IPC
remain valid; only filtered requests carry the additional Season field/proof.

## Paging, bounds and refusals

Initial Search makes one summary GET in provider module/item order. Modules
whose total exceeds their prefix remain available through More. Each More makes
one detail GET, starting at raw offset0 and advancing by20 regardless of dedup.
Remaining modules follow summary order. Exact resources are deduplicated across
pages; conflicting titles within one response refuse. Later title changes do
not overwrite retained entries. Totals may change on each detail response.
Offset paging can overlap or omit changing items; no server snapshot, complete
global ordering or invented cursor is claimed.

Local bounds are query160 characters, six content descriptors, 16 raw modules,
eight summary rows/module, 20 detail rows, total1000000, offsets0..9980 in steps
of20, 64 detail calls and 600 retained resources. Raw JSON is at most512KiB with
depth24, 50000 nodes and 1000 elements/array. Normalized projection/IPC is at
most64KiB. Duplicate JSON keys, malformed Unicode/UTF-8 and invalid scalar/parent
joins refuse. The shared manager displays at most500 results and marks real
truncation. Reaching an adapter bound with more results remaining produces an
explicit failure on More, never false exhaustion.

Opaque one-use RAM continuations bind query, module, raw offset, instance-local
catalog, revision and captured owner admission. Queries and provider bodies
are not persisted. The closed FETCH_SEARCH purpose admits only fresh-guest POST,
one exact Search GET and bounded preflight. The same-UID, non-exported metadata
service carries primitive fields, never arbitrary URLs or account capabilities.
Foreground, original45-second deadline, final owner checks and confirmed runtime
cleanup precede publication. Uncertain cleanup retains actual handles and blocks
reuse through existing guest admission. No retry, renewal, cookie transfer,
login-page script, provider mutation or usage tracking is added.

Rows have UNKNOWN availability and no inferred schedule or entitlement.
Future/offline items can be configured explicitly. Metadata is not playback
evidence; collections are never silently replaced by a parent/first episode,
and Live Event results establish no playback capability.

## Verification and pending observations

Provider-free Node fixtures cover all five arms, Spots, summary/detail URLs,
strict body/JSON limits, refusals, cancellation and exact Season parents. JVM
fixtures cover continuation replay, changing totals, cleanup poisoning, held
owner reads, IPC isolation, configured identity/quality, shared500 truncation and
Search/children/Back. Network-blocked owned WebView fixtures execute
summary/detail/Season projection and primitive parcel checks without provider
requests or saved-store reads. Full Android verification passed2390 JVM cases
across288 reports with no failures, errors or skips, lint, release isolation,
instrumentation compilation, both APK assemblies and the shared signing check.
Nineteen network-blocked guest/IPC device fixtures executed and passed with zero
skips; the ordinary instrumentation manifest was restored.

The first actual Search refused; a closed post-cleanup diagnostic located the
failure at response parsing. The absence-only correction above then passed:
fixed public `相撲` Search returned31 entries across episodes, series and slots
from one summary and one detail, with more remaining. A separately opted-in
`呪術廻戦` Search returned27 entries of those same kinds, also with more
remaining. Neither returned a Season or Live Event. Both preserved protected
records and captured ownership and released the local facade. These observations
establish bounded metadata compatibility, not full provider coverage or playback.
Device: persistent tachiai-dev, Android16/API36, x86-64, WebView133.0.6943.137,
saved connection, exit country unestablished, fresh guest, no account validation.

`AbemaSearchMetadataDeviceTest` skips by default before stores, services or UI.
On verified `tachiai-dev`, `tachiaiReadOnlyAbemaSearch=true` uses fixed public
query `相撲`, one summary and at most one needed detail through the saved route.
It avoids capabilities/account validation, Add, configuration changes and
playback. Protected-record hashes stay local and must remain unchanged. Final
owner/cleanup checks precede logging any public Season candidate. Only safe
outcome/count/kind categories and a public candidate are emitted; More remaining
is reported honestly.

`tachiaiReadOnlyAbemaSeasonCandidateSearch=true` separately opts into the fixed
second public query above with the same request and safety limits. Its success
without a returned candidate is Search evidence only, never Season evidence.

Separate `tachiaiReadOnlyAbemaSeason=true` requires explicit
`tachiaiAbemaSeriesId` and `tachiaiAbemaSeasonId` from an established public
candidate. It observes one filtered episode page with the same protections,
without alternate parent or automatic Search fallback. Restore normal
instrumentation/launcher afterward. Record actual outcomes, observed kinds,
scalar compatibility and refusals after observation. Unobserved kinds remain
unverified; broader #100/#101 coverage and account acceptance remain separate.
