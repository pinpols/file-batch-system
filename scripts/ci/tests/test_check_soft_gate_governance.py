# 用途：回归验证软门禁治理。
# 用法：在仓库根目录执行 python3 -m unittest scripts/ci/tests/test_check_soft_gate_governance.py。
import datetime as dt
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "check-soft-gate-governance.py"
SPEC = importlib.util.spec_from_file_location("check_soft_gate_governance", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class SoftGateGovernanceTest(unittest.TestCase):
    def test_accepts_registered_soft_gate(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".github/workflows").mkdir(parents=True)
            (root / "pom.xml").write_text("<project/>\n", encoding="utf-8")
            (root / ".github/workflows/test.yml").write_text(
                "# soft-gate-id: TEST_GATE\ncontinue-on-error: true\n", encoding="utf-8"
            )
            registry = root / "registry.json"
            registry.write_text(
                json.dumps({"gates": [{
                    "id": "TEST_GATE", "group": "quality", "mode": "report",
                    "control": "workflow-continue-on-error", "owner": "@team", "baseline": "one",
                    "promotionDeadline": "2026-12-31", "target": "hard gate"
                }]}), encoding="utf-8"
            )
            self.assertEqual([], MODULE.validate(dt.date(2026, 10, 7), root, registry))

    def test_rejects_unclassified_continue_on_error(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".github/workflows").mkdir(parents=True)
            (root / "pom.xml").write_text("<project/>\n", encoding="utf-8")
            (root / ".github/workflows/test.yml").write_text(
                "continue-on-error: true\n", encoding="utf-8"
            )
            registry = root / "registry.json"
            registry.write_text('{"gates": []}', encoding="utf-8")
            self.assertEqual(1, len(MODULE.validate(dt.date(2026, 10, 7), root, registry)))

    def test_accepts_registered_runner_report_mode(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".github/workflows").mkdir(parents=True)
            (root / "pom.xml").write_text("<project/>\n", encoding="utf-8")
            (root / ".github/workflows/test.yml").write_text(
                "# soft-gate-id: TEST_REPORT\n"
                "run: bash scripts/ci/run-gate.sh --group quality --mode report TEST_REPORT sample -- false\n",
                encoding="utf-8",
            )
            registry = root / "registry.json"
            registry.write_text(
                json.dumps({"gates": [{
                    "id": "TEST_REPORT", "group": "quality", "mode": "report",
                    "control": "run-gate", "owner": "@team", "baseline": "one",
                    "promotionDeadline": "2026-12-31", "target": "hard gate"
                }]}), encoding="utf-8"
            )
            self.assertEqual([], MODULE.validate(dt.date(2026, 10, 7), root, registry))

    def test_rejects_runner_mode_without_registry_marker(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".github/workflows").mkdir(parents=True)
            (root / "pom.xml").write_text("<project/>\n", encoding="utf-8")
            (root / ".github/workflows/test.yml").write_text(
                "run: bash scripts/ci/run-gate.sh --mode ignore TEST sample -- true\n",
                encoding="utf-8",
            )
            registry = root / "registry.json"
            registry.write_text('{"gates": []}', encoding="utf-8")
            violations = MODULE.validate(dt.date(2026, 10, 7), root, registry)
            self.assertTrue(any("未登记报告/忽略模式" in item for item in violations))

    def test_rejects_legacy_script_gate_skip_switch(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".github/workflows").mkdir(parents=True)
            (root / "scripts/ci").mkdir(parents=True)
            (root / "pom.xml").write_text("<project/>\n", encoding="utf-8")
            (root / "scripts/ci/check-example.sh").write_text(
                '[[ "${BATCH_CI_SKIP_EXAMPLE_GATE:-0}" == 1 ]] && exit 0\n', encoding="utf-8"
            )
            registry = root / "registry.json"
            registry.write_text('{"gates": []}', encoding="utf-8")
            violations = MODULE.validate(dt.date(2026, 10, 7), root, registry)
            self.assertTrue(any("禁止脚本自定义" in item for item in violations))

    def test_rejects_workflow_environment_mode_bypass(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".github/workflows").mkdir(parents=True)
            (root / "pom.xml").write_text("<project/>\n", encoding="utf-8")
            (root / ".github/workflows/test.yml").write_text(
                "env:\n  BATCH_GATE_ENABLED: '0'\n", encoding="utf-8"
            )
            registry = root / "registry.json"
            registry.write_text('{"gates": []}', encoding="utf-8")
            violations = MODULE.validate(dt.date(2026, 10, 7), root, registry)
            self.assertTrue(any("workflow 禁止通过环境变量弱化门禁" in item for item in violations))


if __name__ == "__main__":
    unittest.main()
