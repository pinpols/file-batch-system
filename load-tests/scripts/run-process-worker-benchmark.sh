#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LOAD_DIR="$ROOT_DIR/load-tests"
# shellcheck source=env.sh
source "$LOAD_DIR/scripts/env.sh"

PROCESS_SCENARIOS="${PROCESS_SCENARIOS:-aggregate,copy,idempotency}"
PROCESS_SOURCE_ROWS="${PROCESS_SOURCE_ROWS:-5000}"
PROCESS_ACCOUNT_COUNT="${PROCESS_ACCOUNT_COUNT:-500}"
PROCESS_USERS="${PROCESS_USERS:-1}"
RAMP_SECONDS="${RAMP_SECONDS:-1}"
PIPELINE_MAX_POLLS="${PIPELINE_MAX_POLLS:-0}"
PIPELINE_POLL_INTERVAL_SEC="${PIPELINE_POLL_INTERVAL_SEC:-2}"
WAIT_TERMINAL_TIMEOUT_SECONDS="${WAIT_TERMINAL_TIMEOUT_SECONDS:-600}"
MAX_ERROR_PCT="${MAX_ERROR_PCT:-20.0}"

KAFKA_LAG_GROUP_REGEX="${KAFKA_LAG_GROUP_REGEX:-batch-worker-process|orchestrator-trigger-launch}"
KAFKA_HOST_BOOTSTRAP="${KAFKA_HOST_BOOTSTRAP:-localhost:${KAFKA_HOST_PORT:-19092}}"
KAFKA_CONTAINER_BOOTSTRAP="${KAFKA_CONTAINER_BOOTSTRAP:-kafka:29092}"
BATCH_SCRIPT_RUNTIME="${BATCH_SCRIPT_RUNTIME:-auto}"


RUN_ID="${RUN_ID:-prcw-$(date +%Y%m%d%H%M%S)}"
export RUN_ID BIZ_DATE PGHOST PGPORT PGUSER PGPASSWORD PLATFORM_DB BUSINESS_DB
export PROCESS_SOURCE_ROWS PROCESS_ACCOUNT_COUNT

SKIP_AUTO_CLEANUP="${SKIP_AUTO_CLEANUP:-0}"
on_exit_cleanup() {
  local rc=$?
  if [[ "$SKIP_AUTO_CLEANUP" == "1" ]]; then
    echo "SKIP_AUTO_CLEANUP=1, leaving RUN_ID=${RUN_ID} data in place for inspection"
    exit $rc
  fi
  echo "Auto-cleanup RUN_ID=${RUN_ID} ..." >&2
  RUN_ID="$RUN_ID" "$LOAD_DIR/scripts/cleanup-worker-load-data.sh" >&2 || \
    echo "WARN: cleanup-worker-load-data failed for RUN_ID=${RUN_ID}" >&2
  exit $rc
}
trap on_exit_cleanup EXIT

psql_platform() {
  psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PLATFORM_DB" -v ON_ERROR_STOP=1 "$@"
}

psql_business() {
  psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$BUSINESS_DB" -v ON_ERROR_STOP=1 "$@"
}

csv_contains() {
  local needle="$1" csv=",$2,"
  [[ "$csv" == *",$needle,"* ]]
}

require_tooling() {
  command -v psql >/dev/null || { echo "psql is required" >&2; exit 2; }
  command -v jq >/dev/null || { echo "jq is required" >&2; exit 2; }
}

