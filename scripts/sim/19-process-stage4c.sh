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
SIM_SQL_DIR="$ROOT/scripts/sim/sql"
export SIM_SQL_DIR

export SIM_STAGE_NAME="process-stage4c"
# shellcheck source=env-common.sh
source "$ROOT/scripts/sim/env-common.sh"


batch_require_python

echo "==> seed process stage4c fixtures"
docker exec -i "$PG_CONTAINER" psql -U "$POSTGRES_USER" -d "$BUSINESS_DB" \
  -v ON_ERROR_STOP=1 -v biz_date="$BIZ_DATE" \
  -f /dev/stdin < docs/test-data/sim-stage4c-process-business.sql >/dev/null
docker exec -i "$PG_CONTAINER" psql -U "$POSTGRES_USER" -d "$PLATFORM_DB" \
  -v ON_ERROR_STOP=1 \
  -f /dev/stdin < docs/test-data/sim-stage4c-process-platform.sql >/dev/null

START_TS="$(docker exec -i "$PG_CONTAINER" psql -X -U "$POSTGRES_USER" -d "$PLATFORM_DB" \
  -v ON_ERROR_STOP=1 -tA -f /dev/stdin < "$SIM_SQL_DIR/select-current-timestamp.sql")"
export START_TS

"$PYTHON_BIN" - <<'PY' 2>&1 | tee "$REPORT_DIR/process-stage4c.log"
import json, os, subprocess, sys, time, urllib.error, urllib.parse, urllib.request
from pathlib import Path

BASE = os.environ["TRIGGER_BASE"]
ORCH = os.environ["ORCH_BASE"]
SECRET = os.environ["INTERNAL_SECRET"]
BIZ = os.environ["BIZ_DATE"]
BATCH = os.environ["BATCH_NO"]
START_TS = os.environ["START_TS"].strip()
SQL_DIR = Path(os.environ["SIM_SQL_DIR"])

def psql_file(db, sql_file, variables=None, tuples=False):
    args = ["docker", "exec", "-i", os.environ.get("PG_CONTAINER", "batch-postgres-primary"), "psql", "-X", "-U", os.environ.get("POSTGRES_USER", "batch_user"), "-d", db, "-v", "ON_ERROR_STOP=1", "-P", "pager=off"]
    if tuples:
        args += ["-t", "-A"]
    for key, value in (variables or {}).items():
        args += ["-v", f"{key}={value}"]
    args += ["-f", "/dev/stdin"]
    return subprocess.run(args, input=(SQL_DIR / sql_file).read_text(encoding="utf-8"), check=True, capture_output=True, text=True)

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
        out = psql_file(os.environ["PLATFORM_DB"], "select-process-stage4c-instance.sql", {"tenant_id": "ta", "request_id": rid}, tuples=True)
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
platform_vars = {"tenant_id": "ta", "instance_id": shard_instance}
print(psql_file(os.environ["PLATFORM_DB"], "select-process-stage4c-task-status.sql", platform_vars).stdout, end="")

print("\n-- sharded_target --", flush=True)
business_vars = {"tenant_id": "ta", "biz_date": BIZ}
target = psql_file(os.environ["BUSINESS_DB"], "select-process-stage4c-target-summary.sql", business_vars)
print(target.stdout, end="")

shard_check = (psql_file(os.environ["BUSINESS_DB"], "select-process-stage4c-target-check.sql", business_vars, tuples=True).stdout or "").strip()
task_check = (psql_file(os.environ["PLATFORM_DB"], "select-process-stage4c-task-check.sql", platform_vars, tuples=True).stdout or "").strip()

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
    out = psql_file(os.environ["PLATFORM_DB"], "select-process-stage4c-cancel-task.sql", {"tenant_id": "ta", "request_id": rid_cancel}, tuples=True)
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
    out = psql_file(os.environ["PLATFORM_DB"], "select-process-stage4c-cancel-status-value.sql", {"tenant_id": "ta", "instance_id": cancel_instance}, tuples=True)
    cancel_status = (out.stdout or "").strip()
    if cancel_status and not any(x in cancel_status for x in ("RUNNING", "READY", "CREATED")):
        break
    time.sleep(3)

print("\n-- cancel_status --", flush=True)
print(psql_file(os.environ["PLATFORM_DB"], "select-process-stage4c-cancel-status.sql", {"tenant_id": "ta", "instance_id": cancel_instance}).stdout, end="")

summary = f"{task_check}|{shard_check}|{cancel_status}"
print(f"\n-- assertion_summary --\n{summary}", flush=True)
if not summary.startswith("4|4|16|296.00|16|16|"):
    print("❌ Process Stage4c sharded assertion failed", flush=True)
    sys.exit(1)
if cancel_http != "200":
    print("❌ Process Stage4c cancel API assertion failed", flush=True)
    sys.exit(1)
if not cancel_status.startswith("FAILED|FAILED|FAILED"):
    print("❌ Process Stage4c cancel interrupt assertion failed", flush=True)
    sys.exit(1)

print(f"\n==> Stage 4c process scenario PASS: batchNo={BATCH} startTs={START_TS}", flush=True)
PY
