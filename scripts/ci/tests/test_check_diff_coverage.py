import importlib.util
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "check-diff-coverage.py"
SPEC = importlib.util.spec_from_file_location("check_diff_coverage", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class DiffCoverageTest(unittest.TestCase):
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


if __name__ == "__main__":
    unittest.main()
