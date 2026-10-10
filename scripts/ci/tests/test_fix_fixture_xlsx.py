from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

from openpyxl import load_workbook


ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / "scripts/fix-fixture-xlsx.py"
SPEC = importlib.util.spec_from_file_location("fix_fixture_xlsx", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
_dont_write_bytecode = sys.dont_write_bytecode
sys.dont_write_bytecode = True
try:
    SPEC.loader.exec_module(MODULE)
finally:
    sys.dont_write_bytecode = _dont_write_bytecode


class FixFixtureXlsxTest(unittest.TestCase):
    def test_current_sim_fixtures_match_canonical_schema(self) -> None:
        for name in MODULE.TARGETS:
            with self.subTest(name=name):
                result = MODULE.verify(MODULE.SUITE_DIR / name)
                self.assertFalse(MODULE.verify_failed(result), result)

    def test_legacy_monitoring_header_is_rejected(self) -> None:
        source = MODULE.SUITE_DIR / MODULE.TARGETS[0]
        with tempfile.TemporaryDirectory() as temp_dir:
            target = Path(temp_dir) / source.name
            workbook = load_workbook(source)
            worksheet = workbook["job_monitoring_policy"]
            headers = [cell.value for cell in worksheet[1]]
            column = headers.index("dependency_completion_window_seconds") + 1
            worksheet.cell(row=1, column=column).value = "completion_grace_seconds"
            workbook.save(target)

            result = MODULE.verify(target)

        self.assertTrue(MODULE.verify_failed(result))
        self.assertIn("job_monitoring_policy", result["header_mismatches"])


if __name__ == "__main__":
    unittest.main()
