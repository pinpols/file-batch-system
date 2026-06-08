#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LOAD_DIR="$ROOT_DIR/load-tests"

COMPOSE_ENV_FILE="${COMPOSE_ENV_FILE:-$ROOT_DIR/.env.local}"
if [[ -f "$COMPOSE_ENV_FILE" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$COMPOSE_ENV_FILE"
  set +a
fi

TRIGGER_BASE_URL="${TRIGGER_BASE_URL:-http://localhost:18081}"
CONSOLE_BASE_URL="${CONSOLE_BASE_URL:-http://localhost:18080}"
ORCHESTRATOR_BASE_URL="${ORCHESTRATOR_BASE_URL:-http://localhost:18082}"
INTERNAL_SECRET="${INTERNAL_SECRET:-${BATCH_INTERNAL_SECRET:-internal-secret}}"
BIZ_DATE="${BIZ_DATE:-2026-05-05}"

MODULES_CSV="${MODULES_CSV:-process,dispatch,atomic,trigger}"
CONTROL_PLANE_MODE="${CONTROL_PLANE_MODE:-sequential}"
USERS="${USERS:-4}"
RAMP_SECONDS="${RAMP_SECONDS:-5}"
PIPELINE_MAX_POLLS="${PIPELINE_MAX_POLLS:-0}"
PIPELINE_POLL_INTERVAL_SEC="${PIPELINE_POLL_INTERVAL_SEC:-2}"
WAIT_TERMINAL_TIMEOUT_SECONDS="${WAIT_TERMINAL_TIMEOUT_SECONDS:-300}"
WAIT_TERMINAL_MIN_INSTANCES="${WAIT_TERMINAL_MIN_INSTANCES:-1}"
MAX_ERROR_PCT="${MAX_ERROR_PCT:-20.0}"

PROCESS_LAUNCH_RPS="${PROCESS_LAUNCH_RPS:-1.0}"
DISPATCH_LAUNCH_RPS="${DISPATCH_LAUNCH_RPS:-1.0}"
ATOMIC_LAUNCH_RPS="${ATOMIC_LAUNCH_RPS:-1.0}"
TRIGGER_LAUNCH_RPS="${TRIGGER_LAUNCH_RPS:-5.0}"
TRIGGER_READ_RPS="${TRIGGER_READ_RPS:-2.0}"
TRIGGER_DURATION_SECONDS="${TRIGGER_DURATION_SECONDS:-120}"
TRIGGER_JOB_CODE="${TRIGGER_JOB_CODE:-atomic_sql_demo}"
SCHEDULING_CONSOLE_READS="${SCHEDULING_CONSOLE_READS:-false}"

ATOMIC_JOBS_CSV="${ATOMIC_JOBS_CSV:-atomic_sql_demo}"
KAFKA_LAG_GROUP_REGEX="${KAFKA_LAG_GROUP_REGEX:-batch-worker-(process|dispatch|atomic)|orchestrator-trigger-launch}"
KAFKA_HOST_BOOTSTRAP="${KAFKA_HOST_BOOTSTRAP:-localhost:${KAFKA_HOST_PORT:-19092}}"
KAFKA_CONTAINER_BOOTSTRAP="${KAFKA_CONTAINER_BOOTSTRAP:-kafka:29092}"

PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-15432}"
PGUSER="${PGUSER:-batch_user}"
PGPASSWORD="${PGPASSWORD:-batch_pass_123}"
PLATFORM_DB="${PLATFORM_DB:-batch_platform}"
BUSINESS_DB="${BUSINESS_DB:-batch_business}"
export PGPASSWORD

RUN_ID="${RUN_ID:-ctlw-$(date +%Y%m%d%H%M%S)}"
export RUN_ID BIZ_DATE PGHOST PGPORT PGUSER PGPASSWORD PLATFORM_DB BUSINESS_DB

SKIP_AUTO_CLEANUP="${SKIP_AUTO_CLEANUP:-0}"
CLEANUP_ONLY="${CLEANUP_ONLY:-0}"
on_exit_cleanup() {
  local rc=$?
  if [[ "$SKIP_AUTO_CLEANUP" == "1" ]]; then
    echo "SKIP_AUTO_CLEANUP=1, leaving RUN_ID=${RUN_ID} data in place for inspection"
    exit $rc
  fi
  echo "Auto-cleanup RUN_ID=${RUN_ID} ..." >&2
  RUN_ID="$RUN_ID" "$LOAD_DIR/scripts/cleanup-worker-load-data.sh" >&2 || \
    echo "WARN: cleanup-worker-load-data failed for RUN_ID=${RUN_ID}" >&2
  cleanup_atomic_trigger >&2 || echo "WARN: atomic/trigger cleanup failed for RUN_ID=${RUN_ID}" >&2
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

cleanup_atomic_trigger() {
  psql_platform -v run_id="$RUN_ID" -f "$LOAD_DIR/sql/cleanup-control-plane-worker.sql"
}

require_tooling() {
  command -v psql >/dev/null || { echo "psql is required" >&2; exit 2; }
  command -v jq >/dev/null || { echo "jq is required" >&2; exit 2; }
}

login_token() {
  local response token
  response="$(
    curl -i -fsS -X POST "$CONSOLE_BASE_URL/api/console/auth/login" \
    -H 'Content-Type: application/json' \
    -d '{"username":"admin","password":"admin123"}' \
  )"
  token="$(printf '%s\n' "$response" | tr -d '\r' | sed -n 's/^Set-Cookie: batch_console_token=\([^;]*\).*/\1/p' | head -1)"
  if [[ -z "$token" ]]; then
    token="$(printf '%s\n' "$response" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p' | head -1)"
  fi
  printf '%s\n' "$token"
}

kafka_lag_snapshot() {
  local group_regex="${1:-$KAFKA_LAG_GROUP_REGEX}"
  local kafka_container output
  kafka_container="$(docker ps --format '{{.Names}}' 2>/dev/null | grep -E 'kafka$|kafka-1' | head -1 || true)"
  if [[ -n "$kafka_container" ]]; then
    output="$(
      docker exec -i "$kafka_container" /opt/kafka/bin/kafka-consumer-groups.sh \
        --bootstrap-server "$KAFKA_CONTAINER_BOOTSTRAP" --describe --all-groups 2>&1 || true
    )"
    if [[ "$output" != *"No such file"* && "$output" != *"executable file not found"* && "$output" != *"Error:"* ]]; then
      printf '%s\n' "$output" | awk -v re="$group_regex" 'NR==1 || $1 ~ re {print}'
      return 0
    fi
    echo "kafka lag via container failed: ${output}" >&2
  fi

  if command -v kafka-consumer-groups.sh >/dev/null 2>&1; then
    kafka-consumer-groups.sh --bootstrap-server "$KAFKA_HOST_BOOTSTRAP" --describe --all-groups 2>/dev/null \
      | awk -v re="$group_regex" 'NR==1 || $1 ~ re {print}' \
      || echo "kafka lag unavailable: host kafka-consumer-groups.sh failed"
    return 0
  fi

  echo "kafka lag unavailable: kafka-consumer-groups.sh not found in container or host"
}

