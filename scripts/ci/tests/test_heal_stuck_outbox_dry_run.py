# 用途：验证Outbox 诊断默认只读与非法模式拒绝。
# 用法：在仓库根目录执行 python3 -m unittest scripts/ci/tests/test_heal_stuck_outbox_dry_run.py。
"""验证 Outbox 诊断默认只读，非法模式拒绝执行且不写数据库。"""

import os
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / "scripts/ops/heal-stuck-outbox.sh"


class HealStuckOutboxDryRunTest(unittest.TestCase):
    def run_script(self, *, stuck_count="2", dry_run="true", arguments=()):
        with tempfile.TemporaryDirectory(prefix="heal-outbox-dry-run-") as temporary:
            temporary_path = Path(temporary)
            fake_bin = temporary_path / "bin"
            fake_bin.mkdir()
            env_root = temporary_path / "env"
            env_lib = env_root / "scripts/lib"
            env_lib.mkdir(parents=True)
            for name in ("runtime-defaults.sh", "python-runtime.sh"):
                source = ROOT / "scripts/lib" / name
                (env_lib / name).write_text(source.read_text())

            invocation_log = temporary_path / "psql-invocations.log"
            fake_psql = fake_bin / "psql"
            fake_psql.write_text(
                """#!/usr/bin/env bash
set -euo pipefail
sql_file=""
while (($#)); do
  if [[ "$1" == "-f" ]]; then
    shift
    sql_file="${1##*/}"
  fi
  shift
done
printf '%s\\n' "$sql_file" >> "$FAKE_PSQL_INVOCATIONS"
case "$sql_file" in
  common-connectivity.sql) ;;
  heal-stuck-outbox-count.sql) printf '%s\\n' "$FAKE_STUCK_COUNT" ;;
  heal-stuck-outbox-breakdown.sql) printf 'JOB|%s|2026-10-22 00:00:00+00|3\\n' "$FAKE_STUCK_COUNT" ;;
  heal-stuck-outbox-reset.sql|heal-stuck-outbox-notify.sql)
    printf 'write-path-called\\n' >> "$FAKE_PSQL_INVOCATIONS"
    printf '1\\n'
    ;;
  *) exit 99 ;;
esac
"""
            )
            fake_psql.chmod(0o755)
            env = os.environ.copy()
            env.update(
                {
                    "PATH": f"{fake_bin}{os.pathsep}{env['PATH']}",
                    "BATCH_ENV_COMMON_ROOT": str(env_root),
                    "COMPOSE_ENV_FILE": str(temporary_path / "no-env-file"),
                    "BATCH_HEAL_DRY_RUN": dry_run,
                    "FAKE_STUCK_COUNT": stuck_count,
                    "FAKE_PSQL_INVOCATIONS": str(invocation_log),
                }
            )
            result = subprocess.run(
                ["bash", str(SCRIPT), *arguments],
                capture_output=True,
                text=True,
                env=env,
                timeout=10,
            )
            invocations = invocation_log.read_text().splitlines() if invocation_log.exists() else []
            return result, invocations

    def test_dry_run_reports_candidates_without_reset_or_notify(self):
        result, invocations = self.run_script(arguments=("--dry-run",))

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("DRY-RUN: found 2 stale PUBLISHING event(s)", result.stdout)
        self.assertIn("no database writes were made", result.stdout)
        self.assertEqual(
            [
                "common-connectivity.sql",
                "heal-stuck-outbox-count.sql",
                "heal-stuck-outbox-breakdown.sql",
            ],
            invocations,
        )
        script_source = SCRIPT.read_text()
        self.assertNotIn("heal-stuck-outbox-reset.sql", script_source)
        self.assertNotIn("heal-stuck-outbox-notify.sql", script_source)

    def test_write_mode_and_invalid_modes_fail_closed_before_database_access(self):
        for dry_run in ("false", "flase"):
            with self.subTest(dry_run=dry_run):
                result, invocations = self.run_script(dry_run=dry_run)

                self.assertNotEqual(0, result.returncode)
                self.assertIn("BATCH_HEAL_DRY_RUN", result.stderr + result.stdout)
                self.assertEqual([], invocations)

    def test_unsupported_apply_option_fails_before_database_access(self):
        result, invocations = self.run_script(arguments=("--apply",))

        self.assertNotEqual(0, result.returncode)
        self.assertIn("unsupported option", result.stdout)
        self.assertEqual([], invocations)


if __name__ == "__main__":
    unittest.main()
