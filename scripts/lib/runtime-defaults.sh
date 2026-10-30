#!/bin/sh
# 用途：宿主机脚本与精简 /bin/sh 初始化容器共用的运行时默认值。
# 用法：由调用脚本 source scripts/lib/runtime-defaults.sh 后使用公共变量或函数。
# 宿主机脚本与精简 /bin/sh 初始化容器共用的运行时默认值。
# 本文件仅定义常量，由调用方决定参数是否必需。
# 常量供显式加载本文件的脚本使用。
# shellcheck disable=SC2034

BATCH_DEFAULT_POSTGRES_PORT=15432
BATCH_DEFAULT_POSTGRES_CONTAINER_PORT=5432
BATCH_DEFAULT_POSTGRES_DATABASE=batch_platform
BATCH_DEFAULT_BUSINESS_DATABASE=batch_business
BATCH_DEFAULT_POSTGRES_USERNAME=batch_user
BATCH_DEFAULT_POSTGRES_PASSWORD=batch_pass_123
BATCH_DEFAULT_POSTGRES_CONTAINER=batch-postgres-primary
BATCH_DEFAULT_POSTGRES_SERVICE_HOST=postgres-primary

BATCH_DEFAULT_KAFKA_HOST_PORT=19092
BATCH_DEFAULT_KAFKA_CONTAINER_BOOTSTRAP=kafka:29092
BATCH_DEFAULT_KAFKA_CONTAINER_BIN_DIR=/opt/kafka/bin
BATCH_DEFAULT_KAFKA_CONTAINER=batch-kafka
BATCH_DEFAULT_KAFKA_SERVICE_HOST=kafka
BATCH_DEFAULT_MOCKSERVER_HOST_PORT=11080

batch_validate_kafka_namespace() {
  namespace_value="$1"
  [ -n "$namespace_value" ] || return 0
  case "$namespace_value" in
    [a-z0-9]*) ;;
    *) echo "BATCH_KAFKA_NAMESPACE must start with a lowercase letter or digit" >&2; return 2 ;;
  esac
  case "$namespace_value" in
    *[!a-z0-9_-]*) echo "BATCH_KAFKA_NAMESPACE contains unsupported characters" >&2; return 2 ;;
  esac
  if [ "${#namespace_value}" -gt 48 ]; then
    echo "BATCH_KAFKA_NAMESPACE must be at most 48 characters" >&2
    return 2
  fi
}

batch_namespace_kafka_name() {
  name_value="$1"
  namespace_value="$2"
  if [ -z "$namespace_value" ]; then
    printf '%s\n' "$name_value"
    return 0
  fi
  case "$name_value" in
    "$namespace_value".*) printf '%s\n' "$name_value" ;;
    *) printf '%s.%s\n' "$namespace_value" "$name_value" ;;
  esac
}

BATCH_DEFAULT_MINIO_API_PORT=19000
BATCH_DEFAULT_MINIO_CONTAINER_ENDPOINT=http://minio:9000
BATCH_DEFAULT_MINIO_BUCKET=batch-dev
BATCH_DEFAULT_MINIO_ACCESS_KEY=minioadmin
BATCH_DEFAULT_MINIO_SECRET_KEY=minioadmin123
BATCH_DEFAULT_MINIO_CONTAINER=batch-minio
BATCH_DEFAULT_MINIO_SERVICE_HOST=minio

BATCH_DEFAULT_REDIS_HOST=localhost
BATCH_DEFAULT_REDIS_PORT=16379
BATCH_DEFAULT_REDIS_CONTAINER=batch-valkey

BATCH_DEFAULT_CONSOLE_PORT=18080
BATCH_DEFAULT_TRIGGER_PORT=18081
BATCH_DEFAULT_ORCHESTRATOR_PORT=18082
BATCH_DEFAULT_WORKER_IMPORT_PORT=18083
BATCH_DEFAULT_WORKER_EXPORT_PORT=18084
BATCH_DEFAULT_WORKER_DISPATCH_PORT=18085
BATCH_DEFAULT_WORKER_PROCESS_PORT=18086
BATCH_DEFAULT_WORKER_ATOMIC_PORT=18087
