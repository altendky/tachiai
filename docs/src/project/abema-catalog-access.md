# ABEMA catalog and account access

The [guest-only preparation experiment](abema-guest-bootstrap.md) isolates the
fresh-session prerequisite for account linking. It does not verify an existing
account, profile/client identity or account-backed collection access.

This is the bounded documentation research for
[issue #100](https://github.com/altendky/tachiai/issues/100), reviewed on
2026-10-09. Published ABEMA features and public content identities provide a
selection model, but do not establish a supported external catalog or account
integration. The remaining account-access gates below keep #100 open.

## Current authorization — 2026-10-10

The user now authorizes general ABEMA catalog, existing-account/session,
collection and selected-resource playback integration, including undocumented
endpoints wherever a needed operation has no documented API. The old
supported-only and fixed-content permission limits are superseded by the
[current integration scope](security-and-privacy.md#general-provider-integration-approval--2026-10-10)
and the updated #100/#101/#103 bodies. Lack of official API documentation is an
evidence classification, not another permission gate. Technical access/session,
identity, route and entitlement verification remains outstanding.

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
general catalog integration. Its reviewed guest/metadata/playback requests
did not themselves grant broader catalog, authentication or licensing scope; the
subsequent approval above provides the current integration authority. See the
[security boundaries](security-and-privacy.md) and
[media-origin approvals](media-origin-approvals.md).

## Features and access capabilities

| Feature | Official ABEMA behavior | Tachiai implementation boundary |
| --- | --- | --- |
| Public discovery | Search, genres and timetable on provider pages | Channels browsing and exact lookup use the bounded public channel endpoint below. A separate public Schedule snapshot projects exact broadcasts; one saved-route guest response passed the bounded device observation below. [Provider-wide Search and exact Season children](abema-search.md) have bounded real metadata acceptance: two Search observations and one exact Season page passed. Genres and complete provider coverage are not implemented. |
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
has UNKNOWN availability. The adapter does not resolve new channels to playback.
The existing exact historical samples retain their implemented paths until the
general selected-resource work in #101 is verified.

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

[Issue #103](https://github.com/altendky/tachiai/issues/103) can develop common
connection-state/controller fixtures and the verified account integration,
including authorized undocumented endpoints where necessary.
Real account connection requires verification of Android behavior, instance
ownership, selected route, session persistence and a non-invasive connection
check. Opening ABEMA externally does not demonstrate that a Tachiai instance is
connected. Without that verification, report account access as not verified.

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

Both observations used Android 16/API 36, x86-64, WebView 133.0.6943.137,
the instance's captured saved route, unknown exit country and a fresh guest
without the existing ABEMA account. They establish compatibility with these
actual responses within local parsing bounds. Exact raw byte sizes, full
provider date horizon, changing-schema compatibility and complete All coverage
remain unverified; the broader #100/#101 issues remain open.

### Existing-account email connection implementation

The debug Providers screen offers an instance-owned **ABEMA account** screen.
**Connect ABEMA by email** opens ABEMA's account page in the isolated routed
browser. The user chooses **Switch account → Email address**, enters the email
address and emailed six-character code on provider-controlled pages, then opens
Account management and returns to Tachiai. **Finish email connection** performs
native validation and saves the grant. Tachiai has no email/password/code form.
The other authentication methods are deferred to the human-led
[issue #196](https://github.com/altendky/tachiai/issues/196), without the agent label.

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

A saved connection is currently required for the browser's destination-enforcing
CONNECT bridge. System-network browser access still needs an equivalent direct
bridge; native account requests retain their existing System-network option.
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

Each selected feed creates its own fresh guest, helper, media token and native
CDM. Its owned policy admits only the exact modern episode metadata URL, validates
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
items remain configured while playback returns a safe refusal. This guest slice
does not add My List, history, account adoption or renewal. Channels, exact slots,
series and broader discovery remain [#101](https://github.com/altendky/tachiai/issues/101).
See [the native boundary](native-access-experiments.md#exact-configured-episodes--2026-10-11)
for static evidence and bounded device observations. On 2026-10-11, persistent
`tachiai-dev` rendered the distinct ABEMA Morning episode `89-70_s111_p4016`
and advanced its native position in an explicitly selected 13.051-second run.
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

A fresh owning guest retrieves one bounded, duplicate-free channel snapshot.
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

## Remaining gates and issue status

The research establishes public identity distinctions, retention semantics,
documented account features and a bounded import direction. It does not settle
the implementation-ready account-access acceptance of #100. Keep #100 open for:

- A verified structured catalog and existing-account My List access design,
  or an explicit resolution of those capability limits. A missing API search
  result is not sufficient proof of permanent lack of support.
- Between-basho channel availability, annual title rollover, exact
  slot-to-catch-up mapping and any recurring resolver.
- Whether channel ordering is account-owned/synchronized and retrievable.
- Verified Android account connection, session/account isolation, route
  ownership, persistence, switching, expiry/revocation and logout behavior.
- Required discovery/account origins and endpoint-policy changes verified and
  documented under the current general integration approval.

Account-backed My List in #101 requires both #103's working instance-owned
connection and verified collection access. A connection alone, public import
or fixture success does not satisfy that acceptance. Account-backed history in
[issue #102](https://github.com/altendky/tachiai/issues/102) additionally requires
its own verified retrieval findings. Public import and fixture/UI work remain
independent of these account-backed gates; outstanding work must remain tracked.
