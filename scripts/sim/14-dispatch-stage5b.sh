#!/usr/bin/env bash
# 用途：dispatch-stage5b.sh:Dispatch 未知外部结果的自动重试围栏验证。
# 用法：在仓库根目录执行 bash scripts/sim/14-dispatch-stage5b.sh；参数与前置环境见下方说明及所属 README。
# =========================================================
# 14-dispatch-stage5b.sh:Dispatch 未知外部结果的自动重试围栏验证
#
# 覆盖:
#   - API channel 返回 500，且接收端未声明幂等能力
#   - 实例/分区/任务/dispatch 记录 FAILED，并保留 EXTERNAL_OUTCOME_UNKNOWN
#   - 无自动 retry_schedule，也不伪造 COMPENSATED
#
# 触发方式:Trigger API -> Orchestrator -> Kafka -> worker-dispatch。
# SQL seed 独立放在 test-data/sim,脚本只负责编排、触发、轮询、断言。
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

export SIM_STAGE_NAME="dispatch-stage5b"
# shellcheck source=env-common.sh
source "$ROOT/scripts/sim/env-common.sh"

batch_require_python
SQL_DIR="$ROOT/scripts/sim/sql"

# 故障配置只在本场景有效；正常退出、失败和 TERM/INT 都关闭探测入口，保留故障历史。
cleanup_stage5b_fault_channel() {
  local scenario_status=$?
  local SQL_DIR="$ROOT/scripts/local/sql"
  trap - EXIT
  if ! docker exec -i "$PG_CONTAINER" psql -X -U "$PG_PLATFORM_USER" -d "$PG_PLATFORM_DB" \
      -v ON_ERROR_STOP=1 -f /dev/stdin < "$SQL_DIR/disable-stage5b-fault-channel.sql"; then
    echo "Stage5b 故障渠道清理失败" >&2
    if [[ "$scenario_status" == 0 ]]; then
      scenario_status=1
    fi
  fi
  exit "$scenario_status"
}
trap cleanup_stage5b_fault_channel EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

sim_load_mockserver_stubs
MOCKSERVER_BASE_URL="$(sim_mockserver_base_url)"
STAGE5B_FAULT_PATH="/tb/fail"
STAGE5B_FAULT_ENDPOINT="${MOCKSERVER_BASE_URL%/}${STAGE5B_FAULT_PATH}"
echo "==> seed dispatch stage5b job/channel fixture"
docker exec -i "$PG_CONTAINER" psql -U "$PG_PLATFORM_USER" -d "$PG_PLATFORM_DB" \
  -v ON_ERROR_STOP=1 -v "target_endpoint=$STAGE5B_FAULT_ENDPOINT" \
  -f /dev/stdin < test-data/sim/sim-stage5b-dispatch-fixtures.sql >/dev/null

wait_stage5b_channel_healthy() {
  local health
  local health_sql="$SQL_DIR/select-stage5b-channel-health.sql"
  for _ in $(seq 1 90); do
    health=$(docker exec -i "$PG_CONTAINER" psql -X -q -tA -U "$PG_PLATFORM_USER" -d "$PG_PLATFORM_DB" \
      -v ON_ERROR_STOP=1 -f /dev/stdin < "$health_sql") || return 1
    if [[ "$health" == HEALTHY ]]; then
      echo "  [probe] tb_api_fail HEALTHY (real worker probe)"
      return 0
    fi
    sleep 2
  done
  echo "Stage5b 渠道未获得成功健康探针: ${health:-MISSING}" >&2
  return 1
}
wait_stage5b_channel_healthy

echo "==> preflight dispatch stage5 job/channel"
JOB_DEFINITION_COUNT_SQL="$SQL_DIR/count-enabled-job-definition.sql"
JOB_DEFINITION_COUNT="$(docker exec -i "$PG_CONTAINER" psql -X -U "$PG_PLATFORM_USER" -d "$PG_PLATFORM_DB" \
  -tA -v ON_ERROR_STOP=1 -v tenant_id=tb -v job_code=TB_DISPATCH_STAGE5_FAIL_ONCE \
  -f /dev/stdin < "$JOB_DEFINITION_COUNT_SQL")"
JOB_DEFINITION_COUNT="$(printf '%s' "$JOB_DEFINITION_COUNT" | tr -d '[:space:]')"
if [[ "$JOB_DEFINITION_COUNT" != "1" ]]; then
  echo "❌ missing TB_DISPATCH_STAGE5_FAIL_ONCE fixture" >&2
  exit 1
