# 用途：验证迁移安全门禁对注释、已有 SQL 和新增 SQL 的扫描范围。
# 用法：在仓库根目录执行 python3 -m unittest scripts.ci.tests.test_check_migration_safety。
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


CI_DIR = Path(__file__).resolve().parents[1]


class MigrationSafetyScopeTest(unittest.TestCase):
    def run_guard(self, old_sql: str, new_sql: str | None) -> tuple[subprocess.CompletedProcess[str], list[str]]:
        with tempfile.TemporaryDirectory(prefix="migration-scope-test-") as workdir:
            root = Path(workdir)
            ci = root / "scripts" / "ci"
            migrations = root / "db" / "migration"
            tool_dir = root / ".github" / "ci-tools" / "node_modules" / ".bin"
            ci.mkdir(parents=True)
            migrations.mkdir(parents=True)
            tool_dir.mkdir(parents=True)
            for name in ("check-migration-safety.sh", "flyway_sql_diff.py"):
                shutil.copy2(CI_DIR / name, ci / name)
            tool = tool_dir / "squawk"
            tool.write_text(
                '#!/bin/sh\nprintf "%s\\n" "$@" > "$SQUAWK_TEST_ARGS"\n'
                'for file in "$@"; do grep -q "DROP COLUMN" "$file" && exit 1; done\n'
                'exit 0\n',
                encoding="utf-8",
            )
            tool.chmod(0o700)
            old = migrations / "V1__old.sql"
            old.write_text("ALTER TABLE sample DROP COLUMN retired;\n", encoding="utf-8")
            subprocess.run(["git", "init", "-q"], cwd=root, check=True)
            subprocess.run(["git", "add", "db/migration"], cwd=root, check=True)
            subprocess.run(
                ["git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                 "commit", "-q", "-m", "fixture"],
                cwd=root, check=True,
            )
            old.write_text(old_sql, encoding="utf-8")
            if new_sql is not None:
                (migrations / "V2__new.sql").write_text(new_sql, encoding="utf-8")
            args_file = root / "squawk-args.txt"
            env = os.environ.copy()
            env["SQUAWK_TEST_ARGS"] = str(args_file)
            result = subprocess.run(
                ["bash", str(ci / "check-migration-safety.sh"), "HEAD"],
                cwd=root, env=env, text=True, capture_output=True, check=False,
            )
            args = args_file.read_text(encoding="utf-8").splitlines() if args_file.exists() else []
            return result, args

    def test_comment_only_old_migration_is_not_sent_alongside_new_sql(self) -> None:
        result, args = self.run_guard(
            "-- clarification\nALTER TABLE sample DROP COLUMN retired;\n", "SELECT 1;\n"
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(args, ["db/migration/V2__new.sql"])

    def test_new_unsafe_migration_is_blocked(self) -> None:
        result, args = self.run_guard(
            "-- clarification\nALTER TABLE sample DROP COLUMN retired;\n",
            "ALTER TABLE sample DROP COLUMN current_value;\n",
        )
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(args, ["db/migration/V2__new.sql"])

    def test_existing_effective_sql_change_is_still_scanned(self) -> None:
        result, args = self.run_guard("ALTER TABLE sample DROP COLUMN other_value;\n", None)
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(args, ["db/migration/V1__old.sql"])


if __name__ == "__main__":
    unittest.main()
