"""容量验收的退出码必须包含环境清理失败，并保留主流程的首个失败。"""

import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[3]


class ControlPlaneBenchmarkCleanupTest(unittest.TestCase):
    def run_cleanup(self, *, primary=0, atomic=0, worker=0, skip=False):
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
'''
            environment = {
                'PATH': os.environ['PATH'], 'LOAD_DIR': temporary, 'RUN_ID': 'owned-run',
                'SKIP_AUTO_CLEANUP': str(int(skip)), 'CALLS': str(calls),
                'TEST_PRIMARY_EXIT': str(primary), 'TEST_ATOMIC_EXIT': str(atomic),
                'TEST_WORKER_EXIT': str(worker),
            }
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


if __name__ == '__main__':
    unittest.main()
