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

# ── configuration ─────────────────────────────────────────────────────────────
PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-15432}"
PGDATABASE="${PGDATABASE:-batch_db}"
PGUSER="${PGUSER:-batch}"
export PGPASSWORD="${PGPASSWORD:-}"

BATCH_SCHEMA="${BATCH_SCHEMA:-batch}"
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

# ── 1. connectivity ───────────────────────────────────────────────────────────
check_connectivity() {
  local result
  if ! result="$(psql_query "SELECT 1" 2>&1)"; then
    fail "DB connection failed: ${result}"
    exit 1
  fi
  ok "DB connection to ${PGHOST}:${PGPORT}/${PGDATABASE}"
}

# ── 2. Flyway migration history ───────────────────────────────────────────────
check_flyway() {
  local flyway_table="${BATCH_SCHEMA}.flyway_schema_history"
  local failed_count
  failed_count="$(psql_query \
    "SELECT COUNT(*) FROM ${flyway_table} WHERE success = false" \
    2>/dev/null)" || {
    # Flyway table might be in public schema
    flyway_table="flyway_schema_history"
    failed_count="$(psql_query \
      "SELECT COUNT(*) FROM ${flyway_table} WHERE success = false" \
      2>/dev/null)" || {
      warn "Cannot query flyway_schema_history — skipping Flyway check"
      return
    }
  }
  if [[ "${failed_count}" -gt 0 ]]; then
    fail "Flyway: ${failed_count} failed migration(s) in history"
    psql_query \
      "SELECT version, description, installed_on
         FROM ${flyway_table}
        WHERE success = false
        ORDER BY installed_rank DESC
        LIMIT 5" 2>/dev/null || true
  else
    local total
    total="$(psql_query "SELECT COUNT(*) FROM ${flyway_table} WHERE success = true" 2>/dev/null)" || total="?"
    ok "Flyway: ${total} successful migration(s), 0 failed"
  fi
}

# ── 3. recent alert events ────────────────────────────────────────────────────
check_alert_events() {
  local critical warning
  critical="$(psql_query \
    "SELECT COUNT(*) FROM ${BATCH_SCHEMA}.alert_event
      WHERE severity = 'CRITICAL'
        AND last_seen_at >= NOW() - INTERVAL '${ALERT_LOOKBACK_MINUTES} minutes'" \
    2>/dev/null)" || { warn "Cannot query alert_event"; return; }

  warning="$(psql_query \
    "SELECT COUNT(*) FROM ${BATCH_SCHEMA}.alert_event
      WHERE severity = 'WARNING'
        AND last_seen_at >= NOW() - INTERVAL '${ALERT_LOOKBACK_MINUTES} minutes'" \
    2>/dev/null)" || warning=0

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
  stuck="$(psql_query \
    "SELECT COUNT(*) FROM ${BATCH_SCHEMA}.job_instance
      WHERE instance_status IN ('RUNNING','PENDING')
        AND updated_at < NOW() - INTERVAL '${STUCK_JOB_MINUTES} minutes'" \
    2>/dev/null)" || { warn "Cannot query job_instance"; return; }

  if [[ "${stuck}" -gt 0 ]]; then
    fail "Stuck jobs: ${stuck} instance(s) in RUNNING/PENDING for >${STUCK_JOB_MINUTES}m"
    psql_query \
      "SELECT tenant_id, job_code, instance_no, instance_status, updated_at
         FROM ${BATCH_SCHEMA}.job_instance
        WHERE instance_status IN ('RUNNING','PENDING')
          AND updated_at < NOW() - INTERVAL '${STUCK_JOB_MINUTES} minutes'
        ORDER BY updated_at ASC
        LIMIT 10" 2>/dev/null || true
  else
    ok "Stuck jobs: none (threshold ${STUCK_JOB_MINUTES}m)"
  fi
}

