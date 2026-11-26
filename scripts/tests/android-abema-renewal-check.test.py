#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Provider-free parser and verdict tests; never contact a device."""

import importlib.util
from pathlib import Path
import sys
import unittest


sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location(
    "android_abema_renewal_check", Path(__file__).resolve().parents[1] / "android-abema-renewal-check.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

PROCESSES = """\
ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)
  *APP* UID 10216 ProcessRecord{4d2c40d 5702:net.fstab.tachiai/u0a216}
    pid=5702
    oom adj: max=1001 curRaw=905 setRaw=905 cur=905 set=905
    hasPendingCompaction=false    isFreezeExempt=false isPendingFreeze=false isFrozen=true
  *APP* UID 99005 ProcessRecord{abb0e00 5133:com.google.android.webview:sandboxed_process0:org.chromium.content.app.SandboxedProcessService0:0/u0a216i5}
    oom adj: max=1001 curRaw=0 setRaw=0 cur=0 set=0
    hasPendingCompaction=false    isFreezeExempt=false isPendingFreeze=false isFrozen=false
  *APP* UID 10216 ProcessRecord{5e1f2a1 5837:net.fstab.tachiai:abema_approval_browser/u0a216}
    oom adj: max=1001 curRaw=200 setRaw=200 cur=200 set=200
    hasPendingCompaction=false    isFreezeExempt=false isPendingFreeze=false isFrozen=false
"""


def line(seconds, tag, message, priority="I"):
    return f"         {seconds:.3f}  5702  5747 {priority} {tag}: {message}\n"


class ParserTests(unittest.TestCase):
    def test_keeps_only_tachiai_tags_and_its_freezer_lines(self):
        self.assertEqual(module.parse_line(line(10.5, "TachiaiAbemaRenewal", "TIMER_FIRED")),
                         (10.5, "TachiaiAbemaRenewal", "TIMER_FIRED"))
        self.assertIsNotNone(module.parse_line(line(1, "ActivityManager", "freezing 5702 net.fstab.tachiai", "D")))
        self.assertIsNotNone(module.parse_line(
            line(1, "ActivityManager", "sync unfroze 5837 net.fstab.tachiai:abema_approval_browser for 0", "D")))
        self.assertIsNone(module.parse_line(line(1, "ActivityManager", "freezing 99 com.example.other", "D")))
        self.assertIsNone(module.parse_line(line(1, "ActivityManager", "Start proc 5702:net.fstab.tachiai", "D")))
        self.assertIsNone(module.parse_line(line(1, "chromium", "https://example.invalid/?token=secret")))
        self.assertIsNone(module.parse_line("--------- beginning of main\n"))

    def test_keeps_closed_route_engine_events_but_not_host_bearing_lines(self):
        for message in ("process=main ENGINE_STARTED engines=1", "process=main JOB_LEASE_JOINED engine=SHARED",
                        "process=main JOB_ENGINE_RETIRED reason=RELEASED engines=0",
                        "process=main ENGINE_CLEANUP_UNCONFIRMED"):
            self.assertIsNotNone(module.parse_line(line(1, "TachiaiRouteEngine", message)), message)
        for message in ("ROUTE_REFUSED process=main purpose=PLAYBACK reason=NOT_ALLOWED host=example.invalid port=443 count=1",
                        "ROUTE_CHALLENGED reason=AUTH_CHALLENGE", "process=main ENGINE_STARTED host=example.invalid"):
            self.assertIsNone(module.parse_line(line(1, "TachiaiRouteEngine", message, "W")), message)

    def test_engine_summary_counts_starts_joins_and_other_processes(self):
        lines = [(99.0, "TachiaiRouteEngine", "process=main ENGINE_STARTED engines=1"),
                 (101.0, "TachiaiRouteEngine", "process=main JOB_LEASE_JOINED engine=SHARED"),
                 (102.0, "TachiaiRouteEngine", "process=main JOB_ENGINE_STARTED engines=1"),
                 (103.0, "TachiaiRouteEngine", "process=abema_approval_browser ENGINE_STARTED engines=2")]
        self.assertEqual(module.engine_summary(lines, 100.0), {
            "started": ["JOB_ENGINE_STARTED", "ENGINE_STARTED"], "joined": ["SHARED"],
            "outside": ["abema_approval_browser"], "most": 2})

    def test_trigger_reply_is_a_closed_constant(self):
        self.assertEqual(module.parse_trigger("Result: Bundle[{result=STARTED}]\n"), "STARTED")
        self.assertEqual(module.parse_trigger("Result: Bundle[{result=RUNNING}]\n"), "RUNNING")
        self.assertIsNone(module.parse_trigger("Result: Bundle[{result=SOMETHING_ELSE}]\n"))
        self.assertIsNone(module.parse_trigger("Error while accessing provider\n"))

    def test_process_states_cover_only_tachiai_processes(self):
        self.assertEqual(module.process_states(PROCESSES), {
            "net.fstab.tachiai": (905, True),
            "net.fstab.tachiai:abema_approval_browser": (200, False),
        })
        self.assertEqual(module.freezer_cutoff("  freezer_cutoff_adj=900\n"), 900)


class VerdictTests(unittest.TestCase):
    RESULT = "OPERATION_RESULT result={} cleanupConfirmed={} phase=NONE reason=NONE"

    def test_success_with_confirmed_cleanup_and_no_freeze_passes(self):
        summary = module.judge([(99.0, "TachiaiAbemaRenewal", self.RESULT.format("TEMPORARY_FAILURE", "false")),
                                (100.0, "TachiaiAbemaRenewal", "TIMER_FIRED"),
                                (120.5, "TachiaiAbemaRenewal", self.RESULT.format("SUCCESS", "true"))], 100.0, [])
        self.assertEqual(summary, {"result": "SUCCESS", "cleanupConfirmed": True, "froze": [],
                                   "frozeAfterItsPart": [], "duration": 20.5, "passed": True})

    def test_a_freeze_during_the_operation_fails_even_after_success(self):
        lines = [(101.0, "ActivityManager", "freezing 5702 net.fstab.tachiai"),
                 (101.5, "ActivityManager", "freezing 5703 net.fstab.tachiai:prototype_cached_player"),
                 (120.0, "TachiaiAbemaRenewal", self.RESULT.format("SUCCESS", "true")),
                 (130.0, "ActivityManager", "freezing 5837 net.fstab.tachiai:abema_approval_browser")]
        summary = module.judge(lines, 100.0, [])
        self.assertEqual((summary["froze"], summary["frozeAfterItsPart"]), (["net.fstab.tachiai"], []))
        self.assertFalse(summary["passed"])

    def test_the_browser_working_or_idle_after_its_result(self):
        browser = "net.fstab.tachiai:abema_approval_browser"
        result = (112.8, "TachiaiAbemaRenewal", "BROWSER_RESULT result=DELIVERED phase=NONE")
        done = (117.8, "TachiaiAbemaRenewal", self.RESULT.format("SUCCESS", "true"))
        idle = module.judge([result, (113.6, "ActivityManager", f"freezing 5837 {browser}"), done], 100.0, [])
        self.assertEqual((idle["froze"], idle["frozeAfterItsPart"], idle["passed"]), ([], [browser], True))
        working = module.judge([(105.0, "ActivityManager", f"freezing 5837 {browser}"), result, done], 100.0, [])
        self.assertEqual((working["froze"], working["passed"]), ([browser], False))

    def test_the_freeze_steps_own_actions_count_as_freezing(self):
        lines = [(120.0, "TachiaiAbemaRenewal", self.RESULT.format("SUCCESS", "true"))]
        self.assertFalse(module.judge(lines, 100.0, [("net.fstab.tachiai", 104.0)])["passed"])

    def test_the_stall_and_unconfirmed_cleanup_fail(self):
        self.assertEqual(module.judge([], 100.0, []), {"result": "NONE", "cleanupConfirmed": None, "froze": [],
                                                       "frozeAfterItsPart": [], "duration": None, "passed": False})
        summary = module.judge([(201.3, "TachiaiAbemaRenewal", self.RESULT.format("TEMPORARY_FAILURE", "false"))],
                               100.0, [])
        self.assertEqual((summary["result"], summary["cleanupConfirmed"], summary["passed"]),
                         ("TEMPORARY_FAILURE", False, False))


if __name__ == "__main__":
    unittest.main()
