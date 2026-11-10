# 用途：Sim/容量/恢复脚本契约。
# 用法：在仓库根目录执行 python3 -m unittest scripts/ci/tests/test_control_plane_mixed_pressure.py。
"""混合容量画像必须传递真实 Workflow 身份，并保持独立业务日与分发文件序号。"""

import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[3]
BENCHMARK = ROOT / 'load-tests/scripts/run-control-plane-worker-benchmark.sh'
SIMULATION = ROOT / 'load-tests/src/test/java/io/github/pinpols/batch/loadtest/simulations/ControlPlaneMixedPressureSimulation.java'


class MixedPressureWorkflowArgumentsTest(unittest.TestCase):
    def run_mixed(self, *, failed_job='', modules='process,dispatch,atomic,trigger', gatling_exit=0, abort_on_failure='0', sampler_exit=0):
        function = re.search(r'(?ms)^run_mixed_pressure\(\) \{.*?^\}', BENCHMARK.read_text()).group()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'scripts').mkdir()
            sampler = root / 'scripts/sample-scheduler-backlog.sh'
            sampler.write_text('#!/usr/bin/env bash\nprintf "sampler\\n" >> "$CALLS"\nexit "$SAMPLER_EXIT"\n')
            sampler.chmod(0o700)
            (root / 'process.json').write_text('{"bizDate":"2026-10-18"}')
            shell = r'''
set -euo pipefail
trap 'printf "cleanup-skip:%s\nsampler-status:%s\n" "${SKIP_AUTO_CLEANUP:-0}" "${SCHEDULER_SAMPLER_STATUS:-not-run}" >> "$CALLS"' EXIT
csv_contains() { [[ ",$2," == *",$1,"* ]]; }
resolve_single_job_workflow_id() {
  printf 'resolve:%s:%s\n' "$1" "$2" >> "$CALLS"
  [[ "$2" != "$FAILED_JOB" ]] || return 2
  case "$2" in
    lt_process_sql_job) echo 101 ;;
    lt_dispatch_local_job) echo 102 ;;
    atomic_sql_demo) echo 103 ;;
    trigger_job) echo 104 ;;
    *) return 2 ;;
  esac
}
mvn() {
  printf '%s\n' "$@" > "$MVN_ARGS"
  for argument in "$@"; do
    if [[ "$argument" == -Dcontrol.process.paramsJsonFile=* ]]; then
      printf 'process-date:%s\n' "$(jq -r .bizDate "${argument#*=}")" >> "$CALLS"
    fi
  done
  return "$GATLING_EXIT"
}
wait_run_terminal() { printf 'terminal:%s\n' "$1" >> "$CALLS"; }
assert_run_success() { printf 'success:%s\n' "$1" >> "$CALLS"; }
'''
            environment = {
                'PATH': os.environ['PATH'], 'LOAD_DIR': temporary, 'LOG_DIR': temporary,
                'PARAM_DIR': temporary, 'RUN_ID': 'owned-mixed-run', 'BIZ_DATE': '2026-10-18',
                'LOAD_TEST_TENANT_ID': 'tenant-a', 'MODULES_CSV': modules,
                'TRIGGER_JOB_CODE': 'trigger_job', 'TRIGGER_DURATION_SECONDS': '30',
                'PROCESS_LAUNCH_RPS': '1', 'DISPATCH_LAUNCH_RPS': '1', 'ATOMIC_LAUNCH_RPS': '1',
                'TRIGGER_LAUNCH_RPS': '3', 'TRIGGER_READ_RPS': '1', 'BIZ_DATE_CARDINALITY': '180',
                'TRIGGER_BASE_URL': 'http://localhost:28081', 'CONSOLE_BASE_URL': 'http://localhost:28080',
                'ORCHESTRATOR_BASE_URL': 'http://localhost:28082', 'INTERNAL_SECRET': 'test-secret',
                'PROCESS_PARAMS': str(root / 'process.json'), 'DISPATCH_PARAMS': str(root / 'dispatch.json'),
                'DISPATCH_FILE_IDS_CSV': '1,2', 'WRITE_P95_MS': '500', 'MAX_ERROR_PCT': '0',
                'CALLS': str(root / 'calls.txt'), 'MVN_ARGS': str(root / 'mvn-args.txt'),
                'FAILED_JOB': failed_job, 'GATLING_EXIT': str(gatling_exit),
                'SAMPLER_EXIT': str(sampler_exit),
                'WAIT_TERMINAL_ABORT_ON_STABLE_SHORTFALL': abort_on_failure,
            }
            result = subprocess.run(['bash', '-c', shell + function + '\nrun_mixed_pressure\n'],
                                    text=True, capture_output=True, env=environment)
            args = (root / 'mvn-args.txt').read_text().splitlines() if (root / 'mvn-args.txt').exists() else []
            calls = (root / 'calls.txt').read_text().splitlines() if (root / 'calls.txt').exists() else []
            return result, args, calls

    def test_all_selected_workflow_ids_and_run_id_reach_gatling(self):
        result, args, calls = self.run_mixed()
        self.assertEqual(result.returncode, 0, result.stderr)
        for module, identity in [('process', 101), ('dispatch', 102), ('atomic', 103), ('trigger', 104)]:
            self.assertIn(f'-Dcontrol.{module}.workflowDefinitionId={identity}', args)
        self.assertIn('-Dload.runId=owned-mixed-run', args)
        self.assertIn('resolve:tenant-a:trigger_job', calls)

    def test_missing_managed_workflow_rejects_before_sampler_or_load(self):
        result, args, calls = self.run_mixed(failed_job='lt_dispatch_local_job')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(args, [])
        self.assertNotIn('sampler', calls)

    def test_disabled_modules_are_not_resolved(self):
        result, args, calls = self.run_mixed(modules='process')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('-Dcontrol.process.workflowDefinitionId=101', args)
        self.assertEqual([c for c in calls if c.startswith('resolve:')], ['resolve:tenant-a:lt_process_sql_job'])

    def test_gatling_failure_returns_without_waiting_for_impossible_target(self):
        result, _, calls = self.run_mixed(gatling_exit=9, abort_on_failure='1')
        self.assertEqual(result.returncode, 9, result.stderr)
        self.assertNotIn('terminal:mixed', calls)
        self.assertNotIn('success:mixed', calls)
        self.assertIn('cleanup-skip:1', calls)

    def test_generic_gatling_failure_still_collects_business_results(self):
        result, _, calls = self.run_mixed(gatling_exit=9)
        self.assertEqual(result.returncode, 9, result.stderr)
        self.assertIn('terminal:mixed', calls)
        self.assertIn('success:mixed', calls)

    def test_failed_backlog_sampler_fails_result_after_business_collection(self):
        result, _, calls = self.run_mixed(sampler_exit=7)
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('terminal:mixed', calls)
        self.assertIn('success:mixed', calls)
        self.assertIn('cleanup-skip:1', calls)
        self.assertIn('sampler-status:failed:7', calls)
        self.assertIn('scheduler backlog sampler failed', result.stderr)

    def test_gatling_failure_remains_primary_when_sampler_also_fails(self):
        result, _, calls = self.run_mixed(gatling_exit=9, sampler_exit=7)
        self.assertEqual(result.returncode, 9, result.stdout + result.stderr)
        self.assertIn('cleanup-skip:1', calls)

    def test_successful_backlog_sampler_is_recorded(self):
        result, _, calls = self.run_mixed()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('sampler-status:complete', calls)

    def test_process_payload_uses_the_selected_workflow_business_date(self):
        result, _, calls = self.run_mixed()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('process-date:#{bizDate}', calls)


