#!/usr/bin/env python3
# 用途：验证租户覆盖包初始化脚本的失败关闭和中断保护。
# 用法：python3 -m unittest discover -s scripts/data/tests -p 'test_init_coverage_tenants.py'。
import importlib.util
import json
import os
import stat
import tempfile
import unittest
from pathlib import Path

MODULE = Path(__file__).resolve().parents[1] / 'init-coverage-tenants.py'
spec = importlib.util.spec_from_file_location('coverage_init', MODULE)
subject = importlib.util.module_from_spec(spec)
spec.loader.exec_module(subject)


class FakeApi:
    def __init__(self, existing=()):
        self.existing = set(existing)
        self.calls = []
        self.fail_at = None

    def exists(self, tenant):
        self.calls.append(('exists', tenant))
        return tenant in self.existing

    def create(self, tenant, username, password, key):
        self.calls.append(('create', tenant))
        if self.fail_at == 'create':
            raise subject.Stop('请求结果不明')
        self.existing.add(tenant)
        return {'id': 1, 'tenantId': tenant, 'createdBy': 'operator'}

    def detail(self, tenant):
        return {'id': 1, 'tenantId': tenant, 'createdBy': 'operator'}

    def upload(self, tenant, workbook, key):
        self.calls.append(('upload', tenant))
        return {'uploadToken': 'session', **{k: 0 for k in subject.UPLOAD_COUNTS}}

    def preview(self, tenant, token):
        self.calls.append(('preview', tenant))
        return {'uploadToken': token, 'previewToken': 'preview', 'totalRows': 0,
                'validRows': 0, 'invalidRows': 0, 'issues': [], 'sheets': []}

    def assert_empty_export(self, tenant):
        self.calls.append(('export', tenant))
        if self.fail_at == 'export':
            raise subject.Stop('非空')

    def apply(self, tenant, upload, preview, key):
        self.calls.append(('apply', tenant))
        if self.fail_at == 'apply':
            raise subject.Stop('请求结果不明')
        result = {'tenantId': tenant, **{k: 0 for k in subject.APPLY_COUNTS}}
        if self.fail_at == 'updated':
            result['jobUpdated'] = 1
        return result


class TestCoverageInit(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.files = {}
        for tenant in ('ta', 'tb', 'tc'):
            path = self.root / f'{tenant}.xlsx'
            path.write_bytes(tenant.encode())
            self.files[tenant] = path
        self.receipt = self.root / 'receipt.json'
        self.api = FakeApi()

    def run_init(self):
        return subject.run(self.api, self.files, self.receipt, 'http://127.0.0.1:18080',
                           'operator', '-admin', {t: 'password' for t in ('ta', 'tb', 'tc')}, True)

    def test_any_preexisting_tenant_stops_before_create(self):
        self.api.existing.add('tb')
        with self.assertRaises(subject.Stop):
            self.run_init()
        self.assertFalse(any(c[0] == 'create' for c in self.api.calls))

    def test_happy_path_is_resumable_without_duplicate_writes(self):
        self.run_init()
        self.assertEqual(3, sum(c[0] == 'apply' for c in self.api.calls))
        self.api.calls.clear()
        self.run_init()
        self.assertFalse(any(c[0] in ('create', 'upload', 'apply') for c in self.api.calls))
        self.assertNotIn('password', self.receipt.read_text())
        self.assertNotIn('preview', self.receipt.read_text())

    def test_lost_creation_response_cannot_be_replayed_or_claimed(self):
        self.api.fail_at = 'create'
        with self.assertRaises(subject.Stop):
            self.run_init()
        self.api.fail_at = None
        with self.assertRaises(subject.Stop):
            self.run_init()
        self.assertEqual(1, sum(c[0] == 'create' for c in self.api.calls))

    def test_lost_apply_response_cannot_be_replayed(self):
        self.api.fail_at = 'apply'
        with self.assertRaises(subject.Stop):
            self.run_init()
        self.api.fail_at = None
        with self.assertRaises(subject.Stop):
            self.run_init()
        self.assertEqual(1, sum(c[0] == 'apply' for c in self.api.calls))

    def test_nonempty_export_never_applies(self):
        self.api.fail_at = 'export'
        with self.assertRaises(subject.Stop):
            self.run_init()
        self.assertFalse(any(c[0] == 'apply' for c in self.api.calls))

    def test_changed_package_stops_resume(self):
        self.api.fail_at = 'export'
        with self.assertRaises(subject.Stop):
            self.run_init()
        self.files['ta'].write_bytes(b'changed')
        with self.assertRaises(subject.Stop):
            self.run_init()
        self.assertFalse(any(c[0] == 'apply' for c in self.api.calls))

    def test_wrong_existing_receipt_identity_stops(self):
        self.api.fail_at = 'export'
        with self.assertRaises(subject.Stop):
            self.run_init()
        receipt = json.loads(self.receipt.read_text())
        receipt['operator'] = 'other'
        self.receipt.write_text(json.dumps(receipt))
        with self.assertRaises(subject.Stop):
            self.run_init()


    def test_apply_unexpected_update_does_not_mark_success_or_retry(self):
        self.api.fail_at = 'updated'
        with self.assertRaises(subject.Stop):
            self.run_init()
        with self.assertRaises(subject.Stop):
            self.run_init()
        self.assertEqual(1, sum(c[0] == 'apply' for c in self.api.calls))

    def test_generated_credentials_are_private_unique_and_never_overwritten(self):
        path = self.root / 'credentials.json'
        passwords = subject.credentials(path, '-admin', False)
        self.assertEqual(3, len(set(passwords.values())))
        self.assertEqual(0o600, stat.S_IMODE(path.stat().st_mode))
        original = path.read_bytes()
        with self.assertRaises(FileExistsError):
            subject.credentials(path, '-admin', False)
        self.assertEqual(original, path.read_bytes())
        self.assertEqual(passwords, subject.credentials(path, '-admin', True))
        os.chmod(path, 0o644)
        with self.assertRaises(subject.Stop):
            subject.credentials(path, '-admin', True)

    def test_only_loopback_origins_are_accepted(self):
        for url in ('https://example.com', 'http://127.0.0.1@evil.test',
                    'http://127.0.0.1:18080/path', 'file:///tmp/x'):
            with self.subTest(url=url), self.assertRaises(subject.Stop):
                subject.local_origin(url)

    def test_preview_errors_do_not_reach_apply(self):
        self.api.preview = lambda tenant, token: {'invalidRows': 1, 'issues': [],
                                                  'totalRows': 1, 'validRows': 0}
        with self.assertRaises(subject.Stop):
            self.run_init()
        self.assertFalse(any(c[0] == 'apply' for c in self.api.calls))


if __name__ == '__main__':
    unittest.main()
