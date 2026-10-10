# Twitch catalog authorization prototype

## Implemented boundary

The debug Providers screen exposes one Twitch connection for each instance.
At the user's request it uses Twitch's Smart TV client identity and requests only
`user:read:follows`, through Twitch's documented device authorization flow.
The [connected catalog prototype](twitch-connected-catalog.md) and native source
preparation use the same instance-bound credential owner. This selected provider
registration remains an experiment, not Twitch approval or an own-client
integration. The shipped Tachiai-specific client registration is retired.
Real account/route evidence, anonymous native browsing and selected-resource
playback remain in [#99](https://github.com/altendky/tachiai/issues/99).

Implementation and synthetic fixture evidence are distinct from provider
acceptance. Scoped Smart TV consent, refresh, native playback acceptance,
provider revocation and routed authorization
have not been observed with an actual account. No provider requests or account
experiments were performed to implement this slice. The access research and
official protocol references are in [Twitch catalog access](twitch-catalog-access.md).

## Consent and routes

Open Providers, configure the intended Twitch instance's saved route, then open
the Twitch connection. An unsaved route-editor draft does not select the login route.
Connect requests an activation code on a worker using the saved route. Consent
opens a provider-controlled page in an installed external browser. That browser
uses its own network and login, independently of Tachiai's selected route.

Polling pauses while Tachiai is in the background and resumes with the original
code deadline. Tokens remain worker-local until their exact Smart TV client, scope,
user and lifetime have been validated. A changed instance, saved route, imported
profile or account generation prevents stale completion. Missing or failed routes
block requests; they never select System as a fallback. Explicit System is a valid
saved choice. Forget needs no network and remains available for an unreadable route.

Every OAuth exchange owns and releases its temporary route. The adapter admits
only the exact device, token and validation endpoints on `id.twitch.tv`; no
media-origin policy is broadened. The closed catalog route purpose also admits
`api.twitch.tv` for the separate supported metadata adapter. Cancellation signals route preparation,
coalesces request interruption on bounded workers, and rejects late responses.
Unconfirmed cleanup blocks reuse of that transport and its owning binding.

## Maintained grants

Each stable instance UUID, including the default Twitch instance, has its own
encrypted no-backup shared record. It binds the Smart TV client, scope, validated
user, access/refresh pair, returned lifetime and durable generation. Configured
sources, provider favorites and other instances are unaffected. Historical
token-only playback grants are not migrated or used as a fallback.
Credentials are absent from intents, UI state, recovery data, logs and exports.

A process-created or resumed session begins unverified. Account access requires
current-route validation and expires at the shorter of token validity and one
hour after validation. Backgrounding invalidates leases. Active foreground use
schedules hourly validation. Temporary network failures suspend access without
claiming provider revocation. An invalid client, user or scope requires explicit
reconnection.

Scoped token and validation responses must have positive integral lifetimes, and
the token response must contain a valid refresh credential. The historical
zero-scope Smart TV omitted/zero expiry exception remains limited to its original
experiment. It does not establish scoped grant behavior or permanent validity.

A differently client-bound record reports reconnection required before its
credentials are interpreted. Reads perform no network or implicit replacement;
explicit Connect or Forget can replace it. Native preparation validates afresh
and checks the owning instance, user and durable generation. Catalog success
does not itself establish native playback entitlement.

Expiry or a current-generation 401 permits one serialized refresh. Before HTTP,
the old pair is replaced by a durable refresh-in-progress marker under the
instance's process and file lock. The replacement token must validate the expected
user, client and scope before atomic pair rotation. A lost response, process death,
failed commit or interrupted single-use refresh requires reconnect; the old refresh
token is not retried. Stale unauthorized callbacks cannot revoke a replacement lease.

## Forget and recovery

Forget immediately invalidates live leases in the current process and queues an
independent local clear. Other processes check durable generations and pending
clear markers before using a grant.
Accepted clear work survives activity/controller closure; recreation and ordinary
return wait for it. A failed clear blocks access and offers Retry Forget, including
local recovery when the saved route or registry cannot be read. Only this instance's
shared grant is cleared, ending catalog access and native preparation leases.
Browser login and provider-side authorization are separate.

With Android's file lock, a no-secret pending-clear marker records destructive
intent before the encrypted tombstone. It prevents an old grant from becoming
usable after restart when the encrypted write fails. Successful retry commits the
tombstone and removes the marker. Complete filesystem failure can prevent intent
from being persisted; that failure is reported and cannot be described as a
successful or durable clear.

## Verification limits

JVM fixtures cover protocol parsing, encoded refresh forms, response bounds,
foreground/deadline races, stored generations, interrupted refresh and clear,
route ownership, cleanup, and local credential isolation. Android fixtures cover
the instance-bound entry and sanitized connection UI. They do not establish that
the selected Smart TV registration accepts scoped consent, returns the required
lifetime/refresh shapes, retrieves account collections or admits native playback.
Provider observations and discovery integration
remain tracked by the parent issue.
