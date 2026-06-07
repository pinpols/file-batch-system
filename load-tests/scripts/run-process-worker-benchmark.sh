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

PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-15432}"
PGUSER="${PGUSER:-batch_user}"
PGPASSWORD="${PGPASSWORD:-batch_pass_123}"
PLATFORM_DB="${PLATFORM_DB:-batch_platform}"
BUSINESS_DB="${BUSINESS_DB:-batch_business}"
export PGPASSWORD

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
    psql_business -P pager=off -F ' | ' -A -c "
      select datname, xact_commit, xact_rollback, blks_read, blks_hit, tup_returned,
             tup_fetched, tup_inserted, tup_updated, tup_deleted, temp_bytes
      from pg_stat_database
      where datname = current_database();"
    echo
    echo "## pg_stat_wal"
    psql_business -P pager=off -F ' | ' -A -c "
      select wal_records, wal_fpi, pg_size_pretty(wal_bytes) as wal_bytes
      from pg_stat_wal;" 2>/dev/null || echo "pg_stat_wal unavailable"
    echo
    echo "## relation_sizes"
    psql_business -P pager=off -F ' | ' -A -c "
      select rel,
             pg_size_pretty(pg_total_relation_size(rel::regclass)) as total_size,
             pg_size_pretty(pg_relation_size(rel::regclass)) as heap_size
      from (values
        ('biz.process_order_event'),
        ('batch.process_staging'),
        ('biz.process_account_summary'),
        ('biz.process_event_copy')
      ) v(rel);"
    echo
    echo "## table_stats"
    psql_business -P pager=off -F ' | ' -A -c "
      select schemaname, relname, n_live_tup, n_dead_tup, seq_scan, seq_tup_read,
             idx_scan, n_tup_ins, n_tup_upd, n_tup_del, vacuum_count, autovacuum_count
      from pg_stat_user_tables
      where (schemaname, relname) in (
        ('biz','process_order_event'),
        ('batch','process_staging'),
        ('biz','process_account_summary'),
        ('biz','process_event_copy')
      )
      order by schemaname, relname;"
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
      psql_platform -Atc "
        select count(*) || '|' ||
               count(*) filter (where instance_status in ('SUCCESS','FAILED','PARTIAL_FAILED','CANCELLED','TERMINATED'))
        from batch.job_instance
        where tenant_id = 'default-tenant'
          and job_code = '${job_code}'
          and params_snapshot::text like '%${RUN_ID}%';"
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
    psql_platform -Atc "
      select count(*)
      from batch.job_instance
      where tenant_id = 'default-tenant'
        and job_code = '${job_code}'
        and params_snapshot::text like '%${RUN_ID}%';"
  )"
  expected_total=$((baseline_total + users))

  echo "==> ${label}: job=${job_code}, users=${users}, params=${params_file}"
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
    psql_platform -P pager=off -F ' | ' -A -c "
      select job_code,
             count(*) as total,
             count(*) filter (where instance_status = 'SUCCESS') as success,
             count(*) filter (where instance_status = 'FAILED') as failed,
             count(*) filter (where instance_status not in ('SUCCESS','FAILED','CANCELLED','TERMINATED','PARTIAL_FAILED')) as non_terminal,
             round(avg(extract(epoch from (finished_at - created_at))) filter (where finished_at is not null)::numeric, 3) as avg_seconds,
             round(percentile_cont(0.95) within group (order by extract(epoch from (finished_at - created_at))) filter (where finished_at is not null)::numeric, 3) as p95_seconds
      from batch.job_instance
      where tenant_id = 'default-tenant'
        and job_code in ('lt_process_sql_job','lt_process_copy_job')
        and params_snapshot::text like '%${RUN_ID}%'
      group by job_code
      order by job_code;"
    echo '```'
    echo
    echo "## Stage Duration"
    echo
    echo '```text'
    psql_platform -P pager=off -F ' | ' -A -c "
      select ji.job_code,
             psr.stage_code,
             count(*) as runs,
             round(avg(psr.duration_ms)::numeric, 1) as avg_ms,
             round(percentile_cont(0.95) within group (order by psr.duration_ms)::numeric, 1) as p95_ms,
             max(psr.duration_ms) as max_ms
      from batch.job_instance ji
      join batch.pipeline_instance pi on pi.related_job_instance_id = ji.id
      join batch.pipeline_step_run psr on psr.pipeline_instance_id = pi.id
      where ji.tenant_id = 'default-tenant'
        and ji.job_code in ('lt_process_sql_job','lt_process_copy_job')
        and ji.params_snapshot::text like '%${RUN_ID}%'
      group by ji.job_code, psr.stage_code
      order by ji.job_code, psr.stage_code;"
    echo '```'
    echo
    echo "## Rows And Throughput"
    echo
    echo '```text'
    psql_business -P pager=off -F ' | ' -A -c "
      select 'source_rows' as metric, count(*)::text as value
      from biz.process_order_event
      where tenant_id = 'default-tenant' and account_id like '${RUN_ID}-ACCT-%'
      union all
      select 'source_distinct_accounts', count(distinct account_id)::text
      from biz.process_order_event
      where tenant_id = 'default-tenant' and account_id like '${RUN_ID}-ACCT-%'
      union all
      select 'aggregate_target_rows', count(*)::text
      from biz.process_account_summary
      where tenant_id = 'default-tenant' and account_id like '${RUN_ID}-ACCT-%'
      union all
      select 'copy_target_rows', count(*)::text
      from biz.process_event_copy
      where tenant_id = 'default-tenant' and account_id like '${RUN_ID}-ACCT-%'
      union all
      select 'staging_live_rows', count(*)::text
      from batch.process_staging
      where tenant_id = 'default-tenant' and batch_key like '%${RUN_ID}%';"
    echo
    psql_platform -P pager=off -F ' | ' -A -c "
      with stage as (
        select ji.job_code,
               psr.stage_code,
               avg(psr.duration_ms) / 1000.0 as avg_seconds
        from batch.job_instance ji
        join batch.pipeline_instance pi on pi.related_job_instance_id = ji.id
        join batch.pipeline_step_run psr on psr.pipeline_instance_id = pi.id
        where ji.tenant_id = 'default-tenant'
          and ji.job_code in ('lt_process_sql_job','lt_process_copy_job')
          and ji.params_snapshot::text like '%${RUN_ID}%'
          and psr.stage_code in ('COMPUTE','COMMIT')
        group by ji.job_code, psr.stage_code
      )
      select job_code,
             stage_code,
             round(avg_seconds::numeric, 3) as avg_seconds,
             case
               when job_code = 'lt_process_sql_job' and stage_code = 'COMMIT'
                 then round((${PROCESS_ACCOUNT_COUNT} / nullif(avg_seconds, 0))::numeric, 1)
               else round((${PROCESS_SOURCE_ROWS} / nullif(avg_seconds, 0))::numeric, 1)
             end as estimated_rows_per_second
      from stage
      order by job_code, stage_code;"
    echo '```'
    echo
    echo "## Task Latency"
    echo
    echo '```text'
    psql_platform -P pager=off -F ' | ' -A -c "
      select ji.job_code,
             jt.task_status,
             count(*) as tasks,
             round(avg(extract(epoch from (jt.started_at - jt.created_at))) filter (where jt.started_at is not null)::numeric, 3) as avg_claim_delay_s,
             round(percentile_cont(0.95) within group (order by extract(epoch from (jt.started_at - jt.created_at))) filter (where jt.started_at is not null)::numeric, 3) as p95_claim_delay_s,
             round(avg(extract(epoch from (jt.finished_at - jt.started_at))) filter (where jt.finished_at is not null and jt.started_at is not null)::numeric, 3) as avg_exec_s,
             round(percentile_cont(0.95) within group (order by extract(epoch from (jt.finished_at - jt.started_at))) filter (where jt.finished_at is not null and jt.started_at is not null)::numeric, 3) as p95_exec_s
      from batch.job_instance ji
      join batch.job_task jt on jt.job_instance_id = ji.id
      where ji.tenant_id = 'default-tenant'
        and ji.job_code in ('lt_process_sql_job','lt_process_copy_job')
        and ji.params_snapshot::text like '%${RUN_ID}%'
      group by ji.job_code, jt.task_status
      order by ji.job_code, jt.task_status;"
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
