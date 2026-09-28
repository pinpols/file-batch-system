import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
CHECKER = ROOT / "scripts/ci/check-db-comment-coverage.sh"
GATE_RESULT = ROOT / "scripts/lib/gate-result.sh"


class DatabaseCommentCoverageTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        (self.root / "scripts/ci").mkdir(parents=True)
        (self.root / "scripts/lib").mkdir(parents=True)
        (self.root / "db/migration").mkdir(parents=True)
        shutil.copy2(CHECKER, self.root / "scripts/ci/check-db-comment-coverage.sh")
        shutil.copy2(GATE_RESULT, self.root / "scripts/lib/gate-result.sh")
        self.git("init", "-b", "main")
        self.git("config", "user.name", "Gate Test")
        self.git("config", "user.email", "gate-test@example.invalid")
        (self.root / "db/migration/V1__baseline.sql").write_text(
            "CREATE TABLE batch.baseline (id bigint);\n",
            encoding="utf-8",
        )
        self.commit("baseline")
        self.git("switch", "-c", "test")

    def tearDown(self):
        self.temp_dir.cleanup()

    def git(self, *args):
        return subprocess.run(
            ["git", *args],
            cwd=self.root,
            check=True,
            capture_output=True,
            text=True,
        )

    def commit(self, message):
        self.git("add", "db/migration")
        self.git("commit", "-m", message)

    def run_gate(self):
        return subprocess.run(
            ["bash", "scripts/ci/check-db-comment-coverage.sh", "main"],
            cwd=self.root,
            capture_output=True,
            text=True,
            check=False,
        )

    def test_comment_only_change_is_skipped(self):
        migration = self.root / "db/migration/V1__baseline.sql"
        migration.write_text(
            migration.read_text(encoding="utf-8") + "-- 说明：仅补充说明文字。\n",
            encoding="utf-8",
        )
        self.commit("comment only")

        result = self.run_gate()

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("⏭️ 跳过 | code=DB_COMMENT_COVERAGE", result.stdout)

    def test_new_table_missing_comments_fails(self):
        (self.root / "db/migration/V2__new_table.sql").write_text(
            "CREATE TABLE batch.new_table (\n    job_status text\n);\n",
            encoding="utf-8",
        )
        self.commit("new table without comments")

        result = self.run_gate()

        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("❌ 不通过 | code=DB_COMMENT_COVERAGE", result.stderr)
        self.assertIn("batch.new_table 缺少 COMMENT ON TABLE", result.stdout)

    def test_new_table_with_comments_passes(self):
        (self.root / "db/migration/V2__new_table.sql").write_text(
            """CREATE TABLE batch.new_table (
    job_status text
);
COMMENT ON TABLE batch.new_table IS '状态表';
COMMENT ON COLUMN batch.new_table.job_status IS '当前状态';
""",
            encoding="utf-8",
        )
        self.commit("new table with comments")

        result = self.run_gate()

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("✅ 通过 | code=DB_COMMENT_COVERAGE", result.stdout)


if __name__ == "__main__":
    unittest.main()
