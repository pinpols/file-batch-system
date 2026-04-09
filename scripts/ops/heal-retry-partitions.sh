#!/usr/bin/env bash
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
#     BATCH_ORCHESTRATOR_URL=http://localhost:8082 \
#     BATCH_HEAL_RETRY_TENANT=tenant-001 \
#     bash scripts/ops/heal-retry-partitions.sh
#
# 变量：
#   BATCH_HEAL_RETRY_DRY_RUN                 true/false（默认 true）
#   BATCH_HEAL_RETRY_TENANT                 指定租户（空表示全部）
#   BATCH_HEAL_RETRY_PARTITION_STATUS      指定分区状态（默认 FAILED）
#   BATCH_HEAL_RETRY_BATCH_SIZE           单批最大数量（默认 50）
#   BATCH_HEAL_RETRY_SLEEP_MS            批次间休眠毫秒（默认 500）
#   BATCH_ORCHESTRATOR_TOKEN              可选：Orchestrator token
#
# =========================================================

set -euo pipefail

require_tools() {
  for tool in psql curl; do
    if ! command -v "${tool}" >/dev/null 2>&1; then
      echo "ERROR: ${tool} not found" >&2
      exit 1
    fi
  done
}

# ── configuration ─────────────────────────────────────────────────────────────
PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-15432}"
PGDATABASE="${PGDATABASE:-batch_db}"
PGUSER="${PGUSER:-batch}"
export PGPASSWORD="${PGPASSWORD:-}"

BATCH_SCHEMA="${BATCH_SCHEMA:-batch}"
BATCH_ORCHESTRATOR_URL="${BATCH_ORCHESTRATOR_URL:-http://localhost:8082}"
BATCH_ORCHESTRATOR_TOKEN="${BATCH_ORCHESTRATOR_TOKEN:-}"

BATCH_HEAL_RETRY_DRY_RUN="${BATCH_HEAL_RETRY_DRY_RUN:-true}"
BATCH_HEAL_RETRY_TENANT="${BATCH_HEAL_RETRY_TENANT:-}"
BATCH_HEAL_RETRY_PARTITION_STATUS="${BATCH_HEAL_RETRY_PARTITION_STATUS:-FAILED}"
BATCH_HEAL_RETRY_BATCH_SIZE="${BATCH_HEAL_RETRY_BATCH_SIZE:-50}"
BATCH_HEAL_RETRY_SLEEP_MS="${BATCH_HEAL_RETRY_SLEEP_MS:-500}"

# ── helpers ────────────────────────────────────────────────────────────────────
log() { printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S')" "$*"; }

psql_query() {
  psql -h "${PGHOST}" -p "${PGPORT}" -U "${PGUSER}" -d "${PGDATABASE}" \
       -tA -c "$1" 2>&1
}

orchestrator_post() {
  local path="$1"
  local body="$2"

  local auth_header=()
  if [[ -n "${BATCH_ORCHESTRATOR_TOKEN}" ]]; then
    auth_header=(-H "Authorization: Bearer ${BATCH_ORCHESTRATOR_TOKEN}")
  fi

  curl -fsS -X POST \
    -H "Content-Type: application/json" \
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

build_where() {
  local where="WHERE partition_status = '${BATCH_HEAL_RETRY_PARTITION_STATUS}'"
  if [[ -n "${BATCH_HEAL_RETRY_TENANT}" ]]; then
    where="${where} AND tenant_id = '${BATCH_HEAL_RETRY_TENANT}'"
  fi
  printf '%s' "${where}"
}

require_tools

where="$(build_where)"

log "heal-retry-partitions: DRY_RUN=${BATCH_HEAL_RETRY_DRY_RUN}"
log "Filter: ${where}"

total="$(psql_query \
  "SELECT COUNT(*) FROM ${BATCH_SCHEMA}.job_partition ${where}" \
  2>/dev/null)" || total=0

if [[ "${total}" -eq 0 ]]; then
  log "No job_partition match filter — nothing to heal"
  exit 0
fi

log "Total to replay: ${total} (batch_size=${BATCH_HEAL_RETRY_BATCH_SIZE})"

replayed=0
failed=0
offset=0

while true; do
  batch="$(psql_query \
    "SELECT id, tenant_id
       FROM ${BATCH_SCHEMA}.job_partition
       ${where}
       ORDER BY id ASC
       LIMIT ${BATCH_HEAL_RETRY_BATCH_SIZE} OFFSET ${offset}" \
    2>/dev/null)" || {
      log "ERROR: cannot query job_partition at offset ${offset}"
      break
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

