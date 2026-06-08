#!/usr/bin/env bash
# =========================================================
# inspect-db.sh - PostgreSQL 数据库巡检
# 说明：
# 1) 检查 Flyway 历史、告警、长时间 RUNNING 作业和 Outbox 积压。
# 2) 默认只做只读巡检，不修改库内状态。
# =========================================================
#   5. 死信队列积压（replay_status = NEW 超 dlq_warn_count 条）
#   6. 待重试调度积压（retry_status = WAITING 超 retry_warn_count 条）
#   7. 业务终态实例下仍有活跃 partition/task 子行
#
# 使用方法：
#   PGHOST=localhost PGPORT=15432 PGDATABASE=batch_platform PGUSER=batch_user \
#     PGPASSWORD=secret bash scripts/ops/inspect-db.sh
#
# 全部可配置环境变量见下方 "configuration" 节。

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=env.sh
source "$ROOT/scripts/ops/env.sh"

# ── configuration ─────────────────────────────────────────────────────────────
STUCK_JOB_MINUTES="${BATCH_INSPECT_STUCK_JOB_MINUTES:-60}"
OUTBOX_LAG_SECONDS="${BATCH_INSPECT_OUTBOX_LAG_SECONDS:-120}"
DLQ_WARN_COUNT="${BATCH_INSPECT_DLQ_WARN_COUNT:-50}"
RETRY_WARN_COUNT="${BATCH_INSPECT_RETRY_WARN_COUNT:-200}"
ALERT_LOOKBACK_MINUTES="${BATCH_INSPECT_ALERT_LOOKBACK_MINUTES:-60}"

# ── helpers ───────────────────────────────────────────────────────────────────
failures=0
warnings=0

log()  { printf '%s\n' "$*"; }
ok()   { log "OK:   $*"; }
warn() { log "WARN: $*"; warnings=$((warnings + 1)); }
fail() { log "FAIL: $*"; failures=$((failures + 1)); }

psql_file() {
  psql -h "${PGHOST}" -p "${PGPORT}" -U "${PGUSER}" -d "${PGDATABASE}" \
       -tA -v ON_ERROR_STOP=1 -v schema="$BATCH_SCHEMA" \
       -v stuck_job_minutes="$STUCK_JOB_MINUTES" \
       -v outbox_lag_seconds="$OUTBOX_LAG_SECONDS" \
       -v alert_lookback_minutes="$ALERT_LOOKBACK_MINUTES" "$@"
}

require_psql() {
  if ! command -v psql >/dev/null 2>&1; then
    fail "psql not found — install postgresql-client"
    exit 1
  fi
}

# ── 1. connectivity ───────────────────────────────────────────────────────────
check_connectivity() {
  local result
  if ! result="$(psql_file -f "$OPS_SQL_DIR/common-connectivity.sql" 2>&1)"; then
    fail "DB connection failed: ${result}"
    exit 1
  fi
  ok "DB connection to ${PGHOST}:${PGPORT}/${PGDATABASE}"
}

# ── 2. Flyway migration history ───────────────────────────────────────────────
check_flyway() {
  local flyway_scope="schema"
  local failed_count
  failed_count="$(psql_file -f "$OPS_SQL_DIR/inspect-db-flyway-failed-count.sql" 2>/dev/null)" || {
    flyway_scope="public"
    failed_count="$(psql_file -f "$OPS_SQL_DIR/inspect-db-flyway-public-failed-count.sql" 2>/dev/null)" || {
      warn "Cannot query flyway_schema_history — skipping Flyway check"
      return
    }
  }
  if [[ "${failed_count}" -gt 0 ]]; then
    fail "Flyway: ${failed_count} failed migration(s) in history"
    if [[ "$flyway_scope" == "public" ]]; then
      psql_file -f "$OPS_SQL_DIR/inspect-db-flyway-public-failed-list.sql" 2>/dev/null || true
    else
      psql_file -f "$OPS_SQL_DIR/inspect-db-flyway-failed-list.sql" 2>/dev/null || true
    fi
  else
    local total
    if [[ "$flyway_scope" == "public" ]]; then
      total="$(psql_file -f "$OPS_SQL_DIR/inspect-db-flyway-public-success-count.sql" 2>/dev/null)" || total="?"
    else
      total="$(psql_file -f "$OPS_SQL_DIR/inspect-db-flyway-success-count.sql" 2>/dev/null)" || total="?"
    fi
    ok "Flyway: ${total} successful migration(s), 0 failed"
  fi
}

# ── 3. recent alert events ────────────────────────────────────────────────────
check_alert_events() {
  local critical warning
  critical="$(psql_file -f "$OPS_SQL_DIR/inspect-db-alert-critical-count.sql" 2>/dev/null)" || { warn "Cannot query alert_event"; return; }

  warning="$(psql_file -f "$OPS_SQL_DIR/inspect-db-alert-warning-count.sql" 2>/dev/null)" || warning=0

  if [[ "${critical}" -gt 0 ]]; then
    fail "Alert events: ${critical} CRITICAL in last ${ALERT_LOOKBACK_MINUTES}m"
  elif [[ "${warning}" -gt 0 ]]; then
    warn "Alert events: ${warning} WARNING in last ${ALERT_LOOKBACK_MINUTES}m"
  else
    ok "Alert events: 0 CRITICAL, 0 WARNING in last ${ALERT_LOOKBACK_MINUTES}m"
  fi
}

