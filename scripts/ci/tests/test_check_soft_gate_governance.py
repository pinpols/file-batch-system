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
                    "id": "TEST_GATE", "owner": "@team", "baseline": "one",
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


if __name__ == "__main__":
    unittest.main()
