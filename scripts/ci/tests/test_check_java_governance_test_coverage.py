import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path


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
            self._write_test(root, "module-a", "example.arch", "BoundaryArchTest")
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

    @staticmethod
    def _write_test(root: Path, module: str, package: str, class_name: str) -> None:
        source = root / module / "src" / "test" / "java" / Path(*package.split(".")) / f"{class_name}.java"
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_text(f"package {package};\nclass {class_name} {{}}\n", encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
