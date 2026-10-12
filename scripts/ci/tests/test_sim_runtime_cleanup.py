"""验证 Sim 初始化入口共享清理范围，避免新增阶段的运行数据残留。"""

import ast
import re
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
            "job_definition", "pipeline_definition", "workflow_definition",
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
