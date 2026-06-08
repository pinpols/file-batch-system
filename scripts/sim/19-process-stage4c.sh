#!/usr/bin/env bash
# =========================================================
# 19-process-stage4c.sh:Process 分片 + 取消验证
#
# 覆盖:
#   - STATIC 4 分片 process,SQL 使用 :partitionNo/:partitionCount 切分数据
#   - 每个分片 staged/published 4 行,总目标 16 行
#   - 长 SQL 运行中通过 internal cancel API 置取消态
#
# 注意:分片 SQL 参数需要 worker-process 加载包含 partition 参数透传的代码。
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

export TRIGGER_BASE="${TRIGGER_BASE:-http://localhost:18081}"
export ORCH_BASE="${ORCH_BASE:-http://localhost:18082}"
if [[ -z "${BATCH_INTERNAL_SECRET:-}" && -f .env.local ]]; then
  BATCH_INTERNAL_SECRET="$(grep -E '^BATCH_INTERNAL_SECRET=' .env.local | tail -1 | cut -d= -f2- || true)"
fi
export INTERNAL_SECRET="${BATCH_INTERNAL_SECRET:-internal-secret}"
export BIZ_DATE="${BIZ_DATE:-$(date +%Y-%m-%d)}"
export BATCH_NO="${BATCH_NO:-sim-process-stage4c-$(date +%Y%m%d%H%M%S)}"
export RUN_ID="${RUN_ID:-process-stage4c-$(date +%Y%m%d%H%M%S)}"
export REPORT_DIR="${REPORT_DIR:-load-tests/target/$RUN_ID}"
mkdir -p "$REPORT_DIR"

command -v python3 >/dev/null 2>&1 || { echo "❌ 需要 python3" >&2; exit 1; }

echo "==> seed process stage4c fixtures"
docker exec -i batch-postgres-primary psql -U batch_user -d batch_business \
  -v ON_ERROR_STOP=1 -v biz_date="$BIZ_DATE" \
  -f /dev/stdin < docs/test-data/sim-stage4c-process-business.sql >/dev/null
docker exec -i batch-postgres-primary psql -U batch_user -d batch_platform \
  -v ON_ERROR_STOP=1 \
  -f /dev/stdin < docs/test-data/sim-stage4c-process-platform.sql >/dev/null

START_TS="$(docker exec -i batch-postgres-primary psql -U batch_user -d batch_platform -tAc "select now()")"
export START_TS

python3 - <<'PY' 2>&1 | tee "$REPORT_DIR/process-stage4c.log"
import json, os, subprocess, sys, time, urllib.error, urllib.parse, urllib.request

BASE = os.environ["TRIGGER_BASE"]
ORCH = os.environ["ORCH_BASE"]
SECRET = os.environ["INTERNAL_SECRET"]
BIZ = os.environ["BIZ_DATE"]
BATCH = os.environ["BATCH_NO"]
START_TS = os.environ["START_TS"].strip()

def psql(db, sql, tuples=False):
    args = ["docker", "exec", "batch-postgres-primary", "psql", "-U", "batch_user", "-d", db, "-P", "pager=off"]
    if tuples:
        args += ["-t", "-A"]
    args += ["-c", sql]
    return subprocess.run(args, check=False, capture_output=True, text=True)

