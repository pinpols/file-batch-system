#!/usr/bin/env bash
# =========================================================
# manage-trigger.sh - Trigger 管理 API 运维入口
#
# 默认只预览，不直接改变调度状态。生产执行必须显式设置
# BATCH_TRIGGER_MANAGEMENT_DRY_RUN=false，并提供非默认 BATCH_INTERNAL_SECRET。
# 不允许通过 SQL 直接修改 Quartz 表或 job_definition。
#
# 使用示例：
#   bash scripts/ops/manage-trigger.sh status
#   BATCH_TRIGGER_MANAGEMENT_DRY_RUN=false \
#   BATCH_INTERNAL_SECRET="$SECRET" \
#     bash scripts/ops/manage-trigger.sh pause-tenant t1
#   BATCH_TRIGGER_MANAGEMENT_DRY_RUN=false \
#   BATCH_INTERNAL_SECRET="$SECRET" \
#     bash scripts/ops/manage-trigger.sh pause-job t1 DAILY_IMPORT
# =========================================================

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=env.sh
source "$ROOT/scripts/ops/env.sh"

TRIGGER_MANAGEMENT_DRY_RUN="${BATCH_TRIGGER_MANAGEMENT_DRY_RUN:-true}"
TRIGGER_URL="${BATCH_TRIGGER_URL:-${TRIGGER_BASE_URL:-$TRIGGER_BASE}}"
INTERNAL_SECRET="${BATCH_INTERNAL_SECRET:-}"

usage() {
  cat >&2 <<'EOF'
Usage:
  manage-trigger.sh status
  manage-trigger.sh register TENANT_ID JOB_CODE
  manage-trigger.sh unregister TENANT_ID JOB_CODE
  manage-trigger.sh pause-job TENANT_ID JOB_CODE
  manage-trigger.sh resume-job TENANT_ID JOB_CODE
  manage-trigger.sh pause-all
  manage-trigger.sh resume-all
  manage-trigger.sh pause-tenant TENANT_ID
  manage-trigger.sh resume-tenant TENANT_ID
  manage-trigger.sh drain-status
  manage-trigger.sh drain-enable
  manage-trigger.sh drain-disable
EOF
  exit 2
}

log() { printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S')" "$*"; }

require_tools() {
  command -v curl >/dev/null 2>&1 || {
    log "ERROR: curl not found"
    exit 1
  }
}

require_secret() {
  if [[ -z "$INTERNAL_SECRET" || "$INTERNAL_SECRET" == "internal-secret" ]]; then
    log "ERROR: BATCH_INTERNAL_SECRET must be a non-default secret"
    exit 1
  fi
}

request() {
  local method="$1"
  local path="$2"
  shift 2
  curl --fail-with-body --silent --show-error --max-time "${BATCH_TRIGGER_MANAGEMENT_TIMEOUT_SECONDS:-15}" \
    -X "$method" \
    -H "X-Internal-Secret: $INTERNAL_SECRET" \
    "$TRIGGER_URL$path" "$@"
}

main() {
  require_tools
  [[ $# -ge 1 ]] || usage

  local action="$1"
  shift
  local method=GET
  local path

  case "$action" in
    status) path="/api/triggers/management/scheduler-status" ;;
    register|unregister|pause-job|resume-job)
      [[ $# -eq 2 ]] || usage
      method=POST
      local tenant_id="$1" job_code="$2"
      case "$action" in
        register) path="/api/triggers/management/register?tenantId=$tenant_id&jobCode=$job_code" ;;
        unregister) path="/api/triggers/management/unregister?tenantId=$tenant_id&jobCode=$job_code" ;;
        pause-job) path="/api/triggers/management/pause?tenantId=$tenant_id&jobCode=$job_code" ;;
        resume-job) path="/api/triggers/management/resume?tenantId=$tenant_id&jobCode=$job_code" ;;
      esac
      ;;
    pause-all|resume-all|drain-enable|drain-disable)
      [[ $# -eq 0 ]] || usage
      method=POST
      case "$action" in
        pause-all) path="/api/triggers/management/pause-all" ;;
        resume-all) path="/api/triggers/management/resume-all" ;;
        drain-enable) path="/api/triggers/management/drain/enable" ;;
        drain-disable) path="/api/triggers/management/drain/disable" ;;
      esac
      ;;
    pause-tenant|resume-tenant)
      [[ $# -eq 1 ]] || usage
      method=POST
      local tenant_id="$1"
      if [[ "$action" == "pause-tenant" ]]; then
        path="/api/triggers/management/pause-tenant?tenantId=$tenant_id"
      else
        path="/api/triggers/management/resume-tenant?tenantId=$tenant_id"
      fi
      ;;
    drain-status) path="/api/triggers/management/drain/status" ;;
    *) usage ;;
  esac

  require_secret
  if [[ "$TRIGGER_MANAGEMENT_DRY_RUN" == "true" ]]; then
    log "DRY-RUN: $method $TRIGGER_URL$path"
    log "Set BATCH_TRIGGER_MANAGEMENT_DRY_RUN=false to execute."
    return 0
  fi

  request "$method" "$path"
}

main "$@"
