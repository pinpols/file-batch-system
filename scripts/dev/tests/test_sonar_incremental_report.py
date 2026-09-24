import csv
import importlib.util
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "sonar-incremental-report.py"
SPEC = importlib.util.spec_from_file_location("sonar_incremental_report", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class SonarIncrementalReportTest(unittest.TestCase):

    def test_parse_changed_lines_merges_hunks_and_ignores_deleted_file(self):
        diff = """\
diff --git a/a.java b/a.java
--- a/a.java
+++ b/a.java
@@ -2 +2,2 @@
@@ -5,0 +7,3 @@
diff --git a/deleted.java b/deleted.java
--- a/deleted.java
+++ /dev/null
@@ -1 +0,0 @@
"""

        self.assertEqual({"a.java": [(2, 3), (7, 9)]}, MODULE.parse_changed_lines(diff))

    def test_filter_issue_rows_requires_open_issue_on_changed_line(self):
        rows = [
            self.row("a.java", "2", "OPEN"),
            self.row("a.java", "4", "OPEN"),
            self.row("a.java", "", "OPEN"),
            self.row("a.java", "2", "RESOLVED"),
            self.row("other.java", "2", "OPEN"),
        ]

        filtered = MODULE.filter_issue_rows(rows, {"a.java": [(2, 3)]})

        self.assertEqual([rows[0], rows[2]], filtered)

    def test_write_report_creates_machine_readable_artifacts(self):
        issue = self.row("a.java", "2", "OPEN")
        with tempfile.TemporaryDirectory() as directory:
            output_dir = Path(directory)
            MODULE.write_report(
                output_dir,
                "origin/main",
                "abc123",
                {"a.java": [(2, 3)]},
                [issue],
                [],
            )

            with (output_dir / "sonar-incremental-report.csv").open(encoding="utf-8") as file:
                rows = list(csv.DictReader(file))
            self.assertEqual("a.java", rows[0]["component"])
            self.assertIn("变更行 OPEN Issue：1", (output_dir / "sonar-incremental-report.md").read_text())
            self.assertTrue((output_dir / "sonar-changed-lines.json").exists())

    @staticmethod
    def row(component, line, status):
        return {
            "severity": "MAJOR",
            "type": "CODE_SMELL",
            "component": component,
            "line": line,
            "rule": "java:S1",
            "message": "message",
            "status": status,
            "effort": "5min",
        }


if __name__ == "__main__":
    unittest.main()
