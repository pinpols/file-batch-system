#!/usr/bin/env bash
# =========================================================
# 22-trigger-stage6c.sh:Trigger scheduled / misfire / replay / storm 验证
#
# 覆盖:
#   - wheel scheduled fire 真实落 trigger_request
#   - MANUAL_APPROVAL misfire 真实落 trigger_misfire_pending
#   - catch-up approve replay API 将 ACCEPTED CATCH_UP request 推进到 LAUNCHED
#   - API task storm 收敛
#
# SQL seed 独立放在 docs/test-data,脚本只负责编排、触发、轮询、断言。
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
export BATCH_NO="${BATCH_NO:-sim-trigger-stage6c-$(date +%Y%m%d%H%M%S)}"
export STORM_COUNT="${STORM_COUNT:-60}"
export RUN_ID="${RUN_ID:-trigger-stage6c-$(date +%Y%m%d%H%M%S)}"
export REPORT_DIR="${REPORT_DIR:-load-tests/target/$RUN_ID}"
mkdir -p "$REPORT_DIR"

command -v python3 >/dev/null 2>&1 || { echo "❌ 需要 python3" >&2; exit 1; }

echo "==> seed trigger stage6c fixtures"
docker exec -i batch-postgres-primary psql -U batch_user -d batch_platform \
  -v ON_ERROR_STOP=1 -v batch_no="$BATCH_NO" -v biz_date="$BIZ_DATE" \
  -f /dev/stdin < docs/test-data/sim-stage6c-trigger-fixtures.sql >/dev/null
START_TS="$(docker exec -i batch-postgres-primary psql -U batch_user -d batch_platform -tAc "select now()")"
export START_TS

python3 - <<'PY' 2>&1 | tee "$REPORT_DIR/trigger-stage6c.log"
import json, os, subprocess, sys, time, urllib.request

BASE = os.environ["TRIGGER_BASE"]
SECRET = os.environ["INTERNAL_SECRET"]
BIZ = os.environ["BIZ_DATE"]
BATCH = os.environ["BATCH_NO"]
STORM_COUNT = int(os.environ["STORM_COUNT"])
START_TS = os.environ["START_TS"].strip()

def psql(sql, tuples=False):
    args = ["docker", "exec", "batch-postgres-primary", "psql", "-U", "batch_user", "-d", "batch_platform", "-P", "pager=off"]
    if tuples:
        args += ["-t", "-A"]
    args += ["-c", sql]
    return subprocess.run(args, check=False, capture_output=True, text=True)

