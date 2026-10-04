#!/usr/bin/env bash
# 生产容量治理只读巡检：PostgreSQL 热表/保留、Kafka topic 保留、对象存储桶容量。
# 默认不修复、不清理、不重置 offset。生产执行时请显式传真实 PG/Kafka/对象存储地址。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/ops/env.sh"

STRICT="${BATCH_PROD_CAPACITY_STRICT:-false}"
CHECK_POSTGRES="${BATCH_PROD_CAPACITY_CHECK_POSTGRES:-true}"
CHECK_KAFKA="${BATCH_PROD_CAPACITY_CHECK_KAFKA:-true}"
CHECK_OBJECT_STORE="${BATCH_PROD_CAPACITY_CHECK_OBJECT_STORE:-true}"
POSTGRES_STRICT="${BATCH_PROD_CAPACITY_POSTGRES_STRICT:-$STRICT}"
KAFKA_STRICT="${BATCH_PROD_CAPACITY_KAFKA_STRICT:-$STRICT}"
OBJECT_STORE_STRICT="${BATCH_PROD_CAPACITY_OBJECT_STORE_STRICT:-$STRICT}"

TABLE_SIZE_WARN_MB="${BATCH_PROD_CAPACITY_TABLE_SIZE_WARN_MB:-1024}"
DEAD_TUPLE_WARN_COUNT="${BATCH_PROD_CAPACITY_DEAD_TUPLE_WARN_COUNT:-1000000}"
OUTBOX_BACKLOG_WARN_COUNT="${BATCH_PROD_CAPACITY_OUTBOX_BACKLOG_WARN_COUNT:-1000}"
TRIGGER_BACKLOG_WARN_COUNT="${BATCH_PROD_CAPACITY_TRIGGER_BACKLOG_WARN_COUNT:-1000}"
DEDUP_WARN_COUNT="${BATCH_PROD_CAPACITY_DEDUP_WARN_COUNT:-10000000}"
OLD_RUNTIME_WARN_DAYS="${BATCH_PROD_CAPACITY_OLD_RUNTIME_WARN_DAYS:-30}"

PG_HOST="${BATCH_PROD_CAPACITY_PG_HOST:-${PGHOST:-localhost}}"
PG_PORT="${BATCH_PROD_CAPACITY_PG_PORT:-$PGPORT}"
PG_DATABASE="${BATCH_PROD_CAPACITY_PG_DATABASE:-$PGDATABASE}"
PG_USER="${BATCH_PROD_CAPACITY_PG_USER:-$PGUSER}"

KAFKA_BOOTSTRAP="${BATCH_PROD_CAPACITY_KAFKA_BOOTSTRAP:-${KAFKA_HOST_BOOTSTRAP:-$(batch_format_host_port "${KAFKA_HOST:-localhost}" "$BATCH_DEFAULT_KAFKA_HOST_PORT")}}"
KAFKA_CONTAINER="${BATCH_PROD_CAPACITY_KAFKA_CONTAINER:-$BATCH_DEFAULT_KAFKA_CONTAINER}"
KAFKA_CONTAINER_BOOTSTRAP="${BATCH_PROD_CAPACITY_KAFKA_CONTAINER_BOOTSTRAP:-$KAFKA_CONTAINER_BOOTSTRAP}"
KAFKA_MIN_REPLICATION_FACTOR="${BATCH_PROD_CAPACITY_KAFKA_MIN_REPLICATION_FACTOR:-1}"
KAFKA_REQUIRE_RETENTION="${BATCH_PROD_CAPACITY_KAFKA_REQUIRE_RETENTION:-false}"
KAFKA_TOPICS="${BATCH_PROD_CAPACITY_KAFKA_TOPICS:-batch.task.dispatch.import,batch.task.dispatch.export,batch.task.dispatch.process,batch.task.dispatch.dispatch,batch.task.dispatch.atomic,batch.task.result,batch.task.retry,batch.task.dead-letter,batch.trigger.launch.v1,batch.verifier.failure.v1}"

OBJECT_STORE_ENDPOINT="${BATCH_PROD_CAPACITY_OBJECT_STORE_ENDPOINT:-${BATCH_S3_ENDPOINT:-http://localhost:${MINIO_API_PORT:-19000}}}"
OBJECT_STORE_ACCESS_KEY="${BATCH_PROD_CAPACITY_OBJECT_STORE_ACCESS_KEY:-${BATCH_S3_ACCESS_KEY:-${MINIO_ROOT_USER:-}}}"
OBJECT_STORE_SECRET_KEY="${BATCH_PROD_CAPACITY_OBJECT_STORE_SECRET_KEY:-${BATCH_S3_SECRET_KEY:-${MINIO_ROOT_PASSWORD:-}}}"
OBJECT_STORE_BUCKETS="${BATCH_PROD_CAPACITY_OBJECT_STORE_BUCKETS:-${BATCH_S3_BUCKET:-${MINIO_BUCKET:-batch-dev}},${MINIO_AI_ATTACHMENT_BUCKET:-batch-ai-attachments}}"
OBJECT_STORE_REQUIRE_LIFECYCLE="${BATCH_PROD_CAPACITY_OBJECT_STORE_REQUIRE_LIFECYCLE:-false}"

