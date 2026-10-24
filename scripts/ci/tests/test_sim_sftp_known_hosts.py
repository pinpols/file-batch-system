"""SIM SFTP 通过受控容器公钥固定主机身份，不通过网络扫描或关闭验证建立信任。"""

from __future__ import annotations

import base64
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[3]
PUBLIC_KEY = "ssh-ed25519 " + base64.b64encode(
    len(b"ssh-ed25519").to_bytes(4, "big") + b"ssh-ed25519"
    + (32).to_bytes(4, "big") + bytes(range(32))
).decode("ascii")


class SimSftpKnownHostsTest(unittest.TestCase):
    def run_endpoint(self, runtime='host', running_project='', port=None, stopped=False, docker_exit=0):
        source = (ROOT / 'scripts/sim/env-common.sh').read_text()
        helpers = '\n'.join(re.search(r'(?ms)^' + name + r'\(\) \{.*?^\}', source).group()
                            for name in ('sim_container_stack_active', 'sim_sftp_endpoint'))
        docker = r'''
docker() {
  [[ "$SIM_TEST_DOCKER_EXIT" == 0 ]] || return "$SIM_TEST_DOCKER_EXIT"
  if [[ "$1" == ps && "$SIM_TEST_RUNNING_PROJECT" == "$COMPOSE_PROJECT_NAME" ]]; then
    if [[ "$SIM_TEST_CONSOLE_STOPPED" == 1 && " $* " != *" --all "* ]]; then
      return 0
    fi
    printf '%s\n' owned-console
  fi
}
'''
        environment = dict(os.environ, BATCH_SCRIPT_RUNTIME=runtime,
                           COMPOSE_PROJECT_NAME='isolated-sim', SFTP_HOST='127.0.0.1',
                           SFTP_HOST_PORT='22222', SIM_TEST_RUNNING_PROJECT=running_project,
                           SIM_TEST_CONSOLE_STOPPED=str(int(stopped)),
                           SIM_TEST_DOCKER_EXIT=str(docker_exit))
        environment.pop('SFTP_PORT', None)
        if port is not None:
            environment['SFTP_PORT'] = port
        return subprocess.run(['bash', '-c', docker + helpers + '\nsim_sftp_endpoint'],
                              env=environment, text=True, capture_output=True)

    def test_host_runtime_uses_selected_port_even_with_shared_console_running(self):
        result = self.run_endpoint('host', 'batch-platform')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), '127.0.0.1 22222')

    def test_auto_runtime_ignores_another_projects_console(self):
        result = self.run_endpoint('auto', 'batch-platform')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), '127.0.0.1 22222')

    def test_auto_runtime_recognizes_selected_projects_console(self):
        result = self.run_endpoint('auto', 'isolated-sim')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), 'sftp 22')

    def test_auto_runtime_keeps_compose_ownership_while_console_is_stopped(self):
        result = self.run_endpoint('auto', 'isolated-sim', stopped=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), 'sftp 22')

    def test_explicit_docker_runtime_uses_service_endpoint_before_apps_start(self):
        result = self.run_endpoint('docker')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), 'sftp 22')

    def test_host_endpoint_preserves_explicit_port_for_preflight_validation(self):
        result = self.run_endpoint(port='23222')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), '127.0.0.1 23222')

    def test_auto_runtime_inspection_failure_cannot_fall_back_to_host(self):
        result = self.run_endpoint('auto', docker_exit=9)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, '')

    def test_invalid_runtime_cannot_silently_choose_host_endpoint(self):
        result = self.run_endpoint('unknown')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, '')

    def run_known_hosts_path(self, project):
        source = (ROOT / 'scripts/sim/env-common.sh').read_text()
        helper = re.search(r'(?ms)^sim_sftp_known_hosts_path\(\) \{.*?^\}', source)
        self.assertIsNotNone(helper, 'host-key paths must be scoped to the selected project')
        return subprocess.run(['bash', '-c', helper.group() + '\nsim_sftp_known_hosts_path'],
                              env=dict(os.environ, COMPOSE_PROJECT_NAME=project),
                              text=True, capture_output=True)

    def test_host_key_paths_do_not_overlap_between_projects(self):
        paths = []
        for project in ('batch-platform', 'workflow-daily-test'):
            result = self.run_known_hosts_path(project)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.strip(), f'/tmp/batch/sim-channel-trust/{project}/known_hosts')
            paths.append(result.stdout.strip())
        self.assertNotEqual(paths[0], paths[1])

    def test_project_identity_cannot_escape_host_key_directory(self):
        for project in ('../shared', 'project/other', '/tmp/shared', 'project\nother'):
            with self.subTest(project=project):
                result = self.run_known_hosts_path(project)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(result.stdout, '')

    def test_probe_fixture_preparation_is_scoped_and_key_failure_blocks_sql(self):
        source = (ROOT / 'scripts/sim/env-common.sh').read_text()
        helper = re.search(r'(?ms)^sim_prepare_sftp_probe_channels\(\) \{.*?^\}', source)
        self.assertIsNotNone(helper)
        with tempfile.TemporaryDirectory() as temporary:
            marker = Path(temporary) / 'database-called'
            command = ('mkdir() { :; }; sim_sftp_endpoint() { echo "127.0.0.1 12222"; }; '
                       'sim_sftp_known_hosts_path() { echo /tmp/owned/known_hosts; }; '
                       'sim_write_sftp_known_hosts() { return 7; }; '
                       'docker() { touch "$SIM_TEST_MARKER"; };\n' + helper.group(0)
                       + '\nsim_prepare_sftp_probe_channels')
            result = subprocess.run(['bash', '-c', command], text=True, capture_output=True,
                                    env=dict(os.environ, SIM_TEST_MARKER=str(marker)))
            self.assertEqual(result.returncode, 1, result.stderr)
            self.assertFalse(marker.exists())
        sql = (ROOT / 'scripts/sim/sql/prepare-sftp-probe-fixtures.sql').read_text()
        self.assertIn("AND (tenant_id, channel_code) IN", sql)
        for tenant, code in (('ta', 'ta_sftp_inbound'), ('tb', 'tb_sftp_inbound'), ('tc', 'tc_sftp_score')):
            self.assertIn(f"('{tenant}', '{code}')", sql)
        self.assertIn("'sftp_strict_host_key_checking', 'yes'", sql)

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.keys = self.root / "host_public_keys"
        self.keys.write_text(PUBLIC_KEY + " controlled-server\n")
        self.calls = self.root / "docker_calls"
        docker = self.bin / "docker"
        docker.write_text(
            '#!/bin/bash\n'
            'printf "%s\\n" "$*" >> "$SIM_TEST_DOCKER_CALLS"\n'
            'cat "$SIM_TEST_PUBLIC_KEYS"\n'
            'exit "${SIM_TEST_DOCKER_EXIT:-0}"\n'
        )
        docker.chmod(0o755)
        self.output = self.root / "shared data" / "known hosts"
        self.output.parent.mkdir()
        source = (ROOT / "scripts/sim/env-common.sh").read_text()
        function = re.search(r"(?ms)^sim_write_sftp_known_hosts\(\) \{.*?^\}", source)
        self.assertIsNotNone(function, "shared SFTP host-key writer must exist")
        self.function = function.group(0)

    def run_writer(self, host="127.0.0.1", port="12222", *, docker_exit=0):
        env = {
            "PATH": str(self.bin) + os.pathsep + os.environ["PATH"],
            "SFTP_CONTAINER": "controlled-sim-sftp",
            "SIM_TEST_DOCKER_CALLS": str(self.calls),
            "SIM_TEST_PUBLIC_KEYS": str(self.keys),
            "SIM_TEST_DOCKER_EXIT": str(docker_exit),
            "LC_ALL": "C",
        }
        return subprocess.run(
            ["bash", "-c", self.function + '\nsim_write_sftp_known_hosts "$1" "$2" "$3"',
             "sftp-host-keys", str(self.output), host, port],
            env=env, text=True, capture_output=True, check=False,
        )

    def assert_no_partial_files(self):
        self.assertEqual(sorted(p.name for p in self.output.parent.iterdir()), [self.output.name])

    def test_public_keys_are_pinned_from_owned_container_for_both_alias_forms(self):
        result = self.run_writer()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.output.read_text(), f"127.0.0.1,[127.0.0.1]:12222 {PUBLIC_KEY}\n")
        self.assertEqual(self.output.stat().st_mode & 0o777, 0o644)
        calls = self.calls.read_text()
        self.assertIn("exec controlled-sim-sftp", calls)
        self.assertIn("/etc/ssh/ssh_host_*_key.pub", calls)
        self.assertNotIn("ssh-keyscan", calls)
        self.assert_no_partial_files()

    def test_hostname_and_default_port_are_supported(self):
        result = self.run_writer("sftp", "22")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.output.read_text(), f"sftp,[sftp]:22 {PUBLIC_KEY}\n")

    def test_read_failure_preserves_existing_configuration_and_cleans_temporary_file(self):
        self.output.write_text("previous pinned configuration\n")
        result = self.run_writer(docker_exit=9)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.output.read_text(), "previous pinned configuration\n")
        self.assert_no_partial_files()

    def test_invalid_or_missing_keys_do_not_replace_existing_configuration(self):
        for keys in ("", "not a public key\n", "ssh-ed25519 invalid!base64\n"):
            with self.subTest(keys=keys):
                self.output.write_text("previous pinned configuration\n")
                self.keys.write_text(keys)
                result = self.run_writer()
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(self.output.read_text(), "previous pinned configuration\n")
                self.assert_no_partial_files()

    def test_unsafe_host_or_port_is_rejected_before_reading_container(self):
        for host, port in (("*", "22"), ("sftp,other", "22"), ("sftp\nother", "22"),
                           ("sftp", "0"), ("sftp", "65536"), ("sftp", "$(false)")):
            with self.subTest(host=host, port=port):
                result = self.run_writer(host, port)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(self.calls.exists())
                self.assertFalse(self.output.exists())

    def test_stage_fixture_uses_strict_mode_and_provided_pinned_key_path(self):
        sql = (ROOT / "test-data/sim/sim-stage5c-dispatch-channels-platform.sql").read_text()
        script = (ROOT / "scripts/sim/20-dispatch-stage5c.sh").read_text()
        self.assertRegex(sql, r"'sftp_strict_host_key_checking',\s*'yes'")
        self.assertIn("'sftp_known_hosts_path', :'sftp_known_hosts_path'", sql)
        self.assertIn("sim_write_sftp_known_hosts", script)
        self.assertIn('SFTP_KNOWN_HOSTS_PATH="$(sim_sftp_known_hosts_path)" || exit 1', script)
        self.assertIn('-v sftp_known_hosts_path="$SFTP_KNOWN_HOSTS_PATH"', script)


if __name__ == "__main__":
    unittest.main()
