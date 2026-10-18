# Safe failure diagnostics

The debug prototype retains failure evidence in the app-private, no-backup
`failure-diagnostics.tsv` journal. It helps identify swallowed preparation and
cleanup errors; it does not change playback acceptance, route isolation or
recovery behavior. Recovery dialogs are separate work.

Each playback run has a randomly generated session identifier. Records name the
operation and optional feed A/B, timestamp, main/worker thread category, closed
exception categories and bounded application stack locations. Explicit failure
outcomes such as route-clear timeout have no exception. Existing closed feed
failure reasons are retained, including when provider code reports a safe reason
without throwing.

ABEMA channel failures retain their adapter-owned response, policy, cancellation,
deadline, network and cleanup distinctions. Twitch catalog authentication,
metadata, storage and transport failures likewise retain fixed reviewed values,
including DNS, TLS and timeout. These mappings inspect typed enums, never provider
messages or grant fields. Unknown exceptions remain OTHER; a structured result
reported without an exception cannot gain detail from exception capture.

Native media open/read failures are observed before cleanup. Their fixed
categories distinguish DNS, connection, transport timeout, TLS and transport
cancellation from the existing per-request deadline, refused request admission
and inactive playback budget. The admission boolean combines ownership,
acceptance deadlines and provider grant/admission validity; refusal alone does
not identify which condition failed or establish owner cancellation. An inactive
budget does not identify whether its owner ended or a finite diagnostic
observation expired. Originating request
categories and subsequent cleanup errors remain independent records; an
unconfirmed cleanup still blocks recovery. Request observations contain a new
app-owned exception with only the closed category, never the original private
transport exception or its message/cause. Existing routed transport exceptions
also retain their closed failure category in the journal. No transport phase or
raw request details are added to the v1 journal, and unknown vocabulary still
fails validation. This improves evidence for the sustained-playback investigation;
it does not identify or fix the observed provider/network failures.

Native player cleanup preserves Media3's release-timeout code 1003 as the fixed
`PLAYER_RELEASE_TIMEOUT` category, distinct from generic receipt uncertainty.
The app-owned projection contains no original Media3 exception, message or cause.
The timeout is reported once, including when void-returning release completes
normally; later cleanup stages still run and failed cleanup remains unconfirmed.
Ordinary successful release produces no failure observation.
Only a timeout observed after the actual release begins receives this category;
an earlier queued timeout retains generic cleanup uncertainty.

For this timeout only, debug builds also emit the closed `TachiaiNativeRelease`
log entry with `event=PLAYER_RELEASE_TIMEOUT`, `code=1003` and numeric `elapsedMs`.
This measures the release call itself, excluding preceding transport cleanup;
it is capped at 60,000 ms, with -1 for an unavailable/invalid monotonic clock.
The duration is ephemeral log evidence, not a new persisted journal field. The
v1 journal schema, retention, deadlines and recovery decisions are unchanged.
This instrumentation does not establish that an earlier generic release failure
was a timeout or identify why Media3 could not release.

On 2026-10-18, both provider-free asynchronous cleanup cases passed in 0.031
seconds on persistent `tachiai-dev`, Android 16/API 36 x86-64, after a normal
emulator restart and in-place update. Synthetic void release emitted duplicate
1003 notifications; the typed report occurred once, every remaining cleanup lane
ran, and confirmation stayed false. Successful release, unrelated uncertainty
and a timeout before release began produced no invented timeout attribution.
The journal was not replaced, and original picker assignments and every saved
encrypted-record digest were preserved. All 2,228 JVM cases, 12 collector tests,
lint, release isolation and app/test/unsigned-release assemblies passed, with
the stable debug/test certificate and 16 KiB alignment. This validates the
diagnostic boundary, not a reproduced provider timeout or its cause in #297.

Accepted playback MEDIA response bodies may progress beyond the old absolute
30-second lifetime. Setup and manifests remain bounded to 30 seconds; connect
and individual read timeouts, byte limits and before/after admission checks are
unchanged. Only playback routes offer the explicit routed-media policy. A shared
setup alarm remains active through headers and input acquisition, and body
acceptance must beat its deadline even if the alarm callback is delayed. The
atomic acceptance/expiry transition prevents a late queued alarm from canceling
an accepted body. Completion, cancellation and failure release the alarm; a local
setup timeout remains distinct from caller cancellation. Control-plane and DRM
requests retain their existing whole-call deadlines. This removes an observed
app-imposed media failure; low delivery throughput and sustained provider
behavior still require separate verification.