kafka_lag_snapshot() {
  local group_regex="${1:-$KAFKA_LAG_GROUP_REGEX}"
  local kafka_cli kafka_container output
  if [[ -n "${KAFKA_CONSUMER_GROUPS_BIN:-}" && -x "${KAFKA_CONSUMER_GROUPS_BIN}" ]]; then
    kafka_cli="${KAFKA_CONSUMER_GROUPS_BIN}"
  elif [[ -n "${KAFKA_BIN_DIR:-}" && -x "${KAFKA_BIN_DIR%/}/kafka-consumer-groups.sh" ]]; then
    kafka_cli="${KAFKA_BIN_DIR%/}/kafka-consumer-groups.sh"
  elif command -v kafka-consumer-groups.sh >/dev/null 2>&1; then
    kafka_cli="$(command -v kafka-consumer-groups.sh)"
  fi

  if [[ "$BATCH_SCRIPT_RUNTIME" != "docker" && -n "${kafka_cli:-}" ]]; then
    "$kafka_cli" --bootstrap-server "$KAFKA_HOST_BOOTSTRAP" --describe --all-groups 2>/dev/null \
      | awk -v re="$group_regex" 'NR==1 || $1 ~ re {print}' \
      || echo "kafka lag unavailable: host kafka-consumer-groups.sh failed"
    return 0
  fi

  kafka_container="$(docker ps --format '{{.Names}}' 2>/dev/null | grep -E 'kafka$|kafka-1' | head -1 || true)"
  if [[ "$BATCH_SCRIPT_RUNTIME" != "host" && -n "$kafka_container" ]]; then
    output="$(
      docker exec -i "$kafka_container" "$KAFKA_CONTAINER_BIN_DIR/kafka-consumer-groups.sh" \
        --bootstrap-server "$KAFKA_CONTAINER_BOOTSTRAP" --describe --all-groups 2>&1 || true
    )"
    if [[ "$output" != *"No such file"* && "$output" != *"executable file not found"* && "$output" != *"Error:"* ]]; then
      printf '%s\n' "$output" | awk -v re="$group_regex" 'NR==1 || $1 ~ re {print}'
      return 0
    fi
    echo "kafka lag via container failed: ${output}" >&2
  fi

  echo "kafka lag unavailable: kafka-consumer-groups.sh not found in container or host"
}

docker_stats_snapshot() {
  if ! command -v docker >/dev/null 2>&1; then
    echo "docker unavailable"
    return 0
  fi
  local names
  names="$(docker ps --format '{{.Names}}' | grep -E 'postgres|pg|process|orchestrator|kafka' || true)"
  if [[ -z "$names" ]]; then
    echo "no matching docker containers"
    return 0
  fi
  # shellcheck disable=SC2086
  docker stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.BlockIO}}\t{{.NetIO}}' \
    $names 2>/dev/null \
    || echo "docker stats unavailable"
}

pg_stat_snapshot() {
  {
    echo "## pg_stat_database"
    psql_business -P pager=off -F ' | ' -A -f "$LOAD_DIR/sql/process-pg-stat-database.sql"
    echo
    echo "## pg_stat_wal"
    psql_business -P pager=off -F ' | ' -A -f "$LOAD_DIR/sql/process-pg-stat-wal.sql" 2>/dev/null || echo "pg_stat_wal unavailable"
    echo
    echo "## relation_sizes"
    psql_business -P pager=off -F ' | ' -A -f "$LOAD_DIR/sql/process-relation-sizes.sql"
    echo
    echo "## table_stats"
    psql_business -P pager=off -F ' | ' -A -f "$LOAD_DIR/sql/process-table-stats.sql"
  }
}

wait_job_terminal() {
  local label="$1"
  local job_code="$2"
  local expected_total="$3"
  local elapsed=0
  while [[ "$elapsed" -lt "$WAIT_TERMINAL_TIMEOUT_SECONDS" ]]; do
    local counts total terminal
    counts="$(
      psql_platform -At -v tenant_id="$LOAD_TEST_TENANT_ID" -v job_code="$job_code" -v run_id="$RUN_ID" \
        -f "$LOAD_DIR/sql/worker-run-terminal-counts.sql"
    )"
    total="${counts%%|*}"
    terminal="${counts##*|}"
    if [[ "$total" -ge "$expected_total" && "$terminal" -ge "$expected_total" ]]; then
      echo "==> ${label}: terminal instances ${terminal}/${total}"
      return 0
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
  echo "==> ${label}: timed out waiting for terminal instances" >&2
  return 1
}

