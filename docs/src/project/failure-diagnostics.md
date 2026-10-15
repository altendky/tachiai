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
