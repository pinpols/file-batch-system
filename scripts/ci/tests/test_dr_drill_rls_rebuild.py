"""验证 DR 脚本的 RLS 重建结果传播；Docker 全部替身，不执行任何备份/恢复。"""

import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / "scripts/db/backup/dr-drill.sh"


class DrDrillRlsRebuildTest(unittest.TestCase):
    def run_rebuild(self, docker_status=0, script_exists=True):
        source = SCRIPT.read_text()
        start = source.index("  # §2.1 step4:")
        end = source.index("\nfi\nRTO=", start)
        section = source[start:end]
        function = re.search(r"(?ms)^restore_business_rls\(\) \{.*?^\}", source)
        with tempfile.TemporaryDirectory(prefix="dr-rls-unit-") as directory:
            sql = Path(directory) / "rls.sql"
            if script_exists:
                sql.write_text("SELECT 1;\n")
            env = os.environ.copy()
            env.update({"RLS_SQL": str(sql), "DOCKER_STATUS": str(docker_status)})
            code = """
set -uo pipefail
PG_PASSWORD=fixture-password
BIZ_WRITER_PASSWORD=fixture-password
BIZ_ADMIN_PASSWORD=fixture-password
PG_CONTAINER=fixture-postgres
PG_USER=fixture-user
TGT_BUSINESS=fixture_business_dr
YELLOW= RED= GREEN= RESET=
fails=0 KEEP=0
checks=()
docker() {
  if [[ "$DOCKER_STATUS" != "0" ]]; then
    echo 'fixture RLS rebuild failure' >&2
  fi
  return "$DOCKER_STATUS"
}
""" + (function.group() if function else "") + "\n" + section + """
printf 'FAILS=%s KEEP=%s\n' "$fails" "$KEEP"
exit "$((fails > 0))"
"""
            return subprocess.run(["bash", "-c", code], env=env, capture_output=True, text=True, timeout=10)

    def test_failed_rebuild_fails_drill_and_preserves_restored_database(self):
        result = self.run_rebuild(docker_status=3)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("FAILS=1 KEEP=1", result.stdout)
        self.assertNotIn("✓ 已重跑 rls-phase-a.sql", result.stdout)
        self.assertIn("fixture RLS rebuild failure", result.stderr)

    def test_missing_rls_script_is_failure_instead_of_skip(self):
        result = self.run_rebuild(script_exists=False)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("FAILS=1 KEEP=1", result.stdout)
        self.assertNotIn("✓ 已重跑 rls-phase-a.sql", result.stdout)

    def test_successful_rebuild_does_not_force_keep_or_fail(self):
        result = self.run_rebuild()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("FAILS=0 KEEP=0", result.stdout)
        self.assertIn("✓ 已重跑 rls-phase-a.sql", result.stdout)
        self.assertNotIn("fixture-password", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
