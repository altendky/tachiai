#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Require the active build compiler to match the repository's exact pin."""

import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
import tomllib


ROOT = Path(__file__).resolve().parents[1]


def check(tool: str) -> None:
    with (ROOT / "mise.toml").open("rb") as source:
        declared = tomllib.load(source)["tools"][tool]
    if not isinstance(declared, str) or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", declared):
        raise ValueError(f"{tool}: expected an exact release version in mise.toml")

    if tool == "rust":
        with (ROOT / "rust-toolchain.toml").open("rb") as source:
            channel = tomllib.load(source)["toolchain"]["channel"]
        if channel != declared:
            raise ValueError("rust: mise.toml and rust-toolchain.toml pins disagree")
        output = subprocess.check_output(["rustc", "--version"], cwd=ROOT, text=True).strip()
        words = output.split()
        if len(words) < 2 or words[0] != "rustc":
            raise ValueError("rust: unrecognized rustc version output")
        actual, expected = words[1], declared
    else:
        # Check the selected compiler itself, never an automatically downloaded
        # replacement selected by the caller's module or workspace.
        environment = dict(os.environ, GOTOOLCHAIN="local", GOWORK="off")
        actual = subprocess.check_output(
            ["go", "env", "GOVERSION"], cwd=ROOT, env=environment, text=True
        ).strip()
        expected = "go" + declared
    if actual != expected:
        raise ValueError(f"{tool}: active compiler is {actual}; repository requires {expected}")
    print(f"{tool}: active compiler matches repository pin {expected}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tool", choices=("rust", "go"))
    args = parser.parse_args()
    try:
        check(args.tool)
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        print(f"Build toolchain check failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
