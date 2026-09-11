#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LOAD_DIR="$ROOT_DIR/load-tests"
# shellcheck source=env.sh
source "$LOAD_DIR/scripts/env.sh"

RUN_ID="${RUN_ID:-p2-process-failure-$(date +%Y%m%d%H%M%S)}"
PROCESS_SOURCE_ROWS="${PROCESS_SOURCE_ROWS:-1000000}"
PROCESS_ACCOUNT_COUNT="${PROCESS_ACCOUNT_COUNT:-100000}"
PROCESS_USERS=1
PROCESS_TENANT_ID="${PROCESS_TENANT_ID:-$LOAD_TEST_TENANT_ID}"
WAIT_TERMINAL_TIMEOUT_SECONDS="${WAIT_TERMINAL_TIMEOUT_SECONDS:-900}"
WORKER_RESTART_WAIT_SECONDS="${WORKER_RESTART_WAIT_SECONDS:-180}"
SKIP_AUTO_CLEANUP="${SKIP_AUTO_CLEANUP:-0}"
export RUN_ID BIZ_DATE PGHOST PGPORT PGUSER PGPASSWORD PLATFORM_DB BUSINESS_DB
export PROCESS_SOURCE_ROWS PROCESS_ACCOUNT_COUNT PROCESS_USERS

REPORT="$LOAD_DIR/target/p2-process-failure-${RUN_ID}.md"
LOG_DIR="$LOAD_DIR/target/p2-process-failure-logs/${RUN_ID}"
PARAM_DIR="$LOAD_DIR/target/p2-process-failure-data/${RUN_ID}"
mkdir -p "$LOG_DIR" "$PARAM_DIR"

psql_platform() {
  psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PLATFORM_DB" -v ON_ERROR_STOP=1 "$@"
}

psql_business() {
  psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$BUSINESS_DB" -v ON_ERROR_STOP=1 "$@"
}

cleanup() {
  local rc=$?
  if [[ "$SKIP_AUTO_CLEANUP" == "1" ]]; then
    echo "SKIP_AUTO_CLEANUP=1, leaving RUN_ID=${RUN_ID} data in place"
    exit "$rc"
  fi
  RUN_ID="$RUN_ID" "$LOAD_DIR/scripts/cleanup-worker-load-data.sh" >&2 || true
  exit "$rc"
}
trap cleanup EXIT

require_tooling() {
  command -v jq >/dev/null || { echo "jq is required" >&2; exit 2; }
  command -v psql >/dev/null || { echo "psql is required" >&2; exit 2; }
}

worker_process_pid() {
  ps -ax -o pid= -o command= \
    | awk '/build\/runtime-jars\/worker-process\.jar/ && /java/ {print $1; exit}'
}

wait_worker_health() {
  local deadline=$((SECONDS + WORKER_RESTART_WAIT_SECONDS))
  while (( SECONDS < deadline )); do
    if curl -fsS --max-time 3 "http://localhost:${WORKER_PROCESS_PORT}/actuator/health" \
      | grep -q '"status":"UP"'; then
      return 0
    fi
    sleep 2
  done
  echo "worker-process did not become healthy within ${WORKER_RESTART_WAIT_SECONDS}s" >&2
  return 1
}

start_worker_process() {
  local jar="$ROOT_DIR/build/runtime-jars/worker-process.jar"
  [[ -f "$jar" ]] || { echo "missing runtime jar: $jar" >&2; exit 2; }
  local fast_opts="${LOCAL_FAST_JVM_OPTS:--XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xshare:off}"
  local log="$ROOT_DIR/logs/app/worker-process.log"
  nohup java --enable-native-access=ALL-UNNAMED ${fast_opts} ${JAVA_OPTS:-} \
    -jar "$jar" --spring.profiles.active=local >>"$log" 2>&1 &
  local pid=$!
  mkdir -p "$ROOT_DIR/logs"
  local pid_file="$ROOT_DIR/logs/start-all.pids"
  local tmp
  tmp="$(mktemp "$ROOT_DIR/logs/start-all.pids.p2.XXXXXX")"
  if [[ -f "$pid_file" ]]; then
    awk '$1 != "worker-process" {print}' "$pid_file" > "$tmp"
  fi
  printf 'worker-process\t%s\t%s\n' "$pid" "$jar" >> "$tmp"
  mv "$tmp" "$pid_file"
  wait_worker_health
}

