#!/usr/bin/env bash
# 基础依赖只读巡检：PostgreSQL、Kafka、Valkey、MinIO。
# 只做连通性、关键只读状态和可选 lag/桶检查，不执行重启、清理、重放、failover 或 heal。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/ops/env.sh"
STRICT="${BATCH_INFRA_STRICT:-false}"
USE_DOCKER="${BATCH_INFRA_USE_DOCKER:-true}"
PG_HOST="${BATCH_INFRA_PG_HOST:-${PGHOST:-localhost}}"
PG_PORT="${BATCH_INFRA_PG_PORT:-${PGPORT:-5432}}"
PG_DATABASE="${BATCH_INFRA_PG_DATABASE:-${PGDATABASE:-batch_platform}}"
PG_USER="${BATCH_INFRA_PG_USER:-${PGUSER:-batch_user}}"
PG_CONTAINER="${BATCH_INFRA_PG_CONTAINER:-${PG_CONTAINER:-batch-postgres-primary}}"
KAFKA_BOOTSTRAP="${BATCH_INFRA_KAFKA_BOOTSTRAP:-${BATCH_OBSERVABILITY_KAFKA_BOOTSTRAP_SERVERS:-${KAFKA_HOST_BOOTSTRAP:-localhost:19092}}}"
KAFKA_CONTAINER="${BATCH_INFRA_KAFKA_CONTAINER:-batch-kafka}"
KAFKA_CONTAINER_BOOTSTRAP="${BATCH_INFRA_KAFKA_CONTAINER_BOOTSTRAP:-kafka:29092}"
KAFKA_GROUPS="${BATCH_INFRA_KAFKA_GROUPS:-${BATCH_OBSERVABILITY_KAFKA_GROUPS:-}}"
KAFKA_LAG_THRESHOLD="${BATCH_INFRA_KAFKA_LAG_THRESHOLD:-1000}"
VALKEY_HOST="${BATCH_INFRA_VALKEY_HOST:-localhost}"
VALKEY_PORT="${BATCH_INFRA_VALKEY_PORT:-${REDIS_PORT:-16379}}"
VALKEY_CONTAINER="${BATCH_INFRA_VALKEY_CONTAINER:-batch-valkey}"
VALKEY_PASSWORD="${BATCH_INFRA_VALKEY_PASSWORD:-${REDIS_PASSWORD:-}}"
MINIO_ENDPOINT="${BATCH_INFRA_MINIO_ENDPOINT:-http://localhost:${MINIO_API_PORT:-19000}}"
MINIO_BUCKET_NAME="${BATCH_INFRA_MINIO_BUCKET:-${MINIO_BUCKET:-batch-dev}}"
MINIO_ACCESS_KEY="${BATCH_INFRA_MINIO_ACCESS_KEY:-${MINIO_ROOT_USER:-}}"
MINIO_SECRET_KEY="${BATCH_INFRA_MINIO_SECRET_KEY:-${MINIO_ROOT_PASSWORD:-}}"

failures=0
warnings=0
log() { printf '%s\n' "$*"; }
ok() { log "OK: $*"; }
warn() { log "WARN: $*"; warnings=$((warnings + 1)); }
fail() { log "FAIL: $*"; failures=$((failures + 1)); }
optional_issue() { if [[ "$STRICT" == "true" ]]; then fail "$*"; else warn "$*"; fi; }

docker_running() {
  [[ "$USE_DOCKER" == "true" ]] && command -v docker >/dev/null 2>&1 \
    && docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null | grep -qx true
}
run_docker() { local container="$1"; shift; docker exec "$container" "$@"; }

check_postgres() {
  log "--- PostgreSQL ${PG_HOST}:${PG_PORT}/${PG_DATABASE} ---"
  if command -v pg_isready >/dev/null 2>&1 && pg_isready -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PG_DATABASE" >/dev/null 2>&1; then
    ok "PostgreSQL accepts connections"
  elif docker_running "$PG_CONTAINER" && run_docker "$PG_CONTAINER" pg_isready -U "$PG_USER" -d "$PG_DATABASE" >/dev/null 2>&1; then
    ok "PostgreSQL accepts connections (container ${PG_CONTAINER})"
  else
    fail "PostgreSQL is not ready; checked host client and ${PG_CONTAINER}"
    return
  fi
  local snapshot recovery database version sessions
  if snapshot="$(psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PG_DATABASE" -Atqf "$OPS_SQL_DIR/inspect-infra-postgres.sql" 2>/dev/null)"; then
    IFS='|' read -r database version recovery sessions <<<"$snapshot"
    ok "PostgreSQL database=${database} version=${version} recovery=${recovery} sessions=${sessions}"
  else
    optional_issue "PostgreSQL SQL status unavailable (check credentials/permissions)"
  fi
}

