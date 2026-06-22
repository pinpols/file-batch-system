#!/usr/bin/env bash
# =========================================================
# 13-process-stage4b.sh:Process 幂等重跑/失败恢复业务分支验证
#
# 覆盖:
#   - 稳定 batchKey 重跑前清理同 key 残留 staging
#   - JSONB staging -> UPSERT target 幂等更新,不重复
#   - COMMIT/FEEDBACK 后 staging 清理为 0
#
# 前置:Stage 4 基础 fixture 已存在(TA_PROCESS_STAGE4_JSONB)。
# SQL seed 独立放在 docs/test-data,脚本只负责编排、触发、轮询、断言。
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

SIM_STAGE_NAME="process-stage4b"
# shellcheck source=env-common.sh
source "$ROOT/scripts/sim/env-common.sh"

export BATCH_KEY="${BATCH_KEY:-$BATCH_NO-jsonb-idempotent}"

command -v python3 >/dev/null 2>&1 || { echo "❌ 需要 python3" >&2; exit 1; }

echo "==> preflight process stage4 job"
if [[ "$(docker exec -i "$PG_CONTAINER" psql -U "$POSTGRES_USER" -d "$PLATFORM_DB" -tAc "select count(*) from batch.job_definition where tenant_id='ta' and job_code='TA_PROCESS_STAGE4_JSONB' and enabled=true")" != "1" ]]; then
  echo "❌ missing TA_PROCESS_STAGE4_JSONB fixture; run scripts/sim/10-process-stage4.sh once or apply its fixture" >&2
  exit 1
fi

echo "==> seed process source v1 + stale staging"
docker exec -i "$PG_CONTAINER" psql -U "$POSTGRES_USER" -d "$BUSINESS_DB" \
  -v ON_ERROR_STOP=1 -v biz_date="$BIZ_DATE" \
  -f /dev/stdin < docs/test-data/sim-stage4b-process-source-v1.sql >/dev/null
docker exec -i "$PG_CONTAINER" psql -U "$POSTGRES_USER" -d "$BUSINESS_DB" \
  -v ON_ERROR_STOP=1 -v batch_key="$BATCH_KEY" \
  -f /dev/stdin < docs/test-data/sim-stage4b-process-stale-staging.sql >/dev/null

START_TS="$(docker exec -i "$PG_CONTAINER" psql -U "$POSTGRES_USER" -d "$PLATFORM_DB" -tAc "select now()")"
export START_TS

python3 - <<'PY' 2>&1 | tee "$REPORT_DIR/process-stage4b.log"
import json, os, subprocess, sys, time, urllib.request

BASE = os.environ["TRIGGER_BASE"]
SECRET = os.environ["INTERNAL_SECRET"]
BIZ = os.environ["BIZ_DATE"]
BATCH = os.environ["BATCH_NO"]
BATCH_KEY = os.environ["BATCH_KEY"]

def run(cmd, **kwargs):
    return subprocess.run(cmd, check=False, capture_output=True, text=True, **kwargs)

def psql(db, sql, tuples=False):
    args = ["docker", "exec", os.environ.get("PG_CONTAINER", "batch-postgres-primary"), "psql", "-U", os.environ.get("POSTGRES_USER", "batch_user"), "-d", db, "-P", "pager=off"]
    if tuples:
        args += ["-t", "-A"]
    args += ["-c", sql]
    return run(args)

