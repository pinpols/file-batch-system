#!/usr/bin/env bash
# 用途：trigger-stage6c.sh:Trigger scheduled / misfire / replay / storm 验证。
# 用法：在仓库根目录执行 bash scripts/sim/22-trigger-stage6c.sh；参数与前置环境见下方说明及所属 README。
# =========================================================
# 22-trigger-stage6c.sh:Trigger scheduled / misfire / replay / storm 验证
#
# 覆盖:
#   - Quartz scheduled fire 真实落 trigger_request
#   - 管理 API 暂停/恢复真实 Quartz，按原 fire 验证 MANUAL_APPROVAL 待办
#   - 审批原始请求后 Workflow/Job SUCCESS，保持原日期、区间和版本
#   - API task storm 收敛
#
# SQL seed 独立放在 test-data/sim,脚本只负责编排、触发、轮询、断言。
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
SIM_SQL_DIR="$ROOT/scripts/sim/sql"
export SIM_SQL_DIR

export SIM_STAGE_NAME="trigger-stage6c"
# shellcheck source=env-common.sh
source "$ROOT/scripts/sim/env-common.sh"
source "$ROOT/scripts/lib/process.sh"

export STORM_COUNT="${STORM_COUNT:-60}"

batch_require_python

cleanup_trigger_fixtures() {
  docker exec -i "$PG_CONTAINER" psql -X -U "$POSTGRES_USER" -d "$PLATFORM_DB" \
    -v ON_ERROR_STOP=1 -f /dev/stdin \
    < "$SIM_SQL_DIR/disable-trigger-stage6c-jobs.sql" >/dev/null 2>&1 || true
  for workflow_code in WF-TA_TRIGGER_STAGE6C_SCHEDULED WF-TA_TRIGGER_STAGE6C_MISFIRE; do
    curl -s -m 5 -X POST \
      "$TRIGGER_BASE/api/triggers/management/workflow/unregister?tenantId=ta&workflowCode=$workflow_code" \
      -H "X-Tenant-Id: ta" -H "X-Internal-Secret: $INTERNAL_SECRET" \
      -o /dev/null 2>/dev/null || true
  done
}
trap cleanup_trigger_fixtures EXIT

restart_trigger_for_fixture() {
  if [[ "${SIM_TRIGGER_RESTART_MODE:-restart}" == "screen" ]]; then
    screen -S bfs-trigger -X quit >/dev/null 2>&1 || true
    screen -dmS bfs-trigger bash -lc "
      cd '$ROOT'
      unset BATCH_ENV_LOADED BATCH_ENV_COMMON_ROOT
      source scripts/lib/env-common.sh
      batch_configure_local_jvm_runtime_env
      exec java --enable-native-access=ALL-UNNAMED \
        ${LOCAL_FAST_JVM_OPTS:--XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xshare:off} \
        ${JAVA_OPTS:-} \
        -jar build/runtime-jars/trigger.jar --spring.profiles.active=local \
        >logs/current/app/trigger.log 2>&1
    "
    return 0
  fi
  (
    unset BATCH_ENV_LOADED BATCH_ENV_COMMON_ROOT
    bash scripts/local/restart.sh trigger
  )
}

# Fixture 会直接重置 Quartz 的测试 job/trigger。必须先停 Trigger，再清理 fixture，最后
# 启动 Trigger；仅调用 drain 或“重启后立即清理”都可能让新进程的 reconciler 与 psql 争抢
# QRTZ_LOCKS。这个停启只属于 sim 的数据边界隔离，不代表生产流程需要按阶段重启服务。
echo "==> stop trigger before direct Quartz fixture reset"
runtime_status=0
sim_container_stack_active || runtime_status=$?
[[ "$runtime_status" -le 1 ]] || exit "$runtime_status"
if [[ "$runtime_status" == 0 ]]; then
  # 容器端口由 Docker 代理监听，不能按宿主端口 kill，否则会连带终止
  # Docker daemon。容器栈通过 Compose 停止，后续 restart.sh 负责重建。
  docker compose --env-file "${COMPOSE_ENV_FILE:-.env.local}" \
    -f docker-compose.yml -f deploy/docker/compose/app.yml --profile apps --profile replica \
    stop trigger >/dev/null
else
  trigger_pids="$(process_listen_pids "${BATCH_TRIGGER_PORT:-18081}" || true)"
  if [[ -n "$trigger_pids" ]]; then
    # shellcheck disable=SC2086
    kill -9 $trigger_pids 2>/dev/null || true
  fi
fi
for _ in $(seq 1 30); do
  if ! process_port_is_listening "${BATCH_TRIGGER_PORT:-18081}"; then
    break
  fi
  sleep 1