failures=0
warnings=0

log() { printf '%s\n' "$*"; }
ok() { log "OK: $*"; }
warn() { log "WARN: $*"; warnings=$((warnings + 1)); }
fail() { log "FAIL: $*"; failures=$((failures + 1)); }
optional_issue() {
  local strict="$1"; shift
  if [[ "$strict" == "true" ]]; then
    fail "$*"
  else
    warn "$*"
  fi
}
status_or_fail() {
  local strict="$1"
  local status="$2"
  if [[ "$strict" == "true" && "$status" == "WARN" ]]; then
    printf 'FAIL\n'
  else
    printf '%s\n' "$status"
  fi
}

docker_running() {
  command -v docker >/dev/null 2>&1 \
    && docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null | grep -qx true
}

psql_file() {
  psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PG_DATABASE" \
    -tA -v ON_ERROR_STOP=1 \
    -v schema="$BATCH_SCHEMA" \
    -v table_size_warn_mb="$TABLE_SIZE_WARN_MB" \
    -v dead_tuple_warn_count="$DEAD_TUPLE_WARN_COUNT" \
    -v outbox_backlog_warn_count="$OUTBOX_BACKLOG_WARN_COUNT" \
    -v trigger_backlog_warn_count="$TRIGGER_BACKLOG_WARN_COUNT" \
    -v dedup_warn_count="$DEDUP_WARN_COUNT" \
    -v old_runtime_warn_days="$OLD_RUNTIME_WARN_DAYS" \
    "$@"
}

consume_status_rows() {
  local strict="$1"
  local row check_name status effective_status metric value threshold detail
  while IFS= read -r row; do
    [[ -n "$row" ]] || continue
    IFS='|' read -r check_name status metric value threshold detail <<<"$row"
    effective_status="$(status_or_fail "$strict" "$status")"
    printf '%-42s %-5s %-36s value=%s threshold=%s %s\n' \
      "$check_name" "$effective_status" "$metric" "$value" "${threshold:-"-"}" "$detail"
    case "$effective_status" in
      FAIL) failures=$((failures + 1)) ;;
      WARN) warnings=$((warnings + 1)) ;;
    esac
  done
}

check_postgres_capacity() {
  log "--- PostgreSQL capacity ${PG_HOST}:${PG_PORT}/${PG_DATABASE} ---"
  local output
  if ! output="$(psql_file -f "$OPS_SQL_DIR/inspect-production-capacity-postgres.sql" 2>&1)"; then
    fail "PostgreSQL capacity query failed: ${output}"
    return
  fi
  consume_status_rows "$POSTGRES_STRICT" <<<"$output"
}

kafka_bin() {
  local name="$1"
  if [[ -n "${BATCH_PROD_CAPACITY_KAFKA_BIN_DIR:-}" && -x "${BATCH_PROD_CAPACITY_KAFKA_BIN_DIR%/}/${name}" ]]; then
    printf '%s\n' "${BATCH_PROD_CAPACITY_KAFKA_BIN_DIR%/}/${name}"
  elif command -v "$name" >/dev/null 2>&1; then
    command -v "$name"
  else
    return 1
  fi
}

run_kafka_cli() {
  local name="$1"; shift
  local bin_path
  if bin_path="$(kafka_bin "$name" 2>/dev/null)"; then
    "$bin_path" --bootstrap-server "$KAFKA_BOOTSTRAP" "$@"
  elif docker_running "$KAFKA_CONTAINER"; then
    docker exec "$KAFKA_CONTAINER" "$KAFKA_CONTAINER_BIN_DIR/$name" --bootstrap-server "$KAFKA_CONTAINER_BOOTSTRAP" "$@"
  else
    return 127
  fi
}

