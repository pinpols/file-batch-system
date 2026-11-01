# 用途：验证端到端测试分片清单的覆盖与重复检查。
# 用法：在仓库根目录执行 python3 -m unittest scripts/ci/tests/test_e2e_shard_coverage.py。
"""E2E 分片清单的覆盖和重复执行检查。"""

import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


class E2eShardCoverageTest(unittest.TestCase):
    def test_filesystem_backend_runs_in_its_own_configured_shard(self):
        root = Path(__file__).resolve().parents[3]
        for name in ("full-ci-gate.yml", "staging-gate.yml"):
            with self.subTest(workflow=name):
                workflow = (root / ".github/workflows" / name).read_text(encoding="utf-8")
                shard = workflow.split("          - id: 19\n", 1)[1].split("\n    steps:", 1)[0]
                self.assertIn("FilesystemBackendPipelineE2eIT", shard)
                self.assertIn('"$TESTS" == "FilesystemBackendPipelineE2eIT"', workflow)
                self.assertIn('-Dbatch.test.storage.backend=filesystem', workflow)

    def test_long_process_recoveries_run_in_separate_shards(self):
        root = Path(__file__).resolve().parents[3]
        for name in ("full-ci-gate.yml", "staging-gate.yml"):
            with self.subTest(workflow=name):
                workflow = (root / ".github/workflows" / name).read_text(encoding="utf-8")
                shards = workflow.split("          - id: ")[1:]
                push = [shard for shard in shards if "JobPushProcessRecoveryE2eIT" in shard]
                launch = [shard for shard in shards if "LaunchProcessCrashRecoveryE2eIT" in shard]
                self.assertEqual(1, len(push))
                self.assertEqual(1, len(launch))
                self.assertNotEqual(push[0], launch[0])

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        scripts = self.root / "scripts/ci"
        scripts.mkdir(parents=True)
        original = Path(__file__).resolve().parents[1] / "check-e2e-shard-coverage.sh"
        shutil.copy(original, scripts / original.name)
        tests = self.root / "batch-e2e-tests/src/test/java"
        tests.mkdir(parents=True)
        (tests / "AlphaE2eIT.java").write_text("class AlphaE2eIT {}\n", encoding="utf-8")
        (tests / "BetaE2eIT.java").write_text("class BetaE2eIT {}\n", encoding="utf-8")
        (self.root / ".github/workflows").mkdir(parents=True)

    def run_guard(self, full, staging):
        for name, body in (("full-ci-gate.yml", full), ("staging-gate.yml", staging)):
            (self.root / ".github/workflows" / name).write_text(body, encoding="utf-8")
        return subprocess.run(
            ["bash", "scripts/ci/check-e2e-shard-coverage.sh"],
            cwd=self.root,
            capture_output=True,
            text=True,
            check=False,
        )

    def test_accepts_unique_complete_lists(self):
        contents = "  AlphaE2eIT,\n  BetaE2eIT\n"
        self.assertEqual(self.run_guard(contents, contents).returncode, 0)

    def test_rejects_duplicate_class(self):
        unique = "  AlphaE2eIT,\n  BetaE2eIT\n"
        duplicate = "  AlphaE2eIT,\n  BetaE2eIT,\n  AlphaE2eIT\n"
        result = self.run_guard(duplicate, unique)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("AlphaE2eIT", result.stdout)

    def test_rejects_missing_class(self):
        result = self.run_guard("  AlphaE2eIT\n", "  AlphaE2eIT,\n  BetaE2eIT\n")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("BetaE2eIT", result.stdout)

    def test_parameterized_method_shards_are_checked_for_missing_and_duplicates(self):
        source = self.root / "batch-e2e-tests/src/test/java/AlphaE2eIT.java"
        source.write_text(
            'class AlphaE2eIT {\n@Test\n@DisplayName("一")\nvoid first() {}\n'
            '@ParameterizedTest(name = "{0}")\n@EnumSource(Mode.class)\n'
            '@DisplayName("二")\nvoid second(Mode mode) {}\n}\n',
            encoding="utf-8",
        )
        complete = "  AlphaE2eIT#first,\n  AlphaE2eIT#second,\n  BetaE2eIT\n"
        self.assertEqual(self.run_guard(complete, complete).returncode, 0)
        missing = "  AlphaE2eIT#first,\n  BetaE2eIT\n"
        self.assertNotEqual(self.run_guard(missing, complete).returncode, 0)
        duplicate = "  AlphaE2eIT#first+second,\n  AlphaE2eIT#second,\n  BetaE2eIT\n"
        self.assertNotEqual(self.run_guard(duplicate, complete).returncode, 0)

    def test_method_shards_cover_each_method_once(self):
        source = self.root / "batch-e2e-tests/src/test/java/AlphaE2eIT.java"
        source.write_text(
            'class AlphaE2eIT {\n@Test\n@DisplayName("一")\nvoid first() {}\n'
            '@Test\n@DisplayName("二")\nvoid second() {}\n}\n',
            encoding="utf-8",
        )
        valid = "  AlphaE2eIT#first,\n  AlphaE2eIT#second,\n  BetaE2eIT\n"
        self.assertEqual(self.run_guard(valid, valid).returncode, 0)
        missing = "  AlphaE2eIT#first,\n  BetaE2eIT\n"
        self.assertNotEqual(self.run_guard(missing, valid).returncode, 0)
        repeated = "  AlphaE2eIT#first+second,\n  AlphaE2eIT#second,\n  BetaE2eIT\n"
        self.assertNotEqual(self.run_guard(repeated, valid).returncode, 0)
        mixed = "  AlphaE2eIT,\n  AlphaE2eIT#first+second,\n  BetaE2eIT\n"
        self.assertNotEqual(self.run_guard(mixed, valid).returncode, 0)


if __name__ == "__main__":
    unittest.main()