class DatabasePressureSamplerTest(unittest.TestCase):
    def test_failed_query_is_recorded_and_later_samples_continue(self):
        function = re.search(r'(?ms)^pg_pressure_sampler\(\) \{.*?^\}', BENCHMARK.read_text()).group()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            shell = r'''
set -euo pipefail
psql_platform() {
  if [[ ! -e "$COUNTER" ]]; then touch "$COUNTER"; return 7; fi
  printf '2026-11-10 00:00:00+00|100|1|0|0|1|0|0|0|0|0|0|0|0|0|0\n'
}
sleep() {
  if [[ ! -e "$SLEEP_COUNTER" ]]; then touch "$SLEEP_COUNTER"; return 0; fi
  return 23
}
'''
            environment = {'PATH': os.environ['PATH'], 'LOAD_DIR': temporary,
                           'PG_SAMPLE_INTERVAL_SECONDS': '1', 'COUNTER': str(root / 'query'),
                           'SLEEP_COUNTER': str(root / 'sleep')}
            result = subprocess.run(
                ['bash', '-c', shell + function + '\npg_pressure_sampler "$1" "$2"\n',
                 'sampler-test', str(root / 'samples.csv'), str(root / 'errors.log')],
                text=True, capture_output=True, env=environment)
            self.assertEqual(result.returncode, 23, result.stdout + result.stderr)
            self.assertTrue((root / 'errors.log').exists())
            self.assertIn('query failed', (root / 'errors.log').read_text())
            self.assertIn('|100|1|0|', (root / 'samples.csv').read_text())

    def run_stop(self, *, samples, errors, terminate_early=False):
        function = re.search(r'(?ms)^stop_pg_pressure_sampler\(\) \{.*?^\}', BENCHMARK.read_text()).group()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'pg-pressure-samples.csv').write_text(samples)
            (root / 'pg-pressure-sample-errors.log').write_text(errors)
            shell = r'''
set -u
sleep 60 &
PG_SAMPLER_PID=$!
if [[ "$TERMINATE_EARLY" == 1 ]]; then kill "$PG_SAMPLER_PID"; wait "$PG_SAMPLER_PID" 2>/dev/null || true; fi
'''
            shell += function + r'''
rc=0
stop_pg_pressure_sampler || rc=$?
printf 'status:%s cleanup-skip:%s\n' "$PG_SAMPLER_STATUS" "${SKIP_AUTO_CLEANUP:-0}"
exit "$rc"
'''
            return subprocess.run(['bash', '-c', shell], text=True, capture_output=True,
                                  env={'PATH': os.environ['PATH'], 'LOG_DIR': temporary,
                                       'TERMINATE_EARLY': str(int(terminate_early))})

    def test_sampler_outcome_distinguishes_complete_and_invalid_evidence(self):
        complete = self.run_stop(samples='header\nsample\n', errors='')
        self.assertEqual(complete.returncode, 0, complete.stdout + complete.stderr)
        self.assertIn('status:complete cleanup-skip:0', complete.stdout)
        incomplete = self.run_stop(samples='header\nsample\n', errors='query failed\n')
        self.assertNotEqual(incomplete.returncode, 0)
        self.assertIn('status:incomplete cleanup-skip:1', incomplete.stdout)
        missing = self.run_stop(samples='header\n', errors='')
        self.assertNotEqual(missing.returncode, 0)
        self.assertIn('status:no-samples cleanup-skip:1', missing.stdout)
        stopped = self.run_stop(samples='header\nsample\n', errors='', terminate_early=True)
        self.assertNotEqual(stopped.returncode, 0)
        self.assertIn('status:stopped-unexpectedly cleanup-skip:1', stopped.stdout)


