#!/usr/bin/env bash
# =========================================================
# 15-trigger-stage6b.sh:Trigger 去重 + 小规模 storm 验证
#
# 覆盖:
#   - 相同 requestId / Idempotency-Key 重复 launch 只落一个 trigger_request/job_instance
#   - 多 requestId 批量触发后终态收敛
#
# 触发方式:Trigger API -> Orchestrator -> Kafka -> worker-process。
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
export BATCH_NO="${BATCH_NO:-sim-trigger-stage6b-$(date +%Y%m%d%H%M%S)}"
export STORM_COUNT="${STORM_COUNT:-30}"
export RUN_ID="${RUN_ID:-trigger-stage6b-$(date +%Y%m%d%H%M%S)}"
export REPORT_DIR="${REPORT_DIR:-load-tests/target/$RUN_ID}"
mkdir -p "$REPORT_DIR"

command -v python3 >/dev/null 2>&1 || { echo "❌ 需要 python3" >&2; exit 1; }

echo "==> preflight trigger stage6 job"
if [[ "$(docker exec -i batch-postgres-primary psql -U batch_user -d batch_platform -tAc "select count(*) from batch.job_definition where tenant_id='ta' and job_code='TA_PROCESS_STAGE4_EMPTY_SUCCESS' and enabled=true")" != "1" ]]; then
  echo "❌ missing TA_PROCESS_STAGE4_EMPTY_SUCCESS fixture; run scripts/sim/10-process-stage4.sh once or apply its fixture" >&2
  exit 1
fi

START_TS="$(docker exec -i batch-postgres-primary psql -U batch_user -d batch_platform -tAc "select now()")"
export START_TS

python3 - <<'PY' 2>&1 | tee "$REPORT_DIR/trigger-stage6b.log"
import json, os, subprocess, sys, time, urllib.request

BASE = os.environ["TRIGGER_BASE"]
SECRET = os.environ["INTERNAL_SECRET"]
BIZ = os.environ["BIZ_DATE"]
BATCH = os.environ["BATCH_NO"]
STORM_COUNT = int(os.environ["STORM_COUNT"])
START_TS = os.environ["START_TS"].strip()
JOB = "TA_PROCESS_STAGE4_EMPTY_SUCCESS"

def launch(request_id, batch_key):
    body = {
        "tenantId": "ta",
        "jobCode": JOB,
        "triggerType": "API",
        "bizDate": BIZ,
        "requestId": request_id,
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
            "Idempotency-Key": request_id,
            "X-Request-Id": request_id,
        },
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        text = resp.read().decode()
        ok = resp.status == 200 and '"SUCCESS"' in text
        if not ok:
            print(text[:500], flush=True)
            raise RuntimeError(f"launch failed: {request_id}")

def psql(sql, tuples=False):
    args = ["docker", "exec", "batch-postgres-primary", "psql", "-U", "batch_user", "-d", "batch_platform", "-P", "pager=off"]
    if tuples:
        args += ["-t", "-A"]
    args += ["-c", sql]
    return subprocess.run(args, check=False, capture_output=True, text=True)

dedup_id = f"sim-stage6b-dedup-{int(time.time()*1000)%100000000}"
print("==> dedup launch same requestId twice", flush=True)
launch(dedup_id, BATCH + "-dedup")
launch(dedup_id, BATCH + "-dedup")

storm_ids = []
print(f"==> storm launch {STORM_COUNT}", flush=True)
for i in range(STORM_COUNT):
    rid = f"sim-stage6b-storm-{i:03d}-{int(time.time()*1000)%100000000}"
    storm_ids.append(rid)
    launch(rid, f"{BATCH}-storm-{i:03d}")
print("  [launch] all accepted", flush=True)

deadline = time.time() + 240
while time.time() < deadline:
    out = psql(
        "select count(*) from batch.job_instance "
        f"where tenant_id='ta' and job_code='{JOB}' and created_at >= '{START_TS}' "
        "and instance_status in ('SUCCESS','FAILED','PARTIAL_FAILED','REJECTED','CANCELLED')",
        tuples=True,
    )
    done = int((out.stdout or "0").strip() or "0")
    if done >= STORM_COUNT + 1:
        break
    time.sleep(3)

print("\n-- dedup_check --", flush=True)
dedup_sql = (
    "select count(*) as trigger_rows, count(distinct related_job_instance_id) as instances "
    f"from batch.trigger_request where tenant_id='ta' and request_id='{dedup_id}'"
)
subprocess.run([
    "docker", "exec", "batch-postgres-primary", "psql", "-U", "batch_user",
    "-d", "batch_platform", "-P", "pager=off", "-c", dedup_sql
], check=False)

print("\n-- storm_status --", flush=True)
storm_sql = (
    "select instance_status,count(*) "
    "from batch.job_instance "
    f"where tenant_id='ta' and job_code='{JOB}' and created_at >= '{START_TS}' "
    "group by instance_status order by instance_status"
)
subprocess.run([
    "docker", "exec", "batch-postgres-primary", "psql", "-U", "batch_user",
    "-d", "batch_platform", "-P", "pager=off", "-c", storm_sql
], check=False)

dedup_out = psql(dedup_sql.replace(" as trigger_rows", "").replace(" as instances", ""), tuples=True)
storm_out = psql(
    "select count(*) from batch.job_instance "
    f"where tenant_id='ta' and job_code='{JOB}' and created_at >= '{START_TS}' "
    "and instance_status='SUCCESS'",
    tuples=True,
)
dedup_summary = (dedup_out.stdout or "").strip().replace("|", "|")
success_count = int((storm_out.stdout or "0").strip() or "0")
summary = f"{dedup_summary}|{success_count}"
print(f"\n-- assertion_summary --\n{summary}", flush=True)
if dedup_summary != "1|1" or success_count < STORM_COUNT + 1:
    print(f"❌ Trigger Stage6b assertion failed, expected dedup=1|1 and success>={STORM_COUNT + 1}", flush=True)
    sys.exit(1)

print(f"\n==> Stage 6b trigger scenario PASS: batchNo={BATCH} stormCount={STORM_COUNT}", flush=True)
PY
