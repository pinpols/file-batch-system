#!/usr/bin/env bash
# 用途：手工触发补偿（Console API）。
# 用法：在仓库根目录执行 bash scripts/ops/trigger-compensation.sh；参数与前置环境见下方说明及所属 README。
# =========================================================
# trigger-compensation.sh - 手工触发补偿（Console API）
#
# 说明：
# 1) 直接调用 console-api：POST /api/console/jobs/compensate
# 2) 由你提供补偿请求 JSON（BATCH_COMPENSATION_JSON），避免脚本复杂的字段拼装差异
#
# 使用方法：
#   # dry-run（默认）
#   bash scripts/ops/trigger-compensation.sh
#
#   # 实际执行
#   BATCH_CONSOLE_URL=http://localhost:18080 \
#   BATCH_CONSOLE_TOKEN="$JWT" BATCH_CONSOLE_CSRF_TOKEN="$CSRF" \
#   BATCH_COMPENSATION_DRY_RUN=false \
#   BATCH_COMPENSATION_IDEMPOTENCY_KEY=cmp-001 \
#   BATCH_COMPENSATION_JSON='{"tenantId":"t1","jobCode":"IMPORT_JOB","bizDate":"2026-03-27","compensationType":"JOB","targetId":123,"reason":"manual compensate","operatorId":"ops-user"}' \
#   bash scripts/ops/trigger-compensation.sh
#
# 注意：
# - compensate 接口存在字段校验（例如 jobCode/bizDate/compensationType/targetId），JSON 需满足校验要求。
# =========================================================

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=env.sh
source "$ROOT/scripts/ops/env.sh"

require_tools() {
  if ! command -v curl >/dev/null 2>&1; then
    echo "ERROR: curl not found" >&2
    exit 1
  fi
}

log() { printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S')" "$*"; }

require_tools

BATCH_CONSOLE_TOKEN="${BATCH_CONSOLE_TOKEN:-}"
BATCH_CONSOLE_CSRF_TOKEN="${BATCH_CONSOLE_CSRF_TOKEN:-}"
BATCH_COMPENSATION_DRY_RUN="${BATCH_COMPENSATION_DRY_RUN:-true}"
BATCH_COMPENSATION_IDEMPOTENCY_KEY="${BATCH_COMPENSATION_IDEMPOTENCY_KEY:-cmp-$(date +%s)}"
BATCH_COMPENSATION_JSON="${BATCH_COMPENSATION_JSON:-}"

case "$BATCH_COMPENSATION_DRY_RUN" in
  true|false) ;;
  *) echo "ERROR: BATCH_COMPENSATION_DRY_RUN must be true or false" >&2; exit 2 ;;
esac

if [[ -z "${BATCH_COMPENSATION_JSON}" ]]; then
  log "ERROR: 需要提供 BATCH_COMPENSATION_JSON"
  exit 1
fi

path="/api/console/jobs/compensate"

if [[ "${BATCH_COMPENSATION_DRY_RUN}" == "true" ]]; then
  log "DRY-RUN: 将调用 ${BATCH_CONSOLE_URL%/}${path}"
  log "Idempotency-Key=${BATCH_COMPENSATION_IDEMPOTENCY_KEY}"
  log "Payload=${BATCH_COMPENSATION_JSON}"
  exit 0
fi

# ADR-030 §D7：同一次认证会话的 JWT cookie 与 CSRF double-submit 一起发送。
if [[ -z "$BATCH_CONSOLE_TOKEN" || -z "$BATCH_CONSOLE_CSRF_TOKEN" ]]; then
  echo "ERROR: BATCH_CONSOLE_TOKEN and BATCH_CONSOLE_CSRF_TOKEN are required for execution" >&2
  exit 2
fi
console_cookies="batch_console_token=${BATCH_CONSOLE_TOKEN}; XSRF-TOKEN=${BATCH_CONSOLE_CSRF_TOKEN}"

curl -fsS -X POST \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ${BATCH_COMPENSATION_IDEMPOTENCY_KEY}" \
  --cookie "$console_cookies" \
  -H "X-XSRF-TOKEN: ${BATCH_CONSOLE_CSRF_TOKEN}" \
  -d "${BATCH_COMPENSATION_JSON}" \
  "${BATCH_CONSOLE_URL%/}${path}"

log "补偿触发已提交"
