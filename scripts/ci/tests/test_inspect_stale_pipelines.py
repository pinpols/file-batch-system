# 用途：验证停滞 Pipeline 只读诊断与状态所有权。
# 用法：在仓库根目录执行 python3 -m unittest scripts/ci/tests/test_inspect_stale_pipelines.py。
"""验证停滞 Pipeline 诊断不绕过 Orchestrator 的状态所有权。"""

import os
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / "scripts/ops/inspect-stale-pipelines.sh"


class InspectStalePipelinesTest(unittest.TestCase):
    def run_script(self, *, dry_run="true", arguments=()):
        with tempfile.TemporaryDirectory(prefix="inspect-stale-pipelines-") as temporary:
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
  inspect-stale-pipelines-list.sql)
    printf '41|tenant-a|JOB_A|IMPORT|2026-10-22 00:00:00+00|90000\\n'
    ;;
  inspect-stale-pipelines-count.sql) printf '1\\n' ;;
  heal-zombie-pipelines-update.sql)
    printf 'write-path-called\\n' >> "$FAKE_PSQL_INVOCATIONS"
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

    def test_dry_run_reports_candidates_without_database_writes(self):
        result, invocations = self.run_script(arguments=("--dry-run",))

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("found 1 stale RUNNING pipeline(s)", result.stdout)
        self.assertIn("no database writes were made", result.stdout)
        self.assertEqual(
            ["inspect-stale-pipelines-list.sql", "inspect-stale-pipelines-count.sql"],
            invocations,
        )
        self.assertNotIn("heal-zombie-pipelines-update.sql", SCRIPT.read_text())

    def test_write_mode_and_invalid_modes_fail_before_database_access(self):
        for dry_run in ("false", "flase"):
            with self.subTest(dry_run=dry_run):
                result, invocations = self.run_script(dry_run=dry_run)

                self.assertNotEqual(0, result.returncode)
                self.assertIn("BATCH_HEAL_DRY_RUN", result.stderr + result.stdout)
                self.assertEqual([], invocations)

    def test_unsupported_apply_option_fails_before_database_access(self):
        result, invocations = self.run_script(arguments=("--apply",))

        self.assertNotEqual(0, result.returncode)
        self.assertIn("unsupported option", result.stderr)
        self.assertEqual([], invocations)


if __name__ == "__main__":
    unittest.main()
