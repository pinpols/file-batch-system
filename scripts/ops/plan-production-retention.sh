#!/usr/bin/env bash
# 生产保留治理计划：只读输出候选量和缺失策略，不执行归档、删除或配置修改。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/ops/env.sh"

STRICT="${BATCH_PROD_RETENTION_STRICT:-false}"
CHECK_POSTGRES="${BATCH_PROD_RETENTION_CHECK_POSTGRES:-true}"
CHECK_KAFKA="${BATCH_PROD_RETENTION_CHECK_KAFKA:-true}"
CHECK_OBJECT_STORE="${BATCH_PROD_RETENTION_CHECK_OBJECT_STORE:-true}"
CHECK_REDIS="${BATCH_PROD_RETENTION_CHECK_REDIS:-true}"
POSTGRES_STRICT="${BATCH_PROD_RETENTION_POSTGRES_STRICT:-$STRICT}"
KAFKA_STRICT="${BATCH_PROD_RETENTION_KAFKA_STRICT:-$STRICT}"
OBJECT_STORE_STRICT="${BATCH_PROD_RETENTION_OBJECT_STORE_STRICT:-$STRICT}"
REDIS_STRICT="${BATCH_PROD_RETENTION_REDIS_STRICT:-$STRICT}"

OLD_RUNTIME_DAYS="${BATCH_PROD_RETENTION_OLD_RUNTIME_DAYS:-30}"
OLD_OUTBOX_DAYS="${BATCH_PROD_RETENTION_OLD_OUTBOX_DAYS:-7}"
OLD_TRIGGER_DAYS="${BATCH_PROD_RETENTION_OLD_TRIGGER_DAYS:-30}"
DEDUP_WARN_ROWS="${BATCH_PROD_RETENTION_DEDUP_WARN_ROWS:-10000000}"

PG_HOST="${BATCH_PROD_RETENTION_PG_HOST:-${PGHOST:-localhost}}"
PG_PORT="${BATCH_PROD_RETENTION_PG_PORT:-$PGPORT}"
PG_DATABASE="${BATCH_PROD_RETENTION_PG_DATABASE:-$PGDATABASE}"
PG_USER="${BATCH_PROD_RETENTION_PG_USER:-$PGUSER}"

KAFKA_BOOTSTRAP="${BATCH_PROD_RETENTION_KAFKA_BOOTSTRAP:-${KAFKA_HOST_BOOTSTRAP:-$(batch_format_host_port "${KAFKA_HOST:-localhost}" "$BATCH_DEFAULT_KAFKA_HOST_PORT")}}"
KAFKA_CONTAINER="${BATCH_PROD_RETENTION_KAFKA_CONTAINER:-$BATCH_DEFAULT_KAFKA_CONTAINER}"
KAFKA_CONTAINER_BOOTSTRAP="${BATCH_PROD_RETENTION_KAFKA_CONTAINER_BOOTSTRAP:-$KAFKA_CONTAINER_BOOTSTRAP}"
KAFKA_REQUIRE_TOPIC_RETENTION="${BATCH_PROD_RETENTION_KAFKA_REQUIRE_TOPIC_RETENTION:-true}"
KAFKA_TOPICS="${BATCH_PROD_RETENTION_KAFKA_TOPICS:-batch.task.dispatch.import,batch.task.dispatch.export,batch.task.dispatch.process,batch.task.dispatch.dispatch,batch.task.dispatch.atomic,batch.task.result,batch.task.retry,batch.task.dead-letter,batch.trigger.launch.v1,batch.verifier.failure.v1}"

