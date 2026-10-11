#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Provider-free collector tests; never access devices or app-private storage."""

import importlib.util
import io
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("failure_diagnostics", Path(__file__).resolve().parents[1] / "failure-diagnostics.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class CollectorTests(unittest.TestCase):
    def row(self, **changes):
        values = ["100000", "1" * 32, "1" * 32, "2" * 32, "ROUTE_CREATE", "NONE", "FIRST", "1", "WORKER", "IO", "", "ROUTE_SESSION:CLOSE:77", "NONE"]
        for index, value in changes.items():
            values[int(index)] = value
        return "\t".join(values)

    def journal(self, rows):
        return (module.HEADER + "\n" + "\n".join(rows) + ("\n" if rows else "")).encode("ascii")

    def test_valid_first_secondary_blocked_and_empty_history(self):
        rows = [self.row(), self.row(**{"1": "3" * 32, "4": "PLAYER_RELEASE", "6": "SECONDARY", "9": "ILLEGAL_STATE"}),
                self.row(**{"1": "4" * 32, "4": "VIEWER_BLOCKED", "6": "BLOCKED", "7": "40", "9": "NONE", "11": ""})]
        data = self.journal(rows)
        self.assertEqual(module.validate(data), data)
        self.assertEqual(module.validate(self.journal([])), self.journal([]))

    def test_matching_checkout_contains_expected_closed_schema(self):
        words = module.vocabulary()
        self.assertIn("NETWORK_ON_MAIN_THREAD", words["FailureCategory"])
        self.assertIn("VIEWER_BLOCKED", words["FailureStage"])
        self.assertIn("RECOVERY_RESTART", words["FailureStage"])
        self.assertIn("NATIVE_DASH_PLAYER", words["FailureOwner"])
        self.assertIn("HTTP_REJECTED", words["PrototypeFailureReason"])
        self.assertEqual(words["FailureRelation"], {"FIRST", "SECONDARY", "BLOCKED"})


    def test_extended_provider_categories_are_fixed_and_round_trip_without_schema_changes(self):
        expected = {
            "ABEMA_CHANNEL_INVALID_RESPONSE",
            "ABEMA_CHANNEL_RESPONSE_LIMIT",
            "ABEMA_CHANNEL_POLICY_REFUSED",
            "ABEMA_CHANNEL_COOKIE_HANDLER_REFUSED",
            "ABEMA_CHANNEL_CANCELLED",
            "ABEMA_CHANNEL_TIME_LIMIT_REACHED",
            "ABEMA_CHANNEL_NETWORK_FAILED",
            "ABEMA_CHANNEL_CLEANUP_FAILED",
            "TWITCH_CATALOG_AUTH_INVALID_RESPONSE",
            "TWITCH_CATALOG_AUTH_REJECTED",
            "TWITCH_CATALOG_AUTH_CLIENT_MISMATCH",
            "TWITCH_CATALOG_AUTH_USER_MISMATCH",
            "TWITCH_CATALOG_AUTH_SCOPE_MISMATCH",
            "TWITCH_CATALOG_AUTH_EXPIRED",
            "TWITCH_CATALOG_AUTH_NETWORK",
            "TWITCH_HELIX_INVALID_INPUT",
            "TWITCH_HELIX_INVALID_RESPONSE",
            "TWITCH_CATALOG_NETWORK_DNS",
            "TWITCH_CATALOG_NETWORK_TIMEOUT",
            "TWITCH_CATALOG_NETWORK_TLS",
            "TWITCH_CATALOG_NETWORK_CONNECTION",
            "TWITCH_CATALOG_NETWORK_INTERRUPTED",
            "TWITCH_CATALOG_NETWORK_BUDGET",
            "TWITCH_CATALOG_NETWORK_BACKGROUND",
            "TWITCH_CATALOG_NETWORK_IO",
            "TWITCH_CATALOG_STORE_STORAGE",
            "TWITCH_CATALOG_STORE_INVALID_RECORD",
            "TWITCH_CATALOG_STORE_CLIENT_MISMATCH",
        }
        words = module.vocabulary()["FailureCategory"]
        self.assertEqual({value for value in words if value.startswith(("ABEMA_CHANNEL_", "TWITCH_"))}, expected)
        for category in expected:
            with self.subTest(category=category):
                data = self.journal([self.row(**{"4": "CATALOG_LOAD", "9": category, "10": category})])
                stdout, stderr = io.BytesIO(), io.StringIO()
                self.assertEqual(module.main(io.BytesIO(data), stdout, stderr), 0)
                self.assertEqual(stdout.getvalue(), data)
                self.assertEqual(stderr.getvalue(), "")

    def test_unknown_provider_category_is_rejected_without_echoing_input(self):
        for category in ("ABEMA_CHANNEL_UNKNOWN", "TWITCH_CATALOG_NETWORK_private_secret"):
            data = self.journal([self.row(**{"9": category})])
            stdout, stderr = io.BytesIO(), io.StringIO()
            self.assertEqual(module.main(io.BytesIO(data), stdout, stderr), 1)
            self.assertEqual(stdout.getvalue(), b"")
            self.assertEqual(stderr.getvalue(), "JOURNAL_INVALID\n")

    def test_secret_fields_fail_without_any_output(self):
        secret = "https://secret.example/private?token=password"
        for index in range(13):
            with self.subTest(index=index):
                data = self.journal([self.row(**{str(index): secret})])
                stdout, stderr = io.BytesIO(), io.StringIO()
                self.assertEqual(module.main(io.BytesIO(data), stdout, stderr), 1)
                self.assertEqual(stdout.getvalue(), b"")
                self.assertEqual(stderr.getvalue(), "JOURNAL_INVALID\n")

    def test_distinct_safe_evidence_at_same_stage_is_valid_but_identical_repeats_are_duplicates(self):
        first = self.row()
        distinct = self.row(**{"1": "3" * 32, "6": "SECONDARY", "9": "SECURITY"})
        data = self.journal([first, distinct])
        self.assertEqual(module.validate(data), data)
        for fields in ({"9": "SECURITY"}, {"10": "SECURITY"}, {"11": "ROUTE_SESSION:CLOSE:78"},
                       {"12": "HTTP_REJECTED"}):
            row = self.row(**{"1": "3" * 32, "6": "SECONDARY", **fields})
            self.assertEqual(module.validate(self.journal([first, row])), self.journal([first, row]))
        for frames in ("ROUTE_SESSION:CLOSE:77", "ROUTE_SESSION:CLOSE:077"):
            with self.assertRaises(module.InvalidJournal):
                module.validate(self.journal([first, self.row(**{"1": "3" * 32, "6": "SECONDARY", "11": frames})]))

    def test_bounds_nonascii_and_unknown_vocabulary_are_rejected(self):
        invalid = [b"x" * (module.MAX_BYTES + 1), self.journal([self.row()] * 129), b"\xff", b"garbage\n",
                   self.journal([self.row(**{"7": "0"})]), self.journal([self.row(**{"7": "65536"})]),
                   self.journal([self.row(**{"11": "ROUTE_SESSION:CLOSE:100001"})]),
                   self.journal([self.row(**{"10": ",".join(["IO"] * 5)})]),
                   self.journal([self.row(**{"11": ",".join(["ROUTE_SESSION:CLOSE:1"] * 9)})]),
                   self.journal([self.row(**{"4": "UNKNOWN_STAGE"})])]
        for data in invalid:
            with self.assertRaises(module.InvalidJournal):
                module.validate(data)

    def test_duplicate_ids_keys_or_roots_and_orphan_relationships_are_rejected(self):
        invalid = [self.journal([self.row(), self.row()]),
                   self.journal([self.row(), self.row(**{"1": "3" * 32, "6": "SECONDARY"})]),
                   self.journal([self.row(), self.row(**{"1": "3" * 32, "2": "3" * 32, "4": "PLAYER_RELEASE"})]),
                   self.journal([self.row(**{"2": "3" * 32})]),
                   self.journal([self.row(**{"1": "3" * 32, "6": "SECONDARY"})]),
                   self.journal([self.row(), self.row(**{"0": "99999", "1": "3" * 32, "4": "PLAYER_RELEASE", "6": "SECONDARY"})]),
                   self.journal([self.row(), self.row(**{"1": "3" * 32, "4": "VIEWER_BLOCKED", "6": "BLOCKED"})])]
        for data in invalid:
            with self.assertRaises(module.InvalidJournal):
                module.validate(data)

    def test_input_read_is_bounded_and_success_outputs_only_validated_bytes(self):
        class BoundedInput:
            def read(inner, maximum):
                self.assertEqual(maximum, module.MAX_BYTES + 1)
                return self.journal([self.row()])
        stdout, stderr = io.BytesIO(), io.StringIO()
        self.assertEqual(module.main(BoundedInput(), stdout, stderr), 0)
        self.assertEqual(stdout.getvalue(), self.journal([self.row()]))
        self.assertEqual(stderr.getvalue(), "")


if __name__ == "__main__":
    unittest.main()
