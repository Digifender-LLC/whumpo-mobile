"""Fail closed when the connected-test adapter returns success without JUnit evidence."""

from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def verify(root: Path) -> None:
    for flavor in ("local", "deployed"):
        reports = list((root / "debug" / "flavors" / flavor).glob("TEST-*.xml"))
        if not reports:
            raise ValueError(f"{flavor}: missing connected-test JUnit results")
        for report in reports:
            suite = ET.parse(report).getroot()
            cases = list(suite.iter("testcase"))
            if not cases or int(suite.get("tests", "0")) != len(cases):
                raise ValueError(f"{flavor}: empty or inconsistent JUnit results")
            if (int(suite.get("failures", "0")) or int(suite.get("errors", "0"))
                    or any(case.find("failure") is not None or case.find("error") is not None for case in cases)):
                raise ValueError(f"{flavor}: failing connected-test JUnit results")
            if all(case.find("skipped") is not None for case in cases):
                raise ValueError(f"{flavor}: no executed connected tests")
            print(f"{flavor}: verified {len(cases)} JUnit test cases in {report.name}")


if __name__ == "__main__":
    try:
        verify(Path(sys.argv[1]))
    except (ValueError, ET.ParseError, OSError) as error:
        print(f"Instrumentation evidence failed: {error}", file=sys.stderr)
        sys.exit(3)
