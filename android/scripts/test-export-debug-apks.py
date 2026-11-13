#!/usr/bin/env python3
"""Synthetic source/tool/filesystem boundaries; real APKs are verified in the build job."""

import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("apk_export", Path(__file__).with_name("export-debug-apks.py"))
apk = importlib.util.module_from_spec(spec)
spec.loader.exec_module(apk)


class ExportTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.sdk = self.root / "sdk"
        tools = self.sdk / "build-tools/36.0.0"
        tools.mkdir(parents=True)
        (tools / "source.properties").write_text("Pkg.Revision=36.0.0\n")
        self.env = {"ANDROID_HOME": str(self.sdk)}
        self.dirty = ""
        self.package = None
        self.debuggable = True
        self.cert = "1" * 64
        self.test_cert = self.cert
        self.dn = "C=US, O=Android, CN=Android Debug"
        self.signers = 1
        self.target = "com.whumpo.android.local"
        self.runner = apk.RUNNER
        self.tool_calls = []
        self.mutate = None
        self.badging_mutate = None
        for rel, _, _ in apk.INPUTS:
            path = self.root / "android/androidApp/build/outputs/apk" / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes((rel + " synthetic bytes").encode())

    def run_tool(self, args, cwd):
        args = [str(a) for a in args]
        self.tool_calls.append(args)
        if args[0] == "git":
            if args[1] == "status":
                return self.dirty
            if args[1] == "cat-file":
                return "tree " + "b" * 40 + "\nparent " + "d" * 40 + "\nparent " + "c" * 40 + "\n\nSynthetic merge\n"
            return "b" * 40 if args[-1] == "HEAD^{tree}" else "a" * 40
        if args[0] == "java":
            return "    java.runtime.version = 21.0.13+9-LTS\n    java.vendor = Eclipse Adoptium\n"
        if args[0].endswith("gradlew"):
            return "Gradle 9.9.0\n"
        if args[1] == "version":
            return "0.9\n" if args[0].endswith("apksigner") else "Android Asset Packaging Tool, v0.2-13193326\n"
        if args[1] == "verify":
            require_stage = Path(args[-1])
            self.assertTrue(require_stage.parent.name.startswith(".debug-artifacts-"))
            cert = self.test_cert if "androidTest" in require_stage.name else self.cert
            return f"Number of signers: {self.signers}\nSigner #1 certificate DN: {self.dn}\nSigner #1 certificate SHA-256 digest: {cert}\n"
        if args[2] == "badging":
            if self.badging_mutate:
                self.badging_mutate(Path(args[-1]))
            package = self.package or ("com.whumpo.android.local.test" if "androidTest" in args[-1] else "com.whumpo.android.local")
            return f"package: name='{package}' versionCode='1'\n" + ("application-debuggable\n" if self.debuggable else "")
        if args[2] == "xmltree":
            if self.mutate:
                self.mutate(Path(args[-2]).parent)
            return (f'    E: instrumentation (line=9)\n'
                    f'      A: android:name(0x01010003)="{self.runner}"\n'
                    f'      A: android:targetPackage(0x01010021)="{self.target}"\n'
                    f'    E: uses-permission (line=16)\n')
        self.fail(f"Unexpected synthetic tool invocation: {args}")

    def export(self):
        return apk.export(self.root, self.env, self.run_tool)

    def refused(self):
        with self.assertRaises((ValueError, KeyError, subprocess.SubprocessError)):
            self.export()
        self.assertFalse((self.root / apk.OUTPUT).exists())
        self.assertFalse(list((self.root / apk.OUTPUT).parent.glob(".debug-artifacts-*")))

    def ci(self, event="pull_request"):
        payload = {"repository": {"full_name": apk.REPOSITORY}, "after": "a" * 40,
                   "pull_request": {"head": {"sha": "c" * 40, "repo": {"full_name": "example/fork"}},
                                    "base": {"sha": "d" * 40, "repo": {"full_name": apk.REPOSITORY}}}}
        path = self.root / "event.json"
        path.write_text(json.dumps(payload))
        self.env.update(GITHUB_ACTIONS="true", GITHUB_REPOSITORY=apk.REPOSITORY,
                        GITHUB_EVENT_NAME=event, GITHUB_EVENT_PATH=str(path),
                        GITHUB_SHA="a" * 40, GITHUB_REF="refs/heads/main",
                        GITHUB_RUN_ID="123", GITHUB_RUN_ATTEMPT="1")
        return path, payload

    def test_exact_staged_pair_hashes_and_observed_tools(self):
        manifest = self.export()
        out = self.root / apk.OUTPUT
        self.assertEqual({p.name for p in out.iterdir()},
                         {"manifest.json", "android-local-debug.apk", "android-local-debug-androidTest.apk"})
        self.assertEqual(json.loads((out / "manifest.json").read_text()), manifest)
        for item in manifest["files"]:
            self.assertEqual(item["sha256"], apk.digest(out / item["name"]))
            self.assertEqual(item["bytes"], (out / item["name"]).stat().st_size)
        self.assertEqual(manifest["toolchain"]["gradleVersion"], "9.9.0")
        self.assertEqual(manifest["toolchain"]["javaRuntimeVersion"], "21.0.13+9-LTS")
        self.assertEqual(manifest["build"], {"event": "local", "runId": None, "runAttempt": None})
        self.assertNotIn(str(self.root), json.dumps(manifest))

    def test_pr_merge_checkout_is_distinct_from_head_and_base(self):
        self.ci()
        result = self.export()
        self.assertEqual(result["source"], {"checkoutSha": "a" * 40, "checkoutTreeSha": "b" * 40,
                                          "headSha": "c" * 40, "baseSha": "d" * 40,
                                          "headRepository": "example/fork"})
        self.assertEqual(result["build"]["runId"], "123")

    def test_main_push_evidence(self):
        self.ci("push")
        result = self.export()
        self.assertIsNone(result["source"]["baseSha"])
        self.assertEqual(result["source"]["headSha"], result["source"]["checkoutSha"])

    def test_actual_shallow_merge_retains_raw_parent_evidence(self):
        repo = self.root / "repository"
        repo.mkdir()
        def git(*args, cwd=repo):
            return subprocess.check_output(["git", "-c", "user.name=Fixture",
                                            "-c", "user.email=fixture@example.test", *args],
                                           cwd=cwd, stderr=subprocess.DEVNULL, text=True).strip()
        git("init", "--initial-branch=main")
        git("commit", "--allow-empty", "-m", "base")
        git("checkout", "-b", "feature")
        git("commit", "--allow-empty", "-m", "head")
        head = git("rev-parse", "HEAD")
        git("checkout", "main")
        git("commit", "--allow-empty", "-m", "current base")
        base = git("rev-parse", "HEAD")
        git("merge", "--no-ff", "feature", "-m", "merge")
        clone = self.root / "checkout"
        git("clone", "--depth=1", repo.as_uri(), str(clone))
        self.assertEqual(git("show", "-s", "--format=%P", "HEAD", cwd=clone), "")
        path, payload = self.ci()
        payload["pull_request"]["head"]["sha"] = head
        payload["pull_request"]["base"]["sha"] = base
        path.write_text(json.dumps(payload))
        self.env["GITHUB_SHA"] = git("rev-parse", "HEAD", cwd=clone)
        source, _ = apk.source_evidence(clone, self.env, apk.command)
        self.assertEqual((source["headSha"], source["baseSha"]), (head, base))
        self.assertEqual(source["checkoutTreeSha"], git("rev-parse", "HEAD^{tree}", cwd=clone))

    def test_dirty_source_refused_before_tools(self):
        self.dirty = " M android/build.gradle.kts"
        self.refused()
        self.assertEqual(len(self.tool_calls), 1)

    def test_event_checkout_and_pr_parent_mismatches(self):
        path, payload = self.ci()
        self.env["GITHUB_SHA"] = "e" * 40
        self.refused()
        self.env["GITHUB_SHA"] = "a" * 40
        payload["pull_request"]["head"]["sha"] = "e" * 40
        path.write_text(json.dumps(payload))
        self.refused()

    def test_incomplete_or_foreign_ci_refused(self):
        self.env["GITHUB_EVENT_NAME"] = "push"
        self.refused()
        self.ci()
        self.env["GITHUB_REPOSITORY"] = "example/other"
        self.refused()
        self.env["GITHUB_REPOSITORY"] = apk.REPOSITORY
        self.env.pop("GITHUB_RUN_ID")
        self.refused()

    def test_non_main_push_and_unknown_event_refused(self):
        self.ci("push")
        self.env["GITHUB_REF"] = "refs/heads/other"
        self.refused()
        self.env["GITHUB_EVENT_NAME"] = "workflow_dispatch"
        self.refused()

    def test_missing_input_refused(self):
        (self.root / "android/androidApp/build/outputs/apk" / apk.INPUTS[0][0]).unlink()
        self.refused()

    def test_input_symlink_and_parent_symlink_refused(self):
        path = self.root / "android/androidApp/build/outputs/apk" / apk.INPUTS[0][0]
        original = path.read_bytes()
        path.unlink()
        other = self.root / "other.apk"
        other.write_bytes(original)
        path.symlink_to(other)
        self.refused()
        path.unlink()
        path.parent.rmdir()
        path.parent.symlink_to(self.root, target_is_directory=True)
        self.refused()

    def test_existing_stage_not_deleted(self):
        stage = self.root / apk.OUTPUT
        stage.mkdir()
        (stage / "keep").write_text("unowned")
        with self.assertRaises(ValueError):
            self.export()
        self.assertEqual((stage / "keep").read_text(), "unowned")

    def test_stage_symlink_not_followed_or_deleted(self):
        other = self.root / "other"
        other.mkdir()
        stage = self.root / apk.OUTPUT
        stage.symlink_to(other, target_is_directory=True)
        with self.assertRaises(ValueError):
            self.export()
        self.assertTrue(stage.is_symlink())
        self.assertTrue(other.is_dir())

    def test_output_parent_symlink_refused(self):
        parent = (self.root / apk.OUTPUT).parent
        moved = self.root / "moved-outputs"
        parent.rename(moved)
        parent.symlink_to(moved, target_is_directory=True)
        self.refused()
        self.assertTrue(moved.is_dir())

    def test_empty_and_oversized_input(self):
        path = self.root / "android/androidApp/build/outputs/apk" / apk.INPUTS[0][0]
        path.write_bytes(b"")
        self.refused()
        path.write_bytes(b"x")
        with patch.object(apk, "MAX_BYTES", 32):
            self.refused()

    def test_manifest_counts_towards_total_limit(self):
        with patch.object(apk, "MAX_BYTES", 1024):
            self.refused()

    def test_combined_pair_limit_before_staging(self):
        sizes = [(self.root / "android/androidApp/build/outputs/apk" / rel).stat().st_size
                 for rel, _, _ in apk.INPUTS]
        with patch.object(apk, "MAX_BYTES", max(sizes) + 1):
            self.refused()

    def test_package_and_non_debuggable_refused(self):
        self.package = "com.whumpo.android"
        self.refused()
        self.package = None
        self.debuggable = False
        self.refused()

    def test_target_and_runner_mismatch_refused(self):
        self.target = "com.whumpo.android"
        self.refused()
        self.target = "com.whumpo.android.local"
        self.runner = "example.Runner"
        self.refused()

    def test_signer_mismatch_multiple_and_non_debug_cert_refused(self):
        self.test_cert = "2" * 64
        self.refused()
        self.test_cert = self.cert
        self.signers = 2
        self.refused()
        self.signers = 1
        self.dn = "CN=Release"
        self.refused()

    def test_tool_failure_refuses_export(self):
        original = self.run_tool
        def failing(args, cwd):
            if "verify" in args:
                raise subprocess.CalledProcessError(1, args)
            return original(args, cwd)
        self.run_tool = failing
        self.refused()

    def test_extra_stage_entry_and_late_apk_mutation_refused(self):
        self.mutate = lambda stage: (stage / "extra.log").write_text("not allowed")
        self.refused()
        self.mutate = lambda stage: (stage / "android-local-debug.apk").write_bytes(b"changed")
        self.refused()

    def test_apk_mutation_after_signature_check_before_first_metadata_hash_refused(self):
        self.badging_mutate = lambda path: path.write_bytes(b"changed after signature verification")
        self.refused()

    def test_source_change_during_inspection_refused(self):
        self.mutate = lambda stage: setattr(self, "dirty", " M source.kt")
        self.refused()


if __name__ == "__main__":
    unittest.main()