wait_run_terminal() {
  local label="$1"
  local elapsed=0
  while [[ "$elapsed" -lt "$WAIT_TERMINAL_TIMEOUT_SECONDS" ]]; do
    local counts total terminal
    counts="$(
      psql_platform -Atc "
        select count(*) || '|' ||
               count(*) filter (where instance_status in ('SUCCESS','FAILED','PARTIAL_FAILED','CANCELLED','TERMINATED'))
        from batch.job_instance
        where tenant_id = 'default-tenant'
          and params_snapshot::text like '%${RUN_ID}%';"
    )"
    total="${counts%%|*}"
    terminal="${counts##*|}"
    if [[ "$total" -ge "$WAIT_TERMINAL_MIN_INSTANCES" && "$terminal" -eq "$total" ]]; then
      echo "==> ${label}: terminal instances ${terminal}/${total}"
      return 0
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
  echo "==> ${label}: timed out waiting for terminal instances" >&2
  return 1
}

run_pipeline_completion() {
  local label="$1"
  local job_code="$2"
  local params_file="$3"
  local users="$4"
  local log_file="$LOG_DIR/${label}.log"

  echo "==> ${label}: job=${job_code}, users=${users}"
  (
    cd "$LOAD_DIR"
    mvn gatling:test \
      -Dsimulation=com.example.batch.loadtest.simulations.LaunchPipelineCompletionSimulation \
      -Dtrigger.baseUrl="$TRIGGER_BASE_URL" \
      -Dconsole.baseUrl="$CONSOLE_BASE_URL" \
      -Dorchestrator.baseUrl="$ORCHESTRATOR_BASE_URL" \
      -Dinternal.secret="$INTERNAL_SECRET" \
      -DtenantId=default-tenant \
      -DjobCode="$job_code" \
      -DbizDate="$BIZ_DATE" \
      -Dlaunch.paramsJsonFile="$params_file" \
      -Dpipeline.completion.users="$users" \
      -Dramp.seconds="$RAMP_SECONDS" \
      -Dpipeline.maxPolls="$PIPELINE_MAX_POLLS" \
      -Dpipeline.pollIntervalSec="$PIPELINE_POLL_INTERVAL_SEC" \
      -Dslo.maxErrorPct="$MAX_ERROR_PCT" \
      -Dconsole.accessToken="$TOKEN" \
      --batch-mode
  ) | tee "$log_file"

  local elapsed=0
  while [[ "$elapsed" -lt "$WAIT_TERMINAL_TIMEOUT_SECONDS" ]]; do
    local counts
    counts="$(
      psql_platform -Atc "
        select count(*) || '|' ||
               count(*) filter (where instance_status in ('SUCCESS','FAILED','PARTIAL_FAILED','CANCELLED','TERMINATED'))
        from batch.job_instance
        where tenant_id = 'default-tenant'
          and job_code = '${job_code}'
          and params_snapshot::text like '%${RUN_ID}%';"
    )"
    local total="${counts%%|*}"
    local terminal="${counts##*|}"
    if [[ "$total" -ge "$users" && "$terminal" -eq "$total" ]]; then
      echo "==> ${label}: terminal instances ${terminal}/${total}"
      return 0
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
  echo "==> ${label}: timed out waiting for terminal instances" >&2
  return 1
}