# ── 4. stuck job instances ────────────────────────────────────────────────────
check_stuck_jobs() {
  local stuck
  stuck="$(psql_file -f "$OPS_SQL_DIR/inspect-db-stuck-jobs-count.sql" 2>/dev/null)" || { warn "Cannot query job_instance"; return; }

  if [[ "${stuck}" -gt 0 ]]; then
    fail "Stuck jobs: ${stuck} instance(s) in RUNNING/PENDING for >${STUCK_JOB_MINUTES}m"
    psql_file -f "$OPS_SQL_DIR/inspect-db-stuck-jobs-list.sql" 2>/dev/null || true
  else
    ok "Stuck jobs: none (threshold ${STUCK_JOB_MINUTES}m)"
  fi
}

# ── 5. outbox backlog ─────────────────────────────────────────────────────────
check_outbox_backlog() {
  local pending
  pending="$(psql_file -f "$OPS_SQL_DIR/inspect-db-outbox-backlog-count.sql" 2>/dev/null)" || { warn "Cannot query outbox_event"; return; }

  if [[ "${pending}" -gt 0 ]]; then
    fail "Outbox backlog: ${pending} unpublished event(s) older than ${OUTBOX_LAG_SECONDS}s"
    psql_file -f "$OPS_SQL_DIR/inspect-db-outbox-backlog-list.sql" 2>/dev/null || true
  else
    ok "Outbox backlog: 0 unpublished events older than ${OUTBOX_LAG_SECONDS}s"
  fi

  local stale_publishing
  stale_publishing="$(psql_file -f "$OPS_SQL_DIR/inspect-db-outbox-stale-publishing-count.sql" 2>/dev/null)" || stale_publishing=0
  if [[ "${stale_publishing}" -gt 0 ]]; then
    fail "Outbox stale publishing: ${stale_publishing} event(s) stuck in PUBLISHING for >${OUTBOX_LAG_SECONDS}s"
  fi
}

# ── 6. dead letter backlog ────────────────────────────────────────────────────
check_dead_letters() {
  local new_dlq
  new_dlq="$(psql_file -f "$OPS_SQL_DIR/inspect-db-dead-letter-new-count.sql" 2>/dev/null)" || { warn "Cannot query dead_letter_task"; return; }

  if [[ "${new_dlq}" -gt "${DLQ_WARN_COUNT}" ]]; then
    fail "Dead letters: ${new_dlq} tasks with replay_status=NEW (threshold ${DLQ_WARN_COUNT})"
  elif [[ "${new_dlq}" -gt 0 ]]; then
    warn "Dead letters: ${new_dlq} tasks with replay_status=NEW"
  else
    ok "Dead letters: 0 unprocessed"
  fi
}

# ── 7. retry schedule backlog ─────────────────────────────────────────────────
check_retry_backlog() {
  local waiting overdue
  waiting="$(psql_file -f "$OPS_SQL_DIR/inspect-db-retry-waiting-count.sql" 2>/dev/null)" || { warn "Cannot query retry_schedule"; return; }

  overdue="$(psql_file -f "$OPS_SQL_DIR/inspect-db-retry-overdue-count.sql" 2>/dev/null)" || overdue=0

  if [[ "${overdue}" -gt 0 ]]; then
    fail "Retry schedule: ${overdue} overdue WAITING retries (next_retry_at >10m past)"
  elif [[ "${waiting}" -gt "${RETRY_WARN_COUNT}" ]]; then
    warn "Retry schedule: ${waiting} WAITING retries (threshold ${RETRY_WARN_COUNT})"
  else
    ok "Retry schedule: ${waiting} WAITING, 0 overdue"
  fi
}

# ── 8. terminal job instances with active children ───────────────────────────
check_terminal_instance_active_children() {
  local inconsistent
  inconsistent="$(psql_file -f "$OPS_SQL_DIR/inspect-db-terminal-active-children-count.sql" 2>/dev/null)" || { warn "Cannot query terminal job child consistency"; return; }

  if [[ "${inconsistent}" -gt 0 ]]; then
    fail "Terminal consistency: ${inconsistent} terminal job_instance(s) still have active partition/task children"
    psql_file -f "$OPS_SQL_DIR/inspect-db-terminal-active-children-list.sql" 2>/dev/null || true
  else
    ok "Terminal consistency: no terminal job_instance has active partition/task children"
  fi
}

# ── main ──────────────────────────────────────────────────────────────────────
require_psql
check_connectivity
check_flyway
check_alert_events
check_stuck_jobs
check_outbox_backlog
check_dead_letters
check_retry_backlog
check_terminal_instance_active_children

log ""
if [[ "${failures}" -gt 0 ]]; then
  log "DB inspection FAILED: ${failures} failure(s), ${warnings} warning(s)"
  exit 1
elif [[ "${warnings}" -gt 0 ]]; then
  log "DB inspection PASSED WITH WARNINGS: ${warnings} warning(s)"
  exit 0
fi
log "DB inspection PASSED"
