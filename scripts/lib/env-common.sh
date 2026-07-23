#!/usr/bin/env bash
# 公共本地环境入口。只能被 source,不要直接执行。

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  echo "scripts/lib/env-common.sh must be sourced, not executed" >&2
  exit 2
fi

if [[ -z "${BATCH_ENV_COMMON_ROOT:-}" ]]; then
  BATCH_ENV_COMMON_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
fi

batch_source_env_file() {
  local env_file="${1:-${COMPOSE_ENV_FILE:-$BATCH_ENV_COMMON_ROOT/.env.local}}"
  if [[ -f "$env_file" ]]; then
    set -a
    # shellcheck disable=SC1090
    source "$env_file"
    set +a
  fi
}

batch_load_default_env() {
  if [[ -n "${BATCH_ENV_LOADED:-}" ]]; then
    return 0
  fi

  COMPOSE_ENV_FILE="${COMPOSE_ENV_FILE:-$BATCH_ENV_COMMON_ROOT/.env.local}"
  if [[ "$COMPOSE_ENV_FILE" != "$BATCH_ENV_COMMON_ROOT/.env" && -f "$BATCH_ENV_COMMON_ROOT/.env" ]]; then
    batch_source_env_file "$BATCH_ENV_COMMON_ROOT/.env"
  fi
  batch_source_env_file "$COMPOSE_ENV_FILE"

  export BATCH_TIMEZONE_DEFAULT_ZONE="${BATCH_TIMEZONE_DEFAULT_ZONE:-Asia/Shanghai}"
  export TZ="${TZ:-$BATCH_TIMEZONE_DEFAULT_ZONE}"
  export BATCH_LOCALE="${BATCH_LOCALE:-C.UTF-8}"
  export LANG="${LANG:-$BATCH_LOCALE}"
  export LC_ALL="${LC_ALL:-$BATCH_LOCALE}"

  export POSTGRES_PORT="${POSTGRES_PORT:-15432}"
  export POSTGRES_DB="${POSTGRES_DB:-batch_platform}"
  export POSTGRES_USER="${POSTGRES_USER:-batch_user}"
  export POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-batch_pass_123}"
  export BUSINESS_DB_NAME="${BUSINESS_DB_NAME:-batch_business}"
  export PG_CONTAINER="${PG_CONTAINER:-batch-postgres-primary}"
  export BATCH_DEFAULT_TENANT_ID="${BATCH_DEFAULT_TENANT_ID:-default-tenant}"
  export BATCH_DEV_FIXTURE_TENANTS="${BATCH_DEV_FIXTURE_TENANTS:-ta,tb,tc,tx,default-tenant}"

  export PGHOST="${PGHOST:-${PG_PRIMARY_HOST:-localhost}}"
  export PGPORT="${PGPORT:-${PG_PRIMARY_PORT:-$POSTGRES_PORT}}"
  export PGUSER="${PGUSER:-$POSTGRES_USER}"
  export PGPASSWORD="${PGPASSWORD:-$POSTGRES_PASSWORD}"
  export PGDATABASE="${PGDATABASE:-$POSTGRES_DB}"
  export PLATFORM_DB="${PLATFORM_DB:-$POSTGRES_DB}"
  export BUSINESS_DB="${BUSINESS_DB:-$BUSINESS_DB_NAME}"

  export CONSOLE_API_PORT="${CONSOLE_API_PORT:-${CONSOLE_PORT:-18080}}"
  export TRIGGER_PORT="${TRIGGER_PORT:-18081}"
  export ORCHESTRATOR_PORT="${ORCHESTRATOR_PORT:-18082}"
  export WORKER_IMPORT_PORT="${WORKER_IMPORT_PORT:-18083}"
  export WORKER_EXPORT_PORT="${WORKER_EXPORT_PORT:-18084}"
  export WORKER_DISPATCH_PORT="${WORKER_DISPATCH_PORT:-18085}"
  export WORKER_PROCESS_PORT="${WORKER_PROCESS_PORT:-18086}"
  export WORKER_ATOMIC_PORT="${WORKER_ATOMIC_PORT:-18087}"

  export CONSOLE_BASE="${CONSOLE_BASE:-http://localhost:${CONSOLE_API_PORT}}"
  export TRIGGER_BASE="${TRIGGER_BASE:-http://localhost:${TRIGGER_PORT}}"
  export ORCH_BASE="${ORCH_BASE:-http://localhost:${ORCHESTRATOR_PORT}}"
  export CONSOLE_BASE_URL="${CONSOLE_BASE_URL:-$CONSOLE_BASE}"
  export TRIGGER_BASE_URL="${TRIGGER_BASE_URL:-$TRIGGER_BASE}"
  export ORCHESTRATOR_BASE_URL="${ORCHESTRATOR_BASE_URL:-$ORCH_BASE}"

  export BATCH_ORCHESTRATOR_URL="${BATCH_ORCHESTRATOR_URL:-$ORCH_BASE}"
  export BATCH_CONSOLE_URL="${BATCH_CONSOLE_URL:-$CONSOLE_BASE}"

  export BATCH_INTERNAL_SECRET="${BATCH_INTERNAL_SECRET:-internal-secret}"
  export INTERNAL_SECRET="${INTERNAL_SECRET:-$BATCH_INTERNAL_SECRET}"

  export MINIO_API_PORT="${MINIO_API_PORT:-19000}"
  export MINIO_BUCKET="${MINIO_BUCKET:-${BATCH_S3_BUCKET:-batch-dev}}"
  export BATCH_S3_BUCKET="${BATCH_S3_BUCKET:-$MINIO_BUCKET}"
  export BATCH_S3_ENDPOINT="${BATCH_S3_ENDPOINT:-http://localhost:${MINIO_API_PORT}}"
  export BATCH_S3_ACCESS_KEY="${BATCH_S3_ACCESS_KEY:-${MINIO_ROOT_USER:-minioadmin}}"
  export BATCH_S3_SECRET_KEY="${BATCH_S3_SECRET_KEY:-${MINIO_ROOT_PASSWORD:-minioadmin123}}"

  export KAFKA_HOST_PORT="${KAFKA_HOST_PORT:-19092}"
  export KAFKA_HOST_BOOTSTRAP="${KAFKA_HOST_BOOTSTRAP:-localhost:${KAFKA_HOST_PORT}}"
  export KAFKA_CONTAINER_BOOTSTRAP="${KAFKA_CONTAINER_BOOTSTRAP:-kafka:29092}"

  export BATCH_ENV_LOADED=1
}