run_trigger_pressure() {
  local params_file="$1"
  local log_file="$LOG_DIR/trigger.log"
  local sample_file="$LOG_DIR/trigger-scheduler-backlog.csv"

  echo "==> trigger: job=${TRIGGER_JOB_CODE}, launch_rps=${TRIGGER_LAUNCH_RPS}, duration=${TRIGGER_DURATION_SECONDS}s"
  (
    cd "$LOAD_DIR"
    TENANT_ID=default-tenant \
    DURATION_SECONDS="$((TRIGGER_DURATION_SECONDS + 30))" \
    INTERVAL_SECONDS=5 \
    OUT="$sample_file" \
      bash scripts/sample-scheduler-backlog.sh
  ) &
  local sampler_pid=$!

  (
    cd "$LOAD_DIR"
    mvn gatling:test \
      -Dsimulation=com.example.batch.loadtest.simulations.SchedulingBacklogUnderLoadSimulation \
      -Dtrigger.baseUrl="$TRIGGER_BASE_URL" \
      -Dconsole.baseUrl="$CONSOLE_BASE_URL" \
      -Dorchestrator.baseUrl="$ORCHESTRATOR_BASE_URL" \
      -Dinternal.secret="$INTERNAL_SECRET" \
      -DtenantId=default-tenant \
      -DjobCode="$TRIGGER_JOB_CODE" \
      -DbizDate="$BIZ_DATE" \
      -Dlaunch.paramsJsonFile="$params_file" \
      -Dscheduling.launch.rps="$TRIGGER_LAUNCH_RPS" \
      -Dscheduling.read.rps="$TRIGGER_READ_RPS" \
      -Dscheduling.console.reads="$SCHEDULING_CONSOLE_READS" \
      -Dduration.seconds="$TRIGGER_DURATION_SECONDS" \
      -Dslo.write.p95ms=5000 \
      -Dslo.read.p99ms=5000 \
      -Dslo.maxErrorPct="$MAX_ERROR_PCT" \
      -Dconsole.accessToken="$TOKEN" \
      --batch-mode
  ) | tee "$log_file"

  wait "$sampler_pid" || true
}

