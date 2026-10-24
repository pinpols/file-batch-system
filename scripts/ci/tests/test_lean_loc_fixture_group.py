from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    "lean_loc_fixture_group", Path(__file__).resolve().parents[2] / "dev/lean-loc-report.py"
)
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class SharedFixtureOwnershipTest(unittest.TestCase):
    def test_shared_sql_is_test_input_not_production_code(self) -> None:
        self.assertEqual("test", MODULE.group(Path("test-data/sim/bootstrap.sql")))

    def test_shared_parameters_are_test_input_not_application_config(self) -> None:
        self.assertEqual("test", MODULE.group(Path("test-data/sim/atomic-params.json")))

    def test_application_java_keeps_its_production_group(self) -> None:
        self.assertEqual("prod", MODULE.group(Path("batch-orchestrator/src/main/java/example/Service.java")))


if __name__ == "__main__":
    unittest.main()
