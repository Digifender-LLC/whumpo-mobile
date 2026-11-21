#!/usr/bin/env python3
"""Exercise strict exact-head Wear instrumentation result validation."""

import tempfile
import unittest
import json
import importlib.util
import xml.etree.ElementTree as ET
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    "verify_wear_instrumented_results", Path(__file__).with_name("verify-wear-instrumented-results.py")
)
verifier = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verifier)


SERIAL = "emulator-5554"


class VerifyWearResultsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def write_report(self, flavor, cases=None, *, device=SERIAL, skipped=False):
        folder = self.root / "debug" / "flavors" / flavor
        folder.mkdir(parents=True)
        suite = ET.Element("testsuites", tests="0", failures="0", errors="0", skipped="0")
        names = cases if cases is not None else sorted(verifier.expected_cases(json.loads(verifier.INVENTORY.read_text())))
        for classname, name in names:
            node = ET.SubElement(suite, "testcase", classname=classname, name=name)
            if skipped:
                ET.SubElement(node, "skipped").text = "not executed"
        props = ET.SubElement(suite, "properties")
        ET.SubElement(props, "property", name="device", value=device)
        suite.set("tests", str(len(names)))
        suite.set("skipped", str(len(names) if skipped else 0))
        ET.ElementTree(suite).write(folder / "TEST-device.xml", encoding="unicode")

    def write_both(self, **kwargs):
        for flavor in ("local", "deployed"):
            self.write_report(flavor, **kwargs)

    def test_acceptsCompleteLocalAndDeployedWearInventories(self):
        self.write_both()
        verifier.verify(self.root, SERIAL)

    def test_rejectsMissingWearReport(self):
        self.write_report("local")
        with self.assertRaisesRegex(ValueError, "expected one Wear JUnit report"):
            verifier.verify(self.root, SERIAL)

    def test_rejectsMismatchedOrDuplicateWearMethods(self):
        expected = sorted(verifier.expected_cases(json.loads(verifier.INVENTORY.read_text())))
        self.write_report("local", expected[:-1])
        self.write_report("deployed", expected + [expected[0]])
        with self.assertRaisesRegex(ValueError, "inventory mismatch"):
            verifier.verify(self.root, SERIAL)

    def test_rejectsWrongDeviceAndSkippedCases(self):
        self.write_report("local", device="emulator-5556")
        self.write_report("deployed", skipped=True)
        with self.assertRaisesRegex(ValueError, "mismatched Wear device"):
            verifier.verify(self.root, SERIAL)

    def test_rejectsSkippedWearMethods(self):
        self.write_both(skipped=True)
        with self.assertRaisesRegex(ValueError, "tests failed or were skipped"):
            verifier.verify(self.root, SERIAL)


if __name__ == "__main__":
    unittest.main()
