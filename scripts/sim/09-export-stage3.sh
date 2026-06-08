#!/usr/bin/env bash
# =========================================================
# 09-export-stage3.sh:Export 业务分支系统级验证
#
# 覆盖:
#   - JSON 成功态
#   - FIXED_WIDTH 成功态
#   - EXCEL 成功态
#   - bad SQL 失败态
#
# 触发方式:Trigger API -> Orchestrator -> Kafka -> worker-export。
# 数据准备:直接在本地 biz.customer_account seed 小数据,避免扫 1000w benchmark 数据。
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

export TRIGGER_BASE="${TRIGGER_BASE:-http://localhost:18081}"
export INTERNAL_SECRET="${BATCH_INTERNAL_SECRET:-internal-secret}"
export BIZ_DATE="${BIZ_DATE:-$(date +%Y-%m-%d)}"
export BATCH_NO="${BATCH_NO:-sim-export-stage3-$(date +%Y%m%d%H%M%S)}"
export RUN_ID="${RUN_ID:-export-stage3-$(date +%Y%m%d%H%M%S)}"
export REPORT_DIR="${REPORT_DIR:-load-tests/target/$RUN_ID}"
mkdir -p "$REPORT_DIR"

command -v python3 >/dev/null 2>&1 || { echo "需要 python3" >&2; exit 1; }

echo "==> apply bootstrap(export format runtime config)"
docker exec -i batch-postgres-primary psql -U batch_user -d batch_platform \
  -v ON_ERROR_STOP=1 -f /dev/stdin < docs/test-data/sim-e2e-bootstrap.sql >/dev/null

echo "==> seed small export source rows"
docker exec -i batch-postgres-primary psql -U batch_user -d batch_business \
  -v ON_ERROR_STOP=1 -f /dev/stdin >/dev/null <<SQL
INSERT INTO biz.customer_account (
    tenant_id, customer_no, customer_name, customer_type, certificate_no,
    mobile_no, email, status, source_file_name, source_batch_no, source_trace_id,
    created_by, updated_by
)
SELECT 'ta', v.customer_no, v.customer_name, 'PERSONAL', v.certificate_no,
       v.mobile_no, v.email, v.status, 'stage3-export-seed.csv', '${BATCH_NO}',
       'stage3-export-seed', 'sim-e2e', 'sim-e2e'
FROM (VALUES
    ('EXP-000001', 'Export Stage3 A', 'EXPCERT000001', '13910000001', 'exp1@sim.io', 'ACTIVE'),
    ('EXP-000002', 'Export Stage3 B', 'EXPCERT000002', '13910000002', 'exp2@sim.io', 'ACTIVE'),
    ('EXP-000003', 'Export Stage3 C', 'EXPCERT000003', '13910000003', 'exp3@sim.io', 'INACTIVE'),
    ('EXP-000004', 'Export Stage3 D', 'EXPCERT000004', '13910000004', 'exp4@sim.io', 'ACTIVE')
) AS v(customer_no, customer_name, certificate_no, mobile_no, email, status)
ON CONFLICT (tenant_id, customer_no) DO UPDATE
SET customer_name = EXCLUDED.customer_name,
    customer_type = EXCLUDED.customer_type,
    certificate_no = EXCLUDED.certificate_no,
    mobile_no = EXCLUDED.mobile_no,
    email = EXCLUDED.email,
    status = EXCLUDED.status,
    source_file_name = EXCLUDED.source_file_name,
    source_batch_no = EXCLUDED.source_batch_no,
    source_trace_id = EXCLUDED.source_trace_id,
    updated_by = EXCLUDED.updated_by,
    updated_at = CURRENT_TIMESTAMP;
SQL

START_TS="$(docker exec -i batch-postgres-primary psql -U batch_user -d batch_platform -tAc "select now()")"
export START_TS

python3 - <<'PY' 2>&1 | tee "$REPORT_DIR/export-stage3.log"
import json
import os
import subprocess
import time
import urllib.request

BASE = os.environ["TRIGGER_BASE"]
SECRET = os.environ["INTERNAL_SECRET"]
BIZ = os.environ["BIZ_DATE"]
BATCH = os.environ["BATCH_NO"]
START_TS = os.environ["START_TS"].strip()

SCENARIOS = [
    ("json_ok", "TA_EXPORT_REPORT_JSON_TPL", BATCH, "SUCCESS"),
    ("fixed_ok", "TA_EXPORT_REPORT_FIXED_TPL", BATCH + "-fixed", "SUCCESS"),
    ("excel_ok", "TA_EXPORT_REPORT_EXCEL_TPL", BATCH + "-excel", "SUCCESS"),
    ("bad_sql", "TA_EXPORT_REPORT_BAD_SQL_TPL", BATCH + "-badsql", "FAILED"),
]

