#!/usr/bin/env bash
# =========================================================
# heal-dead-letters.sh - 自愈：批量重放 NEW 状态的死信任务
# 说明：
# 1) 查询 dead_letter_task 中 replay_status='NEW' 的记录并按批处理。
# 2) 调用编排器内部死信重放接口触发重放（绕过审批门控）。
# =========================================================
# 使用方法：
#   # dry-run（默认）
#   bash scripts/ops/heal-dead-letters.sh
#
#   # 实际重放，限定单个租户
#   BATCH_HEAL_DRY_RUN=false \
#   BATCH_ORCHESTRATOR_URL=http://localhost:8082 \
#   BATCH_HEAL_DLQ_TENANT=tenant-001 \
#     bash scripts/ops/heal-dead-letters.sh
#
# 变量：
#   BATCH_HEAL_DLQ_TENANT       只处理指定租户（留空处理全部）
#   BATCH_HEAL_DLQ_SOURCE_TYPE  只处理指定 source_type（留空处理全部）
#   BATCH_HEAL_DLQ_BATCH_SIZE   每批最大重放数量（默认 50）
#   BATCH_HEAL_DLQ_SLEEP_MS     每批之间的休眠毫秒数（默认 500）
#   BATCH_HEAL_DRY_RUN          true（默认）/false

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=env.sh
source "$ROOT/scripts/ops/env.sh"

# ── 配置 ─────────────────────────────────────────────────────────────
BATCH_ORCHESTRATOR_URL="${BATCH_ORCHESTRATOR_URL:-http://localhost:8082}"
BATCH_ORCHESTRATOR_TOKEN="${BATCH_ORCHESTRATOR_TOKEN:-}"
BATCH_HEAL_DRY_RUN="${BATCH_HEAL_DRY_RUN:-true}"
BATCH_HEAL_DLQ_TENANT="${BATCH_HEAL_DLQ_TENANT:-}"
BATCH_HEAL_DLQ_SOURCE_TYPE="${BATCH_HEAL_DLQ_SOURCE_TYPE:-}"
BATCH_HEAL_DLQ_BATCH_SIZE="${BATCH_HEAL_DLQ_BATCH_SIZE:-50}"
BATCH_HEAL_DLQ_SLEEP_MS="${BATCH_HEAL_DLQ_SLEEP_MS:-500}"

# ── 辅助函数 ───────────────────────────────────────────────────────────────────
log() { printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S')" "$*"; }

psql_file() {
  psql -h "${PGHOST}" -p "${PGPORT}" -U "${PGUSER}" -d "${PGDATABASE}" \
       -X -A -t -v ON_ERROR_STOP=1 -v schema="$BATCH_SCHEMA" \
       -v tenant="$BATCH_HEAL_DLQ_TENANT" \
       -v source_type="$BATCH_HEAL_DLQ_SOURCE_TYPE" "$@"
}

console_post() {
  local path="$1"
  local body="${2:-{}}"
  local idempotencyKey="${3:-}"
  local auth_header=()
  if [[ -n "${BATCH_ORCHESTRATOR_TOKEN}" ]]; then
    auth_header=(-H "Authorization: Bearer ${BATCH_ORCHESTRATOR_TOKEN}")
  fi
  local idem_header=()
  if [[ -n "${idempotencyKey}" ]]; then
    idem_header=(-H "Idempotency-Key: ${idempotencyKey}")
  fi
  curl -fsS -X POST \
    -H "Content-Type: application/json" \
    "${idem_header[@]}" \
    "${auth_header[@]}" \
    -d "${body}" \
    "${BATCH_ORCHESTRATOR_URL%/}${path}"
}

sleep_ms() {
  local ms="$1"
  local secs
  secs="$(echo "scale=3; ${ms}/1000" | bc 2>/dev/null)" || secs="0.5"
  sleep "${secs}"
}

require_tools() {
  for tool in psql curl; do
    if ! command -v "${tool}" >/dev/null 2>&1; then
      log "ERROR: ${tool} not found"
      exit 1
    fi
  done
}

# ── 主流程 ──────────────────────────────────────────────────────────────────────
require_tools

log "heal-dead-letters: BATCH_HEAL_DRY_RUN=${BATCH_HEAL_DRY_RUN}"
log "Filter: tenant=${BATCH_HEAL_DLQ_TENANT:-<all>} source_type=${BATCH_HEAL_DLQ_SOURCE_TYPE:-<all>}"

# 打印汇总
log "Dead-letter summary:"
psql_file -f "$OPS_SQL_DIR/heal-dead-letters-summary.sql" 2>/dev/null \
| while IFS='|' read -r tenant src cnt; do
    log "  tenant=${tenant} source_type=${src} count=${cnt}"
  done

total="$(psql_file -f "$OPS_SQL_DIR/heal-dead-letters-count.sql" 2>/dev/null)" || total=0

if [[ "${total}" -eq 0 ]]; then
  log "No dead letters with replay_status=NEW — nothing to heal"
  exit 0
fi

log "Total to replay: ${total} (batch_size=${BATCH_HEAL_DLQ_BATCH_SIZE})"

replayed=0
failed=0
offset=0

while true; do
  # 取一批：id|tenant_id
  batch="$(psql_file \
      -v batch_size="$BATCH_HEAL_DLQ_BATCH_SIZE" \
      -v batch_offset="$offset" \
      -f "$OPS_SQL_DIR/heal-dead-letters-batch.sql" 2>/dev/null)" || {
    log "ERROR: cannot query dead_letter_task at offset ${offset}"
    break
  }

  [[ -z "${batch}" ]] && break

  batch_count=0
  while IFS='|' read -r dlq_id tenant_id; do
    [[ -z "${dlq_id}" ]] && continue
    batch_count=$((batch_count + 1))

    if [[ "${BATCH_HEAL_DRY_RUN}" == "true" ]]; then
      log "DRY-RUN: Would POST /internal/dead-letters/${dlq_id}/replay tenant=${tenant_id}"
      replayed=$((replayed + 1))
      continue
    fi

    response=""
    idem_key="heal-dlq:${tenant_id}:${dlq_id}:$(date +%s)"
    if response="$(console_post \
          "/internal/dead-letters/${dlq_id}/replay" \
          "{\"tenantId\":\"${tenant_id}\"}"
          "${idem_key}" 2>&1)"; then
      log "  Replayed id=${dlq_id} tenant=${tenant_id}"
      replayed=$((replayed + 1))
    else
      log "  FAILED id=${dlq_id} tenant=${tenant_id}: ${response}"
      failed=$((failed + 1))
    fi
  done <<<"${batch}"

  [[ "${batch_count}" -lt "${BATCH_HEAL_DLQ_BATCH_SIZE}" ]] && break
  offset=$((offset + BATCH_HEAL_DLQ_BATCH_SIZE))

  if [[ "${BATCH_HEAL_DRY_RUN}" != "true" && "${BATCH_HEAL_DLQ_SLEEP_MS}" -gt 0 ]]; then
    sleep_ms "${BATCH_HEAL_DLQ_SLEEP_MS}"
  fi
done

log ""
if [[ "${BATCH_HEAL_DRY_RUN}" == "true" ]]; then
  log "DRY-RUN complete: ${replayed} dead letter(s) would be replayed"
  log "Set BATCH_HEAL_DRY_RUN=false to execute."
elif [[ "${failed}" -gt 0 ]]; then
  log "Heal complete: ${replayed} replayed, ${failed} failed"
  exit 1
else
  log "Heal complete: ${replayed} dead letter(s) replayed successfully"
fi