done
if process_port_is_listening "${BATCH_TRIGGER_PORT:-18081}"; then
  echo "❌ trigger 停止失败，无法安全清理 Quartz fixture" >&2
  exit 1
fi

echo "==> seed trigger stage6c fixtures"
docker exec -i "$PG_CONTAINER" psql -U "$POSTGRES_USER" -d "$PLATFORM_DB" \
  -v ON_ERROR_STOP=1 -v batch_no="$BATCH_NO" -v biz_date="$BIZ_DATE" \
  -f /dev/stdin < test-data/sim/sim-stage6c-trigger-fixtures.sql >/dev/null

echo "==> start trigger after direct Quartz fixture reset"
restart_trigger_for_fixture >"$REPORT_DIR/trigger-restart.log" 2>&1
health_code=""
for _ in $(seq 1 60); do
  health_code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$TRIGGER_BASE/actuator/health" 2>/dev/null || true)
  [[ "$health_code" == "200" ]] && break
  sleep 2
done
[[ "$health_code" == "200" ]] || {
  echo "❌ trigger fixture 后启动健康检查失败，详见 $REPORT_DIR/trigger-restart.log" >&2
  exit 1
}

START_TS="$(docker exec -i "$PG_CONTAINER" psql -X -U "$POSTGRES_USER" -d "$PLATFORM_DB" \
  -v ON_ERROR_STOP=1 -tA -f /dev/stdin < "$SIM_SQL_DIR/select-current-timestamp.sql")"
export START_TS

"$PYTHON_BIN" - <<'PY' 2>&1 | tee "$REPORT_DIR/trigger-stage6c.log"
import json, os, subprocess, sys, time, urllib.request
from datetime import date, timedelta
from scripts.sim.launch_contract import workflow_id_for_fixture
from pathlib import Path

BASE = os.environ["TRIGGER_BASE"]
SECRET = os.environ["INTERNAL_SECRET"]
BIZ = os.environ["BIZ_DATE"]
BATCH = os.environ["BATCH_NO"]
STORM_COUNT = int(os.environ["STORM_COUNT"])
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

