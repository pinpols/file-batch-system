# 用途：harness 独占配置切换与恢复，阶段脚本只保留自身故障注入重启。
# 用法：在仓库根目录执行 python3 -m unittest scripts/ci/tests/test_sim_profile_ownership.py。
"""harness 独占配置切换与恢复，阶段脚本只保留自身故障注入重启。"""

from pathlib import Path
import os
import re
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]


class SimProfileOwnershipTest(unittest.TestCase):
    def run_stage(self, name, result=0):
        source = (ROOT / 'scripts/local/sim-harness.sh').read_text()
        function = re.search(r'(?ms)^run_sim_stage\(\) \{.*?^\}', source)
        self.assertIsNotNone(function, 'harness must explicitly own initial/final profile switching')
        with tempfile.TemporaryDirectory() as temporary:
            script = Path(temporary) / name
            script.write_text('printf "%s|%s|%s|%s\\n" "${RESTART_IMPORT_WITH_SKIP-unset}" '
                              '"${RESTORE_IMPORT_AFTER_SKIP-unset}" '
                              '"${RESTART_IMPORT_WITH_CHECKPOINT-unset}" '
                              '"${RESTORE_IMPORT_AFTER_CHECKPOINT-unset}"\nexit "${SIM_TEST_EXIT:-0}"\n')
            return subprocess.run(['bash', '-c', function.group(0) + '\nrun_sim_stage "$1"',
                                   'profile-owner', str(script)], text=True, capture_output=True,
                                  env=dict(os.environ, SIM_TEST_EXIT=str(result)))

    def test_skip_stage_delegates_initial_and_final_restart_to_harness(self):
        result = self.run_stage('23-import-stage2d.sh')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), '0|0|unset|unset')

    def test_checkpoint_stage_delegates_profile_while_retaining_its_fault_restart(self):
        result = self.run_stage('25-import-stage2e-checkpoint-crash.sh')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), 'unset|unset|0|0')
        stage = (ROOT / 'scripts/sim/25-import-stage2e-checkpoint-crash.sh').read_text()
        self.assertIn('restart_worker()', stage)

    def test_other_stages_do_not_inherit_mode_overrides(self):
        result = self.run_stage('20-dispatch-stage5c.sh')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), 'unset|unset|unset|unset')

    def test_failure_is_preserved_and_main_loop_uses_owned_runner(self):
        result = self.run_stage('23-import-stage2d.sh', 7)
        self.assertEqual(result.returncode, 7, result.stderr)
        source = (ROOT / 'scripts/local/sim-harness.sh').read_text()
        self.assertIn('if run_sim_stage "$s" </dev/null >"$stage_log" 2>&1; then', source)

    def test_standalone_skip_restore_failure_cannot_be_swallowed(self):
        source = (ROOT / 'scripts/sim/23-import-stage2d.sh').read_text()
        function = re.search(r'(?ms)^restore_import_default\(\) \{.*?^\}', source)
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            script = directory / 'scripts/local/restart.sh'
            script.parent.mkdir(parents=True)
            script.write_text('exit 9\n')
            command = function.group(0) + ('\n__RESTARTED_IMPORT_WITH_SKIP=1\n'
                                          'RESTORE_IMPORT_AFTER_SKIP=1\n'
                                          'trap restore_import_default EXIT\nexit 0')
            result = subprocess.run(['bash', '-c', command], text=True, capture_output=True,
                                    env=dict(os.environ, ROOT=str(directory)))
            self.assertNotEqual(result.returncode, 0)

    def validate_selection(self, arguments, files):
        source = (ROOT / 'scripts/local/sim-harness.sh').read_text()
        function = re.search(r'(?ms)^validate_sim_stage_selection\(\) \{.*?^\}', source)
        self.assertIsNotNone(function)
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            scripts = directory / 'scripts/sim'
            scripts.mkdir(parents=True)
            for filename in files:
                (scripts / filename).write_text('exit 0\n')
            return subprocess.run(['bash', '-c', function.group(0) + '\nvalidate_sim_stage_selection "$@"',
                                   'selection', *arguments], text=True, capture_output=True,
                                  env=dict(os.environ, ROOT=str(directory)))

    def test_explicit_existing_stage_selection_is_accepted(self):
        result = self.validate_selection(['23', '25'], ['23-import.sh', '25-checkpoint.sh'])
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_empty_invalid_and_duplicate_selection_fail_before_execution(self):
        for arguments in ([], ['03'], ['99'], ['23', '23'], ['23,25']):
            with self.subTest(arguments=arguments):
                result = self.validate_selection(arguments, ['23-import.sh'])
                self.assertEqual(result.returncode, 2, result.stderr)

    def test_missing_stage_file_cannot_produce_an_empty_pass(self):
        result = self.validate_selection(['25'], ['23-import.sh'])
        self.assertEqual(result.returncode, 2, result.stderr)

    def test_selected_scope_is_explicit_and_full_mode_keeps_zero_skip_contract(self):
        source = (ROOT / 'scripts/local/sim-harness.sh').read_text()
        self.assertIn('SCOPE selected: $requested_stages (not a full SIM run)', source)
        self.assertIn('== sim:全量 04→30(零跳过) ==', source)
        self.assertIn('sim-selected) shift; validate_sim_stage_selection "$@" && sim "$@"', source)

    def test_sim_entry_scope_message_runs_with_nounset(self):
        source = (ROOT / 'scripts/local/sim-harness.sh').read_text()
        entry = re.search(r'(?ms)^sim\(\) \{.*?(?=^  \( unset BATCH_ENV_LOADED)', source)
        self.assertIsNotNone(entry)
        for arguments, expected in (([], '全量 04→30'), (['23', '25'], '定向阶段 23 25')):
            with self.subTest(arguments=arguments):
                command = 'set -eu\n' + entry.group(0) + '\n}\nsim "$@"'
                result = subprocess.run(['bash', '-c', command, 'sim-entry', *arguments],
                                        text=True, errors='replace', capture_output=True)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn(expected, result.stdout)
        unsafe = re.findall(r'\$[A-Za-z_][A-Za-z_0-9]*[^\x00-\x7F]', source)
        self.assertEqual(unsafe, [], 'variables adjacent to Chinese punctuation need braces')

    def test_restore_waits_for_ready_and_preserves_primary_failure(self):
        source = (ROOT / 'scripts/sim/23-import-stage2d.sh').read_text()
        wait = re.search(r'(?ms)^wait_import_ready\(\) \{.*?^\}', source).group(0)
        restore = re.search(r'(?ms)^restore_import_default\(\) \{.*?^\}', source).group(0)
        for original, curl_exit, expected in ((0, 0, 0), (0, 9, 1), (7, 9, 7)):
            with self.subTest(original=original, curl_exit=curl_exit):
                with tempfile.TemporaryDirectory() as temporary:
                    directory = Path(temporary)
                    script = directory / 'scripts/local/restart.sh'
                    script.parent.mkdir(parents=True)
                    script.write_text('exit 0\n')
                    for program, content in [('curl', f'#!/bin/bash\nexit {curl_exit}\n'),
                                             ('sleep', '#!/bin/bash\nexit 0\n')]:
                        command = directory / program
                        command.write_text(content)
                        command.chmod(0o755)
                    command = wait + '\n' + restore + ('\n__RESTARTED_IMPORT_WITH_SKIP=1\n'
                        'RESTORE_IMPORT_AFTER_SKIP=1\ntrap restore_import_default EXIT\nexit "$1"')
                    result = subprocess.run(['bash', '-c', command, 'restore', str(original)],
                        text=True, capture_output=True, env=dict(os.environ, ROOT=str(directory),
                        WORKER_IMPORT_PORT='18083', PATH=str(directory) + os.pathsep + os.environ['PATH']))
                    self.assertEqual(result.returncode, expected, result.stdout + result.stderr)

    def test_checkpoint_fixture_preserves_unrelated_pipeline_progress(self):
        fixture = (ROOT / 'test-data/sim/sim-stage2e-import-checkpoint.sql').read_text()
        self.assertNotRegex(fixture, r'(?i)DELETE\s+FROM\s+batch\.pipeline_progress')
        query = (ROOT / 'scripts/sim/sql/checkpoint-request-state.sql').read_text()
        self.assertIn('progress.pipeline_instance_id = pipeline.id', query)
        self.assertIn(":'request_id'", query)

    def run_checkpoint_restore(self, original=0, restored='1000', docker_exit=0,
                               worker=False, restart_exit=0, curl_exit=0):
        source = (ROOT / 'scripts/sim/25-import-stage2e-checkpoint-crash.sh').read_text()
        functions = []
        for name in ('wait_import_ready', 'restore_checkpoint_chunk_size', 'restore_import_default'):
            match = re.search(r'(?ms)^' + name + r'\(\) \{.*?^\}', source)
            self.assertIsNotNone(match)
            functions.append(match.group(0))
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            sql = directory / 'scripts/sim/sql/restore-checkpoint-template-chunk-size.sql'
            sql.parent.mkdir(parents=True)
            sql.write_text((ROOT / 'scripts/sim/sql/restore-checkpoint-template-chunk-size.sql').read_text())
            restart = directory / 'scripts/local/restart.sh'
            restart.parent.mkdir(parents=True)
            restart.write_text(f'exit {restart_exit}\n')
            docker = directory / 'docker'
            docker.write_text(f'#!/bin/bash\ncat >/dev/null\nprintf "%s\\n" "{restored}"\nexit {docker_exit}\n')
            docker.chmod(0o755)
            for name, code in [('curl', curl_exit), ('sleep', 0)]:
                program = directory / name
                program.write_text(f'#!/bin/bash\nexit {code}\n')
                program.chmod(0o755)
            command = '\n'.join(functions) + ('\n__RESTARTED_IMPORT_WITH_CHECKPOINT=1\n'
                'CHECKPOINT_BASELINE_CHUNK_SIZE=1000\ntrap restore_import_default EXIT\nexit "$1"')
            return subprocess.run(['bash', '-c', command, 'checkpoint-restore', str(original)],
                text=True, capture_output=True, env=dict(os.environ, ROOT=str(directory),
                RESTORE_IMPORT_AFTER_CHECKPOINT='1' if worker else '0', PG_CONTAINER='owned-platform',
                POSTGRES_USER='test-user', PG_PLATFORM_DB='isolated-platform', WORKER_IMPORT_PORT='18083',
                PATH=str(directory) + os.pathsep + os.environ['PATH']))

    def test_checkpoint_fixture_restore_detects_database_failure_and_conflict(self):
        for original, restored, docker_exit, expected in (
                (0, '1000', 0, 0), (0, '1024', 0, 1), (0, '', 9, 1), (7, '', 9, 7)):
            with self.subTest(original=original, restored=restored, docker_exit=docker_exit):
                result = self.run_checkpoint_restore(original, restored, docker_exit)
                self.assertEqual(result.returncode, expected, result.stdout + result.stderr)
        sql = (ROOT / 'scripts/sim/sql/restore-checkpoint-template-chunk-size.sql').read_text()
        self.assertIn('AND chunk_size = 1', sql)
        self.assertIn("tenant_id = :'tenant_id'", sql)
        self.assertIn("template_code = :'template_code'", sql)

    def test_checkpoint_worker_restore_requires_ready_and_preserves_primary_failure(self):
        for original, restart_exit, curl_exit, expected in ((0, 9, 0, 9), (0, 0, 9, 1), (7, 9, 0, 7), (0, 0, 0, 0)):
            with self.subTest(original=original, restart_exit=restart_exit, curl_exit=curl_exit):
                result = self.run_checkpoint_restore(original, worker=True,
                    restart_exit=restart_exit, curl_exit=curl_exit)
                self.assertEqual(result.returncode, expected, result.stdout + result.stderr)


if __name__ == '__main__':
    unittest.main()