check_kafka_capacity() {
  log "--- Kafka capacity ${KAFKA_BOOTSTRAP} ---"
  if ! run_kafka_cli kafka-topics.sh --list >/dev/null 2>&1; then
    optional_issue "$KAFKA_STRICT" "Kafka CLI 不可用或 broker 不可达，跳过 topic 容量巡检"
    return
  fi

  local old_ifs topic describe partitions rf configs retention cleanup_policy status detail
  old_ifs="$IFS"
  IFS=','
  for raw_topic in $KAFKA_TOPICS; do
    topic="$(printf '%s' "$raw_topic" | tr -d '[:space:]')"
    [[ -n "$topic" ]] || continue
    describe="$(run_kafka_cli kafka-topics.sh --describe --topic "$topic" 2>/dev/null | head -n 1 || true)"
    if [[ -z "$describe" ]]; then
      optional_issue "$KAFKA_STRICT" "Kafka topic 不存在或无法读取：${topic}"
      continue
    fi
    partitions="$(awk -F'PartitionCount: ' 'NF > 1 { split($2, a, " "); print a[1]; exit }' <<<"$describe")"
    rf="$(awk -F'ReplicationFactor: ' 'NF > 1 { split($2, a, " "); print a[1]; exit }' <<<"$describe")"
    status="OK"
    detail="partitions=${partitions:-?}, rf=${rf:-?}"
    if [[ "${rf:-0}" -lt "$KAFKA_MIN_REPLICATION_FACTOR" ]]; then
      status="$(status_or_fail "$KAFKA_STRICT" WARN)"
      if [[ "$status" == "FAIL" ]]; then failures=$((failures + 1)); else warnings=$((warnings + 1)); fi
    fi
    printf '%-42s %-5s %-36s value=%s threshold=%s %s\n' \
      "kafka.topic" "$status" "$topic" "rf=${rf:-?}" "rf>=${KAFKA_MIN_REPLICATION_FACTOR}" "$detail"

    configs="$(run_kafka_cli kafka-configs.sh --entity-type topics --entity-name "$topic" --describe 2>/dev/null || true)"
    retention="$(grep -o 'retention.ms=[^, ]*' <<<"$configs" | head -n 1 | cut -d= -f2-)"
    cleanup_policy="$(grep -o 'cleanup.policy=[^, ]*' <<<"$configs" | head -n 1 | cut -d= -f2-)"
    if [[ "$KAFKA_REQUIRE_RETENTION" == "true" && ( -z "$retention" || "$retention" == "-1" ) ]]; then
      optional_issue "$KAFKA_STRICT" "Kafka topic ${topic}: 未设置有界 retention.ms"
    else
      ok "Kafka topic ${topic}: retention.ms=${retention:-broker-default}, cleanup.policy=${cleanup_policy:-broker-default}"
    fi
  done
  IFS="$old_ifs"
}

check_object_store_capacity() {
  log "--- Object storage capacity ${OBJECT_STORE_ENDPOINT} ---"
  if [[ -z "$OBJECT_STORE_ACCESS_KEY" || -z "$OBJECT_STORE_SECRET_KEY" ]]; then
    optional_issue "$OBJECT_STORE_STRICT" "对象存储巡检缺少凭据，跳过 bucket 容量和 lifecycle 检查"
    return
  fi
  if ! command -v mc >/dev/null 2>&1; then
    optional_issue "$OBJECT_STORE_STRICT" "mc 未安装，跳过对象存储 bucket 容量和 lifecycle 检查"
    return
  fi

  local config_dir alias_name old_ifs bucket usage lifecycle
  config_dir="$(mktemp -d "${TMPDIR:-/tmp}/batch-prod-mc.XXXXXX")"
  alias_name="batch-prod-capacity"
  if ! MC_CONFIG_DIR="$config_dir" mc alias set "$alias_name" "$OBJECT_STORE_ENDPOINT" "$OBJECT_STORE_ACCESS_KEY" "$OBJECT_STORE_SECRET_KEY" >/dev/null 2>&1; then
    rm -rf "$config_dir"
    fail "对象存储 alias 设置失败"
    return
  fi

  old_ifs="$IFS"
  IFS=','
  for raw_bucket in $OBJECT_STORE_BUCKETS; do
    bucket="$(printf '%s' "$raw_bucket" | tr -d '[:space:]')"
    [[ -n "$bucket" ]] || continue
    if ! MC_CONFIG_DIR="$config_dir" mc ls "$alias_name/$bucket" >/dev/null 2>&1; then
      optional_issue "$OBJECT_STORE_STRICT" "对象存储 bucket 不可访问：${bucket}"
      continue
    fi
    usage="$(MC_CONFIG_DIR="$config_dir" mc du "$alias_name/$bucket" 2>/dev/null | tr -s ' ' | sed 's/^ *//')"
    ok "对象存储 bucket ${bucket}: ${usage:-容量不可读}"
    lifecycle="$(MC_CONFIG_DIR="$config_dir" mc ilm ls "$alias_name/$bucket" 2>/dev/null || true)"
    if [[ "$OBJECT_STORE_REQUIRE_LIFECYCLE" == "true" && -z "$lifecycle" ]]; then
      optional_issue "$OBJECT_STORE_STRICT" "对象存储 bucket ${bucket}: 未读取到 lifecycle 规则"
    elif [[ -n "$lifecycle" ]]; then
      ok "对象存储 bucket ${bucket}: lifecycle 已配置"
    else
      optional_issue "$OBJECT_STORE_STRICT" "对象存储 bucket ${bucket}: lifecycle 未验证，生产需按 runbook 单独确认"
    fi
  done
  IFS="$old_ifs"
  rm -rf "$config_dir"
}

[[ "$CHECK_POSTGRES" == "true" ]] && check_postgres_capacity
[[ "$CHECK_KAFKA" == "true" ]] && check_kafka_capacity
[[ "$CHECK_OBJECT_STORE" == "true" ]] && check_object_store_capacity

log ""
if [[ "$failures" -gt 0 ]]; then
  log "Production capacity inspection FAILED: ${failures} failure(s), ${warnings} warning(s)"
  exit 1
fi
if [[ "$warnings" -gt 0 ]]; then
  log "Production capacity inspection PASSED WITH WARNINGS: ${warnings} warning(s)"
else
  log "Production capacity inspection PASSED"
fi
