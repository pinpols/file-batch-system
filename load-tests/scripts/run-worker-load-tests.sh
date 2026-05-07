#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LOAD_DIR="$ROOT_DIR/load-tests"

TRIGGER_BASE_URL="${TRIGGER_BASE_URL:-http://localhost:18081}"
CONSOLE_BASE_URL="${CONSOLE_BASE_URL:-http://localhost:18080}"
ORCHESTRATOR_BASE_URL="${ORCHESTRATOR_BASE_URL:-http://localhost:18082}"
INTERNAL_SECRET="${INTERNAL_SECRET:-internal-secret}"
BIZ_DATE="${BIZ_DATE:-2026-05-05}"
IMPORT_PROFILE="${IMPORT_PROFILE:-medium}"
USERS_PER_WORKER="${USERS_PER_WORKER:-3}"
RAMP_SECONDS="${RAMP_SECONDS:-5}"
PIPELINE_MAX_POLLS="${PIPELINE_MAX_POLLS:-1}"
PIPELINE_POLL_INTERVAL_SEC="${PIPELINE_POLL_INTERVAL_SEC:-2}"
MAX_ERROR_PCT="${MAX_ERROR_PCT:-20.0}"
WAIT_TERMINAL_TIMEOUT_SECONDS="${WAIT_TERMINAL_TIMEOUT_SECONDS:-180}"

PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-15432}"
PGUSER="${PGUSER:-batch_user}"
PGPASSWORD="${PGPASSWORD:-batch_pass_123}"
PLATFORM_DB="${PLATFORM_DB:-batch_platform}"
BUSINESS_DB="${BUSINESS_DB:-batch_business}"
export PGPASSWORD

RUN_ID="${RUN_ID:-ltw-$(date +%Y%m%d%H%M%S)}"
export RUN_ID BIZ_DATE PGHOST PGPORT PGUSER PGPASSWORD PLATFORM_DB BUSINESS_DB

# 自动 cleanup（EXIT trap）：压测产物（job_instance / job_partition / job_task /
# dead_letter_task / outbox_event / event_outbox_retry / retry_schedule /
# trigger_request / trigger_outbox_event / file_record / biz seed）按 RUN_ID 全清。
# 历史 bug：以前没挂 trap，跑完 dead_letter_task 越攒越多（实测某次 555K 行
# 把 orchestrator 的 scheduler thread pool 占满，导致后续 STRICT smoke 全卡）。
# 设 SKIP_AUTO_CLEANUP=1 可临时禁用（排查现场用）。
SKIP_AUTO_CLEANUP="${SKIP_AUTO_CLEANUP:-0}"
on_exit_cleanup() {
  local rc=$?
  if [[ "$SKIP_AUTO_CLEANUP" == "1" ]]; then
    echo "SKIP_AUTO_CLEANUP=1, leaving RUN_ID=${RUN_ID} data in place for inspection"
    exit $rc
  fi
  echo "Auto-cleanup RUN_ID=${RUN_ID} ..." >&2
  RUN_ID="$RUN_ID" "$LOAD_DIR/scripts/cleanup-worker-load-data.sh" >&2 || \
    echo "WARN: cleanup failed for RUN_ID=${RUN_ID}, run manually: RUN_ID=${RUN_ID} bash $LOAD_DIR/scripts/cleanup-worker-load-data.sh" >&2
  exit $rc
}
trap on_exit_cleanup EXIT

"$LOAD_DIR/scripts/prepare-worker-load-data.sh"
# shellcheck disable=SC1090
source "$LOAD_DIR/target/worker-load-data/run.env"

case "$IMPORT_PROFILE" in
  small) IMPORT_PARAMS="$IMPORT_SMALL_PARAMS" ;;
  medium) IMPORT_PARAMS="$IMPORT_MEDIUM_PARAMS" ;;
  large) IMPORT_PARAMS="$IMPORT_LARGE_PARAMS" ;;
  *) echo "IMPORT_PROFILE must be small, medium, or large" >&2; exit 2 ;;
esac

TOKEN="$(
  curl -fsS -X POST "$CONSOLE_BASE_URL/api/console/auth/login" \
    -H 'Content-Type: application/json' \
    -d '{"username":"admin","password":"admin123"}' \
  | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p'
)"

if [[ -z "$TOKEN" ]]; then
  echo "Failed to acquire console token" >&2
  exit 1
fi

REPORT="$LOAD_DIR/target/worker-load-report-${RUN_ID}.md"
LOG_DIR="$LOAD_DIR/target/worker-load-logs/${RUN_ID}"
mkdir -p "$LOG_DIR"

RUN_STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