class TriggerPressureSamplerTest(unittest.TestCase):
    def run_trigger(self, *, sampler_exit, gatling_exit=0):
        function = re.search(r'(?ms)^run_trigger_pressure\(\) \{.*?^\}', BENCHMARK.read_text()).group()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'scripts').mkdir()
            sampler = root / 'scripts/sample-scheduler-backlog.sh'
            sampler.write_text('#!/usr/bin/env bash\nexit "$SAMPLER_EXIT"\n')
            shell = r'''
set -euo pipefail
trap 'printf "cleanup-skip:%s\nsampler-status:%s\n" "${SKIP_AUTO_CLEANUP:-0}" "${SCHEDULER_SAMPLER_STATUS:-not-run}"' EXIT
resolve_single_job_workflow_id() { echo 104; }
mvn() { return "$GATLING_EXIT"; }
'''
            environment = {
                'PATH': os.environ['PATH'], 'LOAD_DIR': temporary, 'LOG_DIR': temporary,
                'TRIGGER_JOB_CODE': 'trigger_job', 'TRIGGER_DURATION_SECONDS': '1',
                'LOAD_TEST_TENANT_ID': 'tenant-a', 'TRIGGER_LAUNCH_RPS': '1',
                'TRIGGER_READ_RPS': '1', 'TRIGGER_BASE_URL': 'http://localhost:28081',
                'CONSOLE_BASE_URL': 'http://localhost:28080',
                'ORCHESTRATOR_BASE_URL': 'http://localhost:28082', 'INTERNAL_SECRET': 'test-secret',
                'RUN_ID': 'test', 'BIZ_DATE': '2026-10-18', 'SCHEDULING_CONSOLE_READS': 'false',
                'MAX_ERROR_PCT': '0', 'TOKEN': 'test-token', 'SAMPLER_EXIT': str(sampler_exit),
                'GATLING_EXIT': str(gatling_exit),
            }
            return subprocess.run(['bash', '-c', shell + function + '\nrun_trigger_pressure params.json\n'],
                                  text=True, capture_output=True, env=environment)

    def test_failed_sampler_fails_and_preserves_data(self):
        result = self.run_trigger(sampler_exit=7)
        self.assertEqual(result.returncode, 7, result.stdout + result.stderr)
        self.assertIn('cleanup-skip:1', result.stdout)
        self.assertIn('sampler-status:failed:7', result.stdout)

    def test_success_and_competing_failure(self):
        success = self.run_trigger(sampler_exit=0)
        self.assertEqual(success.returncode, 0, success.stdout + success.stderr)
        self.assertIn('sampler-status:complete', success.stdout)
        failed = self.run_trigger(sampler_exit=7, gatling_exit=9)
        self.assertEqual(failed.returncode, 9, failed.stdout + failed.stderr)
        self.assertIn('cleanup-skip:1', failed.stdout)


