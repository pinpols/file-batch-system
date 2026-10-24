#!/usr/bin/env bash
# 用途：自愈：按指定条件重放 job_partition（分区重放/重试回放）。
# 用法：在仓库根目录执行 bash scripts/ops/heal-retry-partitions.sh；参数与前置环境见下方说明及所属 README。
# =========================================================
# heal-retry-partitions.sh - 自愈：按指定条件重放 job_partition（分区重放/重试回放）
#
# 说明：
# 1) 默认重放 partition_status='FAILED' 的分区（也可通过环境变量调整）
# 2) 直连编排器内部重放接口：POST /internal/recoveries/partitions/{partitionId}/replay
# 3) 该操作会触发 retryGovernanceService.retryPartition，重排队到 dispatch outbox
#
# 使用方法：
#   # dry-run（默认）
#   bash scripts/ops/heal-retry-partitions.sh
#
#   # 实际执行（建议限定租户）
#   BATCH_HEAL_RETRY_DRY_RUN=false \
#     BATCH_INTERNAL_SECRET="$SECRET" \
#     BATCH_HEAL_RETRY_TENANT=tenant-001 \
#     bash scripts/ops/heal-retry-partitions.sh
#
# 变量：
#   BATCH_HEAL_RETRY_DRY_RUN                 true/false（默认 true）
#   BATCH_HEAL_RETRY_TENANT                 指定租户（空表示全部）
#   BATCH_HEAL_RETRY_PARTITION_STATUS      指定分区状态（默认 FAILED）
#   BATCH_HEAL_RETRY_BATCH_SIZE           单批最大数量（默认 50）
#   BATCH_HEAL_RETRY_SLEEP_MS            批次间休眠毫秒（默认 500）
#   BATCH_INTERNAL_SECRET                实际执行所需的非默认内部服务密钥
#
# =========================================================

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=scripts/ops/env.sh
source "$ROOT/scripts/ops/env.sh"

require_tools() {
  for tool in psql curl; do
    if ! command -v "${tool}" >/dev/null 2>&1; then
      echo "ERROR: ${tool} not found" >&2
      exit 1
    fi
  done
}

BATCH_HEAL_RETRY_DRY_RUN="${BATCH_HEAL_RETRY_DRY_RUN:-true}"
BATCH_HEAL_RETRY_TENANT="${BATCH_HEAL_RETRY_TENANT:-}"
BATCH_HEAL_RETRY_PARTITION_STATUS="${BATCH_HEAL_RETRY_PARTITION_STATUS:-FAILED}"
BATCH_HEAL_RETRY_BATCH_SIZE="${BATCH_HEAL_RETRY_BATCH_SIZE:-50}"
BATCH_HEAL_RETRY_SLEEP_MS="${BATCH_HEAL_RETRY_SLEEP_MS:-500}"

# ── 辅助函数 ────────────────────────────────────────────────────────────────────
log() { printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S')" "$*"; }

psql_file() {
  psql -h "${PGHOST}" -p "${PGPORT}" -U "${PGUSER}" -d "${PGDATABASE}" \
       -X -A -t -v ON_ERROR_STOP=1 -v schema="$BATCH_SCHEMA" \
       -v tenant="$BATCH_HEAL_RETRY_TENANT" \
       -v partition_status="$BATCH_HEAL_RETRY_PARTITION_STATUS" "$@"
}

orchestrator_post() {
  local path="$1"
  local body="$2"

  curl -fsS -X POST \
    -H "Content-Type: application/json" \
    -H "X-Internal-Secret: ${BATCH_INTERNAL_SECRET}" \
    -d "${body}" \
    "${BATCH_ORCHESTRATOR_URL%/}${path}"
}

sleep_ms() {
  local ms="$1"
  local secs
  secs="$(echo "scale=3; ${ms}/1000" | bc 2>/dev/null)" || secs="0.5"
  sleep "${secs}"
}

require_tools

case "$BATCH_HEAL_RETRY_DRY_RUN" in
  true) ;;
  false) batch_require_internal_secret ;;
  *) echo "ERROR: BATCH_HEAL_RETRY_DRY_RUN must be true or false" >&2; exit 2 ;;
esac

log "heal-retry-partitions: DRY_RUN=${BATCH_HEAL_RETRY_DRY_RUN}"
log "Filter: partition_status=${BATCH_HEAL_RETRY_PARTITION_STATUS} tenant=${BATCH_HEAL_RETRY_TENANT:-<all>}"

total="$(psql_file -f "$OPS_SQL_DIR/heal-retry-partitions-count.sql")" || {
  query_status=$?
  log "ERROR: cannot count job_partition candidates"
  exit "$query_status"
}

if [[ "${total}" -eq 0 ]]; then
  log "No job_partition match filter — nothing to heal"
  exit 0
fi

log "Total to replay: ${total} (batch_size=${BATCH_HEAL_RETRY_BATCH_SIZE})"

replayed=0
failed=0
offset=0

while true; do
  batch="$(psql_file \
      -v batch_size="$BATCH_HEAL_RETRY_BATCH_SIZE" \
      -v batch_offset="$offset" \
      -f "$OPS_SQL_DIR/heal-retry-partitions-batch.sql")" || {
      query_status=$?
      log "ERROR: cannot query job_partition at offset ${offset}"
      exit "$query_status"
    }

  [[ -z "${batch}" ]] && break

  batch_count=0
  while IFS='|' read -r partition_id tenant_id; do
    [[ -z "${partition_id}" ]] && continue
    batch_count=$((batch_count + 1))

    if [[ "${BATCH_HEAL_RETRY_DRY_RUN}" == "true" ]]; then
      log "DRY-RUN: Would POST /internal/recoveries/partitions/${partition_id}/replay tenant=${tenant_id}"
      replayed=$((replayed + 1))
      continue
    fi

    response=""
    if response="$(orchestrator_post \
      "/internal/recoveries/partitions/${partition_id}/replay" \
      "{\"tenantId\":\"${tenant_id}\"}" 2>&1)"; then
      log "  Replayed partitionId=${partition_id} tenant=${tenant_id}"
      replayed=$((replayed + 1))
    else
      log "  FAILED partitionId=${partition_id} tenant=${tenant_id}: ${response}"
      failed=$((failed + 1))
    fi
  done <<<"${batch}"

  [[ "${batch_count}" -lt "${BATCH_HEAL_RETRY_BATCH_SIZE}" ]] && break
  offset=$((offset + BATCH_HEAL_RETRY_BATCH_SIZE))

  if [[ "${BATCH_HEAL_RETRY_DRY_RUN}" != "true" && "${BATCH_HEAL_RETRY_SLEEP_MS}" -gt 0 ]]; then
    sleep_ms "${BATCH_HEAL_RETRY_SLEEP_MS}"
  fi
done

log ""
if [[ "${BATCH_HEAL_RETRY_DRY_RUN}" == "true" ]]; then
  log "DRY-RUN complete: ${replayed} partition(s) would be replayed"
  log "Set BATCH_HEAL_RETRY_DRY_RUN=false to execute."
elif [[ "${failed}" -gt 0 ]]; then
  log "Heal complete: ${replayed} replayed, ${failed} failed"
  exit 1
else
  log "Heal complete: ${replayed} partition(s) replayed successfully"
fi
