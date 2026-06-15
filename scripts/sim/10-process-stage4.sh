#!/usr/bin/env bash
# =========================================================
# 10-process-stage4.sh:Process 业务分支系统级验证
#
# 覆盖:
#   - JSONB staging 成功态
#   - DIRECT fast path 成功态
#   - VALIDATE 失败态
#   - empty result SUCCESS 策略
#
# 触发方式:Trigger API -> Orchestrator -> Kafka -> worker-process。
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

SIM_STAGE_NAME="process-stage4"
# shellcheck source=env-common.sh
source "$ROOT/scripts/sim/env-common.sh"

command -v python3 >/dev/null 2>&1 || { echo "需要 python3" >&2; exit 1; }

echo "==> seed process business tables"
docker exec -i batch-postgres-primary psql -U batch_user -d batch_business \
  -v ON_ERROR_STOP=1 -v biz_date="$BIZ_DATE" \
  -f /dev/stdin < docs/test-data/sim-stage4-process-business-fixtures.sql >/dev/null

echo "==> seed process platform jobs"
docker exec -i batch-postgres-primary psql -U batch_user -d batch_platform \
  -v ON_ERROR_STOP=1 -v biz_date="$BIZ_DATE" \
  -f /dev/stdin < docs/test-data/sim-stage4-process-platform-fixtures.sql >/dev/null

START_TS="$(docker exec -i batch-postgres-primary psql -U batch_user -d batch_platform -tAc "select now()")"
export START_TS

python3 - <<'PY' 2>&1 | tee "$REPORT_DIR/process-stage4.log"
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
    ("jsonb_ok", "TA_PROCESS_STAGE4_JSONB", BATCH + "-jsonb", "SUCCESS"),
    ("direct_ok", "TA_PROCESS_STAGE4_DIRECT", BATCH + "-direct", "SUCCESS"),
    ("validate_fail", "TA_PROCESS_STAGE4_VALIDATE_FAIL", BATCH + "-validate-fail", "FAILED"),
    ("empty_success", "TA_PROCESS_STAGE4_EMPTY_SUCCESS", BATCH + "-empty", "SUCCESS"),
]

def launch(label, job_code, batch_key):
    rid = f"sim-stage4-{label}-{int(time.time()*1000)%100000000}"
    body = {
        "tenantId": "ta",
        "jobCode": job_code,
        "triggerType": "API",
        "bizDate": BIZ,
        "requestId": rid,
        "params": {
            "batchNo": BATCH,
            "batchKey": batch_key,
            "bizDate": BIZ,
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
        print(f"  [launch] {label:14s} {job_code:34s} {'✓' if ok else '✗'}", flush=True)
        if not ok:
            print(text[:500], flush=True)

for label, job_code, batch_key, expected in SCENARIOS:
    launch(label, job_code, batch_key)

print("==> wait worker terminal states", flush=True)
deadline = time.time() + 180
job_codes = ",".join("'" + s[1] + "'" for s in SCENARIOS)
while time.time() < deadline:
    sql = (
        "select count(*) from batch.job_instance "
        f"where tenant_id='ta' and job_code in ({job_codes}) and created_at >= '{START_TS}' "
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
    "select i.id,i.job_code,i.instance_status,t.task_status,t.error_code,"
    "left(coalesce(t.error_message,''),180) as error_message "
    "from batch.job_instance i left join batch.job_task t on t.job_instance_id=i.id "
    f"where i.tenant_id='ta' and i.job_code in ({job_codes}) and i.created_at >= '{START_TS}' "
    "order by i.created_at,i.id"
)
subprocess.run([
    "docker", "exec", "batch-postgres-primary", "psql", "-U", "batch_user",
    "-d", "batch_platform", "-P", "pager=off", "-c", job_sql
], check=False)

print("\n-- target_rows --", flush=True)
subprocess.run([
    "docker", "exec", "batch-postgres-primary", "psql", "-U", "batch_user",
    "-d", "batch_business", "-P", "pager=off", "-c",
    "select scenario, account_id, total_amount, event_count, high_water_mark "
    "from biz.process_stage4_target where tenant_id='ta' order by scenario, account_id"
], check=False)

print("\n-- staging_leftover --", flush=True)
subprocess.run([
    "docker", "exec", "batch-postgres-primary", "psql", "-U", "batch_user",
    "-d", "batch_business", "-P", "pager=off", "-c",
    "select tenant_id,target_table,batch_key,count(*) as rows "
    "from batch.process_staging where tenant_id='ta' and batch_key like '" + BATCH + "%' "
    "group by tenant_id,target_table,batch_key order by batch_key"
], check=False)

print("\n-- expectation_check --", flush=True)
check_sql = (
    "with actual as ("
    "select job_code, instance_status from batch.job_instance "
    f"where tenant_id='ta' and job_code in ({job_codes}) and created_at >= '{START_TS}'"
    "), expected(job_code, expected_status) as (values "
    "('TA_PROCESS_STAGE4_JSONB','SUCCESS'),"
    "('TA_PROCESS_STAGE4_DIRECT','SUCCESS'),"
    "('TA_PROCESS_STAGE4_VALIDATE_FAIL','FAILED'),"
    "('TA_PROCESS_STAGE4_EMPTY_SUCCESS','SUCCESS')) "
    "select e.job_code,e.expected_status,coalesce(a.instance_status,'MISSING') as actual_status,"
    "(coalesce(a.instance_status,'MISSING') = e.expected_status) as ok "
    "from expected e left join actual a using(job_code) order by e.job_code"
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
print(f"\n==> Stage 4 process scenario submitted: batchNo={BATCH} startTs={START_TS}", flush=True)
if failures:
    raise SystemExit(f"Stage 4 process expectation failed: {failures} mismatch(es)")
PY
