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
# 高并发公平画像使用的共享组硬上限。默认 96 与 6000/1200s 的画像预算匹配；
# 12 槽位是用于小规模原子 admission 验证的专用 profile，不应混入吞吐画像。
FAIRNESS_GROUP_SHARED_MAX_RUNNING_JOBS="${FAIRNESS_GROUP_SHARED_MAX_RUNNING_JOBS:-96}"
# The fixture applies the fixed admission policy ta:tb:tc = 3:1:1. Submit an
# equal tenant demand by default, otherwise the result cannot distinguish the
# configured share from a tenant simply sending more work.
FAIRNESS_LAUNCH_WEIGHTS="${FAIRNESS_LAUNCH_WEIGHTS:-p2fa:1,p2fb:1,p2fc:1}"
FAIRNESS_WAIT_SECONDS="${FAIRNESS_WAIT_SECONDS:-1200}"
# Default to the real trigger ingress so every request creates its own
# trigger_request before the asynchronous orchestrator launch. Set
# FAIRNESS_MODE=orchestrator only when using pre-seeded trigger requests.
FAIRNESS_MODE="${FAIRNESS_MODE:-trigger}"
# The 10w profile is an upper-bound/capacity measurement and may legitimately
# exceed the low-latency assertion. Keep report-only behavior by default, while
# allowing CI or a release checklist to turn the measured failure into a hard
# failure without changing the profile itself.
CAPACITY_STRICT="${CAPACITY_STRICT:-0}"
SKIP_AUTO_CLEANUP="${SKIP_AUTO_CLEANUP:-0}"
export RUN_ID BIZ_DATE PGHOST PGPORT PGUSER PGPASSWORD PLATFORM_DB BUSINESS_DB

REPORT="$LOAD_DIR/target/p2-capacity-profile-${RUN_ID}.md"
LOG_DIR="$LOAD_DIR/target/p2-capacity-profile-logs/${RUN_ID}"
FAIRNESS_LOCK_DIR="$LOAD_DIR/target/.p2-multitenant-fairness.lock"
FAIRNESS_LOCK_HELD=0
mkdir -p "$LOG_DIR"
PROFILE_RC=0

psql_platform() {
  psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PLATFORM_DB" -v ON_ERROR_STOP=1 "$@"
}

cleanup() {
  local rc=$?
  if [[ "$SKIP_AUTO_CLEANUP" == "1" ]]; then
    # 保留 fixture 只用于人工取证，不能遗留本地执行锁，否则后续 profile 会被永久误判为并发运行。
    release_fairness_lock
    echo "SKIP_AUTO_CLEANUP=1, leaving RUN_ID=${RUN_ID} data in place"
    exit "$rc"
  fi
  if [[ "$RUN_10W_STORM" == "1" ]]; then
    if ! RUN_ID="${RUN_ID}-10w" "$LOAD_DIR/scripts/cleanup-worker-load-data.sh" >&2; then
      rc=1
    fi
  fi
  if ! psql_platform -v run_id="$RUN_ID" -f "$LOAD_DIR/sql/cleanup-control-plane-worker.sql" >&2; then
    rc=1
  fi
  if [[ "$RUN_FAIRNESS" == "1" && "$FAIRNESS_LOCK_HELD" == "1" ]]; then
    if ! psql_platform -f "$LOAD_DIR/sql/cleanup-p2-multitenant-fairness-policy.sql" >&2; then
      rc=1
    fi
    if ! evict_fairness_config_cache >&2; then
      rc=1
    fi
    release_fairness_lock
  fi
  local remaining
  remaining="$(psql_platform -tA -v run_id="$RUN_ID" \
    -f "$LOAD_DIR/sql/p2-run-instance-residue-count.sql" 2>/dev/null || echo unknown)"
  remaining="${remaining//[[:space:]]/}"
  if [[ "$remaining" != "0" ]]; then
    echo "capacity cleanup left ${remaining} job_instance rows for RUN_ID=${RUN_ID}" >&2
    rc=1
  fi
  exit "$rc"
}
trap cleanup EXIT

acquire_fairness_lock() {
  # p2fa/p2fb/p2fc 是共享的短生命周期 fixture；并发 profile 的 cleanup 会误删另一轮数据。
  # 该锁只约束同一工作区的本地执行，CI 应为每个 job 使用独立 worktree / database。
  if ! mkdir "$FAIRNESS_LOCK_DIR" 2>/dev/null; then
    echo "another P2 multi-tenant fairness profile is already running: $FAIRNESS_LOCK_DIR" >&2
    return 1
  fi
  FAIRNESS_LOCK_HELD=1
}

