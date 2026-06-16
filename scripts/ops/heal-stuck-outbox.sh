#!/usr/bin/env bash
# =========================================================
# heal-stuck-outbox.sh - 自愈：解除卡住的 Outbox 投递
# 说明：
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
#   bash scripts/ops/heal-stuck-outbox.sh
#
#   # 实际重置（需 DB 写权限）
#   BATCH_HEAL_DRY_RUN=false PGHOST=localhost PGUSER=batch PGPASSWORD=secret \
#     bash scripts/ops/heal-stuck-outbox.sh

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=env.sh
source "$ROOT/scripts/ops/env.sh"

# ── 配置 ─────────────────────────────────────────────────────────────
BATCH_HEAL_DRY_RUN="${BATCH_HEAL_DRY_RUN:-true}"
# 超过此秒数未投递的 outbox 视为卡住
OUTBOX_STUCK_SECONDS="${BATCH_HEAL_OUTBOX_STUCK_SECONDS:-300}"

# ── 辅助函数 ───────────────────────────────────────────────────────────────────
log() { printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S')" "$*"; }

psql_file() {
  psql -h "${PGHOST}" -p "${PGPORT}" -U "${PGUSER}" -d "${PGDATABASE}" \
       -X -A -t -v ON_ERROR_STOP=1 -v schema="$BATCH_SCHEMA" \
       -v stuck_seconds="$OUTBOX_STUCK_SECONDS" "$@"
}

require_tools() {
  if ! command -v psql >/dev/null 2>&1; then
    log "ERROR: psql not found — install postgresql-client"
    exit 1
  fi
}

# ── 主流程 ──────────────────────────────────────────────────────────────────────
require_tools

log "heal-stuck-outbox: BATCH_HEAL_DRY_RUN=${BATCH_HEAL_DRY_RUN}"
log "Stuck threshold: ${OUTBOX_STUCK_SECONDS}s"

# 检查连通性
psql_file -f "$OPS_SQL_DIR/common-connectivity.sql" >/dev/null || {
  log "ERROR: DB connection failed"
  exit 1
}

# 统计卡住的事件数
stuck_count="$(psql_file -f "$OPS_SQL_DIR/heal-stuck-outbox-count.sql" 2>/dev/null)" || {
  log "ERROR: cannot query outbox_event"
  exit 1
}

if [[ "${stuck_count}" -eq 0 ]]; then
  log "No stuck outbox events — nothing to heal"
  exit 0
fi

log "Found ${stuck_count} stuck outbox event(s)"
log "Event type breakdown:"
psql_file -f "$OPS_SQL_DIR/heal-stuck-outbox-breakdown.sql" 2>/dev/null \
| while IFS='|' read -r etype cnt oldest max_attempt; do
    log "  event_type=${etype} count=${cnt} oldest=${oldest} max_attempt=${max_attempt}"
  done

if [[ "${BATCH_HEAL_DRY_RUN}" == "true" ]]; then
  log "DRY-RUN: Would reset ${stuck_count} stuck outbox event(s):"
  log "  UPDATE ${BATCH_SCHEMA}.outbox_event"
  log "     SET publish_status = 'FAILED',"
  log "         next_publish_at = NOW(),"
  log "         updated_at = NOW()"
  log "   WHERE publish_status = 'PUBLISHING'"
  log "     AND updated_at < NOW() - INTERVAL '${OUTBOX_STUCK_SECONDS} seconds'"
  log ""
  log "Set BATCH_HEAL_DRY_RUN=false to execute."
  log "Note: After reset the OutboxPollScheduler will pick up events on its next poll cycle."
  log "      If the scheduler appears frozen, restart the batch-orchestrator service."
  exit 0
fi

# 执行重置
updated="$(psql_file -f "$OPS_SQL_DIR/heal-stuck-outbox-reset.sql" 2>/dev/null | grep -c '^[0-9]')" || updated=0

log "Reset ${updated} outbox event(s) — OutboxPollScheduler will retry on next poll."

# 尝试发 DB NOTIFY 立即唤醒轮询器
if psql_file -f "$OPS_SQL_DIR/heal-stuck-outbox-notify.sql" >/dev/null 2>&1; then
  log "Sent NOTIFY outbox_publisher to wake up poll scheduler."
else
  log "Note: NOTIFY failed or not supported — outbox will be retried on next scheduled poll."
  log "      If polling appears frozen, restart batch-orchestrator."
fi

log "heal-stuck-outbox complete: ${updated} event(s) reset"