launch_copy_job() {
  local request_id="$1"
  local params_file="$2"
  local body_file="$PARAM_DIR/${request_id}.request.json"
  jq -n \
    --arg tenant "$PROCESS_TENANT_ID" \
    --arg requestId "$request_id" \
    --arg bizDate "$BIZ_DATE" \
    --slurpfile params "$params_file" \
    '{
      tenantId: $tenant,
      jobCode: "lt_process_copy_job",
      triggerType: "API",
      bizDate: $bizDate,
      requestId: $requestId,
      params: $params[0]
    }' > "$body_file"
  curl -fsS -X POST "$TRIGGER_BASE_URL/api/triggers/launch" \
    -H 'Content-Type: application/json' \
    -H "X-Internal-Secret: $INTERNAL_SECRET" \
    -H "X-Tenant-Id: $PROCESS_TENANT_ID" \
    -H "Idempotency-Key: $request_id" \
    -H "X-Request-Id: $request_id" \
    --data-binary @"$body_file" > "$LOG_DIR/${request_id}.launch.json"
}

request_status() {
  local request_id="$1"
  psql_platform -At \
    -v tenant_id="$PROCESS_TENANT_ID" \
    -v request_id="$request_id" \
    -f "$LOAD_DIR/sql/p2-request-instance-task-status.sql" \
    | head -1
}

wait_task_running() {
  local request_id="$1"
  local timeout="$2"
  local deadline=$((SECONDS + timeout))
  while (( SECONDS < deadline )); do
    local status
    status="$(request_status "$request_id" || true)"
    local task_status
    task_status="$(cut -d '|' -f 6 <<<"$status")"
    if [[ "$task_status" == "RUNNING" ]]; then
      printf '%s\n' "$status"
      return 0
    fi
    sleep 1
  done
  echo "timeout waiting RUNNING for request_id=${request_id}" >&2
  return 1
}

wait_instance_terminal() {
  local request_id="$1"
  local expected="$2"
  local deadline=$((SECONDS + WAIT_TERMINAL_TIMEOUT_SECONDS))
  while (( SECONDS < deadline )); do
    local status instance_status
    status="$(request_status "$request_id" || true)"
    instance_status="$(cut -d '|' -f 2 <<<"$status")"
    case "$instance_status" in
      SUCCESS|FAILED|PARTIAL_FAILED|CANCELLED|TERMINATED|REJECTED)
        printf '%s\n' "$status"
        [[ "$expected" == "*" || "$instance_status" == "$expected" ]] && return 0
        echo "request_id=${request_id} reached ${instance_status}, expected ${expected}" >&2
        return 1
        ;;
    esac
    sleep 3
  done
  echo "timeout waiting terminal for request_id=${request_id}" >&2
  return 1
}

terminate_active_process_backend() {
  local deadline=$((SECONDS + 60))
  while (( SECONDS < deadline )); do
    local pid
    pid="$(psql_business -At -f "$LOAD_DIR/sql/p2-active-process-copy-backend.sql" | head -1)"
    if [[ "$pid" =~ ^[0-9]+$ ]]; then
      psql_business -Atc "select pg_terminate_backend(${pid})" > "$LOG_DIR/pg-terminate-${pid}.txt"
      printf '%s\n' "$pid"
      return 0
    fi
    sleep 1
  done
  echo "timeout finding active process copy backend" >&2
  return 1
}

