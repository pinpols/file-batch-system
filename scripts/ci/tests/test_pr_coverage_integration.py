# 用途：验证 PR 覆盖率集成场景的分片、实际调用和失败传播。
# 用法：在仓库根目录执行 python3 -m unittest scripts.ci.tests.test_pr_coverage_integration。

import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / "scripts/ci/run-pr-coverage-integration.sh"


class PrCoverageIntegrationTest(unittest.TestCase):
    def test_lists_existing_unique_test_classes_in_owned_modules(self) -> None:
        seen = set()
        expected_modules = {
            "a": {"batch-orchestrator"},
            "b1": {"batch-trigger", "batch-worker/process", "batch-worker/dispatch"},
            "b2-workers": {"batch-worker/import", "batch-worker/export"},
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
