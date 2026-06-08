#!/usr/bin/env bash
# =========================================================
# 12-export-stage3b.sh:Export 分片/keyset-range 业务分支验证
#
# 覆盖:
#   - STATIC 4 分片系统级导出
#   - query_param_schema.partition_keyset_range=true opt-in
#   - 4 个 task / 4 个 file_record / 文件名 _pNof4 / recordCount 汇总
#
# 触发方式:Trigger API -> Orchestrator -> Kafka -> worker-export。
# SQL fixture 独立放在 docs/test-data,脚本只负责编排、触发、轮询、断言。
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

export TRIGGER_BASE="${TRIGGER_BASE:-http://localhost:18081}"
if [[ -z "${BATCH_INTERNAL_SECRET:-}" && -f .env.local ]]; then
  BATCH_INTERNAL_SECRET="$(grep -E '^BATCH_INTERNAL_SECRET=' .env.local | tail -1 | cut -d= -f2- || true)"
fi
export INTERNAL_SECRET="${BATCH_INTERNAL_SECRET:-internal-secret}"
export BIZ_DATE="${BIZ_DATE:-$(date +%Y-%m-%d)}"
export BATCH_NO="${BATCH_NO:-sim-export-stage3b-$(date +%Y%m%d%H%M%S)}"
export RUN_ID="${RUN_ID:-export-stage3b-$(date +%Y%m%d%H%M%S)}"
export REPORT_DIR="${REPORT_DIR:-load-tests/target/$RUN_ID}"
mkdir -p "$REPORT_DIR"

command -v python3 >/dev/null 2>&1 || { echo "❌ 需要 python3" >&2; exit 1; }

echo "==> apply bootstrap + stage3b fixtures"
docker exec -i batch-postgres-primary psql -U batch_user -d batch_platform \
  -v ON_ERROR_STOP=1 -f /dev/stdin < docs/test-data/sim-e2e-bootstrap.sql >/dev/null
docker exec -i batch-postgres-primary psql -U batch_user -d batch_platform \
  -v ON_ERROR_STOP=1 -f /dev/stdin < docs/test-data/sim-stage3b-export-fixtures.sql >/dev/null

echo "==> seed export partition source rows"
docker exec -i batch-postgres-primary psql -U batch_user -d batch_business \
  -v ON_ERROR_STOP=1 -v batch_no="$BATCH_NO" \
  -f /dev/stdin < docs/test-data/sim-stage3b-export-source.sql >/dev/null

START_TS="$(docker exec -i batch-postgres-primary psql -U batch_user -d batch_platform -tAc "select now()")"
export START_TS

python3 - <<'PY' 2>&1 | tee "$REPORT_DIR/export-stage3b.log"
import json, os, subprocess, sys, time, urllib.request

BASE = os.environ["TRIGGER_BASE"]
SECRET = os.environ["INTERNAL_SECRET"]
BIZ = os.environ["BIZ_DATE"]
BATCH = os.environ["BATCH_NO"]
START_TS = os.environ["START_TS"].strip()

def psql(db, sql, tuples=False):
    args = [
        "docker", "exec", "batch-postgres-primary", "psql", "-U", "batch_user",
        "-d", db, "-P", "pager=off"
    ]
    if tuples:
        args += ["-t", "-A"]
    args += ["-c", sql]
    return subprocess.run(args, check=False, capture_output=True, text=True)

rid = f"sim-stage3b-keyset4-{int(time.time()*1000)%100000000}"
body = {
    "tenantId": "ta",
    "jobCode": "TA_EXPORT_REPORT_STATIC",
    "triggerType": "API",
    "bizDate": BIZ,
    "requestId": rid,
    "params": {
        "templateCode": "TA_EXPORT_REPORT_JSON_KEYSET_TPL",
        "batchNo": BATCH,
        "bizType": "TA_EXPORT_REPORT_JSON_KEYSET",
        "partitionCount": 4,
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
    print(f"  [launch] TA_EXPORT_REPORT_STATIC partitionCount=4 {'✓' if ok else '✗'}", flush=True)
    if not ok:
        print(text[:500], flush=True)
        sys.exit(1)

deadline = time.time() + 180
instance_id = None
while time.time() < deadline:
    out = psql("batch_platform", (
        "select i.id || '|' || i.instance_status "
        "from batch.trigger_request tr "
        "join batch.job_instance i on i.id = tr.related_job_instance_id "
        f"where tr.tenant_id='ta' and tr.request_id='{rid}' "
        "order by tr.created_at desc limit 1"
    ), tuples=True)
    value = (out.stdout or "").strip()
    if value:
        iid, status = value.split("|", 1)
        if status in ("SUCCESS", "FAILED", "PARTIAL_FAILED", "REJECTED", "CANCELLED"):
            instance_id = iid
            print(f"  [result] instance={iid} status={status}", flush=True)
            if status != "SUCCESS":
                sys.exit(1)
            break
    time.sleep(3)
if not instance_id:
    raise TimeoutError("timeout waiting export stage3b")

print("\n-- task_status --", flush=True)
task_sql = (
    "select p.partition_no,p.partition_status,t.task_status,t.error_code,"
    "left(coalesce(t.error_message,''),160) as error_message "
    "from batch.job_partition p "
    "left join batch.job_task t on t.job_partition_id=p.id "
    f"where p.job_instance_id={instance_id} "
    "order by p.partition_no,t.id"
)
subprocess.run([
    "docker", "exec", "batch-postgres-primary", "psql", "-U", "batch_user",
    "-d", "batch_platform", "-P", "pager=off", "-c", task_sql
], check=False)

print("\n-- file_records --", flush=True)
file_sql = (
    "select file_name,file_status,file_size_bytes,metadata_json->>'recordCount' as record_count,"
    "storage_path "
    "from batch.file_record "
    f"where tenant_id='ta' and source_ref='{BATCH}' and source_type='GENERATED' "
    "order by file_name"
)
subprocess.run([
    "docker", "exec", "batch-postgres-primary", "psql", "-U", "batch_user",
    "-d", "batch_platform", "-P", "pager=off", "-c", file_sql
], check=False)

check_sql = (
    "with tasks as ("
    f"select count(*) filter (where t.task_status='SUCCESS') as success_tasks "
    "from batch.job_task t join batch.job_partition p on p.id=t.job_partition_id "
    f"where p.job_instance_id={instance_id}"
    "), files as ("
    "select count(*) as file_count, "
    "count(*) filter (where file_name ~ '_p[1-4]of4\\.json$') as tagged_files, "
    "coalesce(sum((metadata_json->>'recordCount')::int),0) as exported_rows "
    "from batch.file_record "
    f"where tenant_id='ta' and source_ref='{BATCH}' and source_type='GENERATED'"
    ") select success_tasks, file_count, tagged_files, exported_rows "
    "from tasks cross join files"
)
out = psql("batch_platform", check_sql, tuples=True)
summary = (out.stdout or "").strip()
print(f"\n-- assertion_summary --\n{summary}", flush=True)
if summary != "4|4|4|40":
    print("❌ Export Stage3b assertion failed, expected 4|4|4|40", flush=True)
    sys.exit(1)

print(f"\n==> Stage 3b export scenario PASS: batchNo={BATCH} instance={instance_id} startTs={START_TS}", flush=True)
PY