def launch(request_id, batch_key, biz_date=BIZ):
    body = {
        "tenantId": "ta",
        "workflowDefinitionId": workflow_id_for_fixture("ta", "TA_PROCESS_STAGE4_EMPTY_SUCCESS"),
        "triggerType": "API",
        "bizDate": biz_date,
        "requestId": request_id,
        "params": {"batchNo": BATCH, "batchKey": batch_key, "bizDate": biz_date},
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

def wait_count(label, sql_file, variables, expected, timeout=150):
    deadline = time.time() + timeout
    while time.time() < deadline:
        out = psql_file(sql_file, variables, tuples=True)
        value = int((out.stdout or "0").strip() or "0")
        if value >= expected:
            print(f"  [{label}] {value} >= {expected}", flush=True)
            return value
        time.sleep(3)
    raise TimeoutError(f"timeout waiting {label}")

def read_count(sql_file, variables):
    out = psql_file(sql_file, variables, tuples=True)
    return int((out.stdout or "0").strip() or "0")

def manage_all(action):
    req = urllib.request.Request(
        f"{BASE}/api/triggers/management/{action}-all", data=b"",
        headers={"X-Tenant-Id": "ta", "X-Internal-Secret": SECRET}, method="POST",
    )
    with urllib.request.urlopen(req, timeout=30) as response:
        payload = json.load(response)
        if response.status != 200 or payload.get("code") != "SUCCESS":
            raise RuntimeError(f"Quartz {action}-all failed: {payload}")


manage_all("resume")

print("==> wait Quartz scheduled + misfire", flush=True)
wait_count(
    "quartz-misfire-registered",
    "count-trigger-stage6c-quartz-misfire.sql",
    {},
    1,
)
common_vars = {"tenant_id": "ta", "start_ts": START_TS}
scheduled_count = wait_count(
    "scheduled",
    "count-trigger-stage6c-scheduled.sql",
    common_vars,
    1,
)
# 使用已持久化的原始触发和真实 Quartz 故障；不创建虚拟待处理请求，也不回拨时间戳。
try:
    manage_all("pause")
    planned_fire = (psql_file("select-trigger-stage6d-planned-misfire.sql", tuples=True).stdout or "").strip()
    if not planned_fire:
        raise RuntimeError("Quartz next fire has no matching PLANNED occurrence")
    fire_millis = int(planned_fire)
    delay = max(0, fire_millis / 1000 - time.time()) + 65
    print(f"  [misfire-pause] originalFireTime={fire_millis}, pauseSeconds={delay:.1f}", flush=True)
    time.sleep(delay)
finally:
    manage_all("resume")
misfire_vars = {"tenant_id": "ta", "start_ts": START_TS, "scheduled_fire_millis": fire_millis}
misfire_count = wait_count(
    "misfire-pending", "count-trigger-stage6d-pending.sql", misfire_vars, 1, timeout=120,
)

print("==> approve replay request", flush=True)
pending_link = (psql_file("select-trigger-stage6d-pending-link.sql", misfire_vars, tuples=True).stdout or "").strip()
pending_id, replay_id = pending_link.split("|", 1)
if not replay_id:
    raise RuntimeError(f"misfire pending not linked to original request: {pending_id}")
req = urllib.request.Request(
    f"{BASE}/api/triggers/catch-up/approve",
    data=json.dumps({
        "tenantId": "ta",
        "requestId": replay_id,
        "pendingId": int(pending_id) if pending_id else None,
        "reason": "sim-stage6c",
    }).encode(),
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
    out = psql_file("select-trigger-stage6c-replay-status.sql", {"tenant_id": "ta", "request_id": replay_id}, tuples=True)
    replay_status = (out.stdout or "").strip()
    if replay_status.startswith("LAUNCHED|") and replay_status != "LAUNCHED|":
        break
    time.sleep(3)

print(f"  [replay] {replay_status}", flush=True)
recovery_outcome = ""
deadline = time.time() + 120
while time.time() < deadline:
    recovery_outcome = (psql_file("select-trigger-stage6d-recovery-outcome.sql", misfire_vars, tuples=True).stdout or "").strip()
    if recovery_outcome == "APPROVED|REQUESTED|SUCCESS|SUCCESS|SNAPSHOT_OK|WORKFLOW_ENABLED":
        break
    time.sleep(2)
print(f"  [recovery-outcome] {recovery_outcome}", flush=True)
if recovery_outcome != "APPROVED|REQUESTED|SUCCESS|SUCCESS|SNAPSHOT_OK|WORKFLOW_ENABLED":
    raise RuntimeError(f"misfire recovery did not complete: {recovery_outcome}")


print(f"==> API task storm {STORM_COUNT}", flush=True)
storm_ids = []
storm_base_date = date.fromisoformat(BIZ) + timedelta(days=1)
for i in range(STORM_COUNT):
    rid = f"{BATCH}-storm-{i:03d}"
    storm_ids.append(rid)
    storm_biz_date = (storm_base_date + timedelta(days=i)).isoformat()
    # 使用不同的 Workflow 业务日测量执行收敛情况，
    # 避免同一 Workflow/同一天的幂等抑制影响结果。
    launch(rid, f"{BATCH}-storm-{i:03d}", storm_biz_date)
print("  [storm launch] all accepted", flush=True)

deadline = time.time() + 240
while time.time() < deadline:
    out = psql_file("count-trigger-stage6c-storm-terminal.sql", {"tenant_id": "ta", "batch_prefix": BATCH}, tuples=True)
    done = int((out.stdout or "0").strip() or "0")
    if done >= STORM_COUNT:
        break
    time.sleep(3)

print("\n-- trigger_stage6c_status --", flush=True)
report_vars = {"tenant_id": "ta", "batch_prefix": BATCH, "start_ts": START_TS}
print(psql_file("select-trigger-stage6c-status.sql", report_vars).stdout, end="")

storm_success = int((psql_file("count-trigger-stage6c-storm-success.sql", {"tenant_id": "ta", "batch_prefix": BATCH}, tuples=True).stdout or "0").strip() or "0")
outcomes = psql_file("select-stage6b-workflow-outcomes.sql", {
    "tenant_id": "ta", "request_ids": ",".join(storm_ids),
}, tuples=True).stdout or ""
outcome_rows = [line.split("|") for line in outcomes.splitlines()]
actual_outcomes = {row[0]: tuple(row[1:]) for row in outcome_rows if len(row) == 5}
bad_outcomes = {rid: actual_outcomes.get(rid) for rid in storm_ids
                if actual_outcomes.get(rid) != ("LAUNCHED", "SUCCESS", "SUCCESS", "SUCCESS")}
if bad_outcomes:
    raise RuntimeError(f"API storm did not reach Workflow/Job success: {bad_outcomes}")

summary = (
    f"scheduled={scheduled_count}|misfirePending={misfire_count}|"
    f"misfireSource=quartz|recovery={recovery_outcome}|replay={replay_status}|storm={storm_success}/{STORM_COUNT}"
)
print(f"\n-- assertion_summary --\n{summary}", flush=True)
if (scheduled_count < 1
        or misfire_count < 1
        or not replay_status.startswith("LAUNCHED|")
        or replay_status == "LAUNCHED|"
        or storm_success < STORM_COUNT):
    print("❌ Trigger Stage6c assertion failed", flush=True)
    sys.exit(1)

print(f"\n==> Stage 6c trigger scenario PASS: batchNo={BATCH} stormCount={STORM_COUNT}", flush=True)
PY