class MixedPressureCompletionCountTest(unittest.TestCase):
    def run_success_check(self, counts, expected):
        function = re.search(r'(?ms)^assert_run_success\(\) \{.*?^\}', BENCHMARK.read_text()).group()
        shell = 'set -eu\npsql_platform() { printf "%s\\n" "$TEST_COUNTS"; }\n'
        return subprocess.run(['bash', '-c', shell + function + '\nassert_run_success mixed\n'],
                              text=True, capture_output=True,
                              env={'PATH': os.environ['PATH'], 'LOAD_TEST_TENANT_ID': 'tenant-a',
                                   'RUN_ID': 'owned-run', 'LOAD_DIR': str(ROOT / 'load-tests'),
                                   'TEST_COUNTS': counts,
                                   'WAIT_TERMINAL_EXPECTED_TRIGGER_REQUESTS': str(expected)})

    def test_subset_of_successful_children_does_not_satisfy_capacity_target(self):
        result = self.run_success_check('1|1|1', 2)
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_all_expected_children_must_finish_successfully(self):
        self.assertEqual(self.run_success_check('2|2|2', 2).returncode, 0)
        self.assertNotEqual(self.run_success_check('2|1|1', 2).returncode, 0)

    def test_unbounded_exploration_keeps_its_minimum_instance_semantics(self):
        self.assertEqual(self.run_success_check('1|1|1', 0).returncode, 0)


class BenchmarkReportRequestCountTest(unittest.TestCase):
    def render_report(self, *, mode, modules, users='1', atomic_jobs='atomic_sql_demo', expected='0'):
        source = BENCHMARK.read_text()
        report_function = re.search(r'(?ms)^write_report\(\) \{.*?^\}', source).group()
        count_function = re.search(r'(?ms)^report_expected_end_to_end_requests\(\) \{.*?^\}', source)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for name in ('kafka-lag-before.txt', 'kafka-lag-after.txt', 'pg-pressure-before.txt',
                         'pg-pressure-after.txt', 'pg-pressure-samples.csv'):
                (root / name).write_text('')
            shell = '''set -eu
csv_contains() { [[ ",$2," == *",$1,"* ]]; }
psql_platform() { :; }
psql_business() { :; }
append_pg_pressure_delta() { :; }
'''
            if count_function:
                shell += count_function.group() + '\n'
            shell += report_function + '\nwrite_report\n'
            environment = {
                'PATH': os.environ['PATH'], 'REPORT': str(root / 'report.md'),
                'LOG_DIR': temporary, 'LOAD_DIR': str(ROOT / 'load-tests'),
                'RUN_ID': 'test-run', 'RUN_STARTED_AT': '2026-11-09T00:00:00Z',
                'CONTROL_PLANE_MODE': mode, 'MODULES_CSV': modules, 'USERS': users,
                'ATOMIC_JOBS_CSV': atomic_jobs, 'RAMP_SECONDS': '1',
                'PROCESS_LAUNCH_RPS': '1', 'DISPATCH_LAUNCH_RPS': '1',
                'ATOMIC_LAUNCH_RPS': '1', 'DISPATCH_FIXTURE_COUNT': '1',
                'TRIGGER_JOB_CODE': 'atomic_sql_demo', 'TRIGGER_LAUNCH_RPS': '1',
                'TRIGGER_READ_RPS': '1', 'TRIGGER_DURATION_SECONDS': '30',
                'WAIT_TERMINAL_EXPECTED_TRIGGER_REQUESTS': expected,
                'BIZ_DATE_CARDINALITY': '1', 'BIZ_DATE': '2026-11-09',
                'SCHEDULING_CONSOLE_READS': 'false', 'SKIP_AUTO_CLEANUP': '1',
                'LOAD_TEST_TENANT_ID': 'test-tenant', 'PG_SAMPLE_INTERVAL_SECONDS': '5',
            }
            result = subprocess.run(['bash', '-c', shell], text=True, capture_output=True, env=environment)
            self.assertEqual(result.returncode, 0, result.stderr)
            return (root / 'report.md').read_text()

    def test_sequential_single_process_reports_one_expected_request(self):
        report = self.render_report(mode='sequential', modules='process')
        self.assertIn('- Expected end-to-end launch requests: 1', report)

    def test_sequential_counts_selected_pipeline_jobs(self):
        report = self.render_report(mode='sequential', modules='process,dispatch,atomic,trigger',
                                    users='4', atomic_jobs='atomic_a,atomic_b')
        self.assertIn('- Expected end-to-end launch requests: 16', report)

    def test_parallel_preserves_existing_expected_count(self):
        report = self.render_report(mode='parallel', modules='process', expected='180')
        self.assertIn('- Expected end-to-end launch requests: 180', report)


