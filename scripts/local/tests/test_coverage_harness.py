#!/usr/bin/env python3
# 用途：验证覆盖包运行契约和 harness 的触发、结果断言，不连接真实服务。
# 用法：python3 -m unittest scripts/local/tests/test_coverage_harness.py -v
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from openpyxl import load_workbook

COVERAGE_CONFIG_SPEC = importlib.util.spec_from_file_location(
    'coverage_config', Path(__file__).resolve().parents[3] / 'scripts/lib/coverage-config.py')
coverage_config = importlib.util.module_from_spec(COVERAGE_CONFIG_SPEC)
COVERAGE_CONFIG_SPEC.loader.exec_module(coverage_config)
import sys
sys.modules['coverage_config'] = coverage_config
from coverage_config import apply_runtime_contract

SUBJECT_PATH = Path(__file__).resolve().parents[1] / 'coverage-harness.py'
spec = importlib.util.spec_from_file_location('coverage_harness', SUBJECT_PATH)
subject = importlib.util.module_from_spec(spec)
spec.loader.exec_module(subject)


class Cookie:
    def __init__(self):
        self.name = 'XSRF-TOKEN'
        self.value = 'test-csrf'


class FakeApi:
    def __init__(self):
        self.cookies = [Cookie()]
        self.calls = []
        self.run = None

    def request(self, method, path, payload=None, headers=None, raw=False):
        self.calls.append((method, path, payload))
        if method == 'POST' and path.endswith('/trigger'):
            self.run = {'id': 1, 'runStatus': 'SUCCESS', 'currentNodeCode': 'END'}
            return 'req-test'
        return {'items': [self.run] if self.run else []}


