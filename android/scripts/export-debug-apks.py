#!/usr/bin/env python3
"""Export one bounded debug APK pair with ordinary, unsigned build evidence."""

import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import tempfile


REPOSITORY = "Digifender-LLC/whumpo-mobile"
MAX_BYTES = 64 * 1024 * 1024
OUTPUT = Path("android/androidApp/build/outputs/debug-artifacts")
INPUTS = (
    ("local/debug/androidApp-local-debug.apk", "android-local-debug.apk", "com.whumpo.android.local"),
    ("androidTest/local/debug/androidApp-local-debug-androidTest.apk", "android-local-debug-androidTest.apk", "com.whumpo.android.local.test"),
)
RUNNER = "com.whumpo.android.FixtureTestRunner"
SHA = re.compile(r"[0-9a-f]{40}")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def command(args, cwd):
    # Tool diagnostics may contain local paths; only validated fields are exported.
    result = subprocess.run([str(arg) for arg in args], cwd=cwd, check=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, timeout=90)
    return result.stdout


def regular(path, root):
    require(path.is_relative_to(root), "Path is outside its expected root")
    require(all(not part.is_symlink() for part in (path, *path.parents)
                if part.is_relative_to(root)), "Symlink input or output is not allowed")
    require(path.is_file(), "Expected a regular input file")
    size = path.stat().st_size
    require(0 < size <= MAX_BYTES, "Input size is outside the export limit")
    return size


def digest(path):
    sha = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            sha.update(chunk)
    return sha.hexdigest()


def source_evidence(root, env, run):
    git = lambda *args: run(["git", *args], root).strip()
    require(not git("status", "--porcelain=v1", "--untracked-files=normal"),
            "Source checkout must be clean")
    checkout = git("rev-parse", "HEAD")
    tree = git("rev-parse", "HEAD^{tree}")
    require(SHA.fullmatch(checkout) and SHA.fullmatch(tree), "Invalid checkout identity")
    source = {"checkoutSha": checkout, "checkoutTreeSha": tree,
              "headSha": checkout, "baseSha": None, "headRepository": REPOSITORY}
    build = {"event": "local", "runId": None, "runAttempt": None}
    if env.get("GITHUB_ACTIONS") == "true":
        require(env.get("GITHUB_REPOSITORY") == REPOSITORY, "Unexpected CI repository")
        require(env.get("GITHUB_SHA") == checkout, "CI checkout does not match event SHA")
        for key in ("GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT"):
            require(re.fullmatch(r"[1-9][0-9]{0,19}", env.get(key, "")), "Invalid CI run identity")
        event = json.loads(Path(env["GITHUB_EVENT_PATH"]).read_text())
        require(event.get("repository", {}).get("full_name") == REPOSITORY,
                "Unexpected event repository")
        name = env.get("GITHUB_EVENT_NAME")
        if name == "pull_request":
            pr = event["pull_request"]
            head, base = pr["head"]["sha"], pr["base"]["sha"]
            head_repo = pr["head"]["repo"]["full_name"]
            require(SHA.fullmatch(head) and SHA.fullmatch(base), "Invalid PR revisions")
            require(pr["base"]["repo"]["full_name"] == REPOSITORY
                    and re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", head_repo),
                    "Invalid PR repositories")
            # Read raw headers: revision walkers hide parents in checkout's depth-1 clone.
            headers = git("cat-file", "-p", "HEAD").split("\n\n", 1)[0]
            parents = re.findall(r"^parent ([0-9a-f]{40})$", headers, re.M)
            require(parents == [base, head],
                    "Checkout is not the expected PR merge")
            source.update(headSha=head, baseSha=base, headRepository=head_repo)
        else:
            require(name == "push" and env.get("GITHUB_REF") == "refs/heads/main"
                    and event.get("after") == checkout, "Unsupported build event")
        build = {"event": name, "runId": env["GITHUB_RUN_ID"],
                 "runAttempt": env["GITHUB_RUN_ATTEMPT"]}
    else:
        require(not env.get("GITHUB_ACTIONS") and not env.get("GITHUB_EVENT_NAME"),
                "Incomplete CI context must not become local evidence")
    return source, build


def observed_tools(root, sdk, run):
    java = run(["java", "-XshowSettings:properties", "-version"], root)
    def property_value(key):
        matches = re.findall(r"^\s*" + re.escape(key) + r" = ([A-Za-z0-9 ._+()/-]+)$", java, re.M)
        require(len(matches) == 1, "Missing Java version property")
        return matches[0]
    gradle = run([root / "android/gradlew", "--no-daemon", "--version"], root / "android")
    versions = re.findall(r"^Gradle ([0-9]+(?:\.[0-9]+){1,2})$", gradle, re.M)
    require(len(versions) == 1, "Missing observed Gradle version")
    tools = sdk / "build-tools/36.0.0"
    props = (tools / "source.properties").read_text()
    require(re.search(r"^Pkg.Revision\s*=\s*36\.0\.0\s*$", props, re.M), "Unexpected APK inspection tools")
    signer = run([tools / "apksigner", "version"], root).strip()
    aapt = run([tools / "aapt", "version"], root).strip()
    require(re.fullmatch(r"[0-9.]+", signer)
            and re.fullmatch(r"Android Asset Packaging Tool, v[0-9.-]+", aapt), "Unexpected APK tool version output")
    return tools, {"javaRuntimeVersion": property_value("java.runtime.version"),
                   "javaVendor": property_value("java.vendor"), "gradleVersion": versions[0],
                   "apkInspectionBuildTools": "36.0.0", "apksignerVersion": signer,
                   "aaptVersion": aapt, "pythonVersion": platform.python_version()}


