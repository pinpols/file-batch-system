# 用途：验证根触发调用方的请求目标与传输行为。
# 用法：在仓库根目录执行 python3 -m unittest scripts/ci/tests/test_root_caller_transport.py。
"""使用传输替身执行当前根调用方，不连接真实服务或数据库。"""

import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
INTERNAL_SECRET = "root-caller-test-secret"
TENANT = "root-caller-test-tenant"
ORIGIN = "http://trigger.example.invalid:49237"

# 每次传输调用独立启动进程，确保 Shell 命令替换中的调用可被观测。
TRANSPORT_STUB = r'''import json, os, sys
from pathlib import Path
kind = Path(sys.argv[0]).name
args = sys.argv[1:]
capture = Path(os.environ['TRANSPORT_CAPTURE'])
sql = ''
if kind in ('psql', 'docker'):
    if '-f' in args:
        source = args[args.index('-f') + 1]
        sql = sys.stdin.read() if source == '/dev/stdin' else Path(source).read_text()
    else:
        raise SystemExit('unexpected database invocation')
with capture.open('a') as log:
    log.write(json.dumps({'kind': kind, 'args': args, 'sql': sql}) + '\n')
if kind == 'curl':
    url = next(value for value in args if value.startswith('http'))
    headers = [args[index + 1] for index, value in enumerate(args) if value == '-H']
    if url.endswith('/api/triggers/launch'):
        Path(os.environ['LAUNCHED_MARKER']).touch()
        authenticated = 'X-Internal-Secret: root-caller-test-secret' in headers
        failed = os.environ.get('REJECT_LAUNCH') == '1' or not authenticated
        if failed and any(value in ('-f', '--fail') or value.startswith('-f') for value in args):
            print('injected HTTP failure', file=sys.stderr)
            raise SystemExit(22)
        if '-w' in args:
            print('503' if failed else '200', end='')
        else:
            print('{"code":"ERROR"}' if failed else '{"code":"SUCCESS"}')
    elif url.endswith('/actuator/health'):
        print('{"status":"UP"}')
    elif url.endswith('/actuator/prometheus'):
        launched = Path(os.environ['LAUNCHED_MARKER']).exists()
        print('batch_task_batch_claim_size_count ' + ('2' if launched else '1'))
        print('batch_task_batch_claim_size_sum ' + ('8' if launched else '4'))
        print('process_compute_count 1')
    else:
        raise SystemExit('unexpected HTTP target')
elif kind in ('psql', 'docker'):
    if 'SELECT workflow.id' in sql or 'workflow_code = ' in sql:
        print('47')
    elif 'node_run.job_instance_id' in sql and 'request.request_id' in sql:
        print('601')
    elif 'after_id' in sql:
        # A concurrent launch of the same Job must never become our control target.
        print('999')
    elif 'workflow_run' in sql and 'request_id' in sql and 'INSERT' not in sql:
        print('701|SUCCESS')
    elif 'FROM batch.job_partition' in sql:
        print('0' if 'NOT IN' in sql else '4')
    elif 'SELECT 1' in sql:
        print('1')
    elif 'INSERT' in sql or 'CREATE' in sql or 'process_demo_target' in sql:
        pass
    else:
        raise SystemExit('unexpected SQL in transport stub')
elif kind != 'sleep':
    raise SystemExit('unexpected transport executable')
'''


