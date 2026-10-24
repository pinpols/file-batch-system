# 用途：Sim/容量/恢复脚本契约。
# 用法：在仓库根目录执行 python3 -m unittest scripts/ci/tests/test_sim_runtime_cleanup.py。
"""验证 Sim 初始化入口共享清理范围，避免新增阶段的运行数据残留。"""

import ast
import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]


class SimRuntimeCleanupTest(unittest.TestCase):
    def setUp(self):
        self.sql = (ROOT / "scripts/local/sql/reset-platform-runtime.sql").read_text()
        self.prefix = re.search(r"tablename ~ '([^']+)'", self.sql).group(1)
        array = re.search(r"ARRAY\[(.*?)\]", self.sql, re.S).group(1)
        self.explicit = set(re.findall(r"'([^']+)'", array))

    def selected(self, table):
        return bool(re.search(self.prefix, table)) or table in self.explicit

    def test_runtime_edit_records_are_selected_without_relying_on_foreign_keys(self):
        for table in (
            "job_instance", "job_instance_dedup_key", "job_task", "job_partition",
            "job_step_instance", "job_execution_log", "trigger_request",
            "trigger_outbox_event", "retry_schedule", "outbox_event",
            "worker_report_outbox", "console_operation_audit",
        ):
            with self.subTest(table=table):
                self.assertTrue(self.selected(table))

    def test_configuration_and_identity_are_preserved(self):
        for table in (
            "job_definition", "workflow_definition",
            "job_schedule", "job_monitoring_policy", "tenant", "console_user",
            "api_key", "file_template_config", "file_channel_config",
        ):
            with self.subTest(table=table):
                self.assertFalse(self.selected(table))

    def test_both_entrypoints_use_one_sql_inventory(self):
        reset = (ROOT / "scripts/sim/00-reset-runtime.sh").read_text()
        harness = (ROOT / "scripts/local/sim-harness.sh").read_text()
        self.assertIn('$ROOT/scripts/local/sql/reset-platform-runtime.sql', reset)
        self.assertNotIn("PLAT_TABLES=", reset)
        self.assertIn("sim_platform_sql reset-platform-runtime.sql", harness)
        self.assertFalse((ROOT / "scripts/sim/sql/reset-platform-runtime.sql").exists())

    def test_sql_retains_environment_guard_and_schema_scope(self):
        self.assertIn("current_setting('batch.destructive_ops', true)", self.sql)
        self.assertIn("IS DISTINCT FROM 'sim-reset'", self.sql)
        self.assertIn("schemaname = 'batch'", self.sql)

    def test_sim_shard_is_owned_and_removed_after_verify_even_when_verification_fails(self):
        harness = (ROOT / "scripts/local/sim-harness.sh").read_text()
        prereq = re.search(r"(?ms)^prereq\(\) \{.*?^\}", harness).group()
        verify_data = re.search(r"(?ms)^verify_data\(\) \{.*?^\}", harness).group()
        self.assertIn(
            "BATCH_SIM_RESIDUE_CONTAINER=true \\\n    bash scripts/local/provision-biz-shard.sh",
            prereq,
        )
        self.assertIn("cleanup_sim_owned_biz_shard", verify_data)
        self.assertIn("cleanup_sim_shard_secrets", verify_data)
        self.assertGreaterEqual(prereq.count("cleanup_sim_owned_biz_shard"), 4)

        cleanup = re.search(r"(?ms)^cleanup_sim_owned_biz_shard\(\) \{.*?^\}", harness)
        self.assertIsNotNone(cleanup)
        shell = cleanup.group() + r'''
docker() {
  case "$1 $2 $3" in
    "ps -aq --filter") printf '%s\n' "${SIM_TEST_SHARD_ID:-}" ;;
    "rm -fv test-shard-id") printf 'removed:%s\n' "$3" >&2 ;;
    *) printf 'unexpected docker command: %s\n' "$*" >&2; return 9 ;;
  esac
}
batch_require_local_docker_context() { return 0; }
c_ylw() { printf '%s\n' "$*"; }
BIZ_SHARD_1_CONTAINER=sim-shard
SIM_TEST_SHARD_ID="${SIM_TEST_SHARD_ID-test-shard-id}"
cleanup_sim_owned_biz_shard
'''
        result = subprocess.run(["bash", "-c", shell], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("已清理 SIM 自有 biz-shard", result.stdout)
        self.assertIn("removed:test-shard-id", result.stderr)
        result = subprocess.run(
            ["bash", "-c", shell],
            env={**os.environ, "SIM_TEST_SHARD_ID": ""},
            capture_output=True,
            text=True,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("removed:", result.stderr)
        self.assertNotIn("已清理 SIM 自有 biz-shard", result.stdout)

        verify_shell = verify_data + r'''
bash() { return "${SIM_TEST_VERIFY_EXIT:-0}"; }
cleanup_sim_owned_biz_shard() {
  printf 'SHARD_CLEANUP\n'
  return "${SIM_TEST_CLEANUP_EXIT:-0}"
}
cleanup_sim_shard_secrets() { printf 'SECRET_CLEANUP\n'; }
verify_data
'''
        for verify_exit, cleanup_exit, expected_exit in ((7, 0, 7), (0, 8, 8)):
            with self.subTest(verify_exit=verify_exit, cleanup_exit=cleanup_exit):
                result = subprocess.run(
                    ["bash", "-c", verify_shell],
                    env={
                        **os.environ,
                        "SIM_TEST_VERIFY_EXIT": str(verify_exit),
                        "SIM_TEST_CLEANUP_EXIT": str(cleanup_exit),
                    },
                    capture_output=True,
                    text=True,
                )
                self.assertEqual(expected_exit, result.returncode, result.stderr)
                self.assertIn("SHARD_CLEANUP", result.stdout)
                self.assertIn("SECRET_CLEANUP", result.stdout)

    def test_aggregate_all_registers_shard_cleanup_on_every_exit_path(self):
        harness = (ROOT / "scripts/local/sim-harness.sh").read_text()
        aggregate = re.search(r"(?ms)^all\(\) \{.*?^\}", harness)
        self.assertIsNotNone(aggregate)
        self.assertIn("trap all_sim_teardown EXIT", aggregate.group())
        teardown = re.search(r"(?ms)^all_sim_teardown\(\) \{.*?^\}", harness).group()
        self.assertIn("cleanup_sim_owned_biz_shard", teardown)
        self.assertIn("cleanup_sim_shard_secrets", teardown)

    def test_stale_shard_cleanup_is_scoped_to_compose_project_and_removes_anonymous_data(self):
        cleanup = (ROOT / "scripts/local/clean-stale-test-env.sh").read_text()
        self.assertIn("io.github.pinpols.batch.sim.project=${shard_project}", cleanup)
        self.assertIn('docker rm -fv "$cid"', cleanup)
        self.assertNotIn("--filter 'name=^/batch-postgres-biz-shard-'", cleanup)
        provision = (ROOT / "scripts/local/provision-biz-shard.sh").read_text()
        self.assertIn("io.github.pinpols.batch.sim.project=${COMPOSE_PROJECT_NAME:-batch-platform}", provision)
        self.assertIn("拒绝把未知容器当作临时分片复用", provision)

    def test_routing_teardown_reports_restore_and_shard_cleanup_failures(self):
        harness = (ROOT / "scripts/local/sim-harness.sh").read_text()
        teardown = re.search(r"(?ms)^routing_sim_teardown\(\) \{.*?^\}", harness).group()
        shell = teardown + r'''
routing_overlay_clear() { return "${SIM_TEST_RESTORE_EXIT:-0}"; }
cleanup_sim_owned_biz_shard() { return "${SIM_TEST_CLEANUP_EXIT:-0}"; }
cleanup_sim_shard_secrets() { return 0; }
prior_status() { return "${SIM_TEST_RUN_EXIT:-0}"; }
prior_status
routing_sim_teardown
'''
        for run_exit, restore_exit, cleanup_exit, expected_exit in (
            (7, 0, 0, 7),
            (0, 8, 0, 8),
            (7, 8, 0, 7),
            (0, 0, 9, 9),
        ):
            with self.subTest(run_exit=run_exit, restore_exit=restore_exit, cleanup_exit=cleanup_exit):
                result = subprocess.run(
                    ["bash", "-c", shell],
                    env={
                        **os.environ,
                        "SIM_TEST_RUN_EXIT": str(run_exit),
                        "SIM_TEST_RESTORE_EXIT": str(restore_exit),
                        "SIM_TEST_CLEANUP_EXIT": str(cleanup_exit),
                    },
                    capture_output=True,
                    text=True,
                )
                self.assertEqual(expected_exit, result.returncode, result.stderr)

    def test_sim_exit_cleans_stage_owned_minio_inputs_after_worker_profile_restore(self):
        harness = (ROOT / "scripts/local/sim-harness.sh").read_text()
        trap_start = harness.index("    restore_sim_worker_profiles() {")
        trap_end = harness.index("    trap restore_sim_worker_profiles EXIT", trap_start)
        cleanup = harness[trap_start:trap_end]

        self.assertIn("clean_sim_minio_inputs", cleanup)
        self.assertLess(cleanup.index("restart_import default"), cleanup.index("clean_sim_minio_inputs"))
        self.assertIn("cleanup_failed", cleanup)

    def test_manual_input_cleanup_refuses_while_any_application_port_is_live(self):
        harness = (ROOT / "scripts/local/sim-harness.sh").read_text()
        cleanup = re.search(r"(?ms)^cleanup_sim_inputs\(\) \{.*?^\}", harness).group()
        shell = """set -euo pipefail
""" + cleanup + """
process_port_is_listening() { [[ "${LISTENING_PORT:-}" == "$1" ]]; }
c_red() { printf 'BLOCKED:%s\\n' "$*"; }
clean_sim_sftp_inputs() { echo SFTP_CLEANED; }
clean_sim_minio_inputs() { echo "MINIO_CLEANED:$1"; }
CONSOLE_API_PORT=18080 TRIGGER_PORT=18081 ORCHESTRATOR_PORT=18082
WORKER_IMPORT_PORT=18083 WORKER_EXPORT_PORT=18084 WORKER_DISPATCH_PORT=18085
WORKER_PROCESS_PORT=18086 WORKER_ATOMIC_PORT=18087
LISTENING_PORT=18083 cleanup_sim_inputs
"""
        result = subprocess.run(["bash", "-c", shell], capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("BLOCKED:  ✗ :18083 仍有应用监听", result.stdout)
        self.assertNotIn("SFTP_CLEANED", result.stdout)
        self.assertNotIn("MINIO_CLEANED", result.stdout)

    def test_manual_input_cleanup_cleans_sftp_and_minio_after_all_ports_are_idle(self):
        harness = (ROOT / "scripts/local/sim-harness.sh").read_text()
        cleanup = re.search(r"(?ms)^cleanup_sim_inputs\(\) \{.*?^\}", harness).group()
        shell = """set -euo pipefail
""" + cleanup + """
process_port_is_listening() { return 1; }
c_red() { printf 'BLOCKED:%s\\n' "$*"; }
clean_sim_sftp_inputs() { echo SFTP_CLEANED; }
clean_sim_minio_inputs() { echo "MINIO_CLEANED:$1"; }
CONSOLE_API_PORT=18080 TRIGGER_PORT=18081 ORCHESTRATOR_PORT=18082
WORKER_IMPORT_PORT=18083 WORKER_EXPORT_PORT=18084 WORKER_DISPATCH_PORT=18085
WORKER_PROCESS_PORT=18086 WORKER_ATOMIC_PORT=18087
cleanup_sim_inputs
"""
        result = subprocess.run(["bash", "-c", shell], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("SFTP_CLEANED", result.stdout)
        self.assertIn("MINIO_CLEANED:post-run", result.stdout)

    def test_cleanup_inputs_cli_routes_to_guarded_cleanup(self):
        harness = (ROOT / "scripts/local/sim-harness.sh").read_text()
        self.assertIn("cleanup-inputs) cleanup_sim_inputs ;;", harness)

    def test_successful_stage_25_and_26_cleanup_precedes_default_worker_restart(self):
        harness = (ROOT / "scripts/local/sim-harness.sh").read_text()
        stage_loop_start = harness.index("      if run_sim_stage \"$s\"")
        profile_restore_start = harness.index('      case "$n" in\n        23-*|25-*|26-*)', stage_loop_start)
        completed_stage = harness[stage_loop_start:profile_restore_start]

        self.assertIn('[[ "$n" == 25-* || "$n" == 26-* ]]', completed_stage)
        self.assertIn('clean_sim_minio_inputs "post-stage-${n%%-*}"', completed_stage)
        self.assertLess(
            completed_stage.index('clean_sim_minio_inputs "post-stage-${n%%-*}"'),
            completed_stage.index('echo "PASS $n"'),
        )
        self.assertIn('sim_failed=1', completed_stage)
        self.assertIn('FAIL $n (SIM MinIO input cleanup failed)', completed_stage)

    def test_minio_cleanup_pattern_matches_only_owned_stage_25_and_26_inputs(self):
        harness = (ROOT / "scripts/local/sim-harness.sh").read_text()
        match = re.search(r"local generated_name_regex='([^']+)'", harness)
        self.assertIsNotNone(match)
        pattern = re.compile(match.group(1))

        for owned in (
            "bundle-a-20261017-15302514.csv",
            "bundle-b-20261017-15302514.csv",
            "bundle-import-42.batch.json",
            "stage2e-checkpoint/sim-stage2e-ckpt-15057843.xml",
        ):
            with self.subTest(owned=owned):
                self.assertRegex(owned, pattern)

        for unrelated in (
            "customer.csv",
            "bundle-a-production.csv",
            "stage2e-checkpoint/production.xml",
            "bundle-import-42.json",
        ):
            with self.subTest(unrelated=unrelated):
                self.assertNotRegex(unrelated, pattern)

    def test_minio_cleanup_removes_only_owned_inputs_and_verifies_empty(self):
        self.assert_sim_minio_cleanup(
            fail_delete=False,
            expected_return_code=0,
            expected_remaining=["customer.csv"],
        )

    def test_minio_cleanup_failure_is_nonzero_and_preserves_inventory(self):
        self.assert_sim_minio_cleanup(
            fail_delete=True,
            expected_return_code=1,
            expected_remaining=[
                "bundle-a-20261017-15302514.csv",
                "bundle-import-42.batch.json",
                "customer.csv",
                "stage2e-checkpoint/sim-stage2e-ckpt-15057843.xml",
            ],
        )

    def test_minio_missing_prefix_is_empty_but_other_find_errors_fail_closed(self):
        harness = (ROOT / "scripts/local/sim-harness.sh").read_text()
        find_start = harness.index("sim_minio_find_inputs() {")
        find_end = harness.index("\n}", find_start) + 2
        find_function = harness[find_start:find_end]

        with tempfile.TemporaryDirectory(prefix="sim-minio-find-test-") as temp_dir:
            root = Path(temp_dir)
            helper = root / "scripts/lib/minio-mc.sh"
            helper.parent.mkdir(parents=True)
            helper.write_text(
                "#!/usr/bin/env bash\n"
                "printf '%s\\n' \"$SIM_MINIO_FIND_ERROR\" >&2\n"
                "exit 1\n",
                encoding="utf-8",
            )

            shell = "set -euo pipefail\n" + find_function + "\nsim_minio_find_inputs target regex\n"
            missing_prefix = subprocess.run(
                ["bash", "-c", shell],
                cwd=root,
                env={
                    **os.environ,
                    "SIM_MINIO_FIND_ERROR": (
                        "mc: <ERROR> Unable to stat `local/batch-dev/ingress/ta`. "
                        "Object does not exist."
                    ),
                },
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(0, missing_prefix.returncode, missing_prefix.stderr)
            self.assertEqual("", missing_prefix.stdout)

            access_denied = subprocess.run(
                ["bash", "-c", shell],
                cwd=root,
                env={**os.environ, "SIM_MINIO_FIND_ERROR": "mc: <ERROR> Access Denied."},
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(1, access_denied.returncode)
            self.assertIn("Access Denied", access_denied.stderr)

    def assert_sim_minio_cleanup(self, *, fail_delete, expected_return_code, expected_remaining):
        harness = (ROOT / "scripts/local/sim-harness.sh").read_text()
        find_start = harness.index("sim_minio_find_inputs() {")
        find_end = harness.index("\n}", find_start) + 2
        cleanup_start = harness.index("clean_sim_minio_inputs() {")
        cleanup_end = harness.index("\n}", cleanup_start) + 2
        cleanup_functions = harness[find_start:find_end] + "\n" + harness[cleanup_start:cleanup_end]

        with tempfile.TemporaryDirectory(prefix="sim-minio-cleanup-test-") as temp_dir:
            root = Path(temp_dir)
            helper = root / "scripts/lib/minio-mc.sh"
            helper.parent.mkdir(parents=True)
            helper.write_text(
                """#!/usr/bin/env bash
python3 - "$@" <<'PY'
import os
import re
import sys
from pathlib import Path

args = sys.argv[1:]
target = args[1]
pattern = re.compile(args[args.index("--regex") + 1])
state = Path(os.environ["SIM_TEST_OBJECT_STATE"])
objects = [line for line in state.read_text().splitlines() if line]
matching = [name for name in objects if pattern.fullmatch(name)]
if "--exec" in args:
    if os.environ.get("SIM_TEST_FAIL_DELETE") == "1":
        print("simulated delete failure", file=sys.stderr)
        raise SystemExit(1)
    remaining = [name for name in objects if not pattern.fullmatch(name)]
    state.write_text("".join(name + "\\n" for name in remaining))
    for name in matching:
        print(f"removed {target}/{name}")
else:
    for name in matching:
        print(f"{target}/{name}")
PY
""",
                encoding="utf-8",
            )

            names = [
                "bundle-a-20261017-15302514.csv",
                "bundle-import-42.batch.json",
                "customer.csv",
                "stage2e-checkpoint/sim-stage2e-ckpt-15057843.xml",
            ]
            state = root / "objects.txt"
            state.write_text("".join(name + "\n" for name in names), encoding="utf-8")
            log_dir = root / "sim-logs"
            log_dir.mkdir()
            shell = """
set -euo pipefail
c_red() { printf 'ERROR %s\\n' "$*" >&2; }
ok() { printf 'OK %s\\n' "$*"; }
""" + cleanup_functions + "\nclean_sim_minio_inputs test-run\n"
            result = subprocess.run(
                ["bash", "-c", shell],
                cwd=root,
                env={
                    **os.environ,
                    "MINIO_BUCKET": "batch-dev",
                    "SIM_LOG_DIR": str(log_dir),
                    "SIM_TEST_OBJECT_STATE": str(state),
                    "SIM_TEST_FAIL_DELETE": "1" if fail_delete else "0",
                },
                capture_output=True,
                text=True,
                check=False,
            )

            self.assertEqual(expected_return_code, result.returncode, result.stderr)
            self.assertEqual(expected_remaining, state.read_text(encoding="utf-8").splitlines())
            if not fail_delete:
                self.assertIn("移除 3 个", result.stdout)

    def test_runtime_edit_handles_signals_before_mutation(self):
        script = (ROOT / "scripts/sim/30-tenant-config-runtime-edit.sh").read_text()
        source = script.split("<<'PY'", 1)[1].split("\n", 1)[1].rsplit("\nPY", 1)[0]
        tree = ast.parse(source)
        handler = next(node for node in tree.body if isinstance(node, ast.FunctionDef)
                       and node.name == "interrupt_run")
        namespace = {}
        exec(compile(ast.Module(body=[handler], type_ignores=[]), "signal-handler", "exec"), namespace)
        with self.assertRaises(SystemExit) as raised:
            namespace["interrupt_run"](15, None)
        self.assertEqual(143, raised.exception.code)
        self.assertLess(source.index("signal.signal(signal.SIGTERM"),
                        source.index('update_definition(job_id, sleep_params'))
        finally_block = source.index("finally:")
        restore_call = source.index("update_definition(", finally_block)
        self.assertLess(source.index("signal.signal(signal.SIGTERM, signal.SIG_IGN)", finally_block),
                        restore_call)


if __name__ == "__main__":
    unittest.main()
