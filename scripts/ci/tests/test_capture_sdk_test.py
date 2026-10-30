# 用途：验证 SDK 测试日志收集保留真实退出码。
# 用法：python3 -m unittest scripts.ci.tests.test_capture_sdk_test。
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "capture-sdk-test.sh"


class CaptureSdkTest(unittest.TestCase):
    def test_retains_output_and_nonzero_exit(self):
        with tempfile.TemporaryDirectory() as directory:
            env = {**os.environ, "SDK_TEST_REPORT_DIR": directory}
            result = subprocess.run(["bash", str(SCRIPT), "python-contract", "--", "bash", "-c",
                                     "echo test-output; exit 17"], env=env, capture_output=True, text=True)
            self.assertEqual(17, result.returncode)
            self.assertIn("test-output", (Path(directory) / "python-contract.log").read_text())
            self.assertEqual("17", (Path(directory) / "python-contract.exit").read_text().strip())

    def test_rejects_unsafe_name(self):
        result = subprocess.run(["bash", str(SCRIPT), "../bad", "--", "true"], capture_output=True)
        self.assertEqual(2, result.returncode)


if __name__ == "__main__":
    unittest.main()
