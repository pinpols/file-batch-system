#!/usr/bin/env bash
# 用途：验证 Trigger 的调度、重试、暂停与恢复场景。
# 用法：在仓库根目录执行 bash scripts/sim/24-trigger-stage6d.sh；参数与前置环境见下方说明及所属 README。
# =========================================================
# 验证 Trigger P0 的 misfire、cron 高并发触发与 outbox 恢复。
#
# 覆盖:
#   - 高频 cron fire
#   - Quartz 模式下 enabled=false/true 暂停恢复
#   - 持久 MANUAL_APPROVAL misfire 经 Trigger 重建后按原请求审批恢复
#   - requestId / Idempotency-Key 重放去重
#   - trigger_outbox_event FAILED 积压恢复并最终 PUBLISHED
#   - API storm 全终态
#
# SQL seed / 状态注入独立放在 test-data/sim,脚本只负责编排、触发、轮询、断言。
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
SIM_SQL_DIR="$ROOT/scripts/sim/sql"
export SIM_SQL_DIR

export SIM_STAGE_NAME="trigger-stage6d"
# shellcheck source=env-common.sh
source "$ROOT/scripts/sim/env-common.sh"
source "$ROOT/scripts/lib/process.sh"

export STORM_COUNT="${STORM_COUNT:-80}"
export OUTBOX_COUNT="${OUTBOX_COUNT:-12}"
# 仅第二次（已持久化 pending）重启重新构建，用于跨构建恢复验收。
export SIM_TRIGGER_REBUILD_WITH_PENDING="${SIM_TRIGGER_REBUILD_WITH_PENDING:-0}"
case "$SIM_TRIGGER_REBUILD_WITH_PENDING" in
  0|1) ;;
  *) echo "SIM_TRIGGER_REBUILD_WITH_PENDING must be 0 or 1" >&2; exit 2 ;;
esac
if [[ "$SIM_TRIGGER_REBUILD_WITH_PENDING" == 1 && "${SIM_TRIGGER_RESTART_MODE:-restart}" != restart ]]; then
  echo "cross-build recovery requires restart.sh mode" >&2
  exit 2
fi

batch_require_python

