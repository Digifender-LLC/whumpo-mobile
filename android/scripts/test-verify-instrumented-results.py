"""Synthetic full-suite and per-case evidence regressions; no device or backend."""
import copy
import json
from pathlib import Path
import runpy
import tempfile
import unittest
import xml.etree.ElementTree as ET

HERE = Path(__file__).parent
VERIFY = runpy.run_path(str(HERE / "verify-instrumented-results.py"))["verify"]
INVENTORY = json.loads((HERE / "instrumented-test-inventory.json").read_text())
COMPOSER = "composerIsNotCoveredByTheKeyboard"
CREDENTIAL = "aSignedInSessionNeverReachesTheDeviceLogOrAPlainFile"


class EvidenceTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.inventory = copy.deepcopy(INVENTORY)
        self.reports = {}
        self.reset_reports()

    def reset_reports(self):
        self.reports = {}
        for flavor in ("local", "deployed"):
            suite = ET.Element("testsuites")
            for cls, names in self.inventory["classes"].items():
                for name in names:
                    ET.SubElement(suite, "testcase", classname=cls, name=name)
            self.reports[flavor] = suite

    def save(self):
        for flavor, suite in self.reports.items():
            for key, tag in (("failures", "failure"), ("errors", "error"), ("skipped", "skipped")):
                suite.set(key, str(sum(c.find(tag) is not None for c in suite)))
            suite.set("tests", str(len(suite)))
            folder = self.root / "debug" / "flavors" / flavor
            folder.mkdir(parents=True, exist_ok=True)
            ET.ElementTree(suite).write(folder / "TEST-device.xml")
        inventory = self.root / "inventory.json"
        inventory.write_text(json.dumps(self.inventory))
        return inventory

    def verify(self, api="36"):
        VERIFY(self.root, api, self.save())

    def skip(self, flavor, name, reason):
        case = next(c for c in self.reports[flavor] if c.get("name") == name)
        ET.SubElement(case, "skipped").text = reason

    def test_complete_suite(self):
        self.verify()

    def test_partial_suite(self):
        suite = self.reports["local"]
        suite.remove(suite[0])
        with self.assertRaisesRegex(ValueError, "missing="):
            self.verify()

    def test_duplicate_replaces_missing_even_with_same_count(self):
        suite = self.reports["deployed"]
        suite.remove(suite[1])
        suite.append(copy.deepcopy(suite[0]))
        with self.assertRaisesRegex(ValueError, "duplicates="):
            self.verify()

    def test_duplicate_report_rejected(self):
        inventory = self.save()
        folder = self.root / "debug/flavors/local"
        (folder / "TEST-copy.xml").write_bytes((folder / "TEST-device.xml").read_bytes())
        with self.assertRaisesRegex(ValueError, "expected one JUnit report"):
            VERIFY(self.root, "36", inventory)

    def test_unknown_case(self):
        self.reports["local"][0].set("name", "unreviewed")
        with self.assertRaisesRegex(ValueError, "unknown="):
            self.verify()

    def test_worker_journey_excluded(self):
        ET.SubElement(self.reports["local"], "testcase", classname=INVENTORY["excludedClasses"][0], name="privateJourney")
        with self.assertRaisesRegex(ValueError, "inventory mismatch"):
            self.verify()

    def test_api36_composer_must_execute_in_both_flavors(self):
        for flavor in ("local", "deployed"):
            with self.subTest(flavor=flavor):
                self.reset_reports()
                self.skip(flavor, COMPOSER, "Soft keyboard did not appear on this device")
                with self.assertRaisesRegex(ValueError, "disallowed skip"):
                    self.verify()

    def test_api28_keyboard_absence_is_explicit_conditional_skip(self):
        for flavor in ("local", "deployed"):
            self.skip(flavor, COMPOSER, "org.junit.AssumptionViolatedException: Soft keyboard did not appear on this device")
        self.verify("28")

    def test_api28_other_composer_skip_is_rejected(self):
        self.skip("local", COMPOSER, "ignored failing test")
        with self.assertRaisesRegex(ValueError, "disallowed skip"):
            self.verify("28")

    def test_expected_credential_skip_only_in_deployed(self):
        self.skip("deployed", CREDENTIAL, "org.junit.AssumptionViolatedException: expected local flavor")
        self.verify()
        self.skip("local", CREDENTIAL, "org.junit.AssumptionViolatedException")
        with self.assertRaisesRegex(ValueError, "disallowed skip"):
            self.verify()

    def test_backup_transport_absence_requires_named_reason(self):
        name = "theBackupManagerRefusesThePackageWhileProtectedStateExists"
        self.skip("local", name, "AssumptionViolatedException: the image has no local backup transport")
        self.verify("28")
        self.reset_reports()
        self.skip("local", name, "unrelated ignored failure")
        with self.assertRaisesRegex(ValueError, "disallowed skip"):
            self.verify()

    def test_failure_and_error_rejected(self):
        for tag in ("failure", "error"):
            with self.subTest(tag=tag):
                self.reset_reports()
                ET.SubElement(self.reports["local"][0], tag).text = "synthetic"
                with self.assertRaisesRegex(ValueError, "failing"):
                    self.verify()

    def test_counter_cannot_hide_error(self):
        ET.SubElement(self.reports["local"][0], "failure")
        inventory = self.save()
        report = self.root / "debug/flavors/local/TEST-device.xml"
        suite = ET.parse(report).getroot()
        suite.set("failures", "0")
        ET.ElementTree(suite).write(report)
        with self.assertRaisesRegex(ValueError, "inconsistent"):
            VERIFY(self.root, "36", inventory)

    def test_inherited_and_parameterized_identities_are_exact(self):
        # JUnit reports inherited methods under the concrete child. Parameter suffixes
        # are distinct cases, never stripped or regex-counted as one annotation.
        self.inventory["classes"] = {"SyntheticChild": ["inheritedTest"], "SyntheticParameterized": ["row[0]", "row[1]"]}
        self.reset_reports()
        self.verify()
        suite = self.reports["local"]
        suite.remove(suite[-1])
        with self.assertRaisesRegex(ValueError, "inventory mismatch"):
            self.verify()


if __name__ == "__main__":
    unittest.main()