The first observed failure is retained as `FIRST`. Later distinct failures are
`SECONDARY`; this ordering does not establish causation. For example, a Twitch
404 and an ABEMA cleanup failure can be independent. Repeated blocked viewer
attempts use `BLOCKED` and reference the same first record. Repetition increments
a count instead of replacing its evidence. A failed process-wide route retains
its reporter when the Activity is recreated. Normal cancelled/stale preparation
and normal playback stopping do not become hard errors; an exception during
cleanup or cancellation signalling still does.

The journal never stores raw exception messages, cause strings, raw stack
traces, filenames, thread names, URLs, provider resource identifiers, request or
response contents, headers, credentials, route configuration or protected
license exchanges. Stack class/method metadata is mapped to fixed allowlists;
unknown locations and categories cannot introduce arbitrary strings. Media3
logging remains disabled for protected playback.

Storage is limited to 128 records and 64 KiB, with bounded repetition counts.
On each failure write, sessions whose first record is older than seven days are
removed. Capacity eviction removes older sessions as a group, preserving the
first record for retained sessions. History therefore survives a normal
restart, but is not an indefinite audit log. With no further writes, existing
history remains until explicitly cleared. Atomic replacement and a stable file
lock coordinate the prototype's separate processes. Invalid/oversized history
is left intact for investigation and is not silently overwritten. Recording
failure produces only the fixed `JOURNAL_UNAVAILABLE` marker and never replaces
the original error or skips the remaining cleanup.

## Cached ABEMA helper evidence

