# 用途：验证SIM 专属故障渠道的安全收尾。
# 用法：在仓库根目录执行 python3 -m unittest scripts/ci/tests/test_sim_fault_channel_cleanup.py。
"""验证 SIM 专属故障渠道在新库与已初始化库中的收尾行为。"""

import os
import subprocess
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]


class SimFaultChannelCleanupTest(unittest.TestCase):
    def test_mockserver_port_default_is_centralized_and_override_is_used(self):
        endpoint_command = "source scripts/sim/env-common.sh; sim_mockserver_base_url"
        test_env = os.environ.copy()
        test_env.update(
            {
                "BATCH_SCRIPT_RUNTIME": "host",
                "REPORT_DIR": "/tmp",
                "SIM_STAGE_NAME": "sim-port-default-test",
            }
        )
        test_env.pop("MOCKSERVER_HOST_PORT", None)

        default_result = subprocess.run(
            ["bash", "-c", endpoint_command],
            cwd=ROOT,
            env=test_env,
            check=True,
            capture_output=True,
            text=True,
        )
        self.assertEqual("http://localhost:11080", default_result.stdout.strip())

        runtime_defaults = (ROOT / "scripts/lib/runtime-defaults.sh").read_text()
        env_common = (ROOT / "scripts/lib/env-common.sh").read_text()
        self.assertIn("BATCH_DEFAULT_MOCKSERVER_HOST_PORT=11080", runtime_defaults)
        self.assertIn(
            'export MOCKSERVER_HOST_PORT="${MOCKSERVER_HOST_PORT:-$BATCH_DEFAULT_MOCKSERVER_HOST_PORT}"',
            env_common,
        )

    def test_mockserver_endpoint_uses_host_override_or_compose_service_name(self):
        endpoint_command = "source scripts/sim/env-common.sh; sim_mockserver_base_url"
        test_env = os.environ.copy()
        test_env.update(
            {
                "BATCH_SCRIPT_RUNTIME": "host",
                "MOCKSERVER_HOST_PORT": "11999",
                "REPORT_DIR": "/tmp",
                "SIM_STAGE_NAME": "sim-port-override-test",
            }
        )

        host_result = subprocess.run(
            ["bash", "-c", endpoint_command],
            cwd=ROOT,
            env=test_env,
            check=True,
            capture_output=True,
            text=True,
        )
        self.assertEqual("http://localhost:11999", host_result.stdout.strip())

        test_env["BATCH_SCRIPT_RUNTIME"] = "docker"
        compose_result = subprocess.run(
            ["bash", "-c", endpoint_command],
            cwd=ROOT,
            env=test_env,
            check=True,
            capture_output=True,
            text=True,
        )
        self.assertEqual("http://mockserver:1080", compose_result.stdout.strip())

    def test_cleanup_is_safe_before_schema_creation_and_scoped_to_owned_fixture(self):
        sql = (ROOT / "scripts/local/sql/disable-stage5b-fault-channel.sql").read_text()

        self.assertIn("to_regclass('batch.file_channel_config') IS NOT NULL", sql)
        for predicate in (
            "tenant_id = 'tb'",
            "channel_code = 'tb_api_fail'",
            "channel_name = 'TB API fail channel [SIM:dispatch-stage5b]'",
            "channel_type = 'API'",
        ):
            with self.subTest(predicate=predicate):
                self.assertIn(predicate, sql)

    def test_harness_and_stage_fail_if_cleanup_does_not_complete(self):
        harness = (ROOT / "scripts/local/sim-harness.sh").read_text()
        stage = (ROOT / "scripts/sim/14-dispatch-stage5b.sh").read_text()
        fixture = (ROOT / "test-data/sim/sim-stage5b-dispatch-fixtures.sql").read_text()
        cleanup_sql = (ROOT / "scripts/local/sql/disable-stage5b-fault-channel.sql").read_text()

        self.assertIn("sim_platform_sql disable-stage5b-fault-channel.sql", harness)
        self.assertIn('|| { c_red "  ✗ SIM 故障配置恢复未完成"; return 1; }', harness)
        self.assertIn("cleanup_stage5b_fault_channel", stage)
        self.assertIn('MOCKSERVER_BASE_URL="$(sim_mockserver_base_url)"', stage)
        self.assertIn('STAGE5B_FAULT_PATH="/tb/fail"', stage)
        self.assertIn(
            'STAGE5B_FAULT_ENDPOINT="${MOCKSERVER_BASE_URL%/}${STAGE5B_FAULT_PATH}"',
            stage,
        )
        self.assertIn('-v "target_endpoint=$STAGE5B_FAULT_ENDPOINT"', stage)
        self.assertIn("target_endpoint', :'target_endpoint'", fixture)
        self.assertNotIn("localhost:11080", fixture)
        self.assertNotIn("11080", cleanup_sql)


if __name__ == "__main__":
    unittest.main()
