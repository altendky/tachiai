# Media-origin approvals

This is a user-approved network boundary for the debug-only cached ABEMA
prototype, not a claim of provider support, CDN ownership, or distribution
permission. Historical page-backed examples retain their exact-host policies.

## General integration scope — 2026-10-10

The user subsequently approved general ABEMA and Twitch access, including
undocumented endpoints where a needed operation has no documented API, and
replacement of fixed experiments after actual configured selections work. See
the [current integration approval](security-and-privacy.md#general-provider-integration-approval--2026-10-10)
and updated issues #99–#103. This supersedes earlier fixed-content permission
limits; necessary provider endpoint and network-policy changes can be developed
under that authority without a repeat approval for the same scope.

Verify and document each required destination and its request/resource binding
before changing an allowlist. This general approval does not admit arbitrary
hosts, redirects, lookalike domains or signed URLs without checks. Existing
runtime policies and pending-origin handling remain implemented behavior until
explicit code changes are verified. A review-journal entry remains evidence,
not an access grant or proof of provider ownership/entitlement.

The dated decisions below describe the historical cached prototype. They do
not establish a complete endpoint inventory for the approved general integration.

### Public ABEMA channel metadata

The Channels adapter uses anonymous GET to
[the channel endpoint](https://api.abema.io/v1/channels) under the general
integration approval, with no query, account credentials, redirects
or playback request. Its separate catalog route purpose admits only api.abema.io
on normal verified HTTPS; the provider transport enforces the exact URL/method.
This does not expand Twitch's metadata/OAuth purpose or the cached playback
policy. Both System and imported routes own a cookie-free client with origin
authentication disabled, and imported credentials stay at the local CONNECT
listener. Channel IDs/names are metadata, never proof of live state or entitlement.
See [the bounded catalog implementation](abema-catalog-access.md#public-channels-adapter)
for snapshot limits, instance/route ownership and evidence boundaries.

### Memory-only ABEMA device-authorization challenge

The explicit guest diagnostic's device-authorization operation uses the existing
`abema.tv` and `api.p-c3-e.abema-tv.com` origins under the general integration
approval. After one fresh guest POST and matching server-profile GET, its owned
policy admits one exact JSON POST to
`https://abema.tv/api/auth/oauth/device/authorization`. The destination and
profile-derived client request are verified static first-party bundle findings;
cookie-free response acceptance remains a bounded runtime check. Its separate
route purpose leaves catalog and playback purposes unchanged. No token-polling,
renewal, verification-navigation, collection or media destination is added.
The returned verification URIs remain in JavaScript, are locally restricted and
discarded, and confer no navigation approval. See
[the challenge boundary](abema-guest-bootstrap.md#optional-device-authorization-challenge-verification).

### Memory-only ABEMA pre-approval device-token observation

Under the same general integration approval, the separate explicit observation
operation adds one exact JSON POST to
`https://abema.tv/api/auth/oauth/device/token` after its own fresh guest, matching
profile and strictly validated challenge. The endpoint and unchanged four-field
request DTO are static first-party bundle findings. A distinct route purpose,
owned policy and CSP admit this sequence once; existing purposes are unchanged.
No new host, browser navigation, cookie use, polling/retry, renewal or media
destination is admitted. Only bounded refusal-schema and reviewed recovery
vocabulary categories can leave the runtime, after confirmed cleanup. An
unexpected success body is never opened or adopted. See
[the observation boundary](abema-guest-bootstrap.md#optional-pre-approval-device-token-observation).
Actual response acceptance is recorded separately and cannot qualify unobserved
terminal states or establish a connected account.

### Routed ABEMA account approval — 2026-10-11

The owner requested an in-app browser using the configured provider route after
the external Chrome approval page displayed Sorry. That report does not establish
a regional or routing cause. The approval-only route admits exact HTTPS hosts
`abema.tv`, `auth.abema.tv` and `api.p-c3-e.abema-tv.com`; top-level navigation
is restricted to the first two. Retained first-party account bundle5206,
SHA-256 d342d230d1f24a90a576597b673990a412a94bb5465cfa9b7567a9dc04e85b81,
uses same-origin `/api/auth/…` login/session/device requests and declares the
auth and modern API origins. No media, license, image, advertising, analytics,
lookalike, non-HTTPS or arbitrary redirect destination is added.

The owner's subsequent in-app screenshot shows ABEMA's regional-refusal page.
Retained cookie-free public HTML (SHA-256
8e77b27dab161384ea4cd9a3a3d2c174d440ac22af3ab09af76ec4c397eba7a6)
establishes a separate provider-supplied region check at
`https://ds-glb-linear-abematv.akamaized.net/region`, without a query.
The matching9298 bundle (SHA-256
34207e8d8ff54721927e7dc93edcdfa20c2af99e50c66c8ad5defe5e8e0f03e5)
module32735 checks this resource when client-region status is
set; HTTP403 produces region denial, while failed requests fall back to the
initial eligibility value. The previous three-host policy returned synthetic403
for this resource, which can itself cause that page. Under the existing general
integration approval, a narrow native adapter owns this exact HTTPS GET resource
through the captured saved route. It refuses redirects, reads no response body
or cookies, and returns the real status plus the provider's safe CORS header to
the page. Initial browser resource admission permits this path without queries
or fragments and refuses CDN navigation/media requests. The shared CONNECT
listener necessarily includes the CDN hostname: Android does not re-intercept
an account-origin resource redirected to a different path on that same host.
No such redirect was observed, but the browser boundary cannot claim universal
CDN path enforcement. Its region adapter does enforce its exact request. Actual
provider200/403 remains decisive; the browser never rewrites the region result.
The screenshot does not establish the saved route's actual exit country or
whether the provider will accept that exit after this correction.

The approval browser uses its own process and persistent WebView profile,
provider-controlled pages, normal TLS and the instance's captured route proof
and original challenge deadline. Device geolocation is disabled and location
prompts are refused; no fabricated coordinates or eligibility flags are injected.
ABEMA's network observation must use the configured route exit. The browser
loads only after proxy application, rejects
other destinations without a direct-network fallback, and has no native bridge
or injected scripts. Its own browser login is separate from protected native
grants and is never exported. Returning after confirmed cleanup allows the
existing explicit Continue action; it does not establish approval, token lifetime,
account identity, collection access or browser-email compatibility.
This browser currently requires an imported connection, whose CONNECT bridge
enforces the four reviewed hosts even after redirects. System-network approval is
refused before page loading because Android's
[request interception](https://developer.android.com/reference/android/webkit/WebViewClient#shouldInterceptRequest(android.webkit.WebView,%20android.webkit.WebResourceRequest))
does not recheck subsequent resource redirects. A host-restricted direct CONNECT
bridge remains needed for that browser mode; native System account requests and
other route purposes retain their existing behavior.

## Decisions

| Date | Origin or pattern | Decision | Evidence and scope |
| --- | --- | --- | --- |
| 2026-10-07 | `https://*-abematv.akamaized.net` | Approved by user | One DNS label ending in `-abematv`, including the observed `linear-abematv`, `ds-vod-abematv` and gateway-selected `vod-abematv` hosts. Cached native News/replay only. |

The observed domain is **akamaized.net**, not **akamized.net**. The wildcard
means a nonempty ASCII DNS prefix in that single label, not nested subdomains,
arbitrary Akamai hosts, or lookalike suffixes. Only HTTPS on the default port or
443 is eligible. User information, fragments, existing source-path/query rules,
fixed content eligibility and manifest-declared segment membership remain
independent restrictions. Approving an origin does not approve every URL on it.
API, license, public-bundle and browser navigation boundaries are unchanged.

No additional exact origin has been approved or rejected yet.

## Review workflow

At selected-manifest and encountered manifest-declared-media boundaries, the
prototype records the first observation of each decision/stage/rule/origin
combination. Already approved origins are recorded as APPROVED. An unfamiliar
eligible origin is PENDING and blocked before media network access. The viewer
returns to the picker with an approval-needed message. Unsafe/ambiguous
authorities remain rejected by URI validation and cannot become approval entries.

The app-private, no-backup `abema-cdn-review.tsv` journal is capped at 128 records
and 64 KiB. It retains timestamps, closed categories and
`https://hostname/<redacted-path>` only: paths, queries, fragments, user info,
tokens and signed URLs are not saved. Duplicate segment/token variations do not
create new rows. Atomic merge/write handles duplicate feed slots. Corrupt,
oversized or unwritable evidence stops preparation rather than silently
discarding it or widening access. This is encountered-boundary evidence, not a
complete inventory: validation may stop at the first disallowed declaration.

For an occasional user-requested phone audit, use the attached device's ADB
transport with a bounded reader. For example, replacing `TRANSPORT` with the
current transport ID:

```sh
set -o pipefail
adb -t TRANSPORT exec-out run-as net.fstab.tachiai cat no_backup/abema-cdn-review.tsv | mise exec -- node scripts/abema-cdn-review.cjs
```

The reader validates the entire bounded file before printing normalized rows;
invalid input and raw error details are not printed. A missing journal is not
proof that no origin was attempted. Logcat emits only a pending/unavailable
category, not the URL pattern. No scheduled monitoring is installed.

For each new pattern outside the approved general integration scope, ask the
user before granting access. For a necessary provider destination within that
scope, verify and document the bounded policy change. Record the sanitized
origin, observation context and explicit approval or rejection here. Then add
that exact HTTPS origin to the corresponding compiled approval/denial set in
`AbemaCdnApprovalPolicy`, add a regression test, rebuild, and retry. Denials take
precedence over family/exact approvals. A later observation records the new
decision without erasing earlier pending evidence. The journal is evidence,
never an executable permission database; neither its rows nor a successful
request silently approve a destination.
The reader's `pendingObservations` count includes historical pending rows; it
is not the current permission state. Consult this ledger and compiled policy
when reviewing a pattern that subsequently received a decision.

## Verification status

The policy and review workflow are implemented. On the Pixel 6 / Android 17 /
System WebView 153.0.8010.36, the cached free sumo replay produced native frames
in both independent slots after this change. The phone journal records
`https://vod-abematv.akamaized.net/<redacted-path>` as APPROVED at selected-
manifest and declared-media stages, with no pending observations in that run.
After a fresh-process two-News regression, the journal retained those rows and
added APPROVED `https://linear-abematv.akamaized.net/<redacted-path>` observations
at both stages. Both native News copies rendered and advanced; no new origin
approval was needed.
No new acoustic confirmation was requested. See
[native experiments](native-access-experiments.md) for build and device evidence.