OBJECT_STORE_ENDPOINT="${BATCH_PROD_RETENTION_OBJECT_STORE_ENDPOINT:-${BATCH_S3_ENDPOINT:-http://localhost:${MINIO_API_PORT:-19000}}}"
OBJECT_STORE_ACCESS_KEY="${BATCH_PROD_RETENTION_OBJECT_STORE_ACCESS_KEY:-${BATCH_S3_ACCESS_KEY:-${MINIO_ROOT_USER:-}}}"
OBJECT_STORE_SECRET_KEY="${BATCH_PROD_RETENTION_OBJECT_STORE_SECRET_KEY:-${BATCH_S3_SECRET_KEY:-${MINIO_ROOT_PASSWORD:-}}}"
OBJECT_STORE_BUCKETS="${BATCH_PROD_RETENTION_OBJECT_STORE_BUCKETS:-${BATCH_S3_BUCKET:-${MINIO_BUCKET:-batch-dev}},${MINIO_AI_ATTACHMENT_BUCKET:-batch-ai-attachments}}"
OBJECT_STORE_REQUIRE_LIFECYCLE="${BATCH_PROD_RETENTION_OBJECT_STORE_REQUIRE_LIFECYCLE:-true}"

REDIS_HOST="${BATCH_PROD_RETENTION_REDIS_HOST:-${VALKEY_HOST:-localhost}}"
REDIS_PORT="${BATCH_PROD_RETENTION_REDIS_PORT:-${VALKEY_PORT:-6379}}"
REDIS_PASSWORD="${BATCH_PROD_RETENTION_REDIS_PASSWORD:-${VALKEY_PASSWORD:-}}"
REDIS_TLS="${BATCH_PROD_RETENTION_REDIS_TLS:-false}"
REDIS_PATTERNS="${BATCH_PROD_RETENTION_REDIS_PATTERNS:-batch:*}"
REDIS_SCAN_LIMIT="${BATCH_PROD_RETENTION_REDIS_SCAN_LIMIT:-200}"

failures=0
warnings=0

log() { printf '%s\n' "$*"; }
ok() { log "OK: $*"; }
warn() { log "WARN: $*"; warnings=$((warnings + 1)); }
fail() { log "FAIL: $*"; failures=$((failures + 1)); }
plan() { log "PLAN: $*"; warnings=$((warnings + 1)); }

optional_issue() {
  local strict="$1"; shift
  if [[ "$strict" == "true" ]]; then
    fail "$*"
  else
    warn "$*"
  fi
}

docker_running() {
  command -v docker >/dev/null 2>&1 \
    && docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null | grep -qx true
}

psql_retention_plan() {
  psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PG_DATABASE" \
    -tA -v ON_ERROR_STOP=1 \
    -v schema="$BATCH_SCHEMA" \
    -v old_runtime_days="$OLD_RUNTIME_DAYS" \
    -v old_outbox_days="$OLD_OUTBOX_DAYS" \
    -v old_trigger_days="$OLD_TRIGGER_DAYS" \
    -v dedup_warn_rows="$DEDUP_WARN_ROWS" \
    "$@"
}

consume_plan_rows() {
  local strict="$1"
  local row area status metric value threshold detail effective_status
  while IFS= read -r row; do
    [[ -n "$row" ]] || continue
    IFS='|' read -r area status metric value threshold detail <<<"$row"
    effective_status="$status"
    if [[ "$strict" == "true" && "$status" == "WARN" ]]; then
      effective_status="FAIL"
    fi
    printf '%-40s %-5s %-38s value=%s threshold=%s %s\n' \
      "$area" "$effective_status" "$metric" "$value" "${threshold:-"-"}" "$detail"
    case "$effective_status" in
      FAIL) failures=$((failures + 1)) ;;
      WARN|PLAN) warnings=$((warnings + 1)) ;;
    esac
  done
}

check_postgres_retention_plan() {
  log "--- PostgreSQL retention plan ${PG_HOST}:${PG_PORT}/${PG_DATABASE} ---"
  local output
  if ! output="$(psql_retention_plan -f "$OPS_SQL_DIR/plan-production-retention-postgres.sql" 2>&1)"; then
    optional_issue "$POSTGRES_STRICT" "PostgreSQL 保留计划查询失败：${output}"
    return
  fi
  consume_plan_rows "$POSTGRES_STRICT" <<<"$output"
}

