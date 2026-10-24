"""Ensure dead-letter operations inspect candidates without bypassing approval."""

import os
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / "scripts/ops/inspect-dead-letters.sh"


class InspectDeadLettersTest(unittest.TestCase):
    def run_script(self, *, dry_run="true", arguments=(), fail_file=""):
        with tempfile.TemporaryDirectory(prefix="inspect-dead-letters-") as temporary:
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
for argument in "$@"; do
  case "$argument" in
    -f) next_is_file=true ;;
    *.sql) sql_file="${argument##*/}" ;;
  esac
done
printf '%s|%s\\n' "$sql_file" "$*" >> "$FAKE_PSQL_INVOCATIONS"
if [[ "$sql_file" == "$FAKE_PSQL_FAIL_FILE" ]]; then
  exit 12
fi
case "$sql_file" in
  inspect-dead-letters-summary.sql) printf 'tenant-a|EXPORT|2\\n' ;;
  inspect-dead-letters-count.sql) printf '2\\n' ;;
  inspect-dead-letters-list.sql) printf '41|tenant-a\\n42|tenant-a\\n' ;;
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
                    "FAKE_PSQL_FAIL_FILE": fail_file,
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

    def test_dry_run_lists_candidates_without_replay_api_call(self):
        result, invocations = self.run_script(
            arguments=("--tenant", "tenant-a", "--source-type", "EXPORT", "--limit", "10")
        )

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("found 2 NEW dead-letter task(s)", result.stdout)
        self.assertIn("41|tenant-a", result.stdout)
        self.assertIn("no replay API was called", result.stdout)
        self.assertEqual(
            [
                "inspect-dead-letters-summary.sql",
                "inspect-dead-letters-count.sql",
                "inspect-dead-letters-list.sql",
            ],
            [invocation.split("|", 1)[0] for invocation in invocations],
        )
        self.assertTrue(all("tenant=tenant-a" in invocation for invocation in invocations))
        script_source = SCRIPT.read_text()
        self.assertNotIn("curl", script_source)
        self.assertNotIn("/internal/dead-letters/", script_source)

    def test_non_read_only_and_unsupported_modes_fail_before_database_access(self):
        for dry_run in ("false", "flase"):
            with self.subTest(dry_run=dry_run):
                result, invocations = self.run_script(dry_run=dry_run)

                self.assertNotEqual(0, result.returncode)
                self.assertIn("BATCH_HEAL_DRY_RUN", result.stderr + result.stdout)
                self.assertEqual([], invocations)

        result, invocations = self.run_script(arguments=("--apply",))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("unsupported option", result.stderr)
        self.assertEqual([], invocations)

        result, invocations = self.run_script(arguments=("--limit", "1001"))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("must not exceed 1000", result.stderr)
        self.assertEqual([], invocations)

    def test_query_failure_does_not_report_an_empty_queue(self):
        result, invocations = self.run_script(fail_file="inspect-dead-letters-count.sql")

        self.assertNotEqual(0, result.returncode)
        self.assertIn("inspect-dead-letters-count.sql", [item.split("|", 1)[0] for item in invocations])
        self.assertNotIn("No dead letters", result.stdout)
        self.assertNotIn("found 0 NEW dead-letter", result.stdout)


if __name__ == "__main__":
    unittest.main()
