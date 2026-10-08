#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${COMPOSE_ENV_FILE:-$ROOT/.env.local}"
PROJECT="bfs-redis-ha-drill-$(date -u +%Y%m%d%H%M%S)-$$"
export COMPOSE_PROJECT_NAME="$PROJECT"
# shellcheck source=../lib/destructive-ops.sh
source "$ROOT/scripts/lib/destructive-ops.sh"
COMPOSE_FILES=(
  --project-name "$PROJECT"
  --env-file "$ENV_FILE"
  -f "$ROOT/docker-compose.yml"
  -f "$ROOT/deploy/docker/compose/app.yml"
  -f "$ROOT/docker-compose.redis-sentinel-ha.yml"
  -f "$ROOT/deploy/docker/compose/app-redis-sentinel.yml"
)
RUN_ID="redis-ha-$(date -u +%Y%m%d%H%M%S)-$$"
KEY="batch:local-ha-drill:$RUN_ID"

[[ -f "$ENV_FILE" ]] || { echo "Compose env 文件不存在: $ENV_FILE" >&2; exit 2; }
[[ "$PROJECT" == bfs-redis-ha-drill-* ]] || { echo "拒绝使用非隔离 Compose project" >&2; exit 2; }
batch_require_local_docker_context

compose() { docker compose "${COMPOSE_FILES[@]}" "$@"; }

cleanup() {
  local status=$?
  trap - EXIT
  if ((status != 0)); then
    compose logs --no-color >&2 || true
  fi
  compose down --volumes --remove-orphans
  exit "$status"
}
trap cleanup EXIT

wait_for_command() {
  local timeout_seconds="$1"
  shift
  local deadline=$((SECONDS + timeout_seconds))
  until "$@"; do
    ((SECONDS < deadline)) || return 1
    sleep 2
  done
}

service_healthy() {
  compose exec -T "$1" sh -ec 'valkey-cli -p "$1" ping 2>/dev/null | grep -qx PONG' sh "${2:-6379}" >/dev/null 2>&1
}

sentinel_master_host() {
  compose exec -T valkey-sentinel-1 valkey-cli -p 26379 --raw \
    sentinel get-master-addr-by-name batch-valkey 2>/dev/null \
    | tr -d '\r' | sed -n '1p'
}

sentinel_promoted_replica() {
  local host
  host="$(sentinel_master_host)"
  [[ -n "$host" && "$host" != valkey ]]
}

sentinel_knows_two_replicas() {
  local replicas
  replicas="$(compose exec -T valkey-sentinel-1 valkey-cli -p 26379 --raw \
    sentinel master batch-valkey 2>/dev/null | awk 'previous == "num-slaves" { print; exit } { previous = $0 }')"
  [[ "$replicas" == 2 ]]
}

old_master_rejoined() {
  local role
  role="$(compose exec -T valkey valkey-cli INFO replication 2>/dev/null \
    | tr -d '\r' | sed -n 's/^role://p')"
  [[ "$role" == slave ]]
}

old_master_caught_up() {
  local value
  value="$(compose exec -T valkey valkey-cli GET "$KEY" 2>/dev/null | tr -d '\r')"
  [[ "$value" == failover-write ]]
}

echo "启动隔离 Sentinel 演练栈: $PROJECT"
compose up -d valkey valkey-replica-1 valkey-replica-2 \
  valkey-sentinel-1 valkey-sentinel-2 valkey-sentinel-3

for service in valkey valkey-replica-1 valkey-replica-2; do
  wait_for_command 90 service_healthy "$service" 6379 || {
    echo "Valkey 未就绪: $service" >&2
    exit 1
  }
done
for service in valkey-sentinel-1 valkey-sentinel-2 valkey-sentinel-3; do
  wait_for_command 90 service_healthy "$service" 26379 || {
    echo "Sentinel 未就绪: $service" >&2
    exit 1
  }
done

replica_count="$(compose exec -T valkey valkey-cli WAIT 2 10000 | tr -d '\r')"
[[ "$replica_count" == 2 ]] || {
  echo "主库未确认两个副本同步，WAIT 返回 $replica_count" >&2
  exit 1
}

compose exec -T valkey valkey-cli SET "$KEY" replicated >/dev/null
replica_count="$(compose exec -T valkey valkey-cli WAIT 2 10000 | tr -d '\r')"
[[ "$replica_count" == 2 ]] || {
  echo "演练标记未复制到两个副本，WAIT 返回 $replica_count" >&2
  exit 1
}

initial_master="$(sentinel_master_host)"
[[ "$initial_master" == valkey ]] || {
  echo "Sentinel 初始 master 不正确: ${initial_master:-empty}" >&2
  exit 1
}
wait_for_command 60 sentinel_knows_two_replicas || {
  echo "Sentinel 尚未发现两个复制节点，拒绝开始故障注入" >&2
  exit 1
}
echo "Sentinel 初始 master=${initial_master}，两个副本已确认演练标记。"

compose stop valkey >/dev/null
wait_for_command 60 sentinel_promoted_replica || {
  echo "Sentinel 未在 60 秒内完成主节点切换" >&2
  exit 1
}

promoted_master="$(sentinel_master_host)"
[[ -n "$promoted_master" ]] || { echo "Sentinel 未返回新 master" >&2; exit 1; }
sentinel_port="$(compose exec -T valkey-sentinel-1 valkey-cli -p 26379 --raw \
  sentinel get-master-addr-by-name batch-valkey 2>/dev/null | tr -d '\r' | sed -n '2p')"
[[ "$sentinel_port" == 6379 ]] || { echo "Sentinel 返回异常端口: $sentinel_port" >&2; exit 1; }

observed="$(compose exec -T valkey-sentinel-1 valkey-cli -h "$promoted_master" \
  -p "$sentinel_port" GET "$KEY" | tr -d '\r')"
[[ "$observed" == replicated ]] || {
  echo "切换后演练标记丢失或不可读: $observed" >&2
  exit 1
}
compose exec -T valkey-sentinel-1 valkey-cli -h "$promoted_master" \
  -p "$sentinel_port" SET "$KEY" failover-write >/dev/null
echo "Sentinel 已切换到 ${promoted_master}，切换前数据可读，切换后写入成功。"

compose start valkey >/dev/null
wait_for_command 60 service_healthy valkey 6379 || {
  echo "旧 master 重启后未就绪" >&2
  exit 1
}
compose exec -T valkey valkey-cli REPLICAOF "$promoted_master" "$sentinel_port" >/dev/null
wait_for_command 60 old_master_rejoined || {
  echo "旧 master 重启后未作为副本重新加入" >&2
  exit 1
}
wait_for_command 60 old_master_caught_up || {
  echo "旧 master 重新加入后未追平切换后的写入" >&2
  exit 1
}

echo "PASS: Sentinel 自动切主、切换期间数据保留/写入、旧 master 重新加入并追平。"