def launch(label):
    rid = f"sim-stage4b-{label}-{int(time.time()*1000)%100000000}"
    body = {
        "tenantId": "ta",
        "jobCode": "TA_PROCESS_STAGE4_JSONB",
        "triggerType": "API",
        "bizDate": BIZ,
        "requestId": rid,
        "params": {
            "batchNo": BATCH,
            "batchKey": BATCH_KEY,
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
        print(f"  [launch] {label:10s} {'✓' if ok else '✗'}", flush=True)
        if not ok:
            print(text[:500], flush=True)
            sys.exit(1)
    return rid

def wait_success(rid):
    deadline = time.time() + 150
    while time.time() < deadline:
        out = psql(os.environ.get("PLATFORM_DB", "batch_platform"), (
            "select coalesce(i.instance_status,'') "
            "from batch.trigger_request tr "
            "left join batch.job_instance i on i.id=tr.related_job_instance_id "
            f"where tr.tenant_id='ta' and tr.request_id='{rid}' "
            "order by tr.created_at desc limit 1"
        ), tuples=True)
        status = (out.stdout or "").strip()
        if status in ("SUCCESS", "FAILED", "PARTIAL_FAILED", "REJECTED", "CANCELLED"):
            print(f"  [result] {rid} {status}", flush=True)
            if status != "SUCCESS":
                sys.exit(1)
            return
        time.sleep(3)
    raise TimeoutError(f"timeout waiting {rid}")

rid1 = launch("first")
wait_success(rid1)

print("==> switch source to v2 and rerun same batchKey", flush=True)
run([
    "docker", "exec", "-i", os.environ.get("PG_CONTAINER", "batch-postgres-primary"), "psql", "-U", os.environ.get("POSTGRES_USER", "batch_user"),
    "-d", os.environ.get("BUSINESS_DB", "batch_business"), "-v", "ON_ERROR_STOP=1", "-v", f"biz_date={BIZ}", "-f", "/dev/stdin"
], input=open("docs/test-data/sim-stage4b-process-source-v2.sql").read())

rid2 = launch("rerun")
wait_success(rid2)

print("\n-- target_rows --", flush=True)
target_sql = (
    "select scenario,account_id,total_amount,event_count,high_water_mark "
    "from biz.process_stage4_target "
    "where tenant_id='ta' and scenario='JSONB' and biz_date='" + BIZ + "' "
    "order by account_id"
)
subprocess.run([
    "docker", "exec", os.environ.get("PG_CONTAINER", "batch-postgres-primary"), "psql", "-U", os.environ.get("POSTGRES_USER", "batch_user"),
    "-d", os.environ.get("BUSINESS_DB", "batch_business"), "-P", "pager=off", "-c", target_sql
], check=False)

print("\n-- staging_leftover --", flush=True)
staging_sql = (
    "select count(*) from batch.process_staging "
    f"where tenant_id='ta' and batch_key='{BATCH_KEY}' "
    "and target_schema='biz' and target_table='process_stage4_target'"
)
subprocess.run([
    "docker", "exec", os.environ.get("PG_CONTAINER", "batch-postgres-primary"), "psql", "-U", os.environ.get("POSTGRES_USER", "batch_user"),
    "-d", os.environ.get("BUSINESS_DB", "batch_business"), "-P", "pager=off", "-c", staging_sql
], check=False)

target_assert_sql = (
    "select count(*) || '|' || "
    "coalesce(sum(total_amount),0) || '|' || "
    "coalesce(sum(event_count),0) || '|' || "
    "coalesce(max(high_water_mark),0) "
    "from biz.process_stage4_target "
    "where tenant_id='ta' and scenario='JSONB' and biz_date='" + BIZ + "'"
)
staging_assert_sql = (
    "select count(*) from batch.process_staging "
    f"where tenant_id='ta' and batch_key='{BATCH_KEY}' "
    "and target_schema='biz' and target_table='process_stage4_target'"
)
target_out = psql(os.environ.get("BUSINESS_DB", "batch_business"), target_assert_sql, tuples=True)
staging_out = psql(os.environ.get("BUSINESS_DB", "batch_business"), staging_assert_sql, tuples=True)
summary = (target_out.stdout or "").strip() + "|" + (staging_out.stdout or "").strip()
print(f"\n-- assertion_summary --\n{summary}", flush=True)
if summary != "2|400.00|3|203|0":
    print("❌ Process Stage4b assertion failed, expected 2|400.00|3|203|0", flush=True)
    sys.exit(1)

print(f"\n==> Stage 4b process scenario PASS: batchNo={BATCH} batchKey={BATCH_KEY}", flush=True)
PY
