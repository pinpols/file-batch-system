# 用途：验证nightly 的北京时间日期与变更范围判断。
# 用法：在仓库根目录执行 python3 -m unittest scripts/ci/tests/test_daily_validation_change_gate.py。
from __future__ import annotations

import importlib.util
import subprocess
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import Mock, call, patch


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

class SuccessfulValidationBaselineTest(unittest.TestCase):
    def test_unexecuted_skipped_run_does_not_supply_baseline(self) -> None:
        runs = [
            {"head_sha": "new", "conclusion": "success", "jobs": [{"name": "sim-and-strict", "conclusion": "skipped"}]},
            {"head_sha": "old", "conclusion": "success", "jobs": [{"name": "sim-and-strict", "conclusion": "success"}]},
        ]
        self.assertEqual("old", CHANGE_GATE.last_validated_sha(runs))

    def test_failed_simulation_requires_retry_even_without_new_commit(self) -> None:
        runs = [
            {"head_sha": "same", "conclusion": "failure", "jobs": [{"name": "sim-and-strict", "conclusion": "failure"}]},
            {"head_sha": "same", "conclusion": "success", "jobs": [{"name": "sim-and-strict", "conclusion": "success"}]},
        ]
        self.assertIsNone(CHANGE_GATE.last_validated_sha(runs))

    def test_renamed_job_is_accepted(self) -> None:
        runs = [{"head_sha": "abc", "conclusion": "success", "jobs": [{"name": "Run simulation and strict validation", "conclusion": "success"}]}]
        self.assertEqual("abc", CHANGE_GATE.last_validated_sha(runs))

    @patch.dict("os.environ", {"GITHUB_REPOSITORY": "owner/repo"})
    @patch.object(CHANGE_GATE, "gh_json")
    def test_failed_startup_does_not_reuse_older_success(self, api) -> None:
        api.side_effect = [
            {"workflow_runs": [{"id": 2, "head_sha": "new", "conclusion": "failure"}]},
            {"jobs": []},
        ]
        self.assertIsNone(CHANGE_GATE.successful_baseline())

    @patch.dict("os.environ", {"GITHUB_REPOSITORY": "owner/repo"})
    @patch.object(CHANGE_GATE, "gh_json")
    def test_skipped_run_is_not_mistaken_for_executed_validation(self, api) -> None:
        api.side_effect = [
            {"workflow_runs": [{"id": 2, "head_sha": "new", "conclusion": "success"}, {"id": 1, "head_sha": "old", "conclusion": "success"}]},
            {"jobs": [{"name": "sim-and-strict", "conclusion": "skipped"}]},
            {"jobs": [{"name": "sim-and-strict", "conclusion": "success"}]},
        ]
        self.assertEqual("old", CHANGE_GATE.successful_baseline())

    def test_only_document_changes_do_not_require_validation(self) -> None:
        self.assertEqual([], CHANGE_GATE.relevant_paths(["docs/example.md", "NOTICE", "README.rst"]))

    def test_code_and_configuration_changes_require_validation(self) -> None:
        self.assertEqual(["db/migration/V1.sql", "src/main/Foo.java"], CHANGE_GATE.relevant_paths(["README.md", "src/main/Foo.java", "db/migration/V1.sql"]))


class DailyValidationDecisionTest(unittest.TestCase):
    @patch.dict("os.environ", {"VALIDATION_EVENT": "workflow_dispatch"}, clear=True)
    @patch.object(CHANGE_GATE, "report_gate_result")
    @patch.object(CHANGE_GATE, "output")
    @patch.object(CHANGE_GATE.subprocess, "run")
    @patch.object(CHANGE_GATE, "successful_baseline", return_value="abc")
    def test_only_docs_since_success_skip(self, baseline, run, output, report) -> None:
        run.side_effect = [Mock(returncode=0), Mock(stdout="docs/guide.md\n")]
        self.assertEqual(0, CHANGE_GATE.main())
        output.assert_has_calls([call("should_run", "false")])
        self.assertEqual("SKIP", report.call_args.args[0])

    @patch.dict("os.environ", {"VALIDATION_EVENT": "workflow_dispatch"}, clear=True)
    @patch.object(CHANGE_GATE, "report_gate_result")
    @patch.object(CHANGE_GATE, "output")
    @patch.object(CHANGE_GATE.subprocess, "run")
    @patch.object(CHANGE_GATE, "successful_baseline", return_value="abc")
    def test_nonancestor_baseline_requires_validation(self, baseline, run, output, report) -> None:
        run.return_value = Mock(returncode=1)
        self.assertEqual(0, CHANGE_GATE.main())
        output.assert_has_calls([call("should_run", "true")])
        self.assertEqual("DAILY_SIM_STRICT_RETRY_REQUIRED", report.call_args.args[1])

    @patch.dict("os.environ", {"VALIDATION_EVENT": "workflow_dispatch", "FORCE_VALIDATION": "true"}, clear=True)
    @patch.object(CHANGE_GATE, "report_gate_result")
    @patch.object(CHANGE_GATE, "output")
    @patch.object(CHANGE_GATE, "successful_baseline", side_effect=subprocess.CalledProcessError(1, "gh"))
    def test_manual_force_does_not_depend_on_actions_api(self, baseline, output, report) -> None:
        self.assertEqual(0, CHANGE_GATE.main())
        baseline.assert_not_called()
        output.assert_has_calls([call("should_run", "true")])


if __name__ == "__main__":
    unittest.main()
