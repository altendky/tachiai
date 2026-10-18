#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Retain registry license/notice texts from the locked core dependency graph."""

import json
import shutil
import subprocess
from pathlib import Path


def main() -> None:
    root = Path(__file__).resolve().parents[2]
    metadata = json.loads(
        subprocess.check_output(
            [
                "cargo", "metadata", "--locked", "--format-version", "1",
                "--filter-platform", "aarch64-linux-android",
            ],
            cwd=root,
            text=True,
        )
    )
    packages = {package["id"]: package for package in metadata["packages"]}
    nodes = {node["id"]: node for node in metadata["resolve"]["nodes"]}
    pending = [
        package["id"] for package in packages.values()
        if package["name"] in {"tachiai-core-ffi", "tachiai-core-web"}
    ]
    selected = set()
    while pending:
        identity = pending.pop()
        if identity in selected:
            continue
        package = packages[identity]
        if any("proc-macro" in target["kind"] for target in package["targets"]):
            continue
        selected.add(identity)
        for dependency in nodes[identity]["deps"]:
            if any(kind["kind"] is None for kind in dependency["dep_kinds"]):
                pending.append(dependency["pkg"])

    output = root / "build/core/licenses/tachiai-core-licenses/rust"
    # Replace only this generator's directory, never another component's assets.
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True)
    notices = [
        "Tachiai Rust core dependency notices", "",
        "Versions come from Cargo.lock. Upstream dependency sources are unmodified.",
        "The conservative dependency set may include build-tool code eliminated",
        "from a final native binary. The listed upstream sources and retained",
        "license texts apply to those components, not to Tachiai's own code.", "",
    ]
    for identity in sorted(selected, key=lambda value: (packages[value]["name"], packages[value]["version"])):
        package = packages[identity]
        if package["source"] is None:
            continue
        if not package["source"].startswith("registry+"):
            raise RuntimeError(f"Review source and notices for {package['name']}")
        source = Path(package["manifest_path"]).parent
        texts = {
            path for path in source.iterdir()
            if path.is_file()
            and path.name.upper().startswith(("LICENSE", "COPYING", "COPYRIGHT", "NOTICE"))
        }
        if package["license_file"]:
            explicit = (source / package["license_file"]).resolve()
            if not explicit.is_relative_to(source.resolve()):
                raise RuntimeError(f"License path outside crate: {package['name']}")
            texts.add(explicit)
        if not texts and package["license"] == "MPL-2.0" and package["name"].startswith("uniffi"):
            texts.add(root / "packages/core-ffi/licenses/UNIFFI-MPL-2.0.txt")
        if not texts:
            raise RuntimeError(f"No retained license text for {package['name']} {package['version']}")
        target = output / f"{package['name']}-{package['version']}"
        target.mkdir()
        for text in sorted(texts):
            shutil.copyfile(text, target / text.name)
        notices.extend([
            f"{package['name']} {package['version']}",
            f"Declared license: {package['license']}",
            f"Source: https://crates.io/api/v1/crates/{package['name']}/{package['version']}/download",
            f"Repository: {package['repository'] or 'See source archive'}",
            "License and notice texts: " + target.name + "/", "",
        ])
    notices.extend([
        "UniFFI's unmodified MPL-2.0 source is available from the exact crate",
        "source archive links above and https://github.com/mozilla/uniffi-rs/tree/v0.32.2.",
        "The full MPL-2.0 text retained for UniFFI comes from:",
        "https://github.com/mozilla/uniffi-rs/blob/v0.32.2/LICENSE", "",
    ])
    (output / "THIRD-PARTY-NOTICES.txt").write_text("\n".join(notices))
    own_output = output.parent / "tachiai"
    own_output.mkdir(exist_ok=True)
    for name in ("LICENSE-MIT", "LICENSE-APACHE"):
        shutil.copyfile(root / name, own_output / name)

    sysroot = Path(subprocess.check_output(["rustc", "--print", "sysroot"], text=True).strip())
    rust_docs = sysroot / "share/doc/rust"
    rust_output = output / "standard-library"
    rust_output.mkdir()
    # The pinned Rust toolchain ships attribution for linked standard-library
    # and compiler runtime code even when installed with the minimal profile.
    shutil.copyfile(rust_docs / "COPYRIGHT-library.html", rust_output / "COPYRIGHT-library.html")
    shutil.copytree(rust_docs / "licenses", rust_output / "licenses")
    print(f"Retained license/notice texts for {sum(packages[key]['source'] is not None for key in selected)} Rust dependencies")


if __name__ == "__main__":
    main()
