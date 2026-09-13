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
SIM_SQL_DIR="$ROOT/scripts/sim/sql"
export SIM_SQL_DIR

export SIM_STAGE_NAME="export-stage3"
# shellcheck source=env-common.sh
source "$ROOT/scripts/sim/env-common.sh"

batch_require_python

echo "==> apply bootstrap(export format runtime config)"
docker exec -i "$PG_CONTAINER" psql -U "$POSTGRES_USER" -d "$PLATFORM_DB" \
  -v ON_ERROR_STOP=1 -v mockserver_host_port="${MOCKSERVER_HOST_PORT:-11080}" \
  -f /dev/stdin < docs/test-data/sim-e2e-bootstrap.sql >/dev/null

echo "==> seed small export source rows"
docker exec -i "$PG_CONTAINER" psql -U "$POSTGRES_USER" -d "$BUSINESS_DB" \
  -v ON_ERROR_STOP=1 -v batch_no="$BATCH_NO" -f /dev/stdin \
  < docs/test-data/sim-stage3-export-source.sql >/dev/null

START_TS="$(docker exec -i "$PG_CONTAINER" psql -X -U "$POSTGRES_USER" -d "$PLATFORM_DB" \
  -v ON_ERROR_STOP=1 -tA -f /dev/stdin < "$SIM_SQL_DIR/select-current-timestamp.sql")"
export START_TS

"$PYTHON_BIN" - <<'PY' 2>&1 | tee "$REPORT_DIR/export-stage3.log"
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
    ("json_ok", "TA_EXPORT_REPORT_JSON_TPL", BATCH, "SUCCESS"),
    ("fixed_ok", "TA_EXPORT_REPORT_FIXED_TPL", BATCH + "-fixed", "SUCCESS"),
    ("excel_ok", "TA_EXPORT_REPORT_EXCEL_TPL", BATCH + "-excel", "SUCCESS"),
    ("bad_sql", "TA_EXPORT_REPORT_BAD_SQL_TPL", BATCH + "-badsql", "FAILED"),
]

def psql_file(sql_file, variables=None, tuples=False):
    args = [
        "docker", "exec", "-i", os.environ.get("PG_CONTAINER", "batch-postgres-primary"), "psql",
        "-X", "-U", os.environ.get("POSTGRES_USER", "batch_user"),
        "-d", os.environ["PLATFORM_DB"], "-v", "ON_ERROR_STOP=1", "-P", "pager=off",
    ]
    if tuples:
        args += ["-t", "-A"]
    for key, value in (variables or {}).items():
        args += ["-v", f"{key}={value}"]
    args += ["-f", "/dev/stdin"]
    sql = (SQL_DIR / sql_file).read_text(encoding="utf-8")
    return subprocess.run(args, input=sql, check=True, capture_output=True, text=True)

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
            raise RuntimeError(f"launch failed: {label}")

for label, template_code, batch_no, expected in SCENARIOS:
    launch(label, template_code, batch_no)

print("==> wait worker terminal states", flush=True)
deadline = time.time() + 180
while time.time() < deadline:
    out = psql_file(
        "count-export-stage3-terminal.sql",
        {"tenant_id": "ta", "start_ts": START_TS},
        tuples=True,
    )
    done = int((out.stdout or "0").strip() or "0")
    if done >= len(SCENARIOS):
        break
    time.sleep(3)

print("\n-- job_status --", flush=True)
variables = {"tenant_id": "ta", "start_ts": START_TS}
print(psql_file("select-export-stage3-job-status.sql", variables).stdout, end="")

print("\n-- file_status --", flush=True)
print(psql_file("select-export-stage3-file-status.sql", variables).stdout, end="")

print("\n-- object_sample --", flush=True)
print(psql_file("select-export-stage3-object-sample.sql", variables).stdout, end="")

print("\n-- expectation_check --", flush=True)
print(psql_file("select-export-stage3-expectations.sql", variables).stdout, end="")
out = psql_file("count-export-stage3-mismatches.sql", variables, tuples=True)
failures = int((out.stdout or "0").strip() or "0")
print(f"\n==> Stage 3 export scenario submitted: batchNo={BATCH} startTs={START_TS}", flush=True)
if failures:
    raise SystemExit(f"Stage 3 export expectation failed: {failures} mismatch(es)")
PY
