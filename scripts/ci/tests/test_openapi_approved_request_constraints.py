# 用途：验证已批准请求约束的 OpenAPI 例外边界。
# 用法：python3 -m unittest scripts/ci/tests/test_openapi_approved_request_constraints.py
"""使用 CI 版本的真实 oasdiff 验证精确例外和邻近变更。"""
import copy
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from scripts.ci.tests.test_openapi_skip_reason_cutover import GIT_BASELINE_STUB

ROOT = Path(__file__).resolve().parents[3]
CONSOLE = 'docs/api/console-api.openapi.yaml'
INTERNAL = 'docs/api/orchestrator-internal.openapi.yaml'
GATE = ROOT / 'scripts/ci/check-openapi-breaking.sh'


def fixture(method, path, kind, value, field):
    operation = {'responses': {'200': {'description': 'ok'}}}
    if '{id}' in path:
        operation['parameters'] = [{'name': 'id', 'in': 'path', 'required': True,
                                    'schema': {'type': 'string'}}]
    if kind == 'query':
        operation.setdefault('parameters', []).append({'name': field, 'in': 'query',
                                                       'schema': {'type': 'string'}})
    else:
        schema = {'type': 'object', 'properties': {field: {'type': 'integer'}}}
        if kind == 'node':
            schema = {'type': 'object', 'properties': {'nodes': {'type': 'array', 'items': {
                'type': 'object', 'properties': {field: {'type': 'string'}}}}}}
            if path.endswith('/full'):
                schema = {'type': 'object', 'properties': {'definition': schema}}
        operation['requestBody'] = {'content': {'application/json': {'schema': schema}}}
    base = {'openapi': '3.0.3', 'info': {'title': 'fixture', 'version': '1'},
            'paths': {path: {method: operation}}}
    changed = copy.deepcopy(base)
    target = changed['paths'][path][method]
    if kind == 'query':
        target['parameters'][-1]['schema']['maxLength'] = value
    else:
        schema = target['requestBody']['content']['application/json']['schema']
        if kind == 'node':
            if path.endswith('/full'):
                schema = schema['properties']['definition']
            schema['properties']['nodes']['items']['required'] = [field]
        else:
            schema['properties'][field][kind] = value
    return base, changed


class ApprovedRequestConstraintsTest(unittest.TestCase):
    def run_gate(self, method, path, kind, value, field, target=CONSOLE):
        base, changed = fixture(method, path, kind, value, field)
        with tempfile.TemporaryDirectory(prefix='wdi-openapi-') as tmp:
            root, baseline, fakebin = (Path(tmp) / x for x in ('work', 'base', 'bin'))
            script = root / 'scripts/ci/check-openapi-breaking.sh'
            script.parent.mkdir(parents=True)
            script.write_bytes(GATE.read_bytes())
            for relative in (CONSOLE, INTERNAL):
                for directory, data in ((baseline, base), (root, changed if relative == target else base)):
                    file = directory / relative
                    file.parent.mkdir(parents=True, exist_ok=True)
                    file.write_text(json.dumps(data))
            fakebin.mkdir()
            git = fakebin / 'git'
            git.write_text(f'#!{sys.executable}\n' + GIT_BASELINE_STUB)
            git.chmod(0o755)
            env = os.environ.copy()
            env.update(PATH=f'{fakebin}{os.pathsep}{env["PATH"]}', BASELINE_DIR=str(baseline),
                       GIT_CALL_LOG=str(Path(tmp) / 'git.log'))
            return subprocess.run(['bash', str(script), 'origin/main'], env=env,
                                  text=True, capture_output=True, timeout=20)

    def test_approved_changes_only(self):
        cases = [
            ('post', '/api/console/job-definitions', 'minimum', 1, 'priority'),
            ('post', '/api/console/job-definitions', 'maximum', 9, 'priority'),
            ('put', '/api/console/job-definitions/{id}', 'minimum', 1, 'priority'),
            ('put', '/api/console/job-definitions/{id}', 'maximum', 9, 'priority'),
            ('get', '/api/console/queries/outbox-deliveries', 'query', 64, 'eventType'),
            ('get', '/api/console/queries/outbox-deliveries', 'query', 256, 'targetTopic'),
            ('get', '/api/console/queries/outbox-retries', 'query', 64, 'eventType'),
            ('post', '/api/console/workflow-definitions', 'node', None, 'nodeName'),
            ('put', '/api/console/workflow-definitions/{id}/full', 'node', None, 'nodeName'),
        ]
        for case in cases:
            with self.subTest(case=case):
                result = self.run_gate(*case)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_nearby_changes_remain_blocked(self):
        cases = [
            ('post', '/api/console/job-definitions', 'maximum', 8, 'priority'),
            ('post', '/api/console/job-definitions', 'minimum', 2, 'priority'),
            ('put', '/api/console/job-definitions/{id}', 'maximum', 9, 'other'),
            ('get', '/api/console/queries/outbox-deliveries', 'query', 63, 'eventType'),
            ('get', '/api/console/queries/outbox-retries', 'query', 256, 'targetTopic'),
            ('post', '/api/console/workflow-definitions', 'node', None, 'other'),
            ('post', '/api/console/workflow-definitions-extra', 'node', None, 'nodeName'),
        ]
        for case in cases:
            with self.subTest(case=case):
                result = self.run_gate(*case)
                self.assertEqual(1, result.returncode, result.stdout + result.stderr)
                self.assertIn('[request-', result.stdout)
        result = self.run_gate('post', '/api/console/job-definitions', 'maximum', 9,
                               'priority', target=INTERNAL)
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn('[request-property-max-set]', result.stdout)


if __name__ == '__main__':
    unittest.main()
