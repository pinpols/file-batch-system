# 用途：运行 WARN 审计按同一日志的本地上下文识别 Atomic 提醒，不放过隔离缺口。
# 用法：在仓库根目录执行 python3 -m unittest scripts/ci/tests/test_runtime_warning_audit.py。
"""运行 WARN 审计按同一日志的本地上下文识别 Atomic 提醒，不放过隔离缺口。"""

from pathlib import Path
import os
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
SPI = ('AtomicIsolationStartupCheck - ===== ADR-029 dual-use SPI executor(s) ENABLED: '
       '[shell, sql, stored-proc] ===== This worker can run arbitrary commands/SQL/stored-procs. '
       'MINIMAL-PRIVILEGE deployment is REQUIRED: isolation requirements follow')
HEADER = 'The following 1 profile is active: "local"'


class RuntimeWarningAuditTest(unittest.TestCase):
    def audit_directory(self, directory, *, command_failure=None):
        environment = dict(os.environ, RUNTIME_WARNING_LOG_DIR=str(directory),
                           RUNTIME_WARNING_POLICY_FILE=str(ROOT / 'config/runtime-warning-policy.tsv'))
        if command_failure is not None:
            binary = directory / 'bin'
            binary.mkdir()
            command = binary / command_failure
            command.write_text('#!/bin/bash\necho "injected log read failure" >&2\nexit 2\n')
            command.chmod(0o755)
            environment['PATH'] = str(binary) + os.pathsep + os.environ['PATH']
        return subprocess.run(['bash', str(ROOT / 'scripts/local/audit-runtime-warnings.sh')],
                              env=environment, text=True, capture_output=True)

    def test_missing_log_directory_is_an_input_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            result = self.audit_directory(Path(temporary) / 'missing')
            self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
            self.assertIn('FAIL RUNTIME_WARNINGS', result.stderr)

    def test_empty_log_directory_cannot_report_a_clean_audit(self):
        with tempfile.TemporaryDirectory() as temporary:
            result = self.audit_directory(Path(temporary))
            self.assertEqual(result.returncode, 2, result.stdout + result.stderr)

    def test_only_excluded_debug_logs_do_not_satisfy_audit_input(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / 'worker.debug.log').write_text('2026-10-18 WARN unexpected\n')
            result = self.audit_directory(directory)
            self.assertEqual(result.returncode, 2, result.stdout + result.stderr)

    def test_application_log_with_no_warnings_is_valid_input(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / 'worker.log').write_text('2026-10-18 INFO started\n')
            result = self.audit_directory(directory)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('total=0 expected=0', result.stdout)

    def test_warning_collection_error_cannot_be_treated_as_no_warnings(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / 'worker.log').write_text('2026-10-18 WARN unexpected\n')
            result = self.audit_directory(directory, command_failure='grep')
            self.assertEqual(result.returncode, 2, result.stdout + result.stderr)

    def test_log_inventory_error_cannot_be_treated_as_no_warnings(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / 'worker.log').write_text('2026-10-18 WARN unexpected\n')
            result = self.audit_directory(directory, command_failure='find')
            self.assertEqual(result.returncode, 2, result.stdout + result.stderr)

    def test_context_lookup_supports_log_directory_with_colon_and_spaces(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary) / 'run: selected app'
            directory.mkdir()
            (directory / 'atomic.log').write_text(HEADER + '\n2026-10-18 WARN  ' + SPI + '\n')
            result = self.audit_directory(directory)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('expected=1', result.stdout)

    def audit(self, message, header='', *, other_header='', policy=None):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / 'worker-atomic.log').write_text(header + '\n2026-10-16 WARN  ' + message + '\n')
            if other_header:
                (directory / 'other.log').write_text(other_header + '\n')
            environment = dict(os.environ, RUNTIME_WARNING_LOG_DIR=str(directory))
            if policy is not None:
                path = directory / 'policy.tsv'
                path.write_text(policy)
                environment['RUNTIME_WARNING_POLICY_FILE'] = str(path)
            return subprocess.run(['bash', str(ROOT / 'scripts/local/audit-runtime-warnings.sh')],
                                  env=environment, text=True, capture_output=True)

    def test_exact_atomic_reminder_is_expected_with_same_file_local_profile(self):
        result = self.audit(SPI, HEADER)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('expected=1', result.stdout)

    def test_missing_profile_context_remains_action_required(self):
        result = self.audit(SPI)
        self.assertEqual(result.returncode, 3, result.stdout + result.stderr)
        self.assertIn('context_missing', result.stdout)

    def test_production_profile_remains_action_required(self):
        result = self.audit(SPI, 'The following 1 profile is active: "prod"')
        self.assertEqual(result.returncode, 3, result.stdout + result.stderr)

    def test_profile_in_other_file_cannot_authorize_reminder(self):
        result = self.audit(SPI, other_header=HEADER)
        self.assertEqual(result.returncode, 3, result.stdout + result.stderr)

    def test_extra_executor_is_not_covered_by_local_reminder(self):
        result = self.audit(SPI.replace('[shell, sql, stored-proc]', '[shell, sql, stored-proc, spark-submit]'), HEADER)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn('unknown=1', result.stderr)

    def test_empty_allowlist_is_action_required_even_in_local_profile(self):
        result = self.audit("Atomic dual-use executor in profile ([local]) detected 'empty allowlist = allow all' without enforcement", HEADER)
        self.assertEqual(result.returncode, 3, result.stdout + result.stderr)

    def test_unknown_warning_still_fails(self):
        result = self.audit('unexpected terminal task was executed', HEADER)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)

    def test_existing_five_column_policy_keeps_its_meaning(self):
        result = self.audit('batch.security.bypass-mode=true local')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_lettuce_already_closed_is_expected_only_during_shutdown(self):
        warning = ('[SpringApplicationShutdownHook] [     ] io.lettuce.core.RedisChannelHandler - '
                   'Connection is already closed')
        result = self.audit(warning)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('expected=1', result.stdout)

    def test_lettuce_already_closed_during_runtime_remains_unknown(self):
        warning = ('[main] [     ] io.lettuce.core.RedisChannelHandler - '
                   'Connection is already closed')
        result = self.audit(warning)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn('unknown=1', result.stderr)

    def test_local_schedule_revision_race_is_expected_after_database_rollback_coverage(self):
        warning = ('i.g.p.b.t.i.scheduler.TriggerReconciler - '
                   'scheduled-occurrence-materialization-failed: IllegalStateException: '
                   'Tenant configuration changed during scheduled occurrence materialization')
        result = self.audit(warning, HEADER)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('expected=1', result.stdout)

    def test_schedule_revision_race_outside_local_profile_is_action_required(self):
        warning = ('i.g.p.b.t.i.scheduler.TriggerReconciler - '
                   'scheduled-occurrence-materialization-failed: IllegalStateException: '
                   'Tenant configuration changed during scheduled occurrence materialization')
        result = self.audit(warning, 'The following 1 profile is active: "prod"')
        self.assertEqual(result.returncode, 3, result.stdout + result.stderr)
        self.assertIn('context_missing', result.stdout)

    def test_only_known_stage6c_fixture_misfire_is_expected_locally(self):
        policy = (
            'LOCAL_SIM_QUARTZ_STAGE6C_MISFIRE\tEXPECTED\tworkflow-scheduler\t2027-03-31\t'
            'Quartz trigger misfire detected, triggerName=ta:WF:WF-TA_TRIGGER_STAGE6C_(SCHEDULED|MISFIRE)\t'
            'The following 1 profile is active: "local"\n')
        result = self.audit(
            'QuartzMisfireListener - Quartz trigger misfire detected, '
            'triggerName=ta:WF:WF-TA_TRIGGER_STAGE6C_MISFIRE', HEADER, policy=policy)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('expected=1', result.stdout)

    def test_unregistered_quartz_misfire_remains_unknown(self):
        policy = (
            'LOCAL_SIM_QUARTZ_STAGE6C_MISFIRE\tEXPECTED\tworkflow-scheduler\t2027-03-31\t'
            'Quartz trigger misfire detected, triggerName=ta:WF:WF-TA_TRIGGER_STAGE6C_(SCHEDULED|MISFIRE)\t'
            'The following 1 profile is active: "local"\n')
        result = self.audit(
            'QuartzMisfireListener - Quartz trigger misfire detected, '
            'triggerName=ta:WF:PAYMENTS', HEADER, policy=policy)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn('unknown=1', result.stderr)

    def test_local_config_package_rate_limit_is_expected_only_for_sim_user_and_routes(self):
        messages = (
            'ConsoleRateLimitFilter - Expensive-operation rate limit triggered: '
            'user=testing-console-user path=/api/console/config/tenant-package/excel/upload',
            'ConsoleRateLimitFilter - Expensive-operation rate limit triggered: '
            'user=testing-console-user path=/api/console/config/tenant-package/excel/preview/'
            '0123456789abcdef0123456789abcdef',
        )
        for warning in messages:
            with self.subTest(warning=warning):
                result = self.audit(warning, HEADER)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn('expected=1', result.stdout)

    def test_config_package_rate_limit_for_other_user_remains_unknown(self):
        warning = ('ConsoleRateLimitFilter - Expensive-operation rate limit triggered: '
                   'user=alice path=/api/console/config/tenant-package/excel/upload')
        result = self.audit(warning, HEADER)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn('unknown=1', result.stderr)

    def test_config_package_rate_limit_does_not_cover_other_routes(self):
        warning = ('ConsoleRateLimitFilter - Expensive-operation rate limit triggered: '
                   'user=testing-console-user path=/api/console/reports/excel')
        result = self.audit(warning, HEADER)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn('unknown=1', result.stderr)

    def test_config_package_rate_limit_outside_local_profile_requires_action(self):
        warning = ('ConsoleRateLimitFilter - Expensive-operation rate limit triggered: '
                   'user=testing-console-user path=/api/console/config/tenant-package/excel/upload')
        result = self.audit(warning, 'The following 1 profile is active: "prod"')
        self.assertEqual(result.returncode, 3, result.stdout + result.stderr)
        self.assertIn('context_missing', result.stdout)

    def test_only_stage3_bad_sql_fixture_failure_is_expected_locally(self):
        warning = ('GenerateStep - export GENERATE failed: tenantId=ta, '
                   'jobCode=TA_EXPORT_REPORT, '
                   'batchNo=sim-export-stage3-20261017010016-badsql, '
                   'errorClass=BadSqlGrammarException')
        result = self.audit(warning, HEADER)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('expected=1', result.stdout)

    def test_sim_dead_letter_for_fixture_tenants_is_expected_only_locally(self):
        warning = ('DefaultRetryGovernanceService - dead letter created: '
                   'tenantId=ta, partitionId=1200, instanceNo=inst-20261022T035249Z-test, '
                   'errorClass=BUSINESS')
        result = self.audit(warning, HEADER)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('expected=1', result.stdout)

    def test_dead_letter_for_non_fixture_tenant_remains_unknown(self):
        warning = ('DefaultRetryGovernanceService - dead letter created: '
                   'tenantId=customer-42, partitionId=1200, instanceNo=inst-test, '
                   'errorClass=BUSINESS')
        result = self.audit(warning, HEADER)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn('unknown=1', result.stderr)

    def test_sim_unknown_outcome_retry_block_is_expected_for_atomic_fixture(self):
        warning = ('DefaultRetryGovernanceService - automatic retry blocked for unknown external outcome: '
                   'tenantId=default-tenant, jobCode=ATOMIC_TEST, partitionId=1281')
        result = self.audit(warning, HEADER)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('expected=1', result.stdout)

    def test_sim_stage5b_unknown_outcome_retry_block_is_expected_for_fixture_job(self):
        warning = ('DefaultRetryGovernanceService - automatic retry blocked for unknown external outcome: '
                   'tenantId=tb, jobCode=TB_DISPATCH_STAGE5_FAIL_ONCE, partitionId=1281')
        result = self.audit(warning, HEADER)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('expected=1', result.stdout)

    def test_sim_stage5b_unknown_outcome_retry_block_for_other_job_remains_unknown(self):
        warning = ('DefaultRetryGovernanceService - automatic retry blocked for unknown external outcome: '
                   'tenantId=tb, jobCode=TB_OTHER_JOB, partitionId=1281')
        result = self.audit(warning, HEADER)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn('unknown=1', result.stderr)

    def test_unknown_outcome_retry_block_for_other_tenant_remains_unknown(self):
        warning = ('DefaultRetryGovernanceService - automatic retry blocked for unknown external outcome: '
                   'tenantId=ta, jobCode=OTHER_TENANT_JOB, partitionId=1281')
        result = self.audit(warning, HEADER)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn('unknown=1', result.stderr)

    def test_sim_checkpoint_lease_reclaim_is_expected_only_for_tenant_ta(self):
        warning = ('PartitionReclaimUnit - expired partition reclaimed and re-dispatched: '
                   'tenantId=ta, partitionId=1508, taskId=1508, resetSteps=1, '
                   'fromVersion=1, leaseWindowSeconds=120')
        result = self.audit(warning, HEADER)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('expected=1', result.stdout)

    def test_lease_reclaim_for_other_tenant_remains_unknown(self):
        warning = ('PartitionReclaimUnit - expired partition reclaimed and re-dispatched: '
                   'tenantId=customer-42, partitionId=1508, taskId=1508, resetSteps=1, '
                   'fromVersion=1, leaseWindowSeconds=120')
        result = self.audit(warning, HEADER)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn('unknown=1', result.stderr)

    def test_unrelated_export_sql_failure_remains_unknown(self):
        warning = ('GenerateStep - export GENERATE failed: tenantId=ta, '
                   'jobCode=TA_EXPORT_REPORT, '
                   'batchNo=sim-export-stage3-20261017010016, '
                   'errorClass=BadSqlGrammarException')
        result = self.audit(warning, HEADER)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn('unknown=1', result.stderr)

    def test_stage3_bad_sql_failure_outside_local_profile_requires_action(self):
        warning = ('GenerateStep - export GENERATE failed: tenantId=ta, '
                   'jobCode=TA_EXPORT_REPORT, '
                   'batchNo=sim-export-stage3-20261017010016-badsql, '
                   'errorClass=BadSqlGrammarException')
        result = self.audit(warning, 'The following 1 profile is active: "prod"')
        self.assertEqual(result.returncode, 3, result.stdout + result.stderr)
        self.assertIn('context_missing', result.stdout)

    def test_expired_contextual_rule_cannot_pass(self):
        policy = 'OLD\tEXPECTED\tlocal-owner\t2000-01-01\tAtomicIsolationStartupCheck\t' + HEADER + '\n'
        result = self.audit(SPI, HEADER, policy=policy)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn('EXPIRED OLD', result.stdout)


if __name__ == '__main__':
    unittest.main()