run_mixed_pressure() {
  local log_file="$LOG_DIR/mixed.log"
  local sample_file="$LOG_DIR/trigger-scheduler-backlog.csv"

  echo "==> mixed: modules=${MODULES_CSV}, duration=${TRIGGER_DURATION_SECONDS}s"
  echo "==> mixed rates: process=${PROCESS_LAUNCH_RPS}/s dispatch=${DISPATCH_LAUNCH_RPS}/s atomic=${ATOMIC_LAUNCH_RPS}/s trigger=${TRIGGER_LAUNCH_RPS}/s scheduler_read=${TRIGGER_READ_RPS}/s"

  if csv_contains trigger "$MODULES_CSV"; then
    (
      cd "$LOAD_DIR"
      TENANT_ID=default-tenant \
      DURATION_SECONDS="$((TRIGGER_DURATION_SECONDS + 30))" \
      INTERVAL_SECONDS=5 \
      OUT="$sample_file" \
        bash scripts/sample-scheduler-backlog.sh
    ) &
    local sampler_pid=$!
  else
    local sampler_pid=""
  fi

  (
    cd "$LOAD_DIR"
    mvn gatling:test \
      -Dsimulation=com.example.batch.loadtest.simulations.ControlPlaneMixedPressureSimulation \
      -Dtrigger.baseUrl="$TRIGGER_BASE_URL" \
      -Dconsole.baseUrl="$CONSOLE_BASE_URL" \
      -Dorchestrator.baseUrl="$ORCHESTRATOR_BASE_URL" \
      -Dinternal.secret="$INTERNAL_SECRET" \
      -DtenantId=default-tenant \
      -DbizDate="$BIZ_DATE" \
      -Dcontrol.modules="$MODULES_CSV" \
      -Dcontrol.process.paramsJsonFile="$PROCESS_PARAMS" \
      -Dcontrol.dispatch.paramsJsonFile="$DISPATCH_PARAMS" \
      -Dcontrol.atomic.paramsJsonFile="$PARAM_DIR/atomic.params.json" \
      -Dcontrol.trigger.paramsJsonFile="$PARAM_DIR/trigger.params.json" \
      -Dcontrol.process.rps="$PROCESS_LAUNCH_RPS" \
      -Dcontrol.dispatch.rps="$DISPATCH_LAUNCH_RPS" \
      -Dcontrol.atomic.rps="$ATOMIC_LAUNCH_RPS" \
      -Dscheduling.launch.rps="$TRIGGER_LAUNCH_RPS" \
      -Dscheduling.read.rps="$TRIGGER_READ_RPS" \
      -Dduration.seconds="$TRIGGER_DURATION_SECONDS" \
      -Dslo.maxErrorPct="$MAX_ERROR_PCT" \
      --batch-mode
  ) | tee "$log_file"

  if [[ -n "$sampler_pid" ]]; then
    wait "$sampler_pid" || true
  fi

  wait_run_terminal mixed
}

