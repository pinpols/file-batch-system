# 用途：验证 Flyway 迁移有效 SQL 分类及注释识别边界。
# 用法：在仓库根目录执行 python3 -m unittest scripts/ci/tests/test_flyway_sql_diff.py。
import importlib.util
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "flyway_sql_diff.py"
SPEC = importlib.util.spec_from_file_location("flyway_sql_diff", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
FLYWAY_SQL_DIFF = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FLYWAY_SQL_DIFF)


class FlywaySqlDiffTest(unittest.TestCase):
    def test_header_and_inline_comments_do_not_change_executable_sql(self) -> None:
        base_sql = """-- old purpose
CREATE TABLE batch.example (
    value TEXT -- old explanation
);
"""
        changed_sql = """-- updated purpose
CREATE TABLE batch.example (
    -- updated explanation
    value TEXT
);
"""

        self.assertTrue(FLYWAY_SQL_DIFF.is_comment_only_change(base_sql, changed_sql))

    def test_comment_on_metadata_is_excluded_with_quoted_semicolon(self) -> None:
        base_sql = "COMMENT ON TABLE batch.example IS 'old; description';"
        changed_sql = "COMMENT ON TABLE batch.example IS 'new; description';"

        self.assertTrue(FLYWAY_SQL_DIFF.is_comment_only_change(base_sql, changed_sql))

    def test_sql_literal_containing_comment_marker_is_preserved(self) -> None:
        base_sql = "INSERT INTO batch.example(value) VALUES ('keep -- this');"
        changed_sql = (
            "INSERT INTO batch.example(value) VALUES ('keep -- this'); -- note\n"
        )

        self.assertTrue(FLYWAY_SQL_DIFF.is_comment_only_change(base_sql, changed_sql))

    def test_literal_whitespace_change_is_not_comment_only(self) -> None:
        base_sql = "INSERT INTO batch.example(value) VALUES ('first  second');"
        changed_sql = "INSERT INTO batch.example(value) VALUES ('first second');"

        self.assertFalse(FLYWAY_SQL_DIFF.is_comment_only_change(base_sql, changed_sql))

    def test_dollar_quoted_whitespace_change_is_not_comment_only(self) -> None:
        base_sql = "DO $$ BEGIN RAISE NOTICE 'first  second'; END $$;"
        changed_sql = "DO $$ BEGIN RAISE NOTICE 'first second'; END $$;"

        self.assertFalse(FLYWAY_SQL_DIFF.is_comment_only_change(base_sql, changed_sql))

    def test_ddl_change_is_not_comment_only(self) -> None:
        base_sql = "ALTER TABLE batch.example ADD COLUMN first_value TEXT;"
        changed_sql = "ALTER TABLE batch.example ADD COLUMN second_value TEXT;"

        self.assertFalse(FLYWAY_SQL_DIFF.is_comment_only_change(base_sql, changed_sql))

    def test_insert_value_change_is_not_comment_only(self) -> None:
        base_sql = "INSERT INTO batch.example(value) VALUES ('first');"
        changed_sql = "INSERT INTO batch.example(value) VALUES ('second');"

        self.assertFalse(FLYWAY_SQL_DIFF.is_comment_only_change(base_sql, changed_sql))

    def test_dollar_quoted_block_statements_are_compared(self) -> None:
        base_sql = "DO $$ BEGIN PERFORM 1; END $$;"
        changed_sql = "DO $$ BEGIN PERFORM 2; END $$;"

        self.assertFalse(FLYWAY_SQL_DIFF.is_comment_only_change(base_sql, changed_sql))

    def test_dollar_quoted_body_change_is_not_hidden(self) -> None:
        base_sql = "DO $$ BEGIN RAISE NOTICE 'before'; END $$;"
        changed_sql = "DO $$ BEGIN RAISE NOTICE 'after'; END $$;"

        self.assertFalse(FLYWAY_SQL_DIFF.is_comment_only_change(base_sql, changed_sql))

    def test_comment_metadata_change_does_not_hide_ddl_change(self) -> None:
        base_sql = """COMMENT ON TABLE batch.example IS 'old';
ALTER TABLE batch.example ADD COLUMN first_value TEXT;
"""
        changed_sql = """COMMENT ON TABLE batch.example IS 'new';
ALTER TABLE batch.example ADD COLUMN second_value TEXT;
"""

        self.assertFalse(FLYWAY_SQL_DIFF.is_comment_only_change(base_sql, changed_sql))


if __name__ == "__main__":
    unittest.main()