class RootCallerTransportTest(unittest.TestCase):
    @staticmethod
    def extract_function(path, name):
        source = path.read_text()
        pattern = rf"^{re.escape(name)}\(\)\s*\{{[^\n]*\n.*?^\}}\s*$"
        match = re.search(pattern, source, re.MULTILINE | re.DOTALL)
        if match is None:
            raise AssertionError(f"function {name} missing from {path}")
        metadata = {
            "path": str(path.relative_to(ROOT)),
            "sha256": hashlib.sha256(source.encode()).hexdigest(),
            "function": name,
            "start_line": source.count("\n", 0, match.start()) + 1,
            "end_line": source.count("\n", 0, match.end()) + 1,
        }
        return match.group(), metadata

    def execute(self, relative_path, *, functions=(), suffix="", reject=False):
        source_path = ROOT / relative_path
        source_bytes = source_path.read_bytes()
        metadata = [{"path": relative_path, "sha256": hashlib.sha256(source_bytes).hexdigest(),
                     "mode": "whole script" if not functions else "verbatim functions"}]
        with tempfile.TemporaryDirectory(prefix="root-caller-transport-") as directory:
            temporary = Path(directory)
            fake_bin = temporary / "bin"
            fake_bin.mkdir()
            for name in ("curl", "psql", "docker", "sleep"):
                executable = fake_bin / name
                executable.write_text(f"#!{sys.executable}\n" + TRANSPORT_STUB)
                executable.chmod(0o755)
            capture = temporary / "transport.jsonl"
            env = os.environ.copy()
            env.update({
                "PATH": f"{fake_bin}{os.pathsep}{env['PATH']}",
                "BATCH_ENV_COMMON_HELPERS_ONLY": "1", "BATCH_ENV_LOADED": "1",
                "TRANSPORT_CAPTURE": str(capture), "LAUNCHED_MARKER": str(temporary / "launched"),
                "REJECT_LAUNCH": "1" if reject else "0", "BATCH_INTERNAL_SECRET": INTERNAL_SECRET,
                "INTERNAL_SECRET": INTERNAL_SECRET, "SECRET": INTERNAL_SECRET,
                "PYTHON_BIN": sys.executable, "SDK_E2E_PYTHON_BIN": sys.executable,
                "ROOT": str(ROOT), "SDK_E2E_ROOT": str(ROOT),
                "SDK_E2E_SQL_DIR": str(ROOT / "scripts/lib/sql"),
                "TENANT": TENANT, "TENANT_ID": TENANT, "DEMO_TENANT_ID": TENANT,
                "BATCH_DEFAULT_TENANT_ID": TENANT,
                "TRIGGER_BASE_URL": ORIGIN, "TRG": ORIGIN,
                "TRIGGER_PORT": "49237", "ORCH_PORT": "49238",
                "ORCHESTRATOR_BASE_URL": "http://orchestrator.example.invalid:49238",
                "TRIGGER_URL": ORIGIN + "/api/triggers/launch" if relative_path.startswith("scripts/dev/") else ORIGIN,
                "WORKER_PROCESS_PORT": "49239", "PGHOST": "postgres.example.invalid", "PGPORT": "49240",
                "PGUSER": "test-user", "POSTGRES_USER": "test-user", "PG_CONTAINER": "test-postgres",
                "PLATFORM_DB": "test-platform", "BUSINESS_DB": "test-business", "PGDATABASE": "test-platform",
                "PG_DB": "test-platform", "BIZ_DATE": "2026-10-23", "BDC": "20261023",
                "SDK_E2E_JOB_CODE": "probe-job", "JOB_CODE": "probe-job", "LANG_ID": "java",
                "SDK_E2E_INSTANCE_WAIT_SECONDS": "2", "EXPECT_MIN_PARTITIONS": "4", "AWAIT_TIMEOUT": "2",
                "PROBE_TAG": "root-caller-probe", "SIM4DAY_RUN_ID": "root-caller-probe",
                "SQL_DIR": str(ROOT / "scripts/sim-4day/sql"),
            })
            command = ["bash", str(source_path)]
            if functions:
                chunks = ["set -euo pipefail"]
                for function_path, name in functions:
                    content, function_metadata = self.extract_function(ROOT / function_path, name)
                    chunks.append(content)
                    metadata.append(function_metadata)
                chunks.append(suffix)
                harness = temporary / "probe.sh"
                harness.write_text("\n\n".join(chunks) + "\n")
                command = ["bash", str(harness)]
            result = subprocess.run(command, env=env, capture_output=True, text=True,
                                    errors="replace", timeout=12)
            records = [json.loads(line) for line in capture.read_text().splitlines()] if capture.exists() else []
            evidence_dir = os.environ.get("ROOT_CALLER_EVIDENCE_DIR")
            if evidence_dir:
                evidence_path = Path(evidence_dir)
                evidence_path.mkdir(parents=True, exist_ok=True)
                evidence = {"scope": "transport stubs only; no real HTTP, PostgreSQL or Worker",
                            "source": metadata, "exit_code": result.returncode,
                            "stdout": result.stdout, "stderr": result.stderr, "transport": records}
                evidence_suffix = f"-{functions[-1][1]}" if functions else ""
                (evidence_path / f"{self.id().split('.')[-1]}{evidence_suffix}.json").write_text(json.dumps(evidence, indent=2) + "\n")
            return result, records

    def assert_root_request(self, records):
        requests = [item for item in records if item["kind"] == "curl"
                    and any(value.endswith("/api/triggers/launch") for value in item["args"])]
        self.assertEqual(1, len(requests))
        args = requests[0]["args"]
        url = next(value for value in args if value.startswith("http"))
        self.assertIn(url, (f"{ORIGIN}/api/triggers/launch",
                            "http://localhost:49237/api/triggers/launch"))
        body_index = args.index("-d") + 1
        body = json.loads(args[body_index])
        self.assertEqual(TENANT, body["tenantId"])
        self.assertEqual(47, body["workflowDefinitionId"])
        self.assertNotIn("jobCode", body)
        self.assertIn(f"X-Internal-Secret: {INTERNAL_SECRET}", args)
        keys = [value.removeprefix("Idempotency-Key: ") for value in args if value.startswith("Idempotency-Key: ")]
        self.assertEqual(1, len(keys))
        self.assertTrue(keys[0])
        request_headers = [value.removeprefix("X-Request-Id: ") for value in args
                           if value.startswith("X-Request-Id: ")]
        if request_headers:
            self.assertEqual([keys[0]], request_headers)
        if "requestId" in body:
            self.assertEqual(keys[0], body["requestId"])
        return keys[0], body

    def test_demo_actual_entry_resolves_workflow_and_authenticates(self):
        result, records = self.execute("scripts/dev/trigger-process-demo.sh")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        _, body = self.assert_root_request(records)
        self.assertEqual("MANUAL", body["triggerType"])

    def test_demo_http_failure_stops_before_result_query(self):
        result, records = self.execute("scripts/dev/trigger-process-demo.sh", reject=True)
        self.assertNotEqual(0, result.returncode)
        self.assert_root_request(records)
        result_queries = [item for item in records if any(value.endswith("trigger-process-demo-result.sql") for value in item["args"])]
        self.assertEqual([], result_queries)

    def test_batch_load_actual_entry_preserves_request_result_identity(self):
        result, records = self.execute("scripts/local/batch-consume-load.sh")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        request_id, body = self.assert_root_request(records)
        self.assertEqual("2026-10-23", body["bizDate"])
        identity_queries = [item for item in records if "node_run.job_instance_id" in item["sql"]]
        self.assertEqual(1, len(identity_queries))
        self.assertIn(f"request_id={request_id}", identity_queries[0]["args"])

    def run_sdk_control(self, reject=False):
        path = "scripts/local/sdk-control-e2e.sh"
        functions = (("scripts/lib/sdk-e2e-common.sh", "sdk_e2e_q_file"), (path, "sdk_control_launch"))
        return self.execute(path, functions=functions, suffix="sdk_control_launch 0", reject=reject)

    def test_sdk_control_targets_only_its_original_request(self):
        result, records = self.run_sdk_control()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        request_id, _ = self.assert_root_request(records)
        self.assertEqual("601", result.stdout)
        queries = [item for item in records if item["kind"] == "psql"]
        self.assertEqual(1, len(queries))
        self.assertIn(f"request_id={request_id}", queries[0]["args"])
        self.assertNotIn("after_id=0", queries[0]["args"])

    def test_sdk_control_launch_failure_never_selects_a_control_target(self):
        result, records = self.run_sdk_control(reject=True)
        self.assertNotEqual(0, result.returncode)
        self.assert_root_request(records)
        queries = [item for item in records if item["kind"] == "psql"]
        self.assertEqual([], queries)

    def test_seed_validation_uses_root_request_to_await_terminal_status(self):
        path = "scripts/local/validate-seed-scenarios.sh"
        functions = ((path, "http_post"), (path, "workflow_fire_and_await_id"))
        suffix = '''psql_value() { psql -f "$ROOT/scripts/local/sql/$2" "${@:3}"; }
workflow_fire_and_await_id "$TENANT" 47 seed-contract '{"probe":true}' 2
printf '%s|%s' "$LAST_WORKFLOW_RUN_ID" "$LAST_WORKFLOW_STATUS"'''
        result, records = self.execute(path, functions=functions, suffix=suffix)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        request_id, body = self.assert_root_request(records)
        self.assertEqual({"probe": True}, body["params"])
        self.assertEqual("701|SUCCESS", result.stdout)
        queries = [item for item in records if item["kind"] == "psql"]
        self.assertEqual(1, len(queries))
        self.assertIn(f"request_id={request_id}", queries[0]["args"])

    def test_four_day_both_existing_launch_paths_use_workflow_identity(self):
        path = "scripts/sim-4day/40-run-day.sh"
        for function in ("launch", "launch_workflow"):
            with self.subTest(function=function):
                suffix = f'''{function} "$TENANT" probe-definition 2026-10-23 '{{"probe":true}}' '''
                result, records = self.execute(path, functions=((path, function),), suffix=suffix)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                request_id, body = self.assert_root_request(records)
                self.assertEqual("2026-10-23", body["bizDate"])
                self.assertEqual({"probe": True}, body["params"])
                self.assertEqual(".", result.stdout)
                self.assertEqual(body["requestId"], request_id)


if __name__ == "__main__":
    unittest.main()