write_params() {
  local file="$1"
  jq -n --arg runId "$RUN_ID" --arg module "$2" \
    '{metadata: {runId: $runId, benchmarkModule: $module}}' > "$file"
}

write_report() {
  {
    echo "# Control Plane Worker Benchmark - ${RUN_ID}"
    echo
    echo "- Time window UTC: ${RUN_STARTED_AT} - $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "- Mode: ${CONTROL_PLANE_MODE}"
    echo "- Modules: ${MODULES_CSV}"
    echo "- Users per pipeline module: ${USERS}, ramp seconds: ${RAMP_SECONDS}"
    echo "- Mixed launch rates: process=${PROCESS_LAUNCH_RPS}/s, dispatch=${DISPATCH_LAUNCH_RPS}/s, atomic=${ATOMIC_LAUNCH_RPS}/s"
    echo "- Trigger pressure: job=${TRIGGER_JOB_CODE}, launch_rps=${TRIGGER_LAUNCH_RPS}, read_rps=${TRIGGER_READ_RPS}, duration=${TRIGGER_DURATION_SECONDS}s"
    echo "- Trigger console reads: ${SCHEDULING_CONSOLE_READS}"
    echo "- Logs: ${LOG_DIR}"
    echo "- Auto cleanup: $([[ "$SKIP_AUTO_CLEANUP" == "1" ]] && echo disabled || echo enabled)"
    echo
    echo "## Instance Completion"
    echo
    echo '```text'
    psql_platform -P pager=off -F ' | ' -A -c "
      with ji_with_module as (
        select
          coalesce(
            params_snapshot #>> '{requestParams,metadata,benchmarkModule}',
            params_snapshot #>> '{effectiveParams,metadata,benchmarkModule}',
            case
              when job_code = 'lt_process_sql_job' then 'process'
              when job_code = 'lt_dispatch_local_job' then 'dispatch'
              else 'unknown'
            end
          ) as benchmark_module,
          *
        from batch.job_instance
        where tenant_id = 'default-tenant'
          and params_snapshot::text like '%${RUN_ID}%'
      )
      select
        benchmark_module,
        job_code,
        count(*) as total,
        count(*) filter (where instance_status = 'SUCCESS') as success,
        count(*) filter (where instance_status = 'FAILED') as failed,
        count(*) filter (where instance_status not in ('SUCCESS','FAILED','CANCELLED','TERMINATED','PARTIAL_FAILED')) as non_terminal,
        round(avg(extract(epoch from (finished_at - created_at))) filter (where finished_at is not null)::numeric, 3) as avg_seconds,
        round(percentile_cont(0.95) within group (order by extract(epoch from (finished_at - created_at))) filter (where finished_at is not null)::numeric, 3) as p95_seconds
      from ji_with_module
      group by benchmark_module, job_code
      order by benchmark_module, job_code;"
    echo '```'
    echo
    echo "## Task Latency"
    echo
    echo '```text'
    psql_platform -P pager=off -F ' | ' -A -c "
      with ji_with_module as (
        select
          coalesce(
            params_snapshot #>> '{requestParams,metadata,benchmarkModule}',
            params_snapshot #>> '{effectiveParams,metadata,benchmarkModule}',
            case
              when job_code = 'lt_process_sql_job' then 'process'
              when job_code = 'lt_dispatch_local_job' then 'dispatch'
              else 'unknown'
            end
          ) as benchmark_module,
          *
        from batch.job_instance
        where tenant_id = 'default-tenant'
          and params_snapshot::text like '%${RUN_ID}%'
      )
      select
        ji.benchmark_module,
        ji.job_code,
        jt.task_type,
        count(*) as tasks,
        count(*) filter (where jt.task_status = 'SUCCESS') as success,
        count(*) filter (where jt.task_status = 'FAILED') as failed,
        round(avg(extract(epoch from (jt.started_at - jt.created_at))) filter (where jt.started_at is not null)::numeric, 3) as avg_claim_delay_s,
        round(percentile_cont(0.95) within group (order by extract(epoch from (jt.started_at - jt.created_at))) filter (where jt.started_at is not null)::numeric, 3) as p95_claim_delay_s,
        round(avg(extract(epoch from (jt.finished_at - jt.started_at))) filter (where jt.finished_at is not null and jt.started_at is not null)::numeric, 3) as avg_exec_s,
        round(percentile_cont(0.95) within group (order by extract(epoch from (jt.finished_at - jt.started_at))) filter (where jt.finished_at is not null and jt.started_at is not null)::numeric, 3) as p95_exec_s
      from ji_with_module ji
      join batch.job_task jt on jt.job_instance_id = ji.id
      group by ji.benchmark_module, ji.job_code, jt.task_type
      order by ji.benchmark_module, ji.job_code, jt.task_type;"
    echo '```'
    echo
    echo "## Stage Duration"
    echo
    echo '```text'
    psql_platform -P pager=off -F ' | ' -A -c "
      with ji_with_module as (
        select
          coalesce(
            params_snapshot #>> '{requestParams,metadata,benchmarkModule}',
            params_snapshot #>> '{effectiveParams,metadata,benchmarkModule}',
            case
              when job_code = 'lt_process_sql_job' then 'process'
              when job_code = 'lt_dispatch_local_job' then 'dispatch'
              else 'unknown'
            end
          ) as benchmark_module,
          *
        from batch.job_instance
        where tenant_id = 'default-tenant'
          and params_snapshot::text like '%${RUN_ID}%'
      )
      select
        ji.benchmark_module,
        ji.job_code,
        psr.stage_code,
        count(*) as runs,
        round(avg(psr.duration_ms)::numeric, 1) as avg_ms,
        round(percentile_cont(0.95) within group (order by psr.duration_ms)::numeric, 1) as p95_ms,
        max(psr.duration_ms) as max_ms
      from ji_with_module ji
      join batch.pipeline_instance pi on pi.related_job_instance_id = ji.id
      join batch.pipeline_step_run psr on psr.pipeline_instance_id = pi.id
      group by ji.benchmark_module, ji.job_code, psr.stage_code
      order by ji.benchmark_module, ji.job_code, psr.stage_code;"
    echo '```'
    echo
    echo "## Worker Load"
    echo
    echo '```text'
    psql_platform -P pager=off -F ' | ' -A -c "
      select worker_group, worker_code, status, current_load, max_concurrent,
             round(extract(epoch from (clock_timestamp() - heartbeat_at))::numeric, 1) as heartbeat_age_s
      from batch.worker_registry
      where tenant_id = 'default-tenant'
        and worker_group in ('PROCESS','DISPATCH','ATOMIC')
      order by worker_group, worker_code;"
    echo '```'
    echo
    echo "## Process Staging"
    echo
    echo '```text'
    psql_business -P pager=off -F ' | ' -A -c "
      select
        'process_staging_rows' as metric,
        count(*)::text as value
      from batch.process_staging
      where batch_key like '%${RUN_ID}%'
      union all
      select 'process_staging_table_size',
             pg_size_pretty(pg_total_relation_size('batch.process_staging'));"
    echo '```'
    echo
    echo "## Dispatch Counters"
    echo
    echo '```text'
    psql_platform -P pager=off -F ' | ' -A -c "
      select dispatch_status, count(*) as count
      from batch.file_dispatch_record fdr
      join batch.file_record fr on fr.id = fdr.file_id
      where fr.tenant_id = 'default-tenant'
        and fr.metadata_json::text like '%${RUN_ID}%'
      group by dispatch_status
      order by dispatch_status;"
    echo '```'
    echo
    echo "## Kafka Lag Snapshot"
    echo
    echo '```text'
    cat "$LOG_DIR/kafka-lag-before.txt"
    echo
    echo "--- after ---"
    cat "$LOG_DIR/kafka-lag-after.txt"
    echo '```'
    echo
    if [[ -f "$LOG_DIR/trigger-scheduler-backlog.csv" ]]; then
      echo "## Trigger Backlog Samples"
      echo
      echo "- CSV: ${LOG_DIR}/trigger-scheduler-backlog.csv"
      echo
      echo '```text'
      tail -n 5 "$LOG_DIR/trigger-scheduler-backlog.csv"
      echo '```'
      echo
    fi
    echo "## Not Covered By Default"
    echo
    echo "- atomic shell: skipped because shell executor is disabled by default."
    echo "- atomic stored-proc/http: optional matrix only; requires local procedure / outbound endpoint readiness."
    echo "- dispatch remote SFTP/NAS/EMAIL/OSS failure injection: not part of this safe local default run."
    echo "- 10w task storm: use this script with higher USERS/TRIGGER_LAUNCH_RPS after baseline is stable."
  } > "$REPORT"
}

