# Twitch catalog access

## Status and recommendation

Issue [#98](https://github.com/altendky/tachiai/issues/98) investigates supported
metadata and account-collection access for the configurable source list.
The official documentation was reviewed on 2026-10-09.
On 2026-10-10 the user selected Twitch's Smart TV client identity for both catalog
and native playback, with one scoped connection per provider instance. The debug
implementation uses the documented device-flow mechanics and Helix endpoints,
but use of this provider-owned registration is an explicitly selected experiment,
not an own-client integration or a claim of Twitch approval. The bounded native
playback operations remain described in [security and privacy](security-and-privacy.md).

The shipped Tachiai-specific registration has been retired. Its earlier zero-scope
device-flow evidence remains historical in the
[timing capability matrix](timing-capability-matrix.md#own-client-device-authorization-probe--2026-10-05);
it does not verify the new scoped Smart TV connection. The original metadata
investigation made no account requests. A subsequent bounded `tachiai-dev`
authorization check observed the requested scope, a refresh credential and omitted
token expiry, then rejection before validation. See the
[recorded conditions](twitch-catalog-authorization.md#verification-limits).
Validated account access, Helix responses, refresh, native playback acceptance and
imported-route account access remain unobserved.

The [debug connection prototype](twitch-catalog-authorization.md) owns the shared
grant lifecycle. The [connected catalog prototype](twitch-connected-catalog.md)
uses that connection for supported Helix discovery, while native preparation
obtains a separately bounded validated lease from the same credential owner.
Synthetic verification does not establish actual provider acceptance.

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

The [connected prototype](twitch-connected-catalog.md#known-schedule-context)
now adds bounded known schedule context to exact broadcaster lookup and refresh.
Its date remains optional and independent from saved identity, observed live
status and playback. This implementation has synthetic verification rather than
real account/schedule response evidence.

## Selected Smart TV authorization

Twitch's [public-client device flow](https://dev.twitch.tv/docs/authentication/getting-tokens-oauth/#device-code-grant-flow)
supports scoped user tokens and refresh without a client secret. Public clients
cannot use client credentials to obtain app tokens. Request `user:read:follows`
for the shared connection offering Following; request no email or write
permission. Public metadata needs no additional catalog scope.

Keep activation provider-controlled in the external browser. Present explicit
Following consent and cancellation. Do not capture the approval page or expose
codes in diagnostics. Following permission does not establish native entitlement,
subscription/ad behavior or provider approval of the selected client identity.

The shared policy requires exactly `user:read:follows`, a bounded access/refresh
pair. Present token lifetimes must be positive; an omitted lifetime permits only
worker-local official validation within 30 seconds of the token request starting.
The separately approved [scoped retention policy](twitch-catalog-authorization.md#maintained-grants)
accepts literal integer-zero validation expiry as unknown provider expiry, with
an original local deadline of at most seven days. Known positive expiry also
bounds access; validation and refresh cannot renew the local deadline.
Existing provider-only records retain their strict positive-expiry policy.
Twitch's device-flow examples include token expiry; the observed omission and
zero validation are compatibility differences, not documented permanent validity.
The historical experiment alone did not authorize this extension. Earlier token-only grants are not
copied into the shared store. The same client identifier does not make distinct
old grants or accounts interchangeable. If real scoped responses do not satisfy
the policy, report that mismatch; do not assume permanent tokens or silently
broaden the accepted shapes.

## Session ownership, persistence and routing

The shared connection belongs to one provider-instance UUID and binds
the exact Smart TV client, validated user, authorized scope set and session revision.
Use an encrypted no-backup credential record, atomic replacement and
revision guards following existing storage conventions. No plaintext fallback,
export or migration from a playback slot. Access/refresh credentials stay out
of configured sources, recovery state, UI saved state and logs.

Twitch requires [validation](https://dev.twitch.tv/docs/authentication/validate-tokens/)
at startup and hourly while maintaining an OAuth session. Verify client, user,
scope and lifetime before exposing account access. Revoked/invalid authorization
ends that session; reconnect is explicit. A private playback error alone is not
proof that the shared OAuth grant is invalid. Native source preparation requires
fresh validation and checks the same instance, user and durable grant generation;
successful catalog access alone does not establish playable content.

A record bound to a different valid client requires explicit reconnection before
its scope or credentials are interpreted. Reading it performs no request or
implicit rewrite. Reconnect replaces the old record under the selected identity;
Forget can also clear it. Malformed records and actual storage failures remain
distinct failures.

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

Local Forget clears only this instance's shared connection and invalidates its
catalog and native preparation leases; provider-side revocation is a separate
action. Configured items remain. Route OAuth and Helix requests through
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
deployed. The selected Smart TV user grant does not create anonymous app-token
access. Do not embed a client secret in the APK. Twitch's
[registration guidance](https://dev.twitch.tv/docs/authentication/register-app/)
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
access. Completion of #98 documents the Helix design and signed-out decision;
the subsequently selected Smart TV identity and shared native use remain an
experiment and do not complete those observations or #99.