run_process_job() {
  local label="$1"
  local job_code="$2"
  local params_file="$3"
  local users="$4"
  local log_file="$LOG_DIR/${label}.log"
  local baseline_total expected_total

  baseline_total="$(
    psql_platform -At -v tenant_id="$LOAD_TEST_TENANT_ID" -v job_code="$job_code" -v run_id="$RUN_ID" \
      -f "$LOAD_DIR/sql/process-run-count.sql"
  )"
  expected_total=$((baseline_total + users))

  echo "==> ${label}: job=${job_code}, users=${users}, params=${params_file}"
  (
    cd "$LOAD_DIR"
    mvn gatling:test \
      -Dsimulation=io.github.pinpols.batch.loadtest.simulations.LaunchPipelineCompletionSimulation \
      -Dtrigger.baseUrl="$TRIGGER_BASE_URL" \
      -Dconsole.baseUrl="$CONSOLE_BASE_URL" \
      -Dorchestrator.baseUrl="$ORCHESTRATOR_BASE_URL" \
      -Dinternal.secret="$INTERNAL_SECRET" \
      -DtenantId="$LOAD_TEST_TENANT_ID" \
      -DjobCode="$job_code" \
      -DbizDate="$BIZ_DATE" \
      -Dlaunch.paramsJsonFile="$params_file" \
      -Dpipeline.completion.users="$users" \
      -Dramp.seconds="$RAMP_SECONDS" \
      -Dpipeline.maxPolls="$PIPELINE_MAX_POLLS" \
      -Dpipeline.pollIntervalSec="$PIPELINE_POLL_INTERVAL_SEC" \
      -Dslo.write.p95ms=5000 \
      -Dslo.read.p99ms=5000 \
      -Dslo.maxErrorPct="$MAX_ERROR_PCT" \
      -Dconsole.accessToken="load-test-token" \
      --batch-mode
  ) | tee "$log_file"

  wait_job_terminal "$label" "$job_code" "$expected_total"
}

write_fixed_batch_params() {
  local source="$1"
  local target="$2"
  jq --arg batchKey "${RUN_ID}-fixed-process-copy" \
     --arg module "process-idempotency" \
     '.batchKey = $batchKey | .metadata.benchmarkModule = $module' \
     "$source" > "$target"
}

