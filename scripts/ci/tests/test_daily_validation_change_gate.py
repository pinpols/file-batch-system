from __future__ import annotations

import importlib.util
import unittest
from datetime import datetime, timezone
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "daily-validation-change-gate.py"
SPEC = importlib.util.spec_from_file_location("daily_validation_change_gate", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
CHANGE_GATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHANGE_GATE)


class DailyValidationDateTest(unittest.TestCase):
    def test_delayed_schedule_after_midnight_uses_previous_schedule_day(self) -> None:
        now = datetime.fromisoformat("2026-09-27T01:18:39+08:00")

        self.assertEqual(
            "2026-09-26",
            CHANGE_GATE.validation_day(now, "schedule", "31 13 * * *").isoformat(),
        )

    def test_schedule_at_configured_time_uses_current_day(self) -> None:
        now = datetime.fromisoformat("2026-09-26T13:31:00+00:00")

        self.assertEqual(
            "2026-09-26",
            CHANGE_GATE.validation_day(now, "schedule", "31 13 * * *").isoformat(),
        )

    def test_manual_dispatch_uses_current_beijing_day(self) -> None:
        now = datetime.fromisoformat("2026-09-27T01:18:39+08:00")

        self.assertEqual(
            "2026-09-27",
            CHANGE_GATE.validation_day(now, "workflow_dispatch", "31 13 * * *").isoformat(),
        )

    def test_rejects_non_daily_cron(self) -> None:
        now = datetime.now(timezone.utc)

        with self.assertRaises(ValueError):
            CHANGE_GATE.validation_day(now, "schedule", "31 13 * * 1")


if __name__ == "__main__":
    unittest.main()
