"""Run root/child metrics contracts in an owned schema of the selected local test DB."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import uuid

ROOT = Path(__file__).resolve().parents[2]
SQL = ROOT / 'load-tests/sql'
base = ['psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '-h', os.environ['PGHOST'],
        '-p', os.environ['PGPORT'], '-U', os.environ['PGUSER'], '-d', os.environ['PLATFORM_DB']]
results = []
# 独立预期保留未决根、错租户及错定义，不能把缺少根的请求误判为已完成。
cases = {'success': '2|2|2|2', 'root-running': '2|1|2|1',
         'root-failed': '2|2|2|2', 'foreign-root': '1|1|2|1',
         'wrong-definition': '1|1|2|1'}
for case, expected in cases.items():
    schema = 'closure_metrics_' + uuid.uuid4().hex
    with tempfile.TemporaryDirectory() as directory:
        tmp = Path(directory)
        def mapped(name):
            target = tmp / name
            target.write_text((SQL / name).read_text().replace('CREATE SCHEMA batch;', f'CREATE SCHEMA {schema};').replace('batch.', schema + '.'))
            return str(target)
        subprocess.run(base + ['-v', 'metrics_case=' + case, '-f', mapped('test-control-plane-metrics-fixture.sql')], check=True, capture_output=True)
        try:
            arguments = ['-v', 'tenant_id=tenant-a', '-v', 'run_id=metrics-run',
                         '-v', 'biz_date=2026-10-18', '-v', 'biz_date_cardinality=2',
                         '-f', mapped('control-run-terminal-counts.sql')]
            actual = subprocess.check_output(base + arguments, text=True).strip()
            assert actual == expected, (case, expected, actual)
            results.append({'case': case, 'expected': expected, 'actual': actual})
        finally:
            subprocess.run(base + ['-v', 'fixture_schema=' + schema, '-f', str(SQL / 'drop-control-plane-metrics-fixture.sql')], check=True, capture_output=True)
print(json.dumps({'passed': len(results), 'ownedSchemasCleaned': len(results), 'results': results}, indent=2))
