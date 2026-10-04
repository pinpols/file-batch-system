#!/usr/bin/env bash
# 运行时治理 profile 只读检查。仅校验配置边界，不连接外部系统、不清理数据。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PROFILE="${BATCH_INFRA_GOVERNANCE_PROFILE:-local}"
PROFILE_FILE=""

usage() {
  cat <<'EOF'
Usage: bash scripts/ops/inspect-runtime-governance.sh [--profile local|test|benchmark|prod | --profile-file path]

检查 config/ops-governance/<profile>.env 是否覆盖文件通道、状态后端、调度状态、拓扑、
观测和外部端点治理项。外部环境可传 --profile-file 使用自己的 env 基线。
脚本只读，不连接生产依赖，不执行清理。
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --profile)
      PROFILE="${2:-}"
      [[ -n "$PROFILE" ]] || { echo "--profile requires a value" >&2; exit 2; }
      shift 2
      ;;
    --profile-file)
      PROFILE_FILE="${2:-}"
      [[ -n "$PROFILE_FILE" ]] || { echo "--profile-file requires a value" >&2; exit 2; }
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ -z "$PROFILE_FILE" ]]; then
  case "$PROFILE" in
    local|test|benchmark|prod) ;;
    *)
      echo "Unsupported profile: $PROFILE" >&2
      exit 2
      ;;
  esac
  PROFILE_FILE="$ROOT/config/ops-governance/${PROFILE}.env"
else
  [[ "$PROFILE_FILE" = /* ]] || PROFILE_FILE="$PWD/$PROFILE_FILE"
  PROFILE="$(basename "$PROFILE_FILE" .env)"
fi

[[ -r "$PROFILE_FILE" ]] || { echo "Profile file not found: $PROFILE_FILE" >&2; exit 2; }

set -a
# shellcheck disable=SC1090
source "$PROFILE_FILE"
set +a

status=0

fail() {
  status=1
  printf 'FAIL: %s\n' "$1"
}

warn() {
  printf 'WARN: %s\n' "$1"
}

ok() {
  printf 'OK: %s\n' "$1"
}

require_non_empty() {
  local name="$1"
  local value="${!name:-}"
  if [[ -z "$value" ]]; then
    fail "${name} is empty"
  else
    ok "${name}=${value}"
  fi
}

require_boolean_true_for_prod() {
  local name="$1"
  local value="${!name:-}"
  if [[ "$PROFILE" == "prod" && "$value" != "true" ]]; then
    fail "${name} must be true in prod profile"
  else
    ok "${name}=${value:-<empty>}"
  fi
}

require_channel() {
  local channel="$1"
  local value="${BATCH_DISPATCH_CHANNEL_TYPES_GOVERNED:-}"
  if [[ ",${value}," != *",${channel},"* ]]; then
    fail "BATCH_DISPATCH_CHANNEL_TYPES_GOVERNED missing ${channel}"
  else
    ok "channel ${channel} governed"
  fi
}

printf 'Runtime governance profile: %s\nProfile file: %s\n\n' "$PROFILE" "$PROFILE_FILE"

printf 'File channel governance:\n'
require_non_empty BATCH_DISPATCH_CHANNEL_TYPES_GOVERNED
for channel in LOCAL NAS SFTP OSS API API_PUSH EMAIL; do
  require_channel "$channel"
done
require_non_empty BATCH_DISPATCH_LOCAL_SANDBOX_ROOT
require_non_empty BATCH_DISPATCH_NAS_SANDBOX_ROOT
require_non_empty BATCH_DISPATCH_NAS_COPY_TIMEOUT_SECONDS
require_non_empty BATCH_DISPATCH_OSS_MAX_INLINE_MIB
require_non_empty BATCH_DISPATCH_PROBE_CONNECT_TIMEOUT_MS
require_non_empty BATCH_DISPATCH_PROBE_READ_TIMEOUT_MS
require_boolean_true_for_prod BATCH_DISPATCH_SFTP_STRICT_HOST_KEY_REQUIRED
require_boolean_true_for_prod BATCH_DISPATCH_API_EGRESS_ALLOWLIST_REQUIRED
require_boolean_true_for_prod BATCH_DISPATCH_EMAIL_TLS_REQUIRED

printf '\nObject/file storage governance:\n'
require_non_empty BATCH_STORAGE_BACKEND
if [[ "${BATCH_STORAGE_BACKEND}" == "filesystem" ]]; then
  require_non_empty BATCH_STORAGE_FILESYSTEM_ROOT
else
  ok "BATCH_STORAGE_BACKEND=${BATCH_STORAGE_BACKEND}"
fi

printf '\nWorker report outbox governance:\n'
require_non_empty BATCH_WORKER_REPORT_OUTBOX_ENABLED
require_non_empty BATCH_WORKER_REPORT_OUTBOX_STORAGE
require_non_empty BATCH_WORKER_REPORT_OUTBOX_POLL_INTERVAL_MS
require_non_empty BATCH_WORKER_REPORT_OUTBOX_POLL_BATCH_SIZE
require_non_empty BATCH_WORKER_REPORT_OUTBOX_PUBLISHING_STALE_MS
if [[ "${BATCH_WORKER_REPORT_OUTBOX_STORAGE}" == "SQLITE" ]]; then
  require_non_empty BATCH_WORKER_REPORT_OUTBOX_SQLITE_PATH
  if [[ "$PROFILE" == "prod" ]]; then
    warn "prod profile uses SQLITE report outbox; confirm local disk backup and failover boundary"
  fi
fi

printf '\nState backend and topology governance:\n'
require_non_empty BATCH_QUOTA_RUNTIME_STORE
require_non_empty BATCH_QUOTA_REDIS_FAILURE_MODE
require_non_empty BATCH_SHEDLOCK_PROVIDER
require_non_empty BATCH_SHEDLOCK_REDIS_ENV
require_non_empty BATCH_CONSOLE_READ_REPLICA_ENABLED
require_non_empty BATCH_DATASOURCE_BUSINESS_ROUTING_ENABLED
if [[ "$PROFILE" == "prod" && "${BATCH_QUOTA_REDIS_FAILURE_MODE}" != "FAIL_CLOSED" ]]; then
  fail "BATCH_QUOTA_REDIS_FAILURE_MODE must be FAIL_CLOSED in prod"
fi

printf '\nScheduler and Quartz governance:\n'
require_non_empty BATCH_TRIGGER_MISFIRE_PENDING_RETENTION_DAYS
require_non_empty BATCH_TRIGGER_QUARTZ_DB_MAX_POOL_SIZE
require_non_empty BATCH_TRIGGER_OUTBOX_MAX_PUBLISH_ATTEMPTS

printf '\nObservability and external endpoint governance:\n'
require_non_empty BATCH_OBSERVABILITY_RETENTION_DAYS
require_non_empty BATCH_OBSERVABILITY_LOG_RETENTION_DAYS
require_non_empty OTEL_SAMPLING_PROBABILITY
require_non_empty BATCH_OPENLINEAGE_ENABLED
require_boolean_true_for_prod BATCH_EXTERNAL_ENDPOINT_EGRESS_ALLOWLIST_REQUIRED

if [[ "$status" -ne 0 ]]; then
  printf '\nRuntime governance: FAILED\n'
  exit "$status"
fi

printf '\nRuntime governance: PASSED\n'
