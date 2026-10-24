# 用途：回归验证检查新增的纯英文说明性注释。
# 用法：在仓库根目录执行 python3 -m unittest scripts/ci/tests/test_check_comment_language.py。
from __future__ import annotations

import importlib.util
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "check-comment-language.py"
SPEC = importlib.util.spec_from_file_location("check_comment_language", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class CommentLanguageTest(unittest.TestCase):
    def test_detects_english_explanation(self) -> None:
        self.assertEqual(
            MODULE.prose_comment("// Keep this guard because stale leaders can replay tasks.", ".java"),
            "Keep this guard because stale leaders can replay tasks.",
        )

    def test_detects_english_javadoc_with_utf8_term(self) -> None:
        self.assertEqual(
            MODULE.prose_comment(
                "/** UTF-8 locale for test-service processes; database collation remains image-default. */",
                ".java",
            ),
            "UTF-8 locale for test-service processes; database collation remains image-default.",
        )

    def test_allows_chinese_explanation_with_technical_terms(self) -> None:
        self.assertIsNone(MODULE.prose_comment("-- 使用 ON CONFLICT 保证幂等写入。", ".sql"))

    def test_allows_machine_directive(self) -> None:
        self.assertIsNone(MODULE.prose_comment("# shellcheck disable=SC1090", ".sh"))
        self.assertIsNone(MODULE.prose_comment("-- squawk-ignore ban-drop-not-null", ".sql"))

    def test_detects_english_postgres_object_comment(self) -> None:
        self.assertEqual(
            MODULE.sql_object_comment_prose(
                "COMMENT ON COLUMN batch.job_task.task_status IS "
                "'The task state is used to track each execution attempt.';"
            ),
            [(1, "The task state is used to track each execution attempt.")],
        )

    def test_detects_english_function_and_trigger_comments(self) -> None:
        source = """COMMENT ON FUNCTION batch.sample_guard(timestamp with time zone)
            IS 'The guard serializes each writer.';
            COMMENT ON TRIGGER sample_guard_trigger ON batch.sample
            IS 'The trigger protects each update.';"""
        self.assertEqual(
            MODULE.sql_object_comment_prose(source),
            [
                (1, "The guard serializes each writer."),
                (3, "The trigger protects each update."),
            ],
        )

    def test_allows_chinese_postgres_object_comment(self) -> None:
        self.assertEqual(
            MODULE.sql_object_comment_prose(
                "COMMENT ON COLUMN batch.job_task.task_status IS "
                "'任务状态用于跟踪每次执行尝试。';"
            ),
            [],
        )

    def test_allows_sql_example(self) -> None:
        self.assertIsNone(MODULE.prose_comment("-- SELECT id FROM batch.job_instance;", ".sql"))

    def test_allows_sql_schema_notes_and_expressions(self) -> None:
        self.assertIsNone(MODULE.prose_comment("-- job_instance.dry_run BOOLEAN NOT NULL DEFAULT false", ".sql"))
        self.assertIsNone(MODULE.prose_comment("-- ON CONFLICT (...) DO UPDATE SET is_deleted = false", ".sql"))
        self.assertIsNone(MODULE.prose_comment("-- descriptor.defaults < job_definition.default_params", ".sql"))

    def test_allows_path_references(self) -> None:
        self.assertIsNone(MODULE.prose_comment("# docs/runbook/feature-switch-registry.yml", ".sh"))

    def test_allows_command_examples(self) -> None:
        self.assertIsNone(MODULE.prose_comment("-- psql -d batch_business -f scripts/db/setup.sql", ".sql"))

    def test_does_not_treat_shell_options_as_sql_comments(self) -> None:
        self.assertIsNone(MODULE.prose_comment("--if-not-exists \\", ".sh"))

    def test_does_not_treat_shebang_as_prose(self) -> None:
        self.assertIsNone(MODULE.prose_comment("#!/usr/bin/env bash", ".sh"))

    def test_detects_added_comment_only(self) -> None:
        diff = """diff --git a/scripts/example.sh b/scripts/example.sh
--- a/scripts/example.sh
+++ b/scripts/example.sh
@@ -1 +1,2 @@
 # 中文说明
+# Retry the request after a transient failure.
"""
        self.assertEqual(
            MODULE.parse_diff(diff),
            [("scripts/example.sh", 2, "Retry the request after a transient failure.")],
        )

    def test_path_scan_includes_untracked_files(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            migration = root / "db/migration/V999__untracked.sql"
            migration.parent.mkdir(parents=True)
            migration.write_text(
                "COMMENT ON TABLE batch.sample IS 'The sample table stores task state.';\n",
                encoding="utf-8",
            )
            git_result = subprocess.CompletedProcess(
                args=[], returncode=0, stdout=b"db/migration/V999__untracked.sql\0"
            )
            with patch.object(MODULE, "ROOT", root), patch.object(
                MODULE.subprocess, "run", return_value=git_result
            ) as run:
                findings = MODULE.scan_paths(["db/migration"])

            self.assertEqual(
                findings,
                [("db/migration/V999__untracked.sql", 1, "The sample table stores task state.")],
            )
            self.assertIn("--others", run.call_args.args[0])


if __name__ == "__main__":
    unittest.main()
