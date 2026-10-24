"""恢复运维脚本必须传播查询错误并使用服务当前的认证合同。"""

import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
RETRY_SCRIPTS = ("heal-retry-tasks.sh", "heal-retry-partitions.sh")
CONSOLE_SCRIPTS = ("trigger-compensation.sh", "heal-drain-timeout.sh")
SCRIPTS = RETRY_SCRIPTS + CONSOLE_SCRIPTS
INTERNAL_SECRET = "runbook-test-internal-secret"
CONSOLE_TOKEN = "runbook-test-console-token"
CSRF_TOKEN = "runbook-test-csrf-token"


class RecoveryRunbookTransportTest(unittest.TestCase):
    def run_script(self, name, *, dry_run="false", fail_query="", http_failure=False,
                   missing_auth=False):
        with tempfile.TemporaryDirectory(prefix="recovery-runbook-") as temporary:
            temporary_path = Path(temporary)
            fake_bin = temporary_path / "bin"
            fake_bin.mkdir()
            env_root = temporary_path / "env"
            env_lib = env_root / "scripts/lib"
            env_lib.mkdir(parents=True)
            for helper in ("runtime-defaults.sh", "python-runtime.sh"):
                helper_path = ROOT / "scripts/lib" / helper
                helper_content = helper_path.read_text()
                (env_lib / helper).write_text(helper_content)
            query_log = temporary_path / "queries.jsonl"
            http_log = temporary_path / "http.jsonl"
            fake_psql = fake_bin / "psql"
            fake_psql.write_text(
                """#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
args = sys.argv[1:]
sql = Path(args[args.index('-f') + 1]).name
with open(os.environ['QUERY_LOG'], 'a') as log:
    log.write(json.dumps(args) + '\\n')
if os.environ['FAIL_QUERY'] and os.environ['FAIL_QUERY'] in sql:
    print('injected PostgreSQL query failure', file=sys.stderr)
    sys.exit(12)
if sql.endswith('-count.sql'):
    print('1')
elif sql.endswith('-batch.sql'):
    print('41|tenant-a')
elif sql == 'heal-drain-timeout-workers.sql':
    print('worker-test|tenant-a|2026-10-23T00:00:00Z')
else:
    sys.exit(99)
"""
            )
            fake_psql.chmod(0o755)
            fake_curl = fake_bin / "curl"
            fake_curl.write_text(
                """#!/usr/bin/env python3
import json, os, sys
args = sys.argv[1:]
with open(os.environ['HTTP_LOG'], 'a') as log:
    log.write(json.dumps(args) + '\\n')
headers = [args[index + 1] for index, value in enumerate(args) if value == '-H']
url = args[-1]
if '/internal/recoveries/' in url:
    authenticated = 'X-Internal-Secret: runbook-test-internal-secret' in headers
else:
    cookie = args[args.index('--cookie') + 1] if '--cookie' in args else ''
    authenticated = ('batch_console_token=runbook-test-console-token' in cookie
                     and 'XSRF-TOKEN=runbook-test-csrf-token' in cookie
                     and 'X-XSRF-TOKEN: runbook-test-csrf-token' in headers)
if not authenticated or os.environ['HTTP_FAILURE'] == '1':
    print('HTTP authentication/transport rejected', file=sys.stderr)
    sys.exit(22)
print('{"code":"SUCCESS","data":"operation-test"}')
"""
            )
            fake_curl.chmod(0o755)
            env = os.environ.copy()
            env.pop("BATCH_ENV_LOADED", None)
            env.update({
                "PATH": f"{fake_bin}{os.pathsep}{env['PATH']}",
                "BATCH_ENV_COMMON_ROOT": str(env_root),
                "COMPOSE_ENV_FILE": str(temporary_path / "no-env-file"),
                "BATCH_HEAL_RETRY_DRY_RUN": dry_run,
                "BATCH_COMPENSATION_DRY_RUN": dry_run,
                "BATCH_HEAL_DRY_RUN": dry_run,
                "BATCH_HEAL_RETRY_SLEEP_MS": "0",
                "BATCH_HEAL_RETRY_TENANT": "tenant-a",
                "BATCH_COMPENSATION_JSON": '{"tenantId":"tenant-a","targetId":41}',
                "BATCH_INTERNAL_SECRET": "internal-secret" if missing_auth else INTERNAL_SECRET,
                "BATCH_CONSOLE_TOKEN": CONSOLE_TOKEN,
                "BATCH_CONSOLE_CSRF_TOKEN": "" if missing_auth else CSRF_TOKEN,
                "BATCH_ORCHESTRATOR_URL": "http://operations.example.invalid:49235",
                "BATCH_CONSOLE_URL": "http://console.example.invalid:49236",
                "QUERY_LOG": str(query_log),
                "HTTP_LOG": str(http_log),
                "FAIL_QUERY": fail_query,
                "HTTP_FAILURE": "1" if http_failure else "0",
            })
            script = ROOT / "scripts/ops" / name
            result = subprocess.run(
                ["bash", str(script)], capture_output=True, text=True, env=env, timeout=10
            )
            queries = self.read_invocations(query_log)
            requests = self.read_invocations(http_log)
            return result, queries, requests

    @staticmethod
    def read_invocations(path):
        if not path.exists():
            return []
        return [json.loads(line) for line in path.read_text().splitlines()]

    def test_retry_count_and_page_query_failures_are_not_empty_success(self):
        for script in RETRY_SCRIPTS:
            for query in ("-count.sql", "-batch.sql"):
                with self.subTest(script=script, query=query):
                    result, queries, requests = self.run_script(script, fail_query=query)
                    self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
                    self.assertTrue(queries)
                    self.assertEqual([], requests)
                    self.assertNotIn("nothing to heal", result.stdout)
                    self.assertNotIn("replayed successfully", result.stdout)

    def test_retry_uses_internal_secret_and_configured_origin(self):
        for script in RETRY_SCRIPTS:
            with self.subTest(script=script):
                result, _, requests = self.run_script(script)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertEqual(1, len(requests))
                request = requests[0]
                self.assertIn(f"X-Internal-Secret: {INTERNAL_SECRET}", request)
                self.assertTrue(request[-1].startswith("http://operations.example.invalid:49235/"))
                self.assertNotIn(INTERNAL_SECRET, result.stdout + result.stderr)

    def test_console_uses_jwt_cookie_and_matching_csrf_cookie_header(self):
        for script in CONSOLE_SCRIPTS:
            with self.subTest(script=script):
                result, _, requests = self.run_script(script)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertEqual(1, len(requests))
                request = requests[0]
                self.assertIn(f"X-XSRF-TOKEN: {CSRF_TOKEN}", request)
                self.assertTrue(request[-1].startswith("http://console.example.invalid:49236/"))
                body_argument = request[request.index("-d") + 1]
                body = json.loads(body_argument)
                self.assertEqual("tenant-a", body["tenantId"])
                for secret in (CONSOLE_TOKEN, CSRF_TOKEN):
                    self.assertNotIn(secret, result.stdout + result.stderr)

    def test_dry_run_preserves_preview_without_http_or_authentication(self):
        for script in SCRIPTS:
            with self.subTest(script=script):
                result, _, requests = self.run_script(script, dry_run="true", missing_auth=True)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertIn("DRY-RUN", result.stdout)
                self.assertEqual([], requests)

    def test_invalid_dry_run_value_fails_before_database_or_http(self):
        for script in SCRIPTS:
            with self.subTest(script=script):
                result, queries, requests = self.run_script(script, dry_run="flase")
                self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertEqual([], queries)
                self.assertEqual([], requests)

    def test_missing_write_credentials_fail_before_http(self):
        for script in SCRIPTS:
            with self.subTest(script=script):
                result, _, requests = self.run_script(script, missing_auth=True)
                self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertEqual([], requests)

    def test_http_failure_is_not_reported_as_success(self):
        for script in SCRIPTS:
            with self.subTest(script=script):
                result, _, requests = self.run_script(script, http_failure=True)
                self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertEqual(1, len(requests))
                self.assertNotIn("replayed successfully", result.stdout)
                self.assertNotIn("force-offlined successfully", result.stdout)
                self.assertNotIn("补偿触发已提交", result.stdout)


if __name__ == "__main__":
    unittest.main()
