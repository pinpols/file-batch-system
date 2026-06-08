#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LOAD_DIR="$ROOT_DIR/load-tests"
# shellcheck source=env.sh
source "$LOAD_DIR/scripts/env.sh"

RUN_ID="${RUN_ID:-p2-capacity-$(date +%Y%m%d%H%M%S)}"
RUN_10W_STORM="${RUN_10W_STORM:-1}"
RUN_FAIRNESS="${RUN_FAIRNESS:-1}"
STORM_TOTAL_REQUESTS="${STORM_TOTAL_REQUESTS:-100000}"
STORM_RPS="${STORM_RPS:-200}"
STORM_WAIT_SECONDS="${STORM_WAIT_SECONDS:-2400}"
FAIRNESS_TOTAL_REQUESTS="${FAIRNESS_TOTAL_REQUESTS:-6000}"
FAIRNESS_CONCURRENCY="${FAIRNESS_CONCURRENCY:-96}"
FAIRNESS_WEIGHTS="${FAIRNESS_WEIGHTS:-ta:3,tb:1,tc:1}"
FAIRNESS_WAIT_SECONDS="${FAIRNESS_WAIT_SECONDS:-1200}"
FAIRNESS_MODE="${FAIRNESS_MODE:-orchestrator}"
SKIP_AUTO_CLEANUP="${SKIP_AUTO_CLEANUP:-0}"
export RUN_ID BIZ_DATE PGHOST PGPORT PGUSER PGPASSWORD PLATFORM_DB BUSINESS_DB

REPORT="$LOAD_DIR/target/p2-capacity-profile-${RUN_ID}.md"
LOG_DIR="$LOAD_DIR/target/p2-capacity-profile-logs/${RUN_ID}"
mkdir -p "$LOG_DIR"

psql_platform() {
  psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PLATFORM_DB" -v ON_ERROR_STOP=1 "$@"
}

cleanup() {
  local rc=$?
  if [[ "$SKIP_AUTO_CLEANUP" == "1" ]]; then
    echo "SKIP_AUTO_CLEANUP=1, leaving RUN_ID=${RUN_ID} data in place"
    exit "$rc"
  fi
  if [[ "$RUN_10W_STORM" == "1" ]]; then
    RUN_ID="${RUN_ID}-10w" "$LOAD_DIR/scripts/cleanup-worker-load-data.sh" >&2 || true
  fi
  psql_platform -v run_id="$RUN_ID" -f "$LOAD_DIR/sql/cleanup-control-plane-worker.sql" >&2 || true
  exit "$rc"
}
trap cleanup EXIT

require_tooling() {
  command -v jq >/dev/null || { echo "jq is required" >&2; exit 2; }
  command -v psql >/dev/null || { echo "psql is required" >&2; exit 2; }
  command -v python3 >/dev/null || { echo "python3 is required" >&2; exit 2; }
}

write_report_header() {
  {
    echo "# P2 Worker Capacity Profile - ${RUN_ID}"
    echo
    echo "- Time UTC start: ${RUN_STARTED_AT}"
    echo "- Logs: ${LOG_DIR}"
    echo "- Auto cleanup: $([[ "$SKIP_AUTO_CLEANUP" == "1" ]] && echo disabled || echo enabled)"
    echo
  } > "$REPORT"
}

append_sql_summary() {
  local title="$1"
  local run_id="$2"
  {
    echo "## ${title}"
    echo
    echo '```text'
    psql_platform -P pager=off -F ' | ' -A \
      -v run_id="$run_id" \
      -f "$LOAD_DIR/sql/p2-run-instance-summary.sql"
    echo '```'
    echo
    echo '```text'
    psql_platform -P pager=off -F ' | ' -A \
      -v run_id="$run_id" \
      -f "$LOAD_DIR/sql/p2-run-task-summary.sql"
    echo '```'
    echo
  } >> "$REPORT"
}

run_10w_storm() {
  local storm_run_id="${RUN_ID}-10w"
  local duration
  duration="$(python3 - <<PY
import math
print(math.ceil(${STORM_TOTAL_REQUESTS} / ${STORM_RPS}))
PY
)"
  echo "==> 10w storm run_id=${storm_run_id}, total=${STORM_TOTAL_REQUESTS}, rps=${STORM_RPS}, duration=${duration}s"
  set +e
  RUN_ID="$storm_run_id" \
  MODULES_CSV=atomic \
  CONTROL_PLANE_MODE=parallel \
  ATOMIC_LAUNCH_RPS="$STORM_RPS" \
  TRIGGER_DURATION_SECONDS="$duration" \
  WAIT_TERMINAL_TIMEOUT_SECONDS="$STORM_WAIT_SECONDS" \
  SKIP_AUTO_CLEANUP=1 \
    "$LOAD_DIR/scripts/run-control-plane-worker-benchmark.sh" \
    | tee "$LOG_DIR/10w-storm.log"
  local rc=${PIPESTATUS[0]}
  set -e
  echo "10w storm exit_code=${rc}" | tee "$LOG_DIR/10w-storm.exit"
  append_sql_summary "10w Atomic Storm" "$storm_run_id"
}

run_fairness() {
  echo "==> prepare ta/tb/tc atomic job definitions"
  psql_platform -f "$LOAD_DIR/sql/prepare-p2-multitenant-atomic.sql"
  echo "==> multi-tenant fairness run_id=${RUN_ID}, total=${FAIRNESS_TOTAL_REQUESTS}, weights=${FAIRNESS_WEIGHTS}"
  RUN_ID="$RUN_ID" \
  FAIRNESS_TOTAL_REQUESTS="$FAIRNESS_TOTAL_REQUESTS" \
  FAIRNESS_CONCURRENCY="$FAIRNESS_CONCURRENCY" \
  FAIRNESS_WEIGHTS="$FAIRNESS_WEIGHTS" \
  FAIRNESS_WAIT_SECONDS="$FAIRNESS_WAIT_SECONDS" \
  FAIRNESS_MODE="$FAIRNESS_MODE" \
  TRIGGER_BASE_URL="$TRIGGER_BASE_URL" \
  ORCHESTRATOR_BASE_URL="$ORCHESTRATOR_BASE_URL" \
  INTERNAL_SECRET="$INTERNAL_SECRET" \
  BIZ_DATE="$BIZ_DATE" \
  PGHOST="$PGHOST" PGPORT="$PGPORT" PGUSER="$PGUSER" PGPASSWORD="$PGPASSWORD" PLATFORM_DB="$PLATFORM_DB" \
    python3 "$LOAD_DIR/scripts/p2_multitenant_fairness.py" \
    | tee "$LOG_DIR/multitenant-fairness.log"
  append_sql_summary "Multi-Tenant Fairness" "$RUN_ID"
}

require_tooling
RUN_STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
write_report_header

if [[ "$RUN_10W_STORM" == "1" ]]; then
  run_10w_storm
fi

if [[ "$RUN_FAIRNESS" == "1" ]]; then
  run_fairness
fi

{
  echo "## Finish"
  echo
  echo "- Time UTC finish: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
} >> "$REPORT"

echo "P2 capacity profile report written: $REPORT"