class KafkaLagContainerSelectionTest(unittest.TestCase):
    def run_snapshot(self, *, container=None, failing_container='', host_cli=False, host_fails=False,
                     script=BENCHMARK):
        function = re.search(r'(?ms)^kafka_lag_snapshot\(\) \{.*?^\}', script.read_text()).group()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            docker = root / 'docker'
            docker.write_text('''#!/usr/bin/env bash
printf 'docker:%s\\n' "$*" >> "$CALLS"
if [[ "$1" == ps ]]; then
  printf '%s\\n' batch-kafka isolated-kafka
elif [[ "$1" == exec ]]; then
  [[ "$3" != "$FAILING_CONTAINER" ]] || exit 1
  printf 'GROUP LAG\\nbatch-worker-process 2\\n'
else
  exit 1
fi
''')
            docker.chmod(0o700)
            cli = root / 'host-kafka-consumer-groups'
            if host_cli:
                cli.write_text('''#!/usr/bin/env bash
printf 'host:%s\\n' "$*" >> "$CALLS"
[[ "$HOST_FAILS" != 1 ]] || exit 1
printf 'GROUP LAG\\nbatch-worker-process 1\\n'
''')
                cli.chmod(0o700)
            environment = {
                'PATH': f'{root}:/usr/bin:/bin', 'CALLS': str(root / 'calls'),
                'KAFKA_HOST_BOOTSTRAP': '127.0.0.1:29092',
                'KAFKA_CONTAINER_BOOTSTRAP': 'kafka:9092',
                'KAFKA_CONTAINER_BIN_DIR': '/opt/kafka/bin',
                'KAFKA_LAG_GROUP_REGEX': 'batch-worker',
                'FAILING_CONTAINER': failing_container,
                'HOST_FAILS': '1' if host_fails else '0',
            }
            if container is not None:
                environment['KAFKA_CONTAINER'] = container
            if host_cli:
                environment['KAFKA_CONSUMER_GROUPS_BIN'] = str(cli)
            result = subprocess.run(['bash', '-c', 'set -eu\n' + function + '\nkafka_lag_snapshot\n'],
                                    text=True, capture_output=True, env=environment)
            calls = (root / 'calls').read_text().splitlines()
            return result, calls

    def test_explicit_container_wins_over_first_discovered_shared_container(self):
        result, calls = self.run_snapshot(container='isolated-kafka')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls, ['docker:exec -i isolated-kafka /opt/kafka/bin/kafka-consumer-groups.sh '
                                 '--bootstrap-server kafka:9092 --describe --all-groups'])
        self.assertIn('batch-worker-process 2', result.stdout)

    def test_failed_explicit_container_does_not_fall_back_to_shared(self):
        result, calls = self.run_snapshot(container='isolated-kafka', failing_container='isolated-kafka')
        self.assertIn('kafka lag unavailable', result.stdout)
        self.assertEqual(len(calls), 1)
        self.assertIn('exec -i isolated-kafka ', calls[0])

    def test_unspecified_container_keeps_existing_discovery(self):
        result, calls = self.run_snapshot()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(calls[0].startswith('docker:ps '))
        self.assertIn('exec -i batch-kafka ', calls[1])

    def test_host_cli_takes_priority_and_uses_host_bootstrap(self):
        result, calls = self.run_snapshot(container='isolated-kafka', host_cli=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls, ['host:--bootstrap-server 127.0.0.1:29092 --describe --all-groups'])
        self.assertIn('batch-worker-process 1', result.stdout)

    def test_failed_host_cli_uses_only_explicit_container(self):
        result, calls = self.run_snapshot(container='isolated-kafka', host_cli=True, host_fails=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(calls), 2)
        self.assertIn('exec -i isolated-kafka ', calls[1])

    def test_process_benchmark_uses_explicit_container(self):
        script = ROOT / 'load-tests/scripts/run-process-worker-benchmark.sh'
        result, calls = self.run_snapshot(container='isolated-kafka', script=script)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(calls), 1)
        self.assertIn('exec -i isolated-kafka ', calls[0])

    def test_process_benchmark_does_not_fallback_after_explicit_failure(self):
        script = ROOT / 'load-tests/scripts/run-process-worker-benchmark.sh'
        result, calls = self.run_snapshot(
            container='isolated-kafka', failing_container='isolated-kafka', script=script)
        self.assertIn('kafka lag unavailable', result.stdout)
        self.assertEqual(len(calls), 1)
        self.assertIn('exec -i isolated-kafka ', calls[0])