require_tooling
if [[ "$CLEANUP_ONLY" == "1" ]]; then
  trap - EXIT
  "$LOAD_DIR/scripts/cleanup-worker-load-data.sh"
  cleanup_atomic_trigger
  echo "Cleaned control-plane worker benchmark data for RUN_ID=${RUN_ID}"
  exit 0
fi

"$LOAD_DIR/scripts/prepare-worker-load-data.sh"
# shellcheck disable=SC1090
source "$LOAD_DIR/target/worker-load-data/run.env"

TOKEN="${CONSOLE_ACCESS_TOKEN:-load-test-token}"
if [[ "$PIPELINE_MAX_POLLS" != "0" || "$SCHEDULING_CONSOLE_READS" == "true" ]]; then
  TOKEN="$(login_token)"
  if [[ -z "$TOKEN" ]]; then
    echo "Failed to acquire console token" >&2
    exit 1
  fi
fi

REPORT="$LOAD_DIR/target/control-plane-worker-report-${RUN_ID}.md"
LOG_DIR="$LOAD_DIR/target/control-plane-worker-logs/${RUN_ID}"
mkdir -p "$LOG_DIR"

PARAM_DIR="$LOAD_DIR/target/control-plane-worker-data/${RUN_ID}"
mkdir -p "$PARAM_DIR"
write_params "$PARAM_DIR/atomic.params.json" "atomic"
write_params "$PARAM_DIR/trigger.params.json" "trigger"

