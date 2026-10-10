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
acceptance. A bounded account experiment observed the requested scope and refresh
credential in a token response, with omitted expiry; validated account access,
refresh, native playback acceptance, provider revocation and imported-route
authorization remain unobserved. The access research and
official protocol references are in [Twitch catalog access](twitch-catalog-access.md).

## Consent and routes

Open Providers, configure the intended Twitch instance's saved route, then open
the Twitch connection. An unsaved route-editor draft does not select the login route.
Connect requests an activation code on a worker using the saved route. Consent
opens a provider-controlled page in an installed external browser. That browser
uses its own network and login, independently of Tachiai's selected route.

While activation is pending, a locally generated QR code opens that same
provider-returned activation URL on another device. A matching public code in the
URL is preserved; a bare URL opens the activation page for manual code entry.
The readable code is selectable, and the existing external-browser buttons remain
available. The scanning device uses its own browser, network and login.
An unavailable browser leaves the same pending challenge available for another
browser or QR scan. Cancellation, expiry, validation and loss of ownership remove
the activation instructions. Recomposition and rotation do not request another code.

The debug-only encoder uses ZXing Core 3.5.4. Its upstream Apache license and
notice are included in the debug APK's `assets/licenses/zxing/` directory.

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
user, access/refresh pair, provider expiry when known, local retention and durable
generation. Configured
sources, provider favorites and other instances are unaffected. Historical
token-only playback grants are not migrated or used as a fallback.
Credentials are absent from intents, UI state, recovery data, logs and exports.

A process-created or resumed session begins unverified. Account access requires
current-route validation and expires at the earliest provider/local deadline or
one hour after validation. Backgrounding invalidates leases. Active foreground use
schedules hourly validation. Temporary network failures suspend access without
claiming provider revocation. An invalid client, user or scope requires explicit
reconnection.

On 2026-10-10 the user approved bounded local retention for the scoped connection
in [#137](https://github.com/altendky/tachiai/issues/137). New connections retain an
original deadline of at most seven days from token-poll start. Known positive
provider expiry also bounds access. Literal integer-zero official validation
expiry means unknown provider expiry under this explicit local policy; it does
not mean permanent validity. Version-2 storage keeps the original local deadline
separate from provider expiry. Existing version-1 provider-only records retain
their positive-expiry policy and receive no implicit extension or rewrite.

Present token lifetimes must be positive integral values, and a valid refresh
credential is mandatory. An omitted token lifetime remains worker-local until
exact official client, user and scope validation completes within 30 seconds of
the token request starting. New locally retained grants also use that bounded
acceptance window when the token reports positive expiry. Present null/zero or
malformed token expiry and missing/null/negative/malformed validation expiry
remain rejected. Only literal integer zero receives the approved unknown-expiry
interpretation.

Validation never renews local retention. A positive validation response can
tighten the same token's durable provider bound while preserving its pair
generation. Worker checks enforce the latest durable bound for existing leases;
a zero response does not rewrite storage. Refresh preserves the original local
deadline even when the replacement token reports positive expiry. Expired local
retention requires reconnection before any provider request, including refresh
or a stale unauthorized callback. Gates also run after route preparation.
Wall-clock persistence rejects rollback before the saved anchor, but is not a
tamper-proof measure of elapsed time across restarts.

Debug diagnostics report only closed categories for the authorization phase,
failure, response endpoint, lifetime shape, scope match and refresh presence.
They never include response values, provider scope names, credentials, account
identifiers or activation instructions. Rejection details remain fixed UI text.

A differently client-bound record reports reconnection required before its
credentials are interpreted. Reads perform no network or implicit replacement;
explicit Connect or Forget can replace it. Native preparation validates afresh
and checks the owning instance, user and durable generation. Catalog success
does not itself establish native playback entitlement.

Provider expiry or a current-generation 401 permits one serialized refresh within
any remaining local retention. Before HTTP,
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
the instance-bound entry, JSON decoding and sanitized connection UI. They do not
establish validated account acceptance, account collections or native playback.

On 2026-10-10, the user completed Smart TV consent and returned to Tachiai on
persistent `tachiai-dev` (Android 16/API 36, x86_64), with the instance's saved
System network route. Enum-only diagnostics reported TOKEN lifetime OMITTED,
scopes EXACT and refresh PRESENT. The prior positive-token-expiry policy rejected
this response before `/validate`, so no grant was saved. The external browser
engine and region were not recorded; the user used their existing browser login.
This was an authorization experiment, with no content playback. The bounded
compatibility fix is [#135](https://github.com/altendky/tachiai/issues/135);
validated provider acceptance remains [#99](https://github.com/altendky/tachiai/issues/99).

A second user-approved check at 12:03:34 reached official validation: the parser
passed Smart TV client, well-formed user and exact scope checks, then rejected
literal integer-zero validation expiry as EXPIRED. The shared grant was not saved.
The device, route and account conditions were the same as above. The scoped
local-retention follow-up is tracked in [#137](https://github.com/altendky/tachiai/issues/137).
The user subsequently approved its finite local policy; this earlier rejection
does not establish account, playback or refresh acceptance after the change.

Three selected synthetic UI checks passed on persistent `tachiai-dev` on
2026-10-10 (Android 16/API 36, x86-64): rendered-pixel decoding and challenge
replacement/removal, encoder failure fallback, and a short wide viewport with
twice-normal font size. The last check decoded the QR and verified full visibility
of the code and browser/cancel controls after scrolling. Only a test host,
in-memory fixture state and callbacks were used; saved app data and device
settings were retained. No provider request or account state was involved.
These checks do not establish physical camera scanning, real provider prefill,
Android TV behavior or successful shared account/native acceptance.
