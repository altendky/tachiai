#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Inventory compiler dependency sidecars; flag notices for human review.

This does not decide legal compatibility or establish an exhaustive SBOM.
Run only on the disposable native build path, after compilation has finished.
"""
import json
import shlex
import sys
from pathlib import Path

build = Path(sys.argv[1]).resolve()
output = Path(sys.argv[2]).resolve()
if not build.is_dir() or not output.is_relative_to(build):
    raise SystemExit("Require a completed build and an output inside it")
files = set()
sidecars = 0
excluded = {"tests", "testsuite", "examples", "demos", "tools", "doc", "src", "contrib", "fuzz"}
for dep in build.rglob("*"):
    if dep.suffix not in {".d", ".Po", ".Plo"} or "prefix" in dep.relative_to(build).parts:
        continue
    if "conftest" in dep.name or any(part in excluded for part in dep.relative_to(build).parts):
        continue
    content = dep.read_text(errors="replace").replace("\\\n", " ")
    if ":" not in content or content.startswith("# dummy"):
        continue
    base = dep.parent.parent if dep.parent.name in {".libs", ".deps"} else dep.parent
    first_rule = content.split("\n", 1)[0].split(":", 1)[1]
    dependencies = shlex.split(first_rule)
    if not dependencies:
        continue
    sidecars += 1
    for name in dependencies:
        path = (base / name).resolve()
        if path.is_file() and path.suffix in {".h", ".c", ".inc"}:
            files.add(path)
entries = []
suspects = []
external = []
for path in sorted(files):
    if not path.is_relative_to(build):
        external.append(str(path))
        continue
    notice = path.read_text(errors="replace")[:10000]
    relative = str(path.relative_to(build))
    lesser = "GNU Lesser General Public License" in notice or "GNU Library General Public License" in notice
    gpl = "GNU General Public License" in notice
    if gpl and not lesser:
        suspects.append(relative)
    entries.append({"file": relative, "has_lesser_notice": lesser, "has_gpl_notice": gpl})
report = {"dependency_sidecars": sidecars, "source_and_header_count": len(entries),
    "potential_gpl_only_notices": suspects, "files": entries, "external_sdk_headers": external,
    "limits": "Heuristic notice flags require human review; excluded executable/test directories; no legal conclusion."}
output.write_text(json.dumps(report, indent=2) + "\n")
print(json.dumps({key: report[key] for key in ("dependency_sidecars", "source_and_header_count", "potential_gpl_only_notices")}))
