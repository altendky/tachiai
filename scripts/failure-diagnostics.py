#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Validate bounded app-private diagnostic TSV from stdin before displaying it.

This script does not access devices. Pipe only the explicitly selected app's
failure-diagnostics.tsv into it. Vocabulary comes from fixed trusted files in
the matching checkout; unknown schema/fields fail closed without echoing input.
"""

from pathlib import Path
import re
import sys

MAX_BYTES = 64 * 1024
MAX_RECORDS = 128
HEADER = "tachiai-failure-diagnostics-v1"
ROOT = Path(__file__).resolve().parents[1]
JOURNAL_SOURCE = ROOT / "apps/android/app/src/debug/kotlin/net/fstab/tachiai/platform/diagnostics/FailureJournal.kt"
REASON_SOURCE = ROOT / "apps/android/app/src/main/kotlin/net/fstab/tachiai/presentation/PrototypeFeedFailure.kt"
IDENTIFIER = re.compile(r"[0-9a-f]{32}")


class InvalidJournal(ValueError):
    """A fixed error category; it never carries source input."""


def reject():
    raise InvalidJournal("JOURNAL_INVALID")


def enum_values(source, name):
    # These sources are local reviewable schema, never device-provided data.
    match = re.search(r"internal enum class " + re.escape(name) + r"(?:\([^\n]*\))?\s*\{([^}]*)\}", source, re.DOTALL)
    if match is None:
        reject()
    body = re.sub(r'"(?:\\.|[^"\\])*"', '""', match.group(1))
    body = re.sub(r"//[^\n]*", "", body)
    values = set(re.findall(r"\b([A-Z][A-Z0-9_]*)\s*(?=\(|,|$)", body, re.MULTILINE))
    if not values or len(values) > 256:
        reject()
    return values


def bounded_source(path):
    with path.open("rb") as stream:
        data = stream.read(128 * 1024 + 1)
    if len(data) > 128 * 1024:
        reject()
    return data.decode("utf-8")


def vocabulary():
    source = bounded_source(JOURNAL_SOURCE)
    result = {name: enum_values(source, name) for name in (
        "FailureStage", "FailureSlot", "FailureThread", "FailureRelation",
        "FailureCategory", "FailureOwner", "FailureMethod",
    )}
    result["PrototypeFailureReason"] = enum_values(bounded_source(REASON_SOURCE), "PrototypeFailureReason") | {"NONE"}
    return result


def decimal(value, maximum, minimum=0, digits=13):
    if not re.fullmatch(r"[0-9]{1," + str(digits) + "}", value):
        reject()
    number = int(value)
    if not minimum <= number <= maximum:
        reject()
    return number


def validate(data, words=None):
    if len(data) > MAX_BYTES or any(value < 9 or value > 126 for value in data):
        reject()
    lines = data.decode("ascii").split("\n")
    if not lines or lines[0] != HEADER or lines[-1] != "" or len(lines) > MAX_RECORDS + 2:
        reject()
    words = vocabulary() if words is None else words
    records = []
    ids = set()
    keys = set()
    roots = {}
    for line in lines[1:-1]:
        fields = line.split("\t")
        if len(fields) != 13:
            reject()
        timestamp = decimal(fields[0], 9_999_999_999_999)
        record_id, root_id, session_id = fields[1:4]
        if any(IDENTIFIER.fullmatch(value) is None for value in (record_id, root_id, session_id)):
            reject()
        for column, name in ((4, "FailureStage"), (5, "FailureSlot"), (6, "FailureRelation"),
                             (8, "FailureThread"), (9, "FailureCategory"), (12, "PrototypeFailureReason")):
            if fields[column] not in words[name]:
                reject()
        decimal(fields[7], 65_535, minimum=1, digits=5)
        causes = fields[10].split(",") if fields[10] else []
        if len(causes) > 4 or any(value not in words["FailureCategory"] for value in causes):
            reject()
        frames = fields[11].split(",") if fields[11] else []
        if len(frames) > 8:
            reject()
        safe_frames = []
        for frame in frames:
            parts = frame.split(":")
            if len(parts) != 3 or parts[0] not in words["FailureOwner"] or parts[1] not in words["FailureMethod"]:
                reject()
            safe_frames.append((parts[0], parts[1], decimal(parts[2], 100_000, digits=6)))
        key = (session_id, fields[4], fields[5], fields[6] == "BLOCKED",
               fields[9], tuple(causes), tuple(safe_frames), fields[12])
        if record_id in ids or key in keys:
            reject()
        ids.add(record_id)
        keys.add(key)
        if fields[6] == "FIRST":
            if session_id in roots or root_id != record_id:
                reject()
            roots[session_id] = (record_id, timestamp)
        elif root_id == record_id:
            reject()
        if fields[6] == "BLOCKED" and (fields[9] != "NONE" or causes or frames or fields[12] != "NONE"):
            reject()
        records.append((session_id, root_id, timestamp))
    for session_id, root_id, timestamp in records:
        root = roots.get(session_id)
        if root is None or root[0] != root_id or root[1] > timestamp:
            reject()
    return data


def main(stdin=None, stdout=None, stderr=None):
    stdin = sys.stdin.buffer if stdin is None else stdin
    stdout = sys.stdout.buffer if stdout is None else stdout
    stderr = sys.stderr if stderr is None else stderr
    try:
        data = stdin.read(MAX_BYTES + 1)
        validated = validate(data)
    except Exception:
        stderr.write("JOURNAL_INVALID\n")
        return 1
    stdout.write(validated)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
