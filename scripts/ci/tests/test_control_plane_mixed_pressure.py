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
    def run_mixed(self, *, failed_job='', modules='process,dispatch,atomic,trigger', gatling_exit=0):
        function = re.search(r'(?ms)^run_mixed_pressure\(\) \{.*?^\}', BENCHMARK.read_text()).group()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'scripts').mkdir()
            sampler = root / 'scripts/sample-scheduler-backlog.sh'
            sampler.write_text('#!/usr/bin/env bash\nprintf "sampler\\n" >> "$CALLS"\n')
            sampler.chmod(0o700)
            shell = r'''
set -euo pipefail
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
mvn() { printf '%s\n' "$@" > "$MVN_ARGS"; return "$GATLING_EXIT"; }
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

    def test_gatling_failure_keeps_its_exit_after_business_result_collection(self):
        result, _, calls = self.run_mixed(gatling_exit=9)
        self.assertEqual(result.returncode, 9, result.stderr)
        self.assertIn('terminal:mixed', calls)
        self.assertIn('success:mixed', calls)


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