release_fairness_lock() {
  if [[ "$FAIRNESS_LOCK_HELD" == "1" ]]; then
    rmdir "$FAIRNESS_LOCK_DIR" 2>/dev/null || true
    FAIRNESS_LOCK_HELD=0
  fi
}

require_tooling() {
  command -v curl >/dev/null || { echo "curl is required" >&2; exit 2; }
  command -v jq >/dev/null || { echo "jq is required" >&2; exit 2; }
  command -v psql >/dev/null || { echo "psql is required" >&2; exit 2; }
  batch_require_python
}

evict_fairness_config_cache() {
  local tenant_id
  for tenant_id in p2fa p2fb p2fc; do
    curl --fail --silent --show-error --request POST \
      "${CONSOLE_BASE_URL}/api/console/ops/cache/evict-job-definition?tenantId=${tenant_id}&jobCode=atomic_sql_demo" \
      >/dev/null
    curl --fail --silent --show-error --request POST \
      "${CONSOLE_BASE_URL}/api/console/ops/cache/evict-quota-policies?tenantId=${tenant_id}" \
      >/dev/null
  done
}

write_report_header() {
  {
    echo "# P2 Worker Capacity Profile - ${RUN_ID}"
    echo
    echo "- Time UTC start: ${RUN_STARTED_AT}"
    echo "- Logs: ${LOG_DIR}"
    echo "- Auto cleanup: $([[ "$SKIP_AUTO_CLEANUP" == "1" ]] && echo disabled || echo enabled)"
    echo "- Strict capacity exit: $CAPACITY_STRICT"
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
  duration="$("$PYTHON_BIN" - <<PY
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
  if [[ "$rc" -ne 0 && "$CAPACITY_STRICT" == "1" ]]; then
    PROFILE_RC=1
  fi
  append_sql_summary "10w Atomic Storm" "$storm_run_id"
}

run_fairness() {
  if ! [[ "$FAIRNESS_GROUP_SHARED_MAX_RUNNING_JOBS" =~ ^[1-9][0-9]*$ ]]; then
    echo "FAIRNESS_GROUP_SHARED_MAX_RUNNING_JOBS must be a positive integer" >&2
    PROFILE_RC=1
    return
  fi
  if ! acquire_fairness_lock; then
    PROFILE_RC=1
    return
  fi
  echo "==> prepare isolated p2fa/p2fb/p2fc atomic job definitions"
  psql_platform -v fairness_group_cap="$FAIRNESS_GROUP_SHARED_MAX_RUNNING_JOBS" \
    -f "$LOAD_DIR/sql/prepare-p2-multitenant-atomic.sql"
  echo "==> evict p2fa/p2fb/p2fc job-definition and quota-policy caches after direct SQL setup"
  evict_fairness_config_cache
  echo "==> multi-tenant fairness run_id=${RUN_ID}, total=${FAIRNESS_TOTAL_REQUESTS}, group_cap=${FAIRNESS_GROUP_SHARED_MAX_RUNNING_JOBS}, policy_weights=p2fa:3,p2fb:1,p2fc:1, launch_weights=${FAIRNESS_LAUNCH_WEIGHTS}"
  set +e
  RUN_ID="$RUN_ID" \
  FAIRNESS_TOTAL_REQUESTS="$FAIRNESS_TOTAL_REQUESTS" \
  FAIRNESS_CONCURRENCY="$FAIRNESS_CONCURRENCY" \
  FAIRNESS_LAUNCH_WEIGHTS="$FAIRNESS_LAUNCH_WEIGHTS" \
  FAIRNESS_WAIT_SECONDS="$FAIRNESS_WAIT_SECONDS" \
  FAIRNESS_MODE="$FAIRNESS_MODE" \
  TRIGGER_BASE_URL="$TRIGGER_BASE_URL" \
  ORCHESTRATOR_BASE_URL="$ORCHESTRATOR_BASE_URL" \
  INTERNAL_SECRET="$INTERNAL_SECRET" \
  BIZ_DATE="$BIZ_DATE" \
  PGHOST="$PGHOST" PGPORT="$PGPORT" PGUSER="$PGUSER" PGPASSWORD="$PGPASSWORD" PLATFORM_DB="$PLATFORM_DB" \
    "$PYTHON_BIN" "$LOAD_DIR/scripts/p2_multitenant_fairness.py" \
    | tee "$LOG_DIR/multitenant-fairness.log"
  local rc=${PIPESTATUS[0]}
  set -e
  if [[ "$rc" -ne 0 ]]; then
    PROFILE_RC=1
  fi
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
exit "$PROFILE_RC"
