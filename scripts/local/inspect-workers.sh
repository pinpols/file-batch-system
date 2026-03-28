#!/usr/bin/env bash
# =========================================================
# inspect-workers.sh - Worker 注册表巡检
# Notes:
# 1) 检查 DRAINING 超时、心跳失联和孤儿任务。
# 2) 汇总各状态 worker 分布。
# =========================================================
#
# 使用方法：
#   PGHOST=localhost PGPORT=5432 PGDATABASE=batch_db PGUSER=batch \
#     PGPASSWORD=secret bash scripts/local/inspect-workers.sh
#
# 如发现 DRAINING 超时 worker，可运行 heal-drain-timeout.sh 自动处理。

set -euo pipefail

# ── configuration ─────────────────────────────────────────────────────────────
PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5432}"
PGDATABASE="${PGDATABASE:-batch_db}"
PGUSER="${PGUSER:-batch}"
export PGPASSWORD="${PGPASSWORD:-}"

BATCH_SCHEMA="${BATCH_SCHEMA:-batch}"
STALE_HEARTBEAT_MINUTES="${BATCH_INSPECT_STALE_HEARTBEAT_MINUTES:-5}"

# ── helpers ───────────────────────────────────────────────────────────────────
failures=0
warnings=0

log()  { printf '%s\n' "$*"; }
ok()   { log "OK:   $*"; }
warn() { log "WARN: $*"; warnings=$((warnings + 1)); }
fail() { log "FAIL: $*"; failures=$((failures + 1)); }

psql_query() {
  psql -h "${PGHOST}" -p "${PGPORT}" -U "${PGUSER}" -d "${PGDATABASE}" \
       -tA -c "$1" 2>&1
}

require_psql() {
  if ! command -v psql >/dev/null 2>&1; then
    fail "psql not found — install postgresql-client"
    exit 1
  fi
}

check_connectivity() {
  local result
  if ! result="$(psql_query "SELECT 1" 2>&1)"; then
    fail "DB connection failed: ${result}"
    exit 1
  fi
}

# ── 1. worker status summary ──────────────────────────────────────────────────
check_worker_summary() {
  log "Worker registry summary:"
  psql_query \
    "SELECT worker_status, COUNT(*) AS cnt
       FROM ${BATCH_SCHEMA}.worker_registry
      GROUP BY worker_status
      ORDER BY worker_status" 2>/dev/null \
  | while IFS='|' read -r status cnt; do
      log "  ${status:-?}: ${cnt:-0}"
    done
}

# ── 2. DRAINING workers past deadline ────────────────────────────────────────
check_drain_timeout() {
  local overdue
  overdue="$(psql_query \
    "SELECT COUNT(*) FROM ${BATCH_SCHEMA}.worker_registry
      WHERE worker_status = 'DRAINING'
        AND drain_deadline_at IS NOT NULL
        AND drain_deadline_at < NOW()" \
    2>/dev/null)" || { warn "Cannot query worker_registry drain_deadline_at"; return; }

  if [[ "${overdue}" -gt 0 ]]; then
    fail "Drain timeout: ${overdue} DRAINING worker(s) past drain_deadline_at"
    log "  Affected workers (run heal-drain-timeout.sh to force-offline):"
    psql_query \
      "SELECT worker_code, tenant_id, drain_started_at, drain_deadline_at
         FROM ${BATCH_SCHEMA}.worker_registry
        WHERE worker_status = 'DRAINING'
          AND drain_deadline_at IS NOT NULL
          AND drain_deadline_at < NOW()
        ORDER BY drain_deadline_at ASC" 2>/dev/null \
    | while IFS='|' read -r code tenant started deadline; do
        log "    worker_code=${code} tenant=${tenant} started=${started} deadline=${deadline}"
      done
  else
    local draining_total
    draining_total="$(psql_query \
      "SELECT COUNT(*) FROM ${BATCH_SCHEMA}.worker_registry
        WHERE worker_status = 'DRAINING'" 2>/dev/null)" || draining_total=0
    ok "Drain timeout: 0 overdue (${draining_total} still draining within deadline)"
  fi
}

# ── 3. stale heartbeat (ONLINE but silent) ────────────────────────────────────
check_stale_heartbeat() {
  local stale
  stale="$(psql_query \
    "SELECT COUNT(*) FROM ${BATCH_SCHEMA}.worker_registry
      WHERE worker_status = 'ONLINE'
        AND (last_heartbeat_at IS NULL
          OR last_heartbeat_at < NOW() - INTERVAL '${STALE_HEARTBEAT_MINUTES} minutes')" \
    2>/dev/null)" || { warn "Cannot query worker_registry last_heartbeat_at"; return; }

  if [[ "${stale}" -gt 0 ]]; then
    fail "Stale heartbeat: ${stale} ONLINE worker(s) silent for >${STALE_HEARTBEAT_MINUTES}m"
    psql_query \
      "SELECT worker_code, tenant_id, worker_type, last_heartbeat_at
         FROM ${BATCH_SCHEMA}.worker_registry
        WHERE worker_status = 'ONLINE'
          AND (last_heartbeat_at IS NULL
            OR last_heartbeat_at < NOW() - INTERVAL '${STALE_HEARTBEAT_MINUTES} minutes')
        ORDER BY last_heartbeat_at ASC NULLS FIRST
        LIMIT 10" 2>/dev/null || true
  else
    ok "Stale heartbeat: all ONLINE workers reported within ${STALE_HEARTBEAT_MINUTES}m"
  fi
}

# ── 4. decommissioned workers with active task claims ────────────────────────
check_decommissioned_with_tasks() {
  # Check if task_assignment table exists
  local table_exists
  table_exists="$(psql_query \
    "SELECT COUNT(*) FROM information_schema.tables
      WHERE table_schema = '${BATCH_SCHEMA}'
        AND table_name = 'task_assignment'" \
    2>/dev/null)" || table_exists=0

  if [[ "${table_exists}" -lt 1 ]]; then
    # Try job_task table
    local orphaned
    orphaned="$(psql_query \
      "SELECT COUNT(*) FROM ${BATCH_SCHEMA}.job_task jt
         JOIN ${BATCH_SCHEMA}.worker_registry wr
              ON wr.worker_code = jt.assigned_worker_code
              AND wr.tenant_id  = jt.tenant_id
        WHERE wr.worker_status = 'DECOMMISSIONED'
          AND jt.task_status IN ('RUNNING','READY','CREATED')" \
      2>/dev/null)" || { warn "Cannot check decommissioned/task overlap (table structure unclear)"; return; }

    if [[ "${orphaned}" -gt 0 ]]; then
      fail "Orphaned tasks: ${orphaned} active task(s) assigned to DECOMMISSIONED workers"
    else
      ok "Orphaned tasks: none on DECOMMISSIONED workers"
    fi
  fi
}

# ── main ──────────────────────────────────────────────────────────────────────
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
