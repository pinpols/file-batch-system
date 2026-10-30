# 用途：验证 SDK 跨语言测试结果汇总与缺失报告处理。
# 用法：python3 -m unittest scripts.ci.tests.test_report_sdk_test_summary。
import importlib.util
from pathlib import Path
import re
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'report-sdk-test-summary.py'
SPEC = importlib.util.spec_from_file_location('report_sdk_test_summary', SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class SdkTestSummaryTest(unittest.TestCase):
    def test_matrix_versions_match_summary_artifacts(self):
        workflow = (Path(__file__).resolve().parents[3] / '.github/workflows/sdk-contract-parity.yml').read_text()
        for job, matrix_key, artifact_prefix in (
            ('python-contract', 'python-version', 'sdk-test-python-'),
            ('typescript-contract', 'node-version', 'sdk-test-typescript-'),
            ('go-contract', 'go-version', 'sdk-test-go-'),
        ):
            with self.subTest(job=job):
                block = workflow.split(f'  {job}:\n', 1)[1].split('\n  #', 1)[0]
                matrix = re.search(rf'{matrix_key}:\s*\[([^]]+)\]', block)
                self.assertIsNotNone(matrix)
                versions = set(re.findall(r"'([^']+)'", matrix.group(1)))
                expected = {f'{artifact_prefix}{version}-reports' for version in versions}
                self.assertEqual(expected, {artifact for case_job, artifact, _ in MODULE.CASES if case_job == job})

    def test_success_failed_and_missing_are_distinct(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = root / 'sdk-test-java-reports'
            artifact.mkdir()
            (artifact / 'java-contract.log').write_text('3 tests\n')
            (artifact / 'java-contract.exit').write_text('0\n')
            rows, missing = MODULE.summarize(root, {'java-contract': 'success', 'python-contract': 'success'})
            self.assertEqual('exit-0', next(row['status'] for row in rows if row['case'] == 'java-contract'))
            self.assertTrue(any('python' in item for item in missing))
            rows, missing = MODULE.summarize(root, {'java-contract': 'failure', 'python-contract': 'skipped'})
            self.assertEqual([], missing)
            self.assertEqual('skipped', next(row['status'] for row in rows if row['case'] == 'python-contract' and '3.12' in row['artifact']))


if __name__ == '__main__':
    unittest.main()
