# 用途：验证 SIM 导出分片产物的完整性检查。
# 用法：python3 -m unittest scripts/ci/tests/test_sim_export_partition_evidence.py
import contextlib
import io
import json
import os
import subprocess
import unittest
from pathlib import Path
from unittest import mock

from scripts.sim.export_partition_evidence import verify_partition_contents


def document(*numbers):
    return json.dumps({"details": [
        {"tenant_id": "ta", "customer_no": f"EXP3B-{number:04d}"} for number in numbers
    ]})


class SimExportPartitionEvidenceTest(unittest.TestCase):
    def test_accepts_exact_coverage_across_partitions(self):
        contents = {"one": document(1, 3), "two": document(2, 4)}
        verify_partition_contents(["one", "two"], 2, 4, contents.__getitem__)

    def test_rejects_duplicate_with_equal_total_and_missing_row(self):
        contents = {"one": document(1, 2), "two": document(2, 4)}
        with self.assertRaisesRegex(ValueError, "duplicate"):
            verify_partition_contents(["one", "two"], 2, 4, contents.__getitem__)

    def test_rejects_missing_row_and_incorrect_tenant(self):
        contents = {"one": document(1, 2), "two": document(3)}
        with self.assertRaisesRegex(ValueError, "missing"):
            verify_partition_contents(["one", "two"], 2, 4, contents.__getitem__)
        wrong_tenant = '{"details":[{"tenant_id":"tb","customer_no":"EXP3B-0001"}]}'
        with self.assertRaisesRegex(ValueError, "tenant"):
            verify_partition_contents(["one"], 1, 1, lambda _: wrong_tenant)

    def test_rejects_duplicate_object_or_invalid_document(self):
        with self.assertRaisesRegex(ValueError, "objects"):
            verify_partition_contents(["one", "one"], 2, 4, lambda _: document(1))
        with self.assertRaisesRegex(ValueError, "details"):
            verify_partition_contents(["one"], 1, 1, lambda _: '{"details":null}')


class SimExportScenarioWiringTest(unittest.TestCase):
    def run_scenario(self, script, partitions, rows, duplicate=False):
        root = Path(__file__).resolve().parents[3]
        script_text = (root / "scripts/sim" / script).read_text()
        python_code = script_text.split("<<'PY'", 1)[1].split("\n", 1)[1].split("\nPY", 1)[0]
        sql_dir = root / "scripts/sim/sql"
        names = [f"path-{number}" for number in range(partitions)]
        records = [{"bucket": "bucket", "path": name} for name in names]
        values = list(range(1, rows + 1))
        if duplicate:
            values[-1] = values[0]
        contents = {
            f"local/bucket/{name}": document(*values[index::partitions])
            for index, name in enumerate(names)
        }
        sql_results = {
            "select-request-instance-status.sql": "1|SUCCESS",
            "select-export-stage3b-assertion.sql": "4|4|4|40",
            "count-export-stage3c-terminal.sql": "3",
            "select-export-stage3c-instance.sql": "1",
            "select-export-stage3c-dedup-check.sql": "1|1",
            "select-export-stage3c-shard-check.sql": "8|8|8|80",
            "count-export-stage3c-tenant-files.sql": "3",
            "count-export-stage3c-success.sql": "3",
            "select-export-partition-objects.sql": json.dumps(records),
        }
        sql_by_text = {
            (sql_dir / name).read_text(): value for name, value in sql_results.items()
        }

        def run_psql(args, **kwargs):
            return subprocess.CompletedProcess(args, 0, stdout=sql_by_text.get(kwargs["input"], ""))

        class Response:
            status = 200

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

            def read(self):
                return b'{"code":"SUCCESS"}'

        env = {
            "TRIGGER_BASE": "http://example.invalid",
            "INTERNAL_SECRET": "fixture",
            "BIZ_DATE": "2026-10-30",
            "BATCH_NO": "batch-fixture",
            "START_TS": "2026-10-30T10:00:00Z",
            "PG_CONTAINER": "pg-fixture",
            "POSTGRES_USER": "fixture",
            "PLATFORM_DB": "fixture",
            "SIM_SQL_DIR": str(sql_dir),
            "MINIO_MC_ALIAS": "local",
        }
        with (
            mock.patch.dict(os.environ, env),
            mock.patch("subprocess.run", side_effect=run_psql),
            mock.patch("subprocess.check_output", side_effect=lambda args, **_: contents[args[-1]]),
            mock.patch("urllib.request.urlopen", return_value=Response()),
            mock.patch("scripts.sim.launch_contract.workflow_id_for_fixture", return_value=1),
            contextlib.redirect_stdout(io.StringIO()),
        ):
            exec(compile(python_code, script, "exec"), {})

    def test_both_scenarios_read_actual_partition_contents(self):
        self.run_scenario("12-export-stage3b.sh", 4, 40)
        self.run_scenario("18-export-stage3c.sh", 8, 80)

    def test_equal_count_duplicate_fails_both_scenarios(self):
        for script, partitions, rows in (
            ("12-export-stage3b.sh", 4, 40),
            ("18-export-stage3c.sh", 8, 80),
        ):
            with self.subTest(script=script), self.assertRaisesRegex(ValueError, "duplicate"):
                self.run_scenario(script, partitions, rows, duplicate=True)


if __name__ == "__main__":
    unittest.main()