kafka_cli() {
  local command_name="$1"
  if [[ -n "${BATCH_INFRA_KAFKA_BIN_DIR:-}" && -x "${BATCH_INFRA_KAFKA_BIN_DIR%/}/${command_name}" ]]; then
    printf '%s\n' "${BATCH_INFRA_KAFKA_BIN_DIR%/}/${command_name}"
  elif command -v "$command_name" >/dev/null 2>&1; then
    command -v "$command_name"
  else
    return 1
  fi
}

check_kafka() {
  log "--- Kafka ${KAFKA_BOOTSTRAP} ---"
  local topics_output="" topic_cli=""
  if topic_cli="$(kafka_cli kafka-topics.sh 2>/dev/null)"; then
    topics_output="$($topic_cli --bootstrap-server "$KAFKA_BOOTSTRAP" --list 2>&1)" || topics_output=""
  elif docker_running "$KAFKA_CONTAINER"; then
    topics_output="$(run_docker "$KAFKA_CONTAINER" "$KAFKA_CONTAINER_BIN_DIR/kafka-topics.sh" --bootstrap-server "$KAFKA_CONTAINER_BOOTSTRAP" --list 2>&1)" || topics_output=""
  fi
  if [[ -n "$topics_output" ]]; then
    ok "Kafka broker responds; topics: $(printf '%s\n' "$topics_output" | sed '/^$/d' | wc -l | tr -d ' ')"
  else
    fail "Kafka broker is not reachable or kafka-topics.sh is unavailable"
    return
  fi
  [[ -z "$KAFKA_GROUPS" ]] && return
  local group_cli=""
  if group_cli="$(kafka_cli kafka-consumer-groups.sh 2>/dev/null)"; then :
  elif docker_running "$KAFKA_CONTAINER"; then group_cli="docker"
  else optional_issue "Kafka lag skipped: kafka-consumer-groups.sh unavailable"; return; fi
  local group output lag
  IFS=',' read -r -a groups <<<"$KAFKA_GROUPS"
  for group in "${groups[@]}"; do
    if [[ "$group_cli" == "docker" ]]; then
      output="$(run_docker "$KAFKA_CONTAINER" "$KAFKA_CONTAINER_BIN_DIR/kafka-consumer-groups.sh" --bootstrap-server "$KAFKA_CONTAINER_BOOTSTRAP" --describe --group "$group" 2>&1)" || output=""
    else
      output="$($group_cli --bootstrap-server "$KAFKA_BOOTSTRAP" --describe --group "$group" 2>&1)" || output=""
    fi
    if [[ -z "$output" ]]; then optional_issue "Kafka consumer group ${group}: lag unavailable or group does not exist"; continue; fi
    lag="$(awk 'NR > 1 && $5 ~ /^[0-9]+$/ {sum += $5} END {print sum + 0}' <<<"$output")"
    if [[ "$lag" -gt "$KAFKA_LAG_THRESHOLD" ]]; then fail "Kafka consumer group ${group}: lag ${lag} > ${KAFKA_LAG_THRESHOLD}"; else ok "Kafka consumer group ${group}: lag ${lag}"; fi
  done
}

valkey_command() {
  if command -v valkey-cli >/dev/null 2>&1; then printf '%s\n' valkey-cli
  elif command -v redis-cli >/dev/null 2>&1; then printf '%s\n' redis-cli
  else return 1; fi
}
run_valkey() {
  local cli="$1"; shift
  if [[ -n "$VALKEY_PASSWORD" ]]; then REDISCLI_AUTH="$VALKEY_PASSWORD" "$cli" -h "$VALKEY_HOST" -p "$VALKEY_PORT" "$@"
  else "$cli" -h "$VALKEY_HOST" -p "$VALKEY_PORT" "$@"; fi
}

