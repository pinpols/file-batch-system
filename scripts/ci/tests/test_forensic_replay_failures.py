# 用途：验证取证回放 shell 入口的传输失败传播。
# 用法：在仓库根目录执行 python3 -m unittest scripts/ci/tests/test_forensic_replay_failures.py。
"""用隔离的传输替身执行真实取证 Shell 入口，检查失败传播。"""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[3]


class ForensicReplayFailureTest(unittest.TestCase):
    def run_scenario(self, scenario, *, no_launch=False, terminal_status='SUCCESS'):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            commands = directory / 'bin'
            commands.mkdir()
            row = {'instanceNo': 'original-instance', 'tenantId': 't1', 'jobCode': 'owned_job',
                   'bizDate': '2026-10-23', 'instanceStatus': terminal_status, 'processedCount': 1}
            bundle = directory / 'owned-bundle.zip'
            with zipfile.ZipFile(bundle, 'w') as archive:
                archive.writestr('manifest.json', json.dumps({
                    'exportId': 'owned-export', 'tenantId': 't1',
                    'bizDateFrom': '2026-10-23', 'bizDateTo': '2026-10-23',
                    'jobCodes': ['owned_job']}))
                archive.writestr('job-instances.json', json.dumps([row]))
                archive.writestr('batch-day-operation-audits.json', json.dumps([{'id': 1, 'tenantId': 't1'}]))
            psql = commands / 'psql'
            psql.write_text('''#!/usr/bin/env python3
import json, os, pathlib, sys
args = sys.argv[1:]
name = pathlib.Path(args[args.index('-f') + 1]).name
scenario = os.environ['TEST_SCENARIO']
with open(os.environ['TEST_CALLS'], 'a') as stream:
    stream.write(name + '\\n')
    for arg in args:
        if arg.startswith('replay_schema='): stream.write(arg + '\\n')
if name == 'select-forensic-export-sha.sql':
    if scenario == 'hash_mismatch': print('incorrect-hash')
elif name == 'insert-forensic-day-audit.sql' and scenario == 'audit_failure': sys.exit(9)
elif name == 'select-forensic-trigger-pairs.sql':
    if scenario == 'pair_query_failure': sys.exit(9)
    print('owned_job|2026-10-23')
elif name == 'select-single-job-workflow-id.sql':
    if scenario != 'missing_workflow': print('1')
elif name == 'select-latest-replay-instance-status.sql':
    print('RUNNING' if scenario == 'timeout' else os.environ['TEST_TERMINAL_STATUS'])
elif name == 'select-replay-snapshot.sql':
    if scenario == 'snapshot_failure': sys.exit(9)
    print('[]' if scenario == 'missing_result' else os.environ['TEST_SNAPSHOT'])
''')
            psql.chmod(0o700)
            curl = commands / 'curl'
            curl.write_text('''#!/usr/bin/env python3
import os, sys
if any('/actuator/health' in value for value in sys.argv): print('200', end='')
elif os.environ['TEST_SCENARIO'] == 'trigger_rejected': print('{"code":"UNAVAILABLE"}\\n503', end='')
else: print('{"code":"SUCCESS","data":{"requestId":"accepted"}}\\n200', end='')
''')
            curl.chmod(0o700)
            calls_file = directory / 'calls.txt'
            output = directory / 'evidence'
            environment = dict(os.environ)
            environment.update({
                'PATH': str(commands) + os.pathsep + os.environ['PATH'],
                'BATCH_ENV_LOADED': '1', 'PYTHON_BIN': 'python3',
                'PGHOST': 'unused.invalid', 'PGPORT': '1', 'PGUSER': 'test',
                'PGPASSWORD': 'test', 'PLATFORM_DB': 'test',
                'TRIGGER_BASE_URL': 'http://unused.invalid', 'BATCH_INTERNAL_SECRET': 'test',
                'REPLAY_WORK_DIR': str(output), 'REPLAY_WAIT_TIMEOUT_SEC': '1',
                'REPLAY_WAIT_INTERVAL_SEC': '0', 'TEST_SCENARIO': scenario,
                'TEST_CALLS': str(calls_file), 'TEST_SNAPSHOT': json.dumps([row]),
                'TEST_TERMINAL_STATUS': terminal_status,
            })
            arguments = ['bash', str(ROOT / 'scripts/local/replay-forensic-bundle.sh'), str(bundle)]
            if no_launch:
                arguments.append('--no-launch')
            result = subprocess.run(arguments, env=environment, text=True, capture_output=True, timeout=20)
            calls = calls_file.read_text().splitlines() if calls_file.exists() else []
            preserved = bool(list(output.glob('*/unpacked/manifest.json')))
            return result, calls, preserved

    def test_required_failures_are_nonzero_and_preserve_scene(self):
        for scenario in ('trigger_rejected', 'missing_workflow', 'timeout', 'audit_failure',
                         'snapshot_failure', 'pair_query_failure', 'missing_result', 'hash_mismatch'):
            with self.subTest(scenario=scenario):
                result, calls, preserved = self.run_scenario(scenario)
                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertTrue(preserved)
                self.assertNotIn('drop-forensic-replay-schema.sql', calls)

    def test_complete_replay_cleans_only_owned_schema(self):
        result, calls, preserved = self.run_scenario('success')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(calls.count('drop-forensic-replay-schema.sql'), 1)
        self.assertTrue(preserved)

    def test_real_job_terminal_codes_do_not_wait_for_timeout(self):
        for status in ('CANCELLED', 'TERMINATED', 'SUCCESS_DRY_RUN', 'FAILED_DRY_RUN'):
            with self.subTest(status=status):
                result, calls, preserved = self.run_scenario('success', terminal_status=status)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertTrue(preserved)
                self.assertIn('drop-forensic-replay-schema.sql', calls)

    def test_same_export_has_distinct_owned_schema_on_each_run(self):
        first, first_calls, _ = self.run_scenario('success')
        second, second_calls, _ = self.run_scenario('success')
        self.assertEqual(first.returncode, 0, first.stdout + first.stderr)
        self.assertEqual(second.returncode, 0, second.stdout + second.stderr)
        first_schemas = {call for call in first_calls if call.startswith('replay_schema=')}
        second_schemas = {call for call in second_calls if call.startswith('replay_schema=')}
        self.assertEqual(len(first_schemas), 1)
        self.assertEqual(len(second_schemas), 1)
        self.assertTrue(first_schemas.isdisjoint(second_schemas))

    def test_explicit_no_launch_does_not_require_replay_results(self):
        result, calls, preserved = self.run_scenario('success', no_launch=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotIn('select-forensic-trigger-pairs.sql', calls)
        self.assertTrue(preserved)


if __name__ == '__main__':
    unittest.main()