run_one() {
  local label="$1"
  local job_code="$2"
  local params_file="$3"
  local log_file="$LOG_DIR/${label}.log"

  echo "==> ${label}: job=${job_code}, params=${params_file}"
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
      -Dpipeline.completion.users="$USERS_PER_WORKER" \
      -Dramp.seconds="$RAMP_SECONDS" \
      -Dpipeline.maxPolls="$PIPELINE_MAX_POLLS" \
      -Dpipeline.pollIntervalSec="$PIPELINE_POLL_INTERVAL_SEC" \
      -Dslo.write.p95ms=5000 \
      -Dslo.read.p99ms=5000 \
      -Dslo.maxErrorPct="$MAX_ERROR_PCT" \
      -Dconsole.accessToken="$TOKEN" \
      --batch-mode
  ) | tee "$log_file"

  local elapsed=0
  while [[ "$elapsed" -lt "$WAIT_TERMINAL_TIMEOUT_SECONDS" ]]; do
    local counts
    counts="$(
      psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PLATFORM_DB" -Atc "
        select count(*) || '|' ||
               count(*) filter (where instance_status in ('SUCCESS','FAILED','PARTIAL_FAILED','CANCELLED','TERMINATED'))
        from batch.job_instance
        where tenant_id = 'default-tenant'
          and job_code = '${job_code}'
          and params_snapshot::text like '%${RUN_ID}%';"
    )"
    local total="${counts%%|*}"
    local terminal="${counts##*|}"
    if [[ "$total" -ge "$USERS_PER_WORKER" && "$terminal" -eq "$total" ]]; then
      echo "==> ${label}: terminal instances ${terminal}/${total}"
      return 0
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
  echo "==> ${label}: timed out waiting for terminal instances" >&2
  return 1
}

run_one import import_customer_job "$IMPORT_PARAMS"
run_one export export_settlement_job "$EXPORT_PARAMS"
run_one dispatch lt_dispatch_local_job "$DISPATCH_PARAMS"
run_one process lt_process_sql_job "$PROCESS_PARAMS"

RUN_FINISHED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

psql_platform() {
  psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PLATFORM_DB" -v ON_ERROR_STOP=1 "$@"
}

psql_business() {
  psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$BUSINESS_DB" -v ON_ERROR_STOP=1 "$@"
}

{
  echo "# Worker Load Test Report - ${RUN_ID}"
  echo
  echo "- Time window UTC: ${RUN_STARTED_AT} - ${RUN_FINISHED_AT}"
  echo "- Tenant: default-tenant"
  echo "- Users per worker: ${USERS_PER_WORKER}, ramp seconds: ${RAMP_SECONDS}"
  echo "- Import profile: ${IMPORT_PROFILE}"
  echo "- Data dir: ${OUT_DIR}"
  echo "- Gatling logs: ${LOG_DIR}"
  echo
  echo "## Instance Completion"
  echo
  echo '```text'
  psql_platform -P pager=off -F ' | ' -A -c "
    with scoped as (
      select job_code, instance_status, created_at, finished_at
      from batch.job_instance
      where tenant_id = 'default-tenant'
        and job_code in ('import_customer_job','export_settlement_job','lt_dispatch_local_job','lt_process_sql_job')
        and params_snapshot::text like '%${RUN_ID}%'
    ),
    agg as (
      select
        job_code,
        count(*) as total,
        count(*) filter (where instance_status = 'SUCCESS') as success,
        count(*) filter (where instance_status = 'FAILED') as failed,
        count(*) filter (where instance_status not in ('SUCCESS','FAILED','CANCELLED','TERMINATED','PARTIAL_FAILED')) as non_terminal,
        round(avg(extract(epoch from (finished_at - created_at))) filter (where finished_at is not null)::numeric, 3) as avg_seconds,
        round(percentile_cont(0.95) within group (order by extract(epoch from (finished_at - created_at))) filter (where finished_at is not null)::numeric, 3) as p95_seconds
      from scoped
      group by job_code
    )
    select * from agg order by job_code;"
  echo '```'
  echo
  echo "## Business Throughput Counters"
  echo
  echo '```text'
  psql_business -P pager=off -F ' | ' -A -c "
    select 'import_loaded_rows' as metric, count(*)::text as value
    from biz.customer_account
    where tenant_id = 'default-tenant' and customer_no like '${RUN_ID}-IMP-%'
    union all
    select 'export_source_rows', count(*)::text
    from biz.settlement_detail
    where tenant_id = 'default-tenant' and settlement_no like '${RUN_ID}-SET-%'
    union all
    select 'process_source_rows', count(*)::text
    from biz.process_order_event
    where tenant_id = 'default-tenant' and account_id like '${RUN_ID}-ACCT-%'
    union all
    select 'process_target_rows', count(*)::text
    from biz.process_account_summary
    where tenant_id = 'default-tenant' and account_id like 'LTACCT-%';"
  psql_platform -P pager=off -F ' | ' -A -c "
    select 'dispatch_records' as metric, count(*)::text as value
    from batch.file_dispatch_record fdr
    join batch.file_record fr on fr.id = fdr.file_id
    where fr.tenant_id = 'default-tenant' and fr.metadata_json::text like '%${RUN_ID}%'
    union all
    select 'dispatch_files_dispatched', count(*)::text
    from batch.file_record
    where tenant_id = 'default-tenant'
      and metadata_json::text like '%${RUN_ID}%'
      and file_status = 'DISPATCHED';"
  echo '```'
  echo
  echo "## Task Status"
  echo
  echo '```text'
  psql_platform -P pager=off -F ' | ' -A -c "
    select ji.job_code, jt.task_type, jt.task_status, count(*) as count
    from batch.job_instance ji
    join batch.job_task jt on jt.job_instance_id = ji.id
    where ji.tenant_id = 'default-tenant'
      and ji.params_snapshot::text like '%${RUN_ID}%'
      and ji.job_code in ('import_customer_job','export_settlement_job','lt_dispatch_local_job','lt_process_sql_job')
    group by ji.job_code, jt.task_type, jt.task_status
    order by ji.job_code, jt.task_type, jt.task_status;"
  echo '```'
} > "$REPORT"

echo "Worker load-test report written: $REPORT"