def inspect_apk(path, package, tools, root, run):
    signed = run([tools / "apksigner", "verify", "--verbose", "--print-certs", path], root)
    certs = re.findall(r"^Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})$", signed, re.M)
    dns = re.findall(r"^Signer #1 certificate DN: (.+)$", signed, re.M)
    require("Number of signers: 1" in signed.splitlines() and len(certs) == len(dns) == 1,
            "APK must have one verified signer")
    require(set(part.strip() for part in dns[0].split(",")) == {"C=US", "O=Android", "CN=Android Debug"},
            "APK must use a debug certificate")
    badging = run([tools / "aapt", "dump", "badging", path], root)
    packages = re.findall(r"^package: name='([^']+)'", badging, re.M)
    require(packages == [package] and "application-debuggable" in badging.splitlines(),
            "APK must be the expected debuggable package")
    entry = {"name": path.name, "bytes": path.stat().st_size, "sha256": digest(path),
             "package": package, "debuggable": True, "certificateSha256": certs[0]}
    if package.endswith(".test"):
        tree = run([tools / "aapt", "dump", "xmltree", path, "AndroidManifest.xml"], root)
        blocks = re.findall(r"^([ ]*)E: instrumentation[^\n]*\n((?:\1  [^\n]*\n)*)", tree, re.M)
        require(len(blocks) == 1, "Test APK must contain one instrumentation declaration")
        block = blocks[0][1]
        target = re.findall(r'A: android:targetPackage\([^)]*\)="([^"\n]+)"', block)
        runner = re.findall(r'A: android:name\([^)]*\)="([^"\n]+)"', block)
        require(target == ["com.whumpo.android.local"] and runner == [RUNNER],
                "Unexpected instrumentation target or runner")
        entry.update(targetPackage=target[0], runner=runner[0])
    return entry


def export(root, env, run=command):
    root = root.resolve()
    target = root / OUTPUT
    require(not target.exists() and not target.is_symlink(), "Export stage must not already exist")
    require(all(not p.is_symlink() for p in target.parents if p.is_relative_to(root)),
            "Symlink output parent is not allowed")
    source, build = source_evidence(root, env, run)
    sdk = Path(env["ANDROID_HOME"])
    tools, toolchain = observed_tools(root, sdk, run)
    inputs = [(root / "android/androidApp/build/outputs/apk" / rel, name, package)
              for rel, name, package in INPUTS]
    require(sum(regular(path, root) for path, _, _ in inputs) <= MAX_BYTES,
            "APK pair exceeds total export size")
    target.parent.mkdir(parents=True, exist_ok=True)
    stage = Path(tempfile.mkdtemp(prefix=".debug-artifacts-", dir=target.parent))
    try:
        entries = []
        for path, name, package in inputs:
            dest = stage / name
            shutil.copyfile(path, dest, follow_symlinks=False)
            regular(dest, root)
            entries.append(inspect_apk(dest, package, tools, root, run))
        require(entries[0]["certificateSha256"] == entries[1]["certificateSha256"],
                "APK pair must share a debug signer")
        manifest = {"schemaVersion": 1, "repository": REPOSITORY, "source": source,
                    "build": build, "toolchain": toolchain, "files": entries}
        (stage / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
        require({p.name for p in stage.iterdir()} == {name for _, name, _ in INPUTS} | {"manifest.json"},
                "Unexpected export entries")
        require(sum(regular(p, root) for p in stage.iterdir()) <= MAX_BYTES, "Export exceeds total size")
        for entry in entries:
            require(digest(stage / entry["name"]) == entry["sha256"], "Staged APK changed during inspection")
        require(source_evidence(root, env, run) == (source, build), "Source changed during export")
        require(not target.exists() and not target.is_symlink(), "Export stage appeared during inspection")
        stage.rename(target)
        return manifest
    finally:
        if stage.exists():
            shutil.rmtree(stage)


if __name__ == "__main__":
    try:
        export(Path(__file__).resolve().parents[2], os.environ)
        print("Exported exactly two local debug APKs and manifest.json (at most 64 MiB).")
    except (ValueError, KeyError, TypeError, AttributeError, OSError, subprocess.SubprocessError):
        print("Debug APK export refused: source, tool, APK or stage validation failed.", file=sys.stderr)
        sys.exit(1)
