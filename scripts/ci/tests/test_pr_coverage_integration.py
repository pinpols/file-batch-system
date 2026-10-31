# 用途：验证 PR 覆盖率集成场景的分片、实际调用和失败传播。
# 用法：在仓库根目录执行 python3 -m unittest scripts.ci.tests.test_pr_coverage_integration。

import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / "scripts/ci/run-pr-coverage-integration.sh"


class PrCoverageIntegrationTest(unittest.TestCase):
    def test_orchestrator_parallel_shards_are_required_and_reports_are_merged(self) -> None:
        workflow = (ROOT / ".github/workflows/pr-gate.yml").read_text(encoding="utf-8")
        aggregate = workflow.split("  unit-it-a:\n", 1)[1].split("\n  # ===", 1)[0]
        self.assertIn("unit-it-a-main, unit-it-a-extra", aggregate.split("runs-on:", 1)[0])
        self.assertIn("pr-a-coverage-main", aggregate)
        self.assertIn("pr-a-coverage-extra", aggregate)
        self.assertIn("--extra-report batch-orchestrator=ci-coverage/a-extra/jacoco.xml", aggregate)
        guard = aggregate.split("      - name: Verify orchestrator shard results\n", 1)[1]
        guard = guard.split("        run: |\n", 1)[1].split("\n      - name:", 1)[0]
        command = textwrap.dedent(guard)
        env = {**os.environ, "SCOPE_RESULT": "success", "A_REQUIRED": "true",
               "A_MAIN_RESULT": "success", "A_EXTRA_RESULT": "success"}
        self.assertEqual(0, subprocess.run(["bash", "-e", "-c", command], env=env).returncode)
        for key in ("A_MAIN_RESULT", "A_EXTRA_RESULT"):
            for result in ("failure", "skipped", "cancelled"):
                with self.subTest(key=key, result=result):
                    self.assertNotEqual(0, subprocess.run(["bash", "-e", "-c", command],
                                                         env={**env, key: result}).returncode)
        env.update(A_REQUIRED="false", A_MAIN_RESULT="skipped", A_EXTRA_RESULT="skipped")
        self.assertEqual(0, subprocess.run(["bash", "-e", "-c", command], env=env).returncode)

    def test_trigger_worker_parallel_shards_keep_coverage_and_fail_closed(self) -> None:
        workflow = (ROOT / ".github/workflows/pr-gate.yml").read_text(encoding="utf-8")
        aggregate = workflow.split("  unit-it-b1:\n", 1)[1].split("\n  # ===", 1)[0]
        self.assertIn("unit-it-b1-main, unit-it-b1-extra", aggregate.split("runs-on:", 1)[0])
        for module in ("batch-trigger", "batch-worker/process", "batch-worker/dispatch"):
            self.assertIn(f"--extra-report {module}=ci-coverage/b1-extra/{module}/target/site/jacoco/jacoco.xml", aggregate)
        guard = aggregate.split("      - name: Verify Trigger and worker shard results\n", 1)[1]
        guard = guard.split("        run: |\n", 1)[1].split("\n      - name:", 1)[0]
        command = textwrap.dedent(guard)
        env = {**os.environ, "SCOPE_RESULT": "success", "REQUIRED": "true",
               "MAIN_RESULT": "success", "EXTRA_RESULT": "success"}
        self.assertEqual(0, subprocess.run(["bash", "-e", "-c", command], env=env).returncode)
        for key in ("MAIN_RESULT", "EXTRA_RESULT"):
            for result in ("failure", "skipped", "cancelled"):
                with self.subTest(key=key, result=result):
                    self.assertNotEqual(0, subprocess.run(["bash", "-e", "-c", command],
                                                         env={**env, key: result}).returncode)
        env.update(REQUIRED="false", MAIN_RESULT="skipped", EXTRA_RESULT="skipped")
        self.assertEqual(0, subprocess.run(["bash", "-e", "-c", command], env=env).returncode)

    def test_console_parallel_shards_are_required_and_reports_are_merged(self) -> None:
        workflow = (ROOT / ".github/workflows/pr-gate.yml").read_text(encoding="utf-8")
        aggregate = workflow.split("  unit-it-b2:\n", 1)[1].split("\n  # ===", 1)[0]
        self.assertIn("unit-it-b2-console-extra", aggregate.split("runs-on:", 1)[0])
        self.assertIn("pr-console-coverage-main", aggregate)
        self.assertIn("pr-console-coverage-extra", aggregate)
        self.assertIn("--extra-report batch-console-api=.coverage/console-extra/jacoco.xml", aggregate)
        guard = aggregate.split("      - name: Verify split unit shard results\n", 1)[1]
        guard = guard.split("        run: |\n", 1)[1].split("\n      - name:", 1)[0]
        command = textwrap.dedent(guard)
        env = {**os.environ, "SCOPE_RESULT": "success", "WORKERS_RESULT": "success",
               "CONSOLE_RESULT": "success", "CONSOLE_EXTRA_RESULT": "success",
               "GOVERNANCE_RESULT": "success", "CONSOLE_REQUIRED": "true"}
        self.assertEqual(0, subprocess.run(["bash", "-e", "-c", command], env=env).returncode)
        for key in ("CONSOLE_RESULT", "CONSOLE_EXTRA_RESULT"):
            for result in ("failure", "skipped", "cancelled"):
                with self.subTest(key=key, result=result):
                    self.assertNotEqual(0, subprocess.run(["bash", "-e", "-c", command],
                                                         env={**env, key: result}).returncode)
        env.update(CONSOLE_REQUIRED="false", CONSOLE_RESULT="skipped", CONSOLE_EXTRA_RESULT="skipped")
        self.assertEqual(0, subprocess.run(["bash", "-e", "-c", command], env=env).returncode)

    def test_lists_existing_unique_test_classes_in_owned_modules(self) -> None:
        seen = set()
        expected_modules = {
            "a": {"batch-orchestrator"},
            "a-extra": {"batch-orchestrator"},
            "b1": {"batch-trigger", "batch-worker/process", "batch-worker/dispatch"},
            "b2-workers": {"batch-worker/import", "batch-worker/export"},
            "b2-console": {"batch-console-api"},
            "b2-console-extra": {"batch-console-api"},
        }
        for shard, expected in expected_modules.items():
            result = subprocess.run(
                ["bash", str(SCRIPT), shard, "--list"],
                cwd=ROOT, capture_output=True, text=True, check=True,
            )
            modules = set()
            for line in result.stdout.splitlines():
                module, names = line.split("\t")
                modules.add(module)
                sources = list((ROOT / module / "src/test/java").rglob("*.java"))
                for name in names.split(","):
                    self.assertNotIn((module, name), seen)
                    seen.add((module, name))
                    pattern = re.compile(r"\bclass\s+" + re.escape(name) + r"\b")
                    self.assertTrue(
                        any(pattern.search(source.read_text(encoding="utf-8")) for source in sources),
                        f"{module}: missing {name}",
                    )
            self.assertEqual(expected, modules)

    def test_runs_each_group_with_append_and_stops_on_failure(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            scripts = root / "scripts/ci"
            scripts.mkdir(parents=True)
            shutil.copy(SCRIPT, scripts / SCRIPT.name)
            capture = root / "calls"
            mvn = root / "mvnw"
            mvn.write_text(
                '#!/usr/bin/env bash\nprintf "%s\\n" "$*" >> "$CI_CAPTURE"\n'
                '[[ -z "${CI_FAIL_MODULE:-}" || "$*" != *"$CI_FAIL_MODULE"* ]]\n',
                encoding="utf-8",
            )
            mvn.chmod(0o755)
            env = {**os.environ, "CI_CAPTURE": str(capture)}
            subprocess.run(["bash", str(scripts / SCRIPT.name), "b1"], env=env, check=True)
            calls = capture.read_text().splitlines()
            self.assertEqual(5, len(calls))
            self.assertTrue(all("-Djacoco.append=true" in call for call in calls))
            capture.unlink()
            env["CI_FAIL_MODULE"] = "batch-worker/process"
            failed = subprocess.run(["bash", str(scripts / SCRIPT.name), "b1"], env=env)
            self.assertNotEqual(0, failed.returncode)
            self.assertEqual(3, len(capture.read_text().splitlines()))
            invalid = subprocess.run(["bash", str(scripts / SCRIPT.name), "invalid", "--list"],
                                     env=env, capture_output=True)
            self.assertNotEqual(0, invalid.returncode)


if __name__ == "__main__":
    unittest.main()