def launch(request_id, batch_key):
    body = {
        "tenantId": "ta",
        "jobCode": "TA_PROCESS_STAGE4_EMPTY_SUCCESS",
        "triggerType": "API",
        "bizDate": BIZ,
        "requestId": request_id,
        "params": {"batchNo": BATCH, "batchKey": batch_key, "bizDate": BIZ},
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
        if resp.status != 200 or '"SUCCESS"' not in text:
            print(text[:500], flush=True)
            raise RuntimeError(f"launch failed: {request_id}")

def wait_count(label, sql, expected, timeout=150):
    deadline = time.time() + timeout
    while time.time() < deadline:
        out = psql(sql, tuples=True)
        value = int((out.stdout or "0").strip() or "0")
        if value >= expected:
            print(f"  [{label}] {value} >= {expected}", flush=True)
            return value
        time.sleep(3)
    raise TimeoutError(f"timeout waiting {label}")

print("==> wait wheel scheduled + misfire", flush=True)
scheduled_count = wait_count(
    "scheduled",
    "select count(*) from batch.trigger_request "
    "where tenant_id='ta' and job_code='TA_TRIGGER_STAGE6C_SCHEDULED' "
    "and trigger_type='SCHEDULED' and created_at >= '" + START_TS + "'",
    1,
)
misfire_count = wait_count(
    "misfire-pending",
    "select count(*) from batch.trigger_misfire_pending "
    "where tenant_id='ta' and job_code='TA_TRIGGER_STAGE6C_MISFIRE' "
    "and status='PENDING' and created_at >= '" + START_TS + "'",
    1,
)

print("==> approve replay request", flush=True)
replay_id = BATCH + "-replay"
req = urllib.request.Request(
    f"{BASE}/api/triggers/catch-up/approve",
    data=json.dumps({"tenantId": "ta", "requestId": replay_id, "reason": "sim-stage6c"}).encode(),
    headers={
        "Content-Type": "application/json",
        "X-Tenant-Id": "ta",
        "X-Internal-Secret": SECRET,
        "Idempotency-Key": replay_id + "-approve",
        "X-Request-Id": replay_id + "-approve",
    },
)
with urllib.request.urlopen(req, timeout=30) as resp:
    text = resp.read().decode()
    ok = resp.status == 200 and '"SUCCESS"' in text
    print(f"  [approve] {'✓' if ok else '✗'}", flush=True)
    if not ok:
        print(text[:500], flush=True)
        sys.exit(1)

deadline = time.time() + 120
replay_status = ""
while time.time() < deadline:
    out = psql(
        "select request_status || '|' || coalesce(related_job_instance_id::text,'') "
        f"from batch.trigger_request where tenant_id='ta' and request_id='{replay_id}'",
        tuples=True,
    )
    replay_status = (out.stdout or "").strip()
    if replay_status.startswith("LAUNCHED|") and replay_status != "LAUNCHED|":
        break
    time.sleep(3)

print(f"  [replay] {replay_status}", flush=True)

print(f"==> API task storm {STORM_COUNT}", flush=True)
storm_ids = []
for i in range(STORM_COUNT):
    rid = f"{BATCH}-storm-{i:03d}"
    storm_ids.append(rid)
    launch(rid, f"{BATCH}-storm-{i:03d}")
print("  [storm launch] all accepted", flush=True)

deadline = time.time() + 240
while time.time() < deadline:
    out = psql(
        "select count(*) from batch.trigger_request tr "
        "join batch.job_instance i on i.id=tr.related_job_instance_id "
        "where tr.tenant_id='ta' and tr.request_id like '" + BATCH + "-storm-%' "
        "and i.instance_status in ('SUCCESS','FAILED','PARTIAL_FAILED','REJECTED','CANCELLED')",
        tuples=True,
    )
    done = int((out.stdout or "0").strip() or "0")
    if done >= STORM_COUNT:
        break
    time.sleep(3)

print("\n-- trigger_stage6c_status --", flush=True)
subprocess.run([
    "docker", "exec", "batch-postgres-primary", "psql", "-U", "batch_user",
    "-d", "batch_platform", "-P", "pager=off", "-c",
    "select trigger_type,job_code,request_status,count(*) "
    "from batch.trigger_request "
    "where tenant_id='ta' and (request_id like '" + BATCH + "%' or created_at >= '" + START_TS + "') "
    "group by trigger_type,job_code,request_status order by trigger_type,job_code,request_status"
], check=False)

storm_success = int((psql(
    "select count(*) from batch.trigger_request tr "
    "join batch.job_instance i on i.id=tr.related_job_instance_id "
    "where tr.tenant_id='ta' and tr.request_id like '" + BATCH + "-storm-%' "
    "and i.instance_status='SUCCESS'",
    tuples=True,
).stdout or "0").strip() or "0")

summary = f"scheduled={scheduled_count}|misfire={misfire_count}|replay={replay_status}|storm={storm_success}/{STORM_COUNT}"
print(f"\n-- assertion_summary --\n{summary}", flush=True)
if scheduled_count < 1 or misfire_count < 1 or not replay_status.startswith("LAUNCHED|") or replay_status == "LAUNCHED|" or storm_success < STORM_COUNT:
    print("❌ Trigger Stage6c assertion failed", flush=True)
    sys.exit(1)

print(f"\n==> Stage 6c trigger scenario PASS: batchNo={BATCH} stormCount={STORM_COUNT}", flush=True)
PY
