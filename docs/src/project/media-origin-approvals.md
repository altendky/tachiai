# Media-origin approvals

This is a user-approved network boundary for the debug-only cached ABEMA
prototype, not a claim of provider support, CDN ownership, or distribution
permission. Historical page-backed examples retain their exact-host policies.

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

For each new pattern, ask the user before granting access. Record the sanitized
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
