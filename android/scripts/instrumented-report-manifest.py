#!/usr/bin/env python3
"""Summarize the known connected-test reports without copying logcat or test messages."""

import hashlib
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def file_summary(path: Path, root: Path):
    if path.is_symlink():
        return None
    resolved = path.resolve()
    try:
        relative = resolved.relative_to(root)
    except ValueError:
        return None
    if not resolved.is_file():
        return None
    data = resolved.read_bytes()
    result = {
        "path": str(relative),
        "bytes": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
    }
    if path.suffix == ".xml":
        try:
            suite = ET.fromstring(data)
            cases = list(suite.iter("testcase"))
            result["counts"] = {
                "tests": len(cases),
                "failures": sum(case.find("failure") is not None for case in cases),
                "errors": sum(case.find("error") is not None for case in cases),
                "skipped": sum(case.find("skipped") is not None for case in cases),
            }
        except ET.ParseError:
            result["parse"] = "invalid-xml"
    return result


def main():
    if len(sys.argv) != 5 or sys.argv[1] not in ("28", "36"):
        raise SystemExit("usage: instrumented-report-manifest.py API SERIAL EXIT_CODE OUTPUT")
    api, serial, exit_code, output = sys.argv[1:]
    root = Path.cwd().resolve()
    modules = {"androidApp": ("local", "deployed")}
    if api == "36":
        modules["wearApp"] = ("local", "deployed")
    reports = []
    for module, flavors in modules.items():
        for flavor in flavors:
            base = Path(module)
            results = base / "build/outputs/androidTest-results/connected/debug/flavors" / flavor
            junit = sorted(
                (file_summary(path, root) for path in results.glob("TEST-*.xml")),
                key=lambda item: item["path"] if item else "",
            )
            report = base / "build/reports/androidTests/connected/debug/flavors" / flavor / "index.html"
            reports.append({
                "module": module,
                "flavor": flavor,
                "junit": [item for item in junit if item is not None],
                "htmlReport": file_summary(report, root),
            })
    payload = {
        "version": 1,
        "api": int(api),
        "serial": serial,
        "gradleExitCode": int(exit_code),
        "reports": reports,
    }
    output_path = Path(output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(payload, indent=2) + "\n")


if __name__ == "__main__":
    main()
