"""Require the reviewed full JUnit inventory, with explicit API/flavor skip policy."""

from collections import Counter
import json
import re
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

INVENTORY = Path(__file__).with_name("instrumented-test-inventory.json")


def expected_cases(inventory):
    if inventory["version"] != 1:
        raise ValueError("unsupported instrumentation inventory version")
    cases = [(cls, name) for cls, names in inventory["classes"].items() for name in names]
    if not cases or len(cases) != len(set(cases)):
        raise ValueError("empty or duplicate expected test inventory")
    if any(cls in inventory["excludedClasses"] for cls, _ in cases):
        raise ValueError("excluded class in expected inventory")
    return set(cases)


def verify(root: Path, api: str, serial: str, inventory_path: Path = INVENTORY) -> None:
    if not re.fullmatch(r"emulator-[0-9]+", serial):
        raise ValueError("expected one numeric emulator serial")
    inventory = json.loads(inventory_path.read_text())
    expected = expected_cases(inventory)
    policy = inventory["allowedSkips"][api]
    for flavor in ("local", "deployed"):
        reports = list((root / "debug" / "flavors" / flavor).glob("TEST-*.xml"))
        if not reports:
            raise ValueError(f"{flavor}: missing connected-test JUnit results")
        if len(reports) != 1:
            raise ValueError(f"{flavor}: expected one JUnit report for the selected emulator")
        for report in reports:
            suite = ET.parse(report).getroot()
            cases = list(suite.iter("testcase"))
            identities = Counter((c.get("classname"), c.get("name")) for c in cases)
            actual = set(identities)
            if actual != expected or any(n != 1 for n in identities.values()):
                raise ValueError(
                    f"{flavor}: inventory mismatch: missing={sorted(expected - actual)}, "
                    f"unknown={sorted(actual - expected, key=str)}, "
                    f"duplicates={sorted([k for k, n in identities.items() if n != 1], key=str)}"
                )
            # Each testcase owner must identify the selected device. A matching
            # root property cannot mask a different/missing nested suite property.
            for owner in suite.iter():
                if owner.findall("testcase"):
                    devices = owner.findall("./properties/property[@name='device']")
                    if len(devices) != 1 or devices[0].get("value") != serial:
                        raise ValueError(f"{flavor}: missing, duplicate or mismatched device metadata for {serial}")
            counts = {
                "tests": len(cases),
                "failures": sum(c.find("failure") is not None for c in cases),
                "errors": sum(c.find("error") is not None for c in cases),
                "skipped": sum(c.find("skipped") is not None for c in cases),
            }
            if any(int(suite.get(key, "0")) != value for key, value in counts.items()):
                raise ValueError(f"{flavor}: inconsistent JUnit counts")
            if counts["failures"] or counts["errors"]:
                raise ValueError(f"{flavor}: failing connected-test JUnit results")
            for case in cases:
                skip = case.find("skipped")
                if skip is None:
                    continue
                identity = f"{case.get('classname')}#{case.get('name')}"
                reason = policy[flavor].get(identity)
                detail = " ".join(skip.itertext()) + " " + skip.get("message", "")
                if reason is None or reason not in detail:
                    raise ValueError(f"{flavor}: disallowed skip: {identity}")
                print(f"API {api} {flavor}: permitted conditional skip {identity}; not execution coverage")
            print(f"API {api} {flavor}: verified full inventory ({len(cases)} cases) in {report.name}")


if __name__ == "__main__":
    try:
        if len(sys.argv) != 4 or sys.argv[2] not in ("28", "36"):
            raise ValueError("expected result directory, API 28 or 36 and selected emulator serial")
        verify(Path(sys.argv[1]), sys.argv[2], sys.argv[3])
    except (ValueError, KeyError, TypeError, ET.ParseError, OSError) as error:
        print(f"Instrumentation evidence failed: {error}", file=sys.stderr)
        sys.exit(3)
