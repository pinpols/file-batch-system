#!/usr/bin/env bash
# =========================================================
# inspect-workers.sh - Worker 注册表巡检
# 说明：
# 1) 检查 DRAINING 超时、心跳失联和孤儿任务。
# 2) 汇总各状态 worker 分布。
# =========================================================
#
# 使用方法：
#   PGHOST=localhost PGPORT=15432 PGDATABASE=batch_platform PGUSER=batch_user \
#     PGPASSWORD=secret bash scripts/ops/inspect-workers.sh
#
# 如发现 DRAINING 超时 worker，可运行 heal-drain-timeout.sh 自动处理。

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=env.sh
source "$ROOT/scripts/ops/env.sh"

# ── 配置 ─────────────────────────────────────────────────────────────
STALE_HEARTBEAT_MINUTES="${BATCH_INSPECT_STALE_HEARTBEAT_MINUTES:-5}"

# ── 辅助函数 ───────────────────────────────────────────────────────────────────
failures=0
warnings=0

log()  { printf '%s\n' "$*"; }
ok()   { log "OK:   $*"; }
warn() { log "WARN: $*"; warnings=$((warnings + 1)); }
fail() { log "FAIL: $*"; failures=$((failures + 1)); }

psql_file() {
  psql -h "${PGHOST}" -p "${PGPORT}" -U "${PGUSER}" -d "${PGDATABASE}" \
       -tA -v ON_ERROR_STOP=1 -v schema="$BATCH_SCHEMA" \
       -v stale_heartbeat_minutes="$STALE_HEARTBEAT_MINUTES" "$@"
}

require_psql() {
  if ! command -v psql >/dev/null 2>&1; then
    fail "psql not found — install postgresql-client"
    exit 1
  fi
}

check_connectivity() {
  local result
  if ! result="$(psql_file -f "$OPS_SQL_DIR/common-connectivity.sql" 2>&1)"; then
    fail "DB connection failed: ${result}"
    exit 1
  fi
}

# ── 1. worker 状态汇总 ──────────────────────────────────────────────────
check_worker_summary() {
  log "Worker registry summary:"
  psql_file -f "$OPS_SQL_DIR/inspect-workers-summary.sql" 2>/dev/null \
  | while IFS='|' read -r status cnt; do
      log "  ${status:-?}: ${cnt:-0}"
    done
}

# ── 2. 超过 deadline 的 DRAINING worker ────────────────────────────────────────
check_drain_timeout() {
  local overdue
  overdue="$(psql_file -f "$OPS_SQL_DIR/inspect-workers-drain-timeout-count.sql" 2>/dev/null)" || { warn "Cannot query worker_registry drain_deadline_at"; return; }

  if [[ "${overdue}" -gt 0 ]]; then
    fail "Drain timeout: ${overdue} DRAINING worker(s) past drain_deadline_at"
    log "  Affected workers (run heal-drain-timeout.sh to force-offline):"
    psql_file -f "$OPS_SQL_DIR/inspect-workers-drain-timeout-list.sql" 2>/dev/null \
    | while IFS='|' read -r code tenant started deadline; do
        log "    worker_code=${code} tenant=${tenant} started=${started} deadline=${deadline}"
      done
  else
    local draining_total
    draining_total="$(psql_file -f "$OPS_SQL_DIR/inspect-workers-draining-count.sql" 2>/dev/null)" || draining_total=0
    ok "Drain timeout: 0 overdue (${draining_total} still draining within deadline)"
  fi
}

# ── 3. 心跳失联(ONLINE 但已静默) ────────────────────────────────────
check_stale_heartbeat() {
  local stale
  stale="$(psql_file -f "$OPS_SQL_DIR/inspect-workers-stale-heartbeat-count.sql" 2>/dev/null)" || { warn "Cannot query worker_registry heartbeat_at"; return; }

  if [[ "${stale}" -gt 0 ]]; then
    fail "Stale heartbeat: ${stale} ONLINE worker(s) silent for >${STALE_HEARTBEAT_MINUTES}m"
    psql_file -f "$OPS_SQL_DIR/inspect-workers-stale-heartbeat-list.sql" 2>/dev/null || true
  else
    ok "Stale heartbeat: all ONLINE workers reported within ${STALE_HEARTBEAT_MINUTES}m"
  fi
}

# ── 4. 已下线 worker 仍持有活跃任务认领 ────────────────────────
check_decommissioned_with_tasks() {
  # 检查 task_assignment 表是否存在
  local table_exists
  table_exists="$(psql_file -f "$OPS_SQL_DIR/inspect-workers-task-assignment-table-exists.sql" 2>/dev/null)" || table_exists=0

  local orphaned
  if [[ "${table_exists}" -ge 1 ]]; then
    orphaned="$(psql_file -f "$OPS_SQL_DIR/inspect-workers-decommissioned-assignment-count.sql" 2>/dev/null)" || { warn "Cannot check decommissioned/task overlap via task_assignment"; return; }
  else
    orphaned="$(psql_file -f "$OPS_SQL_DIR/inspect-workers-decommissioned-jobtask-count.sql" 2>/dev/null)" || { warn "Cannot check decommissioned/task overlap via job_task"; return; }
  fi

  if [[ "${orphaned}" -gt 0 ]]; then
    fail "Orphaned tasks: ${orphaned} active task(s) assigned to DECOMMISSIONED workers"
  else
    ok "Orphaned tasks: none on DECOMMISSIONED workers"
  fi
}

# ── 主流程 ──────────────────────────────────────────────────────────────────────
require_psql
check_connectivity
check_worker_summary
check_drain_timeout
check_stale_heartbeat
check_decommissioned_with_tasks

log ""
if [[ "${failures}" -gt 0 ]]; then
  log "Worker inspection FAILED: ${failures} failure(s), ${warnings} warning(s)"
  exit 1
elif [[ "${warnings}" -gt 0 ]]; then
  log "Worker inspection PASSED WITH WARNINGS: ${warnings} warning(s)"
  exit 0
fi
log "Worker inspection PASSED"
