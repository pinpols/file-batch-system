from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


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

    def test_allows_chinese_explanation_with_technical_terms(self) -> None:
        self.assertIsNone(MODULE.prose_comment("-- 使用 ON CONFLICT 保证幂等写入。", ".sql"))

    def test_allows_machine_directive(self) -> None:
        self.assertIsNone(MODULE.prose_comment("# shellcheck disable=SC1090", ".sh"))

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


if __name__ == "__main__":
    unittest.main()
