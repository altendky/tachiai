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
| Public discovery | Search, genres and timetable on provider pages | Channels browsing, local name/ID search and exact lookup use the bounded public channel endpoint below. Native timetable, video discovery and provider-supported external APIs remain unverified. |
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
instance's explicit saved route. Search filters channel names/IDs locally; pages
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

### Existing-account connection implementation

The debug Providers screen now offers an instance-owned ABEMA account screen.
Connect is enabled once the controller is ready and idle, without entering an
account ID, email or password and without a confirmation checkbox. ABEMA handles
sign-in, account selection and approval through its own browser or app. The
internal provider identity comes from the approved token response rather than
the user's email sign-in identifier. Provider documentation describes
[email account sharing and mobile-app QR approval](https://help.abema.tv/hc/ja/articles/28570588900377);
it does not establish that the device-approval URL itself offers email login.
The coherent public account-method selector independently offers provider-owned
email sign-in followed by an emailed code, alongside separate app-account
sharing. The public
[device-switch chunk](https://abema.tv/assets/compat/914d635da0a8f46f.7663.bundle.js)
uses legacy CLIENT_SWITCH device-code operations, distinct from modern OAuth.
Their codes and fields are not interchangeable. Static login options do not
establish approval of Tachiai's challenge; entry remains on provider pages.
The app
prepares a fresh guest and short-lived challenge in a separate private process,
then returns the private challenge through same-UID IPC only after confirmed
runtime and route cleanup. Approval opens in a separate in-app ABEMA browser
process/profile through this instance's saved route. It uses provider-controlled
pages, normal TLS, and its own persistent ABEMA browser login; Chrome cookies
are not read or transferred. The browser has no native JavaScript bridge,
injected scripts, file/content access, downloads or external navigation fallback.
It admits the reviewed ABEMA account origins and a provider region-check origin.
A narrow native adapter owns the exact GET without following redirects; initial
browser admission refuses CDN media/navigation. The shared CONNECT host policy
cannot restrict every same-CDN path reached through an account-resource redirect,
which Android does not re-intercept; no such redirect was observed. The previous
resource policy blocked that check with HTTP403, which the first-party page
interprets as region denial; the owner's in-app screenshot reports that refusal.
The correction lets ABEMA perform its own check without rewriting eligibility.
Device geolocation is disabled and location prompts are refused; the network
location is the configured route exit. The returned native account grant remains
separate and per-instance.
This browser currently requires a saved connection: its authenticated CONNECT
bridge enforces destinations even across Chromium redirects. System-network
browser approval needs an equivalent direct bridge; WebView interception alone
does not check every redirected resource. Existing System-network native account
requests are unchanged. A missing saved route is explained before page loading.

Before offering approval, the app must prove that the native profile operation
accepts the matching fresh guest bearer and refuses a deliberately invalid
bearer. A successful public profile lookup alone is insufficient. Polling pauses
while the app is away and retains the original challenge expiry; foreground
polling additionally has a five-minute limit. Returning from the browser is
not proof of approval: Continue still verifies the token and account profile.
Browser return requires confirmed view, proxy and route cleanup; failure cancels
the native handoff. Original owner proof and expiry also gate browser loading
and resources. Only the observed flat
AUTHORIZATION_PENDING response permits bounded continued polling. Unknown
responses or uncertain cleanup stop the attempt.

A returned grant must have exact agreement among its provider user ID, JWT
subject and fresh authenticated profile, and must differ from the fresh guest.
The account explicitly approved on ABEMA is the selected account. JWT decoding alone
is not cryptographic validation. A positive finite future expiry is required;
the Twitch-specific seven-day unknown-expiry policy does not apply. Native
cookie-independent renewal is unverified and therefore requires reconnection
rather than a cookie or refresh fallback.

Accepted grants use a separate per-instance AndroidKeyStore AES-GCM binding and
no-backup atomic file. Startup/resume requires fresh validation; foreground,
route, expiry, shared-store revision and Forget checks revoke stale leases.
Replacement failure conditionally restores the exact prior protected record,
never a newer replacement or Forget state. Forget first revokes admission, then
clears local authorization while retaining configured choices and routes.

HTTP, stream and backend cleanup use bounded exactly-once receipts. Fatal,
reentrant, timed-out or unconfirmed cleanup cannot publish a connection or
release a still-owned backend. Original resources stay retained and that
instance is blocked until process restart. Diagnostics contain only closed
failure categories, without provider errors, bearer values, account IDs or
challenge destinations.

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

Actual intended-account acceptance remains outstanding. A connected account would not by itself establish
My List, history, entitlement or account-backed playback. Those capabilities
need separate endpoint and intended-account evidence; #103 remains open until
its real account criteria are met.

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

### Account-backed My List adapter

The debug catalog composes a read-only My List adapter around public Channels
and inside local public-link import. Existing configured choices remain
Tachiai-owned: browsing never automatically adds an item, mutates ABEMA's list
or deletes a configured choice when the provider omits it. The separate
[History adapter](provider-history-access.md#abema-read-only-history-adapter--2026-10-11)
uses its own collection binding and endpoint proof under #102; real
intended-account acceptance remains unverified for both collections.

Account validation runs on workers and uses the core session directly. Missing
or corrupt protected authorization starts no provider request. A valid saved
candidate needs a fresh challenge-free guest/profile context, matching positive
and invalid-bearer profile checks and fresh intended-account identity agreement.
My List additionally must reject an invalid bearer and return a strictly parsed
page with that intended grant before reporting access as available. All proof
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

Provider-free fixtures and source review are implementation evidence. Actual
intended-account My List retrieval remains an acceptance gate, distinct from
profile authorization proof. Broader discovery and exact slot/series resolution
remain outstanding in #101; this adapter does not choose a current channel or
an arbitrary episode on behalf of a collection.

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

Issue [#178](https://github.com/altendky/tachiai/issues/178) adds metadata
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

Actual guest compatibility, response limits and optional division behavior
remain separate runtime gates. Network-blocked and explicitly opted-in
saved-route checks must preserve protected records and restore ordinary
test targeting. No account Connect, timetable/search, recurrence or provider
history operation is part of this metadata slice.

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
