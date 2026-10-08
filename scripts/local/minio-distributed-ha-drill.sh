#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${COMPOSE_ENV_FILE:-$ROOT/.env.local}"
PROJECT="bfs-minio-ha-drill-$(date -u +%Y%m%d%H%M%S)-$$"
export COMPOSE_PROJECT_NAME="$PROJECT"
BUCKET="ha-drill-$(date -u +%Y%m%d%H%M%S)-$$"
OBJECT="failover/continuity.txt"
BEFORE_MARKER="before-$PROJECT"
AFTER_MARKER="after-$PROJECT"
# shellcheck source=../lib/destructive-ops.sh
source "$ROOT/scripts/lib/destructive-ops.sh"

[[ -f "$ENV_FILE" ]] || { echo "Compose env 文件不存在: $ENV_FILE" >&2; exit 2; }
[[ "$PROJECT" == bfs-minio-ha-drill-* ]] || { echo "拒绝使用非隔离 Compose project" >&2; exit 2; }
batch_require_local_docker_context

compose() {
  docker compose --project-name "$PROJECT" --env-file "$ENV_FILE" \
    -f "$ROOT/docker-compose.minio-ha.yml" "$@"
}

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

set_alias() {
  local endpoint="$1"
  compose exec -T minio-ha-client sh -ec \
    'mc alias set local "$1" "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null' \
    sh "http://${endpoint}:9000"
}

wait_for_node() {
  local node="$1" timeout_seconds="$2"
  local deadline=$((SECONDS + timeout_seconds))
  until compose exec -T minio-ha-client curl -fsS \
      "http://${node}:9000/minio/health/live" >/dev/null 2>&1; do
    ((SECONDS < deadline)) || return 1
    sleep 3
  done
}

wait_for_cluster() {
  local timeout_seconds="$1"
  local deadline=$((SECONDS + timeout_seconds))
  until compose exec -T minio-ha-client mc ready local >/dev/null 2>&1; do
    ((SECONDS < deadline)) || return 1
    sleep 3
  done
}

write_object() {
  local marker="$1"
  compose exec -T minio-ha-client sh -ec \
    'printf "%s" "$1" | mc pipe "$2"' sh "$marker" "local/$BUCKET/$OBJECT" >/dev/null
}

read_object() {
  compose exec -T minio-ha-client mc cat "local/$BUCKET/$OBJECT" | tr -d '\r'
}

echo "启动隔离 MinIO 分布式纠删码演练栈: $PROJECT"
compose up -d minio-ha-1 minio-ha-2 minio-ha-3 minio-ha-4 minio-ha-client
wait_for_node minio-ha-1 180 || {
  echo "MinIO 初始节点未在 180 秒内就绪" >&2
  exit 1
}
set_alias minio-ha-1
wait_for_cluster 180 || {
  echo "MinIO 分布式集群未在 180 秒内达到读写 quorum" >&2
  exit 1
}

compose exec -T minio-ha-client mc mb --ignore-existing "local/$BUCKET" >/dev/null
write_object "$BEFORE_MARKER"
observed="$(read_object)"
[[ "$observed" == "$BEFORE_MARKER" ]] || {
  echo "节点故障前对象校验失败" >&2
  exit 1
}
echo "4 节点集群已就绪，故障前对象写入与读取通过。停止 minio-ha-1。"

compose stop minio-ha-1 >/dev/null
wait_for_node minio-ha-2 60 || {
  echo "存活节点 minio-ha-2 不可用" >&2
  exit 1
}
set_alias minio-ha-2
wait_for_cluster 120 || {
  echo "损失 1 个节点后 MinIO 未在 120 秒内恢复读写 quorum" >&2
  exit 1
}
observed="$(read_object)"
[[ "$observed" == "$BEFORE_MARKER" ]] || {
  echo "单节点故障期间原对象不可读或内容不一致" >&2
  exit 1
}
write_object "$AFTER_MARKER"
observed="$(read_object)"
[[ "$observed" == "$AFTER_MARKER" ]] || {
  echo "单节点故障期间新对象写入校验失败" >&2
  exit 1
}
echo "单节点故障期间通过存活节点读写和内容校验。恢复 minio-ha-1。"

compose start minio-ha-1 >/dev/null
wait_for_node minio-ha-1 180 || {
  echo "恢复节点 minio-ha-1 未在 180 秒内重新就绪" >&2
  exit 1
}
set_alias minio-ha-1
wait_for_cluster 180 || {
  echo "恢复节点后 MinIO 未在 180 秒内恢复读写 quorum" >&2
  exit 1
}
observed="$(read_object)"
[[ "$observed" == "$AFTER_MARKER" ]] || {
  echo "节点恢复后对象内容不一致" >&2
  exit 1
}
write_object "rejoined-$PROJECT"
echo "PASS: MinIO 4 节点 EC:2 单节点故障期间读写成功，节点恢复后对象一致且可写。"
echo "演练 project 和专用卷将在退出时删除；project: $PROJECT"
