#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Exercise compiler guards and real build entry points without native builds."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]


class BuildToolchainTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="build-toolchain-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / "repository with spaces"
        self.scripts = self.root / "scripts"
        self.scripts.mkdir(parents=True)
        for name in ("check-build-toolchain.py", "build-core-bindings.sh", "build-android-routebridge.sh"):
            shutil.copyfile(ROOT / "scripts" / name, self.scripts / name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.calls = self.root / "calls.jsonl"
        self.sdk = self.root / "sdk"
        ndk = self.sdk / "ndk/30.0.16248370"
        ndk.mkdir(parents=True)
        (ndk / "source.properties").write_text("synthetic SDK; never compiled\n")
        self.module = self.root / "apps/android/routebridge/go.mod"
        self.module.parent.mkdir(parents=True)
        self.module.write_text("module example.invalid/fixture\n\ngo 1.26.0\n")
        self.pins()
        self.environment = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
                                ANDROID_HOME=str(self.sdk), TEST_CALLS=str(self.calls),
                                TEST_RUST="1.98.1", TEST_GO="go1.27.2",
                                GOTOOLCHAIN="go1.99.0+auto", GOWORK="/untrusted/caller/go.work")
        self.executable("rustc", 'print("rustc " + os.environ["TEST_RUST"] + " (fixture 2026-11-06)")')
        self.executable("cargo", 'record(); sys.exit(77)')
        self.executable("go", '''
if sys.argv[1:] == ["env", "GOVERSION"]:
    assert os.environ["GOTOOLCHAIN"] == "local"
    assert os.environ["GOWORK"] == "off"
    print(os.environ["TEST_GO"])
else:
    record()
    sys.exit(77)
''')
        self.executable("uv", '''
assert sys.argv[1:3] == ["run", "--no-project"]
os.execv(sys.executable, [sys.executable, *sys.argv[3:]])
''')

    def executable(self, name, body):
        script = self.bin / name
        script.write_text(f"#!{sys.executable}\n" + '''
import json, os, sys
def record():
    with open(os.environ["TEST_CALLS"], "a") as output:
        output.write(json.dumps(sys.argv) + "\\n")
''' + body + "\n")
        script.chmod(0o755)

    def pins(self, rust="1.98.1", go="1.27.2", channel=None):
        (self.root / "mise.toml").write_text(f'[tools]\nrust = "{rust}"\ngo = "{go}"\n')
        (self.root / "rust-toolchain.toml").write_text(f'[toolchain]\nchannel = "{channel or rust}"\n')

    def guard(self, tool):
        return subprocess.run([sys.executable, str(self.scripts / "check-build-toolchain.py"), tool],
                              cwd=self.module.parent, env=self.environment,
                              capture_output=True, text=True, timeout=10)

    def build(self, tool):
        name = "build-core-bindings.sh" if tool == "rust" else "build-android-routebridge.sh"
        return subprocess.run(["bash", str(self.scripts / name)], cwd=self.module.parent,
                              env=self.environment, capture_output=True, text=True, timeout=10)

    def test_current_and_updated_pins_allow_real_build_entrypoints(self):
        for rust, go in (("1.98.1", "1.27.2"), ("1.99.1", "1.27.3")):
            self.pins(rust=rust, go=go)
            self.environment.update(TEST_RUST=rust, TEST_GO="go" + go)
            for tool in ("rust", "go"):
                with self.subTest(rust=rust, go=go, tool=tool):
                    result = self.build(tool)
                    self.assertEqual(result.returncode, 77, result.stdout + result.stderr)
                    calls = [json.loads(line) for line in self.calls.read_text().splitlines()]
                    self.assertEqual(Path(calls[-1][0]).name, "cargo" if tool == "rust" else "go")
        self.assertEqual(self.module.read_text(), "module example.invalid/fixture\n\ngo 1.26.0\n")

    def test_wrong_compilers_stop_before_build_or_download(self):
        self.pins(rust="1.99.1", go="1.27.3")
        for tool in ("rust", "go"):
            with self.subTest(tool=tool):
                result = self.build(tool)
                self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
                self.assertIn("repository requires", result.stderr)
                self.assertFalse(self.calls.exists())

    def test_rust_declarations_must_agree(self):
        self.pins(rust="1.99.1", channel="1.98.1")
        result = self.guard("rust")
        self.assertEqual(result.returncode, 1)
        self.assertIn("pins disagree", result.stderr)

    def test_nonexact_declarations_fail_closed(self):
        for tool, value in (("rust", "stable"), ("go", "1.27"), ("go", "latest")):
            with self.subTest(tool=tool, value=value):
                self.pins(**{tool: value})
                result = self.guard(tool)
                self.assertEqual(result.returncode, 1)
                self.assertIn("exact release version", result.stderr)

    def test_missing_declaration_reports_failure(self):
        (self.root / "mise.toml").write_text("[tools]\n")
        self.assertEqual(self.guard("go").returncode, 1)

    def test_modified_toolchain_version_suffix_is_rejected(self):
        for tool, key, value in (("rust", "TEST_RUST", "1.98.1-custom"),
                                 ("go", "TEST_GO", "go1.27.2-custom")):
            with self.subTest(tool=tool):
                self.environment[key] = value
                result = self.guard(tool)
                self.assertEqual(result.returncode, 1)
                self.assertIn("repository requires", result.stderr)


if __name__ == "__main__":
    unittest.main()
