# Twitch browser diagnostic

This is reproducible investigation infrastructure for
[#365](https://github.com/altendky/tachiai/issues/365), using the
[#364 web harness](web-client.md)'s pinned Shaka dependency, generated Rust/Wasm
module, controlled test media and production browser test pipeline. It is not a
connected-account feature, provider playback acceptance, a new web UX, or an
extension/companion deployment decision. Issue365 remains open until the actual
browser-local authorization and selected live/replay playback evidence is known.

## Explicit local execution

Follow the [harness setup](../../../apps/web/README.md) to install dependencies,
prepare assets and build. The default command refuses to start a trial; network
access and each exact catalogue case must be selected explicitly:

```sh
mise exec -- npm run probe:twitch --prefix apps/web
mise exec -- npm run probe:twitch --prefix apps/web -- --network --case=live:virtualjapan --case=replay:2080217716
```

The second command starts a fresh ordinary Chrome context and local production
preview server on 127.0.0.1:4173. It prints a short-lived activation code for the
provider's Twitch Activate page. The user controls provider login and consent in
their own browser; the diagnostic never opens or instruments that login page.
No provider credentials, cookies, saved Android grants, imported browser profile,
client secret, refresh operation or new application registration is used.
Do not approve an old code: each run owns one fresh challenge and its deadline.

Only the two named existing catalogue samples are admitted. A refused selected
case ends the playback sequence; it does not try a different channel/replay,
client identity, unauthenticated access, provider embed, route or media relay.
The observed replay distribution `dgeft87wbj63p.cloudfront.net` is admitted only
for a selected replay; this is not a general CloudFront rule. The other media
families match Android's existing experimental `ttvnw.net`/`twitchcdn.net` policy.
These bounds establish neither current availability nor entitlement of a sample.

The run stops after at most 15 minutes, plus bounded in-flight work and cleanup.
Ctrl-C cancels current browser requests, retires the owned player and attempts
revocation of this run's fresh access grant. Closing the page or losing the
browser/network can prevent confirmed revocation; `unconfirmed` is a failure to
establish cleanup, never a claim of revoked authorization. `no-grant-held` means
no access grant was adopted in browser memory, not that the provider could not
have issued an unread response during an interrupted request.

## Account and source ownership

The device flow pins public client `ue6666qo983tsx6so1t0vnawi233wa` and exactly
`user:read:follows`. This is the already selected provider Smart TV public
identifier, not Tachiai's application registration or a supported playback
contract. Scope selection alone is not playback entitlement. The first successful
validation pins the user ID in browser memory; revalidation must preserve exact
client, user, scope and a positive expiry before each source request. Account IDs
never leave that runtime. Refresh data is ignored and never retained.

This diagnostic deliberately requires a finite positive `expires_in` of at most
365 days in both the token grant and validation response. This is stricter than
the selected Android Smart TV catalogue flow: `parseTwitchCatalogToken` can
accept an omitted token expiry, while validation explicitly using
`allowUnspecifiedLifetime` accepts zero as unknown and relies on its separately
bounded local retention policy. That Android path still refuses missing
validation expiry and zero token-grant expiry. The web diagnostic does not
silently import its retention policy or interpret missing/zero as a proven
provider expiration. Such a response causes refusal and revocation of the fresh
grant. An actual manual trial may establish that this finite-expiry requirement
is incompatible with the selected client; that would be a diagnostic policy
finding, requiring a reviewed browser lifetime policy before adoption is relaxed.

OAuth requests use the documented
[device flow](https://dev.twitch.tv/docs/authentication/getting-tokens-oauth/#device-code-grant-flow),
[validation](https://dev.twitch.tv/docs/authentication/validate-tokens/) and
[revocation](https://dev.twitch.tv/docs/authentication/revoke-tokens/) endpoints.
Those documents do not establish private GraphQL/HLS playback support or that
this selected public client will accept a browser session.

Authenticated playback access intentionally follows Android's current
`resolveTwitchPlayback` comparison: the GraphQL `Client-ID` header is empty,
while OAuth issuance, validation and revocation remain bound to the exact public
client above. This is a fixed, documented experimental request shape, not an
accidentally omitted OAuth identity, and there is no automatic alternate-header
retry. A readable GraphQL response must match the exact requested live/replay
field and bounded signature/value shape. Its opaque signed value is used
unchanged for that same selected manifest path; its internal claims are not
treated as independently verified source/entitlement evidence.

All grant-bearing requests stay in the owned browser page. A closed Fetch
transport admits only exact OAuth/GraphQL endpoint/method pairs, forces normal
CORS mode, omits cookies, disables caching and uses `redirect: error`. The
diagnostic replaces Shaka's HTTPS scheme plugin with its own bounded Fetch
transport, using the reviewed media policy and the same cookie/redirect rules;
it does not fall back to XHR or Shaka's default network plugins. Native playback
fallback is disabled, and the page's media CSP permits only blob-backed MSE.
Grants never accompany CDN requests. New destinations cause refusal and require
investigation.

The production runner installs no Playwright request interception. Such routing
can synthesize CORS preflight responses in Chromium and cannot enforce redirects
by rechecking each redirected request, so it would distort this investigation.
Actual browser CORS acceptance remains an outstanding provider observation.

## Player and diagnostic boundary

The separate `twitch-proof.html` entry owns a Shaka instance and video element.
The existing `BrowserPlayer` remains fixture-only: provider sources are never
mislabelled as controlled fixtures and its policy is not broadened. The provider
host follows the same lifetime pattern: muted initial media, single-attempt
preparation, guarded retirement, safe observations, bounded preparation and
refusal to admit another player after failed destruction. Concurrent stop/close
operations share one cached retirement promise; new preparation cannot be
admitted until destruction is confirmed. Failed destruction remains sticky.
It loads the matching
Rust/Wasm module, but provider seek dispatch remains unsupported until Twitch
ad/timeline binding is verified. It does not inherit the fixture's ad-free seek
permission. No provider media is copied, recorded, re-encoded or exported.

Only fixed result categories, the public selected case, numeric frame dimensions,
decode/advance booleans and the short-lived activation code leave the browser.
The runner suppresses browser console messages before loading libraries and
does not retain screenshots, video, traces, HAR, storage, provider bodies,
tokens, signed URLs, account identifiers or unrestricted errors. Acoustic output
is explicitly untested. A future runtime report must record host/OS/browser,
region/account condition, selected content, observed behavior and cleanup.

The session/host diagnostic getter and CLI refusal results expose only a closed
stage enum and an optional integer HTTP status from 100 through 599. Stages
distinguish device challenge, token polling, validation, source access and player
attach/load/playback/cleanup. Network failures may have no HTTP status; media
status is the latest response observed in that stage, not proof of why a decoder
failed. Original preparation failure stages survive subsequent cleanup. Provider
strings, response fields, token shapes and raw player/network errors do not
enter this diagnostic result.

The token-poll stage also exposes only a fixed refusal reason: `cancelled`,
`deadline`, `token-type`, `expiry-missing`, `expiry-zero` or `expiry-invalid`.
These identify the existing post-response checks without returning the token
type, expiry value, access/refresh grant or other response fields. The CLI
independently allowlists the reason, and cleanup preserves the failed observation.
The exact lowercase bearer and finite positive expiry policy is unchanged.

### Activated browser trial: October 26, 2026

The fresh user-activated trial on revision
`f099c590cd363ac6d1b2483407916e6dca777c19` used Linux x86-64 and Chrome
156.0.8078.47, a new browser context and default host networking. Exit country
was not independently measured. The selected sequence was Virtual Japan live,
then the documented replay, stopping on any refusal without an alternative.

After activation, token polling received HTTP 200 and captured this run's fresh
access grant. The diagnostic refused while still at `token-poll`, before account
validation, source preparation or media playback. Cleanup reported `destroyed`
and `revoked`; the listener ended and the loopback server was absent afterward.
No grant, provider body, account identifier, signed URL or media capture was
retained. No selected playback case was reached.

That revision reported no individual refusal reason. Cancellation/deadline,
the exact token-type check and the finite-expiry check remain candidates; this
observation does not establish which failed or justify changing the acceptance
policy. The fixed reasons above prepare a further fresh trial to distinguish
those cases. The revoked grant and old activation code must not be reused.

## Offline verification and outstanding acceptance

```sh
mise exec -- npm run build --prefix apps/web
mise exec -- npm run test:twitch:offline --prefix apps/web
mise exec -- npm run test:twitch:owned --prefix apps/web
```

The session tests use only synthetic responses. They cover exact client/scope/
user validation, poll cadence/slowdown/deadline, catalogue and origin admission,
authenticated request shape and selected manifest binding, response bounds,
late-response cancellation, fresh-grant revocation, pending/concurrent destruction
and sticky cleanup failure.
The separate Chrome test uses intercepted synthetic OAuth/GraphQL responses and
the existing controlled fixture media. It decodes 640×360 frames, advances,
refuses unverified provider seeking and disposes the player/revokes the synthetic
grant. The existing harness suite verifies actual Rust-planned fixture seeking
separately. No provider request leaves this test. Its preview uses port 4178.
An additional ordinary-browser test uses two real loopback HTTP servers without
request interception. It confirms that a denied CORS preflight reaches the
server and prevents POST, an accepted preflight permits POST, cookies are omitted
and not adopted, and a redirect's forbidden destination receives no request.
Only the explicitly synthetic provider/fixture test uses intercepted responses;
it is not evidence of provider CORS or redirect behavior.

These tests cannot establish actual consent, CORS acceptance, provider entitlement,
advertisements, signed manifest/CDN behavior, live-window operation, audio or
renewal. One reviewed opt-in runtime trial and user-controlled activation remain
necessary. No live provider authorization or playback trial was performed as
part of implementing this diagnostic.
