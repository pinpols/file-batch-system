# 用途：回归验证 Java 治理测试清单的 Git 来源、模块归属及失败边界。
# 用法：在仓库根目录执行 python3 -m unittest scripts/ci/tests/test_check_java_governance_test_coverage.py。
import contextlib
import importlib.util
import io
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


SCRIPT = Path(__file__).parents[1] / "check-java-governance-test-coverage.py"
SPEC = importlib.util.spec_from_file_location("check_java_governance_test_coverage", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class JavaGovernanceTestCoverageCheckerTest(unittest.TestCase):
    def test_discovers_arch_and_convention_tests_with_expected_reports(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._init_git(root)
            tracked = self._write_test(root, "module-a", "example.arch", "BoundaryArchTest")
            self._git(root, "add", str(tracked.relative_to(root)))
            self._write_test(root, "module-b", "example.rule", "RepositoryConventionTest")
            self._write_test(root, "module-b", "example.unit", "BusinessGuardTest")

            tests = MODULE.discover(root)

            self.assertEqual(2, len(tests))
            self.assertEqual(
                {
                    "example.arch.BoundaryArchTest",
                    "example.rule.RepositoryConventionTest",
                },
                {test.qualified_name for test in tests},
            )
            self.assertTrue(
                all(str(test.report).endswith(f"TEST-{test.qualified_name}.xml") for test in tests)
            )

    def test_ignores_git_ignored_snapshot_copies(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._init_git(root)
            (root / ".gitignore").write_text("scratch/\n", encoding="utf-8")
            source = self._write_test(root, "module-a", "example.arch", "BoundaryArchTest")
            self._git(root, "add", str(source.relative_to(root)))
            self._write_test(root, "scratch/copied/module-a", "example.arch", "BoundaryArchTest")

            tests = MODULE.discover(root)

            self.assertEqual([source.relative_to(root)], [test.source for test in tests])
            exit_code, _, errors = self._run_main(root)
            self.assertEqual(0, exit_code, errors)

    def test_keeps_tracked_sources_even_when_git_ignore_matches(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._init_git(root)
            (root / ".gitignore").write_text("generated-module/\n", encoding="utf-8")
            tracked = self._write_test(root, "generated-module", "example.arch", "BoundaryArchTest")
            self._git(root, "add", "-f", str(tracked.relative_to(root)))
            self._write_test(root, "generated-module", "example.arch", "IgnoredConventionTest")

            tests = MODULE.discover(root)

            self.assertEqual([tracked.relative_to(root)], [test.source for test in tests])

    def test_rejects_real_duplicate_qualified_names(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._init_git(root)
            source = self._write_test(root, "module-a", "example.arch", "BoundaryArchTest")
            self._git(root, "add", str(source.relative_to(root)))
            self._write_test(root, "module-b", "example.arch", "BoundaryArchTest")

            exit_code, _, errors = self._run_main(root)

            self.assertEqual(1, exit_code)
            self.assertIn("全限定名重复", errors)
            self.assertIn("example.arch.BoundaryArchTest", errors)

    def test_discovers_nonignored_untracked_source_with_whitespace_in_module(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._init_git(root)
            source = self._write_test(root, "module with space\nand newline", "example.rule", "NewConventionTest")

            tests = MODULE.discover(root)

            self.assertEqual([source.relative_to(root)], [test.source for test in tests])
            self.assertEqual(source.parents[5], tests[0].module)

    def test_uses_exact_test_source_boundary_when_ancestor_is_named_src(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._init_git(root)
            source = self._write_test(root, "src/modules/module-a", "example.arch", "BoundaryArchTest")

            tests = MODULE.discover(root)

            self.assertEqual(root / "src/modules/module-a", tests[0].module)
            self.assertEqual(source.relative_to(root), tests[0].source)

    def test_does_not_treat_java_outside_test_source_root_as_governance_tests(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._init_git(root)
            self._write_test(root, "module-a", "example.arch", "BoundaryArchTest")
            outside = root / "docs" / "OutsideArchTest.java"
            outside.parent.mkdir()
            outside.write_text("package example.docs; class OutsideArchTest {}\n", encoding="utf-8")

            tests = MODULE.discover(root)

            self.assertEqual(["example.arch.BoundaryArchTest"], [test.qualified_name for test in tests])

    def test_does_not_require_reports_for_deleted_tracked_sources(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._init_git(root)
            removed = self._write_test(root, "module-a", "example.arch", "RemovedArchTest")
            self._git(root, "add", str(removed.relative_to(root)))
            removed.unlink()
            self._write_test(root, "module-b", "example.rule", "CurrentConventionTest")

            tests = MODULE.discover(root)

            self.assertEqual(["example.rule.CurrentConventionTest"], [test.qualified_name for test in tests])

    def test_fails_explicitly_when_root_is_not_a_git_repository(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_test(root, "module-a", "example.arch", "BoundaryArchTest")

            exit_code, _, errors = self._run_main(root)

            self.assertEqual(1, exit_code)
            self.assertIn("Git 源码枚举失败", errors)

    def test_fails_explicitly_when_git_binary_is_unavailable(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._init_git(root)
            self._write_test(root, "module-a", "example.arch", "BoundaryArchTest")
            with mock.patch("subprocess.run", side_effect=FileNotFoundError("git unavailable")):
                exit_code, _, errors = self._run_main(root)

            self.assertEqual(1, exit_code)
            self.assertIn("git unavailable", errors)

    def test_fails_explicitly_when_git_enumeration_returns_an_error(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._init_git(root)
            self._write_test(root, "module-a", "example.arch", "BoundaryArchTest")
            failure = subprocess.CompletedProcess(["git"], 128, stdout="", stderr="fatal: fixture git failure")
            with mock.patch("subprocess.run", return_value=failure):
                exit_code, _, errors = self._run_main(root)

            self.assertEqual(1, exit_code)
            self.assertIn("Git 源码枚举失败", errors)
            self.assertIn("fixture git failure", errors)

    def test_missing_package_is_still_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._init_git(root)
            source = self._write_test(root, "module-a", "example.arch", "BoundaryArchTest")
            source.write_text("class BoundaryArchTest {}\n", encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "no package declaration"):
                MODULE.discover(root)

    @staticmethod
    def _run_main(root: Path):
        output = io.StringIO()
        errors = io.StringIO()
        with mock.patch.object(sys, "argv", [str(SCRIPT), "--root", str(root)]):
            with contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
                exit_code = MODULE.main()
        return exit_code, output.getvalue(), errors.getvalue()

    @staticmethod
    def _init_git(root: Path) -> None:
        JavaGovernanceTestCoverageCheckerTest._git(root, "init", "--quiet")

    @staticmethod
    def _git(root: Path, *arguments: str) -> None:
        # 只改临时夹具的 Git 索引，不依赖或修改当前工作树的暂存状态。
        subprocess.run(["git", "-C", str(root), *arguments], check=True, capture_output=True, text=True)

    @staticmethod
    def _write_test(root: Path, module: str, package: str, class_name: str) -> Path:
        source = root / module / "src" / "test" / "java" / Path(*package.split(".")) / f"{class_name}.java"
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_text(f"package {package};\nclass {class_name} {{}}\n", encoding="utf-8")
        return source


if __name__ == "__main__":
    unittest.main()
