#!/usr/bin/env bash
# =========================================================
# heal-drain-timeout.sh - 自愈：对排空超时的 Worker 执行 force-offline
# 说明：
# 1) 找出 DRAINING 且超时的 worker。
# 2) 调用控制台 force-offline API 交给 Orchestrator 接管。
# =========================================================
# 使用方法（dry-run 模式默认开启，需显式关闭）：
#   # 仅预览，不实际执行（默认）
#   bash scripts/ops/heal-drain-timeout.sh
#
#   # 真正执行 force-offline
#   BATCH_HEAL_DRY_RUN=false BATCH_CONSOLE_URL=http://localhost:8080 \
#     PGHOST=localhost PGUSER=batch PGPASSWORD=secret \
#     bash scripts/ops/heal-drain-timeout.sh
#
# 所有变量：
#   BATCH_CONSOLE_URL       控制台 base URL（含协议和端口）
#   BATCH_CONSOLE_TOKEN     Bearer token（若控制台启用认证）
#   PGHOST/PGPORT/...       PostgreSQL 连接信息
#   BATCH_HEAL_DRY_RUN      true（默认）= 仅打印操作，false = 实际执行

set -euo pipefail

# ── configuration ─────────────────────────────────────────────────────────────
PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-15432}"
PGDATABASE="${PGDATABASE:-batch_db}"
PGUSER="${PGUSER:-batch}"
export PGPASSWORD="${PGPASSWORD:-}"

BATCH_SCHEMA="${BATCH_SCHEMA:-batch}"
BATCH_CONSOLE_URL="${BATCH_CONSOLE_URL:-http://localhost:8080}"
BATCH_CONSOLE_TOKEN="${BATCH_CONSOLE_TOKEN:-}"
BATCH_HEAL_DRY_RUN="${BATCH_HEAL_DRY_RUN:-true}"

# ── helpers ───────────────────────────────────────────────────────────────────
log()  { printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S')" "$*"; }
dry()  { log "DRY-RUN: $*"; }

psql_query() {
  psql -h "${PGHOST}" -p "${PGPORT}" -U "${PGUSER}" -d "${PGDATABASE}" \
       -tA -c "$1" 2>&1
}

console_post() {
  local path="$1"
  local body="${2:-{}}"
  # ADR-030 §D7: console 端 HttpOnly cookie 是唯一鉴权方式；脚本通过 --cookie 把
  # JWT 写到与浏览器同名的 batch_console_token cookie，复用 filter 的 cookie 解析路径。
  # 不再注入 Authorization header（filter 已无 header fallback，2026-05-15 commit 起）。
  local cookie_arg=()
  if [[ -n "${BATCH_CONSOLE_TOKEN}" ]]; then
    cookie_arg=(--cookie "batch_console_token=${BATCH_CONSOLE_TOKEN}")
  fi
  curl -fsS -X POST \
    -H "Content-Type: application/json" \
    "${cookie_arg[@]}" \
    -d "${body}" \
    "${BATCH_CONSOLE_URL%/}${path}"
}

require_tools() {
  for tool in psql curl; do
    if ! command -v "${tool}" >/dev/null 2>&1; then
      log "ERROR: ${tool} not found"
      exit 1
    fi
  done
}

# ── main ──────────────────────────────────────────────────────────────────────
require_tools

log "heal-drain-timeout: BATCH_HEAL_DRY_RUN=${BATCH_HEAL_DRY_RUN}"
log "Querying drain-timeout workers from ${PGHOST}:${PGPORT}/${PGDATABASE}..."

# Fetch: worker_code|tenant_id|drain_deadline_at
overdue_workers="$(psql_query \
  "SELECT worker_code || '|' || tenant_id || '|' || drain_deadline_at
     FROM ${BATCH_SCHEMA}.worker_registry
    WHERE worker_status = 'DRAINING'
      AND drain_deadline_at IS NOT NULL
      AND drain_deadline_at < NOW()
    ORDER BY drain_deadline_at ASC" 2>/dev/null)" || {
  log "ERROR: cannot query worker_registry"
  exit 1
}

if [[ -z "${overdue_workers}" ]]; then
  log "No drain-timeout workers found — nothing to heal"
  exit 0
fi

healed=0
failed=0

while IFS='|' read -r worker_code tenant_id drain_deadline; do
  [[ -z "${worker_code}" ]] && continue
  log "Processing: worker_code=${worker_code} tenant=${tenant_id} deadline=${drain_deadline}"

  if [[ "${BATCH_HEAL_DRY_RUN}" == "true" ]]; then
    dry "Would POST ${BATCH_CONSOLE_URL}/api/console/workers/${worker_code}/force-offline"
    dry "  body: {\"tenantId\":\"${tenant_id}\"}"
    healed=$((healed + 1))
    continue
  fi

  local_response=""
  if local_response="$(console_post \
        "/api/console/workers/${worker_code}/force-offline" \
        "{\"tenantId\":\"${tenant_id}\"}" 2>&1)"; then
    log "  force-offline OK: ${local_response}"
    healed=$((healed + 1))
  else
    log "  force-offline FAILED: ${local_response}"
    failed=$((failed + 1))
  fi
done <<<"${overdue_workers}"

log ""
if [[ "${BATCH_HEAL_DRY_RUN}" == "true" ]]; then
  log "DRY-RUN complete: ${healed} worker(s) would be force-offlined"
  log "Set BATCH_HEAL_DRY_RUN=false to execute."
elif [[ "${failed}" -gt 0 ]]; then
  log "Heal complete: ${healed} succeeded, ${failed} failed"
  exit 1
else
  log "Heal complete: ${healed} worker(s) force-offlined successfully"
fi
