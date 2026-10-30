# 用途：回归验证使用 JaCoCo XML 校验本次变更新增/修改的可执行 Java 行覆盖率。
# 用法：在仓库根目录执行 python3 -m unittest scripts/ci/tests/test_check_diff_coverage.py。
import importlib.util
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "check-diff-coverage.py"
SPEC = importlib.util.spec_from_file_location("check_diff_coverage", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class DiffCoverageTest(unittest.TestCase):
    def test_cli_unions_parallel_reports_and_rejects_missing_report(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            script = root / "scripts/ci/check-diff-coverage.py"
            script.parent.mkdir(parents=True)
            shutil.copy(SCRIPT, script)
            binary = root / "bin"
            binary.mkdir()
            git = binary / "git"
            git.write_text('#!/bin/sh\nprintf "+++ b/module/src/main/java/example/Demo.java\\n'
                           '@@ -0,0 +10,2 @@\\n"\n', encoding="utf-8")
            git.chmod(0o755)
            main = root / "module/target/site/jacoco/jacoco.xml"
            main.parent.mkdir(parents=True)
            extra = root / "extra.xml"
            template = ('<report><package name="example"><sourcefile name="Demo.java">'
                        '<line nr="10" ci="{first}"/><line nr="11" ci="{second}"/>'
                        '</sourcefile></package></report>')
            main.write_text(template.format(first=1, second=0), encoding="utf-8")
            extra.write_text(template.format(first=0, second=1), encoding="utf-8")
            env = {**os.environ, "PATH": f"{binary}{os.pathsep}{os.environ['PATH']}"}
            cmd = [sys.executable, str(script), "--base", "main", "--path", "module"]
            single = subprocess.run(cmd, cwd=root, env=env, capture_output=True, text=True)
            self.assertEqual(1, single.returncode)
            self.assertIn("50.0%", single.stdout)
            combined = subprocess.run(cmd + ["--extra-report", f"module={extra}"],
                                      cwd=root, env=env, capture_output=True, text=True)
            self.assertEqual(0, combined.returncode, combined.stderr)
            self.assertIn("100.0%", combined.stdout)
            missing = subprocess.run(cmd + ["--extra-report", "module=missing.xml"],
                                     cwd=root, env=env, capture_output=True, text=True)
            self.assertEqual(1, missing.returncode)
            self.assertIn("无效或缺失", missing.stderr)

    def test_combines_coverage_from_parallel_reports_without_losing_lines(self):
        main = {Path("module/src/main/java/example/Demo.java"): {10: True, 11: False}}
        parallel = {Path("module/src/main/java/example/Demo.java"): {10: False, 11: True, 12: False}}
        MODULE.merge_coverage(main, parallel)
        self.assertEqual({10: True, 11: True, 12: False}, next(iter(main.values())))
        self.assertEqual((2, 3), MODULE.evaluate({next(iter(main)): {10, 11, 12}}, main))

    def test_external_report_uses_explicit_module(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report = root / "downloaded/report.xml"
            report.parent.mkdir()
            report.write_text('<report><package name="example"><sourcefile name="Demo.java">'
                              '<line nr="10" ci="1"/></sourcefile></package></report>', encoding="utf-8")
            coverage = MODULE.jacoco_lines(report, root, module=root / "module")
            self.assertEqual({Path("module/src/main/java/example/Demo.java"): {10: True}}, coverage)

    def test_reads_jacoco_source_lines_and_evaluates_changed_executable_lines(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report = root / "module/target/site/jacoco/jacoco.xml"
            report.parent.mkdir(parents=True)
            report.write_text(
                '<report><package name="example"><sourcefile name="Demo.java">'
                '<line nr="10" mi="0" ci="1"/><line nr="11" mi="1" ci="0"/>'
                '</sourcefile></package></report>', encoding="utf-8"
            )
            coverage = MODULE.jacoco_lines(report, root)
            changed = {Path("module/src/main/java/example/Demo.java"): {10, 11, 12}}
            self.assertEqual((1, 2), MODULE.evaluate(changed, coverage))
            self.assertEqual(
                {Path("module/src/main/java/example/Demo.java"): [11]},
                MODULE.uncovered_lines(changed, coverage),
            )


if __name__ == "__main__":
    unittest.main()
