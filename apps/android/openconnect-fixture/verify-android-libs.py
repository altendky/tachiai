#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Inspect the complete replaceable Android closure without changing ELF bytes."""

import hashlib
import re
import subprocess
import sys
from pathlib import Path

package, ndk, abi = Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve(), sys.argv[3]
if abi not in {"aarch64", "x86_64"}:
    raise SystemExit("Require aarch64 or x86_64")
tools = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
names = {
    f"lib{name}.so"
    for name in ("gmp", "nettle", "hogweed", "gnutls", "xml2", "z", "openconnect", "tachiai_openconnect")
}
system = {"libc.so", "libdl.so", "libm.so", "liblog.so"}
machine = {"aarch64": "AArch64", "x86_64": "Advanced Micro Devices X86-64"}[abi]
reports = {f"{name}.elf.txt" for name in names}
if {path.name for path in package.glob("*.so*")} - reports != names:
    raise SystemExit("Require exactly eight unversioned Android shared libraries")
checksums = []
for name in sorted(names):
    library = package / name
    if library.is_symlink() or not library.is_file():
        raise SystemExit(f"Require an ordinary packaged file: {name}")
    elf = subprocess.check_output(
        [str(tools / "llvm-readelf"), "--file-header", "--dynamic", "--program-headers", "--dyn-syms", "--wide", str(library)],
        text=True,
    )
    needed = set(re.findall(r"\(NEEDED\).*\[(.*?)\]", elf))
    soname = re.findall(r"\(SONAME\).*\[(.*?)\]", elf)
    machines = re.findall(r"^\s*Machine:\s*(.*?)\s*$", elf, flags=re.M)
    loads = [line.split()[-1] for line in elf.splitlines() if line.strip().startswith("LOAD")]
    if machines != [machine] or needed - names - system or soname != [name]:
        raise SystemExit(f"ABI/dependency/SONAME check failed: {name}")
    if "(RUNPATH)" in elf or "(RPATH)" in elf:
        raise SystemExit(f"Search path check failed: {name}")
    if not loads or any(alignment != "0x4000" for alignment in loads):
        raise SystemExit(f"16KiB LOAD alignment check failed: {name}")
    # NDK30's arm64 compiler runtime uses optional WEAK memfd_create as an
    # API-level presence test and branches around it when absent. A strong
    # reference would instead prevent loading on API26.
    if re.search(r"\bGLOBAL\s+\S+\s+UND\s+memfd_create(?:@|\s|$)", elf):
        raise SystemExit(f"API30 memfd_create dependency refused: {name}")
    (package / f"{name}.elf.txt").write_text(elf)
    checksums.append(f"{hashlib.sha256(library.read_bytes()).hexdigest()}  {name}\n")
(package / "SHA256SUMS").write_text("".join(checksums))
print(f"PASS: {abi} eight-so closure, SONAME/dependencies, no RPATH/RUNPATH, API26 memory symbol and 16KiB LOAD checks: {package}")
