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

export SIM_STAGE_NAME="process-stage4b"
# shellcheck source=env-common.sh
source "$ROOT/scripts/sim/env-common.sh"

export BATCH_KEY="${BATCH_KEY:-$BATCH_NO-jsonb-idempotent}"

batch_require_python
SQL_DIR="$ROOT/scripts/sim/sql"

echo "==> preflight process stage4 job"
if [[ "$(docker exec -i "$PG_CONTAINER" psql -X -U "$POSTGRES_USER" -d "$PLATFORM_DB" \
  -tA -v ON_ERROR_STOP=1 -v tenant_id=ta -v job_code=TA_PROCESS_STAGE4_JSONB \
  -f /dev/stdin < "$SQL_DIR/count-enabled-job-definition.sql")" != "1" ]]; then
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

"$PYTHON_BIN" - <<'PY' 2>&1 | tee "$REPORT_DIR/process-stage4b.log"
import json, os, subprocess, sys, time, urllib.request

BASE = os.environ["TRIGGER_BASE"]
SECRET = os.environ["INTERNAL_SECRET"]
BIZ = os.environ["BIZ_DATE"]
BATCH = os.environ["BATCH_NO"]
BATCH_KEY = os.environ["BATCH_KEY"]
SQL_DIR = os.path.join(os.getcwd(), "scripts", "sim", "sql")

def run(cmd, **kwargs):
    return subprocess.run(cmd, check=False, capture_output=True, text=True, **kwargs)

def psql(db, sql_file, variables=None, tuples=False, capture_output=True):
    args = [
        "docker", "exec", "-i", os.environ["PG_CONTAINER"], "psql", "-X",
        "-v", "ON_ERROR_STOP=1", "-U", os.environ["POSTGRES_USER"],
        "-d", db, "-P", "pager=off",
    ]
    if tuples:
        args += ["-t", "-A"]
    for key, value in (variables or {}).items():
        args += ["-v", f"{key}={value}"]
    args += ["-f", "/dev/stdin"]
    with open(os.path.join(SQL_DIR, sql_file), encoding="utf-8") as sql:
        return subprocess.run(
            args, check=True, capture_output=capture_output,
            text=True, input=sql.read())

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
        out = psql(
            os.environ["PLATFORM_DB"], "select-request-instance-status-only.sql",
            {"tenant_id": "ta", "request_id": rid}, tuples=True)
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
    "-d", os.environ["BUSINESS_DB"], "-v", "ON_ERROR_STOP=1", "-v", f"biz_date={BIZ}", "-f", "/dev/stdin"
], input=open("docs/test-data/sim-stage4b-process-source-v2.sql").read())

rid2 = launch("rerun")
wait_success(rid2)

print("\n-- target_rows --", flush=True)
target_variables = {"tenant_id": "ta", "biz_date": BIZ}
psql(
    os.environ["BUSINESS_DB"], "select-process-stage4b-target.sql",
    target_variables, capture_output=False)

print("\n-- staging_leftover --", flush=True)
staging_variables = {"tenant_id": "ta", "batch_key": BATCH_KEY}
psql(
    os.environ["BUSINESS_DB"], "count-process-stage4b-staging.sql",
    staging_variables, capture_output=False)

target_out = psql(
    os.environ["BUSINESS_DB"], "select-process-stage4b-assertion.sql",
    target_variables, tuples=True)
staging_out = psql(
    os.environ["BUSINESS_DB"], "count-process-stage4b-staging.sql",
    staging_variables, tuples=True)
summary = (target_out.stdout or "").strip() + "|" + (staging_out.stdout or "").strip()
print(f"\n-- assertion_summary --\n{summary}", flush=True)
if summary != "2|400.00|3|203|0":
    print("❌ Process Stage4b assertion failed, expected 2|400.00|3|203|0", flush=True)
    sys.exit(1)

print(f"\n==> Stage 4b process scenario PASS: batchNo={BATCH} batchKey={BATCH_KEY}", flush=True)
PY
