#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Opt-in actual-pinned SYNTHETIC test. Never downloads assets or calls providers."""
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("web_abema_episode_source", ROOT / "apps/web/scripts/abema-backend-episode-source.py")
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)


@unittest.skipUnless(os.environ.get("TACHIAI_ABEMA_PINNED_TEST") == "1", "Explicit local pinned-factory test only")
class PinnedTests(unittest.TestCase):
    def test_actual_pinned_browser_selection_and_complete_private_binding(self):
        assets = Path(os.environ["TACHIAI_ABEMA_PINNED_ASSETS"])
        # Check all four pins before any downloaded factory or browser executes.
        verified = []
        for name, _, size, digest in module.bootstrap.ASSETS:
            raw = (assets / name).read_bytes()
            self.assertTrue(len(raw) == size and module.bootstrap.hashlib.sha256(raw).hexdigest() == digest, "Public bundle pin mismatch")
            verified.append((name, raw))
        attempt = module.EpisodeAttempt(io.StringIO())
        navigator = attempt.browser_snapshot()  # Existing owned fresh headed blank Chrome and cleanup verifier.
        with tempfile.TemporaryDirectory(prefix="episode-pinned-") as directory:
            root = Path(directory); runtime, config = root / "runtime", root / "docker-config"
            runtime.mkdir(mode=0o755); config.mkdir(mode=0o700)
            for name, raw in verified: (runtime / name).write_bytes(raw); (runtime / name).chmod(0o444)
            scripts = ROOT / "apps/web/scripts"
            for target, original in (("abema-guest-helper.cjs", scripts / "abema-guest-helper.cjs"),
                ("episode-proof.cjs", scripts / "abema-episode-source-helper.cjs"),
                ("abema-episode-source-helper.cjs", Path(__file__).with_name("web-abema-episode-source-pinned.cjs"))):
                shutil.copyfile(original, runtime / target); (runtime / target).chmod(0o444)
            container = module.bootstrap.OwnedContainer(runtime, config, deadline=attempt.deadline,
                helper="abema-episode-source-helper.cjs", private=True)
            with container.opened() as identity:
                result = container.command("start", "--attach", "--interactive", identity,
                    timeout=20, limit=65536, input_data=json.dumps(navigator).encode())
                self.assertTrue(result.code == 0 and not result.stderr, "Pinned synthetic helper refused")
                packet = module.bootstrap.strict_json(result.output, 65536, max_nodes=16000, max_depth=32)
                self.assertTrue(isinstance(packet, dict) and set(packet) == {"cases"} and len(packet["cases"]) == 3, "Pinned synthetic wire refused")
                selections = []
                for item in packet["cases"]:
                    for phase, key in (("metadata", "dto"), ("selection", "binding")):
                        self.assertTrue("outcome" not in item[key], "Actual pinned phase refused")
                        module.validate_binding(item[key], phase, "a" * 32, item["metadata"], item[key]["expiresAtMs"],
                            item["gateway"] if phase == "selection" else None, "SYNTHETIC_TOKEN")
                    selections.append(item["binding"]["drm"])
                self.assertEqual(selections, ["WIDEVINE", "WIDEVINE", "CUSTOM"])


if __name__ == "__main__": unittest.main()