Issue [#171](https://github.com/altendky/tachiai/issues/171) retains the first
proved app-owned helper failure as a typed category: timer expiry, owned
cancellation, fetch rejection before HTTP, returned HTTP refusal, bounded
response-validation refusal or unknown failure. The existing journal schema
and retention are unchanged. These categories are reported directly; no
throwable message is manufactured. A pre-HTTP rejection does not identify a
DNS, proxy or TLS cause.

The helper resets numeric HTTP status at each request and latches the first
failure before abort or teardown. Later cleanup cannot replace that evidence;
an ignored AbortSignal cannot revive an expired run. Strict native admission
accepts only canonical old four-field and new five-field projections, closed
phase/category vocabulary and coherent numeric bounds. Status and metadata
callbacks retain the original owner, epoch and deadline checks. A stale READY
cannot erase a validated failure or start native preparation. Terminal license
failure reads the same bounded metadata before completion, never after close
or by extending the exchange deadline.

Cancellation of pending helper work can retain a typed diagnostic outcome;
ordinary successful take/playback cleanup is excluded. This does not turn a
normal Stop into a recovery dialog. No exception properties, provider values,
URLs, identity or license bytes enter these projections. Missing callbacks or
renderer death may still prevent collection, and new diagnostics cannot
recover the cause of an earlier discarded error.

The cached native session also retains a memory-only preparation phase, pending
source/license flags, whether a native host was created, and the first current
worker exception's fixed category. It never reads exception messages, causes or
class names. Teardown freezes the observations before clearing pending work;
host creation and an absent exception do not establish playback readiness.

The opted-in continuous-broadcast fixture preserves helper progress even when
there is no terminal native reason. It keeps progress separate from failure,
then seals both before disposal. Bounded startup/frame/sampler timings and fixed
sampler states explain how the observation window was consumed without exporting
media positions or timeline identities. These summaries require the existing
account, owner, saved-record, clock and confirmed-cleanup proofs. The Schedule
lead fixture similarly exposes only an allowlisted runtime category and two
projection flags after its preservation and cleanup checks. None of these
observations grants playback authority or changes production deadlines.

## Collecting one device's evidence

Verify the requested device before collecting. For the persistent emulator,
check both the reported AVD name and path:

```sh
adb -s emulator-5580 emu avd name
adb -s emulator-5580 emu avd path
```

They must identify `tachiai-dev` and its owned data directory as described in
[the development-device guide](android-development.md). Then collect only this
journal through the strict validator from the repository root:

```sh
set -o pipefail
adb -s emulator-5580 exec-out run-as net.fstab.tachiai cat no_backup/failure-diagnostics.tsv | uv run scripts/failure-diagnostics.py
```

The collector validates size, vocabulary and record relationships before
printing the validated TSV. Invalid or truncated input emits a fixed error without echoing
the input. A missing journal means there is no retained failure evidence; do
not substitute a broad logcat dump, export app-private directories or enable
Media3 exception logging. The fixed `TachiaiFailure` logcat tag can distinguish
an unavailable journal from successful recording, but does not contain the
underlying exception.

Use the matching checkout for collection: new builds read existing v1 journal
records without migration, while an older collector correctly rejects newer
categories outside its vocabulary. New fixed categories do not change the v1
columns, retention, capacity or storage policy.

Correlate `sessionId`, `recordId`/`rootId`, stage, slot and application locations.
Capture the first failure after starting playback and returning to Sources or
backgrounding the app. Repeated Open viewer attempts should preserve that
evidence. The build cannot reconstruct an exception discarded by an older APK.

After collecting the useful evidence, explicitly clear only the diagnostic
journal on the already verified device if needed:

```sh
adb -s emulator-5580 shell run-as net.fstab.tachiai rm -f no_backup/failure-diagnostics.tsv
```

Do this while playback is stopped to avoid racing a writer. Clearing diagnostic
history does not clear a playback/route failure flag, routes, authorization or
provider data. No automatic upload or external telemetry is added.

## Twitch idle maintenance admission

Each Twitch media check probes the session's maintenance state before owner
I/O. An idle check still performs one complete fresh owner reconciliation and
one durable grant read. Budget, original lease expiry, retention, lifecycle and
revision checks surround that work. Maintenance state is checked again after
owner I/O and after the grant read. Work or a receipt appearing at either point
returns to the existing owner/wait/adopt/currentness path; refusal still stops
the operation. The idle result is never retained across checks.

This removes duplicate owner reconciliation when no maintenance is underway.
The route pool retains its own before/after snapshot rereads, every network
read retains its admission checks before and after input, and the grant is
authenticated afresh for every admitted check. No admission interval, plaintext
cache, skipped expiry/Forget check or new transport request is introduced.
Registry/profile locks are not held across grant I/O.

### Idle-admission observation — 2026-10-18

On persistent `tachiai-dev`, Android 16/API 36 x86-64, with WebView
133.0.6943.137 installed, the same configured muted Twitch replay/System-network
fixture passed in 47.570 seconds (47.536 seconds inside its operation). The
selected video remained 1,409,040 bit/s. The sequential observations compare as
follows; each sampled approximately 40 seconds:

| Observation | Mean body admission | Playing/buffering samples | Position advance |
| --- | ---: | ---: | ---: |
| Original measurement (#326) | 15.875 ms | 111/49 | 27.860 s |
| Key-handle reuse (#328) | 13.820 ms | 130/29 | 32.404 s |
| Idle admission (#331) | 9.125 ms | 159/0 | 39.544 s |

The idle run delivered 8,717,936 media bytes in 2,088 positive input reads.
Its 4,181 body-admission checks took 38.151 seconds, underlying input reads
took 0.188 seconds, and total datasource reads took 38.505 seconds. The final
SDK bandwidth estimate was 1,687,018 bit/s. Frame rendering, unchanged encrypted
records/source identity and confirmed cleanup passed; no terminal failure was
observed. All 2,228 JVM cases, lint, release isolation and app/test/release
assemblies passed, with the stable debug/test certificate and 16 KiB alignment.

This demonstrates a useful local cost reduction and no sampled buffering in
one short single-feed run. It is not a controlled CPU/network benchmark or
evidence that two-feed buffering, ABEMA failures, acoustic output or physical
devices are resolved. No protected-media capture or account/route change was
involved.

Two fresh configured ABEMA News live/Windscribe and Twitch replay/System-network
runs each continued through a 390.2-second observation, with no terminal feed
failure. The first run sampled ABEMA playing 79/79 times and Twitch playing
39/80 times, with 41 buffering samples; their content/replay advances were
391.320 and 212.311 seconds. The repeat sampled ABEMA playing 79/79 times and
Twitch playing 50/80 times, with 30 buffering samples; advances were 394.796 and
238.685 seconds. The state samples include a short pre-observation context,
covering approximately 399–404 seconds. ABEMA uses content time because its
live-window position moves. Twitch's final SDK estimates were 745 and 860 kbit/s,
so the two-feed buffering remains unresolved.

Normal Stop after the first run produced the cleanup recovery dialog. The
validated journal recorded feed A `PLAYER_RELEASE / IO / MAIN`, followed by
`FEED_CLEANUP_UNCONFIRMED`; it does not establish a release-timeout cause. The
built-in restart restored the picker. Normal Stop after the fresh repeat
returned to the picker successfully. Both runs restored all original assignment
controls and preserved every saved encrypted-record digest. The first collector
wrote its complete bounded observation but exited 143; the repeat exited zero.
The intermittent cleanup uncertainty remains tracked in #334 under #297. These
observations do not establish that idle admission caused or fixed that failure.

## Twitch committed System route registry snapshots

Android Twitch additionally opts into a fresh committed-registry snapshot when
the validated entire saved route pool is nonempty and entirely System network.
That pool has no profile file: its single authenticated, decoded registry record
is an explicit one-file linearization point. The store supplies committed versus
legacy-derived provenance from that same read under its existing in-process and
OS file locks; no file-existence precheck or remembered snapshot is used. Snapshot
collections are immutable. Locks end before proof callbacks or grant access.

The generic two-registry/two-profile reconciliation remains the default and is
retained for imported/mixed pools and legacy-derived views. Its first sample is
reused only inside that invocation, avoiding an extra third registry read.
Corruption still refuses instead of becoming absence or System network. Both
paths use identical full-pool/candidate/cleanup proof construction, and a mixed
pool's selected System candidate does not qualify for the shortcut.

This explicitly narrows the SYSTEM-only capture's within-call change-observation
window; neither path is a cross-store atomic transaction or an ABA guarantee.
Every media admission still performs fresh owner and durable grant checks before
and after input reads, with unchanged exact expiry, Forget and caller-budget
guards. The next gate observes a committed route change; an observed mismatch
remains revoked even if the old configuration is restored. Reduced encrypted-read
count alone does not establish a playback or performance improvement.

The older `CatalogAdmissionCostDeviceTest` retains a historical ten-read model
with default key loading. It does not model current production admission. Use
the actual `TwitchMediaReadCostDeviceTest` for current measurements; its
`initialRegistryCommitted` field describes only the initial setup sample's
provenance, not a later transaction or cached authority.

On 2026-10-18, the actual configured muted Twitch replay/System-network fixture
passed in 47.293 seconds on persistent `tachiai-dev`, Android 16/API 36 x86-64,
installed WebView 133.0.6943.137, with its existing catalog account. The initial
registry was committed. A frame rendered, all 159 samples reported playing with
none buffering, and position advanced 39.533 seconds. Body admission averaged
6.239 ms across 5,675 checks; 12,201,388 input bytes arrived in 2,834 positive
reads. Admission still consumed 35.404 of 35.844 seconds measured in datasource
reads, versus 0.236 seconds inside input reads. The final SDK delivery estimate
was 2,699,296 bit/s and selected video was 1,409,040 bit/s. Earlier runs used
different evolving network/rendition conditions; this is a bounded sequential
observation, not controlled throughput attribution or sustained-pair acceptance.
Cleanup, source identity and every saved encrypted record were preserved; the
original 16 picker assignments were verified after returning to the app. The
installed app/test APK hashes matched the verified build. All 2,266 JVM cases,
lint, isolation and app/test/unsigned-release assemblies passed, with stable
development signing and 16 KiB alignment.

A following 390.0-second observation used the configured ABEMA News live stream
through its saved Windscribe route and the same Twitch replay through System
network, with both local builders stopped. Telemetry began at Watch, excluding
older session context; each feed reported its first video frame. ABEMA had 74
playing samples over 372.612 seconds, with content time advancing 368.190 seconds.
Twitch had 64 playing and 11 buffering samples over 377.576 seconds, advancing
289.381 seconds. Its final video remained 852×480 AVC at about 1,409 kbit/s,
against a final SDK estimate of 1,055 kbit/s. No terminal feed failure occurred,
but intermittent buffering remains unresolved in #297; sampled playing states
do not prove continuous acoustic or physical-device output.

Normal Back/Stop returned to the picker, all 16 original assignments were
restored, and every saved encrypted-record digest still matched. No uncertain
cleanup, recovery block or release-timeout projection was observed. ABEMA
retained three unknown transport-open observations while continuing; Stop added
the separate admission-refused/inactive observations. Their distinction does
not establish a transport root cause. Exit country was not independently
remeasured, and no provider page, private request or protected media was captured.

## Twitch protected-store read-key reuse

Twitch catalog admission retains its registry/profile store wrappers and opts
those stores and its catalog grant store into opaque Android Keystore key-handle
reuse. Other stores keep the default behavior. Each read still opens the current
bounded AtomicFile envelope and performs fresh authenticated decryption with a
new Cipher and its original binding. Plaintext, envelopes, Ciphers, grants and
admission results are not cached. Route ownership, expiry, Forget and adoption
checks retain their existing ordering and frequency.

Key loading is serialized per store; file reads and decryption are independent.
File/key/decrypt failures invalidate the applicable handle and propagate the
original failure without retry or key creation. A failed older read cannot evict
a newer handle. Writes use their normal fresh key lookup and invalidate the
read handle before and after the operation, including a failed write.

The opt-in AndroidKeyStore fixture uses only newly reserved UUID bindings with
synthetic payloads. It checks cross-wrapper replacement reads, ciphertext/AAD
refusal, deleted-key and alias-replacement refusal, and concurrent reads. It
removes only proven-owned fixture files/aliases and compares the pre-existing
encrypted records and alias inventory afterward. Uncertain ownership/cleanup
retains the affected fixture for investigation. With playback stopped on the
verified persistent ordinary-debug emulator:

```sh
adb -s emulator-5580 shell am instrument -w -r \
  -e class net.fstab.tachiai.platform.storage.PrivateReadKeyReuseDeviceTest \
  -e privateReadKeyReuse true \
  net.fstab.tachiai.test/androidx.test.runner.AndroidJUnitRunner
```

Without the opt-in it skips before private storage access. JVM race tests do not
establish Android Keystore revocation behavior; the device fixture and an actual
media-read observation are needed before making a device/performance claim.

### Key-handle observation — 2026-10-18

The fixture passed on persistent `tachiai-dev`, Android 16/API 36 x86-64, in
0.170 seconds (149 ms inside its observation). Replacement reads, corruption/AAD
refusal, deleted-key refusal without regeneration, stale-store refusal after
alias replacement and 48 concurrent reads passed. All original encrypted-file
digests and the full alias inventory matched afterward; no fixture resource
remained. This establishes behavior only on this emulator/OS. Earlier Android
Keystore implementations and physical secure hardware remain unmeasured.

With the same existing replay, System network route and 1,409,040 bit/s selected
video, the configured-read fixture passed in 48.595 seconds after this change.
Across 159 samples, 130 reported playing and 29 buffering; position advanced
from zero to 32.404 seconds. It delivered 5,950,200 media bytes in 1,401 positive
input reads. Its 2,806 body-admission checks took 38.779 seconds (13.820 ms mean),
compared with 15.875 ms mean before the change. Underlying input reads took
0.113 seconds; total datasource reads took 39.002 seconds. Admission still
accounted for approximately 99.4% of that measured time. The final SDK bandwidth
estimate was 1,185,947 bit/s. Frame rendering, confirmed cleanup and unchanged
encrypted records/source identity passed, with no observed terminal failure.

This is a modest measured reduction in per-check cost; it does not resolve the
replay's buffering. The observations occurred sequentially, without a controlled
network or CPU benchmark. Request sizes, buffering samples and bandwidth may
vary between runs. The remaining repeated fresh-decryption cost and the
independent ABEMA errors remain under #297.

The actual configured ABEMA News live/Windscribe and Twitch replay/System-network
comparison first encountered the already observed ABEMA preparation refusal
(`CACHED_ABEMA_SOURCE_PREPARE`, `ILLEGAL_STATE`), before native player creation.
Twitch continued for the 390.6-second observation: 52 playing/23 buffering
samples, advancing 266.880 seconds across a 379.426-second sample span. This
attempt does not establish two-feed playback.

A fresh attempt with the same pair prepared both feeds. Its 390.4-second
observation included preceding context already playing; 85 samples per feed
spanned about 430 seconds. ABEMA reported playing in all 85 samples and its
content timestamp advanced 423.327 seconds. Twitch reported 28 playing/57
buffering samples and advanced 146.858 seconds, ending at a 483 kbit/s SDK
bandwidth estimate. Neither feed reported a terminal failure. ABEMA recorded two
unknown transport-open exceptions while continuing to play, so that cause
remains unresolved. No acoustic or physical-device acceptance is claimed.

Both attempts ended through normal Back/Stop. All 16 original picker assignment
controls were restored, every saved encrypted-file digest still matched the
pre-installation baseline, and no uncertain-cleanup/recovery block was reported.
No browser/account reset, credentials or protected-media capture was involved.

## Optional native media read timing

[Issue #326](https://github.com/altendky/tachiai/issues/326) adds an explicitly
owned, in-memory observer for actual native datasource boundaries. It aggregates
fixed manifest/media/other counters for setup, admission checks, response headers,
input acquisition and body reads, with requested/returned byte counts and five
fixed size bins. The observation window is at most 90 seconds. It retains no
URI, request identifier, headers, body, provider text, credentials or exception.
It does not write the failure journal or add telemetry.

The observer is absent by default, with no diagnostic clock or aggregation work.
Clock/observer failures disable evidence rather than changing playback. Existing
admission, expiry, revocation, route, timeout and cleanup checks remain in their
original order. Summed durations may exceed wall time across concurrent loaders;
they are not a utilization percentage. Setup residual time excludes separately
measured admission/header/input-acquisition spans. A boundary completing after
the fixed window is omitted, so in-flight requests may remain unclassified.

The opt-in `TwitchMediaReadCostDeviceTest` uses the production configured replay
startup receipt, preparation, route and player. It adds no per-read transport
wrappers or protected-store checks that would distort input-read wall time.
It requires ordinary debug on verified persistent `tachiai-dev`, one Twitch
instance whose entire route pool is System network, and an existing supported
configured replay. It freezes that source without writing assignments, mutes
playback, samples numeric state/quality for at most 40 seconds and reserves
bounded cleanup time within its original 90-second operation. It verifies saved
encrypted records and source identity afterward. No provider UI or protected
media is captured.

With playback stopped and the verified ordinary app/test APKs installed:

```sh
adb -s emulator-5580 shell am instrument -w -r \
  -e class net.fstab.tachiai.provider.twitch.TwitchMediaReadCostDeviceTest \
  -e twitchMediaReadCosts true \
  net.fstab.tachiai.test/androidx.test.runner.AndroidJUnitRunner
```

Without the opt-in, it skips before private reads. A completed diagnostic can
describe a playback failure if bytes and valid timings were captured and cleanup
and preservation passed; it is not a positive playback-acceptance test. Compare
admission and input-read durations plus returned sizes to distinguish local
admission amplification from transport wait. Header/setup costs, player samples
and bandwidth estimates provide additional context. Input-read wall time includes
the platform stream implementation, not just network transfer; admission time
includes storage and lock waits, not just CPU. Requested bytes are buffer demand,
not delivered traffic. Enabled-observer overhead is included in the timings.
One muted replay observation
does not establish sustained two-feed, acoustic or physical-device behavior.

### Configured replay observation — 2026-10-18

The first opt-in run passed in 48.686 seconds on persistent `tachiai-dev`,
Android 16/API 36, x86-64, with WebView 133.0.6943.137 installed. This was one
existing configured Twitch replay through its saved catalog connection and
explicit System network route; the production read-only startup validation was
used. No ABEMA feed or provider WebView ran in this fixture. Exit-country
geolocation was not independently measured, and audio was muted.

The player rendered a frame. Across 160 samples over approximately 40 seconds,
111 reported playing and 49 buffering; position advanced from zero to 27.860
seconds. Selected video was 1,409,040 bit/s and the final SDK bandwidth estimate
was 994,315 bit/s. These are bounded samples, not continuous output proof.

The media datasource returned 4,872,862 bytes in 1,231 positive input reads,
averaging about 3,958 bytes per read. Its 2,465 body-admission checks took
39.132 seconds, compared with 0.103 seconds inside underlying input reads and
39.327 seconds of total datasource-read time. Admission therefore accounted for
about 99.5% of measured body-read time in this run. Returned-size bins were 45
at up to 256 bytes, 136 at 257–1,024, 472 at 1,025–4,096 and 578 at 4,097–16,384;
none exceeded 16,384. Media response headers took 0.857 seconds; four manifest
opens took 2.028 seconds in response headers and 0.495 seconds in body admission.

This establishes local admission work as the immediate measured bottleneck for
that replay, without proving pure CPU cost, unrestricted CDN capacity or the
cause of the separate ABEMA errors in #297. Normal Stop can contribute aborted
requests/read failures to the final counters; the fixture reported no terminal
playback failure. Cleanup completed, the exact configured source and provider
were preserved, every encrypted-record digest matched across update and probe,
and the launcher was restored. No account renewal, assignment write, credentials,
private request data or media capture was involved. #297 remains open for the
appropriate optimization and wider sustained/failure-recovery acceptance.

## Verification boundaries

JVM tests inject multiple cleanup exceptions, malformed/secret-bearing exception
metadata, cancellation, broken diagnostic sinks, repetition/capacity/age limits,
concurrent writers and reopened journals. Media-request tests retain the
original thrown error while recording the exact disconnect stage.

Device tests use actual no-backup storage, the Activity disposer and replacement
Activity instances' reporter restoration.
Run `FailureDiagnosticsDeviceTest` only on an explicitly verified disposable
emulator: it replaces the diagnostic journal. Never run it or other resetting
instrumentation on `tachiai-dev` or the phone. These fixtures establish evidence
retention and safe error handling, not the cause of a provider playback failure.

ProviderFailureCategoryDeviceTest instead uses synthetic errors and an in-memory
reporter only, with no journal or application-store access. It can verify the
typed category capture and codec on persistent tachiai-dev without replacing its
evidence or accessing a provider.

`AbemaCachedHelperDiagnosticsDeviceTest` likewise uses synthetic responses,
blocked networking and an in-memory reporter in the explicitly targeted cached
process. It does not replace the journal or saved stores. Default instrumentation
skips before WebView access unless that process guard is satisfied.

On 2026-10-11, the integrated change passed 2,030 JVM tests in 243 reports,
56 direct owned-JavaScript cases, 10 collector cases, lint, release isolation,
instrumentation compilation and app/test assembly. Both APK certificates matched
the shared debug identity. Two networking-blocked diagnostic fixtures passed on
persistent tachiai-dev in 0.558 seconds, using in-memory reporting rather than
replacing its journal. They established timer/pre-HTTP categories, first-evidence
preservation, no hostile error-property inspection and refusal of late revival.

One explicitly selected historical News regression on the same build rendered a
native frame and advanced position in 17.042 seconds. The AVD was Android 16/API
36, Google APIs x86-64, WebView 133.0.6943.137; it used the existing ABEMA instance's
saved route with a fresh anonymous guest and native CDM. Region was not remeasured
and audio was muted. Cleanup and captured owner were confirmed, and protected
encrypted records remained byte-for-byte equal to the pre-install baseline.
The temporary cached-process test override was removed; ordinary instrumentation
was rebuilt, certificate/packaged targeting verified, reinstalled with data
preserved and the launcher restored. Final app SHA-256 is
`8456afcd40835cbbd9feb9533b7074342d59de46afecbcbdcd881b5d1257299d`;
ordinary test APK SHA-256 is
`d8d9e10fb6818e75f36277ec0252d82968a12fb08460fd49d9a3791f8427f284`.
This establishes bounded diagnostic fixtures and a success regression, not the
cause of the earlier transient failure or sustained/physical-device behavior.
