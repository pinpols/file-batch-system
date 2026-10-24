#!/usr/bin/env bash
# 用途：trigger-stage6b.sh:Trigger 去重 + 小规模 storm 验证。
# 用法：在仓库根目录执行 bash scripts/sim/15-trigger-stage6b.sh；参数与前置环境见下方说明及所属 README。
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

export SIM_STAGE_NAME="trigger-stage6b"
# shellcheck source=env-common.sh
source "$ROOT/scripts/sim/env-common.sh"

export STORM_COUNT="${STORM_COUNT:-30}"

batch_require_python
SQL_DIR="$ROOT/scripts/sim/sql"

echo "==> preflight trigger stage6 job"
if [[ "$(docker exec -i "$PG_CONTAINER" psql -X -U "$POSTGRES_USER" -d "$PLATFORM_DB" \
  -tA -v ON_ERROR_STOP=1 -v tenant_id=ta -v job_code=TA_PROCESS_STAGE4_EMPTY_SUCCESS \
  -f /dev/stdin < "$SQL_DIR/count-enabled-job-definition.sql")" != "1" ]]; then
  echo "❌ missing TA_PROCESS_STAGE4_EMPTY_SUCCESS fixture; run scripts/sim/10-process-stage4.sh once or apply its fixture" >&2
  exit 1
fi

START_TS="$(docker exec -i "$PG_CONTAINER" psql -X -U "$POSTGRES_USER" -d "$PLATFORM_DB" \
  -tA -v ON_ERROR_STOP=1 -f /dev/stdin < "$SQL_DIR/select-current-timestamp.sql")"
export START_TS

"$PYTHON_BIN" - <<'PY' 2>&1 | tee "$REPORT_DIR/trigger-stage6b.log"
import json, os, subprocess, sys, time, urllib.request
from datetime import date, timedelta
from scripts.sim.launch_contract import workflow_id_for_fixture

BASE = os.environ["TRIGGER_BASE"]
SECRET = os.environ["INTERNAL_SECRET"]
BIZ = os.environ["BIZ_DATE"]
BATCH = os.environ["BATCH_NO"]
STORM_COUNT = int(os.environ["STORM_COUNT"])
START_TS = os.environ["START_TS"].strip()
JOB = "TA_PROCESS_STAGE4_EMPTY_SUCCESS"
SQL_DIR = os.path.join(os.getcwd(), "scripts", "sim", "sql")

def launch(request_id, batch_key, biz_date):
    body = {
        "tenantId": "ta",
        "workflowDefinitionId": workflow_id_for_fixture("ta", JOB),
        "triggerType": "API",
        "bizDate": biz_date,
        "requestId": request_id,
        "params": {
            "batchNo": BATCH,
            "batchKey": batch_key,
            "bizDate": biz_date,
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

def psql(sql_file, variables=None, tuples=False, capture_output=True):
    args = [
        "docker", "exec", "-i", os.environ["PG_CONTAINER"], "psql", "-X",
        "-v", "ON_ERROR_STOP=1", "-U", os.environ["POSTGRES_USER"],
        "-d", os.environ["PLATFORM_DB"], "-P", "pager=off",
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

dedup_id = f"sim-stage6b-dedup-{int(time.time()*1000)%100000000}"
print("==> dedup launch same requestId twice", flush=True)
dedup_biz_date = (date.fromisoformat(BIZ) + timedelta(days=STORM_COUNT + 1)).isoformat()
launch(dedup_id, BATCH + "-dedup", dedup_biz_date)
launch(dedup_id, BATCH + "-dedup", dedup_biz_date)

storm_ids = []
print(f"==> storm launch {STORM_COUNT}", flush=True)
# 此前阶段可能已结算历史日期；此场景验证新的 API 准入路径。
storm_base_date = date.fromisoformat(BIZ) + timedelta(days=1)
for i in range(STORM_COUNT):
    rid = f"sim-stage6b-storm-{i:03d}-{int(time.time()*1000)%100000000}"
    storm_ids.append(rid)
    storm_biz_date = (storm_base_date + timedelta(days=i)).isoformat()
    # 按租户/Workflow/业务日进行幂等准入。每个
    # 独立的风暴请求使用不同业务日，确保测试验证
    # 执行并发，而非重复请求确认数量。
    launch(rid, f"{BATCH}-storm-{i:03d}", storm_biz_date)
print("  [launch] all accepted", flush=True)

deadline = time.time() + 240
while time.time() < deadline:
    out = psql(
        "count-terminal-job-instances.sql",
        {"tenant_id": "ta", "job_code": JOB, "start_ts": START_TS},
        tuples=True,
    )
    done = int((out.stdout or "0").strip() or "0")
    if done >= STORM_COUNT + 1:
        break
    time.sleep(3)

print("\n-- dedup_check --", flush=True)
dedup_variables = {"tenant_id": "ta", "request_id": dedup_id}
psql("select-trigger-request-dedup-counts.sql", dedup_variables, capture_output=False)

print("\n-- storm_status --", flush=True)
storm_variables = {"tenant_id": "ta", "job_code": JOB, "start_ts": START_TS}
psql("select-job-instance-status-counts.sql", storm_variables, capture_output=False)

dedup_out = psql("select-trigger-request-dedup-counts.sql", dedup_variables, tuples=True)
storm_out = psql(
    "select-stage6b-workflow-outcomes.sql",
    {"tenant_id": "ta", "request_ids": ",".join([dedup_id, *storm_ids])},
    tuples=True,
)
dedup_summary = (dedup_out.stdout or "").strip()
outcome_rows = [line.split("|") for line in (storm_out.stdout or "").splitlines()]
actual_outcomes = {row[0]: tuple(row[1:]) for row in outcome_rows if len(row) == 5}
expected_request_ids = [dedup_id, *storm_ids]
bad_outcomes = {
    request_id: actual_outcomes.get(request_id)
    for request_id in expected_request_ids
    if actual_outcomes.get(request_id) != ("LAUNCHED", "SUCCESS", "SUCCESS", "SUCCESS")
}
success_count = len(expected_request_ids) - len(bad_outcomes)
summary = f"{dedup_summary}|{success_count}|{len(bad_outcomes)}"
print(f"\n-- assertion_summary --\n{summary}", flush=True)
if dedup_summary != "1|1" or bad_outcomes:
    print(f"❌ Trigger Stage6b assertion failed, expected dedup=1|1 and all {STORM_COUNT + 1} requests to reach SUCCESS", flush=True)
    print(f"  nonterminal or duplicate requests: {bad_outcomes}", flush=True)
    sys.exit(1)

print(f"\n==> Stage 6b trigger scenario PASS: batchNo={BATCH} stormCount={STORM_COUNT}", flush=True)
PY
