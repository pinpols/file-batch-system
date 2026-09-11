#!/usr/bin/env bash
# =========================================================
# 18-export-stage3c.sh:Export 8 分片 + 多租户 + 幂等重放验证
#
# 覆盖:
#   - STATIC 8 分片小规模导出
#   - 同 requestId 重放不重复创建 job_instance
#   - ta/tb/tc 三租户并发导出均 SUCCESS 且登记 file_record
#
# SQL seed 独立放在 docs/test-data,脚本只负责编排、API 触发、轮询、断言。
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
SIM_SQL_DIR="$ROOT/scripts/sim/sql"
export SIM_SQL_DIR

SIM_STAGE_NAME="export-stage3c"
# shellcheck source=env-common.sh
source "$ROOT/scripts/sim/env-common.sh"

batch_require_python

echo "==> apply bootstrap + stage3b fixture + stage3c source"
docker exec -i "$PG_CONTAINER" psql -U "$POSTGRES_USER" -d "$PLATFORM_DB" \
  -v ON_ERROR_STOP=1 -v mockserver_host_port="${MOCKSERVER_HOST_PORT:-11080}" \
  -f /dev/stdin < docs/test-data/sim-e2e-bootstrap.sql >/dev/null
docker exec -i "$PG_CONTAINER" psql -U "$POSTGRES_USER" -d "$PLATFORM_DB" \
  -v ON_ERROR_STOP=1 -f /dev/stdin < docs/test-data/sim-stage3b-export-fixtures.sql >/dev/null
docker exec -i "$PG_CONTAINER" psql -U "$POSTGRES_USER" -d "$BUSINESS_DB" \
  -v ON_ERROR_STOP=1 -v batch_no="$BATCH_NO" \
  -f /dev/stdin < docs/test-data/sim-stage3c-export-source.sql >/dev/null

START_TS="$(docker exec -i "$PG_CONTAINER" psql -X -U "$POSTGRES_USER" -d "$PLATFORM_DB" \
  -v ON_ERROR_STOP=1 -tA -f /dev/stdin < "$SIM_SQL_DIR/select-current-timestamp.sql")"
export START_TS

"$PYTHON_BIN" - <<'PY' 2>&1 | tee "$REPORT_DIR/export-stage3c.log"
import json, os, subprocess, sys, time, urllib.request
from pathlib import Path

BASE = os.environ["TRIGGER_BASE"]
SECRET = os.environ["INTERNAL_SECRET"]
BIZ = os.environ["BIZ_DATE"]
BATCH = os.environ["BATCH_NO"]
START_TS = os.environ["START_TS"].strip()
SQL_DIR = Path(os.environ["SIM_SQL_DIR"])

def psql_file(sql_file, variables=None, tuples=False):
    args = ["docker", "exec", "-i", os.environ["PG_CONTAINER"], "psql", "-X", "-U", os.environ["POSTGRES_USER"], "-d", os.environ["PLATFORM_DB"], "-v", "ON_ERROR_STOP=1", "-P", "pager=off"]
    if tuples:
        args += ["-t", "-A"]
    for key, value in (variables or {}).items():
        args += ["-v", f"{key}={value}"]
    args += ["-f", "/dev/stdin"]
    return subprocess.run(args, input=(SQL_DIR / sql_file).read_text(encoding="utf-8"), check=True, capture_output=True, text=True)

def launch(tenant, job, rid, params):
    body = {
        "tenantId": tenant,
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
            "X-Tenant-Id": tenant,
            "X-Internal-Secret": SECRET,
            "Idempotency-Key": rid,
            "X-Request-Id": rid,
        },
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        text = resp.read().decode()
        ok = resp.status == 200 and '"SUCCESS"' in text
        print(f"  [launch] {tenant}:{job:26s} {rid} {'✓' if ok else '✗'}", flush=True)
        if not ok:
            print(text[:500], flush=True)
            raise RuntimeError(f"launch failed: {tenant}:{job}")

rid_ta = f"sim-stage3c-ta8-{int(time.time()*1000)%100000000}"
rid_tb = f"sim-stage3c-tb-{int(time.time()*1000)%100000000}"
rid_tc = f"sim-stage3c-tc-{int(time.time()*1000)%100000000}"

print("==> launch ta 8-shard twice with same requestId", flush=True)
ta_params = {
    "templateCode": "TA_EXPORT_REPORT_JSON_KEYSET_TPL",
    "batchNo": BATCH + "-ta8",
    "bizType": "TA_EXPORT_REPORT_JSON_KEYSET",
    "partitionCount": 8,
}
launch("ta", "TA_EXPORT_REPORT_STATIC", rid_ta, ta_params)
launch("ta", "TA_EXPORT_REPORT_STATIC", rid_ta, ta_params)

print("==> launch tb/tc multi-tenant", flush=True)
launch("tb", "TB_EXPORT_STATEMENT", rid_tb, {
    "templateCode": "TB_EXPORT_STATEMENT_TPL",
    "batchNo": BATCH + "-tb",
    "bizType": "STATEMENT",
})
launch("tc", "TC_EXPORT_RISK_ALERT", rid_tc, {
    "templateCode": "TC_EXPORT_RISK_ALERT_TPL",
    "batchNo": BATCH + "-tc",
    "bizType": "RISK",
})

deadline = time.time() + 240
while time.time() < deadline:
    request_vars = {"request_ids": f"{rid_ta},{rid_tb},{rid_tc}"}
    out = psql_file("count-export-stage3c-terminal.sql", request_vars, tuples=True)
    done = int((out.stdout or "0").strip() or "0")
    if done >= 3:
        break
    time.sleep(3)

print("\n-- job_status --", flush=True)
print(psql_file("select-export-stage3c-job-status.sql", request_vars).stdout, end="")

print("\n-- ta_partition_status --", flush=True)
ta_instance = (psql_file("select-export-stage3c-instance.sql", {"tenant_id": "ta", "request_id": rid_ta}, tuples=True).stdout or "").strip()
ta_vars = {"tenant_id": "ta", "instance_id": ta_instance, "source_ref": BATCH + "-ta8"}
print(psql_file("select-export-stage3c-partition-status.sql", ta_vars).stdout, end="")

print("\n-- file_records --", flush=True)
source_vars = {"source_refs": f"{BATCH}-ta8,{BATCH}-tb,{BATCH}-tc"}
print(psql_file("select-export-stage3c-file-records.sql", source_vars).stdout, end="")

dedup = (psql_file("select-export-stage3c-dedup-check.sql", {"tenant_id": "ta", "request_id": rid_ta}, tuples=True).stdout or "").strip()
ta_check = (psql_file("select-export-stage3c-shard-check.sql", ta_vars, tuples=True).stdout or "").strip()
tenant_check = (psql_file("count-export-stage3c-tenant-files.sql", source_vars, tuples=True).stdout or "").strip()
status_check = (psql_file("count-export-stage3c-success.sql", request_vars, tuples=True).stdout or "").strip()
summary = f"{dedup}|{ta_check}|{tenant_check}|{status_check}"
print(f"\n-- assertion_summary --\n{summary}", flush=True)
if summary != "1|1|8|8|8|80|3|3":
    print("❌ Export Stage3c assertion failed, expected 1|1|8|8|8|80|3|3", flush=True)
    sys.exit(1)

print(f"\n==> Stage 3c export scenario PASS: batchNo={BATCH} startTs={START_TS}", flush=True)
PY
