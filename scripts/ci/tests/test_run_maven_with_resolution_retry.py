# 用途：验证 Maven 重试包装只对依赖解析类瞬态失败重试，编译/测试失败立即失败。
# 用法：在仓库根目录执行 python3 -m unittest scripts.ci.tests.test_run_maven_with_resolution_retry。
import os
import subprocess
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "run-maven-with-resolution-retry.sh"


class MavenResolutionRetryTest(unittest.TestCase):
    def run_wrapper(self, command: str, attempts: int = 3) -> subprocess.CompletedProcess[str]:
        environment = dict(os.environ)
        environment["MAVEN_RESOLUTION_RETRY_ATTEMPTS"] = str(attempts)
        environment["MAVEN_RESOLUTION_RETRY_BACKOFF_SECONDS"] = "0"
        return subprocess.run(
            ["bash", str(SCRIPT), "--", "bash", "-c", command],
            env=environment,
            text=True,
            capture_output=True,
            check=False,
        )

    def test_success_runs_once(self) -> None:
        result = self.run_wrapper("echo ok")

        self.assertEqual(0, result.returncode)
        self.assertIn("ok", result.stdout)

    def test_transient_resolution_failure_is_retried(self) -> None:
        with tempfile.TemporaryDirectory(prefix="mvn-retry-") as workdir:
            marker = Path(workdir) / "attempt"
            command = (
                f"if [ ! -f {marker} ]; then touch {marker}; "
                "echo 'Could not transfer artifact org.jacoco:jacoco-maven-plugin:pom:0.8.15'; "
                "exit 1; fi; echo second-ok"
            )
            result = self.run_wrapper(command)

        self.assertEqual(0, result.returncode)
        self.assertIn("second-ok", result.stdout)

    def test_business_failure_is_not_retried(self) -> None:
        with tempfile.TemporaryDirectory(prefix="mvn-retry-") as workdir:
            counter = Path(workdir) / "attempts"
            command = f"echo x >> {counter}; echo 'Tests run: 1, Failures: 1'; exit 7"
            result = self.run_wrapper(command)
            attempts = len(counter.read_text(encoding="utf-8").splitlines())

        self.assertEqual(7, result.returncode)
        self.assertEqual(1, attempts)

    def test_retry_is_bounded(self) -> None:
        result = self.run_wrapper("echo 'Could not resolve dependencies'; exit 5", attempts=2)

        self.assertEqual(5, result.returncode)
        self.assertEqual(1, result.stderr.count("依赖解析类瞬态失败"))
        self.assertIn("放弃重试", result.stderr)

    def test_usage_error(self) -> None:
        result = subprocess.run(
            ["bash", str(SCRIPT)], text=True, capture_output=True, check=False)

        self.assertEqual(2, result.returncode)
        self.assertIn("usage", result.stderr)


if __name__ == "__main__":
    unittest.main()
