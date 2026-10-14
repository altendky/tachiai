# ABEMA catalog and account access

The [guest-only preparation experiment](abema-guest-bootstrap.md) isolates the
fresh-session prerequisite for account linking. Its guest evidence is separate
from the later [native email account acceptance](#native-email-account-acceptance--2026-10-12),
[My List observation](#intended-account-my-list-acceptance--2026-10-12) and
[History observation](provider-history-access.md#intended-account-history-acceptance--2026-10-12).

This document combines the bounded 2026-10-09 research for
[issue #100](https://github.com/altendky/tachiai/issues/100) with dated
implementation and device evidence below. Public discovery, one saved email
account and first-page account collections have bounded native acceptance;
they do not establish a supported external catalog or account API. The stated
initial criteria for #100 and #101 were audited and closed on 2026-10-13;
broader coverage, identity and lifecycle behavior remain unverified.

## Current authorization — 2026-10-10

The user now authorizes general ABEMA catalog, existing-account/session,
collection and selected-resource playback integration, including undocumented
endpoints wherever a needed operation has no documented API. The old
supported-only and fixed-content permission limits are superseded by the
[current integration scope](security-and-privacy.md#general-provider-integration-approval--2026-10-10)
and the updated #100/#101/#103 bodies. Lack of official API documentation is an
evidence classification, not another permission gate. The dated observations
below establish bounded acceptance; further lifecycle, route and entitlement
checks remain outstanding.

## Evidence boundary

The original research used official ABEMA help and public content pages. It did
not use an ABEMA account, credentials, private API probes, a device, changed network routes,
or playback/DRM experiments. The research browser's region, engine and account
state were not established. An attempt to open the public sumo live page returned
a regional restriction; other pages exposed published program metadata. These
observations establish public identities and documented service features, not
current regional availability, playback entitlement or Android behavior.

The cached native prototype creates fresh guest state separately from the
instance-owned email account connection. In addition to retained historical
comparisons, it implements [exact configured episodes](#exact-configured-episode-playback)
and [linear channels](#exact-configured-linear-channel-playback), with a separate
[exact-slot bridge](#exact-broadcast-catch-up-adapter).
The [stronger episode observation](#exact-slot-playback-acceptance-harness--2026-10-12)
verified rendered video, two advancing READY samples and confirmed cleanup for
one free episode; eligible-slot, account-required and broader playback remain
unverified. Guest playback does not establish an existing account or account
entitlement. The current approval above provides integration authority; reviewed
requests alone did not grant broader scope. See the
[security boundaries](security-and-privacy.md) and
[media-origin approvals](media-origin-approvals.md).

## Features and access capabilities

| Feature | Official ABEMA behavior | Tachiai implementation boundary |
| --- | --- | --- |
| Public discovery | Search, genres and timetable on provider pages | Channels browsing and exact lookup use the bounded public channel endpoint below. A separate public Schedule snapshot projects exact broadcasts; one saved-route guest response passed the bounded device observation below. [Provider-wide Search and exact Season children](abema-search.md) have bounded real metadata acceptance: two Search observations and one exact Season page passed. Genres and complete provider coverage are not implemented. |
| Future broadcasts | Timetable describes one week ahead and one week back | Save an already published slot before it starts; a future announcement does not supply an unpublished slot ID. |
| My List | Programs, episodes and series; new-episode and broadcast notifications | The read-only native adapter has [bounded intended-account acceptance](#intended-account-my-list-acceptance--2026-10-12): one entry, no continuation, saved account/route/configured items preserved. Complete coverage, pagination and all resource kinds remain unverified; local import is separate from account-list synchronization. |
| Expired items | My List automatically removes expired content | Tachiai retains its configured identities until local removal, independently of provider availability or list membership. |
| Watch history | Catch-up programs and played videos | The read-only native adapter has [bounded intended-account acceptance](provider-history-access.md#intended-account-history-acceptance--2026-10-12): two entries and an available continuation that was not followed. Complete history coverage, pagination and all live-channel viewing remain unverified under #102. |
| Channel preferences | TV preview permits custom channel/competition ordering | Ordering is not proof of a favorites collection, account synchronization or external retrieval. |
| Account identity | Automatically issued ID owns plans, profile, My List and history | Fresh guest state, an existing account and paid membership are different concepts. |
| Account sharing | Provider-controlled email verification or ID/one-time-password flow | [Provider-controlled email connection](#native-email-account-acceptance--2026-10-12) established protected native grant adoption, fresh validation and process-restart persistence for one instance on tachiai-dev through Windscribe. Other auth methods, multi-instance isolation, switching, expiry/revocation and logout remain unverified. |

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
or other supported interfaces do not exist. The later native email, My List and
History observations establish bounded integration through verified first-party
behavior, without establishing official external API support. Capabilities outside
those observations must retain explicit unverified or access-required states
rather than appear as an empty account list.

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

### Public Channels adapter

[Issue #146](https://github.com/altendky/tachiai/issues/146) replaces fixed-sample
discovery in Manage streams with a real ABEMA Channels adapter behind the shared
catalog interface. It sends one anonymous GET to
[the public channel endpoint](https://api.abema.io/v1/channels) through that
instance's explicit saved route. The original adapter filters channel names/IDs locally; pages
of 20 use a bounded in-memory snapshot and opaque, single-use continuations
owned by the instance, captured route and query. A new query, lookup, refresh,
access loss or close discards the previous continuation. Channels success does
not establish My List or History access; separate outer account bindings must
prove each collection endpoint independently.

The transport accepts at most 512 KiB and 500 unique channels, requires bounded
valid JSON/UTF-8 and retains only normalized ID/name. It discards playback URLs,
opaque metadata and cookies. Channel membership and fields such as viewingOpened
do not establish live state, regional availability or entitlement; every entry
has UNKNOWN availability. The catalog adapter itself does not prepare playback.
The separate [configured linear-channel path](#exact-configured-linear-channel-playback)
carries exact selected identities; its implementation does not make a Channels
snapshot proof of entitlement or current live playback. Historical comparisons
remain separate from selected-resource preparation.

After a successful valid snapshot, a recognized public channel URL absent from
that snapshot can still be explicitly added with UNKNOWN metadata. A bare ID
absent from the snapshot returns NOT_FOUND. A missing refresh preserves the
saved identity/title; a network, parser, route or cleanup failure remains a
failure. Slot, episode and title links retain request-free local import. When
metadata is unavailable before dispatch, public links can still be imported
locally through the current instance; failed requests are never converted into
local success or sent over System as a fallback. Configured entries remain
Tachiai-owned and are neither auto-added nor removed by upstream changes.

The closed ABEMA catalog route purpose admits only api.abema.io, with the
provider transport additionally enforcing the exact endpoint/method and no
query, redirects, credentials or TLS override. Both System and imported routes
use an owned client without an origin authenticator or cookie jar; imported
route credentials remain limited to its local CONNECT listener. The transport
also refuses an installed global Java cookie handler. Ownership is checked
before dispatch and publication; cancellation invalidates pending work, and
uncertain cleanup prevents further metadata admission. Concurrent worker cleanup
joins the single owned disconnect for at most five seconds, preserving an
interrupted worker's flag. This cleanup grace does not extend the 30-second
request admission budget or permit cancelled metadata to publish. Disconnect
failure, timeout and reentrant unconfirmed cleanup remain failures.

Fresh anonymous host research on 2026-10-10 received 58 channels in 52,706 bytes.
The host region was not established, no account was used and sumo was absent;
this is a cached metadata snapshot, not proof of deletion or between-basho
continuity. Device/saved-route acceptance is recorded separately from this host
observation and synthetic fixtures. Broader timetable/video/account/selected
playback work remains in #100/#101/#103; this slice does not close those issues.

On 2026-10-10, a bounded read-only check on the verified persistent tachiai-dev
AVD (Android 16/API 36, Google APIs x86-64) retrieved 58 normalized channels
through the default ABEMA instance's existing saved connection. All entries
remained UNKNOWN and sumo was absent from that snapshot. Region was unverified;
no account credentials or browser engine were used, and playback was not tested.
The provider registry and source configuration were equal before/after the
check. Android JSON validation and an isolated shared-screen fixture also passed
(three selected tests total). No saved accounts, routes or configured items were
reset. This establishes this device/route's public metadata retrieval only.

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
availability remains UNKNOWN; configured episodes have a separate fresh runtime
eligibility check, as do exact configured linear channels. Other unsupported
resource types refuse playback.

That handoff alone does not implement native Channels/search or account-list retrieval.
Selecting one public link from a provider account page is manual import, not
proof that Tachiai can retrieve My List or history. The local handoff does not
use account-page scraping, script injection, a native bridge or cookie transfer.
Actual account integration is separate work under the current approval.

Map public identities into the shared catalog contract from
[issue #96](https://github.com/altendky/tachiai/issues/96). Keep ABEMA's slot,
series and episode semantics inside its adapter. Report public browsing,
structured search, exact lookup, collection-child navigation, My List, history
and playback as independently verified capabilities. Public identity is separate
from upcoming/live/offline/expired/unavailable status and account/region access.
Preserve configured choices when account access, metadata or upstream favorites
disappear.

[Issue #101](https://github.com/altendky/tachiai/issues/101) must carry the actual
selected resource through preparation and playback. Its focused episode child
[#164](https://github.com/altendky/tachiai/issues/164) carries an exact resource
through the cached path described below. Catalog labels alone cannot implement
resource selection. Unsupported imported resources must not resolve to News or
the fixed replay.

[Issue #103](https://github.com/altendky/tachiai/issues/103) has delivered an
instance-owned [email connection](#native-email-account-acceptance--2026-10-12)
with native identity/email validation and process-restart persistence on the
emulator through the saved Windscribe route. Separate read-only My List and
History observations establish more than login success alone. Opening ABEMA
externally does not demonstrate that a Tachiai instance is connected. Other
routes, account switching, expiry/revocation, logout and multiple independent
instances still need their own verification; authorized undocumented access
does not remove those technical gates.

Provider credentials and codes stay on ABEMA-controlled pages. Distinct browser
storage/account isolation and process-wide WebView routing must be established
before promising multiple independently connected ABEMA instances. Account
connection must not repurpose fresh-guest native state or imply verified
entitlement, collection access or playback merely from login success.

### Public Schedule snapshot

The public catalog under [#100](https://github.com/altendky/tachiai/issues/100)
and [#101](https://github.com/altendky/tachiai/issues/101) offers **Schedule**
alongside **Channels**, My List and History. Schedule uses a fresh guest rather
than a saved account grant. Missing or unverified account collections do not
block this public query. [Provider-wide Search](abema-search.md) wraps Channels
with separate summary/detail requests; Schedule itself does not claim global
provider search or complete All coverage.

The retained coherent
[9298 entry](https://abema.tv/assets/compat/4ad4fadda744ab29.9298.js),
[5306 timetable consumer](https://abema.tv/assets/compat/859be5dce9804d5d.5306.bundle.js)
and [3194 slot model](https://abema.tv/assets/compat/4fa634e6d16c9c17.3194.bundle.js)
establish a bearer GET at the reviewed modern API's `/v1/timetable/dataSet`.
The raw root directly contains `slots`, `channels` and `availableDates`, without
a `dataSet` envelope. Slot `id`, `channelId` and `title` establish exact broadcast
identity and display text. Channel annotations use `id`/`name`; the consumer
uses `yyyyMMdd` date strings and performs date/channel filtering locally.
Optional division belongs to a provider context this slice has not established
and is omitted. These are static first-party semantics, not an actual guest
response, horizon guarantee or documented external API contract.

The implementation makes one canonical timetable GET after fresh guest setup,
with no server pagination, retry or renewal. A closed second operation reuses
the existing private list service, runtime and captured instance/route owner.
The request supplies no arbitrary URL or resource argument. Same-UID nonce,
foreground heartbeat, original deadline, worker owner checks and confirmed
WebView/proxy/backend cleanup guard publication. Provider bearer and raw
document remain private; only normalized exact broadcast rows cross IPC.

Whole-document parsing refuses duplicate JSON keys, duplicate/conflicting
channel or slot IDs, malformed dates, invalid identity/title types, invalid
UTF-8 and local bounds. Valid slot identities remain configurable when a
channel/date annotation is missing; annotations never substitute a different
channel or occurrence. Optional groups, timing, playback, regional policy,
entitlement and progress fields are not projected. Every returned entry retains
the existing `slot` / `channelId/slotId` BROADCAST resource with UNKNOWN
availability and no schedule timestamp. The existing exact-slot refresh and
explicit URL import retain their separate behavior.

The raw admission ceilings are explicitly local: 8 MiB, depth 24, 300,000 JSON
values and 20,000 items per array. They do not describe provider maxima and need
an actual-response measurement. Projection retains at most 501 distinct rows
in provider order, with a 256 KiB normalized IPC ceiling. Local pages contain
100 rows and single-use snapshot continuations; restart, another query, lookup,
refresh, access loss or close discards them. The real 501st row causes the shared
500-result display cap to report truncation, instead of presenting a bounded
prefix as exhaustive. Browsing never auto-adds or deletes configured choices.

Owned JavaScript fixtures cover canonical dispatch, whole-response refusal,
non-live retention, missing annotations, prefix/truncation, original deadlines
and cancellation. Initial verification passed all 75 owned guest JavaScript cases and
72 targeted JVM cases across Schedule, management-memory, IPC, policy/runtime
and existing series regressions; instrumentation source compilation and scoped
pre-commit checks also passed. No APK assembly or device observation is implied.
A later review adds aggregate-node refusal, network-blocked Android Schedule
and primitive IPC fixtures, plus a receipt for retained route cleanup; those
Android additions require the combined verification. A separately opt-in
`tachiaiReadOnlyAbemaSchedule` device fixture defaults to skipping before any
protected store/service/UI access. Its one saved-route query checks the captured
owner and encrypted-record hashes without account validation or persistent
writes. Up to six local pages reuse the same snapshot/GET; the closed output
records normalized count, display truncation and one validated public slot
candidate after cleanup, preferring `sumo` when present in the retained prefix.
It emits no exact raw byte size or provider date horizon.

On 2026-10-11, the combined source passed 2,375 JVM tests across 285 reports
with no failures, errors or skips, all 76 owned guest JavaScript cases, scoped
hooks/documentation, lint, release isolation, instrumentation compilation and
both APK assemblies. Both certificates matched the shared debug signer. The
verified app and ordinary test APK were installed on persistent `tachiai-dev`.
One explicit saved-route Schedule observation passed in 15.72 seconds: one
fresh-guest timetable GET yielded a normalized prefix of 501 exact broadcasts,
consumed through six local pages, with display truncation reported. Captured
ownership and encrypted records were unchanged after cleanup. No account
validation, automatic Add, configured write or playback occurred.

A separate exact-slot refresh for the public candidate returned by that
snapshot passed in 12.72 seconds. It returned the same channel/slot identity
with UNKNOWN availability and no schedule, preserving captured ownership and
encrypted records. Fifteen network-blocked Android/IPC guards from the delivered
slot source had already passed before these real requests. Afterwards, all
17 isolated network-blocked guest-runtime/IPC cases passed in 2.583 seconds,
including the new Schedule projection and primitive parcel guards. The test
manifest was restored byte-identically, the ordinary test APK rebuilt with the
shared certificate and reinstalled, and the normal launcher restored. Protected
records remained unchanged. This completes bounded metadata acceptance for the
focused slot issue, not slot playback.

### Test-only raw Schedule observation

Issue #100 has a separate opt-in observation in the guest-process test APK:
`tachiaiReadOnlyAbemaScheduleShape=true`. It decorates the one app-owned Schedule
projection after whole-response validation, using one captured wall clock. The
production asset, metadata contract and catalog entries retain their existing
behavior. One fresh guest setup and one canonical timetable GET supply this
case; it performs no account validation, exact-slot lookup, Add or playback.

The closed marker counts the full admitted response, the retained 501-row
prefix and the displayed 500-row prefix separately. It classifies four time
fields as absent, null, zero, bounded positive integer seconds or invalid, and
the raw `flags.timeshift` / `flags.timeshiftFree` fields as unavailable parent,
absent, null, true, false or invalid. The positive-number ceiling through year
9999 is a local diagnostic bound; the category does not prove provider units.
Intervals are unavailable, invalid order, ended, future or containing the
captured device clock. A separate count intersects ended intervals with an
explicit true free flag. None of these counts establishes live state,
entitlement or an exact playable source. Tokyo calendar-day offsets summarize
the distinct available dates without emitting date values or identifiers.

The marker is size bounded, rejects duplicate and unknown fields, and checks
count sums, prefix subsets, interval/flag consistency and horizon consistency.
It is stripped before the unchanged Schedule decoder. Counts are logged only
after normal snapshot decoding, the original runtime and outer case budgets,
confirmed runtime and Activity cleanup, unchanged captured ownership and
encrypted records. Pending AtomicFile backup/new files refuse this case before
owner reads. This preflight assumes an idle device and is not an external-writer
lock. No raw rows, titles, dates, URLs, token values or private identifiers are
logged. Paired Node fixtures execute the exact test-APK decorator rather than a
duplicate implementation; Android synthetic cases exercise marker stripping.

On 2026-10-13 UTC, one saved-route guest observation on persistent `tachiai-dev`
passed with zero skips in 13.335 seconds. The device was Android 16/API 36,
x86-64 with WebView 133.0.6943.137. The response admitted within the unchanged
local raw bounds contained 5,727 slots, 55 channels and 15 distinct dates:
seven past, one current and seven future Tokyo days, with offsets -7 through +7.
Region was not separately measured; the case used a fresh guest and did not
validate or consume the saved native account grant.

| Observation | All admitted rows | Retained prefix | Displayed prefix |
| --- | ---: | ---: | ---: |
| Rows | 5,727 | 501 | 500 |
| Ended intervals | 3,251 | 317 | 317 |
| Future intervals | 2,428 | 180 | 179 |
| Intervals containing captured clock | 48 | 4 | 4 |
| Explicit true free flag | 3,953 | 347 | 346 |
| Ended interval and explicit true free flag | 2,232 | 226 | 226 |

All admitted rows had positive-number start/end fields and valid interval order.
The general timeshift-end field and true timeshift flag were present on 4,558
rows and absent on 1,169; the free-end field and true free flag were present on
3,953 and absent on 1,774. No null, false, zero or invalid category occurred in
those fields. Both prefix truncation indicators were true. This establishes
that this response includes ended/free-flag candidates even in the displayed
prefix; it neither identifies one nor verifies its current free window, source,
region, rights, license or rendered playback.

All 89 owned guest JavaScript cases passed, including the shared decorator;
2,554 unchanged JVM cases across 295 reports had no failures, errors or skips.
Android lint, release isolation, instrumentation compilation, both APK
assemblies, shared signing checks, scoped hooks/documentation and independent
review passed. Four provider-free Android marker cases passed with zero skips
in 2.181 seconds. The real case confirmed owner, runtime/Activity cleanup and
encrypted-record preservation; the external comparison of all 20 protected
files also matched. The test manifest was restored byte-identically, ordinary
instrumentation rebuilt and reinstalled with the shared signer and no process
override, and the normal launcher restored. All 20 protected files still
matched afterward. The production APK remains unchanged.

Both observations used Android 16/API 36, x86-64, WebView 133.0.6943.137,
the instance's captured saved route, unknown exit country and a fresh guest
without the existing ABEMA account. They establish compatibility with these
actual responses within local parsing bounds. Exact raw byte sizes, full
provider date horizon, changing-schema compatibility and complete All coverage
remain unverified; the broader #100/#101 issues remain open.

### Existing-account email connection implementation

The debug Providers screen offers an instance-owned **ABEMA account** screen.
**Connect ABEMA by email** opens ABEMA's account page in the isolated routed
browser. A remembered login can be reused by staying on Account management and
returning to Tachiai. If sign-in is needed, the user chooses **Switch account →
Email address**, enters the email address and emailed six-character code on
provider-controlled pages, then opens Account management and returns. A confirmed
private browser handoff automatically starts native identity/email validation
on foreground return and saves only an admitted grant. **Finish email connection**
remains a recovery control if verification is interrupted. Tachiai has no
email/password/code form. Switching accounts is not a same-account reauthentication
step; ABEMA can reject that operation when the intended account is already active.
The other authentication methods are deferred to the human-led
[issue #196](https://github.com/altendky/tachiai/issues/196), without the agent label.

Normal Connect retains the dedicated browser login. **Sign in again** is an
explicit recovery action for a remembered session that prevents same-account
sign-in. It clears only the shared ABEMA approval-browser profile on this device,
including cookies, site storage, cache and service workers, using the supported
AndroidX complete-deletion API. Other native account records, routes and configured
items remain unchanged until a new connection passes native acceptance. Other
ABEMA instances share this browser profile, so their remembered browser login is
also cleared; their native grants remain separately owned.

Deletion must complete before route preparation or provider page loading.
Unsupported WebViews report that fresh sign-in is unavailable. A receipt is
retained before dispatch and independently gates cleanup and profile reuse,
including cancellation before route creation. Exceptions or uncertain deletion
completion keep the process poisoned and owned; late callbacks cannot load a
page or race a replacement session. Reused browser login and automatic return
validation implement a bounded reconnect slice of #103/#212. They do not yet
implement automatic native renewal or shared-account native playback.

On 2026-10-13 UTC this reconnect slice passed 2,566 JVM cases across 296 reports
with no failures, errors or skips, Android lint/isolation/instrumentation/APK
assembly/shared signing, scoped hooks/documentation and independent review.
Seven provider-free account-screen cases passed with zero skips in 16.552
seconds on persistent `tachiai-dev`. Installation and those tests preserved
all 20 protected files. The device used Android 16/API 36 x86-64 and the same
WebView 133.0.6943.137; no physical-device acceptance follows.

One real normal Connect then reused the remembered browser login through the
saved route. The provider page loaded and the route's region check returned 200.
No account switch, email/code entry, browser reset or manual Finish occurred.
Return to Tachiai produced MISSING → CHECKING → CONNECTED after the protected
handoff and native identity/registered-email checks. Exactly the intended ABEMA
grant record changed; the other 19 protected files and file set remained
unchanged. The normal launcher was restored and post-connection hashes still
matched. This verifies browser reuse after a missing local grant, not a longer
provider token lifetime or silent renewal of an expired native grant. The fresh
sign-in deletion action was covered by fixtures but not exercised on the real
signed-in profile.

### Shared instance account access — 2026-10-13

The shared-account implementation builds on
[issue #212](https://github.com/altendky/tachiai/issues/212) and the account lifecycle
requirements in [issue #103](https://github.com/altendky/tachiai/issues/103).
The unit of ownership is one ABEMA provider instance and its captured route.
Independent provider instances retain separate native grants; the approval browser
still has its separately remembered provider login.

Episode, series, slot, schedule and search discovery, My List, History and
configured native playback acquire the same protected native account through an
ordinary-process owner. The credential-free public channel list creates no
additional identity.
Overlapping consumers share bounded positive identity and registered-email
validation. Detached consumers cannot publish late results. The final consumer
must confirm cleanup before the owner can be reused. Uncertain cleanup remains
retained and blocks replacement. Main-thread admission uses memory and clocks;
protected-record and route checks run on workers before provider requests.

A genuinely missing local account permits a pinned guest lease for public
discovery/playback. An expired, refused, unreadable or pending-Forget account does
not become a guest. Account replacement, Forget, route changes and expiry revoke
the old lease. My List and History always require an admitted account.

The playback adapter supplies the admitted user and bearer plus the bounded
device identity projected from the same token's `dev` claim. This follows the
reviewed identity/device interfaces in the pinned public bundle, rather than
generating an unrelated guest device. Each feed still obtains its own fresh media
token, exact source and one-exchange native CDM. Existing explicit free-content,
region, source, manifest and license gates remain. Reusing an account does not
enable paid content or establish a license-renewal protocol.

Credentials cross only a private, same-UID Binder channel bound to a nonce,
instance, route proof, source proof and original deadline. They enter only the
app-owned bootstrap initialization in RAM; they do not enter Intents, saved state,
generic UI, provider login scripts or diagnostics. The private initialization
bytes are cleared during teardown. Historical explicitly selected guest
observations remain separate from configured account-consuming paths.

This change does not renew the native bearer or extend the provider JWT expiry.
Browser reuse is the verified reconnect path; silent native renewal still needs
verified provider behavior and remains part of #212. A remembered browser login
and a usable native account are distinct states.

Verification passed 2,621 JVM cases across 299 reports with no failures, errors or
skips, Android lint, release isolation, instrumentation compilation, both APK
assemblies and the shared debug signing checks. The paired app-owned JavaScript
fixtures passed 92 discovery and 73 native cases. Scoped hooks/documentation and
independent reviews passed. Fixtures cover overlap/detach, late publication,
original deadlines, expiry/rollback, account/route changes, Forget, refusal,
uncertain cleanup and replacement without an Activity pause.

On persistent `tachiai-dev`, Android 16/API 36 x86-64, WebView 133.0.6943.137,
one exact configured Morning episode `89-70_s111_p4016` passed the production
playback harness in 22.405 seconds through the captured saved Windscribe route.
The harness required ACCOUNT, observed a decoded frame and advancing READY
positions, and confirmed cleanup, no route failure and unchanged protected
records. No guest fallback, sign-in or saved-grant write occurred. Playback was
muted; exit country, acoustic behavior and phone fidelity were not measured.

The native account screen automatically reached CONNECTED without a Validate or
Connect action and preserved the same grant. Three ordinary-process checks then
passed in 47.416 seconds with zero skips:
production My List returned one entry, History returned two entries with a next
handle, and exact episode metadata refreshed through the actual Android catalog.
No continuation or saved Add was performed. The metadata harness retains the
historical GUEST_CATALOG log label; the current production path uses the shared
account when the protected grant is present. Across installation, playback and
catalog checks, all 20 `no_backup`/`shared_prefs` files and captured ownership
remained unchanged. The temporary cached-player test manifest was restored
byte-for-byte, the ordinary test APK was rebuilt/signed without a process override,
and the normal launcher was restored. These observations establish bounded
same-account access; automatic native renewal, real concurrent consumer recovery,
paid content, live/slot/Chase and sustained playback remain unverified.

The browser passively observes only the Authorization header on the exact
first-party GET to the modern user's email-setting endpoint. It forwards the
original request unchanged. A candidate needs matching request identity and JWT
subject plus a finite future expiry; it is not yet an accepted account. The
candidate crosses a nonce/instance/owner/deadline-bound, same-UID Binder channel
only after confirmed browser, proxy and route cleanup. It never enters an
Intent, activity result, saved state, generic UI or diagnostics. The ordinary
application process owns acceptance and protected storage.

Native acceptance checks the captured owner, original store revision, current
foreground and expiry, then requests the matching profile and a positive
registered-email result with the candidate bearer through that route. Email
values are discarded; only a registration category is returned. Guest/no-email,
rejected, mismatched or late responses cannot publish Connected. JWT decoding
alone is not cryptographic validation. Rechecking saved authorization revokes
prior usable leases before checking the saved identity and email registration;
a refusal cannot retain an earlier Connected status.

The browser uses normal TLS and its own persistent ABEMA login. It has no native
JavaScript bridge, injected scripts, cookie readback, file/content access,
downloads or external navigation fallback. Desktop cookies and tokens are not
copied to Android. Provider-owned `*.abema-tv.com` browser resources are admitted
under the owner's 2026-10-12 instruction. The account origins and narrow region GET
remain separate from playback networking. Geolocation and location prompts are
refused; region remains the configured route's exit.

The browser's destination-enforcing CONNECT bridge supports both an explicitly
selected System connection and an imported connection. System approval uses an
authenticated, host-restricted loopback bridge whose admitted destinations are
dialed through Android's current network, including any active device VPN and
system DNS. Imported routes retain their selected transport and never fall back
to System. Both modes require confirmed WebView proxy installation before loading
the provider page. Other System route purposes retain their direct behavior.
The native email operation has an original ten-minute deadline, with provider
code expiry independently enforced by ABEMA. No invalid-credential rejection
probe or native guest/device challenge is part of normal email setup. The old
profile-enforcement experiment remains opt-in instrumentation only.

Accepted grants retain the per-instance AndroidKeyStore AES-GCM binding and
no-backup atomic file. Native renewal is unverified and requires reconnection;
refresh tokens and browser cookies are not imported or saved. Owner, foreground,
expiry, shared-store revision and Forget revoke stale leases. Failed replacement
conditionally restores the exact prior record, never a newer replacement or
Forget state. Uncertain HTTP/browser/route cleanup prevents publication and
retains the still-owned resources until process restart.

### Automatic saved-account validation — 2026-10-12

The account controller automatically validates an eligible saved grant after
loading and on a genuine return to the foreground. Both identity and registered
email are checked through the freshly captured selected owner; loading a saved
record alone is not Connected. Repeated foreground notifications and overlapping
manual actions do not start duplicate work. Pending email/QR approval, browser
handoff and Forget retain their own lifecycle and controls. Manual validation
remains available for bounded recovery.

The two read-only requests share one original thirty-second validation deadline.
Request-operation time limits and inconclusive provider responses are temporary
failures, distinct from decoded grant expiry, consent expiry or an actual
authorization refusal. Pausing, Forget, replacement, owner changes and stale
callbacks still revoke publication. Successful validation preserves the grant,
its generation and its provider expiry; it performs no challenge, token polling,
credential rotation, rejection probe or browser navigation.

This is a bounded controller slice of
[#212](https://github.com/altendky/tachiai/issues/212), not completed automatic
authorization maintenance. My List and History retain their independent
automatic on-use validation and collection proofs. Shared cross-consumer
coalescing and recovery remain further work. The guest native viewer does not
consume this saved account; account-entitled playback is separate integration.
Native renewal remains unverified. Retained first-party browser code renews
through cookie-backed `/api/auth/accessToken`; it does not establish a native
bearer-only refresh protocol, and browser cookies are not read back or imported.

Full local Android verification passed 2,470 JVM cases in 292 reports with zero
failures, errors or skips, including the new controller and timeout cases.
Lint, release isolation, instrumentation compilation, both APK assemblies,
shared debug certificate verification, scoped hooks/docs and independent review
passed. The existing History parser's compiler warnings remain; native libraries
were unchanged. Six provider-free account-screen cases passed in 11.446 seconds.

On persistent tachiai-dev, Android16/API36/x86-64 with the saved native email
grant and selected Windscribe route (exit country unmeasured), opening the native
account screen reached
Checking and then Connected without pressing Validate or Connect. Covering it
with the native launcher and returning to the same Activity triggered another
Checking-to-Connected validation. No provider browser or handoff was opened.
The observation stripped native UI snapshots to fixed status categories and
retained no raw UI. All twenty saved files were byte-identical after installation,
the six synthetic cases and both observations; the ordinary launcher was
restored. The phone was not used. This establishes two bounded controller
validations, not renewal, switching, full #212 acceptance or playback.

After removing a compiler-reported redundant cast, fifty focused account
controller/HTTP cases and the remaining Android build, lint, isolation and
signing checks passed again. On 2026-10-13 UTC the exact final APK also passed
one further automatic native startup validation. All twenty saved files stayed
unchanged, and the ordinary launcher was verified in the foreground afterward.

One installation attempt lost its ADB connection. Read-only verification found
the old APK and unchanged saved files; a subsequent bounded normal update
succeeded. No reset, uninstall, route fallback or saved-state restoration was
used.

### Explicit System approval bridge — 2026-10-12

The approval-only System bridge reuses the existing authenticated CONNECT
admission, connection bounds, cancellation and opaque tunnel handling. Selected
System mode and proxy presence are distinct: generic System requests remain
unbridged, while both approval modes require the restricted bridge. Region checks
reject generic playback/account routes even when those have an imported backend.
JVM fixtures cover both selected modes, private client authentication, policy,
rollback ownership, unsupported WebView proxy installation and callback ordering.
The full Android checks passed 2,454 JVM cases with zero failures/errors/skips,
lint, release isolation, instrumentation compilation and both APK assemblies.
The full provider-free Go race suite passed; both native ABIs, 16 KiB alignment,
source-companion consistency and the shared debug signing certificate passed.

One explicitly opted-in JNI check executed on persistent `tachiai-dev`, Android
16/API 36 x86-64, with installed WebView 133.0.6943.137. It passed one test with
zero skips in 0.088 seconds: three missing/wrong/duplicate-authentication requests
returned 407, and six authenticated unapproved-host/IP/apex/lookalike/port requests
returned 403. Confirmed cleanup closed the local listener, and repeated actual
native close succeeded. Every connection was local; no authenticated approved
destination, DNS lookup, provider request, browser or WebView override was used.
This verifies JNI admission and cleanup, not System-provider connectivity, exit
country, regional eligibility or account approval.

The same verified production APK updated the emulator and the owner's Pixel 6
(Android 17/API 37 arm64) without clearing data. All protected native records and
shared preferences remained byte-identical: 20 files on the emulator and 22 on
the phone. The existing Windscribe ABEMA owner was retained, ordinary Main was
restored, and phone observations establish installation/preservation only.

### First-party desktop email reference — 2026-10-11

One user-directed desktop browser run completed ABEMA's own email sign-in through
the browser's existing connection. Exact engine/version and egress country were
not established. Observed requests were guest login HTTP200, email-code issuance
HTTP200, email login HTTP200, matching profile HTTP200 and registered-email
lookup HTTP200. The code form closed, Account management displayed the supplied
email address, and profile requests changed from the initial guest to a second
identity. Only normalized paths/statuses and these agreement categories were
recorded; no account identifier, address, code, cookie or token value is retained.

The code request used the email one-time-passcode endpoint and completion used
the email login endpoint, rather than modern device authorization. The current
[first-party account asset](https://abema.tv/assets/compat/4ad4fadda744ab29.9298.js)
confirms email issuance, emailed-code login and the registered-email lookup.
Its email login DTO projects user ID, access token and refresh token without
requiring OAuth's token type. The browser connector did not retain the successful
login response body, so that response shape is static first-party evidence,
not a captured body. The registered-email response was available and agreed
with the address supplied to the provider. This establishes the first-party
browser case, not Android header exposure, native grant adoption, session
renewal, My List/history or account-backed playback.

### Email page and saved-route comparison — 2026-10-11

A fresh `/account` document in the working desktop browser returned HTTP200,
initial `isAllowed=true` and `status=true`, Japan/Tokyo region categories and no
Sorry HTML. Its request identity was Linux desktop Chrome155, with the browser
identified by the owner as Brave. A second navigation timed out; a complete
successful navigation/resource trace was not captured.

On tachiai-dev, two cookie-free exact `/account` requests used the currently
selected Windscribe-named WireGuard connection, with matching owner checks,
confirmed cleanup and unchanged encrypted records. Default WebView and recorded
desktop User-Agent requests both returned HTTP200, Japan/Tokyo eligibility and
no Sorry HTML. A follow-up recorded only the canonical public region host:
`ds-linear-abematv.akamaized.net`. The earlier browser policy admitted only
`ds-glb-linear-abematv.akamaized.net` and returned a local403 for the second host;
the verified provider consumer interprets403 as region denial. The corrected
approval adapter admits the two exact resources and requests the page's actual
selection through the captured route. These public-page checks establish the
current saved route's initial eligibility, not completed browser login, grant
adoption, or a measured exit address.

After the correction, the visible browser on tachiai-dev (Android16/API36,
WebView133.0.6943.137) recorded saved proxy application, admission of its main
document request, accepted local proxy authentication and page start/commit/
finish. No region-adapter result was recorded in that attempt. The protected
page did not expose account labels to the bounded accessibility check; owner
visual confirmation and completed email sign-in were outstanding at that stage. These
stage categories contain no URLs, headers, provider text or credentials. The
tested APK was installed on both tachiai-dev and the verified Pixel6, with
matching installed hashes; the phone was not used for this browser experiment.

### Historical device-authorization verification

Local verification passed 2,128 JVM cases, both owned JavaScript suites,
Android lint/release isolation, instrumentation compilation and app/test
assemblies with the shared signing identity. Five memory-only Android UI
fixtures and one network-blocked private-challenge fixture passed on
tachiai-dev. Ordinary test targeting was restored and the original encrypted
record baseline remained unchanged throughout.

One explicit saved-route check on tachiai-dev (Android 16/API 36, WebView
133.0.6943.137; egress region not remeasured) established matching fresh-guest
profile acceptance and invalid-bearer rejection in 11.736 seconds. It opened no
browser, adopted no account grant and performed no token polling or storage
write. This bounded profile-enforcement evidence does not establish a
registered account or collection compatibility.

At that stage, intended-account acceptance was outstanding. A connected account does not by itself establish
My List, history, entitlement or account-backed playback. Those capabilities
need separate endpoint and intended-account evidence. These historical checks
did not satisfy #103's intended-account acceptance; the later email acceptance
is recorded below.

On 2026-10-11, the corrected routed browser still displayed the known regional
refusal without invoking its region adapter. After Return/Cancel and confirmed
cleanup, one separate cookie-free fixed CDN region GET returned HTTP200 through
the captured saved route in 1.428 seconds. A separately opted-in fixed public
approval-page GET, without activation query, cookies, redirects or retries and
using the public default WebView identity, returned HTTP200 and 31,585 bytes of
regional Sorry HTML without initial client-region assignment, in 2.019 seconds.
That public response is server-side refusal and may differ from private
activation HTML. Endpoint acceptance does not establish a Japanese exit address
or account approval. Device geolocation remains disabled; no invented
coordinates or region flags are supplied. Persistent tachiai-dev, Android
16/API 36, WebView 133.0.6943.137 and unchanged encrypted records bound these
observations.

### Gateway admission audit — 2026-10-12

The exact gateway assumption exists independently of the approval browser.
The owner-requested browser/CONNECT wildcard does not remove these native
constraints:

| Area | Current hostname coupling |
| --- | --- |
| Route purposes | `RouteSession` repeats `api.p-c3-e.abema-tv.com` in native request admission and CONNECT host lists for profile, metadata, account and collection operations. |
| Guest preparation | `AbemaGuestBootstrapPolicy` and `guest-bootstrap.js` require that exact configured API origin; generated CSP repeats it. |
| Email grant adoption | `AbemaAccountAuthorization` recognizes the email-setting request only on that host; native profile/email request constructors also select it. |
| Replay preparation | Native helper CSP, Kotlin request policy and JavaScript checks repeat the fixed API, `streaming-api-cf.p-c2-x` and `license.p-c3-e` gateways. |
| Slot region check | `AbemaNativeBootstrapPolicy` still accepts only the global-linear Akamai `/region`, although approval networking recognizes the provider-selected linear alternative. |
| Selected media | `vod-cf.p-c3-e.abema-tv.com` is recognized by diagnostic classification but excluded by the active approved-media origin set; the playback CONNECT union also lacks it. |

The provider-family origin policy should be distinct from endpoint operation,
source/session identity, expiry, entitlement and manifest binding. Recognizing a
provider-selected origin diagnostically is not currently enough for admission.
This audit establishes duplicated constraints, not verified interchangeability
of their APIs or completed native support for alternate gateways.

On 2026-10-12 the owner observed the startup splash followed by the generic
display-failed page, distinct from the earlier regional refusal. Local stages
confirmed saved proxy application/authentication and document start/commit/
finish, without a region request. The first-party landing-status request precedes
that region step; the omitted gateway was another policy mismatch.

After the owner-requested `*.abema-tv.com` browser/CONNECT update, a fresh
tachiai-dev attempt recorded saved proxy authentication, gateway resource
admission and then the linear-region adapter's actual HTTP200. Reaching the
region step establishes advancement beyond the landing-status check; no gateway
response body or authorization value was captured. Native Go race fixtures,
both rebuilt AAR ABIs, Android tests/lint/release isolation and shared-signing
verification passed. The same APK was installed on the emulator and verified
Pixel6 with matching installed hashes. The owner's subsequent view confirmed
that Account management opened with email unset; no account identifier or image
was retained. Email sign-in and native grant acceptance were still pending in
that startup observation; the phone was not used.

### Native email account acceptance — 2026-10-12

The owner completed provider-controlled email sign-in and the native connection
on persistent tachiai-dev. A fresh opening of the native ABEMA account screen
loaded the saved grant and reported that it needed fresh validation. The normal
**Validate saved ABEMA account** action then reported **ABEMA account validated
for this instance**. The operation required a matching account-profile identity,
positive registered-email status, usable protected-store revision, current route
owner and foreground, and an unexpired finite saved grant before accepting
Connected. Validation uses the owning instance's saved route and confirmed
transport cleanup; it does not replace the saved grant.

Context: tachiai-dev/emulator-5580, Android16/API36/x86-64,
WebView133.0.6943.137, configured Windscribe WireGuard route. The current
owner-controlled connection followed the successfully admitted provider gateway
and actual linear-region HTTP200 described above. Only public state labels and
agreement categories were recorded, without addresses, account identifiers,
verification codes, tokens, cookies or screenshots.

One app-process stop and normal launcher restart preserved the saved account.
Reopening its account screen again loaded an unverified protected grant, and
the same normal validation action again reported Connected. This establishes
saved native email-account adoption, fresh validation and process-restart
persistence on the emulator. Future expiry/reconnection, My List, History and
account-backed playback still need their separate runtime checks.
The verified Pixel6 has the same installed APK but was not used for this
account acceptance check.

### Account-backed My List adapter

The debug catalog composes a read-only My List adapter around public Channels
and inside local public-link import. Existing configured choices remain
Tachiai-owned: browsing never automatically adds an item, mutates ABEMA's list
or deletes a configured choice when the provider omits it. The separate
[History adapter](provider-history-access.md#abema-read-only-history-adapter--2026-10-11)
uses its own collection binding and endpoint proof under #102, with its separate
bounded intended-account observation recorded there. Bounded My List acceptance
is recorded below.

Account validation runs on workers and uses the core session directly. Missing
or corrupt protected authorization starts no provider request. A valid saved
candidate needs a fresh challenge-free guest/profile context, matching guest
profile and intended-account identity agreement. My List must return a strictly
parsed page with that intended grant before reporting access as available.
Normal collection access makes no invalid-bearer requests. All validation
requests retain the original guest preparation deadline. The first positive
page can seed one initial browse in memory; it is not persisted as provider
history or usage data.

The closed collection route admits only the reviewed API host. One fixed
read-only GET requests at most 20 entries, newest updated first, with typed
dataset joins and live events included. Responses are bounded to 512 KiB;
non-success bodies remain unread. Programs map to exact episodes, series and
slot groups remain collections, slots retain their channel/occurrence identity,
and live events retain their own identity. Availability stays UNKNOWN rather
than inferring entitlement, current playback or progress from list membership.
Missing, conflicting or unknown joins refuse the page rather than substitute
another item.

Continuation handles are transient and single-use, bound to provider instance,
route, durable grant generation and shared store revision. Foreground, owner,
expiry, clock or authorization loss clears admission and private browsing state
while retaining configured choices. Collection refusal does not erase a saved
grant or turn an unverified provider response into an expiry diagnosis. Uncertain
HTTP or backend cleanup retains its actual owner and blocks account admission;
independently verified public discovery and local import keep separate gates.

Provider-free fixtures and source review are implementation evidence. The
bounded My List retrieval below establishes more than profile identity alone.
Broader discovery and exact slot/series resolution
remain outstanding in #101; this adapter does not choose a current channel or
an arbitrary episode on behalf of a collection.

### Intended-account My List acceptance — 2026-10-12

After the delivered email connection in
[PR #200](https://github.com/altendky/tachiai/pull/200), one explicitly opted-in
read-only observation on persistent tachiai-dev returned
`MY_LIST_PAGE_ACCEPTED`, with one entry and no continuation. The single executed
instrumentation case passed in 20.123 seconds with zero skips. Context:
Android16/API36/x86-64, WebView133.0.6943.137, the owner's saved native email
grant and the configured Windscribe WireGuard route. The guest/profile context,
intended-account profile and typed My List response were accepted through that
captured owner; no account identifier, entry title or resource identity was
exported or retained in the observation.

The corrected fixture browses My List directly instead of requesting all catalog
capabilities, which also checks History. It verifies the AVD before private
reads, holds an explicit foreground lifetime, consumes only the first positive
page and starts no History, continuation, login, rejection probe or playback.
Protected record digests and captured route ownership are checked after cleanup,
including exceptional cleanup paths. The external no-backup/preferences
baseline was also byte-identical afterward; ordinary launcher state was restored.
The protected account, routes and configured items were preserved.

Forty-nine targeted collection/binding JVM cases passed, including zero History
access checks during direct My List browse. Android instrumentation compilation,
test APK assembly, lint and release isolation passed; app and test certificates
matched the shared debug identity. This one page does not establish complete
pagination, every resource kind, History, renewal, entitlement or playback.
The physical phone was not used for this collection observation.

### Explicit series episode browsing

Issue [#176](https://github.com/altendky/tachiai/issues/176) adds a narrow
series-children adapter through the existing shared catalog. A selected exact
series remains a collection: the user browses its episodes, chooses an exact
episode and explicitly adds it to Tachiai's configured list. The provider's
auto-next/first-item behavior does not choose a configured playback resource.
Back and Add retain the shared management flow; provider omissions or refusal
never remove configured identities, order or quality preferences.

The retained coherent
[9298 entry](https://abema.tv/assets/compat/4ad4fadda744ab29.9298.js),
[3795 request/DTO chunk](https://abema.tv/assets/compat/35a6b0a35f792be3.3795.bundle.js)
and [6245 consumer](https://abema.tv/assets/compat/3eaa977a7f089316.6245.bundle.js)
establish a bearer GET to the reviewed modern API's exact series-program path.
The consumer requests pages of 20 with ascending `seq` or descending `-seq`
order and advances its requested offset by 20. Program ID and matching series
ID establish exact identity; the nested episode title supplies display text.
Series-local search is not global video search, and calendar timestamp
conversion does not establish every provider timestamp's units.

The initial adapter uses ascending `seq`, a fixed page size of 20 and bounded
locally requested offsets. The isolated guest operation retains one immutable
series/page request, the owning instance/route proof, original deadline and
foreground checks. It exports only strictly normalized public episode rows
after confirmed runtime/route cleanup. Duplicate or wrong-parent joins,
malformed titles and ambiguous response data refuse the page. Availability
remains UNKNOWN, without inferred schedule, progress or entitlement. No
existing-account connection, approval challenge, browser or grant write is
part of this operation.

The provider DTO coerces version to String and offset to Number; its reviewed
consumer ignores both and supplies no version parameter. Those coercions do
not prove raw wire types or stable snapshot semantics. Tachiai initially
accepts only a nonempty bounded raw version string, with no coercion. Transient
single-use continuation handles bind that version, exact parent, order,
requested offset and owner revision; subsequent results must match the pinned
version. Missing, unsupported or changed versions refuse visibly rather than
claim that a list is exhausted. Response offset is not interpreted.

Two explicit read-only saved-route checks on 2026-10-11 used exact sumo series
`394-72`: one first-page check passed in 20.648 seconds and one separately
requested two-page check passed in 28.841 seconds. Each page contained 20 valid
distinct episodes; the second request used the first page's accepted nonempty
string version, matched it and had no overlap with the first page. This proves
bounded fresh-guest compatibility and versioned-request acceptance for that
series, not snapshot guarantees for every provider update. The device was
persistent tachiai-dev, Android 16/API 36, WebView 133.0.6943.137, with captured
saved route, unmeasured exit country and no existing-account grant. No item was
added or played, and protected records stayed unchanged. Thirteen network-blocked
Android fixtures, including the two-series/versioned-page projection and private
IPC guards, subsequently passed in 2.43 seconds. Provider-free JS/JVM coverage
preceded the actual requests; the Android fixtures ran afterwards. Temporary
guest-process instrumentation targeting was restored to the ordinary manifest.
Series browsing does not imply global video search, timetable/slot resolution
or an automatically playable permanent sumo collection.

### Exact configured broadcast metadata

Issue [#178](https://github.com/altendky/tachiai/issues/178) delivered metadata
refresh for an already configured exact ABEMA broadcast slot. The existing
public-link parser retains both the channel and slot IDs; refresh must return
that same resource. It does not select the channel's current program, another
day/division, a replay or a recurring group.

The coherent [9298 entry](https://abema.tv/assets/compat/4ad4fadda744ab29.9298.js),
[8974 slot caller](https://abema.tv/assets/compat/0df864b4d17ad744.8974.bundle.js)
and [3194 slot model](https://abema.tv/assets/compat/4fa634e6d16c9c17.3194.bundle.js)
establish a bearer GET on the reviewed modern API at the exact media-slot
path, with `include=payperview`. The response's `slot.id`, `slot.channelId`
and `slot.title` supply the identity and display projection. Optional division
comes from a provider context that this slice has not established; it is
omitted rather than guessed. These are static first-party leads, not actual
guest acceptance or documented third-party support.

The bounded operation uses a fresh guest and one immutable exact-slot
request. Same-UID private IPC carries a closed slot request/result schema
through the existing metadata service; old episode messages retain their
contract. Native admission checks the exact canonical GET and optional
authorization preflight, including the fixed query. CSP constrains the
selected endpoint path, while unrelated account, media and licensing
requests remain refused.

Strict whole-response validation requires the selected slot and its exact
parent channel, a bounded safe provider title and unambiguous JSON. It
projects UNKNOWN availability and no schedule, progress or entitlement.
Worker owner/deadline checks and confirmed runtime/proxy/backend cleanup
precede publication. Failed refresh preserves configured UUIDs, identities,
order and quality; existing public-link import remains usable for non-live
or unavailable items. Exact-slot playback remains NOT_VERIFIED.

Bounded guest metadata acceptance passed through the saved route; the Schedule
observation above includes separate exact-candidate refreshes. This does not
establish complete response limits, optional division behavior or playback.
Network-blocked and explicitly opted-in saved-route checks preserve protected
records and restore ordinary test targeting. No account Connect, recurrence or
provider history operation is part of this metadata slice.

### Exact broadcast source investigation

Agent issue [#193](https://github.com/altendky/tachiai/issues/193) tracks exact
configured broadcast playback. The common viewer still refuses a slot resource;
the metadata adapter cannot supply playback authorization, source or license
state. A slot must never become the channel's current broadcast or another
episode when its own source is unavailable.

The coherent [8917 consumer](https://abema.tv/assets/compat/bcf9d5e368763476.8917.bundle.js)
uses `slot.playback` for timeshift and separately uses `slot.chasePlayback` for
chase playback, with the exact slot ID and DRM marker.
[7478](https://abema.tv/assets/compat/f8086b0d6124dd82.7478.bundle.js) and
[8204](https://abema.tv/assets/compat/f6010ef1803f018d.8204.bundle.js) construct
and select the provider's playlist source. The modern gateway also supports an
ARIN source, but that downstream arm does not by itself verify a selected slot's
source construction or entitlement. Runtime bundle compatibility, guest rights,
advertisement selection and native acceptance remain separate checks.

On 2026-10-11, one explicitly opted-in existing exact-slot GET for the public
candidate `abema-anime/9FYQvU4hBaZZy1` passed in 10.09 seconds. Device:
`tachiai-dev`, Android 16/API 36, x86-64, WebView 133.0.6943.137, captured saved
ABEMA route, unknown exit country, fresh guest and no existing-account access.
The test-only owned-script decorator projected 23 closed shape categories and
stripped them before the unchanged production decoder. Publication followed
exact accepted metadata, current ownership, confirmed cleanup and unchanged
encrypted-record checks. No source URL, ARIN, timestamp value, authority ID or
raw response was recorded, and no Add or playback occurred.

That response contained ordinary and chase playlist objects, both DASH strings,
an ordinary ARIN string, no chase ARIN, and Japan-only linear/timeshift policies.
Timeshift and chase flags were explicitly true; pause, free-timeshift, DASH and
DRM flags were absent. The DRM marker was an empty object, PPV was absent,
start/end/general-timeshift windows were positive safe integers, the free window
was absent, and chase authority IDs were nonempty. These are observed shapes:
an omitted free flag is not affirmative guest entitlement, a positive integer
does not establish a current window, and a returned playlist is not playback
acceptance. The provider's authorization predicates must be checked separately
from its display-period helpers before enabling a source.

The retained ordinary Timeshift container first requires the timeshift flag, an
open general window, allowed region, no pause and supported DRM; it then admits
premium access or `96291`'s free predicate. That helper is also used for telemetry.
An explicit true free flag with absent/zero free-end falls back to the open
general window; zero general end still fails the container's separate check.
Chase admission separately requires a current premium/general window or a
current free-end window and matching subscription feature authority. Premium
access checks unexpired viewing authority rather than a trial flag. This
candidate's absent free flag and nonempty chase-authority list do not establish
guest eligibility. PPV/ticket behavior, runtime compatibility and the concrete
modern slot-source constructor still require direct consumer evidence.

The shared diagnostic harness retains its opt-in-before-private-read gate,
fixed operation/candidate, original deadline, touch-blocked lifecycle and
retain-on-uncertain-cleanup behavior. Strict synthetic marker tests and the
existing Series projection test actually passed with zero skips; 12 owned-script
synthetic cases and independent review also passed. Full Android verification,
including 2,390 JVM cases, lint, release isolation, instrumentation compilation
and shared signing passed. Ordinary instrumentation and Main were restored and
saved records preserved. This observation does not close #193 or #101.

### Exact broadcast catch-up adapter

The next #193 slice carries the canonical channel/slot BROADCAST identity into
an independent native replay feed. It does not choose that channel's current
broadcast, replace the configured UUID, or reuse another feed's guest/token/CDM.
Future, live/chase, premium and PPV sources remain saved but outside this initial
free ended-broadcast slice. Saved UNKNOWN availability only starts verification.

The cookie-free runtime reads one fixed public News HTML response as bounded
data without loading its document or executing its scripts. Its single strict
`window.__CLIENT_REGION__` JSON assignment must explicitly allow access, report
Japan and numeric NONE division. Coherent9298/module42770 exposes raw
`divisionCode`;76599 defines NONE0/GLOBAL1, and7478/module89131 uses that value
for broadcast-region admission. No country, division, GPS or timezone is invented.
When returned status is true,32735 requests its region CDN URL:403 vetoes access
and 200 preserves the original result. This adapter additionally refuses transport
failure rather than using the provider's fallback. A CDN200 alone proves no
country or entitlement. The reads are fresh, have finite deadlines and remain
within the feed's captured route and current ownership. Missing, duplicate,
malformed or Sorry-page region data refuses before slot source selection.

One exact modern slot GET preserves both raw IDs. Ordinary admission requires
explicit timeshift/free flags, no pause/unsupported DRM/PPV, supported region,
an ended valid broadcast interval and an open general window covering the
bounded session. Absent/zero free-end uses that general window; an explicit
positive free-end must also cover the session. This is separate from Chase's
subscription-feature-authority gate. Returned metadata does not itself authorize
the selected source, manifest or license.

The actual pinned legacy selector receives advertised playlists, the fresh
media token, truthful Android UA, provider initial ad-cluster0 and absent dtid;
plain-stream forcing stays false. Its selected DASH must contain the exact slot
as a complete path segment on the existing approved CDN family, with the closed
query retaining the same token. No universal slot-directory layout is assumed.
The runtime-mapped4973/module43132 binds Timeshift Clear Key inputs to ct=slot,
cid=the same slot and t=the fresh token. Only standard URL fields are composed;
the unchanged verified helper and one fresh native CDM own the opaque initial
exchange. Native code rereads the exact channel/slot/source projection, accepts
bounded standard protected initialization and lets the MPD declare exact media.
Wrong identities, unsupported protection, changed KIDs, redirects and uncertain
cleanup refuse without another source, credential or licensing response.

Separate provider Timeshift IMA orchestration is established by8974/module13672,
8917/module76212 and9361/module42446. It is not a proved server completion gate.
The user's [removed debug ad-adapter prerequisite](security-and-privacy.md)
continues to apply: preserve original provider selection without forcing ad-free
mode or fabricating completion/tracking. This native path does not implement a
separate ad driver or claim equivalent advertisements or distribution readiness.

Local verification passed 2,413 JVM cases across the debug/diagnostic variants,
64 owned JavaScript cases, lint, release isolation, instrumentation compilation,
APK assemblies and the shared debug signing checks. On persistent tachiai-dev
(Android16/API36/x86-64, WebView133.0.6943.137), six synthetic native source and
cleanup tests and one network-blocked owned-WebView binding/region test passed
without skips. The latter exposed Android's solidus escaping in `JSONObject.quote`
versus JavaScript `JSON.stringify`; strict binding now reconstructs only the
already-admitted ASCII wire fields, and the actual device rerun passed.
Ordinary instrumentation/Main were restored and encrypted records unchanged.
These fixtures establish no actual provider region, account entitlement, license
acceptance or advancing frames. Real eligible-slot playback remains unverified.
The earlier captured route's regional refusal and the observed candidate's
missing free flag do not provide a positive playback acceptance case. Keep193,
100 and101 open for remaining access modes and bounded actual acceptance.

### Slot response envelope correction — 2026-10-12

Native slot preparation previously treated the complete media-slot reply as the
selected slot and checked identity on that outer object. The established exact
metadata decoder and retained first-party slot caller instead read the response's
`slot` object. The successful exact metadata observation above used that decoder.
The native fixtures supplied a bare slot DTO, so they missed this mismatch. A
separate provider-free reproduction accepted the bare DTO but refused a wrapped
reply before source selection or helper execution; both inputs were synthetic.

Preparation now requires an object envelope containing an object `slot`, with the
exact requested channel and slot IDs inside it. It accepts no bare or alternate
wrapper fallback. Whole-response UTF-8, Unicode, duplicate decoded-key, nesting,
size and trailing-data checks remain in place, including ignored outer fields.
Existing region, free/rights/windows, source/ad selection and exact license
binding checks still apply to the selected slot.

Retained first-party evidence puts lowercase `payperview` inside the slot model;
that field must remain absent or null. Non-null outer `payperview` or `payPerView`
also refuses preparation as a conservative local restriction on unverified
forms. This does not establish outer PPV schema or entitlement semantics. Other
outer metadata and absent/null outer PPV fields are allowed subject to the whole
JSON checks. The separate exact metadata decoder is unchanged and continues to
project UNKNOWN availability without granting playback rights.

Verification passed 151 owned JavaScript cases: 66 native-bootstrap and 85
guest-bootstrap cases, with zero failures/skips. Fixtures cover wrapped success,
bare/missing/null/scalar/array responses, nested identity mismatches, outer IDs
that cannot override the selected slot, literal/escaped-equivalent duplicate
keys and PPV restrictions. Existing region, rights/windows, source/license and
cancellation cases remain covered. Full Android verification passed 2,449 JVM
cases across 292 reports with zero failures/errors/skips, lint, release isolation,
instrumentation compilation, both APK assemblies and shared debug certificates.

On persistent tachiai-dev, one networking-blocked Android test executed and
passed in 0.64 seconds with zero skips. Context: emulator-5580, Android 16/API 36,
x86_64, WebView 133.0.6943.137, synthetic fresh guest/module/response data in the
owned cached-player process. Its three cases checked regional refusal, wrapped
binding acceptance and bare-response CONTENT refusal; helper calls remained
zero. It made no real provider, account, manifest, license or playback request.
The temporary test-process manifest was restored byte-identically, ordinary
shared-signed instrumentation was restored, and the normal launcher returned.

The same verified production APK was installed with data retained on the emulator
and the owner's Pixel 6/oriole, Android 17/API 37, arm64. Before/after digests for
all files under `no_backup` and `shared_prefs` were identical: 20 on the emulator
and 22 on the phone. The synthetic fixture ran only on the emulator. These
checks establish response decoding and local admission, while actual eligible
slot entitlement, manifest/license acceptance and advancing frames remain
unverified. Issue #193 stays open for that acceptance and broader playback modes.

### Exact-slot playback acceptance harness — 2026-10-12

The opt-in configured-playback harness now accepts one explicit canonical
`channel/slot` resource as `slot`/`BROADCAST`. It creates only an in-memory
configured source, resolves the exact resource through the common selection
contract and supplies only `slotResource` to the production cached session.
Episode and channel inputs remain empty for this mode. Conflicting episode/slot
selectors refuse before private access; no historical sample, channel fallback,
saved Add or provider/account-policy change is introduced.

After a native video frame, acceptance requires two READY samples from the same
player on its application looper, at least 1,500 ms apart, with increasing
nonnegative position. Both samples retain foreground, current-run, failure and
original-budget checks. The earlier fixture checked only one position above
500 ms, which could accept a stalled player at a nonzero initial position. Its
historical frame observations remain useful, but do not establish this stronger
movement proof. No eligible slot has yet passed the new harness; live, Chase and
account-required slot playback remain unverified.

One explicitly selected muted Morning-episode check on persistent `tachiai-dev`
executed and failed in 39.788 seconds with zero skips: `CLEANUP_UNCONFIRMED`.
Context: Android 16/API 36 x86-64, WebView 133.0.6943.137, existing saved Windscribe
route, unmeasured exit country and a fresh guest without the existing account.
Closed native event markers reported DRM request/handoff/key loading, READY,
PLAYING and VIDEO_FRAME. The fixture then overwrote its earlier playback result,
so neither measured movement nor orderly cleanup acceptance can be inferred.
The actual cause remains unverified; process exit does not establish orderly
cleanup. No source substitution or cleanup-gate weakening followed this refusal.

The source test manifest was restored byte-identically, the ordinary shared-signed
test APK was rebuilt and checked to contain no `targetProcesses` override, and
normal Main returned. All 20 protected native files/shared preferences matched
the pre-observation baseline and the saved route owner remained current. The
production APK was unchanged. Fixture-only diagnostics now preserve the closed
pre-cleanup outcome, actual frame callback and movement result, plus main-thread
coordinator phase/stage, session receipt/proxy booleans and distinct disposal
failure flags; no provider values or raw exceptions enter those fields.
An in-memory reporter retains at most 16 existing failure-stage enum values;
it discards timestamps, session IDs and error classifications instead of writing
a diagnostic journal or protected store.

After the diagnostic-only correction, one disclosed repeat of the same Morning
episode passed one executed test with zero skips in 25.265 seconds. It used the
same unchanged production APK and saved owner, without source substitution,
account use, route changes or relaxed cleanup. The closed pre-cleanup result
reported a rendered frame and advancing samples; final state was FINISHED,
session cleanup confirmed, no pending/failed receipt, proxy idle, all disposal
failure flags false and no recorded failure-stage enums. The earlier cleanup
refusal was not reproduced, and its cause remains unverified.

The source manifest was again byte-identical to its original, and the rebuilt
ordinary shared-signed test APK had no `targetProcesses` override. Normal Main
returned and all 20 protected files/shared preferences still matched the original
baseline. Full Android verification passed test, lint, release isolation,
instrumentation compilation, both native ABIs and both signed APK assemblies;
the unchanged JVM suite remained 2,454 cases with zero failures/errors/skips.
Targeted queued cleanup-coordinator and worker regressions passed independently.
This establishes bounded exact-episode movement and cleanup under the stronger
harness, not eligible-slot, live, Chase, account, audio or physical-device proof.

### Exact configured episodes

[Issue #153](https://github.com/altendky/tachiai/issues/153) adds explicit metadata
refresh for a configured exact episode through the shared catalog contract. Its
[isolated guest service](abema-guest-bootstrap.md#configured-episode-metadata-refresh)
uses the owning instance's captured route and the exact public episode ID.
Only validated public ID/title metadata can return after cleanup and final
ownership checks; availability remains UNKNOWN. Missing/inaccessible metadata
retains the saved item, and no diagnostic sample, current broadcast or other
episode is substituted.

The common controller updates the provider title while preserving the configured
UUID/instance/resource, current order, quality preferences and unrelated records.
Separate legacy source labels remain unchanged. Local import still needs no
network request. This operation neither reads My List/history nor connects an
existing account; it adds no playback capability. A separate bounded headless
service/catalog check on persistent `tachiai-dev` accepted exact September episode
metadata through the saved route on 2026-10-11, with encrypted records unchanged.
The linked observation records device/engine/account conditions and verification
limits; it does not establish general availability or entitlement.

### Exact configured-episode playback

Issue [#164](https://github.com/altendky/tachiai/issues/164) adds provider-owned
admission for canonical ABEMA `episode`/`VIDEO` resources with bounded public IDs.
The common resolver preserves configured UUID, instance, route, recovery and
quality identity, and passes the immutable resource into the cached session.
The historical full-page comparison explicitly refuses newly supported episodes.
Only an explicit historical constructor may omit the requested resource and use
its retained fixed comparison; an invalid explicit selection cannot fall back.

Configured feeds now acquire the shared instance account described above; a
genuinely missing account permits a pinned guest. Each feed retains its own helper,
fresh media token and native CDM. Its owned policy admits only the exact modern
episode metadata URL, validates
bounded duplicate-free raw metadata and unchanged converted DTO identity/ARIN,
and uses unchanged provider filtering, gateway, URL and DRM configuration modules.
The selected source, MPD, declared media and initial license remain bound to that
episode and fresh token. Route/profile ownership is captured and reread on
workers; foreground, epoch, deadline and confirmed cleanup guard publication.

A saved UNKNOWN snapshot may initiate verification but never proves playability.
The selected path requires explicit free metadata and valid nonempty advertising
terms covering the bounded session, alongside existing trial, restriction and
source/DRM-mode refusals. Current first-party consumers establish epoch-second
term/free-window comparisons; standalone program end dates and broadcast dates
are not guessed entitlement clocks. Unavailable, future, premium or unsupported
items remain configured while playback returns a safe refusal. The original guest
slice did not add My List, history, account adoption or renewal. Channels, exact
slots,
series and broader discovery remain [#101](https://github.com/altendky/tachiai/issues/101).
See [the native boundary](native-access-experiments.md#exact-configured-episodes--2026-10-11)
for static evidence and bounded device observations. On 2026-10-11, persistent
`tachiai-dev` rendered the distinct ABEMA Morning episode `89-70_s111_p4016`
and passed the earlier single-position threshold in an explicitly selected
13.051-second run. That fixture did not measure two separated advancing samples.
Separate historical News and replay regressions also passed. An earlier attempt
for the same Morning episode failed before a guest HTTP status returned; its
cause remains unverified. All runs confirmed cleanup and unchanged protected
records. This is bounded guest playback evidence, not account, sustained-playback
or physical-device acceptance.

### Exact configured linear-channel playback

Issue [#170](https://github.com/altendky/tachiai/issues/170) adds canonical
`channel`/`CHANNEL` resources to the same configured-selection contract. The
cached session receives the immutable requested channel, with its instance,
route, configured UUID, quality and recovery identity retained. The historical
full-page comparison refuses new channel selections rather than substituting News.

The owning account or pinned guest retrieves one bounded, duplicate-free channel
snapshot.
Admission requires exactly one matching raw ID, explicit `viewingOpened`, usable
advertised playback and a supported DRM mode. Saved UNKNOWN availability only
permits this verification. Missing or ambiguous metadata and Widevine-only
channels refuse; no alternate selection is tried. The unchanged pinned linear
selector retains its advertisement behavior. Selected source, exact channel MPD
root, fresh media token and initial channel license must agree. Native declared
media, protected initialization, bounded live transitions, ownership and cleanup
remain enforced independently for each feed.

Unused advertised alternates are retained unchanged as bounded metadata; they
do not need to have the selected channel's MPD path. The unchanged provider
selector performs no I/O. Its returned selection must pass the exact source
binding before native access, and an unbound selected alternate refuses without
substitution. This preserves the provider's source/ad decision.

This slice implements linear-channel intent. It does not resolve a scheduled
slot to a different current broadcast, automatically choose a series episode,
connect an account or retrieve My List/history. Static review and fixtures do not
establish actual playback; bounded device evidence is recorded separately in
[native experiments](native-access-experiments.md#exact-configured-linear-channels--2026-10-11).

### Account subscription profile observation

The next bounded #193 experiment observes one existing-account profile through
the production owned account transport and the instance's captured saved route.
Default invocation skips before protected reads. An explicit invocation requires
the persistent `tachiai-dev` AVD, ordinary app process and a retained grant whose
decoded expiry exactly matches protected storage. Missing, expired or inconsistent
authorization makes no provider request and reports a separate local-unavailable
outcome. It does not reconnect, bootstrap a guest, check email registration,
probe an invalid bearer, renew credentials or change saved records.

The test-only forwarding stream observes the production GET's existing reads;
it does not read ahead, alter bytes or persist the response. The unchanged strict
profile decoder must return the requested identity. A second strict projection
reports only fixed shapes, bounded counts, Boolean categories and subscription
expiry categories relative to the request's captured wall time. Root
`userSubscriptions` is distinct from a similarly named field under `profile`.
The pinned public caller requests the same profile without a subscription
expansion parameter. Its mapper separately consumes root legacy `subscriptions`,
modern `userSubscriptions` and `partnerServiceSubscriptions`; the observer keeps
their shapes and counts separate. Known legacy/partner fields and authority-ID
lists are classified only by fixed types, Boolean/expiry categories and bounded
counts. Legacy or partner entries alone do not establish the modern Premium
viewing-authority predicate, Chase access or an exact playable source.
IDs, plan and authority strings, expiry values, account headers, URLs and raw
bodies remain private. Capture buffers and private comparison digests are wiped.

The original 45-second observation budget starts before protected reads.
Publication follows current owner, stored grant/revision, foreground, clock and
unchanged-record checks plus confirmed actual HTTP/input/disconnect, route/backend,
worker and lifecycle cleanup. Cleanup has one sticky 20-second bound capped by
the original 65-second limit; uncertain original owners remain retained. Parent
fixture cleanup has a separate executor, leaving the production native input and
disconnect workers available. The experiment establishes observed profile shape,
not Premium entitlement, paid playback, Chase or exact live occurrence.

On 2026-10-13, a first explicitly opted-in modern-field check passed, zero skips,
in 5.154 seconds
on persistent `tachiai-dev`, Android 16/API 36, x86-64, using the retained email
account and captured saved Windscribe route. No provider browser was used by the
experiment; exit country was not independently measured. The owner had separately
returned from the remembered ABEMA browser session, and Tachiai confirmed the
connection before a requested emulator restart. Post-restart local admission
passed without a provider request. The actual experiment then made one production
profile GET, returned HTTP 200 and the matching identity, and accepted complete
strict JSON after all ownership, cleanup and preservation gates.

Both root and nested modern subscription fields were **absent** in that response.
The first projection did not observe legacy or partner fields. The
zero projected row count is not an observed empty array or evidence that the
account lacks Premium. No authority rows, Boolean values or expiry values were
available to verify. The precise remaining gap is the provider's subscription
response/caller semantics and positive entitlement evidence; the public DTO's
defaulting behavior cannot fill that gap. #193 remains open for broader broadcast
access modes.

All 20 provider-free observation/admission/cleanup guards passed, zero skips, in
2.258 seconds. Compilation, lint, release isolation, ordinary instrumentation
manifest, shared signing, scoped hooks/docs and independent source review passed.
Production code and the app APK were unchanged. All 20 saved-state files stayed
byte-identical across the test APK update, guards, actual observation and normal
launcher restoration. No purchase, guest fallback, credential renewal, invalid
authorization probe, playback or saved account/route/source mutation occurred.

After verifying the public caller's separate legacy and partner families, the
same unpublished fixture was extended with fixed categories for those fields.
All 22 provider-free guards then passed, zero skips, in 2.277 seconds; compilation,
lint, isolation, ordinary manifest, shared signing, scoped checks and independent
delta review passed again. One separately disclosed follow-up execution passed,
zero skips, in 5.236 seconds. It made exactly one GET through the same retained
grant and route, with matching identity, strict JSON and confirmed cleanup.
The legacy root field was an explicitly empty array. Modern root/nested and
partner root fields were absent, with no projected authority or expiry rows.
All 20 saved files remained byte-identical and the normal launcher was restored.
This is two bounded observations with one request each, not an observed lack of
Premium, token-context equivalence, paid or Chase acceptance. No different
endpoint, query, credential migration or renewal was introduced.

### Fresh Premium authority for ended broadcasts

The debug native exact-slot path additionally supports ordinary ended catch-up
when the connected account supplies fresh modern Premium authority. This is
separate from the shared account owner's cached identity validation. The native
session makes one additional unchanged profile GET on its already-created route;
it creates no second route or WireGuard backend and does not reconnect, renew,
query email or fall back to Guest.

The strict projection requires the requested `profile.userId`, root modern
`userSubscriptions`, a checked Unix-second expiry and an explicit Boolean true
`viewingAuthorities.isPremium`. Legacy/partner subscriptions, trial flags and
nested modern fields grant no authority. An absent modern field, an empty array
and a valid array without affirmative authority remain distinct. They can retain
independently verified free eligibility; malformed modern data or a rejected or
inconclusive profile refuses this account-backed preparation. No raw response or
subscription authority is persisted or exposed in generic UI.

A private receipt binds any positive Premium expiry to the acquired client,
captured instance/route owner, source proof, exact channel/slot, native run and
original playback deadline. Publication consumes the original 45-second readiness
budget, including blocking durable owner checks and actual HTTP cleanup. Memory
and clock admission plus worker-side durable checks continue through playback.
Expiry, clock rollback, Forget/replacement, owner loss or cancellation ends access;
no receipt moves into the shared cached account identity proof.

The app-owned initializer accepts that closed exact-slot authority only under
ACCOUNT. Ordinary admission remains verified Free or current Premium. Premium
uses the general timeshift window independently of an expired free window. Both
branches still require ended valid broadcast times, the timeshift flag, current
region, no pause/PPV/unsupported DRM, and unchanged exact source/manifest/license
binding. Chase and exact live occurrence remain outside this slice. The native
coordinator retains the existing route whenever the borrowed operation's first
HTTP/input/disconnect cleanup is unconfirmed; repeated cancellation cannot turn
uncertainty into confirmation.

The retained account observations above supplied no affirmative modern authority.
This implementation and synthetic positive fixtures do not establish actual
Premium playback, account plan status, Chase or exact live acceptance. No purchase
is implied, and #193 remains open for its unfinished modes and device evidence.

Local verification on 2026-10-13 passed 2,732 JVM cases with zero failures or
skips, 84 app-owned bootstrap cases, lint, release isolation, instrumentation
compilation, scoped hooks/docs and independent review. Both APKs matched the
shared debug signing certificate. Non-destructive updates on verified persistent
`tachiai-dev`, Android 16/API 36, x86-64, preserved all 20 saved-state files
byte-for-byte and returned to the normal launcher. This installation check used
no provider browser or playback operation and does not establish paid frames,
advancing playback, country or physical-device behavior.

### Exact ongoing Chase source

The next debug-native slice resolves an ongoing configured broadcast through its
own `chasePlayback`, using fresh raw slot identity and the existing profile GET.
It requires an active modern CHASE_PLAY feature authority whose raw ID matches
the selected slot's authority list. Numeric enum 2 and the verified exact string
form are accepted without coercion. Malformed feature data makes Chase unverified
independently of Premium; it cannot grant access or invalidate an otherwise valid
ordinary Premium projection. Legacy/partner/trial data supplies no Chase authority.

The private candidate list is bounded, immutable and request-local. Its earliest
active claim expiry conservatively bounds every possible matching authority.
An unrelated short claim can therefore end selected Chase playback early. That
lifetime activates only when the canonical app-owned source binding selects
Chase; an unused or expired candidate never shortens ordinary catch-up. Native
and app-owned runtime checks retain the original client/owner/source/run,
readiness/playback budgets, expiry and rollback protection. No new endpoint,
profile operation, route/backend, stored grant or credential renewal is added.

Fresh slot times select one source arm: an ended slot keeps ordinary playback;
an ongoing slot additionally requires the explicit Chase flag, matching feature
authority, both linear and timeshift region policies, no pause/PPV/unsupported
DRM, and the appropriate Premium/general or non-Premium/free window. The same
verified selector receives the exact advertised Chase playlist. The slot license
tuple, token binding and advertisement selection are unchanged. There is no
channel fallback, source reclassification or automatic transition at the end of
the broadcast. The Chase source remains that occurrence after its broadcast ends.

The user chose live-edge startup for ongoing broadcasts on 2026-10-13. This uses
the existing native viewer's once-only SDK live-default seek, rather than the
provider UI's beginning-position default. Before native READY is exposed, selected
Chase requires an actual SDK live timeline with a usable default-position command;
unsupported or static initial timelines refuse. Subsequent Back to live controls
are tracked separately in [#233](https://github.com/altendky/tachiai/issues/233).

These implementation and synthetic checks do not establish actual positive Chase
authority, a growing protected manifest, CDM exchange, rendered frames or movement.
Channel-backed exact live occurrence still needs verified media-timed PG coverage
and a pre-presentation transition boundary; schedule times or metadata arrival
alone do not establish it. #193 remains open for those modes and device evidence.

Local verification on 2026-10-13 passed 2,756 JVM cases with zero failures or
skips, 95 app-owned bootstrap cases, lint, release isolation, instrumentation
compilation, scoped hooks/docs and independent review. Both APKs matched the
shared debug signer. The ordinary instrumentation APK was unchanged from the
installed previous build. A non-destructive app update on verified persistent
`tachiai-dev`, Android 16/API 36, x86-64, preserved all 20 saved-state files
byte-for-byte and restored the normal launcher. This check used no provider
browser or playback operation; positive Chase playback remains unobserved.

### Fresh Chase eligibility observation

The separate test-only `abemaReadOnlyChaseEligibility` opt-in uses the production
`subscriptionProfile` operation once on the retained account's captured route.
The owned account wrapper forwards this operation through its existing request,
cancellation and route/HTTP cleanup boundary.
It retains the profile fixture's verified persistent AVD, ordinary app process,
explicit provider instance, read-only stores, original observation and cleanup
budgets, foreground, owner, grant, clock and unchanged-record gates. The original
profile-shape opt-in remains separate; both modes cannot run in one invocation.

Only a matching identity and complete strict response can publish the typed Chase
state, a claim count capped at 1,000 and `headroom70s`. The Boolean reports whether
the earliest active claim expiry has at least 70 seconds remaining at publication.
Expiry values and authority IDs remain private. Publication rechecks expiry and
the highest observed publication wall time; expiry or rollback permanently
withholds that observation. Actual input/disconnect, route/backend, worker and
lifecycle cleanup must all be confirmed before a summary escapes.

ABSENT, EMPTY, NO_AFFIRMATIVE_AUTHORITY and UNVERIFIED are distinct capability
observations. They do not establish that the account has no subscription or that
playback succeeded. A rejected, inconclusive or mismatched profile cannot publish
a negative eligibility tuple. Local unavailable authorization makes zero provider
requests. Its Chase-only summary adds a fixed local reason separating storage or
revision refusal, missing records, clock/acceptance checks, elapsed saved expiry,
decoder refusal and stored/decoded expiry mismatch. No token, identity or expiry
value is exported to diagnose that local refusal.
Even VERIFIED with sufficient headroom is not a retained playback grant:
an actual exact Chase run must obtain fresh matching rights, source and session
binding independently. This fixture does not select a broadcast, invoke playback,
reconnect, renew authorization or write saved account, route or source records.

On 2026-10-14, the first opted-in attempt reported local unavailable authorization
without any provider request. Its refined follow-up identified elapsed saved
expiry in 0.137 seconds; the record was byte-identical to the earlier profile
observation whose token-derived expiry had passed consistency checks. Normal
Connect reused the remembered account-management page through the saved Windscribe
route. Returning automatically completed native validation without another email
code, account switch or browser reset. Only the expected account record changed;
the other saved records stayed intact. This observed reconnect is not silent
credential renewal.

The first post-reconnect eligibility run failed after 2.922 seconds because the
owned account wrapper inherited the interface's inconclusive default instead of
forwarding the subscription operation. No profile HTTP request followed that
default. The added delegation uses the same ownership and cleanup path as the
existing identity operation, and a provider-free regression now exercises the
actual wrapper rather than only its inner HTTP transport.

After that correction, one actual saved-account observation passed, zero skips,
in 5.096 seconds on persistent `tachiai-dev`, Android 16/API 36, x86-64. It made
exactly one production profile GET through the captured Windscribe route, returned
HTTP 200 and matching identity, and passed strict JSON, original clock/owner/grant,
worker, lifecycle, actual HTTP/route cleanup and protected-record gates. The closed
result was **ABSENT**, zero active Chase claims and false 70-second headroom.
This response supplies no affirmative Chase authority; it does not establish that
the account has no subscription. No Schedule candidate or Chase playback request
followed it. Actual positive Chase playback and exact channel-backed live
occurrence remain unverified under #193.

All 31 provider-free observer/admission guards passed, zero skips, in 2.494 seconds.
Full Android verification covered 2,782 JVM cases with zero failures or skips,
lint, release isolation, instrumentation compilation and assemblies. Both APKs
matched the shared debug signer; scoped hooks/docs and independent source review
passed. Non-destructive app/test updates preserved authorization and configuration.
The existing diagnostic journal was the only other updated file, and its fixed
schema validated. All 20 files were byte-identical across the final real profile
experiment against its current baseline; ordinary instrumentation and the normal
launcher were restored. Exit country, phone/TV behavior and native playback were
not measured by this profile check.

### Initial ongoing-channel manifest observation

The debug-only opt-in investigation under #193 selects the first canonical ongoing
Schedule row with at least ten minutes remaining at the captured clock. It returns
one public channel/slot tuple, strips its test marker before the unchanged normal
decoder, and verifies literal displayed membership. This is a discovery lead;
it supplies no Chase authority, entitlement or current media-occurrence proof.

A separate source-only opt-in accepts that tuple, obtains one fresh configured
channel source through the saved account and route, and reads its initial DASH
manifest. It stops after the original protected initialization, source/HTTP close
and exact source-binding recheck, before creating any player or CDM or requesting
a license or media segment. It neither saves a selection nor enables live-slot
playback. Ordinary configured playback remains unchanged.

The bounded XML observer exports only closed counts and booleans: Periods,
explicit finite starts/durations/intervals, EventStream and InbandEventStream,
and literal comparisons of unqualified Period IDs or slotId attributes with the
private expected slot. Malformed input, declarations, duplicate attributes,
quota overflow, channel mismatch or repeated capture cannot export partial
positive counts. No XML, URI, KID, identifier, duration or provider timestamp is
released. Publication requires exactly one successful source-only completion,
no native playback event or member, unchanged protected records and route owner,
original monotonic/wall-clock bounds, sticky wall-clock rollback refusal and
confirmed helper, HTTP, route, worker and lifecycle cleanup.

Even a matching identifier and finite Period interval would be a candidate
contract requiring independent verification, not proof that the interval covers
only that selected broadcast or that a media transition can be stopped before
presentation. Exact channel-backed live acceptance remains pending under #193.
On 2026-10-14, one Schedule observation passed, zero skips, in 17.382 seconds on
persistent tachiai-dev, Android 16/API 36, x86-64, through the saved Windscribe
route and retained account. It found three eligible ongoing rows and released
one public tuple after owner, cleanup and protected-record gates. No selection
was saved, and no alternate candidate was tried.

The subsequent source-only attempt failed its success assertion after 18.358
seconds. Native admission reported ACCOUNT; source preparation stopped at the
closed helper state CONTENT, HTTP 200, UNKNOWN before READY or any native manifest
request. The exact failing content check is not identified. A successful HTTP
response and Schedule membership do not prove that the configured channel source
was accepted. No XML counts were published, no native player, CDM, license or
rendered frame was created, and no provider retry followed. Helper, HTTP, route,
worker and lifecycle cleanup were confirmed; route ownership and protected
records stayed unchanged. This establishes a safe refusal, not a successful
manifest observation or exact live playback.

All 18 provider-free XML, Schedule and actual source-resolver guards passed, zero
skips, in 1.614 seconds. The scoped Node fixture and CI hook passed; full Android
verification covered 2,782 JVM tests with zero failures or skips, lint, release
isolation, instrumentation compilation and assemblies. Both APKs matched the
shared debug signer. Independent source review passed after making retained
ACCOUNT admission mandatory before bootstrap for this mode and requiring
already-present owner-store locks before read-only owner access. Exit country,
phone/TV behavior and protected playback were not measured.
The temporary source test manifest was restored byte-identically, the ordinary
shared-signed test APK reinstalled and the normal launcher restored. All 20 saved
files matched the same private baseline across app/test updates, synthetic guards,
both actual attempts and final restoration.

### Channel preparation stage diagnostic

A separate account-backed source-only fixture now opts into eight closed channel
stages at the existing request and guard boundaries: CHANNEL_REQUEST,
CHANNEL_RESPONSE, CHANNEL_ENVELOPE_REFUSED, CHANNEL_SELECTION_REFUSED,
CHANNEL_UNAVAILABLE, CHANNEL_DRM_REFUSED, CHANNEL_PLAYLIST_REFUSED and
CHANNEL_ALTERNATE_REFUSED. Request and response distinguish the channel fetch
from earlier token traffic. The earlier CONTENT / HTTP 200 snapshot can precede
the channel fetch, so it does not establish a successful channel response.
The option defaults off; request count, guard order, predicates, provider source
selection and ordinary manifest/playback acceptance remain unchanged.

The recorder freezes the first parsed failure snapshot. A malformed callback
permanently withholds any prefix. The separate fixture may report a safe refusal
only after retained ACCOUNT admission, capture within the original observation
deadline, unchanged protected records and route owner, sticky clock checks and
confirmed complete cleanup. It retains the source-only stop before native
player/CDM/license/segments. Diagnostic completion means an observation was
released; it supplies no manifest, entitlement, media-occurrence or playback
acceptance. The existing manifest-only fixture still requires an actual initial
manifest, and the ordinary native fixtures still require frame and movement.
No raw response, URL, provider field, identifier or exception is exported.

The dedicated instrumentation gate is
`tachiaiReadOnlyAbemaChannelPreparation=true`, with
`tachiaiRequireSavedAbemaAccount=true` and one canonical public Schedule tuple
in `tachiaiAbemaManifestSlotIdentity`. It is exclusive with the existing actual
playback and manifest-only gates and skips before WebView APIs by default.

On 2026-10-14, the fresh Schedule attempt failed its response checks after
27.562 seconds, with no released candidate or account/preparation refusal.
No Schedule retry followed. A separate diagnostic reproduced channel preparation
using the same previously returned public `abema-anime` channel and tuple from
the earlier observation; the tuple was not asserted to be currently airing.
The diagnostic passed its observation gates in 12.156 seconds, zero skips:
ACCOUNT, CHANNEL_UNAVAILABLE, HTTP 200, UNKNOWN, 46 modules, last module 42204.
The underlying outcome remained PLAYBACK_REFUSED. This identifies the original
`viewingOpened !== true` check, without distinguishing an absent, false, null or
other value. No raw field was exported and no manifest/native playback began.
The last-module marker is not an exception stack: successful nested loads can
leave that enum module recorded during later channel requests and guards.

Both actual attempts used persistent tachiai-dev, Android 16/API 36, x86-64,
WebView 133.0.6943.137 and the saved Windscribe route. All 20 private files stayed
unchanged across updates, provider-free guards and both actual attempts. The
channel attempt confirmed ACCOUNT, unchanged owner and records, FINISHED cleanup,
no pending/failed session cleanup, confirmed teardown and no busy WebView route.
Exit country, actual XML, entitlement, exact live-slot coverage, native playback
and physical-device behavior were not established. No provider retry, alternative
candidate, selection write or source fallback followed.

Verification passed 103 Node cases, 2,788 JVM tests in 304 reports with zero
failures, errors or skips, lint, release isolation, instrumentation compilation,
assemblies, shared certificate checks, scoped hooks/docs and independent review.
An initial incorrect device selector ran only 14 provider-free tests; the corrected
verified suite passed all 18, zero skips, in 1.680 seconds. The ordinary test
manifest was restored byte-identically before rebuilding and reinstalling the
shared-signed ordinary test APK and returning to the normal launcher. #193 remains
open; these diagnostics neither enable nor establish exact live playback.

## Remaining gates and issue status

The original research established public identity distinctions, retention
semantics and documented account features. Subsequent dated observations establish
bounded public discovery and episode playback, one saved native email account
with fresh validation after process restart, one My List entry and two History
entries. These account observations used the owning Windscribe route on
tachiai-dev and preserved protected records and configured items. They establish
bounded access, not complete catalog/account or playback acceptance.
The stated initial criteria for #100 and #101 were independently audited and
the issues closed on 2026-10-13. The wider behavior below remains unverified;
those closures do not establish full provider coverage:

- Broader structured discovery and collection coverage, real continuation
  consumption and every supported My List/History resource kind. Genres and
  complete provider coverage are not implemented; a missing documented API
  is neither proof of permanent lack of support nor a new permission gate.
- Between-basho channel availability, annual title rollover, exact
  slot-to-catch-up mapping and any recurring resolver.
- Whether channel ordering is account-owned/synchronized and retrievable.
- Account switching, expiry/reconnection, renewal, revocation/Forget and logout,
  plus multiple independent browser/native account instances. One successful
  email account and process restart do not establish those lifecycle cases.
- Actual account and collection acceptance through other routes, including the
  implemented System approval bridge, and physical-device/TV behavior. Installation
  or provider-free route fixtures do not establish that acceptance.
- Real eligible free-slot, live-slot, Chase, paid/account-required and sustained
  playback. Metadata/list membership and connected state do not establish
  entitlement, exact current-occurrence binding or license acceptance.
- Further required discovery/account origins and endpoint-policy changes verified
  and documented under the current general integration approval.

The dated email, My List and [History evidence](provider-history-access.md#intended-account-history-acceptance--2026-10-12)
satisfy their respective bounded account/endpoint observations; each retains its
separate ownership, route, foreground, expiry and cleanup admission. Connection
alone, public import or fixture success does not establish an unobserved collection
or playback capability. The stated initial History criteria in
[issue #102](https://github.com/altendky/tachiai/issues/102) were also audited and
closed; wider History coverage remains unverified. Public discovery/import
and guest playback remain independent of those account-backed gates; outstanding
work must remain tracked.