RUN_STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
kafka_lag_snapshot > "$LOG_DIR/kafka-lag-before.txt"

case "$CONTROL_PLANE_MODE" in
  sequential)
    if csv_contains process "$MODULES_CSV"; then
      run_pipeline_completion process lt_process_sql_job "$PROCESS_PARAMS" "$USERS" || true
    fi

    if csv_contains dispatch "$MODULES_CSV"; then
      run_pipeline_completion dispatch lt_dispatch_local_job "$DISPATCH_PARAMS" "$USERS" || true
    fi

    if csv_contains atomic "$MODULES_CSV"; then
      IFS=',' read -r -a ATOMIC_JOBS <<< "$ATOMIC_JOBS_CSV"
      for job_code in "${ATOMIC_JOBS[@]}"; do
        job_code="$(echo "$job_code" | xargs)"
        [[ -n "$job_code" ]] || continue
        run_pipeline_completion "atomic-${job_code}" "$job_code" "$PARAM_DIR/atomic.params.json" "$USERS" || true
      done
    fi

    if csv_contains trigger "$MODULES_CSV"; then
      run_trigger_pressure "$PARAM_DIR/trigger.params.json" || true
    fi
    ;;
  parallel)
    run_mixed_pressure || true
    ;;
  *)
    echo "CONTROL_PLANE_MODE must be sequential or parallel" >&2
    exit 2
    ;;
esac

kafka_lag_snapshot > "$LOG_DIR/kafka-lag-after.txt"
write_report

echo "Control-plane worker benchmark report written: $REPORT"
