#!/usr/bin/env bash
# =========================================================
# 14-dispatch-stage5b.sh:Dispatch no-retry 失败补偿分支验证
#
# 覆盖:
#   - API channel 返回 500
#   - retry_policy=NONE 下 job/partition/task 直接 FAILED
#   - dispatch_record 进入 COMPENSATED
#
# 触发方式:Trigger API -> Orchestrator -> Kafka -> worker-dispatch。
# SQL seed 独立放在 docs/test-data,脚本只负责编排、触发、轮询、断言。
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

export SIM_STAGE_NAME="dispatch-stage5b"
# shellcheck source=env-common.sh
source "$ROOT/scripts/sim/env-common.sh"

batch_require_python
SQL_DIR="$ROOT/scripts/sim/sql"

echo "==> seed dispatch stage5b job/channel fixture"
docker exec -i "$PG_CONTAINER" psql -U "$PG_PLATFORM_USER" -d "$PG_PLATFORM_DB" \
  -v ON_ERROR_STOP=1 -f /dev/stdin < docs/test-data/sim-stage5b-dispatch-fixtures.sql >/dev/null

echo "==> preflight dispatch stage5 job/channel"
if [[ "$(docker exec -i "$PG_CONTAINER" psql -X -U "$PG_PLATFORM_USER" -d "$PG_PLATFORM_DB" \
  -tA -v ON_ERROR_STOP=1 -v tenant_id=tb -v job_code=TB_DISPATCH_STAGE5_FAIL_ONCE \
  -f /dev/stdin < "$SQL_DIR/count-enabled-job-definition.sql" | tr -d '[:space:]')" != "1" ]]; then
  echo "❌ missing TB_DISPATCH_STAGE5_FAIL_ONCE fixture" >&2
  exit 1
fi
if [[ "$(docker exec -i "$PG_CONTAINER" psql -X -U "$PG_PLATFORM_USER" -d "$PG_PLATFORM_DB" \
  -tA -v ON_ERROR_STOP=1 -v tenant_id=tb -v channel_code=tb_api_fail \
  -f /dev/stdin < "$SQL_DIR/count-enabled-file-channel.sql" | tr -d '[:space:]')" != "1" ]]; then
  echo "❌ missing tb_api_fail channel fixture" >&2
  exit 1
fi

echo "==> seed dispatch file_record"
FILE_ID="$(docker exec -i "$PG_CONTAINER" psql -U "$PG_PLATFORM_USER" -d "$PG_PLATFORM_DB" \
  -v ON_ERROR_STOP=1 -v batch_no="$BATCH_NO" -v biz_date="$BIZ_DATE" \
  -t -A -f /dev/stdin < docs/test-data/sim-stage5-dispatch-file.sql | tail -1)"
export FILE_ID
START_TS="$(docker exec -i "$PG_CONTAINER" psql -X -U "$PG_PLATFORM_USER" -d "$PG_PLATFORM_DB" \
  -tA -v ON_ERROR_STOP=1 -f /dev/stdin < "$SQL_DIR/select-current-timestamp.sql")"
export START_TS

"$PYTHON_BIN" - <<'PY' 2>&1 | tee "$REPORT_DIR/dispatch-stage5b.log"
import json, os, subprocess, sys, time, urllib.request

BASE = os.environ["TRIGGER_BASE"]
SECRET = os.environ["INTERNAL_SECRET"]
BIZ = os.environ["BIZ_DATE"]
BATCH = os.environ["BATCH_NO"]
FILE_ID = os.environ["FILE_ID"].strip()
SQL_DIR = os.path.join(os.getcwd(), "scripts", "sim", "sql")

rid = f"sim-stage5b-dispatch-{int(time.time()*1000)%100000000}"
body = {
    "tenantId": "tb",
    "jobCode": "TB_DISPATCH_STAGE5_FAIL_ONCE",
    "triggerType": "API",
    "bizDate": BIZ,
    "requestId": rid,
    "params": {
        "fileId": FILE_ID,
        "channelCode": "tb_api_fail",
        "batchNo": BATCH,
        "externalRequestId": BATCH,
    },
}
req = urllib.request.Request(
    f"{BASE}/api/triggers/launch",
    data=json.dumps(body).encode(),
    headers={
        "Content-Type": "application/json",
        "X-Tenant-Id": "tb",
        "X-Internal-Secret": SECRET,
        "Idempotency-Key": rid,
        "X-Request-Id": rid,
    },
)
with urllib.request.urlopen(req, timeout=30) as resp:
    text = resp.read().decode()
    ok = resp.status == 200 and '"SUCCESS"' in text
    print(f"  [launch] fileId={FILE_ID} {'✓' if ok else '✗'}", flush=True)
    if not ok:
        print(text[:500], flush=True)
        sys.exit(1)

def psql(sql_file, variables=None, tuples=False, capture_output=True):
    args = [
        "docker", "exec", "-i", os.environ["PG_CONTAINER"], "psql", "-X",
        "-v", "ON_ERROR_STOP=1", "-U", os.environ["PG_PLATFORM_USER"],
        "-d", os.environ["PG_PLATFORM_DB"], "-P", "pager=off",
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

deadline = time.time() + 150
instance_id = None
while time.time() < deadline:
    out = psql(
        "select-request-instance-status.sql",
        {"tenant_id": "tb", "request_id": rid},
        tuples=True,
    )
    value = (out.stdout or "").strip()
    if value:
        iid, status = value.split("|", 1)
        if status in ("SUCCESS", "FAILED", "PARTIAL_FAILED", "REJECTED", "CANCELLED"):
            instance_id = iid
            print(f"  [result] instance={iid} status={status}", flush=True)
            if status != "FAILED":
                sys.exit(1)
            break
    time.sleep(3)
if not instance_id:
    raise TimeoutError("timeout waiting dispatch stage5b")

print("\n-- dispatch_status --", flush=True)
query_variables = {
    "tenant_id": "tb",
    "instance_id": instance_id,
    "file_id": FILE_ID,
    "channel_code": "tb_api_fail",
}
psql("select-dispatch-failure-details.sql", query_variables, capture_output=False)

out = psql(
    "select-dispatch-failure-summary.sql",
    query_variables,
    tuples=True,
)
summary = (out.stdout or "").strip()
print(f"\n-- assertion_summary --\n{summary}", flush=True)
if summary != "FAILED|FAILED|FAILED|COMPENSATED":
    print("❌ Dispatch Stage5b assertion failed, expected FAILED|FAILED|FAILED|COMPENSATED", flush=True)
    sys.exit(1)

print(f"\n==> Stage 5b dispatch scenario PASS: batchNo={BATCH} fileId={FILE_ID} instance={instance_id}", flush=True)
PY
