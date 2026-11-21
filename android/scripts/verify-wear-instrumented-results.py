"""Require the full Wear tile instrumentation inventory on its supported API 36 device."""

from collections import Counter
import json
import re
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

INVENTORY = Path(__file__).with_name("wear-instrumented-test-inventory.json")


def expected_cases(inventory):
    if inventory["version"] != 1:
        raise ValueError("unsupported Wear instrumentation inventory version")
    cases = [(cls, name) for cls, names in inventory["classes"].items() for name in names]
    if not cases or len(cases) != len(set(cases)):
        raise ValueError("empty or duplicate expected Wear test inventory")
    return set(cases)


def verify(root: Path, serial: str, inventory_path: Path = INVENTORY) -> None:
    if not re.fullmatch(r"emulator-[0-9]+", serial):
        raise ValueError("expected one numeric emulator serial")
    expected = expected_cases(json.loads(inventory_path.read_text()))
    for flavor in ("local", "deployed"):
        reports = list((root / "debug" / "flavors" / flavor).glob("TEST-*.xml"))
        if len(reports) != 1:
            raise ValueError(f"{flavor}: expected one Wear JUnit report, found {len(reports)}")
        suite = ET.parse(reports[0]).getroot()
        cases = list(suite.iter("testcase"))
        identities = Counter((case.get("classname"), case.get("name")) for case in cases)
        actual = set(identities)
        if actual != expected or any(count != 1 for count in identities.values()):
            raise ValueError(
                f"{flavor}: Wear inventory mismatch: missing={sorted(expected - actual)}, "
                f"unknown={sorted(actual - expected, key=str)}, "
                f"duplicates={sorted([case for case, count in identities.items() if count != 1], key=str)}"
            )
        for owner in suite.iter():
            if owner.findall("testcase"):
                devices = owner.findall("./properties/property[@name='device']")
                if len(devices) != 1 or devices[0].get("value") != serial:
                    raise ValueError(f"{flavor}: missing or mismatched Wear device metadata for {serial}")
        counts = {
            "tests": len(cases),
            "failures": sum(case.find("failure") is not None for case in cases),
            "errors": sum(case.find("error") is not None for case in cases),
            "skipped": sum(case.find("skipped") is not None for case in cases),
        }
        if any(int(suite.get(name, "0")) != count for name, count in counts.items()):
            raise ValueError(f"{flavor}: inconsistent Wear JUnit counts")
        if counts["failures"] or counts["errors"] or counts["skipped"]:
            raise ValueError(f"{flavor}: Wear tests failed or were skipped: {counts}")
        print(f"API 36 {flavor}: verified Wear inventory ({len(cases)} cases) in {reports[0].name}")


if __name__ == "__main__":
    try:
        if len(sys.argv) != 3:
            raise ValueError("expected Wear result directory and selected emulator serial")
        verify(Path(sys.argv[1]), sys.argv[2])
    except (ValueError, KeyError, TypeError, ET.ParseError, OSError) as error:
        print(f"Wear instrumentation evidence failed: {error}", file=sys.stderr)
        sys.exit(3)
