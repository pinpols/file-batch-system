# 用途：验证Shell 内联 SQL 的增量边界。
# 用法：在仓库根目录执行 python3 -m unittest scripts/ci/tests/test_sql_config_boundaries.py。
from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "check-sql-config-boundaries.py"
SPEC = importlib.util.spec_from_file_location("sql_boundary", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
SQL_BOUNDARY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SQL_BOUNDARY)


class SqlConfigBoundaryTest(unittest.TestCase):
    def test_baseline_move_still_reads_target_branch_legacy_budget(self) -> None:
        missing = SimpleNamespace(returncode=128, stdout="")
        legacy = SimpleNamespace(returncode=0, stdout="scripts/owned.sh\t2\n")
        with patch.dict("os.environ", {"GUARD_BASE": "target"}), patch.object(
            SQL_BOUNDARY.subprocess, "run", side_effect=[missing, legacy]
        ) as run:
            self.assertEqual({"scripts/owned.sh": 2}, SQL_BOUNDARY.read_base_baseline())
        self.assertIn("target:scripts/ci/sql-inline-baseline.tsv", run.call_args.args[0])

    def test_unavailable_base_budget_fails_closed(self) -> None:
        missing = SimpleNamespace(returncode=128, stdout="")
        with patch.dict("os.environ", {"GUARD_BASE": "target"}), patch.object(
            SQL_BOUNDARY.subprocess, "run", return_value=missing
        ), self.assertRaises(SystemExit):
            SQL_BOUNDARY.read_base_baseline()

    def test_detects_lowercase_sql_and_combined_psql_options(self) -> None:
        content = """\
psql -tAc "$query"
value=$(query "select count(*) from batch.job_instance")
insert into batch.probe(id) values (1);
select
psql -v ON_ERROR_STOP=1 -f scripts/local/sql/probe.sql
echo "select text shown to an operator"
# select count(*) from ignored_comment
"""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "probe.sh"
            path.write_text(content, encoding="utf-8")
            matches = SQL_BOUNDARY.matched_lines(path)

        self.assertEqual([1, 2, 3, 4], [line_number for line_number, _ in matches])

    def test_detects_wrapped_psql_c_commands_and_eosql_heredocs(self) -> None:
        content = '''\
run_or_preview psql_platform -c "VACUUM (ANALYZE) ${table};"
psql -v ON_ERROR_STOP=1 <<-EOSQL
  DO $$ BEGIN SELECT 1; END $$;
EOSQL
'''
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "probe.sh"
            path.write_text(content, encoding="utf-8")
            matches = SQL_BOUNDARY.matched_lines(path)

        self.assertEqual([1, 2, 3], [line_number for line_number, _ in matches])

    def test_does_not_treat_downstream_grep_count_as_psql_c_option(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "probe.sh"
            path.write_text(
                'updated="$(psql_file -f reset.sql | grep -c "^[0-9]")"\n',
                encoding="utf-8",
            )
            self.assertEqual([], SQL_BOUNDARY.matched_lines(path))

    def test_ignore_marker_is_explicit(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "probe.sh"
            path.write_text(
                'message="select a display option" # sql-boundary: ignore\n',
                encoding="utf-8",
            )
            self.assertEqual([], SQL_BOUNDARY.matched_lines(path))

    def test_detects_docker_exec_psql_without_stdin_transport(self) -> None:
        content = '''\
args = ["docker", "exec", container, "psql", "-f", "/dev/stdin"]
safe = ["docker", "exec", "-i", container, "psql", "-f", "/dev/stdin"]
'''
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "probe.sh"
            path.write_text(content, encoding="utf-8")
            matches = SQL_BOUNDARY.unsafe_psql_transport_lines(path)

        self.assertEqual([1], [line_number for line_number, _ in matches])

    def test_rejects_duplicate_baseline_paths(self) -> None:
        with self.assertRaises(SystemExit):
            SQL_BOUNDARY.parse_baseline("a.sh\t1\na.sh\t1\n", "probe")


if __name__ == "__main__":
    unittest.main()
