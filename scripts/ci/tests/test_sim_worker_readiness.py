# 用途：SIM preflight 核对所选基础设施身份、端点和平台数据库中的 Worker 心跳。
# 用法：在仓库根目录执行 python3 -m unittest scripts/ci/tests/test_sim_worker_readiness.py。
"""SIM preflight 核对所选基础设施身份、端点和平台数据库中的 Worker 心跳。"""

import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]


class SimWorkerReadinessTest(unittest.TestCase):
    def setUp(self):
        self.source = (ROOT / "scripts/local/sim-harness.sh").read_text()
        match = re.search(r"(?ms)^ensure_worker_registrations\(\) \{.*?^\}", self.source)
        self.assertIsNotNone(match, "harness must centralize worker registry readiness")
        self.function = match.group()

    def run_readiness(self, output_by_group, timeout=4, interval=2):
        shell = r'''
sleeps=0
sim_platform_sql() {
  local sql_file="$1"
  shift
  local group=""
  for argument in "$@"; do
    [[ "$argument" == worker_group=* ]] && group="${argument#worker_group=}"
  done
  printf '.\n' >> "$QUERY_LOG"
  printf '%s\n' "${READY_GROUPS[$group]:-0}"
}
sleep() { sleeps=$((sleeps + 1)); }
if ensure_worker_registrations "$READY_TIMEOUT" "$READY_INTERVAL"; then
  result=0
else
  result=$?
fi
printf 'result=%s queries=%s sleeps=%s\n' "$result" "$(wc -l < "$QUERY_LOG" | tr -d '[:space:]')" "$sleeps"
exit "$result"
'''
        env = {"READY_TIMEOUT": str(timeout), "READY_INTERVAL": str(interval)}
        # Bash 无法从环境变量导入关联数组；应在当前 shell 内初始化。
        assignments = "declare -A READY_GROUPS=(" + " ".join(
            f"[{name}]={value}" for name, value in output_by_group.items()
        ) + ")\n"
        shell = assignments + self.function + shell
        with tempfile.NamedTemporaryFile() as query_log:
            env["QUERY_LOG"] = query_log.name
            return subprocess.run(
                ["bash", "-c", shell], text=True, capture_output=True, env={**os.environ, **env}
            )

    def test_all_five_worker_groups_must_be_online(self):
        result = self.run_readiness({
            "IMPORT": "1", "EXPORT": "1", "PROCESS": "1", "DISPATCH": "1", "ATOMIC": "1"
        })
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("result=0 queries=5 sleeps=0", result.stdout)

    def test_missing_worker_group_fails_after_one_shared_timeout(self):
        result = self.run_readiness({
            "IMPORT": "1", "EXPORT": "1", "PROCESS": "1", "DISPATCH": "1", "ATOMIC": "0"
        })
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("timeout=4s, missing=ATOMIC=0", result.stderr)
        self.assertIn("result=1 queries=15 sleeps=2", result.stdout)

    def test_preflight_checks_selected_database_before_fixture_upload(self):
        preflight = re.search(r"(?ms)^preflight\(\) \{.*?^\}", self.source).group()
        self.assertIn("ensure_worker_registrations 30 2", preflight)
        self.assertLess(
            preflight.index("ensure_worker_registrations 30 2"),
            preflight.index("fixture_compat_check || true"),
        )
        self.assertIn("检查 COMPOSE_ENV_FILE 是否与活动服务一致", preflight)

    def test_runtime_probe_reports_connection_refusal_and_checks_remaining_ports(self):
        preflight = re.search(r"(?ms)^preflight\(\) \{.*?^\}", self.source).group()
        loop = re.search(r"(?ms)^    for p in .*?^    done", preflight).group()
        shell = r'''
set -euo pipefail
curl() {
  local url="${@: -1}"
  if [[ "$url" == *:18083/* ]]; then
    return 7
  fi
  printf '200'
}
ok() { printf 'OK:%s\n' "$*"; }
fail() { printf 'FAIL:%s\n' "$*"; FAILED=1; }
probe() {
  local p runtime_ready=1
  FAILED=0
'''+loop+r'''
  printf 'runtime_ready=%s failed=%s\n' "$runtime_ready" "$FAILED"
}
CONSOLE_API_PORT=18080
TRIGGER_PORT=18081
ORCHESTRATOR_PORT=18082
WORKER_IMPORT_PORT=18083
WORKER_EXPORT_PORT=18084
WORKER_DISPATCH_PORT=18085
WORKER_PROCESS_PORT=18086
WORKER_ATOMIC_PORT=18087
probe
[[ "$FAILED" == 1 ]]
'''
        result = subprocess.run(
            ["bash", "-c", shell], text=True, capture_output=True, env=os.environ.copy()
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("FAIL:svc :18083 health=000", result.stdout)
        self.assertIn("OK:svc :18087 UP", result.stdout)
        self.assertIn("runtime_ready=0 failed=1", result.stdout)


class LocalJvmPortMappingTest(unittest.TestCase):
    def run_mapping(self, *, strict='true', **overrides):
        shell = r'''
set -eu
source "$BATCH_ENV_COMMON_ROOT/scripts/lib/env-common.sh"
BATCH_ENV_COMMON_ROOT="$TEST_ENV_ROOT"
COMPOSE_ENV_FILE="$TEST_ENV_ROOT/selected.env"
batch_load_default_env
batch_configure_local_jvm_port_env "$TEST_STRICT"
printf '%s|%s|%s\n' "$CONSOLE_API_PORT" "$BATCH_CONSOLE_API_PORT" "$CONSOLE_BASE"
printf '%s|%s\n' "$TRIGGER_PORT" "$BATCH_TRIGGER_PORT"
printf '%s|%s\n' "$ORCHESTRATOR_PORT" "$BATCH_ORCHESTRATOR_PORT"
printf '%s|%s\n' "$WORKER_IMPORT_PORT" "$BATCH_WORKER_IMPORT_PORT"
printf '%s|%s\n' "$WORKER_EXPORT_PORT" "$BATCH_WORKER_EXPORT_PORT"
printf '%s|%s\n' "$WORKER_DISPATCH_PORT" "$BATCH_WORKER_DISPATCH_PORT"
printf '%s|%s\n' "$WORKER_PROCESS_PORT" "$BATCH_WORKER_PROCESS_PORT"
printf '%s|%s\n' "$WORKER_ATOMIC_PORT" "$BATCH_WORKER_ATOMIC_PORT"
'''
        with tempfile.TemporaryDirectory() as temporary:
            (Path(temporary) / 'selected.env').write_text('')
            environment = {
                'PATH': os.environ['PATH'], 'BATCH_ENV_COMMON_ROOT': str(ROOT),
                'BATCH_ENV_COMMON_HELPERS_ONLY': '1', 'TEST_ENV_ROOT': temporary,
                'TEST_STRICT': strict, **overrides,
            }
            return subprocess.run(['bash', '-c', shell], env=environment,
                                  text=True, capture_output=True)

    def test_custom_console_script_port_drives_actual_spring_property(self):
        result = self.run_mapping(CONSOLE_API_PORT='28080')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('28080|28080|http://localhost:28080', result.stdout)
        source = (ROOT / 'batch-console-api/src/main/resources/application-local.yml').read_text()
        self.assertIn('${BATCH_CONSOLE_API_PORT:18080}', source)

    def test_explicit_spring_port_drives_console_script_and_url(self):
        result = self.run_mapping(BATCH_CONSOLE_API_PORT='28080')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('28080|28080|http://localhost:28080', result.stdout)

    def test_all_eight_custom_script_ports_map_to_application_properties(self):
        ports = {'CONSOLE_API_PORT': '28080', 'TRIGGER_PORT': '28081',
                 'ORCHESTRATOR_PORT': '28082', 'WORKER_IMPORT_PORT': '28083',
                 'WORKER_EXPORT_PORT': '28084', 'WORKER_DISPATCH_PORT': '28085',
                 'WORKER_PROCESS_PORT': '28086', 'WORKER_ATOMIC_PORT': '28087'}
        result = self.run_mapping(**ports)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(result.stdout.splitlines(),
                         ['28080|28080|http://localhost:28080']
                         + [f'{port}|{port}' for port in list(ports.values())[1:]])

    def test_conflicting_host_ports_are_rejected(self):
        result = self.run_mapping(CONSOLE_API_PORT='28080', BATCH_CONSOLE_API_PORT='18080')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('BATCH_CONSOLE_API_PORT', result.stderr)

    def test_docker_host_and_container_port_values_can_remain_distinct(self):
        result = self.run_mapping(strict='false', CONSOLE_API_PORT='28080',
                                  BATCH_CONSOLE_API_PORT='18080')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('28080|18080|http://localhost:28080', result.stdout)

    def test_invalid_host_port_is_rejected_before_startup(self):
        for port in ('0', '65536', 'not-a-port'):
            with self.subTest(port=port):
                result = self.run_mapping(CONSOLE_API_PORT=port)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('BATCH_CONSOLE_API_PORT', result.stderr)

    def test_startup_and_host_sim_require_consistent_ports(self):
        startup = (ROOT / 'scripts/local/start-all.sh').read_text()
        self.assertLess(startup.index('batch_configure_local_jvm_port_env true'),
                        startup.index('docker network create'))
        sim = (ROOT / 'scripts/sim/env-common.sh').read_text()
        validator = re.search(r'(?ms)^sim_validate_infrastructure_targets\(\) \{.*?^\}', sim).group()
        self.assertIn('batch_configure_local_jvm_port_env true || return 1', validator)

    def test_local_lifecycle_entrypoints_use_shared_port_defaults(self):
        port_defaults = {
            'console': 'BATCH_DEFAULT_CONSOLE_PORT',
            'trigger': 'BATCH_DEFAULT_TRIGGER_PORT',
            'orchestrator': 'BATCH_DEFAULT_ORCHESTRATOR_PORT',
            'worker-import': 'BATCH_DEFAULT_WORKER_IMPORT_PORT',
            'worker-export': 'BATCH_DEFAULT_WORKER_EXPORT_PORT',
            'worker-dispatch': 'BATCH_DEFAULT_WORKER_DISPATCH_PORT',
            'worker-process': 'BATCH_DEFAULT_WORKER_PROCESS_PORT',
            'worker-atomic': 'BATCH_DEFAULT_WORKER_ATOMIC_PORT',
        }
        entrypoints = ('sim-harness.sh', 'start-all.sh', 'restart.sh', 'stop-all.sh')
        for entrypoint in entrypoints:
            with self.subTest(entrypoint=entrypoint):
                source = (ROOT / 'scripts/local' / entrypoint).read_text()
                for default_name in port_defaults.values():
                    self.assertIn(f'${default_name}', source)
                self.assertNotRegex(source, r'(?<![0-9])1808[0-7](?![0-9])')
        stop_all = (ROOT / 'scripts/local/stop-all.sh').read_text()
        self.assertIn('source "$ROOT/scripts/lib/env-common.sh"', stop_all)
        self.assertIn('COMPOSE_ENV_FILE="${COMPOSE_ENV_FILE:-$ROOT/.env.local}"', stop_all)
        seed_validator = (ROOT / 'scripts/local/validate-seed-scenarios.sh').read_text()
        seed_worker_defaults = (
            'BATCH_DEFAULT_WORKER_IMPORT_PORT',
            'BATCH_DEFAULT_WORKER_EXPORT_PORT',
            'BATCH_DEFAULT_WORKER_DISPATCH_PORT',
            'BATCH_DEFAULT_WORKER_PROCESS_PORT',
        )
        for default_name in seed_worker_defaults:
            self.assertIn(f'${default_name}', seed_validator)

    def test_stop_all_loads_selected_environment_before_residual_port_checks(self):
        stop_all = (ROOT / 'scripts/local/stop-all.sh').read_text()
        env_source = stop_all.index('source "$ROOT/scripts/lib/env-common.sh"')
        env_load = stop_all.index('batch_load_default_env')
        port_mapping = stop_all.index('batch_configure_local_jvm_port_env true')
        port_check = stop_all.index('_check_port_residual')
        self.assertLess(env_source, env_load)
        self.assertLess(env_load, port_mapping)
        self.assertLess(port_mapping, port_check)

        with tempfile.TemporaryDirectory() as temporary:
            env_file = Path(temporary) / 'selected.env'
            env_file.write_text(
                'CONSOLE_API_PORT=28080\n'
                'TRIGGER_PORT=28081\n'
                'ORCHESTRATOR_PORT=28082\n'
                'WORKER_IMPORT_PORT=28083\n'
                'WORKER_EXPORT_PORT=28084\n'
                'WORKER_DISPATCH_PORT=28085\n'
                'WORKER_PROCESS_PORT=28086\n'
                'WORKER_ATOMIC_PORT=28087\n'
            )
            check_ports = re.search(
                r'(?ms)^_check_port_residual\(\) \{.*?^\}', stop_all
            ).group()
            shell = f'''
set -euo pipefail
COMPOSE_ENV_FILE="$SELECTED_ENV_FILE"
BATCH_ENV_COMMON_HELPERS_ONLY=1
source "$ROOT/scripts/lib/env-common.sh"
batch_load_default_env
batch_configure_local_jvm_port_env true
process_first_listen_pid() {{ printf '%s\\n' "$1" >> "$PORT_LOG"; }}
'''
            result = subprocess.run(
                ['bash', '-c', shell + check_ports + '\n_check_port_residual\n'],
                text=True,
                capture_output=True,
                env={**os.environ, 'ROOT': str(ROOT), 'BATCH_ENV_COMMON_ROOT': str(ROOT),
                     'SELECTED_ENV_FILE': str(env_file), 'PORT_LOG': str(Path(temporary) / 'ports.log')},
            )
            checked_ports = Path(temporary, 'ports.log').read_text().splitlines()

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(checked_ports, ['28082', '28081', '28080', '28083', '28084', '28086', '28085', '28087'])

    def test_start_all_loads_local_environment_before_starting_jvms(self):
        startup = (ROOT / 'scripts/local/start-all.sh').read_text()
        self.assertIn('batch_load_default_env', startup)
        self.assertLess(startup.index('batch_load_default_env'),
                        startup.index('batch_configure_local_jvm_port_env true'))
        self.assertLess(startup.index('batch_load_default_env'),
                        startup.index('start_java orchestrator'))

    def test_host_control_plane_urls_use_actual_application_keys(self):
        shell = r'''
set -eu
source "$BATCH_ENV_COMMON_ROOT/scripts/lib/env-common.sh"
batch_configure_local_jvm_control_plane_env
printf '%s|%s\n' "$BATCH_ORCHESTRATOR_BASE_URL" "$BATCH_TRIGGER_BASE_URL"
'''
        result = subprocess.run(['bash', '-c', shell], text=True, capture_output=True,
                                env={'PATH': os.environ['PATH'], 'BATCH_ENV_COMMON_ROOT': str(ROOT),
                                     'BATCH_ENV_COMMON_HELPERS_ONLY': '1',
                                     'ORCH_BASE': 'http://localhost:28082',
                                     'TRIGGER_BASE': 'http://localhost:28081'})
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(result.stdout.strip(), 'http://localhost:28082|http://localhost:28081')

    def test_generic_compose_environment_does_not_inject_host_service_urls(self):
        source = (ROOT / 'scripts/lib/env-common.sh').read_text()
        loader = re.search(r'(?ms)^batch_load_default_env\(\) \{.*?^\}', source).group()
        self.assertNotIn('batch_configure_local_jvm_control_plane_env', loader)
        compose = (ROOT / 'deploy/docker/compose/app.yml').read_text()
        self.assertIn('${BATCH_ORCHESTRATOR_BASE_URL:-http://orchestrator:18082}', compose)

    def test_host_start_and_restart_apply_control_plane_url_mapping(self):
        startup = (ROOT / 'scripts/local/start-all.sh').read_text()
        restart = (ROOT / 'scripts/local/restart.sh').read_text()
        self.assertIn('batch_configure_local_jvm_control_plane_env', startup)
        host_start = re.search(r'(?ms)^start_module\(\) \{.*?^\}', restart).group()
        self.assertIn('batch_configure_local_jvm_control_plane_env', host_start)
        self.assertLess(host_start.index('compose_restart_module'),
                        host_start.index('batch_configure_local_jvm_control_plane_env'))
        preflight = restart[restart.index('# 校验所有模块名合法'):]
        self.assertLess(preflight.index('batch_configure_local_jvm_port_env true'),
                        preflight.index('echo "==> 停止目标模块..."'))

    def test_console_lifecycle_probes_use_the_same_resolved_port(self):
        for filename in ('start-all.sh', 'restart.sh', 'stop-all.sh', 'sim-harness.sh'):
            with self.subTest(filename=filename):
                source = (ROOT / 'scripts/local' / filename).read_text()
                self.assertFalse('${BATCH_CONSOLE_PORT' in source,
                                 filename + ' must use the resolved Console API port')
        for filename, name in [('start-all.sh', '_port_for_app'), ('restart.sh', 'port_for')]:
            source = (ROOT / 'scripts/local' / filename).read_text()
            function = re.search(r'(?ms)^' + name + r'\(\) \{.*?^\}', source).group()
            result = subprocess.run(['bash', '-c', function + '\n' + name + ' console'],
                                    env={'PATH': os.environ['PATH'], 'CONSOLE_API_PORT': '28080',
                                         'BATCH_CONSOLE_API_PORT': '28080'},
                                    text=True, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.strip(), '28080')


class SimInfrastructureIsolationTest(unittest.TestCase):
    def run_validation(self, **overrides):
        source = (ROOT / 'scripts/sim/env-common.sh').read_text()
        helpers = []
        for name in ('sim_container_stack_active', 'sim_sftp_endpoint',
                     'sim_validate_host_endpoint', 'sim_validate_http_endpoint', 'sim_validate_published_port',
                     'sim_validate_infrastructure_targets'):
            function = re.search(r'(?ms)^' + name + r'\(\) \{.*?^\}', source)
            self.assertIsNotNone(function, f'{name} must validate targets before business writes')
            helpers.append(function.group())
        shell = r'''
set -eu
source "$BATCH_ENV_COMMON_ROOT/scripts/lib/env-common.sh"
source "$BATCH_ENV_COMMON_ROOT/scripts/lib/destructive-ops.sh"
docker() {
  printf '%s\n' "$*" >> "$SIM_TEST_DOCKER_CALLS"
  case "$1" in
    info) return 0 ;;
    context)
      if [[ "$2" == show ]]; then
        printf '%s\n' local-test
      else
        printf '%s\n' unix:///mock/docker.sock
      fi
      ;;
    ps)
      [[ "$SIM_TEST_LIST_EXIT" == 0 ]] || return "$SIM_TEST_LIST_EXIT"
      if [[ "$*" == *name=* && "$SIM_TEST_SFTP_ABSENT" == 0 ]]; then
        printf '%s\n' owned-sftp
      fi
      return 0
      ;;
    inspect)
      local container="${@: -1}"
      if [[ "$container" == isolated-sftp && "$SIM_TEST_SFTP_ABSENT" == 1 ]]; then
        return 1
      fi
      case "$*" in
        *com.docker.compose.project*)
          if [[ "$container" == isolated-kafka && "$SIM_TEST_FOREIGN_KAFKA" == 1 ]]; then
            printf '%s\n' batch-platform
          else
            printf '%s\n' "$COMPOSE_PROJECT_NAME"
          fi
          ;;
        *NetworkSettings.Ports*)
          if [[ "$container" == isolated-kafka ]]; then
            printf '%s\n' "$SIM_TEST_KAFKA_PUBLISHED_PORT"
          else
            printf '%s\n' "$SFTP_HOST_PORT"
          fi
          ;;
      esac
      ;;
    *) return 99 ;;
  esac
}
'''
        environment = {
            'PATH': os.environ['PATH'], 'BATCH_ENV_COMMON_ROOT': str(ROOT),
            'SIM_ROOT': str(ROOT),
            'BATCH_ENV_COMMON_HELPERS_ONLY': '1', 'BATCH_ENV': 'local',
            'BATCH_SCRIPT_RUNTIME': 'host', 'COMPOSE_PROJECT_NAME': 'isolated-sim',
            'PG_CONTAINER': 'isolated-postgres', 'KAFKA_CONTAINER': 'isolated-kafka',
            'MINIO_CONTAINER': 'isolated-minio', 'REDIS_CONTAINER': 'isolated-valkey',
            'SFTP_CONTAINER': 'isolated-sftp', 'KAFKA_HOST_PORT': '19092',
            'KAFKA_HOST_BOOTSTRAP': 'localhost:19092',
            'KAFKA_CONTAINER_BOOTSTRAP': 'kafka:29092',
            'SFTP_HOST_PORT': '22222', 'SIM_TEST_KAFKA_PUBLISHED_PORT': '19092',
            'SIM_TEST_SFTP_ABSENT': '0', 'SIM_TEST_FOREIGN_KAFKA': '0', 'SIM_TEST_LIST_EXIT': '0',
            **overrides,
        }
        with tempfile.NamedTemporaryFile() as calls:
            environment['SIM_TEST_DOCKER_CALLS'] = calls.name
            return subprocess.run(
                ['bash', '-c', shell + '\n'.join(helpers) + '\nsim_validate_infrastructure_targets'],
                env=environment, text=True, capture_output=True,
            )

    def isolated_broker(self, **overrides):
        return self.run_validation(**{
            'KAFKA_HOST_PORT': '29092', 'KAFKA_HOST_BOOTSTRAP': 'localhost:29092',
            'BATCH_KAFKA_BOOTSTRAP_SERVERS': 'localhost:29092',
            'SIM_TEST_KAFKA_PUBLISHED_PORT': '29092', **overrides,
        })

    def test_default_local_broker_and_selected_sftp_mapping_are_valid(self):
        result = self.run_validation()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_host_app_and_sdk_can_share_explicit_isolated_broker(self):
        result = self.isolated_broker(BATCH_KAFKA_BOOTSTRAP_SERVERS='127.0.0.1:29092')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_missing_app_override_cannot_fall_back_to_shared_broker(self):
        result = self.run_validation(KAFKA_HOST_PORT='29092',
                                     KAFKA_HOST_BOOTSTRAP='localhost:29092',
                                     SIM_TEST_KAFKA_PUBLISHED_PORT='29092')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('BATCH_KAFKA_BOOTSTRAP_SERVERS', result.stderr)

    def test_sdk_override_cannot_consume_from_another_broker(self):
        result = self.isolated_broker(KAFKA_BOOTSTRAP='localhost:19092')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('KAFKA_BOOTSTRAP', result.stderr)

    def test_infrastructure_container_must_belong_to_selected_project(self):
        result = self.isolated_broker(SIM_TEST_FOREIGN_KAFKA='1')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Compose project isolated-sim', result.stderr)

    def test_selected_port_must_be_published_by_owned_kafka_container(self):
        result = self.isolated_broker(SIM_TEST_KAFKA_PUBLISHED_PORT='19092')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('isolated-kafka', result.stderr)

    def test_remote_and_multiple_broker_endpoints_are_rejected(self):
        for endpoint in ('remote.example:29092', 'localhost:29092,localhost:19092'):
            with self.subTest(endpoint=endpoint):
                result = self.isolated_broker(BATCH_KAFKA_BOOTSTRAP_SERVERS=endpoint)
                self.assertNotEqual(result.returncode, 0)

    def test_docker_apps_use_service_broker_while_sdk_uses_host_mapping(self):
        result = self.isolated_broker(BATCH_SCRIPT_RUNTIME='docker',
                                     BATCH_KAFKA_BOOTSTRAP_SERVERS='kafka:29092')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_docker_apps_cannot_use_host_loopback_broker(self):
        result = self.isolated_broker(BATCH_SCRIPT_RUNTIME='docker')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('BATCH_KAFKA_BOOTSTRAP_SERVERS', result.stderr)

    def test_sftp_override_cannot_target_another_containers_port(self):
        result = self.isolated_broker(SFTP_PORT='12222')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('SFTP', result.stderr)

    def test_host_sftp_cannot_target_a_remote_server(self):
        result = self.isolated_broker(SFTP_HOST='remote.example')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('SFTP', result.stderr)

    def test_host_worker_and_console_clients_cannot_target_shared_control_plane(self):
        for overrides, key in [({'ORCHESTRATOR_PORT': '28082', 'ORCH_BASE': 'http://localhost:28082',
                                 'BATCH_ORCHESTRATOR_BASE_URL': 'http://localhost:18082'},
                                'BATCH_ORCHESTRATOR_BASE_URL'),
                               ({'TRIGGER_PORT': '28081', 'TRIGGER_BASE': 'http://localhost:28081',
                                 'BATCH_TRIGGER_BASE_URL': 'http://localhost:18081'},
                                'BATCH_TRIGGER_BASE_URL')]:
            with self.subTest(key=key):
                result = self.isolated_broker(**overrides)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(key, result.stderr)

    def test_host_service_url_defaults_follow_custom_application_ports(self):
        result = self.isolated_broker(ORCHESTRATOR_PORT='28082', ORCH_BASE='http://localhost:28082',
                                     TRIGGER_PORT='28081', TRIGGER_BASE='http://localhost:28081')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_unstarted_sftp_is_allowed_before_prerequisite_creation(self):
        result = self.isolated_broker(SIM_TEST_SFTP_ABSENT='1')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_invalid_sftp_port_is_rejected_even_before_container_creation(self):
        for port in ('0', '65536'):
            with self.subTest(port=port):
                result = self.isolated_broker(SIM_TEST_SFTP_ABSENT='1', SFTP_HOST_PORT=port)
                self.assertNotEqual(result.returncode, 0)

    def test_sftp_inventory_failure_cannot_be_treated_as_container_absence(self):
        result = self.isolated_broker(SIM_TEST_LIST_EXIT='9')
        self.assertNotEqual(result.returncode, 0)

    def test_invalid_runtime_fails_before_target_access(self):
        result = self.isolated_broker(BATCH_SCRIPT_RUNTIME='remote')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('BATCH_SCRIPT_RUNTIME', result.stderr)

    def test_preflight_and_direct_sim_validate_before_upload_cleanup_or_restart(self):
        source = (ROOT / 'scripts/local/sim-harness.sh').read_text()
        preflight = re.search(r'(?ms)^preflight\(\) \{.*?^\}', source).group()
        self.assertLess(preflight.index('sim_validate_infrastructure_targets'),
                        preflight.index('fixture_compat_check'))
        sim = re.search(r'(?ms)^sim\(\) \{.*?^\}', source).group()
        self.assertLess(sim.index('sim_validate_infrastructure_targets'),
                        sim.index('clean_stale_env'))

    def test_direct_sim_propagates_target_rejection_before_cleanup(self):
        source = (ROOT / 'scripts/local/sim-harness.sh').read_text()
        sim = re.search(r'(?ms)^sim\(\) \{.*?^\}', source).group()
        shell = r'''
set -eu
source() { :; }
sim_validate_infrastructure_targets() { echo target-rejected >&2; return 7; }
clean_stale_env() { touch "$SIM_TEST_CLEANUP_MARKER"; }
'''
        with tempfile.TemporaryDirectory() as temporary:
            marker = Path(temporary) / 'cleanup-called'
            result = subprocess.run(['bash', '-c', shell + sim + '\nsim 20'],
                                    env=dict(os.environ, SIM_TEST_CLEANUP_MARKER=str(marker)),
                                    text=True, capture_output=True)
            self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
            self.assertIn('target-rejected', result.stderr)
            self.assertFalse(marker.exists())

    def test_quartz_fault_stages_use_selected_runtime_instead_of_shared_trigger_name(self):
        for filename in ('22-trigger-stage6c.sh', '24-trigger-stage6d.sh'):
            with self.subTest(filename=filename):
                source = (ROOT / 'scripts/sim' / filename).read_text()
                self.assertIn('sim_container_stack_active || runtime_status=$?', source)
                self.assertIn('[[ "$runtime_status" -le 1 ]] || exit "$runtime_status"', source)
                self.assertNotIn('sim_container_running batch-trigger', source)


if __name__ == "__main__":
    unittest.main()
