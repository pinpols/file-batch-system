# 用途：验证控制流增量门禁只拦截新增的冗余 else 与业务层通用异常捕获。
# 用法：在仓库根目录执行 python3 -m unittest scripts.ci.tests.test_check_java_control_flow。
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

CI_DIR = Path(__file__).resolve().parents[1]
SCRIPT = CI_DIR / "check-java-control-flow.py"
DEFAULT_PATH = "module/src/main/java/example/application/OrderService.java"


class ControlFlowGateTest(unittest.TestCase):
    def run_guard(self, before: str, after: str, path: str = DEFAULT_PATH) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory(prefix="control-flow-test-") as workdir:
            root = Path(workdir)
            ci = root / "scripts" / "ci"
            ci.mkdir(parents=True)
            shutil.copy2(SCRIPT, ci / SCRIPT.name)
            sample = root / path
            sample.parent.mkdir(parents=True)
            sample.write_text(before, encoding="utf-8")
            subprocess.run(["git", "init", "-q"], cwd=root, check=True)
            subprocess.run(
                ["git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "add", "."],
                cwd=root,
                check=True,
            )
            subprocess.run(
                ["git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                 "commit", "-q", "-m", "before"],
                cwd=root,
                check=True,
            )
            sample.write_text(after, encoding="utf-8")
            subprocess.run(
                ["git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                 "commit", "-q", "-am", "after"],
                cwd=root,
                check=True,
            )
            return subprocess.run(
                [sys.executable, "scripts/ci/check-java-control-flow.py", "--base", "HEAD~1"],
                cwd=root,
                capture_output=True,
                text=True,
            )

    def test_rejects_redundant_else(self) -> None:
        before = "class OrderService { void run(boolean ok) { } }\n"
        after = (
            "class OrderService {\n"
            "  void run(boolean ok) {\n"
            "    if (ok) {\n"
            "      return;\n"
            "    } else {\n"
            "      throw new IllegalStateException();\n"
            "    }\n"
            "  }\n"
            "}\n"
        )
        result = self.run_guard(before, after)
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("else 冗余", result.stderr)

    def test_allows_safe_else(self) -> None:
        before = "class OrderService { void run(boolean ok) { } }\n"
        after = (
            "class OrderService {\n"
            "  void run(boolean ok) {\n"
            "    if (ok) {\n"
            "      handle();\n"
            "    } else {\n"
            "      fallback();\n"
            "    }\n"
            "  }\n"
            "}\n"
        )
        result = self.run_guard(before, after)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_allows_nested_if_as_last_statement(self) -> None:
        before = "class OrderService { void run(boolean ok) { } }\n"
        after = (
            "class OrderService {\n"
            "  void run(boolean ok) {\n"
            "    if (ok) {\n"
            "      if (ok) { return; }\n"
            "    } else {\n"
            "      fallback();\n"
            "    }\n"
            "  }\n"
            "}\n"
        )
        result = self.run_guard(before, after)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_rejects_generic_catch_in_business_layer(self) -> None:
        before = "class OrderService { void run() { } }\n"
        after = (
            "class OrderService {\n"
            "  void run() {\n"
            "    try {\n"
            "      handle();\n"
            "    } catch (Exception ex) {\n"
            "      throw new IllegalStateException(ex);\n"
            "    }\n"
            "  }\n"
            "}\n"
        )
        result = self.run_guard(before, after)
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("业务层捕获", result.stderr)

    def test_allows_generic_catch_in_infrastructure(self) -> None:
        before = "class PaymentGateway { void run() { } }\n"
        after = (
            "class PaymentGateway {\n"
            "  void run() {\n"
            "    try {\n"
            "      handle();\n"
            "    } catch (Exception ex) {\n"
            "      throw new IllegalStateException(ex);\n"
            "    }\n"
            "  }\n"
            "}\n"
        )
        result = self.run_guard(before, after, "module/src/main/java/example/infrastructure/PaymentGateway.java")
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_allows_generic_catch_in_boundary_class(self) -> None:
        before = "class PaymentScheduler { void run() { } }\n"
        after = (
            "class PaymentScheduler {\n"
            "  void run() {\n"
            "    try {\n"
            "      handle();\n"
            "    } catch (Throwable ex) {\n"
            "      log.error(\"scheduler failed\", ex);\n"
            "    }\n"
            "  }\n"
            "}\n"
        )
        result = self.run_guard(
            before, after, "module/src/main/java/example/application/PaymentScheduler.java"
        )
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_ignores_comments_and_strings(self) -> None:
        before = "class OrderService { void run() { } }\n"
        after = (
            "class OrderService {\n"
            "  void run() {\n"
            "    // if (ok) { return; } else { fallback(); }\n"
            "    String sample = \"catch (Exception ex)\";\n"
            "  }\n"
            "}\n"
        )
        result = self.run_guard(before, after)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_does_not_recheck_unchanged_violation(self) -> None:
        before = (
            "class OrderService {\n"
            "  void run(boolean ok) {\n"
            "    if (ok) { return; } else { fallback(); }\n"
            "  }\n"
            "}\n"
        )
        after = before.replace("void run(boolean ok) {", "void run(boolean ok) {\n    int untouched = 1;")
        result = self.run_guard(before, after)
        self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
