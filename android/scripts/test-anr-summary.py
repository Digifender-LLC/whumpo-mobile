#!/usr/bin/env python3
import json
from pathlib import Path
import runpy
import subprocess
import sys
import unittest

SCRIPT = Path(__file__).with_name("anr-summary.py")
MODULE = runpy.run_path(str(SCRIPT))
summarize = MODULE["summarize"]


class AnrSummaryTest(unittest.TestCase):
    def test_fixed_process_labels_and_reason_kind(self):
        for source, label in MODULE["PROCESSES"].items():
            with self.subTest(source=source):
                result = summarize(f"[0,1234,{source},0,Input dispatching timed out (private details)]\n".encode())
                self.assertEqual(result, {"version": 1, "status": "captured", "events": [
                    {"process": label, "pid": 1234, "reasonKind": "input-dispatch"}]})

    def test_cli_never_echoes_untrusted_names_reasons_or_parse_errors(self):
        for raw in (b"[0,1234,synthetic-private-process,0,synthetic-private-reason, more detail]\n",
                    b"synthetic-private-malformed\n", b"[0,1234,name,0,partial", b"\xff"):
            with self.subTest(raw=raw):
                result = subprocess.run([sys.executable, str(SCRIPT)], input=raw, capture_output=True, check=True)
                self.assertEqual(result.stderr, b"")
                self.assertNotIn(b"synthetic-private", result.stdout)
                self.assertEqual(json.loads(result.stdout), summarize(raw))
        self.assertEqual(summarize(b"[0,1234,synthetic-private-process,0,synthetic-private-reason]\n")["events"],
                         [{"process": "other", "pid": 1234, "reasonKind": "other"}])

    def test_empty_capture_is_not_an_assertion_that_no_anr_occurred(self):
        for raw in (b"", b"--------- beginning of events\n--------- switch to events\n"):
            self.assertEqual(summarize(raw), {"version": 1, "status": "captured", "events": []})

    def test_malformed_or_oversized_capture_never_reports_partial_success(self):
        event = b"[0,1234,com.whumpo.android.local,0,reason]\n"
        for raw in (event + b"bad\n", b"[0,0,name,0,reason]", b"[0,4194305,name,0,reason]",
                    b"[2147483648,1234,name,0,reason]", b"[0,1234,name,2147483648,reason]"):
            self.assertEqual(summarize(raw), {"version": 1, "status": "unavailable", "reason": "format"})
        self.assertEqual(summarize(b"x" * (MODULE["MAX_BYTES"] + 1)),
                         {"version": 1, "status": "unavailable", "reason": "limit"})
        self.assertEqual(summarize(event * (MODULE["MAX_EVENTS"] + 1)),
                         {"version": 1, "status": "unavailable", "reason": "limit"})


if __name__ == "__main__":
    unittest.main()
