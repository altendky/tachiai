# Twitch catalog access

## Status and recommendation

Issue [#98](https://github.com/altendky/tachiai/issues/98) investigates supported
metadata and account-collection access for the configurable source list.
The official documentation was reviewed on 2026-10-09.
Implement an own-client catalog session using Twitch's documented public-client
device flow. Keep it separate from the bounded playback experiments described
in [security and privacy](security-and-privacy.md).

Tachiai already has an own PUBLIC application registration and zero-scope
device-flow evidence in the [timing capability matrix](timing-capability-matrix.md#own-client-device-authorization-probe--2026-10-05).
That evidence does not verify scoped catalog authorization. This investigation
made no account requests, retrieved no credentials and performed no device
experiment. Scoped consent, Helix responses, refresh and routed catalog access
remain unobserved. The current developer-console configuration was not inspected.

The separate [debug catalog authorization prototype](twitch-catalog-authorization.md)
implements the proposed lifecycle with synthetic fixtures. This does not establish
actual scoped consent. The [connected catalog prototype](twitch-connected-catalog.md)
adds supported Helix discovery through that separate session, with synthetic
verification and the actual account/route observations still outstanding.

## Documented metadata and identities

| Operation | Authorization | Identity or limit |
| --- | --- | --- |
| [Search Channels](https://dev.twitch.tv/docs/api/reference/#search-channels) | App or user token | Offline included; only channels active within six months |
| [Get Users](https://dev.twitch.tv/docs/api/reference/#get-users) | App or user token | Exact login/ID lookup; broadcaster ID |
| [Following](https://dev.twitch.tv/docs/api/reference/#get-followed-channels) | Matching user token; `user:read:follows` | Followed broadcasters, including offline |
| [Live status](https://dev.twitch.tv/docs/api/reference/#get-streams) | App or user token | Broadcast ID distinct from broadcaster |
| [Published videos](https://dev.twitch.tv/docs/api/reference/#get-videos) | App or user token | Exact video ID; broadcaster-scoped discovery |
| [Schedule](https://dev.twitch.tv/docs/api/reference/#get-channel-stream-schedule) | App or user token | Separate segments and dates |

Save a broadcaster for ongoing-channel intent and an exact video for a specific
published replay. Exact lookup supplements search for new or long-inactive
channels. Schedule metadata is not a video identity. No documented general
watch-history, watch-later, favorite-channel or followed-category retrieval
endpoint was found; do not advertise those collections as available.

Catalog metadata does not establish playback eligibility. Preserve configured
items when offline, deleted, expired, unfollowed or no longer visible to the
current account. Missing information updates availability rather than replacing
the user's saved identity. Unpublished occurrences must not acquire guessed
video IDs. Published videos include content that is not currently live; see
Twitch's [videos guide](https://dev.twitch.tv/docs/api/videos).

## Own-client authorization

Twitch's [public-client device flow](https://dev.twitch.tv/docs/authentication/getting-tokens-oauth/#device-code-grant-flow)
supports scoped user tokens and refresh without a client secret. Public clients
cannot use client credentials to obtain app tokens. Request `user:read:follows`
for a catalog connection offering Following; request no email or write
permission. Public metadata needs no additional catalog scope.

Keep activation provider-controlled in the external browser. Present explicit
catalog consent and cancellation. Do not capture the approval page or expose
codes in diagnostics. Login and Following access do not authorize native
playback experiments or prove subscription/ad behavior.

The existing device transport explicitly requests empty scopes, its parsers
reject nonempty grants, and its normalization omits refresh credentials. Add a
separate catalog authorization policy/session rather than weakening these
experimental validators. Reuse only protocol mechanics whose security contract
is unchanged. Own-client and provider/Smart TV playback grants remain distinct.
They may represent different users: a catalog account name must not relabel a
playback grant as the same account without verified identity evidence.

## Session ownership, persistence and routing

The proposed catalog session belongs to one provider-instance UUID and binds
the exact own client, validated user, authorized scope set and session revision.
Use a separate encrypted no-backup credential record, atomic replacement and
revision guards following existing storage conventions. No plaintext fallback,
export or migration from a playback slot. Access/refresh credentials stay out
of configured sources, recovery state, UI saved state and logs.

Twitch requires [validation](https://dev.twitch.tv/docs/authentication/validate-tokens/)
at startup and hourly while maintaining an OAuth session. Verify client, user,
scope and lifetime before exposing account access. Revoked/invalid authorization
ends that session; reconnect is explicit. A private playback error is not proof
that the independent catalog grant is invalid.

Serialize refresh per session and atomically replace the credential pair. The
[refresh documentation](https://dev.twitch.tv/docs/authentication/refresh-tokens/)
permits omission of a secret for public clients and requires storing replacement
credentials. A delayed result must not undo Forget or account replacement.
A lost successful refresh response may require reconnect rather than repeated
reuse of the old credential.

Use returned lifetimes. The device-flow documentation describes single-use
refresh tokens and thirty-day inactivity expiry; the refresh page describes
thirty days after generation. Record this wording difference rather than
assuming permanent validity or a guaranteed reset date. Implementation must
handle rejection with explicit reauthorization.

Local Forget deletes only the catalog session; provider-side revocation is a
separate action. Configured items remain. Route OAuth and Helix requests through
the instance's selected route with normal TLS and no System fallback. Scope
destinations to official OAuth device/token/validation and required Helix
operations, without broadening private playback/media policy. Closing an adapter
cancels its requests and prevents stale account/query results from reaching a
replacement instance.

## Connected and signed-out behavior

| Access mode | Bounded implementation recommendation |
| --- | --- |
| Valid catalog session | Native All/search, exact lookup, channel videos and Following |
| Disconnected | Local exact public video URL import; metadata remains unknown; channel/logins require connected identity lookup |
| Provider-controlled browsing | External discovery followed by explicit URL/share import; no automatic result extraction |
| Native anonymous All/search | Unresolved access architecture; not implemented by this recommendation |

An app-token service could support anonymous native browsing, but introduces
deployment, registration and operational ownership. No service was selected or
deployed. Do not embed a client secret in the APK or borrow a playback client's
identity. Twitch's [registration guidance](https://dev.twitch.tv/docs/authentication/register-app/)
treats client IDs as public, forbids sharing them between applications and
requires secrets to remain confidential.

Disconnected local import must normalize only recognized public resource
formats; it cannot import credentials, signed media URLs or arbitrary pages.
It does not prove existence or promise playback. Signed-out native browsing
remains a product/access decision for
[#99](https://github.com/altendky/tachiai/issues/99), not a removed requirement.
Connected discovery, fixtures and configured storage can proceed independently.

[Issue #120](https://github.com/altendky/tachiai/issues/120) implements the bounded
exact-video case in the shared manager. It accepts only bare public video links,
retains an unknown availability and requires explicit Add. Mutable channel URLs
and logins are not saved as guessed broadcaster identities. Connected failures
remain failures; a fresh capability assessment and input are required before
using local import after account access is lost.

## Shared adapter mapping

Map the Twitch implementation to the shared contract from
[#96](https://github.com/altendky/tachiai/issues/96):

- Browse/search/refresh and child pages require a validated catalog session.
  Disconnected lookup normalizes an exact public video URL locally and returns
  unknown availability; it does not verify existence. Other inputs are rejected
  by the local path. A validated session permits metadata lookup.
- Following is optional, with explicit authorization-required, reconnect-required
  and scope-required states. Do not turn unavailable access into a successful
  empty list. History remains separate follow-up
  [#102](https://github.com/altendky/tachiai/issues/102).
- Child browsing takes the exact broadcaster resource as parent and returns
  published VIDEO resources. Account collections and content parents are
  different query scopes. A channel does not silently become its latest video.
- Playback capability is independent from catalog access. Resolution retains
  exact public identity and defers preparation to existing playback contracts.
- Resource identity, content availability and access state remain separate.
  Unsupported, not-verified and access-required outcomes are explicit.

Adapters own selected-route transport and account lifecycle. Opaque cursors
belong to the current instance, account/session revision, query and parent;
never persist them as configured identities or diagnostic evidence.

## Paging, failures and verification

The [API concepts guide](https://dev.twitch.tv/docs/api/guide/) documents cursor
paging and dynamic lists that may repeat entries or return an empty page before
exhaustion. Deduplicate exact resource identities while following bounded
cursors; respect endpoint page limits. Track returned rate-limit headers and
reset times rather than assuming a universal request allowance. A 429 exposes
retry time; a 503 permits one retry. Avoid dependencies on raw error wording.

Use closed invalid-input, not-found, access-required, unsupported, not-verified,
rate-limited and temporary outcomes. A 401 enters the session's bounded
validation/refresh/reconnect policy, never a route or client-identity fallback.
Provider response text, sensitive URLs and headers do not enter diagnostics.

Issue #99 must still verify scoped consent and production parsing, offline
Following and exact lookup, parent-scoped videos, refresh rotation, process
restart, revocation/Forget races and selected-route confinement on the target
Android/TV environments. Record device, account state, route and outcome without
sensitive evidence. Fixture success proves contract behavior, not provider
access. Completion of #98 documents the supported connected design and the
signed-out decision; it does not complete those experiments or #99.