# ── 5. outbox backlog ─────────────────────────────────────────────────────────
check_outbox_backlog() {
  local pending
  pending="$(psql_query \
    "SELECT COUNT(*) FROM ${BATCH_SCHEMA}.outbox_event
      WHERE publish_status IN ('NEW','FAILED','PUBLISHING')
        AND created_at < NOW() - INTERVAL '${OUTBOX_LAG_SECONDS} seconds'" \
    2>/dev/null)" || { warn "Cannot query outbox_event"; return; }

  if [[ "${pending}" -gt 0 ]]; then
    fail "Outbox backlog: ${pending} unpublished event(s) older than ${OUTBOX_LAG_SECONDS}s"
    psql_query \
      "SELECT event_type, COUNT(*) AS cnt, MIN(created_at) AS oldest
         FROM ${BATCH_SCHEMA}.outbox_event
        WHERE publish_status IN ('NEW','FAILED','PUBLISHING')
          AND created_at < NOW() - INTERVAL '${OUTBOX_LAG_SECONDS} seconds'
        GROUP BY event_type
        ORDER BY oldest ASC
        LIMIT 10" 2>/dev/null || true
  else
    ok "Outbox backlog: 0 unpublished events older than ${OUTBOX_LAG_SECONDS}s"
  fi

  local stale_publishing
  stale_publishing="$(psql_query \
    "SELECT COUNT(*) FROM ${BATCH_SCHEMA}.outbox_event
      WHERE publish_status = 'PUBLISHING'
        AND updated_at < NOW() - INTERVAL '${OUTBOX_LAG_SECONDS} seconds'" \
    2>/dev/null)" || stale_publishing=0
  if [[ "${stale_publishing}" -gt 0 ]]; then
    fail "Outbox stale publishing: ${stale_publishing} event(s) stuck in PUBLISHING for >${OUTBOX_LAG_SECONDS}s"
  fi
}

# ── 6. dead letter backlog ────────────────────────────────────────────────────
check_dead_letters() {
  local new_dlq
  new_dlq="$(psql_query \
    "SELECT COUNT(*) FROM ${BATCH_SCHEMA}.dead_letter_task
      WHERE replay_status = 'NEW'" \
    2>/dev/null)" || { warn "Cannot query dead_letter_task"; return; }

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
  waiting="$(psql_query \
    "SELECT COUNT(*) FROM ${BATCH_SCHEMA}.retry_schedule
      WHERE retry_status = 'WAITING'" \
    2>/dev/null)" || { warn "Cannot query retry_schedule"; return; }

  overdue="$(psql_query \
    "SELECT COUNT(*) FROM ${BATCH_SCHEMA}.retry_schedule
      WHERE retry_status = 'WAITING'
        AND next_retry_at < NOW() - INTERVAL '10 minutes'" \
    2>/dev/null)" || overdue=0

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
  inconsistent="$(psql_query \
    "SELECT COUNT(DISTINCT ji.id)
       FROM ${BATCH_SCHEMA}.job_instance ji
      WHERE ji.instance_status IN ('SUCCESS','FAILED','PARTIAL_FAILED','CANCELLED','TERMINATED')
        AND (
          EXISTS (
            SELECT 1 FROM ${BATCH_SCHEMA}.job_partition p
             WHERE p.tenant_id = ji.tenant_id
               AND p.job_instance_id = ji.id
               AND p.partition_status IN ('CREATED','WAITING','READY','RUNNING','RETRYING')
          )
          OR EXISTS (
            SELECT 1 FROM ${BATCH_SCHEMA}.job_task t
             WHERE t.tenant_id = ji.tenant_id
               AND t.job_instance_id = ji.id
               AND t.task_status IN ('CREATED','READY','RUNNING')
          )
        )" 2>/dev/null)" || { warn "Cannot query terminal job child consistency"; return; }

  if [[ "${inconsistent}" -gt 0 ]]; then
    fail "Terminal consistency: ${inconsistent} terminal job_instance(s) still have active partition/task children"
    psql_query \
      "SELECT ji.tenant_id, ji.id, ji.job_code, ji.instance_status, ji.updated_at
         FROM ${BATCH_SCHEMA}.job_instance ji
        WHERE ji.instance_status IN ('SUCCESS','FAILED','PARTIAL_FAILED','CANCELLED','TERMINATED')
          AND (
            EXISTS (
              SELECT 1 FROM ${BATCH_SCHEMA}.job_partition p
               WHERE p.tenant_id = ji.tenant_id
                 AND p.job_instance_id = ji.id
                 AND p.partition_status IN ('CREATED','WAITING','READY','RUNNING','RETRYING')
            )
            OR EXISTS (
              SELECT 1 FROM ${BATCH_SCHEMA}.job_task t
               WHERE t.tenant_id = ji.tenant_id
                 AND t.job_instance_id = ji.id
                 AND t.task_status IN ('CREATED','READY','RUNNING')
            )
          )
        ORDER BY ji.updated_at ASC
        LIMIT 10" 2>/dev/null || true
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
