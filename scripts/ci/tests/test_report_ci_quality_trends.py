# 用途：验证CI 测试质量快照与历史趋势汇总。
# 用法：在仓库根目录执行 python3 -m unittest scripts/ci/tests/test_report_ci_quality_trends.py。
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "report-ci-quality-trends.py"
SPEC = importlib.util.spec_from_file_location("report_ci_quality_trends", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class CiQualityTrendReportTest(unittest.TestCase):
    def test_average_duration_uses_completed_runs_only(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "runs.json"
            path.write_text(json.dumps({"workflow_runs": [
                {"status": "completed", "conclusion": "success", "created_at": "2026-10-01T00:00:00Z", "updated_at": "2026-10-01T00:10:00Z"},
                {"status": "in_progress", "conclusion": None, "created_at": "2026-10-02T00:00:00Z", "updated_at": "2026-10-02T00:01:00Z"},
            ]}), encoding="utf-8")
            result = MODULE.parse_workflow_runs(path)
            self.assertEqual(600.0, result["averageDurationSeconds"])
            self.assertEqual(1.0, result["successRate"])
            self.assertEqual(2, result["runs"])

    def test_average_duration_unavailable_when_no_run_is_complete(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "runs.json"
            path.write_text(json.dumps({"workflow_runs": [
                {"status": "queued", "conclusion": None, "created_at": "2026-10-01T00:00:00Z", "updated_at": "2026-10-01T00:01:00Z"},
            ]}), encoding="utf-8")
            result = MODULE.parse_workflow_runs(path)
            self.assertIsNone(result["averageDurationSeconds"])
            self.assertIsNone(result["successRate"])

    def test_parses_test_and_flaky_results(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "TEST-DemoE2eIT.xml"
            path.write_text(
                '<testsuite name="DemoE2eIT" tests="2" failures="0" errors="0" skipped="1" time="1.5">'
                '<testcase name="a"><flakyFailure message="timeout"/></testcase><testcase name="b"/>'
                '</testsuite>', encoding="utf-8"
            )
            report = MODULE.parse_reports(Path(directory))
            self.assertEqual(2, report["tests"])
            self.assertEqual(1, report["flakyFirstFailures"])
            self.assertEqual(1, report["e2e"]["suites"])
            self.assertEqual(1, report["failureCategories"]["timeout"])

    def test_distinguishes_skipped_suites_and_reports_shard_duration(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for shard, name, tests, skipped, duration in [
                ("7", "SlowE2eIT", 2, 0, "12.5"),
                ("17", "SkippedE2eIT", 1, 1, "0.2"),
            ]:
                target = root / f"full-ci-e2e-shard-{shard}-reports"
                target.mkdir()
                (target / f"TEST-{name}.xml").write_text(
                    f'<testsuite name="{name}" tests="{tests}" failures="0" '
                    f'errors="0" skipped="{skipped}" time="{duration}"/>',
                    encoding="utf-8",
                )
            report = MODULE.parse_reports(root)
            self.assertEqual(1, report["e2e"]["successfulSuites"])
            self.assertEqual(1, report["e2e"]["skippedSuites"])
            self.assertEqual(12.5, report["e2eShards"]["7"]["durationSeconds"])
            self.assertEqual(0.2, report["e2eShards"]["17"]["durationSeconds"])
            text = MODULE.markdown({
                "tests": report,
                "workflow": {"successRate": None, "averageDurationSeconds": None, "runs": 0},
                "source": {
                    "disabledTests": 0, "flakyQuarantines": 0,
                    "suppressionExceptions": 0, "softGates": 0,
                },
            })
            self.assertIn("| E2E 套件成功 / 失败 / 全部跳过 | 1 / 0 / 1 |", text)
            self.assertIn("| 7 | 1 | 2 / 0 | 12.5 |", text)
            self.assertIn("测试累计耗时（非作业墙钟）", text)


if __name__ == "__main__":
    unittest.main()
