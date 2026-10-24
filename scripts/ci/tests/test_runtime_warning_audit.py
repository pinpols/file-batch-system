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

    def test_expired_contextual_rule_cannot_pass(self):
        policy = 'OLD\tEXPECTED\tlocal-owner\t2000-01-01\tAtomicIsolationStartupCheck\t' + HEADER + '\n'
        result = self.audit(SPI, HEADER, policy=policy)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn('EXPIRED OLD', result.stdout)


if __name__ == '__main__':
    unittest.main()
