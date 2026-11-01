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
    def test_reports_each_artifact_and_staging_e2e_shard(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for artifact, failed in [("staging-gate-e2e-shard-3-reports", 0),
                                     ("pr-gate-unit-it-a-reports", 1)]:
                target = root / artifact
                target.mkdir()
                (target / "TEST-DemoE2eIT.xml").write_text(
                    f'<testsuite name="DemoE2eIT" tests="2" failures="{failed}" '
                    'errors="0" skipped="0" time="2.5"/>', encoding="utf-8")
            report = MODULE.parse_reports(root)
            self.assertEqual(2, report["artifacts"]["pr-gate-unit-it-a-reports"]["tests"])
            self.assertEqual(1, report["artifacts"]["pr-gate-unit-it-a-reports"]["failures"])
            self.assertEqual(2.5, report["e2eShards"]["3"]["durationSeconds"])

    def test_single_download_keeps_actual_artifact_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "module").mkdir()
            (root / "module" / "TEST-Demo.xml").write_text(
                '<testsuite tests="1" failures="0" errors="0" skipped="0"/>', encoding="utf-8")
            report = MODULE.parse_reports(root, ["pr-gate-java-governance-reports"])
            self.assertIn("pr-gate-java-governance-reports", report["artifacts"])
            self.assertEqual([], MODULE.missing_artifacts(
                {"governance": "success"}, report["artifacts"],
                {"governance": "pr-gate-java-governance-reports"}))
            self.assertEqual(["governance: pr-gate-other-reports"], MODULE.missing_artifacts(
                {"governance": "success"}, report["artifacts"],
                {"governance": "pr-gate-other-reports"}))

    def test_single_flattened_e2e_report_retains_shard(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "TEST-SampleE2eIT.xml").write_text(
                '<testsuite name="SampleE2eIT" tests="1" failures="0" errors="0" skipped="0" time="1"/>',
                encoding="utf-8")
            report = MODULE.parse_reports(root, ["full-ci-e2e-shard-7-reports"])
            self.assertEqual(1, report["e2eShards"]["7"]["tests"])

    def test_multiple_downloads_keep_each_artifact_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ["pr-gate-a-reports", "pr-gate-b-reports"]:
                target = root / name
                target.mkdir()
                (target / "TEST-Demo.xml").write_text(
                    '<testsuite tests="1" failures="0" errors="0" skipped="0"/>', encoding="utf-8")
            report = MODULE.parse_reports(root, ["pr-gate-a-reports", "pr-gate-b-reports"])
            self.assertEqual({"pr-gate-a-reports", "pr-gate-b-reports"}, set(report["artifacts"]))

    def test_reports_job_status_even_if_no_artifact_exists(self):
        with tempfile.TemporaryDirectory() as directory:
            jobs = Path(directory) / "jobs.json"
            jobs.write_text(json.dumps({"unit-it-a-main": {"result": "failure"},
                                        "unit-it-b1": {"result": "skipped"}}), encoding="utf-8")
            self.assertEqual({"unit-it-a-main": "failure", "unit-it-b1": "skipped"},
                             MODULE.parse_job_results(jobs))

    def test_reports_missing_artifacts_only_for_successful_jobs(self):
        jobs = {"unit-it-a-main": "success", "unit-it-b1": "failure", "unit-it-b2": "skipped"}
        required = {"unit-it-a-main": "a-reports", "unit-it-b1": "b-reports",
                    "unit-it-b2": "c-reports"}
        self.assertEqual(["unit-it-a-main: a-reports"], MODULE.missing_artifacts(jobs, {}, required))
        self.assertEqual([], MODULE.missing_artifacts(jobs, {"a-reports": {"reports": 1}}, required))

    def test_checks_every_e2e_shard_artifact_for_successful_matrix(self):
        with tempfile.TemporaryDirectory() as directory:
            workflow = Path(directory) / "workflow.yml"
            workflow.write_text("matrix:\n  - id: 1\n  - id: 2\n", encoding="utf-8")
            actual = {"staging-gate-e2e-shard-1-reports": {"reports": 1}}
            self.assertEqual(["e2e-shard-full: staging-gate-e2e-shard-2-reports"],
                             MODULE.missing_e2e_artifacts({"e2e-shard-full": "success"}, actual,
                                                          workflow, "e2e-shard-full", "staging-gate"))
            self.assertEqual([], MODULE.missing_e2e_artifacts({"e2e-shard-full": "failure"}, actual,
                                                             workflow, "e2e-shard-full", "staging-gate"))

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