def launch(label, template_code, batch_no):
    rid = f"sim-stage3-{label}-{int(time.time()*1000)%100000000}"
    body = {
        "tenantId": "ta",
        "jobCode": "TA_EXPORT_REPORT",
        "triggerType": "API",
        "bizDate": BIZ,
        "requestId": rid,
        "params": {
            "templateCode": template_code,
            "batchNo": batch_no,
            "bizType": template_code.removesuffix("_TPL"),
        },
    }
    req = urllib.request.Request(
        f"{BASE}/api/triggers/launch",
        data=json.dumps(body).encode(),
        headers={
            "Content-Type": "application/json",
            "X-Tenant-Id": "ta",
            "X-Internal-Secret": SECRET,
            "Idempotency-Key": rid,
            "X-Request-Id": rid,
        },
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        text = resp.read().decode()
        ok = resp.status == 200 and '"SUCCESS"' in text
        print(f"  [launch] {label:10s} {template_code:32s} {'✓' if ok else '✗'}", flush=True)
        if not ok:
            print(text[:500], flush=True)

for label, template_code, batch_no, expected in SCENARIOS:
    launch(label, template_code, batch_no)

print("==> wait worker terminal states", flush=True)
deadline = time.time() + 180
while time.time() < deadline:
    sql = (
        "select count(*) from batch.job_instance "
        f"where tenant_id='ta' and job_code='TA_EXPORT_REPORT' and created_at >= '{START_TS}' "
        "and instance_status in ('SUCCESS','FAILED','PARTIAL_FAILED','REJECTED','CANCELLED')"
    )
    out = subprocess.run([
        "docker", "exec", "batch-postgres-primary", "psql", "-U", "batch_user",
        "-d", "batch_platform", "-t", "-A", "-c", sql
    ], capture_output=True, text=True)
    done = int((out.stdout or "0").strip() or "0")
    if done >= len(SCENARIOS):
        break
    time.sleep(3)

print("\n-- job_status --", flush=True)
job_sql = (
    "select i.id,i.params_snapshot#>>'{effectiveParams,templateCode}' as template_code,"
    "i.instance_status,t.task_status,t.error_code,"
    "left(coalesce(t.error_message,''),160) as error_message "
    "from batch.job_instance i left join batch.job_task t on t.job_instance_id=i.id "
    f"where i.tenant_id='ta' and i.job_code='TA_EXPORT_REPORT' and i.created_at >= '{START_TS}' "
    "order by i.created_at,i.id"
)
subprocess.run([
    "docker", "exec", "batch-postgres-primary", "psql", "-U", "batch_user",
    "-d", "batch_platform", "-P", "pager=off", "-c", job_sql
], check=False)

print("\n-- file_status --", flush=True)
file_sql = (
    "select biz_type,file_format_type,file_status,count(*) as files,"
    "sum(file_size_bytes) as bytes "
    "from batch.file_record "
    f"where tenant_id='ta' and created_at >= '{START_TS}' "
    "and source_type='GENERATED' "
    "group by biz_type,file_format_type,file_status "
    "order by biz_type,file_format_type,file_status"
)
subprocess.run([
    "docker", "exec", "batch-postgres-primary", "psql", "-U", "batch_user",
    "-d", "batch_platform", "-P", "pager=off", "-c", file_sql
], check=False)

print("\n-- object_sample --", flush=True)
object_sql = (
    "select biz_type,storage_path,file_ext,file_size_bytes "
    "from batch.file_record "
    f"where tenant_id='ta' and created_at >= '{START_TS}' "
    "and source_type='GENERATED' "
    "order by created_at"
)
subprocess.run([
    "docker", "exec", "batch-postgres-primary", "psql", "-U", "batch_user",
    "-d", "batch_platform", "-P", "pager=off", "-c", object_sql
], check=False)

print("\n-- expectation_check --", flush=True)
check_sql = (
    "with actual as ("
    "select i.params_snapshot#>>'{effectiveParams,templateCode}' as template_code, i.instance_status "
    "from batch.job_instance i "
    f"where i.tenant_id='ta' and i.job_code='TA_EXPORT_REPORT' and i.created_at >= '{START_TS}'"
    "), expected(template_code, expected_status) as (values "
    "('TA_EXPORT_REPORT_JSON_TPL','SUCCESS'),"
    "('TA_EXPORT_REPORT_FIXED_TPL','SUCCESS'),"
    "('TA_EXPORT_REPORT_EXCEL_TPL','SUCCESS'),"
    "('TA_EXPORT_REPORT_BAD_SQL_TPL','FAILED')) "
    "select e.template_code,e.expected_status,coalesce(a.instance_status,'MISSING') as actual_status,"
    "(coalesce(a.instance_status,'MISSING') = e.expected_status) as ok "
    "from expected e left join actual a using(template_code) order by e.template_code"
)
subprocess.run([
    "docker", "exec", "batch-postgres-primary", "psql", "-U", "batch_user",
    "-d", "batch_platform", "-P", "pager=off", "-c", check_sql
], check=False)

fail_sql = "select count(*) from (" + check_sql + ") s where not ok"
out = subprocess.run([
    "docker", "exec", "batch-postgres-primary", "psql", "-U", "batch_user",
    "-d", "batch_platform", "-t", "-A", "-c", fail_sql
], capture_output=True, text=True)
failures = int((out.stdout or "0").strip() or "0")
print(f"\n==> Stage 3 export scenario submitted: batchNo={BATCH} startTs={START_TS}", flush=True)
if failures:
    raise SystemExit(f"Stage 3 export expectation failed: {failures} mismatch(es)")
PY
