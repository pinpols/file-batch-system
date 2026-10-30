# 用途：治理基线扩张守卫测试。
# 用法：在仓库根目录执行 python3 -m unittest scripts/ci/tests/test_governance_baseline_expansion.py。
from __future__ import annotations

import importlib.util
import json
import subprocess
import tempfile
import unittest
from datetime import date
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "check-governance-baseline-expansion.py"
SPEC = importlib.util.spec_from_file_location("governance_baseline_expansion", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class GovernanceBaselineExpansionTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        for relative in MODULE.BASELINE_FILES:
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            if relative.endswith(".txt"):
                path.write_text("# baseline\n", encoding="utf-8")
            else:
                path.write_text('{"schema_version": 1, "counts": {}}\n', encoding="utf-8")
        manifest = self.root / MODULE.APPROVALS_FILE
        manifest.parent.mkdir(parents=True, exist_ok=True)
        manifest.write_text('{"schemaVersion": 1, "approvals": []}\n', encoding="utf-8")
        self.git("init", "-q")
        self.git("config", "user.email", "test@example.invalid")
        self.git("config", "user.name", "Baseline Test")
        self.git("add", ".")
        self.git("commit", "-qm", "baseline")

    def tearDown(self) -> None:
        self.temp.cleanup()

    def git(self, *args: str) -> None:
        subprocess.run(["git", *args], cwd=self.root, check=True, capture_output=True, text=True)

    def add_approval(self, baseline: str, item: str, value: str | None = None) -> None:
        path = self.root / MODULE.APPROVALS_FILE
        manifest = json.loads(path.read_text(encoding="utf-8"))
        record = {
            "baseline": baseline,
            "item": item,
            "owner": "@pinpols",
            "reason": "Required to keep the existing migration window unblocked.",
            "reviewBy": "2030-12-31",
            "removalCondition": "Remove after the listed legacy finding is resolved.",
        }
        if value is not None:
            record["value"] = value
        manifest["approvals"].append(record)
        path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

    def test_rejects_new_text_baseline_entry_without_approval(self) -> None:
        baseline = MODULE.BASELINE_FILES[0]
        (self.root / baseline).write_text("# baseline\nmodule/A.java#ENVIRONMENT#batch.foo\n", encoding="utf-8")

        errors = MODULE.check(self.root, "HEAD", date(2026, 10, 12))

        self.assertTrue(any("unapproved baseline expansion" in error for error in errors))

    def test_accepts_text_expansion_with_complete_unexpired_approval(self) -> None:
        baseline = MODULE.BASELINE_FILES[0]
        item = "module/A.java#ENVIRONMENT#batch.foo"
        (self.root / baseline).write_text(f"# baseline\n{item}\n", encoding="utf-8")
        self.add_approval(baseline, item)

        self.assertEqual([], MODULE.check(self.root, "HEAD", date(2026, 10, 12)))

    def test_java_count_increase_requires_exact_approved_value(self) -> None:
        baseline = MODULE.BASELINE_FILES[2]
        path = self.root / baseline
        path.write_text('{"schema_version": 1, "counts": {"nullable": 2}}\n', encoding="utf-8")
        self.add_approval(baseline, "counts:nullable", "2")

        self.assertEqual([], MODULE.check(self.root, "HEAD", date(2026, 10, 12)))

        self.add_approval(baseline, "counts:nullable", "3")
        path.write_text('{"schema_version": 1, "counts": {"nullable": 3}}\n', encoding="utf-8")
        errors = MODULE.check(self.root, "HEAD", date(2026, 10, 12))
        self.assertTrue(any("approved value does not match" in error for error in errors))

    def test_expired_or_stale_approval_is_rejected(self) -> None:
        baseline = MODULE.BASELINE_FILES[0]
        item = "module/A.java#ENVIRONMENT#batch.foo"
        self.add_approval(baseline, item)

        errors = MODULE.check(self.root, "HEAD", date(2031, 1, 1))

        self.assertTrue(any("approval expired" in error for error in errors))
        self.assertTrue(any("approval is stale" in error for error in errors))

    def test_rejects_invalid_count_in_base_revision(self) -> None:
        baseline = MODULE.BASELINE_FILES[2]
        path = self.root / baseline
        path.write_text('{"schema_version": 1, "counts": {"nullable": true}}\n', encoding="utf-8")
        self.git("add", baseline)
        self.git("commit", "-qm", "invalid base")
        path.write_text('{"schema_version": 1, "counts": {"nullable": 0}}\n', encoding="utf-8")

        with self.assertRaisesRegex(ValueError, "invalid baseline count"):
            MODULE.check(self.root, "HEAD", date(2026, 10, 12))

    def test_diagnostic_label_does_not_select_baseline_format(self) -> None:
        items = MODULE.parse_baseline_items("entry-a\n", "baseline.txt", "HEAD:baseline")

        self.assertEqual({"entry-a": "present"}, items)

if __name__ == "__main__":
    unittest.main()
