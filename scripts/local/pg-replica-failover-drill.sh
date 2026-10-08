#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${COMPOSE_ENV_FILE:-$ROOT/.env.local}"
PROJECT="bfs-pg-ha-drill-$(date -u +%Y%m%d%H%M%S)-$$"
export COMPOSE_PROJECT_NAME="$PROJECT"
RUN_ID="pg-ha-$(date -u +%Y%m%d%H%M%S)-$$"
REJOINED=0
# shellcheck source=../lib/destructive-ops.sh
source "$ROOT/scripts/lib/destructive-ops.sh"
COMPOSE_FILES=(
  --project-name "$PROJECT"
  --env-file "$ENV_FILE"
  --profile replica
  -f "$ROOT/docker-compose.yml"
  -f "$ROOT/deploy/docker/compose/pg-failover-isolated.yml"
)

[[ -f "$ENV_FILE" ]] || { echo "Compose env 文件不存在: $ENV_FILE" >&2; exit 2; }
[[ "$PROJECT" == bfs-pg-ha-drill-* ]] || { echo "拒绝使用非隔离 Compose project" >&2; exit 2; }
batch_require_local_docker_context

compose() { docker compose "${COMPOSE_FILES[@]}" "$@"; }
compose_rejoin() {
  docker compose "${COMPOSE_FILES[@]}" \
    -f "$ROOT/deploy/docker/compose/pg-failover-rejoin.yml" "$@"
}

cleanup() {
  local status=$?
  trap - EXIT
  if ((status != 0)); then
    compose_rejoin logs --no-color postgres-primary postgres-replica >&2 || true
  fi
  compose_rejoin down --volumes --remove-orphans
  # 重连覆盖层替换了 primary 数据卷定义；再用初始 profile 模型回收最初创建的卷。
  compose down --volumes --remove-orphans
  exit "$status"
}
trap cleanup EXIT

query() {
  local service="$1" sql_file="$2" phase="${3:-}"
  local -a command=(exec -T "$service" sh -ec \
    'exec psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atq -v run_id="$1" -v phase="$2" -f -' \
    sh "$RUN_ID" "$phase")
  if ((REJOINED == 1)); then
    compose_rejoin "${command[@]}" <"$sql_file"
  else
    compose "${command[@]}" <"$sql_file"
  fi
}

wait_for_sql() {
  local service="$1" expected="$2" sql_file="$3" phase="${4:-}" timeout_seconds="${5:-60}"
  local deadline=$((SECONDS + timeout_seconds)) actual
  while ((SECONDS < deadline)); do
    actual="$(query "$service" "$sql_file" "$phase" 2>/dev/null || true)"
    if [[ "$actual" == "$expected" ]]; then
      return 0
    fi
    sleep 2
  done
  echo "等待 SQL 条件超时: service=$service expected=$expected actual=${actual:-empty}" >&2
  return 1
}

echo "启动隔离 PG replica profile 演练栈: $PROJECT"
compose up -d postgres-primary postgres-replica
wait_for_sql postgres-primary t "$ROOT/scripts/local/sql/pg-ha-drill/is-primary.sql" "" 120
wait_for_sql postgres-replica t \
  "$ROOT/scripts/local/sql/pg-ha-drill/is-streaming-standby.sql" "" 180

query postgres-primary "$ROOT/scripts/local/sql/pg-ha-drill/create-marker-table.sql" >/dev/null
query postgres-primary "$ROOT/scripts/local/sql/pg-ha-drill/insert-marker.sql" replicated >/dev/null
wait_for_sql postgres-replica 1 \
  "$ROOT/scripts/local/sql/pg-ha-drill/count-marker.sql" replicated 60
echo "流复制已追平，演练标记已在 standby 可见。停止隔离 primary 并提升 standby。"

compose stop postgres-primary >/dev/null
wait_for_sql postgres-replica t "$ROOT/scripts/local/sql/pg-ha-drill/promote-standby.sql" "" 90
wait_for_sql postgres-replica t "$ROOT/scripts/local/sql/pg-ha-drill/is-primary.sql" "" 30
query postgres-replica "$ROOT/scripts/local/sql/pg-ha-drill/insert-marker.sql" promoted-write >/dev/null
wait_for_sql postgres-replica 1 \
  "$ROOT/scripts/local/sql/pg-ha-drill/count-marker.sql" promoted-write 10
echo "standby 已提升为可写主库，提升后写入验证通过。"

echo "使用独立空卷从 promoted replica 重建旧 primary 为 standby。"
REJOINED=1
compose_rejoin up -d --force-recreate postgres-primary
wait_for_sql postgres-primary t \
  "$ROOT/scripts/local/sql/pg-ha-drill/is-streaming-standby.sql" "" 180
wait_for_sql postgres-primary 1 \
  "$ROOT/scripts/local/sql/pg-ha-drill/count-marker.sql" promoted-write 60

echo "PASS: standby 流复制、晋升后写入、旧主隔离重建并重新追平。"
echo "演练 project 和卷将在退出时删除；项目专属名称: $PROJECT"