cleanup_trigger_fixtures() {
  local original_status=$? cleanup_failed=0 response
  trap - EXIT
  docker exec -i "$PG_CONTAINER" psql -X -U "$POSTGRES_USER" -d "$PLATFORM_DB" \
    -v ON_ERROR_STOP=1 -f /dev/stdin \
    < "$SIM_SQL_DIR/disable-trigger-stage6c-jobs.sql" >/dev/null 2>&1 || cleanup_failed=1
  for workflow_code in WF-TA_TRIGGER_STAGE6C_SCHEDULED WF-TA_TRIGGER_STAGE6C_MISFIRE; do
    if ! response=$(curl -fsS -m 5 -X POST \
      "$TRIGGER_BASE/api/triggers/management/workflow/unregister?tenantId=ta&workflowCode=$workflow_code" \
      -H "X-Tenant-Id: ta" -H "X-Internal-Secret: $INTERNAL_SECRET" 2>/dev/null); then
      cleanup_failed=1
    elif ! "$PYTHON_BIN" -c 'import json,sys; sys.exit(0 if json.load(sys.stdin).get("code") == "SUCCESS" else 1)' <<<"$response"; then
      cleanup_failed=1
    fi
  done
  if [[ "$cleanup_failed" != 0 ]]; then
    echo "❌ Trigger Stage6d fixture cleanup failed" >&2
    if [[ "$original_status" == 0 ]]; then
      exit 1
    fi
  fi
  exit "$original_status"
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
export -f restart_trigger_for_fixture

echo "==> seed trigger stage6d fixtures"
runtime_status=0
sim_container_stack_active || runtime_status=$?
[[ "$runtime_status" -le 1 ]] || exit "$runtime_status"
if [[ "$runtime_status" == 0 ]]; then
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
docker exec -i "$PG_CONTAINER" psql -U "$POSTGRES_USER" -d "$PLATFORM_DB" \
  -v ON_ERROR_STOP=1 -v batch_no="$BATCH_NO" -v biz_date="$BIZ_DATE" \
  -f /dev/stdin < test-data/sim/sim-stage6c-trigger-fixtures.sql >/dev/null
docker exec -i "$PG_CONTAINER" psql -U "$POSTGRES_USER" -d "$PLATFORM_DB" \
  -v ON_ERROR_STOP=1 -v batch_no="$BATCH_NO" -v biz_date="$BIZ_DATE" \
  -f /dev/stdin < test-data/sim/sim-stage6d-trigger-fixtures.sql >/dev/null

echo "==> start trigger after direct Quartz fixture reset"
BUILD=0 restart_trigger_for_fixture >"$REPORT_DIR/trigger-restart.log" 2>&1
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

"$PYTHON_BIN" - <<'PY' 2>&1 | tee "$REPORT_DIR/trigger-stage6d.log"
import hashlib, json, os, subprocess, sys, time, urllib.request
from datetime import date, timedelta
from scripts.sim.launch_contract import workflow_id_for_fixture
from pathlib import Path

BASE = os.environ["TRIGGER_BASE"]
CONSOLE = os.environ["CONSOLE_BASE"]
SECRET = os.environ["INTERNAL_SECRET"]
BIZ = os.environ["BIZ_DATE"]
BATCH = os.environ["BATCH_NO"]
STORM_COUNT = int(os.environ["STORM_COUNT"])
OUTBOX_COUNT = int(os.environ["OUTBOX_COUNT"])
START_TS = os.environ["START_TS"].strip()
API_JOB = "TA_PROCESS_STAGE4_EMPTY_SUCCESS"
CRON_WORKFLOW = "WF-TA_TRIGGER_STAGE6C_SCHEDULED"
SQL_DIR = Path(os.environ["SIM_SQL_DIR"])

def query_file(sql_file, variables=None, tuples=False):
    args = ["docker", "exec", "-i", os.environ["PG_CONTAINER"], "psql", "-X", "-U", os.environ["POSTGRES_USER"], "-d", os.environ["PLATFORM_DB"], "-v", "ON_ERROR_STOP=1", "-P", "pager=off"]
    if tuples:
        args += ["-t", "-A"]
    for key, value in (variables or {}).items():
        args += ["-v", f"{key}={value}"]
    args += ["-f", "/dev/stdin"]
    result = subprocess.run(args, input=(SQL_DIR / sql_file).read_text(encoding="utf-8"), capture_output=True, text=True)
    if result.returncode:
        print(result.stderr, file=sys.stderr, flush=True)
        result.check_returncode()
    return result

def psql_file(path, *vars):
    args = ["docker", "exec", "-i", os.environ["PG_CONTAINER"], "psql", "-U", os.environ["POSTGRES_USER"], "-d", os.environ["PLATFORM_DB"], "-v", "ON_ERROR_STOP=1"]
    for key, value in vars:
        args += ["-v", f"{key}={value}"]
    args += ["-f", "/dev/stdin"]
    with open(path, "rb") as fh:
        return subprocess.run(args, check=True, stdin=fh)

def scalar(sql_file, variables=None):
    out = query_file(sql_file, variables, tuples=True)
    return (out.stdout or "").strip()

def launch(request_id, batch_key, biz_date=BIZ):
    body = {
        "tenantId": "ta",
        "workflowDefinitionId": workflow_id_for_fixture("ta", API_JOB),
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
        if resp.status != 200 or json.loads(text).get("code") != "SUCCESS":
            print(text[:500], flush=True)
            raise RuntimeError(f"launch failed: {request_id}")

def manage(action, workflow_code):
    req = urllib.request.Request(
        f"{BASE}/api/triggers/management/workflow/{action}?tenantId=ta&workflowCode={workflow_code}",
        data=b"",
        headers={
            "X-Tenant-Id": "ta",
            "X-Internal-Secret": SECRET,
            "X-Request-Id": f"{BATCH}-{action}",
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        payload = json.load(resp)
        if resp.status != 200 or payload.get("code") != "SUCCESS":
            raise RuntimeError(f"trigger management {action} failed: {payload}")

def wait_int(label, sql_file, variables, predicate, timeout=180, interval=2):
    deadline = time.time() + timeout
    last = 0
    while time.time() < deadline:
        raw = scalar(sql_file, variables) or "0"
        last = int(raw)
        if predicate(last):
            print(f"  [{label}] {last}", flush=True)
            return last
        time.sleep(interval)
    raise TimeoutError(f"timeout waiting {label}, last={last}")

def manage_all(action):
    req = urllib.request.Request(
        f"{BASE}/api/triggers/management/{action}-all", data=b"",
        headers={"X-Tenant-Id": "ta", "X-Internal-Secret": SECRET}, method="POST",
    )
    with urllib.request.urlopen(req, timeout=30) as response:
        payload = json.load(response)
        if response.status != 200 or payload.get("code") != "SUCCESS":
            raise RuntimeError(f"Quartz {action}-all failed: {payload}")


# 先前故障可能在重启后留下 Quartz 持久化的全局暂停标记。
# 此隔离场景独占调度器状态，并从明确恢复后的基线启动。
manage("register", CRON_WORKFLOW)
manage("register", "WF-TA_TRIGGER_STAGE6C_MISFIRE")
manage_all("resume")

print("==> high-frequency cron fire", flush=True)
scheduled_before = wait_int(
    "cron-fire",
    "count-trigger-stage6c-scheduled.sql",
    {"tenant_id": "ta", "start_ts": START_TS},
    lambda v: v >= 1,
    timeout=120,
)

print("==> pause and resume scheduled trigger", flush=True)
manage("pause", CRON_WORKFLOW)
scheduled_vars = {"tenant_id": "ta", "start_ts": START_TS}
paused_count = int(scalar("count-trigger-stage6c-scheduled.sql", scheduled_vars) or "0")
time.sleep(6)
paused_after = int(scalar("count-trigger-stage6c-scheduled.sql", scheduled_vars) or "0")
print(f"  [paused-stable] {paused_count}->{paused_after}", flush=True)
if paused_after != paused_count:
    raise RuntimeError("scheduled trigger fired while paused")

manage("resume", CRON_WORKFLOW)
resumed_count = wait_int(
    "resume-fire",
    "count-trigger-stage6c-scheduled.sql",
    scheduled_vars,
    lambda v: v >= paused_count + 1,
    timeout=180,
)

print("==> misfire pending + catch-up replay", flush=True)
wait_int(
    "quartz-misfire-registered",
    "count-trigger-stage6c-quartz-misfire.sql",
    {},
    lambda v: v >= 1,
    timeout=90,
)
# 通过管理 API 暂停真实 Quartz 调度。保持真实的
# 下一次触发时间和预先规划的 occurrence 不变，之后在阈值过后恢复调度。

try:
    manage_all("pause")
    # 注册触发器与持久化规划触发记录是两个独立操作。并发
    # 配置修订可能使规划事务回滚；让下一轮规划在
    # Quartz 暂停期间完成，不要把已注册的触发器误认为已规划触发。
    fire_millis = wait_int(
        "planned-misfire",
        "select-trigger-stage6d-planned-misfire.sql",
        {},
        lambda value: value > 0,
        timeout=90,
        interval=1,
    )
    # Quartz 默认 misfire 阈值为 60 秒。额外预留 5 秒余量。
    delay = max(0, fire_millis / 1000 + 65 - time.time())
    print(f"  [misfire-pause] originalFireTime={fire_millis}, pauseSeconds={delay:.1f}", flush=True)
    time.sleep(delay)
finally:
    manage_all("resume")
misfire_source = "quartz"
misfire_vars = {"tenant_id": "ta", "start_ts": START_TS, "scheduled_fire_millis": fire_millis}
misfire_count = wait_int(
    "misfire-pending",
    "count-trigger-stage6d-pending.sql",
    misfire_vars,
    lambda v: v >= 1,
    timeout=120,
)
pending_link = scalar("select-trigger-stage6d-pending-link.sql", misfire_vars)
pending_id, linked_request_id = pending_link.split("|", 1)
if not linked_request_id:
    raise RuntimeError(f"misfire pending not linked to catch-up request: pending={pending_id}")
# 通过支持的 Console API 完整停用 Workflow，再沿 Console 使用的 requestId 审批契约恢复原义务。
misfire_workflow_id = workflow_id_for_fixture("ta", "TA_TRIGGER_STAGE6C_MISFIRE")
disable_request = urllib.request.Request(
    f"{CONSOLE}/api/console/workflow-definitions/{misfire_workflow_id}",
    data=json.dumps({"tenantId": "ta", "enabled": False}).encode(),
    method="PATCH",
    headers={"Content-Type": "application/json", "X-Tenant-Id": "ta", "X-Internal-Secret": SECRET},
)
with urllib.request.urlopen(disable_request, timeout=30) as response:
    disabled = json.load(response)
    if disabled.get("code") != "SUCCESS":
        raise RuntimeError(f"Workflow disable failed: {disabled}")
print(f"  [workflow-disabled] id={misfire_workflow_id}", flush=True)
pending_snapshot_text = scalar("select-trigger-stage6d-pending-snapshot.sql", misfire_vars)
if not pending_snapshot_text:
    raise RuntimeError("persisted misfire snapshot is missing before Trigger restart")
pending_snapshot = json.loads(pending_snapshot_text)
if (pending_snapshot["pendingStatus"] != "PENDING"
        or not pending_snapshot["payloadFingerprint"]
        or pending_snapshot["workflowEnabled"]
        or pending_snapshot["outboxCount"] != 0):
    raise RuntimeError("misfire obligation is not waiting for approval before restart")
runtime_jar = Path(os.environ["SIM_ROOT"]) / "build/runtime-jars/trigger.jar"
with runtime_jar.open("rb") as jar_stream:
    old_jar_sha = hashlib.file_digest(jar_stream, "sha256").hexdigest()
rebuild_pending = os.environ["SIM_TRIGGER_REBUILD_WITH_PENDING"]
restart_log = Path(os.environ["REPORT_DIR"]) / "trigger-restart-with-pending.log"
print(f"  [restart-with-pending] pending={pending_id} request={linked_request_id}", flush=True)
with restart_log.open("w", encoding="utf-8") as output:
    subprocess.run(
        ["bash", "-c", "restart_trigger_for_fixture"],
        env=dict(os.environ, ROOT=os.environ["SIM_ROOT"], BUILD=rebuild_pending),
        stdout=output,
        stderr=subprocess.STDOUT,
        check=True,
    )
with runtime_jar.open("rb") as jar_stream:
    new_jar_sha = hashlib.file_digest(jar_stream, "sha256").hexdigest()
build_evidence = {"rebuildRequested": rebuild_pending == "1", "beforeSha256": old_jar_sha, "afterSha256": new_jar_sha}
(Path(os.environ["REPORT_DIR"]) / "trigger-build-identity.json").write_text(
    json.dumps(build_evidence, indent=2) + "\n", encoding="utf-8"
)
if rebuild_pending == "1" and old_jar_sha == new_jar_sha:
    raise RuntimeError("cross-build recovery requires different Trigger jar SHA-256 values")
health_deadline = time.monotonic() + 120
while True:
    try:
        with urllib.request.urlopen(f"{BASE}/actuator/health", timeout=3) as response:
            if response.status == 200:
                break
    except (OSError, urllib.error.URLError):
        pass
    if time.monotonic() >= health_deadline:
        raise RuntimeError(f"Trigger did not become healthy after restart; see {restart_log}")
    time.sleep(1)
restored_snapshot_text = scalar("select-trigger-stage6d-pending-snapshot.sql", misfire_vars)
if not restored_snapshot_text or json.loads(restored_snapshot_text) != pending_snapshot:
    raise RuntimeError("Trigger restart changed the persisted misfire identity, state or snapshot")
print(f"  [pending-after-restart] pending={pending_id} request={linked_request_id} snapshot=UNCHANGED", flush=True)
req = urllib.request.Request(
    f"{BASE}/api/triggers/catch-up/approve",
    data=json.dumps({"tenantId": "ta", "requestId": linked_request_id, "reason": "sim-stage6d"}).encode(),
    headers={
        "Content-Type": "application/json",
        "X-Tenant-Id": "ta",
        "X-Internal-Secret": SECRET,
        "Idempotency-Key": linked_request_id + "-approve",
        "X-Request-Id": linked_request_id + "-approve",
    },
)
with urllib.request.urlopen(req, timeout=30) as resp:
    text = resp.read().decode()
    if resp.status != 200 or json.loads(text).get("code") != "SUCCESS":
        print(text[:500], flush=True)
        raise RuntimeError("catch-up approve failed")

deadline = time.time() + 120
replay_status = ""
while time.time() < deadline:
    replay_status = scalar("select-trigger-stage6d-replay-status.sql", {"tenant_id": "ta", "request_id": linked_request_id})
    if replay_status.startswith("LAUNCHED|") and replay_status != "LAUNCHED|":
        break
    time.sleep(2)
print(f"  [replay] pending={pending_id} request={linked_request_id} status={replay_status}", flush=True)
if not replay_status.startswith("LAUNCHED|") or replay_status == "LAUNCHED|":
    raise RuntimeError("catch-up replay did not launch")

recovery_outcome = ""
deadline = time.time() + 120
while time.time() < deadline:
    recovery_outcome = scalar("select-trigger-stage6d-recovery-outcome.sql", misfire_vars)
    if recovery_outcome == "APPROVED|REQUESTED|SUCCESS|SUCCESS|SNAPSHOT_OK|WORKFLOW_DISABLED":
        break
    time.sleep(2)
print(f"  [recovery-outcome] {recovery_outcome}", flush=True)
if recovery_outcome != "APPROVED|REQUESTED|SUCCESS|SUCCESS|SNAPSHOT_OK|WORKFLOW_DISABLED":
    raise RuntimeError(f"misfire recovery did not complete: {recovery_outcome}")

print("==> requestId dedup replay", flush=True)
dedup_id = BATCH + "-dedup"
dedup_biz_date = (date.fromisoformat(BIZ) + timedelta(days=OUTBOX_COUNT + STORM_COUNT + 1)).isoformat()
launch(dedup_id, BATCH + "-dedup", dedup_biz_date)
launch(dedup_id, BATCH + "-dedup", dedup_biz_date)
dedup_summary = wait_int(
    "dedup-instance",
    "count-trigger-stage6d-dedup-instances.sql",
    {"tenant_id": "ta", "request_id": dedup_id},
    lambda v: v == 1,
    timeout=90,
)
dedup_rows = int(scalar("count-trigger-stage6d-dedup-rows.sql", {"tenant_id": "ta", "request_id": dedup_id}) or "0")
if dedup_rows != 1 or dedup_summary != 1:
    raise RuntimeError(f"dedup failed: rows={dedup_rows}, instances={dedup_summary}")

print(f"==> trigger outbox retry injection {OUTBOX_COUNT}", flush=True)
outbox_base_date = date.fromisoformat(BIZ) + timedelta(days=1)
for i in range(OUTBOX_COUNT):
    outbox_biz_date = (outbox_base_date + timedelta(days=i)).isoformat()
    launch(f"{BATCH}-outbox-{i:03d}", f"{BATCH}-outbox-{i:03d}", outbox_biz_date)
wait_int(
    "outbox-created",
    "count-trigger-stage6d-outbox.sql",
    {"tenant_id": "ta", "request_prefix": BATCH + "-outbox-"},
    lambda v: v >= OUTBOX_COUNT,
    timeout=90,
)
psql_file("test-data/sim/sim-stage6d-trigger-outbox-retry.sql", ("request_prefix", BATCH + "-outbox-"))
outbox_published = wait_int(
    "outbox-published",
    "count-trigger-stage6d-outbox-published.sql",
    {"tenant_id": "ta", "request_prefix": BATCH + "-outbox-"},
    lambda v: v >= OUTBOX_COUNT,
    timeout=180,
)
outbox_instances = wait_int(
    "outbox-launched",
    "count-trigger-stage6d-outbox-instances.sql",
    {"tenant_id": "ta", "request_prefix": BATCH + "-outbox-"},
    lambda v: v >= OUTBOX_COUNT,
    timeout=180,
)
if outbox_instances != OUTBOX_COUNT:
    raise RuntimeError(f"outbox retry duplicated/lost instances: {outbox_instances}/{OUTBOX_COUNT}")

print(f"==> API storm {STORM_COUNT}", flush=True)
storm_base_date = date.fromisoformat(BIZ) + timedelta(days=OUTBOX_COUNT + 1)
for i in range(STORM_COUNT):
    storm_biz_date = (storm_base_date + timedelta(days=i)).isoformat()
    launch(f"{BATCH}-storm-{i:03d}", f"{BATCH}-storm-{i:03d}", storm_biz_date)
terminal_count = wait_int(
    "storm-terminal",
    "count-trigger-stage6d-storm-terminal.sql",
    {"tenant_id": "ta", "request_prefix": BATCH + "-storm-"},
    lambda v: v >= STORM_COUNT,
    timeout=240,
)

expected_api_requests = [dedup_id,
    *(f"{BATCH}-outbox-{i:03d}" for i in range(OUTBOX_COUNT)),
    *(f"{BATCH}-storm-{i:03d}" for i in range(STORM_COUNT))]
outcomes = query_file("select-stage6b-workflow-outcomes.sql", {
    "tenant_id": "ta", "request_ids": ",".join(expected_api_requests),
}, tuples=True).stdout or ""
outcome_rows = [line.split("|") for line in outcomes.splitlines()]
actual_outcomes = {row[0]: tuple(row[1:]) for row in outcome_rows if len(row) == 5}
bad_outcomes = {rid: actual_outcomes.get(rid) for rid in expected_api_requests
                if actual_outcomes.get(rid) != ("LAUNCHED", "SUCCESS", "SUCCESS", "SUCCESS")}
if bad_outcomes:
    raise RuntimeError(f"API/outbox requests did not reach Workflow/Job success: {bad_outcomes}")
print(f"  [api-workflow-job-success] {len(expected_api_requests)}/{len(expected_api_requests)}", flush=True)

batch_vars = {"tenant_id": "ta", "request_prefix": BATCH}
pending_outbox = int(scalar("count-trigger-stage6d-pending-outbox.sql", batch_vars) or "0")
non_terminal = int(scalar("count-trigger-stage6d-non-terminal.sql", batch_vars) or "0")

print("\n-- trigger_stage6d_status --", flush=True)
print(query_file("select-trigger-stage6d-request-status.sql", batch_vars).stdout, end="")
print(query_file("select-trigger-stage6d-outbox-status.sql", batch_vars).stdout, end="")

summary = (
    f"cron_before={scheduled_before}|pause={paused_count}->{paused_after}|resume={resumed_count}|"
    f"misfire={misfire_count}|misfire_source={misfire_source}|replay={replay_status}|"
    f"recovery={recovery_outcome}|"
    f"dedup={dedup_rows}/{dedup_summary}|"
    f"outbox={outbox_published}/{OUTBOX_COUNT}:instances={outbox_instances}|"
    f"storm_terminal={terminal_count}/{STORM_COUNT}|pending_outbox={pending_outbox}|non_terminal={non_terminal}"
)
print(f"\n-- assertion_summary --\n{summary}", flush=True)
if (
    scheduled_before < 1
    or paused_after != paused_count
    or resumed_count < paused_count + 1
    or misfire_count < 1
    or not replay_status.startswith("LAUNCHED|")
    or dedup_rows != 1
    or dedup_summary != 1
    or outbox_published < OUTBOX_COUNT
    or outbox_instances != OUTBOX_COUNT
    or terminal_count < STORM_COUNT
    or pending_outbox != 0
    or non_terminal != 0
):
    print("❌ Trigger Stage6d assertion failed", flush=True)
    sys.exit(1)

print(f"\n==> Stage 6d trigger P0 scenario PASS: batchNo={BATCH}", flush=True)
PY