fi
FILE_CHANNEL_COUNT_SQL="$SQL_DIR/count-enabled-file-channel.sql"
FILE_CHANNEL_COUNT="$(docker exec -i "$PG_CONTAINER" psql -X -U "$PG_PLATFORM_USER" -d "$PG_PLATFORM_DB" \
  -tA -v ON_ERROR_STOP=1 -v tenant_id=tb -v channel_code=tb_api_fail \
  -f /dev/stdin < "$FILE_CHANNEL_COUNT_SQL")"
FILE_CHANNEL_COUNT="$(printf '%s' "$FILE_CHANNEL_COUNT" | tr -d '[:space:]')"
if [[ "$FILE_CHANNEL_COUNT" != "1" ]]; then
  echo "❌ missing tb_api_fail channel fixture" >&2
  exit 1
fi

echo "==> seed dispatch file_record"
FILE_FIXTURE_SQL="test-data/sim/sim-stage5-dispatch-file.sql"
FILE_FIXTURE_OUTPUT="$(docker exec -i "$PG_CONTAINER" psql -U "$PG_PLATFORM_USER" -d "$PG_PLATFORM_DB" \
  -v ON_ERROR_STOP=1 -v batch_no="$BATCH_NO" -v biz_date="$BIZ_DATE" \
  -t -A -f /dev/stdin < "$FILE_FIXTURE_SQL")"
FILE_ID="$(printf '%s\n' "$FILE_FIXTURE_OUTPUT" | tail -1)"
export FILE_ID
CURRENT_TIMESTAMP_SQL="$SQL_DIR/select-current-timestamp.sql"
START_TS="$(docker exec -i "$PG_CONTAINER" psql -X -U "$PG_PLATFORM_USER" -d "$PG_PLATFORM_DB" \
  -tA -v ON_ERROR_STOP=1 -f /dev/stdin < "$CURRENT_TIMESTAMP_SQL")"
export START_TS

"$PYTHON_BIN" - <<'PY' 2>&1 | tee "$REPORT_DIR/dispatch-stage5b.log"
import json, os, subprocess, sys, time, urllib.request
from scripts.sim.launch_contract import workflow_id_for_fixture

BASE = os.environ["TRIGGER_BASE"]
SECRET = os.environ["INTERNAL_SECRET"]
BIZ = os.environ["BIZ_DATE"]
BATCH = os.environ["BATCH_NO"]
FILE_ID = os.environ["FILE_ID"].strip()
SQL_DIR = os.path.join(os.getcwd(), "scripts", "sim", "sql")

rid = f"sim-stage5b-dispatch-{int(time.time()*1000)%100000000}"
workflow_definition_id = workflow_id_for_fixture("tb", "TB_DISPATCH_STAGE5_FAIL_ONCE")
body = {
    "tenantId": "tb",
    "workflowDefinitionId": workflow_definition_id,
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
body_json = json.dumps(body)
body_bytes = body_json.encode()
request_headers = {
    "Content-Type": "application/json",
    "X-Tenant-Id": "tb",
    "X-Internal-Secret": SECRET,
    "Idempotency-Key": rid,
    "X-Request-Id": rid,
}
req = urllib.request.Request(
    f"{BASE}/api/triggers/launch",
    data=body_bytes,
    headers=request_headers,
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
    sql_path = os.path.join(SQL_DIR, sql_file)
    with open(sql_path, encoding="utf-8") as sql:
        sql_text = sql.read()
    psql_result = subprocess.run(
        args,
        check=True,
        capture_output=capture_output,
        text=True,
        input=sql_text,
    )
    return psql_result

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
expected_summary = (
    "FAILED|FAILED|FAILED|FAILED|EXTERNAL_OUTCOME_UNKNOWN|EXTERNAL_OUTCOME_UNKNOWN|0"
)
if summary != expected_summary:
    print(
        f"❌ Dispatch Stage5b assertion failed, expected {expected_summary}",
        flush=True,
    )
    sys.exit(1)

failure_message_result = psql("select-dispatch-failure-message.sql", query_variables, tuples=True)
failure_message = (failure_message_result.stdout or "").strip()
if failure_message != "dispatch api responded 500":
    raise AssertionError(f"expected actual business HTTP 500, got {failure_message!r}")
print(f"  [fault] {failure_message}", flush=True)

print(f"\n==> Stage 5b dispatch scenario PASS: batchNo={BATCH} fileId={FILE_ID} instance={instance_id}", flush=True)
PY
