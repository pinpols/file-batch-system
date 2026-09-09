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
SIM_SQL_DIR="$ROOT/scripts/sim/sql"
export SIM_SQL_DIR

SIM_STAGE_NAME="process-stage4"
# shellcheck source=env-common.sh
source "$ROOT/scripts/sim/env-common.sh"

batch_require_python

echo "==> seed process business tables"
docker exec -i "$PG_CONTAINER" psql -U "$POSTGRES_USER" -d "$BUSINESS_DB" \
  -v ON_ERROR_STOP=1 -v biz_date="$BIZ_DATE" \
  -f /dev/stdin < docs/test-data/sim-stage4-process-business-fixtures.sql >/dev/null

echo "==> seed process platform jobs"
docker exec -i "$PG_CONTAINER" psql -U "$POSTGRES_USER" -d "$PLATFORM_DB" \
  -v ON_ERROR_STOP=1 -v biz_date="$BIZ_DATE" \
  -f /dev/stdin < docs/test-data/sim-stage4-process-platform-fixtures.sql >/dev/null

START_TS="$(docker exec -i "$PG_CONTAINER" psql -X -U "$POSTGRES_USER" -d "$PLATFORM_DB" \
  -v ON_ERROR_STOP=1 -tA -f /dev/stdin < "$SIM_SQL_DIR/select-current-timestamp.sql")"
export START_TS

"$PYTHON_BIN" - <<'PY' 2>&1 | tee "$REPORT_DIR/process-stage4.log"
import json
import os
import subprocess
import time
import urllib.request
from pathlib import Path

BASE = os.environ["TRIGGER_BASE"]
SECRET = os.environ["INTERNAL_SECRET"]
BIZ = os.environ["BIZ_DATE"]
BATCH = os.environ["BATCH_NO"]
START_TS = os.environ["START_TS"].strip()
SQL_DIR = Path(os.environ["SIM_SQL_DIR"])

SCENARIOS = [
    ("jsonb_ok", "TA_PROCESS_STAGE4_JSONB", BATCH + "-jsonb", "SUCCESS"),
    ("direct_ok", "TA_PROCESS_STAGE4_DIRECT", BATCH + "-direct", "SUCCESS"),
    ("validate_fail", "TA_PROCESS_STAGE4_VALIDATE_FAIL", BATCH + "-validate-fail", "FAILED"),
    ("empty_success", "TA_PROCESS_STAGE4_EMPTY_SUCCESS", BATCH + "-empty", "SUCCESS"),
]

def psql_file(db, sql_file, variables=None, tuples=False):
    args = [
        "docker", "exec", os.environ.get("PG_CONTAINER", "batch-postgres-primary"), "psql",
        "-X", "-U", os.environ.get("POSTGRES_USER", "batch_user"), "-d", db,
        "-v", "ON_ERROR_STOP=1", "-P", "pager=off",
    ]
    if tuples:
        args += ["-t", "-A"]
    for key, value in (variables or {}).items():
        args += ["-v", f"{key}={value}"]
    args += ["-f", "/dev/stdin"]
    sql = (SQL_DIR / sql_file).read_text(encoding="utf-8")
    return subprocess.run(args, input=sql, check=True, capture_output=True, text=True)

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
            raise RuntimeError(f"launch failed: {label}")

for label, job_code, batch_key, expected in SCENARIOS:
    launch(label, job_code, batch_key)

print("==> wait worker terminal states", flush=True)
deadline = time.time() + 180
job_codes = ",".join(s[1] for s in SCENARIOS)
while time.time() < deadline:
    out = psql_file(
        os.environ["PLATFORM_DB"],
        "count-process-stage4-terminal.sql",
        {"tenant_id": "ta", "job_codes": job_codes, "start_ts": START_TS},
        tuples=True,
    )
    done = int((out.stdout or "0").strip() or "0")
    if done >= len(SCENARIOS):
        break
    time.sleep(3)

print("\n-- job_status --", flush=True)
variables = {"tenant_id": "ta", "job_codes": job_codes, "start_ts": START_TS, "batch_no": BATCH}
print(psql_file(os.environ["PLATFORM_DB"], "select-process-stage4-job-status.sql", variables).stdout, end="")

print("\n-- target_rows --", flush=True)
print(psql_file(os.environ["BUSINESS_DB"], "select-process-stage4-target-rows.sql", variables).stdout, end="")

print("\n-- staging_leftover --", flush=True)
print(psql_file(os.environ["BUSINESS_DB"], "select-process-stage4-staging-leftover.sql", variables).stdout, end="")

print("\n-- expectation_check --", flush=True)
print(psql_file(os.environ["PLATFORM_DB"], "select-process-stage4-expectations.sql", variables).stdout, end="")
out = psql_file(os.environ["PLATFORM_DB"], "count-process-stage4-mismatches.sql", variables, tuples=True)
failures = int((out.stdout or "0").strip() or "0")
print(f"\n==> Stage 4 process scenario submitted: batchNo={BATCH} startTs={START_TS}", flush=True)
if failures:
    raise SystemExit(f"Stage 4 process expectation failed: {failures} mismatch(es)")
PY