class CoverageHarnessTest(unittest.TestCase):
    def test_runtime_contract_contains_executable_template_and_channel_config(self):
        workbook = load_workbook(
            'test-data/tenant-config-packages/coverage-examples/ta-coverage-example.xlsx')
        apply_runtime_contract('ta', workbook, '/tmp/known_hosts')
        rows = workbook['file_template_config'].iter_rows(values_only=True)
        headers = next(rows)
        templates = [dict(zip(headers, row)) for row in rows]
        import_template = next(row for row in templates if row['template_code'] == 'TA_COV_TPL_IMPORT')
        export_template = next(row for row in templates if row['template_code'] == 'TA_COV_TPL_EXPORT')
        self.assertEqual(json.loads(import_template['query_param_schema'])['jdbcMappedImport']['table'], 'customer_account')
        self.assertEqual(len(json.loads(import_template['field_mappings'])), 7)
        self.assertIn('CAST(:batchNo AS text) IS NOT NULL', export_template['default_query_sql'])
        self.assertEqual(json.loads(export_template['query_param_schema'])['sqlTemplateExport']['cursorColumn'], 'id')
        channel_rows = workbook['file_channel_config'].iter_rows(values_only=True)
        channel_headers = next(channel_rows)
        channel = dict(zip(channel_headers, next(channel_rows)))
        self.assertEqual(channel['target_endpoint'], '127.0.0.1:12222')
        self.assertEqual(json.loads(channel['config_json'])['sftp_known_hosts_path'], '/tmp/known_hosts')
        job_rows = workbook['job_definition'].iter_rows(values_only=True)
        job_headers = next(job_rows)
        dispatch = next(dict(zip(job_headers, row)) for row in job_rows
                        if dict(zip(job_headers, row))['job_type'] == 'DISPATCH')
        self.assertEqual(json.loads(dispatch['default_params'])['systemPipelineStepParams']['DISPATCH']['channelCode'], 'ta-cov-sftp')

    def test_tb_calendar_enables_catch_up(self):
        workbook = load_workbook(
            'test-data/tenant-config-packages/coverage-examples/tb-coverage-example.xlsx')
        apply_runtime_contract('tb', workbook, '/tmp/known_hosts')
        rows = workbook['business_calendar'].iter_rows(values_only=True)
        headers = next(rows)
        calendar = dict(zip(headers, next(rows)))
        self.assertEqual(calendar['catch_up_policy'], 'AUTO')

    def test_trigger_uses_workflow_payload_and_terminal_status(self):
        api = FakeApi()
        result = subject.trigger(api, 'ta', 7, 'workflow:SERIAL', {'proceed': True}, 'API')
        self.assertEqual(result['status'], 'SUCCESS')
        post = next(call for call in api.calls if call[0] == 'POST')
        body = json.loads(post[2].decode())
        self.assertEqual(body['triggerType'], 'API')
        self.assertEqual(json.loads(body['payload']), {'proceed': True})

    def test_schedule_update_uses_workflow_version_and_schedule_endpoint(self):
        api = FakeApi()
        api.run = {'id': 7, 'version': 3, 'scheduleType': 'MANUAL', 'triggerMode': 'MANUAL',
                   'timezone': 'Asia/Shanghai', 'calendarCode': 'default_calendar'}
        subject.update_schedule(api, 'ta', 7, {
            'scheduleType': 'FIXED_RATE', 'scheduleExpr': '60', 'triggerMode': 'SCHEDULED'})
        method, path, payload = next(call for call in api.calls if call[0] == 'PUT')
        self.assertEqual(path, '/api/console/workflow-definitions/7/schedule')
        body = json.loads(payload.decode())
        self.assertEqual(body['expectedVersion'], 3)
        self.assertEqual(body['scheduleType'], 'FIXED_RATE')
        self.assertEqual(body['scheduleExpr'], '60')
        self.assertEqual(body['triggerMode'], 'SCHEDULED')
        self.assertEqual(body['calendarCode'], 'default_calendar')

    def test_import_job_receives_csv_content(self):
        self.assertEqual(subject.job_params(FakeApi(), 'ta', 'IMPORT', 1),
                         {'content': subject.IMPORT_CONTENT})

    def test_cleanup_only_selects_coverage_objects(self):
        self.assertTrue(subject.is_coverage_minio_object(
            'local/batch-dev/uploads/ta/20261103Z/ta-coverage-123.csv'))
        self.assertTrue(subject.is_coverage_minio_object(
            'local/batch-dev/outbound/TA_COV_EXPORT/2026-11-03/customer.csv'))
        self.assertFalse(subject.is_coverage_minio_object(
            'local/batch-dev/outbound/TB_EXPORT_STATEMENT/2026-11-03/customer.csv'))
        sql = subject.CLEANUP_SQL.read_text(encoding='utf-8')
        self.assertNotIn('TRUNCATE', sql.upper())
        self.assertNotIn('customer_summary', sql)
        for statement in sql.split(';'):
            if 'DELETE FROM' in statement:
                self.assertIn('WHERE', statement)
                self.assertTrue("tenant_id IN ('ta', 'tb', 'tc')" in statement
                                or 'coverage_' in statement)

    def test_failure_still_runs_after_cleanup_and_preserves_evidence_contract(self):
        argv = ['coverage-harness.py', 'all', '--operator', 'admin', '--allow-local-bypass']
        with mock.patch.object(subject.sys, 'argv', argv), \
                mock.patch.object(subject, 'run_selected', side_effect=subject.INIT.Stop('expected')), \
                mock.patch.object(subject, 'cleanup') as cleanup:
            self.assertEqual(subject.main(), 1)
        self.assertEqual([call.args[1] for call in cleanup.call_args_list], ['before', 'after'])

    def test_verify_results_rejects_non_success_and_writes_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(subject.INIT.Stop):
                subject.verify_results(type('Args', (), {'run_dir': directory})(),
                                       [{'tenant': 'ta', 'label': 'job:IMPORT', 'status': 'FAILED'}])
            self.assertTrue((Path(directory) / 'results.json').is_file())


if __name__ == '__main__':
    unittest.main()