check_valkey() {
  log "--- Valkey ${VALKEY_HOST}:${VALKEY_PORT} ---"
  local cli="" ping=""
  if cli="$(valkey_command 2>/dev/null)"; then ping="$(run_valkey "$cli" ping 2>/dev/null)" || ping=""
  elif docker_running "$VALKEY_CONTAINER"; then ping="$(run_docker "$VALKEY_CONTAINER" valkey-cli ping 2>/dev/null)" || ping=""; fi
  if [[ "$ping" != "PONG" ]]; then fail "Valkey is not reachable"; return; fi
  ok "Valkey responds to PING"
  if [[ -n "$cli" ]]; then
    local memory role persistence
    memory="$(run_valkey "$cli" info memory 2>/dev/null | awk -F: '/^used_memory_human:/{print $2}' | tr -d '\r')"
    role="$(run_valkey "$cli" info replication 2>/dev/null | awk -F: '/^role:/{print $2}' | tr -d '\r')"
    persistence="$(run_valkey "$cli" info persistence 2>/dev/null | awk -F: '/^aof_enabled:/{print "aof=" $2} /^rdb_last_bgsave_status:/{print "rdb=" $2}' | tr '\n' ' ')"
    ok "Valkey role=${role:-unknown} memory=${memory:-unknown} ${persistence:-persistence=unavailable}"
  elif docker_running "$VALKEY_CONTAINER"; then ok "Valkey container is healthy; host CLI unavailable, detailed stats skipped"
  else optional_issue "Valkey detailed status unavailable"; fi
}

check_minio() {
  log "--- MinIO ${MINIO_ENDPOINT} bucket=${MINIO_BUCKET_NAME} ---"
  if curl -fsS --max-time "${BATCH_INFRA_HTTP_TIMEOUT_SECONDS:-5}" "${MINIO_ENDPOINT%/}/minio/health/ready" >/dev/null 2>&1; then ok "MinIO readiness endpoint responds"
  else fail "MinIO readiness endpoint is not reachable"; return; fi
  if [[ -z "$MINIO_ACCESS_KEY" || -z "$MINIO_SECRET_KEY" ]]; then optional_issue "MinIO bucket check skipped: credentials were not provided"; return; fi
  local mc_bin=""
  if command -v mc >/dev/null 2>&1; then mc_bin="$(command -v mc)"; else optional_issue "MinIO bucket check skipped: mc is not installed"; return; fi
  local config_dir alias_name
  config_dir="$(mktemp -d "${TMPDIR:-/tmp}/batch-mc.XXXXXX")"
  alias_name="batch-check"
  if MC_CONFIG_DIR="$config_dir" "$mc_bin" alias set "$alias_name" "$MINIO_ENDPOINT" "$MINIO_ACCESS_KEY" "$MINIO_SECRET_KEY" >/dev/null 2>&1 \
      && MC_CONFIG_DIR="$config_dir" "$mc_bin" ls "$alias_name/$MINIO_BUCKET_NAME" >/dev/null 2>&1; then ok "MinIO bucket is accessible"; else fail "MinIO bucket is not accessible: ${MINIO_BUCKET_NAME}"; fi
  rm -rf "$config_dir"
}

run_component() {
  case "$1" in
    postgres|pg) check_postgres ;;
    kafka|kfk) check_kafka ;;
    valkey|redis) check_valkey ;;
    minio|s3) check_minio ;;
    *) log "Unknown component: $1 (expected postgres, kafka, valkey, minio)"; failures=$((failures + 1)) ;;
  esac
}

components=("$@")
[[ ${#components[@]} -eq 0 ]] && components=(all)
for component in "${components[@]}"; do
  if [[ "$component" == "all" ]]; then check_postgres; check_kafka; check_valkey; check_minio; else run_component "$component"; fi
done

log ""
if [[ "$failures" -gt 0 ]]; then log "Dependency inspection FAILED: ${failures} failure(s), ${warnings} warning(s)"; exit 1; fi
if [[ "$warnings" -gt 0 ]]; then log "Dependency inspection PASSED WITH WARNINGS: ${warnings} warning(s)"; else log "Dependency inspection PASSED"; fi
