# Provider watch-history access

This is partial research for
[issue #102](https://github.com/altendky/tachiai/issues/102), initially reviewed on
2026-10-09. History can help discover previously viewed channels and on-demand
items without making them favorites. A bounded undocumented ABEMA read-only
adapter is now implemented as described below, with bounded intended-account
retrieval observed on 2026-10-12. An optional bounded Twitch Continue Watching
adapter is implemented below, with a bounded maintained-account response observed
on 2026-10-12. Broader Smart TV compatibility remains unverified. No
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

History and My List use separate account bindings and separate access checks.
History is available only after a fresh challenge-free guest/profile context,
matching guest and intended-account profile identities, and a strictly parsed
History page requested with the saved account grant, with confirmed cleanup.
Ordinary collection access sends no deliberately invalid bearer; rejection probes
belong to explicitly selected diagnostics. Actual provider refusals still prevent
access. Profile or My List success does not establish History access. Collection
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
owner's intentional grant write. The real account check below establishes one
accepted page; source fixtures alone cannot establish account access, history
coverage or retention.

### Intended-account History acceptance — 2026-10-12

After the delivered email connection and separate My List acceptance, one
explicitly opted-in read-only History observation on persistent tachiai-dev
returned `HISTORY_PAGE_ACCEPTED`, two entries and an available continuation.
The continuation was not followed. One executed instrumentation case passed in
19.344 seconds with zero skips. Context: Android16/API36/x86-64,
WebView133.0.6943.137, the owner's protected native email-account grant and the
configured Windscribe WireGuard route. The fresh guest/profile context and
matching intended-account profile preceded the strictly parsed History page.
No account identifier, entry title, resource identity, progress, timestamp or
continuation value was exported or retained in this observation.

The fixture browsed History directly, without My List, public capability sweeps,
account sign-in, rejection probes, playback or configured-item mutation. Its
guarded setup and nested cleanup attempt callback removal, foreground-scenario
closure and protected-record/owner verification even after an earlier failure.
Both internal encrypted-record comparisons and the external no-backup/preferences
baseline were unchanged afterward; the ordinary launcher was restored. Saved
authorization, routes and configured items were preserved.

Seventy-two targeted projection, transport, collection and binding JVM cases
passed, including zero My List access checks during direct History browsing.
Android instrumentation compilation, test APK assembly, lint and release
isolation passed, and app/test certificates matched the shared debug identity.
This is bounded intended-account endpoint acceptance. Complete history coverage,
pagination, every resource kind, renewal, entitlement, playback and Twitch
retrieval remain separate checks. The physical phone was not used.

### Bounded continuation fixture — 2026-10-12

A separate `tachiaiReadOnlyAbemaHistoryContinuation=true` opt-in extends
`AbemaHistoryDeviceTest` without changing the production adapter. It requests
History directly, requires a conservatively projected first page and passes its
opaque `nextCursor` unchanged to exactly one second History browse on the same
catalog. The observation opt-ins are mutually exclusive. It
does not probe My List, invoke a capability sweep, request a third page, retry,
start sign-in, play content or configure a source. A valid empty terminal second
page is acceptable; cross-page overlap is not rejected without an established
provider snapshot contract. Differing public handles do not prove provider
cursor progression, because production replaces provider cursors with fresh
opaque handles.

Worker checks refuse incomplete AtomicFile writes, backups and the intended
account's pending-Forget marker before protected reads and around each browse.
Required lock files must already exist as regular, non-symlink files; the fixture
does not create them. These checks assume an idle device with no external writer
and do not atomically exclude a concurrent account/settings change. The factory
activity gate remains a cheap foreground/deadline check, including its main-thread
callbacks. A shared fifty-second observation deadline reserves twenty seconds
inside the original seventy-second operation/cleanup bound; cleanup and
preservation must also finish within that twenty-second reserve. These are
acceptance deadlines, not a hard wallclock cancellation guarantee for blocked
local lifecycle or IPC work. Final preservation checks run independently of
that revoked foreground gate.

Accepted pages already require the production binding's confirmed request
cleanup. Facade-close return is reported separately and is not treated as proof
of request cleanup by itself. The final accepted outcome additionally requires
both pages' strict projection, bounded timing, no account cleanup poison,
facade closure and unchanged encrypted records and route ownership. Only closed
outcomes, counts and continuation/receipt booleans are logged; private history
contents and cursor values remain transient.

### First continuation attempt — 2026-10-12

One opted-in continuation observation executed one instrumentation case with
zero skips and failed in 2.827 seconds. The first History browse returned
`ACCESS_REQUIRED`; no second browse or continuation consumption ran. The closed
observation reported unchanged encrypted records and owner, returned facade,
callback and foreground cleanup, and no account cleanup poison. Its incomplete
observation flag was false; this is not evidence that the fifty-second deadline
expired. The post-browse foreground, deadline and owner checks passed before the
first-page refusal.

Production maps every unavailable History binding to `ACCESS_REQUIRED`, including
local grant refusal, fresh-bootstrap failure and profile/endpoint refusal. This
observation therefore establishes neither provider denial nor a specific cause.
Provider request execution and the exact access-failure stage were not established.
Context: persistent tachiai-dev, Android16/API36/x86-64, the same saved native
email account and Windscribe route. The independent twenty-file no-backup and
preferences baseline was byte-identical after test-APK installation and afterward.
No account write, sign-in, Add, playback, route change or automatic retry occurred.
Actual continuation acceptance remains outstanding under #102.

A separate `tachiaiReadOnlyAbemaLocalAccount=true` diagnostic narrows local
grant admission without contacting ABEMA. It uses the production protected grant
store and account session to load, inspect the closed status, attempt local
candidate admission and inspect status again. It constructs no Activity,
catalog, bootstrap or transport, performs no provider validation, and pauses
the temporary session during cleanup. It shares the device/process, storage,
owner, budget and preservation guards above. A false candidate can be a completed
local observation; `LOCAL_ACCOUNT_OBSERVED` does not establish account connection
or History access. Only closed statuses and booleans are logged.

One separately opted-in local observation then passed one instrumentation case
with zero skips in 0.161 seconds. Loading reported `UNVERIFIED`; local candidate
admission returned false and status became `RECONNECT_REQUIRED`. Session pause,
owner/record preservation, no account cleanup poison and all acceptance budgets
passed. No Activity, catalog, bootstrap, transport or provider request was
constructed. The independent twenty-file baseline remained byte-identical;
the ordinary launcher was restored. This establishes unavailable local native
grant admission at that observation, without determining a precise cause such
as expiry or proving provider rejection. At that point a renewed connection
under #103 was needed before another intended-account continuation check. No
account grant was replaced or refreshed by either observation.

All seventy-two targeted History projection, collection, binding and HTTP
transport JVM cases passed with zero failures, errors or skips. Instrumentation
compilation, test-APK assembly, lint, release isolation, scoped hooks and the
documentation build passed; the test certificate matched the shared debug
identity. Independent reviews found no blocker. Production sources and the
installed app APK were unchanged; only the normal-process test APK was updated.
The physical phone was not used. The failed continuation attempt is preserved
above; the successful local diagnostic does not satisfy continuation acceptance.

### Fresh handoff and second-page refusal — 2026-10-12

The owner initiated Connect again and reported that ABEMA opened an already
authenticated screen. After return, the native account screen had a ready
**Finish email connection** action. Completing that pending handoff passed
profile and email-registration validation and reported native `CONNECTED`.
Exactly one of twenty baseline files changed: the intended instance's protected
native grant. No files were added or removed; the other nineteen were unchanged.
This intentional account update is separate from the read-only observations.

The native expiry is taken from the provider token's `exp` claim, with identity,
clock and saved-record consistency checks. There is no twelve-hour local account
retention limit. Verified automatic native-grant renewal is not implemented;
a retained browser login and an unusable native grant can coexist. These
observations did not measure the previous token's exact lifetime or establish
why its local candidate was rejected.

Against a new twenty-file baseline captured after that handoff, one fresh
continuation case executed with zero skips and failed in 20.68 seconds. The first
History page returned two entries and a continuation; the second browse returned
`TEMPORARY`. It did not establish accepted continuation metadata. Internal
encrypted-record and owner checks passed, all facade/callback/foreground cleanup
returned, and account cleanup was not poisoned. The incomplete observation flag
again does not establish a deadline expiry.

The external comparison found one changed existing file, the bounded failure
journal; all encrypted records and the other nineteen files were unchanged,
with no additions or removals. The strict journal collector reported
`CATALOG_LOAD` without a detailed cause. The journal was preserved, and the
ordinary launcher was restored. A further account reconnect is not indicated
by this second-page refusal; its HTTP/response projection cause still needs
bounded diagnosis.

### Closed response-shape diagnosis — 2026-10-12

A distinct `tachiaiReadOnlyAbemaHistoryShape=true` test-only mode composes the
production History binding, bootstrap, profile and routed collection transports.
It forwards the exact bytes the production parser consumes, without reading
ahead, and classifies only a complete, strictly checked UTF-8 JSON body within
the existing 512 KiB bound. Output contains closed HTTP/failure categories,
field types and array counts; response values, identities, titles, cursors,
headers and URLs are never logged or persisted. Non-200 bodies remain unread.
Actual resource-close receipts, owner/record preservation and observation and
cleanup budgets must pass before shape output is admitted. Cleanup uncertainty
retains owners and poisons account admission. A parser refusal still fails the
History acceptance case. Guest bootstrap retains its normal bounded failure
journal behavior.

Three provider-free instrumentation cases passed in 0.165 seconds, covering
unchanged bytes, complete versus partial reads, malformed UTF-8 and duplicate
JSON, bounded shape classification, unread refusal bodies and sticky cleanup
failure suppression. Test-only installation and these checks preserved all
twenty baseline files.

One subsequent shape-mode case executed with zero skips and failed in 30.938
seconds. Both collection responses were HTTP 200 with complete JSON. The first
had two History references, one timeshift and one video, and a nonempty next
cursor. The second had `histories: null`, a dataset object whose three known
arrays were `null`, and a paging object with no next field. The production
parser classified that second page as `INVALID_RESPONSE`, explaining the
catalog's `TEMPORARY` refusal. Shape cleanup, budgets, encrypted records and
route ownership passed; all twenty external baseline files were unchanged, and
the ordinary launcher was restored.

The retained coherent first-party module 95697 normalizes null History and
dataset arrays to empty arrays. This establishes a bounded parser compatibility
defect, rather than an authentication or HTTP refusal. Malformed non-array
values, missing History fields, unknown references, broken joins and existing
bounds remain separate refusal cases.

The production projection now treats explicitly null History and known dataset
arrays as empty arrays. It still requires a present History field and validates
every supplied dataset entry, including on empty pages; it has no early empty
return that could skip duplicate identities, malformed fields or bounds. A
nonempty reference still requires its exact matching dataset entry. Paging and
cursor validation are unchanged. This does not adopt the first-party model's
broader coercion of arbitrary malformed values.

### Normal-factory continuation acceptance after the fix — 2026-10-12

Seventy-six targeted History projection, collection, binding and HTTP JVM cases
passed with zero failures, errors or skips, including four new null-array
regression cases. Instrumentation compilation, lint, release isolation, scoped
hooks/docs and independent reviews passed. Both rebuilt APKs matched the shared
debug signing certificate. The Kotlin compiler emitted non-exhaustive-when
warnings in this parser; lint and compilation completed successfully. Native
libraries were unchanged.

After a data-preserving app update, one real normal-production-factory
continuation case executed with zero skips and passed in 26.145 seconds:
`HISTORY_CONTINUATION_ACCEPTED`, two first-page entries and an empty terminal
second page. Both browse calls, conservative projection, request cleanup,
facade/callback/foreground cleanup, budgets, owner and encrypted-record checks
passed without account cleanup poison. All twenty external baseline files were
byte-identical after installation and after the check; the ordinary launcher
was restored. An earlier command typo selected zero tests and made no provider
request; it is not counted as acceptance or as a real provider attempt.

Context remains persistent tachiai-dev, Android16/API36/x86-64,
WebView133.0.6943.137, the freshly validated saved native email-account grant and
the configured Windscribe WireGuard route. The physical phone was not used.
This verifies one intended-account continuation to a terminal page, not complete
history coverage, every resource kind, retention, renewal, entitlement or
playback. #102 and #103 remain open for their broader scope.

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
Raw schema types and nullability are not an established provider contract. The
later bounded positive page passed Tachiai's local restrictions, which
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
That public-source research made no real-account GraphQL request or device
observation. The later bounded acceptance below establishes a narrower positive
result. A provider refusal
or incompatible negative-proof convention may keep this optional collection
unavailable while Following works normally.

`TwitchContinueWatchingDeviceTest` skips before private access by default. Its
explicit opt-in is `tachiaiReadOnlyTwitchContinueWatching=true` plus a
`twitchProviderInstance` and verified tachiai-dev AVD in the ordinary app process.
It requires an already READY grant with more than ninety seconds of retained
validity, maintains foreground/route ownership, forbids protected writes and
consent/refresh/reconnect, and refuses validation that would require expiry
tightening. Existing regular grant and lock files are required; observed unfinished
encrypted-file writes, pending/failed grant writes and poisoned admission are
refused before reads. This assumes an idle device rather than excluding an
external writer racing filesystem checks. Direct owned transports avoid the
ordinary binding's retirement cleanup. Setup and closure retain every owner until
cleanup is confirmed, independently verify protected digests and captured owner,
and wipe partial digest maps. The exact bounded returned projection must match
the final browse result before acceptance. It exercises the same production
collection/session/route classes, but these extra fixture guards are not ordinary
connection behavior.

### Maintained-account Continue Watching acceptance — 2026-10-12

Following the owner's [normal QR connection and saved-grant validation](twitch-catalog-authorization.md#maintained-account-acceptance--2026-10-12)
and the separate [first Following page check](twitch-connected-catalog.md#bounded-following-observation--2026-10-12),
one explicitly opted-in Continue Watching check executed and passed in 5.816
seconds with zero skips. Context: persistent tachiai-dev, emulator-5580, Android
16/API 36, x86_64, ordinary foreground app process, maintained account and captured
**System** route. No browser or media player was opened; region was not separately
measured.

The production session performed one official positive validation GET with HTTP
200. The existing collection adapter then performed its two fixed GraphQL POSTs:
the non-account invalid sentinel received HTTP 401, and the retained account
bearer received HTTP 200 with strictly matching `currentUser` and a parsed page.
This was optional collection-access verification, separate from completed account
setup; it did not reconnect, reject the real account grant or request new consent.

The adapter consumed that positive page's one-use seed and returned two unique
exact VIDEO resources with UNKNOWN availability and no continuation. Final
entries matched the provider-returned projection exactly. The check required
both the production thirty-second proof budget and its seventy-second total
operation/cleanup budget, current account generation/route ownership and definite
cleanup for both collection owners. All 20 files under `no_backup` and
`shared_prefs` had identical before/after digests, and the normal launcher screen
was restored.

No capability sweep, Following/Helix request, refresh, retry, alternative
identity/cookie/route, pagination, configured Add/removal, provider-history
mutation or playback was attempted. Diagnostics contain only closed categories,
statuses, counts and booleans after preservation and cleanup checks. Account and
video identifiers, titles, progress, viewing times, bodies, credentials and record
digests were not included in diagnostics or this record. A decoder exception
would leave its response status unknown rather than infer HTTP 200.

Sixty-eight focused JVM cases passed, including collection proof/projection,
HTTP/owned cleanup, configured selection and catalog session coverage.
Instrumentation compilation and APK assembly, lint/release isolation and both
shared debug-certificate checks passed; the production APK was unchanged. The
fixture received independent review before the real request.

This establishes one bounded recent unfinished-viewing collection on one
emulator, not comprehensive Twitch History, full shelf parity, all schema shapes,
empty-list behavior, Add/selected-video playback, refresh/revocation, imported
routes or physical-device/TV acceptance. Those broader requirements remain under
issues #102 and #99.

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
above; its access depends on its own endpoint response. The existing
shared controller preflight skips unavailable collection browsing, clears the
discovery surface and retains configured items. Users can return to ordinary
All/Following/My List discovery with its existing access rules. Generic unverified
copy now states the uncertainty rather than asking for connection as a remedy.

Production adapter, controller and Compose fixtures cover reporting, request
suppression, retained configured items and return to ordinary discovery. This is
implemented capability reporting, not real history retrieval or account acceptance.
That earlier #130 change added no endpoint, scope, export import,
viewed-time/progress field or history mutation. The bounded ABEMA endpoint
adapter now has the intended-account observation recorded above; #102 remains
open for broader coverage and the remaining Twitch retrieval and privacy
requirements.

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
