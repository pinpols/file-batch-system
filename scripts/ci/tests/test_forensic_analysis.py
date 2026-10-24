"""Compare exported JSON text and database JSON objects without hiding real errors."""
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]

class ForensicAnalysisTest(unittest.TestCase):
    def compare(self, left, right):
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            for name,row in [('prod',left),('replay',right)]:
                value={'jobCode':'sample','bizDate':'2026-10-24','instanceStatus':'SUCCESS','processedCount':1,'resultSummary':row}
                (tmp/f'{name}.json').write_text(json.dumps([value]))
            result=subprocess.run(['bash',str(ROOT/'scripts/local/analyze-replay.sh'),'--prod',str(tmp/'prod.json'),'--replay',str(tmp/'replay.json'),'--out',str(tmp/'diff.json')],text=True,capture_output=True)
            self.assertEqual(0,result.returncode,result.stderr)
            return json.loads((tmp/'diff.json').read_text())

    def test_null_error_fields_in_text_and_object_are_equal(self):
        summary={'lastErrorCode':None,'lastErrorMessage':None,'failedPartitions':0}
        diff=self.compare(json.dumps(summary),summary)
        self.assertEqual(0,diff['summary']['errorChanged'])

    def test_real_structured_and_plain_text_error_changes_remain_visible(self):
        for left,right in [({'lastErrorCode':'A','lastErrorMessage':'old'}, {'lastErrorCode':'B','lastErrorMessage':'new'}),('failed to write old','failed to write new')]:
            with self.subTest(left=left):
                diff=self.compare(json.dumps(left) if isinstance(left,dict) else left,right)
                self.assertEqual(1,diff['summary']['errorChanged'])

if __name__ == '__main__': unittest.main()
