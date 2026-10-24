#!/usr/bin/env bash
# 用途：只读诊断卡住的 Outbox 投递。
# 用法：在仓库根目录执行 bash scripts/ops/heal-stuck-outbox.sh；参数与前置环境见下方说明及所属 README。
# =========================================================
# heal-stuck-outbox.sh - 只读诊断卡住的 Outbox 投递
# 说明：
# 1) 定位长期未投递的 outbox_event。
# 2) 只报告候选，不直接修改 outbox_event；状态回收由 Orchestrator 管理。
# =========================================================
#
# Outbox 卡住的常见原因：
#   - Kafka broker 不可达导致发送失败，retry_count 超上限后停止重试
#   - OutboxPollScheduler 因异常停止轮询（重启服务即可恢复）
#
# 使用方法：
#   # 只读诊断（默认，也可显式传 --dry-run）
#   bash scripts/ops/heal-stuck-outbox.sh
#   bash scripts/ops/heal-stuck-outbox.sh --dry-run
#
# stale PUBLISHING 由 OutboxPollScheduler 自动回收；FAILED/GIVE_UP 使用
# Orchestrator 的受审计 /internal/outbox/republish 接口处理。

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

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --dry-run) ;;
    --help|-h)
      sed -n '2,20p' "$0"
      exit 0
      ;;
    *)
      log "ERROR: unsupported option: $1 (diagnostic mode is read-only)"
      exit 2
      ;;
  esac
  shift
done

case "$BATCH_HEAL_DRY_RUN" in
  true) ;;
  false)
    log "ERROR: BATCH_HEAL_DRY_RUN=false is unsupported; outbox writes must go through Orchestrator"
    exit 2
    ;;
  *)
    log "ERROR: BATCH_HEAL_DRY_RUN must be 'true' (read-only diagnostic)"
    exit 2
    ;;
esac

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

log "DRY-RUN: found ${stuck_count} stale PUBLISHING event(s); no database writes were made."
log "OutboxPollScheduler owns stale PUBLISHING recovery; inspect its health and follow the outbox Runbook."
log "FAILED/GIVE_UP events require the audited Orchestrator republish operation."
