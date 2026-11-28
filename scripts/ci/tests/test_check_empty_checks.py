# 用途：验证空值判断门禁对格式化换行与真实新增判断的区分。
# 用法：在仓库根目录执行 python3 -m unittest scripts.ci.tests.test_check_empty_checks。
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


CI_DIR = Path(__file__).resolve().parents[1]
SOURCE = "module/src/main/java/example/Sample.java"


class EmptyValuesDiffTest(unittest.TestCase):
    def run_guard(self, before: str, after: str) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory(prefix="empty-checks-test-") as workdir:
            root = Path(workdir)
            ci = root / "scripts" / "ci"
            ci.mkdir(parents=True)
            shutil.copy2(CI_DIR / "check-empty-checks.py", ci / "check-empty-checks.py")
            sample = root / SOURCE
            sample.parent.mkdir(parents=True)
            sample.write_text(before, encoding="utf-8")
            subprocess.run(["git", "init", "-q"], cwd=root, check=True)
            subprocess.run(
                ["git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                 "add", "."],
                cwd=root,
                check=True,
            )
            subprocess.run(
                ["git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                 "commit", "-q", "-m", "fixture"],
                cwd=root,
                check=True,
            )
            sample.write_text(after, encoding="utf-8")
            subprocess.run(
                ["git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                 "commit", "-q", "-am", "change"],
                cwd=root,
                check=True,
            )
            return subprocess.run(
                [sys.executable, "scripts/ci/check-empty-checks.py", "--base", "HEAD~1"],
                cwd=root,
                capture_output=True,
                text=True,
            )

    def test_allows_wrapping_only_change(self) -> None:
        before = (
            "package example;\n"
            "class Sample {\n"
            "  String f(String value) {\n"
            "    String resolved =\n"
            "        value != null ? value : \"x\";\n"
            "    return resolved;\n"
            "  }\n"
            "}\n"
        )
        after = (
            "package example;\n"
            "class Sample {\n"
            "    String f(String value) {\n"
            "        String resolved = value != null ? value : \"x\";\n"
            "        return resolved;\n"
            "    }\n"
            "}\n"
        )

        result = self.run_guard(before, after)

        self.assertEqual(result.returncode, 0, result.stderr)

    def test_allows_native_null_check(self) -> None:
        before = (
            "package example;\n"
            "class Sample {\n"
            "  void f(String value) {\n"
            "  }\n"
            "}\n"
        )
        after = (
            "package example;\n"
            "class Sample {\n"
            "    void f(String value) {\n"
            "        if (value == null) {\n"
            "            return;\n"
            "        }\n"
            "    }\n"
            "}\n"
        )

        result = self.run_guard(before, after)

        self.assertEqual(result.returncode, 0, result.stderr)

    def test_blocks_real_new_empty_check(self) -> None:
        before = (
            "package example;\n"
            "class Sample {\n"
            "  void f(java.util.List<String> values) {\n"
            "  }\n"
            "}\n"
        )
        after = (
            "package example;\n"
            "class Sample {\n"
            "    void f(java.util.List<String> values) {\n"
            "        if (values.isEmpty()) {\n"
            "            return;\n"
            "        }\n"
            "    }\n"
            "}\n"
        )

        result = self.run_guard(before, after)

        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("字符串 / 集合空判断", result.stderr)

    def test_allows_central_empty_check(self) -> None:
        before = (
            "package example;\n"
            "class Sample {\n"
            "  void f(java.util.List<String> values) {\n"
            "  }\n"
            "}\n"
        )
        after = (
            "package example;\n"
            "class Sample {\n"
            "    void f(java.util.List<String> values) {\n"
            "        if (EmptyValues.isEmpty(values)) {\n"
            "            return;\n"
            "        }\n"
            "    }\n"
            "}\n"
        )

        result = self.run_guard(before, after)

        self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