batch_require_internal_secret() {
  if [[ -z "${BATCH_INTERNAL_SECRET:-}" || "$BATCH_INTERNAL_SECRET" == "internal-secret" ]]; then
    echo "BATCH_INTERNAL_SECRET is required and must not be the default internal-secret" >&2
    return 2
  fi
}

# 裸 JVM 本地联调使用宿主机端口，而 .env.local 同时还会被 Docker Compose
# 读取，其中部分地址使用容器 DNS。调用方在加载默认 env 后显式调用本函数，
# 将通用 PostgreSQL 配置映射到各应用的强类型配置项。
batch_configure_local_jvm_database_env() {
  export BATCH_PLATFORM_DB_URL="${BATCH_PLATFORM_DB_URL:-jdbc:postgresql://localhost:${POSTGRES_PORT}/batch_platform?reWriteBatchedInserts=true}"
  export BATCH_PLATFORM_DB_USERNAME="${BATCH_PLATFORM_DB_USERNAME:-$POSTGRES_USER}"
  export BATCH_PLATFORM_DB_PASSWORD="${BATCH_PLATFORM_DB_PASSWORD:-$POSTGRES_PASSWORD}"

  export BATCH_BUSINESS_DB_URL="${BATCH_BUSINESS_DB_URL:-jdbc:postgresql://localhost:${POSTGRES_PORT}/${BUSINESS_DB_NAME}?reWriteBatchedInserts=true}"
  export BATCH_BUSINESS_DB_USERNAME="${BATCH_BUSINESS_DB_USERNAME:-$POSTGRES_USER}"
  export BATCH_BUSINESS_DB_PASSWORD="${BATCH_BUSINESS_DB_PASSWORD:-$POSTGRES_PASSWORD}"

  export BATCH_CONSOLE_PRIMARY_URL="${BATCH_CONSOLE_PRIMARY_URL:-$BATCH_PLATFORM_DB_URL}"
  export BATCH_CONSOLE_PRIMARY_USER="${BATCH_CONSOLE_PRIMARY_USER:-$BATCH_PLATFORM_DB_USERNAME}"
  export BATCH_CONSOLE_PRIMARY_PASSWORD="${BATCH_CONSOLE_PRIMARY_PASSWORD:-$BATCH_PLATFORM_DB_PASSWORD}"
  export BATCH_CONSOLE_REPLICA_USER="${BATCH_CONSOLE_REPLICA_USER:-$BATCH_PLATFORM_DB_USERNAME}"
  export BATCH_CONSOLE_REPLICA_PASSWORD="${BATCH_CONSOLE_REPLICA_PASSWORD:-$BATCH_PLATFORM_DB_PASSWORD}"

  case "${BATCH_CONSOLE_REPLICA_URL:-}" in
    ""|jdbc:postgresql://postgres-replica:*)
      export BATCH_CONSOLE_REPLICA_URL="jdbc:postgresql://localhost:${POSTGRES_REPLICA_PORT:-15433}/batch_platform?reWriteBatchedInserts=true"
      ;;
  esac
}

batch_load_default_env