write_report() {
  {
    echo "# Process Worker Benchmark - ${RUN_ID}"
    echo
    echo "- Time window UTC: ${RUN_STARTED_AT} - $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "- Scenarios: ${PROCESS_SCENARIOS}"
    echo "- Source rows: ${PROCESS_SOURCE_ROWS}"
    echo "- Account cardinality: ${PROCESS_ACCOUNT_COUNT}"
    echo "- Users per scenario: ${PROCESS_USERS}"
    echo "- Logs: ${LOG_DIR}"
    echo "- Auto cleanup: $([[ "$SKIP_AUTO_CLEANUP" == "1" ]] && echo disabled || echo enabled)"
    echo
    echo "## Instance Completion"
    echo
    echo '```text'
    psql_platform -P pager=off -F ' | ' -A -v tenant_id="$LOAD_TEST_TENANT_ID" -v run_id="$RUN_ID" \
      -f "$LOAD_DIR/sql/process-instance-completion.sql"
    echo '```'
    echo
    echo "## Stage Duration"
    echo
    echo '```text'
    psql_platform -P pager=off -F ' | ' -A -v tenant_id="$LOAD_TEST_TENANT_ID" -v run_id="$RUN_ID" \
      -f "$LOAD_DIR/sql/process-stage-duration.sql"
    echo '```'
    echo
    echo "## Rows And Throughput"
    echo
    echo '```text'
    psql_business -P pager=off -F ' | ' -A -v tenant_id="$LOAD_TEST_TENANT_ID" -v run_id="$RUN_ID" \
      -f "$LOAD_DIR/sql/process-business-rows.sql"
    echo
    psql_platform -P pager=off -F ' | ' -A -v tenant_id="$LOAD_TEST_TENANT_ID" -v run_id="$RUN_ID" \
      -v account_count="$PROCESS_ACCOUNT_COUNT" -v source_rows="$PROCESS_SOURCE_ROWS" \
      -f "$LOAD_DIR/sql/process-throughput.sql"
    echo '```'
    echo
    echo "## Task Latency"
    echo
    echo '```text'
    psql_platform -P pager=off -F ' | ' -A -v tenant_id="$LOAD_TEST_TENANT_ID" -v run_id="$RUN_ID" \
      -f "$LOAD_DIR/sql/process-task-latency.sql"
    echo '```'
    echo
    echo "## PG Snapshot Before"
    echo
    echo '```text'
    cat "$LOG_DIR/pg-before.txt"
    echo '```'
    echo
    echo "## PG Snapshot After"
    echo
    echo '```text'
    cat "$LOG_DIR/pg-after.txt"
    echo '```'
    echo
    echo "## Docker Stats"
    echo
    echo '```text'
    echo "--- before ---"
    cat "$LOG_DIR/docker-before.txt"
    echo
    echo "--- after ---"
    cat "$LOG_DIR/docker-after.txt"
    echo '```'
    echo
    echo "## Kafka Lag"
    echo
    echo '```text'
    echo "--- before ---"
    cat "$LOG_DIR/kafka-lag-before.txt"
    echo
    echo "--- after ---"
    cat "$LOG_DIR/kafka-lag-after.txt"
    echo '```'
    echo
    echo "## Not Covered"
    echo
    echo "- Failure injection/recovery: run separately; this script is the success-path process pressure profile."
    echo "- 1000w default run: explicitly set PROCESS_SOURCE_ROWS=10000000 to avoid accidental large local writes."
    echo "- Multi-shard process copy: run separately when task/range split planning is enabled."
  } > "$REPORT"
}

require_tooling
"$LOAD_DIR/scripts/prepare-worker-load-data.sh"
# shellcheck disable=SC1090
source "$LOAD_DIR/target/worker-load-data/run.env"

REPORT="$LOAD_DIR/target/process-worker-report-${RUN_ID}.md"
LOG_DIR="$LOAD_DIR/target/process-worker-logs/${RUN_ID}"
PARAM_DIR="$LOAD_DIR/target/process-worker-data/${RUN_ID}"
mkdir -p "$LOG_DIR" "$PARAM_DIR"

RUN_STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
pg_stat_snapshot > "$LOG_DIR/pg-before.txt"
docker_stats_snapshot > "$LOG_DIR/docker-before.txt"
kafka_lag_snapshot > "$LOG_DIR/kafka-lag-before.txt"

if csv_contains aggregate "$PROCESS_SCENARIOS"; then
  run_process_job aggregate lt_process_sql_job "$PROCESS_PARAMS" "$PROCESS_USERS" || true
fi

if csv_contains copy "$PROCESS_SCENARIOS"; then
  run_process_job copy lt_process_copy_job "$PROCESS_COPY_PARAMS" "$PROCESS_USERS" || true
fi

if csv_contains idempotency "$PROCESS_SCENARIOS"; then
  write_fixed_batch_params "$PROCESS_COPY_PARAMS" "$PARAM_DIR/process-copy-fixed.params.json"
  run_process_job idempotency-first lt_process_copy_job "$PARAM_DIR/process-copy-fixed.params.json" 1 || true
  run_process_job idempotency-second lt_process_copy_job "$PARAM_DIR/process-copy-fixed.params.json" 1 || true
fi

kafka_lag_snapshot > "$LOG_DIR/kafka-lag-after.txt"
docker_stats_snapshot > "$LOG_DIR/docker-after.txt"
pg_stat_snapshot > "$LOG_DIR/pg-after.txt"
write_report

echo "Process worker benchmark report written: $REPORT"
