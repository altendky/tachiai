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
