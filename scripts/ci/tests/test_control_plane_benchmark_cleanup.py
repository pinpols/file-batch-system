# 用途：Sim/容量/恢复脚本契约。
# 用法：在仓库根目录执行 python3 -m unittest scripts/ci/tests/test_control_plane_benchmark_cleanup.py。
"""容量验收的退出码必须包含环境清理失败，并保留主流程的首个失败。"""

import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[3]


class LoadTestRunIdentityTest(unittest.TestCase):
    def validate(self, run_id):
        source = (ROOT / 'load-tests/scripts/env.sh').read_text()
        function = re.search(r'(?ms)^validate_load_test_run_id\(\) \{.*?^\}', source).group()
        return subprocess.run(['bash', '-c', 'set -eu\n' + function +
                               '\nvalidate_load_test_run_id "$TEST_RUN_ID"\n'],
                              env={'PATH': os.environ['PATH'], 'TEST_RUN_ID': run_id,
                                   'LOAD_TEST_RUN_ID_MAX_LENGTH': '53'},
                              text=True, capture_output=True)

    def test_documented_run_names_are_accepted(self):
        for run_id in ('ctlw-20261018', 'run.180', 'A-1'):
            with self.subTest(run_id=run_id):
                result = self.validate(run_id)
                self.assertEqual(result.returncode, 0, result.stderr)

    def test_sql_wildcards_and_file_path_characters_are_rejected(self):
        for run_id in ('run_id', 'run%id', 'run/id', 'run\\id', '.hidden', 'run\nid', "run'id"):
            with self.subTest(run_id=run_id):
                self.assertNotEqual(self.validate(run_id).returncode, 0)

    def test_length_limit_is_still_enforced(self):
        self.assertEqual(self.validate('a' * 53).returncode, 0)
        self.assertNotEqual(self.validate('a' * 54).returncode, 0)

    def test_benchmark_validates_identity_before_installing_cleanup(self):
        source = (ROOT / 'load-tests/scripts/run-control-plane-worker-benchmark.sh').read_text()
        self.assertLess(source.index('validate_load_test_run_id "$RUN_ID"'),
                        source.index('trap on_exit_cleanup EXIT'))

    def test_direct_cleanup_validates_identity_before_database_access(self):
        source = (ROOT / 'load-tests/scripts/cleanup-worker-load-data.sh').read_text()
        self.assertLess(source.index('validate_load_test_run_id "$RUN_ID"'),
                        source.index('\nwait_for_stable_platform_rows\n'))


class ControlPlaneBenchmarkCleanupTest(unittest.TestCase):
    def run_cleanup(self, *, primary=0, atomic=0, worker=0, skip=False, inventory=None):
        source = (ROOT / 'load-tests/scripts/run-control-plane-worker-benchmark.sh').read_text()
        function = re.search(r'(?ms)^on_exit_cleanup\(\) \{.*?^\}', source).group()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'scripts').mkdir()
            cleanup = root / 'scripts/cleanup-worker-load-data.sh'
            cleanup.write_text('#!/usr/bin/env bash\nprintf "worker:%s\\n" "$RUN_ID" >> "$CALLS"\nexit "$TEST_WORKER_EXIT"\n')
            cleanup.chmod(0o700)
            calls = root / 'calls.txt'
            shell = r'''
set -euo pipefail
stop_pg_pressure_sampler() { printf 'sampler\n' >> "$CALLS"; }
cleanup_atomic_trigger() {
  printf 'atomic:%s\n' "$RUN_ID" >> "$CALLS"
  return "$TEST_ATOMIC_EXIT"
}
psql_platform() {
  printf 'inventory\n' >> "$CALLS"
  printf '100\n'
  return "$TEST_INVENTORY_EXIT"
}
'''
            environment = {
                'PATH': os.environ['PATH'], 'LOAD_DIR': temporary, 'RUN_ID': 'owned-run',
                'SKIP_AUTO_CLEANUP': str(int(skip)), 'CALLS': str(calls),
                'TEST_PRIMARY_EXIT': str(primary), 'TEST_ATOMIC_EXIT': str(atomic),
                'TEST_WORKER_EXIT': str(worker),
                'TEST_INVENTORY_EXIT': str(inventory or 0),
            }
            if inventory is not None:
                environment['LOG_DIR'] = temporary
            result = subprocess.run(['bash', '-c', shell + function +
                                     '\ntrap on_exit_cleanup EXIT\nexit "$TEST_PRIMARY_EXIT"\n'],
                                    text=True, capture_output=True, env=environment)
            return result, calls.read_text().splitlines() if calls.exists() else []

    def test_success_requires_both_cleanup_steps(self):
        result, calls = self.run_cleanup()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls, ['sampler', 'atomic:owned-run', 'worker:owned-run'])

    def test_atomic_cleanup_failure_fails_successful_benchmark_and_continues_cleanup(self):
        result, calls = self.run_cleanup(atomic=2)
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertEqual(calls, ['sampler', 'atomic:owned-run', 'worker:owned-run'])
        self.assertIn('atomic/trigger cleanup failed', result.stderr)

    def test_worker_cleanup_failure_fails_successful_benchmark(self):
        result, calls = self.run_cleanup(worker=3)
        self.assertEqual(result.returncode, 3, result.stderr)
        self.assertIn('worker:owned-run', calls)
        self.assertIn('cleanup-worker-load-data failed', result.stderr)

    def test_primary_failure_keeps_its_exit_code_and_reports_both_cleanup_failures(self):
        result, calls = self.run_cleanup(primary=7, atomic=2, worker=3)
        self.assertEqual(result.returncode, 7, result.stderr)
        self.assertIn('worker:owned-run', calls)
        self.assertIn('atomic/trigger cleanup failed', result.stderr)
        self.assertIn('cleanup-worker-load-data failed', result.stderr)

    def test_first_cleanup_failure_is_preserved_when_both_fail(self):
        result, _ = self.run_cleanup(atomic=2, worker=3)
        self.assertEqual(result.returncode, 2, result.stderr)

    def test_explicit_skip_preserves_primary_result_and_stops_only_sampler(self):
        for primary in (0, 7):
            with self.subTest(primary=primary):
                result, calls = self.run_cleanup(primary=primary, skip=True)
                self.assertEqual(result.returncode, primary, result.stderr)
                self.assertEqual(calls, ['sampler'])
                self.assertIn('leaving RUN_ID=owned-run data in place', result.stdout)

    def test_root_inventory_is_recorded_before_cleanup(self):
        result, calls = self.run_cleanup(inventory=0)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls, ['sampler', 'inventory', 'atomic:owned-run', 'worker:owned-run'])

    def test_inventory_failure_blocks_mutations_and_preserves_primary_failure(self):
        for primary, expected in ((0, 4), (7, 7)):
            with self.subTest(primary=primary):
                result, calls = self.run_cleanup(primary=primary, inventory=4)
                self.assertEqual(result.returncode, expected, result.stderr)
                self.assertEqual(calls, ['sampler', 'inventory'])
                self.assertIn('cleanup identity inventory failed', result.stderr)


if __name__ == '__main__':
    unittest.main()
