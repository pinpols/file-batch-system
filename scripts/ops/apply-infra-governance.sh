#!/usr/bin/env bash
# 基础设施治理 profile 应用入口。默认只预览；可选择下发 Kafka topic 与 MinIO lifecycle。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PROFILE="local"
PROFILE_FILE=""
APPLY_KAFKA=false
APPLY_MINIO=false

usage() {
  cat <<'EOF'
Usage: bash scripts/ops/apply-infra-governance.sh [--profile local|test|benchmark|prod | --profile-file path] [--apply-kafka-topics] [--apply-minio-lifecycle]

默认只打印 profile 摘要，不修改任何外部系统。
内置 profile 位于 config/ops-governance/；外部环境可传 --profile-file 使用自己的 env 基线。
PostgreSQL / Valkey 参数属于启动配置，需通过对应部署系统重启或滚动发布生效。
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
    --apply-kafka-topics)
      APPLY_KAFKA=true
      shift
      ;;
    --apply-minio-lifecycle)
      APPLY_MINIO=true
      shift
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

cat <<EOF
Infrastructure governance profile: $PROFILE
Profile file: $PROFILE_FILE

PostgreSQL:
  max_wal_size=${POSTGRES_MAX_WAL_SIZE:-}
  checkpoint_timeout=${POSTGRES_CHECKPOINT_TIMEOUT:-}
  wal_compression=${POSTGRES_WAL_COMPRESSION:-}
  autovacuum_scale=${POSTGRES_AUTOVACUUM_VACUUM_SCALE_FACTOR:-}/${POSTGRES_AUTOVACUUM_ANALYZE_SCALE_FACTOR:-}

Kafka:
  broker_retention_hours=${KAFKA_LOG_RETENTION_HOURS:-}
  topic_retention_ms=${KAFKA_TOPIC_RETENTION_MS:-}
  dead_letter_retention_ms=${KAFKA_TOPIC_RETENTION_MS_DEAD_LETTER:-}
  replication_factor=${KAFKA_TOPIC_REPLICATION_FACTOR:-default}
  min_insync_replicas=${KAFKA_TOPIC_MIN_INSYNC_REPLICAS:-default}

Valkey/Redis:
  maxmemory=${VALKEY_MAXMEMORY:-}
  policy=${VALKEY_MAXMEMORY_POLICY:-}
  appendonly=${VALKEY_APPENDONLY:-}

MinIO:
  lifecycle_environment=${MINIO_LIFECYCLE_ENVIRONMENT:-}
  apply_on_init=${MINIO_LIFECYCLE_APPLY_ON_INIT:-}

File channels:
  governed_types=${BATCH_DISPATCH_CHANNEL_TYPES_GOVERNED:-}
  local_sandbox=${BATCH_DISPATCH_LOCAL_SANDBOX_ROOT:-}
  nas_sandbox=${BATCH_DISPATCH_NAS_SANDBOX_ROOT:-}
  sftp_strict_host_key_required=${BATCH_DISPATCH_SFTP_STRICT_HOST_KEY_REQUIRED:-}
  api_egress_allowlist_required=${BATCH_DISPATCH_API_EGRESS_ALLOWLIST_REQUIRED:-}
  email_tls_required=${BATCH_DISPATCH_EMAIL_TLS_REQUIRED:-}

State backends:
  storage_backend=${BATCH_STORAGE_BACKEND:-}
  report_outbox=${BATCH_WORKER_REPORT_OUTBOX_ENABLED:-}/${BATCH_WORKER_REPORT_OUTBOX_STORAGE:-}
  quota=${BATCH_QUOTA_RUNTIME_STORE:-}/${BATCH_QUOTA_REDIS_FAILURE_MODE:-}
  shedlock=${BATCH_SHEDLOCK_PROVIDER:-}/${BATCH_SHEDLOCK_REDIS_ENV:-}

Scheduler/topology:
  read_replica_enabled=${BATCH_CONSOLE_READ_REPLICA_ENABLED:-}
  business_routing_enabled=${BATCH_DATASOURCE_BUSINESS_ROUTING_ENABLED:-}
  misfire_retention_days=${BATCH_TRIGGER_MISFIRE_PENDING_RETENTION_DAYS:-}
  quartz_pool=${BATCH_TRIGGER_QUARTZ_DB_MAX_POOL_SIZE:-}

Observability/external:
  metrics_retention_days=${BATCH_OBSERVABILITY_RETENTION_DAYS:-}
  log_retention_days=${BATCH_OBSERVABILITY_LOG_RETENTION_DAYS:-}
  otel_sampling=${OTEL_SAMPLING_PROBABILITY:-}
  openlineage_enabled=${BATCH_OPENLINEAGE_ENABLED:-}
  egress_allowlist_required=${BATCH_EXTERNAL_ENDPOINT_EGRESS_ALLOWLIST_REQUIRED:-}
EOF

if [[ "$APPLY_KAFKA" == "true" ]]; then
  echo
  echo "Applying Kafka topic governance..."
  bash "$ROOT/scripts/data/init-kafka-topics.sh"
else
  echo
  echo "Kafka topic governance not applied. Add --apply-kafka-topics to update topic configs."
fi

if [[ "$APPLY_MINIO" == "true" ]]; then
  echo
  echo "Applying MinIO lifecycle governance..."
  "$ROOT/scripts/minio/apply-lifecycle.sh" --environment "$PROFILE" --apply
else
  echo "MinIO lifecycle governance not applied. Add --apply-minio-lifecycle to import lifecycle rules."
fi

echo "PostgreSQL and Valkey profile values are restart-required runtime configuration."