def launch(job, label, params):
    rid = f"sim-stage4c-{label}-{int(time.time()*1000)%100000000}"
    body = {
        "tenantId": "ta",
        "jobCode": job,
        "triggerType": "API",
        "bizDate": BIZ,
        "requestId": rid,
        "params": params,
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
        print(f"  [launch] {job:28s} {'✓' if ok else '✗'}", flush=True)
        if not ok:
            print(text[:500], flush=True)
            raise RuntimeError(f"launch failed: {job}")
    return rid

def wait_instance(rid, expected, timeout=240):
    deadline = time.time() + timeout
    while time.time() < deadline:
        out = psql("batch_platform", (
            "select i.id || '|' || coalesce(i.instance_status,'') "
            "from batch.trigger_request tr join batch.job_instance i on i.id=tr.related_job_instance_id "
            f"where tr.tenant_id='ta' and tr.request_id='{rid}' order by tr.created_at desc limit 1"
        ), tuples=True)
        value = (out.stdout or "").strip()
        if value:
            iid, status = value.split("|", 1)
            if status in ("SUCCESS", "FAILED", "PARTIAL_FAILED", "REJECTED", "CANCELLED"):
                print(f"  [result] instance={iid} status={status} expected={expected}", flush=True)
                if status != expected:
                    sys.exit(1)
                return iid
        time.sleep(3)
    raise TimeoutError(f"timeout waiting {rid}")

print("==> launch sharded process", flush=True)
rid_shard = launch("TA_PROCESS_STAGE4_SHARDED", "sharded", {
    "batchNo": BATCH,
    "bizDate": BIZ,
    "partitionCount": 4,
})
shard_instance = wait_instance(rid_shard, "SUCCESS")

print("\n-- sharded_task_status --", flush=True)
subprocess.run([
    "docker", "exec", "batch-postgres-primary", "psql", "-U", "batch_user",
    "-d", "batch_platform", "-P", "pager=off", "-c",
    "select p.partition_no,p.partition_status,t.task_status,p.output_summary "
    "from batch.job_partition p join batch.job_task t on t.job_partition_id=p.id "
    f"where p.job_instance_id={shard_instance} order by p.partition_no"
], check=False)

print("\n-- sharded_target --", flush=True)
subprocess.run([
    "docker", "exec", "batch-postgres-primary", "psql", "-U", "batch_user",
    "-d", "batch_business", "-P", "pager=off", "-c",
    "select count(*) as rows, sum(total_amount) as amount, sum(event_count) as events, max(high_water_mark) as hwm "
    "from biz.process_stage4_target where tenant_id='ta' and scenario='SHARDED' and biz_date='" + BIZ + "'"
], check=False)

shard_check = (psql("batch_business", (
    "select count(*) || '|' || coalesce(sum(total_amount),0) || '|' || "
    "coalesce(sum(event_count),0) || '|' || coalesce(max(high_water_mark),0) "
    "from biz.process_stage4_target "
    f"where tenant_id='ta' and scenario='SHARDED' and biz_date='{BIZ}'"
), tuples=True).stdout or "").strip()
task_check = (psql("batch_platform", (
    "select count(*) filter (where t.task_status='SUCCESS') || '|' || "
    "count(*) filter (where p.partition_status='SUCCESS') "
    "from batch.job_partition p join batch.job_task t on t.job_partition_id=p.id "
    f"where p.job_instance_id={shard_instance}"
), tuples=True).stdout or "").strip()

print("==> launch cancel profile", flush=True)
rid_cancel = launch("TA_PROCESS_STAGE4_CANCEL", "cancel", {
    "batchNo": BATCH,
    "batchKey": BATCH + "-cancel",
    "bizDate": BIZ,
})

cancel_instance = None
cancel_partition = None
deadline = time.time() + 30
while time.time() < deadline:
    out = psql("batch_platform", (
        "select i.id || '|' || p.id || '|' || coalesce(t.task_status,'') "
        "from batch.trigger_request tr "
        "join batch.job_instance i on i.id=tr.related_job_instance_id "
        "join batch.job_partition p on p.job_instance_id=i.id "
        "join batch.job_task t on t.job_partition_id=p.id "
        f"where tr.tenant_id='ta' and tr.request_id='{rid_cancel}' "
        "order by t.id desc limit 1"
    ), tuples=True)
    value = (out.stdout or "").strip()
    if value:
        iid, pid, status = value.split("|", 2)
        cancel_instance, cancel_partition = iid, pid
        if status == "RUNNING":
            break
    time.sleep(1)
if not cancel_instance:
    raise TimeoutError("cancel instance not materialized")

url = f"{ORCH}/internal/instances/{cancel_instance}/cancel?tenantId=ta"
req = urllib.request.Request(
    url,
    data=b"",
    method="POST",
    headers={"X-Internal-Secret": SECRET},
)
cancel_http = ""
cancel_body = ""
try:
    with urllib.request.urlopen(req, timeout=30) as resp:
        cancel_http = str(resp.status)
        cancel_body = resp.read().decode()
except urllib.error.HTTPError as ex:
    cancel_http = str(ex.code)
    cancel_body = ex.read().decode()
print(f"  [cancel] instance={cancel_instance} http={cancel_http} body={cancel_body[:180]}", flush=True)

deadline = time.time() + 90
cancel_status = ""
while time.time() < deadline:
    out = psql("batch_platform", (
        "select i.instance_status || '|' || p.partition_status || '|' || t.task_status "
        "from batch.job_instance i "
        "join batch.job_partition p on p.job_instance_id=i.id "
        "join batch.job_task t on t.job_partition_id=p.id "
        f"where i.id={cancel_instance} order by t.id desc limit 1"
    ), tuples=True)
    cancel_status = (out.stdout or "").strip()
    if cancel_status and not any(x in cancel_status for x in ("RUNNING", "READY", "CREATED")):
        break
    time.sleep(3)

print("\n-- cancel_status --", flush=True)
subprocess.run([
    "docker", "exec", "batch-postgres-primary", "psql", "-U", "batch_user",
    "-d", "batch_platform", "-P", "pager=off", "-c",
    "select i.id,i.instance_status,p.partition_status,t.task_status,t.cancel_requested,t.error_code "
    "from batch.job_instance i join batch.job_partition p on p.job_instance_id=i.id "
    "join batch.job_task t on t.job_partition_id=p.id "
    f"where i.id={cancel_instance}"
], check=False)

summary = f"{task_check}|{shard_check}|{cancel_status}"
print(f"\n-- assertion_summary --\n{summary}", flush=True)
if not summary.startswith("4|4|16|296.00|16|16|"):
    print("❌ Process Stage4c sharded assertion failed", flush=True)
    sys.exit(1)
if cancel_http not in ("200", "409"):
    print("❌ Process Stage4c cancel API assertion failed", flush=True)
    sys.exit(1)

print(f"\n==> Stage 4c process scenario PASS: batchNo={BATCH} startTs={START_TS}", flush=True)
PY
