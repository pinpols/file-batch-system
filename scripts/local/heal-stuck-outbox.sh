#!/usr/bin/env bash
# =========================================================
# heal-stuck-outbox.sh - 自愈：解除卡住的 Outbox 投递
# Notes:
# 1) 定位长期未投递的 outbox_event。
# 2) 重置状态并发送 NOTIFY 唤醒 OutboxPollScheduler。
# =========================================================
#
# Outbox 卡住的常见原因：
#   - Kafka broker 不可达导致发送失败，retry_count 超上限后停止重试
#   - OutboxPollScheduler 因异常停止轮询（重启服务即可恢复）
#
# 使用方法：
#   # dry-run（默认）
#   bash scripts/local/heal-stuck-outbox.sh
#
#   # 实际重置（需 DB 写权限）
#   BATCH_HEAL_DRY_RUN=false PGHOST=localhost PGUSER=batch PGPASSWORD=secret \
#     bash scripts/local/heal-stuck-outbox.sh

set -euo pipefail

# ── configuration ─────────────────────────────────────────────────────────────
PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5432}"
PGDATABASE="${PGDATABASE:-batch_db}"
PGUSER="${PGUSER:-batch}"
export PGPASSWORD="${PGPASSWORD:-}"

BATCH_SCHEMA="${BATCH_SCHEMA:-batch}"
BATCH_HEAL_DRY_RUN="${BATCH_HEAL_DRY_RUN:-true}"
# 超过此秒数未投递的 outbox 视为卡住
OUTBOX_STUCK_SECONDS="${BATCH_HEAL_OUTBOX_STUCK_SECONDS:-300}"
# 重置后将 max_retry_count 设置为此值，触发重新投递
OUTBOX_RESET_MAX_RETRY="${BATCH_HEAL_OUTBOX_RESET_MAX_RETRY:-3}"

# ── helpers ───────────────────────────────────────────────────────────────────
log() { printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S')" "$*"; }

psql_exec() {
  psql -h "${PGHOST}" -p "${PGPORT}" -U "${PGUSER}" -d "${PGDATABASE}" \
       -tA -c "$1" 2>&1
}

require_tools() {
  if ! command -v psql >/dev/null 2>&1; then
    log "ERROR: psql not found — install postgresql-client"
    exit 1
  fi
}

# ── main ──────────────────────────────────────────────────────────────────────
require_tools

log "heal-stuck-outbox: BATCH_HEAL_DRY_RUN=${BATCH_HEAL_DRY_RUN}"
log "Stuck threshold: ${OUTBOX_STUCK_SECONDS}s"

# Check connectivity
psql_exec "SELECT 1" >/dev/null || {
  log "ERROR: DB connection failed"
  exit 1
}

# Count stuck events
stuck_count="$(psql_exec \
  "SELECT COUNT(*) FROM ${BATCH_SCHEMA}.outbox_event
    WHERE published_at IS NULL
      AND created_at < NOW() - INTERVAL '${OUTBOX_STUCK_SECONDS} seconds'" \
  2>/dev/null)" || {
  log "ERROR: cannot query outbox_event"
  exit 1
}

if [[ "${stuck_count}" -eq 0 ]]; then
  log "No stuck outbox events — nothing to heal"
  exit 0
fi

log "Found ${stuck_count} stuck outbox event(s)"
log "Event type breakdown:"
psql_exec \
  "SELECT event_type, COUNT(*) AS cnt,
          MIN(created_at) AS oldest, MAX(retry_count) AS max_retries
     FROM ${BATCH_SCHEMA}.outbox_event
    WHERE published_at IS NULL
      AND created_at < NOW() - INTERVAL '${OUTBOX_STUCK_SECONDS} seconds'
    GROUP BY event_type
    ORDER BY cnt DESC" 2>/dev/null \
| while IFS='|' read -r etype cnt oldest max_retry; do
    log "  event_type=${etype} count=${cnt} oldest=${oldest} max_retry=${max_retry}"
  done

if [[ "${BATCH_HEAL_DRY_RUN}" == "true" ]]; then
  log "DRY-RUN: Would reset ${stuck_count} stuck outbox event(s):"
  log "  UPDATE ${BATCH_SCHEMA}.outbox_event"
  log "     SET retry_count = 0,"
  log "         max_retry_count = ${OUTBOX_RESET_MAX_RETRY},"
  log "         updated_at = NOW()"
  log "   WHERE published_at IS NULL"
  log "     AND created_at < NOW() - INTERVAL '${OUTBOX_STUCK_SECONDS} seconds'"
  log ""
  log "Set BATCH_HEAL_DRY_RUN=false to execute."
  log "Note: After reset the OutboxPollScheduler will pick up events on its next poll cycle."
  log "      If the scheduler appears frozen, restart the batch-orchestrator service."
  exit 0
fi

# Execute reset
updated="$(psql_exec \
  "UPDATE ${BATCH_SCHEMA}.outbox_event
      SET retry_count = 0,
          max_retry_count = ${OUTBOX_RESET_MAX_RETRY},
          updated_at = NOW()
    WHERE published_at IS NULL
      AND created_at < NOW() - INTERVAL '${OUTBOX_STUCK_SECONDS} seconds'
  RETURNING id" \
  2>/dev/null | grep -c '^[0-9]')" || updated=0

log "Reset ${updated} outbox event(s) — OutboxPollScheduler will retry on next poll."

# Attempt DB NOTIFY to wake up the poller immediately
if psql_exec "NOTIFY outbox_publisher" >/dev/null 2>&1; then
  log "Sent NOTIFY outbox_publisher to wake up poll scheduler."
else
  log "Note: NOTIFY failed or not supported — outbox will be retried on next scheduled poll."
  log "      If polling appears frozen, restart batch-orchestrator."
fi

log "heal-stuck-outbox complete: ${updated} event(s) reset"
