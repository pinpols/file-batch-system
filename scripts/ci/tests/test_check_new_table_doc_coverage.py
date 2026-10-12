#!/usr/bin/env python3
"""Tests for incremental database table documentation coverage."""

from __future__ import annotations

import importlib.util
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).parents[1] / "check-new-table-doc-coverage.py"
SPEC = importlib.util.spec_from_file_location("check_new_table_doc_coverage", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class NewTableDocumentationCoverageTest(unittest.TestCase):
    def test_finds_new_business_tables(self) -> None:
        sql = """CREATE TABLE IF NOT EXISTS batch.new_policy (id bigint);
CREATE TABLE archive.new_policy_archive (id bigint);
"""

        self.assertEqual(
            MODULE.created_tables(sql),
            {"batch.new_policy", "archive.new_policy_archive"},
        )

    def test_partition_child_is_not_a_logical_table(self) -> None:
        sql = "CREATE TABLE batch.daily_202610 PARTITION OF batch.daily FOR VALUES FROM (1) TO (2);"

        self.assertEqual(MODULE.created_tables(sql), set())

    def test_detects_multiline_create_table(self) -> None:
        sql = """CREATE TABLE IF NOT EXISTS
    archive.new_archive (
        id bigint
    );
"""

        self.assertEqual(MODULE.created_tables(sql), {"archive.new_archive"})

    def test_ignores_create_table_in_sql_comment(self) -> None:
        sql = "-- CREATE TABLE batch.not_real (id bigint);\n"

        self.assertEqual(MODULE.created_tables(sql), set())

    def test_requires_table_name_in_schema_guide(self) -> None:
        tables = {"batch.documented_table", "batch.missing_table"}
        guide = "### 3.1 Table catalog\n| `documented_table` | example |\n### 3.6 Details\n"

        self.assertEqual(MODULE.find_undocumented(tables, guide), ["batch.missing_table"])

    def test_table_mention_outside_catalog_is_not_registration(self) -> None:
        guide = "### 3.1 Table catalog\n| `documented_table` | example |\n### 3.6 Details\n`new_table` details\n"

        self.assertEqual(
            MODULE.find_undocumented({"batch.new_table"}, guide), ["batch.new_table"]
        )

    def test_detects_removed_catalog_entries(self) -> None:
        previous = "### 3.1 Table catalog\n| `old_table` | old |\n### 3.6 Details\n"
        current = "### 3.1 Table catalog\n| `new_table` | new |\n### 3.6 Details\n"

        self.assertEqual(MODULE.removed_catalog_tables(previous, current), ["old_table"])

    def test_gate_passes_when_new_table_is_documented(self) -> None:
        result = self.run_gate(documented=True)

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("| action=none", result.stdout)
        self.assertIn("已核对新增表登记数量: 1", result.stdout)

    def test_gate_fails_when_new_table_is_not_documented(self) -> None:
        result = self.run_gate(documented=False)

        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("| action=检查失败详情并修复后重试", result.stderr)
        self.assertIn("batch.new_policy", result.stdout)

    def run_gate(self, documented: bool) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            script = root / "scripts/ci/check-new-table-doc-coverage.py"
            script.parent.mkdir(parents=True)
            shutil.copy2(SCRIPT, script)
            guide = root / "docs/design/database-schema-guide.md"
            guide.parent.mkdir(parents=True)
            guide.write_text(
                (
                    "### 3.1 Table catalog\n| `new_policy` | description |\n### 3.6 Details\n"
                    if documented
                    else "### 3.1 Table catalog\n| `other_table` | description |\n### 3.6 Details\n"
                ),
                encoding="utf-8",
            )
            subprocess.run(["git", "init", "-b", "main"], cwd=root, check=True, capture_output=True)
            subprocess.run(
                ["git", "config", "user.name", "Gate Test"], cwd=root, check=True
            )
            subprocess.run(
                ["git", "config", "user.email", "gate-test@example.invalid"],
                cwd=root,
                check=True,
            )
            subprocess.run(["git", "add", "."], cwd=root, check=True)
            subprocess.run(
                ["git", "commit", "-m", "baseline"], cwd=root, check=True, capture_output=True
            )
            subprocess.run(
                ["git", "switch", "-c", "feature"], cwd=root, check=True, capture_output=True
            )
            migration = root / "db/migration/V1__new_policy.sql"
            migration.parent.mkdir(parents=True)
            migration.write_text(
                "CREATE TABLE batch.new_policy (id bigint);\n", encoding="utf-8"
            )
            subprocess.run(["git", "add", "."], cwd=root, check=True)
            subprocess.run(
                ["git", "commit", "-m", "add table"], cwd=root, check=True, capture_output=True
            )
            return subprocess.run(
                [sys.executable, str(script), "--base", "main"],
                cwd=root,
                check=False,
                capture_output=True,
                text=True,
            )


if __name__ == "__main__":
    unittest.main()