write_params() {
  local source="$1"
  local target="$2"
  local batch_key="$3"
  local profile="$4"
  jq --arg batchKey "$batch_key" \
     --arg runId "$RUN_ID" \
     --arg profile "$profile" \
     '.batchKey = $batchKey
      | .metadata = ((.metadata // {}) + {runId: $runId, p2Profile: $profile})' \
     "$source" > "$target"
}

write_report() {
  {
    echo "# P2 Process Failure Profile - ${RUN_ID}"
    echo
    echo "- Time UTC finish: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "- Tenant: ${PROCESS_TENANT_ID}"
    echo "- Source rows: ${PROCESS_SOURCE_ROWS}"
    echo "- Account count: ${PROCESS_ACCOUNT_COUNT}"
    echo "- Logs: ${LOG_DIR}"
    echo "- Auto cleanup: $([[ "$SKIP_AUTO_CLEANUP" == "1" ]] && echo disabled || echo enabled)"
    echo
    echo "## Fault Actions"
    echo
    echo "- Kill worker request: ${KILL_REQUEST_ID}"
    echo "- Killed worker pid: ${KILLED_WORKER_PID}"
    echo "- PG terminate request: ${PG_REQUEST_ID}"
    echo "- Terminated PG backend pid: ${TERMINATED_PG_PID}"
    echo "- PG recovery rerun request: ${PG_RERUN_REQUEST_ID}"
    echo
    echo "## Instance Summary"
    echo
    echo '```text'
    psql_platform -P pager=off -F ' | ' -A \
      -v tenant_id="$PROCESS_TENANT_ID" \
      -v run_id="$RUN_ID" \
      -f "$LOAD_DIR/sql/p2-process-failure-summary.sql"
    echo '```'
    echo
    echo "## Business Summary"
    echo
    echo '```text'
    psql_business -P pager=off -F ' | ' -A \
      -v tenant_id="$PROCESS_TENANT_ID" \
      -v run_id="$RUN_ID" \
      -f "$LOAD_DIR/sql/p2-process-business-summary.sql"
    echo '```'
  } > "$REPORT"
}

require_tooling
RUN_STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "==> prepare process load data RUN_ID=${RUN_ID}, rows=${PROCESS_SOURCE_ROWS}"
"$LOAD_DIR/scripts/prepare-worker-load-data.sh"
# shellcheck disable=SC1090
source "$LOAD_DIR/target/worker-load-data/run.env"

KILL_PARAMS="$PARAM_DIR/kill-worker.params.json"
PG_PARAMS="$PARAM_DIR/pg-disconnect.params.json"
write_params "$PROCESS_COPY_PARAMS" "$KILL_PARAMS" "${RUN_ID}-kill-worker-copy" "kill-worker"
write_params "$PROCESS_COPY_PARAMS" "$PG_PARAMS" "${RUN_ID}-pg-disconnect-copy" "pg-disconnect"

KILL_REQUEST_ID="${RUN_ID}-kill-worker"
PG_REQUEST_ID="${RUN_ID}-pg-disconnect"
PG_RERUN_REQUEST_ID="${RUN_ID}-pg-disconnect-rerun"

echo "==> launch kill-worker profile"
launch_copy_job "$KILL_REQUEST_ID" "$KILL_PARAMS"
wait_task_running "$KILL_REQUEST_ID" 120 | tee "$LOG_DIR/kill-worker-running.txt"
KILLED_WORKER_PID="$(worker_process_pid)"
[[ "$KILLED_WORKER_PID" =~ ^[0-9]+$ ]] || { echo "worker-process pid not found" >&2; exit 1; }
kill -9 "$KILLED_WORKER_PID"
echo "killed worker-process pid=${KILLED_WORKER_PID}"
sleep 20
start_worker_process
wait_instance_terminal "$KILL_REQUEST_ID" "SUCCESS" | tee "$LOG_DIR/kill-worker-terminal.txt"

echo "==> launch PG disconnect profile"
launch_copy_job "$PG_REQUEST_ID" "$PG_PARAMS"
wait_task_running "$PG_REQUEST_ID" 120 | tee "$LOG_DIR/pg-disconnect-running.txt"
TERMINATED_PG_PID="$(terminate_active_process_backend)"
echo "terminated PG backend pid=${TERMINATED_PG_PID}"
wait_instance_terminal "$PG_REQUEST_ID" "*" | tee "$LOG_DIR/pg-disconnect-terminal.txt"

echo "==> rerun same batchKey after PG disconnect"
launch_copy_job "$PG_RERUN_REQUEST_ID" "$PG_PARAMS"
wait_instance_terminal "$PG_RERUN_REQUEST_ID" "SUCCESS" | tee "$LOG_DIR/pg-disconnect-rerun-terminal.txt"

write_report
echo "P2 process failure report written: $REPORT"
