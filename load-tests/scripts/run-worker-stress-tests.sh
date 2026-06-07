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
IMPORT_PROFILE="${IMPORT_PROFILE:-medium}"
STEPS_CSV="${STEPS_CSV:-1,2,4,8}"
RAMP_SECONDS="${RAMP_SECONDS:-5}"
PIPELINE_MAX_POLLS="${PIPELINE_MAX_POLLS:-1}"
PIPELINE_POLL_INTERVAL_SEC="${PIPELINE_POLL_INTERVAL_SEC:-2}"
WAIT_TERMINAL_TIMEOUT_SECONDS="${WAIT_TERMINAL_TIMEOUT_SECONDS:-240}"
MAX_ERROR_PCT="${MAX_ERROR_PCT:-20.0}"

PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-15432}"
PGUSER="${PGUSER:-batch_user}"
PGPASSWORD="${PGPASSWORD:-batch_pass_123}"
PLATFORM_DB="${PLATFORM_DB:-batch_platform}"
BUSINESS_DB="${BUSINESS_DB:-batch_business}"
export PGPASSWORD

RUN_ID="${RUN_ID:-ltw-stress-$(date +%Y%m%d%H%M%S)}"
export RUN_ID BIZ_DATE PGHOST PGPORT PGUSER PGPASSWORD PLATFORM_DB BUSINESS_DB

# 同 run-worker-load-tests.sh 的 EXIT trap：压测产物按 RUN_ID 全清，避免历史 dead_letter 累积。
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

LOGIN_RESPONSE="$(
  curl -i -fsS -X POST "$CONSOLE_BASE_URL/api/console/auth/login" \
    -H 'Content-Type: application/json' \
    -d '{"username":"admin","password":"admin123"}' \
)"
TOKEN="$(printf '%s\n' "$LOGIN_RESPONSE" | tr -d '\r' | sed -n 's/^Set-Cookie: batch_console_token=\([^;]*\).*/\1/p' | head -1)"
if [[ -z "$TOKEN" ]]; then
  TOKEN="$(printf '%s\n' "$LOGIN_RESPONSE" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p' | head -1)"
fi

if [[ -z "$TOKEN" ]]; then
  echo "Failed to acquire console token" >&2
  exit 1
fi

REPORT="$LOAD_DIR/target/worker-stress-report-${RUN_ID}.md"
LOG_DIR="$LOAD_DIR/target/worker-stress-logs/${RUN_ID}"
mkdir -p "$LOG_DIR"

psql_platform() {
  psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PLATFORM_DB" -v ON_ERROR_STOP=1 "$@"
}

run_one() {
  local label="$1"
  local job_code="$2"
  local params_file="$3"
  local users="$4"
  local log_file="$LOG_DIR/${label}-u${users}.log"

  echo "==> stress ${label}: users=${users}, job=${job_code}"
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
      psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PLATFORM_DB" -Atc "
        select count(*) || '|' ||
               count(*) filter (where instance_status in ('SUCCESS','FAILED','PARTIAL_FAILED','CANCELLED','TERMINATED'))
        from batch.job_instance
        where tenant_id = 'default-tenant'
          and job_code = '${job_code}'
          and params_snapshot::text like '%${RUN_ID}%'
          and params_snapshot::text like '%\"stressUsers\": ${users}%';"
    )"
    local total="${counts%%|*}"
    local terminal="${counts##*|}"
    if [[ "$total" -ge "$users" && "$terminal" -eq "$total" ]]; then
      echo "==> stress ${label}: terminal ${terminal}/${total}"
      return 0
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
  echo "==> stress ${label}: timeout waiting for terminal instances" >&2
  return 1
}

write_step_params() {
  local src="$1"
  local dst="$2"
  local users="$3"
  jq --argjson users "$users" '.metadata = ((.metadata // {}) + {stressUsers: $users})' "$src" > "$dst"
}

{
  echo "# Worker Stress Test Report - ${RUN_ID}"
  echo
  echo "- Time UTC start: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "- Steps: ${STEPS_CSV}"
  echo "- Import profile: ${IMPORT_PROFILE}"
  echo "- Logs: ${LOG_DIR}"
  echo
} > "$REPORT"

IFS=',' read -r -a STEPS <<< "$STEPS_CSV"
for users in "${STEPS[@]}"; do
  users="$(echo "$users" | xargs)"
  STEP_DIR="$LOAD_DIR/target/worker-load-data/step-u${users}"
  mkdir -p "$STEP_DIR"
  write_step_params "$IMPORT_PARAMS" "$STEP_DIR/import.params.json" "$users"
  write_step_params "$EXPORT_PARAMS" "$STEP_DIR/export.params.json" "$users"
  write_step_params "$DISPATCH_PARAMS" "$STEP_DIR/dispatch.params.json" "$users"
  write_step_params "$PROCESS_PARAMS" "$STEP_DIR/process.params.json" "$users"

  run_one import import_customer_job "$STEP_DIR/import.params.json" "$users" || true
  run_one export export_settlement_job "$STEP_DIR/export.params.json" "$users" || true
  run_one dispatch lt_dispatch_local_job "$STEP_DIR/dispatch.params.json" "$users" || true
  run_one process lt_process_sql_job "$STEP_DIR/process.params.json" "$users" || true

  {
    echo "## Step users=${users}"
    echo
    echo '```text'
    psql_platform -P pager=off -F ' | ' -A -c "
      select
        ji.job_code,
        count(*) as total,
        count(*) filter (where ji.instance_status = 'SUCCESS') as success,
        count(*) filter (where ji.instance_status <> 'SUCCESS') as not_success,
        round(avg(extract(epoch from (ji.finished_at - ji.created_at))) filter (where ji.finished_at is not null)::numeric, 3) as avg_seconds,
        round(percentile_cont(0.95) within group (order by extract(epoch from (ji.finished_at - ji.created_at))) filter (where ji.finished_at is not null)::numeric, 3) as p95_seconds
      from batch.job_instance ji
      where ji.tenant_id = 'default-tenant'
        and ji.job_code in ('import_customer_job','export_settlement_job','lt_dispatch_local_job','lt_process_sql_job')
        and ji.params_snapshot::text like '%${RUN_ID}%'
        and ji.params_snapshot::text like '%\"stressUsers\": ${users}%'
      group by ji.job_code
      order by ji.job_code;"
    echo '```'
    echo
    echo '```text'
    psql_platform -P pager=off -F ' | ' -A -c "
      select ji.job_code, jt.task_type, jt.task_status, coalesce(jt.error_code,'') as error_code, count(*) as count
      from batch.job_instance ji
      join batch.job_task jt on jt.job_instance_id = ji.id
      where ji.tenant_id = 'default-tenant'
        and ji.params_snapshot::text like '%${RUN_ID}%'
        and ji.params_snapshot::text like '%\"stressUsers\": ${users}%'
      group by ji.job_code, jt.task_type, jt.task_status, jt.error_code
      order by ji.job_code, jt.task_type, jt.task_status, jt.error_code;"
    echo '```'
    echo
  } >> "$REPORT"
done

{
  echo "- Time UTC finish: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
} >> "$REPORT"

echo "Worker stress-test report written: $REPORT"