class SoakKafkaLagTest(unittest.TestCase):
    def run_check(self, output, *, fail=False):
        script = ROOT / 'load-tests/scripts/monitor-soak.sh'
        function = re.search(r'(?ms)^check_kafka_lag\(\) \{.*?^\}', script.read_text()).group()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            docker = root / 'docker'
            docker.write_text('''#!/usr/bin/env bash
printf '%s\\n' "$*" >> "$CALLS"
if [[ "$1" == ps ]]; then printf '%s\\n' batch-kafka isolated-kafka; exit; fi
if [[ "$1" == exec ]]; then
  [[ "$KAFKA_FAIL" != 1 ]] || exit 1
  printf '%s\\n' "$KAFKA_OUTPUT"; exit
fi
exit 1
''')
            docker.chmod(0o700)
            environment = {
                'PATH': f'{root}:/usr/bin:/bin', 'CALLS': str(root / 'calls'),
                'KAFKA_CONTAINER': 'isolated-kafka', 'KAFKA_OUTPUT': output,
                'KAFKA_FAIL': '1' if fail else '0',
                'KAFKA_HOST_BOOTSTRAP': '127.0.0.1:29092',
                'KAFKA_CONTAINER_BOOTSTRAP': 'kafka:9092',
                'KAFKA_CONTAINER_BIN_DIR': '/opt/kafka/bin',
                'KAFKA_LAG_THRESHOLD': '100', 'KAFKA_LAG_BREACH_SECONDS': '30',
            }
            shell = ('set -uo pipefail\n' + function
                     + '\nkafka_breach_since=1\ncheck_kafka_lag\nprintf "since=%s\\n" "$kafka_breach_since"\n')
            result = subprocess.run(['bash', '-c', shell], text=True, capture_output=True, env=environment)
            return result, (root / 'calls').read_text().splitlines()

    def test_unknown_lag_is_reported_and_uses_explicit_container(self):
        result, calls = self.run_check('GROUP TOPIC PART CURRENT END LAG\nworker topic 0 - 1 -')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('since=0', result.stdout)
        self.assertIn('kafka lag unavailable', result.stderr)
        self.assertEqual(len(calls), 1)
        self.assertIn('exec -i isolated-kafka ', calls[0])

    def test_observed_zero_lag_resets_breach(self):
        result, calls = self.run_check('GROUP TOPIC PART CURRENT END LAG\nworker topic 0 1 1 0')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('since=0', result.stdout)
        self.assertIn('exec -i isolated-kafka ', calls[0])

    def test_partial_lag_is_reported(self):
        result, _ = self.run_check(
            'GROUP TOPIC PART CURRENT END LAG\nworker topic 0 1 1 0\nworker topic 1 - 1 -')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('since=0', result.stdout)
        self.assertIn('kafka lag incomplete', result.stderr)

    def test_failed_explicit_container_is_unavailable_without_discovery(self):
        result, calls = self.run_check('', fail=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('kafka lag unavailable', result.stderr)
        self.assertEqual(len(calls), 1)
        self.assertIn('exec -i isolated-kafka ', calls[0])


class MixedPressureBusinessDateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source = SIMULATION.read_text()
        start = source.index('  private static Iterator<Map<String, Object>> feeder(String module) {')
        end = source.index('\n  private static ', start + 1)
        feeder = source[start:end]
        sequences = '\n'.join(re.findall(
            r'(?m)^  private static final AtomicLong [A-Z_]+ = new AtomicLong\(\);$', source))
        cls.temporary = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.directory = Path(cls.temporary.name)
        harness = '''
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.*;
public class MixedDateHarness {
  static final int BIZ_DATE_CARDINALITY = Integer.parseInt(System.getProperty("cardinality", "180"));
  static final List<String> DISPATCH_FILE_IDS = IntStream.range(0, 200).mapToObj(Integer::toString).toList();
  static class GatlingConfig { static final String BIZ_DATE = "2026-10-18"; static final String RUN_ID = "owned-run"; }
''' + sequences + '\n' + feeder + '''
  public static void main(String[] args) throws Exception {
    Iterator<Map<String,Object>> atomic = feeder("atomic"), trigger = feeder("trigger");
    if (args[0].equals("independent")) {
      Set<Object> dates = new HashSet<>();
      for (int i=0; i<90; i++) {
        if (!dates.add(trigger.next().get("bizDate"))) throw new AssertionError("duplicate trigger date");
        if (i<30 && !dates.add(atomic.next().get("bizDate"))) throw new AssertionError("atomic/trigger Workflow date collision");
      }
      if (dates.size()!=120) throw new AssertionError("expected 120 independent launches");
    } else if (args[0].equals("dispatch")) {
      for (int i=0; i<5; i++) trigger.next();
      Iterator<Map<String,Object>> dispatch = feeder("dispatch");
      if (!dispatch.next().get("fileId").equals("0") || !dispatch.next().get("fileId").equals("1"))
        throw new AssertionError("global date allocation must not consume dispatch file indexes");
    } else if (args[0].equals("hotspot")) {
      for (int i=0; i<20; i++)
        if (!atomic.next().get("bizDate").equals("2026-10-18") || !trigger.next().get("bizDate").equals("2026-10-18"))
          throw new AssertionError("cardinality=1 must keep the deduplication workload");
    } else {
      Set<Object> dates = ConcurrentHashMap.newKeySet();
      ExecutorService pool = Executors.newFixedThreadPool(4);
      List<Future<?>> futures = new ArrayList<>();
      try {
        for (int worker=0; worker<4; worker++) {
          Iterator<Map<String,Object>> iterator = feeder(worker%2==0 ? "atomic" : "trigger");
          futures.add(pool.submit(() -> {
            for (int i=0; i<45; i++)
              if (!dates.add(iterator.next().get("bizDate"))) throw new AssertionError("concurrent date collision");
          }));
        }
        for (Future<?> future : futures) future.get();
        if (dates.size()!=180) throw new AssertionError("expected 180 unique dates");
      } finally { pool.shutdownNow(); }
    }
  }
}
'''
        path = cls.directory / 'MixedDateHarness.java'
        path.write_text(harness)
        result = subprocess.run(['javac', '-d', str(cls.directory), str(path)], text=True, capture_output=True)
        if result.returncode:
            raise AssertionError(result.stderr)

    def assert_case(self, case, cardinality=180):
        result = subprocess.run(['java', f'-Dcardinality={cardinality}', '-cp', str(self.directory),
                                 'MixedDateHarness', case], text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_shared_atomic_workflow_has_independent_business_dates(self):
        self.assert_case('independent')

    def test_concurrent_feeders_do_not_share_workflow_business_days(self):
        self.assert_case('concurrent')

    def test_dispatch_keeps_its_own_file_fixture_sequence(self):
        self.assert_case('dispatch')

    def test_cardinality_one_keeps_the_deduplication_workload(self):
        self.assert_case('hotspot', cardinality=1)


if __name__ == '__main__':
    unittest.main()
