# 用途：验证 Flyway 迁移版本连续性和重复版本的门禁结果。
# 用法：在仓库根目录执行 python3 -m unittest scripts.ci.tests.test_validate_flyway_schema。
import os
import subprocess
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "validate-flyway-schema.sh"


class ValidateFlywaySchemaTest(unittest.TestCase):
    def run_guard(self, filenames: list[str]) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory(prefix="flyway-schema-test-") as workdir:
            migrations = Path(workdir) / "db" / "migration"
            migrations.mkdir(parents=True)
            for name in filenames:
                (migrations / name).write_text("SELECT 1;\n", encoding="utf-8")
            return subprocess.run(
                ["bash", str(SCRIPT), "db/migration"],
                cwd=workdir,
                text=True,
                capture_output=True,
                check=False,
            )

    def test_consecutive_versions_pass(self) -> None:
        result = self.run_guard(["V1__first.sql", "V2__second.sql", "V3__third.sql"])
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_gap_is_rejected(self) -> None:
        result = self.run_guard(["V1__first.sql", "V3__third.sql"])
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("expected V2", result.stdout)

    def test_missing_first_version_is_rejected(self) -> None:
        result = self.run_guard(["V2__second.sql"])
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("expected V1", result.stdout)

    def test_duplicate_version_is_rejected(self) -> None:
        result = self.run_guard(["V1__first.sql", "V1__another.sql"])
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("duplicate migration version V1", result.stdout)

    def run_existing_ddl_guard(
        self, allow_existing: bool, add_gap: bool = False
    ) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory(prefix="flyway-ddl-test-") as workdir:
            root = Path(workdir)
            migrations = root / "db" / "migration"
            migrations.mkdir(parents=True)
            original = migrations / "V1__first.sql"
            original.write_text("CREATE TABLE sample (id int);\n", encoding="utf-8")
            subprocess.run(["git", "init", "-q"], cwd=root, check=True)
            subprocess.run(["git", "add", "db/migration"], cwd=root, check=True)
            subprocess.run(
                ["git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                 "commit", "-q", "-m", "fixture"],
                cwd=root, check=True,
            )
            original.write_text("CREATE TABLE sample (id bigint);\n", encoding="utf-8")
            if add_gap:
                (migrations / "V3__third.sql").write_text("SELECT 1;\n", encoding="utf-8")
            env = os.environ.copy()
            env["BASE_REF"] = "HEAD"
            env["FLYWAY_ALLOW_EXISTING_DDL_CHANGES"] = str(allow_existing).lower()
            return subprocess.run(
                ["bash", str(SCRIPT), "db/migration"], cwd=root, env=env,
                text=True, capture_output=True, check=False,
            )

    def test_existing_ddl_changes_are_blocked_by_default(self) -> None:
        result = self.run_existing_ddl_guard(False)
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("existing migration file(s) modified", result.stdout)

    def test_removed_exception_cannot_allow_existing_ddl_changes(self) -> None:
        result = self.run_existing_ddl_guard(True)
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("existing migration file(s) modified", result.stdout)

    def test_old_exception_setting_does_not_allow_version_gap(self) -> None:
        result = self.run_existing_ddl_guard(True, add_gap=True)
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("expected V2", result.stdout)


if __name__ == "__main__":
    unittest.main()