kafka_bin() {
  local name="$1"
  if [[ -n "${BATCH_PROD_RETENTION_KAFKA_BIN_DIR:-}" && -x "${BATCH_PROD_RETENTION_KAFKA_BIN_DIR%/}/${name}" ]]; then
    printf '%s\n' "${BATCH_PROD_RETENTION_KAFKA_BIN_DIR%/}/${name}"
  elif [[ -n "${BATCH_PROD_CAPACITY_KAFKA_BIN_DIR:-}" && -x "${BATCH_PROD_CAPACITY_KAFKA_BIN_DIR%/}/${name}" ]]; then
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

check_kafka_retention_plan() {
  log "--- Kafka retention plan ${KAFKA_BOOTSTRAP} ---"
  if ! run_kafka_cli kafka-topics.sh --list >/dev/null 2>&1; then
    optional_issue "$KAFKA_STRICT" "Kafka CLI 不可用或 broker 不可达，跳过 topic 保留计划"
    return
  fi

  local old_ifs raw_topic topic configs retention cleanup_policy
  old_ifs="$IFS"
  IFS=','
  for raw_topic in $KAFKA_TOPICS; do
    topic="$(printf '%s' "$raw_topic" | tr -d '[:space:]')"
    [[ -n "$topic" ]] || continue
    configs="$(run_kafka_cli kafka-configs.sh --entity-type topics --entity-name "$topic" --describe 2>/dev/null || true)"
    if [[ -z "$configs" ]]; then
      optional_issue "$KAFKA_STRICT" "Kafka topic 不存在或无法读取配置：${topic}"
      continue
    fi
    retention="$(grep -o 'retention.ms=[^, ]*' <<<"$configs" | head -n 1 | cut -d= -f2-)"
    cleanup_policy="$(grep -o 'cleanup.policy=[^, ]*' <<<"$configs" | head -n 1 | cut -d= -f2-)"
    if [[ "$KAFKA_REQUIRE_TOPIC_RETENTION" == "true" && ( -z "$retention" || "$retention" == "-1" ) ]]; then
      optional_issue "$KAFKA_STRICT" "Kafka topic ${topic}: 未设置 topic 级有界 retention.ms，需在生产环境固化"
    else
      ok "Kafka topic ${topic}: retention.ms=${retention:-broker-default}, cleanup.policy=${cleanup_policy:-broker-default}"
    fi
  done
  IFS="$old_ifs"
}

check_object_store_retention_plan() {
  log "--- Object storage lifecycle plan ${OBJECT_STORE_ENDPOINT} ---"
  if [[ -z "$OBJECT_STORE_ACCESS_KEY" || -z "$OBJECT_STORE_SECRET_KEY" ]]; then
    optional_issue "$OBJECT_STORE_STRICT" "对象存储缺少只读凭据，跳过 lifecycle 计划"
    return
  fi
  if ! command -v mc >/dev/null 2>&1; then
    optional_issue "$OBJECT_STORE_STRICT" "mc 未安装，跳过对象存储 lifecycle 计划"
    return
  fi

  local config_dir alias_name old_ifs raw_bucket bucket lifecycle
  config_dir="$(mktemp -d "${TMPDIR:-/tmp}/batch-prod-retention-mc.XXXXXX")"
  alias_name="batch-prod-retention"
  if ! MC_CONFIG_DIR="$config_dir" mc alias set "$alias_name" "$OBJECT_STORE_ENDPOINT" "$OBJECT_STORE_ACCESS_KEY" "$OBJECT_STORE_SECRET_KEY" >/dev/null 2>&1; then
    rm -rf "$config_dir"
    optional_issue "$OBJECT_STORE_STRICT" "对象存储 alias 设置失败"
    return
  fi

  old_ifs="$IFS"
  IFS=','
  for raw_bucket in $OBJECT_STORE_BUCKETS; do
    bucket="$(printf '%s' "$raw_bucket" | tr -d '[:space:]')"
    [[ -n "$bucket" ]] || continue
    lifecycle="$(MC_CONFIG_DIR="$config_dir" mc ilm ls "$alias_name/$bucket" 2>/dev/null || true)"
    if [[ -n "$lifecycle" ]]; then
      ok "对象存储 bucket ${bucket}: lifecycle 可读取"
    elif [[ "$OBJECT_STORE_REQUIRE_LIFECYCLE" == "true" ]]; then
      optional_issue "$OBJECT_STORE_STRICT" "对象存储 bucket ${bucket}: 未读取到 lifecycle，需按导入 staging、导出 draft、坏行文件、归档前缀拆策略"
    else
      plan "对象存储 bucket ${bucket}: lifecycle 未强制，建议按前缀补保留策略"
    fi
  done
  IFS="$old_ifs"
  rm -rf "$config_dir"
}

redis_cli() {
  local args=(--raw -h "$REDIS_HOST" -p "$REDIS_PORT")
  if [[ "$REDIS_TLS" == "true" ]]; then
    args+=(--tls)
  fi
  if [[ -n "$REDIS_PASSWORD" ]]; then
    args+=(-a "$REDIS_PASSWORD" --no-auth-warning)
  fi
  redis-cli "${args[@]}" "$@"
}

check_redis_retention_plan() {
  log "--- Redis retention plan ${REDIS_HOST}:${REDIS_PORT} ---"
  if ! command -v redis-cli >/dev/null 2>&1; then
    optional_issue "$REDIS_STRICT" "redis-cli 未安装，跳过 Redis TTL 计划"
    return
  fi
  if ! redis_cli PING >/dev/null 2>&1; then
    optional_issue "$REDIS_STRICT" "Redis 不可达或凭据无效，跳过 TTL 计划"
    return
  fi

  local old_ifs raw_pattern pattern sample_count no_ttl_count key ttl
  old_ifs="$IFS"
  IFS=','
  for raw_pattern in $REDIS_PATTERNS; do
    pattern="$(printf '%s' "$raw_pattern" | tr -d '[:space:]')"
    [[ -n "$pattern" ]] || continue
    sample_count=0
    no_ttl_count=0
    while IFS= read -r key; do
      [[ -n "$key" ]] || continue
      sample_count=$((sample_count + 1))
      ttl="$(redis_cli TTL "$key" 2>/dev/null || printf '%s' "-2")"
      if [[ "$ttl" == "-1" ]]; then
        no_ttl_count=$((no_ttl_count + 1))
      fi
    done < <(redis_cli --scan --pattern "$pattern" 2>/dev/null | head -n "$REDIS_SCAN_LIMIT")

    if [[ "$sample_count" -eq 0 ]]; then
      ok "Redis pattern ${pattern}: 未扫描到样本"
    elif [[ "$no_ttl_count" -gt 0 ]]; then
      optional_issue "$REDIS_STRICT" "Redis pattern ${pattern}: 样本 ${sample_count} 个，其中 ${no_ttl_count} 个没有 TTL，需确认是否为锁、幂等或缓存设计"
    else
      ok "Redis pattern ${pattern}: 样本 ${sample_count} 个均有 TTL"
    fi
  done
  IFS="$old_ifs"
}

log "Production retention plan is read-only. It reports candidate work only."
[[ "$CHECK_POSTGRES" == "true" ]] && check_postgres_retention_plan
[[ "$CHECK_KAFKA" == "true" ]] && check_kafka_retention_plan
[[ "$CHECK_OBJECT_STORE" == "true" ]] && check_object_store_retention_plan
[[ "$CHECK_REDIS" == "true" ]] && check_redis_retention_plan

log ""
if [[ "$failures" -gt 0 ]]; then
  log "Production retention plan FAILED: ${failures} failure(s), ${warnings} warning(s)/plan item(s)"
  exit 1
fi
if [[ "$warnings" -gt 0 ]]; then
  log "Production retention plan PASSED WITH PLAN ITEMS: ${warnings} item(s)"
else
  log "Production retention plan PASSED"
fi
