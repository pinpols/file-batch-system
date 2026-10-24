#!/usr/bin/env bash
# 用途：自愈：对排空超时的 Worker 执行 force-offline。
# 用法：在仓库根目录执行 bash scripts/ops/heal-drain-timeout.sh；参数与前置环境见下方说明及所属 README。
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
#   BATCH_HEAL_DRY_RUN=false BATCH_CONSOLE_URL=http://localhost:18080 \
#     BATCH_CONSOLE_TOKEN="$JWT" BATCH_CONSOLE_CSRF_TOKEN="$CSRF" \
#     bash scripts/ops/heal-drain-timeout.sh
#
# 所有变量：
#   BATCH_CONSOLE_URL       控制台 base URL（含协议和端口）
#   BATCH_CONSOLE_TOKEN     当前认证会话的 JWT cookie
#   BATCH_CONSOLE_CSRF_TOKEN 同一会话的 XSRF-TOKEN cookie 值
#   PGHOST/PGPORT/...       PostgreSQL 连接信息
#   BATCH_HEAL_DRY_RUN      true（默认）= 仅打印操作，false = 实际执行

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=env.sh
source "$ROOT/scripts/ops/env.sh"

# ── 配置 ─────────────────────────────────────────────────────────────
BATCH_CONSOLE_TOKEN="${BATCH_CONSOLE_TOKEN:-}"
BATCH_CONSOLE_CSRF_TOKEN="${BATCH_CONSOLE_CSRF_TOKEN:-}"
BATCH_HEAL_DRY_RUN="${BATCH_HEAL_DRY_RUN:-true}"

# ── 辅助函数 ───────────────────────────────────────────────────────────────────
log()  { printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S')" "$*"; }
dry()  { log "DRY-RUN: $*"; }

psql_file() {
  psql -h "${PGHOST}" -p "${PGPORT}" -U "${PGUSER}" -d "${PGDATABASE}" \
       -X -A -t -v ON_ERROR_STOP=1 -v schema="$BATCH_SCHEMA" "$@"
}

console_post() {
  local path="$1"
  local body="${2:-}"
  [[ -n "$body" ]] || body='{}'
  local console_cookies="batch_console_token=${BATCH_CONSOLE_TOKEN}; XSRF-TOKEN=${BATCH_CONSOLE_CSRF_TOKEN}"
  curl -fsS -X POST \
    -H "Content-Type: application/json" \
    --cookie "$console_cookies" \
    -H "X-XSRF-TOKEN: ${BATCH_CONSOLE_CSRF_TOKEN}" \
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

# ── 主流程 ──────────────────────────────────────────────────────────────────────
require_tools

case "$BATCH_HEAL_DRY_RUN" in
  true) ;;
  false)
    if [[ -z "$BATCH_CONSOLE_TOKEN" || -z "$BATCH_CONSOLE_CSRF_TOKEN" ]]; then
      echo "ERROR: BATCH_CONSOLE_TOKEN and BATCH_CONSOLE_CSRF_TOKEN are required for execution" >&2
      exit 2
    fi
    ;;
  *) echo "ERROR: BATCH_HEAL_DRY_RUN must be true or false" >&2; exit 2 ;;
esac

log "heal-drain-timeout: BATCH_HEAL_DRY_RUN=${BATCH_HEAL_DRY_RUN}"
log "Querying drain-timeout workers from ${PGHOST}:${PGPORT}/${PGDATABASE}..."

# 拉取:worker_code|tenant_id|drain_deadline_at
overdue_workers="$(psql_file -f "$OPS_SQL_DIR/heal-drain-timeout-workers.sql" 2>/dev/null)" || {
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
