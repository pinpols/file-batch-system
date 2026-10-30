# 用途：验证fixture 会话缓存失效的认证传输。
# 用法：在仓库根目录执行 python3 -m unittest scripts/ci/tests/test_load_fixture_console_session.py。
"""验证真实认证会话缓存失效的传输过程，不访问 fixture 数据库。"""
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest
import json

ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / "load-tests/scripts/prepare-worker-load-data.sh"

class LoadFixtureConsoleSessionTest(unittest.TestCase):
    def run_transport(self, mode="success", token="fixture-jwt", csrf="fixture-csrf"):
        source = SCRIPT.read_text()
        functions = []
        for name in ["require_console_session", "evict_console_definition"]:
            match = re.search(rf"^{name}\(\) \{{\n.*?^\}}", source, re.M | re.S)
            self.assertIsNotNone(match, name)
            functions.append(match.group())
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            capture = tmp / "calls.jsonl"
            curl = tmp / "curl"
            curl.write_text(f"#!{sys.executable}\n" + r"""import os,sys,json
from pathlib import Path
with Path(os.environ['CAPTURE']).open('a') as log:
 log.write(json.dumps(sys.argv[1:])+'\n')
if os.environ['MODE']=='http': raise SystemExit(22)
print('{"code":"ERROR"}' if os.environ['MODE']=='application' else '{"code":"SUCCESS"}')
""")
            curl.chmod(0o755)
            env = dict(os.environ, PATH=str(tmp)+os.pathsep+os.environ['PATH'],
                       CAPTURE=str(capture), MODE=mode, BATCH_CONSOLE_TOKEN=token,
                       BATCH_CONSOLE_CSRF_TOKEN=csrf, CONSOLE_BASE_URL="http://console.example.invalid:49327")
            command = "set -euo pipefail\n" + "\n".join(functions) + "\nrequire_console_session\nevict_console_definition job-definition jobCode sample_job\nevict_console_definition workflow-definition workflowCode sample_workflow\n"
            result = subprocess.run(['bash','-c',command], env=env, text=True, capture_output=True)
            calls = [json.loads(line) for line in capture.read_text().splitlines()] if capture.exists() else []
            return result,calls

    def test_cookie_and_csrf_match_both_exact_cache_targets(self):
        result,calls = self.run_transport()
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertEqual(2,len(calls))
        for call,target in zip(calls,['job-definition?tenantId=default-tenant&jobCode=sample_job', 'workflow-definition?tenantId=default-tenant&workflowCode=sample_workflow']):
            self.assertIn('http://console.example.invalid:49327/api/console/ops/cache/evict-'+target,call)
            self.assertEqual('batch_console_token=fixture-jwt; XSRF-TOKEN=fixture-csrf',call[call.index('--cookie')+1])
            self.assertIn('X-XSRF-TOKEN: fixture-csrf',call)
            self.assertTrue(any(value.startswith('Idempotency-Key: fixture-cache-') for value in call))
            self.assertEqual('batch-load-tests',call[call.index('--user-agent')+1])
        self.assertNotIn('fixture-jwt',result.stdout+result.stderr)

    def test_missing_auth_rejected_before_transport(self):
        for token,csrf in [('','fixture-csrf'),('fixture-jwt','')]:
            with self.subTest(token_present=bool(token)):
                result,calls = self.run_transport(token=token,csrf=csrf)
                self.assertNotEqual(0,result.returncode)
                self.assertEqual([],calls)

    def test_http_and_application_failure_stop_following_evictions(self):
        for mode in ['http','application']:
            with self.subTest(mode=mode):
                result,calls = self.run_transport(mode=mode)
                self.assertNotEqual(0,result.returncode)
                self.assertEqual(1,len(calls))

if __name__ == '__main__': unittest.main()
