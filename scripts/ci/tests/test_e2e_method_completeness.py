# 用途：验证端到端测试按方法选取时的执行报告检查。
# 用法：在仓库根目录执行 python3 -m unittest scripts/ci/tests/test_e2e_method_completeness.py。
"""E2E 方法分片报告检查。"""

import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
GUARD = ROOT / "scripts/ci/check-e2e-run-completeness.sh"


class E2eMethodCompletenessTest(unittest.TestCase):
    def check(self, selectors, methods):
        with tempfile.TemporaryDirectory() as directory:
            xml = "<testsuite>" + "".join(
                f'<testcase name="{name}">{status}</testcase>' for name, status in methods
            ) + "</testsuite>"
            Path(directory, "TEST-example.RoutingTest.xml").write_text(xml, encoding="utf-8")
            return subprocess.run(
                ["bash", str(GUARD), selectors, directory],
                capture_output=True,
                text=True,
                check=False,
            )

    def test_accepts_exact_method_reports(self):
        result = self.check("RoutingTest#alpha+beta", [("alpha", ""), ("beta", "")])
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_rejects_missing_method(self):
        result = self.check("RoutingTest#alpha+beta", [("alpha", "")])
        self.assertNotEqual(result.returncode, 0)

    def test_rejects_skipped_method(self):
        result = self.check("RoutingTest#alpha+beta", [("alpha", ""), ("beta", "<skipped/>")])
        self.assertNotEqual(result.returncode, 0)

    def test_rejects_unexpected_method(self):
        result = self.check("RoutingTest#alpha", [("alpha", ""), ("beta", "")])
        self.assertNotEqual(result.returncode, 0)

    def test_rejects_entire_class_skipped(self):
        result = self.check("RoutingTest", [("alpha", "<skipped/>")])
        self.assertNotEqual(result.returncode, 0)

    def test_rejects_empty_class_report(self):
        result = self.check("RoutingTest", [])
        self.assertNotEqual(result.returncode, 0)

    def test_accepts_class_with_executed_test(self):
        result = self.check("RoutingTest", [("alpha", "")])
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_accepts_class_with_one_executed_and_one_skipped_test(self):
        result = self.check("RoutingTest", [("alpha", ""), ("beta", "<skipped/>")])
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_rejects_failed_class_report(self):
        result = self.check("RoutingTest", [("alpha", "<failure/>")])
        self.assertNotEqual(result.returncode, 0)


if __name__ == "__main__":
    unittest.main()
