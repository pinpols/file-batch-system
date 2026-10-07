import importlib.util
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "report-ci-quality-trends.py"
SPEC = importlib.util.spec_from_file_location("report_ci_quality_trends", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class CiQualityTrendReportTest(unittest.TestCase):
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


if __name__ == "__main__":
    unittest.main()
